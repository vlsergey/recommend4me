import { ExternalLinkIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { AuthorSide, AuthorWork } from "@/api/client";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";

export const authorsKey = (type: string) => ["authors", type];

/** The name an author goes by: their entry's label, else theirs on the site with the most of their works. */
export function authorName(a: AuthorSide): string {
  const entry = a.entries.find((e) => e.label)?.label;
  if (entry) return entry;
  return [...a.sites].sort((x, y) => y.works - x.works)[0]?.name ?? String(a.id);
}

/** The entries of the catalogues an author is bound to, each a link to its page. */
export function EntryLinks({ author }: { author: AuthorSide }) {
  return author.entries.map((e) => (
    <a
      key={`${e.catalogue}/${e.entry}`}
      href={e.url}
      target="_blank"
      rel="noreferrer"
      title={e.description ?? sourceTitle(e.catalogue)}
      className="inline-flex items-center gap-0.5 text-xs text-muted-foreground underline-offset-2 hover:underline"
    >
      {e.entry}
      <ExternalLinkIcon className="size-3 opacity-60" />
    </a>
  ));
}

/** The accounts of an author on the sites, each with its works. */
export function Accounts({ author }: { author: AuthorSide }) {
  const { t } = useTranslation("author");
  return (
    <span className="flex flex-wrap gap-x-3 text-xs text-muted-foreground">
      {author.sites.map((s) => (
        <span key={`${s.source}/${s.author}`} title={sourceTitle(s.source)}>
          {sourceShort(s.source)} <span className="text-foreground">{s.name}</span>{" "}
          <span className="tabular-nums" title={t("Accounts.worksHint")}>
            {s.works}
          </span>
        </span>
      ))}
    </span>
  );
}

/** An author as a heading: the name opens their card; their entries and accounts under it. */
export function AuthorHead({ author, onOpen }: { author: AuthorSide; onOpen: (id: number) => void }) {
  const { t } = useTranslation("author");
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <button type="button" className="min-w-0 truncate text-left font-semibold underline-offset-2 hover:underline" title={t("AuthorHead.openHint")} onClick={() => onOpen(author.id)}>
          {authorName(author)}
        </button>
        <EntryLinks author={author} />
      </div>
      <Accounts author={author} />
    </div>
  );
}

/** A work: its title opens its card here, the icon its page on the site. */
export function WorkLink({ work: w }: { work: AuthorWork }) {
  const { t } = useTranslation("author");
  return (
    <span className="min-w-0">
      <span className="mr-1 text-xs text-muted-foreground" title={sourceTitle(w.source)}>
        {sourceShort(w.source)}
      </span>
      <a href={itemHref({ source: w.source, item: w.item })} className="underline-offset-2 hover:underline">
        {w.title}
      </a>
      {w.url && (
        <a href={w.url} target="_blank" rel="noreferrer" title={t("WorkLink.onSite")} aria-label={t("WorkLink.onSite")} className="ml-1 inline-flex align-baseline text-muted-foreground hover:text-foreground">
          <ExternalLinkIcon className="size-3" />
        </a>
      )}
    </span>
  );
}
