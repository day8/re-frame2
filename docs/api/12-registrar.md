# 12 — Registrar query API

The registrar is the data structure that holds every registered handler — events, subs, fx, cofx, flows, machines, views, schemas, the lot. Treating it as a queryable data structure is what makes the framework's tools possible: Causa enumerates handlers to build its UI; the linter walks the registrar to find unreachable handlers; the migration agent reads `handler-meta` to discover source coords; the MCP servers expose handler metadata to LLM clients.

This chapter is the read-side surface. The write-side surface is `reg-*` / `clear-*` (rowed in [01 — Core](01-core.md)).

Everything here is **JVM-runnable** except `sub-cache` (which holds live `Reaction` objects in CLJS).

## Handlers

### `registrations`

- **Kind**: function
- **Signature**:
  ```clojure
  (registrations kind) → {id metadata-map}
  (registrations kind pred-fn) → {id metadata-map}
  ```
- **Status**: v1 · JVM-runnable
- **Description**: **Use when you want metadata.** Walk the registrar with the full metadata map per id — source-coords, `:rf/sensitive`, `:rf/machine?`, `:platforms`, the doc string. Optional `pred-fn` filters by the metadata map.

### `handler-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-ids kind) → id set
  ```
- **Status**: v1 · JVM-runnable
- **Description**: **Use when you only need to enumerate.** Canonical alias for `(-> (registrations kind) keys set)`. Saves both the metadata-map allocations and the `keys` walk — meaningful at scale (completion lists, existence checks, set-shaped intersections).

### `handler-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-meta kind id) → registration-metadata map
  ```
- **Status**: v1 · JVM-runnable
- **Description**: "What did `reg-*` stamp at this id?" View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`) per `:rf/source-coord-meta`; pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup.

`kind` is one of `:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:flow`, `:route`, `:head`, `:error-projector`, `:app-schema`. The full list lives in [001-Vision §Registry model](../../spec/000-Vision.md).

## Machines

These are derived views over the event registrar — a machine is registered as an event handler with `:rf/machine? true`, so `(machines)` is just `(registrations :event)` filtered by that flag. They're rowed here separately because tools reach for them often enough that the convenience is worth it.

### `machines`

- **Kind**: function
- **Signature**:
  ```clojure
  (machines) → seq of machine-ids
  ```
- **Status**: v1 · JVM-runnable
- **Description**: Enumerate registered machines.

### `machine-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (machine-meta machine-id) → registration-metadata map
  ```
- **Status**: v1 · JVM-runnable
- **Description**: Transition table, doc, schemas. Equivalent to `(handler-meta :event machine-id)`.

See [04 — Machines](04-machines.md) for the rest of the machine surface (subscription helpers, system-id reverse lookup, etc).

## Frames

### `frame-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-ids)
  (frame-ids ns-prefix)
  ```
- **Status**: v1 · JVM-runnable
- **Description**: "What frames exist?" The optional prefix filters by namespace — `(rf/frame-ids :rf.story/)` for tool-owned frames.

### `frame-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-meta frame-id)
  ```
- **Status**: v1 · JVM-runnable
- **Description**: "What did `reg-frame` / `make-frame` stamp at this frame?" Returns the metadata map: `:fx-overrides`, `:interceptors`, `:ssr`, `:on-error`, schema bindings.

### `get-frame-db`

- **Kind**: function
- **Signature**:
  ```clojure
  (get-frame-db frame-id) → app-db value (plain map)
  ```
- **Status**: v1 · JVM-runnable
- **Description**: "What's the current `app-db` for this frame?" Returns `nil` for an unknown / destroyed frame.

### `snapshot-of`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-of path)
  (snapshot-of path opts)
  ```
- **Status**: v1 · JVM-runnable
- **Description**: "What's at this path in `app-db` right now?" Convenience over `get-frame-db` + `get-in`.

## Sub graph

### `sub-topology`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub-topology) → {sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}
  ```
- **Status**: v1 · JVM-runnable
- **Description**: Static dependency graph from `:<-` declarations. Pure data over the registrar; `:inputs` always present (empty for layer-1 subs); the per-entry `:doc` / `:ns` / `:line` / `:file` keys are present when registration carries them.

### `sub-cache`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub-cache frame-id) → live cache state
  ```
- **Status**: v1 · CLJS-only
- **Description**: The runtime cache. CLJS-only because it holds live `Reaction` objects; on JVM there are no reactions to hold. Tools that walk the cache for tab labels / counts in Causa go through this.

The split — `sub-topology` JVM-runnable, `sub-cache` CLJS-only — is principled. Topology is a static property of the registration; the cache is a runtime property of the sub graph. Tools that want the design-time picture (linter, doc generator, conformance harness) reach for `sub-topology`; tools that want the runtime picture (Causa's sub-cache tab) reach for `sub-cache`.

## Schemas

The schema-introspection surfaces are rowed in [08 — Schemas](08-schemas.md). They're JVM-runnable and ship in `re-frame.schemas`:

- `app-schemas` — every schema-at-path for the frame
- `app-schema-at` — schema for one path
- `app-schema-meta-at` — full registration metadata for one path
- `app-schemas-digest` — single hash over the whole schema surface

## Pure sub computation

`compute-sub` is the test-friendly companion to `subscribe`. It runs the sub graph against a value of `app-db` — no cache, no reactivity, no frame — and returns the value.

### `compute-sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (compute-sub query-v db)
  ```
- **Status**: v1 · JVM-runnable
- **Description**: Pure sub computation against an `app-db` value. Use in tests; use in agent tooling that wants to evaluate subs against an artificial state.

(Cross-rowed in [10 — Testing](10-testing.md).)

## Behaviour against destroyed frames

The pair-tool surfaces all share a common behaviour against destroyed frames, documented at [Tool-Pair §Surface behaviour against destroyed frames](../../spec/Tool-Pair.md#surface-behaviour-against-destroyed-frames):

- `get-frame-db` → `nil`
- `epoch-history` → `[]`
- `restore-epoch` → `false` (and emits `:rf.error/no-such-handler` of kind `:frame`)
- `reset-frame-db!` → `false` (same error)
- `register-epoch-listener!` whose observed frame is destroyed → receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace

The pattern: dynamic-ID queries don't throw on absent frames — they return `nil` / `[]` / `false` so callers can compose without try/catch. Errors fire on the trace surface where tools can pick them up.

## See also

- [01 — Core](01-core.md) — the write-side surface (`reg-*` / `clear-*`).
- [04 — Machines](04-machines.md), [05 — Flows](05-flows.md), [06 — Routing](06-routing.md), [08 — Schemas](08-schemas.md), [09 — SSR](09-ssr.md) — the feature-specific surfaces each register their own kind into the registrar.
- [11 — Instrumentation](11-instrumentation.md) — the trace bus is what Causa and the pair tools layer on top of the registrar to give "what's running" alongside "what's registered."
