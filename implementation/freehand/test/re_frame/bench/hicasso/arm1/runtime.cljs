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
    else**, so it is a pure function of a *value*. It is therefore
    cached by that value ([[closure-for]]) and shared by every boundary
    reading the same set — which makes its identity stable across a
    re-render whose reads did not change, which is exactly the condition
    React uses to decide whether to re-subscribe. An unchanged hot read
    performs no new attach and no new release, by construction.
  - React calls that shared `subscribe` once per *fiber*, handing it that
    fiber's own `onStoreChange`. The registration minted inside is
    therefore per-boundary, durable for the mount, and commit-owned — so
    it is the boundary id the index keys on, and a render that never
    commits registers nothing at all. The abandoned-render leak class is
    closed by construction rather than by bookkeeping.
  - `getSnapshot` closes over the same key set and returns the **sum of
    those keys' epochs**. Epochs only ever increase, so the sum is
    strictly monotone: `Object.is` on one number is a correct change
    test, and React's own \"getSnapshot should be cached\" rule is
    satisfied without a memo.

  The hook budget is not self-reported — `arm1_hook_ledger_dom_cljs_test`
  counts hook calls at React's own dispatcher, which sees every call the
  shell makes.

  ## The re-render path

      write -> the sub layer's equality cutoff -> key-cell watch -> dirty set
        -> flush: epoch bump + generation bump
        -> front.sub-index/commit! -> dirty boundary set
        -> registration notify (React's onStoreChange)
        -> React re-renders exactly those boundaries
        -> bodies re-run -> hiccup -> front.codec -> React reconciles

  The dirty *sub-key* set is push-cheap: a key is marked only when the
  sub layer's own equality cutoff let a change through, so a commit that
  moves one subscription costs one mark and one index lookup, never a
  sweep of every live key. The dirty *boundary* set is the shared front
  half's index, unmodified — this arm calls `mount!`, `record-reads!`,
  `unmount!` and `commit!`, and edits nothing.

  Notifications are batched by [[with-commit]], which the arm's
  frame-locked dispatch wraps around every intent, so one user action is
  one flush however many subscriptions it moved.

  ## The generation fence

  [[generation]] counts flushes. A body runs inside [[render-body]],
  which captures the generation before the body and checks it after: if a
  commit landed *during* the body, its reads straddle two commits and the
  body is re-run against the newer one. That is invariant-5 preservation
  written as a loop rather than as a comment, and
  `arm1_generation_fence_dom_cljs_test` stages exactly that commit from
  inside a rendering body.

  A flush that happens while a body is running must not call React's
  `onStoreChange` — that is a render-phase update on somebody else's
  component. Those notifications are deferred to a macrotask; the fence
  has already made the *rendering* boundary correct, so the deferral only
  reaches boundaries that were not rendering.

  ## What is deliberately NOT here (the hard fences)

  No compiler and no analyzer: bodies are ordinary functions and hiccup
  is interpreted by the shared codec at runtime. No dual mode: one shell,
  one index, one read path — the two HD-002 product tiers ([[use-subs]]
  and [[sub]]) differ in *where the author writes the read* and in the
  provenance the index records, not in the machinery underneath. No
  ViewCell-class object graph: a boundary's exclusive retention is one
  registration, its index memberships, and React's own two hook cells —
  the inventory is [[retained-inventory]], and it is a witness rather
  than a claim. No candidate ledger: an edge set is *replaced* wholesale
  at commit — the registration's identity changes with the key set, so
  React's own subscribe/cleanup pair performs the diff — and no per-read
  record survives the commit that consumed it.

  Codec caching is the only accelerant (HD-004). Nothing here holds a
  node reference, plans a hole, or writes the DOM; those are Arm 2's."
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
;; The render context — ambient for one body's dynamic extent (HD-020(a))
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *render*
  "The rendering boundary's context: the resolved frame keyword and the
  read provenance. nil outside a render, which is what makes `(sub …)`
  outside a boundary a loud error rather than a silent read of whichever
  frame happened to be ambient."
  nil)

(defn rendering?
  "Is a boundary body running right now?"
  []
  (some? *render*))

;; ---------------------------------------------------------------------------
;; Frame-locked ops, resolved once per frame and not once per boundary
;; ---------------------------------------------------------------------------

