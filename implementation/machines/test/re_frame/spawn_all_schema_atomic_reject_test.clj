(ns re-frame.spawn-all-schema-atomic-reject-test
  "ATOMIC `:spawn-all` reject when any child fails spawn-time
  `[:schemas :data]` validation (rf2-7u8gen).

  Spawn-time schema validation used to run ONLY inside each per-child
  `:rf.machine/spawn` fx — downstream of `spawn-all-init-fx`, which had
  already published a LIVE join naming every child. A mixed
  valid/schema-invalid invoke therefore:

   - published a live join slot containing the invalid child's id,
   - installed the VALID siblings as real live actors,
   - never installed the invalid child (correctly — it has no snapshot),

  leaving an IMPOSSIBLE join: `join.cljc` requires every declared child for
  `:join :all`, but a child with no snapshot can never emit completion. The
  parent waits in the join state forever while its valid siblings stay live,
  owned by a join that can never resolve. That is precisely the
  dead-join/orphan class the unregistered-type sentinel (rf2-qb1j5z) exists to
  remove, and it violates Spec 005's promise that a parent never observes a
  half-installed child.

  The repair makes the FIRST invoke-level decision (`spawn-all-init-fx`'s
  preflight) authoritative for EVERY fail-closed child-admission condition —
  unregistered TYPE *and* spawn-time schema validity — by preflighting the
  PREPARED per-child spawn args the transition reducer threads onto the init
  fx as `:child-args`. Any rejection seeds one childless reject sentinel and
  every per-child effect suppresses under it, valid siblings included.

  Pinned here:

   1. A mixed valid/invalid invoke installs NOTHING and seeds one childless
      reject sentinel — invalid child first, middle, or last.
   2. Exactly ONE schema error per invalid child.
   3. The join can never observe a half-installed child set, in any `:join`
      mode — the reject is decided upstream of the join mode entirely.
   4. Parent exit clears the sentinel; no valid sibling is left orphaned.
   5. No false reject: an all-valid `:spawn-all` keeps the fast path.

  Moving schema validation back into only the later per-child effect makes
  (1)–(4) fail: the live join publishes, the valid sibling installs, and the
  invalid child is stranded inside it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines facade registers `rf/reg-machine` + the
            ;; reserved machine fxs when this ns runs alone.
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; The schemas artefact ships the registered-validator hot path the
            ;; `:where :machine-data` boundary routes through; the `.malli`
            ;; adapter publishes Malli's validate/explain into the late-bind
            ;; table.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-db rf.machines.test-support/runtime-db)

(def ^:private ChildSchema
  "`:n` must be a positive int — the spawn-time `[:schemas :data]` gate."
  [:map [:n pos-int?]])

(def ^:private strict-child
  "A child TYPE whose initial `:data` is schema-checked at spawn time. Its
  registered default conforms; a per-child `:data` override is what violates."
  {:initial :running
   :data    {:n 1}
   :schemas {:data ChildSchema}
   :states  {:running {:on {:finish {:target :done}}}
             :done    {:final? true}}})

(def ^:private plain-child
  "An unconstrained sibling — a real live actor if anything installs."
  {:initial :running
   :data    {}
   :states  {:running {}}})

(defn- parent-over
  "A `:spawn-all` parent over `children` with join mode `join`."
  ([children] (parent-over children :all))
  ([children join]
   {:initial :idle
    :states
    {:idle    {:on {:start :forking}}
     :forking {:spawn-all {:children        children
                           :join            join
                           :on-all-complete [:all/done]
                           :on-some-complete [:some/done]}
               :on {:all/done :ready :some/done :ready :back :idle}}
     :ready   {}}}))

(defn- schema-failures
  "Every `:rf.error/schema-validation-failure` trace with `:phase :spawn`."
  []
  (filterv #(= :spawn (get-in % [:tags :phase]))
           (rf.machines.test-support/events-of :rf.error/schema-validation-failure)))

(defn- join-slot [parent-id]
  (get-in (frame-db) [:rf.runtime/machines :spawned parent-id [:forking]]))

(defn- snapshot-of [actor-id]
  (get-in (frame-db) [:rf.runtime/machines :snapshots actor-id]))

;; ===========================================================================
;; (1) Mixed valid/invalid — atomic reject, invalid child in every position.
;; ===========================================================================

