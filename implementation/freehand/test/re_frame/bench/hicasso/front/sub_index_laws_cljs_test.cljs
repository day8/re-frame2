(ns re-frame.bench.hicasso.front.sub-index-laws-cljs-test
  "THE SIX INDEX LAWS, as tests (rf2-2rtt6.8).

  architecture.md restates six laws the local `spike-01` pure model
  proved, and HD-017 says the model graduates into the tracked bench/test
  tree *with its six-law algebra as the index's unit tests*. This file is
  that graduation. Each law gets one `deftest` named for its number, and
  each is written so that breaking the mechanism it names turns that test
  red and — as far as the algebra allows — leaves its five siblings
  green, because a law whose failure is indistinguishable from another
  law's failure is not being tested separately.

  The laws, verbatim from architecture.md:

    1. after mount+read, a commit of that sub dirties that boundary only;
    2. two boundaries sharing a sub both dirty;
    3. unmount removes edges;
    4. a re-run with fewer reads drops edges (conditional read);
    5. the broad dirty set is the union of all readers of any dirty sub;
    6. an unknown dirty sub yields the empty set — no phantom boundaries.

  Below the six sit the tests for what a runtime needs beyond the paper
  algebra: sub-key identity under value equality, the abandoned-render
  guard, mount idempotence, liveness as the forward-edge entry, the
  global-atom door, and the HD-005 evidence seam. Those are not laws and
  do not pretend to be; they are the obligations the laws would silently
  lose without them.

  Everything here is a pure-value test against the algebra in
  [[re-frame.bench.hicasso.front.sub-index]] except the last two groups,
  which drive the global atom and are wrapped in a fixture that resets
  it — the index is process-global by design (global maps, not
  reactions), and a consolidated node-test bundle shares one process."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.front.sub-index :as idx]))

;; ---------------------------------------------------------------------------
;; Fixtures and fixtures-free helpers
;; ---------------------------------------------------------------------------

(use-fixtures :each
  {:before (fn [] (idx/reset-index!) (idx/set-evidence-sink! nil))
   :after  (fn [] (idx/reset-index!) (idx/set-evidence-sink! nil))})

(defn- run
  "Mount `boundary-id` if it is not already live, then apply one body run
  that read `sub-keys`. The pure-value counterpart of one render."
  [index boundary-id & sub-keys]
  (-> index
      (idx/mount boundary-id)
      (idx/record-reads boundary-id (set sub-keys))))

;; ===========================================================================
;; LAW 1 — after mount+read, a commit of that sub dirties that boundary only
;; ===========================================================================

(deftest law-1-a-committed-sub-dirties-its-own-reader-only
  (let [index (-> (idx/empty-index)
                  (run :row-1 [:todo/by-id 1] [:todo.ui/editing? 1])
                  (run :row-2 [:todo/by-id 2])
                  (run :header [:cart/count]))]
    (testing "the reader of the dirty sub is dirty"
      (is (= #{:row-1} (idx/dirty-boundaries index #{[:todo/by-id 1]}))))
    (testing "and nobody else is — not the sibling row, not the header"
      (is (= #{:row-2} (idx/dirty-boundaries index #{[:todo/by-id 2]})))
      (is (= #{:header} (idx/dirty-boundaries index #{[:cart/count]}))))
    (testing "a boundary's second read is an independent edge, not a merge"
      (is (= #{:row-1} (idx/dirty-boundaries index #{[:todo.ui/editing? 1]}))))))

;; ===========================================================================
;; LAW 2 — two boundaries sharing a sub both dirty
;; ===========================================================================

(deftest law-2-a-shared-sub-dirties-every-sharer
  (let [index (-> (idx/empty-index)
                  (run :a [:cart/count])
                  (run :b [:cart/count])
                  (run :c [:cart/count] [:user/name]))]
    (testing "every reader of the shared key, and only them"
      (is (= #{:a :b :c} (idx/dirty-boundaries index #{[:cart/count]})))
      (is (= #{:a :b :c} (idx/readers-of index [:cart/count]))))
    (testing "a key one of them additionally reads dirties only that one"
      (is (= #{:c} (idx/dirty-boundaries index #{[:user/name]}))))))

;; ===========================================================================
;; LAW 3 — unmount removes edges
;; ===========================================================================

(deftest law-3-unmount-removes-every-edge-the-boundary-held
  (let [index (-> (idx/empty-index)
                  (run :gone [:x] [:y])
                  (run :stay [:x]))
        after (idx/unmount index :gone)]
    (testing "the shared key keeps the survivor and loses the departed"
      (is (= #{:stay} (idx/dirty-boundaries after #{[:x]}))))
    (testing "the key only the departed read is gone from the index entirely"
      (is (= #{} (idx/dirty-boundaries after #{[:y]})))
      (is (not (contains? (:sub->bs after) [:y]))
          "an emptied reader set is dropped, not retained as an empty set"))
    (testing "the departed holds no forward edges and is not live"
      (is (= #{} (idx/edges-of after :gone)))
      (is (false? (idx/live? after :gone)))
      (is (true? (idx/live? after :stay))))))

;; ===========================================================================
;; LAW 4 — a re-run with fewer reads drops edges (the conditional read)
;; ===========================================================================

(deftest law-4-a-rerun-with-fewer-reads-drops-the-edges-it-stopped-reading
  (let [index  (-> (idx/empty-index) (run :b [:a] [:b]))
        narrow (idx/record-reads index :b #{[:a]})]
    (testing "the edge the second run did not read is dropped"
      (is (= #{[:a]} (idx/edges-of narrow :b)))
      (is (= #{} (idx/dirty-boundaries narrow #{[:b]}))))
    (testing "the edge it did read survives untouched"
      (is (= #{:b} (idx/dirty-boundaries narrow #{[:a]}))))
    (testing "and a third run that reads it again restores the edge"
      (let [wide (idx/record-reads narrow :b #{[:a] [:b]})]
        (is (= #{[:a] [:b]} (idx/edges-of wide :b)))
        (is (= #{:b} (idx/dirty-boundaries wide #{[:b]})))))
    (testing "a re-run that reads nothing leaves the boundary live with no edges"
      (let [silent (idx/record-reads index :b #{})]
        (is (= #{} (idx/edges-of silent :b)))
        (is (true? (idx/live? silent :b)))
        (is (= #{} (idx/dirty-boundaries silent #{[:a] [:b]})))))))

;; ===========================================================================
;; LAW 5 — the broad dirty set is the union of all readers of any dirty sub
;; ===========================================================================

(deftest law-5-the-broad-dirty-set-is-the-union-of-the-readers
  (let [index (-> (idx/empty-index)
                  (run :r1 [:todo/by-id 1] [:cart/count])
                  (run :r2 [:todo/by-id 2])
                  (run :hdr [:cart/count]))]
    (testing "the union across three keys, counted once each"
      (is (= #{:r1 :r2 :hdr}
             (idx/dirty-boundaries index #{[:todo/by-id 1] [:todo/by-id 2] [:cart/count]}))))
    (testing "a two-key commit is the union of exactly those two reader sets"
      (is (= #{:r1 :hdr} (idx/dirty-boundaries index #{[:cart/count]})))
      (is (= #{:r1 :r2} (idx/dirty-boundaries index #{[:todo/by-id 1] [:todo/by-id 2]}))))
    (testing "an overlapping commit does not double-count its overlap"
      (is (= #{:r1 :hdr} (idx/dirty-boundaries index #{[:todo/by-id 1] [:cart/count]}))))
    (testing "the empty commit dirties nothing"
      (is (= #{} (idx/dirty-boundaries index #{}))))))

;; ===========================================================================
;; LAW 6 — an unknown dirty sub yields the empty set; no phantom boundaries
;; ===========================================================================

(deftest law-6-an-unknown-dirty-sub-yields-the-empty-set
  (let [index (-> (idx/empty-index) (run :row-1 [:todo/by-id 1]))]
    (testing "a key nobody reads contributes nothing"
      (is (= #{} (idx/dirty-boundaries index #{[:todo/by-id 99]})))
      (is (= #{} (idx/readers-of index [:todo/by-id 99]))))
    (testing "an unknown key mixed into a known commit adds no phantom"
      (is (= #{:row-1} (idx/dirty-boundaries index #{[:todo/by-id 1] [:todo/by-id 99]}))))
    (testing "a commit made entirely of unknown keys is empty, not everything"
      (is (= #{} (idx/dirty-boundaries index #{[:nope] [:also/nope] [:never]}))))
    (testing "and the unknown key is not interned into the index by asking"
      (idx/dirty-boundaries index #{[:todo/by-id 99]})
      (is (not (contains? (:sub->bs index) [:todo/by-id 99]))))))

;; ===========================================================================
;; Beyond the six — the obligations the laws would silently lose
;; ===========================================================================

(deftest sub-key-identity-is-value-equality-over-query-and-args
  (testing "same query-id, same args — one key, however it was constructed"
    (let [index (-> (idx/empty-index) (run :row-7 [:todo/by-id 7]))]
      (is (= #{:row-7} (idx/dirty-boundaries index #{(vec [:todo/by-id (+ 3 4)])})))))
  (testing "same query-id, different args — different keys"
    (let [index (-> (idx/empty-index) (run :row-7 [:todo/by-id 7]))]
      (is (= #{} (idx/dirty-boundaries index #{[:todo/by-id 8]})))))
  (testing "framework-shaped keys with map args obey the same rule"
    (let [index (-> (idx/empty-index) (run :fav [:rf/mutation {:instance [:fav "slug"]}]))]
      (is (= #{:fav} (idx/dirty-boundaries index #{[:rf/mutation {:instance [:fav "slug"]}]})))
      (is (= #{} (idx/dirty-boundaries index #{[:rf/mutation {:instance [:fav "other"]}]}))))))

(deftest an-abandoned-renders-reads-do-not-resurrect-an-unmounted-boundary
  (testing "record-reads on a boundary that is not live is a no-op"
    (let [index (-> (idx/empty-index) (run :b [:x]) (idx/unmount :b))
          stale (idx/record-reads index :b #{[:x] [:y]})]
      (is (= index stale) "the index value is untouched")
      (is (= #{} (idx/dirty-boundaries stale #{[:x] [:y]})))
      (is (false? (idx/live? stale :b)))))
  (testing "a boundary never mounted at all cannot record edges either"
    (let [index (idx/record-reads (idx/empty-index) :never-mounted #{[:x]})]
      (is (= #{} (idx/dirty-boundaries index #{[:x]}))))))

(deftest mount-is-idempotent-and-does-not-clear-recorded-edges
  (testing "StrictMode's second mount keeps the first mount's reads"
    (let [index (-> (idx/empty-index) (run :b [:x]) (idx/mount :b))]
      (is (= #{[:x]} (idx/edges-of index :b)))
      (is (= #{:b} (idx/dirty-boundaries index #{[:x]})))))
  (testing "a remount after unmount starts clean"
    (let [index (-> (idx/empty-index) (run :b [:x]) (idx/unmount :b) (idx/mount :b))]
      (is (= #{} (idx/edges-of index :b)))
      (is (true? (idx/live? index :b))))))

(deftest the-forward-edge-shares-a-callers-set-rather-than-copying-it
  (testing "rf2-aqgr2, and the assertion is `identical?` on purpose. The
           forward edge retains this set for the life of the mount, so a
           `record-reads` that COPIED it would leave the boundary holding
           a second hash set with the same contents — measured at +46
           B/read (Reagent segment) and +47 (UIx) on `p0_run.cjs --only
           ladder`, and invisible to every value-equality assertion in
           this file. `cljs.core/set` returns a meta-less set unchanged,
           so the sharing is already there; this is the row that stops a
           rebuild being reintroduced as a tidy-up"
    (let [reads #{[:x] [:y]}
          index (-> (idx/empty-index) (idx/mount :b) (idx/record-reads :b reads))]
      (is (identical? reads (idx/edges-of index :b)))))
  (testing "and a caller handing over a SEQUENCE still gets a set edge —
           that is the general contract this namespace is written to, and
           the coercion is what a caller passing read order relies on"
    (let [index (-> (idx/empty-index)
                    (idx/mount :b)
                    (idx/record-reads :b [[:x] [:y] [:x]]))]
      (is (set? (idx/edges-of index :b)))
      (is (= #{[:x] [:y]} (idx/edges-of index :b)) "duplicates collapse")
      (is (= #{:b} (idx/dirty-boundaries index #{[:y]}))))))

(deftest liveness-is-the-forward-edge-entry-and-there-is-no-second-record-of-it
  (testing "rf2-ixb92. The index used to carry a third structure — `:live`,
           a set of mounted boundary ids — beside the two edge maps, and it
           held nothing `:b->subs` did not already hold: mount installs an
           entry, unmount drops it, and record-reads only ever writes for a
           boundary that is already there. This is that derivability as an
           executable statement rather than an argument, walked across the
           reachable values: at every step the set of boundaries the index
           treats as mounted is exactly the key set of the forward edges"
    (let [b0 (idx/empty-index)
          b1 (idx/mount b0 :one)
          b2 (idx/record-reads b1 :one #{[:x] [:y]})
          b3 (idx/mount b2 :two)
          b4 (idx/record-reads b3 :two #{[:x]})
          b5 (idx/record-reads b4 :one #{[:x]})       ; law 4's narrowing
          b6 (idx/record-reads b5 :one #{})           ; a body that read nothing
          b7 (idx/mount b6 :one)                      ; StrictMode's second mount
          b8 (idx/unmount b7 :one)
          b9 (idx/record-reads b8 :one #{[:z]})       ; an abandoned render
          ids [:one :two :never-mounted]]
      (doseq [[step index live] [[:empty       b0 #{}]
                                 [:mount       b1 #{:one}]
                                 [:read        b2 #{:one}]
                                 [:mount-2nd   b3 #{:one :two}]
                                 [:read-2nd    b4 #{:one :two}]
                                 [:narrow      b5 #{:one :two}]
                                 [:read-none   b6 #{:one :two}]
                                 [:remount     b7 #{:one :two}]
                                 [:unmount     b8 #{:two}]
                                 [:abandoned   b9 #{:two}]]]
        (is (= live (set (keys (:b->subs index))))
            (str "the forward edges' key set is the mounted set at " step))
        (is (= live (into #{} (filter #(idx/live? index %)) ids))
            (str "and `live?` answers from it, for every id, at " step)))))
  (testing "the empty set `mount` installs is the whole registration, which
           is why a boundary whose body read nothing is still live and can
           still record edges afterwards — drop that entry as an
           optimisation and the boundary dies silently"
    (let [index (-> (idx/empty-index) (idx/mount :b))]
      (is (true? (idx/live? index :b)))
      (is (= #{} (idx/edges-of index :b)))
      (is (= #{[:x]} (idx/edges-of (idx/record-reads index :b #{[:x]}) :b)))))
  (testing "and there is no second record to drift out of step with the
           first: an index value is exactly the two edge maps"
    (is (= #{:sub->bs :b->subs} (set (keys (idx/empty-index)))))
    (is (= #{:sub->bs :b->subs}
           (set (keys (-> (idx/empty-index) (run :b [:x]) (idx/unmount :b))))))))

(deftest the-global-index-is-one-atom-and-commit-is-its-only-door
  (idx/mount! :a)
  (idx/mount! :b)
  (idx/record-reads! :a #{[:shared] [:only-a]})
  (idx/record-reads! :b #{[:shared]})
  (testing "commit! answers the same question the pure algebra does"
    (is (= #{:a :b} (idx/commit! #{[:shared]})))
    (is (= #{:a} (idx/commit! #{[:only-a]})))
    (is (= #{} (idx/commit! #{[:unknown]}))))
  (testing "commit! reads the index and never mutates it"
    (let [before (idx/snapshot)]
      (idx/commit! #{[:shared] [:unknown]})
      (is (= before (idx/snapshot)))))
  (testing "unmount! through the global door drops the edges too"
    (idx/unmount! :a)
    (is (= #{:b} (idx/commit! #{[:shared]})))
    (is (= #{} (idx/commit! #{[:only-a]})))))

(deftest with-reads-collects-a-bodys-reads-and-applies-them
  (idx/mount! :b)
  (testing "the body's return value comes back, and its reads become edges"
    (let [[result reads] (idx/with-reads :b (fn []
                                              (idx/read! [:a])
                                              (idx/read! [:b])
                                              :hiccup))]
      (is (= :hiccup result))
      (is (= #{[:a] [:b]} reads))
      (is (= #{:b} (idx/commit! #{[:a]})))))
  (testing "a second body run with fewer reads drops the edge — law 4, live"
    (idx/with-reads :b (fn [] (idx/read! [:a])))
    (is (= #{} (idx/commit! #{[:b]})))
    (is (= #{:b} (idx/commit! #{[:a]}))))
  (testing "outside a body the collector is nil and a read indexes nothing"
    (is (nil? idx/*collector*))
    (idx/read! [:stray])
    (is (= #{} (idx/commit! #{[:stray]})))))

;; ---------------------------------------------------------------------------
;; HD-005 — the evidence seam is three lines, nil by default, and silent
;; ---------------------------------------------------------------------------

(deftest the-evidence-seam-is-detached-by-default-and-attachable-without-redesign
  (testing "with no sink attached the index does its work and says nothing"
    (idx/mount! :a)
    (idx/record-reads! :a #{[:x]})
    (is (= #{:a} (idx/commit! #{[:x]}))))
  (testing "an attached sink sees the edge change and the commit"
    (let [seen (atom [])]
      (idx/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
      (idx/mount! :b)
      (idx/record-reads! :b #{[:x] [:y]})
      (idx/commit! #{[:x]})
      (is (= [{:event :edges-changed :boundary :b :added #{[:x] [:y]} :dropped #{}}
              {:event :commit :dirty-subs #{[:x]} :dirty-boundaries #{:a :b}}]
             @seen))))
  (testing "an unchanged read set emits nothing — the seam reports change, not traffic"
    (let [seen (atom [])]
      (idx/mount! :c)
      (idx/record-reads! :c #{[:z]})
      (idx/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
      (idx/record-reads! :c #{[:z]})
      (is (= [] @seen))))
  (testing "detaching restores silence"
    (let [seen (atom [])]
      (idx/set-evidence-sink! (fn [ev] (swap! seen conj ev)))
      (idx/set-evidence-sink! nil)
      (idx/mount! :d)
      (idx/record-reads! :d #{[:q]})
      (idx/commit! #{[:q]})
      (is (= [] @seen)))))
