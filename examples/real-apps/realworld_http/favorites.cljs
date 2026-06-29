(ns realworld-http.favorites
  "Favorite/unfavorite actions plus the authenticated user's feed.

   One favorite toggle, used in three places — the home feed, the profile
   lists, and the article-detail page. The catch is that the same article can
   be on screen in several of those at once, so a favorite has to be patched
   everywhere it appears; the cross-slice helpers below are what make that
   tidy. The followed-authors feed gets its own remote-data slice, so flipping
   between feeds on the home page doesn't throw away global articles you've
   already loaded."
  (:require [re-frame.core :as rf]
            [realworld-http.schema :as schema]
            [realworld-http.http :as rh]))

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

(rf/reg-event :feed/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :feed (request-slice))}))

(rf/reg-event :feed/load
  {:doc "Fetch the signed-in user's feed. Carries `:request-id :feed/load`, so
         :feed/cancel can pull the plug if the user wanders off mid-load. Also
         broadcasts `:fetch-started` into the home machine, nudging the `:data`
         region to `:loading` (or `:refreshing`, if a list is already up). The
         home route's 1-indexed `?page=` becomes the wire's limit/offset window
         via `rh/paginate-path` — the very same pagination the global feed
         uses."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [page (or (get-in rt [:rf.runtime/routing :current :query :page]) 1)
          path (rh/paginate-path "/articles/feed" page)]
      {:db (-> db
               (assoc-in [:feed :status]
                         (if (seq (get-in db [:feed :data])) :fetching :loading))
               (assoc-in [:feed :error] nil)
               (update-in [:feed :attempt] (fnil inc 0)))
       :fx [[:dispatch [:realworld/articles-home [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       path
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id :feed/load
                          :on-success [:feed/loaded]
                          :on-failure [:feed/load-failed]})]]})))

(rf/reg-event :feed/cancel
  {:doc "Abort an in-flight :feed/load — say the user leaves before it lands.
         No point delivering a reply to a screen that's gone. See the HTTP
         guide on aborts:
         ../../../docs/async/http.md#the-search-box-race-cured"}
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :feed/load]]}))

(rf/reg-event :feed/loaded
  {:doc "The user-feed fetch came back happy. Folds the new count into the home
         machine via `:fetch-succeeded`, and the `:data` region's `:resolving`
         `:always` cascade takes it from there — `:empty` or `:some`."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    (let [items (vec (:articles value))
          total (or (:articlesCount value) (count items))]
      {:db (-> db
               (assoc-in [:feed :status] :loaded)
               (assoc-in [:feed :data] items)
               (assoc-in [:feed :articles-count] total)
               (assoc-in [:feed :loaded-at] time-ms))
       :fx [[:dispatch [:realworld/articles-home
                        [:fetch-succeeded {:items items}]]]]})))

(rf/reg-event :feed/load-failed
  {:doc "The user-feed fetch didn't make it. Folds the failure into the home
         machine via `:fetch-failed`, sending the `:data` region to `:error`."}
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
;;
;; A short word on why the three optimistic-with-rollback flows in realworld
;; don't share one rollback helper — because it looks like an obvious DRY
;; opportunity, and it isn't. The reason is that each one is undoing a
;; genuinely different shape of change:
;;
;;   - favorite (here): snapshots {:favorited :favoritesCount} and patches the
;;     article EVERYWHERE it appears (across the :articles / :feed / :profile.*
;;     slices), using the shared `patch-article-everywhere` /
;;     `update-article-in-list` helpers below. This is the cross-slice case.
;;   - follow (profile.cljs): snapshots a single boolean — one field, one slice.
;;     A helper here would be more ceremony than the `assoc-in` it'd replace.
;;   - comment-delete (comments.cljs): snapshots {:index :comment} and slots the
;;     removed comment back at its original position — a positional splice the
;;     map-and-swap helper can't even express, because the entry is gone, not
;;     sitting there waiting to be mapped over.
;;
;; One shared helper would have to paper over \"patch a field across N slices\",
;; \"flip one boolean\", and \"re-insert at an index\" all at once — and an
;; abstraction that vague hides more than it saves. The shared bit that DOES
;; earn its keep (cross-slice article patching) is factored out below, and
;; `:comment-form/submit-success` reuses it for its in-place swap. Shared where
;; it helps, separate where the shapes truly differ — deliberate, not an
;; oversight.

(rf/reg-event :article/toggle-favorite
  {:doc "Flip the favorited flag and nudge the count immediately, then send the
         POST or DELETE; if it fails, put the old values back (rollback).

         Auth-gated: favoriting needs a session, so a logged-out click goes to
         login rather than firing a tokenless request the real Conduit backend
         would 401 — which means no ugly flip-then-rollback flicker for a
         signed-out user. (Why gate it when the demo stub 200s everything? That
         friendly stub would happily mask the 401; gating here keeps the
         example honest against the real backend it's documenting.)"
   :rf.http/decode-schemas [schema/ArticleResponse]}
  (fn [{:keys [db]} [_ slug]]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
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
        {}))))

(rf/reg-event :article/favorite-synced
  (fn [{:keys [db]} [_ slug {:keys [value]}]]
    {:db (if-let [article (:article value)]
      (patch-article-everywhere db slug
                                (fn [_]
                                  (select-keys article
                                               [:slug :title :description :body :tagList
                                                :createdAt :updatedAt :favorited
                                                :favoritesCount :author])))
      db)}))

(rf/reg-event :article/favorite-rollback
  (fn [{:keys [db]} [_ slug {:keys [favorited favoritesCount]} _failure-payload]]
    {:db (patch-article-everywhere db slug
                              #(assoc % :favorited favorited
                                        :favoritesCount favoritesCount))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :feed/slice (fn [db _] (:feed db)))
(rf/reg-sub :feed/data :<- [:feed/slice] (fn [slice _] (:data slice)))
(rf/reg-sub :feed/error :<- [:feed/slice] (fn [slice _] (:error slice)))
(rf/reg-sub :feed/count :<- [:feed/slice] (fn [slice _] (:articles-count slice 0)))
(rf/reg-sub :feed/loading? :<- [:feed/slice]
  (fn [slice _] (#{:loading :fetching} (:status slice))))

