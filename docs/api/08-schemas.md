# 08 — Schemas and data classification

Schemas in re-frame2 are *Malli schemas attached to `app-db` paths*. You register them with `reg-app-schema` (path-keyed, not id-keyed — the only `reg-*` that breaks that pattern, deliberately); the runtime validates `app-db` writes against the matching schemas in dev; production builds elide the validation at the call sites; and a small set of marks (`:sensitive?`, `:large?`) on the schemas drive automatic redaction and size-elision at every wire boundary.

The payoff is that the same schema declaration drives three separate surfaces: dev-time validation, observability redaction (Causa, story-mcp, off-box error forwarders), and bundle-time size protection. You don't write the privacy rules three times in three different places; you declare them once on the schema, and the framework's wire-boundary walker enforces them everywhere.

This chapter covers the registration macros (rowed in [01 — Core](01-core.md), summarised here), the introspection surface in `re-frame.schemas`, the validator-extension seams (`set-schema-validator!` etc.), the boundary-validation interceptor, and the data-classification mechanism (`add-marks`, `set-marks`, plus the egress-side `sensitive?` / `redact-interceptor` / `elide-wire-value` surface). For the canonical contract, see [010-Schemas.md](../../spec/010-Schemas.md) and [Privacy.md](../../spec/Privacy.md).

## Registration

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path schema)
  (reg-app-schema path schema opts)
  ```
- **Description**: "Attach this Malli schema to this `app-db` path." **Path is the registration id** — the `:app-schema` registry kind is path-keyed because schemas-at-paths matches the dataflow grain. `(app-schema-at [:user])` looks up by the same path vector.
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    [:map [:cells/grid [:map-of :keyword :string]]])
  ```
