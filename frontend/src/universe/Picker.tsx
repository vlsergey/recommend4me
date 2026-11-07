import { useState } from "react";
import { Loader2Icon, PlusIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { Candidate } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { percent } from "@/i18n/format";
import i18n from "@/i18n/i18n";

/**
 * A value to pick: its key, its name, a mark after it (the model's chance, the mentions), what the
 * catalogue says it is and a note under it, every name it is found by, and whether the work has it already.
 */
export type Option = { key: string; name: string; mark?: string; description?: string; note?: string; names: string[]; taken: boolean };

/** A name as a search compares it: lower case, "ё" as "е". */
const folded = (text: string) => text.toLocaleLowerCase("ru-RU").replace(/ё/g, "е");

/** "mentioned 3 times" — how often the work's texts name a candidate; nothing when they were not counted. */
function mentionsText(c: Candidate): string | undefined {
  if (c.mentions == null) return undefined;
  if (c.mentions === 0) return i18n.t("Mentions.none", { ns: "universe" });
  return i18n.t("Mentions.count", { ns: "universe", count: c.mentions });
}

/** "87% · mentioned 3 times" */
function candidateMark(c: Candidate): string {
  return [c.chance != null ? percent(c.chance) : undefined, mentionsText(c)].filter((x) => x !== undefined).join(" · ");
}

/**
 * The options in the order of the candidates — the likeliest first, then the most named — each
 * with its chance and mentions; those the backend does not offer (the work has them, the user
 * answered on them) after them, in their own order.
 */
export function ranked(options: Option[], candidates: Candidate[] | undefined): Option[] {
  if (!candidates) return options;
  const rank = new Map(candidates.map((c, i) => [c.key, i]));
  const byKey = new Map(candidates.map((c) => [c.key, c]));
  return options
    .map((o, i) => ({ o, i, r: rank.get(o.key) ?? candidates.length + i }))
    .sort((x, y) => x.r - y.r)
    .map(({ o }) => {
      const c = byKey.get(o.key);
      return c ? { ...o, mark: candidateMark(c) } : o;
    });
}

/**
 * A button that opens a list of values to add, every one of them, narrowed by a line that looks in
 * all their names and finds an id of the catalogue exactly; one the work has already is shown, not
 * offered. With [onName], a typed name is added as a value of the user's own. With [onClose], it
 * is opened by another button (a 🔍 after the values) — open from the start, and closing tells it.
 */
export function Picker({
  label,
  placeholder,
  options,
  loading,
  error,
  empty,
  onPick,
  onName,
  onClose,
  word,
}: {
  label: string;
  placeholder: string;
  options: Option[];
  loading?: boolean;
  error?: string;
  /** What to say when there is nothing to offer. */
  empty?: string;
  onPick: (key: string) => Promise<unknown>;
  onName?: (name: string) => Promise<unknown>;
  onClose?: () => void;
  /** A site's word it is opened by: typed, but not the user's typing, so added as a name only once changed. */
  word?: string;
}) {
  const { t } = useTranslation("universe");
  const [open, setOpen] = useState(onClose !== undefined || !!word);
  const [filter, setFilter] = useState(word ?? "");
  const close = () => {
    setOpen(false);
    setFilter("");
    onClose?.();
  };

  if (!open)
    return (
      <Button variant="ghost" size="sm" className="self-start" onClick={() => setOpen(true)}>
        <PlusIcon /> {label}
      </Button>
    );

  const needle = folded(filter.trim());
  // An id of the catalogue — "Q23991129", or its page's address pasted whole — finds its entry exactly
  const id = needle.split(/[/?#]/).filter((s) => s !== "").pop() ?? "";
  // Every word typed, each in any of its names: "мадам хуч" finds the one named "Мадам Трюк" and "Роланда Хуч"; a site's pair by both its names
  const words = needle.split(/[\s/&]+/).filter((w) => w !== "");
  const shown =
    needle === ""
      ? options
      : options.filter((o) => o.key.toLocaleLowerCase("ru-RU").endsWith(`:${id}`) || words.every((w) => o.names.some((n) => folded(n).includes(w))));
  const typed = filter.trim();

  return (
    // biome-ignore lint/a11y/noStaticElementInteractions: not a control — the box only keeps the typing in it from the keys of the work's screen
    <div className="flex w-full flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        {label}
        <Button variant="ghost" size="icon-xs" onClick={close} title={t("Picker.close")}>
          <XIcon />
        </Button>
      </div>
      <div className="flex items-center gap-2">
        <Input
          autoFocus
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && close()}
          placeholder={placeholder}
          className="h-8"
        />
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{needle === "" ? options.length : t("Picker.shownOf", { shown: shown.length, total: options.length })}</span>
      </div>
      {loading && <Loader2Icon className="size-4 animate-spin text-muted-foreground" />}
      {error && <p className="text-xs text-destructive">{t("Picker.loadFailed", { error })}</p>}
      {!loading && options.length === 0 && empty && <p className="text-xs text-muted-foreground">{empty}</p>}
      {shown.length > 0 && (
        <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-lg border">
          {shown.map((o) => (
            <li key={o.key}>
              <AsyncButton
                variant="ghost"
                onClick={async () => {
                  await onPick(o.key);
                  close();
                }}
                disabled={o.taken}
                title={o.taken ? t("Picker.takenHint") : t("Picker.add")}
                className="h-auto w-full flex-col items-start gap-0 rounded-none px-2 py-1 text-left whitespace-normal"
              >
                <span>
                  {o.name}
                  {o.mark && <span className="ml-1 text-xs font-normal text-muted-foreground tabular-nums">{o.mark}</span>}
                  {o.taken &&<span className="ml-1 text-xs font-normal text-muted-foreground">· {t("Picker.taken")}</span>}
                </span>
                {o.description && <span className="text-xs font-normal">{o.description}</span>}
                {o.note && <span className="text-xs font-normal text-muted-foreground">{o.note}</span>}
              </AsyncButton>
            </li>
          ))}
        </ul>
      )}
      {onName && typed !== "" && filter !== word && (
        <AsyncButton
          variant="outline"
          size="sm"
          className="h-auto self-start whitespace-normal"
          onClick={async () => {
            await onName(typed);
            close();
          }}
          title={t("Picker.addNameHint")}
          icon={<PlusIcon />}
        >
          {t("Picker.addName", { name: typed })}
        </AsyncButton>
      )}
    </div>
  );
}
