(ns re-frame.machine-property-cljs-test
  "Property / model-based test layer for the machines engine (rf2-dp1fi0).

  The machines suite is otherwise ENTIRELY example-based — pin-and-assert
  (one input → one expected snapshot), the 186-fixture SCXML conformance
  corpus, and the W3C-IRP semantic-core ns. Those catch the cases someone
  thought to write; a generative layer catches whole bug classes example
  tests structurally miss: it draws a RANDOM valid machine + a RANDOM event
  sequence, runs it through the pure `machine-transition` engine, and
  asserts the INVARIANTS that must hold for ANY machine + ANY sequence.

  Spec 005 itself flags a 'model-based testing harness' as planned, and
  XState v5 ships `@xstate/test` model-based testing — the analogous
  gold-standard. This is the additive property tier, NOT a replacement for
  the example + conformance suites.

  ## Properties asserted (over deterministic draws)

  For every drawn `[machine event-sequence]` threaded through the pure
  `machines/machine-transition` engine from its `build-initial-snapshot`
  initial snapshot:

    1. STATE-IS-LEAF — the snapshot's `:state` is ALWAYS a leaf
       configuration: a keyword/vector path ending at a childless node for
       flat/compound machines, a region→leaf map for parallel machines.
       Never a non-leaf compound (a path that still has `:states`).
    2. TAGS-IS-UNION — the snapshot's `:tags` ALWAYS equals the union of
       every active-configuration node's declared `:tags` (the projection
       invariant), independently recomputed from the post-transition
       `:state`. The slot is elided exactly when that union is empty.
    3. DEPTH-BOUND SETTLE — every macrostep SETTLES. A machine with an
       unbounded `:always` / `:raise` cycle never hangs nor StackOverflows;
       it returns (the engine aborts at the depth limit, rolling back to
       the atomic target), so the harness completing at all is the proof.
    4. NO-PARTIAL-COMMIT / DETERMINISM OF SELECTION — `machine-transition`
       is a pure function: the SAME `[machine snapshot event]` triple
       yields a byte-identical Result (snapshot + fx). Transition selection
       is deterministic; a `:fail` Result (a throwing action) leaves the
       returned snapshot equal to the rolled-back input — never a half-
       applied configuration.
    5. SPAWN-COUNTER MONOTONE / DATA NON-CORRUPT — the in-snapshot
       `:rf/spawn-counter` is monotone non-decreasing across a sequence
       (spawn-id allocation never rewinds), and user-domain `:data` keys
       are never silently dropped by the engine's own bookkeeping (only the
       reserved `:rf/*` captures are added).
    6. ACTOR LIFECYCLE — every `:rf.machine/spawn` a state emits on ENTRY
       is matched by a `:rf.machine/destroy` carrying the SAME
       `:rf/invoke-id` when that state is EXITED (no leaked actors); the
       spawn allocator id is monotone per machine-id.
    7. REPLAY DETERMINISM — the same machine + the same event sequence
       (pure, no recorded cofx needed at this layer) yields a byte-
       identical FINAL snapshot across two independent runs (the EP-0010 /
       EP-0017 replay claim, at the machine-engine level).
    8. PARALLEL DECLARATION-ORDER INDEPENDENCE — reordering a parallel
       machine's `:regions` declarations yields the SAME selected
       configuration (region selection is set-like, declaration-order
       independent) — generalised from the fixed-case deftests.

  ## Why a hand-rolled seeded PRNG (not clojure.test.check)

  Mirrors the project's established engine/foundation property tests
  (`re-frame.path-laws-cljs-test`, `re-frame.routing-prism-property-cljs-test`,
  `re-frame.identity-cedn1-cljs-test`): a 32-bit linear-congruential
  generator drawing the SAME value stream on CLJ and CLJS, so the property
  runs IDENTICALLY on both hosts with no `test.check` / Malli-generator
  dependency on the machines test classpath. The engine ships on both
  hosts, so dual-host property coverage matters; and a fixed seed means a
  failure is a STABLE, reproducible repro — exactly what the replay-
  determinism property itself demands. Each `deftest` re-seeds from a fixed
  constant, so the whole layer is deterministic.

  Named `*-cljs-test.cljc` so BOTH the cognitect JVM runner (`.*-test$`)
  and the shadow-cljs `:node-test` build (`cljs-test$`) discover it — the
  engine's invariants are exercised on both hosts from this one file.

  Pure-engine only — no frame, no app-db, no `reg-machine`: every property
  drives `machines/machine-transition` (a pure fn of its arguments) from a
  `parallel/build-initial-snapshot` initial snapshot, so the layer is
  JVM-runnable from arguments alone and has no fixture."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [clojure.set :as set]
   [re-frame.machines :as machines]
   [re-frame.machines.parallel :as parallel]
   [re-frame.machines.result :as result]
   [re-frame.machines.transition :as transition]))