(defonce ^:private !frame-ops (atom {}))

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

(defn forget-frame-ops!
  "Drop the memoised bundle for `frame-kw` (or all of them). A destroyed
  and re-created frame is a new incarnation, and a captured bundle is
  pinned to the old one."
  ([] (reset! !frame-ops {}) nil)
  ([frame-kw] (swap! !frame-ops dissoc frame-kw) nil))

;; ---------------------------------------------------------------------------
;; Key cells — one per unique (frame, query), shared by every reader
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
  "A cell with no committed reader is reaped one macrotask later.

  Two callers, one rule. A cell built by a render that never commits has
  no reader and no owner — the reaper is what releases its `+1`, which is
  why an abandoned render leaks nothing even though acquisition is
  commit-owned. A cell whose last reader unmounts is given the same
  macrotask of grace, so a keyed reorder that unmounts and remounts a row
  within one turn reuses the reaction instead of rebuilding it."
  [cell]
  (js/setTimeout (fn [] (when (and (zero? (.-refs cell)) (not (.-disposed cell)))
                          (dispose-cell! cell)))
                 0)
  nil)

(defn- cell-for!
  "The cell for `[frame-kw query-v]`, built on first read. Building takes
  the durable `+1` on the sub-cache entry and attaches the one watch that
  turns the sub layer's equality cutoff into this arm's dirty set."
  [frame-kw query-v]
  (let [sub-key [frame-kw query-v]]
    (or (get @!cells sub-key)
        (let [reaction (subs/subscribe query-v {:frame frame-kw})
              wk       (keyword "rf-hicasso-arm1"
                                (str "w" (vswap! !watch-counter inc)))
              cell     #js {"subKey"   sub-key
                            "frameKw"  frame-kw
                            "queryV"   query-v
                            "reaction" reaction
                            "watchKey" wk
                            "epoch"    0
                            "refs"     0
                            "disposed" false}]
          (when (some? reaction)
            (add-watch reaction wk (fn [_ _ _ _] (mark-dirty! cell))))
          (swap! !cells assoc sub-key cell)
          (arm-cell-reaper! cell)
          cell))))

(defn- acquire! [cell]
  (set! (.-refs cell) (inc (.-refs cell)))
  cell)

(defn- release! [cell]
  (set! (.-refs cell) (dec (.-refs cell)))
  (when (<= (.-refs cell) 0) (arm-cell-reaper! cell))
  nil)

(defn- epoch-sum
  "The snapshot React compares. Epochs are monotone, so the sum is
  monotone, so `Object.is` on it is a correct change test."
  [sub-keys]
  (let [cells @!cells]
    (reduce (fn [acc k] (if-some [c (get cells k)] (+ acc (.-epoch c)) acc))
            0
            sub-keys)))

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
;; The two product read tiers (HD-002)
;; ---------------------------------------------------------------------------

(defn- read-key!
  "Read one query for the rendering boundary: the value from the shared
  cell, the edge into this render's collector."
  [query-v]
  (let [r *render*]
    (when (nil? r)
      (fail! :rf.error/hicasso-sub-outside-render
             're-frame.bench.hicasso.arm1.runtime/read-key!
             (str "A subscription read " (pr-str query-v)
                  " happened outside a boundary render. `sub` and `use-subs` "
                  "are legal only inside a defview body; `subscribe-once` is "
                  "the sanctioned snapshot for handler and utility code.")
             :read-inside-a-boundary-body
             {:query-v query-v}))
    (let [cell (cell-for! (.-frame r) query-v)]
      (index/read! (.-subKey cell))
      (when-some [reaction (.-reaction cell)] @reaction))))

(defn sub
  "**Tier 3, the ambient collector** — the challenger, ridden hardest. A
  plain tracked read, legal anywhere in a body: conditionals, loops,
  helpers. The edge is *recorded* by the collector rather than declared,
  and the recorded set is what the commit installs."
  [query-v]
  (when-some [r *render*] (set! (.-collectorTier r) true))
  (read-key! query-v))

