(ns re-frame.event-model-conformance-cljs-test
  "Adversarial conformance for the public one-form event model.

  Core's focused suites own individual `reg-event`, coeffect-policy and
  observability behaviour. This cross-artefact suite locks the boundaries that
  are easiest to weaken while those tests remain green: handlers receive the
  canonical coeffects and return a closed effect map; supplied recordable
  coeffects are validated at the durable boundary; registrations have one
  `:rf/event-handler` wrapper and no `:event/kind`; and the `^:no-doc` retired
  forms throw, register nothing, and notify the always-on error channel.

  The retired-form probes are deliberately fail-closed. A form that becomes
  callable again returns `:no-throw`, and registration is checked independently,
  so silently reviving an old API cannot satisfy the suite. Frame-targeted lookup
  belongs to `re-frame.facade-frame-read-cljs-test`; live frame dispatch belongs
  to the sibling `re-frame.event-frame-isolation-conformance-cljs-test`.

  The fixture supplies an ambient `:rf/default` frame and clears the always-on
  listener registries between cases. These `.cljc` tests run on both JVM and
  CLJS."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(defn- clear-observation-state! []
  (rf.error-emit/clear-error-listeners!)
  (rf.event-emit/clear-event-listeners!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [test-fn]
    (clear-observation-state!)
    (test-fn)
    (clear-observation-state!)))

(defn- thrown-error-id
  "Call `thunk` and return the `:rf.error/id` of the ExceptionInfo it raises, or
  `:no-throw` if it did not throw."
  [thunk]
  (try
    (thunk)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) exception
      (:rf.error/id (ex-data exception)))))

(defn- interceptor-chain-ids
  "Return ids from a stored chain without resolving its interceptor references."
  [interceptor-chain]
  (mapv (fn [interceptor-ref]
          (cond
            (keyword? interceptor-ref) interceptor-ref
            (and (vector? interceptor-ref)
                 (keyword? (first interceptor-ref))) (first interceptor-ref)
            (map? interceptor-ref) (:id interceptor-ref)
            :else interceptor-ref))
        interceptor-chain))

;; One-form handler and coeffect contract.

(deftest reg-event-handler-receives-the-canonical-coeffect-keys
  (testing "handlers receive the canonical coeffect keys and the event vector, without an event-kind tag"
    (let [seen-coeffects (atom ::unset)
          seen-event     (atom ::unset)]
      (rf/reg-event :evt-conf/inspect-cofx
        (fn [coeffects event]
          (reset! seen-coeffects coeffects)
          (reset! seen-event event)
          {}))
      (rf/dispatch-sync [:evt-conf/inspect-cofx :payload])
      (is (= [:evt-conf/inspect-cofx :payload] @seen-event)
          "the 2nd handler arg is the full dispatched event vector")
      (let [coeffects @seen-coeffects]
        (is (contains? coeffects :db) "`:db` present in the coeffects map")
        (is (= [:evt-conf/inspect-cofx :payload] (:event coeffects))
            "`:event` is the dispatched event vector, the same value as the 2nd handler arg")
        (is (= :rf/default (:rf.frame/id coeffects))
            "the ambient frame id is delivered as `:rf.frame/id`")
        (is (contains? coeffects :rf.db/runtime) "`:rf.db/runtime` present in the coeffects map")
        (is (map? (:rf.cofx coeffects))
            "`:rf.cofx` is the FLAT recordable-coeffect map (fact-name → value, no grouping sub-maps)")
        (is (not (contains? coeffects :event/kind))
            "the coeffects map carries no `:event/kind` sub-tag (one form)")))))

