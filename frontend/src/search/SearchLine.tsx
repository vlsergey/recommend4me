import { useEffect, useRef, useState } from "react";
import { Loader2Icon, SearchIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";

/**
 * The pause in the typing after which the line is searched: the user's choice of a second — the
 * 300 ms usual for suggestions searched every few words cost a reranking each.
 */
const SEARCH_SETTLE_MS = 1000;

/**
 * THE LINE OF THE SEARCH OF THE LIST: what is typed is its own, a letter drawing the field alone — not the screen
 * of works with every card of its list, which on a tablet made each letter wait for the whole screen. The list is
 * told the line searched ([onSearch]) once the typing has paused for SEARCH_SETTLE_MS — at once on Enter: a line
 * searched is a second or two of the reranker reading every work found, and every half-typed word was one ("s",
 * "spa", "space" — each read whole). A line cleared is no search: the list back at once. Nothing while an input
 * method puts a word together: the pause counts from the word done. [searching]: the line searched, its works not
 * come yet.
 */
export function SearchLine({ searching, onSearch }: { searching: boolean; onSearch: (line: string) => void }) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState("");
  // A word still being put together by an input method (Chinese, Japanese, a phone's keyboard): not searched till it is done
  const [composing, setComposing] = useState(false);

  useEffect(() => {
    if (composing) return;
    const line = typed.trim();
    if (!line) {
      onSearch("");
      return;
    }
    const t = setTimeout(() => onSearch(line), SEARCH_SETTLE_MS);
    return () => clearTimeout(t);
  }, [typed, composing, onSearch]);

  // "/" jumps to the search from anywhere but a text field
  const field = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "/" || (e.target as HTMLElement).closest("input, textarea, select, [role=dialog]")) return;
      e.preventDefault();
      field.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className="relative w-full sm:w-96">
      {/* Where the search was typed, its wait: the line searched, its works not come yet */}
      {searching ? (
        <Loader2Icon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 animate-spin text-muted-foreground" aria-hidden />
      ) : (
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
      )}
      <Input
        ref={field}
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        onCompositionStart={() => setComposing(true)}
        onCompositionEnd={() => setComposing(false)}
        onKeyDown={(e) => {
          // The keys of an input method's word are its own: Enter chooses the word, Escape drops it
          if (e.nativeEvent.isComposing) return;
          if (e.key === "Escape") {
            setTyped("");
            e.currentTarget.blur();
          }
          // Enter: the line as typed, not waiting for the pause
          if (e.key === "Enter") onSearch(typed.trim());
        }}
        placeholder={t("App.searchPlaceholder")}
        className="pr-8 pl-8"
        type="search"
      />
      <kbd className="pointer-events-none absolute top-1/2 right-2.5 hidden -translate-y-1/2 rounded border px-1 font-mono text-[10px] text-muted-foreground pointer-fine:block">
        /
      </kbd>
    </div>
  );
}
