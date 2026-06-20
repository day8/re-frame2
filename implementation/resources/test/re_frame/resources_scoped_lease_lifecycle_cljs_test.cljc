(ns re-frame.resources-scoped-lease-lifecycle-cljs-test
  "The scoped-cache LEASE lifecycle — acquire → hold → release → GC — pinned in
  the MULTI-SCOPE context that is re-frame2's structural leak boundary
  (rf2-s6rviz, Spec 016 §The scoped-cache lease lifecycle).

  The single-scope GC mechanics (a fired GC re-check removes an owner-free idle
  entry, skips an owned / in-flight one, re-arms on skip) are pinned by
  `resources_invalidation_gc_cljs_test.cljc`; the owner-lease handoff across a
  mid-session `{:from-db}` re-key by `resources_from_db_scope_cljs_test.cljc`.
  THIS suite pins the property those two do not: a lease is a lease on ONE
  RESOLVED scoped key, so two simultaneously-live scopes (admin impersonating
  tenant A while tenant B stays cached) hold INDEPENDENT leases on INDEPENDENT
  entries — holding / releasing / GC-ing one principal's entry can neither pin
  nor collect another's. That non-interference is the differentiator; the
  others put viewer identity in a key BY CONVENTION and nothing enforces it.

  What's under test (Spec 016 §The scoped-cache lease lifecycle, four phases):

    1. ACQUIRE under the resolved scoped key — a `{:from-db}` ensure attaches
       the owner to the db-RESOLVED scope's entry + owner-index, never the
       literal reference, never a global key;
    2. HOLD pins ONLY its own scope — a fired GC re-check keeps an owned entry;
       scope A's held lease does not pin scope B and vice-versa;
    3. RELEASE is scoped — releasing scope A's lease drops the owner from A's
       entry ONLY; scope B's separately-leased entry + its lease are untouched;
    4. GC-ON-LAST-RELEASE is scoped — dropping A's LAST lease makes A's entry
       GC-eligible and the GC re-check collects it, while B's still-leased
       entry survives the same collection pass; collecting A never touches B.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* events
   ;; (ensure / release-owner / gc-fired) + the named-resolver scope plumbing.
   [re-frame.resources]
   [re-frame.resources.registry]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as registrar]
   [re-frame.test-support :as core-test-support]
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
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "scoped-lease-lifecycle suite default app frame."})
  (registrar/clear-kind! :resource-scope)
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :t/tenant
    {:inputs  {:tenant [:db [:viewer :tenant-id]]}
     :resolve (fn [{:keys [tenant]} _ctx]
                (when tenant [:rf.scope/tenant {:tenant-id tenant}]))})
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
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (rf/runtime-db-value :rf/default))
;; rf2-9e0tyq — `:entries` is keyed on the opaque byte `key-id`; this test
;; reasons about scoped-key VECTORS, so `entries` returns a vector-keyed VIEW
;; (re-keyed from each entry's `:resource/key`) and `owner-index` returns the
;; index with its member byte-ids mapped back to the scoped-key vectors. The
;; semantics (which keys/owners map where) are unchanged.
(defn- entries []
  (into {} (map (fn [[_k-id e]] [(:resource/key e) e]))
        (get-in (runtime-db) (state/entries-path))))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
(defn- owner-index []
  (let [rdb (runtime-db)
        es  (get-in rdb (state/entries-path))
        id->sk (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) es)]
    (into {} (map (fn [[owner members]]
                    [owner (into #{} (map #(get id->sk % %)) members)]))
          (get-in rdb (state/owner-index-path)))))

(defn- tenant-key
  "The scoped feed key for tenant `t`, page `page`."
  [t page]
  (state/scoped-resource-key [:rf.scope/tenant {:tenant-id t}] :t/feed {:page page}))

(defn- ensure-feed!
  "Ensure the tenant-scoped feed for tenant `t` under owner `owner`. Uses an
  explicit `{:from-db}` payload :scope BUT first writes the resolver's app-db
  input to `t`, so the named resolver yields tenant `t`'s scope at use time —
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
;; independent lease on an independent entry.
;; ===========================================================================

(defn- two-live-tenants!
  "Set up tenant A (lease [:lease :a 1]) and tenant B (lease [:lease :b 1]),
  both loaded, both simultaneously cached. Returns [ka kb]."
  []
  (ensure-feed! "acme" 1 [:lease :a 1])
  (settle-loaded! (tenant-key "acme" 1) {:for "acme"})
  (ensure-feed! "globex" 1 [:lease :b 1])
  (settle-loaded! (tenant-key "globex" 1) {:for "globex"})
  [(tenant-key "acme" 1) (tenant-key "globex" 1)])

;; ===========================================================================
;; 1. ACQUIRE — the lease attaches under the db-RESOLVED scoped key
;; ===========================================================================

(deftest acquire-attaches-lease-under-resolved-scope
  (ensure-feed! "acme" 1 [:lease :a 1])
  (testing "Spec 016 §The scoped-cache lease lifecycle (acquire) — a
            {:from-db} ensure resolves the tenant scope from app-db and
            attaches the owner to THAT resolved entry, never a global key"
    (let [ka (tenant-key "acme" 1)]
      (is (some? (entry ka)) "entry lives under the db-derived tenant scope")
      (is (= #{ka} (set (keys (entries))))
          "exactly one entry, under the resolved scope — never a global key")
      (is (contains? (:active-owners (entry ka)) [:lease :a 1])
          "the owner lease is recorded on the resolved entry")
      (is (contains? (get (owner-index) [:lease :a 1]) ka)
          "the owner-index maps the lease to the resolved scoped key"))))

(deftest acquire-two-scopes-are-independent-leases
  (let [[ka kb] (two-live-tenants!)]
    (testing "two simultaneously-live tenants hold INDEPENDENT leases on
              INDEPENDENT entries — neither reachable through the other's key"
      (is (= #{ka kb} (set (keys (entries)))) "both scopes cached at once")
      (is (= {:for "acme"} (:data (entry ka))))
      (is (= {:for "globex"} (:data (entry kb))))
      (is (contains? (:active-owners (entry ka)) [:lease :a 1]))
      (is (contains? (:active-owners (entry kb)) [:lease :b 1]))
      (is (not (contains? (:active-owners (entry ka)) [:lease :b 1]))
          "tenant B's lease is NOT on tenant A's entry")
      (is (not (contains? (:active-owners (entry kb)) [:lease :a 1]))
          "tenant A's lease is NOT on tenant B's entry"))))

;; ===========================================================================
;; 2. HOLD — a held lease pins ONLY its own scope's entry
;; ===========================================================================

(deftest hold-pins-only-its-own-scope
  (let [[ka kb] (two-live-tenants!)]
    (testing "Spec 016 §The scoped-cache lease lifecycle (hold) — a GC
              re-check finds BOTH entries owned and collects neither"
      (gc-recheck! ka)
      (gc-recheck! kb)
      (is (some? (entry ka)) "owned tenant A entry kept (GC skipped)")
      (is (some? (entry kb)) "owned tenant B entry kept (GC skipped)"))))

;; ===========================================================================
;; 3. RELEASE — releasing one scope's lease is scoped to that entry only
;; ===========================================================================

(deftest release-is-scoped-to-its-own-entry
  (let [[ka kb] (two-live-tenants!)]
    (testing "Spec 016 §The scoped-cache lease lifecycle (release) — releasing
              tenant A's lease drops the owner from A's entry ONLY; tenant B's
              separately-leased entry + its lease are untouched"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
      (is (empty? (:active-owners (entry ka))) "tenant A entry now owner-free")
      (is (nil? (get (owner-index) [:lease :a 1])) "A's lease gone from the index")
      ;; the release alone does NOT remove the entry — it is GC-eligible, not gone
      (is (some? (entry ka)) "released-but-uncollected A entry still present")
      (is (contains? (:active-owners (entry kb)) [:lease :b 1])
          "tenant B's lease is UNTOUCHED by tenant A's release")
      (is (contains? (get (owner-index) [:lease :b 1]) kb)
          "B's lease still indexed"))))

;; ===========================================================================
;; 4. GC-ON-LAST-RELEASE — scoped collection: A's last-lease drop GCs A
;;    while B's still-leased entry survives the SAME collection pass.
;; ===========================================================================

(deftest gc-on-last-release-collects-only-the-unleased-scope
  (let [[ka kb] (two-live-tenants!)]
    (testing "Spec 016 §The scoped-cache lease lifecycle (GC on last release) —
              dropping tenant A's LAST lease makes A's entry owner-free, and the
              GC re-check collects A; tenant B's still-leased entry survives the
              very same collection pass (the leak boundary holds under GC)"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
      ;; collect A (now owner-free + idle) — and run the SAME pass over B
      (gc-recheck! ka)
      (gc-recheck! kb)
      (is (nil? (entry ka)) "tenant A's owner-free entry was GC'd")
      (is (some? (entry kb)) "tenant B's still-leased entry SURVIVES — not collected")
      (is (= {:for "globex"} (:data (entry kb)))
          "tenant B's data intact after tenant A's collection")
      (is (= #{kb} (set (keys (entries))))
          "exactly tenant B remains — GC of A touched nothing of B's"))))

(deftest gc-skipped-while-leased-then-collected-after-last-release
  (let [ka (tenant-key "acme" 1)]
    (ensure-feed! "acme" 1 [:lease :a 1])
    (settle-loaded! ka {:for "acme"})
    (testing "Spec 016 §The scoped-cache lease lifecycle — while the lease is
              HELD, a GC re-check keeps the entry"
      (gc-recheck! ka)
      (is (some? (entry ka)) "leased entry kept"))
    (testing "dropping the LAST lease makes the entry owner-free; the next GC
              re-check collects it deterministically (acquire→hold→release→GC)"
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
      (is (empty? (:active-owners (entry ka))) "owner-free after last release")
      (is (some? (entry ka)) "GC-eligible, but not yet collected by the release")
      (gc-recheck! ka)
      (is (nil? (entry ka)) "the GC re-check collected the owner-free idle entry")
      (is (empty? (entries)) "no entry survives"))))

;; ===========================================================================
;; 5. The lease tracks the RESOLVED scope across a mid-session input change —
;;    a lease acquired while impersonating tenant A names tenant A's key, and
;;    is released against tenant A's key even after the viewer input switched
;;    to tenant B (release names the owner, not the live resolver output).
;; ===========================================================================

(deftest lease-release-names-the-resolved-key-not-the-live-resolver
  (let [ka (tenant-key "acme" 1)
        kb (tenant-key "globex" 1)]
    (ensure-feed! "acme" 1 [:lease :a 1])
    (settle-loaded! ka {:for "acme"})
    ;; switch the viewer input to tenant B and ensure B under its own lease
    (ensure-feed! "globex" 1 [:lease :b 1])
    (settle-loaded! kb {:for "globex"})
    (testing "after the viewer input switched to tenant B, releasing tenant A's
              lease still drops it from tenant A's entry (the owner names the
              resolved-at-acquire scoped key; release is owner-keyed, not a
              re-resolution against the now-current viewer)"
      (is (= "globex" (get-in (rf/app-db-value :rf/default) [:viewer :tenant-id]))
          "the live viewer input is now tenant B")
      (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :a 1]}])
      (is (empty? (:active-owners (entry ka))) "tenant A's lease released from A")
      (is (contains? (:active-owners (entry kb)) [:lease :b 1])
          "tenant B's lease (current viewer) untouched")
      (gc-recheck! ka)
      (is (nil? (entry ka)) "tenant A collected on its last-lease drop")
      (is (some? (entry kb)) "tenant B still leased + cached"))))