(deftest dispatch-opt-rf-world-inputs-is-the-generic-unknown-opt-with-did-you-mean
  (testing "the unsupported :rf.world/inputs opt warns and suggests :rf.cofx"
    (rf/reg-event :evt-conf/inspect-renamed (fn [{:keys [db]} _] {:db (assoc db :evt-conf/ran true)}))
    (let [traces (atom [])]
      (rf/register-listener! :trace :evt-conf/renamed-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      ;; Unknown dispatch opts are observational warnings, not hard errors.
      (rf/dispatch-sync [:evt-conf/inspect-renamed]
                        {:rf.world/inputs {:rf/time-ms 1781078400123}})
      (rf/unregister-listener! :trace :evt-conf/renamed-recorder)
      (let [warning-traces (filterv (fn [trace-event]
                                      (and (= :warning (:op-type trace-event))
                                           (= :rf.warning/unknown-dispatch-opt
                                              (:operation trace-event))))
                                    @traces)]
        (is (= 1 (count warning-traces))
            "the unsupported key trips the generic unknown-dispatch-opt warning (not a dedicated error)")
        (let [warning-tags (:tags (first warning-traces))]
          (is (contains? (set (:unknown-keys warning-tags)) :rf.world/inputs)
              "the unsupported key is named as an unrecognised opt")
          (is (re-find #":rf.cofx" (:reason warning-tags))
              "the warning message appends a did-you-mean naming `:rf.cofx` as the replacement"))))))

(deftest live-event-coeffects-carry-no-rf-world-inputs-flat-delivery
  (testing "recordable facts and the canonical :rf.cofx record are flat"
    (let [seen-coeffects (atom ::unset)]
      (rf/reg-event :evt-conf/flat-delivery
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [coeffects _] (reset! seen-coeffects coeffects) {}))
      (rf/dispatch-sync [:evt-conf/flat-delivery]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (let [coeffects @seen-coeffects]
        (is (= 1781078400123 (:rf/time-ms coeffects))
            "the declared recordable fact supplied on the causal token arrived FLAT under its id (:rf/time-ms), not nested")
        (is (not (contains? coeffects :rf.world/inputs))
            "the live coeffects carry NO `:rf.world/inputs` key (no nested envelope)")
        (is (not (contains? coeffects :cofx))
            "the live coeffects carry NO nested `:cofx` successor (flat delivery only)")
        (is (= 1781078400123 (get (:rf.cofx coeffects) :rf/time-ms))
            "the canonical complete record under `:rf.cofx` is the FLAT recordable map (fact-name → value)")))))

(deftest supplied-recordable-non-edn-value-is-cofx-value-invalid
  (testing "a host value cannot cross the durable recordable-coeffect boundary"
    (let [handler-ran? (atom false)]
      (rf/reg-cofx :evt-conf/host-fact {:recordable? true :provided? true})
      (rf/reg-event :evt-conf/reads-host-fact
        {:rf.cofx/requires [:evt-conf/host-fact]}
        (fn [_ _] (reset! handler-ran? true) {}))
      ;; Supply a HOST HANDLE (a function — never recordable EDN) as the value.
      (let [caught-error-id (thrown-error-id
                              #(rf/dispatch-sync
                                 [:evt-conf/reads-host-fact]
                                 {:rf.cofx {:evt-conf/host-fact
                                            (fn [] :a-host-handle)}}))]
        (is (= :rf.error/cofx-value-invalid caught-error-id)
            "a supplied non-EDN recordable value is :rf.error/cofx-value-invalid")
        (is (false? @handler-ran?)
            "the handler never ran — the boundary structural-EDN check halts before the handler")))))

(deftest supplied-recordable-value-failing-schema-is-cofx-value-invalid-in-production
  (testing "recordable-coeffect schemas are enforced at the durable boundary"
    ;; Exercise the schema hook without taking a dependency on the schemas artefact.
    (let [previous-validator (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
          previous-explainer (rf.late-bind/get-fn :schemas/explain-with-registered-fn)]
      (try
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
          (fn [schema value]
            ;; schema shape `[:enum a b …]` → membership check.
            (and (vector? schema)
                 (= :enum (first schema))
                 (contains? (set (rest schema)) value))))
        (rf.late-bind/set-fn! :schemas/explain-with-registered-fn
          (fn [schema value] {:schema schema :value value :failed true}))
        (let [delivered-fact (atom ::unset)]
          (rf/reg-cofx :evt-conf/graded-fact
            {:recordable? true :provided? true
             :schema [:enum :allowed-a :allowed-b]})
          (rf/reg-event :evt-conf/reads-graded-fact
            {:rf.cofx/requires [:evt-conf/graded-fact]}
            (fn [{:keys [evt-conf/graded-fact]} _]
              (reset! delivered-fact graded-fact)
              {}))
          ;; A value the validator REJECTS → :rf.error/cofx-value-invalid.
          (is (= :rf.error/cofx-value-invalid
                 (thrown-error-id
                   #(rf/dispatch-sync [:evt-conf/reads-graded-fact]
                                      {:rf.cofx {:evt-conf/graded-fact :not-allowed}})))
              "a supplied recordable value failing its :schema is :rf.error/cofx-value-invalid")
          ;; A value the validator ACCEPTS → delivered flat (validation is a pass).
          (rf/dispatch-sync [:evt-conf/reads-graded-fact]
                            {:rf.cofx {:evt-conf/graded-fact :allowed-a}})
          (is (= :allowed-a @delivered-fact)
              "a supplied recordable value satisfying its :schema is delivered flat (validation passes)"))
        (finally
          ;; Restore the prior hooks (nil = no validator) so no leak.
          (if previous-validator
            (rf.late-bind/set-fn! :schemas/validate-with-registered-fn previous-validator)
            (swap! rf.late-bind/hooks dissoc :schemas/validate-with-registered-fn))
          (if previous-explainer
            (rf.late-bind/set-fn! :schemas/explain-with-registered-fn previous-explainer)
            (swap! rf.late-bind/hooks dissoc :schemas/explain-with-registered-fn))
          (rf.late-bind/invalidate-cache! :schemas/validate-with-registered-fn)
          (rf.late-bind/invalidate-cache! :schemas/explain-with-registered-fn))))))

