(ns re-frame.event-model-conformance-cljs-test
  "Adversarial conformance for the public one-form event model.

  Core's focused suites own individual `reg-event` and observability behaviour.
  This cross-artefact suite locks the boundaries that are easiest to weaken while
  those tests remain green: handlers receive coeffects and return a closed effect
  map; recordable coeffects obey their host-read policy; registrations have one
  `:rf/event-handler` wrapper and no `:event/kind`; and the `^:no-doc` retired
  forms throw, register nothing, and notify the always-on error channel.

  The retired-form probes are deliberately fail-closed. A form that becomes
  callable again returns `:no-throw`, and registration is checked independently,
  so silently reviving an old API cannot satisfy the suite. Frame-targeted lookup
  belongs to `re-frame.facade-frame-read-cljs-test`; live frame dispatch belongs
  to the sibling `re-frame.event-frame-isolation-conformance-cljs-test`.

  The fixture supplies an ambient `:rf/default` frame and clears the always-on
  listener registries between cases. These `.cljc` tests run on both JVM and
  CLJS; the JVM arm also verifies facade var metadata."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.error-emit :as error-emit]
            [re-frame.event-emit :as event-emit]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(defn- clear-observation-state! []
  (error-emit/clear-error-listeners!)
  (event-emit/clear-event-listeners!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (clear-observation-state!)
    (test-fn)
    (clear-observation-state!)))

(defn- thrown-error-id
  "Call `f` and return the `:rf.error/id` of the ExceptionInfo it raises, or
  `:no-throw` if it did not throw."
  [f]
  (try
    (f)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (:rf.error/id (ex-data e)))))

(defn- thrown-error-reason
  "Call `f` and return the `:reason` text of the ExceptionInfo it raises, or
  `:no-throw` if it did not throw."
  [f]
  (try
    (f)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (:reason (ex-data e)))))

(defn- chain-ids
  "Return ids from a stored chain without resolving its interceptor references."
  [chain]
  (mapv (fn [entry]
          (cond
            (keyword? entry)                          entry
            (and (vector? entry) (keyword? (first entry))) (first entry)
            (map? entry)                              (:id entry)
            :else                                     entry))
        chain))

;; One-form handler and coeffect contract.

(deftest reg-event-has-reg-event-fx-semantics-coeffects-in-effects-out
  (testing "reg-event receives coeffects and returns an explicit effects map"
    (let [seen-cofx (atom ::unset)]
      (rf/reg-sub :evt-conf/count (fn [db _] (:count db 0)))
      (rf/reg-event :evt-conf/bump
        (fn [coeffects _]
          (reset! seen-cofx coeffects)
          {:db (update (:db coeffects) :count (fnil inc 0))}))
      (rf/dispatch-sync [:evt-conf/bump])
      (rf/dispatch-sync [:evt-conf/bump])
      (is (map? @seen-cofx)
          "the handler is handed the coeffects MAP, not a bare db value")
      (is (contains? @seen-cofx :db)
          "`:db` is delivered IN the coeffects map (coeffects-in)")
      (is (= 2 @(rf/subscribe [:evt-conf/count]))
          "the `{:db …}` effect committed cumulatively (the db-write IS an effect)"))))

(deftest reg-event-handler-receives-the-canonical-coeffect-keys
  (testing "handlers receive the canonical coeffect keys without an event-kind tag"
    (let [cofx (atom ::unset)]
      (rf/reg-event :evt-conf/inspect-cofx
        (fn [coeffects _] (reset! cofx coeffects) {}))
      (rf/dispatch-sync [:evt-conf/inspect-cofx :payload])
      (let [c @cofx]
        (is (contains? c :db)           "`:db` present in the coeffects map")
        (is (contains? c :event)        "`:event` present in the coeffects map")
        (is (= [:evt-conf/inspect-cofx :payload] (:event c))
            "`:event` is the dispatched event vector (== the 2nd handler arg)")
        (is (contains? c :rf.frame/id)  "`:rf.frame/id` present in the coeffects map")
        (is (= :rf/default (:rf.frame/id c))
            "the ambient frame id is delivered as `:rf.frame/id`")
        (is (contains? c :rf.db/runtime) "`:rf.db/runtime` present in the coeffects map")
        (is (contains? c :rf.cofx)
            "the canonical complete `:rf.cofx` record is reachable in the coeffects map")
        (is (map? (:rf.cofx c))
            "`:rf.cofx` is the FLAT recordable-coeffect map (fact-name → value, no grouping sub-maps)")
        (is (not (contains? c :rf.world/inputs))
            "the unsupported `:rf.world/inputs` key is absent from the coeffects baseline")
        (is (not (contains? c :event/kind))
            "the `:event/kind` sub-tag is GONE from the coeffects map (one form)")))))

