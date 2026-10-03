(ns re-frame.machine-raise-fifo-test
  "`:raise` drains FIFO (XState v5 / SCXML internal-event queue), NOT
  depth-first.

  XState v5 / SCXML: `raise` / `<raise>` enqueues on the
  machine's ONE internal event queue, drained breadth-first within the
  macrostep. A transition that raises `[B]` then `[C]`, where B's handler
  itself raises `[D]`, processes them `B, C, D` — D goes to the BACK of the
  queue, BEHIND the still-pending sibling C (a depth-first drain would give
  `B, D, C`). That exact case is `scxml-irp-test144-internal-raise-fifo`'s, on
  both hosts; the two-nester case below adds a second nested raise.

  These are pure-engine tests — they call `machine-transition` directly and
  read processing order off the post-macrostep snapshot's `:data` log (the
  pure, deterministic record of the order actions fired). A linear chain
  (each step raises exactly one event) settles identically under FIFO and
  depth-first, so the discriminating fixtures all raise ≥2 siblings where an
  earlier one transitively raises more.

  Pairs with the flat-machine drain in
  `re-frame.machines.transition/drain-raises`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

(defn- log-action
  "Build an action that appends `label` to `:data :log` and optionally
  emits the given `raise-events` (a seq of event-vectors) as `:fx`
  `[:raise …]` entries, in order."
  [label & raise-events]
  (fn [{:keys [data]}]
    (cond-> {:data (update data :log (fnil conj []) label)}
      (seq raise-events)
      (assoc :fx (mapv (fn [ev] [:raise ev]) raise-events)))))

;; ---- 1. deeper interleave — two nesters -----------------------------------

(deftest fifo-two-nesting-siblings-interleave-breadth-first
  (testing "A raises [B] [C]; B raises [D]; C raises [E] ⇒ B, C, D, E"
    ;; Breadth-first discriminates sharply here: FIFO yields B,C,D,E
    ;; (both nested raises behind BOTH siblings); depth-first would yield
    ;; B,D,C,E (each nested raise jumps ahead of its sibling).
    (let [spec {:initial :hub
                :data    {}
                :actions {:go (log-action :go [:b] [:c])
                          :b  (log-action :b [:d])
                          :c  (log-action :c [:e])
                          :d  (log-action :d)
                          :e  (log-action :e)}
                :states  {:hub {:on {:go {:action :go}
                                     :b  {:action :b}
                                     :c  {:action :c}
                                     :d  {:action :d}
                                     :e  {:action :e}}}}}
          {snap :snapshot} (rf.machines/machine-transition
                                 spec {:state :hub :data {}} [:go])]
      (is (= [:go :b :c :d :e] (:log (:data snap)))
          "both first-level siblings (B, C) drain before either's nested
           raise (D, E) — breadth-first, the XState/SCXML internal queue"))))

;; ---- 2. linear chain — FIFO and depth-first agree -------------------------

(deftest fifo-linear-chain-unchanged
  (testing "a linear self-chain (one raise per step) reaches the terminal
   state in one macrostep — order discipline is irrelevant when no step
   raises ≥2 siblings"
    (let [mk (fn [label next-ev]
               (log-action label next-ev))
          spec {:initial :s0
                :data    {}
                :actions {:a1 (mk :a1 [:e2])
                          :a2 (mk :a2 [:e3])
                          :a3 (log-action :a3)}
                :states  {:s0 {:on {:e1 {:target :s1 :action :a1}}}
                          :s1 {:on {:e2 {:target :s2 :action :a2}}}
                          :s2 {:on {:e3 {:target :s3 :action :a3}}}
                          :s3 {}}}
          {snap :snapshot} (rf.machines/machine-transition
                                 spec {:state :s0 :data {}} [:e1])]
      (is (= :s3 (:state snap))
          "linear chain settles to the terminal state")
      (is (= [:a1 :a2 :a3] (:log (:data snap)))
          "linear chain order is identical under FIFO and depth-first"))))

;; ---- 3. depth-bound rollback is TRULY atomic ------------------------------
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
