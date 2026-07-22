(ns re-frame.ui.tool.evidence
  "The first-party tool projection over the ViewCell DEBUG
  invalidation-evidence plane (rf2-vxgfnd.75) — the `re-frame.ui.tool`/Xray
  consumer the reactive scheduler's `set-evidence-sink!` seam was built for
  (rf2-vxgfnd.46).

  WHAT IT PROJECTS. Each flushed render batch delivers one BOUNDED causal
  summary per pending cell (`re-frame.ui.reactive/fold-evidence` — first/latest
  frame-epoch, occurrence `:count`, the cause set, a capped shown-target
  sample, and the honest `:dropped`/`:dropped-exact?` loss account from
  rf2-vxgfnd.74). This namespace ACCRETES those per-batch records into one
  bounded per-cell accumulator indexed by stable identity — a projection ordinal
  (`:cell-id`), the authoring view (`:view-id`), and the owning client root
  (`:root-id`, resolved through the live-root registry at read time) — so a
  tool (Xray) can attribute coalesced renders to their contributing movement
  across the cell's whole observed life WITHOUT retaining any event payload.

  LIFECYCLE — IDENTITY-OWNED, NEVER CLOBBERING. The reactive seam is a raw
  last-write-wins slot; this tier makes it safe for tool coexistence + HMR:
  `install!` claims the projection for an owner id, a SECOND owner's install
  is REJECTED (returns false, warns — the first registration is never
  silently cleared), a same-owner re-install is an idempotent re-arm (the
  `:after-load` / Fast Refresh path — accumulated evidence survives), and
  `uninstall!` (owner-checked) releases the sink callback and every retained
  entry. The index is NON-OWNING: a weak cell key/ref preserves a row while
  React retains an Activity-hidden cell, but cannot pin an ordinarily
  unmounted cell. A tool absent or uninstalled retains ZERO ViewCells/evidence.

  LINEARIZED BY GENERATION (rf2-vxgfnd.147). Owner, generation, the armed
  sink closure, and the current weak registry live in ONE state atom
  (`state*`). Registry publication and lifecycle transitions share the same
  tiny transition gate. Every ownership transition through the closed state
  bumps
  a monotonically unique GENERATION, the armed sink closure captures the
  generation current at arm time, and a delivery publishes ONLY while its
  captured generation exactly matches the current open one — so a delayed
  callback that raced an uninstall cannot repopulate an ownerless
  projection, a stale owner-A callback cannot contaminate owner B's fresh
  projection, and an uninstall/reinstall of the SAME logical owner id is
  ABA-safe (the old incarnation's callbacks are fenced out by generation,
  not by owner equality). On the JVM the lifecycle transitions additionally
  serialize state/registry mutation and raw-slot arm/disarm under a tiny
  transition lock — never through a USER callback —
  so a stale A teardown can never disarm B's freshly installed sink. The
  legal linearization: a publication linearizes at its registry mutation and takes
  effect iff the open generation it captured is still current; install and
  uninstall linearize in transition-lock order.

  OBSERVATIONAL ONLY. The scheduler stays authoritative: delivery runs inside
  the flush's containment (rf2-vxgfnd.73 — a throwing consumer can neither
  strand a cell nor abort a batch), the projection never acquires/releases or
  computes. Explicitly dead cells are pruned at delivery/read; collected weak
  references compact at read and through host finalization, so ordinary
  unmounts disappear without a polling scan. The state atom, transition lock,
  and every access are compile-time gated on `interop/debug-enabled?` and DCE
  out of `:advanced` +
  goog.DEBUG=false production output (Spec 006 §The internal observation
  port; 03 §3)."
  (:require [re-frame.interop     :as interop]
            [re-frame.ui.reactive :as reactive]
            #?(:cljs [re-frame.ui.client :as client])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the bounded per-cell accumulator ---------------------------------------
;;
;; The SAME honesty discipline as the per-window fold (rf2-vxgfnd.74), applied
;; at the cumulative tier: a bounded SHOWN sample of distinct moving targets,
;; overflow recorded by IDENTITY in a bounded loss set, saturation flagged
;; (never silently lost), everything constant-size per cell.

(def ^:private ^:const target-cap
  ;; Distinct moving targets SHOWN per cell accumulator — mirrors the
  ;; per-window shown-sample cap so a one-batch cell projects verbatim.
  8)

(def ^:private ^:const dropped-cap
  ;; Distinct OMITTED target keys tracked before the cumulative loss account
  ;; itself saturates (`:dropped-exact?` flips false; `(count :dropped)`
  ;; becomes a LOWER bound) — mirrors the per-window loss-set cap.
  64)

(def ^:private ^:const target-value-cap
  ;; Maximum members copied from any ONE EDN collection in a target key.
  ;; The scheduler bounds target cardinality; this tier also bounds the values
  ;; inside those keys before retaining them beyond the delivery callback.
  32)

(def ^:private ^:const target-depth-cap 8)
(def ^:private ^:const target-node-cap 128)
(def ^:private ^:const target-string-cap 256)

(def ^:private ^:const interval-cap
  ;; Most-recent lifecycle intervals projected per incarnation (rf2-vxgfnd.95.6).
  ;; A hide/reveal or teardown cycle appends one interval; a long-lived Activity
  ;; cell could accrete many, so the connected-instance record projects a bounded
  ;; TAIL and reports older ones as a `:dropped` count — the lifecycle axis of the
  ;; same honest-loss discipline the per-cell target account uses.
  16)

(def ^:private dynamic-marker {:rf.ui.evidence/opaque :dynamic})
(def ^:private bounded-marker {:rf.ui.evidence/opaque :bounded})

#?(:clj
   (def ^:private exact-number-classes
     "The closed set of immutable platform numeric classes admitted VERBATIM.
     Each is a value-typed leaf that is NOT `IObj`, so an admitted number can
     carry no hidden ViewCell — unlike an arbitrary `Number` subclass, which may
     ALSO implement `IObj` and hold a cell in metadata or a field. Membership is
     by EXACT class, so any subclass (a `proxy [Number IObj]` included) is
     excluded and takes the opaque/inexact path (rf2-1hob1)."
     #{java.lang.Long    java.lang.Integer    java.lang.Short   java.lang.Byte
       java.lang.Double  java.lang.Float
       java.math.BigInteger java.math.BigDecimal
       clojure.lang.BigInt  clojure.lang.Ratio}))

