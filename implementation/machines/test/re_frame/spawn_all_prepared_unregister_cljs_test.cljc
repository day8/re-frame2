(ns re-frame.spawn-all-prepared-unregister-cljs-test
  "An ADMITTED+prepared `:spawn-all` child CONSUMES its invoke's authoritative
  preflight verdict — `spawn-fx` must NOT re-run its child-local
  `unregistered-spawn-type?` registry recheck against an already-prepared child
  (rf2-v4oqd) — and stays HANDLER-RESOLVABLE when the registrar DIVERGES from
  the definition its invoke prepared (rf2-rxjy3).

  `spawn-all-init-fx` (the FIRST fx in the entry vector) resolves, stamps,
  builds, and validates every declarative `:spawn-all` child EXACTLY ONCE and
  RETAINS the prepared result under the join slot's `:rf/prepared` scratch
  (rf2-ek435). But the per-child `:rf.machine/spawn` fx STILL consulted the
  CURRENT registry via `unregistered-spawn-type?` BEFORE reading its keyed
  prepared entry, so a mid-drain UNREGISTER of an admitted child TYPE flipped
  the already-admitted child to rejected: the per-child spawn emitted a SECOND
  `:rf.error/machine-spawn-unregistered-type` reject and installed NO snapshot —
  after the live child-bearing join had already been published. The result was
  the exact impossible half-live join (a join naming a child whose snapshot a
  second verdict omitted) the authoritative handoff exists to make impossible.

  THE MID-DRAIN MUTATOR THIS SUITE USES (rf2-wxy1c). These tests originally
  diverged the registrar from a TRACE LISTENER on
  `:rf.machine.spawn-all/started` / `:rf.machine.spawn/spawned` /
  `:rf.error/system-id-collision`. That instrument is gone: under the rf2-wxy1c
  ruling trace listeners are OBSERVERS, not participants — internal drain-owned
  emits deliver at the POST-DRAIN boundary, so a listener body can no longer run
  between an admitted child's preparation and its install ON ANY PLATFORM. The
  window those listeners reached is now closed BY CONSTRUCTION.

  The mechanism under test is NOT, though — the definition-lifetime rule still
  has to hold for any mid-drain divergence, and one in-drain, non-listener
  mutator still lands in exactly that window: the child's own `[:schemas :data]`
  VALIDATOR. `prepare-spawn-all-child` resolves the TYPE and retains
  `:type-spec` FIRST, then builds the snapshot, then runs the application
  validator LAST — so a validator that mutates the registrar diverges it strictly
  AFTER the prepared definition was captured and strictly BEFORE
  `install-spawn!` forces `prepared-type-ref`. That is the same window, reached
  by ordinary in-drain application code rather than by an observer, and it is
  platform-uniform: schema validation is not reader-conditional.

  So these tests assert the SAME pinning outcomes as before — an admitted child
  always installs, and its `:rf/machine-type` is the revertible KEYWORD while the
  registrar still holds the prepared definition, the PINNED definition once the
  registrar has diverged from it.

  The sibling `spawn_all_authoritative_preflight` suite (rf2-ek435) pins the
  RE-REGISTER seam and the all-valid validator cardinality. This suite pins the
  UNREGISTER seam — the recheck's fail-closed branch — on JVM + CLJS."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading the machines facade registers `rf/reg-machine` + the reserved
   ;; machine fxs when this ns runs alone.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   ;; Unregister a machine TYPE mid-drain (from the child's own `[:schemas :data]`
   ;; validator) by dropping its `:event` registrar entry — the seam the recheck
   ;; tripped on.
   [re-frame.registrar :as rf.registrar]
   ;; The schemas artefact ships the registered-validator hot path the
   ;; `:where :machine-data` boundary routes through; the `.malli` adapter
   ;; publishes Malli's validate/explain into the late-bind table.
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
  "An unconstrained child — a real live actor once its snapshot installs."
  {:initial :running
   :data    {}
   :states  {:running {}}})

