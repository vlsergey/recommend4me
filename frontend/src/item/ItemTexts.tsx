import { useState } from "react";
import { ChevronDownIcon, PlusIcon } from "lucide-react";
import type { SourceInfo, TextValue } from "@/api/client";
import { Button } from "@/components/ui/button";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { textField, type Corrections } from "@/correction/useCorrections";
import { cn } from "@/lib/utils";

/**
 * The texts of the item — overview, annotation, changelog… — spoilers collapsed. Each can be
 * corrected; a text the source declares but the item lacks can be written.
 */
export function ItemTexts({ texts, source, corrections }: { texts: TextValue[]; source?: SourceInfo; corrections: Corrections }) {
  const [adding, setAdding] = useState<string | null>(null);
  // A facet's line as the site writes it is shown with the facet, not among the texts
  const facetLines = new Set((source?.facets ?? []).map((f) => f.original));
  const missing = (source?.texts ?? []).filter((t) => !texts.some((v) => v.key === t.key) && !facetLines.has(t.key));
  const added = missing.find((t) => t.key === adding);
  return (
    <>
      {texts.map((t, i) => (
        <ItemText key={t.key} text={t} accent={i === 0 && texts.length > 2} corrections={corrections} />
      ))}
      {added && (
        <section>
          <h4 className="mb-1 text-sm font-semibold">{added.label}</h4>
          <FieldEditor
            value=""
            multiline
            corrected={false}
            onSave={(content) => corrections.setField(textField(added.key), content)}
            onReset={async () => {}}
            onClose={() => setAdding(null)}
          />
        </section>
      )}
      {missing.length > 0 && !added && (
        <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
          {missing.map((t) => (
            <Button key={t.key} variant="ghost" size="xs" className="text-muted-foreground" onClick={() => setAdding(t.key)}>
              <PlusIcon /> {t.label}
            </Button>
          ))}
        </div>
      )}
    </>
  );
}

function ItemText({ text, accent, corrections }: { text: TextValue; accent: boolean; corrections: Corrections }) {
  const [open, setOpen] = useState(!text.spoiler);
  const [editing, setEditing] = useState(false);
  const field = textField(text.key);
  return (
    <section className={cn("group/text", accent && "rounded-lg border-l-4 border-primary/60 bg-muted/40 p-3")}>
      <div className={cn("flex items-center gap-1", (open || editing) && "mb-1")}>
        {text.spoiler ? (
          <button className="inline-flex items-center gap-1 text-sm font-semibold hover:underline" onClick={() => setOpen(!open)}>
            <ChevronDownIcon className={open ? "size-4" : "size-4 -rotate-90"} />
            {text.label}
            <span className="font-normal text-muted-foreground">· спойлер</span>
          </button>
        ) : (
          <h4 className="text-sm font-semibold">{text.label}</h4>
        )}
        {text.corrected && <CorrectedMark />}
        {!editing && (
          <EditButton
            onClick={() => setEditing(true)}
            title="Исправить текст"
            className="opacity-0 transition-opacity group-hover/text:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100"
          />
        )}
      </div>
      {editing ? (
        <FieldEditor
          value={text.content}
          multiline
          corrected={text.corrected}
          onSave={(content) => corrections.setField(field, content)}
          onReset={() => corrections.resetField(field)}
          onClose={() => setEditing(false)}
        />
      ) : (
        open && <p className="text-sm leading-relaxed whitespace-pre-line text-foreground/90">{text.content}</p>
      )}
    </section>
  );
}
