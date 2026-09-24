(ns re-frame.cofx-supplier-failure-outcome-cljs-test
  "The dispatch outcome of an event whose declared coeffect could not be
  delivered.

  A coeffect supplier that throws during context assembly — an ambient
  supplier, or the generator behind a recordable fact — FAILS the event.
  `re-frame.cofx` emits exactly one `:rf.error/coeffect-exception` naming the
  supplier, the handler does not run, and nothing installs: no `:db`, no
  `:fx`, the same as a handler throw (Spec 009 §Error event catalogue). So the
  always-on `:events` record reads `:outcome :error`. The two always-on axes
  are all an off-box shipper sees of a production dispatch, and an `:ok` on
  the second would report a clean settle for an event whose handler never ran.

  THE CONTROL is the other producer of `:rf/skip-handler?`: an interceptor
  that short-circuits the handler ON PURPOSE (an auth guard that redirects
  instead of committing). That skip is not a failure. Its outcome is `:ok`,
  it emits nothing on the error axis, and the effects it staged in place of
  the handler commit and walk.

  Every assertion reads the ALWAYS-ON registries (`register-error-listener!`,
  `register-event-listener!`), never the dev trace, so the namespace holds in
  every posture. Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs
  `:node-test` build (`npm run test:cljs`) AND the JVM `clojure -M:test`
  runner both run it. Plain CLJC; no DOM dependency."
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

;; ---------------------------------------------------------------------------
;; Helpers — both ALWAYS-ON axes, which are what a production build has.
;; ---------------------------------------------------------------------------

(defn- record-both-axes
  "Run `body-fn`, capturing every always-on record it fans on the `:errors`
  axis and the `:events` axis. Returns `{:errors [...] :events [...]}`."
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
  "The shared contract for a supplier that threw while `event-id` was
  assembled: the dispatch settles `:error`, the ONLY error record is the one
  coeffect-exception attributed to `cofx-id` and `event-id`, and the handler
  never ran."
  [axes event-id cofx-id handler-runs]
  (is (= [[event-id :error]] (outcomes axes))
      (str "the handled-event record reports `:error` for an event whose "
           "coeffect could not be delivered. Observed " (outcomes axes)))
  (is (= [:rf.error/coeffect-exception] (mapv :error (:errors axes)))
      (str "exactly ONE error record, the supplier's own coeffect-exception — "
           "the router adds no second emission. Observed "
           (mapv :error (:errors axes))))
  (let [rec (first (:errors axes))]
    (is (= cofx-id (:failing-id rec))
        "the error record names the failing supplier")
    (is (= event-id (:event-id rec))
        "and the event whose assembly failed"))
  (is (zero? @handler-runs) "the handler never ran"))

;; ===========================================================================
;; Supplier failures settle `:error`
;; ===========================================================================

(deftest throwing-ambient-supplier-fails-the-event
  (testing "an ambient supplier that throws during context assembly settles
            the event as `:error`, and a clean dispatch after it settles `:ok`"
    (let [handler-runs (atom 0)]
      (rf/reg-cofx ::throws-ambient
        (fn [] (throw (ex-info "ambient supplier boom" {}))))
      (rf/reg-event ::uses-ambient
        {:rf.cofx/requires [::throws-ambient]}
        (fn [{:keys [db]} _]
          (swap! handler-runs inc)
          {:db (assoc db :handled true)}))
      (rf/reg-event ::control
        (fn [{:keys [db]} _] {:db (assoc db :control true)}))
      (let [failed  (record-both-axes #(rf/dispatch-sync [::uses-ambient]))
            control (record-both-axes #(rf/dispatch-sync [::control]))]
        (assert-supplier-failure failed ::uses-ambient ::throws-ambient handler-runs)
        (is (nil? (:handled (app-db)))
            "nothing the handler would have written exists")
        (is (= [[::control :ok]] (outcomes control))
            "a clean dispatch after the failure settles `:ok`")
        (is (empty? (:errors control)) "and emits no error record")
        (is (true? (:control (app-db))) "and commits its write")))))

(deftest throwing-generator-fails-the-event
  (testing "the generator behind a recordable fact takes the same route: a
            throw while it mints the fact settles the event as `:error`"
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
      ;; generator really runs; a `:strict` policy would never reach it.
      (let [failed (record-both-axes
                     #(rf/dispatch-sync [::uses-generator]
                                        {:rf.cofx/mint-policy :live}))]
        (assert-supplier-failure failed ::uses-generator ::throws-generator handler-runs)
        (is (nil? (:handled (app-db)))
            "nothing the handler would have written exists")))))

(deftest failed-delivery-installs-nothing
  (testing "a failed coeffect delivery aborts the event like a handler throw:
            effects an interceptor staged around the skipped handler are not
            installed, and no `:fx` walks"
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
      (let [failed (record-both-axes #(rf/dispatch-sync [::staged-uses-ambient]))]
        (assert-supplier-failure failed ::staged-uses-ambient ::throws-ambient handler-runs)
        (is (nil? (:staged (app-db)))
            "no `:db` install: the staged write never reached app-db")
        (is (zero? @fx-runs) "no `:fx` walk: the staged effect never ran")))))

;; ===========================================================================
;; The control — an intentional skip is not a failure
;; ===========================================================================

(deftest intentional-interceptor-skip-stays-ok
  (testing "an interceptor that short-circuits the handler on purpose settles
            `:ok`: nothing is emitted on the error axis, and the effects it
            staged in place of the handler commit and walk"
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
        (is (= [[::guarded :ok]] (outcomes axes))
            (str "a deliberate `:rf/skip-handler?` is not a failure, so the "
                 "outcome stays `:ok`. Observed " (outcomes axes)))
        (is (empty? (:errors axes)) "and nothing is emitted on the error axis")
        (is (zero? @handler-runs) "the handler was skipped")
        (is (true? (:guarded (app-db)))
            "the guard's staged `:db` write committed")
        (is (= 1 @fx-runs) "and its staged `:fx` walked")))))
