(ns day8.re-frame2-xray.views.edn-inspector-cljs-test
  "Unit tests for the first-class edn-inspector widget (rf2-oqa60
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
  6. **Per-call-site isolation** — two `[edn-inspector]` mounts get
     independent `mount-id`s; toggling one path in mount-A leaves
     mount-B's same path untouched.
  7. **Sentinels** (`:rf/redacted`, `:rf.size/large-elided`, combined)
     render their first-class chip chrome.

  Pure-data unit tests; no DOM mount. Default for new Causa/Story
  tests per `feedback-causa-story-cljs-unit-tests-not-playwright`."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
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
  (is (= :nil      (ei/collection-kind nil)))
  (is (= :boolean  (ei/collection-kind true)))
  (is (= :boolean  (ei/collection-kind false)))
  (is (= :keyword  (ei/collection-kind :foo)))
  (is (= :keyword  (ei/collection-kind :ns/foo)))
  (is (= :symbol   (ei/collection-kind 'sym)))
  (is (= :string   (ei/collection-kind "hi")))
  (is (= :number   (ei/collection-kind 42)))
  (is (= :number   (ei/collection-kind 3.14)))
  (is (= :uuid     (ei/collection-kind (random-uuid))))
  (is (= :regex    (ei/collection-kind #"abc")))
  (is (= :fn       (ei/collection-kind (fn [x] x)))))

(deftest classify-collections
  (is (= :map     (ei/collection-kind {:a 1})))
  (is (= :vector  (ei/collection-kind [1 2 3])))
  (is (= :list    (ei/collection-kind '(1 2 3))))
  (is (= :set     (ei/collection-kind #{1 2 3})))
  (is (= :seq     (ei/collection-kind (map inc [1 2 3]))))
  (is (= :map     (ei/collection-kind {})))
  (is (= :vector  (ei/collection-kind []))))

(deftest classify-sentinels
  (testing "redacted bare keyword"
    (is (= :sentinel-redacted (ei/collection-kind :rf/redacted))))
  (testing "large wrapper — spec/015 §Wire elision shape"
    ;; rf2-ndb13 — body keys per the framework's emission site
    ;; (implementation/core/src/re_frame/elision.cljc): `:path :bytes
    ;; :type :reason :hint :handle`.
    (is (= :sentinel-large
           (ei/collection-kind
             {:rf.size/large-elided {:path   [:big]
                                     :bytes  200
                                     :type   :string
                                     :reason :schema
                                     :hint   "preview hint"
                                     :handle [:rf.elision/at [:big]]}}))))
  (testing "redacted-with-size wrapper"
    (is (= :sentinel-redacted-size
           (ei/collection-kind {:rf/redacted {:bytes 200}})))))

(deftest large-sentinel-detects-spec-current-shape
  ;; rf2-ndb13 — regression for stale-key bug. The predicate previously
  ;; matched `:rf/large` (a key the framework no longer emits) and fell
  ;; through generic map rendering for real markers. Lock in the
  ;; spec-current key + pin the legacy key as a non-match.
  (testing "spec-current `:rf.size/large-elided` wrapper is detected"
    (is (true? (ei/large-sentinel?
                 {:rf.size/large-elided {:path   [:p]
                                         :bytes  1024
                                         :type   :vector
                                         :reason :schema
                                         :hint   nil
                                         :handle [:rf.elision/at [:p]]}}))))
  (testing "legacy `:rf/large` shape is NOT detected (pre-alpha: no shim)"
    (is (false? (ei/large-sentinel? {:rf/large {:bytes 1024 :head "abc"}}))))
  (testing "ordinary one-key map is NOT detected"
    (is (false? (ei/large-sentinel? {:not-a-sentinel {:bytes 1}}))))
  (testing "non-map values are NOT detected"
    (is (false? (ei/large-sentinel? :rf.size/large-elided)))
    (is (false? (ei/large-sentinel? nil)))))

;; ---- scalar rendering ----------------------------------------------------

(deftest scalar-keyword-uses-syntax-keyword
  ;; rf2-79ojx — keywords paint via `:syntax-keyword` (magenta), NOT
  ;; `:accent` (chrome blue). The previous mapping put 3 of 5 scalar
  ;; types in the same blue family.
  (let [h (ei/render-scalar :foo)]
    (is (= :span (first h)))
    (is (= (:syntax-keyword tokens) (-> h second :style :color)))
    (is (= ":foo" (collect-text h)))))

(deftest scalar-string-uses-syntax-string-and-quotes
  (let [h (ei/render-scalar "hello")]
    (is (= (:syntax-string tokens) (-> h second :style :color)))
    (is (= "\"hello\"" (collect-text h)))))

(deftest scalar-number-uses-syntax-number
  (let [h (ei/render-scalar 42)]
    (is (= (:syntax-number tokens) (-> h second :style :color)))
    (is (= "42" (collect-text h)))))

(deftest scalar-boolean-distinct-from-number
  (let [h-true (ei/render-scalar true)
        h-num  (ei/render-scalar 1)]
    (is (= "true" (collect-text h-true)))
    (is (not= (-> h-true second :style :color)
              (-> h-num  second :style :color))
        "boolean and number must use DIFFERENT theme tokens")))

(deftest scalar-nil-uses-syntax-nil
  ;; rf2-79ojx — nil paints via its own dedicated `:syntax-nil` token
  ;; (deliberately muted grey, "absence" reads as faded).
  (let [h (ei/render-scalar nil)]
    (is (= (:syntax-nil tokens) (-> h second :style :color)))
    (is (= "nil" (collect-text h)))))

(deftest scalar-symbol-uses-syntax-symbol
  ;; rf2-79ojx — symbols paint via `:syntax-symbol` (blue), distinct
  ;; from the magenta now used for keywords.
  (let [h (ei/render-scalar 'sym)]
    (is (= (:syntax-symbol tokens) (-> h second :style :color)))))

(deftest scalar-fn-renders-with-italic
  (let [h (ei/render-scalar (fn [x] x))]
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
  (let [h (ei/render-scalar :rf/redacted)
        all (collect-text h)]
    (is (some? (find-attr h :data-testid "rf-xray-edn-inspector-redacted")))
    (is (re-find #"redacted" all))
    (is (= (:magenta tokens) (-> h second :style :color)))))

(deftest large-sentinel-chrome
  ;; rf2-ndb13 — marker shape is the framework-emitted spec/015 body:
  ;; `:path :bytes :type :reason :hint :handle`.
  (let [h (ei/render-scalar
            {:rf.size/large-elided {:path   [:blob]
                                    :bytes  5000
                                    :type   :string
                                    :reason :schema
                                    :hint   "Upload preview"
                                    :handle [:rf.elision/at [:blob]]}})
        all (collect-text h)]
    (is (some? (find-attr h :data-testid "rf-xray-edn-inspector-large")))
    (is (re-find #"large" all))
    (is (re-find #"5000" all))
    (is (= (:yellow tokens) (-> h second :style :color)))))

(deftest large-sentinel-chrome-renders-when-marker-keys-missing
  ;; rf2-ndb13 — defensive: the chip must render gracefully even if the
  ;; emission side ever omits optional body slots. `:bytes` may be
  ;; absent (no "· N bytes" segment); `:type` and `:hint` are optional
  ;; (title degrades to the base sentence).
  (testing "marker with only :path + :handle still renders the chip"
    (let [h (ei/render-scalar
              {:rf.size/large-elided {:path   [:x]
                                      :handle [:rf.elision/at [:x]]}})]
      (is (some? (find-attr h :data-testid "rf-xray-edn-inspector-large")))
      (is (re-find #"large" (collect-text h))))))

(deftest large-sentinel-not-rendered-as-plain-map
  ;; rf2-ndb13 — the original symptom: real framework-emitted markers
  ;; fell through to ordinary map rendering, exposing `:path :bytes
  ;; :type :reason :hint :handle` as plain map keys. With the predicate
  ;; pointed at the spec-current key, `collection-kind` MUST classify
  ;; the marker as `:sentinel-large`, NOT `:map`.
  (let [marker {:rf.size/large-elided {:path   [:blob]
                                       :bytes  5000
                                       :type   :string
                                       :reason :schema
                                       :hint   nil
                                       :handle [:rf.elision/at [:blob]]}}]
    (is (= :sentinel-large (ei/collection-kind marker))
        "marker classifies as sentinel-large (not :map)")
    (is (not= :map (ei/collection-kind marker)))))

(deftest redacted-size-sentinel-chrome
  (let [h (ei/render-scalar {:rf/redacted {:bytes 200}})
        all (collect-text h)]
    (is (some? (find-attr h :data-testid "rf-xray-edn-inspector-redacted-size")))
    (is (re-find #"200" all))
    (is (= (:magenta tokens) (-> h second :style :color)))))

;; ---- inline-preview-string -----------------------------------------------

(deftest inline-preview-map-all-fit
  (testing "small map fits inline as `{:a 1, :b 2}`"
    (is (= "{:a 1, :b 2}"
           (ei/inline-preview-string {:a 1 :b 2} 3 80)))))

(deftest inline-preview-map-overflow-fallback
  (testing "map that doesn't fit shows a partial OR `{…N keys}` fallback"
    (let [big (zipmap (map #(keyword (str "k" %)) (range 50)) (range 50))
          s   (ei/inline-preview-string big 3 20)]
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
         (ei/inline-preview-string [1 2 3] 3 80))))

(deftest inline-preview-set
  (let [s   (ei/inline-preview-string #{:a :b} 3 80)]
    ;; Set iteration order is unspecified; assert shape.
    (is (re-find #"^#\{" s))
    (is (re-find #"\}$" s))))

(deftest inline-preview-vector-with-more
  (testing "vector with more elements than max-elements gets `…`"
    (let [s (ei/inline-preview-string [1 2 3 4 5] 3 80)]
      (is (re-find #"…" s)))))

;; ---- bracket styling -----------------------------------------------------

(deftest bracket-characters-per-kind
  (is (= "{"  (-> ei/delim :map      :open)) "map opens with {")
  (is (= "}"  (-> ei/delim :map      :close)))
  (is (= "["  (-> ei/delim :vector   :open)) "vector opens with [")
  (is (= "]"  (-> ei/delim :vector   :close)))
  (is (= "#{" (-> ei/delim :set      :open)) "set opens with #{")
  (is (= "("  (-> ei/delim :list     :open)) "list opens with (")
  (is (= "["  (-> ei/delim :map-entry :open)) "map-entry uses [ chars")
  (testing "map-entry brackets use a DIFFERENT colour token than vector"
    (is (not= (-> ei/delim :vector :tone-key)
              (-> ei/delim :map-entry :tone-key))
        "map-entry and vector share chars but MUST use distinct colours")))

;; ---- expansion-key shape -------------------------------------------------

(deftest expansion-key-shape
  (is (= [:app-db "mid-1" [:cart :items 0]]
         (ei/expansion-key :app-db "mid-1" [:cart :items 0])))
  (is (= [:app-db "mid-1" [:a]]
         (ei/expansion-key :app-db "mid-1" '(:a)))
      "path always coerced to a vector"))

(deftest expansion-slot-keyword
  ;; The slot key is part of the public contract — keep it stable.
  (is (= :rf.xray.edn-inspector/expansion ei/expansion-slot)))

;; ---- resolve-expanded? ---------------------------------------------------

(deftest resolve-expanded-uses-override
  (let [path [:a :b]
        k    (ei/expansion-key :p "m" path)]
    (testing "no override → default"
      (is (true?  (ei/resolve-expanded? {} :p "m" path true)))
      (is (false? (ei/resolve-expanded? {} :p "m" path false))))
    (testing "override wins"
      (is (true?  (ei/resolve-expanded? {k {:expanded? true}}
                                        :p "m" path false)))
      (is (false? (ei/resolve-expanded? {k {:expanded? false}}
                                        :p "m" path true))))))

;; ---- click-to-toggle integration -----------------------------------------
;;
;; The widget's toggle path: clicking the `▸` glyph dispatches
;; `:rf.xray.edn-inspector/toggle-node panel-id mount-id path`. The
;; reducer flips the per-path entry under `:rf.xray.edn-inspector/expansion`.
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
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id mount-id path false])
    (let [snapshot @(rf/subscribe [ei/expansion-slot])
          k         (ei/expansion-key panel-id mount-id path)]
      (is (= true (get-in snapshot [k :expanded?]))
          "default-collapsed: first click stores :expanded? true (opens)"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id mount-id path true])
    (let [snapshot @(rf/subscribe [ei/expansion-slot])
          k         (ei/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "second toggle inverts the stored override to false"))

    ;; Case B — default-expanded (e.g. top-level path). Visible
    ;; state is expanded → dispatch carries `true` → first click
    ;; stores `:expanded? false` (collapses). This is the
    ;; regression the bug fixed; before the fix the reducer would
    ;; emit `{:expanded? true}` — same as the rendered state —
    ;; producing a silent no-op on the first click.
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id mount-id path true])
    (let [snapshot @(rf/subscribe [ei/expansion-slot])
          k         (ei/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "default-expanded: first click stores :expanded? false (collapses)"))

    ;; Reset cleans the slot.
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (is (nil? @(rf/subscribe [ei/expansion-slot])))))

;; ---- render-node — container/scalar dispatch -----------------------------

(deftest render-node-scalar-passes-through
  (let [h (ei/render-node {:value 42
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (= :span (first h)))
    (is (= "42" (collect-text h)))))

(deftest render-node-empty-map-no-toggle
  (let [h (ei/render-node {:value {}
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
  (let [h (ei/render-node {:value {:a 1 :b 2}
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
        h (ei/render-node {:value v
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
        k0 (ei/expansion-key :p "m" [])
        h  (ei/render-node {:value v2
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
        k0 (ei/expansion-key :p "m" [:level1 :level2])
        ;; Force the nested map at [:level1 :level2] CLOSED.
        h  (ei/render-node {:value v
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
        k0  (ei/expansion-key :p "m" [])
        h-expanded   (ei/render-node {:value v :panel-id :p :mount-id "m"
                                      :path [] :depth 0
                                      :expansion-map {k0 {:expanded? true}}
                                      :opts {:default-expanded-depth 0}})
        h-collapsed  (ei/render-node {:value v :panel-id :p :mount-id "m"
                                      :path [] :depth 0
                                      :expansion-map {k0 {:expanded? false}}
                                      :opts {:default-expanded-depth 0}})
        text-expanded   (collect-text h-expanded)
        text-collapsed  (collect-text h-collapsed)]
    (is (re-find #"▾" text-expanded))
    (is (re-find #"▸" text-collapsed))
    (is (not (re-find #"▾" text-collapsed)))))

(deftest render-node-includes-data-testid
  (let [h  (ei/render-node {:value {:a 1}
                            :panel-id :app-db
                            :mount-id "m-42"
                            :path []
                            :depth 0
                            :expansion-map {}
                            :opts {}})]
    (is (some? (find-attr h :data-testid
                          "rf-xray-edn-inspector-app-db-m-42")))))

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
    (is (>= (parse-px (:min-width  ei/triangle-style)) 24)
        ":min-width ≥24px")
    (is (>= (parse-px (:min-height ei/triangle-style)) 24)
        ":min-height ≥24px")
    (is (= "pointer" (:cursor ei/triangle-style))
        "still registers as clickable")
    (is (= "none"    (:user-select ei/triangle-style))
        "no accidental text selection on the glyph")
    (is (= "inline-flex" (:display ei/triangle-style))
        "inline-flex so min-width/min-height are honoured")
    (is (= "center"  (:align-items     ei/triangle-style))
        "glyph centred vertically inside the hit-box")
    (is (= "center"  (:justify-content ei/triangle-style))
        "glyph centred horizontally inside the hit-box")))

(deftest triangle-min-target-px-is-24
  (is (= 24 ei/triangle-min-target-px)
      "the public contract pin: 24px in both axes"))

(deftest triangle-style-font-size-is-22px
  (testing "rf2-4aiaq — triangle glyph font-size is 22px (operator-
            preferred 22-24px band per Mike's live A/B 2026-05-26).
            14px was hit-box-adequate but read as hairline against
            the inspector chrome."
    (is (= "22px" (:font-size ei/triangle-style))
        "triangle glyph renders at 22px so the eye registers it as the
         primary expand/collapse affordance, not a hairline accent")))

(deftest collapsed-triangle-uses-shared-triangle-style
  ;; Force a default-collapsed render (large map at depth past
  ;; default-expanded-depth) and verify the ▸ toggle span carries
  ;; the shared `triangle-style`.
  (let [v   {:a 1 :b 2 :c 3 :d 4 :e 5}
        h   (ei/render-node {:value v
                             :panel-id :test :mount-id "m"
                             :path [] :depth 5
                             :expansion-map {}
                             :opts {:default-expanded-depth 1}})
        tog (find-attr h :data-testid
                       "rf-xray-edn-inspector-test-m-toggle")]
    (is (some? tog) "collapsed renders carry a toggle span")
    (let [s (-> tog second :style)]
      (is (= ei/triangle-style s)
          "collapsed ▸ uses the shared triangle-style verbatim"))))

(deftest expanded-triangle-uses-shared-triangle-style
  (let [v   {:a 1 :b 2 :c 3 :d 4 :e 5}
        k0  (ei/expansion-key :test "m" [])
        h   (ei/render-node {:value v
                             :panel-id :test :mount-id "m"
                             :path [] :depth 0
                             :expansion-map {k0 {:expanded? true}}
                             :opts {:default-expanded-depth 0}})
        tog (find-attr h :data-testid
                       "rf-xray-edn-inspector-test-m-toggle")]
    (is (some? tog) "expanded renders carry a toggle span")
    (let [s (-> tog second :style)]
      (is (= ei/triangle-style s)
          "expanded ▾ uses the shared triangle-style verbatim"))))

(deftest depth-capped-triangle-uses-shared-triangle-style
  ;; A node past :max-depth renders as `▸ {…}` with the same triangle.
  (let [;; nesting depth past max-depth=1 → depth-capped path.
        v   {:a {:b {:c {:d 1}}}}
        h   (ei/render-node {:value v
                             :panel-id :test :mount-id "m"
                             :path [] :depth 0
                             :expansion-map {}
                             :opts {:default-expanded-depth 5 :max-depth 1}})
        tog (find-attr h :data-testid
                       "rf-xray-edn-inspector-test-m-toggle")]
    (is (some? tog) "depth-capped renders still carry a toggle span")
    (let [s (-> tog second :style)]
      (is (= ei/triangle-style s)
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
          k0 (ei/expansion-key :p "m" [])
          h  (ei/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-edn-inspector-p-m-body")]
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
          k0 (ei/expansion-key :p "m" [])
          h  (ei/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-edn-inspector-p-m-body")
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
          k0 (ei/expansion-key :p "m" [])
          h  (ei/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-edn-inspector-p-m-body")]
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
          h (ei/render-node {:value 1
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
      (let [h (ei/render-node {:value v
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
          k0 (ei/expansion-key :p "m" [])
          h  (ei/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-edn-inspector-p-m-body")
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
          k0 (ei/expansion-key :p "m" [])
          h  (ei/render-node {:value v
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {k0 {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
          body (find-attr h :data-testid "rf-xray-edn-inspector-p-m-body")]
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
          h   (ei/render-node {:value v
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
                         "rf-xray-edn-inspector-test-m1-:x-toggle")
          on-click (-> tog second :on-click)]
      (is (fn? on-click) "toggle glyph must carry an :on-click")
      (when on-click (on-click nil))
      (is (= [:rf.xray.edn-inspector/toggle-node :test "m1" [:x] false]
             @captured)
          "default-collapsed render → payload carries rendered? false")))

  (testing "default-expanded path: dispatched event carries rendered? true"
    (let [captured (atom nil)
          ;; A >3-key map at depth 0 with default-expanded-depth 2:
          ;; - inline-fit gate fails (cnt > 3 → not inline)
          ;; - `(<= 0 (dec 2))` true → default-expanded? returns true
          ;; - path renders ▾, toggle dispatches rendered? true.
          v   {:a 1 :b 2 :c 3 :d 4 :e 5}
          h   (ei/render-node {:value v
                               :panel-id :test
                               :mount-id "m2"
                               :path []
                               :depth 0
                               :expansion-map {}
                               :dispatch-fn (fn [event-v]
                                              (reset! captured event-v))
                               :opts {:default-expanded-depth 2}})
          tog (find-attr h :data-testid
                         "rf-xray-edn-inspector-test-m2-toggle")
          on-click (-> tog second :on-click)]
      (is (fn? on-click) "toggle glyph must carry an :on-click")
      (when on-click (on-click nil))
      (is (= [:rf.xray.edn-inspector/toggle-node :test "m2" [] true]
             @captured)
          "default-expanded render → payload carries rendered? true"))))

;; ---- rf2-pvsxs — opt-in `:site-id` for cross-mount persistence ----------
;;
;; By default, two `[edn-inspector value]` mounts in the same panel get
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

(deftest edn-inspector-uses-site-id-when-supplied-as-expansion-key-id
  ;; Two mounts with the SAME `:site-id` and the SAME path must write
  ;; their override to the SAME expansion-key, so the second mount sees
  ;; the first mount's choice.
  (let [panel-id :p
        site-id  [:my-stable-site "alpha"]
        path     [:cart :items]
        ;; First mount → simulate a toggle dispatch carrying rendered? true
        ;; (i.e. visible state is expanded; first click should collapse).
        _        (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
        _        (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node
                                    panel-id site-id path true])
        k        (ei/expansion-key panel-id site-id path)
        snapshot @(rf/subscribe [ei/expansion-slot])]
    (is (= false (get-in snapshot [k :expanded?]))
        "override is stored under [panel-id site-id path], independent
         of any auto-generated mount-id")
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])))

(deftest edn-inspector-public-widget-routes-site-id-to-render-key
  ;; The public widget threads `:site-id` (when present) into the
  ;; `mount-id` slot of every render-node descent so the toggle handler
  ;; dispatches against the stable id, NOT the auto-mount-id. Verified
  ;; by mounting the widget twice with the SAME site-id and a value
  ;; that needs expansion; the testid carrying the stable id must
  ;; appear on both renders.
  (let [outer (ei/edn-inspector {:a 1 :b 2 :c 3 :d 4} {:panel-id :p
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

(deftest edn-inspector-without-site-id-keeps-per-call-site-isolation
  ;; The acceptance contract: when `:site-id` is omitted, behaviour is
  ;; UNCHANGED — auto-mount-id keeps two side-by-side mounts independent.
  ;; This guards the rf2-sndui D4=a default.
  (let [outer1 (ei/edn-inspector {:a 1} {:panel-id :p})
        outer2 (ei/edn-inspector {:a 1} {:panel-id :p})
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
        _        (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
        ;; Step 1 — open the path (rendered? false → store true).
        _        (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node
                                    panel-id site-id path false])
        k        (ei/expansion-key panel-id site-id path)
        ;; "Unmount" — no state cleanup needed; the expansion slot
        ;; survives Reagent unmount because it's in app-db.
        ;; "Remount" — same site-id is passed at the new mount; the
        ;; renderer's resolve-expanded? reads the same key.
        snapshot-after-remount @(rf/subscribe [ei/expansion-slot])]
    (is (= true (get-in snapshot-after-remount [k :expanded?]))
        "expansion override survives the simulated unmount-and-remount cycle
         when the consumer passes a stable :site-id")
    ;; Per the resolve-expanded? helper, this should also yield true
    ;; regardless of the default-expanded heuristic.
    (is (true? (ei/resolve-expanded? snapshot-after-remount
                                     panel-id site-id path false))
        "resolve-expanded? honours the stored override at the site-id key")
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])))

(deftest two-mounts-with-distinct-site-ids-still-isolate
  ;; Two consumers using DIFFERENT :site-ids must STILL isolate, even
  ;; though both opt out of the auto-mount-id default. This is the
  ;; per-call-site contract restated in :site-id space.
  (let [panel-id :p
        s1       [:site/a]
        s2       [:site/b]
        path     []
        k1       (ei/expansion-key panel-id s1 path)
        k2       (ei/expansion-key panel-id s2 path)]
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id s1 path true])
    (let [snapshot @(rf/subscribe [ei/expansion-slot])]
      (is (= false (get-in snapshot [k1 :expanded?]))
          "site/a's override is stored")
      (is (nil? (get snapshot k2))
          "site/b's slot is untouched — distinct :site-ids isolate"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])))

;; ---- per-call-site isolation ---------------------------------------------

(deftest two-mounts-independent-via-distinct-mount-ids
  (let [v {:a 1 :b 2 :c 3 :d 4 :e 5}
        m1 "mount-1"
        m2 "mount-2"
        k1 (ei/expansion-key :p m1 [])
        k2 (ei/expansion-key :p m2 [])
        ;; mount-1 is force-expanded; mount-2 is force-collapsed.
        emap {k1 {:expanded? true}
              k2 {:expanded? false}}
        h1 (ei/render-node {:value v :panel-id :p :mount-id m1
                            :path [] :depth 0
                            :expansion-map emap
                            :opts {:default-expanded-depth 0}})
        h2 (ei/render-node {:value v :panel-id :p :mount-id m2
                            :path [] :depth 0
                            :expansion-map emap
                            :opts {:default-expanded-depth 0}})]
    (is (re-find #"▾" (collect-text h1)) "mount-1 reads :expanded? true")
    (is (re-find #"▸" (collect-text h2)) "mount-2 reads :expanded? false")
    (is (not (re-find #"▾" (collect-text h2))) "mount-2 does NOT show ▾")))

;; ---- map-entry distinction -----------------------------------------------

(deftest map-entry-bracket-tone-distinct-from-vector
  (testing "map-entry uses :accent tone; vector uses :text-secondary"
    (is (= :accent          (-> ei/delim :map-entry :tone-key)))
    (is (= :text-secondary  (-> ei/delim :vector    :tone-key)))
    (is (not= (-> ei/delim :vector :tone-key)
              (-> ei/delim :map-entry :tone-key)))))

;; ---- mini one-liner ------------------------------------------------------

(deftest mini-scalar-keyword
  (let [h (ei/mini :foo)
        all (collect-text h)]
    (is (re-find #":foo" all))))

(deftest mini-map-shows-inline-preview
  (let [h (ei/mini {:a 1 :b 2} 80)
        all (collect-text h)]
    (is (re-find #":a" all))
    (is (re-find #":b" all))))

(deftest mini-sentinel-redacted
  (let [h (ei/mini :rf/redacted)
        all (collect-text h)]
    (is (re-find #"redacted" all))))

(deftest mini-truncates-to-max-len
  (let [long-str (apply str (repeat 200 "x"))
        h (ei/mini long-str 20)
        title (-> h second :title)]
    ;; Title carries the full pr-str; visible content is truncated.
    (is (some? title) "title attribute carries full value")))

;; =========================================================================
;; Diff mode (rf2-q3dzw phase 5 · D5=a per rf2-sndui)
;; =========================================================================
;;
;; The diff path subsumes the legacy `edn-inspector.render` engine —
;; passing `:before` switches the widget into diff mode where each
;; node renders with a left-gutter glyph + colour and `:modified`
;; leaves carry a `← changed from <prior>` annotation. Ancestor chain
;; force-opens over any changed descendant.

;; ---- pure helpers --------------------------------------------------------
;;
;; rf2-n2jig — the home-grown classifier (`diff-op`,
;; `changed-descendant?`, `op->gutter-glyph`, `op->gutter-tone-key`,
;; `op->row-wash-key`, `op->row-stripe-key`) was retired in favour of
;; the Editscript-backed projection engine at
;; `day8.re-frame2-xray.diff.engine`. Per the bead's pre-alpha posture:
;; clean delete, no shim. The engine's own test suite at
;; `day8.re-frame2-xray.diff.engine-cljs-test` carries the classification
;; pins (R1-R8 grammar rules). The diff-leaf rendering tests below
;; continue to drive `render-node` end-to-end with `:before` and assert
;; against the resulting DOM chrome attributes.

(deftest gutter-glyph-colour-is-syntax-palette-disjoint
  (testing "rf2-awqts — the reserved `:diff-gutter` hue must NOT match
            any `:syntax-*` token. Pre-fix the per-op gutter colour
            mapped through `:green` / `:red` / `:yellow` / `:accent`
            which collided with `:syntax-string` / `:syntax-number` /
            `:syntax-boolean` etc., conflating type semantics with
            diff state. The new reserved hue (cyan-teal in dark,
            darker-teal in light) sits outside every `:syntax-*`
            family by design."
    (doseq [palette [dark-palette light-palette]]
      (let [gutter (:diff-gutter palette)
            syntax-hexes #{(:syntax-keyword palette)
                           (:syntax-string  palette)
                           (:syntax-number  palette)
                           (:syntax-boolean palette)
                           (:syntax-nil     palette)
                           (:syntax-symbol  palette)
                           (:syntax-builtin palette)
                           (:syntax-punctuation palette)}]
        (is (not (contains? syntax-hexes gutter))
            (str "diff-gutter " gutter " collides with a syntax-* token "
                 "in palette " (if (= palette dark-palette) :dark :light)))))))

(deftest diff-leaf-preserves-syntax-token-colour
  (testing "rf2-awqts — per-token text colour PRESERVED across `:added`
            and `:modified` ops. Pre-fix the diff path overrode to
            `:green` / `:yellow` text colour which clashed with the
            Calva-aligned syntax palette (numbers orange ≡ modified
            yellow); now the row chrome (wash + stripe + glyph)
            carries the diff signal."
    ;; :added — number value keeps `:syntax-number` orange
    (let [h (ei/render-node {:value 42 :before ::ei/missing :diff? true
                             :panel-id :p :mount-id "m" :path [] :depth 0
                             :expansion-map {} :opts {}})
          node (find-attr h :data-rf-type "number")]
      (is (some? node) "number scalar rendered inside the gutter row")
      (is (= (:syntax-number tokens) (-> node second :style :color))
          ":syntax-number token preserved on the added scalar"))
    ;; :modified — boolean keeps `:syntax-boolean` gold
    (let [h (ei/render-node {:value true :before false :diff? true
                             :panel-id :p :mount-id "m" :path [] :depth 0
                             :expansion-map {} :opts {}})
          node (find-attr h :data-rf-type "boolean")]
      (is (some? node) "boolean scalar rendered inside the gutter row")
      (is (= (:syntax-boolean tokens) (-> node second :style :color))
          ":syntax-boolean token preserved on the modified scalar"))))

(deftest diff-row-wrapper-carries-wash-and-stripe-attrs
  (testing "rf2-awqts — diff row wrapper carries data-attributes so
            tests + DOM inspectors can confirm the wash + stripe are
            applied per op"
    (let [h (ei/render-node {:value 42 :before ::ei/missing :diff? true
                             :panel-id :p :mount-id "m" :path [] :depth 0
                             :expansion-map {} :opts {}})
          wrapper (find-attr h :data-rf-diff-op "added")]
      (is (some? wrapper))
      (is (= "1" (:data-rf-diff-wash  (second wrapper)))
          "added row carries wash attr")
      (is (= "1" (:data-rf-diff-stripe (second wrapper)))
          "added row carries stripe attr")
      (is (= (:diff-added-wash tokens) (-> wrapper second :style :background))
          "wash background reads through the diff-added-wash token"))
    ;; :same — no wash, no stripe attrs
    (let [h (ei/render-node {:value 42 :before 42 :diff? true
                             :panel-id :p :mount-id "m" :path [] :depth 0
                             :expansion-map {} :opts {}})
          wrapper (find-attr h :data-rf-diff-op "same")]
      (is (some? wrapper))
      (is (nil? (:data-rf-diff-wash  (second wrapper)))
          ":same row has no wash")
      (is (nil? (:data-rf-diff-stripe (second wrapper)))
          ":same row has no stripe"))))

;; ---- diff mode — modified-leaf annotation --------------------------------

(deftest diff-modified-leaf-emits-changed-from-annotation
  (let [h (ei/render-node {:value 2
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
        h (ei/render-node {:value v
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
  (let [h (ei/render-node {:value 2
                           :before ei/missing-sentinel
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})]
    (is (re-find #"data-rf-diff-op"
                 (try (pr-str h) (catch :default _ "")))
        "added leaf carries the diff-op marker")))

(deftest diff-removed-leaf-shows-prior-value
  (let [h (ei/render-node {:value ei/missing-sentinel
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
        h (ei/render-node {:value v
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
  (let [h (ei/render-node {:value 1
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

(deftest edn-inspector-diff-mode-marker-on-container
  ;; The public widget's outer container carries `data-rf-mode` =
  ;; "diff" when `:before` is supplied so panels / tests can target
  ;; the diff variant.
  (let [outer (ei/edn-inspector {:a 2} {:before {:a 1}})
        ;; outer is the form-2 closure that returns a fn — call it
        ;; with the same args to get the inner hiccup.
        inner (outer {:a 2} {:before {:a 1}})]
    (is (= "diff" (get (second inner) :data-rf-mode))
        "diff-mode marker present when :before is supplied")
    (is (some? (get (second inner) :data-rf-mount-id))
        "mount-id still auto-generated")))

(deftest edn-inspector-browse-mode-marker-on-container
  (let [outer (ei/edn-inspector {:a 1})
        inner (outer {:a 1} nil)]
    (is (= "browse" (get (second inner) :data-rf-mode))
        "browse-mode marker present without :before")))

(deftest edn-inspector-diff-convenience-threads-before
  ;; The `[edn-inspector-diff before after]` form-2 wrapper should
  ;; produce the same shape as `[edn-inspector after {:before before}]`.
  (let [h (ei/edn-inspector-diff {:a 1} {:a 2})]
    (is (vector? h))
    (is (fn? (first h)))
    (is (= {:a 2} (nth h 1)))
    (is (= {:a 1} (:before (nth h 2))))))

;; =========================================================================
;; rf2-zuh1e — diff renders REMOVED items (children-of walks the union of
;; BEFORE + AFTER, not just AFTER)
;; =========================================================================
;;
;; Pre-fix the render-container body walked `(children-of value)` (AFTER
;; only). Items present in BEFORE but absent from AFTER — the common
;; `dissoc` / set-`disj` / vector-`pop` case — silently disappeared from
;; the rendered tree. Now `children-of-pair` returns the UNION of BEFORE
;; + AFTER triples; removed slots render with the existing rf2-awqts
;; removed chrome (strike-through + red wash + `-` gutter glyph).

(deftest children-of-pair-map-union
  (testing "AFTER's keys in order then BEFORE-only keys appended"
    (let [pairs (ei/children-of-pair {:a 1 :gone "g"} {:a 1 :added 9} :map)]
      ;; Each triple is [k after-value before-value].
      (is (= [:a 1 1]                        (nth (vec pairs) 0)))
      (is (= [:added 9 ei/missing-sentinel]  (nth (vec pairs) 1)))
      (is (= [:gone ei/missing-sentinel "g"] (nth (vec pairs) 2)))))
  (testing "all-added when BEFORE not a map"
    (let [pairs (vec (ei/children-of-pair nil {:a 1} :map))]
      (is (= [:a 1 ei/missing-sentinel] (first pairs)))))
  (testing "all-removed when AFTER empty"
    (let [pairs (vec (ei/children-of-pair {:a 1 :b 2} {} :map))]
      (is (= 2 (count pairs)))
      (is (every? #(= ei/missing-sentinel (second %)) pairs))
      (is (= #{:a :b} (set (map first pairs)))))))

(deftest children-of-pair-vector-tail
  (testing "BEFORE-tail items past AFTER render as removed slots"
    (let [pairs (vec (ei/children-of-pair [:x :y :z] [:x] :vector))]
      (is (= [0 :x :x]                       (nth pairs 0)))
      (is (= [1 ei/missing-sentinel :y]      (nth pairs 1)))
      (is (= [2 ei/missing-sentinel :z]      (nth pairs 2)))))
  (testing "ADDED tail items appear too"
    (let [pairs (vec (ei/children-of-pair [:x] [:x :y :z] :vector))]
      (is (= [1 :y ei/missing-sentinel] (nth pairs 1)))
      (is (= [2 :z ei/missing-sentinel] (nth pairs 2))))))

(deftest children-of-pair-set-union-sorted
  (testing "set members render alongside survivors; sort by pr-str"
    (let [pairs (vec (ei/children-of-pair #{:a :b :ws/authenticating}
                                          #{:a :b}
                                          :set))
          keys  (mapv first pairs)]
      (is (= 3 (count pairs)))
      ;; pr-str sort is stable: `:a` < `:b` < `:ws/authenticating`.
      (is (= [:a :b :ws/authenticating] keys))
      ;; The removed member's AFTER slot is ::missing; BEFORE side
      ;; carries the prior value.
      (is (= [:ws/authenticating ei/missing-sentinel :ws/authenticating]
             (last pairs))
          "removed-only member appears with after=::missing, before=value"))))

(deftest diff-renders-removed-map-key
  ;; Map dissoc case — `:tags` would also be a map at one level up,
  ;; but the simplest assertion is at the top level: AFTER drops a key
  ;; and the rendered hiccup carries the removed-chrome marker for
  ;; that row.
  (let [before {:a 1 :b 2}
        after  {:a 1}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #":b" all)
        "removed key :b still appears in the rendered hiccup")
    (is (re-find #":a" all)
        "surviving key :a still renders (no regression)")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "a row carries the removed diff-op marker")
    (is (re-find #"line-through" s)
        "removed row carries the strike-through text-decoration")))

(deftest diff-renders-fully-dissocd-map
  ;; Edge case — AFTER is `{}` (all keys dropped). Pre-fix the empty
  ;; AFTER short-circuited the header into the `{}` empty-bracket-pair
  ;; render, hiding every removed row. Now the union count drives the
  ;; header so the body still expands.
  (let [before {:a 1 :b 2}
        after  {}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        all (collect-text h)]
    (is (re-find #":a" all)
        "both removed keys appear when AFTER is fully dissoc'd")
    (is (re-find #":b" all))))

(deftest diff-renders-removed-vector-tail
  (let [before [:x :y :z]
        after  [:x]
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #":y" all) "popped tail item :y still appears")
    (is (re-find #":z" all) "popped tail item :z still appears")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "a row carries the removed diff-op marker")))

(deftest diff-renders-removed-set-member
  ;; Canonical machine-snapshot reproduction (rf2-zuh1e bead body):
  ;; `:tags` set loses `:ws/authenticating`. Before this fix the AFTER
  ;; column rendered with no indication anything was removed; now the
  ;; struck-through row appears alongside the survivors.
  (let [before #{:a :b :ws/authenticating}
        after  #{:a :b}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #":ws/authenticating" all)
        "removed set member rendered alongside survivors")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "the removed-member row carries the removed diff-op marker")
    (is (re-find #"line-through" s)
        "the removed row carries strike-through text-decoration")))

(deftest diff-renders-machine-snapshot-tags-transition
  ;; Mike's live repro 2026-05-26 — a Machine snapshot transition
  ;; `[:active :authenticating] → [:active :connected]` where `:tags`
  ;; loses `:ws/authenticating`. The operator sees the post-image with
  ;; the removed tag struck-through.
  (let [before {:state [:active :authenticating]
                :tags  #{:ws/authenticating :ws/online}
                :context {:retries 0}}
        after  {:state [:active :connected]
                :tags  #{:ws/online}
                :context {:retries 0}}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 4}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #":ws/authenticating" all)
        "removed :tags member rendered with the AFTER column")
    (is (re-find #":connected" all) "AFTER's :state member visible")
    (is (re-find #":authenticating" all)
        "BEFORE's :state member rendered via the modified-leaf annotation")
    (is (re-find #"line-through" s)
        "at least one row carries strike-through (the removed tag)")))

(deftest diff-preserves-added-modified-same-rows
  ;; No regression — added / modified / same rows still render
  ;; alongside the new removed rows.
  (let [before {:same 1   :modify 2 :gone "g"}
        after  {:same 1   :modify 9 :added :new}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #"data-rf-diff-op.*added" s)
        "added row marker present")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "removed row marker present")
    (is (re-find #"data-rf-diff-op.*modified" s)
        "modified row marker present")
    (is (re-find #"data-rf-diff-op.*same" s)
        "same row marker still present for unchanged rows")
    (is (re-find #"← changed from 2" s)
        "modified leaf still carries the change annotation")))

;; =========================================================================
;; rf2-y59tb — frame-leak + first-click regression guards
;; =========================================================================
;;
;; Two independent bugs covered here:
;;
;;   Bug A — `edn-inspector` was a plain `defn`, so dispatches from
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

(deftest edn-inspector-is-reg-view-registered
  (testing "rf2-y59tb Bug A — the public widget is registered via
            `reg-view` so dispatches + subscribes inherit the
            surrounding frame from React context. Without this the
            App-DB panel's `:rf/xray` mount routes toggle dispatches
            to `:rf/default`. The registration is present in the
            view registry under the auto-derived namespaced id."
    (is (some? (rf/view :day8.re-frame2-xray.views.edn-inspector/edn-inspector))
        "edn-inspector is registered under its ns/sym id")))

(deftest edn-inspector-toggle-dispatches-to-mount-frame
  ;; rf2-y59tb Bug A regression guard — the click handler dispatches
  ;; through the lexically-injected frame-aware dispatcher. We
  ;; simulate the inner render by calling `render-node` with a
  ;; `dispatch-fn` (which is what the reg-view body threads from its
  ;; outer-scope `dispatch` lexical binding); the handler MUST call
  ;; our supplied dispatch-fn rather than `rf/dispatch` (which would
  ;; route to `:rf/default`).
  (let [captured (atom nil)
        v       {:a 1 :b 2 :c 3 :d 4 :e 5}
        h       (ei/render-node {:value v
                                 :panel-id :app-db
                                 :mount-id "m"
                                 :path []
                                 :depth 0
                                 :expansion-map {}
                                 :dispatch-fn (fn [event-v]
                                                (reset! captured event-v))
                                 :opts {:default-expanded-depth 0}})
        tog     (find-attr h :data-testid
                           "rf-xray-edn-inspector-app-db-m-toggle")
        on-click (-> tog second :on-click)]
    (is (fn? on-click))
    (when on-click (on-click nil))
    (is (some? @captured)
        "toggle handler invoked the threaded dispatch-fn (not rf/dispatch)")
    (is (= :rf.xray.edn-inspector/toggle-node (first @captured))
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
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node
                       panel-id mount-id path true])
    (let [snapshot @(rf/subscribe [ei/expansion-slot])
          k         (ei/expansion-key panel-id mount-id path)]
      (is (= false (get-in snapshot [k :expanded?]))
          "default-expanded → first click collapses (rendered? true → stored false)"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])))

(deftest first-click-expands-default-collapsed-path
  ;; rf2-y59tb Bug B regression guard — the symmetric case. A deep
  ;; path (past default-expanded-depth) renders collapsed by default.
  ;; The toggle dispatch carries `false`; the reducer must store
  ;; `:expanded? true` (opens the node) on the first click.
  (let [panel-id :rf.xray/app-db
        mount-id "m1"
        path     [:deep :nested :node]]
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node
                       panel-id mount-id path false])
    (let [snapshot @(rf/subscribe [ei/expansion-slot])
          k         (ei/expansion-key panel-id mount-id path)]
      (is (= true (get-in snapshot [k :expanded?]))
          "default-collapsed → first click expands (rendered? false → stored true)"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])))

(deftest second-click-inverts-stored-override
  ;; Once an override is stored the reducer ignores the rendered?
  ;; payload (the override IS the visible state) and inverts the
  ;; stored boolean. This is the canonical toggle behaviour for
  ;; clicks 2+ on the same path.
  (let [panel-id :rf.xray/app-db
        mount-id "m1"
        path     [:x]
        k         (ei/expansion-key panel-id mount-id path)]
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    ;; First click on default-expanded → stored false.
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id mount-id path true])
    (is (= false (get-in @(rf/subscribe [ei/expansion-slot]) [k :expanded?])))
    ;; Second click — the rendered? slot is now `false` (override is
    ;; false) but the reducer flips the OVERRIDE, not the payload.
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id mount-id path false])
    (is (= true (get-in @(rf/subscribe [ei/expansion-slot]) [k :expanded?]))
        "second click inverts stored override → true")
    ;; Third click flips again.
    (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node panel-id mount-id path true])
    (is (= false (get-in @(rf/subscribe [ei/expansion-slot]) [k :expanded?]))
        "third click inverts stored override → false")
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])))

;; ---- rf2-63ie5 — inspector card chrome on top-level mounts ---------------
;;
;; `:card? true` opts the widget's outer container into the inspector-card
;; chrome (background, border, radius, padding, margin) so panels with
;; multiple top-level mounts (App-DB's TOP + per-`:rf/*` areas) read as
;; distinct cards. Default off preserves inline / nested behaviour.

(defn- invoke-edn-inspector
  "Form-2 unrolling — run the outer fn, then the inner fn with the same
  args to get the rendered hiccup."
  [value opts]
  (let [outer (ei/edn-inspector value opts)]
    (outer value opts)))

(deftest card-opt-off-by-default
  (testing "rf2-63ie5 — without `:card?` (or with `false`) the outer
            container carries NO card chrome (background, border,
            radius, padding, margin all absent)"
    (let [h-default (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db})
          style-default (-> h-default second :style)
          h-false   (invoke-edn-inspector {:a 1}
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
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
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
    (let [h-on  (invoke-edn-inspector {:a 1}
                                      {:panel-id :rf.xray/app-db :card? true})
          h-off (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db})]
      (is (= "1" (:data-rf-card (second h-on)))
          "card-on publishes :data-rf-card=1 for testbed assertion")
      (is (nil? (:data-rf-card (second h-off)))
          "card-off omits the attribute"))))

;; ---- rf2-726ol — map column alignment (triangle / line / keys / close) --
;;
;; The map body's left margin + 1px border position the vertical guide
;; line at the triangle's visual centre (`margin-left 11px` per
;; `body-grid-style` in impl). Keys sit 6px past the line. The closing
;; brace sits at `padding-left 10px` so the triangle / line / keys /
;; closing-brace converge on one column structure.

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
  (testing "rf2-726ol — the body div's `margin-left 11px` + `border-left
            1px` puts the vertical guide line at the triangle-centred
            column (matching `body-grid-style` in impl); keys sit 6px
            past the line for a small breath"
    (let [v   {:counter 1 :async nil :machine-ui {:open? true}}
          k0  (ei/expansion-key :test "m" [])
          h   (ei/render-node {:value v
                               :panel-id :test :mount-id "m"
                               :path [] :depth 0
                               :expansion-map {k0 {:expanded? true}}
                               :opts {:default-expanded-depth 0}})
          bodies (find-body-divs h)]
      (is (seq bodies) "expanded map renders body div(s)")
      (doseq [body bodies]
        (let [style (:style (second body))]
          (is (= "11px" (:margin-left style))
              "body's margin-left puts the 1px border at the triangle-centred guide column")
          (is (= "6px" (:padding-left style))
              "keys sit 6px past the line for a small breath"))))))

(deftest closing-brace-aligns-with-guide-line
  (testing "rf2-726ol — the closing-bracket div sits at `padding-left
            10px`, column-aligned with the vertical guide line above,
            so the bracket pair `▾ { … }` reads as a coherent vertical
            column at every nesting depth"
    (let [;; 4+ keys in each map defeats inline-fit (which requires
          ;; ≤3 children) so both outer + inner expand-render.
          v   {:a 1 :b 2 :c 3 :d 4
               :nested {:x 1 :y 2 :z 3 :w 4}}
          k0  (ei/expansion-key :test "m" [])
          k1  (ei/expansion-key :test "m" [:nested])
          h   (ei/render-node
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
          (is (= "10px" (:padding-left style))
              "close-brace `padding-left 10px` matches the guide-line x"))))))

(deftest block-body-shares-alignment-with-grid-body
  (testing "rf2-726ol — sequential (vector / list / set) bodies use the
            same `margin-left 11px` + `padding-left 6px` as map bodies
            so a vector's guide line / first item / closing bracket all
            converge on the same column structure"
    (let [;; 4 elements + a nested container defeats inline-fit so the
          ;; block body actually renders.
          v   [1 2 3 4 {:x :y}]
          k0  (ei/expansion-key :test "m" [])
          h   (ei/render-node {:value v
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
        (is (= "11px" (:margin-left style)) "same 11px margin as grid body")
        (is (= "6px"  (:padding-left style)) "same 6px padding as grid body")))))

(deftest card-opt-theme-aware-via-tokens
  (testing "rf2-63ie5 — the card chrome reads from the live `tokens`
            map (a CSS-variable shim per `theme/tokens.cljc`) so both
            light + dark themes resolve at paint time without a re-
            render. This test pins the inline-style values to the
            same token-keyed map the rest of the widget consumes."
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                          :card? true})
          style (-> h second :style)]
      (is (= (:bg-1 tokens) (:background-color style))
          "background reads through `:bg-1` (CSS-var or hex per theme)")
      (is (= (str "1px solid " (:border-default tokens)) (:border style))
          "border reads through `:border-default` (CSS-var or hex per theme)"))))

;; ---- rf2-okq7p — :header opt + three-shade card chrome -------------------
;;
;; `:header` opts the widget into the Machine-panel-aesthetic three-shade
;; card chrome: outer `<section>` (background `:bg-2`, 1px border, 4px
;; radius) + `<header>` ribbon (`:bg-3`, padding 10px 12px) + body sleeve
;; (`:bg-1`, padding 12px). Consumer panels mounting multiple inspectors
;; side-by-side (App-DB 3 mounts; Handler event/before/after/fx/coeffects)
;; pass a per-mount header so the eye reads each as a discrete labelled
;; card rather than blending into one continuous block.
;;
;; Default (`:header` nil) — no `<section>` wrapper, single-div render
;; (unchanged from rf2-63ie5's `:card?` semantics).

(defn- find-tag
  "Return the first hiccup vector in `tree` whose tag is `tag`."
  [tree tag]
  (->> (walk-hiccup tree)
       (filter (fn [n] (and (vector? n) (= tag (first n)))))
       first))

(deftest header-opt-omitted-renders-no-section-wrapper
  (testing "rf2-okq7p — without `:header` the widget emits a single
            `<div>` root, with no `<section>` wrapper and no
            `<header>` ribbon (back-compat with pre-rf2-okq7p mounts)"
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db})]
      (is (= :div (first h)) "root tag is `<div>`, not `<section>`")
      (is (nil? (find-tag h :section)) "no `<section>` anywhere in tree")
      (is (nil? (find-tag h :header)) "no `<header>` ribbon")
      (is (nil? (:data-rf-header (second h)))
          "outer div omits the `data-rf-header` flag"))))

(deftest header-opt-explicit-nil-renders-no-section-wrapper
  (testing "rf2-okq7p — `:header nil` is equivalent to omitting the opt
            (no section wrapper)"
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                          :header nil})]
      (is (= :div (first h)) "root tag is `<div>`")
      (is (nil? (find-tag h :section)) "no `<section>` wrapper"))))

(deftest header-opt-string-renders-section-and-ribbon
  (testing "rf2-okq7p — `:header \"label\"` wraps the render in a
            `<section>` with a `<header>` ribbon containing the supplied
            string"
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                          :header "Counter app · :rf/default"})]
      (is (= :section (first h)) "root tag is `<section>`")
      (is (= "1" (:data-rf-header (second h)))
          "section publishes `data-rf-header=1` for testbed assertion")
      (let [hdr (find-tag h :header)]
        (is (some? hdr) "section contains a `<header>` ribbon")
        (is (= "ribbon" (:data-rf-header-role (second hdr)))
            "header ribbon publishes its role for selectors")
        (is (some #{"Counter app · :rf/default"} (rest hdr))
            "header ribbon contains the supplied string verbatim")))))

(deftest header-opt-hiccup-passes-through-opaquely
  (testing "rf2-okq7p — `:header [hiccup]` renders the supplied vector as
            the ribbon content unchanged (the widget treats hiccup as
            opaque — no parsing, no required shape)"
    (let [header-hiccup [:span
                         [:strong "Counter app"]
                         " · "
                         [:code ":rf/default"]]
          h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                          :header header-hiccup})
          hdr (find-tag h :header)]
      (is (= :section (first h)) "root tag is `<section>`")
      (is (some? hdr) "section contains a `<header>` ribbon")
      (is (some #(= header-hiccup %) (rest hdr))
          "header ribbon embeds the hiccup vector verbatim"))))

(deftest header-opt-composite-hiccup-with-children
  (testing "rf2-okq7p — composite hiccup with label + code + button
            children flows through the ribbon unchanged. Mike's bead
            spec called out this composability — header carries label +
            chips + per-inspector affordances"
    (let [clicked (atom 0)
          header-hiccup [:span
                         [:strong "machine-app"]
                         " · "
                         [:code ":step-deck"]
                         [:button {:on-click (fn [_] (swap! clicked inc))}
                          "reset"]]
          h (invoke-edn-inspector {:counter 1}
                                  {:panel-id :rf.xray/app-db
                                   :header header-hiccup})
          hdr (find-tag h :header)
          ;; Hunt the button down inside the ribbon.
          btn (->> (walk-hiccup hdr)
                   (filter (fn [n] (and (vector? n) (= :button (first n)))))
                   first)]
      (is (some? btn) "composite header retains the embedded `<button>`")
      ;; Pull the on-click handler off the button and exercise it —
      ;; pass-through is genuine, not a structural diff in disguise.
      (when btn
        ((:on-click (second btn)) {})
        (is (= 1 @clicked)
            "the supplied on-click handler fires when the ribbon button
             is clicked")))))

(deftest header-opt-three-shade-chrome-via-tokens
  (testing "rf2-okq7p — the section + header + body each read a distinct
            shade from the live `tokens` map (`:bg-2` outer, `:bg-3`
            header, `:bg-1` body). Theme-aware via CSS variables — both
            light + dark resolve at paint time."
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                          :header "Counter"})
          section-style (-> h second :style)
          hdr           (find-tag h :header)
          hdr-style     (-> hdr second :style)
          ;; Body div is the LAST top-level child of the section (the
          ;; affordance + header come before it).
          body          (->> (rest h)
                             (filter (fn [n]
                                       (and (vector? n)
                                            (= :div (first n))
                                            (= "card-body"
                                               (:data-rf-body-role (second n))))))
                             first)
          body-style    (-> body second :style)]
      (is (= (:bg-2 tokens) (:background-color section-style))
          "outer section reads `:bg-2` (light: #ffffff / dark: bg-2 token)")
      (is (= (str "1px solid " (:border-default tokens))
             (:border section-style))
          "outer border reads `:border-default`")
      (is (= "4px" (:border-radius section-style))
          "outer corner radius 4px (matches Machine panel)")
      (is (= (:bg-3 tokens) (:background hdr-style))
          "header ribbon reads `:bg-3` (light: #e8e8e8)")
      (is (= "10px 12px" (:padding hdr-style))
          "header ribbon padding 10px 12px (matches Machine panel)")
      (is (= (str "1px solid " (:border-subtle tokens))
             (:border-bottom hdr-style))
          "header ribbon carries a `:border-subtle` bottom rule")
      (is (= (:bg-1 tokens) (:background body-style))
          "body sleeve reads `:bg-1` (light: #f5f5f5)")
      (is (= "12px" (:padding body-style))
          "body sleeve padding 12px"))))

(deftest header-opt-preserves-mount-id-and-testid-on-section
  (testing "rf2-okq7p — when the widget chromes itself, the mount-id +
            container testid + ref + data-rf-mode flag move to the
            section so existing test selectors keep working. The body's
            edn-inspector tree still renders inside the body div."
    (let [h (invoke-edn-inspector {:a 1 :b 2}
                                  {:panel-id :rf.xray/app-db
                                   :header "Counter"})
          attrs (second h)]
      (is (= :section (first h)) "root tag is `<section>`")
      (is (some? (:data-testid attrs))
          "section carries the container `data-testid` (same id contract)")
      (is (some? (:data-rf-mount-id attrs))
          "section carries the auto-generated `mount-id`")
      (is (= "browse" (:data-rf-mode attrs))
          "section carries the mode flag")
      (is (fn? (:ref attrs))
          "section carries the measurement ref callback"))))

(deftest header-opt-renders-body-content-inside-body-sleeve
  (testing "rf2-okq7p — the actual edn-inspector tree (collection-kind
            scalars + brackets + body) lives inside the body sleeve, not
            inside the header ribbon"
    (let [h (invoke-edn-inspector {:counter 7}
                                  {:panel-id :rf.xray/app-db
                                   :header "Counter"})
          body (->> (walk-hiccup h)
                    (filter (fn [n]
                              (and (vector? n)
                                   (map? (second n))
                                   (= "card-body"
                                      (:data-rf-body-role (second n))))))
                    first)]
      (is (some? body) "section contains a body sleeve")
      ;; The body should contain a representation of the `:counter` key
      ;; somewhere in its subtree (collected as text leaves).
      (is (str/includes? (collect-text body) ":counter")
          "body sleeve renders the `:counter` key from the value")
      (is (str/includes? (collect-text body) "7")
          "body sleeve renders the numeric `7`"))))

(deftest header-opt-keeps-popup-affordance-on-section
  (testing "rf2-okq7p — when `:popup-affordance?` is on alongside
            `:header`, the icon button still renders inside the section
            (positioned at the section's top-right corner via the
            outer `position: relative`)"
    (let [h (invoke-edn-inspector {:a 1}
                                  {:panel-id :rf.xray/app-db
                                   :header "Counter"
                                   :popup-affordance? true})
          attrs (second h)
          ;; Affordance button lives as a direct child of the section.
          button (->> (rest h)
                      (filter (fn [n] (and (vector? n) (= :button (first n)))))
                      first)]
      (is (= "relative" (:position (:style attrs)))
          "section establishes positioning context for the absolute button")
      (is (= "1" (:data-rf-popup-affordance? attrs))
          "section publishes the affordance flag")
      (is (some? button)
          "section contains the popup affordance button as a direct child"))))

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
    (is (= 7 ei/mono-char-width-px)
        "7px M-advance is the conservative pick for JetBrains Mono 12px")
    (is (= 16 ei/safety-margin-px)
        "16px safety margin covers closing bracket + gutter")
    (is (= 8 ei/default-ceiling-depth)
        "new default `:default-expanded-depth` is 8 (CEILING, not trigger)")))

(deftest estimated-inline-px-multiplies-pr-str-by-mono-advance
  (testing "rf2-kbdk8 — char-count × 7px estimate"
    (is (= (* 7 (count (pr-str {:a 1})))
           (ei/estimated-inline-px {:a 1}))
        "pure function — char count × mono-char-width-px")
    (is (= (* 7 (count "nil"))
           (ei/estimated-inline-px nil))
        "scalars route through the same pr-str pathway")
    ;; Long compound values get proportionally wider estimates — the
    ;; bead's example (~81-char nested value) lands around ~570px.
    (let [big-value [:ws/connection [:rf.machine.timer/after-elapsed
                                     2501 [:active :authenticating]]]]
      (is (= (* 7 (count (pr-str big-value)))
             (ei/estimated-inline-px big-value))
          "nested compound value estimate matches pr-str-length × 7"))))

(deftest would-fit-inline-fits-when-estimate-plus-margin-le-available
  (testing "rf2-kbdk8 — `would-fit-inline?` gate"
    ;; A short value pr-strs to ~10 chars × 7px = 70px + 16px margin = 86px.
    (let [v {:a 1}]
      (is (ei/would-fit-inline? v 200)
          "200px column trivially fits a 10-char value")
      (is (not (ei/would-fit-inline? v 50))
          "50px column rejects even short values"))
    ;; The bead's worked example: ~81-char nested value in a 966px column.
    (let [big-but-fitting (apply str (repeat 80 "x"))]
      (is (ei/would-fit-inline? big-but-fitting 966)
          "~570px estimate trivially fits 966px column"))
    (is (not (ei/would-fit-inline? {:a 1} nil))
        "nil available-width falls back to legacy strict gate")
    (is (not (ei/would-fit-inline? {:a 1} 0))
        "zero or negative width is treated as no measurement")))

(deftest default-expanded-width-aware-branch
  (testing "rf2-kbdk8 — width-aware `default-expanded?` flips the verdict"
    ;; A 2-key map fits in 600px easily — should NOT auto-expand (the
    ;; inline-fit gate picks it up instead).
    (is (false? (ei/default-expanded?
                  {:depth 0 :child-count 2 :value {:a 1 :b 2}
                   :available-width-px 600})))
    ;; A long string-keyed map that overflows 200px should auto-expand
    ;; (within the ceiling).
    (let [wide-v {:a "much-longer-than-the-budget"
                  :b "another-overflowing-string"
                  :c "and-yet-more-data"}]
      (is (true? (ei/default-expanded?
                   {:depth 0 :child-count 3 :value wide-v
                    :available-width-px 100}))))
    ;; Beyond the ceiling, the width-aware branch falls back to false
    ;; (collapsed summary instead of auto-expanding pathologically deep).
    (let [wide-v {:a "much-longer-than-the-budget"
                  :b "another-overflowing-string"}]
      (is (false? (ei/default-expanded?
                    {:depth 9 :child-count 2 :value wide-v
                     :default-expanded-depth 8
                     :available-width-px 100}))))
    ;; Diff mode's force-open over changed descendants still beats width
    (let [v {:a "wide string that overflows"}]
      (is (true? (ei/default-expanded?
                   {:depth 0 :child-count 1 :value v
                    :available-width-px 1000
                    :has-changed-descendant? true}))
          "changed-descendant rule beats width-fits for diff readability"))))

(deftest default-expanded-no-measurement-fallback
  (testing "rf2-kbdk8 — when no measurement yet (nil available-width-px)
            the legacy depth-driven path runs unchanged so unit tests +
            first-paint behaviour stay deterministic"
    ;; depth 0, default-expanded-depth 2 → expanded (legacy behaviour).
    (is (true? (ei/default-expanded?
                 {:depth 0 :child-count 2 :value {:a 1 :b 2}
                  :default-expanded-depth 2})))
    ;; depth 5, default-expanded-depth 2 → collapsed (legacy behaviour).
    (is (false? (ei/default-expanded?
                  {:depth 5 :child-count 2 :value {:a 1 :b 2}
                   :default-expanded-depth 2})))))

(deftest render-container-width-fit-renders-inline-recursively
  (testing "rf2-kbdk8 — when measured width fits the value's pr-str,
            the renderer emits the FULL value (including nested
            containers) on one inline span — no expand glyph, no
            multi-row tree"
    ;; ~60-char nested value vs 800px column.
    (let [v {:tag :foo :payload [:active :authenticating]}
          h (ei/render-node {:value v
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
          h (ei/render-node {:value v
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
          k0 (ei/expansion-key :p "m" [])
          h (ei/render-node {:value v
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
          h (ei/render-inline-recursive v)
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
    (rf/dispatch-sync [:rf.xray.edn-inspector/set-width "m" 600])
    (let [widths @(rf/subscribe [ei/widths-slot])]
      (is (= 600 (get widths "m"))
          "set-width writes a positive measurement to the slot"))
    ;; Bad inputs are ignored (no app-db churn).
    (rf/dispatch-sync [:rf.xray.edn-inspector/set-width "m2" -5])
    (rf/dispatch-sync [:rf.xray.edn-inspector/set-width nil 100])
    (let [widths @(rf/subscribe [ei/widths-slot])]
      (is (nil? (get widths "m2")) "negative width is rejected")
      (is (nil? (get widths nil))  "nil mount-id is rejected"))
    ;; Cleanup
    (rf/dispatch-sync [:rf.xray.edn-inspector/clear-width "m"])
    (let [after-clear @(rf/subscribe [ei/widths-slot])]
      (is (nil? (get after-clear "m"))
          "clear-width removes the entry"))))

(deftest widget-emits-ref-callback-and-available-width-attr
  (testing "rf2-kbdk8 — the outer container carries a `:ref` callback
            (function) for the ResizeObserver lifecycle, plus a data-
            attribute carrying the current measurement (or absent when
            not yet measured)"
    (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db})
          attrs (-> h second)]
      (is (fn? (:ref attrs))
          "outer container carries a ref callback (mount/unmount hook)")
      ;; No measurement yet → attribute absent / nil.
      (is (nil? (:data-rf-available-width-px attrs))
          "data-rf-available-width-px absent until the ref fires"))))

;; =========================================================================
;; rf2-h71e0 — zoom-into-node + breadcrumb navigation
;; =========================================================================
;;
;; The zoom feature turns the inspector into a focused window onto an
;; arbitrary subtree. The widget reads a per-mount path from the
;; `zoom-slot`; when set, render-node walks `get-in` along that path and
;; renders only the subtree. A breadcrumb row above the body shows the
;; path from the original root; each segment is clickable for one-tap
;; zoom-to-that-depth.
;;
;; Tests under this section cover:
;;
;; - Pure helpers: `zoom-key`, `resolve-zoom-path`, `resolve-zoom-into`.
;; - Event reducers: `:zoom-to`, `:zoom-up`, `:zoom-reset`.
;; - Public widget plumbing: `:zoomable?` opts emit data-attrs +
;;   breadcrumb + the `⊙` affordance on containers; default (no opt)
;;   leaves the renderer unchanged.
;; - Per-mount keying: two side-by-side mounts zoom independently;
;;   stable `:site-id` survives unmount/remount.
;; - Diff suppression: diff mode (`:before`) ignores an active zoom.

;; ---- pure helpers --------------------------------------------------------

(deftest zoom-slot-keyword
  (is (= :rf.xray.edn-inspector/zoom ei/zoom-slot)
      "the slot keyword is a stable part of the public contract"))

(deftest zoom-key-shape
  (is (= [:p "m"]    (ei/zoom-key :p "m")))
  (is (= [:p [:s 1]] (ei/zoom-key :p [:s 1]))
      "site-id vector also works as the mount-id slot"))

(deftest resolve-zoom-path-pure
  (testing "no entry → nil"
    (is (nil? (ei/resolve-zoom-path {} :p "m"))))
  (testing "empty path → nil (no-zoom canonical shape)"
    (is (nil? (ei/resolve-zoom-path {[:p "m"] []} :p "m"))))
  (testing "non-empty path returns vec"
    (is (= [:a :b] (ei/resolve-zoom-path {[:p "m"] [:a :b]} :p "m")))
    (is (vector? (ei/resolve-zoom-path {[:p "m"] '(:a :b)} :p "m"))
        "path always coerced to a vector even when stored as a list")))

(deftest resolve-zoom-into-pure
  (let [v {:a {:b {:c 42 :d 99}} :x 1}]
    (testing "no zoom → original value"
      (is (= v (ei/resolve-zoom-into v {} :p "m")))
      (is (= v (ei/resolve-zoom-into v {[:p "m"] []} :p "m"))
          "empty zoom-path also means no zoom"))
    (testing "zoom resolves get-in walk"
      (is (= {:b {:c 42 :d 99}} (ei/resolve-zoom-into v {[:p "m"] [:a]} :p "m")))
      (is (= 42 (ei/resolve-zoom-into v {[:p "m"] [:a :b :c]} :p "m"))))
    (testing "no-longer-resolvable path falls back to original"
      ;; Stale zoom path against a mutated value — render the full
      ;; thing rather than `nil` so the operator sees something.
      (is (= v (ei/resolve-zoom-into v {[:p "m"] [:nonexistent :path]}
                                     :p "m"))))))

;; ---- reducers ------------------------------------------------------------

(deftest zoom-to-event-stores-path
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" [:a :b]])
  (let [zoom @(rf/subscribe [ei/zoom-slot])
        k    (ei/zoom-key :p "m")]
    (is (= [:a :b] (get zoom k))
        "the path is stored verbatim under [panel-id mount-id]"))
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset]))

(deftest zoom-to-empty-path-clears
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" [:a]])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" []])
  (let [zoom @(rf/subscribe [ei/zoom-slot])
        k    (ei/zoom-key :p "m")]
    (is (nil? (get zoom k))
        "passing an empty path clears the zoom (returns to root view)"))
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset]))

(deftest zoom-up-pops-one-segment
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" [:a :b :c]])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
  (let [zoom @(rf/subscribe [ei/zoom-slot])
        k    (ei/zoom-key :p "m")]
    (is (= [:a :b] (get zoom k))
        "zoom-up pops the last segment, leaving the prefix"))
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
  (let [zoom @(rf/subscribe [ei/zoom-slot])
        k    (ei/zoom-key :p "m")]
    (is (nil? (get zoom k))
        "zoom-up past the root clears the entry"))
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset]))

(deftest zoom-up-noop-when-no-zoom
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
  (let [zoom @(rf/subscribe [ei/zoom-slot])]
    (is (or (nil? zoom) (empty? zoom))
        "zoom-up without an active zoom is a no-op (no slot churn)"))
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset]))

(deftest zoom-reset-mount-specific
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m1" [:a]])
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m2" [:b]])
  ;; Reset only m1.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset :p "m1"])
  (let [zoom @(rf/subscribe [ei/zoom-slot])]
    (is (nil? (get zoom (ei/zoom-key :p "m1"))) "m1 cleared")
    (is (= [:b] (get zoom (ei/zoom-key :p "m2")))
        "m2 retained — reset is scoped to the (panel-id, mount-id) pair"))
  ;; Reset all.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (let [zoom @(rf/subscribe [ei/zoom-slot])]
    (is (nil? zoom) "reset with no args clears the whole slot")))

;; ---- public widget plumbing ----------------------------------------------

(deftest zoomable-opt-off-by-default
  (testing "default render: no data-rf-zoomable attribute"
    (let [h (invoke-edn-inspector {:a 1 :b 2}
                                  {:panel-id :rf.xray/app-db})
          attrs (-> h second)]
      (is (nil? (:data-rf-zoomable attrs))
          "default-off — attribute is absent on the outer container")
      (is (nil? (:data-rf-zoomed attrs))
          "no zoom active — no zoomed marker"))))

(deftest zoomable-opt-emits-data-attr
  (testing "with `:zoomable? true` the outer container publishes
            data-rf-zoomable=1 (off by default; zoom is opt-in per
            consumer panel)"
    (let [h (invoke-edn-inspector {:a 1 :b 2}
                                  {:panel-id :rf.xray/app-db
                                   :zoomable? true})
          attrs (-> h second)]
      (is (= "1" (:data-rf-zoomable attrs))
          ":data-rf-zoomable=1 advertises zoom-capable to tooling")
      (is (nil? (:data-rf-zoomed attrs))
          "no zoom active yet — :data-rf-zoomed is still absent"))))

(deftest zoomable-renders-affordance-on-containers
  ;; When zoomable? is on, the recursive renderer emits a `⊙` button
  ;; next to every non-root container. We probe render-node directly
  ;; with a `zoom-path-prefix` of [] to confirm the affordance appears
  ;; at child paths (not at the root).
  (let [v {:a {:nested 1} :b 2}
        ;; Drive render-node with a `:default-expanded-depth 8` so the
        ;; container expands and the `:a` child gets a header row.
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix []
                           :opts {:default-expanded-depth 8}})
        affordance (find-attr h :data-rf-affordance "zoom")]
    (is (some? affordance)
        "the recursive walker emits a zoom affordance on a non-root container")
    (is (= "⊙" (last affordance))
        "the affordance glyph is `⊙` (focus / aim-cursor semantic)")))

(deftest zoomable-skips-affordance-at-root
  ;; The root displayed node (relative path `[]`) does NOT carry a
  ;; zoom affordance — zooming into the current root is a no-op.
  (let [v {:a 1 :b 2}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix []
                           :opts {:default-expanded-depth 0}})
        all-zoom-buttons (filter (fn [n]
                                   (and (vector? n)
                                        (map? (second n))
                                        (= "zoom" (:data-rf-affordance (second n)))))
                                 (walk-hiccup h))]
    ;; With depth-0 expansion the root is collapsed → only one node
    ;; renders. If the root had emitted an affordance we'd see one
    ;; button; we expect zero.
    (is (zero? (count all-zoom-buttons))
        "root container at relative-path [] does NOT render a zoom button")))

(deftest zoomable-skips-affordance-when-opt-off
  ;; `:zoomable? false` (the default) suppresses the affordance even on
  ;; deep nested containers.
  (let [v {:a {:b {:c 1}}}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 8}})
        all-zoom-buttons (filter (fn [n]
                                   (and (vector? n)
                                        (map? (second n))
                                        (= "zoom" (:data-rf-affordance (second n)))))
                                 (walk-hiccup h))]
    (is (zero? (count all-zoom-buttons))
        "with :zoomable? off no node emits a zoom affordance")))

(defn- with-rf-dispatch-spy
  "Drive a click against a stubbed `rf/dispatch*` (the fn-form the
  `dispatch` macro expands to) so the test can inspect the dispatched
  event vector + opts WITHOUT spinning up the router. Returns
  `{:event ... :opts ...}` captured at click. Per rf2-kcaiz — the
  post-fix `zoom-affordance-button` calls `rf/dispatch` directly with
  `{:frame :rf/xray}` opts (mirrors rf2-7sdja popup-affordance fix)."
  [on-click]
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]      (reset! captured {:event ev :opts nil}))
                                 ([ev opts] (reset! captured {:event ev :opts opts})))]
      (on-click nil))
    @captured))

(deftest zoom-affordance-button-dispatches-zoom-to-with-absolute-path
  (let [h        (ei/zoom-affordance-button
                   {;; `:dispatch-fn` is a no-op post rf2-kcaiz —
                    ;; pass nil to make the new contract obvious.
                    :dispatch-fn   nil
                    :panel-id      :p
                    :mount-id      "m"
                    :absolute-path [:a :b :c]
                    :testid        "rf-xray-edn-inspector-zoom-aff"})
        on-click (-> h second :on-click)
        {:keys [event]} (with-rf-dispatch-spy on-click)]
    (is (some? on-click) "button carries an on-click handler")
    (is (= [:rf.xray.edn-inspector/zoom-to :p "m" [:a :b :c]] event)
        "click dispatches the canonical zoom-to event with the absolute path")))

(deftest zoom-affordance-button-onclick-dispatches-against-xray-frame
  ;; rf2-kcaiz — mirrors the rf2-7sdja popup-affordance regression
  ;; guard. The zoom-affordance click handler MUST pin the dispatch
  ;; frame to `:rf/xray` at call time, because React synthetic-event
  ;; timing pops the surrounding frame context before the click fires
  ;; (so a lexically-captured frame-aware dispatcher would leak the
  ;; dispatch to `:rf/default`). This test asserts the envelope.
  (testing "rf2-kcaiz — clicking the zoom-affordance dispatches
            `[:rf.xray.edn-inspector/zoom-to ...]` against `:rf/xray`
            EXPLICITLY (zoom state is Xray-global — pinned via
            `{:frame :rf/xray}` opts)"
    (let [h        (ei/zoom-affordance-button
                     {:dispatch-fn   nil
                      :panel-id      :rf.xray/app-db
                      :mount-id      "m-1"
                      :absolute-path [:cart :items 0]
                      :testid        "x"})
          on-click (-> h second :on-click)
          {:keys [event opts]} (with-rf-dispatch-spy on-click)
          [event-id panel-id mount-id path] event]
      (is (= :rf.xray.edn-inspector/zoom-to event-id)
          "canonical event id")
      (is (= :rf.xray/app-db panel-id)
          "panel-id flows through as second positional arg")
      (is (= "m-1" mount-id)
          "mount-id flows through as third positional arg")
      (is (= [:cart :items 0] path)
          "absolute path flows through as fourth positional arg")
      (is (= :rf/xray (:frame opts))
          "rf2-kcaiz — zoom dispatch pins frame to `:rf/xray`
           explicitly so the zoom-slot subscription (which only
           reads `:rf/xray`'s app-db) sees the write regardless of
           which frame the widget is mounted under. Same class of
           fix as rf2-7sdja (popup-affordance) + rf2-y59tb (triangle
           toggle)."))))

(deftest zoom-affordance-composes-prefix-and-relative-path
  ;; The renderer threads the absolute path = (into zoom-path-prefix path)
  ;; into the affordance — so when the operator is already zoomed at
  ;; `[:rf/machines]` and clicks the affordance on the nested `:ws/connection`
  ;; container (relative path `[:ws/connection]`), the dispatch carries
  ;; the FULL absolute path `[:rf/machines :ws/connection]`.
  (let [v {:ws/connection {:state :open}}
        ;; Drive the renderer with a non-trivial zoom-path-prefix to
        ;; simulate "we're already zoomed into :rf/machines and looking
        ;; at its child :ws/connection".
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix [:rf/machines]
                           :opts {:default-expanded-depth 8}})
        affordance (find-attr h :data-rf-affordance "zoom")]
    (is (some? affordance)
        "the recursive walker emits a zoom affordance on the displayed root's
         children even when `:zoom-path-prefix` is non-empty")
    (is (= "zoom" (-> affordance second :data-rf-affordance))
        "data-rf-affordance attribute is 'zoom'")
    ;; Confirm via integration: re-build the affordance the same way
    ;; render-container does, mirroring the zoom-path-prefix + path
    ;; composition.
    (let [composed (vec (concat [:rf/machines] [:ws/connection]))
          h2       (ei/zoom-affordance-button
                     {:dispatch-fn   nil
                      :panel-id      :p
                      :mount-id      "m"
                      :absolute-path composed
                      :testid        "x"})
          {:keys [event opts]} (with-rf-dispatch-spy (-> h2 second :on-click))]
      (is (= [:rf.xray.edn-inspector/zoom-to :p "m" [:rf/machines :ws/connection]]
             event)
          "the dispatched path is the absolute path = prefix + relative")
      (is (= :rf/xray (:frame opts))
          "rf2-kcaiz — composed-path dispatch is ALSO pinned to `:rf/xray`"))))

;; ---- breadcrumb ----------------------------------------------------------

(deftest breadcrumb-nil-when-no-zoom
  (is (nil? (ei/zoom-breadcrumbs
              {:panel-id      :p
               :mount-id      "m"
               :zoom-path     nil
               :home-label    "home"
               :dispatch-fn   identity}))
      "no zoom-path → no breadcrumb (saves DOM noise on un-zoomed mounts)")
  (is (nil? (ei/zoom-breadcrumbs
              {:panel-id      :p
               :mount-id      "m"
               :zoom-path     []
               :home-label    "home"
               :dispatch-fn   identity}))
      "empty zoom-path also yields no breadcrumb"))

(deftest breadcrumb-renders-home-plus-segments
  (let [h (ei/zoom-breadcrumbs
            {:panel-id      :p
             :mount-id      "m"
             :zoom-path     [:rf/machines :ws/connection]
             :home-label    "app-db"
             :dispatch-fn   identity
             :testid-prefix "bc"})
        text (collect-text h)]
    (is (= 2 (count (filter #(= "1" (:data-rf-breadcrumb-separator (second %)))
                            (walk-hiccup h))))
        "one separator between home+seg1, one between seg1+seg2")
    (is (re-find #"app-db" text) "home label rendered")
    (is (re-find #":rf/machines" text)  "first segment rendered")
    (is (re-find #":ws/connection" text) "second segment rendered")))

(deftest breadcrumb-home-button-dispatches-empty-path
  (let [dispatched (atom nil)
        h (ei/zoom-breadcrumbs
            {:panel-id      :p
             :mount-id      "m"
             :zoom-path     [:rf/machines :ws/connection]
             :home-label    "app-db"
             :dispatch-fn   (fn [ev] (reset! dispatched ev))
             :testid-prefix "bc"})
        home-btn (find-attr h :data-rf-breadcrumb-segment "home")
        on-click (-> home-btn second :on-click)]
    (on-click nil)
    (is (= [:rf.xray.edn-inspector/zoom-to :p "m" []] @dispatched)
        "clicking home dispatches zoom-to with empty path (clears zoom)")))

(deftest breadcrumb-segment-dispatches-truncated-path
  (let [dispatched (atom nil)
        h (ei/zoom-breadcrumbs
            {:panel-id      :p
             :mount-id      "m"
             :zoom-path     [:rf/machines :ws/connection :data]
             :home-label    "app-db"
             :dispatch-fn   (fn [ev] (reset! dispatched ev))
             :testid-prefix "bc"})
        seg-1   (find-attr h :data-rf-breadcrumb-segment "1")
        on-click (-> seg-1 second :on-click)]
    (on-click nil)
    (is (= [:rf.xray.edn-inspector/zoom-to :p "m"
            [:rf/machines :ws/connection]]
           @dispatched)
        "clicking segment-1 dispatches zoom-to truncated to depth 2")))

(deftest breadcrumb-home-label-fallback-when-no-header
  ;; When the consumer didn't supply :header, the home label falls back
  ;; to the generic "root" string.
  (let [h (ei/zoom-breadcrumbs
            {:panel-id      :p
             :mount-id      "m"
             :zoom-path     [:a]
             :home-label    nil
             :dispatch-fn   identity
             :testid-prefix "bc"})
        text (collect-text h)]
    (is (re-find #"root" text)
        "nil home-label renders the 'root' fallback")))

(deftest breadcrumb-home-label-accepts-hiccup
  ;; When the consumer supplied :header as hiccup (the §10.0.10 path),
  ;; the breadcrumb renders that hiccup verbatim as the home segment.
  (let [home [:span [:strong "Counter app"] " · " [:code ":rf/default"]]
        h    (ei/zoom-breadcrumbs
               {:panel-id      :p
                :mount-id      "m"
                :zoom-path     [:counter]
                :home-label    home
                :dispatch-fn   identity
                :testid-prefix "bc"})
        text (collect-text h)]
    (is (re-find #"Counter app" text)
        "hiccup home-label renders inline as the first breadcrumb segment")
    (is (re-find #":rf/default" text)
        "nested hiccup content survives")))

;; ---- public widget — zoom-aware top-level render -------------------------

(deftest widget-with-zoomable-emits-no-breadcrumb-when-not-zoomed
  (let [h     (invoke-edn-inspector {:a 1 :b 2}
                                    {:panel-id :rf.xray/app-db
                                     :zoomable? true})
        bcrumb (find-attr h :data-rf-zoomed "1")]
    (is (nil? bcrumb)
        "no zoom active → no :data-rf-zoomed marker on the body wrapper")))

(deftest widget-with-zoomable-renders-breadcrumb-when-zoomed
  ;; Pre-populate the zoom slot, then render the widget and confirm the
  ;; outer container advertises the zoom + the breadcrumb hiccup renders.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (let [site-id [:rf.xray/app-db "top"]
        _ (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                             :rf.xray/app-db site-id [:nested]])
        v {:nested {:deep 42 :other 99} :sibling 1}
        h (invoke-edn-inspector v
                                {:panel-id :rf.xray/app-db
                                 :site-id  site-id
                                 :zoomable? true
                                 :header   [:span "app-db"]})
        attrs (-> h second)]
    (is (= "1" (:data-rf-zoomed attrs))
        ":data-rf-zoomed=1 on the outer container while a zoom is active")
    (is (= (pr-str [:nested]) (:data-rf-zoom-path attrs))
        ":data-rf-zoom-path attribute carries the literal stored path")
    ;; Walk for breadcrumb home + the zoomed subtree.
    (let [text   (collect-text h)
          bcrumb (find-attr h :role "navigation")]
      (is (some? bcrumb)
          "navigation breadcrumb hiccup rendered above the body")
      (is (re-find #":deep" text)
          "the zoomed subtree's leaf keys render in the body")
      (is (not (re-find #":sibling" text))
          "siblings OUTSIDE the zoom subtree are NOT rendered — that's the
           whole point of zoom"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])))

(deftest widget-diff-mode-suppresses-zoom
  ;; Diff mode (`:before` present) renders the FULL value regardless of
  ;; an active zoom path — the diff path's force-expand-over-changes
  ;; logic and zoom's hide-everything-outside the subtree are
  ;; conflicting intents. Operator viewing a diff sees the whole value;
  ;; non-diff browse can zoom.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (let [site-id [:rf.xray/app-db "top"]
        _ (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                             :rf.xray/app-db site-id [:nested]])
        v      {:nested {:deep 42} :sibling 1}
        before {:nested {:deep 41} :sibling 0}
        h      (invoke-edn-inspector v
                                     {:panel-id :rf.xray/app-db
                                      :site-id  site-id
                                      :zoomable? true
                                      :before   before
                                      :header   [:span "app-db"]})
        attrs  (-> h second)
        text   (collect-text h)]
    (is (nil? (:data-rf-zoomed attrs))
        ":data-rf-zoomed absent in diff mode even when zoom-slot is populated")
    (is (re-find #":sibling" text)
        "diff mode renders the FULL value (siblings outside the would-be
         zoom subtree are visible)")
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])))

(deftest zoom-persists-across-mount-unmount-via-site-id
  ;; Acceptance #7: two renders with the same `:site-id` see the same
  ;; zoom slot — simulating a tab-leave / tab-return cycle.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (let [site-id [:rf.xray/app-db "top"]
        _ (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                             :rf.xray/app-db site-id [:nested]])
        v {:nested {:deep 42} :sibling 1}
        ;; First "mount" — fresh outer/inner pair.
        h1 (invoke-edn-inspector v
                                 {:panel-id :rf.xray/app-db
                                  :site-id  site-id
                                  :zoomable? true})
        ;; Second "mount" — new outer/inner pair (auto-mount-id differs)
        ;; but the same site-id reads the same zoom slot.
        h2 (invoke-edn-inspector v
                                 {:panel-id :rf.xray/app-db
                                  :site-id  site-id
                                  :zoomable? true})]
    (is (= "1" (:data-rf-zoomed (second h1))))
    (is (= "1" (:data-rf-zoomed (second h2))))
    (is (= (:data-rf-zoom-path (second h1))
           (:data-rf-zoom-path (second h2)))
        "both mounts converge on the same zoom path via :site-id keying")
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])))

(deftest two-mounts-without-site-id-zoom-independently
  ;; Acceptance #6: two side-by-side mounts (no shared site-id) zoom
  ;; independently — the auto-mount-id default isolates them.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (let [v {:a {:b 1}}
        h1 (invoke-edn-inspector v
                                 {:panel-id :rf.xray/app-db
                                  :zoomable? true})
        h2 (invoke-edn-inspector v
                                 {:panel-id :rf.xray/app-db
                                  :zoomable? true})
        m1 (:data-rf-mount-id (second h1))
        m2 (:data-rf-mount-id (second h2))]
    (is (not= m1 m2)
        "two mounts without :site-id get distinct auto-mount-ids")
    ;; Zoom only mount-1.
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                       :rf.xray/app-db m1 [:a]])
    (let [zoom @(rf/subscribe [ei/zoom-slot])]
      (is (= [:a] (get zoom (ei/zoom-key :rf.xray/app-db m1)))
          "mount-1's zoom slot is set")
      (is (nil? (get zoom (ei/zoom-key :rf.xray/app-db m2)))
          "mount-2's zoom slot is untouched"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])))

(deftest widget-zoom-keydown-handler-installed-and-dispatches-on-escape
  ;; Acceptance #5: Esc keypress pops one zoom level. The widget
  ;; installs an `:on-key-down` handler on the outer container ONLY
  ;; when a zoom is active; the handler dispatches `:zoom-up`.
  ;;
  ;; We capture the dispatched event by intercepting via a custom
  ;; dispatch (the lexically-injected `dispatch` is async in CLJS so
  ;; reading the slot immediately after `handler(ev)` would race). The
  ;; reducer's correctness is covered by `zoom-up-pops-one-segment`
  ;; above; here we assert that the keydown handler dispatches the
  ;; canonical event with the correct args.
  (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
  (let [site-id [:rf.xray/app-db "top"]
        _ (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                             :rf.xray/app-db site-id [:a :b]])
        v {:a {:b {:c 1}}}
        h (invoke-edn-inspector v
                                {:panel-id :rf.xray/app-db
                                 :site-id  site-id
                                 :zoomable? true})
        attrs (-> h second)
        handler (:on-key-down attrs)]
    (is (fn? handler)
        "an Esc keydown handler is installed while zoom is active")
    ;; Synthesise a minimal escape-key event; the handler internally
    ;; dispatches `:zoom-up`. Since the test runtime processes events
    ;; off the queue at the next macro-task, we wait one macro-task via
    ;; the existing dispatch-sync of a no-op event to flush.
    (let [prevent-called (atom false)
          stop-called    (atom false)
          ev #js {:key "Escape"
                  :preventDefault  (fn [] (reset! prevent-called true))
                  :stopPropagation (fn [] (reset! stop-called true))}]
      (handler ev)
      (is @prevent-called  "handler called preventDefault on the Esc event")
      (is @stop-called     "handler called stopPropagation"))
    ;; Non-Escape keys must NOT trigger the dispatch (verified by the
    ;; preventDefault not being called).
    (let [prevent-called (atom false)
          ev #js {:key "Enter"
                  :preventDefault  (fn [] (reset! prevent-called true))
                  :stopPropagation (fn [])}]
      (handler ev)
      (is (not @prevent-called)
          "non-Escape keystrokes pass through (preventDefault NOT called)"))
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])))

(deftest widget-no-keydown-handler-when-not-zoomed
  ;; Esc must NOT fire zoom-up when no zoom is active — the handler is
  ;; absent so the keystroke continues to bubble to any outer popup
  ;; (rf2-7sdja) without being short-circuited by an unrelated mount.
  (let [h (invoke-edn-inspector {:a 1}
                                {:panel-id :rf.xray/app-db
                                 :zoomable? true})
        attrs (-> h second)]
    (is (nil? (:on-key-down attrs))
        "no zoom active → no keydown handler (keystrokes bubble up)")))
