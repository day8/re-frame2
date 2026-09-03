(ns re-frame.flows.tooling
  "Read-only tooling projections over the flow registry.

  Kept separate from `re-frame.flows` so CLJS applications that attach no tool
  can eliminate this namespace. JVM consumers receive a facade alias; CLJS
  tools require this sibling directly."
  (:require [re-frame.derivation.node :as node]
            [re-frame.flows.registry :as registry]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- algebra views -------------------------------------------------------
;;
;; Every flow has the same derivation policy:
;;   :kind          :derivation
;;   :storage       :app-db
;;   :evaluation    :after-event
;;   :lifecycle     :frame
;;   :materialized? true

(defn- declared-input
  "Lower a flow input to `[:db path]` or `[:runtime path]`."
  [path]
  (if (registry/runtime-input? path)
    [:runtime (registry/input-resolve-path path)]
    [:db (registry/input-resolve-path path)]))

(defn- declared-inputs
  "Project inputs in the positional order expected by `:derive`."
  [flow]
  (mapv declared-input (:inputs flow)))

(defn- flow-source-coords
  "Return a flow's source-coordinate map, or nil when absent."
  [flow]
  (let [source (node/source-coords flow)]
    (when (seq source) source)))

(defn- with-metadata
  "Attach optional registration metadata and the opaque derive token."
  [node flow]
  (let [source (flow-source-coords flow)]
    (cond-> node
      (some? source)           (assoc :source source)
      (contains? flow :schema) (assoc :schema (:schema flow))
      (contains? flow :doc)    (assoc :doc (:doc flow))
      (contains? flow :derive) (assoc :derive (:derive flow)))))

(defn- node-for
  "Build one frame-owned, app-db-materialized derivation node."
  [frame-id flow]
  (let [flow-id (:id flow)]
    (-> (node/node-base flow-id [:db (vec (:output-path flow))]
                        {:kind          :derivation
                         :storage       :app-db
                         :evaluation    :after-event
                         :lifecycle     :frame
                         :materialized? true})
        (assoc :source-form {:kind :reg-flow :id flow-id}
               :inputs      (declared-inputs flow)
               :owner       [:frame frame-id])
        (with-metadata flow))))

(defn flow-algebra-view
  "Return normalized derivation nodes for registered flows.

  Zero-arity preserves the registry shape
  `{frame-id {flow-id derivation-node}}`; one-arity returns the selected
  frame's `{flow-id derivation-node}` map. The projection is read-only and
  preserves frame ownership for ids registered in more than one frame."
  ([]
   (let [snapshot (registry/flows-snapshot)]
     (reduce-kv
       (fn [acc frame-id flow-map]
         (assoc acc frame-id
                (reduce-kv
                  (fn [m flow-id flow]
                    (assoc m flow-id (node-for frame-id flow)))
                  {}
                  flow-map)))
       {}
       snapshot)))
  ([frame-id]
   (let [flow-map (get (registry/flows-snapshot) frame-id)]
     (reduce-kv
       (fn [m flow-id flow]
         (assoc m flow-id (node-for frame-id flow)))
       {}
       (or flow-map {})))))
