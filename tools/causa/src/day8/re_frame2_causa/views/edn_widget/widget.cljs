(ns day8.re-frame2-causa.views.edn-widget.widget
  "Canonical Causa EDN widget — Mike-direction 2026-05-21
  (`cljs-devtools EDN widget`).

  ## Purpose

  One renderer, many call sites — every place Causa shows CLJS data
  (App-DB diff · Trace expanded payloads · Event :db / :fx / coeffects ·
  machine snapshots · Issues ex-data) reaches through this ns so the
  operator learns one expand/collapse + path-click + diff interaction
  and applies it everywhere.

  ## Two engines, one facade

  The widget is a thin Reagent-agnostic facade over two cooperating
  engines:

  1. **`data-display/render`** — Causa's tree walker (collapsible,
     diff-aware, path-clickable). Owns the contract for `browse` +
     `diff` because diff semantics are pairwise over the CLJS values,
     and sticky expansion lives in `:rf.causa/data-display-expansion`.
     Neither concern is in scope for cljs-devtools.

  2. **`cljs-devtools-render`** — `binaryage/cljs-devtools`'s
     formatters, the same engine re-frame-10x uses, walked into hiccup
     by a small JSONML→hiccup adapter (Phase 1 hand-rolled a per-leaf
     classifier and stopped at primitive shapes; that stand-in is
     gone). Owns the CLJS-aware rendering for non-collection leaves +
     the `mini` one-liner: records keep their type tag, persistent
     collections render with native delimiters, metadata gets the
     `^{...}` annotation, IRecord vs IMap is distinguished. Faithful
     CLJS-aware rendering — records · persistent-collections · meta ·
     datafy/nav.

  Callers don't see the split — they call `browse` / `diff` / `mini` /
  `code-block` and the facade routes.

  ## Public API

      (browse {:value v
               :panel-id  :app-db
               :render-id \"epoch-42\"
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
  cljs-devtools dep is Causa-only; the `:devtools/preloads` gate keeps
  it out of production bundles per the contract in `tools/README.md`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.data-display.render :as data-display]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack]]
            [day8.re-frame2-causa.views.edn-widget.cljs-devtools-render
             :as cdt]
            [zprint.core :as zprint]))

;; `inspect` / `inspect-inline` (the panel-facing current-state facade)
;; delegate to `browse` / `mini`, which are defined further down — forward
;; declare so the facade can sit at the top of the file where callers look.
(declare browse mini)

;; ---- universal copy-to-clipboard affordance (rf2-f026h) ------------------
;;
;; re-frame-10x makes every value copyable. Pre-f026h the only copy
;; gesture in Causa lived on the App-DB diff panel's section headers
;; (`diff/render.cljs` → `:rf.causa/copy-{path,value}-to-clipboard`);
;; the Event lens, Trace, segment-inspector, and the Static panels —
;; 7 of 8 EDN surfaces — had none. Rather than thread a copy button
;; into each panel, the affordance rides on the WIDGET ROOT so it
;; lands on every `browse` (and therefore `inspect`) call — Trace's
;; `edn/browse`, the segment-inspector + Event lens + Static panels'
;; `edn/inspect`, all at once.
;;
;; The `:rf.causa/copy-value-to-clipboard` event + the
;; `:rf.causa.fx/copy-to-clipboard` fx are registered process-globally
;; by `app-db-diff-events/install!` (always called from
;; `registry.cljs`), so the dispatch resolves on every surface. The
;; dispatch carries `{:frame :rf/causa}` for the same reason the
;; segment-inspector's affordances do: `:on-click` fires after React
;; pops the frame context, so without the explicit envelope the
;; dispatch would land on `:rf/default`.

(defn copy-affordance
  "A hover-revealed `⎘` copy button that copies `value` to the
  clipboard via `:rf.causa/copy-value-to-clipboard`. Pure hiccup —
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
                           (rf/dispatch [:rf.causa/copy-value-to-clipboard value]
                                        {:frame :rf/causa}))
            :class       "rf-causa-edn-widget-copy"
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
;; panel-side EDN render flows through this namespace — panels MUST
;; NOT reach for `theme.data-inspector` directly. The `inspect` +
;; `inspect-inline` wrappers are the canonical CURRENT-STATE renderers
;; (App-DB · Trace payloads · Event :fx / coeffects · machine snapshots
;; · Issues ex-data). Per Mike-direction 2026-05-21 (rf2-dmso5)
;; current-state rendering is the re-frame-10x cljs-devtools look:
;; collections AND scalars route through cljs-devtools (`browse`).
;;
;; The data-classification sentinels (`:rf/redacted` / `:rf/large` per
;; spec/015) have no cljs-devtools vocabulary, so `inspect` keeps the
;; bespoke chip chrome from `theme.data-inspector` for those —
;; cljs-devtools renders everything else.

(defn inspect
  "Sentinel-aware current-state rendering for one value — the canonical
  L4 detail-panel renderer. Routes the value through cljs-devtools
  (the re-frame-10x look · type-coloured · expandable nested) via the
  `browse` path, EXCEPT for the spec/015 data-classification sentinels
  (`:rf/redacted` / `:rf/large`), which keep their bespoke chip chrome
  from `theme.data-inspector` (cljs-devtools has no sentinel
  vocabulary). Returns hiccup.

  Single-arg form picks the default node-key (`\"root\"`); two-arg
  form lets the caller supply a stable per-mount `node-key` — used as
  the cljs-devtools `render-id` so adjacent inspects in a panel keep
  independent testids.

  Diff rendering (Event panel `:db` before→after smallest-diff) is a
  DIFFERENT contract — call `diff`, which stays on the home-grown
  `data-display/render-tree` engine."
  ([v] (inspect v "root"))
  ([v node-key]
   (cond
     ;; spec/015 sentinels keep their bespoke chip chrome — cljs-devtools
     ;; has no diff/redaction vocabulary.
     (inspector/redacted-sentinel? v)
     (inspector/inspect v node-key)

     (some? (inspector/redacted+size-meta v))
     (inspector/inspect v node-key)

     (some? (inspector/large-meta v))
     (inspector/inspect v node-key)

     ;; Everything else — collections + scalars — render through
     ;; cljs-devtools (current-state browse).
     :else
     (browse {:value     v
              :panel-id  :inspect
              :render-id (str node-key)}))))

