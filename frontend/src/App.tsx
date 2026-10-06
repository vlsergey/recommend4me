import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDownWideNarrowIcon, Gamepad2Icon, HeartIcon, KeyboardIcon, LayoutGridIcon, ListIcon, RefreshCcwIcon, SearchIcon, SettingsIcon, SparklesIcon } from "lucide-react";
import { api, unwrap, type GameSort, type GameView } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { cn } from "@/lib/utils";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { GameBoard, type Layout } from "@/games/GameBoard";
import { ImageProgress } from "@/images/ImageProgress";
import { ReviewProgress } from "@/reviews/ReviewProgress";
import { usePref } from "@/lib/usePref";
import { ModelButton } from "@/model/ModelDialog";
import { NOTHING_HIDDEN, PrefixFilters, type PrefixSelection } from "@/prefix/PrefixFilters";
import { useModel } from "@/model/useModel";
import { SettingsDialog } from "@/settings/SettingsDialog";
import { SyncControl } from "@/sync/SyncControl";
import { ThemeToggle } from "@/theme/ThemeToggle";

const VIEWS: { view: GameView; label: string }[] = [
  { view: "UNRATED", label: "Без оценки" },
  { view: "RATED", label: "Оценённые" },
  { view: "ALL", label: "Все" },
];

const HOTKEYS: [string, string][] = [
  ["/", "поиск"],
  ["← → ↑ ↓ / h j k l", "выбор игры"],
  ["Enter / Пробел", "открыть игру"],
  ["1 … 5", "оценка: не нравится … дайте ещё"],
  ["в окне игры: ← →", "предыдущая · следующая"],
  ["в окне игры: o", "открыть тред"],
  ["Esc", "закрыть окно"],
];

