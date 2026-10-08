(ns re-frame.resources-mutation-cljs-test
  "Mutation over managed HTTP (Spec 016 §Mutations): registration, execute,
  settle consequences, stale suppression, clear, subs, exact targets and the
  `:reply-to` continuation. The `:rf.http/managed` fx is replaced by a stub
  that captures the lowered args; replies are dispatched in the live
  transport's append shape."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.fx :as rf.fx]
   [re-frame.elision :as rf.elision]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources.mutation-events :as rf.resources.mutation-events]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))
(def ^:private scheduled-timers (atom []))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (reset! scheduled-timers [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- instance
  ([instance-id] (instance :rf/default instance-id))
  ([frame-id instance-id]
   (get-in (runtime-db frame-id) (rf.resources.mutation-runtime/instance-path instance-id))))

(defn- instances
  ([] (instances :rf/default))
  ([frame-id]
   (or (get-in (runtime-db frame-id) (rf.resources.mutation-runtime/instances-path)) {})))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (rf.resources.state/entry-path scoped-key))))

(defn- mutation-record
  "The work-ledger row for a mutation instance's current attempt: the row whose
  `:work/id` embeds `[:rf.mutation instance-id]`."
  ([instance-id] (mutation-record :rf/default instance-id))
  ([frame-id instance-id]
   (->> (vals (get-in (runtime-db frame-id) [:rf.runtime/work-ledger]))
        (filter (fn [r] (= [:rf.mutation instance-id]
                           (second (:work/id r)))))
        first)))

(defn- reply-success!
  ([args result] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))
  ([args result opts] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result}) opts)))

(defn- reply-failure!
  ([args failure] (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))
  ([args failure opts] (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure}) opts)))

(defn- art-target
  "The map-form exact target for the `:r/article {:slug slug}` global key."
  ([] (art-target "w"))
  ([slug] {:resource :r/article :params {:slug slug} :scope :rf.scope/global}))

