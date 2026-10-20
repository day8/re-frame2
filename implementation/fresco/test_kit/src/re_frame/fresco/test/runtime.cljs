(ns re-frame.fresco.test.runtime
  "THE TEST KIT'S DOOR ONTO THE RUNTIME — what a witness reads off the
  collector's tables, and the one seam it drives.

  Every reader here is a pure read of state `re-frame.fresco.impl.collector`
  owns and exposes for exactly this purpose — `!cells`, `!entries`,
  `rstate` and its reap horizon — plus `re-frame.fresco.impl.frames`'s op
  table and the codec's cache sizes. A consumer's bundle never runs one,
  so they live in the kit rather than beside the tables they count, and a
  witness reading the runtime reaches it through the kit, as
  `re-frame.fresco.test` and `re-frame.fresco.test.mounted` do. This
  namespace lives in the kit's own source root (`fresco/test_kit/src`),
  and no shipping namespace requires it, so nothing a shipped page runs
  reaches it.

  Four kinds of door:

  - **The supported seam** — [[render-and-commit!]], a boundary rendered
    and committed with no React root, and [[reset-bracket]], the `:each`
    fixture that reads residue BEFORE it resets. A test that needs live
    boundaries and a clean runtime needs these two and no
    `re-frame.fresco.impl.*` namespace.
  - **The census** — [[stats]], [[residue]] and the settling point
    [[quiesced!]] that makes a residue BASELINE honest.
  - **The cell and entry witnesses** — [[cell-reaction]], [[cell-readers]],
    [[boundary-reads]], [[reads-of]], [[snapshot-of]].
  - **The counter and the ledger** — [[body-runs]] / [[reset-body-runs!]]
    over the runtime's always-on body-run counter, and
    [[shell-hook-ledger]], the shell's declared hook calls the
    dispatcher-level witness counts against.

  [[render-and-commit!]] is a composition of the collector's own commit
  seam — `collector/render-body`, `collector/last-reads` and
  `collector/commit-boundary!`, the three calls that take React's place —
  which stays on the collector because it is the runtime's seam rather
  than an instrument."
  (:require [cljs.test :as t :include-macros true]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.frames :as rf.fresco.impl.frames]
            [re-frame.fresco.impl.generation :as rf.fresco.impl.generation]))

;; ---------------------------------------------------------------------------
;; What a DOM comparison must not see
;; ---------------------------------------------------------------------------

