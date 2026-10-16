(ns re-frame.source-store
  "The registration table (Spec 001 §Registry model, EP-0023 §Registration
  Source Store): every registration the process holds, in ONE atom.

  ## Shape

      kind → id → provenance-namespace → descriptor

  The provenance level is what separates a hot reload from a collision:

      same kind + id + same namespace       → replacement in that slot
                                               (the ordinary hot-reload path)
      same kind + id + different namespace   → BOTH descriptors retained
                                               (the image-isolation path)

  Each descriptor carries its own source coordinates (the `:ns` / `:file` /
  `:line` keys in dev, and `re-frame.source-coords/registration-coords` in every
  build) and a monotonic registration sequence number
  ([[registration-seq]]). The sequence number is assigned inside the same
  `swap!` that writes the descriptor — the counter rides the table value's
  metadata — so the order and the entries publish together.

  ## Who reads it

  The table makes no assembly or selection decision of its own. Its readers
  each project it:

    * a frame made without `:images` resolves it live, refusing an id more
      than one namespace registers (`re-frame.live-frame/live-view`) — and the
      `{:source :store}` registrar queries answer the same way;
    * image assembly selects sealed generations from it
      (`re-frame.image-assembly`);
    * a lookup with no frame resolution bound — the test fixture's ambient
      `:rf/default`, the REPL — takes the descriptor with the highest
      registration sequence number ([[latest]]), so the latest registration of
      an id wins there.

  ## `:rf.provenance/ns` — the canonical namespace string

  Per EP-0023 §Relationship To The Earlier Tag-And-Scan Rejection, every
  `reg-*` descriptor carries `:rf.provenance/ns` as a **canonical string** —
  the source-code namespace the registration was authored in. It is a
  production descriptor field, not optional debug metadata: `:include-ns`
  selection (`re-frame.image`) reads it, equality is ordinary string equality.

  The EP's implementation requirement is:

      one namespace string value per source namespace per registration source
      store, not one independently allocated namespace string per registration
      descriptor

  This namespace interns the string through [[canonical-ns]] (a string pool),
  so all descriptors authored in `\"docs.quickstart.counter.v2\"` share ONE
  string instance rather than each allocating its own copy of the same bytes.
  `:rf.provenance/ns` stays an ordinary string at the public descriptor
  surface — no numeric ns-ids, no second descriptor key (which would leak the
  pool table into the public model, contra the EP)."
  (:require [re-frame.source-coords :as rf.source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the provenance key ---------------------------------------------------

(def provenance-ns-key
  "The descriptor key under which the canonical source namespace string is
  stored: `:rf.provenance/ns` (EP-0023). Public — tools read a string here,
  equality is ordinary string equality. Reserved in
  [`spec/Conventions.md` §The single-root reserved set](../../../../spec/Conventions.md)."
  :rf.provenance/ns)

;; ---- the table ------------------------------------------------------------

(defonce
  ^{:doc "kind → id → provenance-ns-string → descriptor. Atomic. The one
  registration table: every `reg-*` writes here, and every registration read
  projects it. The value's metadata carries the last registration sequence
  number assigned."}
  kind->id->ns->descriptor
  (atom {}))

;; ---- the registration sequence number -------------------------------------
;;
;; Rides each descriptor as Clojure METADATA rather than as a key, for the
;; reason the registration coordinate does (`re-frame.source-coords`): it takes
;; no part in descriptor equality, so re-registering an identical descriptor
;; leaves every generation assembled from it `=` to the one before, and it
;; never surfaces through `rf/handler-meta`.

(defn registration-seq
  "The registration sequence number `descriptor` was recorded with — higher is
  later — or nil for a descriptor the table did not record. Pure."
  [descriptor]
  (get (meta descriptor) ::seq))

(defn latest
  "The descriptor of a `provenance-ns → descriptor` entry recorded last (the
  highest [[registration-seq]]), or nil for an empty entry. Pure."
  [ns->desc]
  (if (< (count ns->desc) 2)
    (some-> (first ns->desc) val)
    (val (apply max-key #(or (registration-seq (val %)) 0) ns->desc))))

;; ---- the store GENERATION signal (EP-0023 §Image — resolved-generation cache)
;;
;; A MONOTONIC counter bumped on every mutation of the table. The
;; resolved-generation cache (`re-frame.image-assembly`) folds it into its cache
;; key, so a sealed generation is reused only while the registration pool it was
;; assembled from is unchanged. It is kept apart from the registration sequence
;; number because a restore moves the table back to an earlier value, and the
;; cache key must still move forward.

(defonce ^:private generation
  (atom 0))

(defn- bump-generation!
  []
  (swap! generation inc)
  nil)

(defn store-generation
  "The MONOTONIC generation integer of the table (EP-0023 §Image — the
  resolved-generation cache's source-store-generation key leg). Starts at 0 and
  increments on every `reg-*` / `forget-*` / `clear-*` / [[restore!]]. Equal
  generation across two reads ⇒ the registration pool has not changed between
  them, so a sealed image generation assembled at the first may be reused."
  []
  @generation)

(defn store-identity
  "The table atom itself — the resolved-generation cache's store-identity key
  leg, paired with [[store-generation]]. The cache holds it as an opaque key
  leg and never dereferences it."
  []
  kind->id->ns->descriptor)

;; ---- canonical namespace-string pooling -----------------------------------
;;
;; One string instance per source namespace (the EP's anti-tax requirement).
;; The pool is keyed by the string value; `canonical-ns` returns the pooled
;; instance so every descriptor from the same namespace shares it. This does
;; not leak across the public boundary — `:rf.provenance/ns` is still just a
;; string.

(defonce ^:private ns-string-pool
  (atom {}))

(defn canonical-ns
  "Return the canonical (pooled) namespace string for `ns-ref`. `ns-ref` may be
  a symbol (the `:ns` slot a reg-* macro captures — see
  `re-frame.source-coords`), a string, or nil.

  Returns nil for nil / blank input (a programmatic / REPL registration that
  bypassed the macro path carries no namespace provenance). Otherwise returns
  ONE shared string instance per distinct namespace, per the EP-0023
  one-string-per-namespace requirement — repeated calls for the same namespace
  return the identical pooled string, so descriptors do not each allocate their
  own copy of the same bytes."
  [ns-ref]
  (let [s (cond
            (nil? ns-ref)    nil
            (string? ns-ref) ns-ref
            :else            (str ns-ref))]
    (when (and s (not= "" s))
      (or (get @ns-string-pool s)
          (get (swap! ns-string-pool
                      (fn [pool]
                        (if (contains? pool s)
                          pool
                          (assoc pool s s))))
               s)))))

;; ---- recording ------------------------------------------------------------

(defn provenance-ns
  "The canonical source namespace string `descriptor` records under, or nil
  for a registration with no namespace provenance. Resolution order is the one
  [[record-descriptor!]] documents: an explicit `:rf.provenance/ns`, then the
  descriptor's `:ns` slot, then the `:ns` of the live
  `rf.source-coords/*pending-coords*` binding (the production-surviving path)."
  [descriptor]
  (canonical-ns (or (get descriptor provenance-ns-key)
                    (:ns descriptor)
                    (:ns rf.source-coords/*pending-coords*))))

(defn record-descriptor!
  "Record `descriptor` for `(kind, id)` under its source namespace, returning
  the descriptor as stored: `:kind` / `:id` stamped, `:rf.provenance/ns`
  stamped as a canonical string, and the next registration sequence number
  attached ([[registration-seq]]).

  Provenance namespace resolution, in order:
    1. an explicit `:rf.provenance/ns` already on the descriptor (a tool or
       generated-code path that stamps its own provenance), canonicalized;
    2. otherwise the descriptor's `:ns` slot (the symbol a reg-* macro
       captured), canonicalized to a string;
    3. otherwise the `:ns` of the live `rf.source-coords/*pending-coords*`
       binding, canonicalized.

  Step 3 is the PRODUCTION-ELISION path. The descriptor's `:ns`
  slot (step 2) only survives in DEV: a reg-* macro captures coords into
  `*pending-coords*`, and the registration fn folds them into the stored
  metadata via `rf.source-coords/merge-coords` — but in CLJS production
  (`:advanced` + `goog.DEBUG=false`) `merge-coords` returns the user metadata
  UNCHANGED, stripping `:ns` from the descriptor (consistent with `:file` /
  `:line` / `:doc` elision). The `*pending-coords*` BINDING itself survives
  production (the reg-* macro emits `prod-coords-form`, which keeps `:ns`), so
  reading the namespace off the live binding is the path that survives
  optimized builds — `:rf.provenance/ns` must survive elision or `:include-ns`
  image assembly cannot work (EP-0023 §Namespace-Selected Images). The table
  is written from `register!` while the macro's `*pending-coords*` binding is
  still in flight, so the binding is live at this call.

  When none of the three yields a namespace (a programmatic / REPL
  registration with no macro-captured `:ns`), the descriptor is recorded under
  the nil-provenance slot. Nil-provenance descriptors still occupy ONE slot per
  `(kind, id)` (they cannot be image-isolation-distinguished — they have no
  namespace), so a later programmatic re-register of the same `(kind, id)`
  replaces it.

  `:kind` and `:id` are the table KEY, so the stamp is deterministic — and image
  assembly reads `(:kind descriptor)` / `(:id descriptor)`
  (`descriptor-kind+id`, `check-supported-kinds!`), so a descriptor selected by
  `:include-ns` MUST carry them."
  [kind id descriptor]
  (let [pn    (provenance-ns descriptor)
        ;; When there is no provenance (programmatic path), leave the key off
        ;; rather than store a nil — absence is the honest signal "no source
        ;; namespace".
        desc  (cond-> (assoc descriptor :kind kind :id id)
                pn (assoc provenance-ns-key pn))
        table (swap! kind->id->ns->descriptor
                     (fn [table]
                       (let [n (inc (get (meta table) ::seq 0))]
                         (-> table
                             (assoc-in [kind id pn] (vary-meta desc assoc ::seq n))
                             (vary-meta assoc ::seq n)))))]
    (bump-generation!)
    (-> table (get kind) (get id) (get pn))))

;; ---- queries --------------------------------------------------------------
;;
;; Read surfaces for image assembly, the registrar and tests. None of these
;; make a selection decision — they expose the retained, provenance-tagged
;; descriptors as data.

(defn descriptors-for
  "Return the `provenance-ns-string → descriptor` map for `(kind, id)`, or `{}`
  when none. The map has more than one entry exactly when two or more
  namespaces registered the same `(kind, id)` — the image-isolation case the
  table preserves. The nil-provenance descriptor (if any) is under the `nil`
  key."
  [kind id]
  (-> @kind->id->ns->descriptor (get kind) (get id) (or {})))

(defn descriptor-for
  "Return the descriptor for the exact `(kind, id, provenance-ns)` slot, or nil.
  `provenance-ns` is canonicalized so callers may pass a symbol or string."
  [kind id provenance-ns]
  (-> (descriptors-for kind id) (get (canonical-ns provenance-ns))))

(defn latest-descriptor
  "The descriptor of `(kind, id)` recorded last ([[latest]]), or nil."
  [kind id]
  (latest (-> @kind->id->ns->descriptor (get kind) (get id))))

(defn all-descriptors
  "Return every retained descriptor for `kind` as a flat seq across all
  `(id, provenance-ns)` slots, or an empty seq when the kind has none. Image
  assembly selects from this set by `:include-ns` and validates
  collisions; this fn makes no such decision."
  [kind]
  (for [[_id ns->desc] (get @kind->id->ns->descriptor kind)
        [_ns desc]     ns->desc]
    desc))

(defn kinds-present
  "Return the set of kinds that currently have any recorded descriptor."
  []
  (-> @kind->id->ns->descriptor keys set))

;; ---- removal / reset ------------------------------------------------------
;;
;; Every removal keeps the table's metadata, so the registration sequence never
;; restarts under descriptors that are still there.

(defn- drop-empty-shells
  "Drop the `(kind id)` and `kind` maps a removal left empty, so queries see a
  clean map."
  [table kind id]
  (let [table (if (empty? (-> table (get kind) (get id)))
                (update table kind dissoc id)
                table)]
    (if (empty? (get table kind))
      (dissoc table kind)
      table)))

(defn forget-descriptor!
  "Remove the slot for the exact `(kind, id, provenance-ns)`.
  `provenance-ns` is canonicalized; nil removes the nil-provenance slot only.
  Leaves any sibling-namespace descriptors for the same `(kind, id)` intact."
  [kind id provenance-ns]
  (let [pn (canonical-ns provenance-ns)]
    (swap! kind->id->ns->descriptor
           (fn [table]
             (if (contains? (-> table (get kind) (get id)) pn)
               (-> table
                   (update-in [kind id] dissoc pn)
                   (drop-empty-shells kind id))
               table)))
    (bump-generation!)
    nil))

(defn forget-id!
  "Remove every provenance slot for `(kind, id)` across all namespaces."
  [kind id]
  (swap! kind->id->ns->descriptor
         (fn [table]
           (let [table (update table kind dissoc id)]
             (if (empty? (get table kind))
               (dissoc table kind)
               table))))
  (bump-generation!)
  nil)

(defn clear-kind!
  "Remove every `(id, provenance-ns)` slot for `kind` and BUMP the generation.

  The bump is load-bearing: the resolved-image-generation cache
  (`re-frame.image-assembly`) keys sealed generations on [[store-generation]],
  so without it a later `assemble` / `assemble-default` for the same image
  vector would HIT the cache and return a stale generation that still resolves
  the just-cleared `(kind, id)`s (EP-0023 §Image — resolved-generation cache)."
  [kind]
  (swap! kind->id->ns->descriptor dissoc kind)
  (bump-generation!)
  nil)

(defn restore!
  "Reset the table to `snapshot`, a value of [[kind->id->ns->descriptor]] taken
  earlier, and bump the generation. Registrations recorded since the snapshot
  are gone and registrations removed since it are back; the registration
  sequence continues from the snapshot's."
  [snapshot]
  (reset! kind->id->ns->descriptor snapshot)
  (bump-generation!)
  nil)

(defn clear-all!
  "Empty the table and the namespace-string pool. Test fixtures use this
  between cases, through `registrar/clear-all!`. Pool entries are harmless to
  retain in production (the pool is never reset there)."
  []
  (reset! kind->id->ns->descriptor {})
  (reset! ns-string-pool {})
  (bump-generation!)
  nil)
