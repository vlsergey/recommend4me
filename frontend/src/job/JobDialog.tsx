import { CheckCircle2Icon, DatabaseIcon, DownloadIcon, SquareIcon, TriangleAlertIcon } from "lucide-react";
import type { JobStatus, SourceInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Progress } from "@/components/ui/progress";
import { catalogueLabel, compact, formatDate, MODE_LABEL, phaseLabel, timeLeft } from "@/i18n";
import { catalogueUnfinished, isActive, useCancelJob, useStartJob } from "./useJobs";

type Props = {
  source: SourceInfo | null;
  job?: JobStatus;
  onOpenChange: (open: boolean) => void;
  onNeedSettings: () => void;
};

/**
 * The jobs of one source: what each does, how far the current one got, how long the rest will
 * take — and the buttons that start, resume or stop them.
 */
export function JobDialog({ source, job, onOpenChange, onNeedSettings }: Props) {
  const start = useStartJob();
  const cancel = useCancelJob();
  const active = isActive(job);
  const whole = source?.modes.includes("SCRAPE_ALL");
  const updates = source?.modes.includes("UPDATES");
  const left = job && active ? timeLeft(job.phaseStartedAt, job.processed, job.total) : undefined;
  const catalogue = whole ? job?.catalogue : undefined;
  const resumeWhole = catalogueUnfinished(job) && !active;
  // A loaded catalogue leaves the updates to do: they are the main button then
  const updatesFirst = updates && (!whole || catalogue?.complete === true);
  const wholeLabel = resumeWhole
    ? "Продолжить загрузку каталога"
    : catalogue?.complete
      ? "Пройти каталог заново"
      : catalogue?.stage === "none"
        ? "Начать загрузку каталога"
        : "Загрузить весь каталог";

  return (
    <Dialog open={source !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        {source && (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <DatabaseIcon className="size-5" /> {source.title}: загрузка
              </DialogTitle>
              <DialogDescription>
                Работы приходят с сайта{" "}
                <a href={source.homepage} target="_blank" rel="noreferrer" className="underline underline-offset-2">
                  {source.title}
                </a>
                . Модель учится на тех, что вы оценили, поэтому стоит загрузить и те, что вы знаете давно.
              </DialogDescription>
            </DialogHeader>

            <ul className="flex list-disc flex-col gap-1 pl-5 text-sm">
              {updates && (
                <li>
                  <span className="font-medium">Обновления</span> — то, что появилось или обновилось с прошлого раза. Быстро.
                </li>
              )}
              {whole && (
                <li>
                  <span className="font-medium">Весь каталог</span> — все работы сайта, от новых к старым. Долго; загрузку можно
                  остановить и продолжить позже, даже после перезапуска приложения. По ходу модель переобучается, так что
                  прогнозы улучшаются.
                </li>
              )}
              <li>Картинки и тексты разбираются отдельно и непрерывно — см. счётчики в шапке.</li>
            </ul>

            {job && (
              <div className="grid grid-cols-3 gap-2 text-sm">
                <Stat label="Работ в базе" value={compact(job.items)} />
                <Stat label="Новых за загрузку" value={compact(job.newItems)} />
                <Stat label="Ошибок" value={compact(job.errors)} />
              </div>
            )}

            {catalogue && (
              <div className="flex flex-col gap-1.5 text-sm">
                <span className={catalogue.complete ? "text-yes" : catalogue.stage === "none" ? "text-muted-foreground" : undefined}>
                  {catalogueLabel(catalogue)}
                </span>
                {!catalogue.complete && catalogue.total > 0 && <Progress value={(catalogue.done / catalogue.total) * 100} />}
              </div>
            )}

            {job && active && (
              <div className="flex flex-col gap-1.5">
                <div className="flex justify-between gap-2 text-sm">
                  <span>
                    {job.mode && <span className="text-muted-foreground">{MODE_LABEL[job.mode]} · </span>}
                    {job.state === "CANCELLING" ? "Останавливаю…" : phaseLabel(job.phase)}
                  </span>
                  {job.total > 0 && (
                    <span className="tabular-nums text-muted-foreground">
                      {job.processed} / {job.total}
                      {left && ` · осталось ~${left}`}
                    </span>
                  )}
                </div>
                <Progress value={job.total > 0 ? (job.processed / job.total) * 100 : null} />
              </div>
            )}

            {job?.message && <p className="text-sm text-muted-foreground">{job.message}</p>}

            {job && !active && job.state === "DONE" && job.finishedAt && (
              <div className="flex items-center gap-2 text-sm text-yes">
                <CheckCircle2Icon className="size-4" />
                {job.mode ? MODE_LABEL[job.mode] : "Загрузка"}: готово {formatDate(job.finishedAt)}, новых {job.newItems}.
              </div>
            )}
            {job && !active && job.state === "FAILED" && (
              <div className="flex items-center gap-2 text-sm text-destructive">
                <TriangleAlertIcon className="size-4" /> Загрузка не удалась {job.finishedAt && formatDate(job.finishedAt)}
              </div>
            )}

            {job?.loggedIn === false && (
              <div className="flex gap-2 rounded-lg border border-maybe/50 bg-maybe/10 p-3 text-sm">
                <TriangleAlertIcon className="mt-0.5 size-4 shrink-0 text-maybe" />
                <div>
                  Сайт не узнал вас: без входа он отдаёт не всё. Задайте вход в{" "}
                  <button className="underline underline-offset-2" onClick={onNeedSettings}>
                    настройках
                  </button>
                  .
                </div>
              </div>
            )}

            <DialogFooter>
              {active ? (
                <Button variant="outline" onClick={() => cancel.mutate(source.id)} disabled={job?.state === "CANCELLING" || cancel.isPending}>
                  <SquareIcon /> Остановить
                </Button>
              ) : (
                <>
                  <Button variant="outline" onClick={() => onOpenChange(false)}>
                    Закрыть
                  </Button>
                  {updates && (
                    <Button
                      variant={updatesFirst ? "default" : "outline"}
                      onClick={() => start.mutate({ source: source.id, mode: "UPDATES" }, { onSuccess: () => onOpenChange(false) })}
                      disabled={start.isPending}
                    >
                      <DownloadIcon /> Загрузить обновления
                    </Button>
                  )}
                  {whole && (
                    <Button
                      variant={updatesFirst ? "outline" : "default"}
                      onClick={() => start.mutate({ source: source.id, mode: "SCRAPE_ALL" }, { onSuccess: () => onOpenChange(false) })}
                      disabled={start.isPending}
                    >
                      <DatabaseIcon /> {wholeLabel}
                    </Button>
                  )}
                </>
              )}
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border bg-card p-2.5">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-0.5 font-semibold tabular-nums">{value}</div>
    </div>
  );
}