(def annotation-attributes
  "Spec 006's two dev-mode view annotations, which no comparison in this
  kit may take into account.

  `data-rf2-source-coord` and `data-rf-view` are stamped on every
  `defview` boundary's rendered root in a dev build — the only build this
  kit runs in — and they name the DECLARATION rather than anything the
  page said. A reference tree and a candidate tree are routinely two
  differently-named views rendering the SAME page, so left in they differ
  on every compared boundary. `canonical-dom` would then answer *not
  equal* for two pages that are identical, and `mounted/shadow!` would
  report `data-rf-view` as the first difference while the drift the
  author planted goes unmentioned — a red that is worse than a miss,
  because it looks like an answer.

  It lives HERE, in the namespace both comparison doors already require,
  because the same two names spelled in two files is the drift this kit
  exists to catch in other people's code."
  #{"data-rf2-source-coord" "data-rf-view"})

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(defn stats
  "What the witnesses read: live cells, live boundaries, cached read-set
  entries, the generation, the frame rows, and the codec caches.

  `:cell-refs` and `:edges` are **one number**, counted once, because one
  reader slot is both the reference and the edge — the fusion, stated in
  the instrument rather than argued in a docstring. `:boundaries` counts
  the distinct registrations holding at least one such slot; a boundary
  whose body read nothing retains nothing in this table, so it is
  correctly absent (its read-set entry is still counted by `:entries`)."
  []
  (let [cells       @rf.fresco.impl.collector/!cells
        memberships (reduce-kv (fn [acc _ ^js c] (+ acc (alength (.-readers c)))) 0 cells)
        boundaries  (reduce-kv (fn [acc _ ^js c] (into acc (.-readers c))) #{} cells)]
    {:cells      (count cells)
     :cell-refs  memberships
     :edges      memberships
     :boundaries (count boundaries)
     :entries    (reduce + 0 (map (fn [[_ v]] (count v)) @rf.fresco.impl.collector/!entries))
     :generation (rf.fresco.impl.generation/generation)
     :frames     (count @rf.fresco.impl.frames/!frame-ops)
     :codec      (rf.fresco.impl.codec/cache-sizes)}))

(defn residue
  "What must be zero after a clean teardown. `:cell-refs` is the standing
  zero-leaked-subscription-ref-counts assertion; `:boundaries` and
  `:edges` are the dependency edges' half of it — read off the same
  memberships, which is why a leak cannot show in one and hide in the
  other."
  []
  (select-keys (stats) [:cells :cell-refs :boundaries :edges :entries]))

(defn quiesced!
  "A promise that resolves once the runtime's reapers are idle — **its own
  settling point, and the only honest place to take a [[residue]]
  BASELINE**.

  One macrotask is not that point. `collector/entry-reap-horizon-ms` is
  deliberately outside a bare `setTimeout 0`, so a residue read one
  macrotask after an unclaimed render still counts entries the runtime
  is about to drop. This waits strictly past that horizon and then keeps
  waiting while either reap queue holds an armed timer — a drain re-arms
  for what rode in after its own timer, and the platform may clamp that
  timer past the item's horizon — so it observes the runtime rather than
  modelling its clock. The horizon itself stays a margin no caller may
  rely on: this promise says only *wait for me*, never *here is my
  number*."
  []
  (js/Promise.
    (fn [resolve]
      ((fn settle []
         (js/setTimeout (fn []
                          (if (rf.fresco.impl.collector/reapers-armed?) (settle) (resolve nil)))
                        (inc rf.fresco.impl.collector/entry-reap-horizon-ms)))))))

;; ---------------------------------------------------------------------------
;; The cell and entry witnesses
;; ---------------------------------------------------------------------------

(defn cell-reaction
  "The subscription `sub-key`'s cell currently derives through, or nil —
  either because nothing holds the key, or because the substrate disposed
  the reaction and `collector/invalidate-cell!` dropped the reference.
  The failure the disposal rows pin is what a HELD container answers
  after its disposal, so they have to be able to hold it."
  [sub-key]
  (some-> ^js (get @rf.fresco.impl.collector/!cells sub-key) (.-reaction)))

(defn cell-readers
  "The registrations currently reading `sub-key` — the cell's own reader
  list, which is the whole of the fused table's reverse edge — answered
  as a SNAPSHOT: the live array is cloned before it is wrapped, so a
  caller may hold the result across a mount, an unmount or a remount and
  read back what it captured.

  **The clone is load-bearing and `vec` alone is not it.** On an array,
  `vec` aliases rather than copies (below length 32 the vector's tail IS
  the array handed in), and the collector `.push`es and `.splice`s that
  array in place — so an unclosed result would be not stale but
  INCOHERENT, answering one length to `count` and another to `reduce`. A
  baseline that mutates into the result is a witness that cannot see a
  leak."
  [sub-key]
  (if-some [^js c (get @rf.fresco.impl.collector/!cells sub-key)]
    (vec (aclone (.-readers c)))
    []))

(defn boundary-reads
  "The sub-key set `reg` reads — the fused table's `edges-of`. A field
  read rather than a lookup: the forward edge lives on the registration
  (`.-reads`, the read-set entry's own key set, shared by reference), so
  the table carries no separate structure for it."
  [^js reg]
  (.-reads reg))

(defn reads-of
  "An entry's sub-key set."
  [^js entry]
  (.-set entry))

(defn snapshot-of
  "The number React stores for a boundary with this read set, and the one
  it re-reads after `subscribe` to decide whether the store moved under
  the render. Reading it is the witness's way of performing React's own
  `checkIfSnapshotChanged` without a browser."
  [^js entry]
  ((.-snapshot entry)))

;; ---------------------------------------------------------------------------
;; The counter and the ledger
;; ---------------------------------------------------------------------------

(defn body-runs
  "How many boundary bodies the runtime has run since the process started
  or since the last [[reset-body-runs!]].

  The counter is the collector's and is ALWAYS ON: the package's builds
  are `:advanced` with `goog.DEBUG false`, where a debug-gated instrument
  is dead code, and its price is one integer increment on an object that
  already exists. It counts REAL runs — the bump is inside `run-once`,
  below React's comparator, so a `React.memo` bail-out shows up as an
  increment that did not happen, and the generation fence's re-run of a
  body shows up as one that did. Monotone: witnesses take a DELTA across
  the thing they are measuring, which is why `collector/reset-runtime!`
  deliberately leaves it alone."
  []
  (.-bodyRuns rf.fresco.impl.collector/rstate))

(defn reset-body-runs!
  "Zero [[body-runs]]. Explicit, and not part of `collector/reset-runtime!`."
  []
  (set! (.-bodyRuns rf.fresco.impl.collector/rstate) 0)
  nil)

(def shell-hook-ledger
  "The shell's declared hook calls, in call order — the ≤2-hook budget
  (HD-020) as data. `hook_budget_cljs_test` counts the calls React's own
  dispatcher received against this, so a third hook appearing in the
  shell fails a test rather than a review."
  [:use-context/frame :use-sync-external-store/subscription-epoch])

;; ---------------------------------------------------------------------------
;; The supported seam — a boundary without React, and the bracket around it
;; ---------------------------------------------------------------------------

(defn render-and-commit!
  "Run `body` as one boundary in `frame-kw` and commit it in React's
  place, answering what a test asserts on:

      {:element  the React element the body rendered
       :reads    the set of `[frame-kw query-v]` sub-keys it read
       :release  the cleanup React would hold — call it to take the
                 boundary down}

      (let [{:keys [reads release]}
            (rt/render-and-commit! ::app (fn [_] (h/sub [:todo/count]) nil))]
        (is (= #{[::app [:todo/count]]} reads))
        (release))

  `body` is a body function or a `defview` head; a head runs the body it
  was minted from, as `re-frame.fresco.test/tree` does, so it needs a
  development build. `props` defaults to `{}`.

  The commit is the `subscribe` closure `useSyncExternalStore` would
  call, so the boundary is registered, its cells acquired and its edges
  drawn exactly as a mounted one's are — in Node, with no root. It is
  handed a notifier that does nothing: the boundary does not re-render
  when a read moves, so a test that wants the new value renders again.

  A boundary nobody released is residue, and [[reset-bracket]] fails the
  test that left it."
  ([frame-kw body] (render-and-commit! frame-kw body {}))
  ([frame-kw body props]
   (let [body-fn (if (rf.fresco.impl.codec/boundary-head? body)
                   (rf.fresco.impl.codec/retained-body body)
                   body)
         element (rf.fresco.impl.collector/render-body frame-kw body-fn props)
         entry   (rf.fresco.impl.collector/last-reads)]
     {:element element
      :reads   (reads-of entry)
      :release (rf.fresco.impl.collector/commit-boundary! entry (fn []))})))

(defn- leaked
  "The counters of a [[residue]] reading that are not zero, or nil."
  [reading]
  (not-empty (into {} (filter (fn [[_ n]] (pos? n))) reading)))

(defn- check-then-reset!
  "Wait for quiescence, REPORT what the runtime still holds, and only then
  reset it. Answers a promise of the reading.

  The order is the whole of the instrument: `collector/reset-runtime!`
  empties the very tables [[residue]] reads, so a reset taken first
  answers clean whether or not anything was released."
  []
  (.then (quiesced!)
         (fn [_]
           (let [reading (residue)
                 l       (leaked reading)]
             (t/do-report
               (if l
                 {:type     :fail
                  :message  (str "the runtime still held residue after quiescence, "
                                 "read before the bracket's reset: "
                                 (pr-str l) ". A boundary was committed and "
                                 "never released — call the :release "
                                 "render-and-commit! answered — or a "
                                 "subscription outlived the test.")
                  :expected '(zero? residue)
                  :actual   l}
                 {:type     :pass
                  :message  "the runtime held no residue after quiescence"
                  :expected '(zero? residue)
                  :actual   reading}))
             (rf.fresco.impl.collector/reset-runtime!)
             reading))))

(def reset-bracket
  "**The reset bracket** — a `cljs.test` `:each` fixture, in map form.

      (use-fixtures :each rt/reset-bracket)

  Before each test it resets the runtime, so the test starts from empty
  tables. After it, it waits for [[quiesced!]], reads [[residue]], fails
  the test through `cljs.test/do-report` if any counter is above zero,
  and only THEN resets. Reading first is what lets it see a leak: a
  reset empties the tables the reading counts, so a bracket that reset
  before it checked would pass every test.

  A map because the check waits on the runtime's reapers, and `cljs.test`
  runs only a map fixture's half asynchronously. It does not mix with
  function fixtures in one namespace, so build any other fixture there
  in map form — `re-frame.test-support/make-reset-runtime-fixture` takes
  `:async? true` — and list this one AFTER it: `:after` halves run in
  reverse, so the check then runs before that fixture's teardown can
  destroy the frames the residue lives in.

  The failure is reported after the test's own reporting has closed, so
  it names no test; the message names what leaked."
  {:before (fn [] (rf.fresco.impl.collector/reset-runtime!))
   :after  (fn []
             (t/async done
               (.then (check-then-reset!) (fn [_] (done)))))})
