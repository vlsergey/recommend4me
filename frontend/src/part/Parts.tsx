import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { BookOpenTextIcon, ChevronDownIcon, Loader2Icon } from "lucide-react";
import { api, unwrap, type ItemRef, type PartInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { formatDate } from "@/i18n";
import { cn } from "@/lib/utils";

const FIRST_SHOWN = 20;

/** The parts of the work — chapters — in order; one whose text is kept opens it in place. */
export function Parts({ item, label, parts }: { item: ItemRef; label: string; parts: PartInfo[] }) {
  const [all, setAll] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  if (parts.length === 0) return null;
  const sorted = [...parts].sort((a, b) => a.position - b.position);
  const shown = all ? sorted : sorted.slice(0, FIRST_SHOWN);
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">
        {label} <span className="font-normal text-muted-foreground">{parts.length}</span>
      </h4>
      <ol className="flex flex-col divide-y rounded-lg border">
        {shown.map((p) => (
          <li key={p.partId} className="text-sm">
            <button
              className={cn("flex w-full items-center gap-2 px-3 py-1.5 text-left", p.hasText ? "hover:bg-muted/50" : "cursor-default")}
              onClick={() => p.hasText && setOpen(open === p.partId ? null : p.partId)}
              disabled={!p.hasText}
              title={p.hasText ? "Показать текст" : "Текст не сохранён"}
            >
              <span className="w-8 shrink-0 text-right text-xs tabular-nums text-muted-foreground">{p.position}</span>
              <span className={cn("min-w-0 flex-1 truncate", !p.hasText && "text-muted-foreground")}>{p.title ?? `#${p.position}`}</span>
              {p.publishedAt && <span className="shrink-0 text-xs text-muted-foreground">{formatDate(p.publishedAt)}</span>}
              {p.hasText && <ChevronDownIcon className={cn("size-4 shrink-0 text-muted-foreground", open !== p.partId && "-rotate-90")} />}
            </button>
            {open === p.partId && <PartText item={item} partId={p.partId} />}
          </li>
        ))}
      </ol>
      {sorted.length > shown.length && (
        <Button variant="ghost" size="sm" className="mt-1" onClick={() => setAll(true)}>
          Ещё {sorted.length - shown.length}
        </Button>
      )}
    </section>
  );
}

function PartText({ item, partId }: { item: ItemRef; partId: string }) {
  const part = useQuery({
    queryKey: ["part", item.source, item.item, partId],
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/parts/{partId}", { params: { path: { source: item.source, item: item.item, partId } } })),
  });
  if (part.isLoading)
    return (
      <div className="flex justify-center p-4 text-muted-foreground">
        <Loader2Icon className="size-5 animate-spin" />
      </div>
    );
  if (!part.data) return <div className="px-3 pb-3 text-sm text-destructive">Текст не загрузился</div>;
  return (
    <div className="mx-3 mb-3 max-h-[60vh] overflow-y-auto rounded-md bg-muted/40 p-3">
      <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
        <BookOpenTextIcon className="size-3.5" /> {part.data.title ?? `#${part.data.position}`}
      </div>
      <p className="text-sm leading-relaxed whitespace-pre-line text-foreground/90">{part.data.content}</p>
    </div>
  );
}
