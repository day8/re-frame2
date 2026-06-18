# Server state: resources

Your app shows articles, feeds, profiles. That data's real home is a server, so what you hold on the client is only ever a copy — and that copy starts going stale the moment it arrives. **Server state is the state your app doesn't own — hold it as a declared, inspectable cache, not a private fetch.** This page is the model behind [Part 2 of the tutorial](../tutorial/02-server-data.md): what a resource is, where its cache lives, who is allowed to fetch, and why a logged-out user can never see the previous user's data.

If you've used TanStack Query in a React app, you already know the shape of this. (RTK Query and SWR are the same family.) It's a keyed cache of server reads with staleness, request deduplication, invalidation, and garbage collection. Keep that mental model — it carries you most of the way. Then hold three deliberate differences, because they're where re-frame2 makes choices the query libraries leave to you:

| TanStack Query | re-frame2 resources |
|---|---|
| `useQuery` in a component fetches on mount | **Views never fetch.** A view reads a subscription; a *route entry or event* causes the fetch |
| The viewer id goes in the `queryKey` — by convention, hand-assembled at every call site | **Scope is a required, structural key segment.** Forgetting it is a loud error, not a silent leak |
| Invalidation is an imperative `queryClient.invalidateQueries(...)` call you remember to make | **Invalidation is causal** — a declared consequence of a named write, visible on the event record |

## The cache you don't own

Every SPA answers the same five questions, usually privately and re-decided per feature. Where does the cached copy live? When is it stale? Who may refetch it? What happens when two screens want the same data? And how does logout stop the next user from reading this user's cache? A **resource** — a named server read you register once — turns those private answers into declared data: a cache scope, a params schema, a request, and a staleness policy, all in one place. The runtime then owns everything between the declaration and the pixels.

The cache itself lives in the framework-owned *runtime partition* of frame state — the slice of a [frame](frames.md) (one running instance of your app) that the framework manages on your behalf. That's deliberately **not** your [app-db](app-db.md) (your app's single state map). An ordinary event handler — the function that runs in response to a dispatched event — can't accidentally wipe it. You read it through subscriptions (declared, cached derivations of state) and change it only through events.

> **Coming from re-frame v1?** You hand-built this: an event fires the HTTP effect, writes a `{:status :data :error}` slice into app-db, and a sub reads it back. Resources keep that causal shape — reads are subs, fetches are events — and move the per-read bookkeeping into the framework.

!!! note "Resources are optional — don't reach for them on day one"

    Resources are an optional artefact (`day8/re-frame2-resources`, required once at boot as `re-frame.resources`). An app with one or two reads is perfectly fine with [a managed HTTP request](http.md) and a small app-db slice, and that's the simpler choice. Reach for resources when cached server reads start multiplying; [Where should this value live?](../where-state-lives.md) has the decision table.

## A resource is a sub you read and a cause you fire

That sentence is the whole model. You register the read once:

```clojure
;; Adapted from examples/reagent/realworld_resources/resources.cljs
(ns app.resources
  (:require [re-frame.core :as rf]
            [re-frame.http-managed]    ;; the managed-HTTP transport
            [re-frame.resources]))     ;; boots the optional artefact

(rf/reg-resource :realworld/article
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global     ;; REQUIRED — an explicit, auditable claim
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get
                                :url    (str "/api/articles/" slug)}
                      :decode  :json})
   :stale-after-ms 60000                ;; fresh for a minute; then refetch on next ensure
   :gc-after-ms    (* 5 60 1000)        ;; reclaim after 5 min with no owner
   :tags           (fn [{:keys [slug]} _data]
                     #{[:article slug] [:article-list]})})
```

Four keys carry the model. `:params-schema` defines the read's identity, so every variable that changes the server's answer — the slug, the page number, the filter — must live in params. `:scope` declares whose cache this is (next section). `:tags` name the facts the data contains, so a later write can invalidate exactly the reads it broke. And `:stale-after-ms` and `:gc-after-ms` are the freshness and lifetime policy — without them, an entry stays fresh until you explicitly invalidate it.

