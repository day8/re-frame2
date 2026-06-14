(ns re-frame.reg-event-cljs-test
  "EP-0018 Slice Z (rf2-xhfxcs.14): narrow tests for the ONE public event form
  `reg-event` — coeffects in, a closed effects map out, under the bare name.
  Per Spec 002 §Event handlers, Spec 001 §Registry model, and EP-0018 §1/§4/§5.

  Slice Z COLLAPSED registration to this one form: `reg-event-db` /
  `reg-event-fx` are REMOVED and public `reg-event-ctx` is demoted — the three
  retired names survive only as throwing stubs (this suite was the additive-
  window coexistence suite; that premise is gone, so the coexistence assertions
  are replaced by stub-removal assertions).

  `reg-event` registers under registry kind :event with the ONE framework
  wrapper `:rf/event-handler` (`:rf/default? true`); the historical
  `:event/kind` sub-tag is gone. These tests pin that shape plus the :db/:fx
  effect semantics and uniform `:rf.cofx/requires` support (the EP-0017 hole
  the collapse closes).

  `.cljc` so the suite runs under BOTH the bounded core JVM gate and
  `npm run test:cljs`. Harness mirrors `cofx_cljs_test.cljc` — the shared
  `test-support/make-reset-runtime-fixture` wraps every body in
  `(with-frame :rf/default …)` so the ambient `dispatch-sync` calls resolve."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ===========================================================================
;; 1. Registration shape — registers under :event with the fx wrapper
;; ===========================================================================

(deftest reg-event-registers-under-event-kind
  (testing "reg-event registers under registry kind :event with the ONE
            framework wrapper :rf/event-handler and NO :event/kind sub-tag
            (EP-0018 Slice Z — the per-kind ids and the :event/kind tag are
            gone)"
    (rf/reg-event :reg-event-test/shape
      (fn [{:keys [db]} _] {:db (assoc db :marker :v)}))
    (let [meta (rf/handler-meta :event :reg-event-test/shape)]
      (is (some? meta)
          "reg-event registers under registry kind :event")
      (is (not (contains? meta :event/kind))
          "the :event/kind sub-tag is gone (one form, no kind)")
      (is (= [:rf/event-handler] (mapv :id (:interceptors meta)))
          "the framework wrapper is the one :rf/event-handler interceptor"))))

(deftest reg-event-metadata-interceptors-thread-the-chain
  (testing "reg-event honours the metadata-map :interceptors superset slot —
            the user chain (a REF, EP-0022 reference-only) sits before the
            framework wrapper"
    (rf/reg-interceptor* :reg-event-test/noop {:before identity :after identity})
    (rf/reg-event :reg-event-test/with-icpt
      {:doc "doc" :interceptors [:reg-event-test/noop]}
      (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :reg-event-test/with-icpt)]
      (is (= "doc" (:doc meta))
          "the reflection metadata is retained on the registry entry")
      ;; The stored chain holds the AUTHORED ref (a keyword) for the user entry;
      ;; only the framework wrapper is a map carrying :id :rf/event-handler.
      (is (= [:reg-event-test/noop :rf/event-handler]
             (mapv (fn [e] (if (keyword? e) e (:id e))) (:interceptors meta)))
          "the metadata-map :interceptors ref sits before the runtime wrapper"))))

(deftest reg-event-returns-id
  (testing "reg-event returns its id (Conventions §reg-* return-value)"
    (is (= :reg-event-test/ret
           (rf/reg-event :reg-event-test/ret (fn [_ _] {}))))))

;; ===========================================================================
;; 2. Effect semantics — :db commit, :fx walk, nil/{} no-op
;; ===========================================================================

(deftest reg-event-db-effect-commits-via-app-db
  (testing "a reg-event handler's {:db …} effect commits — read back through a
            layer-1 subscription"
    (rf/reg-sub :reg-event-test/count (fn [db _] (:count db 0)))
    (rf/reg-event :reg-event-test/bump
      (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))
    (rf/dispatch-sync [:reg-event-test/bump])
    (rf/dispatch-sync [:reg-event-test/bump])
    (rf/dispatch-sync [:reg-event-test/bump])
    (is (= 3 @(rf/subscribe [:reg-event-test/count]))
        "three {:db (update … inc)} effects committed cumulatively")))

(deftest reg-event-fx-effect-walks
  (testing "a reg-event handler's :fx vector dispatches its entries (the db
            write is an explicit effect like any other)"
    (rf/reg-sub :reg-event-test/log (fn [db _] (:log db [])))
    (rf/reg-sub :reg-event-test/kicked? (fn [db _] (:kicked? db false)))
    (rf/reg-event :reg-event-test/append
      (fn [{:keys [db]} [_ v]] {:db (update db :log (fnil conj []) v)}))
    (rf/reg-event :reg-event-test/kickoff
      (fn [{:keys [db]} _]
        {:db (assoc db :kicked? true)
         :fx [[:dispatch [:reg-event-test/append :a]]
              [:dispatch [:reg-event-test/append :b]]]}))
    (rf/dispatch-sync [:reg-event-test/kickoff])
    (is (true? @(rf/subscribe [:reg-event-test/kicked?]))
        "the :db effect committed alongside the :fx walk")
    (is (= [:a :b] @(rf/subscribe [:reg-event-test/log]))
        "the :fx-dispatched events ran in source order")))

