# infinite_feed — EP-0021 infinite-resource worked example

A single growing feed — a **load-more / infinite-scroll timeline** — built on
the first-class **infinite resource** primitive ([EP-0021](../../../docs/EP/EP-0021-infinite-resources.md),
[Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds)).
Composed as proper re-frame2: a `reg-resource` with `:infinite true`, owned by
the route, read entirely through the **passive** infinite subscription family,
with accumulation driven by a **causal** `:rf.resource/load-more` event. The
worked companion to the guide's load-more half,
[docs/resources/how-to/paginate-a-feed.md](../../../docs/resources/how-to/paginate-a-feed.md).

This is the **complement** to the numbered-pagination model
([Spec 016 §Paginated and previous data](../../../spec/016-Resources.md#paginated-and-previous-data),
demonstrated as a route in [`reagent/realworld_resources/`](../realworld_resources/)):
numbered pages keep each page an **independent** entry (the page is in
`:params`, the identity is the page) addressed as "go to page N"; an infinite
feed keeps the **accumulation together** as one entry and the next cursor is
always available from the tail. Both are legitimate; an app picks per feed.

## What this demonstrates

The four facts the infinite primitive surfaces, in one cohesive app:

- **One feed, many pages.** `:infinite true` plus a `:next-page-param`
  derivation makes the resource a growing ordered page sequence held as **one**
  cache entry (one owner set, one freshness clock, one GC clock, one Xray row).
  The route's `:resources` metadata ensures **page 0** on entry under a
  `[:route route-id nav-token]` owner; the view reads the merged list passively.
- **Load-more is a causal event.** The "Load more" button dispatches
  `[:rf.resource/load-more …]`. The view never fetches and **never advances a
  cursor** — the runtime derives the next page param from the loaded tail (the
  re-frame2 divergence from TanStack `fetchNextPage()` / SWR `setSize`: views
  stay passive, the cursor lives in the runtime entry).
- **The terminal is `nil`.** `:next-page-param` returning `nil` is the single
  end-of-feed signal; the view reads `:has-next-page?` and shows an end-of-feed
  marker instead of the button. (The demo's last page's next-cursor is `nil`.)
- **The page-error channel.** A load-more failure keeps **every accumulated
  page visible** and surfaces `:page-error` ("couldn't load more — retry")
  while the feed stays `:loaded`. A feed's page fetches all lower the same way,
  so a **page 0** first-load failure lands on `:page-error` too — the feed
  never collapses to the scalar `:error` state. The view reads `:has-data?`
  alongside `:page-error` to split the full error screen (no data) from the
  inline retry (data already shown). (The scalar `:error` / `:refresh-error`
  fields stay nil for a feed.)

The view reads the combined `[:rf.resource/infinite-state …]` view-model —
`:items` (the merged flat list, the **headline** read), `:has-next-page?`,
`:fetching-next?` (a load-more in flight, distinct from a whole-feed
`:fetching?`), `:page-error`, `:loading?`, `:has-data?` — and dispatches **one**
causal event. There is **no app-db list slice, no `:loading-more?` flag, no
cursor threading, and no append reducer** — the runtime owns all of it. (Before
EP-0021 an app hand-rolled every one of those by hand; that is the conspicuous
gap this primitive closes.)

**Enveloped pages need a `:page->items` accessor.** This feed's pages are
`{:items [...] :page-info {…}}`, not bare vectors, so the resource declares the
**required** `:page->items` accessor (`:items`). The runtime is **loud over
guessing**: a non-vector page with no accessor raises
`:rf.error/infinite-missing-page-accessor` at the merge. A feed whose pages are
already bare vectors needs no accessor (they flatten by identity).

**Scope is the fail-closed leak boundary.** This feed is public — the same for
every viewer — so it carries the explicit, auditable `:scope :rf.scope/global`
claim. A per-user feed would carry a scope resolver instead.

**No backend ships with the example.** It overrides `:rf.http/managed` with a
**per-cursor** canned stub that synthesises one enveloped page per request
cursor and delegates to the framework-shipped `:rf.http/managed-canned-success`
(Spec 014 §Testing) — the same enveloped reply shape a cursor-paginated server
would produce, so every page fetch exercises a real fetch, in-flight dedupe,
generation/stale suppression, and the passive status flow (a 140 ms delay lets
the load-more spinner render before each page lands).

## Status

The infinite-resource runtime has **landed** (EP-0021 waves 1–4): the
`:infinite` registration grammar + the required `:next-page-param` gate, the
durable feed entry (`:data` = the ordered page vector), the causal
`:rf.resource/load-more` event + the page reply handlers (append, cursor
advance, the `:page-error` channel, the R6 window-preserving refetch), and the
framework-owned memoised infinite subscription family (`:rf.resource/items` /
`:pages` / `:infinite-state` / `:has-next-page?` / `:fetching-next?` /
`:page-count` / `:page-error`) are all real and operational.

## Deferred — not built here

- **Prepend (`load-prev`).** The `:prev-page-param` derivation **mirror** is
  defined in the runtime (R7), but the prepend event `:rf.resource/load-prev`
  is deferred until a consumer needs it; v1 is next-direction `load-more` only.
  This example is a one-directional next-only feed.
- **In-place item patching.** A mutation touching an item inside the feed
  invalidates the **whole feed** (coarse, correct — R4); patching one item in
  place is the deferred optimistic axis.
- **An auto-loading sentinel** (`IntersectionObserver`). The guide's load-more
  section documents the frame-handle pattern for it; this example uses an
  explicit button to keep the load-more cause visible.

## Files

```
infinite_feed/
  core.cljs    — the :infinite resource, the per-cursor canned :rf.http/managed
                 stub, routes (route entry ensures page 0), the passive
                 infinite-state view + causal load-more, mount.
  index.html   — minimal host page.
```

The example synthesises its server replies **in-app** via the canned
`:rf.http/managed` stub (delegating to `:rf.http/managed-canned-success`), so
there is **no `api/` asset to stage** — the per-cursor pages are routed by the
stub, not by a static file tree.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/infinite-feed
```

The watch build emits `main.js` into `out/examples/infinite-feed/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared assets it
references under [`../../_shared/`](../../_shared/)) alongside it, then serve
`out/examples/infinite-feed/` over HTTP. (`npm run test:adapter-smokes` does not build
this example — it compiles and serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)

