(ns realworld.favorites
  "Favorite/unfavorite actions plus the authenticated user's feed.

   The favorite toggle is shared across the home feed, profile lists,
   and the article-detail page. The followed-authors feed is a distinct
   Pattern-RemoteData slice so the home page can switch between feeds
   without throwing away already-loaded global articles."
  (:require [re-frame.core :as rf]
            [realworld.schema :as schema]
            [realworld.http :as rh]))

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
  {:doc "Fetch the authenticated user's feed. Tagged with
         `:request-id :feed/load` so :feed/cancel can abort an in-flight
         load when the user navigates away (Spec 014 §Aborts).

         Also broadcasts `:fetch-started` into the home machine so the
         `:data` region advances to `:loading` (or `:refreshing` from
         `:some`)."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:feed :status]
                       (if (seq (get-in db [:feed :data])) :fetching :loading))
             (assoc-in [:feed :error] nil)
             (update-in [:feed :attempt] (fnil inc 0)))
     :fx [[:dispatch [:realworld/articles-home [:fetch-started]]]
          [:rf.http/managed
           (rh/request {:method     :get
                        :path       "/articles/feed"
                        :decode     schema/ArticlesResponse
                        :retry      rh/data-fetch-retry
                        :request-id :feed/load
                        :on-success [:feed/loaded]
                        :on-failure [:feed/load-failed]})]]}))

(rf/reg-event-fx :feed/cancel
  {:doc "Abort an in-flight :feed/load. Useful when the user navigates
         away mid-load (Spec 014 §Aborts)."}
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :feed/load]]}))

(rf/reg-event-fx :feed/loaded
  {:doc "Successful user-feed fetch. Folds the new count into the home
         machine via `:fetch-succeeded`; the `:data` region's
         `:resolving` `:always`-cascade picks `:empty` or `:some`."}
  [(rf/inject-cofx :realworld/now)]
  (fn [{:keys [db realworld/now]} [_ {:keys [value]}]]
    (let [items (vec (:articles value))]
      {:db (-> db
               (assoc-in [:feed :status] :loaded)
               (assoc-in [:feed :data] items)
               (assoc-in [:feed :loaded-at] now))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-succeeded {:items items}]]]]})))

(rf/reg-event-fx :feed/load-failed
  {:doc "Failed user-feed fetch. Folds the failure into the home machine
         via `:fetch-failed`; the `:data` region advances to `:error`."}
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    (let [message (rh/failure->message failure)]
      {:db (-> db
               (assoc-in [:feed :status] :error)
               (assoc-in [:feed :error] message))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-failed {:failure message}]]]]})))

;; ============================================================================
;; FAVORITES
;; ============================================================================

(rf/reg-event-fx :article/toggle-favorite
  {:doc "Optimistically flip the favorited flag and bump the count, then
         POST or DELETE the favorite. On failure the prior state is
         restored (rollback)."
   :rf.http/decode-schemas [schema/ArticleResponse]}
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
         :fx [[:rf.http/managed
               (rh/request {:method     (if favorited? :delete :post)
                            :path       (str "/articles/" slug "/favorite")
                            :decode     schema/ArticleResponse
                            :on-success [:article/favorite-synced slug]
                            :on-failure [:article/favorite-rollback slug prior]})]]})
      {})))

(rf/reg-event-db :article/favorite-synced
  (fn [db [_ slug {:keys [value]}]]
    (if-let [article (:article value)]
      (patch-article-everywhere db slug
                                (fn [_]
                                  (select-keys article
                                               [:slug :title :description :body :tagList
                                                :createdAt :updatedAt :favorited
                                                :favoritesCount :author])))
      db)))

(rf/reg-event-db :article/favorite-rollback
  (fn [db [_ slug {:keys [favorited favoritesCount]} _failure-payload]]
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