(defn- exact-number?
  "True for a number safe to admit verbatim: a JS primitive number on CLJS
  (never `IMeta`), or one of the closed set of immutable platform numeric
  classes on the JVM (`exact-number-classes`), matched by exact class so no
  `Number`/`IObj` subclass can root a ViewCell through metadata or a field."
  [x]
  #?(:clj  (contains? exact-number-classes (class x))
     :cljs (number? x)))

(defn- project-target-value
  "Copy one target value into bounded plain EDN, returning `[copy exact?]`.

  ViewCells, host values, records and arbitrary sequences become an opaque
  marker. That is deliberately a COPY, not a serializer: no metadata,
  identity-bearing object or query→cell back-edge can enter the long-lived
  weak-registry value. Ordinary bounded EDN remains exact.

  METADATA is the subtle leak (rf2-vxgfnd.94.17). The metadata-capable scalar
  leaf is the SYMBOL — `(with-meta 'q {:cell cell})` is a legal target value
  whose metadata can hold the very ViewCell the entry is weak-keyed by, so
  returning the symbol verbatim roots the key. (nil, boolean, char and string
  are not `IMeta`; a keyword is interned and rejects metadata on both hosts; a
  record is already opaque.) A metadata-bearing symbol is REBUILT from its
  ns/name — value-equal, provably free of any hidden field — and marked inexact,
  because dropping the metadata changed what the original carried.

  NUMBERS are admitted verbatim ONLY from a closed set of immutable platform
  numeric classes (`exact-number?`). On the JVM `number?` alone is not enough: a
  `Number` subclass can ALSO be `IObj`, so a `proxy [Number IObj]` holding a
  ViewCell in metadata or a field is `number?` and would slip through as exact
  and root the key (rf2-1hob1). Any class outside the set takes the opaque path.

  COLLECTION metadata does not LEAK — every collection branch reconstructs a
  fresh, metadata-free value — but dropping it is still information loss, so a
  reconstructed vector/list/map/set whose original carried metadata is marked
  INEXACT, the same honesty the symbol rule applies (rf2-1hob1). A
  metadata-bearing symbol NESTED in a collection is stripped by the same leaf
  rule on the recursion."
  ([x] (project-target-value x 0 (volatile! target-node-cap)))
  ([x depth budget]
   (if-not (pos? @budget)
     [bounded-marker false]
     (do
       (vswap! budget dec)
       (cond
         (> depth target-depth-cap)
         [bounded-marker false]

         (or (nil? x) (boolean? x) (char? x) (exact-number? x))
         [x true]

         (string? x)
         (if (<= (count x) target-string-cap)
           [x true]
           [bounded-marker false])

         (keyword? x)
         ;; Interned, not IMeta on either host — no back-edge possible.
         (if (<= (count (str x)) target-string-cap)
           [x true]
           [bounded-marker false])

         (symbol? x)
         (if (<= (count (str x)) target-string-cap)
           (if (nil? (meta x))
             [x true]
             ;; Metadata-bearing: rebuild from ns/name so no metadata map (and
             ;; thus no ViewCell it may hold) enters the retained value, and
             ;; mark the loss — the projected symbol is value-equal but no
             ;; longer carries what the original did.
             [(symbol (namespace x) (name x)) false])
           [bounded-marker false])

         (reactive/cell? x)
         [dynamic-marker false]

         (record? x)
         [dynamic-marker false]

         (vector? x)
         (if (> (count x) target-value-cap)
           [bounded-marker false]
           (let [parts (mapv #(project-target-value % (inc depth) budget) x)]
             ;; A reconstructed container drops its own metadata (no back-edge);
             ;; that dropped metadata is loss, so it lowers exactness too.
             [(mapv first parts)
              (and (every? second parts) (nil? (meta x)))]))

         (list? x)
         (if (> (count x) target-value-cap)
           [bounded-marker false]
           (let [parts (mapv #(project-target-value % (inc depth) budget) x)]
             [(apply list (map first parts))
              (and (every? second parts) (nil? (meta x)))]))

         (map? x)
         (if (> (count x) target-value-cap)
           [bounded-marker false]
           (let [parts (mapv (fn [[k v]]
                               [(project-target-value k (inc depth) budget)
                                (project-target-value v (inc depth) budget)])
                             x)]
             ;; A partial map could misstate which value belonged to which key.
             ;; Preserve it only when every copied association is exact — and
             ;; even then, dropping the map's own metadata lowers exactness.
             (if (every? (fn [[[_ k-exact?] [_ v-exact?]]]
                           (and k-exact? v-exact?))
                         parts)
               [(into {} (map (fn [[[k _] [v _]]] [k v])) parts)
                (nil? (meta x))]
               [dynamic-marker false])))

         (set? x)
         (if (> (count x) target-value-cap)
           [bounded-marker false]
           (let [parts (mapv #(project-target-value % (inc depth) budget) x)
                 copy  (into #{} (map first) parts)]
             (if (and (every? second parts) (= (count copy) (count x)))
               [copy (nil? (meta x))]
               [dynamic-marker false])))

         :else
         [dynamic-marker false])))))

(defn- canonicalize-causes
  "Rewrite the pre-rename disposal-cause spelling `:value` to today's
  `:subscription` in one ingress cause set (rf2-ao46i, RULING 2). Fresh
  deliveries already speak the unified `:subscription`/`:hmr`/`:disposed`
  vocabulary, but a RETAINED accrual — or a pending window carried across a
  reload — minted before the rename can still say `:value`, and carrying it
  verbatim would union with fresh `:subscription` into the two-vocabulary set
  the consumer contract forbids. One spelling rewrite at the shared
  normalization choke point, not a compatibility layer."
  [causes]
  (if (contains? causes :value)
    (-> causes (disj :value) (conj :subscription))
    causes))

(defn- project-batch-evidence
  "Replace delivered target identities with bounded non-owning EDN copies,
  and canonicalize the cause vocabulary (`canonicalize-causes`) — BOTH ingress
  paths (fresh delivery via `accrete-evidence`, retained-row migration via
  `reproject-accrual`) normalize here, so no pre-rename `:value` spelling
  survives into a retained value or a projection read.
  Loss is explicit: `:targets-exact?` covers both shown and dropped target
  identity. `:dropped-exact?` also becomes false after any such loss because
  opaque collisions mean cumulative omitted-target cardinality is no longer
  provably exact."
  [ev]
  (let [target-parts  (mapv project-target-value (:targets ev))
        dropped-parts (mapv project-target-value (:dropped ev))
        targets        (mapv first target-parts)
        dropped        (into #{} (map first) dropped-parts)
        shown-exact?   (every? second target-parts)
        omitted-exact? (and (every? second dropped-parts)
                            (= (count dropped) (count (:dropped ev))))
        targets-exact? (and shown-exact? omitted-exact?)]
    (-> ev
        (assoc :targets targets
               :dropped dropped
               :targets-exact? targets-exact?)
        (update :causes canonicalize-causes)
        (cond-> (or (false? (:dropped-exact? ev))
                    (not targets-exact?))
          (assoc :dropped-exact? false)))))

(def ^:private empty-accrual
  ;; The zero accumulator — also the normalization for a (defensive) nil or
  ;; partial delivered batch, which folds as empty.
  {:first-epoch    nil
   :latest-epoch   nil
   :count          0
   :causes         #{}
   :targets        []
   :targets-exact? true
   :dropped        #{}
   :dropped-exact? true})

(defn- fold-target
  "Fold ONE distinct target key `tk` from a delivered batch into the
  cumulative shown-sample/loss-set pair. First classification is stable: a
  target already shown (or already recorded as an omission) folds as known —
  re-delivery across batches can never inflate the loss (the cross-batch
  restatement of the rf2-vxgfnd.74 honesty fix)."
  [acc tk]
  (let [known? (or (some #(= tk %) (:targets acc))
                   (contains? (:dropped acc) tk))]
    (cond
      known?                                 acc
      (< (count (:targets acc)) target-cap)  (update acc :targets conj tk)
      (< (count (:dropped acc)) dropped-cap) (update acc :dropped conj tk)
      :else                                  (assoc acc :dropped-exact? false))))

(defn- accrete-evidence
  "Fold one flushed batch's bounded evidence record `ev` into the cell's
  cumulative accumulator `acc` (nil = first delivery). Returns a BOUNDED,
  constant-size record — `empty-accrual`'s keys plus `:batches`:

    :first-epoch    — the anchor of the FIRST delivered window (set once,
                      never rewritten — nil stays nil when that window
                      carried no epoch evidence, mirroring the window fold)
    :latest-epoch   — the most recent window's latest movement
    :count          — total invalidation OCCURRENCES across every batch
    :batches        — how many flushed batches delivered evidence
    :causes         — the union cause set (:subscription/:hmr/:disposed — ≤3)
    :targets        — bounded non-owning EDN sample of moving targets
    :targets-exact? — false when a target needed an opaque/bounded marker
    :dropped        — bounded loss SET of distinct omitted targets; its
                      count is the honest cumulative fan-out loss
    :dropped-exact? — false once a delivered window/cumulative loss set
                      saturated OR target projection lost distinct identity
                      (a floor, never a lie)

  Never retains `ev` itself, a payload, or anything scaling with the
  invalidation count."
  [acc ev]
  (let [ev  (project-batch-evidence (merge empty-accrual ev))
        acc (or acc (assoc empty-accrual
                           :first-epoch (:first-epoch ev)
                           :batches 0))]
    (-> acc
        (update :batches inc)
        (assoc :latest-epoch (:latest-epoch ev))
        (update :count + (:count ev))
        (update :causes into (:causes ev))
        (as-> a (reduce fold-target a (concat (:targets ev) (:dropped ev))))
        (cond-> (false? (:targets-exact? ev)) (assoc :targets-exact? false)
                (false? (:dropped-exact? ev)) (assoc :dropped-exact? false)))))

(defn- reproject-accrual
  "Re-run one accrual retained across a reload through the CURRENT bounded
  projection (rf2-vxgfnd.94.18).

  A store built by an earlier head holds values its head's copier admitted —
  at the pre-projection heads, RAW target keys including query→ViewCell cycles.
  Recognizing such a store's host weak machinery is not enough: a migrated row
  carrying a raw key still owns its own weak key and pins it forever. Every
  retained value must go through TODAY's copier.

  This IS the delivery path — `project-batch-evidence` then `fold-target` —
  so a migrated row is indistinguishable from one accreted fresh: the same
  bounded shown sample, the same distinct-loss partition, the same honesty.
  Counters (`:count`, `:batches`, epochs) are already bounded plain data and
  carry over verbatim; `:targets` keeps its first-seen order. The cause set is
  bounded but NOT vocabulary-neutral: a store minted before the disposal-cause
  rename retains `:value` where today's contract says `:subscription`, so the
  causes carry over through the normalization's `canonicalize-causes` — never
  verbatim (rf2-ao46i).

  Exactness only ever LOWERS. Re-projection can discover loss the legacy record
  never marked, but it can never prove an exactness the legacy floor already
  denied — the fold starts FROM the stored accrual, so a stored `false` stays
  false. A row is never reset merely to simplify the migration, and an exact
  value is never fabricated."
  [acc]
  (let [acc0 (merge empty-accrual acc)
        ev   (project-batch-evidence acc0)]
    (-> acc0
        (assoc :causes (:causes ev) :targets [] :dropped #{})
        (as-> a (reduce fold-target a (concat (:targets ev) (:dropped ev))))
        (cond-> (false? (:targets-exact? ev)) (assoc :targets-exact? false)
                (false? (:dropped-exact? ev)) (assoc :dropped-exact? false)))))

(defn- sanitize-entry
  "Re-project one retained row's accrual, preserving its `:cell-id` ordinal and
  `:view-id` (stable identity axes, never target values)."
  [entry]
  (cond-> entry
    (some? (:evidence entry)) (update :evidence reproject-accrual)))

;; ---- projection state --------------------------------------------------------

(def ^:private ^:const weak-store-tag
  ;; The CURRENT retained-value contract version. A store carrying EXACTLY this
  ;; tag has had every entry through today's normalization (the
  ;; `project-target-value` copier AND the cause-vocabulary canonicalization);
  ;; any other shape — an older tag, or the untagged defrecord/strong-map
  ;; stores left by earlier heads — is LEGACY, and its entries are re-projected
  ;; on migration (rf2-vxgfnd.94.18). BUMP THIS whenever normalization changes
  ;; what a retained value may hold: v1 admitted metadata-bearing symbols,
  ;; whose metadata could hold the very ViewCell the entry is weak-keyed by
  ;; (rf2-vxgfnd.94.17). v2 was minted at an intermediate head whose copier
  ;; ALSO returned those symbols verbatim, and the copier was then FIXED under
  ;; the same v2 tag — so a store HMR-migrated at that head carries a v2 tag
  ;; over metadata-bearing values and would be trusted forever. v3 rejects
  ;; every pre-final store and re-projects it (rf2-1hob1). v3 was ALSO current
  ;; before the disposal-cause rename (:value → :subscription, rf2-ao46i), so
  ;; a same-owner HMR store tagged v3 can retain :causes #{:value}; trusting
  ;; that tag would skip the sanitation pass that canonicalizes the spelling,
  ;; and the stale row would later union with fresh :subscription and egress a
  ;; two-vocabulary set through explain-render. v4 forces that one pass.
  ::weak-cell-entries-v4)

(def ^:private ^:const weak-store-tag-key ::weak-cell-entries)

(defn- new-weak-cell-entries
  "Create one non-owning identity registry for an OPEN ownership span."
  []
  #?(:clj
     {weak-store-tag-key weak-store-tag
      :members          (java.util.WeakHashMap.)
      :by-cell          nil
      :next-ordinal     (java.util.concurrent.atomic.AtomicLong. 0)
      :reaper           nil}
     :cljs
     (let [members (js/Set.)]
       {weak-store-tag-key weak-store-tag
        :members          members
        :by-cell          (js/WeakMap.)
        :next-ordinal     (volatile! 0)
        :reaper           (when (exists? js/FinalizationRegistry)
                            (js/FinalizationRegistry.
                             (fn [holder]
                               ;; The holder contains only a WeakRef + bounded
                               ;; copied evidence record.
                               (.delete members holder))))})))

(defn- weak-cell-entries?
  "Recognize a weak store of ANY vintage — a tagged map (current or older) and
  the exact same host structure left by the pre-tag defrecord implementation.
  Constructor identity is not stable across a CLJS :after-load; these host
  fields are. Recognition means only that the HOST MACHINERY is reusable; it
  says nothing about whether the retained VALUES meet the current contract —
  that is `weak-store-current?`."
  [x]
  (and (map? x)
       (or (contains? x weak-store-tag-key)
           (and (contains? x :members)
                (contains? x :by-cell)
                (contains? x :next-ordinal)
                (contains? x :reaper)))
       #?(:clj  (and (instance? java.util.Map (:members x))
                     (instance? java.util.concurrent.atomic.AtomicLong
                                (:next-ordinal x)))
          :cljs (and (instance? js/Set (:members x))
                     (instance? js/WeakMap (:by-cell x))
                     (some? (:next-ordinal x))))))

(defn- weak-store-current?
  "True when `store` carries EXACTLY the current retained-value contract tag —
  its entries are already projected by today's copier and migration is a no-op.
  A recognized store with an older tag, or none at all, is legacy: reuse its
  host weak machinery, but trust none of its values."
  [store]
  (= weak-store-tag (get store weak-store-tag-key)))

(defn- store-next-ordinal!
  [store]
  #?(:clj  (.getAndIncrement
            ^java.util.concurrent.atomic.AtomicLong (:next-ordinal store))
     :cljs (let [next* (:next-ordinal store)
                 n     @next*]
             (vswap! next* inc)
             n)))

(defn- store-set-next-at-least!
  [store n]
  #?(:clj  (let [next* ^java.util.concurrent.atomic.AtomicLong
                 (:next-ordinal store)]
             (.set next* (max (.get next*) (long n))))
     :cljs (let [next* (:next-ordinal store)]
             (vreset! next* (max @next* n)))))

(defn- store-seed-entry!
  "Import an already-accreted reload entry without owning its cell."
  [store cell entry]
  #?(:clj
     (let [members ^java.util.Map (:members store)]
       (locking members
         (.put members cell entry)
         (store-set-next-at-least! store (inc (:cell-id entry)))))
     :cljs
     (let [members ^js (:members store)
           by-cell ^js (:by-cell store)
           holder  #js {:ref (js/WeakRef. cell) :entry entry}]
       (.set by-cell cell holder)
       (.add members holder)
       (when-some [reaper (:reaper store)]
         (.register ^js reaper cell holder holder))
       (store-set-next-at-least! store (inc (:cell-id entry)))))
  nil)

(defn- store-upsert!
  "Accrete one bounded batch under a weak key. The JVM monitor makes
  concurrent deliveries exact; CLJS delivery is serial."
  [store cell ev]
  #?(:clj
     (let [members ^java.util.Map (:members store)]
       (locking members
         (let [existing (.get members cell)
               entry    (or existing
                            {:cell-id  (store-next-ordinal! store)
                             :view-id  (reactive/cell-view-id cell)
                             :evidence nil})]
           (.put members cell (update entry :evidence accrete-evidence ev)))))
     :cljs
     (let [members ^js (:members store)
           by-cell ^js (:by-cell store)]
       (if-some [holder (.get by-cell cell)]
         (aset holder "entry"
               (update (aget holder "entry") :evidence accrete-evidence ev))
         (let [entry  {:cell-id  (store-next-ordinal! store)
                       :view-id  (reactive/cell-view-id cell)
                       :evidence (accrete-evidence nil ev)}
               holder #js {:ref (js/WeakRef. cell) :entry entry}]
           (.set by-cell cell holder)
           (.add members holder)
           (when-some [reaper (:reaper store)]
             (.register ^js reaper cell holder holder))))))
  nil)

(defn- store-remove!
  [store cell]
  #?(:clj
     (let [members ^java.util.Map (:members store)]
       (locking members (.remove members cell)))
     :cljs
     (let [members ^js (:members store)
           by-cell ^js (:by-cell store)]
       (when-some [holder (.get by-cell cell)]
         (.delete by-cell cell)
         (.delete members holder)
         (when-some [reaper (:reaper store)]
           (.unregister ^js reaper holder)))))
  nil)

