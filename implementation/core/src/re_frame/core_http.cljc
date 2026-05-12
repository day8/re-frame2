(ns re-frame.core-http
  "Public-API wrappers for the optional managed-HTTP artefact (Spec 014).

  Per rf2-5kpd the `:rf.http/managed` family is registered at
  re-frame.http-managed ns-load time, but the namespace ships in the
  `day8/re-frame2-http` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.http-managed]` — that would pull the
  namespace, the in-flight request registry, the Fetch /
  `java.net.http.HttpClient` transport adapters, the encode / decode
  pipeline, the retry-with-backoff machinery, the eight-category
  `:rf.http/*` failure taxonomy, and every `:rf.http/*` keyword string
  onto every consumer's classpath even when no managed-HTTP request is
  issued.

  The fns in this namespace look the http API up through the late-bind
  hook table at call time, which the http artefact populates from its
  own ns-load.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them) so `core.cljc` is not cluttered with optional-artefact glue.
  The single-import contract is preserved: users continue to write
  `rf/install-managed-request-stubs!` after `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]))

(defn install-managed-request-stubs!
  "Spec 014 §Testing — install per-call fx-overrides for `:rf.http/managed`
  that synthesise the configured replies. Late-bound via
  `:http/install-managed-request-stubs!`."
  [stubs]
  (if-let [f (late-bind/get-fn :http/install-managed-request-stubs!)]
    (f stubs)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'install-managed-request-stubs!
                     :recovery :no-recovery
                     :reason   "rf/install-managed-request-stubs! requires day8/re-frame2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

(defn uninstall-managed-request-stubs!
  "Spec 014 §Testing — remove the per-call fx-override installed by
  `install-managed-request-stubs!`. Late-bound via
  `:http/uninstall-managed-request-stubs!`."
  []
  (if-let [f (late-bind/get-fn :http/uninstall-managed-request-stubs!)]
    (f)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'uninstall-managed-request-stubs!
                     :recovery :no-recovery
                     :reason   "rf/uninstall-managed-request-stubs! requires day8/re-frame2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

(defn with-managed-request-stubs*
  "Function form: install stubs, run thunk, uninstall. Late-bound via
  `:http/with-managed-request-stubs*`."
  [stubs thunk]
  (if-let [f (late-bind/get-fn :http/with-managed-request-stubs*)]
    (f stubs thunk)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'with-managed-request-stubs*
                     :recovery :no-recovery
                     :reason   "rf/with-managed-request-stubs* requires day8/re-frame2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

;; ---- Spec 014 §Middleware — per-frame request interceptors (rf2-6y3q) -----

(defn reg-http-interceptor
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
  [interceptor]
  (if-let [f (late-bind/get-fn :http/reg-http-interceptor)]
    (f interceptor)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:where    'reg-http-interceptor
                     :recovery :no-recovery
                     :reason   "rf/reg-http-interceptor requires day8/re-frame2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."}))))

(defn clear-http-interceptor
  "Spec 014 §Middleware — clear an HTTP interceptor by id from a frame's
  chain. Single-arity clears on `:rf/default`; two-arity targets the
  named frame.

  Late-bound via `:http/clear-http-interceptor`. When the http artefact
  is absent the call raises `:rf.error/http-artefact-missing`."
  ([id] (clear-http-interceptor :rf/default id))
  ([frame id]
   (if-let [f (late-bind/get-fn :http/clear-http-interceptor)]
     (f frame id)
     (throw (ex-info ":rf.error/http-artefact-missing"
                     {:where    'clear-http-interceptor
                      :recovery :no-recovery
                      :reason   "rf/clear-http-interceptor requires day8/re-frame2-http on the classpath; add it to deps and require re-frame.http-managed at app boot."})))))
