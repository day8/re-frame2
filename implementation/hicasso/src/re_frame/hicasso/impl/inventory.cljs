(ns re-frame.hicasso.impl.inventory
  "WHAT THE RUNTIME RETAINS — the declared census and the measured one,
  in one file so they can be read against each other (rf2-hic-009).

  [[retained-inventory]] is the DECLARATION: every boundary-exclusive
  token this arm holds, enumerated rather than asserted, plus the tokens
  that are emphatically shared and the ones that are absent. [[stats]],
  [[entry-buckets]] and [[residue]] are the MEASUREMENT, read off the
  live tables. A heap ladder is only as honest as the agreement between
  those two, and the agreement is only checkable while they sit side by
  side — which is the whole reason this is a namespace rather than a tail
  on the collector.

  Nothing here writes. Every fn is a pure read of state another module
  owns, which is why the collector exposes `!cells`, `!entries` and its
  reap horizon, and `re-frame.hicasso.impl.frames` its op table: an
  instrument that had to be granted a reader fn per number would grow the
  owning file every time somebody wanted to count something new.

  [[quiesced!]] is here rather than beside the reapers it waits on
  because its only purpose is to make a [[residue]] BASELINE honest, and
  a settling point separated from the thing it settles for is a settling
  point that drifts — which is exactly what rf2-981nt was.

  **This file counts what is retained; it does not say why any of it is
  allowed to be one-per-page.** That is a different question with a
  different answer per owner, and it has its own record:
  `docs/design/hicasso/product/globals.md` enumerates every module-level
  mutable owner in the runtime with its disposition, and states the
  searches that produce the roster. Read it before scoping one of these
  tables to a root — three of them are already frame-scoped BY KEYING,
  and at least one (`impl.overlay`'s anchor counter) would break if it
  were scoped to anything narrower than the page (rf2-hic-017)."
  (:require [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.generation :as generation]))

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
     :what  "one JS object: the read set (shared with its entry, never copied — and since rf2-dabt3 this IS the forward edge, so no map holds a second copy of it), React's onStoreChange, and the acquired cell vector"}
    {:token :cell/reader-membership
     :what  "one slot in each read key's cell-local reader list — the key's reverse edge and this boundary's reference to the key's cell, which are ONE membership since rf2-dabt3. It replaces the retired index's :index/b->subs entry, its :index/sub->bs membership, and the singleton reader SET the second map retained per key at fan-out 1"}
    {:token :react/use-sync-external-store
     :what  "React's own hook cell for the one subscription hook"}
    {:token :react/use-context
     :what  "React's own hook cell for the frame hook, plus the fiber's context dependency record — held by the CONTEXT-fed shell only. The frame-fed variant ([[re-frame.hicasso.impl.collector/mint-frame-prop-view!]]) does NOT hold it: its frame arrives as the element prop `rfFrame`, which costs one slot in every boundary element's props object instead. A ladder reading this table for that variant must subtract this token and add that slot (rf2-2rtt6.39, priced on rf2-2rtt6.72)"}
    {:token :react/memo-fiber
     :what  "one EXTRA React fiber — `mint-view!`'s props-equality bail-out is a `React.memo` carrying a comparator, and a memo with a custom comparator stays a MemoComponent rather than collapsing into React's SimpleMemoComponent, so React holds a wrapper fiber above the component's own (rf2-2rtt6.52)"}
    {:token :read-set-entry
     :what  "one key array, key SET, subscribe and getSnapshot per distinct read SEQUENCE — shared ONLY with boundaries whose sequence is identical, so on the distinct-query rung the ladder is taken on there is one per boundary"}]
   :shared
   [{:token :key-cell
     :what  "one cell + one sub-cache reaction + one reader ARRAY per unique (frame, query), however many boundaries read it — the array is the one container the fusion retains, in place of a persistent map entry AND a persistent set per key"}
    {:token :scratch
     :what  "ONE module-level array for the whole runtime, reset by overwrite; its capacity is the high-water read count"}
    {:token :frame-ops
     :what  "one capture-frame bundle and one ambient dispatch per frame"}
    {:token :codec-cache
     :what  "the codec's tag and prop caches, per distinct literal in the build"}]
   :absent
   [{:token :use-ref   :what "no useRef anywhere in the shell (HD-020(b))"}
    {:token :use-state :what "no per-instance render-phase state of any kind IN THE SHELL. The one useState on an Arm 1 page belongs to `front.controlled`'s composition shadow and is a controlled TEXT ELEMENT's, one per field — never a boundary's, and priced per field on `shapes/hook_budget_dom_cljs_test` (rf2-digtt)"}
    {:token :view-cell :what "no per-boundary object graph, reaction, watcher or scheduler"}
    {:token :candidate-ledger
     :what  "one scratch buffer, never two; nothing keyed by render, attempt, lane or generation; no per-read object; no commit-phase deref"}]})

(defn cell-reaction
  "The subscription `sub-key`'s cell currently derives through, or nil —
  either because nothing holds the key, or because the substrate disposed
  the reaction and [[re-frame.hicasso.impl.collector/invalidate-cell!]] dropped the reference.

  A witness reader, and the one the rf2-2rtt6.44 rows need: the failure
  they pin is what a HELD container answers after its disposal, so they
  have to be able to hold it."
  [sub-key]
  (some-> ^js (get @collector/!cells sub-key) (.-reaction)))

