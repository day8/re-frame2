(ns re-frame.ui.compiler.build
  "The ONE build-scoped compiler-state authority, keyed PER shadow build id
  (rf2-vxgfnd.16 → rf2-df9873).

  The S1 compiler runs inside a long-lived JVM (the shadow-cljs daemon, a
  REPL, a whole test run). Its build registries — view digests, the
  Layer-1 root-site and frame-plan indexes, the Root Descriptor index,
  the compile-time custom-element declarations — MUST be a pure function
  of the CURRENT build inputs, never of process history. Recompiling,
  editing, renaming, or deleting a source has to leave those registries
  exactly as a clean process would: the process lifetime is NOT the build
  lifetime.

  One daemon compiles `re-frame.ui` for MULTIPLE builds at once
  (`:node-test`, `:node-test-ui`, `:ui-bench`, browser builds). The prior
  model held a single GLOBAL build-id and WIPED on switch, so two builds
  compiling the same namespaces wiped each other's registries — regressing
  cross-file duplicate detection. This model keys everything PER build id,
  so independent builds are isolated by construction and never cross-
  contribute.

  ## Build identity comes from the compiler env, not a hook

  A contribution's owning build is read AMBIENTLY from the per-thread CLJS
  compiler env (`cljs.env/*compiler*` -> `:shadow.build.cljs-bridge/state`
  -> `:shadow.build/build-id`) — correct even under shadow's default
  PARALLEL compile, where each build's analysis threads carry that build's
  compiler env. Lifecycle hooks own ONLY pass BOUNDARIES (begin / commit /
  abort); they never assign identity. Outside a real compile (a REPL, the
  plain-JVM `clojure -M:test` path) identity falls back to the id the most
  recent `begin-build!` opened, then to `::default`; tests/REPL can pin it
  with the `*build-id*` dynamic.

  ## The one atom + pure transitions

  ONE atom holds `{build-id -> slice}`; every mutation is a single `swap!`
  with a PURE transition. A slice:

    {:pass-open? bool
     :committed  {source {reg-id {k v}}}   ; last-known-good, per source
     :staged     {source {reg-id {k v}}}   ; the OPEN pass's re-declarations
     :touched    #{source}}                ; sources that re-ran this pass

  `source` is the declaring NAMESPACE symbol (the compile unit — matching
  the runtime arm's `[build-id ns-sym]` key; file/line live in error coords
  only, so a REPL pseudo-file never forges a false duplicate and Windows
  path normalization is a non-issue).

  A registry's OBSERVABLE aggregate (`aggregate`) is the EFFECTIVE view:
  committed rows of sources NOT touched this pass, PLUS the staged rows of
  the sources that re-ran. So a source's whole prior contribution is
  superseded ATOMICALLY the moment it re-declares (touched → its committed
  rows drop out of the effective view), and a pass that ABORTS discards its
  staging and republishes the committed last-known-good untouched.

  ## Pass boundaries (last-known-good publication)

    (begin-build! [id])   a compile pass starts: open the pass and DISCARD
                          any staging a failed prior pass left (a successful
                          pass committed at its close, so there is none).
    (commit-build! [id])  the pass succeeded: every touched source REPLACES
                          its committed contribution with what it staged
                          (or is evicted if it staged nothing — a dropped
                          declaration); untouched sources are kept.
    (reconcile! [id])     a WHOLE-build pass ended: commit, THEN drop every
                          committed source that did not re-declare (a
                          deleted / renamed FILE). Sound only after a full
                          pass; an incremental pass recompiles a SUBSET.
    (finish-build! id ms) whole-build finalize against an AUTHORITATIVE
                          member set (the shadow hook's `:build-sources`):
                          commit, then drop committed sources absent from
                          `ms`. Safe on EVERY pass — macro silence is never
                          the deletion signal.
    (abort-build! [id])   the pass failed: discard staging, keep committed.
    (reset-build!)        hard-clear every slice — the clean-build /
                          test-isolation boundary.

  With NO pass open (a REPL form eval; the plain-JVM test path) a
  contribution UPSERTS per-key straight into committed (no begin/commit
  cycle, no sibling eviction) — the RULED REPL posture. A deletion made by
  a bare REPL re-eval converges at the next watch pass; no design can
  observe an unsaved deletion.

  Every check-then-act (the Layer-1 duplicate/conflict indexes) runs INSIDE
  one `swap!` via `contribute-checked!`, so parallel namespace compilation
  can never expose an evict-then-readd gap or a check-then-assoc race.")

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

(def ^{:private true
       :doc "An empty per-build slice — the pure transition seed."}
  empty-slice
  {:pass-open? false :committed {} :staged {} :touched #{}})

(defonce ^{:private true
           :doc "The ONE authority: build-id -> slice. Every registry, every
  build, one atom, pure transitions."}
  state (atom {}))

(defonce ^{:private true
           :doc "The build-id the most recent `begin-build!` opened — the
  identity fallback for the REPL / plain-JVM paths, where no CLJS compiler
  env is bound. Never consulted under a real compile (the compiler env wins,
  and is per-thread correct under parallel builds)."}
  session-build (atom ::default))

(def ^:dynamic *build-id*
  "Explicit identity override (tests / REPL). Wins over the compiler env and
  the session fallback; bind it per-thread to drive interleaved builds."
  nil)

;; ---------------------------------------------------------------------------
;; Ambient build identity
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- shadow-build-id
     "The build id of the compile currently running on THIS thread, read from
     the CLJS compiler env: `cljs.env/*compiler*` (an atom) ->
     `:shadow.build.cljs-bridge/state` -> `:shadow.build/build-id`. nil
     outside a shadow compile. Pinned behind this one fn so a shadow upgrade
     that moves the key breaks here, loudly and in one place."
     []
     (try
       (when-let [compiler-var (resolve 'cljs.env/*compiler*)]
         (when-let [compiler-atom @compiler-var]
           (get-in @compiler-atom
                   [:shadow.build.cljs-bridge/state :shadow.build/build-id])))
       (catch Throwable _ nil))))

(defn current-build-id
  "The build id a contribution belongs to: the explicit `*build-id*`
  override, else the ambient shadow compile (per-thread compiler env), else
  the last `begin-build!` id, else `::default`."
  []
  (or *build-id*
      #?(:clj (shadow-build-id) :cljs nil)
      @session-build
      ::default))

;; ---------------------------------------------------------------------------
;; Pure reads
;; ---------------------------------------------------------------------------

(defn- effective
  "One slice's EFFECTIVE aggregate for `reg-id`: committed rows of the
  sources NOT touched this pass, merged with the staged rows of the sources
  that re-ran — EXCLUDING `exclude` (pass `::none` to exclude nothing). Pure."
  [slice reg-id exclude]
  (let [touched (:touched slice)
        committed (reduce-kv
                   (fn [m src regs]
                     (if (or (= src exclude) (contains? touched src))
                       m
                       (merge m (get regs reg-id))))
                   {} (:committed slice))]
    (reduce-kv
     (fn [m src regs] (if (= src exclude) m (merge m (get regs reg-id))))
     committed (:staged slice))))

(defn aggregate
  "The ambient (or given) build's current aggregate for `reg-id` — the
  effective view (committed last-known-good for untouched sources plus the
  open pass's staged rows). A pure function of the current build inputs; the
  read every consumer uses in place of a bare atom deref."
  ([reg-id] (aggregate reg-id (current-build-id)))
  ([reg-id build-id]
   (effective (get @state build-id empty-slice) reg-id ::none)))

(defn element-properties
  "The declared `:properties` set for custom-element `tag` in the ambient (or
  given) build's compile-time `elements` registry — the compile-path read the
  template analyzer uses to classify a custom element's props (property vs
  attribute). Per-build, resolved through the ambient compiler build identity
  (the SAME `current-build-id` mechanism every other registry read uses), so
  one daemon's parallel builds never cross-classify; NEVER a process-global
  last-writer-wins mirror. Empty set when `tag` is undeclared in this build."
  ([tag] (element-properties tag (current-build-id)))
  ([tag build-id] (get-in (aggregate elements build-id) [tag :properties] #{})))

(defn pass-open?
  "Whether a compile pass is currently open for the ambient (or given)
  build (tests / tooling)."
  ([] (pass-open? (current-build-id)))
  ([build-id] (:pass-open? (get @state build-id empty-slice) false)))

;; ---------------------------------------------------------------------------
;; Contribution (pure transitions)
;; ---------------------------------------------------------------------------

(defn- write-slice
  "Pure: record `source` contributing `k`->`v` to `reg-id`. A pass open →
  STAGE it (and mark the source touched, so its committed contribution is
  superseded on commit). No pass → UPSERT straight into committed (the REPL
  posture: per-key, no sibling eviction)."
  [slice reg-id source k v]
  (if (:pass-open? slice)
    (-> slice
        (update :touched conj source)
        (assoc-in [:staged source reg-id k] v))
    (assoc-in slice [:committed source reg-id k] v)))

(defn contribute!
  "Contribute `source`'s `k`->`v` to the plain registry `reg-id` in the
  ambient build — one `swap!`, pure. The Layer-1 indexes use
  `contribute-checked!` for their pre-write conflict check."
  [reg-id source k v]
  (swap! state update (current-build-id)
         (fnil write-slice empty-slice) reg-id source k v)
  nil)

(defn contribute-checked!
  "The atomic conflict-checked contribution for a Layer-1 index. Inside ONE
  `swap!`: look up `k` in the ambient build's EFFECTIVE map for `reg-id`,
  EXCLUDING `source`'s own rows (a source never conflicts with itself —
  same-source re-declaration replaces), and call `(conflict-fn existing)`. If
  it returns a non-nil value the write is REJECTED and that value is returned
  (the caller builds + throws its domain error); otherwise `source`
  contributes `k`->`v` (staged or upserted) and nil is returned. Atomic, so
  parallel compilation can neither drop a concurrent write (check-then-assoc
  race) nor miss a genuine duplicate."
  [reg-id source k v conflict-fn]
  (let [outcome (atom nil)]             ; call-local — only this call's retries touch it
    (swap! state update (current-build-id)
           (fn [slice]
             (let [slice    (or slice empty-slice)
                   existing (get (effective slice reg-id source) k)
                   conflict (conflict-fn existing)]
               (if conflict
                 (do (reset! outcome {:conflict conflict}) slice)
                 (do (reset! outcome nil)
                     (write-slice slice reg-id source k v))))))
    @outcome))

;; ---------------------------------------------------------------------------
;; Pass boundaries
;; ---------------------------------------------------------------------------

(defn begin-build!
  "Open a compile pass for `build-id` (the zero-arg form uses the sole
  default build). DISCARDS any staging a failed prior pass left — a
  successful pass commits at its close, so there is normally none — and marks
  the pass open. Sets the session-build identity fallback so subsequent
  contributions on the REPL / plain-JVM path route here."
  ([] (begin-build! ::default))
  ([build-id]
   (reset! session-build build-id)
   (swap! state update build-id
          (fn [s] (assoc (or s empty-slice) :pass-open? true :staged {} :touched #{})))
   nil))

(defn- commit-slice
  "Pure: fold the open pass's staging into committed — every touched source
  REPLACES its committed contribution with what it staged, or is evicted if
  it staged nothing."
  [slice]
  (let [committed (reduce
                   (fn [c src]
                     (let [staged (get-in slice [:staged src])]
                       (if (seq staged) (assoc c src staged) (dissoc c src))))
                   (:committed slice)
                   (:touched slice))]
    (assoc slice :committed committed :staged {} :touched #{} :pass-open? false)))

(defn commit-build!
  "Commit the open pass for `build-id`: every touched source's staged
  contribution replaces its committed one (or evicts it), untouched committed
  sources are kept, and the pass closes. The last-known-good publication
  point for an INCREMENTAL pass."
  ([] (commit-build! (current-build-id)))
  ([build-id]
   (swap! state update build-id (fn [s] (commit-slice (or s empty-slice))))
   nil))

(defn- keep-members
  "Pure: drop every committed source absent from `keep?` (a membership
  predicate over sources)."
  [slice keep?]
  (update slice :committed
          (fn [committed]
            (select-keys committed (filter keep? (keys committed))))))

(defn reconcile!
  "Whole-build finalize for `build-id`: commit the open pass, THEN drop every
  committed source that did NOT re-declare this pass (a deleted / renamed
  FILE). Sound ONLY after a WHOLE pass — an incremental pass recompiles a
  subset, whose untouched sources legitimately keep their prior contribution,
  so it must NOT reconcile."
  ([] (reconcile! (current-build-id)))
  ([build-id]
   (swap! state update build-id
          (fn [s]
            (let [s (or s empty-slice)
                  touched (:touched s)]
              (-> s commit-slice (keep-members touched)))))
   nil))

(defn finish-build!
  "Whole-build finalize against an AUTHORITATIVE member set (the shadow
  hook's `:build-sources`): commit the open pass, then drop every committed
  source absent from `members` (a coll/set of source ns-syms). Safe on EVERY
  pass, incrementals included — macro silence is never the deletion signal,
  so authoritative membership, not `touched`, drives eviction."
  [build-id members]
  (let [members (set members)]
    (swap! state update build-id
           (fn [s] (-> (or s empty-slice) commit-slice (keep-members members)))))
  nil)

(defn abort-build!
  "Discard the open pass's staging for `build-id`; the committed
  last-known-good survives untouched. A failed / aborted compile."
  ([] (abort-build! (current-build-id)))
  ([build-id]
   (swap! state update build-id
          (fn [s] (assoc (or s empty-slice) :staged {} :touched #{} :pass-open? false)))
   nil))

(defn reset-build!
  "Hard-clear every build slice — the clean-build / independent-build /
  test-isolation boundary. `begin-build!`'s per-source replace covers
  incremental correctness; this is the fresh top-level state."
  []
  (reset! state {})
  (reset! session-build ::default)
  nil)
