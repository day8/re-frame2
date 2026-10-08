(ns re-frame.sub-error-query-vector-identity-cljs-test
  "Raw query-vector IDENTITY on the always-on production sub-error egress.

  A subscription query vector is IDENTITY — the sub-cache key (Spec 006), the
  skip-dedup key, the reactive-graph edge endpoint — so it egresses VERBATIM on
  the always-on error `:event` slot (an accepted, documented fail-open). The
  Spec 010 schema-axis backstop in `re-frame.schemas.validate` is a separate
  path, untouched here.

  The hazard: `rf.error-emit/dispatch-on-error!` runs a dispatched event's
  `:event` through the frame's durable app-db elision registry, and a
  coincidental concrete integer app-db path (`[1]`) matches a query-vector
  coordinate, so `[:patient/record \"SECRET\"]` would egress as
  `[:patient/record :rf/redacted]`. The generic walker is correct to match it
  (position-precise app-db elision, pinned by `elision_test.clj`); the error
  CALLER skips elision for a query-vector `:event`. Each case reads both
  production routes, the corpus-wide `register-error-listener!` record and the
  frame-owned `:observability :errors` sink, on a frame whose walker still
  redacts the coordinate.

  Dual-runtime `*_cljs_test.cljc`: `npm run test:cljs` and `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(def ^:private secret "SECRET")
(def ^:private query-v [:patient/record secret])

(defn- emitted
  "On a live `:probe` frame that declares an `:errors` sink and classifies the
  concrete integer app-db path `[1]` sensitive (the EP-0025 commit-plane
  effect), emit one error exactly as a production site does and return
  `{:walker :listener :sink}`: the generic walker's view of `event`, and the
  records each route received."
  [error-kw event event-id attrs]
  (let [listener-seen (atom [])
        sink-seen     (atom [])]
    (rf/register-observability-sink! :px0i9/sink #(swap! sink-seen conj %))
    (rf.error-emit/register-error-listener! :px0i9/listener #(swap! listener-seen conj %))
    (try
      (rf/make-frame {:id :probe
                      :observability
                      {:errors [{:sink :px0i9/sink
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      (rf.frame/swap-runtime-db! :probe
        (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[1]]})))
      (rf.error-emit/dispatch-on-error! error-kw event event-id :probe nil 0 1000 attrs)
      {:walker   (rf.elision/elide-wire-value event {:frame :probe})
       :listener @listener-seen
       :sink     @sink-seen}
      (finally
        (rf/unregister-observability-sink! :px0i9/sink)
        (rf.error-emit/clear-error-listeners!)
        (rf/destroy-frame! :probe)))))

(deftest every-query-vector-category-egresses-raw-on-both-routes
  ;; Enumerated by what `:event` carries, not by a `sub-*` prefix, which would
  ;; miss the `:subscribe` realm of the realm-ambiguous frame-destroyed error.
  ;; The internal raw-event? marker never reaches the sink.
  (doseq [[error-kw attrs] [[:rf.error/sub-exception nil]
                            [:rf.error/sub-input-fn-exception nil]
                            [:rf.error/sub-input-fn-bad-return nil]
                            [:rf.error/no-such-sub nil]
                            [:rf.error/frame-destroyed {:op :subscribe}]]]
    (let [{:keys [walker listener sink]} (emitted error-kw query-v :patient/record attrs)]
      (is (= [[:patient/record :rf/redacted]
              [{:error error-kw :event-id :patient/record :event query-v}]
              [{:kind :rf.observe/error :error error-kw :event query-v}]]
             [walker
              (mapv #(select-keys % [:error :event-id :event]) listener)
              (mapv #(select-keys % [:kind :error :event :re-frame.projection/raw-event?]) sink)])
          (str error-kw)))))

(deftest dispatched-event-errors-keep-their-elision-on-both-routes
  ;; A dispatched event's coordinate is payload, not identity: a handler error
  ;; and the `:dispatch` realm of frame-destroyed both elide it.
  (doseq [[error-kw attrs] [[:rf.error/handler-exception nil]
                            [:rf.error/frame-destroyed {:op :dispatch}]]]
    (let [{:keys [listener sink]} (emitted error-kw [:some/event secret] :some/event attrs)]
      (is (= [[[:some/event :rf/redacted]] [[:some/event :rf/redacted]]]
             [(mapv :event listener) (mapv :event sink)])
          (str error-kw)))))
