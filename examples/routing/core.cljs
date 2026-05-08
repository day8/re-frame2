(ns routing.core
  "Worked example for [Construction Prompt CP-7](../../Construction-Prompts.md)
   and [EP 012 Routing](../../012-Routing.md). A small three-page app:
   home, articles list, article detail. Demonstrates URL ↔ frame state,
   navigation as event, route as sub, and route-aware root-view dispatch.

   This example uses the current runtime routing surface directly:

   - `reg-route` for the route table
   - `:rf.route/navigate` for programmatic navigation
   - `:rf.route/handle-url-change` for popstate / initial load
   - `:rf.route/id` and `:rf.route/params` for route reads
   - `:rf/url-requested` for user-initiated anchor clicks"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf])
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

(rf/reg-event-db :app/initialise
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

(def route-link
  (reg-view :rf/route-link
    (fn render-route-link [{:keys [to params]} & children]
      (let [d   (rf/dispatcher)
            url (rf/route-url to (or params {}))]
        [:a {:href     url
             :on-click (fn [e]
                         (when (and (zero? (.-button e))
                                    (not (.-metaKey e))
                                    (not (.-ctrlKey e))
                                    (not (.-shiftKey e)))
                           (.preventDefault e)
                           (d [:rf/url-requested
                               {:url url :to to :params (or params {})}])))}
         (into [:span] children)]))))

;; ============================================================================
;; PAGES
;; ============================================================================

(def home-page
  (reg-view :pages/home
    (fn render-home []
      [:div
       [:h1 "Welcome"]
       [:p [route-link {:to :route/articles} "See the articles →"]]])))

(def articles-page
  (reg-view :pages/articles
    (fn render-articles []
      (let [s (rf/subscriber)]
        [:div
         [:h1 "Articles"]
         [:ul
          (for [{:keys [id title]} @(s [:articles])]
            ^{:key id}
            [:li [route-link {:to :route/article-detail :params {:id id}} title]])]]))))

(def article-detail-page
  (reg-view :pages/article-detail
    (fn render-article-detail []
      (let [s       (rf/subscriber)
            id      (:id @(s [:rf.route/params]))
            article @(s [:article-by-id id])]
        (if article
          [:div
           [:h1 (:title article)]
           [:p (:body article)]
           [:p [route-link {:to :route/articles} "← Back"]]]
          [:div
           [:p "Article not found."]
           [:p [route-link {:to :route/articles} "← Back"]]])))))

(def not-found-page
  (reg-view :pages/not-found
    (fn render-not-found []
      (let [s   (rf/subscriber)
            url (:url @(s [:rf.route/params]))]
        [:div
         [:h1 "Not found"]
         [:p (str "No route matches: " url)]
         [:p [route-link {:to :route/home} "Home"]]]))))

(def root-view
  (reg-view :app/root
    (fn render-root []
      (let [s (rf/subscriber)]
        (case @(s [:rf.route/id])
          :route/home           [home-page]
          :route/articles       [articles-page]
          :route/article-detail [article-detail-page]
          :rf.route/not-found   [not-found-page]
          [not-found-page])))))

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
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
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

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/dispatch-sync [:app/initialise])
  (install-router!)
  (rdc/render root [root-view]))
