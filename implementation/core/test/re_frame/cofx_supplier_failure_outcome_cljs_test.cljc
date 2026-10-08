(ns re-frame.cofx-supplier-failure-outcome-cljs-test
  "The dispatch outcome of an event whose declared coeffect could not be
  delivered. A supplier that throws during context assembly (an ambient
  supplier, or the generator behind a recordable fact) fails the event: one
  `:rf.error/coeffect-exception` names the supplier, the handler does not run,
  nothing installs, and the always-on `:events` record reads `:outcome :error`.
  An off-box shipper sees only those two always-on axes, so an `:ok` there
  would report a clean settle for an event whose handler never ran.

  The control is an interceptor that skips the handler on purpose: it settles
  `:ok`, emits no error, and the effects it staged commit and walk.

  Every assertion reads the always-on `:errors` and `:events` registries, so
  the namespace holds in every posture."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!)
                (rf.event-emit/clear-event-listeners!))}))

(defn- record-both-axes
  "Run `body-fn`, returning `{:errors [...] :events [...]}`: every always-on
  record it fanned on each axis."
  [body-fn]
  (let [errors (atom [])
        events (atom [])]
    (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! errors conj r)))
    (rf.event-emit/register-event-listener! ::rec (fn [r] (swap! events conj r)))
    (try (body-fn)
         (finally
           (rf.error-emit/unregister-error-listener! ::rec)
           (rf.event-emit/unregister-event-listener! ::rec)))
    {:errors @errors :events @events}))

(defn- outcomes
  "`[event-id outcome]` for each handled-event record, in dispatch order."
  [{:keys [events]}]
  (mapv (juxt :event-id :outcome) events))

(defn- app-db [] (rf.frame/frame-app-db-value :rf/default))

(defn- register-counting-fx!
  "Register `::count-fx`, which counts its runs, and return the counter."
  []
  (let [runs (atom 0)]
    (rf/reg-fx ::count-fx (fn [_ _] (swap! runs inc)))
    runs))

(defn- assert-supplier-failure
  "The settle `:error`, the lone coeffect-exception record attributed to
  `cofx-id` and `event-id`, and a handler that never ran."
  [axes event-id cofx-id handler-runs]
  (is (= [[event-id :error]] (outcomes axes)))
  (is (= [[:rf.error/coeffect-exception cofx-id event-id]]
         (mapv (juxt :error :failing-id :event-id) (:errors axes))))
  (is (zero? @handler-runs)))

(deftest throwing-generator-fails-the-event
  (testing "a generator that throws while minting a recordable fact settles the event :error"
    (let [handler-runs (atom 0)]
      (rf/reg-cofx ::throws-generator
        {:recordable? true}
        (fn [] (throw (ex-info "generator boom" {}))))
      (rf/reg-event ::uses-generator
        {:rf.cofx/requires [::throws-generator]}
        (fn [{:keys [db]} _]
          (swap! handler-runs inc)
          {:db (assoc db :handled true)}))
      ;; `:live` mints a declared-absent generator-backed fact, so the
      ;; generator really runs; `:strict` would never reach it.
      (let [failed (record-both-axes
                     #(rf/dispatch-sync [::uses-generator] {:rf.cofx/mint-policy :live}))]
        (assert-supplier-failure failed ::uses-generator ::throws-generator handler-runs)))))

(deftest failed-delivery-installs-nothing
  (testing "a failed delivery aborts the event like a handler throw: effects an
            interceptor staged are not installed, no :fx walks, and a clean
            dispatch after it settles :ok"
    (let [handler-runs (atom 0)
          fx-runs      (register-counting-fx!)]
      (rf/reg-cofx ::throws-ambient
        (fn [] (throw (ex-info "ambient supplier boom" {}))))
      (rf/reg-interceptor ::stages-effects
        {:before (fn [ctx]
                   (-> ctx
                       (assoc-in [:effects :db]
                                 (assoc (get-in ctx [:coeffects :db]) :staged true))
                       (assoc-in [:effects :fx] [[::count-fx :staged]])))})
      (rf/reg-event ::staged-uses-ambient
        {:rf.cofx/requires [::throws-ambient]
         :interceptors     [::stages-effects]}
        (fn [_ _]
          (swap! handler-runs inc)
          {}))
      (rf/reg-event ::control
        (fn [{:keys [db]} _] {:db (assoc db :control true)}))
      (let [failed  (record-both-axes #(rf/dispatch-sync [::staged-uses-ambient]))
            control (record-both-axes #(rf/dispatch-sync [::control]))]
        (assert-supplier-failure failed ::staged-uses-ambient ::throws-ambient handler-runs)
        (is (zero? @fx-runs) "the staged :fx never walked")
        (is (= [[::control :ok]] (outcomes control)))
        (is (empty? (:errors control)))
        (is (= {:control true} (app-db))
            "the staged :db write never landed; the control's did")))))

(deftest intentional-interceptor-skip-stays-ok
  (testing "an interceptor that skips the handler on purpose settles :ok, emits
            no error, and its staged effects commit and walk"
    (let [handler-runs (atom 0)
          fx-runs      (register-counting-fx!)]
      (rf/reg-interceptor ::guard
        {:before (fn [ctx]
                   (-> ctx
                       (assoc :rf/skip-handler? true)
                       (assoc-in [:effects :db]
                                 (assoc (get-in ctx [:coeffects :db]) :guarded true))
                       (assoc-in [:effects :fx] [[::count-fx :guarded]])))})
      (rf/reg-event ::guarded
        {:interceptors [::guard]}
        (fn [_ _]
          (swap! handler-runs inc)
          {}))
      (let [axes (record-both-axes #(rf/dispatch-sync [::guarded]))]
        (is (= [[::guarded :ok]] (outcomes axes)))
        (is (empty? (:errors axes)))
        (is (zero? @handler-runs))
        (is (= {:guarded true} (app-db)))
        (is (= 1 @fx-runs))))))
