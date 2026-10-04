(ns re-frame.machine-raise-fifo-test
  "A `:raise` depth-limit abort discards the WHOLE macrostep.

  `:raise` drains FIFO on the machine's one internal event queue (XState v5 /
  SCXML); that order is pinned on both hosts by
  `scxml-irp-test144-internal-raise-fifo`. This namespace pins what happens
  when the drain trips `:raise-depth-limit`: a pure-engine test that calls
  `machine-transition` directly and reads the failed Result.

  Pairs with the flat-machine drain in
  `re-frame.machines.transition/drain-raises`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

;; ---- depth-bound rollback is TRULY atomic ---------------------------------
;;
;; Raises that neither mutate :data nor emit non-raise fx (identical `[:noop]`
;; self-loops) leave the partially-advanced snapshot EQUAL to the original even
;; without a real rollback, so they cannot distinguish a non-atomic abort from a
;; true one. (That the limit counts every raise drained, siblings included, is
;; pinned by `machine_transition_purity_test`'s
;; `raise-depth-boundary-matches-always-boundary`.) These fixtures make
;; every intermediate raise MUTATE state + :data AND emit a non-raise
;; side-effect fx BEFORE the limit trips, so a non-atomic abort would commit a
;; drifted snapshot and leak the accumulated effects. The contract: the WHOLE
;; macrostep is discarded — original snapshot, empty fx.

(deftest depth-bound-rollback-discards-intermediate-mutations-and-fx
  (testing "a :raise depth-limit abort rolls back the ENTIRE
            macrostep — intermediate state/data writes AND accumulated
            non-raise fx are discarded; the result is the ORIGINAL snapshot
            and an EMPTY fx vector"
    (let [;; Ping-pong: :a's entry bumps :n + emits a side-effect + raises
          ;; [:to-b]; :b's entry bumps :n + emits a side-effect + raises
          ;; [:to-a]. The cycle never terminates → trips the limit, having
          ;; mutated :data and accumulated side-effect fx along the way.
          spec {:initial :idle
                :raise-depth-limit 3
                :states {:idle {:on {:start :a}}
                         :a {:entry (fn [{d :data}]
                                      {:data (update d :n inc)
                                       :fx   [[:raise [:to-b]] [:side-effect-a 1]]})
                             :on {:to-b :b}}
                         :b {:entry (fn [{d :data}]
                                      {:data (update d :n inc)
                                       :fx   [[:raise [:to-a]] [:side-effect-b 1]]})
                             :on {:to-a :a}}}}
          original {:state :idle :data {:n 0}}
          r        (rf.machines/machine-transition spec original [:start])]
      ;; The abort is a FAILED macrostep. Atomic rollback is enforced by the
      ;; `:fail` threading NO snapshot / fx: every intermediate :a/:b state,
      ;; bumped :n, and accumulated :side-effect fx is discarded with the
      ;; macrostep (there is nothing to commit on a `:fail`).
      (is (= :error (:status r))
          "depth-exceeded returns a :fail (failed macrostep), not an :ok no-op")
      (is (= :rf.error/machine-raise-depth-exceeded (get-in r [:error :kind]))
          "the failure names the raise depth-exceeded category (a bounded-depth trip)")
      (is (nil? (:snapshot r))
          "a :fail threads no snapshot — no intermediate :a/:b state or bumped :n survives")
      (is (nil? (:fx r))
          "a :fail threads no fx — EVERY accumulated :side-effect was discarded"))))
