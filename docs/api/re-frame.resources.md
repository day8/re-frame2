# re-frame.resources

A resource is a named, cached read of remote state — re-frame2's answer to TanStack Query / RTK Query / SWR / `re-frame-query`, re-expressed in the model: **views read server state passively through subscriptions; route entry, events, and machines *cause* it to fetch; the cache lives in the framework-owned runtime partition, not `app-db`.** You register a resource once with a scope policy, a params schema, and a request; after that the runtime owns identity, cache scope, staleness, dedupe, invalidation, GC, in-flight ownership, SSR hydration, and the metadata Xray reads.

Resources are an **optional, post-v1 capability** — they ship in `day8/re-frame2-resources` (`re-frame.resources`), require `day8/re-frame2-http` for the transport, and are wired by one `(:require [re-frame.resources])` at app boot. An app that omits the artefact sees the `reg-resource` wrapper throw a clean `:rf.error/resources-artefact-missing`. This doc covers the registration shape, the events, the subs, and introspection. The tutorial is [Guide ch.27 — Server-state and resources](../resources/concepts.md).

> **Scope is HTTP-only.** The first public-beta surface is **landed and complete**: the read-resource MVP, **mutations** (`reg-mutation` / `:rf.mutation/execute`, the causal-write counterpart — see [Mutation events](#mutation-events-map-payloads)), **focus/reconnect active-stale revalidation** (`:rf.resource/window-focused` / `:rf.resource/network-reconnected` + the `install-revalidation-listeners!` / `remove-revalidation-listeners!` host-listener fns — see [Revalidation listeners](#revalidation-listeners)), and **active-owner polling** (`:poll-interval-ms` — see [Polling](#polling)). **GraphQL is deferred** to a later slice. See [Guide ch.27 §What's deferred](../resources/concepts.md).

The resource surfaces are on the `re-frame.core` facade (`reg-resource` / `reg-mutation`); the events and subscriptions are keyword-addressed:

```clojure
(:require [re-frame.core :as rf])
```

## Registration

### `reg-resource`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (reg-resource resource-id metadata request-fn)
  ```
- **Description**: Register a resource as data. The `metadata` is the MIDDLE registration-metadata map (the **required, fail-closed `:scope` policy**, `:params-schema`, `:doc`, …); the `request-fn` (which returns the [managed-HTTP args map](re-frame.http.md)) is the THIRD value slot. A non-map `metadata` is rejected loudly with `:rf.error/invalid-resource-spec`. Validates the reconstructed spec — the **required, fail-closed `:scope` policy** first, then `:params-schema` — and writes a `:resource`-kind registrar entry. Returns `resource-id`. (The stored introspection spec from `resource-meta` reconstructs `:request` onto the metadata map.)

#### The resource spec

```clojure
(rf/reg-resource :article/by-slug
  {:doc "Article detail by slug."

   :params-schema [:map [:slug :string]]      ;; REQUIRED — validates + canonicalizes params
   :data-schema   :app/article                ;; validates successful data when decode supports it

   :scope          :rf.scope/global            ;; REQUIRED — an explicit, auditable claim
   :transport      :rf.http/managed            ;; the only initial-scope transport
   :stale-after-ms 60000
   :gc-after-ms    300000
   :poll-interval-ms 5000                      ;; (optional) revalidate every 5s while actively owned + visible
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})
   :sensitive?     false}

  ;; REQUIRED request fn (third positional arg) — returns a managed-HTTP args map
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :app/article}))
```

**Required keys**:

| Key | Notes |
|---|---|
| `:params-schema` | Validates and canonicalizes params (the resource's identity). |
| `:scope` | The scope **policy** — `:rf.scope/global`, a resolver, or `:rf.scope/from-caller`. **Required, fail-closed** — no policy is a loud `:rf.error/resource-missing-scope-policy`. There is no implicit default; a user-scoped read must say so. |
| request fn (3rd positional arg) | For `:transport :rf.http/managed`, returns a [managed-HTTP args map](re-frame.http.md). MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime supplies those from the scoped key + generation (rejected if present). |

These three (`:params-schema`, `:scope`, request fn) are the registration gate — a `reg-resource` missing any of them throws. `:data-schema` is **not** part of the gate; it is optional — it validates successful data when present, but the registration gate does not enforce it.

**Optional keys**: `:doc`, `:data-schema` (validates successful data when present / transport decode supports it — not enforced by the registration gate), `:transport` (initial scope: `:rf.http/managed`), `:stale-after-ms`, `:gc-after-ms`, `:poll-interval-ms` (the active-owner poll interval — see [Polling](#polling)), `:infinite` + the infinite-only keys (`:next-page-param`, `:prev-page-param`, `:page->items`, `:initial-page-param`, `:page-data-schema`, `:refetch` — see [Infinite resources](#infinite-resources)), `:tags`, `:sensitive?` / `:large?` / schema-based classification.

**Rejected / unused in v1**: `:revalidate`, `:placeholder`, `:cache-key`, `:select`, transport extension protocols. (Interval polling is `:poll-interval-ms`; the `:infinite` load-more kind — see [Infinite resources](#infinite-resources).) The mutation-only keys (`:invalidates`, `:patches`, `:populates`, `:optimistic`, `:optimistic-tags`, `:on-conflict`) are **not resource-registration keys** — they live on `reg-mutation`.

#### Scope policy

`:scope` is required and declares a policy from a closed set:

| Policy | Meaning |
|---|---|
| `:rf.scope/global` | The resource is **explicitly** global — a claim that the same params produce the same data for every user/tenant/permission/locale/impersonation state. Auditable, not a convenience default. |
| `<resolver>` | Derive the scope. A route-resource resolver is `(fn [route ctx] …)`; a sub-resolvable resolver is a pure data value or a fn-of-nothing. A reusable named resolver is registered with [`reg-resource-scope`](#reg-resource-scope) and referenced as `{:from-db <scope-id>}`. |
| `:rf.scope/from-caller` | Scope required from the use site — every ensure / refetch / state call must supply `:scope` (or a route resolver must). |

There is no `[:rf.scope/global]` fallthrough. Event resolution precedence: payload `:scope` → route resolver → spec resolver. Subscription resolution: payload `:scope` → sub-resolvable spec policy → loud `:rf.error/resource-sub-unresolved-scope` (never a silent global read or `:idle`). See [Guide ch.27 §Scope](../resources/concepts.md).

### `clear-resource`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-resource resource-id)
  ```
