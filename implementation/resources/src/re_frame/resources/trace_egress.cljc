(ns re-frame.resources.trace-egress
  "Off-box egress projection for the resource and mutation trace family, the
  resource and mutation read subs, and the family's data riding FX rows.

  Scoped keys `[scope resource-id params]` copied into trace tags are
  invisible to the generic value-path walk, so the family projects them
  itself, through the late-bound `:resources/project-resource-trace-egress`
  hook the epoch tool-pair consults; on-box listeners keep the raw evidence,
  and the trusted-local `:rf.egress/include-sensitive?` opt-in lifts it. This
  namespace is production-reachable rather than part of the bundle-isolated
  tooling sibling, because the hook must be published on CLJS too.

  Two rules, two owners. The SCOPE is the framework's: every non-global scope
  tokenizes content-free on every carrier with no owner lookup
  (`project-scope`), so all carriers of one scope agree, and applying the rule
  never stamps `:sensitive?`. The PARAMS are the owner's: they ride verbatim
  unless the owner makes the coarse `:sensitive?` / `:large?` claim. An
  unregistered owner fails closed. The resource-id and structural scalars
  always ride, so a tool can still attribute a row.
  Spec 015 §Subsystem projection-relative classification."
  (:require [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.path :as rf.path]
            [re-frame.privacy :as rf.privacy]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.reply :as rf.resources.reply]
            [re-frame.resources.ssr :as rf.resources.ssr]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.subs :as rf.resources.subs]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

#?(:clj (set! *warn-on-reflection* true))

