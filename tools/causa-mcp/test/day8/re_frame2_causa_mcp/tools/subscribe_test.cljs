(ns day8.re-frame2-causa-mcp.tools.subscribe-test
  "Unit tests for the T-Stream-1 tool `subscribe` (rf2-8xzoe.26).

  Pure-state tests pin the pieces that don't need the streaming loop:

    - `topic->filter` — projection table per topic.
    - `project-error-topic` — issue-tier post-filter.
    - `merge-tick` — per-tick state-fold contract.
    - `final-summary` — terminal envelope shape + cumulative indicators.
    - `progress-payload` — per-tick `notifications/progress` shape.
    - eval-form composition (drain/subscribe/unsubscribe).
    - pre-flight validation — unknown topic short-circuits."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.subscribe :as sub]))

(defn- payload-of [^js result]
  (-> result (j/get :content) (aget 0) (j/get :text) edn/read-string))

(deftest public-surface-resolvable
  (is (fn? sub/subscribe-tool))
  (is (fn? sub/drain-form))
  (is (fn? sub/subscribe-form))
  (is (fn? sub/unsubscribe-form))
  (is (fn? sub/topic->filter))
  (is (fn? sub/project-error-topic))
  (is (fn? sub/merge-tick))
  (is (fn? sub/final-summary))
  (is (fn? sub/progress-payload))
  (is (= "subscribe" (:name sub/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "subscribe"))))

;; --- topic->filter ----------------------------------------------------------

(deftest topic-projection-trace-is-passthrough
  (is (= {} (sub/topic->filter :trace nil nil 0)))
  (is (= {:op-type :event/dispatched}
         (sub/topic->filter :trace {:op-type :event/dispatched} nil 0))))

(deftest topic-projection-epoch-stamps-op-type
  (is (= {:op-type :epoch/closed}
         (sub/topic->filter :epoch nil nil 0))))

(deftest topic-projection-fx-stamps-op-type
  (is (= {:op-type :fx/run}
         (sub/topic->filter :fx nil nil 0))))

(deftest topic-projection-frame-and-since-ms-stamp
  (is (= {:frame :app :since-ms 12345}
         (sub/topic->filter :trace nil :app 12345))))

;; --- project-error-topic ----------------------------------------------------

(deftest project-error-topic-filters-to-issue-tier
  (let [events [{:op-type :event/dispatched}
                {:op-type :error}
                {:op-type :rf.schema/violation}
                {:op-type :sub/updated}]]
    (is (= 2 (count (sub/project-error-topic :error events))))
    (is (= events (sub/project-error-topic :trace events))
        "non-error topics pass through unchanged")))

;; --- merge-tick -------------------------------------------------------------

(deftest merge-tick-zero-events-no-op
  (is (= sub/initial-state
         (sub/merge-tick sub/initial-state {:n 0 :dropped 0 :elided 0}))))

(deftest merge-tick-bumps-counters
  (let [s' (sub/merge-tick sub/initial-state {:n 3 :dropped 1 :elided 2})]
    (is (= 1 (:tick s')))
    (is (= 3 (:delivered s')))
    (is (= 1 (:dropped-sensitive s')))
    (is (= 2 (:elided-large s')))))

(deftest merge-tick-accumulates-across-ticks
  (let [s1 (sub/merge-tick sub/initial-state {:n 2 :dropped 1 :elided 0})
        s2 (sub/merge-tick s1 {:n 3 :dropped 0 :elided 4})]
    (is (= 2 (:tick s2)))
    (is (= 5 (:delivered s2)))
    (is (= 1 (:dropped-sensitive s2)))
    (is (= 4 (:elided-large s2)))))

;; --- final-summary ----------------------------------------------------------

(deftest final-summary-includes-indicators-when-non-zero
  (let [^js result (sub/final-summary
                     {:sub-id "s-1" :topic :trace
                      :state  {:tick 5 :delivered 17 :dropped-sensitive 2
                               :elided-large 3 :since-ms 0}
                      :reason :max-events-reached})
        payload    (payload-of result)]
    (is (true? (:ok? payload)))
    (is (= "s-1" (:sub-id payload)))
    (is (= :trace (:topic payload)))
    (is (= 17 (:delivered payload)))
    (is (= 5 (:ticks payload)))
    (is (= :max-events-reached (:reason payload)))
    (is (= 2 (:dropped-sensitive payload)))
    (is (= 3 (:elided-large payload)))))

(deftest final-summary-omits-zero-indicators
  (let [^js result (sub/final-summary
                     {:sub-id "s-2" :topic :epoch
                      :state  {:tick 0 :delivered 0 :dropped-sensitive 0
                               :elided-large 0 :since-ms 0}
                      :reason :aborted})
        payload    (payload-of result)]
    (is (nil? (:dropped-sensitive payload)))
    (is (nil? (:elided-large payload)))))

;; --- progress-payload -------------------------------------------------------

(deftest progress-payload-shape
  (let [p (sub/progress-payload "tk-1" 5 "edn-string")]
    (is (= "tk-1" (j/get p :progressToken)))
    (is (= 5     (j/get p :progress)))
    (is (= "edn-string" (j/get p :message)))))

;; --- eval-form composition --------------------------------------------------

(deftest drain-form-routes-runtime-ns-with-origin
  (let [form (sub/drain-form {:frame :app :since-ms 100})]
    (is (.includes form "day8.re-frame2-causa.runtime/get-trace-buffer"))
    (is (.includes form ":causa-mcp"))))

(deftest subscribe-form-routes-runtime-ns-with-origin
  (let [form (sub/subscribe-form :trace nil)]
    (is (.includes form "day8.re-frame2-causa.runtime/subscribe!"))
    (is (.includes form ":topic :trace"))
    (is (.includes form ":causa-mcp"))))

(deftest unsubscribe-form-routes-runtime-ns
  (let [form (sub/unsubscribe-form "sub-1")]
    (is (.includes form "day8.re-frame2-causa.runtime/unsubscribe!"))
    (is (.includes form "\"sub-1\""))))

;; --- handler pre-flight -----------------------------------------------------

(deftest unknown-topic-short-circuits
  (async done
    (-> (sub/subscribe-tool (atom {}) #js {"topic" "bogus"} nil)
        (.then (fn [r]
                 (is (= :unknown-topic (:reason (payload-of r))))
                 (is (true? (j/get r :isError))
                     "unknown-topic returns isError envelope")
                 (done))))))

(deftest missing-topic-short-circuits
  (async done
    (-> (sub/subscribe-tool (atom {}) #js {} nil)
        (.then (fn [r]
                 (is (= :unknown-topic (:reason (payload-of r))))
                 (done))))))