(defn use-subs
  "**Tier 2, grouped — the product default.** One fixed site receiving the
  complete query collection, returning the snapshot the body destructures.
  The edges are *declared*: they are the map's values, so a boundary's
  edge set is a function of its declaration and not of its control flow.

      (let [{:keys [todo editing?]}
            (use-subs {:todo     [:todo/by-id id]
                       :editing? [:todo.ui/editing? id]})]
        …)

  The hook count does not move with the collection's size — which is the
  whole of why this tier is the default and the scalar per-read spine is
  a comparator only."
  [query-map]
  (when-some [r *render*] (set! (.-groupedTier r) true))
  (reduce-kv (fn [m alias query-v] (assoc m alias (read-key! query-v)))
             {}
             query-map))

;; ---------------------------------------------------------------------------
;; The shell's subscribe closure, cached by the value it closes over
;; ---------------------------------------------------------------------------

(defonce ^:private !closures (atom {}))

(defn- reap-closures! [sub-keys]
  (js/setTimeout (fn []
                   (when-some [c (get @!closures sub-keys)]
                     (when (zero? (.-refs c)) (swap! !closures dissoc sub-keys))))
                 0)
  nil)

(defn- make-subscribe
  "React's `subscribe`, as a pure function of the read set.

  Everything commit-owned lives in here: the boundary's registration is
  minted from React's own `onStoreChange`, the index learns the boundary
  and its edges, and each key cell takes its committed reference. The
  returned cleanup is the exact inverse and is the only place edges and
  references are released, so teardown is symmetric with mount whatever
  React did with the renders in between.

  The cells are re-resolved here rather than carried from the render: a
  cell the render built may have been reaped in the window before the
  commit, and the committed reference must be taken on the cell that is
  actually live. The registration then holds exactly the cells it
  acquired, so its cleanup cannot release a successor's."
  [sub-keys]
  (fn subscribe [on-store-change]
    (let [reg   #js {"subKeys" sub-keys "notify" on-store-change}
          cells (mapv (fn [k] (acquire! (cell-for! (nth k 0) (nth k 1)))) sub-keys)]
      (unchecked-set reg "cells" cells)
      (index/mount! reg)
      (index/record-reads! reg sub-keys)
      (when-some [c (get @!closures sub-keys)]
        (set! (.-refs c) (inc (.-refs c)))
        (set! (.-acquired c) true))
      (fn unsubscribe []
        (set! (.-notify reg) nil)
        (index/unmount! reg)
        (doseq [cell cells] (release! cell))
        (when-some [c (get @!closures sub-keys)]
          (set! (.-refs c) (dec (.-refs c)))
          (when (<= (.-refs c) 0) (reap-closures! sub-keys)))
        nil))))

