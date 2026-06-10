(ns resources.core
  "Worked example for [Spec 016 Resources](../../../spec/016-Resources.md)
   (the EP-0003 read-resource MVP). A small server-state app — an articles
   list and an article detail — demonstrating the LANDED resource surface
   composed as proper re-frame2: app-db + events + subs, views passive,
   fetches caused.

   It teaches, in one cohesive app, four causal patterns:

   - ROUTE-DRIVEN page load — a route declares `:resources`; route entry
     ensures them under a `[:route route-id nav-token]` owner.
   - EVENT-DRIVEN ensure     — an event ensures a resource under an
     app-minted `[:lease …]` owner with a matching release path.
   - MANUAL refresh          — a button dispatches `:rf.resource/refetch`
     with a `:cause` (NOT an owner — a refresh keeps nothing alive).
   - MACHINE-OWNED resource  — a machine action ensures under a
     `[:machine machine-id instance-id]` owner (released on actor destroy).

   Views read everything through the PASSIVE `[:rf.resource/*]` subs; no
   view fetches. Scope is the fail-closed leak boundary — this example
   reads public, non-user-specific data, so each resource declares the
   explicit, auditable `:rf.scope/global` claim (a user/tenant-scoped read
   would carry a scope resolver instead).

   SLICE STATUS (rf2-p10npe). Resources is a POST-V1 optional artefact and
   ships here at its SKELETON slice: `reg-resource`, the `:rf.resource/*`
   passive subs, and route `:resources` metadata are real and load cleanly,
   so this example COMPILES and registers as shown. The causal event
   bodies (`:rf.resource/ensure` / `:rf.resource/refetch`) are registered
   but raise `:rf.error/resource-not-implemented` until the runtime slices
   land (rf2-afpdkn / rf2-pbxj48 / …) — so the SHAPE here is the canonical
   one a finished app uses, and the live fetch lights up when those slices
   ship. The example tree is test-free (rf2-8cevm); resource-contract
   coverage lives in `implementation/resources/test/` and the conformance
   fixtures."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Managed HTTP ships in day8/re-frame2-http — the single
            ;; built-in resource transport (Spec 016 §Transport). Loading
            ;; the ns registers the `:rf.http/managed` fx the resource
            ;; runtime lowers each ensure onto.
            [re-frame.http-managed]
            ;; Resources ship in day8/re-frame2-resources. Requiring the
            ;; ns at app boot wires the late-bind hooks + registrations;
            ;; without it, `rf/reg-resource` below throws
            ;; :rf.error/resources-artefact-missing.
            [re-frame.resources]
            ;; Routing ships in day8/re-frame2-routing. The resources
            ;; artefact LATE-BINDS its `:resources` route-metadata
            ;; extension into routing, so loading both is what makes a
            ;; route's `:resources` key accepted (Spec 016 §Route
            ;; integration).
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; RESOURCES — the registry of named, cached server-state reads
;; ============================================================================
;;
;; A resource is identity + scope + a request. `:params-schema`, `:scope`,
;; and `:request` are REQUIRED (Spec 016 §Resource registration spec). The
;; `:scope :rf.scope/global` here is the explicit, AUDITABLE claim that this
;; read is the same for every user/tenant/locale — there is NO implicit
;; default; a missing scope policy is a loud
;; :rf.error/resource-missing-scope-policy at registration.

(rf/reg-resource :articles/list
  {:doc            "The recent-articles list (public, same for everyone)."
   :params-schema  [:map]
   :scope          :rf.scope/global
   :request        (fn [_params _ctx]
                     {:request {:method :get :url "/api/articles"}
                      :decode  :json})
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tags let a write invalidate this list by tag
   ;; (`:rf.resource/invalidate-tags {:tags #{[:article-list]}}`).
   :tags           (fn [_params _data] #{[:article-list]})})

(rf/reg-resource :article/by-slug
  {:doc            "Article detail by slug (public)."
   :params-schema  [:map [:slug :string]]
   :scope          :rf.scope/global
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}
                      :decode  :json})
   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)
   ;; Tag BOTH the per-article identity and the list identity so a save
   ;; can invalidate the detail and the list relationship together.
   :tags           (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})})

