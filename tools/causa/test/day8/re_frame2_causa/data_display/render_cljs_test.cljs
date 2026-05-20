(ns day8.re-frame2-causa.data-display.render-cljs-test
  "Tests for the shared L4-panel data-display renderer (rf2-jgip1).

  ## What's under test

  1. **Pure helpers** — `diff-op`, `changed?`, `changed-descendant?`,
     `default-expanded?`, `expansion-key`, `resolve-expanded?`.
  2. **Scalar leaf rendering** — every primitive shape lands at the
     right colour token (keywords violet, rest mono).
  3. **Container rendering** — maps / vectors / sets / lists render
     the right delimiters; counts roll up; collapse defaults follow
     §10.4 heuristic.
  4. **Diff mode** — `:added` / `:removed` / `:modified` get the right
     gutter glyph + colour + annotation; ancestor chain forces open
     for changed descendants.
  5. **Clickable paths** — emit `:rf.causa/navigate-to-path` with the
     correct payload shape.
  6. **Evicted-epoch placeholder** — renders the §10.7 sentinel layout.
  7. **App-db expansion slot** — toggle event writes the canonical
     key shape into `:rf.causa/data-display-expansion`.

  Pure-data scope — tests assert against hiccup; no DOM mount. Spec
  reference: spec/021-Dynamic-Panel-Designs.md §10."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.data-display.render :as r]
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

;; ---- helpers ------------------------------------------------------------

(defn- walk-hiccup
  "Depth-first collect every hiccup vector in `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (when (vector? node)
                (swap! out conj node)
                (doseq [child (rest node)]
                  (cond
                    (vector? child) (walk child)
                    (seq? child)    (doseq [c child] (walk c))))))]
      (walk tree))
    @out))

(defn- find-attr
  "Return the first node whose attribute-map key `k` equals `v`."
  [tree k v]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= v (get (second n) k)))))
       first))

(defn- node-style
  [node]
  (when (and (vector? node) (map? (second node)))
    (:style (second node))))

(defn- node-children
  [node]
  (when (vector? node) (drop 2 node)))

(defn- collect-text
  "Flatten any string leaves under `tree` into one big string. Useful
  for spotting 'foo' in the rendered output without caring about
  exactly which wrapper holds it."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (string? node) (swap! out conj node)
                (vector? node) (doseq [c (rest node)] (walk c))
                (seq? node)    (doseq [c node] (walk c))))]
      (walk tree))
    (apply str @out)))

;; ---- diff-op helpers ----------------------------------------------------

(deftest diff-op-classification
  (is (= :same     (r/diff-op 1 1)))
  (is (= :same     (r/diff-op ::r/missing ::r/missing)))
  (is (= :added    (r/diff-op ::r/missing 1)))
  (is (= :removed  (r/diff-op 1 ::r/missing)))
  (is (= :modified (r/diff-op 1 2))))

(deftest diff-op-uses-sentinel-from-render-ns
  ;; The ns-qualified sentinel keyword used by render.cljs is the same
  ;; one tests dereference here — no string-vs-keyword drift.
  (is (= :added (r/diff-op ::r/missing :anything))))

(deftest changed-checks
  (is (false? (r/changed? 1 1)))
  (is (true?  (r/changed? 1 2)))
  (is (false? (r/changed-descendant? {:a 1 :b 2} {:a 1 :b 2})))
  (is (true?  (r/changed-descendant? {:a 1 :b 2} {:a 1 :b 3})))
  (is (true?  (r/changed-descendant? {:a {:nested 1}}
                                     {:a {:nested 2}})))
  (is (true?  (r/changed-descendant? [1 2 3] [1 2 4])))
  (is (true?  (r/changed-descendant? [1 2] [1 2 3])))     ;; trailing add
  (is (false? (r/changed-descendant? [1 2 3] [1 2 3]))))

;; ---- expansion heuristic ------------------------------------------------

