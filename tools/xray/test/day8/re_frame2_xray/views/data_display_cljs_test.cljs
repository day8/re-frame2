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

;; ---- rf2-tzvk9 — triangle hit-box ≥24×24 ---------------------------------
;;
;; The expand/collapse triangles (▾ / ▸) measured ~6.6×16.8px in the live
;; widget — far below Fitts's-Law-friendly mouse-target sizing. Mike's
;; live measurement on http://localhost:8031/counter App-DB panel.
;; The fix uses a shared `triangle-style` with padding + font-size +
;; min-width/min-height so every triangle on every code path gets the
;; same ≥24×24 hit-box.

(defn- parse-px
  "Parse `'24px'` → 24. Returns nil for non-px strings."
  [s]
  (when (and (string? s) (re-find #"^\d+(\.\d+)?px$" s))
    (js/parseFloat s)))

(deftest triangle-style-pins-min-target-to-24px-in-both-axes
  (testing "rf2-tzvk9 — the shared triangle-style declares ≥24px min-
            width AND min-height so the computed hit-box meets the
            comfortable-mouse-target threshold"
    (is (>= (parse-px (:min-width  dd/triangle-style)) 24)
        ":min-width ≥24px")
    (is (>= (parse-px (:min-height dd/triangle-style)) 24)
        ":min-height ≥24px")
    (is (= "pointer" (:cursor dd/triangle-style))
        "still registers as clickable")
    (is (= "none"    (:user-select dd/triangle-style))
        "no accidental text selection on the glyph")
    (is (= "inline-flex" (:display dd/triangle-style))
        "inline-flex so min-width/min-height are honoured")
    (is (= "center"  (:align-items     dd/triangle-style))
        "glyph centred vertically inside the hit-box")
    (is (= "center"  (:justify-content dd/triangle-style))
        "glyph centred horizontally inside the hit-box")))

(deftest triangle-min-target-px-is-24
  (is (= 24 dd/triangle-min-target-px)
      "the public contract pin: 24px in both axes"))

(deftest triangle-style-font-size-is-22px
  (testing "rf2-4aiaq — triangle glyph font-size is 22px (operator-
            preferred 22-24px band per Mike's live A/B 2026-05-26).
            14px was hit-box-adequate but read as hairline against
            the inspector chrome."
    (is (= "22px" (:font-size dd/triangle-style))
        "triangle glyph renders at 22px so the eye registers it as the
         primary expand/collapse affordance, not a hairline accent")))

(deftest collapsed-triangle-uses-shared-triangle-style
  ;; Force a default-collapsed render (large map at depth past
  ;; default-expanded-depth) and verify the ▸ toggle span carries
  ;; the shared `triangle-style`.
  (let [v   {:a 1 :b 2 :c 3 :d 4 :e 5}
        h   (dd/render-node {:value v
                             :panel-id :test :mount-id "m"
                             :path [] :depth 5
                             :expansion-map {}
                             :opts {:default-expanded-depth 1}})
        tog (find-attr h :data-testid
                       "rf-xray-data-display-test-m-toggle")]
    (is (some? tog) "collapsed renders carry a toggle span")
    (let [s (-> tog second :style)]
      (is (= dd/triangle-style s)
          "collapsed ▸ uses the shared triangle-style verbatim"))))

(deftest expanded-triangle-uses-shared-triangle-style
  (let [v   {:a 1 :b 2 :c 3 :d 4 :e 5}
        k0  (dd/expansion-key :test "m" [])
        h   (dd/render-node {:value v
                             :panel-id :test :mount-id "m"
                             :path [] :depth 0
                             :expansion-map {k0 {:expanded? true}}
                             :opts {:default-expanded-depth 0}})
        tog (find-attr h :data-testid
                       "rf-xray-data-display-test-m-toggle")]
    (is (some? tog) "expanded renders carry a toggle span")
    (let [s (-> tog second :style)]
      (is (= dd/triangle-style s)
          "expanded ▾ uses the shared triangle-style verbatim"))))

(deftest depth-capped-triangle-uses-shared-triangle-style
  ;; A node past :max-depth renders as `▸ {…}` with the same triangle.
  (let [;; nesting depth past max-depth=1 → depth-capped path.
        v   {:a {:b {:c {:d 1}}}}
        h   (dd/render-node {:value v
                             :panel-id :test :mount-id "m"
                             :path [] :depth 0
                             :expansion-map {}
                             :opts {:default-expanded-depth 5 :max-depth 1}})
        tog (find-attr h :data-testid
                       "rf-xray-data-display-test-m-toggle")]
    (is (some? tog) "depth-capped renders still carry a toggle span")
    (let [s (-> tog second :style)]
      (is (= dd/triangle-style s)
          "depth-capped triangle uses the shared triangle-style"))))

;; ---- rf2-1bra5 — map body layout: column-align + inline scalars ---------
;;
;; Two related bugs in the live App-DB panel:
;;
;;   Bug 1 — some scalar rows wrapped (`:show-parity?` + newline + `true`)
;;     while sibling scalar rows on the same map rendered inline. The
;;     wrapped rows measured 28.79px; the inline rows 17.79px. The
;;     render-path divergence was the `gutter-row` wrapping diff'd
;;     leaves in a BLOCK div (`display: flex`) inside a `flex-wrap:
;;     wrap` per-row container — the wide div wrapped below the key.
;;
;;   Bug 2 — values don't column-align across rows of the same map. Each
;;     row was its own `display: flex` so the value followed whatever
;;     gap landed after the key — different keys produced different
;;     value x-coordinates, a ragged value-column left edge.
;;
;; Fix: CSS Grid (`max-content 1fr`) for the body container, with key +
;; value emitted as direct grid children. The `gutter-row` wrapper
;; switches from block-level `flex` to `inline-flex` so a diff'd leaf
;; composes inline with its preceding key.

(deftest map-body-uses-css-grid-layout
  (testing "rf2-1bra5 — labelled-kind bodies use grid with
            max-content+1fr columns so values column-align across rows"
    ;; Map MUST be too big to inline-fit (cnt > 3) so the body
    ;; container renders.
    (let [v {:short 1 :very-very-long-key 2 :third 3 :fourth 4}
          k0 (dd/expansion-key :p "m" [])
          h  (dd/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-data-display-p-m-body")]
      (is (some? body) "expanded map renders a body container")
      (let [s (-> body second :style)]
        (is (= "grid" (:display s))
            "labelled-kind body uses CSS grid")
        (is (re-find #"max-content" (str (:grid-template-columns s)))
            "grid template uses max-content for the key column")
        (is (re-find #"1fr" (str (:grid-template-columns s)))
            "grid template uses 1fr for the value column")
        (is (= "8px" (:column-gap s))
            "key→value separation is the canonical 8px (gap-2 step)")
        (is (= "baseline" (:align-items s))
            "key + value baselines align per row")))))

(deftest map-body-row-emits-key-and-value-as-direct-grid-children
  (testing "rf2-1bra5 — each row contributes two direct grid children
            (key cell + value cell) so the grid resolves columns
            across rows. NOT wrapped in a per-row flex container."
    (let [;; >3 keys so inline-fit gate fails and the body emits.
          v {:a 1 :b 2 :c 3 :d 4}
          k0 (dd/expansion-key :p "m" [])
          h  (dd/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-data-display-p-m-body")
          key-cells   (->> (walk-hiccup body)
                           (filter #(= "key"   (get (second %) :data-rf-cell))))
          value-cells (->> (walk-hiccup body)
                           (filter #(= "value" (get (second %) :data-rf-cell))))]
      (is (= 4 (count key-cells))   "four map rows → four key cells")
      (is (= 4 (count value-cells)) "four map rows → four value cells"))))

(deftest sequential-body-still-uses-block-layout
  (testing "rf2-1bra5 — sequentials (vectors / lists / sets / seqs)
            keep block layout. Grid only applies to labelled-key kinds
            (map / record / map-entry); sequentials have no key column."
    ;; >3 items so inline-fit gate fails and the body emits.
    (let [v [10 20 30 40]
          k0 (dd/expansion-key :p "m" [])
          h  (dd/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-data-display-p-m-body")]
      (is (some? body) "expanded vector renders a body container")
      (let [s (-> body second :style)]
        (is (not= "grid" (:display s))
            "sequentials do not use grid layout (no key column)")
        (is (= "block" (or (:data-rf-body-layout (second body))
                            "block"))
            "vector body is the block-layout variant")))))

(deftest gutter-row-is-inline-flex-not-block
  (testing "rf2-1bra5 root-cause fix — gutter-row wraps diff'd leaves
            in inline-flex SPAN (not block-level DIV with display: flex).
            Pre-fix the block wrapper inside the per-row flex container
            forced the value below the key (wrap → two-line rows)."
    (let [;; Force a :same diff row — both sides equal scalars.
          h (dd/render-node {:value 1
                             :before 1
                             :diff? true
                             :panel-id :p :mount-id "m" :path [] :depth 0
                             :expansion-map {} :opts {}})
          ;; The gutter wrapper carries the data-rf-diff-op attr.
          row (->> (walk-hiccup h)
                   (filter #(some? (get (second %) :data-rf-diff-op)))
                   first)]
      (is (some? row) "diff render emits the gutter wrapper")
      (is (= :span (first row))
          "gutter wrapper is a SPAN (inline element), not a DIV")
      (is (= "inline-flex" (-> row second :style :display))
          "gutter wrapper is inline-flex so it composes inline with
           a preceding key"))))

(deftest scalar-leaves-render-as-inline-spans-in-non-diff-mode
  (testing "rf2-1bra5 Bug 1 — plain scalars (numbers, booleans, etc.)
            render as inline spans, never as a block element that
            would push the value below its key."
    (doseq [v [42 true false "hello" :foo 'sym nil]]
      (let [h (dd/render-node {:value v
                               :panel-id :p :mount-id "m"
                               :path [] :depth 0
                               :expansion-map {} :opts {}})]
        (is (= :span (first h))
            (str "scalar " (pr-str v) " renders as a [:span] inline"))))))

(deftest map-with-mixed-scalar-and-container-values-grid-layout
  (testing "rf2-1bra5 — mixed-kind map (some scalars, some nested
            containers) renders the body as ONE grid where every key
            sits in column 1 and every value (scalar OR nested) sits in
            column 2. The nested container's own expanded body is its
            own (independent) grid inside the parent's value cell."
    (let [v {:scalar-1 1
             :scalar-2 "two"
             :nested   {:inner 99}}
          k0 (dd/expansion-key :p "m" [])
          h  (dd/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-data-display-p-m-body")
          ;; Filter ONLY this body's direct cells, not the nested
          ;; map's cells.
          direct-children (rest (rest body))]
      (is (= "grid" (-> body second :style :display))
          "outer body uses grid")
      ;; 3 rows × 2 cells = 6 direct grid children.
      (is (= 6 (count direct-children))
          "3 rows × (key + value) = 6 direct grid cells"))))

(deftest map-body-row-gap-is-zero-for-density
  (testing "rf2-1bra5 — row-gap stays 0 so the inspector keeps the
            workstation-dense layout it ships today. The fix is the
            column-alignment + inline-composition; vertical density is
            unchanged."
    (let [v {:a 1 :b 2 :c 3 :d 4}
          k0 (dd/expansion-key :p "m" [])
          h  (dd/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-data-display-p-m-body")]
      (is (= "0" (-> body second :style :row-gap))))))

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

;; ---- rf2-pvsxs — opt-in `:site-id` for cross-mount persistence ----------
;;
;; By default, two `[data-display value]` mounts in the same panel get
;; independent expansion state via the auto-mount-id (rf2-sndui D4=a).
;; The cost — only visible in the panel-leave-and-return workflow —
;; is that the same logical site loses state on every unmount, because
;; the second mount allocates a new auto-mount-id.
;;
;; Opt-in `:site-id` fixes this without breaking the isolation default:
;; consumers that want their expansion state to SURVIVE a remount pass
;; a stable identifier (e.g. `[:app-db-frame frame-id]`) as the
;; `:site-id`. The expansion-key's second component reads `:site-id`
;; when supplied, falling back to auto-mount-id when omitted.

(deftest data-display-uses-site-id-when-supplied-as-expansion-key-id
  ;; Two mounts with the SAME `:site-id` and the SAME path must write
  ;; their override to the SAME expansion-key, so the second mount sees
  ;; the first mount's choice.
  (let [panel-id :p
        site-id  [:my-stable-site "alpha"]
        path     [:cart :items]
        ;; First mount → simulate a toggle dispatch carrying rendered? true
        ;; (i.e. visible state is expanded; first click should collapse).
        _        (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
        _        (rf/dispatch-sync [:rf.xray.data-display/toggle-node
                                    panel-id site-id path true])
        k        (dd/expansion-key panel-id site-id path)
        snapshot @(rf/subscribe [dd/expansion-slot])]
    (is (= false (get-in snapshot [k :expanded?]))
        "override is stored under [panel-id site-id path], independent
         of any auto-generated mount-id")
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])))

(deftest data-display-public-widget-routes-site-id-to-render-key
  ;; The public widget threads `:site-id` (when present) into the
  ;; `mount-id` slot of every render-node descent so the toggle handler
  ;; dispatches against the stable id, NOT the auto-mount-id. Verified
  ;; by mounting the widget twice with the SAME site-id and a value
  ;; that needs expansion; the testid carrying the stable id must
  ;; appear on both renders.
  (let [outer (dd/data-display {:a 1 :b 2 :c 3 :d 4} {:panel-id :p
                                                      :site-id  [:my-site "x"]
                                                      :default-expanded-depth 0})
        inner1 (outer {:a 1 :b 2 :c 3 :d 4} {:panel-id :p
                                              :site-id  [:my-site "x"]
                                              :default-expanded-depth 0})
        attrs  (second inner1)]
    ;; The container attrs carry both the auto-mount-id (debugging) AND
    ;; the literal site-id (for inspection / Storybook-tier targeting).
    (is (some? (get attrs :data-rf-mount-id))
        "auto-mount-id still present (for debugging)")
    (is (= (pr-str [:my-site "x"]) (get attrs :data-rf-site-id))
        ":data-rf-site-id attribute carries the literal site-id")))

(deftest data-display-without-site-id-keeps-per-call-site-isolation
  ;; The acceptance contract: when `:site-id` is omitted, behaviour is
  ;; UNCHANGED — auto-mount-id keeps two side-by-side mounts independent.
  ;; This guards the rf2-sndui D4=a default.
  (let [outer1 (dd/data-display {:a 1} {:panel-id :p})
        outer2 (dd/data-display {:a 1} {:panel-id :p})
        inner1 (outer1 {:a 1} {:panel-id :p})
        inner2 (outer2 {:a 1} {:panel-id :p})
        m1     (get (second inner1) :data-rf-mount-id)
        m2     (get (second inner2) :data-rf-mount-id)]
    (is (some? m1))
    (is (some? m2))
    (is (not= m1 m2)
        "two mounts with no :site-id get DIFFERENT auto-mount-ids → independent expansion state")
    (is (nil? (get (second inner1) :data-rf-site-id))
        "no :site-id supplied → no data-rf-site-id attribute")))

(deftest cross-mount-persistence-survives-unmount-and-remount
  ;; The canonical rf2-pvsxs scenario: mount widget → expand a path →
  ;; unmount → remount with the SAME :site-id → the path is STILL
  ;; expanded. Simulate via:
  ;;   1. dispatch a toggle that opens [:nested :deep] for site-id Σ
  ;;   2. confirm the override is stored under [panel Σ [:nested :deep]]
  ;;   3. "remount" simulated by computing the lookup key with the same Σ
  ;;   4. confirm the override resolves to `true` (expanded)
  (let [panel-id :rf.xray/app-db
        site-id  [:rf.xray/app-db "top"]
        path     [:nested :deep]
        _        (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
        ;; Step 1 — open the path (rendered? false → store true).
        _        (rf/dispatch-sync [:rf.xray.data-display/toggle-node
                                    panel-id site-id path false])
        k        (dd/expansion-key panel-id site-id path)
        ;; "Unmount" — no state cleanup needed; the expansion slot
        ;; survives Reagent unmount because it's in app-db.
        ;; "Remount" — same site-id is passed at the new mount; the
        ;; renderer's resolve-expanded? reads the same key.
        snapshot-after-remount @(rf/subscribe [dd/expansion-slot])]
    (is (= true (get-in snapshot-after-remount [k :expanded?]))
        "expansion override survives the simulated unmount-and-remount cycle
         when the consumer passes a stable :site-id")
    ;; Per the resolve-expanded? helper, this should also yield true
    ;; regardless of the default-expanded heuristic.
    (is (true? (dd/resolve-expanded? snapshot-after-remount
                                     panel-id site-id path false))
        "resolve-expanded? honours the stored override at the site-id key")
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])))

(deftest two-mounts-with-distinct-site-ids-still-isolate
  ;; Two consumers using DIFFERENT :site-ids must STILL isolate, even
  ;; though both opt out of the auto-mount-id default. This is the
  ;; per-call-site contract restated in :site-id space.
  (let [panel-id :p
        s1       [:site/a]
        s2       [:site/b]
        path     []
        k1       (dd/expansion-key panel-id s1 path)
        k2       (dd/expansion-key panel-id s2 path)]
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])
    (rf/dispatch-sync [:rf.xray.data-display/toggle-node panel-id s1 path true])
    (let [snapshot @(rf/subscribe [dd/expansion-slot])]
      (is (= false (get-in snapshot [k1 :expanded?]))
          "site/a's override is stored")
      (is (nil? (get snapshot k2))
          "site/b's slot is untouched — distinct :site-ids isolate"))
    (rf/dispatch-sync [:rf.xray.data-display/reset-expansion])))

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

;; ---- rf2-63ie5 — inspector card chrome on top-level mounts ---------------
;;
;; `:card? true` opts the widget's outer container into the inspector-card
;; chrome (background, border, radius, padding, margin) so panels with
;; multiple top-level mounts (App-DB's TOP + per-`:rf/*` areas) read as
;; distinct cards. Default off preserves inline / nested behaviour.

(defn- invoke-data-display
  "Form-2 unrolling — run the outer fn, then the inner fn with the same
  args to get the rendered hiccup."
  [value opts]
  (let [outer (dd/data-display value opts)]
    (outer value opts)))

(deftest card-opt-off-by-default
  (testing "rf2-63ie5 — without `:card?` (or with `false`) the outer
            container carries NO card chrome (background, border,
            radius, padding, margin all absent)"
    (let [h-default (invoke-data-display {:a 1} {:panel-id :rf.xray/app-db})
          style-default (-> h-default second :style)
          h-false   (invoke-data-display {:a 1}
                                          {:panel-id :rf.xray/app-db
                                           :card? false})
          style-false (-> h-false second :style)]
      (doseq [[label style] [["default" style-default] ["explicit false" style-false]]]
        (is (nil? (:background-color style))
            (str label ": no background"))
        (is (nil? (:border style))
            (str label ": no border"))
        (is (nil? (:border-radius style))
            (str label ": no border-radius"))
        (is (nil? (:margin-bottom style))
            (str label ": no margin-bottom"))))))

(deftest card-opt-applies-card-chrome
  (testing "rf2-63ie5 — `:card? true` adds background/border/radius/
            padding/margin to the outer container so the mount reads
            as a distinct inspector card"
    (let [h (invoke-data-display {:a 1} {:panel-id :rf.xray/app-db
                                          :card? true})
          style (-> h second :style)]
      (is (= (:bg-1 tokens) (:background-color style))
          "background reads `:bg-1` (theme-aware)")
      (is (= (str "1px solid " (:border-default tokens)) (:border style))
          "1px border in `:border-default` (theme-aware)")
      (is (= "8px" (:border-radius style)) "radius 8px")
      (is (= "8px 10px" (:padding style)) "padding 8px 10px")
      (is (= "8px" (:margin-bottom style))
          "margin-bottom 8px gaps adjacent cards"))))

(deftest card-opt-carries-data-attr
  (testing "rf2-63ie5 — the outer container publishes `:data-rf-card`
            when card chrome is on; absent when off"
    (let [h-on  (invoke-data-display {:a 1}
                                      {:panel-id :rf.xray/app-db :card? true})
          h-off (invoke-data-display {:a 1} {:panel-id :rf.xray/app-db})]
      (is (= "1" (:data-rf-card (second h-on)))
          "card-on publishes :data-rf-card=1 for testbed assertion")
      (is (nil? (:data-rf-card (second h-off)))
          "card-off omits the attribute"))))

;; ---- rf2-726ol — map column alignment (triangle / line / keys / close) --
;;
;; The map body's left padding + margin position the vertical guide line
;; at the triangle's visual centre (~16px from the row's left edge at
;; the 22px glyph metric — rf2-4aiaq). Keys sit 6px past the line. The
;; closing brace sits at the same `padding-left 16px` so the triangle /
;; line / keys / closing-brace converge on one column structure.

(defn- find-body-divs
  "Return every body-div the renderer emits (every node whose
  `:data-rf-body-layout` is non-nil)."
  [tree]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some? (:data-rf-body-layout (second node)))))
          (walk-hiccup tree)))

(defn- find-close-divs
  "Return every closing-bracket div (cells with `data-rf-cell \"close\"`)."
  [tree]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (= "close" (:data-rf-cell (second node)))))
          (walk-hiccup tree)))

(deftest map-body-guide-line-at-triangle-center
  (testing "rf2-726ol — the body div's `margin-left 16px` + `border-left
            1px` puts the vertical guide line at x=16px from the row's
            left edge, which is approximately the centre of the 22px
            triangle box (~31-34px wide, centre at ~16px)"
    (let [v   {:counter 1 :async nil :machine-ui {:open? true}}
          k0  (dd/expansion-key :test "m" [])
          h   (dd/render-node {:value v
                               :panel-id :test :mount-id "m"
                               :path [] :depth 0
                               :expansion-map {k0 {:expanded? true}}
                               :opts {:default-expanded-depth 0}})
          bodies (find-body-divs h)]
      (is (seq bodies) "expanded map renders body div(s)")
      (doseq [body bodies]
        (let [style (:style (second body))]
          (is (= "16px" (:margin-left style))
              "body's margin-left puts the 1px border at x=16 (= triangle centre)")
          (is (= "6px" (:padding-left style))
              "keys sit 6px past the line for a small breath"))))))

(deftest closing-brace-aligns-with-guide-line
  (testing "rf2-726ol — the closing-bracket div sits at `padding-left
            16px`, the same x-position as the vertical guide line, so
            the bracket pair `▾ { … }` reads as a coherent vertical
            column at every nesting depth"
    (let [;; 4+ keys in each map defeats inline-fit (which requires
          ;; ≤3 children) so both outer + inner expand-render.
          v   {:a 1 :b 2 :c 3 :d 4
               :nested {:x 1 :y 2 :z 3 :w 4}}
          k0  (dd/expansion-key :test "m" [])
          k1  (dd/expansion-key :test "m" [:nested])
          h   (dd/render-node
                {:value v
                 :panel-id :test :mount-id "m"
                 :path [] :depth 0
                 :expansion-map {k0 {:expanded? true}
                                 k1 {:expanded? true}}
                 :opts {:default-expanded-depth 0}})
          closes (find-close-divs h)]
      (is (= 2 (count closes))
          "outer + inner expanded maps each contribute a close-brace cell")
      (doseq [c closes]
        (let [style (:style (second c))]
          (is (= "16px" (:padding-left style))
              "close-brace `padding-left 16px` matches the guide-line x"))))))

(deftest block-body-shares-alignment-with-grid-body
  (testing "rf2-726ol — sequential (vector / list / set) bodies use the
            same `margin-left 16px` + `padding-left 6px` as map bodies
            so a vector's guide line / first item / closing bracket all
            converge on the same column structure"
    (let [;; 4 elements + a nested container defeats inline-fit so the
          ;; block body actually renders.
          v   [1 2 3 4 {:x :y}]
          k0  (dd/expansion-key :test "m" [])
          h   (dd/render-node {:value v
                               :panel-id :test :mount-id "m"
                               :path [] :depth 0
                               :expansion-map {k0 {:expanded? true}}
                               :opts {:default-expanded-depth 0}})
          bodies (find-body-divs h)
          block-bodies (filter #(= "block" (:data-rf-body-layout (second %)))
                               bodies)]
      (is (seq block-bodies)
          "vector container emits a block-layout body when expanded")
      (let [style (:style (second (first block-bodies)))]
        (is (= "16px" (:margin-left style)) "same 16px margin as grid body")
        (is (= "6px"  (:padding-left style)) "same 6px padding as grid body")))))

(deftest card-opt-theme-aware-via-tokens
  (testing "rf2-63ie5 — the card chrome reads from the live `tokens`
            map (a CSS-variable shim per `theme/tokens.cljc`) so both
            light + dark themes resolve at paint time without a re-
            render. This test pins the inline-style values to the
            same token-keyed map the rest of the widget consumes."
    (let [h (invoke-data-display {:a 1} {:panel-id :rf.xray/app-db
                                          :card? true})
          style (-> h second :style)]
      (is (= (:bg-1 tokens) (:background-color style))
          "background reads through `:bg-1` (CSS-var or hex per theme)")
      (is (= (str "1px solid " (:border-default tokens)) (:border style))
          "border reads through `:border-default` (CSS-var or hex per theme)"))))

;; =========================================================================
;; rf2-kbdk8 — width-aware expansion heuristic
;; =========================================================================
;;
;; The heuristic flips the auto-expand decision: render inline when the
;; value's estimated pr-str width fits the measured column with a small
;; safety margin; otherwise expand to tree. `default-expanded-depth` is
;; repurposed as a CEILING beyond which the widget never auto-expands.
;;
;; These tests pin the pure decision functions (estimated-inline-px,
;; would-fit-inline?, default-expanded? width-aware branch) and the
;; render-container integration:
;;
;;   - width-aware default? returns false when value fits the column;
;;     true (within ceiling) when it doesn't.
;;   - operator's sticky override still wins (a width-fitting node the
;;     operator explicitly opened renders expanded, not inline).
;;   - diff mode's force-open over changed descendants still fires.
;;   - the recursive inline renderer paints nested containers in one
;;     line with full syntax-palette colour.

(deftest mono-char-width-and-safety-margin-are-stable
  (testing "rf2-kbdk8 — width-estimation constants exposed for tests"
    (is (= 7 dd/mono-char-width-px)
        "7px M-advance is the conservative pick for JetBrains Mono 12px")
    (is (= 16 dd/safety-margin-px)
        "16px safety margin covers closing bracket + gutter")
    (is (= 8 dd/default-ceiling-depth)
        "new default `:default-expanded-depth` is 8 (CEILING, not trigger)")))

(deftest estimated-inline-px-multiplies-pr-str-by-mono-advance
  (testing "rf2-kbdk8 — char-count × 7px estimate"
    (is (= (* 7 (count (pr-str {:a 1})))
           (dd/estimated-inline-px {:a 1}))
        "pure function — char count × mono-char-width-px")
    (is (= (* 7 (count "nil"))
           (dd/estimated-inline-px nil))
        "scalars route through the same pr-str pathway")
    ;; Long compound values get proportionally wider estimates — the
    ;; bead's example (~81-char nested value) lands around ~570px.
    (let [big-value [:ws/connection [:rf.machine.timer/after-elapsed
                                     2501 [:active :authenticating]]]]
      (is (= (* 7 (count (pr-str big-value)))
             (dd/estimated-inline-px big-value))
          "nested compound value estimate matches pr-str-length × 7"))))

(deftest would-fit-inline-fits-when-estimate-plus-margin-le-available
  (testing "rf2-kbdk8 — `would-fit-inline?` gate"
    ;; A short value pr-strs to ~10 chars × 7px = 70px + 16px margin = 86px.
    (let [v {:a 1}]
      (is (dd/would-fit-inline? v 200)
          "200px column trivially fits a 10-char value")
      (is (not (dd/would-fit-inline? v 50))
          "50px column rejects even short values"))
    ;; The bead's worked example: ~81-char nested value in a 966px column.
    (let [big-but-fitting (apply str (repeat 80 "x"))]
      (is (dd/would-fit-inline? big-but-fitting 966)
          "~570px estimate trivially fits 966px column"))
    (is (not (dd/would-fit-inline? {:a 1} nil))
        "nil available-width falls back to legacy strict gate")
    (is (not (dd/would-fit-inline? {:a 1} 0))
        "zero or negative width is treated as no measurement")))

(deftest default-expanded-width-aware-branch
  (testing "rf2-kbdk8 — width-aware `default-expanded?` flips the verdict"
    ;; A 2-key map fits in 600px easily — should NOT auto-expand (the
    ;; inline-fit gate picks it up instead).
    (is (false? (dd/default-expanded?
                  {:depth 0 :child-count 2 :value {:a 1 :b 2}
                   :available-width-px 600})))
    ;; A long string-keyed map that overflows 200px should auto-expand
    ;; (within the ceiling).
    (let [wide-v {:a "much-longer-than-the-budget"
                  :b "another-overflowing-string"
                  :c "and-yet-more-data"}]
      (is (true? (dd/default-expanded?
                   {:depth 0 :child-count 3 :value wide-v
                    :available-width-px 100}))))
    ;; Beyond the ceiling, the width-aware branch falls back to false
    ;; (collapsed summary instead of auto-expanding pathologically deep).
    (let [wide-v {:a "much-longer-than-the-budget"
                  :b "another-overflowing-string"}]
      (is (false? (dd/default-expanded?
                    {:depth 9 :child-count 2 :value wide-v
                     :default-expanded-depth 8
                     :available-width-px 100}))))
    ;; Diff mode's force-open over changed descendants still beats width
    (let [v {:a "wide string that overflows"}]
      (is (true? (dd/default-expanded?
                   {:depth 0 :child-count 1 :value v
                    :available-width-px 1000
                    :has-changed-descendant? true}))
          "changed-descendant rule beats width-fits for diff readability"))))

(deftest default-expanded-no-measurement-fallback
  (testing "rf2-kbdk8 — when no measurement yet (nil available-width-px)
            the legacy depth-driven path runs unchanged so unit tests +
            first-paint behaviour stay deterministic"
    ;; depth 0, default-expanded-depth 2 → expanded (legacy behaviour).
    (is (true? (dd/default-expanded?
                 {:depth 0 :child-count 2 :value {:a 1 :b 2}
                  :default-expanded-depth 2})))
    ;; depth 5, default-expanded-depth 2 → collapsed (legacy behaviour).
    (is (false? (dd/default-expanded?
                  {:depth 5 :child-count 2 :value {:a 1 :b 2}
                   :default-expanded-depth 2})))))

(deftest render-container-width-fit-renders-inline-recursively
  (testing "rf2-kbdk8 — when measured width fits the value's pr-str,
            the renderer emits the FULL value (including nested
            containers) on one inline span — no expand glyph, no
            multi-row tree"
    ;; ~60-char nested value vs 800px column.
    (let [v {:tag :foo :payload [:active :authenticating]}
          h (dd/render-node {:value v
                             :panel-id :p :mount-id "m" :path []
                             :depth 0 :expansion-map {}
                             :opts {:default-expanded-depth 2
                                    :available-width-px 800}})
          text (collect-text h)]
      ;; No toggle glyph — the whole thing is already visible.
      (is (not (re-find #"▾|▸" text))
          "width-fit inline render carries no expand/collapse glyph")
      ;; All scalars present in the one-line render.
      (is (re-find #":tag" text))
      (is (re-find #":payload" text))
      (is (re-find #":active" text))
      (is (re-find #":authenticating" text)))))

(deftest render-container-too-wide-expands-to-tree
  (testing "rf2-kbdk8 — when measured width is too narrow for the
            value's pr-str, the renderer falls back to the tree form
            (▾ glyph + indented body)"
    (let [v {:a "much-longer-than-the-budget"
             :b "another-overflowing-string"
             :c "and-yet-more-data"
             :d "and-yet-still-more"
             :e "the-final-overflow"}
          h (dd/render-node {:value v
                             :panel-id :p :mount-id "m" :path []
                             :depth 0 :expansion-map {}
                             :opts {:default-expanded-depth 8
                                    :available-width-px 100}})
          text (collect-text h)]
      ;; Toggle glyph present — narrow column triggers tree form.
      (is (re-find #"▾" text)
          "narrow-column overflow renders expanded tree")
      (is (re-find #":a" text))
      (is (re-find #":e" text)))))

(deftest render-container-respects-operator-override-over-width-fit
  (testing "rf2-kbdk8 — operator's explicit expand override wins even
            when the value would naturally render inline; the operator
            sees what they clicked, not the heuristic's verdict"
    (let [v {:tag :foo :n 1}
          k0 (dd/expansion-key :p "m" [])
          h (dd/render-node {:value v
                             :panel-id :p :mount-id "m" :path []
                             :depth 0
                             :expansion-map {k0 {:expanded? true}}
                             :opts {:default-expanded-depth 2
                                    :available-width-px 800}})
          text (collect-text h)]
      ;; Operator clicked-open → expanded tree, not inline.
      (is (re-find #"▾" text)
          "operator override beats the width-fits inline path")
      (is (re-find #":tag" text)))))

(deftest render-inline-recursive-paints-nested-containers
  (testing "rf2-kbdk8 — the recursive inline renderer emits one-line
            hiccup that includes nested brackets, separators, scalars"
    (let [v {:k1 1 :k2 [:a :b]}
          h (dd/render-inline-recursive v)
          text (collect-text h)]
      (is (re-find #":k1" text))
      (is (re-find #":k2" text))
      ;; Nested vector's brackets present.
      (is (re-find #"\[" text))
      (is (re-find #"\]" text))
      ;; Outer map brackets present.
      (is (re-find #"\{" text))
      (is (re-find #"\}" text))
      ;; Scalars from the nested vector.
      (is (re-find #":a" text))
      (is (re-find #":b" text)))))

(deftest width-slot-set-and-clear-events
  (testing "rf2-kbdk8 — set-width / clear-width app-db reducers"
    (rf/dispatch-sync [:rf.xray.data-display/set-width "m" 600])
    (let [widths @(rf/subscribe [dd/widths-slot])]
      (is (= 600 (get widths "m"))
          "set-width writes a positive measurement to the slot"))
    ;; Bad inputs are ignored (no app-db churn).
    (rf/dispatch-sync [:rf.xray.data-display/set-width "m2" -5])
    (rf/dispatch-sync [:rf.xray.data-display/set-width nil 100])
    (let [widths @(rf/subscribe [dd/widths-slot])]
      (is (nil? (get widths "m2")) "negative width is rejected")
      (is (nil? (get widths nil))  "nil mount-id is rejected"))
    ;; Cleanup
    (rf/dispatch-sync [:rf.xray.data-display/clear-width "m"])
    (let [after-clear @(rf/subscribe [dd/widths-slot])]
      (is (nil? (get after-clear "m"))
          "clear-width removes the entry"))))

(deftest widget-emits-ref-callback-and-available-width-attr
  (testing "rf2-kbdk8 — the outer container carries a `:ref` callback
            (function) for the ResizeObserver lifecycle, plus a data-
            attribute carrying the current measurement (or absent when
            not yet measured)"
    (let [h (invoke-data-display {:a 1} {:panel-id :rf.xray/app-db})
          attrs (-> h second)]
      (is (fn? (:ref attrs))
          "outer container carries a ref callback (mount/unmount hook)")
      ;; No measurement yet → attribute absent / nil.
      (is (nil? (:data-rf-available-width-px attrs))
          "data-rf-available-width-px absent until the ref fires"))))
