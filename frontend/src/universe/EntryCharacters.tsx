import { type ReactNode, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckIcon, ExternalLinkIcon, Loader2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { CharacterInfo, ContentTypeInfo, UniverseClassInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { LocalNameEdit } from "./LocalName";
import { charactersQuery, classesQuery, type EntryRef, keptByClasses, useDictionaryChanged, useInclusion } from "./universeQueries";

/** Which entries the list shows. */
type EntriesShown = "kept" | "left" | "all";

/**
 * What an entry of the catalogue holds, as the dictionary keeps it: the classes of its entries —
 * those of characters kept, the rest (places, groups, spells, things) left out unless the user
 * keeps them — and every entry, each kept or left out by its classes or by the user's word on it alone.
 */
export function EntryCharacters({ type, entry }: { type: ContentTypeInfo; entry: EntryRef }) {
  const { t } = useTranslation("universe");
  const [filter, setFilter] = useState("");
  const [shownKind, setShownKind] = useState<EntriesShown>("kept");
  const characters = useQuery(charactersQuery(type.id, entry));
  const classes = useQuery(classesQuery(type.id, entry));
  const inclusion = useInclusion(type.id, entry);
  const changed = useDictionaryChanged(type.id);

  if (characters.isLoading || classes.isLoading) return <Loader2Icon className="size-4 animate-spin text-muted-foreground" />;
  if (characters.isError) return <p className="text-destructive">{t("Characters.loadFailed", { error: characters.error.message })}</p>;
  if (classes.isError) return <p className="text-destructive">{t("Characters.loadClassesFailed", { error: classes.error.message })}</p>;
  const all = characters.data ?? [];
  if (all.length === 0) return <p className="text-muted-foreground">{t("Characters.empty")}</p>;

  const byId = new Map((classes.data ?? []).map((c) => [c.class, c]));
  const byClasses = (c: CharacterInfo) => keptByClasses(c, byId);
  const needle = filter.trim().toLocaleLowerCase("ru-RU");
  const shown = all
    .filter((c) => shownKind === "all" || c.included === (shownKind === "kept"))
    .filter((c) => needle === "" || c.names.some((n) => n.toLocaleLowerCase("ru-RU").includes(needle)));
  const kept = all.filter((c) => c.included).length;

  return (
    <div className="flex flex-col gap-3">
      <Classes classes={classes.data ?? []} onToggle={inclusion.setClass} />
      <div className="flex flex-wrap items-center gap-2">
        <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("Characters.filterPlaceholder")} className="max-w-xs" type="search" />
        <fieldset className="flex gap-1" aria-label={t("Characters.shown")}>
          {(
            [
              ["kept", t("Characters.kept", { n: kept })],
              ["left", t("Characters.left", { n: all.length - kept })],
              ["all", t("Characters.all", { n: all.length })],
            ] as const
          ).map(([kind, label]) => (
            <Button key={kind} size="sm" variant={shownKind === kind ? "secondary" : "ghost"} aria-pressed={shownKind === kind} onClick={() => setShownKind(kind)}>
              {label}
            </Button>
          ))}
        </fieldset>
        {needle !== "" && <span className="text-xs text-muted-foreground tabular-nums">{t("Characters.found", { n: shown.length })}</span>}
      </div>
      <ul className="flex max-h-96 flex-col divide-y overflow-y-auto rounded-lg border">
        {shown.map((c) => (
          <CharacterRow
            key={c.character}
            character={c}
            classNames={c.classes.map((id) => byId.get(id)?.name ?? id)}
            onToggle={() => inclusion.setEntry(c, byClasses(c))}
            naming={
              <LocalNameEdit
                catalogue={entry.catalogue}
                entry={c.character}
                localName={c.localName}
                catalogueName={(c.localName ? c.names[1] : c.names[0]) ?? c.character}
                onSaved={() => changed(false)}
              />
            }
          />
        ))}
      </ul>
    </div>
  );
}

/**
 * The classes of an entry's entries as chips, those of characters first: a kept class pressed,
 * one the user set apart from what the catalogue says marked. Pressing a chip keeps or leaves out
 * every entry of the class.
 */
function Classes({ classes, onToggle }: { classes: UniverseClassInfo[]; onToggle: (cls: UniverseClassInfo) => Promise<unknown> }) {
  const { t } = useTranslation("universe");
  if (classes.length === 0) return null;
  const group = (character: boolean, label: string, hint: string) => {
    const list = classes.filter((c) => c.character === character);
    if (list.length === 0) return null;
    return (
      <div className="flex flex-col gap-1">
        <div className="text-xs text-muted-foreground" title={hint}>
          {label}
        </div>
        <div className="flex flex-wrap gap-1">
          {list.map((c) => (
            <AsyncButton
              key={c.class}
              size="xs"
              variant={c.included ? "secondary" : "outline"}
              aria-pressed={c.included}
              onClick={() => onToggle(c)}
              title={t(
                c.included
                  ? c.choice != null ? "Classes.keptByChoiceHint" : "Classes.keptHint"
                  : c.choice != null ? "Classes.leftByChoiceHint" : "Classes.leftHint",
                { n: c.count },
              )}
              className={cn("h-auto min-h-6 max-w-full whitespace-normal text-left", !c.included && "text-muted-foreground line-through decoration-muted-foreground/60")}
            >
              {c.choice != null && <span className="text-primary">•</span>}
              {c.name} <span className="tabular-nums">{c.count}</span>
            </AsyncButton>
          ))}
        </div>
      </div>
    );
  };
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-2">
      {group(true, t("Classes.characterClasses"), t("Classes.characterClassesHint"))}
      {group(false, t("Classes.other"), t("Classes.otherHint"))}
    </div>
  );
}

function CharacterRow({
  character: c,
  classNames,
  onToggle,
  naming,
}: {
  character: CharacterInfo;
  classNames: string[];
  onToggle: () => Promise<unknown>;
  /** The user's own name of the character over the catalogue's, to give or take back. */
  naming?: ReactNode;
}) {
  const { t } = useTranslation("universe");
  const [main, ...others] = c.names;
  return (
    <li className={cn("flex items-start gap-2 px-3 py-1.5", !c.included && "text-muted-foreground")}>
      <AsyncButton
        size="icon-xs"
        variant={c.included ? "secondary" : "outline"}
        aria-pressed={c.included}
        onClick={onToggle}
        title={t(
          c.included
            ? c.choice != null ? "CharacterRow.keptByChoiceHint" : "CharacterRow.keptByClassHint"
            : c.choice != null ? "CharacterRow.leftByChoiceHint" : "CharacterRow.leftByClassHint",
        )}
        icon={c.included ? <CheckIcon /> : <XIcon />}
        className="mt-0.5"
      />
      <div className="min-w-0 flex-1">
        <span className={cn("font-medium", !c.included && "line-through decoration-muted-foreground/60")}>{main ?? c.character}</span>
        {naming}
        {others.length > 0 && <span className="text-muted-foreground"> · {others.join(", ")}</span>}
        {c.choice != null && <span className="ml-1 text-xs text-primary">{t("CharacterRow.yourChoice")}</span>}
        {(classNames.length > 0 || c.description) && (
          <div className="text-xs text-muted-foreground">{[classNames.join(", "), c.description].filter(Boolean).join(" — ")}</div>
        )}
      </div>
      <a href={c.url} target="_blank" rel="noreferrer" className="mt-0.5 shrink-0 text-muted-foreground hover:text-foreground" title={t("CharacterRow.wikidataPage")}>
        <ExternalLinkIcon className="size-3.5" />
      </a>
    </li>
  );
}
