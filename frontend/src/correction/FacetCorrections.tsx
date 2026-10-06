import { useEffect, useId, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { api, unwrap, type FacetValueInfo, type ItemFacet, type ItemRef, type SourceInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ChanceMark, INFERRED_BORDER, InferredMark, unlikely } from "@/facet/FacetValueMarks";
import { cn } from "@/lib/utils";
import { SuggestedValues } from "@/suggestion/Suggestions";
import { useSuggestions } from "@/suggestion/useSuggestions";
import { FacetAnswers } from "./FacetAnswers";
import { EditButton } from "./FieldEditor";
import type { Corrections } from "./useCorrections";

/** How many values of a facet the line of a new value offers. */
const OFFERED = 30;

/**
 * Every facet of the item, the user's corrections included. Above the values of a facet the site
 * writes as one line, that line as the site has it. Each value says how likely the model finds it
 * (until the user answers on it) and takes the user's ✓ or ✕; a value the user added is outlined,
 * one the model worked out dotted, one the user took away crossed out. In editing a value of any
 * facet of the source can be added. Below them the application's suggestions to answer.
 */
export function FacetCorrections({
  item,
  source,
  facets,
  corrections,
}: {
  item: ItemRef;
  source?: SourceInfo;
  facets: ItemFacet[];
  corrections: Corrections;
}) {
  const [editing, setEditing] = useState(false);
  const suggestions = useSuggestions(item, source);
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center gap-1">
        <h4 className="text-sm font-semibold">Признаки</h4>
        <EditButton onClick={() => setEditing(!editing)} title={editing ? "Закончить добавление" : "Добавить значение признака"} />
      </div>
      {facets.length === 0 && !editing && <div className="text-sm text-muted-foreground">—</div>}
      {facets.map((f) => (
        <div key={f.facet}>
          <div className="mb-1 text-xs text-muted-foreground">{f.label}</div>
          {f.original && <div className="mb-1 text-xs break-words text-muted-foreground/80">На сайте: {f.original}</div>}
          <div className="flex flex-wrap gap-1">
            {f.values.map((v) => (
              <FacetValue key={v.key} facet={f.facet} value={v} corrections={corrections} />
            ))}
          </div>
        </div>
      ))}
      {editing && source && <AddFacetValue source={source} corrections={corrections} />}
      {suggestions.data && <SuggestedValues suggestions={suggestions.data} corrections={corrections} />}
    </section>
  );
}

const CORRECTED_TITLE: Record<NonNullable<FacetValueInfo["corrected"]>, string> = {
  ADDED: "Добавлено вами",
  CONFIRMED: "Подтверждено вами",
  REMOVED: "Убрано вами",
};

function FacetValue({ facet, value, corrections }: { facet: string; value: FacetValueInfo; corrections: Corrections }) {
  return (
    <Badge
      variant={value.corrected === "ADDED" ? "outline" : "secondary"}
      className={cn(
        "pr-0.5 font-normal",
        value.inferred && INFERRED_BORDER,
        value.corrected === "REMOVED" && "text-muted-foreground",
        value.corrected === "ADDED" && "border-dashed border-primary/50",
        unlikely(value) && "border-maybe/60",
      )}
      title={value.corrected ? CORRECTED_TITLE[value.corrected] : undefined}
    >
      {value.inferred && <InferredMark />}
      <span className={cn(value.corrected === "REMOVED" && "line-through")}>{value.name}</span>
      <ChanceMark value={value} />
      <FacetAnswers facet={facet} value={value} corrections={corrections} />
    </Badge>
  );
}

/**
 * Adding a value: the facet from the source's facets, the value typed — the facet's values the
 * site or the user gave are offered as it is typed and one picked is taken with its key; a new
 * one is sent by its name alone, and the backend makes its key.
 */
function AddFacetValue({ source, corrections }: { source: SourceInfo; corrections: Corrections }) {
  const facets = source.facets;
  const [facetKey, setFacetKey] = useState(facets[0]?.key ?? "");
  const [name, setName] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const t = setTimeout(() => setQuery(name.trim()), 250);
    return () => clearTimeout(t);
  }, [name]);
  const listId = useId();
  const facet = facets.find((f) => f.key === facetKey) ?? facets[0];
  const known = useQuery({
    queryKey: ["facet-values", source.id, facet?.key, query],
    enabled: facet !== undefined,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/sources/{source}/facets/{facet}/values", {
          params: { path: { source: source.id, facet: facet!.key }, query: { query: query || undefined, limit: OFFERED } },
        }),
      ),
    // The offers do not blink away at every letter
    placeholderData: keepPreviousData,
  });
  if (!facet) return null;
  const values = known.data ?? [];
  const typed = name.trim();
  // The values kept from another facet only stand in while its own come: never taken by key
  const existing = known.isPlaceholderData ? undefined : values.find((v) => v.name.toLowerCase() === typed.toLowerCase());
  const isNew = typed !== "" && query === typed && known.isSuccess && !known.isPlaceholderData && !known.isFetching && !existing;

  const add = async () => {
    await corrections.setFacet(facet.key, existing ? { key: existing.key } : { name: typed }, true);
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
          placeholder="Начните вводить…"
          className="h-8"
        />
        <datalist id={listId}>
          {values.map((v) => (
            <option key={v.key} value={v.name} />
          ))}
        </datalist>
        <AsyncButton size="icon" variant="outline" onClick={add} disabled={!typed} title={existing ? "Добавить" : "Добавить новое значение"} icon={<PlusIcon />} />
      </div>
      {isNew && <div className="text-xs text-muted-foreground">Новое значение: такого ещё нет ни у одной работы</div>}
    </div>
  );
}
