import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, RefreshCwIcon, RotateCcwIcon, ScaleIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type FandomWayComparison, type ModelState } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { formatDate, number } from "@/i18n/format";
import { cn } from "@/lib/utils";

/** How often a comparison under way is asked for again. */
const POLL_MS = 1000;

type Way = FandomWayComparison["ways"][number]["way"];

const comparisonKey = (type: string) => ["fandom-way-comparison", type];

/**
 * THE LIKENESS OF THE FANDOMS' NAMES, of the type that keeps the universes: learnt of the user's word on the universes,
 * how right it is on the held out beside the encoder's own, the changes of no word of the user's counted since and how
 * many learn it again by themselves; the way a site's fandom is read for the universes alike one, chosen here or in the
 * window comparing the ways.
 */
export function FandomLikenessCard({
  type,
  m,
  card,
  changed,
}: {
  type: ContentTypeInfo;
  m: ModelState;
  /** The card of a model of the page, given its words and its actions. */
  card: (props: { title: string; hint: string; examples?: string; method?: string; note?: string; actions: React.ReactNode }) => React.ReactNode;
  changed: () => Promise<unknown>;
}) {
  const { t } = useTranslation("model");
  const choose = useMutation({
    mutationFn: async (method: string) => {
      ensureOk(await api.PUT("/api/types/{type}/models/fandoms/way", { params: { path: { type: type.id } }, body: { method } }));
    },
    onSuccess: () => changed(),
  });
  const fit = async () => {
    ensureOk(await api.POST("/api/types/{type}/models/fandoms/fit", { params: { path: { type: type.id } } }));
    await changed();
  };
  const notes = [
    m.baseline && t("FandomLikeness.baseline", { value: Math.round(m.baseline.value * 1000) / 10 }),
    m.autoChanges !== undefined && t("FandomLikeness.autoChanges", { count: m.autoChanges, due: m.autoChangesDue ?? 0 }),
  ].filter(Boolean);
  return card({
    title: t("FandomLikeness.title"),
    hint: t("FandomLikeness.hint"),
    examples: m.items !== undefined ? t("FandomLikeness.universes", { count: m.items }) : undefined,
    method: m.method ? t("FandomLikeness.wayUsed", { way: t(`FandomLikeness.way.${m.method as Way}`) }) : undefined,
    note: notes.join(" · ") || undefined,
    actions: (
      <>
        <label className="flex items-center gap-1.5 text-xs text-muted-foreground" title={t("FandomLikeness.wayHint")}>
          {t("FandomLikeness.wayChoice")}
          <select
            value={m.method ?? ""}
            disabled={choose.isPending}
            onChange={(e) => choose.mutate(e.target.value)}
            className="h-8 rounded-md border border-input bg-transparent px-1.5 text-sm text-foreground outline-none focus-visible:border-ring dark:bg-input/30"
          >
            {(m.methods ?? []).map((way) => (
              <option key={way} value={way}>
                {t(`FandomLikeness.way.${way as Way}`)}
              </option>
            ))}
          </select>
        </label>
        <WayComparisonDialog type={type} current={m.method ?? ""} onChoose={(way) => choose.mutate(way)} busy={choose.isPending} />
        <AsyncButton size="sm" variant="outline" onClick={fit} disabled={m.running} icon={<RefreshCwIcon />} title={t("FandomLikeness.fitHint")}>
          {t("ModelsScreen.retrain")}
        </AsyncButton>
      </>
    ),
  });
}

/**
 * The ways a site's fandom is read compared, in a window of their own: the comparison runs in the queue when the user
 * asks — a minute and more of the card over every fandom —, the window asking for it till it is done. Closing the
 * window does not stop it; opening it again shows it as it stands.
 */
