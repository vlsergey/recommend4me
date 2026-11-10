import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { LinkIcon, Loader2Icon, PlusIcon, SearchIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo, type FoundUniverse, type UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useDictionaryChanged } from "./universeQueries";

/**
 * A work the universes found are for: an entry found is made a universe and linked to the work at
 * once, and a universe bound to it already is linked by a button of its own.
 */
export type UniverseForWork = {
  /** The work has the universe of the value (and the user did not take it away). */
  linked: (value: string) => boolean;
  /** Links the work to the universe of the value. */
  link: (value: string) => Promise<unknown>;
};

/**
 * The search of the catalogue by a name: on the button or Enter only, never as the user types.
 * Each entry found is made a universe by its button — with [forWork], linked to that work as well;
 * with [forUniverse], bound to that universe instead.
 */
export function CatalogueSearch({
  type,
  dictionary,
  forWork,
  forUniverse,
  word = "",
  typed = "",
}: {
  type: ContentTypeInfo;
  dictionary?: UniverseInfo[];
  forWork?: UniverseForWork;
  /** The universe the entries found are bound to. */
  forUniverse?: UniverseInfo;
  /** A site's word it is opened with: typed, and looked for at once. */
  word?: string;
  /** A text it is opened with, typed only: the catalogue is asked by the user's button. */
  typed?: string;
}) {
  const { t } = useTranslation("universe");
  const [query, setQuery] = useState(word || typed);
  const search = useMutation({
    mutationFn: async (q: string) => unwrap(await api.GET("/api/universes/search", { params: { query: { query: q } } })),
  });
  const submit = () => {
    const q = query.trim();
    if (q !== "") search.mutate(q);
  };
  const { mutate } = search;
  useEffect(() => {
    if (word !== "") mutate(word);
  }, [word, mutate]);

  return (
    <div className="flex flex-col gap-2 text-sm">
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t("CatalogueSearch.placeholder")} />
        <Button type="submit" disabled={search.isPending || query.trim() === ""}>
          {search.isPending ? <Loader2Icon className="animate-spin" /> : <SearchIcon />} {t("CatalogueSearch.search")}
        </Button>
      </form>
      {search.isError && <p className="text-destructive">{t("CatalogueSearch.searchFailed", { error: search.error.message })}</p>}
      {search.data && search.data.length === 0 && <p className="text-muted-foreground">{t("CatalogueSearch.nothingFound", { query: search.variables })}</p>}
      {search.data && search.data.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {search.data.map((f) => {
            // The universes as they are now, not as they were at the search: an entry bound or unbound since shows so
            const bound = dictionary
              ? dictionary.find((u) => u.entries.some((e) => e.catalogue === f.catalogue && e.entry === f.universe))
              : undefined;
            return (
              <FoundRow
                key={`${f.catalogue}:${f.universe}`}
                type={type}
                found={f}
                bound={dictionary ? bound?.value : f.bound}
                forWork={forWork}
                forUniverse={forUniverse}
              />
            );
          })}
        </ul>
      )}
    </div>
  );
}

function FoundRow({
  type,
  found: f,
  bound,
  forWork,
  forUniverse,
}: {
  type: ContentTypeInfo;
  found: FoundUniverse;
  /** The universe the entry is bound to, when one is. */
  bound?: string;
  forWork?: UniverseForWork;
  forUniverse?: UniverseInfo;
}) {
  const { t } = useTranslation("universe");
  const changed = useDictionaryChanged(type.id);
  // The spinner stays until the universes show the entry (and the work its link)
  const add = useMutation({
    mutationFn: async () => {
      if (forUniverse) {
        return unwrap(
          await api.PUT("/api/universes/entry", {
            body: { universe: forUniverse.value, catalogue: f.catalogue, entry: f.universe, bound: true },
          }),
        );
      }
      const universe = unwrap(await api.POST("/api/universes", { body: { catalogue: f.catalogue, universe: f.universe } }));
      if (forWork) await forWork.link(universe.value);
      return universe;
    },
    onSettled: () => changed(false),
  });

  const button = () => {
    if (forUniverse) {
      if (forUniverse.entries.some((e) => e.catalogue === f.catalogue && e.entry === f.universe))
        return <span className="shrink-0 text-xs text-muted-foreground">{t("FoundRow.boundHere")}</span>;
      return (
        <Button size="sm" variant="outline" onClick={() => add.mutate()} disabled={add.isPending} title={t("FoundRow.bindHint")}>
          {add.isPending ? <Loader2Icon className="animate-spin" /> : <LinkIcon />}
          {add.isPending ? t("FoundRow.adding") : t("FoundRow.bind")}
        </Button>
      );
    }
    if (forWork && bound)
      return forWork.linked(bound) ? (
        <span className="shrink-0 text-xs text-muted-foreground">{t("FoundRow.linked")}</span>
      ) : (
        <AsyncButton size="sm" variant="outline" onClick={() => forWork.link(bound)} title={t("FoundRow.linkHint")} icon={<LinkIcon />}>
          {t("FoundRow.link")}
        </AsyncButton>
      );
    if (bound) return <span className="shrink-0 text-xs text-muted-foreground">{t("FoundRow.inDictionary")}</span>;
    return (
      <Button size="sm" variant="outline" onClick={() => add.mutate()} disabled={add.isPending} title={t(forWork ? "FoundRow.addAndLinkHint" : "FoundRow.addHint")}>
        {add.isPending ? <Loader2Icon className="animate-spin" /> : <PlusIcon />}
        {add.isPending ? t("FoundRow.adding") : t(forWork ? "FoundRow.addAndLink" : "FoundRow.add")}
      </Button>
    );
  };

  return (
    <li className="flex flex-col gap-1 px-3 py-2">
      <div className="flex items-start gap-2">
        <FoundName found={f} />
        {button()}
      </div>
      {add.isError && <p className="text-destructive">{t("FoundRow.addFailed", { error: add.error.message })}</p>}
    </li>
  );
}

function FoundName({ found: f }: { found: FoundUniverse }) {
  const { t } = useTranslation("universe");
  return (
    <div className="min-w-0 flex-1">
      <a href={f.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline" title={t("FoundName.wikidataPage")}>
        {f.name}
      </a>
      <span className="ml-2 font-mono text-xs text-muted-foreground">{f.universe}</span>
      {f.description && <div className="text-muted-foreground">{f.description}</div>}
    </div>
  );
}
