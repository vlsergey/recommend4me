import { useContext, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { CircleDashedIcon, ExternalLinkIcon, LinkIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type FacetValueInfo, type ItemFacet, type SiteSaid, type SiteWord } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { toast } from "@/components/ui/toast";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { valueName } from "@/i18n/label";
import { RefreshButton } from "@/item/WorkParts";
import { cn } from "@/lib/utils";
import { itemKey } from "@/item/lists";
import { ValueActions } from "./valueActions";

/** The two names of a word of a pair: `Юто/Агеха`, `Holmes & Watson`. */
const PAIR = /\s*\/\s*|\s+&\s+/;

/** A field a press on a site's word opens the search of. */
export type WordTarget = {
  label: string;
  /** The field names characters: it takes the pressed name of a word of a pair, not the whole word. */
  names?: boolean;
  /** The field's search with the [word] typed. */
  onWord: (word: string) => void;
};

/**
 * What the sites say of [fields] that stand together, as one field's sources: the fields of a group
 * read the same words, so they are shown once. A word on several fields is one, reaching whatever it
 * reached in any of them; a word none of them can tell of stays so.
 */
export function saidTogether(fields: ItemFacet[]): ItemFacet {
  const rows = new Map<string, SiteSaid>();
  for (const row of fields.flatMap((f) => f.said ?? [])) {
    const id = `${row.source}/${row.item}`;
    const known = rows.get(id);
    if (!known) {
      rows.set(id, row);
      continue;
    }
    const words = known.words.map((w) => {
      const same = row.words.find((x) => x.key === w.key);
      if (!same) return w;
      const told = [w.reaches, same.reaches].filter((r) => r !== undefined && r !== null);
      return { ...w, searchable: w.searchable || same.searchable, reaches: told.length > 0 ? [...new Set(told.flat())] : undefined };
    });
    rows.set(id, { ...known, words: [...words, ...row.words.filter((x) => !known.words.some((w) => w.key === x.key))] });
  }
  return { ...fields[0], said: [...rows.values()], values: fields.flatMap((f) => f.values) };
}

/** A site's mark and a link to its page, at the start of a row. */
export function SiteMark({ source, url }: { source: string; url: string }) {
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer"
      title={sourceTitle(source)}
      className="inline-flex shrink-0 items-center gap-0.5 text-xs text-muted-foreground hover:text-foreground pointer-coarse:min-h-11"
    >
      {sourceShort(source)}
      <ExternalLinkIcon className="size-3" />
    </a>
  );
}

/** A row of a field's sources: a site's mark, then what it says. */
export function SiteRow({ source, url, children }: { source: string; url: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline gap-2 text-sm">
      <SiteMark source={source} url={url} />
      <div className="flex min-w-0 flex-wrap items-baseline gap-x-1 break-words">{children}</div>
    </div>
  );
}

/** What a word of a site came to in the field. */
function stateOf(word: SiteWord, values: FacetValueInfo[]) {
  const reaches = word.reaches;
  if (reaches === undefined || reaches === null) return "unknown";
  if (reaches.length === 0) return "nothing";
  const reached = values.filter((v) => reaches.includes(v.key));
  return reached.length > 0 && reached.every((v) => v.corrected === "REMOVED") ? "rejected" : "reached";
}

/**
 * A word as the site writes it: as it came to the field — reached a value (plain, the value in its
 * title), its value answered no (crossed out), reached nothing (dotted, ◌); a press goes to the
 * fields it is searched in ([onPress]), a word of a pair in the fields of characters ([split]) a
 * press for each of its names. [hint] says where a press leads.
 */
function Word({
  word,
  values,
  split,
  hint,
  onPress,
}: {
  word: SiteWord;
  values: FacetValueInfo[];
  split: boolean;
  hint: (name: string) => string;
  onPress?: (name: string) => void;
}) {
  const { t } = useTranslation("correction");
  const state = stateOf(word, values);
  const reached = [...new Set(values.filter((v) => word.reaches?.includes(v.key)).map(valueName))];
  const title = state === "nothing" ? t("SiteWords.reachedNothing") : reached.length > 0 ? t("SiteWords.reached", { values: reached.join(", ") }) : undefined;
  const look = cn(state === "rejected" && "text-muted-foreground line-through", state === "nothing" && "underline decoration-dotted underline-offset-2");
  const press = (name: string) =>
    onPress && word.searchable ? (
      <button
        key={name}
        type="button"
        className={cn("text-left hover:text-primary hover:underline pointer-coarse:min-h-11", look)}
        title={[title, hint(name)].filter(Boolean).join("\n")}
        onClick={() => onPress(name)}
      >
        {name}
      </button>
    ) : (
      <span key={name} className={look} title={title}>
        {name}
      </span>
    );
  const names = split ? word.name.split(PAIR).filter((n) => n !== "") : [word.name];
  return (
    <span className="inline-flex items-center gap-0.5">
      {state === "nothing" && <CircleDashedIcon className="size-3 shrink-0 text-maybe" aria-label={t("SiteWords.reachedNothing")} />}
      {names.flatMap((n, i) => (i === 0 ? [press(n)] : [<span key={`/${n}`} className="text-muted-foreground">/</span>, press(n)]))}
    </span>
  );
}

