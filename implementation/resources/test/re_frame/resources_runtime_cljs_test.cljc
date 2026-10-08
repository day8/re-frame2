(ns re-frame.resources-runtime-cljs-test
  "The resource cache-entry runtime (Spec 016): canonical identity, the
  fail-closed scope policy, the lifecycle status transitions, structural
  sharing, stale suppression, the passive subs, per-frame isolation, owners,
  remove, the reverse indexes and clear-resource. The `:rf.http/managed` fx is
  a capturing no-op, so replies are fed to the internal reply events directly."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   [re-frame.identity :as rf.identity]
   [re-frame.resources]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.subs :as rf.resources.subs]
   [re-frame.resources.test-support]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  ;; A named resolver over an app-db slot this suite never writes, so it
  ;; resolves nil and a call supplying no :scope of its own fails closed.
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- entries-table []
  (or (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
              (rf.resources.state/entries-path))
      {}))

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (:rf.db/runtime (rf/frame-state-value frame-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (rf.resources.state/entry-path scoped-key))))

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

(defn- gkey [rid params] (rf.resources.state/scoped-resource-key :rf.scope/global rid params))

(defn- ensure! [rid slug owner]
  (rf/dispatch-sync [:rf.resource/ensure {:resource rid :scope :rf.scope/global
                                          :params {:slug slug} :owner owner}]))

(defn- succeed!
  "Feed the internal success reply for the entry's current work."
  ([k data] (succeed! k data nil))
  ([k data opts]
   (let [e (entry k)]
     (rf/dispatch-sync [:rf.resource.internal/succeeded
                        {:resource/key k :work/id (:current-work e)
                         :generation (:generation e) :data data}]
                       opts))))

(defn- fail! [k error]
  (let [e (entry k)]
    (rf/dispatch-sync [:rf.resource.internal/failed
                       {:resource/key k :work/id (:current-work e)
                        :generation (:generation e) :error error}])))

(defn- refetch! [rid slug]
  (rf/dispatch-sync [:rf.resource/refetch {:resource rid :scope :rf.scope/global
                                           :params {:slug slug}}]))

(defn- record-error-records!
  "Run `body-fn`; return every record fanned on the always-on `:errors` stream."
  [body-fn]
  (let [seen (atom [])
        k    ::error-record-recorder]
    (rf.error-emit/register-error-listener! k (fn [rec] (swap! seen conj rec)))
    (try (body-fn)
         (finally (rf.error-emit/unregister-error-listener! k)))
    @seen))

