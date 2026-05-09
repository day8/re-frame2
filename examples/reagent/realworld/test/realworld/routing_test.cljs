(ns realworld.routing-test
  "Headless tests for realworld.routing — route table coverage including
   path params, query, and not-found fallback. Extracted from
   realworld/routing.cljs under rf2-4v73."
  (:require [re-frame.core :as rf]
            [realworld.routing])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn routing-tests []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:rf.route/navigate :route/article {:slug "hello"}] {:frame f})
    (assert (= :route/article (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))
    (assert (= "hello" (:slug (rf/compute-sub [:rf.route/params] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/profile/eve"] {:frame f})
    (assert (= :route/profile (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/settings"] {:frame f})
    (assert (= :route/settings (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/?tag=clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))

    (rf/dispatch-sync [:rf.route/handle-url-change "/garbage/path"] {:frame f})
    (assert (= :rf.route/not-found (rf/compute-sub [:rf.route/id] (rf/get-frame-db f))))))
