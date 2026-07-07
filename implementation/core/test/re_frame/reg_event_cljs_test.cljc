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
            [re-frame.events :as events]
            [re-frame.image-assembly :as image-assembly]
            [re-frame.registrar :as registrar]
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
    (rf/reg-interceptor :reg-event-test/noop {:before identity :after identity})
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

(defn- stub-throw-reason
  "Call `f` (one of the retired throwing stubs) and return the `:reason` text it
  raises, or `:no-throw` if it did not throw."
  [f]
  (try (f)
       :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:reason (ex-data e)))))

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

(deftest reg-event-ctx-removed-names-reg-interceptor-not-arrow-interceptor
  ;; Cross-wave coherence (rf2-0adhqs.12): EP-0022 demoted `->interceptor` to a
  ;; framework-internal lowering constructor and made `reg-interceptor` the ONE
  ;; public authoring form. The EP-0018 reg-event-ctx-removed stub must therefore
  ;; point users at `reg-interceptor`, NOT the now-internal `->interceptor`.
  (testing "the reg-event-ctx-removed :reason names reg-interceptor"
    (let [reason (stub-throw-reason
                   #(rf/reg-event-ctx :reg-event-test/ctx-reason (fn [_ _] nil)))]
      (is (string? reason) "the stub raises an ex-info carrying a :reason string")
      (is (re-find #"reg-interceptor" reason)
          "the recovery names reg-interceptor (the public authoring form)")
      (is (not (re-find #"->interceptor" reason))
          "the recovery does NOT name ->interceptor (internal-only post-EP-0022)"))))

;; ===========================================================================
;; 5. :rf/set-db — the framework-standard app-db seeding event (EP-0027,
;;    rf2-v1xzoo)
;; ===========================================================================

(defn- handler-throw-id
  "Call the `:rf/set-db` handler directly with `event` (the event vector) and
  return the `:rf.error/id` it raises, or `:no-throw` if it returned normally.
  The handler's bad-argument validation throws via `error/throw-error!`, so the
  unit-level contract is asserted directly on the handler (a dispatch-level
  throw is captured by the cascade as `:rf.error/handler-exception` and
  recovered, so it would not surface to the `dispatch-sync` caller)."
  [event]
  (try (events/set-db-handler {} event)
       :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest set-db-sets-app-db
  (testing "[:rf/set-db {:n 0}] REPLACES app-db with the supplied map (EP-0027
            §:rf/set-db) — read back through a layer-1 subscription"
    (rf/reg-sub :reg-event-test/whole-db (fn [db _] db))
    (rf/dispatch-sync [:rf/set-db {:n 0}])
    (is (= {:n 0} @(rf/subscribe [:reg-event-test/whole-db]))
        "app-db is replaced wholesale with {:n 0}")))

(deftest set-db-replaces-not-merges
  (testing ":rf/set-db REPLACES all of app-db (it is NOT a merge) — a second
            set-db with a disjoint map leaves only the new map"
    (rf/reg-sub :reg-event-test/whole-db (fn [db _] db))
    (rf/dispatch-sync [:rf/set-db {:a 1 :b 2}])
    (rf/dispatch-sync [:rf/set-db {:c 3}])
    (is (= {:c 3} @(rf/subscribe [:reg-event-test/whole-db]))
        "the second set-db replaced app-db entirely — :a / :b are gone")))

(deftest set-db-empty-map-empties-app-db
  (testing "[:rf/set-db {}] empties app-db (the legal empty-app-db case,
            EP-0027 §:rf/set-db)"
    (rf/reg-sub :reg-event-test/whole-db (fn [db _] db))
    (rf/dispatch-sync [:rf/set-db {:seed :present}])
    (rf/dispatch-sync [:rf/set-db {}])
    (is (= {} @(rf/subscribe [:reg-event-test/whole-db]))
        "app-db is emptied to {}")))

(deftest set-db-bad-value-throws-set-db-bad-value
  (testing "a missing / nil / non-map argument to [:rf/set-db x] fails with
            :rf.error/set-db-bad-value (EP-0027 §:rf/set-db) — the handler
            validates exactly one map argument"
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db]))
        "[:rf/set-db] (no argument) fails :rf.error/set-db-bad-value")
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db nil]))
        "[:rf/set-db nil] fails :rf.error/set-db-bad-value")
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db 5]))
        "[:rf/set-db 5] (non-map) fails :rf.error/set-db-bad-value")
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db "nope"]))
        "[:rf/set-db \"nope\"] (non-map) fails :rf.error/set-db-bad-value"))
  (testing "EXTRA trailing args fail with :rf.error/set-db-bad-value (rf2-izy3b2)
            — :rf/set-db takes exactly one map argument; extras were previously
            silently ignored"
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db {:n 0} :junk]))
        "[:rf/set-db {:n 0} :junk] (extra arg) fails :rf.error/set-db-bad-value")
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db {} :a :b]))
        "[:rf/set-db {} :a :b] (two extra args) fails :rf.error/set-db-bad-value")
    (is (= :rf.error/set-db-bad-value (handler-throw-id [:rf/set-db nil :x]))
        "[:rf/set-db nil :x] (extra arg, non-map primary) fails :rf.error/set-db-bad-value"))
  (testing "a valid map argument (including {}) does NOT throw and returns the
            {:db new-db} effect"
    (is (= {:db {:n 0}} (events/set-db-handler {} [:rf/set-db {:n 0}]))
        "a map argument returns {:db new-db}")
    (is (= {:db {}} (events/set-db-handler {} [:rf/set-db {}]))
        "the empty map is valid — returns {:db {}}")))

