import { useTranslation } from "react-i18next";
import type { ItemDetails, ItemRef, WorkSite } from "@/api/client";
import { refKey, sameItem } from "@/api/client";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { sourceShort, sourceTitle } from "@/i18n/label";

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
