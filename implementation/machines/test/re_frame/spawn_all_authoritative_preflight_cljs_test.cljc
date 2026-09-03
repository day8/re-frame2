(ns re-frame.spawn-all-authoritative-preflight-cljs-test
  "The `:spawn-all` admission preflight's VERDICT and its PREPARED CHILDREN are
  AUTHORITATIVE (rf2-ek435).

  `spawn-all-init-fx` (the FIRST fx in the entry vector) resolves, stamps,
  builds, and `[:schemas :data]`-validates every declarative `:spawn-all` child
  EXACTLY ONCE for an attempt, then RETAINS the prepared result — the resolved
  spec + built snapshot per admitted child — under the join slot's reserved
  `:rf/prepared` scratch. Each child's own `:rf.machine/spawn` fx (a SEPARATE
  entry later in the same vector) then CONSUMES its prepared entry rather than
  re-resolving the type, rebuilding the snapshot, or re-running the validator.

  Before this fix `spawn-all-init-fx` retained only a BOOLEAN verdict, so every
  accepted per-child spawn re-resolved / rebuilt / re-validated the same child:

   - a direct all-valid invoke observed TWO validator calls per child, and
   - a mid-drain re-registration of the child TYPE between the two passes meant
     the preflight accepted v1 and published a live child-bearing join while the
     per-child install resolved a now-rejecting v2 and installed NO snapshot —
     leaving an impossible half-live join naming a child whose snapshot a SECOND
     verdict omitted.

  MID-DRAIN INSTRUMENTS (rf2-wxy1c). Both the validator-cardinality sample and
  the mid-drain re-registration used to run in a TRACE LISTENER. Under the
  rf2-wxy1c ruling trace listeners are OBSERVERS: internal drain-owned emits
  deliver at the post-drain boundary on every platform, so a listener body can
  neither sample nor mutate inside a drain. Both instruments now ride the child's
  own `[:schemas :data]` validator — ordinary in-drain application code the
  framework calls synchronously, positioned by `prepare-spawn-all-child` strictly
  after the child's TYPE was resolved + retained and strictly before its install.
  Platform-uniform: schema validation is not reader-conditional.

  Pinned here (JVM + CLJS):

   1. An all-valid child's `[:schemas :data]` validator runs EXACTLY ONCE per
      attempt (the install consumes the prepared result, it does not re-validate).
   2. A type re-registration between the preflight and the install cannot omit
      the child's snapshot — the install consumes the prepared v1, never the
      re-registered v2 — so the join is never child-bearing-but-snapshotless.
   3. An all-valid invoke still seeds a live join, and the ephemeral prepared
      scratch is consumed + dropped: the durable join state retains none.
   4. A partial-reject batch (one schema-invalid child among valid siblings)
      still rejects atomically — one childless sentinel, no sibling installed,
      exactly one spawn-phase failure, and no prepared scratch."
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

(def ^:private strict-child
  "A child TYPE whose initial `:data` is schema-checked at spawn time. Its
  registered default `{:n 1}` conforms to `pos-int?`."
  {:initial :running
   :data    {:n 1}
   :schemas {:data [:map [:n pos-int?]]}
   :states  {:running {:on {:finish {:target :done}}}
             :done    {:final? true}}})

(def ^:private plain-child
  "An unconstrained sibling — a real live actor if anything installs."
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
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:forking]]))

(defn- snap-of [actor-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :snapshots actor-id]))

(defn- spawn-phase-failures []
  (filterv #(= :spawn (get-in % [:tags :phase]))
           (rf.machines.test-support/events-of :rf.error/schema-validation-failure)))

;; ===========================================================================
;; (1) Validator cardinality — the accepted per-child install does NOT
;;     re-validate; each child is validated EXACTLY ONCE per attempt.
;; ===========================================================================