- **In the wild**: [7Guis](https://github.com/day8/re-frame2/tree/main/examples/reagent/7Guis)

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
- **In the wild**: [realworld](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld)

The path-keyed-not-id-keyed asymmetry is principled. Paths are first-class in `get-in` / `assoc-in` / `update-in`; schemas-at-paths matches the dataflow grain; the lookup site (`app-schema-at [:user]`) reads the same way the write site (`(assoc-in db [:user] ...)`) reads. Spelling it as `(reg-app-schema :user/schema schema)` would have shifted the registration's id away from the dataflow grain.

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
  (set-schema-validator! {:validate validate-fn :explain explain-fn})
  ```
- **Description**: "Install the validator the framework uses at every dev-time schema-validation site." `nil` disables validation entirely. The default ships Malli's pair; this seam is for apps that want to swap to a different validator without forking the framework.

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
- **Description**: A **pre-built interceptor value**, not a fn (interceptor `:id` is `:rf.schema/at-boundary`). Add it to a `reg-event-*`'s positional interceptor vector for production-boundary validation. **Do not call it as a fn** — it has no fn arity; invoking `(rf/validate-at-boundary-interceptor ...)` raises `ArityException`.

```clojure
(rf/reg-event-db ::receive-from-server
  [rf/validate-at-boundary-interceptor]
  (fn [db [_ payload]] (assoc db :data payload)))
```

The pattern: dev-time validation runs at every commit by default; production-time validation runs only at handlers wearing `validate-at-boundary-interceptor`. Use it on handlers that ingest data from outside the app's trust boundary (HTTP replies, websocket frames, postMessage handlers).

## Data classification

The same schemas that drive validation also drive **redaction** and **size-elision** at every wire boundary. The mechanism: schemas carry `:sensitive?` and `:large?` flags on the paths that need them; the framework's egress-side walker `elide-wire-value` consults the registered marks; sensitive paths render as `:rf/redacted` and large paths render as `:rf.size/large-elided` summaries.

### The mark-set

#### `add-marks`

- **Kind**: function
- **Signature**:
  ```clojure
  (add-marks frame-id {path mark, ...})
  ```
- **Description**: Frame-scoped path-marks. **Additively merges** into the frame's existing mark-set — paths not mentioned keep their prior state. Schema-attached marks per `reg-app-schema` `:sensitive?` / `:large?` are preserved and union at lookup time. Pure declaration — does not mutate `app-db`. Returns `frame-id`.

#### `set-marks`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-marks frame-id {path mark, ...})
  ```
- **Description**: Frame-scoped path-marks. **Wholesale replaces** the frame's prior mark-set — paths not mentioned are CLEARED. Schema-attached marks are preserved. Pure declaration. Returns `frame-id`.

The two-verb shape (`add` vs `set`) follows the [Conventions §Tear-down verb axis](../../spec/Conventions.md) — `add-` merges; `set-` replaces. Most apps reach for `add-marks` because path classifications accumulate (an audit reveals a new sensitive path; you `add-marks` the path without affecting the rest of the corpus). Reach for `set-marks` when you're declaring the entire authoritative classification at once (a server-pushed policy update; a feature-flag toggle that swaps the whole privacy posture).

### The egress-side surface: `elide-wire-value`

This is the framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots. Every tool that emits wire data — off-box error-monitor forwarders, the Causa-MCP and re-frame2-pair-mcp and story-mcp servers, the on-box dev panels — routes through this walker. **The walker is the single normative emission site for the `:rf/redacted` sentinel and the `:rf.size/large-elided` marker.** Per-tool reimplementation is prohibited.

#### `elide-wire-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (elide-wire-value v opts) → v or an elision-marker substitution
  ```
- **Description**: Walk `v` consulting `[:rf/elision :declarations]` and `[:rf/elision :sensitive-declarations]` of the named frame's `app-db`. Substitute `:rf/redacted` for sensitive slots and `:rf.size/large-elided` markers for large slots. `opts` map: `{:rf.size/include-large? :rf.size/include-sensitive? :rf.size/include-digests? :rf.size/threshold-bytes :path :frame}`. Defaults: both `include-*` flags `false` (maximum elision); `:rf.size/threshold-bytes` falls back to `(rf/configure :elision ...)` then `16384`.

#### `elision-declarations`

- **Kind**: function
- **Signature**:
  ```clojure
  (elision-declarations)
  (elision-declarations frame-id)
  ```
- **Description**: "What paths has the frame nominated for elision?" Returns the current `[:rf/elision :declarations]` map for the frame (or `{}`). Pair-tool and introspection reader.

#### `populate-elision-from-schemas!`

- **Kind**: function
- **Signature**:
  ```clojure
  (populate-elision-from-schemas!) → vector of paths populated
  (populate-elision-from-schemas! frame-id) → vector of paths populated
  ```
- **Description**: Boot-time hydrator that walks the frame's registered app-schemas and writes `{:large? true :source :schema}` declarations for every path whose Malli schema carries `:large? true`. Idempotent. No-op when the schemas artefact isn't on the classpath.

### Composition rule

When both predicates match (`:sensitive?` AND `:large?` apply to the same path), **sensitive drop wins** — the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest` information from a sensitive slot. The walker's composition rule is normative; per [009 §Size elision in traces](../../spec/009-Instrumentation.md#size-elision-in-traces).

### Schema-only declaration path

The `[:rf/elision]` registry has exactly two slots: `:declarations` (schema-derived `:large?` paths, populated by `populate-elision-from-schemas!`) and `:sensitive-declarations` (schema-derived `:sensitive?` paths). **There is no runtime declaration API** — apps declare `:large?` / `:sensitive?` on the Malli schema and `rf/reg-app-schema` it; the boot-time hydrator does the rest.

The single normative reference for "schemas are the only path" lives in [Guide ch.25 — Large blobs](../guide/25-large-blobs.md).

## Privacy: the always-on predicate and the interceptor

The trace runtime stamps `:sensitive? true` at the top level of every trace event emitted inside the scope of a handler whose schema-derived path overlap declares sensitivity. (The legacy handler-meta `:sensitive?` annotation has been removed — sensitive data marking is path-based per the data-classification mechanism above.) Framework-published trace consumers — Sentry / Honeybadger forwarders, the re-frame2-pair server, Causa, Story, story-mcp, re-frame2-pair-mcp — MUST default-drop the stamped events at their egress boundary.

### `sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (sensitive? trace-event) → boolean
  ```
- **Description**: The framework-published predicate every consumer composes against. Replaces per-consumer reimplementations of the same five-token check.

### `redact-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (redact-interceptor paths) → interceptor
  ```
- **Description**: Build a positional interceptor that overwrites the named keys in the event vector's payload map with the `:rf/redacted` sentinel **before the handler chain runs**. The handler body itself sees the UNREDACTED payload via the regular `:event` coeffect slot; the redaction is for the trace surface only. `paths` is a vector of `get-in`-style key paths into the payload map.

The composition pattern: schema-derived `:sensitive?` marks drive `elide-wire-value` at egress, and `redact-interceptor` scrubs in-place where the trace surface needs to see only a partial view. The two surfaces stack — the interceptor scrubs the trace; the walker enforces redaction at the wire boundary.

See [Security §Privacy / secret handling](../../spec/Security.md#privacy--secret-handling) for the framework-wide pattern-level posture, and [Privacy.md](../../spec/Privacy.md) for the cross-artefact inventory and composition order.

## See also

- [01 — Core](01-core.md) — `reg-app-schema` / `reg-app-schemas` / `add-marks` / `set-marks` rowed in registration.
- [03 — Effects and interceptors](03-effects.md) — `validate-at-boundary-interceptor` rowed in the interceptor table.
- [11 — Instrumentation](11-instrumentation.md) — `elide-wire-value` and the trace-surface privacy posture.
- [Spec 010 — Schemas](../../spec/010-Schemas.md), [Privacy.md](../../spec/Privacy.md), [Security.md](../../spec/Security.md).
