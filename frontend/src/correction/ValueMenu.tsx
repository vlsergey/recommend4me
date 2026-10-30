import { useContext, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { EllipsisIcon, ExternalLinkIcon, FileSearchIcon, ListChecksIcon, MoveRightIcon, Trash2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { followLink, navigate, screenHref, type Screen } from "@/screen/screens";
import { candidatesKey, suggestionsKey } from "@/suggestion/useSuggestions";
import { useTagsChanged } from "@/tag/TagsScreen";
import type { Corrections } from "./useCorrections";
import { ValueActions, type AskedValue } from "./valueActions";

/** The two facets of characters: a character is in one of them only, and moves between them. */
const OTHER_CHARACTERS: Record<string, string> = { characters: "secondary-characters", "secondary-characters": "characters" };

const PAIRINGS = "pairings";

type Choice = { label: string; icon: ReactNode } & ({ screen: Screen } | { press: () => Promise<unknown> });

/** What can be done with a value, the evidence aside: by a chip's menu and by a finger's sheet alike. */
function useChoices(asked: AskedValue, corrections: Corrections, tag: boolean): Choice[] {
  const { t } = useTranslation("correction");
  const item = useContext(ValueActions)?.item;
  const queryClient = useQueryClient();
  const tagsChanged = useTagsChanged();
  const { facet, value } = asked;
  const other = OTHER_CHARACTERS[facet];
  const choices: Choice[] = [];
  if (tag) choices.push({ label: t("ValueMenu.page"), icon: <ExternalLinkIcon />, screen: { kind: "tags", tag: value } });
  if (facet === PAIRINGS) choices.push({ label: t("ValueMenu.toConfirm"), icon: <ListChecksIcon />, screen: { kind: "pairings", pairing: value } });
  if (other)
    choices.push({
      label: t(facet === "characters" ? "ValueMenu.toSecondary" : "ValueMenu.toMain"),
      icon: <MoveRightIcon />,
      press: () => corrections.setFacet(other, { key: value }, true),
    });
  if (tag)
    choices.push({
      label: t("ValueMenu.ignore"),
      icon: <Trash2Icon />,
      press: async () => {
        unwrap(await api.PUT("/api/tags/ignored", { body: { tag: value, ignored: true } }));
        await tagsChanged();
        if (!item) return;
        queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
        queryClient.invalidateQueries({ queryKey: candidatesKey(item) });
      },
    });
  return choices;
}

/** ⋯ on a chip, shown when it is pointed at: a finger has the sheet of the name instead. */
export function ValueMenu({ asked, corrections, tag }: { asked: AskedValue; corrections: Corrections; tag: boolean }) {
  const { t } = useTranslation("correction");
  const actions = useContext(ValueActions);
  const choices = useChoices(asked, corrections, tag);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="ghost"
            size="icon-xs"
            className="size-4 rounded-sm text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100 aria-expanded:opacity-100 pointer-coarse:hidden"
            title={t("ValueMenu.title")}
            aria-label={t("ValueMenu.title")}
          />
        }
      >
        <EllipsisIcon className="size-3" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-auto">
        {actions && (
          <DropdownMenuItem onClick={() => actions.evidence(asked)}>
            <FileSearchIcon /> {t("ValueMenu.evidence")}
          </DropdownMenuItem>
        )}
        {choices.map((c) => (
          <DropdownMenuItem key={c.label} onClick={() => ("screen" in c ? navigate(c.screen) : void c.press().catch(() => undefined))}>
            {c.icon} {c.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * A chip's name to a finger: a press opens a sheet from the bottom of the screen with the evidence
 * and what a pointer finds in ⋯. To a pointer it is the [children] as they are.
 */
export function ValueName({ asked, corrections, tag, children }: { asked: AskedValue; corrections: Corrections; tag: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const actions = useContext(ValueActions);
  const choices = useChoices(asked, corrections, tag);
  return (
    <>
      <span className="pointer-coarse:hidden">{children}</span>
      <button type="button" className="hidden min-h-11 items-center text-left pointer-coarse:inline-flex" onClick={() => setOpen(true)}>
        {asked.name}
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="top-auto bottom-0 left-0 flex max-h-[85dvh] w-screen max-w-none translate-x-0 translate-y-0 flex-col overflow-y-auto rounded-none rounded-t-xl sm:w-screen">
          <DialogHeader>
            <DialogTitle>{asked.name}</DialogTitle>
            {asked.summary && <DialogDescription>{asked.summary}</DialogDescription>}
          </DialogHeader>
          <div className="flex flex-wrap gap-1">
            {choices.map((c) =>
              "screen" in c ? (
                <Button key={c.label} variant="outline" nativeButton={false} render={<a href={screenHref(c.screen)} onClick={(e) => followLink(e, c.screen)} />}>
                  {c.icon} {c.label}
                </Button>
              ) : (
                <AsyncButton key={c.label} variant="outline" icon={c.icon} onClick={c.press}>
                  {c.label}
                </AsyncButton>
              ),
            )}
          </div>
          {open && actions?.evidenceBody(asked)}
        </DialogContent>
      </Dialog>
    </>
  );
}