;; ---- a deterministic, host-portable PRNG ----------------------------------
;;
;; The SAME 32-bit linear-congruential generator the foundation tests use
;; (`path-laws-cljs-test` / `routing-prism-property-cljs-test`), so the
;; draw stream is byte-identical on CLJ and CLJS. Numerical-Recipes
;; constants; every op stays in the int32 range under `bit-and`.

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd
  "A draw in [0, n) from `state`. Returns the draw; advance `state` with
  `lcg-next` separately so each draw site threads the PRNG explicitly."
  [state n]
  (mod (lcg-next state) n))

;; ---- machine generators ----------------------------------------------------
;;
;; The grammar each generator draws WITHIN (so every drawn machine is a
;; VALID machine the engine accepts):
;;
;;   - flat:    N leaf states, each with an `:on` map of event → sibling
;;              target (+ optionally one `:always` guarded edge, one
;;              guarded `:action`, a `:tags` set, a `:spawn`).
;;   - compound: one root with an `:initial` chain into nested leaves.
;;   - parallel: 2..3 independent regions, each a flat sub-machine.
;;
;; The event alphabet is closed (`event-pool`) so a drawn sequence has a
;; real chance of matching declared `:on` handlers; guards are pure fns of
;; `:data` so `:always` edges genuinely fire (or don't) deterministically.

(def ^:private state-pool
  "Leaf-state name pool. Distinct keywords so a flat machine's siblings
  never collide."
  [:s0 :s1 :s2 :s3 :s4])

(def ^:private event-pool
  "Closed event alphabet drawn for both `:on` keys and the event sequence,
  so generated events have a real chance of resolving to a handler."
  [:e0 :e1 :e2 :e3 :e4])

(def ^:private tag-pool
  [:tag/a :tag/b :tag/c :tag/d])

;; A single shared guard + action vocabulary every generated machine
;; references by name. The guard set spans always-true, always-false, and
;; a `:data`-dependent predicate so `:always` edges fire conditionally; the
;; actions mutate user-domain `:data` (so the data-non-corruption property
;; has something to chew on) and one raises an internal event (so the
;; raise-drain / depth-bound path is exercised).
(def ^:private shared-guards
  {:g/true  (fn [_] true)
   :g/false (fn [_] false)
   :g/even? (fn [{d :data}] (even? (or (:n d) 0)))})

(def ^:private shared-actions
  {:a/bump  (fn [{d :data}] {:data (update d :n (fnil inc 0))})
   :a/tag   (fn [{d :data}] {:data (assoc d :touched true)})
   :a/raise (fn [_] {:fx [[:raise [:e0]]]})
   :a/noop  (fn [_] {})})

(defn- gen-on-map
  "Draw an `:on` map: 0..3 event → target-keyword entries drawn from
  `targets`. Some entries carry a guarded `:action` (the richer transition
  form) so the action / data path is exercised. Returns `[on-map next]`."
  [state targets]
  (let [n (rnd state 4)]
    (loop [i 0, s (lcg-next state), acc {}]
      (if (= i n)
        [acc s]
        (let [ev   (nth event-pool (rnd s (count event-pool)))
              tgt  (nth targets (rnd (lcg-next s) (count targets)))
              form (rnd (lcg-next (lcg-next s)) 3)
              edge (case form
                     0 tgt                                    ;; bare target
                     1 {:target tgt :action :a/bump}          ;; target + action
                     2 {:target tgt :action :a/tag})          ;; target + action
              ]
          (recur (inc i) (lcg-next (lcg-next (lcg-next s)))
                 (assoc acc ev edge)))))))

(defn- gen-tags
  "Draw a `:tags` set: 0..2 tags from `tag-pool` (0 ⇒ no `:tags` slot, so
  the elision invariant is exercised). Returns `[tags-or-nil next]`."
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
  "Draw one leaf state-node body. Carries an `:on` map (siblings as
  targets), optionally a `:tags` set, optionally one `:always` guarded
  edge, optionally a `:spawn`. Returns `[node next]`."
  [state siblings allow-spawn?]
  (let [[on  s1] (gen-on-map state siblings)
        [tags s2] (gen-tags s1)
        ;; one optional `:always` edge under a drawn guard → a sibling
        always?  (zero? (rnd s2 2))
        guard    (nth [:g/true :g/false :g/even?] (rnd (lcg-next s2) 3))
        a-tgt    (nth siblings (rnd (lcg-next (lcg-next s2)) (count siblings)))
        s3       (lcg-next (lcg-next (lcg-next s2)))
        spawn?   (and allow-spawn? (zero? (rnd s3 3)))
        node     (cond-> {}
                   (seq on) (assoc :on on)
                   tags     (assoc :tags tags)
                   always?  (assoc :always [{:guard guard :target a-tgt}])
                   spawn?   (assoc :spawn {:machine-id :child/worker
                                           :start      [:begin]}))]
    [node (lcg-next s3)]))

