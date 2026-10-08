(ns re-frame.story-error-test
  "`re-frame.story.error`: the one Throwable→error-map projection and the
  `:rf.error/exception` record built on it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.error :as rf.story.error]))

;; `exception-record` walks `:data` against its frame and fails closed when
;; the frame is unresolvable, so a live frame needs an installed adapter.
(use-fixtures :each
  (fn [test-fn]
    (reset! rf.frame/frames {})
    (try (rf/init! rf.substrate.plain-atom/adapter)
         (catch clojure.lang.ExceptionInfo _ nil))
    (rf.frame/ensure-default-frame!)
    (test-fn)))

;; ---- throwable->error-map -------------------------------------------------

(deftest throwable->error-map-projects-the-canonical-shape
  (let [m (rf.story.error/throwable->error-map (ex-info "boom" {:why :test}))]
    (is (= {:message "boom" :data {:why :test}} (dissoc m :stack)))
    (is (str/includes? (:stack m) "boom")
        "the trace is captured into :stack, not leaked to stderr")))

(deftest throwable->error-map-tolerates-nil-throwable
  (is (= {:message nil :stack nil :data nil} (rf.story.error/throwable->error-map nil))))

(deftest throwable->error-map-explicit-message-override
  (testing "an explicit :message wins, even without a throwable; nil falls back"
    (doseq [[e opts expected] [[(ex-info "original" {}) {:message "pre-extracted"} "pre-extracted"]
                               [nil {:message "pre-extracted"} "pre-extracted"]
                               [(ex-info "original" {}) {:message nil} "original"]]]
      (is (= expected (:message (rf.story.error/throwable->error-map e opts)))))))

;; ---- exception-record -----------------------------------------------------

(deftest exception-record-wraps-the-projection
  (rf/make-frame {:id :story.x/v})
  (try
    (let [e (ex-info "kaboom" {:k :v})
          r (rf.story.error/exception-record :story.x/v :phase-2-events [:some/event 1] e)]
      (is (= {:assertion :rf.error/exception :variant-id :story.x/v :phase :phase-2-events
              :event [:some/event 1] :passed? false}
             (select-keys r [:assertion :variant-id :phase :event :passed?])))
      (is (= (rf.story.error/throwable->error-map e {:frame :story.x/v}) (:error r)))
      (is (= {:k :v} (-> r :error :data)) "a live frame's empty policy passes :data through"))
    (finally
      (rf/destroy-frame! :story.x/v))))

;; ---- the no-handler refusal is a captured failure -------------------------

;; The trace `handle-no-handler!` emits: the event rides `:rf.event/v`, and
;; there is no `:exception`, because the refusal happens before any pipeline.
(def ^:private no-such-handler-trace
  {:operation :rf.error/no-such-handler
   :op-type   :error
   :tags      {:category            :rf.error/no-such-handler
               :rf.trace/event-id   :your/setup-event
               :rf.event/v          [:your/setup-event {}]
               :frame               :story.x/v
               :kind                :event
               :rf.trace/dispatch-id 14}})

(deftest no-such-handler-is-a-captured-failure-operation
  (is (true?  (rf.story.error/pipeline-exception-event? :story.x/v no-such-handler-trace)))
  (is (false? (rf.story.error/pipeline-exception-event? :story.other/v no-such-handler-trace))
      "a sibling frame's refusal is not this frame's failure"))
