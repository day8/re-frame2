(ns day8.re-frame2-xray.views.edn-widget.widget
  "Canonical Xray EDN widget facade (rf2-oqa60 phase 1 — thin wrapper
  over the first-class `views.data-display` widget).

  ## Purpose

  One renderer, many call sites — every place Xray shows CLJS data
  (App-DB · Trace expanded payloads · Event :db / :fx / coeffects ·
  machine snapshots · Issues ex-data) reaches through this ns so the
  operator learns one expand/collapse + diff interaction and applies
  it everywhere.

  ## One engine, one facade (post-rf2-oqa60)

  Pre-rf2-oqa60 the widget composed two engines:
  `data-display/render` (diff-aware tree walker) and
  `cljs-devtools-render` (`binaryage/cljs-devtools`-backed formatters
  for current-state browse). The cljs-devtools impedance-mismatch
  burned three rounds (rf2-dw8n7, rf2-oswhk, this rebuild); phase 1
  ships the roll-your-own `views.data-display` widget and the facade
  delegates `browse` / `inspect` / `mini` / `inspect-inline` to it.

  Diff (`edn/diff`) still calls `data-display/render-tree` for now;
  phase 5 subsumes diff into the new widget (D5=a per rf2-sndui).

  ## Public API

      (browse {:value v
               :panel-id  :app-db
               :render-id \"epoch-42\"      ; accepted but ignored
                                            ; (widget auto-generates
                                            ; mount-id; D4=a)
               :default-depth 3})

      (diff   {:before before-v
               :after  after-v
               :panel-id  :event-db
               :render-id \"epoch-42\"
               :default-depth 3})

      (mini   v)    ;; one-liner (no expansion, no diff)

      (code-block {:source \"(reg-event-db :foo …)\"
                   :lang   :clojure})

  ## Posture

  Pre-alpha · NO back-compat shims · dev-only · bundle-isolated. The
  cljs-devtools dep is dropped with the phase 1 cut-over (rf2-oqa60);
  zprint stays for `code-block` source-text rendering."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.data-display.render :as data-display]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack]]
            ;; rf2-oqa60 phase 1 — current-state browse / inspect /
            ;; mini delegate to the first-class data-display widget.
            ;; The cljs-devtools-render adapter + theme.data-inspector
            ;; chrome are superseded; the legacy ns'es are deleted
            ;; alongside this commit.
            [day8.re-frame2-xray.views.data-display :as dd]
            [zprint.core :as zprint]))

;; ---- subscription bridge — superseded by the new data-display widget ----
;;
;; Pre-rf2-oqa60 this section glued the cljs-devtools walker to the
;; shared expansion slot. With the phase 1 cut-over the new widget at
;; `views.data-display` owns its own `:rf.xray.data-display/expansion`
;; slot + toggle/reset events; the facade delegates and the cljs-
;; devtools-render adapter is deleted.

;; `inspect` / `inspect-inline` (the panel-facing current-state facade)
;; delegate to `browse` / `mini`, which are defined further down — forward
;; declare so the facade can sit at the top of the file where callers look.
(declare browse mini)

;; ---- universal copy-to-clipboard affordance (rf2-f026h) ------------------
;;
;; re-frame-10x makes every value copyable. Pre-f026h the only copy
;; gesture in Xray lived on the App-DB diff panel's section headers
;; (`diff/render.cljs` → `:rf.xray/copy-{path,value}-to-clipboard`);
;; the Event lens, Trace, segment-inspector, and the Static panels —
;; 7 of 8 EDN surfaces — had none. Rather than thread a copy button
;; into each panel, the affordance rides on the WIDGET ROOT so it
;; lands on every `browse` (and therefore `inspect`) call — Trace's
;; `edn/browse`, the segment-inspector + Event lens + Static panels'
;; `edn/inspect`, all at once.
;;
;; The `:rf.xray/copy-value-to-clipboard` event + the
;; `:rf.xray.fx/copy-to-clipboard` fx are registered process-globally
;; by `app-db-diff-events/install!` (always called from
;; `registry.cljs`), so the dispatch resolves on every surface. The
;; dispatch carries `{:frame :rf/xray}` for the same reason the
;; segment-inspector's affordances do: `:on-click` fires after React
;; pops the frame context, so without the explicit envelope the
;; dispatch would land on `:rf/default`.

