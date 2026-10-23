/*
 * THE EXTENSION'S OWN WORDS, in English, Russian and Serbian (Latin script), shared by the content
 * script (page.js), the popup, the options page and the userscript for Safari, which the
 * application puts together of these files.
 *
 * Keys are flat, "area.purpose", every language the same keys; a word of a count has a key of every
 * plural form, "_one", "_few", "_many", "_other", and is asked with { count }. Params are written
 * {{name}}, as in the server's words (/i18n/<namespace>/<language>.json), which page.js reads apart.
 *
 * Declared with var and function, not const: a page may be given the file more than once (a site
 * changing its address without loading a page), and a declaration repeated must not fail.
 */

/** The languages the extension speaks, the first the one it falls back to. */
var R4M_LANGUAGES = ["en", "ru", "sr"];

var R4M_WORDS = {
  en: {
    "app.noAnswer": "The application did not answer",
    "app.silent": "The recommend4me application does not answer",

    "common.pressAgain": "{{what}} — press again to take it back",
    "common.pressAgainAlone": "Press again to take it back",
    "common.failed": "Not saved: {{error}}",

    "panel.saving": "saving…",
    "panel.savingCount": "saving… ({{count}})",
    "panel.open": "open in the application",
    "panel.unsaved": "The work is not saved yet: it will appear once the page is sent to the application.",
    "panel.prediction": "Prediction",
    "panel.outOf": "out of 10",
    "panel.withoutGrade": "without your grade:",
    "panel.noPrediction": "No prediction yet: the model is not trained yet or the work is not parsed yet",
    "panel.why": "Why:",

    "grade.title": "Grade:",
    "grade.previous": "previous version ({{version}}): {{grade}}",

    "interest.title": "Interest:",
    "interest.yes": "interesting",
    "interest.no": "not interesting",
    "interest.yesTitle": "Interesting",
    "interest.noTitle": "Not interesting",
    "interest.byModel": "model: interesting with a chance of {{percent}}%",

    "value.restore": "Bring back: you said the work does not have this",
    "value.removeAdded": "Remove what you added",
    "value.unanswer": "Take your answer back",
    "value.unlikely": "The model thinks the work more likely does not have this",
    "value.confidence": "The model's confidence",
    "value.drafted": "by tag",
    "value.implied": "The work has it through a child tag of it; it goes away with that tag",
    "value.draftedFrom": "Filled in by the site's tag «{{from}}» through the universes dictionary; confirm or remove it",
    "value.fromSite": "site",
    "value.fromSiteNote": "The site gives the work this tag; the percentage is the model's word. Confirm or remove it",
    "value.yes": "Yes, the work has this",
    "value.no": "No, the work does not have this",
    "value.mine": "Your answer: the work has this",
    "value.suggested": "Suggestion",

    "universe.refresh": "Refresh the universe's characters from the catalogue",

    "candidate.title": "Candidate: {{mentions}}",
    "candidate.more": "Show {{count}} more candidates",
    "byText.toggle": "By the text",
    "byText.title": "Tags by the text of the work",
    "byText.close": "Close",
    "byText.unfold": "Press to show the whole paragraph",
    "byText.fold": "Press to fold the paragraph",
    "byText.refresh": "Refresh",
    "byText.refreshOff": "off",
    "byText.refreshSeconds": "{{value}} s",
    "byText.refreshMinutes": "{{value}} min",
    "byText.absent": "Not in the work",
    "byText.absentHint": "The work has not this tag at all: answered on the work as absent, every paragraph of the tag offered no more; the model of the closeness learns nothing from it",
    "byText.ignore": "Ignore the tag",
    "byText.ignoreHint": "The tag is ignored altogether: offered nowhere, read by no model; taken back on the tag's page",
    "byText.toggleHint": "The tags the paragraphs of this work's chapters are closest to, each with its paragraph",
    "byText.loading": "Comparing the paragraphs with the tags…",
    "byText.pending": "The chapters' paragraphs are still being encoded: the list comes once they are",
    "byText.none": "No chapters with text: nothing to compare with",
    "byText.notFitted": "Ordered by closeness; chances appear once you have answered \"yes\" and \"wrong\"",
    "byText.closeness": "closeness {{value}}",
    "byText.paragraph": "paragraph {{n}}",
    "byText.partParagraph": "{{part}}, paragraph {{n}}",
    "byText.failed": "Not read: {{error}}",
    "byText.yes": "Yes",
    "byText.yesHint": "The work has this tag by this paragraph: the tag is marked on the work",
    "byText.enoughNot": "Not enough",
    "byText.enoughNotHint": "The paragraph is about this tag, but that is not enough for the work to have it: this pair is offered no more, and the model learns nothing from it",
    "byText.wrong": "Wrong",
    "byText.wrongHint": "The text and the tag are about different things: the model learns from it",
    "candidate.notMentioned": "not mentioned",
    "candidate.mentions_one": "mentioned {{count}} time",
    "candidate.mentions_few": "mentioned {{count}} times",
    "candidate.mentions_many": "mentioned {{count}} times",
    "candidate.mentions_other": "mentioned {{count}} times",

    "allNamed.universeOthers": "no other universes",
    "allNamed.universeWhat": "universes",
    "allNamed.charactersOthers": "no other main ones",
    "allNamed.charactersWhat": "main characters",
    "allNamed.secondaryOthers": "no other secondary ones",
    "allNamed.secondaryWhat": "secondary characters",
    "allNamed.pairingsOthers": "no other pairings",
    "allNamed.pairingsWhat": "pairings",
    "allNamed.valuesOthers": "no other values",
    "allNamed.valuesWhat": "values",
    "allNamed.doneTitle": "All the work's {{what}} are named: the others are not offered and are rejected for the model. Press to take it back",
    "allNamed.title": "All the work's {{what}} are already named: offer no others and count them as rejected",

    "facet.onSite": "On the site: {{values}}",
    "facet.confirmAll": "✓ all ({{count}})",
    "facet.confirmAllTitle": "Confirm every value you have not answered on",
    "facet.works": "works: {{count}}",
    "facet.newTag": "new tag, of the catalogue",
    "facet.change": "change: {{what}}",
    "add.fromMain": "now a main character — will become a secondary one",
    "add.fromSecondary": "now a secondary character — will become a main one",
    "facet.chooseHint": "Choose one of the list: only what the dictionary knows can be added here",

    "mark.plus": "{{what}}: a reason to choose the work",
    "mark.minus": "{{what}}: a reason to turn it down",
    "mark.review": "Review",
    "mark.cover": "Cover",
    "mark.picture": "Picture",

    "card.saving": "recommend4me: the work is being saved",
    "card.pending": "recommend4me: the prediction is still being worked out",
    "card.prediction": "recommend4me: the prediction out of 10",
    "card.grade": "Your grade",
    "card.interestChance": "(model: interesting at {{percent}}%)",
    "card.notSaved": "recommend4me: the mark is not saved — {{error}}",

    "popup.tracking": "tracking",
    "popup.checking": "Checking the application…",
    "popup.send": "Send this page now",
    "popup.recent": "Recent pages",
    "popup.options": "Settings",
    "popup.openApp": "Open the application",
    "popup.answers": "The application at {{server}} answers; sources: {{sources}}",
    "popup.noSources": "none",
    "popup.silent": "The application at {{server}} does not answer ({{error}}). Is it running?",
    "popup.nothingSent": "Nothing sent yet",
    "popup.failed": "{{time}} {{url}} — error: {{error}}",
    "popup.unwanted": "{{time}} {{url}} — no source wants it",
    "popup.nothingNew": "nothing new",
    "popup.partTitle": "“{{title}}”",
    "popup.untitled": "an untitled chapter",
    "popup.partText": "text of {{title}}{{of}}: {{length}} characters",

    "options.title": "recommend4me — settings",
    "options.server": "The application's address",
    "options.enabled": "Send the pages (tracking mode)",
    "options.decorate": "Show the prediction, the grade, the tags and the hints on the sites' pages",
    "options.language": "Language",
    "options.languageBrowser": "The browser's",
    "options.save": "Save",
    "options.saved": "Saved",
    "options.privacy": "The extension sends the application only the pages of the sites its sources need — it takes the list of addresses from the application itself. No other page goes anywhere.",
  },

  ru: {
    "app.noAnswer": "Приложение не ответило",
    "app.silent": "Приложение recommend4me не отвечает",

    "common.pressAgain": "{{what}} — нажмите ещё раз, чтобы снять",
    "common.pressAgainAlone": "Нажмите ещё раз, чтобы снять",
    "common.failed": "Не сохранено: {{error}}",

    "panel.saving": "сохраняю…",
    "panel.savingCount": "сохраняю… ({{count}})",
    "panel.open": "открыть в приложении",
    "panel.unsaved": "Работа ещё не сохранена: она появится, когда страница будет отправлена приложению.",
    "panel.prediction": "Прогноз",
    "panel.outOf": "из 10",
    "panel.withoutGrade": "без вашей оценки:",
    "panel.noPrediction": "Прогноза пока нет: модель ещё не обучена или работа ещё не разобрана",
    "panel.why": "Почему:",

    "grade.title": "Оценка:",
    "grade.previous": "прошлая версия ({{version}}): {{grade}}",

    "interest.title": "Интерес:",
    "interest.yes": "интересно",
    "interest.no": "неинтересно",
    "interest.yesTitle": "Интересно",
    "interest.noTitle": "Неинтересно",
    "interest.byModel": "модель: интересно с вероятностью {{percent}}%",

    "value.restore": "Вернуть: вы сказали, что этого у работы нет",
    "value.removeAdded": "Убрать добавленное вами",
    "value.unanswer": "Снять ваш ответ",
    "value.unlikely": "Модель считает, что скорее этого у работы нет",
    "value.confidence": "Уверенность модели",
    "value.drafted": "по тегу",
    "value.implied": "Есть у работы через свой подтег; уйдёт вместе с ним",
    "value.draftedFrom": "Заполнено по тегу сайта «{{from}}» через словарь вселенных; подтвердите или уберите",
    "value.fromSite": "сайт",
    "value.fromSiteNote": "Этот тег работе даёт сайт; процент — мнение модели. Подтвердите или уберите",
    "value.yes": "Да, это у работы есть",
    "value.no": "Нет, этого у работы нет",
    "value.mine": "Ваш ответ: это у работы есть",
    "value.suggested": "Подсказка",

    "universe.refresh": "Обновить персонажей вселенной из справочника",

    "candidate.title": "Кандидат: {{mentions}}",
    "candidate.more": "Показать ещё {{count}} кандидатов",
    "byText.toggle": "По тексту",
    "byText.title": "Теги по тексту работы",
    "byText.close": "Закрыть",
    "byText.unfold": "Щелчок — показать абзац целиком",
    "byText.fold": "Щелчок — свернуть абзац",
    "byText.refresh": "Обновлять",
    "byText.refreshOff": "вручную",
    "byText.refreshSeconds": "{{value}} с",
    "byText.refreshMinutes": "{{value}} мин",
    "byText.absent": "Нет в работе",
    "byText.absentHint": "Этого тега у работы точно нет: отмечается у работы как отсутствующий, его абзацы больше не предлагаются; модель близости на этом не учится",
    "byText.ignore": "Игнорировать тег",
    "byText.ignoreHint": "Тег помечается игнорируемым вообще: больше нигде не предлагается и не читается моделями; вернуть можно на странице тега",
    "byText.toggleHint": "Теги, к которым ближе всего абзацы глав этой работы, — у каждого его абзац",
    "byText.loading": "Абзацы сравниваются с тегами…",
    "byText.pending": "Абзацы глав ещё кодируются: список появится, когда они будут готовы",
    "byText.none": "Нет глав с текстом — сравнивать не с чем",
    "byText.notFitted": "Порядок — по близости; вероятности появятся после ваших ответов «да» и «неверно»",
    "byText.closeness": "близость {{value}}",
    "byText.paragraph": "абзац {{n}}",
    "byText.partParagraph": "{{part}}, абзац {{n}}",
    "byText.failed": "Не удалось прочитать: {{error}}",
    "byText.yes": "Да",
    "byText.yesHint": "У работы есть этот тег по этому абзацу: тег отмечается у работы",
    "byText.enoughNot": "Недостаточно",
    "byText.enoughNotHint": "Абзац про этот тег, но для тега у работы этого мало: эта пара больше не предлагается, модель на ней не учится",
    "byText.wrong": "Неверно",
    "byText.wrongHint": "Текст и тег про разное: модель учится на этом",
    "candidate.notMentioned": "не упоминается",
    "candidate.mentions_one": "упоминается {{count}} раз",
    "candidate.mentions_few": "упоминается {{count}} раза",
    "candidate.mentions_many": "упоминается {{count}} раз",
    "candidate.mentions_other": "упоминается {{count}} раза",

    "allNamed.universeOthers": "других вселенных нет",
    "allNamed.universeWhat": "вселенные",
    "allNamed.charactersOthers": "других главных нет",
    "allNamed.charactersWhat": "главные персонажи",
    "allNamed.secondaryOthers": "других второстепенных нет",
    "allNamed.secondaryWhat": "второстепенные персонажи",
    "allNamed.pairingsOthers": "других пэйрингов нет",
    "allNamed.pairingsWhat": "пэйринги",
    "allNamed.valuesOthers": "других значений нет",
    "allNamed.valuesWhat": "значения",
    "allNamed.doneTitle": "Названы все {{what}} работы: остальные не предлагаются и для модели отклонены. Нажмите, чтобы снять",
    "allNamed.title": "Все {{what}} работы уже названы: остальные не предлагать и считать отклонёнными",

    "facet.onSite": "На сайте: {{values}}",
    "facet.confirmAll": "✓ все ({{count}})",
    "facet.confirmAllTitle": "Подтвердить все значения без вашего ответа",
    "facet.works": "работ: {{count}}",
    "facet.newTag": "новый тег, из каталога",
    "facet.change": "сменить: {{what}}",
    "add.fromMain": "сейчас среди главных — перейдёт во второстепенные",
    "add.fromSecondary": "сейчас среди второстепенных — перейдёт в главные",
    "facet.chooseHint": "Выберите из списка: сюда можно добавить только то, что есть в словаре",

    "mark.plus": "{{what}}: повод выбрать работу",
    "mark.minus": "{{what}}: повод отказаться от неё",
    "mark.review": "Отзыв",
    "mark.cover": "Обложка",
    "mark.picture": "Картинка",

    "card.saving": "recommend4me: работа сохраняется",
    "card.pending": "recommend4me: прогноз ещё считается",
    "card.prediction": "recommend4me: прогноз из 10",
    "card.grade": "Ваша оценка",
    "card.interestChance": "(модель: интересно на {{percent}}%)",
    "card.notSaved": "recommend4me: отметка не сохранена — {{error}}",

    "popup.tracking": "слежение",
    "popup.checking": "Проверяю приложение…",
    "popup.send": "Отправить эту страницу сейчас",
    "popup.recent": "Последние страницы",
    "popup.options": "Настройки",
    "popup.openApp": "Открыть приложение",
    "popup.answers": "Приложение на {{server}} отвечает; источники: {{sources}}",
    "popup.noSources": "нет",
    "popup.silent": "Приложение на {{server}} не отвечает ({{error}}). Запущено ли оно?",
    "popup.nothingSent": "Пока ничего не отправлено",
    "popup.failed": "{{time}} {{url}} — ошибка: {{error}}",
    "popup.unwanted": "{{time}} {{url}} — ни одному источнику не нужна",
    "popup.nothingNew": "ничего нового",
    "popup.partTitle": "«{{title}}»",
    "popup.untitled": "главы без названия",
    "popup.partText": "текст {{title}}{{of}}: {{length}} знаков",

    "options.title": "recommend4me — настройки",
    "options.server": "Адрес приложения",
    "options.enabled": "Отправлять страницы (режим слежения)",
    "options.decorate": "Показывать на страницах сайтов прогноз, оценку, теги и подсказки",
    "options.language": "Язык",
    "options.languageBrowser": "Как в браузере",
    "options.save": "Сохранить",
    "options.saved": "Сохранено",
    "options.privacy": "Расширение отправляет приложению только страницы тех сайтов, которые нужны его источникам — список адресов оно берёт у самого приложения. Другие страницы никуда не уходят.",
  },

  sr: {
    "app.noAnswer": "Aplikacija nije odgovorila",
    "app.silent": "Aplikacija recommend4me ne odgovara",

    "common.pressAgain": "{{what}} — pritisnite ponovo da poništite",
    "common.pressAgainAlone": "Pritisnite ponovo da poništite",
    "common.failed": "Nije sačuvano: {{error}}",

    "panel.saving": "čuvam…",
    "panel.savingCount": "čuvam… ({{count}})",
    "panel.open": "otvori u aplikaciji",
    "panel.unsaved": "Delo još nije sačuvano: pojaviće se kada stranica bude poslata aplikaciji.",
    "panel.prediction": "Predviđanje",
    "panel.outOf": "od 10",
    "panel.withoutGrade": "bez vaše ocene:",
    "panel.noPrediction": "Predviđanja još nema: model još nije obučen ili delo još nije raščlanjeno",
    "panel.why": "Zašto:",

    "grade.title": "Ocena:",
    "grade.previous": "prethodna verzija ({{version}}): {{grade}}",

    "interest.title": "Interesovanje:",
    "interest.yes": "zanimljivo",
    "interest.no": "nezanimljivo",
    "interest.yesTitle": "Zanimljivo",
    "interest.noTitle": "Nezanimljivo",
    "interest.byModel": "model: zanimljivo sa verovatnoćom od {{percent}}%",

    "value.restore": "Vrati: rekli ste da delo ovo nema",
    "value.removeAdded": "Uklonite ono što ste dodali",
    "value.unanswer": "Poništite svoj odgovor",
    "value.unlikely": "Model smatra da delo ovo verovatnije nema",
    "value.confidence": "Pouzdanost modela",
    "value.drafted": "po tagu",
    "value.implied": "Delo ga ima preko njegovog podređenog taga; nestaje zajedno s njim",
    "value.draftedFrom": "Popunjeno po tagu sajta „{{from}}“ preko rečnika univerzuma; potvrdite ili uklonite",
    "value.fromSite": "sajt",
    "value.fromSiteNote": "Ovaj tag delu daje sajt; procenat je mišljenje modela. Potvrdite ili uklonite",
    "value.yes": "Da, delo ovo ima",
    "value.no": "Ne, delo ovo nema",
    "value.mine": "Vaš odgovor: delo ovo ima",
    "value.suggested": "Savet",

    "universe.refresh": "Osveži likove univerzuma iz kataloga",

    "candidate.title": "Kandidat: {{mentions}}",
    "candidate.more": "Prikaži još {{count}} kandidata",
    "byText.toggle": "Po tekstu",
    "byText.title": "Oznake po tekstu dela",
    "byText.close": "Zatvori",
    "byText.unfold": "Klik — prikaži ceo pasus",
    "byText.fold": "Klik — skupi pasus",
    "byText.refresh": "Osvežavaj",
    "byText.refreshOff": "ručno",
    "byText.refreshSeconds": "{{value}} s",
    "byText.refreshMinutes": "{{value}} min",
    "byText.absent": "Nema u delu",
    "byText.absentHint": "Delo sigurno nema ovu oznaku: označava se na delu kao odsutna, njeni pasusi se više ne nude; model bliskosti iz toga ne uči",
    "byText.ignore": "Zanemari oznaku",
    "byText.ignoreHint": "Oznaka se zanemaruje potpuno: nigde se ne nudi i nijedan model je ne čita; vraća se na stranici oznake",
    "byText.toggleHint": "Oznake kojima su pasusi poglavlja ovog dela najbliži, svaka sa svojim pasusom",
    "byText.loading": "Pasusi se porede sa oznakama…",
    "byText.pending": "Pasusi poglavlja se još kodiraju: spisak dolazi kad budu gotovi",
    "byText.none": "Nema poglavlja sa tekstom: nema s čim da se poredi",
    "byText.notFitted": "Poređano po bliskosti; verovatnoće se pojavljuju posle vaših odgovora „da” i „pogrešno”",
    "byText.closeness": "bliskost {{value}}",
    "byText.paragraph": "pasus {{n}}",
    "byText.partParagraph": "{{part}}, pasus {{n}}",
    "byText.failed": "Nije pročitano: {{error}}",
    "byText.yes": "Da",
    "byText.yesHint": "Delo ima ovu oznaku po ovom pasusu: oznaka se označava na delu",
    "byText.enoughNot": "Nedovoljno",
    "byText.enoughNotHint": "Pasus je o ovoj oznaci, ali to nije dovoljno da je delo ima: ovaj par se više ne nudi, a model iz njega ne uči",
    "byText.wrong": "Pogrešno",
    "byText.wrongHint": "Tekst i oznaka su o različitim stvarima: model uči iz toga",
    "candidate.notMentioned": "ne pominje se",
    "candidate.mentions_one": "pominje se {{count}} put",
    "candidate.mentions_few": "pominje se {{count}} puta",
    "candidate.mentions_many": "pominje se {{count}} puta",
    "candidate.mentions_other": "pominje se {{count}} puta",

    "allNamed.universeOthers": "nema drugih univerzuma",
    "allNamed.universeWhat": "univerzumi",
    "allNamed.charactersOthers": "nema drugih glavnih",
    "allNamed.charactersWhat": "glavni likovi",
    "allNamed.secondaryOthers": "nema drugih sporednih",
    "allNamed.secondaryWhat": "sporedni likovi",
    "allNamed.pairingsOthers": "nema drugih parova",
    "allNamed.pairingsWhat": "parovi",
    "allNamed.valuesOthers": "drugih vrednosti nema",
    "allNamed.valuesWhat": "vrednosti",
    "allNamed.doneTitle": "Navedeni su svi {{what}} dela: ostali se ne nude i za model su odbijeni. Pritisnite da poništite",
    "allNamed.title": "Svi {{what}} dela su već navedeni: ostale ne nuditi i smatrati odbijenim",

    "facet.onSite": "Na sajtu: {{values}}",
    "facet.confirmAll": "✓ sve ({{count}})",
    "facet.confirmAllTitle": "Potvrdi sve vrednosti bez vašeg odgovora",
    "facet.works": "dela: {{count}}",
    "facet.newTag": "novi tag, iz kataloga",
    "facet.change": "promeni: {{what}}",
    "add.fromMain": "sada među glavnim likovima — prelazi u sporedne",
    "add.fromSecondary": "sada među sporednim likovima — prelazi u glavne",
    "facet.chooseHint": "Izaberite sa liste: ovde se može dodati samo ono što rečnik poznaje",

    "mark.plus": "{{what}}: razlog da se delo izabere",
    "mark.minus": "{{what}}: razlog da se od njega odustane",
    "mark.review": "Recenzija",
    "mark.cover": "Korica",
    "mark.picture": "Slika",

    "card.saving": "recommend4me: delo se čuva",
    "card.pending": "recommend4me: predviđanje se još računa",
    "card.prediction": "recommend4me: predviđanje od 10",
    "card.grade": "Vaša ocena",
    "card.interestChance": "(model: zanimljivo sa {{percent}}%)",
    "card.notSaved": "recommend4me: oznaka nije sačuvana — {{error}}",

    "popup.tracking": "praćenje",
    "popup.checking": "Proveravam aplikaciju…",
    "popup.send": "Pošalji ovu stranicu sada",
    "popup.recent": "Poslednje stranice",
    "popup.options": "Podešavanja",
    "popup.openApp": "Otvori aplikaciju",
    "popup.answers": "Aplikacija na {{server}} odgovara; izvori: {{sources}}",
    "popup.noSources": "nema",
    "popup.silent": "Aplikacija na {{server}} ne odgovara ({{error}}). Da li je pokrenuta?",
    "popup.nothingSent": "Još ništa nije poslato",
    "popup.failed": "{{time}} {{url}} — greška: {{error}}",
    "popup.unwanted": "{{time}} {{url}} — nijednom izvoru nije potrebna",
    "popup.nothingNew": "ništa novo",
    "popup.partTitle": "„{{title}}“",
    "popup.untitled": "poglavlja bez naslova",
    "popup.partText": "tekst {{title}}{{of}}: {{length}} znakova",

    "options.title": "recommend4me — podešavanja",
    "options.server": "Adresa aplikacije",
    "options.enabled": "Šalji stranice (režim praćenja)",
    "options.decorate": "Prikazuj predviđanje, ocenu, tagove i savete na stranicama sajtova",
    "options.language": "Jezik",
    "options.languageBrowser": "Kao u pregledaču",
    "options.save": "Sačuvaj",
    "options.saved": "Sačuvano",
    "options.privacy": "Proširenje šalje aplikaciji samo stranice onih sajtova koji su potrebni njenim izvorima — spisak adresa uzima od same aplikacije. Ostale stranice ne idu nikuda.",
  },
};

