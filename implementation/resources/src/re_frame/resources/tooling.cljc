(ns re-frame.resources.tooling
  "The derivation/process algebra views of resources for pair tools and
  conformance fixtures: `resource-algebra-view` (static, over the registry)
  and `resource-cache-algebra-view` (live, over a frame's cache). Read-only
  over the registry and runtime-db; no production code reads it, and the
  `re-frame.resources` facade never requires it, so an app that attaches no
  tool dead-code-eliminates it. Derivations §Resources expose process nodes."
  (:require [re-frame.derivation.node :as rf.derivation.node]
            [re-frame.frame :as rf.frame]
            [re-frame.identity :as rf.identity]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.ssr :as rf.resources.ssr]
            [re-frame.resources.state :as rf.resources.state]
            ;; The tool boundary opts into the trace-egress scope rule so the
            ;; two off-box boundaries cannot answer differently. The edge runs
            ;; from this bundle-isolated sibling into a production ns, the safe
            ;; direction.
            [re-frame.resources.trace-egress :as rf.resources.trace-egress]
            [re-frame.resources.transport :as rf.resources.transport]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- static algebra view --------------------------------------------------

(def resource-superkind
  "A resource is a `:process`. Derivations §The node shape."
  :process)

(def resource-refined-kind
  "The informative refinement of a resource node. Derivations §The node shape."
  :resource-process)

(def resource-storage
  "The local storage class: the cache entry lives in runtime-db. The remote
  source of truth is the separate `:authority` axis. Derivations §Storage class."
  :runtime-db)

(def resource-lifecycle
  "The lifecycle category: a scoped resource key owns the cache entry.
  Distinct from the `:resource/key` data field. Derivations §Lifecycle and owner."
  :scoped-resource-key)

(def resource-evaluation
  "A resource's trigger set: route activation, reply, scheduled timers and
  manual refresh/ensure. Derivations §Evaluation policy."
  #{:on-route :on-reply :scheduled :manual})

(def ^:private resource-selectors
  "The read-fact sub ids over a resource entry, surfaced under `:selectors`
  rather than minted as nodes; reading one starts no work. Matches
  `re-frame.resources.subs/register-subs!`. Spec 016 §Subscriptions (passive)."
  [:rf/resource
   :rf.resource/data
   :rf.resource/status
   :rf.resource/loading?
   :rf.resource/fetching?
   :rf.resource/stale?
   :rf.resource/error
   :rf.resource/refresh-error
   :rf.resource/has-data?
   :rf.resource/previous-data])

(defn- resource-commands
  "The transport command descriptor and its internal reply targets. Commands
  are not facts. Derivations §Process."
  [transport-id]
  [{:effect  transport-id
    :replies {:success :rf.resource.internal/succeeded
              :failure :rf.resource.internal/failed}}])

(defn- authority-for
  "The remote `:authority` map; `:transport` names the one transport,
  `:rf.http/managed`. Derivations §Authority — the remote axis."
  [transport-id]
  {:kind      :remote
   :system    :server
   :transport transport-id})

(defn- declared-inputs
  "The static declared inputs: generic params plus the scope policy reported
  verbatim. Static inspection never runs a resolver.
  Derivations §The don't-execute rule (EP-0014 issue-3 disposition)."
  [spec]
  [[:param :rf.params]
   [:scope (:scope spec)]])

(defn- scope-resolver-enrichment
  "For a `{:from-db <id>}` scope, `{:id <id>}` plus the resolver's declared
  `[:db <path>]` inputs when it is registered (a forward reference carries the
  id alone); nil for any other scope.
  Derivations §Named-resolver enrichment (EP-0014 issue-3 disposition)."
  [spec]
  (let [scope (:scope spec)]
    (when (rf.resources.scope-registry/from-db-reference? scope)
      (let [scope-id (:from-db scope)
            rmeta    (:rf/resource-scope
                       (rf.registrar/lookup
                         rf.resources.scope-registry/scope-kind scope-id))]
        (cond-> {:id scope-id}
          (some? rmeta)
          (assoc :inputs (vec (vals (:inputs rmeta)))
                 :whole-db? (boolean (:whole-db? rmeta))))))))

(defn- with-metadata
  "Attaches source coords, `:schema` (the `:data-schema`), `:doc` and the
  opaque `:derive` request-fn token when present. Coords ride the registrar
  slot, not the spec."
  [node spec slot]
  (cond-> (rf.derivation.node/attach-source node slot)
    (contains? spec :data-schema) (assoc :schema (:data-schema spec))
    (some? (:doc spec))           (assoc :doc (:doc spec))
    (contains? spec :request)     (assoc :derive (:request spec))))

(defn- static-node-for
  "The static process node for one registered resource.
  Derivations §Resources expose process nodes."
  [resource-id spec slot]
  (let [transport-id rf.resources.transport/managed-http-transport
        resolver     (scope-resolver-enrichment spec)]
    (-> (rf.derivation.node/node-base resource-id [:runtime [rf.resources.state/resources-key :entries]]
                        {:kind          resource-superkind
                         :storage       resource-storage
                         :evaluation    resource-evaluation
                         :lifecycle     resource-lifecycle
                         :materialized? true})
        (assoc :refinement  resource-refined-kind
               :source-form {:kind :reg-resource :id resource-id}
               :inputs      (declared-inputs spec)
               :authority   (authority-for transport-id)
               :selectors   resource-selectors
               :commands    (resource-commands transport-id))
        (cond-> (some? resolver) (assoc :scope-resolver resolver))
        (with-metadata spec slot))))

(defn resource-algebra-view
  "The static algebra view of registered resources. Zero-arity returns
  `{resource-id <node>}` (`{}` when none); one-arity returns that resource's
  node or nil. Pure data read through the registrar seams; JVM-runnable.
  There is deliberately no `re-frame.core` export.
  Derivations §Resources expose process nodes."
  ([]
   (reduce
     (fn [acc resource-id]
       (let [slot (rf.registrar/lookup rf.resources.registry/resource-kind resource-id)
             spec (:rf/resource slot)]
         (cond-> acc
           (some? spec) (assoc resource-id (static-node-for resource-id spec slot)))))
     {}
     (rf.registrar/ids rf.resources.registry/resource-kind)))
  ([resource-id]
   (let [slot (rf.registrar/lookup rf.resources.registry/resource-kind resource-id)
         spec (:rf/resource slot)]
     (when (some? spec)
       (static-node-for resource-id spec slot)))))

;; ---- live view and its tool-egress projection -----------------------------
;;
;; The live view is a tool egress boundary, and the scope and params sit in
;; node keys where a value-path walk cannot reach them, so each key is
;; projected here: the scope by the trace-egress standing scope rule, the
;; params by the same owner classification the SSR path uses. The resource-id
;; always survives. Projection is many-to-one, so entries whose projected keys
;; coincide are kept apart by ordinal (`collision-key`), never by anything
;; derived from the raw scope or params.

(defn- project-key-for-egress
  "Projects `scoped-key` for tool egress: the scope through
  `trace-egress/project-scope`, the params through
  `ssr/disposition+project-key`. `:params-schema` marks play no part.
  Returns the projected key. Spec 015 §Projection — applying classification at a boundary"
  [scoped-key frame-id]
  (let [[projected-key _disposition _spec] (rf.resources.ssr/disposition+project-key scoped-key frame-id)]
    (assoc projected-key 0 (rf.resources.trace-egress/project-scope (nth scoped-key 0)))))

(defn- live-work-link
  "Assocs the `:work-ledger` summary of `entry`'s live attempt `work-id` and
  its `:host-transient` handle address onto `acc`. The summary names the
  entry's projected `:resource/key`, the key the attempt serves. The work-id
  carries no scope or params, so it rides as-is."
  [acc entry work-id project-key]
  (let [record  (rf.resources.work-ledger/live-attempt entry work-id)
        summary (select-keys record
                             [:work/id :work/kind :status :generation
                              :transport :owners :causes])
        rec'    (cond-> (assoc summary :work/id work-id)
                  (some? record)
                  (assoc :resource/key (project-key (:resource/key entry))))]
    (assoc acc
           :work-ledger    {:work/id work-id :record rec'}
           :host-transient [[:rf.http/in-flight work-id]])))

(defn- collision-key
  "The identity of the `ordinal`-th of several entries projecting to
  `projected-key`: the params wrapped as
  `{:rf.resource/collision <ordinal> :rf.resource/params <params>}`. Adds
  only the ordinal, so it stays as content-free as the projection."
  [[scope resource-id params] ordinal]
  [scope resource-id {:rf.resource/collision ordinal
                      :rf.resource/params    params}])

(defn- visible-order
  "Sort key for a collision group, built only from what the nodes already
  show, so an ordinal reveals nothing; tied members are indistinguishable."
  [node]
  (let [axes (select-keys node [:status :lifecycle :work-ledger :host-transient])]
    (try
      (rf.identity/canonical-bytes axes)
      (catch #?(:clj Throwable :cljs :default) _t
        (pr-str axes)))))

(defn- live-node-for
  "The live process node for one cache entry. `proj-key` is its projected
  scoped key and `node-key` the identity it carries (`proj-key` or its
  `collision-key`); every identity position carries `node-key`.
  Derivations §The live resource cache view."
  [proj-key node-key frame-id entry static-node]
  (let [[proj-scope resource-id proj-params] proj-key
        project-key (fn [scoped-key]
                      (let [projected (project-key-for-egress scoped-key frame-id)]
                        (if (= projected proj-key) node-key projected)))
        work-id  (:current-work entry)]
    (cond-> {:id            node-key
             :kind          resource-superkind
             :refinement    resource-refined-kind
             :source-form   {:kind :reg-resource :id resource-id}
             :inputs        [[:scope proj-scope] [:param proj-params]]
             :output        [:runtime (rf.resources.state/entry-path node-key)]
             :storage       resource-storage
             :authority     (:authority static-node)
             :evaluation    resource-evaluation
             :lifecycle     {:kind   resource-lifecycle
                             :owners (or (:active-owners entry) #{})}
             :selectors     resource-selectors
             :materialized? true
             :status        (:status entry)}
      (some? work-id)
      (live-work-link entry work-id project-key)
      (contains? static-node :source) (assoc :source (:source static-node))
      (contains? static-node :schema) (assoc :schema (:schema static-node))
      (contains? static-node :doc)    (assoc :doc (:doc static-node))
      (contains? static-node :derive) (assoc :derive (:derive static-node)))))

(defn resource-cache-algebra-view
  "The live algebra view of `frame-id`'s resource cache: one process node
  per entry, keyed on the byte `key-id` of the node's projected `:id`. `{}`
  for no entries or an unknown frame. Read through
  `rf.frame/frame-runtime-db-value`; JVM-runnable.
  Derivations §The live resource cache view."
  [frame-id]
  (let [runtime-db (rf.frame/frame-runtime-db-value frame-id)
        entries    (get-in runtime-db (rf.resources.state/entries-path))
        ;; The node identity is the entry's own `:resource/key` vector; the
        ;; `:entries` map key is an opaque byte key-id.
        members    (for [entry (vals entries)
                         :let  [scoped-key  (:resource/key entry)
                                static-node (resource-algebra-view (second scoped-key))
                                proj-key    (project-key-for-egress scoped-key frame-id)
                                node-at     #(live-node-for proj-key % frame-id
                                                            entry static-node)]]
                     {:proj-key proj-key :node (node-at proj-key) :node-at node-at})]
    ;; Group by the projected key's byte key-id, never by the vector: list and
    ;; vector params are Clojure-= yet two entries. A group of more than one
    ;; gives each member its `collision-key`.
    (reduce-kv
      (fn [acc _proj-key-id group]
        (reduce (fn [acc node] (assoc acc (rf.resources.state/key-id (:id node)) node))
                acc
                (if (next group)
                  (->> group
                       (sort-by (comp visible-order :node))
                       (map-indexed (fn [ordinal {:keys [proj-key node-at]}]
                                      (node-at (collision-key proj-key ordinal)))))
                  (map :node group))))
      {}
      (group-by #(rf.resources.state/key-id (:proj-key %)) members))))

;; ---- bundle isolation -----------------------------------------------------
;;
;; `implementation/scripts/check-bundle-isolation.cjs`'s
;; `resources-tooling` entry forbids this file in the counter build's
;; `manifest.edn`. The gate also fails when that path stops resolving,
;; so a move or rename takes the entry with it.
