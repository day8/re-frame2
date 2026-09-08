(ns re-frame.generated-address-collision-test
  "A spawn whose GENERATED `<type>#<n>` address is already held by a LIVE actor
  is REJECTED fail-closed with `:rf.error/machine-spawn-all-duplicate-id`
  (rf2-1sip, option (f)).

  WHY THE ADDRESS CAN COLLIDE AT ALL. The declarative allocator's counter lives
  INSIDE THE SPAWNING PARENT'S SNAPSHOT (`:rf/spawn-counter`,
  `transition/allocate-spawned-id` — that in-snapshot home is what makes
  `machine-transition` pure in its spawn-id sequencing) while the address space
  it allocates into is FRAME-GLOBAL. The two disagree in the ordinary
  multi-actor shapes:

    - a parent DESTROYED and RESPAWNED at the same address begins counting from
      zero beside its own still-live orphans, so its first new child re-mints
      `<type>#1`; and
    - two parents spawning the same child TYPE each mint `<type>#1`, with no
      destroy or re-incarnation anywhere in sight.

  BEFORE THIS BEAD both installed straight over the live occupant through an
  unguarded `assoc-in`. Because a spawned actor's liveness IS its snapshot's
  presence (Spec 005 §Liveness is derived from runtime-db), that one write was
  simultaneously a birth and an unannounced death: no `:exit`, no teardown, no
  `:rf.machine/destroyed`, and two `:rf.machine.spawn/spawned` traces naming one
  address with no destroy between them. The actor was GONE, not detached.

  WHY REJECT RATHER THAN REPLACE — the distinction this suite exists to pin.
  rf2-dokz ruled that a spawn arriving at an occupied `:fixed-actor-id` REPLACES
  the occupant cleanly and raises nothing, because the AUTHOR NAMED that
  address and naming it twice is a request. Nobody names a generated address, so
  that reading is unavailable here: there is no request to honour, and Spec 005's
  *Teardown is explicit in v1* rule reserves destroying the occupant to the
  author. Rejecting is what is left, and it uses the EXISTING category rather
  than a new one — `:rf.error/machine-spawn-all-duplicate-id` already names
  \"two distinct spawns resolve to one actor address and one would silently
  overwrite the other\", including the fixed-versus-generated shape.

  NOT ATTEMPTED HERE, deliberately: re-homing the counter so `<type>#<n>` is
  frame-unique (rf2-1sip option (e)) — that moves `machine-transition`'s
  purity contract and is a separate ruling. This suite pins the loud failure,
  not the absence of the collision.

  Both directions are pinned, because an error-only suite is half a suite:
  every reject test asserts the OCCUPANT SURVIVED INTACT, and the controls
  assert that ordinary generated spawning, re-entry re-allocation, and
  `:spawn-all` batches are unchanged."
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
(def ^:private machine-data rf.machines.test-support/machine-data)

(defn- rejects
  "The captured collision rejects, each reduced to its `:tags` map (a trace
  event carries its structural context there, not at the root)."
  []
  (mapv :tags (rf.machines.test-support/events-of
                :rf.error/machine-spawn-all-duplicate-id)))

(defn- spawned-ids
  "Every actor address announced by a `:rf.machine.spawn/spawned` trace so far,
  oldest first. The invariant a colliding install used to break is that no
  address appears here twice with no `:rf.machine/destroyed` between."
  []
  (mapv (comp :spawned-id :tags)
        (rf.machines.test-support/events-of :rf.machine.spawn/spawned)))

;; ---------------------------------------------------------------------------
;; Shared fixtures under test.
;; ---------------------------------------------------------------------------

(defn- reg-child!
  "A child machine that records a mark in its own `:data`, so a surviving
  occupant can be told apart from a replacement that overwrote it."
  [id]
  (rf/reg-machine id
    {:initial :running
     :data    {:mark :none}
     :actions {:mark (fn [{d :data ev :event}] {:data (assoc d :mark (second ev))})}
     :states  {:running {:on {:mark {:action :mark}}}}}))

(defn- reg-parent!
  "A parent whose `:working` state declaratively spawns `child-id` at a
  GENERATED address (no `:fixed-actor-id`), and which can leave and re-enter
  that state."
  [id child-id]
  (rf/reg-machine id
    {:initial :idle
     :states  {:idle    {:on {:go :working}}
               :working {:spawn {:machine-id child-id}
                         :on    {:back :idle}}}}))