(defn- record-mutation-traces!
  "Run `body-fn`; return every `:rf.mutation/*` trace event it emitted, in order."
  [body-fn]
  (let [seen (atom [])
        k    ::mutation-trace-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev]
          (when (and (keyword? (:operation ev))
                     (= "rf.mutation" (namespace (:operation ev))))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- record-target-skipped-warnings!
  [body-fn]
  (let [seen (atom [])
        k    ::target-skipped-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= :rf.warning/mutation-target-skipped (:operation ev))
                   (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- record-error-records!
  "Run `body-fn`; return every record fanned on the always-on `:errors` stream."
  [body-fn]
  (let [seen (atom [])
        k    ::error-record-recorder]
    (rf.error-emit/register-error-listener! k (fn [rec] (swap! seen conj rec)))
    (try (body-fn)
         (finally (rf.error-emit/unregister-error-listener! k)))
    @seen))

(defn- save-article-spec
  ([] (save-article-spec {}))
  ([overrides]
   (merge {:params-schema [:map [:slug :string]]
           :invalidates   (fn [{:keys [slug]} _result]
                            #{[:article slug] [:article-list]})}
          overrides)))

(def ^:private save-article-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug)
               :body  {:slug slug}}}))

(defn- article-resource-spec []
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   :tags (fn [{:keys [slug]} _] #{[:article slug]})})

(def ^:private article-resource-request
  (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))

(def ^:private replied (atom []))

(defn- reg-capture-continuation!
  "Register :test/save-replied to record each continuation event it receives."
  []
  (reset! replied [])
  (rf/reg-event :test/save-replied
                (fn [_ event] (swap! replied conj event) {})))

(defn- article-key
  ([] (article-key "w"))
  ([slug] (rf.resources.state/scoped-resource-key :rf.scope/global :r/article {:slug slug})))

;; ---- registration ---------------------------------------------------------

(deftest reg-mutation-registers-and-introspects
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (is (fn? (:request (:rf/mutation (rf/handler-meta {:source :store :kind :mutation :id :m/save})))))
  (rf/clear :mutation :m/save)
  (is (nil? (:rf/mutation (rf/handler-meta {:source :store :kind :mutation :id :m/save})))))

(deftest reg-mutation-fail-closed
  (testing "a :request inside the metadata map is a mislocated key"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-bad-spec"
          (rf/reg-mutation :m/no-req
                           {:params-schema [:map] :request (fn [_ _] {:request {:url "/x"}})}
                           (fn [_ _] {:request {:url "/x"}})))))
  (testing "a spec must declare :params-schema"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-bad-spec"
          (rf/reg-mutation :m/no-schema {} (fn [_ _] {:request {:url "/x"}})))))
  (testing "a non-map metadata raises the canonical error with the value"
    (let [ex (try (rf/reg-mutation :m/bad-vec [] save-article-request)
                  nil
                  (catch #?(:clj Throwable :cljs :default) e e))]
      (is (= {:rf.error/id :rf.error/mutation-bad-spec :value []}
             (select-keys (ex-data ex) [:rf.error/id :value]))))))

(defn- defn-write
  [_params _ctx]
  {:request {:method :put :url "/api/defn"}})

(deftest reg-mutation-rejects-non-callable-request
  ;; A keyword or map is `ifn?` and would be invoked to a silent nil, so the
  ;; gate is `fn?`/`var?`; 42 is the non-ifn class, :kw and {:a 1} the silent one.
  (doseq [bad [42 :kw {:a 1}]]
    (let [ex (try (rf/reg-mutation :m/nonfn-request {:params-schema [:map]} bad)
                  nil
                  (catch #?(:clj Throwable :cljs :default) e e))]
      (is (= {:rf.error/id :rf.error/mutation-bad-spec :recovery :fix-registration
              :mutation-id :m/nonfn-request :value bad}
             (select-keys (ex-data ex) [:rf.error/id :recovery :mutation-id :value]))
          (pr-str bad))
      (is (nil? (:rf/mutation (rf/handler-meta {:source :store :kind :mutation :id :m/nonfn-request}))))))
  ;; A Var is IFn but not Fn on the JVM, so a bare `fn?` gate would reject it.
  (doseq [[label good] [["inline fn" (fn [_p _c] {:request {:url "/i"}})]
                        ["Var of a defn" #'defn-write]]]
    (is (= :m/good-request (rf/reg-mutation :m/good-request {:params-schema [:map]} good)) label)
    (rf/clear :mutation :m/good-request)))

(deftest reg-mutation-rejects-invalidate-timing-typo
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"mutation-bad-spec"
        (rf/reg-mutation :m/typo
                         (save-article-spec {:invalidate-timing :after-succes}) save-article-request))))

;; ---- execute --------------------------------------------------------------

(deftest execute-unregistered-refuses-and-names-the-id
  ;; The refusal precedes the mint, so nothing half-built is left behind, and
  ;; it is legible on the always-on :errors stream rather than a silent no-op.
  (let [recs (record-error-records!
               #(rf/dispatch-sync [:rf.mutation/execute
                                   {:mutation :m/nope :params {} :instance :i1}]))
        rec  (first (filterv #(= :rf.error/handler-exception (:error %)) recs))]
    (is (nil? @last-managed-args))
    (is (nil? (instance :i1)))
    (is (nil? (mutation-record :i1)))
    (is (= :rf.mutation/execute (:event-id rec)))
    (is (= {:rf.error/id :rf.error/mutation-not-registered :mutation-id :m/nope
            :recovery :fix-registration}
           (select-keys (ex-data (:exception rec)) [:rf.error/id :mutation-id :recovery])))))

(deftest execute-registered-under-another-kind-still-refuses
  ;; The guard keys on the :mutation registrar kind: a resource id must not be
  ;; lowered as a write.
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (let [recs (record-error-records!
               #(rf/dispatch-sync [:rf.mutation/execute
                                   {:mutation :r/article :params {:slug "w"}
                                    :instance :i/wrong-kind}]))
        rec  (first (filterv #(= :rf.error/handler-exception (:error %)) recs))]
    (is (nil? @last-managed-args))
    (is (nil? (instance :i/wrong-kind)))
    (is (= {:rf.error/id :rf.error/mutation-not-registered :mutation-id :r/article}
           (select-keys (ex-data (:exception rec)) [:rf.error/id :mutation-id])))))

(deftest execute-mints-instance-and-lowers-write
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :form/save-1}])
  (let [i    (instance :form/save-1)
        args @last-managed-args]
    (is (= {:status :pending :mutation/id :m/save :generation 1}
           (select-keys i [:status :mutation/id :generation])))
    (is (some? (:current-work i)))
    (is (some? (:request-id args)))
    (is (= :rf.mutation.internal/succeeded (first (:on-success args))))
    (is (= :rf.mutation.internal/failed (first (:on-failure args))))
    (is (= {:instance-id :form/save-1 :mutation-id :m/save :generation 1 :rf.frame/id :rf/default}
           (select-keys (nth (:on-success args) 1)
                        [:instance-id :mutation-id :generation :rf.frame/id])))
    (is (= {:method :put :url "/api/articles/w" :body {:slug "w"}} (:request args)))))

(deftest execute-started-at-from-token-time-ms
  ;; :started-at comes from the triggering token's :time-ms (replay-stable),
  ;; never an ambient clock read.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :st/save-1}]
                    {:rf.cofx {:rf/time-ms 1781078400123}})
  (is (= 1781078400123 (:started-at (instance :st/save-1)))))

(deftest execute-generates-instance-id-when-absent
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"}}])
  (is (= [:pending] (mapv :status (vals (instances :rf/default))))))

(deftest execute-rejects-non-serializable-instance-id-fails-closed
  ;; The id is durable and trace-visible, so a host value is refused before any
  ;; write or lowering.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"mutation-non-serializable-instance-id"
        (rf.resources.mutation-runtime/validate-instance-id! (fn []) 'test)))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance (fn [])}])
  (is (nil? @last-managed-args))
  (is (empty? (instances :rf/default))))

(deftest execute-rejects-malformed-reply-to-fails-closed
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :bad-rt
                      :reply-to {:event :not-a-vector}}])
  (is (nil? @last-managed-args))
  (is (nil? (instance :bad-rt))))

;; ---- success / failure consequences -----------------------------------------

(deftest success-invalidates-tags-and-refetches-active-owners
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:view :article]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :s1}])
  (let [mut-args @last-managed-args]
    (reset! last-managed-args nil)
    (reply-success! mut-args {:title "new"})
    (is (= {:status :success :result {:title "new"}}
           (select-keys (instance :s1) [:status :result])))
    (is (contains? #{:loading :fetching} (:status (entry (article-key)))))
    (is (= {:method :get :url "/a/w"} (:request @last-managed-args)))))

(deftest success-patches-resource-entry
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/patch
                   {:params-schema [:map [:slug :string]]
                    :patches (fn [_params result]
                               {(art-target) (fn [old _result] (merge old result))})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:view :a]}])
  (reply-success! @last-managed-args {:title "old" :views 1})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/patch :params {:slug "w"} :instance :p1}])
  (reply-success! @last-managed-args {:title "new"})
  (let [e (entry (article-key))]
    (is (= {:data {:title "new" :views 1} :status :loaded}
           (select-keys e [:data :status])))
    (is (nil? (:invalidated-at e))))
  (is (= [(article-key)] (:affected-keys (instance :p1)))))

