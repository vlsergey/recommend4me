"""Export the image tower of SigLIP2 NaFlex into the models folder as an ONNX graph.

    PYTHONIOENCODING=utf-8 ONNX_EXTRA=<dir with onnx, onnxscript, onnx_ir, ml_dtypes, onnxruntime, protobuf> python tools/export_siglip2_naflex.py

Needs torch and transformers; the ONNX packages are installed into a directory of their own
(`pip install --target <dir> --no-deps onnx onnxscript onnx_ir ml_dtypes onnxruntime protobuf
flatbuffers coloredlogs humanfriendly sympy mpmath`) named by ONNX_EXTRA, so the Python
environment stays as it was — as local-ai-chroma-enf-lora's tools/export_onnx.py does it.
PYTHONIOENCODING=utf-8 matters on Windows: the exporter prints emoji, and in the console's code
page that print is an exception.

ONLY THE DYNAMO EXPORTER: the TorchScript one traces the pooling head's reshapes with the sizes
of the sample batch baked in, and the graph then fails on any other number of pictures or patches.

NAFLEX KEEPS THE ASPECT RATIO: a picture is resized, proportions kept, to at most 256 patches of
16×16 and fed as a sequence of patches with a mask, instead of being squeezed into a square.

THE GRAPH STARTS AFTER THE POSITIONS. The positional embeddings are a 16×16 grid resized to the
patch grid of each picture (antialiased bilinear) — a resize whose size is the data, which an
ONNX graph does not express. The application does that resize itself (Resample.f32, the same
arithmetic as torch's antialiased interpolation) from the grid written here, and the graph takes

    patches   float [B, N, 768]   pixels of each 16×16 patch, (y, x, rgb), scaled to −1…1
    positions float [B, N, 1152]  the resized positional embeddings, padding rows anything
    mask      int64 [B, N]        1 for real patches, 0 for padding

and gives `vector` float [B, 1152], the pooled embedding of unit length.

Files written (into %LOCALAPPDATA%/recommend4me/models, or RECOMMEND4ME_MODELS): siglip2_naflex.onnx (+ .data), models/siglip2_naflex_positions.bin
(16×16×1152 float32 little-endian), and siglip2_naflex_check/ — two pictures of different
proportions with the vectors the reference pipeline (transformers' PIL processor + model) gives,
for the application's test.
"""
import json
import os
import sys

extra = os.environ.get("ONNX_EXTRA")
if extra:
    sys.path.insert(0, extra)

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image
from transformers import Siglip2ImageProcessor, Siglip2Model

OUT = os.environ.get("RECOMMEND4ME_MODELS") or os.path.join(
    os.environ.get("LOCALAPPDATA") or os.path.expanduser("~/.local/share"), "recommend4me", "models")
CHECK = os.path.join(OUT, "siglip2_naflex_check")
os.makedirs(CHECK, exist_ok=True)
MODEL_ID = "google/siglip2-so400m-patch16-naflex"
MAX_PATCHES = 256

# The checkpoint holds both towers; only the image one is exported
vision = Siglip2Model.from_pretrained(MODEL_ID, attn_implementation="eager").eval().vision_model
processor = Siglip2ImageProcessor.from_pretrained(MODEL_ID, max_num_patches=MAX_PATCHES)

grid = vision.embeddings.position_embedding.weight.detach().float()  # [256, 1152]
side = vision.embeddings.position_embedding_size
grid.numpy().astype("<f4").tofile(os.path.join(OUT, "siglip2_naflex_positions.bin"))
print("positions:", side, "x", side, "x", grid.shape[1])


