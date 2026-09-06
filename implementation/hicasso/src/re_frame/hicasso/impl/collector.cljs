(ns re-frame.hicasso.impl.collector
  "The collector: which boundaries a commit must re-run, how a body reaches
  subscription values, and the generation fence that keeps one render pass
  on one commit. React owns everything else about a boundary.

  Owns the render context (`rstate`, `scratch`), the cell table (`!cells`
  — one cell per unique `[frame-kw query-v]`, created and acquired only at
  commit, its reader list the key's reverse edge and reference count in
  one slot), the commit window (`with-commit`, `flush!`), the two read
  tiers (`sub`: warm is a deref, cold is `cold-read!`'s probe), the
  read-set entry cache (`!entries`, the cached `subscribe`/`getSnapshot`
  pair React sees), the two reapers, the shell and the two mint doors.
  Those form one dependency cycle — `flush!` reads `rstate`, `with-commit`
  is its window, `frame-dispatch` is `with-commit` over one captured
  incarnation, and `run-once` binds it for the body's extent — which is
  why they share a namespace; `impl.generation`, `impl.frames` and
  `impl.roots` each hold a one-directional edge out of it. What the
  runtime retains is counted from outside it, through the test kit's
  `re-frame.hicasso.test.runtime`.

  Design record: docs/design/hicasso/architecture.md, section The
  collector (the commit basis, the repairs, the entry cache, the reapers,
  the bracket and the alias), with the sub-read mechanism, HD-002 and
  HD-020 above it; the state machine and the edge-diff operation are
  discharged clause by clause in
  docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md §2; the
  cold read is priced in docs/design/hicasso/studio/the-cold-read-mount-term.md;
  every module-level owner here has its row on
  docs/design/hicasso/product/globals.md."
  (:require [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.error :as rf.hicasso.impl.error :refer [fail!]]
            [re-frame.hicasso.impl.frames :as rf.hicasso.impl.frames]
            [re-frame.hicasso.impl.generation :as rf.hicasso.impl.generation]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.performance :as rf.performance :include-macros true]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; The render context
;; ---------------------------------------------------------------------------
;;
;; Module-level rather than per-render objects, legal because boundary
;; bodies do not nest: a body returns hiccup, the codec turns a child
;; boundary into an element, and React runs the child's body after this
;; one has returned (docs/design/hicasso/product/globals.md).

(def ^js rstate
  "The render slots, one JS object for the whole runtime: the frame the
  running body resolved (nil outside a render, which is what makes
  `(sub …)` outside a boundary a loud error), the entry the last body
  resolved, the cold-probe box the running body's cold reads share
  (`cold-read!`; reset by `run-once`), the always-on body-run counter
  (`bodyRuns`, bumped by `run-once`) and, in a dev build, the
  `subscribe` the shell hands React for that entry (`render-body`).
  Public so the test kit's runtime door reads `bodyRuns` off it; every
  writer is in this file. Module-level because boundary bodies do not
  nest (docs/design/hicasso/product/globals.md)."
  #js {"frame" nil "entry" nil "probe" nil "bodyRuns" 0 "subscribe" nil})

(def ^:private ^js scratch
  "The one scratch buffer: one render's reads, in read order, reset by
  overwrite at the top of every body — `(set! (.-length scratch) 0)` is
  the whole of the reset. Exactly one, because a second would mean
  telling two render attempts apart, which is the ledger HD-002 forbids
  (docs/design/hicasso/product/globals.md)."
  #js [])

(defn rendering?
  "Is a boundary body running right now?"
  []
  (some? (.-frame rstate)))

;; ---------------------------------------------------------------------------
;; The ambient dispatch a body binds — `impl.frames` owns the memo row
;; ---------------------------------------------------------------------------

(declare with-commit)

(defn- mint-frame-dispatch
  "Mint the ambient dispatch closure for ONE frame incarnation, over the
  `capture-frame` bundle it was pinned with. Closes over `ops` and never
  over the frame keyword: a callback lowered under incarnation A calls
  A's own `:dispatch-sync`, so once A is destroyed core's `capture-frame`
  fence refuses it (`:rf.error/frame-destroyed`) instead of resolving the
  address again and writing whoever occupies it now (rf2-x874,
  docs/design/hicasso/product/invariants.md, the rf2-hic-013 record)."
  [ops]
  (let [dispatch-sync (:dispatch-sync ops)]
    (fn dispatch-for-frame [event]
      (with-commit (fn [] (dispatch-sync event)))
      nil)))

(defn frame-row
  "The runtime's one memo row for `frame-kw` — `{:incarnation :ops
  :dispatch}`, pinned to the incarnation live right now. `impl.frames`
  owns the table and the incarnation discipline; this door supplies the
  closure factory, so every caller reads the same row and the bundle can
  never describe a different incarnation than the closure that calls it."
  [frame-kw]
  (rf.hicasso.impl.frames/frame-row frame-kw mint-frame-dispatch))

(defn frame-dispatch
  "The ambient dispatch a boundary binds for its render's dynamic extent
  (HD-020(a)), memoised per frame INCARNATION so binding it allocates
  nothing; its identity is stable across renders of one incarnation and
  changes when the same id names a new one, which is what every lowered
  callback retains. Public because the shell is not the only thing that
  lowers hiccup: `impl.presence-react` re-binds the frame inside its own
  React render, after the parent body's extent has unwound, and the
  dispatch it binds has to be this one."
  [frame-kw]
  (:dispatch (frame-row frame-kw)))

;; ---------------------------------------------------------------------------
;; THE CELL TABLE — one cell per unique (frame, query), shared by every
;; reader, created and acquired ONLY at commit; its reader lists are the
;; dependency index
;; ---------------------------------------------------------------------------
;;
;; A sub-key is `[frame-kw query-v]` — finer than validation.md's
;; `(query-id, args)`, and the honest key for a runtime whose frames are
;; isolated contexts. `.-readers` is the key's reverse edge AND its
;; reference count, one slot per reader, so there is no `refs` counter
;; to drift from it; the forward edge is the registration's own key set
;; (docs/design/hicasso/architecture.md, section The collector).

;; `!cells` is public so the test kit's runtime door can count what the
;; table retains; every writer is in this file. One table for the whole
;; page and FRAME-SCOPED by its keying — the same query under two frames
;; is two cells, and a cross-frame read is an address that cannot be
;; spelled (`roots-frames-isolation-dom-cljs-test`;
;; docs/design/hicasso/product/globals.md carries every owner below).
(defonce !cells (atom {}))
(defonce ^:private !dirty (volatile! #{}))
(defonce ^:private !batching (volatile! false))
(defonce ^:private !deferred (volatile! #{}))

(def ^:private cell-watch-key
  "One constant keyword for every cell's value-change watch. A watch key
  need only be unique within the watched reference, and it is: at most
  one cell per `(frame, query)`, and no two cells hold the same
  reaction. A keyword minted per cell bought that same uniqueness at a
  `Keyword`, its name and its qualified string retained per unique key
  (docs/design/hicasso/studio/the-cold-read-mount-term.md, the mint's
  retirement)."
  ::cell-watch)

(declare flush!)

(defn- mark-dirty! [^js cell]
  (when-not (.-disposed cell)
    (vswap! !dirty conj cell)
    (when-not @!batching (flush!))))

(defn- dispose-cell! [^js cell]
  (when-not (.-disposed cell)
    (set! (.-disposed cell) true)
    (when-some [r (.-reaction cell)] (remove-watch r cell-watch-key))
    (swap! !cells dissoc (.-subKey cell))
    (rf.subs/unsubscribe (.-frameKw cell) (.-queryV cell)))
  nil)

;; ---------------------------------------------------------------------------
;; The reapers — one armed timer per horizon per turn
;; ---------------------------------------------------------------------------
;;
;; A cell whose last reader leaves gets one macrotask of grace, an
;; unclaimed entry `entry-reap-horizon-ms`; each horizon has ONE pending
;; queue and arms a timer only when none is running
;; (`reaper-coalescing-cljs-test`). A timer reaps only what it was ARMED
;; FOR — an item due at or before its target — and leaves what rode in
;; after it to a timer armed for that, so a task posted before an item's
;; horizon (React's passive flush, which claims an entry) always runs
;; first; a superseded timer drains nothing.

(defn- arm-timer!
  "Arm `queue`'s one timer for `due-at-ms`, superseding any timer still pending."
  [^js queue due-at-ms now-ms]
  (let [arm-id (inc (.-seq queue))]
    (set! (.-seq queue) arm-id)
    (set! (.-target queue) due-at-ms)
    (js/setTimeout (fn [] ((.-drain queue) arm-id))
                   (max 0 (- due-at-ms now-ms))))
  nil)

(defn- reap-queue
  "One pending queue for one horizon: the items waiting, the instant each
  falls due, the due the armed timer was armed for (`target`, -1 when
  none), the arm counter a drain checks itself against, and the drain —
  built once, so arming a timer allocates one closure."
  [horizon-ms reap!]
  (let [^js queue #js {"items" #js [] "due" #js [] "target" -1 "seq" 0 "horizon" horizon-ms}]
    (unchecked-set queue "drain"
      (fn drain [arm-id]
        (when (== arm-id (.-seq queue))
          (let [items            (.-items queue)
                due-times        (.-due queue)
                target-due-at-ms (.-target queue)
                now-ms           (js/Date.now)]
            (set! (.-target queue) -1)
            (loop []
              (when (pos? (alength items))
                (let [item-due-at-ms (aget due-times 0)
                      delay-ms       (- item-due-at-ms now-ms)]
                  ;; A `delay-ms` above the horizon means the clock moved under
                  ;; the queue — the test kit's virtual clock handing its
                  ;; timers back to the real one, a stepped system clock —
                  ;; and an item that cannot be waited for is reaped, never
                  ;; parked.
                  (when (or (> delay-ms horizon-ms)
                            (and (<= delay-ms 0)
                                 (<= item-due-at-ms target-due-at-ms)))
                    (.shift due-times)
                    (reap! (.shift items))
                    (recur)))))
            (when (pos? (alength items))
              (arm-timer! queue (aget due-times 0) now-ms))))))
    queue))

(defn- arm-reaper!
  "Queue `item` for `queue`'s horizon, arming the one timer if none is armed."
  [^js queue item]
  (let [now-ms     (js/Date.now)
        horizon-ms (.-horizon queue)]
    (.push (.-items queue) item)
    (if (neg? (.-target queue))
      (let [due-at-ms (+ now-ms horizon-ms)]
        (.push (.-due queue) due-at-ms)
        (arm-timer! queue due-at-ms now-ms))
      ;; A rider on a timer already armed. Where the horizon is measured it
      ;; is stamped one tick late: the clock is whole milliseconds and the
      ;; timer is not, so a drain could otherwise find it due up to a
      ;; millisecond short of the horizon a timer of its own would have
      ;; given it. A zero horizon measures nothing, and its rider is due
      ;; with the drain.
      (.push (.-due queue)
             (+ now-ms horizon-ms (if (pos? horizon-ms) 1 0)))))
  nil)

(defn- reset-reapers!
  "Forget what `queue` is waiting to reap. `reset-runtime!`'s half — a timer
  still armed is superseded and drains nothing."
  [^js queue]
  (set! (.-length (.-items queue)) 0)
  (set! (.-length (.-due queue)) 0)
  (set! (.-target queue) -1)
  (set! (.-seq queue) (inc (.-seq queue)))
  nil)

(def ^:private cell-reapers
  "A cell whose last reader unmounts is given one macrotask of grace, so
  a keyed reorder that unmounts and remounts a row within one turn reuses
  the reaction instead of rebuilding it."
  (reap-queue 0 (fn [^js cell]
                  (when (and (zero? (alength (.-readers cell)))
                             (not (.-disposed cell)))
                    (dispose-cell! cell)))))

(defn- arm-cell-reaper! [^js cell]
  (arm-reaper! cell-reapers cell))

(declare invalidate-cell!)

(defn- wire-cell!
  "Give `cell` a live subscription: subscribe, activate, take the one
  baseline deref, arm the value-change watch, and arm the disposal hook
  that routes to `invalidate-cell!`. The whole of a cell's attachment to
  the substrate in one place, because it is performed twice — at birth,
  and again when the substrate disposes the reaction out from under it.

  Activate FIRST, then watch, then observe: under the ratom family a
  reaction deref'd outside `*ratom-context*` watches nothing, so the
  watch would never fire and the runtime would paint once and go deaf
  (docs/design/hicasso/product/substrate-decision.md, the ratom-only
  line; a routed no-op on the React-hook spine). The disposal hook is an
  event rather than a term in the epoch sum because the substrate says
  exactly when a held reaction dies, and it covers every transition that
  disposes — `first-registration!` carries the one that does not
  (docs/design/hicasso/architecture.md, section The collector)."
  [^js cell]
  (let [frame-kw (.-frameKw cell)
        query-v  (.-queryV cell)
        reaction (rf.subs/subscribe query-v {:frame frame-kw})]
    (set! (.-reaction cell) reaction)
    (when (some? reaction)
      ;; On the substrate's PUSH path, before anything observes or watches it.
      (rf.interop/activate-derived-value! reaction)
      ;; ONE baseline deref, before the watch — see `acquire-cell!`.
      @reaction
      (add-watch reaction cell-watch-key
                 (fn [_ _ old nu] (when-not (= old nu) (mark-dirty! cell))))
      (rf.interop/add-on-dispose! reaction (fn [] (invalidate-cell! cell))))
    cell))

(defn- invalidate-cell!
  "The repair for a cell whose reaction can no longer answer for its key
  — after a `:sub` re-registration, a frame destruction (a same-id
  reincarnation included), or a first registration reaching here through
  `first-registration!`. Two phases: synchronously the reaction reference
  is dropped, so `read-key!` takes the cold probe against the live
  registration and frame from this instant on; at the microtask
  checkpoint the attachment is rebuilt and the cell re-stamped and
  notified, or disposed when the frame did not come back.

  Deferred because this runs inside the registrar's hooks and inside
  frame teardown, none of which is a place to subscribe; a microtask and
  never a macrotask, because the re-stamp is the render/commit tear's
  correction and design law React 3 requires it before visible paint
  (ruling rf2-2l17 on docs/design/hicasso/product/invariants.md, the
  rf2-hic-013 record; the argument in
  docs/design/hicasso/architecture.md, section The collector)."
  [^js cell]
  (when-not (.-disposed cell)
    (set! (.-reaction cell) nil)
    (js/queueMicrotask
      (fn []
        ;; `(nil? (.-reaction cell))`: `acquire-cell!` rewires a reused cell
        ;; too, and wiring one that already holds a reaction would add a
        ;; second `add-on-dispose!` hook, so the next disposal would
        ;; invalidate twice, wire twice and compound.
        (when (and (not (.-disposed cell)) (nil? (.-reaction cell)))
          (if (nil? (rf.frame/frame-incarnation-token (.-frameKw cell)))
            (dispose-cell! cell)
            (do (wire-cell! cell)
                ;; Re-stamp and notify: a boundary that painted before the
                ;; disposal painted the retired computation, and this is the
                ;; commit that corrects it.
                (mark-dirty! cell)))))))
  nil)

(defn- first-registration!
  "The registry transition no disposal announces. The replacement hook
  the sub-cache eviction rides fires only when a previous handler
  existed, so a FIRST `reg-sub` evicts nothing — and a cell that cached
  the substrate's uncached nil-recovery would hold it for the life of
  the mount. Off `registrar/add-registration-hook!`, narrowed to
  first-time `:sub` registrations (a replacement already arrives as a
  disposal) and to the cells still holding a reaction for the id (a cell
  mid-rebuild is about to subscribe against this very registration);
  each is repaired by `invalidate-cell!`. Reaches only the cells holding
  the id, so an unrelated registration moves no snapshot — the held-cell
  half of the registry axis; the staged half is `commit-basis`'s
  registry term (docs/design/hicasso/architecture.md, section The
  collector)."
  [{:keys [kind id was]}]
  (when (and (= :sub kind) (nil? was))
    ;; `first`, not `(nth … 0)`: a registrar hook's throw is SWALLOWED by
    ;; `registrar/register!`, so an exception here would not surface as a
    ;; failure — it would abandon the rest of the scan silently, leaving
    ;; some cells repaired and some not. `(sub [])` is a legal call that
    ;; reaches a cell (`subs/subscribe` treats a nil query-id as a miss
    ;; like any other), and `nth` on it would throw.
    (doseq [^js cell (vals @!cells)]
      (when (and (= id (first (.-queryV cell)))
                 (some? (.-reaction cell)))
        (invalidate-cell! cell))))
  nil)

(defn- sub-registered!
  "The runtime's one listening post on the registry: bump the registry
  epoch, then repair the cells that already hold the id
  (`first-registration!`). Both halves of the registry axis in one place
  because they are one event — first-time or replacement, a body may
  have read one computation while the commit acquires another, and
  neither the flush generation nor the frame's install epoch moves for
  it. Bump BEFORE scan: `invalidate-cell!`'s synchronous phase drops a
  reaction reference, and a render racing it must not see an epoch from
  before the registration it is about to read against."
  [{:keys [kind] :as registration}]
  (when (= :sub kind)
    (rf.hicasso.impl.generation/bump-registry-epoch!)
    (first-registration! registration))
  nil)

;; Armed once per process, at load: the `defonce` IS the arming and the var
;; is never read (docs/design/hicasso/product/globals.md). It costs a
;; keyword compare per registration and the scan only on a first-time `:sub`.
#_:clj-kondo/ignore
(defonce ^:private first-registration-armed
  (do (rf.registrar/add-registration-hook! sub-registered!) true))

(defn- acquire-cell!
  "Commit-phase only. Take (building if necessary) the durable reference
  for `sub-key` on `reg`'s behalf and attach the one watch that turns the
  sub layer's equality cutoff into this runtime's dirty set. Taking the
  reference and recording the edge are one act: `reg` is pushed onto the
  cell's reader list, which is both the key's reverse edge and its
  reference count, and a registration acquires each key of its read SET
  exactly once, so `release-cell!`'s `.indexOf` cannot find the wrong
  slot."
  [sub-key ^js reg]
  (let [^js cell (or (get @!cells sub-key)
                 (let [frame-kw (nth sub-key 0)
                       query-v  (nth sub-key 1)
                       ^js fresh #js {"subKey"   sub-key
                                     "frameKw"  frame-kw
                                     "queryV"   query-v
                                     "reaction" nil
                                     ;; NOT zero: a key with no cell
                                     ;; contributes the CURRENT basis to
                                     ;; `getSnapshot`, so a cell born at
                                     ;; the same basis reads the same.
                                     "epoch"    (rf.hicasso.impl.generation/commit-basis frame-kw)
                                     ;; The key's reverse edge AND its
                                     ;; reference count, in one array —
                                     ;; see the section header.
                                     "readers"  #js []
                                     "disposed" false}]
                   ;; ONE baseline deref, at construction, before the watch.
                   ;; "Acquire without deref" is not implementable here: a
                   ;; fresh reaction's `unset` baseline reports movement on
                   ;; the first later commit whatever it did
                   ;; (docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md
                   ;; §3.1). Made here, not trusted to the layer below,
                   ;; because the notification ITSELF is the dirty signal.
                   (wire-cell! fresh)
                   (swap! !cells assoc sub-key fresh)
                   fresh))]
    ;; A REUSED cell may hold nothing: `invalidate-cell!` drops the reaction
    ;; synchronously and rewires at the microtask checkpoint, and a reader
    ;; attached in between would be unreachable by `mark-dirty!` (measured:
    ;; painted once, deaf to a same-turn write). So the acquire wires it;
    ;; whichever of the two gets here first wires, the other finds a reaction.
    (when (nil? (.-reaction cell)) (wire-cell! cell))
    (.push (.-readers cell) reg)
    cell))

(defn- release-cell!
  "Drop `reg`'s membership — its edge on this key and its reference to
  this key's cell, which are one slot. A cell whose last reader leaves is
  handed to the reaper."
  [^js cell ^js reg]
  (let [readers (.-readers cell)
        i       (.indexOf readers reg)]
    (when (<= 0 i) (.splice readers i 1))
    (when (zero? (alength readers)) (arm-cell-reaper! cell)))
  nil)

;; ---------------------------------------------------------------------------
;; The commit — the only door through which a write becomes re-render work
;; ---------------------------------------------------------------------------

(defn- notify! [registrations]
  (doseq [^js registration registrations]
    (when-some [notify (.-notify registration)] (notify))))

(defn- dirty-readers
  "The boundaries a commit must re-run: the union of the dirty cells'
  reader lists. Laws 5 and 6 in one expression — a boundary reading two
  dirty keys is notified once, and a key nothing holds has no cell, so a
  dirty set made entirely of unread keys is empty rather than everything
  (`index-laws-cljs-test`)."
  [dirty]
  (reduce (fn [acc ^js cell] (into acc (.-readers cell))) #{} dirty))

(defn flush!
  "Turn the dirty cell set into re-render work: bump each dirty key's
  epoch, bump the generation, union the dirty cells' readers, and hand
  each one React's own `onStoreChange`.

  Notifications are deferred to a macrotask when a body is running: an
  `onStoreChange` fired from inside somebody's render is a render-phase
  update on another component, which React rejects — and which the
  generation fence has already made unnecessary for the *rendering*
  boundary."
  []
  (let [dirty @!dirty]
    (when (seq dirty)
      (vreset! !dirty #{})
      (rf.hicasso.impl.generation/bump-generation!)
      ;; Re-STAMP rather than increment, so a cell's epoch stays a
      ;; `commit-basis` reading comparable with a staged key's — floored
      ;; at one above the stamp it carried, because across a same-id frame
      ;; reincarnation the frame term RESTARTS and the basis alone can fail
      ;; to move (measured in Chromium: epoch 3 re-stamped to 3, the
      ;; notification delivered and ignored, the predecessor's value left
      ;; on screen). The floor can only raise a stamp, so the sum stays
      ;; monotone (docs/design/hicasso/product/invariants.md, rf2-hic-013;
      ;; `reincarnation-paint-dom-cljs-test`).
      (doseq [^js cell dirty]
        (set! (.-epoch cell) (max (inc (.-epoch cell))
                                  (rf.hicasso.impl.generation/commit-basis (.-frameKw cell)))))
      (let [boundaries (dirty-readers dirty)]
        (if (rendering?)
          (do (vswap! !deferred into boundaries)
              (js/setTimeout (fn []
                               (let [deferred-readers @!deferred]
                                 (vreset! !deferred #{})
                                 (notify! deferred-readers)))
                             0))
          (notify! boundaries)))))
  nil)

(defn with-commit
  "Run `thunk` inside one commit window: every subscription the writes inside
  it move is collected, and the boundaries that read them are notified
  once, after `thunk` returns. Re-entrant — a nested window joins the
  enclosing one rather than flushing early."
  [thunk]
  (if @!batching
    (thunk)
    (do (vreset! !batching true)
        (try (thunk)
             (finally (vreset! !batching false)
                      (flush!))))))

(defn dispatch!
  "The runtime's frame-locked dispatch — HD-019's synchronous door. The
  event drains synchronously inside the caller's turn and the store
  notification runs before the turn ends, so React commits the echo in
  the same turn. `frame-dispatch` applied: resolving the keyword and
  dispatching are one act against the incarnation live NOW, where a
  callback lowered into markup holds the closure and stays pinned to the
  incarnation it was lowered under."
  [frame-kw event]
  ((frame-dispatch frame-kw) event))

;; ---------------------------------------------------------------------------
;; The two read tiers (HD-002) — the render phase mutates nothing global
;; ---------------------------------------------------------------------------

(defn- cold-read!
  "Read a key no committed cell answers for. Reuses a live sub-cache
  reaction by deref alone when one exists; otherwise computes pure
  (`subs/compute-sub-with-memo`) against one frame-state snapshot per
  body run — the probe box on `rstate`, reset by `run-once` — memoised
  per run in the box's value map so a repeated key is a `find` (a
  memoised nil is a hit, which is what makes an unregistered query's
  `:rf.error/no-such-sub` one emission per run); falls back to
  `subscribe-once` when the frame is missing or destroyed. Runs inside
  `live-frame/call-with-frame-resolution` so an image-loaded frame
  resolves through its own image and a `reg-sub` from earlier in the
  tick is visible to this read (`cold-probe-cljs-test`). Creates no
  cache entry, takes no reference, installs no watch. The memo is per
  read, not run-shared, because the shared one cost more than it saved
  on the acceptance shape (2.75 vs 1.42 µs/read;
  docs/design/hicasso/studio/the-cold-read-mount-term.md)."
  [frame-kw query-v]
  (let [frame-record (rf.frame/frame frame-kw)]
    (if (nil? frame-record)
      (rf.subs/subscribe-once query-v {:frame frame-kw})
      (rf.live-frame/call-with-frame-resolution
        frame-kw
        (fn []
          (if-some [reaction (:reaction (get @(:sub-cache frame-record) query-v))]
            @reaction
            (let [^js probe (or (.-probe rstate)
                                (when-some [frame-state (rf.frame/frame-state-value frame-kw)]
                                  (let [^js fresh #js {"fs" frame-state "vals" {}}]
                                 (set! (.-probe rstate) fresh)
                                 fresh)))]
              (if (nil? probe)
                ;; The frame died between the record resolve and the
                ;; state read — recover exactly as rung 3 does.
                (rf.subs/subscribe-once query-v {:frame frame-kw})
                ;; `find`, not `get`: a memoised nil (an unregistered
                ;; query's recovery) is a HIT, and the one emission per
                ;; distinct unknown key per run rides on that.
                (if-some [cached-value (find (unchecked-get probe "vals") query-v)]
                  (val cached-value)
                  (let [value (rf.subs/compute-sub-with-memo
                                query-v
                                (unchecked-get probe "fs")
                                (atom {rf.subs/observation-opts-key
                                       {:frame frame-kw}}))]
                    (unchecked-set probe "vals"
                                   (assoc (unchecked-get probe "vals") query-v value))
                    value))))))))))

(defn- read-key!
  "One read: append the sub-key to the scratch and return the value. Warm
  — a committed cell holds a reaction — is a pure deref, nothing global
  touched. Cold, or a cell whose reaction `invalidate-cell!` has dropped,
  is `cold-read!`'s probe, which computes against the registration and
  frame incarnation live NOW and retains nothing, so an abandoned render
  leaves the world as it found it. Outside a render it fails loudly,
  naming the query: an escaped read would otherwise be a silent missing
  edge (`read-extent-cljs-test`)."
  [query-v]
  (when (nil? (.-frame rstate))
    (fail! :rf.error/hicasso-sub-outside-render
           're-frame.hicasso.impl.collector/read-key!
           (str "A subscription read " (pr-str query-v)
                " happened outside a boundary render. `sub` is legal only "
                "inside a defview body; `subscribe-once` is "
                "the sanctioned snapshot for handler and utility code.")
           {:query-v query-v}))
  (let [frame-kw (.-frame rstate)
        sub-key  [frame-kw query-v]]
    (.push scratch sub-key)
    (if-some [^js reaction (some-> ^js (get @!cells sub-key) (.-reaction))]
      @reaction
      (cold-read! frame-kw query-v))))

(defn sub
  "The ambient collector — a plain function call, legal anywhere in a
  body: inside a `when`, a `for`, an inlined helper. The edge is recorded
  where the read happens, so a branch not taken contributes no edge
  (docs/design/hicasso/decisions.md HD-002)."
  [query-v]
  (read-key! query-v))

;; ---------------------------------------------------------------------------
;; Read-set entries — the cached subscribe/getSnapshot pair, and the
;; zero-allocation detection of the unchanged case
;; ---------------------------------------------------------------------------

(defonce !entries
  ;; read-sequence hash -> vector of entries; `bucket-key-of` says why the
  ;; key is a hash of the WHOLE sequence rather than the first sub-key.
  (atom {}))

(defn- bucket-key-of
  "The bucket a read sequence belongs to: an order-sensitive hash of the
  whole sequence (`hash-value*31 + hash(read-key)`, int32), for the scratch a body just
  filled and for a hook's one-key array alike. It selects a bucket and
  is never an equality test — `entry-matches?` still compares every key
  pairwise, so a collision costs a second entry and never a wrong one.
  Costs `read-count` cached-hash reads: every sub-key was hashed this render when
  `read-key!` looked it up. The whole sequence rather than the first
  sub-key so the scan's cost is a function of the read set and not of
  how an author ordered their `let` bindings
  (docs/design/hicasso/architecture.md, section The collector)."
  [^js read-keys]
  (let [read-count (alength read-keys)]
    (loop [index 0 hash-value 1]
      (if (== index read-count)
        hash-value
        (recur (inc index)
               ;; hash-value*31 + hash(read-key), truncated to int32 — order-sensitive,
               ;; allocation-free, and the arithmetic is JS-exact.
               (bit-or 0 (+ (bit-shift-left hash-value 5)
                            (- hash-value)
                            (hash (aget read-keys index)))))))))

(defn- drop-entry! [^js entry]
  (let [bucket-key (.-bucketKey entry)]
    (swap! !entries
           (fn [entries-by-bucket]
             (let [remaining-entries
                   (vec (remove #(identical? % entry)
                                (get entries-by-bucket bucket-key)))]
               (if (seq remaining-entries)
                 (assoc entries-by-bucket bucket-key remaining-entries)
                 (dissoc entries-by-bucket bucket-key)))))
    nil))

(def entry-reap-horizon-ms
  "The provisional-entry reaper's delay: 4 ms, not 0. An entry is minted
  during the render and claimed during the commit, and on a root React
  renders concurrently (`hydrateRoot`) a `setTimeout 0` armed inside the
  render beats React's passive flush, so the entry is evicted before it
  is claimed and the next render re-subscribes. 4 ms was the shortest
  probed delay that read 1.00N
  (docs/design/hicasso/studio/coldmount-double-build-priced.md).

  A MARGIN, NOT A CONTRACT: React documents no maximum
  render-to-subscribe interval, no caller may rely on it, and a lost race
  costs a cache miss and a rebuilt subscription, never a wrong value."
  4)

(def ^:private entry-reapers
  "An entry with no committed boundary is dropped at the reap horizon —
  minted by a discarded render and never claimed, or released by its
  last boundary. Cache eviction, never a record of something to undo."
  (reap-queue entry-reap-horizon-ms
              (fn [^js entry]
                (when (zero? (.-refs entry)) (drop-entry! entry)))))

(defn- arm-entry-reaper! [^js entry]
  (arm-reaper! entry-reapers entry))

(defn reapers-armed?
  "Whether either reap queue holds an armed timer — what the test kit's
  `quiesced!` waits out, since a drain re-arms for what rode in after its
  own timer and the platform may clamp that timer past the item's horizon."
  []
  (or (not (neg? (.-target cell-reapers)))
      (not (neg? (.-target entry-reapers)))))

(defn- entry-matches?
  "Ordered pairwise compare of an entry's key array against `read-keys`.
  Allocates nothing. A false negative — same set, different order — costs
  a second entry and a symmetric difference that removes and re-adds the
  same edges; it is never a wrong answer, which is why the hash in
  `bucket-key-of` chooses the bucket and this decides the match."
  [^js entry ^js read-keys]
  (let [entry-read-keys (.-keys entry)
        read-count      (alength entry-read-keys)]
    (and (== read-count (alength read-keys))
         (loop [index 0]
           (cond
             (== index read-count) true
             (= (aget entry-read-keys index) (aget read-keys index))
             (recur (inc index))
             :else false)))))

(declare make-subscribe make-snapshot)

(defn- entry-for
  "The read-set entry for the read sequence `read-keys` — the cached
  `subscribe` / `getSnapshot` pair React sees. A hit allocates nothing
  and keeps `subscribe`'s identity, so React does not re-subscribe and
  the commit does no work; a miss materialises the key array, the key
  set and the two closures once, for every boundary that will ever read
  that set. `read-keys` is the scratch, or a hook's one-key array
  (`hook-entry`), and is never retained: a miss `.slice`s it."
  [^js read-keys]
  (let [bucket-key (bucket-key-of read-keys)
        bucket     (get @!entries bucket-key)]
    (or (some (fn [^js candidate-entry]
                (when (entry-matches? candidate-entry read-keys)
                  candidate-entry))
              bucket)
        (let [stored-read-keys (.slice read-keys)
              ^js entry #js {"keys"      stored-read-keys
                         "set"       (into #{} stored-read-keys)
                         "refs"      0
                         "bucketKey" bucket-key}]
          (unchecked-set entry "subscribe" (make-subscribe entry))
          (unchecked-set entry "snapshot" (make-snapshot entry))
          (swap! !entries update bucket-key (fnil conj []) entry)
          (arm-entry-reaper! entry)
          entry))))

(defn- make-snapshot
  "React's `getSnapshot`: the sum of the set's epochs, where a key no
  cell holds yet contributes its frame's live `commit-basis` instead of
  nothing. Monotone, so `Object.is` on it is a correct change test;
  cached on the entry. The staged term is what reaches the render→commit
  gap: `basis@render` while the boundary renders, `basis@commit` once
  the commit has created the cell, and React's post-subscribe re-read
  compares the two per fiber — a 0 there would answer the same number
  before and after however far the value moved. A mounted boundary has
  no staged term (docs/design/hicasso/architecture.md, section The
  collector)."
  [^js entry]
  (fn snapshot []
    (let [cells @!cells
          read-keys (.-keys entry)
          read-count (alength read-keys)]
      (loop [index 0 epoch-sum 0]
        (if (== index read-count)
          epoch-sum
          (let [read-key (aget read-keys index)]
            (recur (inc index)
                   (if-some [^js cell (get cells read-key)]
                     (+ epoch-sum (.-epoch cell))
                     (+ epoch-sum
                        (rf.hicasso.impl.generation/commit-basis (nth read-key 0)))))))))))

(defn- make-subscribe
  "React's `subscribe`, a pure function of the read set — the only
  global mutation in the state machine. The registration minted from
  React's `onStoreChange` installs itself in each read key's cell as a
  reader, and the returned cleanup is the exact inverse and the only
  place memberships are released, so teardown is symmetric with mount
  whatever React did in between. The registration is also the boundary
  id, which is what makes an edge-set change wholesale: a new read set
  is this cleanup followed by a fresh call to a different entry's
  `subscribe`. `.-reads` on the registration IS the forward edge, the
  entry's own key set by reference
  (docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md §2)."
  [^js entry]
  (fn subscribe [on-store-change]
    (let [reads (.-set entry)
          ^js registration #js {"reads" reads "notify" on-store-change}
          cells (mapv (fn [sub-key] (acquire-cell! sub-key registration)) reads)]
      (unchecked-set registration "cells" cells)
      (set! (.-refs entry) (inc (.-refs entry)))
      (fn unsubscribe []
        (set! (.-notify registration) nil)
        (doseq [cell cells] (release-cell! cell registration))
        (set! (.-refs entry) (dec (.-refs entry)))
        (when (<= (.-refs entry) 0) (arm-entry-reaper! entry))
        nil))))

(defn commit-boundary!
  "**The seam React occupies.** Hand a boundary's read set and a notifier
  to the same `subscribe` closure `useSyncExternalStore` would call, and
  get back the same cleanup React would hold. With `render-body` and
  `last-reads` it is the whole of a harness's render-then-commit — the
  test kit's and Xray's own suites take React's place through these three
  — and it is answerable without a browser, a root, or a render.

  For the entry `render-body` just resolved, that closure is the one the
  shell would hand React — in a dev build `view-subscribe`'s named
  wrapper when the body was a declared view — so a harness commit names
  the view exactly as React's does. Any other entry commits through its
  own `subscribe`: no render is in hand to name it."
  [^js entry notify]
  ((if (and ^boolean js/goog.DEBUG (identical? entry (.-entry rstate)))
     (.-subscribe rstate)
     (.-subscribe entry))
   notify))

;; ---------------------------------------------------------------------------
;; The hook seam — one read, from a React component that is not a boundary
;; ---------------------------------------------------------------------------
;;
;; `re-frame.hicasso.native/use-sub` runs inside a real React component: no
;; shell ran, `rstate` names no frame, the scratch is somebody else's. So
;; it cannot take `sub`; the two doors below are doors onto THIS module's
;; tables, never a second copy of them, so a hook and a boundary reading
;; one key share one entry, one registration shape and one cell.

(defn hook-entry
  "The read-set entry for the SINGLE key `sub-key` — what a hook hands
  `useSyncExternalStore`. A one-key read set is an ordinary read set, so
  the same key hits the same entry, `subscribe` is identical across
  re-renders and React does not call it again: a hook needs no `useMemo`
  and no `useRef`, and a boundary reading exactly this key shares this
  very entry."
  [sub-key]
  (entry-for #js [sub-key]))

(defn hook-read
  "The value of `sub-key`, read from OUTSIDE every boundary body —
  `read-key!`'s two tiers with the scratch and the ambient frame taken
  away. Warm is the same deref; cold is `cold-read!`'s probe, which is
  what a hook's first render needs, before React has called `subscribe`
  and any cell exists. The probe box is saved and restored around the
  call because `run-once` is what resets it for a body, and a hook read
  that left it behind would hand the next hook read a snapshot of a
  world that has since moved."
  [sub-key]
  (if-some [^js reaction (some-> ^js (get @!cells sub-key) (.-reaction))]
    @reaction
    (let [saved-probe (.-probe rstate)]
      (set! (.-probe rstate) nil)
      (try
        (cold-read! (nth sub-key 0) (nth sub-key 1))
        (finally (set! (.-probe rstate) saved-probe))))))

;; ---------------------------------------------------------------------------
;; The body run, and the generation fence
;; ---------------------------------------------------------------------------

(def ^:private max-fence-retries
  "A body is re-run once per commit that landed inside it. Three is a
  ceiling, not a budget: a fourth commit arriving inside three
  consecutive body runs is a write loop, and failing loudly beats
  spinning."
  3)

;; ---------------------------------------------------------------------------
;; Spec 006's two dev-mode DOM annotations
;; ---------------------------------------------------------------------------

(def ^:private view-attrs-slot
  "The dev-only own property carrying a declared view's Spec 006 DOM
  annotations, pre-built at the mint. Written under `goog.DEBUG` only —
  like `displayName` beside it and `views-slot` below — so a release
  bundle carries neither the slot name nor an attribute value in it; the
  literal is pinned on this line by
  `scripts/check_production_erasure.cjs`."
  "hicassoViewAttrs")

(defn view-annotations
  "The attrs map Spec 006 §Source-coord annotation and §View tagging
  contract require on a registered view's rendered root, built ONCE per
  declaration rather than once per render.

  The formatters are core's single cross-host implementation
  (`re-frame.source-coords`, reached here through the
  `re-frame.adapter.context` re-export this namespace already requires),
  so a Hicasso boundary's two attribute values are byte-identical to the
  ones Reagent, reagent-slim, UIx and the JVM SSR registration boundary
  emit for the same id. That is the whole property a tool rests on: one
  reader over every substrate.

  The id is `(keyword view-name)` — `view-name` is `\"<ns>/<sym>\"` and
  the registrar entry `publish-view-alias!` writes is keyed by exactly
  that keyword, so the attribute and the registrar agree by construction
  and `(rf/handler-meta :view id)` answers for the node a tool just read.

  The coordinate is the one `error/declaring!` recorded a moment earlier:
  the `defview` expansion opens the declaration extent BEFORE it mints,
  so the ledger already answers here. A boundary minted by calling
  `mint-view!` directly — a harness, a tool, an HMR re-registration — has
  no coordinate, and `format-source-coord` degrades those two segments to
  `?`, which is the graceful degradation Spec 006 names for a
  registration that bypassed the macro path."
  [view-name]
  (let [view-id (keyword view-name)]
    {:data-rf2-source-coord (rf.adapter.context/format-source-coord
                              view-id (rf.hicasso.impl.error/source-of view-name))
     :data-rf-view          (rf.adapter.context/format-view-id view-id)}))

(defn- author-owns-slot?
  "Does `authored` already write the React prop slot `slot`, in ANY of the
  spellings that land there? `codec/canonical-slot` is the resolver — the
  same one every deny, dissoc and check in the codec asks, and the one the
  emitter itself uses — so this cannot answer differently from what
  `convert-props` will do with the map."
  [authored slot]
  (reduce-kv (fn [_ k _] (if (= slot (rf.hicasso.impl.codec/canonical-slot k)) (reduced true) false))
             false
             authored))

(defn- without-authored-slots
  "`attrs` minus every entry whose canonical React slot `authored` already
  claims — `attrs` itself, by identity, when it claims neither.

  This is what makes the author's ownership a real one. A hiccup attribute
  key is written in five spellings (`re-frame.hicasso.impl.slot`), and
  every one of them emits under a single React name, so `merge` — which
  resolves a collision only between keys that are `=` — keeps BOTH a
  body's `\"data-rf-view\"` and the framework's `:data-rf-view`, and
  `convert-props` then writes them into one slot with the map's ITERATION
  ORDER picking the winner. Removing the framework's entry at the slot
  makes the collision a real key collision, and there is no second entry
  left for an order to choose between.

  Two entries against the root's attribute count, dev-mode only, on a map
  the codec was about to walk anyway."
  [attrs authored]
  (reduce-kv (fn [acc k _]
               (if (author-owns-slot? authored (rf.hicasso.impl.codec/canonical-slot k))
                 (dissoc acc k)
                 acc))
             attrs
             attrs))

(defn annotate-root
  "Merge a declared view's Spec 006 annotations into the root of the
  hiccup its body just returned, and answer the hiccup to encode.

  IT MUTATES THE EXISTING ROOT'S ATTRIBUTE MAP AND NEVER WRAPS. Spec 006
  §CRITICAL constraint: mutate, do not wrap forbids a synthetic host
  element because every layout idiom that reads the DOM tree shape breaks
  when one is interposed — flex and grid direct children, table
  anonymous-box generation, `:nth-child` and sibling selectors,
  positioning ancestors, stacking contexts, containment. It is also what
  makes the annotation free of a wrapper, a fiber and a hook, which is
  what lets Hicasso honour the contract inside HD-020's two-hook budget:
  the cost is two map entries on a map the codec was about to walk
  anyway, in a dev build only.

  ONLY a `:tag` root is annotated. `codec/head-kind` is the classifier the
  codec itself dispatches on, and its other answers are exactly Spec 006's
  documented non-DOM-root exemption: `:fragment` has no element to carry
  an attribute; `:raw` (`[:> …]`) and `:host` hand their props straight to
  a foreign component that never asked for framework-derived strings; and
  `:boundary` is another declared view, which tags its own root. A root
  that is not a vector at all — nil from a conditional body, a string, a
  seq — is left alone for the same reason.

  The author's attrs WIN, matching the Reagent walk
  (`re-frame.views.source-coord-annotation/inject-source-coord-attr`): a
  body that wrote either attribute itself keeps the value it wrote. That
  win is held AT THE CANONICAL SLOT and not at the key
  (`without-authored-slots`), because this codec accepts five spellings of
  one attribute and `merge` is keyed by `=` — so a body writing the string
  `\"data-rf-view\"` beside the framework's keyword left both in the map
  and let iteration order pick, which is the guarantee failing
  nondeterministically rather than failing (rf2-c5w1, audit of PR #9191).
  Ownership is per slot: the annotation a body did NOT write is still
  stamped beside the one it did."
  [hiccup attrs]
  (if (and (vector? hiccup)
           (pos? (count hiccup))
           (= :tag (rf.hicasso.impl.codec/head-kind (nth hiccup 0))))
    (let [maybe-attrs (nth hiccup 1 nil)]
      (if (map? maybe-attrs)
        (assoc hiccup 1 (merge (without-authored-slots attrs maybe-attrs) maybe-attrs))
        (into [(nth hiccup 0) attrs] (rest hiccup))))
    hiccup))

(defn- run-once
  "One body run. The scratch and the probe box are reset
  **unconditionally** — a reset guarded by \"if empty\" would concatenate
  two renders' reads, which is precisely what makes StrictMode's
  double-invoke correct here rather than additive.

  The body's hiccup passes through `annotate-root` before the codec sees
  it, under `goog.DEBUG`, so Spec 006's two annotations land on the root
  the boundary actually painted. Here rather than around the element the
  codec answered, because hiccup is where the contract's mutate-the-attrs
  rule is expressible; and inside the fence's per-attempt run rather than
  once around it, because each attempt produces its own hiccup."
  [frame-kw body-fn props]
  (set! (.-length scratch) 0)
  (set! (.-probe rstate) nil)
  (set! (.-frame rstate) frame-kw)
  ;; THE BODY-RUN COUNTER, bumped where a body actually runs and nowhere
  ;; else — the test kit's `body-runs` reads it. Here rather than in
  ;; `shell` because the generation fence can run a body twice for one
  ;; render, and a real count is the one that says so.
  (set! (.-bodyRuns rstate) (inc (.-bodyRuns rstate)))
  (try
    (rf.hicasso.impl.intent/with-frame frame-kw (frame-dispatch frame-kw)
      (fn []
        (rf.hicasso.impl.codec/as-element
          (let [out (body-fn props)]
            (if ^boolean js/goog.DEBUG
              (if-some [attrs (unchecked-get body-fn view-attrs-slot)]
                (annotate-root out attrs)
                out)
              out)))))
    (finally
      (set! (.-frame rstate) nil))))

;; The dev-only own property on a read-set entry: a `js/Map` from each
;; declared view's name to its committed-reference count and the `subscribe`
;; that keeps it. Written under `goog.DEBUG` only, so a release bundle
;; carries neither the slot name nor a name in it; the literal is pinned on
;; this line by `scripts/check_production_erasure.cjs`, like `body-slot`.
(def ^:private views-slot "hicassoViews")

(defn- view-subscribe
  "Dev only: the `subscribe` the shell hands React for `entry` when the
  body is the declared view named `view-name` — the entry's own closure,
  wrapped so `view-name` is counted where React commits the reference and uncounted where
  its cleanup releases it. The roster `re-frame.hicasso.tool` exports
  claims the MOUNTED views, and only the commit knows that: a render React
  discards and a view that has unmounted name nothing, exactly as they
  hold nothing (docs/design/hicasso/hd-002-adjudication.md §3). Cached per
  (entry, name) on the entry under `views-slot`, so its identity moves
  exactly when the entry's does and React re-subscribes on no render it
  did not already."
  [^js entry view-name]
  (let [^js views (or (unchecked-get entry views-slot)
                      (let [view-map (js/Map.)]
                        (unchecked-set entry views-slot view-map)
                        view-map))]
    (if-some [^js slot (.get views view-name)]
      (.-subscribe slot)
      (let [^js slot  #js {"refs" 0}
            shared    (.-subscribe entry)
            subscribe (fn subscribe [on-store-change]
                        (let [release (shared on-store-change)]
                          (set! (.-refs slot) (inc (.-refs slot)))
                          (fn unsubscribe []
                            (set! (.-refs slot) (dec (.-refs slot)))
                            (release))))]
        (unchecked-set slot "subscribe" subscribe)
        (.set views view-name slot)
        subscribe))))

(defn entry-views
  "The set of declared view names holding a committed reference on
  read-set `entry` — the mounted ones — or nil where none does, or in a
  production build, where nothing writes the slot. Read by
  `re-frame.hicasso.tool`; the names are what
  `re-frame.hicasso.impl.error/source-of` resolves to a coordinate."
  [^js entry]
  (when-some [^js views (unchecked-get entry views-slot)]
    (let [names (volatile! #{})]
      (.forEach views (fn [^js slot view-name]
                        (when (pos? (.-refs slot))
                          (vswap! names conj view-name))))
      (not-empty @names))))

(defn render-body
  "Run a boundary body under the generation fence and return its
  element; `last-reads` carries the entry it resolved, and in a dev
  build `rstate` also carries the `subscribe` the shell hands React —
  the entry's own, or `view-subscribe`'s named wrapper for a declared
  view. The fence: capture `commit-basis`, run the body, and if a commit
  landed while it ran, run again against the newer one — invariant-5
  preservation as one comparison per boundary rather than one deref per
  read. The basis rather than the generation alone, because a mid-body
  move of a key nothing holds moves no watch and so no generation
  (docs/design/hicasso/architecture.md, section The collector)."
  [frame-kw body-fn props]
  (loop [attempt 0]
    (let [basis-before (rf.hicasso.impl.generation/commit-basis frame-kw)
          element      (run-once frame-kw body-fn props)]
      (cond
        (= basis-before (rf.hicasso.impl.generation/commit-basis frame-kw))
        (let [entry (entry-for scratch)]
          (set! (.-entry rstate) entry)
          (when ^boolean js/goog.DEBUG
            (set! (.-subscribe rstate)
                  (if-some [view-name (unchecked-get body-fn "displayName")]
                    (view-subscribe entry view-name)
                    (.-subscribe entry))))
          element)

        (< attempt max-fence-retries)
        (recur (inc attempt))

        :else
        (fail! :rf.error/hicasso-generation-fence-exhausted
               're-frame.hicasso.impl.collector/render-body
               (str "A boundary body observed a new commit on each of "
                    (inc max-fence-retries) " consecutive runs. A body that "
                    "writes on every render cannot be fenced; move the write "
                    "out of the render.")
               {:frame frame-kw :generation (rf.hicasso.impl.generation/generation)})))))

(defn last-reads
  "The read-set entry the most recent `render-body` resolved — what a
  harness hands `commit-boundary!` to take React's place at the commit."
  []
  (.-entry rstate))

;; ---------------------------------------------------------------------------
;; The shell — exactly two React hooks, and no useRef
;; ---------------------------------------------------------------------------
;;
;; The shell's declared hook calls are the test kit's `shell-hook-ledger`
;; (`re-frame.hicasso.test.runtime`), and `hook_budget_cljs_test` counts
;; the calls React's own dispatcher received against it.

(defn resolve-frame!
  "The frame a React component is IN, from the one context every
  React-shaped substrate in this repo writes — or the refusal, when
  nothing above it wrote one. Public and `where`-taking because the
  native tier's hooks resolve their frame here too, so an island and the
  boundary beside it ask one question of one context. Deliberately not
  `frame/require-current-frame!`'s dynamic-var chain: a body's extent has
  unwound by the time React renders the component it returned, so the var
  tier can only answer for a different render than the one asking
  (docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md §3.2)."
  [frame-kw where]
  (if (or (nil? frame-kw) (= rf.adapter.context/no-provider-sentinel frame-kw))
    (fail! :rf.error/no-frame-context
           where
           (str "A Hicasso boundary rendered with no frame in scope. Mount the "
                "tree under a frame boundary — `h/mount!` installs one.")
           {})
    frame-kw))

(defn shell
  "The boundary shell. Two hooks, with the body between them — which is
  legal because what React fixes is hook *order and count*, not the
  position of ordinary code around them, and it is what lets the
  subscription hook close over the reads the body just made. In a dev
  build the subscription hook takes the `subscribe` `render-body` left on
  `rstate` — the entry's own, or the named wrapper that counts a declared
  view at React's commit; in production the branch folds away and the
  entry's own is all there is."
  [body-fn js-props]
  (let [frame-kw (resolve-frame! (react/useContext rf.adapter.context/frame-context)
                                 're-frame.hicasso.impl.collector/shell)
        props    (or (unchecked-get js-props "rfProps") {})
        element  (render-body frame-kw body-fn props)
        ^js entry (.-entry rstate)]
    (react/useSyncExternalStore (if ^boolean js/goog.DEBUG (.-subscribe rstate) (.-subscribe entry))
                                (.-snapshot entry)
                                (.-snapshot entry))
    element))

(defn mint-view!
  "Turn a body fn into a boundary: a React function component, marked as
  a legal hiccup head and wrapped in the codec's stable memo — minted once,
  at definition, which is why the codec's stable-heads cache (HD-004) has
  nothing to do here and why a plain function in head position can be a
  loud error (HD-016). Returns the head, still a function: `React.memo`
  answers an object, and the codec and the tests require a minted head to
  BE one, so `codec/memoize-boundary!` attaches the wrapper to the head
  and the codec creates elements from the wrapper.

  The component fn is Spec 009's `:render` bracket
  (`rf:render:<view-name>`, the string also stamped as `displayName`) and,
  in a dev build, `error/traced-boundary`'s origin, so a refusal raised
  below can name the view; both fold away under `:advanced` with their
  flags off. The bail-out is a scheduling optimisation and never
  observable semantics: React consults the boundary's own
  `useSyncExternalStore` and context updates before the comparator, so a
  boundary whose reads moved cannot be bailed out whatever its props say
  (docs/design/hicasso/decisions.md HD-028). Why the bracket sits on the
  component fn rather than in `render-body`, and what follows from that:
  docs/design/hicasso/architecture.md, section The collector."
  [view-name body-fn]
  (when ^boolean js/goog.DEBUG
    (unchecked-set body-fn "displayName" view-name)
    ;; Spec 006's two DOM annotations, built once here — the declaration
    ;; extent `defview` opened is still open, so `view-annotations` reads
    ;; this view's coordinate off `error`'s ledger rather than taking one
    ;; as a parameter every mint would have to thread.
    (unchecked-set body-fn view-attrs-slot (view-annotations view-name)))
  (let [component (fn hicasso-boundary [js-props]
                    (rf.performance/mark-and-measure :render view-name
                      (shell body-fn js-props)))
        ;; Dev only: the origin a refusal below names. `interop/debug-enabled?`
        ;; is `^boolean goog.DEBUG`, so under `:advanced` this `if` folds to
        ;; `component` and React calls the fn above, unchanged.
        component (if rf.interop/debug-enabled?
                    (rf.hicasso.impl.error/traced-boundary view-name component)
                    component)]
    (unchecked-set component "displayName" view-name)
    (let [head (rf.hicasso.impl.codec/memoize-boundary! (rf.hicasso.impl.codec/mark-boundary! component))]
      ;; The body, kept ON the head for the test kit's L2 walk alone, which
      ;; mounts nothing and would otherwise have no route back to it. One
      ;; own property; under `goog.DEBUG=false` it folds away with
      ;; `codec/retain-body!` (`view-body-retention-elision-prod-test`).
      (when ^boolean js/goog.DEBUG (rf.hicasso.impl.codec/retain-body! head body-fn))
      head)))

;; ---------------------------------------------------------------------------
;; The authoring-time alias — one registrar entry per declaration, dev only
;; ---------------------------------------------------------------------------

(defn publish-view-alias!
  "Publish the `:view` registrar alias for a `defview`, so a keyword an
  author wrote resolves forward to the boundary they meant. `view-id` is
  `(keyword \"<ns>\" \"<sym>\")` — the id `rf/reg-view` derives from its own
  symbol, so one convention answers for both substrates; `slot` is the
  coordinate map the macro captured (`:ns` / `:file` / `:line` / `:column`
  at the top level, where `(rf/handler-meta :view id)` reads them) plus the
  author's `:doc`; `head` is the minted boundary, stored under
  `:hicasso/component`. Answers `view-id` (spec/Conventions.md, the
  `reg-*` return-value convention).

  The entry is an ALIAS: it carries no `:handler-fn`, so `(rf/view id)`
  answers nil deliberately — a boundary is a React component, not a
  render fn — and `:executable-key` names `:hicasso/component` so a
  reload's `:rf.registry/handler-replaced` reports a real swap rather
  than an idempotent one. Written through `registrar/register!` rather
  than `rf/reg-view*`, which always builds a `:handler-fn` wrapper and
  consults the `:adapter/wrap-view` hook at registration time, which at
  namespace load precedes `rf/init!`. Dev only: called inside the
  `defview` expansion's `interop/debug-enabled?` gate, so the call, this
  fn and the slot leave a production bundle
  (`error-source-coord-elision-prod-test`). The argument:
  docs/design/hicasso/architecture.md, section The collector."
  [view-id slot head]
  (rf.registrar/register! :view view-id (assoc slot
                                       :hicasso/component head
                                       :executable-key    :hicasso/component))
  view-id)

;; ---------------------------------------------------------------------------
;; Teardown — the one door that empties every module
;; ---------------------------------------------------------------------------

(defn reset-runtime!
  "Drop every cell, every edge, every cached entry and every frame
  bundle; disposing each cell releases its sub-cache reference, so this
  is the leak check's reset rather than a way to hide one. The PAGE-WIDE
  fixture door, not root teardown: every table here is one-per-page and
  keyed by frame, so it empties the runtime under every root —
  `impl.mount/unmount!` is root teardown and reaches none of this. It
  calls each sibling's own door for what it does not hold, and it does
  not touch a root's hydration adoption window, which only that root's
  handle reaches (docs/design/hicasso/product/globals.md)."
  []
  (doseq [[_ cell] @!cells] (dispose-cell! cell))
  (reset! !cells {})
  (reset! !entries {})
  (vreset! !dirty #{})
  (vreset! !deferred #{})
  (vreset! !batching false)
  (reset-reapers! cell-reapers)
  (reset-reapers! entry-reapers)
  (rf.hicasso.impl.generation/reset-basis!)
  (set! (.-entry rstate) nil)
  (set! (.-subscribe rstate) nil)
  (set! (.-frame rstate) nil)
  (set! (.-probe rstate) nil)
  (set! (.-length scratch) 0)
  ;; `bodyRuns` is deliberately NOT reset here: witnesses take a DELTA
  ;; across the thing they measure, and a teardown door that zeroed the
  ;; counter would let a reading taken on the wrong side of a reset look
  ;; like a reading. The kit's `reset-body-runs!` is the explicit zero.
  (rf.hicasso.impl.frames/forget-frame-ops!)
  nil)