(defn inspect-inline
  "Compact one-line current-state rendering for hover tooltips / list
  cells. Sentinels keep their chip shape (via `theme.data-inspector`);
  everything else renders as a one-line cljs-devtools header summary
  (`mini`)."
  [v]
  (cond
    (inspector/redacted-sentinel? v)
    (inspector/inspect-inline v)

    (some? (inspector/redacted+size-meta v))
    (inspector/inspect-inline v)

    (some? (inspector/large-meta v))
    (inspector/inspect-inline v)

    :else
    (mini v)))

;; ---- variant: browse -----------------------------------------------------

(defn browse
  "Render `:value` as a current-state tree via cljs-devtools — the
  re-frame-10x look: type-coloured leaves, native collection
  delimiters, records keeping their type tag, nested structure
  expanded + indented. Browse mode is current-state · no diff
  semantics; cljs-devtools owns the WHOLE value (collections AND
  scalars), per Mike-direction 2026-05-21 (rf2-dmso5).

  Diff semantics (Event panel `:db` before→after smallest-diff) stay
  on the home-grown `data-display/render-tree` engine via `diff`.

  Required: `:value :panel-id :render-id`.
  Optional: `:max-depth` (recursion cap for cljs-devtools' surrogate
            expansion · defaults to `cdt/default-max-depth`)."
  [{:keys [value panel-id render-id max-depth]}]
  ;; Every browse value — map / vector / set / record / scalar —
  ;; routes through cljs-devtools. Collections expand into the nested
  ;; tree; scalars render as a single coloured span. The home-grown
  ;; render-tree is now diff-only.
  ;;
  ;; rf2-f026h — the universal copy affordance rides on this root, so
  ;; every browse/inspect surface (Trace, segment-inspector, Event
  ;; lens, Static panels) gets a copy-to-clipboard gesture. The
  ;; container is `position:relative` to anchor the absolutely-
  ;; positioned `⎘` button at its top-right; padding-right reserves
  ;; the gutter so the button never overlaps a wide value's first line.
  (let [container-id (str "rf-causa-edn-widget-browse-"
                          (name (or panel-id :unknown))
                          "-"
                          (str (or render-id "")))]
    [:div {:data-testid container-id
           :style {:position    "relative"
                   :font-family mono-stack
                   :font-size   "12px"
                   :color       (:text-primary tokens)
                   :line-height 1.4
                   :padding-right "26px"}}
     (copy-affordance value (str container-id "-copy"))
     (if (some? max-depth)
       (cdt/value->tree-hiccup value max-depth)
       (cdt/value->tree-hiccup value))]))

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
  "One-liner inline rendering of `value` via cljs-devtools' formatters.
  No expansion, no diff — used in chip rows / table cells where the
  full tree would crowd the layout. Returns hiccup `[:span ...]` shape
  so callers can embed inline.

  When the formatter's coloured-span output renders longer than the
  caller's pixel budget the parent's `text-overflow: ellipsis` kicks
  in via the surrounding container; we still cap raw `pr-str` length
  in the `:title` attribute so hover reveals up to `max-len` characters
  of the underlying value."
  ([value] (mini value 80))
  ([value max-len]
   (let [pr        (try (pr-str value) (catch :default _ (str value)))
         truncated (if (<= (count pr) max-len)
                     pr
                     (str (subs pr 0 max-len) "…"))]
     [:span {:data-testid "rf-causa-edn-widget-mini"
             :title       pr
             :style       {:font-family mono-stack
                           :font-size   "11px"
                           :color       (:text-primary tokens)
                           :white-space "nowrap"
                           :overflow    "hidden"
                           :text-overflow "ellipsis"
                           :max-width   "100%"
                           :display     "inline-block"
                           :vertical-align "bottom"}
             ;; A `data-pr` attribute carries the raw pr-str so test
             ;; assertions + a11y readers can still reach the value
             ;; even when the visible content is cljs-devtools markup.
             :data-pr     truncated}
      (cdt/value->hiccup value)])))

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
;;      colour classifications mapped onto the theme tokens (keywords
;;      violet, strings green, numbers cyan, builtins violet). The
;;      bracketed phrase in the rf2-6snc8 acceptance ("highlight.js …
;;      or an embeddable Clojure-mode subset") explicitly authorises
;;      this subset — and keeping the highlighter in-bundle avoids the
;;      cost of a JS-side highlight.js dep on the dev classpath.

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
      (zprint/zprint-file-str src "rf-causa-handler-source" {:width 72})
      (catch :default _ src))))

(defn highlight-clojure-token
  "Per-token colour resolution for the in-bundle Clojure syntax
  highlighter (source-text rendering only; CLJS-value rendering goes
  through `cljs-devtools-render`). Pure data → token-keyword for the
  token-type classification. Public for unit tests."
  [tok-type]
  (case tok-type
    :keyword  :accent-violet
    :string   :green
    :number   :cyan
    :comment  :text-tertiary
    :symbol   :text-primary
    :paren    :text-tertiary
    :builtin  :accent-violet
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
           testid "rf-causa-edn-widget-code"}}]
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
