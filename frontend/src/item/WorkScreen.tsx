import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ChevronDownIcon, ChevronLeftIcon, ChevronRightIcon, ExternalLinkIcon, LayoutPanelTopIcon, StarIcon } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import { api, sameItem, unwrap, type ContentTypeInfo, type Grade, type ItemDetails, type ItemRef, type ItemSummary, type SourceInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useSource } from "@/contenttype/types";
import { FacetCorrections } from "@/correction/FacetCorrections";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { Links } from "@/correction/Links";
import { titleField, useCorrections, type Corrections } from "@/correction/useCorrections";
import { WorkDuplicates, WorkRelations } from "@/duplicate/WorkDuplicates";
import { decided } from "@/facet/valueOrder";
import { score } from "@/i18n/format";
import { label, sourceShort, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { Explanation } from "@/model/Explanation";
import { Parts } from "@/part/Parts";
import { PictureMatches, Pictures } from "@/picture/Pictures";
import { ReviewMatches, Reviews } from "@/review/Reviews";
import { FANFIC_FACETS, FanficSection } from "@/universe/FanficSection";
import { TagsByText } from "@/tagtext/TagsByText";
import { ItemNumbers } from "./ItemNumbers";
import { ItemTexts } from "./ItemTexts";
import { itemKey } from "./lists";
import { Cover, InterestButtons, PreviousRating, RatingButtons, ScoreBadge, SignalBadges } from "./pieces";
import { RelatedWorks } from "./RelatedWorks";
import { RemoveItem } from "./RemoveItem";
import { FITTING, useHeldOut } from "./useHeldOut";
import { Byline, FactLine } from "./WorkFacts";
import { NotFetched, Part, Ratings, RefreshButton } from "./WorkParts";
import { SitesTable } from "./WorkSites";

/** The dialog as the whole screen: there is no outside to press. */
export const WORK_SCREEN_CLASS = "flex h-dvh w-screen max-w-none flex-col gap-0 overflow-hidden rounded-none p-0 ring-0 sm:w-screen";

const CHOICE_KEY = "work.screen";

/** Which screen of a work the user chose, kept in the browser: the new one, or the window it is to replace. */
export function useChosenWorkScreen() {
  const [chosen, setChosen] = useState(() => {
    try {
      return localStorage.getItem(CHOICE_KEY) === "new";
    } catch {
      return false;
    }
  });
  const choose = (value: boolean) => {
    setChosen(value);
    try {
      localStorage.setItem(CHOICE_KEY, value ? "new" : "old");
    } catch {
      // Not kept: the choice lasts while the page is open
    }
  };
  return [chosen, choose] as const;
}

/** What the left page shows, one at a time. */
type View = "work" | "chapters" | "reviews" | "pictures" | "why" | "sites";

/**
 * THE SCREEN OF A WORK: left to read, right to answer, head to decide (docs/work-screen.md). The
 * values of the right page are the same chips as in the window it replaces and in the extension. The
 * marks of the sites in the head switch the whole screen to that site; the values and answers stay,
 * being the work's. On a narrow screen the pages are one at a time, and the verdict folds: a missed
 * tap under the last ✕ neither grades nor overwrites.
 */
export function WorkScreen({
  type,
  source: listedSource,
  summary,
  index,
  count,
  total,
  onIndex,
  onRate,
  onOldScreen,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  summary: ItemSummary;
  index: number;
  count: number;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (grade: Grade | null) => unknown;
  onOldScreen: () => void;
}) {
  const { t } = useTranslation("item");
  const [ref, setRef] = useState<ItemRef>({ source: summary.source, item: summary.item });
  const details = useQuery({
    queryKey: itemKey(ref),
    queryFn: async () => unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: ref } })),
    // The marks of the sites stay while another site is read
    placeholderData: keepPreviousData,
  });
  const corrections = useCorrections(ref, type.id);
  const [editingTitle, setEditingTitle] = useState(false);
  const [page, setPage] = useState<"answers" | "work">("answers");
  const [view, setView] = useState<View>("work");
  const [verdictOpen, setVerdictOpen] = useState(false);
  const d = details.data;
  const shown = d?.summary ?? summary;
  const source = useSource(shown.source)?.source ?? listedSource;
  const facets = d?.allFacets ?? summary.facets;
  const tagFacet = d?.allFacets.find((f) => f.tags && f.editable)?.facet;
  const values = d?.allFacets.filter((f) => f.editable).flatMap((f) => f.values) ?? [];
  const toAnswer = values.filter((v) => !decided(v) && !v.offered && !v.implied).length;
  const offers = values.filter((v) => !decided(v) && v.offered).length;
  const graded = summary.grade != null;

  return (
    <>
      <header className="shrink-0 border-b px-3 py-2 pr-11">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <div className="flex items-center">
            <Button variant="ghost" size="icon-sm" onClick={() => onIndex(Math.max(index - 1, 0))} disabled={index === 0} title={t("ItemDialog.previous")}>
              <ChevronLeftIcon />
            </Button>
            <span className="text-xs tabular-nums text-muted-foreground">
              {index + 1}/{total}
            </span>
            <Button variant="ghost" size="icon-sm" onClick={() => onIndex(Math.min(index + 1, count - 1))} disabled={index >= count - 1} title={t("ItemDialog.next")}>
              <ChevronRightIcon />
            </Button>
          </div>
          <div className="h-10 w-14 shrink-0 overflow-hidden rounded bg-muted">
            <Cover typeId={type.id} summary={shown} className="size-full" />
          </div>
          <div className="min-w-0 flex-1 basis-64">
            {editingTitle ? (
              <FieldEditor
                value={shown.title}
                corrected={d?.titleCorrected ?? false}
                onSave={(content) => corrections.setField(titleField, content)}
                onReset={() => corrections.resetField(titleField)}
                onClose={() => setEditingTitle(false)}
                className="max-w-2xl"
              />
            ) : (
              <div className="group/title flex items-center gap-1">
                <DialogTitle className="text-lg leading-tight font-semibold break-words">{shown.title}</DialogTitle>
                {d?.titleCorrected && <CorrectedMark />}
                <EditButton onClick={() => setEditingTitle(true)} title={t("ItemDialog.editTitle")} />
              </div>
            )}
            <DialogDescription render={<div />} className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground">
              <Byline source={source} facets={facets} />
              <FactLine source={source} summary={shown} facets={facets} />
              <SignalBadges source={source} signals={shown.signals} />
            </DialogDescription>
          </div>
          <SiteMarks sites={d?.sites ?? [{ source: shown.source, item: shown.item, url: shown.url, title: shown.title }]} shown={ref} onShow={setRef} />
          <div className="flex flex-wrap items-center gap-1">
            {tagFacet && <TagsByText item={ref} facet={tagFacet} corrections={corrections} size="sm" className="" />}
            {d?.canRefresh && <RefreshButton item={ref} />}
            <Button variant="ghost" size="sm" onClick={onOldScreen} title={t("WorkScreen.oldHint")}>
              <LayoutPanelTopIcon /> {t("WorkScreen.old")}
            </Button>
          </div>
        </div>
        {/* A phone keeps the verdict folded: the buttons under a thumb are the answers' */}
        <Button variant="outline" size="sm" className="mt-1 w-full justify-between min-[56rem]:hidden" aria-expanded={verdictOpen} onClick={() => setVerdictOpen(!verdictOpen)}>
          {t("WorkScreen.verdict")}
          <ChevronDownIcon className={cn("transition-transform", verdictOpen && "rotate-180")} />
        </Button>
        <div className={cn("mt-1 flex-wrap items-center gap-x-3 gap-y-1", verdictOpen ? "flex" : "hidden min-[56rem]:flex")}>
          {/* Keyed by the work: a press at work on one is not shown on the next the screen moves to */}
          <InterestButtons key={`interest:${summary.source}/${summary.item}`} summary={summary} labels />
          <RatingButtons
            key={`grade:${summary.source}/${summary.item}`}
            type={type}
            value={summary.grade as Grade | undefined}
            onRate={onRate}
            size="sm"
            labels
            className="min-w-0 flex-1 basis-full min-[56rem]:basis-[36rem]"
          />
          <PredictionNumber type={type} summary={summary} graded={graded} />
          <PreviousRating type={type} summary={summary} />
        </div>
      </header>

      <div className="flex shrink-0 gap-1 border-b p-1 min-[56rem]:hidden">
        <Button variant={page === "answers" ? "secondary" : "ghost"} size="sm" className="flex-1" onClick={() => setPage("answers")}>
          {t("WorkScreen.answers", { count: toAnswer })}
        </Button>
        <Button variant={page === "work" ? "secondary" : "ghost"} size="sm" className="flex-1" onClick={() => setPage("work")}>
          {t("WorkScreen.work")}
        </Button>
      </div>

      <div className="grid min-h-0 flex-1 min-[56rem]:grid-cols-2 min-[56rem]:divide-x">
        <section className={cn("min-h-0 min-w-0 overflow-y-auto", page === "work" ? "block" : "hidden min-[56rem]:block")}>
          {details.isLoading ? (
            <Loading />
          ) : d ? (
            <LeftPage type={type} source={source} details={d} corrections={corrections} view={view} onView={setView} onShow={setRef} shown={ref} />
          ) : (
            <div className="p-4 text-destructive">{t("ItemDialog.detailsFailed")}</div>
          )}
        </section>
        <section className={cn("min-h-0 min-w-0 overflow-y-auto p-3", page === "answers" ? "block" : "hidden min-[56rem]:block")}>
          {details.isLoading ? (
            <Loading />
          ) : d ? (
            <div className="flex flex-col gap-4">
              <p className="text-sm font-medium">
                {t("WorkScreen.toAnswer", { count: toAnswer })}
                {offers > 0 && <span className="font-normal text-muted-foreground"> · {t("WorkScreen.offers", { count: offers })}</span>}
              </p>
              {d.sites.length > 1 && <p className="text-xs text-muted-foreground">{t("WorkSites.markingOfWork")}</p>}
              {type.universes && (
                <Part title={t("ItemDialog.fanfic")}>
                  <FanficSection type={type} source={source} details={d} corrections={corrections} />
                </Part>
              )}
              <Part title={t("ItemDialog.facets")}>
                <FacetCorrections item={ref} source={source} facets={d.allFacets} corrections={corrections} except={type.universes ? FANFIC_FACETS : undefined} />
              </Part>
            </div>
          ) : null}
        </section>
      </div>
    </>
  );
}