(def ^:private booting-child
  "A child whose SYNTHETIC bootstrap — the `[:rf.machine.spawn/spawned]` event
  `spawn-fx` dispatches into every spawn that declares no `:start` — causes an
  OBSERVABLE transition (`:idle` → `:ready`), and which then accepts an ordinary
  `:go` event (`:ready` → `:working`).

  `plain-child` above cannot tell a LIVE actor from an INERT snapshot: its
  `:initial` is also its resting state, so `(= :running (machine-state id))`
  holds whether or not the actor's handler ever resolved. This fixture is what
  pins handler RESOLVABILITY rather than mere snapshot presence (rf2-rxjy3) —
  an installed-but-unresolvable child sits at `:idle` with
  `:rf/bootstrap-pending? true` while a `:rf.error/no-such-handler` fires."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:rf.machine.spawn/spawned :ready}}
             :ready   {:on {:go :working}}
             :working {}}})

(def ^:private booting-child-v2
  "A HOT-RELOADED `booting-child`: same states, but `:go` now targets
  `:hot-reloaded`. Re-registered under a live child's TYPE AFTER the drain, it
  is what proves the undisturbed install kept the REVERTIBLE keyword reference
  rather than a pinned copy — the live child follows the new definition."
  {:initial :idle
   :data    {}
   :states  {:idle         {:on {:rf.machine.spawn/spawned :ready}}
             :ready        {:on {:go :hot-reloaded}}
             :working      {}
             :hot-reloaded {}}})

(def ^:private unrelated-v2
  "A DIFFERENT definition re-registered under an admitted child's TYPE between
  the preflight and the install. Its state set is DISJOINT from
  `booting-child`'s, so a child whose prepared v1 snapshot resolved its handler
  from this current v2 would be driving a snapshot whose `:state` v2 does not
  even name — the split-authority failure mode (rf2-rxjy3)."
  {:initial :v2-initial
   :data    {}
   :states  {:v2-initial {:on {:rf.machine.spawn/spawned :v2-boot}}
             :v2-boot    {}}})

(defn- mutating-child
  "`child` augmented with a `[:schemas :data]` validator that runs `f` exactly
  once and always PASSES — the suite's NON-LISTENER mid-drain mutator.

  `prepare-spawn-all-child` retains the child's `:type-spec` (its resolved
  definition) BEFORE it runs this validator, and `install-spawn!` forces
  `prepared-type-ref` AFTER it — so whatever `f` does to the registrar lands in
  the preflight→install window, from ordinary in-drain application code the
  framework calls synchronously on every platform.

  It always returns `true`: the child is ADMITTED (the divergence must never be
  read as a re-verdict — rf2-v4oqd), and the FORM of its installed
  `:rf/machine-type` reference is the only thing under test."
  [child f]
  (let [fired (atom false)]
    (assoc child :schemas
           {:data [:fn (fn [_]
                         (when-not @fired
                           (reset! fired true)
                           (f))
                         true)]})))

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

(defn- unregistered-rejects []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-unregistered-type))

(defn- no-such-handler-errors []
  (rf.machines.test-support/events-of :rf.error/no-such-handler))

(defn- type-ref-of
  "The installed snapshot's revertible `:rf/machine-type` TYPE reference — the
  keyword the lazy resolver reads back through the registrar, or the pinned
  definition map."
  [actor-id]
  (:rf/machine-type (snap-of actor-id)))

;; ===========================================================================
;; (1) THE BUG — a mid-drain mutator UNREGISTERS an admitted child TYPE between
;;     the preflight and the install. The child must STILL install from its
;;     prepared entry, with no duplicate reject and a fully-live join.
;;
;;     Both legs of the definition-lifetime rule ride one invoke here: child :a
;;     diverges its own TYPE and must PIN, sibling :b leaves the registrar
;;     intact and must keep the revertible KEYWORD.
;; ===========================================================================