(defn copy-affordance
  "A hover-revealed `⎘` copy button that copies `value` to the
  clipboard via `:rf.xray/copy-value-to-clipboard`. Pure hiccup —
  positioned `absolute` top-right of a `position:relative` parent. The
  `testid` is derived from the widget's render container id so tests can
  target the per-render affordance. The `⎘` glyph is the same
  click-to-copy mark spec 007:668 uses for the namespace-fade keyword
  copy."
  [value testid]
  [:button {:data-testid testid
            :aria-label  "Copy value to clipboard"
            :title       "Copy value"
            :on-click    (fn [^js e]
                           (.stopPropagation e)
                           (rf/dispatch [:rf.xray/copy-value-to-clipboard value]
                                        {:frame :rf/xray}))
            :class       "rf-xray-edn-widget-copy"
            :style       {:position      "absolute"
                          :top           "2px"
                          :right         "2px"
                          :z-index       1
                          :background    (:bg-3 tokens)
                          :color         (:text-secondary tokens)
                          :border        (str "1px solid " (:border-subtle tokens))
                          :border-radius "3px"
                          :cursor        "pointer"
                          :font-family   mono-stack
                          :font-size     "11px"
                          :line-height   1
                          :padding       "2px 5px"
                          ;; Recede until the parent is hovered. The
                          ;; in-bundle stylesheet (theme/css) reveals it
                          ;; on `:hover` / `:focus-within`; tests assert
                          ;; on presence + dispatch, not the CSS reveal.
                          :opacity       0.55}}
   "⎘"])

;; ---- panel-facing facade — inspect / inspect-inline ----------------------
;;
;; Per Mike-direction rf2-9wsdy ("one widget, many call sites") every
;; panel-side EDN render flows through this namespace. Per rf2-oqa60
;; phase 1 the facade delegates to the first-class `views.data-display`
;; widget — the same engine handles every type (scalars, collections,
;; sentinels) so this thin wrapper no longer needs sentinel-routing
;; branches. Phases 2-4 migrate call sites to call `dd/data-display`
;; directly; this facade is the bridge through that transition.

(defn inspect
  "Sentinel-aware current-state rendering for one value — the canonical
  L4 detail-panel renderer. Routes through the first-class
  data-display widget (rf2-oqa60 phase 1) which classifies every type
  natively, including the spec/015 sentinels (`:rf/redacted` and
  `:rf/large`) as first-class chip chrome (D3=a per rf2-sndui).

  Phase 1 delegates the legacy facade to the new widget so existing
  call sites compile unchanged; phases 2-4 migrate each surface
  (Trace, Sub, Machine) to call `data-display/data-display` directly.

  Single-arg form picks a default panel-id; two-arg / three-arg forms
  accept a node-key string (passed through as a stable per-mount
  qualifier) and an opts map. The `:copy?` key from the old facade is
  accepted but ignored — copy chrome is deferred to a follow-on bead
  (the popup phase, D6=a).

  Diff rendering stays on `edn/diff` for phase 1; phase 5 subsumes
  diff into the new widget (D5=a per rf2-sndui)."
  ([v] (inspect v "root" nil))
  ([v node-key] (inspect v node-key nil))
  ([v node-key _opts]
   [dd/data-display v
    {:panel-id (keyword (str "rf.xray.inspect/" node-key))
     :default-expanded-depth 3}]))

(defn inspect-inline
  "Compact one-line current-state rendering for hover tooltips / list
  cells. Folds sentinel routing in (D2=a per rf2-sndui) — sentinels
  render their chip chrome via the new widget's `mini` overload; all
  other values render as a colour-coded one-liner."
  [v]
  (dd/mini v))

;; ---- variant: browse -----------------------------------------------------

