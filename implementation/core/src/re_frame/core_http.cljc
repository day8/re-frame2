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

;; rf2-kuky.13 — the whole stub family (`with-request-stubs` and the raw
;; `install-managed-request-stubs!` / `uninstall-managed-request-stubs!` pair)
;; is NOT re-exported from `re-frame.core`: it is test-support infrastructure,
;; not app-facing core surface. Tests reach all three directly through the home
;; namespace `re-frame.http.test-support`, so there is no core wrapper and no
;; late-bind hook for any of them.

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
  chain. EP-0002 context-required frame-local. The public surface is EXACT:

    (clear-http-interceptor id)                  ;; ambient scope
    (clear-http-interceptor id {:frame target})  ;; explicit frame

  The single-arity `(clear-http-interceptor id)` resolves the frame through
  the carried-invariant scope chain (a `with-frame` scope, or the closest
  enclosing frame boundary — a `frame-provider` (SCOPE) or a `frame-root`
  (ENSURE));
  under no scope it raises `:rf.error/no-frame-context` — it does NOT
  synthesise a `:rf/default` target.

  The two-arity opts form names the frame explicitly (the *override*) —
  `target` is a frame-id keyword or a live frame value. The opts map is
  FAIL-CLOSED: it must be EXACTLY `{:frame target}` with a present, non-nil
  target. A missing `:frame`, a nil target, a misspelled/unknown or extra
  key, and a non-map second argument all raise the typed
  `:rf.error/http-bad-interceptor` before any ambient frame is touched
  (rf2-s32bf). Two-scalar frame-first `(frame id)` is NOT a public shape.
  Both arities delegate to the late-bound impl, which performs the frame
  resolution.

  Late-bound via `:http/clear-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/clear-http-interceptor :artefact http-artefact :on-absent :throw
   :where 'rf/clear}
  ([id]      :delegate)
  ([id opts] :delegate))
