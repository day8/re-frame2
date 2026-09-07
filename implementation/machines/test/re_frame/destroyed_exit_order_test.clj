(ns re-frame.destroyed-exit-order-test
  "The `:rf.machine/destroyed` trace fires AFTER the child's
  active-configuration `:exit` cascade on EVERY destroy path.

  All three destroy entry-points emit `:rf.machine/destroyed` AFTER the
  `:exit` cascade: the explicit / declarative-`:spawn` destroy
  (`destroy-single!`), the `:spawn-all` per-child teardown
  (`destroy-spawn-all-children!`), and the final-state auto-destroy
  (`finalize-machine`). A consumer keying on `:rf.machine/destroyed`
  therefore sees the destroy signal at a consistent point relative to the
  `:exit` side-effects regardless of which entry-point fired — important
  for tools (Xray, re-frame-10x, story-mcp) that key on the trace.

  Spec 005 §Declarative `:spawn` §Composition with explicit `:entry` /
  `:exit` (005:3026) pins what is guaranteed: the user's `:exit` ACTION
  reads the actor's final snapshot *before* the auto-destroy clears it.
  That is an ACTION-ordering guarantee, and both sides of it run in-drain,
  so it is what makes exit-then-destroyed the spec-correct convention.
  This file pins that convention on all three paths.

  SCOPE CORRECTION (rf2-wxy1c). This docstring used to cite 005:2138 —
  which is the state-tags worked example, not a destroy-ordering rule —
  and to extend the guarantee into a claim that \"a consumer observing the
  db between `:exit` and `:rf.machine/destroyed` sees the live snapshot\".
  That sentence existed only here; the spec never wrote it. It read an
  action-vs-fx ordering guarantee as a TRACE-INTERLEAVING one, which the
  governing section does not give. Under rf2-wxy1c internal drain-owned
  traces deliver at the post-drain boundary, so a multi-child teardown
  batches its `:destroyed` traces after the whole `:exit` cascade — and
  that is the order rf2-wxy1c's own \"no partially settled state\"
  criterion prefers, since the old interleaving let a consumer keyed on
  child A's `:destroyed` read a db with child B still half-alive.

  Mechanism: a shared ordered log captures both the `:exit` action's
  fire (the action conjes a marker) and the `:rf.machine/destroyed`
  trace (a listener conjes a marker). The marker order in the log is
  the observable ordering."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; `record-order!` intentionally uses a RAW listener (not
;; `rf.machines.test-support/with-trace-capture`): it conjes its `:destroyed` sentinel onto the
;; SAME caller-supplied ordering log the machine `:exit` action writes to, so
;; the interleaved marker order is the observable — a fresh capture atom
;; could not share that log.
(defn- record-order!
  "Register a `:rf.machine/destroyed` listener that conjes `:destroyed`
  onto `log`; returns an unregister thunk."
  [log]
  (let [id ::order-listener]
    (rf.trace/register-listener!
      id
      (fn [ev]
        (when (= :rf.machine/destroyed (:operation ev))
          (swap! log conj :destroyed))))
    #(rf.trace/unregister-listener! id)))

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

;; ---- Path 2: :spawn-all per-child teardown (destroy-spawn-all-children!) --

(deftest destroyed-after-exit-on-invoke-all-teardown
  (testing ":spawn-all per-child teardown fires every :exit BEFORE any :destroyed"
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
                                   :on-all-complete [:go-done]
                                   :on-any-failed   [:ia/cancel]}
                                  :on {:go-done   :done
                                       :ia/cancel :idle}}
                     :done {}
                     :idle {}}})
        (rf/dispatch-sync [:eo/ia-parent [:rf.machine.spawn/spawned]])
        (rf/dispatch-sync [:eo/ia-parent [:ia/cancel]])     ;; tear children down
        ;; Two children torn down inside ONE events-fx walk. The `:exit`
        ;; actions are in-drain and run per-child, sequentially; the
        ;; `:rf.machine/destroyed` traces are internal drain-owned emits,
        ;; so under rf2-wxy1c they deliver together at the post-drain
        ;; boundary — in EMISSION ORDER, after the whole cascade. Hence
        ;; the BATCHED [:exit :exit :destroyed :destroyed], not the old
        ;; interleaved [:exit :destroyed :exit :destroyed].
        ;;
        ;; The convention this file exists to pin is intact: every
        ;; `:exit` still precedes every `:destroyed`. What changed is the
        ;; INTERLEAVING between two children, which the spec never
        ;; guaranteed (see the ns docstring's scope correction).
        (is (= [:exit :exit :destroyed :destroyed] @log)
            "every :exit precedes every :destroyed — the destroyed traces batch at the post-drain boundary")
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
        (rf/dispatch-sync [:eo/final-parent [:rf.machine.spawn/spawned]])
        (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/machines :spawned :eo/final-parent [:working]])]
          (rf/dispatch-sync [spawned-id [:finish]]))
        (is (= [:exit :destroyed] @log)
            "final-state auto-destroy emits :exit then :destroyed (reference order)")
        (finally (unreg))))))
