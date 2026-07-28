(ns re-frame.substrate-source-test
  "Per rf2-ejtpd — substrate-internal `:source` values stamped at each
  dispatch site. Closed-set extension over rf2-hxj0d's
  `:ui / :frame-init / :unknown` baseline:

  | new `:source` value | stamped by                | when                                                 |
  |---------------------|---------------------------|------------------------------------------------------|
  | `:fx-dispatch`      | `:dispatch` fx handler     | the `:dispatch` reserved fx executes                 |
  | `:fx-dispatch-later`| `:dispatch-later` fx handler| the `:dispatch-later` reserved fx fires after delay|

  Per Spec 002 §`:source` / Spec-Schemas §`:rf/dispatch-envelope`, each
  substrate dispatch site stamps the matching specific value so the
  Epoch panel's DISPATCH step labels the precise trigger rather than
  the prior aggregate (`:fx` / `:unknown`).

  The `:after-timer` and `:machine-spawn` paths live in the machines
  artefact's own test files (see `machines_after_cljs_test.cljs` and
  `machines_spawn_cljs_test.cljs`); the `:always` microstep trace
  carries `:source :always` and is verified in
  `machines_always_cljs_test.cljs`.

  JVM-only — substrate fx-handler behaviour is platform-agnostic.

  ## Posture split (rf2-d2841)

  The stamp this file is about is a PROPERTY OF THE DISPATCH ENVELOPE. It was
  only ever READ off the `:rf.event/dispatched` trace, which emits nothing
  under `-Dre-frame.debug=false` — so every deftest here failed under
  `scripts/test-core-prod-gate.sh` while the thing being asserted was
  production behaviour all along.

  Each case therefore grew an ALWAYS-ON probe rather than a guard: a
  `:test/probe` fx running inside every level of the cascade captures
  `(:envelope m)`, the production surface
  `cascade-envelope-propagation-test/fx-handler-ctx-carries-envelope-slot`
  establishes. The `:source` / `:origin` claims are read off those envelopes and
  now hold in BOTH postures — including the three-deep override and the
  `:dispatch-later` deferral, which had no production-posture counterpart
  anywhere.

  What is left inside the `(when interop/debug-enabled? …)` arms is the
  narrower claim the trace still owns: that `:source` is HOISTED to the trace
  event's top level (Spec 009 §Core fields) while `:rf.event/origin` rides under
  `:tags`. That is a trace-shape claim, not a propagation claim.

  The `:dispatch-later` case additionally moved its completion signal onto the
  probe. It used to wait on a promise delivered by the TRACE listener — which
  under the gate simply never arrives, so the case burned its full 2s timeout
  before failing. The probe delivers it in both postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- always-on envelope probe (rf2-d2841) --------------------------------
;;
;; The `:source` stamp lives on the DISPATCH ENVELOPE; reading it off the
;; `:rf.event/dispatched` trace was only ever one way to observe it, and the one
;; that disappears under -Dre-frame.debug=false. A user fx-handler receives
;; `(:envelope m)` — the production surface pinned by
;; `cascade-envelope-propagation-test/fx-handler-ctx-carries-envelope-slot` —
;; so running one inside each level of a cascade reads every envelope directly.

(defn- register-probe-fx!
  "Register `:test/probe`, an fx that records the dispatch envelope it runs
  under into `envelopes` keyed by the level keyword it is called with."
  [envelopes]
  (rf/reg-fx :test/probe
    (fn [m [level]] (swap! envelopes assoc level (:envelope m)))))

;; ---- :fx-dispatch stamp by the :dispatch fx handler ----------------------

