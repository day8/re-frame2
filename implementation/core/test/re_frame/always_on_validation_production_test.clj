(ns re-frame.always-on-validation-production-test
  "The two schema-validation surfaces Spec 010 keeps ALWAYS-ON in production,
  pinned under the REAL gate:

    1. **Recordable-coeffect `:schema`** (010:165, 010:179) — a production hard
       error: an out-of-contract recordable value throws
       `:rf.error/cofx-value-invalid` before the handler sees it, because
       folding it into the durable causal ledger is corrupt state.
    2. **`:boundary? true`** (010:204, 010:220) — boundary validation runs even
       when global validation is elided, and its rejection is REPORTED: one
       structural-only always-on record (`:source :boundary`) and
       `:outcome :rejected`, never a silent skip that reads `:ok` off-box.

  Every deftest but the `^:prod-gate` one is posture-independent, so this
  namespace runs in `clojure -M:test` AND joins `scripts/test-core-prod-gate.sh`.
  The `^:prod-gate` deftest is the discriminator: in the gate JVM an
  unguarded handler with the same `:schema` ACCEPTS a bad event, so \"the
  handler did not run\" cannot be step-1 doing the work. `with-redefs` on
  `debug-enabled?` is deliberately not used: the flag is read at load time."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.error-emit/clear-error-listeners!)
  (rf.event-emit/clear-event-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; republish the late-bind validation hooks both surfaces reach through
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-both-axes
  "Capture both ALWAYS-ON axes — `:errors` and `:events` — across one body:
  the whole of what an off-box shipper receives from a production build."
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

(defn- dispatch-capturing
  "Dispatch `event` with `opts`; return the always-on axes plus the thrown
  ex-info, if any."
  [event opts]
  (let [ex (atom nil)
        captured (record-both-axes
                   #(reset! ex (try (rf/dispatch-sync event opts) nil
                                    (catch clojure.lang.ExceptionInfo e e))))]
    (assoc captured :ex @ex)))

