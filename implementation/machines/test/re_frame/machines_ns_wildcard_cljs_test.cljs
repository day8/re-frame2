(ns re-frame.machines-ns-wildcard-cljs-test
  "Per Spec 005 §Wildcard transitions §Namespaced (partial) event
  descriptors. CORRECTNESS coverage for the NAMESPACE-WILDCARD
  event-descriptor tier `:ns/*`.

  The invariant: `:on` resolves THREE descriptor tiers most-specific first —
  EXACT `:foo/bar` > NAMESPACE-WILDCARD `:foo/*` > TOTAL `:*` — at each
  level, before walking to the parent. `:foo/*` is re-frame2's spelling of
  XState v5's partial (prefix) event descriptor `mouse.*` (SCXML §3.12.1
  dot-prefix tokens): re-frame2 events are namespaced keywords, so the
  keyword namespace is the natural prefix tier. The `:ns/*` tier integrates
  with the wildcard-fallthrough: a guard-blocked exact key falls through to
  `:ns/*`, a guard-blocked `:ns/*` falls through to `:*`, then to the parent.

  Exercised through `reg-machine` / `dispatch-sync` — the same runtime
  surface real apps use."
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
;; (a) :mouse/* matches different events in the SAME namespace
;; ---------------------------------------------------------------------------

(deftest ns-wildcard-matches-any-event-in-namespace
  (testing ":mouse/* catches :mouse/down AND :mouse/up — any event in the ns (rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [{ev :event}] (swap! log conj [k (first ev)]) {}))
          machine
          {:initial :flat
           :data    {}
           :actions {:mouse-any (tag :mouse-any)}
           :states  {:flat {:on {:mouse/* {:action :mouse-any}}}}}]
      (rf/reg-machine :z4t2v/ns-any machine)
      (reset! log [])
      (rf/dispatch-sync [:z4t2v/ns-any [:mouse/down]])
      (rf/dispatch-sync [:z4t2v/ns-any [:mouse/up]])
      (rf/dispatch-sync [:z4t2v/ns-any [:mouse/move]])
      (is (= [[:mouse-any :mouse/down]
              [:mouse-any :mouse/up]
              [:mouse-any :mouse/move]]
             @log)
          ":mouse/* fired for every :mouse/... event regardless of the name"))))

;; ---------------------------------------------------------------------------
;; (b) exact :mouse/down BEATS :mouse/* (priority — most-specific wins)
;; ---------------------------------------------------------------------------

(deftest exact-beats-ns-wildcard
  (testing "an enabled exact :mouse/down wins over :mouse/* (priority preserved, rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :actions {:exact-down (tag :exact-down)
                     :ns-any     (tag :ns-any)}
           :states  {:flat {:on {:mouse/down {:action :exact-down}
                                  :mouse/*    {:action :ns-any}}}}}]
      (rf/reg-machine :z4t2v/exact-beats-ns machine)
      (reset! log [])
      ;; :mouse/down has an exact entry → it wins; :mouse/* not consulted.
      (rf/dispatch-sync [:z4t2v/exact-beats-ns [:mouse/down]])
      ;; :mouse/up has NO exact entry → falls to :mouse/*.
      (rf/dispatch-sync [:z4t2v/exact-beats-ns [:mouse/up]])
      (is (= [:exact-down :ns-any] @log)
          "exact :mouse/down fired for :mouse/down; :mouse/* caught :mouse/up")
      (is (not (some #{:ns-any} (take 1 @log)))
          "the namespace-wildcard must NOT fire when an exact candidate is enabled"))))

;; ---------------------------------------------------------------------------
;; (c) :mouse/* BEATS total :* (priority — most-specific wins)
;; ---------------------------------------------------------------------------

(deftest ns-wildcard-beats-total-wildcard
  (testing ":mouse/* wins over the total :* for a :mouse/... event (priority, rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :actions {:ns-any   (tag :ns-any)
                     :total-any (tag :total-any)}
           :states  {:flat {:on {:mouse/* {:action :ns-any}
                                  :*       {:action :total-any}}}}}]
      (rf/reg-machine :z4t2v/ns-beats-total machine)
      (reset! log [])
      ;; :mouse/down → :mouse/* (more specific) wins over :*.
      (rf/dispatch-sync [:z4t2v/ns-beats-total [:mouse/down]])
      ;; :keyboard/down → no :keyboard/* declared → total :* catches it.
      (rf/dispatch-sync [:z4t2v/ns-beats-total [:keyboard/down]])
      (is (= [:ns-any :total-any] @log)
          ":mouse/* caught :mouse/down (beating :*); :* caught the un-namespaced-tier :keyboard/down")
      (is (not (some #{:total-any} (take 1 @log)))
          "the total :* must NOT fire when the namespace-wildcard is enabled"))))

;; ---------------------------------------------------------------------------
;; (d) guard-blocked exact :mouse/down falls through to :mouse/* (icj9t)
;; ---------------------------------------------------------------------------

(deftest guard-blocked-exact-falls-through-to-ns-wildcard
  (testing "a guard-blocked exact :mouse/down falls through to :mouse/* (icj9t × z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:exact-down (tag :exact-down)
                     :ns-any     (tag :ns-any)}
           :states  {:flat {:on {:mouse/down {:guard :never :action :exact-down}
                                  :mouse/*    {:action :ns-any}}}}}]
      (rf/reg-machine :z4t2v/blocked-exact->ns machine)
      (reset! log [])
      ;; Exact :mouse/down's guard returns false ⇒ not enabled ⇒ fall through
      ;; to the same-level :mouse/* (fallthrough across tiers).
      (rf/dispatch-sync [:z4t2v/blocked-exact->ns [:mouse/down]])
      (is (= [:ns-any] @log)
          "guard-blocked exact :mouse/down fell through to :mouse/*")
      (is (not (some #{:exact-down} @log))
          "the guard-blocked exact action must NOT fire"))))

;; ---------------------------------------------------------------------------
;; (e) guard-blocked :mouse/* falls through to total :*, then to parent
;; ---------------------------------------------------------------------------

(deftest guard-blocked-ns-wildcard-falls-through-to-total-then-parent
  (testing "guard-blocked :mouse/* falls through to the same-level :* (icj9t × z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:ns-any    (tag :ns-any)
                     :total-any (tag :total-any)}
           :states  {:flat {:on {:mouse/* {:guard :never :action :ns-any}
                                  :*       {:action :total-any}}}}}]
      (rf/reg-machine :z4t2v/blocked-ns->total machine)
      (reset! log [])
      ;; :mouse/* matches :mouse/down but its guard fails ⇒ fall through to :*.
      (rf/dispatch-sync [:z4t2v/blocked-ns->total [:mouse/down]])
      (is (= [:total-any] @log)
          "guard-blocked :mouse/* fell through to the total :*")
      (is (not (some #{:ns-any} @log))
          "the guard-blocked namespace-wildcard action must NOT fire")))

  (testing "guard-blocked leaf :mouse/* + no enabled leaf :* → walks to parent :* (icj9t × z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-ns         (tag :leaf-ns)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:* {:action :parent-wildcard}}        ;; parent total :*
             :states
             ;; leaf declares ONLY a guard-blocked :mouse/* — no leaf :*.
             {:dashboard {:on {:mouse/* {:guard :never :action :leaf-ns}}}}}}}]
      (rf/reg-machine :z4t2v/blocked-ns->parent machine)
      (reset! log [])
      ;; leaf :mouse/* blocked, no leaf :* ⇒ leaf yields nothing ⇒ walk to
      ;; parent :authenticated, whose :* fires.
      (rf/dispatch-sync [:z4t2v/blocked-ns->parent [:mouse/down]])
      (is (= [:parent-wildcard] @log)
          "blocked leaf :mouse/* fell through past the (absent) leaf :* to the parent :*")
      (is (not (some #{:leaf-ns} @log))
          "the guard-blocked leaf :mouse/* action must NOT fire"))))

;; ---------------------------------------------------------------------------
;; (f) :mouse/* does NOT match :keyboard/down (namespace isolation)
;; ---------------------------------------------------------------------------

(deftest ns-wildcard-isolates-by-namespace
  (testing ":mouse/* does NOT catch :keyboard/down — namespace isolation (rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :actions {:mouse-any (tag :mouse-any)}
           ;; ONLY a :mouse/* — no :keyboard/*, no total :*.
           :states  {:flat {:on {:mouse/* {:action :mouse-any}}}}}]
      (rf/reg-machine :z4t2v/ns-isolation machine)
      ;; Seed an installed leaf so the no-op for :keyboard/down is measured
      ;; against an installed state (the snapshot is synthesised lazily).
      (seed-snapshot! :z4t2v/ns-isolation {:state [:flat] :data {}})
      (reset! log [])
      ;; :mouse/down matches :mouse/*; :keyboard/down does NOT (different ns)
      ;; and there is no total :* → benign no-op.
      (rf/dispatch-sync [:z4t2v/ns-isolation [:mouse/down]])
      (rf/dispatch-sync [:z4t2v/ns-isolation [:keyboard/down]])
      (is (= [:mouse-any] @log)
          ":mouse/* fired only for :mouse/down; :keyboard/down was NOT caught by :mouse/*")
      (is (= [:flat] (:state (snapshot :z4t2v/ns-isolation)))
          ":keyboard/down was a genuine no-op — snapshot unchanged"))))

(deftest bare-event-id-has-no-namespace-tier
  (testing "a non-namespaced event :go has no :ns/* tier — only exact + total :* (rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :flat
           :data    {}
           :actions {:total-any (tag :total-any)}
           ;; A :mouse/* must NOT swallow a bare :go (no namespace tier);
           ;; only the total :* can catch it.
           :states  {:flat {:on {:mouse/* {:action (tag :mouse-any)}
                                  :*       {:action :total-any}}}}}]
      (rf/reg-machine :z4t2v/bare-event machine)
      (reset! log [])
      (rf/dispatch-sync [:z4t2v/bare-event [:go]])
      (is (= [:total-any] @log)
          "bare :go has no namespace ⇒ skips :mouse/* and lands on the total :*"))))

;; ---------------------------------------------------------------------------
;; (g) compound + parallel coverage
;; ---------------------------------------------------------------------------

(deftest compound-ns-wildcard-prefers-leaf-over-parent
  (testing "compound: leaf :mouse/* fires before a parent :mouse/* (same-level priority, rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:leaf-ns   (tag :leaf-ns)
                     :parent-ns (tag :parent-ns)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:mouse/* {:action :parent-ns}}        ;; parent :mouse/*
             :states
             ;; leaf has its OWN :mouse/* — should shadow the parent's.
             {:dashboard {:on {:mouse/* {:action :leaf-ns}}}}}}}]
      (rf/reg-machine :z4t2v/compound machine)
      (reset! log [])
      (rf/dispatch-sync [:z4t2v/compound [:mouse/down]])
      (is (= [:leaf-ns] @log)
          "leaf :mouse/* fired before any parent walk — same-level priority preserved")
      (is (not (some #{:parent-ns} @log))
          "the parent :mouse/* must NOT fire when the leaf handled the event")))

  (testing "compound: blocked leaf exact → leaf :ns/* → walks to parent exact (icj9t × z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-exact  (tag :leaf-exact)
                     :leaf-ns     (tag :leaf-ns)
                     :parent-exact (tag :parent-exact)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:mouse/down {:action :parent-exact}}  ;; parent exact
             :states
             ;; leaf exact :mouse/down is guard-blocked; leaf :mouse/* enabled.
             {:dashboard {:on {:mouse/down {:guard :never :action :leaf-exact}
                               :mouse/*    {:action :leaf-ns}}}}}}}]
      (rf/reg-machine :z4t2v/compound-fallthrough machine)
      (reset! log [])
      ;; leaf exact blocked → leaf :mouse/* enabled fires; the leaf level is
      ;; satisfied so the walk stops — the parent's exact :mouse/down is shadowed.
      (rf/dispatch-sync [:z4t2v/compound-fallthrough [:mouse/down]])
      (is (= [:leaf-ns] @log)
          "leaf :mouse/* fired before any parent walk — within-level tier priority")
      (is (not (some #{:leaf-exact :parent-exact} @log))
          "neither the blocked leaf exact nor the parent exact fired"))))

(deftest parallel-region-ns-wildcard-resolves-per-region
  (testing "parallel: each region's :ns/* resolves independently under broadcast (rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:type    :parallel
           :data    {}
           :actions {:left-ns  (tag :left-ns)
                     :right-ns (tag :right-ns)}
           :regions
           {:left  {:initial :a
                    :states  {:a {:on {:mouse/* {:action :left-ns}}}}}
            :right {:initial :x
                    :states  {:x {:on {:mouse/* {:action :right-ns}}}}}}}]
      (rf/reg-machine :z4t2v/parallel machine)
      (reset! log [])
      ;; :mouse/move broadcasts to both regions; each region's :mouse/* fires.
      (rf/dispatch-sync [:z4t2v/parallel [:mouse/move]])
      (is (= #{:left-ns :right-ns} (set @log))
          "both regions caught :mouse/move via their own :mouse/*")))

  (testing "parallel: exact still beats :ns/* per region; non-matching ns is a region no-op (rf2-z4t2v)"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:type    :parallel
           :data    {}
           :actions {:left-exact (tag :left-exact)
                     :left-ns    (tag :left-ns)
                     :right-ns   (tag :right-ns)}
           :regions
           {:left  {:initial :a
                    :states  {:a {:on {:mouse/down {:action :left-exact}
                                       :mouse/*    {:action :left-ns}}}}}
            :right {:initial :x
                    :states  {:x {:on {:mouse/* {:action :right-ns}}}}}}}]
      (rf/reg-machine :z4t2v/parallel-exact machine)
      (reset! log [])
      ;; :mouse/down: left region's EXACT wins; right region's :mouse/* fires.
      (rf/dispatch-sync [:z4t2v/parallel-exact [:mouse/down]])
      (is (= #{:left-exact :right-ns} (set @log))
          "left region's exact beat its :mouse/*; right region caught it via :mouse/*")
      (is (not (some #{:left-ns} @log))
          "left region's :mouse/* must NOT fire when its exact :mouse/down is enabled"))))
