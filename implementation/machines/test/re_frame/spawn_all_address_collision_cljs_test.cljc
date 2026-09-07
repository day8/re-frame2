(ns re-frame.spawn-all-address-collision-cljs-test
  "A `:spawn-all` invoke whose distinct logical children RESOLVE to the same
  actor address is REJECTED fail-closed (rf2-qlzh9).

  PR #6213 (rf2-ek435) stores prepared children as `{<spawned-id> prepared}`,
  but `:spawn-all` permits two DISTINCT logical children to resolve to the SAME
  actor address — a `:fixed-actor-id` literal shared by two children, or a fixed
  id colliding with a generated `<type>#n` — because registration
  (`validate-spawn-all!`) guards only LOGICAL `:id` uniqueness. The map then
  silently overwrites one prepared entry / one live actor: the first child
  consumes the other's spec/snapshot and drops the only entry, and the later
  child falls back to a second resolution/validation. Only one of the two logical
  children ever completes, so an `:all` join waits forever on the missing one.

  Fix: `spawn-all-init-fx`'s admission preflight detects resolved-address
  aliasing over the prepared children as a THIRD fail-closed condition (alongside
  unregistered TYPE + spawn-time schema rejection), rejects the whole invoke
  atomically (the childless reject sentinel), and emits ONE deterministic
  `:rf.error/machine-spawn-all-duplicate-id` — the same category the
  registration-time logical-`:id` guard uses — naming the offending logical child
  ids and resolved addresses.

  Pinned here (JVM + CLJS):

   1. fixed/fixed collision — two children sharing a `:fixed-actor-id` reject the
      invoke; no snapshot installs; the trace names both child ids + the address.
   2. fixed/generated collision — a fixed id equal to a sibling's generated
      `<type>#n` rejects likewise (registration passed; caught at spawn time).
   3. control — distinct resolved addresses (all-generated) install cleanly with
      NO collision reject.
   4. privacy — the reject trace carries STRUCTURAL context only, never `:data`.
   5. ORDER (rf2-ri19s) — the alias is STRUCTURAL, decided from the pre-allocated
      child args alone, so it rejects BEFORE any type resolution, snapshot build,
      or `[:schemas :data]` callback: an aliased batch whose children are ALSO
      schema-invalid / unregistered emits the collision reject and NOTHING else."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading the machines facade registers `rf/reg-machine` + the reserved
   ;; machine fxs when this ns runs alone.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   ;; The schemas artefact ships the registered-validator hot path the
   ;; `:where :machine-data` boundary routes through; the `.malli` adapter
   ;; publishes Malli's validate/explain into the late-bind table. Needed by
   ;; the ORDER tests below, which pin that an aliased batch never reaches a
   ;; child's `[:schemas :data]` validator at all.
   [re-frame.schemas]
   [re-frame.schemas.malli]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures under test.
;; ---------------------------------------------------------------------------

(def ^:private plain-child
  {:initial :running
   :data    {}
   :states  {:running {}}})

(defn- parent-over
  "A `:spawn-all` parent over `children` with join mode `:all`."
  [children]
  {:initial :idle
   :states
   {:idle    {:on {:start :forking}}
    :forking {:spawn-all {:children         children
                          :join             :all
                          :on-all-complete  [:all/done]}
              :on {:all/done :ready}}
    :ready   {}}})

(defn- join-slot [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:forking]]))

(defn- snap-of [actor-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :snapshots actor-id]))

(defn- collision-rejects []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-all-duplicate-id))

;; ---------------------------------------------------------------------------
;; Ordering probes (rf2-ri19s).
;; ---------------------------------------------------------------------------

(def ^:private reject-operations
  "The three fail-closed `:spawn-all` admission rejects, in no order — the set
  `reject-order` projects the captured stream onto."
  #{:rf.error/machine-spawn-all-duplicate-id
    :rf.error/schema-validation-failure
    :rf.error/machine-spawn-unregistered-type})

(defn- reject-order
  "The captured admission-reject operations in EMISSION order — the sequence
  rf2-ri19s pins. Pre-fix, an aliased batch of schema-invalid children yielded
  `[:rf.error/schema-validation-failure :rf.error/schema-validation-failure
    :rf.error/machine-spawn-all-duplicate-id]`; the structural alias must be the
  ONLY entry."
  []
  (into [] (comp (map :operation) (filter reject-operations))
        (or (rf.machines.test-support/captured-events) [])))

(def ^:private validator-calls
  "Counts every invocation of the counting child validator below, so the ORDER
  tests can assert the application `[:schemas :data]` callback was never REACHED
  — strictly stronger than asserting no failure trace fired."
  (atom 0))