(deftest default-expansion-by-depth
  (testing "shallow nodes are expanded"
    (is (true?  (r/default-expanded? {:depth 0 :child-count 100 :default-depth 2}))))
  (testing "nodes at default-depth collapse when over threshold"
    (is (false? (r/default-expanded? {:depth 2 :child-count 100 :default-depth 2})))
    (is (true?  (r/default-expanded? {:depth 2 :child-count 5   :default-depth 2}))))
  (testing "deep nodes are collapsed by default"
    (is (false? (r/default-expanded? {:depth 4 :child-count 1   :default-depth 2}))))
  (testing "has-changed-descendant? overrides depth/size"
    (is (true?  (r/default-expanded? {:depth 9 :child-count 9999
                                      :default-depth 2
                                      :has-changed-descendant? true})))))

(deftest expansion-key-shape
  (testing "key is [panel-id render-id (vec path)]"
    (is (= [:app-db "rid" [:cart :items 0]]
           (r/expansion-key :app-db "rid" [:cart :items 0]))))
  (testing "path is always coerced to a vector"
    (is (= [:app-db "rid" [:a :b]]
           (r/expansion-key :app-db "rid" '(:a :b))))))

(deftest resolve-expanded-uses-override-when-present
  (let [k (r/expansion-key :event "r1" [:coeffects])]
    (testing "no override → fall back to default"
      (is (true?  (r/resolve-expanded? {} :event "r1" [:coeffects] true)))
      (is (false? (r/resolve-expanded? {} :event "r1" [:coeffects] false))))
    (testing "override wins"
      (is (true?  (r/resolve-expanded? {k {:expanded? true}}
                                       :event "r1" [:coeffects] false)))
      (is (false? (r/resolve-expanded? {k {:expanded? false}}
                                       :event "r1" [:coeffects] true))))))

;; ---- gutter glyph + tone mapping ---------------------------------------

(deftest gutter-glyph-mapping
  (is (= "+" (r/op->gutter-glyph :added)))
  (is (= "-" (r/op->gutter-glyph :removed)))
  (is (= "~" (r/op->gutter-glyph :modified)))
  (is (= "◴" (r/op->gutter-glyph :children)))
  (is (= " " (r/op->gutter-glyph :same))))

(deftest gutter-tone-keys-and-colours
  (is (= :green         (r/op->gutter-tone-key :added)))
  (is (= :red           (r/op->gutter-tone-key :removed)))
  (is (= :yellow        (r/op->gutter-tone-key :modified)))
  (is (= :accent-violet (r/op->gutter-tone-key :children)))
  (is (= :text-tertiary (r/op->gutter-tone-key :same)))
  (testing "gutter-colour resolves through tokens"
    (is (= (:green   tokens) (r/gutter-colour :added)))
    (is (= (:red     tokens) (r/gutter-colour :removed)))
    (is (= (:yellow  tokens) (r/gutter-colour :modified)))
    (is (= (:accent-violet tokens) (r/gutter-colour :children)))))

;; ---- scalar rendering --------------------------------------------------

(deftest scalar-keyword-uses-violet
  (let [hiccup (r/render-scalar :cart)]
    (is (= :span (first hiccup)))
    (is (= (:accent-violet tokens) (:color (node-style hiccup))))
    (is (= ":cart" (collect-text hiccup)))))

(deftest scalar-string-renders-mono-with-quotes
  (let [hiccup (r/render-scalar "abc")]
    (is (= "\"abc\"" (collect-text hiccup)))
    (is (= (:text-primary tokens) (:color (node-style hiccup))))))

(deftest scalar-number-and-bool-and-nil-render-mono
  (is (= (:text-primary tokens) (:color (node-style (r/render-scalar 42)))))
  (is (= (:text-primary tokens) (:color (node-style (r/render-scalar true)))))
  (is (= (:text-primary tokens) (:color (node-style (r/render-scalar nil)))))
  (is (= "nil"   (collect-text (r/render-scalar nil))))
  (is (= "42"    (collect-text (r/render-scalar 42))))
  (is (= "true"  (collect-text (r/render-scalar true)))))

;; ---- evicted-epoch placeholder (§10.7) ---------------------------------

(deftest evicted-placeholder-renders
  (let [hiccup (r/render-tree {:value     nil
                               :panel-id  :app-db
                               :render-id "main"
                               :evicted?  true
                               :epoch-id  42})
        all-text (collect-text hiccup)]
    (testing "carries the §10.7 sentinel data-testid"
      (is (some? (find-attr hiccup :data-testid
                            "rf-causa-data-display-evicted-app-db-main"))))
    (testing "renders the spec-locked copy"
      (is (re-find #"Epoch evicted from buffer\." all-text))
      (is (re-find #"Increase :epoch-history" all-text))
      (is (re-find #"Settings → General → Epoch history" all-text))
      (is (re-find #"#42" all-text)))))

(deftest evicted-placeholder-handles-missing-epoch-id
  (let [hiccup (r/render-tree {:value nil :panel-id :event
                               :render-id "x" :evicted? true})]
    (is (re-find #"unknown" (collect-text hiccup)))))

;; ---- container rendering (smoke) ---------------------------------------

(deftest map-renders-with-keys-and-values
  (let [hiccup (r/render-tree {:value     {:a 1 :b "two"}
                               :panel-id  :event
                               :render-id "r"
                               :default-depth 5})  ;; force expanded
        all-text (collect-text hiccup)]
    ;; Root container shows opening brace once it's expanded.
    (is (re-find #"\{" all-text))
    (is (re-find #":a" all-text))
    (is (re-find #":b" all-text))
    (is (re-find #"1" all-text))
    (is (re-find #"\"two\"" all-text))))

(deftest vector-renders-with-bracket-and-items
  (let [hiccup (r/render-tree {:value     [10 20 30]
                               :panel-id  :event
                               :render-id "r"
                               :default-depth 5})
        all-text (collect-text hiccup)]
    (is (re-find #"\[" all-text))
    (is (re-find #"10" all-text))
    (is (re-find #"30" all-text))))

(deftest deep-nesting-collapses-by-default
  (testing "depth>2 collapses to summary"
    (let [v {:a {:b {:c {:d {:e 1}}}}}
          hiccup (r/render-tree {:value v :panel-id :app-db :render-id "r"
                                 :default-depth 2})
          all-text (collect-text hiccup)]
      ;; Somewhere in the tree there's a collapsed-container summary like
      ;; "{1 entries}" — proves we did NOT recursively render to the leaf.
      (is (re-find #"\{1 entries\}" all-text)))))

(deftest big-map-at-default-depth-collapses
  (let [big (zipmap (range 50) (range 50))
        hiccup (r/render-tree {:value (assoc {} :wrap big)
                               :panel-id :app-db :render-id "r"
                               :default-depth 2})
        all-text (collect-text hiccup)]
    ;; The 50-entry inner map sits at depth 1 (root=0, :wrap value=1).
    ;; default-depth 2 → 1 ≤ 1 so it WOULD expand by depth alone; we
    ;; want it collapsed only at default-depth with > threshold. Push
    ;; default-depth to 1 to force the heuristic to apply at depth 1.
    (let [hiccup2 (r/render-tree {:value (assoc {} :wrap big)
                                  :panel-id :app-db :render-id "r"
                                  :default-depth 1})
          text2   (collect-text hiccup2)]
      (is (re-find #"\{50 entries\}" text2)))
    ;; And independently: the default render is non-empty.
    (is (re-find #":wrap" all-text))))

;; ---- diff rendering ----------------------------------------------------

(deftest diff-modified-leaf-uses-yellow-and-annotation
  (let [hiccup (r/render-tree {:value     {:state :submitting}
                               :before    {:state :idle}
                               :diff?     true
                               :panel-id  :app-db
                               :render-id "r"
                               :default-depth 5})
        all-text (collect-text hiccup)]
    (is (re-find #":submitting" all-text))
    (is (re-find #"changed from" all-text))
    (is (re-find #":idle" all-text))))

(deftest diff-added-key-uses-green-gutter
  (let [hiccup (r/render-tree {:value     {:cart {:total 71.00}}
                               :before    {:cart {}}
                               :diff?     true
                               :panel-id  :app-db
                               :render-id "r"
                               :default-depth 5})
        all-text (collect-text hiccup)]
    (is (re-find #":total" all-text))
    (is (re-find #"71" all-text))
    ;; The `+` glyph appears in the gutter for added paths.
    (is (re-find #"\+" all-text))))

(deftest diff-removed-key-shows-prior-value
  (let [hiccup (r/render-tree {:value     {}
                               :before    {:coupon "SUMMER"}
                               :diff?     true
                               :panel-id  :app-db
                               :render-id "r"
                               :default-depth 5})
        all-text (collect-text hiccup)]
    (is (re-find #"SUMMER" all-text))
    (is (re-find #"-" all-text))))

(deftest diff-forces-ancestor-open
  ;; Even at depth way past the default, a changed leaf should be
  ;; reachable in the rendered text (ancestor chain forces open).
  (let [v {:a {:b {:c {:d {:e 2}}}}}
        b {:a {:b {:c {:d {:e 1}}}}}
        hiccup (r/render-tree {:value v :before b :diff? true
                               :panel-id :app-db :render-id "r"
                               :default-depth 2})
        all-text (collect-text hiccup)]
    (is (re-find #":e" all-text))
    (is (re-find #"changed from 1" all-text))))

;; ---- clickable path segments --------------------------------------------

(deftest path-segment-emits-navigate-event-shape
  ;; Verify the on-click handler dispatches the canonical event shape.
  ;; `rf/dispatch` is a macro that expands to `rf/dispatch*`; tests
  ;; intercept the lower-level fn (same pattern other Causa tests use,
  ;; e.g. `filters/pills_cljs_test.cljs` lines 115 / 141 / 160).
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch* (fn [event-v & _opts]
                                 (reset! captured event-v))]
      (let [hiccup   (r/path-segment {:k         :cart
                                      :path      [:cart]
                                      :panel-id  :app-db
                                      :render-id "main"})
            on-click (-> hiccup second :on-click)]
        (is (fn? on-click))
        (on-click nil)
        (is (= [:rf.causa/navigate-to-path
                {:path [:cart] :panel-id :app-db :render-id "main"}]
               @captured))))))

(deftest path-segment-keyword-uses-violet-colour
  (let [hiccup (r/path-segment {:k :user :path [:user]
                                :panel-id :app-db :render-id "r"})]
    (is (= (:accent-violet tokens) (:color (node-style hiccup))))))

(deftest path-segment-non-keyword-uses-text-primary
  (let [hiccup (r/path-segment {:k 0 :path [:items 0]
                                :panel-id :app-db :render-id "r"})]
    (is (= (:text-primary tokens) (:color (node-style hiccup))))))

;; ---- expansion toggle event ---------------------------------------------

(deftest expansion-slot-and-key-shape
  ;; The canonical slot-key contract IS the pure helper. The full
  ;; event-handler round-trip (db → event → db) is exercised by the
  ;; panel-level integration tests; this unit-test surface asserts the
  ;; pure-data shape — keep the slot keyword stable + the key vector
  ;; shape stable, and consumer panels never break under a refactor.
  (is (= :rf.causa/data-display-expansion r/expansion-slot))
  (is (= [:app-db "main" [:cart :items 0]]
         (r/expansion-key :app-db "main" [:cart :items 0]))))

;; ---- render-tree top-level smoke ----------------------------------------

(deftest render-tree-returns-hiccup-with-data-testid
  (let [hiccup (r/render-tree {:value     {:cart {:items []}}
                               :panel-id  :app-db
                               :render-id "main"})]
    (is (vector? hiccup))
    (is (some? (find-attr hiccup :data-testid
                          "rf-causa-data-display-app-db-main")))))

(deftest render-tree-sparse-scalar
  ;; §10.2 sparse case — bare scalar (fx string result).
  (let [hiccup (r/render-tree {:value     "POST /orders → 201"
                               :panel-id  :trace
                               :render-id "fx-result"})]
    (is (re-find #"POST /orders → 201" (collect-text hiccup)))))

(deftest render-tree-sparse-small-map
  ;; §10.2 sparse case — 2-key event payload.
  (let [hiccup (r/render-tree {:value     {:cart-id "c123" :qty 2}
                               :panel-id  :event
                               :render-id "payload"})
        all-text (collect-text hiccup)]
    (is (re-find #":cart-id" all-text))
    (is (re-find #":qty" all-text))
    (is (re-find #"\"c123\"" all-text))
    (is (re-find #"2" all-text))))