/**
 * A fandom's universe, in the field of universes. Every fandom of a site is bound to a universe of
 * its own name at first: the universe it reached shown when named otherwise, with ✕ to take the
 * fandom out to a universe of its own — the write of the screen of the universes, the other works
 * getting it when their drafts are made anew, in the background. Two universes that are one are
 * merged there, among the alike ones: offered here, every universe of a crossover would be offered to
 * every fandom of it.
 */
const folded = (name: string) => name.trim().toLocaleLowerCase();

function FandomBinding({ said, word, universes }: { said: SiteSaid; word: SiteWord; universes: FacetValueInfo[] }) {
  const { t } = useTranslation("correction");
  const queryClient = useQueryClient();
  const item = useContext(ValueActions)?.item;
  /** The fandom taken out to a universe of its own. */
  const takeOut = async () => {
    ensureOk(await api.PUT("/api/universes/binding", { body: { source: said.source, key: word.key } }));
    toast.add({ title: t("SiteWords.boundLater"), timeout: 5000 });
    queryClient.invalidateQueries({ queryKey: ["universes"], refetchType: "none" });
    if (item) await queryClient.invalidateQueries({ queryKey: itemKey(item) });
  };
  const reached = universes.filter((u) => word.reaches?.includes(u.key));
  // The fandom's own universe is the first it reached: those above it are reached with it
  const own = reached[0];
  return (
    <>
      {own && folded(valueName(own)) !== folded(word.name) && (
        // A universe named as the fandom is not written twice, nor taken out of itself
        <span className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
          → {valueName(own)}
          <AsyncButton size="icon-xs" variant="ghost" title={t("SiteWords.unbind")} aria-label={t("SiteWords.unbind")} icon={<XIcon />} onClick={takeOut} />
        </span>
      )}
      {own && folded(valueName(own)) === folded(word.name) && <LinkIcon className="size-3 text-muted-foreground" aria-label={t("SiteWords.bound", { universe: valueName(own) })} />}
    </>
  );
}

/**
 * THE SOURCES OF A FIELD, right above the place of answering on it: a row for every site that has a
 * word on it, those with none on one line — silence is a fact too, and an item never read is told
 * apart, its silence saying nothing. Nothing when no site can speak of the field. Sources of a group
 * of fields stand over the group ([saidTogether]): a press on a word goes to its one open field, and
 * of several asks which.
 */
export function SiteWords({
  facet,
  targets = [],
  bindTo,
}: {
  facet: ItemFacet;
  /** The open fields a press on a word searches in. */
  targets?: WordTarget[];
  /** The field of universes, its values: a fandom can be bound here to one for all its works. */
  bindTo?: FacetValueInfo[];
}) {
  const { t } = useTranslation("correction");
  const sites = useContext(ValueActions)?.sites;
  // A word pressed that has several fields to go to, till one is chosen
  const [choosing, setChoosing] = useState<{ row: string; name: string; word: string }>();
  const said = facet.said ?? [];
  if (said.length === 0) return null;
  const saying = said.filter((s) => s.words.length > 0);
  const silent = said.filter((s) => s.words.length === 0 && s.read);
  const unread = said.filter((s) => s.words.length === 0 && !s.read);
  const go = (target: WordTarget, name: string, word: string) => {
    setChoosing(undefined);
    target.onWord(target.names ? name : word);
  };
  return (
    <div className="flex flex-col gap-0.5">
      {saying.map((s) => {
        const row = `${s.source}/${s.item}`;
        return (
          <div key={row} className="flex flex-col gap-0.5">
            <SiteRow source={s.source} url={s.url}>
              {s.words.map((w, i) => (
                <span key={w.key} className="inline-flex flex-wrap items-baseline gap-x-1">
                  <Word
                    word={w}
                    values={facet.values}
                    split={targets.some((x) => x.names)}
                    hint={(name) => t(targets.length > 1 ? "SiteWords.findWhere" : "SiteWords.find", { name })}
                    onPress={targets.length === 0 ? undefined : (name) => (targets.length === 1 ? go(targets[0], name, w.name) : setChoosing({ row, name, word: w.name }))}
                  />
                  {bindTo && w.reaches !== undefined && w.reaches !== null && <FandomBinding said={s} word={w} universes={bindTo} />}
                  {i < s.words.length - 1 && <span className="-ml-1 text-muted-foreground">,</span>}
                </span>
              ))}
            </SiteRow>
            {choosing?.row === row && (
              <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
                {choosing.name} →
                {targets.map((x) => (
                  <Button key={x.label} variant="outline" size="xs" className="pointer-coarse:h-11" onClick={() => go(x, choosing.name, choosing.word)}>
                    {x.label}
                  </Button>
                ))}
              </div>
            )}
          </div>
        );
      })}
      {silent.length > 0 && <div className="text-xs text-muted-foreground">{t("SiteWords.silent", { sites: silent.map((s) => sourceShort(s.source)).join(", "), count: silent.length })}</div>}
      {unread.map((s) => (
        <div key={`${s.source}/${s.item}`} className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          {t("SiteWords.unread", { site: sourceShort(s.source) })}
          {sites?.find((x) => x.source === s.source && x.item === s.item)?.canRefresh && <RefreshButton item={s} />}
        </div>
      ))}
    </div>
  );
}
