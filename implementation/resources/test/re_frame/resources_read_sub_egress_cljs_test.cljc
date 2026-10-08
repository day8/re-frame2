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

  The in-process reads stay raw: classification applies at egress only. Every
  assertion read off the trace bus sits inside a
  `(when rf.interop/debug-enabled? …)` arm: `trace/emit!` is dev
  instrumentation, and the negative census assertions would pass vacuously
  with no trace to read."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
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

(def ^:private redacted rf.privacy/redacted-sentinel)

(defn- reply! [value]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value value})))

(defn- capture-traces
  "Run `f` and return every trace event emitted meanwhile."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try
      (f)
      @captured
      (finally
        (rf/unregister-listener! :trace ::capture)))))

(defn- sub-runs [events]
  (filterv #(= :rf.sub/run (:operation %)) events))

(defn- last-run-tags
  "The tags of the LAST `:rf.sub/run` trace for `query-v` in `events`."
  [query-v events]
  (let [runs (filterv #(= query-v (get-in % [:tags :rf.sub/query-v])) (sub-runs events))]
    (is (seq runs) (str query-v " recomputed inside the window"))
    (:tags (peek runs))))

(defn- carries? [secret v]
  (str/includes? (pr-str v) secret))

(defn- run-values
  "Every `:rf.sub/value` / `:rf.sub/prev-value` the sub runs in `events`
  carry: a census over these rather than the whole run, whose query vector is
  the sub's identity and carries the payload it was called with."
  [events]
  (mapcat (comp (juxt :rf.sub/value :rf.sub/prev-value) :tags) (sub-runs events)))

(defn- clean-windows?
  "Every window has sub runs, and none of them carries any of `secrets`."
  [windows secrets]
  (every? (fn [window]
            (and (seq (sub-runs window))
                 (not-any? #(carries? % (sub-runs window)) secrets)))
          windows))

(defn- hold
  "Subscribe to every query vector in `qvs` and return a thunk reading them
  all, keyed by query vector."
  [qvs]
  (let [held (into {} (map (fn [qv] [qv (rf/subscribe qv)])) qvs)]
    (fn [] (into {} (map (fn [[qv r]] [qv @r])) held))))

(defn- claims-under
  "The frame's `:sensitive` declarations under the runtime table `root`."
  [root]
  (filterv #(= root (first %)) (keys (rf.elision/sensitive-declarations :rf/default))))

;; ===========================================================================
;; resource read subs
;; ===========================================================================

(def ^:private profile-q {:resource :acct/profile :scope :rf.scope/global :params {:id "u1"}})

(deftest a-resource-read-subs-data-is-redacted-across-load-refetch-and-evict
  (rf/reg-resource :acct/profile
    {:scope         :rf.scope/global
     :sensitive     [[:data :ssn]]
     :params-schema [:map [:id :string]]}
    (fn [{:keys [id]} _] {:request {:method :get :url (str "/profile/" id)}}))
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
        claims  (claims-under :rf.runtime/resources)
        evict   (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :profile]}])
                    (rf/dispatch-sync [:rf.resource/remove profile-q])
                    (read!)))
        ann     {:name "Ann" :ssn redacted}]
    (is (= [ssn-1 {:name "Ann" :ssn ssn-1}] [(get-in loaded [state-q :data :ssn]) (get loaded data-q)])
        "the in-process reads stay raw")
    (is (= [true []] [(boolean (seq claims)) (claims-under :rf.runtime/resources)])
        "the evict drops the loaded entry's lowered claim from the registry")
    (when rf.interop/debug-enabled?
      (let [state (:rf.sub/value (last-run-tags state-q load))]
        (is (= [:loaded ann ann]
               [(:status state) (:data state) (:rf.sub/value (last-run-tags data-q load))])
            "the load: the declared slot redacts, its undeclared sibling rides"))
      (let [state (last-run-tags state-q refetch)
            data  (last-run-tags data-q refetch)]
        (is (= [ann ann ann ann]
               [(get-in state [:rf.sub/prev-value :data]) (get-in state [:rf.sub/value :data])
                (:rf.sub/prev-value data) (:rf.sub/value data)])
            "the refetch: the prior data keeps its classification too"))
      (let [state (last-run-tags state-q evict)]
        (is (= [:idle ann ann]
               [(get-in state [:rf.sub/value :status]) (get-in state [:rf.sub/prev-value :data])
                (:rf.sub/prev-value (last-run-tags data-q evict))])
            "the evict: the prior data stays redacted after its claims are gone"))
      (is (clean-windows? [load refetch evict] [ssn-1 ssn-2])
          "no sub run in any window carries either secret"))))

(deftest a-resource-declaring-nothing-rides-verbatim
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
      (is (= {:name "Ann" :ssn ssn-1} (:rf.sub/value (last-run-tags data-q events)))
          "control: an undeclared resource's data reaches the trace unchanged"))))

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
      (is (= redacted (:rf.sub/value (last-run-tags data-q events)))))))

(deftest a-kept-previous-projection-redacts-its-data-and-key
  ;; `:keep-previous?` projects the prior key and its data into `:rf/resource`,
  ;; and both carry what the owner declares
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
      (is (= [true ssn-1] [(:previous? live) (get-in live [:previous-data :ssn])])
          "the new key shows the prior key's data, raw in process")
      (when rf.interop/debug-enabled?
        (let [v (:rf.sub/value (last-run-tags state-q events))]
          (is (= [{:ssn redacted :page 1} :acct/page {:acct redacted :page 1} false false]
                 [(select-keys (:previous-data v) [:ssn :page]) (get-in v [:previous-key 1])
                  (select-keys (get-in v [:previous-key 2]) [:acct :page])
                  (carries? acct (run-values events)) (carries? ssn-1 (run-values events))])
              "the declared data slot and param redact; their siblings and the resource id ride"))))))

;; ===========================================================================
;; mutation read subs
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
        claims   (claims-under :rf.runtime/mutations)
        clear    (capture-traces
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/clear {:instance :i1}])
                     (read!)))
        result   {:token redacted :ok true}]
    (is (= [token token] [(get-in settled [state-q :result :token]) (:token (get settled result-q))])
        "the in-process reads stay raw")
    (is (= [true []] [(boolean (seq claims)) (claims-under :rf.runtime/mutations)])
        "the clear drops the settled instance's lowered claim from the registry")
    (when rf.interop/debug-enabled?
      (let [state (:rf.sub/value (last-run-tags state-q settle))]
        (is (= [:success result result]
               [(:status state) (:result state) (:rf.sub/value (last-run-tags result-q settle))])
            "the settle: the declared slot redacts, its undeclared sibling rides"))
      (let [state (last-run-tags state-q clear)]
        (is (= [:idle result result]
               [(get-in state [:rf.sub/value :status]) (get-in state [:rf.sub/prev-value :result])
                (:rf.sub/prev-value (last-run-tags result-q clear))])
            "the clear: the prior result stays redacted after its claims are gone"))
      (is (clean-windows? [settle clear] [token]) "no sub run in either window carries the secret"))))

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
        (is (= [:acct/summary redacted false]
               [(second k) (get-in k [2 :acct]) (carries? acct (sub-runs events))])
            "the affected key carries its owner's declaration, the resource id riding")))))
