(ns re-frame.bench.fresco.arm1.cell-table-laws-cljs-test
  "THE SIX INDEX LAWS, against the fused cell table.

  architecture.md restates six laws the `spike-01` pure model proved, and
  HD-017 makes them the index's unit tests. The index is the runtime's
  fused cell table — the readers live on the cell and the forward edge on
  the registration — so the laws are discharged against the runtime's own
  doors: [[rf.bench.fresco.arm1.runtime/commit-boundary!]] (the seam React
  occupies), [[rf.bench.fresco.arm1.runtime/dispatch!]] (which drives
  `flush!`), [[rf.bench.fresco.arm1.runtime/stats]] and
  [[rf.bench.fresco.arm1.runtime/cell-readers]].

    1. after mount+read, a commit of that sub dirties that boundary only;
    2. two boundaries sharing a sub both dirty;
    3. unmount removes edges;
    4. a re-run with fewer reads drops edges (conditional read);
    5. the broad dirty set is the union of all readers of any dirty sub;
    6. an unknown dirty sub yields the empty set — no phantom boundaries.

  Law 2 is discharged inside law 5, whose header shares `remaining` with a
  row. Below the laws sit the obligations they would silently lose: a
  StrictMode double subscribe, the registration sharing the entry's key
  set, and the evidence seam.

  The adapter is UIx's, not `plain-atom`'s, and that is load-bearing:
  plain-atom has no reactivity layer, so a subscription under it never
  notifies and every dirty-set assertion below would pass vacuously."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The map shape, because the residue claim is `async` — a reaper
     ;; horizon is not observable inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!) (rf.bench.fresco.arm1.runtime/set-evidence-sink! nil))}))

(def ^:private frame-id ::cell-table-laws)

(defn- seeded! []
  (rf.bench.fresco.arm1.runtime/reset-runtime!)
  (rf.bench.fresco.front.dogfood/make-frame! frame-id 3))

(defn- key-of [query] [frame-id query])

(defn- render!
  "One body run through the shell's own fence, minus React. Answers the
  read-set entry."
  [body-fn]
  (rf.bench.fresco.arm1.runtime/render-body frame-id body-fn {})
  (rf.bench.fresco.arm1.runtime/last-reads))

(defn- mount!
  "One boundary at the seam React occupies: render its body, commit its
  reads, and answer `{:entry :reg :hits :stop!}`. `:reg` is read back off
  an edge — the last reader on the first key's cell — because the fused
  table keeps no registry of live boundaries."
  [body-fn]
  (let [entry (render! body-fn)
        hits  (volatile! 0)
        stop  (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! hits inc)))
        reg   (last (rf.bench.fresco.arm1.runtime/cell-readers (first (rf.bench.fresco.arm1.runtime/reads-of entry))))]
    {:entry entry :reg reg :hits hits :stop! stop}))

(defn- hits [& boundaries] (mapv (comp deref :hits) boundaries))

(deftest law-1-a-committed-sub-dirties-its-own-reader-only
  (seeded!)
  (let [row-1  (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))
                                (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/draft 1]))]))
        row-2  (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 2]))]))
        header (mount! (fn [_] [:span (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))]))]
    ;; `edit-draft` moves the draft and nothing else — one dirty key, which
    ;; is what makes this law 1 rather than law 5.
    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/edit-draft 1 "half-typed"])
    (is (= [1 0 0] (hits row-1 row-2 header))
        "the reader of the dirty sub re-runs, and not the sibling row or the header")))

(deftest law-3-unmount-removes-every-edge-the-boundary-held
  (seeded!)
  (let [gone (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                              (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/draft 0]))]))
        stay (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))]
    ((:stop! gone))
    (is (= [[(:reg stay)] []]
           (mapv rf.bench.fresco.arm1.runtime/cell-readers [(key-of [:dogfood/todo 0]) (key-of [:dogfood/draft 0])]))
        "the shared key keeps the survivor, and the key only the departed read has no reader")))

(deftest law-4-a-rerun-with-fewer-reads-drops-the-edges-it-stopped-reading
  (seeded!)
  (testing "a narrowed read set is a fresh registration, so React's own
           cleanup-then-subscribe performs the whole edge-set replacement"
    ((:stop! (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                              (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))))
    (let [narrow (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))]
      (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 1])
      (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0])
      (is (= 1 @(:hits narrow)) "the dropped key no longer reaches it; the kept one still does"))))

