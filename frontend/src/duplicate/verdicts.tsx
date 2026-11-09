import { EqualIcon, EqualNotIcon, LanguagesIcon, LibraryBigIcon, PuzzleIcon } from "lucide-react";
import type { DuplicatePair } from "@/api/client";

/** The user's word on a pair. */
export type Verdict = NonNullable<DuplicatePair["answer"]>;

/** The answers of the buttons: one work (=), different works (≠). */
export const VERDICTS: { verdict: Verdict; icon: React.ReactNode; word: "same" | "different" }[] = [
  { verdict: "SAME", icon: <EqualIcon />, word: "same" },
  { verdict: "DIFFERENT", icon: <EqualNotIcon />, word: "different" },
];

/** The answers of related works, behind a drop-down beside the buttons: a translation, a part, one series. */
export const RELATED: { verdict: Verdict; icon: React.ReactNode; word: "translation" | "partial" | "series" }[] = [
  { verdict: "TRANSLATION", icon: <LanguagesIcon />, word: "translation" },
  { verdict: "PARTIAL", icon: <PuzzleIcon />, word: "partial" },
  { verdict: "SERIES", icon: <LibraryBigIcon />, word: "series" },
];