(defn browse
  "Render `:value` as a current-state tree via the first-class
  data-display widget (rf2-oqa60 phase 1).

  Each collection node renders a `▸` / `▾` triangle that dispatches
  `:rf.xray.data-display/toggle-node` against the per-mount path —
  operator drill-downs persist across re-renders in the
  `:rf.xray.data-display/expansion` slot.

  Diff semantics (Event panel `:db` before→after smallest-diff) stay
  on the home-grown `data-display/render-tree` engine via `diff` for
  phase 1 (D5=a per rf2-sndui — phase 5 subsumes diff).

  Required: `:value`.
  Optional: `:panel-id` (defaults `:rf.xray/browse`),
            `:render-id` (passed through as a stable per-mount
                          qualifier; ignored under the new widget
                          which auto-generates mount-ids, but accepted
                          for facade-call-site compatibility),
            `:default-depth` (renamed to `:default-expanded-depth`
                              under the new widget, defaults to 1),
            `:max-depth` (hard recursion cap; defaults to 16),
            `:copy?` (accepted but no-op under the new widget — copy
                      chrome is deferred per the rf2-oqa60 phasing)."
  [{:keys [value panel-id render-id default-depth max-depth]
    :or   {default-depth 1
           max-depth     16}}]
  [dd/data-display value
   {:panel-id (or panel-id
                  (keyword (str "rf.xray.browse/" (or render-id "anon"))))
    :default-expanded-depth default-depth
    :max-depth max-depth}])

;; ---- variant: diff -------------------------------------------------------

(defn diff
  "Render a before -> after diff tree. Annotates changed branches with
  the §10.3 gutter glyphs (+ added / - removed / ~ modified / ◴ has
  changed descendant) and a `← changed from <prior>` chip on modified
  leaves. Returns hiccup.

  Diff semantics live in `data-display/render-tree` — the engine walks
  before/after pairwise and threads diff-op classification through the
  tree. cljs-devtools doesn't know about diff (it operates on a single
  value), so diff stays here.

  Required: `:before :after :panel-id :render-id`.
  Optional: `:default-depth` (defaults to 2 per §10.4)."
  [{:keys [before after panel-id render-id default-depth]
    :or   {default-depth 2}}]
  (data-display/render-tree
    {:value         after
     :before        before
     :diff?         true
     :panel-id      panel-id
     :render-id     render-id
     :default-depth default-depth}))

;; ---- variant: mini -------------------------------------------------------

(defn mini
  "One-liner inline rendering of `value` via the first-class
  data-display widget (rf2-oqa60 phase 1). Returns hiccup
  `[:span ...]` so callers embed inline. Sentinels (`:rf/redacted`,
  `:rf/large`) keep their chip chrome inline; other values render as
  a colour-coded inline-preview."
  ([value] (mini value 80))
  ([value max-len]
   (dd/mini value max-len)))

