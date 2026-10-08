(ns re-frame.fx-aggregate-classification-cljs-test
  "Classification at the fx-arg-bearing trace slots (`:rf.event/fx` on
  `:rf.fx/do-fx`, and every `[:rf.fx/id :rf.fx/args]` slot) for the two cases
  a static per-fx `:sensitive` cannot reach:

    (a) a `:dispatch` / `:dispatch-later` entry carries a TARGET event, whose
        own registration classification applies to its payload;
    (b) `:rf.http/managed`'s per-call `:sensitive?` flag and its reply
        addresses, routed through the `:http/project-managed-fx-args` hook.

  The live round-trips read the dev trace, so their trace reads sit inside
  `(when rf.interop/debug-enabled? …)` arms (a no-leak sweep over an empty
  stream passes for free); each first proves, in every posture, that the
  handler or fx body received the RAW value — redaction is egress-only."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loading the http artefact binds the `:http/project-managed-fx-args` hook.
            [re-frame.http.managed]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private pw-sentinel "rf2-32ffq1-PW-9e5b27")
(def ^:private email "user@example.test")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(defn- project [ev] (:tags (rf.classification/project-trace-event ev)))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

(defn- register-target-classification! []
  (rf.registrar/register! :event ::target {:sensitive [[:secret]]}))

(deftest aggregate-dispatch-later-inherits-target-classification
  (testing "a [:dispatch-later {:ms … :event [classified-target …]}] entry redacts
            the carried target's payload and keeps the rest of the shape"
    (register-target-classification!)
    (is (= [[:dispatch-later {:ms 500 :event [::target {:secret rf.privacy/redacted-sentinel}]}]]
           (:rf.event/fx (project {:operation :rf.fx/do-fx
                                   :tags {:frame       :rf/default
                                          :rf.event/fx [[:dispatch-later
                                                         {:ms    500
                                                          :event [::target {:secret pw-sentinel}]}]]}}))))))

;; `:reply-to`, `:on-success` and `:on-failure` are alternate ADDRESSING forms
;; for one reply (Spec 014 §Reply addressing), not alternate privacy contracts.
(deftest every-reply-address-key-rides-target-classification-in-aggregate
  (register-target-classification!)
  (doseq [k [:reply-to :on-success :on-failure]]
    (testing (str k " redacts the target's declared path, keeping the id and unmarked fields")
      (is (= [::target {:secret rf.privacy/redacted-sentinel :email email}]
             (get-in (project {:operation :rf.fx/do-fx
                               :tags {:frame       :rf/default
                                      :rf.event/fx [[:rf.http/managed
                                                     {:request {:method :post
                                                                :url    "https://api.example.test/save"}
                                                      k        [::target {:secret pw-sentinel
                                                                          :email  email}]}]]}})
                     [:rf.event/fx 0 1 k]))))))

(deftest live-nested-dispatch-redacts-at-parent-and-target-slots
  (testing "B returns {:fx [[:dispatch [A {…}]]]} with A classified — A's handler
            reads the RAW secret; B's :rf.event/fx aggregate, the :dispatch
            :rf.fx/handled slot and A's own :rf.event/v all redact"
    (let [captured (atom ::none)]
      (rf/reg-event ::target
        {:sensitive [[:secret]]}
        (fn [{:keys [db]} [_ {:keys [secret]}]]
          (reset! captured secret)
          {:db db}))
      (rf/reg-event ::parent
        (fn [_ _]
          {:fx [[:dispatch [::target {:secret pw-sentinel :email email}]]]}))
      (let [traces (record-traces!)]
        (rf/dispatch-sync [::parent])
        (rf/unregister-listener! :trace ::probe)
        (is (= pw-sentinel @captured)
            "the target handler received the RAW secret (egress-only redaction)")
        (when rf.interop/debug-enabled?
          (let [entries (for [ev    @traces
                              :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                              :when (vector? fx-vec)
                              [id args] fx-vec
                              :when (= :dispatch id)]
                          args)]
            (is (seq entries) "the do-fx aggregate carried the :dispatch entry")
            (doseq [args entries]
              (is (= [::target {:secret rf.privacy/redacted-sentinel :email email}] args)
                  "the nested target payload redacts in :rf.event/fx; :email survives")))
          (let [handled (filter #(= :dispatch (get-in % [:tags :rf.fx/id])) @traces)]
            (is (seq handled) ":dispatch emitted a :rf.fx/handled trace")
            (doseq [ev handled]
              (is (= rf.privacy/redacted-sentinel (get-in ev [:tags :rf.fx/args 1 :secret])))))
          (let [vs (->> @traces
                        (keep #(get-in % [:tags :rf.event/v]))
                        (filter #(= ::target (first %))))]
            (is (seq vs) "the target's own dispatched-event trace surfaced")
            (doseq [v vs]
              (is (= rf.privacy/redacted-sentinel (get-in v [1 :secret])))))
          (is (not (some #(leaks? pw-sentinel %) @traces))
              "no emitted trace event leaks the secret"))))))

(deftest live-managed-http-dynamic-flag-redacts-in-aggregate
  (testing "a :sensitive?-flagged :rf.http/managed request — the fx body receives
            the RAW body; the :rf.event/fx aggregate and the :rf.fx/handled slot
            both redact it"
    (let [http (atom [])]
      (rf/reg-event ::bare (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event ::issue
        (fn [_ _]
          {:fx [[:dispatch [::bare]]
                [:rf.http/managed
                 {:request    {:method :post
                               :url    "https://api.example.test/login"
                               :body   {:password pw-sentinel :email email}
                               :request-content-type :json
                               :sensitive? true}
                  :decode     :json
                  :on-success [::bare]
                  :on-failure [::bare]}]]}))
      (let [traces (record-traces!)]
        ;; A fn-value override stands in for the transport but keeps the
        ;; ORIGINAL fx-id on both slots, so the trace pipeline runs unchanged.
        (rf/dispatch-sync [::issue]
                          {:fx-overrides {:rf.http/managed
                                          (fn [_ args] (swap! http conj args))}})
        (rf/unregister-listener! :trace ::probe)
        (is (= pw-sentinel (get-in (first @http) [:request :body :password]))
            "the managed fx received the RAW password")
        (when rf.interop/debug-enabled?
          (let [entries (for [ev    @traces
                              :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                              :when (vector? fx-vec)
                              [id args] fx-vec
                              :when (= :rf.http/managed id)]
                          args)]
            (is (seq entries) "the do-fx aggregate carried the managed entry")
            (doseq [args entries]
              (is (= rf.privacy/redacted-sentinel (get-in args [:request :body])))
              (is (= "https://api.example.test/login" (get-in args [:request :url]))
                  "shape retained — the url survives")))
          (let [handled (filter #(= :rf.http/managed (get-in % [:tags :rf.fx/id])) @traces)]
            (is (seq handled) "the managed fx emitted a :rf.fx/handled trace")
            (doseq [ev handled]
              (is (= rf.privacy/redacted-sentinel
                     (get-in ev [:tags :rf.fx/args :request :body])))))
          (is (not (some #(leaks? pw-sentinel %) @traces))
              "no emitted trace event leaks the password"))))))
