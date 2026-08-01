# Schemas

## When to load

Registering a Malli schema for a path in `app-db` with `reg-app-schema`, or attaching the `validate-at-boundary-interceptor` interceptor to a handler that ingests untrusted data (HTTP responses, websocket messages, query-strings).

## Prerequisite

`reg-app-schema` and the validator surface ship in the optional **`day8/re-frame2-schemas`** artefact (the `reg-app-schema` macro + schema-query aliases in `re-frame.core` are late-bound to `re-frame.schemas`). Add it to `deps.edn` and `(:require [re-frame.schemas])` at app boot, otherwise `reg-app-schema` throws `:rf.error/schemas-artefact-missing`.

## Canonical signatures

The `reg-app-schema` / `reg-app-schemas` **registration macros** stay on the `re-frame.core` façade (`rf/`). The **query + validator-install helpers** live on the owning `re-frame.schemas` namespace — `(:require [re-frame.schemas :as schemas])` — they are not on the `re-frame.core` façade.

```clojure
(rf/reg-app-schema path schema)                              ;; schema is the positional value slot
(rf/reg-app-schema path {:frame :rf/default} schema)          ;; 3-slot — frame target rides the middle metadata map

(schemas/app-schema-at      path)                  ;; -> schema or nil
(schemas/app-schemas)                              ;; -> {path schema ...}
(schemas/app-schemas-digest)                       ;; -> stable digest string

(schemas/set-schema-validator!  validate-fn-or-nil) ;; swap in non-Malli validator (fn or nil ONLY)
(schemas/set-schema-explainer!  explain-fn)
(schemas/set-schema-fns!  {:validate validate-fn  ;; install the validator/explainer/printer bundle atomically
                           :explain  explain-fn
                           :print    print-fn})
```

> **Bundle maps must NOT go to `set-schema-validator!`.** It accepts a validator **fn or `nil`** only — no map-shape inspection. Because maps are invokable in Clojure, passing `{:validate ... :explain ...}` installs the *map itself* as the validator (called as `(the-map schema value)`, looking up `schema` as a key — almost always `nil`/falsey), so validation silently mis-validates. The bundle map belongs to `set-schema-fns!`.

Verified against `implementation/core/src/re_frame/core.cljc`: the `reg-app-schema` macro (and `reg-app-schemas` plural form) are `def`-aliased onto `re-frame.schemas` and stay on the `re-frame.core` façade; the `app-schema-at` / `app-schemas` / `app-schemas-digest` query aliases and the `set-schema-validator!` / `set-schema-fns!` validator seam are reached on `re-frame.schemas` directly (not on the `re-frame.core` façade).

Registrations are **frame-scoped** — the schema attaches to a path inside one frame's `app-db`. Default frame is `(current-frame-id)`; pass `{:frame :other}` in `opts` to target another.

`reg-app-schema` validates the **app-db partition only**; the runtime-db partition (machine snapshots, route slice, elision declarations under `:rf.runtime/*`) is governed by its own framework schemas. So your `reg-app-schema` set describes a *pure* application contract with no framework state mixed in — the payoff of the two-partition frame. Keep calling it "the app-db schema"; it covers exactly the partition you own.

## `reg-app-schema` is a development-build assertion

This is the single most important thing to know about the surface, and it decides which tool a real trust boundary needs.

A production build — `:advanced` with `goog.DEBUG` false, or `-Dre-frame.debug=false` on the JVM — still **registers** every `reg-app-schema` schema, so tools and agents can introspect them, but never **checks a candidate against one**. The candidate validator is elided along with the other ordinary registration diagnostics — a handler's event-vector `:schema` (step 1) and a `reg-fx` args `:schema` (step 5) go the same way — so a candidate that violates a registered app-db schema installs silently: no rejection, no rollback, no diagnostic, because the diagnostic is part of what got elided. Your app-db schemas do not run in production builds.

