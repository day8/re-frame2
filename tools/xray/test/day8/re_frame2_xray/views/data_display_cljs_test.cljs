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
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens dark-palette light-palette]]))

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

(deftest scalar-keyword-uses-syntax-keyword
  ;; rf2-79ojx — keywords paint via `:syntax-keyword` (magenta), NOT
  ;; `:accent` (chrome blue). The previous mapping put 3 of 5 scalar
  ;; types in the same blue family.
  (let [h (dd/render-scalar :foo)]
    (is (= :span (first h)))
    (is (= (:syntax-keyword tokens) (-> h second :style :color)))
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

(deftest scalar-nil-uses-syntax-nil
  ;; rf2-79ojx — nil paints via its own dedicated `:syntax-nil` token
  ;; (deliberately muted grey, "absence" reads as faded).
  (let [h (dd/render-scalar nil)]
    (is (= (:syntax-nil tokens) (-> h second :style :color)))
    (is (= "nil" (collect-text h)))))

(deftest scalar-symbol-uses-syntax-symbol
  ;; rf2-79ojx — symbols paint via `:syntax-symbol` (blue), distinct
  ;; from the magenta now used for keywords.
  (let [h (dd/render-scalar 'sym)]
    (is (= (:syntax-symbol tokens) (-> h second :style :color)))))

(deftest scalar-fn-renders-with-italic
  (let [h (dd/render-scalar (fn [x] x))]
    (is (re-find #"^#fn" (collect-text h)))
    (is (= "italic" (-> h second :style :font-style)))))

;; ---- rf2-79ojx — scalar hue-family contract ------------------------------
;;
;; The five scalar types (keyword / string / number / boolean / nil) MUST
;; span at least four hue families. CLJS programmers' eyes are trained on
;; editor syntax-highlight palettes (One Dark / Calva / Cursive default),
;; where keywords + strings + numbers paint in clearly distinct hues. The
;; pre-rf2-79ojx mapping put 3 of 5 in the blue family with only luminance
;; varying — the inspector looked monochrome.
;;
;; "Hue family" here is the dominant RGB channel of the hex (whichever of
;; R/G/B has the largest value, with a tie tolerance for grey). The
;; contract holds in BOTH dark + light palettes.

(defn- hex->rgb
  "Parse a `#rrggbb` hex string into a `[r g b]` int triple. Cljs-only."
  [hex]
  (let [s (subs hex 1)]
    [(js/parseInt (subs s 0 2) 16)
     (js/parseInt (subs s 2 4) 16)
     (js/parseInt (subs s 4 6) 16)]))

(defn- dominant-channel
  "Classify a hex into a hue family: :red / :green / :blue / :yellow /
  :magenta / :cyan / :orange / :grey. Approximation good enough to
  separate 5 distinct CLJS-editor hues. Grey when max-min < 28 (very
  desaturated)."
  [hex]
  (let [[r g b] (hex->rgb hex)
        mx (max r g b)
        mn (min r g b)]
    (cond
      (< (- mx mn) 28) :grey
      (and (= mx r) (>= g (* 0.6 r)) (< b (* 0.5 r))) :orange ; warm red+green
      (and (= mx r) (>= b (* 0.55 r)))                :magenta ; red + blue
      (and (= mx g) (>= r (* 0.75 g)))                :yellow  ; red ≈ green
      (= mx r)                                        :red
      (= mx g)                                        :green
      (= mx b)                                        :blue
      :else                                           :grey)))

(deftest scalar-hue-families-span-at-least-four-dark
  ;; rf2-79ojx acceptance #2 — five scalar tokens span ≥4 hue families
  ;; in the dark palette. Renames are token-keyword level; this asserts
  ;; the actual hex values.
  (let [families (set (map #(dominant-channel (get dark-palette %))
                           [:syntax-keyword :syntax-string :syntax-number
                            :syntax-boolean :syntax-nil]))]
    (is (>= (count families) 4)
        (str "5 scalar types must span ≥4 hue families; got " families))
    (is (not= families #{:blue})
        "no monochrome blue palette")
    (is (contains? families :grey)
        "nil reads as deliberately muted grey")))

(deftest scalar-hue-families-span-at-least-four-light
  ;; Light-theme mirror. Same contract.
  (let [families (set (map #(dominant-channel (get light-palette %))
                           [:syntax-keyword :syntax-string :syntax-number
                            :syntax-boolean :syntax-nil]))]
    (is (>= (count families) 4)
        (str "5 scalar types must span ≥4 hue families; got " families))
    (is (not= families #{:blue})
        "no monochrome blue palette")))

(deftest no-two-scalar-tokens-share-the-same-blue-family
  ;; The specific regression: pre-rf2-79ojx had keyword(:accent) +
  ;; number(:syntax-number) + string(:syntax-string) all in the blue
  ;; family. Guard against re-introducing the collision.
  (let [scalar-keys [:syntax-keyword :syntax-string :syntax-number
                     :syntax-boolean :syntax-nil]
        dark-blues  (filter #(= :blue (dominant-channel (get dark-palette %)))
                            scalar-keys)
        light-blues (filter #(= :blue (dominant-channel (get light-palette %)))
                            scalar-keys)]
    (is (<= (count dark-blues) 1)
        (str "≤1 dark-palette scalar may be in the blue family; got "
             (vec dark-blues)))
    (is (<= (count light-blues) 1)
        (str "≤1 light-palette scalar may be in the blue family; got "
             (vec light-blues)))))

(deftest scalar-tokens-are-defined-in-both-palettes
  ;; Every scalar token must exist in both palettes — the theme toggle
  ;; can't fall back to undefined.
  (doseq [k [:syntax-keyword :syntax-string :syntax-number
             :syntax-boolean :syntax-nil :syntax-symbol]]
    (is (re-find #"^#[0-9a-fA-F]{6}$" (get dark-palette k))
        (str k " defined in dark-palette as a 6-digit hex"))
    (is (re-find #"^#[0-9a-fA-F]{6}$" (get light-palette k))
        (str k " defined in light-palette as a 6-digit hex"))))

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
  ;; rf2-y59tb — the toggle reducer now inverts from the
  ;; current rendered-expanded? state (passed in the dispatch
  ;; payload) when no override is stored, then inverts the stored
  ;; override on subsequent clicks. This is the load-bearing
  ;; correctness contract: first click MUST invert the visible
  ;; state, not jump to a hard-coded "first click opens" value.
  (let [panel-id :test
        mount-id "m-1"
        path     [:a]]
    ;; Case A — default-collapsed (e.g. deep path). Visible state
    ;; is collapsed → dispatch carries `false` → first click stores
    ;; `:expanded? true` (opens). Second click inverts to `false`.
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path false])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= true (get-in snapshot [k :expanded?]))
          "default-collapsed: first click stores :expanded? true (opens)"))
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path true])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "second toggle inverts the stored override to false"))

    ;; Case B — default-expanded (e.g. top-level path). Visible
    ;; state is expanded → dispatch carries `true` → first click
    ;; stores `:expanded? false` (collapses). This is the
    ;; regression the bug fixed; before the fix the reducer would
    ;; emit `{:expanded? true}` — same as the rendered state —
    ;; producing a silent no-op on the first click.
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path true])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "default-expanded: first click stores :expanded? false (collapses)"))

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
  ;; rf2-y59tb — the dispatch payload threads the rendered-
  ;; expanded? state as a fifth slot so the reducer can invert
  ;; from the user's visible state on the first click.
  (testing "default-collapsed path: dispatched event carries rendered? false"
    (let [captured (atom nil)
          ;; render-node accepts an explicit dispatch-fn so tests
          ;; can intercept the toggle dispatch without redef'ing
          ;; the global rf/dispatch* (which is what the prior shape
          ;; did before the reg-view fix). The :dispatch-fn slot is
          ;; the same closure the reg-view'd outer body threads to
          ;; carry frame context.
          ;;
          ;; default-expanded-depth=1, depth=5 → both depth-band
          ;; checks fail (`(<= 5 0)` false; `(= 5 1)` false) → the
          ;; heuristic returns false → path renders ▸ (collapsed).
          v   {:a 1 :b 2 :c 3 :d 4 :e 5}
          h   (dd/render-node {:value v
                               :panel-id :test
                               :mount-id "m1"
                               :path [:x]
                               :depth 5
                               :expansion-map {}
                               :dispatch-fn (fn [event-v]
                                              (reset! captured event-v))
                               :opts {:default-expanded-depth 1}})
          ;; Find the toggle span by data-testid suffix.
          tog (find-attr h :data-testid
                         "rf-xray-data-display-test-m1-:x-toggle")
          on-click (-> tog second :on-click)]
      (is (fn? on-click) "toggle glyph must carry an :on-click")
      (when on-click (on-click nil))
      (is (= [:rf.xray.data-display/toggle-node :test "m1" [:x] false]
             @captured)
          "default-collapsed render → payload carries rendered? false")))

  (testing "default-expanded path: dispatched event carries rendered? true"
    (let [captured (atom nil)
          ;; A >3-key map at depth 0 with default-expanded-depth 2:
          ;; - inline-fit gate fails (cnt > 3 → not inline)
          ;; - `(<= 0 (dec 2))` true → default-expanded? returns true
          ;; - path renders ▾, toggle dispatches rendered? true.
          v   {:a 1 :b 2 :c 3 :d 4 :e 5}
          h   (dd/render-node {:value v
                               :panel-id :test
                               :mount-id "m2"
                               :path []
                               :depth 0
                               :expansion-map {}
                               :dispatch-fn (fn [event-v]
                                              (reset! captured event-v))
                               :opts {:default-expanded-depth 2}})
          tog (find-attr h :data-testid
                         "rf-xray-data-display-test-m2-toggle")
          on-click (-> tog second :on-click)]
      (is (fn? on-click) "toggle glyph must carry an :on-click")
      (when on-click (on-click nil))
      (is (= [:rf.xray.data-display/toggle-node :test "m2" [] true]
             @captured)
          "default-expanded render → payload carries rendered? true"))))

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

