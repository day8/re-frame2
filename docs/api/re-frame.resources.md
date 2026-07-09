# re-frame.resources

A resource is a named, cached read of remote state. Views read server state passively through subscriptions. Route entry, events, and machines *cause* it to fetch. The cache lives in the framework-owned runtime partition, not `app-db`. You register a resource once with a scope policy, a params schema, and a request. The runtime then owns the rest:

- identity and cache scope
- staleness, dedupe, and invalidation
- GC and in-flight ownership
- SSR hydration and the metadata Xray reads

Resources are an optional, post-v1 capability. They ship in `day8/re-frame2-resources` (`re-frame.resources`) and require `day8/re-frame2-http` for the transport. One `(:require [re-frame.resources])` at app boot wires them in. An app that omits the artefact sees the `reg-resource` wrapper throw `:rf.error/resources-artefact-missing`. This page covers the registration shape, the events, the subs, and introspection. The tutorial is [Guide ch.27 — Server-state and resources](../resources/concepts.md).

Scope in this slice is HTTP-only:

- Read-resource MVP.
- **Mutations** (`reg-mutation` / `:rf.mutation/execute`, the causal-write counterpart) — see [Mutation events](#mutation-events-map-payloads).
- Focus/reconnect active-stale revalidation (`:rf.resource/window-focused` / `:rf.resource/network-reconnected`, plus the `install-revalidation-listeners!` / `remove-revalidation-listeners!` host-listener fns) — see [Revalidation listeners](#revalidation-listeners).
- Active-owner polling (`:poll-interval-ms`) — see [Polling](#polling).
- GraphQL is deferred to a later slice — see [Guide ch.27 §What's deferred](../resources/concepts.md).

`reg-resource` / `reg-mutation` are on the `re-frame.core` facade; the events and subscriptions are keyword-addressed:

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
- **Description**: Register a resource as data. Returns `resource-id`.
  - `metadata` (middle slot): the registration-metadata map. It carries the fail-closed `:scope` policy, `:params-schema`, `:doc`, and so on. A non-map `metadata` raises `:rf.error/resource-bad-spec`.
  - `request-fn` (third slot): returns the [managed-HTTP args map](re-frame.http.md).
  - Validates the reconstructed spec (`:scope` first, then `:params-schema`), then writes a `:resource`-kind registrar entry.
  - The introspection spec stored for `resource-meta` reconstructs `:request` onto the metadata map.

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
| `:scope` | The scope **policy**: `:rf.scope/global`, a resolver, or `:rf.scope/from-caller`. Fail-closed: omitting the policy raises a loud `:rf.error/resource-missing-scope-policy`. There is no implicit default; a user-scoped read must say so. |
| request fn (3rd positional arg) | For `:transport :rf.http/managed`, returns a [managed-HTTP args map](re-frame.http.md). It MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime supplies those from the scoped key + generation. Supplying one raises `:rf.error/resource-reserved-request-key`. |

These three (`:params-schema`, `:scope`, request fn) are the registration gate. A `reg-resource` missing any of them throws. `:data-schema` is not part of the gate: it validates successful data when present, but the gate does not enforce it.

**Optional keys**:

- `:doc`
- `:data-schema` — validates successful data when present and the transport decode supports it. Not enforced by the gate.
- `:transport` — initial scope: `:rf.http/managed`.
- `:stale-after-ms`, `:gc-after-ms`
- `:poll-interval-ms` — the active-owner poll interval. See [Polling](#polling).
- `:infinite`, plus the infinite-only keys `:next-page-param`, `:prev-page-param`, `:page->items`, `:initial-page-param`, `:page-data-schema`, and `:refetch`. See [Infinite resources](#infinite-resources).
- `:tags`
- `:sensitive?` / `:large?` / schema-based classification

**Not in the v1 registration surface**: `:revalidate`, `:placeholder`, `:cache-key`, `:select`, and transport extension protocols. The gate neither reads nor validates them. (Looking for interval polling? That is `:poll-interval-ms`. The load-more kind is `:infinite` — see [Infinite resources](#infinite-resources).) The mutation-only keys (`:invalidates`, `:patches`, `:populates`, `:removes`, `:optimistic`, `:optimistic-tags`, `:on-conflict`) are not resource-registration keys. They live on `reg-mutation`.

#### Scope policy

`:scope` is required and declares a policy from a closed set:

| Policy | Meaning |
|---|---|
| `:rf.scope/global` | The resource is explicitly global: the same params produce the same data for every user/tenant/permission/locale/impersonation state. This is an auditable claim, not a convenience default. |
| `<resolver>` | Derive the scope. A route-resource resolver is `(fn [route ctx] …)`; a sub-resolvable resolver is a pure data value or a fn-of-nothing. A reusable named resolver is registered with [`reg-resource-scope`](#reg-resource-scope) and referenced as `{:from-db <scope-id>}`. |
| `:rf.scope/from-caller` | The use site must supply the scope: every ensure / refetch / state call passes `:scope` (or a route resolver does). Reaching the resource without one raises `:rf.error/resource-scope-required-from-caller`. |

There is no `[:rf.scope/global]` fallthrough.

- Event resolution precedence: payload `:scope` → route resolver → spec resolver. A `{:from-db <id>}` reference that resolves `nil` at an event/route site raises `:rf.error/resource-scope-unresolved-reference`.
- Subscription resolution: payload `:scope` → sub-resolvable spec policy → loud `:rf.error/resource-sub-unresolved-scope` (never a silent global read or `:idle`).

See [Guide ch.27 §Scope](../resources/concepts.md).

### `clear-resource`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-resource resource-id)
  ```
- **Description**: Remove a registered resource. Returns `resource-id`.
  - A **registration-lifecycle** operation, NOT cache invalidation. For data lifecycle use `:rf.resource/invalidate-tags` / `:rf.resource/remove` / `:rf.resource/clear-scope`.
  - Also disposes the resource-runtime state for the id in each affected frame. That disposal:
    - releases owner indexes
    - cancels timers and host handles
    - aborts in-flight work where possible
    - suppresses late replies by generation
    - removes tag-index rows
    - emits a trace

```clojure
;; deregister a resource (registration-lifecycle — e.g. on hot-reload / teardown)
(rf/clear-resource :feed/timeline)
```

A mutation is the causal-WRITE counterpart of a resource: a named write to remote state. On success it invalidates, patches, or populates cached resource reads. The write lowers through the same `:rf.http/managed` transport as resources. The runtime owns reply addressing and stale-suppression (work-id + generation) exactly as for reads. Runtime state is keyed by mutation **instance** id, so concurrent submissions of the same mutation never clobber each other. See [Guide ch.27 §Mutations](../resources/concepts.md).

### `reg-mutation`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (reg-mutation mutation-id metadata request-fn)
  ```
- **Description**: Register a mutation as data under `mutation-id`. Returns `mutation-id`. The causal-write counterpart of `:resource`.
  - `metadata` (middle slot): the registration-metadata map. It carries `:params-schema`, `:invalidates`, `:patches`, `:doc`, and so on. A non-map `metadata` raises `:rf.error/mutation-bad-spec`.
  - `request-fn` (third slot): the [managed-HTTP write](re-frame.http.md).
  - Validates the reconstructed spec and writes a `:mutation`-kind registrar entry.
  - An app that omits the resources artefact sees the wrapper throw `:rf.error/resources-artefact-missing`.
  - The introspection spec stored for `mutation-meta` reconstructs `:request` onto the metadata map.

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
| request fn (3rd positional arg) | Returns a [managed-HTTP args map](re-frame.http.md) (the write). It MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime supplies those from the instance + generation. Supplying one raises `:rf.error/resource-reserved-request-key`. Write retries are **opt-in** and live in the returned args map's own `:retry` (see the note below), never in a `reg-mutation` spec key. |

**Optional keys**:

- `:invalidates` — `(fn [params result] → #{tag …})`, the tags made stale on success. The runtime composes them with `:rf.resource/invalidate-tags`, scoped.
- `:patches` / `:populates` — controlled resource-entry transforms / seeds applied on success, **before** invalidation. They are keyed by scoped key and use the same durable entry shape and structural sharing as the read path.
- `:removes` — `(fn [params result] → [target …])`, controlled resource-entry removals applied on success. Targets use the same map-form exact shape `{:resource :params :scope}`. A removed key's in-flight attempt is best-effort aborted.
- `:scope` — the cache scope the invalidation / patch / populate targets.
- `:invalidate-timing` — `:after-success` (default) | `:before-request` | `:after-failure` | `:after-settle`. A value outside the closed enum is rejected with `:rf.error/mutation-bad-spec`.
- `:transport`, `:doc`.

> **`:retry` is NOT a `reg-mutation` spec key.** Write retries are opt-in. They ride the [managed-HTTP args](re-frame.http.md) your `:request` fn returns — put `:retry {…}` in *that* map. The runtime passes the `:request` args through to the transport unchanged. It does not read or enforce a spec-level `:retry`, so there is no `reg-mutation`-level retry to arm. Reads inherit the same discipline — see [Managed HTTP reference §Retry](../async/http.md#retry-transport-retry-as-data). Re-issuing a non-idempotent write because a reply was merely slow is the double-write bug, so retry stays explicit and per-request.

> **Mutation `:scope` is not fail-closed.** A resource read's `:scope` is required and fails closed. A mutation's `:scope` is optional: it resolves payload `:scope` → spec `:scope` → `:rf.scope/global`. The scope decides which cache scope the success-time invalidate / patch / populate targets, so it MUST match the scope of the resources the write changes. A write against user/tenant/locale-scoped entries that omits `:scope` invalidates the `[:rf.scope/global]` cache instead. It silently misses the scoped entries — stale reads, no error. Pass `:scope` on `[:rf.mutation/execute …]` when the principal is known only at the call site. The `:rf.scope/from-caller` *policy* is a resource-read concept, not a mutation one.

**Optimistic keys** (see [EP-0019](../EP/EP-0019-optimistic-mutation-rollback.md) and [Guide ch.27 §Optimistic writes](../resources/concepts.md#optimistic-writes-commit-roll-back-or-reconcile)):

- `:optimistic` — a registration-level forward plan applied **before** the server confirms. It is the twin of `:patches`, with signature `(fn [params] → {target patch-fn})`. There is no `result` arg; the reply does not exist yet.
- `:optimistic-tags` — its tag-addressed twin for cross-view consistency.
- `:on-conflict` — `:invalidate` (default) | `:force`, the contested-rollback policy.

The inverse is runtime-recorded: the author supplies no `:rollback` registration key. The runtime snapshots each touched entry (with its `:revision`) on the instance row's `:patch-summary` `:rollback` slot and settles via the commit/rollback/reconcile protocol. An optimistic plan combined with `:invalidate-timing :before-request` is contradictory and rejected at registration with `:rf.error/mutation-optimistic-before-request`.

### `clear-mutation`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-mutation mutation-id)
  ```
- **Description**: Remove a registered mutation. Returns `mutation-id`. A **registration-lifecycle** operation, NOT a form-error reset. For the causal runtime-instance reset use the `[:rf.mutation/clear …]` event.

```clojure
;; deregister a mutation (registration-lifecycle — NOT the runtime-instance reset)
(rf/clear-mutation :article/save)
```

## Named scope resolvers

A resource-scope resolver is the third resources kind, alongside resources and mutations. It is a registrar entry under the `:resource-scope` kind that derives a cache scope from declared `app-db` inputs. It is also the one scope-resolution currency: the same named resolver works at every site that needs a scope —

- resource registration
- route resources
- event-side `ensure`
- subscriptions
- invalidation descriptors
- populate/patch/remove targets
- `clear-scope`

A resource (or payload, or route) references a named resolver as `{:from-db <scope-id>}`. The reference is resolved at **use time** against the frame's `app-db`. A `nil` resolution is **fail-closed** at every scope-requiring site — never an implicit global read.

### `reg-resource-scope`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (reg-resource-scope scope-id metadata resolve-fn)
  (reg-resource-scope scope-id resolve-fn)           ;; whole-db sugar (no metadata)
  ```
- **Description**: Register a **pure** named scope resolver under `scope-id` in the canonical 3-slot grammar. Returns `scope-id`. Ships in `day8/re-frame2-resources`; require `re-frame.resources` at boot. An app that omits the artefact sees the wrapper throw `:rf.error/resources-artefact-missing`.
  - `resolve-fn` (value/third slot): the resolver. Its first arg is ALWAYS the resolved inputs map. It MUST be pure — it MUST NOT fetch, dispatch, mutate state, or read ambient host state. The `ctx` arg is reserved and invoked as literal `nil` in this slice. A `nil` resolve result is fail-closed.
  - `metadata` (middle slot): carries the declared `:inputs {name [:db <rf-path>]}` plus optional `:doc`. The only shipped input source is `[:db <rf-path>]` (a concrete `:rf/path`). `[:runtime …]` (route-derived scope) is reserved and rejected with `:rf.error/resource-scope-source-reserved`.
  - Whole-db form: omit `:inputs` — either the 2-arg sugar, or a `:doc`-only metadata — and the resolver reads the whole db as its first arg. This is the one deliberate, documented exception.
  - A non-map metadata, a malformed `:inputs` descriptor, a `:resolve` left inside the metadata map, or a non-fn value slot is rejected with `:rf.error/invalid-resource-scope-spec`.
  - Writes a `:resource-scope`-kind registrar entry carrying the canonical spec plus captured source coords.

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/session {:username username}])))

;; referenced from a resource / payload / route as {:from-db :realworld/session}
```

### `clear-resource-scope`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-resource-scope scope-id)
  ```
- **Description**: Remove a registered resource-scope resolver. This is a **registration-lifecycle** removal — the `clear-` counterpart of `reg-resource-scope`. A resolver holds no per-frame runtime state (it is a pure derivation consulted at use time), so nothing is disposed beyond the registrar entry. When the id is not registered, the call is a no-op that returns `scope-id`.

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
- **Description**: Resolver **helper**: resolve the named resolver `scope-id` against the supplied `db` value, returning a canonical concrete scope or nil.
  - A pure function over the resolver registry, NOT an effect. It has no app-state or dispatch side effects, and no observability side effect: it does NOT emit a `:rf.resource/scope-resolved` trace. The causal `{:from-db …}` / route-entry / mutation-settle boundaries carry that evidence.
  - Throws `:rf.error/resource-scope-not-registered` when no resolver is registered under `scope-id` (a typo'd reference is never a silent nil).
  - A resolver that returns nil for the supplied db yields nil. That is the fail-closed unresolved condition; the use site interprets it — never as an implicit global.
  - Canonical use is the logout / account-switch idiom: resolve the concrete old scope from the handler's coeffect `db` — pre-transition by definition, the causal input — and pass it concretely to `[:rf.resource/clear-scope …]`.

```clojure
;; the logout idiom: resolve the concrete OLD scope off the coeffect db,
;; then clear that scope's whole cache so the next principal can never read it
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old (rf/resolve-resource-scope db :realworld/session)]
      {:db (dissoc db :auth)
       :fx [[:dispatch [:rf.resource/clear-scope {:scope old :cause :logout}]]]})))
```

### `scope-resolver-meta`

- **Kind**: function (post-v1 lib; `re-frame.resources` façade only — not on `re-frame.core`)
- **Signature**:
  ```clojure
  (re-frame.resources/scope-resolver-meta scope-id) → spec-map or nil
  ```
- **Description**: The registered resolver's canonical spec map (`:inputs`, `:resolve`, `:whole-db?`, `:doc`) for `scope-id`, or nil if none is registered. The introspection counterpart of `resource-meta` / `mutation-meta`. The whole-db sugar stores `:whole-db? true` with a synthetic `:inputs {:db [:db []]}`.

```clojure
(re-frame.resources/scope-resolver-meta :realworld/session)
;; => {:inputs {:username [:db [:auth :user :username]]} :resolve #fn :whole-db? false :doc nil}
```

### `scope-resolver-ids`

- **Kind**: function (post-v1 lib; `re-frame.resources` façade only — not on `re-frame.core`)
- **Signature**:
  ```clojure
  (re-frame.resources/scope-resolver-ids) → [scope-id …]
  ```
- **Description**: A vector of every registered resource-scope resolver id (the static resolver registry).

```clojure
(re-frame.resources/scope-resolver-ids)
;; => [:realworld/session]
```

## Revalidation listeners

Active-stale revalidation is expressed as **resource events** (see [`[:rf.resource/window-focused]` / `[:rf.resource/network-reconnected]`](#rfresourcewindow-focused--rfresourcenetwork-reconnected)) — never subscription-driven fetching. On window focus, tab-return, or network reconnect, the runtime rescans the frame's active-owner **stale** entries and refetches them by policy. A stale entry with no active owner is left alone: revalidation creates no liveness. The host-listener install/remove fns below wire and tear down the `window` listeners that dispatch those events.

### `install-revalidation-listeners!` / `remove-revalidation-listeners!`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (install-revalidation-listeners! frame-id)   ;; → nil
  (remove-revalidation-listeners!  frame-id)   ;; → nil
  ```
- **Description**: `install-revalidation-listeners!` wires three host listeners for `frame-id`, each dispatched at `frame-id`:
  - `focus` (on `window`) and `visibilitychange`-to-visible (on `document`, its only valid event target) → `[:rf.resource/window-focused]`.
  - `online` (on `window`) → `[:rf.resource/network-reconnected]`.

  Installation is idempotent: re-installing replaces, never stacks, so it is hot-reload safe. It is CLJS-only; the JVM/SSR arm is a no-op. Listeners are recorded in a host side table and cancelled on frame destroy via the single `:resources/on-frame-destroyed!` hook. `remove-revalidation-listeners!` tears them down — useful for test isolation, and for single-page hosts that rotate which frame owns revalidation. It is a no-op when none is installed.

```clojure
;; at boot, after the frame is live: wire focus / visibility / online revalidation
(rf/install-revalidation-listeners! :rf/default)

;; tear them down (test isolation, or when another frame takes over revalidation)
(rf/remove-revalidation-listeners! :rf/default)
```

## Polling

A resource may declare an optional `:poll-interval-ms` policy. While an entry has at least one **active owner** and the document is **visible**, the runtime re-runs its load every N ms — no component-side fetch call needed. Polling is owner-driven: it tracks the active-owner lease, not a component observer. A route, machine, or app-minted lease keeps it alive, and polling stops the instant the last owner releases.

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
- **Unconditional active-owner tick.** A tick refetches by the interval, not gated on `:stale?`. `:stale-after-ms` stays the orthogonal focus/route-entry knob. (Structural sharing keeps views quiet when an unchanged response comes back.)
- **Cause `:poll`, never an owner.** A poll refetch creates no liveness, extends no GC; generation + stale-reply suppression apply exactly as for any refetch.
- **Default-pause-when-hidden.** Poll ticks are suppressed while the tab is hidden and resume on tab return (which also fires the focus scan). A `:poll-when-hidden?` opt-in for a true background monitor is reserved.
- **Coalescing.** A tick that finds a live in-flight refetch skips (no overlap on a slow endpoint); focus + poll never double-fetch.
- **Background-failure resilient.** A failed poll keeps prior `:data` + records `:refresh-error`, and the next poll still fires.

A "just polling" view with no natural route/machine owner needs an app-minted `[:lease …]` owner to keep the poll alive, with a matching `[:rf.resource/release-owner {…}]`. An owner-free entry never polls.

## Infinite resources

An infinite resource is the load-more / infinite-scroll feed kind (see [EP-0021](../EP/EP-0021-infinite-resources.md); the tutorial is [Guide ch.27 §Infinite feeds](../resources/concepts.md#infinite-feeds-accumulate-pages-with-infinite)). It is a resource registered with `:infinite true` plus a pure `:next-page-param`. The user accumulates pages — 1, then 1+2, then 1+2+3 — rendered as one growing list. The *next* page param is derived from the last page's data. Numbered / cursor pagination (`:keep-previous?` + per-page entries) is untouched and orthogonal; an app picks per feed.

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

- **`:infinite true` makes `:next-page-param` required.** Omitting it raises a loud `:rf.error/infinite-missing-next-page-param`. The fn is pure: `(last-page all-pages) → next-param-or-nil`. Returning `nil` is the single canonical terminal, exposed as the derived `:has-next-page?`.
- **One scoped entry per feed.** Pages accumulate as an ordered vector inside the one `:rf.runtime/resources` entry — not N per-page entries, and not an app-db slice. The feed therefore has one owner set, one freshness clock, one GC clock, one SSR-restore unit, and one Xray row. The per-page param is internal sequencing state, never part of the cache key. Changing the identity params yields a different feed instance.
- **`:page-data-schema` validates one page** and is the per-page egress/classification contract (applied per page on SSR/tool projection); `:data-schema` is not used for the accumulated vector.
- **Other infinite-only keys**:
    - `:prev-page-param` — the bidirectional derivation mirror. Defined, but the `load-prev` prepend event is deferred; v1 ships next-direction load-more only.
    - `:initial-page-param` — the first-page param. Default `nil`.
    - `:page->items` — required for non-vector pages.
    - `:refetch` — `{:refetch-all-pages? false}` by default, the conservative window-preserving refetch policy. `:refetch-all-pages?` / `:refetch-window` are the opt-ins.

A view reads the merged list and dispatches the causal `[:rf.resource/load-more {…}]`:

```clojure
[:rf.resource/items          {:resource :feed/timeline :scope … :params …}]   ;; merged flat list — the headline read
[:rf.resource/pages          {…}]   ;; raw page boundaries
[:rf.resource/has-next-page? {…}]   [:rf.resource/fetching-next? {…}]
[:rf.resource/has-prev-page? {…}]   ;; the bidirectional mirror (observable; the prepend event is deferred)
[:rf.resource/page-count     {…}]   [:rf.resource/page-error     {…}]
[:rf.resource/infinite-state {…}]   ;; combined view-model (the feed analogue of :rf/resource)
```

`:rf.resource/items`, `:rf.resource/pages`, and `:rf.resource/infinite-state` are framework-owned memoised subscriptions. `:rf.resource/ensure` (and a route entry) loads **page 0 only**. A mutation touching an item inside a feed **invalidates the whole feed** — coarse, but correct. In-place item patching inside a feed's page vector is a distinct deferred axis (not the single-entry optimistic surface), homed at [spec/016 §Refetch and invalidation of an infinite feed](../../spec/016-Resources.md#refetch-and-invalidation-of-an-infinite-feed).

## Introspection and projection (tool/test lane)

These direct functions are the tool/test projection lane, not an app-read API:

- `resource-meta` / `mutation-meta` project the **registration** (the registered spec).
- `resource-state` / `resources` / `mutation-state` / `mutations` project **runtime state** (the live entries) as a one-shot, non-reactive snapshot at an explicit frame.

They serve Xray, unit tests, and SSR serialization — contexts with no reactive subscription. App views read runtime state through the passive [`:rf.resource/*` / `:rf.mutation/*` subscriptions](#resource-subscriptions-passive), never through these functions — they do not re-render on change. Registering a handler, dispatching a cause, projecting a snapshot, and subscribing are four distinct jobs; see [Guide ch.27 §Three lanes](../resources/concepts.md#three-lanes--registering-causing-projecting).

`:frame` is an explicit, app-registered frame id ([EP-0002](../EP/EP-0002-frame-target-resolution.md)). There is no ambient `:rf/default` fallback; a frameless call with no resolvable context fails closed.

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
- **Description**: A resource instance's durable runtime entry for an explicit-frame target. The scoped key resolves the same way a subscription's does: a `{:from-db <id>}` scope resolves against the frame's app-db.
  - nil when no entry exists.
  - An absent / nil `:frame` raises `:rf.error/no-frame-context`. This is fail-closed: a nil pass-through would be indistinguishable from a genuinely absent entry.
  - An explicit but unknown / destroyed `:frame` reads as nil.

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
- **Description**: Resource introspection for a frame target — the static registry (every registered id) plus, with `:frame`, the live per-frame resource-instance entries.
  - The `:entries` map is keyed on the CEDN-1 byte `key-id` string — the same key the runtime storage and SSR wire use. Unlike an `=`-keyed scoped-key vector, it cannot collapse CEDN-distinct sequential-params entries.
  - Each entry carries its kind-preserving scoped-resource-key `[scope resource-id params]` under `:resource/key` for destructure / scope-and-resource filtering.

```clojure
(rf/resources)                      ;; => {:resource-ids [...] :entries {}}  (static registry only)
(rf/resources {:frame :rf/default}) ;; => {:resource-ids [...] :entries {<key-id> <entry>}}
```

### `resource-ids`

- **Kind**: function (post-v1 lib; `re-frame.resources` façade only — not on `re-frame.core`)
- **Signature**:
  ```clojure
  (re-frame.resources/resource-ids) → [resource-id …]
  ```
- **Description**: A vector of every registered resource id — the registry-side half of `resources` (which returns the same vector under `:resource-ids`).

```clojure
(re-frame.resources/resource-ids)
;; => [:article/by-slug :feed/timeline]
```

### `mutation-meta`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (mutation-meta mutation-id) → spec-map or nil
  ```
- **Description**: The registered mutation's spec map (`:request`, `:params-schema`, `:invalidates`, `:patches`, `:populates`, `:removes`, `:optimistic`, `:optimistic-tags`, `:on-conflict`, `:scope`, `:invalidate-timing`, `:transport`, `:doc`, source coords), or nil.

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
- **Description**: A mutation **instance**'s durable runtime row (`{:status :result :error …}`) for an explicit-frame target, or nil.
  - The frame is carried explicitly (see [EP-0002](../EP/EP-0002-frame-target-resolution.md)).
  - An absent / nil `:frame` raises `:rf.error/no-frame-context` (fail-closed, symmetric with `resource-state`).
  - An explicit but unknown / destroyed `:frame` reads as nil.

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
  (mutations {:frame …}) → {:mutation-ids [...] :instances {<instance-key-id> <row>}}
  ```
- **Description**: Mutation introspection for a frame target — the registered mutation ids plus, with `:frame`, the live per-frame mutation-instance table.
  - The `:instances` map is keyed on the CEDN-1 byte `key-id` of each instance id (the same identity discipline as `resources` `:entries`).
  - Each row carries its kind-preserving `:instance/id` and `:mutation/id` (Xray groups instances under the registered mutation id).

```clojure
(rf/mutations {:frame :rf/default})
;; => {:mutation-ids [...] :instances {<instance-key-id> <row>}}
```

### `mutation-ids`

- **Kind**: function (post-v1 lib; `re-frame.resources` façade only — not on `re-frame.core`)
- **Signature**:
  ```clojure
  (re-frame.resources/mutation-ids) → [mutation-id …]
  ```
- **Description**: A vector of every registered mutation id — the registry-side half of `mutations` (which returns the same vector under `:mutation-ids`).

```clojure
(re-frame.resources/mutation-ids)
;; => [:article/save]
```

Xray exposes the same shapes, plus:

- the tool accessors — `list-resources`, `list-resource-instances`, `get-resource-state`, `get-resource-history`, `list-resource-invalidations`
- the route/resource graph
- the work-ledger table
- the scope audit surface — the standing enumeration of every `:rf.scope/global` resource

Tool accessors prefer summaries over raw values. Params and scopes get the same privacy/size elision as data.

## Keyword surfaces

The resource and mutation runtime is caused and read entirely through keyword-addressed events and subscriptions — never by calling a function. Resources are caused to fetch by events and read passively by subscriptions; the same split applies to mutations.

### Resource events (map payloads)

Resource events take a **map payload**, not a positional argument vector. The resource-addressed events validate their target:

- An unregistered `:resource` raises `:rf.error/resource-not-registered`.
- Params that fail `:params-schema` raise `:rf.error/resource-invalid-params`.
- Scope resolution is fail-closed (`:rf.error/resource-scope-required-from-caller` / `:rf.error/resource-scope-unresolved-reference` — see [Scope policy](#scope-policy)).

#### `[:rf.resource/ensure {…}]`

- **Kind**: event
- **Payload**: `{:resource :scope :params :owner :cause :keep-previous?}`
- **Description**: Ensure the resource instance is loaded.
  - `ensure` while the same scoped key is already in flight **joins** the existing work (attaches the owner, records the cause, emits a dedupe trace).
  - `ensure` of an already-`:loaded` entry still fresh-by-policy is a fresh-skip: it serves the cached value, attaches the owner, and emits `:rf.resource/cache-hit` — no fetch.
  - `:owner` changes the active-owner set; `:cause` is recorded in trace/history.
  - `:keep-previous?` on a first-loading key records a projection pointer to the prior loaded sibling key. That lets `:rf.resource/previous-data` show old data while the new key loads. The pointer is never inserted into the new entry.

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
- **Description**: Force a refresh. It always starts a new generation, even when a request is already in flight. A still-in-flight prior request is marked superseded, then aborted when possible, otherwise suppressed by work-id + generation. A manual refresh is usually a `:cause`, not an `:owner`.

```clojure
;; a "Refresh" button — a :cause, pointedly no :owner (the route keeps it alive)
[:rf.resource/refetch
 {:resource :articles/list
  :params   {}
  :cause    [:manual :resources.app/refresh-articles]}]
```

#### `[:rf.resource/invalidate-tags {…}]`

- **Kind**: event
- **Payload**: `{:scope :tags :cause :cross-scope?}`
- **Description**: Mark every entry whose tags intersect `:tags` as stale. Entries with active owners are refetched. Inactive entries stay stale or become GC-eligible.
  - **Scoped by default** — a scoped invalidation with no `:scope` raises `:rf.error/resource-invalidate-scope-required` (never a silent nil-scope match).
  - A cross-scope invalidation opts in explicitly with `:cross-scope? true`. It ignores the scope filter, is visible in Xray, and MUST carry `:cause` — omitting one raises `:rf.error/resource-cross-scope-cause-required`.
  - On a successful load an entry's tags are *replaced* with the new data's tags.

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
- **Description**: Causal scope teardown. It:
  - removes (or marks unusable) every entry in the scope
  - releases owners
  - aborts in-flight requests with no owner outside the scope
  - suppresses late replies by scope + generation
  - emits explanatory trace rows

  Required on logout / account / tenant / permission / locale / impersonation change.

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
- **Description**: Extend an `:infinite` feed by one page. The runtime computes the next page param from the entry's tail via `:next-page-param`, fetches that page through the same managed transport, and appends it to the feed's accumulated page vector. The feed stays `:loaded` and the pages stay visible. A load-more in flight shows as the derived `:fetching-next?` sub, distinct from a whole-feed `:fetching?` refresh.
  - A load-more with no next page (`:next-page-param` → `nil`) is a no-op trace.
  - A load-more while a page fetch is already in flight dedupes.
  - A supplied `:owner` is warn-and-ignored — the route (or whatever first-loaded the feed) already owns the one feed entry, and load-more never changes the active-owner set.

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
- **Description**: Scan the frame's active-owner stale entries and refetch them by policy. The refetch carries cause `:focus` (window-focused) or `:reconnect` (network-reconnected). It never carries an owner — it creates no liveness. Generation + stale-suppression protect any late reply. The host listeners ([Revalidation listeners](#revalidation-listeners)) dispatch these; user code MUST NOT dispatch them directly.

```clojure
;; no payload — dispatched by the host listeners (see Revalidation listeners);
;; user code MUST NOT dispatch these directly
[:rf.resource/window-focused]
[:rf.resource/network-reconnected]
```

> **Internal replies — do not dispatch.** `:rf.resource.internal/succeeded` / `…/failed` / `…/page-succeeded` / `…/page-failed` / `…/aborted` / `…/stale-fired` / `…/gc-fired` / `…/poll-fired` / `…/stale-suppressed` / `…/refetch-page` are framework-internal. They carry the verification payload (`:work/id`, `:resource/key`, `:scope`, `:generation`, `:rf.frame/id`). User code MUST NOT dispatch them. Success/failure verify frame + work id + generation before writing — the mandatory stale-suppression boundary. Of these: `:rf.resource.internal/stale-fired` is the stale-timer re-check tick (it arms the stale transition, not a fetch); `:rf.resource.internal/poll-fired` is the poll-timer re-check tick (it refetches an active-owner entry by the interval); `…/page-succeeded` / `…/page-failed` are the infinite-feed page replies; `…/refetch-page` is one leg of a multi-page refetch sweep.

### Resource subscriptions (passive)

A subscription is a pure passive read — it never fetches. It resolves scope per the sub-side precedence and raises `:rf.error/resource-sub-unresolved-scope` rather than reading global or returning a silent `:idle`.

```clojure
[:rf/resource         {:resource … :scope … :params …}]   ;; the full view-model
[:rf.resource/data          {…}]   [:rf.resource/status        {…}]
[:rf.resource/loading?      {…}]   [:rf.resource/fetching?     {…}]
[:rf.resource/stale?        {…}]   [:rf.resource/error         {…}]
[:rf.resource/refresh-error {…}]   [:rf.resource/has-data?     {…}]
[:rf.resource/previous-data {…}]
```

The `:rf/resource` view-model — facts plus **derived** booleans:

```clojure
{:status        :idle | :loading | :fetching | :loaded | :error
 :data          <last-known-good-or-nil>
 :error         <first-load-error-or-nil>          ;; :rf.http/* envelope
 :refresh-error <background-refresh-error-or-nil>  ;; :rf.http/* envelope
 :loading?      <bool>   ;; first load, no usable data
 :fetching?     <bool>   ;; refresh in flight, prior data visible
 :stale?        <bool>   ;; freshness — orthogonal to load status
 :has-data?     <bool>
 :previous?     <bool>}  ;; :keep-previous? projection — when true, also
                         ;; :previous-key + :previous-data (the prior key's data)
```

Status invariants:

- `:loading` = first load, no usable data.
- `:fetching` = refresh in flight while prior data stays visible.
- `:error` = first load failed, no usable data.
- A failed *background* refresh stays `:loaded`, keeps prior `:data`, records `:refresh-error`.

`:stale?` / `:loading?` / `:fetching?` / `:has-data?` are derived sub values, never stored. See [Guide ch.27 §Status](../resources/concepts.md).

> **Lifecycle trace ops (observability, not events you dispatch).** The runtime emits two trace ops here. `:rf.resource/cache-hit` fires when a fresh `ensure` is served from cache with no fetch — a fresh-skip. The already-`:loaded` entry, still fresh-by-policy, serves the cached value and attaches the owner lease; for a blocking route resource it also drains the blocking slot immediately, with no `:fetching` transition. `:rf.resource/stale-fired` fires when a stale timer ticks — it arms the stale transition, not a fetch. These are trace surfaces Xray reads. They are not `:status` values, and user code never dispatches them.

### Mutation events (map payloads)

#### `[:rf.mutation/execute {…}]`

- **Kind**: event
- **Payload**: `{:mutation :params :instance :scope :cause :reply-to :optimistic?}`
- **Description**: Run a mutation.
  - `:instance` is the caller-supplied (or generated) **instance** id all runtime state is keyed by — two concurrent submissions keep distinct rows.
  - On success the runtime patches/populates/removes resource entries then invalidates tags (per `:invalidate-timing`).
  - `:reply-to` is an optional data-only completion continuation target dispatched when the write settles.
  - `:optimistic? false` is the per-call opt-out that forces a registered optimistic plan to run pessimistically.
  - An unregistered `:mutation` raises `:rf.error/mutation-not-registered`; params that fail `:params-schema` raise `:rf.error/mutation-invalid-params`.
  - A superseded reply (a re-execute under the same instance, or an `:rf.mutation/clear`) never overwrites a newer instance (work-id + generation suppression).

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
- **Payload**: `{:instance …}` (clear one instance) or `{:mutation …}` (clear every instance of a mutation id)
- **Description**: The **causal** reset of runtime mutation-instance state. It clears the addressed row(s) and best-effort aborts in-flight work; the work row settles `:cancelled`. Distinct from `clear-mutation`, which removes the *registration*.

```clojure
;; reset one runtime instance's row (e.g. in a completion continuation)
[:rf.mutation/clear {:instance :form/save-1}]
```

### Mutation subscriptions (passive)

A `:rf.mutation/*` subscription is a pure passive read keyed by **instance** id — it never executes a write.

```clojure
[:rf/mutation    {:instance :form/save-1}]   ;; {:status :result :error :affected-keys
                                                   ;;  :pending? :success? :error? :settled? :optimistic?}
[:rf.mutation/status   {:instance :form/save-1}]
[:rf.mutation/pending? {:instance :form/save-1}]
[:rf.mutation/result   {:instance :form/save-1}]
[:rf.mutation/error    {:instance :form/save-1}]
```

`:optimistic?` (derived) is true while a live optimistic apply is showing — applied, not yet settled. `:affected-keys` carries the scoped resource keys the settle touched.

A failure settles `:error` — there is no `:refresh-error` analogue (a write has no last-known-good to keep).

> **Internal replies — do not dispatch.** Two keyword families here are framework-internal, and user code MUST NOT dispatch them: the `:rf.mutation.internal/*` replies (`succeeded` / `failed`), and the `:rf.mutation/*` trace family — `started` / `succeeded` / `failed` / `cleared` / `replied` / `stale-suppressed`, plus the optimistic rows (`optimistic-applied` / `optimistic-reconciled` / `optimistic-rolled-back`). All carry the instance id.

## Reading resource and mutation state

Read resource and mutation state with the ordinary `subscribe`, naming the framework sub vector. There is no named-read-sugar fn: a runtime-db framework read is a subscription vector, one grammar. `subscribe`'s `{:frame <target>}` opts form reads from an explicit frame. A sub never fetches.

```clojure
;; a view reads a resource passively — never fetches
@(rf/subscribe [:rf/resource {:resource :article :params {:slug "hello"}}])
;; => {:status :loading}  …then  {:status :loaded :data {…}}
```

`query` is the `{:resource :params :scope}` map — identical fail-closed scope resolution (`:rf.error/resource-sub-unresolved-scope`).

```clojure
;; a form reads its own submission's state, keyed by instance
@(rf/subscribe [:rf/mutation {:instance :form/save-1}])
;; => {:status :idle …}  …then  {:pending? true …}  …then  {:success? true …}
```

Idle empty-state until the instance's first `:rf.mutation/execute`.

## Cache home

Resource and mutation runtime state lives inside the runtime-db partition (`:rf.db/runtime`):

- the resource cache, **only** at `:rf.runtime/resources`
- the frame work ledger, at `:rf.runtime/work-ledger`
- mutation instance rows, at `:rf.runtime/mutations`

All three are reserved runtime-db keys: framework-owned, per-frame isolated, and allocated lazily. App code reads through the subs and accessors and never hand-edits the slice.

Cache *entries* (durable facts) and work-ledger *attempts* (in-flight records) are separate. Host handles (AbortControllers, timers, promises) live in side tables and are never serialized. The correctness rule: cancellation is opportunistic; stale-reply suppression (by work-id + generation) is **mandatory**. See [Guide ch.27 §Cache home and the work ledger](../resources/concepts.md).

## Examples and cross-references

- [Guide ch.27 — Server-state and resources](../resources/concepts.md) — the tutorial.
- [Glossary](../resources/glossary.md) — the resources and server-state vocabulary in one place.
- [EP-0003 — Resource Queries](../EP/EP-0003-resource-queries.md) — rationale and prior-art benchmark.
- [Migration: re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md) — moving off `shipclojure/re-frame-query` or a hand-rolled Pattern-RemoteData cache.
- [re-frame.http](re-frame.http.md) — the `:rf.http/managed` transport and the `:rf.http/*` failure taxonomy.
- [re-frame.routing](re-frame.routing.md) — `:resources` route metadata.
- [re-frame.ssr](re-frame.ssr.md) — the hydration install path.
