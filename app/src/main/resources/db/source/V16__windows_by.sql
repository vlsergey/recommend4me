-- What the windows of a text were made by: the encoder and the way it cuts a text into them (TextEncoder.windowsId).
-- Windows cut otherwise are made again, the text's vector kept (user, 2026-11-13: bge-m3 cuts a text into scenes of
-- 256 of its pieces overlapping by half, not pages of 512 end to end). Null of the windows made before: made again
ALTER TABLE text_vector ADD COLUMN windows_by VARCHAR(100);
