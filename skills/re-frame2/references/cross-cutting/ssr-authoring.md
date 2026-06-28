# SSR authoring (head/meta + hydration checks)

SSR has two halves: a **per-request server frame** that renders body + head to a string, and a **client bootstrap** that hydrates from the server-supplied payload. This leaf is the authoring surface for the **two registration channels SSR apps own**: (a) `reg-head` plus its query helpers, which turn `<title>`/`<meta>`/`<link>`/JSON-LD into pure data-from-app-db; and (b) the `:rf.ssr/check-version` + `:rf.ssr/check-schema-digest` fxs that a `:rf/hydrate` handler dispatches to detect deploy drift between the rendering server and the bundled client. Both ship in `day8/re-frame2-ssr` and route through the optional-artefact late-bind hooks; both are pre-baked into the runtime's default `:rf/hydrate` handler so most apps inherit them for free.

## When to load

Authoring `<title>` / `<meta>` / JSON-LD for an SSR app; **extending** the framework's shipped `:rf/hydrate` handler (it ships by default — you rarely write your own; re-register only as documented framework-extension code to change the merge policy); debugging `:rf.ssr/version-mismatch` / `:rf.ssr/schema-digest-mismatch` / `:rf.ssr/compatibility-check-skipped` trace events. Load alongside `patterns/boot.md` if the task is whole-app bootstrap.

## `reg-head` — head model as data-from-app-db

A registry kind (`:head`); same discipline as `reg-sub`. The fn is pure, takes `[db route]`, returns the standard head-model map.

```clojure
(rf/reg-head :head/article
  {:doc "Article-page head — derives title/meta/og from the article."}
  (fn [db {:keys [params]}]
    (let [{:keys [title summary image]} (get-in db [:articles (:id params)])]
      {:title  (str title " — Example")
       :meta   [{:name     "description" :content summary}
                {:property "og:title"    :content title}
                {:property "og:image"    :content image}]
       :link   [{:rel "canonical" :href (route-url :route/article params)}]
       :json-ld [{"@context" "https://schema.org"
                  "@type"    "Article"
                  "headline" title}]})))
```

Wire the route to it via `:head` route metadata — **one `:head` per route in v1**, no parent/child composition:

```clojure
(rf/reg-route :route/article
  {:head :head/article}
  "/articles/:id")
```

Standard head-model keys (per `:rf/head-model` in Spec-Schemas): `:title`, `:meta`, `:link`, `:script`, `:json-ld`, `:html-attrs`, `:body-attrs`. The SSR pipeline emits in canonical order — `<title>` first, then `<meta>` in declaration order, then `<link>`, `<script>`, JSON-LD; `:html-attrs` populate `<html>`, `:body-attrs` populate `<body>`.

**No registered head is fine.** Routes without `:head` get the silent default — `<title>` derived from frame metadata's `:doc`, plus `<meta charset>` and `<meta viewport>`. No warning.

`reg-head` returns its `id` (family-wide reg-* return convention). Query via `(rf/registrations :head)` → `id → metadata`.

## `render-head` — materialise the head model

```clojure
(rf/render-head :head/article {:frame :rf/default
                               :route active-route})           ;; :route optional; defaults to (subscribe [:route])
```

Returns the head-model map. Pure, JVM-runnable. Used by the SSR pipeline (and by tooling that wants to inspect the head without re-rendering the body). Equivalent value-shape to `(compute-head head-id db route)` for any registered `head-id`.

## `active-head` — the current route's head model

```clojure
(rf/active-head)                ;; current frame
(rf/active-head frame-id)       ;; named frame
```

Sugar: looks up the active route's `:head` metadata, resolves to a registered head id, calls `render-head`, returns the model. The `:rf/head` sub returns the same value reactively for views/tools.

## `head-model->html` — explicit serialiser

```clojure
(rf/head-model->html head-model)                       ;; inner-head HTML string (no <head> wrapper)
(rf/head-model->html head-model {:wrap? true})         ;; wraps with <head>...</head>
```

The SSR pipeline calls this internally; reach for it only when emitting custom HTML envelopes (e.g. AMP variants, edge-injected fragments).

