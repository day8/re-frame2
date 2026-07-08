(ns re-frame.resources-scope-leak-boundary-cljs-test
  "The scoped-cache LEAK BOUNDARY as an executable security guarantee
  (rf2-wwhedk, spun from rf2-s6rviz; guide §\"Scope — the leak boundary other
  libraries do not have\").

  re-frame2's differentiating proposition: cache SCOPE is a first-class,
  fail-closed isolation axis. The benchmarked class (TanStack Query, RTK
  Query, SWR, shipclojure/re-frame-query) puts viewer identity inside a query
  key / tag BY CONVENTION — nothing structurally stops a forgotten key segment
  from silently sharing one principal's cache with the next. re-frame2's
  boundary is STRUCTURAL: the resolved scope is IN the cache key, a sub
  resolves that scope (or fails closed), logout `clear-scope` removes a
  principal's entries causally, and a scoped invalidation reaches exactly the
  resolved scope.

  The lease-lifecycle non-interference property (a held lease on scope A
  neither pins nor collects scope B's entry) is pinned by
  `resources_scoped_lease_lifecycle_cljs_test.cljc`; the named-resolver
  resolution mechanics (event / route / sub / clear-scope / mismatch warning)
  by `resources_from_db_scope_cljs_test.cljc`; the per-target scoped
  invalidation engine by `resources_invalidation_descriptors_cljs_test.cljc`.

  What THIS suite pins — the same boundary expressed as the READ-PATH security
  guarantee those suites do not assert: that NO principal's live subscription
  can ever observe ANOTHER principal's cached data, across logout, a wrong
  scope, and a multi-scope invalidation. It asserts at the live `rf/subscribe`
  read (the seam a real leak would be visible at), not only at the durable
  entry. The bead's Part-2 leak-boundary scenarios 1/2/3:

    1. LOGOUT clear-scope — after principal A logs out and the session scope is
       cleared, principal B's next session structurally cannot read A's
       entries: B's live sub reads B's (un-ensured) idle empty-state, never
       A's stale-cached data (the cross-user leak test).
    2. WRONG-but-valid scope — a sub that resolves a DIFFERENT valid scope than
       the owning ensure reads ITS OWN (empty) entry, never a silent shared
       read of the other principal's data; under `:rf.scope/from-caller` the
       mismatch is additionally DIAGNOSABLE (a dev `:rf.warning/resource-sub-
       scope-mismatch`), and a nil-resolving reference FAILS CLOSED loudly.
    3. MIXED-scope invalidation — a clear-scope / scoped invalidation reaches
       EXACTLY the resolved scope's entries and no other principal's, so an
       invalidation can never cross the principal boundary without the
       explicit, audited cross-scope escape.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$` regex.
  Cross-host so the boundary holds identically server- and client-side."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/* events
   ;; + subs and the named-resolver scope plumbing.
   [re-frame.resources]
   [re-frame.resources.registry]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs :as r-subs]
   [re-frame.resources.test-support]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as registrar]
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.test-support :as core-test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "A default app frame; stub managed-HTTP so ensure never fetches; register a
  named tenant-scope resolver (db-derived viewer identity, the EP-0016 D3
  form) plus a tenant-scoped feed resource whose spec :scope is the
  `{:from-db :t/tenant}` reference. `:t/login` writes the resolver's app-db
  input (the viewer's tenant id); `:t/logout` removes it."
  []
  (rf/reg-frame :rf/default {:url-bound? true
                             :doc "scope-leak-boundary suite default app frame."})
  (registrar/clear-kind! :resource-scope)
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :t/tenant
    {:inputs {:tenant [:db [:viewer :tenant-id]]}}
    (fn [{:keys [tenant]} _ctx]
      (when tenant [:rf.scope/tenant {:tenant-id tenant}])))
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/tenant}
     :params-schema [:map [:page :int]]
     :tags          (fn [_p _v] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-event :t/login  (fn [{:keys [db]} [_ tenant]] {:db (assoc-in db [:viewer :tenant-id] tenant)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _] {:db (update db :viewer dissoc :tenant-id)})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
;; rf2-9e0tyq — `:entries` is keyed on the byte `key-id`; return a vector-keyed
;; VIEW (re-keyed from each entry's `:resource/key`) so the scope-isolation
;; assertions speak scoped-key vectors. Semantics unchanged.
(defn- entries []
  (into {} (map (fn [[_k-id e]] [(:resource/key e) e]))
        (get-in (runtime-db) (state/entries-path))))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- tenant-key
  "The scoped feed key for tenant `t`, page `page`."
  [t page]
  (state/scoped-resource-key [:rf.scope/tenant {:tenant-id t}] :t/feed {:page page}))

(defn- ensure-feed!
  "Ensure + load the tenant-scoped feed for tenant `t`, page `page`, under
  owner `owner`, with `data`. First writes the resolver's app-db input to
  `t` (a tenant switch / impersonation) so the named resolver yields tenant
  `t`'s scope at use time, then drives the entry to :loaded."
  [t page owner data]
  (rf/dispatch-sync [:t/login t])
  (rf/dispatch-sync [:rf.resource/ensure {:resource :t/feed :params {:page page}
                                          :owner owner}])
  (let [e (entry (tenant-key t page))]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key (tenant-key t page)
                        :work/id      (:current-work e)
                        :generation   (:generation e)
                        :data         data}])))

;; ===========================================================================
;; 1. LOGOUT clear-scope — the cross-user leak test.
;;    After tenant A logs out and its session scope is cleared, tenant B's
;;    next session structurally cannot read A's entries.
;; ===========================================================================

(deftest logout-clear-scope-removes-the-prior-principals-entries
  ;; tenant "acme" logs in, ensures + loads a feed under its tenant scope
  (ensure-feed! "acme" 1 [:lease :acme 1] {:secret "acme-only"})
  (is (some? (entry (tenant-key "acme" 1))) "acme's entry loaded under its tenant scope")
  (testing "logout resolves acme's concrete scope from the pre-transition
            coeffect db and clear-scope removes every acme entry + owner —
            the causal logout boundary (Spec 016 §clear-scope is causal)"
    ;; resolve the concrete scope from the still-current db, THEN clear it,
    ;; THEN drop the identity — the canonical logout ordering.
    (let [old-scope (rf/resolve-resource-scope (rf/app-db-value :rf/default) :t/tenant)]
      (is (= [:rf.scope/tenant {:tenant-id "acme"}] old-scope)
          "the pre-logout db resolves acme's concrete tenant scope")
      (rf/dispatch-sync [:rf.resource/clear-scope {:scope old-scope :cause :logout}])
      (rf/dispatch-sync [:t/logout]))
    (is (nil? (entry (tenant-key "acme" 1))) "acme's entry was removed by clear-scope")
    (is (empty? (entries)) "no entry survives acme's scope clear")))

(deftest next-principal-sub-cannot-read-the-logged-out-principals-data
  ;; THE CROSS-USER LEAK TEST, at the live read. tenant "acme" loads a feed,
  ;; logs out (scope cleared), then tenant "globex" logs in on the SAME frame
  ;; and a live sub (scope derived from app-db via the {:from-db} spec policy)
  ;; reads what globex sees.
  (ensure-feed! "acme" 1 [:lease :acme 1] {:secret "acme-only"})
  (let [old-scope (rf/resolve-resource-scope (rf/app-db-value :rf/default) :t/tenant)]
    (rf/dispatch-sync [:rf.resource/clear-scope {:scope old-scope :cause :logout}])
    (rf/dispatch-sync [:t/logout]))
  ;; globex's session now begins on the same frame
  (rf/dispatch-sync [:t/login "globex"])
  (let [q   {:resource :t/feed :params {:page 1}}   ;; no :scope — derived from app-db
        st  (rf/subscribe [:rf.resource/state q])
        dat (rf/subscribe [:rf.resource/data q])]
    (testing "globex's live sub resolves GLOBEX's tenant scope and reads its
              own (un-ensured) idle empty-state — NEVER acme's cleared data.
              This is the structural property TanStack/RTK/SWR enforce only by
              convention: the resolved scope is IN the key, so globex's read
              cannot address acme's entry at all"
      (is (= :idle (:status @st)) "globex sees idle (no entry under globex's scope)")
      (is (false? (:has-data? @st)) "no data for globex's un-ensured key")
      (is (nil? @dat) "globex's :data is nil — never acme's {:secret \"acme-only\"}")
      (is (nil? (entry (tenant-key "acme" 1))) "acme's entry is gone from the cache")
      (is (nil? (entry (tenant-key "globex" 1)))
          "globex has no entry yet — the leak boundary is not a stale-read"))))

(deftest clear-scope-isolates-the-cleared-principal-other-scopes-survive
  ;; two tenants simultaneously cached; clearing ONE leaves the other intact —
  ;; logout of acme must not collateral-clear globex.
  (ensure-feed! "acme"   1 [:lease :acme 1]   {:for "acme"})
  (ensure-feed! "globex" 1 [:lease :globex 1] {:for "globex"})
  (is (= #{(tenant-key "acme" 1) (tenant-key "globex" 1)} (set (keys (entries))))
      "both tenants cached at once")
  (testing "clearing acme's scope removes ONLY acme's entries — globex's
            separately-scoped entry + data survive the clear (the leak
            boundary holds under clear-scope)"
    (rf/dispatch-sync [:rf.resource/clear-scope
                       {:scope [:rf.scope/tenant {:tenant-id "acme"}] :cause :logout}])
    (is (nil? (entry (tenant-key "acme" 1))) "acme cleared")
    (is (some? (entry (tenant-key "globex" 1))) "globex's entry survives acme's clear")
    (is (= {:for "globex"} (:data (entry (tenant-key "globex" 1))))
        "globex's data intact")
    (is (= #{(tenant-key "globex" 1)} (set (keys (entries))))
        "exactly globex remains — acme's clear touched nothing of globex's")))

;; ===========================================================================
;; 2. WRONG-but-valid scope — a sub at the wrong scope reads its OWN entry,
;;    never a silent shared read; a nil-resolving reference fails closed.
;; ===========================================================================

(deftest wrong-scope-sub-reads-its-own-empty-entry-never-the-other-principals
  ;; acme has a loaded feed; a view subscribes with an EXPLICIT — but WRONG —
  ;; tenant scope (globex's). The wrong-scope read resolves globex's key, which
  ;; has no entry: it reads idle, NEVER acme's data.
  (ensure-feed! "acme" 1 [:lease :acme 1] {:secret "acme-only"})
  (let [wrong-q {:resource :t/feed :params {:page 1}
                 :scope [:rf.scope/tenant {:tenant-id "globex"}]}
        st      (rf/subscribe [:rf.resource/state wrong-q])
        dat     (rf/subscribe [:rf.resource/data wrong-q])]
    (testing "a wrong-but-valid scope addresses ITS OWN (empty) key — the
              resolved scope is part of the key, so the wrong-scope sub
              cannot reach acme's entry. It reads idle, never acme's data"
      (is (= :idle (:status @st)) "the wrong scope's key has no entry — idle")
      (is (nil? @dat) "never a silent shared read of acme's {:secret \"acme-only\"}")
      (is (some? (entry (tenant-key "acme" 1)))
          "acme's entry is untouched — the wrong-scope read did not address it"))))

(deftest from-caller-wrong-scope-is-diagnosable-not-silent
  ;; The from-caller footgun: a route/event ensures under scope A; a view
  ;; subscribes under a DIFFERENT scope. The sub reads :idle forever — correct
  ;; (fail-closed: never a wrong-principal read) but, under :rf.scope/from-
  ;; caller, additionally diagnosable via the dev scope-mismatch warning.
  (rf/reg-resource :t/notes
    {:scope         :rf.scope/from-caller
     :params-schema [:map]}
    (fn [_p _ctx] {:request {:method :get :url "/notes"}}))
  ;; ensure + load acme's notes under acme's explicit scope (an ACTIVE owner)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :t/notes :params {}
                      :scope [:rf.scope/tenant {:tenant-id "acme"}]
                      :owner [:lease :n 1]}])
  (let [ka (state/scoped-resource-key [:rf.scope/tenant {:tenant-id "acme"}] :t/notes {})
        e  (entry ka)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key ka :work/id (:current-work e)
                        :generation (:generation e) :data {:secret "acme-notes"}}]))
  (testing "a from-caller sub at a DIFFERENT (wrong) scope than the active
            ensure reads :idle (fail-closed) AND emits the dev-only
            :rf.warning/resource-sub-scope-mismatch diagnostic — the silent
            permanent-skeleton footgun is surfaced, never a wrong-principal read"
    (let [seen (atom [])
          k    ::mismatch-recorder
          wrong-q {:resource :t/notes :params {}
                   :scope [:rf.scope/tenant {:tenant-id "globex"}]}]
      (r-subs/reset-scope-mismatch-warnings!)
      (trace-tooling/register-listener!
        k (fn [ev] (when (= :rf.warning/resource-sub-scope-mismatch (:operation ev))
                     (swap! seen conj ev))))
      (try
        (let [st (rf/subscribe [:rf.resource/state wrong-q])]
          (is (= :idle (:status @st)) "wrong-scope read is idle (fail-closed)")
          (is (nil? (:data @st)) "never acme's notes"))
        (finally (trace-tooling/unregister-listener! k)))
      ;; the dev build fires the mismatch warning; production elides it. We
      ;; assert it fired when present, and that the read was fail-closed
      ;; regardless (the security property does not depend on the warning).
      (when (seq @seen)
        (let [w (first @seen)]
          (is (= :rf.warning/resource-sub-scope-mismatch (:operation w)))
          (is (= [:rf.scope/tenant {:tenant-id "globex"}] (:sub-scope (:tags w)))
              "names the wrong subscribed scope")
          (is (= [:rf.scope/tenant {:tenant-id "acme"}] (:active-scope (:tags w)))
              "names the active scope the ensure used"))))))

(deftest nil-resolving-sub-scope-fails-closed-loudly
  ;; A {:from-db} sub whose resolver yields nil (no logged-in viewer) raises
  ;; the sub-side fail-closed diagnostic — never a silent :idle / global /
  ;; wrong-entry read. Asserted at the resolution boundary `resolve-scoped-key`
  ;; (a sub-body throw is otherwise routed to the runtime error path).
  (testing "a {:from-db} spec-policy sub resolving nil raises
            :rf.error/resource-sub-unresolved-scope — fail-closed, never a
            silent shared read"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-sub-unresolved-scope"
          (r-subs/resolve-scoped-key {:resource :t/feed :params {:page 1}} {})))))

;; ===========================================================================
;; 3. MIXED-scope invalidation — a scoped invalidation reaches EXACTLY the
;;    resolved scope, never another principal's, without the audited
;;    cross-scope escape.
;; ===========================================================================

(deftest scoped-invalidate-tags-reaches-only-the-named-principal
  ;; two tenants hold the SAME-tagged feed entry under their own scopes. A
  ;; scoped invalidation of [:feed] in acme's scope marks ONLY acme stale —
  ;; globex's same-tagged entry is untouched. Scope is part of the
  ;; invalidation target, so a tag-invalidation cannot cross the principal
  ;; boundary (Spec 016 §Scoped invalidation is the default).
  ;;
  ;; Both entries are made OWNERLESS after load (release the lease) so the
  ;; durable :invalidated-at stale marker is observable rather than being
  ;; cleared by the active-owner refetch the invalidation would otherwise start
  ;; (Spec 016 §Invalidation 3/4 — owned entries refetch, ownerless go stale).
  (ensure-feed! "acme"   1 [:lease :acme 1]   {:for "acme"})
  (ensure-feed! "globex" 1 [:lease :globex 1] {:for "globex"})
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :acme 1]}])
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :globex 1]}])
  (testing "a scoped :rf.resource/invalidate-tags reaches exactly the resolved
            scope's entries (acme), never another principal's (globex)"
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope [:rf.scope/tenant {:tenant-id "acme"}]
                        :tags  #{[:feed]}
                        :cause [:test :scoped-invalidate]}])
    (is (some? (:invalidated-at (entry (tenant-key "acme" 1))))
        "acme's feed was invalidated (its scope was the target)")
    (is (nil? (:invalidated-at (entry (tenant-key "globex" 1))))
        "globex's same-tagged feed was NOT touched — invalidation is scoped, not a cross-user blast")))

(deftest clear-scope-and-invalidation-compose-without-crossing-principals
  ;; the leak boundary holds across BOTH causal scope operations in one flow:
  ;; clear acme on logout, then invalidate globex's feed — each reaches only
  ;; its own principal.
  (ensure-feed! "acme"   1 [:lease :acme 1]   {:for "acme"})
  (ensure-feed! "globex" 1 [:lease :globex 1] {:for "globex"})
  ;; globex ownerless so its :invalidated-at stale marker is observable (an
  ;; active-owner entry would refetch and clear the marker — §Invalidation 3/4).
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :globex 1]}])
  (testing "clearing acme then invalidating globex each stays within its own
            principal — neither operation crosses the boundary"
    (rf/dispatch-sync [:rf.resource/clear-scope
                       {:scope [:rf.scope/tenant {:tenant-id "acme"}] :cause :logout}])
    (rf/dispatch-sync [:rf.resource/invalidate-tags
                       {:scope [:rf.scope/tenant {:tenant-id "globex"}]
                        :tags  #{[:feed]}
                        :cause [:test :scoped-invalidate]}])
    (is (nil? (entry (tenant-key "acme" 1))) "acme was cleared (logout)")
    (is (some? (entry (tenant-key "globex" 1))) "globex survives acme's clear")
    (is (some? (:invalidated-at (entry (tenant-key "globex" 1))))
        "globex's feed was invalidated in its own scope")
    (is (= #{(tenant-key "globex" 1)} (set (keys (entries))))
        "exactly globex remains, marked stale — the two ops never crossed principals")))
