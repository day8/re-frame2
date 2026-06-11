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
   [re-frame.late-bind :as late-bind]
   [re-frame.registrar :as registrar]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the generation cofx/fx these tests
   ;; dispatch / subscribe to.
   [re-frame.resources]
   [re-frame.resources.registry :as registry]
   [re-frame.resources.mutation-registry :as mreg]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs :as subs]
   [re-frame.resources.test-support :as resources-test-support]
   [re-frame.resources.timers :as timers]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.resources.revalidate-listeners :as revalidate-listeners]
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
  ;; rf2-yuc8o0: the shared `make-reset-runtime-fixture` reset-hook-table now
  ;; fires `:resources/reset-resources!` (which clears the host-side
  ;; generation high-water marks) in its `:post-dispose` phase before each
  ;; test body, so no per-suite generation-cache reset is needed here.
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
              :loading? false :fetching? false :stale? false :has-data? false
              :previous? false}
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

;; ===========================================================================
;; 13. Concrete-scope typo rejection at resolution boundaries (rf2-pd7akw)
;; ===========================================================================

(deftest reserved-scope-typo-rejected-at-concrete-boundaries
  (rf/reg-resource :tp/article (article-spec {:scope :rf.scope/from-caller}))
  (let [spec (registry/resource-meta :tp/article)]
    (testing "rf2-pd7akw — a misspelled reserved :rf.scope/* keyword on an
              EVENT payload is rejected fail-closed (never a silent wrong
              cache scope)"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (registry/resolve-scope-for-event
              :tp/article spec {:payload-scope :rf.scope/glabal} 'test))))
    (testing "rf2-pd7akw — a misspelled reserved :rf.scope/* keyword on a
              SUBSCRIPTION payload is rejected fail-closed too"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (registry/resolve-scope-for-sub
              :tp/article spec :rf.scope/sesssion 'test))))
    (testing "rf2-pd7akw — :rf.scope/from-caller reaching a CONCRETE boundary
              as a payload value (not a policy) is a typo-class rejection"
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
            (registry/resolve-scope-for-event
              :tp/article spec {:payload-scope :rf.scope/from-caller} 'test))))
    (testing "an APP-namespaced keyword scope is a legitimate literal scope
              (only the framework-reserved namespace is fail-closed)"
      (is (= :my.app/tenant
             (registry/resolve-scope-for-event
               :tp/article spec {:payload-scope :my.app/tenant} 'test))))
    (testing "a vector-tuple scope [:rf.scope/session {…}] is a value, not a
              bare-keyword typo — accepted"
      (is (= [:rf.scope/session {:tenant "acme"}]
             (registry/resolve-scope-for-event
               :tp/article spec {:payload-scope [:rf.scope/session {:tenant "acme"}]}
               'test))))))

;; ===========================================================================
;; 14. Singleton-vector [:rf.scope/global] cannot create a 2nd global key
;;     (rf2-vv87xz, impl/guard half)
;; ===========================================================================

(deftest singleton-vector-global-normalizes-to-bare-global
  (testing "rf2-vv87xz — a [:rf.scope/global] payload normalizes to the bare
            :rf.scope/global so it collapses to the SAME global cache key the
            implementation resolves an explicit-global policy to (it cannot
            silently create a second, distinct global key)"
    (let [k-bare      (state/canonicalize-scope :rf.scope/global 'test :r/x)
          k-singleton (state/canonicalize-scope [:rf.scope/global] 'test :r/x)]
      (is (= :rf.scope/global k-bare))
      (is (= :rf.scope/global k-singleton)
          "singleton-vector spelling collapses to the bare canonical scope")
      (is (= k-bare k-singleton) "both spellings produce ONE global cache key")))
  (testing "rf2-vv87xz — end-to-end: an ensure under :rf.scope/global and a
            sub under [:rf.scope/global] read the SAME entry (no second key)"
    (rf/reg-resource :gv/article (article-spec))
    (let [bare-key (state/scoped-resource-key :rf.scope/global :gv/article {:slug "w"})]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :gv/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease 1]}])
      (let [wid (:current-work (entry bare-key))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key bare-key :work-id wid :generation 1
                            :data {:title "W"}}]))
      ;; a sub payload using the historical singleton-vector spelling resolves
      ;; to the SAME bare key — it reads the loaded entry, not a fresh skeleton.
      (is (= bare-key
             (subs/resolve-scoped-key {:resource :gv/article
                                       :scope [:rf.scope/global]
                                       :params {:slug "w"}}))))))

;; ===========================================================================
;; 15. clear-resource disposes live runtime state (rf2-m9h5iq)
;; ===========================================================================

(deftest clear-resource-disposes-runtime-state
  (rf/reg-resource :cr/article (article-spec))
  (let [loaded-key  (state/scoped-resource-key :rf.scope/global :cr/article {:slug "loaded"})
        inflight-key (state/scoped-resource-key :rf.scope/global :cr/article {:slug "inflight"})]
    ;; one LOADED entry (with tags + an active owner → indexed)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cr/article :scope :rf.scope/global
                                            :params {:slug "loaded"} :owner [:lease :cr 1]}])
    (let [wid (:current-work (entry loaded-key))]
      (rf/dispatch-sync [:rf.resource.internal/succeeded
                         {:resource-key loaded-key :work-id wid :generation 1
                          :data {:title "L"} :tags #{[:article "loaded"]}}]))
    ;; one IN-FLIGHT entry (still :loading, has :current-work)
    (rf/dispatch-sync [:rf.resource/ensure {:resource :cr/article :scope :rf.scope/global
                                            :params {:slug "inflight"} :owner [:lease :cr 2]}])
    (is (= :loaded (:status (entry loaded-key))))
    (is (some? (:current-work (entry inflight-key))) "in-flight entry has live work")
    (is (seq (get-in (runtime-db) (state/tag-index-path))) "tag index populated")
    (is (seq (get-in (runtime-db) (state/owner-index-path))) "owner index populated")
    (let [inflight-wid (:current-work (entry inflight-key))]
      ;; CLEAR the resource (registration-lifecycle + runtime disposal)
      (registry/clear-resource :cr/article)
      (testing "rf2-m9h5iq — clear-resource removes the registrar entry"
        (is (nil? (registry/resource-meta :cr/article))))
      (testing "rf2-m9h5iq — every live entry for the id is removed from
                :rf.runtime/resources :entries"
        (is (nil? (entry loaded-key)))
        (is (nil? (entry inflight-key))))
      (testing "rf2-m9h5iq — reverse indexes are recomputed/pruned"
        (is (empty? (get-in (runtime-db) (state/tag-index-path))))
        (is (empty? (get-in (runtime-db) (state/owner-index-path)))))
      (testing "rf2-m9h5iq — the in-flight work record is settled terminal
                (:suppressed) so the ledger row is not left in-flight"
        (let [rec (get-in (runtime-db) (state/work-record-path inflight-wid))]
          ;; record may be pruned or marked terminal; if present it must be
          ;; the terminal :suppressed status, never still in-flight.
          (when rec
            (is (= :suppressed (:status rec))))))
      (testing "rf2-m9h5iq — a LATE reply for a cleared in-flight entry cannot
                recreate it (its existence check finds the entry gone)"
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key inflight-key :work-id inflight-wid
                            :generation 1 :data {:title "late"}}])
        (is (nil? (entry inflight-key))
            "late reply suppressed — no resurrected entry")))))

;; ===========================================================================
;; 16. mutation scope routes through shared validation (rf2-lzv9xc)
;; ===========================================================================

(deftest mutation-scope-routes-through-shared-validation
  (testing "rf2-lzv9xc — a mutation execute-payload scope that is a reserved
            :rf.scope/* typo is rejected through the same path resources use"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-invalid-scope"
          (mreg/resolve-scope :m/x {} :rf.scope/glabal))))
  (testing "rf2-lzv9xc — a host / opaque mutation scope value is rejected"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-non-edn-params"
          (mreg/resolve-scope :m/x {} {:fn (fn [])}))))
  (testing "rf2-lzv9xc — the default global scope still resolves, and the
            [:rf.scope/global] spelling normalizes to bare (no 2nd key)"
    (is (= :rf.scope/global (mreg/resolve-scope :m/x {} nil)))
    (is (= :rf.scope/global (mreg/resolve-scope :m/x {} [:rf.scope/global])))))

;; ===========================================================================
;; 17. resource-state fails closed without an explicit frame (rf2-c8lgy3)
;; ===========================================================================

(deftest resource-state-fails-closed-without-frame
  (rf/reg-resource :rs/article (article-spec))
  (testing "rf2-c8lgy3 — a frameless resource-state call raises
            :rf.error/no-frame-context (never a silent nil that is
            indistinguishable from an absent entry)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (re-frame.resources/resource-state
            {:resource :rs/article :scope :rf.scope/global :params {:slug "w"}}))))
  (testing "rf2-c8lgy3 — a valid explicit frame returns nil ONLY for a
            genuinely absent entry"
    (is (nil? (re-frame.resources/resource-state
                {:resource :rs/article :scope :rf.scope/global
                 :params {:slug "absent"} :frame :rf/default}))))
  (testing "rf2-c8lgy3 — a valid explicit frame returns the entry when present"
    (let [k (state/scoped-resource-key :rf.scope/global :rs/article {:slug "w"})]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :rs/article :scope :rf.scope/global
                                              :params {:slug "w"} :owner [:lease 1]}])
      (let [wid (:current-work (entry k))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource-key k :work-id wid :generation 1
                            :data {:title "W"}}]))
      (is (some? (re-frame.resources/resource-state
                   {:resource :rs/article :scope :rf.scope/global
                    :params {:slug "w"} :frame :rf/default}))))))

;; ===========================================================================
;; 18. param canonicalization is total over mixed EDN key types (rf2-ptz7z8)
;; ===========================================================================

(deftest param-canonicalization-total-over-mixed-keys
  (testing "rf2-ptz7z8 — a params map mixing keyword and string keys
            canonicalizes deterministically (no raw ClassCastException)"
    (let [c1 (state/canonicalize {:b 1 "a" 2 :a 3 "z" 4})
          c2 (state/canonicalize {"z" 4 :a 3 "a" 2 :b 1})]
      (is (= c1 c2) "key-order-independent over mixed key types")
      (is (= {:b 1 "a" 2 :a 3 "z" 4} c1) "values preserved")))
  (testing "rf2-ptz7z8 — mixed nil / number / boolean / keyword / string keys
            all order deterministically"
    (let [m {nil 0 1 :one true :t :kw :k "s" :str}]
      (is (= (state/canonicalize m) (state/canonicalize (into {} (shuffle (seq m))))))))
  (testing "rf2-ptz7z8 — a scoped key built from mixed-key params is stable"
    (is (= (state/scoped-resource-key :rf.scope/global :r/x {:a 1 "b" 2})
           (state/scoped-resource-key :rf.scope/global :r/x {"b" 2 :a 1})))))

;; ===========================================================================
;; 19. Shared reset contract (rf2-784223) — `make-reset-runtime-fixture` plus
;;     `re-frame.resources.test-support` clears the resource + mutation
;;     registrars AND every resources host-side side table.
;; ===========================================================================

(deftest reset-resources-clears-registrars-and-host-side-tables
  ;; This is the contract the per-suite fixtures now RELY on (rf2-784223):
  ;; instead of each fixture redundantly re-resetting these caches, the
  ;; shared `make-reset-runtime-fixture` fires `:resources/reset-resources!`
  ;; (published at `re-frame.resources.test-support` ns-load). Prove that one
  ;; thunk clears the whole surface, so the redundant per-suite resets were
  ;; safe to remove.
  (testing "the late-bind reset hook IS published (the fixture's mechanism)"
    (is (some? (late-bind/get-fn :resources/reset-resources!))
        ":resources/reset-resources! hook published at test-support ns-load")
    (is (identical? resources-test-support/reset-resources!
                    (late-bind/get-fn :resources/reset-resources!))
        "the published hook IS test-support/reset-resources!"))
  ;; Seed every surface the reset must clear.
  (rf/reg-resource :rst/article (article-spec))
  (rf/reg-mutation :rst/save
                   {:params-schema [:map [:slug :string]]
                    :request (fn [{:keys [slug]} _ctx]
                               {:request {:method :post :url (str "/api/articles/" slug)}})})
  (state/commit-generation! :rf/default 7)
  (let [k       (state/scoped-resource-key :rf.scope/global :rst/article {:slug "w"})
        work-id (work-ledger/resource-work-id k 1)]
    (work-ledger/put-handle! :rf/default work-id {:abort-fn (fn [] nil)})
    (timers/schedule! :rf/default k timers/gc-kind 1000000)
    (swap! revalidate-listeners/listener-table assoc :rf/default
           {:focus :h :visibility :h :online :h})
    ;; precondition: everything is populated
    (is (contains? (registrar/registrations registry/resource-kind) :rst/article))
    (is (contains? (registrar/registrations mreg/mutation-kind) :rst/save))
    (is (= 7 (state/generation-snapshot :rf/default)))
    (is (some? (work-ledger/get-handle :rf/default work-id)))
    (is (contains? @timers/timer-table [:rf/default k timers/gc-kind]))
    (is (contains? @revalidate-listeners/listener-table :rf/default))
    (testing "rf2-784223 — `reset-resources!` clears the resource + mutation
              registrars AND the generation / work-ledger-handle / timer /
              revalidate-listener host side tables in ONE call"
      (resources-test-support/reset-resources!)
      (is (empty? (registrar/registrations registry/resource-kind))
          "resource registrar cleared")
      (is (empty? (registrar/registrations mreg/mutation-kind))
          "mutation registrar cleared")
      (is (= 0 (state/generation-snapshot :rf/default))
          "generation high-water cache cleared")
      (is (nil? (work-ledger/get-handle :rf/default work-id))
          "work-ledger host handle table cleared")
      (is (not (contains? @timers/timer-table [:rf/default k timers/gc-kind]))
          "stale/GC timer table cleared (timer cancelled)")
      (is (not (contains? @revalidate-listeners/listener-table :rf/default))
          "revalidation-listener side table cleared"))))
