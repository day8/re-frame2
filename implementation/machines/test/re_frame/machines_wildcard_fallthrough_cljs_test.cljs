(ns re-frame.machines-wildcard-fallthrough-cljs-test
  "Per Spec 005 §Transition resolution / §Wildcard transitions.
  CORRECTNESS coverage for the CROSS-KEY explicit→`:*` fallthrough rule.

  The invariant: `:*` is the LEAST-PRIORITY ENABLED transition at
  its level — NOT a 'no explicit KEY exists' fallback. A guard-blocked
  explicit `:on` entry must fall through to the same-level `:*`, and if no
  same-level wildcard is enabled, resolution walks to the parent (whose own
  explicit→`:*` resolution then repeats), down to a genuine no-op only when
  NO enabled transition exists anywhere. This matches XState v5's
  transition-selection order (descend priority within a state before
  walking to its ancestor).

  Exercised through `reg-machine` / `dispatch-sync` — the same runtime
  surface real apps use. The WITHIN-entry candidate-vector fallthrough
  (one explicit key whose guarded candidate-vector has a blocked first +
  enabled later entry) is already covered by other suites and asserted
  here only as a regression guard."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(defn- seed-snapshot!
  "Force the snapshot for `machine-id` to a known value via a `reg-event`
  seed handler (returning `{:rf.db/runtime …}`), so
  a test can reposition the machine to a non-initial leaf without rebuilding
  the whole machine."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (namespace machine-id) "-" (name machine-id)))]
    ;; Machine snapshots are durable runtime-db state.
    (rf/reg-event seed-id
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/machines :snapshots machine-id] snap)}))
    (rf/dispatch-sync [seed-id])))

;; ---------------------------------------------------------------------------
;; (a) guard-blocked explicit :on entry → same-level :* fires
;; ---------------------------------------------------------------------------

(deftest guard-blocked-explicit-falls-through-to-same-level-wildcard
  (testing "a guard-blocked explicit :foo falls through to the same-level :* (rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:explicit-action (tag :explicit)
                     :wildcard-action (tag :wildcard)}
           :states
           {:flat {:on {:foo {:guard :never :action :explicit-action}
                        :*   {:action :wildcard-action}}}}}]
      (rf/reg-machine :icj9t/same-level machine)
      (reset! log [])
      ;; :foo's explicit guard returns false ⇒ the explicit candidate is
      ;; NOT enabled. The blocked explicit falls through to the same-level
      ;; :* — :wildcard-action fires.
      (rf/dispatch-sync [:icj9t/same-level [:foo]])
      (is (= [:wildcard] @log)
          "guard-blocked explicit :foo fell through to same-level :*")
      (is (not (some #{:explicit} @log))
          "the guard-blocked explicit action must NOT fire"))))

;; ---------------------------------------------------------------------------
;; (b) guard-blocked explicit AND no enabled same-level :* → parent :* fires
;; ---------------------------------------------------------------------------

(deftest guard-blocked-explicit-falls-through-to-parent-wildcard
  (testing "blocked explicit + no enabled leaf :* → parent :* catches it (rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-explicit  (tag :leaf-explicit)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:* {:action :parent-wildcard}}        ;; :* at parent
             :states
             ;; leaf declares ONLY a guard-blocked explicit :foo — no leaf :*
             {:dashboard {:on {:foo {:guard :never :action :leaf-explicit}}}}}}}]
      (rf/reg-machine :icj9t/parent-wild machine)
      (reset! log [])
      ;; Leaf [:authenticated :dashboard]: :foo explicit is guard-blocked
      ;; and there is no leaf :* ⇒ leaf yields nothing ⇒ walk to parent
      ;; :authenticated, whose :* fires.
      (rf/dispatch-sync [:icj9t/parent-wild [:foo]])
      (is (= [:parent-wildcard] @log)
          "blocked leaf explicit fell through past the (absent) leaf :* to the parent :*")
      (is (not (some #{:leaf-explicit} @log))
          "the guard-blocked leaf explicit action must NOT fire")))

  (testing "blocked leaf explicit + guard-blocked leaf :* → still walks to parent :* (rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-explicit   (tag :leaf-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:* {:action :parent-wildcard}}
             :states
             ;; leaf's explicit AND its :* are both guard-blocked.
             {:dashboard {:on {:foo {:guard :never :action :leaf-explicit}
                               :*   {:guard :never :action :leaf-wildcard}}}}}}}]
      (rf/reg-machine :icj9t/parent-wild-blocked-leaf-star machine)
      (reset! log [])
      (rf/dispatch-sync [:icj9t/parent-wild-blocked-leaf-star [:foo]])
      (is (= [:parent-wildcard] @log)
          "both leaf candidates blocked ⇒ leaf yields nothing ⇒ parent :* fires")
      (is (not (some #{:leaf-explicit :leaf-wildcard} @log))
          "neither guard-blocked leaf candidate fires"))))

;; ---------------------------------------------------------------------------
;; (c) nothing enabled anywhere → genuine no-op (state + log unchanged)
;; ---------------------------------------------------------------------------

