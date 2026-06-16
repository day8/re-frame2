(ns re-frame.source-store
  "The provenance-preserving registration source store (EP-0023 §Registration
  Source Store, the foundation slice rf2-32siq3.2).

  ## Why a source store exists

  Spec 001's historical registrar is a `(kind, id) → descriptor` resolver map:
  the *last* `reg-*` for a `(kind, id)` clobbers any earlier one, regardless of
  which namespace authored it. That last-write-wins rule is correct for the
  hot-reload case (a namespace re-evaluates and replaces its own registration)
  but wrong for the image-isolation case (two surfaces both register
  `[:event :boot/init]` with different meanings) — it silently drops one.

  EP-0023 partially supersedes that rule. `reg-*` still updates the resolver
  map (the default-image runtime path, unchanged here), but it ALSO writes to
  this provenance-preserving source store, keyed one slot deeper:

      source slot = [kind id provenance-namespace]

  That key gives exactly the two behaviours the EP requires:

      same kind + id + same namespace       → replacement in that source slot
                                               (the ordinary hot-reload path)
      same kind + id + different namespace   → BOTH descriptors retained
                                               (the image-isolation path)

  ## What this slice does and does NOT do

  This is the FOUNDATION slice. It builds only the store that preserves all
  provenance-tagged descriptors. It deliberately makes **no** assembly or
  selection decision — it does not project a sealed `[kind id]` resolver, does
  not run `:include-ns` globs, does not detect or reject cross-namespace
  collisions, and does not build framework-standard sets. Those are the image
  constructor (slice .3) and image assembly (slice .4). The store simply keeps
  every provenance-distinct descriptor alive so a later slice has the full set
  to select from.

  ## `:rf.provenance/ns` — the canonical namespace string

  Per EP-0023 §Relationship To The Earlier Tag-And-Scan Rejection, every
  `reg-*` descriptor carries `:rf.provenance/ns` as a **canonical string** —
  the source-code namespace the registration was authored in. It is a
  production descriptor field, not optional debug metadata: `:include-ns`
  selection (slice .3) reads it, equality is ordinary string equality.

  The EP's implementation requirement is:

      one namespace string value per source namespace per registration source
      store, not one independently allocated namespace string per registration
      descriptor

  This namespace interns the string through [[canonical-ns]] (a store-scoped
  string pool), so all descriptors authored in `\"docs.quickstart.counter.v2\"`
  share ONE string instance rather than each allocating its own copy of the
  same bytes. `:rf.provenance/ns` stays an ordinary string at the public
  descriptor surface — no numeric ns-ids, no second descriptor key (which would
  leak the pool table into the public model, contra the EP).

  ## Realm-scoped seating

  The store mirrors `re-frame.registrar`'s active-registrar pattern. The
  process-default store is [[kind->id->ns->descriptor]]; the dynamic
  [[*source-store*]] var lets an explicit-realm seating (EP-0013 stage 9, the
  same `app-value/install!` path that binds `registrar/*registrar*`) route
  `reg-*` writes into a realm's OWN source store. [[active-source-store]] is the
  single resolution point — nil binding ⇒ the process-default store, the
  byte-identical single-realm path.")

#?(:clj (set! *warn-on-reflection* true))

;; ---- the provenance key ---------------------------------------------------

(def provenance-ns-key
  "The descriptor key under which the canonical source namespace string is
  stored: `:rf.provenance/ns` (EP-0023). Public — tools read a string here,
  equality is ordinary string equality. Reserved in
  [`spec/Conventions.md` §The single-root reserved set](../../../../spec/Conventions.md)."
  :rf.provenance/ns)

