(ns re-frame.http-unpaired-split-reply-test
  "Spec 014 §Reply addressing: `:on-success` and `:on-failure` address one
  reply TOGETHER. One supplied without its partner is refused at dispatch with
  `:rf.error/http-bad-reply-target` `:reason :unpaired-split`, before the
  request issues, so no outcome can be dropped for want of a handler. The live
  fx and the `with-request-stubs` stand-in refuse the same map identically.

  An explicit `nil` is present: it is the deliberate silence, so
  `{:on-success [:a] :on-failure nil}` and `{:reply-to nil}` are accepted, and
  a failure routed to that silence is dropped quietly, with no warning."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Nothing listens on port 1, so a live request here fails at once with a
;; transport error — the outcome a silenced failure branch drops.
(def ^:private url "http://127.0.0.1:1/x")

(defn- capturing
  "Run `(f traces)` with every trace event collected into the atom `traces`;
  return the events."
  [f]
  (let [traces (atom [])
        id     (keyword (gensym "unpaired-split"))]
    (rf.trace.tooling/register-listener! id #(swap! traces conj %))
    (try (f traces) @traces
         (finally (rf.trace.tooling/unregister-listener! id)))))

(defn- fire!
  "Dispatch one event whose only effect is `[:rf.http/managed args]`. Any
  reply lands under `[::replies]` in app-db."
  [args]
  (rf/reg-event ::replied
    {:doc "Records a delivered reply."}
    (fn [{:keys [db]} [_ reply]] {:db (update db ::replies (fnil conj []) reply)}))
  (rf/reg-event ::fire
    {:doc "Issues the request under test."}
    (fn [_ _] {:fx [[:rf.http/managed (assoc args :request {:method :get :url url})]]}))
  (rf/dispatch-sync [::fire]))

(defn- refusals
  "Each managed-HTTP fx handler exception in `traces`, as the effect that
  raised it and the refusal's id, reason and keys."
  [traces]
  (->> traces
       (filter #(= :rf.error/fx-handler-exception (:operation %)))
       (mapv (fn [ev]
               (let [data (ex-data (get-in ev [:tags :exception]))]
                 [(get-in ev [:tags :rf.fx/id])
                  (select-keys data [:rf.error/id :reason :keys])])))))

(defn- ops [traces op] (filterv #(= op (:operation %)) traces))

(defn- replies [] (::replies (rf/app-db-value :rf/default)))

(deftest an-unpaired-split-key-is-refused-before-the-request-issues
  (doseq [k [:on-success :on-failure]]
    (let [refusal {:rf.error/id :rf.error/http-bad-reply-target
                   :reason      :unpaired-split
                   :keys        [k]}]
      (testing (str k " alone, through the live fx")
        (let [traces (capturing (fn [_] (fire! {k [::replied]})))]
          (is (= [[:rf.http/managed refusal]] (refusals traces)))
          (is (empty? (ops traces :rf.http/issued)) "the request never issued")
          (is (empty? (rf.http.managed/in-flight-snapshot)))))
      (testing (str k " alone, through with-request-stubs")
        (let [traces (capturing
                       (fn [_]
                         (rf.http.test-support/with-request-stubs
                           {[:get url] {:reply {:ok {:n 1}}}}
                           (fn [] (fire! {k [::replied]})))))]
          (is (= [[:rf.test/managed-http-scope-stub refusal]] (refusals traces)))
          (is (nil? (replies)) "the stub synthesised no reply"))))))

(deftest explicit-nil-silence-is-accepted-without-a-warning
  (doseq [[label args] [["{:on-success [..] :on-failure nil}" {:on-success [::replied] :on-failure nil}]
                        ["{:reply-to nil}"                      {:reply-to nil}]]]
    (testing (str label ", through the live fx: the transport failure is dropped quietly")
      (let [traces (capturing
                     (fn [traces]
                       (fire! args)
                       (rf.test-support/poll-until #(seq (ops @traces :rf.http/replied))
                                                   {:timeout-ms 8000 :label "the request completes"})))]
        (is (empty? (refusals traces)))
        (is (= 1 (count (ops traces :rf.http/issued))) "the request issued")
        (is (= [:rf.http/transport] (mapv #(get-in % [:tags :error :kind]) (ops traces :rf.http/replied)))
            "its failure completed")
        (is (nil? (replies)) "and reached no handler")
        (is (empty? (filter #(= :warning (:op-type %)) traces)) "with no warning")))
    (testing (str label ", through with-request-stubs: a stubbed failure is dropped quietly")
      (let [traces (capturing
                     (fn [_]
                       (rf.http.test-support/with-request-stubs
                         {[:get url] {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}
                         (fn [] (fire! args)))))]
        (is (empty? (refusals traces)))
        (is (nil? (replies)))
        (is (empty? (filter #(= :warning (:op-type %)) traces)))))))
