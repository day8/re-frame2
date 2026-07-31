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
    set's epochs**. Epochs only ever increase, so the sum is monotone:
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

  A boundary's edge set is **replaced wholesale, once, at commit** —
  `front.sub-index/record-reads`'s set difference — and only when the
  read set actually changed, because an unchanged set leaves the
  `subscribe` closure identical and React does not call it again. The
  unchanged case is detected **without building anything**: the scratch
  array is compared pairwise against the cached entry's key array, so
  steady-state allocation for edge maintenance is zero bytes and the
  allocation slope across warm 1/3/7/20 reads is flat.

  The ordered compare is a false-negative device and never a wrong
  answer: two renders that read the same keys in a different order miss
  the compare, take a second entry with the same set, and React replaces
  a set with itself — slower, still correct. It is deliberately not
  repaired with a content hash, whose failure mode would be a silently
  missing edge.

  **No per-read object, no second candidate slot, nothing keyed by a
  render or an attempt, and no commit-phase deref.** The commit consults
  sub-keys only; the generation fence, not a re-read, is what preserves
  invariant 5.

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

  ## The generation fence

  [[generation]] counts flushes. A body runs inside [[render-body]],
  which captures the generation before the body and checks it after: if a
  commit landed *during* the body, its reads straddle two commits and the
  body is re-run against the newer one. That is invariant-5 preservation
  written as a loop rather than as a comment, and one comparison per
  boundary rather than one deref per read.

  A flush raised while a body is running must not call React's
  `onStoreChange` — that is a render-phase update on somebody else's
  component. Those notifications are deferred to a macrotask; the fence
  has already made the *rendering* boundary correct.

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

(def ^:private rstate
  "The render slots: the frame the running body resolved (nil outside a
  render, which is what makes `(sub …)` outside a boundary a loud error
  rather than a silent read of whichever frame happened to be ambient),
  the two read-surface provenance flags, and the entry the last body
  resolved. One JS object for the whole runtime — not one per render."
  #js {"frame" nil "collector" false "grouped" false "entry" nil})

