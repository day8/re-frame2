(ns re-frame.story.play.step-settle-cljs-test
  "rf2-n0sz4 — the settle rung for a step's ASYNCHRONOUS preconditions.

  rf2-ek9qb gave a step's required BOUNDARY a producer, so a DOM-touching
  step now asks the live adapter to COMMIT before it runs. That closed the
  render race and only that one: a synchronous commit can commit only what
  the host has already SCHEDULED. Two things a play depends on are
  scheduled-but-not-landed at the instant the step wants them, and the
  browser lane measured both —

    - the async DISPATCH behind a synthetic DOM event
      (`expected 42 at [:count] but got 0`), and
    - the MOUNT the auto-run outruns (`selector matched no node`).

  ## Why these witnesses are reads and counts, never timings

  A timing measurement proves nothing in either direction: a fast machine
  passes a broken runner and a loaded one fails a correct one. So the
  witnesses below assert on STATE the runner is supposed to consult —
  `step-precondition-unmet`, the one decision the poll is built on. Before
  this bead nothing consulted it: the run-loop executed every step the
  moment it reached it, and the only thing standing between a `:click` and
  the next step's read of app-db was one blind `setTimeout` 0.

  The load-bearing subtlety, and the reason the queue is the predicate:
  `rf.router/dispatch-sync!` pushes its seed at the FRONT of the queue and
  drains, so an assertion dispatched at the next step JUMPS AHEAD of the
  click's still-queued event and reads app-db before it lands. Draining
  harder cannot fix an ordering inversion — only waiting for the queue
  can, which is what `queue-not-drained-is-an-unmet-precondition` pins.

  ## Which lane proves what

  `.cljc` so the JVM lane (`clojure -M:test` from `tools/story`) loads it,
  and `*_cljs_test` so the `:node-test` build's `cljs-test$` ns-regexp
  discovers it too — the same dual-lane trick
  `substrate_boundary_cljs_test.cljc` uses, and for the same reason: the
  production change is `.cljc` and a reader-conditional mistake in the
  `:cljs` branch would be invisible to a JVM-only run.

  ONE test is deliberately CLJS-only, and it is flagged in its own name.
  Observing a queued-but-undrained router demands that the drain provably
  has NOT run yet, and only CLJS guarantees that: `interop/next-tick` is
  `goog.async.nextTick`, a macrotask that cannot run inside the current
  synchronous turn. The JVM's `next-tick` hands the drain to a thread
  executor which explicitly does NOT promise return-before-start, so the
  same assertion would be a race there. Nothing about the read is async in
  either case — it happens on the statement after the dispatch, which is
  precisely what makes it deterministic."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer [deftest is testing use-fixtures]])
            [re-frame.core   :as rf]
            [re-frame.router :as rf.router]
            [re-frame.frame  :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story  :as rf.story]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]))

;; The two private decisions under witness, reached by var-quote — the
;; established Story-test seam for a runner internal.
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
  (testing "a step that fires an event at a node cannot run before the node
            exists — it has nothing to fire at"
    (is (= "[data-test=go]" (step-required-selector [:click "[data-test=go]"])))
    (is (= "[data-test=in]" (step-required-selector [:type "[data-test=in]" "42"])))
    (is (= "[data-test=in]" (step-required-selector [:focus "[data-test=in]"])))))

(deftest presence-asserting-dom-atoms-require-their-node
  (testing "the FOLDED atom shape [:rf.assert/dom-* selector & args] is what
            reaches the run-loop, so the selector is read out of the atom"
    (is (= "[data-test=out]"
           (step-required-selector
             [:assert [:rf.assert/dom-text "[data-test=out]" "42"]])))
    (is (= "[data-test=out]"
           (step-required-selector
             [:assert [:rf.assert/dom-visible "[data-test=out]"]]))))
  (testing "a raw shipping :assert-dom step that bypassed folding is covered too"
    (is (= "[data-test=out]"
           (step-required-selector [:assert-dom "[data-test=out]" :text "42"])))
    (is (= "[data-test=out]"
           (step-required-selector [:assert-dom "[data-test=out]" :visible])))))

