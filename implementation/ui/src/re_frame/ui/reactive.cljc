(ns re-frame.ui.reactive
  "S2b reactive core of the compiled-view substrate — the ViewCell, the
  render-side probe/record protocol, the 8-step layout-commit reconciler,
  and the three-state lifecycle. Host-agnostic (`.cljc`): the React glue
  that drives it lives in `re-frame.ui.viewcell` (`.cljs`), but every
  ownership decision — kept-check, transactional stage/rollback, evidence
  comparison, publish/release ordering, lifecycle facts — is here, so the
  whole reconciler is graft-checked headlessly against the REAL observation
  port on both hosts (node + JVM, plain-atom adapter).

  Sole reactive consumer of the internal observation port
  (`re-frame.substrate.observation`) — the six operations
  `resolve-target` / `probe` / `acquire!` / `current?` / `read` /
  `release!` (Spec 006 §The internal observation port). Per the S2a
  handoff: `read` returns `:frame-epoch` / `:registry-epoch` ADDITIVELY
  (no second probe at commit step 5); `resolve-target`'s `site-ctx` shape
  is `{:query-v … :frame pin? :override {:value :override-id :version}?}`;
  the value-movement `on-change` watch channel exists only on watchable
  hosts, so on a headless (non-watchable) host movement is caught at the
  commit evidence comparison (step 5), not by a callback — for EVERY acquired
  lease, RETAINED as well as staged (rf2-vxgfnd.39), since a retained site has
  no watch to self-correct there.

  ## The ViewCell (03 §2)

  Every lexical `(sub …)` in a view is a compile-indexed site; all of a
  view's sites share ONE ViewCell — one `useSyncExternalStore`, one scalar
  revision snapshot, one coalesced notification per render batch (the
  drain-quiescence boundary, NOT per epoch — see §Drain coalescing below).
  Render probes WITHOUT
  ownership (resolve-target + probe, no ref-count / watch / cache node);
  the layout commit acquires the CAPTURED targets. Abandoned renders
  (StrictMode double-render, time-sliced tear-off) acquire NO OWNERSHIP —
  the 10k-abandoned-renders-retain-zero-OWNERSHIP property is structural,
  mirroring the port's S-3 §5 cold-probe exit criterion. (They do retain
  their single `:latest-capture` of values until the next render overwrites
  it — at most one, never accumulating; see `with-capture`.)

  ## The render capture

  A render pass records each executed site's resolved target + probe
  evidence + value into a per-pass CAPTURE (ownership-free). Sites dedup by
  target identity, so N reads of one query share one committed lease and a
  shared node can never fall through its zero-owner disposal edge. The
  latest finished capture is stashed on the cell; the layout commit
  reconciles the committed dependency set against it — idempotently, so
  StrictMode's mount→unmount→mount effect replay is naturally balanced.

  ## `sub` value stabilization (03 §2, I-8)

  A site returns the PRIOR EXACT value (identical reference) when the new
  read is `rf=` to the last committed value for its target — so an
  `rf=`-stable read does not repaint downstream. Stabilization here is
  keyed by target identity `(frame, stabilized query)`; per-site query-
  object reuse (the parametric-args case) needs compile-site identity and
  lands with the HMR site-identity slice (S2e).

  ## Drain coalescing + `flush!` scope (S2d — 03 §3 invariant 6; Spec 006
  §Epoch finalization)

  The sixth frozen invariant, stated correctly: THE RENDER BATCH BOUNDARY
  IS DRAIN QUIESCENCE, NOT EPOCH CLOSE. An event/frame EPOCH is a
  write-side commit + diagnostic-evidence unit (one per dequeued event —
  Spec 002 §Drain versus event); it is NOT a React render boundary. A
  single run-to-completion drain may settle SEVERAL queued events, each
  committing its OWN epoch record, before the host regains control — and
  every one of those epochs coalesces into ONE render batch.

  The mechanism: sub deltas mark their cell dirty through constant-work
  `on-change` (never compute — I-5), carrying the moving frame's epoch as
  CAUSE EVIDENCE only. The cell enters a module-level DIRTY REGISTRY
  exactly once (a set, deduped by cell identity); a re-mark while already
  pending FOLDS IN regardless of its epoch tag — the pending flag is the
  coalescing key, the epoch tag is NEVER a second key. On CLJS one
  coalesced flush is armed per drain on the host MICROTASK queue
  (`queue-microtask!` — `js/queueMicrotask`, NOT `goog.async.nextTick`,
  which is a macrotask): the microtask checkpoint runs after the
  synchronous run-to-completion drain unwinds and BEFORE the next paint, so
  the flush fires strictly after drain quiescence — never between two queued
  events of the same drain, and always before a torn frame can show
  (rf2-vxgfnd.40); the JVM headless host has no async render loop, so it
  auto-schedules NOTHING and
  drains via the EXPLICIT `flush!` (07 §2's only flush idiom; SSR is
  one-shot) — one honest option per host. Either way, N epochs committed
  in one drain advance each dirty cell's revision ONCE and let React
  perform ONE read/render batch.

  Render SEPARATION is therefore per DRAIN, not per epoch: two epochs
  settled in one drain share one render batch; two epochs settled in
  SEPARATE drains (distinct external events, the host regaining control
  between them) render separately — NO render count may be inferred from
  the number of event/frame epochs. The push-economics bench's queued-
  cascade gate (a parent event that queues further events, proving one
  ViewCell notification and one React render for the whole batch —
  G-5/G-13) is wired with the bench in S2f, not here.

  `flush!` — the SYNCHRONOUS forcing of pending notifications — is SCOPED
  over that registry. The Q51 scope ruling, PINNED here:

    - `flush!` is PER-ROOT at the public boundary: it flushes the CALLING
      root's pending commit work. The substrate primitive is a scoped
      drain (`flush-scope!`), and the natural scope the substrate owns is
      the FRAME — a dirty cell's pending work belongs to the frame(s) its
      committed sites observe. Root scope COMPOSES over frame scope (a
      root scopes ≥1 frame); the root→frames resolution lives in the
      client root registry (where roots live — off this artefact's
      surface), so the ROOT-spelled public `ui/flush!` / `flush-render!`
      wires there (S2f). The substrate mechanism + the frame-scoped and
      global spellings land HERE.
    - `(flush-frame! frame-id)` — the frame arity — flushes every root
      observing that frame (each dirty cell whose committed deps include
      the frame).
    - the GLOBAL all-roots flush is the TEST-ONLY `ui.test/flush!`
      spelling (`flush-pending!` here). There is no app-facing global
      flush — an app forces its own root, never every root.

  A scoped flush leaves out-of-scope cells pending — no epoch work leaks
  across roots. Flush is reentrancy-SAFE BY CONSTRUCTION: `flush-scope!`
  atomically drains-then-notifies (`swap-vals!`), so a notify-triggered
  re-entrant flush finds the registry already drained and cannot
  double-advance a cell. The DEV-tier `:rf.error/flush-in-open-epoch`
  signal — the DX guard naming a re-entrant flushSync-into-an-open-epoch
  misuse (03 §11; Spec 006 §Epoch finalization) — is REFERENCED, not
  emitted, here: its typed throw lands with its Spec 009 catalogue row in
  the S2f 009 batch (the catalogue↔throw co-edit ratchet couples them,
  and the reentrancy it guards is only reachable once mounted Tier-3
  roots land).

  ## The slice-scoped probe memo (S2d item 3 — 03 §3; Spec 006 §The
  slice-scoped probe memo)

  `sub-read` threads a SLICE-SCOPED pure memo (`obs/make-slice-memo`)
  into every `probe`, so N sibling rows probing one query compute shared
  derivation parents once per synchronous execution slice, not once per
  row (the first-mount fan-out mitigation). The handle is created lazily
  on the first probe of a slice and cleared on the next event-loop tick
  (`interop/next-tick` — a MACROTASK, GC hygiene only, not a
  correctness-before-paint boundary), so an abandoned slice's table is
  unreachable garbage. The memo is an ECONOMY, never an authority — the commit
  evidence comparison (step 5) corrects any staleness before paint."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.observation :as obs]
            [re-frame.subs.override-schema :as override-schema]
            [re-frame.ui.eq :as eq]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Observation-port ABI lockstep (Spec 006 §The internal observation port)
;;
;; This ns is the SOLE reactive consumer of the internal observation port, and
;; from ABI v2 onward it RELIES on the `read` evidence axis `:node-key` — the
;; reincarnation-identity `evidence-moved?` consumes below (rf2-vxgfnd.14/.93).
;; So it PINS the ABI it compiled against and asserts it AT LOAD: a core that
;; predates the `:node-key` read axis is a BOOT ERROR
;; (`:rf.error/observation-port-version-mismatch`, always-on + fanned through
;; the production error-emit axis), never a silently-missed reincarnation
;; correction. Core and re-frame2-ui release on a lockstep train.
;; ---------------------------------------------------------------------------

(def ^:const expected-observation-port-abi
  "The observation-port ABI version this reactive consumer is written against —
  v2 (rf2-vxgfnd.14): `read` on a node lease carries `:node-key`, which
  `evidence-moved?` compares to classify a same-id frame REINCARNATION across
  the render→commit gap as MOVEMENT even when node-version + frame/registry
  epochs coincide. Asserted against the live port at load
  (`assert-port-abi-version!`) so artifact drift fails loud at boot."
  2)

(obs/assert-port-abi-version! expected-observation-port-abi)

;; ---------------------------------------------------------------------------
;; The static override door (03 §3)
;;
;; `resolve-target` consumes a Story-override HIT off the site-ctx. On the
;; JVM there is no React context: `ui.test/render` binds this door
;; explicitly (`{:sub-overrides {query value}}`) — "one honest option, not
;; a pretended same mechanism" (07 §2). On CLJS the dev/full Story skeleton
;; will seed the same door from the public override context with ordinary
;; `useContext` (that React-context wiring is S2f; the static-override LEASE
;; path below is landed + fixtured here so S2f does not duplicate it).
;; ---------------------------------------------------------------------------

