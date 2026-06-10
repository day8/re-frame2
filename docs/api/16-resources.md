# 16 — Resources

A resource is a named, cached read of remote state — re-frame2's answer to TanStack Query / RTK Query / SWR / `re-frame-query`, re-expressed in the model: **views read server state passively through subscriptions; route entry, events, and machines *cause* it to fetch; the cache lives in the framework-owned runtime partition, not `app-db`.** You register a resource once with a scope policy, a params schema, and a request; after that the runtime owns identity, cache scope, staleness, dedupe, invalidation, GC, in-flight ownership, SSR hydration, and the metadata Xray reads.

Resources are an **optional, post-v1 capability** — they ship in `day8/re-frame2-resources` (`re-frame.resources`), require `day8/re-frame2-http` for the transport, and are wired by one `(:require [re-frame.resources])` at app boot. An app that omits the artefact sees the `reg-resource` wrapper throw a clean `:rf.error/resources-artefact-missing`. This chapter covers the registration shape, the events, the subs, and introspection. The tutorial is [Guide ch.27 — Server-state and resources](../guide/27-resources.md); the normative source is [016-Resources.md](../../spec/016-Resources.md).

> **Scope is HTTP-only; mutations and GraphQL are deferred.** `reg-mutation` / `:rf.mutation/execute` and focus/reconnect revalidation land with the next slice; GraphQL is a later phase. See [Guide ch.27 §What's deferred](../guide/27-resources.md#whats-deferred).

## Registration

### `reg-resource`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (reg-resource resource-id resource-spec)
  ```
- **Description**: Register a resource as data. Validates the spec — the **required, fail-closed `:scope` policy** first, then `:params-schema` and `:request` — and writes a `:resource`-kind registrar entry. Returns `resource-id`.
- **In the wild**: see the [examples](#examples-and-cross-references) below.

### The resource spec

```clojure
(rf/reg-resource :article/by-slug
  {:doc "Article detail by slug."

   :params-schema [:map [:slug :string]]      ;; REQUIRED — validates + canonicalizes params
   :data-schema   :app/article                ;; validates successful data when decode supports it

   :request                                    ;; REQUIRED — returns a Spec 014 managed-HTTP args map
   (fn [{:keys [slug]} _ctx]
     {:request {:method :get :url (str "/api/articles/" slug)}
      :decode  :app/article})

   :scope          :rf.scope/global            ;; REQUIRED — an explicit, auditable claim
   :transport      :rf.http/managed            ;; the only initial-scope transport
   :stale-after-ms 60000
   :gc-after-ms    300000
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})
   :sensitive?     false})
```

**Required keys**:

| Key | Notes |
|---|---|
| `:params-schema` | Validates and canonicalizes params (the resource's identity). |
| `:scope` | The scope **policy** — `:rf.scope/global`, a resolver, or `:rf.scope/from-caller`. **Required, fail-closed** — no policy is a loud `:rf.error/resource-missing-scope-policy`. There is no implicit default; a user-scoped read must say so. |
| `:request` | For `:transport :rf.http/managed`, returns a [Spec 014](../../spec/014-HTTPRequests.md) args map. MUST NOT supply `:request-id` / `:on-success` / `:on-failure` — the runtime supplies those from the scoped key + generation (rejected if present). |
| `:data-schema` | Validates successful data when transport decode supports it. |

**Optional keys**: `:doc`, `:transport` (initial scope: `:rf.http/managed`), `:stale-after-ms`, `:gc-after-ms`, `:tags`, `:sensitive?` / `:large?` / schema-based classification.

**Rejected / unused in v1**: `:poll-ms`, `:revalidate`, `:placeholder`, `:cache-key`, `:select`, `:infinite`, transport extension protocols, and mutation-only keys (`:invalidates`, `:optimistic`, `:rollback`).

### Scope policy

`:scope` is required and declares a policy from a closed set:

| Policy | Meaning |
|---|---|
| `:rf.scope/global` | The resource is **explicitly** global — a claim that the same params produce the same data for every user/tenant/permission/locale/impersonation state. Auditable, not a convenience default. |
| `<resolver>` | Derive the scope. A route-resource resolver is `(fn [route ctx] …)`; a sub-resolvable resolver is a pure data value or a fn-of-nothing. |
| `:rf.scope/from-caller` | Scope required from the use site — every ensure / refetch / state call must supply `:scope` (or a route resolver must). |

There is no `[:rf.scope/global]` fallthrough. Event resolution precedence: payload `:scope` → route resolver → spec resolver. Subscription resolution: payload `:scope` → sub-resolvable spec policy → loud `:rf.error/resource-sub-unresolved-scope` (never a silent global read or `:idle`). See [Guide ch.27 §Scope](../guide/27-resources.md#scope--the-leak-boundary-that-fails-closed).

### `clear-resource`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (clear-resource resource-id)
  ```
- **Description**: Remove a registered resource. A **registration-lifecycle** operation — NOT the normal cache-invalidation API (for data lifecycle use `:rf.resource/invalidate-tags` / `:rf.resource/remove` / `:rf.resource/clear-scope`). Also disposes the resource-runtime state for the id in each affected frame (release owner indexes, cancel timers/host handles, abort in-flight where possible, suppress late replies by generation, remove tag-index rows, emit a trace). Returns `resource-id`.

## Events (map payloads)

Resource events take a **map payload**, not a positional argument vector.

### `[:rf.resource/ensure {…}]`

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

### `[:rf.resource/refetch {…}]`

