import { Fragment, useContext, useEffect, useId, useState, type ReactNode } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, PlusIcon, SearchIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type FacetValueInfo, type ItemFacet, type ItemRef, type SourceInfo, type SuggestedValue } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ChanceMark, unlikely } from "@/facet/FacetValueMarks";
import { decided, useLaterOffer, useStableOrder } from "@/facet/valueOrder";
import { label, sourceShort, valueName } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { SuggestedChip } from "@/suggestion/Suggestions";
import { candidatesQuery, useCandidates } from "@/suggestion/useSuggestions";
import { TagNameLink } from "@/tag/TagNameLink";
import { TagsByText } from "@/tagtext/TagsByText";
import { CHIP_TOUCH, FacetAnswers } from "./FacetAnswers";
import { ScaleChoice } from "./ScaleChoice";
import { SiteWords } from "./SiteWords";
import type { Corrections } from "./useCorrections";
import { chipKey, ValueActions } from "./valueActions";
import { ValueMenu, ValueName } from "./ValueMenu";

/** How many values of a facet the line of a new value offers. */
const OFFERED = 30;

/** How many more candidates a press of "…" shows. */
const MORE_STEP = 5;

/**
 * A FIELD OF THE FORM, in the one pattern every field has: its label with its state's tools, the
 * sources right above the place of answering, then that place — [children]. [corrections] with a
 * [facet] give "✓ all" for every value of it the user has not answered on.
 */
export function FieldBlock({
  field,
  label: name,
  facet,
  corrections,
  tools,
  sources,
  children,
}: {
  /** The key the line of fields leads to it by. */
  field: string;
  label: string;
  facet?: ItemFacet;
  corrections?: Corrections;
  tools?: ReactNode;
  /** In place of the sources shown by default, a row of them with its presses. */
  sources?: ReactNode;
  children: ReactNode;
}) {
  const { t } = useTranslation("correction");
  // The work's values the user has not answered on — not those only offered, nor those it has by a tag under them
  const open = facet?.values.filter((v) => !decided(v) && !v.offered && !v.implied) ?? [];
  const confirmAll = async () => {
    for (const v of open) await corrections!.setFacet(facet!.facet, { key: v.key }, true);
  };
  return (
    <section data-field={field} className="flex scroll-mt-24 flex-col gap-1">
      <div className="flex flex-wrap items-center gap-2 text-xs font-medium text-muted-foreground">
        {name}
        {corrections && !facet?.steps?.length && open.length > 1 && (
          <AsyncButton variant="outline" size="xs" className={cn(SMALL_BUTTON, "text-[11px]")} onClick={confirmAll} title={t("FacetLine.confirmAllHint")}>
            {t("FacetLine.confirmAll", { n: open.length })}
          </AsyncButton>
        )}
        {tools}
      </div>
      {sources ?? (facet && <SiteWords facet={facet} />)}
      {children}
    </section>
  );
}

/** A facet of a site, shown as it is, for reading: never answered on. */
export function SiteFacet({ facet: f }: { facet: ItemFacet }) {
  const values = useStableOrder(f.values);
  return (
    <div>
      <div className="mb-1 text-xs text-muted-foreground">{label(f.labelKey)}</div>
      <div className="flex flex-wrap gap-1">
        {values.map((v) => (
          <FacetValue key={v.key} facet={f.facet} value={v} tagLink={f.tags === true} />
        ))}
      </div>
    </div>
  );
}

/**
 * A field of a facet of the work, in the order first shown: the sites' words, the values with their
 * answers, the suggestions, "…" for more candidates of the [item], the search of a value. A press on
 * a site's word opens the search with it typed. A scale is answered on as one, its words no presses.
 */
