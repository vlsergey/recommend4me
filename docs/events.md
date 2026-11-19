# The events and the works of the application

Made of the compiled code by `EventFlowDocsTest` (`gradlew :app:test --tests "*EventFlowDocsTest*"`): a change
of the code that changes what leads to what fails the test, and the test writes this file anew — commit it.
What it reads is said in `EventFlow`: an event is an object of a class some `@EventListener` listens to; a work
is planned by `Workload.plan` or run by `Workload.run`, its kind named where it is planned.

An arrow is "may lead to, directly": the events a thing makes, the works it plans. A request's events and
works go on as the diagram shows.

## Legend

```mermaid
flowchart LR
    outside(("from outside: the start, a loop of the background")) --> event["an event of the application"]
    event --> work{{"a work of the queue, by its kind"}}
    work --> event
```

- a circle: what comes from outside the flow — `start`, the start of the application (the listeners of
  `ApplicationReadyEvent`) deciding of the data; `left by the last run`, the works the last run left in the
  queue planned again by their owners (`Workload.resume`); a loop of the background by its name
  (`Background.start`, `every`, `endless`);
- a box: an event of the application, by its class;
- a hexagon: a work of the queue, by its `WorkKind`;
- a request of the interface or the extension: a row of the table below, by its operation's id.

## What leads to what

