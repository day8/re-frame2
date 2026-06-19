(ns re-frame.elision
  "Frame-owned wire-boundary elision.

  Canonical durable app-db classification comes from the **frame**:
  `(rf/reg-frame :app/main {:large {:app-db [[:docs :csv]]}})` hydrates
  `[:rf.runtime/elision :declarations]`, and `:sensitive {:app-db …}`
  hydrates `[:rf.runtime/elision :sensitive-declarations]` (installed by
  `re-frame.frame-classification` under `:source :frame`). EP-0015 §8
  (Schemas Describe Shape, Not Public Egress Policy): schemas describe
  shape and validation, NOT durable app-db egress policy — a
  `reg-app-schema` `{:sensitive? true}` / `{:large? true}` slot prop is
  **no longer** a second route into this registry. Schema `:sensitive?`
  still drives schema-validation-failure-trace redaction (the schema's own
  egress product — `re-frame.schemas`), and machine `:data-schema` per-slot
  props still classify machine `:data` (EP-0005, unchanged). There are no
  imperative large-path APIs.

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
;;   explicit `:rf.size/threshold-bytes` opt  >  `(rf/configure! {:elision …})`  >  default
;;
;; A threshold of 0 disables runtime auto-detect entirely (only
;; frame-declared `:large` entries elide); the unschema'd-large warning
;; never fires.
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
  advisory (0 disables runtime auto-detect; only frame-declared `:large`
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

(defn ^:no-doc reconcile-runtime-db-effect
  "Reconcile the durable elision registry across a whole-value
  `:rf.db/runtime` partition commit (rf2-gom797).

  A framework-authority event may return a `:rf.db/runtime` effect whose
  value WHOLE-VALUE-REPLACES the runtime-db partition (Spec 002 §A runtime
  effect replaces only runtime-db; router commit decision #5). But the
  elision declaration registry at `[:rf.runtime/elision]` is a CROSS-CUTTING
  durable subsystem child mutated OUT-OF-BAND by `reg-flow` / `add-marks` /
  the frame-classification walker — NOT by the event returning the effect.
  A whole-value runtime-db effect that seeds an UNRELATED subsystem (e.g.
  `:rf.runtime/routing`) and does not itself carry `:rf.runtime/elision`
  would otherwise DROP the registry on commit, silently losing every
  flow-sourced / mark-sourced / frame-declared elision declaration — so the
  post-commit frame would emit raw values that were correctly redacted
  pre-commit (the durable privacy/trace contract breaks after any
  runtime-db-writing event).

  This preserves the existing `:rf.runtime/elision` sub-tree from
  `prev-runtime-db` into `new-runtime-db` UNLESS the committing effect
  ITSELF carries an `:rf.runtime/elision` key (an explicit full-frame
  install — epoch-restore / SSR-hydration — legitimately replaces the
  registry, so its decision is honoured verbatim, including an intentional
  clear). When the effect omits the key, the prior registry is carried
  forward; when there was no prior registry, nothing is added (a frame that
  never used elision stays clean — `runtime-db-effect-whole-value-commit`).

  Returns the reconciled runtime-db value. `new-runtime-db` is the
  whole-value effect payload; `prev-runtime-db` is the pre-commit
  runtime-db partition value (may be nil for a fresh frame)."
  [new-runtime-db prev-runtime-db]
  (let [new-runtime-db (or new-runtime-db {})]
    (if (contains? new-runtime-db :rf.runtime/elision)
      ;; The effect speaks about elision explicitly (full-frame install /
      ;; deliberate clear) — honour it verbatim.
      new-runtime-db
      ;; The effect is silent about elision — carry the prior registry
      ;; forward through the same prune logic so a frame that never used
      ;; elision is left without a stray nil sub-tree.
      (write-elision-slot new-runtime-db
                          (get prev-runtime-db :rf.runtime/elision)))))

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
  "Return the frame-owned `:large` `:app-db` declarations for `frame-id`
  (installed by `re-frame.frame-classification` under `:source :frame`).
  EP-0002 — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` registry."
  ([] (declarations (frame/require-current-frame!
                      :elision-declarations
                      {:where 're-frame.elision/declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :declarations) {})))

(defn sensitive-declarations
  "Return the frame-owned `:sensitive` `:app-db` declarations for `frame-id`
  (installed by `re-frame.frame-classification` under `:source :frame`).
  EP-0002 — the zero-arity ambient form resolves the frame through the
  carried-invariant scope chain (`frame/require-current-frame!`); under no
  established scope it raises `:rf.error/no-frame-context` rather than
  reading a synthesised `:rf/default` registry."
  ([] (sensitive-declarations (frame/require-current-frame!
                                :elision-sensitive-declarations
                                {:where 're-frame.elision/sensitive-declarations})))
  ([frame-id]
   (or (get (registry-of frame-id) :sensitive-declarations) {})))

;; EP-0015 §8 (rf2-d2r3um): the schema-attached `{:sensitive? true}` /
;; `{:large? true}` slot props are NO LONGER a route into this app-db
;; egress registry. Durable app-db classification is frame-owned —
;; `re-frame.frame-classification/install!` writes `:source :frame`
;; declarations at `reg-frame` time. The former
;; `populate-{elision,sensitive}-from-schemas!` / `populate-from-schemas!`
;; functions (which walked `reg-app-schema` slot props into `:source
;; :schema` declarations) are removed: schemas describe shape, not durable
;; app-db egress policy. Schema `:sensitive?` still drives
;; schema-validation-failure-trace redaction (`re-frame.schemas`), and
;; machine `:data-schema` props still classify machine `:data` (EP-0005) —
;; both consult the schema directly, not this registry.

(defn clear-warning-cache!
  []
  (reset! warned-unschema'd #{})
  nil)

(defn ^:no-doc pr-str-bytes
  "Return a byte-count for a value's printed representation. Used by the
  `:rf.size/large-elided` marker payload. `^:no-doc` public so
  `re-frame.marks` reuses it rather than re-inlining the same `pr-str`
  byte-count a second time (rf2-ih437c)."
  [v]
  #?(:clj  (count (.getBytes ^String (pr-str v) "UTF-8"))
     :cljs (count (pr-str v))))

(defn ^:no-doc value-type
  "Coarse value-type tag for the `:rf.size/large-elided` marker payload.
  `^:no-doc` public so `re-frame.marks` reuses it rather than re-inlining
  the same closed `cond` a second time (rf2-ih437c)."
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

(defn ^:no-doc ->marker
  "Build the `:rf.size/large-elided` marker map for value `v` at `path`.
  `^:no-doc` public so `re-frame.marks/large-marker` builds the marker
  here (with `{:reason :marks}`) rather than re-inlining the shape a
  second time (rf2-ih437c)."
  [v path {:keys [hint as-of-epoch include-digests? reason]}]
  (let [body (cond-> {:path   (vec path)
                      :bytes  (pr-str-bytes v)
                      :type   (value-type v)
                      ;; EP-0015 §8 (rf2-d2r3um): the declaration source is
                      ;; now frame-owned (`:source :frame`), not schema —
                      ;; carry that provenance in `:reason` (defaults to
                      ;; `:frame` when the decl omits an explicit source).
                      :reason (or reason :frame)
                      :hint   hint
                      :handle (handle-of (vec path) as-of-epoch)}
               include-digests? (assoc :digest (sha256-hex v)))]
    {:rf.size/large-elided body}))

(defn- marker-opts
  "The `->marker` option map for a declared-`:large` node — derived from the
  matched `large-decl` (its `:hint` / `:source`) and the walk `ctx` (its
  `:as-of-epoch` / `:include-digests?`). Single source for the BYTE-IDENTICAL
  4-key option map the path-based walker (`walk`) and the value-match large
  collector (`collect-large-markers!`) each pass to `->marker` (rf2-4p0bxp).
  Both call sites carry the same `:as-of-epoch` / `:include-digests?` keys on
  their respective `ctx`, so the marker is identical whichever arm emits it."
  [large-decl ctx]
  {:hint             (:hint large-decl)
   :reason           (:source large-decl)
   :as-of-epoch      (:as-of-epoch ctx)
   :include-digests? (:include-digests? ctx)})

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
                      :hint     "Add this path to the frame's `:large {:app-db [...]}` classification (EP-0015)."
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

(defn- reduce-indexed-forks
  "Descend a POSITIONAL container `coll` (a vector OR a seq), tracking an
  integer element index `i` from 0 and forking the candidate
  declaration-coordinate set through that index via `fork-index-paths`. For
  each element this threads an accumulator by calling
  `(step acc element i forked-decl-paths)`, where `forked-decl-paths` is
  `(fork-index-paths decl-paths i decl-prefixes)`. Returns the final `acc`.

  This is the single home for the 'descend a positional container tracking an
  integer index, forking decl-paths via fork-index-paths' micro-shape that
  otherwise recurs across the path-based walker (`walk-indexed` / `walk-seq`)
  and the large-value collector (`collect-large-markers!`'s vector / seq
  branches) (rf2-zp0g04). `reduce` iterates a vector in index order, so the
  vector and seq cases share one traversal; the volatile index reproduces the
  per-element `(conj path i)` exactly. The caller owns `acc`'s identity
  (transient vector for the walkers, transient `[raw marker]` accumulator for
  the collector) and what `step` does with each element."
  [coll decl-paths decl-prefixes step acc]
  (let [idx (volatile! -1)]
    (reduce (fn [a x]
              (let [i (vswap! idx inc)]
                (step a x i (fork-index-paths decl-paths i decl-prefixes))))
            acc
            coll)))

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

(defn- walk-positional
  "Walk a POSITIONAL container `coll` (a vector OR a seq), reproducing each
  element at its integer-indexed path `(conj path i)` and forking the
  candidate decl-paths through the index. Both the vector (`:vector`) and seq
  (`:sequential`) walk arms share this body (rf2-zp0g04): each yields a
  persistent vector of walked elements (a seq normalises to a vector here, as
  the prior `walk-seq` did)."
  [coll path decl-paths ctx]
  (let [decl-prefixes (:decl-prefixes ctx)]
    (persistent!
      (reduce-indexed-forks
        coll decl-paths decl-prefixes
        (fn [acc x i forked]
          (conj! acc (walk x (conj path i) forked ctx)))
        (transient [])))))

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
        (->marker v path (marker-opts large-decl ctx)))

      (map? v)
      (walk-map v path decl-paths ctx)

      (vector? v)
      (walk-positional v path decl-paths ctx)

      (set? v)
      ;; Set elements are nameless collection coordinates — the runtime
      ;; `path` and the candidate decl-paths both pass through unchanged
      ;; (mirrors `:vector`/`:sequential`: the element is not a segment).
      (into #{} (map #(walk % path decl-paths ctx)) v)

      (seq? v)
      (walk-positional v path decl-paths ctx)

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
  "Walk `v` and substitute the frame's declared sensitive or large paths for
  wire egress (the durable declarations live in `[:rf.runtime/elision …]`,
  installed frame-owned by `re-frame.frame-classification` under
  `:source :frame` — EP-0015 §8; the schema→app-db-egress route is gone).
  Sensitive wins over large when both declarations match.

  EP-0002 (rf2-gjq3ow) — the wire-egress frame resolves from the CARRIED
  stamp: the explicit `:frame` opt (*override*) wins, else the in-effect
  carried-invariant scope (`frame/resolve-current-frame` — a `with-frame`
  binding or an enclosing frame-provider). There is NO `:rf/default` floor:
  a frame's elision policy may be applied only when that frame is KNOWN
  (Spec 002 §Frame target resolution; EP-0002 §Trace, Projection, And
  Elision / §Privacy And Egress).

  A resolved frame-id is not enough — it must RESOLVE to a live frame.
  An explicit `:frame` opt (or a stale carried scope) may name a frame that
  was never registered or has since been destroyed. EP-0015 issue 1
  (rf2-t55hxg.18): an unresolvable frame's per-frame elision registry is
  unreachable (`registry-of` returns nil), so feeding it through the policy
  walk produced an EMPTY-policy identity transform — shipping every value
  verbatim under NO policy, the exact silent leak this contract abolishes.
  An unknown / destroyed frame therefore FAILS CLOSED here too, identically
  to the frameless case: it is validated against the live registry
  (`frame/frame`) before being treated as policy-bearing. Spec 015
  §Direct reads and fail-closed frame resolution: an unresolved frame must
  fail closed — it must not fall through to a permissive walk, and never
  synthesizes `:rf/default`.

  Frameless / unresolvable-frame egress FAILS CLOSED. When no LIVE frame is
  carried, the per-frame elision registry is unreachable, so a permissive
  identity walk would ship every value verbatim under NO policy. Rather than
  borrow another frame's marks, the whole value is conservatively redacted
  to the `:rf/redacted` sentinel. `:rf.size/include-sensitive? true` is the
  deliberate opt-out: a caller that has explicitly waived sensitive
  redaction gets the value walked with an empty policy (the identity
  transform), so an inspector that genuinely wants the raw, policy-free
  value asks for it on purpose."
  ([v] (elide-wire-value v nil))
  ([v opts]
   (let [frame-id   (or (:frame opts) (frame/resolve-current-frame))
         ;; A frame-id alone is not policy-bearing — it must resolve to a
         ;; LIVE frame. `frame/frame` returns nil for an unknown /
         ;; never-registered / destroyed id, so an explicit `:frame` opt or
         ;; a stale carried scope that names a dead frame is treated exactly
         ;; like the frameless case below (fail closed). rf2-t55hxg.18.
         live-frame? (and (some? frame-id) (some? (frame/frame frame-id)))]
     (cond
       ;; Known + LIVE carried frame ⇒ apply that frame's elision policy.
       live-frame?
       (elide-against-frame v opts frame-id)

       ;; Frameless / unresolvable-frame + the caller waived sensitive
       ;; redaction ⇒ identity walk against an empty (no-frame) policy. The
       ;; walker is the identity transform when no declarations are
       ;; reachable.
       (true? (:rf.size/include-sensitive? opts))
       (elide-against-frame v opts ::no-frame)

       ;; Frameless / unresolvable-frame egress, no opt-out ⇒ fail closed:
       ;; no policy is available, so conservatively redact the whole value
       ;; rather than borrow another frame's marks or pass it through under
       ;; an empty policy.
       :else
       privacy/redacted-sentinel))))

(defn marker?
  [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; Derived-tree VALUE-based redaction (EP-0015 issue 2, rf2-i783h0).
;;
;; `elide-wire-value` redacts a value by its frame-declared `:sensitive`
;; app-db PATH. But a DERIVED tree — rendered hiccup, a resolved
;; `:effective-args` map, a snapshot body, a plan-resolved value slot —
;; re-surfaces the SAME sensitive value at a NON-app-db position the
;; path-based walker can never reach (a token sitting at hiccup coordinate
;; `[1 :value]`, not at `[:auth :token]`). The path-based walker is
;; structurally blind to the re-keyed copy.
;;
;; The sound posture for a DERIVED tree is VALUE-based redaction: collect the
;; live values sitting at the frame's declared-`:sensitive?` app-db paths,
;; then substitute any leaf in the derived tree that EQUALS one of them with
;; the same `:rf/redacted` sentinel `elide-wire-value` emits. This is the
;; value-based DUAL of the path-based walker above — it belongs in the same
;; ns so the two arms of "redact app-db-sensitive values at egress" share one
;; home (the elision registry, the sentinel, the indexing convention). It
;; centralizes the engine Story-MCP / re-frame2-pair-mcp previously each
;; carried privately (the SECOND place EP-0015 egress semantics could drift).
;;
;; ## Non-unique-secret guard
;;
;; Value-matching is a heuristic: it substitutes EVERY derived-tree leaf `=`
;; a sensitive value. When a sensitive path holds a short/common scalar (`0`,
;; an HTTP `200`, `:ok`, `""`), naive matching scrubs every benign leaf that
;; merely equals it — degrading the consumer's view AND leaking the secret's
;; value-CLASS. The guard subtracts any candidate value that ALSO appears,
;; VERBATIM, on the wire — i.e. in the POST-elision db (the actual wire
;; bytes). Such a value is provably NOT a protected secret: the path-based
;; `:app-db` egress already discloses it, so removing it from the secret set
;; leaks nothing new — the fail-SAFE invariant (never leak a genuine secret)
;; holds by construction.
;;
;; Classifying against the ELIDED db (not the raw db) is load-bearing: a
;; secret aliased into a `:large?`-declared subtree is replaced by the
;; `:rf.size/large-elided` marker on the wire, so it is NOT disclosed and MUST
;; stay redacted; and a seq-indexed `:sensitive?` element redacted by
;; `elide-wire-value` simply does not appear in the elided db. Reasoning
;; against the actual wire bytes is robust to any elision class (digests, new
;; markers, the string auto-detect threshold) without per-class reasoning.

(defn- under-prefix?
  "True when `path` is `prefix` or descends from it (element-wise prefix
  match). Both are indexed vectors. Decides whether an app-db position is
  governed by a declared-`:sensitive?` path — a slot marked sensitive covers
  itself AND everything beneath it."
  [prefix path]
  (let [pn (count prefix)]
    (and (<= pn (count path))
         (loop [i 0]
           (cond
             (== i pn)                       true
             (= (nth prefix i) (nth path i)) (recur (inc i))
             :else                           false)))))

(defn- collect-governed-values!
  "Walk the RAW `node` at `path`, conj!ing onto transient set `acc!` every
  value sitting at (or beneath) a `:sensitive?` prefix — the candidate
  secrets. Returns `acc!`.

  Indexing MIRRORS the path-based walker above so a declared path lands on
  the SAME node `elide-wire-value` redacts: maps descend by key, vectors AND
  seqs descend by integer index (so a seq-indexed `[:tokens 0]` declaration
  is reached — `get-in` cannot read a seq index), and sets are walked at
  their own path (set elements have no stable index, so a whole
  sensitive-declared set contributes all its members).

  A governed node that is a NON-EMPTY COLLECTION is collected WHOLE (the
  subtree itself, not only its scalar leaves) — the value-match dual of the
  path-based walker, which replaces an entire `:sensitive?`-declared subtree
  with ONE `:rf/redacted` sentinel rather than descending into it. So a
  sensitive blob re-keyed into a derived tree collapses to a single
  `:rf/redacted` (sensitive-wins-over-large parity with `collect-large-
  markers!`, which collects the whole subtree too), instead of the blob's
  every element redacting piecemeal. The collection is STILL descended so a
  partial-prefix / seq-indexed declaration that governs only some descendants
  also contributes those nested scalars.

  Only governed positions contribute; `nil` / boolean leaves and EMPTY
  collections are skipped (a `nil`/`false`/`[]`/`{}` is not a secret and
  value-matching it would scrub swathes of benign tree — every empty
  collection in the derived tree would otherwise over-redact)."
  [acc! node path sensitive-prefixes]
  (let [governed? (some #(under-prefix? % path) sensitive-prefixes)]
    (when (and governed?
               (some? node)
               (not (boolean? node))
               (if (coll? node) (seq node) true))
      (conj! acc! node))
    (cond
      (map? node)
      (reduce-kv (fn [a k v]
                   (collect-governed-values! a v (conj path k) sensitive-prefixes))
                 acc! node)

      (vector? node)
      (reduce (fn [a i] (collect-governed-values! a (nth node i) (conj path i)
                                                  sensitive-prefixes))
              acc! (range (count node)))

      (seq? node)
      (let [idx (volatile! -1)]
        (reduce (fn [a x]
                  (collect-governed-values! a x (conj path (vswap! idx inc))
                                            sensitive-prefixes))
                acc! node))

      (set? node)
      ;; Sets have no stable element index; walk members at the set's own
      ;; path so a sensitive-declared set contributes every member.
      (reduce (fn [a x] (collect-governed-values! a x path sensitive-prefixes))
              acc! node)

      :else
      acc!)))

(defn- collect-wire-values!
  "Walk the POST-elision `node`, conj!ing onto transient set `acc!` every
  value (intermediate collections AND leaves) that is `=` to a candidate
  secret. `node` is the elided db — the actual wire bytes — so any candidate
  found here is one the path-based egress ships VERBATIM. Returns `acc!`.

  Because the input is already elided, every `:sensitive?` slot is the
  `:rf/redacted` sentinel and every `:large?` (or auto-detected) slot is the
  `:rf.size/large-elided` marker — the secret value simply is not present at
  any governed position, so there is no path-shape reasoning to get right.
  Membership is the only question; map keys, vector elements, and set/seq
  elements are all walked, since a candidate aliased to ANY surviving wire
  position is disclosed."
  [acc! node candidates]
  (when (contains? candidates node)
    (conj! acc! node))
  (cond
    (map? node)
    (reduce-kv (fn [a k v]
                 (collect-wire-values! a k candidates)
                 (collect-wire-values! a v candidates))
               acc! node)

    (coll? node)
    (reduce (fn [a x] (collect-wire-values! a x candidates))
            acc! node)

    :else
    acc!))

(defn collect-sensitive-values
  "The set of values sitting at `frame-id`'s declared-`:sensitive?` app-db
  paths, read out of the RAW `source-db` — the candidate secrets, with NO
  wire-disclosure guard applied. The fail-SAFE base set for value-matching a
  derived tree (EP-0015 issue 2, rf2-i783h0).

  Reads the SAME frame-owned `:sensitive` `:app-db` declarations
  (`sensitive-declarations`) the path-based walker reads — installed by
  `re-frame.frame-classification` at `reg-frame` time (EP-0015 §8). Candidates
  are collected by WALKING `source-db` at governed positions (mirroring the
  elider's indexing) rather than by `get-in` on each declared path — `get-in`
  cannot index into a seq, so a seq-indexed declaration (`[:tokens 0]`) would
  otherwise yield no candidate and the secret would leak. A governed node that
  is a non-empty collection is collected WHOLE (so a re-keyed sensitive blob
  collapses to a single `:rf/redacted`, parity with the path-based walker) as
  well as descended for its nested scalars; `nil` / boolean leaves and empty
  collections are excluded (not secrets, and value-matching them would scrub
  swathes of benign tree).

  No guard means every governed value stays in the set — over-scrub at worst,
  never under-scrub. Use this for a source that is NOT the live wire `:app-db`
  (e.g. a plan's authored `:db-seed` slot); for the live source use
  `sensitive-value-set`, which layers the non-unique-secret guard on top.
  Returns `#{}` when `source-db` is nil or the frame declares nothing
  sensitive."
  [source-db frame-id]
  (if (nil? source-db)
    #{}
    (let [sensitive-prefixes (mapv vec (keys (sensitive-declarations frame-id)))]
      (if (empty? sensitive-prefixes)
        #{}
        (persistent!
          (collect-governed-values! (transient #{}) source-db []
                                    sensitive-prefixes))))))

(defn sensitive-value-set
  "The set of live values sitting at `frame-id`'s declared-`:sensitive?`
  app-db paths, read out of the RAW `app-db`, MINUS any value that is already
  disclosed on the wire (the non-unique-secret guard). Use this to
  value-redact a DERIVED tree where the same sensitive value reappears at a
  non-app-db position the path-based `elide-wire-value` walker can't reach
  (EP-0015 issue 2, rf2-i783h0).

  Candidates come from `collect-sensitive-values` (the unguarded base set).
  The non-unique-secret guard then subtracts any candidate that ALSO appears,
  VERBATIM, in the POST-elision db — the actual wire bytes produced by
  walking `app-db` through `elide-wire-value` under `wire-opts` (which MUST
  carry the egress floor the path-based `:app-db` slot ships under, so the
  wire-classification reasons about the IDENTICAL bytes; `:frame frame-id` is
  supplied automatically).

  A candidate present there is already disclosed by the path-based egress, so
  dropping it leaks nothing new; one that is absent (redacted / large-elided)
  stays in the set and is redacted everywhere. Classifying against the ELIDED
  db (not the raw db) is load-bearing — a secret aliased into a
  `:large?`-declared subtree is the marker on the wire, NOT the verbatim
  value, so it must stay redacted; a seq-indexed `:sensitive?` element
  redacted by `elide-wire-value` does not appear in the elided db either. The
  irreducible same-value aliasing case (a uniquely-secret short scalar)
  over-scrubs — fail-SAFE, never under-safe.

  Returns `#{}` when `app-db` is nil, when the frame declares nothing
  sensitive, or when every candidate is already on the wire."
  [app-db frame-id wire-opts]
  (let [candidates (collect-sensitive-values app-db frame-id)]
    (if (empty? candidates)
      #{}
      (let [wire   (elide-wire-value app-db (assoc wire-opts :frame frame-id))
            public (persistent!
                     (collect-wire-values! (transient #{}) wire candidates))]
        (into #{} (remove public) candidates)))))

(defn redact-matching-values
  "Walk `tree`, substituting any leaf `=` to a member of `secrets` with the
  `:rf/redacted` sentinel. Recurses through maps, vectors, sets, seqs; treats
  every other value as a leaf. Map KEYS are walked too — a secret used as a
  key (rare, but a `{:value <token>}`-style attribute map could in principle
  key on one) is redacted on both sides. Returns `tree` unchanged when
  `secrets` is empty."
  [tree secrets]
  (if (empty? secrets)
    tree
    (letfn [(walk* [t]
              (cond
                (contains? secrets t) privacy/redacted-sentinel
                (map? t)    (persistent!
                              (reduce-kv (fn [acc k v]
                                           (assoc! acc (walk* k) (walk* v)))
                                         (transient {})
                                         t))
                (vector? t) (mapv walk* t)
                (set? t)    (into #{} (map walk*) t)
                (seq? t)    (map walk* t)
                :else       t))]
      (walk* tree))))

(defn redact-derived-values
  "Value-redact a DERIVED `tree` (rendered hiccup, a resolved `:effective-args`
  map, a snapshot body, a plan-resolved value slot) against the live values
  at `frame-id`'s declared-`:sensitive?` app-db paths, before wire egress
  (EP-0015 issue 2, rf2-i783h0). The single framework home for the
  value-match-redaction primitive Story-MCP / re-frame2-pair-mcp previously
  each carried privately.

  Collects the secret set from `source-db` (the raw db the derived tree was
  produced from) via `sensitive-value-set` — including the non-unique-secret
  guard, classified against the wire bytes `source-db` ships under
  `wire-opts` — then substitutes any matching leaf in `tree` with
  `:rf/redacted`.

  `wire-opts` is the `elide-wire-value` opts the path-based `:app-db` slot
  ships under (the egress floor — e.g. an off-box-tool / off-box-observability
  profile's `:rf.size/*` opt-set); `:frame frame-id` is supplied
  automatically. Keeping the egress floor a caller argument keeps this helper
  egress-profile-agnostic — the `:rf.egress/*` profile vocabulary stays in the
  MCP-base / tool layer.

  Short-circuits: a nil `tree` or nil `source-db` returns `tree` (nothing to
  scrub / no source of secrets); no declared-sensitive (or all-disclosed)
  values returns `tree` unwalked. The opt-out (trusted-local raw) belongs to
  the caller — pass the value through directly when the operator has waived
  redaction."
  [tree source-db frame-id wire-opts]
  (cond
    (nil? tree)      tree
    (nil? source-db) tree
    :else            (redact-matching-values
                       tree
                       (sensitive-value-set source-db frame-id wire-opts))))

;; ---------------------------------------------------------------------------
;; Large-value value-match — the DUAL of the sensitive engine for the :large
;; egress axis (rf2-9o5ixx, EP-0015: sensitive + large are PEER egress axes).
;;
;; `elide-wire-value` elides a declared-`:large` slot to a `:rf.size/large-
;; elided` marker by PATH. But the same large blob can be re-keyed into a
;; DERIVED tree (a rendered `[:pre blob]`, a snapshot/evidence echo, an
;; explain `:effective-args` slot) at a non-app-db position the path-based
;; walker can never reach — so it crosses the wire raw. The sound posture
;; mirrors the sensitive dual: collect the live large values (the whole
;; subtree at each declared-`:large` path, with the marker the path-based
;; egress would have produced) and substitute any matching leaf in the
;; derived tree with that SAME marker.
;;
;; Unlike the sensitive engine there is NO non-unique-secret guard: a large
;; value is, by classification, a big payload (no short-scalar collateral
;; hazard), and a re-keyed copy must be elided wherever it lands regardless
;; of whether the path-based `:app-db` slot also shipped its marker. A
;; sensitive value that is ALSO large stays the sensitive engine's concern —
;; sensitive wins, so the caller runs sensitive redaction FIRST and the large
;; pass only sees what survives.
;; ---------------------------------------------------------------------------

(defn- collect-large-markers!
  "Walk `node` at `path`, conj!ing onto transient vector `acc!` a
  `[raw-value marker]` pair for every node sitting at a declared-`:large`
  path (the whole subtree — the large blob — NOT its scalar leaves). Returns
  `acc!`.

  Reuses the SAME index-aware candidate-path machinery `walk` uses
  (`decl-match` / `fork-decl-paths` / `fork-index-paths`), so a seq-indexed or
  `:map-of`-keyed large declaration lands on the SAME node `elide-wire-value`
  marks. A matched node is collected verbatim and NOT descended (the marker
  replaces the whole subtree, exactly as the path-based walker does); a
  sensitive-declared node is skipped here (sensitive wins — the sensitive
  engine already redacted it). Non-matching collections recurse so a large
  slot nested deeper is still reached."
  [acc! node path decl-paths ctx]
  (let [large-decl (decl-match decl-paths (:large ctx))
        sensitive? (decl-sensitive? decl-paths (:sensitive ctx))]
    (cond
      ;; Sensitive wins — leave it to the sensitive engine.
      sensitive? acc!

      ;; A declared-large node: collect the raw value + the marker the
      ;; path-based egress would emit, and do NOT descend (the whole subtree
      ;; is the blob). A nil / already-marker value contributes nothing.
      (and large-decl (some? node) (not (marker? node)))
      (conj! acc! [node (->marker node path (marker-opts large-decl ctx))])

      (map? node)
      (let [decl-prefixes (:decl-prefixes ctx)]
        (reduce-kv (fn [a k v]
                     (collect-large-markers! a v (conj path k)
                                             (fork-decl-paths decl-paths k decl-prefixes) ctx))
                   acc! node))

      ;; Positional container (vector OR seq): descend each element at its
      ;; integer-indexed path, forking decl-paths through the index — the
      ;; shared `reduce-indexed-forks` micro-shape (rf2-zp0g04).
      (or (vector? node) (seq? node))
      (reduce-indexed-forks
        node decl-paths (:decl-prefixes ctx)
        (fn [a x i forked]
          (collect-large-markers! a x (conj path i) forked ctx))
        acc!)

      (set? node)
      (reduce (fn [a x] (collect-large-markers! a x path decl-paths ctx)) acc! node)

      :else acc!)))

(defn large-value-marker-map
  "The `{large-value marker}` map for `frame-id`'s declared-`:large` app-db
  paths, read out of the RAW `source-db` (rf2-9o5ixx). Each key is the whole
  blob value sitting at a declared-large path; each value is the
  `:rf.size/large-elided` marker `elide-wire-value` would have emitted for it
  (same `:path` / `:bytes` / `:handle` / digest under `wire-opts`).

  The value-match base for redacting a DERIVED tree on the large axis — the
  dual of `collect-sensitive-values`. A sensitive-declared node is excluded
  (sensitive wins; the caller redacts sensitive first). Returns `{}` when
  `source-db` is nil or the frame declares nothing large."
  [source-db frame-id wire-opts]
  (if (nil? source-db)
    {}
    (let [reg   (registry-of frame-id)
          large (or (:declarations reg) {})]
      (if (empty? large)
        {}
        (let [sensitive (or (:sensitive-declarations reg) {})
              ctx       {:frame-id         frame-id
                         :large            large
                         :sensitive        sensitive
                         :decl-prefixes    (decl-prefix-set {:large large :sensitive sensitive})
                         :include-digests? (true? (:rf.size/include-digests? wire-opts))
                         :as-of-epoch      (:as-of-epoch wire-opts)}
              pairs     (persistent!
                          (collect-large-markers! (transient []) source-db [] #{[]} ctx))]
          (into {} pairs))))))

(defn redact-matching-large-values
  "Walk `tree`, substituting any leaf `=` to a key of `marker-map` with that
  key's `:rf.size/large-elided` marker (rf2-9o5ixx). The large-axis dual of
  `redact-matching-values`. Recurses maps/vectors/sets/seqs; map KEYS are
  walked too. Returns `tree` unchanged when `marker-map` is empty.

  A matched node is replaced wholesale (the blob becomes its marker) and not
  descended further — the marker shape is terminal and idempotent under a
  re-projection pass."
  [tree marker-map]
  (if (empty? marker-map)
    tree
    (letfn [(walk* [t]
              (if-let [m (find marker-map t)]
                (val m)
                (cond
                  (map? t)    (persistent!
                                (reduce-kv (fn [acc k v]
                                             (assoc! acc (walk* k) (walk* v)))
                                           (transient {}) t))
                  (vector? t) (mapv walk* t)
                  (set? t)    (into #{} (map walk*) t)
                  (seq? t)    (map walk* t)
                  :else       t)))]
      (walk* tree))))

(defn redact-derived-large-values
  "Value-elide a DERIVED `tree` against the live values at `frame-id`'s
  declared-`:large` app-db paths, before wire egress (rf2-9o5ixx). The
  large-axis dual of `redact-derived-values`: collects the
  `{large-value marker}` map from `source-db` via `large-value-marker-map`
  (under the egress floor `wire-opts`), then substitutes any matching leaf in
  `tree` with the `:rf.size/large-elided` marker.

  Short-circuits: a nil `tree` or nil `source-db` returns `tree`; no
  declared-large (or no matching) values returns `tree` unwalked. The opt-out
  (trusted-local raw) belongs to the caller."
  [tree source-db frame-id wire-opts]
  (cond
    (nil? tree)      tree
    (nil? source-db) tree
    :else            (redact-matching-large-values
                       tree
                       (large-value-marker-map source-db frame-id wire-opts))))

;; ---------------------------------------------------------------------------
;; Composed multi-slot derived-tree egress — the SINGLE public assembling
;; helper (rf2-leggev Option B2, rf2-j7qbhm).
;;
;; The sensitive + large value-match arms above are the disassembled GEARS of
;; one operation: "redact a frame's app-db-sensitive / -large values out of a
;; derived tree (or a set of derived value slots) before off-box egress". The
;; one assembling consumer — Story-MCP's `scrub-rendered` (single tree, both
;; axes) and `scrub-explain-values` (one collection pass over many value
;; slots) — previously reached the gears piecemeal through the `re-frame.core`
;; façade. Spec 015 names the BOUNDARY / OPERATION, not the gearbox, so the
;; gears no longer crowd the façade: this one helper subsumes them, and the
;; low-level arms stay in this namespace for the rare bespoke caller.
;;
;; The one-pass property the gears were carved to provide is preserved: the
;; sensitive candidate set AND the large marker-map are each collected ONCE
;; from `source-db`, then substituted across EVERY slot. Sensitive runs FIRST
;; (it wins — the large collector already skips sensitive-declared nodes), and
;; the large pass sees only the sensitive-survived slots.
;; ---------------------------------------------------------------------------

(defn redact-derived-slots
  "Redact a frame's app-db-sensitive / -large values out of one or more
  DERIVED value slots before off-box egress — the SINGLE composed multi-slot
  egress helper (rf2-leggev Option B2). The value-based DUAL of the path-based
  `elide-wire-value`, assembled so a consumer names the BOUNDARY (\"scrub these
  derived slots for `frame-id`'s egress\") instead of hand-wiring the
  sensitive / large value-match gears.

  `m` carries the derived value(s). `slot-keys` selects which slots of `m` to
  scrub:

    - `nil` (or `()` / `[]`) — the SINGLE-TREE case: `m` *is* the derived tree
      (rendered hiccup, a resolved `:effective-args` map, a snapshot body) and
      is scrubbed wholesale. Subsumes Story-MCP's `scrub-rendered`.
    - a seq of keys — `m` is a MAP and each PRESENT key's value is scrubbed
      (absent keys are left untouched). Subsumes Story-MCP's
      `scrub-explain-values` over its enumerated value-slot keys.

  Both axes run, in `elide-wire-value`'s composition order — SENSITIVE first
  (it wins), then LARGE over the survivors:

    1. The sensitive candidate set is collected ONCE from `source-db` via
       `sensitive-value-set` (with the non-unique-secret guard, classified
       against the wire bytes `source-db` ships under `wire-opts`). A nil
       leaf matching a candidate becomes `:rf/redacted`.
    2. The `{large-value marker}` map is collected ONCE from `source-db` via
       `large-value-marker-map`. A matching leaf becomes the
       `:rf.size/large-elided` marker.

  Both collections are done a SINGLE time and reused across every slot — the
  one-pass property the low-level gears were carved to expose.

  `wire-opts` is the `elide-wire-value` egress floor the path-based `:app-db`
  slot ships under (e.g. an off-box-tool profile's `:rf.size/*` opt-set);
  `:frame frame-id` is supplied automatically, so the helper stays
  egress-profile-agnostic (the `:rf.egress/*` profile vocabulary stays in the
  tool / mcp-base layer). `wire-opts` additionally accepts:

    - `:rf.elision/extra-sensitive-source` — an EXTRA raw db whose UNGUARDED
      governed-sensitive values join the candidate union (no
      wire-disclosure subtraction). This is the FAIL-CLOSED pre-frame source:
      a documented no-run path (e.g. Story-MCP `explain-variant` reading a
      plan's authored `:db-seed` before any run allocates the frame) can seed
      candidates with no live app-db, so a secret authored into the seed and
      re-surfaced in a value slot still redacts. Defaults to none.

  Short-circuits: returns `m` unchanged when `source-db` is nil (no source of
  values — unless an `:rf.elision/extra-sensitive-source` is supplied, which
  then becomes the sole candidate source), and when the frame declares nothing
  sensitive / large (or every candidate is already disclosed) so there is
  nothing to substitute. The opt-out (trusted-local raw) belongs to the
  caller — pass the value through directly when the operator has waived
  redaction."
  [m slot-keys source-db frame-id wire-opts]
  (let [extra-src   (:rf.elision/extra-sensitive-source wire-opts)
        ;; Collect ONCE, reuse across every slot. Sensitive: the guarded live
        ;; candidates (against the wire bytes) UNIONed with the unguarded
        ;; pre-frame seed candidates (fail-closed, no wire subtraction).
        secrets     (into (if source-db
                            (sensitive-value-set source-db frame-id wire-opts)
                            #{})
                          (if extra-src
                            (collect-sensitive-values extra-src frame-id)
                            #{}))
        marker-map  (if source-db
                      (large-value-marker-map source-db frame-id wire-opts)
                      {})
        scrub       (fn [v]
                      ;; Sensitive FIRST (it wins), then large over survivors.
                      (-> v
                          (redact-matching-values secrets)
                          (redact-matching-large-values marker-map)))]
    (cond
      ;; Nothing collected from any source ⇒ identity (no walk).
      (and (empty? secrets) (empty? marker-map))
      m

      ;; Single-tree case: `m` IS the derived tree.
      (empty? slot-keys)
      (scrub m)

      ;; Multi-slot case: scrub each PRESENT slot of the map `m`.
      :else
      (reduce (fn [acc k]
                (if (contains? acc k)
                  (update acc k scrub)
                  acc))
              m
              slot-keys))))

;; EP-0015 §8 (rf2-d2r3um): no `:elision/populate-from-schemas!` hook —
;; schemas no longer feed the app-db egress registry (frame policy owns it).
(late-bind/set-fn! :elision/sensitive-declarations sensitive-declarations)
(late-bind/set-fn! :elision/clear-warning-cache! clear-warning-cache!)
