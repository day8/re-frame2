(ns re-frame.spawn-all-prepared-unregister-cljs-test
  "An ADMITTED+prepared `:spawn-all` child CONSUMES its invoke's authoritative
  preflight verdict — `spawn-fx` must NOT re-run its child-local
  `unregistered-spawn-type?` registry recheck against an already-prepared child
  (rf2-v4oqd).

  `spawn-all-init-fx` (the FIRST fx in the entry vector) resolves, stamps,
  builds, and validates every declarative `:spawn-all` child EXACTLY ONCE and
  RETAINS the prepared result under the join slot's `:rf/prepared` scratch
  (rf2-ek435). But the per-child `:rf.machine/spawn` fx STILL consulted the
  CURRENT registry via `unregistered-spawn-type?` BEFORE reading its keyed
  prepared entry. A `:rf.machine.spawn-all/started` listener that UNREGISTERED
  the admitted child TYPE between the preflight and the install then flipped the
  already-admitted child to rejected: the per-child spawn emitted a SECOND
  `:rf.error/machine-spawn-unregistered-type` reject and installed NO snapshot —
  after the live child-bearing join had already been published. The result was
  the exact impossible half-live join (a join naming a child whose snapshot a
  second verdict omitted) the authoritative handoff exists to make impossible.

  The sibling `spawn_all_authoritative_preflight` suite (rf2-ek435) pins the
  RE-REGISTER seam (the type stays present, so the recheck passed through) and
  the all-valid validator cardinality. This suite pins the UNREGISTER seam that
  note called out as NOT covered — the recheck's fail-closed branch — on JVM +
  CLJS."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading the machines facade registers `rf/reg-machine` + the reserved
   ;; machine fxs when this ns runs alone.
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   ;; Unregister a machine TYPE mid-drain (from the started-listener) by
   ;; dropping its `:event` registrar entry — the seam the recheck tripped on.
   [re-frame.registrar :as registrar]
   ;; The schemas artefact ships the registered-validator hot path the
   ;; `:where :machine-data` boundary routes through; the `.malli` adapter
   ;; publishes Malli's validate/explain into the late-bind table.
   [re-frame.schemas]
   [re-frame.schemas.malli]
   [re-frame.trace :as trace]
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

(defn- unregistered-rejects []
  (mtest/events-of :rf.error/machine-spawn-unregistered-type))

(defn- no-such-handler-errors []
  (mtest/events-of :rf.error/no-such-handler))

(defn- type-ref-of
  "The installed snapshot's revertible `:rf/machine-type` TYPE reference — the
  keyword the lazy resolver reads back through the registrar, or the pinned
  definition map."
  [actor-id]
  (:rf/machine-type (snap-of actor-id)))

(defn- on-started-once
  "Register a synchronous trace listener that runs `f` exactly once, on the
  first `:rf.machine.spawn-all/started` emit (fired by `spawn-all-init-fx`
  AFTER it publishes the live join, BEFORE the per-child spawn fxs run). Returns
  an unregister thunk."
  [listener-id f]
  (let [fired (atom false)]
    (trace/register-listener!
      listener-id
      (fn [ev]
        (when (and (= :rf.machine.spawn-all/started (:operation ev))
                   (not @fired))
          (reset! fired true)
          (f))))
    #(trace/unregister-listener! listener-id)))

;; ===========================================================================
;; (1) THE BUG — a started-listener UNREGISTERS an admitted child TYPE between
;;     the preflight and the install. The child must STILL install from its
;;     prepared entry, with no duplicate reject and a fully-live join.
;; ===========================================================================

(deftest unregister-between-preflight-and-install-still-installs-the-admitted-child
  (testing "a :rf.machine.spawn-all/started listener that UNREGISTERS an admitted
            child TYPE cannot flip the already-prepared child into a rejected one:
            the per-child install consumes the prepared entry rather than
            re-consulting the (now-empty) registry, so the child installs its
            prepared snapshot, the join stays fully live, and NO duplicate
            :rf.error/machine-spawn-unregistered-type reject fires (the pre-fix
            recheck rejected the child + stranded a snapshotless half-live join)."
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/unreg (parent-over [{:id :a :machine-id :sa/plain}
                                             {:id :b :machine-id :sa/plain}]))
    (let [off (on-started-once ::unreg
                #(registrar/unregister! :event :sa/plain))]
      (try
        (rf/dispatch-sync [:sup/unreg [:start]])
        (finally (off))))
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
        (is (= :running (mtest/machine-state id))
            (str "child " id " installed the PREPARED spec verbatim (state :running), not a re-derivation"))))
    (is (empty? (unregistered-rejects))
        "NO :rf.error/machine-spawn-unregistered-type reject fired — the recheck was skipped for the prepared child")))

;; ===========================================================================
;; (2) Adversarial: unregister BOTH admitted children between preflight and
;;     install — every admitted child still installs, no reject fans.
;; ===========================================================================

(deftest unregister-all-children-between-preflight-and-install-installs-all
  (testing "when the started listener unregisters EVERY child TYPE, EVERY
            admitted child still installs from its prepared entry — the whole
            batch consumes its authoritative verdict; no reject, no partial install."
    (rf/reg-machine :sa/one plain-child)
    (rf/reg-machine :sa/two plain-child)
    (rf/reg-machine :sup/allunreg (parent-over [{:id :a :machine-id :sa/one}
                                                {:id :b :machine-id :sa/two}]))
    (let [off (on-started-once ::allunreg
                (fn []
                  (registrar/unregister! :event :sa/one)
                  (registrar/unregister! :event :sa/two)))]
      (try
        (rf/dispatch-sync [:sup/allunreg [:start]])
        (finally (off))))
    (let [slot (join-slot :sup/allunreg)]
      (is (contains? slot :children) "a live child-bearing join was seeded")
      (is (not (contains? slot :rf/prepared)) "all prepared scratch consumed")
      (doseq [id (vals (:children slot))]
        (is (some? (snap-of id))
            (str "child " id " installed despite both TYPEs being unregistered mid-drain"))))
    (is (empty? (unregistered-rejects))
        "no duplicate unregistered-type reject fired for either child")))

;; ===========================================================================
;; (3) Cardinality control — the [:schemas :data] validator runs EXACTLY ONCE
;;     BY INSTALL TIME for an admitted child even when the TYPE is unregistered
;;     between the preflight and the install (the recheck, and any second
;;     spawn-time validation, is skipped for the prepared child).
;;
;;     The count is sampled at the child's `:rf.machine.lifecycle/spawned`
;;     trace, exactly as ek435's sibling `each-spawn-all-child-is-validated-
;;     exactly-once-per-attempt` does — NOT at the end of the drain. A LIVE
;;     actor re-validates its `:data` at every later macrostep (its synthetic
;;     bootstrap included), so an end-of-drain total measures liveness, not
;;     install-time cardinality. This assertion previously read `(= 1 @calls)`
;;     at the end of the drain and passed only because the unregistered child
;;     was INERT and never bootstrapped — the false-green rf2-rxjy3 names.
;; ===========================================================================

(deftest schema-validator-runs-once-by-install-even-when-type-unregistered-mid-drain
  (testing "a counting [:schemas :data] validator runs EXACTLY once by install
            time — the preflight validates it once and the per-child install
            consumes the prepared snapshot rather than re-validating — and the
            child still installs even though its TYPE was unregistered between
            the preflight and the install (the recheck's fail-closed branch is
            skipped for the prepared child)."
    (let [calls       (atom 0)
          at-install  (atom nil)
          listener-id ::count-at-install]
      (rf/reg-machine :sa/counted
                      {:initial :running
                       :data    {:n 1}
                       :schemas {:data [:fn (fn [v] (swap! calls inc) (pos-int? (:n v)))]}
                       :states  {:running {}}})
      (rf/reg-machine :sup/count1 (parent-over [{:id :c :machine-id :sa/counted}]))
      (trace/register-listener!
        listener-id
        (fn [ev]
          (when (and (= :rf.machine.lifecycle/spawned (:operation ev))
                     (= :sa/counted#1 (get-in ev [:tags :spawned-id]))
                     (nil? @at-install))
            (reset! at-install @calls))))
      (let [off (on-started-once ::count1
                  #(registrar/unregister! :event :sa/counted))]
        (try
          (rf/dispatch-sync [:sup/count1 [:start]])
          (finally
            (off)
            (trace/unregister-listener! listener-id))))
      (is (some? (snap-of :sa/counted#1))
          "the child installed its prepared snapshot despite the mid-drain unregister")
      (is (= 1 @at-install)
          "the [:schemas :data] validator ran EXACTLY once by install time — the install consumed the prepared result, it did not re-validate")
      (is (empty? (unregistered-rejects))
          "no unregistered-type reject fired for the prepared child"))))

;; ===========================================================================
;; rf2-rxjy3 — a prepared child must stay HANDLER-RESOLVABLE, not merely
;; snapshot-present. Consuming the prepared verdict (above) installs the child,
;; but the snapshot's `:rf/machine-type` still named the (now-unregistered) TYPE
;; keyword, so the lazy resolver resolved NOTHING: the actor was inert — it
;; never bootstrapped and every later event fell through to
;; `:rf.error/no-such-handler`.
;;
;; DEFINITION-LIFETIME RULE (rf2-rxjy3). A prepared `:spawn-all` child's
;; definition authority is the definition its invoke PREPARED. The install
;; keeps the revertible `:machine-id` KEYWORD reference while the registrar
;; still holds exactly that definition — so ordinary hot-reload semantics are
;; unchanged and live children continue to track a re-registered TYPE (test 7).
;; The moment the registrar has DIVERGED from the prepared definition —
;; unregistered (test 4) or replaced (test 6) — the prepared definition is
;; pinned onto the snapshot verbatim, exactly as an inline `:definition` spawn
;; carries its own spec. So a prepared child is never installed against a
;; MISSING definition, and never mixes a prepared-v1 snapshot with an unrelated
;; current-v2 handler.
;; ===========================================================================

;; ===========================================================================
;; (4) The bug: an admitted child whose TYPE is unregistered mid-drain must be
;;     a LIVE actor — bootstrap runs, and later ordinary events run too.
;; ===========================================================================

(deftest unregistered-prepared-child-is-a-live-resolvable-actor
  (testing "an admitted child whose TYPE a started-listener UNREGISTERED between
            the preflight and the install is a LIVE actor, not an inert
            snapshot: its synthetic [:rf.machine.spawn/spawned] bootstrap
            RESOLVES a handler and transitions it :idle → :ready, its
            :rf/bootstrap-pending? marker clears, no :rf.error/no-such-handler
            fires, and a LATER ordinary event still runs (:ready → :working)."
    (rf/reg-machine :sa/boot booting-child)
    (rf/reg-machine :sup/bootunreg (parent-over [{:id :c :machine-id :sa/boot}]))
    (let [off (on-started-once ::bootunreg
                #(registrar/unregister! :event :sa/boot))]
      (try
        (rf/dispatch-sync [:sup/bootunreg [:start]])
        (finally (off))))
    (let [child (get (:children (join-slot :sup/bootunreg)) :c)]
      (is (some? (snap-of child))
          "the admitted child installed its prepared snapshot (rf2-v4oqd)")
      (is (= :ready (mtest/machine-state child))
          "the child COMPLETED its synthetic bootstrap — it resolved a handler despite the mid-drain unregister")
      (is (not (:rf/bootstrap-pending? (snap-of child)))
          "the :rf/bootstrap-pending? marker cleared — the initial-entry cascade actually ran")
      (is (empty? (no-such-handler-errors))
          "NO :rf.error/no-such-handler fired — the prepared child's definition rides its snapshot")
      ;; A LATER ordinary event executes against the same coherent definition.
      (rf/dispatch-sync [child [:go]])
      (is (= :working (mtest/machine-state child))
          "a later ordinary event ran against the prepared definition too")
      (is (empty? (no-such-handler-errors))
          "still no :rf.error/no-such-handler after the ordinary event"))
    (is (empty? (unregistered-rejects))
        "rf2-v4oqd preserved — the prepared verdict was consumed, the registry recheck never re-ran")))

;; ===========================================================================
;; (5) Adversarial: EVERY child's TYPE unregistered mid-drain — every child is
;;     a live, bootstrapped actor, not just snapshot-present.
;; ===========================================================================

(deftest all-children-stay-live-when-every-type-unregistered-mid-drain
  (testing "when the started listener unregisters EVERY admitted child TYPE, every
            child still bootstraps to :ready and no :rf.error/no-such-handler
            fires for any of them — the whole batch stays handler-resolvable."
    (rf/reg-machine :sa/boot-a booting-child)
    (rf/reg-machine :sa/boot-b booting-child)
    (rf/reg-machine :sup/bootall (parent-over [{:id :a :machine-id :sa/boot-a}
                                               {:id :b :machine-id :sa/boot-b}]))
    (let [off (on-started-once ::bootall
                (fn []
                  (registrar/unregister! :event :sa/boot-a)
                  (registrar/unregister! :event :sa/boot-b)))]
      (try
        (rf/dispatch-sync [:sup/bootall [:start]])
        (finally (off))))
    (let [slot (join-slot :sup/bootall)]
      (is (= 2 (count (:children slot))) "the join names both admitted children")
      (doseq [id (vals (:children slot))]
        (is (= :ready (mtest/machine-state id))
            (str "child " id " bootstrapped — its prepared definition rides its snapshot"))
        (is (map? (type-ref-of id))
            (str "child " id " pinned its prepared definition (the registrar no longer holds it)"))))
    (is (empty? (no-such-handler-errors))
        "no :rf.error/no-such-handler fired for either child")
    (is (empty? (unregistered-rejects))
        "no duplicate unregistered-type reject fired for either child")))

;; ===========================================================================
;; (6) Adversarial: RE-REGISTER an admitted child TYPE to an UNRELATED v2
;;     between the preflight and the install. The child must run the definition
;;     it was PREPARED from — never a prepared-v1 snapshot driven by a
;;     current-v2 handler (the split-authority failure).
;; ===========================================================================

(deftest reregister-to-unrelated-v2-mid-drain-keeps-one-coherent-authority
  (testing "a started listener that RE-REGISTERS an admitted child TYPE to an
            UNRELATED definition cannot split the child's authority: the
            snapshot AND the handler both come from the definition the preflight
            prepared (v1), so the child bootstraps into v1's :ready — never into
            v2's :v2-boot, and never against a v2 definition that does not even
            name v1's states."
    (rf/reg-machine :sa/swap booting-child)
    (rf/reg-machine :sup/swap (parent-over [{:id :c :machine-id :sa/swap}]))
    (let [off (on-started-once ::swap
                #(rf/reg-machine :sa/swap unrelated-v2))]
      (try
        (rf/dispatch-sync [:sup/swap [:start]])
        (finally (off))))
    (let [child (get (:children (join-slot :sup/swap)) :c)]
      (is (= :ready (mtest/machine-state child))
          "the child bootstrapped through the PREPARED v1 definition (:idle → :ready)")
      (is (not= :v2-boot (mtest/machine-state child))
          "the child did NOT resolve its handler from the unrelated current v2")
      (is (= booting-child (type-ref-of child))
          "the prepared v1 definition is pinned on the snapshot — the diverged registrar keyword is not the authority")
      (rf/dispatch-sync [child [:go]])
      (is (= :working (mtest/machine-state child))
          "a later ordinary event ran against v1 too — one coherent authority, not a v1 snapshot on a v2 handler"))
    (is (empty? (no-such-handler-errors))
        "no :rf.error/no-such-handler fired")))

;; ===========================================================================
;; (7) The other leg of the definition-lifetime rule: with NO mid-drain registry
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
      (is (= :ready (mtest/machine-state child))
          "the child bootstrapped through the registered definition")
      ;; Hot-reload the TYPE: `:go` now targets :hot-reloaded instead of :working.
      (rf/reg-machine :sa/hot booting-child-v2)
      (rf/dispatch-sync [child [:go]])
      (is (= :hot-reloaded (mtest/machine-state child))
          "the live child picked up the hot-reloaded definition — hot-reload semantics preserved"))
    (is (empty? (no-such-handler-errors))
        "no :rf.error/no-such-handler fired")))
