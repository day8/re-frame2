(ns re-frame.freehand.cell
  "The ATOMIC SHELL — the law that makes speculative rendering safe
  (EP-0036 governing law 3; ruled by D019 as far as \"a failed candidate
  publishes nothing\").

  A concurrent host separates RENDERING — which may run, restart, or be
  abandoned — from COMMITTING, which alone may own anything. Freehand
  takes that split literally:

    **A speculative render OWNS NOTHING. The SELECTED commit publishes
    its frame, its dependencies, its events and its evidence as ONE
    bundle. A failed or abandoned candidate publishes nothing at all.**

  ## Why this is a value, not a flag

  The unit of a render is a [[candidate]] — a plain value the caller
  HOLDS. It is not an ambient module slot, not a thread-local \"current
  render\", and not a flag some later phase consults. A render that is
  abandoned is abandoned by DROPPING its candidate, so it is structurally
  unable to publish: there is nothing for it to publish through. That is
  the same shape `re-frame.freehand.events` already uses for its site
  table, and the two compose — the cell's commit is what injects the
  frame-bound dispatcher into the event candidate, so a view's
  subscriptions and its event bodies can never come from two different
  renders.

  One thing genuinely cannot be threaded: a reactive read is written by
  the application, deep inside unrestricted Clojure, and has no candidate
  argument to receive. [[observe!]] therefore reaches the candidate
  through a DYNAMIC var — thread-local on the JVM, save/restore on
  ClojureScript. The JVM CONVEYS dynamic bindings into `future` / `pmap`
  / `bound-fn`, which would hand child threads of one render the same
  non-thread-safe capture, so the candidate records the thread that
  opened it and a conveyed read is refused BEFORE it probes (the capture
  law's same-render-thread rule). The owner rides INSIDE the conveyed
  value precisely because conveyance is the mechanism at fault.

  ## The commit, step by step

  1. **Currency.** The candidate is compared against the cell's body
     revision and against the exact frame incarnation it rendered
     against. Anything stale publishes nothing.
  2. **Stage.** Every newly-observed or retargeted target is acquired
     BEFORE anything is released, so a node shared by the old and new
     dependency sets never falls through its zero-owner disposal edge.
     An acquisition failure releases the staged handles in reverse order
     and leaves the PRIOR committed set installed.
  3. **Read and re-check.** Every staged handle's commit-time evidence is
     read — a fail-loud, callback-capable operation that runs INSIDE the
     pre-publication transaction, so a read that throws rolls the fresh
     staging back exactly as an acquisition failure does. Currency is then
     re-read at the narrowest boundary — after ALL callback-capable work,
     the reads included, with nothing callback-capable between it and the
     publish — so a hot reload (or a read that re-entered and advanced
     authority) landing mid-commit cannot publish a stale body.
  4. **Publish.** Frame, dependencies, evidence and the event site table
     become live with no host yield between them. Nothing can observe a
     partial bundle.
  5. **Release.** Only now are the superseded handles released.
  6. **Correct.** Every acquired handle — retained as well as staged —
     has its commit-time reading compared against the render's probe
     evidence; movement in the render→commit gap advances the cell's
     revision so the host corrects before paint.

  Everything here is **common** — the same value shapes, the same laws
  and the same diagnostics on the JVM and in ClojureScript. The React
  glue that drives it is [[re-frame.freehand.shell]]; on the JVM the
  structural host drives it directly.

  INTERNAL. Nothing in this namespace is application API: `v/sub` is the
  authoring surface over [[observe!]], and mounting is what drives the
  rest.

  Normative owner:
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §The Freehand atomic shell."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.freehand.eq :as eq]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.evidence :as evidence]
            [re-frame.interop :as interop]
            [re-frame.router :as router]
            [re-frame.substrate.observation :as obs]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Observation-port ABI lockstep
;; ---------------------------------------------------------------------------
;;
;; Spec 006 §The internal observation port §Scope. The shell is the port's
;; consumer, and from ABI v2 onward it RELIES on the `:node-key` axis of
;; `read` — the reincarnation identity `moved?` below compares. So it pins
;; the ABI it was written against and asserts it AT LOAD: a core that
;; predates that axis is a boot error, never a silently-missed correction.

(def ^:const expected-observation-port-abi
  "The observation-port ABI version this shell is written against — v2,
  in which `read` on a node handle carries `:node-key`, the
  process-unique node identity [[moved?]] compares so a same-id frame
  reincarnation across the render→commit gap reads as MOVEMENT even when
  version and epochs coincide. Asserted against the live port at load."
  2)

(obs/assert-port-abi-version! expected-observation-port-abi)

;; ---------------------------------------------------------------------------
;; Diagnostics
;; ---------------------------------------------------------------------------

(defn- read-outside-render!
  [query]
  (error/throw-error!
    :rf.error/view-read-outside-render
    'v/sub
    (str "A reactive read happened outside an active declared render. A Freehand view "
         "records the reads its OWN render makes, so the selected commit can own exactly "
         "them; a read with no render to belong to has no owner and would leak. Read state "
         "inside a v/defview body. In a STRUCTURAL TEST, render inside the bracket "
         "(t/with-render (t/render form)) — it opens a discardable render and publishes "
         "nothing, so the view under test runs AS WRITTEN rather than being rewritten to "
         "suit the test. In a REPL, a timer, a v/event or v/handler callback or a foreign "
         "listener, use the frame-explicit one-shot read, which resolves, probes, returns "
         "and releases without installing a view dependency.")
    {:recovery :read-inside-a-declared-render-or-use-a-one-shot-read
     :extra    {:query (error/diag-value-summary query)}}))

#?(:clj
   (defn- forked-capture!
     [^Thread owner query]
     (error/throw-error!
       :rf.error/view-forked-capture
       'v/sub
       (str "A reactive read reached a CHILD thread of the render that opened it. A render "
            "capture is single-threaded: future/pmap/bound-fn convey the ambient capture, so "
            "the forked reads would race one non-thread-safe capture and silently lose sites "
            "— and a capture with sites missing reaches commit as missing OWNERSHIP. Read the "
            "sites on the render thread, and fork only work that performs no reads; a genuinely "
            "parallel branch belongs behind its own keyed child view.")
       {:recovery :read-the-sites-on-the-render-thread
        :extra    {:owner-thread   (.getName owner)
                   :current-thread (.getName (Thread/currentThread))
                   :query          (error/diag-value-summary query)}})))

;; ---------------------------------------------------------------------------
;; The cell
;; ---------------------------------------------------------------------------
;;
;; One cell per mounted boundary OCCURRENCE. `deftype` over one `atom`,
;; deliberately: the port's `on-change` fan-out can reach a cell from
;; outside the render thread, so the committed state is held in a
;; compare-and-set container rather than the `volatile!` a strictly
;; render-thread structure (an event site table) can use.

(deftype ViewCell [state])

(defn- st [^ViewCell cell] (.-state cell))

(defn cell
  "Mint the commit-owned state for ONE mounted boundary occurrence.
  `view-id` names the view for diagnostics and evidence.

  A fresh cell owns nothing: no dependencies, no committed frame, and an
  event owner that has never been committed. It becomes `:connected`
  only when a render is SELECTED."
  [view-id]
  (->ViewCell
    (atom {:view-id    view-id
           :generation 0
           :lifecycle  :new
           :frame      nil
           :deps       {}
           :dep-order  []
           :events     (events/owner view-id)
           :revision   0
           :listeners  {}
           :dirty      nil
           :evidence   nil})))

(defn cell?
  "True when `x` is a [[cell]]."
  [x]
  (instance? ViewCell x))

(defn view-id     "The cell's authoring identity."             [^ViewCell c] (:view-id @(st c)))
(defn lifecycle   "`:new`, `:connected`, or `:disconnected`."   [^ViewCell c] (:lifecycle @(st c)))
(defn committed-frame "The frame the SELECTED commit bound to." [^ViewCell c] (:frame @(st c)))
(defn generation  "The body revision the cell is current at."   [^ViewCell c] (:generation @(st c)))
(defn revision    "The repaint counter the host snapshots."     [^ViewCell c] (:revision @(st c)))
(defn events-owner "The cell's committed event owner."          [^ViewCell c] (:events @(st c)))
(defn evidence    "The SELECTED commit's published evidence."   [^ViewCell c] (:evidence @(st c)))

(defn dependencies
  "The committed dependency map — `{site-key {:query :target :handle
  :value :evidence}}`. Empty for a cell that has never had a render
  selected, and empty again after [[disconnect!]]."
  [^ViewCell cell]
  (:deps @(st cell)))

(defn dependency-queries
  "The queries the SELECTED commit owns, in RENDER order.

  The order is the one the commit published, not a sort of the site keys.
  A site key is whatever the tier that recorded it uses — the
  interpreted walk's document ordinal, the compiled tier's proven lexical
  site id — and only an ordinal happens to sort into render order. Reading
  the published order keeps this projection true for both."
  [^ViewCell cell]
  (let [{:keys [deps dep-order]} @(st cell)]
    (mapv #(:query (get deps %)) dep-order)))

;; ---------------------------------------------------------------------------
;; The render candidate — a value the caller holds
;; ---------------------------------------------------------------------------

(deftype RenderCandidate [cell generation frame incarnation reads events sites thread])

(defn candidate?
  "True when `x` is a [[candidate]]."
  [x]
  (instance? RenderCandidate x))

(defn- render-thread
  "The thread that opened a capture — `nil` on ClojureScript, where a
  single-threaded host has no question to ask and the whole guard
  compiles away."
  []
  #?(:clj (Thread/currentThread) :cljs nil))

(defn candidate
  "Open a fresh render candidate for `cell`, bound to `frame`.

  It publishes NOTHING until [[commit!]], and dropping it is how an
  abandoned render publishes nothing. It records, at the instant the
  render begins, exactly what commit will check for currency: the cell's
  body revision and the frame's live incarnation."
  [^ViewCell cell frame]
  (let [s @(st cell)]
    (->RenderCandidate cell
                       (:generation s)
                       frame
                       (when (some? frame) (frame/frame-incarnation-token frame))
                       (volatile! {:order [] :by-site {}})
                       (events/candidate (:events s))
                       (volatile! 0)
                       (render-thread))))

(defn next-site-key!
  "Allocate this candidate's next event-site key.

  Identity is DOCUMENT ORDER within the boundary — the nth event site the
  walk reaches. That is what an unrestricted interpreted body can
  honestly offer, and it is what keeps a site's committed proxy stable
  across re-renders of an unchanged tree; the compiled tier replaces it
  with a finite lexical site id its analyzer can prove."
  [^RenderCandidate cand]
  (let [sites (.-sites cand)
        n     @sites]
    (vswap! sites inc)
    n))

(defn candidate-events
  "The candidate's event site table — what an emitter threads into
  `re-frame.freehand.events/site`. Ownership-free, exactly like the
  reads: [[commit!]] is the one place either becomes live."
  [^RenderCandidate cand]
  (.-events cand))

(defn candidate-frame
  "The frame this candidate rendered against."
  [^RenderCandidate cand]
  (.-frame cand))

(defn candidate-reads
  "The immutable `{site-key render-observation}` this candidate
  recorded — an inspection seam for tools and tests, never a
  publication."
  [^RenderCandidate cand]
  (:by-site @(.-reads cand)))

;; ---------------------------------------------------------------------------
;; Ambient capture — the one thing that cannot be threaded
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *render*
  "The active render candidate, or nil outside a render.

  A dynamic var, not a module slot: `binding` gives save/restore on
  single-threaded ClojureScript and THREAD-LOCAL isolation on the JVM, so
  two concurrent renders own disjoint captures. The JVM also conveys the
  binding into `future` / `pmap` workers, which is exactly why the
  candidate carries its owning thread — see [[observe!]]."
  nil)

(defn observing?
  "True inside an active declared render."
  []
  (some? *render*))

(defn current-candidate
  "The candidate of the render currently in progress, or `nil` outside a
  declared render.

  The ONE thing a compiled body cannot be handed by threading. An
  interpreted walk holds the candidate as it descends and passes it to
  every site it reaches; a compiled body is a straight-line function the
  compiler emitted, and there is no walk between the shell and the site
  to carry it. So the shell's own capture — already established for
  reactive reads, and already scoped to exactly the synchronous body
  evaluation — is what an emitted event site reads its candidate from.

  This is deliberately NOT a second ambient slot. It is [[observe!]]'s,
  read through a different door: the same `binding`, established by the
  same [[with-capture]], torn down at the same instant. A compiled site
  and an interpreted read in one render therefore cannot see two
  different candidates, which is the property that keeps one commit
  atomic.

  Reading it OUTSIDE a render answers `nil` rather than throwing,
  because the answer is informative: a compiled view whose ViewCell was
  elided by proof has no candidate, and a site under it has nothing to
  belong to — the same sentence the interpreted walk already writes for
  markup with no boundary above it."
  []
  *render*)

(defn with-capture
  "Run `thunk` — a view body — under `candidate`, with the candidate's
  frame established as the ambient frame for the duration.

  Ownership-free: the body resolves and probes, and acquires nothing, so
  a thunk whose result the host discards leaves no ref-count, no watch
  and no cache node behind. The caller keeps the candidate; nothing here
  publishes it.

  The frame binding is scoped to the SYNCHRONOUS body evaluation only. A
  view's children are elements the host renders LATER, outside this
  extent, each re-establishing its own frame — so no ambient frame leaks
  across lazy child rendering."
  [^RenderCandidate cand thunk]
  (binding [*render*              cand
            frame/*current-frame* (or (.-frame cand) frame/*current-frame*)]
    (thunk)))

(defn- ensure-render-thread!
  [^RenderCandidate cand query]
  #?(:clj (let [owner (.-thread cand)]
            (when-not (identical? owner (Thread/currentThread))
              (forked-capture! owner query)))
     :cljs nil))

(defn- record-read!
  "The ONE reactive read, under the site key its tier names.

  Resolve, probe, stabilize against the same site's prior committed
  observation, record on the candidate in render order, and answer the
  value. Ownership-free: [[obs/probe]] creates no cache entry, no watch
  and no disposal obligation."
  [^RenderCandidate cand site-key query]
  (let [^ViewCell owner-cell (.-cell cand)
        reads     (.-reads cand)
        prior     (get (:deps @(st owner-cell)) site-key)
        query*    (if (and prior (eq/rf= query (:query prior))) (:query prior) query)
        target    (obs/resolve-target {:query-v query*})
        ev        (obs/probe target)
        v         (:value ev)
        v*        (if (and prior (eq/rf= v (:value prior))) (:value prior) v)]
    (vswap! reads
            (fn [r]
              (-> r
                  (update :order conj site-key)
                  (assoc-in [:by-site site-key]
                            {:query query* :target target :evidence ev :value v*}))))
    v*))

(defn observe!
  "RENDER time: resolve, probe and RECORD one reactive read on the active
  candidate, and return the observed value.

  Ownership-free by construction — [[obs/probe]] creates no cache entry,
  no watch and no disposal obligation, so this is safe in a render the
  host may abandon. The read is recorded against the candidate's own site
  ordinal, in render order; the SELECTED commit is what turns those
  records into ownership.

  Two reads fail rather than guess:

  - **no active render.** A read with no candidate to belong to has no
    owner. `:rf.error/view-read-outside-render`.
  - **a conveyed child thread.** Capture is same-render-thread only.
    `:rf.error/view-forked-capture`, raised BEFORE the probe, so a
    forked read performs no observation work whose ordering it could not
    honour.

  Value and query are STABILIZED against the same site's prior committed
  observation, so an equal-but-fresh query keeps the exact prior query
  object (adapters may key caches by query identity) and an `rf=`-equal
  value is returned as the exact prior value (an equal value is not
  movement)."
  [query]
  (let [^RenderCandidate cand *render*]
    (when (nil? cand)
      (read-outside-render! query))
    (ensure-render-thread! cand query)
    (record-read! cand (count (:order @(.-reads cand))) query)))

(defn observe-site!
  "The SAME render-time read as [[observe!]], recorded under the site key
  `site-key` the caller names rather than the document-order ordinal.

  This is the COMPILED tier's door. Its analyzer has already proven a
  FINITE set of read sites and given each one a stable lexical id, so the
  compiled lowering can name the site it is reading at instead of counting
  its way to it — which is what makes a read's identity survive a body
  edit that moves it, and what lets the manifest's subscription roster and
  the cell's committed dependency set be indexed by the same key.

  Everything else is the one shell: the same capture, the same
  same-thread rule, the same outside-render diagnostic, the same
  stabilization, and the same commit. There is no second reactive path —
  the compiled tier differs in WHEN the site was identified, never in what
  a read means."
  [site-key query]
  (let [^RenderCandidate cand *render*]
    (when (nil? cand)
      (read-outside-render! query))
    (ensure-render-thread! cand query)
    (record-read! cand site-key query)))

;; ---------------------------------------------------------------------------
;; The repaint channel
;; ---------------------------------------------------------------------------
;;
;; Spec 006 §The six frozen invariants, invariant 6. Source-side
;; notification is CONSTANT WORK — mark the cell stale and return. The
;; pending window closes at the host checkpoint (a real microtask in
;; ClojureScript, which runs before the next paint) or at an explicit
;; flush; a cell marked any number of times inside one window is flushed
;; exactly ONCE. The headless JVM host has no paint and therefore no
;; checkpoint, so there the window is closed explicitly — by the caller
;; that wants the correction, which is the only honest boundary a host
;; with no scheduler has.

(defonce ^:private pending-cells (atom #{}))
(defonce ^:private flush-scheduled? (atom false))

(declare flush!)

(defn- schedule-flush!
  []
  #?(:cljs (when (compare-and-set! flush-scheduled? false true)
             (js/queueMicrotask (fn [] (flush!))))
     :clj nil))

(defn- notify-listeners!
  [^ViewCell cell]
  (doseq [f (vals (:listeners @(st cell)))]
    (f))
  nil)

(defn- advance-revision!
  [^ViewCell cell]
  (swap! (st cell) (fn [s] (-> s (update :revision inc) (assoc :dirty nil))))
  (notify-listeners! cell)
  nil)

(defn mark-dirty!
  "The port's `on-change` sink — CONSTANT WORK, never a computation. It
  records the cause, enrols the cell in the open pending window, and
  returns; the revision advance and the host notification happen once
  when that window closes."
  [^ViewCell cell cause]
  (swap! (st cell) (fn [s] (update s :dirty (fn [d] {:cause cause :count (inc (:count d 0))}))))
  (swap! pending-cells conj cell)
  (schedule-flush!)
  nil)

(defn dirty?
  "True when `cell` has been marked since the last window closed."
  [^ViewCell cell]
  (some? (:dirty @(st cell))))

(defn- flush-scope!
  "The scoped-flush PRIMITIVE: close the pending window for exactly the
  cells `in-scope?` accepts, advancing each ONCE and notifying its host
  listeners. Cells outside the scope STAY pending, so no scope can force
  another scope's work and none is lost.

  Reentrancy-safe by construction: the matching cells leave the pending
  set ATOMICALLY, before any listener runs, so a notification that
  re-enters finds them already drained and cannot advance one twice.
  Returns the number flushed."
  [in-scope?]
  (let [[before _] (swap-vals! pending-cells #(into #{} (remove in-scope?) %))
        drained    (filterv in-scope? before)]
    (doseq [c drained]
      (when-not (= :disconnected (lifecycle c))
        (advance-revision! c)))
    (count drained)))

(defn flush!
  "Close the pending window: advance each marked cell's revision ONCE and
  notify its host listeners. Idempotent when nothing is pending."
  []
  #?(:cljs (reset! flush-scheduled? false))
  (flush-scope! (constantly true))
  nil)

(defn- observed-frames
  "The frames `cell`'s COMMITTED dependency set actually observes.

  A committed record carries the target its site resolved to, and a
  `:subscription` target names the frame it resolved in. A cell that owns
  no dependency observes no frame — which is exactly right: such a cell
  is never marked, so there is nothing for a frame scope to reach."
  [^ViewCell cell]
  (into #{}
        (keep (fn [{:keys [target handle]}]
                (when (and (some? handle) (= :subscription (:kind target)))
                  (:frame-id target))))
        (vals (:deps @(st cell)))))

(defn observes-frame?
  "Does `cell`'s committed dependency set include a site resolved in frame
  `frame-id`? The frame-scope membership test — the thing that makes the
  synchronous flush FRAME-scoped rather than global."
  [^ViewCell cell frame-id]
  (contains? (observed-frames cell) frame-id))

(defn flush-frame!
  "The FRAME-SCOPED SYNCHRONOUS FLUSH — close the pending window NOW, for
  every marked cell observing frame `frame-id`, and for no other cell.
  Returns the number flushed.

  This is the second half of the controlled-input round trip (D009). A
  keystroke's event has already been dispatched and drained; the sources
  those cells observe have already moved and marked them. What is left is
  the host notification, and the ordinary window would deliver it at the
  microtask checkpoint — AFTER React has finished the discrete event and
  restored the controlled node from the props it last rendered, which is
  where a character is lost and a caret jumps. Advancing the revision
  HERE, still inside the native listener, puts the new state in front of
  React while the event is still being processed, so the same discrete
  batch renders it.

  The scope is the frame, and deliberately not the whole registry: a
  keystroke in one frame must not force another frame's pending work to
  settle inside a native listener it has nothing to do with. It is
  deliberately not narrower than the frame either — a single occurrence
  scope would let a sibling observing the same frame commit an older
  snapshot of state the flushed cell has already advanced past, and D009
  ruled that trade explicitly. Cells on the same frame that were dirty
  for unrelated reasons DO ride this flush; that coupling is honest,
  measurable, and reducible with ordinary boundary granularity.

  Safe on both hosts, and meaningful on both: the headless JVM has no
  paint and therefore no checkpoint, so an explicit scoped close is the
  only kind of close it has."
  [frame-id]
  (flush-scope! #(observes-frame? % frame-id)))

(defn snapshot
  "The scalar the host snapshots to decide whether to re-render — the
  cell's revision. One external store per cell, whatever its dependency
  count, so N moving targets settle in ONE host render pass."
  [^ViewCell cell]
  (:revision @(st cell)))

(defn subscribe
  "Register `listener` for revision advances and return the unsubscribe
  function — the host's external-store contract."
  [^ViewCell cell listener]
  (let [k #?(:clj (Object.) :cljs (js-obj))]
    (swap! (st cell) assoc-in [:listeners k] listener)
    (fn unsubscribe [] (swap! (st cell) update :listeners dissoc k) nil)))

(defn listener-count
  "How many host listeners the cell currently carries."
  [^ViewCell cell]
  (count (:listeners @(st cell))))

;; ---------------------------------------------------------------------------
;; Hot reload — the body-revision fence
;; ---------------------------------------------------------------------------

(defn advance-generation!
  "Bring `cell` up to `revision` — the BODY REVISION its view's emitter is
  currently publishing. The hot-reload seam: any candidate rendered
  against a lower revision is now stale and publishes nothing, at both
  commit checkpoints.

  The revision is passed in rather than incremented here because a
  compatible reload replaces the body ONCE for the view and every live
  occurrence of it must land on the same number. MONOTONE, so an
  occurrence whose cell was minted after the reload — starting at zero
  and catching up on its first render — converges with one that lived
  through it, and a stale call can never walk a cell backwards."
  [^ViewCell cell revision]
  (when (> revision (:generation @(st cell)))
    (swap! (st cell) assoc :generation revision))
  nil)

;; ---------------------------------------------------------------------------
;; The atomic commit
;; ---------------------------------------------------------------------------

(defn- current?
  "Is `cand` still the render this cell would publish? Two axes, and a
  candidate needs both:

    - the BODY revision it rendered against is still the cell's — a hot
      reload in the render→commit gap makes the render stale; and
    - the FRAME INCARNATION it resolved against is still live — a
      same-id destroy and recreate is a different incarnation, not the
      same frame under a reused name.

  Connection state is deliberately NOT an axis. A host may tear an
  effect down and run it again for the SAME committed render — React's
  StrictMode does exactly that, and a hidden-then-revealed subtree is
  the same shape — and that replay must RECONNECT, re-acquiring from a
  clean slate rather than being refused. What stops a genuinely dead
  boundary from re-acquiring is that the host runs no effect for a
  Fiber it has unmounted, which is a structural fact, not a flag this
  predicate could improve on."
  [^RenderCandidate cand s]
  (and (= (.-generation cand) (:generation s))
       (let [f (.-frame cand)]
         (or (nil? f)
             (frame/frame-incarnation-live? f (.-incarnation cand))))))

(defn- moved?
  "Did a target move in the render→commit gap? Compares the commit-time
  `read` against the render's probe evidence. A cold probe (no node
  version) falls back to `rf=` on the value; a live probe compares the
  node IDENTITY, the node version, and the frame and registry epochs —
  the identity axis being what tells a same-id frame REINCARNATION apart
  from an unmoved node when version and epochs coincide."
  [now probed]
  (if (nil? (:node-version probed))
    (not (eq/rf= (:value now) (:value probed)))
    (or (not= (:version now)        (:node-version probed))
        (not= (:node-key now)       (:node-key probed))
        (not= (:frame-epoch now)    (:frame-epoch probed))
        (not= (:registry-epoch now) (:registry-epoch probed)))))

(defn- frame-dispatcher
  "The frame-bound dispatch target committed into the event site table.
  Every site the SELECTED render published fires into exactly this
  frame, and a retarget is exactly a re-commit with a different one.

  A boundary under NO frame in scope commits a dispatcher that carries no
  frame pin, so a firing site raises re-frame's ordinary
  `:rf.error/no-frame-context` — the same diagnostic any other ambient
  dispatch raises there, rather than a substrate-private variant."
  [frame-id]
  (if (some? frame-id)
    (fn dispatch-into-frame [event] (router/dispatch! event {:frame frame-id :source :ui}))
    (fn dispatch-ambient [event] (router/dispatch! event {:source :ui}))))

(defn- frame-door-dispatcher
  "The SYNCHRONOUS dispatcher a controlled-input site fires through,
  bound to the exact frame this commit publishes (D009).

  One sequence, and it completes before the native listener returns:

      dispatch against the exact committed frame
        → drain re-frame to quiescence
        → flush the cells observing that frame
        → return to the host's event processing

  `dispatch-sync!` is what makes the first two steps one step: it
  processes the event end to end and then drains whatever that handler
  queued, so by the time it returns the frame has settled and every cell
  reading the moved state has been marked. [[flush-frame!]] then closes
  the pending window for exactly those cells — still inside the listener,
  which is the whole point.

  A boundary under NO frame in scope gets no door dispatcher at all
  rather than a nameless synchronous one: with no frame to scope it, a
  flush could only be global, and a keystroke that force-settles every
  root in the process is not a narrower promise than batching — it is a
  wider one. Such a site takes the ordinary lane, where a firing site
  raises re-frame's own `:rf.error/no-frame-context`, exactly as the
  batched dispatcher does."
  [frame-id]
  (when (some? frame-id)
    (fn dispatch-through-door [event]
      (router/dispatch-sync! event {:frame frame-id :source :ui})
      (flush-frame! frame-id)
      nil)))

(defn- release-all!
  [handles]
  (doseq [h handles] (obs/release! h))
  nil)

(defn- stage!
  "Acquire every newly-observed or retargeted target, in render order,
  BEFORE anything is released. Returns `{site-key record}` with each
  record's `:handle` either RETAINED from the prior committed set (the
  kept-check held, and the handle is not touched) or freshly acquired.

  Transactional: an acquisition failure releases the handles this staging
  acquired — in reverse acquisition order, so layered acquisitions unwind
  symmetrically — and rethrows, leaving the prior committed set
  installed."
  [^ViewCell cell prior order reads]
  (let [acquired (volatile! [])]
    (try
      (reduce
        (fn [staged site-key]
          (let [{:keys [target] :as record} (get reads site-key)
                kept                        (get prior site-key)]
            (if (and (some? kept) (obs/current? (:handle kept) target))
              (assoc staged site-key (assoc record :handle (:handle kept) :retained? true))
              (let [h (obs/acquire! target (fn on-change [cause] (mark-dirty! cell cause)))]
                (vswap! acquired conj h)
                (assoc staged site-key (assoc record :handle h :retained? false))))))
        {} order)
      (catch #?(:clj Throwable :cljs :default) e
        (release-all! (rseq @acquired))
        (throw e)))))

(defn- fresh-handles
  "The handles THIS candidate freshly acquired — the render `order`
  restricted to the sites [[stage!]] acquired rather than RETAINED from
  the prior committed set — in ACQUISITION order.

  Its reverse is the release order for any pre-publication rollback (a
  read failure or a lost currency re-check): layered acquisitions unwind
  symmetrically, and a retained prior handle — untouched by this
  candidate and still owned by the installed set — is never in it, so a
  rollback releases exactly what this candidate acquired and nothing the
  prior bundle still needs."
  [order staged]
  (into []
        (keep (fn [site-key]
                (let [record (get staged site-key)]
                  (when (and record (not (:retained? record))) (:handle record)))))
        order))

(defn- superseded-handles
  "The handles the PRIOR committed set owned that the new one does not —
  a dropped site's handle, and a retargeted site's OLD handle. Compared
  by identity, because a handle IS its owner token."
  [prior staged]
  (into []
        (keep (fn [[site-key {:keys [handle]}]]
                (let [now (get-in staged [site-key :handle])]
                  (when-not (identical? now handle) handle))))
        prior))

;; ---------------------------------------------------------------------------
;; The dev-gated occurrence-record emission seam (rf2-3naow, F4g)
;; ---------------------------------------------------------------------------
;;
;; A render on the Freehand path publishes ONE evidence record per SELECTED
;; commit — the occurrence, its lowering, its generation and the commit
;; projection, validated through `re-frame.freehand.evidence/record`. The
;; whole seam sits behind `interop/debug-enabled?`, the SAME gate the rest of
;; the tooling reads (`goog.DEBUG` on ClojureScript, the JVM debug flag on the
;; JVM), so under `:advanced` with `goog.DEBUG=false` Closure folds the gate to
;; `false`, the branch DCEs, and neither the record construction nor the
;; evidence schema it reaches survives into a production bundle.
;;
;; This is a SEAM, not an evidence framework: it validates a record and hands
;; it to whatever sink is installed. The default sink is nil — nothing is
;; retained here, exactly as `re-frame.freehand.evidence` retains nothing (its
;; `retention` names Spec 009's per-frame ring and no second store). The
;; accumulator that folds these records is downstream (rf2-drpa3.167).
;;
;; TWO PLANES, split along the publication line. The installed sink is an
;; observability CONSUMER of the commit, never an authority over it, so its
;; behaviour must not decide whether the commit stands:
;;
;;   - The FRAMEWORK's record is built and VALIDATED through `evidence/record`
;;     BEFORE the bundle is published. A malformed record — a bad occurrence, a
;;     projection that claims more than it knows — fails loud from the evidence
;;     door before anything is live, so the documented all-or-nothing boundary
;;     holds: a commit the schema would refuse publishes NOTHING.
;;   - The CONSUMER's sink is called only AFTER the publish, inside a guard. A
;;     tool callback that throws is contained and reported — it can never turn a
;;     live, connected, published commit into an exception at the caller, and a
;;     normal sink still receives the pre-built record exactly once per commit.
;;
;; The report never routes back through the sink (no recursion): a contained
;; throw is surfaced on the host console and in the single bounded
;; `last-sink-escape` slot, both dev-gated so the whole plane DCEs in
;; production alongside the record construction it guards.

(defonce ^:private evidence-sink
  ;; The installed occurrence-record sink, or nil for the no-op default.
  ;; `defonce` so reloading this namespace does not drop a sink a consumer
  ;; installed; an atom because installation is a RUNTIME act (a tool
  ;; attaching), never a compile-time one.
  (atom nil))

(defn set-evidence-sink!
  "Install `f` — a 1-arg fn taking a validated occurrence record — as the
  render path's evidence sink, and answer the PRIOR sink. `nil` restores
  the no-op default.

  The seam builds a record only when a sink is installed AND
  `interop/debug-enabled?` is true, so an application that installs no sink
  pays nothing beyond the folded-away gate. Downstream tooling
  (rf2-drpa3.167) installs the accumulator here."
  [f]
  (let [prev @evidence-sink]
    (reset! evidence-sink f)
    prev))

(defonce ^:private last-sink-escape
  ;; The single bounded diagnostic slot for a CONTAINED evidence-sink throw
  ;; (`{:view-id … :error e}` | nil), newest escape wins. The evidence sink is a
  ;; DEBUG-only tool CONSUMER of the commit, never an authority over it: a
  ;; throwing sink must not turn a published, connected commit into an exception
  ;; at the caller. So `commit!` validates the record BEFORE it publishes, and
  ;; DELIVERS it to the sink only AFTER, inside a guard that records any consumer
  ;; throw here. Observable via [[last-evidence-sink-escape]] so a contained
  ;; throw is never silent. An atom because it is written at RUNTIME (a tool
  ;; callback throwing), never compile time — the same shape as `evidence-sink`,
  ;; and like it a `defonce` so reloading this namespace keeps a recorded escape.
  (atom nil))

(defn last-evidence-sink-escape
  "The most recent CONTAINED evidence-sink throw as `{:view-id … :error e}`, or
  nil when no installed sink has thrown since load (and always nil in a
  production build, where the whole dev evidence plane is elided).

  A throwing DEBUG sink can never turn a published commit into an exception at
  the caller — it is contained at the seam and surfaced HERE, plus one host
  console report, so the escape is never silent. A tool/test read."
  []
  @last-sink-escape)

(defn- occurrence-of
  "The runtime OCCURRENCE key for `cell` — the mounted boundary's own
  identity, never a query key or a lexical site id (D020). A root
  occurrence has no parent, and parent-occurrence threading is a later
  slice, so `:parent` is nil here and `:key` is the cell's stable object
  identity."
  [^ViewCell cell]
  {:parent nil
   :key    #?(:clj  (System/identityHashCode cell)
              :cljs (goog/getUid cell))})

(defn- commit-evidence-record
  "DEV-GATED, BEFORE publication: when a sink is installed, build the commit
  occurrence-record for `cell` at `lowering` / `generation` and VALIDATE it
  through `re-frame.freehand.evidence/record`. Answers `[sink record]` — the
  captured sink and its validated record — for delivery once the bundle is
  live, or nil when the seam is inert (no sink, or debug disabled).

  FAIL-LOUD on a malformed FRAMEWORK record: `evidence/commit-record` throws
  HERE, before `commit!` publishes anything, so an emission the schema refuses
  aborts the whole commit rather than reaching a reader or leaving a
  half-published bundle. That is the framework plane and it stays loud — only
  the CONSUMER's own failure (the sink throwing) is contained, downstream and
  only after a selected publication.

  The sink is captured together with its record so the tool that was installed
  when the commit began is the one delivered to, even if a listener the publish
  fires swaps the sink mid-flush.

  The `interop/debug-enabled?` gate stands alone as the body's outermost form,
  so under `:advanced` with `goog.DEBUG=false` Closure folds it to `false`, the
  branch DCEs, and neither the record construction nor the evidence schema it
  reaches survives into a production bundle."
  [^ViewCell cell lowering generation]
  (when interop/debug-enabled?
    (when-let [sink @evidence-sink]
      [sink (evidence/commit-record (view-id cell) lowering
                                    (occurrence-of cell) generation)])))

(defn- report-sink-escape!
  "Contain a THROWING evidence sink: record the escape in the single bounded
  [[last-sink-escape]] slot (newest wins) and, on CLJS, emit ONE host
  `console.error` naming the offending view. The bundle is already published
  and live by the time this runs, so the report is a pure after-the-fact
  account that can neither un-publish the commit nor reach the caller.

  Reporting NEVER routes back through `evidence-sink` — it is the console and
  escape-slot path, not another sink call — so a throwing sink cannot recurse
  through the report. Called only from the debug-gated deliver branch, so the
  whole helper DCEs under `goog.DEBUG=false`."
  [view-id e]
  (reset! last-sink-escape {:view-id view-id :error e})
  #?(:cljs (when (exists? js/console)
             (.error js/console
                     (str "re-frame.freehand: the evidence sink (a DEBUG tool "
                          "consumer — e.g. Xray) threw while consuming the "
                          (pr-str view-id) " commit record. The throw was CONTAINED, "
                          "so the commit is published and live and the caller was not "
                          "affected — a debug observer cannot corrupt a commit. Fix the "
                          "sink installed with (set-evidence-sink! …). Cause: "
                          (error/ex-message-safe e))))
     :clj nil)
  nil)

(defn emit-commit-evidence!
  "DEV-GATED, AFTER publication: deliver the pre-built, pre-validated `captured`
  record to the installed sink, CONTAINING any failure the sink CONSUMER
  raises. `captured` is the `[sink record]` [[commit-evidence-record]] built
  and validated BEFORE the publish, or nil when the seam is inert.

  The sink is an observability consumer of the commit, not an authority over
  it: a tool callback that throws must not turn a live, published, connected
  commit into an exception at the caller. So a throw is caught and reported
  through [[report-sink-escape!]] (the bounded escape slot and one host console
  line), never rethrown and never routed back through the sink.

  A normal (non-throwing) sink receives the captured record EXACTLY ONCE per
  selected commit — the one `sink` call below. The `interop/debug-enabled?`
  gate stands alone so the whole delivery-and-containment machinery DCEs under
  `:advanced` with `goog.DEBUG=false`, alongside the record it consumes."
  [^ViewCell cell captured]
  (when interop/debug-enabled?
    (when captured
      (let [[sink record] captured]
        (try
          (sink record)
          (catch #?(:clj Throwable :cljs :default) e
            (report-sink-escape! (view-id cell) e))))))
  nil)

(defn commit!
  "COMMIT time: publish `candidate`'s ENTIRE bundle — its frame, its
  dependencies, its event site table and its evidence — or publish
  NOTHING.

  Returns `:published` when the render was selected and current, or
  `:abandoned` when it was not. An abandoned candidate leaves the cell
  exactly as it found it: the prior dependency set stays installed, the
  prior event bodies stay live, and no revision advances.

  Retargeting is exactly a re-commit with a different frame, so a frame
  change reaches every dependency AND every event site without changing
  one callback identity.

  `lowering` is the occurrence's execution mode — `:interpreted` or
  `:compiled` — carried onto the dev-gated evidence record a selected
  commit emits (see [[emit-commit-evidence!]]). The one-arg arity defaults
  to `:interpreted`, the unrestricted general case a bare probe or a
  headless commit runs under."
  ([cand] (commit! cand :interpreted))
  ([^RenderCandidate cand lowering]
  (let [^ViewCell owner-cell (.-cell cand)
        s0                   @(st owner-cell)]
    (if-not (current? cand s0)
      :abandoned
      (let [{:keys [order by-site]} @(.-reads cand)
            prior  (:deps s0)
            fid    (.-frame cand)
            staged (stage! owner-cell prior order by-site)
            ;; The commit-side reads are part of the pre-publication
            ;; TRANSACTION, not a step past it. `obs/read` is a fail-loud
            ;; port op that may deref application subscription code — it can
            ;; throw, and it can synchronously advance the cell's authority.
            ;; So it runs inside the rollback guard: a read that throws
            ;; releases every handle THIS candidate freshly acquired, in
            ;; reverse acquisition order, leaves the prior committed set
            ;; installed, and rethrows the ORIGINAL failure untranslated.
            readings (try
                       (into {}
                             (map (fn [[k {:keys [handle]}]] [k (obs/read handle)]))
                             staged)
                       (catch #?(:clj Throwable :cljs :default) e
                         (release-all! (rseq (fresh-handles order staged)))
                         (throw e)))]
        ;; The narrowest publication boundary: acquisition AND the
        ;; commit-side reads ran cache installs, disposal hooks and
        ;; application recompute, any of which can synchronously advance the
        ;; cell's authority. Re-read it here — AFTER every callback-capable
        ;; or fail-loud pre-publication operation, with nothing
        ;; callback-capable between this check and the publish — so a read
        ;; that advanced body or frame authority rolls back its fresh
        ;; staging and publishes nothing rather than publishing stale
        ;; ownership.
        (if-not (current? cand @(st owner-cell))
          (do (release-all! (rseq (fresh-handles order staged)))
              :abandoned)
          (let [bundle   {:frame        fid
                          :generation   (.-generation cand)
                          :observations (mapv (fn [k]
                                                (let [r (get staged k)]
                                                  {:site-key k
                                                   :query    (:query r)
                                                   :frame-id (:frame-id (:target r))
                                                   :value    (:value (get readings k))
                                                   :owned?   (obs/owned? (:handle r))}))
                                              order)}
                ;; Build and VALIDATE the dev evidence record BEFORE the publish
                ;; below — the two-plane split: a malformed FRAMEWORK
                ;; record fails loud here, so a commit the schema would refuse
                ;; publishes nothing and the all-or-nothing boundary holds. The
                ;; CONSUMER sink is not called yet — only after the bundle is
                ;; live, where its failure is contained rather than fatal. Not
                ;; callback-capable (it derefs the sink slot and validates a
                ;; value — no app code), so it sits between the currency re-check
                ;; and the publish without reopening that window. Like the
                ;; acquisition and commit-read failures above, a throw here
                ;; releases this candidate's freshly acquired handles in reverse
                ;; order and RETHROWS — the fail-loud path leaks nothing, and the
                ;; catch never swallows a framework error.
                captured (try
                           (commit-evidence-record owner-cell lowering (.-generation cand))
                           (catch #?(:clj Throwable :cljs :default) e
                             (release-all! (rseq (fresh-handles order staged)))
                             (throw e)))]
            ;; ONE publication. Dependencies, frame and evidence become
            ;; live in a single write, and the event site table becomes
            ;; live against this exact frame with no host yield between
            ;; them — nothing can observe a partial bundle.
            (swap! (st owner-cell) assoc
                   :lifecycle :connected
                   :frame     fid
                   :deps      staged
                   :dep-order (vec order)
                   :evidence  bundle)
            (events/commit! (.-events cand)
                            (frame-dispatcher fid)
                            (frame-door-dispatcher fid))
            ;; Only now: the prior set's superseded handles. Acquire
            ;; before release is what keeps a shared node off its
            ;; zero-owner disposal edge mid-reconciliation.
            (release-all! (superseded-handles prior staged))
            ;; Invariant 5 — every acquired handle, retained as well as
            ;; staged, so a retained site on a host with no value watch
            ;; is still corrected.
            (when (some (fn [[k {:keys [evidence]}]] (moved? (get readings k) evidence)) staged)
              (advance-revision! owner-cell))
            ;; The dev-gated occurrence-record seam (rf2-3naow): a SELECTED
            ;; commit is the one place the whole bundle is live, so it is where
            ;; the record — built and validated above, before the publish — is
            ;; DELIVERED to the consumer sink. A throwing sink is contained (the
            ;; commit already stands), so delivery cannot turn `:published` into
            ;; an exception. Compiles out with the gate in production; an
            ;; abandoned candidate reaches neither branch.
            (emit-commit-evidence! owner-cell captured)
            :published)))))))

(defn disconnect!
  "Release everything this cell owns and retire every callback it
  published — React unmount, and any host teardown that means the same
  thing.

  Every handle the SELECTED commit acquired is released, so each node
  reaches its zero-owner disposal edge; every callback the cell published
  is retired, so a foreign listener still holding one finds it inert; and
  the cell leaves the open pending window, so no host listener is
  notified about a boundary that is gone.

  Deliberately NOT terminal, and deliberately not a poison pill for
  renders already in flight: a host that tears an effect down and runs it
  again — StrictMode's mount, cleanup, mount; a hidden subtree revealed —
  must RECONNECT. The next commit therefore reconnects the same cell from
  a clean slate, acquiring fresh handles rather than resurrecting the
  released ones."
  [^ViewCell cell]
  (let [s @(st cell)]
    (when-not (= :disconnected (:lifecycle s))
      (events/retire! (:events s))
      (swap! (st cell) assoc
             :lifecycle :disconnected
             :frame     nil
             :deps      {}
             :dep-order []
             :evidence  nil
             :dirty     nil)
      (swap! pending-cells disj cell)
      (release-all! (map :handle (vals (:deps s))))))
  nil)