(def ^:private counting-child
  "A child TYPE whose `[:schemas :data]` validator counts its own invocations
  and REJECTS the per-child `:data` override the ORDER tests supply (`:n` must
  be a positive int; the override passes a string). Registered default conforms,
  so only an override violates."
  {:initial :running
   :data    {:n 1}
   :schemas {:data [:fn (fn [m]
                          (swap! validator-calls inc)
                          (pos-int? (:n m)))]}
   :states  {:running {}}})

;; ===========================================================================
;; (1) THE BUG — two children sharing a :fixed-actor-id collapse to one actor.
;; ===========================================================================

(deftest fixed-fixed-collision-rejects-the-whole-invoke
  (testing "two DISTINCT logical children declaring the SAME :fixed-actor-id
            resolve to one actor address: the invoke is rejected fail-closed (the
            childless reject sentinel, no :rf/prepared scratch, no snapshot
            installed) and ONE :rf.error/machine-spawn-all-duplicate-id fires
            naming both offending logical child ids + the resolved address. The
            pre-fix path silently overwrote one prepared entry / one live actor."
    (rf/reg-machine :sa/dup plain-child)
    (rf/reg-machine :sup/dup
                    (parent-over [{:id :a :machine-id :sa/dup :fixed-actor-id :dup/actor}
                                  {:id :b :machine-id :sa/dup :fixed-actor-id :dup/actor}]))
    (rf/dispatch-sync [:sup/dup [:start]])
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/dup))
        "one childless reject sentinel — exactly this map, so NO live join and NO :rf/prepared scratch leaked")
    (is (nil? (snap-of :dup/actor))
        "no snapshot installed at the aliased address — nothing overwrote, the reject is atomic")
    (let [evs (collision-rejects)]
      (is (= 1 (count evs))
          "exactly ONE deterministic collision rejection per invoke")
      (let [tags (:tags (first evs))]
        (is (= [[:dup/actor [:a :b]]] (:collisions tags))
            "the reject names the resolved address paired with BOTH offending logical child ids, in child order")
        (is (= :sup/dup (:parent-id tags)) "the reject carries the parent identity")
        (is (= [:forking] (:invoke-id tags)) "the reject carries the invoke identity")))))

;; ===========================================================================
;; (2) Adversarial — a fixed id colliding with a sibling's GENERATED <type>#n.
;; ===========================================================================

(deftest fixed-generated-collision-rejects-the-whole-invoke
  (testing "a child whose :fixed-actor-id equals the address a GENERATED sibling
            will allocate (`<type>#1`) collides at spawn time — registration
            passed (distinct logical :ids), so the aliasing is caught only by the
            spawn-time preflight, which rejects the whole invoke."
    (rf/reg-machine :sa/gen plain-child)
    ;; :b (machine-id :sa/gen, generated) allocates :sa/gen#1; :a fixes that id.
    (let [gen-id (keyword "sa" "gen#1")]
      (rf/reg-machine :sup/fixgen
                      (parent-over [{:id :a :machine-id :sa/gen :fixed-actor-id gen-id}
                                    {:id :b :machine-id :sa/gen}]))
      (rf/dispatch-sync [:sup/fixgen [:start]])
      (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/fixgen))
          "the fixed/generated alias rejects the whole invoke (childless sentinel)")
      (is (nil? (snap-of gen-id))
          "no snapshot installed at the aliased address")
      (let [evs (collision-rejects)]
        (is (= 1 (count evs)) "exactly one collision rejection")
        (is (= [[gen-id [:a :b]]] (:collisions (:tags (first evs))))
            "the reject names the resolved address + both offending logical child ids")))))

;; ===========================================================================
;; (3) Control — distinct resolved addresses install cleanly, no reject.
;; ===========================================================================

(deftest distinct-addresses-install-cleanly-with-no-collision-reject
  (testing "an all-generated :spawn-all (distinct <type>#1 / <type>#2 addresses)
            seeds a LIVE join and installs both children — the collision guard
            fires ONLY on a genuine resolved-address alias, never on unique
            generated ids."
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/clean (parent-over [{:id :a :machine-id :sa/plain}
                                             {:id :b :machine-id :sa/plain}]))
    (rf/dispatch-sync [:sup/clean [:start]])
    (let [slot (join-slot :sup/clean)]
      (is (contains? slot :children) "a live child-bearing join — no false collision reject")
      (is (= #{:sa/plain#1 :sa/plain#2} (set (vals (:children slot))))
          "the two children resolved to DISTINCT generated addresses")
      (doseq [id (vals (:children slot))]
        (is (some? (snap-of id)) (str "child " id " installed"))))
    (is (empty? (collision-rejects))
        "NO collision reject for unique resolved addresses")))

;; ===========================================================================
;; (4) Privacy — the reject trace is STRUCTURAL-only (no :data / spawn args).
;; ===========================================================================