(deftest mixed-invalid-child-rejects-the-whole-invoke-atomically
  (testing "a schema-invalid child rejects the WHOLE invoke before any live
            join or child effect is published: no snapshots, no system-id
            bindings, no spawn-order entries, no spawned traces — and the join
            slot is ONE childless reject sentinel, not a live join naming an
            impossible child"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/mixed
                    (parent-over [{:id :bad :machine-id :sa/strict :data {:n -1}}
                                  {:id :ok  :machine-id :sa/plain
                                   :system-id :ok-actor}]))
    (rf/dispatch-sync [:sup/mixed [:start]])
    ;; The join slot is a childless reject sentinel — NOT a live join.
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/mixed))
        "one childless reject sentinel — no live join naming the impossible child")
    (is (not (contains? (join-slot :sup/mixed) :children))
        "the sentinel carries NO :children — nothing can ever be joined on")
    ;; NOTHING installed — for the invalid child OR its valid sibling.
    (is (nil? (snapshot-of :sa/strict#1))
        "the schema-invalid child installs no snapshot")
    (is (nil? (snapshot-of :sa/plain#1))
        "the VALID sibling is suppressed too — the reject is ATOMIC")
    (is (nil? (get-in (frame-db) [:rf.runtime/machines :system-ids :ok-actor]))
        "no :system-id binding for the suppressed sibling")
    (is (empty? (filterv #{:sa/strict#1 :sa/plain#1}
                         (rf.machines.spawn-order/frame-order :rf/default)))
        "no spawn-order entry for either child")
    (is (empty? (rf.machines.test-support/events-of :rf.machine/spawn-all-started))
        "no spawn-all started trace for a rejected invoke")
    (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
        "no :rf.machine.spawn/spawned trace for ANY child")
    (is (empty? (rf.machines.test-support/events-of :rf.machine.lifecycle/spawned))
        "no :rf.machine.lifecycle/spawned trace either — nothing landed")
    ;; Exactly one schema error, naming the offending child.
    (is (= 1 (count (schema-failures)))
        "exactly ONE :phase :spawn schema failure — for the one invalid child")
    (is (= :forking (rf.machines.test-support/machine-state :sup/mixed))
        "the parent stays in :forking — a refused join, not a deadlock")))

(deftest invalid-child-position-does-not-change-the-reject
  (testing "the invoke-level preflight sees the child set as a SET: an invalid
            child FIRST, in the MIDDLE, or LAST rejects identically. Child
            ordering never repairs (or creates) the architecture"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (let [bad {:id :bad :machine-id :sa/strict :data {:n -1}}
          ok1 {:id :ok1 :machine-id :sa/plain}
          ok2 {:id :ok2 :machine-id :sa/plain}]
      (doseq [[pid children label]
              [[:sup/first  [bad ok1 ok2] "invalid FIRST"]
               [:sup/middle [ok1 bad ok2] "invalid MIDDLE"]
               [:sup/last   [ok1 ok2 bad] "invalid LAST"]]]
        (rf/reg-machine pid (parent-over children))
        (rf/dispatch-sync [pid [:start]])
        (is (= {:rf/spawn-all-rejected? true} (join-slot pid))
            (str label " — childless reject sentinel"))
        (is (nil? (snapshot-of :sa/plain#1))
            (str label " — no valid sibling installed"))
        (is (nil? (snapshot-of :sa/strict#1))
            (str label " — no invalid child installed"))))))

;; ===========================================================================
;; (2) EXACT schema-error cardinality.
;; ===========================================================================

(deftest exactly-one-schema-error-per-invalid-child
  (testing "N schema-invalid children emit EXACTLY N :phase :spawn failures —
            one per offending child. The preflight decides each child once and
            every per-child effect then suppresses under the sentinel, so no
            child is validated (or reported) twice"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/two-bad
                    (parent-over [{:id :bad1 :machine-id :sa/strict :data {:n -1}}
                                  {:id :ok   :machine-id :sa/plain}
                                  {:id :bad2 :machine-id :sa/strict :data {:n -2}}]))
    (rf/dispatch-sync [:sup/two-bad [:start]])
    (let [failures (schema-failures)]
      (is (= 2 (count failures))
          "exactly TWO schema failures — one per invalid child, never doubled")
      (is (= [{:n -2} {:n -1}]
             (sort-by :n (mapv #(select-keys (get-in % [:tags :value]) [:n])
                               failures)))
          "each failure carries its own offending value — distinct children"))
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/two-bad))
        "still exactly one childless reject sentinel for the invoke")))

;; ===========================================================================
;; (3) No join mode can observe a half-installed child set.
;; ===========================================================================

