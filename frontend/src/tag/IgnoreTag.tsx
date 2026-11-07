import { Trash2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type TagInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { cn } from "@/lib/utils";
import { useTagsChanged } from "./tagQueries";

/**
 * The trash can of a tag: pressed, the tag is ignored — it stays with its works, shown and found,
 * but the model of the grades reads it no more; pressed again, read again. Shown as pressed once the
 * application has kept it.
 */
export function IgnoreButton({ tag, disabled }: { tag: TagInfo; /** Its list moving: shown disabled, the reason no press is taken. */ disabled?: boolean }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged();
  return (
    <AsyncButton
      variant={tag.ignored ? "secondary" : "ghost"}
      size="icon-xs"
      className={cn("shrink-0", !tag.ignored && "text-muted-foreground")}
      disabled={disabled}
      aria-pressed={tag.ignored}
      title={t(tag.ignored ? "Ignore.ignoredTitle" : "Ignore.title")}
      aria-label={t(tag.ignored ? "Ignore.ignoredTitle" : "Ignore.title")}
      onClick={async () => {
        unwrap(await api.PUT("/api/tags/ignored", { body: { tag: tag.key, ignored: !tag.ignored } }));
        await changed();
      }}
      icon={<Trash2Icon className={cn(tag.ignored && "text-destructive")} />}
    />
  );
}

/** Whether a tag of the list must be one the user ignores, must not, or either. */
const IGNORED_FILTERS = ["any", "ignored", "read"] as const;
export type IgnoredFilter = (typeof IGNORED_FILTERS)[number];
export const ignoredParam = (f: IgnoredFilter) => (f === "any" ? undefined : f === "ignored");

/** The filter of the tags the user ignores, a select as the filters of the sources. */
export function IgnoredSelect({ value, onChange }: { value: IgnoredFilter; onChange: (f: IgnoredFilter) => void }) {
  const { t } = useTranslation("tag");
  return (
    <label className="flex items-center gap-1.5 text-xs text-muted-foreground" title={t("Ignore.filterTitle")}>
      <Trash2Icon className="size-3.5" />
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as IgnoredFilter)}
        className={cn(
          "h-7 rounded-md border border-input bg-transparent px-1.5 text-xs text-foreground outline-none focus-visible:border-ring dark:bg-input/30",
          value !== "any" && "border-primary",
        )}
      >
        {IGNORED_FILTERS.map((f) => (
          <option key={f} value={f}>
            {t(`Ignore.filter.${f}`)}
          </option>
        ))}
      </select>
    </label>
  );
}