Once it's registered, a **cause** is what makes it fetch — a cause is just the reason a fetch happened. You declare it on a route (next section), or dispatch `[:rf.resource/ensure {:resource :realworld/article :params {:slug "welcome"} :cause [:event :article/opened]}]` from an event. A **view** (a function that renders UI) reads it passively, through an ordinary subscription, and never fetches:

```clojure
;; Adapted from examples/reagent/realworld_resources/views.cljs
(rf/reg-view article-page [slug]
  (let [state @(subscribe [:rf.resource/state    ;; reg-view injects `subscribe`
                           {:resource :realworld/article
                            :params   {:slug slug}}])]
    (cond
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else
      [:<>
       (when (:fetching? state)     [refresh-indicator])
       (when (:refresh-error state) [refresh-warning (:refresh-error state)])
       [article-view (:data state)]])))
```

### Three lanes — registering, causing, projecting

Those three steps are three different jobs, and the surface is much easier to hold once you stop reading them as competing APIs. **Registering a resource is not the same as reading its state**, and neither is the same as causing it to fetch:

| Lane | What it does | The spelling | Who uses it |
|---|---|---|---|
| **Register** | Declare the handler, once, at boot | `(rf/reg-resource …)` / `(rf/reg-mutation …)` — plain functions | Author |
| **Cause** | Make a fetch or a write happen | `(rf/dispatch [:rf.resource/ensure …])`, `[:rf.mutation/execute …]` — event vectors | Routes, events, machines |
| **Project (app read)** | Read the runtime state into a view | `@(subscribe [:rf.resource/state …])` — subscription vectors | Views |

`reg-resource` records a handler in the registrar — exactly like `reg-event` or `reg-sub` records one. It does **not** fetch anything and it does **not** read any cache; it only teaches the runtime *how* to. The cache only fills when a cause fires, and a view only sees it through a subscription. So "I registered the resource but my view is empty" almost always means a cause hasn't fired yet, not that registration failed.

!!! note "`resource-state` and `mutation-state` are tool/test projections, not a second app-read API"

    You'll also find direct functions — `(rf/resource-state {:resource … :scope … :params … :frame …})`, `(rf/resources {:frame …})`, `(rf/mutation-state {:instance … :frame …})`. These project the *same* runtime state the `[:rf.resource/*]` subscriptions read, but as a one-shot, non-reactive snapshot at an explicit frame. They exist for tools (Xray), unit tests, and SSR serialization — places with no reactive subscription context. **In a view, always project through a subscription**: a `resource-state` call won't re-render when the data changes, so reaching for it in UI is reading the right value through the wrong lane.

## Routes declare what a page needs

The cleanest cause is the page itself, because a page already knows what data it needs. `:resources` is [route](routing.md) metadata that says, in effect, "this page needs this server state":

```clojure
;; Adapted from examples/reagent/realworld_resources/routing.cljs
(rf/reg-route :realworld/article
  {:path      "/articles/:slug"
   :params    [:map [:slug :string]]
   :resources [{:resource  :realworld/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}
               {:resource  :realworld/comments
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? false}]})
```

On entry, the runtime ensures each resource with the route as its **owner** — the thing keeping the entry alive. On leave, or on a superseding navigation, it releases them. `:blocking? true` holds the route transition until that read settles, which also gives server-side rendering a natural wait point. A non-blocking resource fetches in the background instead. And `:keep-previous? true` on a paginated list keeps the prior page visible while the next one loads, so paging never flashes a skeleton ([Paginate a feed](../how-to/paginate-a-feed.md) is the recipe).

Navigate to an article page with Xray open: the route-entry event row shows the ensure it caused, and the Resources panel shows the entry move from `:idle` through `:loading` to `:loaded`. Visit the same article a second time and you get a cache hit — no network row at all.

## The scoped key: a leak boundary that fails closed

A cache entry's identity is a triple:

```clojure
[scope  resource-id  canonical-params]
```

Params say *which* article. **Scope** says *whose* cache: the user, tenant, locale, or impersonation boundary that must never leak between viewers. This is the part that trips people up coming from a query-key library, where the viewer id is one key segment you assemble by hand at every call site — forget it once and one user silently reads another's cache. Here scope is a required declaration at registration, with no default. A read that can't resolve its scope raises a structured error; it **never** falls through to a silent shared read. The failure mode is loud, not leaky.

