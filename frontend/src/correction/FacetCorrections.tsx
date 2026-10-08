import { useEffect, useId, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CheckIcon, PlusIcon } from "lucide-react";
import { api, unwrap, type FacetValueInfo, type ItemFacet, type ItemRef, type SourceInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ChanceMark, unlikely } from "@/facet/FacetValueMarks";
import { decided, useStableOrder } from "@/facet/valueOrder";
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
 * facet of the source can be added. Below them the application's suggestions to answer. The
 * facets of [except] are answered elsewhere: neither their values, nor their suggestions, nor
 * the adding of them are here.
 */
export function FacetCorrections({
  item,
  source,
  facets: allFacets,
  corrections,
  except = [],
}: {
  item: ItemRef;
  source?: SourceInfo;
  facets: ItemFacet[];
  corrections: Corrections;
  except?: readonly string[];
}) {
  const [editing, setEditing] = useState(false);
  const suggestions = useSuggestions(item, source);
  const facets = allFacets.filter((f) => !except.includes(f.facet));
  // The application's layer is corrected; the site's facets are its word, shown as they are
  const layer = facets.filter((f) => f.editable);
  const site = facets.filter((f) => !f.editable);
  const suggested = suggestions.data?.filter((s) => !except.includes(s.facet));
  const addable = source && { ...source, facets: source.facets.filter((f) => f.editable && !except.includes(f.key)) };
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center gap-1">
        <h4 className="text-sm font-semibold">Признаки</h4>
        {addable && addable.facets.length > 0 && (
          <EditButton onClick={() => setEditing(!editing)} title={editing ? "Закончить добавление" : "Добавить значение признака"} />
        )}
      </div>
      {layer.length === 0 && !editing && <div className="text-sm text-muted-foreground">—</div>}
      {layer.map((f) => (
        <FacetLine key={f.facet} facet={f} corrections={corrections} />
      ))}
      {editing && addable && <AddFacetValue source={addable} corrections={corrections} />}
      {suggested && <SuggestedValues suggestions={suggested} corrections={corrections} />}
      {site.length > 0 && (
        <div className="flex flex-col gap-3 border-t pt-3">
          <h4 className="text-sm font-semibold">С сайта</h4>
          {site.map((f) => (
            <FacetLine key={f.facet} facet={f} />
          ))}
        </div>
      )}
    </section>
  );
}

/**
 * A facet of the work with its values, in the order first shown: answered on when [corrections]
 * are given — with "✓ все" for every value shown the user has not answered on — shown as it is
 * when not.
 */
function FacetLine({ facet: f, corrections }: { facet: ItemFacet; corrections?: Corrections }) {
  const values = useStableOrder(f.values);
  const open = f.values.filter((v) => !decided(v));
  const confirmAll = async () => {
    for (const v of open) await corrections!.setFacet(f.facet, { key: v.key }, true);
  };
  return (
    <div>
      <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
        {f.label}
        {corrections && open.length > 1 && (
          <AsyncButton variant="outline" size="xs" className="h-5 px-1.5 text-[11px]" onClick={confirmAll} title="Подтвердить все значения без вашего ответа">
            ✓ все ({open.length})
          </AsyncButton>
        )}
      </div>
      {f.original && <div className="mb-1 text-xs break-words text-muted-foreground/80">На сайте: {f.original}</div>}
      <div className="flex flex-wrap gap-1">
        {values.map((v) => (
          <FacetValue key={v.key} facet={f.facet} value={v} corrections={corrections} />
        ))}
      </div>
    </div>
  );
}

const CORRECTED_TITLE: Record<NonNullable<FacetValueInfo["corrected"]>, string> = {
  ADDED: "Добавлено вами",
  CONFIRMED: "Подтверждено вами",
  REMOVED: "Убрано вами",
};

/**
 * A value of the work's facet, its state told by its look and an icon, never by colour alone:
 *
 * - the user's — confirmed or added: filled, a ✓ before the name, no chance; the word taken back
 *   by the button shown when pointed at;
 * - not answered on: outlined on white, the model's chance in small print, ✓ ✕;
 * - taken away by the user: faded, crossed out, ↺.
 *
 * A value of a facet of the site ([corrections] not given) has no answers.
 */
export function FacetValue({ facet, value, corrections }: { facet: string; value: FacetValueInfo; corrections?: Corrections }) {
  const mine = value.corrected === "CONFIRMED" || value.corrected === "ADDED";
  const removed = value.corrected === "REMOVED";
  const open = corrections && !value.corrected;
  return (
    <Badge
      variant="outline"
      className={cn(
        "group pr-0.5 font-normal",
        mine && "border-yes/50 bg-yes/15 text-foreground",
        removed && "border-dashed text-muted-foreground opacity-70",
        open && "bg-background",
        open && unlikely(value) && "border-maybe/60",
        !corrections && "bg-muted/40",
      )}
      title={value.corrected ? CORRECTED_TITLE[value.corrected] : undefined}
    >
      {mine && <CheckIcon className="size-3 text-yes" aria-label="ваш ответ: есть" />}
      <span className={cn(removed && "line-through")}>{value.name}</span>
      <ChanceMark value={value} />
      {corrections && <FacetAnswers facet={facet} value={value} corrections={corrections} />}
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
