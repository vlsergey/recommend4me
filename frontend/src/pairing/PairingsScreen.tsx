import { useState } from "react";
import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo, type Pairing } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { intlTag } from "@/i18n/i18n";
import { label as labelOfKey } from "@/i18n/label";
import { usePref } from "@/lib/usePref";
import { cn } from "@/lib/utils";
import { MarkingTable } from "@/marking/MarkingTable";
import { followLink, navigate, screenHref } from "@/screen/screens";
import { ORIGINAL_CHARACTERS, Picker, type Option } from "@/universe/FanficSection";
import { charactersQuery, useUniverses } from "@/universe/Universes";

/** The cache key of the pairings known: their counts change with every answer on one. */
const PAIRINGS = "pairings";

/** The facet of the layer the pairings are values of. */
const FACET = "pairings";

/** The order of the pairings: by the model's weight of them over the works as a whole, or by name. */
type Order = "weight" | "name";

/** How many pairings the list shows, and how many more each "more" adds: the models know thousands. */
const SHOWN = 200;

/** The key of the pairing of two characters: one whatever their order. */
const pairingKey = (a: string, b: string) => "pair:" + [a, b].sort().join("|");

/** A character's name: an original character's by its key, in the user's language. */
const characterName = (c: Pairing["characters"][number]) => (c.nameKey ? labelOfKey(c.nameKey) : c.name);

/** A weight as a share of the works: "4.2%", small ones with their first two digits. */
const share = (w: number) => (w * 100).toLocaleString(intlTag(), { maximumSignificantDigits: 2 }) + "%";

/**
 * THE PAIRINGS: every pairing the models know or the user answered on, none kept of its own — with
 * the model's weight of it over the works as a whole, its universes and how many works the user said
 * have it, have it not, are not clear of; a new one chosen of two characters of the dictionary (its
 * key alone: marked on, it is known); and, of the one chosen, its works to mark by it, the likeliest
 * first ([MarkingTable]): its yes makes its two characters the work's main ones too.
 */
export function PairingsScreen({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  const { t } = useTranslation("pairing");
  const queryClient = useQueryClient();
  const pairings = useQuery({ queryKey: [PAIRINGS], queryFn: async () => unwrap(await api.GET("/api/pairings", {})) });
  const [filter, setFilter] = useState("");
  const [order, setOrder] = usePref<Order>("pairings.order", "weight");
  const [limit, setLimit] = useState(SHOWN);
  const needle = filter.trim().toLocaleLowerCase();
  const found = (pairings.data ?? [])
    .filter((p) => needle === "" || [p.name, ...p.characters.map(characterName), ...p.universes].some((n) => n.toLocaleLowerCase().includes(needle)))
    // By the model's weight, the heaviest first — one no model knows last; by name as the server gives them
    .sort(order === "weight" ? (a, b) => (b.weight ?? -1) - (a.weight ?? -1) : () => 0);
  const shown = found.slice(0, limit);
  // The one chosen, known or not: a new one is its key alone
  const one = useQuery({
    queryKey: [PAIRINGS, "one", selected],
    enabled: selected !== undefined,
    queryFn: async () => unwrap(await api.GET("/api/pairings/one", { params: { query: { pairing: selected! } } })),
  });
  const chosen = one.data;
  return (
    <div className="mx-auto grid max-w-[1800px] gap-4 px-4 py-4 md:grid-cols-[minmax(16rem,22rem)_1fr]">
      <aside className="flex min-w-0 flex-col gap-2">
        <h2 className="text-lg font-semibold">{t("PairingsScreen.title")}</h2>
        <NewPairing type={type} onChosen={(key) => navigate({ kind: "pairings", pairing: key })} />
        <Input type="search" value={filter} onChange={(e) => (setFilter(e.target.value), setLimit(SHOWN))} placeholder={t("PairingsScreen.filter")} className="h-8" />
        <Tabs value={order} onValueChange={(v) => setOrder(v as Order)}>
          <TabsList>
            <TabsTrigger value="weight" title={t("PairingsScreen.byWeightHint")}>
              {t("PairingsScreen.byWeight")}
            </TabsTrigger>
            <TabsTrigger value="name">{t("PairingsScreen.byName")}</TabsTrigger>
          </TabsList>
        </Tabs>
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
                <span className="text-xs text-muted-foreground tabular-nums">
                  {p.weight != null && <span title={t("PairingsScreen.byWeightHint")}>{t("PairingsScreen.weight", { share: share(p.weight) })} · </span>}
                  {t("PairingsScreen.counts", { yes: p.yes, no: p.no, unknown: p.unknown })}
                </span>
              </a>
            </li>
          ))}
        </ul>
        {found.length > shown.length && (
          <Button variant="outline" size="sm" className="self-start" onClick={() => setLimit(limit + SHOWN)}>
            {t("PairingsScreen.more", { shown: shown.length, total: found.length })}
          </Button>
        )}
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
 * or an original one, found by any of their names; the pairing of them chosen to mark the works by —
 * nothing kept of it: marked on, it is known.
 */
function NewPairing({ type, onChosen }: { type: ContentTypeInfo; onChosen: (key: string) => void }) {
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
      <Button
        size="sm"
        className="self-start"
        disabled={!first || !second}
        onClick={() => {
          const key = pairingKey(first!.key, second!.key);
          close();
          onChosen(key);
        }}
      >
        {t("NewPairing.make")}
      </Button>
    </div>
  );
}