(deftest unregister-between-preflight-and-install-still-installs-the-admitted-child
  (testing "a mid-drain mutator that UNREGISTERS an admitted child TYPE cannot
            flip the already-prepared child into a rejected one: the per-child
            install consumes the prepared entry rather than re-consulting the
            (now-empty) registry, so the child installs its prepared snapshot,
            the join stays fully live, and NO duplicate
            :rf.error/machine-spawn-unregistered-type reject fires (the pre-fix
            recheck rejected the child + stranded a snapshotless half-live join)."
    (let [child-a (mutating-child plain-child
                                  #(rf.registrar/unregister! :event :sa/plain-a))]
      (rf/reg-machine :sa/plain-a child-a)
      (rf/reg-machine :sa/plain-b plain-child)
      (rf/reg-machine :sup/unreg (parent-over [{:id :a :machine-id :sa/plain-a}
                                               {:id :b :machine-id :sa/plain-b}]))
      (rf/dispatch-sync [:sup/unreg [:start]])
      (let [slot (join-slot :sup/unreg)]
        (is (contains? slot :children)
            "a LIVE child-bearing join was seeded (not the reject sentinel)")
        (is (= 2 (count (:children slot)))
            "the join names both admitted children")
        (is (not (contains? slot :rf/prepared))
            "every admitted child consumed + dropped its prepared scratch — the durable join retains none")
        (doseq [id (vals (:children slot))]
          (is (some? (snap-of id))
              (str "child " id " INSTALLED its prepared snapshot even though its TYPE was unregistered mid-drain"))
          (is (= :running (rf.machines.test-support/machine-state id))
              (str "child " id " installed the PREPARED spec verbatim (state :running), not a re-derivation")))
        (is (= child-a (type-ref-of (get (:children slot) :a)))
            "the DIVERGED child pinned its prepared definition — the registrar no longer speaks for it")
        (is (= :sa/plain-b (type-ref-of (get (:children slot) :b)))
            "the UNDISTURBED sibling kept the revertible TYPE keyword — divergence is per-child, not batch-wide"))
      (is (empty? (unregistered-rejects))
          "NO :rf.error/machine-spawn-unregistered-type reject fired — the recheck was skipped for the prepared child"))))

;; ===========================================================================
;; (2) Adversarial: unregister BOTH admitted children between preflight and
;;     install — every admitted child still installs, no reject fans.
;; ===========================================================================

(deftest unregister-all-children-between-preflight-and-install-installs-all
  (testing "when EVERY child TYPE is unregistered mid-drain — each from that
            child's OWN validator, so each divergence lands in that child's own
            preflight→install window — EVERY admitted child still installs from
            its prepared entry: the whole batch consumes its authoritative
            verdict; no reject, no partial install."
    (rf/reg-machine :sa/one (mutating-child plain-child
                                            #(rf.registrar/unregister! :event :sa/one)))
    (rf/reg-machine :sa/two (mutating-child plain-child
                                            #(rf.registrar/unregister! :event :sa/two)))
    (rf/reg-machine :sup/allunreg (parent-over [{:id :a :machine-id :sa/one}
                                                {:id :b :machine-id :sa/two}]))
    (rf/dispatch-sync [:sup/allunreg [:start]])
    (let [slot (join-slot :sup/allunreg)]
      (is (contains? slot :children) "a live child-bearing join was seeded")
      (is (not (contains? slot :rf/prepared)) "all prepared scratch consumed")
      (doseq [id (vals (:children slot))]
        (is (some? (snap-of id))
            (str "child " id " installed despite both TYPEs being unregistered mid-drain"))
        (is (map? (type-ref-of id))
            (str "child " id " pinned its prepared definition"))))
    (is (empty? (unregistered-rejects))
        "no duplicate unregistered-type reject fired for either child")))

;; ===========================================================================
;; (3) Cardinality control — the [:schemas :data] validator runs EXACTLY ONCE
;;     BY INSTALL TIME for an admitted child even when the TYPE is unregistered
;;     between the preflight and the install (the recheck, and any second
;;     spawn-time validation, is skipped for the prepared child).
;;
;;     THE SAMPLING INSTRUMENT (rf2-wxy1c). The count used to be sampled in a
;;     trace listener on the child's `:rf.machine.lifecycle/spawned` emit. That
;;     instrument no longer measures what it names: internal drain-owned traces
;;     now deliver at the POST-DRAIN boundary, so the sample is taken after the
;;     actor's later macrosteps have already re-validated a LIVE child's `:data`
;;     — an end-of-drain total, which measures liveness rather than install-time
;;     cardinality (the very confound the old comment warned about, now reached
;;     through the sampling point itself).
;;
;;     The instrument is now the validator's OWN view of the runtime-db: a call
;;     taken while the child's snapshot is absent is BY DEFINITION a pre-install
;;     validation. No listener, no timing assumption, no platform split — and it
;;     still discriminates exactly what the test is about, because the pre-fix
;;     path's second validation ran at the install, ahead of the write.
;; ===========================================================================

(deftest schema-validator-runs-once-by-install-even-when-type-unregistered-mid-drain
  (testing "a counting [:schemas :data] validator runs EXACTLY once by install
            time — the preflight validates it once and the per-child install
            consumes the prepared snapshot rather than re-validating — and the
            child still installs even though its TYPE was unregistered between
            the preflight and the install (the recheck's fail-closed branch is
            skipped for the prepared child)."
    (let [pre-install (atom 0)
          fired       (atom false)]
      (rf/reg-machine :sa/counted
                      {:initial :running
                       :data    {:n 1}
                       :schemas {:data [:fn (fn [v]
                                              ;; A call taken while the child's
                                              ;; snapshot is still absent is a
                                              ;; PRE-INSTALL validation.
                                              (when (nil? (snap-of :sa/counted#1))
                                                (swap! pre-install inc))
                                              ;; Diverge the registrar ONCE, in
                                              ;; the preflight→install window.
                                              (when-not @fired
                                                (reset! fired true)
                                                (rf.registrar/unregister! :event :sa/counted))
                                              (pos-int? (:n v)))]}
                       :states  {:running {}}})
      (rf/reg-machine :sup/count1 (parent-over [{:id :c :machine-id :sa/counted}]))
      (rf/dispatch-sync [:sup/count1 [:start]])
      (is (some? (snap-of :sa/counted#1))
          "the child installed its prepared snapshot despite the mid-drain unregister")
      (is (= 1 @pre-install)
          "the [:schemas :data] validator ran EXACTLY once by install time — the install consumed the prepared result, it did not re-validate")
      (is (map? (type-ref-of :sa/counted#1))
          "the diverged child pinned its prepared definition")
      (is (empty? (unregistered-rejects))
          "no unregistered-type reject fired for the prepared child"))))

;; ===========================================================================
;; rf2-rxjy3 — a prepared child must stay HANDLER-RESOLVABLE, not merely
;; snapshot-present. Consuming the prepared verdict (above) installs the child,
;; but if the snapshot's `:rf/machine-type` still named the (now-unregistered)
;; TYPE keyword, the lazy resolver would resolve NOTHING: the actor would be
;; inert — never bootstrapped, and every later event falling through to
;; `:rf.error/no-such-handler`.
;;
;; DEFINITION-LIFETIME RULE (rf2-rxjy3). A prepared `:spawn-all` child's
;; definition authority is the definition its invoke PREPARED. The install
;; keeps the revertible `:machine-id` KEYWORD reference while the registrar
;; still holds exactly that definition — so ordinary hot-reload semantics are
;; unchanged and live children continue to track a re-registered TYPE (test 6).
;; The moment the registrar has DIVERGED from the prepared definition —
;; unregistered (test 4) or replaced (test 5) — the prepared definition is
;; pinned onto the snapshot verbatim, exactly as an inline `:definition` spawn
;; carries its own spec. So a prepared child is never installed against a
;; MISSING definition, and never mixes a prepared-v1 snapshot with an unrelated
;; current-v2 handler.
;;
;; rf2-zo5n9 refined WHEN that comparison is taken: `spawn-fx*` passes
;; `prepared-type-ref` as a THUNK and `install-spawn!` forces it at the LAST
;; point before the runtime-db swap, so the rule decides against the registrar
;; as it stands at COMMIT. That placement stands unchanged. What no longer
;; exists is a way for APPLICATION code to act between `spawn-fx*`'s bindings
;; and the write: the only callbacks there were the `:rf.machine.spawn/spawned`
;; and `:rf.error/system-id-collision` traces, and under rf2-wxy1c those deliver
;; post-drain. zo5n9's window is closed BY CONSTRUCTION rather than by late
;; selection, so it carries no separate red/green lever and the suites that used
;; to reach it through a listener are folded into the tests below.
;; ===========================================================================

;; ===========================================================================
;; (4) The bug: an admitted child whose TYPE is unregistered mid-drain must be
;;     a LIVE actor — bootstrap runs, and later ordinary events run too.
;; ===========================================================================

(deftest unregistered-prepared-child-is-a-live-resolvable-actor
  (testing "an admitted child whose TYPE was UNREGISTERED between the preflight
            and the install is a LIVE actor, not an inert snapshot: its
            synthetic [:rf.machine.spawn/spawned] bootstrap RESOLVES a handler
            and transitions it :idle → :ready, its :rf/bootstrap-pending? marker
            clears, no :rf.error/no-such-handler fires, and a LATER ordinary
            event still runs (:ready → :working)."
    (let [child (mutating-child booting-child
                                #(rf.registrar/unregister! :event :sa/boot))]
      (rf/reg-machine :sa/boot child)
      (rf/reg-machine :sup/bootunreg (parent-over [{:id :c :machine-id :sa/boot}]))
      (rf/dispatch-sync [:sup/bootunreg [:start]])
      (let [id (get (:children (join-slot :sup/bootunreg)) :c)]
        (is (some? (snap-of id))
            "the admitted child installed its prepared snapshot (rf2-v4oqd)")
        (is (= child (type-ref-of id))
            "the prepared definition is PINNED verbatim — the diverged registrar keyword is not the authority")
        (is (= :ready (rf.machines.test-support/machine-state id))
            "the child COMPLETED its synthetic bootstrap — it resolved a handler despite the mid-drain unregister")
        (is (not (:rf/bootstrap-pending? (snap-of id)))
            "the :rf/bootstrap-pending? marker cleared — the initial-entry cascade actually ran")
        (is (empty? (no-such-handler-errors))
            "NO :rf.error/no-such-handler fired — the prepared child's definition rides its snapshot")
        ;; A LATER ordinary event executes against the same coherent definition.
        (rf/dispatch-sync [id [:go]])
        (is (= :working (rf.machines.test-support/machine-state id))
            "a later ordinary event ran against the prepared definition too")
        (is (empty? (no-such-handler-errors))
            "still no :rf.error/no-such-handler after the ordinary event"))
      (is (empty? (unregistered-rejects))
          "rf2-v4oqd preserved — the prepared verdict was consumed, the registry recheck never re-ran"))))

;; ===========================================================================
;; (5) Adversarial: RE-REGISTER an admitted child TYPE to an UNRELATED v2
;;     between the preflight and the install. The child must run the definition
;;     it was PREPARED from — never a prepared-v1 snapshot driven by a
;;     current-v2 handler (the split-authority failure).
;; ===========================================================================

(deftest reregister-to-unrelated-v2-mid-drain-keeps-one-coherent-authority
  (testing "a mid-drain mutator that RE-REGISTERS an admitted child TYPE to an
            UNRELATED definition cannot split the child's authority: the
            snapshot AND the handler both come from the definition the preflight
            prepared (v1), so the child bootstraps into v1's :ready — never into
            v2's :v2-boot, and never against a v2 definition that does not even
            name v1's states."
    (let [child (mutating-child booting-child
                                #(rf/reg-machine :sa/swap unrelated-v2))]
      (rf/reg-machine :sa/swap child)
      (rf/reg-machine :sup/swap (parent-over [{:id :c :machine-id :sa/swap}]))
      (rf/dispatch-sync [:sup/swap [:start]])
      (let [id (get (:children (join-slot :sup/swap)) :c)]
        (is (= child (type-ref-of id))
            "the prepared v1 definition is pinned on the snapshot — the diverged registrar keyword is not the authority")
        (is (= :ready (rf.machines.test-support/machine-state id))
            "the child bootstrapped through the PREPARED v1 definition (:idle → :ready)")
        (is (not= :v2-boot (rf.machines.test-support/machine-state id))
            "the child did NOT resolve its handler from the unrelated current v2")
        (rf/dispatch-sync [id [:go]])
        (is (= :working (rf.machines.test-support/machine-state id))
            "a later ordinary event ran against v1 too — one coherent authority, not a v1 snapshot on a v2 handler"))
      (is (empty? (no-such-handler-errors))
          "no :rf.error/no-such-handler fired"))))

;; ===========================================================================
;; (6) The other leg of the definition-lifetime rule: with NO mid-drain registry
;;     mutation, the install keeps the REVERTIBLE keyword reference, so ordinary
;;     hot-reload of a TYPE still reaches its live spawned children.
;; ===========================================================================

(deftest undisturbed-prepared-child-keeps-the-revertible-keyword-and-hot-reloads
  (testing "when the registrar still holds exactly the definition the preflight
            prepared, the install stamps the revertible :machine-id KEYWORD (not
            a pinned copy) — so a LATER hot-reload of that TYPE reaches the live
            child, exactly as it does for a single :spawn."
    (rf/reg-machine :sa/hot booting-child)
    (rf/reg-machine :sup/hot (parent-over [{:id :c :machine-id :sa/hot}]))
    (rf/dispatch-sync [:sup/hot [:start]])
    (let [child (get (:children (join-slot :sup/hot)) :c)]
      (is (= :sa/hot (type-ref-of child))
          "the undisturbed install kept the revertible TYPE keyword — no pinned definition copy")
      (is (= :ready (rf.machines.test-support/machine-state child))
          "the child bootstrapped through the registered definition")
      ;; Hot-reload the TYPE: `:go` now targets :hot-reloaded instead of :working.
      (rf/reg-machine :sa/hot booting-child-v2)
      (rf/dispatch-sync [child [:go]])
      (is (= :hot-reloaded (rf.machines.test-support/machine-state child))
          "the live child picked up the hot-reloaded definition — hot-reload semantics preserved"))
    (is (empty? (no-such-handler-errors))
        "no :rf.error/no-such-handler fired")))

;; ===========================================================================
;; (7) The `install-spawn!` interior — two admitted children sharing a
;;     `:system-id` make the second child's install fan the
;;     `:rf.error/system-id-collision` trace and REBIND the name. That install
;;     path is the tightest one the definition-lifetime rule has to hold on, so
;;     it is pinned here with the same non-listener mid-drain mutator: the
;;     rebound child's own validator diverges its TYPE, and its install must
;;     still land a pinned, resolvable definition.
;; ===========================================================================

(deftest system-id-rebound-child-still-pins-its-prepared-definition
  (testing "the colliding child's install — the one that rebinds a shared
            :system-id — still cannot install a stale keyword: the rebound child
            pins its prepared definition and bootstraps to :ready, and exactly
            one collision trace fans for the invoke."
    (let [child-b (mutating-child booting-child
                                  #(rf.registrar/unregister! :event :sa/sys-b))]
      (rf/reg-machine :sa/sys-a booting-child)
      (rf/reg-machine :sa/sys-b child-b)
      (rf/reg-machine :sup/sys (parent-over
                                 [{:id :a :machine-id :sa/sys-a :system-id :sys/shared}
                                  {:id :b :machine-id :sa/sys-b :system-id :sys/shared}]))
      (rf/dispatch-sync [:sup/sys [:start]])
      (let [slot  (join-slot :sup/sys)
            child (get (:children slot) :b)]
        (is (= 1 (count (rf.machines.test-support/events-of :rf.error/system-id-collision)))
            "the shared :system-id fanned exactly one collision trace (the second child's install)")
        (is (= child-b (type-ref-of child))
            "the rebound child pinned its prepared definition")
        (is (= :ready (rf.machines.test-support/machine-state child))
            "the rebound child bootstrapped — it is live, not inert")
        (is (empty? (no-such-handler-errors))
            "no :rf.error/no-such-handler fired")))))
