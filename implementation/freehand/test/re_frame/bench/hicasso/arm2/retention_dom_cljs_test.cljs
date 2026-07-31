(ns re-frame.bench.hicasso.arm2.retention-dom-cljs-test
  "BOUNDARY-EXCLUSIVE RETENTION, INVENTORIED (rf2-2rtt6.10).

  validation.md is explicit that an arm does not get to claim an absence:
  *before an arm is admitted, every boundary-exclusive token, callback,
  hook cell, epoch, map entry, and edge membership is inventoried in the
  1/3/7/20 heap ladder against the 0.4–0.5 KB target — honest
  accounting, not a claimed absence.*

  This file is the **census half** of that obligation, and it is
  deliberately not a byte figure. It asserts what a boundary *is*, by
  counting what mounting one adds and what unmounting one removes, so
  that the term list a heap figure would be attributed to is established
  independently of any instrument. The byte half needs the browser heap
  instrument and is stated as remaining work rather than estimated here;
  a number nobody measured is worth less than a term list nobody can
  dispute.

  ## The complete term list for one Arm 2 boundary, at R reads

  | term | count | where it lives |
  |---|---|---|
  | boundary record | 1 map, 5 entries | `runtime/!boundaries` |
  | id → record entry | 1 | `runtime/!boundaries` |
  | forward edge set | 1 set of R keys | index `:b->subs` |
  | reverse edge membership | R | index `:sub->bs` |
  | live membership | 1 | index `:live` |
  | node → id entry | 1 | `runtime`'s `WeakMap` |
  | React hook cells | **0** | — |
  | reactions / ref-counts | **0** | — |
  | per-element instance objects | **0** | the previous hiccup *is* the previous tree |
  | epoch / generation counters | **0** | invariant 5 is constructive |

  The three zeros are the arm's whole architectural bet, and each is
  asserted below rather than asserted in prose: a mounted boundary must
  add **no** own-property expando to its element beyond the ones the
  emitter's own contracts require (the shape stamp, the handler register,
  the controlled model value), and those are *element* terms shared by
  every renderer that patches DOM, not per-boundary ones.

  Value-cache terms are per **unique subscription key**, not per
  boundary, and are asserted here too, because a per-boundary reading of
  them would flatter the arm exactly the way rf2-2rtt6.16 warned about."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.arm2.grid-witness :as grid]
            [re-frame.bench.hicasso.arm2.runtime :as rt]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private off-browser "no DOM on this runtime — the census counts mounted boundaries")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- with-grid [n f]
  (rt/reset-runtime!)
  (let [c (container!)
        teardown (grid/mount! c n)]
    (try (f c) (finally (teardown) (.remove c) (rt/reset-runtime!)))))

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(deftest a-boundary-costs-exactly-the-terms-in-the-inventory
  (if-not (browser?)
    (is true off-browser)
    (with-grid 10
      (fn [_c]
        (let [snap (idx/snapshot)]
          (testing "one record and one live membership per boundary"
            (is (= 11 (rt/boundary-count)) "ten cells plus the grid boundary")
            (is (= 11 (count (:live snap)))))
          (testing "one forward edge set per boundary"
            (is (= 11 (count (:b->subs snap)))))
          (testing "R reverse-edge memberships, R = 1 for a cell"
            (is (= 10 (count (:sub->bs snap))) "ten distinct keys, one per cell")
            (is (every? (fn [[_k readers]] (= 1 (count readers))) (:sub->bs snap))
                "and one reader each — no amortisation across boundaries"))
          (testing "the grid boundary reads nothing, and that is visible"
            (is (some (fn [[_id ks]] (empty? ks)) (:b->subs snap)))))))))

(deftest the-value-cache-is-per-unique-key-not-per-boundary
  (testing "rf2-2rtt6.16's mandatory worst case: distinct queries, Q = E"
    (if-not (browser?)
      (is true off-browser)
      (with-grid 10
        (fn [_c]
          (is (= 10 (count (rt/watched-keys)))
              "ten cached values for ten distinct keys")
          (is (= (into #{} (map (fn [i] [:grid/cell i])) (range 10))
                 (rt/watched-keys))))))))

(deftest a-boundary-holds-no-hook-cell-no-reaction-and-no-epoch
  (testing "the three zeros — asserted as the shape of the record, because a
           field that does not exist cannot be measured later"
    (if-not (browser?)
      (is true off-browser)
      (with-grid 3
        (fn [c]
          (let [node (grid/cell-input c 1)
                cell (.-parentNode node)
                rec  (rt/boundary-of cell)]
            (is (some? rec) "the cell's root node maps back to its boundary")
            (is (= #{:id :view :props :hiccup :node} (set (keys rec)))
                "five fields, and no sixth — no hook cell, no epoch, no reaction")))))))

(deftest an-element-carries-only-the-emitters-own-expandos
  (testing "there is no per-element instance object: the previous hiccup is
           the previous tree, so a patched element holds only what the DOM
           contracts need"
    (if-not (browser?)
      (is true off-browser)
      (with-grid 3
        (fn [c]
          (let [input   (grid/cell-input c 1)
                own     (set (js->clj (js/Object.keys input)))
                ours    (into #{} (filter #(re-find #"^__hicasso" %)) own)
                allowed #{"__hicassoOn" "__hicassoValue" "__hicassoChecked"
                          "__hicassoFenced" "__hicassoComposing" "__hicassoSig"}]
            (is (contains? ours "__hicassoOn") "the handler register")
            (is (contains? ours "__hicassoValue") "the controlled model value")
            (is (contains? ours "__hicassoFenced") "the composition fence")
            (is (empty? (remove allowed ours))
                (str "and nothing else the renderer put there: " (pr-str ours)))
            (is (not-any? #(re-find #"[Ff]iber|[Rr]eact" %) own)
                "and nothing React put there — React is not on this path")))))))

;; ---------------------------------------------------------------------------
;; Unmount removes exactly what mount added
;; ---------------------------------------------------------------------------

(deftest unmounting-returns-every-term
  (if-not (browser?)
    (is true off-browser)
    (do (rt/reset-runtime!)
        (let [c (container!)
              teardown (grid/mount! c 10)
              snap-before (idx/snapshot)]
          (is (= 11 (count (:live snap-before))))
          (teardown)
          (let [snap (idx/snapshot)]
            (is (zero? (rt/boundary-count)) "no record")
            (is (empty? (:live snap)) "no live membership")
            (is (empty? (:b->subs snap)) "no forward edge set")
            (is (empty? (:sub->bs snap)) "no reverse edge membership")
            (is (empty? (rt/watched-keys)) "no cached value")
            (is (nil? (.-firstChild c)) "and no node"))
          (.remove c)
          (rt/reset-runtime!)))))

(deftest the-shape-of-the-ladder-is-linear-in-boundaries
  (testing "the census's own scaling check: the term counts a heap figure
           would be divided by"
    (if-not (browser?)
      (is true off-browser)
      (doseq [n [1 10 50]]
        (with-grid n
          (fn [_c]
            (let [snap (idx/snapshot)]
              (is (= (inc n) (count (:live snap))))
              (is (= n (count (:sub->bs snap))))
              (is (= n (count (rt/watched-keys)))))))))))
