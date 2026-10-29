(ns re-frame.refused-event-installs-nothing-test
  "A refused event installs nothing, and its event schema is checked before
  its cofx are delivered (Spec 010 §Validation order on event processing,
  §Per-step recovery row 1).

  A refusal skips the handler but still runs the chain, so an entered
  interceptor's `:after` runs (Spec 002 §Interceptor chain execution). What
  that unwind stages never installs, and its `:fx` never walks: a
  `:boundary? true` payload is attacker-controlled by definition, and an audit
  interceptor writing it into app-db would deliver exactly the input the flag
  exists to keep out. Every refusal here has a control, a conforming payload
  through the same chain, which commits.

  The `:boundary? true` deftests assert execution facts and always-on records
  only, so they hold under `-Dre-frame.debug=false` and join the `:prod-gate`
  lane. The unguarded deftests exercise step-1 validation of a handler
  without `:boundary? true`, which only a dev build runs, so they carry
  `^:requires-debug`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(defn- dispatch-recording
  "Dispatch `event` to `:rf/default` with `opts`. Returns the event's
  always-on `:outcome`, the `:rf.error/id` of every always-on error record and
  the `:rf.error/id` of a throw, if one escaped."
  [event opts]
  (let [errors (atom [])
        events (atom [])]
    (rf.error-emit/register-error-listener! ::rec #(swap! errors conj (:rf.error/id %)))
    (rf.event-emit/register-event-listener! ::rec #(swap! events conj %))
    (try
      (let [thrown (try (rf/dispatch-sync event (assoc opts :frame :rf/default))
                        nil
                        (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e))))]
        {:outcome (some #(when (= (first event) (:event-id %)) (:outcome %)) @events)
         :errors  @errors
         :thrown  thrown})
      (finally
        (rf.error-emit/unregister-error-listener! ::rec)
        (rf.event-emit/unregister-event-listener! ::rec)))))

(defn- reg-audit!
  "Register `:test/audit`, whose `:after` stamps the event into the `:db`
  effect and queues `[:test/log event]` whatever the handler did, and the
  `:test/log` fx. Returns the fx's call log."
  []
  (let [log (atom [])]
    (rf/reg-fx :test/log (fn [_ event] (swap! log conj event)))
    (rf/reg-interceptor :test/audit
      {:after (fn [ctx]
                (let [event (get-in ctx [:coeffects :event])
                      db    (or (get-in ctx [:effects :db]) (get-in ctx [:coeffects :db]))]
                  (-> ctx
                      (assoc-in [:effects :db] (assoc db :audit/last event))
                      (update-in [:effects :fx] (fnil conj []) [:test/log event]))))})
    log))

(defn- reg-audited-event! [id boundary?]
  (rf/reg-event id
    {:schema [:cat [:= id] :int] :boundary? boundary? :interceptors [:test/audit]}
    (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)})))

;; ---- a refused event installs nothing ------------------------------------

(deftest boundary-refusal-installs-nothing-an-interceptor-staged
  (let [log     (reg-audit!)
        _       (reg-audited-event! :test/ingress true)
        before  (db)
        refused (dispatch-recording [:test/ingress "attacker-payload"] {})]
    (is (= [before []] [(db) @log])
        "the interceptor's :db never installed and its :fx never walked")
    (is (= {:outcome :rejected :errors [:rf.error/schema-validation-failure] :thrown nil}
           refused)
        "the refusal still settles :rejected and fans its one always-on record")
    (let [accepted (dispatch-recording [:test/ingress 7] {})]
      (is (= [{:n 7 :audit/last [:test/ingress 7]} [[:test/ingress 7]] :ok]
             [(select-keys (db) [:n :audit/last]) @log (:outcome accepted)])
          "control: the same chain commits a conforming payload and walks its :fx"))))

(deftest ^:requires-debug unguarded-refusal-installs-nothing-and-settles-ok
  ;; production never validates an unguarded :schema and would have run the
  ;; handler, so the refusal has no production signal and settles :ok
  (let [log     (reg-audit!)
        _       (reg-audited-event! :test/unguarded false)
        before  (db)
        refused (dispatch-recording [:test/unguarded "bad"] {})]
    (is (= [before [] :ok] [(db) @log (:outcome refused)]))
    (dispatch-recording [:test/unguarded 3] {})
    (is (= [{:n 3 :audit/last [:test/unguarded 3]} [[:test/unguarded 3]]]
           [(select-keys (db) [:n :audit/last]) @log])
        "control: a conforming payload commits")))

;; ---- the event schema is checked before the cofx -------------------------

(deftest boundary-refusal-runs-no-cofx-generator
  (let [minted (atom 0)
        ran    (atom 0)]
    (rf/reg-cofx :test/minted-id {:recordable? true} (fn [] (swap! minted inc) "id-1"))
    (rf/reg-event :test/minting-ingress
      {:schema           [:cat [:= :test/minting-ingress] :int]
       :boundary?        true
       :rf.cofx/requires [:test/minted-id]}
      (fn [_ _] (swap! ran inc) {}))
    (let [refused (dispatch-recording [:test/minting-ingress "bad"] {})]
      (is (= [0 0 :rejected] [@minted @ran (:outcome refused)])
          "a refused payload mints nothing"))
    (let [accepted (dispatch-recording [:test/minting-ingress 1] {})]
      (is (= [1 1 :ok] [@minted @ran (:outcome accepted)])
          "control: a conforming payload mints once and runs the handler"))))

(deftest boundary-refusal-is-not-masked-by-an-invalid-cofx
  (rf/reg-cofx :test/positive {:recordable? true :schema [:int {:min 1}]} (fn [] 1))
  (rf/reg-event :test/positive-ingress
    {:schema           [:cat [:= :test/positive-ingress] :int]
     :boundary?        true
     :rf.cofx/requires [:test/positive]}
    (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
  (is (= {:outcome :rejected :errors [:rf.error/schema-validation-failure] :thrown nil}
         (dispatch-recording [:test/positive-ingress "bad"] {:rf.cofx {:test/positive -5}}))
      "the payload's own refusal is what reports, not :rf.error/cofx-value-invalid")
  (is (= :rf.error/cofx-value-invalid
         (:thrown (dispatch-recording [:test/positive-ingress 1] {:rf.cofx {:test/positive -5}})))
      "control: a conforming payload still meets the cofx check")
  (is (nil? (:ran (db))) "neither dispatch ran the handler"))

(deftest ^:requires-debug unguarded-refusal-is-not-masked-by-a-missing-cofx
  (let [ran (atom 0)]
    (rf/reg-cofx :test/token {:recordable? true :provided? true})
    (rf/reg-event :test/needs-token
      {:schema [:cat [:= :test/needs-token] :int] :rf.cofx/requires [:test/token]}
      (fn [_ _] (swap! ran inc) {}))
    (is (= [nil 0] [(:thrown (dispatch-recording [:test/needs-token "bad"] {})) @ran])
        "the refusal skips the handler without asking for the cofx")
    (is (= :rf.error/missing-required-cofx
           (:thrown (dispatch-recording [:test/needs-token 1] {})))
        "control: a conforming payload still requires it")))