(deftest reg-event-nil-and-empty-return-are-noops
  (testing "nil and {} returns from a reg-event handler are documented no-ops"
    (rf/reg-sub :reg-event-test/seed (fn [db _] (:seed db :untouched)))
    (rf/reg-event :reg-event-test/seed! (fn [{:keys [db]} _] {:db (assoc db :seed :set)}))
    (rf/reg-event :reg-event-test/nil-ret (fn [_ _] nil))
    (rf/reg-event :reg-event-test/empty-ret (fn [_ _] {}))
    (rf/dispatch-sync [:reg-event-test/seed!])
    (rf/dispatch-sync [:reg-event-test/nil-ret])
    (rf/dispatch-sync [:reg-event-test/empty-ret])
    (is (= :set @(rf/subscribe [:reg-event-test/seed]))
        "neither the nil nor the {} handler disturbed app-db")))

;; ===========================================================================
;; 3. :rf.cofx/requires support (EP-0017 hole closed — REQUIRED by the bead)
;; ===========================================================================

(deftest reg-event-supports-rf-cofx-requires
  (testing "reg-event accepts :rf.cofx/requires and the declared value arrives
            FLAT in the coeffects map (EP-0017 §2/§5; the collapse closes the
            db-handler hole — every event can declare coeffects uniformly)"
    (let [seen (atom ::unset)]
      (rf/reg-cofx :reg-event-test/locale (fn [] "en-AU"))
      (rf/reg-event :reg-event-test/read-locale
        {:rf.cofx/requires [:reg-event-test/locale]}
        (fn [{:keys [reg-event-test/locale]} _]
          (reset! seen locale)
          {}))
      (rf/dispatch-sync [:reg-event-test/read-locale])
      (is (= "en-AU" @seen)
          "the declared coeffect arrived flat under its id on reg-event"))))

(deftest reg-event-requires-declared-only-delivery
  (testing "ADVERSARIAL: an UNDECLARED recordable leaf is NOT delivered to a
            reg-event handler (declared-only delivery — no silent coupling)"
    (let [had-time? (atom ::unset)]
      (rf/reg-event :reg-event-test/declares-nothing
        (fn [{:keys [rf/time-ms] :as cofx} _]
          (reset! had-time? (contains? cofx :rf/time-ms))
          (is (nil? time-ms))
          {}))
      (rf/dispatch-sync [:reg-event-test/declares-nothing]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (false? @had-time?)
          ":rf/time-ms is delivered ONLY on declaration, never implicitly"))))

(deftest reg-event-requires-stored-on-registration
  (testing "the parsed :rf.cofx/requires is stored on the reg-event registration
            (handler-meta surfaces the raw declaration as authored)"
    (rf/reg-cofx :reg-event-test/who (fn [] :nobody))
    (rf/reg-event :reg-event-test/declarer
      {:rf.cofx/requires [:reg-event-test/who]}
      (fn [_ _] {}))
    (let [meta (rf/handler-meta :event :reg-event-test/declarer)]
      (is (= [:reg-event-test/who] (:rf.cofx/requires meta))
          "the raw :rf.cofx/requires is retained on the registry entry")
      (is (contains? meta :rf.cofx/requires-parsed)
          "the parsed entry vector is stored for the satisfaction step"))))

;; ===========================================================================
;; 4. The retired names are throwing stubs (EP-0018 Slice Z removal)
;; ===========================================================================

(defn- stub-throw-id
  "Call `f` (one of the retired throwing stubs) and return the `:rf.error/id`
  it raises, or `:no-throw` if it did not throw."
  [f]
  (try (f)
       :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest retired-reg-event-names-throw-their-removal-stubs
  (testing "EP-0018 Slice Z: the former additive coexistence is gone —
            reg-event-db / reg-event-fx are REMOVED and public reg-event-ctx is
            demoted; the three retired names are throwing stubs that register
            nothing and raise their naming hard error"
    (is (= :rf.error/reg-event-db-removed
           (stub-throw-id #(rf/reg-event-db :reg-event-test/via-db (fn [_ _] nil))))
        "reg-event-db raises :rf.error/reg-event-db-removed")
    (is (= :rf.error/reg-event-fx-removed
           (stub-throw-id #(rf/reg-event-fx :reg-event-test/via-fx (fn [_ _] nil))))
        "reg-event-fx raises :rf.error/reg-event-fx-removed")
    (is (= :rf.error/reg-event-ctx-removed
           (stub-throw-id #(rf/reg-event-ctx :reg-event-test/via-ctx (fn [_ _] nil))))
        "reg-event-ctx raises :rf.error/reg-event-ctx-removed"))

  (testing "the retired-name stubs register NOTHING; only reg-event commits"
    (rf/reg-sub :reg-event-test/tally (fn [db _] (:tally db [])))
    (rf/reg-event :reg-event-test/via-reg-event
      (fn [{:keys [db]} _] {:db (update db :tally (fnil conj []) :reg-event)}))
    ;; The retired-name calls throw and register nothing.
    (stub-throw-id #(rf/reg-event-db :reg-event-test/db-noreg (fn [_ _] nil)))
    (stub-throw-id #(rf/reg-event-fx :reg-event-test/fx-noreg (fn [_ _] nil)))
    (stub-throw-id #(rf/reg-event-ctx :reg-event-test/ctx-noreg (fn [_ _] nil)))
    (rf/dispatch-sync [:reg-event-test/via-reg-event])
    (is (= [:reg-event] @(rf/subscribe [:reg-event-test/tally]))
        "only the reg-event handler committed; the retired-name stubs registered nothing")))