;; ============================================================================
;; ROUTES — route entry CAUSES the page's resources to load
;; ============================================================================
;;
;; The `:resources` route metadata (Spec 016 §Route integration) is the
;; declarative, machine-readable answer to "what server-state does this
;; page need?" On route entry the runtime marks each resource active with
;; owner `[:route route-id nav-token]` and ensures it with cause
;; `[:route-entry route-id nav-token]`; on route leave it releases the
;; owner by token and suppresses any stale reply by generation. The view
;; never fetches — it only reads.

(rf/reg-route :resources.app/home
  {:doc  "Landing page."
   :path "/"})

(rf/reg-route :resources.app/articles
  {:doc   "Articles list — loads the :articles/list resource on entry."
   :path  "/articles"
   :resources
   [{:resource  :articles/list
     :params    (fn [_route] {})
     :blocking? true}]})

(rf/reg-route :resources.app/article-detail
  {:doc    "Article detail — loads :article/by-slug for the URL slug."
   :path   "/articles/:slug"
   :params [:map [:slug :string]]
   :resources
   [{:resource  :article/by-slug
     ;; Route params → resource params: the URL slug identifies the read.
     :params    (fn [route] {:slug (get-in route [:params :slug])})
     :blocking? true}]})

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."
   :path "/_404"})

;; ============================================================================
;; EVENT-DRIVEN ENSURE + MANUAL REFRESH
;; ============================================================================
;;
;; Not every fetch is route-driven. An event can CAUSE an ensure too — here
;; under an app-minted `[:lease …]` owner. App leases are app-authoritative
;; (Spec 016 §Release authority is per owner kind): the event that mints a
;; lease MUST have a matching `:rf.resource/release-owner` path, or the
;; lease pins the entry alive (Xray lints an orphaned lease).

(rf/reg-event-fx :resources.app/preview-opened
  {:doc "Open a lightweight article preview from the list — ensure the
         detail under a releaseable lease."}
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :article/by-slug
                       :params   {:slug slug}
                       :owner    [:lease :resources.app/preview slug]
                       :cause    [:event :resources.app/preview-opened]}]]]}))

(rf/reg-event-fx :resources.app/preview-closed
  {:doc "Close the preview — release the lease so the entry can GC."}
  (fn [_ [_ slug]]
    {:fx [[:dispatch [:rf.resource/release-owner
                      {:owner [:lease :resources.app/preview slug]}]]]}))

;; A MANUAL refresh is a CAUSE, not an owner (Spec 016 §Causes explain why
;; work happened): clicking "Refresh" wants fresh data but does NOT intend
;; to keep the resource alive — that's the route's job. So it dispatches
;; `:rf.resource/refetch` with `:cause` and omits `:owner`.
(rf/reg-event-fx :resources.app/refresh-articles
  {:doc "Manual refresh of the articles list."}
  (fn [_ _]
    {:fx [[:dispatch [:rf.resource/refetch
                      {:resource :articles/list
                       :params   {}
                       :cause    [:manual :resources.app/refresh-articles]}]]]}))

;; ============================================================================
;; MACHINE-OWNED RESOURCE
;; ============================================================================
;;
;; When a workflow OWNS a read for its lifetime, model the workflow as a
;; machine and let it own the resource under `[:machine machine-id
;; instance-id]` (released on actor destroy — Spec 016 §Release authority).
;; The machine stays the semantic workflow; the resource runtime handles
;; the cached-read mechanics. A tiny reader machine: it ensures the article
;; on entry, and the resource is released when the instance is destroyed.

;; `:entry` names an action by keyword (the house convention); the action
;; reads the slug + instance-id it owns under from its `:data` slot (Spec
;; 016 §Machine-owned resource models the owner as `[:machine machine-id
;; (:instance-id data)]`). Released on actor destroy.
(rf/reg-machine :resources.app/reader
  {:doc     "A reader workflow that owns the article it is reading."
   :initial :reading
   :data    {:slug nil :instance-id nil}
   :actions
   {:ensure-article
    (fn [{:keys [data]}]
      {:fx [[:dispatch [:rf.resource/ensure
                        {:resource :article/by-slug
                         :params   {:slug (:slug data)}
                         :owner    [:machine :resources.app/reader (:instance-id data)]
                         :cause    [:machine-action :resources.app/reader.reading]}]]]})}
   :states
   {:reading {:entry :ensure-article}}})