(defn cell-readers
  "The registrations currently reading `sub-key` — the cell's own reader
  list, which is the whole of the fused table's reverse edge.

  A witness reader, answered as a SNAPSHOT: the cell's live array is
  cloned before it is wrapped, so a caller may hold the result across a
  mount, an unmount or a remount and read back what it captured. That is
  the guarantee the reader-counting rows assert against, and it replaces
  the retired index's `readers-of`.

  **The clone is load-bearing and `vec` alone was not it (rf2-0oy4).**
  `cljs.core/vec` says so in its own docstring — *\"JavaScript arrays will
  be aliased and should not be modified\"* — because on an array it calls
  `PersistentVector.fromArray` with `no-clone` true, and below length 32,
  which is every fan-out this table sees, the vector's TAIL *is* the array
  handed in. [[re-frame.hicasso.impl.collector]] then `.push`es and
  `.splice`s that array in place, and the result is not a stale value but
  an INCOHERENT one: `count` and `nth` stay bounded by the `cnt` frozen at
  construction while `reduce` walks each chunk by the tail's live
  `alength`, so the same vector answers 2 to `count` and `[b b]` to
  `mapv`. A baseline that mutates into the result is a witness that cannot
  see a leak, and by symmetry a leaking runtime that reads clean — which
  is how this presented, as rf2-vsgq's HMR baseline.
  `re-frame.hicasso.inventory-snapshot-cljs-test` pins it."
  [sub-key]
  (if-some [^js c (get @collector/!cells sub-key)]
    (vec (aclone (.-readers c)))
    []))

(defn boundary-reads
  "The sub-key set `reg` reads — the fused table's `edges-of`.

  It is a field read rather than a lookup, and that is the point: the
  forward edge was always on the registration (`.-reads`, the read-set
  entry's own key set, shared by reference), which is why retiring the
  index cost no structure (rf2-dabt3)."
  [^js reg]
  (.-reads reg))

(defn stats
  "What the witnesses read: live cells, live boundaries, cached read-set
  entries, the generation, and the codec caches.

  `:cell-refs` and `:edges` are **one number**, counted once, because one
  reader slot is both the reference and the edge — the fusion, stated in
  the instrument rather than argued in a docstring. `:boundaries` counts
  the distinct registrations holding at least one such slot; a boundary
  whose body read nothing retains nothing in this table, so it is
  correctly absent (its read-set entry is still counted by `:entries`)."
  []
  (let [cells       @collector/!cells
        memberships (reduce-kv (fn [acc _ ^js c] (+ acc (alength (.-readers c)))) 0 cells)
        boundaries  (reduce-kv (fn [acc _ ^js c] (into acc (.-readers c))) #{} cells)]
    {:cells      (count cells)
     :cell-refs  memberships
     :edges      memberships
     :boundaries (count boundaries)
     :entries    (reduce + 0 (map (fn [[_ v]] (count v)) @collector/!entries))
     :generation (generation/generation)
     :frames     (count @frames/!frame-ops)
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
  (let [sizes (map (fn [[_ v]] (count v)) @collector/!entries)]
    {:buckets (count sizes) :max-bucket (reduce max 0 sizes)}))

(defn residue
  "What must be zero after a clean teardown. `:cell-refs` is the standing
  zero-leaked-subscription-ref-counts assertion; `:boundaries` and
  `:edges` are the dependency edges' half of it — now read off the same
  memberships, which is why a leak cannot show in one and hide in the
  other."
  []
  (select-keys (stats) [:cells :cell-refs :boundaries :edges :entries]))

(defn quiesced!
  "A promise that resolves once every reaper armed before this call has
  run — **the runtime's own settling point, and the only honest place to
  take a [[residue]] BASELINE**.

  One macrotask is not that point. [[re-frame.hicasso.impl.collector/entry-reap-horizon-ms]] is
  deliberately outside a bare `setTimeout 0`, so a residue read one
  macrotask after an unclaimed render still counts entries the runtime
  is about to drop. A baseline taken there is a state the runtime never
  returns to, and an instrument gating on residue EQUALITY against it
  throws on the first arm whose row outlives the horizon — which is
  exactly what rf2-981nt was: `read_profile_app`'s phase B baselined six
  entries at ~0 ms and found five thereafter, every run, byte-identical.

  Exported so a caller settles against the runtime's own horizon rather
  than against a copy of it, because the copy is what drifted. Nothing
  here becomes a contract: [[re-frame.hicasso.impl.collector/entry-reap-horizon-ms]] stays a margin no
  caller may rely on, and this promise says only *wait for me*, never
  *here is my number*."
  []
  (js/Promise.
    (fn [resolve]
      ;; Strictly past the horizon, so a timer armed here expires after
      ;; every reaper armed before it — the cell reapers (0 ms) included.
      (js/setTimeout (fn [] (resolve nil)) (inc collector/entry-reap-horizon-ms)))))
