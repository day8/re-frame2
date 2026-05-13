(ns day8.re-frame2-causa.panels.hydration-debugger-cljs-test
  "Pure-data tests for Causa's Hydration Debugger helpers
  (Phase 5, rf2-pzxsr).

  ## Why the `.cljc` + `_cljs_test` naming

  The file ends in `_cljs_test.cljc` so:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  Same dual-target pattern as `time_travel_helpers_cljs_test.cljc`
  and `causality_graph_cljs_test.cljc`.

  ## What's under test

    1. **mismatches / mismatches-for-frame** — filter the buffer
       down to hydration-mismatch events; frame-aware projection.
    2. **classify-divergence** — five divergence kinds + the
       unknown fallback.
    3. **hypothesis-for** — every divergence-kind has a hypothesis
       line (per spec §Cause hypothesis).
    4. **first-divergence-path** — bisector path from root to first
       differing node.
    5. **mismatch-detail** — full projection from a trace event to
       the shape the view consumes.
    6. **re-root** — the hash-chip click → re-root path.
    7. **source-coord-for-mismatch** — Lock #11 fallback annotation."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.hydration-debugger-helpers :as h]))

;; ---- fixture builders ----------------------------------------------------

(defn- mismatch-ev
  "Build a `:rf.ssr/hydration-mismatch` trace event with the canonical
  payload shape per spec/006-Hydration-Debugger.md §Substrate."
  ([id path server-tree client-tree]
   (mismatch-ev id path server-tree client-tree {}))
  ([id path server-tree client-tree extra-tags]
   {:id        id
    :op-type   :error
    :operation :rf.ssr/hydration-mismatch
    :tags      (merge {:path        path
                       :server-tree server-tree
                       :client-tree client-tree
                       :server-hash "abc123"
                       :client-hash "def456"
                       :frame       :rf/default
                       :view-id     'cart-summary-view
                       :failing-id  :rf/hydrate}
                      extra-tags)}))

(defn- non-mismatch-ev
  "Build a non-mismatch trace event for the buffer."
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :tags      {:dispatch-id id}})

;; ---- (1) mismatch projection --------------------------------------------

(deftest hydration-mismatch?-predicate
  (testing "hydration-mismatch? identifies the canonical operation"
    (is (true?  (h/hydration-mismatch?
                  {:operation :rf.ssr/hydration-mismatch})))
    (is (false? (h/hydration-mismatch?
                  {:operation :event/dispatched})))
    (is (false? (h/hydration-mismatch? {})))))

(deftest mismatches-filters-the-buffer
  (testing "mismatches returns only hydration-mismatch events, in order"
    (let [trace [(non-mismatch-ev 1)
                 (mismatch-ev 2 [:div] [:div "a"] [:div "b"])
                 (non-mismatch-ev 3)
                 (mismatch-ev 4 [:span] [:span "x"] [:span "y"])]
          got   (h/mismatches trace)]
      (is (= 2 (count got)))
      (is (= [2 4] (mapv :id got))))))

(deftest mismatches-for-frame-frame-aware
  (testing "frame filter passes events whose :tags :frame matches"
    (let [trace [(mismatch-ev 1 [] [:a] [:b] {:frame :rf/default})
                 (mismatch-ev 2 [] [:a] [:c] {:frame :rf/other})
                 (mismatch-ev 3 [] [:a] [:d] {:frame :rf/default})]]
      (is (= [1 3] (mapv :id (h/mismatches-for-frame trace :rf/default))))
      (is (= [2]   (mapv :id (h/mismatches-for-frame trace :rf/other))))
      (is (= [1 2 3] (mapv :id (h/mismatches-for-frame trace nil)))
          "nil frame filter is the identity"))))

(deftest frames-with-mismatches-returns-distinct-frames
  (testing "frames-with-mismatches surfaces every distinct frame-id"
    (let [trace [(mismatch-ev 1 [] [:a] [:b] {:frame :rf/default})
                 (mismatch-ev 2 [] [:a] [:c] {:frame :rf/other})
                 (mismatch-ev 3 [] [:a] [:d] {:frame :rf/default})]]
      (is (= #{:rf/default :rf/other}
             (h/frames-with-mismatches trace))))))

