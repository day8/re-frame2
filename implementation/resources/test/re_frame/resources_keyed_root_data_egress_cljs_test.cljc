(ns re-frame.resources-keyed-root-data-egress-cljs-test
  "A projection whose top level is keyed by id redacts its declared slot under
  every key on each egress carrier, as the durable entry does.

  A declared path rides a map key only once its first named segment has
  matched. The durable entry walks `[:data :email]` with `:data` intact, so
  `{\"u1\" {:email …}}` redacts there. A read sub whose value IS the data
  projection (`:rf.resource/data`, `:previous-data`, `:pages`, a feed's merged
  items, `:rf.mutation/result`) and a scoped key's params component carry no
  such slot, so their walks keep the declaration's anchor: the value is walked
  under the slot the owner declared against. The subs that carry the
  projection under a named slot (`:rf/resource`'s `:data`, `:rf/mutation`'s
  `:result`) are the controls.

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
   [re-frame.fx :as rf.fx]
   [re-frame.interop :as rf.interop]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
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

(def ^:private redacted rf.privacy/redacted-sentinel)

(def ^:private email-1 "keyed-root-email-ONE")
(def ^:private email-2 "keyed-root-email-TWO")

(def ^:private users
  {"u1" {:name "Ann" :email email-1}
   "u2" {:name "Bo" :email email-2}})

(def ^:private redacted-users
  {"u1" {:name "Ann" :email redacted}
   "u2" {:name "Bo" :email redacted}})

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

(defn- last-run-value
  "The `:rf.sub/value` of the LAST `:rf.sub/run` trace for `query-v` in `events`."
  [query-v events]
  (let [runs (filterv #(= query-v (get-in % [:tags :rf.sub/query-v])) (sub-runs events))]
    (is (seq runs) (str query-v " recomputed inside the window"))
    (get-in (peek runs) [:tags :rf.sub/value])))

(defn- carries-a-secret? [events]
  (let [s (pr-str (mapv (comp (juxt :rf.sub/value :rf.sub/prev-value) :tags) (sub-runs events)))]
    (boolean (some #(str/includes? s %) [email-1 email-2]))))

(defn- hold
  "Subscribe to every query vector in `qvs` and return a thunk reading them
  all, keyed by query vector."
  [qvs]
  (let [held (into {} (map (fn [qv] [qv (rf/subscribe qv)])) qvs)]
    (fn [] (into {} (map (fn [[qv r]] [qv @r])) held))))

(defn- durable-data
  "The durable entry's `:data` for query `q`, projected off-box."
  [q data]
  (rf.resources.classification/project-entry-data
    data
    (rf.resources.state/key-id
      (rf.resources.state/scoped-resource-key (:scope q) (:resource q) (:params q)))
    :rf/default
    :rf.egress/off-box-tool))

(deftest a-root-keyed-data-projection-redacts-like-the-durable-entry
  (rf/reg-resource :keyed/users
    {:scope         :rf.scope/global
     :sensitive     [[:data :email]]
     :params-schema [:map [:page :int]]}
    (fn [{:keys [page]} _] {:request {:method :get :url (str "/users/" page)}}))
  (let [q1      {:resource :keyed/users :scope :rf.scope/global :params {:page 1}}
        q2      (assoc-in q1 [:params :page] 2)
        data-q  [:rf.resource/data q1]
        prev-q  [:rf.resource/previous-data q2]
        state-q [:rf/resource q2]
        read!   (hold [data-q prev-q state-q])
        _       (read!)
        events  (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/ensure (assoc q1 :owner [:app :users])])
                    (reply! users)
                    (read!)
                    (rf/dispatch-sync [:rf.resource/ensure
                                       (assoc q2 :owner [:app :users] :keep-previous? true)])
                    (read!)))
        durable (durable-data q1 users)]
    (is (= [users users] [(get (read!) data-q) (get (read!) prev-q)]) "the in-process reads stay raw")
    (is (= redacted-users durable) "the durable entry redacts the slot under every key")
    (when rf.interop/debug-enabled?
      (is (= [durable durable durable]
             [(:previous-data (last-run-value state-q events))
              (last-run-value data-q events)
              (last-run-value prev-q events)])
          "the anchored :rf/resource slot (the control), :rf.resource/data and :previous-data agree")
      (is (false? (carries-a-secret? events)) "no sub run carries either secret"))))

(deftest a-feeds-root-keyed-pages-redact-like-the-durable-entry
  (rf/reg-resource :keyed/feed
    {:scope           :rf.scope/global
     :infinite        true
     :params-schema   [:map]
     :page->items     (fn [page] (vec (vals page)))
     :next-page-param (fn [_last-page _pages] nil)
     :sensitive       [[:data :email]]}
    (fn [_ _] {:request {:method :get :url "/feed"}}))
  (let [q       {:resource :keyed/feed :scope :rf.scope/global :params {}}
        pages-q [:rf.resource/pages q]
        items-q [:rf.resource/items q]
        state-q [:rf.resource/infinite-state q]
        read!   (hold [pages-q items-q state-q])
        _       (read!)
        events  (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :feed])])
                    (reply! users)
                    (read!)))
        durable (durable-data q [users])
        items   [{:name "Ann" :email redacted} {:name "Bo" :email redacted}]]
    (is (= [[users] (vec (vals users))] [(get (read!) pages-q) (get (read!) items-q)])
        "the in-process reads stay raw")
    (is (= [redacted-users] durable) "the durable entry redacts the slot on every page")
    (when rf.interop/debug-enabled?
      (let [state (last-run-value state-q events)]
        (is (= [durable durable items items]
               [(:pages state) (last-run-value pages-q events)
                (:items state) (last-run-value items-q events)])
            "the anchored :pages slot (the control), :rf.resource/pages and both merged lists agree"))
      (is (false? (carries-a-secret? events)) "no sub run carries either secret"))))