- **Description**: Remove a registered resource. A **registration-lifecycle** operation — NOT the normal cache-invalidation API (for data lifecycle use `:rf.resource/invalidate-tags` / `:rf.resource/remove` / `:rf.resource/clear-scope`). Also disposes the resource-runtime state for the id in each affected frame (release owner indexes, cancel timers/host handles, abort in-flight where possible, suppress late replies by generation, remove tag-index rows, emit a trace). Returns `resource-id`.

```clojure
;; deregister a resource (registration-lifecycle — e.g. on hot-reload / teardown)
(rf/clear-resource :feed/timeline)
```

A **mutation** is the causal-WRITE counterpart of a resource: a named write to remote state that, on success, invalidates / patches / populates cached resource reads. The write lowers through the **same** `:rf.http/managed` transport as resources, and the runtime owns reply addressing + stale-suppression (work-id + generation) exactly as for reads. Runtime state is keyed by mutation **instance** id, so concurrent submissions of the same mutation never clobber each other. The tutorial is [Guide ch.27 §Mutations](../resources/concepts.md).

### `reg-mutation`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (reg-mutation mutation-id metadata request-fn)
  ```
- **Description**: Register a mutation as data under `mutation-id`. The `metadata` is the MIDDLE registration-metadata map (`:params-schema`, `:invalidates`, `:patches`, `:doc`, …); the `request-fn` (the [managed-HTTP write](re-frame.http.md)) is the THIRD value slot. A non-map `metadata` is rejected loudly with `:rf.error/invalid-mutation-spec`. Validates the reconstructed spec and writes a `:mutation`-kind registrar entry (the causal-write counterpart of `:resource`). Returns `mutation-id`. An app that omits the resources artefact sees the wrapper throw `:rf.error/resources-artefact-missing`. (The stored introspection spec from `mutation-meta` reconstructs `:request` onto the metadata map.)

```clojure
(rf/reg-mutation :article/save
  {:params-schema :app/article          ;; REQUIRED — validates + canonicalizes params
   :invalidates  (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
   ;; :patches / :populates key a cached entry by its resource descriptor and
   ;; apply on success BEFORE invalidation — patch transforms the entry's data,
   ;; populate seeds it straight from the reply.
   :patches      (fn [{:keys [slug]} result]
                   {{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global}
                    (fn [old] (merge old result))})
   :populates    (fn [{:keys [slug]} result]
                   {{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global} result})
   :scope        :rf.scope/global       ;; the cache scope invalidation/patch defaults to
   :invalidate-timing :after-success}   ;; | :before-request | :after-failure | :after-settle

  ;; REQUIRED request fn (third positional arg) — a managed-HTTP write
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug) :body article}
     :decode  :app/article}))