(deftest collision-reject-carries-structural-context-only
  (testing "the collision reject trace carries the offending logical child ids +
            resolved address + parent/invoke identity, but NEVER the spawn args /
            :data (which may hold application secrets)."
    ;; Neutral ids — the ONLY occurrence of the sentinel string is inside :data,
    ;; so a match in the serialized trace proves a genuine :data leak (not an id).
    (rf/reg-machine :sa/priv {:initial :running
                              :data    {:token "zzsentinelzz"}
                              :states  {:running {}}})
    (rf/reg-machine :sup/priv
                    (parent-over [{:id :a :machine-id :sa/priv :fixed-actor-id :one/actor
                                   :data {:token "zzsentinelzz-a"}}
                                  {:id :b :machine-id :sa/priv :fixed-actor-id :one/actor
                                   :data {:token "zzsentinelzz-b"}}]))
    (rf/dispatch-sync [:sup/priv [:start]])
    (let [ev  (first (collision-rejects))
          txt (pr-str ev)]
      (is (some? ev) "a collision reject fired")
      (is (not (re-find #"zzsentinelzz" txt))
          "no application secret leaked into the reject trace (no :data / spawn args carried)")
      (is (= [[:one/actor [:a :b]]] (:collisions (:tags ev)))
          "structural collision carrier is present"))))

;; ===========================================================================
;; (5) ORDER — the structural alias rejects BEFORE any schema callback runs.
;; ===========================================================================

(deftest aliased-batch-rejects-before-any-schema-callback
  (testing "two aliasing children that are ALSO schema-invalid: the resolved
            address is derivable from the pre-allocated child args alone, so the
            alias is decided FIRST and the invoke rejects with EXACTLY ONE
            :rf.error/machine-spawn-all-duplicate-id — no
            :rf.error/schema-validation-failure precedes it, and the application
            [:schemas :data] validator is never CALLED for the aliased children.
            Pre-fix the preflight prepared every child first, so the captured
            order was [schema-failure schema-failure duplicate-id]: the
            structural invalidity did not fail first, and a validator/listener
            that swapped the owner frame could pre-empt the collision reject
            entirely."
    (reset! validator-calls 0)
    (rf/reg-machine :sa/order counting-child)
    (rf/reg-machine :sup/order
                    (parent-over [{:id :a :machine-id :sa/order
                                   :fixed-actor-id :order/actor :data {:n "bad"}}
                                  {:id :b :machine-id :sa/order
                                   :fixed-actor-id :order/actor :data {:n "bad"}}]))
    (rf/dispatch-sync [:sup/order [:start]])
    (is (= [:rf.error/machine-spawn-all-duplicate-id] (reject-order))
        "the structural alias is the FIRST and ONLY admission reject — no schema failure precedes it")
    (is (zero? @validator-calls)
        "no child [:schemas :data] validator was CALLED — the aliased batch never reached preparation")
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/order))
        "still one childless reject sentinel — the atomic reject shape is unchanged (rf2-qlzh9)")
    (is (nil? (snap-of :order/actor))
        "nothing installed at the aliased address")
    (is (= [[:order/actor [:a :b]]] (:collisions (:tags (first (collision-rejects)))))
        "declaration-order diagnostics survive the hoist")))

;; ===========================================================================
;; (6) ORDER, adversarial — alias + UNREGISTERED sibling + multiple groups.
;; ===========================================================================