(deftest dom-hidden-must-not-wait-for-the-node
  (testing "THE TRAP. An ABSENT node is :rf.assert/dom-hidden's PASS
            condition. Waiting for one to appear would invert the assertion
            and burn the whole budget doing it, so it is excluded"
    (is (nil? (step-required-selector
                [:assert [:rf.assert/dom-hidden "[data-test=gone]"]]))))
  (testing "AND IN RAW FORM, which is the half audit #8319 caught. `run!`
            documents and accepts a hand-built spec and does not fold it, so
            this shape reaches the run-loop verbatim. Read as a bare
            selector it demanded the node be PRESENT — inverting the
            assertion, burning the whole budget, and failing a script that
            passes character-for-character through the folded entry path.
            One step must not mean two things"
    (is (nil? (step-required-selector [:assert-dom "[data-test=gone]" :hidden])))))

(deftest a-malformed-assert-dom-waits-for-nothing
  (testing "tolerant: an :assert-dom the fold cannot parse (missing mode,
            unknown mode) reads as needing no node, so it reaches the
            executor that reports it properly rather than timing out on a
            precondition first"
    (is (nil? (step-required-selector [:assert-dom "[data-test=x]"])))
    (is (nil? (step-required-selector [:assert-dom "[data-test=x]" :sideways])))))

(deftest non-dom-steps-require-no-node
  (testing "a step naming no node waits for no node"
    (is (nil? (step-required-selector [:dispatch [:settle/inc]])))
    (is (nil? (step-required-selector [:dispatch-sync [:settle/inc]])))
    (is (nil? (step-required-selector [:wait-until [:queue-empty]])))
    (is (nil? (step-required-selector
                [:assert [:rf.assert/path-equals [:n] 1]])))))

;; ===========================================================================
;; The precondition decision itself
;; ===========================================================================

(deftest a-settled-frame-has-no-unmet-precondition
  (testing "the common case is a pure read that costs nothing and blocks
            nothing — no queue outstanding, no node demanded"
    (is (nil? (step-precondition-unmet settle-frame
                                       [:dispatch-sync [:settle/inc]])))
    (is (nil? (step-precondition-unmet settle-frame
                                       [:assert [:rf.assert/path-equals [:n] 0]])))))

(deftest headless-never-waits-for-a-node
  (testing "with no DOM available the selector precondition is MOOT — the
            executor's own no-DOM {:skipped? true} branch is the right
            answer and must not be pre-empted by a timeout. This is what
            keeps the JVM / node-runtime headless path unchanged"
    (is (nil? (step-precondition-unmet settle-frame [:click "[data-test=go]"])))
    (is (nil? (step-precondition-unmet
                settle-frame
                [:assert [:rf.assert/dom-text "[data-test=out]" "42"]])))))

(deftest an-unknown-frame-reads-as-drained
  (testing "tolerant — a destroyed / never-registered frame has nothing left
            to wait for, so it never parks the loop"
    (is (nil? (step-precondition-unmet :story.step-settle/no-such-frame
                                       [:dispatch-sync [:settle/inc]])))))

;; ===========================================================================
;; CLJS ONLY: the queue is genuinely outstanding after an async dispatch
;; ===========================================================================

#?(:cljs
   (deftest queue-not-drained-is-an-unmet-precondition
     (testing "THE HOLE rf2-n0sz4 measured. `rf.router/dispatch!` enqueues and
               schedules the drain through `interop/next-tick` — a
               MACROTASK, so it provably has not run on the statement after
               the dispatch. That is the state a step lands in right after a
               synthetic DOM event fires a handler that dispatches, and it
               is exactly the state the runner used to execute the next step
               in, reading app-db before the event landed"
       (rf.router/dispatch! [:settle/inc] {:frame settle-frame})
       (let [unmet (step-precondition-unmet settle-frame
                                            [:assert [:rf.assert/path-equals [:n] 1]])]
         (is (some? unmet)
             "the runner must NOT run the step while the queue is outstanding")
         (is (re-find #"queue" unmet)
             "and the timeout message must name what never settled"))
       (testing "and it clears once the queue really has drained — the poll
                 proceeds on the OBSERVED condition, not on a timer"
         (rf.router/dispatch-sync! [:settle/inc] {:frame settle-frame})
         (is (nil? (step-precondition-unmet
                     settle-frame
                     [:assert [:rf.assert/path-equals [:n] 1]])))))))
