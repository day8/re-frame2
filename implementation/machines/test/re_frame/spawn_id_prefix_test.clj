(ns re-frame.spawn-id-prefix-test
  "rf2-r9ey — a declarative `:spawn` / `:spawn-all` allocates its generated
  address from the spawn-spec's `:id-prefix` when one is supplied, and from its
  `:machine-id` when one is not.

  THE DEFECT THIS PINS. `:id-prefix` was ACCEPTED by the spawn-args validator
  (`lifecycle-fx.validation`'s spawn-spec key set), DOCUMENTED by Spec 005 in
  three places (§Spawn-spec keys and §Spec-spec keys both gloss it as
  `optional; defaults to :machine-id`, and §Spawn-id allocator — counter
  location keys the declarative counter at `[:rf/spawn-counter <id-prefix>]`),
  and then IGNORED: `transition/allocate-one` read `:machine-id`
  unconditionally, and the two args-builders that assemble the
  `:rf.machine/spawn` fx overwrote the author's `:id-prefix` with the child's
  `:machine-id` on the way out. So a spawn-spec asking for `:id-prefix :pc/mine`
  got `:pc/child#1` under counter key `:pc/child`, with nothing raised anywhere.
  That is the worst shape a knob can have — it fails in the REASSURING
  direction at authoring time and is diagnosable only by reading the allocator.

  WHY IT IS LOAD-BEARING RATHER THAN COSMETIC. rf2-1sip shipped option (f)
  ALONE: a generated `<type>#<n>` address already held by a live actor is now
  REJECTED fail-closed with `:rf.error/machine-spawn-all-duplicate-id`
  (`generated_address_collision_test`). The declarative counter lives in the
  SPAWNING PARENT'S SNAPSHOT while the address space is FRAME-GLOBAL, so two
  parents spawning the same child TYPE both mint `<type>#1` and the second is
  refused. `:id-prefix` is the only namespacing escape an author has from that
  refusal — which is exactly what (5) below exercises. Option (e) (re-homing
  the counter so `<type>#<n>` is frame-unique) is NOT attempted here and
  remains unruled; nothing in this file seeds or advances `:rf/spawn-counter`
  across incarnations, and `machine-transition` stays pure.

  BOTH DIRECTIONS ARE PINNED, and the second is the one that matters. (2) is
  the CONTROL: a spawn WITHOUT `:id-prefix` must still allocate under
  `:machine-id`, exactly as before. Without it, an allocator that simply read
  the prefix key unconditionally — a silent rename rather than a repair — would
  pass (1) and every other test here.

  Spec contract: [Spec 005 §Spawn-spec keys], [Spec 005 §Spec-spec keys],
  [Spec 005 §Spawn-id allocator — counter location],
  [Spec 005 §Spawn id format — `<id-prefix>#<n>` keyword]."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private runtime-db rf.machines.test-support/runtime-db)

(defn- spawned-id-for
  "The address the runtime recorded for `parent-id`'s spawn at `invoke-id`, read
  from the runtime-owned registry rather than guessed from the id format."
  [parent-id invoke-id]
  (get-in (runtime-db) [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- spawn-counter
  "`parent-id`'s in-snapshot `:rf/spawn-counter` map — the per-prefix sequence
  the allocator bumps. Asserting on the KEY, not merely on the minted address,
  is what stops a fix that spelled the address from one key while sequencing it
  under another."
  [parent-id]
  (:rf/spawn-counter (snapshot parent-id)))

(defn- reg-leaf!
  "A do-nothing child machine, used only as a spawnable TYPE."
  [id]
  (rf/reg-machine id {:initial :running :data {} :states {:running {}}}))

;; ---------------------------------------------------------------------------
;; (1) THE DEFECT: a supplied `:id-prefix` decides the address AND the counter.
;; ---------------------------------------------------------------------------

(deftest supplied-id-prefix-decides-the-generated-address
  (testing "rf2-r9ey — a declarative :spawn carrying :id-prefix allocates
            <id-prefix>#<n>, sequenced under the SUPPLIED prefix. Before this
            bead it allocated <machine-id>#<n> under the machine-id and the
            supplied prefix had no effect at all."
    (reg-leaf! :idp/child)
    (rf/reg-machine :idp/parent
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :idp/child
                                   :id-prefix  :idp/mine}}}})
    (rf/dispatch-sync [:idp/parent [:go]])

    (is (= :idp/mine#1 (spawned-id-for :idp/parent [:working]))
        "the registry records the address minted from the SUPPLIED prefix")
    (is (some? (snapshot :idp/mine#1))
        "and a live actor installed there")
    (is (nil? (snapshot :idp/child#1))
        "nothing installed at the machine-id-derived address — the supplied
         prefix REPLACED it rather than sitting alongside it")

    (testing "the counter is keyed by the same prefix the address was minted from"
      (is (= {:idp/mine 1} (spawn-counter :idp/parent))
          "one entry, under the supplied prefix — not under :idp/child, and not
           a second bookkeeping entry beside it"))))

;; ---------------------------------------------------------------------------
;; (2) THE CONTROL: no `:id-prefix` still allocates under `:machine-id`.
;; ---------------------------------------------------------------------------

(deftest absent-id-prefix-still-allocates-under-the-machine-id

  (testing "rf2-r9ey CONTROL — a :spawn with NO :id-prefix is unchanged: it
            allocates <machine-id>#<n> under counter key <machine-id>. This is
            the assertion that distinguishes a repair from a silent rename; an
            allocator that always read :id-prefix would pass every other test
            in this file and fail here with a nil-derived address."
    (reg-leaf! :idp2/child)
    (rf/reg-machine :idp2/parent
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :idp2/child}}}})
    (rf/dispatch-sync [:idp2/parent [:go]])

    (is (= :idp2/child#1 (spawned-id-for :idp2/parent [:working]))
        "the address is still derived from :machine-id")
    (is (some? (snapshot :idp2/child#1))
        "and the actor installed there")
    (is (= {:idp2/child 1} (spawn-counter :idp2/parent))
        "and the counter is still keyed by :machine-id")))

;; ---------------------------------------------------------------------------
;; (3) Two prefixes over ONE machine TYPE sequence INDEPENDENTLY.
;; ---------------------------------------------------------------------------

(deftest distinct-prefixes-over-one-type-sequence-independently

  (testing "rf2-r9ey — the counter is per-PREFIX, so two :spawn nodes of the
            SAME machine type under DIFFERENT prefixes each start at #1 rather
            than sharing one 1,2 sequence. This is the fact that makes
            :id-prefix a namespacing escape rather than a relabelling: had the
            fix minted from the prefix while sequencing under the machine-id,
            the second address would read <prefix-b>#2 and this test would say
            so."
    (reg-leaf! :idp3/child)
    (rf/reg-machine :idp3/parent
      {:initial :idle
       :data    {}
       :states  {:idle  {:on {:go :outer}}
                 :outer {:spawn   {:machine-id :idp3/child :id-prefix :idp3/left}
                         :initial :inner
                         :states  {:inner {:spawn {:machine-id :idp3/child
                                                   :id-prefix  :idp3/right}}}}}})
    (rf/dispatch-sync [:idp3/parent [:go]])

    (is (= :idp3/left#1 (spawned-id-for :idp3/parent [:outer]))
        "the outer spawn's own prefix starts at 1")
    (is (= :idp3/right#1 (spawned-id-for :idp3/parent [:outer :inner]))
        "and so does the inner spawn's — NOT #2, which is what a shared
         machine-id-keyed counter would have produced")
    (is (= {:idp3/left 1 :idp3/right 1} (spawn-counter :idp3/parent))
        "two independent per-prefix sequences in one parent snapshot")

    (testing "and the same prefix twice DOES share one sequence"
      (reg-leaf! :idp3b/child)
      (rf/reg-machine :idp3b/parent
        {:initial :idle
         :data    {}
         :states  {:idle  {:on {:go :outer}}
                   :outer {:spawn   {:machine-id :idp3b/child :id-prefix :idp3b/pool}
                           :initial :inner
                           :states  {:inner {:spawn {:machine-id :idp3b/child
                                                     :id-prefix  :idp3b/pool}}}}}})
      (rf/dispatch-sync [:idp3b/parent [:go]])
      (is (= :idp3b/pool#1 (spawned-id-for :idp3b/parent [:outer])))
      (is (= :idp3b/pool#2 (spawned-id-for :idp3b/parent [:outer :inner]))
          "one prefix, one monotonic sequence — the shallowest-first cascade
           order is unchanged")
      (is (= {:idp3b/pool 2} (spawn-counter :idp3b/parent))))))

;; ---------------------------------------------------------------------------
;; (4) `:spawn-all` honours each child's own `:id-prefix`.
;; ---------------------------------------------------------------------------

(deftest spawn-all-children-honour-their-own-id-prefix

  (testing "rf2-r9ey — Spec 005 §Spawn-and-join via :spawn-all says each child
            invoke-spec accepts the same keys as a single :spawn, :id-prefix
            among them. The per-child allocator and the per-child args-builder
            were the second and third sites reading :machine-id, so both are
            exercised here: one child overrides its prefix, its sibling does
            not, and the join records the addresses the registry reports."
    (reg-leaf! :idp4/child)
    (rf/reg-machine :idp4/parent
      {:initial :idle
       :data    {}
       :states  {:idle      {:on {:fan-out :hydrating}}
                 :hydrating {:spawn-all
                             {:children        [{:id :a :machine-id :idp4/child
                                                 :id-prefix :idp4/alpha}
                                                {:id :b :machine-id :idp4/child}]
                              :join            :all
                              :on-all-complete [:idp4/done]}
                             :on {:idp4/done :ready}}
                 :ready     {}}})
    (rf/dispatch-sync [:idp4/parent [:fan-out]])

    (let [jstate (get-in (runtime-db)
                         [:rf.runtime/machines :spawned :idp4/parent [:hydrating]])]
      (is (= :idp4/alpha#1 (get-in jstate [:children :a]))
          "the child that supplied a prefix installed under it")
      (is (= :idp4/child#1 (get-in jstate [:children :b]))
          "its sibling, with no prefix, is unchanged — the control inside the
           :spawn-all case")
      (is (some? (snapshot :idp4/alpha#1)) "prefixed child is live")
      (is (some? (snapshot :idp4/child#1)) "unprefixed sibling is live"))

    (is (= {:idp4/alpha 1 :idp4/child 1} (spawn-counter :idp4/parent))
        "two per-prefix sequences, one per distinct prefix in the batch")))

;; ---------------------------------------------------------------------------
;; (5) THE POINT OF THE REPAIR: `:id-prefix` is the escape from rf2-1sip (f).
;; ---------------------------------------------------------------------------

(deftest distinct-prefixes-let-two-parents-spawn-one-type-without-colliding

  (testing "rf2-r9ey + rf2-1sip (f) — two live parents spawning the SAME child
            TYPE both mint <type>#1, and since rf2-1sip the second is REFUSED
            fail-closed. Giving each parent its own :id-prefix separates the
            addresses, so both children install and NO
            :rf.error/machine-spawn-all-duplicate-id fires. This is why the
            knob had to work rather than merely be documented.

            Option (e) — re-homing the counter so <type>#<n> is frame-unique —
            is deliberately NOT exercised: nothing here seeds or advances
            :rf/spawn-counter across incarnations."
    (reg-leaf! :idp5/child)
    (rf/reg-machine :idp5/parent-a
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :idp5/child :id-prefix :idp5/from-a}}}})
    (rf/reg-machine :idp5/parent-b
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :idp5/child :id-prefix :idp5/from-b}}}})

    (rf/dispatch-sync [:idp5/parent-a [:go]])
    (rf/dispatch-sync [:idp5/parent-b [:go]])

    (is (some? (snapshot :idp5/from-a#1)) "parent A's child installed")
    (is (some? (snapshot :idp5/from-b#1))
        "and so did parent B's — the collision rf2-1sip (f) refuses never
         arose, because the two addresses differ")
    (is (empty? (rf.machines.test-support/events-of
                  :rf.error/machine-spawn-all-duplicate-id))
        "no duplicate-id rejection fired")))

;; ---------------------------------------------------------------------------
;; (6) The emitted spawn args / trace report the EFFECTIVE prefix.
;; ---------------------------------------------------------------------------

(deftest the-spawned-trace-reports-the-effective-id-prefix

  (testing "rf2-r9ey — the two args-builders in `transition` stamp :id-prefix
            onto the outgoing :rf.machine/spawn args, and that value reaches
            the :rf.machine.spawn/spawned trace. They previously overwrote a
            supplied prefix with the child's :machine-id, so a tool reading the
            trace was told a prefix the address did not come from. Both the
            supplied and the defaulted case are asserted."
    (reg-leaf! :idp6/child)
    (rf/reg-machine :idp6/prefixed
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :idp6/child :id-prefix :idp6/mine}}}})
    (rf/reg-machine :idp6/plain
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :idp6/child}}}})

    (rf/dispatch-sync [:idp6/prefixed [:go]])
    (let [tags (mapv :tags (rf.machines.test-support/events-of
                             :rf.machine.spawn/spawned))]
      (is (= [:idp6/mine] (mapv :id-prefix tags))
          "the trace names the SUPPLIED prefix")
      (is (= [:idp6/child] (mapv :machine-id tags))
          "and still names the registered TYPE separately — the two are
           distinct facts and the repair must not conflate them")
      (is (= [:idp6/mine#1] (mapv :spawned-id tags))
          "and the address agrees with the prefix it reports"))

    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:idp6/plain [:go]])
    (let [tags (mapv :tags (rf.machines.test-support/events-of
                             :rf.machine.spawn/spawned))]
      (is (= [:idp6/child] (mapv :id-prefix tags))
          "CONTROL — with no supplied prefix the trace still reports the
           machine-id-derived default, exactly as before")
      (is (= [:idp6/child#1] (mapv :spawned-id tags))))))
