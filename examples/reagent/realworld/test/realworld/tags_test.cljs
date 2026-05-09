(ns realworld.tags-test
  "Headless test for realworld.tags — tag filter and feed-kind query
   round-trip via the route slice. Extracted from realworld/tags.cljs
   under rf2-4v73."
  (:require [re-frame.core :as rf]
            [realworld.tags])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn tag-query-test []
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]})]
    (rf/dispatch-sync [:tags/apply-filter "clojure"] {:frame f})
    (assert (= "clojure" (:tag (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))
    (rf/dispatch-sync [:home/show-your-feed] {:frame f})
    (assert (= "your" (:feed (rf/compute-sub [:rf.route/query] (rf/get-frame-db f)))))))