A genuinely public read declares `:scope :rf.scope/global` — an explicit, auditable claim that every viewer gets the same answer. A viewer-relative read names a **scope resolver** once and references it everywhere:

```clojure
;; Adapted from examples/reagent/realworld_resources/scope.cljs
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username
                [:rf.scope/session {:username username}]))})

;; Adapted from examples/reagent/realworld_resources/resources.cljs
(rf/reg-resource :realworld/feed
  {:params-schema [:map [:page {:optional true} [:maybe :int]]]
   :scope         {:from-db :realworld/session}   ;; whose feed? resolved from app-db
   :request       (fn [{:keys [page]} _ctx]
                    {:request {:method :get
                               :url    "/api/articles/feed"
                               :params {:limit 10 :offset (* 10 (dec (or page 1)))}}
                     :decode  :json})
   :tags          (fn [_params _data] #{[:feed]})})
```

The resolver is pure, and its declared `:inputs` are the app-db facts that decide the scope. That buys two structural properties:

- **Subscriptions re-key when the viewer changes.** At login, logout, or account switch, the same feed subscription re-points to the *new* viewer's cache entry. During the switch it reads the new key's state, typically `:idle` or `:loading` — never the previous viewer's data.
- **Nil resolution fails closed.** Logged out, the resolver yields `nil`. A route entry simply doesn't plan the feed, and a subscription raises a loud "scope unresolved" diagnostic. There is no path from "I forgot the viewer" to "I served someone else's cache."

There's one honest consequence of "subscriptions are passive": a re-keyed subscription does not fetch. The new viewer's data loads only when something *causes* it — usually the route re-entering after login. So suppose your app switches viewer with no route change, like a cold-boot session restore. Then you dispatch an explicit `:rf.resource/ensure` under the new scope, carrying an app-minted owner lease with a matching release at logout so the entry stays alive. Without that lease, the entry just sits at `:idle`.

## What a view sees: five statuses

The `:rf.resource/state` subscription projects one view-model with five statuses:

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted | Nothing, or a placeholder |
| `:loading` | First load, no usable data yet | A skeleton |
| `:fetching` | Refreshing while prior data stays visible | The data, plus a quiet refresh indicator |
| `:loaded` | Usable data present (possibly stale) | The data |
| `:error` | First load failed — no usable data | An error |

Two invariants keep views simple. First, **`:error` is reserved for first-load failure**. A failed *background* refresh keeps the entry `:loaded` with its prior data and records the failure separately in `:refresh-error`, so users keep reading last-known-good data through a flaky network. Second, **freshness is orthogonal to status**. A `:loaded` entry may well be stale; staleness only decides whether the next ensure refetches, not what the view renders now. The derived booleans — `:loading?`, `:fetching?`, `:has-data?`, `:stale?` — exist so a view never has to reverse-engineer these rules. The three-branch `cond` above is the canonical render shape.

That status enum is the *data lifecycle* only, and a real page has more render states than a cache entry does. The tutorial builds the full set of **nine page states**: Nothing, Loading, Empty, One, Some, Too Many, Incorrect, Correct, and Done. The resource feeds the first two and the error branch. The cardinality states (Empty / One / Some / Too Many) fall out of counting the loaded data. Incorrect and Correct come from form state, and Done from domain state — both living in your app-db. So the page's render decision is one derivation over the cache entry plus the page's own app-db state, and [Part 2](../tutorial/02-server-data.md) makes it concrete.

## Owners and causes, and the refetch rules

Query libraries talk about *observers* — a mounted hook keeps a query alive. Resources split that one idea into two that never blur, because keeping a thing alive and explaining why it fetched are different concerns:

- An **owner** is a liveness lease: "this route (or machine, or explicit app lease) needs this entry." Owners decide whether invalidation refetches now or just marks stale, and whether GC may reclaim the entry. Every owner has a defined releaser — the route on leave, a machine on destroy, an app-minted lease via a matching release event.
- A **cause** is an explanation: "why did this fetch happen?" — a route entry, a click, an invalidation, a focus return. Causes change nothing about liveness; they exist so the trace can answer *why*.

The race rules are few, and worth knowing cold:

