(ns re-frame.bench.hicasso.arm1.cell-table-laws-cljs-test
  "THE SIX INDEX LAWS, against the fused cell table (rf2-2rtt6.8,
  rf2-dabt3).

  architecture.md restates six laws the local `spike-01` pure model
  proved, and HD-017 graduated that model into the tracked bench/test
  tree *with its six-law algebra as the index's unit tests*. This file is
  the descendant of that graduation, renamed rather than discarded when
  the index stopped being a separate structure.

  ## What moved, and why the tests moved with it

  The laws used to be discharged against `front.sub-index`, a pure
  algebra over two process-global maps: `sub-key -> #{boundary}` and
  `boundary -> #{sub-key}`. Both were keyed by the same B·R key space the
  runtime's own cell table is keyed by, so every read paid two persistent
  map entries and — at the fan-out the distinct-query ladder rung
  measures — a singleton set per key holding one pointer. rf2-dabt3
  retired deliverable 2 of the shared front half: the readers live on the
  cell, the forward edge was already on the registration (`.-reads`), and
  the namespace went with them.

  **All six laws are statements about the reverse edge**, so they lost
  their subject entirely rather than partially — and they are ported
  here, one `deftest` per law under its own number, discharged against
  the runtime's own doors rather than against a rebuilt value algebra:
  [[rt/commit-boundary!]] (the seam React occupies), [[rt/dispatch!]]
  (which drives `flush!`), [[rt/stats]] and [[rt/cell-readers]]. That
  seam exists precisely so the commit path is provable without a browser,
  and driving the real thing is what makes these rows evidence rather
  than a restatement.

  The laws, verbatim from architecture.md:

    1. after mount+read, a commit of that sub dirties that boundary only;
    2. two boundaries sharing a sub both dirty;
    3. unmount removes edges;
    4. a re-run with fewer reads drops edges (conditional read);
    5. the broad dirty set is the union of all readers of any dirty sub;
    6. an unknown dirty sub yields the empty set — no phantom boundaries.

  Below the six sit the obligations the laws would silently lose. Three
  of them changed shape with the fusion, and the changes are the point:

  - **Abandoned-render safety is now structural.** The retired index
    needed `record-reads` to ignore a boundary that was not live, because
    a stale body run could otherwise resurrect edges `unmount` had just
    dropped. Here the only write is inside `subscribe`, which React calls
    at commit and nowhere else, so there is no render-phase write to
    guard. It is asserted as a witness rather than as a guard.
  - **Mount idempotence dissolves.** There is no `mount` to be idempotent
    in: a `subscribe` mints a fresh registration and its cleanup releases
    exactly that one. What the old test protected — StrictMode's double
    invoke must not leave residue — lives in the zero-residue witness.
  - **The `identical?`-sharing witness changed subject.** It used to say
    `record-reads` coerces rather than copies its caller's set; it now
    says the registration shares the entry's key set, which is the whole
    reason the fused table stores no forward edge.

  The adapter is UIx's, not `plain-atom`'s, and that is load-bearing:
  plain-atom has no reactivity layer at all, so a subscription under it
  never notifies and every dirty-set assertion below would pass vacuously
  by never firing. That is also why the screen-shaped discharge migrated
  here from `front/dogfood_cljs_test` — it drove the retired index's pure
  algebra directly, and the fused doors need a substrate that moves."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; The map shape, because the residue claims are `async` — a reaper
     ;; horizon is not observable inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rt/reset-runtime!) (rt/set-evidence-sink! nil))}))

(def ^:private frame-id ::cell-table-laws)

(defn- seeded!
  ([] (seeded! 3))
  ([n] (rt/reset-runtime!) (dogfood/make-frame! frame-id n) frame-id))

(defn- key-of [query] [frame-id query])

(defn- render!
  "One body run through the shell's own fence, exactly as `rt/shell` does
  — minus React, which contributes nothing to what is asserted. Answers
  the read-set entry."
  [body-fn]
  (rt/render-body frame-id body-fn {})
  (rt/last-reads))