(defn- store-sanitize-entries!
  "Re-project EVERY entry a legacy store retained, IN PLACE — same host weak
  maps, same reaper registrations, same ordinal mint, same rows in the same
  order. Only the retained VALUES change (rf2-vxgfnd.94.18).

  In place is the point: rebuilding the store would re-register the reaper and
  re-key the weak maps for rows this tier does not own, and rewrapping without
  re-projecting would keep the raw query→cell back-edges the migration exists
  to break."
  [store]
  #?(:clj
     (let [members ^java.util.Map (:members store)]
       (locking members
         ;; Materialize first (see `store-live-entries!`), and skip a key that
         ;; cleared in between — its row is already garbage.
         (doseq [[cell entry] (into [] members)
                 :when (some? cell)]
           (.put members cell (sanitize-entry entry)))))
     :cljs
     (let [members ^js (:members store)]
       (.forEach members
                 (fn [holder _ _]
                   (aset holder "entry" (sanitize-entry (aget holder "entry")))))))
  nil)

(defn- migrate-weak-store!
  "Bring a recognized weak store up to the CURRENT retained-value contract,
  reusing its exact host weak maps, reaper and ordinal mint, and drop any
  reload-specific record constructor.

  IDEMPOTENT: a store already carrying the current tag is returned untouched,
  so repeated `:after-load` re-arms do no work. Anything older has its entries
  re-projected before it is re-tagged — the tag is a claim about the values,
  and promoting a legacy store without sanitizing it would make that claim a
  lie (rf2-vxgfnd.94.18)."
  [store]
  (if (weak-store-current? store)
    store
    (do (store-sanitize-entries! store)
        (assoc (into {} store) weak-store-tag-key weak-store-tag))))

