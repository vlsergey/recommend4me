import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { DownloadIcon, ExternalLinkIcon, Loader2Icon, RadarIcon } from "lucide-react";
import { api, unwrap, type ItemRef } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { findSource, useTypes } from "@/contenttype/types";
import { ago, formatDate } from "@/i18n";
import { ItemRefDialog } from "@/item/ItemRefDialog";

const EXTENSION = "/extension/recommend4me-firefox.zip";
const DEFAULT_ADDRESS = "http://127.0.0.1:8095";

/**
 * Browser tracking: how to install the Firefox extension that sends the pages of the tracked
 * sites the user opens, and the pages it sent last.
 */
export function CaptureButton() {
  const [open, setOpen] = useState(false);
  const [item, setItem] = useState<ItemRef | null>(null);
  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogTrigger render={<Button variant="ghost" size="icon" aria-label="Слежение в браузере" title="Слежение в браузере" />}>
          <RadarIcon />
        </DialogTrigger>
        <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
          {open && (
            <CaptureDetails
              onOpenItem={(ref) => {
                setOpen(false);
                setItem(ref);
              }}
            />
          )}
        </DialogContent>
      </Dialog>
      <ItemRefDialog item={item} onClose={() => setItem(null)} />
    </>
  );
}

function CaptureDetails({ onOpenItem }: { onOpenItem: (ref: ItemRef) => void }) {
  const types = useTypes();
  const patterns = useQuery({
    queryKey: ["capture", "patterns"],
    queryFn: async () => unwrap(await api.GET("/api/capture/patterns")),
  });
  const recent = useQuery({
    queryKey: ["capture", "recent"],
    queryFn: async () => unwrap(await api.GET("/api/capture/recent")),
    // While the dialog is open the pages come in as the user browses
    refetchInterval: 3000,
  });
  const titleOf = (source: string) => findSource(types.data, source)?.source.title ?? source;
  const homepageOf = (source: string) => findSource(types.data, source)?.source.homepage;
  const tracked = (patterns.data ?? []).filter((p) => p.patterns.length > 0);

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <RadarIcon className="size-5" /> Слежение в браузере
        </DialogTitle>
        <DialogDescription>
          Расширение для Firefox присылает сюда страницы сайтов, которые вы открываете, — так приложение узнаёт о работах,
          ничего не запрашивая у сайта само, и видит их такими, какими их видите вы (с вашим входом).
        </DialogDescription>
      </DialogHeader>

      <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm">
        <li>
          Скачайте расширение:{" "}
          <a href={EXTENSION} download className="inline-flex items-center gap-1 font-medium underline underline-offset-2">
            <DownloadIcon className="size-3.5" /> recommend4me-firefox.zip
          </a>
          .
        </li>
        <li>
          Откройте в Firefox адрес <Code>about:debugging#/runtime/this-firefox</Code> — его нужно вставить в адресную строку
          вручную, по ссылке Firefox его не откроет.
        </li>
        <li>
          Нажмите «Загрузить временное дополнение…» и выберите скачанный zip — или <Code>manifest.json</Code> из
          распакованной папки.
        </li>
        <li>
          Готово: расширение работает, пока Firefox не перезапустится; после перезапуска его нужно загрузить снова тем же
          способом. Насовсем его можно установить в Firefox Developer Edition, Nightly или ESR: там в <Code>about:config</Code>{" "}
          задайте <Code>xpinstall.signatures.required</Code> = <Code>false</Code> и установите zip через «Установить
          дополнение из файла…» на странице <Code>about:addons</Code>.
        </li>
      </ol>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">Что присылает расширение</h4>
        <p className="text-muted-foreground">
          Только страницы этих сайтов — и только по адресу, на который оно настроено: по умолчанию <Code>{DEFAULT_ADDRESS}</Code>
          , это приложение на этом компьютере. Адрес меняется в настройках расширения (about:addons → recommend4me →
          Настройки). Страницы остальных сайтов никуда не уходят.
        </p>
        {patterns.isLoading ? (
          <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
        ) : tracked.length === 0 ? (
          <p className="text-muted-foreground">Ни один источник сейчас не следит за страницами в браузере.</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {tracked.map((p) => (
              <li key={p.source} className="flex flex-wrap items-baseline gap-x-2">
                {homepageOf(p.source) ? (
                  <a href={homepageOf(p.source)} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline">
                    {titleOf(p.source)}
                  </a>
                ) : (
                  <span className="font-medium">{titleOf(p.source)}</span>
                )}
                <span className="truncate font-mono text-xs text-muted-foreground" title={p.patterns.join("\n")}>
                  {p.patterns.length === 1 ? p.patterns[0] : `${p.patterns.length} шаблонов адресов`}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="flex items-center gap-2 font-semibold">
          Последние присланные страницы
          {recent.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        </h4>
        {recent.data && recent.data.length === 0 && (
          <p className="text-muted-foreground">Пока ни одной. Откройте страницу работы на одном из сайтов выше — она появится здесь через пару секунд.</p>
        )}
        {recent.isError && <p className="text-destructive">Не удалось загрузить список</p>}
        <ul className="flex flex-col divide-y rounded-lg border">
          {(recent.data ?? []).map((p) => (
            <li key={`${p.url}-${p.capturedAt}`} className="flex items-center gap-2 px-3 py-1.5">
              <span className="w-24 shrink-0 text-xs text-muted-foreground" title={formatDate(p.capturedAt)}>
                {ago(p.capturedAt)}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">{titleOf(p.source)}</span>
              {p.item ? (
                <button
                  className="min-w-0 flex-1 truncate text-left underline-offset-2 hover:underline"
                  onClick={() => onOpenItem({ source: p.source, item: p.item! })}
                  title="Открыть работу"
                >
                  {p.title ?? p.item}
                </button>
              ) : (
                <span className="min-w-0 flex-1 truncate text-muted-foreground" title={p.url}>
                  {p.url}
                </span>
              )}
              <a href={p.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title="Страница на сайте">
                <ExternalLinkIcon className="size-3.5" />
              </a>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

function Code({ children }: { children: ReactNode }) {
  return <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs break-words">{children}</code>;
}