;; Closed effect-map contract.

(deftest reg-event-foreign-top-level-key-is-effect-map-shape
  (testing "a foreign top-level effect key is reported and refuses the event"
    (let [traces       (atom [])
          handler-ran? (atom false)]
      (rf/register-listener! :trace :evt-conf/shape-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      ;; A sentinel the foreign shortcut would dispatch IF the runtime
      ;; wrongly honoured the legacy top-level `:dispatch` — it must NOT.
      (rf/reg-event :evt-conf/shortcut-target
        (fn [_ _] (reset! handler-ran? true) {}))
      ;; `:dispatch` at the TOP LEVEL is the canonical v1 legacy shortcut — it
      ;; must be lowered through `{:fx [[:dispatch …]]}`, so the bare top-level
      ;; key is a foreign-key shape error, not a silently-honoured shortcut.
      (rf/reg-event :evt-conf/legacy-shortcut
        (fn [_ _] {:dispatch [:evt-conf/shortcut-target]}))
      (rf/dispatch-sync [:evt-conf/legacy-shortcut])
      (rf/unregister-listener! :trace :evt-conf/shape-recorder)
      (is (false? @handler-ran?)
          "the legacy top-level `:dispatch` was NOT silently honoured")
      (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %)) @traces)]
        (is (seq shape-traces)
            "a foreign / legacy top-level effect key emits :rf.error/effect-map-shape")
        (is (= :dispatch (get-in (first shape-traces) [:tags :offending-key]))
            "the diagnostic names the offending legacy top-level key")
        (is (= :fix-effect (:recovery (first shape-traces)))
            "the envelope violation REFUSES the event pre-commit")))))

(deftest reg-event-bare-app-db-shaped-return-is-effect-map-shape-not-committed
  (testing "a bare app-db map is not mistaken for the explicit :db effect"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/bare-count (fn [db _] (:count db :absent)))
      ;; Seed app-db with a sentinel so a wrongly-committed bare return is
      ;; observable (the bare `{:count 1}` would clobber `:count :seeded`).
      (rf/reg-event :evt-conf/bare-seed! (fn [{:keys [db]} _] {:db (assoc db :count :seeded)}))
      ;; A handler returning a BARE app-db-shaped map — `:count` is a FOREIGN
      ;; top-level effect key, NOT the `{:db …}` write effect.
      (rf/reg-event :evt-conf/bare-db-return (fn [_ _] {:count 1}))
      (rf/dispatch-sync [:evt-conf/bare-seed!])
      (rf/register-listener! :trace :evt-conf/bare-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/bare-db-return])
      (rf/unregister-listener! :trace :evt-conf/bare-recorder)
      (is (= :seeded @(rf/subscribe [:evt-conf/bare-count]))
          "the bare `{:count 1}` return was NOT committed as app-db (there is no db-return convenience)")
      (let [shape-traces (filter #(and (= :rf.error/effect-map-shape (:operation %))
                                       (= :count (get-in % [:tags :offending-key])))
                                 @traces)]
        (is (seq shape-traces)
            "the bare app-db-shaped return's foreign app key emits :rf.error/effect-map-shape naming :count")
        (is (= :fix-effect (:recovery (first shape-traces)))
            "the bare-db-return shape diagnostic REFUSES the event")))))

