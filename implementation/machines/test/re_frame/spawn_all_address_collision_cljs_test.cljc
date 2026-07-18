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
   4. privacy — the reject trace carries STRUCTURAL context only, never `:data`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading the machines facade registers `rf/reg-machine` + the reserved
   ;; machine fxs when this ns runs alone.
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

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
                          :on-child-done    :sa/done
                          :on-child-error   :sa/failed
                          :on-all-complete  [:all/done]}
              :on {:all/done :ready}}
    :ready   {}}})

(defn- join-slot [parent-id]
  (get-in (mtest/runtime-db) [:rf.runtime/machines :spawned parent-id [:forking]]))

(defn- snap-of [actor-id]
  (get-in (mtest/runtime-db) [:rf.runtime/machines :snapshots actor-id]))

(defn- collision-rejects []
  (mtest/events-of :rf.error/machine-spawn-all-duplicate-id))

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
        (is (= {:dup/actor [:a :b]} (:collisions tags))
            "the reject names the resolved address mapped to BOTH offending logical child ids, in child order")
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
        (is (= {gen-id [:a :b]} (:collisions (:tags (first evs))))
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
      (is (= {:one/actor [:a :b]} (:collisions (:tags ev)))
          "structural collision map is present"))))