(deftest aliased-batch-rejects-before-type-resolution-across-groups
  (testing "adversarial: TWO collision groups whose members are variously
            unregistered and schema-invalid, declared interleaved. The alias is
            structural, so it is decided before any TYPE is resolved: the invoke
            emits ONE collision reject naming BOTH groups in declaration order
            and NOTHING else — no :rf.error/machine-spawn-unregistered-type for
            the unregistered member, no schema failure for the invalid one. This
            pins that the hoist is ahead of registry resolution too, and that the
            multi-group diagnostic order does not fall back on map hash order
            (which differs between CLJ and CLJS)."
    (reset! validator-calls 0)
    (rf/reg-machine :sa/adv counting-child)
    ;; Groups: :g1/actor  ← children :a (schema-invalid) + :d (unregistered TYPE)
    ;;         :g2/actor  ← children :b (unregistered TYPE) + :c (schema-invalid)
    ;; Declared a, b, c, d so first-appearance order is [:g1/actor :g2/actor]
    ;; while the per-group child order is [:a :d] / [:b :c].
    (rf/reg-machine :sup/adv
                    (parent-over [{:id :a :machine-id :sa/adv
                                   :fixed-actor-id :g1/actor :data {:n "bad"}}
                                  {:id :b :machine-id :sa/nope
                                   :fixed-actor-id :g2/actor}
                                  {:id :c :machine-id :sa/adv
                                   :fixed-actor-id :g2/actor :data {:n "bad"}}
                                  {:id :d :machine-id :sa/nope
                                   :fixed-actor-id :g1/actor}]))
    (rf/dispatch-sync [:sup/adv [:start]])
    (is (= [:rf.error/machine-spawn-all-duplicate-id] (reject-order))
        "ONE structural reject and nothing else — no unregistered-type or schema reject rode along")
    (is (zero? @validator-calls)
        "no [:schemas :data] validator was CALLED for an already-aliased batch")
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/adv))
        "the whole invoke rejects atomically under the childless sentinel")
    (is (nil? (snap-of :g1/actor)) "nothing installed at the first aliased address")
    (is (nil? (snap-of :g2/actor)) "nothing installed at the second aliased address")
    (let [tags       (:tags (first (collision-rejects)))
          collisions (:collisions tags)]
      ;; rf2-hys95 — GROUP order is now a contract too, read positionally off
      ;; the ordered carrier. Previously this could only assert membership: the
      ;; diagnostic published `(into {} collisions)`, so group order came out of
      ;; an unordered presentation (an array-map at this size, a hash map past
      ;; the small-map threshold) and asserting it would have pinned an
      ;; accident.
      (is (= [[:g1/actor [:a :d]] [:g2/actor [:b :c]]] collisions)
          "both groups in FIRST-APPEARANCE order (:g1/actor before :g2/actor), each naming its children in DECLARATION order (:a before :d, :b before :c)")
      (is (vector? collisions)
          "the carrier itself is an ordered vector of pairs, not a map presentation")
      (is (vector? (second (first collisions)))
          "child ids ride an ordered vector, not a set — declaration order is a contract")
      (is (= :sup/adv (:parent-id tags)) "parent identity carried")
      (is (= [:forking] (:invoke-id tags)) "invoke identity carried"))))

;; ===========================================================================
;; (7) ORDER — GROUP order survives past the small-map threshold (rf2-hys95).
;; ===========================================================================

(deftest collision-groups-carry-declaration-order-past-the-array-map-threshold
  (testing "TEN collision groups, each group's two members declared
            NON-CONTIGUOUSLY (all first members, then all second members). Ten
            entries is past the 8-entry array-map threshold on BOTH hosts, so a
            `{<address> [<child-id> …]}` presentation degrades to a hash map
            whose seq order is neither declaration order nor the same on CLJ and
            CLJS. The diagnostic therefore carries the ORDERED VECTOR of
            `[<resolved-address> [<logical-child-id> …]]` pairs that
            `spawn-all-address-collisions` already computes in first-appearance
            order — read positionally, identically on both hosts.

            Pre-fix `reject-address-collision!` did `(into {} collisions)` and
            published only the map, discarding the carrier: on CLJ ten grouped
            addresses seq'd as g6 g0 g8 g9 g3 g7 g5 g1 g4 g2. A developer reading
            the reject to find WHICH declarations collided got a shuffled list
            that also differed between hosts."
    (rf/reg-machine :sa/wide plain-child)
    (let [n        10
          addr     (fn [i] (keyword "g" (str "actor" i)))
          first-id (fn [i] (keyword (str "a" i)))
          last-id  (fn [i] (keyword (str "b" i)))
          ;; a0…a9 then b0…b9 — group i is {a_i, b_i}, its members 10 apart, so
          ;; first-appearance order is addr(0)…addr(9) and no group is
          ;; contiguous in the declaration vector.
          children (into (mapv (fn [i] {:id (first-id i) :machine-id :sa/wide
                                        :fixed-actor-id (addr i)})
                               (range n))
                         (mapv (fn [i] {:id (last-id i) :machine-id :sa/wide
                                        :fixed-actor-id (addr i)})
                               (range n)))]
      (rf/reg-machine :sup/wide (parent-over children))
      (rf/dispatch-sync [:sup/wide [:start]])
      (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/wide))
          "the whole invoke rejects atomically under the childless sentinel")
      (let [evs (collision-rejects)]
        (is (= 1 (count evs)) "exactly ONE collision rejection for the whole invoke")
        (let [collisions (:collisions (:tags (first evs)))]
          (is (vector? collisions)
              "the diagnostic carries an ORDERED VECTOR of pairs, not a map presentation")
          (is (= (mapv (fn [i] [(addr i) [(first-id i) (last-id i)]]) (range n))
                 collisions)
              "every group in FIRST-APPEARANCE order, each naming its children in declaration order — positionally identical on CLJ and CLJS")
          (is (= (mapv addr (range n)) (mapv first collisions))
              "group order read positionally off the carrier, never off map iteration"))))))
