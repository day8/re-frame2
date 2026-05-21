(ns re-frame.destroyed-exit-order-test
  "Per rf2-iilco — the `:rf.machine/destroyed` trace fires AFTER the
  child's active-configuration `:exit` cascade on EVERY destroy path.

  Before rf2-iilco the explicit / declarative-`:spawn` destroy
  (`destroy-single!`) and the `:spawn-all` per-child teardown
  (`destroy-invoke-all-children!`) emitted `:rf.machine/destroyed`
  BEFORE running `run-child-exit!`, while the final-state auto-destroy
  (`finalize-machine`) emitted it AFTER the cascade. A consumer keying
  on `:rf.machine/destroyed` therefore saw the destroy signal at a
  different point relative to the `:exit` side-effects depending on
  which entry-point fired — a latent inconsistency for tools (Causa,
  re-frame-10x, story-mcp) that key on the trace.

  Spec 005 §Declarative `:spawn` §Composition with explicit `:entry` /
  `:exit` (005:2138) pins the order: the `:exit` action reads the
  actor's final snapshot *before* the auto-destroy clears it, so a
  consumer observing the db between `:exit` and `:rf.machine/destroyed`
  must see the live snapshot. That makes exit-then-destroyed the
  spec-correct convention; this file pins it on all three paths.

  Mechanism: a shared ordered log captures both the `:exit` action's
  fire (the action conjes a marker) and the `:rf.machine/destroyed`
  trace (a listener conjes a marker). The marker order in the log is
  the observable ordering."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- record-order!
  "Register a `:rf.machine/destroyed` listener that conjes `:destroyed`
  onto `log`; returns an unregister thunk."
  [log]
  (let [id ::order-listener]
    (trace/register-listener!
      id
      (fn [ev]
        (when (= :rf.machine/destroyed (:operation ev))
          (swap! log conj :destroyed))))
    #(trace/unregister-listener! id)))

(defn- exit-then-destroyed?
  "True iff the first `:exit` marker precedes the first `:destroyed`
  marker in the ordered `log`."
  [log]
  (let [v (vec log)]
    (< (.indexOf v :exit) (.indexOf v :destroyed))))

;; ---- Path 1: explicit / declarative-:spawn destroy (destroy-single!) ------

(deftest destroyed-after-exit-on-explicit-destroy
  (testing "destroy-single! fires :exit BEFORE :rf.machine/destroyed"
    (let [log   (atom [])
          unreg (record-order! log)]
      (try
        (rf/reg-machine :eo/standalone
          {:initial :running
           :data    {}
           :states  {:running {:exit (fn [_] (swap! log conj :exit) {})}}})
        (rf/reg-machine :eo/destroyer
          {:initial :armed
           :data    {}
           :states  {:armed {:on {:fire {:action (fn [_]
                                                   {:fx [[:rf.machine/destroy :eo/standalone]]})}}}}})
        (rf/dispatch-sync [:eo/standalone [:rf.machine/noop]])
        (rf/dispatch-sync [:eo/destroyer [:fire]])
        (is (= [:exit :destroyed] @log)
            "explicit destroy emits :exit then :rf.machine/destroyed")
        (is (exit-then-destroyed? @log)
            ":exit precedes :rf.machine/destroyed")
        (finally (unreg))))))

(deftest destroyed-after-exit-on-invoke-exit-cascade
  (testing "declarative :spawn exit cascade (destroy-single!) fires :exit BEFORE :destroyed"
    (let [log   (atom [])
          unreg (record-order! log)]
      (try
        (rf/reg-machine :eo/child
          {:initial :working
           :data    {}
           :states  {:working {:exit (fn [_] (swap! log conj :exit) {})}}})
        (rf/reg-machine :eo/parent
          {:initial :idle
           :data    {}
           :states  {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :eo/child}
                               :on    {:stop :idle}}}})
        (rf/dispatch-sync [:eo/parent [:start]])            ;; spawn child
        (rf/dispatch-sync [:eo/parent [:stop]])             ;; exit :working → destroy child
        (is (= [:exit :destroyed] @log)
            "declarative :spawn exit cascade emits :exit then :destroyed")
        (finally (unreg))))))

;; ---- Path 2: :spawn-all per-child teardown (destroy-invoke-all-children!) --

(deftest destroyed-after-exit-on-invoke-all-teardown
  (testing ":spawn-all per-child teardown fires each :exit BEFORE its :destroyed"
    (let [log   (atom [])
          unreg (record-order! log)]
      (try
        (rf/reg-machine :eo/ia-child
          {:initial :working
           :data    {}
           :states  {:working {:exit (fn [_] (swap! log conj :exit) {})}}})
        (rf/reg-machine :eo/ia-parent
          {:initial :hydrating
           :data    {}
           :states  {:hydrating {:spawn-all
                                  {:children [{:id :a :machine-id :eo/ia-child}
                                              {:id :b :machine-id :eo/ia-child}]
                                   :join            :all
                                   :on-child-done   :ia/done
                                   :on-child-error  :ia/failed
                                   :on-all-complete [:go-done]
                                   :on-any-failed   [:ia/cancel]}
                                  :on {:go-done   :done
                                       :ia/cancel :idle}}
                     :done {}
                     :idle {}}})
        (rf/dispatch-sync [:eo/ia-parent [:rf.machine/spawned]])
        (rf/dispatch-sync [:eo/ia-parent [:ia/cancel]])     ;; tear children down
        ;; Two children: each fires :exit then :destroyed. The
        ;; per-child loop runs them sequentially, so the log is a
        ;; strict [:exit :destroyed :exit :destroyed].
        (is (= [:exit :destroyed :exit :destroyed] @log)
            "each child emits :exit then :destroyed, in per-child order")
        (finally (unreg))))))

;; ---- Path 3: final-state auto-destroy (finalize-machine) ------------------

(deftest destroyed-after-exit-on-final-state-auto-destroy
  (testing "finalize-machine fires :exit BEFORE :destroyed (the reference order)"
    (let [log   (atom [])
          unreg (record-order! log)]
      (try
        (rf/reg-machine :eo/final-child
          {:initial :running
           :data    {}
           :states  {:running {:on {:finish :done}}
                     :done    {:final? true
                               :exit   (fn [_] (swap! log conj :exit) {})}}})
        (rf/reg-machine :eo/final-parent
          {:initial :working
           :data    {}
           :states  {:working {:spawn {:machine-id :eo/final-child}}}})
        (rf/dispatch-sync [:eo/final-parent [:rf.machine/spawned]])
        (let [spawned-id (get-in (rf/get-frame-db :rf/default)
                                 [:rf/spawned :eo/final-parent [:working]])]
          (rf/dispatch-sync [spawned-id [:finish]]))
        (is (= [:exit :destroyed] @log)
            "final-state auto-destroy emits :exit then :destroyed (reference order)")
        (finally (unreg))))))
