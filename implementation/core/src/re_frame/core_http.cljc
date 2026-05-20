(ns re-frame.core-http
  "Public-API wrappers for the optional managed-HTTP artefact (Spec 014).
  Implementation ships in `day8/re-frame2-http` (`re-frame.http-managed`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private http-artefact
  {:error-keyword :rf.error/http-artefact-missing
   :maven         "day8/re-frame2-http"
   :require-ns    "re-frame.http-managed"})

;; rf2-lwmgw — the stub family's hooks publish from
;; `re-frame.http-test-support` (single discoverable home for HTTP
;; test surfaces). Tests requiring `re-frame.http-managed` alone (the
;; production surface) will see these defwrappers raise
;; `:rf.error/http-artefact-missing` because the hook is unpublished
;; until `re-frame.http-test-support` is required too. The artefact
;; record points at the prod namespace as the load anchor; the
;; require-ns hint in the error message includes the test-support
;; namespace below.

(def ^:private http-test-support-artefact
  {:error-keyword :rf.error/http-artefact-missing
   :maven         "day8/re-frame2-http"
   :require-ns    "re-frame.http-test-support"})

(defwrapper install-managed-request-stubs!
  "Spec 014 §Testing — install per-call fx-overrides for `:rf.http/managed`
  that synthesise the configured replies. Late-bound via
  `:http/install-managed-request-stubs!` (published from
  `re-frame.http-test-support` per rf2-lwmgw)."
  {:hook :http/install-managed-request-stubs! :artefact http-test-support-artefact :on-absent :throw}
  ([stubs] :delegate))

(defwrapper uninstall-managed-request-stubs!
  "Spec 014 §Testing — remove the per-call fx-override installed by
  `install-managed-request-stubs!`. Late-bound via
  `:http/uninstall-managed-request-stubs!` (published from
  `re-frame.http-test-support` per rf2-lwmgw)."
  {:hook :http/uninstall-managed-request-stubs! :artefact http-test-support-artefact :on-absent :throw}
  ([] :delegate))

(defwrapper with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Late-bound via
  `:http/with-managed-request-stubs*` (published from
  `re-frame.http-test-support` per rf2-lwmgw)."
  {:hook :http/with-managed-request-stubs* :artefact http-test-support-artefact :on-absent :throw}
  ([stubs thunk] :delegate))

;; ---- Spec 014 §Middleware — per-frame request interceptors (rf2-6y3q) -----

(defwrapper reg-http-interceptor
  "Spec 014 §Middleware — register a request-side interceptor on a
  frame's `:rf.http/managed` middleware chain. Signature:
  `(reg-http-interceptor id opts? before)` — `id` is a keyword;
  `before` is `(fn [ctx] ctx')`; `opts` is an optional map carrying
  `:frame` (default `:rf/default`) plus `:rf/registration-metadata`
  keys (`:doc` / `:tags` / `:schema` / `:sensitive?`).

  The chain runs in registration order before each request fires; each
  `:before` receives a ctx `{:request :args :frame :event}` and returns
  a (possibly-modified) ctx. The final `:request` is what the transport
  ships. A throw inside any `:before` classifies as
  `:rf.error/http-interceptor-failed`; the request is not dispatched.

  Late-bound via `:http/reg-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/reg-http-interceptor :artefact http-artefact :on-absent :throw}
  ([id before]      :delegate)
  ([id opts before] :delegate))

(defwrapper clear-http-interceptor
  "Spec 014 §Middleware — clear an HTTP interceptor by id from a frame's
  chain. Single-arity clears on `:rf/default`; two-arity targets the
  named frame.

  Late-bound via `:http/clear-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  {:hook :http/clear-http-interceptor :artefact http-artefact :on-absent :throw}
  ([id]       [:rf/default id])
  ([frame id] :delegate))