(defn- mount!
  "One boundary at the seam React occupies: render its body, commit its
  reads, and hand back `{:entry :reg :hits :stop!}`.

  `:reg` is the registration the commit installed, and it is read back
  off an EDGE — the last reader pushed onto the first key's cell —
  because the fused table keeps no registry of live boundaries by design:
  a registration is live exactly while React holds its cleanup."
  [body-fn]
  (let [entry (render! body-fn)
        hits  (volatile! 0)
        stop  (rt/commit-boundary! entry (fn [] (vswap! hits inc)))
        reg   (last (rt/cell-readers (first (rt/reads-of entry))))]
    {:entry entry :reg reg :hits hits :stop! stop}))

(defn- stop-all! [& boundaries]
  (doseq [b boundaries] ((:stop! b)))
  nil)

;; ===========================================================================
;; LAW 1 — after mount+read, a commit of that sub dirties that boundary only
;; ===========================================================================

(deftest law-1-a-committed-sub-dirties-its-own-reader-only
  (seeded! 3)
  (let [row-1  (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))
                                (str (rt/sub [:dogfood/draft 1]))]))
        row-2  (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 2]))]))
        header (mount! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))]))]
    (try
      ;; `edit-draft` moves the draft and nothing else — one dirty key,
      ;; which is what makes this law-1 rather than law-5.
      (rt/dispatch! frame-id [:dogfood/edit-draft 1 "half-typed"])
      (testing "the reader of the dirty sub re-runs"
        (is (= 1 @(:hits row-1))))
      (testing "and nobody else does — not the sibling row, not the header"
        (is (= 0 @(:hits row-2)))
        (is (= 0 @(:hits header))))
      (testing "the dirty key's reader list holds that boundary and no other"
        (is (= [(:reg row-1)] (rt/cell-readers (key-of [:dogfood/draft 1])))))
      (testing "a boundary's second read is an independent edge, not a merge"
        (is (= [(:reg row-1)] (rt/cell-readers (key-of [:dogfood/todo 1]))))
        (is (= [(:reg row-2)] (rt/cell-readers (key-of [:dogfood/todo 2])))))
      (finally (stop-all! row-1 row-2 header)))))

;; ===========================================================================
;; LAW 2 — two boundaries sharing a sub both dirty
;; ===========================================================================

(deftest law-2-a-shared-sub-dirties-every-sharer
  (seeded! 3)
  (let [a (mount! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))]))
        b (mount! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))]))
        c (mount! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))
                           (str (rt/sub [:dogfood/draft 0]))]))]
    (try
      (testing "ONE cell for the shared key, holding all three readers —
               shared structure, not per-boundary fan-out, which is the
               claim the two-global-maps design used to carry and the
               fused table carries with one container fewer"
        (is (= 3 (count (rt/cell-readers (key-of [:dogfood/remaining])))))
        (is (= #{(:reg a) (:reg b) (:reg c)}
               (set (rt/cell-readers (key-of [:dogfood/remaining])))))
        (is (= 2 (:cells (rt/stats))) "remaining, and c's own draft key"))
      (testing "every reader of the shared key runs, and only them"
        (rt/dispatch! frame-id [:dogfood/toggle 1])
        (is (= 1 @(:hits a)))
        (is (= 1 @(:hits b)))
        (is (= 1 @(:hits c))))
      (testing "a key one of them additionally reads dirties only that one"
        (rt/dispatch! frame-id [:dogfood/edit-draft 0 "mine alone"])
        (is (= 1 @(:hits a)))
        (is (= 1 @(:hits b)))
        (is (= 2 @(:hits c))))
      (finally (stop-all! a b c)))))

;; ===========================================================================
;; LAW 3 — unmount removes edges
;; ===========================================================================

(deftest law-3-unmount-removes-every-edge-the-boundary-held
  (async done
    (seeded! 3)
    (let [gone (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                (str (rt/sub [:dogfood/draft 0]))]))
          stay (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
      (is (= 3 (:edges (rt/stats))) "two edges and one, before the departure")
      ((:stop! gone))
      (testing "the shared key keeps the survivor and loses the departed"
        (is (= [(:reg stay)] (rt/cell-readers (key-of [:dogfood/todo 0])))))
      (testing "the key only the departed read has no reader left"
        (is (= [] (rt/cell-readers (key-of [:dogfood/draft 0])))))
      (testing "and the departure is final: a later write reaches the
               survivor and never the departed"
        (rt/dispatch! frame-id [:dogfood/edit-draft 0 "after the exit"])
        (is (= 0 @(:hits gone)))
        (is (= 0 @(:hits stay)))
        (rt/dispatch! frame-id [:dogfood/toggle 0])
        (is (= 0 @(:hits gone)))
        (is (= 1 @(:hits stay))))
      (is (= 1 (:edges (rt/stats))) "one membership survives, and it is the survivor's")
      ;; A cell whose readers all left is REAPED, which is the fused
      ;; counterpart of the old index dropping an emptied reader set
      ;; rather than retaining it — an index that kept empty sets grew
      ;; without bound across a long session, and a table that kept
      ;; readerless cells would too.
      (js/setTimeout (fn []
                       (is (= 1 (:cells (rt/stats)))
                           "the readerless key left the table entirely")
                       ((:stop! stay))
                       (done))
                     8))))