(deftest set-db-resolves-in-both-registrars
  (testing ":rf/set-db is registered as a framework standard in BOTH the regular
            registrar AND the EP-0023 image standard registry (EP-0027
            §:rf/set-db) — so it resolves whether or not a frame's image
            generation is in scope"
    ;; Re-seed idempotently (mirrors `init!`): sibling test nses may have called
    ;; `image-assembly/clear-standards!` and re-seeded only `:rf.interceptor/path`
    ;; — in the shared cljs.test bundle that would strand `:rf/set-db` from the
    ;; standard registry by the time this test runs. `register-set-db-standard!`
    ;; restores BOTH registrars, so this assertion is run-order-independent.
    (events/register-set-db-standard!)
    ;; Regular registrar: the runnable descriptor is present with the
    ;; :rf/event-handler wrapper at the tail of its :interceptors chain.
    (let [meta (registrar/handler-meta :event :rf/set-db)]
      (is (some? meta)
          ":rf/set-db resolves in the regular registrar")
      (is (= events/set-db-handler (:handler-fn meta))
          "the regular registrar carries the framework set-db handler-fn")
      (is (= [:rf/event-handler] (mapv :id (:interceptors meta)))
          "the runnable :rf/event-handler wrapper is the chain tail"))
    ;; Image standard registry: the same descriptor is unioned into every
    ;; resolved image generation (stamped :standard true). Read through the
    ;; public `standard-descriptors` seq and find the [:event :rf/set-db] entry.
    (let [std (->> (image-assembly/standard-descriptors)
                   (filter #(and (= :event (:kind %)) (= :rf/set-db (:id %))))
                   first)]
      (is (some? std)
          ":rf/set-db resolves in the image standard registry")
      (is (true? (:standard std))
          "the standard descriptor is stamped :standard true")
      (is (= events/set-db-handler (:handler-fn std))
          "the standard descriptor carries the same framework set-db handler-fn"))))

(deftest set-db-app-reregistration-is-reserved-id-collision
  (testing "re-registering :rf/set-db in app code via the public reg-event is a
            RESERVED-ID COLLISION that fails loud (:rf.error/reserved-event-id,
            EP-0027 §:rf/set-db) — the :rf/* single-root is framework-owned"
    (let [thrown (try (rf/reg-event :rf/set-db (fn [_ _] {:db {:hijacked true}}))
                      :no-throw
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e
                        (:rf.error/id (ex-data e))))]
      (is (= :rf.error/reserved-event-id thrown)
          "an app reg-event of :rf/set-db raises :rf.error/reserved-event-id"))
    ;; The framework's own :rf/set-db registration is intact — the app attempt
    ;; registered nothing (the guard fires before any registrar write).
    (is (= events/set-db-handler
           (:handler-fn (registrar/handler-meta :event :rf/set-db)))
        "the framework :rf/set-db handler is unchanged after the rejected app re-registration")))
