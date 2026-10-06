import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRightIcon, ExternalLinkIcon, LinkIcon, Loader2Icon, OrbitIcon, PlusIcon, RefreshCwIcon, SearchIcon, Trash2Icon } from "lucide-react";
import { api, ensureOk, unwrap, type CharacterInfo, type ContentTypeInfo, type FoundUniverse, type UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { ago, formatDate, plural } from "@/i18n";
import { ITEMS } from "@/item/lists";
import { cn } from "@/lib/utils";

/** The cache key of the dictionary: ["universes", type]; a universe's characters lie under it. */
const UNIVERSES = "universes";

/** The dictionary of universes of a content type. */
export function useUniverses(typeId: string, enabled = true) {
  return useQuery({
    queryKey: [UNIVERSES, typeId],
    enabled,
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/universes", { params: { path: { type: typeId } } })),
  });
}

/** The query of the characters of a universe of the dictionary, as the catalogue gave them. */
export function charactersQuery(typeId: string, u: { catalogue: string; universe: string }) {
  return {
    queryKey: [UNIVERSES, typeId, u.catalogue, u.universe, "characters"],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/types/{type}/universes/{catalogue}/{universe}/characters", {
          params: { path: { type: typeId, catalogue: u.catalogue, universe: u.universe } },
        }),
      ),
  };
}

const sameUniverse =(a: { catalogue: string; universe: string }, b: { catalogue: string; universe: string }) =>
  a.catalogue === b.catalogue && a.universe === b.universe;

/**
 * The dictionary of universes of a content type whose works may be fan fiction: the universes the
 * user agreed on, each with its characters as the catalogue gave them. The catalogue is asked
 * only by the user's buttons.
 */
export function UniverseButton({ type }: { type: ContentTypeInfo }) {
  if (!type.universes) return null;
  return (
    <Dialog>
      <DialogTrigger
        render={<Button variant="ghost" className="px-2 lg:px-3" aria-label="Вселенные" title="Вселенные фанфиков: словарь и персонажи" />}
      >
        <OrbitIcon /> <span className="hidden lg:inline">Вселенные</span>
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
        <UniverseDetails type={type} />
      </DialogContent>
    </Dialog>
  );
}

/**
 * What to ask again after the dictionary changed. The universes are the values of the facet
 * "universe", so its filter and its offers change with them; a removed universe takes the works'
 * links with it, so the works are asked again too.
 */
function useDictionaryChanged(typeId: string) {
  const queryClient = useQueryClient();
  return (worksChanged: boolean) =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: [UNIVERSES, typeId] }),
      queryClient.invalidateQueries({ queryKey: ["facets", typeId] }),
      queryClient.invalidateQueries({ queryKey: ["facet-values"] }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      worksChanged && queryClient.invalidateQueries({ queryKey: [ITEMS, typeId] }),
    ]);
}