(deftest select-mismatch-by-id
  (testing "select-mismatch returns the trace event whose :id matches"
    (let [m1 (mismatch-ev 1 [] [:a] [:b])
          m2 (mismatch-ev 2 [] [:a] [:c])]
      (is (= m1 (h/select-mismatch [m1 m2] 1)))
      (is (= m2 (h/select-mismatch [m1 m2] 2)))
      (is (nil? (h/select-mismatch [m1 m2] 999)))
      (is (nil? (h/select-mismatch [] 1))))))

;; ---- (2) classify-divergence --------------------------------------------

(deftest classify-different-text
  (testing "text content differs under the same tag → :different-text"
    (is (= :different-text
           (h/classify-divergence "3 items" "0 items")))
    (is (= :different-text
           (h/classify-divergence [:p "3 items"] [:p "0 items"])))))

(deftest classify-tag-differs
  (testing "tag differs → :tag-differs"
    (is (= :tag-differs
           (h/classify-divergence [:p "hello"] [:span "hello"])))
    (is (= :tag-differs
           (h/classify-divergence [:div] "text"))
        "tag-vs-text is a structural flip")))

(deftest classify-attr-differs
  (testing "attrs differ; tag matches → :attr-differs"
    (is (= :attr-differs
           (h/classify-divergence [:a {:href "/a"} "go"]
                                  [:a {:href "/b"} "go"])))))

(deftest classify-children-missing-client
  (testing "children present server, missing client → :children-missing-client"
    (is (= :children-missing-client
           (h/classify-divergence [:ul [:li "a"] [:li "b"]]
                                  [:ul])))))

(deftest classify-children-missing-server
  (testing "children missing server, present client → :children-missing-server"
    (is (= :children-missing-server
           (h/classify-divergence [:div]
                                  [:div [:span "client-only"]])))))

