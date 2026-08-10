(ns re-frame.hicasso.index-laws-cljs-test
  "THE SIX INDEX LAWS, over the fused cell table (rf2-wjag).

  > (1) after mount+read, a commit of that sub dirties that boundary only;
  > (2) two boundaries sharing a sub both dirty; (3) unmount removes edges;
  > (4) a re-run with fewer reads drops edges (conditional read); (5) the
  > broad dirty set is the union of all readers of any dirty sub; (6) an
  > unknown dirty sub yields the empty set — no phantom boundaries.
  >
  > — `docs/design/hicasso/architecture.md` §2, restated verbatim when
  >   rf2-dabt3 retired `front.sub-index` and fused the reverse edge onto
  >   the key cell. *Nothing about the laws themselves changed.*

  These six are the whole contract of the subscription→boundary index,
  and since the fusion the package has had no witness for any of them.
  `inventory_snapshot_cljs_test` asserts that `cell-readers` answers a
  SNAPSHOT, which is a claim about the instrument. `kernel_commit_owns_cljs_test`
  asserts ACQUISITION — which keys a commit takes and which readers it
  installs. Neither asks the question the laws ask, which is the one the
  runtime exists to answer: **given a commit, which boundaries re-run?**

  ## The observable is the notification, and it has to be

  Every law below is asserted on the count of `onStoreChange` calls a
  committed boundary received, and on `inventory/cell-readers` beside it.
  Reader membership alone is not the law: an edge that is present and a
  commit that never consults it produce identical censuses and opposite
  screens. So each row reads the edge to establish its premise and the
  notification to make its claim.

  ## Every zero here is paired with a one

  A dirty-set law is half prohibition — *and no other boundary*, *not at
  all*, *the empty set* — and a prohibition is trivially satisfied by a
  runtime that has stopped notifying anybody. So **no row asserts a zero
  without asserting, in the same commit or the one after it, a boundary
  that DID move**. That pairing is the negative control, distributed
  across the rows rather than parked in one, because what has to stay
  live is the specific counter the zero is being claimed of.

  ## The doors

  `render-body` + `commit-boundary!` are the published seam — the render
  React drives and the commit it drives, exposed so acquisition and
  dirtying can be stated per key and per reader without a browser. The
  write door is `collector/dispatch!`, which is the arm's own: it is
  `with-commit` applied, so ONE dispatch is ONE commit window however
  many subscriptions it moves. Law 5 is a claim about that window and
  would be untestable through a door that flushed per key, so the row
  asserts the window's existence — `generation` moves by exactly one —
  before it asserts what the window did.

  The adapter is UIx's rather than `plain-atom`'s, and that is
  load-bearing for the whole file: plain-atom has no reactivity layer, so
  a subscription under it never notifies, `mark-dirty!` never fires, and
  every row would pass vacuously by never firing at all.

  ## What is deliberately not restated here

  The bench ancestor carried three obligations beneath the six that the
  fusion changed the shape of, and all three are now asserted elsewhere
  in this package: abandoned-render safety is structural and is
  `kernel_commit_owns_cljs_test`'s server-render row; StrictMode's
  double-invoke leaving no residue is that file's two-subscribes row and
  its DOM sibling's; and the registration sharing the entry's key set by
  reference is `impl.inventory/boundary-reads`' own definition. A second
  copy of any of them would double the maintenance and the two would
  drift."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.generation :as generation]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::index-laws)

(rf/reg-sub :idxlaw/a      (fn [db _] (:a db)))
(rf/reg-sub :idxlaw/b      (fn [db _] (:b db)))
(rf/reg-sub :idxlaw/c      (fn [db _] (:c db)))
(rf/reg-sub :idxlaw/unread (fn [db _] (:unread db)))

(rf/reg-event :idxlaw/seed     (fn [_ [_ db]] {:db db}))
(rf/reg-event :idxlaw/bump     (fn [{:keys [db]} [_ k]] {:db (update db k inc)}))
(rf/reg-event :idxlaw/bump-two (fn [{:keys [db]} [_ k1 k2]]
                                 {:db (-> db (update k1 inc) (update k2 inc))}))

;; `:ambient-frame nil` because this suite seats its own top-level frame,
;; and the carried-invariant chain resolves the dynamic-var tier BEFORE
;; React context — a fixture-installed ambient frame would answer reads
;; for a frame this file never made. The fn form is correct here: no row
;; is `async`, because every law is a statement about one synchronous
;; commit and the one reaper window law 6 needs is entered, not waited on.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:idxlaw/seed {:a 0 :b 0 :c 0 :unread 0}]))
  frame-id)

