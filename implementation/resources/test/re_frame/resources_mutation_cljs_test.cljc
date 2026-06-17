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
   [re-frame.reply :as reply]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.mutation-runtime :as mstate]
   [re-frame.resources.mutation-events :as mevents]
   [re-frame.resources.mutation-registry :as mreg]
   ;; work-ledger: used by the cross-frame request-id correlation assertions
   ;; (rf2-sxyrzk). The per-suite `(timers/reset-cache!)` was dropped with the
   ;; rf2-784223 fixture consolidation (shared reset hook clears timer caches),
   ;; so the `timers` alias is no longer required here.
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing reply stub below.
   [re-frame.http-managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport that REPLAYS the real reply-append shape ----------

(def ^:private last-managed-args (atom nil))
(def ^:private scheduled-timers (atom []))

(defn- capturing-transport-fixture
  ;; rf2-784223: the shared `make-reset-runtime-fixture`'s
  ;; `:resources/reset-resources!` post-dispose hook already clears the
  ;; resource state + timer host caches before this fixture runs — no
  ;; per-suite reset is repeated here.
  [f]
  (reset! last-managed-args nil)
  (reset! scheduled-timers [])
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

(defn- mutation-record
  "The serializable work-ledger record for a mutation INSTANCE's current
  attempt — found by scanning the frame's ledger for the row whose
  `:work/id` embeds `[:rf.mutation instance-id]`. The mutation work-id head
  is `[:rf.work/resource [:rf.mutation instance-id] generation]`, so a row
  scan keyed on the embedded instance key is the stable test reader (the
  generation is runtime-allocated)."
  ([instance-id] (mutation-record :rf/default instance-id))
  ([frame-id instance-id]
   (->> (vals (get-in (runtime-db frame-id) [:rf.runtime/work-ledger]))
        (filter (fn [r] (= [:rf.mutation instance-id]
                           (second (:work/id r)))))
        first)))

(defn- reply-success!
  ([args result] (rf/dispatch-sync (conj (:on-success args) {:kind :success :value result})))
  ;; EP-0010: a fixture may script the reply token's :rf.cofx to pin
  ;; the host :completed-at (the managed transport stamps it on the reply
  ;; dispatch in live code).
  ([args result opts] (rf/dispatch-sync (conj (:on-success args) {:kind :success :value result}) opts)))

(defn- reply-failure!
  ([args failure] (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure})))
  ([args failure opts] (rf/dispatch-sync (conj (:on-failure args) {:kind :failure :failure failure}) opts)))

(defn- art-target
  "The map-form exact target (EP-0016 Rider 2 — the only public input form for
  `:populates` / `:patches`) for the `:r/article {:slug \"w\"}` global key the
  patch/populate tests use. Its canonical STORAGE key is
  `(state/scoped-resource-key :rf.scope/global :r/article {:slug \"w\"})`, the
  `rkey` the entry-lookup assertions read."
  ([] (art-target "w"))
  ([slug] {:resource :r/article :params {:slug slug} :scope :rf.scope/global}))

(defn- record-mutation-traces!
  "Run `body-fn` with a trace listener installed; return the vector of every
  `:rf.mutation/*`-operation trace event emitted during it (capture order)."
  [body-fn]
  (let [seen (atom [])
        k    ::mutation-trace-recorder]
    (trace-tooling/register-listener!
      k (fn [ev]
          (when (and (keyword? (:operation ev))
                     (= "rf.mutation" (namespace (:operation ev))))
            (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- record-target-skipped-warnings!
  "Run `body-fn` with a trace listener installed; return the vector of every
  `:rf.warning/mutation-target-skipped` warning emitted during it (rf2-1vpbld)."
  [body-fn]
  (let [seen (atom [])
        k    ::target-skipped-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.warning/mutation-target-skipped (:operation ev))
                   (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

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

(deftest reg-mutation-rejects-invalidate-timing-typo
  ;; rf2-t8j7oj — :invalidate-timing is a CLOSED four-value enum (Spec 016
  ;; §Mutations). A typo (`:after-succes`) would register cleanly and then
  ;; silently skip every invalidation timing branch at runtime
  ;; (`(or (:invalidate-timing spec) :after-success)` only defaults nil; a
  ;; typo is neither nil nor matched by the `#{…}` timing guards). Reject it
  ;; loudly AT REGISTRATION rather than as a silent runtime no-op.
  (testing "a typo'd :invalidate-timing is rejected at reg-mutation"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-mutation-spec"
          (rf/reg-mutation :m/typo
                           (save-article-spec {:invalidate-timing :after-succes})))))
  (testing "a non-keyword :invalidate-timing is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-mutation-spec"
          (rf/reg-mutation :m/non-kw
                           (save-article-spec {:invalidate-timing "after-success"})))))
  (testing "every value in the closed enum registers cleanly"
    (doseq [timing mreg/invalidate-timings]
      (rf/reg-mutation :m/ok (save-article-spec {:invalidate-timing timing}))
      (is (= timing (:invalidate-timing (rf/mutation-meta :m/ok)))
          (str "valid timing " timing " registered"))
      (rf/clear-mutation :m/ok)))
  (testing "an OMITTED :invalidate-timing is valid (nil → :after-success at runtime)"
    (rf/reg-mutation :m/default (save-article-spec))
    (is (nil? (:invalidate-timing (rf/mutation-meta :m/default))))
    (rf/clear-mutation :m/default)))

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

(deftest execute-started-at-from-token-time-ms
  ;; rf2-dsyqmz / EP-0010 §Resources, Mutations, And Work-Ledger Timestamps:
  ;; :rf.mutation/execute writes the durable instance :started-at from the
  ;; TRIGGERING TOKEN'S :time-ms (the causal world input), NOT an ambient
  ;; clock read in the reducer. Scripting the dispatch's :rf.cofx
  ;; pins it; the same execute token mints the same :started-at
  ;; (replay-stable).
  (rf/reg-mutation :m/save (save-article-spec))
  (let [t1 1781078400123]
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :st/save-1}]
                      {:rf.cofx {:rf/time-ms t1}})
    (testing "the instance :started-at is EXACTLY the token :time-ms (not now)"
      (is (= t1 (:started-at (instance :st/save-1)))))))

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
                                 {(art-target) (fn [old _result] (merge old result))})})
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

(deftest success-settled-at-and-populate-loaded-at-from-reply-completed-at
  ;; rf2-40dqi6 / EP-0010 §Resources, Mutations: a terminal mutation success
  ;; reply writes the instance :settled-at from the reply completion time,
  ;; and ANY resource patch/populate :loaded-at the mutation produces uses
  ;; that SAME causal completion time (off the reply token, never an ambient
  ;; read in the handler). The host :completed-at rides the reply event's
  ;; :rf.cofx :time-ms; scripting it pins both.
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})
                    :stale-after-ms 60000})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})
        completed-at 1781078400456]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :populates (fn [_params result] {(art-target) result})})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :ms1}])
    (reply-success! @last-managed-args {:title "seed"} {:rf.cofx {:rf/time-ms completed-at}})
    (testing "the instance :settled-at is EXACTLY the reply completion time"
      (is (= completed-at (:settled-at (instance :ms1)))))
    (testing "the populated entry's :loaded-at is the SAME causal completion
              time, and :stale-at = :loaded-at + :stale-after-ms"
      (let [e (entry rkey)]
        (is (= completed-at (:loaded-at e)))
        (is (= (+ completed-at 60000) (:stale-at e)))))))

(deftest failure-settled-at-from-reply-completed-at
  ;; rf2-r65m41 / EP-0010 §Resources, Mutations + §Managed Effects: a terminal
  ;; mutation FAILURE reply writes :settled-at from the reply completion time
  ;; carried on the failure reply token — the handler MUST NOT re-read the
  ;; clock. The host :completed-at rides the reply event's :rf.cofx
  ;; :time-ms; scripting it pins :settled-at (replay-stable).
  (rf/reg-mutation :m/save (save-article-spec))
  (let [completed-at 1781078999999]
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :mf1}])
    (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}
                    {:rf.cofx {:rf/time-ms completed-at}})
    (testing "the instance settles :error with :settled-at = the reply
              completion time (not now)"
      (is (= :error (:status (instance :mf1))))
      (is (= completed-at (:settled-at (instance :mf1)))))))

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
                      :populates (fn [_params result] {(art-target) result})})
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
                      :populates (fn [_params result] {(art-target) result})})
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
        (is (= rkey (:resource/key args)))
        (is (= :rf/default (:frame-id args)))
        (is (= 60000 (:stale-delay-ms args)))
        (is (= 300000 (:gc-delay-ms args)) "a GC timer is armed for the populated entry")
        (is (false? (:server? args)) "client frame — not SSR-gated")))
    (testing "rf2-h4cv5e — the populated ownerless entry is GC-eligible: a
              fired GC timer (re-checking owners + generation) removes it"
      (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key rkey}])
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
                                 {(art-target) (fn [old _result] (merge old result))})})
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
        (is (= rkey (:resource/key args)))
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
                      :populates (fn [_params result] {(art-target) result})})
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

