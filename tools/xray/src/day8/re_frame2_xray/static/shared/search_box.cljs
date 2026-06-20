(ns day8.re-frame2-xray.static.shared.search-box
  "Shared search-box + substring `filter-rows` algebra for the Static
  surface's flat-catalogue sub-tabs.

  ## What it provides

  Five Static sub-tabs each grow a flat searchable catalogue — Flows,
  Interceptors, Schemas, Routes (the browse list), and Machines (the
  browse list). They all render their search affordance through this one
  component: an `<input>` dispatching a `set-query` event on every
  keystroke, wrapped in a header row. Two layout variants are offered:

    - the `:catalogue` variant (Flows / Interceptors / Schemas /
      Routes) — a flex row with a `Search` label and a right-aligned
      count chip, varying only on the `data-testid` prefix, the
      set-query event keyword, the placeholder, the count noun, and the
      count chip's `min-width`;
    - the `:pane` variant (Machines browse-list) — a block header with
      a full-width `type=search` input, an Esc-to-clear handler, and an
      `aria-label`, with no count chip (Machines surfaces its count in a
      separate toolbar).

  A single component keeps every call-site at ~6 LoC and makes a UX
  change to the search affordance a one-file edit. Each variant renders
  its call-site's exact markup.

  ## Frame-correctness

  The input's keystroke dispatch is an OUT-OF-RENDER affordance: it
  fires after render unwinds, when the ambient frame is gone, so a bare
  global `rf/dispatch` would leak to `:rf/default` and two Xray shells
  on one page would collide. This component therefore takes a
  caller-supplied frame-aware `:dispatch` fn and NEVER reaches a bare
  `{:frame :rf/xray}` literal or a global `rf/dispatch`. Each call-site
  supplies the dispatcher its own way:

    - Flows / Interceptors / Schemas — these `search-box` call-sites
      render INSIDE a `reg-view` body, so they render-capture
      `(rf/current-frame-id)` and hand down a closure
      `(fn [ev] (rf/dispatch ev {:frame frame}))`.
    - Routes / Machines — the browse list is invoked as a plain-fn
      Reagent component (its own render cycle can't recover the frame),
      so the routes/machines `reg-view` threads its injected frame-aware
      `dispatch` straight through.

  Either way the dispatcher is already frame-bound by the time it
  reaches here; this ns stays a pure presentational helper. The JVM
  `frame_singleton_guard_test` locks that invariant — no `:rf/xray`
  literal, no `:on-* #(rf/dispatch …)`.

  ## Pure hiccup

  Same contract as every Xray view — pure hiccup, no Reagent / UIx /
  Helix references."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- filter-rows algebra (Cluster 2) ------------------------------------

(defn filter-rows
  "Higher-order substring filter shared by the Flows / Interceptors /
  Schemas catalogues. `row->haystack` projects a row to the lower-cased
  string the needle is matched against (each tab supplies its own —
  flow-id + frame + paths + doc for Flows, kind + id + schema for
  Schemas, …). A nil / blank query returns `rows` verbatim. Pure data —
  JVM-runnable, so each tab's projection tests cover it without a CLJS
  runtime."
  [row->haystack rows query]
  (let [q (some-> query str/trim)]
    (if (or (nil? q) (= "" q))
      rows
      (let [needle (str/lower-case q)]
        (filterv #(str/includes? (row->haystack %) needle) rows)))))

;; ---- count chip ---------------------------------------------------------

(defn count-text
  "The catalogue count-chip text: `\"match\"` while a query is filtering,
  else a `noun`-pluralised total (`\"1 flow\"` / `\"7 flows\"`). `noun`
  is the singular form; the plural appends `\"s\"` (every Static noun —
  flow / interceptor / schema / route — pluralises that way). Pure —
  JVM-runnable for the projection tests."
  [noun total filtered?]
  (cond
    filtered?   "match"
    (= 1 total) (str "1 " noun)
    :else       (str total " " noun "s")))

;; ---- catalogue empty states (Cluster 3) ---------------------------------

(def ^:private empty-state-style
  "Shared italic-prose style for the catalogue empty / no-match surfaces."
  {:padding     "16px"
   :color       (:text-tertiary tokens)
   :font-family sans-stack
   :font-size   "11px"
   :font-style  "italic"})

(defn empty-state
  "The cold-start empty surface for a flat-catalogue tab: `\"No flows
  registered.\"` `noun` is the singular form (`\"flow\"` / `\"route\"`
  / …); the plural appends `\"s\"` — the same convention `count-text`
  uses (every Static noun pluralises regularly). `testid-prefix` is the
  tab's `data-testid` stem (e.g. `\"rf-xray-static-flows\"`); the surface
  suffixes `-empty`. Pure hiccup — no Reagent / UIx / Helix refs."
  [testid-prefix noun]
  [:div {:data-testid (str testid-prefix "-empty")
         :style       empty-state-style}
   (str "No " noun "s registered.")])

(defn empty-filtered
  "The no-match surface for a flat-catalogue tab when a query filters
  every row out: `\"No flows match \\\"foo\\\".\"`. `noun` / `testid-
  prefix` as in `empty-state` (the surface suffixes `-empty-filtered`).
  Pure hiccup — no Reagent / UIx / Helix refs."
  [testid-prefix noun query]
  [:div {:data-testid (str testid-prefix "-empty-filtered")
         :style       empty-state-style}
   (str "No " noun "s match " (pr-str query) ".")])

;; ---- per-variant style maps ---------------------------------------------

(def ^:private catalogue-container-style
  "Flex-row header for the Flows / Interceptors / Schemas / Routes
  catalogue search box."
  {:display       "flex"
   :align-items   "center"
   :gap           "8px"
   :padding       "8px 16px"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :font-family   sans-stack})

(def ^:private catalogue-input-style
  {:flex          1
   :background    (:bg-3 tokens)
   :color         (:text-primary tokens)
   :border        (str "1px solid " (:border-default tokens))
   :border-radius "3px"
   :padding       "4px 8px"
   :font-family   mono-stack
   :font-size     "12px"})

(def ^:private pane-container-style
  "Block header for the Machines browse-list search box — the left-pane
  list lives below it, the count lives in a separate toolbar."
  {:padding       "8px 10px"
   :border-bottom (str "1px solid " (:border-subtle tokens))
   :background    (:bg-1 tokens)})

(def ^:private pane-input-style
  {:width         "100%"
   :background    (:bg-2 tokens)
   :border        (str "1px solid " (:border-default tokens))
   :border-radius "4px"
   :color         (:text-primary tokens)
   :font-family   sans-stack
   :font-size     (:body-tight type-scale)
   :padding       "4px 8px"
   :box-sizing    "border-box"})

;; ---- search box ---------------------------------------------------------

(defn search-box
  "Render a Static-surface search box. All knobs arrive in one opts map
  so the four catalogue tabs and the Machines browse-list share one
  component. Two layout variants (see the ns docstring) preserve each
  call-site's exact rendered markup.

  Always-present knobs:

  - `:testid-prefix`   — `data-testid` stem, e.g.
                         `\"rf-xray-static-flows\"`. The container,
                         input, and count chip suffix `-search`,
                         `-search-input`, and `-search-count`.
  - `:dispatch`        — REQUIRED frame-aware dispatcher (see the ns
                         docstring on frame-correctness). Called with a
                         single event vector.
  - `:set-query-event` — event keyword the keystroke dispatches; the
                         input's current value is conj'd as the payload.
  - `:placeholder`     — input placeholder text.
  - `:value`           — current query string (nil-safe; rendered as
                         `(or value \"\")`).
  - `:variant`         — `:catalogue` (default — flex row + label +
                         count chip) or `:pane` (block header,
                         full-width `type=search` input, no count chip).

  `:catalogue`-variant knobs:

  - `:count-noun`      — singular noun for the count chip (`\"flow\"`,
                         `\"route\"`, …); the chip renders
                         `(count-text noun total filtered?)`.
  - `:total`           — pre-filter row count.
  - `:filtered?`       — whether a query is narrowing the list.
  - `:count-min-width` — `min-width` for the count chip. Defaults to
                         `\"80px\"`; Interceptors widens it to `\"90px\"`,
                         Routes narrows it to `\"60px\"`.

  `:pane`-variant knobs:

  - `:on-clear-event`  — event keyword fired when Esc is pressed in the
                         input.
  - `:input-aria-label`— `aria-label` on the input."
  [{:keys [testid-prefix dispatch set-query-event placeholder value variant
           count-noun total filtered? count-min-width
           on-clear-event input-aria-label]
    :or   {variant         :catalogue
           count-min-width "80px"}}]
  (let [pane? (= variant :pane)]
    [:div {:data-testid (str testid-prefix "-search")
           :style       (if pane? pane-container-style catalogue-container-style)}
     (when-not pane?
       [:label {:style {:color          (:text-tertiary tokens)
                        :font-size      "10px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"
                        :min-width      "60px"}}
        "Search"])
     [:input (cond-> {:type        (if pane? "search" "text")
                      :data-testid (str testid-prefix "-search-input")
                      :placeholder placeholder
                      :value       (or value "")
                      :on-change   (fn [^js e]
                                     (dispatch [set-query-event
                                                (.. e -target -value)]))
                      :style       (if pane? pane-input-style catalogue-input-style)}
               input-aria-label (assoc :aria-label input-aria-label)
               on-clear-event   (assoc :on-key-down
                                       (fn [^js e]
                                         (when (= "Escape" (.-key e))
                                           (dispatch [on-clear-event])))))]
     (when (and (not pane?) count-noun)
       [:span {:data-testid (str testid-prefix "-search-count")
               :style       {:color       (:text-tertiary tokens)
                             :font-family mono-stack
                             :font-size   "11px"
                             :min-width   count-min-width
                             :text-align  "right"}}
        (count-text count-noun total filtered?)])]))
