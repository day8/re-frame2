(ns routing.core
  "Worked example for [Construction Prompt CP-7](../../../spec/Construction-Prompts.md)
   and [Spec 012 Routing](../../../spec/012-Routing.md). A small three-page app:
   home, articles list, article detail. Demonstrates URL ↔ frame state,
   navigation as event, route as sub, and route-aware root-view dispatch.

   This example uses the current runtime routing surface directly:

   - `reg-route` for the route table
   - `:rf.route/navigate` for programmatic navigation
   - `:rf.route/handle-url-change` for popstate / initial load
   - `:rf.route/id` and `:rf.route/params` for route reads
   - `:rf/url-requested` for user-initiated anchor clicks"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Routing ships in day8/re-frame2-routing.
            ;; Requiring re-frame.routing at app boot is what triggers
            ;; its load-time hook + reg-sub registrations; without it,
            ;; rf/reg-route below throws :rf.error/routing-artefact-missing.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :routing.app/home
  {:doc  "Landing page."} "/")

(rf/reg-route :routing.app/articles
  {:doc  "Articles list."} "/articles")

(rf/reg-route :routing.app/article-detail
  {:doc    "Detail page for one article."
   :params [:map [:id :string]]} "/articles/:id")

;; The runtime emits :rf.route/not-found for unmatched URLs.
(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for unmatched URLs."} "/_404")

;; ============================================================================
;; APP DATA
;; ============================================================================

;; The articles collection lives under :routing.app/articles-list (not
;; :routing.app/articles) so it doesn't shadow the `:routing.app/articles`
;; route-id. The route-registry and reg-sub registries are independent,
;; but keeping the sub-id + app-db-key separate from the route-id makes
;; the example easier to scan.
(rf/reg-event :routing.app/initialise
  (fn [{:keys [db]} _]
    {:db {:routing.app/articles-list
     [{:id "intro" :title "Intro to re-frame2" :body "..."}
      {:id "ssr"   :title "Server rendering"  :body "..."}]}}))

(rf/reg-sub :routing.app/articles-list
  (fn [db _] (:routing.app/articles-list db)))

(rf/reg-sub :routing.app/article-by-id
  :<- [:routing.app/articles-list]
  (fn [articles [_ id]]
    (first (filter #(= id (:id %)) articles))))

;; ============================================================================
;; PAGES
;; ============================================================================
;;
;; Links use `rf/route-link` — the registered view at `:route/link` shipped
;; by `day8/re-frame2-routing`. Per Spec 012 §Linking from views and the
;; API.md `route-link` row, the framework view renders an `<a href=...>`,
;; intercepts plain primary-button clicks to dispatch `:rf/url-requested`,
;; and defers modifier-key / auxiliary-button (middle-click) clicks to the
;; browser so the native open-in-new-tab affordance is preserved. Any
;; passthrough HTML attrs on the props map (e.g. `:data-testid`) land
;; on the underlying `<a>`.

(reg-view home-page []
  [:div
   [:h1 "Welcome"]
   [:p [rf/route-link {:to :routing.app/articles
                       :data-testid "route-link-articles"}
        "See the articles →"]]])

(reg-view articles-page []
  [:div
   [:h1 "Articles"]
   [:ul
    (for [{:keys [id title]} @(subscribe [:routing.app/articles-list])]
      ^{:key id}
      [:li [rf/route-link {:to :routing.app/article-detail
                           :params {:id id}
                           :data-testid (str "route-link-article-" id)}
            title]])]])

(reg-view article-detail-page []
  (let [id      (:id @(subscribe [:rf.route/params]))
        article @(subscribe [:routing.app/article-by-id id])]
    ;; The back-link is page-chrome shared by both the found and
    ;; not-found states, so compute only the differing inner content
    ;; per branch and render the link once.
    [:div
     (if article
       [:<> [:h1 (:title article)]
            [:p (:body article)]]
       [:p "Article not found."])
     [:p [rf/route-link {:to :routing.app/articles
                         :data-testid "route-link-back-to-articles"}
          "← Back"]]]))

(reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div
     [:h1 "Not found"]
     [:p (str "No route matches: " url)]
     [:p [rf/route-link {:to :routing.app/home
                         :data-testid "route-link-home"}
          "Home"]]]))

(reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :routing.app/home           [home-page]
    :routing.app/articles       [articles-page]
    :routing.app/article-detail [article-detail-page]
    :rf.route/not-found         [not-found-page]))

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
;; (`reg-view` defs `root-view`, which is what we render.)
(defonce react-root (atom nil))

;; EP-0002: under the carried invariant the runtime
;; never synthesises a frame from absence, and URL ownership is an EXPLICIT
;; declaration — a frame owns the browser URL only by carrying
;; `:url-bound? true` (Spec 012 §Multi-frame routing). So the app frame:
;;   1. is registered explicitly (`init!` installs only the adapter),
;;   2. declares `:url-bound? true` so it owns the URL, and
;;   3. seeds its app-db under `with-frame`.
;; The render is wrapped in a `frame-provider` so every in-tree
;; `dispatch`/`subscribe` resolves to it.
;;
;; The popstate listener is the framework's `rf/install-history-listener!`
;; (Spec 012 §popstate drives the URL-owner frame) — NOT a hand-rolled
;; frameless `(rf/dispatch [:rf.route/handle-url-change …])`. It resolves the
;; URL owner AT POP TIME via `url-owner-frame-id` and dispatches the URL-change
;; to THAT frame, so Back/Forward restores the owner's `:rf/route` slice. It
;; also does the initial URL→slice sync and is idempotent (hot-reload safe).
;; A hand-rolled frameless route dispatch would raise
;; `:rf.error/no-frame-context`; the framework listener is the canonical,
;; owner-resolving surface.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {:doc "Routing demo frame." :url-bound? true})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:routing.app/initialise]))
  ;; Framework popstate listener + initial URL sync, targeted at the URL owner.
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [root-view]])))
