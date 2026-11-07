import { useEffect, useState } from "react";
import { ExternalLinkIcon, Link2Icon, RefreshCwIcon, SearchIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, type FoundTagEntry, type TagInfo, unwrap } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Input } from "@/components/ui/input";
import { sourceTitle } from "@/i18n/label";
import { followLink, screenHref } from "@/screen/screens";
import { useTagsChanged } from "./tagQueries";
import { otherNames } from "./TagNames";

/** Every name the tag goes by, its name first: what the search of an entry is offered to start from. */
function searchNames(tag: TagInfo): string[] {
  return [tag.name, ...otherNames(tag)];
}

/**
 * The entries of the catalogues (Wikidata) the tag is bound to — the label of the one bound first
 * names the tag in the user's language, every name of them finds it — and the search of more: by
 * any of the tag's names offered at once, or by a name, a Q-id or an entry's address typed. A tag
 * bound to none is searched for by its name as it opens: what is found is only offered.
 */
export function TagEntry({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged();
  const [text, setText] = useState("");
  const [found, setFound] = useState<FoundTagEntry[] | null>(null);
  const search = async (query: string) =>
    unwrap(await api.GET("/api/tags/entries", { params: { query: { query } } }));
  // The search is the press of its button: busy while it is ([AsyncButton])
  const find = async (query: string) => {
    if (!query.trim()) return;
    setText(query);
    setFound(await search(query.trim()));
  };
  // A tag bound to none: its name searched for once, as the tag opens
  const unbound = tag.catalogueEntries.length === 0;
  const [autoFailed, setAutoFailed] = useState<string | null>(null);
  // biome-ignore lint/correctness/useExhaustiveDependencies: once a tag, as it opens — not again when it is bound or renamed while open
  useEffect(() => {
    if (!unbound) return;
    let alive = true;
    search(tag.name).then(
      (list) => alive && setFound((was) => was ?? list),
      (e: unknown) => alive && setAutoFailed(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      alive = false;
    };
  }, [tag.key]);
  const bind = async (catalogue: string, entry: string, bound: boolean) => {
    unwrap(await api.PUT("/api/tags/entry", { body: { tag: tag.key, catalogue, entry, bound } }));
    await changed();
  };
  const isBound = (f: FoundTagEntry) => tag.catalogueEntries.some((b) => b.catalogue === f.catalogue && b.entry === f.entry);
  // Read of the catalogues again: what was kept is shown till the new comes
  const refresh = async () => {
    unwrap(await api.POST("/api/tags/entries/refresh", { params: { query: { tag: tag.key } } }));
    await changed();
  };
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">{t("Entry.title")}</h2>
        {!unbound && (
          <AsyncButton variant="ghost" size="xs" onClick={refresh} title={t("Entry.refreshTitle")} icon={<RefreshCwIcon />}>
            {t("Entry.refresh")}
          </AsyncButton>
        )}
      </div>
      {tag.catalogueEntries.map((b) => (
        <div key={`${b.catalogue}/${b.entry}`} className="flex flex-wrap items-start gap-x-3 gap-y-1">
          <div className="flex min-w-0 flex-1 flex-col">
            <a href={b.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
              {b.label ?? b.entry} <span className="text-muted-foreground">({sourceTitle(b.catalogue)} {b.entry})</span>
              <ExternalLinkIcon className="ml-1 inline size-3 opacity-60" />
            </a>
            {b.description && <span className="text-muted-foreground">{b.description}</span>}
            {b.names.length > 1 && (
              <span className="truncate text-xs text-muted-foreground" title={b.names.join(", ")}>
                {t("Entry.names", { names: b.names.filter((n) => n !== b.label).join(", ") })}
              </span>
            )}
          </div>
          <AsyncButton variant="ghost" size="sm" onClick={() => bind(b.catalogue, b.entry, false)} title={t("Entry.unbindTitle")} icon={<XIcon />}>
            {t("Entry.unbind")}
          </AsyncButton>
        </div>
      ))}
      {unbound && <p className="text-xs text-muted-foreground">{t("Entry.hint")}</p>}
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs text-muted-foreground">{t("Entry.byName")}</span>
        {searchNames(tag).map((name) => (
          <AsyncButton key={name} variant="outline" size="xs" onClick={() => find(name)} title={t("Entry.byNameTitle", { name })}>
            {name}
          </AsyncButton>
        ))}
      </div>
      <form
        className="flex flex-wrap items-center gap-2"
        onKeyDown={(e) => e.stopPropagation()}
        onSubmit={(e) => e.preventDefault()}
      >
        <Input value={text} onChange={(e) => setText(e.target.value)} placeholder={t("Entry.placeholder")} className="max-w-sm" aria-label={t("Entry.placeholder")} />
        <AsyncButton type="submit" size="sm" onClick={() => find(text)} disabled={!text.trim()} icon={<SearchIcon />}>
          {t("Entry.find")}
        </AsyncButton>
      </form>
      {autoFailed && !found && <p className="text-xs text-destructive">{t("Entry.findError", { error: autoFailed })}</p>}
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
                {f.tags
                  .filter((r) => r.key !== tag.key)
                  .map((r) => (
                    <a
                      key={r.key}
                      href={screenHref({ kind: "tags", tag: r.key })}
                      onClick={(e) => followLink(e, { kind: "tags", tag: r.key })}
                      className="text-xs text-maybe underline-offset-2 hover:underline"
                    >
                      {t("Entry.boundAlso", { name: r.name })}
                    </a>
                  ))}
              </div>
              {isBound(f) ? (
                <span className="text-xs text-muted-foreground">{t("Entry.boundHere")}</span>
              ) : (
                <AsyncButton variant="outline" size="sm" onClick={() => bind(f.catalogue, f.entry, true)} title={t("Entry.bindTitle")} icon={<Link2Icon />}>
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