(defn- k
  "A sub-key. The runtime keys cells by `[frame-kw query-v]` — two frames
  are isolated contexts holding two different app-dbs, so the frame is
  part of the identity the laws are stated over."
  [query-v]
  [frame-id query-v])

(defn- reading
  "A body that reads exactly `query-vs`, in order, through the ambient
  collector. The reads sit in a `mapv` rather than at fixed sites, which
  is the surface the laws are about: the edge is recorded WHERE THE READ
  HAPPENS."
  [query-vs]
  (fn [_props] [:p (str (mapv collector/sub query-vs))]))

(defn- mount!
  "Render a body and commit it, exactly as React does — `render-body`,
  then the same `subscribe` closure `useSyncExternalStore` would call.
  Answers the notification counter React's `onStoreChange` increments,
  and the cleanup React would hold."
  ([body] (mount! body {}))
  ([body props]
   (collector/render-body frame-id body props)
   (let [entry   (collector/last-reads)
         !n      (volatile! 0)
         release (collector/commit-boundary! entry (fn [] (vswap! !n inc)))]
     {:entry entry :notified !n :release release})))

(defn- woken
  "How many times a commit has told this boundary to re-run."
  [b]
  @(:notified b))

(defn- release! [b] ((:release b)))

(defn- readers-of [query-v] (count (inventory/cell-readers (k query-v))))

;; ---------------------------------------------------------------------------
;; Law 1 — a commit of that sub dirties that boundary ONLY
;; ---------------------------------------------------------------------------

(deftest law-1-a-commit-dirties-the-readers-of-the-moved-sub-and-no-other-boundary
  (seeded!)
  (let [reads-a (mount! (reading [[:idxlaw/a]]))
        reads-b (mount! (reading [[:idxlaw/b]]))]

    (testing "the premise: two boundaries, two keys, one reader each"
      (is (= 1 (readers-of [:idxlaw/a])))
      (is (= 1 (readers-of [:idxlaw/b]))))

    (collector/dispatch! frame-id [:idxlaw/bump :a])

    (testing "the reader of the moved sub is woken exactly once"
      (is (= 1 (woken reads-a))))

    (testing "and the boundary that does not read it is not woken at all —
              `only` is the whole law, and a runtime that woke every
              committed boundary would satisfy the line above"
      (is (= 0 (woken reads-b))))

    ;; The mirror, on the same two counters, in the same test. Without it
    ;; the zero above is equally the zero of a runtime that notifies
    ;; nobody, and this file would be asserting silence.
    (collector/dispatch! frame-id [:idxlaw/bump :b])

    (testing "moving the OTHER sub wakes the other boundary and leaves the
              first where it was: both counters are live, and each moved
              only for its own key"
      (is (= 1 (woken reads-b)))
      (is (= 1 (woken reads-a))))

    (release! reads-a)
    (release! reads-b)))

;; ---------------------------------------------------------------------------
;; Law 2 — two boundaries sharing a sub BOTH dirty
;; ---------------------------------------------------------------------------

(deftest law-2-two-boundaries-sharing-a-sub-are-both-dirtied
  (seeded!)
  (let [first-reader  (mount! (reading [[:idxlaw/a]]))
        second-reader (mount! (reading [[:idxlaw/a]]))
        bystander     (mount! (reading [[:idxlaw/c]]))]

    (testing "the premise: ONE cell, TWO readers. The fan-out lives on the
              key's own reader list, which since rf2-dabt3 IS the reverse
              edge — so a law about fan-out is a law about this list"
      (is (= 2 (readers-of [:idxlaw/a]))))

    (collector/dispatch! frame-id [:idxlaw/bump :a])

    (testing "both sharers are woken — the dirty set is the cell's readers,
              plural, and not the reader that got there first"
      (is (= 1 (woken first-reader)))
      (is (= 1 (woken second-reader))))

    (testing "and the boundary reading another key is not swept in with
              them, which is what keeps `both` from meaning `all`"
      (is (= 0 (woken bystander))))

    (release! first-reader)
    (release! second-reader)
    (release! bystander)))

;; ---------------------------------------------------------------------------
;; Law 3 — unmount removes edges
;; ---------------------------------------------------------------------------

