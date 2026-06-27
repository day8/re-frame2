# Schemas

## When to load

Registering a Malli schema for a path in `app-db` with `reg-app-schema`, or attaching the `validate-at-boundary-interceptor` interceptor to a handler that ingests untrusted data (HTTP responses, websocket messages, query-strings).

## Prerequisite

`reg-app-schema` and the validator surface ship in the optional **`day8/re-frame2-schemas`** artefact (the `reg-app-schema` macro + schema-query aliases in `re-frame.core` are late-bound to `re-frame.schemas`). Add it to `deps.edn` and `(:require [re-frame.schemas])` at app boot, otherwise `reg-app-schema` throws `:rf.error/schemas-artefact-missing`.

## Canonical signatures

The `reg-app-schema` / `reg-app-schemas` **registration macros** stay on the `re-frame.core` façade (`rf/`). The **query + validator-install helpers** live on the owning `re-frame.schemas` namespace — `(:require [re-frame.schemas :as schemas])` — they are no longer re-exported from `re-frame.core` (front-porch shrink).

```clojure
(rf/reg-app-schema path {:schema schema})
(rf/reg-app-schema path {:schema schema :frame :rf/default})   ;; frame target rides :frame

(schemas/app-schema-at      path)                  ;; -> schema or nil
(schemas/app-schemas)                              ;; -> {path schema ...}
(schemas/app-schemas-digest)                       ;; -> stable digest string

(schemas/set-schema-validator!  validate-fn-or-nil) ;; swap in non-Malli validator (fn or nil ONLY)
(schemas/set-schema-explainer!  explain-fn)
(schemas/set-schema-fns!  {:validate validate-fn  ;; install the validator/explainer/printer bundle atomically
                           :explain  explain-fn
                           :print    print-fn})
```

> **Bundle maps must NOT go to `set-schema-validator!`.** It accepts a validator **fn or `nil`** only — it does no map-shape inspection. Because maps are invokable in Clojure, passing `{:validate ... :explain ...}` installs the *map itself* as the validator (it is called as `(the-map schema value)`, looking up `schema` as a key — almost always returning `nil`/falsey or a stray value), so validation silently mis-validates instead of running the intended fn. The bundle map belongs to `set-schema-fns!`.

Verified against `implementation/core/src/re_frame/core.cljc`: the `reg-app-schema` macro (and `reg-app-schemas` plural form) are `def`-aliased onto `re-frame.schemas` and stay on the `re-frame.core` façade; the `app-schema-at` / `app-schemas` / `app-schemas-digest` query aliases and the `set-schema-validator!` / `set-schema-fns!` validator seam are reached on `re-frame.schemas` directly (no longer façade-re-exported, per the front-porch shrink).

Registrations are **frame-scoped** — the schema attaches to a path inside one frame's `app-db`. Default frame is `(current-frame-id)`; pass `{:frame :other}` in `opts` to target another.

`reg-app-schema` validates the **app-db partition only**. The framework's runtime-db partition (machine snapshots, route slice, elision declarations — under `:rf.runtime/*`) is governed by its own framework schemas, not yours. This is the payoff of the two-partition frame: your `reg-app-schema` set describes a *pure* application contract with no framework state mixed in. Keep calling it "the app-db schema" — that's the public name; it just happens to cover exactly the partition you own.

## What `:schema` does on a handler

Every `reg-*` macro accepts a `:schema` key in its metadata-map:

```clojure
(rf/reg-event :flight/set-trip-type
  {:doc    "User changed the trip-type combo."
   :schema [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}
  (fn [{:keys [db]} [_ trip-type]] {:db (assoc-in db [:flight :trip-type] trip-type)}))
```

In **dev builds** (`re-frame.interop/debug-enabled?` is `true`) the dispatched event vector is validated against the handler's `:schema` before the handler runs. Failure emits `:rf.error/schema-validation-failure` and skips the handler (sets `:rf/skip-handler?`; see `re-frame.spec`). In **`:advanced` + `goog.DEBUG=false` production builds** these dev-time call sites are elided.

## `validate-at-boundary-interceptor` — opt-in production validation

