(ns re-frame.resources-scoped-owner-lifecycle-cljs-test
  "The scoped-cache OWNER lifecycle — acquire → hold → release → GC — pinned in
  the MULTI-SCOPE context that is re-frame2's structural leak boundary
  (Spec 016 §The scoped-cache owner lifecycle).

  The single-scope GC mechanics (a fired GC re-check removes an owner-free idle
  entry, skips an owned / in-flight one, re-arms on skip) are pinned by
  `resources_invalidation_gc_cljs_test.cljc`; the sub re-key across a
  mid-session `{:from-db}` input change by `resources_from_db_scope_cljs_test.cljc`.
  THIS suite pins the property those two do not: an owner is a hold on ONE
  RESOLVED scoped key, so two simultaneously-live scopes (admin impersonating
  tenant A while tenant B stays cached) hold INDEPENDENT owners on INDEPENDENT
  entries — holding / releasing / GC-ing one principal's entry can neither pin
  nor collect another's. That non-interference is the differentiator; the
  others put viewer identity in a key BY CONVENTION and nothing enforces it.

  What's under test (Spec 016 §The scoped-cache owner lifecycle, four phases):

    1. ACQUIRE under the resolved scoped key — a `{:from-db}` ensure attaches
       the owner to the db-RESOLVED scope's entry + owner-index, never the
       literal reference, never a global key;
    2. HOLD pins ONLY its own scope — a fired GC re-check keeps an owned entry;
       scope A's held owner does not pin scope B and vice-versa;
    3. RELEASE is scoped — releasing scope A's owner drops it from A's
       entry ONLY; scope B's separately-owned entry + its owner are untouched;
    4. GC-ON-LAST-RELEASE is scoped — dropping A's LAST owner makes A's entry
       GC-eligible and the GC re-check collects it, while B's still-owned
       entry survives the same collection pass; collecting A never touches B.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the :rf.resource/* events
   ;; (ensure / release-owner / gc-fired) + the named-resolver scope plumbing.
   [re-frame.resources]
   [re-frame.resources.registry]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "A default app frame; stub managed-HTTP so ensure never fetches; register a
  named tenant-scope resolver (db-derived viewer identity, the EP-0016 D3
  form) and a tenant-scoped feed resource whose spec :scope is the
  `{:from-db :t/tenant}` reference, plus a GC policy so the entry arms a GC
  re-check timer. `:t/switch-tenant` writes the resolver's app-db input."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "scoped-owner-lifecycle suite default app frame."})
  (rf.registrar/clear-kind! :resource-scope)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :t/tenant
    {:inputs {:tenant [:db [:viewer :tenant-id]]}}
    (fn [{:keys [tenant]} _ctx]
      (when tenant [:rf.scope/tenant {:tenant-id tenant}])))
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/tenant}
     :params-schema [:map [:page :int]]
     :gc-after-ms   60000
     :tags          (fn [_p _v] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-event :t/switch-tenant
    (fn [{:keys [db]} [_ tenant]] {:db (assoc-in db [:viewer :tenant-id] tenant)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
;; `:entries` is keyed on the opaque byte `key-id`; this test
;; reasons about scoped-key VECTORS, so `entries` returns a vector-keyed VIEW
;; (re-keyed from each entry's `:resource/key`) and `owner-index` returns the
;; index with its member byte-ids mapped back to the scoped-key vectors. The
;; re-keying preserves which keys/owners map where.
(defn- entries []
  (into {} (map (fn [[_k-id e]] [(:resource/key e) e]))
        (get-in (runtime-db) (rf.resources.state/entries-path))))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- owner-index []
  (let [rdb (runtime-db)
        es  (get-in rdb (rf.resources.state/entries-path))
        id->sk (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) es)]
    (into {} (map (fn [[owner members]]
                    [owner (into #{} (map #(get id->sk % %)) members)]))
          (get-in rdb (rf.resources.state/owner-index-path)))))

(defn- tenant-key
  "The scoped feed key for tenant `t`, page `page`."
  [t page]
  (rf.resources.state/scoped-resource-key [:rf.scope/tenant {:tenant-id t}] :t/feed {:page page}))

(defn- ensure-feed!
  "Ensure the tenant-scoped feed for tenant `t` under owner `owner`. The ensure
  passes no :scope, so the resource's `{:from-db}` spec policy applies; it
  first writes the resolver's app-db input to `t`, so the named resolver
  yields tenant `t`'s scope at use time —
  exactly how an admin impersonation / tenant switch resolves a concrete
  principal. (Two simultaneously-live tenants are produced by ensuring each
  while its tenant id is the current viewer input.)"
  [t page owner]
  (rf/dispatch-sync [:t/switch-tenant t])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page page}
                                          :owner owner}]))

(defn- settle-loaded!
  "Drive the just-ensured entry at `scoped-key` to :loaded."
  [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

(defn- gc-recheck! [scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]))

;; ===========================================================================
;; Two simultaneously-live scopes (the tenant-switcher shape, in miniature):
;; tenant A (impersonated) and tenant B (still cached) each hold an
;; independent owner on an independent entry.
;; ===========================================================================

(defn- two-live-tenants!
  "Set up tenant A (owner [:app :a 1]) and tenant B (owner [:app :b 1]),
  both loaded, both simultaneously cached. Returns [ka kb]."
  []
  (ensure-feed! "acme" 1 [:app :a 1])
  (settle-loaded! (tenant-key "acme" 1) {:for "acme"})
  (ensure-feed! "globex" 1 [:app :b 1])
  (settle-loaded! (tenant-key "globex" 1) {:for "globex"})
  [(tenant-key "acme" 1) (tenant-key "globex" 1)])

;; ===========================================================================
;; 1. ACQUIRE — the owner attaches under the db-RESOLVED scoped key
;; ===========================================================================

(deftest acquire-two-scopes-are-independent-owners
  (let [[ka kb] (two-live-tenants!)]
    (testing "two simultaneously-live tenants hold INDEPENDENT owners on
              INDEPENDENT entries — neither reachable through the other's key"
      (is (= #{ka kb} (set (keys (entries)))) "both scopes cached at once")
      (is (= {:for "acme"} (:data (entry ka))))
      (is (= {:for "globex"} (:data (entry kb))))
      (is (contains? (:active-owners (entry ka)) [:app :a 1]))
      (is (contains? (:active-owners (entry kb)) [:app :b 1]))
      (is (not (contains? (:active-owners (entry ka)) [:app :b 1]))
          "tenant B's owner is NOT on tenant A's entry")
      (is (not (contains? (:active-owners (entry kb)) [:app :a 1]))
          "tenant A's owner is NOT on tenant B's entry"))))

;; ===========================================================================
;; 3. RELEASE — releasing one scope's owner is scoped to that entry only.
;;    The owner tracks the RESOLVED scope across a mid-session input change:
;;    tenant A's owner was acquired while the viewer input named A, and is
;;    released against A's key after the input switched to B (release names
;;    the owner, not the live resolver output). The HOLD half — an owned entry
;;    survives a GC re-check — is `gc-on-last-release-collects-only-the-
;;    unowned-scope` below.
;; ===========================================================================

(deftest release-is-scoped-to-its-own-entry
  (let [[ka kb] (two-live-tenants!)]
    (testing "Spec 016 §The scoped-cache owner lifecycle (release) — releasing
              tenant A's owner drops it from A's entry ONLY; tenant B's
              separately-owned entry + its owner are untouched"
      (is (= "globex" (get-in (rf/app-db-value :rf/default) [:viewer :tenant-id]))
          "the live viewer input is now tenant B, not the tenant being released")
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :a 1]}])
      (is (empty? (:active-owners (entry ka))) "tenant A entry now owner-free")
      (is (nil? (get (owner-index) [:app :a 1])) "A's owner gone from the index")
      ;; the release alone does NOT remove the entry — it is GC-eligible, not gone
      (is (some? (entry ka)) "released-but-uncollected A entry still present")
      (is (contains? (:active-owners (entry kb)) [:app :b 1])
          "tenant B's owner is UNTOUCHED by tenant A's release")
      (is (contains? (get (owner-index) [:app :b 1]) kb)
          "B's owner still indexed"))))

;; ===========================================================================
;; 4. GC-ON-LAST-RELEASE — scoped collection: A's last-owner drop GCs A
;;    while B's still-owned entry survives the SAME collection pass.
;; ===========================================================================

(deftest gc-on-last-release-collects-only-the-unowned-scope
  (let [[ka kb] (two-live-tenants!)]
    (testing "Spec 016 §The scoped-cache owner lifecycle (GC on last release) —
              dropping tenant A's LAST owner makes A's entry owner-free, and the
              GC re-check collects A; tenant B's still-owned entry survives the
              very same collection pass (the leak boundary holds under GC)"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :a 1]}])
      ;; collect A (now owner-free + idle) — and run the SAME pass over B
      (gc-recheck! ka)
      (gc-recheck! kb)
      (is (nil? (entry ka)) "tenant A's owner-free entry was GC'd")
      (is (some? (entry kb)) "tenant B's still-owned entry SURVIVES — not collected")
      (is (= {:for "globex"} (:data (entry kb)))
          "tenant B's data intact after tenant A's collection")
      (is (= #{kb} (set (keys (entries))))
          "exactly tenant B remains — GC of A touched nothing of B's"))))