(def ^:dynamic *sub-overrides*
  "Override door: a map of query-vector → pinned value, or nil. A HIT
  resolves the site to a `:story-override` target — the pinned value IS the
  resolution (no node), and commit acquires a STATIC lease
  (`:owned? false`, callback-free). Seeded by the JVM `ui.test/render`
  door (an explicit `binding`) and, on CLJS, by the Story skeleton off the
  public override context (that React-context→var seeding is S2f).

  ## The change token (opaque to the observation port — the target ABI)

  `resolve-override` LOWERS a HIT to the port's opaque
  `{:override-id :version}` change token (see
  `re-frame.substrate.observation`, which compares both by `=` only and
  never interprets them). This artefact's private lowering:

    - `:override-id` ← the QUERY — the override's stable slot identity, so
      two overrides never collide and a site dedups by its slot.
    - `:version`     ← the VALIDATED value — the movement token, so
      `current?` (a `=` on version) retargets EXACTLY when the surfaced
      value moves.

  Because `resolve-override` re-runs (and re-validates) every render, the
  semantics fall out of the token:

    - equal-value provider replacement → same validated value → version
      unchanged → the kept-check retains the static lease (no retarget).
    - nested providers → the CLOSEST enclosing override wins (the innermost
      `*sub-overrides*` binding / React-context map) → its value is the token.
    - value change → version differs → the site retargets to a fresh
      static lease carrying the new value.
    - HMR / schema change → the override is re-validated against the
      CURRENT registration each render; a schema that now rejects a
      previously-valid value flips the surfaced value to nil, moving the
      version and retargeting the site to nil."
  nil)

(defn- override-sub-meta
  "The target sub's registration metadata for override-value validation,
  resolved through the CURRENT frame's IMAGE generation when a scope is in
  effect (so a multi-image frame validates against its own image's schema —
  parity with the subscription resolution `re-frame.substrate.observation`
  performs), else the global registrar (absence-is-default). Non-throwing;
  nil when the entry sub is unregistered (validation then no-ops)."
  [query-v]
  (if-some [frame-id (frame/resolve-current-frame)]
    (live-frame/call-with-frame-resolution
      (live-frame/frame-resolution-target frame-id)
      (fn [] [(registrar/lookup :sub (first query-v)) frame-id]))
    [(registrar/lookup :sub (first query-v)) nil]))

(defn- resolve-override
  "Resolve a Story `:sub-overrides` HIT for `query`, or nil on a miss.

  The ENTIRE consult — the `*sub-overrides*` dynamic-var read, the schema
  validation, and the token lowering — sits behind `interop/debug-enabled?`
  so a PRODUCTION build (Story absent) carries ZERO per-sub branch, no
  dynamic-var read, and none of these bytes on the subscription render
  path: the whole body DCEs under `:advanced` + `goog.DEBUG=false`, and
  `sub-read`'s `(some? override)` folds to false (rf2-vxgfnd.21).

  On a HIT the pinned value is schema-validated against the target sub's
  declared output `:schema` through the SHARED
  `override-schema/validate-sub-override!` primitive — the SAME registered
  validator + `:rf.error/schema-validation-failure {:where :sub-override}`
  emission + recover-to-nil the Reagent-family `subscribe` path applies, so
  the compiled view can never surface a state the sub's own schema says is
  impossible. A nil-valued (or validation-failed) HIT stays a HIT (distinct
  from a miss). The returned map is the port's opaque change token (see
  `*sub-overrides*`)."
  [query]
  (when interop/debug-enabled?
    (when-some [m *sub-overrides*]
      (when (contains? m query)
        (let [raw            (get m query)
              [sub-meta fid] (override-sub-meta query)
              v              (override-schema/validate-sub-override!
                               raw query sub-meta fid)]
          {:value v :override-id query :version v})))))

;; ---------------------------------------------------------------------------
;; Ambient render capture
;;
;; Single-threaded on both hosts within one synchronous render; a compiled
;; view's children are ELEMENTS (rendered later by the host), never
;; synchronously nested calls, so renders never nest — a save/restore cell
;; is sufficient and re-entrancy-robust.
;; ---------------------------------------------------------------------------

(def ^:private ambient (atom nil)) ;; {:cell <cell> :capture <volatile>} | nil

(defn- fresh-capture
  [generation]
  {:generation generation :order [] :by-key {}})

(defn- target-key
  [target]
  (case (:kind target)
    :subscription   [:sub (:frame-id target) (:query target)]
    :story-override [:override (:override-id target)]))

(defn- record-site
  "Add a site's observation to `cap`, deduped by target identity (the first
  observation of a target within a pass fixes its order + evidence)."
  [cap tk target ev value]
  (if (contains? (:by-key cap) tk)
    cap
    (-> cap
        (update :order conj tk)
        (assoc-in [:by-key tk] {:target target :evidence ev :value value}))))

;; ---------------------------------------------------------------------------
;; The slice-scoped probe memo (S2d item 3; 03 §3)
;;
;; ONE memo handle per synchronous execution slice, shared by every probe
;; in the slice so sibling rows compute shared derivation parents once
;; (the first-mount fan-out mitigation). Created lazily on the first probe
;; of a slice; released on the next event-loop tick (`interop/next-tick` —
;; a macrotask, which is fine here: this is GC hygiene, not a
;; correctness-before-paint boundary) so an abandoned slice's table is
;; unreachable garbage. The memo is an ECONOMY only — commit step 5
;; corrects any staleness before paint — so a single module holder (probes
;; never nest across slices synchronously) is sufficient.
;; ---------------------------------------------------------------------------

(def ^:private slice-memo* (atom nil))

(defn- current-slice-memo
  "The current slice's probe memo handle — reused across every probe of
  this synchronous slice, created lazily. On CLJS a fresh slice mints a
  fresh handle and the old one is released on the next event-loop tick
  (`interop/next-tick` — `goog.async.nextTick`, a macrotask firing after the
  synchronous render pass; GC hygiene only, not a before-paint boundary).
  On the JVM `next-tick` is a concurrent executor, not a microtask, so a
  timer-driven clear would race a synchronous render; there the handle is
  invalidated by the memo's own `(frame, frame-epoch, registry-epoch)` tag
  on the next epoch (`slice-memo-table!`) and cleared between fixtures by
  `reset-scheduler!`. The memo is an ECONOMY — commit step 5 corrects any
  staleness before paint — so the coarser JVM lifetime is harmless."
  []
  (or @slice-memo*
      (let [h (obs/make-slice-memo)]
        (reset! slice-memo* h)
        ;; Release OUR handle at slice end; a later slice may already have
        ;; installed a newer one, so clear only while ours is still current.
        #?(:cljs
           (interop/next-tick (fn [] (compare-and-set! slice-memo* h nil))))
        h)))

;; ---------------------------------------------------------------------------
;; The ViewCell
;; ---------------------------------------------------------------------------

(deftype ViewCell [state]
  ;; Opaque host object with IDENTITY equality (deftype default). `state`
  ;; is an atom of:
  ;;
  ;;   {:view-id vid
  ;;    :generation g            ; view-body generation (HMR); commit rejects
  ;;                             ;   a stale capture (step 1)
  ;;    :lifecycle :fresh|:connected|:disconnected|:dead
  ;;    :root incarnation|nil    ; owning root-incarnation token — a per-mount
  ;;                             ;   identity that SURVIVES an Activity hide, so
  ;;                             ;   root teardown reaps a cell hidden before its
  ;;                             ;   window (rf2-vxgfnd.85; see `root-cells`)
  ;;    :disconnect-provisional? bool ; DEV-only (rf2-vxgfnd.44): a just-emitted
  ;;                             ;   :disconnected interval that has NOT yet
  ;;                             ;   settled past its synchronous commit. A
  ;;                             ;   reconnect while still provisional is a
  ;;                             ;   same-tick StrictMode dev replay (no hide);
  ;;                             ;   `settle-disconnect!` clears it. false/absent
  ;;                             ;   in production (no StrictMode double-invoke)
  ;;    :committed {tk -> lease} ; installed dependency set
  ;;    :values    {tk -> value} ; last published site values (stabilization
  ;;                             ;   + the revision snapshot's evidence)
  ;;    :revision  int           ; get-snapshot returns this (useSyncExternalStore)
  ;;    :dirty?    bool          ; pending-notification flag (drain coalescing)
  ;;    :evidence  ev|nil        ; DEBUG-only bounded causal evidence for the
  ;;                             ;   pending window (see `fold-evidence`); nil
  ;;                             ;   in production (elided) + between flushes
  ;;    :latest-capture cap|nil  ; last finished render capture (commit input)
  ;;    :listeners {k -> fn}     ; useSyncExternalStore subscribers
  ;;    :intervals [interval]}   ; lifecycle facts (dev/tool; 03 §4)
  )

(defn cell?
  [x]
  (instance? ViewCell x))

(defn- state
  [^ViewCell cell]
  (.-state cell))

(defn make-cell
  "Mint a fresh ViewCell for view `view-id` at body `generation` (default
  0). Starts `:fresh` — the first successful commit connects it."
  ([view-id] (make-cell view-id 0))
  ([view-id generation]
   (->ViewCell
     (atom {:view-id        view-id
            :generation     generation
            :lifecycle      :fresh
            :root           nil
            :disconnect-provisional? false
            :committed      {}
            :values         {}
            :revision       0
            :dirty?         false
            :evidence       nil
            :latest-capture nil
            :listeners      {}
            :intervals      []}))))

;; ---- read + query stabilization ---------------------------------------------

(defn sub-read
  "The one bridge `(sub query)` lowers to on both hosts. Resolves the
  site's target (override door → ambient frame), probes ownership-free, and
  returns the value:

    - Inside a live cell render (ambient capture present), RECORDS the site
      (target + evidence) into the capture and returns the `rf=`-stabilized
      value (the prior committed reference when the read is `rf=`).
    - Outside a cell (the JVM `ui.test/render` one-shot headless read, or a
      defensive direct call), returns the freshly probed value — no
      ownership, no capture.

  Fail-loud rides the port: `:rf.error/no-such-sub` on an unknown entry
  sub, `:rf.error/frame-destroyed` against a destroyed frame."
  [query]
  (let [override (resolve-override query)
        site-ctx (cond-> {:query-v query}
                   (some? override) (assoc :override override))
        target   (obs/resolve-target site-ctx)
        ev       (obs/probe target (current-slice-memo))
        v        (:value ev)
        {:keys [cell capture]} @ambient]
    (if (some? cell)
      (let [tk    (target-key target)
            prior (get (:values @(state cell)) tk ::none)
            v*    (if (and (not (identical? ::none prior)) (eq/rf= v prior))
                    prior
                    v)]
        (vswap! capture record-site tk target ev v*)
        v*)
      v)))