export function FacetLine({
  facet: f,
  item,
  source,
  corrections,
  suggested = [],
}: {
  facet: ItemFacet;
  item: ItemRef;
  source: SourceInfo;
  corrections: Corrections;
  suggested?: SuggestedValue[];
}) {
  const { t } = useTranslation("correction");
  const values = useStableOrder(f.values);
  const later = useLaterOffer(values);
  const [search, setSearch] = useState<{ word: string } | null>(null);
  // A value of this facet alone found by its name, when the facet takes values the user adds
  const own = source.facets.filter((d) => d.key === f.facet && d.editable);
  const addable = own.length > 0 ? { ...source, facets: own } : undefined;
  // A facet of one value that has one: the language of a book, the kind of its universe
  const held = f.single === true && f.values.some((v) => !v.offered && v.corrected !== "REMOVED");
  const scale = (f.steps?.length ?? 0) > 0;
  const name = label(f.labelKey);
  if (scale)
    return (
      <FieldBlock field={f.facet} label={name} facet={f} corrections={corrections}>
        <ScaleChoice facet={f} corrections={corrections} />
      </FieldBlock>
    );
  return (
    <FieldBlock
      field={f.facet}
      label={name}
      facet={f}
      corrections={corrections}
      sources={<SiteWords facet={f} onWord={addable && ((word) => setSearch({ word }))} />}
      tools={
        // "No other values": the work's values of it all named — the others not offered, rejected for the model; pressed again, taken back
        f.completable && (
          <AsyncButton
            size="xs"
            variant={f.complete ? "secondary" : "ghost"}
            className={SMALL_BUTTON}
            aria-pressed={f.complete === true}
            onClick={() => corrections.setComplete(f.facet, !f.complete)}
            icon={f.complete ? <CheckIcon /> : undefined}
            title={t(f.complete ? "FacetLine.allNamedDone" : "FacetLine.allNamedHint")}
          >
            {t("FacetLine.allNamed")}
          </AsyncButton>
        )
      }
    >
      {/* The tags the work's own text is closest to: a source of the field too, above its values */}
      {f.tags && <TagsByText item={item} facet={f.facet} corrections={corrections} />}
      <div className="flex flex-wrap gap-1">
        {values.map((v) => (
          <Fragment key={v.key}>
            {v.key === later && <LaterOffers />}
            <FacetValue facet={f.facet} value={v} corrections={corrections} tagLink={f.tags === true} />
          </Fragment>
        ))}
        {suggested.map((v) => (
          <SuggestedChip key={v.key} facet={f.facet} value={v} corrections={corrections} tagLink={f.tags === true} />
        ))}
        {/* A facet of one value that has it offers no other: one chosen in its place replaces it */}
        {!held && <MoreCandidates item={item} facet={f.facet} shown={[...f.values, ...suggested]} corrections={corrections} tagLink={f.tags === true} />}
        {addable && <SearchButton onClick={() => setSearch(search ? null : { word: "" })} title={t(held ? "FacetLine.change" : "FacetLine.search")} />}
      </div>
      {search && addable && <AddFacetValue key={search.word} source={addable} corrections={corrections} word={search.word} onClose={() => setSearch(null)} />}
    </FieldBlock>
  );
}

/** The quiet line before the offers that came after the work was opened. */
export function LaterOffers() {
  const { t } = useTranslation("correction");
  return <span className="mx-0.5 h-4 w-px self-center bg-border" title={t("LaterOffers.title")} aria-label={t("LaterOffers.title")} role="separator" />;
}

/** A button by a line of values — "✓ all", 🔍, "…": as high as a chip, a finger's size on a touch screen. */
const SMALL_BUTTON = "h-5 px-1.5 pointer-coarse:h-11 pointer-coarse:px-2.5 pointer-coarse:text-sm";

/** A small 🔍 after a line of values, beside "…": a value found by its name and added. */
export function SearchButton({ onClick, title }: { onClick: () => void; title: string }) {
  return (
    <Button variant="outline" size="xs" className={SMALL_BUTTON} onClick={onClick} title={title} aria-label={title}>
      <SearchIcon className="size-3" />
    </Button>
  );
}

/**
 * "…" after a facet's values: each press shows the next [MORE_STEP] likeliest candidates the line
 * does not show yet ([shown]), dashed, to answer on — one answered on gives its place to the next —
 * until there are none. How many stays while the work is open. Only so many read as are shown —
 * the line's values and those asked for — not every candidate: of the tags, some 8 600, 2.4 MB, read
 * again after every answer, hung an iPad.
 */
