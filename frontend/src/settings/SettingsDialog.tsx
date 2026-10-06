import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2Icon, TriangleAlertIcon } from "lucide-react";
import { api, unwrap, type Settings, type SettingsUpdate } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type Props = { settings: Settings; open: boolean; onOpenChange: (open: boolean) => void };

export function SettingsDialog({ settings, open, onOpenChange }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        {open && <SettingsForm settings={settings} onDone={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function SettingsForm({ settings, onDone }: { settings: Settings; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [cookie, setCookie] = useState("");
  const [userAgent, setUserAgent] = useState(settings.userAgent);
  const [delay, setDelay] = useState(settings.requestDelayMs);
  const [pages, setPages] = useState(settings.maxPagesPerSync);

  const save = useMutation({
    mutationFn: async (body: SettingsUpdate) => unwrap(await api.PUT("/api/settings", { body })),
    onSuccess: (data) => {
      queryClient.setQueryData(["settings"], data);
      onDone();
    },
  });

  const submit = (clearCookie = false) =>
    save.mutate({
      cookie: clearCookie ? "" : cookie.trim() === "" ? undefined : cookie,
      userAgent,
      requestDelayMs: delay,
      maxPagesPerSync: pages,
    });

  return (
    <>
      <DialogHeader>
        <DialogTitle>Настройки</DialogTitle>
        <DialogDescription>Хранятся в локальной базе приложения.</DialogDescription>
      </DialogHeader>

      <div className="grid gap-2">
        <Label htmlFor="cookie" className="flex items-center gap-2">
          Cookie сессии f95zone
          {settings.cookieSet ? (
            <span className="inline-flex items-center gap-1 text-xs text-yes">
              <CheckCircle2Icon className="size-3.5" /> задан
            </span>
          ) : (
            <span className="inline-flex items-center gap-1 text-xs text-maybe">
              <TriangleAlertIcon className="size-3.5" /> не задан
            </span>
          )}
        </Label>
        <textarea
          id="cookie"
          rows={4}
          value={cookie}
          onChange={(e) => setCookie(e.target.value)}
          placeholder={settings.cookieSet ? "оставьте пустым, чтобы не менять" : "xf_user=…; xf_session=…; …"}
          className="min-h-20 w-full rounded-lg border border-input bg-transparent px-2.5 py-2 font-mono text-xs outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
        />
        <p className="text-xs text-muted-foreground">
          Без входа f95zone прячет спойлеры — жанр, changelog, заметки разработчика. Войдите на сайт в браузере, откройте
          DevTools → Network, выберите любой запрос к f95zone.to и скопируйте значение заголовка <code>Cookie</code>.
        </p>
      </div>

      <div className="grid gap-2">
        <Label htmlFor="ua">User-Agent</Label>
        <Input id="ua" value={userAgent} onChange={(e) => setUserAgent(e.target.value)} />
        <p className="text-xs text-muted-foreground">Лучше тот же, что у браузера, из которого взят cookie.</p>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="grid gap-2">
          <Label htmlFor="delay">Пауза между запросами, мс</Label>
          <Input id="delay" type="number" min={0} value={delay} onChange={(e) => setDelay(Number(e.target.value))} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="pages">Страниц ленты по умолчанию</Label>
          <Input id="pages" type="number" min={1} max={400} value={pages} onChange={(e) => setPages(Number(e.target.value))} />
        </div>
      </div>

      {save.isError && <div className="text-sm text-destructive">Не удалось сохранить: {String(save.error)}</div>}

      <DialogFooter>
        {settings.cookieSet && (
          <Button variant="destructive" onClick={() => submit(true)} disabled={save.isPending} className="sm:mr-auto">
            Удалить cookie
          </Button>
        )}
        <Button variant="outline" onClick={onDone}>
          Отмена
        </Button>
        <Button onClick={() => submit()} disabled={save.isPending}>
          Сохранить
        </Button>
      </DialogFooter>
    </>
  );
}