(deftest classify-unknown-fallback
  (testing "shapes we don't classify return :unknown"
    (is (= :unknown
           (h/classify-divergence {:not "hiccup"} #{:also "weird"})))))

(deftest hypothesis-for-covers-every-kind
  (testing "every divergence-kind has a hypothesis line"
    (doseq [k [:different-text :tag-differs :attr-differs
               :children-missing-client :children-missing-server :unknown]]
      (is (string? (h/hypothesis-for k))
          (str "hypothesis present for " k))
      (is (pos? (count (h/hypothesis-for k)))))))

;; ---- (3) first-divergence-path ------------------------------------------

(deftest divergence-path-equal-trees-returns-nil
  (testing "structurally-equal trees produce nil — no divergence"
    (is (nil? (h/first-divergence-path [:p "a"] [:p "a"])))
    (is (nil? (h/first-divergence-path "text" "text")))))

(deftest divergence-path-root-divergence
  (testing "roots disagree on tag → divergence at []"
    (is (= [] (h/first-divergence-path [:p "a"] [:span "a"])))))

(deftest divergence-path-descends-to-first-difference
  (testing "matching parent + matching first child + divergent second
            child → walker descends to the text leaf at [1 0] (the
            first node whose value structurally diverges)"
    (is (= [1 0] (h/first-divergence-path
                   [:div [:p "match"] [:p "server"]]
                   [:div [:p "match"] [:p "client"]])))))

(deftest divergence-path-descends-multiple-levels
  (testing "deep divergence walks down through matching ancestors —
            the walker descends as deep as the matching structure
            allows, landing at the first text leaf that differs"
    (let [server [:div
                  [:section
                   [:p "match"]
                   [:p [:em "server-bold"]]]]
          client [:div
                  [:section
                   [:p "match"]
                   [:p [:em "client-bold"]]]]]
      (is (= [0 1 0 0] (h/first-divergence-path server client))
          "descends section → second-p → em → text leaf"))))

;; ---- (4) mismatch-detail projection -------------------------------------

(deftest mismatch-detail-shape
  (testing "mismatch-detail surfaces every slot the view consumes"
    (let [ev     (mismatch-ev 42 [:div :main]
                              [:p "3 items"]
                              [:p "0 items"])
          detail (h/mismatch-detail ev)]
      (is (= 42 (:id detail)))
      (is (= [:div :main] (:path detail)))
      (is (= :rf/default (:frame detail)))
      (is (= 'cart-summary-view (:view-id detail)))
      (is (= :rf/hydrate (:failing-id detail)))
      (is (= :different-text (:divergence-kind detail)))
      (is (string? (:hypothesis detail)))
      (is (vector? (:bisector-path detail)))
      (is (vector? (:server-chips detail)))
      (is (vector? (:client-chips detail)))
      (is (= "abc123" (:server-hash detail)))
      (is (= "def456" (:client-hash detail))))))

(deftest mismatch-detail-uses-runtime-first-diff-path-when-supplied
  (testing "when the trace carries :first-diff-path, the detail uses it"
    (let [ev     (mismatch-ev 1 []
                              [:div [:span "a"] [:span "x"]]
                              [:div [:span "a"] [:span "y"]]
                              {:first-diff-path [9 9 9]})
          detail (h/mismatch-detail ev)]
      (is (= [9 9 9] (:bisector-path detail))
          "trace-supplied :first-diff-path wins over computed"))))

(deftest mismatch-detail-nil-on-nil-trace
  (testing "mismatch-detail returns nil when there's no trace event"
    (is (nil? (h/mismatch-detail nil)))))

;; ---- (5) mismatch-list-summary ------------------------------------------

(deftest mismatch-list-summary-shapes-each-entry
  (testing "summary entries carry id, path, view-id, divergence-kind, summary"
    (let [trace [(mismatch-ev 1 [:div] [:p "3"] [:p "0"])
                 (mismatch-ev 2 [:span] [:a] [:b])]
          summ  (h/mismatch-list-summary trace nil)]
      (is (= 2 (count summ)))
      (let [e1 (first summ)]
        (is (= 1 (:id e1)))
        (is (= [:div] (:path e1)))
        (is (= 'cart-summary-view (:view-id e1)))
        (is (= :different-text (:divergence-kind e1)))
        (is (string? (:summary e1)))))))

(deftest mismatch-list-summary-frame-aware
  (testing "summary respects the frame filter axis"
    (let [trace [(mismatch-ev 1 [] [:a] [:b] {:frame :rf/default})
                 (mismatch-ev 2 [] [:c] [:d] {:frame :rf/other})]]
      (is (= [1] (mapv :id (h/mismatch-list-summary trace :rf/default))))
      (is (= [2] (mapv :id (h/mismatch-list-summary trace :rf/other)))))))

;; ---- (6) re-root --------------------------------------------------------

(deftest re-root-walks-into-subtree
  (testing "re-root descends `path` into the tree"
    (let [tree [:div [:p "a"] [:p [:em "deep"]]]]
      (is (= [:p "a"]
             (h/re-root tree [0])))
      (is (= [:p [:em "deep"]]
             (h/re-root tree [1])))
      (is (= [:em "deep"]
             (h/re-root tree [1 0]))))))

(deftest re-root-empty-path-is-identity
  (testing "re-root with [] returns the original tree"
    (let [tree [:div "x"]]
      (is (= tree (h/re-root tree [])))
      (is (= tree (h/re-root tree nil))))))

(deftest re-root-out-of-bounds-returns-current-node
  (testing "an out-of-bounds path stops at the deepest valid node
            (defensive — never throws)"
    (let [tree [:div]]
      (is (= [:div] (h/re-root tree [99]))))))

;; ---- (7) source-coord-for-mismatch (Lock #11 fallback) ------------------

(deftest source-coord-exact-view-coord
  (testing "when :source-coord is on :tags, return :exact annotation"
    (let [ev (mismatch-ev 1 [] [:p] [:p]
                          {:source-coord {:file "src/cart/views.cljs"
                                          :line 42}})]
      (is (= {:coord "src/cart/views.cljs:42" :annotation :exact}
             (h/source-coord-for-mismatch ev))))))

(deftest source-coord-fallback-handler-coord
  (testing "when only :handler-source-coord is present, fall back with
            :fallback annotation (Lock #11)"
    (let [ev (mismatch-ev 1 [] [:p] [:p]
                          {:handler-source-coord {:file "src/cart/events.cljs"
                                                  :line 13}})]
      (is (= {:coord "src/cart/events.cljs:13" :annotation :fallback}
             (h/source-coord-for-mismatch ev))))))

(deftest source-coord-nil-when-absent
  (testing "neither coord present → {:coord nil :annotation nil}"
    (let [ev (mismatch-ev 1 [] [:p] [:p])]
      (is (= {:coord nil :annotation nil}
             (h/source-coord-for-mismatch ev))))))