(deftest dispatch-opt-rf-world-inputs-is-the-generic-unknown-opt-with-did-you-mean
  (testing "the unsupported :rf.world/inputs opt warns and suggests :rf.cofx"
    (rf/reg-event :evt-conf/inspect-renamed (fn [{:keys [db]} _] {:db (assoc db :evt-conf/ran true)}))
    (let [traces (atom [])]
      (rf/register-listener! :trace :evt-conf/renamed-recorder (fn [ev] (swap! traces conj ev)))
      ;; Unknown dispatch opts are observational warnings, not hard errors.
      (rf/dispatch-sync [:evt-conf/inspect-renamed]
                        {:rf.world/inputs {:rf/time-ms 1781078400123}})
      (rf/unregister-listener! :trace :evt-conf/renamed-recorder)
      (let [warns (filterv (fn [ev]
                             (and (= :warning (:op-type ev))
                                  (= :rf.warning/unknown-dispatch-opt (:operation ev))))
                           @traces)]
        (is (= 1 (count warns))
            "the retired draft key trips the generic unknown-dispatch-opt warning (not a dedicated error)")
        (let [t (:tags (first warns))]
          (is (contains? (set (:unknown-keys t)) :rf.world/inputs)
              "the retired key is named as an unrecognised opt")
          (is (re-find #":rf.cofx" (:reason t))
              "the warning message appends a did-you-mean naming `:rf.cofx` as the replacement"))))))

(deftest live-event-coeffects-carry-no-rf-world-inputs-flat-delivery
  (testing "recordable facts and the canonical :rf.cofx record are flat"
    (let [c (atom ::unset)]
      (rf/reg-event :evt-conf/flat-delivery
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [coeffects _] (reset! c coeffects) {}))
      (rf/dispatch-sync [:evt-conf/flat-delivery]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (let [cofx @c]
        (is (= 1781078400123 (:rf/time-ms cofx))
            "the declared recordable fact arrived FLAT under its id (:rf/time-ms), not nested")
        (is (not (contains? cofx :rf.world/inputs))
            "the live coeffects carry NO `:rf.world/inputs` key (the retired nested envelope is gone)")
        (is (not (contains? cofx :cofx))
            "the live coeffects carry NO nested `:cofx` successor (flat delivery only)")
        (is (= 1781078400123 (get (:rf.cofx cofx) :rf/time-ms))
            "the canonical complete record under `:rf.cofx` is the FLAT recordable map (fact-name → value)")))))

(deftest reg-event-second-arg-is-the-event-vector
  (testing "the second handler argument is also available as :event in coeffects"
    (let [arg-event   (atom ::unset)
          cofx-event  (atom ::unset)]
      (rf/reg-event :evt-conf/two-arg
        (fn [coeffects event]
          (reset! arg-event event)
          (reset! cofx-event (:event coeffects))
          {}))
      (rf/dispatch-sync [:evt-conf/two-arg :a :b])
      (is (= [:evt-conf/two-arg :a :b] @arg-event)
          "the 2nd positional arg is the full event vector")
      (is (= @arg-event @cofx-event)
          "`(:event coeffects)` is the SAME value as the positional event arg"))))

(deftest reg-event-rf-cofx-requires-one-arg-ambient-supplier-arg-path
  (testing "a [cofx-id arg] requirement passes arg to an ambient supplier"
    (let [seen (atom ::unset)]
      ;; The call-site-parameterized supplier is one-arg `(fn [arg] value)`
      ;; (cofx.cljc §Supplier signatures), declared `[id arg]` in :rf.cofx/requires.
      (rf/reg-cofx :evt-conf/echo (fn [arg] arg))
      (rf/reg-event :evt-conf/read-echo
        {:rf.cofx/requires [[:evt-conf/echo :hello]]}
        (fn [{:keys [evt-conf/echo]} _] (reset! seen echo) {}))
      (rf/dispatch-sync [:evt-conf/read-echo])
      (is (= :hello @seen)
          "the generator arg was threaded and the result delivered flat"))))

(deftest reg-event-recordable-generator-mints-under-live-and-writes-back-to-the-record
  (testing "live policy mints a missing recordable fact and writes it to the causal record"
    (let [delivered (atom ::unset)
          recorded  (atom ::unset)
          calls     (atom 0)]
      ;; A monotonic supplier makes an accidental second host read observable.
      (rf/reg-cofx :evt-conf/mint-id
        {:recordable? true}
        (fn [] (swap! calls inc) (str "id-" @calls)))
      (rf/reg-event :evt-conf/uses-mint
        {:rf.cofx/requires [:evt-conf/mint-id]}
        (fn [{:keys [evt-conf/mint-id] :as cofx} _]
          (reset! delivered mint-id)
          (reset! recorded (get (:rf.cofx cofx) :evt-conf/mint-id))
          {}))
      (rf/dispatch-sync [:evt-conf/uses-mint])
      (is (= "id-1" @delivered)
          "the recordable generator minted the absent fact under :live and delivered it flat")
      (is (= "id-1" @recorded)
          "the generated value was WRITTEN BACK into the in-flight :rf.cofx record (the post-generation token the epoch captures)")
      (is (= 1 @calls)
          "the generator ran exactly once (the write-back, not a re-read, supplies the record)"))))

(deftest reg-event-recordable-generator-emits-rf-cofx-generated-trace-op
  (testing "recordable generation emits a self-describing :rf.cofx/generated trace"
    (let [traces (atom [])]
      (rf/reg-cofx :evt-conf/gen-fact
        {:recordable? true}
        (fn [] :minted-value))
      (rf/reg-event :evt-conf/triggers-gen
        {:rf.cofx/requires [:evt-conf/gen-fact]}
        (fn [_ _] {}))
      (rf/register-listener! :trace :evt-conf/gen-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/triggers-gen])
      (rf/unregister-listener! :trace :evt-conf/gen-recorder)
      (let [gen-traces (filter #(= :rf.cofx/generated (:operation %)) @traces)]
        (is (seq gen-traces)
            "the generation step emits the :rf.cofx/generated trace op")
        (is (= :evt-conf/gen-fact (get-in (first gen-traces) [:tags :rf.cofx/id]))
            ":rf.cofx/id names the generated fact")
        (is (= :minted-value (get-in (first gen-traces) [:tags :rf.cofx/value]))
            "the op carries the produced value (fact-name + value, self-describing)")))))

(deftest reg-event-strict-mint-policy-refuses-to-generate-and-raises-missing-required
  (testing "strict policy rejects a missing recordable fact without reading the host"
    (let [calls  (atom 0)
          fired? (atom false)]
      (rf/reg-cofx :evt-conf/strict-fact
        {:recordable? true}
        (fn [] (swap! calls inc) :should-not-mint))
      (rf/reg-event :evt-conf/needs-strict-fact
        {:rf.cofx/requires [:evt-conf/strict-fact]}
        (fn [_ _] (reset! fired? true) {}))
      (let [thrown (thrown-error-id
                     #(rf/dispatch-sync [:evt-conf/needs-strict-fact]
                                        {:rf.cofx/mint-policy :strict}))]
        (is (= :rf.error/missing-required-cofx thrown)
            "a declared-absent generator-backed fact under :strict is :rf.error/missing-required-cofx (no mint, no host read)")
        (is (zero? @calls)
            "the generator NEVER ran under :strict (no silent host read on replay)")
        (is (false? @fired?)
            "the handler never ran — missing-required halts the cascade before the handler")))))

(deftest reg-event-explicit-live-mint-policy-mints-the-declared-nondeterminism-escape
  (testing "explicit-live policy opts into minting a missing recordable fact"
    (let [delivered (atom ::unset)]
      (rf/reg-cofx :evt-conf/escape-fact
        {:recordable? true}
        (fn [] :minted-under-escape))
      (rf/reg-event :evt-conf/uses-escape-fact
        {:rf.cofx/requires [:evt-conf/escape-fact]}
        (fn [{:keys [evt-conf/escape-fact]} _] (reset! delivered escape-fact) {}))
      (rf/dispatch-sync [:evt-conf/uses-escape-fact]
                        {:rf.cofx/mint-policy :explicit-live})
      (is (= :minted-under-escape @delivered)
          ":explicit-live mints a declared-absent generator-backed fact (the declared-nondeterminism escape, NOT strict)"))))

(deftest reg-event-typo-cofx-is-the-hard-error
  (testing "requiring an unregistered coeffect is a hard error"
    (rf/reg-event :evt-conf/bad-requires
      {:rf.cofx/requires [:evt-conf/never-registered]}
      (fn [_ _] {}))
    (is (= :rf.error/unregistered-cofx
           (thrown-error-id #(rf/dispatch-sync [:evt-conf/bad-requires])))
        "an unregistered declared cofx raises :rf.error/unregistered-cofx")))

(deftest reg-event-malformed-requires-is-cofx-request-invalid
  (testing "malformed :rf.cofx/requires declarations fail at registration"
    ;; A non-vector :rf.cofx/requires value.
    (is (= :rf.error/cofx-request-invalid
           (thrown-error-id
             #(rf/reg-event :evt-conf/requires-not-vector
                {:rf.cofx/requires :evt-conf/not-a-vector}
                (fn [_ _] {}))))
        "a non-vector :rf.cofx/requires is :rf.error/cofx-request-invalid at registration")
    ;; A vector carrying a non-id (non-keyword, non-`[id arg]`) entry.
    (is (= :rf.error/cofx-request-invalid
           (thrown-error-id
             #(rf/reg-event :evt-conf/requires-bad-entry
                {:rf.cofx/requires [42]}
                (fn [_ _] {}))))
        "a non-id entry in :rf.cofx/requires is :rf.error/cofx-request-invalid at registration")))

(deftest reg-cofx-malformed-grade-metadata-is-cofx-registration-invalid
  (testing "contradictory coeffect grades fail at registration"
    ;; (1) :provided? without :recordable?
    (is (= :rf.error/cofx-registration-invalid
           (thrown-error-id
             #(rf/reg-cofx :evt-conf/provided-not-recordable {:provided? true})))
        ":provided? without :recordable? is :rf.error/cofx-registration-invalid")
    ;; (2) :provided? WITH a supplier (the silently-ignored contradiction).
    (is (= :rf.error/cofx-registration-invalid
           (thrown-error-id
             #(rf/reg-cofx :evt-conf/provided-with-supplier
                {:recordable? true :provided? true}
                (fn [] :ignored))))
        "a :provided? fact carrying a supplier is :rf.error/cofx-registration-invalid")
    ;; (3) ambient (non-provided) fact with NO supplier.
    (is (= :rf.error/cofx-registration-invalid
           (thrown-error-id
             #(rf/reg-cofx :evt-conf/ambient-no-supplier {:doc "no supplier"})))
        "an ambient fact with no supplier is :rf.error/cofx-registration-invalid")))

(deftest supplied-recordable-non-edn-value-is-cofx-value-invalid
  (testing "a host value cannot cross the durable recordable-coeffect boundary"
    (let [fired? (atom false)]
      (rf/reg-cofx :evt-conf/host-fact {:recordable? true :provided? true})
      (rf/reg-event :evt-conf/reads-host-fact
        {:rf.cofx/requires [:evt-conf/host-fact]}
        (fn [_ _] (reset! fired? true) {}))
      ;; Supply a HOST HANDLE (a function — never recordable EDN) as the value.
      (let [thrown (thrown-error-id
                     #(rf/dispatch-sync [:evt-conf/reads-host-fact]
                                        {:rf.cofx {:evt-conf/host-fact (fn [] :a-host-handle)}}))]
        (is (= :rf.error/cofx-value-invalid thrown)
            "a supplied non-EDN recordable value is :rf.error/cofx-value-invalid")
        (is (false? @fired?)
            "the handler never ran — the boundary structural-EDN check halts before the handler")))))

(deftest supplied-recordable-value-failing-schema-is-cofx-value-invalid-in-production
  (testing "recordable-coeffect schemas are enforced at the durable boundary"
    ;; Exercise the schema hook without taking a dependency on the schemas artefact.
    (let [prev-validate (late-bind/get-fn :schemas/validate-with-registered-fn)
          prev-explain  (late-bind/get-fn :schemas/explain-with-registered-fn)]
      (try
        (late-bind/set-fn! :schemas/validate-with-registered-fn
          (fn [schema value]
            ;; schema shape `[:enum a b …]` → membership check.
            (and (vector? schema)
                 (= :enum (first schema))
                 (contains? (set (rest schema)) value))))
        (late-bind/set-fn! :schemas/explain-with-registered-fn
          (fn [schema value] {:schema schema :value value :failed true}))
        (let [delivered (atom ::unset)]
          (rf/reg-cofx :evt-conf/graded-fact
            {:recordable? true :provided? true
             :schema [:enum :allowed-a :allowed-b]})
          (rf/reg-event :evt-conf/reads-graded-fact
            {:rf.cofx/requires [:evt-conf/graded-fact]}
            (fn [{:keys [evt-conf/graded-fact]} _] (reset! delivered graded-fact) {}))
          ;; A value the validator REJECTS → :rf.error/cofx-value-invalid.
          (is (= :rf.error/cofx-value-invalid
                 (thrown-error-id
                   #(rf/dispatch-sync [:evt-conf/reads-graded-fact]
                                      {:rf.cofx {:evt-conf/graded-fact :not-allowed}})))
              "a supplied recordable value failing its :schema is :rf.error/cofx-value-invalid")
          ;; A value the validator ACCEPTS → delivered flat (validation is a pass).
          (rf/dispatch-sync [:evt-conf/reads-graded-fact]
                            {:rf.cofx {:evt-conf/graded-fact :allowed-a}})
          (is (= :allowed-a @delivered)
              "a supplied recordable value satisfying its :schema is delivered flat (validation passes)"))
        (finally
          ;; Restore the prior hooks (nil = no validator) so no leak.
          (if prev-validate
            (late-bind/set-fn! :schemas/validate-with-registered-fn prev-validate)
            (swap! late-bind/hooks dissoc :schemas/validate-with-registered-fn))
          (if prev-explain
            (late-bind/set-fn! :schemas/explain-with-registered-fn prev-explain)
            (swap! late-bind/hooks dissoc :schemas/explain-with-registered-fn))
          (late-bind/invalidate-cache! :schemas/validate-with-registered-fn)
          (late-bind/invalidate-cache! :schemas/explain-with-registered-fn))))))

(deftest reg-event-declared-rf-time-ms-is-delivered-flat-from-the-token
  (testing "a declared :rf/time-ms fact is delivered flat from the causal token"
    (let [seen (atom ::unset)]
      (rf/reg-event :evt-conf/reads-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [rf/time-ms]} _] (reset! seen time-ms) {}))
      (rf/dispatch-sync [:evt-conf/reads-time]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (= 1781078400123 @seen)
          "the DECLARED :rf/time-ms arrived FLAT under its id from the causal token"))))

