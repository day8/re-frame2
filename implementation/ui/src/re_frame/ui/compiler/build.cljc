(ns re-frame.ui.compiler.build
  "The ONE build-scoped compiler state model (rf2-vxgfnd.16).

  The S1 compiler runs inside a long-lived JVM (the shadow-cljs daemon, a
  REPL, a whole test run). Its build registries — view digests, the
  Layer-1 root-site and frame-plan indexes, the Root Descriptor index,
  the compile-time custom-element declarations — MUST be a pure function
  of the CURRENT build inputs, never of process history. Recompiling,
  editing, renaming, or deleting a source has to leave those registries
  exactly as a clean process would: the process lifetime is NOT the build
  lifetime.

  Before this model the registries were process-global `defonce` atoms
  that only ever `assoc`ed the current declaration, so a deleted or
  renamed `defview` / root / frame plan / `ui/custom-element` left its old
  contribution behind — watch builds, sequential builds in one daemon, and
  REPL reloads observed ghosts a fresh process never would, and a live
  file could take a false cross-file `:rf.error/duplicate-root-id` /
  `:rf.error/frame-payload-conflict` from a deleted site.

  ## The model

  Each registry stays a plain aggregate atom (`{k v}`) so every existing
  consumer reads it unchanged — no hot-path lookup pays for this
  bookkeeping. Alongside, this namespace holds a per-SOURCE contribution
  ledger: for each registry, which keys each source (a source-file path,
  the compile unit) contributed, and the build generation it last did so.

  A source's whole contribution is replaced ATOMICALLY the first time it
  declares in a new build generation (self-arming — never the unsound
  'clear on the first macro', which breaks multiple declarations in one
  file and file deletion). So:

    - multiple declarations in one file accumulate, then replace together;
    - a recompiled / edited / renamed file drops its entire prior
      contribution and re-accumulates only what it now declares;
    - a deleted DECLARATION vanishes when its file re-declares without it.

  ## Lifecycle hooks (the smallest integration)

    (begin-build! [build-id?])  a compile pass starts. Same build-id bumps
                                the generation (incremental — every source
                                re-opens fresh on its next declaration); a
                                different build-id starts an isolated slice
                                (independent builds in one JVM never cross-
                                contribute — the wipe is `reset-build!`).
    (reconcile!)                a WHOLE build pass ended: drop every source
                                that did NOT re-declare this generation (a
                                deleted / renamed FILE). Only sound after a
                                full pass — an incremental pass recompiles a
                                SUBSET, whose untouched files legitimately
                                keep their prior generation, so it must NOT
                                reconcile.
    (reset-build!)              hard-clear every registered aggregate + the
                                ledger — the clean-build / test-isolation /
                                build-id-switch boundary.

  Owners register their aggregate atom (`register!`) at load and route
  every write through `contribute!` (plain assoc registries) or
  `open-source!` + `note!` (the Layer-1 indexes, whose pre-write conflict
  check must read the aggregate AFTER the source's stale rows are evicted,
  so a source never conflicts with its own ghost).

  Compile-time state is mutated single-threaded (macro expansion), so the
  two-atom (aggregate + ledger) updates need no coordination.")

;; ---------------------------------------------------------------------------
;; Registry ids (opaque keys naming the five build-scoped registries)
;; ---------------------------------------------------------------------------

(def views       ::views)        ; view-id -> [template-fp hook-sig] (digest)
(def roots       ::roots)        ; Layer-1 root-site index
(def plans       ::plans)        ; Layer-1 frame-plan index
(def descriptors ::descriptors)  ; Root Descriptor index
(def elements    ::elements)     ; compile-time custom-element declarations

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^{:private true
           :doc "Monotonic build-pass counter. `begin-build!` bumps it; a
  source re-opens its contribution the first time it declares at a
  generation newer than the one its ledger records."}
  generation (atom 0))

(defonce ^{:private true
           :doc "The build-id of the current slice — a different id at
  `begin-build!` isolates by wiping (independent builds never share rows)."}
  build-id (atom ::none))

(defonce ^{:private true
           :doc "registry-id -> {:agg <aggregate atom>
                                 :sources {source {:gen g :keys #{k}}}}.
  `:agg` is the plain-map atom consumers read; `:sources` is the per-source
  contribution ledger this model reconciles."}
  registries (atom {}))

;; ---------------------------------------------------------------------------
;; Registration (owners declare their aggregate at load)
;; ---------------------------------------------------------------------------

(defn register!
  "Declare a build-scoped registry: `reg-id` names it, `agg` is the
  plain-map atom consumers read. Idempotent — the existing ledger is kept
  so a hot ns reload never drops live contributions."
  [reg-id agg]
  (swap! registries update reg-id
         (fn [r] (if r (assoc r :agg agg) {:agg agg :sources {}})))
  nil)

;; ---------------------------------------------------------------------------
;; Per-source contribution
;; ---------------------------------------------------------------------------

(defn open-source!
  "Ensure `source`'s contribution to `reg-id` is fresh for the current
  build generation. On `source`'s FIRST declaration this generation, evict
  its prior keys from the aggregate (the atomic per-source replace) and
  reset its key set; idempotent for later same-generation declarations.
  Call BEFORE reading the aggregate for a conflict check so a source never
  conflicts with its own stale row."
  [reg-id source]
  (let [g @generation
        r (get @registries reg-id)]
    (when (and r (not= g (get-in r [:sources source :gen])))
      (when-let [ks (seq (get-in r [:sources source :keys]))]
        (apply swap! (:agg r) dissoc ks))
      (swap! registries assoc-in [reg-id :sources source] {:gen g :keys #{}})))
  nil)

(defn note!
  "Record that `source` contributed key `k` to `reg-id` (after storing it
  in the aggregate). Pairs with `open-source!`."
  [reg-id source k]
  (swap! registries update-in [reg-id :sources source :keys] (fnil conj #{}) k)
  nil)

(defn contribute!
  "The plain-registry case: open `source`'s scope, store `k` -> `v` in the
  aggregate, and note it. Registries with a pre-write conflict check use
  `open-source!` + `note!` around the check instead."
  [reg-id source k v]
  (open-source! reg-id source)
  (swap! (:agg (get @registries reg-id)) assoc k v)
  (note! reg-id source k)
  nil)

;; ---------------------------------------------------------------------------
;; Lifecycle hooks
;; ---------------------------------------------------------------------------

(defn reset-build!
  "Hard-clear every registered aggregate and the whole ledger — the
  clean-build / independent-build / test-isolation boundary (generalises
  the old per-namespace `reset-build-registries!`). `begin-build!`'s
  per-source replace covers incremental correctness; this is the fresh
  top-level slice."
  []
  (doseq [[reg-id r] @registries]
    (reset! (:agg r) {})
    (swap! registries assoc-in [reg-id :sources] {}))
  nil)

(defn begin-build!
  "Open a compile pass. The SAME `build-id` bumps the generation
  (incremental — every source re-opens fresh on its next declaration, so
  edits / renames / declaration deletions replace rather than accumulate);
  a DIFFERENT `build-id` starts an isolated slice (wipes first, so two
  build-ids sharing one JVM never contribute rows or conflicts to each
  other). The zero-arg form uses the sole default build."
  ([] (begin-build! ::default))
  ([id]
   (when (not= id @build-id)
     (reset! build-id id)
     (reset-build!))
   (swap! generation inc)
   nil))

(defn reconcile!
  "Whole-build end hook: drop every source that did NOT re-declare in the
  current generation (a deleted or renamed FILE no longer contributes),
  evicting its keys from every aggregate — so the registries equal a clean
  build over exactly the files that declared this pass. Sound ONLY after a
  WHOLE pass; an incremental pass that recompiles a subset must not call it
  (its untouched files keep their prior generation, which is correct)."
  []
  (let [g @generation]
    (doseq [[reg-id r] @registries
            [source {sg :gen ks :keys}] (:sources r)
            :when (not= sg g)]
      (when (seq ks) (apply swap! (:agg r) dissoc ks))
      (swap! registries update-in [reg-id :sources] dissoc source)))
  nil)

;; ---------------------------------------------------------------------------
;; Introspection (tests / tooling)
;; ---------------------------------------------------------------------------

(defn current-generation
  "The current build generation (tests / tooling)."
  []
  @generation)

(defn source-keys
  "The keys `source` currently contributes to `reg-id` (tests / tooling)."
  [reg-id source]
  (get-in @registries [reg-id :sources source :keys] #{}))
