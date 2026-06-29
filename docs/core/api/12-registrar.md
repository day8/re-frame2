# 12 — Registrar query API

The registrar is the data structure that holds every registered handler — events, subs, fx, cofx, flows, machines, views, schemas, the lot. Treating it as a queryable data structure is what makes the framework's tools possible: Xray enumerates handlers to build its UI; the linter walks the registrar to find unreachable handlers; the migration agent reads `handler-meta` to discover source coords; the MCP servers expose handler metadata to LLM clients.

This chapter is the read-side surface. The write-side surface is `reg-*` / `clear-*` (rowed in [01 — Core](01-core.md)).

Everything here is **JVM-runnable** except `sub-cache` (which holds live `Reaction` objects in CLJS).

The registrar-query surface lives in `re-frame.core`:

```clojure
(:require [re-frame.core :as rf])
```

## Handlers

### `registrations`

- **Kind**: function
- **Signature**:
  ```clojure
  (registrations kind) → {id metadata-map}
  (registrations kind pred-fn) → {id metadata-map}
  ```
- **Description**: **Use when you want metadata.** Walk the registrar with the full metadata map per id — source-coords, `:rf/sensitive`, `:rf/machine?`, `:platforms`, the doc string. Optional `pred-fn` filters by the metadata map.

```clojure
;; the full {id metadata} map for a kind — source coords, :doc, :rf/sensitive, ...
(rf/registrations :event)
;; => {:counter/inc {:ns my-app.events :line 12 :file "my_app/events.cljs"} ...}

;; frame-targeted (EP-0023): only the ids that frame's image carries
(rf/registrations {:frame :tenants/acme :kind :sub})
```

### `handler-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-ids kind) → id set
  ```
- **Description**: **Use when you only need to enumerate.** Canonical alias for `(-> (registrations kind) keys set)`. Saves both the metadata-map allocations and the `keys` walk — meaningful at scale (completion lists, existence checks, set-shaped intersections).

```clojure
;; the id set under a kind (no metadata-map allocations)
(rf/handler-ids :event)                            ;; => #{:counter/inc :counter/reset}

;; existence check
(contains? (rf/handler-ids :event) :counter/inc)   ;; => true

;; frame-targeted: only the ids that frame's image carries
(rf/handler-ids {:frame :blue/main :kind :event})  ;; => #{:counter/inc}
```

### `handler-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-meta kind id) → registration-metadata map
  ```
- **Description**: "What did `reg-*` stamp at this id?" View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`) per `:rf/source-coord-meta`; pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup.

```clojure
;; what reg-* stamped at this id — source coords (:ns / :line / :column / :file)
(rf/handler-meta :sub :counter/value)
;; => {:ns my-app.subs :line 8 :column 1 :file "my_app/subs.cljs"}

;; a route guard reading the matched route's registered :tags
(let [route-meta (rf/handler-meta :route id)]
  (boolean (some #{:requires-auth} (:tags route-meta))))
```

`kind` is one of `:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:flow`, `:route`, `:head`, `:error-projector`. App-db schemas are **not** a registrar kind; look them up via `(app-schema-meta-at path)` instead. The [registrar](../glossary.md#registrar) glossary entry covers the one-table-per-process model these kinds share.

## Machines

Machine registrar queries (`machines`, `machine-meta`) live in `re-frame.machines` — see [04 — Machines](../../machines/api.md).

## Frames

### `frame-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-ids)
  (frame-ids ns-prefix)
  ```
- **Description**: "What frames exist?" The optional prefix filters by namespace — `(rf/frame-ids :rf.story/)` for tool-owned frames.

```clojure
;; all live (non-destroyed) frame ids — a set
(rf/frame-ids)              ;; => #{:rf/default :tenants/acme :tenants/globex}

;; filter by namespace prefix (a string): ids whose namespace starts with it
(rf/frame-ids "tenants")   ;; => #{:tenants/acme :tenants/globex}
```

### `frame-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-meta frame-id)
  ```
- **Description**: "What did `reg-frame` / `make-frame` stamp at this frame?" Returns the metadata map: `:fx-overrides`, `:interceptors`, `:ssr`, `:on-error`, schema bindings.

```clojure
;; the effective (post-preset-expansion) metadata for a frame
(rf/frame-meta :tenants/acme)
;; => {:id :tenants/acme :doc "..." :fx-overrides {...} :ssr {...}}

;; read one stamped slot
(:fx-overrides (rf/frame-meta :tenants/acme))
```

### `app-db-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-db-value frame-id) → app-db value (plain map)
  ```
- **Description**: "What's the current `app-db` value for this frame?" Returns the deref'd `app-db` map (a plain value, not the container) — `nil` for an unknown / destroyed frame. The internal container accessor is `re-frame.frame/app-db-container`; app and tool code want the value, so reach for `app-db-value`.

```clojure
;; the deref'd app-db value (plain map; nil for an unknown / destroyed frame)
(rf/app-db-value :rf/default)
;; => {:user {:id 7} :counts {:hits 3}}

