import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, ClockIcon, ListOrderedIcon, Loader2Icon, PauseIcon, RotateCcwIcon, XIcon, ZapIcon } from "lucide-react";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type QueuedTask } from "@/api/client";
import { RefreshSelect } from "@/components/RefreshSelect";
import { typeTitle } from "@/contenttype/types";
import { ago, duration, formatTime, number } from "@/i18n/format";
import { label, sourceTitle } from "@/i18n/label";
import { useRefresh } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";

/** How often the queue is asked for again till the user chooses ([RefreshSelect]). */
const EVERY_MS = 2000;

/**
 * How often the queue of the computations is read again, as the user chose in its heading: the
 * background work in the header ([WorkProgress]), which has no heading of its own, reads as often.
 */
export function useTasksRefresh(): [number, (ms: number) => void] {
  return useRefresh("tasks.refresh", EVERY_MS);
}

/**
 * THE QUEUE OF THE COMPUTATIONS, as a timeline: the last works done with how long each took, the one
 * computing now with when it began and when it is expected to end, those waiting their turn and
 * those planned after the quiet that follows the changes that asked for them, with how long each is
 * expected to take — as long as the last run of its kind. Each with why: a grade changed, the user
 * answered on a tag, the pictures came… and how many times each.
 */
export function TaskQueue() {
  const { t } = useTranslation("task");
  const queryClient = useQueryClient();
  const [every, setEvery] = useTasksRefresh();
  const queue = useQuery({
    queryKey: ["tasks"],
    queryFn: async () => unwrap(await api.GET("/api/tasks")),
    refetchInterval: every || false,
  });
  const works = queue.data ?? [];
  // A work ended: the models it may have fitted read anew, not after their own long pause
  const lastEnded = works.filter((w) => w.state === "FINISHED").at(-1)?.ended;
  useEffect(() => {
    if (!lastEnded) return;
    void queryClient.invalidateQueries({ queryKey: ["models"] });
    void queryClient.invalidateQueries({ queryKey: ["model"] });
  }, [lastEnded, queryClient]);
  return (
    <section className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <h2 className="flex items-center gap-2 font-semibold">
        <ListOrderedIcon className="size-4" /> {t("TaskQueue.title")}
        <RefreshSelect every={every} onChange={setEvery} className="ml-auto" />
      </h2>
      <p className="text-xs text-muted-foreground">{t("TaskQueue.hint")}</p>
      {queue.isError && <p className="text-sm text-destructive">{t("TaskQueue.loadError", { error: queue.error.message })}</p>}
      {queue.isSuccess && works.length === 0 && <p className="text-sm text-muted-foreground">{t("TaskQueue.empty")}</p>}
      {works.length > 0 && (
        <ul className="flex flex-col divide-y text-sm">
          {works.map((w, i) => (
            // biome-ignore lint/suspicious/noArrayIndexKey: a work of the queue has no id, and two alike in every field can stand in it: the place tells them apart
            <WorkRow key={`${w.state}:${w.what}:${w.type}:${w.source}:${w.facet}:${w.item}:${w.since}:${i}`} w={w} />
          ))}
        </ul>
      )}
    </section>
  );
}

/** A work: its state, what it does and of what, why, and its times. */
function WorkRow({ w }: { w: QueuedTask }) {
  const { t } = useTranslation("task");
  const of = [
    w.type && typeTitle(w.type),
    w.source && sourceTitle(w.source),
    w.facet && (w.labelKey ? label(w.labelKey) : w.facet),
    w.item && `#${w.item}`,
    w.works && t(`Works.${w.works}`),
    // A task over a scope of works goes over them a portion at a time; one of the works the user waits for, at once
    w.works && t("TaskQueue.inPortions"),
  ].filter(Boolean);
  // Of a task over the works, the work it goes on after and its expected grade; left out going on from the first
  const cursorGrade = w.cursorGrade ?? undefined;
  const from =
    w.cursorWork || cursorGrade !== undefined
      ? t("TaskQueue.from", {
          work: w.cursorWork ? `#${w.cursorWork}` : "",
          grade: cursorGrade === undefined || !Number.isFinite(cursorGrade) ? t("TaskQueue.noGrade") : number(cursorGrade),
        })
      : undefined;
  return (
    <li className={cn("flex flex-wrap items-baseline gap-x-3 gap-y-1 py-1.5", w.state === "FINISHED" && "opacity-70")}>
      <State w={w} />
      {w.priority && !w.works && (
        // Where it waits: the user's first, the background after; of a task over a scope of works its scope tells it
        <span
          className={cn("flex items-center gap-1 text-xs", w.priority === "asked" ? "text-foreground" : "text-muted-foreground")}
          title={t(`Priority.${w.priority}.hint`)}
        >
          {w.priority === "asked" && <ZapIcon className="size-3.5" />} {t(`Priority.${w.priority}`)}
        </span>
      )}
      <span className="font-medium">{t(`What.${w.what}`)}</span>
      {of.length > 0 && <span className="text-muted-foreground">{of.join(" · ")}</span>}
      {from && <span className="text-muted-foreground">{from}</span>}
      {w.reasons.length > 0 && (
        <span className="text-muted-foreground">
          {t("TaskQueue.because")} {w.reasons.map((r) => `${t(`Reason.${r.key}`)}${r.count > 1 ? ` ×${number(r.count)}` : ""}`).join(", ")}
        </span>
      )}
      <span className="ml-auto text-xs text-muted-foreground tabular-nums">
        <Times w={w} />
      </span>
    </li>
  );
}

