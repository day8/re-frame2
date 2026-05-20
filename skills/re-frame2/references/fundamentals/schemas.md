# Schemas

## When to load

Registering a Malli schema for a path in `app-db` with `reg-app-schema`, or attaching the `at-boundary` interceptor to a handler that ingests untrusted data (HTTP responses, websocket messages, query-strings).

## Prerequisite

`reg-app-schema` and the validator surface ship in the optional **`day8/re-frame2-schemas`** artefact (`core.cljc:537-542`). Add it to `deps.edn` and `(:require [re-frame.schemas])` at app boot, otherwise `reg-app-schema` throws `:rf.error/schemas-artefact-missing`.

## Canonical signatures

```clojure
(rf/reg-app-schema path schema)
(rf/reg-app-schema path schema opts)              ;; opts :: {:frame :rf/default}

(rf/app-schema-at      path)                       ;; -> schema or nil
(rf/app-schemas)                                   ;; -> {path schema ...}
(rf/app-schemas-digest)                            ;; -> stable digest string

(rf/set-schema-validator!  validate-fn-or-map)     ;; swap in non-Malli validator
(rf/set-schema-explainer!  explain-fn)
```

Verified: `reg-app-schema` macro at `implementation/core/src/re_frame/core.cljc:527`; the query fns at `:571-597`; the validator seam at `:599-622`.

Registrations are **frame-scoped** — the schema attaches to a path inside one frame's `app-db`. Default frame is `(current-frame)`; pass `{:frame :other}` in `opts` to target another.

## What `:schema` does on a handler

Every `reg-*` macro accepts a `:schema` key in its metadata-map (the canonical name post-rf2-ieu0i):

```clojure
(rf/reg-event-db :flight/set-trip-type
  {:doc    "User changed the trip-type combo."
   :schema [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}
  (fn [db [_ trip-type]] (assoc-in db [:flight :trip-type] trip-type)))
```

In **dev builds** (`re-frame.interop/debug-enabled?` is `true`) the dispatched event vector is validated against the handler's `:schema` before the handler runs. Failure emits `:rf.error/schema-validation-failure` and skips the handler (`spec.cljc:154-238`). In **`:advanced` + `goog.DEBUG=false` production builds** these dev-time call sites are elided.

## `at-boundary` — opt-in production validation

For handlers that **must** validate even in production (HTTP response ingestion, websocket payload, postMessage), attach the boundary interceptor (`:rf.schema/at-boundary`, Var `rf/at-boundary`):

```clojure
(ns my-app.api
  (:require [re-frame.core :as rf]))

(rf/reg-event-fx :api/response-received
  {:schema ApiResponseSchema}
  [rf/at-boundary]
  (fn [_ [_ payload]] ...))
```

The interceptor reuses the handler's existing `:schema` (or the deprecated `:spec` alias) — it does NOT introduce a parallel schema (`spec.cljc:30-31`).

Behaviour matrix:

- **Dev build** — no-op (step-1 validation already runs).
- **Production with `:schema`** — runs the same validation inline.
- **Registration without `:schema`** — rejected at `reg-event-*` time with `:rf.error/at-boundary-missing-schema` (rf2-iftj4). The boundary interceptor is structurally meaningless without a schema; the registrar refuses to install the handler.

## Canonical mini-example

From `examples/reagent/7Guis/flight_booker/flight_booker.cljs`:

```clojure
(def FlightState
  [:map
   [:trip-type   [:enum :one-way :return]]
   [:start-text  :string]
   [:return-text :string]])

(rf/reg-app-schema [:flight] FlightState)

(rf/reg-event-db :flight/set-trip-type
  {:doc    "User changed the trip-type combo."
   :schema [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}
  (fn [db [_ trip-type]] (assoc-in db [:flight :trip-type] trip-type)))
```

The `reg-app-schema` validates `app-db` shape at the `[:flight]` path; the `:schema` on the handler validates the dispatched event vector.

## Swapping the validator

`set-schema-validator!` is the **substitute-Malli seam** (`core.cljc:599-621`). Apps that want to drop the ~24KB gzipped Malli surface in production swap in a hand-written validator at boot:

```clojure
(rf/set-schema-validator!
  {:validate (fn [schema value] (my-validator schema value))
   :explain  (fn [schema value] (my-explainer schema value))})

(rf/set-schema-validator! nil)         ;; disable validation entirely
```

## Common gotchas

- **`reg-app-schema` is a no-op without the schemas artefact.** The macro emits a `late-bind` lookup; without `re-frame.schemas` loaded, the call throws `:rf.error/schemas-artefact-missing` at runtime, not at compile time. Always require `re-frame.schemas` at app boot if you call this.
- **`:schema` on a handler validates the event vector, not the `app-db` value.** The schema's first slot is typically `[:cat [:= :event-id] ...]`. For app-db-shape enforcement, use `reg-app-schema`.
- **Boundary interceptor without `:schema` is rejected at registration.** Adding `[rf/at-boundary]` to a handler that has no `:schema` metadata raises `:rf.error/at-boundary-missing-schema` from `reg-event-*` — the handler is not installed. Either attach a `:schema` to the metadata-map or remove the interceptor.
- **Boundary validation is dev-OR-prod, never both.** Dev-mode step-1 has already validated by the time the boundary interceptor runs; the boundary becomes the validator in production builds when step-1 is elided.
- **Schemas are frame-scoped.** Re-registering a schema on the same `[path]` of the same frame replaces; the same path on a different frame is a separate registration.

## Deeper material

Validation-order spec, per-step recovery, digest algorithm, the schemas artefact's full surface, non-Malli validator authoring, machine snapshot schemas: `SKILL-REDIRECT.md` → **EP — Schemas (010)**.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (macro + validator seam) and `implementation/core/src/re_frame/spec.cljc` (boundary interceptor) @ main `89bd9c3`. Re-verify line numbers after `at-boundary` or `set-schema-validator!` changes (rf2-84e9, rf2-froe).*