(defn with-capture
  "Run `thunk` (a compiled view body) under a fresh ambient capture, stash the
  finished capture on `cell` as the layout commit's input, and return the
  thunk's value (the host element).

  Ownership-free: the render acquires NO ref-count, watch, or cache node, so an
  abandoned render (a thunk whose result the host discards — StrictMode
  double-render, time-sliced tear-off) leaks ZERO ownership. It does, however,
  RETAIN its capture: the finished capture (its targets + probe evidence +
  values, possibly a large probed collection) is stashed on the cell as
  `:latest-capture` and stays there until the NEXT render overwrites it. The
  honest bound is therefore AT MOST ONE capture retained per cell — never
  accumulating, never ownership — not zero. That retention is REQUIRED, not a
  leak: the layout commit reads `:latest-capture` in a later effect, and
  StrictMode's effect mount→cleanup→remount re-commits the SAME capture with no
  intervening render, so clearing it here would break the reacquire
  (rf2-vxgfnd.44)."
  [^ViewCell cell thunk]
  (let [cap  (volatile! (fresh-capture (:generation @(state cell))))
        prev @ambient]
    (reset! ambient {:cell cell :capture cap})
    (try
      (let [el (thunk)]
        (swap! (state cell) assoc :latest-capture @cap)
        el)
      (finally
        (reset! ambient prev)))))

;; ---- useSyncExternalStore contract ------------------------------------------

(defn get-snapshot
  "The scalar revision snapshot — a monotonically-advancing integer, stable
  by `=`/`===` between notifications. `useSyncExternalStore`'s getSnapshot."
  [^ViewCell cell]
  (:revision @(state cell)))

(defn subscribe
  "Register `listener` (a zero-arg fn the host re-renders through) under a
  fresh key; returns an unsubscribe thunk. `useSyncExternalStore`'s
  subscribe."
  [^ViewCell cell listener]
  (let [k (gensym "rf-ui-cell-listener")]
    (swap! (state cell) assoc-in [:listeners k] listener)
    (fn unsubscribe [] (swap! (state cell) update :listeners dissoc k))))

(defn- notify-listeners!
  [^ViewCell cell]
  (doseq [f (vals (:listeners @(state cell)))]
    (f)))

(defn- advance-revision!
  "Advance the cell's revision and notify subscribers — the host re-reads
  getSnapshot, sees the new revision, and re-renders. From step 8 this runs
  synchronously inside the layout commit (React corrects BEFORE paint)."
  [^ViewCell cell]
  (swap! (state cell) update :revision inc)
  (notify-listeners! cell))

;; ---- drain coalescing + the notification scheduler (S2d) --------------------
;;
;; `on-change` is constant-work (mark-dirty; never compute — I-5). The moving
;; epoch/cause rides as EVIDENCE only (bounded + DEBUG-gated — see the
;; evidence plane below; production carries just the pending flag). A cell
;; enters the module DIRTY REGISTRY exactly once per flush boundary (the set
;; dedups by identity; a re-mark while pending folds in regardless of epoch
;; tag). N epochs
;; committed in one run-to-completion drain therefore advance the cell ONCE
;; at flush — the render batch boundary is DRAIN QUIESCENCE, not epoch close.
;; On CLJS one coalesced flush is armed per drain on the host MICROTASK queue
;; (`queue-microtask!`), which drains after the synchronous run-to-completion
;; drain unwinds and BEFORE the next paint — so a watch-fired movement is
;; corrected before the host can show a torn frame (rf2-vxgfnd.40; 03 §3).
;; `flush!` is the synchronous forcing, SCOPED so no pending work leaks
;; across roots. The Q51 scope ruling and the reentrancy contract live in
;; the ns docstring.

