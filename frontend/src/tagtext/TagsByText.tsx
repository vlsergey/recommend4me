import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, FileTextIcon, MinusIcon, Trash2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ItemRef, type TagByText, type TagByTextVerdict } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n/format";
import { cn } from "@/lib/utils";
import { candidatesKey, suggestionsKey } from "@/suggestion/useSuggestions";
import { TagNameLink } from "@/tag/TagNameLink";
import { useTagsChanged } from "@/tag/TagsScreen";

export const tagsByTextKey = (item: ItemRef) => ["tagsByText", item.source, item.item];

/** How often the list is asked for again while the chapters' paragraphs are still being encoded: a wait for what was asked, not a refresh. */
const PENDING_POLL_MS = 3000;

/**
 * THE TAGS BY THE WORK'S OWN TEXT: a button under the facet of tags opening a window of its own —
 * read there, in letters to read, not under a magnifier among the work's facets — of the tags the
 * paragraphs of its chapters are closest to, a list apart from the model's chances, each with its
 * paragraph quoted and three answers on the pair: yes (the tag marked on the work), not enough
 * (offered no more, nothing learnt), wrong (learnt from); and the tag ignored altogether — offered
 * nowhere, read by no model — its name a link to its page. Read when opened: made at the asking,
 * of every paragraph against every tag.
 */
export function TagsByText({ item, facet, corrections }: { item: ItemRef; facet: string; corrections: Corrections }) {
  const { t } = useTranslation("tagtext");
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-1">
      <Button variant="outline" size="xs" className="gap-1" onClick={() => setOpen(true)} title={t("TagsByText.toggleHint")}>
        <FileTextIcon />
        {t("TagsByText.toggle")}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="flex max-h-[90vh] max-w-4xl flex-col overflow-hidden">
          <DialogHeader>
            <DialogTitle>{t("TagsByText.title")}</DialogTitle>
            <DialogDescription>{t("TagsByText.toggleHint")}</DialogDescription>
          </DialogHeader>
          {open && <TagsByTextList item={item} facet={facet} corrections={corrections} />}
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** The list of the window, read while it is open. */
function TagsByTextList({ item, facet, corrections }: { item: ItemRef; facet: string; corrections: Corrections }) {
  const { t } = useTranslation("tagtext");
  const queryClient = useQueryClient();
  const tagsChanged = useTagsChanged();
  /** The tag ignored altogether, as the bin of the screen of the tags does: once kept, gone from this list and from what the work is offered. */
  const ignore = async (tag: TagByText) => {
    unwrap(await api.PUT("/api/tags/ignored", { body: { tag: tag.key, ignored: true } }));
    await tagsChanged();
    queryClient.invalidateQueries({ queryKey: tagsByTextKey(item) });
    queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
    queryClient.invalidateQueries({ queryKey: candidatesKey(item) });
  };
  const list = useQuery({
    queryKey: tagsByTextKey(item),
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/tags/by-text", { params: { path: { source: item.source, item: item.item } } })),
    refetchInterval: (q) => (q.state.data?.pending ? PENDING_POLL_MS : false),
  });
  const data = list.data;
  return (
    <div className="-mx-1 flex min-h-0 flex-col gap-3 overflow-y-auto px-1">
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("TagsByText.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("TagsByText.failed", { error: list.error.message })}</p>}
      {data?.pending && <p className="text-sm text-muted-foreground">{t("TagsByText.pending")}</p>}
      {data && !data.pending && data.tags.length === 0 && <p className="text-sm text-muted-foreground">{t("TagsByText.none")}</p>}
      {data && data.tags.length > 0 && !data.fitted && <p className="text-sm text-muted-foreground">{t("TagsByText.notFitted")}</p>}
      {data && data.tags.length > 0 && (
        <ul className="flex flex-col gap-3">
          {data.tags.map((tag) => (
            <TagRow
              key={`${tag.key}:${tag.part}:${tag.paragraph}`}
              tag={tag}
              answer={(verdict) => corrections.answerTagByText(facet, tag, verdict)}
              ignore={() => ignore(tag)}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

/** A tag by the text: its name — a link to its page — its chance or closeness, its paragraph folded to six lines till pressed, the three answers and the ignoring. */
function TagRow({ tag, answer, ignore }: { tag: TagByText; answer: (verdict: TagByTextVerdict) => Promise<void>; ignore: () => Promise<void> }) {
  const { t } = useTranslation("tagtext");
  const [whole, setWhole] = useState(false);
  const where = tag.partTitle ? t("TagsByText.partParagraph", { part: tag.partTitle, n: tag.paragraph + 1 }) : t("TagsByText.paragraph", { n: tag.paragraph + 1 });
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3">
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
      <span className="text-xs text-muted-foreground">{where}</span>
    </li>
  );
}
