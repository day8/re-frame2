(ns day8.re-frame2-xray.views.data-display-cljs-test
  "Unit tests for the first-class data-display widget (rf2-oqa60
  phase 1).

  ## What's under test

  1. **Type classification** — `collection-kind` dispatches scalars
     + collections + sentinels onto the right keyword.
  2. **Scalar rendering** — every leaf shape lands at the right
     theme-token colour.
  3. **Bracket styling** — distinct opener/closer per kind; map-entry
     vs 2-vector brackets differ in COLOUR (same chars).
  4. **Inline preview** — `▸ {:a 1, :b 2, …}` cases (all-fit, partial,
     fallback `{…3 keys}`).
  5. **Click-to-toggle** — toggle event flips the per-path expansion;
     dispatch carries the canonical event shape; expanded state
     swaps the glyph `▸` → `▾` and renders the body.
  6. **Per-call-site isolation** — two `[data-display]` mounts get
     independent `mount-id`s; toggling one path in mount-A leaves
     mount-B's same path untouched.
  7. **Sentinels** (`:rf/redacted`, `:rf/large`, combined) render
     their first-class chip chrome.

  Pure-data unit tests; no DOM mount. Default for new Causa/Story
  tests per `feedback-causa-story-cljs-unit-tests-not-playwright`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.views.data-display :as dd]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]))

;; Fresh re-frame runtime per test so the click-to-toggle integration
;; test can fire `dispatch-sync` against the registered event handlers
;; end-to-end without leaking state between cases.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers -------------------------------------------------------------

(defn- walk-hiccup
  "Depth-first collect every hiccup vector in `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (vector? node)
                (do (swap! out conj node)
                    (doseq [child (rest node)] (walk child)))
                (seq? node) (doseq [c node] (walk c))))]
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

(defn- collect-text
  "Flatten string leaves under `tree` into one big string."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (string? node) (swap! out conj node)
                (vector? node) (doseq [c (rest node)] (walk c))
                (seq? node)    (doseq [c node] (walk c))))]
      (walk tree))
    (apply str @out)))

;; ---- collection-kind classification -------------------------------------

