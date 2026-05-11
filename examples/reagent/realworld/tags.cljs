(ns realworld.tags
  "Home-page query helpers for tag filtering and feed selection.

   The popular-tags list itself is loaded by `articles.cljs`. This file
   owns the route-query driven part of the home page:
   - `?tag=<name>` filters the global articles list
   - `?feed=your` switches the home page to the authenticated feed
   - navigation is always expressed as `:rf.route/navigate` events

   `:home/load` is dispatched by the `:route/home` `:on-match`; it
   broadcasts the per-axis transitions into the home machine
   (`:realworld/articles-home`) before kicking the per-feed fetch."
  (:require [re-frame.core :as rf]))

(defn home-query [db]
  (get-in db [:rf/route :query] {}))

(rf/reg-event-fx :home/load
  {:doc "Route :on-match handler for `:route/home`. Reads the route's
         query params and:
           - broadcasts the `:feed` region into `:user-feed` / `:tag-feed`
             / `:global` per `?feed=` and `?tag=`,
           - broadcasts the `:filter` region into `:tagged` / `:none`
             per `?tag=`,
           - kicks the per-feed fetch (`:articles/load` or `:feed/load`).
         Each fetch handler in turn broadcasts `:fetch-started` into the
         home machine's `:data` region (per articles.cljs and
         favorites.cljs)."}
  (fn [{:keys [db]} _]
    (let [{:keys [feed tag] :as _query} (home-query db)
          your-feed? (= "your" feed)
          tag-feed?  (and (not your-feed?) (some? tag))
          feed-event (cond
                       your-feed? [:show-user-feed]
                       tag-feed?  [:show-tag-feed]
                       :else      [:show-global])
          filter-event (if tag [:apply-filter] [:clear-filter])]
      {:fx (cond-> [[:dispatch [:realworld/articles-home feed-event]]
                    [:dispatch [:realworld/articles-home filter-event]]
                    [:dispatch [:tags/load]]]
             your-feed?       (conj [:dispatch [:feed/load]])
             (not your-feed?) (conj [:dispatch [:articles/load]]))})))

(rf/reg-event-fx :home/show-global-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query {}}]]]}))

(rf/reg-event-fx :home/show-your-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query {:feed "your"}}]]]}))

(rf/reg-event-fx :tags/apply-filter
  (fn [_ [_ tag]]
    {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query {:tag tag}}]]]}))

(rf/reg-event-fx :tags/clear-filter
  (fn [{:keys [db]} _]
    (let [query (dissoc (home-query db) :tag)]
      {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query query}]]]})))

(rf/reg-sub :home/query
  (fn [db _] (home-query db)))

(rf/reg-sub :home/selected-tag
  :<- [:home/query]
  (fn [query _] (:tag query)))

(rf/reg-sub :home/feed-kind
  :<- [:home/query]
  (fn [query _] (if (= "your" (:feed query)) :your :global)))