(defn- store-live-entries!
  "Snapshot live `[cell entry]` pairs, pruning dead cells and cleared refs.

  TWO exits, not one (rf2-un54gv). `:dead` is the PROVEN end — a teardown path
  said so, and the row goes immediately. But an ordinary React reconciliation
  unmount proves nothing: the cell only ever reaches `:disconnected`, exactly as
  an Activity hide does, so a lifecycle predicate can never distinguish them.
  That cell leaves through the OTHER exit — its weak key is collected once React
  drops the last owner, and the read compacts whatever the host has cleared.
  Pruning on `:disconnected` would evict live Activity-hidden rows; pruning only
  on `:dead` would pin every unmount forever. Both exits are load-bearing."
  [store]
  (if (nil? store)
    []
    #?(:clj
       (let [members ^java.util.Map (:members store)]
         (locking members
           ;; Materialize first: mutating WeakHashMap during reduce iteration
           ;; would invalidate its iterator.
           (reduce (fn [out [cell entry]]
                     (cond
                       ;; A WeakHashMap entry OUTLIVES its referent: the key
                       ;; can clear between this materialization and the read
                       ;; below (the iterator holds only the CURRENT key
                       ;; strongly), so `getKey` hands back nil. That is the
                       ;; ordinary-unmount exit — skip the row and let the map
                       ;; expunge it, never `.remove` nil (which would target a
                       ;; genuine null-key mapping).
                       (nil? cell)
                       out

                       (= :dead (reactive/lifecycle cell))
                       (do (.remove members cell) out)

                       :else
                       (conj out [cell entry])))
                   []
                   (into [] members))))
       :cljs
       (let [members ^js (:members store)
             by-cell ^js (:by-cell store)
             out     (array)]
         (.forEach members
                   (fn [holder _ _]
                     (if-some [cell (.deref ^js (aget holder "ref"))]
                       (if (= :dead (reactive/lifecycle cell))
                         (do
                           (.delete by-cell cell)
                           (.delete members holder)
                           (when-some [reaper (:reaper store)]
                             (.unregister ^js reaper holder)))
                         (.push out [cell (aget holder "entry")]))
                       (do
                         ;; FinalizationRegistry holds its `heldValue`
                         ;; strongly until callback/unregister. A cleared
                         ;; WeakRef can be observed first, so release that
                         ;; exact unique token during opportunistic compaction.
                         (when-some [reaper (:reaper store)]
                           (.unregister ^js reaper holder))
                         (.delete members holder)))))
         (vec out)))))