(deftest reg-event-registered-but-absent-provided-fact-is-missing-required-cofx
  (testing "a missing provided fact fails before the handler without a host read"
    (let [traces (atom [])
          fired? (atom false)]
      (rf/reg-cofx :evt-conf/required-boundary
        {:recordable? true :provided? true})
      (rf/reg-event :evt-conf/needs-boundary
        {:rf.cofx/requires [:evt-conf/required-boundary]}
        (fn [_ _] (reset! fired? true) {}))
      (rf/register-listener! :trace :evt-conf/missing-recorder (fn [ev] (swap! traces conj ev)))
      ;; Dispatch WITHOUT supplying the provided fact on the token.
      (let [thrown (thrown-error-id #(rf/dispatch-sync [:evt-conf/needs-boundary]))]
        (rf/unregister-listener! :trace :evt-conf/missing-recorder)
        (is (false? @fired?)
            "the handler never ran — missing-required halts the cascade before the handler")
        (is (= :rf.error/missing-required-cofx thrown)
            "a registered-but-absent provided fact raises :rf.error/missing-required-cofx")
        (let [errs (filter #(= :rf.error/missing-required-cofx (:operation %)) @traces)]
          (is (seq errs)
              "the missing-required error fanned out on the trace bus")
          (is (= :evt-conf/required-boundary (get-in (first errs) [:tags :rf.cofx/id]))
              ":rf.cofx/id names the absent provided fact"))))))

(deftest reg-event-framework-provided-rf-time-ms-declared-delivered-undeclared-absent
  (testing "framework-stamped time is delivered only when declared"
    (let [declared-time   (atom ::unset)
          undeclared-has? (atom ::unset)]
      ;; Leg 1 — DECLARED: the framework-stamped provided `:rf/time-ms` is
      ;; delivered flat from the enqueue stamp, NO caller supply needed.
      (rf/reg-event :evt-conf/declares-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [rf/time-ms]} _] (reset! declared-time time-ms) {}))
      ;; Leg 2 — UNDECLARED: the same stamped fact is NOT staged.
      (rf/reg-event :evt-conf/ignores-time
        (fn [cofx _] (reset! undeclared-has? (contains? cofx :rf/time-ms)) {}))
      ;; Dispatch WITHOUT supplying `:rf/time-ms` — the enqueue stamp provides it.
      (rf/dispatch-sync [:evt-conf/declares-time])
      (rf/dispatch-sync [:evt-conf/ignores-time])
      (is (number? @declared-time)
          "the DECLARED framework-stamped :rf/time-ms arrived flat from the enqueue stamp (a provided fact, always present — never missing-required)")
      (is (false? @undeclared-has?)
          "the UNDECLARED handler never sees :rf/time-ms — no implicit time (declared-only delivery, the most-consumed fact gets no exemption)"))))

(deftest reg-event-custom-provided-cofx-supplied-on-token-is-delivered-flat
  (testing "a supplied custom provided fact is delivered flat without a host read"
    (let [seen (atom ::unset)]
      ;; A custom provided recordable fact — NO supplier (its owner stamps the
      ;; token). The valid provided shape: `{:recordable? true :provided? true}`.
      (rf/reg-cofx :evt-conf/session-token
        {:recordable? true :provided? true
         :doc "A boundary fact the dispatch site stamps onto the token."})
      (rf/reg-event :evt-conf/reads-token
        {:rf.cofx/requires [:evt-conf/session-token]}
        (fn [{:keys [evt-conf/session-token]} _] (reset! seen session-token) {}))
      ;; SUPPLY the provided fact's value on the dispatch token.
      (rf/dispatch-sync [:evt-conf/reads-token]
                        {:rf.cofx {:evt-conf/session-token "jwt-abc-123"}})
      (is (= "jwt-abc-123" @seen)
          "the SUPPLIED custom provided recordable fact arrived FLAT under its id from the token (supplied values win — no generator, no host read)"))))

(deftest inject-cofx-is-off-the-public-facade-and-the-removal-is-a-hard-error
  (testing "inject-cofx is absent from the facade and its tombstone points to :rf.cofx/requires"
    ;; Leg 1 — OFF the public facade. JVM-only var-reflection probe: the
    ;; public facade carries no `inject-cofx` var. (CLJS has no runtime vars;
    ;; the CLJS publics surface is policed by the api-manifest --check gate.)
    #?(:clj
       (is (nil? (ns-resolve 're-frame.core 'inject-cofx))
           "there is NO public re-frame.core/inject-cofx var — the facade surface is removed"))
    ;; Leg 2 — the surviving private thrower is the always-on hard error.
    (is (= :rf.error/inject-cofx-removed
           (thrown-error-id #(cofx/inject-cofx :evt-conf/anything)))
        "the surviving inject-cofx thrower raises :rf.error/inject-cofx-removed")
    (let [reason (thrown-error-reason #(cofx/inject-cofx :evt-conf/anything))]
      (is (string? reason)
          "the removal stub raises an ex-info carrying a :reason string")
      (is (re-find #":rf.cofx/requires" reason)
          "the replacement guidance names `:rf.cofx/requires` (the one declaration surface)"))))

;; Closed effect-map contract.

(deftest reg-event-foreign-top-level-key-is-effect-map-shape
  (testing "a foreign top-level effect key is reported and refuses the event"
    (let [traces (atom [])
          fired? (atom false)]
      (rf/register-listener! :trace :evt-conf/shape-recorder (fn [ev] (swap! traces conj ev)))
      ;; A sentinel the foreign shortcut would dispatch IF the runtime
      ;; wrongly honoured the legacy top-level `:dispatch` — it must NOT.
      (rf/reg-event :evt-conf/shortcut-target (fn [_ _] (reset! fired? true) {}))
      ;; `:dispatch` at the TOP LEVEL is the canonical v1 legacy shortcut — it
      ;; must be lowered through `{:fx [[:dispatch …]]}`, so the bare top-level
      ;; key is a foreign-key shape error, not a silently-honoured shortcut.
      (rf/reg-event :evt-conf/legacy-shortcut
        (fn [_ _] {:dispatch [:evt-conf/shortcut-target]}))
      (rf/dispatch-sync [:evt-conf/legacy-shortcut])
      (rf/unregister-listener! :trace :evt-conf/shape-recorder)
      (is (false? @fired?)
          "the legacy top-level `:dispatch` was NOT silently honoured")
      (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %)) @traces)]
        (is (seq shape-traces)
            "a foreign / legacy top-level effect key emits :rf.error/effect-map-shape")
        (is (= :dispatch (get-in (first shape-traces) [:tags :offending-key]))
            "the diagnostic names the offending legacy top-level key")
        (is (= :fix-effect (:recovery (first shape-traces)))
            "the envelope violation REFUSES the event pre-commit (rf2-04tx)")))))

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
      (rf/register-listener! :trace :evt-conf/bare-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/bare-db-return])
      (rf/unregister-listener! :trace :evt-conf/bare-recorder)
      (is (= :seeded @(rf/subscribe [:evt-conf/bare-count]))
          "the bare `{:count 1}` return was NOT committed as app-db (the db-return convenience is GONE)")
      (let [shape-traces (filter #(and (= :rf.error/effect-map-shape (:operation %))
                                       (= :count (get-in % [:tags :offending-key])))
                                 @traces)]
        (is (seq shape-traces)
            "the bare app-db-shaped return's foreign app key emits :rf.error/effect-map-shape naming :count")
        (is (= :fix-effect (:recovery (first shape-traces)))
            "the bare-db-return shape diagnostic REFUSES the event (rf2-04tx)")))))

