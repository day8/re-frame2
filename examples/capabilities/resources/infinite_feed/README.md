# A feed that grows as you load more

This example is a feed: a list of items with a **Load more** button at the
bottom. Click the button and the next batch of items appends to the list you
already have. Keep clicking and the feed keeps growing, one page at a time,
until it runs out — then the button is replaced by an end-of-feed marker. Each
click shows a brief spinner while the page loads. There's no backend to run: a
canned dataset in the page stands in for the server, so you just start it and
click.

> **The view reads a list and renders it. Everything else lives in the runtime.**

That's the one idea to carry away — **the view does almost nothing.** Clicking
"Load more" dispatches a single [event](../../../../docs/guide/glossary.md#event)
that carries no page number. The view holds no cursor, no `:loading-more?` flag,
no list slice to append to, and no append reducer. All of that — the accumulated
pages, the cursor for the next page, the in-flight tracking, the end-of-feed
signal — lives inside the [resource](../../../../docs/resources/glossary.md#resource)
the runtime owns. The feed is one cache entry that grows; the view just watches
it grow. That inversion is the whole point of the primitive.

It's plain re-frame2: a `reg-resource` with `:infinite true`, owned by the route,
read through the **passive** infinite [subscription](../../../../docs/guide/glossary.md#subscription)
family, with growth driven by a **causal** `:rf.resource/load-more`
[event](../../../../docs/guide/glossary.md#event). It's the worked companion to the
guide's load-more half,
[docs/resources/how-to/paginate-a-feed.md](../../../../docs/resources/how-to/paginate-a-feed.md).

## Two ways to paginate

There are two ways to paginate, and this is the second.

- **Numbered pages** ([Spec 016 §Paginated and previous data](../../../../spec/016-Resources.md#paginated-and-previous-data),
  shown as a route in [`reagent/realworld_resources/`](../../../real-apps/realworld_resources/)).
  The page number lives in `:params`, so each page is its own cache entry you
  navigate *to*. You jump around the pages.
- **One growing feed** — this example. The whole accumulation stays together as a
  single entry. You never name a page; you just ask for *more*, and the runtime
  knows the next cursor because it's sitting in the tail of what you've loaded.

Both are legitimate. An app picks per feed.

## What this demonstrates

Four facts fall out of the infinite primitive, and this one small app surfaces
all of them.

- **One feed, many pages.** `:infinite true` plus a `:next-page-param`
  derivation turns the resource into a growing, ordered sequence of pages held
  as **one** cache entry — one owner set, one freshness clock, one GC clock, one
  [Xray](../../../../docs/guide/glossary.md#xray) row. The timeline route's
  `:resources` metadata fetches **page 0** on entry, under an owner keyed
  `[:route route-id nav-token]`. The view reads the merged list and never knows
  there was paging involved.
- **Load-more is a causal event, not a method call.** The "Load more" button
  dispatches `[:rf.resource/load-more …]` carrying a `:cause` (`[:user
  :feed/load-more]`) and, pointedly, **no `:owner`**. The route already owns the
  feed for its whole lifetime; load-more just extends that one owned entry rather
  than minting a second lease ([owner is lifetime; cause is
  explanation](../../../../docs/resources/glossary.md#owner--cause)). The view never
  fetches and **never advances a cursor** — the runtime derives the next page
  param from the loaded tail. (If you've reached for TanStack's `fetchNextPage()`
  or SWR's `setSize`, this is the re-frame2 version of the same move, except the
  view stays passive and the cursor lives in the runtime entry.)
- **The end of the feed is `nil`.** When `:next-page-param` returns `nil`, that
  *is* the end-of-feed signal — there's no separate "done" flag to keep in sync.
  The view reads `:has-next-page?` and swaps the button for an end-of-feed
  marker. (In this demo the last page's next-cursor comes back `nil`, so the feed
  genuinely runs out.)
- **First-load failure and load-more failure are separate.** An infinite feed
  carries **three** error channels, and they're deliberately *not* the same
  error ([Spec 016 §Causal event](../../../../spec/016-Resources.md#causal-event--rfresourceload-more-r2)):
  - A **page-0 first load** that fails with no data yet settles the scalar
    `:error` channel (`:status :error`, like an ordinary scalar resource). The
    view shows a full error screen, because there's nothing else to show.
  - A **load-more failure** (page N where N > 0, so pages are already on screen)
    settles the separate `:page-error` channel. Every accumulated page **stays
    visible**, the feed stays `:loaded`, and an inline "couldn't load more — tap
    retry" affordance appears under the list. A hiccup mid-scroll never blanks
    the page you were reading.
  - The third channel, `:refresh-error`, records a failed *whole-feed*
    background refresh. This next-only demo never triggers one, so it stays
    `nil`.

  The two channels the **view reads** stay separate: `:error` drives the full
  screen, `:page-error` drives the inline retry. They're never conflated.

The view reads the combined `[:rf.resource/infinite-state …]` view-model —
`:items` (the merged flat list, the main read), `:has-next-page?`,
`:fetching-next?` (a load-more in flight, distinct from a whole-feed
`:fetching?`), `:page-error`, `:loading?`, `:has-data?` — and dispatches **one**
causal event. Worth restating, because it's the payoff: there is **no app-db
list slice, no `:loading-more?` flag, no cursor threading, and no append
reducer**. The runtime owns every bit of it. Hand-rolling all four on every feed you build
is exactly the work this primitive removes.

**Enveloped pages need a `:page->items` accessor.** Real paginated endpoints
rarely hand you a bare array — they wrap it. This feed's pages arrive as `{:items
[...] :page-info {…}}`. So the resource declares the **required** `:page->items`
accessor (here, `:items`) to tell the runtime how to pull the rows out of each
page before flattening them into the merged list. The runtime **raises rather
than guesses**: hand it a non-vector page with no accessor and it raises
`:rf.error/infinite-missing-page-accessor` right at the merge, rather than
silently producing a list of nothing. (A feed whose pages are already bare
vectors needs no accessor — those flatten by identity.)

**Scope is the fail-closed leak boundary.** This feed is public — the same rows
for every viewer — so it carries the explicit, auditable
[`:scope`](../../../../docs/resources/glossary.md#scope) `:rf.scope/global` claim.
The claim is part of the cache identity, so it's there on purpose and an auditor
can see it. A per-user feed would carry a scope resolver instead, and one
principal's feed could never surface in another's cache.

**No backend ships with the example.** Rather than mock `js/fetch`, it overrides
the `:rf.http/managed` [effect](../../../../docs/guide/glossary.md#effect) with a
**per-cursor** canned stub. The stub reads the `:cursor` request param, slices a
26-row demo dataset, and returns a `{:items [...] :page-info {:next-cursor …}}`
envelope — the *same* shape a real cursor-paginated server would produce. The
reply still flows through the real
[managed-HTTP](../../../../docs/resources/glossary.md#managed-http) path, so every
page fetch exercises a genuine fetch, in-flight dedupe, generation/stale
suppression, and the passive status flow — none of that is faked. A small 140 ms
delay lets the load-more spinner render before each page lands.

## Deferred — not built here

- **Prepend (`load-prev`).** The prepend event `:rf.resource/load-prev` is
  deferred until a consumer needs it; this example is a one-directional,
  next-only feed.
- **In-place item patching.** A mutation touching an item inside the feed
  invalidates the **whole feed** (coarse, but correct); patching one item in
  place is a separate optimistic axis not shown here.
- **An auto-loading sentinel** (`IntersectionObserver`). This example uses an
  explicit button to keep the load-more cause visible.

## Files

```
infinite_feed/
  core.cljs    — the :infinite resource, the per-cursor canned :rf.http/managed
                 stub, routes (route entry ensures page 0), the passive
                 infinite-state view + causal load-more, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/infinite-feed
```

Then open the example's [`index.html`](index.html) over HTTP. Click **Load
more** and watch the next batch of items append; keep clicking until the feed
runs out and the button turns into an end-of-feed marker.

## Cross-references

- [`docs/EP/EP-0021-infinite-resources.md`](../../../../docs/EP/EP-0021-infinite-resources.md) — the EP (the accepted design + the R1–R8 rulings).
- [`spec/016-Resources.md §Infinite resources and load-more feeds`](../../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds) — the normative spec.
- [`docs/resources/how-to/paginate-a-feed.md`](../../../../docs/resources/how-to/paginate-a-feed.md) — the guide (numbered-vs-infinite).
- [`examples/capabilities/resources/resources/`](../resources/) — the single-page resource lifecycle (ensure / refetch / owners / causes) this feed builds on.
- [`examples/real-apps/realworld_resources/`](../../../real-apps/realworld_resources/) — the numbered-pagination counterpart (the `:keep-previous?` model).
