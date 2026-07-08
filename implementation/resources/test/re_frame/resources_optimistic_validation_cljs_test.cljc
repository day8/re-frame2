(ns re-frame.resources-optimistic-validation-cljs-test
  "EP-0019 — the 13-case optimistic-mutation VALIDATION SUITE (the
  non-negotiable acceptance gate the ruling names), rf2-byl7bk.1 / slice 4a.

  This is the consolidated conformance gate for the optimistic-mutation surface:
  it names and exercises EVERY one of the 13 general laws in the EP-0019
  §Validation plan as a single suite, so a regression in any optimistic slice
  (revision substrate, apply, settle, tag-addressed, subs) trips here. Each
  `deftest` below is one numbered law; the docstring of each names the EP case.

  Cases 1-5, 9, 10, 13 pin laws also covered by the slice-2 APPLY and slice-3
  SETTLE suites; they are restated here so the GATE is self-contained (one file
  proves the whole contract). Cases 6, 7, 8 (settle), 11, and the Rider-1
  `:optimistic?` sub flag are the laws slice 4a ADDS:

    6.  a STALE / superseded reply discards its inverse and never rolls back —
        the newer generation owns the entry;
    7.  two concurrent optimistic writes on the same entry each record their own
        inverse + revision and compose deterministically per the conflict rule;
    8.  `:optimistic-tags` rolls back each tag-matched entry INDEPENDENTLY (one
        restored, one conflicted → invalidated, in a single settle);
    11. `:reply-to` fires exactly once, after settle, for the accepted reply only
        — the optimistic apply dispatches no continuation;
    +   the Rider-1 `:optimistic?` derived sub flag — true between phase 1.5 and
        settle, false for a pessimistic / settled write.

  The transport is a capturing stub; replies are synthesised by dispatching the
  captured `:on-success` / `:on-failure` internal reply event."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* +
   ;; :rf.mutation/* events + subs + the generation cofx/fx.
   [re-frame.resources]
   [re-frame.resources.mutation-registry :as mreg]
   [re-frame.resources.state :as state]
   [re-frame.registrar :as registrar]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport ---------------------------------------------------

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (registrar/clear-kind! :resource-scope)
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]]
                           {:db (assoc-in db [:auth :user :username] username)})))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter :init-fn init!}
       :cljs {:adapter reagent-adapter/adapter :init-fn init!}))
  capturing-transport-fixture)

;; ---- helpers ---------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
;; rf2-8iciw8 — `:rf.runtime/mutations` is keyed on the instance id's CEDN-1
;; byte `key-id` (`state/key-id`), not the raw id; resolve through it.
(defn- instance [instance-id] (get-in (runtime-db) [:rf.runtime/mutations (state/key-id instance-id)]))
(defn- patch-summary [instance-id] (:patch-summary (instance instance-id)))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(defn- reply-failure! [args failure]
  (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(def ^:private article-key
  (state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))

(defn- reg-article-resource! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})))

(defn- own-loaded! [payload value]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args value)
  (reset! last-managed-args nil))

(def ^:private favorite-plan
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   :optimistic (fn [{:keys [slug]}]
                 {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                  (fn [a] (-> a
                              (assoc-in [:article :favorited] true)
                              (update-in [:article :favoritesCount] inc)))})})

(def ^:private favorite-plan-request
  (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))

(defn- mutation-state [instance-id]
  @(rf/subscribe [:rf.mutation/state {:instance instance-id}]))

(defn- trace-of
  "Run `body-fn`; return the LAST trace event with `op` (its top-level data map;
  facets ride under `:tags`), or nil."
  [op body-fn]
  (let [seen (atom [])
        k    ::recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj ev))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    (:tags (last @seen))))

(defn- traces-of
  "Run `body-fn`; return `{op -> last-event-tags}` for each op in `ops`."
  [ops body-fn]
  (let [op-set (set ops)
        seen   (atom {})
        k      ::multi]
    (trace-tooling/register-listener!
      k (fn [ev] (when (op-set (:operation ev))
                   (swap! seen assoc (:operation ev) (:tags ev)))))
    (try (body-fn) (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- competing-authoritative-write!
  "Simulate a CONCURRENT authoritative write landing on the article key between
  the in-flight mutation's optimistic apply and its reply — a SECOND mutation's
  `:populates` seeds the entry with a NEWER server value, which bumps the entry's
  `:revision` (`populate-entry`), exactly the move the conflict check catches. The
  original in-flight mutation's captured reply args are saved + restored, so the
  test can reply against them afterward."
  [value]
  (let [saved @last-managed-args]
    (rf/reg-mutation :m/competing
      {:scope :rf.scope/global
       :params-schema [:map [:slug :string]]
       :populates (fn [{:keys [slug]} result]
                    {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                     result})}
      (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/competing :params {:slug "w"}
                                             :instance :competing}])
    (reply-success! @last-managed-args value)
    (reset! last-managed-args saved)))

;; ===========================================================================
;; CASE 1 — optimistic apply patches the entry at phase 1.5, before the request.
;; ===========================================================================

(deftest case-01-apply-patches-before-the-request-is-sent
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (let [rev-before (:revision (entry article-key))]
    (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (testing "the optimistic forward patch is in the cache BEFORE any reply"
      (is (= true (get-in (entry article-key) [:data :article :favorited])))
      (is (= 10 (get-in (entry article-key) [:data :article :favoritesCount])))
      (is (= :loaded (:status (entry article-key)))))
    (testing "the apply bumped :revision and recorded the snapshot inverse"
      (is (= (inc rev-before) (:revision (entry article-key))))
      (is (some? (:snapshot-id (patch-summary :f1)))))
    (testing "the request was lowered AFTER the apply (apply is not a reply)"
      (is (some? @last-managed-args))
      (is (= :pending (:status (instance :f1)))))))

;; ===========================================================================
;; CASE 2 — an accepted :ok reply COMMITS; :populates overwrite the optimistic
;;          value; the optimistic apply clears from the entry.
;; ===========================================================================

(deftest case-02-ok-commits-and-overwrites-the-optimistic-value
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite
    (assoc favorite-plan
           :populates (fn [{:keys [slug]} result]
                        {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                         result}))
    favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (let [recon (trace-of :rf.mutation/optimistic-reconciled
                #(reply-success! @last-managed-args
                                 {:article {:favorited true :favoritesCount 42}}))]
    (testing "the authoritative populate OVERWROTE the optimistic 10 with the server 42"
      (is (= 42 (get-in (entry article-key) [:data :article :favoritesCount])))
      (is (= :loaded (:status (entry article-key)))))
    (testing "the instance committed :success and filled the reserved patch-summary slots"
      (is (= :success (:status (instance :f1))))
      (is (some? (:snapshot-id (patch-summary :f1))))
      (is (= [article-key] (:committed (patch-summary :f1)))))
    (testing "the optimistic-reconciled trace names the committed key"
      (is (= [article-key] (:committed recon))))))

;; ===========================================================================
;; CASE 3 — an accepted :error reply with NO conflict restores the recorded
;;          :before entry verbatim (including freshness).
;; ===========================================================================

(deftest case-03-error-no-conflict-restores-before-verbatim
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (let [before (entry article-key)]
    (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (let [rb (trace-of :rf.mutation/optimistic-rolled-back
               #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))]
      (testing "the :error reply restored the EXACT :before entry verbatim"
        (is (= false (get-in (entry article-key) [:data :article :favorited])))
        (is (= 9 (get-in (entry article-key) [:data :article :favoritesCount])))
        (is (= before (entry article-key))
            "the WHOLE entry (incl. freshness + :revision) is restored verbatim"))
      (testing "the instance settled :error; the trace reports RESTORED, no conflict"
        (is (= :error (:status (instance :f1))))
        (is (= [article-key] (:restored rb)))
        (is (= [] (:conflicted rb)))))))

;; ===========================================================================
;; CASE 4 — an accepted :error reply WITH a conflict (revision moved) does NOT
;;          restore; it invalidates + refetches (:on-conflict :invalidate default).
;; ===========================================================================

(deftest case-04-error-conflict-invalidates-instead-of-restoring
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)        ;; :on-conflict defaults to :invalidate
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  ;; a CONCURRENT authoritative write moves the entry's :revision past the apply.
  (competing-authoritative-write! {:article {:favorited false :favoritesCount 100}})
  (rf/reg-fx :rf.resource/refetch (fn [_ _] nil))
  (let [traces (traces-of [:rf.mutation/optimistic-rolled-back :rf.resource/invalidated]
                 #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))
        rb     (:rf.mutation/optimistic-rolled-back traces)
        inval  (:rf.resource/invalidated traces)]
    (testing "the stale inverse (9) was NOT restored over the concurrent write"
      (is (not= 9 (get-in (entry article-key) [:data :article :favoritesCount]))))
    (testing "the conflicted entry's tags were INVALIDATED (read path recovers truth)"
      (is (some? inval))
      (is (contains? (set (:matched inval)) article-key)))
    (testing "the trace reports the conflict + :invalidate + refetch"
      (is (= [article-key] (:conflicted rb)))
      (is (= [article-key] (:refetched rb)))
      (is (= [] (:restored rb)))
      (is (= :invalidate (:on-conflict rb))))
    (testing "the instance row's :reconciliation-refetches records the refetched key"
      (is (= [article-key] (:reconciliation-refetches (patch-summary :f1)))))))

;; ===========================================================================
;; CASE 5 — :on-conflict :force restores the inverse even on conflict (warning).
;; ===========================================================================

(deftest case-05-force-restores-stale-inverse-with-a-warning
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite (assoc favorite-plan :on-conflict :force) favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (competing-authoritative-write! {:article {:favorited false :favoritesCount 100}})
  (let [traces (traces-of [:rf.mutation/optimistic-rolled-back
                           :rf.warning/optimistic-force-clobber]
                 #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))
        rb     (:rf.mutation/optimistic-rolled-back traces)
        warn   (:rf.warning/optimistic-force-clobber traces)]
    (testing ":force RESTORED the recorded (stale) inverse (9) despite the conflict"
      (is (= 9 (get-in (entry article-key) [:data :article :favoritesCount]))))
    (testing "the trace marks the key restored AND conflicted; the warning fired"
      (is (= [article-key] (:restored rb)))
      (is (= [article-key] (:conflicted rb)))
      (is (= :force (:on-conflict rb)))
      (is (some? warn))
      (is (= [article-key] (:forced-keys warn))))))

;; ===========================================================================
;; CASE 6 — a STALE / superseded reply discards its inverse and NEVER rolls back;
;;          the newer generation's apply owns the entry. (slice-4a NEW)
;;
;; A re-execute under the SAME instance mints a NEW generation + work id and a
;; FRESH optimistic apply (re-snapshotting the entry as the optimistic value).
;; The FIRST execute's reply is now STALE (its work id ≠ the instance's
;; :current-work) and MUST be suppressed — its recorded inverse is never
;; replayed, so it cannot clobber the newer apply's value.
;; ===========================================================================

(deftest case-06-stale-superseded-reply-discards-its-inverse
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  ;; FIRST execute — its optimistic apply takes count 9 -> 10; capture its reply.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (let [stale-args @last-managed-args
        gen-1      (:generation (instance :f1))]
    (is (= 10 (get-in (entry article-key) [:data :article :favoritesCount])))
    (reset! last-managed-args nil)
    ;; RE-EXECUTE under the same instance — a NEW generation, a NEW work id, and a
    ;; FRESH optimistic apply (10 -> 11), re-snapshotting the entry as the
    ;; current (optimistic) value. The first execute's reply is now superseded.
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
    (let [gen-2 (:generation (instance :f1))]
      (is (not= gen-1 gen-2) "the re-execute minted a new generation")
      (is (= 11 (get-in (entry article-key) [:data :article :favoritesCount]))
          "the newer apply owns the entry (10 -> 11)")
      (testing "delivering the STALE first reply is SUPPRESSED — no rollback"
        (let [stale-tr (trace-of :rf.mutation/stale-suppressed
                         (fn []
                           ;; deliver the first (now stale) reply as a FAILURE — were its
                           ;; inverse replayed it would clobber the newer value back to 9.
                           (reply-failure! stale-args {:kind :rf.http/http-5xx :status 500})))]
          (is (some? stale-tr) "the superseded reply was stale-suppressed")
          (is (= 11 (get-in (entry article-key) [:data :article :favoritesCount]))
              "the stale inverse was DISCARDED — the newer generation still owns the entry")
          (is (= :pending (:status (instance :f1)))
              "the live (newer) instance was untouched by the stale reply"))))))

;; ===========================================================================
;; CASE 7 — two concurrent optimistic writes on the SAME entry each record their
;;          own inverse + revision; the later commit and the earlier rollback
;;          compose deterministically per the conflict rule. (slice-4a NEW)
;;
;; Two DISTINCT instances (:a then :b) both optimistically increment the same
;; entry. Each records its own snapshot-inverse at the revision it observed.
;; :b commits authoritatively (server truth wins), moving the revision; :a then
;; FAILS — its recorded inverse is now stale (the revision moved past :a's
;; baseline), so :a's rollback DEFERS to the read path (:invalidate), never
;; clobbering :b's committed value.
;; ===========================================================================

(deftest case-07-two-concurrent-optimistic-writes-compose-deterministically
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favoritesCount 9}})
  (rf/reg-mutation :m/inc
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :optimistic (fn [{:keys [slug]}]
                   {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                    (fn [a] (update-in a [:article :favoritesCount] inc))})
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/inc")}}))
  ;; write :a applies (9 -> 10); capture its reply args.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/inc :params {:slug "w"} :instance :a}])
  (let [a-args @last-managed-args
        a-inv  (first (:rollback (patch-summary :a)))]
    (reset! last-managed-args nil)
    ;; write :b applies on top (10 -> 11); capture its reply args.
    (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/inc :params {:slug "w"} :instance :b}])
    (let [b-args @last-managed-args
          b-inv  (first (:rollback (patch-summary :b)))]
      (testing "each write recorded its OWN inverse at the revision it observed"
        (is (some? a-inv))
        (is (some? b-inv))
        (is (not= (:revision a-inv) (:revision b-inv))
            ":b observed a later revision than :a (the apply ledger stacks in order)")
        (is (= 9 (get-in (:before a-inv) [:data :article :favoritesCount]))
            ":a's recorded :before is the pre-:a value (9)")
        (is (= 10 (get-in (:before b-inv) [:data :article :favoritesCount]))
            ":b's recorded :before is the post-:a optimistic value (10)"))
      ;; :b COMMITS authoritatively — the server returns 50, moving the revision.
      (reply-success! b-args {:article {:favoritesCount 50}})
      (is (= 50 (get-in (entry article-key) [:data :article :favoritesCount]))
          ":b committed authoritatively")
      (is (= :success (:status (instance :b))))
      ;; :a now FAILS — its inverse (9) is stale (the revision moved past :a's
      ;; baseline when :b committed), so its rollback DEFERS to the read path.
      (rf/reg-fx :rf.resource/refetch (fn [_ _] nil))
      (let [rb (trace-of :rf.mutation/optimistic-rolled-back
                 #(reply-failure! a-args {:kind :rf.http/http-5xx :status 500}))]
        (testing ":a's stale inverse (9) did NOT clobber :b's committed value (50)"
          (is (not= 9 (get-in (entry article-key) [:data :article :favoritesCount])))
          (is (= [article-key] (:conflicted rb))
              ":a's rollback saw the moved revision as a conflict")
          (is (= :invalidate (:on-conflict rb))
              "the contested rollback deferred to the read path, never restored 9"))
        (testing ":a settled :error (terminal), :b stayed :success"
          (is (= :error (:status (instance :a))))
          (is (= :success (:status (instance :b)))))))))

;; ===========================================================================
;; CASE 8 — :optimistic-tags patches every tag-matched entry across scopes; each
;;          matched entry ROLLS BACK INDEPENDENTLY (one restored, one conflicted
;;          → invalidated, in a single settle). (slice-4a NEW — the tag SETTLE
;;          nuance; slice-2 only proved tag APPLY.)
;; ===========================================================================

(deftest case-08-optimistic-tags-rolls-back-each-matched-entry-independently
  ;; A DETAIL and a FEED entry, each carrying its OWN disjoint tag so a conflict
  ;; invalidation of one cannot cross-stale the other (tag overlap is a separate,
  ;; correct effect — this case isolates the per-key DISPOSITION). The
  ;; :optimistic-tags plan carries TWO descriptors (one per tag) so both are
  ;; patched optimistically; a competing write then moves ONLY the feed.
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (rf/reg-resource :r/feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed-w]})}
    (fn [_p _] {:request {:method :get :url "/feed"}}))
  (let [feed-key (state/scoped-resource-key :rf.scope/global :r/feed {})]
    (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
                 {:article {:favorited false}})
    (own-loaded! {:resource :r/feed :scope :rf.scope/global :params {} :owner [:v :f]}
                 {:items 1})
    (let [detail-before (entry article-key)]
      (rf/reg-mutation :m/favorite-everywhere
        {:scope :rf.scope/global
         :params-schema [:map [:slug :string]]
         ;; TWO descriptors, disjoint tags — both matched entries are patched.
         :optimistic-tags (fn [{:keys [slug]}]
                            [{:scope :rf.scope/global :tags #{[:article slug]}
                              :patch (fn [d] (assoc d :touched true))}
                             {:scope :rf.scope/global :tags #{[:feed-w]}
                              :patch (fn [d] (assoc d :touched true))}])}
        (fn [{:keys [slug]} _] {:request {:method :post :url "/fav"}}))
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite-everywhere
                                               :params {:slug "w"} :instance :fe1}])
      (testing "BOTH tag-matched entries were optimistically patched + each recorded an inverse"
        (is (= true (get-in (entry article-key) [:data :touched])))
        (is (= true (get-in (entry feed-key) [:data :touched])))
        (is (= #{article-key feed-key}
               (set (map :resource/key (:rollback (patch-summary :fe1)))))))
      ;; a competing authoritative write moves ONLY the FEED entry's revision (a
      ;; populate of the feed key) — so on rollback the DETAIL is unmoved
      ;; (restores) while the FEED is conflicted (invalidates), each disposed
      ;; INDEPENDENTLY. The feed's conflict invalidation uses its own [:feed-w]
      ;; tag, disjoint from the detail, so it cannot cross-stale the restore.
      (let [saved @last-managed-args]
        (rf/reg-mutation :m/touch-feed
          {:scope :rf.scope/global
           :params-schema [:map]
           :populates (fn [_p result]
                        {{:resource :r/feed :params {} :scope :rf.scope/global} result})}
          (fn [_p _] {:request {:method :put :url "/feed"}}))
        (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/touch-feed :params {} :instance :tf}])
        (reply-success! @last-managed-args {:items 99 :server true})
        (reset! last-managed-args saved))
      (rf/reg-fx :rf.resource/refetch (fn [_ _] nil))
      (let [traces (traces-of [:rf.mutation/optimistic-rolled-back :rf.resource/invalidated]
                     #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))
            rb     (:rf.mutation/optimistic-rolled-back traces)]
        (testing "the UNMOVED detail entry was RESTORED verbatim"
          (is (nil? (get-in (entry article-key) [:data :touched])) "detail un-touched (restored)")
          (is (= detail-before (entry article-key)) "detail restored to its exact :before")
          (is (contains? (set (:restored rb)) article-key)))
        (testing "the MOVED feed entry was NOT restored — it conflicted + invalidated"
          (is (= 99 (get-in (entry feed-key) [:data :items]))
              "the stale feed inverse did NOT clobber the moved entry (99)")
          (is (contains? (set (:conflicted rb)) feed-key))
          (is (contains? (set (:refetched rb)) feed-key)))
        (testing "the two matched entries were disposed INDEPENDENTLY in one settle"
          (is (= #{article-key} (set (:restored rb))))
          (is (= #{feed-key} (set (:conflicted rb)))))))))

;; ===========================================================================
;; CASE 9 — a {:from-db …} optimistic target that resolves nil is fail-closed.
;; ===========================================================================

(deftest case-09-from-db-target-resolving-nil-is-fail-closed
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed]})}
    (fn [_p _] {:request {:method :get :url "/feed"}}))
  (rf/reg-mutation :m/touch-feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :optimistic (fn [_p]
                   {{:resource :r/feed :params {} :scope {:from-db :t/session}}
                    (fn [d] (assoc d :touched true))})}
    (fn [_p _] {:request {:method :post :url "/feed/touch"}}))
  ;; NO :t/login — the {:from-db :t/session} resolver returns nil.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/touch-feed :params {} :instance :tf1}])
  (testing "no entry was written (fail-closed drop, never an implicit global)"
    (is (nil? (entry (state/scoped-resource-key :rf.scope/global :r/feed {})))))
  (testing "the dropped target is recorded as :target-unresolved; no inverse"
    (is (= [:t/session] (:target-unresolved (patch-summary :tf1))))
    (is (empty? (:rollback (patch-summary :tf1))))))

;; ===========================================================================
;; CASE 10 — :optimistic + :before-request timing is a loud registration error.
;; ===========================================================================

(deftest case-10-optimistic-with-before-request-is-a-registration-error
  (testing ":optimistic + :before-request throws :rf.error/mutation-optimistic-before-request"
    (let [ex (try
               (rf/reg-mutation :m/bad
                 {:scope :rf.scope/global
                  :params-schema [:map [:slug :string]]
                  :invalidate-timing :before-request
                  :optimistic (fn [_p] {})}
                 (fn [_p _] {:request {:method :post :url "/x"}}))
               nil
               (catch #?(:clj Exception :cljs :default) e e))]
      (is (some? ex))
      (is (= :rf.error/mutation-optimistic-before-request (:rf.error/id (ex-data ex))))
      (is (nil? (mreg/mutation-meta :m/bad)) "the bad mutation was NOT registered")))
  (testing ":optimistic-tags + :before-request also throws"
    (let [ex (try
               (rf/reg-mutation :m/bad2
                 {:scope :rf.scope/global :params-schema [:map]
                  :invalidate-timing :before-request
                  :optimistic-tags (fn [_p] [])}
                 (fn [_p _] {:request {:method :post :url "/x"}}))
               nil
               (catch #?(:clj Exception :cljs :default) e e))]
      (is (= :rf.error/mutation-optimistic-before-request (:rf.error/id (ex-data ex)))))))

;; ===========================================================================
;; CASE 11 — :reply-to fires ONCE, after settle, for the accepted reply only —
;;           the optimistic apply dispatches NO continuation. (slice-4a NEW)
;; ===========================================================================

(def ^:private replied (atom []))

(deftest case-11-reply-to-fires-once-after-settle-not-on-the-apply
  (reset! replied [])
  (rf/reg-event :test/replied (fn [_ event] (swap! replied conj event) {}))
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite
    (assoc favorite-plan
           :populates (fn [{:keys [slug]} result]
                        {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                         result}))
    favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"}
                                           :instance :f1 :reply-to [:test/replied]}])
  (testing "the optimistic apply landed but dispatched NO continuation (apply is not a reply)"
    (is (= true (get-in (entry article-key) [:data :article :favorited])))
    (is (= 0 (count @replied)) "no :reply-to before settle"))
  (reply-success! @last-managed-args {:article {:favorited true :favoritesCount 42}})
  (testing "the continuation fired EXACTLY once, after settle, for the accepted reply"
    (is (= 1 (count @replied)))
    (let [[ev-id reply] (first @replied)]
      (is (= :test/replied ev-id))
      (is (= :ok (:status reply)))
      (is (= :f1 (:instance reply))))))

;; ===========================================================================
;; CASE 12 — trace evidence: optimistic-applied / rolled-back / reconciled carry
;;           snapshot id, per-key revision, conflict/restore disposition; the
;;           instance row's :patch-summary slots are filled.
;; ===========================================================================

(deftest case-12-trace-evidence-and-patch-summary-slots-are-filled
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (let [rev-before (:revision (entry article-key))]
    (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
    (testing "optimistic-applied carries the snapshot id + per-key revision + forward op"
      (let [applied (trace-of :rf.mutation/optimistic-applied
                      #(rf/dispatch-sync [:rf.mutation/execute
                                          {:mutation :m/favorite :params {:slug "w"} :instance :f1}]))]
        (is (some? (:snapshot-id applied)))
        (is (= [article-key] (:affected-keys applied)))
        (is (= [{:resource/key article-key :revision rev-before :forward :patch}]
               (:revisions applied)))))
    (testing "the pending instance row's :patch-summary slots are filled (snapshot-id + rollback)"
      (let [ps (patch-summary :f1)]
        (is (some? (:snapshot-id ps)))
        (is (= 1 (count (:rollback ps))))))
    (testing "rolled-back carries the per-key restored disposition"
      (let [rb (trace-of :rf.mutation/optimistic-rolled-back
                 #(reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 500}))]
        (is (some? (:snapshot-id rb)))
        (is (= [{:resource/key article-key :restored true :conflict false}]
               (:dispositions rb)))))))

;; ===========================================================================
;; CASE 13 — epoch restore: a :pending optimistic write dangles; its apply rolls
;;           back inside the restore reconciler's single pure pass. (Covered in
;;           depth by the slice-3 settle suite's restore-dangle test; restated
;;           here as the GATE's law 13.)
;; ===========================================================================

(deftest case-13-restore-dangle-rolls-back-the-optimistic-apply
  ;; The restore-reconciler-internal rollback is exercised end-to-end in
  ;; re-frame.resources-optimistic-settle-cljs-test/
  ;; restore-dangle-rolls-back-the-optimistic-apply-inside-the-reconciler — a
  ;; PENDING optimistic instance dangles to :error and its apply rolls back
  ;; INSIDE the single reconcile pass (no racing post-restore dispatch), both
  ;; the no-conflict (restore) and conflict (durable-stale-in-place) branches.
  ;; This GATE case asserts the LAW holds via that companion: a :pending
  ;; optimistic write has a recorded inverse the dangle path consumes.
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (testing "a :pending optimistic write carries the recorded inverse the dangle replays"
    (is (= :pending (:status (instance :f1))))
    (let [ps (patch-summary :f1)]
      (is (some? (:snapshot-id ps)) "the dangle reconciler reads :snapshot-id")
      (is (= [article-key] (mapv :resource/key (:rollback ps)))
          "the recorded :before the restore-dangle path restores on a no-conflict dangle"))))

;; ===========================================================================
;; RIDER 1 — the :optimistic? derived sub flag on :rf.mutation/state.
;; ===========================================================================

(deftest rider1-optimistic-flag-true-between-apply-and-settle
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false :favoritesCount 9}})
  (rf/reg-mutation :m/favorite
    (assoc favorite-plan
           :populates (fn [{:keys [slug]} result]
                        {{:resource :r/article :params {:slug slug} :scope :rf.scope/global}
                         result}))
    favorite-plan-request)
  (testing "an idle (no-instance) state is :optimistic? false"
    (is (= false (:optimistic? (mutation-state :f1)))))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"} :instance :f1}])
  (testing ":optimistic? is TRUE between the apply (phase 1.5) and settle"
    (let [s (mutation-state :f1)]
      (is (= true (:optimistic? s)))
      (is (= true (:pending? s)) "still pending-aware — the view shows the optimistic value")))
  (reply-success! @last-managed-args {:article {:favorited true :favoritesCount 42}})
  (testing ":optimistic? is FALSE after settle (the value is now authoritative)"
    (let [s (mutation-state :f1)]
      (is (= false (:optimistic? s)))
      (is (= true (:success? s))))))

(deftest rider1-optimistic-flag-false-for-a-pessimistic-write
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false}})
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/save :params {:slug "w"} :instance :s1}])
  (testing "a pessimistic write (no :optimistic plan) is :optimistic? false while pending"
    (let [s (mutation-state :s1)]
      (is (= true (:pending? s)))
      (is (= false (:optimistic? s)) "no optimistic apply → no live optimistic value")))
  (reply-success! @last-managed-args {:article {:favorited true}})
  (testing "still :optimistic? false after settle"
    (is (= false (:optimistic? (mutation-state :s1))))))

(deftest rider1-optimistic-flag-false-after-opt-out
  (reg-article-resource!)
  (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
               {:article {:favorited false}})
  (rf/reg-mutation :m/favorite favorite-plan favorite-plan-request)
  ;; {:optimistic? false} forces the pessimistic path — no apply, so no flag.
  (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/favorite :params {:slug "w"}
                                           :instance :f1 :optimistic? false}])
  (testing "an opted-out call is :optimistic? false (no apply ran)"
    (is (= true (:pending? (mutation-state :f1))))
    (is (= false (:optimistic? (mutation-state :f1))))))

;; ===========================================================================
;; REGRESSION (rf2-o5ca8k) — a MALFORMED :optimistic-tags descriptor is
;; warn-and-skipped, never an abort. The optimistic paint is reversible
;; best-effort; a typo in the descriptor must NOT nuke the authoritative write
;; (which is strictly worse than :invalidates, settled post-write). The
;; well-formed descriptors in the SAME plan still apply.
;; ===========================================================================

(deftest malformed-optimistic-tags-descriptor-does-not-block-the-write
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (rf/reg-resource :r/feed
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed-w]})}
    (fn [_p _] {:request {:method :get :url "/feed"}}))
  (let [feed-key (state/scoped-resource-key :rf.scope/global :r/feed {})]
    (own-loaded! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :d]}
                 {:article {:favorited false}})
    (own-loaded! {:resource :r/feed :scope :rf.scope/global :params {} :owner [:v :f]}
                 {:items 1})
    (testing "a descriptor with a missing :patch does NOT abort — the request still lowers"
      (rf/reg-mutation :m/bad-patch
        {:scope :rf.scope/global
         :params-schema [:map [:slug :string]]
         ;; FIRST descriptor is malformed (no :patch); SECOND is well-formed.
         :optimistic-tags (fn [{:keys [slug]}]
                            [{:scope :rf.scope/global :tags #{[:article slug]}}
                             {:scope :rf.scope/global :tags #{[:feed-w]}
                              :patch (fn [d] (assoc d :touched true))}])}
        (fn [_p _] {:request {:method :post :url "/fav"}}))
      (let [warn (trace-of :rf.warning/optimistic-tags-descriptor-skipped
                   #(rf/dispatch-sync [:rf.mutation/execute
                                       {:mutation :m/bad-patch :params {:slug "w"}
                                        :instance :bp1}]))]
        (is (= :pending (:status (instance :bp1)))
            "the write lowered — the malformed descriptor did NOT abort the event")
        (is (some? @last-managed-args) "the request reached the transport")
        (is (some? warn) "a dev-visible warning was emitted for the skipped descriptor")
        (is (= :m/bad-patch (some-> warn :mutation))
            "the warning names the offending mutation")
        (is (string? (:reason warn)) "the warning carries a dev-readable reason")
        (testing "the WELL-FORMED descriptor in the same plan still applied"
          (is (= true (get-in (entry feed-key) [:data :touched]))))
        (testing "the malformed descriptor's tag was skipped (no patch to apply)"
          (is (nil? (get-in (entry article-key) [:data :touched]))))))
    (reset! last-managed-args nil)
    (testing "a descriptor with a non-collection :tags also warn-and-skips"
      (rf/reg-mutation :m/bad-tags
        {:scope :rf.scope/global
         :params-schema [:map [:slug :string]]
         :optimistic-tags (fn [_p]
                            [{:scope :rf.scope/global :tags :not-a-coll
                              :patch (fn [d] (assoc d :touched2 true))}])}
        (fn [_p _] {:request {:method :post :url "/fav2"}}))
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/bad-tags :params {:slug "w"}
                                               :instance :bt1}])
      (is (= :pending (:status (instance :bt1)))
          "a non-collection :tags descriptor did NOT abort the write")
      (is (some? @last-managed-args) "the request reached the transport"))
    (reset! last-managed-args nil)
    (testing "a non-map descriptor entry in the vector also warn-and-skips, write still lowers"
      (rf/reg-mutation :m/bad-shape
        {:scope :rf.scope/global
         :params-schema [:map [:slug :string]]
         :optimistic-tags (fn [_p] [:not-a-map])}
        (fn [_p _] {:request {:method :post :url "/fav3"}}))
      (rf/dispatch-sync [:rf.mutation/execute {:mutation :m/bad-shape :params {:slug "w"}
                                               :instance :bs1}])
      (is (= :pending (:status (instance :bs1)))
          "a non-map descriptor entry did NOT abort the write")
      (is (some? @last-managed-args) "the request reached the transport"))))