(deftest reg-event-app-handler-runtime-effect-keeps-the-diagnostic-unless-framework-authority
  (testing "runtime-db writes warn for app handlers but not framework-authorised handlers"
    (let [app-traces (atom [])
          fw-traces  (atom [])]
      ;; Leg 1 — an APP handler (no authority) returning :rf.db/runtime fires
      ;; the diagnostic.
      (rf/reg-event :evt-conf/app-writes-runtime
        (fn [_ _] {:rf.db/runtime {:rf.runtime/marker :app-wrote}}))
      (rf/register-listener! :trace :evt-conf/app-runtime-recorder (fn [ev] (swap! app-traces conj ev)))
      (rf/dispatch-sync [:evt-conf/app-writes-runtime])
      (rf/unregister-listener! :trace :evt-conf/app-runtime-recorder)
      (let [warn-traces (filter #(= :rf.warning/app-handler-runtime-effect (:operation %)) @app-traces)]
        (is (seq warn-traces)
            "an APP handler returning :rf.db/runtime keeps the :rf.warning/app-handler-runtime-effect diagnostic")
        (is (= :warned (:recovery (first warn-traces)))
            "the diagnostic carries :recovery :warned (the effect is still applied — convention, not enforcement)"))
      ;; Leg 2 — a FRAMEWORK-AUTHORITY handler (the reserved
      ;; `:rf/framework-authority? true` registration meta) writes :rf.db/runtime
      ;; WITHOUT the diagnostic.
      (rf/reg-event :evt-conf/fw-writes-runtime
        {:rf/framework-authority? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/marker :fw-wrote}}))
      (rf/register-listener! :trace :evt-conf/fw-runtime-recorder (fn [ev] (swap! fw-traces conj ev)))
      (rf/dispatch-sync [:evt-conf/fw-writes-runtime])
      (rf/unregister-listener! :trace :evt-conf/fw-runtime-recorder)
      (is (empty? (filter #(= :rf.warning/app-handler-runtime-effect (:operation %)) @fw-traces))
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
      (rf/register-listener! :trace :evt-conf/noop-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/noop-rewrite])
      (rf/unregister-listener! :trace :evt-conf/noop-recorder)
      (is (= :set @(rf/subscribe [:evt-conf/noop-seed]))
          "the `{:db db}` rewrite left app-db at its prior value (a true no-op)")
      (let [ops (set (map :operation @traces))]
        (is (contains? ops :rf.event/db-noop)
            "an unchanged `{:db db}` return fires :rf.event/db-noop")
        (is (not (contains? ops :rf.event/db-changed))
            "an unchanged `{:db db}` return does NOT fire :rf.event/db-changed")))))

(deftest reg-event-db-nil-return-is-coerced-to-empty-map-with-diagnostic
  (testing "a nil db effect becomes an empty map and emits its diagnostic"
    (let [traces  (atom [])
          db-seen (atom ::unset)]
      ;; Seed a non-trivial app-db so the coercion-to-`{}` is observable.
      (rf/reg-event :evt-conf/nil-seed! (fn [{:keys [db]} _] {:db (assoc db :k :v)}))
      (rf/reg-event :evt-conf/return-nil-db (fn [_ _] {:db nil}))
      ;; A follow-up handler reads app-db so we can prove it is `{}` (a map),
      ;; never nil — `(:k db)` is absent and `(map? db)` holds.
      (rf/reg-event :evt-conf/inspect-db (fn [{:keys [db]} _] (reset! db-seen db) {}))
      (rf/dispatch-sync [:evt-conf/nil-seed!])
      (rf/register-listener! :trace :evt-conf/nil-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/return-nil-db])
      (rf/unregister-listener! :trace :evt-conf/nil-recorder)
      (rf/dispatch-sync [:evt-conf/inspect-db])
      (is (map? @db-seen)
          "app-db after a `{:db nil}` return is a MAP, never nil")
      (is (not (contains? @db-seen :k))
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
      (rf/register-listener! :trace :evt-conf/clear-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/clear-db])
      (rf/unregister-listener! :trace :evt-conf/clear-recorder)
      (is (= :untouched @(rf/subscribe [:evt-conf/clear-mark]))
          "the deliberate `{:db {}}` clear emptied app-db (the prior :mark is gone)")
      (is (empty? (filter #(= :rf.warning/db-nil-coerced (:operation %)) @traces))
          "a deliberate `{:db {}}` clear emits NO :rf.warning/db-nil-coerced diagnostic"))))

(deftest reg-event-malformed-fx-value-refuses-the-event-without-throwing
  (testing "a malformed :fx value refuses the event in-band, never a raw host throw"
    (let [traces    (atom [])
          sentinel? (atom false)]
      (rf/reg-sub :evt-conf/fx-shape-db (fn [db _] (:committed db :absent)))
      ;; A sentinel the malformed `:fx` would dispatch IF the map-shaped value
      ;; were wrongly walked as a pair — it must NOT run.
      (rf/reg-event :evt-conf/fx-shape-sentinel (fn [_ _] (reset! sentinel? true) {}))
      (rf/reg-event :evt-conf/malformed-fx
        (fn [{:keys [db]} _]
          ;; `:db` is a well-formed sibling; `:fx` is a non-sequential MAP
          ;; (the documented forgot-the-outer-vector typo).
          {:db (assoc db :committed :yes)
           :fx {:dispatch [:evt-conf/fx-shape-sentinel]}}))
      (rf/register-listener! :trace :evt-conf/fx-shape-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/malformed-fx])
      (rf/unregister-listener! :trace :evt-conf/fx-shape-recorder)
      (is (= :absent @(rf/subscribe [:evt-conf/fx-shape-db]))
          "the sibling `:db` write did NOT commit — a malformed `:fx` refuses the whole event (rf2-04tx: no partial commit)")
      (is (false? @sentinel?)
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
      (rf/register-listener! :trace :evt-conf/boundary-recorder (fn [ev] (swap! traces conj ev)))
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

;; Registration shape.

(deftest reg-event-registers-under-event-kind-with-the-one-wrapper
  (testing "reg-event registers under :event with one default wrapper"
    (rf/reg-event :evt-conf/shape (fn [{:keys [db]} _] {:db (assoc db :m :v)}))
    (let [meta (rf/handler-meta :event :evt-conf/shape)]
      (is (some? meta)
          "reg-event registers under registry kind :event (handler-meta finds it)")
      (is (fn? (:handler-fn meta))
          "handler-meta surfaces the registered handler-fn")
      (is (not (contains? meta :event/kind))
          "the `:event/kind` sub-tag is GONE (one form, no kind discriminator)")
      ;; THE wrapper lock — exactly ONE framework wrapper, named :rf/event-handler.
      (is (= [:rf/event-handler] (mapv :id (:interceptors meta)))
          "the ONLY framework wrapper is the single :rf/event-handler interceptor")
      (let [wrapper (first (:interceptors meta))]
        (is (= :rf/event-handler (:id wrapper))
            "the wrapper id is :rf/event-handler")
        (is (true? (:rf/default? wrapper))
            "the wrapper carries :rf/default? true (filtered as a framework auto-wrapper)")))))

(deftest reg-event-no-per-kind-wrapper-ids-survive
  (testing "the unified event wrapper excludes the retired per-kind wrapper ids"
    (rf/reg-event :evt-conf/wrapper-drift (fn [_ _] {}))
    (let [ids (set (mapv :id (:interceptors (rf/handler-meta :event :evt-conf/wrapper-drift))))]
      (is (= #{:rf/event-handler} ids)
          "exactly one wrapper id; the unified :rf/event-handler")
      (is (not (contains? ids :rf/db-handler))  "the retired :rf/db-handler wrapper is gone")
      (is (not (contains? ids :rf/fx-handler))  "the retired :rf/fx-handler wrapper is gone")
      (is (not (contains? ids :rf/ctx-handler)) "the retired :rf/ctx-handler wrapper is gone"))))

(deftest reg-event-chain-references-an-interceptor-from-public-reg-interceptor
  (testing "an event chain resolves an interceptor registered through the public facade"
    (let [ran? (atom false)]
      (rf/reg-sub :evt-conf/public-icpt-marker (fn [db _] (:public-icpt-marker db)))
      (rf/reg-interceptor :evt-conf/audit
        {:doc "an application audit interceptor authored via reg-interceptor"}
        {:before (fn [ctx] (reset! ran? true) ctx)})
      (let [imeta (rf/handler-meta :interceptor :evt-conf/audit)]
        (is (some? imeta)
            "reg-interceptor registers under the :interceptor kind (handler-meta finds it)")
        (is (= "an application audit interceptor authored via reg-interceptor" (:doc imeta))
            "the registration metadata (:doc) is retained on the :interceptor entry")
        (is (contains? imeta :rf/interceptor-descriptor)
            "handler-meta surfaces the registered :rf/interceptor-descriptor (the public path)")
        (is (fn? (:before (:rf/interceptor-descriptor imeta)))
            "the descriptor carries the authored :before slot"))
      ;; The event chain references the public-registered interceptor by ID —
      ;; reference-only (an inline value would be :rf.error/inline-interceptor-removed).
      (rf/reg-event :evt-conf/uses-public-icpt
        {:interceptors [:evt-conf/audit]}
        (fn [{:keys [db]} _] {:db (assoc db :public-icpt-marker :handler-ran)}))
      (let [emeta (rf/handler-meta :event :evt-conf/uses-public-icpt)]
        (is (= [:evt-conf/audit :rf/event-handler] (chain-ids (:interceptors emeta)))
            "the event chain carries the interceptor REFERENCE (id), before the framework wrapper"))
      (rf/dispatch-sync [:evt-conf/uses-public-icpt])
      (is (true? @ran?)
          "the public-registered interceptor's :before ran in the event chain")
      (is (= :handler-ran @(rf/subscribe [:evt-conf/public-icpt-marker]))
          "the event handler ran after the public-registered interceptor"))))

;; Retired registration-form tombstones.

(deftest retired-names-raise-their-exact-removal-errors
  (testing "retired facade forms raise their specific removal errors"
    (is (= :rf.error/reg-event-db-removed
           (thrown-error-id #(rf/reg-event-db :evt-conf/via-db (fn [_ _] nil))))
        "reg-event-db raises :rf.error/reg-event-db-removed")
    (is (= :rf.error/reg-event-fx-removed
           (thrown-error-id #(rf/reg-event-fx :evt-conf/via-fx (fn [_ _] nil))))
        "reg-event-fx raises :rf.error/reg-event-fx-removed")
    (is (= :rf.error/reg-event-ctx-removed
           (thrown-error-id #(rf/reg-event-ctx :evt-conf/via-ctx (fn [_ _] nil))))
        "reg-event-ctx raises :rf.error/reg-event-ctx-removed")))

(deftest reg-event-db-and-fx-removals-still-point-at-reg-event
  (testing "retired db and fx forms point to reg-event"
    (let [db-reason (thrown-error-reason
                      #(rf/reg-event-db :evt-conf/db-reason (fn [_ _] nil)))
          fx-reason (thrown-error-reason
                      #(rf/reg-event-fx :evt-conf/fx-reason (fn [_ _] nil)))]
      (is (re-find #"reg-event" db-reason)
          "reg-event-db removal names reg-event as the replacement")
      (is (re-find #"reg-event" fx-reason)
          "reg-event-fx removal names reg-event as the replacement"))))

(deftest retired-names-are-resolvable-facade-vars
  (testing "retired names remain callable facade tombstones"
    ;; Unlike `reg-event`, these are functions in both runtimes.
    (is (fn? rf/reg-event-db)  "reg-event-db is a resolvable callable facade fn (the throwing stub)")
    (is (fn? rf/reg-event-fx)  "reg-event-fx is a resolvable callable facade fn (the throwing stub)")
    (is (fn? rf/reg-event-ctx) "reg-event-ctx is a resolvable callable facade fn (the throwing stub)")))

(deftest retired-names-register-nothing-only-reg-event-commits
  (testing "retired-form tombstones register nothing"
    (rf/reg-sub :evt-conf/tally (fn [db _] (:tally db [])))
    (rf/reg-event :evt-conf/live
      (fn [{:keys [db]} _] {:db (update db :tally (fnil conj []) :reg-event)}))
    (thrown-error-id #(rf/reg-event-db  :evt-conf/db-noreg  (fn [_ _] nil)))
    (thrown-error-id #(rf/reg-event-fx  :evt-conf/fx-noreg  (fn [_ _] nil)))
    (thrown-error-id #(rf/reg-event-ctx :evt-conf/ctx-noreg (fn [_ _] nil)))
    (is (nil? (registrar/lookup :event :evt-conf/db-noreg))
        "reg-event-db registered nothing")
    (is (nil? (registrar/lookup :event :evt-conf/fx-noreg))
        "reg-event-fx registered nothing")
    (is (nil? (registrar/lookup :event :evt-conf/ctx-noreg))
        "reg-event-ctx registered nothing")
    (rf/dispatch-sync [:evt-conf/live])
    (is (= [:reg-event] @(rf/subscribe [:evt-conf/tally]))
        "only the reg-event handler committed; the retired stubs registered nothing")))

(deftest retired-name-error-fans-out-on-the-always-on-channel-before-throwing
  (testing "retired-form errors reach the always-on channel before they throw"
    (let [seen (atom [])]
      (rf/register-listener! :errors :evt-conf/removal-recorder
        (fn [r] (swap! seen conj (:error r))))
      ;; The call throws; the listener must already have received the record.
      (is (= :rf.error/reg-event-db-removed
             (thrown-error-id #(rf/reg-event-db :evt-conf/fanned (fn [_ _] nil))))
          "the call still throws the removal error")
      (is (some #{:rf.error/reg-event-db-removed} @seen)
          "the removal error fanned out on the always-on channel before the throw")
      ;; Reset + prove the same for the fx + ctx removals (one channel, three errors).
      (reset! seen [])
      (thrown-error-id #(rf/reg-event-fx :evt-conf/fanned (fn [_ _] nil)))
      (is (some #{:rf.error/reg-event-fx-removed} @seen)
          "reg-event-fx removal also fans out on the always-on channel")
      (reset! seen [])
      (thrown-error-id #(rf/reg-event-ctx :evt-conf/fanned (fn [_ _] nil)))
      (is (some #{:rf.error/reg-event-ctx-removed} @seen)
          "reg-event-ctx removal also fans out on the always-on channel"))))

(deftest handled-event-fans-out-on-the-always-on-events-channel
  (testing "a handled event emits one record on the always-on event channel"
    (let [seen (atom [])]
      (rf/register-listener! :events :evt-conf/handled-recorder
        (fn [r] (swap! seen conj r)))
      (rf/make-frame {:id :evt-conf/events-main})
      (rf/reg-event :evt-conf/events-probe
        {:frame :evt-conf/events-main}
        (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
      (rf/dispatch-sync [:evt-conf/events-probe] {:frame :evt-conf/events-main})
      (is (= 1 (count @seen))
          "exactly one always-on event-emit record per processed event")
      (let [r (first @seen)]
        (is (= :evt-conf/events-probe (:event-id r))
            "the record names the processed event")
        (is (= :evt-conf/events-main (:frame r))
            "the record carries the resolved frame-id (fanned across EVERY frame)")
        (is (= :ok (:outcome r))
            "a clean settle reports :ok on the always-on channel")
        (is (contains? r :elapsed-ms)
            "the tight record carries the wall-clock :elapsed-ms slot (Spec 009 §Record shape)"))
      (rf/unregister-listener! :events :evt-conf/handled-recorder))))

;; JVM-only metadata check; the CLJS manifest gate owns compile-time publics.

#?(:clj
   (deftest public-facade-no-doc-classification
     (testing "reg-event is documented while retired facade tombstones are not"
       (is (nil? (:no-doc (meta #'re-frame.core/reg-event)))
           "reg-event is PUBLIC — it carries no :no-doc meta")
       (is (true? (:no-doc (meta #'re-frame.core/reg-event-ctx)))
           "the reg-event-ctx facade tombstone carries ^:no-doc")
       (is (true? (:no-doc (meta #'re-frame.core/reg-event-db)))
           "the REMOVED reg-event-db carries ^:no-doc (off the public manifest)")
       (is (true? (:no-doc (meta #'re-frame.core/reg-event-fx)))
           "the REMOVED reg-event-fx carries ^:no-doc (off the public manifest)"))))

(deftest reg-event-path-interceptor-works-with-db-slice-return
  (testing "a path interceptor splices an explicit :db slice effect into app-db"
    (rf/reg-sub :evt-conf/counter (fn [db _] (:counter db)))
    (rf/reg-event :evt-conf/inc-via-path
      {:interceptors [[:rf.interceptor/path [:counter]]]}
      ;; `db` here is the FOCUSED slice at [:counter], not the whole app-db;
      ;; the return is `{:db <new-slice>}`.
      (fn [{:keys [db]} _] {:db (update (or db {}) :value (fnil inc 0))}))
    (rf/dispatch-sync [:evt-conf/inc-via-path])
    (rf/dispatch-sync [:evt-conf/inc-via-path])
    (is (= {:value 2} @(rf/subscribe [:evt-conf/counter]))
        "the `{:db slice}` return was spliced back into app-db at [:counter]")))

(deftest raw-context-work-is-expressible-via-an-interceptor
  (testing "an interceptor can inspect context, skip the handler, and install effects"
    (let [handler-ran? (atom false)]
      (rf/reg-sub :evt-conf/guard-marker (fn [db _] (:guard-marker db)))
      (rf/reg-interceptor :evt-conf/guard
        {:before
         (fn [ctx]
           ;; Capture (read the full context) + short-circuit the
           ;; handler + install an effect directly — the trio that
           ;; reg-event-ctx used to do, now an interceptor concern.
           (-> ctx
               (assoc :rf/skip-handler? true)
               (assoc-in [:effects :fx]
                         [[:dispatch [:evt-conf/guard-fired]]])))})
      (rf/reg-event :evt-conf/guard-fired
        (fn [{:keys [db]} _] {:db (assoc db :guard-marker :fired)}))
      (rf/reg-event :evt-conf/guarded
        {:interceptors [:evt-conf/guard]}
        (fn [_ _] (reset! handler-ran? true) {:db {:should :not-run}}))
      (rf/dispatch-sync [:evt-conf/guarded])
      (is (false? @handler-ran?)
          "the interceptor short-circuited the handler via :rf/skip-handler?")
      (is (= :fired @(rf/subscribe [:evt-conf/guard-marker]))
          "the interceptor's directly-installed effect ran (no reg-event-ctx needed)"))))