;; ---- the store state ------------------------------------------------------
;;
;; Shape: kind → id → provenance-ns-string → descriptor.
;;
;; The extra `provenance-ns` level (vs the registrar's `kind → id → descriptor`)
;; is the whole point: two namespaces registering the same `(kind, id)` occupy
;; two distinct leaves, so neither clobbers the other. A re-eval of the SAME
;; namespace lands on the same leaf and replaces it (hot reload).

(defonce
  ^{:doc "kind → id → provenance-ns-string → descriptor. Atomic. The
  PROCESS-DEFAULT source store — the default realm owns it, mirroring
  `registrar/kind->id->metadata`. A single-realm app routes every `reg-*`
  through this one atom."}
  kind->id->ns->descriptor
  (atom {}))

(def ^:dynamic *source-store*
  "The active source-store atom, or nil for the process-default
  [[kind->id->ns->descriptor]]. Bound (alongside `registrar/*registrar*`) by
  `app-value/install!` / `reinstall!` to seat a constructed realm's
  registrations into the realm's OWN source store (EP-0013 stage 9). nil ⇒ the
  default realm — the byte-identical single-realm path."
  nil)

(defn active-source-store
  "Return the source-store atom `record-descriptor!` / queries currently
  target — the bound [[*source-store*]] when an explicit-realm seating is in
  flight, else the process-default [[kind->id->ns->descriptor]]. The single
  resolution point so every read/write below targets ONE consistent atom under
  a binding."
  []
  (or *source-store* kind->id->ns->descriptor))

;; ---- the store GENERATION signal (EP-0023 §Image — resolved-generation cache)
;;
;; A MONOTONIC counter bumped on every mutation of the active source store. The
;; resolved-generation cache (`re-frame.image-assembly`) folds this into its
;; cache key so a sealed generation is reused only while the registration pool
;; it was assembled from is unchanged — and is invalidated the instant any
;; `reg-*` / `forget-*` / `clear-*` alters the store. The EP names this the
;; "registration source-store generation" leg of the minimum cache key.
;;
;; The counter is keyed per active store (an atom-identity → counter map) so a
;; realm-bound `*source-store*` carries its OWN generation independent of the
;; process-default store — two realms never alias each other's cache bucket. A
;; bare integer per store is enough: any change bumps it; equality means "the
;; store has not changed since this generation was read".

(defonce ^{:doc "atom-identity → monotonic generation integer. One slot per
  source-store atom (process-default + each bound realm store). Bumped by every
  store mutation; read by the resolved-generation cache key."
            :private true}
  store->generation
  (atom {}))

(defn- bump-generation!
  "Increment the active source store's monotonic generation. Called by every
  mutation (`record-descriptor!`, `forget-*`, `clear-all!`). Keyed by the store
  atom identity so realm stores keep independent generations."
  []
  (let [store (active-source-store)]
    (swap! store->generation update store (fnil inc 0))
    nil))

(defn store-generation
  "The MONOTONIC generation integer of the active source store (EP-0023 §Image —
  the resolved-generation cache's source-store-generation key leg). Starts at 0
  (a never-mutated store) and increments on every `reg-*` / `forget-*` /
  `clear-*` mutation. Equal generation across two reads ⇒ the registration pool
  is byte-for-byte the set it was when the earlier generation was read, so a
  sealed image generation assembled then may be reused; any change bumps it and
  invalidates the cache. Keyed per active store so a realm-bound store reports
  its OWN generation."
  []
  (get @store->generation (active-source-store) 0))

;; ---- canonical namespace-string pooling -----------------------------------
;;
;; One string instance per source namespace per store (the EP's anti-tax
;; requirement). The pool is keyed by the string value; `canonical-ns` returns
;; the pooled instance so every descriptor from the same namespace shares it.
;;
;; The pool is process-scoped (one atom) rather than per-store: identical
;; namespace strings across realms can safely share one instance (the value is
;; immutable and equality is by value), and pooling at the process level keeps
;; the table small and avoids re-interning on realm switches. This does not
;; leak across the public boundary — `:rf.provenance/ns` is still just a string.

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

(defn record-descriptor!
  "Record `descriptor` for `(kind, id)` under its source namespace in the
  active source store, returning the descriptor as stored (with
  `:rf.provenance/ns` stamped as a canonical string).

  Provenance namespace resolution, in order:
    1. an explicit `:rf.provenance/ns` already on the descriptor (a tool or
       generated-code path that stamps its own provenance), canonicalized;
    2. otherwise the descriptor's `:ns` slot (the symbol a reg-* macro
       captured via `*pending-coords*`), canonicalized to a string.

  When neither yields a namespace (a programmatic / REPL registration with no
  macro-captured `:ns`), the descriptor is recorded under the nil-provenance
  slot. Nil-provenance descriptors still occupy ONE slot per `(kind, id)`
  (they cannot be image-isolation-distinguished — they have no namespace), so a
  later programmatic re-register of the same `(kind, id)` replaces it. They are
  NOT silently dropped: a later image-assembly slice still sees them.

  This is additive to `registrar/register!`'s resolver-map write — the store
  preserves every provenance-distinct descriptor; the resolver map remains the
  unchanged default-image runtime path until a later slice projects from here."
  [kind id descriptor]
  (let [pn   (canonical-ns (or (get descriptor provenance-ns-key)
                               (:ns descriptor)))
        ;; Stamp the canonical provenance string onto the stored descriptor so
        ;; `(rf/handler-meta kind id)` consumers and `:include-ns` selection
        ;; (slice .3) read a string at :rf.provenance/ns. When there is no
        ;; provenance (programmatic path), leave the key off rather than store
        ;; a nil — absence is the honest signal "no source namespace".
        desc (cond-> descriptor
               pn (assoc provenance-ns-key pn))
        store (active-source-store)]
    (swap! store assoc-in [kind id pn] desc)
    (bump-generation!)
    desc))

;; ---- queries --------------------------------------------------------------
;;
;; Read surfaces for a later image-assembly slice (and tests). None of these
;; make a selection decision — they expose the retained, provenance-tagged
;; descriptors as data.

(defn descriptors-for
  "Return the `provenance-ns-string → descriptor` map for `(kind, id)` in the
  active source store, or `{}` when none. The map has more than one entry
  exactly when two or more namespaces registered the same `(kind, id)` — the
  image-isolation case the store preserves. The nil-provenance descriptor (if
  any) is under the `nil` key."
  [kind id]
  (-> @(active-source-store) (get kind) (get id) (or {})))

(defn descriptor-for
  "Return the descriptor for the exact `(kind, id, provenance-ns)` source slot,
  or nil. `provenance-ns` is canonicalized so callers may pass a symbol or
  string."
  [kind id provenance-ns]
  (-> (descriptors-for kind id) (get (canonical-ns provenance-ns))))

(defn all-descriptors
  "Return every retained descriptor for `kind` as a flat seq across all
  `(id, provenance-ns)` slots, or an empty seq when the kind has none. A later
  image-assembly slice selects from this set by `:include-ns` and validates
  collisions; this fn makes no such decision."
  [kind]
  (for [[_id ns->desc] (get @(active-source-store) kind)
        [_ns desc]     ns->desc]
    desc))

(defn kinds-present
  "Return the set of kinds that currently have any recorded descriptor in the
  active source store."
  []
  (-> @(active-source-store) keys set))

;; ---- removal / reset ------------------------------------------------------

(defn forget-descriptor!
  "Remove the source slot for the exact `(kind, id, provenance-ns)`, mirroring
  `registrar/unregister!`'s targeted removal. `provenance-ns` is canonicalized.
  When `provenance-ns` is nil this removes the nil-provenance slot only.
  Leaves any sibling-namespace descriptors for the same `(kind, id)` intact."
  [kind id provenance-ns]
  (let [pn    (canonical-ns provenance-ns)
        store (active-source-store)]
    (swap! store update-in [kind id] dissoc pn)
    ;; Drop the now-empty (kind id) and kind shells so queries see a clean map.
    (swap! store (fn [s]
                   (let [s (if (empty? (get-in s [kind id]))
                             (update s kind dissoc id)
                             s)]
                     (if (empty? (get s kind))
                       (dissoc s kind)
                       s))))
    (bump-generation!)
    nil))

(defn forget-id!
  "Remove every provenance slot for `(kind, id)` across all namespaces.
  Used where the resolver-map path drops a `(kind, id)` wholesale."
  [kind id]
  (let [store (active-source-store)]
    (swap! store (fn [s]
                   (let [s (update s kind dissoc id)]
                     (if (empty? (get s kind))
                       (dissoc s kind)
                       s))))
    (bump-generation!)
    nil))

(defn clear-all!
  "Reset the PROCESS-DEFAULT source store and the namespace-string pool. Test
  fixtures use this between cases, alongside `registrar/clear-all!`.

  Resets only the process-default store (not a bound realm store) — fixtures
  run against the default store. The ns-string pool is reset too so a fixture
  asserting pool behaviour starts clean; pool entries are harmless to retain in
  production (the pool is never reset there)."
  []
  (reset! kind->id->ns->descriptor {})
  (reset! ns-string-pool {})
  ;; Bump the PROCESS-DEFAULT store's generation (clear-all! always targets it,
  ;; regardless of any *source-store* binding) so a cache keyed on the old
  ;; generation invalidates against the freshly-cleared store.
  (swap! store->generation update kind->id->ns->descriptor (fnil inc 0))
  nil)