function Loading() {
  return (
    <div className="grid gap-3 p-4">
      <Skeleton className="h-5 w-40" />
      <Skeleton className="h-24 w-full" />
      <Skeleton className="h-24 w-full" />
    </div>
  );
}

/** The sites of the work, the one that says the most first: a press switches the screen to it, the arrow opens its page. */
function SiteMarks({ sites, shown, onShow }: { sites: { source: string; item: string; url: string; title: string }[]; shown: ItemRef; onShow: (site: ItemRef) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-1">
      {sites.map((s) => (
        <span key={`${s.source}/${s.item}`} className="inline-flex items-center">
          <Button
            variant={sameItem(s, shown) ? "secondary" : "ghost"}
            size="xs"
            aria-pressed={sameItem(s, shown)}
            title={`${sourceTitle(s.source)}: ${s.title}`}
            onClick={() => onShow({ source: s.source, item: s.item })}
          >
            {sourceShort(s.source)}
          </Button>
          <a href={s.url} target="_blank" rel="noreferrer" className="p-1 text-muted-foreground hover:text-foreground" title={sourceTitle(s.source)}>
            <ExternalLinkIcon className="size-3" />
          </a>
        </span>
      ))}
    </div>
  );
}

/**
 * The model's word beside the grades, as a number and never on them: a mark on the very control the
 * model learns from would anchor the grade. Of a graded work the number of the model that had not
 * seen it, drawn as such; till there is one, says so.
 */