export function App() {
  const queryClient = useQueryClient();
  const [view, setView] = usePref<GameView>("view", "UNRATED");
  const [sort, setSort] = usePref<GameSort>("sort", "SCORE");
  const [layout, setLayout] = usePref<Layout>("layout", "grid");
  const [prefixes, setPrefixes] = usePref<PrefixSelection>("hiddenPrefixes", NOTHING_HIDDEN);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [total, setTotal] = useState<number | undefined>();
  const [settingsOpen, setSettingsOpen] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(t);
  }, [searchInput]);

  const settings = useQuery({ queryKey: ["settings"], queryFn: async () => unwrap(await api.GET("/api/settings")) });

  // After a retraining the list is NOT re-sorted under the cursor; a button offers it instead
  const model = useModel();
  const shownTrainedAt = useRef<string | undefined>(undefined);
  const [fresh, setFresh] = useState(false);
  useEffect(() => {
    const trainedAt = model.data?.trainedAt ?? "";
    if (shownTrainedAt.current === undefined) shownTrainedAt.current = trainedAt;
    else if (trainedAt !== shownTrainedAt.current && !model.data?.training) setFresh(true);
  }, [model.data]);
  const resort = () => {
    shownTrainedAt.current = model.data?.trainedAt ?? "";
    setFresh(false);
    queryClient.invalidateQueries({ queryKey: ["games"] });
  };

  const onTotal = useCallback((t: number | undefined) => setTotal(t), []);

  // "/" jumps to the search from anywhere but a text field
  const searchRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "/" || (e.target as HTMLElement).closest("input, textarea, [role=dialog]")) return;
      e.preventDefault();
      searchRef.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-40 border-b bg-background/85 backdrop-blur supports-backdrop-filter:bg-background/70">
        <div className="mx-auto flex h-14 max-w-[1800px] items-center gap-1 px-4 sm:gap-3">
          <div className="flex items-center gap-2 font-semibold">
            <Gamepad2Icon className="size-5" />
            <span className="hidden md:inline">F95 рекомендации</span>
          </div>
          {/* On a phone the progress takes what the icons leave; on a wide screen it sits in the middle */}
          <div className="mx-1 min-w-0 flex-1 sm:mx-auto sm:flex-none">
            <SyncControl settings={settings.data} onNeedCookie={() => setSettingsOpen(true)} />
          </div>
          <ImageProgress />
          <ReviewProgress />
          <ModelButton />
          <ThemeToggle />
          <Button variant="ghost" size="icon" onClick={() => setSettingsOpen(true)} aria-label="Настройки" title="Настройки" className="relative">
            <SettingsIcon />
            {settings.data && !settings.data.cookieSet && <span className="absolute top-1.5 right-1.5 size-2 rounded-full bg-maybe" />}
          </Button>
        </div>
      </header>

      <div className="mx-auto max-w-[1800px] px-4">
        <div className="flex flex-wrap items-center gap-3 py-4">
          <Tabs value={view} onValueChange={(v) => setView(v as GameView)}>
            <TabsList>
              {VIEWS.map((v) => (
                <TabsTrigger key={v.view} value={v.view} className="px-3">
                  {v.label}
                  {view === v.view && total !== undefined && <span className="ml-1 text-xs tabular-nums text-muted-foreground">{total}</span>}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          <div className="relative w-full sm:w-96">
            <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              ref={searchRef}
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              onKeyDown={(e) => e.key === "Escape" && (setSearchInput(""), e.currentTarget.blur())}
              placeholder="Поиск по смыслу и словам: «sci-fi с романтикой», имя разработчика…"
              className="pr-8 pl-8"
              type="search"
            />
            <kbd className="pointer-events-none absolute top-1/2 right-2.5 hidden -translate-y-1/2 rounded border px-1 font-mono text-[10px] text-muted-foreground pointer-fine:block">/</kbd>
          </div>

          <Tabs value={search ? "RELEVANCE" : sort} onValueChange={(v) => v !== "RELEVANCE" && setSort(v as GameSort)}>
            <TabsList>
              {search && (
                <TabsTrigger value="RELEVANCE" className="px-3">
                  <SearchIcon /> По релевантности
                </TabsTrigger>
              )}
              {/* A phone's width holds the three only with short names; while searching, the others are off and hidden there */}
              <TabsTrigger value="SCORE" className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")} disabled={!!search}>
                <SparklesIcon /> <span className="sm:hidden">Прогноз</span>
                <span className="hidden sm:inline">По прогнозу</span>
              </TabsTrigger>
              <TabsTrigger value="UPDATED" className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")} disabled={!!search}>
                <ArrowDownWideNarrowIcon /> <span className="sm:hidden">Дата</span>
                <span className="hidden sm:inline">По дате</span>
              </TabsTrigger>
              <TabsTrigger
                value="POPULAR"
                className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")}
                disabled={!!search}
                title="Самые залайканные — те, во что вы скорее всего уже играли"
              >
                <HeartIcon /> <span className="sm:hidden">Популярные</span>
                <span className="hidden sm:inline">По популярности</span>
              </TabsTrigger>
            </TabsList>
          </Tabs>

          <PrefixFilters value={prefixes} onChange={setPrefixes} />

          {fresh && (
            <Button variant="secondary" size="sm" onClick={resort}>
              <RefreshCcwIcon /> Прогнозы обновились — пересортировать
            </Button>
          )}

          <div className="ml-auto flex items-center gap-1">
            <Tooltip>
              {/* No keyboard on a touch screen, no hotkeys to show */}
              <TooltipTrigger render={<Button variant="ghost" size="icon" aria-label="Горячие клавиши" className="hidden pointer-fine:inline-flex" />}>
                <KeyboardIcon />
              </TooltipTrigger>
              <TooltipContent side="bottom" className="block">
                <table>
                  <tbody>
                    {HOTKEYS.map(([keys, what]) => (
                      <tr key={keys}>
                        <td className="pr-3 font-mono">{keys}</td>
                        <td>{what}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TooltipContent>
            </Tooltip>
            <Tabs value={layout} onValueChange={(v) => setLayout(v as Layout)}>
              <TabsList>
                <TabsTrigger value="grid" aria-label="Карточки" title="Карточки">
                  <LayoutGridIcon />
                </TabsTrigger>
                <TabsTrigger value="list" aria-label="Список" title="Список">
                  <ListIcon />
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
        </div>

        <main className="pb-10">
          <GameBoard view={view} sort={sort} search={search} prefixes={prefixes} layout={layout} onTotal={onTotal} />
        </main>
      </div>

      {settings.data && <SettingsDialog settings={settings.data} open={settingsOpen} onOpenChange={setSettingsOpen} />}
    </div>
  );
}