(deftest success-settled-at-and-populate-loaded-at-from-reply-completed-at
  ;; One causal completion time (the reply token's :time-ms) stamps both the
  ;; instance :settled-at and the populated entry's :loaded-at.
  (rf/reg-resource :r/article (assoc (article-resource-spec) :stale-after-ms 60000)
                   article-resource-request)
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]
                    :populates (fn [_params result] {(art-target) result})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :ms1}])
  (reply-success! @last-managed-args {:title "seed"} {:rf.cofx {:rf/time-ms 1781078400456}})
  (is (= 1781078400456 (:settled-at (instance :ms1))))
  (is (= {:loaded-at 1781078400456 :stale-at (+ 1781078400456 60000)}
         (select-keys (entry (article-key)) [:loaded-at :stale-at]))))

(deftest failure-settled-at-from-reply-completed-at
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :mf1}])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}
                  {:rf.cofx {:rf/time-ms 1781078999999}})
  (let [i (instance :mf1)]
    (is (= {:status :error :settled-at 1781078999999 :error {:kind :rf.http/http-5xx :status 500}}
           (select-keys i [:status :settled-at :error])))
    (is (nil? (:result i)))))

(deftest success-populates-resource-entry
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]
                    :populates (fn [_params result] {(art-target) result})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :pop1}])
  (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
  (is (= {:status :loaded :data {:slug "w" :title "Fresh"} :tags #{[:article "w"]}}
         (select-keys (entry (article-key)) [:status :data :tags])))
  (testing "an undeclared GC policy still arms the default GC timer, and no stale timer"
    (is (= [[(article-key) nil 300000]]
           (mapv (juxt :resource/key (comp :stale :timers) (comp :gc :timers)) @scheduled-timers)))))

(deftest populate-lowers-sensitive-classification-without-manual-reconcile
  ;; A :populates can create an entry no resource event ever lowered a
  ;; declaration for; the succeeded handler must keep the elision registry in
  ;; step, or the SSR projector would ride :ssn verbatim. No manual reconcile here.
  (rf/reg-resource :acct/profile
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :sensitive [[:data :ssn]]
                    :tags (fn [{:keys [slug]} _] #{[:acct slug]})}
                   (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (rf/reg-mutation :m/save-profile
                   {:params-schema [:map [:slug :string]]
                    :populates (fn [_params result]
                                 {{:resource :acct/profile :params {:slug "w"}
                                   :scope :rf.scope/global} result})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save-profile :params {:slug "w"} :instance :sp1}])
  (reply-success! @last-managed-args {:ssn "123-45-6789" :name "Alice"})
  (let [rkey (rf.resources.state/scoped-resource-key :rf.scope/global :acct/profile {:slug "w"})
        k-id (rf.resources.state/key-id rkey)
        e    (entry rkey)]
    (is (= {:ssn "123-45-6789" :name "Alice"} (:data e)) "the durable cache stays raw")
    (is (= #{{:source :resource}}
           (get (rf.elision/sensitive-declarations :rf/default)
                [:rf.runtime/resources :entries k-id :data :ssn])))
    (let [projected (rf.resources.classification/project-entry-data
                      (:data e) k-id :rf/default :rf.egress/ssr-hydration)]
      (is (= {:ssn rf.privacy/redacted-sentinel :name "Alice"} projected))
      (is (not (str/includes? (pr-str projected) "123-45-6789"))))))

(deftest success-populate-arms-stale-and-gc-timers
  ;; An ownerless populated entry carries a GC policy, so it needs an armed reaper.
  (rf/reg-resource :r/article (assoc (article-resource-spec) :stale-after-ms 60000 :gc-after-ms 300000)
                   article-resource-request)
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]
                    :populates (fn [_params result] {(art-target) result})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :pgc1}])
  (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
  (is (empty? (:active-owners (entry (article-key)))))
  (is (= [[(article-key) :rf/default 60000 300000 false]]
         (mapv (juxt :resource/key :frame-id (comp :stale :timers) (comp :gc :timers) :server?)
               @scheduled-timers)))
  (testing "the fired GC timer reaps the ownerless populated entry"
    (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key (article-key)}])
    (is (nil? (entry (article-key))))))

(deftest success-patch-arms-timers-for-policy-keys
  (rf/reg-resource :r/article (assoc (article-resource-spec) :stale-after-ms 60000 :gc-after-ms 300000)
                   article-resource-request)
  (rf/reg-mutation :m/patch
                   {:params-schema [:map [:slug :string]]
                    :patches (fn [_params result]
                               {(art-target) (fn [old _result] (merge old result))})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:view :a]}])
  (reply-success! @last-managed-args {:title "old" :views 1})
  (reset! scheduled-timers [])
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/patch :params {:slug "w"} :instance :pat-gc1}])
  (reply-success! @last-managed-args {:title "new"})
  (is (= [[(article-key) 60000 300000]]
         (mapv (juxt :resource/key (comp :stale :timers) (comp :gc :timers)) @scheduled-timers))))

