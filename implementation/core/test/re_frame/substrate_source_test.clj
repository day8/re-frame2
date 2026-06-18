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

  JVM-only — substrate fx-handler behaviour is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
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
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- :fx-dispatch stamp by the :dispatch fx handler ----------------------

(deftest dispatch-fx-stamps-source-fx-dispatch
  (testing ":dispatch fx stamps `:source :fx-dispatch` on the child envelope"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event :test/parent
          (fn [_ _]
            {:fx [[:dispatch [:test/child]]]}))
        (rf/reg-event :test/child (fn [{:keys [db]} _] {:db db}))

        ;; Parent stamps `:source :ui` (mimicking a UI handler call-site).
        (rf/dispatch-sync [:test/parent] {:source :ui})

        (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
              parent-ev  (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
              child-ev   (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
          (is (some? parent-ev) "parent's :rf.event/dispatched is captured")
          (is (some? child-ev)  "child's :rf.event/dispatched is captured")
          (is (= :ui (:source parent-ev))
              "parent carries the caller-supplied :source :ui")
          (is (= :fx-dispatch (:source child-ev))
              ":dispatch fx stamped :source :fx-dispatch on the child envelope (rf2-ejtpd)"))
        (finally (rf/unregister-listener! :trace ::rec))))))

(deftest dispatch-fx-overrides-parent-source-three-deep
  (testing ":fx-dispatch is the *immediate* trigger — overrides at every cascade depth"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event :test/lvl-0 (fn [_ _] {:fx [[:dispatch [:test/lvl-1]]]}))
        (rf/reg-event :test/lvl-1 (fn [_ _] {:fx [[:dispatch [:test/lvl-2]]]}))
        (rf/reg-event :test/lvl-2 (fn [{:keys [db]} _] {:db db}))

        (rf/dispatch-sync [:test/lvl-0] {:source :ui})

        (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
              ev-for     (fn [id]
                           (first (filter #(= [id] (get-in % [:tags :rf.event/v])) dispatched)))]
          (is (= :ui          (:source (ev-for :test/lvl-0))) "root keeps :ui")
          (is (= :fx-dispatch (:source (ev-for :test/lvl-1))) "lvl-1 stamped :fx-dispatch")
          (is (= :fx-dispatch (:source (ev-for :test/lvl-2)))
              "lvl-2 ALSO stamped :fx-dispatch (immediate trigger, not :ui from the root)"))
        (finally (rf/unregister-listener! :trace ::rec))))))

(deftest dispatch-fx-preserves-origin-while-overriding-source
  (testing ":origin propagates through the cascade; :source is OVERRIDDEN per-step"
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event :test/parent (fn [_ _] {:fx [[:dispatch [:test/child]]]}))
        (rf/reg-event :test/child  (fn [{:keys [db]} _] {:db db}))

        (rf/dispatch-sync [:test/parent] {:source :ui :origin :pair})

        (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
              parent-ev  (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
              child-ev   (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
          (is (= :pair (get-in parent-ev [:tags :rf.event/origin])))
          (is (= :pair (get-in child-ev  [:tags :rf.event/origin]))
              ":origin propagates through the cascade")
          (is (= :ui          (:source parent-ev)))
          (is (= :fx-dispatch (:source child-ev))
              ":source is overridden by the substrate's :dispatch fx (rf2-ejtpd)"))
        (finally (rf/unregister-listener! :trace ::rec))))))

;; ---- :fx-dispatch-later stamp by the :dispatch-later fx handler ----------

(deftest dispatch-later-fx-stamps-source-fx-dispatch-later
  (testing ":dispatch-later fx stamps `:source :fx-dispatch-later` on the deferred dispatch"
    (let [seen (atom [])
          done (promise)]
      (rf/register-listener! :trace ::rec
        (fn [ev]
          (swap! seen conj ev)
          (when (and (= :rf.event/dispatched (:operation ev))
                     (= [:test/child] (get-in ev [:tags :rf.event/v])))
            (deliver done :seen))))
      (try
        (rf/reg-event :test/parent
          (fn [_ _]
            {:fx [[:dispatch-later {:ms 1 :event [:test/child]}]]}))
        (rf/reg-event :test/child (fn [{:keys [db]} _] {:db db}))

        (rf/dispatch-sync [:test/parent] {:source :ui})

        (is (= :seen (deref done 2000 :timeout))
            "the deferred :test/child dispatch fired")

        (let [dispatched (->> @seen (filter #(= :rf.event/dispatched (:operation %))))
              parent-ev  (first (filter #(= [:test/parent] (get-in % [:tags :rf.event/v])) dispatched))
              child-ev   (first (filter #(= [:test/child]  (get-in % [:tags :rf.event/v])) dispatched))]
          (is (some? parent-ev))
          (is (some? child-ev))
          (is (= :ui                (:source parent-ev)))
          (is (= :fx-dispatch-later (:source child-ev))
              ":dispatch-later fx stamped :source :fx-dispatch-later on the deferred dispatch (rf2-ejtpd)"))
        (finally (rf/unregister-listener! :trace ::rec))))))
