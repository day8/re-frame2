(ns re-frame.machine-property-cljs-test
  "Property layer for the pure `machine-transition` engine: random valid
  machines and event sequences, run from `build-initial-snapshot`, must keep
  invariants that hold for ANY machine — INVARIANT 1 the state is a leaf,
  2 `:tags` is the active-configuration union, 4 transition is deterministic,
  5 `:rf/spawn-counter` never rewinds and user `:data` keys are never dropped,
  8 parallel selection ignores region order, 9 parallel `:always` rounds are
  parent-owned.

  The PRNG is a seeded 32-bit LCG, so the draws are identical on CLJ and CLJS
  and every failure is a stable repro."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [clojure.set :as set]
   [re-frame.machines :as rf.machines]
   [re-frame.machines.parallel :as rf.machines.parallel]
   [re-frame.machines.transition :as rf.machines.transition]))

;; ---- PRNG ------------------------------------------------------------------

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd
  "A draw in [0, n) from `state`; advance `state` with `lcg-next` separately."
  [state n]
  (mod (lcg-next state) n))

;; ---- generators ------------------------------------------------------------
;;
;; Every generator returns `[value next-state]`. The event alphabet is closed
;; so drawn events have a real chance of matching an `:on` key.

(def ^:private state-pool [:s0 :s1 :s2 :s3 :s4])
(def ^:private event-pool [:e0 :e1 :e2 :e3 :e4])
(def ^:private tag-pool [:tag/a :tag/b :tag/c :tag/d])

(def ^:private shared-guards
  {:g/true  (fn [_] true)
   :g/false (fn [_] false)
   :g/even? (fn [{d :data}] (even? (or (:n d) 0)))})

(def ^:private shared-actions
  {:a/bump (fn [{d :data}] {:data (update d :n (fnil inc 0))})
   :a/tag  (fn [{d :data}] {:data (assoc d :touched true)})})

(defn- gen-on-map
  "0..3 event → sibling edges; unless `bare?`, an edge may carry a `:data`-writing action."
  [state targets bare?]
  (let [n (rnd state 4)]
    (loop [i 0, s (lcg-next state), acc {}]
      (if (= i n)
        [acc s]
        (let [ev   (nth event-pool (rnd s (count event-pool)))
              tgt  (nth targets (rnd (lcg-next s) (count targets)))
              edge (if bare?
                     tgt
                     (case (rnd (lcg-next (lcg-next s)) 3)
                       0 tgt
                       1 {:target tgt :action :a/bump}
                       2 {:target tgt :action :a/tag}))]
          (recur (inc i) (lcg-next (lcg-next (lcg-next s)))
                 (assoc acc ev edge)))))))

