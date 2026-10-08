(ns day8.re-frame2-xray.views.edn-inspector-cljs-test
  "Unit tests for the first-class edn-inspector widget.

  ## What's under test

  1. **Scalar rendering** — every leaf shape lands at the right
     theme-token colour.
  2. **Bracket styling** — distinct opener/closer per kind; map-entry
     vs 2-vector brackets differ in COLOUR (same chars).
  3. **Inline preview** — `▸ {:a 1, :b 2, …}` cases.
  4. **Click-to-toggle** — toggle event flips the per-path expansion;
     dispatch carries the canonical event shape; expanded state
     swaps the glyph `▸` → `▾` and renders the body.
  5. **Per-call-site isolation** — two `[edn-inspector]` mounts get
     independent `mount-id`s; toggling one path in mount-A leaves
     mount-B's same path untouched.
  6. **Sentinels** (`:rf/redacted`, `:rf.size/large-elided`, combined)
     render their first-class chip chrome.
  7. **Diff mode**, the bounded walks, the width-aware heuristic, zoom
     and the card / header chrome.

  Pure-data unit tests; no DOM mount, which is the default shape for
  Xray/Story tests."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.diff.engine :as engine]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens dark-palette light-palette]]))

;; A fresh re-frame runtime per test, with the widget's own `install!` run
;; on the just-reset registrar: the `:rf.xray.edn-inspector/*` subs and
;; events register from `install!`, never at ns-load, so requiring `ei`
;; registers nothing.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (ei/install!))}))

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

(defn- nodes-with-attr
  "Every node whose attribute-map key `k` equals `v`, in document order."
  [tree k v]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= v (get (second n) k)))))))

(defn- find-attr
  "Return the first node whose attribute-map key `k` equals `v`."
  [tree k v]
  (first (nodes-with-attr tree k v)))

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

;; ---- records -------------------------------------------------------------

(defrecord R [a])

(deftest records-render-with-their-tag
  ;; A record opens with the `#<ns>.R{` tag `pr-str` prints, on every path —
  ;; never a bare `#`, which would read as a set's `#{`.
  (let [r       (->R 1)
        pr      (pr-str r)
        opening (subs pr 0 (inc (str/index-of pr "{")))
        text    (fn [expansion-map]
                  (collect-text (ei/render-node {:value r
                                                 :panel-id :test
                                                 :mount-id "m1"
                                                 :path []
                                                 :depth 0
                                                 :expansion-map expansion-map
                                                 :opts {}})))]
    (is (str/includes? (text {}) opening) "the inline render")
    (is (str/includes? (text {(ei/expansion-key :test "m1" []) {:expanded? true}})
                       opening)
        "the expanded header")
    (is (str/starts-with? (ei/inline-preview-string r 3 80) opening)
        "the collapsed preview")
    (is (str/includes? (ei/inline-preview-string {:p r} 3 80)
                       (str opening "…1 keys}"))
        "and its one-level placeholder inside a parent's preview")))

;; ---- scalar rendering ----------------------------------------------------

