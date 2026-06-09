(ns re-frame.elision
  "Schema-first wire-boundary elision.

  Canonical declarations come from app-schema slot metadata:
  `{:large? true}` hydrates `[:rf.runtime/elision :declarations]`, and
  `{:sensitive? true}` hydrates
  `[:rf.runtime/elision :sensitive-declarations]`. Handler metadata
  `:sensitive?` remains the coarse escape hatch for cross-cutting
  handlers. There are no imperative large-path APIs.

  EP-0001 (rf2-vzld77): the elision declaration registry is DURABLE,
  serializable framework state (it must survive epoch-restore / SSR-
  hydration so an off-box projection redacts consistently), so it lives in
  the frame's **runtime-db** partition at `[:rf.runtime/elision …]` — NOT in
  app-db (where it briefly sat under the retired `:rf/runtime` root). Per
  Conventions §Reserved runtime-db keys. Reads come off the runtime-db
  projection; writes go through `frame/swap-runtime-db!` (the runtime-db
  partition write surface)."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- runtime size threshold ----------------------------------------------
;;
;; Per API.md §Size-elision wire-boundary walker and §Configure keys, the
;; runtime auto-detect threshold for the `:rf.warning/large-value-unschema'd`
;; advisory is configurable. Precedence (normative, API.md L507):
;;
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure! :elision …)`  >  default
;;
;; A threshold of 0 disables runtime auto-detect entirely (only declared /
;; schema-marked entries elide); the unschema'd-large warning never fires.
;; The default mirrors the documented `{:rf.size/threshold-bytes 16384}`.

(def ^:private default-threshold-bytes 16384)

(defonce ^:private config
  ;; Map shape so future :elision configure-keys land additively, matching
  ;; `re-frame.subs.cache/config`.
  (atom {:rf.size/threshold-bytes default-threshold-bytes}))

(defn configure!
  "Update the elision configuration. Supports
  `{:rf.size/threshold-bytes N}` — a non-negative integer runtime
  auto-detect size threshold for the `:rf.warning/large-value-unschema'd`
  advisory (0 disables runtime auto-detect; only declared / schema-marked
  entries elide). Per API.md §Configure keys (`:elision`) and Spec 009
  §Size elision in traces. Routed from `re-frame.core/configure!`."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:rf.size/threshold-bytes])))
  nil)

(defn current-config
  "Return the current elision configuration map. Public for tests and
  tools that want to display the configured runtime size threshold."
  []
  @config)

(defn- configured-threshold-bytes
  "The configured runtime size threshold, falling back to the documented
  default when no `configure` call has set it."
  []
  (let [v (:rf.size/threshold-bytes @config)]
    (if (some? v) v default-threshold-bytes)))

