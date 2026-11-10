import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDownToLineIcon, ArrowLeftIcon, ChevronRightIcon, ExternalLinkIcon, Loader2Icon, PencilIcon, RefreshCwIcon, Undo2Icon, UnlinkIcon, UsersIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type UniverseEntryInfo, type UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ago, formatDate } from "@/i18n/format";
import { sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { CatalogueSearch } from "./CatalogueSearch";
import { EntryCharacters } from "./EntryCharacters";
import { LocalNameEdit } from "./LocalName";
import { SimilarUniverses } from "./SimilarUniverses";
import { entryRef, UNIVERSES, useDictionaryChanged } from "./universeQueries";

/** The query of one universe as it is now. */
export function universeQuery(typeId: string, value: string) {
  return {
    queryKey: [UNIVERSES, typeId, "one", value],
    queryFn: async () => unwrap(await api.GET("/api/universes/one", { params: { query: { universe: value } } })),
  };
}

/**
 * A UNIVERSE OPENED: its name — the user's, its entry's, its sites' fandom's of the most works —
 * whether the characters of its entries are read, the sites' fandoms bound to it (one taken out to a
 * universe of its own), the entries of the catalogue it is bound to (with their characters when
 * read) and the search to bind one more, the universes above and under it, and the universes alike
 * it, to merge, put under or above, or tell apart.
 */
export function UniverseCard({ type, value, dictionary }: { type: ContentTypeInfo; value: string; dictionary?: UniverseInfo[] }) {
  const { t } = useTranslation("universe");
  const info = useQuery(universeQuery(type.id, value));
  const u = info.data;
  return (
    <section className="flex min-w-0 flex-col gap-4 text-sm">
      <a
        href={screenHref({ kind: "universes" })}
        onClick={(e) => followLink(e, { kind: "universes" })}
        className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground lg:hidden"
      >
        <ArrowLeftIcon className="size-4" /> {t("UniverseCard.allUniverses")}
      </a>
      {info.isLoading && <Loader2Icon className="size-5 animate-spin text-muted-foreground" />}
      {info.isError && <p className="text-destructive">{t("UniverseCard.loadFailed", { error: info.error.message })}</p>}
      {u && (
        <>
          <Header type={type} universe={u} />
          <Sites type={type} universe={u} />
          <Entries type={type} universe={u} dictionary={dictionary} />
          <Around type={type} universe={u} dictionary={dictionary} />
          <SimilarUniverses type={type} universe={u} />
        </>
      )}
    </section>
  );
}

/** What to ask again after the universe [value] changed: it, the universes, the works. */
function useChanged(typeId: string, value: string) {
  const queryClient = useQueryClient();
  const changed = useDictionaryChanged(typeId);
  return async () => {
    await queryClient.invalidateQueries({ queryKey: [UNIVERSES, typeId, "one", value] });
    await changed(true);
  };
}

/** The name and its giving, the description, the characters read or not and the reading again. */
function Header({ type, universe: u }: { type: ContentTypeInfo; universe: UniverseInfo }) {
  const { t } = useTranslation("universe");
  const changed = useChanged(type.id, u.value);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(u.ownName ?? "");
  const rename = async (given?: string) => {
    ensureOk(await api.PUT("/api/universes/name", { body: { universe: u.value, name: given } }));
    await changed();
    setEditing(false);
  };
  const read = async (on: boolean) => {
    ensureOk(await api.PUT("/api/universes/characters", { body: { universe: u.value, read: on } }));
    await changed();
  };
  const refresh = async () => {
    ensureOk(await api.POST("/api/universes/refresh", { body: { universe: u.value } }));
    await changed();
  };
  return (
    <header className="flex flex-col gap-2">
      <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold">
        {editing ? (
          <form
            className="flex flex-wrap items-center gap-1"
            onSubmit={(e) => {
              e.preventDefault();
              void rename(name.trim() || undefined);
            }}
          >
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={u.calculatedName ?? u.name} className="w-72" autoFocus />
            <AsyncButton size="sm" onClick={() => rename(name.trim() || undefined)}>
              {t("UniverseCard.save")}
            </AsyncButton>
            <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
              {t("UniverseCard.cancel")}
            </Button>
          </form>
        ) : (
          <>
            {u.name}
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={() => {
                setName(u.ownName ?? "");
                setEditing(true);
              }}
              title={t("UniverseCard.rename")}
              aria-label={t("UniverseCard.rename")}
            >
              <PencilIcon />
            </Button>
            {u.ownName && (
              <AsyncButton variant="ghost" size="icon-xs" onClick={() => rename(undefined)} title={t("UniverseCard.ownNameBack")} aria-label={t("UniverseCard.ownNameBack")} icon={<Undo2Icon />} />
            )}
          </>
        )}
      </h1>
      {u.ownName && u.calculatedName && <p className="text-xs text-muted-foreground">{t("UniverseCard.sitesName", { name: u.calculatedName })}</p>}
      {u.description && <p className="text-muted-foreground">{u.description}</p>}
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-muted-foreground">{t("UniverseCard.works", { count: u.works })}</span>
        <AsyncButton
          size="sm"
          variant={u.characters ? "secondary" : "outline"}
          aria-pressed={u.characters}
          onClick={() => read(!u.characters)}
          disabled={u.entries.length === 0 && !u.characters}
          title={t(u.entries.length === 0 ? "UniverseCard.charactersNoEntry" : u.characters ? "UniverseCard.charactersOnHint" : "UniverseCard.charactersOffHint")}
          icon={<UsersIcon />}
        >
          {u.characters ? t("UniverseCard.charactersOn", { count: u.characterCount }) : t("UniverseCard.charactersOff")}
        </AsyncButton>
        {u.entries.length > 0 && (
          <AsyncButton size="sm" variant="ghost" onClick={refresh} title={t("UniverseCard.refreshHint")} icon={<RefreshCwIcon />}>
            {t("UniverseCard.refresh")}
          </AsyncButton>
        )}
      </div>
    </header>
  );
}

/**
 * The sites' fandoms bound to the universe, of the most works first; of several, one taken out to a universe of its
 * own — or put down: taken out to a universe of its own under this one, a part of it (a film of a franchise).
 */
function Sites({ type, universe: u }: { type: ContentTypeInfo; universe: UniverseInfo }) {
  const { t } = useTranslation("universe");
  const changed = useChanged(type.id, u.value);
  const takeOut = async (source: string, key: string, under: boolean) => {
    ensureOk(await api.PUT("/api/universes/binding", { body: { source, key, under } }));
    await changed();
  };
  return (
    <section className="flex flex-col gap-1">
      <h2 className="font-semibold">{t("UniverseCard.sites")}</h2>
      {u.sites.length === 0 && <p className="text-muted-foreground">{t("UniverseCard.noSites")}</p>}
      <ul className="flex flex-col divide-y rounded-lg border">
        {u.sites.map((s) => (
          <li key={`${s.source}/${s.key}`} className="flex flex-wrap items-center gap-2 px-3 py-1.5">
            <span className="text-xs text-muted-foreground">{sourceTitle(s.source)}</span>
            {s.url ? (
              <a href={s.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline">
                {s.name}
              </a>
            ) : (
              <span className="font-medium">{s.name}</span>
            )}
            <span className="text-xs text-muted-foreground tabular-nums">{t("UniverseCard.works", { count: s.works })}</span>
            {u.sites.length > 1 && (
              <span className="ml-auto flex gap-1">
                <AsyncButton size="xs" variant="ghost" onClick={() => takeOut(s.source, s.key, true)} title={t("UniverseCard.putDownHint")} icon={<ArrowDownToLineIcon />}>
                  {t("UniverseCard.putDown")}
                </AsyncButton>
                <AsyncButton size="xs" variant="ghost" onClick={() => takeOut(s.source, s.key, false)} title={t("UniverseCard.takeOutHint")} icon={<UnlinkIcon />}>
                  {t("UniverseCard.takeOut")}
                </AsyncButton>
              </span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The entries of the catalogue the universe is bound to, each unbound by its button, and the search to bind one more. */
function Entries({ type, universe: u, dictionary }: { type: ContentTypeInfo; universe: UniverseInfo; dictionary?: UniverseInfo[] }) {
  const { t } = useTranslation("universe");
  return (
    <section className="flex flex-col gap-2">
      <h2 className="font-semibold">{t("UniverseCard.entries")}</h2>
      {u.entries.length === 0 && <p className="text-muted-foreground">{t("UniverseCard.noEntries")}</p>}
      {u.entries.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {u.entries.map((e) => (
            <EntryRow key={e.value} type={type} universe={u} entry={e} />
          ))}
        </ul>
      )}
      <CatalogueSearch type={type} dictionary={dictionary} forUniverse={u} typed={u.entries.length === 0 ? u.name : ""} />
    </section>
  );
}

function EntryRow({ type, universe: u, entry: e }: { type: ContentTypeInfo; universe: UniverseInfo; entry: UniverseEntryInfo }) {
  const { t } = useTranslation("universe");
  const changed = useChanged(type.id, u.value);
  const [open, setOpen] = useState(false);
  const unbind = async () => {
    ensureOk(await api.PUT("/api/universes/entry", { body: { universe: u.value, catalogue: e.catalogue, entry: e.entry, bound: false } }));
    await changed();
  };
  return (
    <li className={cn("flex flex-col gap-2 px-3 py-2", open && "bg-muted/30")}>
      <div className="flex flex-wrap items-start gap-2">
        {u.characters && (
          <Button variant="ghost" size="icon-sm" onClick={() => setOpen(!open)} aria-expanded={open} title={t(open ? "UniverseCard.hideCharacters" : "UniverseCard.showCharacters")}>
            <ChevronRightIcon className={cn("transition-transform", open && "rotate-90")} />
          </Button>
        )}
        <div className="min-w-0 flex-1 basis-60">
          <span className="font-medium">{e.name}</span>
          {e.localName && <span className="ml-1.5 text-xs text-muted-foreground">{t("UniverseCard.catalogueName", { name: e.catalogueName })}</span>}
          <span className="ml-2 font-mono text-xs text-muted-foreground">{e.entry}</span>
          <a href={e.url} target="_blank" rel="noreferrer" className="ml-1.5 inline-flex align-middle text-muted-foreground hover:text-foreground" title={t("UniverseCard.wikidataPage")}>
            <ExternalLinkIcon className="size-3.5" />
          </a>
          <LocalNameEdit catalogue={e.catalogue} entry={e.entry} localName={e.localName} catalogueName={e.catalogueName} onSaved={changed} />
          {e.description && <div className="text-muted-foreground">{e.description}</div>}
          <div className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
            {u.characters && (
              <span>
                {e.total !== e.characters
                  ? t("UniverseCard.charactersOfTotal", { count: e.characters, total: e.total })
                  : t("UniverseCard.characters", { count: e.characters })}
              </span>
            )}
            <span title={formatDate(e.refreshedAt)}>{t("UniverseCard.refreshed", { ago: ago(e.refreshedAt) })}</span>
          </div>
        </div>
        <AsyncButton size="sm" variant="ghost" onClick={unbind} title={t("UniverseCard.unbindHint")} icon={<XIcon />}>
          {t("UniverseCard.unbind")}
        </AsyncButton>
      </div>
      {open && <EntryCharacters type={type} entry={entryRef(e)} />}
    </li>
  );
}

/** The universes above and under this one, each a link, the link taken back by ✕. */
function Around({ type, universe: u, dictionary }: { type: ContentTypeInfo; universe: UniverseInfo; dictionary?: UniverseInfo[] }) {
  const { t } = useTranslation("universe");
  const changed = useChanged(type.id, u.value);
  if (u.parents.length === 0 && u.children.length === 0) return null;
  const nameOf = (v: string) => dictionary?.find((d) => d.value === v)?.name ?? v;
  const untie = async (other: string) => {
    ensureOk(await api.PUT("/api/universes/relation", { body: { universe: u.value, other, relation: "NONE" } }));
    await changed();
  };
  const row = (label: string, values: string[]) =>
    values.length > 0 && (
      <div className="flex flex-wrap items-center gap-1">
        <span className="text-muted-foreground">{label}</span>
        {values.map((v) => (
          <span key={v} className="inline-flex items-center gap-0.5 rounded border px-1.5">
            <a href={screenHref({ kind: "universes", universe: v })} onClick={(e) => followLink(e, { kind: "universes", universe: v })} className="hover:underline">
              {nameOf(v)}
            </a>
            <AsyncButton size="icon-xs" variant="ghost" onClick={() => untie(v)} title={t("UniverseCard.untie")} aria-label={t("UniverseCard.untie")} icon={<XIcon />} />
          </span>
        ))}
      </div>
    );
  return (
    <section className="flex flex-col gap-1">
      {row(t("UniverseCard.parents"), u.parents)}
      {row(t("UniverseCard.children"), u.children)}
    </section>
  );
}