(deftest reg-event-app-handler-runtime-effect-keeps-the-diagnostic-unless-framework-authority
  (testing "runtime-db writes warn for app handlers but not framework-authorised handlers"
    (let [app-traces       (atom [])
          framework-traces (atom [])]
      ;; Leg 1 — an APP handler (no authority) returning :rf.db/runtime fires
      ;; the diagnostic.
      (rf/reg-event :evt-conf/app-writes-runtime
        (fn [_ _] {:rf.db/runtime {:rf.runtime/marker :app-wrote}}))
      (rf/register-listener! :trace :evt-conf/app-runtime-recorder
        (fn [trace-event] (swap! app-traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/app-writes-runtime])
      (rf/unregister-listener! :trace :evt-conf/app-runtime-recorder)
      (let [warning-traces (filter #(= :rf.warning/app-handler-runtime-effect (:operation %)) @app-traces)]
        (is (seq warning-traces)
            "an APP handler returning :rf.db/runtime keeps the :rf.warning/app-handler-runtime-effect diagnostic")
        (is (= :warned (:recovery (first warning-traces)))
            "the diagnostic carries :recovery :warned (the effect is still applied — convention, not enforcement)"))
      ;; Leg 2 — a FRAMEWORK-AUTHORITY handler (the reserved
      ;; `:rf/framework-authority? true` registration meta) writes :rf.db/runtime
      ;; WITHOUT the diagnostic.
      (rf/reg-event :evt-conf/fw-writes-runtime
        {:rf/framework-authority? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/marker :fw-wrote}}))
      (rf/register-listener! :trace :evt-conf/fw-runtime-recorder
        (fn [trace-event] (swap! framework-traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/fw-writes-runtime])
      (rf/unregister-listener! :trace :evt-conf/fw-runtime-recorder)
      (is (empty? (filter #(= :rf.warning/app-handler-runtime-effect (:operation %))
                          @framework-traces))
          "a FRAMEWORK-AUTHORITY handler writes :rf.db/runtime silently — NO app-handler-runtime-effect diagnostic"))))

(deftest reg-event-unchanged-db-return-is-a-true-noop
  (testing "returning the identical db emits db-noop rather than db-changed"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/noop-seed (fn [db _] (:seed db :untouched)))
      (rf/reg-event :evt-conf/noop-seed! (fn [{:keys [db]} _] {:db (assoc db :seed :set)}))
      ;; A handler that returns `{:db db}` — the SAME value it was handed, the
      ;; canonical unchanged-db return.
      (rf/reg-event :evt-conf/noop-rewrite (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:evt-conf/noop-seed!])
      ;; Listen only across the no-op dispatch so the seed write's db-changed
      ;; does not pollute the assertion.
      (rf/register-listener! :trace :evt-conf/noop-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/noop-rewrite])
      (rf/unregister-listener! :trace :evt-conf/noop-recorder)
      (is (= :set @(rf/subscribe [:evt-conf/noop-seed]))
          "the `{:db db}` rewrite left app-db at its prior value (a true no-op)")
      (let [operations (set (map :operation @traces))]
        (is (contains? operations :rf.event/db-noop)
            "an unchanged `{:db db}` return fires :rf.event/db-noop")
        (is (not (contains? operations :rf.event/db-changed))
            "an unchanged `{:db db}` return does NOT fire :rf.event/db-changed")))))

