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
   [re-frame.resources.mutation-events :as mevents]
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

;; ===========================================================================
;; 11. rf2-3yyaur — patch / populate TARGET scoped key validation (fail-closed)
;; ===========================================================================

(defn- article-resource-spec []
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
   :tags (fn [{:keys [slug]} _] #{[:article slug]})})

;; The validation boundary itself is asserted via DIRECT calls (the
;; re-frame event loop catches a handler throw and surfaces it as
;; :rf.error/handler-exception rather than rethrowing to dispatch-sync's
;; caller — so a thrown-with-msg? around dispatch never sees it; the
;; codebase asserts validation throws at the fn boundary, exactly as
;; `mutation-registry-rejects-non-edn-params` does, and proves the
;; dispatch-path fail-closed behavior by OBSERVING no partial cache mutation).

(deftest validate-target-key-rejects-unregistered-resource
  ;; rf2-3yyaur — a controlled patch / populate targeting an UNREGISTERED
  ;; resource fails CLOSED (the patched / seeded entry would be unreachable by
  ;; any subscription).
  (testing "an unregistered resource id is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            [:rf.scope/global :r/never-registered {:slug "w"}]
            (fn [_] false) 'test :patches)))))

(deftest validate-target-key-rejects-malformed-key
  ;; rf2-3yyaur — a target that is not a 3-element scoped key is rejected.
  (testing "a 1-element key is malformed"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key! [:r/article] (constantly true) 'test :populates))))
  (testing "a non-vector target is malformed"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key! :r/article (constantly true) 'test :patches))))
  (testing "a non-keyword resource id is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key! [:rf.scope/global "article" {}] (constantly true) 'test :patches)))))

(deftest validate-target-key-rejects-reserved-scope-typo
  ;; rf2-3yyaur — a bare framework-reserved :rf.scope/* keyword outside the
  ;; closed enum (a typo) would silently write under a wrong scope — rejected.
  (testing ":rf.scope/glabal (a typo) is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            [:rf.scope/glabal :r/article {:slug "w"}] (constantly true) 'test :patches))))
  (testing "the closed reserved policy :rf.scope/global is a legitimate literal scope"
    (is (= [:rf.scope/global :r/article {:slug "w"}]
           (mstate/validate-target-key!
             [:rf.scope/global :r/article {:slug "w"}] (constantly true) 'test :patches)))))

(deftest validate-target-key-rejects-non-edn-params
  ;; rf2-3yyaur — a host value in the target's params / scope reaches the
  ;; cache-key boundary and is rejected (the EDN discipline resource params
  ;; follow).
  (testing "non-EDN params rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            [:rf.scope/global :r/article {:slug "w" :cb (fn [])}] (constantly true) 'test :patches))))
  (testing "non-EDN scope rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            [(fn []) :r/article {:slug "w"}] (constantly true) 'test :patches)))))

(deftest validate-target-map-canonicalizes-and-rejects-whole-map
  ;; rf2-3yyaur — one bad target rejects the WHOLE arm (no partial write), and
  ;; valid targets are re-keyed by the canonical scoped key.
  (testing "a single bad target rejects the whole map"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-map!
            {[:rf.scope/global :r/article {:slug "w"}] :ok
             [:rf.scope/glabal :r/article {:slug "x"}] :bad}
            (constantly true) :patches 'test))))
  (testing "an all-valid map is re-keyed by the canonical scoped key"
    (is (= {[:rf.scope/global :r/article {:slug "w"}] :v}
           (mstate/validate-target-map!
             {[:rf.scope/global :r/article {:slug "w"}] :v}
             (constantly true) :populates 'test))))
  (testing "an empty / nil map returns nil (no-op arm)"
    (is (nil? (mstate/validate-target-map! {} (constantly true) :patches 'test)))
    (is (nil? (mstate/validate-target-map! nil (constantly true) :patches 'test)))))

(deftest bad-patch-target-fails-closed-no-partial-cache-mutation
  ;; rf2-3yyaur — end-to-end: a mutation whose :patches targets an unregistered
  ;; resource fails on the success path BEFORE any cache mutation. The event
  ;; loop catches the throw, so we observe the fail-closed EFFECT: the bad
  ;; target's entry was never (partially) written, and the second VALID patch
  ;; in the same arm also did not land (one bad target rejects the whole arm).
  (rf/reg-resource :r/article (article-resource-spec))
  (let [good-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})
        bad-key  [:rf.scope/global :r/never-registered {:slug "w"}]]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :patches (fn [_p _r] {good-key (fn [old r] (merge old r))
                                            bad-key  (fn [old _] old)})})
    ;; seed the good entry so a (wrongly) partial patch would be observable
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old"})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :bad1}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "fail-closed: the good entry was NOT patched (the whole arm was rejected
              before any cache mutation), and the unregistered key was never written"
      (is (= {:title "old"} (:data (entry good-key))) "good entry unchanged")
      (is (nil? (entry bad-key)) "no partial write of the bad target"))))

(deftest valid-patch-target-still-applies
  ;; rf2-3yyaur — the happy path is UNCHANGED: a well-formed, registered,
  ;; serializable target patches normally (validation is transparent on valid
  ;; input, and canonicalizes the key so an alternate spelling still lands).
  (rf/reg-resource :r/article (article-resource-spec))
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/patch
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      ;; target params spelled in a different key order — must
                      ;; canonicalize to the same identity the read path stored.
                      :patches (fn [_p _r] {[:rf.scope/global :r/article {:slug "w"}]
                                            (fn [old result] (merge old result))})})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old" :views 1})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/patch :params {:slug "w"} :instance :ok1}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "the valid patch applied to the canonical entry"
      (is (= {:title "new" :views 1} (:data (entry rkey))))
      (is (= [rkey] (:affected-keys (instance :ok1)))))))

;; ===========================================================================
;; 12. rf2-agrjvk — before-request invalidation PRECEDES request lowering
;; ===========================================================================

(deftest before-request-invalidation-precedes-lowering
  ;; rf2-agrjvk — the contract says :before-request invalidation fires BEFORE
  ;; the request is lowered to transport. Prove it on the returned :fx VECTOR
  ;; (fx run in order): the :rf.resource/invalidate-tags dispatch must sit at a
  ;; LOWER index than the :rf.http/managed lower fx.
  (rf/reg-resource :r/article (article-resource-spec))
  (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :before-request}))
  (let [cofx   {:rf.db/runtime {} :rf.frame/id :rf/default :rf.resource/generation 0}
        out    (mevents/execute-handler
                 cofx [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :ord1}])
        fx     (:fx out)
        fx-ids (mapv first fx)
        inv-ix (->> fx (keep-indexed (fn [i [id sub]]
                                       (when (and (= :dispatch id)
                                                  (= :rf.resource/invalidate-tags (first sub))) i)))
                    first)
        low-ix (->> fx-ids (keep-indexed (fn [i id] (when (= :rf.http/managed id) i))) first)]
    (testing "both the invalidation dispatch and the managed-HTTP lower are present"
      (is (some? inv-ix) "a before-request invalidation dispatch was emitted")
      (is (some? low-ix) "the managed-HTTP request was lowered"))
    (testing "EP-0003 §Mutations / rf2-agrjvk — invalidation is ordered BEFORE
              the request lowering in the fx vector"
      (is (< inv-ix low-ix)
          (str "invalidation (index " inv-ix ") must precede lowering (index "
               low-ix "); got fx ids " (pr-str fx-ids))))))

(deftest no-before-request-invalidation-leaves-order-intact
  ;; rf2-agrjvk — a default (:after-success) timing emits NO before-request
  ;; dispatch; the lower fx is still present and the reorder is a no-op.
  (rf/reg-mutation :m/save (save-article-spec)) ;; default :after-success
  (let [cofx {:rf.db/runtime {} :rf.frame/id :rf/default :rf.resource/generation 0}
        out  (mevents/execute-handler
               cofx [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :ord2}])
        fx-ids (mapv first (:fx out))]
    (testing "no before-request invalidation dispatch is emitted on the default timing"
      (is (not-any? (fn [[id sub]] (and (= :dispatch id)
                                        (= :rf.resource/invalidate-tags (first sub))))
                    (:fx out))))
    (testing "the managed-HTTP lower fx is still present"
      (is (some #{:rf.http/managed} fx-ids)))))

;; ===========================================================================
;; 13. rf2-e8wj5t — serializable mutation INSTANCE ids (reject host values)
;; ===========================================================================

(deftest execute-rejects-non-serializable-instance-id-fails-closed
  ;; rf2-e8wj5t — a non-serializable caller-supplied instance id is rejected
  ;; BEFORE any runtime-db / work-ledger write or HTTP lowering (the id is
  ;; durable + trace-visible + epoch-restore-safe). The event loop catches the
  ;; throw, so we observe the fail-closed EFFECT: nothing lowered to transport,
  ;; and no instance row written. (The throw itself is asserted directly in
  ;; `validate-instance-id-accepts-scalars-and-vectors`.)
  (rf/reg-mutation :m/save (save-article-spec))
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance (fn [])}])
  (testing "nothing was lowered to transport (fail-closed BEFORE the write)"
    (is (nil? @last-managed-args)))
  (testing "no instance row was written"
    (is (empty? (:instances (rf/mutations {:frame :rf/default}))))))

(deftest validate-instance-id-accepts-scalars-and-vectors
  ;; rf2-e8wj5t — valid scalar / vector instance ids pass (they ARE
  ;; epoch / restore-safe serializable EDN).
  (testing "scalar ids pass"
    (is (= :form/save-1 (mstate/validate-instance-id! :form/save-1 'test)))
    (is (= "inst-7" (mstate/validate-instance-id! "inst-7" 'test)))
    (is (= 42 (mstate/validate-instance-id! 42 'test))))
  (testing "a vector id (e.g. a row-keyed form instance) passes"
    (is (= [:row 7] (mstate/validate-instance-id! [:row 7] 'test))))
  (testing "nil passes (the events layer then mints a generated id)"
    (is (nil? (mstate/validate-instance-id! nil 'test))))
  (testing "a host value is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-non-serializable-instance-id"
          (mstate/validate-instance-id! (fn []) 'test)))))

(deftest execute-with-valid-vector-instance-id
  ;; rf2-e8wj5t — a vector instance id (a common row-keyed form shape) is
  ;; accepted end-to-end and stored on the durable instance.
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance [:row 7]}])
  (testing "the instance is keyed + stored under the serializable vector id"
    (let [i (instance [:row 7])]
      (is (= :pending (:status i)))
      (is (= [:row 7] (:instance/id i))))))