;; ============================================================================
;; APP DATA + READ-MODEL SUBS
;; ============================================================================
;;
;; The PASSIVE resource subs read the runtime-managed cache. A small derived
;; app sub picks a slug from the list for the "open preview" affordance.

(rf/reg-sub :resources.app/first-slug
  (fn [_ _]
    ;; A derived read over the list resource's data — projections are
    ;; ordinary subs layered over `[:rf.resource/data …]` (Spec 016 §No
    ;; :select key), NOT a resource-local hook.
    @(rf/subscribe [:rf.resource/data {:resource :articles/list :params {}}]))
  (fn [articles _] (:slug (first articles))))

;; ============================================================================
;; PAGES — passive reads; the runtime owns the state
;; ============================================================================

(reg-view home-page []
  [:div
   [:h1 "Resources demo"]
   [:p "Server-state as named, cached reads. Views are passive; route entry,
        events, and machines cause the fetch."]
   [:p [rf/route-link {:to :resources.app/articles
                       :data-testid "route-link-articles"}
        "See the articles →"]]])

(reg-view articles-page []
  ;; The articles list resource was ensured by THIS route's `:resources`
  ;; metadata on entry. The view reads its full view-model passively.
  (let [state @(subscribe [:rf.resource/state {:resource :articles/list :params {}}])]
    [:div
     [:h1 "Articles"]
     [:button {:data-testid "refresh-articles"
               :on-click    #(dispatch [:resources.app/refresh-articles])}
      (if (:fetching? state) "Refreshing…" "Refresh")]
     (cond
       ;; First load, no usable data yet → skeleton.
       (:loading? state)
       [:p {:data-testid "articles-skeleton"} "Loading articles…"]

       ;; First load failed, no usable data → error.
       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "articles-error"} "Could not load articles."]

       :else
       [:<>
        ;; A background-refresh failure keeps the data and surfaces a warning.
        (when (:refresh-error state)
          [:p.warn {:data-testid "articles-refresh-warn"} "Refresh failed; showing last-known data."])
        (into [:ul {:data-testid "articles-list"}]
              (for [{:keys [slug title]} (:data state)]
                ^{:key slug}
                [:li [rf/route-link {:to :resources.app/article-detail
                                     :params {:slug slug}
                                     :data-testid (str "route-link-article-" slug)}
                      title]]))])]))

(reg-view article-detail-page []
  ;; This route's `:resources` ensured :article/by-slug for the URL slug.
  (let [slug  (:slug @(subscribe [:rf.route/params]))
        state @(subscribe [:rf.resource/state {:resource :article/by-slug
                                               :params  {:slug slug}}])]
    [:div
     (cond
       (:loading? state)
       [:p {:data-testid "article-skeleton"} "Loading article…"]

       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "article-error"} "Could not load this article."]

       :else
       (let [{:keys [title body]} (:data state)]
         [:<>
          [:h1 {:data-testid "article-title"} title]
          (when (:fetching? state)
            [:span {:data-testid "article-refreshing"} " (refreshing…)"])
          [:p {:data-testid "article-body"} body]]))
     [:p [rf/route-link {:to :resources.app/articles
                         :data-testid "route-link-back"}
          "← Back"]]]))

(reg-view not-found-page []
  [:div
   [:h1 "Not found"]
   [:p [rf/route-link {:to :resources.app/home :data-testid "route-link-home"} "Home"]]])

(reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :resources.app/home           [home-page]
    :resources.app/articles       [articles-page]
    :resources.app/article-detail [article-detail-page]
    :rf.route/not-found           [not-found-page]
    [home-page]))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; The React root is materialised lazily inside `run` (not at ns-load) per
;; examples/TESTING.md §Example mount-isolation convention. EP-0002: the app
;; establishes its frame explicitly (`reg-frame`), declares `:url-bound?
;; true` so it owns the browser URL, and wraps the render in a
;; `frame-provider` so every in-tree dispatch/subscribe resolves to it. The
;; framework `install-history-listener!` does the initial URL→slice sync and
;; popstate handling, targeted at the URL owner.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {:doc "Resources demo frame." :url-bound? true})
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:frame app-frame}
                 [root-view]])))