(defn- redacted-token? [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- redact-anchored
  "`v`, the value of the slot `slot` an owner declares against, redacted under
  the `slot`-relative `sens` / `large` paths, index-free. The walk runs over
  `{slot v}` because a path rides a map key only once its first named segment
  has matched; walked from `v`'s own root it would miss a key at the top of
  `v`. Returns `v` itself when both path sets are empty."
  [slot v sens large]
  (let [anchor (fn [paths] (mapv #(into [slot] %) paths))]
    (get (rf.classification/redact-with-paths {slot v} (anchor sens) (anchor large)
                                              {:index-free? true})
         slot)))

(defn project-scope
  "The standing scope rule for off-box egress. `:rf.scope/global` and nil ride;
  a `[tier {identity}]` tuple keeps its tier and tokenizes the identity; any
  other scope tokenizes whole. No owner can declassify a scope, because it is
  identity an off-box observer must not learn. Idempotent over tokens and the
  `:rf/redacted` sentinel; never stamps `:sensitive?`."
  [scope]
  (cond
    (or (nil? scope)
        (contains? rf.resources.state/reserved-concrete-scopes scope)
        (= rf.privacy/redacted-sentinel scope)
        (redacted-token? scope))
    scope

    (and (vector? scope) (= 2 (count scope)) (keyword? (nth scope 0)) (map? (nth scope 1)))
    (if (redacted-token? (nth scope 1))
      scope
      [(nth scope 0) (rf.resources.ssr/redact-value (nth scope 1))])

    :else
    (rf.resources.ssr/redact-value scope)))

(defn- key-params-disposition
  "How a scoped key's params project off-box: the owner's
  `whole-entry-disposition`; `:redact` for an unregistered owner (fail-closed);
  `::projected` for params that are already a token (they ride and still count
  as an owner redaction)."
  [scoped-key]
  (if (redacted-token? (nth scoped-key 2))
    ::projected
    (if-let [spec (rf.resources.registry/resource-meta (nth scoped-key 1))]
      (rf.resources.classification/whole-entry-disposition spec)
      :redact)))

(defn- project-trace-scoped-key
  "Projects one scoped key: scope by `project-scope`, params by
  `key-params-disposition`. Returns `[projected-key owner-redacted?]`, where
  `owner-redacted?` is the row's `:sensitive?` stamp and is false when only the
  scope tokenized. A non-key value rides unchanged. Idempotent."
  [scoped-key _frame-id]
  (if-not (and (vector? scoped-key) (= 3 (count scoped-key)))
    [scoped-key false]
    (let [[scope resource-id params] scoped-key
          disposition (key-params-disposition scoped-key)]
      [[(project-scope scope)
        resource-id
        (if (contains? #{:serialize ::projected} disposition)
          params
          (rf.resources.ssr/redact-value params disposition))]
       (not= :serialize disposition)])))

(def ^:private scoped-key-slot
  "Tag slots carrying a single scoped key."
  #{:resource/key})

(def ^:private scoped-keys-slot
  "Tag slots named as carrying a vector of scoped keys. A fidelity aid, not the
  defence: an unnamed slot's keys project identically through the shape-driven
  default (`project-unknown-slot-value`), so do not grow this set for a newly
  noticed slot."
  #{:resource/keys :matched :removed :keys :exempt :committed
    :patched :populated :invalidated
    :restored :conflicted :refetched
    :restored-keys :conflicted-keys :refetched-keys :reconciliation-refetches
    :affected-keys})

(def ^:private error-envelope-slot
  "Row slots carrying an `:rf.http/*` failure envelope, whose body routinely
  echoes submitted form fields. Tokenized content-free on every family row
  regardless of owner; status attribution survives on the row's other scalar
  tags."
  #{:error :page-error})

(def ^:private sibling-owned-slot
  "Slots the `:rf.resource/scope-resolved` projector, which runs first on its
  row, has already classified; they pass through. `:scope` is deliberately not
  here: other family rows stamp a resolved scope under `:scope` too, so it
  takes `scope-slot`'s rule on every row."
  #{:input-values})

(def ^:private scope-slot
  "The free resolved-scope tag. It takes `project-scope`, the rule the scope
  inside every key on the row takes, so the row's carriers of one scope agree."
  :scope)

(def ^:private cursor-slot
  "The load-more pagination cursor tags. The cursor is owner-defined and can
  carry identity, so it tokenizes iff the row's owner redacts its key."
  #{:page-param :next-page-param})

(def ^:private reply-payload-slot
  "The owner-data slots of a family continuation reply riding an FX carrier,
  tokenized iff the reply's owner redacts. Owner-conditional because
  `:value` and `:params` are words the FX family uses for its own data too, so
  a name-only arm would redact plain request maps and the generation counter."
  #{:params :value})

(def ^:private reply-error-slot
  "The failure-envelope slot of a family continuation reply (read or
  mutation), tokenized unconditionally inside the reply marker so it agrees
  with `error-envelope-slot` on the family's own rows. It must not join
  `reply-payload-slot`: that set is owner-conditional, which would leave a plain
  owner's envelope raw on the carrier while the row copy tokenizes. `:status` is
  not the gate, because an aborted reply still carries `:error`."
  #{:error})

(def ^:private reply-correlation-slot
  "The `:correlation` facts of a family continuation reply. Its `:scope` takes
  `project-scope`, gated on the reply marker rather than the payload proof,
  because a mutation reply's correlation carries no reserved vocabulary. It must
  not join `reply-payload-slot`: tokenizing the whole map would destroy the
  `:generation` / `:mutation/id` / `:instance/id` identities tools join on."
  :correlation)

(defn- row-owner-redacts?
  "Whether the row's owner classifies non-`:serialize` (sensitive, large, or
  unregistered), read from `:resource/key` on entry rows and `:resource/id` on
  attempt rows. Governs the cursor and a read reply's `:value` / `:params`.
  False when the row names no owner."
  [tags _frame-id]
  (let [sk  (:resource/key tags)
        rid (:resource/id tags)]
    (cond
      (and (vector? sk) (= 3 (count sk)))
      (not= :serialize (key-params-disposition sk))

      (keyword? rid)
      (not= :serialize (if-let [spec (rf.resources.registry/resource-meta rid)]
                         (rf.resources.classification/whole-entry-disposition spec)
                         :redact))

      :else false)))

(def ^:private disposition-rows-slot
  "Slots carrying a vector of per-key disposition maps, each with a
  `:resource/key`."
  #{:dispositions :rollback})

(def ^:private nested-map-slot
  "Slots whose value is a nested map of further slots, projected recursively."
  #{:patch-summary :invalidation})

(def ^:private work-id-marker
  "The head of a resource work-id `[:rf.work/resource <resource-id> <generation>]`.
  A work id carries no scoped key; its head keeps it from being read as one,
  and marks a payload the runtime planted."
  :rf.work/resource)

(defn- family-work-id?
  "Whether `v` is a resource work-id vector."
  [v]
  (and (vector? v) (= work-id-marker (first v))))

(defn- scoped-key-frame?
  "The skeleton of a scoped key: a 3-vector with a keyword at position 1 that
  is not a work id. Necessary, never sufficient: each reader adds its own proof."
  [v]
  (and (vector? v) (= 3 (count v)) (keyword? (nth v 1))
       (not (family-work-id? v))))

(defn- scoped-key-shape?
  "Whether `v` is recognisably a scoped key on a family row: the skeleton plus
  either a map at position 2 or a registered resource id at position 1.
  Structural 3-vectors such as `:owner [:app :l 1]` must ride verbatim, and
  params can legally be a vector, scalar or nil, so the registry proof is
  needed beside the map test. A non-map-params key of an unregistered owner is
  left to the walk, which still tokenizes every map inside it."
  [v]
  (and (scoped-key-frame? v)
       (or (map? (nth v 2))
           (some? (rf.resources.registry/resource-meta (nth v 1))))))

(defn- project-unknown-slot-value
  "The shape-driven fail-closed projection of a tag value under a slot the
  vocabulary does not name. Returns `[projected sensitive?]`. A token rides; a
  scoped key projects as a named slot's would; a map tokenizes; any other
  collection is walked member-wise preserving its kind (list and vector params
  are distinct keys, and `:tags` rides as a set); a scalar rides. So no map
  under an unrecognised tag egresses raw at any depth, whichever slot nobody
  has enumerated yet."
  [value frame-id]
  (cond
    (redacted-token? value)   [value true]
    (scoped-key-shape? value) (project-trace-scoped-key value frame-id)
    (map? value)              [(rf.resources.ssr/redact-value value) true]

    (coll? value)
    (let [sensitive-found? (volatile! false)
          project-value    (fn [nested-value]
                             (let [[projected-value sensitive?]
                                   (project-unknown-slot-value nested-value frame-id)]
                               (when sensitive? (vreset! sensitive-found? true))
                               projected-value))]
      [(cond
         (set? value)    (into #{} (map project-value) value)
         (vector? value) (mapv project-value value)
         ;; `mapv` would erase the list/vector distinction.
         (seq? value)    (apply list (map project-value value))
         :else           (mapv project-value value))
       @sensitive-found?])

    :else [value false]))


(defn- family-named-key?
  "Whether map key `k` is reserved for one of the family's scoped keys: any
  `resource`-namespaced keyword, plus `:rf.reply/resource-key`. A fidelity
  arm: it keeps the fail-closed unregistered-owner arm reachable inside a
  carrier. Conventions §Reserved namespaces (framework-owned)."
  [k]
  (and (keyword? k)
       (or (= "resource" (namespace k))
           (= :rf.reply/resource-key k))))

(defn- carrier-family-payload?
  "Whether map `m` in a foreign carrier is a payload the runtime planted,
  proved by a `resource`-namespaced key or a work-id value among its immediate
  entries. It covers the read continuation reply, the mutation execute payload
  and a read reply's `:correlation`, but not a mutation reply's `:correlation`
  (see `reply-correlation-slot`). An app's own `{:scope …}` carries neither
  proof and rides."
  [m]
  (boolean (some (fn [[k v]] (or (family-named-key? k) (family-work-id? v))) m)))

(defn- resource-reply?
  "Whether `m` is a canonical read completion reply, by its
  `:rf.reply/work-kind :resource` marker. A mutation reply is excluded because
  its path declarations redact at source."
  [m]
  (= rf.resources.reply/work-kind-resource (:rf.reply/work-kind m)))

(defn- mutation-reply-owner-redacts?
  "Whether `m` is a mutation continuation reply whose owner, named by its
  `:mutation`, makes the coarse `:sensitive?` / `:large?` claim. The claim is
  applied here, off-box, rather than at source with the path declarations,
  because the app's own `:reply-to` handler is entitled to the real values,
  as a read reply's is (`row-owner-redacts?`). An unregistered owner fails
  closed; a reply naming no owner rides."
  [m]
  (let [mid (:mutation m)]
    (and (= rf.resources.reply/work-kind-mutation (:rf.reply/work-kind m))
         (keyword? mid)
         (not= :serialize (if-let [spec (rf.resources.mutation-registry/mutation-meta mid)]
                            (rf.resources.classification/whole-entry-disposition spec)
                            :redact)))))

(def ^:private family-reply-kinds
  "The family's two reply work kinds. Enumerated, because other families (HTTP)
  stamp `:rf.reply/work-kind` too and classify their own replies."
  #{rf.resources.reply/work-kind-resource
    rf.resources.reply/work-kind-mutation})

(defn- family-reply?
  "Whether `m` is a read or mutation completion reply. The envelope arms use
  this wider proof because no source-side redaction reaches `:error` on either
  half."
  [m]
  (boolean (family-reply-kinds (:rf.reply/work-kind m))))

(defn- feed-reply-spec
  "`spec` with its data declaration read the way an infinite feed's reply
  carries the data: as the merged item list, not the page the owner declared
  against. Through a keyword accessor, a data path through the accessor key is
  read both with and without it (the merged list does not say which page kind
  it came from); through a callable accessor, a data declaration covers the
  whole `:value`, which fails closed. Any other spec is returned unchanged."
  [spec]
  (let [accessor (:page->items spec)]
    (if-not (and (rf.resources.registry/infinite-resource? spec)
                 (or (keyword? accessor) (rf.resources.state/callable? accessor)))
      spec
      (let [data     (fn [p] (let [p (rf.path/normalize-concrete p)]
                               (case (first p)
                                 (:params :scope) nil
                                 :data            (subvec p 1)
                                 p)))
            through  (fn [d] (if (= accessor (first d)) [(subvec d 1) d] [d]))
            read-as  (fn [paths whole?]
                       (let [ds    (keep data paths)
                             other (vec (remove data paths))]
                         (cond
                           (empty? ds) paths
                           whole?      (conj other [:data])
                           :else       (into other
                                             (comp (mapcat through) (map #(into [:data] %)))
                                             ds))))
            callable (rf.resources.state/callable? accessor)]
        (cond-> spec
          (seq (:sensitive spec)) (update :sensitive read-as callable)
          (seq (:large spec))     (update :large read-as callable))))))

(defn- redact-reply-declarations
  "A read continuation reply with its owner's projection-relative declarations
  redacted, through the same `redact-continuation-reply` the mutation uses at
  source. Needed because a spec that declares only paths classifies
  `:serialize`, so the coarse arm misses it while the durable entry redacts the
  same bytes. Applied at egress rather than source because the app's own
  `:reply-to` handler is entitled to the decoded body. An unregistered owner
  rides here: the coarse arm has already tokenized it."
  [reply]
  (let [rid  (second (:resource/key reply))
        spec (when (keyword? rid) (rf.resources.registry/resource-meta rid))]
    (if spec
      (rf.resources.classification/redact-continuation-reply reply (feed-reply-spec spec))
      reply)))

(defn- carrier-family-value?
  "Whether a value in a foreign carrier is a scoped key the family may speak
  for: the skeleton plus proof, either `named?` (reached through the family's
  reserved vocabulary, which keeps the fail-closed arm reachable for a cleared
  owner) or a registered resource id. Shape alone is not enough here, because
  an app event vector `[:app/save :user {…}]` wears it. It reads the skeleton
  rather than `scoped-key-shape?`, whose map test would block a named key with
  non-map params."
  [v named?]
  (and (scoped-key-frame? v)
       (or named? (some? (rf.resources.registry/resource-meta (nth v 1))))))

(def ^:private fx-carrier-slot
  "FX-owned tag slots the family's data rides in: `handle-one-fx` stamps an
  effect's args under `:rf.fx/args` and `do-fx` the effect vector under
  `:rf.event/fx`, on rows the family does not own."
  #{:rf.fx/args :rf.event/fx})

(defn- project-embedded-keys
  "Projects the family's own data inside an FX carrier value `v` and leaves
  everything else untouched. Returns `[projected sensitive?]`. Unlike
  `project-unknown-slot-value` it descends maps rather than tokenizing them,
  because the carrier belongs to the FX family. `named?` is true when `v` sits
  under a `resource`-namespaced key; it resets at each map entry and is
  inherited through sequential collections.

  Each arm fires on proof that the runtime planted the datum, never on a local
  cue, and each has its own grain:
    - a scoped key (`carrier-family-value?`) projects through the owner;
    - `:scope` in a runtime payload (`carrier-family-payload?`) takes
      `project-scope` unconditionally, as does each scope in a mutation
      payload's issuance `:from-db-scopes`;
    - `:value` / `:params` of a read reply (`resource-reply?`) tokenize iff its
      owner redacts, and otherwise its declared paths substitute
      (`redact-reply-declarations`); of a mutation reply, iff its owner makes
      the coarse claim (`mutation-reply-owner-redacts?`);
    - `:error` of any family reply (`family-reply?`) tokenizes unconditionally;
    - a family reply's `:correlation` gives its `:scope` the scope rule.
  Spec 015 §Projection — applying classification at a boundary"
  [value frame-id named?]
  (cond
    (redacted-token? value) [value true]

    (carrier-family-value? value named?)
    (project-trace-scoped-key value frame-id)

    (coll? value)
    (let [sensitive-found?            (volatile! false)
          record-sensitive-projection!
          (fn [sensitive? projected-value]
            (when sensitive? (vreset! sensitive-found? true))
            projected-value)
          project-value
          (fn [nested-value nested-named?]
            (let [[projected-value sensitive?]
                  (project-embedded-keys nested-value frame-id nested-named?)]
              (record-sensitive-projection! sensitive? projected-value)))
          resource-reply-carrier? (and (map? value) (resource-reply? value))
          family-reply-carrier?   (and (map? value) (family-reply? value))
          owner-requires-redaction?
          (and (map? value)
               (or (and resource-reply-carrier? (row-owner-redacts? value frame-id))
                   (mutation-reply-owner-redacts? value)))
          ;; Declarations substitute BEFORE the walk, so the sentinels ride
          ;; out as scalars; a declaration that matched nothing leaves the row
          ;; unstamped.
          declaration-projected-value
          (if (and resource-reply-carrier? (not owner-requires-redaction?))
            (let [redacted-value (redact-reply-declarations value)]
              (if (= redacted-value value)
                value
                (record-sensitive-projection! true redacted-value)))
            value)

          runtime-payload?
          (and (map? declaration-projected-value)
               (carrier-family-payload? declaration-projected-value))
          project-map-value
          (fn [map-key map-value]
                     (cond
                       (and runtime-payload? (= :scope map-key))
                       (project-scope map-value)

                       (and runtime-payload? (= :from-db-scopes map-key) (map? map-value))
                       (into {} (map (fn [[scope-id scope]] [scope-id (project-scope scope)]))
                             map-value)

                       ;; Only `:scope` takes the scope rule; the other
                       ;; correlation facts take the walk.
                       (and family-reply-carrier?
                            (= reply-correlation-slot map-key)
                            (map? map-value))
                       (reduce-kv
                          (fn [projected-correlation correlation-key correlation-value]
                            (assoc projected-correlation correlation-key
                                   (if (= :scope correlation-key)
                                     (project-scope correlation-value)
                                     (project-value correlation-value
                                                    (family-named-key? correlation-key)))))
                          {}
                          map-value)

                       (and family-reply-carrier?
                            (reply-error-slot map-key)
                            (some? map-value))
                       (record-sensitive-projection!
                         true
                         (if (redacted-token? map-value)
                           map-value
                           (rf.resources.ssr/redact-value map-value)))

                       (and owner-requires-redaction?
                            (reply-payload-slot map-key)
                            (some? map-value))
                       (record-sensitive-projection!
                         true
                         (if (redacted-token? map-value)
                           map-value
                           (rf.resources.ssr/redact-value map-value)))

                       :else
                       (project-value map-value (family-named-key? map-key))))]
      [(cond
         (map? declaration-projected-value)
         (reduce-kv
           (fn [projected-map map-key map-value]
             (assoc projected-map
                    (project-value map-key named?)
                    (project-map-value map-key map-value)))
           {}
           declaration-projected-value)

         (set? declaration-projected-value)
         (into #{} (map #(project-value % named?)) declaration-projected-value)

         (vector? declaration-projected-value)
         (mapv #(project-value % named?) declaration-projected-value)

         (seq? declaration-projected-value)
         (apply list (map #(project-value % named?) declaration-projected-value))

         :else
         (mapv #(project-value % named?) declaration-projected-value))
       @sensitive-found?])

    :else [value false]))

(declare project-tags*)

(defn- project-disposition-row
  "Projects one disposition row: `:resource/key` as a scoped key, every other
  slot by the unknown-slot rule. Returns `[projected-row sensitive?]`; a
  non-map row rides."
  [row frame-id]
  (if-not (map? row)
    [row false]
    (reduce-kv
      (fn [[projected-row sensitive-found?] slot value]
        (let [[projected-value sensitive?]
              (if (= :resource/key slot)
                (project-trace-scoped-key value frame-id)
                (project-unknown-slot-value value frame-id))]
          [(assoc projected-row slot projected-value)
           (or sensitive-found? (boolean sensitive?))]))
      [{} false]
      row)))

(defn- project-tags*
  "Slot-keyed projection of `tags`, returning `[tags' sensitive?]` without
  stamping (the caller stamps once). Recurses into nested-map slots."
  [tags frame-id]
  (if-not (map? tags)
    [tags false]
    (let [sensitive-found?  (volatile! false)
          record-sensitive! (fn [sensitive?]
                              (when sensitive?
                                (vreset! sensitive-found? true)))
          cursor-redacts? (row-owner-redacts? tags frame-id)
          projected-tags
          (reduce-kv
            (fn [projected-tags tag-name tag-value]
              (cond
                (and (scoped-key-slot tag-name) (some? tag-value))
                (let [[projected-key sensitive?]
                      (project-trace-scoped-key tag-value frame-id)]
                  (record-sensitive! sensitive?)
                  (assoc projected-tags tag-name projected-key))

                (= scope-slot tag-name)
                (assoc projected-tags tag-name (project-scope tag-value))

                (and (scoped-keys-slot tag-name) (sequential? tag-value))
                (assoc projected-tags tag-name
                       (mapv (fn [scoped-key]
                               (let [[projected-key sensitive?]
                                     (project-trace-scoped-key scoped-key frame-id)]
                                 (record-sensitive! sensitive?)
                                 projected-key))
                             tag-value))

                (and (disposition-rows-slot tag-name) (sequential? tag-value))
                (assoc projected-tags tag-name
                       (mapv (fn [row]
                               (let [[projected-row sensitive?]
                                     (project-disposition-row row frame-id)]
                                 (record-sensitive! sensitive?)
                                 projected-row))
                             tag-value))

                (and (nested-map-slot tag-name) (map? tag-value))
                (let [[projected-map sensitive?] (project-tags* tag-value frame-id)]
                  (record-sensitive! sensitive?)
                  (assoc projected-tags tag-name projected-map))

                ;; A token must not reach `redact-value`, which would re-digest it.
                (and (cursor-slot tag-name) (some? tag-value))
                (if (redacted-token? tag-value)
                  (do (record-sensitive! true)
                      (assoc projected-tags tag-name tag-value))
                  (if cursor-redacts?
                    (do (record-sensitive! true)
                        (assoc projected-tags tag-name (rf.resources.ssr/redact-value tag-value)))
                    (assoc projected-tags tag-name tag-value)))

                (and (error-envelope-slot tag-name) (some? tag-value))
                (if (redacted-token? tag-value)
                  (do (record-sensitive! true)
                      (assoc projected-tags tag-name tag-value))
                  (do (record-sensitive! true)
                      (assoc projected-tags tag-name (rf.resources.ssr/redact-value tag-value))))

                ;; Before `sibling-owned-slot`, so a pre-redacted `:input-values`
                ;; keeps the row's `:sensitive?` stamp.
                (redacted-token? tag-value)
                (do (record-sensitive! true)
                    (assoc projected-tags tag-name tag-value))

                (sibling-owned-slot tag-name)
                (assoc projected-tags tag-name tag-value)

                ;; Shape-driven fail-closed default. A named slot is not always a
                ;; key vector: `:rf.resource/route-plan` carries `:removed` as an
                ;; int count, which fails the named arm's `sequential?` test and
                ;; rides here as the scalar it is.
                :else
                (let [[projected-value sensitive?]
                      (project-unknown-slot-value tag-value frame-id)]
                  (record-sensitive! sensitive?)
                  (assoc projected-tags tag-name projected-value))))
            {}
            tags)]
      [projected-tags @sensitive-found?])))

(defn project-resource-trace-egress
  "Projects a resource or mutation trace row's `tags` for off-box egress:
  every scoped key by `project-trace-scoped-key`, the free `:scope` by
  `project-scope`, the cursor by the row's owner, failure envelopes
  unconditionally, unnamed slots by shape, nested-map slots recursively. Work
  ids and structural scalars ride. Stamps `:sensitive? true` when any owner
  claim redacted. Idempotent; a non-map `tags` rides.
  Spec 015 §Subsystem projection-relative classification."
  [tags frame-id]
  (if-not (map? tags)
    tags
    (let [[projected-tags sensitive?] (project-tags* tags frame-id)]
      (cond-> projected-tags sensitive? (assoc :sensitive? true)))))

(defn project-fx-args-egress
  "Projects the `:rf.fx/args` / `:rf.event/fx` slots of any trace row with
  `project-embedded-keys`, so the family's data riding an FX row classifies
  exactly as it does on the family's own rows; otherwise one record could carry
  the same secret redacted in one slot and raw in the next. Everything else on
  the row rides, and a tags map with neither slot is returned as-is, so the
  epoch consumer applies it to every row. Stamps `:sensitive? true` when
  anything redacted. Idempotent.
  Spec 015 §Projection — applying classification at a boundary"
  [tags frame-id]
  (if-not (map? tags)
    tags
    (let [sensitive-found? (volatile! false)
          projected-tags
          (reduce (fn [projected-tags carrier-slot]
                    (if-not (contains? projected-tags carrier-slot)
                      projected-tags
                      (let [[projected-value sensitive?]
                            (project-embedded-keys
                              (get projected-tags carrier-slot)
                              frame-id
                              false)]
                        (when sensitive?
                          (vreset! sensitive-found? true))
                        (assoc projected-tags carrier-slot projected-value))))
                  tags
                  fx-carrier-slot)]
      (cond-> projected-tags @sensitive-found? (assoc :sensitive? true)))))

;; ---- read subs on `:rf.sub/run` -------------------------------------------
;;
;; A read sub returns a bare projection of its owner's entry or instance, so
;; no runtime-db path reaches it; core's sub-trace chokepoint calls
;; `project-read-sub-egress` through the `:resources/project-resource-sub-egress`
;; hook, at emit.
;;
;; The declaration is read from the owner SPEC, not the live instances,
;; because evicting an entry removes it while a held sub still carries the old
;; value as `:rf.sub/prev-value`. The claims the value's own inputs carry are
;; unioned in — the frame's live classification for a live value, and for a
;; prior one the claims the policy it was computed under makes on the
;; runtime-db it was computed from — so an owner re-registered without its
;; declaration does not ship data loaded under it raw. A coarse claim is that
;; policy's claim on each entry value slot the value carries, so it is
;; recovered the same way. In-process `@(subscribe …)` stays raw.

(def ^:private slot-roots
  "Where each read sub's value carries its owner's slots, by slot (`[]`: the
  value is the slot). `:data` is the owner's data projection, which its path
  declarations and its coarse claim cover. A resource view-model — the one
  `:rf/resource` and the route-bound `:rf/route-resource` both return — also
  carries the entry's other value slots under their own keys, and a focused
  error read is one of them; the coarse whole-entry claim alone covers those
  (`entry-value-slots`), and a slot a value does not hold is never walked. A
  mutation's `:data` is its `:result`, and the `:rf/mutation` view-model
  carries its instance's other value slots the same way
  (`mutation-instance-value-slots`). A feed's merged item list is projected
  by `project-feed-items` instead, so `:rf.resource/items` has no data root."
  (let [slot-of     (fn [data-slot]
                      (comp (remove #{data-slot}) (map (fn [slot] [slot [[slot]]]))))
        entry-slots (into {} (slot-of :data)
                          rf.resources.classification/entry-value-slots)
        view-model  (assoc entry-slots :data [[:data] [:previous-data]])
        mutation-vm (assoc (into {} (slot-of :result)
                                 rf.resources.classification/mutation-instance-value-slots)
                           :data [[:result]])]
    {:rf/resource                view-model
     :rf/route-resource          view-model
     :rf.resource/data           {:data [[]]}
     :rf.resource/previous-data  {:data [[]]}
     :rf.resource/error          {:error [[]]}
     :rf.resource/refresh-error  {:refresh-error [[]]}
     :rf.resource/page-error     {:page-error [[]]}
     :rf.resource/items          {:data []}
     :rf.resource/pages          {:data [[]]}
     :rf.resource/infinite-state (assoc entry-slots :data [[:pages]])
     :rf/mutation                mutation-vm
     :rf.mutation/result         {:data [[]]}
     :rf.mutation/error          {:error [[]]}}))

(def ^:private mutation-subs
  "The mutation read subs; they name an instance, not a mutation."
  #{:rf/mutation :rf.mutation/result :rf.mutation/error})

(defn- owner-payload
  "A read sub's payload as the owner reference the projection reads. The
  route-bound read names its resource by bare id, because the active route's
  plan supplies the identity; one spec declares every entry of a resource, so
  the id alone names the owner."
  [sub-id payload]
  (if (= :rf/route-resource sub-id) {:resource payload} payload))

(defn- live-runtime-db
  "`frame-id`'s runtime-db, read untracked: the projector runs inside the
  sub's compute, and a capturing read would make the runtime-db an input of
  the sub it describes."
  [frame-id]
  (when-let [container (rf.frame/runtime-db-container frame-id)]
    (rf.substrate.adapter/read-container-untracked container)))

(defn- live-frame-state
  "`frame-id`'s frame-state, read untracked for the same reason."
  [frame-id]
  (when-let [container (rf.frame/frame-state-container frame-id)]
    (rf.substrate.adapter/read-container-untracked container)))

(defn- owner-spec
  "The owner spec a read sub's payload names, or nil. A mutation sub's owner
  is read off its instance in the `runtime-db` thunk."
  [sub-id payload runtime-db]
  (if (contains? mutation-subs sub-id)
    (when-let [inst (get-in (runtime-db)
                            (rf.resources.mutation-runtime/instance-path (:instance payload)))]
      (rf.resources.mutation-registry/mutation-meta (:mutation/id inst)))
    (when-some [resource-id (:resource payload)]
      (rf.resources.registry/resource-meta resource-id))))

(defn- slot-paths
  "The claims on the owner's `slot`, re-rooted under each of `roots`: the
  spec's data declaration when `slot` is `:data` (no other slot takes a path),
  its coarse claim as the whole slot, and the `recovered` claims —
  `[sensitive-paths large-paths]`."
  [spec slot [r-sens r-large] roots]
  (let [marks  (when (= :data slot)
                 (:data (rf.resources.classification/spec-declaration-marks spec)))
        sens   (cond-> (into (vec (keys (:sensitive marks))) r-sens) (:sensitive? spec) (conj []))
        large  (cond-> (into (vec (keys (:large marks))) r-large) (:large? spec) (conj []))
        rebase (fn [paths] (into [] (for [root roots p (distinct paths)] (into root p))))]
    [(rebase sens) (rebase large)]))

(defn- prior-runtime-db
  "The runtime-db a prior value's inputs carry: a mutation sub's input is the
  runtime-db, a resource sub's the frame-state around it."
  [prior-inputs]
  (let [in (first prior-inputs)]
    (if (and (map? in) (or (contains? in :rf.db/runtime) (contains? in :rf.db/app)))
      (:rf.db/runtime in)
      in)))

(defn- registry-claims
  "The claims in classification value `registry` on paths `rel` maps to a
  relative path (nil for uncovered): `[sensitive-paths large-paths]`."
  [registry rel]
  (let [claims (fn [decls] (into [] (keep #(rel (vec %))) (keys decls)))]
    [(claims (:sensitive-declarations registry)) (claims (:declarations registry))]))

(defn- inputs-registry
  "The classification a read sub's value was computed under: the frame's live
  classification for a live value, else the claims `prior-policy` makes on the
  runtime-db in `prior-inputs`."
  [frame live? prior-inputs prior-policy]
  (if live?
    {:sensitive-declarations (rf.elision/sensitive-declarations frame)
     :declarations           (rf.elision/declarations frame)}
    (let [runtime-db (prior-runtime-db prior-inputs)]
      (rf.elision/with-runtime-classification nil [prior-policy] runtime-db))))

(defn- under
  "A `rel` for `registry-claims`: a path under `prefix`, with `prefix` stripped."
  [prefix]
  (let [n (count prefix)]
    (fn [p] (when (and (>= (count p) n) (= prefix (subvec p 0 n))) (subvec p n)))))

(defn- recovered-claims
  "The `slot`-relative claims `registry` holds for a read sub's owner: `slot`
  of its mutation instance (`:data` being the `:result`), or `slot` of every
  entry of its resource (one spec declares them all). `runtime-db` holds the
  entries the claims name."
  [sub-id payload registry runtime-db slot]
  (if (contains? mutation-subs sub-id)
    (registry-claims registry
                     (under (conj (rf.resources.mutation-runtime/instance-path (:instance payload))
                                  (if (= :data slot) :result slot))))
    (let [entries (rf.resources.state/entries-path)
          n       (count entries)
          table   (get-in runtime-db entries)
          owner   (:resource payload)]
      (registry-claims registry
                       (fn [p]
                         (when (and (>= (count p) (+ n 2))
                                    (= entries (subvec p 0 n))
                                    (= slot (nth p (inc n)))
                                    (= owner (second (:resource/key (get table (nth p n))))))
                           (subvec p (+ n 2))))))))

(defn- project-merged-items
  "Projects a feed's merged item list under the owner's page-relative data
  declaration. `pages` is a thunk of the raw pages. The declaration redacts the
  pages, which are then merged by `merge-pages->items`'s rule. With no accessor,
  or a declaration covering the whole projection, the list is redacted directly.
  When the pages cannot be read, do not merge to `items`, or no longer merge to
  a list, the whole list redacts, because guessing where a declared field went
  could miss one."
  [items spec recovered resource-id pages]
  (let [[sens large] (slot-paths spec :data recovered [[]])
        accessor     (rf.resources.state/resolve-page->items (:page->items spec))]
    (cond
      (and (empty? sens) (empty? large))
      items

      (or (nil? accessor) (some empty? sens) (some empty? large))
      (redact-anchored :data items sens large)

      :else
      (or (try
            (let [raw (pages)]
              (when (and (vector? raw)
                         (= items (rf.resources.state/merge-pages->items
                                    raw accessor resource-id 'rf.resource/items)))
                (let [page-items (mapv #(if (vector? %) % (accessor %))
                                       (redact-anchored :data raw sens large))]
                  (when (every? #(or (nil? %) (and (coll? %) (not (map? %)))) page-items)
                    (into [] cat page-items)))))
            (catch #?(:clj Throwable :cljs :default) _ nil))
          rf.privacy/redacted-sentinel))))

(defn- project-feed-items
  "Projects the merged item list in `:rf.resource/items` (the whole value) or
  `:rf.resource/infinite-state` (`:items`); any other sub's value is returned
  unchanged. The items sub carries no pages, so they are read through
  `rf.resources.subs/pages-sub-fn` over the `frame-state` thunk."
  [sub-id value spec recovered query-v frame-state]
  (let [resource-id (:resource (second query-v))]
    (case sub-id
      :rf.resource/items
      (project-merged-items value spec recovered resource-id
                            #(rf.resources.subs/pages-sub-fn (frame-state) query-v))

      :rf.resource/infinite-state
      (if (map? value)
        (update value :items project-merged-items spec recovered resource-id #(:pages value))
        value)

      value)))

(defn- project-key-slot
  "Project the scoped key(s) under `slot` of `value` — one key, or a vector of
  them when `many?` — keeping `value` identical when nothing changes."
  [value slot many? frame-id]
  (let [ks (get value slot)]
    (if (or (nil? ks) (and many? (empty? ks)))
      value
      (let [project (fn [k] (first (project-trace-scoped-key k frame-id)))
            ks'     (if many? (into (empty ks) (map project) ks) (project ks))]
        (if (= ks ks') value (assoc value slot ks'))))))

(defn- project-keys
  [sub-id value frame-id]
  (if-not (map? value)
    value
    (case sub-id
      (:rf/resource :rf/route-resource) (project-key-slot value :previous-key false frame-id)
      :rf/mutation (project-key-slot value :affected-keys true frame-id)
      value)))

(defn- whole-value-slot
  "The slot a read sub's value is, when its `slots` say the value is one slot
  whole, else nil."
  [slots]
  (when (= 1 (count slots))
    (let [[slot roots] (first slots)]
      (when (= [[]] roots) slot))))

(defn project-read-sub-egress
  "Projects a resource or mutation read sub's `value` for egress: the owner's
  data declaration under each data root, its coarse claim over every owner
  slot the value carries, a feed's merged items through its accessor, and the
  scoped keys in `:previous-key` / `:affected-keys`. `query-v` names the
  owner; `opts` carries `:frame`. The five-arity projects a PRIOR value
  against the `prior-inputs` it was computed from and the `prior-policy` it
  was computed under. Any other query returns `value` unchanged; a value with
  nothing to project rides identical."
  ([query-v value opts]
   (project-read-sub-egress query-v value opts ::live nil))
  ([query-v value {:keys [frame]} prior-inputs prior-policy]
   (let [sub-id (when (sequential? query-v) (first query-v))
         slots  (get slot-roots sub-id)]
     (if (nil? slots)
       value
       (let [payload     (owner-payload sub-id (second query-v))
             live?       (or (= ::live prior-inputs) (nil? prior-inputs))
             inputs-db   (if live? (live-runtime-db frame) (prior-runtime-db prior-inputs))
             inputs-reg  (when inputs-db
                           (inputs-registry frame live? prior-inputs prior-policy))
             runtime-db  #(if live? (live-runtime-db frame) (first prior-inputs))
             frame-state #(if live? (live-frame-state frame) (first prior-inputs))
             spec        (when (map? payload) (owner-spec sub-id payload runtime-db))
             recovered   (when (and inputs-db (map? payload))
                           (into {}
                                 (map (fn [slot]
                                        [slot (recovered-claims sub-id payload inputs-reg
                                                                inputs-db slot)]))
                                 (keys slots)))
             value       (if (or spec (some #(some seq %) (vals recovered)))
                           (let [projected    (project-feed-items sub-id value spec (:data recovered)
                                                                  query-v frame-state)
                                 [sens large] (reduce-kv
                                                (fn [[sens large] slot roots]
                                                  (let [[s l] (slot-paths spec slot (get recovered slot)
                                                                          roots)]
                                                    [(into sens s) (into large l)]))
                                                [[] []]
                                                slots)]
                             ;; A whole-value projection anchors the walk at the
                             ;; slot the owner declared against.
                             (if-let [slot (whole-value-slot slots)]
                               (redact-anchored slot projected sens large)
                               (rf.classification/redact-with-paths
                                 projected sens large {:index-free? true})))
                           value)]
         (project-keys sub-id value frame))))))
