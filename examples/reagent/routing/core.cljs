(ns routing.core
  "A small three-page routing app: home, articles list, article detail.
   See the [routing guide](../../../docs/routing/concepts.md).

   The one idea: the URL is application state you read through a subscription,
   and navigation is just an event. A route is a registration, the active route
   is a sub, and `root-view` is a `case` over `:rf.route/id`. No router object,
   no route context to thread through the tree.

   The surface this example uses:

   - `reg-route` for the route table
   - `:rf.route/id` and `:rf.route/params` to read the active route as subs
   - `route-link` for links that drive navigation
   - `:rf/url-requested` for user-initiated anchor clicks
   - `install-history-listener!` for popstate + initial-load URL→state sync"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Routing ships in the day8/re-frame2-routing artefact. Requiring
            ;; it registers the route subs the `rf/reg-route` calls below depend
            ;; on; without this require those calls throw
            ;; :rf.error/routing-artefact-missing.
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

;; The articles collection lives under :routing.app/articles-list. Route
;; ids and sub/app-db keys live in separate registries, so a clash is
;; harmless — but a distinct key (not :routing.app/articles, the route
;; id) keeps the example easy to scan.
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
;; Links use `rf/route-link`, the framework view shipped by the routing
;; artefact. It renders an `<a href=...>`, intercepts plain primary-button
;; clicks to dispatch `:rf/url-requested`, and defers modifier-key and
;; middle-click clicks to the browser so open-in-new-tab still works. Any
;; passthrough HTML attrs on the props map (e.g. `:data-testid`) land on the
;; underlying `<a>`. See the routing guide, "Linking from views":
;; ../../../docs/routing/concepts.md#linking-from-views

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
;; MOUNT (+ router wiring)
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; The frame id the whole app runs under. `:rf/default` is an ordinary id
;; with no framework privilege — the runtime won't infer it for you, so you
;; establish it like any other (the provider in `run` does that). A frame
;; owns the browser URL by carrying `:url-bound? true`, which this app's
;; provider sets. See the routing guide, "The browser is just another event
;; source": ../../../docs/routing/concepts.md#the-browser-is-just-another-event-source
(def app-frame :rf/default)

(defn run []
  ;; Install the Reagent adapter. Each adapter ns exports an `adapter`
  ;; var; pass it directly to `init!`.
  (rf/init! reagent-adapter/adapter)
  ;; Install the framework popstate listener. It does the initial
  ;; URL→state sync now, then on each Back/Forward resolves the URL-owner
  ;; frame at pop time and updates that frame's `:rf/route` slice. Safe to
  ;; call again on hot reload.
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The frame provider sets up the app frame in one spot. On first mount
    ;; it creates the frame under `app-frame`, marks it `:url-bound? true` so
    ;; it owns the URL, and runs `:initial-events` once to seed app-db. On
    ;; hot reload it reuses the existing frame and skips the seed. Everything
    ;; in `root-view` then dispatches and subscribes against this frame.
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :doc "Routing demo frame."
                                    :url-bound? true
                                    :initial-events [[:routing.app/initialise]]}
                 [root-view]])))
