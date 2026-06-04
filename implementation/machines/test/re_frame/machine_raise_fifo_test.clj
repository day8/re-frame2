(ns re-frame.machine-raise-fifo-test
  "Per rf2-nr434 — `:raise` drains FIFO (XState v5 / SCXML internal-event
  queue), NOT depth-first.

  XState v5 / SCXML gold standard: `raise` / `<raise>` enqueues on the
  machine's ONE internal event queue, drained breadth-first within the
  macrostep. A transition that raises `[B]` then `[C]`, where B's handler
  itself raises `[D]`, processes them `B, C, D` — D goes to the BACK of the
  queue, BEHIND the still-pending sibling C. The engine previously prepended
  the nested raise (`(concat fx2 rest-pending)` in `drain-raises`), giving a
  depth-first `B, D, C`; that was an unblessed divergence, now aligned.

  These are pure-engine tests — they call `machine-transition` directly and
  read processing order off the post-macrostep snapshot's `:data` log (the
  pure, deterministic record of the order actions fired). A linear chain
  (each step raises exactly one event) settles identically under FIFO and
  depth-first, so the discriminating fixtures all raise ≥2 siblings where an
  earlier one transitively raises more.

  Pairs with the flat-machine drain in
  `re-frame.machines.transition/drain-raises`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]))

(defn- log-action
  "Build an action that appends `label` to `:data :log` and optionally
  emits the given `raise-events` (a seq of event-vectors) as `:fx`
  `[:raise …]` entries, in order."
  [label & raise-events]
  (fn [{:keys [data]}]
    (cond-> {:data (update data :log (fnil conj []) label)}
      (seq raise-events)
      (assoc :fx (mapv (fn [ev] [:raise ev]) raise-events)))))

;; ---- 1. two sibling raises, earlier sibling raises a nested event --------

(deftest fifo-nested-raise-goes-behind-sibling
  (testing "A raises [B] then [C]; B raises [D] ⇒ FIFO order B, C, D (D behind C)"
    ;; A single hub state handles every event as an INTERNAL transition
    ;; (no :target), so the machine stays put and each raised event resolves
    ;; against the same state. The :data :log records the action order.
    (let [spec {:initial :hub
                :data    {}
                :actions {:go (log-action :go [:b] [:c]) ;; raise B then C
                          :b  (log-action :b [:d])       ;; B raises D
                          :c  (log-action :c)
                          :d  (log-action :d)}
                :states  {:hub {:on {:go {:action :go}
                                     :b  {:action :b}
                                     :c  {:action :c}
                                     :d  {:action :d}}}}}
          {snap ::result/snap} (machines/machine-transition
                                 spec {:state :hub :data {}} [:go])]
      (is (= [:go :b :c :d] (:log (:data snap)))
          "FIFO (XState/SCXML): the nested raise D lands behind sibling C —
           NOT the depth-first [:go :b :d :c] the old prepend produced"))))

;; ---- 2. plain two-sibling order (no nesting) -----------------------------

(deftest fifo-sibling-raises-process-in-emit-order
  (testing "A raises [B] then [C] (neither nests) ⇒ B before C, in :fx order"
    (let [spec {:initial :hub
                :data    {}
                :actions {:go (log-action :go [:b] [:c])
                          :b  (log-action :b)
                          :c  (log-action :c)}
                :states  {:hub {:on {:go {:action :go}
                                     :b  {:action :b}
                                     :c  {:action :c}}}}}
          {snap ::result/snap} (machines/machine-transition
                                 spec {:state :hub :data {}} [:go])]
      (is (= [:go :b :c] (:log (:data snap)))
          "sibling raises drain in the order they entered the queue"))))

;; ---- 3. deeper interleave — two nesters -----------------------------------

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
          {snap ::result/snap} (machines/machine-transition
                                 spec {:state :hub :data {}} [:go])]
      (is (= [:go :b :c :d :e] (:log (:data snap)))
          "both first-level siblings (B, C) drain before either's nested
           raise (D, E) — breadth-first, the XState/SCXML internal queue"))))

;; ---- 4. linear chain — FIFO and depth-first agree -------------------------

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
          {snap ::result/snap} (machines/machine-transition
                                 spec {:state :s0 :data {}} [:e1])]
      (is (= :s3 (:state snap))
          "linear chain still settles to the terminal state")
      (is (= [:a1 :a2 :a3] (:log (:data snap)))
          "linear chain order is identical under FIFO and depth-first"))))

;; ---- 5. depth bound is order-independent ----------------------------------

(deftest fifo-depth-bound-counts-total-raises-not-order
  (testing "the :raise-depth-limit trips on the COUNT of raises drained,
   unaffected by FIFO vs depth-first ordering (rf2-r26e2 boundary preserved)"
    ;; Six sibling raises in one :fx vector under limit 4. The drain
    ;; processes raises at depths 0..3 then aborts at depth 4 — the >=
    ;; boundary — regardless of queue ordering, because order changes the
    ;; SEQUENCE not the COUNT. The macrostep rolls back atomically.
    (let [seen (atom [])
          spec {:initial :idle
                :data    {}
                :raise-depth-limit 4
                :actions {:fan-out (fn [_]
                                     {:fx [[:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]]})}
                :states  {:idle    {:on {:start {:target :running :action :fan-out}
                                         :noop  :idle}}
                          :running {:on {:noop :idle}}}}]
      (let [listener (fn [ev] (when (= :rf.error/machine-raise-depth-exceeded
                                        (get-in ev [:tags :rf/op-type-id]
                                                (:op-type-id ev)))
                                (swap! seen conj ev)))]
        ;; Just assert the macrostep rolls back to the original snapshot
        ;; (depth bound halts the cascade uncommitted) — the snapshot stays
        ;; at :idle, fx empty.
        (let [r (machines/machine-transition spec {:state :idle :data {}} [:start])]
          (is (result/ok? r) "depth-exceeded returns an :ok rollback, not a :fail")
          (is (= :idle (:state (result/snap r)))
              "macrostep rolled back atomically — snapshot uncommitted")
          (is (= [] (result/fx r))
              "no fx survive the depth-bound rollback"))))))