function WayComparisonDialog({ type, current, onChoose, busy }: { type: ContentTypeInfo; current: string; onChoose: (way: string) => void; busy: boolean }) {
  const { t } = useTranslation("model");
  const [open, setOpen] = useState(false);
  const queryClient = useQueryClient();
  const comparison = useQuery({
    queryKey: comparisonKey(type.id),
    enabled: open,
    queryFn: async (): Promise<FandomWayComparison | null> => {
      const { data, response } = await api.GET("/api/types/{type}/models/fandoms/comparison", { params: { path: { type: type.id } } });
      if (response.status === 404) return null;
      return data ?? null;
    },
    refetchInterval: (q) => (q.state.data && !q.state.data.finishedAt ? POLL_MS : false),
  });
  const start = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/types/{type}/models/fandoms/comparison", { params: { path: { type: type.id } } })),
    onSuccess: (data) => queryClient.setQueryData(comparisonKey(type.id), data),
  });
  const c = comparison.data;
  const running = c ? !c.finishedAt : false;
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger render={<Button size="sm" variant="ghost" title={t("FandomLikeness.compareHint")} />}>
        <ScaleIcon /> {t("ScorerComparisonDialog.compare")}
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ScaleIcon className="size-5" /> {t("FandomLikeness.compareTitle")}
          </DialogTitle>
          <DialogDescription>{t("FandomLikeness.compareDescription")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {c ? (
            <span>
              {t("ScorerComparisonDialog.started", { date: formatDate(c.startedAt) })}
              {c.finishedAt && ` · ${t("ScorerComparisonDialog.finished", { date: formatDate(c.finishedAt) })}`}
              {c.millis != null && ` · ${t("ScorerComparisonDialog.seconds", { value: Math.round(c.millis / 100) / 10 })}`}
            </span>
          ) : (
            comparison.isSuccess && <span>{t("FandomLikeness.notCompared")}</span>
          )}
          <Button size="sm" variant="outline" className="ml-auto" disabled={running || start.isPending} onClick={() => start.mutate()}>
            {running || start.isPending ? <Loader2Icon className="animate-spin" /> : <RotateCcwIcon />}
            {running ? t("ScorerComparisonDialog.computing") : c ? t("ScorerComparisonDialog.recompute") : t("FandomLikeness.compareStart")}
          </Button>
        </div>
        {start.isError && <div className="text-sm text-destructive">{t("ScorerComparisonDialog.startFailed", { error: String(start.error.message) })}</div>}
        {c?.error && <div className="text-sm text-destructive">{t("FandomLikeness.compareFailed", { error: c.error })}</div>}
        {c && c.ways.length > 0 && <WayTable comparison={c} current={current} onChoose={onChoose} busy={busy} />}
      </DialogContent>
    </Dialog>
  );
}

function WayTable({ comparison, current, onChoose, busy }: { comparison: FandomWayComparison; current: string; onChoose: (way: string) => void; busy: boolean }) {
  const { t } = useTranslation("model");
  const best = Math.max(...comparison.ways.map((w) => w.reciprocal ?? -1));
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left font-medium">{t("FandomLikeness.wayColumn")}</th>
            <th className="px-3 py-2 text-right font-medium" title={t("FandomLikeness.firstHint")}>
              {t("FandomLikeness.first")}
            </th>
            <th className="px-3 py-2 text-right font-medium" title={t("FandomLikeness.reciprocalHint")}>
              1/rank
            </th>
            <th className="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          {comparison.ways.map((w) => {
            const chosen = w.way === current;
            return (
              <tr key={w.way} className={cn("border-t", chosen && "bg-yes/10 font-medium")}>
                <td className="px-3 py-1.5">
                  {t(`FandomLikeness.way.${w.way}`)}
                  {chosen && <span className="ml-1 text-xs text-muted-foreground">{t("ScorerComparisonDialog.main")}</span>}
                </td>
                {w.first != null && w.reciprocal != null ? (
                  <>
                    <td className="px-3 py-1.5 text-right tabular-nums">{number(Math.round(w.first * 1000) / 10)}%</td>
                    <td className={cn("px-3 py-1.5 text-right tabular-nums", w.reciprocal === best && "font-semibold text-yes")}>{w.reciprocal.toFixed(3)}</td>
                  </>
                ) : (
                  <td colSpan={2} className="px-3 py-1.5 text-right text-muted-foreground">
                    {t("FandomLikeness.noneAsked")}
                  </td>
                )}
                <td className="px-3 py-1.5 text-right">
                  {!chosen && (
                    <Button size="xs" variant="outline" disabled={busy} onClick={() => onChoose(w.way)}>
                      {t("ScorerComparisonDialog.makeMain")}
                    </Button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