#?(:cljs
   (goog-define ^boolean elision-negative-control? false))

#?(:clj
   (def ^:const elision-negative-control? false))

#?(:cljs
   (defonce ^:private cacheline*
     ;; Exact-head mutation oracle for the advanced-production gate. The
     ;; default false branch folds to nil; the gate's second build flips this
     ;; define and must reject the renamed rooted atom/reset path at runtime.
     (when elision-negative-control? (atom {:cursor 0}))))

(defonce ^:private state*
  ;; THE ONE AUTHORITATIVE projection state (rf2-vxgfnd.147): owner,
  ;; generation, armed sink closure and current ownership-span registry.
  ;;
  ;;   :owner        — the identity currently owning the projection (any
  ;;                   comparable value; a namespaced keyword by
  ;;                   convention), or nil when uninstalled.
  ;;   :generation   — monotonically unique ownership-span counter. Bumped
  ;;                   on every transition through the CLOSED state (fresh
  ;;                   claim, uninstall, force-release) and NEVER reused,
  ;;                   so a callback fenced on the generation it captured
  ;;                   is ABA-safe across uninstall/reinstall of the same
  ;;                   logical owner id. A same-owner re-arm (the HMR
  ;;                   path) is the SAME continuous ownership span and
  ;;                   keeps its generation — accumulated evidence and
  ;;                   in-flight deliveries stay valid together.
  ;;   :sink         — the exact closure this tier last armed on the raw
  ;;                   reactive slot (nil when closed) — kept HERE per the
  ;;                   one-authoritative-state discipline, and the capture
  ;;                   door for race fixtures (`installed-sink`).
  ;;   :entries      — nil while closed; while open, a reload-stable tagged
  ;;                   structural weak store whose values contain only
  ;;                   cell-id/view-id/bounded copied evidence. Dynamic
  ;;                   targets are opaque, so no value owns its weak key/ref.
  ;;                   ordinal mint belongs to this ownership span and is
  ;;                   discarded with the registry on close.
  ;;
  ;; `defonce` is module-lived; normalize-state migrates both a pre-weak strong
  ;; map and the former defrecord-shaped weak store on first post-reload access
  ;; without losing retained Activity evidence, host registrations or ordinals —
  ;; and re-projects their pre-projection values on the way, so no legacy row
  ;; migrates still owning its own weak key (rf2-vxgfnd.94.18).
  ;; In production the var root is literally nil: no atom, weak registry, or
  ;; ordinal mint is allocated (rf2-vxgfnd.149).
  (when interop/debug-enabled?
    (atom {:owner nil :generation 0 :sink nil :entries nil})))

