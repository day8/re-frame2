(ns day8.re-frame2-xray.views.edn-widget
  "Canonical Xray EDN widget facade — thin wrapper over the first-
  class `views.edn-inspector` widget.

  ## Purpose

  One renderer, many call sites — every place Xray shows CLJS data
  (App-DB · Trace expanded payloads · Event :db / :fx / coeffects ·
  machine snapshots · Issues ex-data) reaches through this ns so the
  operator learns one expand/collapse + diff interaction and applies
  it everywhere.

  ## One engine, one facade (post-rf2-q3dzw)

  The WHOLE value-rendering contract lives in `views.edn-inspector`
  as one renderer. This facade is a thin delegate that the legacy
  `edn/*` call sites still reach through; new call sites should use
  `[edn-inspector value opts]` directly.

  Previous shape (pre-rf2-oqa60) composed two engines:
  `edn-inspector.render` (diff-aware tree walker) and
  `cljs-devtools-render` (binaryage/cljs-devtools-backed formatters).
  Phase 1 (rf2-oqa60) shipped the roll-your-own browse path; phase 5
  (rf2-q3dzw) subsumed the diff engine into the same widget and
  deleted the legacy `edn-inspector.render` + `theme.data-inspector`
  ns'es.

  ## Public API

      (inspect v)               ;; canonical L4 detail-panel renderer
      (inspect v node-key)      ;; with a stable per-mount qualifier
      (inspect-inline v)        ;; compact one-liner (hover / list cells)

      (code-block {:source \"(reg-event :foo …)\"
                   :lang   :clojure})

  ## Posture

  Pre-alpha · NO back-compat shims · dev-only · bundle-isolated.
  zprint stays for `code-block` source-text rendering."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack]]
            ;; The first-class edn-inspector widget owns the WHOLE
            ;; contract — browse, diff, mini, sentinel chrome — as a
            ;; single source of truth (phase 5 / rf2-q3dzw,
            ;; D5=a per rf2-sndui).
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [zprint.core :as zprint]))

;; ---- subscription bridge — superseded by the new edn-inspector widget ----
;;
;; Pre-rf2-oqa60 this section glued the cljs-devtools walker to the
;; shared expansion slot. With the phase 1 cut-over the new widget at
;; `views.edn-inspector` owns its own `:rf.xray.edn-inspector/expansion`
;; slot + toggle/reset events; the facade delegates and the cljs-
;; devtools-render adapter is deleted.

;; ---- universal copy-to-clipboard affordance (rf2-f026h) ------------------
;;
;; re-frame-10x makes every value copyable. Rather than thread a copy
;; button into each panel, the affordance rides on the WIDGET ROOT so
;; it lands on every `browse` (and therefore `inspect`) call — Trace's
;; `edn/browse`, the segment-inspector + Static panels' `edn/inspect`,
;; all at once.
;;
;; The `:rf.xray/copy-value-to-clipboard` event + the
;; `:rf.xray.fx/copy-to-clipboard` fx are registered process-globally
;; by `app-db-diff-events/install!` (always called from
;; `registry.cljs`), so the dispatch resolves on every surface.
;;
;; rf2-nesy9 — the affordance captures the SURROUNDING frame at render
;; time via `(rf/current-frame-id)` and dispatches into it on click. The
;; click-handler fires after React pops the frame context, so the
;; render-time capture is what keeps the dispatch on the right instance
;; frame (N parallel shells stay isolated). The widget root threads
;; everywhere through plain fns, so a render-time `current-frame-id`
;; capture is cleaner than plumbing a `dispatch-fn` through the whole
;; inspect/browse/diff/mini tree — and replaces the prior
;; `{:frame :rf/xray}` singleton literal.

