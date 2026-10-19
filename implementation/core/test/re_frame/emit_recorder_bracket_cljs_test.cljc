(ns re-frame.emit-recorder-bracket-cljs-test
  "`re-frame.test-support/with-emit-recorder!`, the test-tier bracket over the
  implementation-tier `re-frame.error-emit` / `re-frame.event-emit`
  registries, and the tier split it expresses: those raw streams are not in
  the public `register-listener!` vocabulary, and a frame's `:observability`
  sink sees the projected record of the same failure."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            ;; The bracket is a macro in `re-frame.test-support`'s `#?(:clj …)`
            ;; arm: JVM refers it directly, CLJS through `:require-macros`.
            #?(:clj [re-frame.test-support :as rf.test-support
                     :refer [with-emit-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support]))
  #?(:cljs (:require-macros [re-frame.test-support :refer [with-emit-recorder!]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(defn- reg-boom!
  [frame-id event-id]
  (rf/reg-event event-id
                {:frame frame-id}
                (fn [_ _] (throw (ex-info "boom" {:probe event-id})))))

(deftest sink-and-bracket-observe-the-same-failure-in-their-own-shapes
  (testing "a frame's :errors sink and a bracketed error-emit listener both see one
            handler exception: the sink a projected :rf.observe/error, the bracket
            the raw substrate record carrying the host throwable"
    (let [sunk (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [record] (swap! sunk conj record)))
      (rf/make-frame {:id :bracket/paired
                      :observability {:errors [{:sink :test.sinks/sentry}]}})
      (reg-boom! :bracket/paired :paired/boom)
      (with-emit-recorder! [raw]
        (rf/dispatch-sync [:paired/boom] {:frame :bracket/paired})
        (is (= [[:rf.error/handler-exception :bracket/paired true false]]
               (mapv (fn [r] [(:rf.error/id r) (:frame r) (some? (:exception r)) (contains? r :kind)])
                     @raw))
            "raw: error-keyed, carries the throwable, is not a projected record")
        (is (= [[:rf.observe/error :rf.error/handler-exception :bracket/paired]]
               (mapv (juxt :kind :rf.error/id :frame) @sunk))
            "sink: the projected record of the same failure")))))

(deftest the-public-facade-no-longer-offers-the-always-on-streams
  (testing "register-listener! refuses both raw always-on streams, naming the
            two dev streams it accepts"
    (doseq [stream [:errors :events]]
      (let [data (try (rf/register-listener! stream ::probe (fn [_]))
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
                        (ex-data ex)))]
        (is (= {:rf.error/id :rf.error/unknown-listener-stream
                :stream      stream
                :valid       #{:trace :epoch}}
               (select-keys data [:rf.error/id :stream :valid]))
            (str "rf/register-listener! refuses " stream))))))
