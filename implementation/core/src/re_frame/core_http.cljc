(ns re-frame.core-http
  "Public-API wrappers for the optional managed-HTTP artefact (Spec 014).
  Implementation ships in `day8/re-frame2-http` (`re-frame.http-managed`
  ns).

  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private http-artefact
  {:error-keyword :rf.error/http-artefact-missing
   :maven         "day8/re-frame2-http"
   :require-ns    "re-frame.http-managed"})

(defwrapper install-managed-request-stubs!
  "Spec 014 §Testing — install per-call fx-overrides for `:rf.http/managed`
  that synthesise the configured replies. Late-bound via
  `:http/install-managed-request-stubs!`."
  {:hook :http/install-managed-request-stubs! :artefact http-artefact :on-absent :throw}
  ([stubs] :delegate))

(defwrapper uninstall-managed-request-stubs!
  "Spec 014 §Testing — remove the per-call fx-override installed by
  `install-managed-request-stubs!`. Late-bound via
  `:http/uninstall-managed-request-stubs!`."
  {:hook :http/uninstall-managed-request-stubs! :artefact http-artefact :on-absent :throw}
  ([] :delegate))

(defwrapper with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Late-bound via
  `:http/with-managed-request-stubs*`."
  {:hook :http/with-managed-request-stubs* :artefact http-artefact :on-absent :throw}
  ([stubs thunk] :delegate))

;; ---- Spec 014 §Middleware — per-frame request interceptors --------------

(defwrapper reg-http-interceptor
  "Spec 014 §Middleware — register a request-side interceptor on a
  frame's `:rf.http/managed` middleware chain. The interceptor map
  shape is `{:frame <frame-id> :id <kw> :before (fn [ctx] ctx')}`.

  The chain runs in registration order before each request fires; each
  `:before` receives a ctx `{:request :args :frame :event}` and returns
  a (possibly-modified) ctx. The final `:request` is what the transport
  ships. A throw inside any `:before` classifies as
  `:rf.error/http-interceptor-failed`; the request is not dispatched.

  Late-bound via `:http/reg-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/reg-http-interceptor :artefact http-artefact :on-absent :throw}
  ([interceptor] :delegate))

(defwrapper clear-http-interceptor
  "Spec 014 §Middleware — clear an HTTP interceptor by id from a frame's
  chain. Single-arity clears on `:rf/default`; two-arity targets the
  named frame.

  Late-bound via `:http/clear-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/clear-http-interceptor :artefact http-artefact :on-absent :throw}
  ([id]       [:rf/default id])
  ([frame id] :delegate))
