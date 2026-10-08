(ns re-frame.cofx-cljs-test
  "Coeffect delivery through `reg-cofx` and `:rf.cofx/requires`: declared-only
  flat delivery, the recordable generator and its mint policies, the cofx error
  family, and the removed `inject-cofx` (Spec 001 §`reg-cofx`, Spec 002
  §Satisfaction algorithm, Spec 009 §Error catalogue).

  A `.cljc` file, so the JVM runner and the CLJS `:node-test` build both run it.
  Reads of the dev trace sit behind `rf.interop/debug-enabled?`; the delivered
  values, the thrown ex-data and the always-on `:errors` records also hold under
  the production gate."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.cofx :as rf.cofx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- collect-traces!
  "Register a trace listener under `id`; returns the atom it accumulates into."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- collect-errors!
  "Register an always-on `:errors` listener under `id`; returns the atom it
  accumulates into."
  [id]
  (let [acc (atom [])]
    (rf.error-emit/register-error-listener! id (fn [rec] (swap! acc conj rec)))
    acc))

(defn- errors-of [recs category]
  (filterv #(= category (:error %)) recs))

(defn- traces-of [traces operation]
  (filterv #(= operation (:operation %)) traces))

(defn- thrown
  "Call `f` and return the ExceptionInfo it throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e)))

;; ---------------------------------------------------------------------------
;; Declared-only delivery
;; ---------------------------------------------------------------------------

(deftest undeclared-leaf-on-token-is-not-delivered
  (testing "only the declared fact is staged flat; an undeclared leaf riding the token is not"
    (let [seen (atom ::unset)]
      (rf/reg-event :cofx-test/declares-only-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [cofx _]
          (reset! seen (select-keys cofx [:rf/time-ms :app/extra]))
          {}))
      (rf/dispatch-sync [:cofx-test/declares-only-time]
                        {:rf.cofx {:rf/time-ms 1781078400123
                                   :app/extra  :should-not-be-delivered}})
      (is (= {:rf/time-ms 1781078400123} @seen)))))

(deftest reply-envelope-carries-rf-cofx-flat-and-freshly-stamped
  (testing "a child dispatched from a handler is stamped its own :rf/time-ms
            instead of inheriting the parent's causal token"
    (let [request-record (atom ::unset)
          reply-record   (atom ::unset)]
      (rf/reg-event :cofx-test/request
        (fn [cofx _]
          (reset! request-record (:rf.cofx cofx))
          {:fx [[:dispatch [:cofx-test/replied]]]}))
      (rf/reg-event :cofx-test/replied
        (fn [cofx _]
          (reset! reply-record (:rf.cofx cofx))
          {}))
      (rf/dispatch-sync [:cofx-test/request] {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (= {:rf/time-ms 1781078400123} @request-record))
      (is (integer? (:rf/time-ms @reply-record)))
      (is (not= 1781078400123 (:rf/time-ms @reply-record))))))

;; ---------------------------------------------------------------------------
;; The cofx error family
;; ---------------------------------------------------------------------------

(deftest missing-required-provided-fact-fails-loudly
  (testing "a declared provided fact absent from the token halts before the
            handler, on the throw and on the always-on :errors record"
    (let [traces (collect-traces! ::missing)
          recs   (collect-errors! ::missing)
          fired? (atom false)]
      (rf/reg-cofx :cofx-test/required-boundary {:recordable? true :provided? true})
      (rf/reg-event :cofx-test/needs-boundary
        {:rf.cofx/requires [:cofx-test/required-boundary]}
        (fn [_ _] (reset! fired? true) {}))
      (let [ex (thrown #(rf/dispatch-sync [:cofx-test/needs-boundary]))]
        (rf/unregister-listener! :trace ::missing)
        (rf.error-emit/unregister-error-listener! ::missing)
        (is (false? @fired?))
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex))))
        ;; The always-on record names the declaring event; only the dev trace
        ;; names the absent fact.
        (is (= [:cofx-test/needs-boundary]
               (mapv :event-id (errors-of @recs :rf.error/missing-required-cofx))))
        (when rf.interop/debug-enabled?
          (is (= [:cofx-test/required-boundary]
                 (mapv #(get-in % [:tags :rf.cofx/id])
                       (traces-of @traces :rf.error/missing-required-cofx)))))))))

(deftest typo-yields-unregistered-not-missing
  (testing "a declared id with no reg-cofx is :rf.error/unregistered-cofx,
            distinct from missing-required"
    (let [traces (collect-traces! ::typo)
          recs   (collect-errors! ::typo)]
      (rf/reg-event :cofx-test/has-typo
        {:rf.cofx/requires [:cofx-test/typpo]}
        (fn [_ _] {}))
      (let [ex (thrown #(rf/dispatch-sync [:cofx-test/has-typo]))]
        (rf/unregister-listener! :trace ::typo)
        (rf.error-emit/unregister-error-listener! ::typo)
        (is (= :rf.error/unregistered-cofx (:rf.error/id (ex-data ex))))
        (is (= [[:cofx-test/has-typo :rf/default]]
               (mapv (juxt :event-id :frame) (errors-of @recs :rf.error/unregistered-cofx))))
        (when rf.interop/debug-enabled?
          (is (= [[:cofx-test/typpo :cofx-test/has-typo]]
                 (mapv (juxt #(get-in % [:tags :rf.cofx/id]) #(get-in % [:tags :failing-id]))
                       (traces-of @traces :rf.error/unregistered-cofx)))))))))

(deftest malformed-or-colliding-declaration-is-refused-at-registration
  (doseq [[label expected-id f]
          [["non-vector :rf.cofx/requires" :rf.error/cofx-request-invalid
            #(rf/reg-event :cofx-test/bad-requires-1
               {:rf.cofx/requires :not-a-vector}
               (fn [_ _] {}))]
           ["non-id :rf.cofx/requires entry" :rf.error/cofx-request-invalid
            #(rf/reg-event :cofx-test/bad-requires-2
               {:rf.cofx/requires [42]}
               (fn [_ _] {}))]
           ["the same id declared twice" :rf.error/cofx-name-collision
            #(rf/reg-event :cofx-test/dup-requires
               {:rf.cofx/requires [:rf/time-ms :rf/time-ms]}
               (fn [_ _] {}))]
           ["reg-cofx on a fold argument key" :rf.error/cofx-name-collision
            #(rf/reg-cofx :db (fn [] :nope))]]]
    (testing label
      (is (= expected-id (-> (thrown f) ex-data :rf.error/id))))))

(deftest malformed-reg-cofx-grade-is-registration-invalid
  (testing "a contradictory grade is :rf.error/cofx-registration-invalid naming
            the id, and nothing registers"
    (doseq [[label id f]
            [["provided without :recordable? true" :cofx-test/bad-grade
              #(rf/reg-cofx :cofx-test/bad-grade {:provided? true})]
             ["no supplier and not provided" :cofx-test/no-supplier
              #(rf/reg-cofx :cofx-test/no-supplier {:doc "missing supplier"})]
             ;; Delivery never runs a provided fact's supplier, so accepting
             ;; one would silently ignore it.
             ["provided WITH a supplier" :cofx-test/provided-with-fn
              #(rf/reg-cofx :cofx-test/provided-with-fn
                 {:recordable? true :provided? true}
                 (fn [] :ignored))]]]
      (testing label
        (is (= [:rf.error/cofx-registration-invalid id]
               ((juxt :rf.error/id :rf.cofx/id) (ex-data (thrown f)))))
        (is (nil? (rf.registrar/lookup :cofx id)))))))

;; ---------------------------------------------------------------------------
;; Ambient suppliers
;; ---------------------------------------------------------------------------

(deftest cofx-run-stamps-produced-value-and-arg
  (testing "an ambient supplier's produced value is delivered flat; :rf.cofx/run
            stamps it under :rf.cofx/value, with the requirement-arg under
            :rf.cofx/arg only for an [id arg] declaration"
    (let [traces    (collect-traces! ::run-tags)
          delivered (atom ::unset)]
      (rf/reg-cofx :cofx-test/local-pref (fn [storage-key] (str "value-for-" storage-key)))
      (rf/reg-cofx :cofx-test/locale (fn [] "en-AU"))
      (rf/reg-event :cofx-test/read-ambient
        {:rf.cofx/requires [[:cofx-test/local-pref "theme"] :cofx-test/locale]}
        (fn [cofx _]
          (reset! delivered (select-keys cofx [:cofx-test/local-pref :cofx-test/locale]))
          {}))
      (rf/dispatch-sync [:cofx-test/read-ambient])
      (rf/unregister-listener! :trace ::run-tags)
      (is (= {:cofx-test/local-pref "value-for-theme" :cofx-test/locale "en-AU"} @delivered))
      (when rf.interop/debug-enabled?
        (is (= {:cofx-test/local-pref {:rf.cofx/value "value-for-theme" :rf.cofx/arg "theme"}
                :cofx-test/locale     {:rf.cofx/value "en-AU"}}
               (into {}
                     (map (juxt #(get-in % [:tags :rf.cofx/id])
                                #(select-keys (:tags %) [:rf.cofx/value :rf.cofx/arg])))
                     (traces-of @traces :rf.cofx/run))))))))

(deftest cofx-run-sensitive-produced-value-is-redacted-end-to-end
  (testing "a :sensitive path of the produced value is redacted on the
            :rf.cofx/run trace, never in the coeffect the handler receives"
    (let [traces    (collect-traces! ::run-redact)
          delivered (atom ::unset)]
      (rf/reg-cofx :cofx-test/session
        {:sensitive [[:token]]}
        (fn [] {:token "super-secret-jwt" :public "ok"}))
      (rf/reg-event :cofx-test/read-session
        {:rf.cofx/requires [:cofx-test/session]}
        (fn [{:keys [cofx-test/session]} _] (reset! delivered session) {}))
      (rf/dispatch-sync [:cofx-test/read-session])
      (rf/unregister-listener! :trace ::run-redact)
      (is (= {:token "super-secret-jwt" :public "ok"} @delivered))
      (when rf.interop/debug-enabled?
        (let [value (get-in (first (traces-of @traces :rf.cofx/run)) [:tags :rf.cofx/value])]
          (is (= [:rf/redacted "ok"] ((juxt :token :public) value))))))))

(deftest inject-cofx-call-is-hard-error
  (testing "the inject-cofx stub throws :rf.error/inject-cofx-removed naming
            :rf.cofx/requires as the replacement"
    (let [data (ex-data (thrown #(rf.cofx/inject-cofx :anything)))]
      (is (= [:rf.error/inject-cofx-removed :anything]
             ((juxt :rf.error/id :rf.cofx/id) data)))
      (is (re-find #":rf.cofx/requires" (:reason data))))))

(deftest platforms-gating-skips-off-platform-ambient
  (testing "a :platforms #{:client} supplier does not run on a :server frame:
            nothing is delivered and the event still runs"
    ;; The frame pins :server so the supplier is off-platform on both hosts
    ;; (the host default is :server on the JVM and :client on CLJS).
    (rf/make-frame {:id :cofx-test/server-frame :platform :server})
    (let [traces      (collect-traces! ::plat)
          cofx-fired? (atom false)
          delivered?  (atom ::unset)]
      (rf/reg-cofx :cofx-test/browser-locale
        {:platforms #{:client}}
        (fn [] (reset! cofx-fired? true) "en-US"))
      (rf/reg-event :cofx-test/read-browser-locale
        {:rf.cofx/requires [:cofx-test/browser-locale]}
        (fn [cofx _]
          (reset! delivered? (contains? cofx :cofx-test/browser-locale))
          {}))
      (rf/dispatch-sync [:cofx-test/read-browser-locale] {:frame :cofx-test/server-frame})
      (rf/unregister-listener! :trace ::plat)
      (is (false? @cofx-fired?))
      (is (false? @delivered?) "the handler ran, without the skipped fact")
      (when rf.interop/debug-enabled?
        (is (= [:cofx-test/browser-locale]
               (mapv #(get-in % [:tags :rf.cofx/id])
                     (traces-of @traces :rf.cofx/skipped-on-platform))))))))

(deftest handler-meta-surfaces-requires
  (testing "`:rf.cofx/requires` surfaces in handler-meta exactly as authored (Spec 009 §9)"
    (rf/reg-event :cofx-test/reflective {:rf.cofx/requires [:rf/time-ms]} (fn [_ _] {}))
    (is (= [:rf/time-ms]
           (:rf.cofx/requires (rf.registrar/lookup :event :cofx-test/reflective))))))

;; ---------------------------------------------------------------------------
;; Recordable generators
;; ---------------------------------------------------------------------------

(defn- with-schema-validator
  "Install `validate-fn` (and a trivial explainer) through the schemas
  late-bind seam for the duration of `body-fn`, then restore the prior hooks.
  Exercises the real cofx `:schema` branch without the schemas artefact."
  [validate-fn body-fn]
  (let [prior-v (re-frame.late-bind/get-fn :schemas/validate-with-registered-fn)
        prior-e (re-frame.late-bind/get-fn :schemas/explain-with-registered-fn)]
    (re-frame.late-bind/set-fn! :schemas/validate-with-registered-fn validate-fn)
    (re-frame.late-bind/set-fn! :schemas/explain-with-registered-fn
                                (fn [schema value] {:schema schema :value value :failed true}))
    (try
      (body-fn)
      (finally
        (re-frame.late-bind/set-fn! :schemas/validate-with-registered-fn prior-v)
        (re-frame.late-bind/set-fn! :schemas/explain-with-registered-fn prior-e)))))

(deftest generator-runs-at-processing-start-fills-and-records
  (testing "under the :live default a declared-absent generator-backed fact is
            generated once, delivered flat, and written back into the causal
            :rf.cofx record beside the stamped :rf/time-ms"
    (let [traces    (collect-traces! ::generated)
          gen-calls (atom 0)
          seen      (atom nil)]
      (rf/reg-cofx :gen-test/delta {:recordable? true} (fn [] (swap! gen-calls inc) 7))
      (rf/reg-event :gen-test/inc
        {:rf.cofx/requires [:rf/time-ms :gen-test/delta]}
        (fn [cofx _] (reset! seen cofx) {}))
      (rf/dispatch-sync [:gen-test/inc])
      (rf/unregister-listener! :trace ::generated)
      (let [cofx    @seen
            time-ms (:rf/time-ms cofx)]
        (is (= 1 @gen-calls))
        (is (integer? time-ms) "the declared, unsupplied :rf/time-ms is the router's stamp")
        (is (= 7 (:gen-test/delta cofx)))
        (is (= {:rf/time-ms time-ms :gen-test/delta 7} (:rf.cofx cofx))))
      (when rf.interop/debug-enabled?
        (is (= [{:rf.cofx/id :gen-test/delta :rf.cofx/value 7}]
               (mapv #(select-keys (:tags %) [:rf.cofx/id :rf.cofx/value])
                     (traces-of @traces :rf.cofx/generated))))
        (is (empty? (traces-of @traces :rf.cofx/run))
            "a generated fact does not also emit the ambient :rf.cofx/run op")))))

(deftest supplied-value-wins-generator-does-not-run
  (testing "a recordable fact present on the token is delivered verbatim and
            its generator does not run"
    (let [gen-calls (atom 0)
          seen      (atom nil)]
      (rf/reg-cofx :gen-test/supplied-delta {:recordable? true} (fn [] (swap! gen-calls inc) 99))
      (rf/reg-event :gen-test/use-supplied
        {:rf.cofx/requires [:gen-test/supplied-delta]}
        (fn [{:keys [gen-test/supplied-delta]} _] (reset! seen supplied-delta) {}))
      (rf/dispatch-sync [:gen-test/use-supplied] {:rf.cofx {:gen-test/supplied-delta 3}})
      (is (zero? @gen-calls))
      (is (= 3 @seen)))))

(deftest generated-value-schema-mismatch-is-hard-error
  (testing "a generated value failing the registration's :schema is
            :rf.error/cofx-value-invalid and halts before the handler"
    (with-schema-validator
      (fn [schema value] (if (= schema :gen-test/positive) (pos-int? value) true))
      (fn []
        (let [traces (collect-traces! ::bad-gen)
              recs   (collect-errors! ::bad-gen)
              fired? (atom false)]
          (rf/reg-cofx :gen-test/bad {:recordable? true :schema :gen-test/positive} (fn [] -1))
          (rf/reg-event :gen-test/uses-bad
            {:rf.cofx/requires [:gen-test/bad]}
            (fn [_ _] (reset! fired? true) {}))
          (let [ex (thrown #(rf/dispatch-sync [:gen-test/uses-bad]))]
            (rf/unregister-listener! :trace ::bad-gen)
            (rf.error-emit/unregister-error-listener! ::bad-gen)
            (is (false? @fired?))
            (is (= [:rf.error/cofx-value-invalid :gen-test/bad]
                   ((juxt :rf.error/id :rf.cofx/id) (ex-data ex))))
            (is (= [:gen-test/uses-bad]
                   (mapv :event-id (errors-of @recs :rf.error/cofx-value-invalid))))
            (when rf.interop/debug-enabled?
              (is (= [:gen-test/bad]
                     (mapv #(get-in % [:tags :rf.cofx/id])
                           (traces-of @traces :rf.error/cofx-value-invalid)))))))))))

(deftest generated-non-edn-value-is-cofx-value-invalid
  (testing "a generator minting a host handle is :rf.error/cofx-value-invalid
            (:non-edn-recordable-value) even with no :schema, halting before
            write-back and before the handler"
    (let [traces (collect-traces! ::gen-non-edn)
          recs   (collect-errors! ::gen-non-edn)
          fired? (atom false)]
      (rf/reg-cofx :gen-test/host-handle {:recordable? true} (fn [] (atom :a-host-handle)))
      (rf/reg-event :gen-test/uses-host-handle
        {:rf.cofx/requires [:gen-test/host-handle]}
        (fn [_ _] (reset! fired? true) {}))
      (let [data (ex-data (thrown #(rf/dispatch-sync [:gen-test/uses-host-handle])))]
        (rf/unregister-listener! :trace ::gen-non-edn)
        (rf.error-emit/unregister-error-listener! ::gen-non-edn)
        (is (false? @fired?))
        (is (= {:rf.error/id         :rf.error/cofx-value-invalid
                :rf.cofx/value-error :non-edn-recordable-value
                :rf.cofx/id          :gen-test/host-handle
                :path                [:gen-test/host-handle]}
               (select-keys data [:rf.error/id :rf.cofx/value-error :rf.cofx/id :path])))
        (is (string? (:bad-type data)) "a printable type name, never the raw object")
        (is (= [:gen-test/uses-host-handle]
               (mapv :event-id (errors-of @recs :rf.error/cofx-value-invalid))))
        (when rf.interop/debug-enabled?
          (is (= [[:non-edn-recordable-value :gen-test/host-handle]]
                 (mapv (juxt #(get-in % [:tags :reason]) #(get-in % [:tags :rf.cofx/id]))
                       (traces-of @traces :rf.error/cofx-value-invalid)))))))))

;; ---------------------------------------------------------------------------
;; Mint-policy binding points: per-call opt, then frame config, then :live
;; ---------------------------------------------------------------------------

(deftest test-preset-default-is-strict-does-not-generate
  (testing "a :preset :test frame defaults to :strict: a declared-absent
            generator-backed fact is missing-required and its generator never runs"
    (let [gen-calls (atom 0)]
      (rf/make-frame {:id :mint-test/strict-frame :preset :test})
      (rf/reg-cofx :mint-test/strict-delta {:recordable? true} (fn [] (swap! gen-calls inc) 3))
      (rf/reg-event :mint-test/strict-evt
        {:rf.cofx/requires [:mint-test/strict-delta]}
        (fn [_ _] {}))
      (let [ex (thrown #(rf/dispatch-sync [:mint-test/strict-evt] {:frame :mint-test/strict-frame}))]
        (is (zero? @gen-calls))
        (is (= [:rf.error/missing-required-cofx :mint-test/strict-delta]
               ((juxt :rf.error/id :rf.cofx/id) (ex-data ex))))))))

(deftest replay-per-call-strict-does-not-generate
  (testing "the per-call :rf.cofx/mint-policy :strict opt, which replay uses,
            beats the frame's :live: an incomplete record is missing-required,
            never re-minted"
    (let [gen-calls (atom 0)]
      (rf/reg-cofx :mint-test/replay-delta {:recordable? true} (fn [] (swap! gen-calls inc) 5))
      (rf/reg-event :mint-test/replay-evt
        {:rf.cofx/requires [:mint-test/replay-delta]}
        (fn [_ _] {}))
      (let [ex (thrown #(rf/dispatch-sync [:mint-test/replay-evt]
                                          {:rf.cofx/mint-policy :strict
                                           :rf.cofx             {:rf/time-ms 1781078400123}}))]
        (is (zero? @gen-calls))
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex))))))))

(deftest explicit-live-overrides-strict-frame-generates
  (testing "the per-call :explicit-live opt overrides a :test frame's :strict
            default, so the generator runs"
    (let [gen-calls (atom 0)
          seen      (atom nil)]
      (rf/make-frame {:id :mint-test/escape-frame :preset :test})
      (rf/reg-cofx :mint-test/escape-delta {:recordable? true} (fn [] (swap! gen-calls inc) 9))
      (rf/reg-event :mint-test/escape-evt
        {:rf.cofx/requires [:mint-test/escape-delta]}
        (fn [{:keys [mint-test/escape-delta]} _] (reset! seen escape-delta) {}))
      (rf/dispatch-sync [:mint-test/escape-evt]
                        {:frame               :mint-test/escape-frame
                         :rf.cofx/mint-policy :explicit-live})
      (is (= 1 @gen-calls))
      (is (= 9 @seen)))))

(deftest per-call-strict-inherited-by-cascade-child
  (testing "a per-call :strict on the parent carries to a :dispatch child: the
            child's absent generator-backed fact is missing-required, not re-minted"
    (let [child-gen-calls (atom 0)]
      (rf/reg-cofx :cascade-test/child-delta {:recordable? true} (fn [] (swap! child-gen-calls inc) 7))
      (rf/reg-event :cascade-test/child-evt
        {:rf.cofx/requires [:cascade-test/child-delta]}
        (fn [_ _] {}))
      (rf/reg-event :cascade-test/parent-evt
        (fn [_ _] {:fx [[:dispatch [:cascade-test/child-evt]]]}))
      (let [ex (thrown #(rf/dispatch-sync [:cascade-test/parent-evt] {:rf.cofx/mint-policy :strict}))]
        (is (zero? @child-gen-calls))
        (is (= [:rf.error/missing-required-cofx :cascade-test/child-delta]
               ((juxt :rf.error/id :rf.cofx/id) (ex-data ex)))))))

  (testing "FOIL: without the per-call :strict the same cascade child generates
            under the ambient :live default"
    (let [child-gen-calls (atom 0)
          child-seen      (atom nil)]
      (rf/reg-cofx :cascade-test/live-delta {:recordable? true} (fn [] (swap! child-gen-calls inc) 11))
      (rf/reg-event :cascade-test/live-child
        {:rf.cofx/requires [:cascade-test/live-delta]}
        (fn [{:keys [cascade-test/live-delta]} _] (reset! child-seen live-delta) {}))
      (rf/reg-event :cascade-test/live-parent
        (fn [_ _] {:fx [[:dispatch [:cascade-test/live-child]]]}))
      (rf/dispatch-sync [:cascade-test/live-parent])
      (is (= 1 @child-gen-calls))
      (is (= 11 @child-seen)))))
