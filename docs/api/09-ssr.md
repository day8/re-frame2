# 09 — SSR

Server-side rendering in re-frame2 is the same framework as the client side — same registrations, same cascade, same `app-db`, same subs. The differences are: the request creates a frame (the per-request frame pattern), the cascade runs to completion before the response is built, the resulting hiccup is emitted as an HTML string, and a hydration payload is shipped with it so the client can pick up where the server left off without re-rendering.

This works because the framework was designed around immutable data and an explicit cascade boundary. The handler that runs server-side is the same handler the client would run; the only differences are which fx are gated to which platform (`:platforms` metadata) and which cofx are server-only (`:rf.server/request`).

This chapter covers the rendering primitives (`render-to-string`, the streaming triple, the structural-hash), the head model (`reg-head`, `active-head`, `render-head`), the per-request response accumulator and its server-only fx, the error-projection seam (`reg-error-projector`, `project-error`), and the `:platforms` metadata that gates fx execution by active platform.

The normative source is [011-SSR.md](../../spec/011-SSR.md). The SSR surfaces live in `re-frame.ssr` (artefact `day8/re-frame2-ssr`); the Ring host-adapter lives in `re-frame.ssr.ring`. Neither is re-exported from `re-frame.core` — apps targeting SSR add the artefacts to their deps and require the namespace directly.

## Rendering primitives

### `render-to-string`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-to-string view-or-hiccup opts) → HTML string
  ```
- **Description**: The canonical server-side render. Walks the hiccup tree once, emits a string. JVM-runnable. Test-friendly because it's pure.
- **Example**:
  ```clojure
  (rf/with-frame [f (rf/make-frame {:on-create [:app/server-init]})]
    (ssr/render-to-string [app-root] {:frame f}))
  ```
- **In the wild**: [ssr](https://github.com/day8/re-frame2/tree/main/examples/reagent/ssr)

### `render-tree-hash`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-tree-hash render-tree) → 32-bit FNV-1a structural hash (lowercase hex)
  ```
- **Description**: A deterministic structural fingerprint of a render tree. Same canonical-EDN representation produces the same hash on JVM and CLJS. Used by the hydration compatibility check — if the server's hash doesn't match the client's hash, hydration is unsafe.

### `project-error`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-error frame-id trace-event) → :rf/public-error
  ```
- **Description**: Apply the active error-projector (selected by the frame's `:ssr {:public-error-id ...}` metadata) for the named frame. This is the seam between "internal error trace event with full diagnostic detail" and "client-safe public-error projection."

### Streaming render

Larger pages benefit from streaming — emit the shell-html and continue rendering boundary subtrees as their data becomes available. Re-frame2's streaming model uses `:rf/suspense-boundary` markers in the render tree; each boundary becomes a continuation.

### `streaming-render-shell`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-render-shell root-hiccup)
    → {:shell-html "..." :continuations [{:id :subtree} ...]}
  ```
