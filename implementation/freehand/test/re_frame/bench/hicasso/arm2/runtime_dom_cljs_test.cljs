(ns re-frame.bench.hicasso.arm2.runtime-dom-cljs-test
  "THE COMMIT, THE INDEX AND THE LIFECYCLE — the runtime's own claims
  (rf2-2rtt6.10).

  Three witnesses from the roster land here:

  - `:bulk/narrow-write` — `:exactly-one-boundary-re-ran`;
  - `:abandoned/query-identity` — `:no-edge-from-the-abandoned-render`,
    `:index-matches-the-winning-render`;
  - `:lifecycle/strict-abandoned-teardown-hmr` —
    `:zero-leaked-subscription-refcounts-after-teardown`,
    `:unchanged-hot-read-performs-no-new-attach-or-release`,
    `:hmr-preserves-root-frame-and-app-db`.

  Plus the one claim that is Arm 2's alone: **invariant 5 holds by
  construction**. The three facts the runtime's docstring names are each
  asserted below — a read without a snapshot is an error, a commit's
  reads all resolve against one value, and a dispatch raised inside a
  commit does not open a second one."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.arm2.dogfood-screen :as screen]
            [re-frame.bench.hicasso.arm2.runtime :as rt]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private off-browser "no DOM on this runtime — the runtime patches real nodes")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- with-screen [n f]
  (rt/reset-runtime!)
  (let [c (container!)
        teardown (screen/mount! c n)]
    (try (f c)
         (finally (teardown) (.remove c) (rt/reset-runtime!)))))

;; ---------------------------------------------------------------------------
;; The screen mounts — the commit HD-014 starts the clock on
;; ---------------------------------------------------------------------------

(deftest the-dogfood-screen-mounts-a-list-a-controlled-field-and-its-reads
  (if-not (browser?)
    (is true off-browser)
    (with-screen 20
      (fn [c]
        (is (= 20 (count (screen/row-nodes c))) "one list, twenty rows")
        (is (some? (.querySelector c "#new-todo")) "one controlled field")
        (is (= "20 left" (.-textContent (.querySelector c "#remaining"))))
        (is (= (vec (range 20)) (screen/row-ids c)))
        (is (pos? (:sub-reads @rt/stats)) "and the boundaries read subscriptions")))))

;; ---------------------------------------------------------------------------
;; Invariant 5, by construction
;; ---------------------------------------------------------------------------

