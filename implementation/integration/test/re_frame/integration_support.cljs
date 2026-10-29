(ns re-frame.integration-support
  "Test-only helpers the integration suites share: the harness every
  resource-example suite runs under, a JWT saved in `localStorage`, and the
  `:machine-data` schema-boundary trace collector."
  (:require [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.routing :as rf.routing]
            [re-frame.source-store :as rf.source-store]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- the resource-example harness ------------------------------------------

(defn- clear-trace-bus! []
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!))

(defn resource-example-harness
  "Install the harness a resource-example suite runs under, and return
  `{:trace-bus-fixture f :reset-fixture f :init! f :managed-args atom
  :managed-args-log atom}`; the suite passes the two fixtures, in that order,
  to `use-fixtures :each`, and calls `:init!` itself only to start a fresh
  frame mid-test. Call it at the suite's top level, after the example's
  namespace has loaded.

  The reset fixture clears the registrar kinds `:kinds` names between tests,
  and CLJS cannot re-require the example, so its registrations of those kinds
  are snapshotted here and reinstalled before each test. They also leave the
  shared registrar here, so another suite's reset never clears them and
  mirrors the resulting frameless `:rf.registry/handler-cleared` burst into a
  tooling test's collector; the trace-bus fixture clears listeners and rings
  around each test for the same reason.

  Each test then runs with the routing integration installed, a
  `:rf.http/managed` stub that captures its args (the last into
  `:managed-args`, every one onto `:managed-args-log`) so the test replays the
  reply itself, a no-op `:rf.nav/push-url`, and a url-bound `:rf/default`
  documented `:frame-doc`. The frame is made LAST: it syncs the URL at
  construction, and under Node that URL is \"/\", so a route there plans its
  resources inside `make-frame` — made before the reinstall, it would plan
  against an empty kind and record a route-plan error the suite's own
  navigation never clears.

  `:app-ns` is the example's namespace prefix, which keeps its rows out of
  every other suite's registrar baseline (every co-loaded example registers
  `:rf.route/not-found`). `:async?` builds both fixtures in map form, which
  cljs.test requires of every `:each` fixture before it runs an
  `(async done …)` test."
  [{:keys [kinds app-ns frame-doc async?]}]
  (let [snapshots        (into {} (for [kind kinds
                                        :let [id->desc (rf.registrar/registrations kind)]
                                        :when (seq id->desc)]
                                    [kind id->desc]))
        managed-args     (atom nil)
        managed-args-log (atom [])
        init!            (fn []
                           (reset! managed-args nil)
                           (reset! managed-args-log [])
                           ;; `register!` writes registrar and source store
                           ;; together, so image-loaded frames see the rows.
                           (doseq [[kind id->desc] snapshots
                                   [id desc]       id->desc]
                             (rf.registrar/register! kind id desc))
                           (rf.routing/reset-counters!)
                           (rf.resources.route/install-routing-integration!)
                           (rf.fx/reg-fx :rf.http/managed
                                         (fn [_ctx args]
                                           (reset! managed-args args)
                                           (swap! managed-args-log conj args)
                                           nil))
                           (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                                         (fn [_ _] nil))
                           (rf/make-frame {:id :rf/default :url-bound? true :doc frame-doc}))]
    (swap! rf.source-store/kind->id->ns->descriptor
           (fn [store]
             (reduce-kv (fn [store kind id->desc]
                          (update store kind #(apply dissoc % (keys id->desc))))
                        store
                        snapshots)))
    {:init!             init!
     :managed-args      managed-args
     :managed-args-log  managed-args-log
     :trace-bus-fixture (if async?
                          {:before clear-trace-bus! :after clear-trace-bus!}
                          (fn [f] (clear-trace-bus!) (f) (clear-trace-bus!)))
     :reset-fixture     (rf.test-support/make-reset-runtime-fixture
                          (cond-> {:adapter rf.integration-adapter/adapter
                                   :app-ns  app-ns
                                   :init-fn init!}
                            async? (assoc :async? true)))}))

;; ---- localStorage ------------------------------------------------------------

(defn with-local-storage
  "Run `f` with `globalThis.localStorage` defined by the property `descriptor`,
  then restore whatever was there."
  [descriptor f]
  (let [g     js/globalThis
        prior (js/Object.getOwnPropertyDescriptor g "localStorage")]
    (js/Object.defineProperty g "localStorage" descriptor)
    (try
      (f)
      (finally
        (if prior
          (js/Object.defineProperty g "localStorage" prior)
          (js-delete g "localStorage"))))))

(defn with-saved-jwt
  "Run `f` with a `localStorage` holding `token` under `\"jwtToken\"`."
  [token f]
  (with-local-storage
    #js {:configurable true
         :value        #js {:getItem    (fn [k] (when (= k "jwtToken") token))
                            :setItem    (fn [_ _] nil)
                            :removeItem (fn [_] nil)}}
    f))

;; ---- traces ------------------------------------------------------------------

(defn collect-machine-data-traces!
  "Run `thunk` while collecting `:rf.error/schema-validation-failure` traces
  with `:where :machine-data` (Spec 005 §Schema validation), and return them."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
    (try (thunk)
         (finally (rf/unregister-listener! :trace ::collect)))
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-data (-> % :tags :where)))
             @traces)))
