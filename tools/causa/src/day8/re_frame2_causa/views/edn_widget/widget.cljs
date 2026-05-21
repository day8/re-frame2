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
            [day8.re-frame2-causa.data-display.render :as data-display]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack]]
            [day8.re-frame2-causa.views.edn-widget.cljs-devtools-render
             :as cdt]))

;; ---- variant: browse -----------------------------------------------------

(defn- collection-value?
  "True when `v` is a collection — anything `data-display/render-tree`'s
  tree walker treats as a node with children (map / vector / list / set).
  Non-collections route through cljs-devtools' single-value formatter."
  [v]
  (or (map? v) (vector? v) (set? v) (sequential? v)))

(defn browse
  "Render `:value` as a path-aware expand/collapse tree (collections)
  or as a single cljs-devtools-formatted hiccup span (non-collection
  leaves). Browse mode — no diff semantics. Returns hiccup.

  Required: `:value :panel-id :render-id`.
  Optional: `:default-depth` (defaults to 2 per §10.4)."
  [{:keys [value panel-id render-id default-depth]
    :or   {default-depth 2}}]
  (if (collection-value? value)
    (data-display/render-tree
      {:value         value
       :diff?         false
       :panel-id      panel-id
       :render-id     render-id
       :default-depth default-depth})
    ;; Non-collection — record / scalar / function / etc. cljs-devtools
    ;; owns the leaf shape (type tag, metadata, IRecord chrome).
    [:div {:data-testid (str "rf-causa-edn-widget-browse-"
                             (name (or panel-id :unknown))
                             "-"
                             (str (or render-id "")))
           :style {:font-family mono-stack
                   :font-size   "12px"
                   :color       (:text-primary tokens)
                   :line-height 1.4}}
     (cdt/value->hiccup value)]))

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
;; values, not strings). We keep a small in-bundle Clojure tokenizer
;; for source rendering — ~30 LoC, zero deps, sufficient for the
;; handler-source snippets the Event panel renders.

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