Read that as a claim about **ordinary registration diagnostics**, not about schema validation as such. Several checks are ungated and run in the release bundle exactly as they do in dev — the boundary interceptor below is the one you reach for, and the [full survivor list](#what-survives-is-settled-by-what-the-check-is-for-not-by-who-declared-it) is a few paragraphs down. The practical test is the one the framework applies to itself: a check that exists to catch the programmer's own mistake may be elided, while a check the framework relies on to keep a promise of its own — refusing malformed input at an untrusted ingress, refusing a corrupt value into a durable record — runs unconditionally.

That is deliberate and settled (Spec 010 §Production builds; ruled 2026-07-27) — production trusts the programmer, and the elision is what keeps the reason strings, keywords and validator derefs out of the shipped bundle. It is not a gap waiting to be closed, so don't reach for a workaround that turns it back on.

What it means for how you write code:

- **A registered schema is a tripwire that catches *you*, during development.** It is worth having on every boundary path for exactly that reason, and the same is true of handler `:schema` metadata.
- **It is not a guard on the deployed bundle.** Keep the real invariant in the handler — code that runs unconditionally.
- **Where untrusted data must be rejected in production too, use the boundary interceptor** (below). It survives the production gate, and it is the survivor an application author actually reaches for.

### What survives is settled by what the check is for, not by who declared it

That rule is normative (Spec 000 C-000.35). An ordinary registration diagnostic elides, because a release build trusts the programmer. A check the framework relies on to keep a promise of its own does not, and a promise kept only in dev is not a promise — so the boundary interceptor is not alone. These also run in every build: a declared route's `:params` / `:query` shape (gated on the schemas artefact and a declared schema, not on `debug-enabled?`), a recordable coeffect's `:schema` (an out-of-contract durable value is corrupt causal state, so it *throws* rather than eliding), the reserved `:rf.server/*` response effects' checks on their own arguments, and a Malli schema handed to Managed HTTP's `:decode` — the last being an argument to the framework's own parse rather than a diagnostic layered over it.

**Do not reach for "who wrote the schema" as the shortcut — it gets four of those five wrong.** You declare all but one of them yourself: the boundary interceptor validates against your handler's own `:schema`, the route check against your `reg-route` `:params` / `:query`, the decode check against the schema you handed that request, the recordable coeffect against your own `reg-cofx` `:schema`. Only the reserved `:rf.server/*` effects check against a type the framework publishes. The recordable coeffect makes the point sharpest: you declare that `:schema` exactly as you declare a handler's, and one survives while the other does not. What differs is what the framework does with it — the recordable value folds into the epoch ledger and replays verbatim, so your schema is applied at that durable boundary to keep the framework's own promise that a record replays to the same state. Ask what breaks if the check goes, not who typed it.

None of that softens the section above: a `reg-app-schema` registration and a handler's `:schema` metadata still do not run in production.

The improver skill's [`schemaless-events.md`](../../../re-frame2-improver/references/schemaless-events.md) is the audit-side counterpart: a handler ingesting untrusted input while carrying only `:schema` / `reg-app-schema` is a finding, not a pass.

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

Whichever arm does the checking, the *rejection* is identical: `:rf/skip-handler?` is set, the handler never runs, and the payload never reaches `app-db`. It also **reports** in both builds — one always-on `:rf.error/schema-validation-failure` record (`:source :boundary`, `:where :event`) plus `:outcome :rejected` on the event record. The dev and production routes share a single emit site, so a rejection can never produce two records. What a production record drops is the payload — no event vector, no offending value, no Malli explanation — because a boundary payload is attacker-controlled by definition. Surviving and reporting are separate claims about a check; on this one you get both.

## Canonical mini-example

From `examples/core/seven_guis/flight_booker/core.cljs`:

```clojure
(def FlightState
  [:map
   [:trip-type   [:enum :one-way :return]]
   [:start-text  :string]
   [:return-text :string]])

(rf/reg-app-schema [:flight] FlightState)               ;; dev-only

(rf/reg-event :flight/set-trip-type
  {:doc    "User changed the trip-type combo."
   :schema [:cat [:= :flight/set-trip-type] [:enum :one-way :return]]}   ;; dev-only
  (fn [{:keys [db]} [_ trip-type]] {:db (assoc-in db [:flight :trip-type] trip-type)}))
```

The `reg-app-schema` validates `app-db` shape at the `[:flight]` path; the `:schema` on the handler validates the dispatched event vector. Both are dev-build assertions, which is the right choice here — the trip-type combo is the app's own UI, not a trust boundary. A handler ingesting a server payload would additionally reference `:rf.schema/at-boundary`.

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
- **`:schema` on a handler validates the event vector, not the `app-db` value.** The schema's first slot is typically `[:cat [:= :event-id] ...]`. For app-db-shape checking in dev, use `reg-app-schema`.
- **Neither `reg-app-schema` nor a bare `:schema` reaches production.** Both are elided from a production build, so "I registered a schema for that path" is not an answer to "what stops bad data getting in?" — see §`reg-app-schema` is a development-build assertion. The production answer is `:rf.schema/at-boundary`, an always-on validator in the handler body, or a decoding gate such as Managed HTTP's `:decode`.
- **Boundary interceptor without `:schema` is rejected at registration.** Referencing `:rf.schema/at-boundary` under metadata `:interceptors` on a handler that has no `:schema` metadata raises `:rf.error/at-boundary-missing-schema` from `reg-event` — the handler is not installed. Either attach a `:schema` to the metadata-map or remove the ref.
- **Boundary validation is dev-OR-prod, never both.** Dev-mode step-1 has already validated by the time the boundary interceptor runs; the boundary becomes the validator in production builds when step-1 is elided.
- **Schemas are frame-scoped.** Re-registering a schema on the same `[path]` of the same frame replaces; the same path on a different frame is a separate registration.

## Deeper material

Validation-order spec, per-step recovery, digest algorithm, the schemas artefact's full surface, non-Malli validator authoring, machine snapshot schemas: `SKILL-REDIRECT.md` → **EP — Schemas (010)**.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (macro + validator seam) and `implementation/core/src/re_frame/spec.cljc` (boundary interceptor) @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after `validate-at-boundary-interceptor` or `set-schema-validator!` changes.*
