import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, Loader2Icon, TagsIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, type TagInfo, type TagNameRule, type TagRef, type TagRelation, unwrap } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";
import { HierarchyOfferList } from "./HierarchyOfferList";
import { IgnoreButton } from "./IgnoreTag";
import type { Said } from "./relations";
import { Similar } from "./SimilarTags";
import { TagEntry } from "./TagEntry";
import { TagList } from "./TagList";
import { TAGS, useTagsChanged } from "./tagQueries";

/** What a change of the tags is read anew by — the screens that change tags take it from the screen of the tags. */
export { useTagsChanged } from "./tagQueries";

/** The key of the words saying how a tag's name was chosen. */
function ruleKey(rule: TagNameRule) {
  return `Rule.${rule}` as const;
}

/**
 * THE SCREEN OF THE TAGS: the normalized tags of a content type's free tags. Every site's tag is
 * bound to a normalized tag — at first to the one of its name, any case; the user merges tags that
 * are one — "Angst" and its Russian spelling — names a tag, takes a site's tag out of one, puts tags under
 * others, tells two apart. The application only offers tags alike by the meaning of their names; it
 * merges nothing by itself.
 */
export function TagsScreen({ selected }: { selected?: string }) {
  const { t } = useTranslation("tag");
  return (
    <div className="mx-auto grid max-w-[1800px] grid-cols-[minmax(0,1fr)] gap-4 px-4 py-4 lg:grid-cols-[minmax(0,28rem)_minmax(0,1fr)]">
      <div className={cn("flex flex-col gap-6", selected && "hidden lg:flex")}>
        <TagList selected={selected} />
        <HierarchyOfferList />
      </div>
      {selected ? (
        <TagDetails key={selected} tag={selected} />
      ) : (
        <div className="hidden flex-col gap-2 text-sm text-muted-foreground lg:flex">
          <h1 className="flex items-center gap-2 text-xl font-semibold text-foreground">
            <TagsIcon className="size-5" /> {t("TagsScreen.title")}
          </h1>
          <p>{t("TagsScreen.intro")}</p>
          <p>{t("TagsScreen.nameRule")}</p>
        </div>
      )}
    </div>
  );
}

function TagDetails({ tag }: { tag: string }) {
  const { t } = useTranslation("tag");
  const info = useQuery({
    queryKey: [TAGS, "one", tag],
    queryFn: async () => unwrap(await api.GET("/api/tags/one", { params: { query: { tag } } })),
  });
  const found = info.data;
  return (
    <section className="flex min-w-0 flex-col gap-4">
      <a
        href={screenHref({ kind: "tags" })}
        onClick={(e) => followLink(e, { kind: "tags" })}
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground lg:hidden"
      >
        <ArrowLeftIcon className="size-4" /> {t("TagDetails.allTags")}
      </a>
      {info.isLoading && <Loader2Icon className="size-5 animate-spin text-muted-foreground" />}
      {info.isError && <p className="text-sm text-destructive">{t("TagDetails.loadError", { error: info.error.message })}</p>}
      {found && (
        <>
          <header className="flex flex-col gap-1">
            <h1 className="flex items-center gap-2 text-xl font-semibold">
              {found.name}
              <IgnoreButton tag={found} />
              {found.ignored && <span className="text-sm font-normal text-muted-foreground">{t("Ignore.ignored")}</span>}
            </h1>
            <p className="text-sm text-muted-foreground">
              {t(ruleKey(found.rule))} · {t("TagDetails.works", { count: found.works })} · {t("TagDetails.graded", { graded: found.graded })} ·{" "}
              {t("TagDetails.liked", { liked: found.liked })}
            </p>
          </header>
          <TagDescriptions tag={found} />
          <TagName tag={found} />
          <TagEntry tag={found} />
          <Related tag={found} />
          <CatalogueOffers tag={found} />
          <Variants tag={found} />
          <Similar tag={found} />
        </>
      )}
    </section>
  );
}