```

**Required keys**:

| Key | Notes |
|---|---|
| `:params-schema` | Validates and canonicalizes the write's params. |
| request fn (3rd positional arg) | Returns a [managed-HTTP args map](re-frame.http.md) (the write). MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime supplies those from the instance + generation (rejected if present). **Write retries are OPT-IN** and live in this returned args map's own `:retry` (see the note below) — never a `reg-mutation` spec key. |

**Optional keys**: `:invalidates` (`(fn [params result] → #{tag …})` — the tags made stale on success, composed with `:rf.resource/invalidate-tags`, scoped), `:patches` / `:populates` (controlled resource-entry transforms / seeds applied on success **before** invalidation, keyed by scoped key, via the same durable entry shape + structural sharing the read path uses), `:scope` (the cache scope the invalidation / patch / populate targets), `:invalidate-timing` (`:after-success` (default) | `:before-request` | `:after-failure` | `:after-settle`), `:transport`, `:doc`.

> **`:retry` is NOT a `reg-mutation` spec key.** Write retries are opt-in and ride the [managed-HTTP args](re-frame.http.md) your `:request` fn returns — put `:retry {…}` in *that* map. The runtime passes the `:request` args through to the transport unchanged and does **not** read or enforce a spec-level `:retry`; there is no `reg-mutation`-level retry to arm. (Reads inherit the same discipline — see [Guide ch.10 §Retry](../async/http.md): re-issuing a non-idempotent write because a reply was merely slow is the double-write bug, so retry stays explicit and per-request.)

> **Mutation `:scope` is not fail-closed.** Unlike a resource read — whose `:scope` is **required** and fails closed — a mutation's `:scope` is optional and resolves payload `:scope` → spec `:scope` → `:rf.scope/global`. The scope decides which cache scope the success-time invalidate / patch / populate targets, so it **MUST match the scope of the resources the write changes**: a write against user/tenant/locale-scoped entries that omits `:scope` invalidates the `[:rf.scope/global]` cache instead and silently misses the scoped entries (stale reads, no error). Pass `:scope` on `[:rf.mutation/execute …]` when the principal is known only at the call site; the `:rf.scope/from-caller` *policy* is a resource-read concept, not a mutation one.

**Optimistic keys** (see [EP-0019](../EP/EP-0019-optimistic-mutation-rollback.md) and [Guide ch.27 §Optimistic writes](../resources/concepts.md#optimistic-writes-commit-roll-back-or-reconcile)): `:optimistic` is a registration-level forward plan (the twin of `:patches`) applied **before** the server confirms; `:optimistic-tags` is its tag-addressed twin for cross-view consistency; `:on-conflict` (`:invalidate` default | `:force`) decides the contested-rollback policy. The **inverse is runtime-recorded** — the author supplies no `:rollback` registration key; the runtime snapshots each touched entry (with its `:revision`) on the instance row's `:patch-summary` `:rollback` slot and settles via the commit/rollback/reconcile protocol.

### `clear-mutation`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-mutation mutation-id)
  ```
- **Description**: Remove a registered mutation — a **registration-lifecycle** operation, NOT a form-error reset. For the causal runtime-instance reset use the `[:rf.mutation/clear …]` event. Returns `mutation-id`.

```clojure
;; deregister a mutation (registration-lifecycle — NOT the runtime-instance reset)
(rf/clear-mutation :article/save)
```

## Named scope resolvers

A resource-scope resolver is the **third resources kind** (alongside resources and mutations) — a registrar entry under the `:resource-scope` kind that derives a **cache scope** from declared `app-db` inputs. It is the one scope-resolution currency the same named resolver carries to resource registration, route resources, event-side `ensure`, subscriptions, invalidation descriptors, populate/patch/remove targets, and `clear-scope`. A resource (or payload, or route) references a named resolver as `{:from-db <scope-id>}`; the reference is resolved at **use time** against the frame's `app-db`. A `nil` resolution is **fail-closed** at every scope-requiring site — never an implicit global read.

### `reg-resource-scope`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (reg-resource-scope scope-id resolver)
  ```
- **Description**: Register a PURE named scope resolver under `scope-id`. `resolver` is either the canonical declared-inputs map `{:inputs {name [:db <rf-path>]} :resolve (fn [inputs ctx] -> scope|nil)}` — where the `:resolve` first arg is ALWAYS the resolved inputs map (the one stable Name-over-place meaning) — or the single documented explicit alternative, the whole-db fn sugar `(fn [db ctx] -> scope|nil)` (whose first arg is the whole db, the one deliberate exception). The only shipped input source is `[:db <rf-path>]` (a concrete `:rf/path`); `[:runtime …]` (route-derived scope) is **reserved** and rejected loudly. The `:resolve` fn MUST be pure (it MUST NOT fetch, dispatch, mutate state, or read ambient host state); the `ctx` arg is reserved and invoked as literal `nil` in this slice. A `nil` resolve result is **fail-closed**. Writes a `:resource-scope`-kind registrar entry carrying the canonical spec plus captured source coords; returns `scope-id`. Implementation ships in `day8/re-frame2-resources`; require `re-frame.resources` at boot — an app that omits the artefact sees the wrapper throw `:rf.error/resources-artefact-missing`.

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})

;; referenced from a resource / payload / route as {:from-db :realworld/session}
```

### `clear-resource-scope`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-resource-scope scope-id)
  ```
- **Description**: Remove a registered resource-scope resolver — a **registration-lifecycle** removal (the `clear-` decrement counterpart of `reg-resource-scope`). A resolver holds no per-frame runtime state of its own (it is a pure derivation consulted at use time), so there is nothing to dispose beyond the registrar entry. A no-op (returns `scope-id`) when the id is not registered.

```clojure
;; deregister a named scope resolver (registration-lifecycle — e.g. on hot-reload / teardown)
(rf/clear-resource-scope :realworld/session)
```

### `resolve-resource-scope`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resolve-resource-scope db scope-id) → scope or nil
  ```
- **Description**: Resolver **helper**: resolve the named resolver `scope-id` against the supplied `db` value, returning a canonical concrete scope or nil. A **pure** function over the resolver registry — NOT an effect: no app-state / dispatch side effects, and no observability side effect (it does NOT emit `:rf.resource/scope-resolved` trace; the causal `{:from-db …}` / route-entry / mutation-settle boundaries carry that evidence instead). The canonical use is the **logout / account-switch idiom**: resolve the concrete old scope from the handler's coeffect `db` (pre-transition by definition — the causal input) and pass it concretely to `[:rf.resource/clear-scope …]`. Throws `:rf.error/resource-scope-not-registered` when no resolver is registered under `scope-id` (the loud fail-closed boundary — a typo'd reference is never a silent nil). A resolver that returns nil for the supplied db yields nil (the fail-closed unresolved condition; the use site interprets it — never an implicit global).

```clojure
;; the logout idiom: resolve the concrete OLD scope off the coeffect db,
;; then clear that scope's whole cache so the next principal can never read it
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx [[:dispatch [:rf.resource/clear-scope {:scope old :cause :logout}]]]})))
```

## Revalidation listeners

Active-stale revalidation is expressed as **resource events** (see [`[:rf.resource/window-focused]` / `[:rf.resource/network-reconnected]`](#rfresourcewindow-focused--rfresourcenetwork-reconnected)), never subscription-driven fetching. On window focus / tab-return / network reconnect, the frame's active-owner **stale** entries are rescanned and refetched by policy — a stale entry with no active owner is left alone (revalidation creates no liveness). The host-listener install/remove fns below wire (and tear down) the `window` listeners that dispatch those events.

### `install-revalidation-listeners!` / `remove-revalidation-listeners!`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (install-revalidation-listeners! frame-id)   ;; → nil
  (remove-revalidation-listeners!  frame-id)   ;; → nil
  ```