(deftest law-3-an-unmount-removes-the-edge-and-the-next-commit-passes-that-boundary-by
  (seeded!)
  (let [leaving  (mount! (reading [[:idxlaw/a]]))
        staying  (mount! (reading [[:idxlaw/a]]))]

    (testing "the premise: two memberships on one key"
      (is (= 2 (readers-of [:idxlaw/a]))))

    (release! leaving)

    (testing "disconnect drops the departing boundary's edge immediately,
              and exactly one of them — the survivor's membership is
              untouched"
      (is (= 1 (readers-of [:idxlaw/a]))))

    (collector/dispatch! frame-id [:idxlaw/bump :a])

    (testing "so a later commit of that very sub reaches the survivor and
              not the departed. The PAIR is the law: a runtime that had
              simply stopped notifying would satisfy the zero on its own"
      (is (= 1 (woken staying)))
      (is (= 0 (woken leaving))))

    (release! staying)))

;; ---------------------------------------------------------------------------
;; Law 4 — a re-run with fewer reads drops edges (the conditional read)
;; ---------------------------------------------------------------------------

(defn- conditional-body
  "One body, two read sets. `wide?` false takes the branch that never
  performs the second read at all — which is what makes the dropped edge
  a consequence of control flow rather than of a declaration."
  [{:keys [wide?]}]
  (let [a (collector/sub [:idxlaw/a])
        b (when wide? (collector/sub [:idxlaw/b]))]
    [:p (str a "/" b)]))