(deftest each-spawn-all-child-is-validated-exactly-once-per-attempt
  (testing "an all-valid :spawn-all child's [:schemas :data] validator runs
            EXACTLY once BY INSTALL TIME for the attempt — the preflight prepares
            + validates it and the per-child install CONSUMES that prepared
            snapshot rather than re-validating (the pre-fix all-valid path
            observed TWO calls, the second at the install)."
    (let [pre-install (atom 0)]
      ;; A conforming schema that counts every validate call taken BEFORE the
      ;; child's snapshot exists. It passes ({:n 1} is a pos-int?), so the child
      ;; installs on both the fixed and the pre-fix path — the DISCRIMINATOR is
      ;; how many times it was validated by install time, not whether it
      ;; installed.
      ;;
      ;; THE SAMPLING INSTRUMENT (rf2-wxy1c). This count used to be sampled in a
      ;; trace listener on `:rf.machine.lifecycle/spawned`. Internal drain-owned
      ;; traces now deliver at the POST-DRAIN boundary, so that sample is taken
      ;; after the actor's later macrosteps have re-validated a LIVE child's
      ;; `:data` — it measures liveness, not install-time cardinality. The
      ;; validator's own view of the runtime-db has no such assumption: a call
      ;; taken while the snapshot is absent is BY DEFINITION pre-install. No
      ;; listener, no timing assumption, no platform split.
      (rf/reg-machine :sa/counted
                      {:initial :running
                       :data    {:n 1}
                       :schemas {:data [:fn (fn [v]
                                              (when (nil? (snap-of :sa/counted#1))
                                                (swap! pre-install inc))
                                              (pos-int? (:n v)))]}
                       :states  {:running {}}})
      (rf/reg-machine :sup/count (parent-over [{:id :c :machine-id :sa/counted}]))
      (rf/dispatch-sync [:sup/count [:start]])
      (is (some? (snap-of :sa/counted#1))
          "(precondition) the conforming child installed a live snapshot")
      (is (= 1 @pre-install)
          "validated EXACTLY once by install time — the preflight prepared it and
           the install consumed that result (the pre-fix path re-validated → 2)"))))

;; ===========================================================================
;; (2) Type re-registration between the preflight and the install cannot omit
;;     the child's snapshot — the authoritative repro of the half-live join.
;; ===========================================================================

(deftest type-reregistration-between-preflight-and-install-cannot-omit-a-snapshot
  (testing "a mid-drain mutator that RE-REGISTERS a child TYPE to a now-rejecting
            spec cannot flip the admitted child into a rejected one: the
            per-child install consumes the preflight's prepared v1 snapshot
            rather than re-resolving + re-validating the v2 that landed, so the
            child installs and the join is FULLY live — never a child-bearing
            join whose snapshot a SECOND verdict omitted."
    ;; THE MID-DRAIN MUTATOR (rf2-wxy1c). This used to re-register from a
    ;; `:rf.machine.spawn-all/started` trace listener. Internal drain-owned emits
    ;; now deliver POST-DRAIN on every platform, so a listener body can no longer
    ;; run in the preflight→install window. The child's own `[:schemas :data]`
    ;; validator still can, and is ordinary in-drain application code:
    ;; `prepare-spawn-all-child` resolves + retains the TYPE, builds the
    ;; snapshot, THEN runs the validator — so a registrar mutation from inside it
    ;; lands strictly after the child was prepared and strictly before its
    ;; install.
    (let [fired (atom false)]
      ;; v1 — pos-int?, data {:n 1}, conforms. Its validator swaps the registrar
      ;; to a v2 whose schema REJECTS the very {:n 1} data the preflight is
      ;; admitting in this very call. A pre-fix install re-resolves that v2 and
      ;; rejects the child, installing no snapshot.
      (rf/reg-machine :sa/strict
                      (assoc strict-child
                             :schemas
                             {:data [:fn (fn [v]
                                           (when-not @fired
                                             (reset! fired true)
                                             (rf/reg-machine
                                               :sa/strict
                                               (assoc strict-child
                                                      :schemas {:data [:map [:n neg-int?]]})))
                                           (pos-int? (:n v)))]}))
      (rf/reg-machine :sup/rereg (parent-over [{:id :c :machine-id :sa/strict}]))
      (rf/dispatch-sync [:sup/rereg [:start]])
      (is (true? @fired)
          "(precondition) the mid-drain mutator ran and re-registered the type"))
    (let [slot (join-slot :sup/rereg)]
      (is (contains? slot :children)
          "a LIVE child-bearing join was seeded")
      (is (= {:c :sa/strict#1} (:children slot))
          "the join names the admitted child at its allocated id"))
    ;; The install fired its lifecycle trace AND landed the snapshot — proof it
    ;; consumed the prepared v1 rather than re-resolving/re-validating v2 (which
    ;; would have omitted the snapshot, stranding an impossible half-live join).
    (is (seq (filterv #(= :sa/strict#1 (get-in % [:tags :spawned-id]))
                      (rf.machines.test-support/events-of :rf.machine.lifecycle/spawned)))
        "the child's install COMMITTED (a lifecycle/spawned trace fired) — a second verdict did not omit it")
    (is (some? (snap-of :sa/strict#1))
        "the child's snapshot INSTALLED — the install consumed the preflight's prepared v1")
    (is (= :running (rf.machines.test-support/machine-state :sa/strict#1))
        "the installed snapshot is v1's (state :running) — the prepared result was consumed verbatim, not re-derived from v2")))

;; ===========================================================================
;; (3) All-valid keeps the live join AND retains no prepared scratch.
;; ===========================================================================

(deftest all-valid-seeds-a-live-join-and-retains-no-prepared-scratch
  (testing "an all-valid :spawn-all seeds a LIVE child-bearing join and every
            child installs; the authoritative :rf/prepared scratch the preflight
            retained is CONSUMED and dropped by the installs, so the durable
            join state carries none once the drain completes."
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/clean (parent-over [{:id :a :machine-id :sa/plain}
                                             {:id :b :machine-id :sa/plain}]))
    (rf/dispatch-sync [:sup/clean [:start]])
    (let [slot (join-slot :sup/clean)
          ids  (vals (:children slot))]
      (is (contains? slot :children)
          "a live child-bearing join — no false reject")
      (is (= 2 (count ids))
          "the join names both children")
      (is (not (contains? slot :rf/prepared))
          "the ephemeral prepared scratch was consumed + dropped — the durable join retains none")
      (doseq [id ids]
        (is (some? (snap-of id))
            (str "child " id " installed its snapshot"))))))

;; ===========================================================================
;; (4) Adversarial: a partial-reject batch still rejects atomically and retains
;;     no prepared scratch.
;; ===========================================================================

(deftest partial-reject-batch-rejects-atomically-with-no-prepared-scratch
  (testing "a batch with ONE schema-invalid child among valid siblings rejects
            the WHOLE invoke: one childless reject sentinel (never a prepared
            join), no sibling installed, and exactly one spawn-phase schema
            failure — the invalid child validated ONCE, its siblings suppressed."
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/partial
                    (parent-over [{:id :ok  :machine-id :sa/plain}
                                  {:id :bad :machine-id :sa/strict :data {:n -1}}
                                  {:id :ok2 :machine-id :sa/plain}]))
    (rf/dispatch-sync [:sup/partial [:start]])
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/partial))
        "one childless reject sentinel — exactly this map, so NO :rf/prepared scratch leaked onto the reject path")
    (is (nil? (snap-of :sa/strict#1))
        "the schema-invalid child installed nothing")
    (is (nil? (snap-of :sa/plain#1))
        "no valid sibling installed — the reject is ATOMIC")
    (is (nil? (snap-of :sa/plain#2))
        "no valid sibling installed — the reject is ATOMIC")
    (is (= 1 (count (spawn-phase-failures)))
        "exactly ONE :phase :spawn failure — the invalid child validated once, never re-validated under a suppressed per-child spawn")))
