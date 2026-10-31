import type { ReactNode } from "react";
import { ExternalLinkIcon, UnlinkIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, ItemRef, WorkSite } from "@/api/client";
import { refKey, sameItem } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { number } from "@/i18n/format";
import { label, sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { RefreshButton } from "./WorkParts";

/**
 * IS THIS REALLY THE SAME WORK? What each site has, side by side, read from the work alone: its title,
 * authors, description, numbers and how many reviews, pictures and parts it has, as the sites have
 * them. A row whose sites say otherwise, an absent value not counted, is marked ≠. Under every site
 * its page, its refresh, and "not this work".
 */
export function SitesTable({
  type,
  sites,
  shown,
  onShow,
  onTakeOut,
}: {
  type: ContentTypeInfo;
  sites: WorkSite[];
  shown: ItemRef;
  onShow: (site: ItemRef) => void;
  onTakeOut: (site: ItemRef) => Promise<unknown>;
}) {
  const { t } = useTranslation("item");
  const description = (s: WorkSite) => {
    const key = type.sources.find((x) => x.id === s.source)?.texts.find((x) => x.role === "DESCRIPTION")?.key;
    return s.texts.find((x) => x.key === key)?.siteContent;
  };
  const numbers = [...new Map(sites.flatMap((s) => s.numbers).map((n) => [n.key, n.labelKey])).entries()];
  const rows: { label: string; plain: (s: WorkSite) => string | undefined; cell?: (s: WorkSite) => ReactNode }[] = [
    { label: t("WorkSites.title"), plain: (s) => s.siteTitle },
    { label: t("WorkSites.authors"), plain: (s) => s.authors.join(", ") || undefined },
    { label: t("WorkSites.description"), plain: description, cell: (s) => <span className="line-clamp-6">{description(s)}</span> },
    ...numbers.map(([key, labelKey]) => {
      const of = (s: WorkSite) => s.numbers.find((n) => n.key === key)?.siteValue ?? undefined;
      return {
        label: label(labelKey),
        plain: (s: WorkSite) => of(s)?.toString(),
        cell: (s: WorkSite) => {
          const n = of(s);
          return n === undefined ? null : number(n);
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
          <tr className="border-t align-top">
            <th />
            {sites.map((s) => (
              <td key={refKey(s)} className="px-2 py-1">
                <div className="flex flex-wrap gap-1">
                  <Button variant="outline" size="sm" nativeButton={false} render={<a href={s.url} target="_blank" rel="noreferrer" />} title={sourceTitle(s.source)}>
                    <ExternalLinkIcon /> {t("WorkScreen.open")}
                  </Button>
                  {s.canRefresh && <RefreshButton item={s} />}
                  {sites.length > 1 && (
                    <AsyncButton variant="ghost" size="sm" icon={<UnlinkIcon />} title={t("WorkScreen.notThisWorkHint")} onClick={() => onTakeOut({ source: s.source, item: s.item })}>
                      {t("WorkScreen.notThisWork")}
                    </AsyncButton>
                  )}
                </div>
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

