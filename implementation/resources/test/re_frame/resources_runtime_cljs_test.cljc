(ns re-frame.resources-runtime-cljs-test
  "Runtime behaviour for the Resources artefact (rf2-pbxj48, Spec 016
  §EP-0003 slice 4 — the resource RUNTIME).

  These JVM+CLJS unit tests pin the cache-entry runtime this slice
  implements:

    1. canonical params + scope identity (key-order-independent;
       serializable-EDN-only; nil-vs-missing schema-defined);
    2. fail-closed scope policy (no `[:rf.scope/global]` fallthrough;
       missing scope policy → loud error; from-caller required);
    3. the compact lifecycle status transition fn (:idle/:loading/
       :fetching/:loaded/:error + :refresh-error) — a pure fn, NOT a
       spawned machine;
    4. structural sharing (preserve the old :data value when the newly-
       decoded value equals the previous);
    5. durable entries store FACTS, not derived booleans (the booleans
       are computed in subs);
    6. the passive subscriptions (none fetch);
    7. per-frame ISOLATION (resources in frame A invisible to frame B);
    8. stale suppression on the entry (a superseded reply never mutates a
       newer entry);
    9. owner / tag indexes, exact tag invalidation, scope clear, remove.

  HTTP transport execution, the serializable work-ledger records, host
  side tables, abort, GC timers, route/SSR/Xray are LATER slices; this
  test does not exercise them. The transport is decoupled by overriding
  the `:rf.http/managed` fx with a capturing no-op so ensure's entry write
  and reply-handler semantics are tested deterministically without a live
  fetch."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   [re-frame.registrar :as registrar]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the generation cofx/fx these tests
   ;; dispatch / subscribe to.
   [re-frame.resources]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs :as subs]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing no-op below.
   [re-frame.http-managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport (decouples entry-state tests from HTTP) ----------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a capturing no-op so ensure's
  :loading entry write + lower-fx are deterministic and no real fetch fires.
  Composed INSIDE the reset-runtime fixture (one `use-fixtures` call — a
  second `use-fixtures :each` would REPLACE, not accumulate)."
  [f]
  (reset! last-managed-args nil)
  ;; the resources reset hook is not yet in the core reset-hook-table, so
  ;; clear the host-side generation high-water marks here for per-test
  ;; isolation (otherwise generations leak across tests).
  (state/reset-cache!)
  ;; fx handlers are BINARY `(fn [ctx args] …)` (Spec 002 §binary
  ;; fx-handler signature) — capture the args (second arg).
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
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

(defn- entry
  "The durable cache entry for a scoped key in a frame (default
  :rf/default)."
  ([scoped-key] (entry :rf/default scoped-key))
  ([frame-id scoped-key]
   (get-in (runtime-db frame-id) (state/entry-path scoped-key))))

