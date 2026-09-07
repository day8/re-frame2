(ns re-frame.flows-direct-clear-settle-cljs-test
  "Cross-host coverage for Spec 013 §The same boundary for a direct `clear-flow`.

  The `:rf.fx/clear-flow` route already settles: its dependents recompute
  against the cleared flow's absence before the dispatching event returns
  (`re-frame.flows-settle-on-dispatch-test`). The plain function
  `re-frame.flows/clear-flow` is documented as a synchronous
  deregister-and-vacate call for boot code, tests, and per-tenant setup, and
  it must honour the same OBSERVABLE boundary: when it returns, no remaining
  flow may still publish a value derived from the slot it just removed.

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it AND the cognitect JVM runner runs it
  (the `-test` suffix). The lifecycle code under test — `clear-flow`, the
  settle bridge, and `run-flows-on-db` — is all `.cljc`, so the boundary is
  exercised on both hosts rather than only the one the defect was found on.

  ## Why these tests are shaped the way they are

  The defect this file pins is invisible to any test that does something
  after the clear. A stale dependent self-heals on the next ordinary drain,
  so a witness that dispatches — or that merely calls anything which could
  drain — passes whether or not the bug is present. Every assertion below is
  therefore made against a db value captured with `clear-flow` as the ONLY
  intervening call, and each carries two independent proofs that nothing
  drained in that window:

  1. A `:derive` counter on the dependent. A settle runs the dependent's
     derivation exactly once; a window in which nothing drained leaves the
     counter untouched. The counter distinguishes \"settled inside the
     clear\" from \"settled later\" in a way an app-db read alone cannot.
  2. A trace recorder over EVERY op-type. `:rf.event` op-type events are
     emitted by any dispatch and any drain, so an empty `:rf.event` slice
     across the window is affirmative evidence that no event ran — which
     also pins the implementation constraint that the direct settle must NOT
     be a dispatched settle event (that would re-enter the drain gate the
     call already holds).

  An absence is only evidence if the instrument works, so the recorder
  carries its own POSITIVE CONTROL in-run: the same slice is re-read after a
  deliberate dispatch and must be non-empty. Without it, a dead recorder — a
  build with tracing compiled out, an op-type rename — would report the empty
  window slice that means \"nothing drained\" in exactly the words a working
  one does.

  `re-frame.core/app-db-value` is a pure deref of the frame's app-db
  projection through the substrate adapter, so the observation itself cannot
  trigger a pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace :as rf.trace]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

;; ---- whole-stream trace recorder -----------------------------------------
;;
;; Unlike the flow-op-type recorder in `flows-trace-test`, this one keeps
;; EVERY op-type: the point here is to prove the ABSENCE of `:rf.event`
;; traffic, which a pre-filtered recorder could not distinguish from a filter
;; that simply never matched. Registered per test body rather than as a
;; fixture so the teardown is a plain `finally` on both hosts.

(defn- call-with-recorder
  "Run `(f captured)` with a whole-stream trace recorder installed."
  [f]
  (let [captured (atom [])]
    (rf.trace/register-listener!
      ::direct-clear-settle-recorder
      (fn [ev] (swap! captured conj ev)))
    (try
      (f captured)
      (finally
        (rf.trace/unregister-listener! ::direct-clear-settle-recorder)))))