#?(:clj
   (defonce ^:private transition-lock
     ;; Serialises LIFECYCLE transitions (install/uninstall/force-release)
     ;; with their raw-slot arm/disarm so the pair is one linearization
     ;; point: a stale A teardown can never disarm B's freshly armed sink.
     ;; Publication takes the same lock for its bounded weak-map mutation;
     ;; this is the tool's sink callback, never a user callback. CLJS is
     ;; single-threaded, so no lock exists there.
     (when interop/debug-enabled? (Object.))))

(defn- transition!
  "Run lifecycle transition `f` (state swap + raw-slot effect) atomically
  with respect to other transitions. See `transition-lock`."
  [f]
  #?(:clj  (locking transition-lock (f))
     :cljs (f)))

(defn- normalize-state
  "Bring the value `defonce` preserved verbatim across a reload up to the
  current contract. TWO legacy store shapes exist, and BOTH hold values an
  earlier copier admitted — so recognizing a shape is where the migration
  starts, not where it ends (rf2-vxgfnd.94.18):

    - a recognized weak store from an earlier head (including the pre-tag
      defrecord): its host weak machinery is REUSED and its entries are
      re-projected in place;
    - the pre-weak persistent ViewCell map: rebuilt into a fresh weak store,
      seeding each still-live row's SANITIZED entry under a weak key.

  Either way Activity-retained rows, their ordinals and their order survive,
  and no raw target/query value does. Closed state owns no registry."
  [{:keys [owner entries next-ordinal] :as s}]
  (cond
    (nil? owner)
    (-> s (assoc :entries nil) (dissoc :next-ordinal))

    (weak-cell-entries? entries)
    (-> s
        (assoc :entries (migrate-weak-store! entries))
        (dissoc :next-ordinal))

    :else
    (let [store (new-weak-cell-entries)]
      (doseq [[cell entry] entries
              :when (not= :dead (reactive/lifecycle cell))]
        (store-seed-entry! store cell (sanitize-entry entry)))
      (store-set-next-at-least! store (or next-ordinal 0))
      (-> s (assoc :entries store) (dissoc :next-ordinal)))))

(defn- project-batch!
  "One generation-fenced delivery: accrete one flushed batch's bounded
  record into `cell`'s accumulator (or remove an explicitly dead cell) — IFF
  `gen`, the generation the armed sink closure captured, is still the current OPEN
  owner's generation. A callback whose generation has closed (uninstall,
  force-release, or a later reinstall — even of the same owner id) is a
  no-op: it can neither repopulate an ownerless projection nor contaminate
  a successor owner's fresh one (rf2-vxgfnd.147). The fence, the ordinal
  mint, and the entry write share the lifecycle transition gate. A concurrent
  uninstall either precedes it (fence rejects) or follows it (the transition
  drops the whole registry it just admitted into).

  Runs inside the flush's rf2-vxgfnd.73 containment; constant-work per
  delivery (the record is already bounded). Never called in production
  (the flush's sink call is debug-gated); belt-gated here regardless."
  [gen cell ev]
  (when interop/debug-enabled?
    (transition!
     (fn []
       (let [{:keys [owner generation entries]} @state*]
         (when (and (some? owner) (= gen generation))
           (if (= :dead (reactive/lifecycle cell))
             (store-remove! entries cell)
             (store-upsert! entries cell ev))))))
    nil))

(defn- sink-for
  "The armed evidence-sink closure for one ownership span: every delivery
  it performs is fenced on `generation` — the generation current when it
  was armed (`install!`). Retains nothing beyond that integer."
  [generation]
  (fn [cell ev] (project-batch! generation cell ev)))

