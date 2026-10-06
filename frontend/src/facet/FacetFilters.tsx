import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDownIcon, SearchIcon } from "lucide-react";
import { api, unwrap, type FacetFilter } from "@/api/client";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

/**
 * What is hidden: facet values as "<facet id>=<value key>", and the facets whose works without
 * any of their values are hidden too. Everything else shows — a value a site adds later as well.
 */
export type FacetSelection = { hidden: string[]; hiddenWithout: string[] };

export const NOTHING_HIDDEN: FacetSelection = { hidden: [], hiddenWithout: [] };

export function isFiltered(s: FacetSelection): boolean {
  return s.hidden.length > 0 || s.hiddenWithout.length > 0;
}

export function valueRef(facetId: string, key: string): string {
  return `${facetId}=${key}`;
}

/**
 * A remembered selection without the facets of the old "<source>:<key>" ids (now
 * "<source>.<key>"): the backend knows them no more, and they would hide nothing.
 */
export function validSelection(s: FacetSelection): FacetSelection {
  const current = (facetId: string) => !facetId.includes(":");
  const hidden = s.hidden.filter((ref) => current(ref.slice(0, Math.max(0, ref.indexOf("=")))));
  const hiddenWithout = s.hiddenWithout.filter(current);
  return hidden.length === s.hidden.length && hiddenWithout.length === s.hiddenWithout.length ? s : { hidden, hiddenWithout };
}

/** A list this long gets a line to find a value in. */
const FINDABLE = 15;

export function useFacets(type: string) {
  return useQuery({
    queryKey: ["facets", type],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/facets", { params: { path: { type } } })),
  });
}

/**
 * A drop-down list of checkboxes for every filter facet of the content type, all checked at
 * first. A work shows when, in every facet, it has a checked value — or none of the facet's, with
 * that line checked.
 */
export function FacetFilters({ type, value, onChange }: { type: string; value: FacetSelection; onChange: (v: FacetSelection) => void }) {
  const facets = useFacets(type);
  return (
    <div className="flex flex-wrap items-center gap-1">
      {(facets.data ?? []).map((f) => (
        <FacetDropdown key={f.id} facet={f} value={value} onChange={onChange} />
      ))}
    </div>
  );
}

function FacetDropdown({ facet, value, onChange }: { facet: FacetFilter; value: FacetSelection; onChange: (v: FacetSelection) => void }) {
  const [find, setFind] = useState("");
  const refs = new Set(facet.values.map((v) => valueRef(facet.id, v.key)));
  const hasWithout = facet.noneLabel !== undefined && facet.noneLabel !== "";
  const withoutLabel = facet.noneLabel ?? "";
  const shownValues = facet.values.filter((v) => !value.hidden.includes(valueRef(facet.id, v.key)));
  const withoutShown = !value.hiddenWithout.includes(facet.id);
  const total = facet.values.length + (hasWithout ? 1 : 0);
  const shown = shownValues.length + (hasWithout && withoutShown ? 1 : 0);
  // All shown says nothing; one shown is named; else how many of how many
  const summary =
    shown === total ? null : shown === 0 ? "ничего" : shown === 1 ? (shownValues[0]?.name ?? withoutLabel) : `${shown} из ${total}`;

  // The facet's own choices are replaced, the other facets' kept
  const others = { hidden: value.hidden.filter((r) => !refs.has(r)), hiddenWithout: value.hiddenWithout.filter((f) => f !== facet.id) };
  const toggle = (ref: string, on: boolean) =>
    onChange({ ...value, hidden: on ? value.hidden.filter((r) => r !== ref) : [...value.hidden, ref] });
  const toggleWithout = (on: boolean) => onChange({ ...value, hiddenWithout: on ? others.hiddenWithout : [...others.hiddenWithout, facet.id] });
  const all = () => onChange(others);
  const none = () =>
    onChange({ hidden: [...others.hidden, ...refs], hiddenWithout: hasWithout ? [...others.hiddenWithout, facet.id] : others.hiddenWithout });

  const needle = find.trim().toLowerCase();
  const listed = needle ? facet.values.filter((v) => v.name.toLowerCase().includes(needle)) : facet.values;

  if (facet.values.length === 0 && !hasWithout) return null;
  return (
    <DropdownMenu onOpenChange={(open) => !open && setFind("")}>
      <DropdownMenuTrigger render={<Button variant="outline" size="sm" className={cn(summary && "border-primary/60")} />}>
        <span className="max-w-44 truncate">
          {facet.label}
          {summary && <span className="font-semibold">: {summary}</span>}
        </span>
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="max-h-[70vh] w-auto min-w-56">
        <div className="flex gap-1">
          <DropdownMenuItem className="flex-1 justify-center" onClick={all} closeOnClick={false} disabled={shown === total}>
            Всё
          </DropdownMenuItem>
          <DropdownMenuItem className="flex-1 justify-center" onClick={none} closeOnClick={false} disabled={shown === 0}>
            Ничего
          </DropdownMenuItem>
        </div>
        {facet.values.length > FINDABLE && (
          <div className="relative px-1 py-1">
            <SearchIcon className="pointer-events-none absolute top-1/2 left-3 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <input
              value={find}
              onChange={(e) => setFind(e.target.value)}
              // The menu's own keys (type-ahead, arrows) must not take the letters typed here
              onKeyDown={(e) => e.key !== "Escape" && e.stopPropagation()}
              placeholder="Найти…"
              className="h-7 w-full rounded-md border border-input bg-transparent pr-2 pl-7 text-sm outline-none focus-visible:border-ring"
            />
          </div>
        )}
        <DropdownMenuSeparator />
        {listed.map((v) => {
          const ref = valueRef(facet.id, v.key);
          return (
            <DropdownMenuCheckboxItem key={v.key} checked={!value.hidden.includes(ref)} onCheckedChange={(on) => toggle(ref, on)} closeOnClick={false}>
              <span className="flex-1">{v.name}</span>
              <span className="text-xs tabular-nums text-muted-foreground">{v.items}</span>
            </DropdownMenuCheckboxItem>
          );
        })}
        {hasWithout && !needle && (
          <DropdownMenuCheckboxItem checked={withoutShown} onCheckedChange={toggleWithout} closeOnClick={false}>
            <span className="flex-1 italic">{withoutLabel}</span>
            <span className="text-xs tabular-nums text-muted-foreground">{facet.itemsWithout}</span>
          </DropdownMenuCheckboxItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
