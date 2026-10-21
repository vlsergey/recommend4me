import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { BanIcon, CheckIcon, FileTextIcon, Loader2Icon, MinusIcon, Trash2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ItemRef, type TagByText, type TagByTextVerdict } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n/format";
import { useCalmUpdates } from "@/lib/useCalmUpdates";
import { useRefresh } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";
import { candidatesKey, suggestionsKey } from "@/suggestion/useSuggestions";
import { TagNameLink } from "@/tag/TagNameLink";
import { useTagsChanged } from "@/tag/TagsScreen";

export const tagsByTextKey = (item: ItemRef) => ["tagsByText", item.source, item.item];

/** How often the list is asked for again while the chapters' paragraphs are still being encoded: a wait for what was asked, not a refresh. */
const PENDING_POLL_MS = 3000;

/** How often the list is read again till the user chooses ([RefreshSelect]): the model is fitted anew as the answers pause. */
const EVERY_MS = 10_000;

/** A row of the list: a tag and its paragraph. */
const rowOf = (tag: TagByText) => `${tag.key}:${tag.part}:${tag.paragraph}`;

/** A row read again the same: its chance and closeness to the shown digits. */
const sameRow = (a: TagByText, b: TagByText) =>
  Math.round((a.chance ?? -1) * 100) === Math.round((b.chance ?? -1) * 100) && a.closeness.toFixed(2) === b.closeness.toFixed(2);

/**
 * THE TAGS BY THE WORK'S OWN TEXT: a button under the facet of tags opening a window of its own —
 * read there, in letters to read, not under a magnifier among the work's facets — of the tags the
 * paragraphs of its chapters are closest to, a list apart from the model's chances, each with its
 * paragraph quoted and three answers on the pair: yes (the tag marked on the work), not enough
 * (offered no more, nothing learnt), wrong (learnt from); and the tag ignored altogether — offered
 * nowhere, read by no model — its name a link to its page. Read when opened: made at the asking,
 * of every paragraph against every tag.
 */
export function TagsByText({
  item,
  facet,
  corrections,
  size = "xs",
  className = "mt-1",
}: {
  item: ItemRef;
  facet: string;
  corrections: Corrections;
  /** The button's size: small under the facet of tags, as its neighbours in the bar of the work's window. */
  size?: "xs" | "sm";
  className?: string;
}) {
  const { t } = useTranslation("tagtext");
  const [open, setOpen] = useState(false);
  const [every, setEvery] = useRefresh("tagtext.refresh", EVERY_MS);
  return (
    <div className={className}>
      <Button variant="outline" size={size} className="gap-1" onClick={() => setOpen(true)} title={t("TagsByText.toggleHint")}>
        <FileTextIcon />
        {t("TagsByText.toggle")}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="flex max-h-[90vh] max-w-4xl flex-col overflow-hidden">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              {t("TagsByText.title")}
              {/* Beside the closing cross */}
              <RefreshSelect every={every} onChange={setEvery} className="mr-8 ml-auto" />
            </DialogTitle>
            <DialogDescription>{t("TagsByText.toggleHint")}</DialogDescription>
          </DialogHeader>
          {open && <TagsByTextList item={item} facet={facet} corrections={corrections} every={every} />}
        </DialogContent>
      </Dialog>
    </div>
  );
}

/**
 * The list of the window, read while it is open — as often as chosen, and anew after every answer —
 * shown calmly ([useCalmUpdates]): another list read, the rows move to it, the list taking no press
 * meanwhile; a row answered on fades and the rows after it move up into its place.
 */