(defn- invalid-params-ex [validate rid spec params]
  (try (validate rid spec params 'test)
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

;; ---- canonical identity -----------------------------------------------------

(deftest resource-identity-uses-shared-cedn1-rule
  (testing "non-portable numbers are rejected at the cache-key boundary"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (rf.resources.state/reject-non-edn! {:ratio 1.5} 'test :params :r/x)))
    (is (false? (rf.resources.state/serializable-edn? {:n 9007199254740993}))
        "an integer beyond the safe range"))
  (is (true? (rf.resources.state/serializable-edn? {:rev 1 :page 42})))
  (is (= {:rev 1 :page 42} (rf.resources.state/canonicalize {:page 42 :rev 1})))
  (testing "list and vector params stay distinct, kind-preserving identities"
    (let [cv (rf.resources.state/canonicalize {:xs [1 2 3]})
          cl (rf.resources.state/canonicalize {:xs '(1 2 3)})]
      (is (not (rf.identity/identical-identity? (gkey :r/x cv) (gkey :r/x cl))))
      (is (vector? (:xs cv)))
      (is (seq? (:xs cl))))))

;; Present-nil and missing are distinct identities, and nil-vs-missing is
;; schema-defined: validation defers to :params-schema.

(deftest resource-present-nil-param-distinct-from-missing
  (let [spec {:scope :rf.scope/global
              :params-schema [:map [:x {:optional true} [:maybe :string]]]
              :request (fn [_ _ctx] {:request {:method :get :url "/x"}})}
        present-nil (rf.resources.registry/validate+canonicalize-params :r/x spec {:x nil} 'test)
        absent      (rf.resources.registry/validate+canonicalize-params :r/x spec {} 'test)]
    (is (= [{:x nil} {}] [present-nil absent]))
    (is (not (rf.identity/identical-identity? (gkey :r/x present-nil) (gkey :r/x absent))))))

(deftest resource-explicit-nil-params-slot-distinct-from-omitted
  ;; A whole-slot {:params nil} reaches the schema unchanged; only an absent
  ;; slot defaults to {}.
  (let [spec {:scope         :rf.scope/global
              :params-schema [:maybe :map]
              :request       (fn [_ _ctx] {:request {:method :get :url "/x"}})}
        explicit-nil (rf.resources.registry/validate+canonicalize-params
                       :r/x spec (rf.resources.state/params-present? {:params nil}) 'test)
        omitted      (rf.resources.registry/validate+canonicalize-params
                       :r/x spec (rf.resources.state/params-present? {}) 'test)]
    (is (= [nil {}] [explicit-nil omitted]))
    (is (not (rf.identity/identical-identity? (gkey :r/x explicit-nil) (gkey :r/x omitted))))))

(deftest resource-explicit-nil-params-slot-rejected-when-schema-rejects-nil
  ;; Coercing the nil slot to {} would pass a [:map] schema.
  (let [ex (invalid-params-ex rf.resources.registry/validate+canonicalize-params :r/x
                              {:scope         :rf.scope/global
                               :params-schema [:map [:slug :string]]
                               :request       (fn [_ _] {:request {:method :get :url "/x"}})}
                              (rf.resources.state/params-present? {:params nil}))]
    (is (= {:rf.error/id :rf.error/resource-invalid-params :resource-id :r/x :params nil}
           (select-keys (ex-data ex) [:rf.error/id :resource-id :params])))))

(deftest mutation-explicit-nil-params-slot-rejected-when-schema-rejects-nil
  (let [spec {:scope :rf.scope/global
              :params-schema [:map [:slug :string]]
              :request (fn [_ _] {:request {:method :post :url "/x"}})}
        ex   (invalid-params-ex rf.resources.mutation-registry/validate+canonicalize-params :m/x spec
                                (rf.resources.state/params-present? {:params nil}))]
    (is (= {:rf.error/id :rf.error/mutation-invalid-params :params nil}
           (select-keys (ex-data ex) [:rf.error/id :params])))
    (is (= {} (rf.resources.mutation-registry/validate+canonicalize-params
                :m/x {:scope :rf.scope/global :params-schema [:map]
                      :request (fn [_ _] {:request {:method :post :url "/x"}})}
                (rf.resources.state/params-present? {}) 'test))
        "an omitted slot still defaults to {}")))

(deftest param-canonicalization-total-over-mixed-keys
  (let [c1 (rf.resources.state/canonicalize {:b 1 "a" 2 :a 3 "z" 4})]
    (is (= c1 (rf.resources.state/canonicalize {"z" 4 :a 3 "a" 2 :b 1})))
    (is (= {:b 1 "a" 2 :a 3 "z" 4} c1))))

;; ---- fail-closed scope policy -----------------------------------------------

(deftest scope-resolution-fail-closed
  (rf/reg-resource :sr/derived (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (testing "a {:from-db …} policy resolving nil, with no payload scope, is a loud error"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-unresolved-reference"
          (rf.resources.registry/resolve-scope-for-event
            :sr/derived (rf.resources.registry/resource-meta :sr/derived)
            {:payload-scope nil :db {}} 'test))))
  (testing "a payload :scope overrides the policy"
    (is (= {:user "u-1"}
           (rf.resources.registry/resolve-scope-for-event
             :sr/derived (rf.resources.registry/resource-meta :sr/derived)
             {:payload-scope {:user "u-1"}} 'test))))
  (testing "an explicit :rf.scope/global policy resolves to global"
    (rf/reg-resource :sr/global (article-spec) article-spec-request)
    (is (= :rf.scope/global
           (rf.resources.registry/resolve-scope-for-event
             :sr/global (rf.resources.registry/resource-meta :sr/global) {} 'test)))))

(deftest sub-side-scope-fail-closed
  (rf/reg-resource :ss/derived (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-sub-unresolved-scope"
        (rf.resources.subs/resolve-scoped-key {:resource :ss/derived :params {:slug "x"}} {}))))

(deftest reserved-scope-typo-rejected-at-concrete-boundaries
  (rf/reg-resource :tp/article (article-spec {:scope {:from-db :t/caller-scope}}) article-spec-request)
  (let [spec (rf.resources.registry/resource-meta :tp/article)]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (rf.resources.registry/resolve-scope-for-event
            :tp/article spec {:payload-scope :rf.scope/glabal} 'test)))
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (rf.resources.registry/resolve-scope-for-sub
            :tp/article spec :rf.scope/sesssion 'test {})))
    (testing "only the reserved namespace fails closed; app keywords and tuples are values"
      (is (= :my.app/tenant
             (rf.resources.registry/resolve-scope-for-event
               :tp/article spec {:payload-scope :my.app/tenant} 'test)))
      (is (= [:rf.scope/session {:tenant "acme"}]
             (rf.resources.registry/resolve-scope-for-event
               :tp/article spec {:payload-scope [:rf.scope/session {:tenant "acme"}]}
               'test))))))

(deftest singleton-vector-global-rejected-fail-closed
  ;; The global scope is the bare keyword; the wrapped spelling is not an alias.
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
        (rf.resources.state/canonicalize-scope [:rf.scope/global] 'test :r/x))))

(deftest mutation-scope-routes-through-shared-validation
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
        (rf.resources.mutation-registry/resolve-scope :m/x {} :rf.scope/glabal {})))
  (is (thrown-with-msg?
        #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
        (rf.resources.mutation-registry/resolve-scope :m/x {} {:fn (fn [])} {})))
  (is (= :rf.scope/global (rf.resources.mutation-registry/resolve-scope :m/x {} nil {}))))

;; ---- the registration guard on the read path --------------------------------

(deftest ensure-unregistered-refuses-and-names-the-id
  ;; The guard runs before any entry write or lowering, and the refusal is
  ;; legible on the always-on :errors stream rather than a silent no-op.
  (let [recs (record-error-records! #(ensure! :r/nope "w" [:app :test 1]))
        rec  (first (filterv #(= :rf.error/handler-exception (:error %)) recs))]
    (is (nil? (entry (gkey :r/nope {:slug "w"}))))
    (is (nil? @last-managed-args))
    (is (= :rf.resource/ensure (:event-id rec)))
    (is (= {:rf.error/id :rf.error/resource-not-registered :resource-id :r/nope
            :recovery :fix-registration}
           (select-keys (ex-data (:exception rec)) [:rf.error/id :resource-id :recovery])))))

(deftest ensure-registered-under-another-kind-still-refuses
  ;; The guard keys on the :resource registrar kind: a mutation's POST must not
  ;; be lowered as a cache read.
  (rf/reg-mutation :m/save
                   {:params-schema [:map [:slug :string]]}
                   (fn [{:keys [slug]} _ctx]
                     {:request {:method :post :url (str "/api/articles/" slug)}}))
  (let [recs (record-error-records! #(ensure! :m/save "w" [:app :test 1]))
        rec  (first (filterv #(= :rf.error/handler-exception (:error %)) recs))]
    (is (nil? (entry (gkey :m/save {:slug "w"}))))
    (is (nil? @last-managed-args))
    (is (= {:rf.error/id :rf.error/resource-not-registered :resource-id :m/save}
           (select-keys (ex-data (:exception rec)) [:rf.error/id :resource-id])))))

;; ---- lifecycle --------------------------------------------------------------

(deftest ensure-then-success-loaded
  (rf/reg-resource :a/article (article-spec) article-spec-request)
  (let [k (gkey :a/article {:slug "welcome"})]
    (ensure! :a/article "welcome" [:app :test 1])
    (let [e (entry k)]
      (is (= {:status :loading :generation 1} (select-keys e [:status :generation])))
      (is (some? (:current-work e)))
      (is (contains? (:active-owners e) [:app :test 1])))
    (is (= :rf.resource.internal/succeeded (first (:on-success @last-managed-args))))
    (is (some? (:request-id @last-managed-args)))
    (succeed! k {:title "Welcome"})
    (is (= [:loaded {:title "Welcome"} nil #{[:article "welcome"]}]
           ((juxt :status :data :current-work :tags) (entry k))))))

(deftest structural-sharing-preserves-identical-data
  ;; An equal newly decoded value keeps the old value's identity, so
  ;; downstream subs stay quiet.
  (rf/reg-resource :ss/article (article-spec) article-spec-request)
  (let [k (gkey :ss/article {:slug "w"})]
    (ensure! :ss/article "w" [:app :ss 1])
    (succeed! k {:title "Welcome" :body [1 2 3]})
    (let [first-data (:data (entry k))]
      (refetch! :ss/article "w")
      (succeed! k {:title "Welcome" :body [1 2 3]})
      (is (identical? first-data (:data (entry k)))))))

(deftest refresh-failure-keeps-data
  (rf/reg-resource :rf2/article (article-spec) article-spec-request)
  (let [k (gkey :rf2/article {:slug "w"})]
    (ensure! :rf2/article "w" [:app :rf2 1])
    (succeed! k {:title "Welcome"})
    (refetch! :rf2/article "w")
    (is (= {:status :fetching :data {:title "Welcome"}} (select-keys (entry k) [:status :data])))
    (fail! k {:kind :rf.http/http-5xx :status 503})
    (is (= [:loaded {:title "Welcome"} {:kind :rf.http/http-5xx :status 503} nil]
           ((juxt :status :data :refresh-error :error) (entry k))))))

(deftest first-load-failure-error
  (rf/reg-resource :fl/article (article-spec) article-spec-request)
  (let [k (gkey :fl/article {:slug "w"})]
    (ensure! :fl/article "w" [:app :fl 1])
    (fail! k {:kind :rf.http/http-5xx :status 503})
    (is (= [:error nil {:kind :rf.http/http-5xx :status 503}]
           ((juxt :status :data :error) (entry k))))))

(deftest stale-reply-is-suppressed
  (rf/reg-resource :st/article (article-spec) article-spec-request)
  (let [k (gkey :st/article {:slug "w"})]
    (ensure! :st/article "w" [:app :st 1])
    (let [wid1 (:current-work (entry k))]
      (refetch! :st/article "w")
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource/key k :work/id wid1 :generation 1
                          :data {:stale "data"}}])
      (is (= [:loading 2 nil] ((juxt :status :generation :data) (entry k)))))))

(deftest ensure-dedupes-in-flight
  (rf/reg-resource :dd/article (article-spec) article-spec-request)
  (let [k (gkey :dd/article {:slug "w"})]
    (ensure! :dd/article "w" [:route :r 1])
    (ensure! :dd/article "w" [:app :x 2])
    (is (= 1 (:generation (entry k))) "a second ensure while in flight joins")
    (is (= #{[:route :r 1] [:app :x 2]} (set (:active-owners (entry k)))))))

(deftest per-frame-isolation
  (rf/reg-resource :iso/article (article-spec) article-spec-request)
  (let [fa :iso/frame-a
        fb :iso/frame-b
        k  (gkey :iso/article {:slug "w"})]
    (rf/make-frame {:id fa :doc "isolation frame A"})
    (rf/make-frame {:id fb :doc "isolation frame B"})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :iso/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:app :a 1]}]
                      {:frame fa})
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k :work/id (:current-work (entry fa k)) :generation 1
                        :data {:title "A"}}]
                      {:frame fa})
    (is (= {:title "A"} (:data (entry fa k))))
    (is (nil? (entry fb k)))
    (rf.frame/destroy-frame! fa)
    (rf.frame/destroy-frame! fb)))

;; ---- passive subs -----------------------------------------------------------

(deftest passive-subs-project-the-entry
  (rf/reg-resource :sub/article (article-spec) article-spec-request)
  (let [k (gkey :sub/article {:slug "w"})
        q {:resource :sub/article :scope :rf.scope/global :params {:slug "w"}}]
    (testing "before any load: the idle empty state, and subscribing fetches nothing"
      (is (= {:status :idle :data nil :error nil :refresh-error nil
              :loading? false :fetching? false :stale? false :has-data? false
              :previous? false}
             @(rf/subscribe [:rf/resource q])))
      (is (nil? (entry k))))
    (ensure! :sub/article "w" [:app :s 1])
    (succeed! k {:title "Welcome"})
    (is (= :loaded (:status @(rf/subscribe [:rf/resource q]))))
    (is (true?  @(rf/subscribe [:rf.resource/has-data? q])))
    (is (false? @(rf/subscribe [:rf.resource/loading? q])))
    (is (= {:title "Welcome"} @(rf/subscribe [:rf.resource/data q])))))

(deftest stale-sub-derives-from-facts
  (rf/reg-resource :stl/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [k (gkey :stl/article {:slug "w"})
        q {:resource :stl/article :scope :rf.scope/global :params {:slug "w"}}]
    (ensure! :stl/article "w" [:app :st 1])
    (succeed! k {:title "W"})
    (is (false? @(rf/subscribe [:rf.resource/stale? q])))
    ;; Released first, so the invalidation stales rather than refetches.
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :st 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope :rf.scope/global :tags #{[:article "w"]}}])
    (is (true? @(rf/subscribe [:rf.resource/stale? q])))))

(deftest fresh-skip-decision-reads-causal-time-ms-not-ambient-clock
  ;; The fresh-skip gate gates a durable write, so it reads the ensure token's
  ;; :time-ms; the live host clock is decades past this scripted window.
  (rf/reg-resource :fsd/article (article-spec {:stale-after-ms 60000}) article-spec-request)
  (let [k  (gkey :fsd/article {:slug "w"})
        t0 1000000]
    (ensure! :fsd/article "w" [:app :fsd 1])
    (succeed! k {:title "W"} {:rf.cofx {:rf/time-ms t0}})
    (is (= (+ t0 60000) (:stale-at (entry k))))
    (doseq [[label time-ms fetch? gen] [["within the window serves cache" (+ t0 30000) false 1]
                                        ["past the window refetches" (+ t0 90000) true 2]]]
      (reset! last-managed-args nil)
      (rf/dispatch-sync [:rf.resource/ensure {:resource :fsd/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:app :fsd 1]}]
                        {:rf.cofx {:rf/time-ms time-ms}})
      (is (= [fetch? gen] [(some? @last-managed-args) (:generation (entry k))]) label))))

;; ---- owners, remove ---------------------------------------------------------

(deftest release-owner-drops-the-owner
  (rf/reg-resource :ro/article (article-spec) article-spec-request)
  (let [k (gkey :ro/article {:slug "w"})]
    (ensure! :ro/article "w" [:app :ro 1])
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :ro 1]}])
    (is (not (contains? (:active-owners (entry k)) [:app :ro 1])))))

(deftest remove-evicts-the-entry
  (rf/reg-resource :rm/article (article-spec) article-spec-request)
  (let [k (gkey :rm/article {:slug "w"})]
    (ensure! :rm/article "w" [:app :rm 1])
    (rf/dispatch-sync [:rf.resource/remove {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"}}])
    (is (nil? (entry k)))))

;; ---- reverse indexes --------------------------------------------------------

(deftest indexes-recompute-from-entries
  ;; Restore recomputes the indexes from :entries rather than trusting a snapshot.
  (let [k1 [:rf.scope/global :r/a {:id 1}]
        k2 [:rf.scope/global :r/b {:id 2}]
        rebuilt (rf.resources.state/recompute-indexes
                  {:entries {k1 {:tags #{:t1 :shared} :active-owners #{[:app 1]}}
                             k2 {:tags #{:t2 :shared} :active-owners #{[:app 1]}}}})]
    (is (= {:shared #{k1 k2} :t1 #{k1}}
           (select-keys (:tag-index rebuilt) [:shared :t1])))
    (is (= #{k1 k2} (get-in rebuilt [:owner-index [:app 1]])))))

;; reindex-keys maintains the indexes incrementally at mutation sites; the
;; result must equal the full rebuild after every op.

(defn- reindex-drift [old-entries new-entries touched]
  (let [old-subtree (rf.resources.state/recompute-indexes {:entries old-entries})
        incremental (rf.resources.state/reindex-keys (assoc old-subtree :entries new-entries)
                                                     old-entries touched)
        full        (rf.resources.state/recompute-indexes {:entries new-entries})]
    (when (not= (select-keys full [:tag-index :owner-index])
                (select-keys incremental [:tag-index :owner-index]))
      {:touched (vec touched) :full full :incremental incremental})))

(deftest reindex-keys-equals-full-rebuild-on-crafted-transitions
  ;; The random sequence below touches one key per step; these touch several.
  (testing "two keys removed at once"
    (is (nil? (reindex-drift {"a" {:tags #{:a :shared} :active-owners #{[:app 1]}}
                              "b" {:tags #{:b :shared} :active-owners #{[:app 1]}}
                              "c" {:tags #{:c} :active-owners #{}}}
                             {"c" {:tags #{:c} :active-owners #{}}}
                             ["a" "b"]))))
  (testing "an unchanged key in the touched set"
    (is (nil? (reindex-drift {"a" {:tags #{:t1} :active-owners #{[:app 1]}}
                              "b" {:tags #{:t1} :active-owners #{[:app 1]}}}
                             {"a" {:tags #{:t1} :active-owners #{[:app 1]}}
                              "b" {:tags #{:t2} :active-owners #{[:app 1]}}}
                             ["a" "b"])))))

(deftest reindex-keys-equals-full-rebuild-under-random-mutation-sequence
  ;; A deterministic LCG, so the sequence is identical on every host.
  (let [seed     (atom 2463534242)
        nextint  (fn [n]
                   (let [x (-> (* @seed 1103515245) (+ 12345) (bit-and 0x7fffffff))]
                     (reset! seed x)
                     (mod x n)))
        key-ids  (mapv #(str "k" %) (range 8))
        tags     [:t0 :t1 :t2 :shared]
        owners   [[:app 1] [:app 2] [:route :r 1] [:route :r 2]]
        rand-set (fn [pool]
                   (set (keep (fn [x] (when (zero? (nextint 2)) x)) pool)))
        drift    (loop [step 0 entries {} drift []]
                   (if (= step 600)
                     drift
                     (let [k           (nth key-ids (nextint (count key-ids)))
                           remove?     (and (contains? entries k) (zero? (nextint 4)))
                           new-entries (if remove?
                                         (dissoc entries k)
                                         (assoc entries k {:tags          (rand-set tags)
                                                           :active-owners (rand-set owners)}))
                           d           (reindex-drift entries new-entries [k])]
                       (recur (inc step) new-entries (cond-> drift d (conj (assoc d :step step)))))))]
    (is (= [] (take 1 drift)))))

;; ---- clear-resource ---------------------------------------------------------

(deftest clear-resource-disposes-runtime-state
  (rf/reg-resource :cr/article (article-spec) article-spec-request)
  (let [loaded-key   (gkey :cr/article {:slug "loaded"})
        inflight-key (gkey :cr/article {:slug "inflight"})]
    (ensure! :cr/article "loaded" [:app :cr 1])
    (succeed! loaded-key {:title "L"})
    (ensure! :cr/article "inflight" [:app :cr 2])
    (let [inflight-wid (:current-work (entry inflight-key))]
      (is (some? (rf.resources.work-ledger/get-record (runtime-db) inflight-wid))
          "precondition: the in-flight row is present")
      (rf.resources.registry/clear-resource :cr/article)
      (is (nil? (rf.resources.registry/resource-meta :cr/article)))
      (is (= [nil nil] [(entry loaded-key) (entry inflight-key)]))
      (is (empty? (get-in (runtime-db) (rf.resources.state/tag-index-path))))
      (is (empty? (get-in (runtime-db) (rf.resources.state/owner-index-path))))
      (testing "the disposed entries' ledger rows are dropped"
        (is (not-any? (fn [[_ r]] (contains? #{loaded-key inflight-key} (:resource/key r)))
                      (get-in (runtime-db) [:rf.runtime/work-ledger]))))
      (testing "a late reply for the cleared in-flight entry cannot recreate it"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key inflight-key :work/id inflight-wid
                            :generation 1 :data {:title "late"}}])
        (is (nil? (entry inflight-key)))))))

;; ---- the entries table is keyed on the CEDN-1 byte key-id -------------------
;; `(= [1 2 3] '(1 2 3))` is true, so keying on the scoped-key vector would
;; collapse a list-params entry and a vector-params entry onto one map key.

(deftest resources-introspection-keys-by-byte-key-id
  (rf/reg-resource :intro/article (article-spec) article-spec-request)
  (let [k1 (gkey :intro/article {:slug "one"})
        k2 (gkey :intro/article {:slug "two"})]
    (ensure! :intro/article "one" [:app :intro 1])
    (ensure! :intro/article "two" [:app :intro 2])
    (let [entries (entries-table)]
      (is (= #{(rf.resources.state/key-id k1) (rf.resources.state/key-id k2)} (set (keys entries))))
      (is (= #{k1 k2} (set (map :resource/key (vals entries))))))))

(deftest resources-introspection-keeps-cedn-distinct-scoped-keys-distinct
  (rf/reg-resource :intro/article (article-spec) article-spec-request)
  (let [kv (gkey :intro/article {:xs [1 2 3]})
        kl (gkey :intro/article {:xs '(1 2 3)})
        ev (assoc (rf.resources.state/empty-entry :intro/article kv) :status :loaded :data {:v 1})
        el (assoc (rf.resources.state/empty-entry :intro/article kl) :status :loaded :data {:l 1})]
    (is (= kv kl) "precondition: the scoped-key vectors are Clojure-=")
    (rf.frame/swap-runtime-db! :rf/default
      (fn [rdb] (-> (or rdb {})
                    (assoc-in (rf.resources.state/entry-path kv) ev)
                    (assoc-in (rf.resources.state/entry-path kl) el))))
    (let [entries (entries-table)
          ev'     (get entries (rf.resources.state/key-id kv))
          el'     (get entries (rf.resources.state/key-id kl))]
      (is (= 2 (count entries)))
      (is (= [{:v 1} {:l 1}] [(:data ev') (:data el')]))
      (is (vector? (-> ev' :resource/key (nth 2) :xs)))
      (is (seq? (-> el' :resource/key (nth 2) :xs))))))
