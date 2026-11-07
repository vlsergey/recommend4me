import type { DuplicatePair, DuplicateWork } from "@/api/client";

type Verdict = NonNullable<DuplicatePair["answer"]>;

/** The work a side of a pair is of: its normalized work as read; of none known (parted from its work since), the item alone. */
const workOf = (w: DuplicateWork) => (w.work != null ? `work:${w.work}` : `item:${w.source}/${w.item}`);

const isItem = (w: DuplicateWork, x: DuplicateWork) => w.source === x.source && w.item === x.item;

/**
 * The pairs read that a word on [p] is a word on too, [p] among them. A word is on the two works, of every
 * item of them alike: of a pair of two works, every pair of an item of each; of a pair of one work (said
 * one before), the word parts its second item from the work — the server takes it out — so every pair of
 * that item and another of the work.
 */
export function sharing(p: DuplicatePair, pairs: DuplicatePair[]): DuplicatePair[] {
  const x = workOf(p.first);
  const y = workOf(p.second);
  if (x !== y)
    return pairs.filter((q) => {
      const a = workOf(q.first);
      const b = workOf(q.second);
      return (a === x && b === y) || (a === y && b === x);
    });
  return pairs.filter((q) => (isItem(q.first, p.second) && workOf(q.second) === x) || (isItem(q.second, p.second) && workOf(q.first) === x));
}

/**
 * The pairs read as they stand once the word [verdict] on [p] is kept — none: the word taken back. Every
 * pair sharing it ([sharing]) has it; the works as the word leaves them: two said one work, the second's
 * one with the first's; the second item parted from a work it was one with, a work of its own — so that
 * the next word, on any pair of them, is shared as the server shares it, before the pairs are read again.
 */
export function afterWord(p: DuplicatePair, verdict: Verdict | undefined, pairs: DuplicatePair[]): DuplicatePair[] {
  const touched = new Set(sharing(p, pairs));
  const oneWork = workOf(p.first) === workOf(p.second);
  const joined = !oneWork && verdict === "SAME";
  const parted = oneWork && verdict !== "SAME";
  const from = workOf(p.second);
  const moved = (w: DuplicateWork): DuplicateWork => {
    if (joined && workOf(w) === from) return { ...w, work: p.first.work };
    if (parted && isItem(w, p.second)) return { ...w, work: undefined };
    return w;
  };
  return pairs.map((q) => {
    const first = moved(q.first);
    const second = moved(q.second);
    const answer = touched.has(q) ? verdict : q.answer;
    return first === q.first && second === q.second && answer === q.answer ? q : { ...q, first, second, answer };
  });
}
