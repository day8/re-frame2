(ns re-frame.core-http
  "Public-API wrappers for the optional managed-HTTP artefact (Spec 014).
  Implementation ships in `day8/re-frame2-http` (`re-frame.http.managed`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private http-artefact
  {:error-keyword :rf.error/http-artefact-missing
   :maven         "day8/re-frame2-http"
   :require-ns    "re-frame.http.managed"})

;; rf2-lwmgw — the stub family's hooks publish from
;; `re-frame.http.test-support` (single discoverable home for HTTP
;; test surfaces). Tests requiring `re-frame.http.managed` alone (the
;; production surface) will see these defwrappers raise
;; `:rf.error/http-artefact-missing` because the hook is unpublished
;; until `re-frame.http.test-support` is required too. The artefact
;; record points at the prod namespace as the load anchor; the
;; require-ns hint in the error message includes the test-support
;; namespace below.

(def ^:private http-test-support-artefact
  {:error-keyword :rf.error/http-artefact-missing
   :maven         "day8/re-frame2-http"
   :require-ns    "re-frame.http.test-support"})

;; The raw `install-managed-request-stubs!` / `uninstall-managed-request-stubs!`
;; pair is NOT re-exported from `re-frame.core` (rf2-ntwwyt — test-support
;; infrastructure, not app-facing core surface). Tests reach it directly through
;; the home namespace `re-frame.http.test-support`, so there is no core wrapper
;; (and no `:http/install-managed-request-stubs!` / `:http/uninstall-managed-
;; request-stubs!` late-bind hook) for it. The ergonomic `with-managed-request-
;; stubs` macro keeps its `with-managed-request-stubs*` façade plumbing below.

(defwrapper with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Late-bound via
  `:http/with-managed-request-stubs*` (published from
  `re-frame.http.test-support` per rf2-lwmgw)."
  {:hook :http/with-managed-request-stubs* :artefact http-test-support-artefact :on-absent :throw}
  ([stubs thunk] :delegate))

;; ---- Spec 014 §Middleware — per-frame request interceptors (rf2-6y3q) -----

(defwrapper reg-http-interceptor
  "Spec 014 §Middleware (rf2-uheqq shape iii) — register an HTTP
  interceptor on a frame's `:rf.http/managed` middleware chain.
  Signature: `(reg-http-interceptor id interceptor-map)` — `id` is a
  keyword; `interceptor-map` carries at least one of:

    `:before` — `(fn [ctx] ctx')`            request-side transform
    `:after`  — `(fn [ctx response] response')` response-side transform

  plus an optional `:frame` (the EP-0002 *override*) and any
  `:rf/registration-metadata` keys (`:doc` / `:tags` / `:schema` /
  `:sensitive?`). Absent an explicit `:frame`, the carried-invariant
  scope chain resolves the target; under no scope the call raises
  `:rf.error/no-frame-context` (no `:rf/default` is synthesised). The
  two slots mirror the event-interceptor
  `{:id :before :after}` shape (Spec 002).

  The `:before` chain runs in REGISTRATION ORDER before the request
  fires. Each `:before` receives a ctx `{:request :args :frame :event}`
  and returns a (possibly-modified) ctx. The final `:request` is what
  the transport ships. A throw inside any `:before` classifies as
  `:rf.error/http-interceptor-failed`; the request is not dispatched.

  The `:after` chain runs in REVERSE REGISTRATION ORDER after the
  response is built and BEFORE `:on-success` / `:on-failure` fire. Each
  `:after` receives `(ctx, response)` — `ctx` is the SAME ctx the
  `:before` chain ended with (enables request-correlated handling) and
  `response` is the canonical reply envelope: `{:status :ok :value v …}`
  or `{:status :error :error f …}` (rf2-ibksxg). Returns the
  (possibly-transformed) response.

  Late-bound via `:http/reg-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/reg-http-interceptor :artefact http-artefact :on-absent :throw}
  ([id interceptor-map] :delegate))

(defwrapper clear-http-interceptor
  "Spec 014 §Middleware — clear an HTTP interceptor by id from a frame's
  chain. EP-0002 context-required frame-local: the single-arity
  `(clear-http-interceptor id)` resolves the frame through the
  carried-invariant scope chain (a `with-frame` / frame-provider scope).
  Pass the public opts form `(clear-http-interceptor id {:frame target})`
  to name the frame explicitly (the *override*); `target` is a frame-id
  keyword or a live frame value. Per rf2-f28bno the 2-arity is
  SHAPE-DISCRIMINATED on the second arg (mirroring `reg-http-interceptor`'s
  `:frame` opt): an opts map ⇒ the public form; a bare frame target ⇒ the
  internal frame-first plumbing. Under no scope and no explicit frame the
  call raises `:rf.error/no-frame-context` — it does NOT synthesise a
  `:rf/default` target. Both arities delegate to the late-bound impl,
  which performs the frame resolution.

  Late-bound via `:http/clear-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/clear-http-interceptor :artefact http-artefact :on-absent :throw}
  ([id]          :delegate)
  ([id-or-frame b] :delegate))
