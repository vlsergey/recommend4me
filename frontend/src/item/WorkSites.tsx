import type { ReactNode } from "react";
import { Undo2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, ItemDetails, ItemRef, WorkSite } from "@/api/client";
import { refKey, sameItem } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { numberField, textField, titleField, type Corrections } from "@/correction/useCorrections";
import { number } from "@/i18n/format";
import { label, sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";

/** The items of a work of several, each a tab: the site's own data of it below. */
export function SiteTabs({ sites, shown, onShow }: { sites: WorkSite[]; shown: ItemRef; onShow: (site: ItemRef) => void }) {
  const { t } = useTranslation("item");
  return (
    <div className="flex shrink-0 items-center gap-2 border-b px-4 py-2">
      <span className="text-xs text-muted-foreground">{t("WorkSites.tabs")}</span>
      <Tabs
        value={refKey(shown)}
        onValueChange={(v) => {
          const site = sites.find((s) => refKey(s) === v);
          if (site) onShow({ source: site.source, item: site.item });
        }}
      >
        <TabsList>
          {sites.map((s) => (
            <TabsTrigger key={refKey(s)} value={refKey(s)} title={`${sourceTitle(s.source)}: ${s.title}`}>
              {sourceShort(s.source)}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
    </div>
  );
}

/** The site's own word under the user's correction, the correction taken back by ↺. */
function SiteSaid({ said, onReset }: { said?: string; onReset: () => Promise<unknown> }) {
  const { t } = useTranslation("item");
  return (
    <div className="mt-0.5 flex items-start gap-1 text-xs text-muted-foreground">
      <span className="min-w-0 flex-1 line-clamp-3">{said === undefined ? t("WorkScreen.siteSaysNothing") : t("WorkScreen.siteSays", { value: said })}</span>
      <AsyncButton variant="ghost" size="icon-xs" title={t("WorkScreen.takeBackCorrection")} onClick={onReset} icon={<Undo2Icon />} />
    </div>
  );
}

/**
 * WHAT EACH SITE SAYS OF THE WORK, side by side, read from the work alone: its title, authors,
 * description, numbers and how many reviews, pictures and parts it has. A row whose sites say
 * otherwise, an absent value not counted, is marked ≠. What the user corrected stands apart from the
 * site's own word, with ↺.
 */
export function SitesTable({
  type,
  sites,
  shown,
  onShow,
  corrections,
}: {
  type: ContentTypeInfo;
  sites: WorkSite[];
  shown: ItemRef;
  onShow: (site: ItemRef) => void;
  corrections: Corrections;
}) {
  const { t } = useTranslation("item");
  const description = (s: WorkSite) => type.sources.find((x) => x.id === s.source)?.texts.find((x) => x.role === "DESCRIPTION")?.key;
  const numbers = [...new Map(sites.flatMap((s) => s.numbers).map((n) => [n.key, n.labelKey])).entries()];
  const rows: { label: string; plain: (s: WorkSite) => string | undefined; cell?: (s: WorkSite) => ReactNode }[] = [
    {
      label: t("WorkSites.title"),
      plain: (s) => s.title,
      cell: (s) => (
        <>
          {s.title}
          {s.titleCorrected && <SiteSaid said={s.siteTitle} onReset={() => corrections.resetField(titleField)} />}
        </>
      ),
    },
    { label: t("WorkSites.authors"), plain: (s) => s.authors.join(", ") || undefined },
    {
      label: t("WorkSites.description"),
      plain: (s) => s.description,
      cell: (s) => (
        <>
          <span className="line-clamp-6">{s.description}</span>
          {s.descriptionCorrected && <SiteSaid said={s.siteDescription} onReset={() => corrections.resetField(textField(description(s) ?? ""))} />}
        </>
      ),
    },
    ...numbers.map(([key, labelKey]) => {
      const of = (s: WorkSite) => s.numbers.find((n) => n.key === key);
      return {
        label: label(labelKey),
        plain: (s: WorkSite) => of(s)?.value.toString(),
        cell: (s: WorkSite) => {
          const n = of(s);
          if (!n) return null;
          return (
            <>
              {number(n.value)}
              {n.corrected && <SiteSaid said={n.siteValue === undefined ? undefined : number(n.siteValue)} onReset={() => corrections.resetField(numberField(key))} />}
            </>
          );
        },
      };
    }),
    { label: t("ItemDialog.reviews"), plain: (s) => (s.reviewCount > 0 ? s.reviewCount.toString() : undefined) },
    { label: t("ItemDialog.pictures"), plain: (s) => (s.pictureCount > 0 ? s.pictureCount.toString() : undefined) },
    { label: t("ItemDialog.parts"), plain: (s) => (s.partCount > 0 ? s.partCount.toString() : undefined) },
  ];
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr>
            <th />
            {sites.map((s) => (
              <th key={refKey(s)} className="px-2 py-1 text-left align-bottom font-medium">
                <Button variant={sameItem(s, shown) ? "secondary" : "ghost"} size="xs" title={sourceTitle(s.source)} onClick={() => onShow({ source: s.source, item: s.item })}>
                  {sourceShort(s.source)}
                </Button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const said = sites.map(r.plain).filter((v) => v !== undefined);
            const differ = new Set(said.map((v) => v!.trim())).size > 1;
            return (
              <tr key={r.label} className="border-t align-top">
                <th className="py-1 pr-2 text-left text-xs font-normal whitespace-nowrap text-muted-foreground">
                  {r.label}
                  {differ && <span className="ml-1 text-maybe" title={t("WorkScreen.sitesDiffer")}>≠</span>}
                </th>
                {sites.map((s) => (
                  <td key={refKey(s)} className={cn("min-w-32 px-2 py-1 break-words", sameItem(s, shown) && "font-medium")}>
                    {r.cell ? r.cell(s) : r.plain(s)}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** The sites saying one thing, as many as they are: one value with the marks of those that give it. */
function Saying({ label, values, shown }: { label: string; values: [string, WorkSite[]][]; shown: ItemRef }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs text-muted-foreground">{label}</span>
      {values.map(([value, sites]) => (
        <div key={value} className="flex items-baseline gap-2 text-sm">
          <span className="shrink-0 text-xs text-muted-foreground" title={sites.map((s) => sourceTitle(s.source)).join(", ")}>
            {sites.map((s) => sourceShort(s.source)).join(", ")}
          </span>
          <span className={sites.some((s) => sameItem(s, shown)) ? "line-clamp-4 min-w-0 font-medium" : "line-clamp-4 min-w-0"}>{value}</span>
        </div>
      ))}
    </div>
  );
}

/** Of what a site says, the values of each, the sites giving one and the same together; none when they all say the same. */
function differing(sites: WorkSite[], say: (site: WorkSite) => string | undefined): [string, WorkSite[]][] {
  const values = new Map<string, WorkSite[]>();
  for (const site of sites) {
    const value = say(site)?.trim();
    if (value) values.set(value, [...(values.get(value) ?? []), site]);
  }
  return values.size > 1 ? [...values] : [];
}

/** Whether the sites of the work say anything otherwise. */
export const sitesDiffer = (sites: WorkSite[]) =>
  [(s: WorkSite) => s.title, (s: WorkSite) => s.authors.join(", "), (s: WorkSite) => s.description].some((say) => differing(sites, say).length > 0);

/**
 * WHAT THE SITES OF A WORK SAY OTHERWISE: its title, its authors and its description may come
 * from several sites and differ. Only what differs is shown, each value with the sites that give it;
 * the one of the site the window stands on stands out.
 */
export function WorkSites({ details, shown }: { details: ItemDetails; shown: ItemRef }) {
  const { t } = useTranslation("item");
  const titles = differing(details.sites, (s) => s.title);
  const authors = differing(details.sites, (s) => s.authors.join(", "));
  const descriptions = differing(details.sites, (s) => s.description);
  return (
    <div className="flex flex-col gap-2">
      {titles.length > 0 && <Saying label={t("WorkSites.title")} values={titles} shown={shown} />}
      {authors.length > 0 && <Saying label={t("WorkSites.authors")} values={authors} shown={shown} />}
      {descriptions.length > 0 && <Saying label={t("WorkSites.description")} values={descriptions} shown={shown} />}
    </div>
  );
}