- **Ensure of a fresh entry is a cache hit** — no fetch, the cached value serves immediately.
- **Ensure while the same key is in flight joins the existing request** — two screens asking for the same article fire one fetch.
- **An explicit refetch starts a new generation**, superseding any in-flight attempt.
- **Cancellation is opportunistic; stale-reply suppression is mandatory.** When an owner leaves or a newer generation starts, the runtime aborts the request if it can. Even when it can't, a generation check guarantees a late reply can never overwrite a newer entry. Abort saves bandwidth; suppression is what makes it *correct*.

The familiar refetch-on-window-focus behaviour is opt-in per frame — `(rf/install-revalidation-listeners! frame-id)` at boot (client-only). It refetches only entries that are both stale *and* still owned, so a tab return never triggers a fetch storm for data nothing is showing.

### Polling: keep this fresh every N ms

For a dashboard, a build-status badge, a notification count, or a "is this long job done yet" read, declare a **`:poll-interval-ms`** on the resource and the runtime re-reads it on that interval — no `setInterval`, no manual unmount/tab-hide bookkeeping:

```clojure
(rf/reg-resource :dashboard/build-status
  {:scope            :rf.scope/global
   :params-schema    [:map [:repo :string]]
   :poll-interval-ms 5000          ;; re-read every 5s while actively owned + the tab is visible
   :request (fn [{:keys [repo]} _ctx]
              {:request {:method :get :url (str "/repos/" repo "/build")} :decode :json})
   :tags    (fn [_ _] #{[:build]})})
```

Polling is **owner-driven**: it runs only while the entry has an active owner (a route, a machine, or an app-minted `[:lease …]`) and pauses automatically while the tab is hidden — so it stops the instant nothing is looking at it. A poll tick refetches *unconditionally by the interval* (cause `:poll`, never an owner), coalesces with any in-flight work (a slow endpoint never stacks overlapping requests), and a failed poll keeps the prior data and keeps polling.

Three freshness tools, three questions:

- **Polling (`:poll-interval-ms`)** — "this changes on its own; keep it fresh on a clock" (build status, queue depth, presence).
- **Focus/reconnect revalidation** — "refresh stale data when the user comes back" (the default freshness most reads want; cheap, event-driven, no clock).
- **Invalidation (a mutation's `:invalidates`)** — "*this* write made *that* read wrong; refresh it now" (the causal, surgical refresh after a known change).

They compose: a polled entry still revalidates on focus and still gets invalidated by a write — the in-flight coalescing gate keeps the overlap to one fetch.

## Writes invalidate by tag — causally

In TanStack Query, keeping reads honest after a write is a call you remember to make in `onSuccess`. Here it's a declaration on the write itself, so there's nothing to remember. A **mutation** is a named causal write; on success it invalidates the tags it broke, through the same scoped machinery, recorded on the event record:

```clojure
;; Adapted from examples/reagent/realworld_resources/mutations.cljs
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :post
                               :url    (str "/api/articles/" slug "/favorite")}
                     :decode  :json})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])})
```

Entries with live owners refetch; unowned entries are marked stale and refetch when next ensured. Invalidation is **scoped by default**, so a write can't accidentally stale another tenant's cache. The operational details — instance-keyed pending state, `:reply-to` continuations, seeding the cache from the reply — live in [Part 4](../tutorial/04-mutations-and-invalidation.md) and [Invalidate after a mutation](../how-to/invalidate-after-a-mutation.md).

## Optimistic writes commit, roll back, or reconcile

The pattern above settles the cache only when the server replies. For a write that should *feel* instant — the favorite heart flips on click, the count ticks up — a mutation may declare an **optimistic plan** that patches the cache *before* the request is sent, then commits, rolls back, or reconciles when the reply settles. This is the re-frame2 counterpart of TanStack Query's `onMutate`/`onError` rollback (and SWR's `optimisticData` + `rollbackOnError`).

You declare the forward change; the runtime records the inverse for you, so a rollback restores exactly the entry that existed — never an author-written inverse that can drift from the forward patch:

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :post
                               :url    (str "/api/articles/" slug "/favorite")}
                     :decode  :json})
   ;; Patch every cached entry carrying these tags, in its scope, before the
   ;; request leaves — the detail, every list, and the session feed flip at once.
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [article] (update-favorite article true))}])
   ;; The accepted reply still seeds the authoritative value and invalidates.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :realworld/article :params {:slug slug}} result})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session} :tags #{[:feed]}}])})
