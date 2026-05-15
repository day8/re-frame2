(ns day8.re-frame2-causa.panels.subscriptions-chain-model
  "Pure invalidation-chain projection for the Subscriptions panel."
  (:require [day8.re-frame2-causa.panels.subscriptions-projection
             :as projection]))

(defn- input-row
  [input-q-v sub-cache by-q-v error-cache]
  (let [entry  (get sub-cache input-q-v)
        layer  (:layer entry)
        run    (get by-q-v input-q-v)
        error  (get error-cache input-q-v)
        status (projection/compute-status (or entry {}) run
                                          (some? error))]
    {:query-v     input-q-v
     :sub-id      (first input-q-v)
     :layer       layer
     :status      status
     :recomputed? (boolean (some-> run :recomputed?))
     :link-reason (if (or (nil? layer) (<= layer 1))
                    :slice-changed
                    :input-changed)}))

(defn compute-chain
  "Project the one-level invalidation chain for a focused sub."
  [focused-q-v sub-cache sub-runs error-cache changed-paths]
  (let [by-q-v (projection/sub-runs-index sub-runs)
        entry  (get sub-cache focused-q-v)
        run    (get by-q-v focused-q-v)
        error  (get error-cache focused-q-v)]
    (if (nil? entry)
      {:focused      nil
       :inputs       []
       :app-db-paths []
       :missing?     true}
      (let [status      (projection/compute-status entry run
                                                   (some? error))
            inputs      (->> (or (:input-subs entry) [])
                             (map #(input-row % sub-cache by-q-v
                                              error-cache))
                             (filter (some-fn :recomputed?
                                              #(= :error (:status %))))
                             vec)
            own-paths   (when (and (some? (:layer entry))
                                   (<= (:layer entry) 1))
                          (or (:paths entry) []))
            input-paths (->> (or (:input-subs entry) [])
                             (mapcat
                               (fn [iq]
                                 (let [ie (get sub-cache iq)]
                                   (when (and (some? (:layer ie))
                                              (<= (:layer ie) 1))
                                     (or (:paths ie) [])))))
                             vec)
            all-paths   (vec (distinct (concat own-paths input-paths)))
            attributed  (if (seq changed-paths)
                          (filterv (set changed-paths) all-paths)
                          all-paths)]
        {:focused      {:query-v      focused-q-v
                        :sub-id       (first focused-q-v)
                        :status       status
                        :layer        (:layer entry)
                        :ref-count    (or (:ref-count entry) 0)
                        :input-subs   (vec (or (:input-subs entry) []))
                        :recomputed?  (boolean (some-> run :recomputed?))
                        :error        error}
         :inputs       inputs
         :app-db-paths attributed
         :missing?     false}))))
