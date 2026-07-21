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
  handle, RETAINED as well as staged (rf2-vxgfnd.39), since a retained site has
  no watch to self-correct there.

  ## The ViewCell (03 §2)

  Every lexical `(sub …)` in a view is a compile-indexed site; all of a
  view's sites share ONE ViewCell — one `useSyncExternalStore`, one scalar
  revision snapshot, one coalesced notification per render batch (the HOST
  CHECKPOINT boundary, NOT per epoch and NOT per drain — see §Render batches
  below).
  Render probes WITHOUT
  ownership (resolve-target + probe, no ref-count / watch / cache node);
  the layout commit acquires the CAPTURED targets. Abandoned renders
  (StrictMode double-render, time-sliced tear-off) acquire NO OWNERSHIP —
  the 10k-abandoned-renders-retain-zero-OWNERSHIP property is structural,
  mirroring the port's S-3 §5 cold-probe exit criterion. Each finished render
  returns its immutable capture beside the host element; an abandoned render's
  pair becomes unreachable instead of publishing speculative state to the
  ViewCell.

  ## The render capture

  A render pass records each executed site's resolved target + probe
  evidence + value into a per-pass CAPTURE (ownership-free), keyed by the
  compiler-issued lexical site id. Equal queries at distinct sites therefore
  own distinct balanced handles while still sharing the subscription node.
  The exact immutable capture travels beside its host element into the
  selected layout/passive effect closure; it is never published speculatively
  by render. Layout reconciles the committed dependency set against that
  selected capture — idempotently, so StrictMode's mount→unmount→mount effect
  replay is naturally balanced.

  ## `sub` value stabilization (03 §2, I-8)

  A site returns its PRIOR EXACT value (identical reference) when the new read
  is `rf=` to that lexical site's last committed value — so an `rf=`-stable
  read does not repaint downstream. Query-object reuse is likewise per-site:
  a parametric read may retain its exact prior query object without collapsing
  an equal query executed at another compiler-issued site.

  ## Render batches + `flush!` scope (S2d — 03 §3 invariant 6; Spec 006
  §Render-batch finalization)

  The sixth frozen invariant, stated correctly: A RENDER BATCH IS THE PENDING
  READ/RENDER WINDOW THAT ENDS AT THE NEXT HOST CHECKPOINT — the next CLJS host
  microtask, or an explicit headless/test flush. The boundary is the HOST's, not
  the router's: this scheduler has no hook from router drain finalization and
  observes no drain boundary at all (rf2-vxgfnd.166).

  An event/frame EPOCH is a commit-phase + diagnostic-evidence unit (one per
  dequeued event — Spec 002 §Drain versus event); it is NOT a React render
  boundary. A single run-to-completion drain may settle SEVERAL queued events,
  each committing its OWN epoch record, before the host regains control — and
  every one of those epochs coalesces into ONE render batch.

  What the boundary GUARANTEES:

    1. A synchronous run-to-completion drain CANNOT be split across batches —
       the window cannot close while the stack is still unwinding.
    2. N epochs/events settled within ONE drain coalesce into ONE batch.
    3. SEVERAL drains — or listener re-entry after a completed batch — that
       finish before the SAME host checkpoint MAY SHARE one batch. Two
       back-to-back `dispatch-sync!` calls in one JavaScript stack render
       once, not twice; so do nested cross-frame synchronous drains.

       Read that example precisely. Guarantee 3 is a PERMISSION — drains that
       finish before the same checkpoint MAY share a batch — and it stays a
       permission. The example is nonetheless UNCONDITIONAL because its own
       phrase `in one JavaScript stack` supplies the condition: a stack that
       has not yielded cannot have reached a host checkpoint (the microtask
       armed by the first mark cannot run until the stack unwinds), so both
       drains necessarily finish inside one window. The example is therefore a
       THEOREM of guarantees 1, 3 and 4 together — NOT a promotion of the
       permission into a general `separate drains always share` rule. Two
       drains with a real yield between them still render separately, by
       guarantee 4.
    4. Drains separated by a real HOST YIELD render separately.

  Guarantee 3's example held on the mounted React path all along. It was
  briefly retracted (rf2-kahkr) on a measurement — three back-to-back
  `dispatch-sync!` calls advancing the revision 0 -> 1 -> 2, with a control
  microtask observed running seemingly MID-CALL — that was REAL but
  MISATTRIBUTED.
  The yield was INSTRUMENTATION, not the router (rf2-i3dvj): the DEBUG
  call-site stamp emitted a runtime `cond->` into the caller's context, which
  the CLJS compiler lowered to `await (async function(){...})()` because the
  fixture's calls sat inside a `cljs.test/async` body. The `await` — not
  `dispatch-sync!` — reached the microtask checkpoint that flushed the
  previous mark. `dispatch-sync!` and the synchronously-observable call-site
  family (`dispatch`, `dispatch-sync`, `subscribe`) are SAME-STACK
  SYNCHRONOUS IN EVERY BUILD, dev included; call-site macros now splice only
  yield-free literals. Nothing about the scheduler changed, and no router,
  scheduler or drain-finalization seam was added: there was never a router
  question here to leave open.

  `One render batch per router drain` is RETIRED as normative. It remains
  merely the COMMON CASE — true exactly when callers yield between drains
  (guarantee 4) — and it is not a rule this scheduler enforces or could
  enforce without a drain-finalization seam that deliberately does not exist.

  The mechanism: sub deltas mark their cell dirty through constant-work
  `on-change` (never compute — I-5), carrying the moving frame's epoch as
  CAUSE EVIDENCE only. The cell enters a module-level DIRTY REGISTRY
  exactly once (a set, deduped by cell identity); a re-mark while already
  pending FOLDS IN regardless of its epoch tag — the pending flag is the
  coalescing key, the epoch tag is NEVER a second key. On CLJS ONE coalesced
  flush is armed by the FIRST dirty mark of a pending window on the host
  MICROTASK queue (`queue-microtask!` — `js/queueMicrotask`, NOT
  `goog.async.nextTick`, which is a macrotask): the microtask checkpoint runs
  after the current synchronous task unwinds and BEFORE the next paint, so the
  flush fires strictly after the stack drains — never between two queued
  events of the same drain, and always before a torn frame can show
  (rf2-vxgfnd.40); the JVM headless host has no async render loop, so it
  auto-schedules NOTHING and
  drains via the EXPLICIT `flush!` (07 §2's only flush idiom; SSR is
  one-shot) — one honest option per host. Either way, N epochs committed
  in one drain advance each dirty cell's revision ONCE and let React
  perform ONE read/render batch.

  Note precisely WHAT arms the window: the first mark, not the start of a
  drain — and what closes it: the host checkpoint, not the end of a drain.
  Nothing consults the router. So everything that marks a cell before that
  checkpoint folds into the same batch, whether it came from one drain, from
  several back-to-back synchronous drains, or from a listener re-entering
  after a batch already completed.

  Render SEPARATION therefore follows HOST CHECKPOINTS, not epochs and not
  drains: epochs settled before the same checkpoint share one render batch —
  however many drains produced them — and work separated by a real host yield
  renders separately. NO render count may be inferred from the number of
  event/frame epochs, nor from the number of drains. The push-economics
  bench's queued-cascade gate (a parent event that queues further events,
  proving one ViewCell notification and one React render for the whole batch —
  G-5/G-13) is wired with the bench in S2f, not here.

  SYNCHRONOUS forcing is scoped over that registry:

    - `(flush-frame! frame-id)` — the internal frame scope — flushes every root
      observing that frame (each dirty cell whose committed deps include
      the frame).
    - the GLOBAL all-roots flush is the TEST-ONLY `ui.test/flush!`
      spelling (`flush-pending!` here). It is the sole public test flush;
      there is no public production `re-frame.ui/flush!`.

  A scoped flush leaves out-of-scope cells pending — no epoch work leaks
  across roots. Flush is reentrancy-SAFE BY CONSTRUCTION: `flush-scope!`
  atomically drains-then-notifies (`swap-vals!`), so a notify-triggered
  re-entrant flush finds the registry already drained and cannot
  double-advance a cell. The DEV-tier `:rf.error/flush-in-open-epoch`
  signal — the DX guard naming a re-entrant flushSync-into-an-open-epoch
  misuse (03 §11; Spec 006 §Render-batch finalization) — is REFERENCED, not
  emitted by `ui.test/flush!`, before this registry is touched, with the
  Spec 009 catalogue row carrying the active frame + frame epoch.

  A synchronous forcing call re-drains to a fixed point (a commit-triggered
  re-dirty can enrol a cell AFTER the pass that flushed it), so that re-drain is
  BOUNDED by `converge-flush!` / `flush-convergence-budget` (rf2-0faipl): an
  unstable commit→re-dirty cycle that the single-pass ambient guards (dispatch
  drain-depth, React update-depth) cannot see across separate `flushSync`
  passes fails loud with the typed `:rf.error/flush-convergence-exceeded`
  diagnostic (pass + pending counts) instead of spinning forever. The
  first-party adapter's `flush-render!` and the test `flush!` rest on this ONE
  shared bound.

  ## The slice-scoped probe memo (S2d item 3 — 03 §3; Spec 006 §The
  slice-scoped probe memo)

  `sub-read` threads a SLICE-SCOPED pure memo (`obs/make-slice-memo`)
  into every `probe`, so N sibling rows probing one query compute shared
  derivation parents once per top-level render/execution slice, not once per
  row (the first-mount fan-out mitigation). The handle is created lazily on the
  first probe of a slice and DIES WITH ITS SLICE (rf2-vxgfnd.174): the JVM opens
  a thread-local per-render scope (`with-slice-memo`, discarded on return); CLJS
  shares the synchronous render pass through a module holder released at the
  MICROTASK checkpoint (`queue-microtask!`, aligned with the port's own table
  clear). How a slice is BOUNDED is per-host, so the CLJS slice's MAXIMUM
  lifetime is that microtask checkpoint: within one host-microtask window — even
  when an inverse microtask FIFO ordering lets a later synchronous render pass
  (or a caught-render retry) interpose before the clear drains — a probe may
  reuse the still-installed holder, but NO holder or table survives PAST the
  checkpoint into the next window (rf2-2g7pxq). The memo is an ECONOMY, never an
  authority — the commit evidence comparison (step 5) corrects any staleness
  before paint, and the exact `(frame, frame-epoch, registry-epoch)` +
  incarnation tag re-validates every reused table so an interposed later render
  at a MOVED epoch mints a fresh table rather than serving a stale value."
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
  v2 (rf2-vxgfnd.14): `read` on a node handle carries `:node-key`, which
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
;; a pretended same mechanism" (07 §2). On CLJS the landed React-context
;; carriage seeds the same door from the mounted override Provider:
;; `re-frame.ui.sub-overrides/use-current` reads the nearest Provider map
;; with ordinary `useContext`, and `re-frame.ui.viewcell` binds it around
;; each compiled body.
;; ---------------------------------------------------------------------------

(def ^:dynamic *sub-overrides*
  "Override door: a map of query-vector → pinned value, or nil. A HIT
  resolves the site to a `:story-override` target — the pinned value IS the
  resolution (no node), and commit acquires a STATIC handle
  (`:owned? false`, callback-free). Seeded by the JVM `ui.test/render`
  door (an explicit `binding`) and, on CLJS, by the landed React-context
  carriage — `re-frame.ui.sub-overrides` reads the nearest mounted override
  Provider and `re-frame.ui.viewcell` binds this var around each compiled
  body.

  ## The change token (opaque to the observation port — the target ABI)

  `resolve-override` LOWERS a HIT to the port's opaque
  `{:override-id :version}` change token. The port
  (`re-frame.substrate.observation`) never interprets either token, but the
  two obey a SPLIT equality law: `:override-id` is slot identity, compared
  by plain `=`; `:version` is the movement token, compared by the frozen
  `rf=` law (the port's core-local `node-value=` spelling), under which
  NaN-to-NaN RETAINS — the observable counterexample plain `=` gets wrong
  (an `=`-compared NaN version would retarget forever). This artefact's
  private lowering:

    - `:override-id` ← the QUERY — the override's stable slot identity
      (an `=` compare), so two overrides never collide and a site dedups
      by its slot.
    - `:version`     ← the VALIDATED value — the movement token, so
      `current?` (frozen `rf=` on version) retargets EXACTLY when the
      surfaced value MOVES under `rf=`, and retains when it does not
      (including a NaN replaced by NaN).

  Because `resolve-override` re-runs (and re-validates) every render, the
  semantics fall out of the token:

    - `rf=`-equal provider replacement (same validated value, NaN-to-NaN
      included) → version unchanged under the movement law → the
      kept-check retains the static handle (no retarget).
    - nested providers → the CLOSEST enclosing override wins (the innermost
      `*sub-overrides*` binding / React-context map) → its value is the token.
    - value movement under `rf=` → version differs → the site retargets to
      a fresh static handle carrying the new value.
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
;; Single-threaded on both hosts WITHIN one synchronous render; a compiled
;; view's children are ELEMENTS (rendered later by the host), never
;; synchronously nested calls, so renders never nest on one thread. ACROSS
;; threads the JVM Tier-1 host gives no such guarantee (parallel test
;; runners, fixture futures), so the slot is a DYNAMIC var — thread-local
;; under `binding`, exactly like every neighbouring render-path scope
;; (`*sub-overrides*`, `frame/*current-frame*`) — and two concurrent renders
;; can neither cross-record sites into each other's captures nor false-throw
;; the duplicate-sid guard (rf2-1llvoh). On single-threaded CLJS `binding`
;; compiles to the same save/restore the old atom hand-rolled.
;; ---------------------------------------------------------------------------

;; {:cell <cell> :capture <volatile> :owner <Thread|nil>} | nil
;; `:owner` is the JVM thread that opened the capture (nil on CLJS) — see
;; `ensure-capture-owner!` (rf2-vxgfnd.171). It rides INSIDE the conveyed value
;; because binding conveyance into `future`/`pmap` is the hazard it detects.
(def ^:private ^:dynamic *ambient* nil)

(defn- fresh-capture
  [generation]
  {:generation generation
   :order []
   :by-site {}})

(defn- target-key
  [target]
  (case (:kind target)
    :subscription   [:sub (:frame-id target) (:query target)]
    :story-override [:override (:override-id target)]))

(defn- record-site
  "Add one compiler-indexed lexical site to `cap`. Site identity—not target
  equality—is the ownership key, so two equal queries remain two owners. A
  repeated execution of one sid in a render is a compiler-contract violation
  and fails loudly: rendering a later value while committing the first target
  would otherwise create an incoherent dependency."
  [cap sid query target ev value]
  (if (contains? (:by-site cap) sid)
    (error/throw-error!
      :rf.error/ui-tree-malformed
      're-frame.ui.reactive/sub-read
      (str "compiler lexical site id " (pr-str sid)
           " executed more than once in one render capture — each finite "
           "reactive occurrence must have a distinct stable id")
      {:extra {:site-id sid :query query}})
    (-> cap
        (update :order conj sid)
        (assoc-in [:by-site sid] {:query query
                                  :target target
                                  :evidence ev
                                  :value value}))))

(defn site-records
  "The immutable `{sid -> render observation}` in capture `cap` (internal
  compiler/runtime/test seam)."
  [cap]
  (:by-site cap))

;; ---------------------------------------------------------------------------
;; The slice-scoped probe memo (S2d item 3; 03 §3; Spec 006 §The slice-scoped
;; probe memo)
;;
;; ONE memo handle per top-level render/execution slice, shared by every probe
;; in the slice so sibling rows compute shared derivation parents once (the
;; first-mount fan-out mitigation). The handle DIES WITH ITS SLICE — no handle or
;; table survives into a LATER render slice (rf2-vxgfnd.174). How a slice is
;; BOUNDED is per-host because the hosts' scheduling models genuinely differ:
;;
;;   - JVM: a THREAD-LOCAL render-slice scope (`*slice*`, opened by
;;     `with-slice-memo`). EVERY public Tier-1 JVM render entry opens one around
;;     the render thunk — `re-frame.ui.tree/render` and `ui.test/render`'s
;;     view-reference / literal / plan-bearing routes (all three converge on
;;     `render-with-opts` / `render-plan-bearing`) — and the reactive host entry
;;     `with-capture` opens one around each ViewCell render. The box holds
;;     this slice's handle; the `binding` discards it on scope exit, so the
;;     prior table is unreachable with NO `reset-scheduler!`/tag-change
;;     dependency, two sequential executor tasks never share a table, and
;;     concurrent renders on distinct threads stay isolated. A bare probe
;;     outside any render slice gets a fresh per-call handle (no cross-call
;;     retention).
;;   - CLJS: a single module holder shares one handle across every probe of a
;;     synchronous render pass (single-threaded, so no thread-local scope is
;;     needed), released at the MICROTASK checkpoint (`queue-microtask!`,
;;     aligned with the port's own table clear — NOT the `interop/next-tick`
;;     macrotask, which would leave a dead slice's holder live for one more
;;     host turn) under a CAS guard so a stale clear cannot erase a newer holder.
;;     That checkpoint is the CLJS slice's HONEST boundary — its MAXIMUM
;;     lifetime. queue-microtask! is FIFO, so a microtask enqueued BEFORE the
;;     first probe drains BEFORE our clear (an inverse ordering): if it performs
;;     a later synchronous render, that render's probes see the still-installed
;;     holder and REUSE it. This reuse is a bounded, safe economy, not a leak —
;;     the whole host-microtask window IS one CLJS slice, and the exact
;;     `(frame, frame-epoch, registry-epoch)` + incarnation tag re-validates
;;     every table hit, so an interposed later render at a MOVED epoch installs a
;;     fresh table (never a stale value). A caught-render retry is the same case:
;;     synchronous, before the checkpoint, so it too is within-window sharing.
;;     What the checkpoint guarantees is the LAW: no holder or table survives
;;     PAST it into the next window (rf2-2g7pxq).
;;
;; The memo is an ECONOMY only — commit step 5 corrects any staleness before
;; paint, and the incarnation-complete tag (rf2-vxgfnd.160) keeps a commit-free
;; reader correct on its own — so a single per-slice handle is sufficient.
;; ---------------------------------------------------------------------------

#?(:cljs (declare queue-microtask!))

(def ^:dynamic ^:private *slice*
  "When thread-bound (inside a render slice opened by `with-slice-memo`), a
  volatile box holding THIS slice's probe-memo handle — created lazily, shared
  by every probe of the slice, and discarded with the `binding` when the scope
  returns. nil outside a render slice. Effective on the JVM only: the CLJS host
  shares a synchronous render pass through the module holder below and never
  binds this var."
  nil)

(def ^:private slice-memo*
  "CLJS module holder for the current SLICE's probe-memo handle (the JVM
  per-render scope is the thread-local `*slice*` above). `current-slice-memo`
  installs the handle lazily and arms a CAS clear at the host MICROTASK
  checkpoint. That checkpoint is the holder's MAXIMUM lifetime, and what it
  guarantees is exactly this: NO holder or table survives PAST it into the next
  window (rf2-2g7pxq).

  What is NOT guaranteed — do not build on it: that the holder spans exactly one
  synchronous render pass. Its scope is the host-microtask WINDOW, so a later
  synchronous render (or a caught-render retry) interposing BEFORE the clear
  drains reuses the still-installed handle — a bounded economy, re-validated on
  every table hit by the handle's own exact tag. See `current-slice-memo`."
  (atom nil))

(defn ^:no-doc with-slice-memo
  "THE Tier-1 JVM render-slice runner. Open a render/execution slice for
  `thunk`: every `sub-read`/probe run under it shares ONE slice-memo handle,
  discarded when `thunk` returns, so N sibling probes of one cold derived parent
  compute it once per render (the first-mount fan-out mitigation) yet a LATER
  render recomputes it. Established at every real public Tier-1 render boundary —
  `re-frame.ui.tree/render` and `ui.test/render`'s view/literal/plan-bearing
  routes — plus the reactive host entry `with-capture`, so the public headless
  path shares one memo exactly as the browser/Tier-3 path does. Re-entrant —
  a nested call reuses the enclosing slice rather than opening a second one, so a
  Tier-1 render entry and the `with-capture` inside it share ONE slice, and a
  bare `sub-read` OUTSIDE any scope gets a fresh per-call handle. On CLJS this is
  a passthrough: the single-threaded host shares a synchronous render pass
  through the module holder (`slice-memo*`), microtask-released, and needs no
  per-scope binding."
  [thunk]
  #?(:clj  (if (thread-bound? #'*slice*)
             (thunk)
             (binding [*slice* (volatile! nil)] (thunk)))
     :cljs (thunk)))