(defn- article-spec
  "A minimal valid resource spec (global scope, a slug param)."
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :request       (fn [{:keys [slug]} _ctx]
                            {:request {:method :get :url (str "/api/articles/" slug)}})
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

;; ===========================================================================
;; 1. Canonical params + scope identity
;; ===========================================================================

(deftest canonicalization-is-key-order-independent
  (testing "scoped key is identical regardless of map key order (Spec 016
            §Canonicalization rule)"
    (let [k1 (state/scoped-resource-key {:tenant "acme" :user "u-42"}
                                        :article/by-slug {:slug "x" :rev 1})
          k2 (state/scoped-resource-key {:user "u-42" :tenant "acme"}
                                        :article/by-slug {:rev 1 :slug "x"})]
      (is (= k1 k2) "two spellings of the same scope + params collapse to one key")))
  (testing "nested maps recurse; sets / vectors keep value semantics"
    (is (= (state/canonicalize {:a {:c 3 :b 2} :z #{2 1}})
           (state/canonicalize {:z #{1 2} :a {:b 2 :c 3}})))))

(deftest host-values-rejected-at-the-cache-key-boundary
  (testing "a host / opaque param value is rejected loudly (Spec 016
            §Resource identity)"
    (is (false? (state/serializable-edn? {:f (fn [])})))
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (state/reject-non-edn! {:f (fn [])} 'test :params :r/x)))))

;; ===========================================================================
;; 2. Fail-closed scope policy (no global fallthrough)
;; ===========================================================================

(deftest scope-resolution-fail-closed
  (rf/reg-resource :sr/from-caller (article-spec {:scope :rf.scope/from-caller}))
  (testing "from-caller with no payload scope is a loud use-time error
            (Spec 016 §Scope resolution — no global fallthrough)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-scope-required-from-caller"
          (registry/resolve-scope-for-event
            :sr/from-caller (registry/resource-meta :sr/from-caller)
            {:payload-scope nil} 'test))))
  (testing "from-caller WITH a payload scope resolves to that scope"
    (is (= {:user "u-1"}
           (registry/resolve-scope-for-event
             :sr/from-caller (registry/resource-meta :sr/from-caller)
             {:payload-scope {:user "u-1"}} 'test))))
  (testing "an explicit :rf.scope/global policy resolves to global (its
            declared policy, not a fallthrough)"
    (rf/reg-resource :sr/global (article-spec))
    (is (= :rf.scope/global
           (registry/resolve-scope-for-event
             :sr/global (registry/resource-meta :sr/global) {} 'test)))))

(deftest sub-side-scope-fail-closed
  (rf/reg-resource :ss/from-caller (article-spec {:scope :rf.scope/from-caller}))
  (testing "a sub that cannot resolve a scope raises
            :rf.error/resource-sub-unresolved-scope (never a silent global
            read / :idle) — Spec 016 §Subscription-side scope resolution"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-sub-unresolved-scope"
          (subs/resolve-scoped-key {:resource :ss/from-caller :params {:slug "x"}})))))

;; ===========================================================================
;; 3. Pure lifecycle status transition fn (NOT a spawned machine)
;; ===========================================================================

(deftest status-transition-fn-is-pure
  (testing "Spec 016 §Lifecycle is an FSM — a pure transition fn over the
            five states"
    (is (= :loading  (state/next-status :idle    :start-load false)))
    (is (= :fetching (state/next-status :loaded  :start-load true)))
    (is (= :loaded   (state/next-status :loading :success   false)))
    (is (= :error    (state/next-status :loading :failure   false)))
    ;; background-refresh failure returns to :loaded (data kept)
    (is (= :loaded   (state/next-status :fetching :failure  true)))
    (is (= :loading  (state/next-status :error    :start-load false)))))

;; ===========================================================================
;; 4. ensure → :loading → succeeded → :loaded  +  structural sharing
;; ===========================================================================

(deftest ensure-then-success-loaded
  (rf/reg-resource :a/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :a/article {:slug "welcome"})]
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :a/article :scope :rf.scope/global
                        :params {:slug "welcome"} :owner [:lease :test 1]}])
    (testing "ensure transitions the (no-data) entry to :loading and mints
              a generation + current-work pointer"
      (let [e (entry scoped-key)]
        (is (= :loading (:status e)))
        (is (= 1 (:generation e)))
        (is (some? (:current-work e)))
        (is (contains? (:active-owners e) [:lease :test 1]))))
    (testing "the transport was lowered (the capturing :rf.http/managed fx
              saw the runtime-owned request-id + reply addressing)"
      (is (some? @last-managed-args))
      (is (= [:rf.resource.internal/succeeded] (subvec (:on-success @last-managed-args) 0 1)))
      (is (some? (:request-id @last-managed-args))))
    ;; simulate the transport reply (the managed-HTTP slice will dispatch
    ;; this for real; here we feed the internal reply directly)
    (let [work-id (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id work-id :generation 1
                          :data {:title "Welcome"}}])
      (testing "succeeded settles :loaded with the decoded data + produced tags"
        (let [e (entry scoped-key)]
          (is (= :loaded (:status e)))
          (is (= {:title "Welcome"} (:data e)))
          (is (nil? (:current-work e)))
          (is (= #{[:article "welcome"]} (:tags e)))))
      (testing "durable entry stores FACTS, not derived booleans (Spec 016
                §Status semantics)"
        (let [e (entry scoped-key)]
          (is (not (contains? e :loading?)))
          (is (not (contains? e :stale?)))
          (is (not (contains? e :has-data?))))))))

(deftest structural-sharing-preserves-identical-data
  (rf/reg-resource :ss/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ss/article {:slug "w"})
        data1      {:title "Welcome" :body [1 2 3]}]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ss/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ss 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id wid1 :generation 1 :data data1}]))
    (let [first-data (:data (entry scoped-key))]
      ;; a refetch returns an EQUAL but freshly-constructed value
      (rf/dispatch-sync [:rf.resource/refetch {:resource :ss/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (let [wid2 (:current-work (entry scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key scoped-key :work-id wid2 :generation 2
                            :data {:title "Welcome" :body [1 2 3]}}]))
      (testing "Spec 016 §Structural sharing — the old :data value is
                preserved (identity) when the newly-decoded value is ="
        (is (identical? first-data (:data (entry scoped-key)))
            "equal new data keeps the OLD value identity (quiet downstream)")))))

;; ===========================================================================
;; 5. background-refresh failure keeps prior data (:refresh-error)
;; ===========================================================================

(deftest refresh-failure-keeps-data
  (rf/reg-resource :rf2/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :rf2/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rf2/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rf2 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id wid1 :generation 1
                          :data {:title "Welcome"}}]))
    (rf/dispatch-sync [:rf.resource/refetch {:resource :rf2/article :scope :rf.scope/global
                                             :params {:slug "w"}}])
    (testing "refetch with existing data → :fetching (prior data visible)"
      (is (= :fetching (:status (entry scoped-key))))
      (is (= {:title "Welcome"} (:data (entry scoped-key)))))
    (let [wid2 (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource-key scoped-key :work-id wid2 :generation 2
                          :error {:kind :rf.http/http-5xx :status 503}}]))
    (testing "Spec 016 §Status semantics — a background-refresh failure
              returns to :loaded, keeps prior :data, records :refresh-error"
      (let [e (entry scoped-key)]
        (is (= :loaded (:status e)))
        (is (= {:title "Welcome"} (:data e)))
        (is (= {:kind :rf.http/http-5xx :status 503} (:refresh-error e)))
        (is (nil? (:error e)))))))

(deftest first-load-failure-error
  (rf/reg-resource :fl/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :fl/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :fl/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :fl 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/failed
                         {:resource-key scoped-key :work-id wid :generation 1
                          :error {:kind :rf.http/http-5xx :status 503}}]))
    (testing "first-load failure → :error, no usable data"
      (let [e (entry scoped-key)]
        (is (= :error (:status e)))
        (is (nil? (:data e)))
        (is (= {:kind :rf.http/http-5xx :status 503} (:error e)))))))

;; ===========================================================================
;; 6. Stale suppression on the entry (superseded reply never mutates)
;; ===========================================================================

(deftest stale-reply-is-suppressed
  (rf/reg-resource :st/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :st/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :st/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :st 1]}])
    (let [wid1 (:current-work (entry scoped-key))]
      ;; a newer refetch supersedes (generation 2)
      (rf/dispatch-sync [:rf.resource/refetch {:resource :st/article :scope :rf.scope/global
                                               :params {:slug "w"}}])
      (is (= 2 (:generation (entry scoped-key))))
      (testing "Spec 016 §stale suppression — a late reply carrying the OLD
                generation/work-id is suppressed (never mutates the newer
                entry)"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key scoped-key :work-id wid1 :generation 1
                            :data {:stale "data"}}])
        (let [e (entry scoped-key)]
          (is (not= {:stale "data"} (:data e)) "stale reply did not write")
          (is (= 2 (:generation e)) "entry generation unchanged by the stale reply")
          (is (= :loading (:status e)) "entry still in flight on its current gen"))))))

