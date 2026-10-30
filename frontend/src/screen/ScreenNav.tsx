import { Fragment, type ReactNode } from "react";
import type { TFunction } from "i18next";
import { BookMarkedIcon, ChevronDownIcon, CopyIcon, HeartHandshakeIcon, HistoryIcon, LibraryIcon, MenuIcon, OrbitIcon, PowerIcon, RadarIcon, SlidersHorizontalIcon, TagsIcon, UsersIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSub, DropdownMenuSubContent, DropdownMenuSubTrigger, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { typeIcon, typeTitle } from "@/contenttype/types";
import { LanguageSubmenu } from "@/i18n/LanguageMenu";
import { UpdateSubmenu } from "@/job/JobControl";
import { cn } from "@/lib/utils";
import { ModelLabel } from "@/model/GradeModel";
import { ThemeSubmenu, type Theme } from "@/theme/ThemeToggle";
import { followLink, screenHref, type Screen } from "./screens";

/**
 * An entry of the menu: a screen of a content type, opened by a link — or a window opened by
 * [open]: the model's details, the browser extension's.
 */
type Entry = { key: string; label: ReactNode; title: string; active: boolean } & (
  | { screen: Screen }
  | { open: () => void }
);

/** A group of the menu: its entries, and the submenus that are neither a screen nor a window. */
type Group = { key: string; label: ReactNode; items: (Entry | { key: string; submenu: ReactNode })[] };

export type MenuProps = {
  types: ContentTypeInfo[];
  type: ContentTypeInfo;
  screen: Screen;
  theme: Theme;
  onTheme: (theme: Theme) => void;
  onCapture: () => void;
  /** The window of a source's loads opened ([JobControl][@/job/JobControl]). */
  onJobDialog: (source: string) => void;
  /** The application stopped as it should be ([StopDialog][@/screen/StopDialog]). */
  onStop: () => void;
};

/**
 * THE MENU, ONE LIST FOR THE BAR AND THE BUTTON: the works — of every content type by its name,
 * "Games", "Books", the updates of the sites, the order the model gives them, the history of the
 * grades — their marking, the settings, the browser extension and the stop of the application.
 */
function menu({ types, type, screen, theme, onTheme, onCapture, onJobDialog, onStop }: MenuProps, t: TFunction<"screen">): (Entry | Group)[] {
  const universes = types.some((tp) => tp.universes);
  return [
    {
      key: "works",
      label: (
        <>
          <LibraryIcon /> {t("Menu.group.works")}
        </>
      ),
      items: [
        ...types.map((tp): Entry => {
          const Icon = typeIcon(tp.id);
          return {
            key: `works:${tp.id}`,
            screen: { kind: "works", type: tp.id },
            label: (
              <>
                <Icon /> {typeTitle(tp.id)}
              </>
            ),
            title: t("Menu.works.title", { type: typeTitle(tp.id) }),
            active: screen.kind === "works" && tp.id === type.id,
          };
        }),
        { key: "update", submenu: <UpdateSubmenu type={type} onDialog={onJobDialog} /> },
        {
          key: "models",
          screen: { kind: "models", type: type.id },
          label: <ModelLabel type={type} />,
          title: t("Menu.model.title"),
          active: screen.kind === "models",
        },
        {
          key: "history",
          screen: { kind: "history" },
          label: (
            <>
              <HistoryIcon /> {t("Menu.history")}
            </>
          ),
          title: t("Menu.history.title"),
          active: screen.kind === "history",
        },
      ],
    },
    {
      key: "markup",
      label: (
        <>
          <BookMarkedIcon /> {t("Menu.group.markup")}
        </>
      ),
      items: [
        {
          key: "duplicates",
          screen: { kind: "duplicates", type: type.id },
          label: (
            <>
              <CopyIcon /> {t("Menu.duplicates")}
            </>
          ),
          title: t("Menu.duplicates.title"),
          active: screen.kind === "duplicates",
        },
        ...(universes
          ? [
              {
                key: "universes",
                screen: { kind: "universes" },
                label: (
                  <>
                    <OrbitIcon /> {t("Menu.universes")}
                  </>
                ),
                title: t("Menu.universes.title"),
                active: screen.kind === "universes",
              } satisfies Entry,
            ]
          : []),
        {
          key: "authors",
          screen: { kind: "authors", type: type.id },
          label: (
            <>
              <UsersIcon /> {t("Menu.authors")}
            </>
          ),
          title: t("Menu.authors.title"),
          active: screen.kind === "authors",
        },
        ...(universes
          ? [
              {
                key: "pairings",
                screen: { kind: "pairings" },
                label: (
                  <>
                    <HeartHandshakeIcon /> {t("Menu.pairings")}
                  </>
                ),
                title: t("Menu.pairings.title"),
                active: screen.kind === "pairings",
              } satisfies Entry,
            ]
          : []),
        {
          key: "tags",
          screen: { kind: "tags" },
          label: (
            <>
              <TagsIcon /> {t("Menu.tags")}
            </>
          ),
          title: t("Menu.tags.title"),
          active: screen.kind === "tags",
        },
      ],
    },
    {
      key: "settings",
      label: (
        <>
          <SlidersHorizontalIcon /> {t("Menu.group.settings")}
        </>
      ),
      items: [
        { key: "theme", submenu: <ThemeSubmenu theme={theme} onChange={onTheme} /> },
        { key: "language", submenu: <LanguageSubmenu /> },
      ],
    },
    {
      key: "capture",
      open: onCapture,
      label: (
        <>
          <RadarIcon /> {t("Menu.capture")}
        </>
      ),
      title: t("Menu.capture.title"),
      active: false,
    },
    {
      key: "stop",
      open: onStop,
      label: (
        <>
          <PowerIcon /> {t("Menu.stop")}
        </>
      ),
      title: t("Menu.stop.title"),
      active: false,
    },
  ];
}

/** A group whose entry the user stands on: lit, or a bar with nothing lit would say they stand nowhere. */
const holdsActive = (g: Group) => g.items.some((i) => "active" in i && i.active);

/** A click on an entry of a screen: followed as a link is — the type is in its address. */
function follow(ev: React.MouseEvent<HTMLAnchorElement>, e: Entry & { screen: Screen }) {
  followLink(ev, e.screen);
}

/** An entry of the bar: a link or a button drawn alike; the screen the user stands on filled — brighter than a hover. */
const ITEM =
  "inline-flex h-8 items-center gap-1.5 rounded-md px-3 text-sm font-medium whitespace-nowrap transition-colors hover:bg-muted [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0";
const ACTIVE = "bg-primary text-primary-foreground hover:bg-primary";

/**
 * THE MENU: ITS BAR WHERE THE BAR FITS, ITS BUTTON WHERE IT DOES NOT — by what the bar's entries and
 * their words take against what the header leaves, whatever the window, and by the layout alone, no
 * width counted in script. The two share a wrapping flex one line high, its lines kept to the bottom:
 * - the bar fits: it and the button's box stand on one line, the box given what the bar leaves;
 * - it does not: the box, however narrow, wraps to a line of its own — the one that shows, the bar's
 *   going above the top and clipped — and is given the whole width.
 * The button stands in its box at 100% of the box less the whole width (100cqw): nought when it has
 * the whole width; the bar's width to the left, out of the box and clipped, when it has not.
 */
export function ScreenBar(props: MenuProps) {
  return (
    <div className="@container min-w-0 flex-1">
      <div className="flex h-9 flex-wrap content-end overflow-clip">
        <ScreenBarEntries {...props} />
        <div className="flex h-9 min-w-0 flex-1 items-center overflow-clip">
          <ScreenMenuButton {...props} />
        </div>
      </div>
    </div>
  );
}

/** The bar's entries, as wide as their words: a group drops its entries down. */
function ScreenBarEntries(props: MenuProps) {
  const { t } = useTranslation("screen");
  return (
    <nav className="flex h-9 w-max items-center gap-1" aria-label={t("Menu.title")}>
      {menu(props, t).map((e) =>
        "items" in e ? (
          <DropdownMenu key={e.key}>
            <DropdownMenuTrigger render={<button type="button" className={cn(ITEM, holdsActive(e) && ACTIVE)} />}>
              {e.label}
              <ChevronDownIcon className="opacity-60" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-auto min-w-48">
              <GroupItems group={e} />
            </DropdownMenuContent>
          </DropdownMenu>
        ) : "screen" in e ? (
          <a
            key={e.key}
            href={screenHref(e.screen)}
            onClick={(ev) => follow(ev, e)}
            aria-current={e.active ? "page" : undefined}
            title={e.title}
            className={cn(ITEM, e.active && ACTIVE)}
          >
            {e.label}
          </a>
        ) : (
          <button key={e.key} type="button" onClick={e.open} title={e.title} className={ITEM}>
            {e.label}
          </button>
        ),
      )}
    </nav>
  );
}

/** The same menu under a button, where the bar does not fit — the bar folded, not a second arrangement of it: a group its submenu. */
function ScreenMenuButton(props: MenuProps) {
  const { t } = useTranslation("screen");
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" size="icon" className="ml-[calc(100%-100cqw)] shrink-0" aria-label={t("Menu.title")} />}>
        <MenuIcon />
      </DropdownMenuTrigger>
      {/* As wide as its words, not as the button it hangs on */}
      <DropdownMenuContent className="w-auto min-w-48">
        {menu(props, t).map((e) =>
          "items" in e ? (
            <DropdownMenuSub key={e.key}>
              {/* Opened by a press alone: on a touch screen there is no hover, and a hover opening it first makes the press close it */}
              <DropdownMenuSubTrigger openOnHover={false} className={cn(holdsActive(e) && "bg-accent text-accent-foreground")}>
                {e.label}
              </DropdownMenuSubTrigger>
              <DropdownMenuSubContent className="w-auto min-w-48">
                <GroupItems group={e} />
              </DropdownMenuSubContent>
            </DropdownMenuSub>
          ) : (
            <EntryItem key={e.key} entry={e} />
          ),
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function GroupItems({ group }: { group: Group }) {
  return group.items.map((i) => ("submenu" in i ? <Fragment key={i.key}>{i.submenu}</Fragment> : <EntryItem key={i.key} entry={i} />));
}

/** An entry in a dropping menu; the screen the user stands on marked. */
function EntryItem({ entry: e }: { entry: Entry }) {
  return "screen" in e ? (
    <DropdownMenuItem
      render={<a href={screenHref(e.screen)} onClick={(ev) => follow(ev, e)} />}
      aria-current={e.active ? "page" : undefined}
      title={e.title}
      className={cn(e.active && "bg-accent text-accent-foreground")}
    >
      {e.label}
    </DropdownMenuItem>
  ) : (
    <DropdownMenuItem onClick={e.open} title={e.title}>
      {e.label}
    </DropdownMenuItem>
  );
}