(defn- current-slice-memo
  "The current slice's probe-memo handle, reused across every probe of the slice
  and created lazily. Inside a `with-slice-memo` scope the handle lives in the
  thread-local `*slice*` box and is discarded when the scope returns
  (rf2-vxgfnd.174) — so the prior JVM table is unreachable with no reset/tag
  dependency. Outside any scope: on CLJS the module holder shares the synchronous
  render pass and a MICROTASK checkpoint releases it — that checkpoint is the
  holder's MAXIMUM lifetime, so a later render interposing within the same
  host-microtask window (an inverse microtask FIFO drains a pre-enqueued
  callback before our clear) reuses the still-installed holder as a bounded, safe
  economy, but nothing survives PAST the checkpoint into the next window
  (rf2-2g7pxq); on the JVM a bare probe gets a fresh per-call handle (no
  cross-call/cross-render retention). The handle's own
  `(frame, frame-epoch, registry-epoch)` tag PLUS the exact frame-incarnation
  token (rf2-vxgfnd.160) still invalidate a stale table within a slice — so even
  the interposed within-window reuse mints a fresh table at a moved epoch rather
  than serving a stale value. The memo is an ECONOMY — commit step 5 corrects
  staleness before paint, and the incarnation-complete tag keeps a commit-free
  reader correct on its own."
  []
  (if-some [box *slice*]
    (or @box (let [h (obs/make-slice-memo)] (vreset! box h) h))
    #?(:cljs
       (or @slice-memo*
           (let [h (obs/make-slice-memo)]
             (reset! slice-memo* h)
             ;; Release OUR handle at the microtask checkpoint — the CLJS slice's
             ;; MAXIMUM lifetime. queue-microtask! is FIFO, so a callback enqueued
             ;; before this probe drains before this clear; if it renders, its
             ;; probes reuse `h` (within-window bounded economy, tag-guarded).
             ;; CAS so a stale clear cannot erase a NEWER holder a later window
             ;; installed; the clear itself is what stops `h` surviving PAST the
             ;; checkpoint into the next window (rf2-2g7pxq).
             (queue-microtask! (fn [] (compare-and-set! slice-memo* h nil)))
             h))
       :clj
       (obs/make-slice-memo))))

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
  ;;    :disconnect-provisional? bool ; DEV-only and ABSENT in production
  ;;                             ;   (rf2-vxgfnd.44): a just-emitted
  ;;                             ;   :disconnected interval that has NOT yet
  ;;                             ;   settled past its synchronous checkpoint. A
  ;;                             ;   reconnect while still provisional is
  ;;                             ;   UNSETTLED/same-checkpoint evidence — a
  ;;                             ;   StrictMode replay OR consecutive synchronous
  ;;                             ;   commits, indistinguishable at the host, left
  ;;                             ;   :unknown (rf2-vxgfnd.164);
  ;;                             ;   `settle-disconnect!` clears it. Production
  ;;                             ;   has no field, lookup, or provisional branch
  ;;    :committed {sid -> {:query exact-query :target target :value value
  ;;                        :handle handle|nil}}
  ;;                             ; lexical site records. `:handle nil` survives
  ;;                             ; disconnect for exact query/value reuse and
  ;;                             ; hidden-cell frame attribution; absence means
  ;;                             ; the site was conditional/dropped
  ;;    :revision  int           ; get-snapshot returns this (useSyncExternalStore)
  ;;    :dirty?    bool          ; pending-notification flag (drain coalescing)
  ;;    :evidence  ev|nil        ; DEBUG-only bounded causal evidence for the
  ;;                             ;   pending window (see `fold-evidence`); nil
  ;;                             ;   in production (elided) + between flushes
  ;;    :listeners {k -> fn}     ; useSyncExternalStore subscribers
  ;;    :intervals [interval]     ; lifecycle facts (dev/tool; 03 §4)
  ;;    :pending-commit-causes [{:cause kind …detail} …] ; DEBUG-only APPEND-ONLY
  ;;                             ;   vector of PORT folds (:subscription/:hmr/
  ;;                             ;   :disposed), one distinct entry per move, captured
  ;;                             ;   at the cause SITE (`note-commit-cause` in
  ;;                             ;   `enrol-dirty-window!`). A subscription entry
  ;;                             ;   carries target/query/frame-id + version from->to
  ;;                             ;   + epoch; the rest are bare. A connected commit
  ;;                             ;   drains only entries AT/BEFORE the render's
  ;;                             ;   captured :cause-waterline into :rf.view/causes
  ;;                             ;   (coalesced per identity) and keeps the residual
  ;;                             ;   (later folds) for the next commit (rf2-eww3k /
  ;;                             ;   rf2-sy536, atomic take/publish). ABSENT in
  ;;                             ;   production (elided) and when no fold is pending
  ;;    :local-state-committed? bool ; DEBUG-only COMMIT-TIME flag: a substrate local
  ;;                             ;   write React actually committed (`note-local-state!`).
  ;;                             ;   Read + cleared by the next connected commit as the
  ;;                             ;   :local-state cause (NOT waterline-fenced — it is a
  ;;                             ;   commit-time fact). ABSENT in production + when unset
  ;;    :commit-record {…}|nil}  ; DEBUG-only S6 committed-instance record from
  ;;                             ;   the most-recent CONNECTED commit (Ruling 1/2;
  ;;                             ;   integer render-key + per-observation
  ;;                             ;   observations + the per-commit :rf.view/causes
  ;;                             ;   vector). ABSENT in production (elided) and nil
  ;;                             ;   before the first connect
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
     (atom
      (cond-> {:view-id        view-id
               :generation     generation
               :lifecycle      :fresh
               :root           nil
               :committed      {}
               :revision       0
               :dirty?         false
               :evidence       nil
               :listeners      {}
               :intervals      []}
        interop/debug-enabled? (assoc :disconnect-provisional? false))))))

;; ---------------------------------------------------------------------------
;; Dev-only view slots — stable shell + exact body revision (03 §10)
;; ---------------------------------------------------------------------------
;;
;; ONE per-view slot is the whole HMR authority.  It owns the stable component
;; identities, current implementation descriptor, revision store/listeners, and
;; the hook-signature → remount decision.  There is deliberately no parallel
;; registrar cache or render-time var lookup.
;;
;; Every successful registration advances BODY REVISION, including a
;; same-signature edit.  Only a changed hook signature advances REMOUNT
;; GENERATION.  The stable outer shell observes body revision; its stable inner
;; body is keyed by remount generation.  Thus a same-signature update runs the
;; new body on the existing Fiber, while an incompatible hook edit remounts that
;; Fiber exactly once without changing the public shell identity.
;;
;; The slot is dev-only in the CLJS emitter: production takes a literal
;; `goog.DEBUG=false` branch directly to `React.memo`, leaving this registry and
;; all listener/dynamic-descriptor machinery unreachable to Closure DCE.

(defonce ^:private view-generations
  ;; view-id -> one atomic private HMR slot. `defonce` survives namespace
  ;; reload. `reset-scheduler!` deliberately NEVER clears it: already-exported
  ;; stable shells retain this slot as their live render authority.
  (atom {}))

(defn ensure-view-shells!
  "Return the stable shell pair for `view-id`, creating it once with
  `make-shells` when absent. `make-shells` returns `{:outer x :inner y}`.

  The winning pair is published in the SAME per-view slot later used for the
  descriptor/revisions/listeners. A losing speculative pair is unreachable
  garbage (possible only under a concurrent JVM test; the CLJS host is
  single-threaded)."
  [view-id make-shells]
  (let [slots (swap! view-generations update view-id
                     (fn [slot]
                       (if (:hmr-outer slot)
                         slot
                         (let [{:keys [outer inner]} (make-shells)]
                           (merge {:hmr-registered?       false
                                   :hmr-body-revision     -1
                                   :hmr-remount-generation 0
                                   :hmr-listeners         {}}
                                  slot
                                  {:hmr-outer outer
                                   :hmr-inner inner})))))]
    (let [slot (get slots view-id)]
      {:outer (:hmr-outer slot)
       :inner (:hmr-inner slot)})))

(defn prepare-view-descriptor!
  "Prepare a descriptor/revision publication without notifying mounted shells.

  This is the first half of the DEV view-registration transaction. It makes
  the new descriptor and revisions visible BEFORE `registrar/register!` fires
  its synchronous registration/replacement hooks, so no hook can observe a new
  manifest paired with an old (or nil) render/comparator. `publication-token`
  is private transaction identity; listeners may still subscribe while the
  registrar call is in progress without invalidating the transaction.

  Call `commit-view-descriptor!` after registrar success or
  `rollback-view-descriptor!` if it throws."
  [view-id hook-signature descriptor]
  (let [publication-token (atom nil)
        [old-slots slots]
        (swap-vals! view-generations update view-id
                    (fn [slot]
                      (let [registered? (boolean (:hmr-registered? slot))
                            changed?    (and registered?
                                             (not= hook-signature
                                                   (:hmr-hook-signature slot)))]
                        (-> (merge {:hmr-body-revision      -1
                                    :hmr-remount-generation 0
                                    :hmr-listeners          {}}
                                   slot)
                            (assoc :hmr-registered? true
                                   :hmr-hook-signature hook-signature
                                   :hmr-descriptor descriptor
                                   :hmr-publication-token publication-token)
                            (update :hmr-body-revision inc)
                            (cond-> changed?
                              (update :hmr-remount-generation inc))))))
        prepared (get slots view-id)]
    {:view-id view-id
     :publication-token publication-token
     :before (get old-slots view-id)
     :prepared prepared}))

(defn- report-hmr-listener-escape!
  "Surface the FIRST contained listener failure from one DEV publication.

  Reporting is deliberately bounded to one warning per publication and is
  itself contained: diagnostic machinery can neither change a committed HMR
  outcome nor replace the registrar failure that caused a rollback."
  [view-id phase slot error]
  #?(:cljs
     (try
       (when (exists? js/console)
         (.warn js/console
                (str "[re-frame.ui] an HMR publication listener threw while "
                     "notifying view " (pr-str view-id) " after " (name phase)
                     " revision " (:hmr-body-revision slot)
                     " — the throw was CONTAINED, every snapshotted sibling "
                     "still ran, and the publication outcome remains "
                     "authoritative. Cause: "
                     (if (nil? error)
                       "nil"
                       (error/ex-message-safe error)))))
       (catch :default _ nil))
     :clj nil))

(defn- notify-view-listeners!
  "Notify a snapshot of `slot`'s listeners, containing every failure.

  The explicit `:failed?` bit preserves first-failure identity even when
  JavaScript throws false or nil. Later failures are contained but do not
  replace the bounded diagnostic's first cause."
  [view-id phase slot]
  (let [listeners (vec (vals (:hmr-listeners slot)))
        first-failure
        (reduce (fn [failure listener]
                  (try
                    (listener)
                    failure
                    (catch #?(:clj Throwable :cljs :default) error
                      (if (:failed? failure)
                        failure
                        {:failed? true :error error}))))
                {:failed? false :error nil}
                listeners)]
    (when (and interop/debug-enabled? (:failed? first-failure))
      (report-hmr-listener-escape!
       view-id phase slot (:error first-failure)))
    nil))

(defn commit-view-descriptor!
  "Commit a prepared view publication and notify mounted shells exactly once.

  A re-entrant registration for the same id supersedes an older transaction;
  only the transaction whose token is still current may notify. Notification
  walks a pre-delivery listener snapshot and contains each listener failure,
  so every sibling runs and no observer can turn a successful commit into a
  registrar failure. A re-entrant winner remains authoritative while the outer
  snapshot finishes. Returns the complete current slot snapshot."
  [{:keys [view-id publication-token]}]
  (let [[old-slots slots]
        (swap-vals! view-generations update view-id
                    (fn [slot]
                      (if (identical? publication-token
                                      (:hmr-publication-token slot))
                        (dissoc slot :hmr-publication-token)
                        slot)))
        current-before (get old-slots view-id)
        committed? (identical? publication-token
                                (:hmr-publication-token current-before))
        published (get slots view-id)]
    (when committed?
      (notify-view-listeners! view-id :commit published))
    published))

(defn rollback-view-descriptor!
  "Roll back a prepared descriptor after registrar failure.

  Rollback is conditional on transaction identity, so it cannot overwrite a
  newer re-entrant registration. Revisions are globally monotone even across
  failure: restore the prior descriptor at a fresh body revision and notify
  once, making every render of the provisional descriptor stale. If the failed
  candidate exposed a different hook shape, restoration advances remount
  generation again. A failed first registration becomes an unavailable
  tombstone at a fresh revision. Listener subscriptions made in flight are
  retained. Compensation notification uses the same snapshotted, per-listener
  containment as commit, so observer failures cannot replace the primary
  registrar failure or starve siblings. Returns true only when this publication
  performed the compensation; a stale publication returns false."
  [{:keys [view-id publication-token before]}]
  (let [[old-slots slots]
        (swap-vals!
         view-generations update view-id
         (fn [slot]
           (if (identical? publication-token
                           (:hmr-publication-token slot))
             (let [prior (or before {})
                   prior-registered? (boolean (:hmr-registered? prior))
                   shape-observed?
                   (or (not prior-registered?)
                       (not= (:hmr-hook-signature prior)
                             (:hmr-hook-signature slot)))
                   restored
                   (-> prior
                       (assoc :hmr-listeners (:hmr-listeners slot {})
                              :hmr-body-revision
                              (inc (:hmr-body-revision slot))
                              :hmr-remount-generation
                              (cond-> (:hmr-remount-generation slot)
                                shape-observed? inc)))]
               (if prior-registered?
                 restored
                 (assoc restored
                        :hmr-registered? false
                        :hmr-hook-signature nil
                        :hmr-descriptor nil)))
             slot)))
        current-before (get old-slots view-id)
        rolled-back? (identical? publication-token
                                  (:hmr-publication-token current-before))
        restored (get slots view-id)]
    (when rolled-back?
      (notify-view-listeners! view-id :rollback restored))
    rolled-back?))

(defn register-view-descriptor!
  "Publish one descriptor directly through the same prepare/commit path used
  by the client registrar transaction. This is the headless/JVM test seam.

  Body revision advances on every call. Remount generation advances only when
  an already-registered slot changes hook signature."
  [view-id hook-signature descriptor]
  (-> (prepare-view-descriptor! view-id hook-signature descriptor)
      (commit-view-descriptor!)))

(defn register-view-generation!
  "Headless test/JVM seam for the same registration decision as
  `register-view-descriptor!`. Returns BODY REVISION. It never creates a second
  authority: the existing descriptor (if any) is republished in the one slot."
  [view-id hook-signature]
  (:hmr-body-revision
   (register-view-descriptor!
    view-id hook-signature
    (get-in @view-generations [view-id :hmr-descriptor]))))

(defn view-generation
  "The current BODY REVISION for `view-id` (0 when never registered). Kept
  under the historical internal name while callers migrate; this is no longer
  the remount key."
  [view-id]
  (max 0 (get-in @view-generations
                 [view-id :hmr-body-revision]
                 0)))

(defn registered-view-revision
  "The authoritative body revision for a successfully registered `view-id`, or
  nil when the test/direct-call path has no registered slot."
  [view-id]
  (let [slot (get @view-generations view-id)]
    (when (:hmr-registered? slot)
      (:hmr-body-revision slot))))

(defn view-remount-generation
  "The current hook-incompatibility remount key for `view-id` (tool/test read)."
  [view-id]
  (get-in @view-generations [view-id :hmr-remount-generation] 0))

(defn view-descriptor
  "The current implementation descriptor for `view-id` (dev render path)."
  [view-id]
  (get-in @view-generations [view-id :hmr-descriptor]))

(defn view-shells
  "The stable `{:outer :inner}` identities for `view-id` (tool/test read)."
  [view-id]
  (let [slot (get @view-generations view-id)]
    {:outer (:hmr-outer slot)
     :inner (:hmr-inner slot)}))

(defn subscribe-view!
  "Subscribe `listener` to successful body publications for `view-id`; return
  an idempotent unsubscribe thunk. This is the stable shell's minimal
  useSyncExternalStore store."
  [view-id listener]
  (let [k (gensym "rf-ui-hmr-listener")]
    (swap! view-generations assoc-in
           [view-id :hmr-listeners k] listener)
    (fn unsubscribe []
      (swap! view-generations update-in
             [view-id :hmr-listeners] dissoc k))))

(defn advance-generation!
  "Advance a LIVE `cell` to a newer BODY REVISION before capture. A revision
  bump makes an in-flight capture from the prior body stale at commit step 1.
  Monotone: a no-op unless `generation` exceeds the cell's current revision."
  [^ViewCell cell generation]
  (let [st (state cell)]
    (when (> generation (:generation @st))
      (swap! st assoc :generation generation)))
  cell)

(defn generation
  "The cell's current view-body generation (tool/test read)."
  [^ViewCell cell]
  (:generation @(state cell)))

;; ---- read + query stabilization ---------------------------------------------

;; ---- render-capture thread ownership (rf2-vxgfnd.171) -----------------------
;;
;; The ambient capture is a `volatile!` reached through a `^:dynamic` Var. On
;; CLJS that is exactly right: one thread, and `binding` buys save/restore for
;; free. On the JVM the Var ALSO gives two concurrent top-level renders disjoint
;; captures (rf2-1llvoh) — but Clojure CONVEYS dynamic bindings into `future` /
;; `pmap` workers. A render body that forks its site reads and joins them hands
;; every child thread the SAME non-thread-safe volatile, and their `vswap!`s
;; race: sites are silently lost and the surviving order is nondeterministic.
;;
;; The implementation contract is a deterministic, compiler-ordered capture, and
;; a capture with sites missing flows into commit/release logic as MISSING
;; OWNERSHIP. Losing sites quietly is strictly worse than refusing an
;; unsupported parallel render body, so the contract is enforced rather than
;; engineered around. (Supporting parallel render bodies would need an atomic
;; capture plus a deterministic site order; that is a different design, not a
;; guard.)
;;
;; The owner travels WITH the capture value, in the ambient map itself — NOT in
;; thread-local state. That is the whole point: conveyance is precisely the
;; mechanism at fault, so a check that lived in a `^:dynamic` Var or a
;; ThreadLocal would either be conveyed along with the capture (seeing the
;; child's own state and agreeing) or fail to reach the child at all. A value
;; carried inside the conveyed map is read identically by every thread that
;; receives it, so the child compares the ORIGINATING thread against its own and
;; disagrees. This holds for any conveyance route — `future`, `pmap`,
;; `bound-fn`, an executor submission — because none of them rewrite the value.
;;
;; Separate top-level renders on different threads each open their OWN
;; `with-capture` and so record their own owner: they stay valid and isolated.

(defn- capture-owner
  "The thread that owns a render capture, recorded when `with-capture` opens it.
  `nil` on CLJS — a single-threaded host has no question to ask, and the guard
  below compiles away entirely."
  []
  #?(:clj  (Thread/currentThread)
     :cljs nil))

(defn- ensure-capture-owner!
  "Reject a capture mutation attempted from a thread other than the one that
  opened the capture. Called BEFORE any probe or `vswap!`, so a rejected fork
  commits no partial ownership.

  JVM-only by construction: on CLJS `owner` is always `nil` and this whole body
  reader-conditionals away."
  [where owner sid query-or-descriptor]
  #?(:clj
     (when (and (some? owner)
                (not (identical? owner (Thread/currentThread))))
       (error/throw-error!
        :rf.error/ui-tree-malformed
        where
        (str "render capture is single-threaded, but this site ran on a child "
             "thread of the render that opened it — a render body may not fork "
             "its site reads (future/pmap convey the ambient capture, and the "
             "forked writes race, silently losing sites). Read the sites on the "
             "render thread; fork only work that performs no site reads.")
        {:extra {:site-id        sid
                 :owner-thread   (.getName ^Thread owner)
                 :current-thread (.getName (Thread/currentThread))
                 :query          query-or-descriptor}}))
     :cljs nil))

