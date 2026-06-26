# infinite_feed — EP-0021 infinite-resource worked example

A single growing feed — a **load-more / infinite-scroll timeline** — built on
the first-class **infinite resource** primitive ([EP-0021](../../../docs/EP/EP-0021-infinite-resources.md),
[Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds)).

Here's the one idea worth carrying away: the view does almost nothing. It reads
a list and renders it, and clicking "Load more" dispatches a single event that
carries no page number. No cursor lives in the view. No `:loading-more?` flag
sits in [app-db](../../../docs/guide/glossary.md#app-db). There is no list slice
to append to and no append reducer to write. All of that — the accumulated
pages, the cursor that knows where the next page starts, the in-flight tracking,
the where-does-it-end signal — lives inside the [resource](../../../docs/resources/glossary.md#resource)
the runtime owns. The feed is one cache entry that grows; the view just watches
it grow. That inversion is the whole point of the primitive, and it's the thing
to notice as you read the source.

It's composed as proper re-frame2: a `reg-resource` with `:infinite true`, owned
by the route, read entirely through the **passive** infinite [subscription](../../../docs/guide/glossary.md#subscription)
family, with accumulation driven by a **causal** `:rf.resource/load-more`
[event](../../../docs/guide/glossary.md#event). It's the worked companion to the
guide's load-more half,
[docs/resources/how-to/paginate-a-feed.md](../../../docs/resources/how-to/paginate-a-feed.md).

There are two honest ways to paginate, and this example is the second one. The
first — **numbered pages** ([Spec 016 §Paginated and previous data](../../../spec/016-Resources.md#paginated-and-previous-data),
shown as a route in [`reagent/realworld_resources/`](../realworld_resources/))
— treats each page as its own thing: the page number lives in `:params`, so
"page 3" is a distinct cache entry you navigate *to*, and going back to page 2
is a separate (cached) read. The second — this one — keeps the whole
**accumulation together** as a single entry: you never address a page by number,
you just ask for *more*, and the runtime always knows the next cursor because
it's sitting right there in the tail of what you've already loaded. Numbered
pagination is a map you jump around on; an infinite feed is a tape you keep
pulling. Both are legitimate, and an app picks per feed.

## What this demonstrates

Four facts fall out of the infinite primitive, and this one small app surfaces
all of them.

- **One feed, many pages.** `:infinite true` plus a `:next-page-param`
  derivation turns the resource into a growing, ordered sequence of pages held
  as **one** cache entry — one owner set, one freshness clock, one GC clock, one
  [Xray](../../../docs/guide/glossary.md#xray) row. The timeline route's
  `:resources` metadata ensures **page 0** on entry, under an owner keyed
  `[:route route-id nav-token]`; the view reads the merged list passively and
  never knows there was paging involved.
- **Load-more is a causal event, not a method call.** The "Load more" button
  dispatches `[:rf.resource/load-more …]` carrying a `:cause` (`[:user
  :feed/load-more]`) — and, pointedly, **no `:owner`**: the route already owns
  the feed for its whole lifetime, and load-more just extends that one owned
  entry rather than minting a second lease ([owner is lifetime; cause is
  explanation](../../../docs/resources/glossary.md#owner--cause)). The view
  never fetches and **never advances a cursor**; the runtime derives the next
  page param from the loaded tail. (If you've reached for TanStack's
  `fetchNextPage()` or SWR's `setSize` before, this is the re-frame2 version of
  the same move, except the view stays passive and the cursor lives in the
  runtime entry instead of in component state.)
- **The terminal is `nil`.** When `:next-page-param` returns `nil`, that *is*
  the end-of-feed signal — there's no separate "done" flag to keep in sync. The
  view reads `:has-next-page?` and swaps the button for an end-of-feed marker.
  (In this demo the last page's next-cursor comes back `nil`, so the feed
  genuinely runs out.)
- **First-load failure and load-more failure are separate channels.** An
  infinite feed carries **three** error channels, and they're deliberately *not*
  the same error ([Spec 016 §Causal event](../../../spec/016-Resources.md#causal-event--rfresourceload-more-r2)).
  A **page-0 first load** that fails with no data yet settles the scalar
  `:error` channel (`:status :error`, exactly like an ordinary scalar resource)
  — and the view shows a full error screen, because there's nothing else to
  show. A **load-more failure** (page N where N > 0, so you already have pages
  on screen) is the separate `:page-error` channel: every accumulated page
  **stays visible**, the feed stays `:loaded`, and an inline "couldn't load
  more — tap retry" affordance appears under the list. A hiccup mid-scroll
  never blanks the page you were reading. (The third channel, `:refresh-error`,
  records a failed *whole-feed* background refresh — the inherited scalar
  channel; this next-only demo never triggers a whole-feed refresh, so it stays
  `nil`.) The two channels the **view reads** are kept separate — `:error`
  drives the full screen, `:page-error` drives the inline retry — and they're
  never conflated.

The view reads the combined `[:rf.resource/infinite-state …]` view-model —
`:items` (the merged flat list, and the **headline** read), `:has-next-page?`,
`:fetching-next?` (a load-more in flight, which is distinct from a whole-feed
`:fetching?`), `:page-error`, `:loading?`, `:has-data?` — and dispatches **one**
causal event. Worth restating, because it's the payoff: there is **no app-db
list slice, no `:loading-more?` flag, no cursor threading, and no append
reducer**. The runtime owns every bit of it. Before EP-0021 you hand-rolled all
four by hand on every feed you built; closing that gap is precisely what this
primitive is for.

**Enveloped pages need a `:page->items` accessor.** Real paginated endpoints
rarely hand you a bare array — they wrap it: this feed's pages arrive as `{:items
[...] :page-info {…}}`. So the resource declares the **required** `:page->items`
accessor (here, `:items`) to tell the runtime how to pull the rows out of each
page before flattening them into the merged list. The runtime is **loud over
guessing**: hand it a non-vector page with no accessor and it raises
`:rf.error/infinite-missing-page-accessor` right at the merge, rather than
silently producing a list of nothing. (A feed whose pages are already bare
vectors needs no accessor — those flatten by identity.)

**Scope is the fail-closed leak boundary.** This feed is public — the same rows
for every viewer — so it carries the explicit, auditable
[`:scope`](../../../docs/resources/glossary.md#scope) `:rf.scope/global` claim.
The claim is part of the cache identity, so it's there on purpose and an auditor
can see it; a per-user feed would carry a scope resolver instead, and one
principal's feed could never surface in another's cache.

**No backend ships with the example.** Rather than mock `js/fetch`, it overrides
the `:rf.http/managed` [effect](../../../docs/guide/glossary.md#effect) with a
**per-cursor** canned stub: the stub reads the `:cursor` request param, slices a
26-row demo dataset, and returns a `{:items [...] :page-info {:next-cursor …}}`
envelope — the *same* shape a real cursor-paginated server would produce — then
hands it to the framework-shipped `:rf.http/managed-canned-success`
(Spec 014 §Testing). Because the reply flows through the real
[managed-HTTP](../../../docs/resources/glossary.md#managed-http) path, every page
fetch exercises a genuine fetch, in-flight dedupe, generation/stale suppression,
and the passive status flow — none of that is faked. A small 140 ms delay (via
the canned fx's `:after-ms`, dispatched through framework `:dispatch-later`, so
it stays trace-visible and time-travel-safe — never raw `js/setTimeout`) lets
the load-more spinner actually render before each page lands.

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
load-more failure keeps the feed and surfaces `:page-error` (the inline-retry
channel, distinct from a first-load `:error`).
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
