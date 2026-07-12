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
  hosts, so headless movement is caught at the commit evidence comparison
  (step 5), not by a callback.

  ## The ViewCell (03 §2)

  Every lexical `(sub …)` in a view is a compile-indexed site; all of a
  view's sites share ONE ViewCell — one `useSyncExternalStore`, one scalar
  revision snapshot, one notification per epoch. Render probes WITHOUT
  ownership (resolve-target + probe, no ref-count / watch / cache node);
  the layout commit acquires the CAPTURED targets. Abandoned renders
  (StrictMode double-render, time-sliced tear-off) retain NOTHING because
  render never acquires — the 10k-abandoned-renders-retain-zero property
  is structural, mirroring the port's S-3 §5 cold-probe exit criterion.

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

  ## Epoch coalescing + `flush!` scope (S2d — 03 §3 invariant 6; Spec 006
  §Epoch finalization)

  The sixth frozen invariant: EPOCH CLOSE NOTIFIES EACH DIRTY CELL ONCE.
  Sub deltas mark their cell dirty through constant-work `on-change`
  (never compute — I-5); the cell enters a module-level DIRTY REGISTRY
  exactly once (a set, deduped by cell identity) and one coalesced flush
  is scheduled per microtask (`interop/next-tick` — drain→React is
  microtask-aligned) ON CLJS; the JVM headless host has no async render
  loop, so it auto-schedules NOTHING and drains via the EXPLICIT `flush!`
  (07 §2's only flush idiom; SSR is one-shot) — one honest option per
  host. N sub deltas within one epoch therefore advance the cell's
  revision ONCE. Coalescing is per-cell-per-flush-boundary; the
  cross-epoch SEPARATION the push-economics bench asserts (8 epochs ⇒ 8
  renders — G-5/G-13) rides the async drain boundaries and is wired with
  the bench in S2f, not here.

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
  on the first probe of a slice and cleared on the next microtask
  (`interop/next-tick`), so an abandoned slice's table is unreachable
  garbage. The memo is an ECONOMY, never an authority — the commit
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
;; of a slice; cleared on the next microtask so an abandoned slice's table
;; is unreachable garbage. The memo is an ECONOMY only — commit step 5
;; corrects any staleness before paint — so a single module holder (probes
;; never nest across slices synchronously) is sufficient.
;; ---------------------------------------------------------------------------

(def ^:private slice-memo* (atom nil))

(defn- current-slice-memo
  "The current slice's probe memo handle — reused across every probe of
  this synchronous slice, created lazily. On CLJS a fresh slice mints a
  fresh handle and the old one is released on the next microtask (a true
  `goog.async.nextTick` boundary firing AFTER the synchronous render pass).
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
  ;;    :committed {tk -> lease} ; installed dependency set
  ;;    :values    {tk -> value} ; last published site values (stabilization
  ;;                             ;   + the revision snapshot's evidence)
  ;;    :revision  int           ; get-snapshot returns this (useSyncExternalStore)
  ;;    :dirty?    bool          ; pending-notification flag (epoch coalescing)
  ;;    :dirty-epoch epoch|nil   ; the epoch the pending notification tags
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
            :committed      {}
            :values         {}
            :revision       0
            :dirty?         false
            :dirty-epoch    nil
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
  "Run `thunk` (a compiled view body) under a fresh ambient capture, stash
  the finished capture on `cell` as the commit input, and return the
  thunk's value (the host element). Ownership-free: an abandoned render (a
  thunk whose result the host discards) leaves only unreachable garbage."
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

;; ---- epoch coalescing + the notification scheduler (S2d) --------------------
;;
;; `on-change` is constant-work (mark-dirty; never compute — I-5). A cell
;; enters the module DIRTY REGISTRY exactly once per flush boundary (the
;; set dedups by identity); N sub deltas in one epoch therefore advance
;; the cell ONCE at flush. One coalesced async flush is scheduled per
;; microtask (drain→React is microtask-aligned — 03 §3); `flush!` is the
;; synchronous forcing, SCOPED so no epoch work leaks across roots. The
;; Q51 scope ruling and the reentrancy contract live in the ns docstring.

(defonce ^:private dirty-cells
  ;; The set of ViewCells with a pending (unflushed) notification — the
  ;; input to every scoped flush. `defonce` (module-lived); tests clear it
  ;; via `reset-scheduler!`.
  (atom #{}))

(defonce ^:private flush-scheduled? (atom false))

(declare flush-pending!)

(defn- schedule-flush!
  "Arm ONE coalesced microtask that drains the whole registry — the CLJS
  host's realization of the microtask-aligned epoch close (03 §3). Re-marks
  before it runs fold into the same flush; a synchronous `flush!` beforehand
  just leaves it an empty drain.

  CLJS-only: `interop/next-tick` there is a true `goog.async.nextTick`
  microtask that fires AFTER the synchronous render pass. The JVM headless
  host has NO async render loop to align to — its epoch close is the
  EXPLICIT `flush!` (07 §2 'the only flush idiom'; SSR renders one-shot) —
  and `next-tick` there is a CONCURRENT executor, not a microtask, so a
  background auto-drain would race synchronous callers. One honest option
  per host, not a pretended same mechanism (03 §3)."
  []
  #?(:cljs
     (when (compare-and-set! flush-scheduled? false true)
       (interop/next-tick
         (fn []
           (reset! flush-scheduled? false)
           (flush-pending!))))
     :clj nil))

(defn mark-dirty!
  "The `on-change` body: mark `cell` dirty for `epoch` and enrol it in the
  dirty registry (once — a re-mark while already dirty coalesces), then
  arm one microtask flush. Never acquires/releases (no reentrant-graph-op)
  and never computes (I-5) — it flags a revision advance the scheduled
  render re-probes. `epoch` (the moving frame's commit epoch, from the
  `on-change` payload) tags the pending notification; nil when driven
  without epoch evidence."
  ([^ViewCell cell] (mark-dirty! cell nil))
  ([^ViewCell cell epoch]
   (let [st (state cell)]
     (when-not (:dirty? @st)
       (swap! st assoc :dirty? true :dirty-epoch epoch)
       (swap! dirty-cells conj cell)
       (schedule-flush!)))))

(defn- flush-one!
  "Clear `cell`'s pending flag and advance its revision once (the caller
  has already removed it from the registry). No-op when not dirty —
  idempotent under a concurrent drain."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (:dirty? @st)
      (swap! st assoc :dirty? false :dirty-epoch nil)
      (advance-revision! cell))))