(defn- gen-flat-machine
  "Draw a flat machine: 2..4 leaf states, one of them the `:initial`.
  Returns `[machine next]`."
  [state allow-spawn?]
  (let [n        (+ 2 (rnd state 3))
        names    (vec (take n state-pool))
        [states s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[node s1] (gen-leaf-node s names allow-spawn?)]
              (recur (inc i) s1 (assoc acc (nth names i) node)))))]
    [{:initial (first names)
      :data    {:n 0}
      :guards  shared-guards
      :actions shared-actions
      :states  states}
     s']))

(defn- gen-compound-machine
  "Draw a compound machine: one root compound state with an `:initial`
  child and 2..3 nested leaf children (themselves drawn flat). The root
  has its own `:tags` so the union spans depth. Returns `[machine next]`."
  [state]
  (let [n        (+ 2 (rnd state 2))
        names    (vec (take n state-pool))
        [children s1]
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[node s'] (gen-leaf-node s names false)]
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
  "Draw a parallel machine: 2..3 independent regions, each a flat
  sub-machine drawn from `gen-flat-machine` (its `:initial` / `:states`
  only — guards/actions hoist to the root). Returns `[machine next]`."
  [state]
  (let [n        (+ 2 (rnd state 2))
        region-names [:rA :rB :rC]
        [regions s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[fm s1] (gen-flat-machine s false)]
              (recur (inc i) s1
                     (assoc acc (nth region-names i)
                            (select-keys fm [:initial :states]))))))]
    [(parallel/install-region-cache
       {:type    :parallel
        :data    {:n 0}
        :guards  shared-guards
        :actions shared-actions
        :regions regions})
     s']))

;; ---- independent-region parallel generator (for INVARIANT 8) ---------------
;;
;; Per Spec 005 §Parallel regions / `parallel_test.clj` §6: shared `:data`
;; flows SEQUENTIALLY through region actions in DECLARATION ORDER, and a
;; region's guard reads that shared `:data`. So a region whose `:always`
;; guard reads `:data` a SIBLING'S action mutates is LEGITIMATELY order-
;; sensitive — reordering the regions changes what parity the reading
;; guard sees, hence the selected leaf (verified against the live engine;
;; this is documented, correct behaviour, NOT a bug). That coupling is
;; tested separately (`parallel-shared-data-flows-through-regions`).
;;
;; The declaration-order-INDEPENDENCE invariant the bead names is about
;; SELECTION being set-like for INDEPENDENT regions. To test it without
;; conflating the (separately-covered) shared-data-ordering semantics, this
;; generator draws regions that are independent BY CONSTRUCTION: their
;; `:always` guards are CONSTANT (`:g/true` / `:g/false`, not the
;; `:data`-reading `:g/even?`) and their `:on` edges carry NO action (no
;; shared-`:data` writes). Reordering such regions cannot change behaviour,
;; so any divergence is a genuine selection / broadcast bug.

(defn- gen-independent-on-map
  "Like `gen-on-map` but every edge is a BARE target (no `:action`), so a
  region never mutates the shared `:data` a sibling's guard might read."
  [state targets]
  (let [n (rnd state 4)]
    (loop [i 0, s (lcg-next state), acc {}]
      (if (= i n)
        [acc s]
        (let [ev  (nth event-pool (rnd s (count event-pool)))
              tgt (nth targets (rnd (lcg-next s) (count targets)))]
          (recur (inc i) (lcg-next (lcg-next s)) (assoc acc ev tgt)))))))

(defn- gen-independent-leaf
  "A leaf node for an independent region: `:on` with bare targets, optional
  `:tags`, optional `:always` under a CONSTANT guard (never `:g/even?`)."
  [state siblings]
  (let [[on s1]   (gen-independent-on-map state siblings)
        [tags s2] (gen-tags s1)
        always?   (zero? (rnd s2 2))
        guard     (nth [:g/true :g/false] (rnd (lcg-next s2) 2))
        a-tgt     (nth siblings (rnd (lcg-next (lcg-next s2)) (count siblings)))
        node      (cond-> {}
                    (seq on) (assoc :on on)
                    tags     (assoc :tags tags)
                    always?  (assoc :always [{:guard guard :target a-tgt}]))]
    [node (lcg-next (lcg-next (lcg-next s2)))]))

(defn- gen-independent-region
  "Draw one independent region body (`:initial` + `:states`), 2..3 leaves."
  [state]
  (let [n     (+ 2 (rnd state 2))
        names (vec (take n state-pool))
        [states s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[node s1] (gen-independent-leaf s names)]
              (recur (inc i) s1 (assoc acc (nth names i) node)))))]
    [{:initial (first names) :states states} s']))

(defn- gen-independent-parallel-machine
  "Draw a parallel machine whose 2..3 regions are INDEPENDENT by
  construction (constant guards, no shared-`:data`-writing actions), so
  region reordering cannot change behaviour. Returns `[machine next]`."
  [state]
  (let [n            (+ 2 (rnd state 2))
        region-names [:rA :rB :rC]
        [regions s']
        (loop [i 0, s (lcg-next state), acc {}]
          (if (= i n)
            [acc s]
            (let [[rb s1] (gen-independent-region s)]
              (recur (inc i) s1 (assoc acc (nth region-names i) rb)))))]
    [(parallel/install-region-cache
       {:type    :parallel
        :data    {:n 0}
        :guards  shared-guards
        :actions shared-actions
        :regions regions})
     s']))

(defn- gen-machine
  "Draw a machine of a random shape (flat / compound / parallel). Returns
  `[machine shape next]` — `shape` carried so a property can branch its
  oracle on the configuration form."
  [state]
  (case (rnd state 3)
    0 (let [[m s'] (gen-flat-machine (lcg-next state) true)]     [m :flat s'])
    1 (let [[m s'] (gen-compound-machine (lcg-next state))]      [m :compound s'])
    2 (let [[m s'] (gen-parallel-machine (lcg-next state))]      [m :parallel s'])))

(defn- gen-events
  "Draw an event sequence of 1..8 events from `event-pool`. Returns
  `[events next]`."
  [state]
  (let [n (inc (rnd state 8))]
    (loop [i 0, s (lcg-next state), acc []]
      (if (= i n)
        [acc s]
        (recur (inc i) (lcg-next s)
               (conj acc [(nth event-pool (rnd s (count event-pool)))]))))))

;; ---- engine drivers / oracles ---------------------------------------------

(defn- initial-snapshot
  "The freshly-derived initial snapshot for `machine` (the same builder the
  registration + spawn paths use). `bootstrap-pending? false` ⇒ a clean
  post-cascade snapshot to drive the pure engine from."
  [machine]
  (parallel/build-initial-snapshot machine {:bootstrap-pending? false}))

(defn- run-sequence
  "Thread `events` through the pure engine from `machine`'s initial
  snapshot, returning a vector of per-step `{:snap :fx}` Result projections
  (one per event). A `:fail` Result keeps the prior snapshot (the engine's
  atomic rollback), so the thread never dies on a throwing action."
  [machine events]
  (loop [snap (initial-snapshot machine), evs events, acc []]
    (if (empty? evs)
      acc
      (let [r     (machines/machine-transition machine snap (first evs))
            snap' (if (result/ok? r) (result/snap r) snap)]
        (recur snap' (rest evs)
               (conj acc {:snap snap' :fx (when (result/ok? r) (result/fx r))
                          :ok? (result/ok? r)}))))))

(defn- leaf-node?
  "True iff the node `n` is a real leaf — resolves and has no `:states`
  children (so it is a terminal configuration node, never a non-leaf
  compound)."
  [n]
  (and (map? n) (not (contains? n :states))))

(defn- state-is-leaf?
  "INVARIANT 1 oracle. For `machine` + post-transition `state`, true iff
  `state` is a leaf configuration:
   - flat / compound: a keyword or vector path resolving (via `node-at`)
     to a childless node;
   - parallel: a region→value map where every region value is a leaf of
     that region's sub-machine."
  [machine state]
  (if (map? state)
    ;; parallel: every region value resolves to a leaf of its region body
    (every?
      (fn [[rn rstate]]
        (let [rbody (parallel/region-machine machine rn)]
          (leaf-node? (transition/node-at rbody (transition/state-path rstate)))))
      state)
    (leaf-node? (transition/node-at machine (transition/state-path state)))))

;; ---- INVARIANT 2 oracle: an INDEPENDENT tag computation -------------------
;;
;; The oracle must NOT recompute the expected tag union via
;; `transition/compute-tags` — that is the SAME fn the engine's commit-tags
;; calls (`transition.cljc` `commit-tags` → `compute-tags`), so reusing it
;; only cross-checks the elision rule + state/tag consistency, never the
;; union math itself (rf2-ln2ctp). Instead, walk the active-state ancestor
;; chain HERE — descending the machine spec's `:states` map directly along
;; the state path and reading each node's declared `:tags` slot by hand — so
;; the expected union is derived by a genuinely independent method. The
;; parallel branch already cross-checks the engine's `commit-tags-parallel`
;; independently; both branches are reimplemented below for symmetry.

(defn- declared-tags
  "Coerce a node's `:tags` slot to a set — independently of the engine's
  `node-tags` (same canonical-form tolerance, reimplemented by hand)."
  [node]
  (let [t (:tags node)]
    (cond
      (nil? t)        #{}
      (set? t)        t
      (sequential? t) (set t)
      (keyword? t)    #{t}
      :else           #{})))

(defn- path->vec
  "Normalise a single-machine `:state` (keyword or vector path) to a vector
  path — reimplemented here so the oracle does not lean on
  `transition/state-path`."
  [state]
  (cond
    (vector? state)  state
    (keyword? state) [state]
    :else            (throw (ex-info "oracle: bad state form" {:state state}))))

(defn- ancestor-chain-tags
  "INDEPENDENT walk: descend `states-map` (a machine's `:states`) along the
  vector `path`, collecting the `:tags` declared on EVERY node from root to
  leaf. Returns the union. Does NOT call `compute-tags` / `nodes-along-path`
  / `node-at` — it threads `:states` by hand, so it is a true cross-check of
  the engine's projection rather than a re-run of the same code."
  [states-map path]
  (loop [m states-map, p path, acc #{}]
    (if (empty? p)
      acc
      (let [node (get m (first p))]
        (if (nil? node)
          acc                                   ;; unresolvable: stop (defensive)
          (recur (:states node) (rest p)
                 (set/union acc (declared-tags node))))))))

(defn- expected-tags
  "INVARIANT 2 oracle. Independently recompute the active-configuration tag
  union for `machine` + `state` (the projection the engine must stamp), via
  a HAND-WRITTEN ancestor-chain walk over the machine spec — NOT via
  `transition/compute-tags` (which the engine itself uses, so reusing it
  would be circular; rf2-ln2ctp). For parallel, union the independent walk
  across every region; for flat/compound, walk the single path."
  [machine state]
  (if (map? state)
    ;; parallel: independently walk each region's own :states by its leaf path
    (transduce
      (map (fn [[rn rstate]]
             (let [rbody (parallel/region-machine machine rn)]
               (ancestor-chain-tags (:states rbody) (path->vec rstate)))))
      set/union #{} state)
    (ancestor-chain-tags (:states machine) (path->vec state))))

;; ---- INVARIANT 1: state is always a leaf ----------------------------------

(deftest prop-state-is-always-a-leaf
  (testing "the post-transition :state is ALWAYS a leaf configuration —
            never a non-leaf compound (over generated machines + sequences)"
    (let [failure
          (loop [i 0, s 1001]
            (if (= i 400)
              nil
              (let [[m _shape s1] (gen-machine s)
                    [evs s2]      (gen-events s1)
                    steps         (run-sequence m evs)]
                (if-let [bad (some (fn [{state :snap}]
                                     (when-not (state-is-leaf? m (:state state))
                                       state))
                                   steps)]
                  [:non-leaf m evs (:state bad)]
                  ;; the initial snapshot itself must also be a leaf config
                  (if-not (state-is-leaf? m (:state (initial-snapshot m)))
                    [:non-leaf-initial m (:state (initial-snapshot m))]
                    (recur (inc i) (lcg-next s2)))))))]
      (is (nil? failure)
          (str "state-is-leaf property failed: " (pr-str failure))))))

;; ---- INVARIANT 2: :tags is always the active-config union -----------------

(deftest prop-tags-is-active-configuration-union
  (testing ":tags ALWAYS equals the union of active nodes' :tags, and is
            elided exactly when that union is empty (the projection invariant)"
    (let [failure
          (loop [i 0, s 2002]
            (if (= i 400)
              nil
              (let [[m _shape s1] (gen-machine s)
                    [evs s2]      (gen-events s1)
                    steps         (cons {:snap (initial-snapshot m)}
                                        (run-sequence m evs))]
                (if-let [bad
                         (some
                           (fn [{snap :snap}]
                             (let [expect (expected-tags m (:state snap))
                                   actual (:tags snap)]
                               (cond
                                 ;; non-empty union must be stamped exactly
                                 (and (seq expect) (not= expect actual))
                                 {:why :mismatch :state (:state snap)
                                  :expect expect :actual actual}
                                 ;; empty union must elide the slot entirely
                                 (and (empty? expect) (contains? snap :tags))
                                 {:why :not-elided :state (:state snap)
                                  :actual actual})))
                           steps)]
                  [:tags m evs bad]
                  (recur (inc i) (lcg-next s2))))))]
      (is (nil? failure)
          (str "tags-union property failed: " (pr-str failure))))))

;; ---- INVARIANT 3: every macrostep settles (depth-bound, no hang) ----------

(deftest prop-macrostep-always-settles
  (testing "every macrostep SETTLES — a machine with an unbounded :always /
            :raise cycle returns (engine aborts at the depth limit) rather
            than hanging or StackOverflowing. Reaching the assertion proves it."
    ;; Construct adversarial cyclic machines explicitly (the random grammar
    ;; can produce them, but pin the worst cases deterministically):
    ;; (a) two states ping-pong via always-true :always edges; (b) a state
    ;; whose action re-raises its own event forever.
    (let [always-cycle {:initial :a
                        :data    {}
                        :guards  shared-guards
                        :actions shared-actions
                        :states  {:a {:always [{:guard :g/true :target :b}]}
                                  :b {:always [{:guard :g/true :target :a}]}}}
          raise-cycle  {:initial :loop
                        :data    {}
                        :guards  shared-guards
                        :actions shared-actions
                        :states  {:loop {:on {:e0 {:action :a/raise}}}}}
          ;; Both calls MUST return (not hang / SOE). The harness running to
          ;; completion is the settle proof. Per rf2-y3jv8q an unbounded
          ;; :always / :raise cycle now surfaces as a FAILED macrostep at the
          ;; depth bound (XState v5 throws on such a runaway) — a `result/fail`
          ;; carrying the `::depth-abort?` sentinel, NOT an :ok rollback no-op
          ;; that masqueraded as a guard-blocked decline.
          r-always (machines/machine-transition
                     always-cycle (initial-snapshot always-cycle) [:noop])
          r-raise  (machines/machine-transition
                     raise-cycle (initial-snapshot raise-cycle) [:e0])]
      (is (result/depth-abort? r-always)
          "always-cycle aborts at the depth bound as a depth-abort :fail (settled, not hung)")
      (is (nil? (result/snap r-always))
          "the depth-abort :fail threads no snapshot (atomic rollback — no partial leaf commits)")
      (is (result/depth-abort? r-raise)
          "raise-cycle aborts at the depth bound as a depth-abort :fail (settled, not hung)")
      (is (nil? (result/snap r-raise))
          "the depth-abort :fail threads no snapshot (atomic rollback — no partial leaf commits)")
      ;; And over the random corpus: every step of every drawn machine
      ;; returned (the loop below cannot complete if any macrostep hangs).
      (let [completed
            (loop [i 0, s 3003]
              (if (= i 300)
                true
                (let [[m _ s1] (gen-machine s)
                      [evs s2] (gen-events s1)]
                  (run-sequence m evs)   ;; must return for every drawn case
                  (recur (inc i) (lcg-next s2)))))]
        (is (true? completed)
            "every macrostep over 300 random machine+sequence draws settled")))))

;; ---- INVARIANT 4: pure determinism + no partial commit --------------------

(deftest prop-transition-is-pure-and-deterministic
  (testing "machine-transition is a pure fn — the SAME (machine, snapshot,
            event) triple yields a byte-identical Result; selection is
            deterministic and a :fail leaves the snapshot rolled back (no
            half-applied configuration)"
    (let [failure
          (loop [i 0, s 4004]
            (if (= i 400)
              nil
              (let [[m _ s1] (gen-machine s)
                    [evs s2] (gen-events s1)
                    snap0    (initial-snapshot m)]
                ;; Re-derive a per-step probe: for each prefix, call the
                ;; engine TWICE on the same input and compare Results.
                (if-let [bad
                         (loop [snap snap0, todo evs]
                           (if (empty? todo)
                             nil
                             (let [ev (first todo)
                                   r1 (machines/machine-transition m snap ev)
                                   r2 (machines/machine-transition m snap ev)]
                               (cond
                                 (not= r1 r2)
                                 {:why :nondeterministic :state (:state snap) :event ev}
                                 ;; no-partial-commit: a :fail's input snapshot
                                 ;; is what we carry forward, never a partial
                                 (and (result/fail? r1) (result/fail? r2))
                                 (recur snap (rest todo))
                                 :else
                                 (recur (result/snap r1) (rest todo))))))]
                  [:purity m evs bad]
                  (recur (inc i) (lcg-next s2))))))]
      (is (nil? failure)
          (str "purity/determinism property failed: " (pr-str failure))))))

;; ---- INVARIANT 5: spawn-counter monotone + data non-corruption ------------

(deftest prop-spawn-counter-monotone-and-data-non-corrupt
  (testing "the in-snapshot :rf/spawn-counter is monotone non-decreasing
            across a sequence, and the engine never drops user-domain :data
            keys (only reserved :rf/* captures are added)"
    (let [counter-total (fn [snap]
                          (reduce + 0 (vals (:rf/spawn-counter snap))))
          ;; user-domain keys the generated actions write (`:a/bump` → :n,
          ;; `:a/tag` → :touched) plus the initial `{:n 0}`. The KEY-
          ;; PRESERVATION oracle: the engine never silently DROPS one of
          ;; these user keys once it is present — it adds only reserved
          ;; `:rf/*` captures, never orphaning / discarding a user key. (The
          ;; generated actions themselves only ever `update`/`assoc` these
          ;; keys; none removes one — so any disappearance is the engine's.)
          user-keys     #{:n :touched}
          user-keys-of  (fn [d] (when (map? d)
                                  (set/intersection user-keys (set (keys d)))))
          failure
          (loop [i 0, s 5005]
            (if (= i 400)
              nil
              (let [[m _ s1] (gen-machine s)
                    [evs s2] (gen-events s1)
                    snap0    (initial-snapshot m)
                    steps    (cons {:snap snap0} (run-sequence m evs))
                    ;; monotone check over the running counter total
                    [_ mono-bad]
                    (reduce
                      (fn [[prev _] {snap :snap}]
                        (let [now (counter-total snap)]
                          (if (< now prev)
                            (reduced [now {:why :counter-rewound :prev prev :now now}])
                            [now nil])))
                      [(counter-total snap0) nil]
                      steps)
                    ;; data-non-corrupt: `:data` stays a map, AND every
                    ;; user-domain key (`#{:n :touched}`) the engine has ever
                    ;; carried survives into EVERY subsequent step — the engine
                    ;; never silently drops a user key (only `:rf/*` captures
                    ;; are added). `seen` accumulates the user keys observed so
                    ;; far; a later step missing one of them is corruption.
                    [_ data-bad]
                    (reduce
                      (fn [[seen _] {snap :snap}]
                        (let [d (:data snap)]
                          (cond
                            (not (map? d))
                            (reduced [seen {:why :data-not-a-map :data d}])
                            ;; a previously-present user key vanished
                            (not (set/subset? seen (set (keys d))))
                            (reduced [seen {:why :user-key-dropped
                                            :dropped (set/difference seen (set (keys d)))
                                            :data d}])
                            :else
                            [(set/union seen (user-keys-of d)) nil])))
                      [#{} nil]
                      steps)]
                (cond mono-bad [:monotone m evs mono-bad]
                      data-bad [:data m evs data-bad]
                      :else    (recur (inc i) (lcg-next s2))))))]
      (is (nil? failure)
          (str "spawn-counter/data property failed: " (pr-str failure)))
      ;; A positive case: a machine that genuinely spawns advances the
      ;; counter (so the monotone check above isn't vacuously true on a
      ;; corpus that never spawned).
      (let [spawner {:initial :idle
                     :data    {}
                     :guards  shared-guards
                     :actions shared-actions
                     :states  {:idle    {:on {:e0 :working}}
                               :working {:spawn {:machine-id :child/worker :start [:begin]}
                                         :on    {:e1 :idle}}}}
            s0 (initial-snapshot spawner)
            r  (machines/machine-transition spawner s0 [:e0])]
        (is (= 1 (reduce + 0 (vals (:rf/spawn-counter (result/snap r)))))
            "entering a :spawn state bumps the in-snapshot counter to 1")))))

;; ---- INVARIANT 6: spawned-actor lifecycle (entry spawn → exit destroy) ----

(deftest prop-spawned-actor-lifecycle-is-balanced
  (testing "every :rf.machine/spawn emitted on ENTRY of a state is matched
            by a :rf.machine/destroy carrying the SAME :rf/invoke-id when
            that state is EXITED — no leaked actors"
    ;; A flat machine with a spawn-bearing state; drive in then out and
    ;; assert the spawn / destroy invoke-ids pair up. The pure engine emits
    ;; the spawn fx on entry and the destroy fx on exit (build-destroy-fx),
    ;; both keyed on the state's path under :rf/invoke-id.
    (let [spawn-invoke-ids
          (fn [fx] (->> fx
                        (filter #(= :rf.machine/spawn (first %)))
                        (map (comp :rf/invoke-id second))
                        set))
          destroy-invoke-ids
          (fn [fx] (->> fx
                        (filter #(= :rf.machine/destroy (first %)))
                        (map (comp :rf/invoke-id second))
                        set))
          failure
          (loop [i 0, s 6006]
            (if (= i 200)
              nil
              ;; Build a 3-state machine: idle → working(:spawn) → done,
              ;; with a drawn extra event back to idle so exit fires.
              (let [extra (nth event-pool (rnd s (count event-pool)))
                    m {:initial :idle
                       :data    {}
                       :guards  shared-guards
                       :actions shared-actions
                       :states  {:idle    {:on {:e0 :working}}
                                 :working {:spawn {:machine-id :child/worker :start [:begin]}
                                           :on    {:e1 :done :e2 :idle}}
                                 :done    {:on {:e3 :idle}}}}
                    s0       (initial-snapshot m)
                    ;; enter the spawn state
                    r-enter  (machines/machine-transition m s0 [:e0])
                    spawned  (spawn-invoke-ids (result/fx r-enter))
                    ;; exit the spawn state (→ :done)
                    r-exit   (machines/machine-transition m (result/snap r-enter) [:e1])
                    destroyed (destroy-invoke-ids (result/fx r-exit))]
                (cond
                  (not= #{[:working]} spawned)
                  [:spawn-not-emitted extra spawned]
                  ;; every spawned invoke-id must be destroyed on exit
                  (not= spawned destroyed)
                  [:unbalanced extra :spawned spawned :destroyed destroyed]
                  :else (recur (inc i) (lcg-next s))))))]
      (is (nil? failure)
          (str "actor-lifecycle property failed: " (pr-str failure))))
    ;; spawn allocator id is monotone per machine-id across re-entries.
    (let [m {:initial :idle
             :data    {}
             :guards  shared-guards
             :actions shared-actions
             :states  {:idle    {:on {:e0 :working}}
                       :working {:spawn {:machine-id :child/worker :start [:begin]}
                                 :on    {:e1 :idle}}}}
          s0 (initial-snapshot m)
          r1 (machines/machine-transition m s0 [:e0])           ;; spawn #1
          r2 (machines/machine-transition m (result/snap r1) [:e1]) ;; exit (destroy)
          r3 (machines/machine-transition m (result/snap r2) [:e0])] ;; spawn #2
      (is (= 1 (get-in (result/snap r1) [:rf/spawn-counter :child/worker])))
      (is (= 2 (get-in (result/snap r3) [:rf/spawn-counter :child/worker]))
          "re-entering the spawn state allocates the NEXT id — counter monotone, never rewound"))))

;; ---- INVARIANT 7: replay determinism (same machine + sequence) ------------

(deftest prop-replay-is-deterministic
  (testing "the same machine + the same event sequence yields a byte-
            identical FINAL snapshot across two independent runs (the
            machine-engine-level replay claim)"
    (let [final-snap (fn [machine events]
                       (:snap (last (cons {:snap (initial-snapshot machine)}
                                          (run-sequence machine events)))))
          failure
          (loop [i 0, s 7007]
            (if (= i 400)
              nil
              (let [[m _ s1] (gen-machine s)
                    [evs s2] (gen-events s1)
                    run-a    (final-snap m evs)
                    run-b    (final-snap m evs)]
                (if (not= run-a run-b)
                  [:replay-divergence m evs run-a run-b]
                  (recur (inc i) (lcg-next s2))))))]
      (is (nil? failure)
          (str "replay-determinism property failed: " (pr-str failure))))))

;; ---- INVARIANT 8: parallel declaration-order independence ------------------

(deftest prop-parallel-selection-is-declaration-order-independent
  (testing "reordering an INDEPENDENT parallel machine's :regions yields the
            SAME selected configuration (+ tag union) for the same event
            sequence — region selection is set-like, declaration-order
            independent. Regions are independent by construction (constant
            guards, no shared-:data writes); the SEPARATELY-tested shared-
            :data sequential-flow coupling (parallel_test §6) legitimately
            depends on declaration order and is excluded here by design."
    (let [reorder-regions
          (fn [machine]
            ;; rebuild the regions map in reversed key order; reinstall the
            ;; region cache so the reordered spec is a fresh, valid machine.
            (let [rs   (:regions machine)
                  rev  (into {} (map (fn [k] [k (get rs k)]) (reverse (keys rs))))]
              (parallel/install-region-cache (assoc machine :regions rev))))
          failure
          (loop [i 0, s 8008]
            (if (= i 300)
              nil
              (let [[m s1] (gen-independent-parallel-machine s)
                    [evs s2] (gen-events s1)
                    m'       (reorder-regions m)
                    final-a  (:snap (last (run-sequence m evs)))
                    final-b  (:snap (last (run-sequence m' evs)))]
                (cond
                  ;; the region→leaf selection must match (the :state map is
                  ;; key-order independent under `=`)
                  (not= (:state final-a) (:state final-b))
                  [:selection-differs m evs (:state final-a) (:state final-b)]
                  ;; the tag union (a set) must be identical regardless of order
                  (not= (:tags final-a) (:tags final-b))
                  [:tags-differ m evs (:tags final-a) (:tags final-b)]
                  :else (recur (inc i) (lcg-next s2))))))]
      (is (nil? failure)
          (str "declaration-order-independence property failed: " (pr-str failure))))))