;; ===========================================================================
;; LAW 4 — a re-run with fewer reads drops edges (the conditional read)
;; ===========================================================================

(deftest law-4-a-rerun-with-fewer-reads-drops-the-edges-it-stopped-reading
  (seeded! 3)
  (testing "**Law 4 IS the subscribe/cleanup pair on this wiring, and that
           is not a degenerate case of something more general any more.**
           The boundary id is the registration React mints per
           `subscribe`, so a narrowed read set takes a different entry, a
           different `subscribe` identity and therefore a fresh
           registration; React's own sequence — the previous cleanup,
           then the new subscribe — performs the whole edge-set
           replacement. rf2-2rtt6.47, rf2-dabt3"
    (let [wide (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                (str (rt/sub [:dogfood/todo 1]))]))]
      (is (= 2 (:edges (rt/stats))))
      ((:stop! wide))
      (let [narrow (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
        (testing "the edge the second run did not read is dropped"
          (is (= #{(key-of [:dogfood/todo 0])} (rt/boundary-reads (:reg narrow))))
          (is (= [] (rt/cell-readers (key-of [:dogfood/todo 1]))))
          (rt/dispatch! frame-id [:dogfood/toggle 1])
          (is (= 0 @(:hits narrow))))
        (testing "the edge it did read survives untouched"
          (rt/dispatch! frame-id [:dogfood/toggle 0])
          (is (= 1 @(:hits narrow))))
        ((:stop! narrow)))
      (testing "and a third run that reads it again restores the edge"
        (let [again (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                     (str (rt/sub [:dogfood/todo 1]))]))]
          (is (= 2 (:edges (rt/stats))))
          (rt/dispatch! frame-id [:dogfood/toggle 1])
          (is (= 1 @(:hits again)))
          ((:stop! again))))
      (testing "a re-run that reads nothing leaves the boundary holding no
               edge at all — and holding nothing is what being edgeless
               MEANS here, because the table has no registration record
               beside the memberships"
        (let [entry (render! (fn [_] [:li "static"]))
              stop  (rt/commit-boundary! entry (fn []))]
          (is (= #{} (rt/reads-of entry)))
          (is (= 0 (:edges (rt/stats))))
          (is (= 0 (:boundaries (rt/stats))))
          (stop))))))

;; ===========================================================================
;; LAW 5 — the broad dirty set is the union of all readers of any dirty sub
;; ===========================================================================

(deftest law-5-the-broad-dirty-set-is-the-union-of-the-readers
  (seeded! 3)
  (let [r1  (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))
                             (str (rt/sub [:dogfood/remaining]))]))
        r2  (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 2]))]))
        hdr (mount! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))]))]
    (try
      (testing "**the union does not double-count its overlap.** A toggle
               moves BOTH of r1's keys in one commit, and r1 is notified
               ONCE — the obligation a per-cell reader walk has to carry
               that a single reverse-edge map carried for free"
        (rt/dispatch! frame-id [:dogfood/toggle 1])
        (is (= 1 @(:hits r1)))
        (is (= 1 @(:hits hdr)) "and the other reader of the shared key ran")
        (is (= 0 @(:hits r2)) "and the row that read neither did not"))
      (testing "a commit whose keys nobody shares is the plain union"
        (rt/with-commit (fn []
                          (rt/dispatch! frame-id [:dogfood/edit-draft 1 "a"])
                          (rt/dispatch! frame-id [:dogfood/edit-draft 2 "b"])))
        (is (= 1 @(:hits r1)) "neither draft is read by anybody")
        (is (= 1 @(:hits hdr)))
        (is (= 0 @(:hits r2))))
      (testing "the empty commit dirties nothing"
        (let [g (rt/generation)]
          (rt/with-commit (fn []))
          (is (= g (rt/generation)))
          (is (= 1 @(:hits r1)))))
      (finally (stop-all! r1 r2 hdr)))))