(defn sub-read
  "The one bridge `(sub query)` lowers to on both hosts. The compiler calls
  `(sub-read sid query)`; `sid` is the stable lexical ownership key. Resolves
  the site's target (override door → ambient frame), probes ownership-free,
  and returns the value:

    - Inside a live cell render (ambient capture present), RECORDS the site
      by sid. A fresh candidate query that is `rf=` to that same site's prior
      query is replaced by the PRIOR EXACT query object BEFORE override/target
      resolution. The value is then stabilized against the same site's prior
      exact value.
    - Outside a cell, either arity is a one-shot headless read: freshly probe,
      with no ownership/capture. The legacy one-argument arity is deliberately
      illegal under an ambient capture so a compiler/test cannot silently lose
      lexical identity.

  Fail-loud rides the port: `:rf.error/no-such-sub` on an unknown entry
  sub, `:rf.error/frame-destroyed` against a destroyed frame."
  ([query]
   (if (some? (:cell *ambient*))
     (error/throw-error!
       :rf.error/ui-tree-malformed
       're-frame.ui.reactive/sub-read
       (str "one-argument sub-read reached an active ViewCell capture — "
            "compiled render reads must carry their lexical site id")
       {:extra {:query query}})
     (sub-read nil query)))
  ([sid query]
   (let [{:keys [cell capture owner]} *ambient*
         _ (when (and (some? cell) (nil? sid))
             (error/throw-error!
               :rf.error/ui-tree-malformed
               're-frame.ui.reactive/sub-read
               (str "nil lexical site id reached an active ViewCell capture — "
                    "compiled render reads must carry a non-nil site id")
               {:extra {:query query}}))
         ;; Reject a conveyed child thread BEFORE the probe, so a forked read
         ;; neither mutates the capture nor performs observation work whose
         ;; ordering it could not honour (rf2-vxgfnd.171).
         _ (when (some? cell)
             (ensure-capture-owner! 're-frame.ui.reactive/sub-read
                                    owner sid query))
         prior-record (when (some? cell)
                        (get (:committed @(state cell)) sid))
         prior-query  (:query prior-record)
         query*       (if (and prior-record (eq/rf= query prior-query))
                        prior-query
                        query)
         ;; Query stabilization MUST precede both the override door and target
         ;; resolution: adapters may key their caches by exact query identity.
         override     (resolve-override query*)
         site-ctx     (cond-> {:query-v query*}
                        (some? override) (assoc :override override))
         target       (obs/resolve-target site-ctx)
         ev           (obs/probe target (current-slice-memo))
         v            (:value ev)]
     (if (some? cell)
       (let [prior-value (:value prior-record)
             v*          (if (and prior-record (eq/rf= v prior-value))
                           prior-value
                           v)]
         (vswap! capture record-site sid query* target ev v*)
         v*)
       v))))

(defn with-capture
  "Run `thunk` (a compiled view body) under a fresh ambient capture and return
  `[host-element capture]`.

  Ownership-free: the render acquires NO ref-count, watch, or cache node, so an
  abandoned render (a thunk whose result the host discards — StrictMode
  double-render, time-sliced tear-off) leaks ZERO ownership and publishes ZERO
  shared capture state. React retains the pair only on the selected finished
  Fiber: its layout-effect closure commits that exact render's capture. A later
  speculative render therefore cannot replace the input of an earlier render
  that React actually commits. StrictMode's effect mount→cleanup→remount reuses
  the selected effect closure, so both mounts reconcile the same immutable
  capture without a shared slot.

  The ambient slot is a dynamic var: `binding` gives the save/restore for
  free on single-threaded CLJS and THREAD-LOCAL isolation on the JVM, so two
  concurrent Tier-1 renders own disjoint captures (rf2-1llvoh). The JVM ALSO
  conveys that binding into `future`/`pmap` workers, which would hand child
  threads of ONE render the same non-thread-safe capture — so the capture
  records its owning thread and a conveyed site read is refused rather than
  allowed to race (rf2-vxgfnd.171).

  Each render is ALSO a slice-memo scope: on the JVM `with-slice-memo` opens a
  thread-local per-render slice (discarded on return) so sibling probes share a
  derivation parent within the render yet a later render recomputes it
  (rf2-vxgfnd.174); on CLJS this is a passthrough (the module holder owns the
  synchronous pass), so the hot path allocates nothing extra."
  [^ViewCell cell thunk]
  (let [s0  @(state cell)
        ;; DEBUG-only cause WATERLINE (rf2-eww3k): the count of pending port folds
        ;; at the instant this render begins. The commit drains only folds at/before
        ;; it, so a fold arriving after this render (a movement that drove no
        ;; already-rendered commit) is fenced to the render it actually drove. DCE'd
        ;; whole in production (`interop/debug-enabled?` is build-constant).
        cap (volatile! (cond-> (fresh-capture (:generation s0))
                         interop/debug-enabled?
                         (assoc :cause-waterline (count (:pending-commit-causes s0)))))]
    (binding [*ambient* {:cell cell :capture cap :owner (capture-owner)}]
      #?(:clj  (with-slice-memo (fn [] (let [el (thunk)] [el @cap])))
         :cljs (let [el (thunk)] [el @cap])))))

(defn ambient-cell
  "The ViewCell of the ambient render capture, or nil outside a render. The
  `re-frame.ui.hooks` local-state bridge reads this at setter-mint time (which
  runs inside `with-capture`) so a later host-only `set!`/`update!` can attribute
  its re-render to the owning cell (`note-local-state!`; Ruling 2 :local-state).
  Internal runtime seam."
  []
  (:cell *ambient*))

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

(defn listener-count
  "The current host-subscriber count for `cell` (internal tool/test read).
  Reads the existing listener map on demand; it adds no production counter or
  notification-path work."
  [^ViewCell cell]
  (count (:listeners @(state cell))))

