(ns re-frame.resources-lifecycle-trace-ops-cljs-test
  "The lifecycle trace ops the Xray timeline and AI audit consume (Spec 016
  §Xray and AI tooling): :rf.resource/registered on first registration only,
  :rf.resource/owner-attached when a new owner lands (fresh load or dedupe
  join), :rf.resource/hydrate-refetch per hydration refetch-plan entry, the
  single :rf.resource/stale-suppressed op carrying the canonical reply
  envelope, and :rf.resource/cache-hit on a fresh-skip ensure."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(defn- capturing-transport-fixture [f]
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

(defn- record-resource-traces!
  "Run `body-fn`; return every :rf.resource/* trace event, in capture order."
  [body-fn]
  (let [seen (atom [])
        k    ::resource-trace-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev]
          (when (and (keyword? (:operation ev))
                     (= "rf.resource" (namespace (:operation ev))))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- ops [traces] (into #{} (map :operation) traces))

(defn- tags-of
  "The :tags of each captured event with `op`, in capture order."
  [traces op]
  (into [] (comp (filter #(= op (:operation %))) (map :tags)) traces))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- entry [scoped-key]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) (rf.resources.state/entry-path scoped-key)))

(defn- ensure! [resource owner]
  (rf/dispatch-sync [:rf.resource/ensure {:resource resource :scope :rf.scope/global
                                          :params {:slug "w"} :owner owner}]))

(defn- k-of [resource] (rf.resources.state/scoped-resource-key :rf.scope/global resource {:slug "w"}))

(deftest reg-resource-emits-registered-trace
  (let [[tags :as all] (tags-of (record-resource-traces!
                                  #(rf/reg-resource :rt/article
                                                    (article-spec {:stale-after-ms 60000
                                                                   :gc-after-ms    300000})
                                                    article-spec-request))
                                :rf.resource/registered)]
    (is (= [1 {:resource-id :rt/article :scope-policy :rf.scope/global
               :stale-after-ms 60000 :gc-after-ms 300000}]
           [(count all) (select-keys tags [:resource-id :scope-policy :stale-after-ms :gc-after-ms])])
        "one registered row carrying the static policy summary")))

(deftest registered-fires-first-time-only
  ;; :rf.registry/handler-replaced is the hot-reload signal
  (rf/reg-resource :rt/once (article-spec) article-spec-request)
  (is (empty? (tags-of (record-resource-traces!
                         #(rf/reg-resource :rt/once (article-spec {:stale-after-ms 1}) article-spec-request))
                       :rf.resource/registered))))

(deftest owner-attached-on-fresh-load
  (rf/reg-resource :oa/article (article-spec) article-spec-request)
  (is (= [{:owner [:app :oa 1] :resource/key (k-of :oa/article) :joined-in-flight? false}]
         (mapv #(select-keys % [:owner :resource/key :joined-in-flight?])
               (tags-of (record-resource-traces! #(ensure! :oa/article [:app :oa 1]))
                        :rf.resource/owner-attached)))))

(deftest owner-attached-on-dedupe-join
  (rf/reg-resource :oa/join (article-spec) article-spec-request)
  (ensure! :oa/join [:route :r 1])
  (let [traces (record-resource-traces! #(ensure! :oa/join [:app :x 2]))]
    (is (seq (tags-of traces :rf.resource/deduped)) "precondition: the second ensure deduped")
    (is (= [{:owner [:app :x 2] :joined-in-flight? true}]
           (mapv #(select-keys % [:owner :joined-in-flight?]) (tags-of traces :rf.resource/owner-attached)))
        "one row for the newly joined owner")))

(deftest owner-attached-not-re-emitted-for-existing-owner
  (rf/reg-resource :oa/same (article-spec) article-spec-request)
  (ensure! :oa/same [:app :same 1])
  (is (empty? (tags-of (record-resource-traces! #(ensure! :oa/same [:app :same 1]))
                       :rf.resource/owner-attached))))

(deftest owner-attached-absent-when-no-owner
  ;; a cause is not an owner (Spec 016 §Active owners and causes)
  (rf/reg-resource :oa/none (article-spec) article-spec-request)
  (is (empty? (tags-of (record-resource-traces!
                         #(rf/dispatch-sync
                            [:rf.resource/ensure {:resource :oa/none :scope :rf.scope/global
                                                  :params {:slug "w"} :cause [:manual :x]}]))
                       :rf.resource/owner-attached))))

(defn- hydrated-runtime-db
  "Three hydrated entries: fresh with data (no refetch), stale with data, and
  metadata-only (no data)."
  [clock]
  (let [k-fresh [:rf.scope/global :h/fresh {:slug "f"}]
        k-stale [:rf.scope/global :h/stale {:slug "s"}]
        k-meta  [:rf.scope/global :h/meta  {:slug "m"}]]
    {rf.resources.state/resources-key
     {:entries
      {(rf.resources.state/key-id k-fresh) {:resource/key k-fresh :status :loaded :data {:x 1} :loaded-at (- clock 10) :stale-at (+ clock 10000)}
       (rf.resources.state/key-id k-stale) {:resource/key k-stale :status :loaded :data {:y 2} :loaded-at (- clock 20000) :stale-at (- clock 1)}
       (rf.resources.state/key-id k-meta)  {:resource/key k-meta  :status :loaded :data nil    :loaded-at (- clock 10) :stale-at (+ clock 10000)}}}}))

(deftest hydrate-refetch-emits-per-plan-entry
  (let [clock 5000
        rows  (tags-of (record-resource-traces!
                         #(rf.resources.ssr/hydrate-refetch-plan (hydrated-runtime-db clock) clock :rf/default))
                       :rf.resource/hydrate-refetch)]
    (is (= [2 #{[:h/stale :stale :hydration] [:h/meta :no-data :hydration]}]
           [(count rows) (set (map (juxt :resource-id :reason :cause) rows))])
        "one row per refetched entry with its reason, none for the fresh one")))

(deftest stale-suppressed-trace-carries-canonical-reply-envelope
  ;; the envelope is what tooling reads; a behaviour-only check would pass a
  ;; stale branch that skipped the shared re-frame.reply substrate
  (rf/reg-resource :rev/article (article-spec) article-spec-request)
  (let [k (k-of :rev/article)]
    (ensure! :rev/article [:app :rev 1])
    (let [wid1   (:current-work (entry k))
          traces (record-resource-traces!
                   (fn []
                     ;; a refetch mints generation 2; then the generation-1 reply lands
                     (rf/dispatch-sync [:rf.resource/refetch {:resource :rev/article :scope :rf.scope/global
                                                              :params {:slug "w"}}])
                     (rf/dispatch-sync [:rf.resource.internal/succeeded
                                        {:resource/key k :work/id wid1 :generation 1
                                         :data {:stale "data"}}])))
          tags   (first (tags-of traces :rf.resource/stale-suppressed))]
      (is (not (contains? (ops traces) :rf.resource/work-suppressed)) "stale-suppressed is the one suppression op")
      (is (= {:resource/key k :outcome :success :rf.reply/status :stale :rf.reply/work-status :suppressed
              :rf.reply/stale-reason :rf.resource/superseded :rf.reply/work-id wid1}
             (select-keys tags [:resource/key :outcome :rf.reply/status :rf.reply/work-status
                                :rf.reply/stale-reason :rf.reply/work-id])))
      (is (not (contains? tags :work/id)) "the work identity rides only as :rf.reply/work-id")
      (is (= [1 2 k] ((juxt (comp :carried :generation) (comp :current :generation) :resource/key)
                      (:rf.reply/correlation tags)))
          "the carried-vs-current generation pair")
      (is (= [2 false :suppressed]
             [(:generation (entry k)) (= {:stale "data"} (:data (entry k)))
              (:status (rf.resources.work-ledger/get-record (:rf.db/runtime (rf/frame-state-value :rf/default)) wid1))])
          "the stale reply wrote nothing and its work row settled :suppressed"))))

(deftest cache-hit-emitted-on-fresh-ensure
  ;; no :stale-after-ms, so the loaded entry stays fresh
  (rf/reg-resource :ch/article (article-spec) article-spec-request)
  (let [k (k-of :ch/article)]
    (ensure! :ch/article [:app :ch 1])
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k :work/id (:current-work (entry k)) :generation 1 :data {:title "W"}}])
    (let [before (entry k)
          traces (record-resource-traces! #(ensure! :ch/article [:app :ch 2]))
          e      (entry k)]
      (is (= [{:resource/key k :owner [:app :ch 2]}]
             (mapv #(select-keys % [:resource/key :owner]) (tags-of traces :rf.resource/cache-hit))))
      (is (empty? (filter #{:rf.resource/fetch-started :rf.resource/work-started :rf.resource/deduped}
                          (ops traces)))
          "a fresh-skip starts no load and is not a dedupe")
      (is (= [(:generation before) true nil true]
             [(:generation e) (identical? (:data before) (:data e)) (:current-work e)
              (contains? (:active-owners e) [:app :ch 2])])
          "the cached value is served unchanged and the new owner attached")
      (is (= [false] (mapv :joined-in-flight? (tags-of traces :rf.resource/owner-attached)))
          "the new owner is recorded, not as an in-flight join"))))
