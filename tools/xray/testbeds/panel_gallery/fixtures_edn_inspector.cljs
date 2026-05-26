(ns panel-gallery.fixtures-edn-inspector
  "Pure fixture builders for the edn-inspector widget gallery
  (rf2-hp4ow — widget-isolation coverage).

  The edn-inspector widget (`day8.re-frame2-xray.views.edn-inspector`)
  is exercised indirectly by every L4 panel that mounts it — App-DB,
  Reactive, Trace, Machines, Issues, Routing all route their CLJS
  value rendering through it. The dedicated gallery here gives the
  widget its own isolated surface: one variant per value shape /
  opts combination, side-by-side, so an operator can scan the
  renderer's response without the surrounding panel chrome.

  ## Fixture shape

  Each builder returns a map:

      {:value   <cljs-value>          ;; the value the widget renders
       :opts    {<opts-key> <val>}    ;; optional; passed verbatim
       :before  <cljs-value>}         ;; optional; switches to diff mode

  The variant fires a single testbed-only seed event
  (`:panel-gallery.edn-inspector/seed!`) into the variant frame
  carrying the fixture map; the gallery's `:panel-gallery.edn-
  inspector/Panel` view subscribes to that slot and renders
  `[edn-inspector value (assoc opts :before before)]` (diff mode
  engages automatically when `:before` is present).

  ## Coverage axes

  - **Scalars** — every leaf type the widget classifies via
    `collection-kind` (string, number, keyword, symbol, nil,
    boolean) so the syntax-highlight palette is visible at a
    glance.
  - **Maps** — small / medium / large / nested + the size-elision
    sentinel.
  - **Vectors / lists / seqs / sets** — each container kind exercises
    its own bracket pair + colour token.
  - **Records** — the `#user.Rec{...}` header path.
  - **UUID + inst** — the default `IXrayEdnInspector` formatters
    (`edn-inspector-default-formatters`) render compact headers +
    full bodies.
  - **Diff mode** — before/after pairs that surface `:added`,
    `:modified`, `:removed`, and `:same` rows via the rf2-zuh1e
    `children-of-pair` walk.
  - **Opts demos** — one variant per individual opt
    (`:zoomable?`, `:popup-affordance?`, `:card?` + `:header`,
    `:site-id`, `:default-expanded-depth`, large-sentinel
    routing) so the visual effect of each switch is isolated.")

;; =========================================================================
;; record type — used by the record fixture below
;; =========================================================================
;;
;; Declared at ns-load so the record renders consistently across reloads;
;; `defrecord` is idempotent at the same shape so shadow-cljs hot-reload
;; survives.

(defrecord GalleryUser [id name email])

;; =========================================================================
;; scalar fixtures
;; =========================================================================

(defn scalar-mix
  "Vector of every scalar kind the widget classifies. Rendered as a
  top-level container so each leaf renders side-by-side and the
  per-type colour token is visible at a glance."
  []
  {:value [;; nil — `:syntax-nil` token
           nil
           ;; boolean — `:syntax-boolean` token
           true
           false
           ;; integer + float — `:syntax-number` token
           42
           -7
           3.14159
           ;; string — `:syntax-string` token
           "hello"
           "with \"escaped\" quotes"
           ;; keyword — `:syntax-keyword` token
           :simple
           :ns/qualified
           ;; symbol — `:syntax-symbol` token
           'plain-sym
           'my.ns/qualified-sym]
   :opts  {:default-expanded-depth 4}})

;; =========================================================================
;; map fixtures — small / medium / large / nested
;; =========================================================================

(defn map-small
  "Three-key map. Inline-preview territory — the widget's width-aware
  heuristic should keep it on one line under default measurements."
  []
  {:value {:counter 5
           :name    "Ada"
           :active? true}})

(defn map-medium
  "Twelve-key flat map. Wide enough to force expansion past the
  default `:max-inline-width`, narrow enough to fit comfortably
  on one screen."
  []
  {:value (into {} (for [i (range 12)]
                     [(keyword (str "k-" i)) (* i 10)]))})

(defn map-large
  "Fifty-key flat map. Exercises the expanded-body scroll behaviour."
  []
  {:value (into {} (for [i (range 50)]
                     [(keyword (str "metric-" i)) (str "value-" i)]))})

(defn map-nested
  "Six-level nested map. Exercises the depth ceiling — at depth ≥ 8
  the widget renders `▸ {…N keys}` summaries; ancestors up to that
  depth open by default under the rf2-kbdk8 width-aware heuristic."
  []
  {:value {:tenant {:acme {:department {:engineering {:team {:platform
                                                              {:project :xray
                                                               :status :active
                                                               :leads ["Ada" "Grace"]}}}}}}}})

(defn map-large-elided
  "Single-key map whose only entry is the `:rf.size/large-elided`
  sentinel (per Spec 015). The widget routes this through the
  sentinel chip renderer rather than the regular map path."
  []
  {:value {:rf.size/large-elided {:source        :app-db
                                  :path          [:report/cache :report-42]
                                  :original-size 12480293
                                  :bytes         12480293
                                  :handle        :report/payload-42
                                  :reason        :over-budget}}})

;; =========================================================================
;; container fixtures — vector / list / seq / set
;; =========================================================================

(defn vector-mixed
  "Vector whose elements span every scalar kind plus a nested map."
  []
  {:value [:apple "pear" 42 nil true {:id 7 :name "Ada"}]})

(defn list-of-events
  "List of event vectors — the canonical shape Xray's Event tab
  feeds the widget."
  []
  {:value (list [:counter/inc]
                [:user/sign-in {:email "ada@example.com"}]
                [:cart/add-item :apple 2]
                [:rf/route-change {:path "/cart"}])})

(defn lazy-seq-fib
  "Realised prefix of an infinite fibonacci seq. The widget never
  forces a lazy tail — `count` on a lazy-seq is realised lazily
  too, but the fixture takes the first 12 elements so the renderer
  walks a finite seq."
  []
  {:value (let [fib (fn fib [a b] (lazy-seq (cons a (fib b (+ a b)))))]
            (take 12 (fib 0 1)))})

(defn set-of-tags
  "Set of keyword tags — exercises the `#{...}` bracket pair +
  unordered-iteration path."
  []
  {:value #{:dev :prod :staging :beta :alpha :internal :public :legacy}})

;; =========================================================================
;; record fixture
;; =========================================================================

(defn record-instance
  "Single `GalleryUser` record. The widget renders the header
  `#panel-gallery.fixtures-edn-inspector.GalleryUser{...}` and lays
  the slots out below."
  []
  {:value (->GalleryUser 7 "Ada Lovelace" "ada@example.com")})

;; =========================================================================
;; uuid + inst fixtures — default IXrayEdnInspector formatters
;; =========================================================================

(defn uuid-instance
  "Single random UUID. The default formatter renders a compact
  `#uuid \"…<last-8>\"` header (rf2-x16b1)."
  []
  {:value (random-uuid)})

(defn inst-instances
  "Mixed insts spanning the formatter's relative-time bucketing —
  recent, hours-ago, days-ago, ISO-fallback. The default formatter
  picks the bucket based on `(.getTime (js/Date.))` at render
  time (rf2-x16b1)."
  []
  {:value (let [now      (.getTime (js/Date.))
                ms-per-h (* 60 60 1000)
                ms-per-d (* 24 ms-per-h)]
            {:recent (js/Date. (- now 3000))                  ;; ~ just now / 3s ago
             :mins   (js/Date. (- now (* 5 60 1000)))         ;; 5m ago
             :hours  (js/Date. (- now (* 3 ms-per-h)))        ;; 3h ago
             :days   (js/Date. (- now (* 5 ms-per-d)))        ;; 5d ago
             :iso    (js/Date. (- now (* 200 ms-per-d)))      ;; ISO fallback
             :future (js/Date. (+ now (* 2 60 1000)))})})     ;; in 2m

;; =========================================================================
;; diff fixtures — :before + :after pairs
;; =========================================================================
;;
;; Each diff fixture surfaces a different `children-of-pair` op
;; (rf2-zuh1e): added, modified, removed, same. Diff mode renders
;; gutter glyphs (`+`/`-`/`~`/`◴`) + `← changed from <prior>`
;; annotations on modified leaves.

(defn diff-map-mixed-ops
  "Diff pair surfacing every op-tag in a single map: `:added` (new
  `:flash`), `:modified` (scalar bump + nested name change),
  `:removed` (`:legacy-flag` dropped), `:same` (untouched user/id)."
  []
  {:before {:counter     5
            :user        {:id 7 :name "Ada"}
            :legacy-flag true}
   :value  {:counter 6
            :user    {:id 7 :name "Ada Lovelace"}
            :flash   {:level :ok :text "Order placed"}}})

(defn diff-vector-element-changes
  "Diff pair on a vector — `:added` (new tail item), `:modified`
  (middle item's `:qty` bumped), `:same` (head item unchanged).
  Vector diffs walk pair-wise so position matters."
  []
  {:before [{:id :apple :qty 1}
            {:id :pear  :qty 1}]
   :value  [{:id :apple :qty 1}
            {:id :pear  :qty 3}
            {:id :grape :qty 2}]})

(defn diff-deep-children-changed
  "Diff pair where the change sits six levels deep. Ancestor chain
  force-expands over a changed descendant; the `◴` (children-changed)
  glyph paints the gutter for every intermediate map."
  []
  (let [path  [:tenant :acme :department :eng :team :platform :project :xray :status]
        deep  (fn [v] (assoc-in {} path v))]
    {:before (deep :idle)
     :value  (deep :active)}))

;; =========================================================================
;; opts-demo fixtures
;; =========================================================================
;;
;; One fixture per opt the widget exposes. Each pins a single visual
;; difference — switching ONE opt and renders the same underlying
;; value so the eye can isolate the change.

(defn opts-base-value
  "The shared base value reused across opts-demo variants. Wide
  enough to surface every opt's visual effect (the popup affordance
  sits over content; the card chrome wraps it; the header ribbon
  labels it; zoom needs a non-trivial subtree to zoom into)."
  []
  {:cart    {:items    [{:id :apple :qty 1}
                        {:id :pear  :qty 2}
                        {:id :grape :qty 3}]
             :discount "SAVE10"
             :totals   {:subtotal 23.50
                        :tax      2.35
                        :total    25.85}}
   :user    {:id 7 :name "Ada Lovelace" :email "ada@example.com"}
   :session {:expires-at 1735689600000
             :renewed?   true}})

(defn opts-zoomable
  "`:zoomable? true` — each container gains a `⊙` zoom-affordance
  button next to the expand triangle (rf2-h71e0)."
  []
  {:value (opts-base-value)
   :opts  {:zoomable? true}})

(defn opts-popup-affordance
  "`:popup-affordance? true` — a `↗` icon button sits at the
  widget's top-right corner (rf2-l4625)."
  []
  {:value (opts-base-value)
   :opts  {:popup-affordance? true}})

(defn opts-card
  "`:card? true` — the outer container picks up the inspector-card
  chrome (rf2-63ie5): `:bg-1` background, `1px` border, `8px`
  radius."
  []
  {:value (opts-base-value)
   :opts  {:card? true}})

(defn opts-header
  "`:header \"Shopping cart snapshot\"` — opt-in three-shade card
  chrome with a labelled ribbon (rf2-okq7p)."
  []
  {:value (opts-base-value)
   :opts  {:header "Shopping cart snapshot"}})

(defn opts-header-hiccup
  "`:header` as composed hiccup — the widget treats the value as
  opaque hiccup so consumers can mix label + chip + affordance
  (rf2-okq7p)."
  []
  {:value (opts-base-value)
   :opts  {:header [:span
                    [:strong {:style {:font-size "13px"}} "Shopping cart"]
                    [:code {:style {:margin-left "8px"
                                    :padding "1px 6px"
                                    :background "rgba(127,127,127,0.18)"
                                    :border-radius "3px"
                                    :font-size "11px"}}
                     ":rf/cart"]]}})

(defn opts-site-id
  "`:site-id :gallery.demo` — stable site-id survives a remount
  (rf2-pvsxs)."
  []
  {:value (opts-base-value)
   :opts  {:site-id :panel-gallery.edn-inspector/site-demo}})

(defn opts-shallow-depth
  "`:default-expanded-depth 1` — legacy depth-driven behaviour
  (rf2-kbdk8). The widget collapses everything past depth 1, so
  deeper nodes render as `▸ {…N keys}` summaries."
  []
  {:value (opts-base-value)
   :opts  {:default-expanded-depth 1}})

(defn opts-combined
  "Composed opts (`:header` + `:popup-affordance?` + `:zoomable?`)
  — all three affordances coexist (header ribbon up top, popup
  arrow at top-right, zoom triangle on every container)."
  []
  {:value (opts-base-value)
   :opts  {:header            "All affordances at once"
           :popup-affordance? true
           :zoomable?         true}})
