(ns re-frame.resources-read-sub-egress-cljs-test
  "A held resource or mutation read sub's `:rf.sub/run` trace redacts the slots
  its owner declares, in both `:rf.sub/value` and `:rf.sub/prev-value`.

  A `reg-resource` / `reg-mutation` `:sensitive` declaration is lowered into
  the frame's elision registry at the entry's absolute runtime-db position,
  while a read sub returns the bare projection — the entry's data, the
  instance's result, the scoped keys they sit under. So the sub's trace is
  classified from the owner spec, re-rooted onto the sub's value, and a prior
  value stays redacted after an evict or a clear has dropped the lowered
  claims.

  The in-process reads stay raw: classification applies at egress only.

  Every assertion read off the trace bus sits inside a
  `(when rf.interop/debug-enabled? …)` arm: `trace/emit!` is dev
  instrumentation, and the negative census assertions would pass vacuously
  with no trace to read.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.elision :as rf.elision]
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.privacy :as rf.privacy]
   ;; load-bearing side-effecting requires: the facade registers the
   ;; :rf.resource/* + :rf.mutation/* events and subs and publishes the
   ;; read-sub egress projector; schemas binds the shared walker hooks.
   [re-frame.resources]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; ---- capturing transport --------------------------------------------------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Replace the real `:rf.http/managed` fx with one that records its args, so a
  test replies through the request's own `:on-success` continuation."
  [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(def ^:private ssn-1 "ajpcr-ssn-ONE")
(def ^:private ssn-2 "ajpcr-ssn-TWO")
(def ^:private token "ajpcr-token-SECRET")
(def ^:private acct "ajpcr-acct-SECRET")

;; ---- helpers --------------------------------------------------------------

(defn- reply!
  "Reply success with `value` through the last request's `:on-success`."
  [value]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value value})))

(defn- capture-traces
  "Run `f` and return every trace event emitted meanwhile, always unregistering
  the listener."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try
      (f)
      @captured
      (finally
        (rf/unregister-listener! :trace ::capture)))))

(defn- sub-runs
  [events]
  (filterv #(= :rf.sub/run (:operation %)) events))

(defn- last-run-tags
  "The tags of the LAST `:rf.sub/run` trace for `query-v` in `events`."
  [query-v events]
  (let [runs (filterv #(= query-v (get-in % [:tags :rf.sub/query-v])) (sub-runs events))]
    (is (seq runs) (str query-v " recomputed inside the window"))
    (:tags (peek runs))))

(defn- carries?
  [secret v]
  (str/includes? (pr-str v) secret))

(defn- run-values
  "Every `:rf.sub/value` / `:rf.sub/prev-value` the sub runs in `events`
  carry. A census over these rather than the whole run, where the query vector
  is the sub's identity and carries the payload it was called with."
  [events]
  (mapcat (comp (juxt :rf.sub/value :rf.sub/prev-value) :tags) (sub-runs events)))

(defn- hold
  "Subscribe to every query vector in `qvs` and return a thunk reading them
  all, keyed by query vector."
  [qvs]
  (let [held (into {} (map (fn [qv] [qv (rf/subscribe qv)])) qvs)]
    (fn [] (into {} (map (fn [[qv r]] [qv @r])) held))))

(defn- resource-claims
  "The frame's `:sensitive` declarations under the resource entry table."
  []
  (filterv #(= :rf.runtime/resources (first %))
           (keys (rf.elision/sensitive-declarations :rf/default))))

(defn- mutation-claims
  []
  (filterv #(= :rf.runtime/mutations (first %))
           (keys (rf.elision/sensitive-declarations :rf/default))))

;; ===========================================================================
;; 1. Resource read subs
;; ===========================================================================

(defn- reg-profile!
  "`:acct/profile` declares its data's `:ssn` sensitive; `:name` is undeclared."
  []
  (rf/reg-resource :acct/profile
    {:scope         :rf.scope/global
     :sensitive     [[:data :ssn]]
     :params-schema [:map [:id :string]]}
    (fn [{:keys [id]} _] {:request {:method :get :url (str "/profile/" id)}})))

(def ^:private profile-q {:resource :acct/profile :scope :rf.scope/global :params {:id "u1"}})

(deftest a-resource-read-subs-data-is-redacted-across-load-refetch-and-evict
  (reg-profile!)
  (let [state-q [:rf/resource profile-q]
        data-q  [:rf.resource/data profile-q]
        read!   (hold [state-q data-q])
        _       (read!)
        load    (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/ensure (assoc profile-q :owner [:app :profile])])
                    (read!)
                    (reply! {:name "Ann" :ssn ssn-1})
                    (read!)))
        loaded  (read!)
        refetch (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/refetch profile-q])
                    (read!)
                    (reply! {:name "Ann" :ssn ssn-2})
                    (read!)))
        claims  (resource-claims)
        evict   (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :profile]}])
                    (rf/dispatch-sync [:rf.resource/remove profile-q])
                    (read!)))]
    (testing "the in-process reads stay raw"
      (is (= ssn-1 (get-in loaded [state-q :data :ssn])))
      (is (= {:name "Ann" :ssn ssn-1} (get loaded data-q))))
    (testing "the evict drops the entry's lowered claims from the registry"
      (is (seq claims) "control: the loaded entry's claim is in the registry")
      (is (empty? (resource-claims))))
    (when rf.interop/debug-enabled?
      (testing "the load: the declared slot redacts, its undeclared sibling rides"
        (let [state (:rf.sub/value (last-run-tags state-q load))
              data  (:rf.sub/value (last-run-tags data-q load))]
          (is (= :loaded (:status state)))
          (is (= rf.privacy/redacted-sentinel (get-in state [:data :ssn])))
          (is (= "Ann" (get-in state [:data :name])))
          (is (= {:name "Ann" :ssn rf.privacy/redacted-sentinel} data))))
      (testing "the refetch: the prior data keeps its classification too"
        (let [state (last-run-tags state-q refetch)
              data  (last-run-tags data-q refetch)]
          (is (= rf.privacy/redacted-sentinel (get-in state [:rf.sub/prev-value :data :ssn])))
          (is (= rf.privacy/redacted-sentinel (get-in state [:rf.sub/value :data :ssn])))
          (is (= rf.privacy/redacted-sentinel (get-in data [:rf.sub/prev-value :ssn])))
          (is (= rf.privacy/redacted-sentinel (get-in data [:rf.sub/value :ssn])))))
      (testing "the evict: the prior data stays redacted after its claims are gone"
        (let [state (last-run-tags state-q evict)
              data  (last-run-tags data-q evict)]
          (is (= :idle (get-in state [:rf.sub/value :status])))
          (is (= rf.privacy/redacted-sentinel (get-in state [:rf.sub/prev-value :data :ssn])))
          (is (= {:name "Ann" :ssn rf.privacy/redacted-sentinel} (:rf.sub/prev-value data)))))
      (testing "no sub run in any window carries either secret"
        (doseq [window [load refetch evict]]
          (is (seq (sub-runs window)))
          (is (not (carries? ssn-1 (sub-runs window))))
          (is (not (carries? ssn-2 (sub-runs window)))))))))

(deftest a-resource-declaring-nothing-rides-verbatim
  (testing "control: an undeclared resource's data reaches the trace unchanged"
    (rf/reg-resource :plain/profile
      {:scope :rf.scope/global :params-schema [:map [:id :string]]}
      (fn [{:keys [id]} _] {:request {:method :get :url (str "/plain/" id)}}))
    (let [q      {:resource :plain/profile :scope :rf.scope/global :params {:id "u1"}}
          data-q [:rf.resource/data q]
          read!  (hold [data-q])
          _      (read!)
          events (capture-traces
                   (fn []
                     (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :plain])])
                     (reply! {:name "Ann" :ssn ssn-1})
                     (read!)))]
      (when rf.interop/debug-enabled?
        (is (= {:name "Ann" :ssn ssn-1} (:rf.sub/value (last-run-tags data-q events))))))))

(deftest a-coarse-sensitive-resource-redacts-its-whole-data-projection
  (rf/reg-resource :sealed/profile
    {:scope :rf.scope/global :sensitive? true :params-schema [:map [:id :string]]}
    (fn [{:keys [id]} _] {:request {:method :get :url (str "/sealed/" id)}}))
  (let [q      {:resource :sealed/profile :scope :rf.scope/global :params {:id "u1"}}
        data-q [:rf.resource/data q]
        read!  (hold [data-q])
        _      (read!)
        events (capture-traces
                 (fn []
                   (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :sealed])])
                   (reply! {:name "Ann" :ssn ssn-1})
                   (read!)))]
    (is (= ssn-1 (:ssn (get (read!) data-q))) "the in-process read stays raw")
    (when rf.interop/debug-enabled?
      (is (= rf.privacy/redacted-sentinel (:rf.sub/value (last-run-tags data-q events)))))))

(deftest a-kept-previous-projection-redacts-its-data-and-key
  (testing "`:keep-previous?` projects the prior key and its data into
            `:rf/resource`, and both carry what the owner declares"
    (rf/reg-resource :acct/page
      {:scope         :rf.scope/global
       :sensitive     [[:params :acct] [:data :ssn]]
       :params-schema [:map [:acct :string] [:page :int]]}
      (fn [{:keys [page]} _] {:request {:method :get :url (str "/page/" page)}}))
    (let [q1      {:resource :acct/page :scope :rf.scope/global :params {:acct acct :page 1}}
          q2      (assoc-in q1 [:params :page] 2)
          state-q [:rf/resource q2]]
      (rf/dispatch-sync [:rf.resource/ensure (assoc q1 :owner [:app :page])])
      (reply! {:ssn ssn-1 :page 1})
      (let [read!  (hold [state-q])
            _      (read!)
            events (capture-traces
                     (fn []
                       (rf/dispatch-sync [:rf.resource/ensure
                                          (assoc q2 :owner [:app :page] :keep-previous? true)])
                       (read!)))
            live   (get (read!) state-q)]
        (is (:previous? live) "control: the new key shows the prior key's data")
        (is (= ssn-1 (get-in live [:previous-data :ssn])) "the in-process read stays raw")
        (when rf.interop/debug-enabled?
          (let [v (:rf.sub/value (last-run-tags state-q events))]
            (is (= rf.privacy/redacted-sentinel (get-in v [:previous-data :ssn])))
            (is (= 1 (get-in v [:previous-data :page])) "an undeclared data slot rides")
            (is (= rf.privacy/redacted-sentinel (get-in v [:previous-key 2 :acct])))
            (is (= 1 (get-in v [:previous-key 2 :page])) "an undeclared param rides")
            (is (= :acct/page (get-in v [:previous-key 1])) "the resource id rides")
            (is (not (carries? acct (run-values events))))
            (is (not (carries? ssn-1 (run-values events))))))))))

;; ===========================================================================
;; 2. Mutation read subs
;; ===========================================================================

(deftest a-mutation-read-subs-result-is-redacted-across-success-and-clear
  (rf/reg-mutation :m/issue
    {:params-schema [:map [:slug :string]]
     :sensitive     [[:data :token]]}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/issue/" slug)}}))
  (let [state-q  [:rf/mutation {:instance :i1}]
        result-q [:rf.mutation/result {:instance :i1}]
        read!    (hold [state-q result-q])
        _        (read!)
        settle   (capture-traces
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :m/issue :params {:slug "w"} :instance :i1}])
                     (read!)
                     (reply! {:token token :ok true})
                     (read!)))
        settled  (read!)
        claims   (mutation-claims)
        clear    (capture-traces
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/clear {:instance :i1}])
                     (read!)))]
    (testing "the in-process reads stay raw"
      (is (= token (get-in settled [state-q :result :token])))
      (is (= token (:token (get settled result-q)))))
    (testing "the clear drops the instance's lowered claims from the registry"
      (is (seq claims) "control: the settled instance's claim is in the registry")
      (is (empty? (mutation-claims))))
    (when rf.interop/debug-enabled?
      (testing "the settle: the declared slot redacts, its undeclared sibling rides"
        (let [state  (:rf.sub/value (last-run-tags state-q settle))
              result (:rf.sub/value (last-run-tags result-q settle))]
          (is (= :success (:status state)))
          (is (= rf.privacy/redacted-sentinel (get-in state [:result :token])))
          (is (true? (get-in state [:result :ok])))
          (is (= {:token rf.privacy/redacted-sentinel :ok true} result))))
      (testing "the clear: the prior result stays redacted after its claims are gone"
        (let [state  (last-run-tags state-q clear)
              result (last-run-tags result-q clear)]
          (is (= :idle (get-in state [:rf.sub/value :status])))
          (is (= rf.privacy/redacted-sentinel (get-in state [:rf.sub/prev-value :result :token])))
          (is (= {:token rf.privacy/redacted-sentinel :ok true} (:rf.sub/prev-value result)))))
      (testing "no sub run in either window carries the secret"
        (doseq [window [settle clear]]
          (is (seq (sub-runs window)))
          (is (not (carries? token (sub-runs window)))))))))

(deftest a-mutation-states-affected-keys-carry-their-owners-declarations
  (rf/reg-resource :acct/summary
    {:scope         :rf.scope/global
     :sensitive     [[:params :acct]]
     :params-schema [:map [:acct :string]]
     :tags          (fn [{:keys [acct]} _] #{[:summary acct]})}
    (fn [_ _] {:request {:method :get :url "/summary"}}))
  (rf/dispatch-sync [:rf.resource/ensure {:resource :acct/summary :scope :rf.scope/global
                                          :params {:acct acct} :owner [:app :s]}])
  (reply! {:total 1})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :s]}])
  (rf/reg-mutation :m/touch
    {:params-schema [:map [:acct :string]]
     :sensitive     [[:params :acct]]
     :invalidates   (fn [{:keys [acct]} _] #{[:summary acct]})}
    (fn [_ _] {:request {:method :post :url "/touch"}}))
  (let [state-q [:rf/mutation {:instance :t1}]
        read!   (hold [state-q])
        _       (read!)
        events  (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.mutation/execute
                                       {:mutation :m/touch :params {:acct acct} :instance :t1}])
                    (reply! {:ok true})
                    (read!)))
        live    (get (read!) state-q)]
    (is (= [:rf.scope/global :acct/summary {:acct acct}] (first (:affected-keys live)))
        "control: the invalidated key is affected, raw in-process")
    (when rf.interop/debug-enabled?
      (let [k (first (:affected-keys (:rf.sub/value (last-run-tags state-q events))))]
        (is (= :acct/summary (second k)) "the resource id rides")
        (is (= rf.privacy/redacted-sentinel (get-in k [2 :acct]))))
      (is (not (carries? acct (sub-runs events)))))))
