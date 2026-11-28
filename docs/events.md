# The events and the works of the application

Made of the compiled code by `EventFlowDocsTest` (`gradlew :app:test --tests "*EventFlowDocsTest*"`): a change
of the code that changes what leads to what fails the test, and the test writes this file anew — commit it.
What it reads is said in `EventFlow`: an event is an object of a class some `@EventListener` listens to; a work
is planned by `Tasks.plan` or run by `Tasks.run`, its kind named where it is planned.

An arrow is "may lead to, directly": the events a thing makes, the works it plans. A request's events and
works go on as the diagram shows.

## Legend

```mermaid
flowchart LR
    outside(("from outside: the start, a loop of the background")) --> event["an event of the application"]
    event --> task{{"a task of the queue, by its kind"}}
    task --> event
```

- a circle: what comes from outside the flow — `start`, the start of the application (the listeners of
  `ApplicationReadyEvent`) deciding of the data; `left by the last run`, the works the last run left in the
  queue planned again by their owners (`Tasks.resume`); a loop of the background by its name
  (`Background.start`, `every`, `endless`);
- a box: an event of the application, by its class;
- a hexagon: a task of the queue, by its `TaskKind`;
- a request of the interface or the extension: a row of the table below, by its operation's id.

## What leads to what

```mermaid
flowchart LR

    o_job__(("job-*"))
    o_pictures(("pictures"))
    o_reparse(("reparse"))
    o_start(("start"))
    o_universes_languages(("universes-languages"))
    e_FandomRelationChangeEvent["FandomRelationChangeEvent"]
    e_FandomVectorChangeEvent["FandomVectorChangeEvent"]
    e_FieldModelFitEvent["FieldModelFitEvent"]
    e_FullTextCentreFitEvent["FullTextCentreFitEvent"]
    e_PieceProjectionFitEvent["PieceProjectionFitEvent"]
    e_RatedWorkValueFillEvent["RatedWorkValueFillEvent"]
    e_SearchJudgmentEvent["SearchJudgmentEvent"]
    e_SiteFandomBindingChangeEvent["SiteFandomBindingChangeEvent"]
    e_SiteFandomParentChangeEvent["SiteFandomParentChangeEvent"]
    e_SiteFandomSynonymChangeEvent["SiteFandomSynonymChangeEvent"]
    e_SiteItemCastEncodeEvent["SiteItemCastEncodeEvent"]
    e_SiteItemChapterChangeEvent["SiteItemChapterChangeEvent"]
    e_SiteItemChapterVectorChangeEvent["SiteItemChapterVectorChangeEvent"]
    e_SiteItemMarkChangeEvent["SiteItemMarkChangeEvent"]
    e_SiteItemPictureAnalysisEvent["SiteItemPictureAnalysisEvent"]
    e_SiteItemPictureDownloadEvent["SiteItemPictureDownloadEvent"]
    e_SiteItemPictureListEvent["SiteItemPictureListEvent"]
    e_SiteItemRemoveEvent["SiteItemRemoveEvent"]
    e_SiteItemSetVectorChangeEvent["SiteItemSetVectorChangeEvent"]
    e_SiteItemTextVectorChangeEvent["SiteItemTextVectorChangeEvent"]
    e_SiteItemWriteEvent["SiteItemWriteEvent"]
    e_SiteSeriesPageChangeEvent["SiteSeriesPageChangeEvent"]
    e_SiteTagBindingChangeEvent["SiteTagBindingChangeEvent"]
    e_SiteTagParentChangeEvent["SiteTagParentChangeEvent"]
    e_SiteTagSynonymChangeEvent["SiteTagSynonymChangeEvent"]
    e_TagChangeEvent["TagChangeEvent"]
    e_TagTextAnswerEvent["TagTextAnswerEvent"]
    e_UniverseChangeEvent["UniverseChangeEvent"]
    e_WorkCastNameFillEvent["WorkCastNameFillEvent"]
    e_WorkChangeEvent["WorkChangeEvent"]
    e_WorkChapterWindowFillEvent["WorkChapterWindowFillEvent"]
    e_WorkDocumentWriteEvent["WorkDocumentWriteEvent"]
    e_WorkFieldChangeEvent["WorkFieldChangeEvent"]
    e_WorkFieldFillEvent["WorkFieldFillEvent"]
    e_WorkGradeChangeEvent["WorkGradeChangeEvent"]
    e_WorkGroupChangeEvent["WorkGroupChangeEvent"]
    e_WorkInterestChangeEvent["WorkInterestChangeEvent"]
    e_WorkMannerChangeEvent["WorkMannerChangeEvent"]
    e_WorkMannerFillEvent["WorkMannerFillEvent"]
    e_WorkSetVectorFillEvent["WorkSetVectorFillEvent"]
    e_WorkTextVectorFillEvent["WorkTextVectorFillEvent"]
    w_analyseSiteItemPictures{{"analyseSiteItemPictures"}}
    w_compareFandomWays{{"compareFandomWays"}}
    w_compareFieldMethods{{"compareFieldMethods"}}
    w_compareGradeScorers{{"compareGradeScorers"}}
    w_compareInterestScorers{{"compareInterestScorers"}}
    w_countFandomWorks{{"countFandomWorks"}}
    w_countTags{{"countTags"}}
    w_encodeFandomNames{{"encodeFandomNames"}}
    w_encodeSiteItemCasts{{"encodeSiteItemCasts"}}
    w_encodeSiteItemChapters{{"encodeSiteItemChapters"}}
    w_encodeSiteItemTexts{{"encodeSiteItemTexts"}}
    w_encodeTagNames{{"encodeTagNames"}}
    w_eraseDifferentInSeries{{"eraseDifferentInSeries"}}
    w_fillWorkFields{{"fillWorkFields"}}
    w_fitDuplicateModel{{"fitDuplicateModel"}}
    w_fitFandomLikeness{{"fitFandomLikeness"}}
    w_fitFieldModels{{"fitFieldModels"}}
    w_fitFullTextCentre{{"fitFullTextCentre"}}
    w_fitGradeModels{{"fitGradeModels"}}
    w_fitHeldOutModel{{"fitHeldOutModel"}}
    w_fitMannerWords{{"fitMannerWords"}}
    w_fitPieceProjection{{"fitPieceProjection"}}
    w_fitSearchWeights{{"fitSearchWeights"}}
    w_fitSetDirections{{"fitSetDirections"}}
    w_fitTagTextModel{{"fitTagTextModel"}}
    w_indexWorkTexts{{"indexWorkTexts"}}
    w_keepOutWorks{{"keepOutWorks"}}
    w_keyNamedValues{{"keyNamedValues"}}
    w_letGoUnusedFandoms{{"letGoUnusedFandoms"}}
    w_makeGroupManners{{"makeGroupManners"}}
    w_makeSiteItemSetVectors{{"makeSiteItemSetVectors"}}
    w_makeWorkManners{{"makeWorkManners"}}
    w_measurePartWorth{{"measurePartWorth"}}
    w_measureWorkSizes{{"measureWorkSizes"}}
    w_nameFandoms{{"nameFandoms"}}
    w_nameTags{{"nameTags"}}
    w_predictWorks{{"predictWorks"}}
    w_readScorerInput{{"readScorerInput"}}
    w_relateTagsBySites{{"relateTagsBySites"}}
    w_removeSiteItemTexts{{"removeSiteItemTexts"}}
    w_renameFandoms{{"renameFandoms"}}
    w_searchAuthorDuplicates{{"searchAuthorDuplicates"}}
    w_searchDuplicates{{"searchDuplicates"}}
    w_searchFieldModelAnew{{"searchFieldModelAnew"}}
    w_weighParts{{"weighParts"}}
    w_writeListIndex{{"writeListIndex"}}
    w_writeWorkValues{{"writeWorkValues"}}
    o_job__ --> e_SiteFandomParentChangeEvent
    o_job__ --> e_SiteFandomSynonymChangeEvent
    o_job__ --> e_SiteItemChapterChangeEvent
    o_job__ --> e_SiteItemPictureListEvent
    o_job__ --> e_SiteItemRemoveEvent
    o_job__ --> e_SiteItemWriteEvent
    o_job__ --> e_SiteSeriesPageChangeEvent
    o_job__ --> e_SiteTagParentChangeEvent
    o_job__ --> e_SiteTagSynonymChangeEvent
    o_job__ --> e_WorkGroupChangeEvent
    o_job__ --> w_fitGradeModels
    o_pictures --> e_SiteFandomParentChangeEvent
    o_pictures --> e_SiteFandomSynonymChangeEvent
    o_pictures --> e_SiteItemChapterChangeEvent
    o_pictures --> e_SiteItemPictureDownloadEvent
    o_pictures --> e_SiteItemPictureListEvent
    o_pictures --> e_SiteItemRemoveEvent
    o_pictures --> e_SiteItemWriteEvent
    o_pictures --> e_SiteSeriesPageChangeEvent
    o_pictures --> e_SiteTagParentChangeEvent
    o_pictures --> e_SiteTagSynonymChangeEvent
    o_pictures --> e_WorkGroupChangeEvent
    o_reparse --> e_SiteFandomParentChangeEvent
    o_reparse --> e_SiteFandomSynonymChangeEvent
    o_reparse --> e_SiteItemChapterChangeEvent
    o_reparse --> e_SiteItemPictureListEvent
    o_reparse --> e_SiteItemRemoveEvent
    o_reparse --> e_SiteItemWriteEvent
    o_reparse --> e_SiteSeriesPageChangeEvent
    o_reparse --> e_SiteTagParentChangeEvent
    o_reparse --> e_SiteTagSynonymChangeEvent
    o_reparse --> e_WorkGroupChangeEvent
    o_start --> e_SiteFandomParentChangeEvent
    o_start --> e_SiteFandomSynonymChangeEvent
    o_start --> e_SiteItemChapterChangeEvent
    o_start --> e_SiteItemPictureListEvent
    o_start --> e_SiteItemRemoveEvent
    o_start --> e_SiteItemWriteEvent
    o_start --> e_SiteSeriesPageChangeEvent
    o_start --> e_SiteTagParentChangeEvent
    o_start --> e_SiteTagSynonymChangeEvent
    o_start --> e_WorkGroupChangeEvent
    o_start --> w_analyseSiteItemPictures
    o_start --> w_countFandomWorks
    o_start --> w_encodeSiteItemChapters
    o_start --> w_encodeSiteItemTexts
    o_start --> w_fitFullTextCentre
    o_start --> w_fitGradeModels
    o_start --> w_fitPieceProjection
    o_start --> w_fitTagTextModel
    o_start --> w_indexWorkTexts
    o_start --> w_makeGroupManners
    o_start --> w_makeSiteItemSetVectors
    o_start --> w_searchAuthorDuplicates
    o_start --> w_searchDuplicates
    o_start --> w_writeListIndex
    o_universes_languages --> e_UniverseChangeEvent
    e_FandomRelationChangeEvent --> w_fitFandomLikeness
    e_FandomVectorChangeEvent --> w_fitFandomLikeness
    e_FieldModelFitEvent --> w_fillWorkFields
    e_FullTextCentreFitEvent --> w_encodeSiteItemChapters
    e_FullTextCentreFitEvent --> w_encodeSiteItemTexts
    e_PieceProjectionFitEvent --> w_encodeSiteItemChapters
    e_PieceProjectionFitEvent --> w_encodeSiteItemTexts
    e_RatedWorkValueFillEvent --> w_fitGradeModels
    e_SearchJudgmentEvent --> w_fitSearchWeights
    e_SiteFandomBindingChangeEvent --> e_FandomRelationChangeEvent
    e_SiteFandomBindingChangeEvent --> e_UniverseChangeEvent
    e_SiteFandomBindingChangeEvent --> e_WorkChangeEvent
    e_SiteFandomBindingChangeEvent --> w_renameFandoms
    e_SiteFandomParentChangeEvent --> e_FandomRelationChangeEvent
    e_SiteFandomParentChangeEvent --> e_UniverseChangeEvent
    e_SiteFandomParentChangeEvent --> e_WorkChangeEvent
    e_SiteFandomParentChangeEvent --> w_renameFandoms
    e_SiteFandomSynonymChangeEvent --> e_FandomRelationChangeEvent
    e_SiteFandomSynonymChangeEvent --> e_UniverseChangeEvent
    e_SiteFandomSynonymChangeEvent --> e_WorkChangeEvent
    e_SiteFandomSynonymChangeEvent --> w_renameFandoms
    e_SiteItemCastEncodeEvent --> w_fitFieldModels
    e_SiteItemChapterChangeEvent --> w_fitFieldModels
    e_SiteItemChapterChangeEvent --> w_fitMannerWords
    e_SiteItemChapterChangeEvent --> w_measureWorkSizes
    e_SiteItemChapterChangeEvent --> w_writeListIndex
    e_SiteItemChapterVectorChangeEvent --> w_fitFieldModels
    e_SiteItemMarkChangeEvent --> w_fitGradeModels
    e_SiteItemPictureAnalysisEvent --> w_fitGradeModels
    e_SiteItemPictureAnalysisEvent --> w_writeListIndex
    e_SiteItemPictureDownloadEvent --> w_analyseSiteItemPictures
    e_SiteItemRemoveEvent --> e_WorkChangeEvent
    e_SiteItemRemoveEvent --> w_fitGradeModels
    e_SiteItemRemoveEvent --> w_makeGroupManners
    e_SiteItemRemoveEvent --> w_removeSiteItemTexts
    e_SiteItemRemoveEvent --> w_writeListIndex
    e_SiteItemSetVectorChangeEvent --> w_fitGradeModels
    e_SiteItemTextVectorChangeEvent --> w_fitFieldModels
    e_SiteItemWriteEvent --> e_SiteFandomBindingChangeEvent
    e_SiteItemWriteEvent --> e_SiteTagBindingChangeEvent
    e_SiteItemWriteEvent --> e_WorkChangeEvent
    e_SiteItemWriteEvent --> e_WorkGroupChangeEvent
    e_SiteItemWriteEvent --> w_countFandomWorks
    e_SiteItemWriteEvent --> w_eraseDifferentInSeries
    e_SiteItemWriteEvent --> w_keyNamedValues
    e_SiteItemWriteEvent --> w_letGoUnusedFandoms
    e_SiteItemWriteEvent --> w_makeGroupManners
    e_SiteItemWriteEvent --> w_measureWorkSizes
    e_SiteItemWriteEvent --> w_renameFandoms
    e_SiteTagBindingChangeEvent --> w_relateTagsBySites
    e_SiteTagParentChangeEvent --> w_relateTagsBySites
    e_SiteTagSynonymChangeEvent --> w_encodeTagNames
    e_SiteTagSynonymChangeEvent --> w_nameTags
    e_SiteTagSynonymChangeEvent --> w_relateTagsBySites
    e_TagChangeEvent --> w_encodeTagNames
    e_TagChangeEvent --> w_fitGradeModels
    e_TagChangeEvent --> w_measureWorkSizes
    e_TagChangeEvent --> w_writeListIndex
    e_TagTextAnswerEvent --> w_fitTagTextModel
    e_UniverseChangeEvent --> w_fitFieldModels
    e_UniverseChangeEvent --> w_nameFandoms
    e_UniverseChangeEvent --> w_writeListIndex
    e_WorkCastNameFillEvent --> w_fillWorkFields
    e_WorkChangeEvent --> w_encodeFandomNames
    e_WorkChangeEvent --> w_encodeTagNames
    e_WorkChangeEvent --> w_fitFieldModels
    e_WorkChangeEvent --> w_nameFandoms
    e_WorkChangeEvent --> w_nameTags
    e_WorkChangeEvent --> w_writeListIndex
    e_WorkChapterWindowFillEvent --> w_makeSiteItemSetVectors
    e_WorkDocumentWriteEvent --> w_encodeSiteItemCasts
    e_WorkDocumentWriteEvent --> w_encodeSiteItemChapters
    e_WorkDocumentWriteEvent --> w_encodeSiteItemTexts
    e_WorkDocumentWriteEvent --> w_fillWorkFields
    e_WorkDocumentWriteEvent --> w_fitFullTextCentre
    e_WorkDocumentWriteEvent --> w_fitPieceProjection
    e_WorkDocumentWriteEvent --> w_indexWorkTexts
    e_WorkDocumentWriteEvent --> w_makeSiteItemSetVectors
    e_WorkDocumentWriteEvent --> w_makeWorkManners
    e_WorkDocumentWriteEvent --> w_predictWorks
    e_WorkFieldChangeEvent --> w_fitFieldModels
    e_WorkFieldChangeEvent --> w_fitGradeModels
    e_WorkFieldChangeEvent --> w_measureWorkSizes
    e_WorkFieldChangeEvent --> w_writeListIndex
    e_WorkFieldFillEvent --> w_predictWorks
    e_WorkGradeChangeEvent --> w_fitGradeModels
    e_WorkGradeChangeEvent --> w_nameTags
    e_WorkGradeChangeEvent --> w_writeListIndex
    e_WorkGroupChangeEvent --> w_eraseDifferentInSeries
    e_WorkGroupChangeEvent --> w_makeGroupManners
    e_WorkGroupChangeEvent --> w_writeListIndex
    e_WorkInterestChangeEvent --> w_fitGradeModels
    e_WorkInterestChangeEvent --> w_writeListIndex
    e_WorkMannerChangeEvent --> w_fitGradeModels
    e_WorkMannerFillEvent --> w_predictWorks
    e_WorkSetVectorFillEvent --> w_fillWorkFields
    e_WorkSetVectorFillEvent --> w_predictWorks
    e_WorkTextVectorFillEvent --> w_fillWorkFields
    e_WorkTextVectorFillEvent --> w_predictWorks
    w_analyseSiteItemPictures --> e_SiteItemPictureAnalysisEvent
    w_compareGradeScorers --> w_readScorerInput
    w_compareInterestScorers --> w_readScorerInput
    w_encodeFandomNames --> e_FandomVectorChangeEvent
    w_encodeSiteItemCasts --> e_SiteItemCastEncodeEvent
    w_encodeSiteItemCasts --> e_WorkCastNameFillEvent
    w_encodeSiteItemChapters --> e_SiteItemChapterVectorChangeEvent
    w_encodeSiteItemChapters --> e_WorkChapterWindowFillEvent
    w_encodeSiteItemTexts --> e_SiteItemTextVectorChangeEvent
    w_encodeSiteItemTexts --> e_WorkTextVectorFillEvent
    w_fillWorkFields --> e_RatedWorkValueFillEvent
    w_fillWorkFields --> e_WorkFieldFillEvent
    w_fitFieldModels --> e_FieldModelFitEvent
    w_fitFullTextCentre --> e_FullTextCentreFitEvent
    w_fitGradeModels --> w_predictWorks
    w_fitMannerWords --> w_makeWorkManners
    w_fitPieceProjection --> e_PieceProjectionFitEvent
    w_fitSearchWeights --> w_fitFullTextCentre
    w_keepOutWorks --> e_SiteItemRemoveEvent
    w_makeGroupManners --> e_WorkMannerChangeEvent
    w_makeSiteItemSetVectors --> e_SiteItemSetVectorChangeEvent
    w_makeSiteItemSetVectors --> e_WorkSetVectorFillEvent
    w_makeSiteItemSetVectors --> w_fitSetDirections
    w_makeWorkManners --> e_WorkMannerChangeEvent
    w_makeWorkManners --> e_WorkMannerFillEvent
    w_nameTags --> w_countTags
    w_relateTagsBySites --> e_TagChangeEvent
    w_relateTagsBySites --> e_WorkChangeEvent
    w_relateTagsBySites --> w_nameTags
    w_searchFieldModelAnew --> w_fitFieldModels
    w_writeListIndex --> e_WorkDocumentWriteEvent
```