(defn copy-affordance
  "A hover-revealed `⎘` copy button that copies `value` to the
  clipboard via `:rf.xray/copy-value-to-clipboard`. Pure hiccup —
  positioned `absolute` top-right of a `position:relative` parent. The
  `testid` is derived from the widget's render container id so tests can
  target the per-render affordance. The `⎘` glyph is the same
  click-to-copy mark spec 007:668 uses for the namespace-fade keyword
  copy."
  [value testid]
  ;; rf2-nesy9 — capture the surrounding instance frame at RENDER time
  ;; so the deferred copy click dispatches back to it (not a `:rf/xray`
  ;; literal). The widget renders inside the panels' `reg-view`s, so
  ;; `current-frame-id` resolves through the React-context tier here.
  (let [frame (rf/current-frame-id)]
   [:button {:data-testid testid
            :aria-label  "Copy value to clipboard"
            :title       "Copy value"
            :on-click    (fn [^js e]
                           (.stopPropagation e)
                           (rf/dispatch [:rf.xray/copy-value-to-clipboard value]
                                        {:frame frame}))
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
    "⎘"]))

;; ---- panel-facing facade — inspect / inspect-inline ----------------------
;;
;; Per Mike-direction rf2-9wsdy ("one widget, many call sites") every
;; panel-side EDN render flows through this namespace. Per rf2-oqa60
;; phase 1 the facade delegates to the first-class `views.edn-inspector`
;; widget — the same engine handles every type (scalars, collections,
;; sentinels) so this thin wrapper no longer needs sentinel-routing
;; branches. Phases 2-4 migrate call sites to call `ei/edn-inspector`
;; directly; this facade is the bridge through that transition.

(defn inspect
  "Sentinel-aware current-state rendering for one value — the canonical
  L4 detail-panel renderer. Routes through the first-class
  edn-inspector widget (rf2-oqa60 phase 1) which classifies every type
  natively, including the spec/015 sentinels (`:rf/redacted` and
  `:rf.size/large-elided`) as first-class chip chrome (D3=a per
  rf2-sndui).

  Phase 1 delegates the legacy facade to the new widget so existing
  call sites compile unchanged; phases 2-4 migrate each surface
  (Trace, Sub, Machine) to call `edn-inspector/edn-inspector` directly.

  Single-arg form picks a default panel-id; two-arg / three-arg forms
  accept a node-key string (passed through as a stable per-mount
  qualifier) and an opts map. The `:copy?` key from the old facade is
  accepted but ignored — copy chrome is deferred to a follow-on bead
  (the popup phase, D6=a).

  Diff rendering also routes through the new widget (an opt-in
  `:before` mode on `ei/edn-inspector`) after phase 5 (rf2-q3dzw,
  D5=a per rf2-sndui)."
  ([v] (inspect v "root" nil))
  ([v node-key] (inspect v node-key nil))
  ([v node-key _opts]
   [ei/edn-inspector v
    {:panel-id (keyword (str "rf.xray.inspect/" node-key))
     :default-expanded-depth 3}]))

(defn inspect-inline
  "Compact one-line current-state rendering for hover tooltips / list
  cells. Folds sentinel routing in (D2=a per rf2-sndui) — sentinels
  render their chip chrome via the new widget's `mini` overload; all
  other values render as a colour-coded one-liner."
  [v]
  (ei/mini v))

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

(defn unescape-source-newlines
  "Turn the two-character escaped-newline sequence `\\n` (backslash + n)
  inside a captured Clojure source string back into a REAL newline so a
  multi-line `:doc` (or any multi-line string literal) renders across
  lines, matching how it was written in source (rf2-iosnp). Pure fn;
  testable.

  ## Why this is needed

  The substrate captures handler source via `(pr-str whole-form)`
  (`re-frame.core-reg-macros/with-form-source-form`, rf2-xgfuy). Clojure's
  printer escapes newlines inside string literals, so a source-level

      {:doc \"line one
             line two\"}

  arrives here as the single physical line `:doc \"line one\\nline two\"`
  with a literal backslash-n. Under the code-block's `white-space: pre`
  that paints one over-wide line carrying a visible `\\n`, not the
  multi-line docstring the author wrote.

  ## Why a whole-string replace is safe

  `pr-str` only ever emits the `\\n` escape INSIDE string literals — a
  bare `\\n` two-char sequence never occurs elsewhere in printed Clojure
  source. So unescaping `\\n` across the whole formatted string
  reconstructs the original line breaks without misfiring on code. The
  replace is escape-aware: `pr-str` emits every escape as a two-char
  sequence (`\\\\` for a literal backslash, `\\n` for a newline, `\\\"`
  for a quote, …). The regex consumes a literal backslash-pair (`\\\\`)
  as its FIRST alternative so it is kept verbatim, leaving only a
  genuine `\\n` (a single backslash + n) to be rewritten. A literal
  backslash directly before a printed newline (`\\\\` then `\\n`)
  therefore keeps the backslash AND still unescapes the newline; an
  escaped backslash followed by a literal `n` (`\\\\` then `n`) keeps
  both."
  [s]
  (if-not (and (string? s) (str/index-of s "\\n"))
    s
    ;; `\\\\` (a literal backslash-pair) is matched FIRST so it is
    ;; consumed verbatim; only a remaining bare `\n` reaches the newline
    ;; rewrite. The function replacement keeps the backslash-pair as-is.
    (str/replace s #"\\\\|\\n"
                 (fn [m] (if (= m "\\n") "\n" m)))))

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
  panel's narrow handler-source slot without horizontal scroll.

  rf2-iosnp — after zprint (or the fall-through on a parse failure) the
  result is run through [[unescape-source-newlines]] so an embedded
  multi-line `:doc` renders its real line breaks instead of a literal
  backslash-n on one over-wide line. zprint preserves string-literal
  contents verbatim, so the `\\n` escape survives the round-trip and is
  unescaped here as the final step."
  [src]
  (if-not (and (string? src) (seq src))
    src
    (-> (try
          (zprint/zprint-file-str src "rf-xray-handler-source" {:width 72})
          (catch :default _ src))
        unescape-source-newlines)))

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
  macro-call emphasis) — so a `:foo` keyword and a `reg-event`
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
    "deftype" "defrecord" "ns" "require" "reg-event"
    ;; EP-0018 collapsed the three public event registrars onto `reg-event`
    ;; (above); calling a retired name is now a hard error. The retired
    ;; spellings nonetheless stay in the highlighter set because the
    ;; source-text highlighter renders whatever source the substrate captured
    ;; — including legacy `reg-event-db` / `-fx` / `-ctx` call sites in a v1
    ;; or pre-migration app under inspection.
    "reg-event-db" "reg-event-fx" "reg-event-ctx" "reg-sub" "reg-fx" "reg-view"
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
                     :background  (:bg-1 tokens)
                     :border      (str "1px solid " (:border-default tokens))
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
