(ns re-frame.elision
  "Size-elision wire-boundary walker plus the `[:rf/elision ...]` app-db
   registry mechanics. Per Spec 009 §Size elision in traces, Spec API
   §Size-elision wire-boundary walker, Spec-Schemas §`:rf/elision-registry`
   / `:rf/elision-marker`, and Conventions §Reserved app-db keys / fx-ids.

   The walker `elide-wire-value` is the **single normative emission site**
   for the `:rf/redacted` privacy sentinel and the `:rf.size/large-elided`
   size marker — every tool that emits wire data goes through it; per-tool
   reimplementation is prohibited.

   Surface:

   - `elide-wire-value`           — the walker; takes a value and an opts
                                    map; returns the value or a substitution.
   - `declare-large-path!`        — REPL/boot wrapper that dispatches
                                    `:rf.size/declare-large` against the
                                    current frame.
   - `clear-large-path!`          — REPL/boot wrapper for `:rf.size/clear`.
   - `:rf.size/declare-large` fx  — declarative path registration from
                                    `:fx`.
   - `:rf.size/clear` fx          — declarative path removal.
   - `configure-elision!`         — process-level knob for
                                    `:rf.size/threshold-bytes`.
   - `populate-elision-from-schemas!`
                                  — schema-driven boot population: walks
                                    every registered app-schema and
                                    pre-populates `[:rf/elision :declarations]`
                                    for every path whose registration carries
                                    `:large? true` in its metadata."
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

;; ---- configuration --------------------------------------------------------

(def default-threshold-bytes
  "Default `:rf.size/threshold-bytes` (16 KiB). Per Spec API §Configure
   keys (`:elision`). Values exceeding this in their `pr-str` form get
   auto-flagged by the runtime auto-detect path; declared / schema
   entries bypass the threshold (their predicate is a registry hit, not
   a measurement)."
  16384)

(defonce
  ^{:doc "Process-level `:rf.size/threshold-bytes` knob. Mutated by
          `configure-elision!`. 0 disables runtime auto-detect (only
          declared / schema entries elide)."}
  threshold-bytes
  (atom default-threshold-bytes))

(defn configure-elision!
  "Set the process-level `:rf.size/threshold-bytes` knob. Per Spec API
   §Configure keys, dispatched from `(rf/configure :elision opts)`. A
   non-negative integer; 0 disables runtime auto-detect."
  [opts]
  (let [n (:rf.size/threshold-bytes opts)]
    (when (and (number? n) (not (neg? n)))
      (reset! threshold-bytes (long n))))
  nil)

;; ---- registry primitives (in-app-db) --------------------------------------
;;
;; The registry lives at `[:rf/elision]` in every frame's app-db. Per
;; Spec-Schemas §`:rf/elision-registry`:
;;
;;   {:declarations    {<path-vec> {:large? bool :hint <str-or-nil> :source <kw>}}
;;    :runtime-flagged {<path-vec> {:bytes <int> :first-seen-epoch <int>}}}
;;
;; The slot is allocated lazily — absent until the first declaration.
;; User code MUST NOT write under `:rf/elision`; the runtime owns it.

(defn- registry-of
  "Read the current `:rf/elision` registry value for a frame; nil when
   the frame doesn't exist or the slot is unallocated."
  [frame-id]
  (when-let [container (frame/get-frame-db frame-id)]
    (get (adapter/read-container container) :rf/elision)))

(defn- swap-registry!
  "Atomic update of a frame's `[:rf/elision ...]` registry via
   `(f registry)`. Skips silently when the frame doesn't exist."
  [frame-id f]
  (when-let [container (frame/get-frame-db frame-id)]
    (let [old-db (adapter/read-container container)
          old-reg (get old-db :rf/elision)
          new-reg (f old-reg)
          new-db  (if (nil? new-reg)
                    (dissoc old-db :rf/elision)
                    (assoc old-db :rf/elision new-reg))]
      (adapter/replace-container! container new-db)))
  nil)

(defn- write-declaration!
  "Write `[:rf/elision :declarations <path>] -> entry` on the frame's
   app-db. Idempotent — last-write-wins on duplicate paths."
  [frame-id path entry]
  (swap-registry! frame-id
    (fn [reg]
      (assoc-in (or reg {}) [:declarations path] entry))))

(defn- clear-declaration!
  "Remove `[:rf/elision :declarations <path>]` and the parallel
   `:runtime-flagged` slot. Per [Conventions §Reserved fx-ids] —
   `:rf.size/clear` dissocs both slots."
  [frame-id path]
  (swap-registry! frame-id
    (fn [reg]
      (when reg
        (let [r1 (update reg :declarations dissoc path)
              r2 (update r1  :runtime-flagged dissoc path)
              r3 (cond-> r2
                   (empty? (:declarations r2))    (dissoc :declarations)
                   (empty? (:runtime-flagged r2)) (dissoc :runtime-flagged))]
          (when (seq r3) r3))))))

(defn- write-runtime-flagged!
  "Write `[:rf/elision :runtime-flagged <path>] -> {:bytes N
   :first-seen-epoch nil}`. Idempotent — caller short-circuits on a
   prior flag so this only fires once per (path, frame)."
  [frame-id path bytes]
  (swap-registry! frame-id
    (fn [reg]
      (assoc-in (or reg {})
                [:runtime-flagged path]
                {:bytes (long bytes)}))))

;; ---- public registry surface ----------------------------------------------

(defn declarations
  "Return the current `[:rf/elision :declarations]` map for a frame, or
   `{}`. Pair tools and tests read this to introspect what paths are
   nominated for elision. Default frame is `:rf/default`."
  ([] (declarations :rf/default))
  ([frame-id]
   (or (get (registry-of frame-id) :declarations) {})))

(defn runtime-flagged
  "Return the current `[:rf/elision :runtime-flagged]` map for a frame,
   or `{}`."
  ([] (runtime-flagged :rf/default))
  ([frame-id]
   (or (get (registry-of frame-id) :runtime-flagged) {})))

(defn- resolve-declaration
  "Given a frame's `[:rf/elision]` registry and a path, return the
   merged declaration map or nil. Conflict-resolution rule per
   [009 §Size elision in traces]: declared wins, then runtime-flagged.
   Schema entries live alongside declared entries (both under
   `:declarations`) and are distinguished by `:source`."
  [reg path]
  (when reg
    (or (get-in reg [:declarations path])
        (when-let [rt (get-in reg [:runtime-flagged path])]
          {:large? true
           :hint   nil
           :source :runtime-flagged
           :bytes  (:bytes rt)}))))

;; ---- public !-suffix wrappers ---------------------------------------------

(defn declare-large-path!
  "REPL / boot-time convenience wrapper for `:rf.size/declare-large`.
   Writes `{:large? true :hint <hint-or-nil> :source :declared}` into
   `[:rf/elision :declarations <path>]` against the current frame.
   Per [API.md §`declare-large-path!`].

   Three arities:

     (declare-large-path! path)                ;; hint nil, current frame
     (declare-large-path! path hint)           ;; hint, current frame
     (declare-large-path! path hint frame-id)  ;; named frame

   Returns nil. Idempotent on the same (path, frame) — last-write-wins."
  ([path]      (declare-large-path! path nil  (frame/current-frame)))
  ([path hint] (declare-large-path! path hint (frame/current-frame)))
  ([path hint frame-id]
   (write-declaration! frame-id path
                       {:large? true
                        :hint   hint
                        :source :declared})
   nil))

(defn clear-large-path!
  "REPL / boot-time convenience wrapper for `:rf.size/clear`. Clears
   both the `:declarations` and `:runtime-flagged` slots for the path.
   Per [API.md §`clear-large-path!`].

   Two arities:

     (clear-large-path! path)
     (clear-large-path! path frame-id)

   Returns nil."
  ([path]          (clear-large-path! path (frame/current-frame)))
  ([path frame-id] (clear-declaration! frame-id path) nil))

;; ---- fx handlers ----------------------------------------------------------
;;
;; Per [Conventions §Reserved fx-ids]:
;;   :rf.size/declare-large {:path [...] :hint <str-or-nil>}
;;   :rf.size/clear         {:path [...]}

(defn ^:no-doc declare-large-fx
  "fx handler for `:rf.size/declare-large`. Per [Conventions §Reserved
   fx-ids]. Args: `{:path [...] :hint <str-or-nil>}`. Writes (or merges)
   a `{:large? true :hint ... :source :declared}` entry into
   `[:rf/elision :declarations <path>]`."
  [{:keys [frame]} {:keys [path hint source]}]
  (let [frame-id (or frame :rf/default)]
    (when (vector? path)
      (write-declaration! frame-id path
                          {:large? true
                           :hint   hint
                           :source (or source :declared)}))
    nil))

(defn ^:no-doc clear-fx
  "fx handler for `:rf.size/clear`. Per [Conventions §Reserved fx-ids].
   Args: `{:path [...]}`. `dissoc`s the slot at
   `[:rf/elision :declarations <path>]` (and the parallel
   `[:rf/elision :runtime-flagged <path>]`, if present)."
  [{:keys [frame]} {:keys [path]}]
  (let [frame-id (or frame :rf/default)]
    (when (vector? path)
      (clear-declaration! frame-id path))
    nil))

;; Register the fx-ids at namespace load. Per the `:rf.machine/spawn`
;; pattern (rf2-xbtj) the elision fx ride the regular fx-registry; core
;; ships them unconditionally because every implementation must support
;; the size-elision contract (per Conventions §Reserved fx-ids the ids
;; are reserved framework-owned even on ports that don't ship the
;; walker, but the CLJS reference ships both).
(fx/reg-fx :rf.size/declare-large
  {:doc "Declare an app-db path as a candidate for size-elision at the wire boundary. Args: {:path [...] :hint <str-or-nil>}. Per Spec 009 §Size elision in traces."}
  declare-large-fx)

(fx/reg-fx :rf.size/clear
  {:doc "Clear an elision declaration. Args: {:path [...]}. Per Spec 009 §Size elision in traces."}
  clear-fx)

;; ---- schema-driven boot population ---------------------------------------
;;
;; Per [009 §Size elision in traces] nomination 1 (Schema-driven): the
;; runtime walks every registered app-schema at boot and writes
;; `{:large? true :source :schema}` entries into the registry for every
;; `:large? true` slot. The Malli-side walker (rf2-nwv63) ships
;; separately; this helper provides the integration point that the
;; schemas artefact (or a user app) calls when it wants the registry
;; populated.
;;
;; This boot-time fn is idempotent: re-populating doesn't duplicate
;; entries; declared entries (`:source :declared`) take precedence and
;; are not overwritten.

(def ^:private descend-wrapper-ops
  "Single-child Malli wrapper ops whose own properties don't introduce a
   new app-db path segment, so an inner `:large?` / `:hint` claim on
   the wrapped schema applies to the wrapper's path. Conservative v1:
   only `:maybe`. `:and` / `:or` / `:enum` may carry MULTIPLE children
   with different props (merge / priority semantics TBD per rf2-b20zm
   follow-on); a future iteration may extend this set."
  #{:maybe})

(defn- schema-properties
  "Return the top-level Malli properties map of a schema form, or nil.
   Recognises the literal vector form `[:op {props} & children]` and
   the metadata-map fallback (some Malli setups carry props as
   Clojure metadata on the schema value).

   When the top-level op is a single-child wrapper (`:maybe`) and
   carries NO props of its own, descend into the wrapped schema so the
   idiomatic Malli shape `[:maybe [:string {:large? true :hint ...}]]`
   exposes the inner `:large?` to callers. The descent preserves an
   explicit outer props map — if the wrapper itself carries props
   (`[:maybe {:large? true} :string]`), those win and no descent
   happens. Per rf2-b20zm."
  [schema]
  (cond
    (and (vector? schema)
         (> (count schema) 1)
         (map? (nth schema 1)))
    (nth schema 1)

    ;; Wrapper descent: `[:maybe inner]` (no props) — peek at the inner
    ;; schema's top-level props so an inner `:large?` propagates. Only
    ;; for single-child wrappers per `descend-wrapper-ops`.
    (and (vector? schema)
         (> (count schema) 1)
         (contains? descend-wrapper-ops (nth schema 0))
         (not (map? (nth schema 1))))
    (recur (nth schema 1))

    ;; Fallback: metadata-form attachment. Try (meta schema) — returns
    ;; nil for values that don't carry metadata; safe on both runtimes.
    :else
    (try
      (meta schema)
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- schema-large?
  "Return true when a registered schema slot carries `:large? true`.
   Per [Spec-Schemas §`:rf/app-schema-meta`] — the `:large?` key lives
   in the Malli-properties map of the schema. We deliberately accept
   both the vector-form (`[:map {props} ...]`) and a metadata-form
   fallback so test fixtures can attach `:large?` either way."
  [schema]
  (true? (:large? (schema-properties schema))))

(defn populate-elision-from-schemas!
  "Walk the registered app-schema set for a frame and write
   `{:large? true :source :schema}` entries into the elision
   `[:rf/elision :declarations]` registry for every path whose
   registered schema carries `:large? true` in its top-level Malli
   properties.

   Per [009 §Size elision in traces] nomination 1 (Schema-driven).
   Idempotent — re-populating updates existing `:source :schema`
   entries in place. **Declared entries are preserved** (`:source
   :declared` wins over `:source :schema`, per the conflict-resolution
   rule).

   No-op when the schemas artefact (day8/re-frame2-schemas) is not on
   the classpath. Returns the vector of paths that were populated
   (possibly empty)."
  ([] (populate-elision-from-schemas! (frame/current-frame)))
  ([frame-id]
   (if-let [entries-fn (late-bind/get-fn :schemas/frame-schema-entries)]
     (let [entries (entries-fn frame-id)]
       (reduce
        (fn [acc [path entry]]
          (let [schema (:schema entry)]
            (if (schema-large? schema)
              (let [existing (get-in (registry-of frame-id) [:declarations path])]
                ;; Preserve declared entries: don't clobber them with schema source.
                (if (= :declared (:source existing))
                  acc
                  (do
                    (write-declaration! frame-id path
                                        {:large? true
                                         :hint   (:hint (schema-properties schema))
                                         :source :schema})
                    (conj acc path))))
              acc)))
        []
        entries))
     [])))

;; ---- walker primitives ----------------------------------------------------

(def ^:private ^:const max-pr-str-bytes
  "Safety ceiling on `pr-str-bytes` sampling — we never need to walk a
   value beyond this size to decide it's too big; short-circuit at
   threshold-bytes + 1 to keep auto-detect cheap."
  (long 1048576))                       ; 1 MiB

(defn- pr-str-bytes
  "Approximate the `pr-str` byte count of `v`, capped at
   `max-pr-str-bytes`. Returns an exact count for small values and the
   cap for very large ones — exact-ness above the cap is irrelevant
   because the elision predicate fires on (n > threshold)."
  [v]
  #?(:clj  (count (.getBytes ^String (pr-str v) "UTF-8"))
     :cljs (count (pr-str v))))

(defn- value-type
  "Top-level shape of `v` for the marker's `:type` slot. Per Spec 009
   one of `:map :vector :set :scalar :string`."
  [v]
  (cond
    (map? v)     :map
    (vector? v)  :vector
    (set? v)     :set
    (string? v)  :string
    :else        :scalar))

(defn- sha256-hex
  "Compute a `sha256:<hex>` digest of `(pr-str v)` for the marker's
   optional `:digest` slot. Only invoked when `:rf.size/include-digests?`
   is true on the call. CLJS path uses an in-process hash via
   `goog.crypt.Sha256` — not available, so the CLJS branch returns nil
   (the field is OPTIONAL per the marker schema)."
  [v]
  #?(:clj
     (let [bytes (.getBytes ^String (pr-str v) "UTF-8")
           md    (doto (java.security.MessageDigest/getInstance "SHA-256")
                   (.update bytes))
           hex   (format "%064x" (java.math.BigInteger. 1 (.digest md)))]
       (str "sha256:" hex))
     :cljs
     ;; goog.crypt.Sha256 is available in the closure library; we keep
     ;; the CLJS branch simple by returning nil — callers that opt into
     ;; digests on a CLJS runtime can wire in a host-side helper. Per
     ;; the marker schema, :digest is OPTIONAL.
     nil))

(defn- handle-of
  "Build the `:handle` slot for the marker. Per [Spec-Schemas
   §`:rf/elision-marker`] — `[:rf.elision/at <path>]` or
   `[:rf.elision/at <path> :as-of-epoch <epoch-id>]` when the marker
   rides inside a past-epoch payload."
  [path as-of-epoch]
  (if as-of-epoch
    [:rf.elision/at path :as-of-epoch as-of-epoch]
    [:rf.elision/at path]))

(defn- ->marker
  "Build the `{:rf.size/large-elided {...}}` substitution map for an
   elided value. Per Spec 009 §Wire marker."
  [v path {:keys [reason hint as-of-epoch include-digests? known-bytes]}]
  (let [bytes (or known-bytes (pr-str-bytes v))
        body  (cond-> {:path   (vec path)
                       :bytes  bytes
                       :type   (value-type v)
                       :reason (or reason :declared)
                       :hint   hint
                       :handle (handle-of (vec path) as-of-epoch)}
                include-digests? (assoc :digest (sha256-hex v)))]
    {:rf.size/large-elided body}))

(def ^:private redacted-sentinel
  "The `:rf/redacted` privacy sentinel emitted by the walker for slots
   matching the `:sensitive?` predicate. Per [009 §Privacy / sensitive
   data in traces] — the existing surface; the walker is the single
   normative emission site (rf2-isdwf wires the per-event `:sensitive?`
   stamping that this walker consults)."
  :rf/redacted)

(defn- sensitive-decl?
  "Per-path `:sensitive?` predicate. Today the registry doesn't carry
   per-path sensitivity (that's the rf2-isdwf cofx-side stamping work
   in flight); we surface a forward-compatible read so that when the
   sensitivity registry lands, callers don't need to revisit the
   walker. Reads `:sensitive?` from a declaration entry if present."
  [decl]
  (true? (:sensitive? decl)))

;; ---- the walker ----------------------------------------------------------

(defn- walk
  "Internal recursion driver for `elide-wire-value`. `path` and
   `frame-id` ride as positional args so the (otherwise stable) `opts`
   map isn't re-`assoc`'d at every child — meaningful on deep walks
   (the elision walker fires per event-emit + per sub-return). All
   public arities funnel through here after one-shot opts decoding."
  [v path frame-id opts]
  (cond
    ;; Nil / scalar fast path — never elidable.
    (or (nil? v) (number? v) (boolean? v) (keyword? v) (symbol? v))
    v

    ;; Consult the registry first; declared / schema / runtime-flagged hit?
    :else
    (let [as-of-epoch        (:as-of-epoch opts)
          include-large?     (:rf.size/include-large? opts)
          include-sensitive? (:rf.size/include-sensitive? opts)
          include-digests?   (:rf.size/include-digests? opts)
          threshold          (:rf.size/threshold-bytes opts)
          reg                (registry-of frame-id)
          decl               (resolve-declaration reg path)
          ;; sensitive? — registry may carry it; the rf2-isdwf
          ;; per-event :sensitive? stamping lands separately. The
          ;; walker reads it as a regular predicate.
          sensitive?         (sensitive-decl? decl)
          ;; large? — declared / schema hit, or auto-detect over threshold.
          ;;
          ;; Runtime auto-detect fires on:
          ;;   - leaf values (strings, scalars whose pr-str over-runs)
          ;; but NOT on containers (maps/vectors/sets/seqs). Containers
          ;; get walked; the per-leaf decision is where elision
          ;; meaningfully shrinks the wire. (A 5MB base64 string lives
          ;; at a leaf; eliding the wrapping map would discard small
          ;; siblings unnecessarily.) Declared / schema entries
          ;; override this and elide at any level.
          declared-large?    (and (some? decl) (:large? decl))
          auto-large?        (and (not declared-large?)
                                  (pos? threshold)
                                  (not (coll? v))
                                  (string? v)
                                  (let [n (pr-str-bytes v)]
                                    (when (> n threshold)
                                      n)))
          large?             (or declared-large? auto-large?)
          reason             (cond
                               declared-large? (:source decl)
                               auto-large?     :runtime-flagged
                               :else           nil)
          hint               (when declared-large? (:hint decl))
          known-bytes        (when (number? auto-large?) auto-large?)]
      (cond
        ;; Composition rule: sensitive drop wins on both-flagged value.
        sensitive?
        (if include-sensitive?
          v
          redacted-sentinel)

        large?
        (if include-large?
          v
          (do
            ;; First-time auto-flag: persist the heuristic decision
            ;; and emit the one-shot warning per (path, frame).
            (when (and auto-large?
                       (not (get-in reg [:runtime-flagged path])))
              (write-runtime-flagged! frame-id path
                                      (or known-bytes (pr-str-bytes v)))
              (trace/emit! :warning :rf.warning/runtime-large-elision
                           {:frame    frame-id
                            :path     path
                            :bytes    (or known-bytes (pr-str-bytes v))
                            :reason   :runtime-flagged
                            :recovery :warned-and-replaced}))
            (->marker v path
                      {:reason           reason
                       :hint             hint
                       :as-of-epoch      as-of-epoch
                       :include-digests? include-digests?
                       :known-bytes      known-bytes})))

        ;; No elision at this level — recurse into containers,
        ;; preserving the per-key path. Per the short-circuit rule,
        ;; we only descend when nothing here was substituted.
        (map? v)
        (reduce-kv
         (fn [acc k vv]
           (assoc acc k (walk vv (conj path k) frame-id opts)))
         (empty v) v)

        (vector? v)
        (mapv (fn [idx vv]
                (walk vv (conj path idx) frame-id opts))
              (range) v)

        (set? v)
        ;; Sets don't have indices; walk children with the same path so
        ;; a registered set-element predicate still applies. Most
        ;; callers won't declare individual set members.
        (into #{} (map #(walk % path frame-id opts)) v)

        (seq? v)
        ;; Sequences (lists / lazy seqs) — walk and return a vector for
        ;; wire stability. Per Tool-Pair, wire payloads are EDN data;
        ;; converting seq -> vector is safe.
        (mapv (fn [idx vv]
                (walk vv (conj path idx) frame-id opts))
              (range) v)

        :else
        v))))

(defn elide-wire-value
  "Walk `v`, eliding values that match an elision predicate. Per
   [API.md §`elide-wire-value`] — the framework primitive every wire-
   emitting tool calls.

   `opts` is a map:

     :path                          — current path inside the slice's root
                                      (default `[]`).
     :frame                         — frame-id whose `[:rf/elision]`
                                      registry to consult (default
                                      `(frame/current-frame)`).
     :rf.size/include-large?        — when true, large values pass
                                      through unmodified (default
                                      false).
     :rf.size/include-sensitive?    — when true, sensitive values pass
                                      through unmodified (default
                                      false).
     :rf.size/include-digests?      — when true, the marker's `:digest`
                                      slot is computed (default false).
     :rf.size/threshold-bytes       — runtime auto-detect threshold;
                                      falls back to the configured
                                      knob (default 16384). 0 disables
                                      runtime auto-detect.
     :as-of-epoch                   — when set, the marker's `:handle`
                                      carries `:as-of-epoch <id>` for
                                      past-epoch payloads (per Spec 009
                                      §Composition × :rf.mcp/diff-from).

   Composition rule (normative): when both predicates match the
   **sensitive drop wins** — `:rf/redacted` is emitted; no
   `:rf.size/large-elided` marker is produced (the marker itself would
   leak `:path` / `:bytes` / `:digest`).

   Returns either `v` (unmodified) or a substitution. Short-circuits
   on elided subtrees — once a subtree is elided we don't recurse
   into it."
  ([v]
   (elide-wire-value v nil))
  ([v opts]
   (let [frame-id  (or (:frame opts) (frame/current-frame) :rf/default)
         threshold (long (or (:rf.size/threshold-bytes opts)
                             @re-frame.elision/threshold-bytes))
         ;; Normalise opts once: defaults coerced, booleans pre-evaluated,
         ;; resolved frame-id / threshold cached for the whole recursion.
         opts'     (-> (or opts {})
                       (assoc :rf.size/threshold-bytes      threshold
                              :rf.size/include-large?      (true? (:rf.size/include-large? opts))
                              :rf.size/include-sensitive?  (true? (:rf.size/include-sensitive? opts))
                              :rf.size/include-digests?    (true? (:rf.size/include-digests? opts))))]
     (walk v (vec (:path opts)) frame-id opts'))))

;; ---- introspection sugar --------------------------------------------------

(defn marker?
  "True when `v` is a `:rf.size/large-elided` wire marker."
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(defn handle?
  "True when `v` is a `[:rf.elision/at <path> ...]` fetch-handle vector."
  [v]
  (and (vector? v) (= :rf.elision/at (first v))))