;; ---- code-block (handler / interceptor source rendering) ----------------
;;
;; `code-block` renders Clojure SOURCE TEXT, not a CLJS value, so the
;; cljs-devtools formatters API doesn't apply (it operates on live
;; values, not strings).
;;
;; The pipeline is two-stage:
;;
;;   1. **zprint pre-format** — `format-source` runs the source string
;;      through `zprint/zprint-file-str` so a poorly-formatted
;;      registration (everything on one line, weird indentation,
;;      mid-expression breaks) becomes canonical-looking before
;;      rendering. zprint is dev-only — bundle-isolated from production
;;      via the `:devtools/preloads` gate (rf2-6snc8). On a zprint
;;      failure (parse error / unsupported reader macro) `format-source`
;;      falls through to the original source string so a bad input
;;      never strands the widget.
;;
;;   2. **In-bundle Clojure-mode tokenizer** — `tokenize-clojure` is a
;;      lightweight ~140-LoC source-text lexer that emits per-token
;;      colour classifications mapped onto the theme tokens. Per the
;;      rf2-93jp0 palette split, keywords paint on `:syntax-keyword`
;;      (red family), strings on `:syntax-string` (blue family),
;;      numbers on `:syntax-number` (cool-blue), and builtins on
;;      `:accent` (chrome blue, macro-call emphasis) — each visually
;;      distinct, both light and dark, mirroring the Figma authority's
;;      `.syntax-*` CSS classes. The bracketed phrase in the rf2-6snc8
;;      acceptance ("highlight.js … or an embeddable Clojure-mode
;;      subset") explicitly authorises this subset — and keeping the
;;      highlighter in-bundle avoids the cost of a JS-side highlight.js
;;      dep on the dev classpath.

(defn format-source
  "Pre-format a Clojure source string via zprint so the rendered
  code-block reads canonically regardless of how the registration was
  laid out. On parse failure (unsupported reader macro, mid-form
  splice, etc.) returns the original source unchanged so a bad input
  never strands the widget. Pure fn; testable.

  `zprint-file-str` is used (rather than `zprint-str` on a value) so
  the input string is treated as raw source — comments, blank lines,
  and multiple top-level forms survive the round-trip. The width is
  capped at 72 columns so the rendered block fits inside the Event
  panel's narrow handler-source slot without horizontal scroll."
  [src]
  (if-not (and (string? src) (seq src))
    src
    (try
      (zprint/zprint-file-str src "rf-xray-handler-source" {:width 72})
      (catch :default _ src))))

(defn highlight-clojure-token
  "Per-token colour resolution for the in-bundle Clojure syntax
  highlighter (source-text rendering only; CLJS-value rendering goes
  through `cljs-devtools-render`). Pure data → token-keyword for the
  token-type classification. Public for unit tests.

  ## Palette (rf2-93jp0)

  Each token type maps to a dedicated `:syntax-*` token from
  `theme/tokens.cljc` so the rendered hues match the Figma authority's
  `.syntax-*` CSS block exactly (keyword red / string blue / number
  cool-blue) in BOTH the light and dark theme. Keywords and builtins
  resolve to DIFFERENT hues — keyword on `:syntax-keyword` (red family,
  per Figma), builtin on `:accent` (the chrome blue, reading as
  macro-call emphasis) — so a `:foo` keyword and a `reg-event-db`
  builtin no longer paint identically against a real editor.

  The fallthrough is `:text-primary`, matching a real editor where
  plain symbols carry no special colour."
  [tok-type]
  (case tok-type
    :keyword  :syntax-keyword          ; Figma .syntax-keyword (red family)
    :string   :syntax-string           ; Figma .syntax-string  (blue family)
    :number   :syntax-number           ; Figma .syntax-number  (cool-blue)
    :comment  :text-tertiary
    :symbol   :text-primary
    :paren    :text-tertiary
    :builtin  :accent                  ; macro-call emphasis (chrome accent)
    :text-primary))

(def clojure-builtins
  "Recognised Clojure builtins for the in-bundle source-text
  highlighter. Public so tests can assert membership."
  #{"def" "defn" "defn-" "defmacro" "let" "if" "when" "when-not"
    "cond" "case" "do" "loop" "recur" "fn" "fn*" "reify"
    "deftype" "defrecord" "ns" "require" "reg-event-db"
    "reg-event-fx" "reg-event-ctx" "reg-sub" "reg-fx" "reg-view"
    "reg-flow" "reg-machine" "dispatch" "dispatch-sync" "subscribe"
    "assoc" "assoc-in" "update" "update-in" "get" "get-in"
    "->" "->>" "some->" "some->>"})