function PredictionNumber({ type, summary, graded }: { type: ContentTypeInfo; summary: ItemSummary; graded: boolean }) {
  const { t } = useTranslation("item");
  if (!graded) return <ScoreBadge type={type} summary={summary} />;
  return (
    <span className="inline-flex items-center gap-1 text-xs text-muted-foreground" title={t("ItemDialog.withoutGrade")}>
      <StarIcon className="size-3" />
      {summary.heldOut ? t("WorkScreen.heldOut", { score: score(Math.max(0, summary.heldOut.score)) }) : t("WorkScreen.noEstimate")}
    </span>
  );
}

/** The left page: one view at a time of what there is to read. It never covers the answers. */
function LeftPage({
  type,
  source,
  details: d,
  corrections,
  view,
  onView,
  onShow,
  shown,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  corrections: Corrections;
  view: View;
  onView: (view: View) => void;
  onShow: (site: ItemRef) => void;
  shown: ItemRef;
}) {
  const { t } = useTranslation("item");
  const ref = { source: d.summary.source, item: d.summary.item };
  const reviewsLabel = source.reviewsLabelKey ? label(source.reviewsLabelKey) : t("ItemDialog.reviews");
  const partsLabel = source.partsLabelKey ? label(source.partsLabelKey) : t("ItemDialog.parts");
  const nothing = d.texts.length === 0 && d.pictures.length === 0 && d.reviews.length === 0 && d.parts.length === 0;
  return (
    <div className="flex flex-col gap-3 p-3">
      <Tabs value={view} onValueChange={(v) => onView(v as View)}>
        <TabsList className="h-auto flex-wrap">
          <TabsTrigger value="work">{t("WorkScreen.work")}</TabsTrigger>
          <TabsTrigger value="chapters">
            {partsLabel} {d.parts.length}
          </TabsTrigger>
          <TabsTrigger value="reviews">
            {reviewsLabel} {d.reviewCount ?? d.reviews.length}
          </TabsTrigger>
          <TabsTrigger value="pictures">
            {t("ItemDialog.pictures")} {d.pictures.length}
          </TabsTrigger>
          <TabsTrigger value="why">{t("WorkScreen.why")}</TabsTrigger>
          <TabsTrigger value="sites">
            {t("WorkScreen.sites")} {d.sites.length}
          </TabsTrigger>
        </TabsList>
      </Tabs>
      {view === "work" && (
        <>
          {nothing && d.canRefresh && <NotFetched item={ref} />}
          <Part title={t("ItemDialog.texts")}>
            <ItemTexts texts={d.texts} source={source} corrections={corrections} editing />
          </Part>
          <Part title={t("ItemDialog.numbers")}>
            <ItemNumbers numbers={d.allNumbers} source={source} corrections={corrections} />
          </Part>
          <Part title={t("ItemDialog.seriesAndAuthor")}>
            <RelatedWorks type={type} related={d.related} />
          </Part>
        </>
      )}
      {view === "chapters" && (
        <Part title={partsLabel}>
          <Parts item={ref} label={partsLabel} parts={d.parts} />
        </Part>
      )}
      {view === "reviews" && (
        <>
          <Part title={reviewsLabel}>
            <Reviews item={ref} label={reviewsLabel} reviews={d.reviews} total={d.reviewCount} explained />
          </Part>
          <Part title={t("ItemDialog.similarReviews")}>
            <ReviewMatches item={ref} matches={d.reviewMatches} />
          </Part>
        </>
      )}
      {view === "pictures" && (
        <>
          <Part title={t("ItemDialog.pictures")}>
            <Pictures item={ref} pictures={d.pictures} explained />
          </Part>
          <Part title={t("ItemDialog.similarPictures")}>
            <PictureMatches item={ref} matches={d.pictureMatches} />
          </Part>
        </>
      )}
      {view === "why" && <WhyView type={type} details={d} />}
      {view === "sites" && (
        <>
          <Part title={t("WorkScreen.sites")}>
            <SitesTable type={type} sites={d.sites} shown={shown} onShow={onShow} corrections={corrections} />
          </Part>
          <Part title={t("ItemDialog.sameWork")}>
            <Links type={type} summary={d.summary} corrections={corrections} />
          </Part>
          <Part title={t("WorkScreen.duplicates")}>
            <WorkDuplicates type={type} item={ref} sites={d.sites} corrections={corrections} />
          </Part>
          <Part title={t("WorkScreen.relations")}>
            <WorkRelations type={type} item={ref} relations={d.relations} />
          </Part>
          <RemoveItem summary={d.summary} typeId={type.id} />
        </>
      )}
    </div>
  );
}