(defonce ^:private warned-unschema'd
  (atom #{}))

(defn- registry-of
  [frame-id]
  (when-let [container (frame/runtime-db-container frame-id)]
    (get-in (adapter/read-container container) [:rf.runtime/elision])))

(defn ^:no-doc write-elision-slot
  "Set or clear the per-frame elision registry inside `runtime-db`. When
  `new-reg` is non-empty it lands at `[:rf.runtime/elision]`. When empty,
  the `:rf.runtime/elision` key is removed so a frame that never used
  elision doesn't manifest a stray nil sub-tree.

  Internal helper; exposed (with `^:no-doc`) so the sibling
  `re-frame.marks` ns — which writes through the SAME slot via
  `add-marks` / `set-marks` / `clear-app-db-marks!` — can share a
  single source of truth for the prune logic. Not part of the
  public API.

  EP-0001 (rf2-vzld77): operates on the runtime-db partition value (the
  elision registry is durable framework state — Conventions §Reserved
  runtime-db keys), no longer on the app-db `:rf/runtime` root."
  [runtime-db new-reg]
  (cond
    (seq new-reg)
    (assoc runtime-db :rf.runtime/elision new-reg)

    ;; Clearing — only mutate when there's actually an :rf.runtime/elision
    ;; slot to clear, so a frame that never used elision doesn't get a
    ;; stray nil entry.
    (contains? runtime-db :rf.runtime/elision)
    (dissoc runtime-db :rf.runtime/elision)

    :else
    runtime-db))

(defn ^:no-doc swap-elision-slot!
  "Read-transform-write helper for the per-frame elision registry.

  Reads the registry at `[:rf.runtime/elision]` from `frame-id`'s
  runtime-db, applies `(f reg) -> new-reg`, and writes the result back
  through `write-elision-slot` (which removes a stranded
  `:rf.runtime/elision` when the slot clears). No-op when the frame's
  container does not exist. Returns nil.

  Internal helper; exposed (with `^:no-doc`) so the sibling
  `re-frame.marks` ns — which mutates the SAME slot from its
  `add-marks` / `set-marks` / `clear-app-db-marks!`
  paths — can share a single source of truth for the read-transform-
  write skeleton. Not part of the public API.

  EP-0001 (rf2-vzld77): writes through `frame/swap-runtime-db!` (the
  runtime-db partition of the one physical frame-state container) — the
  elision registry is durable framework state and lives in runtime-db, not
  in the retired app-db `:rf/runtime` root."
  [frame-id f]
  (frame/swap-runtime-db! frame-id
                          (fn [old-runtime-db]
                            (let [old-runtime-db (or old-runtime-db {})]
                              (write-elision-slot
                                old-runtime-db
                                (f (get-in old-runtime-db [:rf.runtime/elision]))))))
  nil)

(defn declarations
  "Return schema-derived `:large?` declarations for `frame-id`. EP-0002 —
  the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` registry."
  ([] (declarations (frame/require-current-frame!
                      :elision-declarations
                      {:where 're-frame.elision/declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :declarations) {})))

(defn sensitive-declarations
  "Return schema-derived `:sensitive?` declarations for `frame-id`. EP-0002
  — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` registry."
  ([] (sensitive-declarations (frame/require-current-frame!
                                :elision-sensitive-declarations
                                {:where 're-frame.elision/sensitive-declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :sensitive-declarations) {})))

(defn- schema-declarations
  [frame-id extract-hook]
  (let [entries-fn (late-bind/get-fn :schemas/frame-schema-entries)
        extract-fn (late-bind/get-fn extract-hook)]
    (if (and entries-fn extract-fn)
      (reduce-kv
        (fn [acc base-path entry]
          (merge acc (extract-fn (:schema entry) base-path)))
        {}
        (entries-fn frame-id))
      {})))

(defn- install-schema-declarations!
  [frame-id registry-key schema-decls]
  (swap-elision-slot! frame-id
    (fn [reg]
      (let [without-schema (reduce-kv
                             (fn [acc path decl]
                               (if (= :schema (:source decl))
                                 acc
                                 (assoc acc path decl)))
                             {}
                             (get reg registry-key))
            merged         (merge without-schema schema-decls)
            reg'           (if (seq merged)
                             (assoc (or reg {}) registry-key merged)
                             (dissoc (or reg {}) registry-key))]
        reg')))
  (vec (keys schema-decls)))

(defn populate-elision-from-schemas!
  "Populate `[:rf.runtime/elision :declarations]` from `{:large? true}`
  schema slot metadata. Returns the populated paths. EP-0002 — the
  zero-arity ambient form resolves the frame through the carried-invariant
  scope chain (`frame/require-current-frame!`); under no established scope
  it raises `:rf.error/no-frame-context` rather than populating a
  synthesised `:rf/default` registry. The per-dispatch / registration-time
  callers (router, schema storage) always pass an explicit `frame-id`."
  ([] (populate-elision-from-schemas!
        (frame/require-current-frame!
          :populate-elision-from-schemas
          {:where 're-frame.elision/populate-elision-from-schemas!})))
  ([frame-id]
   (install-schema-declarations!
     frame-id
     :declarations
     (schema-declarations frame-id :schemas/extract-large-paths-from-schema))))

(defn populate-sensitive-from-schemas!
  "Populate `[:rf.runtime/elision :sensitive-declarations]` from
  `{:sensitive? true}` schema slot metadata. Returns the populated
  paths. EP-0002 — the zero-arity ambient form resolves the frame through
  the carried-invariant scope chain (`frame/require-current-frame!`); under
  no scope it raises `:rf.error/no-frame-context` rather than populating a
  synthesised `:rf/default` registry."
  ([] (populate-sensitive-from-schemas!
        (frame/require-current-frame!
          :populate-sensitive-from-schemas
          {:where 're-frame.elision/populate-sensitive-from-schemas!})))
  ([frame-id]
   (install-schema-declarations!
     frame-id
     :sensitive-declarations
     (schema-declarations frame-id :schemas/extract-sensitive-paths-from-schema))))

(defn populate-from-schemas!
  "Refresh both schema-owned declaration registries for `frame-id`. EP-0002
  — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain; under no scope it raises
  `:rf.error/no-frame-context`."
  ([] (populate-from-schemas!
        (frame/require-current-frame!
          :populate-from-schemas
          {:where 're-frame.elision/populate-from-schemas!})))
  ([frame-id]
   {:large     (populate-elision-from-schemas! frame-id)
    :sensitive (populate-sensitive-from-schemas! frame-id)}))

(defn clear-warning-cache!
  []
  (reset! warned-unschema'd #{})
  nil)

(defn- pr-str-bytes
  [v]
  #?(:clj  (count (.getBytes ^String (pr-str v) "UTF-8"))
     :cljs (count (pr-str v))))

(defn- value-type
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- sha256-hex
  [v]
  #?(:clj
     (let [bytes (.getBytes ^String (pr-str v) "UTF-8")
           md    (doto (java.security.MessageDigest/getInstance "SHA-256")
                   (.update bytes))]
       (str "sha256:"
            (format "%064x" (java.math.BigInteger. 1 (.digest md)))))
     :cljs
     nil))

(defn- handle-of
  [path as-of-epoch]
  (if as-of-epoch
    [:rf.elision/at path :as-of-epoch as-of-epoch]
    [:rf.elision/at path]))

(defn- ->marker
  [v path {:keys [hint as-of-epoch include-digests?]}]
  (let [body (cond-> {:path   (vec path)
                      :bytes  (pr-str-bytes v)
                      :type   (value-type v)
                      :reason :schema
                      :hint   hint
                      :handle (handle-of (vec path) as-of-epoch)}
               include-digests? (assoc :digest (sha256-hex v)))]
    {:rf.size/large-elided body}))

(defn- warn-large-unschema'd!
  [frame-id path bytes]
  (when interop/debug-enabled?
    (let [k [frame-id (vec path)]]
      (when-not (contains? @warned-unschema'd k)
        (swap! warned-unschema'd conj k)
        (trace/emit! :warning :rf.warning/large-value-unschema'd
                     {:frame    frame-id
                      :path     (vec path)
                      :bytes    bytes
                      :hint     "Add `{:large? true}` to the schema slot for this path."
                      :recovery :no-recovery})))))

;; ---- collection-coordinate declaration matching (rf2-wm9kp) ---------------
;;
;; Schema-derived declarations are INDEX-FREE: the schema walker descends
;; positional/keyed containers (`:vector` / `:sequential` / `:set` /
;; `:map-of`) at the SAME base-path (walker.cljc ~L145), because a vector
;; index or a `:map-of` key is not a declarable app-db slot. So a schema
;; `[:items] [:vector [:map [:token {:sensitive? true} :string]]]` declares
;; the index-free path `[:items :token]`, while the runtime value lives at
;; the INDEXED path `[:items 0 :token]` (and a `:map-of` value lives at the
;; KEYED path `[:by-id "a" :secret]` against decl `[:by-id :secret]`).
;;
;; The wire-elision walker walks a RUNTIME value, so it sees the indexed /
;; keyed paths. To match the index-free declarations without re-walking the
;; schema (the walker doesn't carry it), we thread a SET of candidate
;; declaration-coordinate paths alongside the concrete runtime path:
;;
;;   - Descending a VECTOR / SEQ element at index `i`: the index is
;;     UNAMBIGUOUSLY a collection coordinate (never a named slot), so each
;;     candidate forks into the INDEX-FREE interpretation `c` (unchanged —
;;     matches schema decls that descend positional containers at the same
;;     base-path) AND the LITERAL-INDEX interpretation `(conj c i)` (matches
;;     a declaration that itself pins a concrete index, e.g. `[:tokens 0]`),
;;     the latter pruned by `decl-prefixes` (see `fork-index-paths`).
;;     Riding an index never floats a declaration past a NAMED slot, so the
;;     index-free interpretation is kept for ALL candidates (incl. the empty
;;     seed) without the map-key skip's position guard. A SET element has no
;;     stable index ⇒ index-free pass-through only.
;;   - Descending a MAP key `k`: a runtime map is structurally ambiguous —
;;     `k` may be a NAMED `:map` slot (a real segment ⇒ `(conj c k)`) OR a
;;     `:map-of` key (a collection coordinate ⇒ `c` unchanged). We can't tell
;;     which from the value alone, so we fork each candidate into BOTH
;;     interpretations and let the declaration table disambiguate at the leaf
;;     (only the interpretation that actually matches a declared path fires).
;;     The `:map-of`-key (skip) fork is POSITION-PRECISE: only a NON-EMPTY
;;     candidate — one that has already consumed ≥1 declared segment — may
;;     skip a key. The empty seed `[]` advances only via the named fork (see
;;     `fork-decl-paths`), so a declaration cannot FLOAT past leading named
;;     map slots and falsely match the same key-sequence at a deeper,
;;     non-declared position.
;;
;; The fork would grow combinatorially with map depth, so we PRUNE: a
;; candidate that is not a prefix of ANY declared path can never match and is
;; dropped. `decl-prefixes` (all prefixes of every sensitive ∪ large decl
;; path) bounds the live candidate set to the declaration cardinality — tiny
;; in practice, and empty (so zero overhead) when nothing is declared.
;;
;; Precision: the runtime path `[:by-id "a" :secret]` matches decl
;; `[:by-id :secret]` only because `:secret` is a real map slot under the
;; `:map-of` value (`[:by-id]` is a non-empty partial match, so it legally
;; skips the map-of key `"a"`); a sibling NON-sensitive leaf
;; `[:by-id "a" :other]` never matches (its decl-coordinate candidates
;; `[:by-id :other]` / `[:by-id "a" :other]` aren't declared). And decl
;; `[:auth :password]` does NOT match the deeper `[:tags :some-other-slot
;; :auth :password]`: the `[]` seed cannot skip the leading `:tags` /
;; `:some-other-slot` named slots, so it never reaches the same-named `:auth`
;; at that non-declared position. The concrete runtime `path` still drives
;; marker `:path` / `:handle` and the unschema'd-large warning, so an agent's
;; follow-up `get-path` lands on the exact indexed location.

(declare walk marker?)

(defn- prefixes
  "All non-empty prefixes (including the full path) of `path`, as a set.
  Used to seed `decl-prefixes` so the candidate decl-path fork can be
  pruned to paths that could still reach a declaration."
  [path]
  (into #{} (map #(subvec path 0 %)) (range 1 (inc (count path)))))

(defn- decl-prefix-set
  "Build the set of every prefix of every declared path (sensitive ∪
  large). A candidate declaration-coordinate path is kept alive during the
  walk only while it is a member of this set — anything outside it can
  never reach a declaration, so dropping it is matching-safe and keeps the
  forked candidate set bounded by the declaration cardinality."
  [ctx]
  (into #{}
        (mapcat prefixes)
        (concat (keys (:sensitive ctx)) (keys (:large ctx)))))

(defn- fork-decl-paths
  "Descend the candidate declaration-coordinate set through a MAP key `k`.
  Each candidate forks into the NAMED-slot interpretation `(conj c k)` and
  the `:map-of`-key interpretation `c` (key is a collection coordinate, no
  segment). Both are retained only when they remain a prefix of some
  declared path (`decl-prefixes`), bounding the set.

  POSITION-PRECISE skip (rf2-wm9kp follow-up): the `:map-of`-key
  interpretation — keeping `c` unchanged so the key is treated as a
  collection coordinate — is only legitimate for a candidate that has
  ALREADY consumed at least one declared segment (`(seq c)`). A non-empty
  `c` is a partial declaration-prefix match in progress, so a map key at
  that position can plausibly be a `:map-of` value key inside that
  declared subtree (e.g. `[:by-id]` skips the map-of key `\"a\"` so the
  subsequent `:secret` forms `[:by-id :secret]`). The EMPTY seed `[]` has
  matched nothing yet: letting it skip a key would let a declaration
  FLOAT past arbitrary leading NAMED map slots — matching decl
  `[:auth :password]` against the deeper `[:tags :some-other-slot :auth
  :password]` where that `:auth`/`:password` is a DIFFERENT position, not
  the declared one. So `[]` advances only via the named-slot fork; it
  never survives as a free-floating skip. This keeps the headline goal
  green (`[:items 0 :token]` matches `[:items :token]`; `[:by-id \"a\"
  :secret]` matches `[:by-id :secret]`) while sealing the over-redaction
  of same-named slots at non-declared positions."
  [decl-paths k decl-prefixes]
  (persistent!
    (reduce (fn [acc c]
              (let [named (conj c k)
                    acc   (if (contains? decl-prefixes named) (conj! acc named) acc)]
                ;; `:map-of`-key interpretation: `c` stays a live candidate
                ;; ONLY when it is a non-empty partial match — see the
                ;; position-precise rationale in the docstring. `c` is
                ;; already a known decl-prefix (it survived the round that
                ;; produced it), so retaining it keeps the set bounded by
                ;; `decl-prefixes`.
                (if (seq c) (conj! acc c) acc)))
            (transient #{})
            decl-paths)))

(defn- fork-index-paths
  "Descend the candidate declaration-coordinate set through a VECTOR / SEQ
  element at integer index `i`.

  An element index is UNAMBIGUOUSLY a collection coordinate — unlike a map
  key, it can never be a named app-db slot. So a vector/seq descent forks
  each candidate into:

    - the INDEX-FREE interpretation `c` (unchanged): a schema-derived decl
      descends positional containers at the same base-path, so the
      index-free `[:items :token]` matches the runtime `[:items 0 :token]`.
      Retained for ALL candidates (including the empty seed `[]`): riding
      an index through never floats a declaration past a NAMED slot, so it
      cannot over-redact the way the map-key skip could.

    - the LITERAL-INDEX interpretation `(conj c i)`: a declaration MAY
      itself carry a concrete index (`[:tokens 0]`, declared directly
      against the indexed runtime position rather than schema-derived).
      Retained only while it remains a prefix of some declared path
      (`decl-prefixes`), so it is dropped immediately when no decl pins
      that index — keeping the set bounded.

  Both interpretations are matching-safe: the index-free one matches
  schema decls, the literal-index one matches directly-declared indexed
  paths, and only the interpretation actually present in the declaration
  table fires at the leaf."
  [decl-paths i decl-prefixes]
  (persistent!
    (reduce (fn [acc c]
              (let [indexed (conj c i)
                    acc     (if (contains? decl-prefixes indexed)
                              (conj! acc indexed) acc)]
                ;; Index-free interpretation: pass `c` through unchanged.
                (conj! acc c)))
            (transient #{})
            decl-paths)))

(defn- decl-match
  "Test the candidate declaration-coordinate set against a declaration
  table `tbl` (`:sensitive` or `:large`). Returns the matched declaration
  value (truthy) for the FIRST candidate present in `tbl`, or nil. For the
  sensitive table any present entry suffices (membership); for the large
  table the entry is the marker-hint declaration map."
  [decl-paths tbl]
  (some #(get tbl %) decl-paths))

(defn- decl-sensitive?
  [decl-paths sensitive-tbl]
  (boolean (some #(contains? sensitive-tbl %) decl-paths)))

(defn- walk-map
  [m path decl-paths ctx]
  (let [decl-prefixes (:decl-prefixes ctx)]
    (reduce-kv
      (fn [acc k v]
        (assoc acc k (walk v
                           (conj path k)
                           (fork-decl-paths decl-paths k decl-prefixes)
                           ctx)))
      (empty m)
      m)))

(defn- walk-indexed
  [v path decl-paths ctx]
  (let [decl-prefixes (:decl-prefixes ctx)
        n             (count v)]
    (loop [i 0 acc (transient [])]
      (if (< i n)
        (recur (inc i)
               (conj! acc (walk (nth v i)
                                (conj path i)
                                (fork-index-paths decl-paths i decl-prefixes)
                                ctx)))
        (persistent! acc)))))

(defn- walk-seq
  [xs path decl-paths ctx]
  (let [decl-prefixes (:decl-prefixes ctx)
        idx           (volatile! -1)]
    (persistent!
      (reduce (fn [acc v]
                (vswap! idx inc)
                (conj! acc (walk v
                                 (conj path @idx)
                                 (fork-index-paths decl-paths @idx decl-prefixes)
                                 ctx)))
              (transient [])
              xs))))

(defn- walk
  [v path decl-paths ctx]
  (let [path        (vec path)
        large-decl  (decl-match decl-paths (:large ctx))
        sensitive?  (decl-sensitive? decl-paths (:sensitive ctx))
        include-lg? (:include-large? ctx)
        include-s?  (:include-sensitive? ctx)]
    (cond
      (and sensitive? (not include-s?))
      privacy/redacted-sentinel

      (and large-decl (not include-lg?))
      (if (marker? v)
        ;; Idempotence under double-projection: a value at a `:large?`-
        ;; declared path that already carries the `:rf.size/large-elided`
        ;; marker shape is passed through unchanged. A forwarder pipeline
        ;; that accidentally double-projects (middleware composition,
        ;; tool-then-watcher fan-out) MUST NOT re-mark the marker — the
        ;; second pass's `:bytes` would otherwise reflect the printed
        ;; length of the prior marker, not the original payload. Mirrors
        ;; the sensitive-case idempotence (the `:rf/redacted` scalar
        ;; sentinel is non-matchable so the walker descends into nothing
        ;; on a re-projection pass). Per rf2-fq8ep.
        v
        (->marker v path {:hint             (:hint large-decl)
                          :as-of-epoch      (:as-of-epoch ctx)
                          :include-digests? (:include-digests? ctx)}))

      (map? v)
      (walk-map v path decl-paths ctx)

      (vector? v)
      (walk-indexed v path decl-paths ctx)

      (set? v)
      ;; Set elements are nameless collection coordinates — the runtime
      ;; `path` and the candidate decl-paths both pass through unchanged
      ;; (mirrors `:vector`/`:sequential`: the element is not a segment).
      (into #{} (map #(walk % path decl-paths ctx)) v)

      (seq? v)
      (walk-seq v path decl-paths ctx)

      :else
      (do
        (when (and (string? v)
                   (not large-decl)
                   (not sensitive?))
          (let [threshold (:threshold-bytes ctx)]
            ;; A threshold of 0 disables runtime auto-detect entirely —
            ;; no `pr-str-bytes` walk, no warning. Per API.md §Configure
            ;; keys (`:elision` — "0 disables runtime auto-detect").
            (when (pos? threshold)
              (let [bytes (pr-str-bytes v)]
                (when (> bytes threshold)
                  (warn-large-unschema'd! (:frame-id ctx) path bytes))))))
        v))))

(defn- elide-against-frame
  "Inner walk for `elide-wire-value` against a KNOWN carried frame.
  `frame-id` is the resolved frame whose elision registry supplies the
  sensitive / large declaration tables. Pure walk — no frame resolution
  happens here (the caller has already resolved + validated the stamp)."
  [v opts frame-id]
  (let [reg       (registry-of frame-id)
        ;; Precedence (API.md L507): explicit opt > configured > default.
        threshold (let [opt (:rf.size/threshold-bytes opts)]
                    (if (some? opt) opt (configured-threshold-bytes)))
        large     (or (:declarations reg) {})
        sensitive (or (:sensitive-declarations reg) {})
        ctx       {:frame-id           frame-id
                   :large              large
                   :sensitive          sensitive
                   ;; Prefix set of every declared path — bounds the forked
                   ;; candidate decl-path set as the walker descends maps
                   ;; (rf2-wm9kp). Empty when nothing is declared ⇒ the fork
                   ;; prunes to {} immediately and the walker is identity.
                   :decl-prefixes      (decl-prefix-set {:large large :sensitive sensitive})
                   :include-large?     (true? (:rf.size/include-large? opts))
                   :include-sensitive? (true? (:rf.size/include-sensitive? opts))
                   :include-digests?   (true? (:rf.size/include-digests? opts))
                   :threshold-bytes    threshold
                   :as-of-epoch        (:as-of-epoch opts)}
        seed-path (vec (:path opts))]
    ;; Seed the candidate declaration-coordinate set with the offset path
    ;; (`#{[]}` for the common no-offset call). The walk forks/prunes it
    ;; against `:decl-prefixes` on every map-key descent.
    (walk v seed-path #{seed-path} ctx)))

(defn elide-wire-value
  "Walk `v` and substitute schema-declared sensitive or large paths for
  wire egress. Sensitive wins over large when both declarations match.

  EP-0002 (rf2-gjq3ow) — the wire-egress frame resolves from the CARRIED
  stamp: the explicit `:frame` opt (*override*) wins, else the in-effect
  carried-invariant scope (`frame/resolve-current-frame` — a `with-frame`
  binding or an enclosing frame-provider). There is NO `:rf/default` floor:
  a frame's elision policy may be applied only when that frame is KNOWN
  (Spec 002 §Frame target resolution; EP-0002 §Trace, Projection, And
  Elision / §Privacy And Egress).

  Frameless egress FAILS CLOSED. When no frame is carried, the per-frame
  elision registry is unreachable, so a permissive identity walk would ship
  every value verbatim under NO policy — the silent leak this contract
  exists to abolish. Rather than borrow another frame's marks, the whole
  value is conservatively redacted to the `:rf/redacted` sentinel.
  `:rf.size/include-sensitive? true` is the deliberate opt-out: a caller
  that has explicitly waived sensitive redaction gets the value walked with
  an empty policy (the identity transform), so an inspector that genuinely
  wants the raw, policy-free value asks for it on purpose."
  ([v] (elide-wire-value v nil))
  ([v opts]
   (let [frame-id (or (:frame opts) (frame/resolve-current-frame))]
     (cond
       ;; Known carried frame ⇒ apply that frame's elision policy.
       (some? frame-id)
       (elide-against-frame v opts frame-id)

       ;; Frameless + the caller waived sensitive redaction ⇒ identity
       ;; walk against an empty (no-frame) policy. The walker is the
       ;; identity transform when no declarations are reachable.
       (true? (:rf.size/include-sensitive? opts))
       (elide-against-frame v opts ::no-frame)

       ;; Frameless egress, no opt-out ⇒ fail closed: no policy is
       ;; available, so conservatively redact the whole value rather than
       ;; borrow another frame's marks.
       :else
       privacy/redacted-sentinel))))

(defn marker?
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(defn handle?
  [v]
  (and (vector? v) (= :rf.elision/at (first v))))

(late-bind/set-fn! :elision/populate-from-schemas! populate-from-schemas!)
(late-bind/set-fn! :elision/sensitive-declarations sensitive-declarations)
(late-bind/set-fn! :elision/clear-warning-cache! clear-warning-cache!)
