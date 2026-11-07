import type { ReactNode } from "react";
import type { TagInfo } from "@/api/client";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";

/**
 * The names of the sites' tags merged into a tag other than the one it is shown by, each once, any
 * case, in the order the name is chosen by (TagInfo.names): without a name the user gave, the first
 * is the tag's name, the rest follow it; with one, the first leads the rest.
 */
export function otherNames(tag: TagInfo): string[] {
  const seen = new Set([tag.name.toLowerCase()]);
  return tag.names.filter((n) => !seen.has(n.toLowerCase()) && seen.add(n.toLowerCase()));
}

/**
 * A tag's name, then the other names merged into it in brackets in a fainter type — cut with an
 * ellipsis where the row ends, every one of them in the title.
 */
export function TagNames({ tag, className, children }: { tag: TagInfo; className?: string; children?: ReactNode }) {
  const others = otherNames(tag);
  return (
    <span className={cn("flex min-w-0 items-baseline gap-1", className)} title={[tag.name, ...others].join(" · ")}>
      {/* The name first, whole as far as the row allows; the others in what is left */}
      {children ?? <span className="max-w-full shrink-0 truncate">{tag.name}</span>}
      {others.length > 0 && <span className="min-w-0 truncate text-muted-foreground">({others.join(", ")})</span>}
    </span>
  );
}

/** The sites a tag is used on, by their short titles ("КФ, AO3"), the full ones in the title. */
export function SiteNames({ tag, className }: { tag: TagInfo; className?: string }) {
  const sites = sitesOf(tag);
  return (
    <span className={cn("truncate text-xs text-muted-foreground", className)} title={sites.map(sourceTitle).join(", ")}>
      {sites.map(sourceShort).join(", ")}
    </span>
  );
}

/** The sites a tag is used on, each once, and after them the catalogues it is bound to an entry of: shown as sites. */
function sitesOf(tag: TagInfo): string[] {
  return [...new Set([...tag.variants.map((v) => v.source), ...tag.catalogueEntries.map((e) => e.catalogue)])];
}
