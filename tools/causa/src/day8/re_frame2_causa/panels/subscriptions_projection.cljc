(ns day8.re-frame2-causa.panels.subscriptions-projection
  "Project live subscription cache data into panel rows."
  (:require [day8.re-frame2-causa.panels.subscriptions-status :as status]))

(defn- sub-runs-by-query-v
  [sub-runs]
  (into {}
        (map (juxt :query-v identity))
        (or sub-runs [])))

(defn compute-status
  "Project one cache entry's canonical status."
  [{:keys [ref-count rerunning? invalidated?] :as _cache-entry}
   sub-run
   error?]
  (cond
    error?                                  :error
    rerunning?                              :re-running
    (and sub-run (:recomputed? sub-run))    :fresh
    invalidated?                            :invalidated
    (or (nil? ref-count) (zero? ref-count)) :cached-no-watcher
    :else                                   :fresh))

(defn- query-v-of-cache-entry
  [[k v]]
  (or (:query-v v) k))

(defn project-rows
  "Project the live sub-cache, sub-run projection, and error cache into
  sorted row maps consumed by the view."
  [sub-cache sub-runs error-cache]
  (let [by-q-v (sub-runs-by-query-v sub-runs)
        err    (or error-cache {})
        rows   (for [[k entry] (or sub-cache {})
                     :let [q-v     (query-v-of-cache-entry [k entry])
                           sub-run (get by-q-v q-v)
                           error   (get err q-v)
                           status  (compute-status entry sub-run
                                                   (some? error))]]
                 {:query-v      q-v
                  :sub-id       (first q-v)
                  :status       status
                  :layer        (:layer entry)
                  :ref-count    (or (:ref-count entry) 0)
                  :input-subs   (vec (or (:input-subs entry) []))
                  :recomputed?  (boolean (some-> sub-run :recomputed?))
                  :error        error})
        sorter (zipmap status/statuses (range))]
    (vec
      (sort-by (fn [{:keys [status query-v]}]
                 [(get sorter status (count status/statuses))
                  (pr-str query-v)])
               rows))))

(defn status-counts
  [rows]
  (frequencies (map :status rows)))

(defn filter-by-status
  [rows keep-statuses]
  (if (or (nil? keep-statuses) (empty? keep-statuses))
    rows
    (filterv #(contains? keep-statuses (:status %)) rows)))

(def sub-runs-index
  "Internal seam shared with the chain model."
  sub-runs-by-query-v)