(defn classify-token
  "Classify a Clojure source-text token literal string. Pure fn;
  lightweight — handles the cases that matter for handler-source
  rendering (keywords, strings, numbers, comments, parens, builtins,
  plain symbols)."
  [s]
  (cond
    (str/blank? s)                            :whitespace
    (str/starts-with? s ";")                  :comment
    (str/starts-with? s ":")                  :keyword
    (and (str/starts-with? s "\"")
         (str/ends-with? s "\""))             :string
    (re-matches #"^-?\d+(?:\.\d+)?$" s)       :number
    (contains? clojure-builtins s)            :builtin
    (#{"(" ")" "[" "]" "{" "}"} s)            :paren
    :else                                      :symbol))

(defn tokenize-clojure
  "Split a Clojure source string into a vector of `[token-type
  literal]` pairs. Pure fn; greedy single-pass tokenizer good enough
  for the inline-source rendering. Strings + comments are matched
  before symbols so they capture greedily."
  [src]
  (loop [acc [] s src]
    (cond
      (empty? s) acc

      ;; whitespace run
      (re-find #"^\s+" s)
      (let [m (re-find #"^\s+" s)]
        (recur (conj acc [:whitespace m]) (subs s (count m))))

      ;; line comment
      (str/starts-with? s ";")
      (let [nl  (str/index-of s "\n")
            end (or nl (count s))
            m   (subs s 0 end)]
        (recur (conj acc [:comment m]) (subs s end)))

      ;; string
      (str/starts-with? s "\"")
      (let [m (or (re-find #"^\"(?:[^\"\\]|\\.)*\"" s)
                  (subs s 0 1))]
        (recur (conj acc [:string m]) (subs s (count m))))

      ;; paren / bracket / brace
      (#{\( \) \[ \] \{ \}} (first s))
      (let [m (subs s 0 1)]
        (recur (conj acc [:paren m]) (subs s 1)))

      ;; keyword
      (str/starts-with? s ":")
      (let [m (or (re-find #"^:[a-zA-Z0-9_./?!*+<>=&%-]+" s) ":")]
        (recur (conj acc [:keyword m]) (subs s (count m))))

      ;; number
      (re-find #"^-?\d" s)
      (let [m (or (re-find #"^-?\d+(?:\.\d+)?" s) (subs s 0 1))]
        (recur (conj acc [:number m]) (subs s (count m))))

      ;; symbol / builtin
      :else
      (let [m (or (re-find #"^[a-zA-Z_!?*+<>=&%-][a-zA-Z0-9_./?!*+<>=&%-]*" s)
                  (subs s 0 1))
            t (classify-token m)]
        (recur (conj acc [t m]) (subs s (count m)))))))

(defn code-block
  "Render `:source` as a syntax-highlighted code block. Pure hiccup —
  Clojure-only highlighter for source-text strings (cljs-devtools
  operates on live CLJS values, not source text, so it doesn't apply
  here).

  When `:lang` is `:clojure` (the default) the source is pre-formatted
  via zprint (`format-source`) before the in-bundle tokenizer runs, so
  a poorly-formatted registration still renders cleanly. Other
  languages render as mono text (unformatted).

  Required: `:source`.
  Optional: `:lang` (defaults to `:clojure` — only `:clojure` highlights
            today · others render mono-text), `:testid`."
  [{:keys [source lang testid]
    :or   {lang :clojure
           testid "rf-xray-edn-widget-code"}}]
  (if (or (not (string? source)) (str/blank? source))
    [:div {:data-testid (str testid "-empty")
           :style {:font-family mono-stack
                   :font-size   "11px"
                   :color       (:text-tertiary tokens)
                   :font-style  "italic"}}
     "(source unavailable)"]
    (let [formatted  (if (= :clojure lang) (format-source source) source)
          tokens-seq (if (= :clojure lang)
                       (tokenize-clojure formatted)
                       [[:symbol formatted]])]
      [:pre {:data-testid testid
             :data-lang   (name lang)
             :data-formatted (str (and (= :clojure lang)
                                       (not= formatted source)))
             :style {:font-family mono-stack
                     :font-size   "11px"
                     :line-height 1.5
                     :color       (:text-primary tokens)
                     :background  (:bg-3 tokens)
                     :border      (str "1px solid " (:border-subtle tokens))
                     :border-radius "3px"
                     :padding     "8px 10px"
                     :margin      0
                     ;; Clamp to the containing block + scroll long lines
                     ;; WITHIN the panel rather than overflowing it. Without
                     ;; `max-width:100%` the `white-space:pre` block grows to
                     ;; its longest line's intrinsic width, expanding every
                     ;; flex ancestor (the Event-panel pipeline is a flex
                     ;; column) past the panel edge — the first line is then
                     ;; clipped left under the step-rail gutter and later
                     ;; lines run off the right (rf2-l7ha9). The `min-width:0`
                     ;; companion lives on the flex-item ancestors so this
                     ;; `overflow-x:auto` can actually engage.
                     :max-width   "100%"
                     :box-sizing  "border-box"
                     :overflow-x  "auto"
                     :white-space "pre"}}
       (into [:code]
             (for [[idx [t literal]] (map-indexed vector tokens-seq)]
               (with-meta
                 (if (= t :whitespace)
                   [:span literal]
                   [:span {:style {:color (get tokens
                                               (highlight-clojure-token t)
                                               (:text-primary tokens))}}
                    literal])
                 {:key idx})))])))

;; ---- dispatch by variant -------------------------------------------------

(defn render
  "Single-entry dispatch by `:variant`. Routes to `browse` / `diff` /
  `mini`. Helpful for call sites that pick the variant at runtime."
  [{:keys [variant] :or {variant :browse} :as opts}]
  (case variant
    :browse (browse opts)
    :diff   (diff   opts)
    :mini   (mini   (:value opts) (or (:max-len opts) 80))
    (browse opts)))
