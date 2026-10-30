import { createContext, type ReactNode } from "react";
import type { ItemRef } from "@/api/client";

/** What a value is asked of: its facet and key, how it is named, its page in the catalogue, how often the texts name it. */
export type AskedValue = { facet: string; value: string; name: string; url?: string | null; summary?: string };

/** The key a chip is found by: [ValueActions.reveal], the `data-value` of its element. */
export const chipKey = (facet: string, key: string) => `${facet}=${key}`;

/**
 * What the screen of a work gives its chips: the evidence of a value opened on the left page, the
 * same for a finger in a sheet, and the chip a feature of the explanation led to.
 */
export const ValueActions = createContext<{
  item: ItemRef;
  evidence: (asked: AskedValue) => void;
  evidenceBody: (asked: AskedValue) => ReactNode;
  revealed?: string;
} | null>(null);
