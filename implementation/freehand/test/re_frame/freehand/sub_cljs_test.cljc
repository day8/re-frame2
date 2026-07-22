(ns re-frame.freehand.sub-cljs-test
  "FH-SUB-008 … FH-SUB-011 — the subscription law: `v/sub`, the paved path's
  render-only reactive read.

  Four fixtures, four laws, proven through the PUBLIC authoring verb rather
  than the shell's internal `observe!` port: a render read returns a value and
  the commit owns it as a bundle dependency; a read outside any render is
  refused loudly with a stable id; the capture reaches through an ordinary
  `defn` helper; and an input change recomputes and recommits the whole bundle
  atomically.

  Every row runs over the REAL observation port and the REAL sub-cache (the
  plain-atom adapter), on the JVM and in ClojureScript from one fixture apiece
  — a `.cljc` claim green on one host is a gap. The plain-atom adapter's
  derived values are not watchable, so invalidation here is proven by
  re-rendering and re-committing against the mutated frame-state and asserting
  the bundle flips as a UNIT — the honest headless posture the shell
  documents, not a simulated watch."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Seams — the port surfaces a v/sub read moves, read exactly as the shell
;; suite reads them (one boundary, one adapter).
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)

(defn- sub-cache [f] (:sub-cache (frame/frame f)))
(defn- ref-count [f q] (:ref-count (get @(sub-cache f) q)))
(defn- seed!     [f db] (frame/replace-app-db! f db))

(defn- render!
  "One render pass of cell `c` bound to frame `f`, reading `queries` in order
  through the PUBLIC `v/sub`. Returns `[candidate values]`; nothing is
  published — the candidate is a value the caller holds."
  [c f queries]
  (let [cand (cell/candidate c f)
        vals (cell/with-capture cand (fn [] (mapv v/sub queries)))]
    [cand vals]))

;; ===========================================================================
;; FH-SUB-008 — a render read returns a value; the commit owns the dependency
;; ===========================================================================

(def sub-008 (conf/fixture :FH-SUB-008))

(deftest fh-sub-008-a-render-read-returns-a-value-and-the-commit-owns-it
  (testing "Per FH-SUB-008: v/sub in a render RETURNS the subscription's
            current value and records a render-owned read; the SELECTED commit
            publishes those reads as the bundle's dependency set — exactly the
            queries read, in render order, each owned."
    (let [{:keys [query-a query-b db values before after]} sub-008]
      (rf/reg-sub (first query-a) (fn [db _] (:a db)))
      (rf/reg-sub (first query-b) (fn [db _] (:b db)))
      (seed! fid db)
      (let [c           (cell/cell :sub/panel)
            [cand vals] (render! c fid [query-a query-b])]
        (is (= values vals) "v/sub returned each subscription's current value")
        (testing "before the commit the render owns nothing"
          (is (= (:lifecycle before) (cell/lifecycle c)))
          (is (= (:dependency-count before) (count (cell/dependencies c))))
          (is (nil? (ref-count fid query-a))
              "the render acquired no cache node"))
        (is (= :published (cell/commit! cand)))
        (testing "and the commit publishes the whole dependency set as one bundle"
          (is (= (:lifecycle after) (cell/lifecycle c)))
          (is (= (:dependency-count after) (count (cell/dependencies c))))
          (is (= [query-a query-b] (cell/dependency-queries c))
              "the published bundle depends on exactly the queries read, in order")
          (is (= [query-a query-b] (mapv :query (:observations (cell/evidence c)))))
          (is (= values (mapv :value (:observations (cell/evidence c))))
              "and the bundle carries their committed values")
          (is (every? :owned? (:observations (cell/evidence c)))
              "each read is an OWNED dependency")
          (is (pos? (count (cell/dependencies c)))
              "non-vacuous: the commit owns a non-empty dependency set"))))))

;; ===========================================================================
;; FH-SUB-009 — a read outside any render fails loud
;; ===========================================================================

(def sub-009 (conf/fixture :FH-SUB-009))