;; ---- invalidation timing ----------------------------------------------------

(deftest after-settle-invalidation-timing
  ;; Each row settles its own ownerless article, so each :invalidated-at is
  ;; caused by that row's reply.
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :after-settle}) save-article-request)
  (doseq [[label slug settle!]
          [["failure" "failed" #(reply-failure! % {:kind :rf.http/http-5xx :status 503})]
           ["success" "saved"  #(reply-success! % {:title "new"})]]]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug slug}}])
    (reply-success! @last-managed-args {:title "old"})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug slug}
                                             :instance (keyword "as" slug)}])
    (settle! @last-managed-args)
    (is (some? (:invalidated-at (entry (article-key slug)))) label)))

(deftest before-request-invalidation-timing
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :before-request}) save-article-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
  (reply-success! @last-managed-args {:title "x"})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :br1}])
  (is (some? (:invalidated-at (entry (article-key))))))

(defn- execute-fx [mutation-id instance-id]
  (:fx (rf.resources.mutation-events/execute-handler
         {:rf.db/runtime {} :rf.frame/id :rf/default
          :rf.resource/generation-allocation {:generation 1 :counter 1}}
         [:rf.mutation/execute {:mutation mutation-id :params {:slug "w"} :instance instance-id}])))

(defn- invalidate-dispatch? [[id sub]]
  (and (= :dispatch id) (= :rf.resource/invalidate-tags (first sub))))

(deftest before-request-invalidation-precedes-lowering
  ;; fx run in order, so the invalidation dispatch must sit before the lowering.
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :before-request}) save-article-request)
  (let [fx (execute-fx :m/save :ord1)]
    (is (= [:invalidate :rf.http/managed]
           (keep (fn [[id :as f]]
                   (cond (invalidate-dispatch? f) :invalidate
                         (= :rf.http/managed id)  id))
                 fx)))))

(deftest default-timing-emits-no-before-request-invalidation
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [fx (execute-fx :m/save :ord2)]
    (is (not-any? invalidate-dispatch? fx))
    (is (some #{:rf.http/managed} (map first fx)))))

;; ---- stale suppression and frame isolation ----------------------------------

(deftest stale-mutation-suppressed-trace-carries-canonical-reply-envelope
  ;; A superseded reply never settles the newer instance, and the suppression
  ;; trace carries the canonical reply envelope.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :rv}])
  (let [gen1-args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :rv}])
    (let [wid1   (-> gen1-args :on-success (nth 1) :work/id)
          traces (record-mutation-traces! #(reply-success! gen1-args {:stale "result"}))
          tags   (:tags (first (filterv #(= :rf.mutation/stale-suppressed (:operation %)) traces)))]
      (is (= {:instance :rv :outcome :success :rf.reply/status :stale
              :rf.reply/work-status :suppressed :rf.reply/stale-reason :rf.mutation/superseded
              :rf.reply/work-id wid1}
             (select-keys tags [:instance :outcome :rf.reply/status :rf.reply/work-status
                                :rf.reply/stale-reason :rf.reply/work-id])))
      (is (not (contains? tags :work/id)))
      (is (= {:generation {:carried 1 :current 2} :instance/id :rv}
             (select-keys (:rf.reply/correlation tags) [:generation :instance/id])))
      (is (= [:pending 2 nil] ((juxt :status :generation :result) (instance :rv)))))
    (reply-success! @last-managed-args {:fresh "result"})
    (is (= {:status :success :result {:fresh "result"}}
           (select-keys (instance :rv) [:status :result])))))

(deftest cross-frame-mutation-reply-rejected-without-mutating-receiving-frame
  ;; Two frames at the same instance/generation: a reply dispatched into the
  ;; wrong frame must not settle it.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [all-args (atom [])]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (rf/make-frame {:id :xfm/frame-a :doc "frame A"})
    (rf/make-frame {:id :xfm/frame-b :doc "frame B"})
    (doseq [f [:xfm/frame-a :xfm/frame-b]]
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :form/x}]
                        {:frame f}))
    (let [args-a (first @all-args)]
      (rf/dispatch-sync (conj (:on-success args-a) {:status :ok :value {:ok true}})
                        {:frame :xfm/frame-b})
      (is (= [:pending nil] ((juxt :status :result) (instance :xfm/frame-b :form/x))))
      (rf/dispatch-sync (conj (:on-success args-a) {:status :ok :value {:ok true}})
                        {:frame :xfm/frame-a})
      (is (= :success (:status (instance :xfm/frame-a :form/x)))))))

(deftest cross-frame-mutation-request-id-does-not-collide
  ;; The frame-local work-ids collide across frames, so the process-global
  ;; transport request-id must be frame-qualified.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [all-args (atom [])
        fa :xm/frame-a
        fb :xm/frame-b]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (rf/make-frame {:id fa :doc "frame A"})
    (rf/make-frame {:id fb :doc "frame B"})
    (doseq [f [fa fb]]
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :form/save-1}]
                        {:frame f}))
    (let [wid-a (:current-work (instance fa :form/save-1))
          wid-b (:current-work (instance fb :form/save-1))]
      (is (= wid-a wid-b) "precondition: the bare work-ids collide")
      (is (= [(rf.resources.work-ledger/managed-request-id fa wid-a)
              (rf.resources.work-ledger/managed-request-id fb wid-b)]
             (mapv :request-id @all-args)))
      (is (apply distinct? (mapv :request-id @all-args)))
      (rf/dispatch-sync (conj (:on-success (first @all-args)) {:status :ok :value {:ok true}})
                        {:frame fa})
      (is (= :success (:status (instance fa :form/save-1))))
      (is (= :pending (:status (instance fb :form/save-1)))))))

