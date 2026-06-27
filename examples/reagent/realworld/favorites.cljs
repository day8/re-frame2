(ns realworld.favorites
  "Favorite/unfavorite actions plus the authenticated user's feed.

   The favorite toggle is shared across the home feed, profile lists, and
   the article-detail page. The followed-authors feed is its own remote-data
   slice so the home page can switch between feeds without throwing away
   already-loaded global articles."
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

(rf/reg-event :feed/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :feed (request-slice))}))

(rf/reg-event :feed/load
  {:doc "Fetch the authenticated user's feed. Carries
         `:request-id :feed/load` so :feed/cancel can abort an in-flight
         load when the user navigates away.

         Also broadcasts `:fetch-started` into the home machine so the
         `:data` region advances to `:loading` (or `:refreshing` from
         `:some`).

         `?page=` (the home route's 1-indexed page) becomes the wire's
         limit/offset window via `rh/paginate-path` — the same pagination
         the global feed uses."
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
  {:doc "Abort an in-flight :feed/load — e.g. when the user navigates away
         mid-load. See the HTTP guide on aborts:
         ../../../docs/resources/http.md#the-search-box-race-cured"}
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :feed/load]]}))

(rf/reg-event :feed/loaded
  {:doc "Successful user-feed fetch. Folds the new count into the home
         machine via `:fetch-succeeded`; the `:data` region's
         `:resolving` `:always`-cascade picks `:empty` or `:some`."
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
;;
;; Optimistic rollback shapes across the app. realworld has three
;; optimistic-with-rollback flows, and they use three different rollback
;; shapes on purpose — because the thing being rolled back differs:
;;
;;   - favorite (here): snapshots {:favorited :favoritesCount} and patches
;;     the article EVERYWHERE it appears (across the :articles / :feed /
;;     :profile.* slices) via the shared `patch-article-everywhere` /
;;     `update-article-in-list` helpers below — the cross-slice case.
;;   - follow (profile.cljs): snapshots a single boolean — one field, one
;;     slice — so a helper would be heavier than the `assoc-in` it replaces.
;;   - comment-delete (comments.cljs): snapshots {:index :comment} and
;;     re-inserts the removed comment at its original position — a positional
;;     splice the map-and-swap helper here can't express (the entry is gone,
;;     not present-to-map-over).
;;
;; A single shared optimistic helper would have to abstract over "patch a
;; field across N slices", "flip one boolean", and "re-insert at an index" —
;; obscuring more than it saves. The shared surface that DOES pay off
;; (cross-slice article patching) is already factored out below and reused
;; by `:comment-form/submit-success`'s in-place swap. So: shared where it
;; helps, distinct where the data shapes genuinely differ — not an oversight.

(rf/reg-event :article/toggle-favorite
  {:doc "Optimistically flip the favorited flag and bump the count, then
         POST or DELETE the favorite. On failure the prior state is
         restored (rollback).

         Auth-gated: favoriting requires a session. An unauthenticated
         click navigates to login instead of issuing a tokenless request
         that the real Conduit backend would 401 — so there is no
         optimistic flip-then-rollback flicker for a logged-out user. (The
         demo stub 200s everything, which would mask the 401; gating here
         keeps the example correct against the real backend it documents.)"
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