(deftest nothing-enabled-anywhere-is-a-genuine-noop
  (testing "guard-blocked everywhere ⇒ no transition fires, snapshot unchanged (rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-explicit   (tag :leaf-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             ;; parent :* is ALSO guard-blocked ⇒ nothing enabled anywhere.
             :on      {:* {:guard :never :action :parent-wildcard}}
             :states
             {:dashboard {:on {:foo {:guard :never :action :leaf-explicit}
                               :*   {:guard :never :action :leaf-wildcard}}}}}}}]
      (rf/reg-machine :icj9t/noop machine)
      ;; Install the initial leaf BEFORE measuring — the snapshot is
      ;; synthesised lazily on first dispatch, so capturing `before` ahead
      ;; of bootstrap would read nil and make even a real no-op look like a
      ;; change. Seed the cascaded initial leaf so the no-op is measured
      ;; against an installed state.
      (seed-snapshot! :icj9t/noop {:state [:authenticated :dashboard] :data {}})
      (let [before (snapshot :icj9t/noop)]
        (reset! log [])
        (rf/dispatch-sync [:icj9t/noop [:foo]])
        (is (empty? @log)
            "no action fired — every explicit and wildcard candidate is guard-blocked")
        (is (= [:authenticated :dashboard] (:state before))
            "precondition: machine seeded at the initial leaf")
        (is (= (:state before) (:state (snapshot :icj9t/noop)))
            "snapshot :state unchanged — genuine no-op")))))

;; ---------------------------------------------------------------------------
;; (d) enabled explicit still wins over same-level :* (priority preserved)
;; ---------------------------------------------------------------------------

(deftest enabled-explicit-beats-same-level-wildcard
  (testing "an ENABLED explicit :foo wins over the same-level :* (priority preserved, rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           ;; explicit guard PASSES this time.
           :guards  {:always (fn [_] true)}
           :actions {:explicit-action (tag :explicit)
                     :wildcard-action (tag :wildcard)}
           :states
           {:flat {:on {:foo {:guard :always :action :explicit-action}
                        :*   {:action :wildcard-action}}}}}]
      (rf/reg-machine :icj9t/priority machine)
      (reset! log [])
      (rf/dispatch-sync [:icj9t/priority [:foo]])
      (is (= [:explicit] @log)
          "enabled explicit fires; same-level :* is NOT consulted")
      (is (not (some #{:wildcard} @log))
          "the same-level :* must NOT fire when an explicit candidate is enabled"))))

;; ---------------------------------------------------------------------------
;; within-entry candidate-vector fallthrough — regression guard (already
;; worked pre-icj9t; pinned here so the engine change doesn't regress it)
;; ---------------------------------------------------------------------------

(deftest within-entry-candidate-vector-fallthrough-still-works
  (testing "one explicit key, guarded candidate-vector: blocked first + enabled later still fires (regression)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:first-action  (tag :first)
                     :second-action (tag :second)
                     :wildcard-action (tag :wildcard)}
           :states
           {:flat {:on {:foo [{:guard :never :action :first-action}
                              {:action :second-action}]      ;; unguarded fallback
                        :*   {:action :wildcard-action}}}}}]
      (rf/reg-machine :icj9t/within-entry machine)
      (reset! log [])
      ;; The first candidate's guard fails; the second (unguarded) candidate
      ;; in the SAME explicit entry is enabled — it fires. The :* is NOT
      ;; reached because the explicit key DID yield an enabled candidate.
      (rf/dispatch-sync [:icj9t/within-entry [:foo]])
      (is (= [:second] @log)
          "within-entry fallthrough fired the second candidate; :* not consulted")
      (is (not (some #{:first :wildcard} @log))
          "neither the blocked first candidate nor the wildcard fired"))))

;; ---------------------------------------------------------------------------
;; (e) compound coverage — leaf blocked explicit, leaf :*, parent :*
;; ---------------------------------------------------------------------------

(deftest compound-explicit-blocked-prefers-leaf-wildcard-over-parent
  (testing "compound: blocked leaf explicit falls through to LEAF :* before the parent (rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-explicit   (tag :leaf-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:* {:action :parent-wildcard}}        ;; parent :* exists
             :states
             ;; leaf has a guard-blocked explicit AND an ENABLED :*.
             {:dashboard {:on {:foo {:guard :never :action :leaf-explicit}
                               :*   {:action :leaf-wildcard}}}}}}}]
      (rf/reg-machine :icj9t/compound machine)
      (reset! log [])
      ;; Within-level priority: leaf explicit blocked → leaf :* (enabled)
      ;; fires; the leaf level is satisfied so the walk stops — parent :*
      ;; is shadowed.
      (rf/dispatch-sync [:icj9t/compound [:foo]])
      (is (= [:leaf-wildcard] @log)
          "leaf :* fired before any parent walk — same-level priority preserved")
      (is (not (some #{:leaf-explicit :parent-wildcard} @log))
          "neither the blocked leaf explicit nor the parent :* fired"))))

;; ---------------------------------------------------------------------------
;; (f) parallel-region coverage — each region resolves fallthrough
;; independently (broadcast routes through the same per-region pick path)
;; ---------------------------------------------------------------------------

(deftest parallel-region-explicit-blocked-falls-through-to-region-wildcard
  (testing "parallel: each region's blocked explicit falls through to that region's :* (rf2-icj9t)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:type    :parallel
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:left-explicit  (tag :left-explicit)
                     :left-wildcard  (tag :left-wildcard)
                     :right-explicit (tag :right-explicit)
                     :right-wildcard (tag :right-wildcard)}
           :regions
           {:left  {:initial :a
                    :states  {:a {:on {:foo {:guard :never :action :left-explicit}
                                       :*   {:action :left-wildcard}}}}}
            :right {:initial :x
                    :states  {:x {:on {:foo {:guard :never :action :right-explicit}
                                       :*   {:action :right-wildcard}}}}}}}]
      (rf/reg-machine :icj9t/parallel machine)
      (reset! log [])
      ;; The event broadcasts to both regions; in EACH the explicit :foo is
      ;; guard-blocked and falls through to that region's same-level :*.
      (rf/dispatch-sync [:icj9t/parallel [:foo]])
      (is (= #{:left-wildcard :right-wildcard} (set @log))
          "both regions fell through their blocked explicit to their own :*")
      (is (not (some #{:left-explicit :right-explicit} @log))
          "neither region's guard-blocked explicit action fired"))))
