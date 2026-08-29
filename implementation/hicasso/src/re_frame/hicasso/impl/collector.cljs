(ns re-frame.hicasso.impl.collector
  "HICASSO — THE COLLECTOR. The runtime's heart.

  A boundary is a real React function component minted by `defview`
  (`re-frame.hicasso`). React owns identity,
  reconciliation, context, refs, errors, concurrency and the
  controlled-input end-of-event restore; this namespace owns exactly
  three things React does not: *which* boundaries a commit must re-run,
  *how* a boundary's body reaches subscription values, and *the fence*
  that keeps one render pass on one commit.

  ## The siblings, and why the grouping is what it is

  The runtime is carved into owned modules so that file fences are real
  files. What stays together here is what could not be separated:

  | module | what it owns |
  |---|---|
  | **this one** | the render context, the cell table, the commit, the two read tiers, the read-set entries, the generation fence, the two shells and the two mint doors |
  | [[re-frame.hicasso.impl.generation]] | the flush generation, the registry epoch and `commit-basis` |
  | [[re-frame.hicasso.impl.frames]] | the one frame-locked memo row per id — incarnation, captured bundle and ambient dispatch coupled — its lazy replacement and the reset door |
  | [[re-frame.hicasso.impl.roots]] | the hydration adoption window — one per root, and NOT this module's to empty |

  What the runtime RETAINS is counted from outside it: the test kit's
  `re-frame.hicasso.test.runtime` reads the tables this file exposes, so
  no instrument lives on the hot path.

  The grouping above is a dependency fact, not a taste. The render
  context, the commit and the shells are one strongly-connected
  component: `flush!` has to know whether a body is running (it must not
  call React's `onStoreChange` from inside somebody's render), which is a
  read of [[rstate]]; [[with-commit]] is `flush!`'s window;
  [[frame-dispatch]] is `with-commit` applied to a captured frame's
  `:dispatch-sync`, memoised per frame INCARNATION; [[dispatch!]] is that
  closure applied; and [[run-once]] — which owns `rstate` — binds
  `frame-dispatch` for the body's dynamic extent, which is how a lowered
  callback comes to hold one incarnation's dispatch for the rest of its
  life. Cut that chain anywhere and the two halves require each other.
  So the chain is one namespace, and what left are the parts with an edge
  in one direction only.

  Residence: `implementation/hicasso/src`, the package's own source root.
  No production build requires it — Hicasso is bundle-isolated and
  EXPERIMENTAL — and nothing here reaches the benchmark tree
  (`re-frame.bench.hicasso.*`), which
  `implementation/hicasso/scripts/check_freeze.py` enforces.

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
    [[re-frame.hicasso.impl.generation/commit-basis]] rather than nothing (see *the render→commit gap*
    below). Every term only ever increases, so the sum is monotone:
    `Object.is` on one number is a correct change test, and React's own
    \"getSnapshot should be cached\" rule is satisfied without a memo.

  The hook budget is not self-reported — `arm1_hook_ledger_dom_cljs_test`
  counts hook calls at React's own dispatcher.

  ## The collector is the surface being made to work

  The ambient collector — `sub` as an ordinary function call, legal
  inside a `when`, inside a `for`, and inside an inlined helper — is the
  only read surface acceptable on ergonomics, and grouped `use-subs`
  sits below the usability bar.
  So [[sub]] is the surface this runtime engineers for and [[use-subs]]
  is kept as the control it is measured against. That choice waives none
  of HD-002's correctness gates, and the tripwire still overrides the
  clock.

  ### The ownership state machine (clause (a))

  One sentence carries it, and it is the positive form of the tripwire:

  > **No render-phase code mutates the cell table or a subscription
  > ref-count. The only global mutation is the commit's.**

      RENDERING   the body runs; reads append to ONE module-level scratch
                  array, reset unconditionally at the top of every body
      PENDING     the body returned; the scratch has been resolved to a
                  cached read-set entry; the cell table is untouched
      COMMITTED   React called the entry's `subscribe`; the registration
                  exists and sits in each read key's cell reader list —
                  which is its edge and its reference, one membership
      UNMOUNTED   React called the cleanup; every membership is removed

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
  and it is worth being exact about that.** A boundary id here is the
  registration object minted inside
  [[make-subscribe]], so a changed read set means a new entry, a new
  `subscribe` and therefore a new registration, whose held edge set is
  empty by construction. **There is no difference left to
  take** — a registration installs itself in each acquired cell's reader
  list and its cleanup removes itself from exactly those, which *is* the
  wholesale replacement rather than a degenerate case of something more
  general. `law-4-a-rerun-with-fewer-reads-drops-the-edges-it-stopped-reading`
  proves the outcome against the fused doors.

  What the changed case therefore costs, for an `n`-read boundary with
  one key different: `n` membership removals (each cell left readerless
  queued for the one reaper timer), one entry miss (a `.slice`, an
  `(into #{} ks)` and two closures), and
  `n` [[acquire-cell!]]s, each pushing one slot onto a cell's reader
  list. There is **no cheap route for \"19 of 20 keys unchanged\"**, and a
  page whose rows change read set on a data change pays it per row. That
  is the honest price and the thing to watch on the bulk rows. It
  includes no whole-map rebuild of any second process-global map: the
  reader lists on the cells are the only index there is.

  A durable per-boundary id would make the difference live, and it is
  **unavailable at this runtime's fences rather than merely unbuilt**. It has
  to survive a re-subscribe, so it cannot live on the registration; the
  shell has no per-instance storage that is not a hook, and a third hook
  breaks the HD-020 budget the ledger witness enforces; and threading one
  into `subscribe` would mean `subscribe` closing over something other
  than the read set, which is what makes it a shared, identity-stable
  pure function of a value in the first place. Buying the diff costs the
  two properties the design guarantees, so it is not bought —
  and nothing needs it, because the subscribe/cleanup pair React already
  performs is the whole operation.

  The ordered compare is a false-negative device and never a wrong
  answer: two renders that read the same keys in a different order miss
  the compare, take a second entry with the same set, and React replaces
  a set with itself — slower, still correct. The compare is what decides
  a match, and it is deliberately not *replaced* by a content hash, whose
  failure mode would be a silently missing edge. A hash does choose which
  entries the compare is run against ([[bucket-key-of]]), which is a
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

  This runtime is safe by construction: [[run-once]] closes the window around
  `codec/as-element`, and the codec is eager everywhere it walks —
  `expand-seq` drives a seq to exhaustion, `realize-children` folds one
  into a vector, a seq at a *native* prop position goes through
  `clj->js`, and `impl.codec/realize-deep` forces every lazy sequence
  reachable from a *boundary's* props before the crossing hands them on.
  A lazy read is therefore forced inside the window by the same pass that
  turns hiccup into elements, and moving the codec call out of `run-once`
  fails `a-lazy-for-registers-its-edges-and-its-readers-re-run`.

  The fourth clause is a repair, and the shape of what it repairs is the
  reason [[read-key!]]'s guard below is not the whole story: a boundary
  hand-off that passed its props map through raw would let a seq written
  in one body be realised inside ANOTHER body's render — where the guard
  finds a frame, does not throw, and files the read under the wrong
  boundary. `arm1/boundary-crossing-cljs-test` pins it.

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
  `impl.codec/refuse-deferred!`, and `arm1/deferred-read-cljs-test`.

  ### The cold read, and what it costs (clause (a) consequence)

  A render-phase read is a **pure deref** when the key already has a
  committed cell — the overwhelming case, and the one validation.md's
  \"an unchanged hot read performs no new attach/release\" describes. A
  read of a key nothing holds yet is a **cold probe** ([[cold-read!]]):
  reuse a live sub-cache reaction by deref alone when one
  exists, else compute PURE against one coherent frame-state snapshot
  through one render-scoped memo — the cold-probe discipline, reached
  through the core seam built for it
  (`re-frame.subs/compute-sub-with-memo`). The probe
  creates no cache entry, takes no reference, installs no watch and
  leaves no disposal obligation, so an abandoned render leaves the world
  exactly as it found it — and unlike a `subscribe-once` crossing
  (profiled on the acceptance shape's 141-read mount,
  `read_profile_app.cljs`), it does not pay a reaction build, a cache
  insert, an in-tick evict and a dispose cascade per read to arrive at a
  value it retains nothing of. Acquisition happens at commit, without a
  render-phase deref.

  A cold key still computes twice — once at render (the probe), once
  when the commit acquires and takes its baseline. What the probe
  removes is the second *construction*, not the second compute. The
  shipping React spine attacks the same double build with a render-phase
  escrow, and this runtime deliberately does **not** copy it:
  an escrow is a render-phase ref-count mutation, which is the one thing
  the state machine above forbids — the probe moves the read the other
  way, to a path that mutates nothing at all, transiently or otherwise.
  It is also not a collector charge — `useSyncExternalStore` has the
  identical render/commit shape, so grouped and the scalar comparator
  inherit it — and the escrow belongs to the spine rather than to this
  package.

  ## The re-render path

      write -> the sub layer's equality cutoff -> key-cell watch -> dirty set
        -> flush: epoch bump + generation bump
        -> the dirty CELLS' own reader lists -> dirty boundary set
        -> registration notify (React's onStoreChange)
        -> React re-renders exactly those boundaries
        -> bodies re-run -> hiccup -> impl.codec -> React reconciles

  The dirty *sub-key* set is push-cheap: a key is marked only when the
  sub layer's own equality cutoff let a change through. Notifications are
  batched by [[with-commit]], which the runtime's frame-locked dispatch wraps
  around every intent, so one user action is one flush however many
  subscriptions it moved.

  ## The commit basis, the fence, and the render→commit gap

  Invariant 5 has two windows, and they are not the same window. One is
  *inside a body* — a commit landing between two of one render's reads.
  The other is the **render→commit gap** — a commit landing after the
  body returned and before React runs the effect that acquires its
  edges. Re-reading every subscription at commit would guard the second,
  but a commit-phase deref is forbidden (HD-002), so the runtime needs
  something else there.

  Both windows are judged against one number, [[re-frame.hicasso.impl.generation/commit-basis]]:

      commit-basis(frame) = this runtime's flush generation
                          + that frame's own physical-install epoch
                          + this runtime's registry epoch

  [[re-frame.hicasso.impl.generation/generation]] counts flushes. `frame-commit-epoch` is the substrate's
  own counter, bumped once per physical frame-state install at both
  write chokepoints, and it exists for
  exactly this question — *did the frame's durable state move in the
  render→commit gap?* — because it answers without watching anything.
  [[re-frame.hicasso.impl.generation/registry-epoch]] counts `:sub` registrations, which are neither a
  flush nor an install, so a `reg-sub` in the gap would otherwise move no
  term at all. The second term is what the generation alone cannot
  supply, and it is why the basis is not just the generation:

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
     gap heals.**

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

  ### The other two axes, and which half of each the basis carries

  Writes are not the only movement invariant 5 must see. The other two
  axes — a `:sub` registration (Spec 006's registry epoch) and a same-id
  frame reincarnation (its `:node-key`) — split cleanly by whether the
  boundary in question **already holds a cell** for the key, and the two
  halves want opposite answers.

  For a boundary that holds one, **adding a term closes nothing**: each
  transition leaves the cell holding a reaction that can no longer answer
  for its key, so a number that moved would buy exactly one extra render,
  and that render would read back through the same dead reference. That
  half is carried by the substrate's own events, below.

  For a boundary inside the render→commit gap there is no cell, so there
  is no dead reference — the commit acquires against whatever is live
  *then*, and one extra render is exactly the repair. The registry half
  is closed by the [[re-frame.hicasso.impl.generation/registry-epoch]] term of the
  basis; because a held key contributes a frozen stamp and only a staged
  key reads the basis live, the term reaches the gap and costs the
  mounted case nothing. The `:node-key` half stays open and is stated in
  [[re-frame.hicasso.impl.generation/commit-basis]] — the basis TIES across a reincarnation, so no
  arithmetic over these terms could report it.

  What each transition does to a *held* cell, and what the runtime hears
  when it happens:

  | transition | what the cell is left holding | what announces it |
  |---|---|---|
  | `:sub` **re-registration** | a disposed container — the sub-cache evicted the entry, and `-dispose` cleared the watcher set, so the cell is deaf from that instant and answers the RETIRED computation on every later deref | the reaction's own disposal |
  | **frame destruction** (incl. a same-id reincarnation) | a container wired to the frame that no longer exists, answering the destroyed incarnation's db | the reaction's own disposal |
  | `:sub` **first registration** | the substrate's uncached nil-recovery, which was never wired to anything and can never see the handler that has now arrived | nothing — so [[first-registration!]] listens for the registration itself |

  So the runtime takes the substrate's own events instead of a term.
  [[invalidate-cell!]] is the one repair all three reach:
  [[wire-cell!]] arms it per unique key against the reaction's disposal,
  which covers the two transitions that dispose; the third disposes
  nothing — `registrar/add-replacement-hook!` fires only when a previous
  handler existed — so the runtime hangs it off
  `registrar/add-registration-hook!` ([[sub-registered!]]), narrowed to
  first-time `:sub` registrations and to the cells that hold the id being
  registered. It costs no React hook and no per-boundary object.

  The gap half rides the same hook, one line earlier, as a `vswap!` on
  [[re-frame.hicasso.impl.generation/registry-epoch]] — a registry count the runtime keeps itself
  rather than a new public reader on a production namespace. **What is
  rejected is the OTHER placement**: a registry term in
  every key's *live* contribution to [[make-snapshot]], which moves every
  mounted boundary in the application on every `reg-sub`. This term is in
  the basis, which [[make-snapshot]] reads live for staged keys only, so
  an unrelated registration still moves no *mounted* boundary's
  snapshot — the invariant
  `a-first-registration-of-an-id-no-cell-holds-disturbs-nothing` states,
  and the one that distinguishes the two options.

  ## What is deliberately NOT here (the hard fences)

  No compiler and no analyzer: bodies are ordinary functions and hiccup
  is interpreted by the shared codec at runtime. No dual mode: one shell,
  one cell table, one read path — the two HD-002 tiers differ in *where
  the author writes the read*, not in the machinery underneath. No
  ViewCell-class object graph. No candidate ledger. Codec caching is the
  only accelerant (HD-004); nothing here holds a node reference, plans a
  hole, or writes the DOM."
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
  "Give `cell` a live subscription: subscribe, **activate**, establish the
  baseline, arm the value-change watch, and arm the **disposal** hook. The
  whole of a cell's attachment to the substrate, in one place, because it is
  performed twice — once when the cell is born and once when the
  substrate disposes the reaction out from under it.

  **Activation comes first, and it is not optional** — ACTIVATE, then
  watch, then observe.
  A subscription under the ratom family IS a bare `reagent.ratom/Reaction`,
  built deliberately without `:auto-run`, and a Reaction learns its sources
  only through `deref-capture`: a plain deref taken outside `*ratom-context*`
  runs the body raw and leaves `watching` nil. The reaction is then not in
  app-db's watcher set, so the watch below never fires, [[mark-dirty!]] never
  fires — that watch is its only caller — and no write after the mount ever
  becomes re-render work. Measured: the runtime painted once and was deaf
  thereafter. `interop/activate-derived-value!` is the substrate's own op for
  this and is a routed no-op on the React-hook spine, which wires one watch
  per source at construction.

  It runs BEFORE the watch so the activating run cannot fan a priming
  notification, and before the baseline deref so that deref reads a settled
  node — the activation left it clean, so the baseline recomputes nothing.

  The disposal hook is the runtime's counterpart to the two axes
  `commit-basis` cannot see, and it is deliberately an
  *event* rather than a term in the epoch sum: the substrate already
  tells us, exactly and only when it happens, and a term would have to be
  read by every key on every snapshot to discover the same thing later.
  It covers every transition that *disposes* — which is all of them but
  one, and [[first-registration!]] carries the one it is not: a first
  registration announces itself by registering, and disposes nothing."
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
  "**The repair for a cell whose reaction can no longer answer for its
  key**. A cell holds its reaction for the life of every
  boundary that reads the key — that is what makes a warm read a pure
  deref — and three substrate transitions retire what it is holding:

  1. a `:sub` **re-registration**, which evicts the query's sub-cache
     entry and disposes the reaction (`subs.cache/invalidate-sub-on-replace!`);
  2. a **frame destruction**, whose teardown disposes the frame's cached
     reactions — including across a same-id reincarnation;
  3. a `:sub` **first registration**, which disposes nothing at all and
     reaches here through [[first-registration!]] instead.

  The first two leave the cell holding a container whose `-dispose` has
  already run `(reset! watchers {})`, so the watch this runtime installed is
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
  [[cold-read!]]'s probe, so every render from this instant on computes
  against the new registration and the live frame. **At the microtask
  checkpoint** the durable attachment is rebuilt, so later writes notify
  again — deferred because this callback runs inside the registrar's
  replacement hook, inside its registration hook and inside frame
  teardown, and none of them is a place to subscribe. A frame that did
  not come back has nothing to rebuild against, and the cell is disposed
  instead.

  **The deferral is a microtask, and that is a correctness requirement
  rather than a preference**. Design law React 3 requires a
  render/commit tear to be corrected *before visible paint*, and the
  `mark-dirty!` below IS that correction: `commit-basis` ties across a
  same-id reincarnation — the frame term restarts and neither of the
  other two is a frame fact — so `getSnapshot` ties, React schedules
  nothing at the instant the successor seats, and the committed fiber
  goes on holding the predecessor's value until the rebuild moves the
  number. A `setTimeout 0` here would hand the correction to a
  LATER task, and the event loop is free to update the rendering between
  tasks: the predecessor's value could reach the screen, and on a
  tenant or account switch that is another tenant's data, briefly, with
  no trace left by the time anyone looks. The microtask checkpoint
  cannot be skipped that way. It drains before the same task's
  update-the-rendering step, and it drains microtasks queued *while*
  draining — which is how React's own microtask-scheduled sync-lane
  flush for the `useSyncExternalStore` notification gets in — so the
  rebuild, the re-stamp, the notification, and React's render and commit
  all complete ahead of the next paint.

  What it may not become is *no* deferral. Rewiring in-stack re-enters
  the three hooks named above and was measured red; the unwound stack is
  the whole of what the deferral buys, and the microtask checkpoint is
  the earliest moment the stack is unwound. The narrowing that costs is
  that the rewire window is the current checkpoint rather than a
  whole task, so a successor seated in a LATER task finds the cell
  disposed — and recovers, through [[cold-read!]]'s probe on the next
  render, which is the same recovery a key that never had a cell gets."
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
  "**The registry transition no disposal announces.**
  `registrar/add-replacement-hook!` — the hook the
  sub-cache eviction that [[invalidate-cell!]] rides is built on — fires
  only when a previous handler existed. A *first* `reg-sub` for a query
  therefore evicts nothing, disposes nothing, and would reach a runtime that
  listened for disposals alone by no route whatsoever.

  It has something to reach because a boundary can hold the miss. The
  substrate is careful that it should not: a subscribe to an unregistered
  query emits `:rf.error/no-such-sub`, recovers to a nil-yielding
  reaction, and **deliberately does not cache it**, precisely so that a
  later registration is observed by the next `subscribe`
  (`subs/build-and-cache!*`). This runtime has exactly one property that
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
  here — and at namespace load there are no cells to scan.

  **It is the held-cell half of the axis, and only that half.** A
  boundary inside the render→commit gap has no cell for the id, so this
  scan reaches nothing on its behalf; the [[re-frame.hicasso.impl.generation/registry-epoch]] term of
  [[re-frame.hicasso.impl.generation/commit-basis]] carries that half instead."
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
  "One cold read — a key no committed cell answers for — on the cold-probe
  discipline.

  Three rungs, cheapest first, all of them mutation-free:

  1. **A live sub-cache reaction is reused by deref alone.** Some other
     holder (a cell on another boundary mid-anything, a tool, a test)
     keeps the reaction warm; a `subscribe-once` reaching the same deref
     would bump a ref-count both ways for nothing. Single-threaded CLJS
     is what makes the unguarded deref safe: nothing can evict between
     the `get` and the `@`.
  2. **A truly cold key computes PURE** — `subs/compute-sub-with-memo`
     against ONE coherent frame-state snapshot, minted lazily on the
     run's first cold read ([[run-once]] resets the box, so a fence
     re-run or a StrictMode double-invoke computes against the state
     that is current THEN). No cache entry, no ref-count, no watch, no
     disposal obligation — where `subscribe-once` paid a reaction build,
     a cache insert, an in-tick evict and a dispose cascade per read,
     this path pays a registrar lookup and the sub's own body. Each
     compute threads a FRESH per-read memo seeded with
     `subs/observation-opts-key`, so an unregistered query emits the
     always-on `:rf.error/no-such-sub` exactly as the reactive build
     does and recovers to the same nil; the run-level dedup lives in
     the box's own value map, where a key already computed this run is
     a `find`, not a second compute. The read profile
     (`read_profile_app.cljs`, phase A) is why the memo is per read
     rather than one threaded across the run: on the acceptance shape's
     141 distinct layer-1 reads a run-shared memo's own bookkeeping —
     three `swap!`s per sub against a map grown to 141 entries — cost
     more than it deduplicated (`probe` 2.75 vs `probe-fresh` 1.42
     µs/read), and mid-graph parent sharing, the thing only a threaded
     memo can buy, prices at zero on a page whose subs are all
     single-source. A layered consumer pays one extra parent compute
     per cold read of the same run — bounded by the fence to one
     commit's worth — and the trade is re-openable the day a shape
     with deep shared chains prices it the other way.
  3. **A missing or destroyed frame falls back to `subscribe-once`**,
     which emits `:rf.error/frame-destroyed` and recovers to nil — the
     predecessor's whole behaviour on that edge, kept rather than
     re-spelled.

  The compute sits inside `live-frame/call-with-frame-resolution` for
  the same two reasons `subscribe` itself does: an image-loaded frame's
  registrar lookups must resolve through that frame's own image, and the
  wrapper's read-time coalesced flush is what makes a `reg-sub` issued
  earlier in this same tick visible to this very read — without it, a
  register-then-render-sync sequence computes against a stale
  projection and misses the handler (witnessed, and mutation-proven, in
  `arm1/cold_read_cljs_test`).

  Within one body run all cold reads observe one snapshot, which is the
  fence's own invariant stated smaller; a commit landing mid-body moves
  the [[re-frame.hicasso.impl.generation/commit-basis]] and [[render-body]] re-runs the body against the
  newer one, fresh box included. The values a probe computes are what
  the sub-cache reaction would have answered against the same committed
  state — `compute-sub` and the reactive path share the input grammar,
  the recover-to-nil contracts and the schema validation, which is what
  makes the equivalence a contract of the seam rather than a
  coincidence."
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
                " happened outside a boundary render. `sub` and `use-subs` "
                "are legal only inside a defview body; `subscribe-once` is "
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
  against — not because it is being defended. It sits below the
  ergonomics bar."
  [query-map]
  (reduce-kv (fn [m alias query-v] (assoc m alias (read-key! query-v)))
             {}
             query-map))

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
  "The bucket a read sequence belongs to: an **order-sensitive hash of
  the whole sequence**.

  Two callers hand it two arrays and neither is special-cased — the
  scratch a body just filled ([[entry-for]]), and the one-key array a
  React hook reads with ([[hook-entry]]). A bucket rule that knew which
  of the two it was answering for would be two rules.

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

  **Why the first sub-key is not enough.** Bucketing on
  `(aget scratch 0)` makes the scan's cost a function of how an author
  orders their `let` bindings. A row body reading its per-row key first
  puts one entry in each bucket; the same body reading a page-wide key
  first — one line moved — puts every live row's entry in ONE bucket, and
  every probe then passes the length test and the index-0 test and fails
  only at the last key. Mounting N such rows costs `sum(i)` probes, and
  N = 300 is a rung the benchmark suite measures. Same page, same edges,
  same DOM, ~150x the entry-lookup work. Hashing the whole sequence makes
  the bucket a function of the read set rather than of its first element,
  and `the-bucket-scan-does-not-grow-with-the-number-of-boundaries`
  holds it there."
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
  "The provisional-entry reaper's delay: **4 ms, not 0**.

  ## What the 0 raced

  An entry is minted during the RENDER ([[entry-for]], from
  [[render-body]]) and claimed during the COMMIT — React calls the
  entry's `subscribe` from a passive effect, and that is the only place
  `refs` goes above zero. A `setTimeout 0` armed inside the render
  therefore has to beat React back to its own passive flush, and on a
  root React renders CONCURRENTLY it does not: the entry is evicted from
  the cache before it is claimed, the subscribed boundary holds a
  detached entry, and the next render of the same read sequence misses,
  mints a second entry, and hands `useSyncExternalStore` a different
  `subscribe` — so React tears the subscription down and rebuilds it,
  releasing and re-acquiring every cell, immediately after adoption.

  `hydrateRoot` is exactly such a root. It is the same class the spine
  meets, where a `setTimeout 0` escrow reaper beats
  `createRoot().render()`'s passive flush and costs `bodyRuns` 2.00N on
  every consumer mount; 4 ms is the SHORTEST delay measured to win there,
  at N = 1 and N = 300 alike, and this runtime adopts that number rather than
  inventing one.

  ## A MARGIN, NOT A CONTRACT

  React documents no maximum render-to-subscribe interval, so 4 ms cannot
  be sized against a guarantee — it is the measured distance on React 19
  today, and a scheduling change can silently reintroduce the rebuild.
  **No caller may rely on it.** Correctness does not: a lost race costs
  a cache miss and a rebuilt subscription, never a wrong value, because
  the entry object itself survives in the closure that was handed out.
  What the horizon buys is that the adoption is realised, and what it
  costs is that an abandoned render's entry sits in the cache 4 ms — the
  zero-leak property is unchanged, and only its zero-POINT moves."
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
  "Turn a body fn into a boundary: a React function component, marked as a
  legal hiccup head and given the codec's stable memo wrapper. Minted
  once, at definition — which is why the codec's third HD-004 cache
  (stable component heads) has nothing to do in this runtime, and why HD-016
  can make a plain function in head position a loud error instead of
  auto-wrapping it.

  **The returned value is still the function**, and that is a constraint
  rather than an accident: `React.memo` answers an object, the codec and
  these tests require a minted head to BE a function, and no memo object
  may escape as the public representation. `memoize-boundary!` therefore attaches the wrapper to
  the head and hands the head back; the codec creates elements from the
  wrapper. See [[re-frame.hicasso.impl.codec/memoize-boundary!]].

  ## Why there is a bail-out at all (HD-006 as amended)

  Without one, a write that moves a key the PAGE reads re-renders the
  page and then every boundary beneath it — 300 of 300 cards on the
  tier-1 feed shape, every card's props and every card's subscription
  values equal. That contradicts the central claim — that
  boundaries are independent, and a write wakes only its readers — on
  precisely the bulk row the bar is set on, and it is the axis Reagent's
  argv compare already wins.

  ## Why bailing out on PROPS is safe when bodies read SUBSCRIPTIONS

  This is the question the bail-out has to answer, because a memo that
  bailed while a subscription moved would freeze a row on screen — the
  exact failure class [[invalidate-cell!]] and [[first-registration!]]
  exist to close. It is safe
  because props are not the only channel into the shell, and memo blocks
  only one of them:

  - **Subscriptions** arrive through [[shell]]'s `useSyncExternalStore`.
    A commit calls that fiber's own `onStoreChange` ([[flush!]] →
    `notify!`), which schedules an update **on the boundary's fiber**.
    React consults `checkScheduledUpdateOrContext` BEFORE it consults the
    comparator, so a fiber with pending work re-renders and the
    comparator is never even asked. A row whose reads moved cannot be
    bailed out, whatever its props say. Precedent: reagent-slim's
    default update check is argv `=`, and reactive invalidation bypasses
    it via `forceUpdate` — the same shape, by design.
  - **The frame** arrives through `useContext`, and React propagates a
    context change to its consumers directly — again ahead of the
    comparator, and again through a memo.
  - **Props** are the remaining channel, and the only one memo blocks.

  So the bail-out is exactly the case where all three are unchanged, and
  a body that is a pure function of the three cannot observe it — which
  is the contract: bodies stay pure and re-runnable, and memoization is a
  scheduling optimization and never observable semantics. The residue is
  a body reading something that is none of the three — a bare atom,
  `Date.now()` — which was never tracked and never woke a boundary on its
  own before either; the cascade merely re-ran it by accident. Reagent's
  argv compare has the identical residue.

  ## What it costs, stated rather than claimed away

  **No hook** — the comparator is React's, not the shell's, so the ≤2-hook
  budget is untouched and the ledger still reads `useContext` +
  `useSyncExternalStore`. **One fiber per boundary**: a `React.memo`
  carrying a custom comparator stays a `MemoComponent` rather than
  collapsing to React's `SimpleMemoComponent`, so React keeps a wrapper
  fiber above the component's own. That is React's retention rather than
  this runtime's, and the bench tree's retained inventory prices it under
  `:react/memo-fiber` instead of leaving it for a heap ladder to
  discover.

  ## Spec 009's `:render` bucket, and why the bracket is HERE

  Spec 009 §What gets bracketed names four hot paths; three of them
  (`:event`, `:sub`, `:fx`) are core's and are already live for a
  Hicasso app, because they sit in the router, the subs layer and the fx
  layer this runtime consumes unchanged. The fourth — `:render` — is the
  **view substrate's**, and it is bracketed here so a Hicasso app's
  per-view render reaches the User-Timing stream like every other
  re-frame2 app's. It is a `:render` in the spec's own terms: the bucket is keyed
  on the *representation* of the work — one view boundary's body run,
  once per render pass the host actually performs — not on which
  registration API minted the head. `reg-view*` mints its wrapper with a
  `defn`; `defview` mints its wrapper here; both are \"the wrapper the
  substrate emits around a registered view body\", which is exactly what
  the spec's `Where` column describes.

  **The bracket is the component fn and not [[render-body]]**, and the
  reason is a cost that would survive elision. [[render-body]] does not
  know the view's name — threading one in would add a parameter passed on
  every render of every boundary, present in the OFF bundle as well as
  the on one, which is the thing Spec 009's whole design is arranged to
  avoid (and which [[hydrate-cljs-test]] would have to be edited to
  accommodate). `view-name` is already closed over at this exact point,
  so under `:advanced` + `re-frame.performance/enabled? false` the macro
  constant-folds to `(shell body-fn js-props)` — byte-for-byte the call
  that was here before, with nothing added anywhere.

  Placing it at the component fn also makes four behaviours fall out
  rather than be arranged:

  - **A memo bail-out emits nothing.** React consults the comparator
    ABOVE this fn; a bailed-out boundary never enters it. HD-028's rider
    holds on the measure stream for the same reason it holds on the
    body-run counter — a boundary React skipped and a boundary React ran
    are two different numbers.
  - **StrictMode's double-invoke emits twice**, which is correct: React
    ran the body twice and the measure stream should say so.
  - **The generation fence emits once.** [[render-body]] may run a body
    up to four times for ONE React render; the bracket spans the whole
    retry loop, so the entry count stays one-per-render-pass and its
    duration is the wall-clock React actually paid — the honest RUM
    number rather than a count inflated by an internal retry.
  - **A throwing body still emits**, via the macro's own `try/finally`,
    and an abandoned render (a suspended subtree, an SSR pass React
    discards) emits the time it genuinely spent. Nothing durable is left
    behind either way: the bracket writes one measure and clears it by
    name unless the consumer flipped `retain-entries?`.

  **Boundaries only.** A plain inlined helper called from a body is not a
  React component and is not a `reg-view` peer, so it is not separately
  bracketed — its cost lands inside the enclosing boundary's measure,
  which is the same place its reads land (see the render-context comment
  above).

  **The id rule, pinned:** the entry is `rf:render:<view-name>`, where
  `view-name` is the string this fn was given and the string stamped as
  `displayName` on the line below — so a consumer reading a measure name
  and a developer reading React DevTools are looking at the same
  identifier, and `defview` makes it `\"<ns>/<sym>\"`. Witnessed in
  `arm1.render-measure-cljs-test` (the OFF half) and
  `arm1.render-measure-emit-nightly-test` (the ON half)."
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
  "Publish the name `defview` already computed to core's `:view`
  registrar, so a keyword an author WROTE resolves forward to the
  boundary they meant.

  Three arguments, each already in the macro's hand at the declaration:

  - `view-id` is `(keyword \"<ns>\" \"<sym>\")` — byte-identical to the
    id `rf/reg-view` derives from its own symbol
    (`core-reg-view-macro/expand-reg-view`), so ONE convention answers
    for both substrates and a tool needs no per-substrate spelling.
  - `slot` is the coordinate map the macro captured, plus the author's
    `:doc` when they wrote one. It is stored the way `reg-view` stores
    it — `:ns` / `:file` / `:line` / `:column` at the TOP LEVEL of the
    registration metadata, which is where `(rf/handler-meta :view id)`
    already reads them, so a consumer needs no second seam. `:doc`
    rides along for the same reason it does on a `reg-view`: the
    registrar dev-warns `:rf.warning/missing-doc` for a macro-path
    registration with no usable `:doc`, and withholding a docstring the
    author DID write would make that warning fire on a documented view.
  - `head` is the value the `def` binds — the minted boundary — under
    `:hicasso/component`.

  ## The entry is an ALIAS, and carries NO `:handler-fn`

  `rf/view` answers *the registered render fn*, and this entry has none:
  a Hicasso boundary is a React component minted by [[mint-view!]], not
  a hiccup-returning render fn, and pretending otherwise would make that
  contract lie. So `(rf/view id)` answers nil here, deliberately, and a
  substrate handed a Hicasso id keeps whatever diagnostic it already
  has. The head is reachable at `:hicasso/component` by a consumer that
  knows what to do with a React component.

  This publishes RESOLVABILITY and not identity. The name is not new —
  `defview` already stamps it as DevTools `displayName`, keys Spec 009's
  `rf:render:<name>` measure off it, and attributes refusals with it;
  the only thing missing was lookup. Mounted boundary identity is still
  keyed by the read set and still unnamed, which is what
  `re-frame.hicasso.tool` answers the BACKWARD question with — its
  refusals stand untouched, because they refuse *which view is this
  runtime boundary?* while this answers *where is the view the author
  named in source?*.

  ## Why `registrar/register!` rather than `rf/reg-view*`

  Two reasons, and either alone is decisive. `rf/reg-view*` on CLJS
  delegates to `re-frame.views/reg-view*`, which ALWAYS builds a
  frame-aware render wrapper and stores it under `:handler-fn` — so it
  cannot mint the metadata-only entry above at all. And its first step
  is `apply-adapter-wrap-view`, which consults the `:adapter/wrap-view`
  late-bind hook AT REGISTRATION TIME; a `defview` is declared at
  namespace load, which routinely precedes `rf/init!`, so a registration
  that went through that path would ask an adapter question before there
  is an adapter to answer it. Writing the slot here — a plain
  registration under a kind the registrar already knows — is
  adapter-NEUTRAL by construction: no hook is consulted, nothing wraps
  the head, and `:hicasso/component` is `identical?` to the value the
  `def` binds whether an adapter is installed or not.

  ## Hot reload — the slot needed nothing added, the NOTIFICATION did

  Re-evaluating a `defview` re-registers the SAME id, and the registrar
  replaces the slot atomically — one entry, never two, with the fresh
  head. The provenance is unchanged across a save, so the registrar's
  collision warning stays correctly silent; a genuine cross-source clash
  on one id still surfaces, because it surfaces for every kind.

  What the entry DID have to say out loud is where its executable
  identity lives, and `:executable-key` says it. `register!` tags every
  `:rf.registry/handler-replaced` — and every replacement-hook call —
  with `:different-fn?`, derived by default from `:handler-fn`. This
  entry has none by design, so the default derivation compares nil with
  nil and reports `:different-fn? false` for a genuine swap of one
  component for another: a hot-reload consumer branching on that tag
  would read every Hicasso view edit as an idempotent reload and decline
  to refresh. Naming `:hicasso/component` as the executable slot makes
  the tag truthful without a second HMR surface, without a hicasso-side
  replacement hook, and without putting anything in `:handler-fn`.
  The registrar reads the key this
  registration named; it learns nothing about Hicasso.

  Called ONLY from inside the `defview` expansion's
  `(when re-frame.interop/debug-enabled? …)` gate — the same gate the
  declaration extent rides — so under `:advanced` + `goog.DEBUG=false`
  the call, this fn and the slot map all leave the bundle and a
  production Hicasso app has no registry at runtime. Witnessed in
  `re-frame.hicasso.error-source-coord-elision-prod-test`.

  Answers `view-id`, per Conventions §`reg-*` return-value."
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
