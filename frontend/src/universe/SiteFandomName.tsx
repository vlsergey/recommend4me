import { ExternalLinkIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { UniverseSite } from "@/api/client";
import { cn } from "@/lib/utils";

/** A site's fandom by its name, a link to its page on the site when the source tells it, as an author's account is. */
export function SiteFandomName({ site: s, className }: { site: UniverseSite; className?: string }) {
  const { t } = useTranslation("universe");
  if (!s.url) return <span className={className}>{s.name}</span>;
  return (
    <a href={s.url} target="_blank" rel="noreferrer" title={t("SiteFandomName.page")} className={cn(className, "underline-offset-2 hover:underline")}>
      {s.name}
      <ExternalLinkIcon className="ml-0.5 inline size-3 align-baseline opacity-60" />
    </a>
  );
}