(defn- record-of [{:keys [errors]} error-kw]
  (first (filter #(= error-kw (:error %)) errors)))

(defn- boundary-records [{:keys [errors]}]
  (filterv #(= :rf.error/schema-validation-failure (:error %)) errors))

(defn- outcome-of [{:keys [events]} event-id]
  (:outcome (first (filter #(= event-id (:event-id %)) events))))

(def ^:private boundary-record-keys
  "The CLOSED key set of the always-on boundary-rejection record. It egresses
  to Sentry / Datadog, so widening it is an EGRESS decision: read
  `re-frame.egress-chokepoint-conformance-test`'s allow-list entry first."
  #{:error :where :source :event-id :failing-id :schema-id :frame :recovery :time})

(defn- db-of [] (rf/app-db-value :rf/default))

;; ---- surface 1: recordable-coeffect `:schema` is a production hard error --

(deftest supplied-recordable-value-violating-its-schema-throws-in-every-posture
  ;; Spec 010:179 — a value SUPPLIED on the dispatch token (the replay-hole
  ;; shape) is validated before it folds into the handler context
  (let [ran (atom false)]
    (rf/reg-cofx :prod/positive {:recordable? true :schema [:int {:min 1}]} (fn [] 1))
    (rf/reg-event :prod/uses-positive
      {:rf.cofx/requires [:prod/positive]}
      (fn [{:keys [db]} _] (reset! ran true) {:db (assoc db :folded true)}))
    (let [c (dispatch-capturing [:prod/uses-positive] {:rf.cofx {:prod/positive -5}})]
      (is (= [:rf.error/cofx-value-invalid :prod/positive false nil]
             [(:rf.error/id (ex-data (:ex c))) (:rf.cofx/id (ex-data (:ex c))) @ran (:folded (db-of))])
          "the run halted with the specified hard error before the fold")
      ;; the record names the DISPATCH (the cofx id rides the throw's ex-data)
      (is (= {:event-id :prod/uses-positive :frame :rf/default}
             (select-keys (record-of c :rf.error/cofx-value-invalid) [:event-id :frame]))
          "the always-on record fired; red under the gate means Spec 010:179 is elided"))))

;; The `[:ref ...]` target is deliberately UNREGISTERED: Malli throws on it, the
;; recordable check fails closed, and the redaction decision is the walker's
;; against the declared schema — the path under test. This surface is
;; always-on, so a walker that treated `:ref` as walkable-and-flag-free would
;; ship the value off-box in a production build.

(deftest recordable-cofx-with-ref-schema-redacts-off-box-in-every-posture
  (rf/reg-cofx :prod/ref-ctx {:recordable? true :schema [:ref :fixture/user]}
    (fn [] {:token "placeholder"}))
  (rf/reg-event :prod/uses-ref-ctx {:rf.cofx/requires [:prod/ref-ctx]} (fn [{:keys [db]} _] {:db db}))
  (let [c    (dispatch-capturing [:prod/uses-ref-ctx]
                                 {:rf.cofx {:prod/ref-ctx {:token "SECRET-REF-TOKEN"}}})
        data (ex-data (:ex c))
        err  (record-of c :rf.error/cofx-value-invalid)]
    (is (= [:rf.error/cofx-value-invalid :rf/redacted true]
           [(:rf.error/id data) (:value data) (:sensitive? data)])
        "the throw's ex-data (public error data) redacts and says so")
    (is (= [true nil false false]
           [(some? err) (:value err)
            (str/includes? (pr-str data) "SECRET-REF-TOKEN")
            (str/includes? (pr-str err) "SECRET-REF-TOKEN")])
        "no raw value survives in the ex-data or the off-box record")))

(deftest recordable-cofx-with-plain-map-schema-rides-verbatim-control
  ;; the control for the test above: a walkable, non-sensitive schema's
  ;; failing value rides verbatim and unstamped — 'everything redacts' fails here
  (rf/reg-cofx :prod/plain-ctx {:recordable? true :schema [:map [:n :int]]} (fn [] {:n 1}))
  (rf/reg-event :prod/uses-plain-ctx {:rf.cofx/requires [:prod/plain-ctx]} (fn [{:keys [db]} _] {:db db}))
  (let [data (ex-data (:ex (dispatch-capturing [:prod/uses-plain-ctx]
                                               {:rf.cofx {:prod/plain-ctx {:n "not-an-int"}}})))]
    (is (= [:rf.error/cofx-value-invalid {:n "not-an-int"} false]
           [(:rf.error/id data) (:value data) (contains? data :sensitive?)]))))

(deftest conforming-recordable-value-still-folds-in-every-posture
  (rf/reg-cofx :prod/positive {:recordable? true :schema [:int {:min 1}]} (fn [] 1))
  (rf/reg-event :prod/uses-positive
    {:rf.cofx/requires [:prod/positive]}
    (fn [{:keys [db prod/positive]} _] {:db (assoc db :folded positive)}))
  (rf/dispatch-sync [:prod/uses-positive] {:rf.cofx {:prod/positive 7}})
  (is (= 7 (:folded (db-of)))))

(deftest generated-recordable-value-violating-its-schema-throws-in-every-posture
  ;; Spec 010:165 — a generated value is validated before the write-back into
  ;; the durable :rf.cofx record
  (let [ran (atom false)]
    (rf/reg-cofx :prod/generated-positive {:recordable? true :schema [:int {:min 1}]} (fn [] -5))
    (rf/reg-event :prod/uses-generated
      {:rf.cofx/requires [:prod/generated-positive]}
      (fn [_ _] (reset! ran true) {}))
    (let [c (dispatch-capturing [:prod/uses-generated] {})]
      (is (= [:rf.error/cofx-value-invalid false true]
             [(:rf.error/id (ex-data (:ex c))) @ran (some? (record-of c :rf.error/cofx-value-invalid))])))))

;; ---- surface 2: `:boundary? true` runs, and reports, in every posture ----

(defn- reg-boundary-handler! [calls]
  (rf/reg-event :prod/boundary
    {:schema [:cat [:= :prod/boundary] :int] :boundary? true}
    (fn [{:keys [db]} [_ n]] (swap! calls inc) {:db (assoc db :n n)})))

(deftest at-boundary-rejection-fans-one-structural-record-in-every-posture
  ;; dev refuses in step-1 and production in the boundary arm; both converge on
  ;; one router-tail emit, so EXACTLY ONE record in either posture. The record
  ;; is STRUCTURAL-ONLY: a boundary payload is attacker-controlled or private
  ;; by definition, so every payload-derived slot is omitted outright.
  (let [calls    (atom 0)
        secret   "sentinel-secret-value"
        _        (reg-boundary-handler! calls)
        captured (record-both-axes #(rf/dispatch-sync [:prod/boundary {:password secret}]))
        records  (boundary-records captured)
        rec      (first records)]
    (is (= [0 nil 1] [@calls (:n (db-of)) (count records)])
        "the handler was skipped, nothing committed, and exactly one record fanned")
    (is (= {:where :event :source :boundary :event-id :prod/boundary :failing-id :prod/boundary
            :schema-id :prod/boundary :frame :rf/default :recovery :no-recovery}
           (dissoc rec :error :time))
        ":where :event (the SSR projector answers 400) and :source :boundary")
    (is (= boundary-record-keys (set (keys rec))) "the key set is CLOSED")
    (is (number? (:time rec)))
    (is (not (str/includes? (pr-str rec) secret))
        "the rejected payload appears nowhere in the record, by any route")
    (is (= :rejected (outcome-of captured :prod/boundary))
        "not :ok — monitoring must separate a refused payload from a healthy dispatch")))

(deftest at-boundary-pass-emits-no-record-and-settles-ok-in-every-posture
  (let [calls    (atom 0)
        _        (reg-boundary-handler! calls)
        captured (record-both-axes #(rf/dispatch-sync [:prod/boundary 42]))]
    (is (= [1 42 [] :ok]
           [@calls (:n (db-of)) (boundary-records captured) (outcome-of captured :prod/boundary)])
        "a conforming payload runs once, fans no record, and settles :ok")))

(deftest unguarded-schema-refusal-is-not-a-boundary-rejection-in-every-posture
  ;; a :schema without :boundary? true is dev-only validation: neither posture
  ;; may fan the always-on record or report :rejected
  (rf/reg-event :prod/unguarded-obs
    {:schema [:cat [:= :prod/unguarded-obs] :int]}
    (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (let [captured (record-both-axes #(rf/dispatch-sync [:prod/unguarded-obs "not-an-int"]))]
    (is (empty? (boundary-records captured)))
    (is (not= :rejected (outcome-of captured :prod/unguarded-obs)))))

;; ---- the discriminator — gate lane ONLY ----------------------------------

(deftest ^:prod-gate ordinary-event-schema-validation-really-is-elided-here
  ;; an unguarded handler with the identical :schema ACCEPTS a bad event in this
  ;; JVM, so the boundary rejection above cannot be step-1 doing the work
  (is (false? rf.interop/debug-enabled?) "precondition: the real production gate")
  (let [calls (atom 0)]
    (rf/reg-event :prod/unguarded
      {:schema [:cat [:= :prod/unguarded] :int]}
      (fn [{:keys [db]} [_ n]] (swap! calls inc) {:db (assoc db :n n)}))
    (rf/dispatch-sync [:prod/unguarded "not-an-int"])
    (is (= [1 "not-an-int"] [@calls (:n (db-of))])
        "red means step-1 validation still runs here and the boundary tests prove nothing")))
