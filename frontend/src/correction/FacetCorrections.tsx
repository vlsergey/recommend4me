import { useEffect, useId, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { PlusIcon, Undo2Icon, XIcon } from "lucide-react";
import { api, unwrap, type FacetValueInfo, type ItemFacet, type ItemRef, type SourceInfo, type SuggestedValue } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { DoubtMark, SuggestedValues } from "@/suggestion/Suggestions";
import { suggestionsOf, useSuggestions } from "@/suggestion/useSuggestions";
import { EditButton } from "./FieldEditor";
import type { Corrections } from "./useCorrections";

/** How many values of a facet the line of a new value offers. */
const OFFERED = 30;

/**
 * Every facet of the item, the user's corrections included: a value the user added is outlined,
 * one the user took away is crossed out. In editing, a site's value can be taken away, a
 * correction taken back, and a value of any facet of the source added. Below them the
 * application's suggestions to answer; a site's value it doubts is marked on its chip.
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
        <EditButton onClick={() => setEditing(!editing)} title={editing ? "Закончить исправления" : "Исправить признаки"} />
      </div>
      {facets.length === 0 && !editing && <div className="text-sm text-muted-foreground">—</div>}
      {facets.map((f) => (
        <div key={f.facet}>
          <div className="mb-1 text-xs text-muted-foreground">{f.label}</div>
          <div className="flex flex-wrap gap-1">
            {f.values.map((v) => (
              <FacetValue
                key={v.key}
                facet={f.facet}
                value={v}
                doubt={suggestionsOf(suggestions.data, f.facet)?.doubted.find((d) => d.key === v.key)}
                editing={editing}
                corrections={corrections}
              />
            ))}
          </div>
        </div>
      ))}
      {editing && source && <AddFacetValue source={source} corrections={corrections} />}
      {suggestions.data && <SuggestedValues suggestions={suggestions.data} corrections={corrections} />}
    </section>
  );
}

function FacetValue({
  facet,
  value,
  doubt,
  editing,
  corrections,
}: {
  facet: string;
  value: FacetValueInfo;
  /** The application doubts the site's value: it does not look like the work. */
  doubt?: SuggestedValue;
  editing: boolean;
  corrections: Corrections;
}) {
  // A value the user already answered for is not in doubt any more
  const doubted = doubt !== undefined && !value.corrected;
  return (
    <Badge
      variant={value.corrected === "ADDED" ? "outline" : "secondary"}
      className={cn(
        "font-normal",
        value.corrected === "REMOVED" && "text-muted-foreground line-through",
        value.corrected === "ADDED" && "border-dashed border-primary/50",
        doubted && "border-maybe/60",
        (editing || doubted) && "pr-0.5",
      )}
      title={value.corrected === "REMOVED" ? "Убрано вами" : value.corrected === "ADDED" ? "Добавлено вами" : undefined}
    >
      {value.name}
      {doubted ? (
        <DoubtMark facet={facet} value={doubt} corrections={corrections} />
      ) : (
        editing &&
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
            onClick={() => corrections.setFacet(facet, { key: value.key }, false)}
            icon={<XIcon className="size-3" />}
          />
        ))
      )}
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
