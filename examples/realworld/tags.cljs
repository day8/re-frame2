(ns example.realworld.tags
  "Home-page query helpers for tag filtering and feed selection.

   The popular-tags list itself is loaded by `articles.cljs`. This file
   owns the route-query driven part of the home page:
   - `?tag=<name>` filters the global articles list
   - `?feed=your` switches the home page to the authenticated feed
   - navigation is always expressed as `:rf.route/navigate` events"
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn home-query [db]
  (get-in db [:route :query] {}))

(rf/reg-event-fx :home/load
  (fn [{:keys [db]} _]
    (let [{:keys [feed]} (home-query db)]
      {:fx (cond-> [[:dispatch [:tags/load]]]
             (= "your" feed) (conj [:dispatch [:feed/load]])
             (not= "your" feed) (conj [:dispatch [:articles/load]]))})))

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

(defn tag-query-test []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (assert (= "your" (:feed (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))))
