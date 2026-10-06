import { useId, useState } from "react";
import { PlusIcon, Undo2Icon, XIcon } from "lucide-react";
import type { FacetInfo, FacetValueInfo, ItemFacet, SourceInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { useFacets } from "@/facet/FacetFilters";
import { cn } from "@/lib/utils";
import { EditButton } from "./FieldEditor";
import type { Corrections } from "./useCorrections";

/**
 * Every facet of the item, the user's corrections included: a value the user added is outlined,
 * one the user took away is crossed out. In editing, a site's value can be taken away, a
 * correction taken back, and a value of any facet of the source added.
 */
export function FacetCorrections({
  typeId,
  source,
  facets,
  corrections,
}: {
  typeId: string;
  source?: SourceInfo;
  facets: ItemFacet[];
  corrections: Corrections;
}) {
  const [editing, setEditing] = useState(false);
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center gap-1">
        <h4 className="text-sm font-semibold">Признаки</h4>
        <EditButton onClick={() => setEditing(!editing)} title={editing ? "Закончить исправления" : "Исправить признаки"} />
      </div>
      {facets.length === 0 && !editing && <div className="text-sm text-muted-foreground">—</div>}
      {facets.map((f) => (
        <div key={f.facet}>
          <div className="mb-1 text-xs text-muted-foreground">{f.label}</div>
          <div className="flex flex-wrap gap-1">
            {f.values.map((v) => (
              <FacetValue key={v.key} facet={f.facet} value={v} editing={editing} corrections={corrections} />
            ))}
          </div>
        </div>
      ))}
      {editing && source && <AddFacetValue typeId={typeId} facets={source.facets} corrections={corrections} />}
    </section>
  );
}

function FacetValue({ facet, value, editing, corrections }: { facet: string; value: FacetValueInfo; editing: boolean; corrections: Corrections }) {
  return (
    <Badge
      variant={value.corrected === "ADDED" ? "outline" : "secondary"}
      className={cn(
        "font-normal",
        value.corrected === "REMOVED" && "text-muted-foreground line-through",
        value.corrected === "ADDED" && "border-dashed border-primary/50",
        editing && "pr-0.5",
      )}
      title={value.corrected === "REMOVED" ? "Убрано вами" : value.corrected === "ADDED" ? "Добавлено вами" : undefined}
    >
      {value.name}
      {editing &&
        (value.corrected ? (
          <AsyncButton
            variant="ghost"
            size="icon-xs"
            className="size-4 rounded-sm"
            title={value.corrected === "ADDED" ? "Убрать добавленное" : "Вернуть как на сайте"}
            onClick={() => corrections.resetFacet(facet, value.key)}
            icon={value.corrected === "ADDED" ? <XIcon className="size-3" /> : <Undo2Icon className="size-3" />}
          />
        ) : (
          <AsyncButton
            variant="ghost"
            size="icon-xs"
            className="size-4 rounded-sm"
            title="Убрать: значение сайта неверно"
            onClick={() => corrections.setFacet(facet, value.key, false)}
            icon={<XIcon className="size-3" />}
          />
        ))}
    </Badge>
  );
}

/** A made-up key for a value the user names: the name in lower case, spaces as hyphens. */
function keyOf(name: string): string {
  return name.trim().toLowerCase().replace(/\s+/g, "-");
}

/**
 * Adding a value: the facet from the source's facets, the value typed — one the filters know is
 * offered as it is typed and taken with its key; a new one gets a key made of its name.
 */
function AddFacetValue({ typeId, facets, corrections }: { typeId: string; facets: FacetInfo[]; corrections: Corrections }) {
  const [facetKey, setFacetKey] = useState(facets[0]?.key ?? "");
  const [name, setName] = useState("");
  const listId = useId();
  const known = useFacets(typeId);
  if (facets.length === 0) return null;
  const facet = facets.find((f) => f.key === facetKey) ?? facets[0];
  const values = known.data?.find((f) => f.id === facet.id)?.values ?? [];
  const typed = name.trim();
  const existing = values.find((v) => v.name.toLowerCase() === typed.toLowerCase() || v.key === typed);

  const add = async () => {
    if (existing) await corrections.setFacet(facet.key, existing.key, true, existing.name);
    else await corrections.setFacet(facet.key, keyOf(typed), true, typed);
    setName("");
  };

  return (
    <div className="flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="text-xs text-muted-foreground">Добавить значение</div>
      <select
        value={facet.key}
        onChange={(e) => setFacetKey(e.target.value)}
        className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring dark:bg-input/30"
      >
        {facets.map((f) => (
          <option key={f.key} value={f.key}>
            {f.label}
          </option>
        ))}
      </select>
      <div className="flex gap-1">
        <Input
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && typed && add().catch(() => {})}
          list={listId}
          placeholder={values.length ? "Начните вводить…" : "Название"}
          className="h-8"
        />
        <datalist id={listId}>
          {values.map((v) => (
            <option key={v.key} value={v.name} />
          ))}
        </datalist>
        <AsyncButton size="icon" variant="outline" onClick={add} disabled={!typed} title={existing ? "Добавить" : "Добавить новое значение"} icon={<PlusIcon />} />
      </div>
      {typed && !existing && values.length > 0 && <div className="text-xs text-muted-foreground">Новое значение: такого ещё нет ни у одной работы</div>}
    </div>
  );
}
