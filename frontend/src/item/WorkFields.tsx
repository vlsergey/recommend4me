import { useState, type ReactNode } from "react";
import { ChevronDownIcon, PlusIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, ItemDetails, WorkSite } from "@/api/client";
import { Button } from "@/components/ui/button";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { FieldBlock } from "@/correction/FacetCorrections";
import { SiteRow } from "@/correction/SiteWords";
import { numberField, textField, titleField, type Corrections } from "@/correction/useCorrections";
import { number } from "@/i18n/format";
import { label } from "@/i18n/label";

/**
 * One field that is a plain value: what each site has, then the value of the work; its ✎ opening the
 * editor, a corrected one taken back there. A site with no word on it is not listed: the value is
 * given by the user alone.
 */
function PlainField({
  field,
  name,
  rows,
  value,
  corrected,
  multiline,
  numeric,
  onSave,
  onReset,
}: {
  field: string;
  name: string;
  rows: { site: WorkSite; said?: string }[];
  value?: string;
  corrected: boolean;
  multiline?: boolean;
  numeric?: boolean;
  onSave: (content: string) => Promise<unknown>;
  onReset: () => Promise<unknown>;
}) {
  const { t } = useTranslation("item");
  const [editing, setEditing] = useState(false);
  return (
    <FieldBlock
      field={field}
      label={name}
      sources={
        <div className="flex flex-col gap-0.5">
          {rows
            .filter((r) => r.said !== undefined)
            .map(({ site, said }) => (
              <SiteRow key={`${site.source}/${site.item}`} source={site.source} url={site.url}>
                <span className="line-clamp-3 whitespace-pre-line">{said}</span>
              </SiteRow>
            ))}
        </div>
      }
    >
      {editing ? (
        <FieldEditor value={value ?? ""} multiline={multiline} numeric={numeric} corrected={corrected} onSave={onSave} onReset={onReset} onClose={() => setEditing(false)} />
      ) : (
        <div className="flex items-center gap-1 text-sm">
          {corrected && <CorrectedMark />}
          {corrected && <span className="min-w-0 line-clamp-3 whitespace-pre-line">{value}</span>}
          {value === undefined ? (
            <Button variant="ghost" size="xs" className="text-muted-foreground pointer-coarse:h-11" onClick={() => setEditing(true)}>
              <PlusIcon /> {t("WorkFields.give")}
            </Button>
          ) : (
            <EditButton onClick={() => setEditing(true)} title={t("WorkFields.edit")} />
          )}
        </div>
      )}
    </FieldBlock>
  );
}

/** A heading that folds what is under it. */
function Folded({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <section className="flex flex-col gap-3">
      <button className="inline-flex items-center gap-1 border-b pb-0.5 text-left text-sm font-semibold hover:underline" onClick={() => setOpen(!open)} aria-expanded={open}>
        <ChevronDownIcon className={open ? "size-4" : "size-4 -rotate-90"} />
        {title}
      </button>
      {open && children}
    </section>
  );
}

/**
 * THE TITLE, THE TEXTS AND THE NUMBERS AS FIELDS OF THE FORM, folded at its end, being all but never
 * corrected: each with what every site has of it, and a text or a number a site declares and no item
 * has can be given.
 */
export function WorkFields({ type, details: d, corrections }: { type: ContentTypeInfo; details: ItemDetails; corrections: Corrections }) {
  const { t } = useTranslation("item");
  const sources = d.sites.flatMap((s) => type.sources.filter((x) => x.id === s.source));
  const lines = new Set(sources.flatMap((s) => s.facets.map((f) => f.original)));
  const texts = [...new Map(sources.flatMap((s) => s.texts).filter((x) => !lines.has(x.key)).map((x) => [x.key, x.labelKey])).entries()];
  const numbers = [...new Map(sources.flatMap((s) => s.numbers).map((x) => [x.key, x.labelKey])).entries()];
  const first = d.sites[0];
  return (
    <Folded title={t("WorkFields.heading")}>
      {first && (
        <PlainField
          field="title"
          name={t("WorkSites.title")}
          rows={d.sites.map((site) => ({ site, said: site.siteTitle }))}
          value={first.title}
          corrected={first.titleCorrected}
          onSave={(content) => corrections.setField(titleField, content)}
          onReset={() => corrections.resetField(titleField)}
        />
      )}
      {texts.map(([key, labelKey]) => {
        const held = d.sites.flatMap((s) => s.texts).find((x) => x.key === key);
        return (
          <PlainField
            key={key}
            field={`text:${key}`}
            name={label(labelKey)}
            rows={d.sites.map((site) => ({ site, said: site.texts.find((x) => x.key === key)?.siteContent }))}
            value={held?.content}
            corrected={held?.corrected ?? false}
            multiline
            onSave={(content) => corrections.setField(textField(key), content)}
            onReset={() => corrections.resetField(textField(key))}
          />
        );
      })}
      {numbers.map(([key, labelKey]) => {
        const held = d.sites.flatMap((s) => s.numbers).find((x) => x.key === key);
        return (
          <PlainField
            key={key}
            field={`number:${key}`}
            name={label(labelKey)}
            rows={d.sites.map((site) => {
              const said = site.numbers.find((x) => x.key === key)?.siteValue;
              return { site, said: said === undefined || said === null ? undefined : number(said) };
            })}
            value={held?.value?.toString()}
            corrected={held?.corrected ?? false}
            numeric
            onSave={(content) => corrections.setField(numberField(key), content)}
            onReset={() => corrections.resetField(numberField(key))}
          />
        );
      })}
    </Folded>
  );
}
