import type { KeyboardEvent } from "react";

/**
 * The keys of a press, for a block pressed as a button that must stay a block (text to select and
 * copy, folded and unfolded by a press): Enter or Space does what the press does.
 */
export function pressKeys(press: () => void) {
  return (e: KeyboardEvent) => {
    if (e.key !== "Enter" && e.key !== " ") return;
    e.preventDefault();
    press();
  };
}