(deftest stale-mutation-suppressed-trace-carries-canonical-reply-envelope
  ;; rf2-mn4j89 / rf2-hh8nzd / rf2-uwqs7l — the canonical :status :stale reply
  ;; envelope rides the PRODUCTION mutation stale-suppression trace. The
  ;; behaviour-only `stale-mutation-reply-suppressed` above PASSED SILENTLY
  ;; while the production stale branch discarded the canonical reply; these
  ;; assertions FAIL before rf2-mn4j89 and pin the fix.
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :rv}])
  (let [gen1-args @last-managed-args]
    ;; supersede with a second execute under the SAME instance id → gen 2 live.
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :rv}])
    (is (= 2 (:generation (instance :rv))))
    (testing "rf2-mn4j89 — the STALE gen-1 reply is recorded :status :stale /
              :work/status :suppressed via the shared substrate, with the
              carried-vs-current (1 vs 2) generation pair on the production
              :rf.mutation/stale-suppressed trace; NO durable write"
      (let [wid1   (-> gen1-args :on-success (nth 1) :work/id)
            traces (record-mutation-traces!
                     #(reply-success! gen1-args {:stale "result"}))
            sup    (first (filterv #(= :rf.mutation/stale-suppressed (:operation %)) traces))]
        (is (some? sup) ":rf.mutation/stale-suppressed fired for the stale reply")
        (let [tags (:tags sup)]
          ;; bespoke facts preserved (additive, not replaced)
          (is (= :rv      (:instance tags)))
          (is (= :success (:outcome tags)))
          ;; CANONICAL reply-envelope vocabulary via the shared substrate
          (is (= :stale (:rf.reply/status tags))
              "the canonical :status :stale reply IS produced via re-frame.reply")
          (is (= :suppressed (:rf.reply/work-status tags)))
          (is (= :rf.mutation/superseded (:rf.reply/stale-reason tags)))
          (is (= wid1 (:rf.reply/work-id tags)) "joined to :work/id")
          (let [corr (:rf.reply/correlation tags)]
            (is (= 1 (-> corr :generation :carried)) "carried gen off the stale token")
            (is (= 2 (-> corr :generation :current)) "current = the LIVE instance gen")
            (is (= :rv (:instance/id corr)))))
        ;; (2)/(5) the app target did NOT run — the instance was NOT settled
        ;; by the stale reply (still :pending on gen 2, no :result written).
        (let [i (instance :rv)]
          (is (= :pending (:status i)) "instance still pending on the current gen")
          (is (= 2 (:generation i)) "generation unchanged")
          (is (nil? (:result i)) "stale reply did NOT write a result"))))))

;; ===========================================================================
;; 7b. rf2-jzh5gq — a mutation reply whose stamped :rf.frame/id does not match
;;     the RECEIVING frame is REJECTED (the mutation analogue of the resource
;;     cross-frame reply test). Two frames at the same instance/generation: a
;;     misrouted reply must NOT durably settle the wrong frame.
;; ===========================================================================

(deftest cross-frame-mutation-reply-rejected-without-mutating-receiving-frame
  (rf/reg-mutation :m/save (save-article-spec))
  (let [all-args (atom [])]
    (rf/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (let [fa :xfm/frame-a
          fb :xfm/frame-b]
      (rf/reg-frame fa {:doc "frame A"})
      (rf/reg-frame fb {:doc "frame B"})
      ;; both frames execute the SAME mutation under the SAME instance id →
      ;; the SAME frame-local work-id + generation in each frame.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :form/x}]
                        {:frame fa})
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :form/x}]
                        {:frame fb})
      (let [args-a (first @all-args)]
        (testing "rf2-jzh5gq — frame A's reply (payload stamped :rf.frame/id = A)
                  dispatched INTO frame B is rejected: frame B's pending
                  instance is NOT settled (no cross-frame durable write even at
                  the same instance/generation)"
          ;; dispatch frame A's reply (its payload carries :rf.frame/id = A)
          ;; into the WRONG frame B.
          (rf/dispatch-sync (conj (:on-success args-a) {:kind :success :value {:ok true}})
                            {:frame fb})
          (is (= :pending (:status (instance fb :form/x)))
              "frame B's instance untouched by frame A's misrouted reply")
          (is (nil? (:result (instance fb :form/x))) "no cross-frame result written")
          (is (= :pending (:status (instance fa :form/x)))
              "frame A's own instance also still pending (its reply went to B)"))
        (testing "frame A's reply dispatched into frame A DOES settle it"
          (rf/dispatch-sync (conj (:on-success args-a) {:kind :success :value {:ok true}})
                            {:frame fa})
          (is (= :success (:status (instance fa :form/x))) "frame A settled by its own reply"))))))

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
  (testing "no instance — idle empty-state (incl. EP-0019 :optimistic? false)"
    (is (= :idle @(rf/subscribe [:rf.mutation/status {:instance :sub1}])))
    (is (false? @(rf/subscribe [:rf.mutation/pending? {:instance :sub1}])))
    (is (false? (:optimistic? @(rf/subscribe [:rf.mutation/state {:instance :sub1}])))))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :sub1}])
  (testing ":pending while in flight — a PESSIMISTIC write is :optimistic? false"
    (is (= :pending @(rf/subscribe [:rf.mutation/status {:instance :sub1}])))
    (is (true? @(rf/subscribe [:rf.mutation/pending? {:instance :sub1}])))
    (is (false? (:optimistic? @(rf/subscribe [:rf.mutation/state {:instance :sub1}])))
        "no :optimistic plan → no live optimistic value"))
  (reply-success! @last-managed-args {:saved true})
  (testing ":success settles the result"
    (let [st @(rf/subscribe [:rf.mutation/state {:instance :sub1}])]
      (is (= :success (:status st)))
      (is (:success? st))
      (is (:settled? st))
      (is (false? (:optimistic? st)))
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

;; The map-form exact target is the only public input form (EP-0016 Rider 2 /
;; slice 6). The events layer RESOLVES each target map's :scope to a concrete
;; value first, then `validate-target-key!` validates the [resolved-scope
;; resource params] identity. These unit tests pass the resolved scope directly.

(deftest validate-target-key-rejects-unregistered-resource
  ;; rf2-3yyaur — a controlled patch / populate targeting an UNREGISTERED
  ;; resource fails CLOSED (the patched / seeded entry would be unreachable by
  ;; any subscription).
  (testing "an unregistered resource id is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            {:resource :r/never-registered :params {:slug "w"}}
            :rf.scope/global (fn [_] false) 'test :patches)))))

(deftest validate-target-key-rejects-malformed-target
  ;; EP-0016 Rider 2 — the public input is the map form {:resource :params
  ;; :scope}; a non-map (a bare tuple, a keyword) is rejected loudly.
  (testing "a tuple (the internal storage form) is NOT a public input"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            [:rf.scope/global :r/article {:slug "w"}] :rf.scope/global
            (constantly true) 'test :populates))))
  (testing "a non-map target is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key! :r/article :rf.scope/global (constantly true) 'test :patches))))
  (testing "a map missing :resource is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key! {:params {}} :rf.scope/global (constantly true) 'test :patches))))
  (testing "a non-keyword :resource is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key! {:resource "article" :params {}} :rf.scope/global
                                       (constantly true) 'test :patches)))))

(deftest validate-target-key-rejects-reserved-scope-typo
  ;; rf2-3yyaur — a bare framework-reserved :rf.scope/* keyword outside the
  ;; closed enum (a typo) would silently write under a wrong scope — rejected.
  ;; The events layer resolves the map :scope first; a typo'd literal resolves
  ;; to itself and is caught here.
  (testing ":rf.scope/glabal (a typo) is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            {:resource :r/article :params {:slug "w"}} :rf.scope/glabal
            (constantly true) 'test :patches))))
  (testing "the closed reserved policy :rf.scope/global is a legitimate literal scope"
    (is (= [:rf.scope/global :r/article {:slug "w"}]
           (mstate/validate-target-key!
             {:resource :r/article :params {:slug "w"}} :rf.scope/global
             (constantly true) 'test :patches)))))

(deftest validate-target-key-rejects-non-edn-params
  ;; rf2-3yyaur — a host value in the target's params / scope reaches the
  ;; cache-key boundary and is rejected (the EDN discipline resource params
  ;; follow).
  (testing "non-EDN params rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            {:resource :r/article :params {:slug "w" :cb (fn [])}} :rf.scope/global
            (constantly true) 'test :patches))))
  (testing "non-EDN scope rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/validate-target-key!
            {:resource :r/article :params {:slug "w"}} (fn [])
            (constantly true) 'test :patches)))))

(deftest validate-target-map-strict-policy-rejects-whole-map
  ;; rf2-3yyaur / EP-0016 Rider 2 — the DEFAULT (:strict) policy (pre-write /
  ;; optimistic / execute-time callers, where no server write has landed): one
  ;; bad target — recoverable OR corruption-class — rejects the WHOLE arm (no
  ;; partial write); valid targets are re-keyed by the canonical STORAGE key;
  ;; and a {:from-db …} target whose scope resolves nil is FAIL-CLOSED (dropped,
  ;; recorded in the returned nil-resolved ids — never an implicit global).
  ;; The injected `resolve-target-scope` resolves :rf.scope/same (here the
  ;; supplied mut-scope) and a fake {:from-db :nope} reference to nil.
  (let [resolve-scope (fn [{:keys [scope]}]
                        (cond
                          (nil? scope)                 [:resolved :rf.scope/global]
                          (= scope {:from-db :nope})   [:nil-resolved :nope]
                          :else                        [:resolved scope]))]
    (testing "a single bad target (typo scope) rejects the whole map (default :strict)"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
            (mstate/validate-target-map!
              {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
               {:resource :r/article :params {:slug "x"} :scope :rf.scope/glabal} :bad}
              resolve-scope (constantly true) :patches 'test))))
    (testing "a RECOVERABLE bad target (unregistered) ALSO rejects the whole arm under :strict"
      ;; the pre-write / optimistic surface still whole-arm-rejects an
      ;; unregistered resource (rf2-1vpbld — only the POST-WRITE settle path relaxes).
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
            (mstate/validate-target-map!
              {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
               {:resource :r/nope :params {:slug "x"} :scope :rf.scope/global} :bad}
              resolve-scope #(= % :r/article) :patches 'test))))
    (testing "an all-valid map is re-keyed by the canonical STORAGE key (and no nils)"
      (is (= [{[:rf.scope/global :r/article {:slug "w"}] :v} []]
             (mstate/validate-target-map!
               {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :v}
               resolve-scope (constantly true) :populates 'test))))
    (testing "a {:from-db …} target that resolves nil is FAIL-CLOSED — dropped,
              its resolver id recorded as nil-resolved (never an implicit global)"
      (is (= [{} [:nope]]
             (mstate/validate-target-map!
               {{:resource :r/article :params {:slug "w"} :scope {:from-db :nope}} :v}
               resolve-scope (constantly true) :populates 'test))))
    (testing "an empty / nil map returns [nil []] (no-op arm)"
      (is (= [nil []] (mstate/validate-target-map! {} resolve-scope (constantly true) :patches 'test)))
      (is (= [nil []] (mstate/validate-target-map! nil resolve-scope (constantly true) :patches 'test))))))

(deftest validate-target-map-skip-recoverable-policy
  ;; rf2-1vpbld — the POST-WRITE settle policy (:skip-recoverable): a RECOVERABLE
  ;; bad sibling (unregistered resource / non-map / non-keyword :resource) is
  ;; DROPPED-AND-collected (not thrown) while the VALID siblings still canonicalize
  ;; + land — the server write already committed, so a typo must not strand the
  ;; whole arm. CACHE-IDENTITY CORRUPTION (reserved-scope typo / non-EDN scope /
  ;; params) STILL THROWS. Returns the 3-tuple [canonical nil-ids skipped].
  (let [resolve-scope (fn [{:keys [scope]}]
                        (cond
                          (nil? scope)                 [:resolved :rf.scope/global]
                          (= scope {:from-db :nope})   [:nil-resolved :nope]
                          :else                        [:resolved scope]))]
    (testing "an unregistered sibling is SKIPPED while the valid sibling LANDS"
      (let [[canonical nils skipped]
            (mstate/validate-target-map!
              {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
               {:resource :r/nope :params {:slug "x"} :scope :rf.scope/global} :bad}
              resolve-scope #(= % :r/article) :patches 'test :skip-recoverable)]
        (is (= {[:rf.scope/global :r/article {:slug "w"}] :ok} canonical)
            "the valid sibling canonicalized + retained its value")
        (is (= [] nils))
        (is (= 1 (count skipped)))
        (is (= :unregistered-resource (:reason (first skipped))))
        (is (= :r/nope (:resource (first skipped))) "the recoverable resource id is recorded")))
    (testing "a non-keyword :resource sibling is SKIPPED while the valid one LANDS"
      (let [[canonical _nils skipped]
            (mstate/validate-target-map!
              {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
               {:resource "article" :params {:slug "x"} :scope :rf.scope/global} :bad}
              resolve-scope (constantly true) :populates 'test :skip-recoverable)]
        (is (= {[:rf.scope/global :r/article {:slug "w"}] :ok} canonical))
        (is (= [:non-keyword-resource] (mapv :reason skipped)))))
    (testing "CACHE-IDENTITY CORRUPTION (reserved-scope typo) STILL THROWS even under :skip-recoverable"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
            (mstate/validate-target-map!
              {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} :ok
               {:resource :r/article :params {:slug "x"} :scope :rf.scope/glabal} :bad}
              resolve-scope (constantly true) :patches 'test :skip-recoverable))))
    (testing "CACHE-IDENTITY CORRUPTION (non-EDN params) STILL THROWS"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
            (mstate/validate-target-map!
              {{:resource :r/article :params {:slug "w" :cb (fn [])} :scope :rf.scope/global} :bad}
              resolve-scope (constantly true) :patches 'test :skip-recoverable))))
    (testing "a {:from-db …} nil-resolve is STILL fail-closed-dropped (separate from skip)"
      (is (= [{} [:nope] []]
             (mstate/validate-target-map!
               {{:resource :r/article :params {:slug "w"} :scope {:from-db :nope}} :v}
               resolve-scope (constantly true) :populates 'test :skip-recoverable))))
    (testing "an empty / nil map returns the 3-tuple empty shape [nil [] []]"
      (is (= [nil [] []] (mstate/validate-target-map! {} resolve-scope (constantly true) :patches 'test :skip-recoverable)))
      (is (= [nil [] []] (mstate/validate-target-map! nil resolve-scope (constantly true) :patches 'test :skip-recoverable))))))

(deftest classify-target-key-corruption-vs-recoverable
  ;; rf2-1vpbld — the pure classifier: recoverable cases return [:skip …]
  ;; (dropped by the relaxed settle policy), corruption-class THROWS.
  (testing "an unregistered resource classifies :skip :unregistered-resource"
    (is (= [:skip :unregistered-resource {:target (pr-str {:resource :r/nope :params {:slug "w"}})
                                          :resource :r/nope}]
           (mstate/classify-target-key
             {:resource :r/nope :params {:slug "w"}} :rf.scope/global (constantly false) 'test :patches))))
  (testing "a non-map target classifies :skip :non-map-target"
    (is (= :non-map-target
           (second (mstate/classify-target-key :r/article :rf.scope/global (constantly true) 'test :patches)))))
  (testing "a non-keyword :resource classifies :skip :non-keyword-resource"
    (is (= :non-keyword-resource
           (second (mstate/classify-target-key {:resource "article" :params {}} :rf.scope/global (constantly true) 'test :patches)))))
  (testing "a valid target classifies :apply <canonical key>"
    (is (= [:apply [:rf.scope/global :r/article {:slug "w"}]]
           (mstate/classify-target-key
             {:resource :r/article :params {:slug "w"}} :rf.scope/global (constantly true) 'test :patches))))
  (testing "a reserved-scope typo THROWS (corruption) — never classified :skip"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/classify-target-key
            {:resource :r/article :params {:slug "w"}} :rf.scope/glabal (constantly true) 'test :patches))))
  (testing "corruption (non-EDN scope) wins even when the resource is also unregistered"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-target"
          (mstate/classify-target-key
            {:resource :r/nope :params {:slug "w"}} (fn []) (constantly false) 'test :patches)))))

(deftest recoverable-patch-target-skipped-while-valid-sibling-lands
  ;; rf2-1vpbld — end-to-end POST-WRITE settle: a mutation whose :patches has
  ;; ONE recoverable bad target (an UNREGISTERED resource) and one VALID sibling.
  ;; The server write ALREADY COMMITTED (the reply event fired post-write), so
  ;; the bad sibling is DROPPED-AND-WARNED while the valid sibling LANDS and the
  ;; instance SETTLES — instead of the old all-or-nothing throw that stranded the
  ;; whole committed mutation (the asymmetry-fix: a patch on a missing entry
  ;; already no-ops; an unregistered target now does too, with a loud warning).
  (rf/reg-resource :r/article (article-resource-spec))
  (let [good-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})
        bad-key  [:rf.scope/global :r/never-registered {:slug "w"}]]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :patches (fn [_p _r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global}
                                            (fn [old r] (merge old r))
                                            {:resource :r/never-registered :params {:slug "w"} :scope :rf.scope/global}
                                            (fn [old _] old)})})
    ;; seed the good entry so the patch has data to transform
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old"})
    (reset! last-managed-args nil)
    (let [warns (record-target-skipped-warnings!
                  (fn []
                    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :bad1}])
                    (reply-success! @last-managed-args {:title "new"})))]
      (testing "the VALID sibling patch LANDED (the recoverable bad target was dropped, not all-or-nothing)"
        (is (= {:title "new"} (:data (entry good-key))) "good entry patched")
        (is (nil? (entry bad-key)) "the unregistered key was never written"))
      (testing "the instance SETTLED :success + the work row :completed (the actual defect — the throw used to strand both)"
        (let [i (instance :bad1)]
          (is (= :success (:status i)) "instance reached :success")
          (is (= {:title "new"} (:result i)) "result settled"))
        (is (= :completed (:status (mutation-record :bad1))) "work-ledger row flipped :completed"))
      (testing "the dropped target is recorded on the patch-summary :target-skipped (egress-safe evidence)"
        (let [summary (:patch-summary (instance :bad1))
              skipped (:target-skipped summary)]
          (is (= 1 (count skipped)))
          (is (= :patches (:arm (first skipped))))
          (is (= :unregistered-resource (:reason (first skipped))))
          (is (= :r/never-registered (:resource (first skipped))))))
      (testing "the dedicated :rf.warning/mutation-target-skipped dev tripwire fired"
        (let [warn (some #(when (= :rf.warning/mutation-target-skipped (:operation %)) %) warns)
              ;; the trace event's payload map rides under the event's `:tags`
              ;; field (the trace-bus envelope shape — as the scope-mismatch tests read).
              pay  (:tags warn)]
          (is (some? warn) "the dedicated skipped-target warning was emitted")
          (is (= :unregistered-resource (:reason pay)))
          (is (= :r/never-registered (:resource pay)))
          (is (= :patches (:arm pay))))))))

(deftest corruption-class-patch-target-still-throws-no-partial-mutation
  ;; rf2-1vpbld — the CORRUPTION-class throw is KEPT: a :patches target carrying
  ;; a reserved-scope TYPO (which would silently write the cache under a WRONG
  ;; scope) STILL aborts the whole arm — no relaxed policy may swallow a
  ;; wrong-identity write. The event loop catches the throw, so we observe the
  ;; fail-closed EFFECT: the valid sibling in the same arm did NOT land.
  (rf/reg-resource :r/article (article-resource-spec))
  (let [good-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      ;; the SECOND target carries a bare reserved-scope typo
                      ;; (:rf.scope/glabal) — cache-identity corruption.
                      :patches (fn [_p _r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global}
                                            (fn [old r] (merge old r))
                                            {:resource :r/article :params {:slug "x"} :scope :rf.scope/glabal}
                                            (fn [old _] old)})})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old"})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :bad2}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "corruption-class: the valid sibling did NOT land (the whole arm was rejected)"
      (is (= {:title "old"} (:data (entry good-key))) "good entry unchanged — corruption still fails closed"))))

(deftest recoverable-populate-target-skipped-while-valid-sibling-lands
  ;; rf2-1vpbld — :populates smoke: an unregistered populate target is
  ;; SKIPPED-AND-WARNED while the valid sibling SEEDS the cache and the instance
  ;; SETTLES.
  (rf/reg-resource :r/article (article-resource-spec))
  (let [good-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/create
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug)}})
                      :populates (fn [_p r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global} r
                                             {:resource :r/never-registered :params {:slug "w"} :scope :rf.scope/global} r})})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/create :params {:slug "w"} :instance :pop1}])
    (reply-success! @last-managed-args {:title "seeded"})
    (testing "the valid populate SEEDED the entry; the unregistered sibling was skipped"
      (is (= {:title "seeded"} (:data (entry good-key))) "valid populate landed")
      (is (= :success (:status (instance :pop1))) "instance settled :success")
      (is (= :completed (:status (mutation-record :pop1))) "work row :completed"))
    (testing "the skipped populate sibling is recorded :target-skipped with :arm :populates"
      (let [skipped (:target-skipped (:patch-summary (instance :pop1)))]
        (is (= [:populates] (mapv :arm skipped)))
        (is (= [:unregistered-resource] (mapv :reason skipped)))
        (is (= [:r/never-registered] (mapv :resource skipped)))))))

(deftest recoverable-remove-target-skipped-while-valid-sibling-lands
  ;; rf2-1vpbld — :removes smoke: an unregistered remove target is
  ;; SKIPPED-AND-WARNED while the valid sibling DROPS its entry and the instance
  ;; SETTLES.
  (rf/reg-resource :r/article (article-resource-spec))
  (let [good-key (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
    (reply-success! @last-managed-args {:title "doomed"})
    (is (some? (entry good-key)) "entry seeded")
    (reset! last-managed-args nil)
    (rf/reg-mutation :m/delete2
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}})
                      :removes (fn [{:keys [slug]} _r]
                                 [{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                                  {:resource :r/never-registered :params {:slug slug} :scope :rf.scope/global}])})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/delete2 :params {:slug "w"} :instance :rm1}])
    (reply-success! @last-managed-args {:deleted true})
    (testing "the valid remove DROPPED its entry; the unregistered sibling was skipped"
      (is (nil? (entry good-key)) "valid remove landed")
      (is (= [good-key] (:removed (:patch-summary (instance :rm1)))) "only the valid key removed")
      (is (= :success (:status (instance :rm1))) "instance settled :success")
      (is (= :completed (:status (mutation-record :rm1))) "work row :completed"))
    (testing "the skipped remove sibling is recorded :target-skipped with :arm :removes"
      (let [skipped (:target-skipped (:patch-summary (instance :rm1)))]
        (is (= [:removes] (mapv :arm skipped)))
        (is (= [:unregistered-resource] (mapv :reason skipped)))))))

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
                      :patches (fn [_p _r] {{:resource :r/article :params {:slug "w"} :scope :rf.scope/global}
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
  (let [cofx   {:rf.db/runtime {} :rf.frame/id :rf/default
                :rf.resource/generation-allocation {:generation 1 :counter 1}}
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
  (let [cofx {:rf.db/runtime {} :rf.frame/id :rf/default
              :rf.resource/generation-allocation {:generation 1 :counter 1}}
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

(deftest cedn-distinct-sequential-instance-ids-do-not-clobber
  ;; rf2-8iciw8 — two caller-supplied instance ids that are CEDN-distinct but
  ;; Clojure-= (a vector `[:row 7]` and a list `'(:row 7)`) MUST address
  ;; DISTINCT runtime rows. The instance id was used directly as a Clojure map
  ;; key under :rf.runtime/mutations, and `(= [:row 7] '(:row 7))` is TRUE, so
  ;; the second execute would clobber the first's row (and a later settle /
  ;; clear would gate the wrong one).
  (rf/reg-mutation :m/save (save-article-spec))
  (let [all-args (atom [])]
    (rf/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (let [iv [:row 7]
          il '(:row 7)]
      (is (= iv il) "the two instance ids are Clojure-= (the collapse routed around)")
      (is (not= (mstate/instance-key-id iv) (mstate/instance-key-id il))
          "their byte key-ids differ (v[…] vs l(…)) — distinct storage rows")
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "v"} :instance iv}])
      (let [args-v (last @all-args)]
        (rf/dispatch-sync [:rf.mutation/execute
                           {:mutation :m/save :params {:slug "l"} :instance il}])
        (let [args-l (last @all-args)]
          (testing "TWO distinct instance rows exist (no =-collapse onto one)"
            (is (= 2 (count (:instances (rf/mutations {:frame :rf/default})))))
            (is (= :pending (:status (instance iv))))
            (is (= :pending (:status (instance il))))
            (is (= iv (:instance/id (instance iv))) "vector row keeps its vector id")
            (is (= il (:instance/id (instance il))) "list row keeps its list id")
            (is (vector? (:instance/id (instance iv))) "vector kind preserved")
            (is (seq?    (:instance/id (instance il))) "list kind preserved")
            (is (= {:slug "v"} (:params (instance iv))) "vector row keeps its OWN params")
            (is (= {:slug "l"} (:params (instance il))) "list row keeps its OWN params"))
          (testing "settling the LIST instance leaves the VECTOR instance untouched
                    (no cross-gating between CEDN-distinct rows)"
            (reply-success! args-l {:id :l})
            (is (= :success (:status (instance il))) "list row settled")
            (is (= {:id :l} (:result (instance il))))
            (is (= :pending (:status (instance iv)))
                "vector row is STILL pending — the list settle did not gate it"))
          (testing "then settling the VECTOR instance"
            (reply-success! args-v {:id :v})
            (is (= :success (:status (instance iv))))
            (is (= {:id :v} (:result (instance iv))))))))))

(deftest cedn-distinct-sequential-instance-ids-clear-independently
  ;; rf2-8iciw8 — `:rf.mutation/clear` must target the row by the SAME byte
  ;; identity, so clearing `[:row 7]` does NOT also clear / gate `'(:row 7)`.
  (rf/reg-mutation :m/save (save-article-spec))
  (let [all-args (atom [])]
    (rf/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (let [iv [:row 7]
          il '(:row 7)]
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "v"} :instance iv}])
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "l"} :instance il}])
      (is (= 2 (count (:instances (rf/mutations {:frame :rf/default})))) "two rows")
      (rf/dispatch-sync [:rf.mutation/clear {:instance iv}])
      (testing "only the vector row was cleared; the list row survives intact"
        (is (nil? (instance iv)) "the vector row is gone")
        (is (some? (instance il)) "the CEDN-distinct list row survives")
        (is (= :pending (:status (instance il))))
        (is (= il (:instance/id (instance il))) "and keeps its kind-preserving id")))))

;; ===========================================================================
;; ADVERSARIAL (rf2-sxyrzk / eu2ifi) — two frames executing the SAME mutation
;; instance at the SAME generation get DISTINCT frame-qualified transport
;; request-ids, so the process-global managed-HTTP in-flight registry cannot
;; supersede / abort one frame's write with the other's. Both frames settle
;; independently. The bare frame-local work-id WOULD collide.
;; ===========================================================================

(deftest cross-frame-mutation-request-id-does-not-collide
  (rf/reg-mutation :m/save (save-article-spec))
  (let [all-args (atom [])]
    (rf/reg-fx :rf.http/managed (fn [_ctx args] (swap! all-args conj args) nil))
    (let [fa :xm/frame-a
          fb :xm/frame-b]
      (rf/reg-frame fa {:doc "frame A"})
      (rf/reg-frame fb {:doc "frame B"})
      ;; both frames execute the SAME mutation under the SAME caller-supplied
      ;; instance id → SAME frame-local work-id at the same generation.
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :form/save-1}]
                        {:frame fa})
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :form/save-1}]
                        {:frame fb})
      (let [wid-a (:current-work (instance fa :form/save-1))
            wid-b (:current-work (instance fb :form/save-1))
            req-ids (mapv :request-id @all-args)]
        (testing "each frame mints the SAME frame-local work-id (the collision
                  a bare work-id request-id would cause)"
          (is (= wid-a wid-b) "bare work-ids identical across frames"))
        (testing "Spec 016 §Transport — the lowered transport :request-id is
                  the frame-QUALIFIED token, DISTINCT per frame"
          (is (= 2 (count req-ids)))
          (is (contains? (set req-ids) (work-ledger/managed-request-id fa wid-a)))
          (is (contains? (set req-ids) (work-ledger/managed-request-id fb wid-b)))
          (is (apply distinct? req-ids) "the two frames' request-ids differ")
          (is (not= (set req-ids) #{wid-a})
              "the request-id is NOT the bare work-id (which would collide)"))
        (testing "each frame holds an independent live work record keyed by
                  its own [frame-id work-id]"
          (is (= :running (:status (work-ledger/get-record (runtime-db fa) wid-a))))
          (is (= :running (:status (work-ledger/get-record (runtime-db fb) wid-b))))
          (is (some? (work-ledger/get-handle fa wid-a)))
          (is (some? (work-ledger/get-handle fb wid-b))))
        (testing "frame A's reply settles ONLY frame A's instance — frame B's
                  write stays independently in flight (no stranded instance)"
          ;; the reply event the live transport appends, dispatched into frame A
          (rf/dispatch-sync (conj (:on-success (first @all-args)) {:kind :success :value {:ok true}})
                            {:frame fa})
          (is (= :success (:status (instance fa :form/save-1))) "frame A settled")
          (is (= :pending (:status (instance fb :form/save-1)))
              "frame B's instance still pending — untouched by frame A's reply"))))))

;; ===========================================================================
;; 14. rf2-6bff0q / EP-0016 D1 — mutation completion continuation (:reply-to)
;;
;; A `:rf.mutation/execute` may carry a call-site `:reply-to` event target. On
;; an ACCEPTED terminal reply the runtime dispatches that target with the
;; canonical uniform reply map appended (the shared reply substrate, NOT a
;; family-private callback), AFTER cache consequences + instance settlement
;; (phase 6). A STALE / superseded reply never fires the continuation.
;; ===========================================================================

(def ^:private replied (atom []))

(defn- reg-capture-continuation!
  "Register an app event that records the reply map the continuation appends
  (the LAST arg) plus the full event vector (so static-arg preservation is
  observable)."
  []
  (reset! replied [])
  (rf/reg-event :test/save-replied
                   (fn [_ event] (swap! replied conj event) {})))

(deftest reply-to-fires-on-accepted-success-and-carries-the-reply-map
  ;; Validation rule 2 + 5: the continuation fires exactly once for an accepted
  ;; reply and carries mutation id, params, instance, scope, status, value,
  ;; affected keys, work id, frame id, completion time, and cause.
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec))
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})
        completed-at 1781078400777]
    (rf/reg-mutation :m/save
                     {:params-schema [:map [:slug :string]]
                      :request   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                      :populates (fn [_params result] {(art-target) result})})
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :rc1
                        :reply-to [:test/save-replied]}])
    (reply-success! @last-managed-args {:slug "w" :title "Fresh"}
                    {:rf.cofx {:rf/time-ms completed-at}})
    (testing "the continuation fired exactly once"
      (is (= 1 (count @replied))))
    (testing "EP-0016 — the appended reply map carries the full continuation contract"
      (let [[ev-id reply] (first @replied)]
        (is (= :test/save-replied ev-id))
        (is (= :ok (:status reply)))
        (is (= :m/save (:mutation reply)))
        (is (= {:slug "w"} (:params reply)))
        (is (= :rc1 (:instance reply)))
        (is (= :rf.scope/global (:scope reply)))
        (is (= {:slug "w" :title "Fresh"} (:value reply)) "decoded result rides as :value")
        (is (= :mutation (:work/kind reply)))
        (is (some? (:work/id reply)))
        (is (= :rf/default (:rf.frame/id reply)))
        (is (= completed-at (:completed-at reply)) "EP-0010 causal completion time")
        (is (= [:mutation :m/save :rc1] (:cause reply)))
        (is (contains? (:affected-keys reply) rkey)
            "the populated key is in :affected-keys")))))

(deftest reply-to-reconciles-the-list-on-settle
  ;; SCOPE: the continuation runs on settle + the mutation reconciles the
  ;; affected list. Here the mutation invalidates the list tag (cache
  ;; consequence), and the continuation observes the post-reconcile state.
  (reg-capture-continuation!)
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save (save-article-spec))
    ;; own + load the list-tagged article so the invalidation refetches it
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global
                        :params {:slug "w"} :owner [:view :a]}])
    (reply-success! @last-managed-args {:title "old"})
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :lc1
                        :reply-to [:test/save-replied]}])
    (reply-success! @last-managed-args {:title "new"})
    (testing "the list reconciled — the active-owner entry refetched (back in flight)"
      (is (contains? #{:loading :fetching} (:status (entry rkey)))))
    (testing "the continuation fired after the reconcile (phase 6)"
      (is (= 1 (count @replied)))
      (is (= :ok (:status (second (first @replied))))))))

(deftest reply-to-preserves-static-call-site-args
  ;; Spec 016 §Mutation completion continuations — `:reply-to [:e {:kind :x}]`
  ;; dispatches `[:e {:kind :x} reply]` (the reply appended AFTER static args).
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :sc1
                      :reply-to [:test/save-replied {:kind :article} 7]}])
  (reply-success! @last-managed-args {:ok true})
  (testing "static call-site args are preserved; the reply is the FINAL arg"
    (let [ev (first @replied)]
      (is (= 4 (count ev)) "event-id + 2 static args + reply")
      (is (= [:test/save-replied {:kind :article} 7] (subvec ev 0 3)))
      (is (map? (last ev)))
      (is (= :ok (:status (last ev)))))))

(deftest reply-to-durable-target-rejects-malformed-and-host-handle-at-fn-boundary
  ;; rf2-6kdcs9 — the call-site `:reply-to` is transport-payload-only, but it
  ;; MUST be data-only. The execute handler runs it through
  ;; `re-frame.reply/durable-target` AT ISSUANCE, before any runtime-db /
  ;; work-ledger write, transport lower, or trace. The throw itself is asserted
  ;; HERE at the fn boundary (the event loop catches a handler throw and
  ;; surfaces it as :rf.error/handler-exception rather than rethrowing to
  ;; dispatch-sync's caller — same convention as the instance-id validation).
  (testing "a malformed call-site target (non-vector / bare-keyword :event) is rejected"
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"event-vector|Invalid"
          (reply/durable-target {:event :not-a-vector})))
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"event-vector|Invalid"
          (reply/durable-target {}))))
  (testing "a host handle smuggled into a public slot (a fn in :suppress) is rejected"
    (try
      (reply/durable-target {:event [:test/save-replied] :suppress {:cb (fn [] 1)}})
      (is false "expected durable-target to reject a host-handle target")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
        (is (= :rf.reply/non-data-target (:rf.error/kind (ex-data e)))))))
  (testing "a WELL-FORMED data-only call-site target survives durable projection"
    (is (= {:event [:test/save-replied] :delivery :append}
           (reply/durable-target [:test/save-replied])))))

(deftest execute-rejects-malformed-reply-to-fails-closed
  ;; rf2-6kdcs9 — the dispatch-path fail-closed EFFECT: a malformed call-site
  ;; `:reply-to` rejects BEFORE any transport lower / instance write (the throw
  ;; itself is asserted directly above). The event loop catches the throw, so
  ;; we observe the absence of side effects (mirrors
  ;; `execute-rejects-non-serializable-instance-id-fails-closed`).
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (reset! last-managed-args nil)
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :bad-rt
                      :reply-to {:event :not-a-vector}}])
  (testing "nothing was lowered to transport (fail-closed BEFORE the write)"
    (is (nil? @last-managed-args)))
  (testing "no instance row was written and no continuation fired"
    (is (nil? (instance :bad-rt)))
    (is (empty? @replied))))

(deftest reply-to-observes-settled-instance-and-cache-consequences
  ;; Validation rule 3: the continuation fires AFTER cache consequences and
  ;; mutation instance settlement — a handler reached by `:reply-to` sees both
  ;; already settled for the accepted reply.
  (let [seen (atom nil)]
    (reset! replied [])
    (rf/reg-resource :r/article
                     {:scope :rf.scope/global
                      :params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                      :tags (fn [{:keys [slug]} _] #{[:article slug]})})
    (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
      (rf/reg-mutation :m/save
                       {:params-schema [:map [:slug :string]]
                        :request   (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})
                        :populates (fn [_params result] {(art-target) result})})
      ;; the continuation handler reads the runtime-db AT continuation time:
      ;; both the instance settle AND the populated entry must already be in.
      (rf/reg-event :test/save-replied
                       (fn [_ [_ reply]]
                         (reset! seen {:instance-status (:status (instance :pc1))
                                       :entry-status    (:status (entry rkey))
                                       :entry-data      (:data (entry rkey))
                                       :reply-status    (:status reply)})
                         {}))
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "w"} :instance :pc1
                          :reply-to [:test/save-replied]}])
      (reply-success! @last-managed-args {:slug "w" :title "Fresh"})
      (testing "the continuation observed the instance ALREADY settled :success"
        (is (= :success (:instance-status @seen))))
      (testing "and the populate cache consequence ALREADY applied"
        (is (= :loaded (:entry-status @seen)))
        (is (= {:slug "w" :title "Fresh"} (:entry-data @seen))))
      (testing "the reply status is :ok"
        (is (= :ok (:reply-status @seen)))))))

(deftest reply-to-fires-on-accepted-error
  ;; D1 delivery rule: keyed on ACCEPTANCE, not a status enumeration — an
  ;; accepted `:error` reply fires the continuation too (the handler folds
  ;; validation errors / form state off the reply `:status`).
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :ec1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
  (testing "the continuation fired for the accepted error reply"
    (is (= 1 (count @replied)))
    (let [reply (second (first @replied))]
      (is (= :error (:status reply)))
      (is (= {:kind :rf.http/http-5xx :status 503} (:error reply)))
      (is (= :m/save (:mutation reply)))
      (is (= :ec1 (:instance reply)))
      (is (= #{} (:affected-keys reply)) "a failed write touches no exact key")
      (is (= [:mutation :m/save :ec1] (:cause reply))))))

(deftest reply-to-fires-on-accepted-cancellation
  ;; D1 delivery rule — an accepted TERMINAL cancellation (an `:rf.http/aborted`
  ;; envelope, which the reply substrate lowers to `:status :cancelled`) is an
  ;; accepted terminal reply, so it fires the continuation.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :cc1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/aborted :reason :user-abort})
  (testing "the accepted terminal cancellation fired the continuation"
    (is (= 1 (count @replied)))
    (is (= :cancelled (:status (second (first @replied)))))))

(deftest accepted-abort-reply-settles-ledger-cancelled
  ;; rf2-qsn30x (EP-0011): an ACCEPTED mutation abort/cancel reply
  ;; (`{:kind :rf.http/aborted}`, which the reply substrate lowers to
  ;; `:status :cancelled` / `:work/status :cancelled`) must settle the
  ;; work-ledger row terminal `:cancelled` — NOT `:failed`. The ledger
  ;; status MUST agree with the canonical reply's `:work/status`
  ;; (Managed-Effects §Status taxonomy / §Work-status mapping). A stale
  ;; abort still settles `:suppressed` (covered by the stale suite); this
  ;; pins the LIVE/accepted path.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :ac1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/aborted :reason :user-abort})
  (testing "the canonical reply work-status is :cancelled"
    (is (= :cancelled (:status (second (first @replied))))))
  (testing "the work-ledger row settled terminal :cancelled (agrees with the reply)"
    (let [rec (mutation-record :ac1)]
      (is (= :cancelled (:status rec)))
      (is (= :cancelled (:work/status (second (first @replied))))
          "the ledger status agrees with the canonical reply :work/status")))
  (testing "the ledger outcome carries the cancel reason, not an error summary"
    (let [rec (mutation-record :ac1)]
      (is (= :aborted (:reason (:outcome rec))))
      (is (nil? (:error (:outcome rec)))))))

(deftest accepted-failure-reply-still-settles-ledger-failed
  ;; rf2-qsn30x guard: a genuine (non-abort) failure reply still settles the
  ;; ledger row `:failed` — the abort branch must NOT swallow ordinary
  ;; failures into `:cancelled`.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :af1
                      :reply-to [:test/save-replied]}])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
  (testing "the work-ledger row settled terminal :failed"
    (is (= :failed (:status (mutation-record :af1)))))
  (testing "the canonical reply work-status is :error"
    (is (= :error (:status (second (first @replied)))))))

(deftest stale-reply-does-not-fire-the-continuation
  ;; Validation rule 4: a STALE / superseded mutation reply fires NO
  ;; continuation — the mandatory stale-suppression boundary the reply envelope
  ;; enforces is inherited for free.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  ;; two executes under the SAME instance id → the gen-1 reply is now stale.
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :i
                      :reply-to [:test/save-replied]}])
  (let [gen1-args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :i
                        :reply-to [:test/save-replied]}])
    (is (= 2 (:generation (instance :i))))
    (testing "the STALE gen-1 reply does NOT fire the continuation"
      (reply-success! gen1-args {:stale "result"})
      (is (= 0 (count @replied)) "no continuation for the superseded reply"))
    (testing "the CURRENT gen-2 reply DOES fire the continuation exactly once"
      (reply-success! @last-managed-args {:fresh "result"})
      (is (= 1 (count @replied)))
      (is (= {:fresh "result"} (:value (second (first @replied))))))))

(deftest cleared-instance-reply-does-not-fire-the-continuation
  ;; A `:rf.mutation/clear` removes the instance row; a late reply for the
  ;; cleared instance is stale-suppressed (no live instance), so it fires no
  ;; continuation — the same suppression gate, via the clear path.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :clr1
                      :reply-to [:test/save-replied]}])
  (let [args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/clear {:instance :clr1}])
    (is (nil? (instance :clr1)))
    (testing "a reply for the cleared instance fires no continuation"
      (reply-success! args {:late "result"})
      (is (= 0 (count @replied))))))

(deftest no-reply-to-fires-no-continuation
  ;; Backwards compatibility — an execute WITHOUT `:reply-to` behaves exactly
  ;; as before (no continuation dispatched).
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :nc1}])
  (reply-success! @last-managed-args {:ok true})
  (testing "no :reply-to → no continuation, instance settles normally"
    (is (= 0 (count @replied)))
    (is (= :success (:status (instance :nc1))))))

;; ===========================================================================
;; rf2-ru73k6 F2 — the :rf.mutation/replied trace lands AFTER settlement
;; ===========================================================================

(defn- ops-of
  "The ordered vector of `:operation` keywords from captured trace events."
  [rows]
  (mapv :operation rows))

(defn- index-of
  "Portable (JVM + CLJS) first-index of `x` in vector `v`, or -1 if absent."
  [v x]
  (or (first (keep-indexed (fn [i e] (when (= e x) i)) v)) -1))

(deftest replied-trace-lands-after-succeeded-in-phase-order
  ;; The adversarial phase-order pin: `:rf.mutation/replied` is emitted from
  ;; the settlement boundary AFTER `:rf.mutation/succeeded`, so its stream
  ;; position truthfully reflects phase 6 (continuation runs after cache
  ;; consequences + instance settlement). Pre-fix it was emitted while BUILDING
  ;; the dispatch effect (inside continuation-fx), so the row could appear
  ;; BEFORE succeeded — misleading evidence of the phase order.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (let [rows (record-mutation-traces!
               (fn []
                 (rf/dispatch-sync [:rf.mutation/execute
                                    {:mutation :m/save :params {:slug "w"} :instance :po1
                                     :reply-to [:test/save-replied]}])
                 (reply-success! @last-managed-args {:title "new"})))
        ops (ops-of rows)
        succ-idx (index-of ops :rf.mutation/succeeded)
        repl-idx (index-of ops :rf.mutation/replied)]
    (testing "BOTH the settlement and the replied trace rows were emitted"
      (is (not= -1 succ-idx) ":rf.mutation/succeeded emitted")
      (is (not= -1 repl-idx) ":rf.mutation/replied emitted"))
    (testing "the :rf.mutation/replied row follows :rf.mutation/succeeded
              (post-settlement evidence, not pre-settlement)"
      (is (< succ-idx repl-idx)
          (str "expected succeeded before replied; got ops " (pr-str ops))))
    (testing "the continuation still actually fired (the row corresponds to a
              real dispatched continuation, not a phantom)"
      (is (= 1 (count @replied))))))

(deftest stale-reply-emits-no-replied-trace-row
  ;; The replied row corresponds to the ACTUAL continuation-dispatch boundary:
  ;; a superseded reply dispatches no continuation, so it emits NO
  ;; :rf.mutation/replied row (it surfaces as stale-suppressed instead). The
  ;; CURRENT reply emits exactly one, after its settlement.
  (reg-capture-continuation!)
  (rf/reg-mutation :m/save (save-article-spec))
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :m/save :params {:slug "w"} :instance :sr
                      :reply-to [:test/save-replied]}])
  (let [gen1-args @last-managed-args]
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :sr
                        :reply-to [:test/save-replied]}])
    (testing "the STALE gen-1 reply emits NO :rf.mutation/replied row"
      (let [rows (record-mutation-traces! #(reply-success! gen1-args {:stale "x"}))]
        (is (zero? (count (filter #(= :rf.mutation/replied (:operation %)) rows)))
            "no replied row for the superseded reply")))
    (testing "the CURRENT gen-2 reply emits exactly one :rf.mutation/replied row
              (after its succeeded settlement)"
      (let [rows (record-mutation-traces! #(reply-success! @last-managed-args {:fresh "x"}))
            replied-rows (filter #(= :rf.mutation/replied (:operation %)) rows)]
        (is (= 1 (count replied-rows)))
        (let [ops (ops-of rows)]
          (is (< (index-of ops :rf.mutation/succeeded)
                 (index-of ops :rf.mutation/replied))
              "the live reply's replied row follows its succeeded row"))))))

(deftest reply-to-fires-after-failure-invalidation
  ;; phase-6 ordering on the failure path: the continuation composes AFTER the
  ;; optional failure-time invalidation (it is dispatched last).
  (reg-capture-continuation!)
  (rf/reg-resource :r/article
                   {:scope :rf.scope/global
                    :params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                    :tags (fn [{:keys [slug]} _] #{[:article slug]})})
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :after-failure}))
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
    (reply-success! @last-managed-args {:title "x"})
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :fi1
                        :reply-to [:test/save-replied]}])
    (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
    (testing "the failure-time invalidation marked the tag stale"
      (is (some? (:invalidated-at (entry rkey)))))
    (testing "AND the continuation fired for the accepted error reply"
      (is (= 1 (count @replied)))
      (is (= :error (:status (second (first @replied))))))))

;; ===========================================================================
;; 15. rf2-fi6tda.2 — invalidated/stale keys flow into :affected-keys
;; ===========================================================================
;;
;; Spec 016 §Mutation completion continuations: `:affected-keys` are the keys
;; POPULATED, PATCHED, REMOVED, OR MARKED STALE by the accepted reply. The
;; success path previously unioned only patched + populated keys, and the
;; failure invalidation recorded an EMPTY set — the keys an invalidation pass
;; stales were dropped. These pin the fix (the runtime pre-computes the stale
;; keys through the SAME shared match the dispatched invalidate-tags uses).

(deftest invalidation-only-success-includes-stale-keys-in-affected-keys
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec))
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    ;; an ownerless loaded article entry the mutation will INVALIDATE (no
    ;; populate / patch — invalidation is the ONLY cache consequence).
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"}
                        :owner [:v :a]}])
    (reply-success! @last-managed-args {:title "old"})
    (rf/dispatch-sync [:rf.resource/release-owner
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]}])
    (reset! last-managed-args nil)
    ;; a mutation that ONLY invalidates the article tag (no populate / patch)
    (rf/reg-mutation :m/touch
                     {:params-schema [:map [:slug :string]]
                      :request     (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/touch")}})
                      :invalidates (fn [{:keys [slug]} _r] #{[:article slug]})})
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/touch :params {:slug "w"} :instance :iv1
                        :reply-to [:test/save-replied]}])
    (reply-success! @last-managed-args {:ok true})
    (testing "rf2-fi6tda.2 — the stale-marked key is in the reply :affected-keys
              even though nothing was populated/patched"
      (let [reply (second (first @replied))]
        (is (= 1 (count @replied)))
        (is (contains? (:affected-keys reply) rkey)
            "the invalidated key flows into :affected-keys (was dropped before)")))
    (testing "and the instance :affected-keys records it too"
      (is (= #{rkey} (set (:affected-keys (instance :iv1))))))))

(deftest after-failure-invalidation-includes-stale-keys-in-affected-keys
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec))
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]}])
    (reply-success! @last-managed-args {:title "x"})
    (rf/dispatch-sync [:rf.resource/release-owner
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]}])
    (reset! last-managed-args nil)
    (rf/reg-mutation :m/save (save-article-spec {:invalidate-timing :after-failure}))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :afk1
                        :reply-to [:test/save-replied]}])
    (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
    (testing "rf2-fi6tda.2 — an :after-failure invalidation's stale key is in
              :affected-keys (the failure path previously recorded #{})"
      (let [reply (second (first @replied))]
        (is (= :error (:status reply)))
        (is (contains? (:affected-keys reply) rkey))))
    (testing "and the failed instance records the affected key"
      (is (= #{rkey} (set (:affected-keys (instance :afk1))))))))

;; ===========================================================================
;; 16. rf2-fi6tda.3 finding 1 — mutation :removes drops exact entries
;; ===========================================================================

(deftest mutation-removes-drops-the-exact-entry-and-reports-it
  ;; Spec 016 §Map-form exact resource targets — accepted replies apply
  ;; patches, populates, invalidates, AND removes. A delete write drops the
  ;; cached entry (mirroring :rf.resource/remove) and reports the removed key.
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec))
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    ;; seed a loaded entry the delete mutation will remove
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
    (reply-success! @last-managed-args {:title "doomed"})
    (is (some? (entry rkey)) "entry seeded")
    (reset! last-managed-args nil)
    (rf/reg-mutation :m/delete
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}})
                      :removes (fn [{:keys [slug]} _result]
                                 [{:resource :r/article :params {:slug slug} :scope :rf.scope/global}])})
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/delete :params {:slug "w"} :instance :del1
                        :reply-to [:test/save-replied]}])
    (reply-success! @last-managed-args {:deleted true})
    (testing "the exact entry was REMOVED from the cache"
      (is (nil? (entry rkey)) "the entry is gone (dissoc'd by key-id)"))
    (testing "the removed key is on the instance patch-summary :removed + :affected-keys"
      (is (= [rkey] (:removed (:patch-summary (instance :del1)))))
      (is (= #{rkey} (set (:affected-keys (instance :del1))))))
    (testing "the removed key is in the reply :affected-keys"
      (is (contains? (:affected-keys (second (first @replied))) rkey)))))

(deftest mutation-removes-accepts-single-map-form-target
  ;; the :removes fn may return a SINGLE map-form target (sugar) — not only a
  ;; collection. A remove of a key with no entry is a harmless no-op.
  (rf/reg-resource :r/article (article-resource-spec))
  (let [rkey (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :scope :rf.scope/global :params {:slug "w"}}])
    (reply-success! @last-managed-args {:title "x"})
    (reset! last-managed-args nil)
    (rf/reg-mutation :m/del-one
                     {:params-schema [:map [:slug :string]]
                      :request (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}})
                      :removes (fn [{:keys [slug]} _r]
                                 {:resource :r/article :params {:slug slug} :scope :rf.scope/global})})
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/del-one :params {:slug "w"} :instance :do1}])
    (reply-success! @last-managed-args {:deleted true})
    (testing "a single map-form target removes the entry"
      (is (nil? (entry rkey))))
    (testing "removing a key with no entry is a no-op (no throw, empty :removed)"
      (rf/reg-mutation :m/del-missing
                       {:params-schema [:map [:slug :string]]
                        :request (fn [{:keys [slug]} _] {:request {:method :delete :url (str "/a/" slug)}})
                        :removes (fn [{:keys [slug]} _r]
                                   {:resource :r/article :params {:slug slug} :scope :rf.scope/global})})
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/del-missing :params {:slug "gone"} :instance :dm1}])
      (reply-success! @last-managed-args {:deleted true})
      (is (= [] (:removed (:patch-summary (instance :dm1))))))))

;; ===========================================================================
;; 17. rf2-fi6tda.4 finding 1 — pin the runtime :rf.mutation/replied trace
;; ===========================================================================

(deftest replied-trace-emitted-on-accepted-reply-with-full-shape
  ;; The accepted :reply-to continuation emits exactly one :rf.mutation/replied
  ;; row carrying :target, :work/id, :mutation, :instance, :status, :cause —
  ;; pinned via a REAL trace listener over the runtime (not a synthetic row).
  (reg-capture-continuation!)
  (rf/reg-resource :r/article (article-resource-spec))
  (let [traces (record-mutation-traces!
                 (fn []
                   (rf/reg-mutation :m/save (save-article-spec))
                   (rf/dispatch-sync [:rf.mutation/execute
                                      {:mutation :m/save :params {:slug "w"} :instance :rt1
                                       :reply-to [:test/save-replied]}])
                   (reply-success! @last-managed-args {:ok true})))
        replied-rows (filter #(= :rf.mutation/replied (:operation %)) traces)]
    (testing "exactly one :rf.mutation/replied row for the accepted reply"
      (is (= 1 (count replied-rows))))
    (testing "the row carries the full continuation evidence shape"
      (let [row (:tags (first replied-rows))]
        ;; :target is the normalized reply-target descriptor (the :reply-to
        ;; vector lowered to {:event … :delivery :append} by re-frame.reply).
        (is (= [:test/save-replied] (:event (:target row))))
        (is (= :append (:delivery (:target row))))
        (is (some? (:work/id row)))
        (is (= :m/save (:mutation row)))
        (is (= :rt1 (:instance row)))
        (is (= :ok (:status row)))
        (is (= [:mutation :m/save :rt1] (:cause row)))))))

(deftest replied-trace-not-emitted-for-stale-suppressed-reply
  ;; the trace fires only for an accepted terminal reply, never for a
  ;; stale/suppressed one (the delivery rule, pinned at the trace boundary).
  (reg-capture-continuation!)
  (let [traces (record-mutation-traces!
                 (fn []
                   (rf/reg-mutation :m/save (save-article-spec))
                   ;; two executes under the SAME instance → gen-1 reply is stale
                   (rf/dispatch-sync [:rf.mutation/execute
                                      {:mutation :m/save :params {:slug "w"} :instance :si
                                       :reply-to [:test/save-replied]}])
                   (let [gen1 @last-managed-args]
                     (rf/dispatch-sync [:rf.mutation/execute
                                        {:mutation :m/save :params {:slug "w"} :instance :si
                                         :reply-to [:test/save-replied]}])
                     (reply-success! gen1 {:stale true}))))
        replied-rows (filter #(= :rf.mutation/replied (:operation %)) traces)
        stale-rows   (filter #(= :rf.mutation/stale-suppressed (:operation %)) traces)]
    (testing "no :rf.mutation/replied row for the stale reply"
      (is (= 0 (count replied-rows))))
    (testing "the stale reply WAS recorded as stale-suppressed"
      (is (= 1 (count stale-rows))))))

;; ===========================================================================
;; 14. mutation-state fails closed without an explicit frame (rf2-a76921)
;; ===========================================================================

(deftest mutation-state-fails-closed-without-frame
  ;; rf2-a76921 — the mutation introspection half MUST be symmetric with
  ;; `resource-state` (rf2-c8lgy3): a frameless `mutation-state` call cannot
  ;; silently pass nil through to `frame-runtime-db-value` (which returns nil
  ;; for a missing frame) and return a nil that is INDISTINGUISHABLE from a
  ;; genuinely absent instance. Per EP-0002 the frame target is carried
  ;; explicitly; the MISSING explicit target fails closed.
  (rf/reg-mutation :m/save (save-article-spec))
  (testing "a frameless mutation-state call raises :rf.error/no-frame-context
            (never a silent nil that is indistinguishable from an absent
            instance)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (rf/mutation-state {:instance :ms-no-frame}))))
  (testing "an explicit nil :frame ALSO fails closed (nil is not a frame)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (rf/mutation-state {:instance :ms-no-frame :frame nil}))))
  (testing "a valid explicit frame returns nil ONLY for a genuinely absent
            instance (the fail-closed boundary is the missing target, not a
            vanished one)"
    (is (nil? (rf/mutation-state {:instance :ms-absent :frame :rf/default}))))
  (testing "an explicit but UNKNOWN / destroyed frame reads as nil runtime-db
            and returns nil (no instance) — same result as a live frame with
            no instance for the id, NOT a fail-closed throw"
    (is (nil? (rf/mutation-state {:instance :ms-absent :frame :no/such-frame}))))
  (testing "a valid explicit frame returns the instance row when present"
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "w"} :instance :ms-present}])
    (reply-success! @last-managed-args {:ok true})
    (let [row (rf/mutation-state {:instance :ms-present :frame :rf/default})]
      (is (some? row))
      (is (= :success (:status row))))))