- **Description**: Walk the tree once; at each `:rf/suspense-boundary` emit a `<template …suspense-fallback>` placeholder and record a continuation. Returns the shell-html (ready to flush) and the continuations to drain.
- **In the wild**: [ssr_streaming](https://github.com/day8/re-frame2/tree/main/examples/reagent/ssr_streaming)

### `streaming-render-continuation`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-render-continuation frame-id entry)
    → {:id :html :delta :failed?}
  ```
- **Description**: Drain one continuation against `frame-id`'s app-db. Snapshots before-db / after-db and computes the per-subtree delta. Catches throws and surfaces the original fallback HTML inline (per [011 §Failure semantics — inline fallback](../../spec/011-SSR.md#failure-semantics--inline-fallback)).

### `streaming-build-final-payload`

- **Kind**: function
- **Signature**:
  ```clojure
  (streaming-build-final-payload frame-id render-hash opts)
    → canonical :rf/hydration-payload
  ```
- **Description**: Called after all continuations drain to populate the `__rf_payload` final chunk.

The streaming surface is host-adapter territory — the SSR-aware host (`re-frame.ssr.ring` or equivalent) wires it. Most app code interacts via the host adapter and never touches `streaming-render-*` directly.

## The head model

The `<head>` of an SSR document is structurally separate from the body. Re-frame2 models it as a head-model — a data structure carrying `:title`, `:meta`, `:link`, `:json-ld`, `:html-attrs`, `:body-attrs` — registered per-route and rendered separately from the body.

### `reg-head`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-head id ?metadata head-fn)
  ```
- **Description**: Register a head-fn keyed by id. Signature: `(fn [db route] head-model)`. Routes opt-in via `:head` route metadata.
- **Example**:
  ```clojure
  (rf/reg-head :app/head
    (fn [db _route]
      {:title (str "MyApp — " (:page-title db))
       :meta  [{:name "description" :content (:summary db)}]}))
  ```

### `render-head`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-head head-id opts) → :rf/head-model
  ```
- **Description**: Evaluate the registered head-fn for `head-id`. Returns a head-model.

### `active-head`

- **Kind**: function
- **Signature**:
  ```clojure
  (active-head) → :rf/head-model
  (active-head frame-id) → :rf/head-model
  ```
- **Description**: Resolve the head-model for the currently active route in the named frame. Sub-shape: `[:rf/head]` subscribes to this.

### `head-model->html`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model->html head-model) → inner-head HTML string
  (head-model->html head-model {:wrap? bool}) → inner-head HTML string
  ```
- **Description**: Render a head-model to its HTML representation. `:wrap?` controls whether `<head>` tags are emitted (default: false, so the result can be composed into a larger shell).

### `head-snapshot`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-snapshot frame-id) → {head-id → :rf/head-model}
  ```
- **Description**: Read the per-frame snapshot of last-produced head-models. Returns `{}` for a frame that has never seen a `render-head` call. Useful for tests, introspection, and tools. Re-exported as `rf/head-snapshot`.

## Standard SSR events

| Event | What it does | Spec |
|---|---|---|
| `:rf/server-init` | Per-request server-side initialisation. Reads request cofx; dispatches setup events. `:platforms #{:server}`. | 011 |
| `:rf/hydrate` | Seed the client-side `app-db` from the server-supplied payload. Runs once on client bootstrap. | 011 |

## Standard SSR fx (server-only)

All server-only — `:platforms #{:server}`. These build the response accumulator that the host adapter turns into the HTTP response.

| Fx | Args | Spec |
|---|---|---|
| `[:rf.server/set-status int]` | per `:rf.fx.server/set-status-args` | 011 |
| `[:rf.server/set-header {:name :value}]` | per `:rf.fx.server/set-header-args` | 011 |
| `[:rf.server/append-header {:name :value}]` | per `:rf.fx.server/append-header-args` | 011 |
| `[:rf.server/set-cookie :rf.server/cookie]` | structured cookie map | 011 |
| `[:rf.server/delete-cookie {:name ?:path ?:domain}]` | — | 011 |
| `[:rf.server/redirect {:location ?:status}]` | default `:status 302`; truncates HTML | 011 |

## Standard SSR subs

| Sub | Returns | Spec |
|---|---|---|
| `:rf/response` | The current request's response accumulator (status / headers / cookies / redirect) | 011 |
| `:rf/head` | The head model for the active route (resolved via `(active-head)`) | 011 |
| `:rf/public-error` | The sanitised public-error projection when an error page is being rendered; `nil` otherwise | 011 |

## Standard SSR cofx

| Cofx | Returns | Spec |
|---|---|---|
| `:rf.server/request` | The active HTTP request map. | 011 |

## `:platforms` metadata on `reg-fx`

`reg-fx` accepts a `:platforms` metadata key — a set containing `:server` and / or `:client` — that gates fx execution by active platform. Default `#{:server :client}` (universal) when the key is absent.

```clojure
(rf/reg-fx :my/fx
  ^{:platforms #{:server}}
  (fn [args] ...))
```

Skipped fx emit a `:rf.fx/skipped-on-platform` trace event so debug tools see the gate firing. The cofx side has a mirror trace event, `:rf.cofx/skipped-on-platform`.

Detail in [011 §`:platforms` metadata on `reg-fx`](../../spec/011-SSR.md#platforms-metadata-on-reg-fx).

## Per-frame error-projection policy

A frame opts into SSR error projection via the `:ssr {:public-error-id ... :dev-error-detail? ...}` map on its `reg-frame` / `make-frame` metadata. This is **per-frame metadata**, not a `configure` key — different frames in the same process can carry different projector / dev-detail settings, so the natural lifetime is per-frame.

### `reg-error-projector`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-error-projector id ?metadata projector-fn)
  ```
- **Description**: Register a projector keyed by id. Signature: `(fn [trace-event] :rf/public-error)`. Named per-frame via the frame's `:ssr {:public-error-id ...}` metadata.
- **Example**:
  ```clojure
  (rf/reg-error-projector :app/public-error
    (fn [trace-event]
      {:status  500
       :message "Something went wrong."}))
  ```

The companion `project-error` accessor is rowed above in [§Rendering primitives](#project-error) — same signature; same job. Apply the active projector for the named frame.

Full rationale: [Conventions §Configuration surfaces](../../spec/Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucket 3 and [011 §Server error projection](../../spec/011-SSR.md#server-error-projection).

## Hydration

The server-rendered HTML carries a `__rf_payload` chunk that the client deserialises into `app-db` on bootstrap. The structural-hash from `render-tree-hash` is captured at render time and checked at hydration; mismatch surfaces `:rf.ssr/version-mismatch` or `:rf.ssr/schema-digest-mismatch` rather than silently mounting a broken DOM.

The hydration payload shape lives at [Spec-Schemas §`:rf/hydration-payload`](../../spec/Spec-Schemas.md#rfhydration-payload).

## See also

- [01 — Core](01-core.md) — `reg-head` / `reg-error-projector` rowed in registration.
- [06 — Routing](06-routing.md) — routes opt into head models via `:head` metadata.
- [11 — Instrumentation](11-instrumentation.md) — the SSR-specific trace events live in the error catalogue.
- [Spec 011 — SSR](../../spec/011-SSR.md) — the normative source.
