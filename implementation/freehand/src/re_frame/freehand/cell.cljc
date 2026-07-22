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
  3. **Re-check.** Currency is re-read at the narrowest boundary — after
     all callback-capable work, with nothing callback-capable between it
     and the publish — so a hot reload landing mid-commit cannot publish
     a stale body.
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
         "inside a v/defview body, or — in a REPL, a test, a timer or a foreign callback — "
         "use the frame-explicit one-shot read, which resolves, probes, returns and "
         "releases without installing a view dependency.")
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
  "The queries the SELECTED commit owns, in render order."
  [^ViewCell cell]
  (let [deps (:deps @(st cell))]
    (mapv #(:query (get deps %)) (sort (keys deps)))))

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
    (let [^ViewCell owner-cell (.-cell cand)
          reads     (.-reads cand)
          site-key  (count (:order @reads))
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
      v*)))

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

(defn flush!
  "Close the pending window: advance each marked cell's revision ONCE and
  notify its host listeners. Idempotent when nothing is pending."
  []
  #?(:cljs (reset! flush-scheduled? false))
  (let [[marked _] (reset-vals! pending-cells #{})]
    (doseq [c marked]
      (when-not (= :disconnected (lifecycle c))
        (advance-revision! c))))
  nil)

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
  "Record that `cell`'s view BODY was replaced — the hot-reload seam. Any
  candidate rendered against the previous revision is now stale and
  publishes nothing, at both commit checkpoints."
  [^ViewCell cell]
  (swap! (st cell) update :generation inc)
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
  one callback identity."
  [^RenderCandidate cand]
  (let [^ViewCell owner-cell (.-cell cand)
        s0                   @(st owner-cell)]
    (if-not (current? cand s0)
      :abandoned
      (let [{:keys [order by-site]} @(.-reads cand)
            prior  (:deps s0)
            fid    (.-frame cand)
            staged (stage! owner-cell prior order by-site)]
        ;; The narrowest publication boundary: acquisition ran cache
        ;; installs and disposal hooks, any of which can synchronously
        ;; advance the cell's authority. Re-read it here, with nothing
        ;; callback-capable between this check and the publish.
        (if-not (current? cand @(st owner-cell))
          (do (release-all! (into [] (comp (remove :retained?) (map :handle)) (vals staged)))
              :abandoned)
          (let [readings (into {}
                               (map (fn [[k {:keys [handle]}]] [k (obs/read handle)]))
                               staged)
                bundle   {:frame        fid
                          :generation   (.-generation cand)
                          :observations (mapv (fn [k]
                                                (let [r (get staged k)]
                                                  {:site-key k
                                                   :query    (:query r)
                                                   :frame-id (:frame-id (:target r))
                                                   :value    (:value (get readings k))
                                                   :owned?   (obs/owned? (:handle r))}))
                                              order)}]
            ;; ONE publication. Dependencies, frame and evidence become
            ;; live in a single write, and the event site table becomes
            ;; live against this exact frame with no host yield between
            ;; them — nothing can observe a partial bundle.
            (swap! (st owner-cell) assoc
                   :lifecycle :connected
                   :frame     fid
                   :deps      staged
                   :evidence  bundle)
            (events/commit! (.-events cand) (frame-dispatcher fid))
            ;; Only now: the prior set's superseded handles. Acquire
            ;; before release is what keeps a shared node off its
            ;; zero-owner disposal edge mid-reconciliation.
            (release-all! (superseded-handles prior staged))
            ;; Invariant 5 — every acquired handle, retained as well as
            ;; staged, so a retained site on a host with no value watch
            ;; is still corrected.
            (when (some (fn [[k {:keys [evidence]}]] (moved? (get readings k) evidence)) staged)
              (advance-revision! owner-cell))
            :published))))))

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
             :evidence  nil
             :dirty     nil)
      (swap! pending-cells disj cell)
      (release-all! (map :handle (vals (:deps s))))))
  nil)