## `:rf.ssr/check-version` — payload version vs runtime version

Dispatch from a `:rf/hydrate` handler. Compares the payload's `:rf/version` against the runtime's published `:rf2/runtime-version` hook; emits `:rf.ssr/version-mismatch` (a trace event, `:op-type :error`) on disagreement. The hydrate handler still applies — locked best-effort posture.

Two input forms:

```clojure
;; scalar — the reference handler's shape; client-side actual looked up via :rf2/runtime-version hook
[:rf.ssr/check-version version]

;; explicit map — when the caller already has both sides
[:rf.ssr/check-version {:expected server-value :actual client-value}]
```

`:platforms #{:client}` — server-side dispatches no-op. When no `:rf2/runtime-version` hook is registered, emits `:rf.ssr/compatibility-check-skipped` (warning) and no-ops the comparison. The fx **never throws** — degraded-but-running is the lock.

## `:rf.ssr/check-schema-digest` — server vs client app-schema set

Same shape and gating as `:rf.ssr/check-version`. Compares the payload's `:rf/schema-digest` against a digest of the client's currently-registered `app-schema` set (via the `:schemas/app-schemas-digest` hook); emits `:rf.ssr/schema-digest-mismatch` on disagreement. Catches deploy drift where server and client bundles disagree about the schema corpus.

```clojure
[:rf.ssr/check-schema-digest digest]
[:rf.ssr/check-schema-digest {:expected server-digest :actual client-digest}]
```

Same no-hook → `:rf.ssr/compatibility-check-skipped` posture.

## The canonical `:rf/hydrate` handler

The runtime ships this handler by default in `re-frame.ssr` — **most apps inherit it and never write their own.** `:rf/hydrate` is a reserved `:rf/*` event (Cardinal rule 7; `spec/Conventions.md`), so an ordinary app-level `(rf/reg-event :rf/hydrate …)` is **overriding a framework event** — do it only as a deliberate, documented **framework-extension** when you need a non-replace merge policy (the default is `:replace-frame-state`, server-authoritative, spec-locked).

If you do override, you take over the shipped handler's safety contract and **MUST preserve all of it** — the snippet below is the recipe shape, not a drop-in replacement (it omits guards for brevity). The shipped `re-frame.ssr` handler (`implementation/ssr/src/re_frame/ssr/hydrate.cljc`):

- **fails closed on a malformed payload** — a non-map payload, or a present-but-non-map `:rf/app-db` / `:rf/runtime-db` slice, is rejected, the existing client db is left untouched, and `:rf.error/malformed-hydration-payload` is emitted (never silently installs garbage as the whole app-db);
- **merges the runtime metadata** under `[:rf.runtime/ssr :hydration]` (server-hash + version) onto the payload's runtime-db slice;
- **platform-gates the check fxs at the handler level** so server-side `:rf/hydrate` runs (test harness / isomorphic loopback) don't emit `:rf.fx/skipped-on-platform` per check.

Drop any of these and you reintroduce a fail-open hydration path. Hydration installs a whole **frame-state**, not just an app-db slice: the payload carries `:rf/app-db` (the app-db partition) and an optional `:rf/runtime-db` (the *serializable* runtime-db projection — machine snapshots, route slice, elision declarations, SSR metadata), and the handler installs both partitions in one atomic transition. The framework `:rf/hydrate` handler is framework-authority, so it may emit the reserved `:rf.db/runtime` effect. The two check fxs ride inside `:fx`:

```clojure
(rf/reg-event :rf/hydrate
  {:doc       "Install a coherent frame-state (app-db + serializable runtime-db) from the server payload."
   :platforms #{:client}}                                ;; hydration is client-side only
  (fn [_ [_ {:rf/keys [version frame-id app-db runtime-db render-hash schema-digest]}]]
    {:db            app-db                               ;; app-db partition (replace, not merge)
     :rf.db/runtime (-> runtime-db                       ;; runtime-db partition (replace, not merge)
                        (assoc-in [:rf.runtime/ssr :hydration :server-hash] render-hash)
                        (cond-> version (assoc-in [:rf.runtime/ssr :hydration :version] version)))
     :fx (cond-> [[:rf.ssr/check-version version]]
           schema-digest (conj [:rf.ssr/check-schema-digest schema-digest]))}))
```

