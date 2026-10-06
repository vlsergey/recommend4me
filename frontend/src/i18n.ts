import type { SourceMode } from "./api/client";

/** A score with one decimal at most, the Russian way: 7,5. */
export function score(value: number): string {
  return (Math.round(value * 10) / 10).toLocaleString("ru-RU");
}

/** Where a search found a work, when the search names a field by a code rather than a label. */
export const SEARCH_FIELD_LABEL: Record<string, string> = {
  TITLE: "название",
  CREATOR: "автор",
  AUTHOR: "автор",
  TAG: "теги",
  FACET: "признаки",
  OVERVIEW: "описание",
  ANNOTATION: "аннотация",
  CHANGELOG: "changelog",
};

export function searchFieldLabel(field: string): string {
  return SEARCH_FIELD_LABEL[field] ?? field.toLowerCase();
}

/** The phases of a job: the application's own and the ones the sources name. */
export const JOB_PHASE_LABEL: Record<string, string> = {
  dictionary: "Словарь тегов",
  feed: "Лента обновлений",
  catalogue: "Каталог",
  details: "Страницы работ",
  pages: "Страницы",
  covers: "Обложки",
  pictures: "Картинки",
  reviews: "Отзывы",
  parts: "Главы",
  vectors: "Векторы текстов",
  scoring: "Обучение и прогнозы",
};

export function phaseLabel(phase: string | undefined): string {
  return phase ? (JOB_PHASE_LABEL[phase] ?? phase) : "";
}

export const MODE_LABEL: Record<SourceMode, string> = {
  SCRAPE_ALL: "Весь каталог",
  UPDATES: "Обновления",
  BROWSER: "Слежение в браузере",
};

/** The background work of the application, as `/api/status` names it. */
export const WORK_LABEL: Record<string, { title: string; about: string }> = {
  pictures: {
    title: "Картинки",
    about: "Обложки и скриншоты, разобранные моделью картинок: сначала оценённых работ, потом остальных по прогнозу.",
  },
  texts: { title: "Тексты", about: "Описания и другие тексты, превращённые в векторы для модели и поиска по смыслу." },
  parts: { title: "Главы", about: "Тексты глав, нарезанные на фрагменты; у каждого фрагмента свой вектор." },
  sets: { title: "Наборы", about: "Пересчёт направлений наборов: скриншотов, отзывов, фрагментов текста." },
};

/** The user's own actions on a site, as the sources name them. */
const SIGNAL_LABEL: Record<string, string> = {
  liked: "лайк",
  like: "лайк",
  bookmarked: "в закладках",
  bookmark: "в закладках",
  favorite: "в избранном",
  favourite: "в избранном",
  shelf: "полка",
  library: "в библиотеке",
  read: "прочитано",
  reading: "читаю",
  played: "сыграно",
  watched: "отслеживаю",
  subscribed: "подписка",
  rating: "моя оценка",
};

/** "лайк", "полка: читаю" — a signal and its value; a yes/no value shows the name only. */
export function signalLabel(name: string, value: string): string {
  const label = SIGNAL_LABEL[name] ?? name;
  return value === "" || value === "true" || value === "1" ? label : `${label}: ${SIGNAL_LABEL[value] ?? value}`;
}

export function formatDate(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

const RELATIVE = new Intl.RelativeTimeFormat("ru", { numeric: "auto" });

/** "3 часа назад", "вчера" — how long ago something happened. */
export function ago(iso: string): string {
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(seconds);
  if (abs < 60) return RELATIVE.format(Math.round(seconds), "second");
  if (abs < 3600) return RELATIVE.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return RELATIVE.format(Math.round(seconds / 3600), "hour");
  if (abs < 86400 * 30) return RELATIVE.format(Math.round(seconds / 86400), "day");
  if (abs < 86400 * 365) return RELATIVE.format(Math.round(seconds / (86400 * 30)), "month");
  return RELATIVE.format(Math.round(seconds / (86400 * 365)), "year");
}

export function percent(x: number | undefined): string {
  return x === undefined ? "—" : `${Math.round(x * 100)}%`;
}

export function compact(n: number): string {
  return new Intl.NumberFormat("ru-RU", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

/** A number as it is written: 1 234,5. */
export function number(n: number): string {
  return n.toLocaleString("ru-RU", { maximumFractionDigits: 2 });
}

const PLURAL = new Intl.PluralRules("ru");

/** The word for a count: plural(5, ["работа", "работы", "работ"]) — "работ". */
export function plural(n: number, [one, few, many]: [string, string, string]): string {
  switch (PLURAL.select(n)) {
    case "one":
      return one;
    case "few":
      return few;
    default:
      return many;
  }
}

/** "2 ч 15 мин", "40 мин", "меньше минуты" — a duration in seconds as people say it. */
export function duration(seconds: number): string {
  if (!isFinite(seconds) || seconds <= 0) return "";
  if (seconds < 60) return "меньше минуты";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} мин`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} ч` : `${hours} ч ${rest} мин`;
}

/** Time left of a phase from its pace so far; undefined until there is a pace to judge by. */
export function timeLeft(phaseStartedAt: string | undefined, processed: number, total: number): string | undefined {
  if (!phaseStartedAt || processed <= 0 || total <= processed) return undefined;
  const elapsed = (Date.now() - new Date(phaseStartedAt).getTime()) / 1000;
  return duration((elapsed / processed) * (total - processed));
}
