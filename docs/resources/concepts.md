# Server state: resources

Your app shows articles, feeds, profiles. That data's real home is a server, so what you hold on the client is only ever a copy — and that copy starts going stale the moment it arrives. **Server state is the state your app doesn't own — hold it as a declared, inspectable cache, not a private fetch.**

This page builds that idea up one step at a time. We'll register a single server read, trigger a fetch from a route, read the result passively from a view, and render the five statuses the read can be in. Once that loop is solid we'll layer on the harder questions — whose cache is this, what happens at logout, how a write keeps reads honest — each with a small example first. It's the full model behind what [Part 2 of the tutorial](tutorial/02-server-data.md) builds, and the page the rest of this section leans on for definitions.

??? info "Coming from TanStack Query?"

    You already know the shape: a keyed cache of server reads with staleness, request deduplication, invalidation, and garbage collection. (RTK Query and SWR are the same family.) Keep that mental model — it carries you most of the way. Three deliberate differences are flagged as `> **Coming from TanStack Query?**` callouts as we go: views never fetch, scope is a required key segment, and invalidation is causal rather than an imperative call you remember to make.

!!! note "Resources are optional — don't reach for them on day one"

    Resources ship as a separate, opt-in library (the Maven coordinate `day8/re-frame2-resources`; you switch it on by requiring `re-frame.resources` once at boot). An app with one or two reads is perfectly happy with [a managed HTTP request](../async/http.md) and a small [app-db](../core/glossary.md#app-db) slice, and that's the simpler choice. Reach for resources when cached server reads start *multiplying*; [Where should this value live?](../core/where-state-lives.md) has the decision table.

## The cache you don't own

Every SPA answers the same five questions, usually privately and re-decided per feature. Where does the cached copy live? When is it stale? Who may refetch it? What happens when two screens want the same data at once? And how does logout stop the next user from reading this user's cache?

A **[resource](glossary.md#resource)** — a named server read you register once — turns those five private answers into declared data: a cache scope, a params schema, a request, and a staleness policy, all in one place. The runtime then owns everything between the declaration and the pixels.

That cache lives in **[runtime-db](../core/glossary.md#runtime-db)** — the framework-owned half of a [frame](../core/glossary.md#frame) (one running instance of your app), addressed by the projection path `:rf.db/runtime`. The resource cache is one runtime-db subsystem, addressed `:rf.runtime/resources`; [app-db](../core/concepts/app-db.md) introduces [the two partitions](../core/glossary.md#the-two-partitions) in full. It is **not** your app-db — so an ordinary [event handler](../core/glossary.md#event-handler) can't reach in and wipe it by accident. You read it through [subscriptions](../core/glossary.md#subscription) and change it only through [events](../core/glossary.md#event). Same in / out discipline as the rest of re-frame2; only the storage moved next door.

??? info "From re-frame v1"

    You hand-built this: an event fires the HTTP effect, writes a `{:status :data :error}` slice into app-db, and a sub reads it back. Resources keep that causal shape — reads are subs, fetches are events — and move the per-read bookkeeping into the framework.

## Your first resource: register it

A resource is *a subscription you read and a cause you fire.* That one sentence is the whole model. (A **cause** is just an event that triggers a fetch — a route entry, a click, a write. We say "cause" rather than "trigger" because re-frame2 records *why* every fetch happened; more on that below.) And the simplest possible resource fits on a screen — you register the read once, at boot:

```clojure
;; Adapted from examples/real-apps/realworld_resources/resources.cljs
(ns app.resources
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]    ;; the managed-HTTP transport
            [re-frame.resources]))     ;; boots the optional artefact

(rf/reg-resource :realworld/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}       ;; REQUIRED — an explicit, auditable claim
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get
               :url    (str "/api/articles/" slug)}
     :decode  :json}))
```

Two keys carry the minimum model. `:params-schema` defines the read's *identity*: every variable that changes the server's answer — the slug, the page number, a filter — must live in params. `:scope` declares *whose* cache this is; `:rf.scope/global` is the claim "every viewer gets the same answer" (we'll meet viewer-relative scope shortly). The function in the third slot describes the request itself — method, url, `:decode`.

`reg-resource` records a handler in the [registrar](../core/glossary.md#registrar), exactly like `reg-event` or `reg-sub` does. It does **not** fetch anything, and it does **not** read any cache; it only teaches the runtime *how* to. The cache fills only when a *cause* fires, and a [view](../core/glossary.md#view) only ever sees it through a subscription.

!!! warning "Gotcha — the metadata is the middle slot; the request is the third"

    The shape is a strict 3-slot grammar: `(rf/reg-resource resource-id metadata request-fn)`. The `:request` fetch function is the **third** slot, *never* a `:request` key inside the metadata map. Putting it in the metadata is a loud `:rf.error/invalid-resource-spec`, not a silent no-op. `reg-mutation` (later) follows the same three-slot shape.

!!! warning "Gotcha — the `_ctx` second argument is reserved, currently `nil`"

    Every author-supplied function this artefact calls (`:request`, a scope resolver's `:resolve`) receives a trailing `ctx` that is **literal nil** in this slice — declared for forward-compatibility, not to be relied on. Derive your request from `params` and your scope from declared `:inputs`, never from `ctx`. (The one exception is a route-resource `:params` / `:scope` / `:when` function, which carries a populated route context, because route-entry planning has a real match to thread.)

## Cause it to fetch from a route

The cleanest cause is the page itself, because a page already knows what data it needs. `:resources` is [route](../routing/glossary.md#route) metadata that says, in effect, "this page needs this server state":

```clojure
;; Adapted from examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :realworld/article
  {:params    [:map [:slug :string]]
   :resources [{:resource  :realworld/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]}
  "/articles/:slug")
```

On entry, the runtime ensures the resource with the route as its **owner** — the thing keeping the cache entry alive. On leave, or on a superseding navigation, it releases it. `:blocking? true` holds the route transition until that read settles (which also hands server-side rendering a natural wait point); a non-blocking resource fetches in the background instead.

Navigate to an article page with [Xray](../core/glossary.md#xray) (the dev inspector) open and you can watch the whole [event cascade](../core/glossary.md#event-cascade): the route-entry event row shows the ensure it caused, and the Resources panel shows the entry move from `:idle` through `:loading` to `:loaded`. Visit the same article a second time and you get a cache hit — no network row at all.

??? info "Coming from TanStack Query?"

    Difference one of three: **views never fetch.** With `useQuery`, the component fetches on mount. Here a *route entry or event* causes the fetch; the view only reads. That separation is what lets the same view render on the server, in a test, or after a cache hit with no network at all — the render never has a side effect hiding inside it.

??? info "From re-frame v1"

    A route containing `:resources` only validates because the resources artefact teaches the router about that key. The router rejects unknown bare route-metadata keys by default — so if you forget to `(:require [re-frame.resources])` at boot, a route with `:resources` is correctly rejected at registration. That's the [fail-loud](../core/glossary.md#fail-loud-not-silent) ethos: a typo'd or un-loaded feature fails at registration, not as a mysterious empty page. `:resources` sits *beside* `:on-match` (still the canonical hook for arbitrary route-entry work), not in place of it.

## Read it from a view

A view reads the entry passively, through an ordinary subscription, and never fetches. The `:rf.resource/state` subscription hands you a single ready-to-render map — a *view-model* carrying everything the view needs to decide what to show — and `resources/sub-resource` is the named sugar that reads it:

```clojure
;; Adapted from examples/real-apps/realworld_resources/views.cljs
(rf/reg-view article-page [slug]
  (let [state @(resources/sub-resource {:resource :realworld/article
                                        :params   {:slug slug}})]
    (cond
      (:loading? state)                              [article-skeleton]
      (and (:error state) (not (:has-data? state)))  [article-error (:error state)]
      :else
      [:<>
       (when (:fetching? state)     [refresh-indicator])
       (when (:refresh-error state) [refresh-warning (:refresh-error state)])
       [article-view (:data state)]])))
```

That three-branch `cond` is the canonical render shape, and the next section explains the statuses it's switching on. The thing to notice now is that the view does nothing but *read and render* — no fetch, no effect.

??? info "Coming from TanStack Query?"

    There is no `:select` key, and that's deliberate. `useQuery` lets you pass `select` to derive a slice. Here a projection is just an ordinary subscription layered over `[:rf.resource/data …]`, the same way you'd derive anything else in [the derivation graph](../core/glossary.md#the-derivation-graph) — which already gives you memoised, shared, re-render-minimal derivations for free. A query-local `:select` would be a worse version of a thing you already have.

!!! warning "Gotcha — no subscription ever fetches"

    This is the rule that surprises query-library refugees most. A subscription that finds no entry reads `:idle` and *stays* `:idle` until a route entry, an event, or a [machine](../machines/glossary.md#machine) causes the fetch. So "I registered the resource but my view is a permanent skeleton" almost always means a cause hasn't fired yet — you're missing a cause, not a sub.

### Three lanes — registering, causing, projecting

Those three steps you just wrote are three different jobs, and the whole surface gets easier to hold once you stop reading them as competing APIs. **Registering a resource is not the same as reading its state, and neither is the same as causing it to fetch:**

| Lane | What it does | The spelling | Who uses it |
|---|---|---|---|
| **Register** | Declare the handler, once, at boot | `(rf/reg-resource …)` / `(rf/reg-mutation …)` — plain functions | Author |
| **Cause** | Make a fetch or a write happen | `(rf/dispatch [:rf.resource/ensure …])`, `[:rf.mutation/execute …]` — event vectors | Routes, events, machines |
| **Project (app read)** | Read the runtime state into a view | `@(subscribe [:rf.resource/state …])` — subscription vectors | Views |

A route's `:resources` is one *cause* spelling; dispatching `[:rf.resource/ensure {:resource :realworld/article :params {:slug "welcome"} :cause [:event :article/opened]}]` from an event handler is another. Both make the same entry fetch.

## What a view sees: five statuses

The `:rf.resource/state` subscription projects one view-model with five statuses. Learn these five and the render `cond` above stops being mysterious:

| `:status` | Meaning | Show |
|---|---|---|
| `:idle` | No load attempted | Nothing, or a placeholder |
| `:loading` | First load, no usable data yet | A skeleton |
| `:fetching` | Refreshing while prior data stays visible | The data, plus a quiet refresh indicator |
| `:loaded` | Usable data present (possibly stale) | The data |
| `:error` | First load failed — no usable data | An error |

Two invariants keep views simple. First, **`:error` is reserved for first-load failure.** A failed *background* refresh keeps the entry `:loaded` with its prior data and records the failure separately in `:refresh-error`, so users keep reading last-known-good data straight through a flaky network. Second, **freshness is orthogonal to status.** A `:loaded` entry may well be stale; staleness only decides whether the next ensure refetches, not what the view renders now.

The derived booleans — `:loading?`, `:fetching?`, `:has-data?`, `:stale?` — exist so a view never has to reverse-engineer those two rules. That's why the canonical `cond` reads them directly rather than matching on `:status`.

??? note "Going deeper"

    That status enum is the *data lifecycle* only — a real page renders from this cache entry *plus* its own app-db (cardinality, form, and domain state), so the page's render decision is one derivation over both. [Part 2](tutorial/02-server-data.md) builds that full set of nine page states.

## The full read and command surface

You now have the working loop. Here is the rest of the vocabulary, in one place, for when the simple case isn't enough.

**Reads** are subscription vectors — all passive, all taking the same `{:resource :scope :params}` key. `:rf.resource/state` is the one you'll reach for most, but the narrower projections exist so a view can subscribe to *just* the fact it cares about and re-render only when *that* fact changes:

```clojure
[:rf.resource/state         {:resource … :scope … :params …}]  ;; the whole view-model
[:rf.resource/data          {:resource … :scope … :params …}]  ;; just :data
[:rf.resource/status        {:resource … :scope … :params …}]  ;; the status keyword
[:rf.resource/loading?      {:resource … :scope … :params …}]
[:rf.resource/fetching?     {:resource … :scope … :params …}]
[:rf.resource/stale?        {:resource … :scope … :params …}]
[:rf.resource/error         {:resource … :scope … :params …}]
[:rf.resource/refresh-error {:resource … :scope … :params …}]
[:rf.resource/has-data?     {:resource … :scope … :params …}]
[:rf.resource/previous-data {:resource … :scope … :params …}]  ;; prior page (:keep-previous?)
```

**Commands** are dispatched event vectors that *cause* work — each takes a map payload, never positional args:

```clojure
;; Cause a fetch (cache hit if fresh; joins an in-flight request for the same key)
[:rf.resource/ensure   {:resource … :scope … :params … :owner … :cause …}]
;; Force a refresh — starts a new generation, supersedes any in-flight attempt
[:rf.resource/refetch  {:resource … :scope … :params … :cause …}]
;; Mark matching cached reads stale (refetches the owned ones)
[:rf.resource/invalidate-tags {:scope … :tags #{…} :cause …}]
;; Drop a liveness lease (the matching release for an app-minted :owner)
[:rf.resource/release-owner   {:owner …}]
;; Tear down a whole scope at logout / account switch (later)
[:rf.resource/clear-scope     {:scope … :cause …}]
;; Evict one exact entry now, regardless of GC policy
[:rf.resource/remove   {:resource … :scope … :params …}]
```

The most common one after `ensure` is **`:rf.resource/refetch`** — your "pull to refresh" / manual refresh button. It forces a new generation and supersedes any in-flight attempt, so a user mashing the refresh button never stacks requests. Pass a `:cause [:manual :article/refresh]` so the trace can explain the fetch; don't pass an `:owner` (a refresh keeps nothing alive — it just refreshes what's already owned).

??? note "Going deeper"

    You'll also find direct functions — `(rf/resource-state {:resource … :scope … :params … :frame …})`, `(rf/resources {:frame …})`, `(rf/resource-meta :realworld/article)`, `(rf/mutation-state {:instance … :frame …})`, `(rf/mutations {:frame …})`. The `*-meta` functions project the *registration*; `*-state` / `resources` / `mutations` project the *same runtime state* the `[:rf.resource/*]` subscriptions read, but as a one-shot, non-reactive snapshot at an explicit frame. They exist for tools (Xray), unit tests, and SSR serialization — places with no reactive subscription context. **In a view, always project through a subscription:** a `resource-state` call won't re-render when the data changes, so reaching for it in UI is reading the right value through the wrong lane. (And `:frame` is an explicit, app-registered frame id — there's no ambient default, so a frameless introspection call with no resolvable context [fails closed](../core/glossary.md#fail-loud-not-silent) rather than peeking at the wrong frame.)

## Freshness and lifetime: the policy keys

So far our resource has stayed fresh forever, refetching only when something forces it. Two more registration keys give it a freshness and a lifetime policy:

```clojure
(rf/reg-resource :realworld/article
  {:params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :stale-after-ms 60000                ;; fresh for a minute; then refetch on next ensure
   :gc-after-ms    (* 5 60 1000)        ;; reclaim after 5 min with no owner
   :tags           (fn [{:keys [slug]} _data]
                     #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)} :decode :json}))
```

`:stale-after-ms` is the freshness window — after it elapses, the next *ensure* refetches; absent, the entry stays fresh until explicitly invalidated. `:gc-after-ms` is the lifetime once the entry goes *owner-free* — absent, it lingers unowned until something re-leases or removes it. And `:tags` *label what this data is about*: a tag is a vector like `[:article slug]`, read as "this entry holds article `slug`." Those labels are how a later write says "I changed article `slug`" and refreshes exactly the reads that showed it — the machinery the "Writes invalidate by tag" section builds on.

The full **registration metadata** is small and worth knowing in one glance. These are the keys `reg-resource`'s middle slot accepts:

| Key | Required? | What it does |
|---|---|---|
| `:params-schema` | **yes** | A Malli [schema](../core/glossary.md#schema) validating and canonicalising params. Defines the read's identity; missing it is `:rf.error/invalid-resource-spec`. |
| `:scope` | **yes** | The scope *policy* — `:rf.scope/global`, a `{:from-db <id>}` resolver reference, or `:rf.scope/from-caller`. Missing it is `:rf.error/resource-missing-scope-policy`. |
| `:tags` | no | `(fn [params data] -> #{tag …})` naming the facts the data carries, so a write can invalidate exactly the reads it broke. A tag is a *vector* (`[:article slug]`). |
| `:stale-after-ms` | no | Freshness window. After this, the next *ensure* refetches. Absent ⇒ fresh until explicitly invalidated. |
| `:gc-after-ms` | no | Lifetime after the entry goes owner-free. Absent ⇒ the entry lingers unowned until something re-leases or removes it. |
| `:poll-interval-ms` | no | Re-read on a clock while actively owned and the tab is visible (see [Polling](#polling-keep-this-fresh-every-n-ms)). |
| `:data-schema` | no | Validates *successful* data where the transport decode supports it. Validation only — it does **not** drive egress classification. |
| `:sensitive` / `:large` | no | Projection-relative path-vectors (`[[:data :ssn] [:params :account-id]]`) marking which slots redact / summarise at every egress boundary (SSR, tools, trace) — the framework's [data classification](../core/glossary.md#data-classification) applied to a cache entry. The coarse whole-entry `:sensitive?` / `:large?` booleans are the degenerate root-prop case. |
| `:transport` | no | `:rf.http/managed` is the only built-in (and the default); the model is transport-neutral so a later transport can plug in. |
| `:infinite` | no | `true` registers a load-more / infinite-scroll feed instead of a keyed cache — a different kind, with its own `:next-page-param` / `:page->items` keys and `load-more` surface. See [Infinite feeds](#infinite-feeds-accumulate-pages-with-infinite). |

!!! warning "Gotcha — the `:request` function describes the domain request only"

    It MUST NOT supply `:request-id`, `:on-success`, or `:on-failure`: the runtime owns reply addressing (it threads those from the scoped key and current generation, which is what makes stale-reply suppression airtight), and an args map that supplies them is rejected. Cross-cutting decoration — auth headers, tracing, a base URL, a default retry — belongs in a frame-registered `reg-http-interceptor` that decorates *every* managed request, not copied into each resource's `:request`.

## The scoped key: a leak boundary that fails closed

So far every resource has been `:rf.scope/global` — the same answer for everyone. Most real reads aren't. A user's feed, a tenant's dashboard, a profile under impersonation: these must *never* leak between viewers. **[Scope](glossary.md#scope)** is how re-frame2 makes that leak unrepresentable.

A cache entry's identity is a triple:

```clojure
[scope  resource-id  canonical-params]
```

Params say *which* article. Scope says *whose* cache. A genuinely public read declares `:scope :rf.scope/global` — an explicit, auditable claim. A viewer-relative read names a **scope resolver** once and references it everywhere:

```clojure
;; Adapted from examples/real-apps/realworld_resources/scope.cljs
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username
                [:rf.scope/session {:username username}]))})

;; Adapted from examples/real-apps/realworld_resources/resources.cljs
(rf/reg-resource :realworld/feed
  {:params-schema [:map [:page {:optional true} [:maybe :int]]]
   :scope         {:from-db :realworld/session}   ;; whose feed? resolved from app-db
   :tags          (fn [_params _data] #{[:feed]})}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get
               :url    "/api/articles/feed"
               :params {:limit 10 :offset (* 10 (dec (or page 1)))}}
     :decode  :json}))
```

The resolver is pure, and its declared `:inputs` are the app-db facts that decide the scope. That buys two structural properties:

- **Subscriptions re-key when the viewer changes.** At login, logout, or account switch, the same feed subscription re-points to the *new* viewer's cache entry. During the switch it reads the new key's state — typically `:idle` or `:loading` — never the previous viewer's data.
- **Nil resolution fails closed.** Logged out, the resolver yields `nil`. A route entry simply doesn't plan the feed, and a subscription raises a loud "scope unresolved" diagnostic. There is no path from "I forgot the viewer" to "I served someone else's cache."

There's one honest consequence of "subscriptions are passive": a re-keyed subscription does not fetch. The new viewer's data loads only when something *causes* it — usually the route re-entering after login. If your app switches viewer with *no* route change (a cold-boot session restore, say), dispatch an explicit `:rf.resource/ensure` under the new scope, carrying an app-minted owner lease with a matching release at logout so the entry stays alive. Without that lease, the entry just sits at `:idle`.

There is a third `:scope` policy for the resource that legitimately has *no* fixed answer of its own — **`:rf.scope/from-caller`**. Instead of declaring `:rf.scope/global` or naming a resolver, the resource *defers the scope to its use site*: every `ensure` / refetch / `:rf.resource/state` call MUST supply `:scope` on the payload (or a route resolver must supply it). This suits a reusable read that different callers legitimately scope differently — the resource owns no scope policy, so it demands one at every call. It keeps the same fail-closed floor: a `from-caller` resource reached with no payload `:scope` and no route resolver is a loud use-time error, `:rf.error/resource-scope-required-from-caller` — never a silent global read.

??? info "Coming from TanStack Query?"

    Difference two of three: **scope is a required, structural key segment.** In a query-key library the viewer id is one segment you assemble by hand at every call site — forget it once and one user silently reads another's cache. Here scope is a required declaration at registration, with no default. A read that can't resolve its scope raises a structured error; it **never** falls through to a silent shared read. The failure mode is loud, not leaky.

??? note "Going deeper — the shape of the whole boundary"

    A scope you *forgot* is a registration or read error; a scope you got *wrong* is a dev warning at the moment it bites; a scope you supplied with a *typo* (a `:rf.scope/*` keyword outside the closed enum) is rejected loudly wherever it appears. There is, by construction, no path from any of these to "served another user's cache." The full table of these signals is in [When it fails loud](#when-it-fails-loud--the-errors-and-warnings) below — but the property worth carrying now is that every footgun on this boundary has been moved from "silent leak" to "loud failure."

## Logout is one causal event

The classic cross-session leak — user B sees user A's dashboard — has a structural fix here. Every session-scoped entry lives under A's scope, and logout clears that scope. One subtlety is worth pausing on: resolve the *old* scope from the `db` the handler received (which still carries the logging-out user) *before* you remove the auth slice — otherwise you've thrown away the very identity you need to name the scope:

```clojure
;; Adapted from examples/real-apps/realworld_resources/auth.cljs
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx (cond-> []
             old-scope
             (conj [:dispatch [:rf.resource/clear-scope
                               {:scope old-scope :cause :logout}]]))})))
```

`clear-scope` removes every entry in that scope, releases its owners, and aborts or suppresses its in-flight requests. It leaves every other scope untouched, including still-valid global reads like article lists.

??? info "Coming from TanStack Query?"

    In the query-key world, logout is either `queryClient.clear()` (which drops the *global* cache too) or a hand-maintained list of keys to forget — both more brittle than clearing one scope. Here the leak boundary you declared at registration *is* the teardown boundary at logout.

## Owners and causes, and the refetch rules

Query libraries talk about *observers* — a mounted hook keeps a query alive. Resources split that one idea into two that never blur, because keeping a thing alive and explaining why it fetched are different concerns ([owner & cause](glossary.md#owner--cause) in the glossary):

- An **owner** is a liveness lease: "this route (or machine, or explicit app lease) needs this entry." Owners decide whether invalidation refetches now or just marks stale, and whether GC may reclaim the entry. Every owner has a defined releaser — the route on leave, a machine on destroy, an app-minted lease via a matching release event.
- A **cause** is an explanation: "why did this fetch happen?" — a route entry, a click, an invalidation, a focus return. Causes change nothing about liveness; they exist so the trace can answer *why*.

The race rules are few, and worth knowing cold:

- **Ensure of a fresh entry is a cache hit** — no fetch; the cached value serves immediately.
- **Ensure while the same key is in flight joins the existing request** — two screens asking for the same article fire one fetch.
- **An explicit refetch starts a new generation**, superseding any in-flight attempt.
- **Cancellation is opportunistic; stale-reply suppression is mandatory.** When an owner leaves or a newer generation starts, the runtime aborts the request if it can. Even when it can't, a generation check guarantees a late reply can never overwrite a newer entry. Abort saves bandwidth; suppression is what makes it *correct*.

The familiar refetch-on-window-focus behaviour is opt-in per frame — `(rf/install-revalidation-listeners! frame-id)` at boot (client-only). It refetches only entries that are both stale *and* still owned, so a tab return never triggers a fetch storm for data nothing is showing.

### Polling: keep this fresh every N ms

For a dashboard, a build-status badge, a notification count, or a "is this long job done yet" read, declare a **`:poll-interval-ms`** on the resource and the runtime re-reads it on that interval — no `setInterval`, no manual unmount / tab-hide bookkeeping:

```clojure
(rf/reg-resource :dashboard/build-status
  {:scope            :rf.scope/global
   :params-schema    [:map [:repo :string]]
   :poll-interval-ms 5000          ;; re-read every 5s while actively owned + the tab is visible
   :tags    (fn [_ _] #{[:build]})}
  (fn [{:keys [repo]} _ctx]
    {:request {:method :get :url (str "/repos/" repo "/build")} :decode :json}))
```

Polling is **owner-driven**: it runs only while the entry has an active owner (a route, a machine, or an app-minted `[:lease …]`) and pauses automatically while the tab is hidden — so it stops the instant nothing is looking at it. A poll tick refetches *unconditionally by the interval* (cause `:poll`, never an owner), coalesces with any in-flight work (a slow endpoint never stacks overlapping requests), and a failed poll keeps the prior data and keeps polling.

Three freshness tools, three questions:

- **Polling (`:poll-interval-ms`)** — "this changes on its own; keep it fresh on a clock" (build status, queue depth, presence).
- **Focus/reconnect revalidation** — "refresh stale data when the user comes back" (the default freshness most reads want; cheap, event-driven, no clock).
- **Invalidation (a mutation's `:invalidates`)** — "*this* write made *that* read wrong; refresh it now" (the causal, surgical refresh after a known change).

They compose: a polled entry still revalidates on focus and still gets invalidated by a write — the in-flight coalescing gate keeps the overlap to one fetch.

## Routes can declare more than one resource

The route example earlier ensured a single resource. A real page often needs several, with ordering and pagination concerns. Each `:resources` entry is a small map, and these are the keys it accepts:

```clojure
;; Adapted from examples/real-apps/realworld_resources/routing.cljs
(rf/reg-route :realworld/article
  {:params    [:map [:slug :string]]
   :resources [{:resource  :realworld/article
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}
               {:resource  :realworld/comments
                :params    (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? false}]}
  "/articles/:slug")
```

| Entry key | What it does |
|---|---|
| `:resource` | The registered resource id to ensure. |
| `:params` | A `(fn [route] params)` deriving params from the route match (or a literal map). |
| `:scope` | A `{:from-db <id>}` named-resolver reference, or a `(fn [route ctx] scope)` for a *route* fact (a tenant in a path segment). For viewer identity, always the named resolver. |
| `:blocking?` | `true` holds the route transition until the read settles (and gives SSR a wait point); `false` fetches in the background. |
| `:when` | A `(fn [route ctx] boolean)` predicate — plan this resource only when it returns true. Use this for conditional resources, never sentinel `nil` params. |
| `:keep-previous?` | Keep the prior key's data visible while the new one first-loads — see below. |
| `:id` / `:after` | A route-local id, and a `#{id …}` set ordering the *ensure-dispatch* of dependent entries. |

`:keep-previous? true` on a paginated list keeps the prior page visible while the next one loads, so paging never flashes a skeleton ([Paginate a feed](how-to/paginate-a-feed.md) is the recipe). The prior data arrives in the new entry's `:rf.resource/state` projection as `:previous?`, `:previous-key`, and `:previous-data` — a *read* from the old key, never merged into the new entry, so it can't pollute the new key's tags.

??? info "Coming from TanStack Query?"

    `:after` orders *dispatch*, not data. If you've used `enabled` to chain dependent queries, hold the shape but adjust the promise: `:after` guarantees the *ensure* of a dependent entry is dispatched after the one it names — so the dependency's fetch kicks off first — but the route plan is a pure synchronous planner that resolves *every* entry's params at route entry, before anything settles. A later entry's params therefore **cannot** depend on an earlier entry's loaded *data* (that's a data-waterfall, a deferred slice). `:after` must target a route-local `:id`; a dangling target or a cycle is a loud route-planning error, never a silent fall-back to declaration order.

## Writes invalidate by tag — causally

A read is half the story; eventually you favorite an article, post a comment, edit a profile. A **[mutation](glossary.md#mutation)** is a named causal write. On success it [invalidates](glossary.md#invalidate) the tags it broke, through the same scoped machinery, recorded on the event record — so keeping reads honest after a write is a *declaration*, not a call you remember to make:

```clojure
;; Adapted from examples/real-apps/realworld_resources/mutations.cljs
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url    (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

??? info "Coming from TanStack Query?"

    Difference three of three: **invalidation is causal.** In TanStack Query, keeping reads honest after a write is an imperative `queryClient.invalidateQueries(...)` you remember to make in `onSuccess`. Here it's a declared consequence of a named write, visible on the event record — there's nothing to remember and nothing to forget.

A mutation's success-phase plan has four declarative arms, and they run in a fixed order before the success-time invalidation:

- **`:populates`** — `(fn [params result] {target result})` *seeds* a cache entry from the reply, as if that key had loaded normally. A populated key becomes `:loaded`, arms its freshness timers, and is **exempt from this same mutation's invalidation pass** (so a write that populates an article *and* invalidates a broad article tag doesn't immediately refetch the key it just learned). The populated value must be the resource's stored shape — the same value a successful load produces — or you get a cache-coherence bug.
- **`:patches`** — `(fn [params result] {target (fn [old result] …)})` *transforms* an existing exact key. Patch only touches keys that already exist; it does not target tags.
- **`:removes`** — `(fn [params result] [target …])` *evicts* exact keys (a delete write).
- **`:invalidates`** — runs last, marking matching reads stale.

The `target` in `:populates` / `:patches` / `:removes` is a **map** — `{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global}` — the one canonical exact-target shape (no hand-built key tuples in app code).

Invalidation timing is explicit via **`:invalidate-timing`** — `:after-success` (the default), `:before-request`, `:after-failure`, or `:after-settle`. Entries with live owners refetch; unowned entries are marked stale and refetch when next ensured. Invalidation is **scoped by default**, so a write can't accidentally stale another tenant's cache.

!!! warning "Gotcha — match the scope or the invalidation silently misses"

    A mutation's `:invalidates` matches only entries *in the mutation's resolved scope*. If a `:rf.scope/global`-defaulted mutation invalidates a tag owned by a session-scoped read (or the reverse), no entry matches, nothing refreshes, and **no error is raised** — it's a legitimate "no match in this scope." When a write affects session- or tenant-scoped reads, name the matching scope on the execute payload, or — cleaner — use the **per-target descriptor form**, where each descriptor carries its own scope:

    ```clojure
    :invalidates
    (fn [{:keys [slug]} _result]
      [{:scope :rf.scope/global              ;; global facts
        :tags  #{[:article slug] [:article-list]}}
       {:scope {:from-db :realworld/session} ;; viewer-relative facts
        :tags  #{[:feed]}}])
    ```

    In dev, a mismatch trips a `:rf.warning/mutation-scope-mismatch` warning at the moment it misses, so the silent case becomes loud. The genuinely-broad "invalidate this tag wherever it lives" operation is the separate, *audited* `:cross-scope? true` escape — it must carry a `:cause` and shows up in Xray.

### Running a mutation and reading its state

`reg-mutation` only *registers* the write — the same register / cause / read split you saw for resources. You **run** it (the cause) with `:rf.mutation/execute` and **read** its progress through the passive `:rf.mutation/*` subs. Both the run and the read are keyed by an **instance** id rather than the mutation id — an instance is one particular submission of the write — so two concurrent submissions of the same mutation never clobber each other's pending/result state:

```clojure
;; In a form-submit event handler:
[:rf.mutation/execute
 {:mutation :realworld/favorite
  :params   {:slug slug}
  :instance [:favorite slug]            ;; caller-supplied (or generated) instance id
  :scope    {:from-db :realworld/session}
  :cause    [:click :article/favorite]
  :reply-to [:favorite/replied slug]}]  ;; optional continuation (below)
```

A view reads the instance's progress through `:rf.mutation/state` (or the narrower `:rf.mutation/pending?` / `:result` / `:error`); `resources/sub-mutation` is the named sugar for the whole-instance view-model:

```clojure
;; Adapted from examples/real-apps/realworld_resources/views.cljs
(rf/reg-view favorite-button [slug]
  (let [m @(resources/sub-mutation [:favorite slug])]
    [:button {:disabled (:pending? m)
              :on-click #(dispatch [:rf.mutation/execute
                                    {:mutation :realworld/favorite
                                     :params   {:slug slug}
                                     :instance [:favorite slug]
                                     :scope    {:from-db :realworld/session}
                                     :cause    [:click :article/favorite]}])}
     (cond
       (:pending? m) "Saving…"
       (:error? m)   "Retry"
       :else         "Favorite")]))
```

`:rf.mutation/state` projects `{:status :result :error :pending? :success? :error? :settled? :optimistic?}`. A failed write settles `:error` (there's no `:refresh-error` analogue — a write has no last-known-good to keep). `[:rf.mutation/clear {:instance …}]` is the causal reset: it clears the instance row and best-effort aborts in-flight work — the idiom for "dismiss this error and let the form retry cleanly."

??? note "Going deeper — `:reply-to` is a call-site event target, not a callback"

    A verified mutation reply is ordinary causal input, so a continuation can only drive durable state by dispatching an event — never by returning effects from a callback (that would escape the event tape, interceptors, and replay). When the reply is accepted, the runtime dispatches your `:reply-to` target with the canonical reply map *appended* as the final argument: `:reply-to [:favorite/replied slug]` dispatches `[:favorite/replied slug reply]`. The reply map carries `:status`, `:value` (on `:ok`), `:error` (on `:error`), `:mutation`, `:instance`, `:affected-keys`, and the carried frame stamp. The continuation fires **exactly once for an accepted terminal reply** and **never** for a stale/superseded one (a re-execute under the same instance, or a `:rf.mutation/clear`) — you inherit stale-suppression for free. By the time it runs, cache consequences and the instance row are already settled, so a `:reply-to` handler sees the world fully reconciled. Use it for workflow that *isn't* cache state — a toast, a redirect, focusing the next field.

The operational details — instance-keyed pending state, the full `:reply-to` reply-map fields, seeding the cache from the reply — live in [Part 4](tutorial/04-mutations-and-invalidation.md) and [Invalidate after a mutation](how-to/invalidate-after-a-mutation.md).

## Optimistic writes commit, roll back, or reconcile

The mutation above settles the cache only when the server replies. For a write that should *feel* instant — the favorite heart flips on click, the count ticks up — a mutation may declare an **[optimistic plan](glossary.md#optimistic-update--rollback)** that patches the cache *before* the request is sent, then commits, rolls back, or reconciles when the reply settles.

You declare the forward change; the runtime records the inverse for you, so a rollback restores exactly the entry that existed — never an author-written inverse that can drift from the forward patch:

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
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
                     {:scope {:from-db :realworld/session} :tags #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url    (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

Two forward forms exist: `:optimistic` patches **exact** cache targets (the twin of `:populates`/`:patches`), and `:optimistic-tags` patches **every** entry carrying a tag in its scope (the twin of tag-addressed `:invalidates`) — the cross-view-consistency case the author can't enumerate by key. Both are exact-key or tag-within-named-scope only, and **fail closed**: a `{:from-db …}` scope that resolves to `nil` drops that target rather than writing globally, so an optimistic write can never leak across viewers.

Settle is deterministic, decided on recorded facts (the generation acceptance verdict and a per-entry `:revision`), so there's no wall-clock race:

- an accepted **`:ok`** reply **commits** — the authoritative `:populates`/`:patches` overwrite the optimistic value with the server's, then `:invalidates` runs.
- an accepted **`:error`**/`:cancelled` reply **rolls back** — the recorded inverse is restored.
- a **stale/superseded** reply rolls back nothing; the current generation already owns the entry.

A view renders the in-flight optimistic state from the instance sub's derived **`:optimistic?`** flag.

??? info "Coming from TanStack Query?"

    This is the re-frame2 counterpart of `onMutate`/`onError` rollback (and SWR's `optimisticData` + `rollbackOnError`). The one deliberate divergence is a *contested* rollback — a concurrent write landed on the entry between your apply and the failure. `:on-conflict` governs it: the default **`:invalidate`** declines to restore a now-stale inverse and instead marks the entry stale so the read path refetches the authoritative value (re-frame2's deliberate departure from the query libraries' unconditional context-restore); `:force` restores the inverse anyway (the single-writer escape, with a tooling warning).

??? note "Going deeper"

    The settle decision runs entirely on recorded facts — the generation acceptance verdict and the per-entry `:revision` the conflict check compares against — so it never depends on the wall clock. A malformed optimistic descriptor (a bad target, a scope that doesn't resolve) is warned and skipped per descriptor rather than half-applying the plan.

## SSR and hydration

On the server, each request renders in its own [frame](../core/glossary.md#frame) — which matters, because a process-global cache would itself be a cross-user leak. Blocking route resources are the render's wait point. Every durable entry present at serialize time rides the projection, not just the blocking ones — a non-blocking resource that happened to settle before render serializes exactly like a blocking one, and one still in flight simply has no `:data` to ship yet (the client refetches it on hydration if the route still needs it). The settled entries are serialized (sensitive data redacted) and shipped with the page, and on the client, [hydration](../ssr/glossary.md#hydration) installs them under the same freshness rules. A hydrated entry that's still fresh is **not** refetched, so there's no duplicate-fetch flash on first paint; a stale one background-refreshes by policy. Hydration never crosses scopes — the serialized scope and the client's resolved scope must agree before hydrated data is usable. The mental model is in [Server-side rendering](../ssr/concepts.md), and `examples/capabilities/ssr/resources_ssr/` is the worked demo.

!!! warning "Gotcha — a blocking SSR resource needs a deadline, not an open wait"

    `:blocking? true` holds the render until the read settles, so a slow upstream can't be allowed to hang the request forever. A blocking resource that exceeds the render deadline settles as a structured first-load failure for that SSR frame — an `:error` envelope `{:kind :rf.http/timeout :reason :ssr-blocking-timeout}` (the `:kind` stays inside the closed `:rf.http/*` taxonomy; the `:reason` lets your [error projector](../core/glossary.md#registration) tell an SSR-deadline failure apart from a genuine upstream timeout). The renderer then chooses error markup, a skeleton, or an app fallback; the client picks the read up again on hydration.

## When it fails loud — the errors and warnings

Resources lean hard on the [fail-loud](../core/glossary.md#fail-loud-not-silent) ethos: the dangerous mistakes are *unrepresentable* or *raised*, not silent. Knowing the names means you can recognise them on sight — and, as an [error record](../core/glossary.md#error-record), branch on the `:rf.error/*` category, never on the prose `:reason`. These are the ones you'll actually meet:

| Signal | When | What it's telling you |
|---|---|---|
| `:rf.error/resource-missing-scope-policy` | at `reg-resource` | You didn't declare `:scope`. "I forgot this read is user-scoped" is unrepresentable — say `:rf.scope/global` (a claim) or name a resolver. |
| `:rf.error/invalid-resource-spec` | at `reg-resource` | A malformed spec — usually `:request` left *inside* the metadata map (it's the third slot), or a missing/bad `:params-schema`, or a malformed `:sensitive` / `:large` path-vector. |
| `:rf.error/resource-sub-unresolved-scope` | at a subscription | A `[:rf.resource/state …]` whose scope can't be resolved — pass `:scope` on the payload, the same scope the owning route/event ensured under. Never a silent `:idle`, never a silent global read. |
| `:rf.error/resource-scope-unresolved-reference` | at an ensure / route site | The ensure/route counterpart to the sub-side error above: a `{:from-db <id>}` scope reference that resolved to `nil` against the current db (e.g. logged out) at a scope-*requiring* event or route. A derived scope that can't resolve is fail-closed — never a fall-through to global. |
| `:rf.error/resource-invalidate-scope-required` | at `:rf.resource/invalidate-tags` | A bare invalidate with no scope. Name the scope, or opt into the audited `:cross-scope? true`. The fail-closed floor — never silently global. |
| `:rf.error/resource-route-plan` | at a route with `:resources` | A route resource couldn't be planned — its params or scope didn't resolve, its params failed the schema, or a `:when` guard threw. Recorded on the route slice's `:error` (visible to the `:rf/route` sub and Xray) so navigation surfaces the failure instead of silently dropping the read. |
| `:rf.error/resource-ssr-blocking-timeout` | at SSR render | A `:blocking? true` resource exceeded the render deadline. Each unsettled blocking entry settles as a first-load `:error` (`{:kind :rf.http/timeout :reason :ssr-blocking-timeout}`) so the render never hangs; the client picks the read up on hydration. |
| `:rf.error/invalid-mutation-spec` | at `reg-mutation` | The mutation twin of the resource spec error (e.g. `:request` in the metadata map). |

And the **dev-only warnings** (elided from production) that catch the *resolvable-but-wrong* footguns — the ones fail-closed can't catch, because a scope *did* resolve, just to the wrong place:

- **`:rf.warning/resource-sub-scope-mismatch`** — a subscription resolved a perfectly valid scope key that has *no owner*, while a *different* scope key for the same resource *is* active. Almost always: your view's `:scope` doesn't match the route/event that ensured the data. You'll see a permanent skeleton; the warning names the active scope you probably meant.
- **`:rf.warning/mutation-scope-mismatch`** — the write-side twin: a mutation's `:invalidates` matched zero entries in its scope while the same tags match an entry in *another* scope. Your write succeeded but the cached read it should have refreshed didn't, because the scopes don't line up.
- **`:rf.warning/resource-clear-scope-unresolved`** — a `:rf.resource/clear-scope` whose `{:from-db <id>}` scope reference resolved to `nil` against the current db (e.g. logging out with no logged-in user left). Fail-closed: it clears **nothing** rather than silently no-op'ing or wiping global, and names the resolver so you can fix the scope you meant to tear down.

## Advanced

### Infinite feeds: accumulate pages with `:infinite`

Everything so far has treated a paginated list as *independent* entries — page 2 is its own cache key, addressable as "go to page N", and `:keep-previous?` stops it flashing a skeleton on the way there. A **load-more / infinite-scroll feed** is the complementary shape: the user *accumulates* pages (page 1, then 1+2, then 1+2+3) into one growing list, and the *next* page's cursor is derived from the last page's data. You opt one resource into that shape with `:infinite true`:

```clojure
(rf/reg-resource :feed/timeline
  {:infinite         true
   :params-schema    [:map [:filter :keyword]]   ;; the FEED identity — not the page
   :scope            {:from-db :realworld/session}
   :next-page-param  (fn [last-page _all-pages]   ;; REQUIRED for :infinite
                       (get-in last-page [:page-info :next-cursor]))  ;; nil ⇒ end of feed
   :page->items      :items                       ;; REQUIRED when a page isn't already a vector
   :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})}
  ;; the per-page cursor rides the (otherwise-reserved) ctx — no new arity
  (fn [{:keys [filter]} {:rf.resource/keys [page-param]}]
    {:request {:method :get :url "/api/timeline"
               :params (cond-> {:filter filter :limit 20}
                         page-param (assoc :cursor page-param))}
     :decode  :app/timeline-page}))
```

The whole feed is **one** scoped entry — its `:data` is an ordered vector of pages, not N per-page cache keys — so it gets one owner, one GC clock, one SSR-restore unit, one Xray row. Only the *identity* params (filter / sort / search) name the feed; the per-page cursor is internal sequencing state and is **never** part of the cache key. Change a filter and you get a different feed instance; the old accumulation becomes a separate, GC-eligible entry.

Extending the feed is a **cause**, not a view fetch — dispatch `[:rf.resource/load-more {:resource :feed/timeline :scope … :params … :cause [:user :feed/load-more]}]`. A `load-more` is **ownerless** by rule: the route (or whatever first-loaded the feed) already owns the one entry, and a load-more only *extends* it, so it carries a `:cause` and never an `:owner` (a supplied owner is warn-and-ignored, `:rf.warning/resource-load-more-owner-ignored`). It reuses the work ledger and stale-suppression exactly as `ensure` does, and there's **no sixth FSM state** — a load-more on a `:loaded` feed transitions to `:fetching` (the accumulated pages stay visible, no skeleton) and back to `:loaded` with the new page appended.

A view reads the feed through a small infinite-specific projection family — all passive, all derived, all framework-owned and memoised:

```clojure
@(subscribe [:rf.resource/infinite-state {:resource :feed/timeline :scope … :params …}])
;; => {:status :loaded
;;     :items          [<item> <item> …]   ;; the merged list — the everyday read
;;     :pages          [<page-0> <page-1> …]
;;     :page-count     3
;;     :has-next-page? true                ;; (some? next-page-param)
;;     :fetching-next? false               ;; a load-more in flight (distinct from :fetching?)
;;     :has-data?      true
;;     :error          nil                 ;; page-0 first-load failure
;;     :refresh-error  nil
;;     :page-error     nil}                ;; last load-more failure
```

`:rf.resource/items` is the headline read — the flat list every feed view wants (the thing a TanStack user reaches for `.flatMap(p => p.items)` to get). The narrower `:rf.resource/pages`, `:has-next-page?`, `:fetching-next?`, `:page-count`, and `:page-error` exist so a view subscribes to just the fact it cares about. A worked feed view renders `:items`, shows a spinner while `:fetching-next?`, a "Load more" button (dispatching `:rf.resource/load-more`) while `:has-next-page?`, and an end-of-feed marker otherwise.

??? info "Coming from TanStack Query?"

    This is `useInfiniteQuery`. `:next-page-param` is `getNextPageParam(lastPage, allPages)` — with two deliberate alignments: re-frame2 standardises the terminal on **`nil`** (not `undefined` / an empty page) and additionally hands you `:has-next-page?` so a view never re-derives "are we at the end". The merge that TanStack leaves to your render (`pages.flatMap(...)`) is the framework-owned, memoised `:rf.resource/items` here.

!!! warning "Gotcha — the third error channel: `:page-error`"

    A *load-more* page-fetch failure is **not** a feed first-load failure and **not** a whole-feed refresh failure. The feed stays `:loaded`, **keeps every accumulated page**, and records the failure in `:page-error` — so the view shows "couldn't load more — retry" without losing the list the user already scrolled. It's cleared by the next successful load-more or whole-feed load. (First-load failure is still `:error`; a failed background refresh of the whole feed is still `:refresh-error`.)

!!! warning "Gotcha — `:page->items` is required, not guessed"

    If a page is already a vector it flattens by identity. If a page is *enveloped* (`{:items [...] :page-info {…}}`), you **must** declare a `:page->items` accessor — a keyword key or `(fn [page] …)`; the framework will not guess `:items` / `:data`. A non-vector page with no accessor is a loud registration error (`:rf.error/infinite-missing-page-accessor`), the same fail-loud floor as a missing `:next-page-param` (`:rf.error/infinite-missing-next-page-param`).

A few load-bearing edges: an `:rf.resource/ensure` (or a blocking route entry) on an infinite resource fetches **page 0 only** — it doesn't re-fetch the accumulation. A `:refetch` of a feed defaults to **window-preserving** (the visible pages stay rendered until their replacement succeeds, so a focus/reconnect refetch never collapses the feed back to page 0); `:refetch-all-pages?` and `:refetch-window` are the opt-ins. And a mutation that touches one item *inside* a feed invalidates the **whole feed** in this slice (coarse but correct) rather than patching one element in place. Two edges are named but deferred or niche: a backward `:rf.resource/load-prev` is reserved for a later slice, and a per-page `:page-data-schema` carries the egress classification for page data. [Paginate a feed](how-to/paginate-a-feed.md) is the worked recipe for both pagination shapes.

## When resources are the wrong tool

Resources earn their keep when cached server reads multiply. When they don't, reach for something simpler — pick the cheapest of [the four homes](../core/glossary.md#the-four-homes-where-state-lives) that fits:

- **A handful of reads, no caching story.** A [managed HTTP request](../async/http.md) plus a small app-db slice is less machinery and entirely idiomatic.
- **Login and other commands.** Auth is a [state machine](../machines/glossary.md#machine) driving a write — don't contort it into a cached read.
- **GraphQL.** The transport is HTTP-only for now; GraphQL is a planned later phase.

!!! note "Don't avoid resources just because a write needs to feel instant"

    A mutation *can* flip the UI immediately and reconcile when the server replies — see [Optimistic writes commit, roll back, or reconcile](#optimistic-writes-commit-roll-back-or-reconcile) above. That's a property of the mutation, not a reason to keep server state out of the cache.
