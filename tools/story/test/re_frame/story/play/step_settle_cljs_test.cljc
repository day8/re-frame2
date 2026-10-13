(ns re-frame.story.play.step-settle-cljs-test
  "The settle rung for a step's ASYNCHRONOUS preconditions. A synchronous
  commit can commit only what the host has already scheduled, so two things
  a play depends on can be scheduled-but-not-landed when a step wants them:
  the async dispatch behind a synthetic DOM event, and the mount an auto-run
  outruns. The witnesses read `step-precondition-unmet`, the decision the
  poll is built on, never a timing.

  The queue is the predicate because `rf.router/dispatch-sync!` pushes its
  seed at the FRONT of the queue: an assertion dispatched at the next step
  would jump ahead of a click's still-queued event. `.cljc` with a
  `-cljs-test` name, so the JVM and `:node-test` lanes both run it. The one
  CLJS-only test needs `interop/next-tick` to be a macrotask that provably
  has not run yet; the JVM's executor makes no such promise."
  (:require #?(:clj  [clojure.test :refer [are deftest is use-fixtures]]
               :cljs [cljs.test    :refer [are deftest is use-fixtures]])
            [re-frame.core   :as rf]
            [re-frame.router :as rf.router]
            [re-frame.frame  :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story  :as rf.story]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]))

;; The two private decisions under witness, reached by var-quote.
(def ^:private step-required-selector   @#'rf.story.play.runner-events/step-required-selector)
(def ^:private step-precondition-unmet  @#'rf.story.play.runner-events/step-precondition-unmet)

(def ^:private settle-frame :story.step-settle/frame)

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id settle-frame :doc "step-settle witness frame"})
  (rf/reg-event :settle/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (test-fn))

(use-fixtures :each reset-rf!)

;; ===========================================================================
;; PURE: which steps must wait for a node, and — the trap — which must not
;; ===========================================================================

(deftest a-step-waits-only-for-a-node-it-needs-present
  ;; The folded atom reaches the run-loop, and `run!` accepts a hand-built,
  ;; unfolded spec, so each DOM row is checked in both forms.
  (are [step selector] (= selector (step-required-selector step))
    [:click "[data-test=go]"]                              "[data-test=go]"
    [:assert [:rf.assert/dom-text "[data-test=out]" "42"]] "[data-test=out]"
    [:assert-dom "[data-test=out]" :text "42"]             "[data-test=out]"
    ;; an ABSENT node is dom-hidden's PASS condition: waiting for one would
    ;; invert the assertion and burn the budget
    [:assert [:rf.assert/dom-hidden "[data-test=gone]"]]   nil
    [:assert-dom "[data-test=gone]" :hidden]               nil
    ;; a malformed step reaches the executor that reports it, not a timeout
    [:assert-dom "[data-test=x]" :sideways]                nil
    [:dispatch [:settle/inc]]                              nil
    [:assert [:rf.assert/path-equals [:n] 1]]              nil))

;; ===========================================================================
;; The precondition decision itself
;; ===========================================================================

(deftest headless-never-waits-for-a-node
  ;; with no DOM the executor's own no-DOM skip answers, never a timeout
  (is (nil? (step-precondition-unmet settle-frame [:click "[data-test=go]"]))))

(deftest an-unknown-frame-reads-as-drained
  (is (nil? (step-precondition-unmet :story.step-settle/no-such-frame
                                     [:dispatch-sync [:settle/inc]]))))

;; ===========================================================================
;; CLJS ONLY: the queue is genuinely outstanding after an async dispatch
;; ===========================================================================

#?(:cljs
   (deftest queue-not-drained-is-an-unmet-precondition
     ;; `rf.router/dispatch!` schedules its drain as a macrotask, so on the next
     ;; statement the queue is provably outstanding: the state a step lands in
     ;; right after a synthetic DOM event's handler dispatches.
     (rf.router/dispatch! [:settle/inc] {:frame settle-frame})
     (let [unmet (step-precondition-unmet settle-frame
                                          [:assert [:rf.assert/path-equals [:n] 1]])]
       (is (some? unmet) "the runner must NOT run the step while the queue is outstanding")
       (is (re-find #"queue" unmet) "the timeout message names what never settled"))
     (rf.router/dispatch-sync! [:settle/inc] {:frame settle-frame})
     (is (nil? (step-precondition-unmet settle-frame [:assert [:rf.assert/path-equals [:n] 1]]))
         "it clears once the queue has drained")))
