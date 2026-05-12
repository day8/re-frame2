(ns re-frame.core-routing
  "Public-API wrappers for the optional routing artefact (Spec 012).

  Per rf2-k682 the routing implementation ships in the
  `day8/re-frame2-routing` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.routing]` — that would pull the
  route-rank / pattern-compile / nav-token machinery, the `:rf/route`
  reg-sub family, and every `:rf.route/*` / `:rf.nav/*` keyword string
  onto every consumer's classpath even when no route is registered.

  The fns in this namespace look the routing API up through the
  late-bind hook table at call time, which the routing artefact
  populates from its own ns-load.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them as `rf/reg-route`, `rf/match-url`, `rf/route-url`) so `core.cljc`
  is not cluttered with optional-artefact glue. The single-import
  contract is preserved: users continue to write `rf/reg-route` after
  `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]))

(defn match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Match a URL against
  registered routes; return `{:route-id :params :query
  :validation-failed?}` for the first match, or `nil` if no route
  matches. Late-bound via :routing/match-url."
  [url]
  (if-let [f (late-bind/get-fn :routing/match-url)]
    (f url)
    (throw (ex-info ":rf.error/routing-artefact-missing"
                    {:where    'match-url
                     :recovery :no-recovery
                     :reason   "rf/match-url requires day8/re-frame2-routing on the classpath; add it to deps and require re-frame.routing at app boot."}))))

(defn route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Inverse of `match-url` —
  build a URL string from a route-id + path-params (and optional
  query-params). Late-bound via :routing/route-url."
  ([route-id path-params] (route-url route-id path-params {}))
  ([route-id path-params query-params]
   (if-let [f (late-bind/get-fn :routing/route-url)]
     (f route-id path-params query-params)
     (throw (ex-info ":rf.error/routing-artefact-missing"
                     {:where    'route-url
                      :route-id route-id
                      :recovery :no-recovery
                      :reason   "rf/route-url requires day8/re-frame2-routing on the classpath; add it to deps and require re-frame.routing at app boot."})))))

(defn reg-route
  "Fn-form delegate that performs the late-bind lookup for `reg-route`.
  The `re-frame.core/reg-route` macro (JVM) and the CLJS `def`-alias
  both route here, so the late-bind logic and the missing-artefact
  error message live in one place."
  [id metadata]
  (if-let [f (late-bind/get-fn :routing/reg-route)]
    (f id metadata)
    (throw (ex-info ":rf.error/routing-artefact-missing"
                    {:where    'reg-route
                     :route-id id
                     :recovery :no-recovery
                     :reason   "rf/reg-route requires day8/re-frame2-routing on the classpath; add it to deps and require re-frame.routing at app boot."}))))