## Coverage

The example tree is test-free, but this example's wiring is pinned by
a direct headless CLJS fixture, **`re-frame.infinite-feed-example-cljs-test`**
(`implementation/adapters/reagent/test/re_frame/`, run by `npm run test:cljs`).
It requires this example's production `infinite-feed.core` and drives the
composition directly: route entry ensures **page 0** under a `[:route …]` owner
(the view reads `:loading?` then the merged `:items`); a causal
`:rf.resource/load-more` appends the next page and advances the cursor (the view
reads `:has-next-page?` / `:fetching-next?`); the demo's last page's `nil`
next-cursor flips `:has-next-page?` false (the end-of-feed marker); and a
load-more failure keeps the feed and surfaces `:page-error` (the third channel).
The generic infinite-resource runtime contract (the FSM transitions, dedupe,
stale suppression, the R6 refetch opt-ins, the loud missing-accessor merge) is
pinned in `implementation/resources/test/`. See the
[coverage table](../README.md#coverage-level-per-reagent-example).

## Cross-references

- [`docs/EP/EP-0021-infinite-resources.md`](../../../docs/EP/EP-0021-infinite-resources.md) — the EP (the accepted design + the R1–R8 rulings).
- [`spec/016-Resources.md §Infinite resources and load-more feeds`](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds) — the normative spec.
- [`docs/resources/how-to/paginate-a-feed.md`](../../../docs/resources/how-to/paginate-a-feed.md) — the guide (numbered-vs-infinite).
- [`examples/reagent/resources/`](../resources/) — the single-page resource lifecycle (ensure / refetch / owners / causes) this feed builds on.
- [`examples/reagent/realworld_resources/`](../realworld_resources/) — the numbered-pagination counterpart (the `:keep-previous?` model).