class Tower(torch.nn.Module):
    """Everything after the positional resize: patch projection, encoder, layer norm, pooling head."""

    def __init__(self, v):
        super().__init__()
        self.v = v

    def forward(self, patches, positions, mask):
        h = self.v.embeddings.patch_embedding(patches) + positions
        keep = mask.to(h.dtype)
        # Additive mask over the keys: padding patches are seen by nobody
        additive = (1.0 - keep)[:, None, None, :] * torch.finfo(h.dtype).min
        h = self.v.encoder(inputs_embeds=h, attention_mask=additive).last_hidden_state
        h = self.v.post_layernorm(h)
        head = self.v.head
        probe = head.probe.repeat(h.shape[0], 1, 1)
        pool_mask = additive[:, :, :1, :].repeat(1, head.num_heads, 1, 1).reshape(-1, 1, h.shape[1])
        p = head.attention(probe, h, h, attn_mask=pool_mask)[0]
        p = p + head.mlp(head.layernorm(p))
        return F.normalize(p[:, 0], dim=-1)


def positions_for(shapes, length):
    """The resize the application does: the grid to every picture's patch grid, padding with row 0."""
    g = grid.reshape(side, side, -1).permute(2, 0, 1).unsqueeze(0)
    out = torch.zeros(len(shapes), length, grid.shape[1])
    for i, (h, w) in enumerate(shapes):
        r = F.interpolate(g, size=(h, w), mode="bilinear", align_corners=False, antialias=True)
        r = r.reshape(grid.shape[1], h * w).transpose(0, 1)
        out[i, : h * w] = r
        out[i, h * w :] = r[0]
    return out


# Two pictures of different proportions, deterministic, kept for the application's test
rng = np.random.default_rng(0)
pictures = []
for name, (w, h) in (("wide.png", (640, 360)), ("tall.png", (300, 800))):
    yy, xx = np.mgrid[0:h, 0:w]
    rgb = np.stack([(xx * 255 // w), (yy * 255 // h), ((xx + yy) * 7) % 256], axis=-1).astype(np.uint8)
    rgb = np.clip(rgb.astype(int) + rng.integers(-20, 20, rgb.shape), 0, 255).astype(np.uint8)
    path = os.path.join(CHECK, name)
    Image.fromarray(rgb).save(path)
    pictures.append((name, Image.open(path).convert("RGB")))

enc = processor(images=[p for _, p in pictures], return_tensors="pt")
with torch.inference_mode():
    reference = vision(
        pixel_values=enc["pixel_values"],
        attention_mask=enc["pixel_attention_mask"],
        spatial_shapes=enc["spatial_shapes"],
    ).pooler_output
    reference = F.normalize(reference, dim=-1)
    shapes = enc["spatial_shapes"].tolist()
    mine = Tower(vision)(enc["pixel_values"], positions_for(shapes, enc["pixel_values"].shape[1]), enc["pixel_attention_mask"].long())
print("tower vs model:", F.cosine_similarity(mine, reference).tolist())

path = os.path.join(OUT, "siglip2_naflex.onnx")
args = (enc["pixel_values"], positions_for(shapes, enc["pixel_values"].shape[1]), enc["pixel_attention_mask"].long())
batch = torch.export.Dim("batch", min=1, max=64)
length = torch.export.Dim("length", min=2, max=MAX_PATCHES)
torch.onnx.export(
    Tower(vision), args, path,
    input_names=["patches", "positions", "mask"], output_names=["vector"],
    dynamic_shapes={"patches": {0: batch, 1: length}, "positions": {0: batch, 1: length}, "mask": {0: batch, 1: length}},
    opset_version=18, external_data=True, dynamo=True,
)

import onnxruntime as ort

session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
got = session.run(None, {"patches": args[0].numpy(), "positions": args[1].numpy(), "mask": args[2].numpy()})[0]
print("onnx vs model:", [float(np.dot(got[i], reference[i].numpy())) for i in range(len(got))])

# Other sizes than the sample's: one picture, a shorter sequence — the graph must not care
one = enc["pixel_values"][:1, :100]
alone = session.run(None, {
    "patches": one.numpy(),
    "positions": positions_for(shapes[:1], 100).numpy(),
    "mask": enc["pixel_attention_mask"][:1, :100].long().numpy(),
})[0]
print("one picture, 100 patches: shape", alone.shape)

with open(os.path.join(CHECK, "vectors.json"), "w", encoding="utf-8") as fh:
    json.dump({name: reference[i].tolist() for i, (name, _) in enumerate(pictures)} | {"spatial_shapes": shapes}, fh)
print("done")
