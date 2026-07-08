(ns tenant-switcher.core
  "Shared framework-behavior testbed — the scoped-cache TENANT SWITCHER.

  EP-0013/resources Part-2 leak-boundary scenario 5 (rf2-5e22yc, spun from
  rf2-wwhedk): the multi-SCOPE consumer the rest of the testbed tree lacks.
  Every other multi-tenant-shaped surface in the repo is multi-FRAME (one
  frame per tenant, each isolated). THIS one is multi-SCOPE: a SINGLE frame
  switching the active principal — an admin impersonating
  tenant `acme`, then `globex`, then back — while the scoped-cache keeps each
  tenant's loaded dashboard structurally isolated and SIMULTANEOUSLY live.

  The live demonstration of the guide's claim (docs/core/concepts/server-state.md
  §\"The scoped key: a leak boundary that fails closed\"): the resolved
  scope is IN the cache key, so two tenants' reads of the SAME params land on
  two structurally distinct entries. After switching from `acme` to `globex`,
  the active-scope view reads GLOBEX's dashboard — never `acme`'s cached data
  — and switching back reads `acme`'s entry straight from cache (no refetch).
  Both entries coexist in `:entries` the whole time; the active scope decides
  which one a sub can even address. That is the structural property TanStack
  Query / RTK Query / SWR enforce only by convention (the viewer id is one
  hand-assembled segment of a query key); here it is the *type* of the key.

  Scope resolution is the EP-0016 D3 named-resolver form: a `reg-resource-scope`
  derives the active tenant's `[:rf.scope/tenant {:tenant-id …}]` from app-db
  (`[:viewer :active-tenant]`), and the dashboard resource references it with
  `{:from-db :tenant/scope}`. Switching tenants is one ordinary event that
  rewrites `[:viewer :active-tenant]`; everything downstream (ensure, the
  active-scope sub) re-resolves through the named resolver.

  This is NOT a tutorial — the bodies are minimal and there are no deliberate
  bugs or anti-pattern demos (feedback_testbeds_are_test_surfaces). The
  EXECUTABLE leak-boundary guarantees (cross-user logout leak, wrong-scope
  fail-closed read, scoped-invalidation isolation, lease-lifecycle
  non-interference) are pinned by the CLJS unit suites
  `resources_scope_leak_boundary_cljs_test.cljc` +
  `resources_scoped_lease_lifecycle_cljs_test.cljc`; THIS surface is the live
  demonstrator a tool (Xray / Story / re-frame2-pair-mcp) observes, not the
  regression vehicle."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Resources artefact (day8/re-frame2-resources): requiring at
            ;; app boot triggers its load-time registrations (the
            ;; :rf.resource/* events + subs and the named-resolver scope
            ;; plumbing). Without it, dispatching :rf.resource/ensure or
            ;; subscribing :rf/resource would fail late-bound.
            [re-frame.resources]
            [re-frame.resources.registry]
            ;; Managed-HTTP is the resource runtime's single built-in read
            ;; transport. Requiring it publishes the late-bind feature probe
            ;; the resource lowering consults (`:http/abort-on-actor-destroy`)
            ;; so an HTTP-backed ensure does not fail artefact-missing. The
            ;; testbed then OVERRIDES the `:rf.http/managed` fx with a
            ;; deterministic in-memory stub (below) so no request crosses the
            ;; wire — a testbed IS a test affordance, so this is correct.
            [re-frame.http.managed]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; The two tenants
;; ----------------------------------------------------------------------------

(def tenants
  "The two principals the admin switches between. Each carries a distinct
  display name and the secret payload its dashboard resource loads — the
  payloads are intentionally different so a leak (reading the other tenant's
  data after a switch) would be visible in the DOM. Ordered so the view's
  button row and witness row render deterministically."
  [["acme"   {:label "Acme Corp"  :motto "acme-only secret"}]
   ["globex" {:label "Globex Inc" :motto "globex-only secret"}]])

(def tenant-index
  "Map view of `tenants` for id-keyed lookups."
  (into {} tenants))

(def no-data
  "DOM sentinel rendered when a scope's dashboard has no loaded data — a
  stable string the smoke can assert against (an empty motto would be a
  fragile empty-string assertion, and would also read identically whether
  the entry is genuinely absent or merely empty)."
  "(no data)")

;; ----------------------------------------------------------------------------
;; Named scope resolver (EP-0016 D3) — derive the active tenant's scope once
;; ----------------------------------------------------------------------------
;;
;; The viewer's ACTIVE tenant lives at [:viewer :active-tenant]; the resolver
;; derives the concrete `[:rf.scope/tenant {:tenant-id …}]` from it. Pure —
;; it derives a scope and does nothing else. Declaring `:inputs` lets a tool
;; explain which app-db fact decides a resource identity (and lets the runtime
;; re-resolve only when that input changes). Nil when no tenant is active —
;; fail-closed by contract (never a silent shared/global read).

(rf/reg-resource-scope :tenant/scope
  {:inputs {:tenant [:db [:viewer :active-tenant]]}}
  (fn [{:keys [tenant]} _ctx]
    (when tenant [:rf.scope/tenant {:tenant-id tenant}])))

;; ----------------------------------------------------------------------------
;; The tenant-scoped dashboard resource
;; ----------------------------------------------------------------------------
;;
;; `:scope {:from-db :tenant/scope}` references the named resolver above, so
;; the dashboard's cache key carries the active tenant structurally. Two
;; tenants reading `{:page 1}` therefore land on two distinct entries — the
;; leak boundary, by construction.

(rf/reg-resource :tenant/dashboard
  {:scope         {:from-db :tenant/scope}
   :params-schema [:map [:page :int]]
   :tags          (fn [_p _v] #{[:dashboard]})}
  (fn [{:keys [page]} _ctx]
    {:request {:method :get :url "/dashboard" :params {:page page}}}))

;; ----------------------------------------------------------------------------
;; Deterministic in-memory transport stub
;; ----------------------------------------------------------------------------
;;
;; The resource runtime lowers an ensure into `:rf.http/managed`, stamping the
;; reply addressing it OWNS (`:on-success` / `:on-failure` carrying the
;; verification payload — work-id + resource-key + scope + generation). This
;; testbed overrides that fx with a stub that reads the runtime's own
;; `:on-success` target and replies with a synthetic success through the
;; ordinary internal reply path — so the entry loads exactly as a live read
;; would, but deterministically and with no network. The success `:value` is
;; derived from the resolved scope baked into the resource-key, so each
;; tenant's entry loads ITS OWN distinct payload (a real cross-tenant leak
;; would surface as the wrong motto in the DOM).

(defn- tenant-id-of
  "Extract the tenant-id from a scoped resource key's scope segment
  `[:rf.scope/tenant {:tenant-id …}]`. The scope is the first element of the
  `[scope resource-id params]` key the runtime computed."
  [resource-key]
  (let [[scope] resource-key]
    (when (and (vector? scope) (= :rf.scope/tenant (first scope)))
      (:tenant-id (second scope)))))

(rf/reg-fx :rf.http/managed
  (fn [_frame-ctx {:keys [on-success]}]
    ;; HOT PATH — synthesise the success reply the live transport would have
    ;; dispatched. `on-success` is `[reply-id verification-payload]`; the
    ;; runtime verifies frame + work-id + generation on the payload before it
    ;; writes, so stale/superseded replies are still suppressed correctly.
    (let [[reply-id payload] on-success
          tenant (tenant-id-of (:resource/key payload))
          motto  (get-in tenant-index [tenant :motto])]
      ;; Synthesise the canonical success reply the live transport would have
      ;; appended (rf2-ibksxg — the reply is the uniform envelope; the resources
      ;; runtime reads `:value` off it).
      (rf/dispatch [reply-id payload {:status :ok
                                      :value  {:tenant tenant
                                               :motto  motto}}]))))

;; ----------------------------------------------------------------------------
;; Events
;; ----------------------------------------------------------------------------

(rf/reg-event ::initialise
  (fn [_cofx _ev]
    ;; Boot with `acme` as the active tenant (the admin starts impersonating
    ;; acme). No dashboard is ensured yet — the active-scope view reads idle
    ;; until the operator clicks Load.
    {:db {:viewer {:active-tenant "acme"}}}))

(rf/reg-event ::switch-tenant
  (fn [{:keys [db]} [_ tenant]]
    ;; The impersonation switch — one ordinary app-db write. The named scope
    ;; resolver re-derives `[:rf.scope/tenant {:tenant-id tenant}]` from this
    ;; on the next resolution, so every downstream ensure + active-scope sub
    ;; re-points at the new tenant's entry.
    {:db (assoc-in db [:viewer :active-tenant] tenant)}))

(rf/reg-event ::load-active-dashboard
  (fn [_ctx _ev]
    ;; Ensure the dashboard for the CURRENTLY-ACTIVE tenant. No explicit
    ;; :scope — it is derived from app-db via the resource's {:from-db
    ;; :tenant/scope} policy at use time, so this loads exactly the active
    ;; tenant's entry. An :owner lease keeps the entry pinned for the session.
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :tenant/dashboard
                       :params   {:page 1}
                       :owner    [:lease :tenant-switcher 1]}]]]}))

;; ----------------------------------------------------------------------------
;; Subs
;; ----------------------------------------------------------------------------

(rf/reg-sub :active-tenant (fn [db _] (get-in db [:viewer :active-tenant])))

;; ----------------------------------------------------------------------------
;; Views
;; ----------------------------------------------------------------------------
;;
;; The active-scope panel subscribes `:rf/resource` / `:rf.resource/data`
;; with NO explicit :scope — the sub resolves the active tenant's scope from
;; app-db (the {:from-db} spec policy), so it ALWAYS reads the active tenant's
;; entry and can never address another tenant's. That is the read seam where a
;; leak would be visible; it structurally cannot happen.

;; The ACTIVE-scope dashboard panel. Subscribes with NO explicit :scope — the
;; sub resolves the active tenant's scope from app-db (the {:from-db} spec
;; policy), so it always reads the active tenant's entry and can never address
;; another tenant's. This is the read seam where a leak would be visible.
(reg-view dashboard-panel []
  (let [active (subscribe [:active-tenant])
        q      {:resource :tenant/dashboard :params {:page 1}}
        state  (subscribe [:rf/resource q])
        data   (subscribe [:rf.resource/data q])]
    (fn []
      (let [tenant @active
            label  (get-in tenant-index [tenant :label])
            st     @state
            d      @data]
        [:div {:data-testid "dashboard-panel"
               :style {:border "1px solid #aaa" :padding "0.75em"
                       :margin "0.5em 0" :min-width "22em"}}
         [:h3 "Active dashboard"]
         [:p "tenant=" [:span {:data-testid "active-tenant"} tenant]
          " (" label ")"]
         [:p "status=" [:span {:data-testid "status"} (name (:status st))]]
         [:p "motto=" [:span {:data-testid "motto"} (or (:motto d) no-data)]]]))))

;; The CACHE WITNESS row — one explicit-scope read per tenant, side by side.
;; Each subscribes `:rf/resource` at an EXPLICIT `[:rf.scope/tenant …]`
;; scope, so it reads THAT tenant's entry regardless of who is active. This is
;; the load-bearing evidence for scenario 5: once each tenant's dashboard is
;; loaded, BOTH witnesses read `:loaded` with their OWN distinct motto at the
;; same time — the two entries are simultaneously live in one cache, and each
;; explicit scope addresses exactly its own (never the other's) data.
(reg-view cache-witness [tenant]
  (let [scope [:rf.scope/tenant {:tenant-id tenant}]
        q     {:resource :tenant/dashboard :params {:page 1} :scope scope}
        state (subscribe [:rf/resource q])
        data  (subscribe [:rf.resource/data q])]
    (fn [tenant]
      (let [st @state
            d  @data]
        [:div {:data-testid (str "witness-" tenant)
               :style {:border "1px dashed #bbb" :padding "0.5em"
                       :margin "0.25em" :min-width "12em"}}
         [:strong (get-in tenant-index [tenant :label])]
         [:p {:style {:margin "0.25em 0"}}
          "status="
          [:span {:data-testid (str "witness-status-" tenant)} (name (:status st))]]
         [:p {:style {:margin "0.25em 0"}}
          "motto="
          [:span {:data-testid (str "witness-motto-" tenant)} (or (:motto d) no-data)]]]))))

(reg-view root []
  (let [active (subscribe [:active-tenant])]
    (fn []
      [:div {:data-testid "tenant-switcher" :style {:font-family "sans-serif" :padding "1em"}}
       [:h1 "tenant-switcher testbed"]
       [:p "One frame, two tenant scopes. Switch the active tenant
            (impersonation), load each tenant's dashboard, and watch the
            scoped-cache keep both tenants' entries simultaneously live yet
            structurally isolated in one resource cache — the active-scope view
            only ever reads the active tenant's data."]

       [:div {:style {:display :flex :gap "0.5em" :flex-wrap :wrap :align-items :center}}
        [:span "Impersonate:"]
        ;; `doall` realises the deref-bearing lazy seq inside the reactive
        ;; render (a bare `for` would defer the `@active` deref out of the
        ;; reactive context — the "deref not supported in lazy seq" warning).
        (doall
          (for [[id {:keys [label]}] tenants]
            ^{:key id}
            [:button {:data-testid (str "switch-" id)
                      :disabled    (= id @active)
                      :on-click    #(dispatch [::switch-tenant id])}
             label]))
        [:button {:data-testid "load"
                  :on-click    #(dispatch [::load-active-dashboard])}
         "Load active dashboard"]]

       [dashboard-panel]

       [:h3 "Cache witnesses (explicit per-tenant scope)"]
       [:p {:style {:color "#666"}}
        "Each reads ITS OWN tenant scope explicitly, regardless of who is
         active — evidence both entries coexist in one cache once loaded;
         scope (not params) decides which one a read can address."]
       [:div {:style {:display :flex :flex-wrap :wrap :align-items :flex-start}}
        (doall
          (for [[id _] tenants]
            ^{:key id}
            [cache-witness id]))]])))

;; ----------------------------------------------------------------------------
;; Mount
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from absence.
  ;; `:rf/default` is this testbed's frame, registered explicitly; the
  ;; boot dispatch runs under it and the render is wrapped in a
  ;; `frame-provider` so in-tree dispatch/subscribe resolve to it.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (rf/dispatch-sync [::initialise]))
  (rdc/render react-root [rf/frame-provider {:frame :rf/default} [root]]))
