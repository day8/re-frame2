(ns re-frame.resources-prior-value-reregistration-cljs-test
  "A held resource or mutation read sub's value, prior and current, keeps the
  classification it was computed under when its owner is re-registered
  without its declaration.

  The read-sub egress projector reads the owner spec as it stands now, so on
  that alone the re-registered owner's empty declaration would ship data
  loaded under the old one raw. A value also takes the claims the elision
  registry holds in the inputs it was computed from — the prior inputs for
  `:rf.sub/prev-value`, the live runtime-db for `:rf.sub/value` — unioned
  with the current spec's: the entry's or instance's `:data` claims, and each
  carried scoped key's `:resource/key` claims.

  Two boundaries are pinned here so a change to either is visible: the
  registry never holds a coarse `:sensitive?` root claim, and it holds the
  lowered claims only until the next resource commit reconciles it against
  the current registrations.

  Every scenario runs re-registered and unchanged, the unchanged run as the
  control. For a resource's prior data across a remove and a mutation's prior
  result across a clear, the unchanged control is the read-sub egress suite's
  load/refetch/evict and success/clear tests.

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

(def ^:private ssn-1 "lafas-ssn-ONE")
(def ^:private ssn-2 "lafas-ssn-TWO")
(def ^:private token "lafas-token-SECRET")
(def ^:private acct "lafas-acct-SECRET")

(def ^:private redacted rf.privacy/redacted-sentinel)

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

(defn- prev-values
  "Every `:rf.sub/prev-value` the sub runs in `events` carry."
  [events]
  (map (comp :rf.sub/prev-value :tags) (sub-runs events)))

(defn- hold
  "Subscribe to every query vector in `qvs` and return a thunk reading them
  all, keyed by query vector."
  [qvs]
  (let [held (into {} (map (fn [qv] [qv (rf/subscribe qv)])) qvs)]
    (fn [] (into {} (map (fn [[qv r]] [qv @r])) held))))

;; ===========================================================================
;; 1. A resource's prior data, across a remove
;; ===========================================================================

(defn- reg-profile!
  [spec]
  (rf/reg-resource :lafas/profile
    (merge {:scope :rf.scope/global :params-schema [:map [:id :string]]} spec)
    (fn [{:keys [id]} _] {:request {:method :get :url (str "/profile/" id)}})))

(def ^:private profile-q {:resource :lafas/profile :scope :rf.scope/global :params {:id "u1"}})

(defn- remove-profile
  "Register the profile under `declaration`, load it, re-register it under
  `re-registered` (when given), then release and remove the entry. Returns
  `[live-before-remove remove-window]`."
  [declaration re-registered]
  (reg-profile! declaration)
  (let [state-q [:rf/resource profile-q]
        data-q  [:rf.resource/data profile-q]
        read!   (hold [state-q data-q])]
    (read!)
    (rf/dispatch-sync [:rf.resource/ensure (assoc profile-q :owner [:app :profile])])
    (reply! {:name "Ann" :ssn ssn-1})
    (let [loaded (read!)]
      (when re-registered (reg-profile! re-registered))
      [loaded
       (capture-traces
         (fn []
           (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :profile]}])
           (rf/dispatch-sync [:rf.resource/remove profile-q])
           (read!)))])))

(defn- assert-prior-profile-redacted
  [re-registered]
  (let [state-q         [:rf/resource profile-q]
        data-q          [:rf.resource/data profile-q]
        [loaded window] (remove-profile {:sensitive [[:data :ssn]]} re-registered)]
    (is (= ssn-1 (get-in loaded [data-q :ssn])) "the in-process read stays raw")
    (when rf.interop/debug-enabled?
      (let [state (last-run-tags state-q window)
            data  (last-run-tags data-q window)]
        (is (= :idle (get-in state [:rf.sub/value :status])) "the entry is gone")
        (is (= {:name "Ann" :ssn redacted} (get-in state [:rf.sub/prev-value :data])))
        (is (= {:name "Ann" :ssn redacted} (:rf.sub/prev-value data))))
      (is (seq (sub-runs window)))
      (is (not (carries? ssn-1 (sub-runs window)))))))

(deftest a-re-registered-resources-prior-data-keeps-its-declaration-across-a-remove
  (testing "the owner is re-registered without its declaration before the remove"
    (assert-prior-profile-redacted {})))