Matches `examples/capabilities/ssr/ssr/core.cljc` and the reference body in [`spec/011-SSR.md §The :rf/hydrate event`](../../../../spec/011-SSR.md#the-rfhydrate-event). If you override to add client-only transient state, **preserve `[:rf.runtime/ssr :hydration :server-hash]`** (a runtime-db path) — `verify-hydration!` reads it after first render to drive `:rf.ssr/hydration-mismatch` detection.

## The trace events you'll see

All three are catalogued in [`009 §Error event catalogue`](../../../../spec/009-Instrumentation.md) — single source of truth.

| Operation | When | Severity |
|---|---|---|
| `:rf.ssr/version-mismatch` | payload `:rf/version` ≠ client `:rf2/runtime-version` | `:op-type :error` |
| `:rf.ssr/schema-digest-mismatch` | payload `:rf/schema-digest` ≠ client `:schemas/app-schemas-digest` | `:op-type :error` |
| `:rf.ssr/compatibility-check-skipped` | no hook registered for the relevant probe | `:op-type :warn` |

These `:rf.ssr/*` events are **trace-channel** diagnostics — DCE-eligible in CLJS production builds, JVM-gated on `re-frame.debug`. **Keep them distinct from the promoted `:rf.error/ssr-*` records below:** `:rf.ssr/version-mismatch`, `:rf.ssr/schema-digest-mismatch`, `:rf.ssr/compatibility-check-skipped`, and `:rf.ssr/hydration-mismatch` are compatibility/hydration *diagnostics* that do **not** ride the always-on error-emit substrate; they elide in a production CLJS client build unless the build keeps the trace surface (`:closure-defines {goog.DEBUG true}`), and none has been promoted to a catalogued `:rf.error/*` as of EP-0008. So do not wire `register-listener!` `:errors` expecting *these* to arrive — they won't. To track this drift in production, instrument it deliberately: detect the condition in your own app code (the strict-mode hydration hook, or your `:rf/hydrate` extension) and dispatch an app event that ships through the production observability surfaces (`register-listener!` `:events` / `:errors` per [`production-observability.md`](production-observability.md)). The default `:rf2/runtime-version` / schema-digest checks remain dev-and-server diagnostics on the trace channel.

> **The SSR render/streaming/projection failures DO ride the always-on axis (EP-0008).** Separately from the `:rf.ssr/*` compatibility diagnostics above, the production-reachable SSR error categories — `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, and `:rf.error/ssr-ring-error-view-failed` — ride the **always-on error-emit axis** (surface #4). On a long-lived JVM SSR host, an off-box shipper (Sentry / Datadog) registered via `register-listener!` `:errors` receives these structured records **even under `-Dre-frame.debug=false`**, where the dev trace surface is elided — the off-box record, not the wire response, is the telemetry. These are **non-event** records: they carry no `:event` / `:event-id` (some carry only `:frame` + `:exception` + category-specific slots), so a listener must branch on `(:error record)` and not assume the per-event shape. So you need not route SSR production drift only through the `:rf/public-error` projector or the dev trace — the always-on records ARE the production egress for these categories. (The recoverable-degradation and post-commit members are non-projecting: their riding the axis changes what shippers see, never the wire status.) See [`production-observability.md` §The promoted-SSR records](production-observability.md) and [`009 §What IS available in production`](../../../../spec/009-Instrumentation.md#what-is-available-in-production).

## Streaming SSR (advanced — most apps skip this)

When the page shell + header should render immediately while slow subtrees stream in as their data resolves, mark each streamed subtree with the `:rf/suspense-boundary` hiccup marker. The server emits the shell plus per-subtree fallback hiccup first, then streams each subtree's real content as its fetch resolves; the client hydrates per-subtree as each chunk arrives (interleaved, not all-at-once). Inline-fallback failure semantics apply per boundary.

```clojure
[:rf/suspense-boundary {:fallback [card-skeleton]}
 [slow-card card-id]]                         ;; renders the fallback until the card's data resolves, then streams in
```

Worked example: `examples/capabilities/ssr/ssr_streaming/core.cljc` (a three-slow-card dashboard) — read it for the `:rf/suspense-boundary` marker, per-card fallback hiccup, inline-fallback failure semantics, and interleaved per-subtree hydration. Spec: [`spec/011-SSR.md §Streaming`](../../../../spec/011-SSR.md#streaming-ssr). For the parallel data-fetch fan-out an SSR request needs, see [`../../patterns/ssr-loaders.md`](../../patterns/ssr-loaders.md). Low priority — skip unless the task is explicitly streaming SSR.

## Common gotchas

- **`reg-head` fns subscribe like sub fns.** Inside the fn, `(subscribe ...)` derefs against the static `app-db` value (same path as views via `compute-sub`). No reactive deref.
- **One `:head` per route.** No composition in v1. Routes that want to share head logic reference the same id, or call a shared helper from each head fn.
- **The default `:rf/hydrate` already dispatches the checks.** It ships in `re-frame.ssr`; most apps never write their own. Re-registering replaces a reserved framework event — do it only as documented framework-extension code to change the merge policy, and preserve the malformed-payload fail-closed guard, the runtime-metadata merge, and the handler-level platform gate.
- **`:platforms #{:client}` gates both fxs.** Server-side dispatches no-op silently; don't sprinkle `:platforms` guards inside your own code.
- **The fxs never throw.** A misregistered hook → `:rf.ssr/compatibility-check-skipped` (warning), not a crash. Read the trace surface to confirm wiring.
- **Head emits as part of the unified render-tree hash in v1.** Head-mismatch surfaces as `:rf.ssr/hydration-mismatch` with `:tags {:failing-id :rf.ssr/head-mismatch}` — not a separate category. Body-mismatch carries `:failing-id :rf/hydrate`.

## Cross-references

- SSR patterns: [`../../patterns/form-action.md`](../../patterns/form-action.md) (handling an HTML form POST — progressive enhancement, CSRF, multipart); [`../../patterns/ssr-loaders.md`](../../patterns/ssr-loaders.md) (parallel data fetch via `:spawn-all` before render). A page typically uses Loaders for the GET render and FormAction for subsequent POSTs.
- Spec normative: [`spec/011-SSR.md §Head/meta contract`](../../../../spec/011-SSR.md) (`reg-head` / `render-head` / `active-head`); [`§The :rf/hydrate event`](../../../../spec/011-SSR.md) (check fxs).
- API summary: [`spec/API.md §SSR (Spec 011)`](../../../../spec/API.md) — `render-head`, `active-head`, `head-model->html` row; `reg-head` row in §Registration.
- Guide concept: [`docs/ssr/concepts.md`](../../../../docs/ssr/concepts.md) — narrative walkthrough, head/meta and hydration sections.
- Worked example: [`examples/capabilities/ssr/ssr/core.cljc`](../../../../examples/capabilities/ssr/ssr/core.cljc) — the reference `:rf/hydrate` body matches this leaf verbatim.
- Production observability: [`production-observability.md`](production-observability.md) — the always-on event/error-emit listeners. Note `:rf.ssr/*` diagnostics do NOT ride the error-emit substrate (they elide with the trace channel unless separately promoted); instrument SSR drift via your own app event through those listeners, or the `:rf/public-error` projector server-side.
- Trace catalogue: [`spec/009-Instrumentation.md §Error event catalogue`](../../../../spec/009-Instrumentation.md) — `:rf.ssr/*` keywords.

---

*Derived from `re-frame.ssr.head` and `:rf.ssr/check-*` fxs @ main. Verified shapes: `examples/capabilities/ssr/ssr/core.cljc:112-118` (canonical `:rf/hydrate`); `spec/011-SSR.md §Head/meta contract` (head surface); `spec/Spec-Schemas.md §:rf/head-model` (head-model shape).*
