import { useEffect, useRef } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { DatabaseIcon, DownloadIcon, Loader2Icon, SquareIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, JobStatus, SourceInfo } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
} from "@/components/ui/dropdown-menu";
import { Progress } from "@/components/ui/progress";
import { toast } from "@/components/ui/toast";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatDate, timeLeft } from "@/i18n/format";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { catalogueLabel, jobMessage, modeLabel, phaseLabel } from "./labels";
import { ITEMS } from "@/item/lists";
import { JobDialog } from "./JobDialog";
import { catalogueUnfinished, isActive, useCancelJob, useJobs, useStartJob } from "./useJobs";

type Props = {
  type: ContentTypeInfo;
  /** The source whose window of loads is open: opened from the menu too ([UpdateSubmenu]). */
  dialog: string | null;
  onDialog: (source: string | null) => void;
  onNeedSettings: () => void;
};

/**
 * The sources of the type that scrape by themselves: the others (the book sites) are filled by
 * the browser extension only, and have nothing to start or stop here.
 */
function scraping(type: ContentTypeInfo): SourceInfo[] {
  return type.sources.filter((s) => s.modes.includes("SCRAPE_ALL") || s.modes.includes("UPDATES"));
}

/**
 * The loads of the sources of the current content type as they go, started from the menu
 * ([UpdateSubmenu]): their progress and what is left of them; finished jobs refresh the list, the
 * filters and the model.
 */
export function JobControl({ type, dialog, onDialog: setDialog, onNeedSettings }: Props) {
  const { t } = useTranslation("job");
  const queryClient = useQueryClient();
  const jobs = useJobs();
  const cancel = useCancelJob();

  const sources = scraping(type);
  const jobOf = (source: string) => jobs.data?.find((j) => j.source === source);
  const ours = (jobs.data ?? []).filter((j) => sources.some((s) => s.id === j.source));

  // A job that has just ended: the list, the filters and the model are asked for anew, and a toast says how it went
  const states = useRef(new Map<string, JobStatus["state"]>());
  useEffect(() => {
    for (const job of jobs.data ?? []) {
      const was = states.current.get(job.source);
      states.current.set(job.source, job.state);
      if (!was || !isActive({ ...job, state: was }) || isActive(job)) continue;
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      queryClient.invalidateQueries({ queryKey: ["facets"] });
      queryClient.invalidateQueries({ queryKey: ["model"] });
      const name = sourceTitle(job.source);
      if (job.state === "DONE")
        toast.add({
          title: t(job.mode === "SCRAPE_ALL" ? "JobControl.catalogueLoaded" : "JobControl.updatesLoaded", { source: name }),
          description: job.errors
            ? t("JobControl.resultErrors", { newItems: job.newItems, errors: job.errors })
            : t("JobControl.result", { newItems: job.newItems }),
          type: "success",
        });
      if (job.state === "FAILED") toast.add({ title: t("JobControl.failed", { source: name }), description: jobMessage(job), type: "error" });
    }
  });

  if (sources.length === 0) return null;

  const opened = sources.find((s) => s.id === dialog) ?? null;
  const dialogElement = (
    <JobDialog
      source={opened}
      job={opened ? jobOf(opened.id) : undefined}
      onOpenChange={(open) => !open && setDialog(null)}
      onNeedSettings={() => {
        setDialog(null);
        onNeedSettings();
      }}
    />
  );

  const running = ours.filter(isActive);
  if (running.length > 0) {
    const s = running[0];
    const left = timeLeft(s.phaseStartedAt, s.processed, s.total);
    return (
      <div className="flex min-w-0 items-center gap-1 sm:gap-3">
        <Loader2Icon className="hidden size-4 shrink-0 animate-spin text-muted-foreground sm:block" />
        <button
          className="flex min-w-0 flex-1 flex-col gap-1 text-left sm:w-64 sm:flex-none"
          onClick={() => setDialog(s.source)}
          title={s.catalogue && s.mode === "SCRAPE_ALL" ? catalogueLabel(s.catalogue) : t("JobControl.details")}
        >
          <div className="flex justify-between gap-2 text-xs">
            <span className="truncate">
              <span className="hidden text-muted-foreground sm:inline">
                {sources.length > 1 && `${sourceShort(s.source)} · `}
                {s.mode === "SCRAPE_ALL" && `${t("JobControl.catalogue")} · `}
              </span>
              {s.state === "CANCELLING" ? t("JobControl.stopping") : phaseLabel(s.phase) || t("JobControl.loading")}
            </span>
            {s.total > 0 && (
              <span className="shrink-0 tabular-nums text-muted-foreground">
                {s.processed} / {s.total}
                {left && <span className="hidden sm:inline"> · ~{left}</span>}
              </span>
            )}
          </div>
          <Progress value={s.total > 0 ? (s.processed / s.total) * 100 : null} />
        </button>
        {running.length > 1 && (
          <Badge variant="outline" title={running.slice(1).map((j) => sourceTitle(j.source)).join(", ")}>
            +{running.length - 1}
          </Badge>
        )}
        <Button variant="ghost" size="icon-sm" onClick={() => cancel.mutate(s.source)} disabled={s.state === "CANCELLING"} title={t("JobControl.stop")}>
          <SquareIcon />
        </Button>
        {dialogElement}
      </div>
    );
  }

  const last = ours
    .filter((j) => j.state === "DONE" && j.finishedAt)
    .sort((a, b) => (b.finishedAt ?? "").localeCompare(a.finishedAt ?? ""))[0];
  const notLoggedIn = ours.filter((j) => j.loggedIn === false);
  const resumable = ours.filter((j) => sources.some((s) => s.id === j.source && s.modes.includes("SCRAPE_ALL")) && catalogueUnfinished(j));

  return (
    <div className="flex items-center gap-2">
      {last && (
        <span className="hidden text-xs text-muted-foreground xl:inline" title={formatDate(last.finishedAt)}>
          {t("JobControl.newItems", { count: last.newItems })}
        </span>
      )}
      {notLoggedIn.length > 0 && (
        <Tooltip>
          <TooltipTrigger render={<Badge variant="outline" className="hidden cursor-pointer border-maybe/60 text-maybe sm:inline-flex" onClick={onNeedSettings} />}>
            <TriangleAlertIcon /> <span className="hidden xl:inline">{t("JobControl.notLoggedIn")}</span>
          </TooltipTrigger>
          <TooltipContent>{t("JobControl.notLoggedInHint", { sources: notLoggedIn.map((j) => sourceTitle(j.source)).join(", ") })}</TooltipContent>
        </Tooltip>
      )}
      {resumable.map((j) => (
        <Button
          key={j.source}
          variant="outline"
          onClick={() => setDialog(j.source)}
          title={
            j.catalogue
              ? t("JobControl.catalogueUnfinishedAt", { progress: catalogueLabel(j.catalogue) })
              : t("JobControl.catalogueUnfinished")
          }
          className="hidden sm:inline-flex"
        >
          <DatabaseIcon />
          <span className="hidden 2xl:inline">
            {sources.length > 1 ? t("JobControl.resumeSource", { source: sourceTitle(j.source) }) : t("JobControl.resumeCatalogue")}
          </span>
        </Button>
      ))}
      {dialogElement}
    </div>
  );
}

