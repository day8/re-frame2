(ns re-frame.bench.hicasso.arm1.runtime
  "HICASSO ARM 1 — LEAN-REACT. The runtime skeleton (rf2-2rtt6.9).

  A boundary is a real React function component minted by `defview`
  (`re-frame.bench.hicasso.arm1.lang`). React owns identity,
  reconciliation, context, refs, errors, concurrency and the
  controlled-input end-of-event restore; this namespace owns exactly
  three things React does not: *which* boundaries a commit must re-run,
  *how* a boundary's body reaches subscription values, and *the fence*
  that keeps one render pass on one commit.

  Residence: the bench/test tree, off every production source path
  (HD-017). Nothing under `implementation/*/src` requires this.

  ## The shell, and the ≤2-hook budget (HD-020)

  The whole shell is two React hook calls and no `useRef`:

      1. `useContext(frame-context)`         the frame hook
      2. `useSyncExternalStore(sub, snap)`   the subscription/epoch hook

  There is no third hook, and — this is the part that took the design —
  **no per-instance render-phase state at all**. That is not a saving,
  it is what makes the budget reachable: React offers a function
  component no per-instance storage except a hook cell, so a shell that
  wants one has spent a third hook (`useRef`/`useState`) before it
  starts. The way out is to notice what the two closures actually need
  to close over.

  - `subscribe` closes over **the render's sub-key set and nothing
    else**, so it is a pure function of a value. It lives on a
    [[read-set entry|entry-for]] cached by that value, shared by every
    boundary reading the same set, and therefore identity-stable across
    a re-render whose reads did not change — which is exactly the
    condition React uses to decide whether to re-subscribe. **An
    unchanged hot read performs no new attach and no new release, and
    the commit does no work at all**, because React never calls the
    closure again.
  - React calls that shared `subscribe` once per *fiber*, handing it that
    fiber's own `onStoreChange`. The registration minted inside is
    therefore per-boundary, durable for the mount, and commit-owned — so
    it is the boundary id the index keys on, and a render that never
    commits registers nothing.
  - `getSnapshot` also lives on the entry and returns the **sum of the
    set's epochs**, where a key nothing holds yet contributes the
    [[commit-basis]] rather than nothing (see *the render→commit gap*
    below). Every term only ever increases, so the sum is monotone:
    `Object.is` on one number is a correct change test, and React's own
    \"getSnapshot should be cached\" rule is satisfied without a memo.

  The hook budget is not self-reported — `arm1_hook_ledger_dom_cljs_test`
  counts hook calls at React's own dispatcher.

  ## The collector is the surface being made to work

  The operator ruled on 2026-07-31 that the ambient collector — `sub` as
  an ordinary function call, legal inside a `when`, inside a `for`, and
  inside an inlined helper — is the only read surface acceptable on
  ergonomics, and that grouped `use-subs` sits below the usability bar.
  So [[sub]] is the surface this arm engineers for and [[use-subs]] is
  kept as the control it is measured against. That inverts which tier is
  defended; it waives none of HD-002's correctness gates, and the
  tripwire still overrides the clock.

  ### The ownership state machine (clause (a))

  One sentence carries it, and it is the positive form of the tripwire:

  > **No render-phase code mutates the index or a subscription
  > ref-count. The only global mutation is the commit's.**

      RENDERING   the body runs; reads append to ONE module-level scratch
                  array, reset unconditionally at the top of every body
      PENDING     the body returned; the scratch has been resolved to a
                  cached read-set entry; the index is untouched
      COMMITTED   React called the entry's `subscribe`; the registration
                  exists, the edges are installed, the cells are acquired
      UNMOUNTED   React called the cleanup; edges and references released

  An abandoned render is `PENDING → RENDERING`, and it needs no cleanup
  because it never did anything that would need cleaning. The scratch is
  reset by overwrite, not by bookkeeping, which is also why StrictMode's
  double-invoke is correct rather than additive.

  ### The allowed edge-diff operation (clause (b))

  A boundary's edge set is **replaced wholesale, once, at commit**, and
  only when the read set actually changed, because an unchanged set
  leaves the `subscribe` closure identical and React does not call it
  again. The unchanged case is detected **without building anything**:
  the scratch array is compared pairwise against the cached entry's key
  array, so steady-state allocation for edge maintenance is zero bytes
  and the allocation slope across warm 1/3/7/20 reads is flat.

  **The replacement is `unmount-all` + `mount-all`, not a set difference,
  and it is worth being exact about that (rf2-2rtt6.47).** The clause
  used to be discharged against `front.sub-index/record-reads`'s
  difference — the operation `front/sub_index_laws_cljs_test` proves law
  4 for. This wiring never takes its dropping half. A boundary id here is
  the registration object minted inside [[make-subscribe]], so a changed
  read set means a new entry, a new `subscribe`, a new registration and
  therefore a new id; `index/mount!` installs `#{}` for it, and the
  `record-reads!` that follows sees `held = #{}`, `added = the whole read
  set`, `dropped = #{}` every single time. The narrowing is done by the
  *previous* registration's cleanup calling `index/unmount!`, which drops
  every edge outright. `the-wired-path-never-takes-the-diffs-dropping-half`
  asserts that, so the claim is checkable rather than argued.

  What the changed case therefore costs, for an `n`-read boundary with
  one key different: `n` `release-cell!`s and up to `n` armed reapers,
  one entry miss (a `.slice`, an `(into #{} ks)` and two closures), `n`
  `acquire-cell!`s, one `mount!` and one `record-reads!` — two whole-map
  rebuilds of `:sub->bs`. There is **no cheap route for \"19 of 20 keys
  unchanged\"**, and a page whose rows change read set on a data change
  pays it per row. That is the honest price and the thing to watch on the
  bulk rows.

  A durable per-boundary id would make the difference live, and it is
  **unavailable at this arm's fences rather than merely unbuilt**. It has
  to survive a re-subscribe, so it cannot live on the registration; the
  shell has no per-instance storage that is not a hook, and a third hook
  breaks the HD-020 budget the ledger witness enforces; and threading one
  into `subscribe` would mean `subscribe` closing over something other
  than the read set, which is what makes it a shared, identity-stable
  pure function of a value in the first place. Buying the diff costs the
  two properties this arm exists to demonstrate, so it is not bought.
  The difference stays in the shared front half — general, proved, and
  driven in its degenerate form from here.

  The ordered compare is a false-negative device and never a wrong
  answer: two renders that read the same keys in a different order miss
  the compare, take a second entry with the same set, and React replaces
  a set with itself — slower, still correct. The compare is what decides
  a match, and it is deliberately not *replaced* by a content hash, whose
  failure mode would be a silently missing edge. A hash does choose which
  entries the compare is run against ([[scratch-bucket-key]]), which is a
  different job with a different failure mode — a collision costs a
  second entry, never a wrong one.

  **No per-read object, no second candidate slot, nothing keyed by a
  render or an attempt, and no commit-phase deref.** The commit consults
  sub-keys only; the generation fence, not a re-read, is what preserves
  invariant 5.

  ### Laziness, and why the collector window closes around the codec

  A Surface B property worth stating in the runtime rather than only in a
  test, because a host language with lazy seqs can lose it silently. `for`
  returns a **lazy sequence**, so every `(sub …)` inside one runs when
  something walks the seq — not when the body returns. A collector closed
  at the body's return would register **no edge for any row**, and would
  look perfectly correct on the first render, because the values are right
  once realised; it would simply never update again.

  This arm is safe by construction: [[run-once]] closes the window around
  `codec/as-element`, and the codec is eager everywhere it walks —
  `expand-seq` drives a seq to exhaustion, `realize-children` folds one
  into a vector, a seq at a *native* prop position goes through
  `clj->js`, and `front.codec/realize-deep` forces every lazy sequence
  reachable from a *boundary's* props before the crossing hands them on.
  A lazy read is therefore forced inside the window by the same pass that
  turns hiccup into elements, and moving the codec call out of `run-once`
  fails `a-lazy-for-registers-its-edges-and-its-readers-re-run`.

  The fourth clause is a repair, and the shape of what it repairs is the
  reason [[read-key!]]'s guard below is not the whole story: the boundary
  hand-off used to pass its props map through raw, so a seq written in
  one body was realised inside ANOTHER body's render — where the guard
  finds a frame, does not throw, and files the read under the wrong
  boundary. rf2-2rtt6.45, and `arm1/boundary-crossing-cljs-test`.

  **An eager codec is only half of it, and the other half matters more.**
  A codec can force the reads it walks; nothing can force a read the
  author deferred past the render — a handler closure, a `delay`, a lazy
  seq stashed rather than returned. Each of those would otherwise be a
  *silent* missing edge: correct on screen, frozen thereafter,
  attributable to nothing. So the render frame is set in a `try` and
  cleared in the matching `finally`, and [[read-key!]] fails loudly when
  it finds none. Every escape becomes an error naming the query rather
  than an edge that was quietly not recorded.

  The one escape that guard cannot see is a deferral forced inside
  ANOTHER body's render, where a frame is bound and [[read-key!]] is
  satisfied. Structure is repaired there — `realize-deep` forces it at
  the crossing — and an explicit deferral is REFUSED there, by the same
  walk, because forcing a `delay` would change what the author wrote.
  `front.codec/refuse-deferred!`, and `arm1/deferred-read-cljs-test`.
  rf2-2rtt6.32.

  ### The cold read, and what it costs (clause (a) consequence)

  A render-phase read is a **pure deref** when the key already has a
  committed cell — the overwhelming case, and the one validation.md's
  \"an unchanged hot read performs no new attach/release\" describes. A
  read of a key nothing holds yet computes through `subscribe-once`,
  which subscribes, derefs and unsubscribes inside the calling tick and
  retains nothing, so an abandoned render leaves the world exactly as it
  found it. Acquisition happens at commit, without a deref.

  That costs a cold key its computation twice — once discarded at render,
  once when the commit acquires. The shipping React spine avoids the same
  double build with a render-phase escrow (rf2-2rtt6.25), and this arm
  deliberately does **not** copy it: an escrow is a render-phase
  ref-count mutation, which is the one thing the state machine above
  forbids. It is also not a collector charge — `useSyncExternalStore` has
  the identical render/commit shape, so grouped and the scalar comparator
  inherit it — and it belongs to the shared front half rather than to
  this arm.

  ## The re-render path

      write -> the sub layer's equality cutoff -> key-cell watch -> dirty set
        -> flush: epoch bump + generation bump
        -> front.sub-index/commit! -> dirty boundary set
        -> registration notify (React's onStoreChange)
        -> React re-renders exactly those boundaries
        -> bodies re-run -> hiccup -> front.codec -> React reconciles

  The dirty *sub-key* set is push-cheap: a key is marked only when the
  sub layer's own equality cutoff let a change through. Notifications are
  batched by [[with-commit]], which the arm's frame-locked dispatch wraps
  around every intent, so one user action is one flush however many
  subscriptions it moved.

  ## The commit basis, the fence, and the render→commit gap

  Invariant 5 has two windows, and they are not the same window. One is
  *inside a body* — a commit landing between two of one render's reads.
  The other is the **render→commit gap** — a commit landing after the
  body returned and before React runs the effect that acquires its
  edges. The predecessor guarded the second by re-reading every
  subscription at commit; this arm may not (HD-002), so it needs
  something else there.

  Both windows are judged against one number, [[commit-basis]]:

      commit-basis(frame) = this runtime's flush generation
                          + that frame's own physical-install epoch

  [[generation]] counts flushes. `frame-commit-epoch` is the substrate's
  own counter, bumped once per physical frame-state install at both
  write chokepoints, and Spec 006's observation port already uses it for
  exactly this question — *did the frame's durable state move in the
  render→commit gap?* — because it answers without watching anything.
  That second term is what the runtime was missing, and it is why the
  basis is not just the generation:

  > The generation only moves when `flush!` bumps it, `flush!` only runs
  > from `mark-dirty!`, and `mark-dirty!`'s only caller is the
  > value-change watch [[acquire-cell!]] installs **at commit**. So a
  > key nothing holds yet — no cell, no watch, no epoch — could move
  > without moving the generation by so much as one.

  The two windows then use the basis in the two places they can:

  1. **Inside a body.** [[render-body]] captures the basis before the
     body and checks it after; a commit that landed during the run makes
     the body re-run against the newer one. One comparison per boundary
     rather than one deref per read.
  2. **The render→commit gap.** A key with no cell contributes the
     current basis to [[make-snapshot]], and a cell records the basis it
     was *created* at. So a staged key's number is `basis@render` while
     the boundary renders and `basis@commit` once the commit acquires
     it — equal when nothing moved, different when something did. React
     re-reads `getSnapshot` immediately after calling `subscribe`
     (`updateStoreInstance` runs as the next passive effect) and
     compares it against the snapshot **that fiber** captured at render,
     so the comparison is per boundary, costs one number, and needs no
     record of what any read returned. **A staged read that moves in the
     gap now heals.** rf2-2rtt6.42.

  It is conservative in the safe direction and only there: an install
  that moved nothing this boundary read still moves the basis, so a
  boundary mounting exactly as an unrelated write lands re-renders once.
  A MISSED move would be the P0. Nothing pays for this in steady state —
  a mounted boundary holds a reference to every key it reads, so its
  snapshot has no staged term in it at all.

  A flush raised while a body is running must not call React's
  `onStoreChange` — that is a render-phase update on somebody else's
  component. Those notifications are deferred to a macrotask; the fence
  has already made the *rendering* boundary correct.

  ### The other two axes, and why they are not the basis's to carry

  The predecessor compared three things, and the basis answers one of
  them. The other two — a `:sub` registration (its `:registry-epoch`)
  and a same-id frame reincarnation (its `:node-key`) — move neither
  term, and rf2-2rtt6.44 established that **adding a term for them would
  have closed nothing**. Each of them leaves the cell holding a reaction
  that can no longer answer for its key, so a number that moved would
  have bought exactly one extra render, and that render would have read
  back through the same dead reference. What each does to the cell, and
  what the arm hears when it happens:

  | transition | what the cell is left holding | what announces it |
  |---|---|---|
  | `:sub` **re-registration** | a disposed container — the sub-cache evicted the entry, and `-dispose` cleared the watcher set, so the cell is deaf from that instant and answers the RETIRED computation on every later deref | the reaction's own disposal |
  | **frame destruction** (incl. a same-id reincarnation) | a container wired to the frame that no longer exists, answering the destroyed incarnation's db | the reaction's own disposal |
  | `:sub` **first registration** | the substrate's uncached nil-recovery, which was never wired to anything and can never see the handler that has now arrived | nothing — so [[first-registration!]] listens for the registration itself |

  So the arm takes the substrate's own events instead of a term.
  [[invalidate-cell!]] is the one repair all three reach:
  [[wire-cell!]] arms it per unique key against the reaction's disposal,
  which covers the two transitions that dispose; the third disposes
  nothing — `registrar/add-replacement-hook!` fires only when a previous
  handler existed — so the arm hangs it off
  `registrar/add-registration-hook!`, narrowed to first-time `:sub`
  registrations and to the cells that hold the id being registered. It
  costs no React hook, no per-boundary object, nothing in the snapshot
  arithmetic, and no read of the registry's own epoch — the epoch-sum
  `getSnapshot` is untouched, and an unrelated registration moves no
  boundary's snapshot. rf2-2rtt6.44 records the costing that rejected
  the alternative.

  ## What is deliberately NOT here (the hard fences)

  No compiler and no analyzer: bodies are ordinary functions and hiccup
  is interpreted by the shared codec at runtime. No dual mode: one shell,
  one index, one read path — the two HD-002 tiers differ in *where the
  author writes the read*, not in the machinery underneath. No
  ViewCell-class object graph. No candidate ledger. Codec caching is the
  only accelerant (HD-004); nothing here holds a node reference, plans a
  hole, or writes the DOM."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.front.sub-index :as index]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn- fail! [id where reason recovery extra]
  (throw (ex-info (str reason " [" id "]")
                  (merge {:rf.error/id id :where where
                          :reason reason :recovery recovery}
                         extra))))

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

(def ^:private ^js rstate
  "The render slots: the frame the running body resolved (nil outside a
  render, which is what makes `(sub …)` outside a boundary a loud error
  rather than a silent read of whichever frame happened to be ambient),
  the two read-surface provenance flags, and the entry the last body
  resolved. One JS object for the whole runtime — not one per render."
  #js {"frame" nil "collector" false "grouped" false "entry" nil})

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
;; Frame-locked ops, resolved once per frame and not once per boundary
;; ---------------------------------------------------------------------------

(defonce ^:private !frame-ops (atom {}))
(defonce ^:private !frame-dispatch (atom {}))

(declare dispatch!)

(defn frame-ops
  "The frame-locked op bundle for `frame-kw` — `rf/capture-frame`'s
  `{:frame :dispatch :dispatch-sync :subscribe}` — memoised per frame.

  HD-020(a) has each boundary read the frame *once* from the substrate's
  single internal context; it does not ask each boundary to rebuild the
  bundle. `capture-frame` pins a frame incarnation and is not free, so
  the shell's cost here is one map lookup per render and no allocation."
  [frame-kw]
  (or (get @!frame-ops frame-kw)
      (let [ops (rf/capture-frame frame-kw)]
        (swap! !frame-ops assoc frame-kw ops)
        ops)))

(defn- frame-dispatch
  "The ambient dispatch a boundary binds for its render's dynamic extent
  (HD-020(a)), memoised per frame so binding it allocates nothing."
  [frame-kw]
  (or (get @!frame-dispatch frame-kw)
      (let [f (fn dispatch-for-frame [event] (dispatch! frame-kw event))]
        (swap! !frame-dispatch assoc frame-kw f)
        f)))

(defn forget-frame-ops!
  "Drop the memoised bundles. A destroyed and re-created frame is a new
  incarnation, and a captured bundle is pinned to the old one."
  ([] (reset! !frame-ops {}) (reset! !frame-dispatch {}) nil)
  ([frame-kw] (swap! !frame-ops dissoc frame-kw)
              (swap! !frame-dispatch dissoc frame-kw)
              nil))

;; ---------------------------------------------------------------------------
;; Key cells — one per unique (frame, query), shared by every reader,
;; created and acquired ONLY at commit
;; ---------------------------------------------------------------------------
;;
;; The sub-key the index sees is `[frame-kw query-v]`. validation.md pins
;; sub-key identity as `(query-id, args)` under value equality; qualifying
;; it by frame is strictly finer, and it is the honest key for a runtime
;; in which two frames are isolated contexts holding two different
;; app-dbs. The pair is a value, so every index law reads it exactly as it
;; reads a bare query vector.
;;
;; Cells are plain JS objects rather than a deftype on purpose: this is
;; the object the heap ladder prices per unique key, and a deftype would
;; add a constructor and a prototype to a structure with no behaviour.

(defonce ^:private !cells (atom {}))
(defonce ^:private !dirty (volatile! #{}))
(defonce ^:private !batching (volatile! false))
(defonce ^:private !generation (volatile! 0))
(defonce ^:private !deferred (volatile! #{}))
(defonce ^:private !watch-counter (volatile! 0))

(defn generation
  "The commit generation. Bumped once per flush that moved something."
  []
  @!generation)

(defn commit-basis
  "**The number both invariant-5 windows are judged against** — this
  runtime's flush [[generation]] plus `frame`'s own physical-install
  epoch (`re-frame.frame/frame-commit-epoch`, the substrate's
  observation-port evidence counter, bumped once per frame-state install
  at both write chokepoints).

  The generation alone cannot carry it, and the reason is structural
  rather than a matter of degree: the generation moves only through
  `mark-dirty!`, whose only caller is the value-change watch
  [[acquire-cell!]] installs **at commit**, so a key nothing holds yet
  can move without moving it. The frame's install epoch has no such
  dependency — it is a counter read, not a watch — which is exactly why
  Spec 006's observation port uses it to ask whether durable state moved
  in the render→commit gap.

  Both terms are monotone within a frame incarnation, so the basis is,
  and so is any sum of bases and cell epochs. Deliberately
  install-counting rather than `=`-counting: a value-equal install still
  advances it, which costs at most one redundant re-render and cannot
  cost a missed one. Pure read; allocates nothing.

  Silent on two axes, and permanently so — no `:sub` registration is a
  frame-state install, and a same-id frame reincarnation RESTARTS
  `frame-commit-epoch` at 0 (measured: A's epoch and B's are both 1, so
  the basis TIES across the reincarnation), which is the case the
  observation port needs its `:node-key` field for. **Neither axis is
  this number's to carry**, and rf2-2rtt6.44 settled why rather than
  extending it: every one of those transitions leaves the cell holding a
  reaction that can no longer answer for its key, so a moved number
  would only buy a re-render that read back through the same dead
  reference. [[invalidate-cell!]] carries all three, reached from the
  substrate's own disposal for the two that dispose (a re-registration,
  a frame teardown) and from [[first-registration!]] for the one that
  does not."
  [frame-kw]
  (+ @!generation (frame/frame-commit-epoch frame-kw)))

(declare flush!)

(defn- mark-dirty! [^js cell]
  (when-not (.-disposed cell)
    (vswap! !dirty conj cell)
    (when-not @!batching (flush!))))

(defn- dispose-cell! [^js cell]
  (when-not (.-disposed cell)
    (set! (.-disposed cell) true)
    (when-some [r (.-reaction cell)] (remove-watch r (.-watchKey cell)))
    (swap! !cells dissoc (.-subKey cell))
    (subs/unsubscribe (.-frameKw cell) (.-queryV cell)))
  nil)

(defn- arm-cell-reaper!
  "A cell whose last reader unmounts is given one macrotask of grace, so
  a keyed reorder that unmounts and remounts a row within one turn reuses
  the reaction instead of rebuilding it."
  [^js cell]
  (js/setTimeout (fn [] (when (and (zero? (.-refs cell)) (not (.-disposed cell)))
                          (dispose-cell! cell)))
                 0)
  nil)

(declare invalidate-cell!)

(defn- wire-cell!
  "Give `cell` a live subscription: subscribe, establish the baseline,
  arm the value-change watch, and arm the **disposal** hook. The whole of
  a cell's attachment to the substrate, in one place, because it is
  performed twice — once when the cell is born and once when the
  substrate disposes the reaction out from under it.

  The disposal hook is the arm's counterpart to the two axes
  `commit-basis` cannot see (rf2-2rtt6.44), and it is deliberately an
  *event* rather than a term in the epoch sum: the substrate already
  tells us, exactly and only when it happens, and a term would have to be
  read by every key on every snapshot to discover the same thing later.
  It covers every transition that *disposes* — which is all of them but
  one, and [[first-registration!]] carries the one it is not: a first
  registration announces itself by registering, and disposes nothing."
  [^js cell]
  (let [frame-kw (.-frameKw cell)
        query-v  (.-queryV cell)
        wk       (.-watchKey cell)
        reaction (subs/subscribe query-v {:frame frame-kw})]
    (set! (.-reaction cell) reaction)
    (when (some? reaction)
      ;; ONE baseline deref, before the watch — see `acquire-cell!`.
      @reaction
      (add-watch reaction wk
                 (fn [_ _ old nu] (when-not (= old nu) (mark-dirty! cell))))
      (interop/add-on-dispose! reaction (fn [] (invalidate-cell! cell))))
    cell))

(defn- invalidate-cell!
  "**The repair for a cell whose reaction can no longer answer for its
  key** (rf2-2rtt6.44). A cell holds its reaction for the life of every
  boundary that reads the key — that is what makes a warm read a pure
  deref — and three substrate transitions retire what it is holding:

  1. a `:sub` **re-registration**, which evicts the query's sub-cache
     entry and disposes the reaction (`subs.cache/invalidate-sub-on-replace!`);
  2. a **frame destruction**, whose teardown disposes the frame's cached
     reactions — including across a same-id reincarnation;
  3. a `:sub` **first registration**, which disposes nothing at all and
     reaches here through [[first-registration!]] instead.

  The first two leave the cell holding a container whose `-dispose` has
  already run `(reset! watchers {})`, so the watch this arm installed is
  gone and `mark-dirty!` can never fire for that key again. Measured,
  before this hook existed: the boundary read the RETIRED computation
  forever and no later write notified it. That is why neither axis was
  ever closable by adding a term to `getSnapshot` — the extra render a
  moved number buys reads back through the same dead cell. The third
  leaves it holding the substrate's nil-recovery, which was never wired
  to anything, and is deaf for the same reason.

  Two phases, and the split is what keeps this out of both re-entrant
  windows. **Synchronously** the reaction reference is dropped, which is
  all a correct read needs: [[read-key!]] treats a cell with no reaction
  exactly as it treats a key with no cell and goes through
  `subscribe-once`, so every render from this instant on computes against
  the new registration and the live frame. **On a macrotask** the durable
  attachment is rebuilt, so later writes notify again — deferred because
  this callback runs inside the registrar's replacement hook, inside its
  registration hook and inside frame teardown, and none of them is a
  place to subscribe. A frame that did not come back has nothing to
  rebuild against, and the cell is disposed instead."
  [^js cell]
  (when-not (.-disposed cell)
    (set! (.-reaction cell) nil)
    (js/setTimeout
      (fn []
        (when-not (.-disposed cell)
          (if (nil? (frame/frame-incarnation-token (.-frameKw cell)))
            (dispose-cell! cell)
            (do (wire-cell! cell)
                ;; Re-stamp and notify: a boundary that painted before the
                ;; disposal painted the retired computation, and this is the
                ;; commit that corrects it.
                (mark-dirty! cell)))))
      0))
  nil)

(defn- first-registration!
  "**The registry transition no disposal announces** (rf2-2rtt6.44
  audit follow-up). `registrar/add-replacement-hook!` — the hook the
  sub-cache eviction that [[invalidate-cell!]] rides is built on — fires
  only when a previous handler existed. A *first* `reg-sub` for a query
  therefore evicts nothing, disposes nothing, and would reach an arm that
  listened for disposals alone by no route whatsoever.

  It has something to reach because a boundary can hold the miss. The
  substrate is careful that it should not: a subscribe to an unregistered
  query emits `:rf.error/no-such-sub`, recovers to a nil-yielding
  reaction, and **deliberately does not cache it**, precisely so that a
  later registration is observed by the next `subscribe`
  (`subs/build-and-cache!*`). This arm has exactly one property that
  breaks that assumption — a cell holds its reaction for the life of
  every boundary reading the key, and never subscribes again — so the
  recovery the substrate declined to cache was cached anyway, in a cell,
  where nothing evicted it. Measured before this: the boundary painted
  nil for the life of the mount and no later write notified it, on a
  query that was by then registered.

  So the repair is to restore the substrate's assumption rather than to
  keep the recovery honest: the same [[invalidate-cell!]] the disposal
  path uses, off `registrar/add-registration-hook!` — the public sibling
  that fires on first-time *and* re-registration. Narrowed to the
  first-time case (`:was` nil), because the re-registration case already
  arrives as a disposal and doing it twice would rebuild one attachment
  twice; and to the cells still holding a reaction, because a cell
  already mid-rebuild has dropped its reference and is about to subscribe
  against this very registration.

  **Not the rejected registry term, and the difference is the whole
  costing.** That term sat in every key's contribution to
  `getSnapshot`, so every mounted boundary in the application re-rendered
  on every `reg-sub` — and read back through a dead cell when it did.
  This reaches the cells that hold the id being registered and nothing
  else: an unrelated first registration moves no snapshot, notifies no
  boundary, and rebuilds no attachment.

  The scan is `@!cells`, on first-time `:sub` registrations only. Those
  are namespace-load and lazy-module-load events — an HMR save
  re-registers, so its ids take the `:was`-non-nil branch and never get
  here — and at namespace load there are no cells to scan."
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

;; Arm it once per process, at load. `defonce` is the arming, so the var
;; exists for its side effect and is deliberately never read — the hook
;; vector is the only thing that holds the fn. The hook is the substrate's
;; own extension point and the arm installs nothing else global; it costs
;; a keyword compare and a nil check on every registration of any kind,
;; and the scan above only on a first-time `:sub`.
#_:clj-kondo/ignore
(defonce ^:private first-registration-armed
  (do (registrar/add-registration-hook! first-registration!) true))

(defn- acquire-cell!
  "**Commit-phase only.** Take (building if necessary) the durable
  reference for `sub-key`, and attach the one watch that turns the sub
  layer's equality cutoff into this arm's dirty set. Acquire without
  deref: the render already knows the value."
  [sub-key]
  (let [^js cell (or (get @!cells sub-key)
                 (let [frame-kw (nth sub-key 0)
                       query-v  (nth sub-key 1)
                       wk       (keyword "rf-hicasso-arm1"
                                         (str "w" (vswap! !watch-counter inc)))
                       ^js fresh #js {"subKey"   sub-key
                                     "frameKw"  frame-kw
                                     "queryV"   query-v
                                     "reaction" nil
                                     "watchKey" wk
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
                                     ;; the boundary. rf2-2rtt6.42.
                                     "epoch"    (commit-basis frame-kw)
                                     "refs"     0
                                     "disposed" false}]
                   ;; ONE baseline deref, at construction, before the watch.
                   ;;
                   ;; HD-002's adjudication sketches commit-phase
                   ;; acquisition as "acquire without deref: the value is
                   ;; already known from the render". Against this
                   ;; substrate that is not implementable, and the failure
                   ;; is silent: a derived value starts at an `unset`
                   ;; baseline that is never `rf=` a real value, and the
                   ;; render's own read went through `subscribe-once`,
                   ;; which built a DIFFERENT reaction and disposed it. So
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
                   ;; the layer below because this arm uses the
                   ;; notification ITSELF as the dirty signal: the shipping
                   ;; spine can tolerate a notification that did not move,
                   ;; since `useSyncExternalStore` re-compares snapshots
                   ;; after it, and this arm cannot.
                   ;;
                   ;; [[wire-cell!]] performs that deref, that watch and the
                   ;; disposal hook, because a cell is attached to the
                   ;; substrate twice — here, and again if the substrate
                   ;; disposes the reaction underneath it.
                   (wire-cell! fresh)
                   (swap! !cells assoc sub-key fresh)
                   fresh))]
    (set! (.-refs cell) (inc (.-refs cell)))
    cell))

(defn- release-cell! [^js cell]
  (set! (.-refs cell) (dec (.-refs cell)))
  (when (<= (.-refs cell) 0) (arm-cell-reaper! cell))
  nil)

;; ---------------------------------------------------------------------------
;; The commit — the only door through which a write becomes re-render work
;; ---------------------------------------------------------------------------

(defn- notify! [registrations]
  (doseq [^js r registrations]
    (when-some [n (.-notify r)] (n))))

(defn flush!
  "Turn the dirty sub-key set into re-render work: bump each dirty key's
  epoch, bump the generation, ask the index which boundaries read those
  keys, and hand each one React's own `onStoreChange`.

  Notifications are deferred to a macrotask when a body is running: an
  `onStoreChange` fired from inside somebody's render is a render-phase
  update on another component, which React rejects — and which the
  generation fence has already made unnecessary for the *rendering*
  boundary."
  []
  (let [dirty @!dirty]
    (when (seq dirty)
      (vreset! !dirty #{})
      (vswap! !generation inc)
      ;; Re-STAMP rather than increment, so a cell's epoch is always a
      ;; `commit-basis` reading and never drifts into a private
      ;; numbering the staged term could not be compared against. It
      ;; still strictly increases: the generation was just bumped and
      ;; the frame's install epoch never falls, so the new stamp is
      ;; above the one this cell last carried.
      (doseq [^js c dirty] (set! (.-epoch c) (commit-basis (.-frameKw c))))
      (let [boundaries (index/commit! (into #{} (map (fn [^js c] (.-subKey c))) dirty))]
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
  "The arm's frame-locked dispatch — HD-019's synchronous door. The event
  drains synchronously inside the caller's turn (the discrete browser
  event, for an intent) and the store notification runs before that turn
  ends, so React commits the echo in the same turn."
  [frame-kw event]
  (with-commit (fn [] ((:dispatch-sync (frame-ops frame-kw)) event)))
  nil)

;; ---------------------------------------------------------------------------
;; The two read tiers (HD-002) — the render phase mutates nothing global
;; ---------------------------------------------------------------------------

(defn- read-key!
  "One read: append the sub-key to the scratch, and return the value.

  Warm — a committed cell holds the key — is a pure deref: no acquire,
  no release, nothing global touched. Cold is `subscribe-once`, which
  subscribes, derefs and unsubscribes inside this tick and retains
  nothing, so an abandoned render leaves the world as it found it.

  A cell [[invalidate-cell!]] has dropped the reaction of takes the cold
  path too, and that is the whole of what an invalidated read needs to be
  correct: `subscribe-once` computes against the registration and the
  frame incarnation that are live NOW, so a render in the window between
  the invalidation and its rebuild reads the new computation rather than
  the retired one — or, for a key registered for the FIRST time while the
  boundary was mounted, the real handler rather than the nil-recovery.
  rf2-2rtt6.44."
  [query-v]
  (when (nil? (.-frame rstate))
    (fail! :rf.error/hicasso-sub-outside-render
           're-frame.bench.hicasso.arm1.runtime/read-key!
           (str "A subscription read " (pr-str query-v)
                " happened outside a boundary render. `sub` and `use-subs` "
                "are legal only inside a defview body; `subscribe-once` is "
                "the sanctioned snapshot for handler and utility code.")
           :read-inside-a-boundary-body
           {:query-v query-v}))
  (let [frame-kw (.-frame rstate)
        sub-key  [frame-kw query-v]]
    (.push scratch sub-key)
    (if-some [^js r (some-> ^js (get @!cells sub-key) (.-reaction))]
      @r
      (subs/subscribe-once query-v {:frame frame-kw}))))

(defn sub
  "**The ambient collector** — the surface the operator ruled the only
  acceptable one (2026-07-31). A plain function call, legal anywhere in a
  body: inside a `when`, inside a `for`, inside an inlined helper. The
  edge is *recorded* where the read happens, and the recorded set is what
  the commit installs — so a branch not taken contributes no edge."
  [query-v]
  (set! (.-collector rstate) true)
  (read-key! query-v))

(defn use-subs
  "**Grouped — the control.** One fixed site receiving the complete query
  collection, returning the snapshot the body destructures. Its edges are
  *declared*: they are the map's values, so a boundary's edge set is a
  function of its declaration and not of its control flow, and a branch
  not taken still costs its edge.

      (let [{:keys [todo editing?]}
            (use-subs {:todo     [:todo/by-id id]
                       :editing? [:todo.ui/editing? id]})]
        …)

  Kept, and kept working, because the three-rendering dogfood judgement
  needs it and because it is the surface the collector is measured
  against — not because it is being defended. The operator ruled it below
  the ergonomics bar."
  [query-map]
  (set! (.-grouped rstate) true)
  (reduce-kv (fn [m alias query-v] (assoc m alias (read-key! query-v)))
             {}
             query-map))

;; ---------------------------------------------------------------------------
;; Read-set entries — the cached subscribe/getSnapshot pair, and the
;; zero-allocation detection of the unchanged case
;; ---------------------------------------------------------------------------

(defonce ^:private !entries
  ;; read-sequence hash -> vector of entries. See [[scratch-bucket-key]]
  ;; for why the key is a hash of the WHOLE sequence and not, as it once
  ;; was, the first sub-key.
  (atom {}))

(defn- scratch-bucket-key
  "The bucket the scratch's current read sequence belongs to: an
  **order-sensitive hash of the whole sequence**.

  It selects a bucket and is never an equality test — [[entry-matches?]]
  still compares every key pairwise before an entry is reused. That
  division is the whole safety argument, and it is why this is not the
  content hash the design record rejected: a hash *instead of* the
  compare could hand back an entry for a different read set, which is a
  silently missing edge; a hash *in front of* the compare can only send
  two different sequences to one bucket, where the compare rejects one of
  them and the caller mints a second entry. False negatives only, in both
  directions.

  It costs nothing measurable. Every sub-key on the scratch has already
  been hashed this render — [[read-key!]] looks it up in `!cells` before
  it returns — so this is `n` cached-hash reads and `n` integer ops, with
  no allocation, which is what keeps the steady-state hit path at zero
  bytes.

  **Why the first sub-key was not enough (rf2-2rtt6.46).** Bucketing on
  `(aget scratch 0)` made the scan's cost a function of how an author
  ordered their `let` bindings. A row body reading its per-row key first
  put one entry in each bucket; the same body reading a page-wide key
  first — one line moved — put every live row's entry in ONE bucket, and
  every probe then passed the length test and the index-0 test and failed
  only at the last key. Mounting N such rows cost `sum(i)` probes, and
  N = 300 is a rung this programme benchmarks. Same page, same edges,
  same DOM, ~150x the entry-lookup work. Hashing the whole sequence makes
  the bucket a function of the read set rather than of its first element,
  and `the-bucket-scan-does-not-grow-with-the-number-of-boundaries`
  holds it there."
  []
  (let [n (alength scratch)]
    (loop [i 0 h 1]
      (if (== i n)
        h
        (recur (inc i)
               ;; h*31 + hash(k), truncated to int32 — order-sensitive,
               ;; allocation-free, and the arithmetic is JS-exact.
               (bit-or 0 (+ (bit-shift-left h 5) (- h) (hash (aget scratch i)))))))))

(defn- drop-entry! [^js entry]
  (let [bucket-key (.-bucketKey entry)]
    (swap! !entries
           (fn [m]
             (let [left (vec (remove #(identical? % entry) (get m bucket-key)))]
               (if (seq left) (assoc m bucket-key left) (dissoc m bucket-key)))))
    nil))

(defn- arm-entry-reaper!
  "An entry with no committed boundary is dropped one macrotask later.
  Two callers, one rule: an entry a discarded render minted was never
  claimed, and an entry whose last boundary unmounted is no longer
  anybody's. Both are cache eviction and neither is a record of something
  to undo."
  [^js entry]
  (js/setTimeout (fn [] (when (zero? (.-refs entry)) (drop-entry! entry))) 0)
  nil)

(defn- entry-matches?
  "Ordered pairwise compare of an entry's key array against the scratch.
  Allocates nothing. A false negative — same set, different order — costs
  a second entry and a symmetric difference that removes and re-adds the
  same edges; it is never a wrong answer, which is why the hash in
  [[scratch-bucket-key]] chooses the bucket and this decides the match."
  [^js entry]
  (let [ks (.-keys entry)
        n  (alength ks)]
    (and (== n (alength scratch))
         (loop [i 0]
           (cond
             (== i n)                          true
             (= (aget ks i) (aget scratch i))  (recur (inc i))
             :else                             false)))))

(declare make-subscribe make-snapshot)

(defn- entry-for
  "The read-set entry for the scratch's current contents — the cached
  `subscribe` / `getSnapshot` pair React sees. A hit allocates nothing and
  keeps `subscribe`'s identity, so React does not re-subscribe and the
  commit does no work; a miss materialises the key array, the key set and
  the two closures **once**, for every boundary that will ever read that
  set.

  The bucket is [[scratch-bucket-key]]'s hash of the whole read sequence,
  so what a lookup scans is the set of read sequences that COLLIDE — not,
  as it once was, the set of live boundaries that happen to share a first
  key. `drop-entry!`'s rebuild of the bucket vector is O(1) for the same
  reason."
  []
  (let [bucket-key (scratch-bucket-key)
        bucket     (get @!entries bucket-key)]
    (or (some (fn [^js e] (when (entry-matches? e) e)) bucket)
        (let [ks    (.slice scratch)
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
  cell holds yet** contributes its frame's current [[commit-basis]]
  instead of nothing. Monotone, so `Object.is` on it is a correct change
  test; cached on the entry, so a render allocates no closure for it.

  That one term is what reaches the render→commit gap. A staged key
  contributes `basis@render` while the boundary renders and, once the
  commit has created its cell, `basis@commit` — the same number when
  nothing installed in between and a different one when something did.
  React re-reads this closure immediately after `subscribe` returns
  (`updateStoreInstance` is the next passive effect) and compares
  against the value **that fiber** captured at render, so the tear check
  is per boundary, is one number, and holds no record of what any read
  returned. Returning 0 there — which is what a key with no epoch used
  to contribute — meant a staged key answered the same number before and
  after the commit however far its value had moved, so React saw no tear
  and scheduled no re-render, and nothing ever corrected the boundary.
  rf2-2rtt6.42.

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
                     (+ acc (commit-basis (nth k 0)))))))))))

(defn- make-subscribe
  "React's `subscribe`, as a pure function of the read set.

  **The only global mutation in the state machine.** The boundary's
  registration is minted from React's own `onStoreChange`, the index
  learns the boundary and its edges, and each key takes its committed
  reference. The returned cleanup is the exact inverse and is the only
  place edges and references are released, so teardown is symmetric with
  mount whatever React did with the renders in between.

  The registration holds exactly the cells it acquired, so its cleanup
  cannot release a successor's after a reap and rebuild.

  **The registration is also the boundary id, and that is what makes the
  index's edge diff degenerate here** — a fresh id every time, so
  `record-reads!` always adds the whole read set against an empty held
  set and never drops anything. A read-set change is this cleanup
  followed by a fresh call to a different entry's `subscribe`: the
  edge-set replacement in full, done by the pair rather than by a
  difference. See the ns docstring, clause (b)."
  [^js entry]
  (fn subscribe [on-store-change]
    (let [reads (.-set entry)
          ^js reg #js {"reads" reads "notify" on-store-change}
          cells (mapv acquire-cell! reads)]
      (unchecked-set reg "cells" cells)
      (index/mount! reg)
      (index/record-reads! reg reads)
      (set! (.-refs entry) (inc (.-refs entry)))
      (fn unsubscribe []
        (set! (.-notify reg) nil)
        (index/unmount! reg)
        (doseq [cell cells] (release-cell! cell))
        (set! (.-refs entry) (dec (.-refs entry)))
        (when (<= (.-refs entry) 0) (arm-entry-reaper! entry))
        nil))))

(defn commit-boundary!
  "**The seam React occupies.** Hand a boundary's read set and a notifier
  to the same `subscribe` closure `useSyncExternalStore` would call, and
  get back the same cleanup React would hold.

  It exists because the commit path, the index wiring and the
  zero-leaked-reference assertion are the parts of this arm most worth
  proving cheaply and deterministically, and every one of them is
  answerable without a browser, a root, or a render. The DOM suites then
  prove that React drives *this* seam rather than re-proving what the
  seam does."
  [^js entry notify]
  ((.-subscribe entry) notify))

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
  "One body run. The scratch and both provenance flags are reset
  **unconditionally** — a reset guarded by \"if empty\" would concatenate
  two renders' reads, which is precisely what makes StrictMode's
  double-invoke correct here rather than additive."
  [frame-kw body-fn props]
  (set! (.-length scratch) 0)
  (set! (.-collector rstate) false)
  (set! (.-grouped rstate) false)
  (set! (.-frame rstate) frame-kw)
  (try
    (intent/with-frame frame-kw (frame-dispatch frame-kw)
      (fn [] (codec/as-element (body-fn props))))
    (finally (set! (.-frame rstate) nil))))

(defn render-body
  "Run a boundary body under the generation fence and return its element;
  [[last-reads]] carries the read-set entry.

  The fence is the loop: capture the [[commit-basis]], run the body, and
  if a commit landed while it ran, run it again against the newer
  commit. All of a pass's reads therefore observe one commit —
  invariant-5 preservation as one comparison per boundary, not one deref
  per read.

  It compares the basis rather than the generation alone because the
  generation cannot see a mid-body move of a key nothing holds: no cell,
  so no watch, so no `mark-dirty!`, so no bump. A body that read a
  staged key, dispatched, and read again could straddle two commits with
  the generation sitting perfectly still. The frame's install epoch
  moves for that write, so the basis does. rf2-2rtt6.42."
  [frame-kw body-fn props]
  (loop [attempt 0]
    (let [before  (commit-basis frame-kw)
          element (run-once frame-kw body-fn props)]
      (cond
        (= before (commit-basis frame-kw))
        (do (set! (.-entry rstate) (entry-for)) element)

        (< attempt max-fence-retries)
        (recur (inc attempt))

        :else
        (fail! :rf.error/hicasso-generation-fence-exhausted
               're-frame.bench.hicasso.arm1.runtime/render-body
               (str "A boundary body observed a new commit on each of "
                    (inc max-fence-retries) " consecutive runs. A body that "
                    "writes on every render cannot be fenced; move the write "
                    "out of the render.")
               :move-the-write-out-of-the-render
               {:frame frame-kw :generation (generation)})))))

(defn last-reads
  "The read-set entry the most recent [[render-body]] resolved."
  []
  (.-entry rstate))

(defn reads-of
  "An entry's sub-key set."
  [^js entry]
  (.-set entry))

(defn snapshot-of
  "The number React stores for a boundary with this read set, and the
  one it re-reads after `subscribe` to decide whether the store moved
  under the render. Reading it is the witness's way of performing
  React's own `checkIfSnapshotChanged` without a browser."
  [^js entry]
  ((.-snapshot entry)))

(defn last-tiers
  "Which read surfaces the most recent body used. The instrument's input,
  and the reason a rendering's tier is a measured property rather than a
  claim in a docstring."
  []
  {:collector? (.-collector rstate) :grouped? (.-grouped rstate)})

;; ---------------------------------------------------------------------------
;; The shell — exactly two React hooks, and no useRef
;; ---------------------------------------------------------------------------

(def shell-hook-ledger
  "The shell's declared hook calls, in call order. The dispatcher-level
  witness counts against this, so a third hook appearing in the shell
  fails a test rather than a review."
  [:use-context/frame :use-sync-external-store/subscription-epoch])

(defn- resolve-frame! [frame-kw]
  (if (or (nil? frame-kw) (= adapter-context/no-provider-sentinel frame-kw))
    (fail! :rf.error/no-frame-context
           're-frame.bench.hicasso.arm1.runtime/shell
           (str "A Hicasso boundary rendered with no frame in scope. Mount the "
                "tree under a frame boundary — `arm1.mount/root!` installs one.")
           :mount-under-a-frame
           {})
    frame-kw))

(defn shell
  "The boundary shell. Two hooks, with the body between them — which is
  legal because what React fixes is hook *order and count*, not the
  position of ordinary code around them, and it is what lets the
  subscription hook close over the reads the body just made."
  [body-fn js-props]
  (let [frame-kw (resolve-frame! (react/useContext adapter-context/frame-context))
        props    (or (unchecked-get js-props "rfProps") {})
        element  (render-body frame-kw body-fn props)
        ^js entry (.-entry rstate)]
    (react/useSyncExternalStore (.-subscribe entry) (.-snapshot entry) (.-snapshot entry))
    element))

(defn mint-view!
  "Turn a body fn into a boundary: a React function component, marked as a
  legal hiccup head. Minted once, at definition — which is why the codec's
  third HD-004 cache (stable component heads) has nothing to do in this
  arm, and why HD-016 can make a plain function in head position a loud
  error instead of auto-wrapping it."
  [view-name body-fn]
  (let [component (fn hicasso-boundary [js-props] (shell body-fn js-props))]
    (unchecked-set component "displayName" view-name)
    (codec/mark-boundary! component)))

;; ---------------------------------------------------------------------------
;; Retained inventory — honest accounting, not a claimed absence
;; ---------------------------------------------------------------------------

(defn retained-inventory
  "Every boundary-exclusive token this arm retains, enumerated rather than
  asserted (architecture.md, Arm 1). `:shared` names what is emphatically
  *not* per boundary, because that is the half a heap ladder reads wrong
  if nobody writes it down.

  **The classification is the ladder's input, so where a token sits is a
  measurement and not a filing convenience (rf2-2rtt6.46).** The read-set
  entry sat under `:shared` and does not belong there: an entry is shared
  only between boundaries whose read SEQUENCES are identical, and the
  shape the per-read heap ladder is taken on (rf2-2rtt6.34) is the
  distinct-query one — every row reading its own key, so every row a read
  sequence of its own and an entry of its own. Filed as `:shared` it
  under-counted per-boundary retention by one entry per boundary, on
  exactly the rung being measured, in the direction that flatters this
  arm. Filed here it over-counts in the coincident-sequence case, which
  is the direction a candidate's own instrument should err in."
  []
  {:per-boundary
   [{:token :registration
     :what  "one JS object: the read set (shared with its entry), React's onStoreChange, and the acquired cell vector"}
    {:token :index/live
     :what  "one membership in the index's :live set"}
    {:token :index/b->subs
     :what  "one map entry holding this boundary's read set"}
    {:token :index/sub->bs
     :what  "one membership per edge in each read key's reader set"}
    {:token :react/use-sync-external-store
     :what  "React's own hook cell for the one subscription hook"}
    {:token :react/use-context
     :what  "React's own hook cell for the frame hook"}
    {:token :read-set-entry
     :what  "one key array, key SET, subscribe and getSnapshot per distinct read SEQUENCE — shared ONLY with boundaries whose sequence is identical, so on the distinct-query rung the ladder is taken on there is one per boundary"}]
   :shared
   [{:token :key-cell
     :what  "one cell + one sub-cache reaction per unique (frame, query), however many boundaries read it"}
    {:token :scratch
     :what  "ONE module-level array for the whole runtime, reset by overwrite; its capacity is the high-water read count"}
    {:token :frame-ops
     :what  "one capture-frame bundle and one ambient dispatch per frame"}
    {:token :codec-cache
     :what  "the codec's tag and prop caches, per distinct literal in the build"}]
   :absent
   [{:token :use-ref   :what "no useRef anywhere in the shell (HD-020(b))"}
    {:token :use-state :what "no per-instance render-phase state of any kind"}
    {:token :view-cell :what "no per-boundary object graph, reaction, watcher or scheduler"}
    {:token :candidate-ledger
     :what  "one scratch buffer, never two; nothing keyed by render, attempt, lane or generation; no per-read object; no commit-phase deref"}]})

(defn cell-reaction
  "The subscription `sub-key`'s cell currently derives through, or nil —
  either because nothing holds the key, or because the substrate disposed
  the reaction and [[invalidate-cell!]] dropped the reference.

  A witness reader, and the one the rf2-2rtt6.44 rows need: the failure
  they pin is what a HELD container answers after its disposal, so they
  have to be able to hold it."
  [sub-key]
  (some-> ^js (get @!cells sub-key) (.-reaction)))

(defn stats
  "What the witnesses read: live cells, live boundaries, cached read-set
  entries, the generation, and the codec caches."
  []
  (let [idx (index/snapshot)]
    {:cells      (count @!cells)
     :cell-refs  (reduce-kv (fn [acc _ ^js c] (+ acc (.-refs c))) 0 @!cells)
     :boundaries (count (:live idx))
     :edges      (reduce + 0 (map (fn [[_ v]] (count v)) (:b->subs idx)))
     :entries    (reduce + 0 (map (fn [[_ v]] (count v)) @!entries))
     :generation (generation)
     :frames     (count @!frame-ops)
     :codec      (codec/cache-sizes)}))

(defn entry-buckets
  "The read-set entry cache's bucket occupancy — `{:buckets n :max-bucket
  m}`, where `:max-bucket` is the number of entries a lookup compares
  against in the worst case.

  The scan's cost is `:max-bucket`, and the point of hashing the whole
  read sequence is that it stays put while the number of live boundaries
  grows. Computed on demand from the cache, so nothing on the hot path
  counts anything. rf2-2rtt6.46."
  []
  (let [sizes (map (fn [[_ v]] (count v)) @!entries)]
    {:buckets (count sizes) :max-bucket (reduce max 0 sizes)}))

(defn residue
  "What must be zero after a clean teardown. `:cell-refs` is the standing
  zero-leaked-subscription-ref-counts assertion; `:boundaries` and
  `:edges` are the index's half of it."
  []
  (select-keys (stats) [:cells :cell-refs :boundaries :edges :entries]))

(defn reset-runtime!
  "Drop every cell, every edge, every cached entry and every frame bundle.
  The root-teardown and test-fixture door; disposing each cell releases
  its sub-cache reference, so this is the leak check's reset rather than
  a way to hide one."
  []
  (doseq [[_ cell] @!cells] (dispose-cell! cell))
  (reset! !cells {})
  (reset! !entries {})
  (vreset! !dirty #{})
  (vreset! !deferred #{})
  (vreset! !batching false)
  (vreset! !generation 0)
  (set! (.-entry rstate) nil)
  (set! (.-frame rstate) nil)
  (set! (.-length scratch) 0)
  (index/reset-index!)
  (forget-frame-ops!)
  nil)