- **Kind**: event
- **Payload**: `{:resource :scope :params :owner :cause}`
- **Description**: Force a refresh. May force a new generation — a still-in-flight prior request is marked superseded, aborted when possible, otherwise suppressed by work-id + generation. A manual refresh is usually a `:cause`, not an `:owner`.

### `[:rf.resource/invalidate-tags {…}]`

- **Kind**: event
- **Payload**: `{:scope :tags :cause}`
- **Description**: Mark entries whose tags intersect `:tags` stale; refetch entries with active owners; leave inactive entries stale or GC-eligible. **Scoped by default** — a cross-scope invalidation must opt in explicitly and is visible in Xray. On a successful load an entry's tags are *replaced* with the new data's tags.

### `[:rf.resource/release-owner {…}]`

- **Kind**: event
- **Payload**: `{:owner …}`
- **Description**: Release an owner lease. Aborts in-flight work only when no remaining owner needs it. App-minted leases (`[:lease …]`) MUST have a matching release path — an orphaned lease pins an entry alive (Xray lints it).

### `[:rf.resource/clear-scope {…}]`

- **Kind**: event
- **Payload**: `{:scope :cause}`
- **Description**: Causal scope teardown. Removes/marks-unusable every entry in the scope, releases owners, aborts in-flight requests with no owner outside the scope, suppresses late replies by scope + generation, emits explanatory trace rows. Required on logout / account / tenant / permission / locale / impersonation change.

### `[:rf.resource/remove {…}]`

- **Kind**: event
- **Payload**: `{:resource :scope :params}`
- **Description**: Remove a single resource instance's cache entry.

> **Internal replies — do not dispatch.** `:rf.resource.internal/succeeded` / `…/failed` / `…/aborted` / `…/gc-fired` / `…/stale-suppressed` are framework-internal and carry the verification payload (`:work-id`, `:resource-key`, `:scope`, `:generation`, `:rf.frame/id`). User code MUST NOT dispatch them; success/failure verify frame + work id + generation before writing (the mandatory stale-suppression boundary).

## Subscriptions (passive)

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

Status invariants: `:loading` = first load, no usable data; `:fetching` = refresh in flight while prior data stays visible; `:error` = first load failed, no usable data; a failed *background* refresh stays `:loaded`, keeps prior `:data`, records `:refresh-error`. `:stale?` / `:loading?` / `:fetching?` / `:has-data?` are derived sub values, never stored. See [Guide ch.27 §Status](../guide/27-resources.md#status--facts-not-derived-booleans).

## Introspection

`:frame` is an explicit, app-registered frame id ([EP-0002](../EP/EP-0002-frame-target-resolution.md) — no ambient `:rf/default` fallback; a frameless call with no resolvable context fails closed).

### `resource-meta`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resource-meta resource-id) → spec-map or nil
  ```
- **Description**: The registered resource's spec (`:params-schema`, `:data-schema`, `:request`, `:scope`, `:transport`, `:stale-after-ms`, `:gc-after-ms`, `:tags`, `:doc`, source coords).

### `resource-state`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resource-state {:resource … :scope … :params … :frame …}) → entry or nil
  ```
- **Description**: A resource instance's durable runtime entry for an explicit-frame target, resolving the scoped key the same way a subscription does. nil when no entry exists.

### `resources`

- **Kind**: function (post-v1 lib)
- **Signature**:
  ```clojure
  (resources)            → {:resource-ids [...] :entries {}}
  (resources {:frame …}) → {:resource-ids [...] :entries {<scoped-key> <entry>}}
  ```
- **Description**: Resource introspection for a frame target — the static registry (every registered id) plus, with `:frame`, the live per-frame resource-instance entries.

Xray exposes the same shapes plus the tool accessors (`list-resources`, `list-resource-instances`, `get-resource-state`, `get-resource-history`, `list-resource-invalidations`), the route/resource graph, the work-ledger table, and the **scope audit surface** (the standing enumeration of every `:rf.scope/global` resource). Tool accessors prefer summaries over raw values; params/scopes get the same privacy/size elision as data.

## Cache home

Resource cache lives **only** at `:rf.runtime/resources` inside the runtime-db partition (`:rf.db/runtime`); the frame work ledger at `:rf.runtime/work-ledger`. Both are reserved runtime-db keys, framework-owned, per-frame isolated, allocated lazily. App code reads through the subs and accessors and never hand-edits the slice. Cache *entries* (durable facts) and work-ledger *attempts* (in-flight records) are deliberately separate; host handles (AbortControllers, timers, promises) live in side tables and are never serialized. The correctness rule: **cancellation is opportunistic; stale-reply suppression (by work-id + generation) is mandatory.** See [Guide ch.27 §Cache home and the work ledger](../guide/27-resources.md#cache-home-and-the-work-ledger).

## Examples and cross-references

- [Guide ch.27 — Server-state and resources](../guide/27-resources.md) — the tutorial.
- [Spec 016 — Resources](../../spec/016-Resources.md) — the normative contract.
- [EP-0003 — Resource Queries](../EP/EP-0003-resource-queries.md) — rationale and prior-art benchmark.
- [Migration: re-frame-query → resources](../../migration/from-re-frame-v1/re-frame-query-to-resources.md) — moving off `shipclojure/re-frame-query` or a hand-rolled Pattern-RemoteData cache.
- [07 — HTTP](07-http.md) — the `:rf.http/managed` transport and the `:rf.http/*` failure taxonomy.
- [06 — Routing](06-routing.md) — `:resources` route metadata.
- [09 — SSR](09-ssr.md) — the hydration install path.