(deftest reg-event-db-nil-return-is-coerced-to-empty-map-with-diagnostic
  (testing "a nil db effect becomes an empty map and emits its diagnostic"
    (let [traces  (atom [])
          seen-db (atom ::unset)]
      ;; Seed a non-trivial app-db so the coercion-to-`{}` is observable.
      (rf/reg-event :evt-conf/nil-seed! (fn [{:keys [db]} _] {:db (assoc db :k :v)}))
      (rf/reg-event :evt-conf/return-nil-db (fn [_ _] {:db nil}))
      ;; A follow-up handler reads app-db so we can prove it is `{}` (a map),
      ;; never nil — `(:k db)` is absent and `(map? db)` holds.
      (rf/reg-event :evt-conf/inspect-db
        (fn [{:keys [db]} _] (reset! seen-db db) {}))
      (rf/dispatch-sync [:evt-conf/nil-seed!])
      (rf/register-listener! :trace :evt-conf/nil-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/return-nil-db])
      (rf/unregister-listener! :trace :evt-conf/nil-recorder)
      (rf/dispatch-sync [:evt-conf/inspect-db])
      (is (map? @seen-db)
          "app-db after a `{:db nil}` return is a MAP, never nil")
      (is (not (contains? @seen-db :k))
          "the `{:db nil}` return coerced app-db to an EMPTY map (the prior :k is gone)")
      (let [coerce-traces (filter #(= :rf.warning/db-nil-coerced (:operation %)) @traces)]
        (is (seq coerce-traces)
            "a `{:db nil}` return emits the :rf.warning/db-nil-coerced diagnostic")
        (is (= :warned (:recovery (first coerce-traces)))
            "the coercion diagnostic carries :recovery :warned (the value is still applied)")))))

(deftest reg-event-deliberate-empty-db-clear-emits-no-diagnostic
  (testing "an explicit empty db clears state without the nil-coercion diagnostic"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/clear-mark (fn [db _] (:mark db :untouched)))
      (rf/reg-event :evt-conf/clear-seed! (fn [{:keys [db]} _] {:db (assoc db :mark :set)}))
      (rf/reg-event :evt-conf/clear-db    (fn [_ _] {:db {}}))
      (rf/dispatch-sync [:evt-conf/clear-seed!])
      (rf/register-listener! :trace :evt-conf/clear-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/clear-db])
      (rf/unregister-listener! :trace :evt-conf/clear-recorder)
      (is (= :untouched @(rf/subscribe [:evt-conf/clear-mark]))
          "the deliberate `{:db {}}` clear emptied app-db (the prior :mark is gone)")
      (is (empty? (filter #(= :rf.warning/db-nil-coerced (:operation %)) @traces))
          "a deliberate `{:db {}}` clear emits NO :rf.warning/db-nil-coerced diagnostic"))))

(deftest reg-event-malformed-fx-value-refuses-the-event-without-throwing
  (testing "a malformed :fx value refuses the event in-band, never a raw host throw"
    (let [traces        (atom [])
          sentinel-ran? (atom false)]
      (rf/reg-sub :evt-conf/fx-shape-db (fn [db _] (:committed db :absent)))
      ;; A sentinel the malformed `:fx` would dispatch IF the map-shaped value
      ;; were wrongly walked as a pair — it must NOT run.
      (rf/reg-event :evt-conf/fx-shape-sentinel
        (fn [_ _] (reset! sentinel-ran? true) {}))
      (rf/reg-event :evt-conf/malformed-fx
        (fn [{:keys [db]} _]
          ;; `:db` is a well-formed sibling; `:fx` is a non-sequential MAP
          ;; (the documented forgot-the-outer-vector typo).
          {:db (assoc db :committed :yes)
           :fx {:dispatch [:evt-conf/fx-shape-sentinel]}}))
      (rf/register-listener! :trace :evt-conf/fx-shape-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/malformed-fx])
      (rf/unregister-listener! :trace :evt-conf/fx-shape-recorder)
      (is (= :absent @(rf/subscribe [:evt-conf/fx-shape-db]))
          "the sibling `:db` write did NOT commit — a malformed `:fx` refuses the whole event (no partial commit)")
      (is (false? @sentinel-ran?)
          "the non-sequential `:fx` value was never walked as a pair (sentinel never dispatched)")
      (let [shape-traces (filter #(and (= :rf.error/effect-map-shape (:operation %))
                                       (= :fx (get-in % [:tags :offending-key])))
                                 @traces)]
        (is (seq shape-traces)
            "a non-sequential `:fx` value emits :rf.error/effect-map-shape naming :fx")
        (is (= :fix-effect (:recovery (first shape-traces)))
            "the malformed-:fx diagnostic carries :recovery :fix-effect")))))

