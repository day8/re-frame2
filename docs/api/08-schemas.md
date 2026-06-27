# 08 — Schemas and data classification

Schemas in re-frame2 are *Malli schemas attached to `app-db` paths*. You register them with `reg-app-schema` (path-keyed, not id-keyed — the only `reg-*` that breaks that pattern, deliberately); the runtime validates `app-db` writes against the matching schemas in dev; production builds elide the validation at the call sites.

Schemas describe **shape and validation**. Per [EP-0025](../EP/EP-0025-data-classification.md), durable `app-db` data classification is *not* a schema concern: a schema must not be a second route to classify an `app-db` path the **event** already owns (the event is `app-db`'s definition site — see below). Where a schema *is* the owner's natural surface — a machine's `:data`, a resource's data/params, an HTTP response body's `:decode` slots — per-slot `:sensitive?` / `:large?` Malli props remain the one-and-only classification route for that owner's data. The full three-owner model (commit-plane classification effects for durable `app-db`; per-slot schema props for owner-local schema'd data; registration metadata for transient payloads) lives in [Guide ch.23 — Privacy and large things](../guide/how-to/keep-secrets-out-of-traces.md).

This chapter covers the registration macros (rowed in [01 — Core](01-core.md), summarised here), the introspection surface in `re-frame.schemas`, the validator-extension seams (`set-schema-validator!` etc.), and the boundary-validation interceptor. For the canonical contracts, see [010-Schemas.md](../../spec/010-Schemas.md), [015-Data-Classification.md](../../spec/015-Data-Classification.md), and [Privacy.md](../../spec/Privacy.md).

## Registration

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path {:schema schema})
  (reg-app-schema path {:schema schema :frame frame})
  ```
- **Description**: "Attach this Malli schema to this `app-db` path." The schema rides the metadata map under `:schema`; the optional frame target rides `:frame` in the same map. **Path is the registration id** — app-db schemas are path-keyed (the schemas-at-paths grain matches `get-in` / `assoc-in`) and live in the schemas artefact's per-frame side-table (per rf2-cq1ak app-db schemas are NOT a registrar kind). `(app-schema-at [:user])` looks up by the same path vector.
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    {:schema [:map [:cells/grid [:map-of :keyword :string]]]})
  ```
- **In the wild**: [7GUIs](https://github.com/day8/re-frame2/tree/main/examples/core/seven_guis)

### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})
  ```
- **Description**: Bulk plural form. Feature-modular apps registering 5–20 paths against the same prefix reach for this. Each entry routes through the singular form and is stamped with this call's source coords. Returns the vector of paths registered.
- **Example**:
  ```clojure
  (rf/reg-app-schemas
    {[:auth]     AuthState
     [:articles] ArticlesState})
  ```
- **In the wild**: [realworld](https://github.com/day8/re-frame2/tree/main/examples/real-apps/realworld_http)

The path-keyed-not-id-keyed asymmetry is principled. Paths are first-class in `get-in` / `assoc-in` / `update-in`; schemas-at-paths matches the dataflow grain; the lookup site (`app-schema-at [:user]`) reads the same way the write site (`(assoc-in db [:user] ...)`) reads. Spelling it as `(reg-app-schema :user/schema {:schema schema})` would have shifted the registration's id away from the dataflow grain.

See [Conventions §`reg-*` return-value rule](../../spec/Conventions.md#reg--return-value-convention) for the wider convention this row participates in.

## Introspection

The introspection surfaces live in `re-frame.schemas` (artefact `day8/re-frame2-schemas`); consumers `(:require [re-frame.schemas :as schemas])`. They are *not* re-exported from `re-frame.core` — the registration macros live in `re-frame.core` and route through the schemas artefact at registration time, but the read-side surface stays in its own namespace.

### `app-schemas`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas)
  (app-schemas {:frame frame-id})
  ```
- **Description**: "Hand me every registered schema-at-path for this frame." Returns `{path schema}`. Tools and agents walk this to enumerate the app's schema surface.

### `app-schema-at`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schema-at path)
  (app-schema-at path {:frame frame-id})
  ```
- **Description**: "Schema for this exact path." Returns the schema value or `nil`.

### `app-schema-meta-at`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schema-meta-at path)
  (app-schema-meta-at path opts-or-frame-id)
  ```
- **Description**: "Full registration-metadata map for this path." Returns `:path`, `:schema`, `:frame`, plus source-coords (`:ns` / `:line` / `:file`) and the rest of `:rf/registration-metadata`. Pair tools and 10x reach for this when they need the registration anchor for click-back-to-code. The lighter `app-schema-at` is the right call when only the schema value is needed.

### `app-schemas-digest`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-schemas-digest) → string
  (app-schemas-digest {:frame frame-id}) → string
  ```
- **Description**: "Single hash over the frame's whole schema surface." Used by SSR hydration compatibility checks and by tools that want to know "has the schema corpus changed?" without diffing schema-by-schema.

### Validator-extension seams

The default validator ships Malli's `validate` / `explain` pair. These seams let apps swap in their own validator — typically to drop the Malli dep entirely, or to add a custom explainer that formats failures for the app's domain.

#### `set-schema-validator!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-validator! validate-fn)
  ```
- **Description**: "Install the validator the framework uses at every dev-time schema-validation site." Swaps ONLY the validator; `nil` disables validation entirely. The default ships Malli's pair; this seam is for apps that want to swap to a different validator without forking the framework. To install the validator/explainer/printer together, use `set-schema-fns!`.

#### `set-schema-fns!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-fns! {:validate validate-fn :explain explain-fn :print print-fn})
  ```
- **Description**: "Atomically install any subset of the validator / explainer / printer bundle from a single map." The honest bundle setter — named for what it sets, not just the validator. Each key is optional; an absent key leaves the existing registration in place, and a `nil` `:print` coerces to the default EDN canonicaliser. The one-call substitute-Malli boot pattern, so the three fns never drift mid-boot.

#### `set-schema-explainer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-explainer! explain-fn)
  ```
- **Description**: "Install the explainer the framework uses to enrich `:rf.error/schema-validation-failure` traces' `:explain` key." Companion to `set-schema-validator!`.

#### `set-schema-printer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-schema-printer! print-fn)
  ```
- **Description**: "Install the schema-print companion the digest pipeline hashes." `(fn [schema-value] canonical-string)`. Must be pure and deterministic across runtimes. `nil` falls back to the default EDN canonicaliser, so the digest is never undefined. Parallel to the validator / explainer setters: non-Malli ports register their own serialiser so cross-runtime digest comparison reflects their port's contract.

The three setters answer three different questions: validation correctness (`validator`), human-readable failure messages (`explainer`), and stable canonical printing for digest (`printer`). Most apps use the defaults; ports and apps swap them selectively.

### The boundary interceptor

#### `validate-at-boundary-interceptor`

- **Kind**: Var (interceptor value)
- **Signature**:
  ```clojure
  validate-at-boundary-interceptor
  ```
- **Description**: A **pre-built interceptor value**, not a fn (interceptor `:id` is `:rf.schema/at-boundary`). Add it to a `reg-event` metadata map's `:interceptors` vector for production-boundary validation. **Do not call it as a fn** — it has no fn arity; invoking `(rf/validate-at-boundary-interceptor ...)` raises `ArityException`.

```clojure
(rf/reg-event ::receive-from-server
  {:interceptors [rf/validate-at-boundary-interceptor]}
  (fn [{:keys [db]} [_ payload]] {:db (assoc db :data payload)}))