/**
 * The updates in the menu: of every source of the type at once, and of each, with the load of its
 * catalogue. Not to be opened while a load of the type goes: its progress in the header says so.
 */
export function UpdateSubmenu({ type, onDialog }: { type: ContentTypeInfo; onDialog: (source: string) => void }) {
  const { t } = useTranslation("job");
  const jobs = useJobs();
  const start = useStartJob();
  const sources = scraping(type);
  if (sources.length === 0) return null;
  const jobOf = (source: string) => jobs.data?.find((j) => j.source === source);
  const running = sources.some((s) => {
    const job = jobOf(s.id);
    return job && isActive(job);
  });
  const withUpdates = sources.filter((s) => s.modes.includes("UPDATES"));
  const updateAll = () => withUpdates.forEach((s) => start.mutate({ source: s.id, mode: "UPDATES" }));
  return (
    <DropdownMenuSub>
      {/* Opened by a press alone: on a touch screen there is no hover, and a hover opening it first makes the press close it */}
      <DropdownMenuSubTrigger openOnHover={false} disabled={running}>
        <DownloadIcon /> {t("JobControl.update")}
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent className="w-auto min-w-64">
        <DropdownMenuItem
          onClick={() => (withUpdates.length > 0 ? updateAll() : onDialog(sources[0].id))}
          title={withUpdates.length > 0 ? t("JobControl.updatesOf", { sources: withUpdates.map((s) => sourceTitle(s.id)).join(", ") }) : undefined}
        >
          {withUpdates.length > 0 ? <DownloadIcon /> : <DatabaseIcon />}
          {withUpdates.length > 0 ? t("JobControl.loadUpdates") : t("JobControl.loadCatalogue")}
        </DropdownMenuItem>
        {sources.map((s) => {
          const catalogue = s.modes.includes("SCRAPE_ALL") ? jobOf(s.id)?.catalogue : undefined;
          return (
            <DropdownMenuGroup key={s.id}>
              <DropdownMenuSeparator />
              <DropdownMenuLabel>
                {sourceTitle(s.id)}
                {catalogue && <div className="font-normal text-muted-foreground">{catalogueLabel(catalogue)}</div>}
              </DropdownMenuLabel>
              {s.modes.includes("UPDATES") && (
                <DropdownMenuItem onClick={() => start.mutate({ source: s.id, mode: "UPDATES" })}>
                  <DownloadIcon /> {modeLabel("UPDATES")}
                </DropdownMenuItem>
              )}
              <DropdownMenuItem onClick={() => onDialog(s.id)}>
                <DatabaseIcon /> {s.modes.includes("SCRAPE_ALL") ? `${modeLabel("SCRAPE_ALL")}…` : t("JobControl.more")}
              </DropdownMenuItem>
            </DropdownMenuGroup>
          );
        })}
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}
