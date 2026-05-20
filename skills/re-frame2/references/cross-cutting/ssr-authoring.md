# SSR authoring (head/meta + hydration checks)

SSR has two halves: a **per-request server frame** that renders body + head to a string, and a **client bootstrap** that hydrates from the server-supplied payload. This leaf is the authoring surface for the **two registration channels SSR apps own**: (a) `reg-head` plus its query helpers, which turn `<title>`/`<meta>`/`<link>`/JSON-LD into pure data-from-app-db; and (b) the `:rf.ssr/check-version` + `:rf.ssr/check-schema-digest` fxs that a `:rf/hydrate` handler dispatches to detect deploy drift between the rendering server and the bundled client. Both ship in `day8/re-frame2-ssr` and route through the optional-artefact late-bind hooks; both are pre-baked into the runtime's default `:rf/hydrate` handler so most apps inherit them for free.

## When to load

Authoring `<title>` / `<meta>` / JSON-LD for an SSR app; writing a custom `:rf/hydrate` handler; debugging `:rf.ssr/version-mismatch` / `:rf.ssr/schema-digest-mismatch` / `:rf.ssr/compatibility-check-skipped` trace events. Load alongside `patterns/boot.md` if the task is whole-app bootstrap.

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
  {:path "/articles/:id"
   :head :head/article})
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

Two input forms (per rf2-69ad2):

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

The runtime ships this handler by default. **Re-register only if you need a non-replace merge policy** — the default is `:replace-app-db` (server-authoritative), and that's spec-locked. The two check fxs ride inside `:fx`:

```clojure
(rf/reg-event-fx :rf/hydrate
  {:doc       "Seed the client-side app-db from the server-supplied payload."
   :platforms #{:client}}                                ;; hydration is client-side only
  (fn [_ [_ {:rf/keys [version frame-id app-db schema-digest]}]]
    {:db app-db                                          ;; replace, not merge
     :fx (cond-> [[:rf.ssr/check-version version]]
           schema-digest (conj [:rf.ssr/check-schema-digest schema-digest]))}))
```

Matches `examples/reagent/ssr/core.cljc` and the reference body in [`spec/011-SSR.md §The :rf/hydrate event`](../../../../spec/011-SSR.md#the-rfhydrate-event). If you override to add client-only transient state, **preserve `[:rf/hydration :server-hash]`** — `verify-hydration!` reads it after first render to drive `:rf.ssr/hydration-mismatch` detection.

## The trace events you'll see

All three are catalogued in [`009 §Error event catalogue`](../../../../spec/009-Instrumentation.md) — single source of truth.

| Operation | When | Severity |
|---|---|---|
| `:rf.ssr/version-mismatch` | payload `:rf/version` ≠ client `:rf2/runtime-version` | `:op-type :error` |
| `:rf.ssr/schema-digest-mismatch` | payload `:rf/schema-digest` ≠ client `:schemas/app-schemas-digest` | `:op-type :error` |
| `:rf.ssr/compatibility-check-skipped` | no hook registered for the relevant probe | `:op-type :warn` |

These are **trace** events — DCE-eligible in CLJS production builds. To ship them off-box, wire `register-error-listener!` per [`production-observability.md`](production-observability.md); the error-emit substrate carries `:rf.ssr/*` records through to production.

## Common gotchas

- **`reg-head` fns subscribe like sub fns.** Inside the fn, `(subscribe ...)` derefs against the static `app-db` value (same path as views via `compute-sub`). No reactive deref.
- **One `:head` per route.** No composition in v1. Routes that want to share head logic reference the same id, or call a shared helper from each head fn.
- **The default `:rf/hydrate` already dispatches the checks.** Re-register only when changing merge policy; the default handler is the recipe.
- **`:platforms #{:client}` gates both fxs.** Server-side dispatches no-op silently; don't sprinkle `:platforms` guards inside your own code.
- **The fxs never throw.** A misregistered hook → `:rf.ssr/compatibility-check-skipped` (warning), not a crash. Read the trace surface to confirm wiring.
- **Head emits as part of the unified render-tree hash in v1.** Head-mismatch surfaces as `:rf.ssr/hydration-mismatch` with `:tags {:failing-id :rf.ssr/head-mismatch}` — not a separate category. Body-mismatch carries `:failing-id :rf/hydrate`.

## Cross-references

- Spec normative: [`spec/011-SSR.md §Head/meta contract`](../../../../spec/011-SSR.md) (`reg-head` / `render-head` / `active-head`); [`§The :rf/hydrate event`](../../../../spec/011-SSR.md) (check fxs).
- API summary: [`spec/API.md §SSR (Spec 011)`](../../../../spec/API.md) — `render-head`, `active-head`, `head-model->html` row; `reg-head` row in §Registration.
- Guide chapter: [`docs/guide/11-server-side.md`](../../../../docs/guide/11-server-side.md) — narrative walkthrough, head/meta and hydration sections.
- Worked example: [`examples/reagent/ssr/core.cljc`](../../../../examples/reagent/ssr/core.cljc) — the reference `:rf/hydrate` body matches this leaf verbatim.
- Production trace fan-out: [`production-observability.md`](production-observability.md) — shipping `:rf.ssr/*` records off-box.
- Trace catalogue: [`spec/009-Instrumentation.md §Error event catalogue`](../../../../spec/009-Instrumentation.md) — `:rf.ssr/*` keywords.

---

*Derived from `re-frame.ssr.head` (rf2-4dra9 PR #724) and `:rf.ssr/check-*` fxs (rf2-69ad2 PR #731) @ main. Verified shapes: `examples/reagent/ssr/core.cljc:112-118` (canonical `:rf/hydrate`); `spec/011-SSR.md §Head/meta contract` (head surface); `spec/Spec-Schemas.md §:rf/head-model` (head-model shape).*
