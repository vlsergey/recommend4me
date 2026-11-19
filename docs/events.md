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
    e_RatedWorkValueFillEvent["RatedWorkValueFillEvent"]
    e_SearchJudgmentEvent["SearchJudgmentEvent"]
    e_SiteItemCastEncodeEvent["SiteItemCastEncodeEvent"]
    e_SiteItemChapterChangeEvent["SiteItemChapterChangeEvent"]
    e_SiteItemMarkChangeEvent["SiteItemMarkChangeEvent"]
    e_SiteItemPictureAnalysisEvent["SiteItemPictureAnalysisEvent"]
    e_SiteItemPictureDownloadEvent["SiteItemPictureDownloadEvent"]
    e_SiteItemPictureListEvent["SiteItemPictureListEvent"]
    e_SiteItemRemoveEvent["SiteItemRemoveEvent"]
    e_SiteItemSetVectorChangeEvent["SiteItemSetVectorChangeEvent"]
    e_SiteItemTextVectorChangeEvent["SiteItemTextVectorChangeEvent"]
    e_SiteItemWriteEvent["SiteItemWriteEvent"]
    e_SiteTagSynonymChangeEvent["SiteTagSynonymChangeEvent"]
    e_TagChangeEvent["TagChangeEvent"]
    e_TagTextAnswerEvent["TagTextAnswerEvent"]
    e_WorkChangeEvent["WorkChangeEvent"]
    e_WorkFieldChangeEvent["WorkFieldChangeEvent"]
    e_WorkGradeChangeEvent["WorkGradeChangeEvent"]
    e_WorkGroupChangeEvent["WorkGroupChangeEvent"]
    e_WorkInterestChangeEvent["WorkInterestChangeEvent"]
    e_WorkMannerChangeEvent["WorkMannerChangeEvent"]
    e_WorkPredictionChangeEvent["WorkPredictionChangeEvent"]
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
    o_job__ --> e_SiteItemChapterChangeEvent
    o_job__ --> e_SiteItemPictureListEvent
    o_job__ --> e_SiteItemRemoveEvent
    o_job__ --> e_SiteItemWriteEvent
    o_job__ --> e_SiteTagSynonymChangeEvent
    o_job__ --> e_WorkGroupChangeEvent
    o_job__ --> w_score
    o_job__ --> w_textVectors
    o_job__ --> w_train
    o_pictures --> e_SiteItemChapterChangeEvent
    o_pictures --> e_SiteItemPictureDownloadEvent
    o_pictures --> e_SiteItemPictureListEvent
    o_pictures --> e_SiteItemRemoveEvent
    o_pictures --> e_SiteItemWriteEvent
    o_pictures --> e_SiteTagSynonymChangeEvent
    o_pictures --> e_WorkGroupChangeEvent
    o_reparse --> e_SiteItemChapterChangeEvent
    o_reparse --> e_SiteItemPictureListEvent
    o_reparse --> e_SiteItemRemoveEvent
    o_reparse --> e_SiteItemWriteEvent
    o_reparse --> e_SiteTagSynonymChangeEvent
    o_reparse --> e_WorkGroupChangeEvent
    o_start --> e_SiteItemChapterChangeEvent
    o_start --> e_SiteItemPictureListEvent
    o_start --> e_SiteItemRemoveEvent
    o_start --> e_SiteItemWriteEvent
    o_start --> e_SiteTagSynonymChangeEvent
    o_start --> e_WorkGroupChangeEvent
    o_start --> w_duplicates
    o_start --> w_fandomSiteWorksChanged
    o_start --> w_listIndex
    o_start --> w_listValues
    o_start --> w_pictureVectors
    o_start --> w_searchUpdate
    o_start --> w_setVectors
    o_start --> w_tagTextModel
    o_start --> w_train
    o_universes_languages --> e_WorkChangeEvent
    e_FandomRelationChangeEvent --> w_fandomLikeness
    e_FandomVectorChangeEvent --> w_fandomLikeness
    e_FieldModelFitEvent --> w_listValues
    e_RatedWorkValueFillEvent --> w_train
    e_SearchJudgmentEvent --> w_searchWeights
    e_SiteItemCastEncodeEvent --> w_listIndex
    e_SiteItemCastEncodeEvent --> w_suggesters
    e_SiteItemChapterChangeEvent --> w_manners
    e_SiteItemChapterChangeEvent --> w_partVectors
    e_SiteItemChapterChangeEvent --> w_suggesters
    e_SiteItemChapterChangeEvent --> w_workSizes
    e_SiteItemMarkChangeEvent --> w_train
    e_SiteItemPictureAnalysisEvent --> w_setVectors
    e_SiteItemPictureAnalysisEvent --> w_train
    e_SiteItemPictureDownloadEvent --> w_pictureVectors
    e_SiteItemRemoveEvent --> e_WorkChangeEvent
    e_SiteItemRemoveEvent --> w_train
    e_SiteItemSetVectorChangeEvent --> w_train
    e_SiteItemTextVectorChangeEvent --> w_listIndex
    e_SiteItemTextVectorChangeEvent --> w_searchUpdate
    e_SiteItemTextVectorChangeEvent --> w_setVectors
    e_SiteItemTextVectorChangeEvent --> w_suggesters
    e_SiteItemWriteEvent --> e_WorkChangeEvent
    e_SiteItemWriteEvent --> e_WorkGroupChangeEvent
    e_SiteItemWriteEvent --> w_castNames
    e_SiteItemWriteEvent --> w_fandomRename
    e_SiteItemWriteEvent --> w_fandomSiteWorksChanged
    e_SiteItemWriteEvent --> w_fandomsLetGo
    e_SiteItemWriteEvent --> w_manners
    e_SiteItemWriteEvent --> w_setVectors
    e_SiteItemWriteEvent --> w_textVectors
    e_SiteItemWriteEvent --> w_workSizes
    e_SiteTagSynonymChangeEvent --> w_tagVectors
    e_SiteTagSynonymChangeEvent --> w_tagsNamed
    e_TagChangeEvent --> w_tagVectors
    e_TagChangeEvent --> w_train
    e_TagChangeEvent --> w_workSizes
    e_TagTextAnswerEvent --> w_tagTextModel
    e_WorkChangeEvent --> w_fandomNames
    e_WorkChangeEvent --> w_fandomVectors
    e_WorkChangeEvent --> w_layerDrafts
    e_WorkChangeEvent --> w_listIndex
    e_WorkChangeEvent --> w_searchUpdate
    e_WorkChangeEvent --> w_suggesters
    e_WorkChangeEvent --> w_tagVectors
    e_WorkChangeEvent --> w_tagsNamed
    e_WorkFieldChangeEvent --> w_listIndex
    e_WorkFieldChangeEvent --> w_searchUpdate
    e_WorkFieldChangeEvent --> w_suggesters
    e_WorkFieldChangeEvent --> w_train
    e_WorkFieldChangeEvent --> w_workSizes
    e_WorkGradeChangeEvent --> w_listIndex
    e_WorkGradeChangeEvent --> w_tagsNamed
    e_WorkGradeChangeEvent --> w_train
    e_WorkGroupChangeEvent --> w_listIndex
    e_WorkGroupChangeEvent --> w_manners
    e_WorkInterestChangeEvent --> w_listIndex
    e_WorkInterestChangeEvent --> w_train
    e_WorkMannerChangeEvent --> w_train
    e_WorkPredictionChangeEvent --> w_listIndex
    w_castNames --> e_SiteItemCastEncodeEvent
    w_fandomVectors --> e_FandomVectorChangeEvent
    w_interestScorersCompared --> w_scorersInput
    w_listValues --> e_RatedWorkValueFillEvent
    w_manners --> e_WorkMannerChangeEvent
    w_partVectors --> e_SiteItemTextVectorChangeEvent
    w_pictureVectors --> e_SiteItemPictureAnalysisEvent
    w_score --> e_WorkPredictionChangeEvent
    w_scorersCompared --> w_scorersInput
    w_setVectors --> e_SiteItemSetVectorChangeEvent
    w_setVectors --> w_setDirections
    w_suggesterAnew --> w_suggesters
    w_suggesters --> e_FieldModelFitEvent
    w_tagsNamed --> w_tagsCounted
    w_textVectors --> e_SiteItemTextVectorChangeEvent
    w_train --> e_WorkPredictionChangeEvent
    w_train --> w_score
    w_worksKeptOut --> e_SiteItemRemoveEvent
