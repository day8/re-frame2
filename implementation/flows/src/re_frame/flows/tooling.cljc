(ns re-frame.flows.tooling
  "Read-only tooling projections over the flows each frame runs.

  Kept separate from `re-frame.flows` so CLJS applications that attach no tool
  can eliminate this namespace. The facade carries no alias for it; tools
  require this sibling directly on both runtimes."
  (:require [re-frame.derivation.node :as rf.derivation.node]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]))

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
  (if (rf.flows.registry/runtime-input? path)
    [:runtime (rf.flows.registry/partition-relative-input-path path)]
    [:db (rf.flows.registry/partition-relative-input-path path)]))

(defn- declared-inputs
  "Project inputs in the positional order expected by `:derive`."
  [flow]
  (mapv declared-input (:inputs flow)))

(defn- flow-source-coords
  "Return a flow's source-coordinate map, or nil when absent."
  [flow]
  (let [source (rf.derivation.node/source-coords flow)]
    (when (seq source) source)))

(defn- with-metadata
  "Attach optional registration metadata and the opaque derive token."
  [node flow]
  (let [source (flow-source-coords flow)]
    (cond-> node
      (some? source)           (assoc :source source)
      (contains? flow :doc)    (assoc :doc (:doc flow))
      (contains? flow :derive) (assoc :derive (:derive flow)))))

(defn- node-for
  "Build one frame-owned, app-db-materialized derivation node."
  [frame-id flow]
  (let [flow-id (:id flow)]
    (-> (rf.derivation.node/node-base flow-id [:db (vec (:output-path flow))]
                        {:kind          :derivation
                         :storage       :app-db
                         :evaluation    :after-event
                         :lifecycle     :frame
                         :materialized? true})
        (assoc :source-form {:kind :reg-flow :id flow-id}
               :inputs      (declared-inputs flow)
               :owner       [:frame frame-id])
        (with-metadata flow))))

(defn- frame-flows
  "The definitions `frame-id` runs, as `{flow-id flow}`, each `:flows` id
  resolved through the frame's own view. An id that does not resolve — or
  that more than one namespace registers — is left out: a read-only
  projection reports what the frame can run and never throws."
  [frame-id]
  (let [ids (:flows (rf.frame/frame-config frame-id))]
    (if (empty? ids)
      {}
      (let [resolve (fn []
                      (reduce (fn [m id]
                                (if-let [flow (try (rf.registrar/lookup rf.flows.registry/kind id)
                                                   (catch #?(:clj Exception :cljs :default) _ nil))]
                                  (assoc m id flow)
                                  m))
                              {}
                              ids))]
        (if-let [with-frame-view (rf.late-bind/get-fn
                                   :live-frame/call-with-frame-resolution)]
          (with-frame-view frame-id resolve)
          (resolve))))))

(defn flow-algebra-view
  "Return normalized derivation nodes for the flows frames run.

  Zero-arity returns `{frame-id {flow-id derivation-node}}` over every live
  frame that runs a flow; one-arity returns the selected frame's
  `{flow-id derivation-node}` map. Each frame's ids resolve through its own
  view, so two frames running different definitions of one id each project
  their own. The projection is read-only."
  ([]
   (reduce (fn [acc frame-id]
             (let [nodes (flow-algebra-view frame-id)]
               (cond-> acc (seq nodes) (assoc frame-id nodes))))
           {}
           (rf.frame/frame-ids)))
  ([frame-id]
   (reduce-kv
     (fn [m flow-id flow]
       (assoc m flow-id (node-for frame-id flow)))
     {}
     (frame-flows frame-id))))