(defn- gen-tags
  "0..2 tags; nil (no `:tags` slot) for 0, so tag elision is exercised."
  [state]
  (let [n (rnd state 3)]
    (if (zero? n)
      [nil (lcg-next state)]
      (loop [i 0, s (lcg-next state), acc #{}]
        (if (= i n)
          [acc s]
          (recur (inc i) (lcg-next s)
                 (conj acc (nth tag-pool (rnd s (count tag-pool))))))))))

(defn- gen-leaf-node
  "A leaf with an `:on` map and optional `:tags`, guarded `:always` and `:spawn`.
  An `independent?` leaf writes no `:data` and guards only on constants, so a
  sibling region cannot observe it."
  [state siblings spawn? independent?]
  (let [[on s1]   (gen-on-map state siblings independent?)
        [tags s2] (gen-tags s1)
        guards    (if independent? [:g/true :g/false] [:g/true :g/false :g/even?])
        always?   (zero? (rnd s2 2))
        guard     (nth guards (rnd (lcg-next s2) (count guards)))
        a-tgt     (nth siblings (rnd (lcg-next (lcg-next s2)) (count siblings)))
        s3        (lcg-next (lcg-next (lcg-next s2)))
        spawn?    (and spawn? (zero? (rnd s3 3)))
        node      (cond-> {}
                    (seq on) (assoc :on on)
                    tags     (assoc :tags tags)
                    always?  (assoc :always [{:guard guard :target a-tgt}])
                    spawn?   (assoc :spawn {:machine-id :child/worker
                                            :start      [:begin]}))]
    [node (lcg-next s3)]))

(defn- gen-flat-machine
  "2..4 leaf states, the first one `:initial`."
  [state spawn? independent?]
  (let [n     (+ 2 (rnd state 3))
        names (vec (take n state-pool))
        [states s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[node s1] (gen-leaf-node s names spawn? independent?)]
              (recur (inc i) s1 (assoc acc (nth names i) node)))))]
    [{:initial (first names)
      :data    {:n 0}
      :guards  shared-guards
      :actions shared-actions
      :states  states}
     s']))

(defn- gen-compound-machine
  "One tagged root compound state over 2..3 drawn leaves, so the tag union spans depth."
  [state]
  (let [n     (+ 2 (rnd state 2))
        names (vec (take n state-pool))
        [children s1]
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[node s'] (gen-leaf-node s names false false)]
              (recur (inc i) s' (assoc acc (nth names i) node)))))
        [root-tags s2] (gen-tags s1)]
    [{:initial :root
      :data    {:n 0}
      :guards  shared-guards
      :actions shared-actions
      :states  {:root (cond-> {:initial (first names)
                               :states  children}
                        root-tags (assoc :tags root-tags))}}
     s2]))

(defn- gen-parallel-machine
  "2..3 regions, each a flat machine's `:initial` / `:states`."
  [state independent?]
  (let [n (+ 2 (rnd state 2))
        [regions s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[fm s1] (gen-flat-machine s false independent?)]
              (recur (inc i) s1
                     (assoc acc (nth [:rA :rB :rC] i) (select-keys fm [:initial :states]))))))]
    [(rf.machines.parallel/install-region-cache
       {:type    :parallel
        :data    {:n 0}
        :guards  shared-guards
        :actions shared-actions
        :regions regions})
     s']))

(defn- gen-machine
  "A flat, compound or parallel machine."
  [state]
  (case (rnd state 3)
    0 (gen-flat-machine (lcg-next state) true false)
    1 (gen-compound-machine (lcg-next state))
    2 (gen-parallel-machine (lcg-next state) false)))

(defn- gen-events
  "1..8 events from `event-pool`."
  [state]
  (let [n (inc (rnd state 8))]
    (loop [i 0, s (lcg-next state), acc []]
      (if (= i n)
        [acc s]
        (recur (inc i) (lcg-next s)
               (conj acc [(nth event-pool (rnd s (count event-pool)))]))))))

;; ---- drivers ---------------------------------------------------------------

(defn- initial-snapshot [machine]
  (rf.machines.parallel/build-initial-snapshot machine {:bootstrap-pending? false}))

(defn- run-sequence
  "Every snapshot from `machine`'s initial one through `events`; a failed
  macrostep keeps the prior snapshot."
  [machine events]
  (reductions (fn [snap ev]
                (let [r (rf.machines/machine-transition machine snap ev)]
                  (if (= :ok (:status r)) (:snapshot r) snap)))
              (initial-snapshot machine)
              events))

(defn- final-snapshot [machine events] (last (run-sequence machine events)))

(defn- draw-with
  "A case drawer: a machine from `gen` plus an event sequence, as `[[machine events] next]`."
  [gen]
  (fn [state]
    (let [[m s1]   (gen state)
          [evs s2] (gen-events s1)]
      [[m evs] s2])))

(defn- first-failure
  "Draw `n` cases from `seed` and return the first non-nil `(check case)`."
  [seed n draw check]
  (loop [i 0, s seed]
    (when (< i n)
      (let [[c s'] (draw s)]
        (or (check c) (recur (inc i) (lcg-next s')))))))

(defn- reorder-regions
  "`machine` with its `:regions` declared in `order`. `:region-order` is
  restated because `install-region-cache` keeps a valid one unchanged, which
  would silently no-op the reorder."
  [machine order]
  (rf.machines.parallel/install-region-cache
    (assoc machine
           :regions      (into {} (map (fn [k] [k (get-in machine [:regions k])])) order)
           :region-order (vec order))))

;; ---- INVARIANT 1: state is always a leaf -----------------------------------

(defn- leaf? [node] (and (map? node) (not (contains? node :states))))

(defn- state-is-leaf? [machine state]
  (if (map? state)
    (every? (fn [[rn rstate]]
              (leaf? (rf.machines.transition/node-at (rf.machines.parallel/region-machine machine rn)
                                                     (rf.machines.transition/state-path rstate))))
            state)
    (leaf? (rf.machines.transition/node-at machine (rf.machines.transition/state-path state)))))

(deftest prop-state-is-always-a-leaf
  (is (nil? (first-failure 1001 400 (draw-with gen-machine)
              (fn [[m evs]]
                (when-let [bad (first (remove #(state-is-leaf? m (:state %)) (run-sequence m evs)))]
                  [m evs (:state bad)]))))))

;; ---- INVARIANT 2: :tags is the active-configuration union ------------------

(defn- path-tags
  "The union of `:tags` on every node along `path`, walked by hand down
  `states` rather than through the engine's own `compute-tags`, so the union
  itself is checked."
  [states path]
  (loop [m states, [k & more] path, acc #{}]
    (if-let [node (and k (get m k))]
      (recur (:states node) more (into acc (:tags node)))
      acc)))

(defn- as-path [state] (if (vector? state) state [state]))

(defn- expected-tags [machine state]
  (if (map? state)
    (reduce (fn [acc [rn rstate]]
              (into acc (path-tags (:states (rf.machines.parallel/region-machine machine rn))
                                   (as-path rstate))))
            #{}
            state)
    (path-tags (:states machine) (as-path state))))

(deftest prop-tags-is-active-configuration-union
  (testing ":tags equals the union of the active nodes' :tags, and is elided when that union is empty"
    (is (nil? (first-failure 2002 400 (draw-with gen-machine)
                (fn [[m evs]]
                  (some (fn [snap]
                          (let [expect (expected-tags m (:state snap))]
                            (when (if (seq expect)
                                    (not= expect (:tags snap))
                                    (contains? snap :tags))
                              [m evs (:state snap) expect (:tags snap)])))
                        (run-sequence m evs))))))))

;; ---- INVARIANT 4: transition is pure and deterministic ---------------------

(deftest prop-transition-is-pure-and-deterministic
  (testing "the same (machine, snapshot, event) yields an identical Result at every step"
    (is (nil? (first-failure 4004 400 (draw-with gen-machine)
                (fn [[m evs]]
                  (loop [snap (initial-snapshot m), [ev & more] evs]
                    (when ev
                      (let [r1 (rf.machines/machine-transition m snap ev)
                            r2 (rf.machines/machine-transition m snap ev)]
                        (if (= r1 r2)
                          (recur (if (= :ok (:status r1)) (:snapshot r1) snap) more)
                          [m (:state snap) ev]))))))))))

;; ---- INVARIANT 5: spawn-counter monotone, user :data keys never dropped ----

(deftest prop-spawn-counter-monotone-and-data-non-corrupt
  (testing ":rf/spawn-counter never rewinds, and :data stays a map that never loses a user key"
    (let [counter-total #(reduce + 0 (vals (:rf/spawn-counter %)))
          user-keys     #(set/intersection #{:n :touched} (set (keys %)))]
      (is (nil? (first-failure 5005 400 (draw-with gen-machine)
                  (fn [[m evs]]
                    (let [snaps (run-sequence m evs)
                          datas (map :data snaps)]
                      (cond
                        (not (apply <= (map counter-total snaps)))
                        [:counter-rewound m evs (map counter-total snaps)]

                        (not (every? map? datas))
                        [:data-not-a-map m evs datas]

                        (not (every? (fn [[a b]] (set/subset? (user-keys a) (set (keys b))))
                                     (partition 2 1 datas)))
                        [:user-key-dropped m evs datas])))))))))

;; ---- INVARIANT 8: parallel selection ignores region declaration order ------

(deftest prop-parallel-selection-is-declaration-order-independent
  (testing "reversing an independent parallel machine's :regions selects the same configuration and tags"
    (is (nil? (first-failure 8008 300 (draw-with #(gen-parallel-machine % true))
                (fn [[m evs]]
                  (let [outcome #((juxt :state :tags) (final-snapshot % evs))
                        a       (outcome m)
                        b       (outcome (reorder-regions m (reverse (keys (:regions m)))))]
                    (when (not= a b) [m evs a b]))))))))

;; Order independence is a law about selection within one frozen round, not
;; about the outcome (docs/machines/parallel-states.md): non-commuting writes
;; to one key change what the next round freezes, and so a sibling's target.
(deftest non-commuting-region-writes-are-declaration-order-sensitive
  (testing "regions :a and :b write :x non-commutatively on one event; region :c's :always
            target follows the frozen :x, so reversing :a/:b changes :c's resolved state"
    (let [base {:type    :parallel
                :data    {}
                :guards  {:x-is-1? (fn [{d :data}] (= 1 (:x d)))
                          :x-is-2? (fn [{d :data}] (= 2 (:x d)))}
                :actions {:set-x-1 (fn [{d :data}] {:data (assoc d :x 1)})
                          :set-x-2 (fn [{d :data}] {:data (assoc d :x 2)})}
                :regions {:a {:initial :s0
                              :states  {:s0 {:on {:ev {:target :s1 :action :set-x-1}}} :s1 {}}}
                          :b {:initial :s0
                              :states  {:s0 {:on {:ev {:target :s1 :action :set-x-2}}} :s1 {}}}
                          :c {:initial :c0
                              :states  {:c0    {:always [{:guard :x-is-2? :target :c-two}
                                                         {:guard :x-is-1? :target :c-one}]}
                                        :c-one {}
                                        :c-two {}}}}}
          run  (fn [order]
                 (let [s (final-snapshot (rf.machines.parallel/install-region-cache
                                           (assoc base :region-order order))
                                         [[:ev]])]
                   {:x (get-in s [:data :x]) :c (get-in s [:state :c])}))]
      (is (= [{:x 2 :c :c-two} {:x 1 :c :c-one}]
             [(run [:a :b :c]) (run [:b :a :c])])))))

;; ---- INVARIANT 9: parent-owned parallel :always rounds ---------------------
;;
;; After an event applies, the PARENT freezes configuration + `:data`, selects
;; every enabled regional `:always` against that frozen view, applies the set
;; in region order, and re-freezes until quiescent (Spec 005 §Per-region
;; `:always` / `:after` / `:spawn` scoping). So a region whose `:always` reads
;; a sibling's same-macrostep write converges in that macrostep under ANY
;; region order. A region-local settle loop fails that: a region drained before
;; the sibling it watches strands. Every write here is region-owned and
;; constant, so apply order cannot change `:data` — a divergence indicts
;; selection.

(def ^:private cross-region-names [:rA :rB :rC])

(defn- flag-key     [rn] (keyword (str "flag-"  (name rn))))
(defn- flag2-key    [rn] (keyword (str "flag2-" (name rn))))
(defn- flag-action  [rn] (keyword "a" (str "flag-"  (name rn))))
(defn- flag2-action [rn] (keyword "a" (str "flag2-" (name rn))))
(defn- flag-guard   [rn] (keyword "g" (str "flag-"  (name rn) "?")))
(defn- flag2-guard  [rn] (keyword "g" (str "flag2-" (name rn) "?")))

(def ^:private cross-region-actions
  (reduce (fn [acc rn]
            (assoc acc
                   (flag-action rn)  (fn [{d :data}] {:data (assoc d (flag-key rn) true)})
                   (flag2-action rn) (fn [{d :data}] {:data (assoc d (flag2-key rn) true)})))
          {}
          cross-region-names))

(def ^:private cross-region-guards
  (reduce (fn [acc rn]
            (assoc acc
                   (flag-guard rn)  (fn [{d :data}] (true? (get d (flag-key rn))))
                   (flag2-guard rn) (fn [{d :data}] (true? (get d (flag2-key rn))))))
          {}
          cross-region-names))

(defn- cross-region-body
  "Region `self`, whose `:always` guards read `watched`'s flags:

    :s0 --ev--> :s1                          (writes flag-self)
    :s0/:s1 --:always flag-watched?--> :s2   (writes flag2-self)
    :s2 --:always flag2-watched?--> :s3      (drawn half the time)

  The second tier can only fire a round after the first, which exercises the
  re-freeze between rounds. `:s3` is terminal, so every draw settles."
  [state self watched]
  (let [ev     (nth event-pool (rnd state (count event-pool)))
        tier2? (zero? (rnd (lcg-next state) 2))
        tier1  [{:guard (flag-guard watched) :target :s2 :action (flag2-action self)}]
        body   {:initial :s0
                :states
                (cond-> {:s0 {:tags   #{(keyword (name self) "s0")}
                              :on     {ev {:target :s1 :action (flag-action self)}}
                              :always tier1}
                         :s1 {:tags   #{(keyword (name self) "s1")}
                              :always tier1}
                         :s2 {:tags #{(keyword (name self) "s2")}}}
                  tier2?
                  (-> (assoc-in [:s2 :always] [{:guard (flag2-guard watched) :target :s3}])
                      (assoc :s3 {:tags #{(keyword (name self) "s3")}})))}]
    [body (lcg-next (lcg-next state))]))

(defn- gen-cross-region-parallel-machine
  "2..3 regions where region i watches region (i+1 mod n), so the watch
  relation is a cycle."
  [state]
  (let [n     (+ 2 (rnd state 2))
        names (vec (take n cross-region-names))
        [regions s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [self      (nth names i)
                  [body s1] (cross-region-body s self (nth names (mod (inc i) n)))]
              (recur (inc i) s1 (assoc acc self body)))))]
    [(rf.machines.parallel/install-region-cache
       {:type    :parallel
        :data    {}
        :guards  (merge shared-guards cross-region-guards)
        :actions (merge shared-actions cross-region-actions)
        :regions regions})
     s']))

(defn- permutations [coll]
  (if (<= (count coll) 1)
    [(vec coll)]
    (vec (mapcat (fn [x] (map #(vec (cons x %)) (permutations (remove #{x} coll)))) coll))))

(deftest prop-parallel-always-rounds-are-parent-owned
  (testing "coupled regions resolve to the same state, data and tags under every :regions order"
    (is (nil? (first-failure 9009 300 (draw-with gen-cross-region-parallel-machine)
                (fn [[m evs]]
                  (let [outcome #((juxt :state :data :tags) (final-snapshot % evs))
                        base    (outcome m)]
                    (some (fn [order]
                            (let [got (outcome (reorder-regions m order))]
                              (when (not= base got)
                                {:regions (:regions m) :events evs :order order :base base :got got})))
                          (permutations (keys (:regions m)))))))))))
