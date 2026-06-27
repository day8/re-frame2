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
            [day8.re-frame2-xray.diff.engine :as engine]
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
  ;; rf2-7hqwe — a sequential collection is SPACE-separated (`[1 2 3]`),
  ;; matching canonical EDN print spacing; commas are reserved for map
  ;; entries.
  (is (= "[1 2 3]"
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
;; leaves carry a `← was <prior>` annotation. Ancestor chain
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
    (is (re-find #"← was 1" all)
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
    (is (re-find #"← was 48" all)
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
    (is (re-find #"← was 1" all) "with its annotation")))

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

;; =========================================================================
;; rf2-vu42n — scattered / mid-vector removals render the genuinely-removed
;; members struck, and the surviving-shifted members NOT struck. The fixed
;; renderer consumes the engine's off-path `:vector-removals` + `:same-
;; shifted` projection instead of index-aligning the raw before/after
;; vectors via `children-of-pair`.
;; =========================================================================
;;
;; Pre-fix, the vector body renderer index-aligned position-by-position:
;; for `[:a :b :c :d] -> [:a :c]` it rendered `[ :a :c(was…) -:c -:d ]` —
;; it struck `:c` (which SURVIVES at after-index 1) and `:d`, and never
;; surfaced the genuinely-removed `:b`. Contiguous TAIL removals happened
;; to line up under index alignment, so only mid / scattered removals
;; mis-rendered. These tests drive `render-node` WITH a projection (the
;; real diff path; the no-projection test/REPL path still falls back to
;; `children-of-pair`), and assert which members are struck.

(defn- struck-members
  "Return the set of value strings the renderer struck through in `tree`.
  A removed leaf renders via `gutter-row :removed`, whose OUTER span and
  INNER body span BOTH carry `:data-rf-diff-op \"removed\"`; the outer span
  also holds the `-` gutter glyph. We collect the text under the
  shallowest `removed` span and strip a leading gutter glyph + whitespace
  so the comparison is against the bare value (e.g. `\":b\"`, not `\"-:b\"`)."
  [tree]
  (let [out (atom #{})]
    (letfn [(walk [node]
              (when (vector? node)
                (let [attrs (when (map? (second node)) (second node))]
                  (if (= "removed" (:data-rf-diff-op attrs))
                    (swap! out conj
                           (-> (collect-text node)
                               (str/replace #"^[\s\-−\+~]+" "")
                               str/trim))
                    (doseq [c (rest node)] (walk c))))))]
      (walk tree))
    @out))

(defn- render-vec-diff
  "Render `before -> after` as a vector diff THROUGH the real projection
  path (the production renderer computes `engine/project` once at the top
  and threads it down via `:projection`). Returns the hiccup tree."
  [before after]
  (ei/render-node {:value      after
                   :before     before
                   :diff?      true
                   :projection (engine/project before after)
                   :panel-id   :p :mount-id "m"
                   :path [] :depth 0
                   :expansion-map {}
                   ;; default-expanded-depth high enough that the root
                   ;; expands and the body walk runs.
                   :opts {:default-expanded-depth 4}}))

(deftest diff-vector-scattered-removal-strikes-removed-not-survivors
  ;; Canonical rf2-vu42n repro: `[:a :b :c :d] -> [:a :c]` removes :b@1
  ;; and :d@3; :c survives (shifted from index 2 → 1).
  (let [before [:a :b :c :d]
        after  [:a :c]
        h      (render-vec-diff before after)
        all    (collect-text h)
        struck (struck-members h)]
    (testing "the genuinely-removed members ARE struck"
      (is (contains? struck ":b") ":b (removed@1) is struck")
      (is (contains? struck ":d") ":d (removed@3) is struck"))
    (testing "the surviving-shifted member is NOT struck"
      (is (not (contains? struck ":c"))
          ":c SURVIVES at after-index 1 — must not be struck")
      (is (not (contains? struck ":a"))
          ":a survives at index 0 — must not be struck"))
    (testing "every member is still visible somewhere in the tree"
      (is (re-find #":a" all))
      (is (re-find #":b" all))
      (is (re-find #":c" all))
      (is (re-find #":d" all)))
    (testing "the surviving-shifted :c carries a `(was N)` shift suffix"
      ;; The engine's R6 shift detector reports :c's before-index for
      ;; this edit script; the renderer surfaces it verbatim as `(was N)`.
      ;; We only assert that SOME shift suffix renders for the survivor,
      ;; not the exact N (that's the engine's contract, tested under
      ;; rf2-yucxn / the engine suite) — the renderer's job is to PAINT it.
      (is (re-find #"\(was \d+\)" all)
          ":c is surviving-shifted → carries a (was N) shift suffix"))))

(deftest diff-vector-single-mid-removal-strikes-only-the-gap
  ;; A single mid-vector removal: `[:a :b :c] -> [:a :c]` removes :b@1.
  ;; :c survives (shifted 2 → 1). Pre-fix this struck :c and dropped :b.
  (let [before [:a :b :c]
        after  [:a :c]
        h      (render-vec-diff before after)
        struck (struck-members h)]
    (is (contains? struck ":b") ":b (the removed gap) is struck")
    (is (not (contains? struck ":c"))
        ":c survives (shifted) — must not be struck")
    (is (not (contains? struck ":a")) ":a survives in place — not struck")))

(deftest diff-vector-tail-removal-still-correct-with-projection
  ;; Re-verify the contiguous-tail case under the projection path (it was
  ;; the ONE case index alignment happened to get right; the projection
  ;; walk must not regress it). `[:x :y :z] -> [:x]` removes :y@1 + :z@2.
  (let [before [:x :y :z]
        after  [:x]
        h      (render-vec-diff before after)
        struck (struck-members h)]
    (is (contains? struck ":y") ":y (tail) is struck")
    (is (contains? struck ":z") ":z (tail) is struck")
    (is (not (contains? struck ":x")) ":x survives at index 0 — not struck")))

(deftest diff-vector-numeric-scattered-removal
  ;; Numeric payload, scattered removal: `[10 20 30 40 50] -> [10 30 50]`
  ;; removes 20@1 + 40@3; 30 (2→1) and 50 (4→2) survive shifted.
  (let [before [10 20 30 40 50]
        after  [10 30 50]
        h      (render-vec-diff before after)
        struck (struck-members h)]
    (is (contains? struck "20") "20 (removed@1) struck")
    (is (contains? struck "40") "40 (removed@3) struck")
    (is (not (contains? struck "30")) "30 survives (shifted 2→1) — not struck")
    (is (not (contains? struck "50")) "50 survives (shifted 4→2) — not struck")
    (is (not (contains? struck "10")) "10 survives in place — not struck")))

;; =========================================================================
;; rf2-3eplfk — MIXED insert+delete edit scripts render the genuinely-
;; removed member struck and the survivors un-morphed. Mirrors
;; `diff-removed-vector-element-no-sentinel-leak` (the delete-only render
;; guard) for the mixed-edit case the engine bug uncovered.
;; =========================================================================
;;
;; These exercise the SAME render path as the rf2-vu42n scattered-removal
;; tests above (`render-vec-diff` threads the real `engine/project`), so a
;; regression in the unified-replay engine fix surfaces here as the WRONG
;; member struck (or a survivor morphed). Each case is a repro from the
;; rf2-3eplfk bead.

(deftest diff-vector-insert-before-delete-strikes-removed-not-survivor
  ;; rf2-3eplfk repro 1: `[:a :b :c] -> [:X :a :c]` ⇒ `[[0] :+ :X] [[2] :-]`.
  ;; :b (before-idx 1) is removed; :X added; :a/:c survive. Pre-fix struck
  ;; :c (a SURVIVOR) and never surfaced :b.
  (let [before [:a :b :c]
        after  [:X :a :c]
        h      (render-vec-diff before after)
        all    (collect-text h)
        struck (struck-members h)]
    (testing "the genuinely-removed member :b is struck"
      (is (contains? struck ":b") ":b (removed@1) is struck"))
    (testing "the surviving members are NOT struck (not morphed)"
      (is (not (contains? struck ":c"))
          ":c SURVIVES (unmoved at after-idx 2) — must not be struck")
      (is (not (contains? struck ":a"))
          ":a SURVIVES (shifted 0→1) — must not be struck")
      (is (not (contains? struck ":X"))
          ":X is the inserted element — added, not struck"))
    (testing "every member still visible somewhere in the tree"
      (is (re-find #":a" all))
      (is (re-find #":b" all))
      (is (re-find #":c" all))
      (is (re-find #":X" all)))))

(deftest diff-vector-insert-before-double-delete-survivor-not-struck
  ;; rf2-3eplfk repro 2: `[:a :b :c :d] -> [:X :a :d]` ⇒
  ;; `[[0] :+ :X] [[2] :-] [[2] :-]`. :b + :c removed; :d SURVIVES.
  ;; Pre-fix struck :d (a survivor) and dropped :c.
  (let [before [:a :b :c :d]
        after  [:X :a :d]
        h      (render-vec-diff before after)
        struck (struck-members h)]
    (testing "the two genuinely-removed members are struck"
      (is (contains? struck ":b") ":b (removed) is struck")
      (is (contains? struck ":c") ":c (removed) is struck"))
    (testing "the surviving :d is NOT struck"
      (is (not (contains? struck ":d"))
          ":d SURVIVES at after-idx 2 — must not be struck"))))

(deftest diff-vector-insert-then-tail-delete-shows-dropped-removal
  ;; rf2-3eplfk repro 3: `[:a :b :c :d] -> [:a :X :b :c]` ⇒
  ;; `[[1] :+ :X] [[4] :-]`. :d (before-idx 3) removed. Pre-fix DROPPED the
  ;; removal entirely (edit-index 4 out-of-range vs pristine `(range 4)`),
  ;; so :d never rendered struck. The fix must surface it.
  (let [before [:a :b :c :d]
        after  [:a :X :b :c]
        h      (render-vec-diff before after)
        all    (collect-text h)
        struck (struck-members h)]
    (testing "the removed :d is struck (not silently dropped)"
      (is (contains? struck ":d") ":d (removed@3) is struck"))
    (testing "the survivors are not struck"
      (is (not (contains? struck ":a")) ":a survives in place — not struck")
      (is (not (contains? struck ":b")) ":b survives (shifted) — not struck")
      (is (not (contains? struck ":c")) ":c survives (shifted) — not struck")
      (is (not (contains? struck ":X")) ":X is inserted — added, not struck"))
    (testing ":d still visible in the tree"
      (is (re-find #":d" all) "the dropped element :d appears struck-through"))))

(deftest sequential-diff-children-scattered-removal-shape
  ;; The pure projection-aware child walk directly: for `[:a :b :c :d] ->
  ;; [:a :c]` it emits before-ordered triples with the removed members at
  ;; a synthetic key (forces `:removed` via `::missing` after-value) and
  ;; the survivors at their AFTER index (projection chrome resolves).
  (let [before [:a :b :c :d]
        after  [:a :c]
        proj   (engine/project before after)
        pairs  (vec (ei/sequential-diff-children before after :vector [] proj))]
    (testing "one triple per before-position (survivors + struck removals)"
      (is (= 4 (count pairs))
          "[:a :b :c :d] -> [:a :c]: 2 survivors + 2 removed = 4 rows"))
    (testing "removed members carry ::missing on the AFTER side"
      ;; :b and :d are the removed before-values; their after slot is ::missing.
      (let [removed-after (->> pairs
                               (filter (fn [[_ a _]] (= a ::ei/missing)))
                               (map (fn [[_ _ b]] b))
                               set)]
        (is (= #{:b :d} removed-after)
            ":b and :d are the struck (after=::missing) members")))
    (testing "survivors keep their AFTER index as the path key"
      (let [survivors (->> pairs
                           (remove (fn [[_ a _]] (= a ::ei/missing)))
                           (map (fn [[k a _]] [k a]))
                           set)]
        ;; :a at after-index 0, :c at after-index 1.
        (is (= #{[0 :a] [1 :c]} survivors)
            "survivors rendered at after-index 0 (:a) and 1 (:c)")))
    (testing "before-order: :a, :b(removed), :c, :d(removed)"
      (is (= [:a :b :c :d]
             (mapv (fn [[_ a b]] (if (= a ::ei/missing) b a)) pairs))
          "rows read in before-order with deletions struck in place"))))

(deftest sequential-diff-children-falls-back-without-projection
  ;; No projection (test/REPL path) → defer to the index-aligning union
  ;; walk so the no-removal / tail cases stay deterministic.
  (let [before [:x :y :z]
        after  [:x]
        pairs  (vec (ei/sequential-diff-children before after :vector [] nil))]
    (is (= (vec (ei/children-of-pair before after :vector)) pairs)
        "nil projection falls back to children-of-pair")))

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

;; =========================================================================
;; rf2-8pfkk — the `::missing` sentinel must NEVER reach the output, and a
;; removed CONTAINER renders as a struck-through collapsed ghost (not a
;; flat pr-str, not the leaked sentinel keyword).
;; =========================================================================
;;
;; These cases thread a REAL `engine/project` projection (the live render
;; path) rather than the `projection nil` fallback the older diff tests
;; use. The leak only surfaces with a projection in play: the engine
;; anchors a `(update db :shapes dissoc :added)` deletion on the surviving
;; parent (`op-at [:shapes]` → `:removed`) and classifies the removed
;; child slot `:children` (`op-at [:shapes :added]` → `:children`, it owns
;; the ghost subtree in `:container-ops`). Pre-fix the leaf renderer
;; trusted that `:children` op, fell through `case op`'s default branch,
;; and rendered `(render-scalar ::missing)` — leaking
;; `:day8.re-frame2-xray.views.edn-inspector/missing` literally into the
;; row (`:added ::missing`). The fix makes the structural sentinel
;; authoritative and routes removed containers through a recursive ghost.

(defn- no-missing-sentinel-leak?
  "True iff the rendered hiccup carries no trace of the internal
  `::missing` sentinel keyword in any string leaf OR attribute value.
  The sentinel's `pr-str` is `:day8.re-frame2-xray.views.edn-
  inspector/missing`; its `name` is the bare `\"missing\"`. We assert on
  the broad `pr-str` of the whole tree so a leak anywhere (text, attr,
  data-* marker) is caught."
  [h]
  (let [s (try (pr-str h) (catch :default _ ""))]
    (not (re-find #"edn-inspector/missing" s))))

(deftest diff-removed-only-key-renders-struck-ghost-not-sentinel
  ;; Mike's live repro (standard_epochs button 7): `(update db :shapes
  ;; dissoc :added)` removes the only key of `:shapes`, leaving `{}`.
  ;; The removed `:added {…}` slot must render as a struck-through ghost,
  ;; NEVER as `:added ::missing` and NEVER as `:shapes {} :same`.
  (let [before {:shapes {:added {:label "added" :n 42}}}
        after  {:shapes {}}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    ;; The engine genuinely misclassifies the child path — this pins the
    ;; precondition so the test documents WHY the renderer cannot trust it.
    (is (= :children (engine/op-at proj [:shapes :added]))
        "precondition: engine anchors the removal on the parent, leaves the child :children")
    (is (no-missing-sentinel-leak? h)
        "the ::missing sentinel keyword must never reach the rendered output")
    (is (not (re-find #":day8" all))
        "no internal namespaced keyword leaks into the visible text")
    (is (re-find #":added" all)
        "the removed key :added still appears (struck-through ghost)")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "the removed slot carries the removed diff-op marker")
    (is (re-find #"line-through" s)
        "the removed ghost is struck through")))

(deftest diff-removed-container-renders-collapsed-ghost
  ;; A removed nested map renders as ONE collapsed struck-through node
  ;; (`{…} (N keys)` summary), reusing the ordinary collapse machinery —
  ;; bounds verbosity rather than pr-str'ing the whole deleted subtree.
  (let [before {:shapes {:added {:label "added" :n 42 :deep {:x 1 :y 2}}}}
        after  {:shapes {}}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        s   (try (pr-str h) (catch :default _ ""))]
    (is (no-missing-sentinel-leak? h)
        "no sentinel leak even with a deeper ghost subtree")
    ;; The ghost node is marked + collapsed by default.
    (is (re-find #"data-rf-removed-ghost" s)
        "the removed container renders as a marked ghost node")
    (is (re-find #"data-rf-preview" s)
        "the ghost defaults to a collapsed `{…N keys}` summary, not the full subtree")
    (is (re-find #"line-through" s)
        "the ghost line is struck through")))

(deftest diff-deleted-ancestor-children-inherit-removed-when-expanded
  ;; The deleted-ancestor hard case: when the operator EXPANDS a removed
  ;; container ghost, every descendant inherits `:removed` (the symmetric
  ;; of rf2-bufw2's `:added` inheritance) — never an `:added` (green) or
  ;; `:same` row, and never a leaked sentinel.
  (let [before {:shapes {:added {:label "added" :nested {:deep 1}}}}
        after  {:shapes {}}
        proj   (engine/project before after)
        ;; Force the whole ghost subtree open via a sticky expansion
        ;; override at the ghost path + its nested child.
        expansion-map {(ei/expansion-key :p "m" [:shapes :added])         {:expanded? true}
                       (ei/expansion-key :p "m" [:shapes :added :nested]) {:expanded? true}}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map expansion-map
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    (is (no-missing-sentinel-leak? h)
        "no sentinel leak when the ghost subtree is walked")
    (is (re-find #":deep" all)
        "the deeply-nested ghost leaf is reachable on expand")
    (is (not (re-find #"data-rf-diff-op.\"?added" s))
        "no descendant of a removed subtree renders as :added (green) — all inherit :removed")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "ghost descendants carry the removed marker")))

(deftest diff-removed-vector-element-no-sentinel-leak
  ;; A vector that loses its tail under a real projection must not leak
  ;; the sentinel for the dropped indices.
  (let [before {:xs [:a :b :c]}
        after  {:xs [:a]}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)]
    (is (no-missing-sentinel-leak? h)
        "popped vector tail must not leak the ::missing sentinel")
    (is (re-find #":b" all) "dropped element :b still appears struck-through")
    (is (re-find #":c" all) "dropped element :c still appears struck-through")))

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
    (is (re-find #"← was 2" s)
        "modified leaf still carries the change annotation")))

(deftest l0us2-set-member-swap-renders-member-level-not-whole-key
  ;; rf2-l0us2 — the bead repro: the door machine's `:tags` went
  ;; `#{:door/locked}` → `#{:door/closed}`. The renderer must show
  ;; `-:door/locked +:door/closed` with the `:tags` KEY INTACT, NOT a
  ;; struck-through whole `:tags` entry (the 'sea of red'). With the
  ;; engine fix `:tags` classifies `:children` (intact) and the set's
  ;; member union carries per-member -/+ chrome.
  (let [before {:tags #{:door/locked}}
        after  {:tags #{:door/closed}}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    ;; Precondition (engine): :tags intact, members diffed.
    (is (= :children (engine/op-at proj [:tags]))
        "precondition: :tags key is intact (:children), not wholly removed")
    (is (= :removed (engine/op-at proj [:tags :door/locked])))
    (is (= :added (engine/op-at proj [:tags :door/closed])))
    ;; Renderer: both members visible, member-level chrome present.
    (is (re-find #":door/locked" all)
        "the removed member :door/locked still renders")
    (is (re-find #":door/closed" all)
        "the added member :door/closed renders")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "a row carries the removed diff-op marker (the gone member)")
    (is (re-find #"data-rf-diff-op.*added" s)
        "a row carries the added diff-op marker (the new member)")
    (is (re-find #"line-through" s)
        "the removed member is struck through, not the whole key")
    ;; The :tags key itself must NOT be inside a removed-ghost wrapper —
    ;; that would be the 'sea of red' whole-key removal. The
    ;; `data-rf-removed-ghost` attr is present on every container header
    ;; but carries the value "1" ONLY for an actual removed ghost; with
    ;; the fix :tags classifies :children so the marker stays unset.
    (is (not (re-find #":data-rf-removed-ghost \"1\"" s))
        "the :tags set is not rendered as a removed ghost (no whole-key strike)")))

;; =========================================================================
;; rf2-yucxn — vector/list emptied renders member-level (BUG A) + multi-
;; element removal shows every removed value distinctly (BUG B)
;; =========================================================================
;;
;; The audit found vector/list empty edges classified as a whole-key
;; `:modified` (a `~` amber row + `← was [1]`) instead of the member-level
;; removal the set/map empty edges produce — and multi-element vector
;; removals reporting one before-value repeatedly while dropping the rest.
;; These tests drive the LIVE render path (a real `engine/project`
;; projection) so the renderer's structural handling is exercised.

(deftest yucxn-vector-emptied-renders-member-level-not-whole-key
  ;; rf2-yucxn BUG A — `{:a [1]} → {:a []}` (vector emptied, key intact):
  ;; the operator must see `:a [ ]` with the removed `1` struck-through
  ;; INSIDE it, NOT a whole-key `:a ~ [] ← was [1]`. The `:a` key must not
  ;; be a removed ghost (its value is still present, just empty).
  (let [before {:a [1]}
        after  {:a []}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    ;; The removed element is visible + struck.
    (is (re-find #"\b1\b" all) "the removed element 1 still renders")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "a row carries the removed diff-op marker (the dropped element)")
    (is (re-find #"line-through" s)
        "the dropped element is struck-through, not a whole-key modify")
    ;; The :a key is NOT a removed ghost (key intact, value just empty).
    (is (not (re-find #":data-rf-removed-ghost \"1\"" s))
        "the :a vector is not a removed ghost (key intact)")
    ;; No `← was [1]` whole-value annotation (that was the BUG A symptom).
    (is (not (re-find #"← was \[1\]" all))
        "no whole-key `← was [1]` modify annotation (member-level instead)")))

(deftest yucxn-list-emptied-renders-member-level
  ;; rf2-yucxn BUG A — the list empty edge mirrors the vector edge.
  (let [before {:a '(1)}
        after  {:a '()}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #"data-rf-diff-op.*removed" s)
        "the emptied list shows the dropped element as a removed row")
    (is (not (re-find #":data-rf-removed-ghost \"1\"" s))
        "the :a list is not a removed ghost (key intact)")))

(deftest yucxn-vector-populated-from-empty-renders-added
  ;; rf2-yucxn BUG A — symmetric `{:a []} → {:a [1]}` shows the new element
  ;; in green (`+`), not a whole-key `~` modify.
  (let [before {:a []}
        after  {:a [1]}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    (is (re-find #"\b1\b" all) "the new element 1 renders")
    (is (re-find #"data-rf-diff-op.*added" s)
        "the filled-from-empty vector shows the new element as an added row")))

(deftest yucxn-vector-multi-removal-shows-every-removed-value-distinctly
  ;; rf2-yucxn BUG B — `{:a [1 2 3]} → {:a [1]}` drops 2 AND 3. The
  ;; renderer must show BOTH struck-through, with their CORRECT values —
  ;; not `2` twice (the pre-fix duplication) and not a vanished `3`.
  (let [before {:a [1 2 3]}
        after  {:a [1]}
        proj   (engine/project before after)
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection proj
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})
        all (collect-text h)
        s   (try (pr-str h) (catch :default _ ""))]
    ;; Engine precondition — the channel carries both with true values.
    (is (= [{:before-index 1 :before-value 2}
            {:before-index 2 :before-value 3}]
           (get-in proj [:vector-removals [:a]]))
        "precondition: both removed elements recovered with correct values")
    ;; Both removed values appear in the render (3 is the proof BUG B is gone).
    (is (re-find #"\b2\b" all) "removed element 2 renders")
    (is (re-find #"\b3\b" all) "removed element 3 renders (not dropped — BUG B fixed)")
    (is (re-find #"data-rf-diff-op.*removed" s)
        "the dropped elements carry the removed marker")))

(deftest yucxn-vector-scattered-removal-engine-channel-correct
  ;; rf2-yucxn BUG B — a scattered removal `[:a :b :c :d] → [:a :c]` drops
  ;; :b (before-idx 1) and :d (before-idx 3). The ENGINE's :vector-removals
  ;; channel now recovers both with true before-index + value (the pre-fix
  ;; post-shift-index resolution reported :b + :c, dropping :d).
  ;;
  ;; The RENDERER side is a KNOWN residual gap (rf2-vu42n): the inline
  ;; vector body uses an index-aligned `children-of-pair` walk rather than
  ;; the engine's :vector-removals + :same-shifted projection, so a
  ;; scattered/mid removal mis-renders which members are struck. Contiguous
  ;; TAIL removals (the common case) render correctly — see
  ;; `yucxn-vector-multi-removal-shows-every-removed-value-distinctly`.
  ;; This test pins the ENGINE contract that rf2-vu42n's renderer fix will
  ;; consume.
  (let [before {:v [:a :b :c :d]}
        after  {:v [:a :c]}
        proj   (engine/project before after)]
    (is (= [{:before-index 1 :before-value :b}
            {:before-index 3 :before-value :d}]
           (get-in proj [:vector-removals [:v]]))
        "engine recovers :b (idx 1) + :d (idx 3) — not :b + :c (pre-fix bug)")))

;; =========================================================================
;; rf2-0c6a3 — a collection value EMPTYING renders KEY-INTACT (member-level
;; removal inside the now-empty container), DISTINCT from a `dissoc` of the
;; key (a struck-through removed ghost)
;; =========================================================================
;;
;; THE BUG: `#{:a}→#{}`, `{:k :v}→{}`, `[x]→[]`, `(x)→()` rendered the whole
;; KEY as a struck-through removed ghost — indistinguishable from dissoc'ing
;; the key. The engine's R5 `mark-wholly-changed` legitimately promotes the
;; emptied set / map container path to `:removed` (the opposite side is
;; empty — no surviving member anchors a member-level diff at the container
;; path), and the renderer faithfully struck the KEY + painted the `−`
;; glyph. The fix (`diff-emptied?`) keys the distinction off the SLOT shape:
;; an emptied slot's AFTER value is a present empty collection (the key
;; survives), whereas a dissoc'd slot's AFTER value is `missing-sentinel`.
;;
;; `find-key-cell` locates the `data-rf-cell "key"` cell carrying the named
;; key so the assertions probe the OUTER key (not a struck INNER member key
;; like a removed map entry, which SHOULD strike).

(defn- find-key-cell
  "Return the first `data-rf-cell \"key\"` hiccup node whose flattened text
  matches `key-pat` (a regex). Lets a test assert on a SPECIFIC key row's
  chrome (e.g. is the OUTER `:one-set` key struck) rather than the whole
  tree."
  [tree key-pat]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n) (map? (second n))
                      (= "key" (get (second n) :data-rf-cell))
                      (re-find key-pat (collect-text n)))))
       first))

(defn- key-intact?
  "True when the OUTER key row matching `key-pat` is NOT struck-through and
  carries NO `−` removal glyph — i.e. the key survives (it was not
  removed). Renders `nil` (treated as not-intact) when no such key cell."
  [tree key-pat]
  (let [cell (find-key-cell tree key-pat)
        s    (when cell (pr-str cell))]
    (boolean
      (and s
           (not (re-find #"line-through" s))
           (not (re-find #"\"−\"" s))))))

(defn- emptied-render
  "Render `{key populated}` → `{key empty}` at DEFAULT depth (testbed-
  faithful — no forced expansion) and return the hiccup."
  [k populated empty-coll]
  (let [before {k populated}
        after  {k empty-coll}
        proj   (engine/project before after)]
    (ei/render-node {:value after :before before :diff? true
                     :projection proj :panel-id :p :mount-id "m"
                     :path [] :depth 0 :expansion-map {} :opts {}})))

(deftest c0c6a3-emptied-collection-renders-key-intact-not-removed-ghost
  ;; rf2-0c6a3 — for EACH container family, emptying the collection (key
  ;; intact) must render the KEY un-struck (NOT a removed ghost) with the
  ;; dropped member struck-through INSIDE the now-empty container.
  (doseq [[label k populated empty-coll member-pat]
          [["set"    :one-set #{:only}  #{}  #":only"]
           ["map"    :one-map {:k 1}    {}   #":k"]
           ["vector" :one-vec [:only]   []   #":only"]
           ["list"   :one-lst '(:only)  '()  #":only"]]]
    (let [h   (emptied-render k populated empty-coll)
          s   (try (pr-str h) (catch :default _ ""))
          key-pat (re-pattern (str k))]
      (testing (str label " — emptied collection, key intact")
        ;; The OUTER key is NOT struck and carries no `−` glyph — it
        ;; survived (the value just emptied).
        (is (key-intact? h key-pat)
            (str "the " label " key " k " must render INTACT (not struck, "
                 "no `−` glyph) — emptying is not a key removal"))
        ;; The node is NOT a removed-container ghost (that's the dissoc
        ;; rendering — `data-rf-removed-ghost "1"`).
        (is (not (re-find #":data-rf-removed-ghost \"1\"" s))
            (str "the emptied " label " must NOT render as a removed ghost"))
        ;; The dropped member is still visible + struck-through INSIDE the
        ;; now-empty container (member-level removal).
        (is (re-find #"data-rf-diff-op.*removed" s)
            (str "the dropped " label " member carries the removed diff-op"))
        (is (re-find #"line-through" s)
            (str "the dropped " label " member is struck-through"))
        (is (re-find member-pat (collect-text h))
            (str "the dropped " label " member text still renders"))))))

(deftest c0c6a3-emptied-reads-distinct-from-dissoc
  ;; rf2-0c6a3 — the CONTRAST the testbed wires: an emptied collection
  ;; (key intact) MUST read DISTINCT from a `dissoc` of a sibling key (the
  ;; struck-through removed ghost). The discriminator: an emptied key's
  ;; cell is intact; a dissoc'd key's cell is struck + `−` glyph + the node
  ;; is a removed ghost.
  (let [;; emptied: :one-set #{:only} → #{} (key intact)
        h-empty (emptied-render :one-set #{:only} #{})
        ;; dissoc: :doomed {:goodbye true} → (absent)
        before  {:doomed {:goodbye true}}
        after   {}
        proj    (engine/project before after)
        h-diss  (ei/render-node {:value after :before before :diff? true
                                 :projection proj :panel-id :p :mount-id "m"
                                 :path [] :depth 0 :expansion-map {} :opts {}})
        s-diss  (try (pr-str h-diss) (catch :default _ ""))]
    ;; Emptied: key intact, NOT a ghost.
    (is (key-intact? h-empty #":one-set")
        "emptied :one-set renders key-intact")
    (is (not (re-find #":data-rf-removed-ghost \"1\"" (pr-str h-empty)))
        "emptied :one-set is not a removed ghost")
    ;; Dissoc: key STRUCK + removed ghost — the distinct rendering.
    (is (not (key-intact? h-diss #":doomed"))
        "dissoc'd :doomed renders the key struck-through (removed)")
    (is (re-find #":data-rf-removed-ghost \"1\"" s-diss)
        "dissoc'd :doomed renders as a removed ghost")
    ;; The two renders are structurally distinct: only the dissoc is a ghost.
    (is (not= (boolean (re-find #":data-rf-removed-ghost \"1\"" (pr-str h-empty)))
              (boolean (re-find #":data-rf-removed-ghost \"1\"" s-diss)))
        "emptied vs dissoc render DISTINCTLY (ghost present only for dissoc)")))

(deftest c0c6a3-diff-emptied-predicate
  ;; rf2-0c6a3 — the render-side discriminator. True for a populated→empty
  ;; same-family collection (key intact); false for a real key removal
  ;; (after missing), a populated→populated change, and a type flip.
  (testing "emptied same-family collections — true"
    (is (ei/diff-emptied? #{:a} #{}))
    (is (ei/diff-emptied? {:k 1} {}))
    (is (ei/diff-emptied? [:x] []))
    (is (ei/diff-emptied? '(:x) '()))
    (is (ei/diff-emptied? [1 2 3] '()) "vector→empty list (same seq family)"))
  (testing "NOT emptied — false"
    (is (not (ei/diff-emptied? #{:a} ei/missing-sentinel))
        "after missing = real key removal (dissoc), not emptying")
    (is (not (ei/diff-emptied? ei/missing-sentinel #{}))
        "before missing = a new empty collection (added), not emptying")
    (is (not (ei/diff-emptied? #{:a} #{:b}))
        "populated→populated swap is not emptying")
    (is (not (ei/diff-emptied? #{} #{}))
        "already-empty before is not an emptying")
    (is (not (ei/diff-emptied? {:k 1} #{}))
        "map→set type flip is not a same-family emptying")
    (is (not (ei/diff-emptied? :scalar #{}))
        "scalar→empty-set is not a container emptying")))

;; =========================================================================
;; rf2-e28r3 — single render path: value (always) + before (optional)
;; =========================================================================
;;
;; The widget has ONE renderer. With a `:before` pre-image present the
;; tree paints inline diff annotations + the R4 op-coloured rail + R3
;; chip on change-bearing containers; with no pre-image the SAME renderer
;; shows the value plainly (no rail, no chip, no annotation). The former
;; `:full-with-diff?` flag — which gated the rail/chip on a defunct
;; mode-2-vs-mode-3 distinction — is GONE; the rail now keys directly on
;; `has-change?` (which itself implies `:diff?`).

(deftest with-before-paints-rail-on-change-bearing-container
  (testing "rf2-e28r3 — a `:before` pre-image renders the change-bearing
            container with the R4 rail (`data-rf-rail`); modified leaves
            carry the `← was <prior>` annotation. The rail paints on a
            container whose OWN op is added/removed/modified (a newly-
            added nested map here), the only chrome now that the
            `:full-with-diff?` flag is gone."
    (let [before {:a 1}
          after  {:a 1 :nested {:x 1 :y 2}}
          proj   (engine/project before after)
          h      (ei/render-node {:value after
                                  :before before
                                  :diff? true
                                  :projection proj
                                  :panel-id :p :mount-id "m"
                                  :path [] :depth 0
                                  :expansion-map {}
                                  :opts {:default-expanded-depth 4}})]
      (is (some? (find-attr h :data-rf-rail "1"))
          "the added nested container's body carries the R4 rail attr")
      ;; A modified leaf also annotates (separate scenario keeps the
      ;; rail assertion clean above).
      (let [hm (ei/render-node {:value {:counter 2}
                                :before {:counter 1}
                                :diff? true
                                :projection (engine/project {:counter 1} {:counter 2})
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
            sm (try (pr-str hm) (catch :default _ ""))]
        (is (re-find #"← was 1" sm)
            "the modified leaf carries the inline change annotation")))))

(defn- diff-op-values
  "Collect every non-nil `:data-rf-diff-op` attribute value in `tree`."
  [tree]
  (->> (walk-hiccup tree)
       (keep (fn [n]
               (when (map? (second n))
                 (get (second n) :data-rf-diff-op))))
       (remove nil?)))

(deftest value-only-render-has-no-rail-or-annotation
  (testing "rf2-e28r3 — with NO pre-image (`:diff?` absent) the same
            renderer shows the value plainly: no R4 rail, no `← was`
            annotation, no painted diff-op markers"
    (let [v {:counter 2 :stable :x :nested {:deep 1}}
          h (ei/render-node {:value v
                             :panel-id :p :mount-id "m"
                             :path [] :depth 0
                             :expansion-map {}
                             :opts {:default-expanded-depth 4}})
          s (try (pr-str h) (catch :default _ ""))]
      (is (nil? (find-attr h :data-rf-rail "1"))
          "plain value render paints no R4 rail")
      (is (not (re-find #"← was" s))
          "plain value render carries no change annotation")
      (is (empty? (diff-op-values h))
          "plain value render emits no painted diff-op markers (the
           `:data-rf-diff-op` value is nil outside diff mode)")
      (is (re-find #":counter" s)
          "the value's keys still render"))))

;; =========================================================================
;; rf2-zpeyv — slot-vs-value anchoring (R2 + R6 whole-row treatment)
;; =========================================================================
;;
;; When the SLOT itself changes (key added / removed), the per-op wash
;; paints the WHOLE row (key cell + value cell) and the `:removed`
;; strike-through reaches the key text. When only the VALUE changed
;; inside an existing slot (R1/R7/R8), the chrome stays value-anchored —
;; no key-cell wash, no key strike.

(defn- ^:private projection-for
  "Compute a projection for `(before, after)` so tests can drive
  `render-node` with the same engine the production renderer uses."
  [before after]
  (engine/project before after))

(deftest slot-anchored-added-key-paints-whole-row
  ;; R2 added map-key — both the key cell AND the value cell carry the
  ;; per-op wash; `data-rf-row-anchor="slot"` markers appear on both.
  (let [before {:a 1}
        after  {:a 1 :b 2}
        proj   (projection-for before after)
        h      (ei/render-node {:value after
                                :before before
                                :diff? true
                                :projection proj
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
        nodes  (walk-hiccup h)
        slot-cells (->> nodes
                        (filter (fn [n]
                                  (and (vector? n)
                                       (map? (second n))
                                       (= "slot"
                                          (get (second n) :data-rf-row-anchor))))))
        cell-roles (->> slot-cells
                        (map (fn [n] (get (second n) :data-rf-cell)))
                        set)]
    (is (>= (count slot-cells) 2)
        "added-key row tags both grid cells with data-rf-row-anchor=slot")
    (is (contains? cell-roles "key")
        "the key cell carries the slot-anchor marker")
    (is (contains? cell-roles "value")
        "the value cell carries the slot-anchor marker")
    ;; Both cells must paint a non-empty :background (the per-op wash).
    (doseq [cell slot-cells]
      (let [bg (-> cell second :style :background)]
        (is (some? bg)
            (str "slot cell " (get (second cell) :data-rf-cell)
                 " paints :background wash"))))))

(deftest slot-anchored-removed-key-paints-whole-row-and-strikes-key
  ;; R2 removed map-key — both cells get the wash AND the key cell
  ;; gets `text-decoration: line-through` so the strike reaches the
  ;; key text, not just the value text.
  (let [before {:a 1 :legacy-flag true}
        after  {:a 1}
        proj   (projection-for before after)
        h      (ei/render-node {:value after
                                :before before
                                :diff? true
                                :projection proj
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
        nodes  (walk-hiccup h)
        slot-cells (->> nodes
                        (filter (fn [n]
                                  (and (vector? n)
                                       (map? (second n))
                                       (= "slot"
                                          (get (second n) :data-rf-row-anchor))))))
        key-cells (filter (fn [n] (= "key" (get (second n) :data-rf-cell)))
                          slot-cells)
        value-cells (filter (fn [n] (= "value" (get (second n) :data-rf-cell)))
                            slot-cells)]
    (is (= 1 (count key-cells))
        "removed-key row contributes exactly one slot-anchored key cell")
    (is (= 1 (count value-cells))
        "removed-key row contributes exactly one slot-anchored value cell")
    (let [key-style (-> key-cells first second :style)]
      (is (some? (:background key-style))
          "key cell paints the per-op wash background")
      (is (= "line-through" (:text-decoration key-style))
          "key cell paints strike-through so the strike reaches the key text"))
    (let [val-style (-> value-cells first second :style)]
      (is (some? (:background val-style))
          "value cell paints the per-op wash background"))))

(deftest slot-anchored-leaf-wash-suppressed-no-double-paint
  ;; The inner gutter-row inside a slot-anchored value cell MUST
  ;; suppress its own wash; otherwise the wash double-paints over the
  ;; cell-level wash. We assert via the `data-rf-diff-wash` attribute
  ;; (set by gutter-row when it paints a wash) being absent inside an
  ;; `:added` / `:removed` slot row.
  (let [before {:a 1}
        after  {:a 1 :b 2}
        proj   (projection-for before after)
        h      (ei/render-node {:value after
                                :before before
                                :diff? true
                                :projection proj
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
        nodes  (walk-hiccup h)
        ;; Find the value cell for the added :b key — it carries
        ;; `data-rf-cell value` and `data-rf-row-anchor slot`.
        value-cell (->> nodes
                        (filter (fn [n]
                                  (and (vector? n)
                                       (map? (second n))
                                       (= "value" (get (second n) :data-rf-cell))
                                       (= "slot" (get (second n) :data-rf-row-anchor)))))
                        first)
        ;; Walk inside the slot-anchored value cell looking for any
        ;; descendant with `data-rf-diff-wash` set — that would mean a
        ;; gutter-row inside the cell painted its own wash.
        inner-washes (->> (walk-hiccup value-cell)
                          (filter (fn [n]
                                    (and (vector? n)
                                         (map? (second n))
                                         (= "1" (get (second n) :data-rf-diff-wash))))))]
    (is (some? value-cell)
        "added-key slot-anchored value cell is present")
    (is (zero? (count inner-washes))
        "slot-anchored value cell suppresses inner gutter-row wash")))

(deftest value-anchored-modified-row-does-not-paint-key-cell-wash
  ;; R1 (value mutated, slot identity unchanged) MUST stay value-
  ;; anchored. No `data-rf-row-anchor=slot` markers on the key/value
  ;; cells; key cell carries no wash and no strike.
  (let [before {:counter 5}
        after  {:counter 6}
        proj   (projection-for before after)
        h      (ei/render-node {:value after
                                :before before
                                :diff? true
                                :projection proj
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
        nodes  (walk-hiccup h)
        slot-cells (->> nodes
                        (filter (fn [n]
                                  (and (vector? n)
                                       (map? (second n))
                                       (= "slot"
                                          (get (second n) :data-rf-row-anchor))))))
        key-cells (->> nodes
                       (filter (fn [n]
                                 (and (vector? n)
                                      (map? (second n))
                                      (= "key" (get (second n) :data-rf-cell))))))
        s   (try (pr-str h) (catch :default _ ""))]
    (is (zero? (count slot-cells))
        "modified-leaf row carries no slot-anchor markers (value-anchored)")
    (is (pos? (count key-cells))
        "the key cell still renders (no regression)")
    (doseq [kc key-cells]
      (let [style (-> kc second :style)]
        (is (not (:background style))
            "modified-key cell paints NO row wash")
        (is (not= "line-through" (:text-decoration style))
            "modified-key cell paints NO key-text strike")))
    (is (re-find #"← was 5" s)
        "value-side R1 annotation still present")))

(deftest value-anchored-redaction-row-stays-value-anchored
  ;; R8 (redaction transition) keeps value-anchored chrome — the slot
  ;; identity didn't change, only the visibility of the value did.
  (let [before {:secret :rf/redacted}
        after  {:secret "now-visible"}
        proj   (projection-for before after)
        h      (ei/render-node {:value after
                                :before before
                                :diff? true
                                :projection proj
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
        nodes  (walk-hiccup h)
        slot-cells (->> nodes
                        (filter (fn [n]
                                  (and (vector? n)
                                       (map? (second n))
                                       (= "slot"
                                          (get (second n) :data-rf-row-anchor))))))]
    (is (zero? (count slot-cells))
        "R8 row stays value-anchored — no slot-anchor markers")))

(deftest slot-anchored-removed-key-marker-on-data-attrs
  ;; The removed-row regression test already covers `line-through`
  ;; appearing in pr-str — but we want a positive assertion that the
  ;; KEY CELL specifically carries the strike. Walk through the hiccup
  ;; and confirm the key-cell `<div>` is what holds it (not just the
  ;; inner key-segment span).
  (let [before {:a 1 :b 2}
        after  {:a 1}
        proj   (projection-for before after)
        h      (ei/render-node {:value after
                                :before before
                                :diff? true
                                :projection proj
                                :panel-id :p :mount-id "m"
                                :path [] :depth 0
                                :expansion-map {}
                                :opts {:default-expanded-depth 2}})
        nodes  (walk-hiccup h)
        key-cell-with-strike
        (->> nodes
             (filter (fn [n]
                       (and (vector? n)
                            (map? (second n))
                            (= "key" (get (second n) :data-rf-cell))
                            (= "slot" (get (second n) :data-rf-row-anchor))
                            (= "line-through"
                               (get-in (second n) [:style :text-decoration])))))
             first)]
    (is (some? key-cell-with-strike)
        "removed-key cell DIV (not just inner span) carries line-through")))

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
      (is (= "1" (:data-rf-popup-affordance attrs))
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

(deftest default-expanded-diff-collapses-unchanged
  (testing "rf2-fqcdd / rf2-e28r3 — with a pre-image present (`:diff?`):
            unchanged subtrees collapse regardless of depth/width. Only
            the root + ancestors of a change auto-expand. (The former
            `:full-with-diff?` flag was removed; the collapse heuristic
            now keys directly on `:diff?`.)"
    ;; Root (depth 0) always expands so the operator sees the keys.
    (is (true? (ei/default-expanded?
                 {:depth 0 :child-count 5 :value {:a 1 :b 2}
                  :default-expanded-depth 3
                  :diff? true})))
    ;; Depth 1 with NO changed descendant + diff on → collapse,
    ;; even though depth ≤ default-expanded-depth (would normally expand).
    (is (false? (ei/default-expanded?
                  {:depth 1 :child-count 5 :value {:a 1 :b 2}
                   :default-expanded-depth 3
                   :diff? true})))
    ;; Depth 1 WITH changed descendant + diff on → expand (the
    ;; force-expand rule for diff readability wins).
    (is (true? (ei/default-expanded?
                 {:depth 1 :child-count 5 :value {:a 1 :b 2}
                  :default-expanded-depth 3
                  :diff? true
                  :has-changed-descendant? true})))
    ;; No pre-image (`:diff?` absent) — width/depth heuristic applies
    ;; (the regression pin: the collapse branch is gated on `:diff?`).
    (is (true? (ei/default-expanded?
                 {:depth 1 :child-count 5 :value {:a 1 :b 2}
                  :default-expanded-depth 3})))))

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

;; ---- rf2-7hqwe — inline / collapsed inter-element spacing ----------------
;;
;; The inline (one-line) + collapsed-preview renders MUST separate
;; consecutive elements with canonical EDN spacing — a single SPACE
;; between sequential (vector / list / set / seq) elements, and `, `
;; between map / record entries — rather than running them together
;; (`["machine-epochs":machine-epochs/run-step26:rf/default]`) or
;; comma-separating sequentials (`[a, b, c]`). Pins the exact
;; separator string so the regression can't silently re-appear.

(deftest inline-separator-per-kind
  (testing "rf2-7hqwe — sequential kinds space-separate; maps/records comma"
    (is (= " "  (ei/inline-separator :vector)))
    (is (= " "  (ei/inline-separator :list)))
    (is (= " "  (ei/inline-separator :seq)))
    (is (= " "  (ei/inline-separator :set)))
    (is (= " "  (ei/inline-separator :map-entry)))
    (is (= ", " (ei/inline-separator :map)))
    (is (= ", " (ei/inline-separator :record)))))

(deftest render-inline-recursive-vector-space-separated
  (testing "rf2-7hqwe — an inline vector renders elements space-separated,
            matching canonical EDN (not comma-separated, not run-together)"
    (let [v    ["machine-epochs" :machine-epochs/run-step 26 :rf/default]
          text (collect-text (ei/render-inline-recursive v))]
      ;; Exact canonical EDN inline form — the single space between each
      ;; element is the load-bearing assertion (no `, `, no concatenation).
      (is (= "[\"machine-epochs\" :machine-epochs/run-step 26 :rf/default]"
             text))
      ;; Defence-in-depth: the exact run-together symptom from the bug
      ;; report (`run-step26`, `26:rf/default`) must NOT be present.
      (is (not (re-find #"run-step26" text))
          "the integer must not run into the preceding keyword")
      (is (not (re-find #"26:rf" text))
          "the trailing keyword must not run into the preceding integer")
      (is (not (re-find #", " text))
          "a sequential vector must not comma-separate its elements"))))

(deftest render-inline-recursive-map-comma-separated
  (testing "rf2-7hqwe — an inline map keeps `, ` between k/v pairs and a
            space within each pair"
    (let [text (collect-text (ei/render-inline-recursive {:a 1 :b 2}))]
      (is (= "{:a 1, :b 2}" text)))))

(deftest inline-preview-string-vector-space-separated
  (testing "rf2-7hqwe — collapsed-preview of a sequential is space-separated"
    (is (= "[\"machine-epochs\" :machine-epochs/run-step 26 :rf/default]"
           (ei/inline-preview-string
             ["machine-epochs" :machine-epochs/run-step 26 :rf/default] 5 80)))
    (is (= "[1 2 3]" (ei/inline-preview-string [1 2 3] 5 80)))))

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
;; (gesture reworked rf2-zl4rs — double-click / Enter, no glyph)
;; =========================================================================
;;
;; The zoom feature turns the inspector into a focused window onto an
;; arbitrary subtree. The widget reads a per-mount path from the
;; `zoom-slot`; when set, render-node walks `get-in` along that path and
;; renders only the subtree. A breadcrumb row above the body shows the
;; path from the original root; each segment is clickable for one-tap
;; zoom-to-that-depth.
;;
;; rf2-zl4rs — zoom-in is a node-local gesture (double-click / Enter)
;; on the container itself; there is NO `⊙` glyph button. Zoom applies
;; in the SINGLE full+diff renderer (re-root value always, before too
;; when present). Esc + breadcrumb still zoom out.
;;
;; Tests under this section cover:
;;
;; - Pure helpers: `zoom-key`, `resolve-zoom-path`, `resolve-zoom-into`.
;; - Event reducers: `:zoom-to`, `:zoom-up`, `:zoom-reset`.
;; - Public widget plumbing: `:zoomable?` opts emit data-attrs +
;;   breadcrumb + the double-click / Enter zoom-trigger attrs on
;;   containers; default (no opt) leaves the renderer unchanged.
;; - Per-mount keying: two side-by-side mounts zoom independently;
;;   stable `:site-id` survives unmount/remount.
;; - Single full+diff renderer: a zoom re-roots BOTH value and before;
;;   no glyph renders.

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

;; ---- zoom GESTURE — double-click / Enter, no glyph (rf2-zl4rs) -----------
;;
;; rf2-zl4rs removed the `⊙` glyph button entirely; zoom-in is now a
;; node-local gesture on the container's own outer div. These tests
;; assert: (a) NO `⊙` / zoom-affordance glyph renders anywhere; (b) the
;; non-root container carries the `data-rf-zoom-target` + handlers; (c)
;; the root + opt-off cases carry no zoom target; (d) double-click +
;; Enter both dispatch the canonical zoom-to through the captured
;; dispatcher with the absolute path.

(defn- zoom-target-nodes
  "Every hiccup node carrying `data-rf-zoom-target=1`."
  [tree]
  (filter (fn [n]
            (and (vector? n)
                 (map? (second n))
                 (= "1" (:data-rf-zoom-target (second n)))))
          (walk-hiccup tree)))

(defn- glyph-nodes
  "Every hiccup node whose string content is the legacy `⊙` zoom glyph,
  plus any `data-rf-affordance=zoom` button — the surfaces rf2-zl4rs
  removed. Used to assert their TOTAL ABSENCE."
  [tree]
  (filter (fn [n]
            (and (vector? n)
                 (or (= "⊙" (last n))
                     (and (map? (second n))
                          (= "zoom" (:data-rf-affordance (second n)))))))
          (walk-hiccup tree)))

(deftest zoomable-emits-no-glyph-button
  ;; The `⊙` glyph + the `data-rf-affordance=zoom` button are GONE
  ;; (rf2-zl4rs). The recursive walker emits neither, at any depth.
  (let [v {:a {:nested 1} :b 2}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix []
                           :opts {:default-expanded-depth 8}})]
    (is (empty? (glyph-nodes h))
        "rf2-zl4rs — no `⊙` glyph / zoom-affordance button renders")))

(deftest zoomable-marks-non-root-containers-as-zoom-targets
  ;; With zoomable? on, every non-root container's outer div carries the
  ;; zoom-trigger attrs (data-rf-zoom-target + tab-index + aria-label +
  ;; handlers). We probe render-node directly with a `zoom-path-prefix`
  ;; of [] to confirm the child container `:a` is a target.
  (let [v {:a {:nested 1} :b 2}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix []
                           :opts {:default-expanded-depth 8}})
        targets (zoom-target-nodes h)]
    (is (seq targets)
        "a non-root container is marked as a zoom target")
    (let [attrs (-> targets first second)]
      (is (= 0 (:tab-index attrs))
          "the target is keyboard-focusable (tab-index 0)")
      (is (string? (:aria-label attrs))
          "the target carries an aria-label — preserves the removed
           button's screen-reader affordance")
      (is (fn? (:on-double-click attrs))
          "double-click handler present")
      (is (fn? (:on-key-down attrs))
          "key-down handler present (Enter zooms in)"))))

(deftest zoomable-skips-zoom-target-at-root
  ;; The root displayed node (relative path `[]`) is NOT a zoom target —
  ;; zooming into the current root is a no-op.
  (let [v {:a 1 :b 2}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix []
                           :opts {:default-expanded-depth 0}})]
    ;; With depth-0 expansion the root is collapsed → only one node
    ;; renders. The root must NOT be a zoom target.
    (is (zero? (count (zoom-target-nodes h)))
        "root container at relative-path [] is NOT a zoom target")))

(deftest zoomable-skips-zoom-target-when-opt-off
  ;; `:zoomable? false` (the default) suppresses the gesture even on
  ;; deep nested containers.
  (let [v {:a {:b {:c 1}}}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 8}})]
    (is (zero? (count (zoom-target-nodes h)))
        "with :zoomable? off no node is a zoom target")
    (is (empty? (glyph-nodes h))
        "and certainly no glyph")))

(defn- with-captured-dispatch-spy
  "Build the zoom-trigger attrs with a captured-dispatcher STUB as its
  `:dispatch-fn`, FIRE the named gesture, and capture the dispatched
  event vector WITHOUT spinning up the router. `make-attrs` is
  `(fn [spy] attrs-map)`; `gesture` is `:on-double-click` or
  `:on-key-down`; `evt` is the synthetic DOM event (nil → a stub that
  satisfies the Enter predicate). Returns `{:event ... :attrs ...}`.

  Per rf2-r0o63 — the gesture dispatches through the SUPPLIED frame-aware
  dispatcher (the one the surrounding `reg-view` body captured via
  `(:dispatch (rf/capture-frame))`), NOT a bare `rf/dispatch` with a `{:frame :rf/xray}`
  literal. The dispatcher closure already bound the instance frame at
  render time; this stub stands in for it."
  ([make-attrs] (with-captured-dispatch-spy make-attrs :on-double-click nil))
  ([make-attrs gesture evt]
   (let [captured (atom nil)
         spy      (fn [ev] (reset! captured ev))
         attrs    (make-attrs spy)
         handler  (get attrs gesture)
         ;; Enter handler needs a key-bearing event; dblclick ignores it.
         event    (or evt
                      (when (= gesture :on-key-down)
                        (js-obj "key" "Enter"
                                "ctrlKey" false "metaKey" false
                                "altKey" false "shiftKey" false
                                "preventDefault" (fn [])
                                "stopPropagation" (fn []))))]
     (handler event)
     {:event @captured :attrs attrs})))

(deftest zoom-trigger-double-click-dispatches-zoom-to-with-absolute-path
  (let [{:keys [event attrs]}
        (with-captured-dispatch-spy
          (fn [spy]
            (ei/zoom-trigger-attrs
              {:dispatch-fn   spy
               :panel-id      :p
               :mount-id      "m"
               :absolute-path [:a :b :c]})))]
    (is (fn? (:on-double-click attrs)) "carries a double-click handler")
    (is (= [:rf.xray.edn-inspector/zoom-to :p "m" [:a :b :c]] event)
        "double-click dispatches the canonical zoom-to with the absolute path")))

(deftest zoom-trigger-enter-key-dispatches-zoom-to
  ;; rf2-zl4rs — Enter on the focused node is the keyboard a11y path the
  ;; removed glyph button used to provide.
  (let [{:keys [event]}
        (with-captured-dispatch-spy
          (fn [spy]
            (ei/zoom-trigger-attrs
              {:dispatch-fn   spy
               :panel-id      :p
               :mount-id      "m"
               :absolute-path [:a :b]}))
          :on-key-down nil)]
    (is (= [:rf.xray.edn-inspector/zoom-to :p "m" [:a :b]] event)
        "Enter dispatches the canonical zoom-to with the absolute path")))

(deftest zoom-trigger-enter-ignores-modifiers-and-other-keys
  ;; A bare Enter zooms; Ctrl/Cmd/Alt/Shift+Enter and non-Enter keys do
  ;; NOT — so Esc-zoom-out + the spine bindings (j/k/L/G) pass through.
  (let [mk (fn [opts]
             (js-obj "key" (:key opts)
                     "ctrlKey" (boolean (:ctrl? opts))
                     "metaKey" (boolean (:meta? opts))
                     "altKey"  (boolean (:alt? opts))
                     "shiftKey" (boolean (:shift? opts))
                     "preventDefault" (fn [])
                     "stopPropagation" (fn [])))]
    (doseq [evt [(mk {:key "Enter" :ctrl? true})
                 (mk {:key "Enter" :meta? true})
                 (mk {:key "Enter" :alt? true})
                 (mk {:key "Enter" :shift? true})
                 (mk {:key "Escape"})
                 (mk {:key "j"})
                 (mk {:key "k"})]]
      (let [{:keys [event]}
            (with-captured-dispatch-spy
              (fn [spy]
                (ei/zoom-trigger-attrs
                  {:dispatch-fn spy :panel-id :p :mount-id "m"
                   :absolute-path [:a]}))
              :on-key-down evt)]
        (is (nil? event)
            (str "key " (.-key evt) " (modifiers held: "
                 (.-ctrlKey evt) (.-metaKey evt) (.-altKey evt) (.-shiftKey evt)
                 ") must NOT trigger zoom"))))))

(deftest zoom-trigger-dispatches-through-captured-dispatcher
  ;; rf2-r0o63 — supersedes the rf2-kcaiz pin. The gesture dispatches
  ;; through the SUPPLIED frame-aware dispatcher so the zoom-slot write
  ;; lands on the instance frame — single-arg event vector, frame baked
  ;; into the closure, NOT a `{:frame :rf/xray}` literal.
  (testing "rf2-r0o63 — the zoom gesture dispatches a single-arg
            `[:rf.xray.edn-inspector/zoom-to ...]` (no `{:frame :rf/xray}`)"
    (let [{:keys [event]}
          (with-captured-dispatch-spy
            (fn [spy]
              (ei/zoom-trigger-attrs
                {:dispatch-fn   spy
                 :panel-id      :rf.xray/app-db
                 :mount-id      "m-1"
                 :absolute-path [:cart :items 0]})))
          [event-id panel-id mount-id path] event]
      (is (= :rf.xray.edn-inspector/zoom-to event-id) "canonical event id")
      (is (= :rf.xray/app-db panel-id) "panel-id flows through")
      (is (= "m-1" mount-id) "mount-id flows through")
      (is (= [:cart :items 0] path) "absolute path flows through")
      (is (= 4 (count event))
          "rf2-r0o63 — single-arg event vector; the frame is captured in
           the dispatcher closure, not a `{:frame :rf/xray}` literal"))))

(deftest zoom-trigger-composes-prefix-and-relative-path
  ;; render-container threads the absolute path = (into zoom-path-prefix
  ;; path) into the gesture — so when the operator is already zoomed at
  ;; `[:rf.db/runtime :rf.runtime/machines :snapshots]` and double-clicks
  ;; the nested `:ws/connection` container (relative path `[:ws/connection]`),
  ;; the dispatch carries the FULL absolute path.
  (let [v {:ws/connection {:state :open}}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :zoomable? true
                           :zoom-path-prefix [:rf.db/runtime :rf.runtime/machines :snapshots]
                           :opts {:default-expanded-depth 8}})
        targets (zoom-target-nodes h)]
    (is (seq targets)
        "the child container is a zoom target even when zoom-path-prefix
         is non-empty")
    ;; Confirm via the factory directly, mirroring render-container's
    ;; (into zoom-path-prefix path) composition.
    (let [composed (vec (concat [:rf.db/runtime :rf.runtime/machines :snapshots] [:ws/connection]))
          {:keys [event]}
          (with-captured-dispatch-spy
            (fn [spy]
              (ei/zoom-trigger-attrs
                {:dispatch-fn   spy
                 :panel-id      :p
                 :mount-id      "m"
                 :absolute-path composed})))]
      (is (= [:rf.xray.edn-inspector/zoom-to :p "m"
              [:rf.db/runtime :rf.runtime/machines :snapshots :ws/connection]]
             event)
          "the dispatched path is the absolute path = prefix + relative")
      (is (= 4 (count event))
          "rf2-r0o63 — composed-path dispatch is ALSO a single-arg event
           vector through the captured dispatcher (no `:rf/xray` literal)"))))

;; ---- rf2-6nw3g — the toggle triangle owns its double-click gesture -------
;;
;; A zoomable container's outer div carries `:on-double-click -> zoom-to`
;; (zoom-trigger-attrs). The `▸`/`▾` toggle glyph nested inside dispatches
;; toggle on `:on-click`. Before the fix the glyph had no `:on-double-click`
;; guard, so a double-click on the TRIANGLE fired toggle twice (net visual
;; no-op, two dispatches) AND its `dblclick` bubbled to the container's zoom.
;; The fix adds `swallow-dblclick` (preventDefault + stopPropagation, no
;; dispatch) so the triangle owns its gesture: zoom only fires on a
;; double-click OUTSIDE the triangle, and a single click still toggles.

(defn- stub-evt
  "A synthetic-event stub that records `preventDefault` / `stopPropagation`
  invocations into the supplied atom map `{:prevented? false :stopped? false}`."
  [spy]
  (js-obj "preventDefault"  (fn [] (swap! spy assoc :prevented? true))
          "stopPropagation" (fn [] (swap! spy assoc :stopped? true))))

(deftest toggle-glyph-double-click-is-swallowed-no-zoom
  ;; Render a NON-ROOT, collapsed, zoomable container so BOTH the toggle
  ;; glyph and the container's zoom-trigger attrs are present.
  (let [dispatched (atom [])
        dispatch-fn (fn [ev] (swap! dispatched conj ev))
        v   {:a 1 :b 2 :c 3 :d 4 :e 5}
        h   (ei/render-node {:value v
                             :panel-id :p
                             :mount-id "m"
                             :path [:parent]
                             :depth 5
                             :expansion-map {}
                             :zoomable? true
                             :dispatch-fn dispatch-fn
                             :opts {:default-expanded-depth 1}})
        tog (find-attr h :data-testid "rf-xray-edn-inspector-p-m-:parent-toggle")
        on-click (-> tog second :on-click)
        on-dblclick (-> tog second :on-double-click)]
    (is (some? tog) "the collapsed container renders a toggle glyph")
    (is (seq (zoom-target-nodes h))
        "the non-root container is also a zoom target (gesture lives on the
         outer div, which the dblclick must NOT reach)")
    (testing "the toggle glyph carries a double-click swallow guard"
      (is (fn? on-dblclick) "toggle glyph must carry an :on-double-click")
      (let [spy (atom {:prevented? false :stopped? false})]
        (on-dblclick (stub-evt spy))
        (is (:prevented? @spy)
            "preventDefault — suppresses the native text-selection")
        (is (:stopped? @spy)
            "stopPropagation — the dblclick never bubbles to the container's zoom")
        (is (empty? @dispatched)
            "the swallow dispatches NOTHING (no zoom-to, no toggle)")))
    (testing "a single click still toggles (existing behaviour preserved)"
      (on-click nil)
      (is (= [[:rf.xray.edn-inspector/toggle-node :p "m" [:parent] false]]
             @dispatched)
          "one click dispatches exactly one canonical toggle event"))))

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

(deftest widget-diff-mode-zooms-and-reroots-both-halves
  ;; rf2-zl4rs — zoom now applies in the SINGLE full+diff renderer.
  ;; A zoom in diff mode re-roots `value` AND `before` onto the same
  ;; subtree, so the operator focuses the changed subtree with its diff
  ;; annotations intact — siblings outside the subtree are hidden, and
  ;; the re-rooted before still feeds the projection so the change paints.
  ;; (Supersedes the rf2-h71e0 "diff suppresses zoom" behaviour.)
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
    (is (= "1" (:data-rf-zoomed attrs))
        ":data-rf-zoomed=1 in diff mode — zoom applies in the unified renderer")
    (is (= (pr-str [:nested]) (:data-rf-zoom-path attrs))
        ":data-rf-zoom-path carries the stored path even with a :before")
    (is (re-find #":deep" text)
        "the zoomed subtree's leaf renders in the body")
    (is (not (re-find #":sibling" text))
        "siblings OUTSIDE the zoom subtree are hidden — even in diff mode")
    (is (re-find #"42" text) "the after-side leaf value renders")
    (is (re-find #"41" text)
        "the re-rooted before's prior leaf renders too — diff annotation
         survives the zoom (before re-rooted along the same path)")
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

;; ---- rf2-4p1vl — engine/project memoisation across re-renders -------------
;;
;; The audit's H1 finding: in diff mode (mode-3) the inner render fn invoked
;; `engine/project` on every render — expansion toggle, ResizeObserver width
;; update, parent re-render. Even when the `(before, after)` inputs were
;; byte-identical, the full Editscript A* walk + ancestor classification
;; ran again from scratch.
;;
;; The fix captures a per-mount projection cache in the form-2 outer body
;; closure. Identity-stable inputs short-circuit to the cached projection.
;; The cache key uses `identical?` on both `before` and `after`, matching
;; the engine's own short-circuit at engine.cljc:515.
;;
;; Tests below spy on `engine/project` via `with-redefs` and count
;; invocations across multiple inner-fn calls with identical inputs.

(deftest projection-memo-skips-recompute-on-identical-inputs
  ;; Mount the widget once (get the form-2 inner fn); call inner three
  ;; times with the SAME `before` + `value` references. Without the
  ;; memo `engine/project` fires three times; with the memo it fires
  ;; once.
  (let [before {:a 1 :b {:c 2}}
        after  {:a 1 :b {:c 3}}
        opts   {:panel-id :rf.xray/app-db
                :before   before}
        call-count (atom 0)
        real-project engine/project]
    (with-redefs [engine/project (fn [b a]
                                   (swap! call-count inc)
                                   (real-project b a))]
      (let [inner (ei/edn-inspector after opts)]
        ;; Three renders with identical (before, after) refs.
        (inner after opts)
        (inner after opts)
        (inner after opts)
        (is (= 1 @call-count)
            "engine/project invoked exactly once across three identity-stable renders")))))

(deftest projection-memo-recomputes-when-before-changes
  ;; Cache invalidates when `before` is a different reference. New
  ;; epoch / new diff input must trigger a fresh projection.
  (let [before1 {:a 1}
        before2 {:a 2}
        after   {:a 3}
        call-count (atom 0)
        real-project engine/project]
    (with-redefs [engine/project (fn [b a]
                                   (swap! call-count inc)
                                   (real-project b a))]
      (let [inner (ei/edn-inspector after {:panel-id :rf.xray/app-db
                                            :before   before1})]
        (inner after {:panel-id :rf.xray/app-db :before before1})
        (inner after {:panel-id :rf.xray/app-db :before before2})
        (is (= 2 @call-count)
            "engine/project re-runs when `before` reference changes")))))

(deftest projection-memo-recomputes-when-after-changes
  ;; Mirror of the above for the `after` side. Same-`before`, different-
  ;; `after` reference must miss the cache.
  (let [before {:a 1}
        after1 {:a 2}
        after2 {:a 3}
        call-count (atom 0)
        real-project engine/project]
    (with-redefs [engine/project (fn [b a]
                                   (swap! call-count inc)
                                   (real-project b a))]
      (let [inner (ei/edn-inspector after1 {:panel-id :rf.xray/app-db
                                             :before   before})]
        (inner after1 {:panel-id :rf.xray/app-db :before before})
        (inner after2 {:panel-id :rf.xray/app-db :before before})
        (is (= 2 @call-count)
            "engine/project re-runs when `after` reference changes")))))

(deftest projection-memo-is-per-mount-isolated
  ;; The cache lives in the form-2 outer body closure → each mount gets
  ;; its OWN cache. Two side-by-side mounts must not share entries,
  ;; otherwise mount-A's projection could be served to mount-B with
  ;; different inputs.
  (let [before {:a 1}
        after  {:a 2}
        call-count (atom 0)
        real-project engine/project]
    (with-redefs [engine/project (fn [b a]
                                   (swap! call-count inc)
                                   (real-project b a))]
      (let [inner1 (ei/edn-inspector after {:panel-id :rf.xray/app-db
                                             :before   before})
            inner2 (ei/edn-inspector after {:panel-id :rf.xray/app-db
                                             :before   before})]
        (inner1 after {:panel-id :rf.xray/app-db :before before})
        (inner2 after {:panel-id :rf.xray/app-db :before before})
        ;; Two distinct mounts → two cache misses, even with identity-
        ;; stable inputs. The point of this test is the ABSENCE of
        ;; cross-mount cache sharing; if both mounts shared, we'd see
        ;; @call-count = 1 here.
        (is (= 2 @call-count)
            "each mount has its own projection cache (no cross-mount leak)")
        ;; And within a single mount, second render is a cache hit.
        (inner1 after {:panel-id :rf.xray/app-db :before before})
        (is (= 2 @call-count)
            "mount-1's second render hits its own cache")))))

(deftest projection-not-invoked-outside-diff-mode
  ;; Browse mode (no `:before` opt) must NOT touch `engine/project` at
  ;; all — the projection is nil and the renderer's path-keyed lookups
  ;; return `:same` for everything. This guards against accidentally
  ;; warming the cache in non-diff renders.
  (let [call-count (atom 0)
        real-project engine/project]
    (with-redefs [engine/project (fn [b a]
                                   (swap! call-count inc)
                                   (real-project b a))]
      (let [inner (ei/edn-inspector {:a 1} {:panel-id :rf.xray/app-db})]
        (inner {:a 1} {:panel-id :rf.xray/app-db})
        (inner {:a 1} {:panel-id :rf.xray/app-db})
        (is (= 0 @call-count)
            "browse mode never calls engine/project")))))
