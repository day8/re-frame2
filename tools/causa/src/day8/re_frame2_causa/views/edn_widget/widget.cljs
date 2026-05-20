(ns day8.re-frame2-causa.views.edn-widget.widget
  "Canonical Causa EDN widget (rf2-9wsdy, Mike-direction 2026-05-21).

  ## Purpose

  One renderer, many call sites — every place Causa shows CLJS data
  (App-DB diff · Trace expanded payloads · Event :db / :fx / coeffects ·
  machine snapshots · Issues ex-data) reaches through this ns so the
  operator learns one expand/collapse + path-click + diff interaction
  and applies it everywhere.

  ## Adoption

  The widget is a thin Reagent-agnostic facade on top of the existing
  `data-display/render` engine (rf2-jgip1) which already implements the
  lazy collapsible tree, sticky per-node expansion, diff gutters,
  keyword-violet type coloring, click-to-navigate path segments, and
  the §10.7 evicted-epoch placeholder. The facade exists so call sites
  address ONE widget by name (Mike's 2026-05-21 design direction —
  `cljs-devtools EDN widget`) regardless of how the engine evolves
  underneath.

  Per the bead's variant taxonomy:

    `browse` — single-value tree (default; `:variant :browse`)
    `diff`   — before/after tree (`:variant :diff` · `:before` slot)
    `mini`   — one-liner inline (`:variant :mini` · for chips + rows)

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

  Pre-alpha · NO back-compat shims · dev-only · the underlying engine
  must not leak into production bundles (bundle-isolation gate holds —
  Causa as a whole is `:devtools/preloads`-gated).

  ## Why not cljs-devtools formatters yet

  Mike's direction names cljs-devtools as the formatter library; the
  binaryage/cljs-devtools artefact would register Chrome
  custom-formatters so opening DevTools' console sees pretty CLJS
  values. That is complementary — it improves the console story, not
  the in-Causa rendering surface. The in-DOM widget here delivers the
  pixel contract Mike sketched; a follow-on bead can layer the
  cljs-devtools console formatters on the same dep when needed."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.data-display.render :as data-display]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack]]))

;; ---- variant: browse -----------------------------------------------------

(defn browse
  "Render `:value` as a path-aware expand/collapse tree. Browse mode —
  no diff semantics. Returns hiccup.

  Required: `:value :panel-id :render-id`.
  Optional: `:default-depth` (defaults to 2 per §10.4)."
  [{:keys [value panel-id render-id default-depth]
    :or   {default-depth 2}}]
  (data-display/render-tree
    {:value         value
     :diff?         false
     :panel-id      panel-id
     :render-id     render-id
     :default-depth default-depth}))

;; ---- variant: diff -------------------------------------------------------

(defn diff
  "Render a before -> after diff tree. Annotates changed branches with
  the §10.3 gutter glyphs (+ added / - removed / ~ modified / ◴ has
  changed descendant) and a `← changed from <prior>` chip on modified
  leaves. Returns hiccup.

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
  "One-liner inline rendering of `value`. No expansion, no diff — used
  in chip rows / table cells where the full tree would crowd the
  layout. Returns hiccup `[:span ...]` shape so callers can embed
  inline.

  Truncates at `max-len` (default 80) — long values get an ellipsis
  plus the full value in the title attribute so hover reveals the full
  pr-str."
  ([value] (mini value 80))
  ([value max-len]
   (let [s   (try (pr-str value) (catch :default _ (str value)))
         len (count s)
         out (if (<= len max-len) s (str (subs s 0 max-len) "…"))]
     [:span {:data-testid "rf-causa-edn-widget-mini"
             :title       s
             :style       {:font-family mono-stack
                           :font-size   "11px"
                           :color       (:text-primary tokens)
                           :white-space "nowrap"
                           :overflow    "hidden"
                           :text-overflow "ellipsis"}}
      out])))

;; ---- code-block (handler / interceptor source rendering) ----------------

(defn highlight-clojure-token
  "Per-token colour resolution for the bundled lightweight Clojure
  syntax highlighter. Pure data -> token-keyword for the token-type
  classification. Public for unit tests."
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
  "Recognised Clojure builtins for the in-bundle lightweight
  highlighter. Public so tests can assert membership."
  #{"def" "defn" "defn-" "defmacro" "let" "if" "when" "when-not"
    "cond" "case" "do" "loop" "recur" "fn" "fn*" "reify"
    "deftype" "defrecord" "ns" "require" "reg-event-db"
    "reg-event-fx" "reg-event-ctx" "reg-sub" "reg-fx" "reg-view"
    "reg-flow" "reg-machine" "dispatch" "dispatch-sync" "subscribe"
    "assoc" "assoc-in" "update" "update-in" "get" "get-in"
    "->" "->>" "some->" "some->>"})

(defn classify-token
  "Classify a Clojure token literal string. Pure fn; lightweight —
  handles the cases that matter for handler-source rendering
  (keywords, strings, numbers, comments, parens, builtins, plain
  symbols)."
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
  before symbols so they capture greedily.

  This is intentionally lightweight — re-frame handlers are typically
  short. A heavier highlight.js integration could land via a follow-on
  bead; for now this keeps the bundle small and CSS-free."
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
  Clojure-only highlighter (lightweight bundled tokenizer; cljs-devtools
  + a heavier highlight.js can land in a follow-on bead).

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
    (let [tokens-seq (if (= :clojure lang)
                       (tokenize-clojure source)
                       [[:symbol source]])]
      [:pre {:data-testid testid
             :data-lang   (name lang)
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