- **Description**: `install-revalidation-listeners!` wires three host `window` listeners for `frame-id` — `focus` and `visibilitychange`-to-visible → `[:rf.resource/window-focused]`, and `online` → `[:rf.resource/network-reconnected]` — each dispatched at `frame-id`. Idempotent (re-installing replaces, never stacks — hot-reload safe); CLJS-only (the JVM/SSR arm is a no-op). Listeners are recorded in a host side table and cancelled on frame destroy via the single `:resources/on-frame-destroyed!` hook. `remove-revalidation-listeners!` tears them down (useful for test isolation and single-page hosts that rotate which frame owns revalidation); a no-op when none is installed.

```clojure
;; at boot, after the frame is live: wire focus / visibility / online revalidation
(rf/install-revalidation-listeners! :rf/default)

;; tear them down (test isolation, or when another frame takes over revalidation)
(rf/remove-revalidation-listeners! :rf/default)
```

## Polling

A resource may declare an optional **`:poll-interval-ms`** policy: while an entry has at least one **active owner** and the document is **visible**, the runtime re-runs its load every N ms — without any component-side fetch call (re-frame2's counterpart of TanStack Query's `refetchInterval` / SWR's `refreshInterval` / RTK Query's `pollingInterval`). Polling is **owner-driven** (it tracks the active-owner lease, not a component observer), so a route / machine / app-minted lease keeps it alive and the instant the last owner releases polling stops.

```clojure
(rf/reg-resource :notifications/unread-count
  {:scope            {:from-db :app/session}
   :params-schema    [:map]
   :poll-interval-ms 15000           ;; refresh every 15s while someone owns it + the tab is visible
   :tags    (fn [_ _] #{[:notifications]})}
  (fn [_ _ctx] {:request {:method :get :url "/notifications/unread"} :decode :json}))
```

