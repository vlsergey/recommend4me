# The computations of the models and the works' values: requirements and where they come from

Every requirement says its source: **the owner** (with the date and the words, translated) or **ours** — a choice
made while building, not agreed with the owner. A choice of ours stays open until the owner agrees, changes or drops
it. A new requirement is added here when it is given. The queue that runs them is described in `AGENTS.md`
("A request never waits for a computation").

## The owner's

| # | Requirement | Source |
|---|---|---|
| 1 | The making of the models and the setting of the works' values and predictions go in four stages: (1) the models of the normalized fields are made; (2) the normalized values — the given and the computed together — are written anew of every work rated (graded or marked of interest); (3) the models of the grade and of the interest are made; (4) in the lowest priority, the works not rated are taken a thousand at a time, their computed values and their predictions of the grade and of the interest made anew. | 2026-11-19: "split it into 4 stages. At the first we build the models computing the normalized fields. At the second we write the updated normalized values (the given plus the computed) of all the rated works (by a grade or by interest). At the third we build the model of the grades and of interest. At the fourth, in low priority, we start taking 1k unrated works at a time and updating both the computed values and the prediction of the grade and interest for them" |
| 2 | The works not rated are taken in the order of their predicted grade, the highest first — the prediction of the last pass or of this one, whichever. | 2026-11-19: "the order: by the grade descending (of the last iteration or now — not that important)" |
| 3 | The values of the works are written to Lucene alone; making the models and filling the values are two works, the filling lowest. | `docs/work-list.md`, 5 and 6 |
| 5 | The model of a normalized field learns of the user's word alone — the values the user said a work has or has not, and of a work the user said all named every other value not. What a site says is no learning of it: a hint to the user marking the field, and a source of the work's values while the user said nothing of them. | 2026-11-19: "the models of the fields must learn on the marked-up works only; the models cannot learn on the unmarked"; "the site does not train the model! There is no sense in training by the site. That the site says something is a reason to give additional hints for the marking, but it is not data for training. You may use it when filling the works' fields as one of the sources of information (if the user said nothing), but not for training" |
| 6 | What a site says of a work is an input of the model, what the user says its output: the model learns of the user's word how far the site is right. The drafts of the dictionary (a universe of a fandom) are inputs too, as the site's. | 2026-11-19: "the site's markup is the input, the user's markup the output. Putting the input to the output would be wrong" |
| 7 | The values a field's model offers are those of the dictionary of the type and of the works it learns of — not those every work of the source happens to have: no walk over every work to count them. The cutoff of a calibrated field is worked out of the works rated and those the model learns of, not of every work. | 2026-11-19, of these two and of the order of the work ("first the labels of the user's word alone, then these, then the four stages"): "do it" |
| 4 | A new way of fitting is a plugin of its own, chosen by the application's defaults and the instance's settings; an improvement that works always is made unconditionally. | 2026-11-19: "I hope you make all of this as different plugins and simply change the defaults of the application, and the settings chosen in my instance. Unless it is an improvement that always works: those can be turned on unconditionally" |

## Ours, not agreed

| # | Choice | Where |
|---|---|---|
| a | The stages follow each other by themselves: the end of the first of a source plans the second of its type, the second the third, the third the fourth; what starts the first stays as it is (the user's answers, the sites' data). | — |
| b | A grade or a mark of interest of a work with no other change starts at the second stage, of that work alone, then the third. | — |
| c | A fourth stage begun anew — a model of the grades made again — leaves the one under way: it begins from the highest predicted work again. The works done in a pass are marked in their documents with the pass, the next thousand asked of those not marked: the order of the predictions written meanwhile does not move the pass. | — |
| d | The average work the model of the grades reads is counted along the fourth stage and the second, of every work, kept at the end of the fourth for the next third. | — |
| i | Of the pairings the values are still counted of every work: a pairing is a pair of a work's characters, of no dictionary. | `FacetFitting` |
| e | The fourth stage replaces the sweep of the scores (`ScoreSweep`) and the filling of the values (`ListFilling`); the third reads the values of the rated works from the index of the list, not by the models' chain. | — |