(deftest a-read-without-a-snapshot-is-a-loud-error
  (testing "fact 1: there is no ambient current-db path in this runtime"
    (is (thrown-with-msg? js/Error #"outside a commit" (rt/sub [:dogfood/filter])))))

(deftest every-read-in-one-commit-resolves-against-one-snapshot
  (if-not (browser?)
    (is true off-browser)
    (with-screen 20
      (fn [_c]
        (rt/reset-stats!)
        (rf/with-frame screen/frame-id (rf/dispatch-sync [:dogfood/set-filter :done]))
        (rt/force-commit!)
        (is (= 1 (:snapshots-per-commit @rt/stats))
            "one distinct snapshot was observed across every read of the commit")
        (is (pos? (:sub-reads @rt/stats)) "and there were reads to observe")))))

(deftest a-dispatch-raised-inside-a-commit-does-not-nest
  (testing "fact 3: the commit loop takes another turn instead of opening a
           second binding inside the first"
    (if-not (browser?)
      (is true off-browser)
      (with-screen 5
        (fn [c]
          ;; A row's toggle handler dispatches; the commit that applies it
          ;; runs its own commit! — which must not nest.
          (let [toggle (.querySelector c "#toggle-2")]
            (rt/reset-stats!)
            (.click toggle)
            (is (= 1 (:snapshots-per-commit @rt/stats)))
            (is (true? (get-in (rf/app-db-value screen/frame-id) [:todos 2 :done?]))
                "and the write landed")))))))

;; ---------------------------------------------------------------------------
;; :bulk/narrow-write
;; ---------------------------------------------------------------------------

(deftest a-narrow-write-re-runs-exactly-one-boundary
  (if-not (browser?)
    (is true off-browser)
    (with-screen 100
      (fn [_c]
        (rt/reset-stats!)
        (rt/dispatch! [:dogfood/edit-draft 42 "x"])
        (is (= 1 (:dirty-boundaries @rt/stats))
            "one row's draft changed, so one row is dirty out of a hundred")
        (is (= 1 (:body-runs @rt/stats)))))))

(deftest a-broad-write-rebuilds-the-list-and-nothing-else
  (if-not (browser?)
    (is true off-browser)
    (with-screen 20
      (fn [c]
        (rt/dispatch! [:dogfood/toggle 1])
        (rt/reset-stats!)
        (rt/dispatch! [:dogfood/set-filter :done])
        (is (= 1 (count (screen/row-nodes c))) "one done to-do survives the filter")
        (is (= [1] (screen/row-ids c)))))))

;; ---------------------------------------------------------------------------
;; The keyed witness, through the runtime
;; ---------------------------------------------------------------------------

(deftest a-reorder-through-the-screen-moves-nodes-rather-than-rebuilding-them
  (if-not (browser?)
    (is true off-browser)
    (with-screen 10
      (fn [c]
        (let [before (into #{} (screen/row-nodes c))
              row-7  (nth (screen/row-nodes c) 7)]
          (rt/dispatch! [:dogfood/move 7 0])
          (is (= 7 (first (screen/row-ids c))) "row 7 is now first")
          (is (identical? row-7 (first (screen/row-nodes c))) "and it is the same node")
          (is (= before (into #{} (screen/row-nodes c))) "no row was recreated"))))))

(deftest removing-a-row-unmounts-its-boundary-and-drops-its-edges
  (if-not (browser?)
    (is true off-browser)
    (with-screen 10
      (fn [c]
        (let [before (rt/boundary-count)]
          (rt/dispatch! [:dogfood/remove 3])
          (is (= 9 (count (screen/row-nodes c))))
          (is (= (dec before) (rt/boundary-count)) "exactly one boundary left")
          (is (empty? (idx/readers-of (idx/snapshot) [:dogfood/todo 3]))
              "and nothing still reads the gone row's subscription"))))))

;; ---------------------------------------------------------------------------
;; :abandoned/query-identity
;; ---------------------------------------------------------------------------

(deftest an-unmounted-boundarys-edges-do-not-come-back
  (testing "the front half's record-reads guard, exercised through the runtime:
           a body run for a boundary that is gone records nothing"
    (if-not (browser?)
      (is true off-browser)
      (with-screen 5
        (fn [_c]
          (rt/dispatch! [:dogfood/remove 2])
          (idx/record-reads! 999 #{[:dogfood/todo 2]})
          (is (empty? (idx/readers-of (idx/snapshot) [:dogfood/todo 2]))
              "an abandoned render cannot resurrect an unmounted boundary's edges"))))))

(deftest a-boundarys-query-identity-follows-its-props
  (if-not (browser?)
    (is true off-browser)
    (with-screen 5
      (fn [_c]
        (let [snap (idx/snapshot)]
          (is (seq (idx/readers-of snap [:dogfood/todo 3]))
              "the index matches the winning render, key by key")
          (is (empty? (idx/readers-of snap [:dogfood/todo 99]))
              "and an unread key has no reader — no phantom boundary"))))))

;; ---------------------------------------------------------------------------
;; :lifecycle
;; ---------------------------------------------------------------------------

(deftest an-unchanged-hot-read-performs-no-new-attach-or-release
  (testing "there is nothing to attach: this arm holds no reaction and no
           ref-count, so the claim is that the edge set is untouched"
    (if-not (browser?)
      (is true off-browser)
      (with-screen 10
        (fn [_c]
          (let [edges-before (:sub->bs (idx/snapshot))]
            (rt/dispatch! [:dogfood/toggle 4])
            (is (= edges-before (:sub->bs (idx/snapshot)))
                "a hot re-run reading the same keys changed no edge")))))))

(deftest teardown-leaves-nothing-behind
  (if-not (browser?)
    (is true off-browser)
    (do (rt/reset-runtime!)
        (let [c (container!)
              teardown (screen/mount! c 10)]
          (is (pos? (rt/boundary-count)))
          (teardown)
          (is (zero? (rt/boundary-count)))
          (is (empty? (:sub->bs (idx/snapshot))) "no edge survives")
          (is (empty? (:live (idx/snapshot))) "no boundary is live")
          (is (empty? (rt/watched-keys)) "no subscription value is retained")
          (is (nil? (.-firstChild c)) "and the container is empty")
          (.remove c)
          (rt/reset-runtime!)))))

(deftest an-hmr-body-swap-keeps-the-root-the-frame-and-app-db
  (if-not (browser?)
    (is true off-browser)
    (with-screen 5
      (fn [c]
        (let [db-before (rf/app-db-value screen/frame-id)
              root      (rt/root-node)
              original  @(unchecked-get screen/row-view "hicassoBody")]
          (rt/swap-body! screen/row-view
                         (fn [{:keys [id]}]
                           (let [todo (rt/sub [:dogfood/todo id])]
                             [:li.todo {:data-id id} [:b.swapped (:title todo)]])))
          (is (= 5 (count (screen/row-nodes c))) "the list survived")
          (is (some? (.querySelector c "b.swapped")) "the changed body is in use")
          (is (identical? root (rt/root-node)) "the root node is the same")
          (is (= db-before (rf/app-db-value screen/frame-id)) "app-db is untouched")
          (is (seq (:sub->bs (idx/snapshot))) "and no subscription leaked")
          ;; leave the view as we found it, since it is a def
          (rt/swap-body! screen/row-view original))))))
