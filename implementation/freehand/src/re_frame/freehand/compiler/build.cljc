(ns re-frame.freehand.compiler.build
  "The build-scoped compiler registries and Option-C acceptance transaction.

  A real Shadow build carries its own state. Its retained `:compiler-env`
  contains one accepted `{build-id registries version}` snapshot and,
  only while compiling, disposable scratch. Macro expansion mutates scratch
  through the bound `cljs.env/*compiler*` atom. Compile finish returns a new
  build-state containing the candidate snapshot; Shadow retaining that value
  after the entire configured pipeline succeeds IS the commit. A later
  optimize/check/flush/watch failure discards it. There is no process-global
  latest-build atom and no private completion callback.

  One daemon can compile many build ids concurrently because each build's
  functional state and compiler-env are independent. JVM tooling outside a
  compiler binding must pass the explicit retained build-state it wants to
  read (`accepted-snapshot`, `accepted-aggregate`).

  A compile slice has the shape:

    {:pass-open? bool
     :authoritative-members #{ns ...} | nil
     :committed  {source {reg-id {k v}}}   ; last-known-good, per source
     :staged     {source {reg-id {k v}}}   ; the OPEN pass's re-declarations
     :touched    #{source}}                ; sources that re-ran this pass

  `source` is the declaring NAMESPACE symbol (the compile unit — matching
  the runtime arm's `[build-id ns-sym]` key; file/line live in error coords
  only, so a REPL pseudo-file never forges a false duplicate and Windows
  path normalization is a non-issue).

  The effective aggregate is accepted rows of untouched sources plus staged
  rows for sources recompiled in this pass. Source replacement and every
  collision check happen inside one compiler-env `swap!`, so parallel macro
  expansion cannot create an evict/re-add gap or check/write race. Finish
  commits touched rows and evicts sources absent from Shadow's authoritative
  `:build-sources` graph; cache silence is never treated as deletion.

  Direct no-pass REPL contributions use a separate overlay seeded from the
  accepted snapshot. They support immediate body/HMR diagnostics but never
  change accepted registries; the next prepare clears the
  overlay and seeds fresh scratch from accepted state.

  `state` plus begin/commit/abort below remain only as a plain-JVM/test harness
  for the same pure slice transitions. They are not Shadow build authority."
  (:require [re-frame.error :as error]
            [re-frame.freehand.eq :as eq]))

;; ---------------------------------------------------------------------------
;; Registry ids (opaque keys naming the build-scoped registries)
;; ---------------------------------------------------------------------------

(def views       ::views)        ; view-id -> [template-fp hook-sig] (digest)
(def ^:private view-declarations ::view-declarations) ; view-id -> [ns var]
(def view-static ::view-static)  ; view-id -> {:caps #{..} :deps #{..}} (static-root proof, Spec 004C §3; plain-JVM/SSR slice, rf2-u53yy.1 S3)
(def roots       ::roots)        ; Layer-1 root-site index
(def elements    ::elements)     ; compile-time custom-element declarations (plain-JVM/SSR slice)
(def ^:private element-declarations ::element-declarations) ; tag -> owning ns

;; Custom-element declarations are DECOUPLED from the view-slice on the Shadow
;; build path (rf2-u53yy.1 S1). Property lowering is decided at MACROEXPANSION,
;; before compile-finish, so elements are the one ordering exception that cannot
;; ride the compile-finish analyzer-map carrier the other registries use. Instead
;; the build hook HARVESTS every authoritative build member's literal
;; declarations at `:compile-prepare` (`harvest.clj`, a bounded syntactic reader)
;; into a flat all-members manifest `{tag {:properties #{…}}}` — a pure function
;; of the build's SOURCE, independent of which sources re-expand. That manifest
;; lives beside the accepted snapshot (`:elements`) and in the open pass's scratch
;; (`:element-manifest`), NEVER in the per-source `:committed`/`:staged`/`:touched`
;; ledger, so harvesting a WARM source's elements can no longer mark it touched
;; and evict its committed views at commit. The `v/custom-element` macro is then
;; validation/reporting-only during a real Shadow build pass. The plain-JVM / SSR
;; and REPL paths (no `:compile-prepare` hook) keep populating the per-source
;; `::elements` slice through the macro + lazy own-source harvest below.

(def refused-property-names
  "The author-space names a `(v/custom-element tag {:properties #{…}})`
  declaration may never classify as JS properties — `:class` and `:style`.

  Both are ATTRIBUTES with grammars of their own: `:class` composes with the
  `.class#id` tag sugar and `:style` carries the CSS map, and 004B/004D say so
  outright — on a custom element, \"booleans/`:class`/`:style` follow DOM
  rules\" exactly as they do on a `<div>`. Declaring one as a property is a
  category error rather than an unusual-but-meaningful choice.

  It had to become a REFUSAL because the substrate's answer was silent WRONG
  OUTPUT. A property-classified name lands in the node's
  `:rf.ui/property-props` set, and the serialiser omits exactly those names
  from markup (a server cannot run a property setter; the client applies them
  at hydration) — so the declaration was accepted and the element rendered
  with its class and style absent from the HTML, while the structural fold,
  reading the same declaration but serialising nothing, still carried them.
  One declaration, two answers, and no diagnostic anywhere, because both
  answers are structurally well-formed (rf2-oazgv).

  TWO NAMES, and deliberately only two. This is NOT a general
  attribute-versus-property taxonomy: the declaration remains the sole
  classifier for every other name, including one that is also a standard HTML
  attribute spelling (`:tab-index` is the JS property on a tag that declares
  it). Nor is it a coercion — silently rewriting `:class` into an attribute
  would be the same sin as silently dropping it. Extending this roster is
  formally a RULING (rf2-5gliq ruled the v1 `:properties` grammar), not a
  maintenance edit; a candidate name needs evidence that it is demonstrably a
  category error, on its own bead.

  Read by the two doors that recognise a declaration: the `v/custom-element`
  macro validator (`re-frame.freehand.compiler/custom-element**`, which
  refuses with `:rf.ui.compile/bad-custom-element`) and the syntactic
  prepare-time harvest (`re-frame.freehand.compiler.harvest`, which declines
  to SEED what the macro will refuse)."
  #{:class :style})

;; `::view-static` is similarly DECOUPLED from the Shadow build path (rf2-u53yy.1
;; S3), but by the simplest mechanism of all: its ONLY reader, `v/render-static`,
;; is JVM-only (a CLJS expansion is rejected), so a real Shadow build never reads
;; it. Unlike `views`/`elements` it is not blocker/eviction-relevant, so it needs
;; NO cache-durable Shadow-path descriptor — the `defview` macro simply does not
;; contribute it under a Shadow build pass (`shadow-build-pass?`), keeping the
;; Shadow-path registries macro-independent (the S6 direction). The per-source
;; slice here is populated + read only on the plain-JVM / SSR / REPL path, where
;; render-static reads it MID-EXPANSION; the same per-view `{:caps :deps}` facts
;; also ride each view's registered manifest (`:static-facts`) for cross-build/AOT
;; resolution.

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(def ^{:private true
       :doc "An empty per-build slice — the pure transition seed."}
  empty-slice
  {:pass-open? false
   :authoritative-members nil
   :committed {}
   :staged {}
   :touched #{}})

(defonce ^{:private true
           :doc "Plain-JVM/test fallback state only. Real Shadow builds keep
  their accepted snapshot and disposable pass scratch in Shadow's functional
  compiler-env; no successful-build authority lives in this process atom."}
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

;; These keys live in Shadow's retained `:compiler-env`. They are deliberately
;; namespaced and data-only: Shadow's returned build-state is the transaction.
;; A failed downstream optimize/check/flush/watch step discards that state, so
;; an external last-known-good commit or private Shadow completion callback is
;; neither needed nor permitted.
(def accepted-snapshot-key ::accepted-snapshot)
(def scratch-key ::scratch)
(def repl-overlay-key ::repl-overlay)

(defn- empty-snapshot [build-id]
  {:build-id build-id
   :registries {}
   :elements {}
   :version 0})

(def ^:private shadow-bridge-key
  "Shadow's OUTER build-bridge marker. re-frame.freehand recognizes it but does NOT
  depend on it for build authority: a future Shadow rename/removal of this key
  must not strand re-frame.freehand's own private carrier."
  :shadow.build.cljs-bridge/state)

(def ^:private private-carrier-keys
  "re-frame.freehand's OWN compiler-env carrier keys — the accepted snapshot plus the
  disposable scratch/overlay. Their presence is authoritative re-frame.freehand build
  ownership, independent of Shadow's outer marker."
  [accepted-snapshot-key scratch-key repl-overlay-key])

(defn- ui-owned-compiler-state?
  "The ONE closed re-frame.freehand compiler-ownership recognizer. A compiler-env map
  is re-frame.freehand-owned when it carries any of re-frame.freehand's own private carrier
  keys — the authority re-frame.freehand itself established via the build hook — OR
  Shadow's still-recognized outer bridge marker (the pre-hook window / legacy
  path). Identity resolution, accepted/scratch reads, contribution writes, and
  finish all gate on THIS predicate, so a drift of the outer marker cannot route
  one build's authority onto another's while the private carrier is intact. Pure."
  [compiler-state]
  (boolean
   (or (contains? compiler-state shadow-bridge-key)
       (some #(contains? compiler-state %) private-carrier-keys))))

#?(:clj
   (defn- compiler-env-atom []
     (when-let [compiler-var (resolve 'cljs.env/*compiler*)]
       (let [v @compiler-var]
         (when (instance? clojure.lang.IAtom v) v)))))

#?(:clj
   (defn- owned-compiler-state
     "Raw recognizer: the bound compiler-env state iff re-frame.freehand-owned (a
     private carrier key or Shadow's still-recognized outer bridge marker), else
     nil. Does NOT validate build identity — every ambient read/write that
     observes or mutates the carrier goes through `validated-compiler-state`,
     which additionally fails closed on a malformed or contradictory identity."
     []
     (when-let [a (compiler-env-atom)]
       (let [s @a]
         (when (ui-owned-compiler-state? s) s)))))

(defn accepted-snapshot
  "Return the accepted re-frame.freehand snapshot carried by an explicit Shadow
  build-state/compiler-env. This is the only JVM read of a real build's
  finalized registries; callers must name the build-state they mean.
  Returns an empty version-0 snapshot when the build has not yet succeeded."
  [build-state-or-compiler-env]
  (let [compiler-env (or (:compiler-env build-state-or-compiler-env)
                         build-state-or-compiler-env)
        build-id (or (get-in compiler-env [accepted-snapshot-key :build-id])
                     (:shadow.build/build-id build-state-or-compiler-env)
                     (get-in compiler-env
                             [:shadow.build.cljs-bridge/state
                              :shadow.build/build-id])
                     ::default)]
    (or (get compiler-env accepted-snapshot-key)
        (empty-snapshot build-id))))

(defn accepted-aggregate
  "Registry aggregate from an explicit accepted Shadow build-state/compiler-env.
  Scratch and no-pass REPL overlay are intentionally invisible."
  [reg-id build-state-or-compiler-env]
  (reduce-kv (fn [m _src regs] (merge m (get regs reg-id)))
             {}
             (:registries (accepted-snapshot build-state-or-compiler-env))))

(defn accepted-element-manifest
  "The accepted flat custom-element manifest `{tag {:properties #{…}}}` from an
  explicit Shadow build-state/compiler-env — the all-members harvest the last
  successful build finalized. Decoupled from `:registries` (it is not a
  per-source view-slice registry); the coarse warm-invalidation baseline and the
  warm-watch topology gate read it here."
  [build-state-or-compiler-env]
  (:elements (accepted-snapshot build-state-or-compiler-env) {}))

;; ---------------------------------------------------------------------------
;; Ambient build identity
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- carrier-build-id
     "THE single validated re-frame.freehand build-identity resolver — private-carrier
     presence, build-id extraction, and private-versus-Shadow agreement in ONE
     place, resolved from re-frame.freehand's OWN authority rather than Shadow's outer
     marker. Every ambient read/write/finish boundary traverses it, so a
     malformed or contradictory carrier is rejected fail-closed everywhere, not
     just where `current-build-id` happens to be consulted.

     Identity lives ONLY in the accepted-snapshot carrier (its `:build-id`); the
     disposable scratch/overlay slices carry no identity. When `compiler-state`
     carries that accepted carrier it is authoritative: its `:build-id` is the
     answer. An accepted carrier with a MISSING build id, or with an id that
     DISAGREES with a still-present Shadow bridge id, fails loudly — downgrading
     to the process session fallback could route this compilation into a
     different parallel build.

     With no accepted carrier yet (the pre-hook window / legacy path, or a
     no-hook REPL/watch overlay) identity comes from Shadow's outer bridge
     marker: a recognized bridge supplies the id. A recognized bridge present
     without its leaf id fails loudly rather than downgrade to the session
     fallback, which the contract reserves for a genuinely plain CLJS/REPL
     environment carrying NEITHER recognized marker NOR carrier. Callers pass a
     state already known re-frame.freehand-owned (see `owned-compiler-state`); the
     fail-closed throw is the gate."
     [compiler-state]
     ;; `private?` is the ACCEPTED-SNAPSHOT identity carrier specifically — not
     ;; the disposable scratch/overlay (which a no-hook REPL/watch write creates
     ;; with no accepted snapshot and whose identity legitimately resolves from
     ;; the Shadow bridge below).
     (let [private?   (contains? compiler-state accepted-snapshot-key)
           private-id (get-in compiler-state [accepted-snapshot-key :build-id])
           shadow-id  (get-in compiler-state
                              [shadow-bridge-key :shadow.build/build-id])]
       (if private?
         (cond
           (nil? private-id)
           (throw
            (ex-info
             (str "re-frame.freehand compiler found its private build carrier but no "
                  "build id in the accepted snapshot; refusing the session-build "
                  "fallback because it can route this compilation into a "
                  "different parallel build")
             {::error ::private-build-id-unresolved
              :recovery :check-ui-build-carrier
              :expected-path [accepted-snapshot-key :build-id]}))

           (and (some? shadow-id) (not= shadow-id private-id))
           (throw
            (ex-info
             (str "re-frame.freehand compiler private build carrier id " private-id
                  " disagrees with Shadow's still-present build id " shadow-id
                  "; refusing to guess which build this compilation belongs to")
             {::error ::private-build-id-shadow-disagreement
              :recovery :check-ui-build-carrier
              :private-build-id private-id
              :shadow-build-id shadow-id}))

           :else private-id)

         ;; No private carrier yet: the state is re-frame.freehand-recognized only
         ;; through Shadow's outer bridge, which must therefore carry the id.
         (do
           (when (nil? shadow-id)
             (throw
              (ex-info
               (str "re-frame.freehand compiler could not resolve shadow's build id "
                    "from a recognized build bridge; refusing the session-build "
                    "fallback because it can route this compilation into a "
                    "different parallel build")
               {::error ::shadow-build-id-unresolved
                :recovery :check-shadow-compiler-env
                :expected-path [shadow-bridge-key :shadow.build/build-id]})))
           shadow-id)))))

#?(:clj
   (defn- validated-compiler-state
     "The ONE resolver every ambient accepted/scratch/overlay read and every
     ambient write traverses: the bound owned compiler-state with its build
     identity validated by `carrier-build-id`, which throws fail-closed on a
     malformed or contradictory carrier BEFORE it can be observed or mutated.
     nil when no owned carrier is bound (the genuinely plain / REPL fallback)."
     []
     (when-let [s (owned-compiler-state)]
       (carrier-build-id s)                 ; fail-closed identity gate
       s)))

#?(:clj
   (defn- ambient-build-id
     "The build id the current compilation belongs to, resolved and validated
     through `carrier-build-id` from re-frame.freehand's OWN authority rather than
     Shadow's outer marker. nil for a genuinely plain / REPL env (no owned
     carrier), where `current-build-id` uses the session fallback."
     []
     (when-let [s (owned-compiler-state)]
       (carrier-build-id s))))

(defn current-build-id
  "The build id a contribution belongs to: the explicit `*build-id*`
  override, else the ambient shadow compile (per-thread compiler env), else
  the last `begin-build!` id, else `::default`."
  []
  (or *build-id*
      #?(:clj (ambient-build-id) :cljs nil)
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

#?(:clj
   (defn- ambient-shadow-slice
     "Effective disposable slice for the currently bound Shadow compiler.
     During a file/watch pass this is `scratch`; during no-pass REPL work it is
     an isolated overlay seeded from the accepted snapshot. Neither is an
     accepted successful-build authority. The carrier's build identity is
     validated (`validated-compiler-state`) before any slice is observed."
     []
     (when-let [compiler-state (validated-compiler-state)]
       (or (get compiler-state scratch-key)
           (get compiler-state repl-overlay-key)
           (assoc empty-slice
                  :committed (:registries (accepted-snapshot compiler-state)))))))

#?(:clj
   (defn- ambient-accepted-slice []
     (when-let [compiler-state (validated-compiler-state)]
       (assoc empty-slice
              :committed (:registries (accepted-snapshot compiler-state))))))

(defn aggregate
  "The ambient (or given) build's current aggregate for `reg-id` — the
  effective view (committed last-known-good for untouched sources plus the
  open pass's staged rows). A pure function of the current build inputs; the
  read every consumer uses in place of a bare atom deref."
  ([reg-id]
   (if-let [slice #?(:clj (ambient-shadow-slice) :cljs nil)]
     (effective slice reg-id ::none)
     (aggregate reg-id (current-build-id))))
  ([reg-id build-id]
   (effective (get @state build-id empty-slice) reg-id ::none)))

(defn committed-aggregate
  "The build's LAST-KNOWN-GOOD aggregate for `reg-id`: the merge of the
  COMMITTED rows only, IGNORING the open pass's staging. Unlike `aggregate`
  (the effective view — committed-untouched PLUS staged), this reads solely
  the committed layer, which changes ONLY at a successful commit / finish
  boundary (`commit-build!` / `finish-build!`; `begin-build!` / `abort-build!`
  never touch it). So a read during an OPEN, FAILED, or interleaved pass
  returns the last finalized snapshot — never a partial mid-pass mix — and
  the value a consumer reads is finalized-at-the-successful-build-boundary by
  construction. Per-build-id like `aggregate`; on the no-pass REPL / plain-JVM
  path a contribution upserts straight into committed, so this reader sees it
  immediately. Pure."
  ([reg-id]
   (if-let [slice #?(:clj (ambient-accepted-slice) :cljs nil)]
     (reduce-kv (fn [m _src regs] (merge m (get regs reg-id)))
                {} (:committed slice))
     (committed-aggregate reg-id (current-build-id))))
  ([reg-id build-id]
   (reduce-kv (fn [m _src regs] (merge m (get regs reg-id)))
              {} (:committed (get @state build-id empty-slice)))))

#?(:clj
   (defn- shadow-pass-manifest
     "The prepare-harvested flat custom-element manifest of the OPEN Shadow build
     pass (`[scratch-key :element-manifest]`), or nil. Present only inside a real
     Shadow build pass the build hook opened; nil on a Shadow REPL overlay
     (no `:compile-prepare` harvest), plain JVM/SSR, and CLJS — those fall back to
     the per-source `elements` slice. The carrier's identity is validated
     (`validated-compiler-state`) before the manifest is observed."
     []
     (when-let [cs (validated-compiler-state)]
       (get-in cs [scratch-key :element-manifest]))))

(defn element-properties
  "The declared `:properties` set for custom-element `tag` in the ambient (or
  given) build's compile-time custom-element manifest — the compile-path read the
  template analyzer uses to classify a custom element's props (property vs
  attribute). Inside a real Shadow build pass it reads the prepare-time
  all-members harvest manifest (`shadow-pass-manifest`), which is a pure function
  of the build's source and independent of macro re-expansion; on the plain-JVM /
  SSR / REPL path it reads the per-source `elements` slice through the ambient
  build identity. Per-build either way, so one daemon's parallel builds never
  cross-classify; NEVER a process-global last-writer-wins mirror. Empty set when
  `tag` is undeclared in this build."
  ([tag]
   (if-let [m #?(:clj (shadow-pass-manifest) :cljs nil)]
     (get-in m [tag :properties] #{})
     (get-in (aggregate elements) [tag :properties] #{})))
  ([tag build-id] (get-in (aggregate elements build-id) [tag :properties] #{})))

(defn pass-open?
  "Whether a compile pass is currently open for the ambient (or given)
  build (tests / tooling)."
  ([]
   (if-let [slice #?(:clj (ambient-shadow-slice) :cljs nil)]
     (:pass-open? slice false)
     (pass-open? (current-build-id))))
  ([build-id] (:pass-open? (get @state build-id empty-slice) false)))

(defn shadow-compile?
  "Whether an OWNED Shadow compiler-env carrier is bound — a real Shadow
  build/watch/REPL compile, where the build hook's `:compile-prepare` harvest
  (or the REPL's own top-to-bottom order) governs custom-element seeding. False
  on the plain-JVM / SSR path, where no compiler env is bound and the harvest
  must lazily read the ambient namespace's own source instead (rf2-vxgfnd.141).
  Always false on CLJS (there is no compile-path host there)."
  []
  #?(:clj (boolean (validated-compiler-state)) :cljs false))

(defn shadow-build-pass?
  "Whether a real Shadow build PASS is bound — a `:compile-prepare`-opened scratch
  is present. Inside this window the prepare-time all-members custom-element
  harvest is authoritative and the `v/custom-element` macro is
  validation/reporting-only (it does not populate the compile-time registry).
  False on a Shadow REPL overlay (no scratch, no prepare harvest), plain JVM/SSR,
  and CLJS — where the macro's own contribution still populates the `elements`
  slice."
  []
  #?(:clj (boolean (when-let [cs (validated-compiler-state)]
                     (contains? cs scratch-key)))
     :cljs false))

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

(defn- update-current-slice!
  "Atomically apply `f` to the current compilation slice. A real Shadow pass
  mutates only compiler-env scratch. A no-pass Shadow REPL form mutates only a
  disposable overlay seeded from the accepted snapshot. Plain JVM/tests use
  the legacy fallback atom. Returns the updated slice."
  [f]
  #?(:clj
     (if-let [compiler-atom (when-not *build-id* (compiler-env-atom))]
       (if (ui-owned-compiler-state? @compiler-atom)
         (let [updated (atom nil)]
           (swap! compiler-atom
                  (fn [compiler-state]
                    ;; Fail-closed identity gate BEFORE any scratch/overlay
                    ;; mutation: a carrier whose build identity is missing or
                    ;; disagrees with a still-present Shadow bridge id must not
                    ;; be written (the same authority `validated-compiler-state`
                    ;; enforces on the read side).
                    (carrier-build-id compiler-state)
                    (let [k (if (contains? compiler-state scratch-key)
                              scratch-key
                              repl-overlay-key)
                          seed (or (get compiler-state k)
                                   (assoc empty-slice
                                          :committed
                                          (:registries
                                           (accepted-snapshot compiler-state))))
                          next (f seed)]
                      (reset! updated next)
                      (assoc compiler-state k next))))
           @updated)
         (let [build-id (current-build-id)
               updated (atom nil)]
           (swap! state update build-id
                  (fn [slice]
                    (let [next (f (or slice empty-slice))]
                      (reset! updated next)
                      next)))
           @updated))
       (let [build-id (current-build-id)
             updated (atom nil)]
         (swap! state update build-id
                (fn [slice]
                  (let [next (f (or slice empty-slice))]
                    (reset! updated next)
                    next)))
         @updated))
     :cljs
     (let [build-id (current-build-id)
           updated (atom nil)]
       (swap! state update build-id
              (fn [slice]
                (let [next (f (or slice empty-slice))]
                  (reset! updated next)
                  next)))
       @updated)))

(defn contribute!
  "Contribute `source`'s `k`->`v` to the plain registry `reg-id` in the
  ambient build — one `swap!`, pure. Collision-sensitive registries use
  `contribute-checked!` for their pre-write conflict check."
  [reg-id source k v]
  (update-current-slice! #(write-slice % reg-id source k v))
  nil)

(defn contribute-checked!
  "An atomic conflict-checked registry contribution. Inside ONE
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
    (update-current-slice!
     (fn [slice]
       (let [existing (get (effective slice reg-id source) k)
             conflict (conflict-fn existing)]
         (if conflict
           (do (reset! outcome {:conflict conflict}) slice)
           (do (reset! outcome nil)
               (write-slice slice reg-id source k v))))))
    @outcome))

(defn- element-conflict-row
  "Pure: one side of a custom-element contradiction, rendered as deterministic
  evidence. `build-id` + `ns` are the ruled `[build-id ns-sym]` anchor pair."
  [build-id source decl]
  {:build build-id
   :ns source
   :properties (:properties decl #{})})

(defn elements-conflict
  "Pure DEFENCE-IN-DEPTH detector: the first cross-source non-`rf=`-equal
  same-tag collision in a per-source `registries` map (`{source {reg-id {k v}}}`),
  or nil.

  `contribute-element-checked!` is the write BARRIER — it is the law, and it
  makes a conflicting row unable to enter a slice in the first place. This
  function re-derives the same verdict from finalized rows so that NO
  aggregation path can ever pick a winner by merge order, even if a row
  arrived by some route that bypassed the barrier. Sources are folded in
  sorted order and the losing pair is reported with BOTH anchors sorted the
  same way, so the evidence is identical under every source permutation."
  [build-id registries]
  (let [conflict (fn [tag owner prior source decl]
                   {::conflict
                    {:tag tag
                     :declarations
                     (vec (sort-by (juxt (comp str :build) (comp str :ns))
                                   [(element-conflict-row build-id owner prior)
                                    (element-conflict-row build-id source decl)]))}})
        step (fn [seen source]
               (reduce-kv
                (fn [seen tag decl]
                  (let [[owner prior] (get seen tag)]
                    (cond
                      (nil? owner)          (assoc seen tag [source decl])
                      (eq/rf= prior decl)   seen  ; duplicates co-exist
                      :else                 (reduced (conflict tag owner prior
                                                               source decl)))))
                seen
                (get-in registries [source elements])))
        outcome (reduce (fn [seen source]
                          (let [next (step seen source)]
                            (if (::conflict next) (reduced next) next)))
                        {}
                        (sort-by str (keys registries)))]
    (::conflict outcome)))

(defn- stage-element-checked
  "Pure: admit `source`'s `tag` -> `decl` into the per-source `slice` under the
  ruled cross-source conflict law, returning `[slice' conflict|nil]` — the write
  barrier the plain-JVM / SSR / REPL `contribute-element-checked!` uses (the
  Shadow build path re-derives the same verdict wholesale in `element-manifest`).
  An `rf=`-equal duplicate is idempotent; a contradiction from another live
  source (or a second contradictory declaration in the same open pass of one ns)
  is refused without writing, leaving the last-known-good slice untouched."
  [slice source tag decl]
  (let [other-decl (get (effective slice elements source) tag)
        other-owner (get (effective slice element-declarations source) tag)
        ;; Own STAGED row only: a second, contradictory declaration of the same
        ;; tag in the same pass of the same ns. Committed/no-pass rows are the
        ;; source replacing itself and must stay admissible.
        own-decl (when (:pass-open? slice)
                   (get-in slice [:staged source elements tag]))
        conflict (cond
                   (and (some? other-decl) (not (eq/rf= other-decl decl)))
                   {:owner other-owner :declaration other-decl}

                   (and (some? own-decl) (not (eq/rf= own-decl decl)))
                   {:owner source :declaration own-decl}

                   :else nil)]
    (if conflict
      [slice conflict]
      [(-> slice
           (write-slice element-declarations source tag source)
           (write-slice elements source tag decl))
       nil])))

(defn contribute-element-checked!
  "Atomically contribute ONE custom-element declaration under the ruled
  cross-source conflict law (rf2-vxgfnd.143, delegated ruling 2026-07-15
  Option A — fail atomically; never a merge/winner law).

  Inside ONE `swap!`, `source`'s `tag` -> `decl` is admitted iff every OTHER
  live source's declaration of `tag` is `rf=`-equal to it. `rf=`-equal
  duplicates co-exist (idempotent — two namespaces may legitimately state the
  same fact); a non-`rf=`-equal declaration from a DIFFERENT source is a
  contradiction and is REJECTED without writing, so the losing row never
  enters the ledger and the last-known-good aggregate is left untouched.

  A source never conflicts with ITSELF across passes: during an open pass the
  source's COMMITTED rows are excluded (it is being replaced wholesale), and
  on the no-pass REPL path a re-evaluated declaration simply replaces its own
  prior row. Its STAGED rows are NOT excluded, so two contradictory
  declarations of one tag inside a single compile of one namespace do fail —
  those are two live declarations, not a replacement.

  Owner provenance is written in the SAME `swap!` as the declaration, so the
  rejected side can always be anchored to BOTH `[build-id ns-sym]` pairs.
  Returns nil on success, or `{:conflict {:owner <ns> :declaration <decl>}}`
  without writing."
  [source tag decl]
  (let [outcome (atom nil)]
    (update-current-slice!
     (fn [slice]
       (let [[slice' conflict] (stage-element-checked slice source tag decl)]
         (reset! outcome (when conflict {:conflict conflict}))
         slice')))
    @outcome))

(defn- seeded->registries
  "Pure: group harvested `[source tag decl]` triples into the per-source shape
  `elements-conflict` folds — `{source {elements {tag decl}}}` — or
  `{::conflict …}` when two non-`rf=`-equal declarations of one tag arrive from
  the SAME source in ONE harvest. Grouping itself must not pick a winner:
  `elements-conflict` compares finalized per-source rows, so a same-source
  contradiction would otherwise collapse last-wins before the law ever ran
  (PR #6646 audit rider). Two contradictory declarations in one pass of one
  namespace are two LIVE declarations — the same law violation
  `contribute-element-checked!` refuses via its staged rows on the plain-JVM
  path — not a source replacing itself across passes: each prepare harvest is
  independent, so an ordinary edit between builds still replaces cleanly.
  `rf=`-equal duplicates fold idempotently."
  [build-id seeded]
  (reduce (fn [m [source tag decl]]
            (let [prior (get-in m [source elements tag])]
              (cond
                (nil? prior)        (assoc-in m [source elements tag] decl)
                (eq/rf= prior decl) m
                :else
                (reduced
                 {::conflict
                  {:tag tag
                   :declarations
                   (vec (sort-by (juxt (comp str :build) (comp str :ns))
                                 [(element-conflict-row build-id source prior)
                                  (element-conflict-row build-id source decl)]))}}))))
          {} seeded))

(defn element-manifest
  "Pure: fold harvested `[source tag decl]` triples into the flat all-members
  custom-element manifest `{tag {:properties #{…}}}`. The SAME conflict law as
  `contribute-element-checked!` governs admission — a non-`rf=` same-tag
  declaration from a DIFFERENT source, or a second non-`rf=` declaration of one
  tag inside the SAME source's single harvest (PR #6646 audit rider), is a
  contradiction and THROWS (a `:compile-prepare` error is a build-time error),
  so the manifest never carries a merge-order or source-order winner;
  `rf=`-equal duplicates fold idempotently. `build-id` anchors the thrown
  evidence."
  [build-id seeded]
  (let [grouped (seeded->registries build-id seeded)]
    (when-let [c (or (::conflict grouped)
                     (elements-conflict build-id grouped))]
      (throw
       (ex-info
        (str "re-frame.freehand found contradictory custom-element declarations for "
             (:tag c) " while harvesting the build's declarations; refusing to "
             "publish a merge-order winner")
        {::error ::custom-element-conflict
         :build-id build-id
         :recovery :reconcile-custom-element-declarations
         :tag (:tag c)
         :declarations (:declarations c)}))))
  (reduce (fn [m [_source tag decl]]
            (assoc m tag {:properties (:properties decl #{})}))
          {} seeded))

(defn set-shadow-element-manifest
  "Purely store the prepare-harvested all-members custom-element `manifest`
  (`{tag {:properties #{…}}}`, built by `element-manifest`) into `build-state`'s
  open scratch pass, BEFORE any view analyzes — the order-independence +
  macro-independence seam the build hook uses at `:compile-prepare`
  (rf2-vxgfnd.141 dim 2, rf2-u53yy.1 S1). The manifest is a pure function of the
  build's SOURCE, held beside the pass rather than in its per-source `:touched`
  ledger, so harvesting a warm source's declarations never evicts its committed
  views. A pure build-state transform (`cljs.env/*compiler*` is not bound during
  the hook); when no scratch pass is open the build-state is returned unchanged."
  [build-state manifest]
  (if (get-in build-state [:compiler-env scratch-key])
    (assoc-in build-state [:compiler-env scratch-key :element-manifest] manifest)
    build-state))

;; ---------------------------------------------------------------------------
;; View descriptor carrier (rf2-u53yy.1 S2)
;;
;; On the Shadow build path the whole-build `views` / `view-declarations`
;; registries are NOT populated by the `defview` macro's per-source slice
;; contribution — a warm cache-hit source never re-runs that macro. Instead each
;; compiled view stamps its descriptor onto the generated def's analyzer metadata
;; (the emitter's var-meta: `:rf.ui/view-id` + `:rf.ui/view-digest`). Shadow persists
;; a compiled namespace's analyzer data to its disk cache and RESTORES it under
;; `[:compiler-env :cljs.analyzer/namespaces <ns>]` on a cache HIT (the S0 proof,
;; rf2-u53yy.1.1: exact-EDN round-trip across shadow 3.4.0/3.4.10/3.4.11). At
;; compile-finish the hook folds the descriptor of EVERY authoritative member —
;; cached and compiled alike — into the two registries, so a warm source's views
;; are restored from the cache-durable analyzer map, not re-stamped by a macro.
;; This is the same decoupling S1 gave elements, at compile-finish (where views
;; resolve) rather than compile-prepare (elements are the ordering exception).
;; ---------------------------------------------------------------------------

(defn- analyzer-view-defs
  "PURE: `[view-id declaration digest]` triples for the compiled views a
  namespace's restored/fresh analyzer `:defs` carries. A def is a view iff its
  metadata carries BOTH `:rf.ui/view-id` and `:rf.ui/view-digest` — a bare
  `(declare ^:rf.ui/view …)` forward declaration carries neither, and the
  `$render`/`$host_render` helpers carry no view metadata at all. `declaration`
  is the ruled `[ns var]` pair; `digest` is the `[template-fingerprint
  hook-signature]` vector the emitter stamped (the same value the macro slice
  path contributes off the Shadow path)."
  [ns-sym ns-analyzer-map]
  (keep (fn [[var-sym def-map]]
          (let [m (:meta def-map)
                view-id (:rf.ui/view-id m)
                digest (:rf.ui/view-digest m)]
            (when (and view-id digest)
              [view-id [ns-sym var-sym] digest])))
        (:defs ns-analyzer-map)))

(defn harvest-view-registries
  "PURE: fold the analyzer-map view descriptors of every authoritative `members`
  namespace in `build-state` into per-source registry rows
  `{source {::views {view-id digest} ::view-declarations {view-id declaration}}}`.
  Reads `[:compiler-env :cljs.analyzer/namespaces <ns>]` — Shadow's
  disk-cache-durable carrier (rf2-u53yy.1 S2), present identically for a cache-hit
  member and a freshly compiled one. The cross-source view-id uniqueness law runs
  HERE (a compile-finish error is still a build-time error): two DISTINCT
  declarations claiming one view-id THROW rather than letting merge order pick a
  winner. The same declaration cannot appear twice — one def, one `:defs` entry —
  so an `rf=`-style duplicate cannot arise. Members are folded in sorted order so
  the reported collision evidence is identical under every graph permutation."
  [build-state members]
  (let [nss (get-in build-state [:compiler-env :cljs.analyzer/namespaces])
        seeded (mapcat (fn [ns-sym]
                         (analyzer-view-defs ns-sym (get nss ns-sym)))
                       (sort-by str members))
        conflict (reduce
                  (fn [owners [view-id declaration _digest]]
                    (let [prior (get owners view-id)]
                      (if (and prior (not= prior declaration))
                        (reduced
                         {::conflict
                          {:view-id view-id
                           :declarations (vec (sort-by str [prior declaration]))}})
                        (assoc owners view-id declaration))))
                  {}
                  seeded)]
    (when-let [c (::conflict conflict)]
      (throw
       (ex-info
        (str "re-frame.freehand found two distinct defview declarations claiming view "
             "id " (:view-id c) " while harvesting the build's compiled views; "
             "refusing to publish a merge-order winner. Give each view a distinct "
             "qualified keyword (re-expanding the exact same var remains the HMR "
             "replacement path)")
        {::error ::view-id-conflict
         :view-id (:view-id c)
         :declarations (:declarations c)
         :recovery :reconcile-defview-declarations})))
    (reduce (fn [regs [view-id declaration digest]]
              (let [source (first declaration)]
                (-> regs
                    (assoc-in [source views view-id] digest)
                    (assoc-in [source view-declarations view-id] declaration))))
            {}
            seeded)))

(defn- merge-registries
  "PURE: deep-merge two `{source {reg-id {k v}}}` registry maps; the RIGHT map's
  rows win per `(source, reg-id, k)`. Used to overlay the analyzer-map-harvested
  view rows onto the slice-derived rows of the other registries."
  [a b]
  (merge-with (fn [ra rb] (merge-with merge ra rb)) a b))

;; ---------------------------------------------------------------------------
;; Root-site carrier (rf2-u53yy.1 S4)
;;
;; Root sites are CALL SITES (`v/render-static`), not defs, so unlike views (S2)
;; they carry NO generated def whose analyzer var-meta could hold their descriptor.
;; On the Shadow build path each site is instead stamped onto a SYNTHETIC
;; per-namespace descriptor in the analyzer namespace map (variant B, S0 proof
;; rf2-u53yy.1.1): a single ns-level key
;; `[::namespaces <ns> :rf.ui/root-plan-descriptor]` accumulating this namespace's
;; `{:roots [..]}` sites. Shadow persists the whole ns analyzer entry to its disk
;; cache and RESTORES it under `[:compiler-env :cljs.analyzer/namespaces <ns>]` on a
;; cache HIT, so the build hook harvests the roots registry from it at
;; compile-finish — present identically for a cached member and a freshly compiled
;; one — instead of from a macro-expansion side effect a warm cache-hit source never
;; re-runs. Eviction is automatic and identical to S2's def-meta carrier: Shadow's
;; watch reset dissocs the WHOLE `[::namespaces <ns>]` entry for a modified source
;; before it recompiles (`remove-output-by-id`, default `:watch-namespace-reset`),
;; so a removed site simply vanishes from the re-analyzed carrier. This is the same
;; decoupling S1 gave elements and S2 gave views.
;;
;; The `:plans`/`:descriptors` sub-keys this carrier once also held (S5) retired
;; with the compiled-mount door (rf2-12p25): `register-plan-site!` /
;; `register-descriptor!` were their only writers, so this carrier keeps the
;; `:roots` path only (rf2-kl2pq).
;; ---------------------------------------------------------------------------

(def ^{:private true
       :doc "The SYNTHETIC per-namespace Layer-1 carrier key in the analyzer
  namespace map (rf2-u53yy.1 S4). Namespaced + analyzer-only: it is compile-time
  build state, never emitted into any bundle."}
  root-plan-descriptor-key
  :rf.ui/root-plan-descriptor)

(defn stamp-root-plan-site!
  "Accumulate one Layer-1 root `site` under `sub-key` — always `:roots`, the sole
  Freehand-door call-site registry with a live writer. (The `:plans`/`:descriptors`
  sub-keys this carrier once also accepted retired with the compiled-mount door,
  rf2-12p25/rf2-kl2pq; only the `:roots` path survives.) The site is stamped into
  the live analyzer map's SYNTHETIC per-namespace descriptor for `ns-sym` — the S4
  variant-B carrier. Called at macro expansion on a real Shadow build pass
  (`register-root-site!` gates on `shadow-build-pass?`) in place of the per-source
  slice contribution: the whole-build root-site registry is then harvested from this
  carrier at compile-finish, present identically for a cache-hit member and a
  freshly compiled one. A `:roots` site is
  `{:root-id .. :row {:file .. :line .. :provenance ..}}` (the same row the slice
  path contributes off the Shadow path). A direct `swap!` on the per-thread compiler
  env (parallel builds each own theirs); a no-op when no compiler env is bound
  (never reached — the caller gates on a live Shadow pass)."
  [ns-sym sub-key site]
  #?(:clj (when-let [a (compiler-env-atom)]
            (swap! a update-in
                   [:cljs.analyzer/namespaces ns-sym root-plan-descriptor-key sub-key]
                   (fnil conj []) site))
     :cljs nil)
  nil)

(defn- site-coords
  "PURE: the `{:file :line}` locator of a stamped Layer-1 row, for conflict
  evidence."
  [row]
  (select-keys row [:file :line]))

(defn- site-str
  "PURE: a stamped Layer-1 row's `file:line` locator rendered for the human
  diagnostic sentence — the same rendering `re-frame.freehand.compiler.root/register-root-site!`
  uses off the build-pass path, so both compiler doors read one message shape."
  [{:keys [file line]}]
  (str (or file "<unknown-file>") (when line (str ":" line))))

(defn harvest-root-plan-registries
  "PURE: fold the SYNTHETIC analyzer-map root sites of every authoritative
  `members` namespace in `build-state` into per-source registry rows
  `{source {::roots {root-id row}}}`. Reads
  `[:compiler-env :cljs.analyzer/namespaces <ns> :rf.ui/root-plan-descriptor]` —
  Shadow's disk-cache-durable variant-B carrier (rf2-u53yy.1 S4), present
  identically for a cache-hit member and a freshly compiled one. Same-namespace
  re-declaration replaces (a source's own later site wins — watch/HMR tolerance);
  the cross-NAMESPACE Layer-1 root-id law runs HERE (a compile-finish error is still
  a build-time error): two namespaces resolving one root-id throw the canonical
  `:rf.error/duplicate-root-id` through the shared error builder — the SAME public
  discriminator the off-build-pass door
  (`re-frame.freehand.compiler.root/register-root-site!`) raises, so one public
  collision has one catalogued identity regardless of build mode. Members are folded
  in sorted order so the reported evidence is stable under every graph permutation.
  Empty (no root sites in the build) yields `{}`, so the finish overlay is a no-op
  for a mount-free build."
  [build-state members]
  (let [nss (get-in build-state [:compiler-env :cljs.analyzer/namespaces])]
    (loop [srcs        (sort-by str members)
           regs        {}
           root-owners {}]               ; root-id -> {:source ns :row row}
      (if-let [ns-sym (first srcs)]
        (let [d         (get (get nss ns-sym) root-plan-descriptor-key)
              ;; per-source fold: a namespace's own later site replaces its earlier
              ;; one for the same root-id (same-ns re-declaration tolerance).
              src-roots (reduce (fn [m {:keys [root-id row]}] (assoc m root-id row))
                                {} (:roots d))]
          (doseq [[root-id row] src-roots]
            (when-let [{owner-row :row} (get root-owners root-id)]
              ;; Canonical `:rf.error/duplicate-root-id` through the shared error
              ;; builder — the one small consistent projection that makes this real
              ;; Shadow compile-finish door carry the SAME public discriminator (and
              ;; the same message/`:extra` shape) the off-build-pass door
              ;; (`register-root-site!`) raises.
              (error/throw-error!
               :rf.error/duplicate-root-id 'v/render-static
               (str "two root sites in one build resolve to root-id "
                    (pr-str root-id) " — " (site-str owner-row) " and "
                    (site-str row) ". Root-ids are page-unique identity; "
                    (if (= :derived (:provenance owner-row) (:provenance row))
                      (str "both ids derived from the same view — add "
                           ":disambiguator or author :root-id")
                      "author distinct :root-id values"))
               {:recovery :make-root-ids-unique
                :extra {:root-id    root-id
                        :provenance [(:provenance owner-row) (:provenance row)]
                        :sites      (vec (sort-by str [(site-coords owner-row)
                                                       (site-coords row)]))}})))
          (recur (rest srcs)
                 (cond-> regs
                   (seq src-roots) (assoc-in [ns-sym roots] src-roots))
                 (into root-owners
                       (map (fn [[rid row]] [rid {:source ns-sym :row row}]))
                       src-roots)))
        regs))))

;; ---------------------------------------------------------------------------
;; Pass boundaries
;; ---------------------------------------------------------------------------

(defn begin-build!
  "Open a compile pass for `build-id` (the zero-arg form uses the sole
  default build). DISCARDS any staging a failed prior pass left — a
  successful pass commits at its close, so there is normally none — and marks
  the pass open. `authoritative-members`, when supplied by the build hook from
  Shadow's already-resolved graph, is open-pass conflict context: a committed
  declaration owned by a nonmember cannot block its current replacement, but
  stays last-known-good until successful finish. Sets the session-build
  identity fallback so subsequent contributions on the REPL / plain-JVM path
  route here."
  ([] (begin-build! ::default nil))
  ([build-id] (begin-build! build-id nil))
  ([build-id authoritative-members]
   (reset! session-build build-id)
   (swap! state update build-id
          (fn [s]
            (assoc (or s empty-slice)
                   :pass-open? true
                   :authoritative-members (when (some? authoritative-members)
                                            (set authoritative-members))
                   :staged {}
                   :touched #{})))
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
    (assoc slice
           :committed committed
           :staged {}
           :touched #{}
           :pass-open? false
           :authoritative-members nil)))

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

(defn- committed-rows
  "Pure: one finalized slice's COMMITTED rows for `reg-id`, merged across
  sources (the same fold `committed-aggregate` performs, over an explicit
  slice)."
  [slice reg-id]
  (reduce-kv (fn [m _src regs] (merge m (get regs reg-id)))
             {} (:committed slice)))

(defn prepare-shadow-build
  "Purely open a disposable pass in `build-state` from its incoming accepted
  snapshot. Dirty scratch from an abandoned compile and every no-pass REPL
  overlay are overwritten/cleared. `recompiled-members` is Shadow's exact
  compile schedule at `:compile-prepare`: those sources are pre-touched so a
  successful recompile which removes its final re-frame.freehand declaration evicts
  the accepted row even though no registry macro runs. Output-present cache
  hits are not pre-touched and retain their accepted rows. The returned
  build-state is the sole place this pass exists; no external last-known-good
  state changes."
  [build-state build-id members recompiled-members]
  (let [accepted (accepted-snapshot build-state)
        scratch (assoc empty-slice
                       :pass-open? true
                       :authoritative-members (set members)
                       :committed (:registries accepted)
                       :touched (set recompiled-members))]
    (-> build-state
        (assoc-in [:compiler-env accepted-snapshot-key]
                  (assoc accepted :build-id build-id))
        (assoc-in [:compiler-env scratch-key] scratch)
        (update :compiler-env dissoc repl-overlay-key))))

(defn shadow-finish-candidate
  "Derive, but do not externally publish, the successful compiler candidate
  from `build-state`'s disposable scratch. The snapshot version advances once
  per finalized pass.

  Before deriving anything, the supplied `build-id` is validated against
  re-frame.freehand's OWN accepted private carrier and any still-present Shadow bridge
  id via `carrier-build-id` — the same fail-closed identity every ambient
  read/write traverses. A carrier for build :A whose id disagrees with a
  supplied :B (or with a still-present Shadow bridge :B) therefore cannot
  finalize into a :B-stamped snapshot carrying A's registry rows: it fails
  before a candidate is derived."
  [build-state build-id members]
  ;; Fail-closed identity gate (JVM build-time only): the supplied build-id must
  ;; agree with the accepted private carrier and any still-present Shadow bridge
  ;; id before a candidate is derived.
  #?(:clj
     (when (ui-owned-compiler-state? (:compiler-env build-state))
       (let [resolved (carrier-build-id (:compiler-env build-state))]
         (when (and (some? resolved) (not= resolved build-id))
           (throw
            (ex-info
             (str "re-frame.freehand compile-finish supplied build id " build-id
                  " disagrees with its accepted private build carrier id "
                  resolved "; refusing to finalize a cross-build candidate")
             {::error ::private-build-id-shadow-disagreement
              :recovery :check-ui-build-carrier
              :private-build-id resolved
              :shadow-build-id build-id}))))))
  (let [accepted (accepted-snapshot build-state)
        scratch (get-in build-state [:compiler-env scratch-key])]
    (when-not (and scratch (:pass-open? scratch))
      (throw
       (ex-info
        "re-frame.freehand compile-finish had no matching compiler-env scratch"
        {::error ::missing-shadow-scratch
         :build-id build-id
         :recovery :configure-ui-build-hook-once})))
    ;; Custom elements are NOT in the committed view-slice on this path
    ;; (rf2-u53yy.1 S1): the manifest was harvested wholesale at
    ;; `:compile-prepare` (all-members, macro-independent) and its cross-source
    ;; conflict law was enforced there. Carry that finalized manifest onto the
    ;; snapshot beside `:registries`.
    (let [after (-> scratch commit-slice (keep-members (set members)))
          ;; Views ride the disk-cache-durable analyzer-map carrier on the Shadow
          ;; path (rf2-u53yy.1 S2). The defview macro contributes NO view row to
          ;; the slice under a Shadow build pass, so harvest every authoritative
          ;; member's compiled view descriptors from the analyzer map and overlay
          ;; them onto the slice-derived rows of the other registries. A cache-hit
          ;; member contributes its RESTORED descriptor identically to a freshly
          ;; compiled one, so a warm source's views survive without the macro
          ;; re-running. (Off the Shadow path — plain-JVM/REPL/tests staging views
          ;; directly through the slice — the analyzer map is empty and the slice
          ;; rows carry through unchanged.)
          view-registries (harvest-view-registries build-state members)
          ;; Root sites ride the disk-cache-durable SYNTHETIC per-namespace
          ;; analyzer-map descriptor on the Shadow path (rf2-u53yy.1 S4). The
          ;; register-root-site! macro contributes NO slice row under a Shadow build
          ;; pass, so harvest every authoritative member's root sites from the carrier
          ;; and overlay them onto the slice-derived rows of the other registries — a
          ;; cache-hit member contributes its RESTORED sites identically to a freshly
          ;; compiled one. The cross-namespace Layer-1 root-id law runs inside the
          ;; harvest. (Off the Shadow path the analyzer carrier is empty and the slice
          ;; rows carry through unchanged.)
          root-registries (harvest-root-plan-registries build-state members)
          snapshot {:build-id build-id
                    :registries (-> (:committed after)
                                    (merge-registries view-registries)
                                    (merge-registries root-registries))
                    :elements (:element-manifest scratch {})
                    :version (inc (long (or (:version accepted) 0)))}]
      {:snapshot snapshot})))

(defn carry-shadow-candidate
  "Purely associate `candidate` into the returned Shadow build-state and drop
  disposable scratch/REPL overlay. Shadow retaining this returned state after
  all later stages is the commit; a downstream failure discards it."
  [build-state {:keys [snapshot]}]
  (-> build-state
      (assoc-in [:compiler-env accepted-snapshot-key] snapshot)
      (update :compiler-env dissoc scratch-key repl-overlay-key)))

(defn finish-candidate
  "Purely derive `build-id`'s successful whole-build finish without publishing
  it. Returns an opaque candidate containing the observed slice and the
  finalized slice; the Shadow hook then calls `commit-finish-candidate!`."
  [build-id members]
  (let [before (get @state build-id empty-slice)
        after  (-> before commit-slice (keep-members (set members)))]
    {:build-id build-id
     :before before
     :after after}))

(defn commit-finish-candidate!
  "Publish a previously derived finish candidate iff its build slice has not
  changed. A projection failure never calls this function, so compiler state
  remains last-known-good. Unrelated build ids may advance concurrently; the
  compare is scoped to this candidate's explicit build id."
  [{:keys [build-id before after]}]
  (swap! state
         (fn [all]
           (let [current (get all build-id empty-slice)]
             (when-not (= before current)
               (throw
                (ex-info
                 "re-frame.freehand finish candidate became stale before commit"
                 {::error ::stale-finish-candidate
                  :build-id build-id})))
             (assoc all build-id after))))
  nil)

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
          (fn [s]
            (assoc (or s empty-slice)
                   :staged {}
                   :touched #{}
                   :pass-open? false
                   :authoritative-members nil)))
   nil))

(defn reset-build!
  "Hard-clear every build slice — the clean-build / independent-build /
  test-isolation boundary. `begin-build!`'s per-source replace covers
  incremental correctness; this is the fresh top-level state."
  []
  (reset! state {})
  (reset! session-build ::default)
  nil)