;; ===========================================================================
;; 7. ensure dedupe (join in-flight)
;; ===========================================================================

(deftest ensure-dedupes-in-flight
  (rf/reg-resource :dd/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :dd/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]}])
    (let [gen1 (:generation (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :dd/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease :x 2]}])
      (testing "Spec 016 §Race — a second ensure while in flight JOINS (no
                new generation), attaching the new owner"
        (let [e (entry scoped-key)]
          (is (= gen1 (:generation e)) "no new generation on dedupe")
          (is (contains? (:active-owners e) [:route :r 1]))
          (is (contains? (:active-owners e) [:lease :x 2])))))))

;; ===========================================================================
;; 8. Per-frame ISOLATION (frame A invisible to frame B)
;; ===========================================================================

(deftest per-frame-isolation
  (rf/reg-resource :iso/article (article-spec))
  (let [fa :iso/frame-a
        fb :iso/frame-b
        scoped-key (state/scoped-resource-key :rf.scope/global :iso/article {:slug "w"})]
    (rf/reg-frame fa {:doc "isolation frame A"})
    (rf/reg-frame fb {:doc "isolation frame B"})
    (testing "Spec 016 — resources are per-frame isolated; a resource
              loaded in frame A is invisible in frame B"
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :iso/article :scope :rf.scope/global
                          :params {:slug "w"} :owner [:lease :a 1]}]
                        {:frame fa})
      (let [wid (:current-work (entry fa scoped-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key scoped-key :work-id wid :generation 1
                            :data {:title "A"}}]
                          {:frame fa}))
      (is (= {:title "A"} (:data (entry fa scoped-key))) "frame A has the entry")
      (is (nil? (entry fb scoped-key)) "frame B has NO entry (isolated)"))
    (frame/destroy-frame! fa)
    (frame/destroy-frame! fb)))

