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
                                    every registered app-schema (deeply,
                                    via the schemas artefact's late-bind
                                    walker) and pre-populates
                                    `[:rf/elision :declarations]` for every
                                    path whose schema carries `:large? true`
                                    in its per-slot properties at any depth."
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

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
;; `:large? true` slot. The Malli-side deep walker
;; (`re-frame.schemas.walker/extract-large-paths-from-schema`,
;; rf2-nwv63) ships in the schemas artefact and is consumed here
;; through the `:schemas/extract-large-paths-from-schema` late-bind
;; hook — re-frame.core never statically requires the schemas artefact
;; (per rf2-p7va — schemas is optional).
;;
;; The walker returns `{path declaration}` for every per-slot `:large?`
;; declaration nested anywhere inside the registered schema (Spec 010
;; §`:large?` — schema-driven size-elision nomination, lines 219-262);
;; this fn folds every returned path into `[:rf/elision :declarations]`
;; with the Spec 009 conflict-resolution rule applied: existing
;; `:source :declared` entries are preserved (declared beats schema).
;;
;; Idempotent — re-populating against the same `(schemas, declared)`
;; pair produces the same result.

(defn populate-elision-from-schemas!
  "Walk every registered app-schema for a frame and write
   `{:large? true :source :schema}` entries into the elision
   `[:rf/elision :declarations]` registry for every path whose
   schema (at any depth) carries `:large? true` in its Malli per-slot
   properties.

   Per [009 §Size elision in traces] nomination 1 (Schema-driven) and
   [Spec 010 §`:large?` — schema-driven size-elision nomination]
   (lines 219-262). Nested declarations are honoured — a `:large?`
   slot at `[:map [:a [:map [:b {:large? true} :string]]]]` registered
   at `[:root]` lands at `[:root :a :b]`.

   Idempotent — re-populating updates existing `:source :schema`
   entries in place. **Declared entries are preserved** (`:source
   :declared` wins over `:source :schema`, per the conflict-resolution
   rule).

   No-op when the schemas artefact (day8/re-frame2-schemas) is not on
   the classpath — both required late-bind hooks
   (`:schemas/frame-schema-entries` and
   `:schemas/extract-large-paths-from-schema`) must be present.
   Returns the vector of paths that were populated (possibly empty)."
  ([] (populate-elision-from-schemas! (frame/current-frame)))
  ([frame-id]
   (let [entries-fn (late-bind/get-fn :schemas/frame-schema-entries)
         extract-fn (late-bind/get-fn :schemas/extract-large-paths-from-schema)]
     (if (and entries-fn extract-fn)
       (let [entries (entries-fn frame-id)
             ;; Walk every registered schema deeply and merge the
             ;; per-path declarations across the whole frame's schema
             ;; set. Each schema is walked at its own `reg-app-schema`
             ;; path so nested `:large?` slots land at the absolute
             ;; app-db path.
             schema-decls (reduce-kv
                            (fn [acc base-path entry]
                              (merge acc (extract-fn (:schema entry) base-path)))
                            {}
                            entries)]
         (reduce-kv
           (fn [acc path decl]
             (let [existing (get-in (registry-of frame-id) [:declarations path])]
               ;; Preserve declared entries — declared beats schema.
               (if (= :declared (:source existing))
                 acc
                 (do
                   (write-declaration! frame-id path
                                       {:large? true
                                        :hint   (:hint decl)
                                        :source :schema})
                   (conj acc path)))))
           []
           schema-decls))
       []))))

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
   matching the `:sensitive?` predicate. Re-exported from
   `re-frame.privacy/redacted-sentinel` so the in-handler
   `with-redacted` interceptor and this wire-walker emit the same value.
   Per Spec 009 §Privacy / sensitive data in traces — privacy owns the
   policy locus; the walker remains the single normative emission site
   for the elision pathway."
  privacy/redacted-sentinel)

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
  "Internal recursion driver for `elide-wire-value`.

  HOT PATH: invoked per node during every wire-emission walk; under
  prod with event-emit / error-emit listeners installed this fires
  on every cascade. Per rf2-oetyi the registry lookup and opts-
  derived locals (`include-large?`, `include-sensitive?`, threshold,
  …) are hoisted into `elide-wire-value`'s top arity and ride here
  as `ctx` — a per-walk-call invariant struct. Only `path` changes
  on recursion. Pre-rf2-oetyi `walk` re-read `(registry-of frame-id)`
  and destructured `opts` on every recursive node, so a million-node
  walk did a million map lookups for a value that doesn't change."
  [v path ctx]
  (cond
    ;; Nil / scalar fast path — never elidable.
    (or (nil? v) (number? v) (boolean? v) (keyword? v) (symbol? v))
    v

    ;; Consult the registry first; declared / schema / runtime-flagged hit?
    :else
    (let [reg                (:reg ctx)
          frame-id           (:frame-id ctx)
          threshold          (:threshold ctx)
          include-large?     (:include-large? ctx)
          include-sensitive? (:include-sensitive? ctx)
          include-digests?   (:include-digests? ctx)
          as-of-epoch        (:as-of-epoch ctx)
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
           (assoc acc k (walk vv (conj path k) ctx)))
         (empty v) v)

        (vector? v)
        (mapv (fn [idx vv]
                (walk vv (conj path idx) ctx))
              (range) v)

        (set? v)
        ;; Sets don't have indices; walk children with the same path so
        ;; a registered set-element predicate still applies. Most
        ;; callers won't declare individual set members.
        (into #{} (map #(walk % path ctx)) v)

        (seq? v)
        ;; Sequences (lists / lazy seqs) — walk and return a vector for
        ;; wire stability. Per Tool-Pair, wire payloads are EDN data;
        ;; converting seq -> vector is safe.
        (mapv (fn [idx vv]
                (walk vv (conj path idx) ctx))
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
         ;; Per rf2-oetyi: hoist the registry read and opts-derived
         ;; locals into one immutable ctx struct, built once here and
         ;; threaded positionally through `walk` — rather than re-
         ;; resolving `(registry-of frame-id)` and re-destructuring
         ;; opts on every recursive node. The registry is constant
         ;; for the duration of one walk; opts are user input that
         ;; doesn't mutate mid-recursion. HOT PATH under prod with
         ;; event-emit / error-emit listeners installed.
         ctx       {:reg                (registry-of frame-id)
                    :frame-id           frame-id
                    :threshold          threshold
                    :include-large?     (true? (:rf.size/include-large? opts))
                    :include-sensitive? (true? (:rf.size/include-sensitive? opts))
                    :include-digests?   (true? (:rf.size/include-digests? opts))
                    :as-of-epoch        (:as-of-epoch opts)}]
     (walk v (vec (:path opts)) ctx))))

;; ---- introspection sugar --------------------------------------------------

(defn marker?
  "True when `v` is a `:rf.size/large-elided` wire marker."
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(defn handle?
  "True when `v` is a `[:rf.elision/at <path> ...]` fetch-handle vector."
  [v]
  (and (vector? v) (= :rf.elision/at (first v))))
