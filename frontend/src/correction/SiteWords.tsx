import { useContext, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { CircleDashedIcon, ExternalLinkIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type FacetValueInfo, type ItemFacet, type SiteSaid, type SiteWord } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { toast } from "@/components/ui/toast";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { valueName } from "@/i18n/label";
import { RefreshButton } from "@/item/WorkParts";
import { cn } from "@/lib/utils";
import { itemKey } from "@/item/lists";
import { ValueActions } from "./valueActions";

/** The two names of a word of a pair: `Юто/Агеха`, `Holmes & Watson`. */
const PAIR = /\s*\/\s*|\s+&\s+/;

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
  if (word.reaches === undefined || word.reaches === null) return "unknown";
  if (word.reaches.length === 0) return "nothing";
  const reached = values.filter((v) => word.reaches!.includes(v.key));
  return reached.length > 0 && reached.every((v) => v.corrected === "REMOVED") ? "rejected" : "reached";
}

/**
 * A word as the site writes it: as it came to the field — reached a value (plain, the value in its
 * title), its value answered no (crossed out), reached nothing (dotted, ◌); a press opens the field's
 * search with the name typed, a word of a pair in the fields of characters ([split]) a press for
 * each of its names.
 */
function Word({
  word,
  values,
  split,
  onWord,
}: {
  word: SiteWord;
  values: FacetValueInfo[];
  split: boolean;
  onWord?: (name: string) => void;
}) {
  const { t } = useTranslation("correction");
  const state = stateOf(word, values);
  const reached = values.filter((v) => word.reaches?.includes(v.key)).map(valueName);
  const title = state === "nothing" ? t("SiteWords.reachedNothing") : reached.length > 0 ? t("SiteWords.reached", { values: reached.join(", ") }) : undefined;
  const look = cn(state === "rejected" && "text-muted-foreground line-through", state === "nothing" && "underline decoration-dotted underline-offset-2");
  const press = (name: string) =>
    onWord && word.searchable ? (
      <button
        key={name}
        type="button"
        className={cn("text-left hover:text-primary hover:underline pointer-coarse:min-h-11", look)}
        title={[title, t("SiteWords.find", { name })].filter(Boolean).join("\n")}
        onClick={() => onWord(name)}
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
      {names.flatMap((n, i) => (i === 0 ? [press(n)] : [<span key={`/${i}`} className="text-muted-foreground">/</span>, press(n)]))}
    </span>
  );
}

/**
 * A fandom for all its works, from the field of universes: a word that reached nothing, bound to a
 * universe the work has; a word bound, its universe and ✕ to take it back. The write of the screen
 * of the universes: the other works get it when their drafts are made anew, in the background.
 */
function FandomBinding({ said, word, universes }: { said: SiteSaid; word: SiteWord; universes: FacetValueInfo[] }) {
  const { t } = useTranslation("correction");
  const queryClient = useQueryClient();
  const item = useContext(ValueActions)?.item;
  const bind = async (universe: string, bound: boolean) => {
    ensureOk(await api.PUT("/api/universes/fandoms", { body: { source: said.source, fandom: word.key, universe, bound } }));
    if (bound) toast.add({ title: t("SiteWords.boundLater"), timeout: 5000 });
    queryClient.invalidateQueries({ queryKey: ["universes"], refetchType: "none" });
    if (item) await queryClient.invalidateQueries({ queryKey: itemKey(item) });
  };
  const reached = universes.filter((u) => word.reaches?.includes(u.key));
  return (
    <>
      {word.reaches?.length === 0 &&
        universes
          .filter((u) => u.corrected !== "REMOVED" && !u.offered)
          .map((u) => (
            <AsyncButton key={u.key} size="xs" variant="ghost" className="h-5 px-1 text-xs pointer-coarse:h-11" title={t("SiteWords.bindHint")} onClick={() => bind(u.key, true)}>
              {t("SiteWords.bind", { universe: valueName(u) })}
            </AsyncButton>
          ))}
      {reached.map((u) => (
        <span key={u.key} className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
          → {valueName(u)}
          <AsyncButton size="icon-xs" variant="ghost" title={t("SiteWords.unbind")} aria-label={t("SiteWords.unbind")} icon={<XIcon />} onClick={() => bind(u.key, false)} />
        </span>
      ))}
    </>
  );
}

/**
 * THE SOURCES OF A FIELD, right above the place of answering on it: a row for every site that has a
 * word on it, those with none on one line — silence is a fact too, and an item never read is told
 * apart, its silence saying nothing. Nothing when no site can speak of the field.
 */
export function SiteWords({
  facet,
  split = false,
  onWord,
  bindFandoms = false,
}: {
  facet: ItemFacet;
  /** The field names characters: each name of a word of a pair is a press of its own. */
  split?: boolean;
  /** A press on a word: the field's search with the [name] typed. */
  onWord?: (name: string) => void;
  /** The field of universes: a fandom can be bound here for all its works. */
  bindFandoms?: boolean;
}) {
  const { t } = useTranslation("correction");
  const sites = useContext(ValueActions)?.sites;
  const said = facet.said ?? [];
  if (said.length === 0) return null;
  const saying = said.filter((s) => s.words.length > 0);
  const silent = said.filter((s) => s.words.length === 0 && s.read);
  const unread = said.filter((s) => s.words.length === 0 && !s.read);
  return (
    <div className="flex flex-col gap-0.5">
      {saying.map((s) => (
        <SiteRow key={`${s.source}/${s.item}`} source={s.source} url={s.url}>
          {s.words.map((w, i) => (
            <span key={w.key} className="inline-flex flex-wrap items-baseline gap-x-1">
              <Word word={w} values={facet.values} split={split} onWord={onWord} />
              {bindFandoms && w.reaches !== undefined && w.reaches !== null && <FandomBinding said={s} word={w} universes={facet.values} />}
              {i < s.words.length - 1 && <span className="-ml-1 text-muted-foreground">,</span>}
            </span>
          ))}
        </SiteRow>
      ))}
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
