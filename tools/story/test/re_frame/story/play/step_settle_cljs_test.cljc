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
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test    :refer [deftest is use-fixtures]])
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

(deftest interaction-steps-require-their-node
  (is (= "[data-test=go]" (step-required-selector [:click "[data-test=go]"]))))

(deftest presence-asserting-dom-atoms-require-their-node
  ;; the folded atom reaches the run-loop; a raw step that bypassed folding too
  (is (= "[data-test=out]"
         (step-required-selector [:assert [:rf.assert/dom-text "[data-test=out]" "42"]])))
  (is (= "[data-test=out]"
         (step-required-selector [:assert-dom "[data-test=out]" :text "42"]))))

(deftest dom-hidden-must-not-wait-for-the-node
  ;; An ABSENT node is dom-hidden's PASS condition: waiting for one would
  ;; invert the assertion and burn the budget. `run!` accepts a hand-built,
  ;; unfolded spec, so the raw form must mean the same.
  (is (nil? (step-required-selector [:assert [:rf.assert/dom-hidden "[data-test=gone]"]])))
  (is (nil? (step-required-selector [:assert-dom "[data-test=gone]" :hidden]))))

(deftest a-malformed-assert-dom-waits-for-nothing
  ;; it reaches the executor that reports it, rather than timing out first
  (is (nil? (step-required-selector [:assert-dom "[data-test=x]" :sideways]))))

(deftest non-dom-steps-require-no-node
  (is (nil? (step-required-selector [:dispatch [:settle/inc]])))
  (is (nil? (step-required-selector [:assert [:rf.assert/path-equals [:n] 1]]))))

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