(defonce ^:private dirty-cells
  ;; The set of ViewCells with a pending (unflushed) notification — the
  ;; input to every scoped flush. `defonce` (module-lived); tests clear it
  ;; via `reset-scheduler!`.
  (atom #{}))

(defonce ^:private flush-scheduled? (atom false))

(defonce ^:private live-cells
  ;; The set of currently-CONNECTED ViewCells — the input to a frame-destroy
  ;; sweep (`teardown-frame!`). A cell enrols on `connect!` (its first commit,
  ;; when it starts observing a frame) and leaves on `disconnect!` (React
  ;; unmount / Activity hide, when it releases its committed dependency set) or
  ;; `teardown!` (it goes :dead). Membership therefore tracks EXACTLY the cells
  ;; carrying a live committed dependency set, so a destroyed frame can find the
  ;; cells observing it and transition them to :dead (03 §4) rather than leave
  ;; them live to throw `:rf.error/frame-destroyed` on their next read. Because
  ;; a disconnected cell leaves the set, an unmounted cell never lingers here
  ;; (no retention leak). `defonce` (module-lived); tests clear it via
  ;; `reset-scheduler!`.
  (atom #{}))

(defonce ^:private root-cells
  ;; ROOT-INCARNATION OWNERSHIP: `incarnation -> #{owned cells}`. Every ViewCell
  ;; attached to a root (`attach-root!`, the mount seam) enrols here under its
  ;; root's incarnation and STAYS enrolled across a transient Activity hide — a
  ;; hide removes the cell from `live-cells` (it holds no committed deps) but NOT
  ;; from its root membership. This is the piece `teardown-collector` alone cannot
  ;; supply: a cell hidden by React Activity BEFORE the root's teardown window is
  ;; armed already left `:fresh`/`:connected`, so its cleanup can never enrol it in
  ;; the window, and it would otherwise linger `:disconnected {:reason :unknown}`
  ;; and RECONNECTABLE after its root is gone (rf2-vxgfnd.85). `teardown-root!`
  ;; consults this registry to reap those already-hidden cells alongside the ones
  ;; the window captures. Membership is bounded to CURRENTLY-retained cells: a cell
  ;; leaves on final `teardown!` (`detach-root!`), and an incarnation's entry is
  ;; dropped the moment its last cell leaves — so repeated mount/hide/unmount
  ;; cycles never grow a historical registry. The incarnation is a FRESH per-mount
  ;; identity (`make-root-incarnation`), NOT the reusable root-id, so a stale
  ;; teardown can never reap the cells of a replacement root mounted under the same
  ;; root-id. `defonce` (module-lived); `reset-scheduler!` clears it between
  ;; fixtures.
  (atom {}))

(defonce ^:private teardown-collector
  ;; The COLLECTION WINDOW of an in-flight host/root teardown (`teardown-root!`),
  ;; or nil at rest. `teardown-root!` arms it (a set) around the host React
  ;; `.unmount`; while armed, `disconnect!` attributes each disconnecting cell
  ;; to it — because a host `.unmount` sweeps the effect-cleanups of EXACTLY its
  ;; own root's tree (React scopes the unmount), every captured cell belongs to
  ;; that root and no sibling root's cell can enter the window. The driver then
  ;; retroactively proves each captured cell an unmount (03 §4). nil while no
  ;; teardown runs, so an Activity hide (a `disconnect!` with the window unarmed)
  ;; stays a transient reconnectable disconnect. Save/restored around a teardown
  ;; so a re-entrant one nests. `defonce` (module-lived); `reset-scheduler!`
  ;; clears it between fixtures.
  (atom nil))

(declare flush-pending!)

#?(:cljs
   (defn- queue-microtask!
     "Enqueue `f` on the host MICROTASK queue. The HTML event loop runs its
     microtask checkpoint after the current synchronous task and BEFORE the
     'update the rendering' (paint) step, so a microtask-scheduled flush
     corrects a moved sub before the host can present a torn frame — the
     property the drain-quiescence render batch leans on (rf2-vxgfnd.40).

     `js/queueMicrotask` where present (all modern browsers + Node ≥ 11);
     a resolved-Promise job is the fallback. DELIBERATELY NOT
     `goog.async.nextTick` (`interop/next-tick`), which is a MACROTASK
     (`setImmediate` / `MessageChannel` / `setTimeout`) — it yields to the
     event loop and may let a torn frame paint before it runs."
     [f]
     (if (exists? js/queueMicrotask)
       (js/queueMicrotask f)
       (.then (js/Promise.resolve) (fn [_] (f))))))

(defn- schedule-flush!
  "Arm ONE coalesced microtask that drains the whole registry — the CLJS
  host's realization of the drain-quiescence render batch (03 §3). One
  microtask per drain, NOT per epoch: it is armed by the first mark of a
  drain and fires only after the synchronous run-to-completion drain
  unwinds, so every epoch committed by the drain's queued events folds into
  the same flush. Re-marks before it runs fold in; a synchronous `flush!`
  beforehand just leaves it an empty drain.

  CLJS-only: the flush rides `queue-microtask!` — a TRUE host microtask that
  fires after the synchronous drain unwinds and BEFORE the next paint, so a
  watch-fired invalidation is corrected before a torn frame can show
  (rf2-vxgfnd.40). The JVM headless host has NO async render loop to align
  to — its drain-quiescence flush is the EXPLICIT `flush!` (07 §2 'the only flush
  idiom'; SSR renders one-shot) — and `interop/next-tick` there is a
  CONCURRENT executor, not a microtask, so a background auto-drain would
  race synchronous callers. One honest option per host, not a pretended same
  mechanism (03 §3)."
  []
  #?(:cljs
     (when (compare-and-set! flush-scheduled? false true)
       (queue-microtask!
         (fn []
           (reset! flush-scheduled? false)
           (flush-pending!))))
     :clj nil))

;; ---- the DEBUG invalidation-evidence plane (rf2-vxgfnd.46) ------------------
;;
;; TWO SEPARATE PLANES. The PRODUCTION scheduler needs only the pending flag
;; + identity-deduped registry membership (`enrol-dirty!`); that is the WHOLE
;; production invalidation cost — no per-cause allocation, nothing that scales
;; with the queued-event count. The DEBUG plane accumulates a BOUNDED
;; (constant-size) causal summary of the coalesced batch for tooling (Xray),
;; gated behind `interop/debug-enabled?` so it DCEs out of `:advanced` +
;; goog.DEBUG=false. Notification coalescing and causal evidence stay distinct:
;; one dirty enrolment / one render, while dev/tool builds retain enough to
;; attribute the render batch to its contributing movement (Spec 006 §The
;; internal observation port; 03 §3).

(def ^:private ^:const target-cap
  ;; Distinct moving targets kept per pending window before overflow folds
  ;; into a running dropped-count — bounds the evidence to constant size.
  8)

(defn- fold-evidence
  "DEBUG plane: fold one invalidation `payload` into a cell's pending-window
  evidence `ev` (nil = a fresh window). Returns a BOUNDED, constant-size
  record — never an unbounded payload vector:

    {:first-epoch  e0    ; the FIRST movement's frame-epoch (the anchor)
     :latest-epoch eN    ; the most-recent movement's frame-epoch
     :count        n     ; total invalidations folded this window
     :causes       #{…}  ; the SET of causes seen (:value/:hmr/:disposed — ≤3)
     :targets      [tk…] ; distinct moved target keys, capped at `target-cap`
     :dropped      m}    ; distinct targets dropped past the cap (loss account)

  The cause set and epoch scalars are naturally bounded; the target vector is
  explicitly capped with a dropped-count, so overflow is REPORTED, never
  silently lost (acceptance-criterion 3)."
  [ev {:keys [cause target frame-epoch]}]
  (let [tk (when target (target-key target))]
    (if (nil? ev)
      {:first-epoch  frame-epoch
       :latest-epoch frame-epoch
       :count        1
       :causes       (if cause #{cause} #{})
       :targets      (if tk [tk] [])
       :dropped      0}
      (let [known?  (or (nil? tk) (some #(= tk %) (:targets ev)))
            at-cap? (>= (count (:targets ev)) target-cap)]
        (cond-> (-> ev
                    (assoc :latest-epoch frame-epoch)
                    (update :count inc))
          cause                            (update :causes conj cause)
          (and (not known?) (not at-cap?)) (update :targets conj tk)
          (and (not known?) at-cap?)       (update :dropped inc))))))

(defn- record-evidence!
  "DEBUG plane: fold `payload`'s bounded causal evidence into `cell`'s
  pending-window accumulator. Elided in production (every caller gates on
  `interop/debug-enabled?`)."
  [^ViewCell cell payload]
  (swap! (state cell) update :evidence fold-evidence payload))

(defonce ^:private evidence-sink
  ;; DEBUG-only consumer seam: `(fn [cell evidence] …)` | nil. Invoked at each
  ;; flush with the coalesced bounded evidence BEFORE it is cleared, so a tool
  ;; (Xray) receives the causal summary of the render batch rather than it
  ;; being dead cell state (rf2-vxgfnd.46). `defonce` (module-lived);
  ;; `reset-scheduler!` clears it between fixtures.
  (atom nil))

(defn set-evidence-sink!
  "Install (or clear, with nil) the DEBUG-only invalidation-evidence consumer
  — a `(fn [cell evidence] …)` the flush invokes with each pending cell's
  coalesced bounded evidence just after it completes that cell's flush. The
  intended `re-frame.ui.tool`/Xray projection point; the flush's call is gated
  on `interop/debug-enabled?`, so it is a no-op in production. A THROWING sink
  is contained by the flush (see `flush-one!` / `report-sink-escape!`) and can
  never strand a cell or abort the render batch. Returns nil."
  [f]
  (reset! evidence-sink f)
  nil)

(defonce ^:private last-sink-escape
  ;; The single bounded diagnostic slot for a CONTAINED evidence-sink throw
  ;; (`{:cell cell :error e}` | nil). The evidence-sink is a DEBUG-only tool
  ;; consumer of the scheduler, NEVER an authority over it: a throwing sink
  ;; must never strand a cell (the acceptance bug rf2-vxgfnd.73) — so
  ;; `flush-one!` completes every cell's flush FIRST and CONTAINS the sink's
  ;; throw, and `flush-scope!` records the escape here (overwritten — one slot,
  ;; the newest escape) plus one host-console diagnostic per batch. Observable
  ;; via `last-evidence-sink-escape` so the escape is never silent. `defonce`
  ;; (module-lived); `reset-scheduler!` clears it between fixtures.
  (atom nil))

(defn- report-sink-escape!
  "DEV plane: contain the fallout of a THROWING `evidence-sink`. The flush has
  ALREADY completed every drained cell's scheduler state (`:dirty?`/evidence
  cleared, revision advanced, listeners notified), so this is a pure
  after-the-fact report that can neither strand a cell nor abort the batch.
  Record the escape in the single bounded `last-sink-escape` slot (a
  tool/test read — `last-evidence-sink-escape`) and, on CLJS, emit ONE host
  `console.warn` naming the offending view. Reporting NEVER routes back through
  `evidence-sink` (no recursion). Called at most once per `flush-scope!` batch,
  and only from its debug-gated branch, so the whole helper DCEs under
  `goog.DEBUG=false`. `escape` is `{:cell cell :error e}`."
  [{:keys [cell error] :as escape}]
  (reset! last-sink-escape escape)
  #?(:cljs
     (when (exists? js/console)
       (.warn js/console
              (str "[re-frame.ui] an evidence-sink (the DEBUG "
                   "invalidation-evidence consumer — e.g. Xray) threw while the "
                   "scheduler flushed view " (pr-str (:view-id @(state cell)))
                   " — the throw was CONTAINED so the render correction still "
                   "completed and the batch was not aborted; a debug observer "
                   "cannot corrupt flush completion. Fix the sink callback "
                   "installed with (set-evidence-sink! …). Cause: "
                   (error/ex-message-safe error))))
     :clj nil))

(defn- enrol-dirty!
  "The PRODUCTION scheduling core: flag `cell` pending, enrol it in the dirty
  registry once (identity-deduped — a re-mark while already dirty coalesces),
  and arm one per-drain microtask flush. NO evidence, no compute, no
  acquire/release (I-5) — this is the WHOLE production invalidation cost, flat
  in the number of queued events."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (:dirty? @st)
      (swap! st assoc :dirty? true)
      (swap! dirty-cells conj cell)
      (schedule-flush!))))

(defn mark-dirty!
  "The `on-change` body / test seam: enrol `cell` for a coalesced flush
  (`enrol-dirty!`) and — in DEV/tool builds only — fold `epoch` as minimal
  cause evidence. Never acquires/releases and never computes (I-5). Coalescing
  keys on the pending flag, NEVER on any cause tag: a re-mark while already
  pending advances nothing, yet its evidence still FOLDS into the same bounded
  pending-window record — so N invalidations in one drain coalesce to ONE
  render while the debug plane preserves first/latest epoch plus a bounded
  cause/target summary (rf2-vxgfnd.46). `on-change-fn` folds the RICHER port
  payload (cause + target); this arity carries only an epoch, for the JVM/test
  seam. nil when driven without epoch evidence."
  ([^ViewCell cell] (mark-dirty! cell nil))
  ([^ViewCell cell epoch]
   (when interop/debug-enabled?
     (record-evidence! cell {:frame-epoch epoch}))
   (enrol-dirty! cell)))

(defn- complete-flush!
  "PHASE 1 of a batch flush (rf2-vxgfnd.86): complete `cell`'s SCHEDULER STATE
  with NO arbitrary user code — capture the pre-clear DEBUG evidence, clear
  `:dirty?`/evidence, and advance the revision (WITHOUT notifying listeners yet).
  No-op / nil when the cell is not dirty. Returns `[cell ev]` for the cell it
  completed (`ev` nil in production / when the window carried none), else nil.

  Splitting completion from notification is the order-independence fix. The batch
  core runs this over the WHOLE drained batch BEFORE `deliver-flush!` runs ANY
  listener or evidence-sink, so a re-entrant re-mark (from a phase-2 listener or
  sink) always finds every drained cell already completed and, seeing a cleared
  `:dirty?`, enrols a FRESH pending window (next-batch semantics) — independent of
  set iteration order. Previously each cell was completed-and-notified one at a
  time, so whether a re-marked cell was already cleared (→ fresh window) or still
  in the drained-but-uncompleted batch (→ mark lost) depended on hash order."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (:dirty? @st)
      (let [ev (when interop/debug-enabled? (:evidence @st))]
        (swap! st assoc :dirty? false :evidence nil)
        (swap! st update :revision inc)
        [cell ev]))))

(defn- deliver-flush!
  "PHASE 2 of a batch flush (rf2-vxgfnd.86): now that `complete-flush!` has
  settled the WHOLE batch's scheduler state, notify `cell`'s listeners (the
  production re-render trigger — one coalesced React render batch, since every
  listener fires synchronously within this flush) and, in DEV, hand the captured
  `ev` to the installed `evidence-sink` (Xray; rf2-vxgfnd.46) inside a guard.

  A THROWING sink is a broken DEBUG tool, never an authority over the render
  correction: its throw is CONTAINED here (caught and returned to the batch core
  for one bounded diagnostic) so it can neither strand a cell nor abort the
  batch's remaining cells (rf2-vxgfnd.73). Because completion already finished for
  the whole batch, a re-entrant sink/listener that re-marks this (or ANY) cell
  enrols a FRESH pending window rather than losing the notification. The sink
  still receives the exact pre-clear bounded summary once per flushed cell.
  Returns `{:cell cell :error e}` on a contained sink escape (DEV only), else nil;
  the whole evidence/containment path DCEs under `goog.DEBUG=false`."
  [^ViewCell cell ev]
  (notify-listeners! cell)
  (when interop/debug-enabled?
    (when-some [sink @evidence-sink]
      (try
        (sink cell ev)
        nil
        (catch #?(:clj Throwable :cljs :default) e
          {:cell cell :error e})))))

(defn- run-flush-batch!
  "The two-phase batch-flush CORE over an explicit ordered `cells` seq
  (rf2-vxgfnd.86). PHASE 1 (`complete-flush!`) settles every dirty cell's
  scheduler state — with no user code — so the whole batch is complete before
  PHASE 2 (`deliver-flush!`) runs any listener or evidence-sink. A re-entrant
  re-mark in phase 2 therefore always sees a fully-completed batch and opens the
  NEXT window, regardless of iteration order. Preserves the DEV containment:
  each `deliver-flush!` contains a throwing sink, the batch captures the FIRST
  escape and emits ONE bounded diagnostic (never through the sink), and the whole
  DEBUG branch DCEs under `goog.DEBUG=false`. Returns the count actually flushed."
  [cells]
  (let [completed (into [] (keep complete-flush!) cells)]
    (if interop/debug-enabled?
      ;; deliver EVERY cell regardless of tool behaviour — a throwing sink is
      ;; contained per-cell (returns the escape, never propagates). Evaluate the
      ;; delivery FIRST (never inside `or`, which would short-circuit the rest of
      ;; the batch after the first escape), keep the FIRST escape, emit ONE
      ;; bounded diagnostic — never through the sink.
      (let [escape (reduce (fn [acc [cell ev]]
                             (let [e (deliver-flush! cell ev)] (or acc e)))
                           nil
                           completed)]
        (when (some? escape)
          (report-sink-escape! escape)))
      (doseq [[cell _] completed]
        (deliver-flush! cell nil)))
    (count completed)))

