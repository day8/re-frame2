# EP-0021: Infinite Resources And Load-More Feeds

Status: final
Type: standards-track

> **Graduated `accepted → final` 2026-06-18 (Mike, operator graduation).**
> This EP added a first-class **infinite resource** primitive to Spec 016:
> a managed resource whose value is an ordered, growing **sequence of pages**
> accumulated by repeated `load-more` events, with a declarative
> next/previous-page-param model, a merged-list + page-metadata subscription
> contract, and refetch/invalidation/scope/GC semantics consistent with the
> existing single-page resource. Its normative home is
> [`spec/016-Resources.md`](../../spec/016-Resources.md) (the `:infinite`
> registration key reserved there since EP-0003). The seven open design
> questions plus the `:request` signature are ruled (Mike, 2026-06-17); see
> [§Resolved Decisions](#resolved-decisions). The `spec/016` + `Spec-Schemas`
> amendments, the runtime implementation (registry / state / events / subs /
> SSR-restore / Xray), the cursor-egress fix, the tooling, the guide rewrite,
> and the flagship example have all landed across the seven implementation
> waves plus the two graduation review tails. The §Specification and §Bead Plan
> sections below are the design record; where they and the spec differ, the
> spec governs. `final` asserts the **decisions are settled** and the normative
> home governs.

## Abstract

Spec 016 documents **numbered/keyed pagination** well: every page, filter,
sort, and cursor goes into `:params`, each page is an ordinary independent cache
entry, and `:keep-previous?` keeps the prior page visible while the next first-
loads ([spec/016-Resources.md §Paginated and previous data](../../spec/016-Resources.md#paginated-and-previous-data)).
That model is correct for tables and "go to page N" UIs, where the user names a
discrete page and the app shows exactly that page.

It does **not** serve the **load-more / infinite-scroll feed**, where the user
accumulates pages: page 1, then page 1+2, then page 1+2+3, rendered as one
growing list, with the *next* page param derived from the *last* page's data
(a cursor, an after-id, an offset count). Today an app builds that by hand: an
app-db list slice, an event trio per page, manual cursor threading, manual
append, and manual coherence with the resource cache. EP-0016's RealWorld
dogfood confirmed this is the one remaining mainstream server-state pattern the
resource runtime makes the app rebuild from primitives
([EP-0016 §Non-Goals](EP-0016-resource-mutation-completion.md#non-goals): "No
pagination primitives").

This EP proposes the missing primitive, aligned to the two gold-standard
references: **TanStack Query `useInfiniteQuery`** (a `{ pages, pageParams }`
structure, `getNextPageParam` / `getPreviousPageParam`, `fetchNextPage`) and
**SWR `useSWRInfinite`** (a `getKey(index, previousPageData)` page-key function
and a `size` cursor). The proposed re-frame2 shape keeps the resource center
intact — one scoped identity, declarative request, passive subscriptions,
runtime-owned cache — and expresses the only genuinely new fact (an ordered
window of pages) as a single resource instance whose `:data` is the page
sequence, exposed to views through a merged-list + page-metadata projection.

## Motivation

### Why a bead is not enough

Where the accumulated pages **live** (one resource entry whose `:data` is a
vector of pages, vs N per-page entries plus an index, vs an app-db slice) and
how they are **exposed to subscriptions** (a merged list? the pages array? page
metadata?) are load-bearing contract decisions with several defensible
alternatives, each with different consequences for invalidation, GC, structural
sharing, SSR, and tooling. That is precisely the EP-worthiness bar
([EP-0009](EP-0009-the-ep-process.md)): a feature-level public-contract decision
with unresolved alternatives. The decision interacts with the already-`final`
resource identity, scope, FSM, and work-ledger contracts, so it must be designed
against them, not bolted on.

### The two shapes are genuinely different

| | Numbered pagination (shipped) | Infinite / load-more (this EP) |
|---|---|---|
| User mental model | "show page N" / "this filter" | "show me more" |
| Cache identity | one entry **per page** (page in params) | one entry **per feed** (pages inside) |
| Next page param | named by the user/route | **derived from the last page's data** |
| Render | replace | **append / accumulate** |
| `:keep-previous?` | prior page visible while next loads | not the mechanism — pages persist by design |

The numbered model deliberately keeps each page an independent entry so "page 3"
is cacheable, shareable, route-addressable, and GC'd on its own. The infinite
model deliberately keeps the *accumulation* together so the merged list is one
reactive value and the next-page cursor is always available from the tail. Both
are legitimate; an app picks per feed. This EP adds the second without
disturbing the first.

### What "fall back to app-db composition" costs today

A hand-rolled infinite feed re-implements, per feed: the page list slice, a
`:loading-more?` flag, a `:cursor`/`:next-page-param` slice, the
load-more-success append reducer, the "we've reached the end" flag, dedupe of an
in-flight load-more, reset-on-filter-change, and — the hard part — keeping that
app-db slice coherent with the resource cache when an item inside the feed is
mutated/invalidated elsewhere. EP-0003 removed exactly this class of code for
single-page reads; the infinite case is the conspicuous remaining hole.

## Goals / Non-Goals

### Goals

- Add a **first-class infinite resource** registration kind (the `:infinite`
  key reserved in [spec/016-Resources.md §Resource registration spec](../../spec/016-Resources.md#resource-registration-spec)),
  built on the existing resource identity / scope / FSM / work-ledger / SSR /
  restore contracts — not a parallel subsystem.
- Define the **page-param model**: how the next (and optionally previous) page
  param is derived from the accumulated pages, including the "no more pages"
  terminal signal.
- Define how **pages accumulate** as one resource instance's durable value, and
  the durable cache-entry shape that holds them.
- Define the **subscription contract**: the merged list, the raw pages, the
  page metadata (`:has-next-page?`, `:fetching-next-page?`, page count), and how
  these compose with the existing `:rf.resource/*` family.
- Define the **load-more / fetch-next** causal event and its FSM interaction
  (fetching-next is a refresh-class transition, not a first-load reset).
- Define **refetch / invalidation** of an infinite resource: what "refetch the
  feed" means (re-fetch page 1 and discard the tail? refetch every accumulated
  page?), and how tag invalidation reaches items inside the feed.
- Define **instance-keying + scope** interaction: a feed is one scoped resource
  key; filter/sort changes produce a *different* feed instance, not a mutation
  of the current accumulation.
- Define **tooling surfacing**: the Xray/trace evidence for page accumulation,
  next-page-param resolution, and load-more work.

### Non-Goals

- **No numbered-pagination replacement.** `:keep-previous?` + per-page entries
  stay exactly as documented; this EP is additive and orthogonal.
- **No normalized entity / graph cache.** Items inside a feed are not extracted
  into a normalized store; that remains the deferred Apollo/Relay-class slice
  ([spec/016-Resources.md §What Spec 016 does NOT cover](../../spec/016-Resources.md#what-spec-016-does-not-cover)).
- **No bidirectional realtime/streaming feed.** Optional `getPreviousPageParam`
  (prepend) is in scope as a *param-derivation* mirror; live push, websockets,
  and subscription-driven fetching are not.
- **No optimistic insert/remove inside a feed.** That composes with the deferred
  optimistic-rollback EP ([EP-0016 issue 9](EP-0016-resource-mutation-completion.md#open-issues)),
  not this one.
- **No new transport.** Infinite resources lower through the same Spec 014
  managed HTTP as every other resource; GraphQL/cursor-connection transports
  remain deferred.
- **No `:select`-style projection key.** Merged-list shaping is an ordinary
  subscription layered over the page sequence, per
  [spec/016-Resources.md §No `:select` key](../../spec/016-Resources.md#no-select-key).

## Relationships

- **[EP-0003](EP-0003-resource-queries.md) / [Spec 016](../../spec/016-Resources.md)
  (final).** This EP is a post-final additive amendment. It reserves and fills
  the `:infinite` registration key that Spec 016 lists as deferred
  ([§Resource registration spec](../../spec/016-Resources.md#resource-registration-spec),
  [§Deferred slices](../../spec/016-Resources.md#deferred-slices)). It does not
  reopen resource identity, scope, the FSM, the work ledger, route ownership, or
  SSR.
- **[EP-0016](EP-0016-resource-mutation-completion.md) (final).** Mutations that
  affect feed items invalidate by tag; this EP defines what tag invalidation
  *means* for an accumulated feed (see [§Refetch and invalidation](#refetch-and-invalidation-of-an-infinite-resource)).
  The named scope resolvers (D3) and per-target invalidation (D2) apply to
  infinite resources unchanged — a feed is a scoped resource like any other.
- **[EP-0004](EP-0004-subscription-inputs.md) (final).** The merged-list
  projection is an ordinary parametric subscription layered over the page
  sequence; per-feed view-models compose over `[:rf.resource/items …]` exactly
  as they compose over `[:rf.resource/data …]`.
- **[EP-0001](EP-0001-frame-partitions.md) / [Spec 016 §Cache home](../../spec/016-Resources.md#cache-home-and-write-authority)
  (final).** Accumulated pages live in the same runtime-db `:rf.runtime/resources`
  partition as every other resource entry; **never** an app-db slice (resolving
  Open Question 1 toward runtime-db is the recommendation, but the *exact entry
  shape* is the open decision).
- **[EP-0002](EP-0002-frame-target-resolution.md) (final).** A feed instance is
  frame-scoped and carries an explicit scope like any resource; no ambient
  default-frame fallback.
- **[EP-0012](EP-0012-path-optics-and-canonical-forms.md) (final).** Feed params
  (the *feed identity* params: filter/sort, **not** the per-page cursor) are
  canonical-EDN identities. The per-page param is **not** part of the feed's
  cache identity — see [§Part 2: page-param model](#part-2-page-param-model).
- **[EP-0015](EP-0015-frame-owned-egress-policy.md) (final).** Accumulated page
  data, page params (which may be cursors carrying ids), and the merged list all
  pass through the frame-owned egress projection; a feed's classification is
  owned by its **`:page-data-schema`, applied per page** on egress/SSR/tool
  projection (Resolved Decision R5) — the accumulated `:data` is a framework
  vector of pages and must not bypass per-page classification.

## Specification

> Normative-voiced for graduation ergonomics; binds nothing until accepted.

The proposal is **one decision** (a first-class infinite resource) with five
parts: registration, the page-param model, the durable cache shape, the causal
events, and the subscription contract.

### Part 1: registration — `:infinite`

An infinite resource is a resource registered with `:infinite true` plus a
page-param derivation. It reuses every existing required resource key
(`:params-schema`, `:scope`, `:request`) — the difference is that the
**reserved `:request` context** (the second argument, `nil`/empty for
non-infinite resources) now carries the resolved page context
(`:rf.resource/page-param`, `:rf.resource/page-index`), and the registration
declares how the next page param is derived. **No new `:request` arity is
introduced** — the page context rides the already-reserved context slot (see
[Resolved Decision R8](#resolved-decisions)).

```clojure
(rf/reg-resource
  :feed/timeline
  {:doc "Infinite home timeline (load-more)."

   :infinite true

   ;; The feed-IDENTITY params (filter / sort) — what makes two feeds distinct
   ;; cache instances. The per-page cursor is NOT here (it is :page-param).
   :params-schema   [:map [:filter :keyword]]
   :scope           {:from-db :app/session}      ;; EP-0016 D3 named resolver
   :page-data-schema :app/timeline-page          ;; validates ONE page

   ;; :request keeps its (params ctx) shape; the RESERVED ctx now carries the
   ;; resolved page context for THIS page (nil/empty for non-infinite resources).
   :request
   (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
     {:request {:method :get
                :url    "/api/timeline"
                :params (cond-> {:filter filter :limit 20}
                          page-param (assoc :cursor page-param))}
      :decode  :app/timeline-page})

   ;; Derive the NEXT page param from the last loaded page + all pages so far.
   ;; Returns nil to signal "no more pages" (the terminal). Aligns with
   ;; TanStack getNextPageParam(lastPage, allPages, lastPageParam, allPageParams).
   :next-page-param
   (fn [last-page _all-pages]
     (get-in last-page [:page-info :next-cursor]))   ;; nil => end of feed

   ;; OPTIONAL — bidirectional feeds (prepend). Mirror of :next-page-param.
   :prev-page-param
   (fn [first-page _all-pages]
     (get-in first-page [:page-info :prev-cursor]))

   :tags (fn [{:keys [filter]} _data] #{[:feed filter]})
   :stale-after-ms 60000
   :gc-after-ms    300000})
```

Rules (proposed, MUST):

- `:infinite true` makes `:next-page-param` **required**. The `:request` fn keeps
  its settled `(params ctx)` shape (Spec 016); for an infinite resource the
  **reserved `ctx`** carries `:rf.resource/page-param` (nil for the first page)
  and `:rf.resource/page-index`. Non-infinite requests still receive a nil/empty
  ctx unchanged — **no new arity** (see [Resolved Decision R8](#resolved-decisions)).
- The **first page** is fetched with `:page-param nil` (TanStack
  `initialPageParam` analogue; the framework default is nil, overridable via an
  optional `:initial-page-param`).
- `:next-page-param` is **pure** `(last-page all-pages) -> next-param-or-nil`.
  Returning `nil` is the canonical **terminal** signal (no more pages); the
  derived `:has-next-page?` is `(some? (:next-page-param …))`.
- `:page-data-schema` validates **one page's** decoded value (the per-page
  envelope) **and is the per-page egress/classification contract** applied per
  page on SSR/tool projection (Resolved Decision R5). `:data-schema` is **not**
  used for the accumulated vector.
- `:tags` on an infinite resource tag the **feed identity** and SHOULD also be
  derivable per item so item-level invalidation can reach into the feed (Open
  Question 4).

### Part 2: page-param model

The page-param is the single new concept. It is **not** part of the feed's cache
identity — two `load-more` calls on the same feed do not produce two cache keys;
they extend one entry. This is the deliberate divergence from numbered
pagination, where the page *is* in params and *is* the identity.

- **Identity params** (`:params`, schema-validated, canonical) name the feed:
  filter, sort, search query. Change them → a **different scoped resource key** →
  a different feed instance (the old accumulation is a separate, GC-eligible
  entry; the new feed first-loads page 1).
- **Page param** (`:page-param`) is internal sequencing state, derived by
  `:next-page-param` from loaded data, stored on the feed entry as the
  `:page-params` vector (one per accumulated page), and **never** part of the
  cache key.

This mirrors both references precisely:

- **TanStack** keeps `pageParams` *alongside* `pages` inside one query's cache
  entry; the query key is the feed identity, not the page. `getNextPageParam`
  derives the next param from the last page.
- **SWR** `useSWRInfinite` uses `getKey(index, previousPageData)` to compute each
  page's key and a `size` cursor; the *collection* is one infinite hook
  instance. re-frame2's `:next-page-param` is the `getKey`/`getNextPageParam`
  analogue, but the framework — not the view — owns the `size`/index cursor (the
  re-frame2 divergence: views are passive, so `size` lives in the runtime entry,
  advanced by a causal `load-more` event, not by a `setSize` call from render).

**Deliberate re-frame2 divergences from the references, called out:**

1. **Page param is runtime state, not a view-held cursor.** SWR's `size` and
   TanStack's `fetchNextPage()` are imperative calls from components. re-frame2
   keeps views passive (Spec 016 §Views stay passive): `load-more` is a causal
   event, the cursor advances in runtime-db, and the view only reads
   `:has-next-page?` and dispatches `[:rf.resource/load-more …]`.
2. **`nil` is the single terminal.** TanStack uses `undefined` from
   `getNextPageParam`; SWR ends when a page returns empty. re-frame2 standardises
   on `nil` from `:next-page-param` (loud, single rule), and additionally exposes
   `:has-next-page?` so the view never re-derives the terminal.
3. **Feed identity is fail-closed scoped** like every resource — the references
   put session/tenant into the query key; re-frame2 keeps scope a separate,
   fail-closed leak boundary ([Spec 016 §Scope resolution](../../spec/016-Resources.md#scope-resolution)).

### Part 3: durable cache shape (ruled — Resolved Decision R1)

> This was the **load-bearing decision** (Open Issue 1). It is now **ruled**
> ([Resolved Decision R1](#resolved-decisions)): one resource entry per feed,
> pages as an ordered vector inside it. The rejected alternatives (N per-page
> entries; an app-db slice) are recorded in [§Open Issues](#open-issues).

**Ruled: one resource entry per feed, pages stored as an ordered vector
inside it, in the existing `:rf.runtime/resources` partition.** The infinite feed
reuses the single-resource entry shape (`implementation/resources/src/re_frame/resources/state.cljc`
`empty-entry*`, around line 183) and refines `:data` to be the page sequence,
adding a small set of infinite-only facts:

```clojure
;; Durable cache entry for an infinite resource (proposed refinement of the
;; existing entry shape — Spec 016 §Status semantics / §Cache home).
{:resource/id    :feed/timeline
 :resource/key   [scope :feed/timeline {:filter :recent}]
 :infinite?      true
 :status         :loaded            ;; existing FSM, unchanged semantics
 ;; :data is the ORDERED PAGE SEQUENCE — the durable fact. One element per
 ;; accumulated page, in load order. The merged list is DERIVED in the subs
 ;; layer (never stored — Spec 016: derived values are not durable facts).
 :data           [<page-0-decoded> <page-1-decoded> …]
 :page-params    [nil <param-1> <param-2> …]   ;; one per page (page-0 = nil)
 :next-page-param <param-or-nil>               ;; derived after each load; nil = end
 :prev-page-param <param-or-nil>               ;; bidirectional only
 :error          nil                ;; first-load (page 0) failure envelope
 :refresh-error  nil                ;; background refresh failure (data kept)
 :page-error     nil                ;; a LOAD-MORE (page N>0) failure — see Part 4
 :loaded-at      <ms>  :stale-at <ms>  :invalidated-at nil
 :attempt 1 :generation 4 :request-id … :current-work …
 :tags #{[:feed :recent]} :active-owners #{[:route :route/home nav-token]}}
```

Why one-entry-with-pages over N-per-page-entries:

- The **merged list and the next cursor are one coherent reactive value** — a
  view reading the feed reads one entry, so structural sharing (`state.cljc`
  `entry-succeeded`, around line 671) keeps unchanged pages identical across a
  load-more, and the whole feed has one `:loaded-at` / `:stale-at` / owner set /
  GC clock.
- It matches both references' own internal model (`{ pages, pageParams }` is one
  cache entry).
- It keeps the existing FSM literally unchanged for the feed-level status (Open
  Question 2 only adds a *page-level* sub-status for load-more, orthogonal to the
  five states).

The cost it accepts (and the reason the decision is open): a feed entry's `:data`
is larger and grows, so GC and egress projection must reason about a growing
durable value; and item-level invalidation must reach inside the vector (Open
Question 4). The N-entries alternative inverts these trade-offs.

### Part 4: causal events — `load-more` / `fetch-next`

A new resource event extends the feed by one page. It is **causal** (Spec 016
§Views stay passive), reuses the work ledger (one work-ledger row per page
fetch, `:work/kind :resource`), and respects generation + stale suppression.

```clojure
[:rf.resource/load-more
 {:resource :feed/timeline
  :scope    {:from-db :app/session}     ;; resolved like any resource event
  :params   {:filter :recent}           ;; the FEED identity (not a page)
  :owner    [:route :route/home nav-token]
  :cause    [:user :feed/load-more]}]
```

FSM interaction (proposed):

- `load-more` on a `:loaded` feed that **has a next page** computes the next
  `:page-param` from the entry's tail, issues the managed request for that page,
  and transitions the feed to a **`:fetching` (refresh-class)** state — the
  existing FSM transition, because the feed already has data. The accumulated
  pages stay visible (no skeleton).
- A page-fetch **success** appends the decoded page to `:data`, appends its
  param to `:page-params`, recomputes `:next-page-param` / `:prev-page-param`,
  and returns the feed to `:loaded`. Structural sharing preserves all prior
  pages.
- A page-fetch **failure** is a **load-more failure**, not a feed first-load
  failure: the feed returns to `:loaded`, keeps all accumulated pages, and
  records `:page-error` (a third error channel beside `:error` /
  `:refresh-error`, so a view can show "couldn't load more — retry" without
  losing the feed). This mirrors EP-0016's first-load-vs-refresh error
  distinction, extended to the load-more axis.
- `load-more` with **no next page** (`:next-page-param` is nil) is a no-op that
  emits a trace (`:rf.resource/load-more-skipped`, reason `:no-next-page`); it
  never fires a request.
- A `load-more` while a page fetch is **already in flight** dedupes against the
  in-flight work (Spec 016 §dedupe), exactly as a duplicate `ensure` does.

`:rf.resource/ensure` on an infinite resource fetches **page 0 only** (first
load); it does not re-fetch the whole accumulation. `:rf.resource/refetch` is
[§Refetch and invalidation](#refetch-and-invalidation-of-an-infinite-resource).

### Part 5: subscription contract (ruled — Resolved Decision R3)

> Open Question 3, now **ruled** ([Resolved Decision R3](#resolved-decisions)):
> `:rf.resource/items` / `:rf.resource/pages` / `:rf.resource/infinite-state`
> are framework-owned memoised subscriptions, and `:page->items` is **required**
> for any non-vector/enveloped page (loud over guessing).

The existing `:rf.resource/state` / `:data` / `status` / `loading?` / `fetching?`
/ `stale?` / `error` / `has-data?` family (`subs.cljc` `register-subs!`, around
line 406) all apply to an infinite resource — but `:rf.resource/data` would
return the raw
page vector, which is rarely what a feed view wants. The proposal adds a small
infinite-specific projection family, all passive, all derived (never stored):

```clojure
;; The merged / flattened list — the everyday feed read. DERIVED from :data
;; (the page vector) by concatenating pages, with optional per-resource flatten.
[:rf.resource/items          {:resource :feed/timeline :scope … :params …}]

;; The raw page sequence (the TanStack `pages` analogue) — for views that need
;; page boundaries (e.g. "—— page break ——" or per-page headers).
[:rf.resource/pages          {:resource … :scope … :params …}]

;; Page metadata — the load-more UI state.
[:rf.resource/has-next-page? {:resource … :scope … :params …}]   ;; (some? next-param)
[:rf.resource/has-prev-page? {:resource … :scope … :params …}]
[:rf.resource/fetching-next? {:resource … :scope … :params …}]   ;; a load-more in flight
[:rf.resource/page-count     {:resource … :scope … :params …}]
[:rf.resource/page-error     {:resource … :scope … :params …}]   ;; last load-more failure
```

And a combined infinite view-model (the feed analogue of `:rf.resource/state`):

```clojure
@(rf/subscribe [:rf.resource/infinite-state {:resource :feed/timeline :scope … :params …}])
;; =>
{:status            :loaded
 :items             [<item> <item> …]   ;; merged list (the everyday read)
 :pages             [<page-0> <page-1> …]
 :page-count        3
 :has-next-page?    true
 :has-prev-page?    false
 :loading?          false               ;; first load (page 0), no data yet
 :fetching-next?    false               ;; a load-more in flight (pages stay visible)
 :fetching?         false               ;; whole-feed refresh in flight
 :stale?            false
 :has-data?         true
 :error             nil                 ;; page-0 first-load failure
 :refresh-error     nil
 :page-error        nil}                ;; last load-more failure
```

Divergence called out: the **merged list `:items` is the headline read**, not the
pages array — most feed views want the flat list and TanStack users immediately
`.flatMap(p => p.items)`. re-frame2 makes that the default projection (with
`:pages` available when boundaries matter), since the merge is a pure derivation
the framework can own and memoise once.

The flatten rule is per-resource and **loud, not magic** (ruled — see
[Resolved Decision R3](#resolved-decisions)): a page that *is already a vector*
flattens by identity, but a feed whose page is non-vector/enveloped
(e.g. `{:items [...] :page-info {…}}`) **MUST** declare a `:page->items`
accessor — the framework does not guess `:items`/`:data`. `:items` is then
`(into [] (mapcat page->items) pages)`. `:rf.resource/items`,
`:rf.resource/pages`, and `:rf.resource/infinite-state` are framework-owned
memoised subscriptions (the merge is a pure derivation the framework owns once),
not ordinary app subs over `:pages`.

### Worked example — an infinite feed view

```clojure
(rf/reg-view timeline-feed []
  (let [scope @(rf/subscribe [:app/session-scope])
        feed  @(rf/subscribe [:rf.resource/infinite-state
                              {:resource :feed/timeline :scope scope
                               :params {:filter :recent}}])]
    (cond
      (:loading? feed) [feed-skeleton]
      (and (:error feed) (not (:has-data? feed))) [feed-error (:error feed)]
      :else
      [:<>
       (for [item (:items feed)] ^{:key (:id item)} [feed-row item])
       (when (:page-error feed) [load-more-error (:page-error feed)])
       (cond
         (:fetching-next? feed) [spinner]
         (:has-next-page? feed)
         [:button {:on-click #(rf/dispatch
                                [:rf.resource/load-more
                                 {:resource :feed/timeline :scope scope
                                  :params {:filter :recent}
                                  :owner [:route :route/home nav-token]
                                  :cause [:user :feed/load-more]}])}
          "Load more"]
         :else [end-of-feed])])))
```

The view is passive: it reads the merged list + page metadata and dispatches a
causal `load-more`. The route owns the feed (page 0 ensured on entry); the
runtime owns accumulation.

### Route integration

A route declares an infinite resource exactly as it declares any resource
([Spec 016 §Route integration](../../spec/016-Resources.md#route-integration)) —
route entry ensures **page 0** (the first load), route leave releases the owner.
Load-more is a user-caused event during the route's lifetime, not a route plan
step. `:blocking?` blocks on page 0 only.

## Refetch and invalidation of an infinite resource

This is a genuine semantic decision (folded into the spec sketch; flagged as
Open Question 4 for the *item-reach* sub-decision).

- **`:rf.resource/refetch` of a feed** is governed by an explicit per-resource
  `:refetch` policy. The **ruled default is conservative: preserve the visible
  window** — the accumulated pages stay rendered until their replacement
  succeeds, so a focus/reconnect/invalidation-driven refetch never collapses a
  loaded feed back to page 0 (the failure mode of a hard discard-tail default).
  Two opt-ins ship **from day one** (ruled — see
  [Resolved Decision R6](#resolved-decisions)): `:refetch-all-pages?` re-fetches
  every accumulated page param in sequence (TanStack-parity), and
  `:refetch-window` bounds how much of the accumulation is refreshed. Pages
  persist by *accumulation*, not by being independently cached, so the
  window-preserving swap is coherent.
- **Tag invalidation** (`:rf.resource/invalidate-tags`, and EP-0016 mutation
  `:invalidates`) marks the feed entry stale by its **feed tag** (`[:feed
  :recent]`) → the feed refetches per the refetch rule above on the next ensure.
- **Item-level invalidation** (a mutation changes one item *inside* the feed) is
  Open Question 4: the recommendation is that a feed `:tags` fn may also be
  evaluated per-item so an item tag (`[:timeline-item id]`) maps to the feeds
  containing it, but *reaching into* the page vector to patch one item is the
  optimistic/patch axis deferred to a later EP — for v1, an item mutation
  **invalidates the feed** (coarse, correct) rather than patching in place.
- **Scope** invalidation (`clear-scope` on logout) drops the feed entry like any
  scoped entry — the whole accumulation goes, correctly, with the user.

## Restore, replay, SSR

The infinite feed entry is an ordinary durable resource entry, so it rides the
existing contracts unchanged:

- **SSR / hydration** ([Spec 016 §SSR and hydration](../../spec/016-Resources.md#ssr-and-hydration)):
  a blocking route serializes the accumulated pages (typically just page 0 from
  the server) through the same allowlist projection + egress walker; the client
  hydrates the page vector and load-more continues from the hydrated tail's
  `:next-page-param`.
- **Restore / replay** ([Spec 016 §Restore and replay](../../spec/016-Resources.md#restore-and-replay)):
  the page vector is durable and restores wholesale; an in-flight load-more is a
  non-terminal work-ledger row reconciled to dangling exactly as any in-flight
  resource fetch; the generation allocator stays monotonic so a late page reply
  cannot append to a post-restore feed.

No new restore/SSR rules are required — that is a direct benefit of
Part 3's one-entry-in-runtime-db recommendation. (The N-entries-per-page
alternative would need new SSR/restore reasoning about the page index.)

## Tooling and trace surfacing

The `:rf.resource/*` trace family ([Spec 016 §Xray and AI tooling](../../spec/016-Resources.md#xray-and-ai-tooling))
gains infinite-specific evidence (op names reserved now; the Xray panel spec
`tools/xray/spec/024-Resources-Panel.md` lands with the emit slice, per the
standing Xray-spec-currency rule):

- `:rf.resource/load-more` — a load-more was dispatched: feed key, resolved
  `:page-param`, current `:page-count`, work id.
- `:rf.resource/page-appended` — a page-fetch succeeded and was appended: page
  index, new `:page-count`, derived `:next-page-param` (or `:terminal? true`).
- `:rf.resource/load-more-skipped` — load-more with no next page (`:reason
  :no-next-page`) or a deduped in-flight (`:reason :in-flight`).
- `:rf.resource/page-failed` — a load-more page fetch failed (the `:page-error`
  channel; distinct from `:rf.resource/failed` first-load and
  `:rf.resource/refresh-failed`).

Xray's resource-instance table shows, for an infinite entry: page count,
next-page-param presence (`has-next?`), per-page params (egress-projected, since
cursors can carry ids), and the accumulated-size growth view (the cache-growth
surface already exists; a feed is its natural stress case).

## Rationale

Why a first-class primitive rather than "document the app-db pattern" (the
acceptance criteria's explicit fork):

- EP-0003 + EP-0016 already removed this class of code for every *other*
  server-state shape; leaving the most common feed shape as hand-rolled app-db
  composition is the conspicuous gap, and the gap is *exactly* the kind both gold
  standards ship as a headline feature.
- The only genuinely new fact — an ordered window of pages with a
  derived-from-data cursor — has a clean re-frame2 expression (one entry, pages
  as the durable value, load-more as a causal event, merged list as a derivation)
  that reuses every existing resource contract. The design is small.
- The alternative (canonical app-db pattern, documented and deferred) leaves the
  coherence-with-the-cache problem unsolved: a hand-rolled app-db feed cannot
  participate in tag invalidation, scope clearing, SSR, or restore without
  re-implementing them.

Why pages-in-one-entry over N-per-page-entries: the merged list and the cursor
are one reactive value; structural sharing, GC, owners, freshness, SSR, and
restore all stay single-entry contracts. The cost (a growing durable value;
item-reach needs vector traversal) is bounded and is itself the open question.

## Backwards Compatibility

Pre-alpha; additive. `:infinite` is a reserved-but-unused key today
([Spec 016 §Resource registration spec](../../spec/016-Resources.md#resource-registration-spec)),
so no resource declares it. Numbered pagination (`:keep-previous?`) is untouched.
No existing subscription or event changes shape. The only migration is
*opportunity*: hand-rolled app-db feeds in examples (if any) may move to the
primitive once it lands.

## Bead Plan / Reference Implementation

A proposed sequence (not a single PR); each graduation wave carries a
guide-impact assessment.

1. **Spec 016 amendments** (the normative home): the `:infinite` registration
   grammar, the page-param model, the durable entry refinement, the `load-more`
   event + FSM interaction, the infinite subscription family, refetch/invalidation
   semantics, and the trace additions. (Hot-zone file — sequential.)
2. **Registry + state**: `:infinite` validation in
   `implementation/resources/src/re_frame/resources/registry.cljc`; the entry
   refinement + page-append / next-param transitions in
   `implementation/resources/src/re_frame/resources/state.cljc` (sibling to
   `entry-succeeded` / `prior-loaded-sibling-key`).
3. **Events**: the `:rf.resource/load-more` handler in
   `implementation/resources/src/re_frame/resources/events.cljc` (page-param
   resolution, work-ledger row, dedupe, generation/stale suppression), and
   `refetch` reset semantics.
4. **Subs**: the infinite projection family in
   `implementation/resources/src/re_frame/resources/subs.cljc` (merged `:items`,
   `:pages`, page metadata, `:infinite-state`), memoised.
5. **SSR / restore**: confirm the page vector rides the existing projection +
   restore paths (likely zero new code under Part 3's recommendation — that is the
   acceptance test).
6. **Tooling**: emit the four new trace ops; Xray panel spec update
   (`tools/xray/spec/024-Resources-Panel.md`) in the same PR per the standing rule.
7. **Guide + dogfood**: rewrite `docs/guide/how-to/paginate-a-feed.md` to teach
   numbered-vs-infinite and the new primitive; add a flagship example feed; add
   conformance + small synthetic tests (not Playwright — per the standing
   Causa/Story CLJS-unit-test default).

## Open Issues

> The genuine design decisions for the operator to rule. **All seven are now
> ruled** (Mike, 2026-06-17), together with the `:request` signature; the
> resolutions are recorded in [§Resolved Decisions](#resolved-decisions) below.
> The questions are kept here for the record; the rows below are normative.

1. **Where do accumulated pages live, and in what exact shape?** (the headline
   decision.) **Ruled (R1):** **one resource entry in `:rf.runtime/resources`
   whose `:data` is an ordered page vector** ([Part 3](#part-3-durable-cache-shape-ruled--resolved-decision-r1)).
   Alternatives, **rejected:** (a) **N independent per-page entries** (page in params, like
   numbered pagination) plus a small **feed index entry** that lists the ordered
   page keys — pages stay individually cacheable/GC-able and item-invalidation is
   per-page-entry, at the cost of a second indirection and merged-list reads
   spanning N entries; (b) **app-db slice** (the deferred-pattern fork) — rejected
   here because it forfeits cache coherence, but it is the operator's call whether
   to defer entirely. **Sub-decision:** if (a), the index-entry-vs-pages-vector
   split changes the subscription contract (Part 5) materially.

2. **Does load-more get a page-level sub-status, or only the existing
   `:fetching`?** The recommendation reuses `:fetching` (refresh-class) for the
   feed status and exposes a derived **`:fetching-next?`** sub (a load-more in
   flight) distinct from `:fetching?` (a whole-feed refresh). Alternative: a
   genuine new entry status (`:fetching-next`) in the FSM — rejected as
   over-loading the five-state FSM, but flagged because it is a contract choice
   (the FSM is `final`).

3. **The merged-list contract: what is the headline read, and what is the default
   flatten?** Recommendation: `:rf.resource/items` (merged flat list) is the
   headline, `:rf.resource/pages` is available, and the per-resource
   `:page->items` accessor defaults to "the page if it is a vector, else its
   `:items` key." Open: is the default flatten worth the convention, or should
   `:page->items` be **required** for any feed whose page is not already a vector
   (loud over magic)? Also: should `:items` even be a framework sub, or is the
   merge an ordinary app subscription over `:pages` (the `:select` precedent —
   "projections are ordinary subscriptions")? The latter is more orthodox but
   loses the framework-owned memoised merge.

4. **What does an item-inside-the-feed mutation do?** Recommendation: **invalidate
   the whole feed** (coarse, correct, v1) and defer in-place patching to the
   optimistic/patch EP ([EP-0016 issue 9](EP-0016-resource-mutation-completion.md#open-issues)).
   Open: should v1 ship a per-item `:tags` evaluation so item tags map to
   containing feeds (enabling targeted feed invalidation), or is feed-tag
   invalidation enough until the patch EP?

5. **`:data-schema` vs `:page-data-schema` for an infinite resource.**
   Recommendation: `:page-data-schema` validates one page (the decode target);
   `:data-schema` is **not** used for the accumulated vector (the vector is a
   framework-owned structure, not app-decoded data). Open: confirm there is no
   `:data-schema`-on-the-accumulation use, or define what it would validate.

6. **`refetch` default: discard-tail vs refetch-all-pages.** Recommendation:
   default **discard tail, re-fetch page 0**, with opt-in `:refetch-all-pages?`
   for TanStack parity. Open: is discard-tail the right *default*, given TanStack
   defaults to refetch-all? (re-frame2 divergence justification: discard-tail is
   cheaper and the common feed UX tolerates a scroll-reset on explicit refresh; a
   stale-while-revalidate feed rarely hard-refetches all pages.)

7. **Bidirectional (`:prev-page-param` / prepend) in v1 or deferred?**
   Recommendation: define the param-derivation mirror now (it is free — same
   machinery) but **defer the prepend event** (`:rf.resource/load-prev`) unless a
   consumer needs it, to keep the v1 surface minimal. Open: ship both directions
   or next-only for v1?

## Resolved Decisions

Ruled by Mike on 2026-06-17 (GO on EP-0021; adopting the converged mayor
recommendation plus the Codex refinements). One row per Open Issue, plus the
`:request` signature. These rows are normative; the body above has been
reconciled to them.

| # | Decision | Resolution |
|---|----------|-----------|
| **R1** | Where do accumulated pages live, and in what exact shape? (Open Issue 1) | **ONE scoped resource entry per feed** in `:rf.runtime/resources`; `:data` = the ordered page vector `[page-0 page-1 …]`, plus `:page-params`, `:next-page-param`, `:page-error`, and the page-fetch work evidence. **NOT** N per-page entries and **NOT** an app-db slice. One owner set / freshness clock / GC clock / SSR-restore unit / Xray row for the whole feed. |
| **R2** | Page-level sub-status, or only the existing `:fetching`? (Open Issue 2) | **No 6th FSM state.** A load-more reuses the existing `:fetching` (refresh-class) transition; a derived **`:fetching-next?`** subscription distinguishes a load-more in flight from a whole-feed `:fetching?` refresh. The five-state FSM is untouched. |
| **R3** | Merged-list contract: headline read + default flatten? (Open Issue 3) | Framework-owned, memoised subscriptions: **`:rf.resource/items`** (the merged flat list — the headline read), **`:rf.resource/pages`** (raw boundaries), **`:rf.resource/infinite-state`** (combined view-model), plus the page-metadata subs. **`:page->items` is REQUIRED** for any feed whose page is non-vector/enveloped — loud over guessing `:items`/`:data`. A page that is already a vector flattens by identity. |
| **R4** | What does an item-inside-the-feed mutation do? (Open Issue 4) | A mutation touching an item inside a feed **invalidates the whole feed** (coarse, correct, v1). In-place patching of one item inside the page vector is **deferred** to the optimistic/patch axis. |
| **R5** | `:data-schema` vs `:page-data-schema`? (Open Issue 5) | **`:page-data-schema` validates one page** (the decode target) **and is the per-page egress/classification contract**: SSR and tool projection apply it **per page** so sensitive page fields are classified (the accumulated `:data` is a framework-owned vector and must not bypass classification). **`:data-schema` is not used** for the accumulated vector. |
| **R6** | `refetch` default: discard-tail vs refetch-all-pages? (Open Issue 6) | **Conservative default: preserve the visible window** until the replacement succeeds (so focus/reconnect/invalidation refetch never collapses a loaded feed to page 0). `:refetch` is an explicit per-resource policy; **`:refetch-all-pages?` and `:refetch-window` ship as opt-ins from day one.** *(This supersedes the EP's earlier discard-tail default — adopting the Codex-flagged safer behaviour; the body's Part / Issue 6 text is reconciled to it.)* |
| **R7** | Bidirectional (`:prev-page-param` / prepend) in v1 or deferred? (Open Issue 7) | **Next-direction `load-more` only for v1.** Define the `:next-page-param` / `:prev-page-param` derivation **mirror now** (free — same machinery), but **DEFER** the `:rf.resource/load-prev` prepend event until a consumer needs it. |
| **R8** | `:request` signature — new 3-arity, or the reserved context? | Use the **reserved `:request` context as the page extension point**: `(request feed-params {:rf.resource/page-param p :rf.resource/page-index i})`. Non-infinite requests still receive a nil/empty ctx unchanged. **Do NOT add a new 3-arity.** |

> **Subsequent slices.** This acceptance lands in the EP document only. The
> `spec/016-Resources.md` model definition + the `spec/Spec-Schemas.md` closed
> args-map schema, then the runtime implementation (state / registry / events /
> subs / SSR-restore / Xray) and the guide rewrite, follow as later slices per
> the [Bead Plan](#bead-plan--reference-implementation). EP-0021 stays
> `accepted` (not `final`) until those land.

## Recommendation

**Accept a first-class infinite resource** built on the existing resource
contracts: one scoped entry per feed, pages as the ordered durable `:data`, a
pure `:next-page-param` derivation with `nil` as the terminal, a causal
`:rf.resource/load-more` event, and a merged-list + page-metadata subscription
family. Align to TanStack `useInfiniteQuery` (`{pages, pageParams}`,
`getNextPageParam`) and SWR `useSWRInfinite` (per-page key derivation, a size
cursor), with three deliberate re-frame2 divergences: the page cursor is
runtime-owned state advanced by a causal event (views stay passive), `nil` is the
single terminal, and feed identity is fail-closed scoped separately from params.

The cut is intentionally narrow: next-direction load-more, coarse feed-level
invalidation, a window-preserving refetch default (with `:refetch-all-pages?` /
`:refetch-window` opt-ins from day one), and the page-vector-in-one-entry
shape — deferring in-place item patching (to the optimistic EP), normalized
item stores, prepend, and streaming. The seven open issues — above all where the
pages live (1) and the merged-list contract (3) — were the genuine rulings; all
are now resolved in [§Resolved Decisions](#resolved-decisions), and the rest of
the design follows from them.