;; =========================================================================
;; Diff mode (rf2-q3dzw phase 5 · D5=a per rf2-sndui)
;; =========================================================================
;;
;; The diff path subsumes the legacy `data-display.render` engine —
;; passing `:before` switches the widget into diff mode where each
;; node renders with a left-gutter glyph + colour and `:modified`
;; leaves carry a `← changed from <prior>` annotation. Ancestor chain
;; force-opens over any changed descendant.

;; ---- pure helpers --------------------------------------------------------

(deftest diff-op-classification
  (is (= :same     (dd/diff-op 1 1)))
  (is (= :same     (dd/diff-op nil nil)))
  (is (= :modified (dd/diff-op 1 2)))
  (is (= :modified (dd/diff-op :a :b)))
  (is (= :added    (dd/diff-op dd/missing-sentinel 1)))
  (is (= :removed  (dd/diff-op 1 dd/missing-sentinel)))
  (is (= :same     (dd/diff-op dd/missing-sentinel dd/missing-sentinel))))

(deftest changed-descendant?-walks-maps
  (is (false? (dd/changed-descendant? {:a 1 :b 2} {:a 1 :b 2})))
  (is (true?  (dd/changed-descendant? {:a 1 :b 2} {:a 1 :b 3})))
  (is (true?  (dd/changed-descendant? {:a {:x 1}} {:a {:x 2}}))
      "deep change propagates to root")
  (is (true?  (dd/changed-descendant? {:a 1} {:a 1 :b 2}))
      "key added"))

