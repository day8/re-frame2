# The blessed-virtualizer recipe

The evidence for [specification.md](specification.md#7-complete-use-case-coverage) §7's *Large collections* row — *suitable read topology and keys*, answered by a *blessed foreign virtualizer recipe*, proved against *10K-row behavior, focus and accessibility* — and the ecosystem half of its *Foreign React ecosystem and native hot work* row.

`rf2-hic-047` **changes no runtime and adds no namespace to the artefact.** It is one screen written on the shipped public doors, a stand-in for the npm package a consumer would install, and the suites that hold them. The screen is the **ledger**: ten thousand records, twenty-four rows in the document, a controlled field and a toggle in every row.

> **Scope, corrected at source.** This bead's title names an imperative SDK as well. It does not own one: the bead's own scope-correction note assigns that witness to `rf2-hic-067`, which has landed it — `implementation/hicasso/test/re_frame/hicasso/imperative_sdk_dom_cljs_test.cljs`, one foreign instance owned through `n/defcomponent` + `h/defhost` on the acquire/release recipe. This page is the virtualizer half and nothing else.

## Where it lives, and why not under `examples/`

Everything is under `implementation/hicasso/test/re_frame/hicasso/examples/ledger/`, beside the slice, the Todo witness, the four-field editor, the 100-cell grid, the typeahead and the forms application. That tree is already on the `:source-paths` both test lanes compile from, so no `:source-paths` entry was added, no build id was minted and no `:dev-http` port was claimed.

The repo-root `examples/` tree was the other candidate and is the wrong home: examples are **test-free** by standing decision (2026-05-19, `rf2-8cevm`), and this screen is nothing but its witnesses. A screen whose whole purpose is to prove that focus survives a scroll has to live where its proof can live.

## The crossing, in full

```clojure
(h/defhost rows vendor/virtual-rows
  {:callbacks {:render-row :render
               :on-window  :event}})
```

That is the entire interop surface. Two declared positions carrying two different contracts, neither inferred from the prop's spelling; every other prop — `:count`, `:row-height`, `:viewport-height`, `:overscan`, `:pinned-index` — is ordinary data and needs no declaration at all.

Both callbacks are written with `h/fn` rather than as intent vectors, and for one reason: the vendor invokes them **value-first** — `renderRow(index, offset)`, `onWindow(from, to)` — so there is no DOM event at argument one and nothing for a vector's markers to read. That is HD-024's motivating shape, and the one callback form receives the library's own arguments, in order.

The application's foreign-dependency roster is **four** namespaces: `re-frame.core`, `re-frame.hicasso`, `re-frame.adapter.uix`, and the vendor. Three of those four are what the 100-cell grid names. Read as a table, that is the headline: **a serious third-party component costs one line on the roster and one declaration in the view.** No wrapper library, no adapter namespace, no interop shim.

## The four rules

### Rule 1 — the key is the row's place in the MODEL

```clojure
:render-row (h/fn [i offset]
              (h/as-element
                [ledger-row {:key (row-key i) :index i :offset offset}]))
```

This is the rule the whole screen turns on, and a virtualizer makes it easy to get wrong, because a windowing component hands the consumer a position on every render and the *window* positions are stable while the records under them are not.

Key by the window slot — or omit the key, which is the same thing, since React then reconciles by array position — and React sees twenty-six unchanged children across a scroll. It reuses every DOM node and merely re-supplies the props. On screen that is indistinguishable from correct. In the document the field the user is typing into now belongs to a different record: same node, same caret, same focus, different row, and the next keystroke lands in somebody else's data.

Key by the model index and React sees the truth: the rows that stayed keep their nodes, the rows that left are unmounted, the rows that arrived are mounted. Focus, caret, selection and any DOM state a row is holding travel with the record, because they never moved.

**The finding this screen publishes:** Hicasso's unkeyed-children warning does not reach here. The array of children belongs to the vendor, the substrate never walks it, and React's own console warning is all that fires. So the rule cannot be enforced at the crossing and has to be written down — which is what this page is. `ledger.virtualized-dom-cljs-test` mounts the keyless variant and measures the defect rather than describing it: after a three-row scroll the focused field's `data-record` reads `rec-100043` where the application's reads `rec-100040`, the caret is unmoved, and what the user typed is three rows above where nothing is looking.

### Rule 2 — the row reads its own record

The row takes an index and an offset. Everything it displays it reads. A parent that read the window's records and passed them down would re-render on every scroll and on every keystroke in any visible row, and would put the whole collection one refactor away from the render path.

It also matters for Rule 1. A row that reads by index needs no data from its parent to be minted, so the render callback needs none either — which is fortunate, because **a subscription may not be read inside a render callback**: the callback runs during the vendor's render, outside the supplying boundary's read extent, and Hicasso refuses a read that escapes it (I7). A row shape that needed its record at the call site could not be written at all.

### Rule 3 — the focused row stays mounted, and the application still does not touch focus

`:on-focus` writes the row's index into `app-db`; the screen reads it back and hands it to the virtualizer as `:pinned-index`; the virtualizer keeps that row rendered wherever the window has got to, at its true offset, off screen.

Nothing calls `.focus()`. Nothing reads `document.activeElement`. The platform keeps owning focus — the pin only stops React from **deleting the node the platform's focus is already in**, which is the ordinary behaviour of every virtualizer and the reason the rule exists. The distinction is the difference between a recipe and a focus manager, and the suite is written to make it visible: with the pin, a scroll four hundred and sixty rows away leaves the caret exactly where it was; without it, focus falls to `document.body` and the user is typing nowhere.

The pin is released by the next focus and by nothing else. A `:on-blur` companion would unmount the row while the platform is still moving focus through it, and buys back one row of DOM.

### Rule 4 — the count the reader hears is the model's

`aria-rowcount` is ten thousand while twenty-four rows exist, and each row's `aria-rowindex` is its model index plus one. Those two attributes are the entire accessibility story of virtualization: **the document has stopped being the model**, and only a value the author wrote can carry the model into the accessibility tree. Without them a screen reader announces a twenty-four-row table, confidently and wrongly.

They are also the pair a window-relative implementation gets wrong while looking right — announcing "row 1 of 10,000" for the four-hundred-and-ninety-eighth record — so the value is asserted structurally on the row and again on a real engine after a scroll.

## What makes a virtualizer *blessed*

The spec's adjective is doing work. Three properties, all of them real library features rather than inventions for this screen:

| property | why it matters | what its absence breaks |
|---|---|---|
| the consumer supplies the **key** | identity across a scroll is a model fact, and only the consumer knows the model | slot-keyed wrappers move focus and caret to a different record on every scroll |
| its own wrappers are **`role="presentation"`** | `role="grid"` owns `role="row"`, and a virtualizer inserts two divs between them | the rows stop being the grid's rows and the table's semantics collapse into a scroll container |
| it can be told to **keep a row mounted** | React destroys the focused node the moment the window leaves it | focus is lost mid-interaction, silently, on an ordinary scroll |

### Which packages have them, audited by version

A claim about a third-party API is not a fact until a version is attached to it, and these two packages have both changed their answer at least once. Each cell below names the API that answers the property and the release it was read from. The reading is off each package's own published declarations and source rather than its prose — `react-window@2.3.0/dist/react-window.d.ts` and its source map, `react-window@1.8.11/src/createListComponent.js`, and `@tanstack/virtual-core@3.17.7/dist/esm/index.d.ts`, the core that `@tanstack/react-virtual@3.14.9` depends on — taken 2026-08-13.

| property | `@tanstack/react-virtual` 3.14.9 | `react-window` 2.3.0 | `react-window` 1.8.11 |
|---|---|---|---|
| the consumer supplies the **key** | yes — `getItemKey?: (index) => Key`, surfaced on each `virtualItem.key` | yes — `rowKey?: (index, data) => React.Key`, used as the row element's `key` | yes — `itemKey?: (index, data) => any`, used as the row element's `key` |
| its own wrappers are the consumer's to shape | yes, vacuously — it exports two hooks and no components, so every element on screen is the consumer's | yes — one root, whose element `tagName` picks and whose default `role="list"` any `role` in the rest props overrides; rows are its direct children and each row element is the consumer's `rowComponent`. The one element it adds beyond them is an `aria-hidden` sizing div | yes, through the escape it ships for exactly this — `outerElementType` and `innerElementType` replace both wrappers. Plain attributes are not forwarded, so a `role` arrives as a component rather than as a prop |
| it can be told to **keep a row mounted** | yes — `rangeExtractor?: (range: Range) => Array<number>` returns the indices to render, and may return any index at all | **no** | **no** |

The earlier reading of that third column was wrong, and wrong in the way this page exists to warn about: assembled from an impression of a package instead of read off it. `react-window` does not render an unchangeable keyed wrapper and never has. The key has been the consumer's for the whole of the 1.x line — `itemKey` from 1.0.0 (July 2018) through 1.8.11 (December 2024) — and over exactly that span both wrapper elements were replaceable through `outerElementType` and `innerElementType`. The 2.x rewrite (2.0.0, August 2025) did not introduce consumer keying; it simplified the wrapper story, dropping the inner container so that rows became direct children of a root whose tag and role the consumer sets.

What `react-window` does lack is the third property, and it lacks it in both API generations, structurally rather than by an unshipped prop. Each computes a single contiguous `[start, stop]` range and loops it — `for (index = startIndex; index <= stopIndex; index++)` in 1.8.11, the same shape over `startIndexOverscan`/`stopIndexOverscan` in 2.3.0. The only consumer influence on that range is `overscanCount`, which widens it by a row or two around what is visible and cannot reach a record four hundred and sixty places away. Neither version's export surface offers any other seam: no `rangeExtractor`, no sticky-index option, nothing that admits an out-of-range index to what renders.

**So the recommendation stands, but on one property instead of three.** `rangeExtractor` is what Rule 3 is written on and `react-window` has no counterpart to it, which is enough on its own. The narrower grounds are what a reader choosing a virtualizer actually needs, though: `react-window` will key by the model correctly and will not fight a `role="grid"`, and a screen built on it comes apart only where focus has to survive the focused row leaving the window. A collection with nothing focusable in its rows does not touch the property at all, and for that screen `react-window` is a fair choice. That is why the recipe names the properties rather than the package.

## The vendor, and why it is a stand-in

`examples.ledger.vendor` is a real windowing component written in raw React: it renders only a window, owns its own scroll offset in `useState`, and recycles nothing by itself. It is a stand-in for the npm package for the reason `imperative_sdk_dom_cljs_test` states about its own vendor — the artefact may not take an npm dependency for a witness.

Its foreignness is mechanical rather than claimed. `ledger.surface-cljs-test` reads its dependency edges off the ClojureScript analyzer and asserts that not one of them names anything of ours: no public door, no core, no native tier, no test kit. It could be lifted into an npm package without changing a character, which is what a consumer needs before believing a declaration is all their own virtualizer will need.

## The evidence

| suite | lane | what it owns |
|---|---|---|
| `ledger.l0-cljs-test` | `:node-test` | the model through a real frame, and the window arithmetic every DOM count below is derived from |
| `ledger.a11y-cljs-test` | `:node-test` | roles, resolved names, `aria-rowindex`, the moving `aria-pressed`, and the unnamed-control sweep with its sabotage |
| `ledger.surface-cljs-test` | `:node-test` | the import discipline, the four-door roster, and the vendor's own foreignness |
| `ledger.virtualized-dom-cljs-test` | `:browser-test` | the mounted claims: windowing, keyed identity across a scroll, focus continuity, announced position under scroll, body counts at two model sizes, teardown |

Two claims a reader must not conflate, and the DOM suite reports them separately so that a failure says which half moved:

- **Windowing bounds the mounted rows.** A hundred records and ten thousand put the same twenty-four rows in the document; the same rows with no virtualizer put a hundred and three hundred.
- **The read topology bounds the rows a change notifies.** One keystroke runs one row body at both model sizes; a three-row scroll runs the rows that entered plus the status line.

A screen with the first and not the second re-renders every mounted row on every keystroke. A screen with the second and not the first mounts ten thousand DOM subtrees and then updates one. The coarse-read sabotage that makes the second claim red is `examples.grid.scaling-dom-cljs-test`'s and is not repeated here; what this screen adds is the window as the bound.

## What was not needed

No `n/defcomponent`, no `:ref`, no effect, no imperative handle, no memo hint, no second root and no second state owner. The vendor's scroll offset is React state and dies with the fiber; the application holds no reactive cell of its own anywhere.

Nor was a `re-frame.hicasso.virtual` module extracted. The [specification.md](specification.md#7-complete-use-case-coverage) §7 second-caller gate applies and the caller count is one: this screen. What a second consumer would share with it is the four rules on this page, and prose is exactly the right carrier for a rule whose whole content is *which value to put at `:key`*. If a second screen arrives and copies the pin plumbing rather than the reasoning, that is the moment to revisit — and the [requirements mine](requirements-mine.md) row is where the count is kept.