(deftest fh-sub-009-a-read-outside-render-fails-loud
  (testing "Per FH-SUB-009: v/sub with no active declared render has no owner,
            so it is refused loudly with a stable diagnostic id rather than
            probed and dropped to a silent nil."
    (let [{:keys [query db outside-render-error-id]} sub-009]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid db)
      (is (false? (cell/observing?)) "no active render")
      (is (= outside-render-error-id
             (conf/caught-id #(rf/with-frame fid (v/sub query))))
          "a stable id, not a silent nil")
      (is (some? outside-render-error-id)
          "non-vacuous: the law asserts an actual diagnostic id"))))

;; ===========================================================================
;; FH-SUB-010 — the capture reaches through a plain helper fn
;; ===========================================================================

(def sub-010 (conf/fixture :FH-SUB-010))

;; A PLAIN defn — deliberately NOT a v/defview — that reads through v/sub deep
;; inside ordinary Clojure. The render must own its read wherever the call
;; lexically sits, so brackets-vs-parens changes ownership, not this seam.
(defn- via-helper [q] (inc (v/sub q)))

(deftest fh-sub-010-capture-reaches-through-a-plain-helper-fn
  (testing "Per FH-SUB-010: the capture rides the active render, not the call
            depth — a v/sub inside an ordinary defn helper called from the body
            is owned by the calling render exactly as an inline read is, and the
            same helper called outside a render is refused loudly."
    (let [{:keys [query db helper-value dependency-queries
                  outside-render-error-id]} sub-010]
      (rf/reg-sub (first query) (fn [db _] (:n db)))
      (seed! fid db)
      (let [c    (cell/cell :sub/panel)
            cand (cell/candidate c fid)
            v    (first (cell/with-capture cand (fn [] [(via-helper query)])))]
        (is (= helper-value v) "the helper computed on the subscription value")
        (is (= :published (cell/commit! cand)))
        (is (= dependency-queries (cell/dependency-queries c))
            "the helper's v/sub is owned by the calling render, not lost")
        (is (= 1 (count (cell/dependencies c)))
            "non-vacuous: the helper read became one owned dependency"))
      (testing "and the SAME helper outside a render is refused loudly"
        (is (false? (cell/observing?)))
        (is (= outside-render-error-id
               (conf/caught-id #(rf/with-frame fid (via-helper query)))))))))

;; ===========================================================================
;; FH-SUB-011 — invalidation recomputes and recommits the bundle atomically
;; ===========================================================================

(def sub-011 (conf/fixture :FH-SUB-011))

(deftest fh-sub-011-invalidation-recomputes-and-recommits-atomically
  (testing "Per FH-SUB-011: when an input a committed v/sub depends on changes
            value, a re-render recomputes it and the commit republishes the
            whole bundle atomically — the committed bundle is unchanged until the
            next commit, and the recommitted dependency then carries the NEW
            value against the SAME retained handle, flipping as a unit rather
            than mixing one render's dependency with another's value."
    (let [{:keys [query db-before db-after value-before value-after
                  dependency-queries]} sub-011]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid db-before)
      (let [c            (cell/cell :sub/panel)
            [cand1 [v1]] (render! c fid [query])]
        (is (= value-before v1) "the first render read the old value")
        (is (= :published (cell/commit! cand1)))
        (let [h1 (:handle (get (cell/dependencies c) 0))]
          (is (= value-before (:value (first (:observations (cell/evidence c)))))
              "the first bundle carries the old value")
          ;; The input moves. The OLD bundle stays installed until the next
          ;; commit — nothing partial is published in between.
          (seed! fid db-after)
          (is (= value-before (:value (first (:observations (cell/evidence c)))))
              "the committed bundle is unchanged until a render is re-selected")
          (let [[cand2 [v2]] (render! c fid [query])]
            (is (= value-after v2) "the re-render recomputed the new value")
            (is (= :published (cell/commit! cand2)))
            (is (= dependency-queries (cell/dependency-queries c)))
            (is (= value-after (:value (first (:observations (cell/evidence c)))))
                "the recommitted bundle carries the NEW value with its dependency, atomically")
            (is (identical? h1 (:handle (get (cell/dependencies c) 0)))
                "against the SAME retained handle — acquire-before-release kept the node")
            (is (= 1 (count (cell/dependencies c)))
                "non-vacuous: exactly one owned dependency across the recommit")
            (is (not= value-before value-after)
                "and the recompute genuinely moved the value")))))))
