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
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.error :as error :refer [fail!]]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.generation :as generation]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.performance :as performance :include-macros true]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Errors — `fail!` is `re-frame.hicasso.impl.error`'s
;; ---------------------------------------------------------------------------
;;
;; ONE constructor, `:refer`red so every call site below reads as a bare
;; `fail!` while the ns form says, once, where the shape lives.

;; ---------------------------------------------------------------------------
;; The render context
;; ---------------------------------------------------------------------------
;;
;; Module-level rather than per-render objects, and legal for one reason:
;; **boundary bodies do not nest**. A body returns hiccup; the codec turns
;; a child boundary into a React *element*, and React runs that child's
;; body later, after this one has returned. A plain helper called from a
;; body does run inside it — and its reads belong to the enclosing
;; boundary, which is exactly the collector's helper-donated read.

(def ^js rstate
  "The render slots: the frame the running body resolved (nil outside a
  render, which is what makes `(sub …)` outside a boundary a loud error
  rather than a silent read of whichever frame happened to be ambient),
  the entry the last body resolved, the cold-probe box the running body's
  cold reads share (`cold-read!` — nil until a cold read mints it, reset
  by every `run-once` exactly as the scratch is), the always-on body-run
  counter — one integer on the object that already exists, bumped by
  `run-once` — and, in a dev build only, the `subscribe` the shell hands
  React for that entry (`render-body`). One JS object for the whole
  runtime — not one per render.

  Public so the test kit's runtime door (`re-frame.hicasso.test.runtime`)
  can read `bodyRuns` off it without this file growing a reader per
  instrument. It is this file's to WRITE, and every writer is here."
  #js {"frame" nil "entry" nil "probe" nil "bodyRuns" 0 "subscribe" nil})

