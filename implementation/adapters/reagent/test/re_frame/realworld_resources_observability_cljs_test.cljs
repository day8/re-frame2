(ns re-frame.realworld-resources-observability-cljs-test
  "The example policy routes one already-redacted error and remains closed without a policy."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.observability :as rf.observability]
            [re-frame.test-support :as rf.test-support]
            [realworld-resources.core :as app]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(def ^:private sensitive-token
  "Stands in for the Conduit JWT. The example marks [:auth :token] sensitive at
   frame creation (`:auth/classify-token`), so this string must never reach a
   sink raw."
  "CONDUIT-JWT-SENTINEL-4f21c9")

(defn- classify-token-sensitive!
  [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [[:auth :token]]}))))

(defn- reg-throwing-event! [frame-id]
  (rf/reg-event ::boom
    {:frame frame-id}
    (fn [_ _] (throw (ex-info "kaboom" {:cause :test})))))

(defn- drive-failure! [frame-id]
  (rf/dispatch-sync [::boom {:auth {:token sensitive-token}}] {:frame frame-id}))

(deftest example-policy-routes-one-projected-error-record
  (testing "a handler exception in a frame configured with the EXAMPLE'S OWN
            :observability map delivers exactly one projected
            :rf.observe/error record to a sink registered under the example's
            own id, with the sensitive JWT redacted before the sink sees it"
    (let [seen (atom [])]
      (rf/register-observability-sink! app/error-sink-id
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id ::routed :observability app/observability})
      (classify-token-sensitive! ::routed)
      (reg-throwing-event! ::routed)
      (drive-failure! ::routed)

      (let [r (first @seen)]
        (is (= [[1 :rf.observe/error ::routed :rf.error/handler-exception ::boom] :rf/redacted]
               [[(count @seen) (:kind r) (:frame r) (:rf.error/id r) (:event-id r)]
                (get-in (:event r) [1 :auth :token])]))))))

(deftest a-frame-without-the-policy-routes-nothing
  (testing "the same registered sink and the same throwing handler route
            NOTHING from a frame that declares no :observability policy — which
            is why the example declares its policy unconditionally"
    (let [seen (atom [])]
      (rf/register-observability-sink! app/error-sink-id
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id ::unclassified})
      (classify-token-sensitive! ::unclassified)
      (reg-throwing-event! ::unclassified)
      (drive-failure! ::unclassified)
      (is (empty? @seen)))))

(deftest the-sink-registration-is-gated-on-build-posture
  (testing "install-error-monitor! follows debug-enabled? — a dev build
            registers nothing (and the framework's console fallback takes the
            record instead), while an optimised build registers the sink"
    (let [result (app/install-error-monitor!)]
      (if ^boolean rf.interop/debug-enabled?
        (is (nil? result))
        (is (= app/error-sink-id result))))))
