import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { BanIcon, Loader2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type TagInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { useTagsChanged } from "./tagQueries";

/**
 * "Do not add its works": pressed, the tag keeps its works out of the library — a work any site tags
 * with it, or with a tag under it, is not kept. Asked to be confirmed first with how many works kept now
 * go, and how many of them graded: they are removed with their grades and answers. Pressed again, its
 * works are kept when a site shows them again.
 */
export function KeepOutButton({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged();
  const [asking, setAsking] = useState(false);
  const say = async (keepsOut: boolean) => {
    unwrap(await api.PUT("/api/tags/kept-out", { body: { tag: tag.key, keepsOut } }));
    await changed();
  };
  const title = t(tag.keepsOut ? "KeepOut.keptTitle" : "KeepOut.title");
  return (
    <>
      <AsyncButton
        variant={tag.keepsOut ? "secondary" : "ghost"}
        size="icon-xs"
        className={cn("shrink-0", !tag.keepsOut && "text-muted-foreground")}
        aria-pressed={tag.keepsOut}
        title={title}
        aria-label={title}
        onClick={async () => {
          if (tag.keepsOut) await say(false);
          else setAsking(true);
        }}
        icon={<BanIcon className={cn(tag.keepsOut && "text-destructive")} />}
      />
      {asking && <KeepOutConfirm tag={tag} onClose={() => setAsking(false)} onConfirm={() => say(true)} />}
    </>
  );
}

/** The question before the tag keeps its works out: how many works kept now go, the graded among them. */
function KeepOutConfirm({ tag, onClose, onConfirm }: { tag: TagInfo; onClose: () => void; onConfirm: () => Promise<void> }) {
  const { t } = useTranslation("tag");
  const count = useQuery({
    queryKey: ["tags", "keptOut", tag.key],
    staleTime: 0,
    queryFn: async () => unwrap(await api.GET("/api/tags/kept-out", { params: { query: { tag: tag.key } } })),
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t("KeepOut.confirmTitle", { name: tag.name })}</DialogTitle>
          <DialogDescription>{t("KeepOut.confirmText")}</DialogDescription>
        </DialogHeader>
        <p className="text-sm">
          {count.data ? (
            t("KeepOut.count", { count: count.data.works, graded: count.data.graded })
          ) : (
            <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
          )}
        </p>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("KeepOut.cancel")}
          </Button>
          <AsyncButton
            variant="destructive"
            icon={<BanIcon />}
            disabled={!count.data}
            onClick={async () => {
              await onConfirm();
              onClose();
            }}
          >
            {t("KeepOut.confirm")}
          </AsyncButton>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
