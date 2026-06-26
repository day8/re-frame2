# Resources vs TanStack Query, RTK Query, and SWR

If you've written a React app in the last few years, you've almost certainly used a server-state library — [TanStack Query](https://tanstack.com/query) (the React Query family), [RTK Query](https://redux-toolkit.js.org/rtk-query/overview), or [SWR](https://swr.vercel.app/). They solve a real, gnarly problem: caching server data, keeping it fresh, deduping in-flight requests, invalidating after writes. re-frame2 solves the *same* problem with its **managed [resources](../../resources/glossary.md#resource)** — but the physics underneath are different, and that difference is the whole story of this page.

This is the honest, feature-by-feature comparison. The conceptual model lives in [Server state: resources](../../resources/concepts.md); this page is the scorecard that sits beside it, telling you what's matched, what's *deliberately different*, and — bluntly — what's **out of scope** for this HTTP-only phase.

One sentence to carry through everything below:

> **Same problem, different physics.** A query library is a cache wired to component lifecycle — a fetch happens because a component mounted. A re-frame2 resource is a cache wired to *causes* — a fetch happens because a route was entered, an [event](../glossary.md#event) fired, or a [machine](../../machines/glossary.md#machine) asked — and the viewer's identity (which user is this cache for?) is a required, declared part of the cache key, not something you remember to add.

If "cause" and "the cache key includes the viewer" feel abstract right now, don't sweat it — every clause of that sentence gets its own section below. We build it up one idea at a time: where the cache lives, how identity ([scope](../../resources/glossary.md#scope)) works, what keeps an entry alive, why views never fetch, and how writes keep reads honest. The big parity table is at the end as a reference scorecard — read the narrative first; it explains the rows.

> **Optional artefact, and pre-alpha.** Server state in re-frame2 is the optional `day8/re-frame2-resources` artefact, and it is **pre-alpha**. The read path, the mutation path, optimistic rollback, active-owner polling, and infinite / load-more feeds have all landed. Two query-library features are *deliberately out of scope* for this phase — normalized/GraphQL caches and offline/cross-tab persistence — and the [honest gaps](#the-honest-gaps--out-of-scope-on-purpose) section names exactly where that line sits. Nothing here is back-compat-frozen.

## The smallest thing that works

Here's a complete resource. One `reg-resource` call declares *everything* about how this server read behaves:

```clojure
(rf/reg-resource
  :article/by-slug
  {:params-schema  [:map [:slug :string]]   ;; REQUIRED — identity + canonicalization
   :scope          :rf.scope/global         ;; REQUIRED — the leak boundary (more soon)
   :stale-after-ms 60000                    ;; serve immediately, refetch when stale
   :gc-after-ms    300000}                  ;; collect 5 min after the last owner leaves
  ;; the :request fetch fn is the THIRD argument — NOT a metadata key
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))
```

That's the shape to hold in your head: an **id**, a **metadata map** of policy, and a **fetch function** as the third positional argument. A [view](../glossary.md#view) never calls this fetch function. Instead, a view reads the *result* through a [subscription](../glossary.md#subscription), and some separate event — a route being entered, a click — is what actually triggers the fetch. (That split is the "cause" idea from the epigraph; it gets its own section, [Views never fetch](#views-never-fetch--the-structural-inversion), below.) The rest of this page unpacks why each piece is the way it is.

> **For JavaScript developers.** This is your `useQuery` config object, hoisted out of the component. Where TanStack collects `staleTime` / `gcTime` / `refetchInterval` in one options map, re-frame2 collects them on one `reg-resource` — but at *boot*, named once, not inline at a call site. The fetch function is TanStack's `queryFn`; `:params-schema` is the typed, validated half of `queryKey`.

> **Gotcha — the `:request` fn is the third argument, not a key.** This trips everyone exactly once: the fetch function is the *third positional argument* to `reg-resource`, after the id and the metadata map — not a `:request` entry *inside* that map. Put it inside the map by mistake and you get a loud `:rf.error/invalid-resource-spec`. The fn is called with `(params ctx)`; `ctx` is reserved for a future slice and is literally `nil` today, so derive everything you need from `params`. What it returns is a [managed-HTTP](../../resources/glossary.md#managed-http) request map ([re-frame2's framework-handled HTTP](../../resources/http.md)). One restriction: you may *not* set `:request-id`, `:on-success`, or `:on-failure` on it. The resources machinery fills those in itself — it has to, because it routes each reply back to the exact cache entry that asked for it. The success/failure handlers aren't yours to override.

## Where the cache lives, and why it's per-frame

A query library keeps **one** cache for the whole running app: TanStack's `QueryClient`, RTK Query's store slice, SWR's module cache. That's fine for a single-user browser tab. It becomes a liability the moment two users share one process — most sharply on the server, where a single Node process renders pages for every user at once, all reaching into that one shared cache.

re-frame2 keeps the cache somewhere else: inside the *frame*. A [frame](../glossary.md#frame) is one running instance of your app — its own [app-db](../glossary.md#app-db), its own subscriptions, its own everything. In the browser you typically have one; on the server you spin up a fresh frame per request. The resource cache lives in the framework-owned **[runtime-db](../glossary.md#runtime-db)** partition of each frame (the subsystem at `[:rf.db/runtime :rf.runtime/resources]`, beside the machine and route slices) — and crucially, **never** in your app-db. The runtime-db partition is introduced in full on [the app-db page](../concepts/app-db.md); here it's enough that it's the framework's half of frame-state ([the two partitions](../glossary.md#the-two-partitions)), read through subscriptions, never written by an ordinary `:db` handler. Two consequences fall out:

- **SSR can't leak by construction.** Each server request renders in its own frame, so there's no process-global cache to bleed user A's articles into user B's render. A query library reaches the same safety only by spinning up a fresh client per request and being careful never to share it.
- **An ordinary [event handler](../glossary.md#event-handler) can't corrupt the cache.** It lives in runtime-db, not app-db, so a careless `assoc` in a `:db` handler can't wipe it. You read it through subscriptions and change it only through events.

This is the [Server state: resources](../../resources/concepts.md#the-cache-you-dont-own) "cache you don't own" model. It's *different by design*, not merely a different default.

> **Coming from React?** The `QueryClient` you wrap your app in is module-global by default, and the per-request SSR safety you get for free here is the thing the TanStack SSR guide spends pages warning you to do by hand: "create a *new* `QueryClient` inside the request handler, never at module scope." re-frame2 makes that the only thing you *can* do, because the cache is a property of the frame, not the module.

## Scope: the leak boundary the query libraries leave to you

This is the single biggest difference, and the one most worth internalising.

In every query library, the viewer's identity is a **convention**: you remember to put the user id (or tenant, locale, impersonation marker) into the `queryKey`. Forget it once, and one user silently reads another's cache — a bug with no error, no stack trace, surfacing weeks later as a support ticket. The worst kind of bug: the kind that doesn't look like one.

re-frame2 makes scope the **first, required segment of the cache key** and refuses to default it:

```clojure
;; Adapted from spec/016-Resources.md — the registration gate
(rf/reg-resource :realworld/feed
  {:params-schema [:map [:page {:optional true} [:maybe :int]]]
   :scope         {:from-db :realworld/session}   ;; REQUIRED — no default
   :tags          (fn [_ _] #{[:feed]})}
  (fn [{:keys [page]} _ctx] ...))
```

- A `reg-resource` with **no** `:scope` is a loud registration error (`:rf.error/resource-missing-scope-policy`) — "I forgot this read is user-scoped" is *unrepresentable*.
- A genuinely public read says so: `:scope :rf.scope/global` is an **auditable claim**, enumerated by [Xray](../glossary.md#xray)'s scope-audit surface as the security-review list.
- A subscription that can't resolve a scope raises `:rf.error/resource-sub-unresolved-scope` — it never falls through to a shared read or a silent permanent `:idle`.
- When the viewer changes mid-session (login, logout, account switch), a `{:from-db …}` subscription **re-keys**: it automatically re-points to the new user's cache entry — reading that fresh entry's `:idle`/`:loading` state, never the previous user's data. (More on how that re-pointing works in the resolver section just below.)

The query libraries *can* do scoped caching — nothing stops you putting the user id in the key. re-frame2's difference is that it **fails closed**: the boundary is enforced, not advised. See [The scoped key: a leak boundary that fails closed](../../resources/concepts.md#the-scoped-key-a-leak-boundary-that-fails-closed).

> **Going deeper.** Read scope as a *quotient*: the cache key is the pair `(scope, params)`, and scope partitions the keyspace into equivalence classes that can never see each other. A query library leaves the partition implicit — it's whatever you happened to encode in the key string, recoverable only by reading every call site. re-frame2 makes the partition a typed, first-class structural component of the key, so the equivalence relation is *declared* rather than emergent. The fail-closed `nil` is the bottom element: absence of an identity is never silently coerced to "everyone," it halts.

### `{:from-db …}` and the named scope resolver

You'll have noticed `{:from-db :realworld/session}` recurring through the examples. That's a reference to a **named scope resolver** — registered once, reused everywhere a scope is needed (registration, route resources, event-side ensure, subscriptions, invalidation descriptors, `clear-scope`). One scope-resolution currency, not a resolver re-spelled at every call site. You register it with `rf/reg-resource-scope`:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username
                [:rf.scope/session {:username username}]))})
```

Three properties make this the mechanism the whole leak boundary rests on:

- **A resolver is pure.** It derives a scope value from declared app-db inputs and nothing else — it doesn't fetch, [dispatch](../glossary.md#dispatch), or read ambient host state. Declaring `:inputs` (rather than reading the whole db) lets the runtime re-resolve scope *only* when a relevant input changes, and lets Xray explain which facts decide a resource's identity.
- **`nil` is fail-closed, always.** When `:username` is absent (logged out), the resolver returns `nil`, and at any scope-requiring site `nil` is the *unresolved* condition — never silent permission to read the global cache. A subscription whose scope resolves `nil` raises `:rf.error/resource-sub-unresolved-scope`; a `clear-scope` reference that resolves `nil` emits a loud diagnostic, never a silent no-op.
- **A `{:from-db …}` subscription re-keys reactively.** This is the account-switch / login / logout payoff. When the resolver's inputs change mid-session, the subscription re-points to the *new* viewer's cache entry on the next reactive pass — the same subscription, now tracking the new viewer. During the transition the view reads the new key's `:idle` / `:loading`, **never** the previous viewer's `:data`. The leak boundary holds across the re-key, not just at first resolution.

The normative contract is [Spec 016 §Named resource-scope resolvers](../../../spec/016-Resources.md#named-resource-scope-resolvers-reg-resource-scope). There's one tricky moment — logout — where ordering bites: to clear the logged-out user's cache you have to resolve *which* scope they were in *before* the db forgets them. `rf/resolve-resource-scope` is the helper for exactly that: inside a handler, it resolves a named scope against the db as it stood when the event arrived, so you can clear the scope the user *was* in even as the same event logs them out.

## Owners and causes, not observers

Query libraries have one idea — the **observer**: a mounted hook keeps a query alive, and an unmounted one lets it become eligible for garbage collection (GC). Notice that this single idea is quietly doing two jobs at once. It decides *liveness* (should this entry stay cached, or can we collect it?) *and* it stands in for *why a fetch happened* (answer: "a component appeared"). re-frame2 pulls those two jobs apart into two concepts that never blur ([owner & cause](../../resources/glossary.md#owner--cause)):

- An **owner** answers "should this stay cached?". Think of it as a *lease* on a cache entry — a hold that keeps the entry alive while the holder still needs it. A route holds a lease for as long as that route is active; a machine holds one for its actor's lifetime; and your app can take out an explicit lease by hand (with a matching release when done). When the last lease is released, the entry becomes eligible for GC. Owners also decide whether an invalidation refetches *now* or just marks the entry stale for later.
- A **cause** answers "why did this fetch happen?" — route entry, a click, an invalidation, a window-focus return. A cause changes nothing about liveness; it exists purely so the trace can explain *why* a fetch fired.

The practical payoff: **opening a devtool never pins or refetches a resource.** A query library's devtools panel is itself an observer-ish surface; Xray, by contract, never becomes an owner by observing ([Spec 016 §Active owners and causes](../../../spec/016-Resources.md#active-owners-and-causes)). It also means a "background sync" that should keep data warm with no UI mounted is a first-class, explicit lease here — rather than a `staleTime: Infinity` + always-mounted-hidden-component hack.

> **Going deeper.** Conflating liveness with cause is the original sin of lifecycle-bound caching: GC pressure and provenance are different concerns, and binding both to "is a component mounted?" means you can't express *keep this warm with no UI* or *this fetch happened because of an invalidation, not a mount* without contortions. Splitting them gives you two orthogonal axes — a lease lattice for liveness, a causal log for provenance — that compose independently. The trace is then a genuine causal history, not a reconstruction from mount/unmount churn.

## Views never fetch — the structural inversion

`useQuery` fetches on mount. That's the defining ergonomic of the query libraries, and the defining *difference* of re-frame2: a re-frame2 view reads a subscription and **never** triggers a fetch. The fetch is caused by a route entry, an event, or a machine.

Here's what that looks like. The view subscribes to `[:rf.resource/state …]`, gets back a small map of status booleans, and branches on them — and that's all it does. (The exact keys it reads, `:loading?` / `:error` / `:has-data?` / `:data`, are spelled out in the [next subsection](#what-rfresourcestate-actually-hands-the-view) — for now, just notice the view never asks for a fetch.)

```clojure
;; The view is passive — it reads, it does not fetch.
(rf/reg-view article-page [slug]
  (let [state @(subscribe [:rf.resource/state
                           {:resource :realworld/article :params {:slug slug}}])]
    (cond
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else [article-view (:data state)])))
```

The cleanest *cause* is the page itself, declared as route metadata:

```clojure
(rf/reg-route :realworld/article
  {:resources [{:resource :realworld/article
                :params   (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

This isn't a missing feature — the data still loads on navigation. It's the *inversion* that lets SSR get a natural wait point (`:blocking? true`), lets a route own and release the resource deterministically, and keeps views pure. If you find yourself wanting "fetch when this component appears," the re-frame2 answer is "make the route or an event the cause" — see [Routes declare what a page needs](../../resources/concepts.md#routes-can-declare-more-than-one-resource).

> **Gotcha — what goes wrong at the route boundary.** Two failure modes live here, both fail-closed and both surfaced on the route slice (not swallowed). If a route-resource entry's `:params` / `:scope` / `:when` function can't resolve — a `{:from-db …}` scope that comes back `nil`, a params fn that throws — the route plan raises `:rf.error/resource-route-plan` and the navigation surfaces the error rather than entering a page wired to a phantom cache key. And on the server, a `:blocking? true` resource that never settles can't hold the render open forever: exceeding the render deadline raises `:rf.error/resource-ssr-blocking-timeout`, so a hung upstream becomes a located, traced error instead of a silently truncated page. (A *fresh* blocking resource — already `:loaded` and still fresh-by-policy — settles the navigation immediately, so a route blocked on cached-fresh data never hangs.)

> **Coming from React?** "Fetch on mount" feels like the natural place for data because, in React, the component *is* the only durable thing you have. re-frame2 has routes, events, and machines as first-class causes that outlive any one component, so the fetch attaches to *those* — and the view drops back to its proper job: a pure function of the current state. The inversion is the same one behind [Inside out: why views come last](inside-out.md).

### What `:rf.resource/state` actually hands the view

`[:rf.resource/state …]` projects a **view-model of derived facts**, not the raw durable cache entry. The booleans your view branches on are computed for you — you never reconstruct "error, but I still have stale data to show" from a status enum plus a guard. The projection:

```clojure
{:status        :idle | :loading | :fetching | :loaded | :error
 :data          <last-known-good-or-nil>
 :error         <first-load-error-or-nil>      ;; only when :status is :error
 :refresh-error <background-refresh-error-or-nil>
 :loading?      <bool>   ;; first load, no usable data yet
 :fetching?     <bool>   ;; refreshing while prior :data stays visible
 :stale?        <bool>   ;; past :stale-after-ms (or invalidated)
 :has-data?     <bool>}  ;; :data is non-nil — the one to branch on for skeleton-vs-content
```

The two status words are the whole ergonomic. **`:loading`** means *first load, nothing to show* (render a skeleton). **`:fetching`** means *work is in flight but prior data is still good* (render the data, maybe a quiet spinner). And the error split matters: **`:error`** is a first load that produced no usable data, while **`:refresh-error`** is a *background* refresh that failed with prior data preserved — so a view shows a full-page error in the first case and a stale-data-with-a-warning banner in the second, without guessing. The error envelope is the same closed [managed-HTTP failure shape](../../resources/http.md) (`:kind` drawn from the `:rf.http/*` taxonomy), shared by `:error` and `:refresh-error` alike. The narrower single-fact subs (`:rf.resource/data`, `:rf.resource/loading?`, `:rf.resource/has-data?`, …) project one slice each when a view needs only one ([Spec 016 §Status semantics](../../../spec/016-Resources.md#status-semantics)).

> **For JavaScript developers.** `:loading?` vs `:fetching?` is TanStack's `isLoading` vs `isFetching` made explicit and put first. The piece TanStack leaves you to assemble by hand — "the refresh failed but I still have good data, so warn, don't blow up the page" — is here as the `:error` / `:refresh-error` split, decided for you.

## Projection: no `select`, because subscriptions already do it

TanStack's `select` (and RTK's `selectFromResult`) lets a component derive a slice of the cached value without re-rendering on unrelated changes. re-frame2 has **no `:select` key** — and that's a structural advantage, not a gap. A projection over a resource is an ordinary subscription layered over `[:rf.resource/data …]`, with the full [derivation graph](../glossary.md#the-derivation-graph)'s caching and parametric inputs behind it:

```clojure
(rf/reg-sub :article/title
  :<- [:rf.resource/data {:resource :realworld/article :params {:slug "welcome"}}]
  (fn [article _] (:title article)))
```

You get memoisation, composition with other subs, and reuse across views for free — none of which a per-call `select` function gives you ([Spec 016 §No `:select` key](../../../spec/016-Resources.md#no-select-key)).

> **Going deeper.** `select` is a special-cased, per-call projection bolted onto one cache read. A subscription is the *general* projection — a node in a memoised dataflow graph — of which "derive a slice of a resource" is just one instance. Once you already have the general structure, the special case is redundant; re-frame2 omits it not because projection is unimportant but because it was already solved one layer down.

## Invalidation is a declared consequence, not a remembered call

In TanStack and SWR, keeping reads honest after a write is an imperative call you remember to make in `onSuccess` (`invalidateQueries` / `mutate`). RTK Query is closer to re-frame2 here with its `providesTags` / `invalidatesTags`. re-frame2 takes the declarative-tag model further: [invalidation](../../resources/glossary.md#invalidate) is a **declared consequence of the named [mutation](../../resources/glossary.md#mutation)**, recorded on the event record, scoped by default, with a precise three-rung cross-scope lattice:

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global         ;; global facts
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}  ;; this viewer's feed
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx] ...))
```

The fail-closed floor matters: a bare `:rf.resource/invalidate-tags` with no scope raises `:rf.error/resource-invalidate-scope-required`, not a silent global blast across every tenant. "Invalidate this tag wherever it lives" *is* possible — `:cross-scope? true` — but it's an **audited** operation that *must* carry a `:cause` (one without it raises `:rf.error/resource-cross-scope-cause-required`) and is a privacy-relevant trace event. A descriptor that omits `:scope` defaults to `:rf.scope/same` — the mutation's resolved scope — which is also the meaning of the bare tag-set shorthand. See [Writes invalidate by tag — causally](../../resources/concepts.md#writes-invalidate-by-tag--causally) and [Spec 016 §Scoped invalidation descriptors](../../../spec/016-Resources.md#scoped-invalidation-descriptors-per-target).

> **Coming from Redux?** If you already use RTK Query's `providesTags` / `invalidatesTags`, you're home — this is the same tag-graph model. The two upgrades: invalidation is *scoped* by default (a write in one tenant can't quietly nuke another's cache), and crossing a scope boundary is an explicit, audited, traceable opt-in rather than the default reach.

## Running and observing a mutation

`useMutation` returns a `{ mutate, isPending, data, error }` tuple. re-frame2 splits that one tuple into two halves: a **causal command** (an event you dispatch to *make the write happen*) and a **passive read** (a subscription that *reports how it's going*). This is the same command-versus-read split you already saw on the read side, where a route causes a fetch and a view subscribes to its result — applied now to writes. You *run* a mutation by dispatching `:rf.mutation/execute`, and you *observe* it through the `:rf.mutation/*` subs — keyed by an **instance** id, not the mutation id:

```clojure
;; the command — a route, an event, or a form submit dispatches it
[:rf.mutation/execute
 {:mutation :article/save
  :params   article
  :instance :form/save-1          ;; caller-supplied (or generated) instance id
  :scope    [:rf.scope/session {:user-id "u-42"}]
  :cause    [:form-submit :article/save]
  :reply-to [:editor/save-replied]}]   ;; optional completion event (below)

;; the read — a view observes the instance
@(rf/subscribe [:rf.mutation/state {:instance :form/save-1}])
;; => {:status :result :error :pending? :success? :error? :settled? :optimistic?}
```

The instance-keying matters: two concurrent submissions of `:comment/add` keep **distinct** pending/result/error rows and never clobber each other — the thing a single `useMutation` hook can't express without one hook per in-flight write. Narrower subs (`:rf.mutation/pending?`, `:rf.mutation/result`, `:rf.mutation/error`, `:rf.mutation/status`) project one slice when a button needs only one. A mutation's failure settles `:error` (there's no `:refresh-error` analogue — a write has no last-known-good to keep), and `[:rf.mutation/clear {:instance …}]` is the causal reset that clears the instance and best-effort aborts any in-flight work.

A mutation is registered with `rf/reg-mutation`, mirroring `reg-resource`'s three-slot grammar — id, metadata map, and the `:request` *write* fn as the third argument. Its success-phase consequence keys are the heart of it:

```clojure
(rf/reg-mutation :article/save
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [params result] [{:scope … :tags #{[:article-list]}}])  ;; RTK invalidatesTags
   :populates     (fn [params result] {scoped-key result})    ;; seed an entry from the reply (authoritative load)
   :patches       (fn [params result] {scoped-key (fn [old result] (merge old result))})  ;; transform an entry
   :removes       (fn [params _result] [target-map])}          ;; delete an exact entry
  (fn [params _ctx] {:request {:method :put :url "/api/articles" :body params}}))
```

`:populates` / `:patches` / `:removes` are the declarative twins of TanStack's imperative `setQueryData` in `onSuccess`: `:populates` seeds an entry from the reply (semantically a successful load), `:patches` transforms one in place, `:removes` evicts an exact key. They run *before* the success-time `:invalidates`, and the invalidation timing is itself explicit (`:before-request` / `:after-success` (the default) / `:after-failure` / `:after-settle`).

> **For JavaScript developers.** `useMutation`'s one hook is split here into a verb you dispatch (`:rf.mutation/execute`) and a noun you subscribe to (`:rf.mutation/state`), keyed by a *per-call* instance id. That instance id is what buys you N concurrent in-flight writes of the same mutation without N hooks — the case `useMutation` quietly can't model.

### The completion continuation is an event, not a callback

`useMutation`'s `onSuccess` / `onError` callbacks are where you fire a toast, navigate, or reset a form. re-frame2 does the equivalent, but instead of a callback you name a **`:reply-to` event** at the call site — an ordinary event that gets dispatched when the write completes. Why an event and not a callback? Because in re-frame2 the *only* legitimate way to change durable state is to dispatch an event, so that every change flows through the one re-frame2 loop (and stays visible to [interceptors](../glossary.md#interceptor), tracing, and [time-travel](../glossary.md#time-travel) replay). A callback that quietly performed effects would change state *outside* that loop — invisible to all of it. So the verified server reply becomes the input to an event, like any other cause. The reply map is appended after any static args you supply: `:reply-to [:toast/after-save {:kind :article}]` dispatches `[:toast/after-save {:kind :article} reply]`. It fires **exactly once**, and **only** for the accepted terminal reply — a stale or superseded reply (a re-execute under the same instance, an intervening `:rf.mutation/clear`) never fires it. By the time your `:reply-to` handler runs, the cache consequences and the instance state are already settled, so it sees a coherent world ([Spec 016 §Mutation completion continuations](../../../spec/016-Resources.md#mutation-completion-continuations--call-site-reply-to)).

> **Gotcha — mutation scope fails *open* on absence.** This is the one place a scope is optional, and the footgun worth memorising. Unlike a resource, a mutation's `:scope` is optional and defaults to `:rf.scope/global` when omitted, because a causal write has no cached-read leak boundary of its own. But the *invalidation* it triggers is still fail-closed (it needs an explicit scope), and the two must agree: if a global-defaulted mutation invalidates tags owned by a session-scoped resource, the invalidation **silently misses** — no entry matches in that scope, the read never refreshes, and no error fires. In dev the framework trips a `:rf.warning/mutation-scope-mismatch` warning at exactly that moment; in production, declare the matching `:scope` on the execute payload (or use a per-target invalidation descriptor).

## Optimistic updates with rollback

The query libraries all ship [optimistic UI](../../resources/glossary.md#optimistic-update--rollback): flip the cache immediately, snapshot the prior value, revert on server rejection (`onMutate` / `onError` rollback in TanStack, `updateQueryData` + undo patch in RTK, `optimisticData` + `rollbackOnError` in SWR). re-frame2 matches this on the mutation path, with one deliberate divergence on *contested* rollback.

A mutation declares an **optimistic plan** applied *before* the request leaves, in one of two forward forms — the twins of the success-time consequence keys:

- **`:optimistic`** — `(fn [params] -> {target patch-fn})`, the exact-target twin of `:patches`. A `nil` patch-fn removes the entry optimistically; a patch over an absent key seeds it.
- **`:optimistic-tags`** — `(fn [params] -> [{:scope … :tags #{…} :patch (fn [old] new)}])`, the tag-addressed twin of `:invalidates`. It patches every cached entry carrying the tag in its scope — the cross-view-consistency case (flip a favorite and have it flip on the detail, every list, and the session feed at once) you can't enumerate by exact key.

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [article] (update-favorite article true))}])
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :realworld/article :params {:slug slug}} result})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session} :tags #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx] ...))
```

Three properties are worth holding onto, because they're where re-frame2 differs from a hand-rolled `onMutate` snapshot:

- **The runtime records the inverse — you don't.** It snapshots each touched entry verbatim before patching, so a rollback restores exactly what existed, never an author-written undo patch that can drift from the forward change.
- **Settle is deterministic, with no wall-clock race.** Whether a write commits or rolls back is decided on *recorded facts*, not on which reply happened to arrive first. Two facts do the deciding: a *generation* check (each fetch carries a generation number, so the runtime can tell a reply meant for the current request from a stale one left over from a superseded request) and a per-entry `:revision` (a counter bumped on every change, so the runtime knows whether the entry moved underneath you). An accepted `:ok` reply commits (the authoritative `:populates`/`:patches` overwrite the optimistic value, then `:invalidates` runs); an accepted `:error`/`:cancelled` rolls back; a stale or superseded reply rolls back nothing.
- **Contested rollback is governed by `:on-conflict`, and the default diverges from the query libraries.** When a concurrent write landed on the entry between the apply and a failure, the recorded inverse is a stale "before." The default `:invalidate` declines to restore it and marks the entry stale so the read path refetches the authoritative value — the read path is the recovery authority, re-frame2's deliberate divergence from TanStack/SWR's unconditional context restore. `:force` restores the inverse anyway (the single-writer escape, with a tooling warning).

A view renders the in-flight optimistic state from the instance sub's derived **`:optimistic?`** flag (true between the pre-request apply and settle). The optimistic surface is exact-key or tag-within-named-scope only, both **fail-closed** on a nil-resolving `{:from-db …}` scope — there's no scope-agnostic optimistic write, so it can't leak across viewers, tenants, or SSR requests. The normative contract is [Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations); the worked write is in [Part 4 of the tutorial](../../resources/tutorial/04-mutations-and-invalidation.md).

> **Going deeper.** An optimistic update is a *speculative* state transition that must be confirmed or undone. The query libraries make you carry the undo as a hand-written patch — a second source of truth that can disagree with the forward change. re-frame2 instead records the *inverse automatically* (snapshot the before-image) and resolves the commit/rollback against recorded facts (a generation verdict plus per-entry revisions) rather than wall-clock arrival order. The contested case — where a concurrent write invalidated your snapshot — is then a genuine conflict, and re-frame2 resolves it by *re-deriving from the authority* (refetch) rather than restoring a known-stale value. That's the same "the source of truth wins, not the optimistic guess" instinct, applied to the one case the query libraries paper over.

## Polling and infinite feeds — both landed

The two parity-relevant features once tracked as gaps here have since landed; they're recorded below for readers arriving from the query libraries.

### Polling / refetch-interval (EP-0020)

TanStack's `refetchInterval`, RTK's `pollingInterval`, and SWR's `refreshInterval` make timed background refetching a one-line option. re-frame2 ships the same as **`:poll-interval-ms`** on `reg-resource` ([Spec 016 §Polling](../../../spec/016-Resources.md#polling)): a positive interval in ms that revalidates the entry while it's *actively owned* and the tab is visible — the third member of the freshness-timer family beside `:stale-after-ms` / `:gc-after-ms`. It's owner-gated by design: an owner-free entry never polls, so opening a devtool or leaving a stale tab open can't drive background traffic. (This is in addition to the focus/reconnect revalidation that landed earlier via `install-revalidation-listeners!`.)

### Infinite / load-more feeds (EP-0021)

`useInfiniteQuery` (TanStack) and `useSWRInfinite` accumulate pages into one growing list with cursor management. re-frame2 ships this as a first-class **`:infinite`** resource ([Spec 016 §Infinite resources and load-more feeds](../../../spec/016-Resources.md#infinite-resources-and-load-more-feeds)): a resource registered with `:infinite true` plus a pure `:next-page-param` derivation (returning `nil` is the single terminal). One scoped feed entry accumulates an ordered page vector; a causal `:rf.resource/load-more` event extends it; the merged flat list is the headline read at `:rf.resource/items`, with `:rf.resource/pages` for boundaries and `:rf.resource/infinite-state` for the combined view-model (which adds `:fetching-next?` and `:has-next-page?` to the read so a view can render a spinner, a "Load more" button, or an end-of-feed marker without bookkeeping). Numbered and cursor pagination stay exactly as before — every page is an ordinary resource keyed by its params, with `:keep-previous?` to avoid skeleton flashes ([Spec 016 §Paginated and previous data](../../../spec/016-Resources.md#paginated-and-previous-data)) — the infinite kind is the complementary accumulating-feed model, not a replacement.

> **Gotcha — `:next-page-param` is required, and load-more failure has its own error channel.** Declaring `:infinite true` without a `:next-page-param` derivation is a loud registration error (`:rf.error/infinite-missing-next-page-param`) — there's no inferring the cursor. (A feed whose page is non-vector / enveloped also needs a `:page->items` accessor, or `:rf.error/infinite-missing-page-accessor`.) At runtime, a *load-more* page fetch that fails is **not** a feed first-load failure: the feed returns to `:loaded`, **keeps every accumulated page**, and records the failure in a **third error channel** — `:page-error`, beside `:error` (first load) and `:refresh-error` (whole-feed refresh) — so the view shows "couldn't load more — retry" without losing the feed. `:page-error` clears on the next successful load-more.

## When to reach for resources at all

A query library is the obvious default in React because it's the *only* server-state machinery on offer. In re-frame2 it's one tool among several, and not always the right one:

> **Simpler than a resource.** Two cases where reaching for the resources artefact is over-engineering:
>
> - **A handful of reads, no caching story.** A [managed HTTP request](../../resources/http.md) plus a small app-db slice is less machinery and entirely idiomatic.
> - **Login and other commands.** Auth is a state machine driving a write — model it as a [machine](../../machines/concepts.md), not a cached read.

Reach for resources when cached server reads start multiplying and the per-read bookkeeping — scope, staleness, dedupe, invalidation, GC, SSR — is worth moving into the framework. [Where should this value live?](../where-state-lives.md) has the decision table, and resources are one of [the four homes](../glossary.md#the-four-homes-where-state-lives) state can take.

## The honest gaps — out of scope, on purpose

These remain deliberately outside this HTTP-only phase. Don't let the rest of this page's confidence obscure them.

- **Normalized / GraphQL caches** (Apollo, Relay, normalizr). The transport is HTTP-only this phase; a normalized entity cache is a separate later artefact gated on a GraphQL phase, not a resources gap ([Spec 016 §What Spec 016 does NOT cover](../../../spec/016-Resources.md#what-spec-016-does-not-cover)).
- **Offline persistence and cross-tab broadcast.** Deferred later slices; not in the public-beta contract.

## Advanced

Three power-user topics that don't belong in the progressive flow above but that a real consumer reaches for once the basics are in place.

### Where auth headers go: the managed-HTTP decoration seam

Every flagship example on this page hits a bare `/api/...` URL, which raises the obvious question: where do auth headers, tracing headers, the API base URL, and tenant headers live? **Not on the resource.** A resource's (or mutation's) `:request` fn describes the *domain* request only — method, url, params, body, `:decode`. Cross-cutting decoration belongs to the managed-HTTP layer the resource lowers through, applied once by a frame-registered `reg-http-interceptor` that decorates *every* `:rf.http/managed` request the frame issues — reads, writes, and plain managed calls alike ([Spec 016 §Request decoration belongs to the managed-HTTP seam](../../../spec/016-Resources.md#request-decoration-belongs-to-the-managed-http-seam-not-the-resource-declaration)):

```clojure
(rf/reg-http-interceptor :realworld/auth
  {:before (fn [ctx]
             (let [token (some-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Token " token)))))})
```

Two details earn their keep. The interceptor reads frame state through `(rf/app-db-value (:frame ctx))` — the carried-frame-correct read — never an ambient `db`, so it stays SSR-safe and frame-isolated. And a resource that needs auth needs **no** per-resource opt-in: register the interceptor once and every read is decorated. (One asymmetry: default retry policy is read-focused — **write retries stay opt-in**, because retrying a write can duplicate side effects, so a mutation arms `:retry` only when its own `:request` declares it.)

### `:rf.scope/from-caller` and the two scope-mismatch tripwires

Most resources name their scope at registration (`:rf.scope/global` or a `{:from-db …}` resolver). A third policy, **`:rf.scope/from-caller`**, *requires* the scope from the use site — every `ensure` / `refetch` / `:rf.resource/state` call (or a route-resource resolver) must supply `:scope`, or it's a loud use-time error (`:rf.error/resource-scope-required-from-caller`). It pushes enforcement to where the scope is actually known.

The footgun this opens is the *resolvable-but-wrong* case: a subscription supplies a `:scope` that resolves to a perfectly valid — but **different** — concrete scope than the one the owning route/event ensured under. Fail-closed is still correct (the read never reaches a wrong-principal entry), but the symptom is a silent permanent skeleton: the sub reads `:idle` forever against a key no owner ever attached to. Because the miss is silent by construction, re-frame2 surfaces it from **both** ends of the loop in dev (all DCE'd from production):

- **Read-side** — `:rf.warning/resource-sub-scope-mismatch` fires the moment a `:rf.scope/from-caller` sub lands on a scope key with zero active owners while a *different* key for the same resource id is active. Its `:hint` names the fix: pass the same `:scope` the owning route/event ensured under.
- **Write-side** — `:rf.warning/mutation-scope-mismatch` fires when a mutation's `:invalidates` descriptor matches zero entries in its resolved scope while a *different* scope holds them (the "Gotcha — mutation scope fails open" case from the mutation section above).

Both are one-shot idempotent per distinct mismatch, and Xray's offline scope-mismatch lint catches the cases the live heuristics narrow away ([Spec 016 §Dev-mode likely-mismatch warning](../../../spec/016-Resources.md#dev-mode-likely-mismatch-warning-rfwarningresource-sub-scope-mismatch)).

### The `ctx` argument is reserved

Every author-supplied function this artefact calls — a resource `:request`, a spec-side `:scope` resolver, a `reg-resource-scope` `:resolve` — receives a trailing `ctx` argument that is **reserved and literally `nil` today**. It's declared so the surface is forward-compatible, but you must derive your result from the function's *own* declared inputs (`params`, the resolver's `:inputs`), never from `ctx`. The one exception is the **route-resource** `:params` / `:scope` / `:when` functions, which carry a populated `(route ctx)` context because route-entry planning has a real route match to thread. The cache-consequence callbacks (`:invalidates` / `:populates` / `:patches` / `:removes`) take no `ctx` at all — their canonical signature is `(params result)`, and db-derived scope is reached only via `{:from-db …}` references ([Spec 016 §The `ctx` argument is reserved](../../../spec/016-Resources.md#the-ctx-argument-is-reserved-across-resourcemutation-fn-surfaces)).

## The full scorecard

Now that the narrative has explained the rows, here's the whole comparison on one card. Each row carries a status:

| Status | Meaning |
|---|---|
| **Landed** | Shipped in the reference implementation (`re-frame.resources`) and pinned by tests. |
| **Different by design** | A capability the query libraries have, expressed differently here on purpose — usually because re-frame2 already has a more general mechanism (the subscription graph, the re-frame2 loop) that subsumes it. |
| **Out of scope** | Deliberately not a resources concern — a different artefact, a different phase, or a non-goal. |
| **Deferred (later slice)** | A real parity gap, deliberately held for a later slice, with no shipped contract yet. Used here only for offline persistence / cross-tab broadcast. |

| Dimension | TanStack Query | RTK Query | SWR | re-frame2 resources | Status |
|---|---|---|---|---|---|
| **Keyed cache** | `queryKey` array | endpoint + serialized arg | string/array key | `[scope resource-id canonical-params]` triple; params are schema-validated and canonicalized | **Landed** |
| **Cache home** | `QueryClient` (module-level, app-global) | Redux store slice | module-level `SWRConfig` cache | framework-owned **runtime-db** partition of *each frame* (`[:rf.db/runtime :rf.runtime/resources]`); never your app-db, never process-global | **Different by design** |
| **Staleness (SWR semantics)** | `staleTime`; stale-while-revalidate | `keepUnusedDataFor` + refetch triggers | always SWR; `dedupingInterval` | `:stale-after-ms`; `:loaded` entries serve immediately, refetch on next *ensure* when stale | **Landed** |
| **Request deduplication** | in-flight queries coalesce | automatic | `dedupingInterval` window | `ensure` of an in-flight key joins the existing request (one fetch, two owners) | **Landed** |
| **Fresh-skip (cache hit, no fetch)** | fresh query returns cached, no fetch | served from store | within deduping window | fresh `:loaded` `ensure` serves the cached value, attaches the owner, fetches nothing | **Landed** |
| **Garbage collection** | `gcTime` (was `cacheTime`); GC when no observer | `keepUnusedDataFor` after last subscriber | revalidation-driven; weak retention | `:gc-after-ms` after the **last owner lease** is released; timer re-checks owners before collecting | **Landed** |
| **Scope / cache identity boundary** | viewer id is one `queryKey` segment, by convention | baked into arg by convention | part of the key, by convention | **scope is a required, structural key segment**; forgetting it is a loud registration/subscription error, never a silent cross-viewer leak | **Different by design** |
| **Invalidation** | `queryClient.invalidateQueries({queryKey})`, imperative | tag-based (`providesTags` / `invalidatesTags`) | `mutate(key)`, imperative | tag-based, declared as a *consequence of a named mutation*; scoped by default; per-target scoped descriptors; cross-scope is an audited opt-in | **Landed** |
| **Mutations** | `useMutation` | `builder.mutation` | bound `mutate` / `useSWRMutation` | `reg-mutation` + `:rf.mutation/execute`; instance-keyed pending/result/error state; same managed-HTTP transport as reads | **Landed** |
| **Mutation consequences (patch/populate/seed)** | `setQueryData` in `onSuccess` | `onQueryStarted` + `updateQueryData` | `mutate` with `optimisticData`/`populateCache` | declarative `:patches` / `:populates` / `:removes` from the reply, then tag invalidation, with explicit timing | **Landed** |
| **Mutation completion continuation** | `onSuccess` / `onError` callbacks | lifecycle callbacks | promise resolution | call-site `:reply-to` **event** (not a callback); fires only for the accepted terminal reply | **Landed** |
| **Projection / `select`** | `select` option | `selectFromResult` | derived in component | no `:select` key — projections are ordinary subscriptions layered over `[:rf.resource/data …]` | **Different by design** |
| **Optimistic updates + rollback** | `onMutate` snapshot + `onError` rollback | `updateQueryData` + `undo` patch | `optimisticData` + `rollbackOnError` | `:optimistic` (exact-target) / `:optimistic-tags` (tag-addressed) plan applied pre-request; runtime records the inverse; deterministic commit / rollback / reconcile on settle, with `:on-conflict` governing a contested rollback | **Landed** |
| **Polling / refetch interval** | `refetchInterval` | `pollingInterval` | `refreshInterval` | `:poll-interval-ms` — revalidates every N ms while the entry is *actively owned* and the tab is visible; the third freshness timer beside stale/GC | **Landed** |
| **Refetch on window focus / reconnect** | `refetchOnWindowFocus` / `refetchOnReconnect` | `refetchOnFocus` / `refetchOnReconnect` | `revalidateOnFocus` / `revalidateOnReconnect` | `install-revalidation-listeners!` per frame; refetches only entries that are *stale AND owned* | **Landed** |
| **Infinite / load-more** | `useInfiniteQuery` | `infiniteQuery` (recent) | `useSWRInfinite` | `:infinite true` + `:next-page-param` — one scoped feed entry accumulates an ordered page vector; a causal `:rf.resource/load-more` extends it; `:rf.resource/items` is the merged read (numbered/cursor pages stay ordinary resources with `:keep-previous?`) | **Landed** |
| **Keep-previous-data while paging** | `placeholderData: keepPreviousData` | n/a (manual) | `keepPreviousData` | `:keep-previous?` on the route/resource; `:rf.resource/state` projects `:previous-data` / `:previous-key` | **Landed** |
| **SSR / hydration** | `dehydrate` / `HydrationBoundary` | `getRunningQueries` + preload | fallback data | per-request frames; blocking route resources are the render wait point; allowlist projection serialized + hydrated under freshness rules; fresh hydrated entries are not re-fetched | **Landed** |
| **Devtools / observability** | React Query Devtools | RTK devtools (Redux) | external | Xray Resources panel + a `:rf.resource/*` / `:rf.mutation/*` trace family; static registry, live instance table, work-ledger table, route/resource graph, scope-audit + orphaned-owner lints | **Landed** |
| **Normalized / GraphQL cache** | normalizr (external) | partial | external | Apollo/Relay-class — not a resources concern; transport is HTTP-only this phase | **Out of scope** |
| **Offline persistence / cross-tab** | persister plugins | n/a | external | not built; held for a later slice | **Deferred (later slice)** |

Every "Landed" claim above is grounded in [Spec 016 — Resources](../../../spec/016-Resources.md) and the reference implementation under `implementation/resources/`.

### The public surface, at a glance

re-frame2 keeps three lanes strictly separate, and the lane a symbol lives in tells you what it does. (This is the [Spec 016 §Public API](../../../spec/016-Resources.md#public-api) lane table, compressed.)

| Lane | What it is | The surface | Who calls it |
|---|---|---|---|
| **Registration** (functions, at boot) | Declare a handler once — it does not fetch or read | `rf/reg-resource` / `rf/clear-resource`, `rf/reg-mutation` / `rf/clear-mutation`, `rf/reg-resource-scope` / `rf/clear-resource-scope` | app code, once, at startup |
| **Commands** (causal event vectors, dispatched) | *Cause* work — they are not reads | `[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.resource/invalidate-tags …]`, `[:rf.resource/release-owner …]`, `[:rf.resource/clear-scope …]`, `[:rf.resource/remove …]`, `[:rf.resource/load-more …]`, `[:rf.mutation/execute …]` | routes, events, machines |
| **Reads** (passive subscription vectors) | Project runtime state — the only lane a view touches | `[:rf.resource/state …]`, `[:rf.resource/data …]`, `[:rf.resource/status …]`, `[:rf.resource/loading? …]`, `[:rf.resource/fetching? …]`, `[:rf.resource/stale? …]`, `[:rf.resource/error …]`, `[:rf.resource/refresh-error …]`, `[:rf.resource/has-data? …]`, `[:rf.resource/previous-data …]`, `[:rf.resource/items …]`, `[:rf.resource/infinite-state …]`, `[:rf.mutation/state …]` | views, via `subscribe` |

> **The whole `rf/` resource surface is the optional Resources artefact.** `reg-resource`, `reg-mutation`, and `reg-resource-scope` are facade-exported registration *macros/functions* on `re-frame.core`, but they're `advanced` post-v1 capability — late-bound by `day8/re-frame2-resources`, absent from an app that never requires it. The introspection accessors (`rf/resource-meta`, `rf/resource-state`, `rf/resources`) are the tool/test projection lane — not an app-read API; a view that reaches for them instead of a subscription is a category error (they take a one-shot snapshot and never re-render).

Three command names earn a sentence each, because a query-library reader reaches for them and the mapping isn't obvious:

- **`:rf.resource/refetch`** is the imperative bypass — TanStack's `refetch()` / SWR's `mutate(key)` with no data. It forces a fetch regardless of freshness, carrying a `:cause` (`[:manual :article/refresh]`) but usually *no* `:owner` — a manual refresh keeps no lease.
- **`:rf.resource/remove`** evicts one exact entry (scope + resource + params), eagerly, regardless of GC policy — the surgical counterpart to letting GC reclaim it.
- **`:rf.resource/release-owner`** drops an app-minted lease (the matching half of an `:owner` you attached on an `ensure`). Forgetting it is the orphaned-owner leak Xray lints for.

### One registration carries every policy the matrix splits into rows

The scorecard splits a resource's behaviour into a dozen rows — staleness, GC, polling, invalidation tags, scope — but in the source they all live on *one* `reg-resource` call. Seeing them together is worth more than any single row, because it shows that a re-frame2 resource declares its whole policy at the definition site:

```clojure
(rf/reg-resource
  :article/by-slug
  {:doc            "Article detail by slug."
   :params-schema  [:map [:slug :string]]       ;; REQUIRED — identity + canonicalization
   :scope          :rf.scope/global             ;; REQUIRED — the fail-closed leak boundary
   :data-schema    :app/article                 ;; optional — validates decoded data
   :transport      :rf.http/managed             ;; the only built-in transport this phase
   :stale-after-ms 60000                         ;; TanStack staleTime — serve, refetch on next ensure when stale
   :gc-after-ms    300000                        ;; TanStack gcTime — collect N ms after the last owner leaves
   :poll-interval-ms 5000                        ;; refetchInterval — only while actively owned + tab visible
   :tags           (fn [{:keys [slug]} _data]    ;; RTK providesTags — what a mutation can invalidate
                     #{[:article slug]})
   :sensitive      [[:data :ssn]]}               ;; classification — redacted off-box (trace, SSR wire)
  ;; the :request fetch fn is the THIRD slot — NOT a metadata key
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :app/article}))
```

A few keys repay attention because they differ from the query-library defaults you carry in muscle memory:

- **`:params-schema` and `:scope` are the only two required keys**, and both fail loud. A `reg-resource` with no `:scope` throws `:rf.error/resource-missing-scope-policy`; one with no `:params-schema` (or with `:request` mistakenly placed *inside* the metadata map rather than as the third slot) throws `:rf.error/invalid-resource-spec`. There's no "it'll figure out the key from the URL" — identity and the leak boundary are declared, never inferred.
- **`:data-schema` is optional and validate-only.** When present it shape-checks decoded data; when absent the response is taken as-is. It does *not* drive what gets redacted off-box — that's `:sensitive` / `:large` (below). Most resources, including the reference implementation's flagship example, omit it.
- **The three timers are a family.** `:stale-after-ms`, `:gc-after-ms`, and `:poll-interval-ms` are the staleness, GC, and polling rows respectively — all advisory, all owner-aware. An entry with *no* `:gc-after-ms` simply lingers owner-free until something re-leases or removes it, rather than vanishing; `:poll-interval-ms` only ticks while the entry is actively owned and the tab is visible, so a backgrounded or devtool-only entry never drives traffic.
- **`:tags` is a function of `(params data)`**, not a static list. It returns the set of [cache tags](../../resources/glossary.md#cache-tag) this entry *provides* — the RTK `providesTags` analogue — so a mutation's `:invalidates` can address it by tag without naming its exact key.
- **`:sensitive` / `:large` classify for egress**, not validation. They're vectors of paths rooted at the instance projection (`[:data :ssn]`, `[:params :account-id]`); the coarse whole-entry claims `:sensitive?` / `:large?` are the blunt instrument. These govern what the trace bus and the SSR/hydration wire redact — never inferred from the schema. See [Spec 015 — Data Classification](../../../spec/015-Data-Classification.md#subsystem-projection-relative-classification) for the model, and [Data classification](../glossary.md#data-classification) for the one-paragraph version.