```

Two forward forms exist: `:optimistic` patches **exact** cache targets (the twin of `:populates`/`:patches`), and `:optimistic-tags` patches **every** entry carrying a tag in its scope (the twin of tag-addressed `:invalidates`) — the cross-view-consistency case the author can't enumerate by key. Both are exact-key or tag-within-named-scope only, and **fail closed**: a `{:from-db …}` scope that resolves to `nil` drops that target rather than writing globally, so an optimistic write can never leak across viewers.

Settle is deterministic, decided on recorded facts (the generation acceptance verdict and a per-entry `:revision`), so there's no wall-clock race:

- an accepted **`:ok`** reply **commits** — the authoritative `:populates`/`:patches` overwrite the optimistic value with the server's, then `:invalidates` runs.
- an accepted **`:error`**/`:cancelled` reply **rolls back** — the recorded inverse is restored.
- a **stale/superseded** reply rolls back nothing; the current generation already owns the entry.

The one wrinkle is a *contested* rollback — a concurrent write landed on the entry between your apply and the failure. `:on-conflict` governs it: the default **`:invalidate`** declines to restore a now-stale inverse and instead marks the entry stale so the read path refetches the authoritative value (re-frame2's deliberate divergence from the query libraries' unconditional context-restore); `:force` restores the inverse anyway (the single-writer escape, with a tooling warning). A view renders the in-flight optimistic state from the instance sub's derived **`:optimistic?`** flag. The normative contract is [Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations).

## Logout is one causal event

The cross-session leak — user B sees user A's dashboard — has a structural fix here. Every session-scoped entry lives under A's scope, and logout clears that scope. There's one subtlety worth pausing on: resolve the *old* scope from the handler's coeffect db (the read-only inputs handed to the handler), which still carries the logging-out user, *before* you remove the auth slice:

```clojure
;; Adapted from examples/reagent/realworld_resources/auth.cljs
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx (cond-> []
             old-scope
             (conj [:dispatch [:rf.resource/clear-scope
                               {:scope old-scope :cause :logout}]]))})))
```

`clear-scope` removes every entry in that scope, releases its owners, and aborts or suppresses its in-flight requests. It leaves every other scope untouched, including still-valid global reads like article lists. Compare the query-key world, where logout is either `queryClient.clear()` (which drops the global cache too) or a hand-maintained list of keys to forget — both more brittle than clearing one scope.

## SSR and hydration

On the server, each request renders in its own frame, which matters because a process-global cache would itself be a cross-user leak. Blocking route resources are the render's wait point. The settled entries are serialized (sensitive data redacted) and shipped with the page, and on the client, hydration installs them under the same freshness rules. A hydrated entry that's still fresh is **not** refetched, so there's no duplicate-fetch flash on first paint; a stale one background-refreshes by policy. Hydration never crosses scopes — the serialized scope and the client's resolved scope must agree before hydrated data is usable. The mental model is in [Server-side rendering](ssr.md), and `examples/reagent/resources_ssr/` is the worked demo.

## When resources are the wrong tool

!!! note "Reach for something simpler in these cases"

    - **A handful of reads, no caching story.** A [managed HTTP request](http.md) plus a small app-db slice is less machinery and entirely idiomatic.
    - **Login and other commands.** Auth is a state machine driving a write — don't contort it into a cached read.
    - **GraphQL.** The transport is HTTP-only for now; GraphQL is a planned later phase.

A mutation *can* now flip the UI immediately and reconcile when the server replies: an **optimistic mutation** patches the cache before the request is sent and commits, rolls back, or reconciles on settle. That's a property of the mutation, not a reason to avoid resources — see [Optimistic writes commit, roll back, or reconcile](#optimistic-writes-commit-roll-back-or-reconcile) below.

---

**You can now:**

- say what server state is — a cache of truth you don't own — and where re-frame2 keeps it
- declare a page's data on its route and read it passively from a view, with the five-status render shape
- explain why a forgotten scope is an error here, not a silent cross-user leak
- tear down a user's cached server state at logout with one causal event
- recognise the cases where a plain managed HTTP call beats a resource