;; ===========================================================================
;; 2. A mutation's prior result, across a clear
;; ===========================================================================

(defn- reg-issue!
  [spec]
  (rf/reg-mutation :lafas/issue
    (merge {:params-schema [:map [:slug :string]]} spec)
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/issue/" slug)}})))

(defn- assert-prior-result-redacted
  [re-registered]
  (reg-issue! {:sensitive [[:data :token]]})
  (let [state-q  [:rf/mutation {:instance :p1}]
        result-q [:rf.mutation/result {:instance :p1}]
        read!    (hold [state-q result-q])
        _        (read!)
        _        (rf/dispatch-sync [:rf.mutation/execute
                                    {:mutation :lafas/issue :params {:slug "w"} :instance :p1}])
        _        (reply! {:token token :ok true})
        settled  (read!)
        _        (when re-registered (reg-issue! re-registered))
        window   (capture-traces
                   (fn []
                     (rf/dispatch-sync [:rf.mutation/clear {:instance :p1}])
                     (read!)))]
    (is (= token (get-in settled [result-q :token])) "the in-process read stays raw")
    (when rf.interop/debug-enabled?
      (let [state  (last-run-tags state-q window)
            result (last-run-tags result-q window)]
        (is (= :idle (get-in state [:rf.sub/value :status])) "the instance is gone")
        (is (= {:token redacted :ok true} (get-in state [:rf.sub/prev-value :result])))
        (is (= {:token redacted :ok true} (:rf.sub/prev-value result))))
      (is (seq (sub-runs window)))
      (is (not (carries? token (sub-runs window)))))))

(deftest a-re-registered-mutations-prior-result-keeps-its-declaration-across-a-clear
  (testing "the owner is re-registered without its declaration before the clear"
    (assert-prior-result-redacted {})))

;; ===========================================================================
;; 3. A carried key: `:rf/resource`'s kept-previous projection
;; ===========================================================================

(defn- assert-prior-previous-projection-redacted
  [re-registered]
  (let [spec    {:scope         :rf.scope/global
                 :params-schema [:map [:acct :string] [:page :int]]}
        reg!    (fn [decl]
                  (rf/reg-resource :lafas/page (merge spec decl)
                    (fn [{:keys [page]} _] {:request {:method :get :url (str "/page/" page)}})))
        q1      {:resource :lafas/page :scope :rf.scope/global :params {:acct acct :page 1}}
        q2      (assoc-in q1 [:params :page] 2)
        state-q [:rf/resource q2]]
    (reg! {:sensitive [[:params :acct] [:data :ssn]]})
    (rf/dispatch-sync [:rf.resource/ensure (assoc q1 :owner [:app :page])])
    (reply! {:ssn ssn-1 :page 1})
    (let [read!  (hold [state-q])
          _      (read!)
          _      (rf/dispatch-sync [:rf.resource/ensure
                                    (assoc q2 :owner [:app :page] :keep-previous? true)])
          kept   (get (read!) state-q)
          _      (when re-registered (reg! re-registered))
          window (capture-traces
                   (fn []
                     (reply! {:ssn ssn-2 :page 2})
                     (read!)))]
      (is (:previous? kept) "control: the new key shows the prior key's data")
      (is (= ssn-1 (get-in kept [:previous-data :ssn])) "the in-process read stays raw")
      (when rf.interop/debug-enabled?
        (let [tags (last-run-tags state-q window)
              prev (:rf.sub/prev-value tags)]
          (is (false? (get-in tags [:rf.sub/value :previous?])) "the new key has loaded")
          (is (= redacted (get-in prev [:previous-data :ssn])))
          (is (= 1 (get-in prev [:previous-data :page])) "an undeclared data slot rides")
          (is (= redacted (get-in prev [:previous-key 2 :acct])))
          (is (= 1 (get-in prev [:previous-key 2 :page])) "an undeclared param rides"))
        (is (not (carries? acct (prev-values window))))
        (is (not (carries? ssn-1 (prev-values window))))))))

(deftest control-an-unchanged-resources-prior-previous-projection-stays-redacted
  (assert-prior-previous-projection-redacted nil))

(deftest a-re-registered-resources-prior-previous-projection-keeps-its-declaration
  (testing "the carried previous key's entry is in the prior inputs, so its
            `:resource/key` claims are recovered with the entry's data claims"
    (assert-prior-previous-projection-redacted {})))

;; ===========================================================================
;; 4. The documented residue: a coarse root claim is never in the registry
;; ===========================================================================

(deftest a-coarse-resource-re-registered-without-its-claim-ships-its-prior-data
  (testing "control: unchanged, the coarse claim redacts the whole prior data"
    (let [[_ window] (remove-profile {:sensitive? true} nil)]
      (when rf.interop/debug-enabled?
        (is (= redacted (:rf.sub/prev-value
                          (last-run-tags [:rf.resource/data profile-q] window)))))))
  (testing "PINNED RESIDUE: re-registered without it, the prior data ships raw,
            because a coarse `:sensitive?` claim is never lowered into the
            registry the prior inputs carry (Spec 016 §Resource registration spec)"
    (let [[_ window] (remove-profile {:sensitive? true} {})]
      (when rf.interop/debug-enabled?
        (is (= {:name "Ann" :ssn ssn-1}
               (:rf.sub/prev-value (last-run-tags [:rf.resource/data profile-q] window))))))))

;; ===========================================================================
;; 5. The live value, on a recompute the resource did not cause
;; ===========================================================================

(defn- recompute-profile
  "Register the profile under `[[:data :ssn]]`, load it, re-register it under
  `re-registered` (when given), run `between!`, then recompute the held subs
  through an app-db write. Returns `[live window]` of that recompute."
  [re-registered between!]
  (rf/reg-event :lafas/bump (fn [{:keys [db]} _] {:db (update db :lafas/n (fnil inc 0))}))
  (reg-profile! {:sensitive [[:data :ssn]]})
  (let [state-q [:rf/resource profile-q]
        data-q  [:rf.resource/data profile-q]
        read!   (hold [state-q data-q])]
    (read!)
    (rf/dispatch-sync [:rf.resource/ensure (assoc profile-q :owner [:app :profile])])
    (reply! {:name "Ann" :ssn ssn-1})
    (read!)
    (when re-registered (reg-profile! re-registered))
    (between!)
    (let [window (capture-traces
                   (fn []
                     (rf/dispatch-sync [:lafas/bump])
                     (read!)))]
      [(read!) window])))

(defn- assert-live-profile-redacted
  [re-registered]
  (let [data-q        [:rf.resource/data profile-q]
        state-q       [:rf/resource profile-q]
        [live window] (recompute-profile re-registered (fn []))]
    (is (= ssn-1 (get-in live [data-q :ssn])) "the in-process read stays raw")
    (when rf.interop/debug-enabled?
      (is (= {:name "Ann" :ssn redacted} (:rf.sub/value (last-run-tags data-q window))))
      (is (= {:name "Ann" :ssn redacted}
             (get-in (last-run-tags state-q window) [:rf.sub/value :data])))
      (is (not (carries? ssn-1 (sub-runs window)))))))

(deftest control-an-unchanged-resources-live-data-stays-redacted-on-a-recompute
  (assert-live-profile-redacted nil))

(deftest a-re-registered-resources-live-data-keeps-its-declaration-on-a-recompute
  (testing "the owner is re-registered without its declaration, and an unrelated
            app-db write recomputes the held subs"
    (assert-live-profile-redacted {})))

(deftest a-resource-commit-under-the-new-registration-releases-the-live-data
  (testing "PINNED BOUNDARY: any resource commit reconciles the registry against
            the current registrations, so after one the re-registered owner's
            empty declaration governs the data it did not reload"
    (let [[_ window] (recompute-profile
                       {}
                       (fn []
                         (rf/reg-resource :lafas/other
                           {:scope :rf.scope/global :params-schema [:map]}
                           (fn [_ _] {:request {:method :get :url "/other"}}))
                         (rf/dispatch-sync [:rf.resource/ensure
                                            {:resource :lafas/other :scope :rf.scope/global
                                             :params {} :owner [:app :other]}])))]
      (when rf.interop/debug-enabled?
        (is (= {:name "Ann" :ssn ssn-1}
               (:rf.sub/value (last-run-tags [:rf.resource/data profile-q] window))))))))
