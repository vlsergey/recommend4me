import { Loader2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * THE SCREEN OF A WORK WHILE IT IS READ, opened at once at the press that asked for it: a press that
 * shows nothing for a second reads as one not taken. Its parts stand where the work's will — the head
 * with the title and the grade, the work on the left, the answers on the right — as outlines, the
 * head saying the work is being opened.
 */
export function WorkOpening() {
  const { t } = useTranslation("item");
  return (
    <div className="flex min-h-0 flex-1 flex-col" aria-busy>
      <header className="flex flex-col gap-2 border-b p-3 pr-12">
        <DialogTitle className="flex items-center gap-2 text-sm font-normal text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" /> {t("WorkOpening.opening")}
        </DialogTitle>
        <Skeleton className="h-6 w-2/3 max-w-xl" />
        <Skeleton className="h-8 w-full max-w-xl" />
      </header>
      <div className="grid min-h-0 flex-1 min-[56rem]:grid-cols-2 min-[56rem]:divide-x">
        <div className="grid content-start gap-3 p-4">
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
        <div className="hidden content-start gap-3 p-4 min-[56rem]:grid">
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      </div>
    </div>
  );
}
