(ns realworld.favorites
  "Favorite/unfavorite actions plus the authenticated user's feed.

   The favorite toggle is shared across the home feed, profile lists,
   and the article-detail page. The followed-authors feed is a distinct
   Pattern-RemoteData slice so the home page can switch between feeds
   without throwing away already-loaded global articles."
  (:require [re-frame.core :as rf]
            [realworld.schema]
            [realworld.http])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn current-time-ms [] (.getTime (js/Date.)))

(defn request-slice []
  {:status :idle :data [] :error nil :loaded-at nil :attempt 0})

(def list-paths
  [[:articles :data]
   [:feed :data]
   [:profile.articles :data]
   [:profile.favorites :data]])

(defn update-article-in-list [articles slug f]
  (mapv (fn [article]
          (if (= slug (:slug article))
            (f article)
            article))
        (or articles [])))

(defn patch-article-everywhere [db slug f]
  (let [db* (reduce (fn [acc path]
                      (if (get-in acc path)
                        (update-in acc path update-article-in-list slug f)
                        acc))
                    db
                    list-paths)]
    (if (= slug (get-in db* [:article :data :slug]))
      (update-in db* [:article :data] f)
      db*)))

(defn find-article [db slug]
  (or (some #(when (= slug (:slug %)) %) (or (get-in db [:articles :data]) []))
      (some #(when (= slug (:slug %)) %) (or (get-in db [:feed :data]) []))
      (some #(when (= slug (:slug %)) %) (or (get-in db [:profile.articles :data]) []))
      (some #(when (= slug (:slug %)) %) (or (get-in db [:profile.favorites :data]) []))
      (when (= slug (get-in db [:article :data :slug]))
        (get-in db [:article :data]))))

;; ============================================================================
;; FEED
;; ============================================================================

(rf/reg-event-db :feed/initialise
  (fn [db _]
    (assoc db :feed (request-slice))))

(rf/reg-event-fx :feed/load
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:feed :status]
                       (if (seq (get-in db [:feed :data])) :fetching :loading))
             (assoc-in [:feed :error] nil)
             (update-in [:feed :attempt] (fnil inc 0)))
     :fx [[:http {:method     :get
                  :url        "/articles/feed"
                  :on-success [:feed/loaded]
                  :on-error   [:feed/load-failed]}]]}))

(rf/reg-event-db :feed/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:feed :status] :loaded)
        (assoc-in [:feed :data] (vec (:articles resp)))
        (assoc-in [:feed :loaded-at] (current-time-ms)))))

(rf/reg-event-db :feed/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:feed :status] :error)
        (assoc-in [:feed :error] err))))

;; ============================================================================
;; FAVORITES
;; ============================================================================

(rf/reg-event-fx :article/toggle-favorite
  (fn [{:keys [db]} [_ slug]]
    (if-let [article (find-article db slug)]
      (let [prior {:favorited      (:favorited article)
                   :favoritesCount (:favoritesCount article)}
            favorited? (:favorited article)
            next-count (if favorited?
                         (max 0 (dec (:favoritesCount article)))
                         (inc (:favoritesCount article)))]
        {:db (patch-article-everywhere db slug
                                       #(assoc % :favorited (not favorited?)
                                                 :favoritesCount next-count))
         :fx [[:http {:method     (if favorited? :delete :post)
                      :url        (str "/articles/" slug "/favorite")
                      :on-success [:article/favorite-synced slug]
                      :on-error   [:article/favorite-rollback slug prior]}]]})
      {})))

(rf/reg-event-db :article/favorite-synced
  (fn [db [_ slug resp]]
    (if-let [article (:article resp)]
      (patch-article-everywhere db slug
                                (fn [_]
                                  (select-keys article
                                               [:slug :title :description :body :tagList
                                                :createdAt :updatedAt :favorited
                                                :favoritesCount :author])))
      db)))

(rf/reg-event-db :article/favorite-rollback
  (fn [db [_ slug {:keys [favorited favoritesCount]}]]
    (patch-article-everywhere db slug
                              #(assoc % :favorited favorited
                                        :favoritesCount favoritesCount))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :feed (fn [db _] (:feed db)))
(rf/reg-sub :feed/data :<- [:feed] (fn [slice _] (:data slice)))
(rf/reg-sub :feed/error :<- [:feed] (fn [slice _] (:error slice)))
(rf/reg-sub :feed/loading? :<- [:feed]
  (fn [slice _] (#{:loading :fetching} (:status slice))))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn favorite-toggle-test []
  (rf/reg-fx :http.canned-favorite-rollback
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors {:body ["rollback"]}}) {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-favorite-rollback}})]
    (rf/dispatch-sync [:articles/initialise] {:frame f})
    (rf/dispatch-sync [:articles/loaded
                       {:articles [{:slug "hello"
                                    :title "Hello"
                                    :description "Short"
                                    :body "Body"
                                    :tagList []
                                    :createdAt "2026-05-01"
                                    :updatedAt "2026-05-01"
                                    :favorited false
                                    :favoritesCount 0
                                    :author {:username "alice" :bio nil :image nil :following false}}]}]
                      {:frame f})
    (rf/dispatch-sync [:article/toggle-favorite "hello"] {:frame f})
    (assert (false? (-> (rf/compute-sub [:articles/data] (rf/get-frame-db f))
                        first
                        :favorited)))
    (assert (= 0 (-> (rf/compute-sub [:articles/data] (rf/get-frame-db f))
                     first
                     :favoritesCount)))))