(deftest a-mutations-root-keyed-result-redacts-on-every-read-sub
  (rf/reg-mutation :keyed/issue
    {:params-schema [:map [:slug :string]]
     :sensitive     [[:data :email]]}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/issue/" slug)}}))
  (let [state-q  [:rf/mutation {:instance :i1}]
        result-q [:rf.mutation/result {:instance :i1}]
        read!    (hold [state-q result-q])
        _        (read!)
        events   (capture-traces
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :keyed/issue :params {:slug "w"} :instance :i1}])
                     (read!)
                     (reply! users)
                     (read!)))]
    (is (= users (get (read!) result-q)) "the in-process read stays raw")
    (when rf.interop/debug-enabled?
      (is (= [redacted-users redacted-users]
             [(:result (last-run-value state-q events)) (last-run-value result-q events)])
          "the anchored :rf/mutation slot (the control) and :rf.mutation/result agree")
      (is (false? (carries-a-secret? events)) "no sub run carries either secret"))))

;; A scoped key's params component, keyed by id at its top level.

(def ^:private keyed-params-key
  [:rf.scope/global :keyed/by-acct {"a" {:acct email-1} "b" {:acct email-2}}])

(def ^:private redacted-params-key
  [:rf.scope/global :keyed/by-acct {"a" {:acct redacted} "b" {:acct redacted}}])

(deftest a-root-keyed-params-component-redacts-its-declared-slot-in-the-key
  (is (= redacted-params-key
         (rf.resources.trace-egress/redact-key-declarations
           keyed-params-key :serialize {:sensitive [[:params :acct]]}))))

(deftest a-recovered-key-claim-reaches-a-root-keyed-params-component
  ;; the claim the registry holds for the key's entry, lowered at
  ;; `[… :resource/key 2 :acct]`
  (let [claim      (into (rf.resources.state/entry-path keyed-params-key) [:resource/key 2 :acct])
        runtime-db {:rf.runtime/elision {:sensitive-declarations {claim {:sensitive? true}}
                                         :declarations           {}}}]
    (is (= redacted-params-key
           (#'rf.resources.trace-egress/recover-key keyed-params-key keyed-params-key runtime-db)))))