```mermaid
flowchart LR

    o_job__(("job-*"))
    o_pictures(("pictures"))
    o_reparse(("reparse"))
    o_start(("start"))
    o_universes_languages(("universes-languages"))
    e_CastNamesEncoded["CastNamesEncoded"]
    e_FacetAnswered["FacetAnswered"]
    e_FacetModelsFitted["FacetModelsFitted"]
    e_FandomVectorsMade["FandomVectorsMade"]
    e_GradesChanged["GradesChanged"]
    e_InterestChanged["InterestChanged"]
    e_ItemsChanged["ItemsChanged"]
    e_MannersChanged["MannersChanged"]
    e_MarksChanged["MarksChanged"]
    e_PartsSaved["PartsSaved"]
    e_PicturesAnalysed["PicturesAnalysed"]
    e_PicturesDownloaded["PicturesDownloaded"]
    e_PicturesListed["PicturesListed"]
    e_PredictionsChanged["PredictionsChanged"]
    e_RatedValuesFilled["RatedValuesFilled"]
    e_SearchJudged["SearchJudged"]
    e_SetVectorsChanged["SetVectorsChanged"]
    e_SiteItemsWritten["SiteItemsWritten"]
    e_SynonymsSet["SynonymsSet"]
    e_TagTextAnswered["TagTextAnswered"]
    e_TagsChanged["TagsChanged"]
    e_TextVectorsChanged["TextVectorsChanged"]
    e_UniversesRelated["UniversesRelated"]
    e_WorksRegrouped["WorksRegrouped"]
    w_castNames{{"castNames"}}
    w_duplicates{{"duplicates"}}
    w_duplicatesModel{{"duplicatesModel"}}
    w_fandomLikeness{{"fandomLikeness"}}
    w_fandomNames{{"fandomNames"}}
    w_fandomRename{{"fandomRename"}}
    w_fandomSiteWorksChanged{{"fandomSiteWorksChanged"}}
    w_fandomVectors{{"fandomVectors"}}
    w_fandomsLetGo{{"fandomsLetGo"}}
    w_heldOut{{"heldOut"}}
    w_interestScorersCompared{{"interestScorersCompared"}}
    w_layerDrafts{{"layerDrafts"}}
    w_listIndex{{"listIndex"}}
    w_listValues{{"listValues"}}
    w_manners{{"manners"}}
    w_methodsCompared{{"methodsCompared"}}
    w_partVectors{{"partVectors"}}
    w_pictureVectors{{"pictureVectors"}}
    w_score{{"score"}}
    w_scorersCompared{{"scorersCompared"}}
    w_scorersInput{{"scorersInput"}}
    w_searchUpdate{{"searchUpdate"}}
    w_searchWeights{{"searchWeights"}}
    w_setDirections{{"setDirections"}}
    w_setVectors{{"setVectors"}}
    w_suggesterAnew{{"suggesterAnew"}}
    w_suggesters{{"suggesters"}}
    w_tagTextModel{{"tagTextModel"}}
    w_tagVectors{{"tagVectors"}}
    w_tagsCounted{{"tagsCounted"}}
    w_tagsNamed{{"tagsNamed"}}
    w_textVectors{{"textVectors"}}
    w_train{{"train"}}
    w_weighing{{"weighing"}}
    w_workSizes{{"workSizes"}}
    w_worksKeptOut{{"worksKeptOut"}}
    w_worth{{"worth"}}
    o_job__ --> e_GradesChanged
    o_job__ --> e_ItemsChanged
    o_job__ --> e_PartsSaved
    o_job__ --> e_PicturesListed
    o_job__ --> e_SiteItemsWritten
    o_job__ --> e_SynonymsSet
    o_job__ --> e_WorksRegrouped
    o_job__ --> w_score
    o_job__ --> w_textVectors
    o_job__ --> w_train
    o_pictures --> e_GradesChanged
    o_pictures --> e_ItemsChanged
    o_pictures --> e_PartsSaved
    o_pictures --> e_PicturesDownloaded
    o_pictures --> e_PicturesListed
    o_pictures --> e_SiteItemsWritten
    o_pictures --> e_SynonymsSet
    o_pictures --> e_WorksRegrouped
    o_pictures --> w_train
    o_reparse --> e_GradesChanged
    o_reparse --> e_ItemsChanged
    o_reparse --> e_PartsSaved
    o_reparse --> e_PicturesListed
    o_reparse --> e_SiteItemsWritten
    o_reparse --> e_SynonymsSet
    o_reparse --> e_WorksRegrouped
    o_reparse --> w_train
    o_start --> e_GradesChanged
    o_start --> e_ItemsChanged
    o_start --> e_PartsSaved
    o_start --> e_PicturesListed
    o_start --> e_SiteItemsWritten
    o_start --> e_SynonymsSet
    o_start --> e_WorksRegrouped
    o_start --> w_duplicates
    o_start --> w_fandomSiteWorksChanged
    o_start --> w_listIndex
    o_start --> w_listValues
    o_start --> w_pictureVectors
    o_start --> w_searchUpdate
    o_start --> w_setVectors
    o_start --> w_tagTextModel
    o_start --> w_train
    o_universes_languages --> e_ItemsChanged
    e_CastNamesEncoded --> w_listIndex
    e_CastNamesEncoded --> w_suggesters
    e_FacetAnswered --> w_listIndex
    e_FacetAnswered --> w_searchUpdate
    e_FacetAnswered --> w_suggesters
    e_FacetAnswered --> w_train
    e_FacetAnswered --> w_workSizes
    e_FacetModelsFitted --> w_listValues
    e_FandomVectorsMade --> w_fandomLikeness
    e_GradesChanged --> w_listIndex
    e_GradesChanged --> w_tagsNamed
    e_GradesChanged --> w_train
    e_InterestChanged --> w_listIndex
    e_InterestChanged --> w_train
    e_ItemsChanged --> w_fandomNames
    e_ItemsChanged --> w_fandomVectors
    e_ItemsChanged --> w_layerDrafts
    e_ItemsChanged --> w_listIndex
    e_ItemsChanged --> w_searchUpdate
    e_ItemsChanged --> w_suggesters
    e_ItemsChanged --> w_tagVectors
    e_ItemsChanged --> w_tagsNamed
    e_MannersChanged --> w_train
    e_MarksChanged --> w_train
    e_PartsSaved --> w_manners
    e_PartsSaved --> w_partVectors
    e_PartsSaved --> w_suggesters
    e_PartsSaved --> w_workSizes
    e_PicturesAnalysed --> w_setVectors
    e_PicturesAnalysed --> w_train
    e_PicturesDownloaded --> w_pictureVectors
    e_PredictionsChanged --> w_listIndex
    e_RatedValuesFilled --> w_train
    e_SearchJudged --> w_searchWeights
    e_SetVectorsChanged --> w_train
    e_SiteItemsWritten --> e_WorksRegrouped
    e_SiteItemsWritten --> w_castNames
    e_SiteItemsWritten --> w_fandomRename
    e_SiteItemsWritten --> w_fandomSiteWorksChanged
    e_SiteItemsWritten --> w_fandomsLetGo
    e_SiteItemsWritten --> w_manners
    e_SiteItemsWritten --> w_setVectors
    e_SiteItemsWritten --> w_textVectors
    e_SiteItemsWritten --> w_workSizes
    e_SynonymsSet --> w_tagVectors
    e_SynonymsSet --> w_tagsNamed
    e_TagTextAnswered --> w_tagTextModel
    e_TagsChanged --> w_tagVectors
    e_TagsChanged --> w_train
    e_TagsChanged --> w_workSizes
    e_TextVectorsChanged --> w_listIndex
    e_TextVectorsChanged --> w_searchUpdate
    e_TextVectorsChanged --> w_setVectors
    e_TextVectorsChanged --> w_suggesters
    e_UniversesRelated --> w_fandomLikeness
    e_WorksRegrouped --> w_listIndex
    e_WorksRegrouped --> w_manners
    w_castNames --> e_CastNamesEncoded
    w_fandomVectors --> e_FandomVectorsMade
    w_interestScorersCompared --> w_scorersInput
    w_listValues --> e_RatedValuesFilled
    w_manners --> e_MannersChanged
    w_partVectors --> e_TextVectorsChanged
    w_pictureVectors --> e_PicturesAnalysed
    w_score --> e_PredictionsChanged
    w_scorersCompared --> w_scorersInput
    w_setVectors --> e_SetVectorsChanged
    w_setVectors --> w_setDirections
    w_suggesterAnew --> w_suggesters
    w_suggesters --> e_FacetModelsFitted
    w_tagsNamed --> w_tagsCounted
    w_textVectors --> e_TextVectorsChanged
    w_train --> e_PredictionsChanged
    w_train --> w_score
    w_worksKeptOut --> e_GradesChanged
    w_worksKeptOut --> e_ItemsChanged
    w_worksKeptOut --> w_train
```