```

The pattern: dev-time validation runs at every commit by default; production-time validation runs only at handlers wearing `validate-at-boundary-interceptor`. Use it on handlers that ingest data from outside the app's trust boundary (HTTP replies, websocket frames, postMessage handlers).

## Data classification

Schemas describe shape; **classification of durable `app-db` data is event-owned**, not schema-attached. The `app-db` path is yours at an absolute path you own, so you classify it (EP-0025) by returning a **commit-plane classification effect** from a `reg-event` handler — alongside `:db`, in the same event. There are four, one per axis-and-direction:

```clojure
(rf/reg-event :app/init
  (fn [{:keys [db]} _]
    {:db              (assoc db :auth {})
     :sensitive       [[:auth :token]
                       [:tenant :partner-api-key]]   ;; classify sensitive
     :large           [[:documents :csv-upload]]}))  ;; classify large
;;   :clear-sensitive / :clear-large un-classify a path when its ownership ends.

;; Wire the init event at frame creation; the classification is in place
;; before any off-box egress. The frame owns only the :observability sink
;; policy now — durable app-db paths and HTTP carriers do NOT live on it.
(rf/reg-frame :app/main
  {:initial-events [[:app/init]]})

;; App-specific HTTP carrier names ride the :rf.http/managed registration.
(rf/reg-fx :rf.http/managed
  {:carriers {:headers ["X-Honeycomb-Team"]}}
  re-frame.http.managed/managed-handler)
```

A `reg-frame` / `make-frame` config carrying `:sensitive` or `:large` **fails loud at registration** (EP-0025 retired the frame keys — the durable `:app-db` block moved to the commit-plane effects, the `:http` carrier block moved to `:rf.http/managed`). There is likewise **no** schema-attached or imperative-mark route to classify a durable `app-db` path; the event is `app-db`'s definition site, and that is the one route. (Schema `:sensitive?` / `:large?` props remain the route for *owner-local schema'd* data — machine `:data`, resource data/params, HTTP response bodies — see [04 — Machines](../machines/api.md), [16 — Resources](../resources/api.md), [07 — HTTP](../resources/http-api.md).) Transient payloads (event args, sub/flow outputs, the `:rf.http/managed` `:carriers` block) are classified by the registration that introduces the shape.

The full teaching of the three owners, the two projection primitives, and the egress profiles lives in [Guide ch.23 — Privacy and large things](../guide/how-to/keep-secrets-out-of-traces.md). For the framework-internal egress primitives (`project-egress`, `elide-wire-value`) consumed by tools and sinks, see [11 — Instrumentation](11-instrumentation.md).

### Composition rule

When both classifications match the same slot (`:sensitive?` AND `:large?`), **sensitive drop wins** — the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest` information from a sensitive slot. The composition rule is normative; per [009 §Size elision in traces](../../spec/009-Instrumentation.md#size-elision-in-traces) and [015 §Projection](../../spec/015-Data-Classification.md).

## See also

- [01 — Core](01-core.md) — `reg-app-schema` / `reg-app-schemas` rowed in registration.
- [03 — Effects and interceptors](03-effects.md) — `validate-at-boundary-interceptor` rowed in the interceptor table.
- [11 — Instrumentation](11-instrumentation.md) — `project-egress` / `elide-wire-value` and the trace-surface privacy posture.
- [Spec 010 — Schemas](../../spec/010-Schemas.md), [015-Data-Classification.md](../../spec/015-Data-Classification.md), [Privacy.md](../../spec/Privacy.md), [Security.md](../../spec/Security.md).