;; ---- lifecycle (identity-owned install/uninstall) ---------------------------

(defn- warn-rejected-install!
  "A rejected install must not be SILENT (the caller also gets false):
  emit one host diagnostic naming both owners. Debug-gated at the caller,
  so the whole branch DCEs out of production."
  [current rejected]
  #?(:cljs
     (when (exists? js/console)
       (.warn js/console
              (str "[re-frame.ui] the invalidation-evidence projection is "
                   "already owned by " (pr-str current) " — the install for "
                   (pr-str rejected) " was REJECTED (one tool cannot "
                   "silently clear another owner's registration). Uninstall "
                   "the current owner first (uninstall!), or coordinate on "
                   "one owner id.")))
     :clj nil))

(defn install!
  "Claim the invalidation-evidence projection for `owner-id` (any comparable
  non-nil value; a namespaced keyword by convention) and arm the reactive
  seam (`reactive/set-evidence-sink!`) with this namespace's accretor.

  Identity-owned, HMR-safe:
    - unowned            → installs fresh (empty projection); returns true.
    - owned by owner-id  → idempotent RE-ARM (the `:after-load`/Fast-Refresh
                           path, and the recovery after a test-fixture
                           `reset-scheduler!` cleared the raw slot) —
                           accumulated evidence is KEPT; returns true.
    - owned by another   → REJECTED: nothing changes, the current owner's
                           registration stands, one console diagnostic is
                           emitted (never silent); returns false.

  Production (`goog.DEBUG=false` / `-Dre-frame.debug=false`): a no-op
  returning false — the debug evidence plane does not exist there, and the
  whole projection DCEs out of `:advanced` output.

  While installed, the raw `set-evidence-sink!` slot belongs to this tier;
  bypassing it directly is the test seam only.

  Linearization (rf2-vxgfnd.147): the ownership swap + the raw-slot arm are
  one transition (`transition!`), a FRESH claim opens a NEW generation, and
  the armed closure captures exactly that generation — deliveries of any
  earlier generation are fenced out, even for the same owner id (ABA-safe)."
  [owner-id]
  (if-not interop/debug-enabled?
    false
    (do
      (assert (some? owner-id) "install! requires a non-nil owner id")
      (transition!
       (fn []
         (let [[old new]
               (swap-vals! state*
                           (fn [s0]
                             (let [{:keys [owner generation] :as s}
                                   (normalize-state s0)]
                               (cond
                                 ;; fresh claim → NEW generation, fresh fenced
                                 ;; closure, empty weak projection
                                 (nil? owner)
                                 (let [g (inc generation)]
                                   (assoc s :owner owner-id
                                            :generation g
                                            :sink (sink-for g)
                                            :entries (new-weak-cell-entries)))
                                 ;; same-owner re-arm → the SAME ownership span
                                 ;; (generation + weak registry kept), fresh
                                 ;; closure for the raw slot
                                 (= owner owner-id)
                                 (assoc s :sink (sink-for generation))
                                 ;; foreign owner → untouched
                                 :else s))))]
           (if (or (nil? (:owner old)) (= (:owner old) owner-id))
             ;; Arm the raw slot with the closure the transition minted —
             ;; both writes sit inside the transition lock, so no stale
             ;; teardown can interleave between them.
             (do (reactive/set-evidence-sink! (:sink new))
                 true)
             (do (warn-rejected-install! (:owner new) owner-id)
                 false))))))))

(defn uninstall!
  "Release the projection iff `owner-id` owns it: one atomic transition
  frees the ownership, CLOSES the generation (so every in-flight delivery
  of this span is fenced out — a delayed callback cannot repopulate the
  released projection), drops EVERY retained entry (cells + accumulated
  evidence) and the ordinal state, and disarms the reactive sink slot —
  teardown releases all callbacks/references, so a closed tool retains
  nothing. Owner-checked AND transition-serialised: a stale uninstall
  racing a successor's install either precedes it (releases, then the
  successor claims fresh) or follows it (sees the successor's ownership
  and refuses — the successor's sink/state are untouched). Returns true
  when released; false (nothing changes) when `owner-id` is not the
  current owner. Production: no-op, false."
  [owner-id]
  (if-not interop/debug-enabled?
    false
    (transition!
     (fn []
       (let [[old _] (swap-vals! state*
                                 (fn [{:keys [owner generation] :as s}]
                                   (if (= owner owner-id)
                                     {:owner        nil
                                      :generation   (inc generation)
                                      :sink         nil
                                      :entries      nil}
                                     s)))]
         (if (= (:owner old) owner-id)
           (do (reactive/set-evidence-sink! nil)
               true)
           false))))))

(defn force-release!
  "Test support: release the projection REGARDLESS of owner — sink slot
  cleared, every entry dropped, ownership freed, and the generation CLOSED
  (an in-flight delivery captured before the reset is fenced out exactly
  as under an owner-checked uninstall). The fixture-reset door (pair it
  with `reactive/reset-scheduler!` between fixtures); production tools use
  the owner-checked `uninstall!`. Returns nil."
  []
  #?(:cljs
     (when elision-negative-control?
       ;; Compiles only in the mutated advanced control. This is an actual
       ;; rooted-state mutation, not a source-name or diagnostic-string proxy.
       (swap! cacheline* update :cursor inc)))
  (when interop/debug-enabled?
    (transition!
     (fn []
       (swap! state* (fn [{:keys [generation]}]
                       {:owner      nil
                        :generation (inc generation)
                        :sink       nil
                        :entries    nil}))
       (reactive/set-evidence-sink! nil)
       nil)))
  nil)

(defn installed-owner
  "The identity currently owning the projection, or nil (tool/test read)."
  []
  (when interop/debug-enabled?
    (:owner @state*)))

(defn installed-sink
  "The exact sink closure this tier last armed on the reactive slot, or
  nil when the projection is closed (tool/test read). Race fixtures use it
  the way `deliver-flush!` does — deref at delivery start, invoke later —
  to replay a DELAYED delivery against a projection whose generation has
  since closed and prove the fence holds."
  []
  (when interop/debug-enabled?
    (:sink @state*)))

