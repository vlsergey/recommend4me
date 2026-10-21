import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, ChevronDownIcon, ChevronRightIcon, MinusIcon, Trash2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ItemRef, type TagByText, type TagByTextVerdict } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
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
 * THE TAGS BY THE WORK'S OWN TEXT: the tags the paragraphs of its chapters are closest to, a list
 * apart from the model's chances, each with its paragraph quoted and three answers on the pair — yes
 * (the tag marked on the work), not enough (offered no more, nothing learnt), wrong (learnt from);
 * and the tag ignored altogether — offered nowhere, read by no model — its name a link to its page.
 * Folded till opened: made at the asking, of every paragraph against every tag.
 */
export function TagsByText({ item, facet, corrections }: { item: ItemRef; facet: string; corrections: Corrections }) {
  const { t } = useTranslation("tagtext");
  const queryClient = useQueryClient();
  const tagsChanged = useTagsChanged();
  const [open, setOpen] = useState(false);
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
    enabled: open,
    refetchInterval: (q) => (q.state.data?.pending ? PENDING_POLL_MS : false),
  });
  const data = list.data;
  return (
    <div className="mt-1">
      <Button variant="ghost" size="xs" className="h-6 gap-1 px-1 text-xs text-muted-foreground" onClick={() => setOpen(!open)} title={t("TagsByText.toggleHint")}>
        {open ? <ChevronDownIcon /> : <ChevronRightIcon />}
        {t("TagsByText.toggle")}
        {data && data.tags.length > 0 && <span className="tabular-nums">({data.tags.length})</span>}
      </Button>
      {open && (
        <div className="mt-1 flex flex-col gap-2 border-l pl-3">
          {list.isError && <p className="text-xs text-destructive">{t("TagsByText.failed", { error: list.error.message })}</p>}
          {data?.pending && <p className="text-xs text-muted-foreground">{t("TagsByText.pending")}</p>}
          {data && !data.pending && data.tags.length === 0 && <p className="text-xs text-muted-foreground">{t("TagsByText.none")}</p>}
          {data && data.tags.length > 0 && !data.fitted && <p className="text-xs text-muted-foreground">{t("TagsByText.notFitted")}</p>}
          {data && data.tags.length > 0 && (
            <ul className="flex flex-col gap-2">
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
      )}
    </div>
  );
}

/** A tag by the text: its name — a link to its page — its chance or closeness, its paragraph folded to three lines till pressed, the three answers and the ignoring. */
function TagRow({ tag, answer, ignore }: { tag: TagByText; answer: (verdict: TagByTextVerdict) => Promise<void>; ignore: () => Promise<void> }) {
  const { t } = useTranslation("tagtext");
  const [whole, setWhole] = useState(false);
  const where = tag.partTitle ? t("TagsByText.partParagraph", { part: tag.partTitle, n: tag.paragraph + 1 }) : t("TagsByText.paragraph", { n: tag.paragraph + 1 });
  return (
    <li className="flex flex-col gap-1 rounded-md border p-2">
      <div className="flex flex-wrap items-center gap-2">
        <TagNameLink tag={tag.key} className="text-sm font-medium">
          {tag.name}
        </TagNameLink>
        <span className="text-[10px] text-muted-foreground tabular-nums">
          {tag.chance != null ? percent(tag.chance) : t("TagsByText.closeness", { value: tag.closeness.toFixed(2) })}
        </span>
        <div className="ml-auto flex flex-wrap gap-1">
          <AsyncButton size="xs" variant="outline" icon={<CheckIcon />} title={t("TagsByText.yesHint")} onClick={() => answer("YES")}>
            {t("TagsByText.yes")}
          </AsyncButton>
          <AsyncButton size="xs" variant="outline" icon={<MinusIcon />} title={t("TagsByText.enoughNotHint")} onClick={() => answer("ENOUGH_NOT")}>
            {t("TagsByText.enoughNot")}
          </AsyncButton>
          <AsyncButton size="xs" variant="outline" icon={<XIcon />} title={t("TagsByText.wrongHint")} onClick={() => answer("WRONG")}>
            {t("TagsByText.wrong")}
          </AsyncButton>
          {/* As the bin of the screen of the tags: red, an icon alone */}
          <AsyncButton
            size="icon-xs"
            variant="ghost"
            className="text-destructive"
            icon={<Trash2Icon />}
            title={t("TagsByText.ignoreHint")}
            aria-label={t("TagsByText.ignore")}
            onClick={ignore}
          />
        </div>
      </div>
      <button type="button" className={cn("text-left text-xs text-muted-foreground", !whole && "line-clamp-3")} onClick={() => setWhole(!whole)}>
        {tag.quote}
      </button>
      <span className="text-[10px] text-muted-foreground">{where}</span>
    </li>
  );
}