(defn flush-scope!
  "The scoped-flush PRIMITIVE: synchronously flush every pending cell for
  which `(scope-pred cell)` is truthy, advancing each once; cells outside
  the scope stay pending (no epoch work leaks across scopes). Reentrancy-
  SAFE by construction — the matching cells are removed from the registry
  ATOMICALLY (`swap-vals!`) before any notify, so a notify-triggered
  re-entrant flush sees an already-drained set. Returns the count flushed."
  [scope-pred]
  (let [[old _] (swap-vals! dirty-cells
                            (fn [cells] (into #{} (remove scope-pred) cells)))
        flushed (filterv scope-pred old)]
    (doseq [cell flushed]
      (flush-one! cell))
    (count flushed)))

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
  (swap! (state cell) assoc :dirty? false :dirty-epoch nil)
  nil)

(defn dirty?
  "True when `cell` has a pending (unflushed) notification (tool/test read)."
  [^ViewCell cell]
  (boolean (:dirty? @(state cell))))

(defn pending-cell-count
  "The number of cells with a pending notification (tool/test read)."
  []
  (count @dirty-cells))

(defn reset-scheduler!
  "Test support: drop every pending notification and the slice memo without
  advancing any revision — a clean slate between fixtures. Returns nil."
  []
  (reset! dirty-cells #{})
  (reset! flush-scheduled? false)
  (reset! slice-memo* nil)
  nil)

(defn- on-change-fn
  [^ViewCell cell]
  (fn [payload] (mark-dirty! cell (:frame-epoch payload))))

;; ---- lifecycle (03 §4) ------------------------------------------------------
;;
;; Three OBSERVABLE runtime states. The fact emitted at cleanup is always
;; `:disconnected {:reason :unknown}` — the platform gives no
;; hide-vs-unmount signal. Later evidence annotates the PRIOR interval,
;; never the present: a reconnect proves an Activity hide
;; (`:activity-hidden {:proof :reconnect}`); an explicit host/root teardown
;; proves an unmount (`:unmounted {:proof :host-teardown}`).

(defn lifecycle
  "The cell's current runtime state keyword."
  [^ViewCell cell]
  (:lifecycle @(state cell)))

(defn intervals
  "The cell's lifecycle interval log (dev/tool read) — the emitted facts
  plus any retroactive annotations."
  [^ViewCell cell]
  (:intervals @(state cell)))

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

(defn- connect!
  "Commit-time lifecycle transition into `:connected`. A transition FROM
  `:disconnected` is a reconnect — it retroactively proves the prior
  interval was an Activity hide."
  [^ViewCell cell]
  (let [st @(state cell)]
    (when (= :disconnected (:lifecycle st))
      (annotate-open-disconnect! cell :activity-hidden :reconnect))
    (swap! (state cell) assoc :lifecycle :connected)))

(defn disconnect!
  "Effects-cleanup transition (React unmount OR Activity hide —
  indistinguishable at this moment): release lease owners (hidden UI must
  not poll) and emit `:disconnected {:reason :unknown}`. The cell is
  reconnectable — a later commit on the same cell reacquires and
  corrects. Idempotent. Returns the cell."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (contains? #{:fresh :connected} (:lifecycle @st))
      (release-committed! cell)
      (discard-pending! cell)
      (swap! st (fn [m]
                  (-> m
                      (assoc :lifecycle :disconnected)
                      (update :intervals conj {:state :disconnected :reason :unknown})))))
    cell))