(deftest law-4-a-re-run-with-fewer-reads-drops-the-edge-it-stopped-holding
  (seeded!)
  ;; `keeper` exists so the row cannot pass vacuously. Without a second
  ;; reader, `:idxlaw/b`'s last membership leaves with the wide render and
  ;; the cell is handed to the reaper — at which point a commit of `:b`
  ;; notifies nobody because the key has no cell, which is LAW 6 and not
  ;; this one. The keeper holds the cell alive, so the silence measured
  ;; below is an edge that was dropped rather than a table that emptied.
  (let [keeper (mount! (reading [[:idxlaw/b]]))
        wide   (mount! conditional-body {:wide? true})]

    (testing "the premise: the wide render read both keys, so `b` carries
              two memberships"
      (is (= #{(k [:idxlaw/a]) (k [:idxlaw/b])} (collector/reads-of (:entry wide))))
      (is (= 2 (readers-of [:idxlaw/b]))))

    ;; The re-run, with one read fewer. At the seam a read-set change is
    ;; the pair — a fresh `subscribe` against the new entry, then the old
    ;; entry's cleanup — because the registration IS the boundary id, so a
    ;; changed read set is a new registration by construction.
    (let [narrow (mount! conditional-body {:wide? false})]
      (release! wide)

      (testing "the narrow read set is the branch the body actually took"
        (is (= #{(k [:idxlaw/a])} (collector/reads-of (:entry narrow)))))

      (testing "and `b` has lost the boundary that stopped reading it while
                keeping the one that did not"
        (is (= 1 (readers-of [:idxlaw/b]))))

      (collector/dispatch! frame-id [:idxlaw/bump :b])

      (testing "so the sub the re-run dropped passes it by"
        (is (= 0 (woken narrow))))

      (testing "while the boundary that still reads `b` is woken — which is
                what makes the zero above a DROPPED EDGE and not a key
                nothing holds"
        (is (= 1 (woken keeper))))

      (collector/dispatch! frame-id [:idxlaw/bump :a])

      (testing "and the read the re-run kept still reaches it, so the
                replacement dropped one edge rather than all of them"
        (is (= 1 (woken narrow)))
        (is (= 1 (woken keeper))))

      (release! narrow)
      (release! keeper))))

;; ---------------------------------------------------------------------------
;; Law 5 — the broad dirty set is the UNION of all readers of any dirty sub
;; ---------------------------------------------------------------------------

(deftest law-5-the-broad-dirty-set-is-the-union-and-a-double-reader-is-woken-once
  (seeded!)
  (let [only-a    (mount! (reading [[:idxlaw/a]]))
        reads-ab  (mount! (reading [[:idxlaw/a] [:idxlaw/b]]))
        only-c    (mount! (reading [[:idxlaw/c]]))
        gen-before (generation/generation)]

    ;; ONE commit window moving TWO subs. `collector/dispatch!` is the
    ;; arm's own door and is `with-commit` applied, so the writes inside
    ;; it are collected and flushed once.
    (collector/dispatch! frame-id [:idxlaw/bump-two :a :b])

    (testing "the premise, and it is not decoration: `flush!` bumps the
              generation once per flush that found a dirty cell, so ONE
              here is the whole claim that this was one commit window. Two
              flushes would make `woken once` an accident of a second
              flush finding nothing rather than a union"
      (is (= (inc gen-before) (generation/generation))))

    (testing "every reader of every dirty sub is in the set"
      (is (= 1 (woken only-a)))
      (is (= 1 (woken reads-ab))))

    (testing "and it is a UNION rather than a concatenation: the boundary
              that read BOTH dirty subs is woken once, not once per dirty
              key it happened to read"
      (is (= 1 (woken reads-ab))))

    (testing "and a reader of neither dirty sub is not in the union"
      (is (= 0 (woken only-c))))

    ;; The mirror for `only-c`, so its zero is a live counter's zero.
    (collector/dispatch! frame-id [:idxlaw/bump :c])
    (testing "moving `c` wakes it and nobody else"
      (is (= 1 (woken only-c)))
      (is (= 1 (woken only-a)))
      (is (= 1 (woken reads-ab))))

    (release! only-a)
    (release! reads-ab)
    (release! only-c)))

;; ---------------------------------------------------------------------------
;; Law 6 — an unknown dirty sub yields the empty set: no phantom boundaries
;; ---------------------------------------------------------------------------

(deftest law-6-a-sub-no-boundary-reads-becomes-no-commit-work-at-all
  (seeded!)
  (let [reader     (mount! (reading [[:idxlaw/a]]))
        gen-before (generation/generation)]

    (collector/dispatch! frame-id [:idxlaw/bump :unread])

    (testing "the write really happened — a body run reads the moved value
              back, so the silence below is the index's answer and not a
              dispatch that did nothing"
      (let [!seen (volatile! nil)]
        (collector/render-body frame-id
                               (fn [_] (vreset! !seen (collector/sub [:idxlaw/unread])) [:p])
                               {})
        (is (= 1 @!seen))))

    (testing "no cell holds the key, so it never enters the dirty set and
              the flush finds nothing to do"
      (is (nil? (get @collector/!cells (k [:idxlaw/unread]))))
      (is (= gen-before (generation/generation))))

    (testing "and no phantom boundary is conjured for it: the one committed
              boundary in the runtime is not woken"
      (is (= 0 (woken reader))))

    ;; The mirror, on that very counter.
    (collector/dispatch! frame-id [:idxlaw/bump :a])
    (testing "which it is, the moment a sub it actually reads moves"
      (is (= 1 (woken reader))))

    (release! reader)))

(deftest law-6-a-dirty-cell-whose-readers-have-all-left-yields-the-empty-set
  (seeded!)
  ;; The sharper half, and the one a `no cell` row cannot make: a cell
  ;; that EXISTS, is genuinely dirtied by a commit, and still contributes
  ;; no boundary. A released cell is given one macrotask of grace before
  ;; the reaper takes it, so this whole row sits inside that window — the
  ;; watch is still armed, `mark-dirty!` still fires, `flush!` still runs.
  ;; The dirty set is non-empty and the boundary set derived from it is
  ;; empty, which is the strongest available statement of `no phantom
  ;; boundaries`.
  (seeded!)
  (let [departed  (mount! (reading [[:idxlaw/a]]))
        elsewhere (mount! (reading [[:idxlaw/c]]))]

    (release! departed)

    (testing "the premise: the cell survives its last reader, holding no
              readers at all"
      (is (some? (get @collector/!cells (k [:idxlaw/a]))))
      (is (= 0 (readers-of [:idxlaw/a]))))

    (let [gen-before (generation/generation)]
      (collector/dispatch! frame-id [:idxlaw/bump :a])

      (testing "the cell really was dirtied — the generation moved, so a
                flush found it. Without this the zeros below would be the
                zeros of a commit that never reached the index"
        (is (= (inc gen-before) (generation/generation))))

      (testing "and the union over that dirty cell is empty: the departed
                boundary is not resurrected, and the live boundary reading
                another key is not swept in to stand for it"
        (is (= 0 (woken departed)))
        (is (= 0 (woken elsewhere)))))

    ;; The mirror for `elsewhere`, so its zero is a live counter's zero.
    (collector/dispatch! frame-id [:idxlaw/bump :c])
    (testing "the surviving boundary is woken by its own key"
      (is (= 1 (woken elsewhere))))

    (release! elsewhere)))
