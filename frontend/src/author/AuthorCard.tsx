import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLinkIcon, Link2Icon, SearchIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type AuthorSide, type ContentTypeInfo, type FoundAuthorEntry } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { Offers } from "./AuthorOffers";
import { authorName, authorsKey, WorkLink } from "./pieces";

/**
 * THE CARD OF AN AUTHOR, over whatever list it was opened from: the accounts on the sites, the entries
 * of the catalogues (Wikidata), every pair with them with the word said of it, and all the works.
 */
export function AuthorCard({ type, id, onOpen, onClose }: { type: ContentTypeInfo; id: number; onOpen: (id: number) => void; onClose: () => void }) {
  const { t } = useTranslation("author");
  const author = useQuery({
    queryKey: [...authorsKey(type.id), "author", id],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/authors/{id}", { params: { path: { type: type.id, id } } })),
  });
  const a = author.data;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-5xl">
        <DialogHeader>
          <DialogTitle>{a ? authorName(a) : t("Offers.loading")}</DialogTitle>
          {a && <DialogDescription>{t("Card.works", { count: a.works })}</DialogDescription>}
        </DialogHeader>
        {author.isError && <p className="text-sm text-destructive">{t("Offers.failed", { error: author.error.message })}</p>}
        {a && (
          <div className="flex flex-col gap-5 text-sm">
            <Sites type={type} author={a} />
            <Entries type={type} author={a} />
            <section className="flex flex-col gap-1.5">
              <h2 className="font-semibold">{t("Card.pairs")}</h2>
              <Offers type={type} said="ALL" author={id} every={0} onOpen={onOpen} onMerged={onClose} />
            </section>
            <section className="flex flex-col gap-1.5">
              <h2 className="font-semibold">{t("Card.allWorks")}</h2>
              <ul className="flex flex-col">
                {a.rest.map((w) => (
                  <li key={`${w.source}/${w.item}`}>
                    <WorkLink work={w} />
                  </li>
                ))}
              </ul>
            </section>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** The accounts of the author on the sites; of several, each to be taken out by its ✕. */
function Sites({ type, author }: { type: ContentTypeInfo; author: AuthorSide }) {
  const { t } = useTranslation("author");
  const queryClient = useQueryClient();
  const takeOut = async (source: string, site: string) => {
    ensureOk(await api.DELETE("/api/authors/{id}/sites", { params: { path: { id: author.id }, query: { source, author: site } } }));
    await queryClient.invalidateQueries({ queryKey: authorsKey(type.id) });
  };
  return (
    <section className="flex flex-col gap-1.5">
      <h2 className="font-semibold">{t("Card.accounts")}</h2>
      <ul className="flex flex-col gap-0.5">
        {author.sites.map((s) => (
          <li key={`${s.source}/${s.author}`} className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground" title={sourceTitle(s.source)}>
              {sourceShort(s.source)}
            </span>
            <span>{s.name}</span>
            <span className="text-xs text-muted-foreground tabular-nums" title={t("Accounts.worksHint")}>
              {s.works}
            </span>
            {author.sites.length > 1 && <AsyncButton size="icon-xs" variant="ghost" icon={<XIcon />} title={t("Card.takeOutHint")} onClick={() => takeOut(s.source, s.author)} />}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The entries of the catalogues the author is bound to, and the search of more: by a name of theirs, or by a name, a Q-id or an entry's address typed. */
function Entries({ type, author }: { type: ContentTypeInfo; author: AuthorSide }) {
  const { t } = useTranslation("author");
  const queryClient = useQueryClient();
  const [text, setText] = useState("");
  const [found, setFound] = useState<FoundAuthorEntry[] | null>(null);
  const find = async (query: string) => {
    if (!query.trim()) return;
    setText(query);
    setFound(unwrap(await api.GET("/api/types/{type}/authors/entries", { params: { path: { type: type.id }, query: { query: query.trim() } } })));
  };
  const bind = async (catalogue: string, entry: string, bound: boolean) => {
    ensureOk(await api.PUT("/api/authors/{id}/entries", { params: { path: { id: author.id } }, body: { catalogue, entry, bound } }));
    await queryClient.invalidateQueries({ queryKey: authorsKey(type.id) });
  };
  const isBound = (f: FoundAuthorEntry) => author.entries.some((b) => b.catalogue === f.catalogue && b.entry === f.entry);
  const names = [...new Set(author.sites.map((s) => s.name))];
  return (
    <section className="flex flex-col gap-1.5">
      <h2 className="font-semibold">{t("Entry.title")}</h2>
      {author.entries.map((b) => (
        <div key={`${b.catalogue}/${b.entry}`} className="flex flex-wrap items-start gap-x-3 gap-y-1">
          <div className="flex min-w-0 flex-1 flex-col">
            <a href={b.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
              {b.label ?? b.entry} <span className="text-muted-foreground">({sourceTitle(b.catalogue)} {b.entry})</span>
              <ExternalLinkIcon className="ml-1 inline size-3 opacity-60" />
            </a>
            {b.description && <span className="text-muted-foreground">{b.description}</span>}
          </div>
          <AsyncButton variant="ghost" size="sm" onClick={() => bind(b.catalogue, b.entry, false)} title={t("Entry.unbindHint")} icon={<XIcon />}>
            {t("Entry.unbind")}
          </AsyncButton>
        </div>
      ))}
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs text-muted-foreground">{t("Entry.byName")}</span>
        {names.map((name) => (
          <AsyncButton key={name} variant="outline" size="xs" onClick={() => find(name)} title={t("Entry.byNameHint", { name })}>
            {name}
          </AsyncButton>
        ))}
      </div>
      <form className="flex flex-wrap items-center gap-2" onKeyDown={(e) => e.stopPropagation()} onSubmit={(e) => e.preventDefault()}>
        <Input value={text} onChange={(e) => setText(e.target.value)} placeholder={t("Entry.placeholder")} className="max-w-sm" aria-label={t("Entry.placeholder")} />
        <AsyncButton type="submit" size="sm" onClick={() => find(text)} disabled={!text.trim()} icon={<SearchIcon />}>
          {t("Entry.find")}
        </AsyncButton>
      </form>
      {found && found.length === 0 && <p className="text-muted-foreground">{t("Entry.nothing")}</p>}
      {found && found.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {found.map((f) => (
            <li key={`${f.catalogue}/${f.entry}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5">
              <div className="flex min-w-0 flex-1 flex-col">
                <a href={f.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
                  {f.label ?? f.entry} <span className="text-muted-foreground">({f.entry})</span>
                </a>
                {f.description && <span className="text-xs text-muted-foreground">{f.description}</span>}
                {f.authors
                  .filter((r) => r.id !== author.id)
                  .map((r) => (
                    <span key={r.id} className="text-xs text-maybe">
                      {t("Entry.boundAlso", { name: r.name })}
                    </span>
                  ))}
              </div>
              {isBound(f) ? (
                <span className="text-xs text-muted-foreground">{t("Entry.boundHere")}</span>
              ) : (
                <AsyncButton variant="outline" size="sm" onClick={() => bind(f.catalogue, f.entry, true)} title={t("Entry.bindHint")} icon={<Link2Icon />}>
                  {t("Entry.bind")}
                </AsyncButton>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
