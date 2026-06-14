(ns re-frame.reg-event-cljs-test
  "EP-0018 Slice B-add (rf2-xhfxcs.2): narrow tests for the new one public
  event form `reg-event` — semantically `reg-event-fx` (coeffects in, a closed
  effects map out), under the bare name. Per Spec 002 §Event handlers, Spec 001
  §Registry model, and EP-0018 §1/§4/§5.

  This slice is ADDITIVE: `reg-event` is added; `reg-event-db` / `reg-event-fx`
  / `reg-event-ctx` remain fully working for the additive window (their removal
  is a later slice, Z = rf2-xhfxcs.14). The coexistence test below asserts both
  the new form and the old forms register and dispatch side-by-side.

  `reg-event` shares the `reg-event-fx` registration path verbatim, so it
  registers under registry kind :event with `:event/kind :fx` and the
  `:rf/fx-handler` wrapper (the additive-window internals are unchanged). These
  tests pin that shape plus the :db/:fx effect semantics and uniform
  `:rf.cofx/requires` support (the EP-0017 hole the collapse closes).

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

(deftest reg-event-registers-under-event-kind-fx
  (testing "reg-event registers under registry kind :event with :event/kind :fx
            and the :rf/fx-handler wrapper (it shares the reg-event-fx path
            verbatim during the additive window)"
    (rf/reg-event :reg-event-test/shape
      (fn [{:keys [db]} _] {:db (assoc db :marker :v)}))
    (let [meta (rf/handler-meta :event :reg-event-test/shape)]
      (is (some? meta)
          "reg-event registers under registry kind :event")
      (is (= :fx (:event/kind meta))
          "reg-event registers under :event/kind :fx (shares the reg-event-fx path)")
      (is (= [:rf/fx-handler] (mapv :id (:interceptors meta)))
          "the framework wrapper is the one :rf/fx-handler interceptor"))))

(deftest reg-event-metadata-interceptors-thread-the-chain
  (testing "reg-event honours the metadata-map :interceptors superset slot —
            the user chain sits before the framework wrapper"
    (let [noop {:id :reg-event-test/noop :before identity :after identity}]
      (rf/reg-event :reg-event-test/with-icpt
        {:doc "doc" :interceptors [noop]}
        (fn [{:keys [db]} _] {:db db}))
      (let [meta (rf/handler-meta :event :reg-event-test/with-icpt)]
        (is (= "doc" (:doc meta))
            "the reflection metadata is retained on the registry entry")
        (is (= [:reg-event-test/noop :rf/fx-handler] (mapv :id (:interceptors meta)))
            "the metadata-map :interceptors chain sits before the runtime wrapper")))))

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
;; 4. Additive coexistence — reg-event sits beside the old forms (this slice)
;; ===========================================================================

(deftest additive-window-old-forms-still-work
  (testing "EP-0018 Slice B-add: reg-event coexists with reg-event-db /
            reg-event-fx / reg-event-ctx — all four register and dispatch"
    (rf/reg-sub :reg-event-test/tally (fn [db _] (:tally db [])))
    (rf/reg-event :reg-event-test/via-reg-event
      (fn [{:keys [db]} _] {:db (update db :tally (fnil conj []) :reg-event)}))
    (rf/reg-event-db :reg-event-test/via-db
      (fn [db _] (update db :tally (fnil conj []) :db)))
    (rf/reg-event-fx :reg-event-test/via-fx
      (fn [{:keys [db]} _] {:db (update db :tally (fnil conj []) :fx)}))
    (rf/reg-event-ctx :reg-event-test/via-ctx
      (fn [ctx]
        (update-in ctx [:effects :db]
                   (fn [db] (update (or db (get-in ctx [:coeffects :db]))
                                    :tally (fnil conj []) :ctx)))))
    (rf/dispatch-sync [:reg-event-test/via-reg-event])
    (rf/dispatch-sync [:reg-event-test/via-db])
    (rf/dispatch-sync [:reg-event-test/via-fx])
    (is (= [:reg-event :db :fx]
           @(rf/subscribe [:reg-event-test/tally]))
        "reg-event, reg-event-db and reg-event-fx all committed in order")
    (let [db-meta  (rf/handler-meta :event :reg-event-test/via-db)
          fx-meta  (rf/handler-meta :event :reg-event-test/via-fx)
          ctx-meta (rf/handler-meta :event :reg-event-test/via-ctx)]
      (is (= :db (:event/kind db-meta)) "reg-event-db keeps :event/kind :db")
      (is (= :fx (:event/kind fx-meta)) "reg-event-fx keeps :event/kind :fx")
      (is (= :ctx (:event/kind ctx-meta)) "reg-event-ctx keeps :event/kind :ctx")
      (is (= [:rf/db-handler] (mapv :id (:interceptors db-meta)))
          "reg-event-db keeps the :rf/db-handler wrapper id")
      (is (= [:rf/ctx-handler] (mapv :id (:interceptors ctx-meta)))
          "reg-event-ctx keeps the :rf/ctx-handler wrapper id"))))
