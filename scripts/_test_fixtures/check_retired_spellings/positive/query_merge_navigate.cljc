(ns fixture.positive.query-merge-navigate
  "POSITIVE fixture: the retired :query-merge key in an in-place navigate
  request. An in-place :query merges into the current query.")

(defn next-page [rf page]
  ;; RETIRED: one way to edit the query in place.
  (rf/dispatch [:rf.route/navigate {:query-merge {:page (inc page)}}]))
