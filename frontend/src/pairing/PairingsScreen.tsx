import { useState } from "react";
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo, type Pairing } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { label as labelOfKey } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { MarkingTable } from "@/marking/MarkingTable";
import { followLink, navigate, screenHref } from "@/screen/screens";
import { ORIGINAL_CHARACTERS, Picker, type Option } from "@/universe/FanficSection";
import { charactersQuery, useUniverses } from "@/universe/Universes";

/** The cache key of the pairings known: their counts change with every answer on one. */
const PAIRINGS = "pairings";

/** The facet of the layer the pairings are values of. */
const FACET = "pairings";

/** A character's name: an original character's by its key, in the user's language. */
const characterName = (c: Pairing["characters"][number]) => (c.nameKey ? labelOfKey(c.nameKey) : c.name);

/**
 * THE PAIRINGS: every pairing known — made here, or answered on, or drafted on some work — with its
 * universes and how many works the user said have it, have it not, are not clear of; a new one made
 * of two characters of the dictionary; and, of the one chosen, its works to mark by it, the likeliest
 * first ([MarkingTable]): its yes makes its two characters the work's main ones too.
 */
export function PairingsScreen({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  const { t } = useTranslation("pairing");
  const queryClient = useQueryClient();
  const pairings = useQuery({ queryKey: [PAIRINGS], queryFn: async () => unwrap(await api.GET("/api/pairings", {})) });
  const [filter, setFilter] = useState("");
  const needle = filter.trim().toLocaleLowerCase();
  const shown = (pairings.data ?? []).filter(
    (p) => needle === "" || [p.name, ...p.characters.map(characterName), ...p.universes].some((n) => n.toLocaleLowerCase().includes(needle)),
  );
  const chosen = pairings.data?.find((p) => p.key === selected);
  return (
    <div className="mx-auto grid max-w-[1800px] gap-4 px-4 py-4 md:grid-cols-[minmax(16rem,22rem)_1fr]">
      <aside className="flex min-w-0 flex-col gap-2">
        <h2 className="text-lg font-semibold">{t("PairingsScreen.title")}</h2>
        <NewPairing type={type} onMade={(p) => (queryClient.invalidateQueries({ queryKey: [PAIRINGS] }), navigate({ kind: "pairings", pairing: p.key }))} />
        <Input type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("PairingsScreen.filter")} className="h-8" />
        {pairings.isLoading && <p className="text-sm text-muted-foreground">{t("PairingsScreen.loading")}</p>}
        {pairings.isError && <p className="text-sm text-destructive">{t("PairingsScreen.failed", { error: pairings.error.message })}</p>}
        {pairings.data && shown.length === 0 && <p className="text-sm text-muted-foreground">{t("PairingsScreen.none")}</p>}
        <ul className="flex flex-col divide-y rounded-lg border">
          {shown.map((p) => (
            <li key={p.key}>
              <a
                href={screenHref({ kind: "pairings", pairing: p.key })}
                onClick={(e) => followLink(e, { kind: "pairings", pairing: p.key })}
                className={cn("flex flex-col gap-0.5 px-3 py-2 hover:bg-muted/50", p.key === selected && "bg-muted")}
              >
                <span className="font-medium">{p.name}</span>
                <span className="text-xs text-muted-foreground">{p.universes.join(", ")}</span>
                <span className="text-xs text-muted-foreground tabular-nums">{t("PairingsScreen.counts", { yes: p.yes, no: p.no, unknown: p.unknown })}</span>
              </a>
            </li>
          ))}
        </ul>
      </aside>
      <main className="min-w-0">
        {!selected && <p className="text-sm text-muted-foreground">{t("PairingsScreen.choose")}</p>}
        {selected && (
          <div className="flex flex-col gap-3">
            <div className="flex flex-col gap-0.5">
              <h2 className="text-lg font-semibold">{chosen?.name ?? selected}</h2>
              {chosen && <span className="text-sm text-muted-foreground">{chosen.universes.join(", ")}</span>}
            </div>
            <MarkingTable key={selected} facet={FACET} value={selected} onAnswered={() => queryClient.invalidateQueries({ queryKey: [PAIRINGS] })} />
          </div>
        )}
      </main>
    </div>
  );
}