(defn- closure-for
  "The cached `subscribe` for this read set. Identity-stable while the set
  is unchanged, which is what stops React re-subscribing on an ordinary
  re-render — and different the moment the set changes, which is what
  makes React's own subscribe/cleanup pair perform the edge-set diff.

  Entries are ref-counted by *committed* registrations, never by renders:
  a render increments nothing, so a body that runs ten times before its
  first commit leaves the count at zero, and the creation reaper drops an
  entry no commit ever claimed."
  [sub-keys]
  (if-some [c (get @!closures sub-keys)]
    (.-subscribe c)
    (let [fresh #js {"subscribe" (make-subscribe sub-keys) "refs" 0 "acquired" false}]
      (swap! !closures assoc sub-keys fresh)
      (js/setTimeout (fn []
                       (when-some [c (get @!closures sub-keys)]
                         (when (and (zero? (.-refs c)) (not (.-acquired c)))
                           (swap! !closures dissoc sub-keys))))
                     0)
      (.-subscribe fresh))))

(defn commit-boundary!
  "**The seam React occupies.** Hand a boundary's read set and a notifier
  to the same `subscribe` closure `useSyncExternalStore` would call, and
  get back the same cleanup React would hold.

  It exists because the commit path, the index wiring and the
  zero-leaked-reference assertion are the parts of this arm most worth
  proving cheaply and deterministically — and every one of them is
  answerable without a browser, a root, or a render. The DOM suites then
  prove that React drives *this* seam, rather than re-proving what the
  seam does."
  [reads notify]
  ((closure-for reads) notify))

;; ---------------------------------------------------------------------------
;; The body run, and the generation fence
;; ---------------------------------------------------------------------------

(def ^:private max-fence-retries
  "A body is re-run once per commit that landed inside it. Three is a
  ceiling, not a budget: a fourth commit arriving inside three
  consecutive body runs is a write loop, and failing loudly beats
  spinning."
  3)

(defn- run-once [frame-kw body-fn props]
  (let [collector (volatile! #{})
        ctx       #js {"frame" frame-kw "collectorTier" false "groupedTier" false}]
    (binding [*render*          ctx
              index/*collector* collector]
      (let [element (intent/with-frame
                      (fn [event] (dispatch! frame-kw event))
                      (fn [] (codec/as-element (body-fn props))))]
        #js [element @collector (.-collectorTier ctx) (.-groupedTier ctx)]))))

(defn render-body
  "Run a boundary body under the generation fence. Returns
  `#js [element read-set collector? grouped?]`.

  The fence is the loop: capture the generation, run the body, and if a
  commit landed while it ran, run it again against the newer commit. All
  of a pass's reads therefore observe one commit — the invariant-5
  preservation architecture.md asks of this arm, and the thing the
  staged-stale witness stages."
  [frame-kw body-fn props]
  (loop [attempt 0]
    (let [before (generation)
          out    (run-once frame-kw body-fn props)]
      (cond
        (= before (generation))       out
        (< attempt max-fence-retries) (recur (inc attempt))
        :else
        (fail! :rf.error/hicasso-generation-fence-exhausted
               're-frame.bench.hicasso.arm1.runtime/render-body
               (str "A boundary body observed a new commit on each of "
                    (inc max-fence-retries) " consecutive runs. A body that "
                    "writes on every render cannot be fenced; move the write "
                    "out of the render.")
               :move-the-write-out-of-the-render
               {:frame frame-kw :generation (generation)})))))

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
        out      (render-body frame-kw body-fn props)
        reads    (aget out 1)
        snapshot (fn [] (epoch-sum reads))]
    (react/useSyncExternalStore (closure-for reads) snapshot snapshot)
    (aget out 0)))

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
     :what  "one JS object: the sub-key set (shared with the closure cache), React's onStoreChange, and the acquired cell vector"}
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
    {:token :subscribe-closure
     :what  "one subscribe closure per distinct read SET, shared by every boundary with that set, ref-counted and reaped"}
    {:token :frame-ops
     :what  "one capture-frame bundle per frame"}
    {:token :codec-cache
     :what  "the codec's tag and prop caches, per distinct literal in the build"}]
   :absent
   [{:token :use-ref   :what "no useRef anywhere in the shell (HD-020(b))"}
    {:token :use-state :what "no per-instance render-phase state of any kind"}
    {:token :view-cell :what "no per-boundary object graph, reaction, watcher or scheduler"}
    {:token :candidate-ledger
     :what  "no per-read record survives the commit that consumed it"}]})

(defn stats
  "What the witnesses read: live cells, live boundaries, cached closures,
  the generation, and the codec caches."
  []
  (let [idx (index/snapshot)]
    {:cells      (count @!cells)
     :cell-refs  (reduce + 0 (map (fn [[_ c]] (.-refs c)) @!cells))
     :boundaries (count (:live idx))
     :edges      (reduce + 0 (map (fn [[_ v]] (count v)) (:b->subs idx)))
     :closures   (count @!closures)
     :generation (generation)
     :frames     (count @!frame-ops)
     :codec      (codec/cache-sizes)}))

(defn residue
  "What must be zero after a clean teardown. `:cell-refs` is the standing
  zero-leaked-subscription-ref-counts assertion; `:boundaries` and
  `:edges` are the index's half of it."
  []
  (select-keys (stats) [:cells :cell-refs :boundaries :edges :closures]))

(defn reset-runtime!
  "Drop every cell, every edge, every cached closure and every frame
  bundle. The root-teardown and test-fixture door; disposing each cell
  releases its sub-cache reference, so this is the leak check's reset
  rather than a way to hide one."
  []
  (doseq [[_ cell] @!cells] (dispose-cell! cell))
  (reset! !cells {})
  (reset! !closures {})
  (vreset! !dirty #{})
  (vreset! !deferred #{})
  (vreset! !batching false)
  (vreset! !generation 0)
  (index/reset-index!)
  (forget-frame-ops!)
  nil)