;; ===========================================================================
;; 9. Passive subscriptions (none fetch)
;; ===========================================================================

(deftest passive-subs-project-the-entry
  (rf/reg-resource :sub/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :sub/article {:slug "w"})
        q          {:resource :sub/article :scope :rf.scope/global :params {:slug "w"}}]
    (testing "before any load the state projection is the idle empty-state
              (a sub NEVER fetches — Spec 016 §Subscriptions)"
      (is (= {:status :idle :data nil :error nil :refresh-error nil
              :loading? false :fetching? false :stale? false :has-data? false}
             @(rf/subscribe [:rf.resource/state q])))
      (is (nil? (entry scoped-key)) "subscribing did not cause a fetch / entry"))
    (rf/dispatch-sync [:rf.resource/ensure {:resource :sub/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :s 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id wid :generation 1
                          :data {:title "Welcome"}}]))
    (testing "after load the derived booleans are computed in the sub
              (loaded / has-data?), not stored on the entry"
      (is (= :loaded (:status @(rf/subscribe [:rf.resource/state q]))))
      (is (true?  @(rf/subscribe [:rf.resource/has-data? q])))
      (is (false? @(rf/subscribe [:rf.resource/loading? q])))
      (is (= {:title "Welcome"} @(rf/subscribe [:rf.resource/data q]))))))

(deftest stale-sub-derives-from-facts
  (rf/reg-resource :stl/article (article-spec {:stale-after-ms 60000}))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :stl/article {:slug "w"})
        q          {:resource :stl/article :scope :rf.scope/global :params {:slug "w"}}]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :stl/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :st 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id wid :generation 1
                          :data {:title "W"}}]))
    (testing "a freshly loaded entry (stale-after 60s) is not stale"
      (is (false? @(rf/subscribe [:rf.resource/stale? q]))))
    ;; release the owner so invalidation marks the entry stale WITHOUT
    ;; auto-refetching it (a refetch would satisfy + clear the invalidation)
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :st 1]}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope :rf.scope/global :tags #{[:article "w"]}}])
    (testing "after exact-tag invalidation of an INACTIVE entry, :stale?
              derives true from the durable :invalidated-at fact (Spec 016
              §Invalidation / §Status semantics)"
      (is (some? (:invalidated-at (entry scoped-key))) "durable fact set")
      (is (true? @(rf/subscribe [:rf.resource/stale? q]))))))

;; ===========================================================================
;; 10. owner release / clear-scope / remove / tag invalidation
;; ===========================================================================