/**
 * A new pairing: two characters chosen, each of the characters of every universe of the dictionary
 * or an original one, found by any of their names; made, it is chosen.
 */
function NewPairing({ type, onMade }: { type: ContentTypeInfo; onMade: (p: Pairing) => void }) {
  const { t } = useTranslation("pairing");
  const { t: tu } = useTranslation("universe");
  const [open, setOpen] = useState(false);
  const [first, setFirst] = useState<Option | null>(null);
  const [second, setSecond] = useState<Option | null>(null);
  const dictionary = useUniverses(type.id, open);
  const universes = dictionary.data ?? [];
  const characters = useQueries({ queries: open ? universes.map((u) => charactersQuery(type.id, u)) : [] });
  // A character of several universes offered once, with every universe it is of
  const byKey = new Map<string, Option & { universes: string[] }>();
  universes.forEach((u, i) => {
    for (const c of (characters[i]?.data ?? []).filter((c) => c.included)) {
      const known = byKey.get(c.value);
      if (known) known.universes.push(u.name);
      else
        byKey.set(c.value, {
          key: c.value,
          name: c.names[0] ?? c.character,
          description: c.description ?? undefined,
          names: c.names.length > 0 ? c.names : [c.character],
          universes: [u.name],
          taken: false,
        });
    }
  });
  const options: Option[] = [
    ...[...byKey.values()].map((o) => ({ ...o, note: [o.names.slice(1).join(", "), o.universes.join(", ")].filter((x) => x !== "").join(" — ") })),
    ...ORIGINAL_CHARACTERS.map((c) => ({ key: c.key, name: tu(c.label), names: [tu(c.label)], taken: false })),
  ];
  const loading = dictionary.isLoading || characters.some((q) => q.isLoading);
  const close = () => {
    setOpen(false);
    setFirst(null);
    setSecond(null);
  };
  if (!open)
    return (
      <Button variant="outline" size="sm" className="self-start" onClick={() => setOpen(true)}>
        <PlusIcon /> {t("NewPairing.open")}
      </Button>
    );
  /** One of the two: chosen, its name shown with a cross to choose again; else the picker. */
  const slot = (chosen: Option | null, choose: (o: Option | null) => void, label: string) =>
    chosen ? (
      <div className="flex items-center gap-1 text-sm">
        <span className="font-medium">{chosen.name}</span>
        <Button variant="ghost" size="icon-xs" onClick={() => choose(null)} title={t("NewPairing.again")}>
          <XIcon />
        </Button>
      </div>
    ) : (
      <Picker
        label={label}
        placeholder={tu("OpenCharacters.placeholder")}
        options={options.map((o) => ({ ...o, taken: o.key === first?.key || o.key === second?.key }))}
        loading={loading}
        error={dictionary.error?.message ?? characters.find((q) => q.isError)?.error?.message}
        onPick={async (key) => choose(options.find((o) => o.key === key) ?? null)}
        // Open from the start; closed, it is a button to open it again — the new pairing stays
        onClose={() => {}}
      />
    );
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-2">
      <div className="flex items-center justify-between text-sm font-medium">
        {t("NewPairing.title")}
        <Button variant="ghost" size="icon-xs" onClick={close} title={t("NewPairing.close")}>
          <XIcon />
        </Button>
      </div>
      {slot(first, setFirst, t("NewPairing.first"))}
      {slot(second, setSecond, t("NewPairing.second"))}
      <AsyncButton
        size="sm"
        className="self-start"
        disabled={!first || !second}
        onClick={async () => {
          const made = unwrap(await api.POST("/api/pairings", { body: { first: first!.key, second: second!.key } }));
          close();
          onMade(made);
        }}
      >
        {t("NewPairing.make")}
      </AsyncButton>
    </div>
  );
}