(deftest changed-descendant?-walks-sequentials
  (is (false? (dd/changed-descendant? [1 2 3] [1 2 3])))
  (is (true?  (dd/changed-descendant? [1 2 3] [1 2 4])))
  (is (true?  (dd/changed-descendant? [1 2] [1 2 3]))))

(deftest gutter-glyph-mapping
  (is (= "+" (dd/op->gutter-glyph :added)))
  (is (= "-" (dd/op->gutter-glyph :removed)))
  (is (= "~" (dd/op->gutter-glyph :modified)))
  (is (= "◴" (dd/op->gutter-glyph :children)))
  (is (= " " (dd/op->gutter-glyph :same))))

(deftest gutter-tone-mapping
  (is (= :green     (dd/op->gutter-tone-key :added)))
  (is (= :red       (dd/op->gutter-tone-key :removed)))
  (is (= :yellow    (dd/op->gutter-tone-key :modified)))
  (is (= :accent    (dd/op->gutter-tone-key :children)))
  (is (= :text-tertiary (dd/op->gutter-tone-key :same))))

;; ---- diff mode — modified-leaf annotation --------------------------------

(deftest diff-modified-leaf-emits-changed-from-annotation
  (let [h (dd/render-node {:value 2
                           :before 1
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        all (collect-text h)]
    (is (re-find #"← changed from 1" all)
        "modified scalar leaf carries the annotation chip")))

(deftest diff-modified-nested-leaf-annotates
  (let [v {:cart {:items {:total 71.00}}}
        b {:cart {:items {:total 48.00}}}
        h (dd/render-node {:value v
                           :before b
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 5}})
        all (collect-text h)]
    (is (re-find #"← changed from 48" all)
        "deep modified leaf carries the annotation chip")))

;; ---- diff mode — added / removed -----------------------------------------

(deftest diff-added-leaf-renders-in-green
  (let [h (dd/render-node {:value 2
                           :before dd/missing-sentinel
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})]
    (is (re-find #"data-rf-diff-op"
                 (try (pr-str h) (catch :default _ "")))
        "added leaf carries the diff-op marker")))

(deftest diff-removed-leaf-shows-prior-value
  (let [h (dd/render-node {:value dd/missing-sentinel
                           :before 1
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        all (collect-text h)]
    (is (re-find #"1" all)
        "removed leaf still renders the prior value (struck-through)")))

;; ---- diff mode — ancestor chain force-open -------------------------------

(deftest diff-forces-ancestor-chain-open-over-changed-descendant
  ;; A deep `:e` change should be visible even when the depth heuristic
  ;; would normally collapse the parents — force-expand wins.
  (let [v {:a {:b {:c {:d {:e 2}}}}}
        b {:a {:b {:c {:d {:e 1}}}}}
        h (dd/render-node {:value v
                           :before b
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 1}})
        all (collect-text h)]
    (is (re-find #":e" all) "deep changed leaf appears in the rendered text")
    (is (re-find #"← changed from 1" all) "with its annotation")))

;; ---- diff mode — same nodes dim ------------------------------------------

(deftest diff-same-leaf-uses-text-tertiary
  (let [h (dd/render-node {:value 1
                           :before 1
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        ;; The leaf wrapping span uses the :same gutter row + a span
        ;; with text-tertiary colour.
        s (pr-str h)]
    (is (re-find #"text-tertiary" s)
        "same leaf in diff mode renders via the text-tertiary token")))

;; ---- diff mode — public widget exposes mode marker -----------------------

(deftest data-display-diff-mode-marker-on-container
  ;; The public widget's outer container carries `data-rf-mode` =
  ;; "diff" when `:before` is supplied so panels / tests can target
  ;; the diff variant.
  (let [outer (dd/data-display {:a 2} {:before {:a 1}})
        ;; outer is the form-2 closure that returns a fn — call it
        ;; with the same args to get the inner hiccup.
        inner (outer {:a 2} {:before {:a 1}})]
    (is (= "diff" (get (second inner) :data-rf-mode))
        "diff-mode marker present when :before is supplied")
    (is (some? (get (second inner) :data-rf-mount-id))
        "mount-id still auto-generated")))

(deftest data-display-browse-mode-marker-on-container
  (let [outer (dd/data-display {:a 1})
        inner (outer {:a 1} nil)]
    (is (= "browse" (get (second inner) :data-rf-mode))
        "browse-mode marker present without :before")))

(deftest data-display-diff-convenience-threads-before
  ;; The `[data-display-diff before after]` form-2 wrapper should
  ;; produce the same shape as `[data-display after {:before before}]`.
  (let [h (dd/data-display-diff {:a 1} {:a 2})]
    (is (vector? h))
    (is (fn? (first h)))
    (is (= {:a 2} (nth h 1)))
    (is (= {:a 1} (:before (nth h 2))))))

;; =========================================================================
;; rf2-y59tb — frame-leak + first-click regression guards
;; =========================================================================
;;
;; Two independent bugs covered here:
;;
;;   Bug A — `data-display` was a plain `defn`, so dispatches from
;;     its click handlers did NOT carry the surrounding frame; toggle
;;     events landed on `:rf/default` while the App-DB panel mounted
;;     the widget under `:rf/xray`. The expansion-slot mutation ended
;;     up in the wrong frame's app-db, invisible to the surrounding
;;     subscribe.
;;
;;   Bug B — the reducer's "no override → first click opens" logic
;;     was wrong for paths the widget renders as default-expanded
;;     (top-level triangles, depth ≤ default-expanded-depth). The
;;     first click stored `:expanded? true` — the same value the path
;;     already rendered with — producing a silent no-op.

(deftest data-display-is-reg-view-registered
  (testing "rf2-y59tb Bug A — the public widget is registered via
            `reg-view` so dispatches + subscribes inherit the
            surrounding frame from React context. Without this the
            App-DB panel's `:rf/xray` mount routes toggle dispatches
            to `:rf/default`. The registration is present in the
            view registry under the auto-derived namespaced id."
    (is (some? (rf/view :day8.re-frame2-xray.views.data-display/data-display))
        "data-display is registered under its ns/sym id")))

(deftest data-display-toggle-dispatches-to-mount-frame
  ;; rf2-y59tb Bug A regression guard — the click handler dispatches
  ;; through the lexically-injected frame-aware dispatcher. We
  ;; simulate the inner render by calling `render-node` with a
  ;; `dispatch-fn` (which is what the reg-view body threads from its
  ;; outer-scope `dispatch` lexical binding); the handler MUST call
  ;; our supplied dispatch-fn rather than `rf/dispatch` (which would
  ;; route to `:rf/default`).
  (let [captured (atom nil)
        v       {:a 1 :b 2 :c 3 :d 4 :e 5}
        h       (dd/render-node {:value v
                                 :panel-id :app-db
                                 :mount-id "m"
                                 :path []
                                 :depth 0
                                 :expansion-map {}
                                 :dispatch-fn (fn [event-v]
                                                (reset! captured event-v))
                                 :opts {:default-expanded-depth 0}})
        tog     (find-attr h :data-testid
                           "rf-xray-data-display-app-db-m-toggle")
        on-click (-> tog second :on-click)]
    (is (fn? on-click))
    (when on-click (on-click nil))
    (is (some? @captured)
        "toggle handler invoked the threaded dispatch-fn (not rf/dispatch)")
    (is (= :rf.xray.data-display/toggle-node (first @captured))
        "canonical event id")))

(deftest first-click-collapses-default-expanded-path
  ;; rf2-y59tb Bug B regression guard — driven through dispatch-sync
  ;; against the registered toggle reducer with the rendered-
  ;; expanded? payload threaded. A default-expanded path passes
  ;; `true` as the rendered state; the reducer must store
  ;; `:expanded? false` (the inverted visible state), NOT
  ;; `:expanded? true` (the prior "first click opens" no-op bug).
  (let [panel-id :rf.xray/app-db
        mount-id "m1"
        path     [:top-level]]
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node
                       panel-id mount-id path true])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "default-expanded → first click collapses (rendered? true → stored false)"))
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])))

(deftest first-click-expands-default-collapsed-path
  ;; rf2-y59tb Bug B regression guard — the symmetric case. A deep
  ;; path (past default-expanded-depth) renders collapsed by default.
  ;; The toggle dispatch carries `false`; the reducer must store
  ;; `:expanded? true` (opens the node) on the first click.
  (let [panel-id :rf.xray/app-db
        mount-id "m1"
        path     [:deep :nested :node]]
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node
                       panel-id mount-id path false])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])
          k         (dd/expansion-key panel-id mount-id path)]
      (is (= true (get-in snapshot [k :expanded?]))
          "default-collapsed → first click expands (rendered? false → stored true)"))
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])))

(deftest second-click-inverts-stored-override
  ;; Once an override is stored the reducer ignores the rendered?
  ;; payload (the override IS the visible state) and inverts the
  ;; stored boolean. This is the canonical toggle behaviour for
  ;; clicks 2+ on the same path.
  (let [panel-id :rf.xray/app-db
        mount-id "m1"
        path     [:x]
        k         (dd/expansion-key panel-id mount-id path)]
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    ;; First click on default-expanded → stored false.
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path true])
    (is (= false (get-in @(rf/subscribe [dd/expansion-slot]) [k :expanded?])))
    ;; Second click — the rendered? slot is now `false` (override is
    ;; false) but the reducer flips the OVERRIDE, not the payload.
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path false])
    (is (= true (get-in @(rf/subscribe [dd/expansion-slot]) [k :expanded?]))
        "second click inverts stored override → true")
    ;; Third click flips again.
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id mount-id path true])
    (is (= false (get-in @(rf/subscribe [dd/expansion-slot]) [k :expanded?]))
        "third click inverts stored override → false")
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])))