(deftest dispatch-fx-stamps-source-fx-dispatch
  (testing ":dispatch fx stamps `:source :fx-dispatch` on the child envelope"
    (let [seen      (atom [])
          envelopes (atom {})]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (register-probe-fx! envelopes)
        (rf/reg-event :test/parent
          (fn [_ _]
            {:fx [[:test/probe [:parent]]
                  [:dispatch [:test/child]]]}))
        (rf/reg-event :test/child
          (fn [{:keys [db]} _] {:db db :fx [[:test/probe [:child]]]}))

        ;; Parent stamps `:source :ui` (mimicking a UI handler call-site).
        (rf/dispatch-sync [:test/parent] {:source :ui})

        ;; ---- ALWAYS-ON (rf2-d2841): read the stamp off the ENVELOPES ------
        (let [parent-env (:parent @envelopes)
              child-env  (:child  @envelopes)]
          (is (some? parent-env) "the parent's dispatch envelope was captured")
          (is (some? child-env)  "the child's dispatch envelope was captured")
          (is (= :ui (:source parent-env))
              "parent carries the caller-supplied :source :ui")
          (is (= :fx-dispatch (:source child-env))
              ":dispatch fx stamped :source :fx-dispatch on the child envelope (rf2-ejtpd)"))

        ;; ---- rf2-d2841 dev arm: the same values, HOISTED onto the trace ---
        (when interop/debug-enabled?
          (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
                parent-ev  (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
                child-ev   (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
            (is (some? parent-ev) "parent's :rf.event/dispatched is captured")
            (is (some? child-ev)  "child's :rf.event/dispatched is captured")
            (is (= :ui (:source parent-ev))
                "parent carries the caller-supplied :source :ui")
            (is (= :fx-dispatch (:source child-ev))
                ":dispatch fx stamped :source :fx-dispatch on the child envelope (rf2-ejtpd)")))
        (finally (rf/unregister-listener! :trace ::rec))))))

(deftest dispatch-fx-overrides-parent-source-three-deep
  (testing ":fx-dispatch is the *immediate* trigger — overrides at every cascade depth"
    (let [seen      (atom [])
          envelopes (atom {})]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (register-probe-fx! envelopes)
        (rf/reg-event :test/lvl-0
          (fn [_ _] {:fx [[:test/probe [:lvl-0]] [:dispatch [:test/lvl-1]]]}))
        (rf/reg-event :test/lvl-1
          (fn [_ _] {:fx [[:test/probe [:lvl-1]] [:dispatch [:test/lvl-2]]]}))
        (rf/reg-event :test/lvl-2
          (fn [{:keys [db]} _] {:db db :fx [[:test/probe [:lvl-2]]]}))

        (rf/dispatch-sync [:test/lvl-0] {:source :ui})

        ;; ---- ALWAYS-ON (rf2-d2841): the override at EVERY depth -----------
        (is (= :ui          (:source (:lvl-0 @envelopes))) "root keeps :ui")
        (is (= :fx-dispatch (:source (:lvl-1 @envelopes))) "lvl-1 stamped :fx-dispatch")
        (is (= :fx-dispatch (:source (:lvl-2 @envelopes)))
            "lvl-2 ALSO stamped :fx-dispatch (immediate trigger, not :ui from the root)")

        ;; ---- rf2-d2841 dev arm --------------------------------------------
        (when interop/debug-enabled?
          (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
                ev-for     (fn [id]
                             (first (filter #(= [id] (get-in % [:tags :rf.event/v])) dispatched)))]
            (is (= :ui          (:source (ev-for :test/lvl-0))) "root keeps :ui")
            (is (= :fx-dispatch (:source (ev-for :test/lvl-1))) "lvl-1 stamped :fx-dispatch")
            (is (= :fx-dispatch (:source (ev-for :test/lvl-2)))
                "lvl-2 ALSO stamped :fx-dispatch (immediate trigger, not :ui from the root)")))
        (finally (rf/unregister-listener! :trace ::rec))))))

(deftest dispatch-fx-preserves-origin-while-overriding-source
  (testing ":origin propagates through the cascade; :source is OVERRIDDEN per-step"
    (let [seen      (atom [])
          envelopes (atom {})]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (register-probe-fx! envelopes)
        (rf/reg-event :test/parent
          (fn [_ _] {:fx [[:test/probe [:parent]] [:dispatch [:test/child]]]}))
        (rf/reg-event :test/child
          (fn [{:keys [db]} _] {:db db :fx [[:test/probe [:child]]]}))

        (rf/dispatch-sync [:test/parent] {:source :ui :origin :pair})

        ;; ---- ALWAYS-ON (rf2-d2841): the two axes read off the envelopes ---
        (let [parent-env (:parent @envelopes)
              child-env  (:child  @envelopes)]
          (is (= :pair (:origin parent-env)))
          (is (= :pair (:origin child-env))
              ":origin propagates through the cascade")
          (is (= :ui          (:source parent-env)))
          (is (= :fx-dispatch (:source child-env))
              ":source is overridden by the substrate's :dispatch fx (rf2-ejtpd)"))

        ;; ---- rf2-d2841 dev arm: the trace SHAPE — `:origin` under `:tags`,
        ;;      `:source` hoisted to the top level (Spec 009 §Core fields).
        (when interop/debug-enabled?
          (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
                parent-ev  (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
                child-ev   (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
            (is (= :pair (get-in parent-ev [:tags :rf.event/origin])))
            (is (= :pair (get-in child-ev  [:tags :rf.event/origin]))
                ":origin propagates through the cascade")
            (is (= :ui          (:source parent-ev)))
            (is (= :fx-dispatch (:source child-ev))
                ":source is overridden by the substrate's :dispatch fx (rf2-ejtpd)")))
        (finally (rf/unregister-listener! :trace ::rec))))))

;; ---- :fx-dispatch-later stamp by the :dispatch-later fx handler ----------

(deftest dispatch-later-fx-stamps-source-fx-dispatch-later
  (testing ":dispatch-later fx stamps `:source :fx-dispatch-later` on the deferred dispatch"
    (let [seen      (atom [])
          envelopes (atom {})
          ;; rf2-d2841 — the completion signal rides the ALWAYS-ON probe, not
          ;; the trace listener. Waiting on a trace under -Dre-frame.debug=false
          ;; simply never returns, so the case used to burn its whole 2s timeout
          ;; before failing.
          done      (promise)]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-fx :test/probe
          (fn [m [level]]
            (swap! envelopes assoc level (:envelope m))
            (when (= :child level) (deliver done :seen))))
        (rf/reg-event :test/parent
          (fn [_ _]
            {:fx [[:test/probe [:parent]]
                  [:dispatch-later {:ms 1 :event [:test/child]}]]}))
        (rf/reg-event :test/child
          (fn [{:keys [db]} _] {:db db :fx [[:test/probe [:child]]]}))

        (rf/dispatch-sync [:test/parent] {:source :ui})

        (is (= :seen (deref done 2000 :timeout))
            "the deferred :test/child dispatch fired")

        ;; ---- ALWAYS-ON (rf2-d2841) ---------------------------------------
        (let [parent-env (:parent @envelopes)
              child-env  (:child  @envelopes)]
          (is (some? parent-env))
          (is (some? child-env))
          (is (= :ui                (:source parent-env)))
          (is (= :fx-dispatch-later (:source child-env))
              ":dispatch-later fx stamped :source :fx-dispatch-later on the deferred dispatch (rf2-ejtpd)"))

        ;; ---- rf2-d2841 dev arm --------------------------------------------
        (when interop/debug-enabled?
          (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
                parent-ev  (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
                child-ev   (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
            (is (some? parent-ev))
            (is (some? child-ev))
            (is (= :ui                (:source parent-ev)))
            (is (= :fx-dispatch-later (:source child-ev))
                ":dispatch-later fx stamped :source :fx-dispatch-later on the deferred dispatch (rf2-ejtpd)")))
        (finally (rf/unregister-listener! :trace ::rec))))))
