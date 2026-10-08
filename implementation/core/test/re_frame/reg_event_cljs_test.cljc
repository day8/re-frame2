(ns re-frame.reg-event-cljs-test
  "`reg-event`, the one public event form (Spec 002 §Event handlers): the
  metadata `:interceptors` chain, the return value, nil/{} no-op returns, the
  retired `reg-event-db` / `-fx` / `-ctx` throwing stubs, and the
  framework-standard `:rf/set-db` event.

  `:doc` is stripped from the stored registry entry in production, so its one
  assertion sits in a `(when rf.interop/debug-enabled? ...)` arm."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.events :as rf.events]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest reg-event-metadata-interceptors-thread-the-chain
  (rf/reg-interceptor :reg-event-test/noop {:before identity :after identity})
  (rf/reg-event :reg-event-test/with-icpt
    {:doc "doc" :interceptors [:reg-event-test/noop]}
    (fn [{:keys [db]} _] {:db db}))
  (let [meta (rf/handler-meta {:source :store :kind :event :id :reg-event-test/with-icpt})]
    (when rf.interop/debug-enabled?
      (is (= "doc" (:doc meta))))
    ;; the authored ref stays a keyword; the framework wrapper is a map
    (is (= [:reg-event-test/noop :rf/event-handler]
           (mapv (fn [e] (if (keyword? e) e (:id e))) (:interceptors meta)))
        "the user chain sits before the framework wrapper")))

(deftest reg-event-returns-id
  (is (= :reg-event-test/ret
         (rf/reg-event :reg-event-test/ret (fn [_ _] {})))))

(deftest reg-event-nil-and-empty-return-are-noops
  (rf/reg-sub :reg-event-test/seed (fn [db _] (:seed db :untouched)))
  (rf/reg-event :reg-event-test/seed! (fn [{:keys [db]} _] {:db (assoc db :seed :set)}))
  (rf/reg-event :reg-event-test/nil-ret (fn [_ _] nil))
  (rf/reg-event :reg-event-test/empty-ret (fn [_ _] {}))
  (rf/dispatch-sync [:reg-event-test/seed!])
  (rf/dispatch-sync [:reg-event-test/nil-ret])
  (rf/dispatch-sync [:reg-event-test/empty-ret])
  (is (= :set @(rf/subscribe [:reg-event-test/seed]))))

(defn- stub-throw-id [f]
  (try (f)
       :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; that the stubs register nothing is events_test's
;; retired-reg-event-stubs-register-nothing
(deftest retired-reg-event-names-throw-their-removal-stubs
  (is (= [:rf.error/reg-event-db-removed
          :rf.error/reg-event-fx-removed
          :rf.error/reg-event-ctx-removed]
         [(stub-throw-id #(rf/reg-event-db :reg-event-test/via-db (fn [_ _] nil)))
          (stub-throw-id #(rf/reg-event-fx :reg-event-test/via-fx (fn [_ _] nil)))
          (stub-throw-id #(rf/reg-event-ctx :reg-event-test/via-ctx (fn [_ _] nil)))])))

(deftest reg-event-ctx-removed-names-reg-interceptor-not-arrow-interceptor
  ;; the recovery names the public authoring form, not the internal
  ;; ->interceptor* lowering constructor
  (let [reason (try (rf/reg-event-ctx :reg-event-test/ctx-reason (fn [_ _] nil))
                    :no-throw
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                      (:reason (ex-data e))))]
    (is (re-find #"reg-interceptor" reason))
    (is (not (re-find #"->interceptor" reason)))))

(defn- handler-throw-id
  "The `:rf.error/id` the `:rf/set-db` handler raises for `event`, called
  directly: through a dispatch the throw would be recovered as
  `:rf.error/handler-exception`."
  [event]
  (try (rf.events/set-db-handler {} event)
       :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest set-db-replaces-not-merges
  (rf/reg-sub :reg-event-test/whole-db (fn [db _] db))
  (rf/dispatch-sync [:rf/set-db {:a 1 :b 2}])
  (rf/dispatch-sync [:rf/set-db {:c 3}])
  (is (= {:c 3} @(rf/subscribe [:reg-event-test/whole-db]))))

(deftest set-db-bad-value-throws-set-db-bad-value
  ;; exactly one map argument: missing, non-map and extra args all fail
  (is (= [:rf.error/set-db-bad-value :rf.error/set-db-bad-value :rf.error/set-db-bad-value]
         (mapv handler-throw-id [[:rf/set-db] [:rf/set-db nil] [:rf/set-db {:n 0} :junk]])))
  (is (= {:db {:n 0}} (rf.events/set-db-handler {} [:rf/set-db {:n 0}]))))

(deftest set-db-app-reregistration-is-reserved-id-collision
  (is (= :rf.error/reserved-event-id
         (try (rf.events/reg-event :rf/set-db (fn [_ _] {:db {:hijacked true}}))
              :no-throw
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core/ExceptionInfo) e
                (:rf.error/id (ex-data e))))))
  (is (= rf.events/set-db-handler
         (:handler-fn (rf.registrar/handler-meta :event :rf/set-db)))
      "the guard fires before any registrar write"))