For handlers that **must** validate even in production (HTTP response ingestion, websocket payload, postMessage), reference the framework-registered boundary interceptor by id — `:rf.schema/at-boundary`. Under EP-0022 a public `:interceptors` chain carries refs, not inline interceptor values, so reference the registered interceptor rather than dropping the `rf/validate-at-boundary-interceptor` Var into the chain:

```clojure
(ns my-app.api
  (:require [re-frame.core :as rf]))

(rf/reg-event :api/response-received
  {:schema ApiResponseSchema
   :interceptors [:rf.schema/at-boundary]}            ;; ref by id, not the inline Var value
  (fn [_ [_ payload]] ...))
```

The interceptor reuses the handler's existing `:schema` metadata — it does NOT introduce a parallel schema. (The `rf/validate-at-boundary-interceptor` Var still exists as the registration-boundary value; the chain references the registered `:rf.schema/at-boundary` interceptor by id.)

Behaviour matrix:

- **Dev build** — no-op (step-1 validation already runs).
- **Production with `:schema`** — runs the same validation inline.
- **Registration without `:schema`** — rejected at `reg-event` time with `:rf.error/at-boundary-missing-schema`. The boundary interceptor is structurally meaningless without a schema; the registrar refuses to install the handler.

## Canonical mini-example

From `examples/core/seven_guis/flight_booker/core.cljs`:

```clojure
(def FlightState
  [:map
   [:trip-type   [:enum :one-way :return]]
   [:start-text  :string]
   [:return-text :string]])

(rf/reg-app-schema [:flight] {:schema FlightState})

(rf/reg-event :flight/set-trip-type
  {:doc    "User changed the trip-type combo."
   :schema [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}
  (fn [{:keys [db]} [_ trip-type]] {:db (assoc-in db [:flight :trip-type] trip-type)}))
```

The `reg-app-schema` validates `app-db` shape at the `[:flight]` path; the `:schema` on the handler validates the dispatched event vector.

## Swapping the validator

`set-schema-validator!` is the **substitute-Malli seam**. Apps that want to drop the ~24KB gzipped Malli surface in production swap in a hand-written validator at boot. `set-schema-validator!` swaps only the validator; `set-schema-fns!` installs the validator/explainer/printer bundle atomically (the honest bundle setter — its name says it sets all three):

```clojure
;; Validator only — explainer/printer untouched.
(schemas/set-schema-validator! (fn [schema value] (my-validator schema value)))

;; All three at once, atomically.
(schemas/set-schema-fns!
  {:validate (fn [schema value] (my-validator schema value))
   :explain  (fn [schema value] (my-explainer schema value))})

(schemas/set-schema-validator! nil)    ;; disable validation entirely
```

## Common gotchas

- **`reg-app-schema` is a no-op without the schemas artefact.** The macro emits a `late-bind` lookup; without `re-frame.schemas` loaded, the call throws `:rf.error/schemas-artefact-missing` at runtime, not at compile time. Always require `re-frame.schemas` at app boot if you call this.
- **`:schema` on a handler validates the event vector, not the `app-db` value.** The schema's first slot is typically `[:cat [:= :event-id] ...]`. For app-db-shape enforcement, use `reg-app-schema`.
- **Boundary interceptor without `:schema` is rejected at registration.** Referencing `:rf.schema/at-boundary` under metadata `:interceptors` on a handler that has no `:schema` metadata raises `:rf.error/at-boundary-missing-schema` from `reg-event` — the handler is not installed. Either attach a `:schema` to the metadata-map or remove the ref.
- **Boundary validation is dev-OR-prod, never both.** Dev-mode step-1 has already validated by the time the boundary interceptor runs; the boundary becomes the validator in production builds when step-1 is elided.
- **Schemas are frame-scoped.** Re-registering a schema on the same `[path]` of the same frame replaces; the same path on a different frame is a separate registration.

## Deeper material

Validation-order spec, per-step recovery, digest algorithm, the schemas artefact's full surface, non-Malli validator authoring, machine snapshot schemas: `SKILL-REDIRECT.md` → **EP — Schemas (010)**.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (macro + validator seam) and `implementation/core/src/re_frame/spec.cljc` (boundary interceptor) @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after `validate-at-boundary-interceptor` or `set-schema-validator!` changes.*