;; ---- the projection read -----------------------------------------------------

(defn- root-id-index
  "incarnation -> root-id over the CLJS client live-root registry — the
  developer-facing name for the opaque per-mount incarnation a cell enrols
  under (rf2-vxgfnd.85). Empty on the JVM host (no client registry; Tier-1
  cells attach to raw incarnations, which have no authored name)."
  []
  #?(:cljs (into {}
                 (keep (fn [rid]
                         (when-some [i (:root-incarnation
                                        (client/live-root-entry rid))]
                           [i rid])))
                 (client/live-root-ids))
     :clj  {}))

(defn projection
  "The current Xray-usable invalidation-evidence projection: a vector of
  per-cell records in stable `:cell-id` order —

    {:cell-id  ord      ; stable projection ordinal for this cell instance
     :view-id  vid      ; the authoring view (defview identity)
     :root-id  rid|nil  ; the owning LIVE client root (nil when the cell is
                        ;   not under a live client root — e.g. Tier-1/JVM)
     :evidence accrual} ; the bounded cumulative record (`accrete-evidence`)

  Dead cells and GC-cleared ordinary-unmount refs are pruned before the read;
  Activity-hidden cells remain while React owns them. An uninstalled (or
  never-installed) projection is empty. nil in a
  production build, where the debug evidence plane is elided (tool read)."
  []
  (when interop/debug-enabled?
    (let [entries (transition!
                   (fn []
                     (let [s (swap! state* normalize-state)]
                       (store-live-entries! (:entries s)))))
          roots   (root-id-index)]
      (->> entries
           (map (fn [[cell {:keys [cell-id view-id evidence]}]]
                  {:cell-id  cell-id
                   :view-id  view-id
                   :root-id  (get roots (reactive/cell-root cell))
                   :evidence evidence}))
           (sort-by :cell-id)
           (into [])))))

;; ---- connected-instance records (S3 — lifecycle-aware) -----------------------
;;
;; The S3 tool tier (`re-frame.ui.tool`) needs the SAME retained incarnations the
;; `projection` read exposes, plus the cell's live CONNECTION state and its
;; lifecycle-fact log. Those live on the ViewCell (03 §4). Projecting them HERE —
;; where the engine already holds the cell behind its non-owning weak key — keeps
;; the public facade handle-free: only the ordinal, the authoring view id, and
;; bounded serializable data ever egress. No ViewCell, handle, or React object.

(def ^:private lifecycle-interval-keys
  ;; The serializable lifecycle-fact keys (03 §4). `:state` is the EMITTED runtime
  ;; fact; `:reason`/`:proof` are the qualified RETROACTIVE annotation — an
  ;; explicit INFERENCE label (`:unknown` until the runtime PROVES `:activity-hidden`
  ;; via a settled reconnect or `:unmounted` via host/root teardown). This tier
  ;; passes them through VERBATIM and never fabricates a hide/unmount claim the
  ;; runtime did not record.
  [:state :reason :proof])

(defn- project-lifecycle
  "Project a cell's interval log into a BOUNDED, serializable lifecycle
  annotation: the most-recent `interval-cap` intervals (each reduced to the
  `:state`/`:reason`/`:proof` label keys — all keywords, never a handle) plus an
  honest `:dropped` count of older intervals omitted and an `:exact?` flag. The
  hide-vs-unmount labels are copied through as the runtime recorded them; the
  projection never upgrades an `:unknown` disconnect to a fabricated hide/unmount."
  [ivs]
  (let [ivs     (vec ivs)
        n       (count ivs)
        dropped (max 0 (- n interval-cap))
        shown   (if (pos? dropped) (subvec ivs dropped) ivs)]
    {:intervals (mapv #(select-keys % lifecycle-interval-keys) shown)
     :dropped   dropped
     :exact?    (zero? dropped)}))

(defn instance-records
  "Per-incarnation CONNECTED-INSTANCE records (tool read) — the S3
  lifecycle-aware sibling of `projection`. Each retained committed incarnation
  projects to

    {:occurrence ord      ; stable per-incarnation ordinal (occurrence identity)
     :view-id    vid       ; the authoring view (defview identity)
     :root-id    rid|nil   ; the owning LIVE client root (nil under no live root)
     :connection state     ; the cell's LIVE runtime lifecycle keyword
                           ;   (:connected / :disconnected — dead cells are pruned)
     :lifecycle  {:intervals [...] :dropped n :exact? b}
                           ; bounded serializable lifecycle-fact log + honest
                           ;   hide-vs-unmount labels (never fabricated)
     :evidence   accrual}  ; the bounded cumulative render evidence (`projection`)

  Records represent COMMITTED CONNECTED incarnations only — a speculative render
  never enrols, so nothing here is fabricated. A currently-connected cell reads
  `:connection :connected`; an Activity-hidden cell the tool deliberately retains
  reads `:connection :disconnected` and carries the honest hide-vs-unmount
  interval labels (rf2-un54gv / 03 §4). No ViewCell, handle, or React object
  egresses — only the ordinal, the view id, and bounded serializable data. nil in
  a production build, where the debug evidence plane is elided."
  []
  (when interop/debug-enabled?
    (let [entries (transition!
                   (fn []
                     (let [s (swap! state* normalize-state)]
                       (store-live-entries! (:entries s)))))
          roots   (root-id-index)]
      (->> entries
           (map (fn [[cell {:keys [cell-id view-id evidence]}]]
                  {:occurrence cell-id
                   :view-id    view-id
                   :root-id    (get roots (reactive/cell-root cell))
                   :connection (reactive/lifecycle cell)
                   :lifecycle  (project-lifecycle (reactive/intervals cell))
                   :evidence   evidence}))
           (sort-by :occurrence)
           (into [])))))