(deftest scalar-leaves-paint-their-syntax-token
  ;; Each scalar kind paints through its own `:syntax-*` token and prints
  ;; its own text.
  (doseq [[v token text] [[:foo    :syntax-keyword ":foo"]
                          ["hello" :syntax-string  "\"hello\""]
                          [42      :syntax-number  "42"]
                          [true    :syntax-boolean "true"]
                          [nil     :syntax-nil     "nil"]
                          ['sym    :syntax-symbol  "sym"]]]
    (let [h (ei/render-scalar v)]
      (is (= [(get tokens token) text]
             [(-> h second :style :color) (collect-text h)])
          (str (pr-str v) " paints via " token " and prints as " text)))))

(deftest scalar-fn-renders-with-italic
  (let [h (ei/render-scalar (fn [x] x))]
    (is (= ["#fn" "italic"]
           [(collect-text h) (-> h second :style :font-style)]))))

;; ---- scalar hue-family contract ------------------------------------------
;;
;; The five scalar types (keyword / string / number / boolean / nil) span at
;; least four hue families in BOTH palettes, as editor syntax palettes do —
;; three of five in the blue family would make the inspector look
;; monochrome. "Hue family" is the dominant RGB channel of the hex, with a
;; tie tolerance for grey.

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

(deftest scalar-hue-families-stay-distinct-in-both-palettes
  ;; In each palette the five scalar tokens span ≥4 hue families, and at
  ;; most one sits in the blue family. This asserts the actual hex values.
  (let [scalar-keys [:syntax-keyword :syntax-string :syntax-number
                     :syntax-boolean :syntax-nil]
        families-of (fn [palette]
                      (set (map #(dominant-channel (get palette %)) scalar-keys)))]
    (doseq [[label palette] [[:dark dark-palette] [:light light-palette]]]
      (let [families (families-of palette)
            blues    (filter #(= :blue (dominant-channel (get palette %)))
                             scalar-keys)]
        (is (>= (count families) 4)
            (str label ": 5 scalar types must span ≥4 hue families; got " families))
        (is (<= (count blues) 1)
            (str label ": ≤1 scalar may be in the blue family; got " (vec blues)))))
    (is (contains? (families-of dark-palette) :grey)
        "nil reads as deliberately muted grey")))

;; ---- sentinel rendering --------------------------------------------------

(deftest sentinel-chips-render-their-chrome
  ;; spec/015's three sentinels render as first-class chips — testid, hue
  ;; and text. The large-elided body's `:bytes`, `:type` and `:hint` are
  ;; optional, and the chip renders without them.
  (doseq [[v testid colour text]
          [[:rf/redacted
            "rf-xray-edn-inspector-redacted" :magenta "●redacted"]
           [{:rf/redacted {:bytes 200}}
            "rf-xray-edn-inspector-redacted-size" :magenta "●redacted· 200 bytes"]
           [{:rf.size/large-elided {:path   [:blob]
                                    :bytes  5000
                                    :type   :string
                                    :reason :schema
                                    :hint   "Upload preview"
                                    :handle [:rf.elision/at [:blob]]}}
            "rf-xray-edn-inspector-large" :yellow "●large· 5000 bytes"]
           [{:rf.size/large-elided {:path   [:x]
                                    :handle [:rf.elision/at [:x]]}}
            "rf-xray-edn-inspector-large" :yellow "●large"]]]
    (let [h (ei/render-scalar v)]
      (is (= [testid (get tokens colour) text]
             [(-> h second :data-testid) (-> h second :style :color) (collect-text h)])
          (pr-str v)))))

;; ---- inline-preview-string -----------------------------------------------

(deftest inline-preview-string-fits-truncates-or-falls-back
  ;; Canonical EDN spacing (`, ` between map entries, a space between
  ;; sequential elements); past `max-elements` a trailing `…`; past
  ;; `max-chars` a one-element preview.
  (are [v max-chars preview] (= preview (ei/inline-preview-string v 3 max-chars))
    {:a 1 :b 2}           80 "{:a 1, :b 2}"
    [1 2 3]               80 "[1 2 3]"
    [1 2 3 4 5]           80 "[1 2 3 …]"
    #{:a}                 80 "#{:a}"
    {:a 1 :b 2 :c 3 :d 4} 12 "{:a 1, …}"))

;; ---- bracket styling -----------------------------------------------------

(deftest bracket-characters-per-kind
  (is (= [["{" "}"] ["[" "]"] ["(" ")"] ["#{" "}"] ["[" "]"]]
         (map (juxt :open :close)
              (map ei/delim [:map :vector :list :set :map-entry]))))
  (is (= [:accent :text-secondary]
         (map (comp :tone-key ei/delim) [:map-entry :vector]))
      "map-entry brackets share a vector's characters but read in `:accent`"))

;; ---- click-to-toggle -----------------------------------------------------

(deftest toggle-event-flips-expansion-state
  ;; The reducer inverts the stored override when there is one, and
  ;; otherwise the rendered state the click carries, so the first click
  ;; always flips what the user sees.
  (let [stored (fn [] (get-in @(rf/subscribe [ei/expansion-slot])
                              [(ei/expansion-key :test "m-1" [:a]) :expanded?]))
        click! (fn [rendered-expanded?]
                 (rf/dispatch-sync [:rf.xray.edn-inspector/toggle-node
                                    :test "m-1" [:a] rendered-expanded?]))]
    (click! false)
    (is (= true (stored)) "rendered collapsed, no override: the first click opens")
    (click! true)
    (is (= false (stored)) "the second click inverts the stored override")
    (click! true)
    (is (= true (stored))
        "a stale payload contradicting the stored override: the override wins and inverts")
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (click! true)
    (is (= false (stored)) "rendered expanded, no override: the first click collapses")
    (rf/dispatch-sync [:rf.xray.edn-inspector/reset-expansion])
    (is (nil? @(rf/subscribe [ei/expansion-slot])) "reset clears the slot")))

;; ---- render-node — container/scalar dispatch -----------------------------

(deftest render-node-empty-map-no-toggle
  (is (= "{}" (collect-text (ei/render-node {:value {}
                                             :panel-id :p
                                             :mount-id "m"
                                             :path []
                                             :depth 0
                                             :expansion-map {}
                                             :opts {}})))
      "an empty collection renders its bracket pair flat, with no toggle"))

(deftest render-node-small-map-renders-every-entry-inline
  (is (= "{:a 1, :b 2}"
         (collect-text (ei/render-node {:value {:a 1 :b 2}
                                        :panel-id :p
                                        :mount-id "m"
                                        :path []
                                        :depth 0
                                        :expansion-map {}
                                        :opts {:default-expanded-depth 2}})))))

(deftest render-node-deep-map-default-collapsed
  ;; Past `:default-expanded-depth` the tree collapses.
  (let [v {:level1 {:level2 {:level3 {:level4 {:level5 42}}}}}
        h (ei/render-node {:value v
                           :panel-id :p
                           :mount-id "m"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})
        text (collect-text h)]
    (is (not (re-find #":level5" text)))
    (is (re-find #":level1" text))))

(deftest render-node-closed-override-hides-the-subtree
  (let [v {:level1 {:level2 {:level3 {:deep 1}}}}
        k0 (ei/expansion-key :p "m" [:level1 :level2])
        h  (ei/render-node {:value v
                            :panel-id :p
                            :mount-id "m"
                            :path []
                            :depth 0
                            :expansion-map {k0 {:expanded? false}}
                            :opts {:default-expanded-depth 5}})]
    (is (not (re-find #":deep" (collect-text h)))
        "the node forced closed renders none of its children")))

(deftest container-testid-names-exactly-one-node
  ;; The container's testid is `[panel-id mount-id]`; a node's is
  ;; `[panel-id mount-id path]`, with the path separator unconditional, so
  ;; the root node at `[]` cannot compose the container's string.
  (let [outer (ei/edn-inspector {:a 1 :b 2} {:panel-id :p})
        tree  (outer {:a 1 :b 2} {:panel-id :p})
        hits  (nodes-with-attr tree :data-testid (:data-testid (second tree)))]
    (is (= 1 (count hits))
        (str "the container testid names exactly one node; got " (count hits)))
    (is (every? #(some? (:data-rf-mount-id (second %))) hits)
        "and that node is the container — the only one carrying data-rf-mount-id")))

;; ---- triangle hit-box ≥24×24 ---------------------------------------------

(defn- parse-px
  "Parse `'24px'` → 24. Returns nil for non-px strings."
  [s]
  (when (and (string? s) (re-find #"^\d+(\.\d+)?px$" s))
    (js/parseFloat s)))

(deftest triangle-style-gives-a-24px-hit-box
  ;; `min-width` / `min-height` only take effect on a non-inline box.
  (is (= "inline-flex" (:display ei/triangle-style)))
  (is (every? #(>= (parse-px (get ei/triangle-style %)) 24)
              [:min-width :min-height])))

(deftest every-toggle-triangle-uses-the-shared-triangle-style
  ;; The three header branches that draw a toggle — collapsed ▸, expanded ▾
  ;; and the depth-capped `▸ {…}` — all carry the shared hit-box style. The
  ;; capped row addresses `[:a]`, the node AT the cap.
  (doseq [[label v depth expansion-map opts toggle-testid placeholder]
          [["collapsed ▸" {:a 1 :b 2 :c 3 :d 4 :e 5} 5 {}
            {:default-expanded-depth 1}
            "rf-xray-edn-inspector-test-m--toggle" nil]
           ["expanded ▾" {:a 1 :b 2 :c 3 :d 4 :e 5} 0
            {(ei/expansion-key :test "m" []) {:expanded? true}}
            {:default-expanded-depth 0}
            "rf-xray-edn-inspector-test-m--toggle" nil]
           ["depth-capped ▸ {…}" {:a {:b {:c {:d 1}}}} 0 {}
            {:default-expanded-depth 5 :max-depth 1}
            "rf-xray-edn-inspector-test-m-:a-toggle" "…"]]]
    (let [h   (ei/render-node {:value v
                               :panel-id :test :mount-id "m"
                               :path [] :depth depth
                               :expansion-map expansion-map
                               :opts opts})
          tog (find-attr h :data-testid toggle-testid)]
      (when placeholder
        (is (str/includes? (collect-text h) placeholder)
            (str label " is the capped placeholder")))
      (is (= ei/triangle-style (-> tog second :style))
          (str label " uses the shared triangle-style verbatim")))))

(deftest depth-capped-toggle-expands-one-level-rf2-3x7nj-25-4
  ;; The capped `▸ {…}` is a real control: its own click, run through the
  ;; real reducer, opens the node — by exactly one level.
  (let [render   (fn [expansion-map dispatch-fn]
                   (ei/render-node {:value {:a {:b {:c 1}}}
                                    :panel-id :test :mount-id "m"
                                    :path [] :depth 0
                                    :expansion-map expansion-map
                                    :dispatch-fn dispatch-fn
                                    :opts {:default-expanded-depth 5 :max-depth 1}}))
        a-toggle "rf-xray-edn-inspector-test-m-:a-toggle"
        b-toggle "rf-xray-edn-inspector-test-m-:a/:b-toggle"
        state    (fn [h toggle-testid child-key]
                   [(-> (find-attr h :data-testid toggle-testid) second :aria-expanded)
                    (str/includes? (collect-text h) child-key)])
        captured (atom nil)
        capped   (render {} (fn [ev] (reset! captured ev)))]
    (is (= [false false] (state capped a-toggle ":b"))
        "at the cap `[:a]` is collapsed with nothing painted under it")
    ((-> (find-attr capped :data-testid a-toggle) second :on-click) nil)
    (rf/dispatch-sync @captured)
    (let [h (render @(rf/subscribe [ei/expansion-slot]) nil)]
      (is (= [true true] (state h a-toggle ":b"))
          "the override the click stored opens `[:a]`")
      (is (= [false false] (state h b-toggle ":c"))
          "by one level — `[:a :b]` is capped in its turn"))))

;; ---- map body layout -----------------------------------------------------

(deftest map-body-is-one-grid-with-key-and-value-cells
  ;; Keys and values are direct children of one `max-content 1fr` grid, so
  ;; values column-align across rows; a nested map lays out inside its own
  ;; value cell.
  (let [h    (ei/render-node {:value {:scalar-1 1 :scalar-2 "two" :nested {:inner 99}}
                              :panel-id :p :mount-id "m"
                              :path [] :depth 0
                              :expansion-map {(ei/expansion-key :p "m" []) {:expanded? true}}
                              :opts {:default-expanded-depth 0}})
        body (find-attr h :data-testid "rf-xray-edn-inspector-p-m--body")
        s    (-> body second :style)]
    (is (= ["grid" "max-content 1fr" 6]
           [(:display s) (:grid-template-columns s) (count (drop 2 body))])
        "3 rows × (key cell + value cell) as direct grid children")))

(deftest gutter-row-is-inline-flex-not-block
  ;; A diff'd leaf composes inline with its key; a block wrapper inside a
  ;; wrapping row would push the value below the key.
  (let [h (ei/render-node {:value 1 :before 1 :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {} :opts {}})]
    (is (= [:span "inline-flex"] [(first h) (-> h second :style :display)]))))

(deftest scalar-leaves-render-as-inline-spans-in-non-diff-mode
  (let [h (ei/render-node {:value 42
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {} :opts {}})]
    (is (= [:span "42"] [(first h) (collect-text h)]))))

;; ---- toggle handler shape ------------------------------------------------

(deftest toggle-handler-dispatches-canonical-event
  ;; The click carries the node's rendered state as the fifth slot, so the
  ;; reducer can invert from what the user sees.
  (doseq [[path depth default-depth toggle-testid rendered-expanded?]
          [[[:x] 5 1 "rf-xray-edn-inspector-test-m1-:x-toggle" false]
           [[]   0 2 "rf-xray-edn-inspector-test-m1--toggle"   true]]]
    (let [captured (atom nil)
          h        (ei/render-node {:value {:a 1 :b 2 :c 3 :d 4 :e 5}
                                    :panel-id :test
                                    :mount-id "m1"
                                    :path path
                                    :depth depth
                                    :expansion-map {}
                                    :dispatch-fn (fn [event-v] (reset! captured event-v))
                                    :opts {:default-expanded-depth default-depth}})]
      ((-> (find-attr h :data-testid toggle-testid) second :on-click) nil)
      (is (= [:rf.xray.edn-inspector/toggle-node :test "m1" path rendered-expanded?]
             @captured)))))

;; ---- mount-id, site-id and per-call-site isolation -----------------------

(deftest edn-inspector-container-carries-mount-and-site-id-attrs
  ;; Each mount draws its own mount-id, so two side-by-side mounts keep
  ;; independent expansion; a supplied `:site-id` is published beside it;
  ;; the container carries the measurement ref.
  (let [attrs (fn [opts] (second ((ei/edn-inspector {:a 1} opts) {:a 1} opts)))]
    (is (not= (:data-rf-mount-id (attrs {:panel-id :p}))
              (:data-rf-mount-id (attrs {:panel-id :p})))
        "two mounts with no :site-id draw distinct mount-ids")
    (is (= (pr-str [:my-site "x"])
           (:data-rf-site-id (attrs {:panel-id :p :site-id [:my-site "x"]}))))
    (is (fn? (:ref (attrs {:panel-id :p})))
        "the container carries the ref callback that drives width measurement")))

(deftest site-id-keys-the-expansion-state-the-render-reads
  ;; An override stored under `[panel-id site-id path]` is the one a fresh
  ;; mount reads, whatever mount-id it drew — so the state survives a remount.
  (let [site-id [:my-site "x"]
        v       {:a 1 :b 2 :c 3 :d 4}
        opts    {:panel-id :p :site-id site-id :default-expanded-depth 0}
        root-expanded (fn []
                        (:data-rf-expanded
                          (second (first (nodes-with-attr ((ei/edn-inspector v opts) v opts)
                                                          :data-rf-kind "map")))))]
    (is (= "1" (root-expanded)) "with no override the root renders expanded")
    (rf/dispatch-sync [:rf.xray.edn-inspector/set-node :p site-id [] false])
    (is (= "0" (root-expanded))
        "an override stored under the site-id collapses a fresh mount's root")))

(deftest two-mounts-independent-via-distinct-mount-ids
  (let [v        {:a 1 :b 2 :c 3 :d 4 :e 5}
        emap     {(ei/expansion-key :p "mount-1" []) {:expanded? true}
                  (ei/expansion-key :p "mount-2" []) {:expanded? false}}
        expanded (fn [mount-id]
                   (-> (ei/render-node {:value v :panel-id :p :mount-id mount-id
                                        :path [] :depth 0
                                        :expansion-map emap
                                        :opts {:default-expanded-depth 0}})
                       second
                       :data-rf-expanded))]
    (is (= ["1" "0"] (map expanded ["mount-1" "mount-2"]))
        "each mount reads only its own override for the same path")))

;; ---- mini one-liner ------------------------------------------------------

(deftest mini-one-liner-renders-scalars-maps-and-sentinels
  ;; A sentinel keeps its opaque chip on the one-line path too.
  (is (= [":foo" "{:a 1, :b 2}" "●redacted"]
         (map #(collect-text (ei/mini % 80)) [:foo {:a 1 :b 2} :rf/redacted]))))

;; =========================================================================
;; Diff mode
;; =========================================================================
;;
;; Passing `:before` paints a gutter glyph, wash and stripe per node and a
;; `← was <prior>` annotation on modified leaves; ancestors of a change
;; force open. Classification is the diff engine's, pinned in its own
;; suite; these tests pin the chrome.

(deftest gutter-glyph-colour-is-syntax-palette-disjoint
  ;; The reserved `:diff-gutter` hue sits outside every `:syntax-*` token,
  ;; so diff state never reads as a type colour.
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
               "in palette " (if (= palette dark-palette) :dark :light))))))

(deftest diff-leaf-preserves-syntax-token-colour
  ;; The row chrome carries the diff signal; the leaf keeps its syntax
  ;; colour under `:added` and `:modified`.
  (doseq [[v before data-type token] [[42 ::ei/missing "number" :syntax-number]
                                      [true false "boolean" :syntax-boolean]]]
    (let [h (ei/render-node {:value v :before before :diff? true
                             :panel-id :p :mount-id "m" :path [] :depth 0
                             :expansion-map {} :opts {}})]
      (is (= (get tokens token)
             (-> (find-attr h :data-rf-type data-type) second :style :color))
          (str data-type " keeps " token)))))

(deftest diff-row-wrapper-carries-wash-and-stripe-attrs
  (let [chrome (fn [before]
                 ((juxt :data-rf-diff-op :data-rf-diff-wash :data-rf-diff-stripe
                        (comp :background :style))
                  (second (ei/render-node {:value 42 :before before :diff? true
                                           :panel-id :p :mount-id "m" :path [] :depth 0
                                           :expansion-map {} :opts {}}))))]
    (is (= ["added" "1" "1" (:diff-added-wash tokens)] (chrome ::ei/missing))
        "an added row carries the wash and the stripe, the wash through its token")
    (is (= ["same" nil nil nil] (chrome 42))
        "a same row carries neither")))

(deftest diff-modified-leaf-emits-changed-from-annotation
  (is (re-find #"← was 1"
               (collect-text (ei/render-node {:value 2 :before 1 :diff? true
                                              :panel-id :p :mount-id "m"
                                              :path [] :depth 0
                                              :expansion-map {} :opts {}})))))

(deftest diff-removed-leaf-shows-prior-value
  ;; The struck row paints the prior value — never the absence sentinel.
  (is (= "-1" (collect-text (ei/render-node {:value ei/missing-sentinel
                                             :before 1
                                             :diff? true
                                             :panel-id :p :mount-id "m"
                                             :path [] :depth 0
                                             :expansion-map {} :opts {}})))))

(deftest diff-forces-ancestor-chain-open-over-changed-descendant
  ;; A change four levels down shows even though the depth heuristic would
  ;; collapse its ancestors.
  (is (re-find #"← was 1"
               (collect-text (ei/render-node {:value  {:a {:b {:c {:d {:e 2}}}}}
                                              :before {:a {:b {:c {:d {:e 1}}}}}
                                              :diff? true
                                              :panel-id :p :mount-id "m"
                                              :path [] :depth 0
                                              :expansion-map {}
                                              :opts {:default-expanded-depth 1}})))))

(deftest diff-same-leaf-uses-text-tertiary
  (let [h (ei/render-node {:value 1
                           :before 1
                           :diff? true
                           :panel-id :p :mount-id "m" :path [] :depth 0
                           :expansion-map {}
                           :opts {}})
        ;; Read the value span's own colour: the gutter glyph beside it is
        ;; text-tertiary too, whatever colour the value took.
        value-colours (keep (fn [n] (get-in (second n) [:style :color]))
                            (nodes-with-attr h :data-rf-diff-op "same"))]
    (is (= [(:text-tertiary tokens)] value-colours))))

(deftest edn-inspector-mode-marker-on-container
  ;; `data-rf-mode` is "diff" when `:before` is supplied, "browse" otherwise.
  (is (= ["diff" "browse"]
         [(:data-rf-mode (second ((ei/edn-inspector {:a 2} {:before {:a 1}})
                                  {:a 2} {:before {:a 1}})))
          (:data-rf-mode (second ((ei/edn-inspector {:a 1}) {:a 1} nil)))])))

;; ---- set members ---------------------------------------------------------

(deftest children-of-pair-set-union-sorted
  ;; A set diff walks the union of members in `pr-str` order, so a removed
  ;; member has a stable place beside the survivors.
  (is (= [[:a :a :a]
          [:b :b :b]
          [:ws/authenticating ::ei/missing :ws/authenticating]]
         (vec (ei/children-of-pair #{:a :b :ws/authenticating} #{:a :b} :set)))))

;; =========================================================================
;; Vector diff — removed members struck in place
;; =========================================================================
;;
;; The renderer consumes the engine's `:vector-removals` + `:same-shifted`
;; projection rather than index-aligning before and after, so a scattered
;; or mid-vector removal strikes the removed member, not the survivor that
;; slid up into its slot.

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
  "Render `before -> after` as a vector diff through the real projection,
  as the production renderer does."
  [before after]
  (ei/render-node {:value      after
                   :before     before
                   :diff?      true
                   :projection (engine/project before after)
                   :panel-id   :p :mount-id "m"
                   :path [] :depth 0
                   :expansion-map {}
                   :opts {:default-expanded-depth 4}}))

(deftest projection-vector-diff-strikes-removed-members-not-survivors
  ;; Each row is an edit script rendered through the real projection: every
  ;; removed member is struck, and every survivor renders and is NOT struck.
  ;; A misaligned replay of the edit script surfaces as the WRONG member
  ;; struck or a removal dropped.
  (doseq [[label before after removed survivors]
          [["scattered `[:a :b :c :d] -> [:a :c]`: :b@1 and :d@3 removed, :c survives shifted 2 → 1"
            [:a :b :c :d] [:a :c] [":b" ":d"] [":a" ":c"]]
           ["contiguous tail `[:x :y :z] -> [:x]`, the one case index alignment also gets right"
            [:x :y :z] [:x] [":y" ":z"] [":x"]]
           ["insert before a delete, `[[0] :+ :X] [[2] :-]`: a misaligned replay strikes :c and never surfaces :b"
            [:a :b :c] [:X :a :c] [":b"] [":a" ":c" ":X"]]
           ["insert before a double delete, `[[0] :+ :X] [[2] :-] [[2] :-]`: a misaligned replay strikes :d and drops :c"
            [:a :b :c :d] [:X :a :d] [":b" ":c"] [":d"]]
           ["insert then a tail delete, `[[1] :+ :X] [[4] :-]`: replaying edit-index 4 against `(range 4)` would DROP the removal"
            [:a :b :c :d] [:a :X :b :c] [":d"] [":a" ":b" ":c" ":X"]]]]
    (let [h      (render-vec-diff before after)
          all    (collect-text h)
          struck (struck-members h)]
      (doseq [m removed]
        (is (contains? struck m)
            (str label " — the removed " m " is struck")))
      (doseq [m survivors]
        (is (not (contains? struck m))
            (str label " — the survivor " m " is not struck"))
        (is (str/includes? all m)
            (str label " — the survivor " m " still renders")))))
  (testing "the scattered script's surviving-shifted :c carries a `(was N)`
            suffix; the exact N is the engine's contract, tested there"
    (is (re-find #"\(was \d+\)" (collect-text (render-vec-diff [:a :b :c :d] [:a :c]))))))

;; ---- the sequential diff entry path is bounded ---------------------------
;;
;; `sequential-diff-children` realises both sides through `bounded-vec`
;; before it branches, so an endless sequence is never realised whole on
;; the diff path. These measure REALISATION with a counter, never output
;; length: a bound reached too late looks identical to one that works if
;; all you count is rows.

(def ^:private count-bound
  "Mirrors the view's own private `count-bound` (1001) — the single
  ceiling `child-count`, `children-of`, `children-of-pair`,
  `diff-pair-count` and `bounded-vec` all share."
  1001)

(def ^:private render-path-bound
  "What the RENDER path may realise: `count-bound` plus exactly one,
  because the header's `bounded-count*` (`cljs.core/bounded-count`) looks
  one element past the ceiling to learn whether another exists."
  (inc count-bound))

(defn- counting-seq
  "Endless lazy seq 0, 1, 2, … that records the high-water mark of
  REALISED elements in `counter` and THROWS when asked for element
  `guard`.

  The guard converts \"loops for ever\" into \"fails in milliseconds\",
  so an unbounded walk's RED is observable at all. `lazy-seq` + `cons` is
  unchunked, so `counter` tracks single-element pulls exactly."
  [counter guard]
  (letfn [(step [i]
            (lazy-seq
              (when (>= i guard)
                (throw (ex-info "realised past the guard"
                                {:i i :guard guard})))
              (swap! counter max (inc i))
              (cons i (step (inc i)))))]
    (step 0)))

(deftest sequential-diff-children-bounds-the-entry-path-rf2-brmyq
  ;; A generator that throws past element 1500 is realised no further than
  ;; the bound, whichever side it is on.
  (let [seen (atom 0)]
    (dorun (ei/sequential-diff-children (counting-seq seen 1500) [1 2 3] :vector [] nil))
    (is (<= @seen count-bound)
        (str "the BEFORE side realised " @seen " elements; the bound is " count-bound)))
  (let [seen (atom 0)]
    (dorun (ei/sequential-diff-children [1 2 3] (counting-seq seen 1500) :seq [] nil))
    (is (<= @seen count-bound)
        (str "the AFTER side realised " @seen " elements; the bound is " count-bound))))

(deftest diff-render-path-bounds-an-endless-sequence-rf2-brmyq
  ;; Through `render-node`: the header count and the walk together realise
  ;; at most the render-path bound, on either side. A guard far above the
  ;; bound stands in for a truly endless sequence.
  (doseq [endless-side [:value :before]]
    (let [seen (atom 0)]
      (ei/render-node (merge {:value [0 1 2] :before [0 1 2]}
                             {endless-side (counting-seq seen 50000)}
                             {:diff? true :projection nil
                              :panel-id :test :mount-id "m1"
                              :path [] :depth 0
                              :expansion-map {} :opts {}}))
      (is (<= @seen render-path-bound)
          (str "the endless " (name endless-side) " side realised " @seen " elements")))))

(deftest bounding-preserves-removal-alignment-over-the-bound-rf2-brmyq
  ;; A finite vector LONGER than the bound, with a mid-vector removal, is
  ;; rendered whole: the removed member struck in place, survivors at their
  ;; shifted after-index, and no row claiming an unrealised value.
  (let [n         1050
        drop-at   500
        before    (vec (range n))
        after     (vec (concat (range drop-at) (range (inc drop-at) n)))
        rows      (vec (ei/sequential-diff-children before after :vector []
                                                    (engine/project before after)))
        removed?  (fn [[_ a _]] (= a ::ei/missing))
        survivors (into {} (comp (remove removed?) (map (fn [[k a _]] [a k]))) rows)]
    (is (= [drop-at] (mapv #(nth % 2) (filter removed? rows)))
        (str "exactly element " drop-at " is struck — index alignment would "
             "strike the survivor that slid up into the vacated slot"))
    (is (= (vec (range n)) (mapv (fn [[_ a b :as row]] (if (removed? row) b a)) rows))
        "rows read in BEFORE-order with the deletion in place, none dropped")
    (is (= [500 0] (map survivors [(inc drop-at) 0]))
        "element 501 renders at after-index 500; element 0 is unmoved")
    (is (not-any? (fn [[_ a b]] (or (= a ::ei/unrealised) (= b ::ei/unrealised))) rows)
        "neither side was capped, so no row claims an unrealised value")))

;; ---- a `counted?` sequence renders every row its header promises ---------
;;
;; Counters (`bounded-count*`) and walkers (`bounded-vec`) both split on
;; `counted?`, so a finite collection is realised whole and only an
;; endless-capable one is capped: the header's number and the body's rows
;; agree past the bound.

(def ^:private jh12f-n
  "Comfortably past `count-bound`, so a walker that truncates shows it by
  ~200 rows rather than by one."
  1200)

(defn- header-promise
  "The number the COLLAPSED header promises, read out of the production
  code: `inline-preview-string`'s fallback `(…N items)`, whose N comes from
  `bounded-count*`. A `max-chars` of 0 forces that fallback; a cut
  sequence's `N+` is read past."
  [v]
  (let [s (ei/inline-preview-string v 3 0)]
    (some-> (re-find #"(\d+)\+? items" s) second js/parseInt)))

(defn- rendered-rows
  "How many child rows the renderer emitted for the ROOT container: the
  root's block body less its tag and attribute map."
  [tree]
  (when-let [body (first (nodes-with-attr tree :data-rf-body-layout "block"))]
    (- (count body) 2)))

(defn- render-expanded
  "`render-node` with the root forced OPEN, so the body is walked rather
  than summarised (the override also switches off the inline-fit gate)."
  [m]
  (let [panel-id :test
        mount-id "m1"]
    (ei/render-node
      (merge {:panel-id      panel-id
              :mount-id      mount-id
              :path          []
              :depth         0
              :expansion-map {(ei/expansion-key panel-id mount-id [])
                              {:expanded? true}}
              :opts          {}}
             m))))

(deftest browse-header-and-body-agree-for-a-counted-list-rf2-jh12f
  ;; A `PersistentList` is `counted?`, so the header reports its full count
  ;; and the walk must render every element under it.
  (let [v (apply list (range jh12f-n))]
    (is (= [jh12f-n jh12f-n]
           [(header-promise v) (rendered-rows (render-expanded {:value v}))]))))

;; ---- a sequence cut at the bound SAYS so ---------------------------------

(defn- unrealised-tail
  "The trailing `… (not realised past N)` row, or nil."
  [tree]
  (find-attr tree :data-rf-cell "unrealised-tail"))

(deftest a-cut-sequence-says-so-rf2-3x7nj-25-1
  ;; A not-`counted?` sequence longer than the bound reads as CUT — a
  ;; trailing row and a `1001+` count — at no cost past the render bound.
  (let [guard 50000]
    (testing "the expanded body closes on an explicit unrealised-tail row"
      (let [seen (atom 0)
            h    (render-expanded {:value (counting-seq seen guard)})]
        (is (str/includes? (collect-text (unrealised-tail h))
                           (str "not realised past " count-bound)))
        (is (= count-bound (rendered-rows h))
            "the body's own rows are still exactly the rows walked")
        (is (<= @seen render-path-bound)
            (str "and realised " @seen " elements"))))
    (testing "the collapsed count reads `1001+`, not a confident `1001`"
      (let [seen (atom 0)
            s    (ei/inline-preview-string (counting-seq seen guard) 3 0)]
        (is (str/includes? s (str count-bound "+ items")) s)
        (is (<= @seen render-path-bound) (str "realised " @seen " elements")))
      (is (str/includes? (ei/inline-preview-string
                           {:rows (map identity (range 5000))} 3 80)
                         (str "(…" count-bound "+ items)"))
          "and so does a cut sequence's placeholder inside a parent's preview")))
  (testing "nothing is claimed of a sequence that was NOT cut"
    (let [exact (map identity (range count-bound))]
      (is (nil? (unrealised-tail (render-expanded {:value exact})))
          (str "a lazy seq of EXACTLY " count-bound " elements ended, so no marker"))
      (is (str/includes? (ei/inline-preview-string exact 3 0)
                         (str count-bound " items"))
          "and its count carries no `+`"))
    (is (nil? (unrealised-tail (render-expanded {:value (apply list (range jh12f-n))})))
        "nor does a `counted?` list, which is rendered whole")))

;; ---- a capped BEFORE side keeps the counted AFTER tail -------------------
;;
;; `bounded-vec` caps only a not-`counted?` side, so a lazy before side stops
;; at the bound while a vector after side is realised whole. The walk emits
;; every accessible after row; a survivor past the before bound carries
;; `::unrealised` — an UNKNOWN prior — never `::missing`, the structural
;; sentinel that forces `:added` and would paint the survivor green.

(deftest capped-before-side-keeps-the-counted-after-tail-rf2-zk4he
  (let [n      1050
        before (map identity (range n))
        after  (assoc (vec (range n)) (dec n) :changed-at-tail)
        rows   (vec (ei/sequential-diff-children before after :vector []
                                                 (engine/project before after)))]
    (is (= n (count rows)) "every accessible AFTER row is emitted")
    (is (= [(dec n) :changed-at-tail ::ei/unrealised]
           (first (filter (fn [[k _ _]] (= k (dec n))) rows)))
        "the changed tail element carries its real value and an UNKNOWN prior")))

(deftest unrealised-before-slot-is-not-an-addition-rf2-zk4he
  ;; `::unrealised` is not structural, so the op comes off the projection;
  ;; with no projection the `← was` chip states the unknown instead of
  ;; printing the sentinel.
  (let [render      (fn [b projection]
                      (ei/render-node {:value      :changed
                                       :before     b
                                       :diff?      true
                                       :projection projection
                                       :panel-id   :test :mount-id "m1"
                                       :path       [1] :depth 1
                                       :expansion-map {} :opts {}}))
        unprojected (render ::ei/unrealised nil)]
    (is (= "modified" (-> (render ::ei/unrealised (engine/project [1 2] [1 :changed]))
                          second
                          :data-rf-diff-op))
        "the row takes the projection's own op, never `:added`")
    (is (not (str/includes? (collect-text unprojected) "edn-inspector/unrealised"))
        "the internal sentinel keyword never reaches the rendered output")
    (is (seq (nodes-with-attr unprojected :data-rf-diff-annotation "unrealised-before"))
        "an explicit unknown-prior chip is rendered in its place")))

(deftest capped-before-side-stays-bounded-rf2-zk4he
  ;; Recovering the after tail costs nothing past the walker's bound on an
  ;; endless before side. The projection comes from an equivalent vector, so
  ;; computing it does not itself realise the generator.
  (let [n     1050
        after (assoc (vec (range n)) (dec n) :changed-at-tail)
        seen  (atom 0)
        rows  (vec (ei/sequential-diff-children
                     (counting-seq seen 1500) after :vector []
                     (engine/project (vec (range n)) after)))]
    (is (<= @seen count-bound)
        (str "realised " @seen " elements of the endless BEFORE side"))
    (is (= n (count rows)) "and still emits every accessible AFTER row")))

;; ---- a capped AFTER side keeps the counted BEFORE tail -------------------
;;
;; The mirror: a vector before side against a lazy after side capped at the
;; bound. Before-side survivors past the after ceiling are emitted in
;; before-order under a synthetic key, carrying `::unrealised` in the AFTER
;; slot — never `::missing`, which would strike a retained element as a
;; confirmed deletion.

(deftest capped-after-side-keeps-the-counted-before-tail-rf2-f8nm7
  (let [n      1050
        before (assoc (vec (range n)) (dec n) :changed-at-tail)
        after  (map identity (range n))
        rows   (vec (ei/sequential-diff-children before after :vector []
                                                 (engine/project before after)))]
    (is (= n (count rows)) "every accessible BEFORE row is emitted")
    (is (= [::ei/unrealised :changed-at-tail]
           (rest (first (filter (fn [[_ _ b]] (= b :changed-at-tail)) rows))))
        "the tail element keeps its real prior and states its current value unknown")
    (is (empty? (filter (fn [[_ a _]] (= a ::ei/missing)) rows))
        "nothing was removed, so no row is struck")))

(deftest unreached-before-row-is-not-an-unchanged-value-rf2-f8nm7
  ;; A row past the after ceiling has no after-path, so `op-at`'s `:same`
  ;; default would paint the PRIOR, muted, as the settled value. It renders
  ;; as `:modified` with an explicit unknown-value token instead.
  (let [n      1050
        before (assoc (vec (range n)) (dec n) :changed-at-tail)
        after  (map identity (range n))
        tree   (render-expanded {:value      after
                                 :before     before
                                 :diff?      true
                                 :projection (engine/project before after)})]
    (is (seq (nodes-with-attr tree :data-rf-diff-value "unrealised-after"))
        "the recovered row states the unknown")
    (is (not (str/includes? (collect-text tree) "edn-inspector/unrealised"))
        "the internal sentinel keyword never reaches the rendered output")))

(deftest capped-after-side-stays-bounded-with-distinct-keys-rf2-f8nm7
  (testing "an endless AFTER side is realised no further than the walker's bound"
    (let [n      1050
          before (assoc (vec (range n)) (dec n) :changed-at-tail)
          seen   (atom 0)
          rows   (vec (ei/sequential-diff-children
                        before (counting-seq seen 1500) :vector []
                        (engine/project before (vec (range n)))))]
      (is (<= @seen count-bound)
          (str "realised " @seen " elements of the endless AFTER side"))
      (is (= n (count rows)) "and still emits every accessible BEFORE row")))
  (testing "every emitted row carries a distinct key"
    ;; Early additions push the unpaired before-indices into the integer
    ;; range the after-side keys occupy, so a bare before-index would collide.
    (let [before (vec (range 1050))
          after  (map identity (concat [:added-a :added-b :added-c] (range 1050)))
          ks     (mapv first (ei/sequential-diff-children before after :vector []
                                                         (engine/project before after)))]
      (is (= (count ks) (count (distinct ks)))))))

;; ---- an unknown prior carried INTO a nested container --------------------
;;
;; When a survivor past the before bound is itself a map, the renderer
;; descends with `::unrealised` as its whole before side. Its children
;; inherit the unknown prior, and the container is not promoted to
;; change-bearing on the strength of a prior nobody looked at.

(deftest nested-tail-container-under-unknown-prior-rf2-t450s
  ;; An unchanged 1050-element vector whose last element is a map, diffed
  ;; against a lazy-seq view of itself: nothing changed.
  (let [n            1050
        tail-map     {:keep 1 :other 2 :third 3 :fourth 4}
        full         (assoc (vec (range n)) (dec n) tail-map)
        proj         (engine/project (map identity full) full)
        tail-path    [(dec n)]
        kids         (vec (ei/children-of-pair ::ei/unrealised tail-map :map))
        rendered-op  (fn [path v b]
                       (-> (ei/render-node {:value      v
                                            :before     b
                                            :diff?      true
                                            :projection proj
                                            :panel-id   :test :mount-id "m1"
                                            :path       path :depth 1
                                            :expansion-map {} :opts {}})
                           second
                           :data-rf-diff-op))]
    (is (= (repeat 4 ::ei/unrealised) (map #(nth % 2) kids))
        "every child keeps the UNKNOWN prior, never `::missing`")
    (is (= (repeat 4 "same")
           (map (fn [[k v b]] (rendered-op (conj tail-path k) v b)) kids))
        "so each child renders the projection's own op, not a fabricated `:added`")
    (is (= ["same" "children"]
           [(rendered-op tail-path tail-map ::ei/unrealised)
            (rendered-op tail-path tail-map {:keep 1})])
        "an unknown prior leaves the container `:same`; a real differing prior still promotes it")))

;; =========================================================================
;; Removed slots render as struck-through ghosts, never as the sentinel
;; =========================================================================
;;
;; These thread a real `engine/project` projection. The engine anchors a
;; `dissoc` on the surviving parent and classifies the removed child slot
;; `:children`; the structural `::missing` sentinel overrides that, and a
;; removed CONTAINER renders as a collapsed struck-through ghost.

(defn- no-missing-sentinel-leak?
  "True iff the rendered hiccup carries no trace of the internal
  `::missing` sentinel keyword in any string leaf OR attribute value."
  [h]
  (let [s (try (pr-str h) (catch :default _ ""))]
    (not (re-find #"edn-inspector/missing" s))))

(deftest diff-removed-only-key-renders-struck-ghost-not-sentinel
  ;; `(update db :shapes dissoc :added)` removes the only key of `:shapes`;
  ;; the removed `:added {…}` slot renders as a collapsed struck-through
  ;; ghost, never as `:added ::missing`.
  (let [before {:shapes {:added {:label "added" :n 42}}}
        after  {:shapes {}}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection (engine/project before after)
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})]
    (is (no-missing-sentinel-leak? h)
        "the ::missing sentinel keyword never reaches the rendered output")
    (is (re-find #":added" (collect-text h))
        "the removed key :added still appears")
    (is (seq (nodes-with-attr h :data-rf-removed-ghost "1"))
        "as a removed-container ghost")
    (is (seq (nodes-with-attr h :data-rf-preview "1"))
        "collapsed to a one-line summary rather than the whole deleted subtree")
    (is (re-find #"line-through" (pr-str h))
        "struck through")))

(deftest diff-deleted-ancestor-children-inherit-removed-when-expanded
  ;; Expanding a removed container ghost walks the deleted subtree, and
  ;; every descendant inherits `:removed` — never `:added`, never the
  ;; sentinel.
  (let [before {:shapes {:added {:label "added" :nested {:deep 1}}}}
        after  {:shapes {}}
        expansion-map {(ei/expansion-key :p "m" [:shapes :added])         {:expanded? true}
                       (ei/expansion-key :p "m" [:shapes :added :nested]) {:expanded? true}}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection (engine/project before after)
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map expansion-map
                           :opts {:default-expanded-depth 6}})
        s (try (pr-str h) (catch :default _ ""))]
    (is (no-missing-sentinel-leak? h)
        "no sentinel leak when the ghost subtree is walked")
    (is (re-find #":deep" (collect-text h))
        "the deeply-nested ghost leaf is reachable on expand")
    (is (not (re-find #"data-rf-diff-op.\"?added" s))
        "no descendant of a removed subtree renders as :added")
    (is (seq (nodes-with-attr h :data-rf-diff-op "removed"))
        "ghost descendants carry the removed marker")))

(deftest diff-removed-vector-element-no-sentinel-leak
  ;; A vector that loses its tail under a real projection strikes exactly
  ;; the dropped elements, printed as their values.
  (let [before {:xs [:a :b :c]}
        after  {:xs [:a]}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection (engine/project before after)
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})]
    (is (no-missing-sentinel-leak? h)
        "a popped vector tail does not leak the ::missing sentinel")
    (is (= #{":b" ":c"} (struck-members h))
        "the dropped :b and :c are struck, as their values")))

(deftest diff-preserves-added-modified-same-rows
  ;; A map diff walks the union of both sides' keys, so added, removed,
  ;; modified and unchanged rows render together.
  (let [h (ei/render-node {:value  {:same 1 :modify 9 :added :new}
                           :before {:same 1 :modify 2 :gone "g"}
                           :diff? true
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 2}})]
    (is (every? #(seq (nodes-with-attr h :data-rf-diff-op %))
                ["added" "removed" "modified" "same"]))
    (is (re-find #"← was 2" (collect-text h))
        "the modified leaf carries the change annotation")))

(deftest l0us2-set-member-swap-renders-member-level-not-whole-key
  ;; The door machine's `:tags` going `#{:door/locked}` → `#{:door/closed}`
  ;; renders `-:door/locked +:door/closed` with the `:tags` KEY intact — not
  ;; a struck-through whole `:tags` entry.
  (let [before {:tags #{:door/locked}}
        after  {:tags #{:door/closed}}
        h (ei/render-node {:value after
                           :before before
                           :diff? true
                           :projection (engine/project before after)
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 6}})]
    (is (= #{":door/locked"} (struck-members h))
        "only the gone member is struck — not the whole key")
    (is (seq (nodes-with-attr h :data-rf-diff-op "added"))
        "the new member carries the added marker")
    (is (re-find #":door/closed" (collect-text h))
        "and renders")))

;; ---- a vector filled from empty renders member-level ---------------------

(deftest yucxn-vector-populated-from-empty-renders-added
  ;; `{:a []} → {:a [1]}` shows the new element as its own added row, not a
  ;; whole-key `~` modify with `← was []`.
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
        all (collect-text h)]
    (is (re-find #"\b1\b" all) "the new element 1 renders")
    (is (seq (nodes-with-attr h :data-rf-diff-op "added"))
        "the filled-from-empty vector shows the new element as an added row")))

;; ---- an emptied collection keeps its KEY intact --------------------------
;;
;; `#{:a}→#{}`, `{:k :v}→{}`, `[x]→[]`, `(x)→()` keep the key — the dropped
;; member is struck INSIDE the now-empty container — where a `dissoc`
;; strikes the key as a removed ghost. `diff-emptied?` reads the SLOT shape:
;; an emptied slot's AFTER value is a present empty collection.

(defn- find-key-cell
  "Return the first `data-rf-cell \"key\"` hiccup node whose flattened text
  matches `key-pat` (a regex)."
  [tree key-pat]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n) (map? (second n))
                      (= "key" (get (second n) :data-rf-cell))
                      (re-find key-pat (collect-text n)))))
       first))

(defn- key-intact?
  "True when the OUTER key row matching `key-pat` is NOT struck-through and
  carries NO `−` removal glyph. `nil` (not intact) when no such key cell."
  [tree key-pat]
  (let [cell (find-key-cell tree key-pat)
        s    (when cell (pr-str cell))]
    (boolean
      (and s
           (not (re-find #"line-through" s))
           (not (re-find #"\"−\"" s))))))

(defn- emptied-render
  "Render `{key populated}` → `{key empty}` at DEFAULT depth (no forced
  expansion) and return the hiccup."
  [k populated empty-coll]
  (let [before {k populated}
        after  {k empty-coll}
        proj   (engine/project before after)]
    (ei/render-node {:value after :before before :diff? true
                     :projection proj :panel-id :p :mount-id "m"
                     :path [] :depth 0 :expansion-map {} :opts {}})))

(deftest c0c6a3-emptied-collection-renders-key-intact-not-removed-ghost
  ;; Each container family walks its own children, so each is a row.
  (doseq [[label k populated empty-coll member-pat]
          [["set"    :one-set #{:only}  #{}  #":only"]
           ["map"    :one-map {:k 1}    {}   #":k"]
           ["vector" :one-vec [:only]   []   #":only"]
           ["list"   :one-lst '(:only)  '()  #":only"]]]
    (let [h (emptied-render k populated empty-coll)]
      (is (key-intact? h (re-pattern (str k)))
          (str label ": the key renders intact — emptying is not a key removal"))
      (is (seq (nodes-with-attr h :data-rf-diff-op "removed"))
          (str label ": the dropped member is marked removed"))
      (is (re-find member-pat (collect-text h))
          (str label ": and still renders")))))

(deftest c0c6a3-diff-emptied-predicate
  ;; Same FAMILY, not same kind; a populated → populated swap, an
  ;; already-empty before and a type flip are not emptyings.
  (are [before after emptied?] (= emptied? (ei/diff-emptied? before after))
    [1 2 3] '()   true
    #{:a}   #{:b} false
    #{}     #{}   false
    {:k 1}  #{}   false))

;; =========================================================================
;; Single render path: value (always) + before (optional)
;; =========================================================================

(defn- render-map-diff
  "Render `before -> after` through the real projection at a depth where
  the root's rows are visible."
  [before after]
  (ei/render-node {:value after
                   :before before
                   :diff? true
                   :projection (engine/project before after)
                   :panel-id :p :mount-id "m"
                   :path [] :depth 0
                   :expansion-map {}
                   :opts {:default-expanded-depth 2}}))

(deftest with-before-paints-rail-on-change-bearing-container
  ;; R4: a change-bearing container's body carries an op-coloured rail.
  (is (some? (find-attr (render-map-diff {:a 1} {:a 1 :nested {:x 1 :y 2}})
                        :data-rf-rail "1"))))

(defn- diff-op-values
  "Collect every non-nil `:data-rf-diff-op` attribute value in `tree`."
  [tree]
  (->> (walk-hiccup tree)
       (keep (fn [n]
               (when (map? (second n))
                 (get (second n) :data-rf-diff-op))))
       (remove nil?)))

(deftest value-only-render-has-no-rail-or-annotation
  ;; With no pre-image the same renderer shows the value plainly. Every
  ;; rail, gutter row and annotation hangs off a diff op.
  (let [h (ei/render-node {:value {:counter 2 :stable :x :nested {:deep 1}}
                           :panel-id :p :mount-id "m"
                           :path [] :depth 0
                           :expansion-map {}
                           :opts {:default-expanded-depth 4}})]
    (is (re-find #":counter" (collect-text h)) "the value renders")
    (is (empty? (diff-op-values h)) "with no diff chrome")))

;; ---- slot-vs-value anchoring (R2 + R6 whole-row treatment) ---------------
;;
;; When the SLOT changes (key added / removed) the per-op wash paints the
;; WHOLE row (key cell + value cell) and a removal strikes the key text.
;; When only the VALUE changed (R1/R7/R8) the chrome stays on the value.

(defn- slot-cells
  "Every node tagged `data-rf-row-anchor=\"slot\"`."
  [tree]
  (filter #(= "slot" (:data-rf-row-anchor (second %))) (walk-hiccup tree)))

(deftest slot-anchored-added-key-paints-whole-row
  ;; Both cells carry the wash, and the leaf's own gutter-row wash is
  ;; suppressed so the value half is not painted twice.
  (let [cells (slot-cells (render-map-diff {:a 1} {:a 1 :b 2}))]
    (is (= #{"key" "value"} (set (map #(:data-rf-cell (second %)) cells))))
    (is (every? #(some? (-> % second :style :background)) cells)
        "each slot cell paints the per-op wash")
    (is (not-any? #(= "1" (:data-rf-diff-wash (second %)))
                  (walk-hiccup (first (filter #(= "value" (:data-rf-cell (second %))) cells))))
        "the slot-anchored value cell suppresses its inner gutter-row wash")))

(deftest slot-anchored-removed-key-paints-whole-row-and-strikes-key
  ;; Both cells carry the wash, and the strike reaches the key text.
  (let [cells    (slot-cells (render-map-diff {:a 1 :legacy-flag true} {:a 1}))
        style-of (fn [role]
                   (map #(-> % second :style)
                        (filter #(= role (:data-rf-cell (second %))) cells)))]
    (is (= [["line-through" true]]
           (map (juxt :text-decoration (comp some? :background)) (style-of "key")))
        "one key cell, washed and struck")
    (is (= [true] (map (comp some? :background) (style-of "value")))
        "one value cell, washed")))

(deftest value-anchored-modified-row-does-not-paint-key-cell-wash
  ;; R1 and R8 change the value inside a surviving slot: no slot anchor,
  ;; hence no key-cell wash or strike.
  (let [h (render-map-diff {:counter 5} {:counter 6})]
    (is (re-find #"← was 5" (collect-text h)) "the value-side R1 annotation renders")
    (is (empty? (slot-cells h)) "and the row carries no slot-anchor markers"))
  (is (empty? (slot-cells (render-map-diff {:secret :rf/redacted} {:secret "now-visible"})))
      "nor does an R8 redaction transition"))

;; ---- inspector card chrome on top-level mounts ---------------------------

(defn- invoke-edn-inspector
  "Form-2 unrolling — run the outer fn, then the inner fn with the same
  args to get the rendered hiccup."
  [value opts]
  (let [outer (ei/edn-inspector value opts)]
    (outer value opts)))

(deftest card-opt-applies-card-chrome-only-when-set
  ;; `:card? true` gives a top-level mount the theme-aware card surface
  ;; spec/021 documents; inline mounts leave it off.
  (let [chrome (fn [opts]
                 ((juxt :background-color :border :border-radius :padding :margin-bottom)
                  (-> (invoke-edn-inspector {:a 1} (assoc opts :panel-id :rf.xray/app-db))
                      second
                      :style)))]
    (is (= [nil nil nil nil nil] (chrome {})) "off by default")
    (is (= [(:bg-1 tokens) (str "1px solid " (:border-default tokens)) "8px" "8px 10px" "8px"]
           (chrome {:card? true})))))

;; ---- close bracket -------------------------------------------------------

(defn- find-close-divs
  "Return every closing-bracket div (cells with `data-rf-cell \"close\"`)."
  [tree]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (= "close" (:data-rf-cell (second node)))))
          (walk-hiccup tree)))

(deftest expanded-containers-render-a-close-bracket-cell
  (let [h (ei/render-node
            {:value {:a 1 :b 2 :c 3 :d 4 :nested {:x 1 :y 2 :z 3 :w 4}}
             :panel-id :test :mount-id "m"
             :path [] :depth 0
             :expansion-map {(ei/expansion-key :test "m" [])        {:expanded? true}
                             (ei/expansion-key :test "m" [:nested]) {:expanded? true}}
             :opts {:default-expanded-depth 0}})]
    (is (= ["}" "}"] (map collect-text (find-close-divs h)))
        "the outer and the nested expanded map each close their bracket")))

;; ---- :header opt + three-shade card chrome -------------------------------
;;
;; `:header` wraps the render in a `<section>` with a `<header>` ribbon and
;; a body sleeve; without it the widget is a single `<div>`.

(defn- find-tag
  "Return the first hiccup vector in `tree` whose tag is `tag`."
  [tree tag]
  (->> (walk-hiccup tree)
       (filter (fn [n] (and (vector? n) (= tag (first n)))))
       first))

(deftest header-opt-omitted-renders-no-section-wrapper
  (is (= :div (first (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db})))))

(deftest header-opt-hiccup-passes-through-opaquely
  ;; The ribbon embeds the supplied header as-is — no parsing, no required
  ;; shape.
  (let [header-hiccup [:span
                       [:strong "Counter app"]
                       " · "
                       [:code ":rf/default"]]
        h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                        :header header-hiccup})]
    (is (some #(= header-hiccup %) (rest (find-tag h :header))))))

(deftest header-opt-three-shade-chrome-via-tokens
  ;; Section `:bg-2`, header ribbon `:bg-3`, body sleeve `:bg-1`, with the
  ;; borders and spacing spec/021 §10.0.10 documents.
  (let [h (invoke-edn-inspector {:a 1} {:panel-id :rf.xray/app-db
                                        :header "Counter"})]
    (is (= [[(:bg-2 tokens) (str "1px solid " (:border-default tokens)) "4px"]
            [(:bg-3 tokens) "10px 12px" (str "1px solid " (:border-subtle tokens))]
            [(:bg-1 tokens) "12px"]]
           [((juxt :background-color :border :border-radius) (-> h second :style))
            ((juxt :background :padding :border-bottom) (-> (find-tag h :header) second :style))
            ((juxt :background :padding)
             (-> (find-attr h :data-rf-body-role "card-body") second :style))]))))

(deftest header-opt-preserves-mount-id-and-testid-on-section
  ;; The section takes the container's identity, so the same selectors, the
  ;; measurement ref and the mode flag address it.
  (let [h     (invoke-edn-inspector {:a 1 :b 2}
                                    {:panel-id :rf.xray/app-db
                                     :header "Counter"})
        attrs (second h)]
    (is (= [:section true true true "browse"]
           [(first h)
            (some? (:data-testid attrs))
            (some? (:data-rf-mount-id attrs))
            (fn? (:ref attrs))
            (:data-rf-mode attrs)]))))

(deftest header-opt-renders-body-content-inside-body-sleeve
  (is (= "{:counter 7}"
         (collect-text (find-attr (invoke-edn-inspector {:counter 7}
                                                        {:panel-id :rf.xray/app-db
                                                         :header "Counter"})
                                  :data-rf-body-role "card-body")))))

(deftest header-opt-keeps-popup-affordance-on-section
  ;; The affordance button sits inside the section, which positions it.
  (let [h (invoke-edn-inspector {:a 1}
                                {:panel-id :rf.xray/app-db
                                 :header "Counter"
                                 :popup-affordance? true})]
    (is (= "relative" (-> h second :style :position)))
    (is (some #(and (vector? %) (= :button (first %))) (rest h))
        "the button is a direct child of the section")))

;; =========================================================================
;; Width-aware expansion heuristic
;; =========================================================================
;;
;; A container renders inline when its estimated width fits the measured
;; column and expands to a tree otherwise; `:default-expanded-depth` is a
;; CEILING past which nothing auto-expands.

(deftest default-expanded-depth-ceiling-defaults-to-8
  (is (= 8 ei/default-ceiling-depth)))

;; ---- the estimate is BOUNDED ---------------------------------------------
;;
;; `estimated-inline-px` runs on every render: it decides with a walk that
;; stops at the budget, and measures exactly only what is provably small.
;; Every assertion here is on a value that terminates by construction.

(deftest infinite-seq-saturates-HIGH-so-it-never-reads-as-fitting
  ;; Over budget the estimate is `##Inf`. A finite saturation (the char cap
  ;; × 7px ≈ 28,672px) would report a `(range)` as FITTING a wider column.
  (is (= ##Inf (ei/estimated-inline-px (range))))
  (is (false? (ei/would-fit-inline? {:a (range)} 100000))))

(deftest bounded-estimate-leaves-ordinary-values-EXACT
  ;; Under budget the answer is the exact printed width, escapes included —
  ;; up to a string just under the char cap.
  (doseq [v [{:a {:b {:c [1 2 3]}}}
             (vec (range 200))
             "has \"quotes\""
             (apply str (repeat 4000 "y"))]]
    (is (= (* ei/mono-char-width-px (count (pr-str v)))
           (ei/estimated-inline-px v))
        (str "exact for a value printing " (count (pr-str v)) " characters"))))

;; ---- a long STRING leaf is never serialised in full ----------------------

(defn- printed-sizes
  "Run thunk `f` with `pr-str` spying, returning `[result sizes]`,
  where `sizes` holds the character count of every string `pr-str`
  produced, in call order. The original is captured before the
  redefinition, so the spy still measures real output."
  [f]
  (let [sizes    (atom [])
        original pr-str
        result   (with-redefs [cljs.core/pr-str
                               (fn [& objs]
                                 (let [s (apply original objs)]
                                   (swap! sizes conj (count s))
                                   s))]
                   (f))]
    [result @sizes]))

(deftest a-long-string-leaf-is-never-serialised-in-full
  ;; Asserting only `##Inf` would pass a print-it-all branch, which returns
  ;; `##Inf` too — expensively — so this spies on the sizes printed.
  (let [[px sizes] (printed-sizes
                     #(ei/estimated-inline-px {:body (apply str (repeat 500000 "x"))} 100))]
    (is (some #(= 5 %) sizes)
        "the spy is live: the 5-character `:body` key was printed")
    (is (not-any? #(>= % 500000) sizes)
        (str "no print is proportional to the 500,000-character leaf; sizes were "
             (pr-str sizes)))
    (is (= ##Inf px))))

(deftest preview-and-annotation-paths-return-on-an-infinite-seq
  ;; `mini` and the `← was` chip print through a bounded `pr-str`.
  (is (vector? (ei/mini {:a (range)} 40)) "`mini` returns on an infinite seq")
  (is (str/includes? (collect-text (ei/render-node {:value 1
                                                    :before (range)
                                                    :diff? true
                                                    :panel-id :test :mount-id "m1"
                                                    :path [:k] :depth 0
                                                    :expansion-map {} :opts {}}))
                     "← was")
      "the `← was` chip of a leaf whose prior is the infinite seq"))

(deftest would-fit-inline-fits-when-estimate-plus-margin-le-available
  ;; `{:a 1}` prints 6 characters: 42px + the 16px margin. No measurement
  ;; (nil or non-positive) never fits.
  (are [width fits?] (= fits? (ei/would-fit-inline? {:a 1} width))
    200 true
    50  false
    nil false
    0   false))

(deftest default-expanded-collapses-past-the-ceiling-and-unchanged-diff-subtrees
  (is (false? (ei/default-expanded?
                {:depth 9 :child-count 2
                 :value {:a "much-longer-than-the-budget"
                         :b "another-overflowing-string"}
                 :default-expanded-depth 8
                 :available-width-px 100}))
      "past the ceiling a too-wide container stays collapsed")
  (is (false? (ei/default-expanded?
                {:depth 1 :child-count 5 :value {:a 1 :b 2}
                 :default-expanded-depth 3
                 :diff? true}))
      "in diff mode an unchanged subtree collapses even within the ceiling"))

(deftest render-container-width-fit-renders-inline-recursively
  ;; When the measured column fits, the whole value — nested containers
  ;; included — renders on one line in canonical EDN spacing, no toggle.
  (is (= "{:tag :foo, :payload [:active :authenticating]}"
         (collect-text (ei/render-node {:value {:tag :foo :payload [:active :authenticating]}
                                        :panel-id :p :mount-id "m" :path []
                                        :depth 0 :expansion-map {}
                                        :opts {:default-expanded-depth 2
                                               :available-width-px 800}})))))

(deftest render-container-too-wide-expands-to-tree
  (is (re-find #"▾" (collect-text (ei/render-node {:value {:a "much-longer-than-the-budget"
                                                           :b "another-overflowing-string"
                                                           :c "and-yet-more-data"
                                                           :d "and-yet-still-more"
                                                           :e "the-final-overflow"}
                                                   :panel-id :p :mount-id "m" :path []
                                                   :depth 0 :expansion-map {}
                                                   :opts {:default-expanded-depth 8
                                                          :available-width-px 100}})))))

(deftest render-container-respects-operator-override-over-width-fit
  ;; An explicit open override wins over a value that would fit inline.
  (is (re-find #"▾" (collect-text (ei/render-node {:value {:tag :foo :n 1}
                                                   :panel-id :p :mount-id "m" :path []
                                                   :depth 0
                                                   :expansion-map {(ei/expansion-key :p "m" [])
                                                                   {:expanded? true}}
                                                   :opts {:default-expanded-depth 2
                                                          :available-width-px 800}})))))

(deftest width-slot-set-and-clear-events
  (let [widths (fn [] @(rf/subscribe [ei/widths-slot]))]
    (rf/dispatch-sync [:rf.xray.edn-inspector/set-width "m" 600])
    (rf/dispatch-sync [:rf.xray.edn-inspector/set-width "m2" -5])
    (is (= {"m" 600} (widths))
        "a positive measurement is stored; a non-positive one is not")
    (rf/dispatch-sync [:rf.xray.edn-inspector/clear-width "m"])
    (is (empty? (widths)) "clear-width removes the entry")))

;; =========================================================================
;; Zoom-into-node + breadcrumb navigation
;; =========================================================================
;;
;; With `:zoomable?` every non-root container is a zoom target: double-click
;; (or Enter while focused) re-roots the inspector onto it, a breadcrumb row
;; shows the path from the original root, and Esc zooms out one level. A
;; zoom re-roots `value` always and `before` too in diff mode.

(deftest zoom-events-move-the-per-mount-zoom-path
  (let [zoom-of (fn [mount-id]
                  (get @(rf/subscribe [ei/zoom-slot]) (ei/zoom-key :p mount-id)))]
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" [:a :b :c]])
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
    (is (= [:a :b] (zoom-of "m")) "zoom-up pops one segment")
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-up :p "m"])
    (is (nil? (zoom-of "m")) "popping past the root clears the entry")
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" [:a]])
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m" []])
    (is (nil? (zoom-of "m")) "zoom-to an empty path clears the zoom")
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m1" [:a]])
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to :p "m2" [:b]])
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset :p "m1"])
    (is (= [nil [:b]] (map zoom-of ["m1" "m2"]))
        "a scoped reset clears only its own (panel-id, mount-id) entry")
    (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-reset])
    (is (nil? @(rf/subscribe [ei/zoom-slot])) "an unscoped reset clears the whole slot")))

;; ---- zoom gesture — double-click / Enter on the container ----------------

(defn- zoom-target-nodes
  "Every hiccup node carrying `data-rf-zoom-target=1`."
  [tree]
  (filter (fn [n]
            (and (vector? n)
                 (map? (second n))
                 (= "1" (:data-rf-zoom-target (second n)))))
          (walk-hiccup tree)))

(deftest zoomable-marks-non-root-containers-as-zoom-targets
  ;; Only the child map is a target: the root is not (zooming into the
  ;; current root is a no-op), and nothing is with `:zoomable?` off.
  (let [targets (fn [zoomable?]
                  (zoom-target-nodes
                    (ei/render-node {:value {:a {:nested 1} :b 2}
                                     :panel-id :p :mount-id "m"
                                     :path [] :depth 0
                                     :expansion-map {}
                                     :zoomable? zoomable?
                                     :zoom-path-prefix []
                                     :opts {}})))]
    (is (= [1 0] (map (comp count targets) [true false])))
    (is (= [0 "Zoom into [:a]"]
           ((juxt :tab-index :aria-label) (second (first (targets true)))))
        "the target is keyboard-focusable and labelled for screen readers")))

;; ---- Enter / Space on the toggle triangle toggle -------------------------
;;
;; The triangle announces `role="button"` with `tabIndex 0`, so Enter and
;; Space on it toggle the node, and its handler stops propagation so the
;; enclosing zoomable container's Enter-to-zoom never sees the keystroke.
;; Any other key, or a modified Enter, passes through untouched to the
;; spine bindings.

(defn- key-event
  "Minimal `KeyboardEvent` stand-in, with `modifier` (`:ctrl`, `:meta`,
  `:alt` or `:shift`) held when given. Records whether the handler
  called `preventDefault` / `stopPropagation`."
  ([k] (key-event k nil))
  ([k modifier]
   (let [prevented (atom false)
         stopped   (atom false)]
     {:event #js {:key k
                  :ctrlKey (= modifier :ctrl) :metaKey (= modifier :meta)
                  :altKey (= modifier :alt)   :shiftKey (= modifier :shift)
                  :preventDefault  (fn [] (reset! prevented true))
                  :stopPropagation (fn [] (reset! stopped true))}
      :prevented prevented
      :stopped   stopped})))

(defn- press-toggle
  "Fire key `k` (with `modifier` held) at the toggle of a collapsed,
  zoomable node; return what was dispatched and whether the event was
  consumed."
  [k modifier]
  (let [captured (atom [])
        h        (ei/render-node {:value {:a 1 :b 2 :c 3 :d 4 :e 5}
                                  :panel-id :test :mount-id "m1"
                                  :path [:x] :depth 5
                                  :expansion-map {}
                                  :zoomable? true
                                  :zoom-path-prefix []
                                  :dispatch-fn (fn [event-v] (swap! captured conj event-v))
                                  :opts {:default-expanded-depth 1}})
        {:keys [event prevented stopped]} (key-event k modifier)]
    ((-> (find-attr h :data-testid "rf-xray-edn-inspector-test-m1-:x-toggle")
         second
         :on-key-down)
     event)
    {:dispatched @captured :prevented @prevented :stopped @stopped}))

(deftest enter-and-space-on-toggle-toggle-and-do-not-zoom
  (doseq [k ["Enter" " "]]
    (is (= {:dispatched [[:rf.xray.edn-inspector/toggle-node :test "m1" [:x] false]]
            :prevented  true
            :stopped    true}
           (press-toggle k nil))
        (str (pr-str k) " toggles, consumed so the enclosing zoom never fires"))))

(deftest other-keys-and-modified-enter-pass-through-untouched
  (doseq [[k modifier] [["Escape" nil]
                        ["Enter" :ctrl] ["Enter" :meta] ["Enter" :alt] ["Enter" :shift]]]
    (is (= {:dispatched [] :prevented false :stopped false}
           (press-toggle k modifier))
        (str (pr-str k) (when modifier (str " + " (name modifier)))
             " passes through the triangle untouched"))))

(deftest zoom-trigger-enter-ignores-modifiers-and-other-keys
  ;; Only a bare Enter zooms, so Esc-zoom-out and the spine bindings pass.
  (doseq [[k modifier] [["Escape" nil]
                        ["Enter" :ctrl] ["Enter" :meta] ["Enter" :alt] ["Enter" :shift]]]
    (let [dispatched  (atom [])
          on-key-down (:on-key-down (ei/zoom-trigger-attrs
                                      {:dispatch-fn (fn [ev] (swap! dispatched conj ev))
                                       :panel-id :p :mount-id "m"
                                       :absolute-path [:a]}))]
      (on-key-down (:event (key-event k modifier)))
      (is (empty? @dispatched)
          (str (pr-str k) (when modifier (str " + " (name modifier))) " must not zoom")))))

(deftest zoom-trigger-composes-prefix-and-relative-path
  ;; Already zoomed at a prefix, a double-click or Enter on a nested
  ;; container dispatches the ABSOLUTE path through the captured dispatcher.
  (let [dispatched (atom [])
        h     (ei/render-node {:value {:ws/connection {:state :open}}
                               :panel-id :p
                               :mount-id "m"
                               :path []
                               :depth 0
                               :expansion-map {}
                               :zoomable? true
                               :zoom-path-prefix [:rf.db/runtime :rf.runtime/machines :snapshots]
                               :dispatch-fn (fn [ev] (swap! dispatched conj ev))
                               :opts {:default-expanded-depth 8}})
        attrs (second (first (zoom-target-nodes h)))
        zoom  [:rf.xray.edn-inspector/zoom-to :p "m"
               [:rf.db/runtime :rf.runtime/machines :snapshots :ws/connection]]]
    ((:on-double-click attrs) nil)
    ((:on-key-down attrs) (:event (key-event "Enter")))
    (is (= [zoom zoom] @dispatched))))

;; ---- the toggle triangle owns its double-click gesture -------------------

(defn- stub-evt
  "A synthetic-event stub that records `preventDefault` / `stopPropagation`
  invocations into the supplied atom map `{:prevented? false :stopped? false}`."
  [spy]
  (js-obj "preventDefault"  (fn [] (swap! spy assoc :prevented? true))
          "stopPropagation" (fn [] (swap! spy assoc :stopped? true))))

(deftest toggle-glyph-double-click-is-swallowed-no-zoom
  ;; A double-click on the triangle of a zoomable, non-root container must
  ;; not bubble to the container's zoom, nor dispatch anything itself.
  (let [dispatched (atom [])
        spy        (atom {:prevented? false :stopped? false})
        h          (ei/render-node {:value {:a 1 :b 2 :c 3 :d 4 :e 5}
                                    :panel-id :p
                                    :mount-id "m"
                                    :path [:parent]
                                    :depth 5
                                    :expansion-map {}
                                    :zoomable? true
                                    :dispatch-fn (fn [ev] (swap! dispatched conj ev))
                                    :opts {:default-expanded-depth 1}})]
    ((-> (find-attr h :data-testid "rf-xray-edn-inspector-p-m-:parent-toggle")
         second
         :on-double-click)
     (stub-evt spy))
    (is (= [{:prevented? true :stopped? true} []] [@spy @dispatched]))))

;; ---- breadcrumb ----------------------------------------------------------

(deftest breadcrumb-renders-home-plus-segments
  (is (= "app-db›:rf/machines›:ws/connection"
         (collect-text (ei/zoom-breadcrumbs {:panel-id      :p
                                             :mount-id      "m"
                                             :zoom-path     [:rf/machines :ws/connection]
                                             :home-label    "app-db"
                                             :dispatch-fn   identity
                                             :testid-prefix "bc"})))))

(deftest breadcrumb-buttons-dispatch-truncated-paths
  ;; Home zooms back to the root; segment N re-roots at the path's first N+1
  ;; segments.
  (let [dispatched (atom [])
        h     (ei/zoom-breadcrumbs {:panel-id      :p
                                    :mount-id      "m"
                                    :zoom-path     [:rf/machines :ws/connection :data]
                                    :home-label    "app-db"
                                    :dispatch-fn   (fn [ev] (swap! dispatched conj ev))
                                    :testid-prefix "bc"})
        click (fn [segment]
                ((-> (find-attr h :data-rf-breadcrumb-segment segment) second :on-click) nil))]
    (click "home")
    (click "1")
    (is (= [[:rf.xray.edn-inspector/zoom-to :p "m" []]
            [:rf.xray.edn-inspector/zoom-to :p "m" [:rf/machines :ws/connection]]]
           @dispatched))))

(deftest breadcrumb-home-label-accepts-hiccup
  ;; The home segment's content IS the consumer's hiccup, not a printed copy.
  (let [home [:span [:strong "Counter app"] " · " [:code ":rf/default"]]
        h    (ei/zoom-breadcrumbs {:panel-id      :p
                                   :mount-id      "m"
                                   :zoom-path     [:counter]
                                   :home-label    home
                                   :dispatch-fn   identity
                                   :testid-prefix "bc"})]
    (is (= home (last (find-attr h :data-rf-breadcrumb-segment "home"))))))

;; ---- public widget — zoom-aware top-level render -------------------------

(deftest widget-with-zoomable-renders-breadcrumb-when-zoomed
  (let [site-id [:rf.xray/app-db "top"]
        _       (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                                   :rf.xray/app-db site-id [:nested]])
        h       (invoke-edn-inspector {:nested {:deep 42 :other 99} :sibling 1}
                                      {:panel-id  :rf.xray/app-db
                                       :site-id   site-id
                                       :zoomable? true
                                       :header    [:span "app-db"]})
        text    (collect-text h)]
    (is (some? (find-attr h :role "navigation"))
        "a breadcrumb row renders above the body")
    (is (= [true false] (map #(str/includes? text %) [":deep" ":sibling"]))
        "the body is the zoomed subtree; siblings outside it do not render")))

(deftest widget-diff-mode-zooms-and-reroots-both-halves
  ;; In diff mode the zoom re-roots `before` along the same path, so the
  ;; zoomed subtree keeps its annotations.
  (let [site-id [:rf.xray/app-db "top"]
        _       (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                                   :rf.xray/app-db site-id [:nested]])
        text    (collect-text (invoke-edn-inspector {:nested {:deep 42} :sibling 1}
                                                    {:panel-id  :rf.xray/app-db
                                                     :site-id   site-id
                                                     :zoomable? true
                                                     :before    {:nested {:deep 41} :sibling 0}
                                                     :header    [:span "app-db"]}))]
    (is (= [false true] (map #(str/includes? text %) [":sibling" "← was 41"]))
        "siblings are hidden, and the re-rooted before still annotates the change")))

;; ---- a list / seq element is zoomable, and an unresolvable zoom ----------
;; ---- renders un-zoomed ---------------------------------------------------
;;
;; A list or seq element is keyed by its integer index, which `get` cannot
;; walk, so the zoom walk steps in with `nth`; a stored path that no longer
;; resolves renders exactly as un-zoomed.

(deftest zoom-into-a-list-element-renders-that-element-rf2-3x7nj-25-2
  ;; The zoom path is the one the renderer's own double-click mints.
  (let [site-id  [:rf.xray/app-db "top"]
        v        {:todos (list {:id 1 :title "alpha"} {:id 2 :title "bravo"})
                  :sibling 1}
        captured (atom nil)
        tree     (ei/render-node {:value v
                                  :panel-id :rf.xray/app-db
                                  :mount-id site-id
                                  :path [] :depth 0
                                  :expansion-map {}
                                  :zoomable? true
                                  :zoom-path-prefix []
                                  :dispatch-fn (fn [ev] (reset! captured ev))
                                  :opts {}})
        target   (first (filter (fn [n]
                                  (let [t (collect-text n)]
                                    (and (str/includes? t "bravo")
                                         (not (str/includes? t "alpha")))))
                                (zoom-target-nodes tree)))]
    ((:on-double-click (second target)) nil)
    (is (= [:rf.xray.edn-inspector/zoom-to :rf.xray/app-db site-id [:todos 1]]
           @captured)
        "the second todo's double-click stores the list element's INDEX path")
    (rf/dispatch-sync @captured)
    (let [text (collect-text (invoke-edn-inspector v {:panel-id :rf.xray/app-db
                                                      :site-id  site-id
                                                      :zoomable? true}))]
      (is (= [true false false] (map #(str/includes? text %) ["bravo" "alpha" ":sibling"]))
          "the body is the zoomed element alone"))))

(deftest unresolvable-zoom-renders-un-zoomed-rf2-3x7nj-25-2
  ;; A stale path — the zoomed key removed by a later event.
  (let [site-id [:rf.xray/app-db "top"]
        _       (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                                   :rf.xray/app-db site-id [:gone]])
        h       (invoke-edn-inspector {:todos {:a 1 :b 2 :c 3 :d 4} :sibling 1}
                                      {:panel-id :rf.xray/app-db
                                       :site-id  site-id
                                       :zoomable? true
                                       :header   [:span "app-db"]})]
    (is (= [nil nil] [(find-attr h :role "navigation") (:data-rf-zoomed (second h))])
        "neither breadcrumbs nor `data-rf-zoomed` claim a zoom the body does not show")
    (is (str/includes? (collect-text h) ":sibling")
        "the body is the full value")
    (is (some #(= "Zoom into [:todos]" (:aria-label (second %))) (zoom-target-nodes h))
        "a further zoom composes its path from the ROOT, not under the stale one")))

;; ---- a diff-mode zoom into a key ADDED this epoch ------------------------
;;
;; The zoom resolves in the AFTER value; the before side is walked along the
;; same path and reads absent, so the zoomed subtree is a plain addition
;; rather than diffed against the whole before-root.

(defn- diff-ops-in-order
  "Every `:data-rf-diff-op` value in `tree`, in document order."
  [tree]
  (vec (keep (fn [n] (let [attrs (second n)]
                       (when (map? attrs) (:data-rf-diff-op attrs))))
             (walk-hiccup tree))))

(deftest diff-mode-zoom-into-a-key-added-this-epoch-reads-as-added-rf2-pmux4
  (let [site-id [:rf.xray/app-db "top"]
        _       (rf/dispatch-sync [:rf.xray.edn-inspector/zoom-to
                                   :rf.xray/app-db site-id [:fresh]])
        h       (invoke-edn-inspector {:kept {:deep 1} :sibling 0 :fresh {:x 1 :y 2}}
                                      {:panel-id  :rf.xray/app-db
                                       :site-id   site-id
                                       :zoomable? true
                                       :before    {:kept {:deep 1} :sibling 0}})
        ops     (diff-ops-in-order h)]
    (is (= #{"added"} (set ops))
        "every node of the zoomed subtree reads as added")
    (is (not (str/includes? (collect-text h) ":sibling"))
        "the before-root's own keys do not ghost into the zoomed subtree")
    (is (= ops (diff-ops-in-order
                 (invoke-edn-inspector {:x 1 :y 2} {:panel-id :rf.xray/app-db
                                                    :added?   true})))
        "the same annotations the `:added?` first-run path paints")))

(deftest widget-zoom-keydown-handler-installed-and-dispatches-on-escape
  ;; While zoomed, Esc on the widget dispatches one `zoom-up` keyed by the
  ;; panel and the persistent site-id; other keys pass through. With no zoom
  ;; there is no handler, so Esc keeps bubbling to an enclosing popup.
  ;; `render-inspector` takes a recording dispatcher; the heads' injected
  ;; `dispatch` is async.
  (let [site-id    [:rf.xray/app-db "top"]
        dispatched (atom [])
        render     (fn [zoom-map]
                     (ei/render-inspector
                       {:value         {:a {:b {:c 1}}}
                        :opts          {:panel-id  :rf.xray/app-db
                                        :site-id   site-id
                                        :zoomable? true}
                        :mount-id      "esc-mount"
                        :dispatch-fn   (fn [ev] (swap! dispatched conj ev))
                        :expansion-map {}
                        :zoom-map      zoom-map
                        :widths        {}}))
        handler    (-> (render {[:rf.xray/app-db site-id] [:a :b]}) second :on-key-down)
        enter      (key-event "Enter")
        esc        (key-event "Escape")]
    (handler (:event enter))
    (handler (:event esc))
    (is (= [[:rf.xray.edn-inspector/zoom-up :rf.xray/app-db site-id]] @dispatched))
    (is (= [false false true true]
           (map deref [(:prevented enter) (:stopped enter) (:prevented esc) (:stopped esc)]))
        "Enter is neither consumed nor stopped; Esc is both")
    (is (nil? (-> (render {}) second :on-key-down))
        "no zoom active, no keydown handler")))

;; ---- engine/project memoisation across re-renders -------------------------
;;
;; A mount's projection is cached on `identical?` inputs, so re-renders with
;; the same `(before, after)` skip the Editscript walk, and a new reference
;; on either side recomputes.

(deftest projection-memo-recomputes-only-when-an-input-changes
  (let [calls        (atom 0)
        real-project engine/project
        before1      {:a 1 :b {:c 2}}
        before2      {:a 1 :b {:c 4}}
        after1       {:a 1 :b {:c 3}}
        after2       {:a 1 :b {:c 5}}
        render       (fn [inner before after]
                       (inner after {:panel-id :rf.xray/app-db :before before}))]
    (with-redefs [engine/project (fn [b a]
                                   (swap! calls inc)
                                   (real-project b a))]
      (let [inner (ei/edn-inspector after1 {:panel-id :rf.xray/app-db :before before1})]
        (dotimes [_ 3] (render inner before1 after1))
        (is (= 1 @calls) "three identity-stable renders compute once")
        (render inner before2 after1)
        (is (= 2 @calls) "a new `before` reference recomputes")
        (render inner before2 after2)
        (is (= 3 @calls) "a new `after` reference recomputes")))))

;; ---- the PUBLIC PROJECTION STAGE is bounded too --------------------------
;;
;; `render-inspector` computes the projection before any walker runs, and
;; `engine/project` short-circuits only `identical?` inputs, so two DISTINCT
;; endless sequences sharing a prefix would compare for ever. `project-for`
;; bounds the pair first — only where BOTH sides could be endless, so an
;; ordinary or mixed pair reaches the engine as the very objects passed.
;; These go through `ei/edn-inspector`, so the projection is computed
;; exactly as a mounted widget computes it.

(defn- projection-inputs-via-public-path
  "Render `after` against `before` through the PUBLIC widget and return
  the `[before after]` pair `engine/project` actually received. The spy
  wraps the real function, so the render completes as it normally would."
  [before after]
  (let [seen         (atom nil)
        real-project engine/project]
    (with-redefs [engine/project (fn [b a]
                                   (reset! seen [b a])
                                   (real-project b a))]
      (let [opts  {:panel-id :rf.xray/app-db :before before}
            inner (ei/edn-inspector after opts)]
        (inner after opts)))
    @seen))

(deftest public-projection-bounds-two-endless-sequences-rf2-bmed1
  ;; At the root, and nested under a map key — the projection walks a
  ;; collapsed child the renderer never descends into.
  (doseq [wrap [identity (fn [s] {:xs s})]]
    (let [seen-b (atom 0)
          seen-a (atom 0)
          before (wrap (counting-seq seen-b 50000))
          after  (wrap (counting-seq seen-a 50000))
          opts   {:panel-id :rf.xray/app-db :before before}]
      ((ei/edn-inspector after opts) after opts)
      (is (<= (max @seen-b @seen-a) render-path-bound)
          (str "realised " @seen-b " BEFORE / " @seen-a " AFTER elements")))))

(deftest container-op-equality-is-bounded-rf2-bmed1
  ;; `classify-container-op` promotes a `:same` projection to `:children`
  ;; when the two sides differ, and a bounded projection reports `:same` for
  ;; two endless sequences sharing a prefix — so that comparison is bounded
  ;; too.
  (let [seen-b (atom 0)
        seen-a (atom 0)
        before (counting-seq seen-b 50000)
        after  (counting-seq seen-a 50000)]
    (ei/render-node {:value      after
                     :before     before
                     :diff?      true
                     :projection (engine/project (take count-bound before)
                                                 (take count-bound after))
                     :panel-id   :test :mount-id "bmed1-a"
                     :path       [] :depth 0
                     :expansion-map {} :opts {}})
    (is (<= (max @seen-b @seen-a) render-path-bound)
        (str "realised " @seen-b " BEFORE / " @seen-a " AFTER elements"))))

(deftest public-projection-leaves-ordinary-pairs-untouched-rf2-bmed1
  ;; An ordinary pair, a `counted?` vector longer than the bound, and a
  ;; mixed lazy / counted pair all reach `engine/project` UNCOPIED — the
  ;; mixed pair whole, because `::unrealised` rows rely on a projection
  ;; over the full inputs.
  (doseq [[before after] [[{:a 1 :b {:c 2} :d [1 2 3]} {:a 1 :b {:c 3} :d [1 2 3]}]
                          [(vec (range 1050)) (assoc (vec (range 1050)) 900 :changed)]
                          [(map identity (range 5)) [0 1 2 3 4 5]]]]
    (is (= [true true] (map identical? [before after]
                            (projection-inputs-via-public-path before after))))))