/** Why the prediction: of a graded work by the model that has not seen it, read for this view alone; the grades given under it. */
function WhyView({ type, details: d }: { type: ContentTypeInfo; details: ItemDetails }) {
  const { t } = useTranslation("item");
  const ref = { source: d.summary.source, item: d.summary.item };
  const graded = d.summary.grade != null;
  const heldOut = useHeldOut(ref, graded);
  return (
    <>
      {graded && (heldOut.data === undefined || heldOut.data === FITTING) && !heldOut.isError ? (
        <Skeleton className="h-16 w-full" />
      ) : heldOut.data && heldOut.data !== FITTING ? (
        <Part title={t("ItemDialog.withoutGrade")}>
          <div className={cn("flex flex-col gap-1", heldOut.isFetching && "opacity-60")}>
            <div className="text-sm">
              <Trans
                t={t}
                i18nKey="ItemDialog.heldOutScore"
                values={{ score: score(Math.max(0, heldOut.data.score)) }}
                components={{ score: <span className="font-semibold tabular-nums" /> }}
              />
            </div>
            <Explanation groups={heldOut.data.explanation} title={t("ItemDialog.drivenBy")} />
          </div>
        </Part>
      ) : (
        <Part title={t("ItemDialog.whyPrediction")}>
          <Explanation groups={d.explanation} />
        </Part>
      )}
      {d.ratings.length > 0 && (
        <Part title={t("ItemDialog.myGrades")}>
          <Ratings type={type} details={d} />
        </Part>
      )}
    </>
  );
}