;; ---------------------------------------------------------------------------
;; (1) The filed defect: a respawned parent re-mints its live orphan's address.
;; ---------------------------------------------------------------------------

(deftest a-respawned-parent-is-refused-its-live-orphans-generated-address
  (testing "rf2-1sip — a parent destroyed and respawned at the SAME address
            starts its :rf/spawn-counter fresh, so its first new child would
            allocate <type>#1 over the still-live orphan the previous
            incarnation left. That install is now REJECTED: the orphan keeps its
            snapshot and its :data verbatim, no second spawned trace names the
            address, and one :rf.error/machine-spawn-all-duplicate-id fires."
    (reg-child! :gac/child)
    (reg-parent! :gac/parent :gac/child)
    (rf/reg-event :gac/hire
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :gac/parent
                                          :fixed-actor-id :gac/p}]]}))
    (rf/reg-event :gac/fire
      (fn [_ _] {:fx [[:rf.machine/destroy :gac/p]]}))

    ;; First incarnation of the parent spawns a child at the generated address.
    (rf/dispatch-sync [:gac/hire])
    (rf/dispatch-sync [:gac/p [:go]])
    (is (some? (snapshot :gac/child#1))
        "the first incarnation's child installed at the generated address")
    (rf/dispatch-sync [:gac/child#1 [:mark :FIRST]])
    (is (= :FIRST (:mark (machine-data :gac/child#1)))
        "and it is addressable — the mark distinguishes it from any successor")

    ;; An IMPERATIVE destroy of the parent tears down the parent ONLY: the
    ;; child is an independent actor at its own address and outlives its
    ;; spawner (Spec 005 §Teardown is explicit in v1). That orphan is the
    ;; occupant the respawned parent will collide with.
    (rf/dispatch-sync [:gac/fire])
    (is (nil? (snapshot :gac/p)) "the parent was destroyed")
    (is (some? (snapshot :gac/child#1))
        "its child survives as an orphan — teardown is explicit in v1")

    (rf.machines.test-support/reset-captured!)

    ;; A new incarnation at the SAME address, with a FRESH counter.
    (rf/dispatch-sync [:gac/hire])
    (is (= {} (:rf/spawn-counter (snapshot :gac/p)))
        "the new incarnation's spawn-counter is seeded fresh — this is the
         defect's cause, and pinning it keeps the test honest about what it
         reproduces")
    (rf/dispatch-sync [:gac/p [:go]])

    (testing "the collision is REFUSED"
      (is (= 1 (count (rejects)))
          "exactly one :rf.error/machine-spawn-all-duplicate-id, not zero and
           not one per retry")
      (is (= :gac/child#1 (:failing-id (first (rejects))))
          "the reject names the occupied address")
      (is (= :gac/child (:machine-id (first (rejects))))
          "and the machine TYPE that could not be spawned")
      (is (= :gac/p (:parent-id (first (rejects))))
          "and the spawning parent"))

    (testing "the occupant SURVIVED — the half of this that is not the error"
      (is (some? (snapshot :gac/child#1))
          "the orphan still has a snapshot")
      (is (= :FIRST (:mark (machine-data :gac/child#1)))
          "and it is the SAME actor: its :data was never overwritten")
      (is (= [] (filterv #(= :gac/child#1 %) (spawned-ids)))
          "no :rf.machine.spawn/spawned announced the address a second time")
      (is (nil? (snapshot :gac/child#2))
          "and the runtime did not silently side-line the spawn to a fresh
           address either — the reject is fail-closed, not a re-allocation"))))

;; ---------------------------------------------------------------------------
;; (2) The same collision with no re-incarnation at all: two live parents.
;; ---------------------------------------------------------------------------

(deftest a-second-parent-is-refused-the-address-its-sibling-already-holds
  (testing "rf2-1sip — the counter is per-snapshot and the address space is
            per-frame, so two parents of one type at DISTINCT addresses both
            mint <type>#1 with no destroy and no re-incarnation anywhere. The
            second is refused and the first parent's child is untouched."
    (reg-child! :gac2/child)
    (reg-parent! :gac2/parent :gac2/child)
    (rf/reg-event :gac2/hire
      (fn [_ [_ addr]] {:fx [[:rf.machine/spawn {:machine-id     :gac2/parent
                                                 :fixed-actor-id addr}]]}))

    (rf/dispatch-sync [:gac2/hire :gac2/a])
    (rf/dispatch-sync [:gac2/hire :gac2/b])
    (is (and (some? (snapshot :gac2/a)) (some? (snapshot :gac2/b)))
        "two live parents at two distinct addresses — the ordinary multi-actor
         shape, nothing exotic")

    (rf/dispatch-sync [:gac2/a [:go]])
    (is (some? (snapshot :gac2/child#1)) "parent A's child installed")
    (rf/dispatch-sync [:gac2/child#1 [:mark :FROM-A]])

    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:gac2/b [:go]])

    (is (= 1 (count (rejects)))
        "parent B's spawn is refused rather than collapsing onto A's child")
    (is (= :gac2/child#1 (:failing-id (first (rejects)))))
    (is (= :gac2/b (:parent-id (first (rejects))))
        "the reject names B, the parent whose spawn was refused")
    (is (= :FROM-A (:mark (machine-data :gac2/child#1)))
        "A's child is the SAME actor it was — this is the silent data loss the
         reject replaces")
    (is (= :gac2/child#1 (get-in (machine-data :gac2/a) [:rf/spawned [:working]]))
        "and A still records that address as the child it spawned")))

;; ---------------------------------------------------------------------------
;; (3) Controls — ordinary generated spawning is UNCHANGED.
;; ---------------------------------------------------------------------------

(deftest an-uncontended-generated-spawn-installs-exactly-as-before
  (testing "rf2-1sip — the guard costs one runtime-db read and changes nothing
            when the generated address is free: the child installs, the spawned
            trace fires once, and no reject is emitted"
    (reg-child! :gac3/child)
    (reg-parent! :gac3/parent :gac3/child)
    (rf/dispatch-sync [:gac3/parent [:go]])
    (is (some? (snapshot :gac3/child#1)) "the child installed normally")
    (is (= [:gac3/child#1] (spawned-ids)) "one spawned trace, naming it")
    (is (empty? (rejects)) "and no collision reject")))

(deftest re-entry-re-allocates-and-never-collides
  (testing "rf2-1sip — leaving a :spawn-bearing state destroys the child through
            the exit cascade AND the parent's counter advances, so re-entry
            allocates <type>#2 at an empty address. Both defences hold and the
            guard never fires."
    (reg-child! :gac4/child)
    (reg-parent! :gac4/parent :gac4/child)
    (rf/dispatch-sync [:gac4/parent [:go]])
    (is (some? (snapshot :gac4/child#1)))
    (rf/dispatch-sync [:gac4/parent [:back]])
    (is (nil? (snapshot :gac4/child#1))
        "the exit cascade destroyed the child — unlike an imperative parent
         destroy, which does not")
    (rf/dispatch-sync [:gac4/parent [:go]])
    (is (some? (snapshot :gac4/child#2))
        "re-entry allocated the NEXT address, not a re-mint of #1")
    (is (nil? (snapshot :gac4/child#1)))
    (is (empty? (rejects)) "no reject on the ordinary re-entry path")))

(deftest an-occupied-fixed-address-still-REPLACES-rather-than-rejecting
  (testing "rf2-1sip does NOT reopen rf2-dokz: an address the AUTHOR NAMED is
            still replaced cleanly, with no error, because naming it twice is a
            request. Only the generated case rejects."
    (reg-child! :gac5/child)
    (rf/reg-event :gac5/hire
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id     :gac5/child
                                          :fixed-actor-id :gac5/pinned}]]}))
    (rf/dispatch-sync [:gac5/hire])
    (rf/dispatch-sync [:gac5/pinned [:mark :FIRST]])
    (is (= :FIRST (:mark (machine-data :gac5/pinned))))

    (rf/dispatch-sync [:gac5/hire])
    (is (some? (snapshot :gac5/pinned)) "the replacement installed")
    (is (= :none (:mark (machine-data :gac5/pinned)))
        "and it IS the replacement — a fresh incarnation at the named address")
    (is (empty? (rejects))
        "no reject: rf2-dokz's ruling is untouched by rf2-1sip's guard")))

;; ---------------------------------------------------------------------------
;; (4) `:spawn-all` — the within-batch guard and the admitted-child invariant.
;; ---------------------------------------------------------------------------

(deftest an-intra-spawn-all-duplicate-still-rejects-the-whole-invoke
  (testing "rf2-qlzh9's within-batch resolved-address guard is structurally
            elsewhere (the invoke preflight) and is UNCHANGED: two children
            sharing one :fixed-actor-id still reject the whole invoke before
            anything installs"
    (reg-child! :gac6/child)
    (rf/reg-machine :gac6/parent
      {:initial :idle
       :states  {:idle    {:on {:go :forking}}
                 :forking {:spawn-all {:children        [{:id :x :machine-id :gac6/child
                                                          :fixed-actor-id :gac6/one}
                                                         {:id :y :machine-id :gac6/child
                                                          :fixed-actor-id :gac6/one}]
                                       :join            :all
                                       :on-all-complete [:all/done]}
                           :on {:all/done :ready}}
                 :ready   {}}})
    (rf/dispatch-sync [:gac6/parent [:go]])
    (is (= 1 (count (rejects)))
        "one deterministic reject for the aliased invoke")
    (is (= [[:gac6/one [:x :y]]] (:collisions (first (rejects))))
        "and it is the PREFLIGHT's reject — it carries the :collisions vector,
         which the generated-address reject does not")
    (is (nil? (snapshot :gac6/one))
        "nothing installed: the invoke was rejected atomically")))

(deftest an-admitted-spawn-all-child-still-always-installs
  (testing "rf2-v4oqd — the authoritative preflight is the SOLE verdict for a
            :spawn-all child, so the generated-address guard deliberately
            excludes prepared children: a second per-child reject would strand
            a live join naming a child that never appears. A batch of two
            children of one type allocates #1 and #2 and both install."
    (reg-child! :gac7/child)
    (rf/reg-machine :gac7/parent
      {:initial :idle
       :states  {:idle    {:on {:go :forking}}
                 :forking {:spawn-all {:children        [{:id :x :machine-id :gac7/child}
                                                         {:id :y :machine-id :gac7/child}]
                                       :join            :all
                                       :on-all-complete [:all/done]}
                           :on {:all/done :ready}}
                 :ready   {}}})
    (rf/dispatch-sync [:gac7/parent [:go]])
    (is (some? (snapshot :gac7/child#1)) "the first child installed")
    (is (some? (snapshot :gac7/child#2)) "and the second, at the NEXT address")
    (is (empty? (rejects)) "no reject — the batch's addresses are distinct")))

;; ---------------------------------------------------------------------------
;; (5) The reject's own shape.
;; ---------------------------------------------------------------------------

(deftest the-reject-carries-structural-context-only-and-names-both-escapes
  (testing "rf2-1sip — the diagnostic is structural-only (Spec 009 privacy: the
            spawn args / :data may hold application PII) and its human reason
            names the two author-side escapes, since :recovery is :no-recovery"
    (reg-child! :gac8/child)
    (rf/reg-machine :gac8/parent
      {:initial :idle
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :gac8/child
                                   :data       {:secret "hunter2"}}}}})
    (rf/reg-event :gac8/hire
      (fn [_ [_ addr]] {:fx [[:rf.machine/spawn {:machine-id     :gac8/parent
                                                 :fixed-actor-id addr}]]}))
    (rf/dispatch-sync [:gac8/hire :gac8/a])
    (rf/dispatch-sync [:gac8/hire :gac8/b])
    (rf/dispatch-sync [:gac8/a [:go]])
    (rf.machines.test-support/reset-captured!)
    (rf/dispatch-sync [:gac8/b [:go]])

    (let [ev  (first (rejects))
          raw (first (rf.machines.test-support/events-of
                       :rf.error/machine-spawn-all-duplicate-id))]
      (is (some? ev) "the reject fired")
      ;; Spec 009 §Core fields hoists `:recovery` onto the event root and
      ;; drops it from `:tags`, so it is read off the raw event.
      (is (= :no-recovery (:recovery raw))
          "the runtime may neither re-allocate (that would break the
           deterministic <type>#<n> sequencing) nor destroy the occupant")
      (is (string? (:reason ev)))
      (is (re-find #":fixed-actor-id" (:reason ev))
          "the reason names the distinct-address escape")
      (is (re-find #"destroy" (:reason ev))
          "and the destroy-the-occupant-first escape")
      (is (not (re-find #"hunter2" (:reason ev)))
          "and it never echoes the spawn :data")
      (is (nil? (:data ev)) "no :data rides the record")
      (is (nil? (:args ev)) "nor the raw spawn args")
      (is (not (re-find #"hunter2" (pr-str raw)))
          "and no slot of the WHOLE emitted event smuggles the payload through"))))