;; SSR: read the settled app-db for the request frame
(rf/app-db-value request-frame)
```

### `snapshot-of`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-of path)
  (snapshot-of path opts)
  ```
- **Description**: "What's at this path in `app-db` right now?" Convenience over `app-db-value` + `get-in`.

```clojure
;; value at a path in the active frame's app-db
(rf/snapshot-of [:user :id])          ;; => 7
(rf/snapshot-of [:counts])            ;; => {:hits 3}

;; target a specific frame via opts
(rf/snapshot-of [:n] {:frame :left})  ;; => 11
```

## Sub graph

### `sub-topology`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub-topology) → {sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}
  ```
- **Description**: Static dependency graph from `:<-` declarations. Pure data over the registrar; `:inputs` always present (empty for layer-1 subs); the per-entry `:doc` / `:ns` / `:line` / `:file` keys are present when registration carries them.

```clojure
;; static dependency graph over the registrar (JVM-runnable). Lives in
;; re-frame.subs.tooling — the rf core facade alias was removed.
(require '[re-frame.subs.tooling :as subs-tooling])

(subs-tooling/sub-topology)
;; => {:n   {:input-kind :db     :inputs []}
;;     :sum {:input-kind :static :inputs [[:a] [:b]]}}

;; a parametric (input-fn) sub reports the :parametric sentinel
(:inputs ((subs-tooling/sub-topology) :article/page))   ;; => :parametric
```

### `sub-cache`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub-cache frame-id) → live cache state
  ```
- **Description**: The runtime cache. CLJS-only because it holds live `Reaction` objects; on JVM there are no reactions to hold. Tools that walk the cache for tab labels / counts in Xray go through this.

```clojure
;; the runtime cache snapshot — CLJS-only (nil on the JVM). Lives in
;; re-frame.subs.tooling as sub-cache-snapshot — the rf core facade alias
;; was removed.
(require '[re-frame.subs.tooling :as subs-tooling])

(subs-tooling/sub-cache-snapshot :rf/default)
;; => {[:n]     {:value 7     :ref-count 2 :input-kind :db :realized-inputs []}
;;     [:name*] {:value "ada" :ref-count 1 :input-kind :db :realized-inputs []}}

(subs-tooling/sub-cache-snapshot :no-such-frame)   ;; => nil
```

The split — `sub-topology` JVM-runnable, `sub-cache` CLJS-only — is principled. Topology is a static property of the registration; the cache is a runtime property of the sub graph. Tools that want the design-time picture (linter, doc generator, conformance harness) reach for `sub-topology`; tools that want the runtime picture (Xray's sub-cache tab) reach for `sub-cache`.

## Schemas

The schema-introspection surfaces are rowed in [08 — Schemas](08-schemas.md). They're JVM-runnable and ship in `re-frame.schemas`:

- `app-schemas` — every schema-at-path for the frame
- `app-schema-at` — schema for one path
- `app-schema-meta-at` — full registration metadata for one path
- `app-schemas-digest` — single hash over the whole schema surface

## Pure sub computation

`compute-sub` is the test-friendly companion to `subscribe`. It runs the sub graph against a value of `app-db` — no cache, no reactivity, no frame — and returns the value.

### `compute-sub`

Defined in [10 — Testing](10-testing.md#compute-sub) — the cache-bypassing pure sub evaluator.

## Behaviour against destroyed frames

The pair-tool surfaces all share a common behaviour against destroyed frames:

- `app-db-value` → `nil`
- `epoch-history` → `[]`
- `restore-epoch!` → `false` (and emits `:rf.error/no-such-handler` of kind `:frame`)
- `replace-app-db!` → `false` (same error)
- `register-epoch-listener!` whose observed frame is destroyed → receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace

The pattern: dynamic-ID queries don't throw on absent frames — they return `nil` / `[]` / `false` so callers can compose without try/catch. Errors fire on the trace surface where tools can pick them up.

## See also

- [01 — Core](01-core.md) — the write-side surface (`reg-*` / `clear-*`).
- [04 — Machines](../../machines/api.md), [05 — Flows](05-flows.md), [06 — Routing](../../routing/api.md), [08 — Schemas](08-schemas.md), [09 — SSR](../../ssr/api.md) — the feature-specific surfaces each register their own kind into the registrar.
- [11 — Instrumentation](11-instrumentation.md) — the trace bus is what Xray and the pair tools layer on top of the registrar to give "what's running" alongside "what's registered."
