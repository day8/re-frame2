(ns re-frame.resources-read-sub-egress-cljs-test
  "A held mutation read sub's `:rf.sub/run` trace redacts the slots
  its owner declares, in both `:rf.sub/value` and `:rf.sub/prev-value`.

  A `reg-resource` / `reg-mutation` `:sensitive` declaration is claimed at
  the entry's or instance's absolute runtime-db position while it is present,
  while a read sub returns the bare projection — the entry's data and error
  channels, the instance's result, the scoped keys they sit under. So the
  sub's trace is classified from the owner spec, re-rooted onto the sub's
  value, a resource's coarse claim covering every entry value slot the value
  carries, and a prior value stays redacted after an evict or a clear has
  removed the instance and its claims.

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

(def ^:private token "ajpcr-token-SECRET")
(def ^:private acct "ajpcr-acct-SECRET")

(def ^:private redacted rf.privacy/redacted-sentinel)

(defn- reply! [value]
  (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value value})))

(defn- fail! [error]
  (rf/dispatch-sync (conj (:on-failure @last-managed-args) {:status :error :error error})))

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
        "the settled instance's claim goes with the clear")
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

(deftest a-mutation-states-affected-keys-carry-their-owners-claims
  (rf/reg-resource :acct/summary
    {:scope         :rf.scope/global
     :sensitive?    true
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
        (is (= [:acct/summary true false]
               [(second k) (contains? (nth k 2) :rf/redacted) (carries? acct (sub-runs events))])
            "the affected key carries its owner's coarse claim, the resource id riding")))))

(def ^:private echoed "rkcsf-alice@example.com")

(defn- failed-first-load
  "Register resource `id` under `spec`, hold its `:rf/resource` and
  `:rf.resource/error` reads, and fail its first load with `error`. Returns
  `{:live <in-process reads> :off-box [view-error error-sub carries-echo?]}`,
  the off-box half read off the failure's sub runs."
  [id spec error]
  (rf/reg-resource id
    (merge {:scope :rf.scope/global :params-schema [:map [:form :string]]} spec)
    (fn [_ _] {:request {:method :post :url "/signup"}}))
  (let [q       {:resource id :scope :rf.scope/global :params {:form "signup"}}
        state-q [:rf/resource q]
        error-q [:rf.resource/error q]
        read!   (hold [state-q error-q])
        _       (read!)
        window  (capture-traces
                  (fn []
                    (rf/dispatch-sync [:rf.resource/ensure (assoc q :owner [:app :signup])])
                    (fail! error)
                    (read!)))
        live    (read!)]
    {:live    [(get-in live [state-q :error]) (get live error-q)]
     :off-box (when rf.interop/debug-enabled?
                [(get-in (last-run-tags state-q window) [:rf.sub/value :error])
                 (:rf.sub/value (last-run-tags error-q window))
                 (carries? echoed (sub-runs window))])}))

(deftest a-coarse-resources-read-sub-error-body-is-redacted-off-box
  ;; The coarse claim covers the whole entry, so the error channel a read sub
  ;; carries beside the data redacts with it: a 4xx body routinely echoes the
  ;; value the user submitted.
  (let [error  {:kind        :rf.http/http-4xx
                :http-status 422
                :body        {:email [(str echoed " is already registered")]}}
        secret (failed-first-load :signup/secret {:sensitive? true} error)
        plain  (failed-first-load :signup/plain {} error)]
    (is (= [error error] (:live secret) (:live plain)) "the in-process reads stay raw")
    (when rf.interop/debug-enabled?
      (is (= [redacted redacted false] (:off-box secret))
          "the sensitive resource's error body arrives redacted on the view and the focused read")
      (is (= [error error true] (:off-box plain))
          "control: an undeclared resource's error body rides raw"))))