(defn flush-scope!
  "The scoped-flush PRIMITIVE: synchronously flush every pending cell for
  which `(scope-pred cell)` is truthy, advancing each once; cells outside
  the scope stay pending (no epoch work leaks across scopes). Reentrancy-
  SAFE by construction — the matching cells are removed from the registry
  ATOMICALLY (`swap-vals!`) before any notify, so a notify-triggered
  re-entrant flush sees an already-drained set. The drained batch runs through
  the two-phase `run-flush-batch!` core, so a re-entrant re-mark of ANOTHER cell
  in the same batch is order-INDEPENDENT (rf2-vxgfnd.86). Returns the count
  flushed."
  [scope-pred]
  (let [[old _] (swap-vals! dirty-cells
                            (fn [cells] (into #{} (remove scope-pred) cells)))
        flushed (filterv scope-pred old)]
    (run-flush-batch! flushed)))

(defn flush-batch-in-order!
  "Test seam (rf2-vxgfnd.86): drain and flush EXACTLY `cells` in the GIVEN order
  through the same two-phase `run-flush-batch!` core `flush-scope!` uses, so a
  fixture can FORCE a deterministic iteration order and prove the flush is
  order-independent (drive `[a b]` and `[b a]`, assert identical outcomes).
  Removes them from the dirty registry first (mirroring `flush-scope!`'s atomic
  drain), then runs the ordered two-phase flush. Returns the count flushed."
  [cells]
  (swap! dirty-cells #(reduce disj % cells))
  (run-flush-batch! (vec cells)))

(defn flush-pending!
  "GLOBAL drain — flush EVERY pending cell once (the test-only all-roots
  spelling `ui.test/flush!` rides this). Returns the count flushed."
  []
  (flush-scope! (constantly true)))

(defn- cell-frames
  "The set of frame-ids `cell`'s committed subscription sites observe."
  [^ViewCell cell]
  (into #{}
        (keep (fn [tk] (when (= :sub (nth tk 0)) (nth tk 1))))
        (keys (:committed @(state cell)))))

(defn cell-observes-frame?
  "True when `cell`'s committed dependency set includes a site in frame
  `frame-id` (the frame-scope membership test)."
  [^ViewCell cell frame-id]
  (contains? (cell-frames cell) frame-id))

(defn flush-frame!
  "The FRAME arity of `flush!` — flush every pending cell observing frame
  `frame-id` (every root that observes that frame). Cells scoped to other
  frames stay pending. Returns the count flushed."
  [frame-id]
  (flush-scope! #(cell-observes-frame? % frame-id)))

(defn flush-dirty!
  "Synchronously flush THIS cell's pending notification, if any (test seam
  + the per-cell forcing door). Returns nil."
  [^ViewCell cell]
  (flush-scope! #(identical? % cell))
  nil)

(defn- discard-pending!
  "Drop `cell`'s pending notification WITHOUT advancing its revision —
  used at disconnect/teardown so an unmounted or dead cell never lingers
  in the registry or fires a stale flush. Returns nil."
  [^ViewCell cell]
  (swap! dirty-cells disj cell)
  (swap! (state cell) assoc :dirty? false :evidence nil)
  nil)

(defn dirty?
  "True when `cell` has a pending (unflushed) notification (tool/test read)."
  [^ViewCell cell]
  (boolean (:dirty? @(state cell))))

(defn pending-epoch
  "The FIRST-epoch ANCHOR of `cell`'s pending-window evidence — the frame-epoch
  the FIRST `on-change` of the current window carried (nil when the cell is not
  pending, was dirtied without epoch evidence, or in a production build where
  the debug evidence plane is elided). A convenience read over
  `pending-evidence`. Epoch ids are movement/cause evidence ONLY; coalescing
  keys on the pending flag, never on this tag, so later queued events' epochs
  fold into the same render batch without re-anchoring or advancing it. For the
  FULL coalesced batch — latest epoch, the cause/target span, the loss account
  — read `pending-evidence` (tool/test read)."
  [^ViewCell cell]
  (:first-epoch (:evidence @(state cell))))

(defn pending-evidence
  "The BOUNDED causal-evidence record for `cell`'s current pending window (nil
  when the cell is not pending, or in a production build where the debug plane
  is elided). The coalesced summary of every invalidation folded into this one
  render batch — see `fold-evidence` for the shape: first/latest frame-epoch,
  the fold count, the cause set, a capped distinct-target vector, and a
  dropped-count. The `re-frame.ui.tool`/Xray projection reads this (or receives
  it via `set-evidence-sink!`) to attribute a coalesced render to its
  contributing movement WITHOUT forcing a render per epoch (rf2-vxgfnd.46;
  tool/test read)."
  [^ViewCell cell]
  (:evidence @(state cell)))

(defn pending-cell-count
  "The number of cells with a pending notification (tool/test read)."
  []
  (count @dirty-cells))

(defn last-evidence-sink-escape
  "The most recent CONTAINED `evidence-sink` throw as `{:cell cell :error e}`,
  or nil when no sink has thrown since the last `reset-scheduler!` (or in a
  production build, where the debug evidence plane is elided). A throwing
  DEBUG sink never strands a cell or aborts a flush batch (rf2-vxgfnd.73) — it
  is contained and surfaced HERE (plus a host `console.warn`) so the escape is
  never silent (tool/test read)."
  []
  @last-sink-escape)

(defn reset-scheduler!
  "Test support: drop every pending notification and the slice memo without
  advancing any revision — a clean slate between fixtures. Returns nil."
  []
  (reset! dirty-cells #{})
  (reset! flush-scheduled? false)
  (reset! slice-memo* nil)
  (reset! live-cells #{})
  (reset! root-cells {})
  (reset! teardown-collector nil)
  (reset! evidence-sink nil)
  (reset! last-sink-escape nil)
  nil)

(defn- on-change-fn
  "Build the per-lease `on-change` the commit registers on each acquired
  target (Spec 006 §The internal observation port). Constant-work: enrol
  `cell` for a coalesced flush and — in DEV/tool builds only — fold the port's
  rich invalidation payload (`:cause`/`:target`/`:frame-epoch`, plus the
  `:node-*`/`:registry-epoch` axes it carries) into the bounded pending-window
  evidence. Production carries only the enrolment (I-5; the evidence fold DCEs
  out under goog.DEBUG=false)."
  [^ViewCell cell]
  (fn [payload]
    (when interop/debug-enabled?
      (record-evidence! cell payload))
    (enrol-dirty! cell)))

;; ---- lifecycle (03 §4) ------------------------------------------------------
;;
;; Three OBSERVABLE runtime states. The fact emitted at cleanup is always
;; `:disconnected {:reason :unknown}` — the platform gives no hide-vs-unmount
;; signal. Later evidence annotates the PRIOR interval, never the present: a
;; SETTLED reconnect proves an Activity hide (`:activity-hidden {:proof
;; :reconnect}`); an explicit host/root teardown proves an unmount (`:unmounted
;; {:proof :host-teardown}`).
;;
;; The settle qualifier is the rf2-vxgfnd.44 honesty fix: a reconnect within the
;; SAME synchronous commit as its disconnect is NOT a hide — it is React
;; StrictMode's dev mount→cleanup→remount replay, and asserting `:activity-hidden`
;; for it would fabricate a proof the runtime never observed. So `disconnect!`
;; marks each cleanup PROVISIONAL and `settle-disconnect!` (a microtask on CLJS)
;; clears it once the disconnect outlives its commit; only a disconnect that
;; survived a host yield can then be proven a hide. DEV-only — production has no
;; StrictMode double-invoke, so `:disconnect-provisional?` is never set and a
;; reveal is proven exactly as before.

(defn lifecycle
  "The cell's current runtime state keyword."
  [^ViewCell cell]
  (:lifecycle @(state cell)))

(defn intervals
  "The cell's lifecycle interval log (dev/tool read) — the emitted facts
  plus any retroactive annotations."
  [^ViewCell cell]
  (:intervals @(state cell)))

;; ---- root-incarnation ownership (03 §4; rf2-vxgfnd.85) ----------------------
;;
;; A ViewCell's committed dependency set (`live-cells`) is the discoverability
;; surface a FRAME-destroy sweep consults, but it is dropped the instant the cell
;; disconnects — so it cannot survive an Activity hide. Root teardown needs an
;; ownership association that DOES survive a transient hide: the `root-cells`
;; registry keyed by a per-mount root incarnation. `attach-root!` (the mount seam)
;; enrols a cell under its root's incarnation; the cell stays enrolled across a
;; hide and leaves only when it is proven dead (`detach-root!` from `teardown!`).
;; `teardown-root!` reaps a root's already-hidden cells through this registry.

(defn make-root-incarnation
  "Mint a FRESH, opaque root-incarnation token — a per-mount identity with no
  structure, compared only by `identical?`. A root that re-mounts under the same
  (reusable) root-id gets a DISTINCT incarnation, so a stale teardown carrying an
  old incarnation can never reap a replacement root's cells (rf2-vxgfnd.85). The
  mount seam mints one per root and threads it to every ViewCell under that root
  via `attach-root!`."
  []
  #?(:clj (Object.) :cljs (js-obj)))

(defn- forget-root-cell!
  "Remove `cell` from `incarnation`'s membership set, DROPPING the incarnation
  entry entirely when its last cell leaves — so repeated mount/hide/unmount
  cycles never grow a historical registry (rf2-vxgfnd.85 AC5)."
  [^ViewCell cell incarnation]
  (when (some? incarnation)
    (swap! root-cells
           (fn [m]
             (let [s (disj (get m incarnation #{}) cell)]
               (if (empty? s) (dissoc m incarnation) (assoc m incarnation s)))))))

(defn attach-root!
  "Mount seam: associate `cell` with root `incarnation` — the per-mount ownership
  token (`make-root-incarnation`) that SURVIVES a transient Activity disconnect
  and lets `teardown-root!` reap a cell already Activity-hidden BEFORE the host
  unmount window (rf2-vxgfnd.85). Enrols the cell in `root-cells` under
  `incarnation` (idempotent — a set); re-attaching to a DIFFERENT incarnation
  first drops the old membership, so a cell can never straddle two roots. An
  Activity hide (which removes the cell from `live-cells`) does NOT drop this
  membership — that is the whole point. Returns the cell."
  [^ViewCell cell incarnation]
  (let [st  (state cell)
        old (:root @st)]
    (when (and (some? old) (not (identical? old incarnation)))
      (forget-root-cell! cell old))
    (swap! st assoc :root incarnation)
    (swap! root-cells update incarnation (fnil conj #{}) cell))
  cell)

(defn- detach-root!
  "Drop `cell`'s root-incarnation membership on FINAL teardown — the cell is
  proven dead, so it leaves the registry (membership is bounded to
  currently-retained cells). Idempotent; a no-op when the cell owns no root."
  [^ViewCell cell]
  (let [st  (state cell)
        inc (:root @st)]
    (when (some? inc)
      (forget-root-cell! cell inc)
      (swap! st assoc :root nil))))

(defn cell-root
  "The root incarnation `cell` is attached to, or nil (tool/test read)."
  [^ViewCell cell]
  (:root @(state cell)))

(defn root-cell-count
  "The number of root incarnations currently tracked, or — with `incarnation` —
  the number of cells owned by it (tool/test read). Proves the ownership registry
  is bounded to retained/mounted cells and drops empty incarnations on final
  teardown (rf2-vxgfnd.85 AC5)."
  ([] (count @root-cells))
  ([incarnation] (count (get @root-cells incarnation))))

(defn- release-committed!
  "Release every lease in the committed dependency set and clear it —
  acquire-before-release is not needed here (this is a full teardown, not a
  reconcile). Idempotent via the port's own release! idempotence."
  [^ViewCell cell]
  (let [st (state cell)]
    (doseq [lease (vals (:committed @st))]
      (obs/release! lease))
    (swap! st assoc :committed {})))

(defn- annotate-open-disconnect!
  "Upgrade the still-open `:disconnected {:reason :unknown}` interval's
  reason to `reason`+`proof` (the retroactive annotation). No-op when the
  last interval is not an open disconnect."
  [^ViewCell cell reason proof]
  (swap! (state cell) update :intervals
         (fn [ivs]
           (if (and (seq ivs) (= :disconnected (:state (peek ivs))))
             (conj (pop ivs)
                   (assoc (peek ivs) :reason reason :proof proof))
             ivs))))

(defn settle-disconnect!
  "Settle `cell`'s open PROVISIONAL disconnect — mark it a disconnect that
  outlived the synchronous commit that produced it, so a subsequent reconnect
  honestly proves an Activity hide rather than a same-tick React StrictMode
  replay (rf2-vxgfnd.44). No-op unless the cell is still `:disconnected` (a cell
  already reconnected or torn down needs no settle). On CLJS `disconnect!` arms
  this as a microtask — it fires after the synchronous commit unwinds and before
  the next paint, so ONLY a same-commit StrictMode replay can reconnect ahead of
  it; a genuine reveal (a later task) always finds the disconnect already
  settled. A headless/JVM fixture calls this explicitly to model the host yield
  of a real reveal. Returns nil."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (= :disconnected (:lifecycle @st))
      (swap! st assoc :disconnect-provisional? false)))
  nil)

(defn- arm-disconnect-settle!
  "Arm the settle of `cell`'s provisional disconnect. On CLJS a host microtask
  (`queue-microtask!`) that runs after the current synchronous commit unwinds
  and before the next paint — so React StrictMode's synchronous
  mount→cleanup→remount reconnects BEFORE it (a replay, un-annotated), while a
  genuine reveal (a later task) reconnects after it (proven a hide). No
  auto-settle on the JVM headless host (no StrictMode, no async render loop); a
  fixture there settles explicitly."
  [^ViewCell cell]
  #?(:cljs (queue-microtask! (fn [] (settle-disconnect! cell)))
     :clj  nil))

(defn- connect!
  "Commit-time lifecycle transition into `:connected`. A transition FROM
  `:disconnected` is a reconnect. A reconnect proves an Activity hide ONLY when
  the prior disconnect had SETTLED — i.e. it outlived the synchronous commit
  that produced it. A SETTLED-then-reconnected interval is a genuine hide→reveal
  and is annotated `:activity-hidden {:proof :reconnect}`. An UNSETTLED reconnect
  is a React StrictMode dev replay — the same cell's effect
  mount→cleanup→remount within ONE synchronous commit, where NO hide and NO
  unmount happened — so it is NOT annotated: the runtime must not fabricate an
  Activity-hide proof it never observed (rf2-vxgfnd.44). In production
  `:disconnect-provisional?` is never set (no StrictMode double-invoke), so a
  reveal is proven exactly as before."
  [^ViewCell cell]
  (let [st @(state cell)]
    (when (= :disconnected (:lifecycle st))
      (if (:disconnect-provisional? st)
        ;; same-tick StrictMode replay — the disconnect never settled; clear the
        ;; provisional flag and DO NOT fabricate an Activity-hide proof.
        (swap! (state cell) assoc :disconnect-provisional? false)
        (annotate-open-disconnect! cell :activity-hidden :reconnect)))
    (swap! (state cell) assoc :lifecycle :connected)
    ;; Enrol in the live-cell registry (idempotent — a set) so a frame-destroy
    ;; sweep can find this cell while it observes a live committed dep set.
    (swap! live-cells conj cell)))

(defn disconnect!
  "Effects-cleanup transition (React unmount OR Activity hide —
  indistinguishable at this moment): release lease owners (hidden UI must
  not poll) and emit `:disconnected {:reason :unknown}`. The cell is
  reconnectable — a later commit on the same cell reacquires and
  corrects. Idempotent. Returns the cell.

  When a host/root teardown is in flight (`teardown-root!` armed the
  collection window — this cleanup is firing DURING a real `.unmount`), the
  disconnecting cell is captured so the driver can retroactively prove it an
  unmount. The emitted fact is STILL `:disconnected {:reason :unknown}` (the
  same immediate cleanup fact as an Activity hide — the two are
  indistinguishable here, 03 §4); the upgrade to `:unmounted` happens later, in
  `teardown-root!`. With the window unarmed (an Activity hide) nothing is
  captured and the cell stays reconnectable."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (contains? #{:fresh :connected} (:lifecycle @st))
      (release-committed! cell)
      (discard-pending! cell)
      ;; Leave the live-cell registry: a disconnected cell holds no committed
      ;; deps, so it observes no frame — and an unmounted cell must not linger.
      (swap! live-cells disj cell)
      (swap! st (fn [m]
                  (-> m
                      (assoc :lifecycle :disconnected)
                      (update :intervals conj {:state :disconnected :reason :unknown}))))
      ;; Same-tick StrictMode-replay guard (DEV-only — DCEs in production, which
      ;; has no StrictMode double-invoke): mark this disconnect PROVISIONAL and
      ;; arm its settle. A reconnect BEFORE the settle is a synchronous replay
      ;; (mount→cleanup→remount in one commit — no hide); a reconnect AFTER it is
      ;; a genuine reveal that `connect!` proves an Activity hide (rf2-vxgfnd.44).
      (when interop/debug-enabled?
        (swap! st assoc :disconnect-provisional? true)
        (arm-disconnect-settle! cell))
      ;; Host/root teardown in flight: attribute this cell to it (03 §4).
      (when (some? @teardown-collector)
        (swap! teardown-collector conj cell)))
    cell))

(defn teardown!
  "Explicit host/root teardown (root unmount, parent teardown, frame
  destroy): the frame/adapter/root is destroyed under this cell's handle —
  the retained interval is proven an unmount. Detaches leases, marks the
  cell `:dead` (no resume), annotates, and de-enrols it from the live-cell
  registry. Wired from core's frame-destroy path via `teardown-frame!` (the
  `:ui/on-frame-destroyed!` late-bind hook `re-frame.ui.frames` registers).
  Idempotent. Returns the cell."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (= :dead (:lifecycle @st))
      (if (= :disconnected (:lifecycle @st))
        (annotate-open-disconnect! cell :unmounted :host-teardown)
        (swap! st update :intervals conj
               {:state :unmounted :reason :unmounted :proof :host-teardown}))
      (release-committed! cell)
      (discard-pending! cell)
      (swap! st assoc :lifecycle :dead)
      (swap! live-cells disj cell)
      ;; leave the root-incarnation registry — a dead cell is no longer
      ;; retained, so it must not linger as reapable ownership (rf2-vxgfnd.85).
      (detach-root! cell))
    cell))

(defn teardown-frame!
  "Frame-destroy sweep: transition every currently-connected ViewCell
  observing frame `frame-id` to `:dead` (03 §4 dead-cell lifecycle). Each
  matched cell's leases are detached, its pending notification dropped, and
  its retained interval proven an unmount (`:unmounted {:proof
  :host-teardown}`) — so a subsequent read/probe on such a cell follows the
  dead-cell lifecycle instead of throwing `:rf.error/frame-destroyed` off the
  observation port. Fired from core's `frame/destroy-frame!` through the
  `:ui/on-frame-destroyed!` late-bind hook wired in `re-frame.ui.frames`;
  the sweep runs while the frame is still live, so each cell releases its
  leases against the live sub-cache (symmetric with `disconnect!`). The
  membership test rides the cell's committed dependency set
  (`cell-observes-frame?`); a disconnected cell holds none and is therefore
  never a target. Iterates a snapshot, so the per-cell `teardown!` de-enrol
  is safe. Returns the count torn down."
  [frame-id]
  (let [victims (filterv #(cell-observes-frame? % frame-id) @live-cells)]
    (doseq [cell victims]
      (teardown! cell))
    (count victims)))

(defn teardown-root!
  "Explicit host/ROOT teardown driver (03 §4) — the root-path counterpart to
  the frame-destroy sweep, driven from `re-frame.ui.client/unmount!*` at the
  host teardown moment. Runs `unmount-thunk` (the host React `.unmount`) with a
  collection window armed, so every ViewCell whose effect-cleanup fires DURING
  the host unmount is captured (`disconnect!` enrols it — see the
  `teardown-collector` window). Because a host `.unmount` sweeps the cleanups of
  EXACTLY its own root's tree, the captured set is precisely the cells belonging
  to that root — sibling and nested-portal roots are structurally isolated (they
  are separate React roots the sweep never touches).

  Each captured cell is then retroactively proven an unmount via the shared
  `teardown!` primitive (the SAME machinery the frame-destroy path uses, not a
  parallel one): its transient `:disconnected {:reason :unknown}` interval —
  which effect cleanup ALREADY emitted, indistinguishable from an Activity hide
  at that moment — is upgraded to `:unmounted {:proof :host-teardown}` and the
  cell goes `:dead`, so a retained handle can never reconnect after its root is
  gone and a late recommit fails through the dead-cell lifecycle (commit! step
  2) rather than by probing a torn-down context. An Activity hide, by contrast,
  disconnects with NO window armed, is never captured, and stays reconnectable —
  a reveal proves it `:activity-hidden {:proof :reconnect}`.

  ## Reaping cells already Activity-hidden before the window (rf2-vxgfnd.85)

  The window alone captures ONLY cells whose effect cleanup fires DURING the host
  unmount. A ViewCell hidden by React Activity/Offscreen BEFORE the window is
  armed already left `:fresh`/`:connected` for `:disconnected`, so its cleanup can
  never re-enrol it — React does not re-run an already-destroyed effect when it
  discards the hidden fiber at root unmount. Such a cell would linger
  `:disconnected {:reason :unknown}` and RECONNECTABLE after its root is gone. So
  teardown ALSO consults the `root-cells` ownership registry: after the window
  closes it reaps every still-`:disconnected` cell owned by a torn-down root
  incarnation. Two incarnation sources, unioned:

    - `root-incarnation` — the explicit incarnation the caller (the mount/root
      layer) names for the root being unmounted. This is the deterministic path:
      it reaps a root's hidden cells even when the window captured NONE (the whole
      root hidden, or a single already-hidden cell).
    - the incarnations of every WINDOW-CAPTURED cell — a captured cell names its
      own root's incarnation, so its still-hidden siblings under the same root are
      reaped too, even when no explicit incarnation is supplied.

  Only `:disconnected` owned cells are reaped; a still-`:connected` cell of a
  SIBLING root's incarnation is never in the set, and an incarnation is a fresh
  per-mount identity, so a replacement root under the same root-id is untouched.

  Ordering-robust and re-entrancy-safe by save/restore. If `unmount-thunk`
  THROWS (React refused to unmount — it did NOT tear the tree down, so no
  cleanup ran and nothing was collected), the window is restored and the throw
  propagates WITHOUT tearing any cell down: the orphaned tree's cells stay live,
  and the original host error is never masked (the ruled host-teardown
  behaviour, rf2-vxgfnd.53). A throwing thunk reaps NOTHING — including the
  registry sweep — because the root was not actually torn down. Returns the count
  of cells torn down.

  Two arities: `[unmount-thunk]` (no explicit incarnation — window + captured-cell
  incarnations only, the current `client/unmount!*` call) and
  `[root-incarnation unmount-thunk]` (the incarnation-aware path)."
  ([unmount-thunk] (teardown-root! nil unmount-thunk))
  ([root-incarnation unmount-thunk]
   (let [prev      @teardown-collector
         collected (do (reset! teardown-collector #{})
                       (try
                         (unmount-thunk)
                         @teardown-collector
                         (finally
                           (reset! teardown-collector prev))))
         ;; the incarnations whose hidden cells this teardown owns: the explicit
         ;; one plus every window-captured cell's own incarnation.
         incs      (cond-> (into #{} (keep cell-root) collected)
                     (some? root-incarnation) (conj root-incarnation))
         ;; already-Activity-hidden owned cells the window could NOT capture —
         ;; still `:disconnected`, belonging to a torn-down incarnation.
         hidden    (into #{}
                         (comp (mapcat #(get @root-cells %))
                               (filter #(= :disconnected (lifecycle %))))
                         incs)
         victims   (into collected hidden)]
     (doseq [cell victims]
       (teardown! cell))
     (count victims))))

;; ---------------------------------------------------------------------------
;; The 8-step layout-commit reconciler (03 §3)
;; ---------------------------------------------------------------------------

(defn- evidence-moved?
  "Did the target move in the render→commit gap? Compares the acquire-time
  `read` against the render's `probe` evidence. A cold probe
  (`:node-version nil`) falls back to `rf=` on value; a live probe compares
  the node IDENTITY (`:node-key`), the node version, and the frame/registry
  epochs (belt-and-braces the two-guard rule leans on).

  The `:node-key` clause (ABI v2, rf2-vxgfnd.14/.93) is the reincarnation axis:
  a same-id frame DESTROY + RECREATE across the gap builds a FRESH reaction with
  a strictly-greater `:node-key`, so the render probed one node's key and the
  commit read a DIFFERENT node's key — MOVEMENT the reconciler must correct
  before paint. Version + epochs ALONE can tie across the two incarnations
  (`frame/dissoc-frame!` restarts the commit epoch), so without this clause the
  reincarnation reads as unchanged. The unchanged-node fast path is preserved:
  the same live node reads the same key/version/epochs, so no correction fires.
  The cold-probe (`:node-version nil`) and static-override (`read` returns no
  `:node-key`, probed cold) branches are untouched."
  [read-result probe-ev]
  (if (nil? (:node-version probe-ev))
    (not (eq/rf= (:value read-result) (:value probe-ev)))
    (or (not= (:version read-result) (:node-version probe-ev))
        (not= (:node-key read-result) (:node-key probe-ev))
        (not= (:frame-epoch read-result) (:frame-epoch probe-ev))
        (not= (:registry-epoch read-result) (:registry-epoch probe-ev)))))

;; ---- frame-close revalidation: incarnation-safe (rf2-vxgfnd.88, extends .61) ---
;;
;; A commit publishing ownership must resolve against the EXACT frame incarnation
;; it acquired its leases from — never merely the reused frame-id. `destroy-frame!`
;; + a fresh same-id construction mints a DISTINCT incarnation token
;; (`frame/frame-incarnation-token` — the record's `:drain-lock`, stable across one
;; incarnation, distinct across destroy+recreate), so comparing the token captured
;; at ACQUIRE against the live token catches a same-id reincarnation the bare-id
;; `frame-closing?` check alone misses: a commit that acquired incarnation A but
;; finds B live under the id must JOIN A's teardown, not publish `:connected`
;; against the replacement (rf2-vxgfnd.88). The bare-id `frame-closing?` clause is
;; RETAINED for the .61 in-flight window (A mid-teardown, still live pre-flip,
;; token unchanged — the marker is the only signal there).

(defn- committed-frame-incarnations
  "Snapshot `{frame-id -> incarnation-token}` for every frame the committed map
  `committed` observes — captured at ACQUIRE time so the frame-close revalidation
  compares each frame's LIVE incarnation against the one this commit acquired from
  (rf2-vxgfnd.88). A frame absent/destroyed reads nil. O(observed frames)."
  [committed]
  (persistent!
    (reduce (fn [acc tk]
              (if (= :sub (nth tk 0))
                (assoc! acc (nth tk 1) (frame/frame-incarnation-token (nth tk 1)))
                acc))
            (transient {})
            (keys committed))))

(defn- incarnation-superseded?
  "True when ANY frame in the acquire-time `incarnations` snapshot is no longer
  live under the SAME incarnation token — its incarnation was destroyed (nil now)
  or REPLACED by a fresh same-id incarnation (a distinct token). The
  exact-identity half of the frame-close revalidation (rf2-vxgfnd.88): a commit
  must resolve against the incarnation it ACQUIRED from, never the reused id."
  [incarnations]
  (reduce-kv (fn [_ fid captured]
               (if (identical? captured (frame/frame-incarnation-token fid))
                 false
                 (reduced true)))
             false
             incarnations))

(def ^:dynamic ^:no-doc *commit-barrier*
  "JVM/DOM linearization TEST SEAM — nil in production (two nil checks per commit,
  zero further cost), NEVER bound off a test path (the `*upsert-decide-probe*`
  idiom). Bound to a `(fn [phase cell] …)`, `commit!` calls it at two
  deterministic points so a fixture can interleave a frame destroy / same-id
  reincarnation with a commit:

    :pre-acquire   — after the render capture is loaded, BEFORE the kept-check /
                     stage-acquire. A same-id DESTROY+RECREATE here makes the
                     commit ACQUIRE the FRESH incarnation while the render probed
                     the destroyed one — the `:node-key` reincarnation
                     `evidence-moved?` must correct before paint (rf2-vxgfnd.93).
    :post-acquire  — after stage-acquire + the evidence read, BEFORE the publish.
                     A full destroy of the ACQUIRED incarnation + a fresh same-id
                     incarnation here proves the frame-close revalidation joins the
                     commit to the acquired incarnation's teardown, not the
                     replacement id (rf2-vxgfnd.88)."
  nil)

(defn commit!
  "Run the 8-step layout commit for `cell` against its latest render
  capture. Idempotent: an unchanged committed set + capture reconciles to a
  no-op (kept-check retains every lease untouched), so StrictMode's
  release/reacquire replay is naturally balanced.

  1. Reject a stale-generation capture (HMR) — return `:stale`, the host
     re-renders (no ownership touched).
  2. A `:dead` cell fails loudly — reconnection after teardown is not
     allowed.
  3. Kept-check every previously-committed site with `(current? lease
     target)`; unchanged live leases are RETAINED untouched, a failed check
     (disposed node, frame swap, restabilized query, moved override)
     classifies the site as retargeted.
  4. STAGE-acquire every newly-observed or retargeted target BEFORE
     releasing anything (acquire-before-release — a shared node never falls
     through its zero-owner edge). On ANY acquisition failure every staged
     lease is synchronously released in REVERSE acquisition order, the
     prior committed set stays installed, and the typed error propagates.
  5. Compare each acquired node's version + frame/registry epochs against
     the render's probe evidence — for BOTH retained and staged leases, so a
     retained site's movement is caught here on a non-watchable headless host
     that has no value-movement watch (rf2-vxgfnd.39).
  6. Publish the committed site values + the new dependency set (retained +
     staged) — before the user can interact with the new DOM.
  7. Release the prior leases of dropped + retargeted sites.
  8. If any evidence moved in the render→commit gap, advance the revision
     and notify — React corrects BEFORE paint.

  Returns `cell` on a normal commit, `:stale` on a rejected generation, or
  `:no-capture` when nothing has been rendered yet."
  [^ViewCell cell]
  (let [st  (state cell)
        st0 @st
        cap (:latest-capture st0)]
    (cond
      (nil? cap)
      :no-capture

      ;; step 1 — stale generation
      (not= (:generation cap) (:generation st0))
      :stale

      :else
      (do
        ;; step 2 — dead cell fails loudly (no resume). The context is gone,
        ;; so the always-on `:rf.error/frame-destroyed` is the honest id (no
        ;; new catalogue row): reconnection after teardown is not allowed.
        (when (= :dead (:lifecycle st0))
          (error/throw-error!
            :rf.error/frame-destroyed
            're-frame.ui.reactive/commit!
            (str "a ViewCell commit reached a :dead cell (view " (:view-id st0)
                 ") — the frame/root was torn down under a retained handle; a "
                 "dead cell cannot resume")
            {:extra {:view-id (:view-id st0)}}))
        ;; :pre-acquire test seam — a fixture may reincarnate the frame in the
        ;; render→commit gap here so the stage-acquire below binds the FRESH
        ;; incarnation while the render probed the destroyed one (rf2-vxgfnd.93).
        (when-some [barrier *commit-barrier*] (barrier :pre-acquire cell))
        (let [committed  (:committed st0)          ;; tk -> lease
              new-order  (:order cap)              ;; tk, render order
              new-by     (:by-key cap)
              new-set    (set new-order)
              ;; step 3 — kept-check
              retained   (persistent!
                           (reduce
                             (fn [acc [tk lease]]
                               (if (and (contains? new-set tk)
                                        (obs/current? lease (:target (new-by tk))))
                                 (assoc! acc tk lease)
                                 acc))
                             (transient {})
                             committed))
              retained?  (fn [tk] (contains? retained tk))
              to-release (persistent!
                           (reduce
                             (fn [acc [tk lease]]
                               (if (retained? tk) acc (assoc! acc tk lease)))
                             (transient {})
                             committed))
              to-acquire (into [] (remove retained?) new-order)
              on-change  (on-change-fn cell)
              ;; step 4 — transactional stage-acquire
              staged     (loop [ks     to-acquire
                                acc    []]
                           (if (empty? ks)
                             acc
                             (let [tk     (first ks)
                                   target (:target (new-by tk))
                                   lease  (try
                                            (obs/acquire! target on-change)
                                            (catch #?(:clj Throwable :cljs :default) e
                                              ;; rollback: release staged in
                                              ;; REVERSE acquisition order; the
                                              ;; prior committed set stays
                                              ;; installed; propagate the throw.
                                              (doseq [[_ l] (rseq acc)]
                                                (obs/release! l))
                                              (throw e)))]
                               (recur (rest ks) (conj acc [tk lease])))))
              staged-map (into {} staged)
              ;; ACQUIRE-time incarnation snapshot for the frame-close
              ;; revalidation (rf2-vxgfnd.88): the exact incarnation each lease
              ;; was acquired from, so a same-id reincarnation before publish is
              ;; caught by token identity, not merely the reused frame-id.
              incarnations (committed-frame-incarnations (merge retained staged-map))
              ;; step 5 — evidence comparison: read EACH acquired node (staged AND
              ;; retained) against the render's probe evidence, so movement in the
              ;; render→commit gap is caught before paint (invariant 5).
              ;;
              ;;   - STAGED leases: their freshly-installed watch could not have
              ;;     fired for a pre-acquire gap move, so step 5 is the SOLE catch
              ;;     on every host.
              ;;   - RETAINED leases: on a WATCHABLE host their live watch already
              ;;     caught the move (the cell is pending; its scheduled flush
              ;;     corrects before paint), but on a NON-WATCHABLE headless host
              ;;     there is NO watch — so step 5 is the ONLY catch. Without this
              ;;     read a retained site's headless movement is corrected by
              ;;     nothing: `:values` publishes the stale render value and no
              ;;     revision advances (rf2-vxgfnd.39).
              ;;
              ;; `read` recomputes the plain-atom node on deref, so a headless move
              ;; is observed here; the two catches are kept distinct because the
              ;; step-8 advance treats them differently (see below).
              moved-in? (fn [[tk lease]]
                          (evidence-moved? (obs/read lease)
                                           (:evidence (new-by tk))))
              staged-moved?   (boolean (some moved-in? staged))
              retained-moved? (boolean (some moved-in? retained))
              new-values (persistent!
                           (reduce (fn [acc tk]
                                     (assoc! acc tk (:value (new-by tk))))
                                   (transient {})
                                   new-order))]
          ;; :post-acquire test seam — a fixture may destroy the ACQUIRED
          ;; incarnation + recreate the id here to prove the revalidation below
          ;; joins this commit to the acquired incarnation's teardown, not the
          ;; replacement id (rf2-vxgfnd.88).
          (when-some [barrier *commit-barrier*] (barrier :post-acquire cell))
          ;; step 6 — publish (committed values + dependency set)
          (swap! st assoc
                 :committed (merge retained staged-map)
                 :values    new-values)
          ;; step 7 — release dropped + retargeted prior leases
          (doseq [[_ lease] to-release]
            (obs/release! lease))
          ;; lifecycle: connect (reconnect annotation when re-committing a
          ;; hidden cell). This ENROLS the cell into the live-cell registry —
          ;; the discoverability publish a frame-destroy sweep consults.
          (connect! cell)
          ;; INCARNATION-SAFE frame-close revalidation (rf2-vxgfnd.88, extending
          ;; rf2-vxgfnd.61). Two reasons this commit must JOIN a teardown instead
          ;; of publishing `:connected`, checked against the ACQUIRE-time
          ;; incarnation snapshot rather than the bare frame-id:
          ;;
          ;;   (a) `incarnation-superseded?` — a frame this commit acquired from
          ;;       is no longer live under the SAME incarnation token: it was
          ;;       destroyed, OR a fresh same-id incarnation replaced it in the
          ;;       render→commit gap. The bare-id `frame-closing?` MISSES this once
          ;;       the old incarnation's teardown completed and cleared its marker
          ;;       while a replacement went live under the id — so an old lease
          ;;       would otherwise survive on the replacement id (rf2-vxgfnd.88).
          ;;       Token identity (`frame/frame-incarnation-token`, the record's
          ;;       `:drain-lock`, distinct per incarnation) resolves the commit to
          ;;       exactly the incarnation it targeted.
          ;;
          ;;   (b) `frame-incarnation-closing?` — the ACQUIRED incarnation is
          ;;       IN-FLIGHT closing (rf2-vxgfnd.61, scoped to the incarnation by
          ;;       rf2-vxgfnd.94). #5731 wires destroy to a SNAPSHOT sweep of the
          ;;       live cells that runs while the frame is still LIVE (pre-flip),
          ;;       then flips liveness. A commit that acquires + enrols between the
          ;;       sweep snapshot and the flip is MISSED by the sweep, and its
          ;;       incarnation token is UNCHANGED (the frame is still live pre-flip)
          ;;       — so `incarnation-superseded?` alone would not catch it. But
          ;;       `destroying-frames` is populated at the TOP of `destroy-frame!`,
          ;;       so the marker is continuously present across the whole teardown
          ;;       window: the enrolled cell observes the close and joins the
          ;;       teardown against the still-releasable cache. Scoping to the
          ;;       ACQUIRE-time token (not the bare id) is what closes .88's
          ;;       reciprocal Failure-2: in the JVM window where an OLD incarnation
          ;;       A's marker is still set (post-`dissoc-frame!`, pre-`finally`)
          ;;       while a fresh same-id incarnation B is already live, a commit
          ;;       that acquired B reads FALSE here (B's token ≠ A's marker token),
          ;;       so A's stale close authority cannot tear down a cell that owns B
          ;;       (rf2-vxgfnd.94). The bare-id `frame/frame-closing?` would read
          ;;       true for the reused id and wrongly reap B's cell.
          ;;
          ;; A live, not-closing frame under an unchanged incarnation (incl. a
          ;; committed fresh same-id incarnation this commit legitimately acquired)
          ;; makes both checks false — disjoint frames commit/destroy concurrently,
          ;; and the single-threaded CLJS host (destroy runs to completion without
          ;; yielding to a commit) never sees either true.
          (if (or (incarnation-superseded? incarnations)
                  (some (fn [[fid token]]
                          (frame/frame-incarnation-closing? fid token))
                        incarnations))
            (teardown! cell)
            ;; step 8 — moved evidence corrects before paint. The staged catch
            ;; always advances synchronously; a RETAINED catch advances only when
            ;; no live watch already caught the move — a pending (`dirty?`) cell is
            ;; a watchable host whose scheduled flush already corrects before paint,
            ;; so advancing here too would add a redundant render (rf2-vxgfnd.39).
            (when (or staged-moved?
                      (and retained-moved? (not (dirty? cell))))
              (advance-revision! cell)))
          cell)))))

;; ---- test/inspection reads --------------------------------------------------

(defn committed-target-keys
  "The target keys of the cell's installed dependency set (tool/test read)."
  [^ViewCell cell]
  (set (keys (:committed @(state cell)))))

(defn committed-values
  "The cell's last-published site values, keyed by target (tool/test read)."
  [^ViewCell cell]
  (:values @(state cell)))

(defn committed-lease
  "The installed lease for target key `tk` (tool/test read), or nil."
  [^ViewCell cell tk]
  (get (:committed @(state cell)) tk))

(defn latest-capture
  "The cell's last finished render capture — the layout commit's input, and the
  single capture a cell retains between renders (tool/test read; nil before the
  first render). See `with-capture`: a render retains AT MOST this one capture,
  overwritten by the next render, never accumulating (rf2-vxgfnd.44)."
  [^ViewCell cell]
  (:latest-capture @(state cell)))

(defn revision
  "The cell's current revision integer (tool/test read)."
  [^ViewCell cell]
  (:revision @(state cell)))

(defn current-live-cells
  "The set of currently-CONNECTED ViewCells (tool/test read) — the live-cell
  registry a frame-destroy sweep consults, and the seam a DOM lifecycle fixture
  grabs a mounted cell through to observe its post-unmount lifecycle."
  []
  @live-cells)