```

## The requests

| Request | Events | Works |
|---|---|---|
| `addUniverse` | WorkChangeEvent | — |
| `answerAuthors` | WorkGroupChangeEvent | — |
| `answerDuplicate` | WorkGradeChangeEvent, WorkInterestChangeEvent | duplicatesModel |
| `answerMarking` | WorkFieldChangeEvent | — |
| `answerTagByText` | TagTextAnswerEvent, WorkFieldChangeEvent | — |
| `bindSiteFandom` | FandomRelationChangeEvent, WorkChangeEvent | fandomRename |
| `bindSiteTag` | TagChangeEvent, WorkChangeEvent | tagsNamed |
| `bindTagEntry` | TagChangeEvent, WorkChangeEvent | — |
| `bindUniverseEntry` | WorkChangeEvent | — |
| `cancelJob` | SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `capturePage` | SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `chooseFacetCast` | — | suggesterAnew |
| `chooseFacetMethod` | — | suggesterAnew |
| `chooseInterestScorer` | — | train |
| `choosePairMethod` | — | duplicates |
| `chooseScorer` | — | train |
| `chooseUniverseCharacters` | WorkChangeEvent | — |
| `chooseUniverseClass` | WorkChangeEvent | — |
| `chooseUniverseEntry` | WorkChangeEvent | — |
| `completeFacet` | WorkFieldChangeEvent | — |
| `correctFacet` | TagChangeEvent, WorkChangeEvent, WorkFieldChangeEvent | tagsNamed |
| `correctField` | WorkChangeEvent | train |
| `createTagAbove` | TagChangeEvent, WorkChangeEvent | tagsNamed |
| `fitPairModels` | — | duplicatesModel |
| `getExplanation` | — | heldOut |
| `getHeldOut` | — | heldOut |
| `getPicture` | SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `giveMatchFeedback` | SiteItemMarkChangeEvent | — |
| `ignoreTag` | TagChangeEvent | — |
| `judgeSearch` | SearchJudgmentEvent | — |
| `keepOutTag` | — | worksKeptOut |
| `linkItem` | WorkChangeEvent, WorkGradeChangeEvent, WorkInterestChangeEvent | train |
| `listHierarchyOffers` | — | tagsNamed |
| `listJobs` | SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `markInterest` | WorkInterestChangeEvent | — |
| `markPicture` | SiteItemMarkChangeEvent | — |
| `markReview` | SiteItemMarkChangeEvent | — |
| `measurePartWorth` | — | worth |
| `measureWeighing` | — | weighing |
| `nameTag` | TagChangeEvent, WorkChangeEvent | — |
| `nameUniverse` | FandomRelationChangeEvent, WorkChangeEvent | fandomRename |
| `nameUniverseEntry` | WorkChangeEvent | — |
| `rateItem` | WorkGradeChangeEvent | — |
| `refreshItem` | SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent, WorkPredictionChangeEvent | — |
| `refreshTagEntries` | TagChangeEvent, WorkChangeEvent | — |
| `refreshUniverse` | WorkChangeEvent | — |
| `relateTags` | TagChangeEvent, WorkChangeEvent | tagsNamed |
| `relateUniverses` | FandomRelationChangeEvent, WorkChangeEvent | fandomRename |
| `removeItem` | SiteItemRemoveEvent | — |
| `resetUniverseClass` | WorkChangeEvent | — |
| `resetUniverseEntry` | WorkChangeEvent | — |
| `searchDuplicates` | — | duplicates |
| `searchFacetModel` | — | suggesterAnew |
| `startFacetComparison` | — | methodsCompared |
| `startJob` | SiteItemChapterChangeEvent, SiteItemPictureListEvent, SiteItemRemoveEvent, SiteItemWriteEvent, SiteTagSynonymChangeEvent, WorkGroupChangeEvent | — |
| `startScorerComparison` | — | interestScorersCompared, scorersCompared |
| `takeOutSiteAuthor` | WorkGroupChangeEvent | — |
| `trainModel` | — | train |
| `unanswerDuplicate` | — | duplicatesModel |
| `unanswerMarking` | WorkFieldChangeEvent | — |
| `uncompleteFacet` | WorkFieldChangeEvent | — |
| `uncorrectFacet` | WorkFieldChangeEvent | — |
| `uncorrectField` | WorkChangeEvent | train |
| `undoDuplicateWord` | WorkGradeChangeEvent, WorkInterestChangeEvent | duplicatesModel |
| `unlinkItem` | WorkChangeEvent | train |
| `unmarkInterest` | WorkInterestChangeEvent | — |
| `unmarkPicture` | SiteItemMarkChangeEvent | — |
| `unmarkReview` | SiteItemMarkChangeEvent | — |
| `unrateItem` | WorkGradeChangeEvent | — |
