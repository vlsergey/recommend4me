import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type SiteFandom, type UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { toast } from "@/components/ui/toast";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";

/** The fandoms shown at first, and added by "more". */
const PAGE = 50;

/** Which fandoms the list shows. */
type FandomsShown = "unbound" | "bound" | "all";

const selectClass =
  "h-7 rounded-md border border-input bg-transparent px-1.5 text-xs text-foreground outline-none focus-visible:border-ring disabled:opacity-50 dark:bg-input/30";

/**
 * THE FANDOMS OF THE SITES, BOUND TO THE UNIVERSES OF THE DICTIONARY by the user: every work of a
 * fandom bound is drafted with its universes — a value it has till the user says otherwise, what the
 * models of the universes and of the characters learn from. The fandoms of the most works first.
 */
export function SiteFandoms({ type, dictionary }: { type: ContentTypeInfo; dictionary?: UniverseInfo[] }) {
  const { t } = useTranslation("universe");
  const [filter, setFilter] = useState("");
  const [source, setSource] = useState("");
  const [shown, setShown] = useState<FandomsShown>("all");
  const [limit, setLimit] = useState(PAGE);
  const fandoms = useQuery({
    queryKey: ["universes", type.id, "fandoms"],
    queryFn: async () => unwrap(await api.GET("/api/universes/fandoms", {})),
  });

  if (fandoms.isLoading) return <Loader2Icon className="size-4 animate-spin text-muted-foreground" />;
  if (fandoms.isError) return <p className="text-destructive">{t("SiteFandoms.loadFailed", { error: fandoms.error.message })}</p>;
  const all = fandoms.data ?? [];
  if (all.length === 0) return <p className="text-muted-foreground">{t("SiteFandoms.empty")}</p>;

  const sources = [...new Set(all.map((f) => f.source))];
  const ofSource = all.filter((f) => source === "" || f.source === source);
  const bound = ofSource.filter((f) => f.universes.length > 0).length;
  const needle = filter.trim().toLocaleLowerCase("ru-RU");
  const list = ofSource
    .filter((f) => shown === "all" || (f.universes.length > 0) === (shown === "bound"))
    .filter((f) => needle === "" || f.name.toLocaleLowerCase("ru-RU").includes(needle));
  const names = new Map((dictionary ?? []).map((u) => [u.value, u.name]));
  // The universes to bind to, by name
  const universes = [...(dictionary ?? [])].sort((a, b) => a.name.localeCompare(b.name, "ru"));

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setLimit(PAGE);
          }}
          placeholder={t("SiteFandoms.filterPlaceholder")}
          className="max-w-xs"
          type="search"
        />
        {sources.length > 1 && (
          <select
            value={source}
            onChange={(e) => {
              setSource(e.target.value);
              setLimit(PAGE);
            }}
            className={cn(selectClass, source !== "" && "border-primary")}
            aria-label={t("SiteFandoms.site")}
          >
            <option value="">{t("SiteFandoms.everySite")}</option>
            {sources.map((s) => (
              <option key={s} value={s}>
                {sourceTitle(s)}
              </option>
            ))}
          </select>
        )}
        <div className="flex gap-1" role="group" aria-label={t("SiteFandoms.shown")}>
          {(
            [
              ["all", t("SiteFandoms.all", { n: ofSource.length })],
              ["unbound", t("SiteFandoms.unbound", { n: ofSource.length - bound })],
              ["bound", t("SiteFandoms.bound", { n: bound })],
            ] as const
          ).map(([kind, label]) => (
            <Button
              key={kind}
              size="sm"
              variant={shown === kind ? "secondary" : "ghost"}
              aria-pressed={shown === kind}
              onClick={() => {
                setShown(kind);
                setLimit(PAGE);
              }}
            >
              {label}
            </Button>
          ))}
        </div>
        {needle !== "" && <span className="text-xs text-muted-foreground tabular-nums">{t("SiteFandoms.found", { n: list.length })}</span>}
      </div>
      <ul className="flex flex-col divide-y rounded-lg border">
        {list.slice(0, limit).map((f) => (
          <FandomRow key={`${f.source}/${f.fandom}`} type={type} fandom={f} names={names} universes={universes} />
        ))}
      </ul>
      {list.length > limit && (
        <Button size="sm" variant="ghost" className="self-start" onClick={() => setLimit(limit + PAGE)}>
          {t("SiteFandoms.more", { n: Math.min(PAGE, list.length - limit), left: list.length - limit })}
        </Button>
      )}
    </div>
  );
}

/** A fandom of a site: its works, the universes it is bound to — each taken back by its ✕ — and a choice of one more. */
function FandomRow({
  type,
  fandom: f,
  names,
  universes,
}: {
  type: ContentTypeInfo;
  fandom: SiteFandom;
  /** The names of the universes of the dictionary by their values. */
  names: Map<string, string>;
  universes: UniverseInfo[];
}) {
  const { t } = useTranslation("universe");
  const queryClient = useQueryClient();
  // The choice of a universe is at work: the select is busy while its own binding is
  const [binding, setBinding] = useState(false);
  const bind = async (universe: string, bound: boolean) => {
    ensureOk(await api.PUT("/api/universes/fandoms", { body: { source: f.source, fandom: f.fandom, universe, bound } }));
    // Shown once kept: the list read again
    await queryClient.invalidateQueries({ queryKey: ["universes", type.id, "fandoms"] });
  };
  const choose = async (universe: string) => {
    if (universe === "") return;
    setBinding(true);
    try {
      await bind(universe, true);
    } catch (e) {
      toast.add({ title: t("SiteFandoms.bindFailed", { error: e instanceof Error ? e.message : String(e) }), type: "error" });
    } finally {
      setBinding(false);
    }
  };
  const free = universes.filter((u) => !f.universes.includes(u.value));

  return (
    <li className="flex flex-wrap items-center gap-x-2 gap-y-1 px-3 py-1.5 text-sm">
      <span className="w-10 shrink-0 truncate text-xs text-muted-foreground" title={sourceTitle(f.source)}>
        {sourceShort(f.source)}
      </span>
      <span className="min-w-0 flex-1 basis-48 font-medium">{f.name}</span>
      <span className="shrink-0 text-xs text-muted-foreground tabular-nums" title={t("SiteFandoms.worksHint")}>
        {t("SiteFandoms.works", { count: f.works })}
      </span>
      <div className="flex flex-wrap items-center gap-1">
        {f.universes.map((u) => (
          <span key={u} className="inline-flex items-center gap-0.5 rounded-md bg-secondary py-0.5 pr-0.5 pl-2 text-xs">
            {names.get(u) ?? u}
            <AsyncButton size="icon-xs" variant="ghost" onClick={() => bind(u, false)} title={t("SiteFandoms.unbindHint")} icon={<XIcon />} />
          </span>
        ))}
        {free.length > 0 && (
          <select
            value=""
            disabled={binding}
            onChange={(e) => void choose(e.target.value)}
            className={selectClass}
            title={t("SiteFandoms.bindHint")}
          >
            <option value="">{binding ? t("SiteFandoms.binding") : t("SiteFandoms.bindTo")}</option>
            {free.map((u) => (
              <option key={u.value} value={u.value}>
                {u.name}
              </option>
            ))}
          </select>
        )}
      </div>
    </li>
  );
}