function UniverseDetails({ type }: { type: ContentTypeInfo }) {
  const universes = useUniverses(type.id);

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <OrbitIcon className="size-5" /> Вселенные: {type.title.toLowerCase()}
        </DialogTitle>
        <DialogDescription>
          Словарь вселенных, по которым пишут фанфики, — с персонажами, какими их дают Викиданные. Связь работы со вселенной
          подтверждается или отклоняется в окне работы, как любое значение признака. Викиданные спрашиваются только по кнопкам
          этого окна; персонажи хранятся у вас.
        </DialogDescription>
      </DialogHeader>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">Добавить из Викиданных</h4>
        <CatalogueSearch type={type} dictionary={universes.data} />
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="flex items-center gap-2 font-semibold">
          В словаре
          {universes.data && <span className="font-normal text-muted-foreground tabular-nums">{universes.data.length}</span>}
          {universes.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        </h4>
        {universes.isError && <p className="text-destructive">Не удалось загрузить словарь: {universes.error.message}</p>}
        {universes.data && universes.data.length === 0 && (
          <p className="text-muted-foreground">Словарь пуст. Найдите вселенную в Викиданных выше и добавьте её.</p>
        )}
        {universes.data && universes.data.length > 0 && (
          <ul className="flex flex-col divide-y rounded-lg border">
            {universes.data.map((u) => (
              <UniverseRow key={u.value} type={type} universe={u} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

/** A universe of the dictionary: its numbers, its buttons, and its characters when expanded. */
function UniverseRow({ type, universe: u }: { type: ContentTypeInfo; universe: UniverseInfo }) {
  const changed = useDictionaryChanged(type.id);
  const [expanded, setExpanded] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const ref = { catalogue: u.catalogue, universe: u.universe };
  // The spinner stays until the list shows what the catalogue gave
  const refresh = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/types/{type}/universes", { params: { path: { type: type.id } }, body: ref })),
    onSuccess: () => changed(false),
  });
  const remove = useMutation({
    mutationFn: async () =>
      ensureOk(await api.DELETE("/api/types/{type}/universes/{catalogue}/{universe}", { params: { path: { type: type.id, ...ref } } })),
    onSuccess: () => changed(true),
  });

  return (
    <li className="flex flex-col gap-2 px-3 py-2">
      <div className="flex flex-wrap items-start gap-2">
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={() => setExpanded(!expanded)}
          aria-expanded={expanded}
          aria-label={expanded ? "Скрыть персонажей" : "Показать персонажей"}
          title={expanded ? "Скрыть персонажей" : "Показать персонажей"}
        >
          <ChevronRightIcon className={cn("transition-transform", expanded && "rotate-90")} />
        </Button>
        <div className="min-w-0 flex-1">
          <a href={u.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline" title="Страница в Викиданных">
            {u.name}
          </a>
          {u.description && <div className="text-muted-foreground">{u.description}</div>}
          <div className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
            <span>{plural(u.characters, "персонаж", "персонажа", "персонажей")}</span>
            <span>· {plural(u.works, "работа", "работы", "работ")}</span>
            <span title={`Персонажи спрошены ${formatDate(u.refreshedAt)}`}>· обновлено {ago(u.refreshedAt)}</span>
            <span title={formatDate(u.addedAt)}>· добавлено {ago(u.addedAt)}</span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1">
          <Button
            size="sm"
            variant="outline"
            onClick={() => refresh.mutate()}
            disabled={refresh.isPending || remove.isPending}
            title="Заново спросить у Викиданных персонажей этой вселенной"
          >
            {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
            {refresh.isPending ? "Обновляется…" : "Обновить персонажей"}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirming(true)} disabled={confirming || remove.isPending} title="Удалить из словаря">
            <Trash2Icon /> Удалить
          </Button>
        </div>
      </div>

      {refresh.isError && <p className="text-destructive">Не удалось обновить: {refresh.error.message}</p>}
      {confirming && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/5 p-2">
          <span className="min-w-0 flex-1">Вселенная и все связи работ с ней будут удалены.</span>
          <Button size="sm" variant="destructive" onClick={() => remove.mutate()} disabled={remove.isPending}>
            {remove.isPending ? <Loader2Icon className="animate-spin" /> : <Trash2Icon />} Удалить
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirming(false)} disabled={remove.isPending}>
            Отмена
          </Button>
          {remove.isError && <p className="w-full text-destructive">Не удалось удалить: {remove.error.message}</p>}
        </div>
      )}
      {expanded && <Characters type={type} universe={u} />}
    </li>
  );
}

/** Every character of a universe as the dictionary keeps it, with a line to find one by any of its names. */
function Characters({ type, universe: u }: { type: ContentTypeInfo; universe: UniverseInfo }) {
  const [filter, setFilter] = useState("");
  const characters = useQuery(charactersQuery(type.id, u));

  if (characters.isLoading) return <Loader2Icon className="ml-9 size-4 animate-spin text-muted-foreground" />;
  if (characters.isError) return <p className="ml-9 text-destructive">Не удалось загрузить персонажей: {characters.error.message}</p>;
  const all = characters.data ?? [];
  if (all.length === 0) return <p className="ml-9 text-muted-foreground">Викиданные не дали этой вселенной ни одного персонажа.</p>;

  const needle = filter.trim().toLocaleLowerCase("ru-RU");
  const shown = needle === "" ? all : all.filter((c) => c.names.some((n) => n.toLocaleLowerCase("ru-RU").includes(needle)));

  return (
    <div className="ml-9 flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Найти персонажа по любому имени" className="max-w-xs" type="search" />
        <span className="text-xs text-muted-foreground tabular-nums">{needle === "" ? all.length : `${shown.length} из ${all.length}`}</span>
      </div>
      <ul className="flex max-h-96 flex-col divide-y overflow-y-auto rounded-lg border">
        {shown.map((c) => (
          <CharacterRow key={c.character} character={c} />
        ))}
      </ul>
    </div>
  );
}

function CharacterRow({ character: c }: { character: CharacterInfo }) {
  const [main, ...others] = c.names;
  return (
    <li className="flex items-start gap-2 px-3 py-1.5">
      <div className="min-w-0 flex-1">
        <span className="font-medium">{main ?? c.character}</span>
        {others.length > 0 && <span className="text-muted-foreground"> · {others.join(", ")}</span>}
        {c.description && <div className="text-xs text-muted-foreground">{c.description}</div>}
      </div>
      <a href={c.url} target="_blank" rel="noreferrer" className="mt-0.5 shrink-0 text-muted-foreground hover:text-foreground" title="Страница в Викиданных">
        <ExternalLinkIcon className="size-3.5" />
      </a>
    </li>
  );
}

/**
 * A work the universes found are for: a universe found is added to the dictionary and linked to
 * the work at once, and one of the dictionary already is linked by a button of its own.
 */
export type UniverseForWork = {
  /** The work has the universe of the value (and the user did not take it away). */
  linked: (value: string) => boolean;
  /** Links the work to the universe of the value. */
  link: (value: string) => Promise<unknown>;
};

/**
 * The search of the catalogue by a name: on the button or Enter only, never as the user types.
 * Each universe found is added to the dictionary by its button — and, with [forWork], linked to
 * that work as well.
 */
export function CatalogueSearch({ type, dictionary, forWork }: { type: ContentTypeInfo; dictionary?: UniverseInfo[]; forWork?: UniverseForWork }) {
  const [query, setQuery] = useState("");
  const search = useMutation({
    mutationFn: async (q: string) =>
      unwrap(await api.GET("/api/types/{type}/universes/search", { params: { path: { type: type.id }, query: { query: q } } })),
  });
  const submit = () => {
    const q = query.trim();
    if (q !== "") search.mutate(q);
  };

  return (
    <div className="flex flex-col gap-2 text-sm">
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Название вселенной: «Гарри Поттер», «Наруто»…" />
        <Button type="submit" disabled={search.isPending || query.trim() === ""}>
          {search.isPending ? <Loader2Icon className="animate-spin" /> : <SearchIcon />} Найти
        </Button>
      </form>
      {search.isError && <p className="text-destructive">Поиск не удался: {search.error.message}</p>}
      {search.data && search.data.length === 0 && <p className="text-muted-foreground">По запросу «{search.variables}» ничего не нашлось.</p>}
      {search.data && search.data.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {search.data.map((f) => {
            // The dictionary as it is now, not as it was at the search: a universe added or removed since shows so
            const entry = dictionary?.find((u) => sameUniverse(u, f));
            return (
              <FoundRow
                key={`${f.catalogue}:${f.universe}`}
                type={type}
                found={f}
                added={dictionary ? entry !== undefined : f.added}
                entry={entry}
                forWork={forWork}
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
  added,
  entry,
  forWork,
}: {
  type: ContentTypeInfo;
  found: FoundUniverse;
  added: boolean;
  /** The universe as the dictionary has it, when it has it. */
  entry?: UniverseInfo;
  forWork?: UniverseForWork;
}) {
  const changed = useDictionaryChanged(type.id);
  // The spinner stays until the dictionary below shows the universe (and the work its link)
  const add = useMutation({
    mutationFn: async () => {
      const universe = unwrap(
        await api.POST("/api/types/{type}/universes", { params: { path: { type: type.id } }, body: { catalogue: f.catalogue, universe: f.universe } }),
      );
      if (forWork) await forWork.link(universe.value);
      return universe;
    },
    onSettled: () => changed(false),
  });

  if (forWork)
    return (
      <li className="flex flex-col gap-1 px-3 py-2">
        <div className="flex items-start gap-2">
          <FoundName found={f} />
          {entry && forWork.linked(entry.value) ? (
            <span className="shrink-0 text-xs text-muted-foreground">уже у работы</span>
          ) : entry ? (
            <AsyncButton size="sm" variant="outline" onClick={() => forWork.link(entry.value)} title="Вселенная уже в словаре: связать с ней работу" icon={<LinkIcon />}>
              Связать с работой
            </AsyncButton>
          ) : added ? (
            <span className="shrink-0 text-xs text-muted-foreground">уже в словаре</span>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => add.mutate()}
              disabled={add.isPending}
              title="Добавить в словарь вместе с персонажами из Викиданных и связать с ней работу"
            >
              {add.isPending ? <Loader2Icon className="animate-spin" /> : <PlusIcon />}
              {add.isPending ? "Добавляется…" : "Добавить и связать"}
            </Button>
          )}
        </div>
        {add.isError && <p className="text-destructive">Не удалось добавить: {add.error.message}</p>}
      </li>
    );

  return (
    <li className="flex flex-col gap-1 px-3 py-2">
      <div className="flex items-start gap-2">
        <FoundName found={f} />
        {added ? (
          <span className="shrink-0 text-xs text-muted-foreground">уже в словаре</span>
        ) : (
          <Button size="sm" variant="outline" onClick={() => add.mutate()} disabled={add.isPending} title="Добавить в словарь вместе с персонажами из Викиданных">
            {add.isPending ? <Loader2Icon className="animate-spin" /> : <PlusIcon />}
            {add.isPending ? "Добавляется…" : "Добавить"}
          </Button>
        )}
      </div>
      {add.isError && <p className="text-destructive">Не удалось добавить: {add.error.message}</p>}
    </li>
  );
}

function FoundName({ found: f }: { found: FoundUniverse }) {
  return (
    <div className="min-w-0 flex-1">
      <a href={f.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline" title="Страница в Викиданных">
        {f.name}
      </a>
      <span className="ml-2 font-mono text-xs text-muted-foreground">{f.universe}</span>
      {f.description && <div className="text-muted-foreground">{f.description}</div>}
    </div>
  );
}
