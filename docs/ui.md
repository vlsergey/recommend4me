# The rules of the interface

How every screen of the web interface (`frontend/src`) and every view of the extension
(`browser-extension/extension`) behaves when its data changes and when the user presses. A new
screen, list or button follows them, and a change of an old one brings it to them. How a form of
marking is laid out is in `work-screen.md`; this file is about behaviour.

## 1. A list read again is shown calmly

A list whose rows have buttons is never redrawn under the user's finger. It is shown through
`lib/useCalmUpdates.ts`, whatever made it change: a word kept, a refresh, "load more", a filter or a
search changed. The list just read is held against the one shown.

- **Nothing changed:** nothing happens.
- **The same rows in the same places, a word of one of them changed:** it is shown at once. Nothing
  moves, nothing is locked.
- **Another list** (a row more, a row less, another order): the list is **locked** and every row
  moves for 1 s from where it stood to where it stands now. A new row fades in. The list takes
  presses again 1 s after the rows stop, so a row that moved is never pressed by the click meant
  for the one that was there.
- **A row answered on that leaves the list** (`remove`): it fades where it stands for 0.5 s, the
  rows after it move up into its place for 0.5 s, the list locked meanwhile.
- **A word kept on a row that stays** (`change`): the row shows it in its place at once.
- **A list read while the rows move** waits till they stand.

Every row is given `rowRef` of its key. The extension's lists do the same (the window of tags by text:
`byTextBlock`).

Where: `marking/MarkingTable.tsx`, `duplicate/DuplicatesScreen.tsx`, `author/AuthorOffers.tsx`,
`tagtext/TagsByText.tsx`, `universe/SimilarUniverses.tsx`; `tag/SimilarTags.tsx` does the same by
itself, made before the hook.

## 2. A locked list shows that it is locked

While a list is locked:

- every button of it is `disabled`, and looks so;
- the list has `pointer-events-none` and `aria-busy`;
- its heading shows the spinner.

A click that does nothing must look like a button that cannot be pressed, never like a fault. In the
extension: `b.disabled` while `state.locked`, with a dimmed `.r4m-button:disabled`.

## 3. A button shows only its own work

A button is busy (spinner, disabled) only while its own press is at work: `components/AsyncButton.tsx`
in the web interface, `act` with its `about` mark in the extension. It is never busy because of the
state of a query: a list read anew in the background is not the button's work. A button that reads
something ("load more") reads it in its own handler (`queryClient.fetchQuery`), and the list shows it
once it is there. A button stays in its place while it works; it does not vanish while its own
reading is under way.

## 4. An answer is kept, then shown

A press is sent at once and shown once the server has kept it, never before. After an answer only
what is on the screen changes. The list is not read again because of it — with two exceptions:

- the auto-refresh of the area is on (section 5);
- a list of a dictionary, whose order a merge changes (tags, universes alike one), is read again
  2 s after the last press, in the background, and shown by section 1.

No polling is started by an answer. A `pending` mark is asked again only for work the user pressed
for on that screen, or on a screen with nothing on it yet.

## 5. An area that reads itself again lets the user choose how often

Every part of the web interface that reads its data again by itself has, in its heading, the choice
of how often: `components/RefreshSelect.tsx` with `lib/useRefresh.ts` (off, 1 s … 1 min). The choice
is kept in the browser's localStorage under the area's own key (`<area>.refresh`), and the query
reads `refetchInterval: every || false`. A new area that reads itself again gets one too.

## 6. A wait of a moment marks nothing

A busy mark of a whole area (a list read anew) is shown only once the wait has held for 300 ms, and
then for 400 ms at least: `lib/useShownLate.ts`. A list back in a moment does not flash.

## 7. Layout by CSS alone

Layout that reacts to the room it has is done by CSS (flex-wrap, container units, the components'
own classes). A width is never measured in script: no `ResizeObserver` of a layout. If script looks
like the only way, ask the owner first.

## 8. Filters and choices are kept in the browser

A filter of a list, a sort, a tab that says how a list is shown is kept in the browser
(`lib/usePref.ts`) under the screen's own key, by the content type when it depends on it
(`universes.has.<type>`). Changing one starts the list again from its first page.