export function MoreCandidates({
  item,
  facet,
  shown,
  corrections,
  tagLink,
}: {
  item: ItemRef;
  facet: string;
  shown: { key: string }[];
  corrections: Corrections;
  /** The values are normalized tags: their names link to their pages. */
  tagLink?: boolean;
}) {
  const { t } = useTranslation("correction");
  const queryClient = useQueryClient();
  const [asked, setAsked] = useState(0);
  // The line's values may be among the likeliest candidates: read so many more
  const wanted = (n: number) => shown.length + n;
  const candidates = useCandidates(item, facet, asked > 0, wanted(asked));
  const keys = new Set(shown.map((v) => v.key));
  const rest = (candidates.data ?? []).filter((c) => !keys.has(c.key));
  if (candidates.isError) return null;
  // Fewer read than asked for: there are no more
  const done = candidates.data !== undefined && !candidates.isPlaceholderData && candidates.data.length < wanted(asked) && rest.length <= asked;
  // The press reads the next ones and shows them once they are there: the button is at work while
  // ITS press is ([AsyncButton]) — never while the candidates are read anew after answers, which
  // waits for the work's chances
  const more = async () => {
    await queryClient.fetchQuery(candidatesQuery(item, facet, wanted(asked + MORE_STEP)));
    setAsked(asked + MORE_STEP);
  };
  return (
    <>
      {rest.slice(0, asked).map((c) => (
        <FacetValue key={c.key} facet={facet} value={{ key: c.key, name: c.name, nameKey: c.nameKey, chance: c.chance ?? undefined, offered: true }} corrections={corrections} tagLink={tagLink} />
      ))}
      {!done && (
        <AsyncButton
          variant="outline"
          size="xs"
          className={SMALL_BUTTON}
          onClick={more}
          title={t("MoreCandidates.showMore", { count: MORE_STEP })}
          aria-label={t("MoreCandidates.showMore", { count: MORE_STEP })}
          icon="…"
        />
      )}
    </>
  );
}

/** The keys of the titles of a value by the user's answer on it, translated at render. */
const CORRECTED_TITLE = {
  ADDED: "FacetValue.added",
  CONFIRMED: "FacetValue.confirmed",
  REMOVED: "FacetValue.removed",
} as const satisfies Record<NonNullable<FacetValueInfo["corrected"]>, string>;

/**
 * A value of the work's facet, its state told by its look and an icon, never by colour alone:
 *
 * - the user's — confirmed or added: filled, a ✓ before the name, no chance; the word taken back
 *   by the button shown when pointed at;
 * - not answered on: outlined on white, the model's chance in small print, ✓ ✕; dashed when only
 *   offered, not the work's yet;
 * - taken away by the user: faded, crossed out, ↺.
 *
 * A value of a facet of the site ([corrections] not given) has no answers.
 */
export function FacetValue({
  facet,
  value,
  corrections,
  tagLink,
  mentions,
}: {
  facet: string;
  value: FacetValueInfo;
  corrections?: Corrections;
  /** A normalized tag: its name links to its page on the screen of the tags. */
  tagLink?: boolean;
  /** How often the work's texts name it, when they were counted. */
  mentions?: number | null;
}) {
  const { t } = useTranslation("correction");
  const actions = useContext(ValueActions);
  const mine = value.corrected === "CONFIRMED" || value.corrected === "ADDED";
  const removed = value.corrected === "REMOVED";
  const open = corrections && !value.corrected;
  const name = tagLink ? <TagNameLink tag={value.key}>{valueName(value)}</TagNameLink> : valueName(value);
  const key = chipKey(facet, value.key);
  const asked = { facet, value: value.key, name: valueName(value), summary: value.description ?? undefined, sites: value.sites?.map(sourceShort).join(", ") };
  return (
    <Badge
      variant="outline"
      data-value={key}
      className={cn(
        "group pr-0.5 font-normal",
        CHIP_TOUCH,
        actions?.revealed === key && "ring-2 ring-primary",
        mine && "border-yes/50 bg-yes/15 text-foreground",
        removed && "border-dashed text-muted-foreground opacity-70",
        open && "bg-background",
        open && unlikely(value) && "border-maybe/60",
        // Only offered, not the work's yet: dashed, as a suggestion
        open && value.offered && "border-dashed",
        !corrections && "bg-muted/40",
      )}
      // What the catalogue says of a universe or a character first: the name alone may be of several
      title={
        [value.description, value.corrected ? t(CORRECTED_TITLE[value.corrected]) : value.implied ? t("FacetValue.implied") : undefined]
          .filter(Boolean)
          .join("\n") || undefined
      }
    >
      {mine && <CheckIcon className="size-3 text-yes" aria-label={t("FacetValue.yourAnswerYes")} />}
      {/* Given by a tag under it: marked as such, not by a chance of its own */}
      {value.implied && !value.corrected && <span className="text-muted-foreground" aria-label={t("FacetValue.fromSubtag")}>↑</span>}
      <span className={cn(removed && "line-through")}>
        {actions && corrections ? (
          <ValueName asked={asked} corrections={corrections} tag={tagLink === true}>
            {name}
          </ValueName>
        ) : (
          name
        )}
      </span>
      <ChanceMark value={value} />
      {mentions != null && mentions > 0 && (
        <span className="text-[10px] text-muted-foreground tabular-nums" title={t("FacetValue.mentions", { n: mentions })}>
          {mentions}×
        </span>
      )}
      {corrections && <FacetAnswers facet={facet} value={value} corrections={corrections} />}
      {actions && corrections && <ValueMenu asked={asked} corrections={corrections} tag={tagLink === true} />}
    </Badge>
  );
}

