(ns {{namespace}}.subs
  "Subscriptions — derived values over app-db.

   `reg-sub` registers a pure extractor: `(fn [db query-vector] value)`.
   The runtime caches by query-vector, recomputes only when inputs
   change, and fans out the result to every view that subscribed."
  (:require [re-frame.core :as rf]))

(rf/reg-sub
  :counter/value
  (fn [db _query]
    (:counter/value db)))