(defn- event-ops
  "Every recorded `:rf.event` op-type operation, in capture order. Non-empty
  iff an event was dispatched or a drain ran while the recorder was armed."
  [captured]
  (into [] (comp (filter #(= :rf.event (:op-type %)))
                 (map :operation))
        @captured))

;; ---------------------------------------------------------------------------
;; The witness
;; ---------------------------------------------------------------------------

(deftest direct-clear-settles-dependents-before-it-returns
  (testing "a dependent flow has recomputed against the cleared producer's
            absence by the time the plain `clear-flow` call returns, with no
            application-authored dispatch and no drain in the window"
    (call-with-recorder
      (fn [captured]
        (let [derives (atom 0)]
          (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
          ;; Producer A: [:x] -> [:a].
          (rf/reg-flow :probe/a
            {:inputs [[:x]] :output-path [:a]}
            (fn [x] x))
          ;; Dependent B: [:a] -> [:b]. B's ONLY declared input is A's output,
          ;; so once A is deregistered and its slot vacated, B's correct value
          ;; is the derivation of `nil`.
          (rf/reg-flow :probe/b
            {:inputs [[:a]] :output-path [:b]}
            (fn [a] (swap! derives inc) a))
          (rf/dispatch-sync [:seed])
          (is (= {:x 2 :a 2 :b 2} (rf/app-db-value :rf/default))
              "precondition — the seeding drain materialised A and B in topological order")

          (let [derives-before @derives]
            ;; Arm the window: discard everything the seeding dispatch
            ;; recorded, so the `:rf.event` slice below covers the clear and
            ;; nothing else.
            (reset! captured [])

            ;; ---- THE WINDOW ---------------------------------------------
            ;; `clear-flow` is the SOLE call between the precondition read
            ;; above and the capture below. No dispatch, no `dispatch-sync`,
            ;; no manual flow pass, no other framework call of any kind.
            (rf/clear :flow :probe/a)
            (let [observed   (rf/app-db-value :rf/default)
                  window-ops (event-ops captured)]
              ;; -------------------------------------------------------------

              ;; Proof 1 — nothing drained: no event ran in the window at all.
              ;; (Its positive control is at the end of this test.)
              (is (= [] window-ops)
                  (str "no event ran between the clear and the observation — the "
                       "direct settle must not dispatch (it would re-enter the "
                       "drain gate the call already holds); saw " (pr-str window-ops)))

              ;; The already-correct half, retained so a regression here is not
              ;; masked by the dependent assertion below.
              (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) :probe/a))
                  "A is deregistered from the per-frame registry")
              (is (not (contains? observed :a))
                  "A's output leaf is vacated")

              ;; THE DEFECT. Red before the fix: `:b` is still 2, derived from
              ;; the `:a` slot that this very call removed — an app-db in which
              ;; a live flow publishes a value from a dead input.
              ;;
              ;; B is still REGISTERED, so its slot stays published; what must
              ;; change is the value, now derived from A's absence. This is the
              ;; same shape `:rf.fx/clear-flow` produces — see
              ;; `flows-settle-on-dispatch-test/settle-runs-once-and-recomputes-dependents`,
              ;; where the dependent's slot holds `"total="` (derived from nil)
              ;; rather than disappearing. Only the CLEARED flow's own leaf is
              ;; vacated; leaf-only vacation does not cascade.
              (is (= {:x 2 :b nil} observed)
                  (str "the dependent recomputed against A's absence before the "
                       "call returned; app-db was " (pr-str observed)))

              ;; Proof 2 — the recompute happened INSIDE the clear, not merely
              ;; "eventually": exactly one derivation, and no fixed-point churn.
              (is (= 1 (- @derives derives-before))
                  "the dependent derived exactly once — one settle pass, not repeated iteration"))

            ;; THE DIAGNOSIS, stated directly. The bug was never "dependents are
            ;; not updated" — it was "they are updated only by some LATER,
            ;; UNRELATED drain", which is what made it self-healing in a busy
            ;; app and invisible to any test that dispatches afterwards. So the
            ;; contract is drain-equivalence: an unrelated event must find
            ;; nothing left to repair. Before the fix these two values differ
            ;; ({:x 2, :b 2} against {:x 2, :b nil}); that difference IS the
            ;; defect.
            (let [settled-by-clear (rf/app-db-value :rf/default)
                  derives-at-clear @derives]
              (reset! captured [])
              (rf/reg-event :unrelated-no-op (fn [_ _] {}))
              (rf/dispatch-sync [:unrelated-no-op])
              (is (= settled-by-clear (rf/app-db-value :rf/default))
                  "an unrelated drain finds nothing to repair — the clear already settled it")
              (is (zero? (- @derives derives-at-clear))
                  "and the dependent does not derive again: the dirty check sees settled inputs")

              ;; POSITIVE CONTROL for proof 1, sharing its exact shape: the
              ;; same `:rf.event` slice, over a window that DID dispatch. If
              ;; this is empty the recorder is dead and the empty window slice
              ;; above proved nothing.
              (is (seq (event-ops captured))
                  (str "control — the recorder does capture :rf.event traffic, so "
                       "the empty window slice above is a real absence and not a "
                       "dead instrument")))))))))

(deftest direct-clear-settle-is-frame-local
  (testing "clearing on one frame settles that frame only — a sibling frame's
            identically-named flows are neither recomputed nor mutated"
    (let [left-derives  (atom 0)
          right-derives (atom 0)]
      (rf/make-frame {:id :probe/left})
      (rf/make-frame {:id :probe/right})
      (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
      (doseq [[frame-id counter] [[:probe/left left-derives]
                                  [:probe/right right-derives]]]
        (rf/reg-flow :probe/a
          {:frame frame-id :inputs [[:x]] :output-path [:a]}
          (fn [x] x))
        (rf/reg-flow :probe/b
          {:frame frame-id :inputs [[:a]] :output-path [:b]}
          (fn [a] (swap! counter inc) a)))
      (rf/dispatch-sync [:seed] {:frame :probe/left})
      (rf/dispatch-sync [:seed] {:frame :probe/right})
      (is (= {:x 2 :a 2 :b 2} (rf/app-db-value :probe/right))
          "precondition — the sibling frame is materialised")

      (let [right-before @right-derives]
        (rf/clear :flow :probe/a {:frame :probe/left})
        (let [left-observed  (rf/app-db-value :probe/left)
              right-observed (rf/app-db-value :probe/right)]
          (is (= {:x 2 :b nil} left-observed)
              "the cleared frame settled: A's slot gone, B recomputed against the absence")
          (is (= {:x 2 :a 2 :b 2} right-observed)
              "the sibling frame's app-db is untouched")
          (is (zero? (- @right-derives right-before))
              "the sibling frame's dependent was not re-derived"))))))

(deftest direct-clear-no-op-paths-stay-silent
  (testing "an unknown flow id settles nothing and derives nothing"
    (call-with-recorder
      (fn [captured]
        (let [derives (atom 0)]
          (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
          (rf/reg-flow :probe/a {:inputs [[:x]] :output-path [:a]} (fn [x] x))
          (rf/reg-flow :probe/b {:inputs [[:a]] :output-path [:b]}
            (fn [a] (swap! derives inc) a))
          (rf/dispatch-sync [:seed])
          (let [before @derives]
            (reset! captured [])
            (rf/clear :flow :probe/no-such-flow)
            (is (= {:x 2 :a 2 :b 2} (rf/app-db-value :rf/default))
                "an unknown id leaves the frame exactly as it was")
            (is (zero? (- @derives before))
                "an unknown id runs no settle pass")
            (is (= [] (event-ops captured))
                "an unknown id dispatches nothing"))))))

  (testing "an absent frame is a silent no-op, not a throw"
    (is (nil? (rf/clear :flow :probe/a {:frame :probe/never-registered}))
        "clear-flow against an absent frame returns nil without throwing")))