(deftest law-5-the-broad-dirty-set-is-the-union-of-the-readers
  (seeded!)
  (let [r1  (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))
                             (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))]))
        r2  (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 2]))]))
        hdr (mount! (fn [_] [:span (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))]))]
    (testing "a toggle moves BOTH of r1's keys in one commit and r1 is
             notified once; hdr shares `remaining` with r1, so it runs too
             (law 2); r2 read neither"
      (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 1])
      (is (= [1 0 1] (hits r1 r2 hdr))))
    (testing "the empty commit dirties nothing"
      (let [g (rf.bench.fresco.arm1.runtime/generation)]
        (rf.bench.fresco.arm1.runtime/with-commit (fn []))
        (is (= g (rf.bench.fresco.arm1.runtime/generation)))))))

(deftest law-6-an-unknown-dirty-sub-yields-the-empty-set
  (seeded!)
  (let [row-1 (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))]))]
    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/edit-draft 2 "nobody reads this"])
    ;; A cell outlives its last reader by a reaper's grace, so between a
    ;; cleanup and the next macrotask there is a live cell with an EMPTY
    ;; reader list — the fused table's own version of an unknown key.
    ((:stop! (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/draft 1]))]))))
    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/edit-draft 1 "into the void"])
    (is (= 0 @(:hits row-1)) "a write that moves only unread keys notifies nobody")))

(deftest the-table-answers-the-screens-own-narrow-and-broad-writes
  (seeded!)
  (let [header (mount! (fn [_] [:span (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))
                                (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/visible-ids]))]))
        row-0  (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                                (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/done? 0]))]))
        row-1  (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 1]))
                                (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/done? 1]))]))]
    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 1])
    (is (= [1 1 0] (hits header row-1 row-0)) "the narrow write dirties the one row and the header that counts it")
    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/set-filter :active])
    (is (= [2 1 0] (hits header row-1 row-0)) "the broad write dirties every reader of the list")))

;; ===========================================================================
;; Beyond the six — the obligations the laws would silently lose
;; ===========================================================================

(deftest a-double-subscribe-and-its-two-cleanups-leave-zero-residue
  (async done
    (seeded!)
    (testing "StrictMode's double invoke is two registrations on one entry:
             neither may corrupt the other's edges, and neither may survive
             teardown"
      (let [entry  (render! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))
            n      (volatile! 0)
            stop-1 (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! n inc)))
            stop-2 (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn [] (vswap! n inc)))]
        (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0])
        (is (= 2 @n) "both are notified, once each")
        (stop-1)
        (is (= 1 (count (rf.bench.fresco.arm1.runtime/cell-readers (key-of [:dogfood/todo 0]))))
            "the first cleanup removes ITS membership and not the other's")
        (stop-2)
        (is (= {:cells 1 :cell-refs 0 :boundaries 0 :edges 0 :entries 1}
               (rf.bench.fresco.arm1.runtime/residue))
            "no membership survives; the cell and the entry await the reaper")
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rf.bench.fresco.arm1.runtime/residue))
                             "and nothing survives the macrotask horizon")
                         (done))
                       8)))))

(deftest the-registration-shares-the-entrys-key-set-rather-than-copying-it
  (seeded!)
  (testing "a copy would hold a second hash set per boundary — +46 B/read —
           invisible to every value-equality assertion"
    (let [b (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                             (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))]))]
      (is (identical? (rf.bench.fresco.arm1.runtime/reads-of (:entry b))
                      (rf.bench.fresco.arm1.runtime/boundary-reads (:reg b)))))))

;; ---------------------------------------------------------------------------
;; HD-005 — the evidence seam is two lines, nil by default, and silent
;; ---------------------------------------------------------------------------

(deftest the-evidence-seam-is-detached-by-default-and-attachable-without-redesign
  (seeded!)
  (let [seen (atom [])]
    (rf.bench.fresco.arm1.runtime/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
    (try
      (let [b (mount! (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]))]
        (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0])
        (is (= [{:event :edges-changed :boundary (:reg b) :added #{(key-of [:dogfood/todo 0])} :dropped #{}}
                {:event :commit :dirty-subs #{(key-of [:dogfood/todo 0])} :dirty-boundaries #{(:reg b)}}]
               @seen)
            "the seam's fixed vocabulary: the edge change, then the commit"))
      (reset! seen [])
      (rf.bench.fresco.arm1.runtime/commit-boundary! (render! (fn [_] [:li "static"])) (fn []))
      (is (= [] @seen) "a boundary that read nothing emits no edge change — the seam reports change, not traffic")
      (finally (rf.bench.fresco.arm1.runtime/set-evidence-sink! nil)))))