## The requests

| Request | Events | Works |
|---|---|---|
| `addUniverse` | UniverseChangeEvent | — |
| `answerAuthors` | WorkGroupChangeEvent | — |
| `answerDuplicate` | WorkGradeChangeEvent, WorkGroupChangeEvent, WorkInterestChangeEvent | fitDuplicateModel |
| `answerMarking` | WorkFieldChangeEvent | — |
| `answerTagByText` | TagTextAnswerEvent, WorkFieldChangeEvent | — |
| `bindSiteFandom` | FandomRelationChangeEvent, UniverseChangeEvent | renameFandoms |
| `bindSiteTag` | TagChangeEvent, WorkChangeEvent | nameTags |
| `bindTagEntry` | TagChangeEvent, WorkChangeEvent | — |
| `bindUniverseEntry` | UniverseChangeEvent | — |
| `cancelJob` | SiteFandomParentChangeEvent, SiteFandomSynonymChangeEvent, SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteSeriesPageChangeEvent, SiteTagParentChangeEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `capturePage` | SiteFandomParentChangeEvent, SiteFandomSynonymChangeEvent, SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteSeriesPageChangeEvent, SiteTagParentChangeEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `chooseFacetCast` | — | searchFieldModelAnew |
| `chooseFacetMethod` | — | searchFieldModelAnew |
| `chooseInterestScorer` | — | fitGradeModels |
| `choosePairMethod` | — | fitDuplicateModel, searchDuplicates |
| `chooseScorer` | — | fitGradeModels |
| `chooseUniverseCharacters` | UniverseChangeEvent | — |
| `chooseUniverseClass` | UniverseChangeEvent | — |
| `chooseUniverseEntry` | UniverseChangeEvent | — |
| `completeFacet` | WorkFieldChangeEvent | — |
| `correctFacet` | TagChangeEvent, WorkChangeEvent, WorkFieldChangeEvent | nameTags |
| `correctField` | SiteItemTextVectorChangeEvent, WorkChangeEvent | fitGradeModels, writeWorkValues |
| `createTagAbove` | TagChangeEvent, WorkChangeEvent | nameTags |
| `fitFandomLikeness` | — | fitFandomLikeness |
| `fitPairModels` | — | fitDuplicateModel |
| `getExplanation` | — | fitHeldOutModel |
| `getHeldOut` | — | fitHeldOutModel |
| `getItem` | SiteItemTextVectorChangeEvent | writeWorkValues |
| `getPage` | SiteItemTextVectorChangeEvent | writeWorkValues |
| `getPicture` | SiteFandomParentChangeEvent, SiteFandomSynonymChangeEvent, SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteSeriesPageChangeEvent, SiteTagParentChangeEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `getSearchQuality` | — | fitFullTextCentre |
| `giveMatchFeedback` | SiteItemMarkChangeEvent | — |
| `ignoreTag` | TagChangeEvent | — |
| `judgeSearch` | SearchJudgmentEvent | — |
| `keepOutTag` | — | keepOutWorks |
| `linkItem` | SiteItemTextVectorChangeEvent, WorkChangeEvent, WorkGradeChangeEvent, WorkInterestChangeEvent | fitGradeModels, writeWorkValues |
| `listHierarchyOffers` | — | nameTags |
| `listItems` | — | fitFullTextCentre |
| `listJobs` | SiteFandomParentChangeEvent, SiteFandomSynonymChangeEvent, SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteSeriesPageChangeEvent, SiteTagParentChangeEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `markInterest` | WorkInterestChangeEvent | — |
| `markPicture` | SiteItemMarkChangeEvent | — |
| `markReview` | SiteItemMarkChangeEvent | — |
| `measurePartWorth` | — | measurePartWorth |
| `measureWeighing` | — | weighParts |
| `nameTag` | TagChangeEvent, WorkChangeEvent | — |
| `nameUniverse` | FandomRelationChangeEvent, UniverseChangeEvent | renameFandoms |
| `nameUniverseEntry` | UniverseChangeEvent | — |
| `rateItem` | WorkGradeChangeEvent | — |
| `refreshItem` | SiteFandomParentChangeEvent, SiteFandomSynonymChangeEvent, SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemTextVectorChangeEvent, SiteItemWriteEvent, SiteSeriesPageChangeEvent, SiteTagParentChangeEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | writeWorkValues |
| `refreshTagEntries` | TagChangeEvent, WorkChangeEvent | — |
| `refreshUniverse` | UniverseChangeEvent | — |
| `relateTags` | TagChangeEvent, WorkChangeEvent | nameTags |
| `relateUniverses` | FandomRelationChangeEvent, UniverseChangeEvent, WorkChangeEvent | renameFandoms |
| `removeItem` | SiteItemRemoveEvent | — |
| `resetUniverseClass` | UniverseChangeEvent | — |
| `resetUniverseEntry` | UniverseChangeEvent | — |
| `searchAuthorDuplicates` | — | searchAuthorDuplicates |
| `searchDuplicates` | — | searchDuplicates |
| `searchFacetModel` | — | searchFieldModelAnew |
| `startFacetComparison` | — | compareFieldMethods |
| `startFandomWayComparison` | — | compareFandomWays |
| `startJob` | SiteFandomParentChangeEvent, SiteFandomSynonymChangeEvent, SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteSeriesPageChangeEvent, SiteTagParentChangeEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `startScorerComparison` | — | compareGradeScorers, compareInterestScorers |
| `takeOutOfSeries` | WorkGroupChangeEvent | — |
| `takeOutSiteAuthor` | WorkGroupChangeEvent | — |
| `trainModel` | — | fitGradeModels |
| `unanswerDuplicate` | WorkGroupChangeEvent | fitDuplicateModel |
| `unanswerMarking` | WorkFieldChangeEvent | — |
| `uncompleteFacet` | WorkFieldChangeEvent | — |
| `uncorrectFacet` | WorkFieldChangeEvent | — |
| `uncorrectField` | SiteItemTextVectorChangeEvent, WorkChangeEvent | fitGradeModels, writeWorkValues |
| `undoDuplicateWord` | WorkGradeChangeEvent, WorkGroupChangeEvent, WorkInterestChangeEvent | fitDuplicateModel |
| `unlinkItem` | SiteItemTextVectorChangeEvent, WorkChangeEvent | fitGradeModels, writeWorkValues |
| `unmarkInterest` | WorkInterestChangeEvent | — |
| `unmarkPicture` | SiteItemMarkChangeEvent | — |
| `unmarkReview` | SiteItemMarkChangeEvent | — |
| `unrateItem` | WorkGradeChangeEvent | — |
