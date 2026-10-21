(ns counter-with-stories.stories-cljs-test
  "Counter story results, diagnostic failures, and recorder classification."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.source-store :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.test-support :as rf.test-support]
            [counter-with-stories.events]
            [counter-with-stories.subs]
            [counter-with-stories.stories :as cw-stories]))

(def ^:private registrar-snapshot (atom nil))

;; Restore namespace-load app descriptors for scoped images after sibling resets.
(def ^:private source-store-baseline @rf.source-store/kind->id->ns->descriptor)

(defn- before! []
  (reset! registrar-snapshot (rf.test-support/snapshot-registrar))
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/clear-all!)
  (reset! rf.source-store/kind->id->ns->descriptor source-store-baseline)
  (cw-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (rf.test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! rf.frame/frames {}))

;; Async story bodies require map fixtures.
(use-fixtures :each {:before before! :after after!})

(defn- run-then [done variant-id check]
  (-> (rf.story/run-variant variant-id)
      (rf.story.async/then
        (fn [result]
          (check result)
          (rf.story/destroy-variant! variant-id)
          (done)))))

(deftest matrix-variants-registered
  (is (= #{:story.counter-matrix/no-play
           :story.counter-matrix/loader-success
           :story.counter-matrix/loader-never-completes
           :story.counter-matrix/loader-rejects
           :story.counter-matrix/schema-invalid
           :story.counter-matrix/nested-controls
           :story.counter-matrix/decorator-throws
           :story.counter-matrix/multi-substrate
           :story.counter-matrix/isolation-a
           :story.counter-matrix/isolation-b
           :story.counter-matrix/recorder-redaction
           :story.counter-matrix/a11y-known-good
           :story.counter-matrix/a11y-known-bad
           :story.counter-matrix/failing-fx-stub-miss}
         (rf.story/variants-of :story.counter-matrix))))

(deftest recorder-redaction-sensitive-handler-registered-self-contained
  (is (= [[:password]]
         (:sensitive (rf/handler-meta {:kind :event
                                      :id :counter/sign-in})))))

(deftest recorder-redaction-variant-runs-self-contained
  (async done
    (run-then done :story.counter-matrix/recorder-redaction
      (fn [result]
        (is (= [:ready 11 true]
               [(:lifecycle result)
                (get-in result [:app-db :count])
                (rf.story/assertions-passing? result)]))))))

(deftest failing-fx-stub-miss-variant-fails-with-canonical-reason
  (async done
    (run-then done :story.counter-matrix/failing-fx-stub-miss
      (fn [result]
        (is (= [{:assertion :rf.assert/effect-emitted
                 :passed? false
                 :reason "fx :never-stubbed was not emitted during play"}]
               (mapv #(select-keys % [:assertion :passed? :reason])
                     (:assertions result))))))))

(deftest empty-variant-runs-and-passes
  (async done
    (run-then done :story.counter/empty
      (fn [result]
        (is (= [:ready 0 true]
               [(:lifecycle result)
                (get-in result [:app-db :count])
                (rf.story/assertions-passing? result)]))))))

(deftest loaded-variant-runs-and-passes
  (async done
    (run-then done :story.counter/loaded
      (fn [result]
        (is (= [:ready 7 3 true]
               [(:lifecycle result)
                (get-in result [:app-db :count])
                (count (:assertions result))
                (rf.story/assertions-passing? result)]))))))

(deftest clicked-three-times-variant-runs-and-passes
  (async done
    (run-then done :story.counter/clicked-three-times
      (fn [result]
        (is (= [3 true]
               [(get-in result [:app-db :count])
                (rf.story/assertions-passing? result)]))))))

(deftest save-stubbed-variant-runs-and-effect-emitted-passes
  (async done
    (run-then done :story.counter/save-stubbed
      (fn [result]
        (is (= [:ready true true]
               [(:lifecycle result)
                (get-in result [:app-db :saving?])
                (rf.story/assertions-passing? result)]))))))

(deftest diagnostic-failing-play-records-failure
  (async done
    (run-then done :story.counter-diagnostics/failing-play
      (fn [result]
        (is (= [1 false true]
               [(get-in result [:app-db :count])
                (rf.story/assertions-passing? result)
                (boolean (some #(and (= :rf.assert/path-equals (:assertion %))
                                     (false? (:passed? %)))
                               (:assertions result)))]))))))

(deftest diagnostic-event-exception-records-failure
  (async done
    (run-then done :story.counter-diagnostics/failing-event-throws
      (fn [result]
        (is (= [false true]
               [(rf.story/assertions-passing? result)
                (boolean
                  (some #(and (= :rf.error/exception (:assertion %))
                              (= :phase-4-play (:phase %))
                              (= [:counter/throw-deterministic] (:event %))
                              (re-find #"story-load deterministic event handler failure"
                                       (get-in % [:error :message] "")))
                        (:assertions result)))]))))))

(deftest diagnostic-loader-exception-records-failure
  (async done
    (run-then done :story.counter-diagnostics/loader-throws
      (fn [result]
        (is (= [false true]
               [(rf.story/assertions-passing? result)
                (boolean
                  (some #(and (= :rf.error/exception (:assertion %))
                              (= :phase-1-loaders (:phase %))
                              (re-find #"story-load deterministic event handler failure"
                                       (get-in % [:error :message] "")))
                        (:assertions result)))]))))))