Semantics (per [Guide ch.27 §Polling](../resources/concepts.md#polling-keep-this-fresh-every-n-ms)):

- **Positive integer ms; non-positive / absent = no polling.** The poll timer is the third member of the freshness-timer family (beside `:stale-after-ms` / `:gc-after-ms`).
- **Unconditional active-owner tick.** A tick refetches **by the interval**, not gated on `:stale?` — the consumer asked for "re-read every N ms". `:stale-after-ms` stays the orthogonal focus/route-entry knob. (Structural sharing keeps views quiet when an unchanged response comes back.)
- **Cause `:poll`, never an owner.** A poll refetch creates no liveness, extends no GC; generation + stale-reply suppression apply exactly as for any refetch.
- **Default-pause-when-hidden.** Poll ticks are suppressed while the tab is hidden and resume on tab return (which also fires the focus scan). A `:poll-when-hidden?` opt-in for a true background monitor is reserved.
- **Coalescing.** A tick that finds a live in-flight refetch skips (no overlap on a slow endpoint); focus + poll never double-fetch.
- **Background-failure resilient.** A failed poll keeps prior `:data` + records `:refresh-error`, and the next poll still fires.

A "just polling" view with no natural route/machine owner needs an app-minted `[:lease …]` owner (with a matching `[:rf.resource/release-owner {…}]`) to keep the poll alive — an owner-free entry never polls.

## Infinite resources

An **infinite resource** is the load-more / infinite-scroll feed counterpart of TanStack Query's `useInfiniteQuery` / SWR's `useSWRInfinite` (see [EP-0021](../EP/EP-0021-infinite-resources.md); tutorial in [Guide ch.27 §Infinite feeds](../resources/concepts.md#infinite-feeds-accumulate-pages-with-infinite)). It is a resource registered with `:infinite true` plus a pure `:next-page-param`; the user accumulates pages (1, then 1+2, then 1+2+3) rendered as one growing list, with the *next* page param derived from the last page's data. Numbered / cursor pagination (`:keep-previous?` + per-page entries) is untouched and orthogonal — an app picks per feed.

```clojure
(rf/reg-resource :feed/timeline
  {:doc "Infinite home timeline (load-more)."
   :infinite         true
   :params-schema    [:map [:filter :keyword]]   ;; FEED identity (filter/sort) — NOT the page cursor
   :scope            {:from-db :app/session}
   :page-data-schema :app/timeline-page          ;; validates ONE page (decode target + per-page egress)
   :next-page-param  (fn [last-page _all-pages]    ;; REQUIRED; nil = the single terminal
                       (get-in last-page [:page-info :next-cursor]))
   :page->items      :items                        ;; REQUIRED when a page is non-vector/enveloped (loud over guessing)
   :tags             (fn [{:keys [filter]} _] #{[:feed filter]})}

  ;; request fn (third positional arg) — reserved ctx carries the page context (R8 — no new arity)
  (fn [{:keys [filter]} {:rf.resource/keys [page-param]}]
    {:request {:method :get :url "/api/timeline"
               :params (cond-> {:filter filter :limit 20} page-param (assoc :cursor page-param))}
     :decode  :app/timeline-page}))
```

Key semantics:

- **`:infinite true` makes `:next-page-param` required** (a loud `:rf.error/infinite-missing-next-page-param` otherwise). It is pure `(last-page all-pages) → next-param-or-nil`; returning `nil` is the single canonical terminal, exposed as the derived `:has-next-page?`.
- **One scoped entry per feed.** Pages accumulate as an ordered vector inside the one `:rf.runtime/resources` entry — **not** N per-page entries, **not** an app-db slice — so the feed has one owner set / freshness clock / GC clock / SSR-restore unit / Xray row. The per-page param is internal sequencing state, **never** part of the cache key; changing the identity params yields a different feed instance.
- **`:page-data-schema` validates one page** and is the per-page egress/classification contract (applied per page on SSR/tool projection); `:data-schema` is not used for the accumulated vector.
- **Other infinite-only keys**: `:prev-page-param` (the bidirectional derivation mirror — defined, but the `load-prev` prepend event is deferred; v1 ships next-direction load-more only), `:initial-page-param` (the first-page param, default `nil`), `:page->items` (required for non-vector pages), and `:refetch` (`{:refetch-all-pages? false}` by default — the conservative window-preserving refetch policy, with `:refetch-all-pages?` / `:refetch-window` opt-ins).

A view reads the merged list and dispatches the causal `[:rf.resource/load-more {…}]`:

```clojure
[:rf.resource/items          {:resource :feed/timeline :scope … :params …}]   ;; merged flat list — the headline read
[:rf.resource/pages          {…}]   ;; raw page boundaries
[:rf.resource/has-next-page? {…}]   [:rf.resource/fetching-next? {…}]
[:rf.resource/page-count     {…}]   [:rf.resource/page-error     {…}]
[:rf.resource/infinite-state {…}]   ;; combined view-model (the feed analogue of :rf.resource/state)
```

`:rf.resource/items`, `:rf.resource/pages`, and `:rf.resource/infinite-state` are framework-owned memoised subscriptions; `:rf.resource/ensure` (and a route entry) loads **page 0 only**, and a mutation touching an item inside a feed **invalidates the whole feed** (coarse, correct; in-place item patching is on the deferred optimistic/patch axis).

## Introspection and projection (tool/test lane)

These direct functions are the **tool/test projection lane** — not an app-read API. `resource-meta` / `mutation-meta` project the **registration** (the registered spec); `resource-state` / `resources` / `mutation-state` / `mutations` project **runtime state** (the live entries) as a one-shot, non-reactive snapshot at an explicit frame. They serve Xray, unit tests, and SSR serialization — contexts with no reactive subscription. **App views read runtime state through the passive [`:rf.resource/*` / `:rf.mutation/*` subscriptions](#resource-subscriptions-passive)**, never through these functions, which do not re-render on change. Registering a handler, dispatching a cause, projecting a snapshot, and subscribing are four distinct jobs; see [Guide ch.27 §Three lanes](../resources/concepts.md#three-lanes--registering-causing-projecting).

`:frame` is an explicit, app-registered frame id ([EP-0002](../EP/EP-0002-frame-target-resolution.md) — no ambient `:rf/default` fallback; a frameless call with no resolvable context fails closed).

### `resource-meta`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resource-meta resource-id) → spec-map or nil
  ```
- **Description**: The registered resource's spec (`:params-schema`, `:data-schema`, `:request`, `:scope`, `:transport`, `:stale-after-ms`, `:gc-after-ms`, `:poll-interval-ms`, `:tags`, `:doc`, source coords).

```clojure
;; tool/test lane: read a registered resource's spec back
(rf/resource-meta :article/by-slug)
;; => {:scope :rf.scope/global :params-schema [...] :request #fn ... :doc "…"}
```

### `resource-state`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resource-state {:resource … :scope … :params … :frame …}) → entry or nil
  ```
- **Description**: A resource instance's durable runtime entry for an explicit-frame target, resolving the scoped key the same way a subscription does. nil when no entry exists.

```clojure
;; the live durable entry for one scoped key, at an explicit frame
(rf/resource-state {:resource :article/by-slug
                    :scope    :rf.scope/global
                    :params   {:slug "welcome"}
                    :frame    :rf/default})
;; => entry map, or nil when no entry exists
```

### `resources`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resources)            → {:resource-ids [...] :entries {}}
  (resources {:frame …}) → {:resource-ids [...] :entries {<key-id> <entry>}}
  ```
- **Description**: Resource introspection for a frame target — the static registry (every registered id) plus, with `:frame`, the live per-frame resource-instance entries. The `:entries` map is keyed on the CEDN-1 byte `key-id` string (the same key the runtime storage / SSR wire use; it cannot collapse CEDN-distinct sequential-params entries the way an `=`-keyed scoped-key vector would). Each entry carries its kind-preserving scoped-resource-key `[scope resource-id params]` under `:resource/key` for destructure / scope-and-resource filtering.

```clojure
(rf/resources)                      ;; => {:resource-ids [...] :entries {}}  (static registry only)
(rf/resources {:frame :rf/default}) ;; => {:resource-ids [...] :entries {<key-id> <entry>}}
```

### `mutation-meta`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (mutation-meta mutation-id) → spec-map or nil
  ```
- **Description**: The registered mutation's spec map (`:request`, `:params-schema`, `:invalidates`, `:patches`, `:populates`, `:scope`, `:invalidate-timing`, `:transport`, `:doc`, source coords), or nil.

```clojure
(rf/mutation-meta :article/save)
;; => {:request #fn ... :params-schema :app/article :invalidates #fn ... :scope :rf.scope/global …}
```

### `mutation-state`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (mutation-state {:instance … :frame …}) → row or nil
  ```
- **Description**: A mutation **instance**'s durable runtime row (`{:status :result :error …}`) for an explicit-frame target, or nil. The frame is carried explicitly (see [EP-0002](../EP/EP-0002-frame-target-resolution.md)).

```clojure
;; one mutation INSTANCE's runtime row, at an explicit frame
(rf/mutation-state {:instance :form/save-1 :frame :rf/default})
;; => {:status … :result … :error …}, or nil
```

### `mutations`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (mutations)            → {:mutation-ids [...] :instances {}}
  (mutations {:frame …}) → {:mutation-ids [...] :instances {<instance-id> <row>}}
  ```
- **Description**: Mutation introspection for a frame target — the registered mutation ids plus, with `:frame`, the live per-frame mutation-instance table (Xray groups instances under their registered mutation id).

```clojure
(rf/mutations {:frame :rf/default})
;; => {:mutation-ids [...] :instances {<instance-id> <row>}}
```

Xray exposes the same shapes plus the tool accessors (`list-resources`, `list-resource-instances`, `get-resource-state`, `get-resource-history`, `list-resource-invalidations`), the route/resource graph, the work-ledger table, and the **scope audit surface** (the standing enumeration of every `:rf.scope/global` resource). Tool accessors prefer summaries over raw values; params/scopes get the same privacy/size elision as data.

## Keyword surfaces

The resource and mutation runtime is **caused** and **read** entirely through keyword-addressed events and subscriptions — never by calling a function. Resources are caused to fetch by events and read passively by subscriptions; the same split applies to mutations.

### Resource events (map payloads)

Resource events take a **map payload**, not a positional argument vector.

#### `[:rf.resource/ensure {…}]`

- **Kind**: event
- **Payload**: `{:resource :scope :params :owner :cause}`
- **Description**: Ensure the resource instance is loaded. `ensure` while the same scoped key is already in flight **joins** the existing work (attaches the owner, records the cause, emits a dedupe trace). `:owner` changes the active-owner set; `:cause` is recorded in trace/history.

```clojure
[:rf.resource/ensure
 {:resource :article/by-slug
  :scope    [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
  :params   {:slug "welcome"}
  :owner    [:route :route/article nav-token]
  :cause    [:route-entry :route/article nav-token]}]
```

#### `[:rf.resource/refetch {…}]`

- **Kind**: event
- **Payload**: `{:resource :scope :params :owner :cause}`
- **Description**: Force a refresh. May force a new generation — a still-in-flight prior request is marked superseded, aborted when possible, otherwise suppressed by work-id + generation. A manual refresh is usually a `:cause`, not an `:owner`.

```clojure
;; a "Refresh" button — a :cause, pointedly no :owner (the route keeps it alive)
[:rf.resource/refetch
 {:resource :articles/list
  :params   {}
  :cause    [:manual :resources.app/refresh-articles]}]
```

#### `[:rf.resource/invalidate-tags {…}]`

- **Kind**: event
- **Payload**: `{:scope :tags :cause}`
- **Description**: Mark entries whose tags intersect `:tags` stale; refetch entries with active owners; leave inactive entries stale or GC-eligible. **Scoped by default** — a cross-scope invalidation must opt in explicitly and is visible in Xray. On a successful load an entry's tags are *replaced* with the new data's tags.

```clojure
;; after a write settles, stale the reads carrying these tags
[:rf.resource/invalidate-tags
 {:scope :rf.scope/global
  :tags  #{[:article "welcome"]}
  :cause [:follow-author-detail-sync "welcome"]}]
```

#### `[:rf.resource/release-owner {…}]`

- **Kind**: event
- **Payload**: `{:owner …}`
- **Description**: Release an owner lease. Aborts in-flight work only when no remaining owner needs it. App-minted leases (`[:lease …]`) MUST have a matching release path — an orphaned lease pins an entry alive (Xray lints it).

```clojure
;; the matching release for an app-minted [:lease …] owner
[:rf.resource/release-owner
 {:owner [:lease :preview "welcome"]}]
```

#### `[:rf.resource/clear-scope {…}]`

- **Kind**: event
- **Payload**: `{:scope :cause}`
- **Description**: Causal scope teardown. Removes/marks-unusable every entry in the scope, releases owners, aborts in-flight requests with no owner outside the scope, suppresses late replies by scope + generation, emits explanatory trace rows. Required on logout / account / tenant / permission / locale / impersonation change.

```clojure
;; logout / tenant-switch — drop a whole scope's cache so the next principal
;; can never read the last one's data
[:rf.resource/clear-scope
 {:scope [:rf.scope/session {:user-id "u-42"}]
  :cause :logout}]
```

#### `[:rf.resource/remove {…}]`

- **Kind**: event
- **Payload**: `{:resource :scope :params}`
- **Description**: Remove a single resource instance's cache entry.

```clojure
;; drop one cached instance, keyed by its scoped resource key
[:rf.resource/remove
 {:resource :article/by-slug
  :scope    :rf.scope/global
  :params   {:slug "welcome"}}]
```

#### `[:rf.resource/load-more {…}]`

- **Kind**: event (infinite resources only — see [Infinite resources](#infinite-resources))
- **Payload**: `{:resource :scope :params :cause}` — **ownerless** (carries a `:cause`, never an `:owner`)
- **Description**: Extend an `:infinite` feed by one page. Computes the next page param from the entry's tail via `:next-page-param`, fetches that page through the same managed transport, and appends it to the feed's accumulated page vector (the feed stays `:loaded`; the pages stay visible — a load-more in flight is the derived `:fetching-next?` sub, distinct from a whole-feed `:fetching?` refresh). A load-more with no next page (`:next-page-param` → `nil`) is a no-op trace; a load-more while a page fetch is already in flight dedupes. A supplied `:owner` is warn-and-ignored — the route (or whatever first-loaded the feed) already owns the one feed entry, and load-more never changes the active-owner set.

```clojure
;; the "Load more" button — ownerless; carries a :cause, never an :owner
[:rf.resource/load-more
 {:resource :feed/timeline
  :scope    :rf.scope/global
  :params   {}
  :cause    [:user :feed/load-more]}]
```

#### `[:rf.resource/window-focused]` / `[:rf.resource/network-reconnected]`

- **Kind**: event (no payload)
- **Description**: Scan the frame's active-owner stale entries and refetch them by policy. The refetch carries cause `:focus` (window-focused) or `:reconnect` (network-reconnected) — **never an owner** (it creates no liveness); generation + stale-suppression protect any late reply. Dispatch these directly only when driving revalidation yourself; ordinarily the host listeners ([Revalidation listeners](#revalidation-listeners)) dispatch them.

```clojure
;; no payload — dispatch directly only when driving revalidation yourself
;; (ordinarily the host listeners dispatch these for you — see Revalidation listeners)
[:rf.resource/window-focused]
[:rf.resource/network-reconnected]
```

> **Internal replies — do not dispatch.** `:rf.resource.internal/succeeded` / `…/failed` / `…/aborted` / `…/stale-fired` / `…/gc-fired` / `…/poll-fired` / `…/stale-suppressed` are framework-internal and carry the verification payload (`:work/id`, `:resource/key`, `:scope`, `:generation`, `:rf.frame/id`). User code MUST NOT dispatch them; success/failure verify frame + work id + generation before writing (the mandatory stale-suppression boundary). (`:rf.resource.internal/stale-fired` is the stale-timer re-check tick — it arms the stale transition, not a fetch; `:rf.resource.internal/poll-fired` is the poll-timer re-check tick — it refetches an active-owner entry by the interval.)

### Resource subscriptions (passive)

A subscription is a **pure passive read** — it never fetches. It resolves scope per the sub-side precedence and raises `:rf.error/resource-sub-unresolved-scope` rather than reading global or returning a silent `:idle`.

```clojure
[:rf.resource/state         {:resource … :scope … :params …}]   ;; the full view-model
[:rf.resource/data          {…}]   [:rf.resource/status        {…}]
[:rf.resource/loading?      {…}]   [:rf.resource/fetching?     {…}]
[:rf.resource/stale?        {…}]   [:rf.resource/error         {…}]
[:rf.resource/refresh-error {…}]   [:rf.resource/has-data?     {…}]
[:rf.resource/previous-data {…}]
```

The `:rf.resource/state` view-model — facts plus **derived** booleans:

```clojure
{:status        :idle | :loading | :fetching | :loaded | :error
 :data          <last-known-good-or-nil>
 :error         <first-load-error-or-nil>          ;; :rf.http/* envelope
 :refresh-error <background-refresh-error-or-nil>  ;; :rf.http/* envelope
 :loading?      <bool>   ;; first load, no usable data
 :fetching?     <bool>   ;; refresh in flight, prior data visible
 :stale?        <bool>   ;; freshness — orthogonal to load status
 :has-data?     <bool>}
```

Status invariants: `:loading` = first load, no usable data; `:fetching` = refresh in flight while prior data stays visible; `:error` = first load failed, no usable data; a failed *background* refresh stays `:loaded`, keeps prior `:data`, records `:refresh-error`. `:stale?` / `:loading?` / `:fetching?` / `:has-data?` are derived sub values, never stored. See [Guide ch.27 §Status](../resources/concepts.md).

> **Lifecycle trace ops (observability, not events you dispatch).** The runtime emits `:rf.resource/cache-hit` when a fresh `ensure` is served from cache with **no fetch** (a fresh-skip: an already-`:loaded` entry still fresh-by-policy serves the cached value, attaches the owner lease, and — for a blocking route resource — drains the blocking slot immediately, with no `:fetching` transition), and `:rf.resource/stale-fired` when a stale timer ticks (arming the stale transition, not a fetch). These are trace surfaces Xray reads — they are not `:status` values and not events user code dispatches.

### Mutation events (map payloads)

#### `[:rf.mutation/execute {…}]`

- **Kind**: event
- **Payload**: `{:mutation :params :instance :scope :cause}`
- **Description**: Run a mutation. `:instance` is the caller-supplied (or generated) **instance** id all runtime state is keyed by — two concurrent submissions keep distinct rows. On success the runtime patches/populates resource entries then invalidates tags (per `:invalidate-timing`). A superseded reply (a re-execute under the same instance, or an `:rf.mutation/clear`) never overwrites a newer instance (work-id + generation suppression).

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   article
  :instance :form/save-1
  :scope    [:rf.scope/session {:user-id "u-42"}]
  :cause    [:form-submit :article/save]}]
```

#### `[:rf.mutation/clear {…}]`

- **Kind**: event
- **Payload**: `{:instance …}`
- **Description**: The **causal** reset of a runtime mutation instance — clears its row and best-effort aborts in-flight work. Distinct from `clear-mutation` (which removes the *registration*).

```clojure
;; reset one runtime instance's row (e.g. in a completion continuation)
[:rf.mutation/clear {:instance :form/save-1}]
```

### Mutation subscriptions (passive)

A `:rf.mutation/*` subscription is a pure passive read keyed by **instance** id — it never executes a write.

```clojure
[:rf.mutation/state    {:instance :form/save-1}]   ;; {:status :result :error :pending? :success? :error? :settled?}
[:rf.mutation/status   {:instance :form/save-1}]
[:rf.mutation/pending? {:instance :form/save-1}]
[:rf.mutation/result   {:instance :form/save-1}]
[:rf.mutation/error    {:instance :form/save-1}]
```

A failure settles `:error` — there is **no `:refresh-error` analogue** (a write has no last-known-good to keep).

> **Internal replies — do not dispatch.** The `:rf.mutation.internal/*` replies (and the `:rf.mutation/*` trace family — `started` / `succeeded` / `failed` / `cleared` / `stale-suppressed`, carrying the instance id) are framework-internal; user code MUST NOT dispatch them.

## Cache home

Resource cache lives **only** at `:rf.runtime/resources` inside the runtime-db partition (`:rf.db/runtime`); the frame work ledger at `:rf.runtime/work-ledger`. Both are reserved runtime-db keys, framework-owned, per-frame isolated, allocated lazily. App code reads through the subs and accessors and never hand-edits the slice. Cache *entries* (durable facts) and work-ledger *attempts* (in-flight records) are separate; host handles (AbortControllers, timers, promises) live in side tables and are never serialized. The correctness rule: **cancellation is opportunistic; stale-reply suppression (by work-id + generation) is mandatory.** See [Guide ch.27 §Cache home and the work ledger](../resources/concepts.md).

## Examples and cross-references

- [Guide ch.27 — Server-state and resources](../resources/concepts.md) — the tutorial.
- [Glossary](../resources/glossary.md) — the resources and server-state vocabulary in one place.
- [EP-0003 — Resource Queries](../EP/EP-0003-resource-queries.md) — rationale and prior-art benchmark.
- [Migration: re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md) — moving off `shipclojure/re-frame-query` or a hand-rolled Pattern-RemoteData cache.
- [re-frame.http](re-frame.http.md) — the `:rf.http/managed` transport and the `:rf.http/*` failure taxonomy.
- [re-frame.routing](re-frame.routing.md) — `:resources` route metadata.
- [re-frame.ssr](re-frame.ssr.md) — the hydration install path.