function TagsByTextList({ item, facet, corrections, every }: { item: ItemRef; facet: string; corrections: Corrections; every: number }) {
  const { t } = useTranslation("tagtext");
  const queryClient = useQueryClient();
  const tagsChanged = useTagsChanged();
  const list = useQuery({
    queryKey: tagsByTextKey(item),
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/tags/by-text", { params: { path: { source: item.source, item: item.item } } })),
    refetchInterval: (q) => (q.state.data?.pending ? PENDING_POLL_MS : every || false),
  });
  const data = list.data;
  const calm = useCalmUpdates(data?.tags, rowOf, sameRow);
  const shown = calm.shown ?? [];
  /** The user's word on a row: once kept, the row leaves. */
  const answer = async (tag: TagByText, verdict: TagByTextVerdict) => {
    await corrections.answerTagByText(facet, tag, verdict);
    calm.remove([rowOf(tag)]);
  };
  /** The work has not the tag — the user's word on the facet, as ✕ of a value of it: once kept, every row of the tag leaves. */
  const absent = async (tag: TagByText) => {
    await corrections.setFacet(facet, { key: tag.key }, false);
    calm.remove(shown.filter((x) => x.key === tag.key).map(rowOf));
  };
  /** The tag ignored altogether, as the bin of the screen of the tags does: once kept, its rows leave, and it is offered the work no more. */
  const ignore = async (tag: TagByText) => {
    unwrap(await api.PUT("/api/tags/ignored", { body: { tag: tag.key, ignored: true } }));
    calm.remove(shown.filter((x) => x.key === tag.key).map(rowOf));
    await tagsChanged();
    queryClient.invalidateQueries({ queryKey: tagsByTextKey(item) });
    queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
    queryClient.invalidateQueries({ queryKey: candidatesKey(item) });
  };
  return (
    <div className="-mx-1 flex min-h-0 flex-col gap-3 overflow-y-auto px-1">
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("TagsByText.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("TagsByText.failed", { error: list.error.message })}</p>}
      {data?.pending && <p className="text-sm text-muted-foreground">{t("TagsByText.pending")}</p>}
      {data && !data.pending && shown.length === 0 && <p className="text-sm text-muted-foreground">{t("TagsByText.none")}</p>}
      {shown.length > 0 && data && !data.fitted && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          {t("TagsByText.notFitted")}
          {calm.locked && <Loader2Icon className="size-3.5 animate-spin" />}
        </p>
      )}
      {shown.length > 0 && (
        <ul className={cn("flex flex-col gap-3", calm.locked && "pointer-events-none")} aria-busy={calm.locked}>
          {shown.map((tag) => (
            <TagRow
              key={rowOf(tag)}
              rowRef={calm.rowRef(rowOf(tag))}
              tag={tag}
              answer={(verdict) => answer(tag, verdict)}
              absent={() => absent(tag)}
              ignore={() => ignore(tag)}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

/** A tag by the text: its name — a link to its page — its chance or closeness, its paragraph folded to six lines till pressed, the three answers and the ignoring. */
function TagRow({
  tag,
  rowRef,
  answer,
  absent,
  ignore,
}: {
  tag: TagByText;
  rowRef: (e: HTMLElement | null) => void;
  answer: (verdict: TagByTextVerdict) => Promise<void>;
  absent: () => Promise<void>;
  ignore: () => Promise<void>;
}) {
  const { t } = useTranslation("tagtext");
  const [whole, setWhole] = useState(false);
  const where = tag.partTitle ? t("TagsByText.partParagraph", { part: tag.partTitle, n: tag.paragraph + 1 }) : t("TagsByText.paragraph", { n: tag.paragraph + 1 });
  return (
    <li ref={rowRef} className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <TagNameLink tag={tag.key} className="text-base font-semibold">
          {tag.name}
        </TagNameLink>
        <span className="text-xs text-muted-foreground tabular-nums">
          {tag.chance != null ? percent(tag.chance) : t("TagsByText.closeness", { value: tag.closeness.toFixed(2) })}
        </span>
        <div className="ml-auto flex flex-wrap gap-1.5">
          <AsyncButton size="sm" variant="outline" icon={<CheckIcon />} title={t("TagsByText.yesHint")} onClick={() => answer("YES")}>
            {t("TagsByText.yes")}
          </AsyncButton>
          <AsyncButton size="sm" variant="outline" icon={<MinusIcon />} title={t("TagsByText.enoughNotHint")} onClick={() => answer("ENOUGH_NOT")}>
            {t("TagsByText.enoughNot")}
          </AsyncButton>
          <AsyncButton size="sm" variant="outline" icon={<XIcon />} title={t("TagsByText.wrongHint")} onClick={() => answer("WRONG")}>
            {t("TagsByText.wrong")}
          </AsyncButton>
          <AsyncButton size="sm" variant="outline" icon={<BanIcon />} title={t("TagsByText.absentHint")} onClick={absent}>
            {t("TagsByText.absent")}
          </AsyncButton>
          {/* As the bin of the screen of the tags: red, an icon alone */}
          <AsyncButton
            size="icon-sm"
            variant="ghost"
            className="text-destructive"
            icon={<Trash2Icon />}
            title={t("TagsByText.ignoreHint")}
            aria-label={t("TagsByText.ignore")}
            onClick={ignore}
          />
        </div>
      </div>
      <button type="button" className={cn("text-left text-sm whitespace-pre-line", !whole && "line-clamp-6")} onClick={() => setWhole(!whole)}>
        {tag.quote}
      </button>
      {/* Where the paragraph is read on the site: its chapter, scrolled to it */}
      <a href={tag.url} target="_blank" rel="noopener noreferrer" className="w-fit text-xs text-muted-foreground underline-offset-2 hover:underline">
        {where}
      </a>
    </li>
  );
}