(def ^:private scratch
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

(declare flush!)

(defn- mark-dirty! [cell]
  (when-not (.-disposed cell)
    (vswap! !dirty conj cell)
    (when-not @!batching (flush!))))

(defn- dispose-cell! [cell]
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
  [cell]
  (js/setTimeout (fn [] (when (and (zero? (.-refs cell)) (not (.-disposed cell)))
                          (dispose-cell! cell)))
                 0)
  nil)

(defn- acquire-cell!
  "**Commit-phase only.** Take (building if necessary) the durable
  reference for `sub-key`, and attach the one watch that turns the sub
  layer's equality cutoff into this arm's dirty set. Acquire without
  deref: the render already knows the value."
  [sub-key]
  (let [cell (or (get @!cells sub-key)
                 (let [frame-kw (nth sub-key 0)
                       query-v  (nth sub-key 1)
                       reaction (subs/subscribe query-v {:frame frame-kw})
                       wk       (keyword "rf-hicasso-arm1"
                                         (str "w" (vswap! !watch-counter inc)))
                       fresh    #js {"subKey"   sub-key
                                     "frameKw"  frame-kw
                                     "queryV"   query-v
                                     "reaction" reaction
                                     "watchKey" wk
                                     "epoch"    0
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
                   (when (some? reaction)
                     @reaction
                     (add-watch reaction wk
                                (fn [_ _ old nu] (when-not (= old nu) (mark-dirty! fresh)))))
                   (swap! !cells assoc sub-key fresh)
                   fresh))]
    (set! (.-refs cell) (inc (.-refs cell)))
    cell))

(defn- release-cell! [cell]
  (set! (.-refs cell) (dec (.-refs cell)))
  (when (<= (.-refs cell) 0) (arm-cell-reaper! cell))
  nil)

;; ---------------------------------------------------------------------------
;; The commit — the only door through which a write becomes re-render work
;; ---------------------------------------------------------------------------

(defn- notify! [registrations]
  (doseq [r registrations]
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
      (doseq [c dirty] (set! (.-epoch c) (inc (.-epoch c))))
      (let [boundaries (index/commit! (into #{} (map #(.-subKey %)) dirty))]
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
  nothing, so an abandoned render leaves the world as it found it."
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
    (if-some [cell (get @!cells sub-key)]
      (when-some [r (.-reaction cell)] @r)
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
  ;; first-sub-key -> vector of entries. Bucketing on a value the scratch
  ;; already holds keeps the steady-state lookup allocation-free; the
  ;; distinct-query witness puts one entry in each bucket.
  (atom {}))

(def ^:private empty-bucket-key ::no-reads)

(defn- drop-entry! [entry]
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
  [entry]
  (js/setTimeout (fn [] (when (zero? (.-refs entry)) (drop-entry! entry))) 0)
  nil)

(defn- entry-matches?
  "Ordered pairwise compare of an entry's key array against the scratch.
  Allocates nothing. A false negative — same set, different order — costs
  a second entry and a symmetric difference that removes and re-adds the
  same edges; it is never a wrong answer, which is why this is not a
  content hash."
  [entry]
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
  set."
  []
  (let [bucket-key (if (zero? (alength scratch)) empty-bucket-key (aget scratch 0))
        bucket     (get @!entries bucket-key)]
    (or (some (fn [e] (when (entry-matches? e) e)) bucket)
        (let [ks    (.slice scratch)
              entry #js {"keys"      ks
                         "set"       (into #{} ks)
                         "refs"      0
                         "bucketKey" bucket-key}]
          (unchecked-set entry "subscribe" (make-subscribe entry))
          (unchecked-set entry "snapshot" (make-snapshot entry))
          (swap! !entries update bucket-key (fnil conj []) entry)
          (arm-entry-reaper! entry)
          entry))))

(defn- make-snapshot
  "React's `getSnapshot`: the sum of the set's epochs. Monotone, so
  `Object.is` on it is a correct change test; cached on the entry, so a
  render allocates no closure for it."
  [entry]
  (fn snapshot []
    (let [cells @!cells
          ks    (.-keys entry)
          n     (alength ks)]
      (loop [i 0 acc 0]
        (if (== i n)
          acc
          (recur (inc i)
                 (if-some [c (get cells (aget ks i))] (+ acc (.-epoch c)) acc)))))))

(defn- make-subscribe
  "React's `subscribe`, as a pure function of the read set.

  **The only global mutation in the state machine.** The boundary's
  registration is minted from React's own `onStoreChange`, the index
  learns the boundary and its edges, and each key takes its committed
  reference. The returned cleanup is the exact inverse and is the only
  place edges and references are released, so teardown is symmetric with
  mount whatever React did with the renders in between.

  The registration holds exactly the cells it acquired, so its cleanup
  cannot release a successor's after a reap and rebuild."
  [entry]
  (fn subscribe [on-store-change]
    (let [reads (.-set entry)
          reg   #js {"reads" reads "notify" on-store-change}
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
  [entry notify]
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
    (intent/with-frame (frame-dispatch frame-kw)
      (fn [] (codec/as-element (body-fn props))))
    (finally (set! (.-frame rstate) nil))))

(defn render-body
  "Run a boundary body under the generation fence and return its element;
  [[last-reads]] carries the read-set entry.

  The fence is the loop: capture the generation, run the body, and if a
  commit landed while it ran, run it again against the newer commit. All
  of a pass's reads therefore observe one commit — invariant-5
  preservation as one comparison per boundary, not one deref per read."
  [frame-kw body-fn props]
  (loop [attempt 0]
    (let [before  (generation)
          element (run-once frame-kw body-fn props)]
      (cond
        (= before (generation))
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
  [entry]
  (.-set entry))

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
        entry    (.-entry rstate)]
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
  if nobody writes it down."
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
     :what  "React's own hook cell for the frame hook"}]
   :shared
   [{:token :key-cell
     :what  "one cell + one sub-cache reaction per unique (frame, query), however many boundaries read it"}
    {:token :read-set-entry
     :what  "one key array, key set, subscribe and getSnapshot per distinct read SET, shared by every boundary with that set"}
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

(defn stats
  "What the witnesses read: live cells, live boundaries, cached read-set
  entries, the generation, and the codec caches."
  []
  (let [idx (index/snapshot)]
    {:cells      (count @!cells)
     :cell-refs  (reduce + 0 (map (fn [[_ c]] (.-refs c)) @!cells))
     :boundaries (count (:live idx))
     :edges      (reduce + 0 (map (fn [[_ v]] (count v)) (:b->subs idx)))
     :entries    (reduce + 0 (map (fn [[_ v]] (count v)) @!entries))
     :generation (generation)
     :frames     (count @!frame-ops)
     :codec      (codec/cache-sizes)}))

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