(deftest reg-event-final-effects-boundary-refuses-after-interceptor-foreign-key
  (testing "the final effects boundary refuses a foreign key injected by an after interceptor"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/boundary-db (fn [db _] (:committed db :absent)))
      (rf/reg-interceptor :evt-conf/inject-foreign
        {:after (fn [ctx]
                  ;; Inject a FOREIGN top-level effect key into the final
                  ;; effects map AFTER the handler already returned — the
                  ;; SECOND route into the one boundary that decides.
                  (assoc-in ctx [:effects :evt-conf/foreign] :should-be-dropped))})
      (rf/reg-event :evt-conf/handler-with-after
        {:interceptors [:evt-conf/inject-foreign]}
        (fn [{:keys [db]} _] {:db (assoc db :committed :yes)}))
      (rf/register-listener! :trace :evt-conf/boundary-recorder
        (fn [trace-event] (swap! traces conj trace-event)))
      (rf/dispatch-sync [:evt-conf/handler-with-after])
      (rf/unregister-listener! :trace :evt-conf/boundary-recorder)
      (is (= :absent @(rf/subscribe [:evt-conf/boundary-db]))
          "the handler's well-formed `:db` write did NOT commit — an :after-injected foreign key gets the SAME verdict as a handler-returned one")
      (let [shape-traces (filter #(and (= :rf.error/effect-map-shape (:operation %))
                                       (= :evt-conf/foreign (get-in % [:tags :offending-key])))
                                 @traces)]
        (is (seq shape-traces)
            "an :after-interceptor-injected foreign key is refused at the FINAL boundary")
        (is (= :fix-effect (:recovery (first shape-traces)))
            "the final-boundary diagnostic carries :recovery :fix-effect")))))

;; Registration shape and interceptor chains.

(deftest reg-event-registers-under-event-kind-with-the-one-wrapper
  (testing "reg-event registers under :event with one default wrapper"
    (rf/reg-event :evt-conf/shape (fn [{:keys [db]} _] {:db (assoc db :m :v)}))
    (let [event-metadata (rf/handler-meta {:source :store :kind :event :id :evt-conf/shape})]
      (is (some? event-metadata)
          "reg-event registers under registry kind :event (handler-meta finds it)")
      (is (fn? (:handler-fn event-metadata))
          "handler-meta surfaces the registered handler-fn")
      (is (not (contains? event-metadata :event/kind))
          "there is no `:event/kind` sub-tag (one form, no kind discriminator)")
      ;; THE wrapper lock — exactly ONE framework wrapper, named :rf/event-handler.
      ;; This EXACT-VECTOR equality is also what excludes any per-kind wrapper
      ;; id (`:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`):
      ;; a singleton `[:rf/event-handler]` cannot contain any of them, and it
      ;; refuses an extra wrapper and a wrong order too. A second registration
      ;; asserting those three non-memberships would restate a consequence of
      ;; this line rather than exercise another path — every `reg-event` takes
      ;; the one form.
      (is (= [:rf/event-handler] (mapv :id (:interceptors event-metadata)))
          "the ONLY framework wrapper is the single :rf/event-handler interceptor")
      (is (true? (:rf/default? (first (:interceptors event-metadata))))
          "the wrapper carries :rf/default? true (filtered as a framework auto-wrapper)"))))