(deftest release-owner-drops-the-lease
  (rf/reg-resource :ro/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :ro/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :ro/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :ro 1]}])
    (is (contains? (:active-owners (entry scoped-key)) [:lease :ro 1]))
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :ro 1]}])
    (testing "Spec 016 §Active owners — release drops the owner from the
              entry + the owner-index"
      (is (not (contains? (:active-owners (entry scoped-key)) [:lease :ro 1])))
      (is (nil? (get-in (runtime-db) (conj (state/owner-index-path) [:lease :ro 1])))))))

(deftest clear-scope-removes-scoped-entries
  (rf/reg-resource :cs/article (article-spec {:scope :rf.scope/from-caller}))
  (let [scope-a {:user "a"}
        scope-b {:user "b"}
        ka (state/scoped-resource-key scope-a :cs/article {:slug "w"})
        kb (state/scoped-resource-key scope-b :cs/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cs/article :scope scope-a
                                            :params {:slug "w"} :owner [:lease :a 1]}])
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cs/article :scope scope-b
                                            :params {:slug "w"} :owner [:lease :b 1]}])
    (is (some? (entry ka)))
    (is (some? (entry kb)))
    (rf/dispatch-sync [:rf.resource/clear-scope {:scope scope-a :cause :logout}])
    (testing "Spec 016 §clear-scope — only the cleared scope's entries are
              removed; other scopes untouched (no cross-scope leak)"
      (is (nil?  (entry ka)) "scope A cleared")
      (is (some? (entry kb)) "scope B untouched"))))

(deftest invalidate-tags-marks-stale-and-refetches-active
  (rf/reg-resource :it/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :it/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :it/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:route :r 1]}])
    (let [wid (:current-work (entry scoped-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key scoped-key :work-id wid :generation 1
                          :data {:title "W"}}]))
    (is (= :loaded (:status (entry scoped-key))))
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope :rf.scope/global :tags #{[:article "w"]}}])
    (testing "Spec 016 §Invalidation — a matched active-owner entry is
              marked stale (durable :invalidated-at) and refetched
              (→ :fetching, prior data kept)"
      (let [e (entry scoped-key)]
        ;; the refetch dispatch (active owner) bumps it to :fetching
        (is (= :fetching (:status e)))
        (is (= {:title "W"} (:data e)) "prior data kept during refetch")))))

(deftest remove-evicts-the-entry
  (rf/reg-resource :rm/article (article-spec))
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :rm/article {:slug "w"})]
    (rf/dispatch-sync [:rf.resource/ensure {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"} :owner [:lease :rm 1]}])
    (is (some? (entry scoped-key)))
    (rf/dispatch-sync [:rf.resource/remove {:resource :rm/article :scope :rf.scope/global
                                            :params {:slug "w"}}])
    (testing "Spec 016 §Events — remove evicts the single instance"
      (is (nil? (entry scoped-key))))))

;; ===========================================================================
;; 11. reverse-index recompute is derivable from entries
;; ===========================================================================

(deftest indexes-recompute-from-entries
  (testing "Spec 016 §Restore and replay part 5 — :tag-index / :owner-index
            are recomputable-from-:entries (never trusted from a snapshot)"
    (let [k1 [:rf.scope/global :r/a {:id 1}]
          k2 [:rf.scope/global :r/b {:id 2}]
          subtree {:entries {k1 {:tags #{:t1 :shared} :active-owners #{[:lease 1]}}
                             k2 {:tags #{:t2 :shared} :active-owners #{[:lease 1]}}}}
          rebuilt (state/recompute-indexes subtree)]
      (is (= #{k1 k2} (get-in rebuilt [:tag-index :shared])))
      (is (= #{k1}    (get-in rebuilt [:tag-index :t1])))
      (is (= #{k1 k2} (get-in rebuilt [:owner-index [:lease 1]]))))))

;; ===========================================================================
;; 12. resource registrar sanity (re-affirm the registrar kind is closed)
;; ===========================================================================

(deftest resource-kind-registered
  (testing "the :resource registrar kind is valid (skeleton invariant held)"
    (is (registrar/valid-kind? :resource))
    (is (not (registrar/valid-kind? :query)))))