/**
 * The times of a work. Done: when it ended and how long it took. Running: when it began and when
 * it is expected to end. Waiting: since when, and how long it will take. Planned: when it is due —
 * one past due waits for the work before it — and how long it will take.
 */
function Times({ w }: { w: QueuedTask }) {
  const { t } = useTranslation("task");
  const since = new Date(w.since).getTime();
  // Left out comes as null at run time, whatever the type says
  const expectedMillis = w.expectedMillis ?? undefined;
  const expected = expectedMillis !== undefined ? t("TaskQueue.expected", { span: span(expectedMillis, t) }) : t("TaskQueue.noEstimate");
  switch (w.state) {
    case "FINISHED":
      // A work done says when it ended; without that, no span
      return t("TaskQueue.finished", { time: formatTime(w.ended), span: w.ended ? span(new Date(w.ended).getTime() - since, t) : "" });
    case "RUNNING": {
      // The step a work of several is at, and what it is of: "fitting the field Genre"
      const step = w.step ? [t(`Step.${w.step}`), w.stepOf && label(w.stepOf)].filter(Boolean).join(" ") : undefined;
      const began = [t("TaskQueue.began", { time: formatTime(w.since), span: span(Date.now() - since, t) }), step].filter(Boolean).join(" · ");
      // A work that tells how much of it is done: the share, and its end by the pace so far
      const done = w.progress ?? undefined;
      if (done !== undefined && done > 0) {
        const elapsed = Date.now() - since;
        const left = (elapsed * (1 - done)) / done;
        return `${began} · ${t("TaskQueue.progress", { percent: Math.floor(done * 100) })} · ${t("TaskQueue.endsIn", { span: span(left, t) })}`;
      }
      if (expectedMillis === undefined) return `${began} · ${t("TaskQueue.noEstimate")}`;
      const left = since + expectedMillis - Date.now();
      return `${began} · ${left > 0 ? t("TaskQueue.endsIn", { span: span(left, t) }) : t("TaskQueue.longer", { span: span(expectedMillis, t) })}`;
    }
    case "WAITING":
      return `${t("TaskQueue.waiting", { when: ago(w.since) })} · ${expected}`;
    case "PLANNED": {
      const due = w.due && new Date(w.due).getTime() > Date.now() ? t("TaskQueue.due", { when: ago(w.due) }) : t("TaskQueue.overdue");
      return `${due} · ${expected}`;
    }
  }
}

/** A span to the second — "12 s", "3 min 05 s" — an hour or more as the coarse duration. */
function span(millis: number, t: (key: "Span.seconds" | "Span.minutesSeconds", options: Record<string, unknown>) => string): string {
  const seconds = Math.max(0, millis) / 1000;
  if (seconds < 60) return t("Span.seconds", { value: number(Number(seconds.toPrecision(seconds < 10 ? 2 : 3))) });
  if (seconds < 3600) return t("Span.minutesSeconds", { minutes: Math.floor(seconds / 60), seconds: String(Math.round(seconds % 60)).padStart(2, "0") });
  return duration(seconds);
}

/** The state of a work as a mark: done, failed or given up for newer data, computing now, waiting its turn, planned. */
function State({ w }: { w: QueuedTask }) {
  const { t } = useTranslation("task");
  const look = w.failed
    ? { icon: <XIcon className="size-3.5" />, text: t("TaskQueue.state.FAILED"), hint: t("TaskQueue.state.FAILED.hint"), className: "bg-destructive/15 text-destructive" }
    : w.givenUp
    ? { icon: <RotateCcwIcon className="size-3.5" />, text: t("TaskQueue.state.GIVEN_UP"), hint: t("TaskQueue.state.GIVEN_UP.hint"), className: "bg-muted text-muted-foreground" }
    : {
        FINISHED: { icon: <CheckIcon className="size-3.5" />, text: t("TaskQueue.state.FINISHED"), hint: t("TaskQueue.state.FINISHED.hint"), className: "bg-muted text-muted-foreground" },
        RUNNING: { icon: <Loader2Icon className="size-3.5 animate-spin" />, text: t("TaskQueue.state.RUNNING"), hint: t("TaskQueue.state.RUNNING.hint"), className: "bg-yes/15 text-yes" },
        WAITING: { icon: <PauseIcon className="size-3.5" />, text: t("TaskQueue.state.WAITING"), hint: t("TaskQueue.state.WAITING.hint"), className: "bg-maybe/15 text-maybe" },
        PLANNED: { icon: <ClockIcon className="size-3.5" />, text: t("TaskQueue.state.PLANNED"), hint: t("TaskQueue.state.PLANNED.hint"), className: "bg-muted text-muted-foreground" },
      }[w.state];
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium", look.className)} title={look.hint}>
      {look.icon}
      {look.text}
    </span>
  );
}