(def ^:private ^js scratch
  "**The one scratch buffer**, reused by every body and reset by
  overwrite at the top of each. One render's reads, in read order,
  nothing else, and no allocation: `(set! (.-length scratch) 0)` is the
  whole of the reset. There is exactly one of it, which is the point —
  a second would mean telling two render attempts apart, and that is the
  ledger."
  #js [])

(defn rendering?
  "Is a boundary body running right now?"
  []
  (some? (.-frame rstate)))

;; ---------------------------------------------------------------------------
;; The ambient dispatch a body binds — the memo row is
;; `re-frame.hicasso.impl.frames`'s; the closure in it is this file's,
;; because it is `with-commit` partially applied
;; ---------------------------------------------------------------------------

(declare with-commit)

(defn- mint-frame-dispatch
  "Mint the ambient dispatch closure for ONE frame incarnation, over the
  `capture-frame` bundle that incarnation was pinned with.

  It closes over `ops` and never over the frame keyword, and that is
  load-bearing. A callback lowered under incarnation A calls A's own
  `:dispatch-sync`, so once A is destroyed core's `capture-frame` fence
  refuses it (recover-but-emit `:rf.error/frame-destroyed`) instead of
  resolving the address a second time and finding whoever occupies it now.
  A closure that re-resolved the keyword at fire time would silently
  write the successor whenever the memo was cold.

  `:dispatch-sync` is destructured once, at mint, rather than on every
  event: the row is per incarnation, so there is nothing left to look up."
  [ops]
  (let [dispatch-sync (:dispatch-sync ops)]
    (fn dispatch-for-frame [event]
      (with-commit (fn [] (dispatch-sync event)))
      nil)))

(defn frame-row
  "The runtime's one memo row for `frame-kw` — `{:incarnation :ops :dispatch}`,
  pinned to the incarnation live right now.

  [[re-frame.hicasso.impl.frames/frame-row]] owns the table and the
  incarnation discipline; this is the door that supplies the closure factory,
  so every caller in the runtime reads the SAME row and the bundle can never
  describe a different incarnation than the closure that calls it."
  [frame-kw]
  (frames/frame-row frame-kw mint-frame-dispatch))

(defn frame-dispatch
  "The ambient dispatch a boundary binds for its render's dynamic extent
  (HD-020(a)), memoised per frame INCARNATION so binding it allocates
  nothing.

  **Public because a boundary shell is not the only thing that lowers
  hiccup**. `impl.presence-react` renders retained
  children inside its OWN React render, after the parent body's dynamic
  extent has unwound, so it must re-bind the ambient frame before it
  hands them to the codec — and the dispatch it binds has to be *this*
  one. Handing it a private route of its own (a fresh closure per render)
  would allocate a closure per presence render and would make \"a presence
  child lowers exactly as it would in the parent's body\" an approximation
  rather than an identity. Nothing new is exported: the memo, the
  `capture-frame` pin and the [[with-commit]] batching are the ones
  `run-once` already binds.

  The identity it hands back is stable across repeated renders of one
  incarnation and CHANGES when the same public id names a new one — which
  is the point, because that identity is what every lowered callback
  retains."
  [frame-kw]
  (:dispatch (frame-row frame-kw)))

;; ---------------------------------------------------------------------------
;; THE CELL TABLE — one cell per unique (frame, query), shared by every
;; reader, created and acquired ONLY at commit; and the dependency index
;; itself
;; ---------------------------------------------------------------------------
;;
;; A sub-key is `[frame-kw query-v]`. validation.md pins sub-key identity
;; as `(query-id, args)` under value equality; qualifying it by frame is
;; strictly finer, and it is the honest key for a runtime in which two
;; frames are isolated contexts holding two different app-dbs. The pair is
;; a value, so every law below reads it exactly as it reads a bare query
;; vector.
;;
;; ## The reverse edge lives on the cell
;;
;; A second process-global structure beside this table — a sub-index
;; holding `sub-key -> #{boundary}` and `boundary -> #{sub-key}` — would
;; be keyed by the SAME B·R key space. Every read would then pay two
;; persistent map entries where the design needs one table, plus, at
;; fan-out 1 (which is what the
;; distinct-query ladder rung measures), a singleton `PersistentHashSet`
;; per key whose whole job was to hold one pointer.
;;
;; So the readers moved onto the cell. `.-readers` is that key's reverse
;; edge, and one slot in it is simultaneously **the boundary's edge on the
;; key and its reference to the key's cell** — which is why the cell has
;; no `refs` counter beside it: the reader list IS the count, and two
;; records that must stay equal are one record that cannot drift. The
;; forward edge needs no home either: [[make-subscribe]]'s registration
;; already holds `.-reads`, the read-set entry's own key set, by
;; reference.
;;
;; **No lookup got worse.** The dirty set was already a walk of the dirty
;; keys' reader sets, and [[flush!]] already holds the dirty CELLS — an
;; index would need `.-subKey` mapped over them purely to map them
;; straight back. There is no such round trip: the union is taken
;; directly off the cells in hand.
;;
;; The table is owned by its sole consumer (architecture.md §2), which is
;; what lets it be this specific: a general, separately-testable index
;; algebra would have to serve callers that do not exist.
;;
;; Cells are plain JS objects rather than a deftype on purpose: this is
;; the object the heap ladder prices per unique key, and a deftype would
;; add a constructor and a prototype to a structure with no behaviour.
;; `.-readers` is a JS array for the same reason — at the fan-out this
;; table actually sees, a `.push` and an `.indexOf` beat a persistent set
;; and retain one object rather than a container per membership.

;; `!cells` is public so the test kit's runtime door
;; (`re-frame.hicasso.test.runtime`) can count what the table retains
;; without this file growing a reader per instrument. It is the
;; collector's to WRITE, and every writer is in this file.
;;
;; **One table for the whole page, and FRAME-SCOPED anyway** — the sub-key
;; is `[frame-kw query-v]`, so the same query read under two frames
;; occupies two cells with two reader lists and two reactions, and a
;; cross-frame read is not a collision to be prevented but an address that
;; cannot be spelled. Isolation between roots is a property of this KEYING
;; and not one React provides — React knows nothing about this table.
;; `roots_frames_isolation_dom_cljs_test` is the standing witness and reads
;; the key set rather than the DOM, because a boundary that resolved the
;; wrong frame still renders a plausible page.
;; `docs/design/hicasso/product/globals.md` carries the same disposition for
;; every other module-level owner here — `!entries`, the four flush-extent
;; refs below, `rstate` and `scratch`.
(defonce !cells (atom {}))
(defonce ^:private !dirty (volatile! #{}))
(defonce ^:private !batching (volatile! false))
(defonce ^:private !deferred (volatile! #{}))

(def ^:private cell-watch-key
  "**One constant keyword for every cell's value-change watch** — not a
  minted-per-cell identity.

  A watch key has to be unique *within the watched reference*, and it is:
  there is at most one cell per `(frame, query)`, `subs/subscribe` hands
  back that pair's own cached reaction, and no two cells ever hold the
  same reaction — so one namespaced constant collides with nothing this
  arm installs, and its namespace keeps it clear of any other watcher keyed
  per observer on the same reactions.

  A `(keyword \"rf-hicasso\" (str \"w\" (vswap! counter inc)))` would buy
  that same uniqueness by allocating a `Keyword`, its name string and its
  fully-qualified string per cell and retaining all three in the cell and
  in the reaction's watch map — per *unique key*, which is per *read* on
  the distinct-query rung the per-read heap ladder is taken on. The
  uniqueness is already structural; only the identity would be
  paid for. The counter goes with it: a global that numbered something
  that never needed a number."
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
    (subs/unsubscribe (.-frameKw cell) (.-queryV cell)))
  nil)

;; ---------------------------------------------------------------------------
;; The reapers — one armed timer per horizon per turn
;; ---------------------------------------------------------------------------
;;
;; A cell whose last reader leaves and an entry nobody has claimed are each
;; given a horizon of grace before they are dropped — one macrotask for a
;; cell, [[entry-reap-horizon-ms]] for an entry. Neither arms a timer of
;; its own: each horizon has ONE pending queue, and an arm pushes onto it
;; and starts a timer only when none is running. A cold mount of 300
;; distinct-read boundaries arms one timer rather than three hundred, and
;; unmounting them arms two rather than six hundred
;; (`reaper_coalescing_cljs_test` counts both).
;;
;; What a per-item timer gave for free, and the one timer has to keep: an
;; item's reaper task is enqueued no earlier than the item's own horizon,
;; so a task posted before that horizon — React's passive flush, which is
;; what claims an entry — runs first. A timer therefore reaps only what it
;; was ARMED FOR, an item due at or before its target, and leaves what
;; rode in after it to a timer armed for that; a timer `reset-runtime!`
;; or a re-arm has superseded does nothing at all. Measured before this
;; held: a drain armed by one test's release, still pending after the
;; reset, dropped the next test's entry between its render and React's
;; flush, and the island re-subscribed on its next render (the island
;; hooks' W9 row).

(defn- arm-timer!
  "Arm `q`'s one timer for `due`, superseding any timer still pending."
  [^js q due now]
  (let [id (inc (.-seq q))]
    (set! (.-seq q) id)
    (set! (.-target q) due)
    (js/setTimeout (fn [] ((.-drain q) id)) (max 0 (- due now))))
  nil)

(defn- reap-queue
  "One pending queue for one horizon: the items waiting, the instant each
  falls due, the due the armed timer was armed for (`target`, -1 when
  none), the arm counter a drain checks itself against, and the drain —
  built once, so arming a timer allocates one closure."
  [horizon-ms reap!]
  (let [^js q #js {"items" #js [] "due" #js [] "target" -1 "seq" 0 "horizon" horizon-ms}]
    (unchecked-set q "drain"
      (fn drain [id]
        (when (== id (.-seq q))
          (let [items  (.-items q)
                due    (.-due q)
                target (.-target q)
                now    (js/Date.now)]
            (set! (.-target q) -1)
            (loop []
              (when (pos? (alength items))
                (let [head (aget due 0)
                      left (- head now)]
                  ;; A `left` above the horizon means the clock moved under
                  ;; the queue — the test kit's virtual clock handing its
                  ;; timers back to the real one, a stepped system clock —
                  ;; and an item that cannot be waited for is reaped, never
                  ;; parked.
                  (when (or (> left horizon-ms)
                            (and (<= left 0) (<= head target)))
                    (.shift due)
                    (reap! (.shift items))
                    (recur)))))
            (when (pos? (alength items))
              (arm-timer! q (aget due 0) now))))))
    q))

(defn- arm-reaper!
  "Queue `x` for `q`'s horizon, arming the one timer if none is armed."
  [^js q x]
  (let [now        (js/Date.now)
        horizon-ms (.-horizon q)]
    (.push (.-items q) x)
    (if (neg? (.-target q))
      (let [due (+ now horizon-ms)]
        (.push (.-due q) due)
        (arm-timer! q due now))
      ;; A rider on a timer already armed. Where the horizon is measured it
      ;; is stamped one tick late: the clock is whole milliseconds and the
      ;; timer is not, so a drain could otherwise find it due up to a
      ;; millisecond short of the horizon a timer of its own would have
      ;; given it. A zero horizon measures nothing, and its rider is due
      ;; with the drain.
      (.push (.-due q) (+ now horizon-ms (if (pos? horizon-ms) 1 0)))))
  nil)

(defn- reset-reapers!
  "Forget what `q` is waiting to reap. [[reset-runtime!]]'s half — a timer
  still armed is superseded and drains nothing."
  [^js q]
  (set! (.-length (.-items q)) 0)
  (set! (.-length (.-due q)) 0)
  (set! (.-target q) -1)
  (set! (.-seq q) (inc (.-seq q)))
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
        reaction (subs/subscribe query-v {:frame frame-kw})]
    (set! (.-reaction cell) reaction)
    (when (some? reaction)
      ;; On the substrate's PUSH path, before anything observes or watches it.
      (interop/activate-derived-value! reaction)
      ;; ONE baseline deref, before the watch — see `acquire-cell!`.
      @reaction
      (add-watch reaction cell-watch-key
                 (fn [_ _ old nu] (when-not (= old nu) (mark-dirty! cell))))
      (interop/add-on-dispose! reaction (fn [] (invalidate-cell! cell))))
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
        ;; `(nil? (.-reaction cell))` because [[acquire-cell!]] rebuilds the
        ;; attachment too, the moment a commit reuses a cell this call left
        ;; empty. Wiring a cell that already holds a reaction
        ;; would add a SECOND `add-on-dispose!` hook to it, so the next
        ;; disposal would invalidate twice, wire twice and compound — the
        ;; guard is what makes the two writers idempotent with respect to
        ;; each other rather than merely both correct.
        (when (and (not (.-disposed cell)) (nil? (.-reaction cell)))
          (if (nil? (frame/frame-incarnation-token (.-frameKw cell)))
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
  "The runtime's whole listening post on the registry, and the two halves of
  the registry axis in one place because they are one event.

  **Bump then scan.** [[re-frame.hicasso.impl.generation/registry-epoch]] counts every `:sub` registration,
  first-time or replacement, because both are the same defect in the
  render→commit gap: the body read one computation and the commit
  acquires against another, and neither the flush generation nor the
  frame's install epoch moves for either. [[first-registration!]] then
  repairs the cells that already hold the id, which is the half a
  replacement reaches by its own disposal and a first registration
  reaches by no route at all.

  The bump is before the scan deliberately. [[invalidate-cell!]]'s
  synchronous phase drops a reaction reference, and a render that races
  it must not see an epoch from before the registration it is about to
  read against."
  [{:keys [kind] :as registration}]
  (when (= :sub kind)
    (generation/bump-registry-epoch!)
    (first-registration! registration))
  nil)

;; Arm it once per process, at load. `defonce` is the arming, so the var
;; exists for its side effect and is deliberately never read — the hook
;; vector is the only thing that holds the fn. The hook is the substrate's
;; own extension point and the runtime installs nothing else global; it costs
;; a keyword compare on every registration of any kind, a `vswap!` on
;; every `:sub` one, and the scan above only on a first-time `:sub`.
#_:clj-kondo/ignore
(defonce ^:private first-registration-armed
  (do (registrar/add-registration-hook! sub-registered!) true))

(defn- acquire-cell!
  "**Commit-phase only.** Take (building if necessary) the durable
  reference for `sub-key` on `reg`'s behalf, and attach the one watch
  that turns the sub layer's equality cutoff into this runtime's dirty set.
  Acquire without deref: the render already knows the value.

  Taking the reference and recording the edge are **one act**: `reg` is
  pushed onto the cell's reader list, which is both the key's reverse
  edge and its reference count. A registration acquires each
  key of its read SET exactly once, so a slot per reader is the whole
  invariant and `.indexOf` in [[release-cell!]] cannot find the wrong
  one."
  [sub-key ^js reg]
  (let [^js cell (or (get @!cells sub-key)
                 (let [frame-kw (nth sub-key 0)
                       query-v  (nth sub-key 1)
                       ^js fresh #js {"subKey"   sub-key
                                     "frameKw"  frame-kw
                                     "queryV"   query-v
                                     "reaction" nil
                                     ;; NOT zero. A key with no cell
                                     ;; contributes the CURRENT
                                     ;; `commit-basis` to `getSnapshot`,
                                     ;; so a cell born at the same basis
                                     ;; contributes the same number and
                                     ;; a mount that raced nothing
                                     ;; re-renders for nothing. Born at a
                                     ;; LATER basis — something installed
                                     ;; in the gap — it contributes a
                                     ;; different one, and React's
                                     ;; post-subscribe re-check corrects
                                     ;; the boundary.
                                     "epoch"    (generation/commit-basis frame-kw)
                                     ;; The key's reverse edge AND its
                                     ;; reference count, in one array —
                                     ;; see the section header.
                                     "readers"  #js []
                                     "disposed" false}]
                   ;; ONE baseline deref, at construction, before the watch.
                   ;;
                   ;; HD-002's adjudication sketches commit-phase
                   ;; acquisition as "acquire without deref: the value is
                   ;; already known from the render". Against this
                   ;; substrate that is not implementable, and the failure
                   ;; is silent: a derived value starts at an `unset`
                   ;; baseline that is never `rf=` a real value, and the
                   ;; render's own read went through the cold probe,
                   ;; which built no reaction at all. So
                   ;; a freshly acquired reaction whose baseline is still
                   ;; `unset` reports movement on the FIRST later commit
                   ;; whatever the commit did — every newly mounted
                   ;; boundary re-rendering once for nothing. Establishing
                   ;; the baseline here costs one compute per *new unique
                   ;; key*, on a path that has to compute anyway. It is
                   ;; emphatically not the forbidden commit-phase re-read:
                   ;; that one is per read per commit and is a tear check;
                   ;; this one never runs again for the life of the cell.
                   ;;
                   ;; The watch then hands us old and new, so the movement
                   ;; test is free. It is made here rather than trusted to
                   ;; the layer below because this runtime uses the
                   ;; notification ITSELF as the dirty signal: the shipping
                   ;; spine can tolerate a notification that did not move,
                   ;; since `useSyncExternalStore` re-compares snapshots
                   ;; after it, and this runtime cannot.
                   ;;
                   ;; [[wire-cell!]] performs that deref, that watch and the
                   ;; disposal hook, because a cell is attached to the
                   ;; substrate twice — here, and again if the substrate
                   ;; disposes the reaction underneath it.
                   (wire-cell! fresh)
                   (swap! !cells assoc sub-key fresh)
                   fresh))]
    ;; **A REUSED cell may be holding nothing**.
    ;; [[invalidate-cell!]] drops the reaction synchronously and defers the
    ;; rewire to the microtask checkpoint, so between those two moments the
    ;; table holds a cell with no reaction and no watch. A reader attached to
    ;; it then is unreachable by [[mark-dirty!]] — the boundary renders the
    ;; right value (a reaction-less cell takes [[cold-read!]]'s probe) and
    ;; nothing notifies it until the deferred rewire lands. Measured: a
    ;; boundary mounted in that window painted once and did not move on a
    ;; write taken in the same turn.
    ;;
    ;; So the acquire wires it, and that is not a second owner of the rewire
    ;; — it is this function keeping the promise its own first line makes.
    ;; The deferral exists because `invalidate-cell!` is called from inside
    ;; the registrar's hooks and frame teardown, *\"and none of them is a
    ;; place to subscribe\"*; a commit is precisely such a place, which is why
    ;; the whole cell table is commit-owned. Whichever of the two gets here
    ;; first wires the cell and the other finds a reaction and does nothing.
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
  (doseq [^js r registrations]
    (when-some [n (.-notify r)] (n))))

(defn- dirty-readers
  "The boundaries a commit must re-run: the union of the dirty cells' own
  reader lists.

  Laws 5 and 6 in one expression. It is a union, so a boundary reading two
  dirty keys is notified once; and a key nothing holds has no cell, so it
  is not in `dirty` at all and contributes no phantom reader — a dirty set
  made entirely of unread keys is empty rather than everything."
  [dirty]
  (reduce (fn [acc ^js c] (into acc (.-readers c))) #{} dirty))

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
      (generation/bump-generation!)
      ;; Re-STAMP rather than increment, so a cell's epoch is a
      ;; `commit-basis` reading and does not drift into a private
      ;; numbering the staged term could not be compared against —
      ;; floored at one above the stamp it carried, because across a
      ;; same-id frame reincarnation the basis alone can FAIL TO MOVE.
      ;;
      ;; A frame's install epoch is NOT monotone across a same-id
      ;; reincarnation: `generation/commit-basis` says so itself — a
      ;; reincarnation RESTARTS `frame-commit-epoch`. Measured in
      ;; Chromium: a boundary mounted across an A→B switch holds epoch 3
      ;; from basis (gen 1 + frame 2); the successor seats at frame epoch
      ;; 1; bumping the generation to 2 re-stamps to 1 + 2 = 3 — the SAME
      ;; NUMBER. `getSnapshot` ties, React's `checkIfSnapshotChanged`
      ;; finds nothing, no body re-runs, and the predecessor's value
      ;; stays on screen indefinitely: the notification is delivered and
      ;; ignored, which is the one failure a re-stamp must make
      ;; impossible.
      ;;
      ;; The floor is what restores monotonicity. It can only raise the
      ;; stamp, never lower it, so a staged key's `commit-basis` reading
      ;; stays comparable with a held key's stamp in the direction that
      ;; matters, and the sum stays monotone.
      (doseq [^js c dirty]
        (set! (.-epoch c) (max (inc (.-epoch c))
                               (generation/commit-basis (.-frameKw c)))))
      (let [boundaries (dirty-readers dirty)]
        (if (rendering?)
          (do (vswap! !deferred into boundaries)
              (js/setTimeout (fn []
                               (let [d @!deferred]
                                 (vreset! !deferred #{})
                                 (notify! d)))
                             0))
          (notify! boundaries)))))
  nil)

(defn with-commit
  "Run `f` inside one commit window: every subscription the writes inside
  it move is collected, and the boundaries that read them are notified
  once, after `f` returns. Re-entrant — a nested window joins the
  enclosing one rather than flushing early."
  [f]
  (if @!batching
    (f)
    (do (vreset! !batching true)
        (try (f)
             (finally (vreset! !batching false)
                      (flush!))))))

(defn dispatch!
  "The runtime's frame-locked dispatch — HD-019's synchronous door. The event
  drains synchronously inside the caller's turn (the discrete browser
  event, for an intent) and the store notification runs before that turn
  ends, so React commits the echo in the same turn.

  It is [[frame-dispatch]] applied, not a second route to the same place:
  resolving the keyword and dispatching are ONE act, taken now, against the
  incarnation live now. A caller handing it a bare keyword is asking for the
  frame at that ADDRESS, and that is what it gets — whereas a callback
  lowered into markup holds the closure itself and stays pinned to the
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
  (let [frame-record (frame/frame frame-kw)]
    (if (nil? frame-record)
      (subs/subscribe-once query-v {:frame frame-kw})
      (live-frame/call-with-frame-resolution
        frame-kw
        (fn []
          (if-some [r (:reaction (get @(:sub-cache frame-record) query-v))]
            @r
            (let [^js pr (or (.-probe rstate)
                             (when-some [fs (frame/frame-state-value frame-kw)]
                               (let [^js fresh #js {"fs" fs "vals" {}}]
                                 (set! (.-probe rstate) fresh)
                                 fresh)))]
              (if (nil? pr)
                ;; The frame died between the record resolve and the
                ;; state read — recover exactly as rung 3 does.
                (subs/subscribe-once query-v {:frame frame-kw})
                ;; `find`, not `get`: a memoised nil (an unregistered
                ;; query's recovery) is a HIT, and the one emission per
                ;; distinct unknown key per run rides on that.
                (if-some [kv (find (unchecked-get pr "vals") query-v)]
                  (val kv)
                  (let [v (subs/compute-sub-with-memo
                            query-v
                            (unchecked-get pr "fs")
                            (atom {subs/observation-opts-key
                                   {:frame frame-kw}}))]
                    (unchecked-set pr "vals"
                                   (assoc (unchecked-get pr "vals") query-v v))
                    v))))))))))

(defn- read-key!
  "One read: append the sub-key to the scratch, and return the value.

  Warm — a committed cell holds the key — is a pure deref: no acquire,
  no release, nothing global touched. Cold is [[cold-read!]]'s probe,
  which reuses a live sub-cache reaction by deref alone or computes pure
  against the run's one frame-state snapshot, retains nothing, and so
  leaves an abandoned render's world as it found it.

  A cell whose reaction [[invalidate-cell!]] has dropped takes the cold
  path too, and that is the whole of what an invalidated read needs to be
  correct: the probe computes against the registration and the frame
  incarnation that are live NOW, so a render in the window between
  the invalidation and its rebuild reads the new computation rather than
  the retired one — or, for a key registered for the FIRST time while the
  boundary was mounted, the real handler rather than the nil-recovery."
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
    (if-some [^js r (some-> ^js (get @!cells sub-key) (.-reaction))]
      @r
      (cold-read! frame-kw query-v))))

(defn sub
  "**The ambient collector** — the only acceptable read surface on
  ergonomics. A plain function call, legal anywhere in a
  body: inside a `when`, inside a `for`, inside an inlined helper. The
  edge is *recorded* where the read happens, and the recorded set is what
  the commit installs — so a branch not taken contributes no edge."
  [query-v]
  (read-key! query-v))

;; ---------------------------------------------------------------------------
;; Read-set entries — the cached subscribe/getSnapshot pair, and the
;; zero-allocation detection of the unchanged case
;; ---------------------------------------------------------------------------

(defonce !entries
  ;; read-sequence hash -> vector of entries. See [[bucket-key-of]] for
  ;; why the key is a hash of the WHOLE sequence rather than the first
  ;; sub-key.
  (atom {}))

(defn- bucket-key-of
  "The bucket a read sequence belongs to: an order-sensitive hash of the
  whole sequence (`h*31 + hash(k)`, int32), for the scratch a body just
  filled and for a hook's one-key array alike. It selects a bucket and
  is never an equality test — `entry-matches?` still compares every key
  pairwise, so a collision costs a second entry and never a wrong one.
  Costs `n` cached-hash reads: every sub-key was hashed this render when
  `read-key!` looked it up. The whole sequence rather than the first
  sub-key so the scan's cost is a function of the read set and not of
  how an author ordered their `let` bindings
  (docs/design/hicasso/architecture.md, section The collector)."
  [^js ks]
  (let [n (alength ks)]
    (loop [i 0 h 1]
      (if (== i n)
        h
        (recur (inc i)
               ;; h*31 + hash(k), truncated to int32 — order-sensitive,
               ;; allocation-free, and the arithmetic is JS-exact.
               (bit-or 0 (+ (bit-shift-left h 5) (- h) (hash (aget ks i)))))))))

(defn- drop-entry! [^js entry]
  (let [bucket-key (.-bucketKey entry)]
    (swap! !entries
           (fn [m]
             (let [left (vec (remove #(identical? % entry) (get m bucket-key)))]
               (if (seq left) (assoc m bucket-key left) (dissoc m bucket-key)))))
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
  "An entry with no committed boundary is dropped at the reap horizon.
  Two callers, one rule: an entry a discarded render minted was never
  claimed, and an entry whose last boundary unmounted is no longer
  anybody's. Both are cache eviction and neither is a record of something
  to undo.

  The horizon is [[entry-reap-horizon-ms]] — deliberately past a bare
  `setTimeout 0`, and deliberately not a promise to anyone."
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
  "Ordered pairwise compare of an entry's key array against `ks`.
  Allocates nothing. A false negative — same set, different order — costs
  a second entry and a symmetric difference that removes and re-adds the
  same edges; it is never a wrong answer, which is why the hash in
  [[bucket-key-of]] chooses the bucket and this decides the match."
  [^js entry ^js ks]
  (let [eks (.-keys entry)
        n   (alength eks)]
    (and (== n (alength ks))
         (loop [i 0]
           (cond
             (== i n)                    true
             (= (aget eks i) (aget ks i)) (recur (inc i))
             :else                       false)))))

(declare make-subscribe make-snapshot)

(defn- entry-for
  "The read-set entry for the read sequence `ks` — the cached
  `subscribe` / `getSnapshot` pair React sees. A hit allocates nothing and
  keeps `subscribe`'s identity, so React does not re-subscribe and the
  commit does no work; a miss materialises the key array, the key set and
  the two closures **once**, for every boundary that will ever read that
  set.

  The bucket is [[bucket-key-of]]'s hash of the whole read sequence,
  so what a lookup scans is the set of read sequences that COLLIDE —
  never the set of live boundaries that happen to share a first
  key. `drop-entry!`'s rebuild of the bucket vector is O(1) for the same
  reason.

  `ks` is the scratch when a boundary body resolves its reads, and a
  one-key array when a React hook does ([[hook-entry]]). The array is
  never retained: a miss `.slice`s it, and a hit reads nothing off it
  after the compare."
  [^js ks]
  (let [bucket-key (bucket-key-of ks)
        bucket     (get @!entries bucket-key)]
    (or (some (fn [^js e] (when (entry-matches? e ks) e)) bucket)
        (let [ks    (.slice ks)
              ^js entry #js {"keys"      ks
                         "set"       (into #{} ks)
                         "refs"      0
                         "bucketKey" bucket-key}]
          (unchecked-set entry "subscribe" (make-subscribe entry))
          (unchecked-set entry "snapshot" (make-snapshot entry))
          (swap! !entries update bucket-key (fnil conj []) entry)
          (arm-entry-reaper! entry)
          entry))))

(defn- make-snapshot
  "React's `getSnapshot`: the sum of the set's epochs, where a key **no
  cell holds yet** contributes its frame's current [[re-frame.hicasso.impl.generation/commit-basis]]
  instead of nothing. Monotone, so `Object.is` on it is a correct change
  test; cached on the entry, so a render allocates no closure for it.

  That one term is what reaches the render→commit gap. A staged key
  contributes `basis@render` while the boundary renders and, once the
  commit has created its cell, `basis@commit` — the same number when
  nothing moved in between and a different one when something did. It is
  a *live* [[re-frame.hicasso.impl.generation/commit-basis]] read, which is why the basis's registry term
  reaches a `reg-sub` in the gap and why a held key —
  whose contribution is the cell's frozen stamp — is untouched by one.
  React re-reads this closure immediately after `subscribe` returns
  (`updateStoreInstance` is the next passive effect) and compares
  against the value **that fiber** captured at render, so the tear check
  is per boundary, is one number, and holds no record of what any read
  returned. Returning 0 for a key with no epoch would make a staged key
  answer the same number before and after the commit however far its
  value had moved, so React would see no tear, schedule no re-render, and
  never correct the boundary.

  Steady state pays nothing for it: a mounted boundary holds a reference
  to every key it reads, so every term is a cell epoch and the staged
  branch is never taken."
  [^js entry]
  (fn snapshot []
    (let [cells @!cells
          ks    (.-keys entry)
          n     (alength ks)]
      (loop [i 0 acc 0]
        (if (== i n)
          acc
          (let [k (aget ks i)]
            (recur (inc i)
                   (if-some [^js c (get cells k)]
                     (+ acc (.-epoch c))
                     (+ acc (generation/commit-basis (nth k 0)))))))))))

(defn- make-subscribe
  "React's `subscribe`, as a pure function of the read set.

  **The only global mutation in the state machine.** The boundary's
  registration is minted from React's own `onStoreChange` and installs
  itself in each read key's cell as a reader — which is that key's
  reverse edge and the boundary's reference to it, one slot rather than
  two records. The returned cleanup is the exact inverse and is the only
  place memberships are released, so teardown is symmetric with mount
  whatever React did with the renders in between.

  The registration holds exactly the cells it acquired, so its cleanup
  cannot release a successor's after a reap and rebuild.

  **The forward edge needs no home**: `.-reads` on the registration IS
  it — the entry's own key set, shared by reference and never copied, so
  the fused table stores the reverse edge and nothing else.

  **The registration is also the boundary id, and that is what makes the
  replacement wholesale here** — a fresh id every time, so a read-set
  change is this cleanup followed by a fresh call to a different entry's
  `subscribe`: the edge-set replacement in full, done by the pair. See
  the ns docstring, clause (b).

  **Abandoned renders are safe structurally rather than by a guard.** A
  design that wrote edges during the render would need a liveness check
  so a stale body run could not resurrect an unmounted boundary's edges;
  here there is no render-phase write to guard, because the only write is
  inside this closure and React calls it at commit and nowhere else."
  [^js entry]
  (fn subscribe [on-store-change]
    (let [reads (.-set entry)
          ^js reg #js {"reads" reads "notify" on-store-change}
          cells (mapv (fn [sub-key] (acquire-cell! sub-key reg)) reads)]
      (unchecked-set reg "cells" cells)
      (set! (.-refs entry) (inc (.-refs entry)))
      (fn unsubscribe []
        (set! (.-notify reg) nil)
        (doseq [cell cells] (release-cell! cell reg))
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
;; `re-frame.hicasso.native/use-sub` is a real React hook inside a real
;; React function component, and a component is not a
;; boundary: no shell ran, no body ran, `rstate` names no frame and the
;; scratch holds somebody else's reads or none. So the hook cannot take
;; [[sub]], and the two doors below are what it takes instead.
;;
;; **They are doors onto this module's tables, never a second copy of
;; them.** [[hook-entry]] mints its entry from the same cache, with the
;; same `subscribe` and the same `getSnapshot`, so a hook and a boundary
;; reading one key SHARE one entry, one registration shape and one cell —
;; which is why `re-frame.hicasso.tool`'s rosters see a hook's reads
;; without knowing hooks exist, and why the residue census counts them.
;; A private table here would have bought a hook that leaked invisibly.

(defn hook-entry
  "The read-set entry for the SINGLE key `sub-key` — what a hook hands
  `useSyncExternalStore`.

  **Identity is the whole point.** React re-subscribes whenever the
  `subscribe` it is given is a new function, so a hook that built its
  closure per render would tear the subscription down and rebuild it on
  every re-render — releasing and re-acquiring the cell, and doing it
  invisibly, because the value on screen would be right the whole time.
  The entry cache already answers that: the same key hits the same
  entry, so `subscribe` is identical across re-renders and React does
  not call it again. A hook therefore needs no `useMemo` and no
  `useRef`, and holds to the shell's own hook budget.

  A one-key read set is an ordinary read set. Nothing below this line
  knows the difference, and a boundary whose body reads exactly this one
  key shares this very entry."
  [sub-key]
  (entry-for #js [sub-key]))

(defn hook-read
  "The value of `sub-key`, read from OUTSIDE every boundary body —
  [[read-key!]]'s two tiers with the scratch and the ambient frame taken
  away.

  Warm is the same pure deref: once the hook's `subscribe` has run, the
  cell exists and holds the reaction, so every render after the first is
  a `get` and an `@`. Cold is [[cold-read!]]'s probe, unchanged and
  still retaining nothing — which is what the render→commit gap needs,
  because a hook's first render happens before React has called
  `subscribe` and therefore before any cell exists.

  **The probe box is scoped to this call, and that is not tidiness.**
  `cold-read!` shares one frame-state snapshot across a body run and
  [[run-once]] is what resets it; a hook read that left the box behind
  would hand the NEXT hook read, arbitrarily later, a snapshot of a
  world that has since moved. Saving and restoring costs one local and
  makes the question unaskable. The saved value is nil in every path
  that exists today — React renders a component after its parent body
  has returned, never inside one — so what the restore protects is the
  invariant rather than a caller.

  It takes the SUB-KEY rather than the pair, so a hook builds the one
  vector the cell table is keyed by and hands it to both doors."
  [sub-key]
  (if-some [^js r (some-> ^js (get @!cells sub-key) (.-reaction))]
    @r
    (let [saved (.-probe rstate)]
      (set! (.-probe rstate) nil)
      (try
        (cold-read! (nth sub-key 0) (nth sub-key 1))
        (finally (set! (.-probe rstate) saved))))))

;; ---------------------------------------------------------------------------
;; The body run, and the generation fence
;; ---------------------------------------------------------------------------

(def ^:private max-fence-retries
  "A body is re-run once per commit that landed inside it. Three is a
  ceiling, not a budget: a fourth commit arriving inside three
  consecutive body runs is a write loop, and failing loudly beats
  spinning."
  3)

(defn- run-once
  "One body run. The scratch and the probe box are reset
  **unconditionally** — a reset guarded by \"if empty\" would concatenate
  two renders' reads, which is precisely what makes StrictMode's
  double-invoke correct here rather than additive."
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
    (intent/with-frame frame-kw (frame-dispatch frame-kw)
      (fn [] (codec/as-element (body-fn props))))
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
  body is the declared view named `n` — the entry's own closure, wrapped
  so `n` is counted where React commits the reference and uncounted where
  its cleanup releases it. The roster `re-frame.hicasso.tool` exports
  claims the MOUNTED views, and only the commit knows that: a render React
  discards and a view that has unmounted name nothing, exactly as they
  hold nothing (docs/design/hicasso/hd-002-adjudication.md §3). Cached per
  (entry, name) on the entry under `views-slot`, so its identity moves
  exactly when the entry's does and React re-subscribes on no render it
  did not already."
  [^js entry n]
  (let [^js views (or (unchecked-get entry views-slot)
                      (let [m (js/Map.)] (unchecked-set entry views-slot m) m))]
    (if-some [^js slot (.get views n)]
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
        (.set views n slot)
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
      (.forEach views (fn [^js slot n]
                        (when (pos? (.-refs slot)) (vswap! names conj n))))
      (not-empty @names))))

(defn render-body
  "Run a boundary body under the generation fence and return its element;
  `last-reads` carries the read-set entry it resolved, and in a dev build
  `rstate` also carries the `subscribe` the shell hands React for it —
  the entry's own, or `view-subscribe`'s named wrapper when the body is
  a declared view, so the name is written at the commit and never here.

  The fence is the loop: capture the
  `re-frame.hicasso.impl.generation/commit-basis`, run the body, and if a
  commit landed while it ran, run it again against the newer commit. All
  of a pass's reads therefore observe one commit — invariant-5
  preservation as one comparison per boundary, not one deref per read.

  It compares the basis rather than the generation alone because the
  generation cannot see a mid-body move of a key nothing holds: no cell,
  so no watch, so no `mark-dirty!`, so no bump. A body that read a
  staged key, dispatched, and read again could straddle two commits with
  the generation sitting perfectly still. The frame's install epoch
  moves for that write, so the basis does."
  [frame-kw body-fn props]
  (loop [attempt 0]
    (let [before  (generation/commit-basis frame-kw)
          element (run-once frame-kw body-fn props)]
      (cond
        (= before (generation/commit-basis frame-kw))
        (let [entry (entry-for scratch)]
          (set! (.-entry rstate) entry)
          (when ^boolean js/goog.DEBUG
            (set! (.-subscribe rstate)
                  (if-some [n (unchecked-get body-fn "displayName")]
                    (view-subscribe entry n)
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
               {:frame frame-kw :generation (generation/generation)})))))

(defn last-reads
  "The read-set entry the most recent [[render-body]] resolved — what a
  harness hands [[commit-boundary!]] to take React's place at the commit."
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
  "The frame a React component is IN, taken from the one context every
  React-shaped substrate in this repo writes — or the refusal, when
  nothing above it wrote one.

  Public, and `where`-taking, because the boundary shell is no longer
  its only caller: the native tier's hooks resolve their
  frame HERE rather than through a second chain of their own. That is
  the property `frames are isolated contexts` reduces to in code — an
  island and the boundary beside it ask one question of one context and
  cannot be told different answers, so `n/use-sub` has no door onto a
  frame the surrounding tree is not already in.

  Deliberately NOT `frame/require-current-frame!`'s dynamic-var →
  context chain, which is what the UIx-adapter hooks use. A body's
  dynamic extent has unwound by the time React renders the component it
  returned, so the var tier can only ever answer for a *different*
  render than the one asking — and answering from it would let an
  island read a frame its own subtree is not under."
  [frame-kw where]
  (if (or (nil? frame-kw) (= adapter-context/no-provider-sentinel frame-kw))
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
  (let [frame-kw (resolve-frame! (react/useContext adapter-context/frame-context)
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
  (when ^boolean js/goog.DEBUG (unchecked-set body-fn "displayName" view-name))
  (let [component (fn hicasso-boundary [js-props]
                    (performance/mark-and-measure :render view-name
                      (shell body-fn js-props)))
        ;; The refusal shape's ambient half. The wrapper makes
        ;; this boundary the origin for the duration of its render, so a
        ;; refusal raised anywhere below can name the view and resolve the
        ;; source coordinate `defview` captured. `interop/debug-enabled?` is
        ;; `^boolean goog.DEBUG`, so under `:advanced` + `goog.DEBUG=false`
        ;; this `if` folds to `component` and what React calls is the
        ;; component fn above, unchanged.
        component (if interop/debug-enabled?
                    (error/traced-boundary view-name component)
                    component)]
    (unchecked-set component "displayName" view-name)
    (let [head (codec/memoize-boundary! (codec/mark-boundary! component))]
      ;; The body, kept ON the head, for the test kit alone.
      ;; A minted head is a React component and its body is reachable only
      ;; through `shell`, so `re-frame.hicasso.test` — which mounts nothing
      ;; and runs no hook — would otherwise have no route back to the
      ;; function the author wrote. L2 renders one, so the head carries it.
      ;;
      ;; ONE own property, and nothing else changes: no registry, no map,
      ;; no per-view object, and the returned value is still the function.
      ;; `goog.DEBUG` is the same gate the `displayName`
      ;; stamp above uses, so under `:advanced` + `goog.DEBUG=false` this
      ;; folds away with `codec/retain-body!` behind it and a production
      ;; head answers nil — asserted against the real advanced bundle in
      ;; `re-frame.hicasso.view-body-retention-elision-prod-test`.
      (when ^boolean js/goog.DEBUG (codec/retain-body! head body-fn))
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
  (registrar/register! :view view-id (assoc slot
                                       :hicasso/component head
                                       :executable-key    :hicasso/component))
  view-id)

;; ---------------------------------------------------------------------------
;; Teardown — the one door that empties every module
;; ---------------------------------------------------------------------------

(defn reset-runtime!
  "Drop every cell, every edge, every cached entry and every frame bundle.
  Disposing each cell releases its sub-cache reference, so this is the
  leak check's reset rather than a way to hide one.

  **The PAGE-WIDE fixture door, and not part of root teardown.**
  Every table it empties is one-per-page and keyed by frame,
  so calling it to tear a root down empties the runtime under every other
  root on the page too. `impl.mount/unmount!` is root teardown and
  reaches none of this; `impl.mount/release!` is the fixture pairing that
  ends with a page holding nothing.

  It lives here because the collector holds most of what it drops, and it
  calls each sibling's own door for the rest — a module that owns state
  owns the act of emptying it, so this fn cannot silently miss a slot
  somebody adds elsewhere.

  **It does NOT touch the hydration adoption window**. A window
  belongs to the root that minted it and is reachable only from that
  root's handle, so there is nothing page-wide to rescue: a root whose
  construction threw left an unreachable object, and a root that is torn
  down is closed by `impl.mount/unmount!`. Reaching in from here would be
  cross-root interference — a reset in one root shutting a sibling root's
  window while it is still adopting."
  []
  (doseq [[_ cell] @!cells] (dispose-cell! cell))
  (reset! !cells {})
  (reset! !entries {})
  (vreset! !dirty #{})
  (vreset! !deferred #{})
  (vreset! !batching false)
  (reset-reapers! cell-reapers)
  (reset-reapers! entry-reapers)
  (generation/reset-basis!)
  (set! (.-entry rstate) nil)
  (set! (.-subscribe rstate) nil)
  (set! (.-frame rstate) nil)
  (set! (.-probe rstate) nil)
  (set! (.-length scratch) 0)
  ;; `bodyRuns` is deliberately NOT reset here: witnesses take a DELTA
  ;; across the thing they measure, and a teardown door that zeroed the
  ;; counter would let a reading taken on the wrong side of a reset look
  ;; like a reading. The kit's `reset-body-runs!` is the explicit zero.
  (frames/forget-frame-ops!)
  nil)
