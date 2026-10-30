import { useEffect, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { UsersIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type AuthorKind, type ContentTypeInfo } from "@/api/client";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useRefresh } from "@/lib/useRefresh";
import { AuthorCard } from "./AuthorCard";
import { Offers } from "./AuthorOffers";
import { Accounts, authorName, authorsKey, EntryLinks } from "./pieces";

/** How many authors a page shows, and how many more each "more" adds. */
const PAGE = 50;

/** How often the lists are read again till the user chooses ([RefreshSelect]): never — they change by the user's answers. */
const EVERY_MS = 0;

/** How long the typing pauses before the search of an author is read. */
const SEARCH_SETTLE_MS = 300;

/** Which list the screen shows: the pairs offered, every author, the pairs said to be partly one, those told apart. */
type Shown = "offers" | "all" | "partial" | "different";

const KINDS: AuthorKind[] = ["ALL", "MERGED", "CATALOGUED"];

/**
 * THE SCREEN OF THE NORMALIZED AUTHORS of a content type: the authors of the sites, each at first a
 * writer of their own, merged by the user on the evidence of their works, never of their names. The
 * pairs offered are answered on where they stand — one author, different, partly one; every author
 * is found by a name on its own tab; a name anywhere opens the author's card, with their accounts, their
 * entry of Wikidata, every pair with them and their works ([AuthorCard]). Nothing read again for an
 * answer on the tabs: as often as chosen.
 */
export function AuthorsScreen({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("author");
  const [shown, setShown] = useState<Shown>("offers");
  const [every, setEvery] = useRefresh("authors.refresh", EVERY_MS);
  const [opened, setOpened] = useState<number | null>(null);
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-4">
      <header className="flex flex-col gap-1">
        <h1 className="flex items-center gap-2 text-xl font-semibold">
          <UsersIcon className="size-5" /> {t("AuthorsScreen.title")}
        </h1>
        <p className="text-sm text-muted-foreground">{t("AuthorsScreen.description")}</p>
      </header>
      <div className="flex flex-wrap items-center gap-2">
        <Tabs value={shown} onValueChange={(v) => setShown(v as Shown)}>
          <TabsList>
            <TabsTrigger value="offers">{t("AuthorsScreen.offers")}</TabsTrigger>
            <TabsTrigger value="all">{t("AuthorsScreen.all")}</TabsTrigger>
            <TabsTrigger value="partial">{t("AuthorsScreen.partial")}</TabsTrigger>
            <TabsTrigger value="different">{t("AuthorsScreen.different")}</TabsTrigger>
          </TabsList>
        </Tabs>
        <RefreshSelect every={every} onChange={setEvery} className="ml-auto" />
      </div>
      {shown === "all" ? (
        <AllAuthors type={type} every={every} onOpen={setOpened} />
      ) : (
        <Offers key={shown} type={type} said={shown === "offers" ? "OFFERED" : shown === "partial" ? "PARTIAL" : "DIFFERENT"} every={every} onOpen={setOpened} />
      )}
      {opened !== null && <AuthorCard key={opened} type={type} id={opened} onOpen={setOpened} onClose={() => setOpened(null)} />}
    </div>
  );
}

/** Every author with works, the most works first: found by a name, narrowed to the merged or those of an entry. */
function AllAuthors({ type, every, onOpen }: { type: ContentTypeInfo; every: number; onOpen: (id: number) => void }) {
  const { t } = useTranslation("author");
  const [kind, setKind] = useState<AuthorKind>("ALL");
  const [typed, setTyped] = useState("");
  const [search, setSearch] = useState("");
  const [limit, setLimit] = useState(PAGE);
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(typed.trim());
      setLimit(PAGE);
    }, SEARCH_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [typed]);
  const list = useQuery({
    queryKey: [...authorsKey(type.id), "list", kind, search, limit],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/authors", { params: { path: { type: type.id }, query: { kind, search: search || undefined, offset: 0, limit } } })),
    placeholderData: keepPreviousData,
    refetchInterval: every || false,
  });
  const data = list.data;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={t("All.search")} aria-label={t("All.search")} className="max-w-xs" />
        <Tabs
          value={kind}
          onValueChange={(v) => {
            setKind(v as AuthorKind);
            setLimit(PAGE);
          }}
        >
          <TabsList>
            {KINDS.map((k) => (
              <TabsTrigger key={k} value={k}>
                {t(`All.kind.${k}`)}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        {data && <span className="text-sm text-muted-foreground tabular-nums">{t("All.total", { count: data.total })}</span>}
      </div>
      {list.isLoading && <p className="text-sm text-muted-foreground">{t("Offers.loading")}</p>}
      {list.isError && <p className="text-sm text-destructive">{t("Offers.failed", { error: list.error.message })}</p>}
      {data && data.authors.length === 0 && <p className="text-sm text-muted-foreground">{t("All.none")}</p>}
      {data && data.authors.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {data.authors.map((a) => (
            <li key={a.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 px-3 py-1.5">
              <button type="button" className="min-w-0 truncate text-left font-medium underline-offset-2 hover:underline" title={t("AuthorHead.openHint")} onClick={() => onOpen(a.id)}>
                {authorName(a)}
              </button>
              <EntryLinks author={a} />
              <Accounts author={a} />
              <span className="ml-auto text-xs text-muted-foreground tabular-nums">{t("Card.works", { count: a.works })}</span>
            </li>
          ))}
        </ul>
      )}
      {data && data.total > data.authors.length && (
        <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("Offers.more", { count: Math.min(PAGE, data.total - data.authors.length) })}
        </Button>
      )}
    </div>
  );
}