(defn teardown!
  "Explicit host/root teardown (root unmount, parent teardown, frame
  destroy): the frame/adapter/root is destroyed under this cell's handle —
  the retained interval is proven an unmount. Detaches leases, marks the
  cell `:dead` (no resume), and annotates. Wired by the frame/root
  teardown path (S2c/core) for a frame-scoped destroy; here it is the
  substrate contract + its fixtures. Idempotent. Returns the cell."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (= :dead (:lifecycle @st))
      (if (= :disconnected (:lifecycle @st))
        (annotate-open-disconnect! cell :unmounted :host-teardown)
        (swap! st update :intervals conj
               {:state :unmounted :reason :unmounted :proof :host-teardown}))
      (release-committed! cell)
      (discard-pending! cell)
      (swap! st assoc :lifecycle :dead))
    cell))

;; ---------------------------------------------------------------------------
;; The 8-step layout-commit reconciler (03 §3)
;; ---------------------------------------------------------------------------

(defn- evidence-moved?
  "Did the target move in the render→commit gap? Compares the acquire-time
  `read` against the render's `probe` evidence. A cold probe
  (`:node-version nil`) falls back to `rf=` on value; a live probe compares
  the node version and the frame/registry epochs (belt-and-braces the
  two-guard rule leans on)."
  [read-result probe-ev]
  (if (nil? (:node-version probe-ev))
    (not (eq/rf= (:value read-result) (:value probe-ev)))
    (or (not= (:version read-result) (:node-version probe-ev))
        (not= (:frame-epoch read-result) (:frame-epoch probe-ev))
        (not= (:registry-epoch read-result) (:registry-epoch probe-ev)))))

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
     the render's probe evidence.
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
              ;; step 5 — evidence comparison (moved in the render→commit gap)
              moved?     (boolean
                           (some (fn [[tk lease]]
                                   (evidence-moved? (obs/read lease)
                                                    (:evidence (new-by tk))))
                                 staged))
              new-values (persistent!
                           (reduce (fn [acc tk]
                                     (assoc! acc tk (:value (new-by tk))))
                                   (transient {})
                                   new-order))]
          ;; step 6 — publish (committed values + dependency set)
          (swap! st assoc
                 :committed (merge retained staged-map)
                 :values    new-values)
          ;; step 7 — release dropped + retargeted prior leases
          (doseq [[_ lease] to-release]
            (obs/release! lease))
          ;; lifecycle: connect (reconnect annotation when re-committing a
          ;; hidden cell)
          (connect! cell)
          ;; step 8 — moved evidence corrects before paint
          (when moved?
            (advance-revision! cell))
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

(defn revision
  "The cell's current revision integer (tool/test read)."
  [^ViewCell cell]
  (:revision @(state cell)))