;; ===========================================================================
;; LAW 6 — an unknown dirty sub yields the empty set; no phantom boundaries
;; ===========================================================================

(deftest law-6-an-unknown-dirty-sub-yields-the-empty-set
  (seeded! 3)
  (let [row-1 (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))]))]
    (try
      (testing "a key nobody reads has no reader"
        (is (= [] (rt/cell-readers (key-of [:dogfood/todo 2]))))
        (is (= [] (rt/cell-readers (key-of [:dogfood/draft 1])))))
      (testing "a write that moves only unread keys notifies nobody"
        (rt/dispatch! frame-id [:dogfood/edit-draft 2 "nobody reads this"])
        (is (= 0 @(:hits row-1))))
      (testing "an unread key mixed into a read one adds no phantom"
        (rt/with-commit (fn []
                          (rt/dispatch! frame-id [:dogfood/toggle 1])
                          (rt/dispatch! frame-id [:dogfood/edit-draft 2 "still nobody"])))
        (is (= 1 @(:hits row-1))))
      (testing "and asking about an unread key does not intern it — the
               fused table has nowhere to intern one, which is stronger
               than the old index's `get` with a default"
        (let [before (:cells (rt/stats))]
          (is (= [] (rt/cell-readers (key-of [:no-such/query]))))
          (is (= before (:cells (rt/stats))))))
      (testing "**the fused table's own version of an unknown key, and the
               one the retired index could not have.** A cell outlives its
               last reader by a reaper's grace, so between a cleanup and
               the next macrotask there is a live cell with an EMPTY
               reader list. A write that dirties it must contribute
               nothing — the same claim law 6 makes about a key nobody
               ever read, now about a key nobody reads any more"
        (let [tmp (mount! (fn [_] [:li (str (rt/sub [:dogfood/draft 1]))]))]
          ((:stop! tmp))
          (rt/dispatch! frame-id [:dogfood/edit-draft 1 "into the void"])
          (is (= 0 @(:hits tmp)))
          (is (= 1 @(:hits row-1))
              "and the live boundary is not swept up by the readerless cell")))
      (finally (stop-all! row-1)))))

;; ===========================================================================
;; The screen-shaped discharge, migrated from front/dogfood_cljs_test
;; ===========================================================================

(deftest the-table-answers-the-screens-own-narrow-and-broad-writes
  (testing "**Migrated from `front/dogfood_cljs_test` (rf2-dabt3).** That
           file proved the front half composes, and closed with the index
           saying which boundary a real screen's write dirtied — driven
           through `front.sub-index`'s pure doors, because the front half
           owned the index. It no longer does. The claim is the same one,
           now taken through the fused doors against real notifications
           rather than against a value algebra"
    (seeded! 3)
    (let [header (mount! (fn [_] [:span (str (rt/sub [:dogfood/remaining]))
                                  (str (rt/sub [:dogfood/visible-ids]))]))
          row-0  (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                  (str (rt/sub [:dogfood/done? 0]))]))
          row-1  (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))
                                  (str (rt/sub [:dogfood/done? 1]))]))]
      (try
        (testing "the narrow write dirties the one row and the header that counts it"
          (rt/dispatch! frame-id [:dogfood/toggle 1])
          (is (= 1 @(:hits row-1)))
          (is (= 1 @(:hits header)))
          (is (= 0 @(:hits row-0))))
        (testing "the broad write dirties every reader of the list"
          (rt/dispatch! frame-id [:dogfood/set-filter :active])
          (is (= 2 @(:hits header)))
          (is (= 1 @(:hits row-1)))
          (is (= 0 @(:hits row-0))))
        (testing "a to-do nobody has mounted a row for dirties nothing"
          (rt/dispatch! frame-id [:dogfood/edit-draft 2 "no row for this"])
          (is (= 2 @(:hits header)))
          (is (= 1 @(:hits row-1)))
          (is (= 0 @(:hits row-0))))
        (testing "unmounting a row takes its edges with it"
          ((:stop! row-1))
          (is (= [] (rt/cell-readers (key-of [:dogfood/done? 1]))))
          (rt/dispatch! frame-id [:dogfood/toggle 1])
          (is (= 1 @(:hits row-1)) "no further notification for the departed")
          (is (= 3 @(:hits header)) "and the header still counts"))
        (finally (stop-all! header row-0))))))

