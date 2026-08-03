(ns re-frame.bench.hicasso.front.sub-index
  "THE SUBSCRIPTION→BOUNDARY INDEX — deliverable 2 of the Wave-1 shared
  front half (rf2-2rtt6.8), graduated out of the local `spike-01` pure
  model into the tracked bench/test tree as HD-017 directs.

  ## What it is

  One question, answered in one place: *given the set of subscription
  keys a commit dirtied, which boundaries must re-run?* Everything else
  in this namespace exists to keep that answer correct as boundaries
  mount, read, re-run with a different read set, and unmount.

  The index is **global maps, not reactions**. Two maps live in a single
  atom:

      :sub->bs   sub-key      -> #{boundary-id}    the reverse edges
      :b->subs   boundary-id  -> #{sub-key}        the forward edges

  Shared structure, not per-boundary object fan-out. A boundary's
  membership in the index is one entry in `:b->subs` and one membership
  per edge in `:sub->bs` — there is no per-boundary reaction, watcher, or
  cell here, and a ViewCell-class object graph appearing in this file is
  the explicit failure marker for the whole programme (architecture.md,
  Arm 1).

  ## Liveness is the forward-edge entry, and there is no second record

  A boundary is live exactly when `:b->subs` holds an entry for it
  (rf2-ixb92). There used to be a third structure — `:live`, a set of
  mounted boundary ids — and it carried no information the forward edges
  did not already carry: [[mount]] installs `#{}` for a boundary it has
  not seen, [[unmount]] drops the entry, and [[record-reads]] only ever
  writes for a boundary that is already there, so the set was the key set
  of `:b->subs` at every reachable index value. Deriving it costs
  nothing — `contains?` and `count` are the same operations on a
  persistent map as on a persistent set — and it removes both a
  membership per mounted boundary from the retained inventory and a
  second place [[mount]] and [[unmount]] had to stay symmetric in.

  That makes the empty set [[mount]] installs load-bearing rather than
  tidy: it is *the* record that the boundary exists, so a boundary whose
  body read nothing is still live and can still record edges later.
  `liveness-is-the-forward-edge-entry-and-there-is-no-second-record-of-it`
  holds both halves.

  **Nothing in here is reactive.** The index does not watch, subscribe,
  schedule, or notify. It is a pure function of the edges it has been
  told about, and *only the commit applies the dirty set* — [[commit!]]
  is the single door through which a dirty sub-key set becomes a dirty
  boundary set.

  ## The six laws

  architecture.md restates the six laws the spike-01 model proved, and
  they are the acceptance criteria for this namespace. Each has a test
  of its own in `sub_index_laws_cljs_test.cljs`, named for its number:

    1. after mount+read, a commit of that sub dirties that boundary only;
    2. two boundaries sharing a sub both dirty;
    3. unmount removes edges;
    4. a re-run with fewer reads drops edges (the conditional read);
    5. the broad dirty set is the union of all readers of any dirty sub;
    6. an unknown dirty sub yields the empty set — no phantom boundaries.

  ## Two things this adds to the spike model, and why

  The spike was a paper proof of the algebra and threaded its state by
  hand. Two behaviours a runtime needs were therefore never expressed,
  and both are leak classes rather than conveniences:

  - **[[record-reads]] ignores a boundary that is not live.** React can
    abandon a render, and an abandoned render's reads must not resurrect
    the edges [[unmount]] just removed. Without the guard law 3 holds
    only until the next stale body run, which is exactly the class
    HD-002's adjudication clause (a) asks a read mechanism to answer for.
    With it, unmount is final.
  - **[[mount]] is idempotent.** StrictMode mounts twice and HMR
    remounts; neither may clear the edges an already-live boundary has
    recorded. (The spike had this right already; it is restated here
    because it is load-bearing and one `contains?` away from being lost.)

  ## Sub-key identity

  A sub-key is the query vector itself — `(query-id, args)` under
  ordinary value equality, so `[:todo/by-id 7]` and `[:todo/by-id 7]`
  are one key and `[:todo/by-id 8]` is another. Framework-shaped keys
  with map args work by the same rule and are tested. Value-unstable map
  args thrash the index; that is documented and programmer-trusted
  (validation.md), not defended against here.

  ## The evidence sink (HD-005)

  Two lines — a holder and a setter — so dev tooling can attach to the
  dependency index later with no redesign. Detached cost is one deref and
  one nil test at each of the two tap points, and **that is a literal
  count, which it only is because the nil test is the outermost form at
  each tap point**: nothing the sink would have been handed gets built
  when there is no sink. A third line used to own that check — a private
  `evidence!` the tap points called with the event already constructed —
  and the indirection is what hid the cost, charging the detached path one
  event map per boundary per commit at [[record-reads!]] and one per
  commit at [[commit!]], garbage the moment it was made and so invisible
  to a retained-heap ladder (rf2-e3i6y). Factoring the guard back out
  restores the claim's falsity, which is why it reads as duplication here
  and stays. **No evidence subsystem ships**: there is no manifest, no
  registry, no buffering, and the sink is nil until something sets it."
  (:require [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; The evidence sink seam (HD-005)
;; ---------------------------------------------------------------------------

(defonce ^:private !evidence-sink (atom nil))
(defn set-evidence-sink! "Attach (or with nil, detach) the evidence sink." [f] (reset! !evidence-sink f) nil)

;; ---------------------------------------------------------------------------
;; The pure algebra — an index is a value; every op returns the next value
;; ---------------------------------------------------------------------------

(defn empty-index
  "A fresh, empty index value."
  []
  {:sub->bs {}
   :b->subs {}})

(defn mount
  "Register `boundary-id` as live — which, since rf2-ixb92, is exactly
  *give it a forward-edge entry*. The empty set is therefore the whole
  registration and not a convenience: it is what [[live?]] answers from
  and what [[record-reads]] checks before it writes.

  Idempotent, and now by returning the index unchanged rather than by
  rebuilding it: a boundary already in the index keeps the edges it has
  recorded, because StrictMode mounts a boundary twice and HMR remounts
  it, and neither event means its reads went away."
  [idx boundary-id]
  (if (contains? (:b->subs idx) boundary-id)
    idx
    (assoc-in idx [:b->subs boundary-id] #{})))

(defn- drop-edges
  "Remove `boundary-id` from the reverse edges of every key in `sub-keys`,
  dropping a key entirely once its reader set empties — an index that
  retained empty sets would grow without bound across a long session."
  [sub->bs boundary-id sub-keys]
  (reduce (fn [m sub-key]
            (let [readers (disj (get m sub-key #{}) boundary-id)]
              (if (seq readers)
                (assoc m sub-key readers)
                (dissoc m sub-key))))
          sub->bs
          sub-keys))

(defn unmount
  "Drop `boundary-id` and every edge it holds. After this the boundary is
  not live, holds no forward edges, and appears in no key's reader set —
  law 3, and the standing zero-leaked-refcounts assertion depends on it."
  [idx boundary-id]
  (-> idx
      (update :sub->bs drop-edges boundary-id (get-in idx [:b->subs boundary-id] #{}))
      (update :b->subs dissoc boundary-id)))

(defn record-reads
  "Replace `boundary-id`'s edge set with the sub-keys its body just read.

  This is the whole of the edge maintenance: the difference against the
  previous set is added, the difference the other way is dropped, and an
  unchanged read set touches nothing — which is what makes law 4 (the
  conditional read) an edge-set diff rather than a bookkeeping ledger.

  A boundary that is not live is ignored. See the namespace docstring:
  an abandoned render must not resurrect an unmounted boundary's edges.

  **`set` here is a coercion and must never become a copy, and that is a
  measured constraint rather than a stylistic one (rf2-aqgr2).** A caller
  handing over a set — which is what every runtime caller does — hands
  over the set the FORWARD EDGE THEN RETAINS FOR THE LIFE OF THE MOUNT,
  and `cljs.core/set` gives it back unchanged when it is already a
  meta-less set. Replace this call with a rebuild (`(into #{} …)`, a
  transient loop) and the boundary retains a second hash set with
  identical contents: measured on `p0_run.cjs --only ladder`, that costs
  **+46 B/read** on the Reagent segment and **+47 B/read** on the UIx
  segment, donors unmoved, and it would be invisible in every test in the
  suite. The coercion stays for callers that pass a sequence, which is
  the general contract this namespace is written to;
  `the-forward-edge-shares-a-callers-set-rather-than-copying-it` is what
  keeps the sharing from being lost to a tidy-up.

  **Which half of the difference a caller exercises depends on how
  durable its boundary ids are, and that is worth knowing before reading
  a discharge against this function (rf2-2rtt6.47).** A caller that
  re-runs a body under a STABLE id drives both halves. Arm 1's id is the
  registration object React mints per `subscribe`, so a changed read set
  arrives here as a fresh id with `held = #{}`: `added` is the whole read
  set, `dropped` is always empty, and the narrowing is [[unmount]]'s
  drop-everything on the previous registration. Law 4 is therefore proved
  here and, on that wiring, delivered by the `unmount`/`mount` pair. The
  general form stays because this is the shared front half and because
  narrowing it to one caller's wiring would make a second `record-reads`
  for the same id leak edges silently."
  [idx boundary-id read-sub-keys]
  (if-not (contains? (:b->subs idx) boundary-id)
    idx
    (let [reads   (set read-sub-keys)
          held    (get-in idx [:b->subs boundary-id] #{})
          dropped (set/difference held reads)
          added   (set/difference reads held)]
      (-> idx
          (update :sub->bs drop-edges boundary-id dropped)
          (update :sub->bs
                  (fn [m] (reduce (fn [m sub-key] (update m sub-key (fnil conj #{}) boundary-id))
                                  m
                                  added)))
          (assoc-in [:b->subs boundary-id] reads)))))

(defn dirty-boundaries
  "The set of boundaries that must re-run, given the sub-keys a commit
  dirtied. The union of every dirty key's reader set — law 5 — and an
  unknown key contributes nothing, so the answer for a key nobody reads
  is the empty set and never a phantom boundary (law 6)."
  [idx dirty-sub-keys]
  (reduce (fn [acc sub-key] (into acc (get (:sub->bs idx) sub-key)))
          #{}
          dirty-sub-keys))

(defn edges-of
  "The sub-keys `boundary-id` currently reads."
  [idx boundary-id]
  (get-in idx [:b->subs boundary-id] #{}))

(defn readers-of
  "The boundaries currently reading `sub-key`."
  [idx sub-key]
  (get (:sub->bs idx) sub-key #{}))

(defn live?
  "Is `boundary-id` mounted? Answered from the forward edges, because
  holding a forward-edge entry is what being mounted *is* (rf2-ixb92) —
  including the entry that is still `#{}` because the body read nothing."
  [idx boundary-id]
  (contains? (:b->subs idx) boundary-id))

;; ---------------------------------------------------------------------------
;; The global index — one atom, the runtime's door
;; ---------------------------------------------------------------------------

(defonce ^:private !index (atom (empty-index)))

(defn snapshot
  "The current index value. For tests and for the evidence seam's
  consumers; the runtime never reads it."
  []
  @!index)

(defn reset-index!
  "Drop every boundary and every edge. Test-fixture and root-teardown door."
  []
  (reset! !index (empty-index))
  nil)

(defn mount! [boundary-id] (swap! !index mount boundary-id) nil)

(defn unmount! [boundary-id] (swap! !index unmount boundary-id) nil)

(defn record-reads!
  "Apply this render's read set for `boundary-id` and emit the edge
  change on the evidence seam."
  [boundary-id read-sub-keys]
  (let [before (:b->subs @!index)
        after  (:b->subs (swap! !index record-reads boundary-id read-sub-keys))
        held   (get before boundary-id #{})
        reads  (get after boundary-id #{})]
    (when-some [sink @!evidence-sink]
      (when (not= held reads)
        (sink {:event    :edges-changed
               :boundary boundary-id
               :added    (set/difference reads held)
               :dropped  (set/difference held reads)})))
    nil))

(defn commit!
  "**The only door through which a commit becomes re-render work.** Given
  the sub-keys this commit dirtied, return the set of boundaries to
  re-run. Reads the index; never mutates it."
  [dirty-sub-keys]
  (let [dirty (dirty-boundaries @!index dirty-sub-keys)]
    (when-some [sink @!evidence-sink]
      (sink {:event :commit :dirty-subs (set dirty-sub-keys) :dirty-boundaries dirty}))
    dirty))

;; ---------------------------------------------------------------------------
;; The read collector — how a body's reads reach [[record-reads!]]
;; ---------------------------------------------------------------------------

(def ^:dynamic *collector*
  "Bound to a transient-free mutable set for the dynamic extent of one
  boundary body. `nil` outside a render, which is what makes a read
  outside a render detectable rather than silently indexed."
  nil)

(defn read!
  "Record that the running body read `sub-key`. The value is the sub
  layer's business; this records the edge only."
  [sub-key]
  (when-some [c *collector*] (vswap! c conj sub-key))
  nil)

(defn with-reads
  "Run `body-fn` with a fresh collector bound, apply the reads it made to
  `boundary-id`, and return `[result reads]`. This is the front half's
  read-collection shape; an arm's boundary shell calls it once per
  render."
  [boundary-id body-fn]
  (let [c (volatile! #{})
        result (binding [*collector* c] (body-fn))
        reads @c]
    (record-reads! boundary-id reads)
    [result reads]))