(deftest no-join-mode-observes-a-half-installed-child-set
  (testing ":all and :any alike see one childless reject sentinel — the
            admission decision is taken in spawn-all-init BEFORE the join mode
            is ever consulted, so the mode cannot change the outcome"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (doseq [[pid mode] [[:sup/join-all :all]
                        [:sup/join-any :any]]]
      (rf/reg-machine pid (parent-over [{:id :bad :machine-id :sa/strict :data {:n -1}}
                                        {:id :ok  :machine-id :sa/plain}]
                                       mode))
      (rf/dispatch-sync [pid [:start]])
      (is (= {:rf/spawn-all-rejected? true} (join-slot pid))
          (str ":join " mode " — childless reject sentinel, no live join"))
      (is (nil? (snapshot-of :sa/plain#1))
          (str ":join " mode " — no half-installed sibling to observe")))))

(deftest completion-against-a-rejected-invoke-is-a-noop-not-a-hang
  (testing "a stray completion delivered after an invoke-level schema reject
            finds the childless sentinel and is the documented no-op — the
            join cannot be driven toward resolution, and cannot hang"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/stray
                    (parent-over [{:id :bad :machine-id :sa/strict :data {:n -1}}
                                  {:id :ok  :machine-id :sa/plain}]))
    (rf/dispatch-sync [:sup/stray [:start]])
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/stray))
        "(precondition) the childless reject sentinel is seeded")
    ;; Hand-drive the completion the (suppressed) valid sibling would have sent.
    (rf/dispatch-sync [:sup/stray [:sa/done :ok]])
    (is (= :forking (rf.machines.test-support/machine-state :sup/stray))
        "a completion against a childless sentinel resolves nothing and hangs nothing")))

;; ===========================================================================
;; (4) Parent exit clears the sentinel — no teardown debt, no orphan.
;; ===========================================================================

(deftest parent-exit-clears-the-schema-reject-sentinel
  (testing "parent exit clears the reject sentinel and no valid sibling remains
            live or orphaned — a rejected invoke owns no teardown debt"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/exit
                    (parent-over [{:id :bad :machine-id :sa/strict :data {:n -1}}
                                  {:id :ok  :machine-id :sa/plain}]))
    (rf/dispatch-sync [:sup/exit [:start]])
    (is (= {:rf/spawn-all-rejected? true} (join-slot :sup/exit))
        "(precondition) the childless reject sentinel is seeded")
    (rf/dispatch-sync [:sup/exit [:back]])
    (is (= :idle (rf.machines.test-support/machine-state :sup/exit))
        "(precondition) the parent left the :spawn-all state")
    (is (nil? (join-slot :sup/exit))
        "parent exit CLEARS the reject sentinel")
    (is (nil? (snapshot-of :sa/plain#1))
        "no valid sibling was left live or orphaned")))

;; ===========================================================================
;; (5) No false reject — the all-valid fast path is untouched.
;; ===========================================================================

(deftest all-valid-spawn-all-still-installs-a-live-join
  (testing "an all-valid :spawn-all keeps the existing fast path: a LIVE
            child-bearing join is seeded and every child installs. The
            preflight must not reject what the per-child install would accept
            — it validates the same stamped value the install builds"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sa/plain plain-child)
    (rf/reg-machine :sup/valid
                    (parent-over [{:id :good :machine-id :sa/strict :data {:n 7}}
                                  {:id :ok   :machine-id :sa/plain}]))
    (rf/dispatch-sync [:sup/valid [:start]])
    (let [slot (join-slot :sup/valid)]
      (is (contains? slot :children)
          "a LIVE child-bearing join is seeded — no false reject")
      (is (= {:good :sa/strict#1 :ok :sa/plain#1} (:children slot))
          "the join names both children at their allocated ids")
      (is (some? (:rf/attempt slot))
          "the live seed minted its per-attempt token"))
    (is (empty? (schema-failures))
        "a conforming child emits NO schema failure")
    (is (some? (snapshot-of :sa/strict#1))
        "the schema-valid child installed")
    (is (some? (snapshot-of :sa/plain#1))
        "the unconstrained sibling installed")
    (is (= {:n 7} (select-keys (:data (snapshot-of :sa/strict#1)) [:n]))
        "the child's materialised :data landed — validated, not mutated")))

(deftest single-spawn-schema-reject-is-unchanged
  (testing "a standalone single :spawn has no invoke to reject: its own
            per-child schema gate still fires exactly once and fails closed
            (the invoke sentinel must not swallow it)"
    (rf/reg-machine :sa/strict strict-child)
    (rf/reg-machine :sup/single
                    {:initial :idle
                     :states
                     {:idle    {:on {:start :working}}
                      :working {:spawn {:machine-id :sa/strict :data {:n -1}}}}})
    (rf/dispatch-sync [:sup/single [:start]])
    (is (= 1 (count (schema-failures)))
        "exactly ONE :phase :spawn failure for the rejected single spawn")
    (is (nil? (snapshot-of :sa/strict#1))
        "the rejected single spawn installs nothing")))