;; ===========================================================================
;; Beyond the six — the obligations the laws would silently lose
;; ===========================================================================

(deftest sub-key-identity-is-value-equality-over-query-and-args
  (seeded! 3)
  (let [row (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 1]))]))]
    (try
      (testing "same query-id, same args — one key, however it was constructed"
        (is (= [(:reg row)]
               (rt/cell-readers [frame-id (vec [:dogfood/todo (+ 0 1)])]))))
      (testing "same query-id, different args — different keys"
        (is (= [] (rt/cell-readers (key-of [:dogfood/todo 2])))))
      (finally (stop-all! row)))))

(deftest an-abandoned-render-writes-nothing-because-the-only-write-is-the-commits
  (seeded! 3)
  (testing "**The abandoned-render obligation, now structural.** The
           retired index needed `record-reads` to ignore a boundary that
           was not live, because React can abandon a render and an
           abandoned render's reads must not resurrect the edges
           `unmount` had just dropped. There is no render-phase write to
           guard here: the only write to the table is inside `subscribe`,
           which React calls at commit and nowhere else. So this is a
           witness rather than a guard — and it is the stronger of the
           two, because a guard can be removed while a structure cannot
           acquire a write path by accident"
    (let [before (rt/stats)]
      (render! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                        (str (rt/sub [:dogfood/remaining]))]))
      (let [after (rt/stats)]
        (is (= (:boundaries before) (:boundaries after)) "no boundary registered")
        (is (= (:edges before) (:edges after)) "no edge added")
        (is (= (:cells before) (:cells after)) "no cell built")))
    (testing "and a body run AFTER its boundary's cleanup adds nothing
             either, which is exactly what the old guard existed for"
      (let [b (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
        ((:stop! b))
        (is (= 0 (:edges (rt/stats))))
        (render! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                          (str (rt/sub [:dogfood/todo 1]))]))
        (is (= 0 (:edges (rt/stats))))
        (rt/dispatch! frame-id [:dogfood/toggle 0])
        (is (= 0 @(:hits b)))))))

(deftest a-double-subscribe-and-its-two-cleanups-leave-zero-residue
  (async done
    (seeded! 3)
    (testing "**Where mount idempotence went.** There is no `mount` to be
             idempotent in: a `subscribe` mints a fresh registration and
             its cleanup releases exactly that one. StrictMode's double
             invoke is therefore two registrations on one entry rather
             than one registration mounted twice, and what the old test
             actually protected — the second pass must not corrupt the
             first's edges, and neither must survive teardown — is this"
      (let [entry (render! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))
            hits  (volatile! 0)
            stop-1 (rt/commit-boundary! entry (fn [] (vswap! hits inc)))
            stop-2 (rt/commit-boundary! entry (fn [] (vswap! hits inc)))]
        (is (= 2 (count (rt/cell-readers (key-of [:dogfood/todo 0]))))
            "two registrations, one entry, one cell")
        (is (= 2 (:boundaries (rt/stats))))
        (rt/dispatch! frame-id [:dogfood/toggle 0])
        (is (= 2 @hits) "both are notified, once each")
        (stop-1)
        (is (= 1 (count (rt/cell-readers (key-of [:dogfood/todo 0]))))
            "the first cleanup removes ITS membership and not the other's")
        (stop-2)
        (is (= [] (rt/cell-readers (key-of [:dogfood/todo 0]))))
        (is (= {:cells 1 :cell-refs 0 :boundaries 0 :edges 0 :entries 1}
               (rt/residue))
            "no membership survives; the cell and the entry await the reaper")
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rt/residue))
                             "and nothing survives the macrotask horizon")
                         (done))
                       8)))))

