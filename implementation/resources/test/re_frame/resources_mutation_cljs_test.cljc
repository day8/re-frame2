(ns re-frame.resources-mutation-cljs-test
  "Mutation over managed HTTP (rf2-dwme29, Spec 016 §Deferred slices /
  EP-0003 §Mutations — the first public-beta gate, the LAST core slice).

  These JVM+CLJS unit tests pin the slice's semantics:

    1. reg-mutation / clear-mutation registration + introspection, and the
       fail-closed authoring boundary (missing :request / :params-schema);
    2. :rf.mutation/execute mints an INSTANCE keyed by a caller-supplied or
       generated instance id, and lowers the write through the SAME
       managed-HTTP transport (runtime-owned reply addressing);
    3. concurrency — two submissions of the SAME mutation id under
       different instance ids never clobber each other's pending/result;
    4. success → controlled resource PATCH / POPULATE then tag invalidation
       (composing with the landed :rf.resource/invalidate-tags);
    5. failure settles the instance :error (no :refresh-error analogue);
       optional after-failure / after-settle invalidation timing;
    6. before-request invalidation timing;
    7. generation / work-id STALE SUPPRESSION — a superseded reply NEVER
       overwrites a newer instance;
    8. :rf.mutation/clear is the causal instance reset (clears the runtime
       row, aborts in-flight), distinct from clear-mutation (registration);
    9. the passive :rf.mutation/* subs project the instance view-model.

  The transport is exercised end-to-end by overriding the
  `:rf.http/managed` fx with a capturing stub that synthesises the
  transport's reply-event-append shape (the genuine 3-element internal
  reply event the live transport produces — `(conj on-success {:kind
  :success :value …})`)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.mutation-runtime :as mstate]
   [re-frame.resources.mutation-registry :as mreg]
   [re-frame.resources.timers :as timers]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing reply stub below.
   [re-frame.http-managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport that REPLAYS the real reply-append shape ----------

(def ^:private last-managed-args (atom nil))
(def ^:private scheduled-timers (atom []))

(defn- capturing-transport-fixture
  [f]
  (reset! last-managed-args nil)
  (reset! scheduled-timers [])
  (state/reset-cache!)
  (timers/reset-cache!)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  ;; capture the host-side stale / GC timer arming so the success handler's
  ;; emission is asserted deterministically WITHOUT a real wall-clock timer
  ;; firing (the timer-table primitive is tested directly in the
  ;; invalidation/GC suite).
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ctx args] (swap! scheduled-timers conj args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db
  ([] (runtime-db :rf/default))
  ([frame-id] (rf/runtime-db-value frame-id)))

(defn- instance
  ([instance-id] (instance :rf/default instance-id))
  ([frame-id instance-id]
   (get-in (runtime-db frame-id) (mstate/instance-path instance-id))))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:kind :success :value result})))

(defn- reply-failure! [args failure]
  (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure})))

(defn- save-article-spec
  ([] (save-article-spec {}))
  ([overrides]
   (merge {:params-schema [:map [:slug :string]]
           :request       (fn [{:keys [slug]} _ctx]
                            {:request {:method :put :url (str "/api/articles/" slug)
                                       :body  {:slug slug}}})
           :invalidates   (fn [{:keys [slug]} _result]
                            #{[:article slug] [:article-list]})}
          overrides)))

;; ===========================================================================
;; 1. Registration + introspection + fail-closed authoring boundary
;; ===========================================================================

(deftest reg-mutation-registers-and-introspects
  (rf/reg-mutation :m/save (save-article-spec))
  (testing "the registered spec is introspectable"
    (is (= (vec [:m/save]) (filter #{:m/save} (:mutation-ids (rf/mutations)))))
    (is (fn? (:request (rf/mutation-meta :m/save))))
    (is (fn? (:invalidates (rf/mutation-meta :m/save)))))
  (testing "clear-mutation removes the registration"
    (rf/clear-mutation :m/save)
    (is (nil? (rf/mutation-meta :m/save)))))

(deftest reg-mutation-fail-closed
  (testing "EP-0003 §Mutations — a mutation spec MUST declare :request"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-mutation-spec"
          (rf/reg-mutation :m/no-req {:params-schema [:map]}))))
  (testing "and :params-schema"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-mutation-spec"
          (rf/reg-mutation :m/no-schema {:request (fn [_ _] {:request {:url "/x"}})})))))

(deftest execute-unregistered-is-loud
  (testing "EP-0003 §Mutations — :rf.mutation/execute on an unregistered
            mutation never reaches the transport"
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/nope :params {} :instance :i1}])
    (is (nil? @last-managed-args))))

;; ===========================================================================
;; 2. execute mints an instance + lowers the write (runtime-owned addressing)
;; ===========================================================================

(deftest execute-mints-instance-and-lowers-write
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :form/save-1}])
  (testing "the instance row is :pending, keyed by the caller-supplied id"
    (let [i (instance :form/save-1)]
      (is (= :pending (:status i)))
      (is (= :m/save (:mutation/id i)))
      (is (= 1 (:generation i)))
      (is (some? (:current-work i)))))
  (testing "the write lowered through managed HTTP with runtime-owned reply
            addressing targeting the MUTATION internal replies"
    (let [args @last-managed-args]
      (is (some? (:request-id args)))
      (is (= [:rf.mutation.internal/succeeded] (subvec (:on-success args) 0 1)))
      (is (= [:rf.mutation.internal/failed]    (subvec (:on-failure args) 0 1)))
      (let [vp (nth (:on-success args) 1)]
        (is (= :form/save-1 (:instance-id vp)))
        (is (= :m/save (:mutation-id vp)))
        (is (= 1 (:generation vp)))
        (is (= :rf/default (:rf.frame/id vp))))
      (testing "the app :request body passes through unchanged"
        (is (= {:method :put :url "/api/articles/w" :body {:slug "w"}} (:request args)))))))

(deftest execute-generates-instance-id-when-absent
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"}}])
  (testing "EP-0003 §Mutations — a generated instance id is used when the
            caller supplies none"
    (let [insts (:instances (rf/mutations {:frame :rf/default}))]
      (is (= 1 (count insts)))
      (is (= :pending (:status (val (first insts))))))))

;; ===========================================================================
;; 3. Concurrency — same mutation id, different instances, no clobber
;; ===========================================================================

(deftest concurrent-submissions-do-not-clobber
  (rf/reg-mutation :comment/add
                   {:params-schema [:map [:body :string]]
                    :request (fn [{:keys [body]} _] {:request {:method :post :url "/c" :body {:body body}}})})
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :comment/add :params {:body "one"} :instance :c1}])
  (let [args1 @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :comment/add :params {:body "two"} :instance :c2}])
    (let [args2 @last-managed-args]
      (testing "both instances are :pending and independent"
        (is (= :pending (:status (instance :c1))))
        (is (= :pending (:status (instance :c2)))))
      (testing "settling :c2 leaves :c1 untouched (EP-0003 §Mutations — keyed
                by instance id so concurrent submissions don't clobber)"
        (reply-success! args2 {:id 2})
        (is (= :success (:status (instance :c2))))
        (is (= {:id 2} (:result (instance :c2))))
        (is (= :pending (:status (instance :c1))) "c1 still pending"))
      (testing "then settling :c1"
        (reply-success! args1 {:id 1})
        (is (= :success (:status (instance :c1))))
        (is (= {:id 1} (:result (instance :c1))))))))

;; ===========================================================================
;; 4. Success → patch / populate then invalidation
;; ===========================================================================

(deftest success-invalidates-tags-and-refetches-active-owners
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (rf/reg-mutation :m/save (save-article-spec))
  ;; load + own the article resource so the invalidation refetches it
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :article]}])
    (reply-success! @last-managed-args {:title "old"})
    (is (= :loaded (:status (entry rkey))))
    (reset! last-managed-args nil)
    ;; run the mutation
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :s1}])
    (let [mut-args @last-managed-args]
      (reset! last-managed-args nil)
      (reply-success! mut-args {:title "new"})
      (testing "the mutation instance settles :success"
        (is (= :success (:status (instance :s1))))
        (is (= {:title "new"} (:result (instance :s1)))))
      (testing "EP-0003 §Mutations — success invalidated the article tag, and
                the active-owner article resource was refetched (a new managed
                GET lowered, the entry back in flight)"
        (let [e (entry rkey)]
          ;; the active-owner entry refetched: it is in flight again
          ;; (:invalidated-at was cleared by the refetch's start-load), and a
          ;; fresh managed-HTTP GET was lowered.
          (is (contains? #{:loading :fetching} (:status e)) "entry refetching")
          (is (some? @last-managed-args) "a refetch GET was lowered")
          (is (= {:method :get :url "/a/w"} (:request @last-managed-args))))))))

(deftest success-patches-resource-entry
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/patch
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :patches (fn [_params result]
                                 {rkey (fn [old _result] (merge old result))})})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old" :views 1})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/patch :params {:slug "w"} :instance :p1}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "EP-0003 §Mutations — the controlled patch transformed the cached
              entry's :data in place (kept :views, updated :title)"
      (let [e (entry rkey)]
        (is (= {:title "new" :views 1} (:data e)))
        (is (= :loaded (:status e)))
        (is (nil? (:invalidated-at e)) "patch freshened the entry")))
    (testing "the affected-keys trace reservation records the patched key"
      (is (= [rkey] (:affected-keys (instance :p1)))))))

(deftest success-populates-resource-entry
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :populates (fn [_params result] {rkey result})})
    ;; no prior ensure — the populate SEEDS the entry from the mutation result
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :pop1}])
    (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
    (testing "EP-0003 §Mutations — the controlled populate seeded a :loaded
              entry from the result, carrying the resource's own tags"
      (let [e (entry rkey)]
        (is (= :loaded (:status e)))
        (is (= {:slug "w" :title "Fresh"} (:data e)))
        (is (= #{[:article "w"]} (:tags e)))))))

(deftest success-populate-arms-stale-and-gc-timers
  ;; rf2-h4cv5e — a mutation :populates seeds a fresh, OWNERLESS :loaded entry
  ;; with a durable :stale-at / :gc-after-ms policy. The success handler MUST
  ;; arm the advisory stale / GC timers for it (mirroring the resource read
  ;; path) — otherwise the populated entry would carry a GC policy but NO armed
  ;; reaper, lingering past :gc-after-ms (a cache-growth completeness gap).
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})
                    :stale-after-ms 60000
                    :gc-after-ms    300000})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :populates (fn [_params result] {rkey result})})
    (reset! scheduled-timers [])
    ;; no prior ensure — the populate SEEDS an ownerless entry
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :pgc1}])
    (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
    (testing "the populated entry is ownerless (no liveness lease)"
      (is (empty? (:active-owners (entry rkey)))))
    (testing "rf2-h4cv5e — the success handler armed the advisory stale / GC
              timers for the populated key, mirroring the resource read path's
              :rf.resource/schedule-timers emission"
      (is (= 1 (count @scheduled-timers)))
      (let [args (first @scheduled-timers)]
        (is (= rkey (:resource-key args)))
        (is (= :rf/default (:frame-id args)))
        (is (= 60000 (:stale-delay-ms args)))
        (is (= 300000 (:gc-delay-ms args)) "a GC timer is armed for the populated entry")
        (is (false? (:server? args)) "client frame — not SSR-gated")))
    (testing "rf2-h4cv5e — the populated ownerless entry is GC-eligible: a
              fired GC timer (re-checking owners + generation) removes it"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource-key rkey}])
      (is (nil? (entry rkey)) "GC removed the inactive populated entry"))))

(deftest success-patch-arms-timers-for-policy-keys
  ;; rf2-h4cv5e — a :patches refresh of an existing entry re-arms its advisory
  ;; timers from the resource policy too (the patch moved :loaded-at /
  ;; :stale-at forward, so the prior timer's basis is stale).
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})
                    :stale-after-ms 60000
                    :gc-after-ms    300000})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/patch
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :patches (fn [_params result]
                                 {rkey (fn [old _result] (merge old result))})})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old" :views 1})
    (reset! scheduled-timers [])
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/patch :params {:slug "w"} :instance :pat-gc1}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "rf2-h4cv5e — the patch re-armed the entry's stale / GC timers"
      (is (= 1 (count @scheduled-timers)))
      (let [args (first @scheduled-timers)]
        (is (= rkey (:resource-key args)))
        (is (= 60000 (:stale-delay-ms args)))
        (is (= 300000 (:gc-delay-ms args)))))))

(deftest success-populate-no-policy-arms-no-timers
  ;; rf2-h4cv5e — a populate of a resource declaring NO stale / GC policy arms
  ;; NO timers (no schedule-timers fx) — exactly as the read path.
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :populates (fn [_params result] {rkey result})})
    (reset! scheduled-timers [])
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :pnp1}])
    (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
    (testing "no policy → no schedule-timers fx"
      (is (= [] @scheduled-timers)))))

;; ===========================================================================
;; 5. Failure settles :error
;; ===========================================================================

(deftest failure-settles-instance-error
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :f1}])
  (testing "EP-0003 §Mutations — a failed write settles the instance :error
            with the appended transport failure envelope (no :refresh-error)"
    (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
    (let [i (instance :f1)]
      (is (= :error (:status i)))
      (is (= {:kind :rf.http/http-5xx :status 503} (:error i)))
      (is (nil? (:result i))))))

(deftest after-failure-invalidation-timing
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :after-failure}))
    ;; ensure WITHOUT an owner so the invalidation leaves the matched entry
    ;; stale (an ownerless entry is left stale / GC-eligible, NOT refetched —
    ;; so :invalidated-at stays observable rather than being cleared by a
    ;; refetch's start-load).
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"}}])
    (reply-success! @last-managed-args {:title "x"})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :af1}])
    (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
    (testing "EP-0003 §Mutations — :after-failure timing invalidated the tag on
              the FAILURE path (re-read authoritative state after a rejected write)"
      (is (some? (:invalidated-at (entry rkey)))))))

;; ===========================================================================
;; 6. before-request invalidation timing
;; ===========================================================================

(deftest before-request-invalidation-timing
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :before-request}))
    ;; ownerless ensure — the invalidation leaves the entry stale (observable
    ;; via :invalidated-at) rather than refetching it.
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"}}])
    (reply-success! @last-managed-args {:title "x"})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :br1}])
    (testing "EP-0003 §Mutations — :before-request timing invalidated the tag
              BEFORE the write reply landed"
      (is (some? (:invalidated-at (entry rkey)))))))

;; ===========================================================================
;; 7. Stale suppression — a superseded reply never overwrites a newer instance
;; ===========================================================================

(deftest stale-mutation-reply-suppressed
  (rf/reg-mutation :m/save (save-article-spec))
  ;; two executes under the SAME instance id mint different generations; the
  ;; first reply is now stale against the live (gen-2) instance.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :i}])
  (let [gen1-args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :i}])
    (is (= 2 (:generation (instance :i))))
    (testing "Spec 016 §Cancellation is opportunistic; stale suppression is
              mandatory — the STALE gen-1 reply NEVER settles the newer instance"
      (reply-success! gen1-args {:stale "result"})
      (let [i (instance :i)]
        (is (= :pending (:status i)) "still pending on the current gen")
        (is (= 2 (:generation i)) "generation unchanged")
        (is (nil? (:result i)) "stale reply did not write a result")))
    (testing "the CURRENT gen-2 reply settles normally"
      (reply-success! @last-managed-args {:fresh "result"})
      (is (= :success (:status (instance :i))))
      (is (= {:fresh "result"} (:result (instance :i)))))))

;; ===========================================================================
;; 8. :rf.mutation/clear — causal instance reset
;; ===========================================================================

(deftest clear-resets-instance
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :clr1}])
  (reply-success! @last-managed-args {:ok true})
  (is (= :success (:status (instance :clr1))))
  (testing "EP-0003 §Mutations — :rf.mutation/clear clears the runtime
            instance row (the causal reset, NOT a form-error reset)"
    (rf/dispatch-sync [:rf.mutation/clear {:instance :clr1}])
    (is (nil? (instance :clr1)))))

(deftest clear-by-mutation-id-clears-all-instances
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "a"} :instance :a1}])
  (reply-success! @last-managed-args {:ok 1})
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "b"} :instance :b1}])
  (reply-success! @last-managed-args {:ok 2})
  (testing "clear by :mutation drops every instance of that mutation id"
    (rf/dispatch-sync [:rf.mutation/clear {:mutation :m/save}])
    (is (nil? (instance :a1)))
    (is (nil? (instance :b1)))))

;; ===========================================================================
;; 9. Passive subs project the instance view-model
;; ===========================================================================

(deftest mutation-subs-project-view-model
  (rf/reg-mutation :m/save (save-article-spec))
  (testing "no instance — idle empty-state"
    (is (= :idle @(rf/subscribe [:rf.mutation/status {:instance :sub1}])))
    (is (false? @(rf/subscribe [:rf.mutation/pending? {:instance :sub1}]))))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :sub1}])
  (testing ":pending while in flight"
    (is (= :pending @(rf/subscribe [:rf.mutation/status {:instance :sub1}])))
    (is (true? @(rf/subscribe [:rf.mutation/pending? {:instance :sub1}]))))
  (reply-success! @last-managed-args {:saved true})
  (testing ":success settles the result"
    (let [st @(rf/subscribe [:rf.mutation/state {:instance :sub1}])]
      (is (= :success (:status st)))
      (is (:success? st))
      (is (:settled? st))
      (is (= {:saved true} (:result st))))
    (is (= {:saved true} @(rf/subscribe [:rf.mutation/result {:instance :sub1}])))))

;; ===========================================================================
;; 10. params canonicalization (the :invalidates / :patches close over them)
;; ===========================================================================

(deftest execute-canonicalizes-and-stores-params
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :cp1}])
  (testing "the instance stores the canonical params (serializable, for Xray)"
    (is (= {:slug "w"} (:params (instance :cp1))))))

(deftest mutation-registry-rejects-non-edn-params
  (rf/reg-mutation :m/save (save-article-spec))
  (testing "EP-0003 §Mutations — a host value in params is rejected at the
            cache-key boundary (mutation reuses the resource EDN discipline)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (mreg/validate+canonicalize-params
            :m/save (rf/mutation-meta :m/save) {:slug "w" :cb (fn [])}
            'test)))))