/**
 * Adding a value: the facet from the source's facets, the value typed — the facet's values the
 * site or the user gave are offered as it is typed and one picked is taken with its key; a new
 * one is sent by its name alone, and the backend makes its key. Of a facet of tags no tag of the
 * name typed, the entries of the catalogues are offered after the tags, each marked new: picked,
 * the tag is made of the entry. Of one facet alone, no choice of it; with [onClose], closed by its
 * ✕ or Escape. With [word], a site's word it is opened by: typed, but not the user's typing, so
 * it adds only what it finds till the user changes it.
 */
function AddFacetValue({ source, corrections, word = "", onClose }: { source: SourceInfo; corrections: Corrections; word?: string; onClose?: () => void }) {
  const { t } = useTranslation("correction");
  const facets = source.facets;
  const [facetKey, setFacetKey] = useState(facets[0]?.key ?? "");
  const [name, setName] = useState(word);
  const [query, setQuery] = useState(word);
  useEffect(() => {
    const timer = setTimeout(() => setQuery(name.trim()), 250);
    return () => clearTimeout(timer);
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
  // Found by the name shown as well as by the data's own: of a value of the application's, they differ
  const existing = known.isPlaceholderData
    ? undefined
    : values.find((v) => [v.name, valueName(v)].some((n) => n.toLowerCase() === typed.toLowerCase()));
  const isNew = typed !== "" && query === typed && known.isSuccess && !known.isPlaceholderData && !known.isFetching && !existing;
  const asTyped = word === "" || name !== word;

  const add = async () => {
    await corrections.setFacet(facet.key, existing?.entry ? { entry: existing.entry } : existing ? { key: existing.key } : { name: typed }, true);
    setName("");
  };

  return (
    <div className="mt-1 flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        {facets.length === 1 ? t("AddFacetValue.headingOf", { facet: label(facet.labelKey) }) : t("AddFacetValue.heading")}
        {onClose && (
          <Button variant="ghost" size="icon-xs" onClick={onClose} title={t("AddFacetValue.close")}>
            <XIcon />
          </Button>
        )}
      </div>
      {facets.length > 1 && (
        <select
          value={facet.key}
          onChange={(e) => setFacetKey(e.target.value)}
          className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring dark:bg-input/30"
        >
          {facets.map((f) => (
            <option key={f.key} value={f.key}>
              {label(f.labelKey)}
            </option>
          ))}
        </select>
      )}
      <div className="flex gap-1">
        <Input
          autoFocus={onClose !== undefined}
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && typed && (existing || asTyped)) add().catch(() => {});
            if (e.key === "Escape") onClose?.();
          }}
          list={listId}
          placeholder={t("AddFacetValue.placeholder")}
          className="h-8"
        />
        <datalist id={listId}>
          {values.map((v) => (
            // An entry of a catalogue no tag is bound to: a new tag, told so beside its name
            <option key={v.key} value={valueName(v)} label={v.entry ? [t("AddFacetValue.newTag"), v.entry.description].filter(Boolean).join(" · ") : undefined} />
          ))}
        </datalist>
        <AsyncButton size="icon" variant="outline" onClick={add} disabled={!typed || (!existing && !asTyped)} title={existing ? t("AddFacetValue.add") : t("AddFacetValue.addNew")} icon={<PlusIcon />} />
      </div>
      {/* The values of the facet the word finds, to pick by a press: a datalist opens only as one types */}
      {word !== "" && !asTyped && values.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {values.map((v) => (
            <AsyncButton key={v.key} size="xs" variant="outline" className="h-auto whitespace-normal" onClick={() => corrections.setFacet(facet.key, v.entry ? { entry: v.entry } : { key: v.key }, true).then(onClose)}>
              {valueName(v)}
            </AsyncButton>
          ))}
        </div>
      )}
      {isNew && asTyped && <div className="text-xs text-muted-foreground">{t("AddFacetValue.newValue")}</div>}
      {existing?.entry && (
        <div className="text-xs text-muted-foreground">
          {t("AddFacetValue.newOfEntry")}{" "}
          {existing.entry.url && (
            <a href={existing.entry.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
              {existing.entry.description ?? existing.entry.entry}
            </a>
          )}
        </div>
      )}
    </div>
  );
}