(defn- notify-listeners!
  "Deliver the revision notification to EVERY listener of `cell`. Each
  listener is contained in its own try/catch so one throwing consumer cannot
  starve its sibling listeners on the same cell (mirroring the observation
  port's per-handle disposal containment); the FIRST escape is rethrown AFTER
  every listener has been delivered — surfaced, never starving (rf2-owwbyl).

  Escape PRESENCE is tracked independently of the escape's own value: JavaScript
  permits throwing falsy values (`throw null`, `throw false`), so the first
  escape is captured in a truthy wrapper `{:escaped? true :error e}` rather than
  as the raw value. Using the value's truthiness as the presence test would lose
  a thrown `nil` and let a later truthy escape overwrite a first thrown `false`,
  violating the first-escape order/identity contract (rf2-vxgfnd.172). The
  wrapper is allocated only when a listener actually throws, so the happy path
  carries no overhead."
  [^ViewCell cell]
  (let [escape (reduce (fn [acc f]
                         (try
                           (f)
                           acc
                           (catch #?(:clj Throwable :cljs :default) e
                             (or acc {:escaped? true :error e}))))
                       nil
                       (vals (:listeners @(state cell))))]
    (when escape
      (throw (:error escape)))))

(defn- advance-revision!
  "Advance the cell's revision and notify subscribers — the host re-reads
  getSnapshot, sees the new revision, and re-renders. From step 8 this runs
  synchronously inside the layout commit (React corrects BEFORE paint)."
  [^ViewCell cell]
  (swap! (state cell) update :revision inc)
  (notify-listeners! cell))

;; ---- render-batch coalescing + the notification scheduler (S2d) -------------
;;
;; `on-change` is constant-work (mark-dirty; never compute — I-5). The moving
;; epoch/cause rides as EVIDENCE only (bounded + DEBUG-gated — see the
;; evidence plane below; production carries just the pending flag). A cell
;; enters the module DIRTY REGISTRY exactly once per flush boundary (the set
;; dedups by identity; a re-mark while pending folds in regardless of epoch
;; tag). N epochs
;; committed in one run-to-completion drain therefore advance the cell ONCE
;; at flush — the render batch boundary is the HOST CHECKPOINT (the next CLJS
;; microtask, or an explicit headless/test flush), NOT epoch close and NOT
;; drain completion: nothing here observes the router (rf2-vxgfnd.166).
;; On CLJS one coalesced flush is armed by the FIRST dirty mark of the pending
;; window on the host MICROTASK queue (`queue-microtask!`), which runs after
;; the current synchronous task unwinds and BEFORE the next paint — so a
;; watch-fired movement is corrected before the host can show a torn frame
;; (rf2-vxgfnd.40; 03 §3). Any number of drains that complete before that
;; checkpoint share the window; a real host yield separates batches.
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
  ;; The set of currently-CONNECTED ViewCells — the connected input to a
  ;; frame-destroy sweep (`teardown-frame!`). A cell enrols on `connect!`,
  ;; when it starts observing subscriptions, and leaves on `disconnect!` (React
  ;; unmount / Activity hide) or `teardown!` (it goes :dead). Its committed
  ;; subscription handles define reactive observation/flush scope. A disconnected
  ;; cell leaves the set, so an unmounted cell never lingers here (no retention
  ;; leak). `defonce` (module-lived); tests clear it via `reset-scheduler!`.
  (atom #{}))

(defonce ^:private root-cells
  ;; ROOT-INCARNATION OWNERSHIP: `incarnation -> <weak membership set>`. Every
  ;; ViewCell attached to a root (`attach-root!`, the mount seam) enrols here
  ;; under its root's incarnation and STAYS enrolled across a transient Activity
  ;; hide — a hide removes the cell from `live-cells` (it holds no committed
  ;; deps) but NOT from its root membership. This is the piece
  ;; `teardown-collector` alone cannot supply: a cell hidden by React Activity
  ;; BEFORE the root's teardown window is armed already left
  ;; `:fresh`/`:connected`, so its cleanup can never enrol it in the window, and
  ;; it would otherwise linger `:disconnected {:reason :unknown}` and
  ;; RECONNECTABLE after its root is gone (rf2-vxgfnd.85). `teardown-root!`
  ;; consults this registry to reap those already-hidden cells alongside the
  ;; ones the window captures.
  ;;
  ;; Membership is WEAK (rf2-mc62sp): an ordinary React reconciliation unmount
  ;; (conditional subtree, route change, keyed-list eviction) runs only the
  ;; effect cleanup → `disconnect!`, which deliberately never detaches (hide vs
  ;; unmount are indistinguishable there — 03 §4), so a STRONG registry would
  ;; pin every ordinarily-unmounted cell — with its retained committed site
  ;; values — for the root's whole lifetime: unbounded production memory growth
  ;; per UI churn. Weak membership keeps both consumers exactly correct: a
  ;; genuinely HIDDEN cell is strongly reachable from React's retained fiber
  ;; (the `useRef` in `use-cell`), so its entry lives precisely as long as
  ;; Activity retention and stays discoverable for `teardown-root!` /
  ;; `teardown-frame!`; a reconciliation-unmounted cell becomes unreachable
  ;; (its handles were already released at `disconnect!`, and nothing can ever
  ;; reconnect a cell nothing references), so it collects and its entry clears
  ;; — the teardown scans lose nothing, per 03 §4 "the cell is garbage". The
  ;; per-host weak plumbing (`make-weak-member-set` etc.) lives beside
  ;; `attach-root!`; `detach-root!` on final `teardown!` stays the
  ;; DETERMINISTIC fast path, and an incarnation's entry is dropped the moment
  ;; its last cell deterministically leaves (or, on CLJS, when the
  ;; finalization reaper clears the last collected member) — repeated
  ;; mount/hide/unmount cycles never grow a historical registry. The
  ;; incarnation is a FRESH per-mount identity (`make-root-incarnation`), NOT
  ;; the reusable root-id, so a stale teardown can never reap the cells of a
  ;; replacement root mounted under the same root-id. `defonce`
  ;; (module-lived) — and because `defonce` carries the VALUE across a hot
  ;; reload, a representation change must migrate the surviving state:
  ;; `migrate-legacy-root-cells!` (invoked at namespace load, beside the
  ;; weak helpers) rebuilds any pre-weak persistent-set entries into the
  ;; current host weak sets (rf2-vxgfnd.168). `reset-scheduler!` clears the
  ;; registry between fixtures. Frame
  ;; teardown also consults its still-disconnected members, whose retained
  ;; subscription targets name their last published frames.
  (atom {}))

#?(:cljs
   (defonce ^:private platform-capabilities
     ;; One lazy, root-admission/attach-time probe for the host primitives that
     ;; make weak root ownership honest. Both success and incompatibility are
     ;; cached: production never re-probes per cell/render. Tests clear this
     ;; alongside the root registry in `reset-scheduler!`.
     (atom nil)))

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

(defonce ^:private teardown-settle-signal
  ;; rf2-vxgfnd.275 — the ROOT-LEVEL host-teardown SETTLEMENT signal of an
  ;; in-flight `teardown-root!`, or nil at rest. Holds `{:incarnation <inc>
  ;; :fired <volatile>}` while a teardown-root! thunk (the host React `.unmount`)
  ;; runs. The client's `root-commit-reporter` carries a mount-lifetime cleanup
  ;; sentinel that calls `report-root-teardown!` when React tears the reporter
  ;; (and thus the whole root tree) down; if that fires DURING the thunk it sets
  ;; the volatile — POSITIVE evidence the host ran this root's teardown
  ;; SYNCHRONOUSLY, independent of ViewCell population. Because the reporter wraps
  ;; EVERY root render, a compiled static/cell-less or entirely Activity-hidden
  ;; root (no connected cell to observe) still emits this signal — the gap
  ;; cell-connectivity (`weak-connected`) alone could not see (rf2-vxgfnd.182).
  ;; Save/restored around a teardown so a re-entrant one nests. `defonce`
  ;; (module-lived); `reset-scheduler!` clears it between fixtures.
  (atom nil))

(defonce ^:private live-reporters
  ;; rf2-vxgfnd.275 — the incarnations with a COMMITTED, not-yet-torn-down
  ;; `root-commit-reporter`. Its mount-lifetime layout-effect SETUP runs on
  ;; COMMIT (`report-root-commit!`) and its cleanup on host teardown
  ;; (`report-root-teardown!`). A membership here means a RENDERED root tree
  ;; exists whose host teardown React will still signal — so `teardown-root!`
  ;; must AWAIT that signal to distinguish a deferred teardown from a synchronous
  ;; one, INDEPENDENT of ViewCell population (a cell-less/hidden rendered root is
  ;; a member too). An UNRENDERED / pre-commit root (the render never reached the
  ;; reporter's layout effect — an unrendered `create-root`, a `unmount!` before
  ;; the first commit, a failed-first-mount rollback) is NOT a member, so it has
  ;; no pending host teardown to await and settles synchronously. `defonce`
  ;; (module-lived); `reset-scheduler!` clears it between fixtures.
  (atom #{}))

(declare flush-pending!)

#?(:cljs
   (defn- queue-microtask!
     "Enqueue `f` on the host MICROTASK queue. The HTML event loop runs its
     microtask checkpoint after the current synchronous task and BEFORE the
     'update the rendering' (paint) step, so a microtask-scheduled flush
     corrects a moved sub before the host can present a torn frame — the
     property the host-checkpoint render batch leans on (rf2-vxgfnd.40).

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
  host's realization of the HOST-CHECKPOINT render batch (03 §3). ONE microtask
  per PENDING WINDOW, NOT per epoch and NOT per drain: it is armed by the FIRST
  mark of the window (the `flush-scheduled?` CAS) and fires at the next host
  microtask checkpoint. It therefore folds in every epoch committed by the
  drain's queued events — AND every further drain, nested cross-frame drain, or
  listener re-entry that marks a cell before that checkpoint arrives. This
  scheduler has no router hook and cannot observe a drain boundary
  (rf2-vxgfnd.166). Re-marks before it runs fold in; a synchronous `flush!`
  beforehand just leaves it an empty drain.

  CLJS-only: the flush rides `queue-microtask!` — a TRUE host microtask that
  fires after the current synchronous task unwinds and BEFORE the next paint, so
  a watch-fired invalidation is corrected before a torn frame can show
  (rf2-vxgfnd.40). The JVM headless host has NO async render loop to align
  to — its checkpoint is the EXPLICIT `flush!` (07 §2 'the only flush
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
  ;; Distinct moving targets SHOWN per pending window — a bounded SAMPLE of what
  ;; moved. Further distinct targets fold into the bounded `:dropped` loss SET
  ;; (not a running occurrence count), keeping the shown sample constant-size.
  8)

(def ^:private ^:const dropped-cap
  ;; The SECOND bound: distinct OMITTED target keys tracked in `:dropped` before
  ;; the loss account ITSELF saturates. Past this many distinct omissions,
  ;; `:dropped-exact?` flips false and `(count :dropped)` becomes a LOWER bound.
  ;; The whole record therefore stays constant-size (≤ target-cap shown +
  ;; ≤ dropped-cap omitted, all small sub-query-vector keys).
  64)

(defn- fold-evidence
  "DEBUG plane: fold one invalidation `payload` into a cell's pending-window
  evidence `ev` (nil = a fresh window). Returns a BOUNDED, constant-size
  record — never an unbounded payload vector:

    {:first-epoch    e0    ; the FIRST movement's frame-epoch (the anchor)
     :latest-epoch   eN    ; the most-recent movement's frame-epoch
     :count          n     ; total invalidation OCCURRENCES folded this window
     :causes         #{…}  ; the SET of causes seen (:subscription/:hmr/:disposed — ≤3)
     :targets        [tk…] ; distinct moving targets SHOWN, capped at `target-cap`
     :dropped        #{tk} ; distinct moving targets OMITTED past the shown cap —
                           ;   a BOUNDED SET; its COUNT is the honest fan-out loss
     :dropped-exact? b}    ; true ⇒ every omission is individually tracked;
                           ;   false ⇒ the loss set saturated at `dropped-cap`, so
                           ;   `(count :dropped)` is a LOWER bound

  Honesty (rf2-vxgfnd.74). `:count` is the OCCURRENCE axis (every fold, including
  repeats of an already-seen target); `:dropped` is the DISTINCT-target LOSS
  axis. They are complementary, never duplicates: re-invalidating one already-
  omitted target N times advances `:count` by N but leaves `:dropped` unchanged
  (that target's identity is already recorded). So a window that invalidates
  eight retained targets once then a NINTH target 100 times reports `:count 108`
  yet `:dropped #{tk9}` — ONE distinct omission, not 100. The field means what it
  says (distinct omitted targets), so it never overstates fan-out.

  The cause set and epoch scalars are naturally bounded; BOTH the shown-target
  vector and the omitted-target set are explicitly capped, so overflow is
  REPORTED (never silently lost) yet the record stays constant-size — a
  distinct omission past `dropped-cap` sets `:dropped-exact? false` rather than
  growing the set (acceptance-criteria 3/5)."
  [ev {:keys [cause target frame-epoch]}]
  (let [tk (when target (target-key target))]
    (if (nil? ev)
      {:first-epoch    frame-epoch
       :latest-epoch   frame-epoch
       :count          1
       :causes         (if cause #{cause} #{})
       :targets        (if tk [tk] [])
       :dropped        #{}
       :dropped-exact? true}
      (let [known?     (or (nil? tk)
                           (some #(= tk %) (:targets ev))
                           (contains? (:dropped ev) tk))
            at-cap?    (>= (count (:targets ev)) target-cap)
            drop-full? (>= (count (:dropped ev)) dropped-cap)]
        (cond-> (-> ev
                    (assoc :latest-epoch frame-epoch)
                    (update :count inc))
          cause                                       (update :causes conj cause)
          ;; a NEW distinct target with room in the shown sample
          (and (not known?) (not at-cap?))            (update :targets conj tk)
          ;; a NEW distinct target past the shown cap, with room in the loss set:
          ;; record its IDENTITY so `:dropped` counts DISTINCT omissions
          (and (not known?) at-cap? (not drop-full?)) (update :dropped conj tk)
          ;; a NEW distinct omission but the loss set is FULL: the count is now a
          ;; floor — mark it inexact (the honest "≥ dropped-cap distinct" signal)
          (and (not known?) at-cap? drop-full?)       (assoc :dropped-exact? false))))))

;; ---------------------------------------------------------------------------
;; Commit-cause capture (Ruling 2 detailed records; rf2-qkq2k rework)
;;
;; The per-commit :rf.view/causes vector ships DETAILED cause records — not bare
;; keyword tokens — so slice d and its consumers keep the attribution the port
;; note already carries. The detail is captured at the NOTE (the true cause site,
;; Ruling 2 "emitted at the cause site, never reconstructed"), NOT folded through
;; the bounded Xray window `:evidence` (which coalesces to a lossy cause SET) —
;; keeping the Xray cumulative plane untouched and the records honest.
;;
;; ONLY the explicitly-ruled fields are preserved per cause (Ruling 2); this is
;; NOT a general evidence framework:
;;   - :subscription  target / query / frame-id + version :from->:to + :epoch,
;;                    read straight off the `:subscription` port-note axes (:target,
;;                    :node-key, :node-version, :frame-epoch). `:from` is the
;;                    version before the movement (the port advances it by one per
;;                    move); repeated moves of the SAME target coalesce at DRAIN to
;;                    one record keeping the EARLIEST :from and the LATEST :to/:epoch
;;                    so it spans the whole movement honestly, while two DISTINCT
;;                    targets stay two records (rf2-sy536).
;;   - :hmr / :disposed  bare markers — no ruled detail.
;; Folds are held as an APPEND-ONLY vector (each move a distinct entry); the drain
;; is FENCED by a render waterline captured with the immutable render capture, so a
;; fold arriving after that render (including one racing the publish barrier) is
;; kept for the next render rather than back-attributed here (rf2-eww3k).
;; :story-override (identity + version) and :mount and :local-state are classified
;; at commit time from lifecycle/override/host-write facts (see `commit-causes`),
;; not from the pending port-fold vector.
;; The whole plane is DEBUG-only (production-erased, G-7/G-11).
;; ---------------------------------------------------------------------------

(defn- commit-cause-fact
  "Project ONE port-note `payload` to its S6 commit-cause `[kind detail]` pair, or
  nil when the note carries no commit cause. The port cause keyword is
  `:subscription` — unified port-wide (rf2-ao46i, RULING 2): the observation port,
  this per-commit record surface, and the Xray cumulative window all speak the one
  `:subscription` vocabulary, so no rename happens at this boundary. DEBUG-only —
  reached only from the DEBUG arm of `enrol-dirty-window!`."
  [{:keys [cause target node-key node-version frame-epoch]}]
  (case cause
    :subscription [:subscription {:target   node-key
                                  :query    (:query target)
                                  :frame-id (:frame-id target)
                                  :from     (some-> node-version dec)
                                  :to       node-version
                                  :epoch    frame-epoch}]
    :hmr          [:hmr {}]
    :disposed     [:disposed {}]
    nil))

(defn- fold-commit-cause
  "Append a captured `[kind detail]` port fold to the append-only
  `:pending-commit-causes` vector (`v`, nil = empty) as a DISTINCT entry
  `{:cause kind …ruled-detail}`. Coalescing per causal identity and the
  render-waterline FENCE both happen at DRAIN time (`mint-commit-record!`), never
  here — so a fold is never retroactively merged into a cause an earlier render
  already captured (rf2-eww3k), and two distinct targets never collapse into one
  fabricated cross-target span (rf2-sy536)."
  [v kind detail]
  (conj (or v []) (assoc detail :cause kind)))

(defn- note-commit-cause
  "Fold `payload`'s ruled commit-cause detail onto `s`'s append-only
  `:pending-commit-causes` vector. Pure state transition (the DEBUG arm of the ONE
  linearizable enrolment swap); a payload with no commit cause leaves `s`
  untouched."
  [s payload]
  (if-some [[kind detail] (commit-cause-fact payload)]
    (update s :pending-commit-causes fold-commit-cause kind detail)
    s))

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
  never strand a cell or abort the render batch. Returns nil.

  This is the RAW last-write-wins slot (the test seam). First-party tools
  install through the identity-owned, HMR-safe lifecycle over it —
  `re-frame.ui.tool.evidence/install!` / `uninstall!` (rf2-vxgfnd.75) — which
  rejects a second owner instead of silently clearing the first."
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

(defn- enrol-dirty-window!
  "The PRODUCTION scheduling core — ONE linearizable pending-window enrolment
  transition (rf2-vxgfnd.180). In a SINGLE `swap-vals!` on the cell state,
  perform the `:dirty?` false→true flip and — in DEV/tool builds only — fold the
  optional bounded `payload` evidence into the pending window; then, EXACTLY on
  the false→true transition (read from the swap's OWN prior value, never a
  separate pre-read), enrol `cell` in the dirty registry once (identity-deduped)
  and arm one flush for the pending window. NO compute, no acquire/release (I-5) — the
  production cost is one flag flip, flat in the number of queued events (the
  evidence fold DCEs under goog.DEBUG=false).

  Why one swap (rf2-vxgfnd.180): folding evidence and flipping `:dirty?` in
  SEPARATE transitions — or reading `:dirty?` to decide enrolment separately from
  setting it — is not linearizable on the JVM host. A mark racing a
  `complete-flush!` clear could fold fresh evidence, then read a still-set
  `:dirty?` and skip enrolment, and completion would then erase that evidence and
  leave the cell clean and unregistered — a real update lost with no next
  revision. Folding the flip and the evidence into one swap, and deriving
  enrolment from that swap's own false→true edge, makes a concurrent mark
  linearize cleanly EITHER before completion's capture (its evidence joins the
  captured window) OR after its clear (a fresh next window is enrolled)."
  [^ViewCell cell payload]
   ;; `interop/debug-enabled?` is a STANDALONE top-level gate (not folded into the
   ;; swap-fn body as `(and interop/debug-enabled? payload)`) so Closure DCEs the
   ;; evidence-folding swap-fn — and every reference it carries into `fold-evidence`
   ;; (`:dropped-exact?`, `:first-epoch`, …) — out of the advanced production
   ;; bundle, exactly as the prod-elision gate proves. Both arms are ONE
   ;; `swap-vals!`, so the evidence fold and the `:dirty?` false→true flip stay a
   ;; single atomic transition on each host (rf2-vxgfnd.180).
  (let [st (state cell)
        [old _] (if interop/debug-enabled?
                  (swap-vals! st (fn [s]
                                   (cond-> (assoc s :dirty? true)
                                     ;; Xray window fold (bounded/coalesced) AND the
                                     ;; per-cause DETAIL capture for the commit record
                                     ;; (Ruling 2; rf2-qkq2k), both inside the ONE
                                     ;; linearizable enrolment swap (rf2-vxgfnd.180)
                                     ;; and both DCE'd whole in production.
                                     payload (update :evidence fold-evidence payload)
                                     payload (note-commit-cause payload))))
                  (swap-vals! st (fn [s] (assoc s :dirty? true))))]
    (when-not (:dirty? old)
      (swap! dirty-cells conj cell)
      (schedule-flush!))))

(defn- enrol-dirty!
  "Enrol one pending window. On the JVM, serialize the cell dirty edge and
  registry insertion with `reset-scheduler!`'s detach-and-clear transition, so
  a racing mark linearizes wholly before reset or becomes a fresh post-reset
  enrolment. CLJS is single-threaded and pays no locking path."
  ([^ViewCell cell] (enrol-dirty! cell nil))
  ([^ViewCell cell payload]
   #?(:clj  (locking dirty-cells
              (enrol-dirty-window! cell payload))
      :cljs (enrol-dirty-window! cell payload))))

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
  seam. nil when driven without epoch evidence. The fold and the dirty flip are
  ONE linearizable swap (`enrol-dirty!`; rf2-vxgfnd.180)."
  ([^ViewCell cell] (mark-dirty! cell nil))
  ([^ViewCell cell epoch]
   (enrol-dirty! cell {:frame-epoch epoch})))

(def ^:dynamic ^:no-doc *completion-barrier*
  "JVM linearization TEST SEAM — nil in production (one nil check per completed
  cell, zero further cost), NEVER bound off a test path (the `*commit-barrier*`
  idiom). Bound to a `(fn [cell] …)`, `complete-flush!` calls it at the ONE
  deterministic point BETWEEN reading a cell's pending window and the
  `compare-and-set!` that clears it, so a fixture can interleave a concurrent
  `mark-dirty!` INSIDE completion's capture→clear window and prove the transition
  still linearizes (rf2-vxgfnd.180). Because the clear is a compare-and-set!
  RETRY loop, a mark landing in the barrier window fails the CAS and completion
  re-reads — capturing the freshly-folded evidence rather than erasing it."
  nil)

(defn- complete-flush!
  "PHASE 1 of a batch flush (rf2-vxgfnd.86): complete `cell`'s SCHEDULER STATE
  with NO arbitrary user code — capture the pre-clear DEBUG evidence, clear
  `:dirty?`/evidence, and advance the revision (WITHOUT notifying listeners yet),
  as ONE linearizable transition (rf2-vxgfnd.180). No-op / nil when the cell is
  not dirty. Returns `[cell ev]` for the cell it completed (`ev` nil in
  production / when the window carried none), else nil.

  ONE linearizable capture-and-clear (rf2-vxgfnd.180). The capture (the returned
  window `ev`), the `:dirty?`/evidence clear, and the revision advance are a
  SINGLE compare-and-set! transition over the value read at the top of the loop,
  so the delivered window is EXACTLY the state that was cleared. A `mark-dirty!`
  racing this on the JVM host either commits BEFORE the CAS — in which case the
  CAS over the now-stale value fails, the loop re-reads, and the mark's fresh
  evidence joins the captured window — or AFTER the clear, where it observes a
  cleared `:dirty?` and enrols a FRESH next window. It can no longer fold
  evidence into a window this call already captured-by-value yet is about to
  erase (the pre-fix loss: capture and clear were separate reads/writes, so an
  interleaved mark's evidence was captured-around then wiped, and its enrolment
  skipped on a still-set `:dirty?`).

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
    (loop []
      (let [s @st]
        (when (:dirty? s)
          ;; The per-commit :rf.view/causes DETAIL is captured at the NOTE
          ;; (`enrol-dirty-window!` -> `note-commit-cause`), NOT here: completion
          ;; owns only the scheduler state (dirty/evidence clear + revision
          ;; advance). `:pending-commit-causes` survives this clear (the next
          ;; connected commit drains it), so the flush never re-reads or stashes a
          ;; cause (rf2-qkq2k). The Xray window `:evidence` is still handed to the
          ;; sink below before it is cleared.
          (let [cleared (-> s
                            (assoc :dirty? false :evidence nil)
                            (update :revision inc))]
            (when-some [barrier *completion-barrier*] (barrier cell))
            (if (compare-and-set! st s cleared)
              [cell (when interop/debug-enabled? (:evidence s))]
              (recur))))))))

(defn- deliver-flush!
  "PHASE 2 of a batch flush (rf2-vxgfnd.86): now that `complete-flush!` has
  settled the WHOLE batch's scheduler state, notify `cell`'s listeners (the
  production re-render trigger — one coalesced React render batch, since every
  listener fires synchronously within this flush) and, in DEV, hand the captured
  `ev` to the installed `evidence-sink` (Xray; rf2-vxgfnd.46) inside a guard.

  BOTH consumer families are contained here, because neither is an authority
  over the render correction (the rf2-vxgfnd.73 doctrine):

    - a THROWING LISTENER (the PRODUCTION surface — rf2-owwbyl) is caught and
      returned to the batch core, so it can neither strand this cell nor abort
      the batch's remaining cells' deliveries: phase 1 already cleared every
      drained cell's dirty flag and registry membership, so an aborted phase 2
      would LOSE the undelivered sibling notifications outright — stale UI
      until unrelated movement re-marks them. The batch core rethrows the
      FIRST listener escape AFTER the whole batch has delivered — surfaced,
      never silent, never starving (the same drain-then-rethrow shape as the
      observation port's `drain-pending-disposals!`).
    - a THROWING sink is a broken DEBUG tool: contained exactly as before
      (rf2-vxgfnd.73) and reported via ONE bounded diagnostic (never rethrown,
      never through the sink). The sink still receives the exact pre-clear
      bounded summary once per flushed cell.

  Because completion already finished for the whole batch, a re-entrant
  sink/listener that re-marks this (or ANY) cell enrols a FRESH pending window
  rather than losing the notification. Returns nil, or a map carrying the
  contained `:listener` and/or `:sink` escape as `{:cell cell :error e}`; the
  evidence/sink half DCEs under `goog.DEBUG=false`."
  [^ViewCell cell ev]
  (let [listener-escape (try
                          (notify-listeners! cell)
                          nil
                          (catch #?(:clj Throwable :cljs :default) e
                            {:cell cell :error e}))
        sink-escape     (when interop/debug-enabled?
                          (when-some [sink @evidence-sink]
                            (try
                              (sink cell ev)
                              nil
                              (catch #?(:clj Throwable :cljs :default) e
                                {:cell cell :error e}))))]
    (cond-> nil
      (some? listener-escape) (assoc :listener listener-escape)
      (some? sink-escape)     (assoc :sink sink-escape))))

(defn- run-flush-batch!
  "The two-phase batch-flush CORE over an explicit ordered `cells` seq
  (rf2-vxgfnd.86). PHASE 1 (`complete-flush!`) settles every dirty cell's
  scheduler state — with no user code — so the whole batch is complete before
  PHASE 2 (`deliver-flush!`) runs any listener or evidence-sink. A re-entrant
  re-mark in phase 2 therefore always sees a fully-completed batch and opens the
  NEXT window, regardless of iteration order.

  Phase 2 delivers EVERY cell regardless of consumer behaviour — each
  `deliver-flush!` contains its cell's throwing listener AND (in DEV) a
  throwing sink per-cell, so neither can abort the remaining cells' deliveries
  (rf2-owwbyl / rf2-vxgfnd.73). Evaluate the delivery FIRST (never inside a
  short-circuiting form), keep the FIRST escape of each family across the
  batch, then: emit ONE bounded sink diagnostic (never through the sink; the
  sink half DCEs under `goog.DEBUG=false`) and RETHROW the first listener
  escape AFTER the whole batch delivered — a production consumer bug is
  surfaced to the flush caller, never silent, and never costs a sibling cell
  its notification. Returns the count actually flushed."
  [cells]
  (let [completed (into [] (keep complete-flush!) cells)
        escapes   (reduce (fn [acc [cell ev]]
                            (let [e (deliver-flush! cell ev)]
                              (cond-> acc
                                (and (:listener e) (nil? (:listener acc)))
                                (assoc :listener (:listener e))

                                (and (:sink e) (nil? (:sink acc)))
                                (assoc :sink (:sink e)))))
                          {}
                          completed)]
    (when interop/debug-enabled?
      (when-some [se (:sink escapes)]
        (report-sink-escape! se)))
    (when-some [le (:listener escapes)]
      (throw (:error le)))
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

(defn guard-open-drain!
  "The SHARED open-event-drain guard — the DEV-tier `:rf.error/flush-in-open-epoch`
  signal (03 §11; Spec 006 §Render-batch finalization). Reject a synchronous
  registry-flush forced from `where` while a frame's run-to-completion event
  drain is STILL OPEN: flushing there could publish partially-settled queued
  update/commit work (a torn read/render). The single owner of the ruling — reused by
  BOTH `ui.test/flush!` (the test all-roots spelling) and the first-party
  adapter's `flush-render!` (the production synchronous render-commit), so there
  is ONE guard, not two copies drifting apart.

  `re-frame.frame/*run-frame-state-before*` is bound around the current
  event-pipeline run and SURVIVES a handler destroying its own frame — a live
  registry scan cannot, since destroy removes the active frame before the handler
  returns, which used to let a destroy-self-then-flush call cross the guard and
  deliver render-phase work inside the still-open run. Throws BEFORE the registry is
  touched (no partial flush); a no-op outside any drain."
  [where]
  (when (some? frame/*run-frame-state-before*)
    (let [frame-id (frame/frame-target->id frame/*current-frame*)]
      (error/throw-error!
       :rf.error/flush-in-open-epoch where
       (str where " was called while frame " (pr-str frame-id)
            " is still inside its event drain — let the queued update and "
            "commit phases run to completion before forcing a read/render batch")
       {:recovery :no-recovery
        :extra {:frame frame-id
                :frame-epoch (frame/frame-commit-epoch frame-id)}}))))

(defn- cell-frames
  "The set of frame-ids `cell`'s committed subscription sites observe.

  Only records with a live handle are currently observed. A static Story-
  override target names NO frame (the pinned value IS the
  resolution — there is no node and no observed frame), so an OVERRIDE-ONLY
  cell observes no frame and this returns `#{}`. The frame-scope membership
  test (`cell-observes-frame?`) is therefore false for such a cell against
  EVERY frame, and `flush-frame!` — which scopes on it — can never reach an
  override-only cell; only the GLOBAL `flush-pending!` drains one. A cell
  mixing `sub` and override sites observes exactly its `sub` sites' frames."
  [^ViewCell cell]
  (into #{}
        (keep (fn [{:keys [target handle]}]
                (when (and handle (= :subscription (:kind target)))
                  (:frame-id target))))
        (vals (:committed @(state cell)))))

(defn cell-observes-frame?
  "True when `cell`'s committed dependency set includes a site in frame
  `frame-id` (the frame-scope membership test)."
  [^ViewCell cell frame-id]
  (contains? (cell-frames cell) frame-id))

(defn- cell-retained-frame?
  "True when `cell`'s last published lexical subscription site records name
  `frame-id`. Records survive an Activity disconnect with `:handle nil`,
  providing bounded exact query/value history plus frame attribution for hidden
  root-owned cells."
  [^ViewCell cell frame-id]
  (boolean
   (some (fn [{:keys [target]}]
           (and (= :subscription (:kind target))
                (= frame-id (:frame-id target))))
         (vals (:committed @(state cell))))))

(defn flush-frame!
  "The FRAME arity of `flush!` — flush every pending cell observing frame
  `frame-id` (every root that observes that frame). Cells scoped to other
  frames stay pending — and an OVERRIDE-ONLY cell, which observes no frame at
  all (`cell-frames` tracks `:sub` keys only), is never in scope: only the
  global `flush-pending!` drains such a cell. Returns the count flushed."
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
  the total occurrence `:count`, the cause set, a capped SHOWN distinct-target
  vector, and a bounded `:dropped` SET of the distinct targets OMITTED past the
  shown cap (its count is the honest fan-out loss, `:dropped-exact?` flags
  saturation). The `re-frame.ui.tool`/Xray projection reads this (or receives
  it via `set-evidence-sink!`) to attribute a coalesced render to its
  contributing movement WITHOUT forcing a render for every epoch (rf2-vxgfnd.46;
  tool/test read)."
  [^ViewCell cell]
  (:evidence @(state cell)))

(defn pending-cell-count
  "The number of cells with a pending notification (tool/test read)."
  []
  (count @dirty-cells))

;; ---- bounded flush convergence (rf2-0faipl) ---------------------------------
;;
;; A synchronous forcing caller (the first-party adapter's render-commit; the
;; test all-roots flush) drains the registry, then RE-DRAINS to a fixed point
;; because a commit-triggered re-dirty — a layout effect that dispatches, a
;; useSyncExternalStore listener that re-marks — can enrol a cell AFTER the
;; pass that flushed it. That re-drain must terminate. The ambient dispatch
;; drain-depth (`:rf.error/drain-depth-exceeded`) and React maximum-update-depth
;; guards bound a SINGLE synchronous pass; neither can see a re-enrolment that
;; lands across two SEPARATE `flushSync` passes, so an unstable
;; commit→re-dirty cycle could once spin the synchronous forcing call forever.
;; `converge-flush!` gives that re-drain a LOCAL finite pass budget and fails
;; loud with one typed diagnostic when it cannot quiesce.

(def ^:const flush-convergence-budget
  "The finite ceiling on synchronous re-flush passes a `converge-flush!` driver
  runs before declaring the ViewCell registry NON-QUIESCENT and failing loud.
  Ordinary commit-triggered convergence settles in a handful of passes (React's
  own maximum-update-depth guard sits near 50); this ceiling is deliberately
  well above any legitimate multi-pass settle, so tripping it means a commit
  path is re-dirtying cells EVERY pass — an unstable effect/notification cycle,
  never slow-but-terminating convergence. The bound is LOCAL to the flush
  driver: it does NOT rely on the ambient dispatch drain-depth or React
  update-depth guards, which cover a single synchronous pass and cannot see a
  re-enrolment across two separate `flushSync` passes (rf2-0faipl)."
  100)

(defn flush-nonconvergence!
  "Throw the SHARED `:rf.error/flush-convergence-exceeded` diagnostic — the one
  typed non-quiescence signal for a flush that will not settle within
  `flush-convergence-budget` synchronous passes. `pending` is the residual
  pending-cell count; `where` names the forcing site
  (`re-frame.ui.substrate/flush-render!` / `rf.ui.test/flush!`). Carries
  `:passes` (the exhausted budget) + `:pending` so the diagnostic locates the
  runaway. Shared by the synchronous `converge-flush!` loop AND the CLJS async
  `ui.test/flush!` cycle so there is ONE diagnostic, not two copies drifting
  apart (rf2-0faipl)."
  [where pending]
  (error/throw-error!
    :rf.error/flush-convergence-exceeded where
    (str where " could not converge the compiled-view flush registry within "
         flush-convergence-budget " synchronous passes — " pending
         " cell(s) still pending. A commit path is re-dirtying cells every "
         "pass (an unstable layout-effect or notification cycle); this is a "
         "non-quiescent flush, not slow convergence — fix the effect/listener "
         "that keeps re-marking.")
    {:recovery :no-recovery
     :extra    {:passes  flush-convergence-budget
                :pending pending}}))

(defn converge-flush!
  "Drive `flush-pass!` (a zero-arg thunk performing ONE synchronous flush of the
  caller's scope) to a BOUNDED fixed point: while the dirty registry is
  non-quiescent, run one more pass — but never more than
  `flush-convergence-budget` of them. The CALLER runs the INITIAL write+flush
  pass; this drains any commit-triggered re-dirty (a dispatching layout effect,
  a re-marking listener) that follows it.

  Ordinary multi-pass convergence and the no-op case are preserved exactly: a
  registry already quiescent on entry loops ZERO times and never touches
  `flush-pass!`; a one-shot commit-triggered re-dirty drains in one further
  pass. When the registry is STILL non-quiescent after the budget is spent,
  fail loud through `flush-nonconvergence!` with
  `:rf.error/flush-convergence-exceeded` (pass budget + residual pending count)
  — the LOCAL non-quiescence signal the single-pass ambient guards do not
  guarantee (rf2-0faipl). `where` names the forcing site. Returns nil.

  This is the SHARED bound both synchronous loop-to-quiescence sites rest on —
  the first-party adapter's `flush-render!` and the JVM test `flush!` — so
  there is ONE convergence law, not two copies drifting apart."
  [where flush-pass!]
  (loop [pass 0]
    (let [pending (pending-cell-count)]
      (when (pos? pending)
        (when (>= pass flush-convergence-budget)
          (flush-nonconvergence! where pending))
        (flush-pass!)
        (recur (inc pass)))))
  nil)

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
  "Test support: detach every pending cell, clear its dirty/evidence window,
  and reset scheduler/tool registries without advancing a revision or notifying
  a listener — a clean slate between fixtures. A concurrent JVM mark linearizes
  before the detach (and is cleared) or after it (and remains freshly enrolled).
  Returns nil."
  []
  (letfn [(detach-and-clear! []
            (let [[detached _] (swap-vals! dirty-cells (constantly #{}))]
              (doseq [cell detached]
                (swap! (state cell)
                       (fn [m]
                         (-> (assoc m :dirty? false :evidence nil)
                             ;; DEBUG-only carry-forward slot (Ruling 2): a fixture
                             ;; clean slate must not leak stashed causes across
                             ;; tests. Absent in production, so this dissoc is a
                             ;; no-op there.
                             (dissoc :pending-commit-causes)))))))]
    #?(:clj  (locking dirty-cells (detach-and-clear!))
       :cljs (detach-and-clear!)))
  (reset! flush-scheduled? false)
  (reset! slice-memo* nil)
  (reset! live-cells #{})
  (reset! root-cells {})
  #?(:cljs (reset! platform-capabilities nil))
  (reset! teardown-collector nil)
  (reset! teardown-settle-signal nil)
  (reset! live-reporters #{})
  (reset! evidence-sink nil)
  (reset! last-sink-escape nil)
  ;; Do NOT clear `view-generations`: it now owns the stable component shells
  ;; and descriptors created at namespace load. Clearing it would strand every
  ;; already-defined defview Var on a shell whose dynamic descriptor vanished.
  ;; Tests use qualified per-fixture view ids for HMR decisions.
  nil)

(defn- on-change-fn
  "Build the per-handle `on-change` the commit registers on each acquired
  target (Spec 006 §The internal observation port). Constant-work: enrol
  `cell` for a coalesced flush and — in DEV/tool builds only — fold the port's
  rich invalidation payload (`:cause`/`:target`/`:frame-epoch`, plus the
  `:node-*`/`:registry-epoch` axes it carries) into the bounded pending-window
  evidence. Production carries only the enrolment (I-5; the evidence fold DCEs
  out under goog.DEBUG=false). The fold and the dirty flip are ONE linearizable
  swap (`enrol-dirty!`; rf2-vxgfnd.180)."
  [^ViewCell cell]
  (fn [payload]
    (enrol-dirty! cell payload)))

;; ---- lifecycle (03 §4) ------------------------------------------------------
;;
;; Three OBSERVABLE runtime states. The fact emitted at cleanup is always
;; `:disconnected {:reason :unknown}` — the platform gives no hide-vs-unmount
;; signal. Later evidence annotates the PRIOR interval, never the present: a
;; SETTLED reconnect proves an Activity hide (`:activity-hidden {:proof
;; :reconnect}`); an explicit host/root teardown proves an unmount (`:unmounted
;; {:proof :host-teardown}`).
;;
;; The settle qualifier is the rf2-vxgfnd.44 / rf2-vxgfnd.164 honesty fix: a
;; reconnect that beats the settle is UNSETTLED / same-checkpoint evidence, and
;; the runtime must NOT claim more than the host proves. It is consistent with a
;; React StrictMode dev mount→cleanup→remount replay, but it does NOT identify
;; one: two REAL commits can also complete in one synchronous stack (consecutive
;; `flushSync` hide/reveal) before the settle microtask runs — the microtask
;; separates JavaScript checkpoints, not React commits. With no exact host
;; discriminator, asserting `:activity-hidden` would fabricate a proof never
;; observed, so the interval honestly stays `:unknown`. So `disconnect!` marks
;; each cleanup PROVISIONAL and `settle-disconnect!` (a microtask on CLJS) clears
;; it once the disconnect outlives its checkpoint; only a disconnect that survived
;; a host yield is then proven a hide.
;;
;; The field, its settle, and the reconnect branch are DEV-only. Production
;; therefore holds NO settle evidence and makes NO Activity-hide claim: every
;; reconnect stays at the honest `:unknown` floor. Production has no StrictMode
;; double-invoke, but it still has two real commits in one synchronous stack, so
;; annotating there would export the very proof the dev path refuses to fabricate
;; — a build flag may change what is RECORDED, never what is CLAIMED
;; (rf2-vxgfnd.164). The `:unmounted {:proof :host-teardown}` upgrade is
;; unaffected in both builds: it rests on an authoritative host signal (an
;; explicit root `.unmount`), not on a timing inference.

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
;; `live-cells` is the connected discoverability surface a FRAME-destroy sweep
;; consults: subscription handles contribute reactive-observation frames.
;; The membership is dropped the instant the cell disconnects, so it cannot
;; survive an Activity hide. Root teardown needs an ownership association that
;; DOES survive a transient hide: the `root-cells` registry keyed by a per-mount
;; root incarnation. `attach-root!` (the mount seam) enrols a cell under its
;; root's incarnation; the cell stays enrolled across a hide and leaves
;; deterministically when it is proven dead (`detach-root!` from `teardown!`) —
;; or, because membership is WEAK (rf2-mc62sp), simply by being collected after
;; an ordinary reconciliation unmount dropped the last strong reference.
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

;; ---- weak root membership (rf2-mc62sp) --------------------------------------
;;
;; The registry must survive an Activity hide (React gives no hide-vs-unmount
;; signal at cleanup), yet an ordinary reconciliation unmount runs ONLY that
;; same cleanup — so a strong set would pin every ordinarily-unmounted cell
;; (with its retained committed site values) until the whole root tears down.
;; Membership is therefore WEAK, per host idiom (mirroring the observation
;; port's weak node-record table): a hidden cell is strongly reachable from
;; React's retained fiber, so its entry lives exactly as long as Activity
;; retention; an unmounted cell becomes unreachable — nothing can ever
;; reconnect it — collects, and its entry clears with it.
;;
;;   - JVM: a synchronized `WeakHashMap`-backed keyset (the
;;     `re-frame.interop` weak-registry idiom) — stale entries expunge on
;;     every access, iteration under the wrapper's own lock.
;;   - CLJS: a `js/Set` of required `js/WeakRef` plus an ephemeron `js/WeakMap`
;;     (cell -> its ref) for O(1) removal. Root admission captures WeakRef once
;;     and fails typed-before-mutation when absent — there is no retaining
;;     fallback. An OPTIONAL module `FinalizationRegistry` reaper prunes a
;;     collected cell's ref and drops the incarnation entry when its last member
;;     clears; where the host lacks that accelerator, every synchronous scan
;;     compacts husks and drops empty entries instead.

(defn- make-weak-member-set
  []
  #?(:clj  (java.util.Collections/synchronizedSet
            (java.util.Collections/newSetFromMap (java.util.WeakHashMap.)))
     :cljs {:refs (js/Set.) :by-cell (js/WeakMap.)}))

(defn- weak-empty?
  [members]
  #?(:clj  (.isEmpty ^java.util.Set members)
     :cljs (zero? (.-size ^js (:refs members)))))

(defn- drop-root-entry-if-empty!
  "Drop `incarnation`'s registry entry when its membership set has emptied —
  guarded so a concurrent re-attach (which installs or repopulates the entry)
  is never clobbered. Retry-safe inside `swap!`: the emptiness read is
  idempotent."
  [incarnation members]
  (when (weak-empty? members)
    (swap! root-cells
           (fn [m]
             (let [s (get m incarnation)]
               (if (and (identical? s members) (weak-empty? s))
                 (dissoc m incarnation)
                 m))))))

#?(:cljs
   (defn- usable-weak-ref-constructor
     "Return `candidate` only when it implements the required WeakRef contract:
     constructable with an object target and a callable, non-throwing `deref`
     that returns that still-strongly-held target. Probe objects are local only."
     [candidate]
     (when (= "function" (goog/typeOf candidate))
       (try
         (let [target (js-obj)
               ref    (js/Reflect.construct candidate #js [target])
               deref  (.-deref ref)]
           (when (and (= "function" (goog/typeOf deref))
                      (identical? target (.call deref ref)))
             candidate))
         (catch :default _ nil)))))

#?(:cljs
   (defn- usable-finalization-reaper
     "Construct and minimally exercise the optional FinalizationRegistry.
     Missing/malformed/throwing implementations are treated as absence. The
     probe registration is immediately unregistered, retaining no probe target."
     [candidate callback]
     (when (= "function" (goog/typeOf candidate))
       (try
         (let [reaper     (js/Reflect.construct candidate #js [callback])
               register   (.-register reaper)
               unregister (.-unregister reaper)
               target     (js-obj)
               token      (js-obj)]
           (when (and (= "function" (goog/typeOf register))
                      (= "function" (goog/typeOf unregister)))
             (.call register reaper target :rf.ui/finalizer-probe token)
             (when (true? (.call unregister reaper token))
               reaper)))
         (catch :default _ nil)))))

(defn ensure-platform-compatible!
  "Internal root-admission gate for the CLJS weak-ownership substrate.

  `WeakRef` is required: without it, root ownership would either retain every
  ordinarily-unmounted ViewCell or silently lose hidden-cell teardown
  discovery. The capability is probed exactly once for working construction +
  `deref`, and its constructor is retained. `FinalizationRegistry` is optional;
  when absent or unusable, synchronous scans compact cleared WeakRef husks. The JVM uses its
  WeakHashMap implementation and needs no JavaScript capability probe.

  Throws `:rf.error/ui-platform-incompatible` before root/ViewCell ownership
  mutation when the required JavaScript capability is absent or unusable. Returns the
  cached internal capability record on CLJS, nil on the JVM."
  [where]
  #?(:clj nil
     :cljs
     (let [caps
           (or @platform-capabilities
               (let [weak-ref-candidate
                     (when (exists? js/globalThis)
                       (aget js/globalThis "WeakRef"))
                     weak-ref-constructor
                     (usable-weak-ref-constructor weak-ref-candidate)
                     finalization-registry-constructor
                     (when (exists? js/globalThis)
                       (aget js/globalThis "FinalizationRegistry"))
                     compatible? (some? weak-ref-constructor)
                     reaper
                     (when compatible?
                       (usable-finalization-reaper
                        finalization-registry-constructor
                        (fn [held]
                          (let [{:keys [incarnation ref]} held
                                members (get @root-cells incarnation)]
                            (when (some? members)
                              (.delete ^js (:refs members) ref)
                              (drop-root-entry-if-empty!
                               incarnation members))))))
                     probed {:compatible? compatible?
                             :platform :javascript
                             :capability :js/WeakRef
                             :weak-ref-constructor weak-ref-constructor
                             :reaper reaper}]
                 (if (compare-and-set! platform-capabilities nil probed)
                   probed
                   @platform-capabilities)))]
       (when-not (:compatible? caps)
         (error/throw-error!
          :rf.error/ui-platform-incompatible where
          (str "re-frame.ui requires a usable JavaScript WeakRef for bounded "
               "root/ViewCell ownership, but this host does not provide one — "
               "use a modern "
               "WeakRef-capable browser or JavaScript runtime")
          {:recovery :use-a-weakref-capable-javascript-runtime
           :extra {:platform (:platform caps)
                   :capability (:capability caps)}}))
       caps)))

(defn- weak-add!
  [members incarnation ^ViewCell cell]
  #?(:clj  (.add ^java.util.Set members cell)
     :cljs (let [{:keys [weak-ref-constructor reaper]}
                 (ensure-platform-compatible!
                  're-frame.ui.reactive/attach-root!)
                 by-cell ^js (:by-cell members)]
             (when-not (.has by-cell cell)
               (let [ref (js/Reflect.construct weak-ref-constructor #js [cell])]
                 (.set by-cell cell ref)
                 (.add ^js (:refs members) ref)
                 (when (some? reaper)
                   (.register reaper cell
                              {:incarnation incarnation :ref ref}
                              ref)))))))

(defn- weak-remove!
  [members ^ViewCell cell]
  #?(:clj  (.remove ^java.util.Set members cell)
     :cljs (let [by-cell ^js (:by-cell members)
                 reaper (:reaper @platform-capabilities)]
             (when-some [ref (.get by-cell cell)]
               (.delete by-cell cell)
               (.delete ^js (:refs members) ref)
               (when (some? reaper)
                 (.unregister reaper ref))))))

(defn- weak-live
  "Snapshot the still-LIVE cells of one incarnation's weak membership set
  (nil-safe — an absent entry is no members). The teardown-discovery read:
  a cleared ref is a collected — therefore unreachable, therefore never
  reconnectable — cell with nothing left to reap.

  Every such READ also COMPACTS the outer registry, on BOTH hosts: neither host
  removes the enclosing incarnation entry on its own — the CLJS
  FinalizationRegistry is an OPTIONAL accelerator, and the JVM WeakHashMap
  expunges the collected member (on `.isEmpty`/`.size`) but never the registry
  entry that held it. So when the snapshot leaves the membership empty, the
  now-empty incarnation entry is dropped here (rf2-vxgfnd.169) — the read is the
  correctness path for both hosts, not just an accelerator on CLJS."
  ([members] (weak-live members nil))
  ([members incarnation]
   (if (nil? members)
     []
     (let [snapshot
           #?(:clj  (locking members (into [] members))
              :cljs (let [refs ^js (:refs members)
                          out  (array)]
                      (.forEach refs
                                (fn [ref _ _]
                                  (if-some [cell (.deref ^js ref)]
                                    (.push out cell)
                                    (.delete refs ref))))
                      (vec out)))]
       (when (some? incarnation)
         (drop-root-entry-if-empty! incarnation members))
       snapshot))))

(defn- weak-live-count
  [members incarnation]
  (if (nil? members)
    0
    #?(:clj  (let [n (.size ^java.util.Set members)]
               ;; `.size` expunges the collected member from the WeakHashMap but
               ;; not the enclosing registry entry — compact it too, so a count of
               ;; a fully-collected incarnation also prunes it (rf2-vxgfnd.169).
               (when (some? incarnation)
                 (drop-root-entry-if-empty! incarnation members))
               n)
       :cljs (count (weak-live members incarnation)))))

;; ---- reload migration (rf2-vxgfnd.168) ---------------------------------------
;;
;; `root-cells` is `defonce`, so a hot reload (CLJS `:after-load`, JVM
;; `require :reload`) PRESERVES the pre-weak value — `incarnation ->
;; #{cell …}` persistent sets (pre-rf2-mc62sp) — while every reloaded
;; operation assumes the host weak representation. Without migration the
;; first post-reload root operation crashes mid-lifecycle: on the JVM,
;; `weak-add!`/`weak-remove!` hit the persistent set's unsupported mutable
;; ops (`UnsupportedOperationException` inside `teardown!`, AFTER the cell
;; already went :dead); on CLJS the weak helpers read the missing
;; `:refs`/`:by-cell` slots (`TypeError`), and the incarnation stays
;; retained. The load-time call below converges any surviving legacy
;; entries into the current representation before anything touches them.

(defn migrate-legacy-root-cells!
  "RELOAD MIGRATION (rf2-vxgfnd.168): rebuild every PRE-WEAK `root-cells`
  entry — the persistent `#{cell …}` shape a pre-rf2-mc62sp namespace's
  `defonce` hands a hot reload — into the current host weak membership
  set. Runs once at namespace load (immediately below, after the weak
  helpers/reaper exist); re-running is an idempotent no-op, so it is also
  the tool/test re-run door.

  Identity-preserving: every still-referenced member — INCLUDING an
  Activity-hidden cell that must stay discoverable for root/frame
  teardown — is re-enrolled under its incarnation (and, on CLJS,
  re-registered with the finalization reaper via `weak-add!`). The
  registry is never cleared: clearing would orphan hidden ownership and
  leave teardown incomplete. Ordinary reconciliation-unmounted cells
  become collectible exactly as the weak design intends — the fresh weak
  set holds them no more strongly than any other member.

  Mixed-state tolerant + convergent: only legacy persistent-set entries
  convert (`set?` is false for BOTH host weak representations), each
  entry swap is CAS-guarded on the exact legacy value so a concurrent
  enrol/forget/migrate can never be clobbered, and the outer loop
  re-checks until no legacy entry remains (new code never writes the
  legacy shape, so the loop terminates). Fresh startup pays only the
  bounded empty-registry check — no compatibility branch remains on the
  hot read/write paths once migration completes. Returns nil."
  []
  (loop []
    (let [legacy (filter (comp set? val) @root-cells)]
      (when (seq legacy)
        (doseq [[incarnation members] legacy]
          (let [fresh (make-weak-member-set)]
            (run! (fn [cell] (weak-add! fresh incarnation cell)) members)
            (swap! root-cells
                   (fn [m]
                     (if (identical? (get m incarnation) members)
                       (assoc m incarnation fresh)
                       m)))))
        (recur)))))

(defn seed-legacy-root-cells!
  "Test seam (rf2-vxgfnd.168): overwrite `incarnation`'s registry entry
  with the PRE-WEAK persistent-set representation (`#{cell …}`) exactly
  as a pre-rf2-mc62sp namespace's `defonce` leaves it across a hot
  reload — the reload-simulation fixture seeds this, runs
  `migrate-legacy-root-cells!`, and drives teardown/counting through the
  reloaded code. Never called by production code. Returns nil."
  [incarnation cells]
  (swap! root-cells assoc incarnation (into #{} cells))
  nil)

;; The load-time hook: converge any `defonce`-surviving legacy entries
;; BEFORE any post-reload root operation can reach them. On a fresh
;; start `root-cells` is empty and this is one bounded no-op check.
(migrate-legacy-root-cells!)

(defn- forget-root-cell!
  "Remove `cell` from `incarnation`'s weak membership set, DROPPING the
  incarnation entry entirely when its last cell leaves — so repeated
  mount/hide/unmount cycles never grow a historical registry
  (rf2-vxgfnd.85 AC5)."
  [^ViewCell cell incarnation]
  (when (some? incarnation)
    (when-some [members (get @root-cells incarnation)]
      (weak-remove! members cell)
      (drop-root-entry-if-empty! incarnation members))))

(defn- enrol-root-cell!
  "Add `cell` to `incarnation`'s WEAK membership set, installing the set on
  first use. Loops to self-heal the (concurrent-JVM, test-shaped) race where
  a simultaneous last-member removal drops the incarnation entry between the
  install and the add — the re-check guarantees the added cell is reachable
  through the CURRENT registry entry."
  [^ViewCell cell incarnation]
  (let [existing (get @root-cells incarnation)
        members (or existing
                    (let [fresh (make-weak-member-set)]
                      (-> (swap! root-cells update incarnation #(or % fresh))
                          (get incarnation))))]
    ;; Without the optional reaper, enrolment is itself the bounded churn edge:
    ;; compact cleared reconciliation members before appending. A capable reaper
    ;; keeps the normal production attach path O(1), with no added scan.
    #?(:cljs (when (and (some? existing)
                        (nil? (:reaper @platform-capabilities)))
               (weak-live members incarnation))
       :clj nil)
    (if-not (identical? members (get @root-cells incarnation))
      ;; Compaction may have dropped an all-dead entry. Retry before allocating a
      ;; member into that now-orphan set.
      (recur cell incarnation)
      (do
        (weak-add! members incarnation cell)
        (when-not (identical? members (get @root-cells incarnation))
          (recur cell incarnation))))))

(defn attach-root!
  "Mount seam: associate `cell` with root `incarnation` — the per-mount ownership
  token (`make-root-incarnation`) that SURVIVES a transient Activity disconnect
  and lets `teardown-root!` reap a cell already Activity-hidden BEFORE the host
  unmount window (rf2-vxgfnd.85). Enrols the cell in `root-cells` under
  `incarnation` (idempotent, and WEAK — membership never keeps an unmounted
  cell alive, rf2-mc62sp); re-attaching to a DIFFERENT incarnation first drops
  the old membership, so a cell can never straddle two roots. An Activity hide
  (which removes the cell from `live-cells`) does NOT drop this membership —
  that is the whole point. Returns the cell."
  [^ViewCell cell incarnation]
  ;; The capability gate precedes even the cell's `:root` write and the
  ;; registry install, so an incompatible host leaves both surfaces pristine.
  (ensure-platform-compatible! 're-frame.ui.reactive/attach-root!)
  (let [st  (state cell)
        old (:root @st)]
    ;; A frame close revalidation can reject and tear down the cell in the
    ;; preceding layout commit. The later lifecycle effect must not resurrect
    ;; that dead cell into root ownership.
    (when-not (= :dead (:lifecycle @st))
      (when (and (some? old) (not (identical? old incarnation)))
        (forget-root-cell! cell old))
      (swap! st assoc :root incarnation)
      (enrol-root-cell! cell incarnation)))
  cell)

(defn- detach-root!
  "Drop `cell`'s root-incarnation membership on FINAL teardown — the cell is
  proven dead, so it leaves the registry deterministically (the fast path;
  weak membership is the backstop for cells that were never proven dead).
  Idempotent; a no-op when the cell owns no root."
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
  the number of LIVE cells its weak membership currently retains (tool/test
  read). Proves the ownership registry is bounded to retained/mounted cells:
  empty incarnations drop on final teardown (rf2-vxgfnd.85 AC5), and a
  collected member simply stops counting (rf2-mc62sp)."
  ([]
   ;; Read = compact, on BOTH hosts: the optional CLJS FinalizationRegistry only
   ;; accelerates husk reaping, and the JVM WeakHashMap expunges collected members
   ;; but never the now-empty incarnation entry. Compacting here makes a
   ;; fully-collected generation stop being tracked (rf2-vxgfnd.169).
   (doseq [[incarnation members] @root-cells]
     (weak-live members incarnation))
   (count @root-cells))
  ([incarnation]
   (weak-live-count (get @root-cells incarnation) incarnation)))

(defn- release-committed!
  "Release every live lexical-site handle and retain each exact query/target/
  value record with `:handle nil`. A disconnect therefore owns nothing while a
  reconnect can still stabilize exact objects per site. Idempotent."
  [^ViewCell cell]
  (let [st (state cell)]
    (doseq [{:keys [handle]} (vals (:committed @st))]
      (when handle (obs/release! handle)))
    (swap! st update :committed
           (fn [sites]
             (reduce-kv (fn [out sid record]
                          (assoc out sid (assoc record :handle nil)))
                        (empty sites)
                        sites)))))

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
  outlived the synchronous CHECKPOINT that produced it, so a subsequent reconnect
  can honestly prove an Activity hide (rf2-vxgfnd.44). No-op unless the cell is
  still `:disconnected` (a cell already reconnected or torn down needs no settle).
  On CLJS `disconnect!` arms this as a microtask — it fires after the synchronous
  checkpoint unwinds and before the next paint. A reconnect that arrives BEFORE it
  is UNSETTLED / same-checkpoint evidence the host does not further discriminate:
  a StrictMode replay OR two real commits completing in one synchronous stack
  (consecutive `flushSync` hide/reveal), so `connect!` leaves it `:unknown`. A
  reconnect that arrives AFTER it is a settled disconnect — a genuine reveal
  proven an Activity hide. The microtask separates JavaScript checkpoints, not
  React commits (rf2-vxgfnd.164). A headless/JVM fixture calls this explicitly to
  model the host yield of a real reveal. Returns nil."
  [^ViewCell cell]
  (when interop/debug-enabled?
    (let [st (state cell)]
      (when (= :disconnected (:lifecycle @st))
        (swap! st assoc :disconnect-provisional? false))))
  nil)

(defn- arm-disconnect-settle!
  "Arm the settle of `cell`'s provisional disconnect. On CLJS a host microtask
  (`queue-microtask!`) that runs after the current synchronous checkpoint unwinds
  and before the next paint. A reconnect BEFORE it is UNSETTLED / same-checkpoint
  — a StrictMode synchronous mount→cleanup→remount OR two real commits in one
  synchronous stack (consecutive `flushSync` hide/reveal), indistinguishable at
  the host, so it is left un-annotated (`:unknown`); a reconnect AFTER it is a
  settled disconnect a genuine reveal proves a hide. The microtask separates
  JavaScript checkpoints, not React commits (rf2-vxgfnd.164). No auto-settle on
  the JVM headless host (no async render loop); a fixture there settles
  explicitly."
  [^ViewCell cell]
  #?(:cljs (queue-microtask! (fn [] (settle-disconnect! cell)))
     :clj  nil))

(defn- connect!
  "Commit-time lifecycle transition into `:connected`. A transition FROM
  `:disconnected` is a reconnect. A reconnect proves an Activity hide ONLY when
  the prior disconnect had SETTLED — i.e. it outlived the synchronous checkpoint
  that produced it. A SETTLED-then-reconnected interval is a genuine hide→reveal
  and is annotated `:activity-hidden {:proof :reconnect}`. An UNSETTLED reconnect
  is same-checkpoint evidence: it beat the settle. That is CONSISTENT WITH a React
  StrictMode dev replay (mount→cleanup→remount within ONE synchronous commit), but
  it is NOT PROOF of one — two real commits can also complete in a single
  synchronous stack (consecutive `flushSync` hide/reveal) before the settle
  microtask runs. The host supplies no exact discriminator, so the runtime
  DECLINES to annotate: it fabricates no Activity-hide proof and claims no unique
  StrictMode identity; the interval honestly stays `:disconnected {:reason
  :unknown}` (rf2-vxgfnd.44, rf2-vxgfnd.164).

  The annotation is licensed by SETTLE EVIDENCE and by nothing else, so it is
  gated on holding that evidence — structurally, in ONE place. Production elides
  the provisional field, its settle, and this whole branch, and therefore holds
  NO settle evidence: it makes NO Activity-hide claim and leaves every reconnect
  at the honest `:unknown` floor. That is deliberate. Production has no StrictMode
  double-invoke, but it DOES still have two real commits in one synchronous stack
  (`flushSync(hide); flushSync(reveal)`), so a direct production annotation would
  export exactly the fabricated proof the dev path refuses — a build flag may
  change what is RECORDED, never what is CLAIMED (rf2-vxgfnd.164). `:unmounted
  {:proof :host-teardown}` is unaffected in both builds: it rests on an
  authoritative host signal (an explicit root `.unmount`), not on timing."
  [^ViewCell cell]
  (let [st @(state cell)]
    (when (= :disconnected (:lifecycle st))
      ;; Annotate IFF settled evidence is held. An unsettled reconnect beat the
      ;; settle (a StrictMode replay OR two real commits in one synchronous
      ;; stack; the host does not discriminate): clear the provisional flag and
      ;; DECLINE to annotate — fabricate no Activity-hide proof, claim no unique
      ;; StrictMode identity. Production has no evidence at all and so, by the
      ;; same rule, never annotates; the branch DCEs whole (rf2-vxgfnd.164).
      (when interop/debug-enabled?
        (if (:disconnect-provisional? st)
          (swap! (state cell) assoc :disconnect-provisional? false)
          (annotate-open-disconnect! cell :activity-hidden :reconnect))))
    (swap! (state cell) assoc :lifecycle :connected)
    ;; Enrol in the live-cell registry (idempotent — a set) so a frame-destroy
    ;; sweep can find this cell while it observes a live committed dep set.
    (swap! live-cells conj cell)))

(defn disconnect!
  "Effects-cleanup transition (React unmount OR Activity hide —
  indistinguishable at this moment): release handle owners (hidden UI must
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
      ;; Unsettled-reconnect guard (DEV-only — DCEs in production, which has no
      ;; StrictMode double-invoke): mark this disconnect PROVISIONAL and arm its
      ;; settle. A reconnect BEFORE the settle is UNSETTLED/same-checkpoint (a
      ;; StrictMode replay OR consecutive synchronous commits — the host does not
      ;; discriminate, so `connect!` leaves it :unknown); a reconnect AFTER it is
      ;; a genuine reveal `connect!` proves an Activity hide (rf2-vxgfnd.44,
      ;; rf2-vxgfnd.164).
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
  the retained interval is proven an unmount. Detaches handles, marks the
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
      ;; Terminalize and clear every generic registry. A synchronous trace
      ;; listener that re-enters teardown now observes :dead and no-ops instead
      ;; of recursively releasing forever.
      (swap! st (fn [m] (assoc m :lifecycle :dead :committed {})))
      (swap! live-cells disj cell)
      ;; leave the root-incarnation registry — a dead cell is no longer
      ;; retained, so it must not linger as reapable ownership (rf2-vxgfnd.85).
      (detach-root! cell))
    cell))

(defn teardown-frame!
  "Frame-destroy sweep: transition every currently-connected ViewCell whose
  retained subscription targets name frame `frame-id`, plus every still-
  disconnected root-owned ViewCell whose last published site values name it, to
  `:dead` (03 §4 dead-cell lifecycle). Each
  matched cell's handles are detached, its pending notification dropped, and
  its retained interval proven an unmount (`:unmounted {:proof
  :host-teardown}`) — so a subsequent read/probe on such a cell follows the
  dead-cell lifecycle instead of throwing `:rf.error/frame-destroyed` off the
  observation port. Fired from core's `frame/destroy-frame!` through the
  `:ui/on-frame-destroyed!` late-bind hook wired in `re-frame.ui.frames`;
  the sweep runs while the frame is still live, so each cell releases its
  handles against the live sub-cache (symmetric with `disconnect!`). The
  connected membership test uses `cell-retained-frame?`: committed
  subscription targets provide observation attribution. An Activity-hidden cell
  holds no live subscription owners, so its bounded root ownership plus retained
  subscription targets supply corresponding discoverability without another
  global registry. Iterates snapshots, so the per-cell `teardown!` de-enrol is
  safe. Returns the count torn down."
  [frame-id]
  (let [connected (filter #(cell-retained-frame? % frame-id)
                          @live-cells)
        hidden    (into #{}
                        (comp (mapcat (fn [[incarnation members]]
                                        (weak-live members incarnation)))
                              (filter #(= :disconnected (lifecycle %)))
                              (filter #(cell-retained-frame? % frame-id)))
                        @root-cells)
        victims   (into hidden connected)]
    (doseq [cell victims]
      (teardown! cell))
    (count victims)))

(defn report-root-commit!
  "The client `root-commit-reporter`'s mount-lifetime layout-effect SETUP
  (rf2-vxgfnd.275): a React root tree for `incarnation` has COMMITTED, so its host
  teardown will later fire `report-root-teardown!`. Marks the reporter LIVE so a
  subsequent `teardown-root!` awaits that settlement signal (fencing a deferred
  teardown of a rendered root — even a cell-less one). Idempotent. Returns nil."
  [incarnation]
  (when (some? incarnation)
    (swap! live-reporters conj incarnation))
  nil)

(defn report-root-teardown!
  "The client `root-commit-reporter`'s mount-lifetime CLEANUP sentinel
  (rf2-vxgfnd.275), or a graft fixture's synchronous host model: React has torn
  `incarnation`'s root tree down. This is the ROOT-LEVEL host-teardown signal —
  present for EVERY rendered root because the reporter wraps every render, so a
  compiled static/cell-less or entirely Activity-hidden root that owns no
  connected ViewCell still emits it (the population `teardown-root!` could not
  otherwise observe). Clears the reporter-live mark.

  When a `teardown-root!` thunk for this exact incarnation is IN FLIGHT (the host
  `.unmount` ran the reporter cleanup SYNCHRONOUSLY, as React does for a normal
  unmount), this also marks that teardown settled-synchronously, so the driver
  releases the client's claim now. Fired LATER — react-dom 19.2 refusing an
  in-render unmount defers the teardown to its own microtask — there is no
  in-flight signal to match, so it only clears the mark: the deferred settlement
  is driven by `settle-deferred-root!` (the FIFO-ordered microtask). Returns nil."
  [incarnation]
  (when (some? incarnation)
    (swap! live-reporters disj incarnation))
  (let [sig @teardown-settle-signal]
    (when (and sig (some? incarnation) (identical? incarnation (:incarnation sig)))
      (vreset! (:fired sig) true)))
  nil)

(defn retire-root-reporter!
  "Terminally retire `incarnation`'s reporter authority at a SUCCESSFUL exact
  adapter/manual quarantine recovery (rf2-nd7z9h). A throwing host `.unmount`
  aborts before React runs the reporter's mount-lifetime cleanup, so
  `report-root-teardown!` never fires and the incarnation stays STRONGLY held in
  `live-reporters`. Adapter-wide recovery then reclaims the consumed container and
  releases the client's `live-roots` claim — but `release-root!` alone touches
  only `live-roots`, so WITHOUT this retirement the reporter ledger keeps one dead
  token per recovery cycle plus stale reporter-deferred teardown authority (a
  later `teardown-root!` for the retired incarnation would be classified DEFERRED
  off a reporter that can never settle).

  Call ONLY once the terminal container reclaim has completed: the reporter
  authority is then spent, so retiring it lets a subsequent `teardown-root!` for
  this incarnation settle SYNCHRONOUSLY and returns the ledger to BASELINE across
  repeated recovery cycles. Reuses the exact identity-keyed strong set — no second
  registry. Idempotent (`disj`), and — unlike `report-root-teardown!` — it does
  NOT touch the in-flight teardown-settle signal, because recovery runs after the
  throwing thunk has already unwound. A late real-React `report-root-teardown!`
  for this same incarnation is thereafter a harmless no-op whose identity check
  still cannot settle a same-id SUCCESSOR incarnation. Returns nil."
  [incarnation]
  (when (some? incarnation)
    (swap! live-reporters disj incarnation))
  nil)

(defn live-reporter-count
  "The number of root incarnations whose committed reporter is still live
  (tool/test read). A cleanup-failure quarantine whose container reclaim has
  completed is retired (`retire-root-reporter!`), so repeated
  mount → throwing-cleanup → adapter-reclaim cycles return this to baseline
  instead of accreting one strong token per cycle (rf2-nd7z9h)."
  []
  (count @live-reporters))

(defn live-reporter?
  "Whether `incarnation`'s committed reporter is still live (tool/test read).
  A retired predecessor reads false while a same-id successor reads true."
  [incarnation]
  (contains? @live-reporters incarnation))

(defn- settle-deferred-root!
  "Settle a DEFERRED host teardown (rf2-vxgfnd.182). The host `.unmount` returned
  but scheduled this root's teardown for a later microtask, so its cells are
  still connected and the owning claim must stay fenced. Schedule the SETTLEMENT
  — a microtask FIFO-ordered AFTER React's own deferred teardown (so it runs once
  React has swept the cleanups and cleared the container) — that reaps every cell
  still owned by `incarnation` to `:dead` (idempotent: a cell React already
  disconnected upgrades from `:disconnected`, one it left connected is force-dead)
  and then fires `on-settled` to release the client's ownership claim. On the JVM
  headless host there is no async React teardown to await, so the settlement runs
  synchronously."
  [incarnation on-settled]
  (let [settle (fn []
                 (doseq [cell (weak-live (get @root-cells incarnation)
                                         incarnation)]
                   (teardown! cell))
                 (when on-settled (on-settled)))]
    #?(:cljs (queue-microtask! settle)
       :clj  (settle))))

;; PRESENCE, not JS truthiness, decides whether the host `.unmount` thunk threw
;; (rf2-s2cfv). On CLJS a thunk can throw a legitimately falsy value (false/nil);
;; `host-error` holds this identity sentinel until a throw records into it, so
;; such a failure is a REAL, preserved host error — reaped fail-closed over the
;; exact generation and rethrown — never reclassified as a success/deferred
;; teardown that settles and releases ownership. Cross-host: a bare Object on the
;; JVM, a fresh JS object on CLJS; compared only by `identical?`.
(def ^:private no-host-error
  #?(:clj (Object.) :cljs #js {}))

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
  incarnation. Which incarnation(s) that registry sweep — and the captured set
  itself — is scoped to depends ON THE ARITY (rf2-vxgfnd.156):

    - `[root-incarnation unmount-thunk]` — the explicit incarnation is
      AUTHORITATIVE. It alone derives the hidden-cell victims (the deterministic
      path: it reaps a root's hidden cells even when the window captured NONE —
      the whole root hidden, or a single already-hidden cell), AND the
      window-captured set is FILTERED to cells belonging to that exact identity.
      A re-entrant cleanup that disconnects a SIBLING root's cell inside the
      window (a trace listener) therefore neither dies nor
      expands the sweep onto its root — sibling isolation holds even when the
      structural host guarantee does not (the cleanup was app-driven, not a
      React sweep of A's tree).
    - `[unmount-thunk]` — no explicit generation, so the incarnations of every
      WINDOW-CAPTURED cell govern: a captured cell names its own root's
      incarnation, so its still-hidden same-root siblings are reaped too. This is
      sound because a real host `.unmount` sweeps EXACTLY its own root's tree, so
      the captured set is precisely that root's cells.

  Only `:disconnected` owned cells are reaped through the registry; a
  still-`:connected` cell of a SIBLING root's incarnation is never in the set,
  and an incarnation is a fresh per-mount identity, so a replacement root under
  the same root-id is untouched.

  Ordering-robust and re-entrancy-safe by save/restore. If `unmount-thunk`
  THROWS, the original host error still propagates, but an EXPLICIT
  `root-incarnation` is a framework ownership token: every cell belonging to
  that exact generation is force-dead and its handles are released before the
  throw escapes. React may have consumed the host Root handle even though its
  synchronous flush refused; retaining connected observations after the client
  releases that handle would create unreachable framework ownership. The fresh
  incarnation token keeps this fail-closed reap isolated from a later same-id
  replacement. The one-arity form has no generation evidence and therefore can
  reap only cells actually captured by the teardown window. Returns the count of
  cells torn down on success; a host failure rethrows after the targeted reap.

  ## DEFERRED host teardown + settlement (rf2-vxgfnd.182/.275)

  react-dom 19.2 refuses a SYNCHRONOUS `.unmount` from inside render/commit: it
  returns NORMALLY but runs none of this root's effect cleanups, scheduling the
  teardown for a later microtask. Whether the host tore down SYNCHRONOUSLY is
  decided by a ROOT-LEVEL settlement law, independent of ViewCell population
  (rf2-vxgfnd.275): the teardown is DEFERRED iff a COMMITTED reporter for this
  root has not yet torn down (`live-reporters`) AND its mount-lifetime cleanup
  sentinel did NOT fire during the thunk (`report-root-teardown!` → the
  `teardown-settle-signal` volatile). The reporter is present even for a compiled
  static/cell-less or entirely Activity-hidden root that owns no connected cell —
  the case a cell-connectivity probe (the retired `weak-connected`) could not
  discriminate and so mis-classified as synchronous, releasing the claim into a
  still-scheduled teardown. When DEFERRED, this driver does NOT fire `on-settled`
  synchronously — it schedules the SETTLEMENT (`settle-deferred-root!`)
  FIFO-ordered after React's own deferred teardown, so the client holds its
  root-id/container/prefix claim `:tearing-down` until React has actually cleared
  the container. A SYNCHRONOUS teardown (the reporter sentinel fired) fires
  `on-settled` immediately, preserving the ratified same-container
  immediate-remount. An UNRENDERED / pre-commit root has no committed reporter, so
  nothing is pending: it settles synchronously.

  A THROWING `.unmount` keeps its immediate, fail-closed reap of the exact
  generation and rethrows; `on-settled` is NOT fired (rf2-vxgfnd.275): the host
  cannot prove the container free, so the client FAILS CLOSED, leaving the exact
  id/container/prefix claim QUARANTINED `:tearing-down` (reuse requires a fresh
  container), never released into a possibly-still-scheduled teardown.

  Three arities: `[unmount-thunk]` (no explicit incarnation — window +
  captured-cell incarnations only), `[root-incarnation unmount-thunk]` (the
  incarnation-aware path), and `[root-incarnation unmount-thunk on-settled]` (the
  `client/unmount!*` call — `on-settled` releases the claim at the settlement
  boundary)."
  ([unmount-thunk] (teardown-root! nil unmount-thunk nil))
  ([root-incarnation unmount-thunk] (teardown-root! root-incarnation unmount-thunk nil))
  ([root-incarnation unmount-thunk on-settled]
   (let [prev       @teardown-collector
         prev-sig   @teardown-settle-signal
         fired      (volatile! false)
         host-error (volatile! no-host-error)
         captured   (volatile! #{})
         _          (do
                      (reset! teardown-collector #{})
                      ;; rf2-vxgfnd.275 — publish the root-level settlement signal
                      ;; so the reporter's teardown sentinel (fired synchronously
                      ;; by React during a normal `.unmount`) marks this teardown
                      ;; settled. Only armed for the explicit-incarnation path.
                      (reset! teardown-settle-signal
                              (when root-incarnation
                                {:incarnation root-incarnation :fired fired}))
                      (try
                        (unmount-thunk)
                        (catch #?(:clj Throwable :cljs :default) e
                          (vreset! host-error e))
                        (finally
                          (vreset! captured @teardown-collector)
                          (reset! teardown-collector prev)
                          (reset! teardown-settle-signal prev-sig))))
         collected  @captured
         ;; PRESENCE, not truthiness: a throw of a falsy value (false/nil) still
         ;; counts as a host failure (rf2-s2cfv).
         host-threw? (not (identical? @host-error no-host-error))
         explicit?  (some? root-incarnation)
         ;; When an explicit root incarnation is named it is AUTHORITATIVE: this
         ;; teardown owns EXACTLY the cells of that generation (rf2-vxgfnd.156).
         ;; A re-entrant cleanup can disconnect an UNRELATED cell INSIDE this
         ;; window — a router trace listener firing during root A's `.unmount`
         ;; may disconnect a sibling
         ;; root's cell OR a bare/unattached cell — and such a captured cell is
         ;; NOT ours to reap. Reap ONLY cells whose `cell-root` is IDENTICAL to
         ;; the named incarnation: POSITIVE ownership evidence, never absence of
         ;; it (rf2-vxgfnd.251). A captured cell with a different non-nil root, OR
         ;; with NO root ownership (`cell-root` nil — never attached to this
         ;; provider), lacks that evidence and is dropped, leaving it
         ;; reconnectable. The legacy one-arity path has no explicit generation,
         ;; so it trusts every captured cell (a real host `.unmount` sweeps
         ;; exactly its own root's tree, 03 §4).
         owned     (if explicit?
                     (into #{}
                           (filter (fn [c]
                                     (identical? root-incarnation (cell-root c))))
                           collected)
                     collected)
         ;; the incarnations whose hidden cells this teardown owns. Explicit:
         ;; EXACTLY the named token — a captured sibling's token must NOT expand
         ;; the hidden-cell sweep onto that sibling's root. Legacy: inferred from
         ;; every captured cell's own root (its still-hidden same-root siblings).
         incs      (if explicit?
                     #{root-incarnation}
                     (into #{} (keep cell-root) collected))
         ;; already-Activity-hidden owned cells the window could NOT capture —
         ;; still `:disconnected`, belonging to a torn-down incarnation. A
         ;; hidden-but-alive cell is pinned by React's retained fiber, so weak
         ;; membership still discovers it; only collected (unreachable, never
         ;; reconnectable) cells are absent — nothing to reap (rf2-mc62sp).
         hidden    (into #{}
                         (comp (mapcat #(weak-live (get @root-cells %) %))
                               (filter #(= :disconnected (lifecycle %))))
                         incs)
         victims   (if (and host-threw? explicit?)
                     ;; The host handle is consumed/released even on this path.
                     ;; Fail closed over the EXACT root generation, including
                     ;; cells still connected because React ran no cleanup.
                     (into owned (weak-live (get @root-cells root-incarnation)
                                            root-incarnation))
                     (into owned hidden))
         ;; rf2-vxgfnd.275 — the ROOT-LEVEL settlement law. A teardown is DEFERRED
         ;; iff a COMMITTED reporter for this root has not yet torn down
         ;; (`live-reporters`) AND its mount-lifetime cleanup sentinel did NOT fire
         ;; during the thunk (`@fired` — a synchronous host teardown ran it). A
         ;; RENDERED root — cell-bearing OR cell-less/entirely-hidden — has a
         ;; committed reporter, so its deferral is seen regardless of ViewCell
         ;; population (the gap the retired cell-connectivity probe could not see).
         ;; An UNRENDERED / pre-commit root (the render never reached the reporter's
         ;; layout effect) is not a member, so it has no pending host teardown to
         ;; await and settles synchronously. `collected` no longer classifies — it
         ;; only drives the reap (`owned`/`hidden`/`victims`).
         deferred? (and explicit? (not host-threw?) (not @fired)
                        (contains? @live-reporters root-incarnation))]
     (doseq [cell victims]
       (teardown! cell))
     (cond
       host-threw?
       ;; rf2-vxgfnd.275 — the exact generation is force-dead (victims) above;
       ;; on-settled is NOT fired. The host teardown threw, so the container is
       ;; NOT proven free: the client FAILS CLOSED in its own catch, leaving the
       ;; id/container/prefix claim quarantined `:tearing-down` (never released).
       (throw @host-error)

       deferred?
       ;; hold the claim: settle (reap + on-settled) past React's deferred boundary.
       (do (settle-deferred-root! root-incarnation on-settled)
           (count victims))

       :else
       ;; synchronous teardown (or nothing owned): settle immediately.
       (do (when on-settled (on-settled))
           (count victims))))))

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
;; it acquired its handles from — never merely the reused frame-id. `destroy-frame!`
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
  "Post-acquire snapshot `{frame-id -> incarnation-token}` for every frame the
  candidate observation map observes — the acquire-time incarnation snapshot the
  close revalidation resolves against."
  [committed]
  (persistent!
   (reduce (fn [acc {:keys [target handle]}]
             (if (and handle (= :subscription (:kind target)))
               (let [fid (:frame-id target)]
                 (assoc! acc fid (frame/frame-incarnation-token fid)))
               acc))
           (transient {})
           (vals committed))))

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
    :post-stage-acquire — after handles are acquired, BEFORE the incarnation
                     snapshot/current validation.
    :post-acquire  — after validation + the evidence read, BEFORE the publish.
                     A full destroy of the ACQUIRED incarnation + a fresh same-id
                     incarnation here proves the frame-close revalidation joins the
                     commit to the acquired incarnation's teardown, not the
                     replacement id (rf2-vxgfnd.88).
    :pre-publish   — INSIDE `publish-commit!`, between that attempt's authority
                     SAMPLE (the cell-state snapshot + the registered-slot token)
                     and its publish CAS, fired exactly ONCE. An
                     `advance-generation!` (cell-local axis) or a same-view
                     re-registration (registry axis) here lands in EXACTLY the
                     former check-to-use window and must be caught by the CAS /
                     slot-token gate, never published (rf2-77pb08)."
  nil)

(defn- slot-body-revision
  "The registered body revision carried by an ALREADY-SAMPLED HMR slot object
  `slot` (nil when the slot is absent or was never registered). The publication
  linearization captures the slot ONCE per attempt as its authority token and
  reads the revision out of that exact sample, never re-derefing the live
  registry between the token capture and the publish CAS."
  [slot]
  (when (:hmr-registered? slot)
    (:hmr-body-revision slot)))

(defn- publish-commit!
  "Settle the FINAL HMR body-authority validation together with the state
  publication (rf2-77pb08) across two axes of differing strength — a cell-state
  CAS linearization and a best-effort registered-slot identity check taken
  immediately before it (detailed below). Publishes `new-committed` onto `cell`
  and returns `:published`, or returns `:stale` having published NOTHING when
  the body authority moved.

  ## The race this closes

  The rf2-vxgfnd.214 fence sampled the cell generation + the registered-view
  revision and THEN performed an INDEPENDENT `swap!` to publish — a check-to-use
  gap. An `advance-generation!` (cell-local axis) or a same-view re-registration
  (registry axis) landing AFTER the sample but BEFORE the swap published a
  stale-generation capture that connected and owned handles while current
  authority had already moved. Re-reading the authority a third time only MOVES
  the race to between that read and the swap; it is not a linearization point.

  ## The linearization — two axes, two strengths

  Validation and publication settle against the cell-state value read at the top
  of the loop (the `complete-flush!` rf2-vxgfnd.180 idiom), but the two authority
  axes do NOT carry the same guarantee — a distinction that matters for a future
  maintainer or async-host author, so it is stated precisely:

    - CELL-LOCAL axis — a GENUINE single-atom CAS linearization. The capture
      generation is compared to `(:generation s)` of the SAME snapshot `s` the
      `compare-and-set! st s published` commits. A racing `advance-generation!`
      is either already visible in `s` (→ `:stale`) or lands after the sample
      and FAILS the CAS (`st` ≠ `s`); the loop then re-reads and re-validates.
      Airtight even under genuine concurrency.
    - REGISTRY axis — a BEST-EFFORT slot-IDENTITY gate, NOT a single-atom
      linearization. The view's HMR slot is sampled ONCE per attempt as an
      authority TOKEN; the publish gate is `(and (identical? token (get
      (deref view-generations) view-id)) (compare-and-set! st s published))` — TWO reads
      of TWO different atoms (`view-generations` for the token, `st` for the
      CAS). The token is checked IMMEDIATELY BEFORE the CAS, not fused atomically
      WITH it. A same-view re-registration swaps `view-generations` to a fresh
      slot object, so the token gate fails and the loop re-reads a moved revision
      (→ `:stale`). A residual two-expression window remains (the `identical?`
      read → the `st` CAS) in which a re-registration would leave `st` untouched
      and let the CAS publish a stale-revision capture. That window is
      UNREACHABLE on the single-threaded CLJS render host where HMR/registry
      authority lives — JS never preempts between two non-yielding expressions
      and no application/framework callback runs in it (`published` is computed
      before the `if`) — and the whole authority arm is DCE'd in production (see
      below). It reopens ONLY under genuinely unsynchronized multi-threaded
      concurrent commit-of-the-same-view + re-registration, which is outside
      re-frame2's per-frame single-threaded contract.

  The `*commit-barrier* :pre-publish` seam fires ONCE, between the first
  attempt's authority sample and its CAS, so a fixture can interpose either
  authority advance in EXACTLY that window and prove the gate holds.

  DEV/HMR-only: production mints every cell at body revision 0 and never
  advances it, so `interop/debug-enabled?` constant-folds the entire authority
  arm — no `view-generations` deref, no token capture — leaving a single publish
  CAS that succeeds first try on the single-threaded host."
  [^ViewCell cell cap view-id new-committed]
  (let [st      (state cell)
        cap-gen (:generation cap)
        fired   (volatile! false)]
    (loop []
      (let [s     @st
            token (when interop/debug-enabled? (get @view-generations view-id))]
        (when (and (not @fired) (some? *commit-barrier*))
          (vreset! fired true)
          (*commit-barrier* :pre-publish cell))
        (let [stale? (and interop/debug-enabled?
                          (or (not= cap-gen (:generation s))
                              (when-some [reg (slot-body-revision token)]
                                (not= cap-gen reg))))]
          (if stale?
            :stale
            (let [published (assoc s :committed new-committed)]
              (if (and (or (not interop/debug-enabled?)
                           (identical? token (get @view-generations view-id)))
                       (compare-and-set! st s published))
                :published
                (recur)))))))))

;; ---------------------------------------------------------------------------
;; S6 committed-instance record (Ruling 1; EP-0033 §Two evidence layers,
;; spec/004 §View identity) — the per-commit view-evidence record minted on the
;; connected-commit path.
;;
;; DEBUG-ONLY. The whole view-evidence plane is production-erased (G-7/G-11);
;; every mint below sits behind `interop/debug-enabled?` at the call site (in
;; `commit*`), so Closure DCEs it and the render-key source is never allocated
;; nor advanced in an :advanced production build — the same gate the invalidation
;; evidence plane and the HMR provisional field already ride.
;;
;; OPTION 1A (honest capture). The record carries exactly what the substrate owns
;; at commit, and NOT what it cannot honestly source:
;;   - :render-key is a MODULE-GLOBAL monotonic integer minted fresh at EACH
;;     DISTINCT connected commit (so a genuine re-render increments it; a
;;     StrictMode re-commit of the SAME capture does NOT — see
;;     `mint-commit-record!`) — a total order over committed renders for the
;;     `data-rf-render-key` DOM stamp (rf2-ny34u) and a React Performance-Tracks
;;     timeline correlate. It is NOT the legacy `:rf.sub/reader-render-key`: that
;;     is a DIFFERENT identity (the stock-adapter `[view-id instance-token]` wire
;;     shape), stamped during render-time sub reads from `re-frame.views/
;;     *render-key*` — a dynamic var implementation/ui never binds, so a compiled
;;     defview read stamps none. The direct sub→view relation is this record's
;;     OWN `:observations`/`:query` rows, not a cross-key correlation to a key
;;     this substrate never mints (rf2-8ds0v truth repair, PR #6562).
;;   - frame attribution is PER-OBSERVATION, never a single fabricated fact: a
;;     ViewCell observes a SET of frames (an override-only cell observes none),
;;     so each observation carries its own target's :frame-id and the record
;;     claims NO singular top-level :frame-id.
;;   - :parent-render-key is DEFERRED — no S6 surface consumes view parentage, so
;;     NO parent-capture machinery exists here.
;;   - :rf.view/causes is the per-commit vector of DETAILED cause RECORDS (Ruling
;;     2, slice b as reworked, rf2-qkq2k) — each `{:cause <kind> …ruled-fields}`,
;;     projected from EXISTING sources, never a new capture machine and never a
;;     general evidence framework (only the fields explicitly ruled per cause):
;;       :mount           the connect lifecycle (:fresh->:connected).
;;       :story-override  a (re)acquired override target — identity + version.
;;       :subscription    detail captured at the port note (`note-commit-cause`):
;;                        target / query / frame-id + version :from->:to + :epoch.
;;       :local-state     the substrate-owned local writer bridge.
;;       :hmr / :disposed bare markers carried forward in `:pending-commit-causes`.
;;       :foreign-or-react  the honesty fallback when a commit carries no cause.
;;     DEFERRED, never emitted (each a deferred-with-trigger row in slice d's
;;     EP-0033 delta): :hmr-remount (honest per-instance attribution needs a
;;     teardown->remount pairing signal — cross-surface + a fiddly closing rule —
;;     so a view-granularity emit would mislabel unrelated fresh mounts) and
;;     :epoch-restore (restore provenance outside perform-restore!'s dynamic
;;     extent; Mike ruling option c). See `commit-causes` / `cause-order`.
;; ---------------------------------------------------------------------------

(defonce ^:private render-key-counter
  ;; The module-global monotonic :render-key source (DEBUG-only; nil in
  ;; production, where no committed-instance record is ever minted). `defonce` is
  ;; module-lived and deliberately survives `reset-scheduler!` — render-keys are a
  ;; strictly-increasing total order over EVERY committed render for the module's
  ;; lifetime, so consumers compare them (a later commit's key is greater), never
  ;; read absolute values.
  (when interop/debug-enabled? (atom 0)))

(defn- next-render-key!
  "Mint the next monotonic render-key. Called ONLY under `interop/debug-enabled?`
  at the connected-commit site, so production never reaches it and the counter
  stays nil + unallocated there."
  []
  (swap! render-key-counter inc))

(defn- project-observations
  "Project this render's captured sites into the per-commit :observations vector,
  in compiler render order. PURE PROJECTION of data the capture already holds —
  the resolved site target plus its ownership-free probe evidence — so it makes
  no port call, reads no handle, and performs no NEW capture (Ruling 1: the frame
  is 'already captured per-site').

  Each observation carries its TARGET'S :frame-id — the amended per-observation
  frame attribution (nil for a Story override, which resolves against no frame).
  :target-id / :version are the node identity + version THIS render observed
  (nil when probed cold, or for a static override); :owned? is true for an owned
  subscription node handle and false for a static Story-override handle."
  [new-order new-by]
  (mapv (fn [sid]
          (let [{:keys [target evidence query]} (get new-by sid)
                kind (:kind target)]
            {:kind      kind
             :frame-id  (:frame-id target)
             :query     query
             :target-id (:node-key evidence)
             :version   (:node-version evidence)
             :owned?    (= :subscription kind)}))
        new-order))

(defn note-local-state!
  "DEBUG-only bridge (Ruling 2 :local-state): the substrate-owned local writer
  (`re-frame.ui.hooks` `local-state`) records that a host-only local mutation drove
  `cell`'s re-render. React (not the ViewCell scheduler) owns that re-render, so
  there is no pending-window flush to carry the cause — and, unlike a port fold,
  the write is confirmed only at COMMIT time (post-render), so it is NOT a
  render-fenced pending fold. The writer instead sets the commit-time
  `:local-state-committed?` FLAG, which `mint-commit-record!` reads (like
  `mounting?`) and clears for THIS commit's :rf.view/causes (`:local-state` carries
  no ruled detail, so its record is a bare marker). Being a commit-time flag rather
  than a pre-render fold is what keeps it eligible for the very commit whose layout
  phase confirms it, immune to the eww3k render-waterline fence (rf2-eww3k).

  The CALLER (`re-frame.ui.hooks`) fires this only for an ACTUALLY-COMMITTED value
  change: React 19.2 bails a no-op setter (and a same-batch net-zero `0->1->0`)
  WITHOUT committing, so noting on every setter invocation left a stale marker that
  contaminated a LATER unrelated commit (rf2-qkq2k / rf2-bvqu0). Setting the same
  boolean flag twice is idempotent, so a StrictMode replay is harmless.

  No-op on a nil cell (a `local` used outside a live capture) and elided whole in
  production. Never marks the cell dirty or advances a revision — React already
  re-renders, so double-scheduling is deliberately avoided. Returns nil."
  [^ViewCell cell]
  (when (and interop/debug-enabled? (some? cell))
    (swap! (state cell) assoc :local-state-committed? true))
  nil)

(def ^:private ^:const cause-order
  "The canonical, deterministic ordering of the SHIPPED S6 :rf.view/causes roster
  (Ruling 2, as reworked rf2-qkq2k). A commit's present causes project into their
  detailed records in this order, so the vector is stable across hosts and runs.

  DEFERRED — never emitted by slice b (each a deferred-with-trigger row in slice
  d's EP-0033 delta; consumers keep tolerating absence, Xray 021 §3.4.1):
    - :hmr-remount   honest per-instance attribution needs a teardown->remount
                     pairing signal (a remounted instance is React-indistinguishable
                     from a fresh mount at the same view generation using only the
                     view-global remount counter). That is cross-surface (the runtime
                     Outer shell + the ViewCell) plus a fiddly closing rule — the same
                     'new capture machine, fiddly close' class the fence excludes — so
                     the S3 view-granularity emit (which mislabels unrelated later
                     mounts) is DEFERRED rather than shipped dishonestly. Trigger: a
                     substrate remount-pairing channel that ties a mount to its
                     predecessor's teardown.
    - :epoch-restore restore provenance fires from the deferred reactive commit
                     loop, outside `perform-restore!`'s dynamic extent, and headless
                     hosts have no value-movement watch (Mike ruling option c).
  `:foreign-or-react` is the standalone honesty fallback (not in the order)."
  [:mount :story-override :subscription :local-state :hmr :disposed])

(defn- cause-identity
  "The coalescing identity of a pending port fold: a `:subscription` coalesces by
  its observed TARGET (the upstream node identity, the `:target` axis); every other
  kind collapses to one record per kind (rf2-sy536). Two subscriptions to DIFFERENT
  targets have DIFFERENT identities, so they never merge into one fabricated span."
  [entry]
  (if (= :subscription (:cause entry))
    [:subscription (:target entry)]
    (:cause entry)))

(defn- coalesce-pending
  "Coalesce the drained fold vector `eligible` into one record per DISTINCT causal
  identity, in first-appearance order (rf2-sy536). Repeated same-target
  `:subscription` folds merge to one record spanning that target's EARLIEST `:from`
  to its LATEST `:to`/`:epoch`; two DISTINCT targets stay two records and neither
  disappears; bare markers collapse. It never fabricates a cross-target transition
  by mixing one target's `:from` with another's `:to`."
  [eligible]
  (let [{:keys [order by-id]}
        (reduce (fn [acc entry]
                  (let [id (cause-identity entry)]
                    (if-some [prior (get (:by-id acc) id)]
                      (assoc-in acc [:by-id id]
                                (if (= :subscription (:cause entry))
                                  (assoc entry :from (or (:from prior) (:from entry)))
                                  entry))
                      (-> acc
                          (update :order conj id)
                          (assoc-in [:by-id id] entry)))))
                {:order [] :by-id {}}
                eligible)]
    (mapv by-id order)))

(defn- commit-causes
  "Project THIS connected commit's :rf.view/causes vector — a vector of DETAILED
  cause RECORDS (Ruling 2, reworked rf2-qkq2k), each `{:cause <kind> …ruled-fields}`
  — from signals that ALREADY exist, never a new capture machine. EP-0033 makes
  causes a VECTOR because one commit can have several: two moved dependencies yield
  two records, not one collapsed span (rf2-sy536).

    - :mount          `mounting?` — the cell's pre-commit lifecycle was :fresh
                      (the :fresh->:connected transition). Bare marker.
    - :story-override `override-details` — ONE ruled `{:override-id :version}` per
                      (re)acquired site whose target is a static Story override;
                      sibling overrides are each preserved (never `some`-dropped).
                      Classification, not reconstruction.
    - :local-state    `local-state?` — the commit-time host-write flag set by the
                      substrate local writer (`note-local-state!`), confirmed only
                      at commit (post-render), so it is a COMMIT-TIME fact, not a
                      render-fenced pending fold. Bare marker.
    - :subscription / :hmr / :disposed
                      captured at their cause site into the render-fenced
                      `:pending-commit-causes` vector (`note-commit-cause` at the
                      port note) and coalesced per identity. :subscription carries
                      its ruled target/query/frame-id + version :from->:to + :epoch
                      detail; the rest are bare markers.
    - :foreign-or-react
                      the honesty fallback — a commit that carries no other cause
                      (a foreign React re-render, or a headless value move caught at
                      commit step 5 with no evidence window). Never has detail.

  `eligible` is the render-fenced fold vector (rf2-eww3k). Records project in the
  canonical `cause-order`; same-kind ordering is stable by first-appearance.
  `:hmr-remount` / `:epoch-restore` are deferred (see `cause-order`) and never
  appear. Empty is impossible: the fallback always yields one record."
  [eligible mounting? override-details local-state?]
  (let [folded  (coalesce-pending eligible)
        by-kind (cond-> (group-by :cause folded)
                  mounting?              (assoc :mount [{:cause :mount}])
                  (seq override-details) (assoc :story-override
                                                (mapv #(assoc % :cause :story-override)
                                                      override-details))
                  local-state?           (assoc :local-state [{:cause :local-state}]))
        ordered (into [] (mapcat by-kind) cause-order)]
    (if (seq ordered)
      ordered
      [{:cause :foreign-or-react}])))

(def ^:dynamic ^:no-doc *commit-publish-barrier*
  "JVM linearization TEST SEAM — nil in production (one nil check per minted
  record, zero further cost), NEVER bound off a test path (the
  `*completion-barrier*` idiom). Bound to a `(fn [cell] …)`,
  `mint-commit-record!` calls it at the ONE deterministic point BETWEEN reading
  `:pending-commit-causes` and the `compare-and-set!` that publishes the record +
  advances the stash, so a fixture can interleave a concurrent cause fold
  (`enrol-dirty!` / `note-local-state!`) INSIDE the take->publish window and prove
  the racing cause is never LOST (rf2-qkq2k). Because publication is a
  compare-and-set! RETRY loop, a fold landing in the barrier window fails the CAS
  and the mint re-reads over the fresh state. What that fold then does is decided
  by the eww3k WATERLINE, not erased: a PORT fold (subscription/hmr/disposed) lands
  ABOVE the render waterline, so it is FENCED into the residual stash and drives
  the next commit (rf2-eww3k); the commit-time `:local-state` FLAG is read fresh
  and belongs to THIS commit. Neither is dropped by an unconditional clear."
  nil)

(defn- mint-commit-record!
  "DEBUG-only: mint a fresh monotonic :render-key and publish the S6
  committed-instance record onto `cell` for THIS connected commit (Ruling 1/2).
  Reads the cell's just-published state, so :view-id / :generation / :root-id
  reflect the commit that connected. `:root-id` is the owning root incarnation —
  the opaque per-mount token the tool projection resolves to the authored root-id
  name (nil under no root, e.g. Tier-1/JVM). Projects the per-commit
  :rf.view/causes vector of DETAILED cause records (Ruling 2, reworked rf2-qkq2k)
  from the carry-forward cause stash plus the commit-time lifecycle / override
  facts, then CLEARS the stash so a subsequent causeless re-render honestly reports
  :foreign-or-react. `mounting?` is the cell's PRE-connect lifecycle fact
  (`:fresh`), read by the caller before `connect!`. Returns the record.

  RENDER-WATERLINE FENCE (rf2-eww3k). The pending-cause vector is drained only up
  to the render's captured `:cause-waterline` (the fold count when THIS render
  began); folds ABOVE it — a movement that drove no already-rendered commit,
  including one racing in the publish barrier — stay in the residual stash and
  drive the next commit rather than being back-attributed here.

  ATOMIC take/publish (rf2-qkq2k CAS fix). The stash read + record publish + stash
  advance + `:local-state` flag clear are ONE `compare-and-set!` over the value read
  at the top of the loop, so a concurrent cause fold on the JVM host (a racing
  `enrol-dirty!` / `note-local-state!` between the read and the advance) can no
  longer be LOST: a fold that commits BEFORE the CAS fails it (the loop re-reads,
  fences it by the waterline, and keeps it as residual), and one AFTER enrols a
  fresh next stash. `next-render-key!` is minted ONCE before the loop, so a retry
  never wastes a key. Single-threaded CLJS runs exactly one iteration.

  STRICTMODE REPLAY IDEMPOTENCE (rf2-8ds0v, PR #6567). React 19 StrictMode
  intentionally re-invokes the ViewCell's reconcile layout effect
  (setup→cleanup→setup) with the SAME committed capture (the effect closure of
  ONE committed render). The reconciler is already OWNERSHIP-idempotent (kept-
  check retains/reacquires), but a naive re-mint OVERWROTE the genuine first
  record — its `:mount` cause — with a spurious `:foreign-or-react` replay record,
  advanced `:render-key` twice for ONE rendered commit, and drained the causes.
  So a mint is keyed on the ORIGINATING capture's object identity (stamped as
  `:commit-record-capture`): a re-commit of the EXACT same capture is a no-op —
  the first record stands, `:render-key` does not advance (the counter is not even
  touched), and causes are not drained. This is not a StrictMode special case but
  the honest invariant it exposed: ONE committed-instance record per DISTINCT
  committed render. A genuine re-render (or a real hide→reveal that re-renders)
  produces a FRESH capture object, so it is never mistaken for a replay and mints
  honestly; a pure hide→reveal that does NOT re-render reuses the capture and is
  correctly not a new committed render."
  [^ViewCell cell cap new-order new-by mounting? to-acquire]
  (let [st  (state cell)
        st0 @st]
    (if (identical? cap (:commit-record-capture st0))
      ;; StrictMode replay of the EXACT same committed capture — idempotent: the
      ;; genuine first record stands, no render-key is minted, no causes drained.
      (:commit-record st0)
      (let [waterline  (:cause-waterline cap)
            ;; ONE ruled record per (re)acquired Story-override target, in render
            ;; order — `keep` preserves siblings `some` (first-only) dropped
            ;; (rf2-sy536).
            override-details (into []
                                   (keep (fn [sid]
                                           (let [t (:target (new-by sid))]
                                             (when (= :story-override (:kind t))
                                               {:override-id (:override-id t)
                                                :version     (:version t)}))))
                                   to-acquire)
            base       {:render-key    (next-render-key!)
                        :view-id       (:view-id st0)
                        :generation    (:generation cap)
                        :root-id       (:root st0)
                        :connection    :connected
                        :observations  (project-observations new-order new-by)}]
        (loop []
          (let [s        @st
                pending  (or (:pending-commit-causes s) [])
                n        (count pending)
                cut      (if (and waterline (< waterline n)) waterline n)
                eligible (subvec pending 0 cut)
                residual (subvec pending cut)
                causes   (commit-causes eligible mounting? override-details
                                        (boolean (:local-state-committed? s)))
                record   (assoc base :rf.view/causes causes)
                next-s   (-> (assoc s :commit-record record)
                             ;; stamp the originating capture so a StrictMode
                             ;; re-commit of THIS capture is caught as a replay.
                             (assoc :commit-record-capture cap)
                             (assoc :pending-commit-causes residual)
                             (dissoc :local-state-committed?))]
            (when-some [barrier *commit-publish-barrier*] (barrier cell))
            (if (compare-and-set! st s next-s)
              record
              (recur))))))))

(defn- commit*
  "Run the 8-step layout commit for `cell` against the exact immutable
  `capture` returned beside the committed host element by `with-capture`.
  Idempotent: an unchanged committed set + capture reconciles to a
  no-op (kept-check retains every handle untouched), so StrictMode's
  release/reacquire replay is naturally balanced.

  1. Reject a stale-generation capture (HMR) — return `:stale`, the host
     re-renders (no ownership touched).
  2. A `:dead` cell fails loudly — reconnection after teardown is not
     allowed.
  3. Kept-check every previously-committed site with `(current? handle
     target)`; unchanged live handles are RETAINED untouched, a failed check
     (disposed node, frame swap, restabilized query, moved override)
     classifies the site as retargeted.
  4. STAGE-acquire every newly-observed or retargeted target BEFORE
     releasing anything (acquire-before-release — a shared node never falls
     through its zero-owner edge). On ANY acquisition failure every staged
     handle is synchronously released in REVERSE acquisition order, the
     prior committed set stays installed, and the typed error propagates.
  5. Compare each acquired node's version + frame/registry epochs against
     the render's probe evidence — for BOTH retained and staged handles, so a
     retained site's movement is caught here on a non-watchable headless host
     that has no value-movement watch (rf2-vxgfnd.39).
  6. Publish the committed site values + the new dependency set (retained +
     staged) — before the user can interact with the new DOM.
  7. Release the prior handles of dropped + retargeted sites.
  8. If any evidence moved in the render→commit gap, advance the revision
     and notify — React corrects BEFORE paint.

  Returns `cell` on a normal commit or `:stale` on a rejected generation."
  [^ViewCell cell cap]
  (let [st  (state cell)
        st0 @st]
    (cond
      ;; step 1 — stale body revision. The cell-local comparison catches an
      ;; explicit sync/remount. The authoritative slot comparison closes the
      ;; harder render(old) → registration(new) → layout(old) window even when
      ;; the cell has not rendered again yet. Direct/headless callers with no
      ;; registered slot retain the cell-local contract.
      (or (not= (:generation cap) (:generation st0))
          (and interop/debug-enabled?
               (when-some [current (registered-view-revision (:view-id st0))]
                 (not= (:generation cap) current))))
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
        (let [committed  (:committed st0)          ;; sid -> site record
              new-order  (:order cap)              ;; sid, render order
              new-by     (:by-site cap)
              new-set    (set new-order)
              ;; step 3 — kept-check
              retained   (persistent!
                           (reduce
                             (fn [acc [sid prior]]
                               (let [handle (:handle prior)]
                                 (if (and handle
                                          (contains? new-set sid)
                                          (obs/current? handle
                                                        (:target (new-by sid))))
                                   (assoc! acc sid
                                           (assoc (select-keys (new-by sid)
                                                               [:query :target :value])
                                                  :handle handle))
                                 acc)))
                             (transient {})
                             committed))
              retained?  (fn [sid] (contains? retained sid))
              to-release (persistent!
                           (reduce
                             (fn [acc [sid record]]
                               (if (or (retained? sid) (nil? (:handle record)))
                                 acc
                                 (assoc! acc sid record)))
                             (transient {})
                             committed))
              to-acquire (into [] (remove retained?) new-order)
              on-change  (on-change-fn cell)
              ;; step 4 — transactional stage-acquire
              staged     (loop [ks     to-acquire
                                acc    []]
                           (if (empty? ks)
                             acc
                             (let [sid    (first ks)
                                   target (:target (new-by sid))
                                   handle  (try
                                            (obs/acquire! target on-change)
                                            (catch #?(:clj Throwable :cljs :default) e
                                              ;; rollback: release staged in
                                              ;; REVERSE acquisition order; the
                                              ;; prior committed set stays
                                              ;; installed; propagate the throw.
                                              (doseq [[_ record] (rseq acc)]
                                                (obs/release! (:handle record)))
                                              (throw e)))]
                               (recur (rest ks)
                                      (conj acc
                                            [sid (assoc (select-keys (new-by sid)
                                                                     [:query :target :value])
                                                        :handle handle)])))))
              staged-map (into {} staged)
              candidate (merge retained staged-map)
              ;; A JVM fixture can destroy/recreate the just-acquired frame in
              ;; the formerly-uncovered acquire→snapshot window.
              _ (when-some [barrier *commit-barrier*]
                  (barrier :post-stage-acquire cell))
              incarnations (committed-frame-incarnations candidate)
              candidate-current?
              (every? (fn [[sid record]]
                        (obs/current? (:handle record) (:target (new-by sid))))
                      candidate)]
          (if-not candidate-current?
            (do
              ;; One of the acquired/retained handles belonged to an incarnation
              ;; that vanished before a trustworthy snapshot could be paired
              ;; with it. Roll back only newly staged ownership, preserve the
              ;; prior committed set, and synchronously invalidate so the host
              ;; re-probes the current incarnation before paint.
              (doseq [[_ record] (rseq staged)]
                (obs/release! (:handle record)))
              (advance-revision! cell)
              cell)
            (let [
              ;; step 5 — evidence comparison: read EACH acquired node (staged AND
              ;; retained) against the render's probe evidence, so movement in the
              ;; render→commit gap is caught before paint (invariant 5).
              ;;
              ;;   - STAGED handles: their freshly-installed watch could not have
              ;;     fired for a pre-acquire gap move, so step 5 is the SOLE catch
              ;;     on every host.
              ;;   - RETAINED handles: on a WATCHABLE host their live watch already
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
              moved-in? (fn [[sid record]]
                          (evidence-moved? (obs/read (:handle record))
                                           (:evidence (new-by sid))))
              staged-moved?   (boolean (some moved-in? staged))
              retained-moved? (boolean (some moved-in? retained))
              new-committed candidate]
          ;; :post-acquire test seam — a fixture may destroy the ACQUIRED
          ;; incarnation + recreate the id here to prove the revalidation below
          ;; joins this commit to the acquired incarnation's teardown, not the
          ;; replacement id (rf2-vxgfnd.88).
          (when-some [barrier *commit-barrier*] (barrier :post-acquire cell))
          ;; FINAL HMR-authority fence + publication — two axes of differing strength
          ;; (rf2-77pb08, completing rf2-vxgfnd.214). Step 1 samples the body
          ;; authority ONCE — before the :pre-acquire barrier, the acquire/cache
          ;; callbacks, and the :post-acquire barrier, EVERY one of which can
          ;; synchronously advance the authoritative body revision (a same-shell
          ;; re-registration landing in the render→commit gap). The .214 fence
          ;; re-read the authority HERE and then performed an INDEPENDENT `swap!`
          ;; to publish — a check-to-use gap in which an `advance-generation!`
          ;; (cell-local axis) or a same-view re-registration (registry axis) could
          ;; land and let a stale-generation capture publish and connect. A third
          ;; read only MOVES that gap. `publish-commit!` instead CAS-linearizes
          ;; the final cell-state validation with the step-6 publication
          ;; (cell-local axis), and checks the registered-slot identity token
          ;; IMMEDIATELY BEFORE that CAS (registry axis — best-effort under the
          ;; single-threaded CLJS host), so no pause interposed before the CAS can
          ;; publish a stale capture. On rejection it publishes NOTHING; we release
          ;; ONLY the newly staged handles in reverse acquisition order, leaving the
          ;; prior committed set / values / lifecycle untouched, and return :stale
          ;; exactly as step 1 does (the re-registration already notified the shell,
          ;; so a fresh render at the new body is inbound; unlike the
          ;; candidate-current? incarnation path this needs no advance-revision!).
          ;; DEV/HMR-only: production mints every cell at body revision 0 and never
          ;; advances it, so the authority arm DCEs under goog.DEBUG=false — no
          ;; registry lookup, no hot-path bookkeeping.
          (if (= :stale (publish-commit! cell cap (:view-id st0)
                                         new-committed))
            (do
              (doseq [[_ record] (rseq staged)]
                (obs/release! (:handle record)))
              :stale)
            (do
              ;; step 6 (publish exact per-site query/value + dependency set)
              ;; already completed atomically inside publish-commit!.
              ;; step 7 — release dropped + retargeted prior handles
              (doseq [[_ record] to-release]
                (obs/release! (:handle record)))
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
              ;;       while a replacement went live under the id — so an old handle
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
                (do
                  ;; S6 committed-instance record (Ruling 1/2; EP-0033 §Two
                  ;; evidence layers). Only a CONNECTED commit reaches here — a
                  ;; stale, superseded, torn-down or never-committed (speculative)
                  ;; render publishes nothing, so no instance is fabricated
                  ;; (I-1/I-2). Mint a fresh monotonic :render-key + the per-commit
                  ;; record, incl. the Ruling-2 :rf.view/causes vector. `st0` is the
                  ;; PRE-`connect!` state read at the top of `commit*`, so its
                  ;; `:fresh` lifecycle is the honest :mount fact (connect! has since
                  ;; flipped it to :connected); `to-acquire` names the (re)acquired
                  ;; sites, classifying a moved Story override.
                  ;; DEBUG-ONLY: the whole plane is production-erased (G-7/G-11), so
                  ;; this DCEs under goog.DEBUG=false and no render-key advances.
                  (when interop/debug-enabled?
                    (mint-commit-record! cell cap new-order new-by
                                         (= :fresh (:lifecycle st0)) to-acquire))
                  ;; step 8 — moved evidence corrects before paint. The staged catch
                  ;; always advances synchronously; a RETAINED catch advances only when
                  ;; no live watch already caught the move — a pending (`dirty?`) cell is
                  ;; a watchable host whose scheduled flush already corrects before paint,
                  ;; so advancing here too would add a redundant render (rf2-vxgfnd.39).
                  (when (or staged-moved?
                            (and retained-moved? (not (dirty? cell))))
                    (advance-revision! cell))))
              cell)))))))))

(defn commit!
  "Commit a render capture: run the 8-step layout-commit reconciler for `cell`
  against its exact immutable `cap`."
  [^ViewCell cell cap]
  (commit* cell cap))

;; ---- test/inspection reads --------------------------------------------------

(defn committed-target-keys
  "Legacy target projection of the cell's LIVE installed dependency set.
  Equal-query lexical sites intentionally collapse in this compatibility view;
  use `committed-sites` to inspect ownership identity."
  [^ViewCell cell]
  (into #{}
        (comp (filter :handle) (map (comp target-key :target)))
        (vals (:committed @(state cell)))))

(defn committed-values
  "Legacy target-keyed value projection. Equal targets collapse here by
  definition; canonical state is `committed-sites`."
  [^ViewCell cell]
  (persistent!
    (reduce (fn [out {:keys [target value]}]
              (assoc! out (target-key target) value))
            (transient {})
            (vals (:committed @(state cell))))))

(defn committed-handle
  "Legacy target-keyed installed handle projection, or nil. If two sites share
  a target this returns one of their distinct handles; use `committed-site`."
  [^ViewCell cell tk]
  (some (fn [{:keys [target handle]}]
          (when (= tk (target-key target)) handle))
        (vals (:committed @(state cell)))))

(defn committed-sites
  "The canonical `{sid -> {:query :target :value :handle}}` lexical ownership
  map for `cell` (internal tool/test seam). Disconnected records remain with
  `:handle nil`; conditionally absent sites are not present."
  [^ViewCell cell]
  (:committed @(state cell)))

(defn committed-site
  "The canonical committed record for lexical `sid`, or nil."
  [^ViewCell cell sid]
  (get (:committed @(state cell)) sid))

(defn revision
  "The cell's current revision integer (tool/test read)."
  [^ViewCell cell]
  (:revision @(state cell)))

(defn commit-record
  "The cell's most-recent CONNECTED-commit S6 committed-instance record, or nil
  when the cell has never connected — and always nil in a production build, where
  the view-evidence plane is elided. The per-commit record (Ruling 1/2; EP-0033
  §Two evidence layers): integer :render-key, :view-id, :generation, :root-id,
  :connection, a per-observation :observations vector, and the per-commit
  :rf.view/causes vector (Ruling 2 — the SIX shipped kinds `:mount` /
  `:story-override` / `:subscription` / `:local-state` / `:hmr` / `:disposed`
  projected from their existing sources, plus the `:foreign-or-react` fallback; one
  record per distinct causal identity, rf2-sy536). Both `:hmr-remount` and
  `:epoch-restore` are DEFERRED and never produced by slice b. There is deliberately
  NO singular :frame-id (frame attribution is per-observation) and NO
  :parent-render-key (deferred). Tool/test read."
  [^ViewCell cell]
  (:commit-record @(state cell)))

(defn render-key
  "The integer :render-key of the cell's most-recent connected commit, or nil.
  Module-global monotonic and fresh per connected commit (tool/test read).

  The React ViewCell commit site (`re-frame.ui.viewcell`) reads this back right
  after `commit!` to stamp `data-rf-render-key` onto the committed host-root DOM
  node (dev only; rf2-ny34u) — the DOM-navigation counterpart to the compiler's
  static `data-rf-view` / `data-rf2-source-coord` host-root annotation."
  [^ViewCell cell]
  (:render-key (commit-record cell)))

(defn cell-view-id
  "The view id `cell` was minted for (tool/test read) — the stable authoring
  identity axis the `re-frame.ui.tool.evidence` projection keys a
  developer-facing row on, alongside the owning root (rf2-vxgfnd.75)."
  [^ViewCell cell]
  (:view-id @(state cell)))

(defn current-live-cells
  "The set of currently-CONNECTED ViewCells (tool/test read) — the live-cell
  registry a frame-destroy sweep consults, and the seam a DOM lifecycle fixture
  grabs a mounted cell through to observe its post-unmount lifecycle."
  []
  @live-cells)