;; ---- clear ------------------------------------------------------------------

(deftest clear-aborts-the-in-flight-write
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [aborts (atom [])]
    (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx request-id] (swap! aborts conj request-id) nil))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :clr-live}])
    (let [wid (:current-work (instance :clr-live))]
      (rf/dispatch-sync [:rf.mutation/clear {:instance :clr-live}])
      (is (= [[:rf.req :rf/default wid]] @aborts)))))

(deftest clear-by-mutation-id-clears-all-instances
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "a"} :instance :a1}])
  (reply-success! @last-managed-args {:ok 1})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "b"} :instance :b1}])
  (reply-success! @last-managed-args {:ok 2})
  (rf/dispatch-sync [:rf.mutation/clear {:mutation :m/save}])
  (is (= {} (instances))))

(deftest cedn-distinct-sequential-instance-ids-do-not-clobber
  ;; `(= [:row 7] '(:row 7))` is true, so a plain map key would collapse these
  ;; two ids onto one row.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [all-args (atom [])
        iv [:row 7]
        il '(:row 7)]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "v"} :instance iv}])
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "l"} :instance il}])
    (let [[args-v args-l] @all-args]
      (is (= 2 (count (instances))))
      (is (vector? (:instance/id (instance iv))))
      (is (seq? (:instance/id (instance il))))
      (is (= [{:slug "v"} {:slug "l"}] [(:params (instance iv)) (:params (instance il))]))
      (reply-success! args-l {:id :l})
      (is (= [:pending :success] [(:status (instance iv)) (:status (instance il))]))
      (reply-success! args-v {:id :v})
      (is (= [{:id :v} {:id :l}] [(:result (instance iv)) (:result (instance il))])))))

(deftest cedn-distinct-sequential-instance-ids-clear-independently
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [iv [:row 7]
        il '(:row 7)]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "v"} :instance iv}])
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "l"} :instance il}])
    (rf/dispatch-sync [:rf.mutation/clear {:instance iv}])
    (is (nil? (instance iv)))
    (is (= :pending (:status (instance il))))
    (is (seq? (:instance/id (instance il))))))

;; ---- subs and introspection -------------------------------------------------

(deftest mutation-subs-project-view-model
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (testing "no instance: the idle empty state"
    (is (= :idle @(rf/subscribe [:rf.mutation/status {:instance :sub1}])))
    (is (false? @(rf/subscribe [:rf.mutation/pending? {:instance :sub1}])))
    (is (false? (:optimistic? @(rf/subscribe [:rf/mutation {:instance :sub1}])))))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :sub1}])
  (testing "pending; a write with no :optimistic plan is not optimistic"
    (is (= :pending @(rf/subscribe [:rf.mutation/status {:instance :sub1}])))
    (is (true? @(rf/subscribe [:rf.mutation/pending? {:instance :sub1}])))
    (is (false? (:optimistic? @(rf/subscribe [:rf/mutation {:instance :sub1}])))))
  (reply-success! @last-managed-args {:saved true})
  (testing "success"
    (is (= {:status :success :success? true :settled? true :optimistic? false :result {:saved true}}
           (select-keys @(rf/subscribe [:rf/mutation {:instance :sub1}])
                        [:status :success? :settled? :optimistic? :result])))
    (is (= {:saved true} @(rf/subscribe [:rf.mutation/result {:instance :sub1}])))))

(deftest mutation-state-fails-closed-without-frame
  ;; A frameless call must not return a nil indistinguishable from an absent instance.
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"no-frame-context"
        (rf/mutation-state {:instance :ms-no-frame})))
  (is (nil? (rf/mutation-state {:instance :ms-absent :frame :rf/default})))
  (is (nil? (rf/mutation-state {:instance :ms-absent :frame :no/such-frame})))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :ms-present}])
  (reply-success! @last-managed-args {:ok true})
  (is (= :success (:status (rf/mutation-state {:instance :ms-present :frame :rf/default})))))

;; ---- params and exact-target validation -------------------------------------