(deftest reg-event-chain-references-an-interceptor-from-public-reg-interceptor
  (testing "an event chain resolves an interceptor registered through the public facade"
    (let [ran? (atom false)]
      (rf/reg-sub :evt-conf/public-icpt-marker (fn [db _] (:public-icpt-marker db)))
      (rf/reg-interceptor :evt-conf/audit
        {:doc "an application audit interceptor authored via reg-interceptor"}
        {:before (fn [ctx] (reset! ran? true) ctx)})
      (let [interceptor-metadata (rf/handler-meta {:source :store :kind :interceptor :id :evt-conf/audit})]
        (is (some? interceptor-metadata)
            "reg-interceptor registers under the :interceptor kind (handler-meta finds it)")
        (is (= "an application audit interceptor authored via reg-interceptor"
               (:doc interceptor-metadata))
            "the registration metadata (:doc) is retained on the :interceptor entry")
        (is (contains? interceptor-metadata :rf/interceptor-descriptor)
            "handler-meta surfaces the registered :rf/interceptor-descriptor (the public path)")
        (is (fn? (:before (:rf/interceptor-descriptor interceptor-metadata)))
            "the descriptor carries the authored :before slot"))
      ;; The event chain references the public-registered interceptor by ID —
      ;; reference-only (an inline value would be :rf.error/inline-interceptor-removed).
      (rf/reg-event :evt-conf/uses-public-icpt
        {:interceptors [:evt-conf/audit]}
        (fn [{:keys [db]} _] {:db (assoc db :public-icpt-marker :handler-ran)}))
      (let [event-metadata (rf/handler-meta {:source :store :kind :event :id :evt-conf/uses-public-icpt})]
        (is (= [:evt-conf/audit :rf/event-handler]
               (interceptor-chain-ids (:interceptors event-metadata)))
            "the event chain carries the interceptor REFERENCE (id), before the framework wrapper"))
      (rf/dispatch-sync [:evt-conf/uses-public-icpt])
      (is (true? @ran?)
          "the public-registered interceptor's :before ran in the event chain")
      (is (= :handler-ran @(rf/subscribe [:evt-conf/public-icpt-marker]))
          "the event handler ran after the public-registered interceptor"))))

;; Retired registration-form tombstones.

(deftest retired-forms-fan-out-throw-and-register-nothing
  (testing "each retired form reaches the always-on channel, throws its removal
            error, and registers nothing"
    (let [seen-errors (atom [])]
      (rf.error-emit/register-error-listener! :evt-conf/removal-recorder
        (fn [error-record] (swap! seen-errors conj (:error error-record))))
      (doseq [[form-name retired-form error-id event-id]
              [["reg-event-db"  rf/reg-event-db  :rf.error/reg-event-db-removed  :evt-conf/db-noreg]
               ["reg-event-fx"  rf/reg-event-fx  :rf.error/reg-event-fx-removed  :evt-conf/fx-noreg]
               ["reg-event-ctx" rf/reg-event-ctx :rf.error/reg-event-ctx-removed :evt-conf/ctx-noreg]]]
        (reset! seen-errors [])
        ;; The call throws; the listener must already have received the record.
        (is (= error-id (thrown-error-id #(retired-form event-id (fn [_ _] nil))))
            (str form-name " throws " error-id))
        (is (some #{error-id} @seen-errors)
            (str form-name " fans its removal error out on the always-on channel before the throw"))
        (is (nil? (rf.registrar/lookup :event event-id))
            (str form-name " registered nothing"))))))

;; Always-on event channel.

(deftest handled-event-fans-out-on-the-always-on-events-channel
  (testing "a handled event emits one record on the always-on event channel"
    (let [seen-events (atom [])]
      (rf.event-emit/register-event-listener! :evt-conf/handled-recorder
        (fn [event-record] (swap! seen-events conj event-record)))
      (rf/make-frame {:id :evt-conf/events-main})
      (rf/reg-event :evt-conf/events-probe
        {:frame :evt-conf/events-main}
        (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
      (rf/dispatch-sync [:evt-conf/events-probe] {:frame :evt-conf/events-main})
      (is (= 1 (count @seen-events))
          "exactly one always-on event-emit record per processed event")
      (let [event-record (first @seen-events)]
        (is (= :evt-conf/events-probe (:event-id event-record))
            "the record names the processed event")
        (is (= :evt-conf/events-main (:frame event-record))
            "the record carries the resolved frame-id (fanned across EVERY frame)")
        (is (= :ok (:outcome event-record))
            "a clean settle reports :ok on the always-on channel")
        (is (contains? event-record :elapsed-ms)
            "the tight record carries the wall-clock :elapsed-ms slot (Spec 009 §Record shape)"))
      (rf.event-emit/unregister-event-listener! :evt-conf/handled-recorder))))