## The requests

| Request | Events | Works |
|---|---|---|
| `addUniverse` | ItemsChanged | — |
| `answerAuthors` | WorksRegrouped | — |
| `answerDuplicate` | GradesChanged, InterestChanged | duplicatesModel |
| `answerMarking` | FacetAnswered | — |
| `answerTagByText` | FacetAnswered, TagTextAnswered | — |
| `bindSiteFandom` | ItemsChanged, UniversesRelated | fandomRename |
| `bindSiteTag` | ItemsChanged, TagsChanged | tagsNamed |
| `bindTagEntry` | ItemsChanged, TagsChanged | — |
| `bindUniverseEntry` | ItemsChanged | — |
| `cancelJob` | GradesChanged, ItemsChanged, PartsSaved, PicturesListed, SiteItemsWritten, SynonymsSet, WorksRegrouped | train |
| `capturePage` | GradesChanged, ItemsChanged, PartsSaved, PicturesListed, SiteItemsWritten, SynonymsSet, WorksRegrouped | train |
| `chooseFacetCast` | — | suggesterAnew |
| `chooseFacetMethod` | — | suggesterAnew |
| `chooseInterestScorer` | — | train |
| `choosePairMethod` | — | duplicates |
| `chooseScorer` | — | train |
| `chooseUniverseCharacters` | ItemsChanged | — |
| `chooseUniverseClass` | ItemsChanged | — |
| `chooseUniverseEntry` | ItemsChanged | — |
| `completeFacet` | FacetAnswered | — |
| `correctFacet` | FacetAnswered, ItemsChanged, TagsChanged | tagsNamed |
| `correctField` | ItemsChanged | train |
| `createTagAbove` | ItemsChanged, TagsChanged | tagsNamed |
| `fitPairModels` | — | duplicatesModel |
| `getExplanation` | — | heldOut |
| `getHeldOut` | — | heldOut |
| `getPicture` | GradesChanged, ItemsChanged, PartsSaved, PicturesListed, SiteItemsWritten, SynonymsSet, WorksRegrouped | train |
| `giveMatchFeedback` | MarksChanged | — |
| `ignoreTag` | TagsChanged | — |
| `judgeSearch` | SearchJudged | — |
| `keepOutTag` | — | worksKeptOut |
| `linkItem` | GradesChanged, InterestChanged, ItemsChanged | train |
| `listHierarchyOffers` | — | tagsNamed |
| `listJobs` | GradesChanged, ItemsChanged, PartsSaved, PicturesListed, SiteItemsWritten, SynonymsSet, WorksRegrouped | train |
| `markInterest` | InterestChanged | — |
| `markPicture` | MarksChanged | — |
| `markReview` | MarksChanged | — |
| `measurePartWorth` | — | worth |
| `measureWeighing` | — | weighing |
| `nameTag` | ItemsChanged, TagsChanged | — |
| `nameUniverse` | ItemsChanged, UniversesRelated | fandomRename |
| `nameUniverseEntry` | ItemsChanged | — |
| `rateItem` | GradesChanged | — |
| `refreshItem` | GradesChanged, ItemsChanged, PartsSaved, PicturesListed, PredictionsChanged, SiteItemsWritten, SynonymsSet, WorksRegrouped | train |
| `refreshTagEntries` | ItemsChanged, TagsChanged | — |
| `refreshUniverse` | ItemsChanged | — |
| `relateTags` | ItemsChanged, TagsChanged | tagsNamed |
| `relateUniverses` | ItemsChanged, UniversesRelated | fandomRename |
| `removeItem` | GradesChanged, ItemsChanged | train |
| `resetUniverseClass` | ItemsChanged | — |
| `resetUniverseEntry` | ItemsChanged | — |
| `searchDuplicates` | — | duplicates |
| `searchFacetModel` | — | suggesterAnew |
| `startFacetComparison` | — | methodsCompared |
| `startJob` | GradesChanged, ItemsChanged, PartsSaved, PicturesListed, SiteItemsWritten, SynonymsSet, WorksRegrouped | train |
| `startScorerComparison` | — | interestScorersCompared, scorersCompared |
| `takeOutSiteAuthor` | WorksRegrouped | — |
| `trainModel` | — | train |
| `unanswerDuplicate` | — | duplicatesModel |
| `unanswerMarking` | FacetAnswered | — |
| `uncompleteFacet` | FacetAnswered | — |
| `uncorrectFacet` | FacetAnswered | — |
| `uncorrectField` | ItemsChanged | train |
| `undoDuplicateWord` | GradesChanged, InterestChanged | duplicatesModel |
| `unlinkItem` | ItemsChanged | train |
| `unmarkInterest` | InterestChanged | — |
| `unmarkPicture` | MarksChanged | — |
| `unmarkReview` | MarksChanged | — |
| `unrateItem` | GradesChanged | — |