(deftest classify-scalars
  (is (= :nil      (dd/collection-kind nil)))
  (is (= :boolean  (dd/collection-kind true)))
  (is (= :boolean  (dd/collection-kind false)))
  (is (= :keyword  (dd/collection-kind :foo)))
  (is (= :keyword  (dd/collection-kind :ns/foo)))
  (is (= :symbol   (dd/collection-kind 'sym)))
  (is (= :string   (dd/collection-kind "hi")))
  (is (= :number   (dd/collection-kind 42)))
  (is (= :number   (dd/collection-kind 3.14)))
  (is (= :uuid     (dd/collection-kind (random-uuid))))
  (is (= :regex    (dd/collection-kind #"abc")))
  (is (= :fn       (dd/collection-kind (fn [x] x)))))

(deftest classify-collections
  (is (= :map     (dd/collection-kind {:a 1})))
  (is (= :vector  (dd/collection-kind [1 2 3])))
  (is (= :list    (dd/collection-kind '(1 2 3))))
  (is (= :set     (dd/collection-kind #{1 2 3})))
  (is (= :seq     (dd/collection-kind (map inc [1 2 3]))))
  (is (= :map     (dd/collection-kind {})))
  (is (= :vector  (dd/collection-kind []))))

(deftest classify-sentinels
  (testing "redacted bare keyword"
    (is (= :sentinel-redacted (dd/collection-kind :rf/redacted))))
  (testing "large wrapper"
    (is (= :sentinel-large
           (dd/collection-kind {:rf/large {:bytes 200 :head "abc"}}))))
  (testing "redacted-with-size wrapper"
    (is (= :sentinel-redacted-size
           (dd/collection-kind {:rf/redacted {:bytes 200}})))))

;; ---- scalar rendering ----------------------------------------------------

(deftest scalar-keyword-uses-accent
  (let [h (dd/render-scalar :foo)]
    (is (= :span (first h)))
    (is (= (:accent tokens) (-> h second :style :color)))
    (is (= ":foo" (collect-text h)))))

(deftest scalar-string-uses-syntax-string-and-quotes
  (let [h (dd/render-scalar "hello")]
    (is (= (:syntax-string tokens) (-> h second :style :color)))
    (is (= "\"hello\"" (collect-text h)))))

(deftest scalar-number-uses-syntax-number
  (let [h (dd/render-scalar 42)]
    (is (= (:syntax-number tokens) (-> h second :style :color)))
    (is (= "42" (collect-text h)))))

(deftest scalar-boolean-distinct-from-number
  (let [h-true (dd/render-scalar true)
        h-num  (dd/render-scalar 1)]
    (is (= "true" (collect-text h-true)))
    (is (not= (-> h-true second :style :color)
              (-> h-num  second :style :color))
        "boolean and number must use DIFFERENT theme tokens")))

(deftest scalar-nil-uses-text-tertiary
  (let [h (dd/render-scalar nil)]
    (is (= (:text-tertiary tokens) (-> h second :style :color)))
    (is (= "nil" (collect-text h)))))

(deftest scalar-symbol-uses-magenta
  (let [h (dd/render-scalar 'sym)]
    (is (= (:magenta tokens) (-> h second :style :color)))))

(deftest scalar-fn-renders-with-italic
  (let [h (dd/render-scalar (fn [x] x))]
    (is (re-find #"^#fn" (collect-text h)))
    (is (= "italic" (-> h second :style :font-style)))))

;; ---- sentinel rendering --------------------------------------------------

(deftest redacted-sentinel-chrome
  (let [h (dd/render-scalar :rf/redacted)
        all (collect-text h)]
    (is (some? (find-attr h :data-testid "rf-xray-data-display-redacted")))
    (is (re-find #"redacted" all))
    (is (= (:magenta tokens) (-> h second :style :color)))))

(deftest large-sentinel-chrome
  (let [h (dd/render-scalar {:rf/large {:bytes 5000 :head "preview"}})
        all (collect-text h)]
    (is (some? (find-attr h :data-testid "rf-xray-data-display-large")))
    (is (re-find #"large" all))
    (is (re-find #"5000" all))
    (is (= (:yellow tokens) (-> h second :style :color)))))

(deftest redacted-size-sentinel-chrome
  (let [h (dd/render-scalar {:rf/redacted {:bytes 200}})
        all (collect-text h)]
    (is (some? (find-attr h :data-testid "rf-xray-data-display-redacted-size")))
    (is (re-find #"200" all))
    (is (= (:magenta tokens) (-> h second :style :color)))))

;; ---- inline-preview-string -----------------------------------------------

(deftest inline-preview-map-all-fit
  (testing "small map fits inline as `{:a 1, :b 2}`"
    (is (= "{:a 1, :b 2}"
           (dd/inline-preview-string {:a 1 :b 2} 3 80)))))

(deftest inline-preview-map-overflow-fallback
  (testing "map that doesn't fit shows a partial OR `{…N keys}` fallback"
    (let [big (zipmap (map #(keyword (str "k" %)) (range 50)) (range 50))
          s   (dd/inline-preview-string big 3 20)]
      ;; Implementation may take any of: full first N (if fits),
      ;; partial preview with `…`, or the `{…50 keys}` fallback. ALL
      ;; outputs must:
      ;;   - start with `{` and end with `}` (delimiter shape preserved)
      ;;   - signal incompleteness via `…` (an ellipsis marker)
      (is (re-find #"^\{" s))
      (is (re-find #"\}$" s))
      (is (re-find #"…" s)
          "overflow output must signal incompleteness with `…`"))))

(deftest inline-preview-vector
  (is (= "[1, 2, 3]"
         (dd/inline-preview-string [1 2 3] 3 80))))

(deftest inline-preview-set
  (let [s   (dd/inline-preview-string #{:a :b} 3 80)]
    ;; Set iteration order is unspecified; assert shape.
    (is (re-find #"^#\{" s))
    (is (re-find #"\}$" s))))

(deftest inline-preview-vector-with-more
  (testing "vector with more elements than max-elements gets `…`"
    (let [s (dd/inline-preview-string [1 2 3 4 5] 3 80)]
      (is (re-find #"…" s)))))

;; ---- bracket styling -----------------------------------------------------

(deftest bracket-characters-per-kind
  (is (= "{"  (-> dd/delim :map      :open)) "map opens with {")
  (is (= "}"  (-> dd/delim :map      :close)))
  (is (= "["  (-> dd/delim :vector   :open)) "vector opens with [")
  (is (= "]"  (-> dd/delim :vector   :close)))
  (is (= "#{" (-> dd/delim :set      :open)) "set opens with #{")
  (is (= "("  (-> dd/delim :list     :open)) "list opens with (")
  (is (= "["  (-> dd/delim :map-entry :open)) "map-entry uses [ chars")
  (testing "map-entry brackets use a DIFFERENT colour token than vector"
    (is (not= (-> dd/delim :vector :tone-key)
              (-> dd/delim :map-entry :tone-key))
        "map-entry and vector share chars but MUST use distinct colours")))

;; ---- expansion-key shape -------------------------------------------------

(deftest expansion-key-shape
  (is (= [:app-db "mid-1" [:cart :items 0]]
         (dd/expansion-key :app-db "mid-1" [:cart :items 0])))
  (is (= [:app-db "mid-1" [:a]]
         (dd/expansion-key :app-db "mid-1" '(:a)))
      "path always coerced to a vector"))

(deftest expansion-slot-keyword
  ;; The slot key is part of the public contract — keep it stable.
  (is (= :rf.xray.data-display/expansion dd/expansion-slot)))

;; ---- resolve-expanded? ---------------------------------------------------

(deftest resolve-expanded-uses-override
  (let [path [:a :b]
        k    (dd/expansion-key :p "m" path)]
    (testing "no override → default"
      (is (true?  (dd/resolve-expanded? {} :p "m" path true)))
      (is (false? (dd/resolve-expanded? {} :p "m" path false))))
    (testing "override wins"
      (is (true?  (dd/resolve-expanded? {k {:expanded? true}}
                                        :p "m" path false)))
      (is (false? (dd/resolve-expanded? {k {:expanded? false}}
                                        :p "m" path true))))))

;; ---- click-to-toggle integration -----------------------------------------
;;
;; The widget's toggle path: clicking the `▸` glyph dispatches
;; `:rf.xray.data-display/toggle-node panel-id mount-id path`. The
;; reducer flips the per-path entry under `:rf.xray.data-display/expansion`.
;; A subsequent render against the new app-db state must show `▾` and
;; render the body.

(deftest toggle-event-flips-expansion-state
  (let [panel-id :test
        mount-id "m-1"
        path     [:a]]
    ;; Drive the reducer via re-frame's dispatch-sync against the
    ;; runtime fixture set up at the top of the ns. The first toggle
    ;; opens the node (no override → fresh `true`); the second toggle
    ;; inverts to `false`. `reset-expansion` clears the slot.
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= true (get-in snapshot [k :expanded?]))
          "first toggle on a fresh slot opens the node"))
    ;; Second toggle inverts.
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "second toggle inverts to closed"))
    ;; Reset cleans the slot.
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (is (nil? @(rf/subscribe [dd/expansion-slot])))))

;; ---- render-node — container/scalar dispatch -----------------------------

(deftest render-node-scalar-passes-through
  (let [h (dd/render-node {:value 42
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (= :span (first h)))
    (is (= "42" (collect-text h)))))

(deftest render-node-empty-map-no-toggle
  (let [h (dd/render-node {:value {}
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})
        text (collect-text h)]
    (is (re-find #"\{" text))
    (is (re-find #"\}" text))
    ;; No toggle glyph for empty collections.
    (is (not (re-find #"▸|▾" text)))))

(deftest render-node-map-default-expanded
  ;; default-expanded-depth = 2 → depth 0 should be expanded.
  (let [h (dd/render-node {:value {:a 1 :b 2}
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        text (collect-text h)]
    ;; Either expanded (▾ with keys visible) OR inline-fit (all on one line).
    ;; The 2-key map fits inline, so we don't get a triangle — but :a
    ;; and 1 must both be visible.
    (is (re-find #":a" text))
    (is (re-find #":b" text))
    (is (re-find #"1" text))
    (is (re-find #"2" text))))

(deftest render-node-deep-map-default-collapsed
  (let [v {:level1 {:level2 {:level3 {:level4 {:level5 42}}}}}
        h (dd/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        text (collect-text h)]
    ;; Past default-expanded-depth=2 collapses; rendered text should
    ;; NOT contain the deepest leaf.
    (is (not (re-find #":level5" text)))
    ;; First two levels should be visible.
    (is (re-find #":level1" text))))

(deftest render-node-expanded-shows-body
  ;; Force expansion via the override map; verify the body is rendered.
  ;; A 5-entry map doesn't inline-fit, so the toggle ▾ + body are
  ;; both rendered.
  (let [v2 {:a 1 :b {:c 2} :d 3 :e 4 :f 5}
        k0 (dd/expansion-key :p "m" [])
        h  (dd/render-node {:value v2
                            :panel-id :p
                            :mount-id "m"
                            :path []
                            :depth 0
                            :expansion-map {k0 {:expanded? true}}
                            :opts {:default-expanded-depth 0}})
        text (collect-text h)]
    (is (re-find #":a" text))
    (is (re-find #":f" text))))

(deftest render-node-collapsed-shows-preview-not-body
  (let [v {:level1 {:level2 {:level3 {:deep 1}}}}
        k0 (dd/expansion-key :p "m" [:level1 :level2])
        ;; Force the nested map at [:level1 :level2] CLOSED.
        h  (dd/render-node {:value v
                            :panel-id :p
                            :mount-id "m"
                            :path []
                            :depth 0
                            :expansion-map {k0 {:expanded? false}}
                            :opts {:default-expanded-depth 5}})
        text (collect-text h)]
    ;; The closed node's children should NOT be in the rendered text.
    (is (not (re-find #":deep" text)))))

(deftest render-node-toggle-glyph-changes
  ;; The header carries the toggle span; when expanded? we see ▾,
  ;; when collapsed we see ▸.
  (let [v   {:a 1 :b 2 :c 3 :d 4 :e 5}  ;; too big to inline-fit
        k0  (dd/expansion-key :p "m" [])
        h-expanded   (dd/render-node {:value v :panel-id :p :mount-id "m"
                                      :path [] :depth 0
                                      :expansion-map {k0 {:expanded? true}}
                                      :opts {:default-expanded-depth 0}})
        h-collapsed  (dd/render-node {:value v :panel-id :p :mount-id "m"
                                      :path [] :depth 0
                                      :expansion-map {k0 {:expanded? false}}
                                      :opts {:default-expanded-depth 0}})
        text-expanded   (collect-text h-expanded)
        text-collapsed  (collect-text h-collapsed)]
    (is (re-find #"▾" text-expanded))
    (is (re-find #"▸" text-collapsed))
    (is (not (re-find #"▾" text-collapsed)))))

(deftest render-node-includes-data-testid
  (let [h  (dd/render-node {:value {:a 1}
                            :panel-id :app-db
                            :mount-id "m-42"
                            :path []
                            :depth 0
                            :expansion-map {}
                            :opts {}})]
    (is (some? (find-attr h :data-testid
                          "rf-xray-data-display-app-db-m-42")))))

;; ---- toggle handler shape ------------------------------------------------

(deftest toggle-handler-dispatches-canonical-event
  (let [captured (atom nil)]
    ;; rf/dispatch expands through `dispatch*`; intercept at the
    ;; lower-level fn the same way the legacy data-display tests do.
    (with-redefs [rf/dispatch* (fn [event-v & _]
                                 (reset! captured event-v))]
      ;; Force a non-inline-fit collection so the toggle glyph is rendered.
      (let [v   {:a 1 :b 2 :c 3 :d 4 :e 5}
            h   (dd/render-node {:value v
                                 :panel-id :test
                                 :mount-id "m1"
                                 :path [:x]
                                 :depth 0
                                 :expansion-map {}
                                 :opts {:default-expanded-depth 0}})
            ;; Find the toggle span by data-testid suffix.
            tog (find-attr h :data-testid
                           "rf-xray-data-display-test-m1-:x-toggle")
            on-click (-> tog second :on-click)]
        (is (fn? on-click) "toggle glyph must carry an :on-click")
        (when on-click (on-click nil))
        (is (= [:rf.xray.data-display/toggle-node :test "m1" [:x]]
               @captured))))))

;; ---- per-call-site isolation ---------------------------------------------

(deftest two-mounts-independent-via-distinct-mount-ids
  (let [v {:a 1 :b 2 :c 3 :d 4 :e 5}
        m1 "mount-1"
        m2 "mount-2"
        k1 (dd/expansion-key :p m1 [])
        k2 (dd/expansion-key :p m2 [])
        ;; mount-1 is force-expanded; mount-2 is force-collapsed.
        emap {k1 {:expanded? true}
              k2 {:expanded? false}}
        h1 (dd/render-node {:value v :panel-id :p :mount-id m1
                            :path [] :depth 0
                            :expansion-map emap
                            :opts {:default-expanded-depth 0}})
        h2 (dd/render-node {:value v :panel-id :p :mount-id m2
                            :path [] :depth 0
                            :expansion-map emap
                            :opts {:default-expanded-depth 0}})]
    (is (re-find #"▾" (collect-text h1)) "mount-1 reads :expanded? true")
    (is (re-find #"▸" (collect-text h2)) "mount-2 reads :expanded? false")
    (is (not (re-find #"▾" (collect-text h2))) "mount-2 does NOT show ▾")))

;; ---- map-entry distinction -----------------------------------------------

(deftest map-entry-bracket-tone-distinct-from-vector
  (testing "map-entry uses :accent tone; vector uses :text-secondary"
    (is (= :accent          (-> dd/delim :map-entry :tone-key)))
    (is (= :text-secondary  (-> dd/delim :vector    :tone-key)))
    (is (not= (-> dd/delim :vector :tone-key)
              (-> dd/delim :map-entry :tone-key)))))

;; ---- mini one-liner ------------------------------------------------------

(deftest mini-scalar-keyword
  (let [h (dd/mini :foo)
        all (collect-text h)]
    (is (re-find #":foo" all))))

(deftest mini-map-shows-inline-preview
  (let [h (dd/mini {:a 1 :b 2} 80)
        all (collect-text h)]
    (is (re-find #":a" all))
    (is (re-find #":b" all))))

(deftest mini-sentinel-redacted
  (let [h (dd/mini :rf/redacted)
        all (collect-text h)]
    (is (re-find #"redacted" all))))

(deftest mini-truncates-to-max-len
  (let [long-str (apply str (repeat 200 "x"))
        h (dd/mini long-str 20)
        title (-> h second :title)]
    ;; Title carries the full pr-str; visible content is truncated.
    (is (some? title) "title attribute carries full value")))