(deftest the-registration-shares-the-entrys-key-set-rather-than-copying-it
  (seeded! 3)
  (testing "**Where the `identical?` sharing witness went (rf2-aqgr2,
           rf2-dabt3).** It used to assert that `record-reads` coerced
           rather than copied its caller's set, because the forward-edge
           map retained that set for the life of the mount and a rebuild
           would have left the boundary holding a second hash set with
           the same contents — measured at +46 B/read (Reagent segment)
           and +47 (UIx), and invisible to every value-equality assertion
           in the old suite. There is no forward-edge map to retain
           anything now, and the sharing claim is one step shorter and
           one step stronger: the registration's read set IS the entry's,
           so the fused table stores no forward edge at all"
    (let [b (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                             (str (rt/sub [:dogfood/remaining]))]))]
      (try
        (is (identical? (rt/reads-of (:entry b)) (rt/boundary-reads (:reg b))))
        (is (= #{(key-of [:dogfood/todo 0]) (key-of [:dogfood/remaining])}
               (rt/boundary-reads (:reg b))))
        (finally (stop-all! b))))))

(deftest one-membership-is-both-the-edge-and-the-reference
  (async done
    (seeded! 3)
    (testing "**The fusion, as an executable statement rather than an
             argument.** The table used to run beside a second global
             structure over the same key space, and the two had to be
             kept in step: a boundary's reference count and its edge
             count were separate records that were always equal and could
             always drift. They are now one slot, counted once — walked
             across the reachable values so a regression that reintroduces
             a second record has to make two numbers disagree"
      (let [steps (volatile! [])
            note! (fn [step]
                    (let [s (rt/stats)]
                      (vswap! steps conj [step (:cell-refs s) (:edges s)])))]
        (note! :empty)
        (let [a (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                 (str (rt/sub [:dogfood/todo 1]))]))]
          (note! :one-boundary-two-reads)
          (let [b (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
            (note! :a-shared-key)
            ((:stop! a))
            (note! :the-wide-one-leaves)
            ((:stop! b))
            (note! :empty-again)
            (is (= [[:empty 0 0]
                    [:one-boundary-two-reads 2 2]
                    [:a-shared-key 3 3]
                    [:the-wide-one-leaves 1 1]
                    [:empty-again 0 0]]
                   @steps)
                "the reference count and the edge count are one number at
                 every step, because they are one membership")
            (js/setTimeout (fn []
                             (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                    (rt/residue)))
                             (done))
                           8)))))))

;; ---------------------------------------------------------------------------
;; HD-005 — the evidence seam is two lines, nil by default, and silent
;; ---------------------------------------------------------------------------

(deftest the-evidence-seam-is-detached-by-default-and-attachable-without-redesign
  (seeded! 3)
  (testing "the `:edges-changed` and `:commit` event shapes are kept
           verbatim across the move off `front.sub-index`, so anything
           written against the seam attaches to the fused table without
           being redesigned (rf2-dabt3)"
    (let [seen (atom [])]
      (testing "with no sink attached the table does its work and says nothing"
        (let [b (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
          (rt/dispatch! frame-id [:dogfood/toggle 0])
          (is (= 1 @(:hits b)))
          (is (= [] @seen))
          ((:stop! b))))
      (testing "an attached sink sees the edge change and the commit"
        (seeded! 3)
        (rt/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
        (try
          (let [b (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
            (rt/dispatch! frame-id [:dogfood/toggle 0])
            (let [[edges commit] @seen]
              (is (= 2 (count @seen)))
              (is (= :edges-changed (:event edges)))
              (is (identical? (:reg b) (:boundary edges)))
              (is (= #{(key-of [:dogfood/todo 0])} (:added edges)))
              (is (= #{} (:dropped edges))
                  "this wiring never drops: a narrowed read set is a fresh
                   registration, and the old one's cleanup took its
                   memberships with it")
              (is (= :commit (:event commit)))
              (is (contains? (:dirty-subs commit) (key-of [:dogfood/todo 0])))
              (is (= #{(:reg b)} (:dirty-boundaries commit))))
            ((:stop! b)))
          (finally (rt/set-evidence-sink! nil))))
      (testing "a boundary that read nothing emits no edge change — the
               seam reports change, not traffic"
        (seeded! 3)
        (reset! seen [])
        (rt/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
        (try
          (let [entry (render! (fn [_] [:li "static"]))
                stop  (rt/commit-boundary! entry (fn []))]
            (is (= [] @seen))
            (stop))
          (finally (rt/set-evidence-sink! nil))))
      (testing "detaching restores silence"
        (seeded! 3)
        (reset! seen [])
        (rt/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
        (rt/set-evidence-sink! nil)
        (let [b (mount! (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]))]
          (rt/dispatch! frame-id [:dogfood/toggle 0])
          (is (= [] @seen))
          ((:stop! b)))))))