(deftest mutation-registry-rejects-non-edn-params
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
        (rf.resources.mutation-registry/validate+canonicalize-params
          :m/save (:rf/mutation (rf/handler-meta {:source :store :kind :mutation :id :m/save}))
          {:slug "w" :cb (fn [])} 'test))))

;; Validation throws are asserted at the fn boundary: the event loop catches a
;; handler throw, so the dispatch path is proved by observing no partial write.

(deftest validate-target-key-rejects-an-invalid-identity
  (doseq [[label target resolved-scope registered? arm]
          [["an unregistered resource id"
            {:resource :r/never-registered :params {:slug "w"}} :rf.scope/global (fn [_] false) :patches]
           ["the internal tuple form is not a public input"
            [:rf.scope/global :r/article {:slug "w"}] :rf.scope/global (constantly true) :populates]
           ["a non-keyword :resource"
            {:resource "article" :params {}} :rf.scope/global (constantly true) :patches]
           ["a reserved-scope typo"
            {:resource :r/article :params {:slug "w"}} :rf.scope/glabal (constantly true) :patches]
           ["non-EDN params"
            {:resource :r/article :params {:slug "w" :cb (fn [])}} :rf.scope/global (constantly true) :patches]
           ["a non-EDN scope"
            {:resource :r/article :params {:slug "w"}} (fn []) (constantly true) :patches]]]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (rf.resources.mutation-runtime/validate-target-key!
            target resolved-scope registered? 'test arm))
        label)))

(defn- resolve-scope [{:keys [scope]}]
  (cond
    (nil? scope)               [:resolved :rf.scope/global]
    (= scope {:from-db :nope}) [:nil-resolved :nope]
    :else                      [:resolved scope]))

(deftest validate-target-map-strict-policy-rejects-whole-map
  ;; The default :strict policy (pre-write callers): any bad target rejects the
  ;; whole arm; a nil-resolving {:from-db …} target is dropped, never global.
  (testing "a corruption-class or a recoverable bad target rejects the whole map"
    (doseq [[bad registered?] [[{:resource :r/article :params {:slug "x"} :scope :rf.scope/glabal}
                                (constantly true)]
                               [{:resource :r/nope :params {:slug "x"} :scope :rf.scope/global}
                                #(= % :r/article)]]]
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
            (rf.resources.mutation-runtime/validate-target-map!
              {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok bad :bad}
              resolve-scope registered? :patches 'test)))))
  (is (= [{[:rf.scope/global :r/article {:slug "w"}] :v} []]
         (rf.resources.mutation-runtime/validate-target-map!
           {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :v}
           resolve-scope (constantly true) :populates 'test)))
  (is (= [{} [:nope]]
         (rf.resources.mutation-runtime/validate-target-map!
           {{:resource :r/article :params {:slug "w"} :scope {:from-db :nope}} :v}
           resolve-scope (constantly true) :populates 'test)))
  (is (= [nil []] (rf.resources.mutation-runtime/validate-target-map! {} resolve-scope (constantly true) :patches 'test))))

(deftest validate-target-map-skip-recoverable-policy
  ;; The post-write settle policy: the server write already committed, so a
  ;; recoverable bad sibling is dropped and collected while valid siblings land;
  ;; cache-identity corruption still throws.
  (let [[canonical nils skipped]
        (rf.resources.mutation-runtime/validate-target-map!
          {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
           {:resource :r/nope :params {:slug "x"} :scope :rf.scope/global} :bad}
          resolve-scope #(= % :r/article) :patches 'test :skip-recoverable)]
    (is (= {[:rf.scope/global :r/article {:slug "w"}] :ok} canonical))
    (is (= [] nils))
    (is (= [{:reason :unregistered-resource :resource :r/nope}]
           (mapv #(select-keys % [:reason :resource]) skipped))))
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
        (rf.resources.mutation-runtime/validate-target-map!
          {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
           {:resource :r/article :params {:slug "x"} :scope :rf.scope/glabal} :bad}
          resolve-scope (constantly true) :patches 'test :skip-recoverable)))
  (is (= [{} [:nope] []]
         (rf.resources.mutation-runtime/validate-target-map!
           {{:resource :r/article :params {:slug "w"} :scope {:from-db :nope}} :v}
           resolve-scope (constantly true) :populates 'test :skip-recoverable)))
  (is (= [nil [] []] (rf.resources.mutation-runtime/validate-target-map! {} resolve-scope (constantly true) :patches 'test :skip-recoverable))))

(deftest classify-target-key-corruption-vs-recoverable
  (is (= [:skip :unregistered-resource {:target (pr-str {:resource :r/nope :params {:slug "w"}})
                                        :resource :r/nope}]
         (rf.resources.mutation-runtime/classify-target-key
           {:resource :r/nope :params {:slug "w"}} :rf.scope/global (constantly false) 'test :patches)))
  (is (= :non-map-target
         (second (rf.resources.mutation-runtime/classify-target-key :r/article :rf.scope/global (constantly true) 'test :patches))))
  (is (= :non-keyword-resource
         (second (rf.resources.mutation-runtime/classify-target-key {:resource "article" :params {}} :rf.scope/global (constantly true) 'test :patches))))
  (testing "corruption wins even when the resource is also unregistered"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (rf.resources.mutation-runtime/classify-target-key
            {:resource :r/nope :params {:slug "w"}} (fn []) (constantly false) 'test :patches)))))

;; ---- exact-target arms end to end -------------------------------------------

(deftest recoverable-patch-target-skipped-while-valid-sibling-lands
  ;; The reply fires post-write, so an unregistered sibling is dropped and
  ;; warned while the valid sibling lands and the instance settles.
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]
                    :patches (fn [_p _r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global}
                                          (fn [old r] (merge old r))
                                          {:resource :r/never-registered :params {:slug "w"} :scope :rf.scope/global}
                                          (fn [old _] old)})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:view :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (let [warns (record-target-skipped-warnings!
                (fn []
                  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :bad1}])
                  (reply-success! @last-managed-args {:title "new"})))
        skipped-shape {:arm :patches :reason :unregistered-resource :resource :r/never-registered}]
    (is (= {:title "new"} (:data (entry (article-key)))))
    (is (nil? (entry [:rf.scope/global :r/never-registered {:slug "w"}])))
    (is (= {:status :success :result {:title "new"}} (select-keys (instance :bad1) [:status :result])))
    (is (= :completed (:status (mutation-record :bad1))))
    (is (= [skipped-shape]
           (mapv #(select-keys % [:arm :reason :resource])
                 (:target-skipped (:patch-summary (instance :bad1))))))
    (is (= [skipped-shape] (mapv #(select-keys (:tags %) [:arm :reason :resource]) warns)))))

(deftest corruption-class-patch-target-still-throws-no-partial-mutation
  ;; A reserved-scope typo would write under a wrong scope, so it still aborts
  ;; the whole arm: the valid sibling does not land.
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]
                    :patches (fn [_p _r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global}
                                          (fn [old r] (merge old r))
                                          {:resource :r/article :params {:slug "x"} :scope :rf.scope/glabal}
                                          (fn [old _] old)})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:view :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :bad2}])
  (reply-success! @last-managed-args {:title "new"})
  (is (= {:title "old"} (:data (entry (article-key))))))

(deftest recoverable-populate-target-skipped-while-valid-sibling-lands
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/create
                   {:params-schema [:map [:slug :string]]
                    :populates (fn [_p r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} r
                                           {:resource :r/never-registered :params {:slug "w"} :scope :rf.scope/global} r})}
                   (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/create :params {:slug "w"} :instance :pop1}])
  (reply-success! @last-managed-args {:title "seeded"})
  (is (= {:title "seeded"} (:data (entry (article-key)))))
  (is (= :success (:status (instance :pop1))))
  (is (= :completed (:status (mutation-record :pop1))))
  (is (= [{:arm :populates :reason :unregistered-resource :resource :r/never-registered}]
         (mapv #(select-keys % [:arm :reason :resource])
               (:target-skipped (:patch-summary (instance :pop1)))))))

(deftest recoverable-remove-target-skipped-while-valid-sibling-lands
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
  (reply-success! @last-managed-args {:title "doomed"})
  (rf/reg-mutation :m/delete2
                   {:params-schema [:map [:slug :string]]
                    :removes (fn [{:keys [slug]} _r]
                               [{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                                {:resource :r/never-registered :params {:slug slug} :scope :rf.scope/global}])}
                   (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/delete2 :params {:slug "w"} :instance :rm1}])
  (reply-success! @last-managed-args {:deleted true})
  (let [summary (:patch-summary (instance :rm1))]
    (is (nil? (entry (article-key))))
    (is (= [(article-key)] (:removed summary)))
    (is (= :success (:status (instance :rm1))))
    (is (= :completed (:status (mutation-record :rm1))))
    (is (= [{:arm :removes :reason :unregistered-resource}]
           (mapv #(select-keys % [:arm :reason]) (:target-skipped summary))))))

(deftest mutation-removes-drops-the-exact-entry-and-reports-it
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
  (reply-success! @last-managed-args {:title "doomed"})
  (rf/reg-mutation :m/delete
                   {:params-schema [:map [:slug :string]]
                    :removes (fn [{:keys [slug]} _result]
                               [{:resource :r/article :params {:slug slug} :scope :rf.scope/global}])}
                   (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/delete :params {:slug "w"} :instance :del1
                      :reply-to [:test/save-replied]}])
  (reply-success! @last-managed-args {:deleted true})
  (is (nil? (entry (article-key))))
  (is (= [(article-key)] (:removed (:patch-summary (instance :del1)))))
  (is (= #{(article-key)} (set (:affected-keys (instance :del1)))))
  (is (contains? (:affected-keys (second (first @replied))) (article-key))))

(deftest mutation-removes-accepts-single-map-form-target
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
  (reply-success! @last-managed-args {:title "x"})
  (rf/reg-mutation :m/del-one
                   {:params-schema [:map [:slug :string]]
                    :removes (fn [{:keys [slug]} _r]
                               {:resource :r/article :params {:slug slug} :scope :rf.scope/global})}
                   (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/del-one :params {:slug "w"} :instance :do1}])
  (reply-success! @last-managed-args {:deleted true})
  (is (nil? (entry (article-key))))
  (testing "removing a key with no entry is a no-op"
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/del-one :params {:slug "gone"} :instance :dm1}])
    (reply-success! @last-managed-args {:deleted true})
    (is (= [] (:removed (:patch-summary (instance :dm1)))))))

;; ---- :reply-to continuation -------------------------------------------------
;; On an accepted terminal reply the runtime dispatches the call-site
;; `:reply-to` target with the canonical reply map appended, after cache
;; consequences and instance settlement. A stale reply never fires it.

(deftest reply-to-fires-on-accepted-success-and-carries-the-reply-map
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]
                    :populates (fn [_params result] {(art-target) result})}
                   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :rc1
                      :reply-to [:test/save-replied]}])
  (reply-success! @last-managed-args {:slug "w" :title "Fresh"}
                  {:rf.cofx {:rf/time-ms 1781078400777}})
  (is (= 1 (count @replied)))
  (let [[ev-id reply] (first @replied)]
    (is (= :test/save-replied ev-id))
    (is (= {:status :ok :mutation :m/save :params {:slug "w"} :instance :rc1
            :scope :rf.scope/global :value {:slug "w" :title "Fresh"}
            :rf.reply/work-kind :mutation :rf.frame/id :rf/default
            :completed-at 1781078400777 :cause [:mutation :m/save :rc1]}
           (select-keys reply [:status :mutation :params :instance :scope :value
                               :rf.reply/work-kind :rf.frame/id :completed-at :cause])))
    (is (some? (:rf.reply/work-id reply)))
    (is (contains? (:affected-keys reply) (article-key)))))

(deftest reply-to-preserves-static-call-site-args
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :sc1
                      :reply-to [:test/save-replied {:kind :article} 7]}])
  (reply-success! @last-managed-args {:ok true})
  (let [ev (first @replied)]
    (is (= [:test/save-replied {:kind :article} 7] (butlast ev)))
    (is (= :ok (:status (last ev))))))

(deftest reply-to-target-metadata-reaches-the-handler
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :mc1
                      :reply-to (with-meta [:test/save-replied {:kind :article}]
                                  {:app/tag :article})}])
  (reply-success! @last-managed-args {:ok true})
  (let [ev (first @replied)]
    (is (= [:test/save-replied {:kind :article}] (butlast ev)))
    (is (= {:app/tag :article} (meta ev)))))

(deftest reply-to-observes-settled-instance-and-cache-consequences
  (let [seen (atom nil)]
    (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :populates (fn [_params result] {(art-target) result})}
                     (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
    (rf/reg-event :test/save-replied
                  (fn [_ [_ reply]]
                    (reset! seen {:instance-status (:status (instance :pc1))
                                  :entry           (select-keys (entry (article-key)) [:status :data])
                                  :reply-status    (:status reply)})
                    {}))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :pc1
                        :reply-to [:test/save-replied]}])
    (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
    (is (= {:instance-status :success
            :entry           {:status :loaded :data {:slug "w" :title "Fresh"}}
            :reply-status    :ok}
           @seen))))

(deftest reply-to-fires-on-accepted-error
  ;; Delivery keys on acceptance, not on status: an accepted :error fires too.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :ec1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
  (is (= 1 (count @replied)))
  (is (= {:status :error :error {:kind :rf.http/http-5xx :status 503} :mutation :m/save
          :instance :ec1 :affected-keys #{} :cause [:mutation :m/save :ec1]}
         (select-keys (second (first @replied))
                      [:status :error :mutation :instance :affected-keys :cause])))
  (is (= :failed (:status (mutation-record :ec1))) "a genuine failure is not :cancelled"))

(deftest accepted-abort-reply-settles-ledger-cancelled
  ;; The ledger status must agree with the reply's :rf.reply/work-status.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :ac1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/aborted :reason :user-abort})
  (is (= [{:status :cancelled :rf.reply/work-status :cancelled}]
         (mapv #(select-keys (second %) [:status :rf.reply/work-status]) @replied)))
  (let [rec (mutation-record :ac1)]
    (is (= :cancelled (:status rec)))
    (is (= :aborted (:reason (:outcome rec))))
    (is (nil? (:error (:outcome rec))))))

(deftest stale-reply-does-not-fire-the-continuation
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :i
                      :reply-to [:test/save-replied]}])
  (let [gen1-args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :i
                        :reply-to [:test/save-replied]}])
    (reply-success! gen1-args {:stale "result"})
    (is (= [] @replied))
    (reply-success! @last-managed-args {:fresh "result"})
    (is (= [{:fresh "result"}] (mapv (comp :value second) @replied)))))

(deftest cleared-instance-reply-does-not-fire-the-continuation
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :clr1
                      :reply-to [:test/save-replied]}])
  (let [args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/clear {:instance :clr1}])
    (is (nil? (instance :clr1)))
    (reply-success! args {:late "result"})
    (is (= [] @replied))))

(deftest replied-trace-lands-after-succeeded-in-phase-order
  ;; The :rf.mutation/replied row is emitted at settlement, after
  ;; :rf.mutation/succeeded, so its stream position reflects the phase order.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec) save-article-request)
  (let [rows (record-mutation-traces!
               (fn []
                 (rf/dispatch-sync [:rf.mutation/execute
                                    {:mutation :m/save :params {:slug "w"} :instance :po1
                                     :reply-to [:test/save-replied]}])
                 (reply-success! @last-managed-args {:title "new"})))
        row  (:tags (first (filter #(= :rf.mutation/replied (:operation %)) rows)))]
    (is (= [:rf.mutation/succeeded :rf.mutation/replied]
           (filterv #{:rf.mutation/succeeded :rf.mutation/replied} (map :operation rows))))
    (is (= {:mutation :m/save :instance :po1 :status :ok :cause [:mutation :m/save :po1]}
           (select-keys row [:mutation :instance :status :cause])))
    (is (= {:event [:test/save-replied] :delivery :append}
           (select-keys (:target row) [:event :delivery])))))

;; :affected-keys are the keys populated, patched, removed or marked stale by
;; the accepted reply.

(deftest invalidation-only-success-includes-stale-keys-in-affected-keys
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"}
                      :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "old"})
  (rf/dispatch-sync [:rf.resource/release-owner
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]}])
  (rf/reg-mutation :m/touch
                   {:params-schema [:map [:slug :string]]
                    :invalidates (fn [{:keys [slug]} _r] #{[:article slug]})}
                   (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/touch")}}))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/touch :params {:slug "w"} :instance :iv1
                      :reply-to [:test/save-replied]}])
  (reply-success! @last-managed-args {:ok true})
  (is (contains? (:affected-keys (second (first @replied))) (article-key)))
  (is (= #{(article-key)} (set (:affected-keys (instance :iv1))))))

(deftest after-failure-invalidation-includes-stale-keys-in-affected-keys
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec) article-resource-request)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]}])
  (reply-success! @last-managed-args {:title "x"})
  (rf/dispatch-sync [:rf.resource/release-owner
                     {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]}])
  (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :after-failure}) save-article-request)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :afk1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
  (is (some? (:invalidated-at (entry (article-key)))))
  (let [reply (second (first @replied))]
    (is (= :error (:status reply)))
    (is (contains? (:affected-keys reply) (article-key))))
  (is (= #{(article-key)} (set (:affected-keys (instance :afk1))))))