/** The first of the browser's languages the extension speaks ("sr-Latn-RS" is "sr"), else English. */
function browserLanguage() {
  const wanted = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || ""])
    .map((tag) => String(tag).split("-")[0].toLowerCase());
  return wanted.find((language) => R4M_LANGUAGES.includes(language)) || R4M_LANGUAGES[0];
}

/** The language spoken: the one chosen in the options, else the browser's. */
var r4mLanguage = browserLanguage();

/** Speaks [chosen] — one of R4M_LANGUAGES; anything else ("" — not chosen) is the browser's language. */
function setLanguage(chosen) {
  r4mLanguage = R4M_LANGUAGES.includes(chosen) ? chosen : browserLanguage();
}

/** The language spoken, as Intl takes it: numbers, times and plural forms. Serbian in its Latin script. */
function r4mLocale() {
  return r4mLanguage === "sr" ? "sr-Latn" : r4mLanguage;
}

/** [text] with every {{name}} filled from [params]; a param not given stays as it is written. */
function fill(text, params) {
  return String(text).replace(/\{\{(\w+)\}\}/g, (all, name) => (params && params[name] !== undefined && params[name] !== null ? String(params[name]) : all));
}

/**
 * The extension's words of [key] in the language spoken, filled from [params]; with params.count, of
 * the plural form of that count ("<key>_one", "<key>_few"…). A key missing is shown as it is.
 */
function t(key, params) {
  const words = R4M_WORDS[r4mLanguage] || R4M_WORDS.en;
  let at = key;
  if (params && typeof params.count === "number" && words[key] === undefined) {
    at = `${key}_${new Intl.PluralRules(r4mLocale()).select(params.count)}`;
    if (words[at] === undefined) at = `${key}_other`;
  }
  return fill(words[at] !== undefined ? words[at] : key, params);
}

/** The words of an extension's page put in: the text of every [data-i18n] element, its key the attribute's value. */
function translatePage() {
  document.documentElement.lang = r4mLanguage;
  document.querySelectorAll("[data-i18n]").forEach((e) => {
    e.textContent = t(e.getAttribute("data-i18n"));
  });
}
