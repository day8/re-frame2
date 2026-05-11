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
            ;; rf2-k682: routing ships in day8/re-frame2-routing.
            ;; Requiring re-frame.routing at app boot is what triggers
            ;; its load-time hook + reg-sub registrations; without it,
            ;; rf/reg-route below throws :rf.error/routing-artefact-missing.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

;; ============================================================================
;; ROUTES
;; ============================================================================

(rf/reg-route :route/home
  {:doc  "Landing page."
   :path "/"})

(rf/reg-route :route/articles
  {:doc  "Articles list."
   :path "/articles"})

(rf/reg-route :route/article-detail
  {:doc    "Detail page for one article."
   :path   "/articles/:id"
   :params [:map [:id :string]]})

;; The runtime emits :rf.route/not-found for unmatched URLs.
(rf/reg-route :rf.route/not-found
  {:doc  "Fallback page for unmatched URLs."
   :path "/_404"})

;; ============================================================================
;; APP DATA
;; ============================================================================

(rf/reg-event-db :routing.app/initialise
  (fn [_ _]
    {:articles [{:id "intro" :title "Intro to re-frame2" :body "..."}
                {:id "ssr"   :title "Server rendering"  :body "..."}]}))

(rf/reg-sub :articles
  (fn [db _] (:articles db)))

(rf/reg-sub :article-by-id
  :<- [:articles]
  (fn [articles [_ id]]
    (first (filter #(= id (:id %)) articles))))

;; ============================================================================
;; LINK VIEW
;; ============================================================================

(reg-view route-link [{:keys [to params]} & children]
  (let [url (rf/route-url to (or params {}))]
    [:a {:href     url
         :on-click (fn [e]
                     (when (and (zero? (.-button e))
                                (not (.-metaKey e))
                                (not (.-ctrlKey e))
                                (not (.-shiftKey e)))
                       (.preventDefault e)
                       (dispatch [:rf/url-requested
                                  {:url url :to to :params (or params {})}])))}
     (into [:span] children)]))

;; ============================================================================
;; PAGES
;; ============================================================================

(reg-view home-page []
  [:div
   [:h1 "Welcome"]
   [:p [route-link {:to :route/articles} "See the articles →"]]])

(reg-view articles-page []
  [:div
   [:h1 "Articles"]
   [:ul
    (for [{:keys [id title]} @(subscribe [:articles])]
      ^{:key id}
      [:li [route-link {:to :route/article-detail :params {:id id}} title]])]])

(reg-view article-detail-page []
  (let [id      (:id @(subscribe [:rf.route/params]))
        article @(subscribe [:article-by-id id])]
    (if article
      [:div
       [:h1 (:title article)]
       [:p (:body article)]
       [:p [route-link {:to :route/articles} "← Back"]]]
      [:div
       [:p "Article not found."]
       [:p [route-link {:to :route/articles} "← Back"]]])))

(reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div
     [:h1 "Not found"]
     [:p (str "No route matches: " url)]
     [:p [route-link {:to :route/home} "Home"]]]))

(reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :route/home           [home-page]
    :route/articles       [articles-page]
    :route/article-detail [article-detail-page]
    :rf.route/not-found   [not-found-page]
    [not-found-page]))

;; ============================================================================
;; ROUTER WIRING
;; ============================================================================

(defn current-url []
  (str (.. js/window -location -pathname)
       (.. js/window -location -search)
       (.. js/window -location -hash)))

(defn install-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/dispatch [:rf.route/handle-url-change (current-url)])))
  (rf/dispatch-sync [:rf.route/handle-url-change (current-url)]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn routing-tests []
  (with-frame [f (rf/make-frame {:on-create [:routing.app/initialise]})]
    (rf/dispatch-sync [:rf.route/navigate :route/articles] {:frame f})
    (assert (= :route/articles (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/navigate :route/article-detail {:id "intro"}] {:frame f})
    (assert (= :route/article-detail (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))
    (assert (= "intro" (:id (rf/compute-sub [:rf.route/params] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/ssr"] {:frame f})
    (assert (= :route/article-detail (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage"] {:frame f})
    (assert (= :rf.route/not-found (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is named `react-root`; `reg-view` defs `root-view`,
;; which is what we render.
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf2-agql: pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:routing.app/initialise])
  (install-router!)
  (rdc/render react-root [root-view]))