/** The tags right above and under this one, each a link to its own page. */
function Related({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  if (tag.parents.length === 0 && tag.children.length === 0) return null;
  const links = (list: TagRef[]) =>
    list.map((r, i) => (
      <span key={r.key}>
        {i > 0 && ", "}
        <a href={screenHref({ kind: "tags", tag: r.key })} onClick={(e) => followLink(e, { kind: "tags", tag: r.key })} className="underline-offset-2 hover:underline">
          {r.name}
        </a>
      </span>
    ));
  return (
    <div className="flex flex-col gap-0.5 text-sm">
      {tag.parents.length > 0 && (
        <div title={t("Related.parentsTitle")}>
          <span className="text-muted-foreground">{t("Related.parents")} </span>
          {links(tag.parents)}
        </div>
      )}
      {tag.children.length > 0 && (
        <div title={t("Related.childrenTitle")}>
          <span className="text-muted-foreground">{t("Related.children")} </span>
          {links(tag.children)}
        </div>
      )}
    </div>
  );
}

/**
 * Other tags the catalogues offer above or under this one — by what the entries they are bound to
 * are a kind of, the way up shown — each said so by its own button, or left as it is.
 */
function CatalogueOffers({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged();
  if (tag.catalogueOffers.length === 0) return null;
  const relate = async (other: string, relation: TagRelation) => {
    unwrap(await api.PUT("/api/tags/relation", { body: { tag: tag.key, other, relation } }));
    await changed();
  };
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">{t("Offers.title")}</h2>
      <ul className="flex flex-col divide-y rounded-lg border">
        {tag.catalogueOffers.map((o) => (
          <li key={o.tag.key} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5">
            <div className="flex min-w-0 flex-1 flex-col">
              <span>
                <span className="text-muted-foreground">{t(`Relation.${o.relation as Said}.label`)} </span>
                <a href={screenHref({ kind: "tags", tag: o.tag.key })} onClick={(e) => followLink(e, { kind: "tags", tag: o.tag.key })} className="underline-offset-2 hover:underline">
                  {o.tag.name}
                </a>
              </span>
              <span className="text-xs text-muted-foreground">
                {sourceTitle(o.catalogue)}: {o.path.join(" → ")}
              </span>
            </div>
            <AsyncButton variant="outline" size="sm" onClick={() => relate(o.tag.key, o.relation)} title={t(`Relation.${o.relation as Said}.title`)}>
              {t(o.relation === "PARENT" ? "Offers.makeParent" : "Offers.makeChild")}
            </AsyncButton>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The name the user gives the tag, or none: then the sites' names decide. */
function TagName({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged();
  const [name, setName] = useState(tag.explicitName ?? "");
  const save = useMutation({
    mutationFn: async (value: string | null) =>
      unwrap(await api.PUT("/api/tags/name", { body: { tag: tag.key, name: value ?? undefined } })),
    onSuccess: (saved) => {
      setName(saved.explicitName ?? "");
      return changed();
    },
  });
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate(name.trim() || null);
      }}
    >
      <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tag.name} className="max-w-sm" aria-label={t("TagName.label")} />
      <Button type="submit" size="sm" disabled={save.isPending || name.trim() === (tag.explicitName ?? "")}>
        {save.isPending && <Loader2Icon className="animate-spin" />} {t("TagName.save")}
      </Button>
      {tag.explicitName && (
        <Button type="button" variant="ghost" size="sm" onClick={() => save.mutate(null)} disabled={save.isPending} title={t("TagName.resetTitle")}>
          <XIcon /> {t("TagName.reset")}
        </Button>
      )}
      {save.isError && <span className="w-full text-sm text-destructive">{t("TagName.saveError", { error: save.error.message })}</span>}
    </form>
  );
}

/**
 * What the tag means: as its sites' tags' own pages say, then as the entries it is bound to do —
 * each with who says it.
 */
function TagDescriptions({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const awaited = tag.awaitedDescriptions ?? [];
  if (tag.descriptions.length === 0 && awaited.length === 0) return null;
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {tag.descriptions.map((d) => (
        <li key={`${d.origin}/${d.name ?? ""}/${d.text}`}>
          {d.text}{" "}
          <span className="text-xs text-muted-foreground">
            — {sourceTitle(d.origin)}
            {d.name && d.name !== tag.name ? `, «${d.name}»` : ""}
          </span>
        </li>
      ))}
      {/* A site asked for its word by the browser, from a page of it open there: not come yet */}
      {awaited.map((site) => (
        <li key={`awaited-${site}`} className="text-xs text-muted-foreground">
          {t("TagDetails.awaitedDescription", { site: sourceTitle(site) })}
        </li>
      ))}
    </ul>
  );
}

/** The sites' tags bound to the tag, as the sites write them, with their works. */
function Variants({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">{t("Variants.title")}</h2>
      <table className="w-full">
        <thead className="text-xs text-muted-foreground">
          <tr className="text-left">
            <th className="font-normal">{t("Variants.site")}</th>
            <th className="font-normal">{t("Variants.name")}</th>
            <th className="pl-3 text-right font-normal">{t("Variants.works")}</th>
            <th className="hidden pl-3 text-right font-normal sm:table-cell" title={t("Variants.gradedTitle")}>
              {t("Variants.graded")}
            </th>
            <th className="hidden pl-3 text-right font-normal sm:table-cell" title={t("Variants.likedTitle")}>
              {t("Variants.liked")}
            </th>
          </tr>
        </thead>
        <tbody>
          {tag.variants.map((v) => (
            <tr key={`${v.source}/${v.tag}`} className="border-t">
              <td className="py-1 pr-2 text-muted-foreground" title={sourceTitle(v.source)}>
                {sourceShort(v.source)}
              </td>
              <td className="py-1 pr-2">{v.name}</td>
              <td className="py-1 pl-3 text-right tabular-nums">{v.works}</td>
              <td className="hidden py-1 pl-3 text-right tabular-nums sm:table-cell">{v.graded}</td>
              <td className="hidden py-1 pl-3 text-right tabular-nums sm:table-cell">{v.liked}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
