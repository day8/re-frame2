(ns re-frame.core-http
  "Public-API wrappers for the optional managed-HTTP artefact (Spec 014).
  Implementation ships in `day8/re-frame2-http` (`re-frame.http.managed`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [artefact-info defwrapper]]
                                        :cljs [:refer        [artefact-info]
                                               :refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private http-artefact (artefact-info :http))

;; The stub transports are NOT re-exported from `re-frame.core`: they are test
;; and demo infrastructure, not app-facing core surface. Callers reach them
;; directly through the home namespace `re-frame.http.stub-transport`, so there
;; is no core wrapper and no late-bind hook for any of them.

;; ---- Spec 014 §Middleware — HTTP interceptor definitions ---------------

(defwrapper reg-http-interceptor
  "Spec 014 §Middleware — register the frame-agnostic HTTP interceptor
  definition `id` under the registrar kind `:http-interceptor`.
  Signature: `(reg-http-interceptor id interceptor-map)` — `id` is a
  keyword; `interceptor-map` carries at least one of:

    `:before` — `(fn [ctx] ctx')`            request-side transform
    `:after`  — `(fn [ctx response] response')` response-side transform

  plus any `:rf/registration-metadata` keys (`:doc` / `:tags` / `:schema`).
  A `:frame` in the map raises `:rf.error/http-bad-interceptor`: a frame
  activates the definition by naming `id` in its `:http-interceptors` config
  vector, e.g. `(rf/make-frame {:id :shop/app :http-interceptors [:shop/auth
  :shop/telemetry]})`. A frame that names no chain runs none.

  The `:before` chain runs in the frame's VECTOR ORDER before the request
  fires. Each `:before` receives a ctx `{:request :args :frame :event}`
  and returns a (possibly-modified) ctx. The final `:request` is what
  the transport ships. A throw inside any `:before` classifies as
  `:rf.error/http-interceptor-failed`; the request is not dispatched.

  The `:after` chain runs in REVERSE vector order after the
  response is built and BEFORE `:on-success` / `:on-failure` fire. Each
  `:after` receives `(ctx, response)` — `ctx` is the SAME ctx the
  `:before` chain ended with (enables request-correlated handling) and
  `response` is the canonical reply envelope: `{:status :ok :value v …}`
  or `{:status :error :error f …}`. Returns the
  (possibly-transformed) response.

  Late-bound via `:http/reg-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/reg-http-interceptor :artefact http-artefact :on-absent :throw}
  ([id interceptor-map] :delegate))

(defwrapper clear-http-interceptor
  "Spec 014 §Middleware — remove the HTTP interceptor definition `id`; the
  `:http-interceptor` arm of `(rf/clear :http-interceptor id)`, which takes no
  frame opts. A frame that still names `id` in its `:http-interceptors` fails
  its next request at issue.

  Late-bound via `:http/clear-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/clear-http-interceptor :artefact http-artefact :on-absent :throw
   :where 'rf/clear}
  ([id] :delegate))
