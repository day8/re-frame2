(ns day8.re-frame2-causa.theme.data-inspector
  "cljs-devtools-shaped value renderer for Causa's Layer 4 detail panels
  (rf2-x9fzk).

  ## Why this exists

  Causa previously rendered detail-tab values via `pr-str` — one long
  string in a monospace span. That's a regression versus the re-frame
  10x rendering, which used `binaryage/cljs-devtools` to give every
  value a coloured, click-to-expand, structural tree. This namespace
  closes that gap.

  ## Why hand-built (not `binaryage/cljs-devtools`)

  `binaryage/cljs-devtools` targets the Chrome console's custom
  formatters API — its output is browser-host objects, not in-page
  hiccup. Causa renders into the host page's DOM via its substrate
  adapter; we need pure hiccup. The aesthetic (colour palette, expand
  triangle, indentation) is straightforward to reproduce in ~300 LoC
  and gives us full control over theme tokens + click-to-source.

  ## Public API

  - `inspect` — expandable hero. Returns hiccup; renders the value as a
    cljs-devtools-shaped tree. Maps / vectors / lists / sets collapse
    by default when long; click `▶` to expand. Primitives render inline.
  - `inspect-inline` — one-line tail-elided rendering for compact rows
    (hover tooltips, list cells). No expand affordance; falls back to
    the same coloured formatting but caps display width.

  ## Substrate-agnostic state (rf2-tijr)

  Per Causa's pure-hiccup contract this ns never references Reagent /
  UIx / Helix. Per-node expand state lives in `:rf/causa` app-db under
  `[:data-inspector node-key …]` and is read/written via re-frame
  primitives. Each L4 panel mount supplies a unique `node-key` prefix
  so two panels rendered side-by-side don't share expand state.

  ## Data-classification sentinels (per spec/015-Data-Classification)

  Three sentinel shapes get bespoke chrome:

  - `:rf/redacted` (bare keyword) — opaque magenta chip; NEVER
    expandable; no reveal affordance ever.
  - `{:rf/large {:bytes N :head \"...\"}}` — yellow chip
    `[● large: N bytes \"head…\"]`; click reveals an inline
    expansion of `:head`. Sizes above
    `large-fetch-warn-threshold-bytes` (100KB) gate behind a confirm
    affordance per spec/018 §12.
  - `{:rf/redacted {:bytes N}}` — combined sensitive + large; magenta;
    size shown for diagnostic; NEVER expandable."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack]]))

;; ---- colour palette (cljs-devtools-flavoured against our tokens) -----

(def ^:private colour
  "Per-leaf colour mapping. Keywords purple, strings green, numbers
  cyan, nil grey, booleans orange, default text-primary. Mapped onto
  Causa's existing token palette so the renderer reads as native shell
  chrome rather than a third-party widget."
  {:keyword (:accent-violet tokens)
   :string  (:green tokens)
   :number  (:cyan tokens)
   :nil     (:text-tertiary tokens)
   :boolean (:orange tokens)
   :symbol  (:magenta tokens)
   :default (:text-primary tokens)
   :punct   (:text-tertiary tokens)
   :meta    (:text-secondary tokens)})

;; ---- size / expansion thresholds -------------------------------------

(def collapse-threshold
  "Collections with more than this many elements start collapsed; the
  user must click `▶` to expand. Matches the 10x default that Mike
  used to read across the screen — short enough that map literals
  ≤ 5 keys render flat, long enough that a typical app-db slice
  doesn't dump every key on initial render."
  5)

(def string-inline-cap
  "Strings longer than this render with a tail ellipsis in the inline
  form; the full value is still visible by switching to the expanded
  form via the parent collection's expand affordance."
  64)

(def large-fetch-warn-threshold-bytes
  "Per spec/018 §12 — sizes above this threshold gate the large-blob
  expansion behind an extra confirm step so a stray click can't pour
  a multi-megabyte expansion into the detail panel."
  100000)

;; ---- sentinel detection ----------------------------------------------

(defn redacted-sentinel?
  "`:rf/redacted` bare keyword — the §15 contract's primary opaque
  sentinel."
  [v]
  (= :rf/redacted v))

(defn large-meta
  "Detect the `{:rf/large {:bytes N :head s}}` single-entry wrapper.
  Returns the metadata map when v matches, else nil."
  [v]
  (when (and (map? v) (= 1 (count v)))
    (let [[k m] (first v)]
      (when (and (= :rf/large k) (map? m))
        m))))

(defn redacted+size-meta
  "Detect the combined `{:rf/redacted {:bytes N}}` single-entry wrapper —
  sensitive value carrying its size for diagnostic purposes. Returns
  the metadata map when v matches, else nil."
  [v]
  (when (and (map? v) (= 1 (count v)))
    (let [[k m] (first v)]
      (when (and (= :rf/redacted k) (map? m))
        m))))

;; ---- expand-state — keyed in :rf/causa app-db ------------------------

(defn- expand-path
  [node-key]
  [:data-inspector node-key])

(rf/reg-sub :rf.causa.data-inspector/expansion
  (fn [db [_ node-key]]
    (get-in db (expand-path node-key))))

(rf/reg-sub :rf.causa.data-inspector/all-expansion
  (fn [db _]
    (get db :data-inspector)))

(rf/reg-event-db :rf.causa.data-inspector/toggle-expanded
  (fn [db [_ node-key]]
    (update-in db (conj (expand-path node-key) :expanded?) not)))

(rf/reg-event-db :rf.causa.data-inspector/set-expanded
  (fn [db [_ node-key expanded?]]
    (assoc-in db (conj (expand-path node-key) :expanded?) (boolean expanded?))))

(rf/reg-event-db :rf.causa.data-inspector/request-large-confirm
  (fn [db [_ node-key]]
    (assoc-in db (conj (expand-path node-key) :needs-confirm?) true)))

(rf/reg-event-db :rf.causa.data-inspector/confirm-large
  (fn [db [_ node-key]]
    (-> db
        (assoc-in (conj (expand-path node-key) :confirmed?) true)
        (assoc-in (conj (expand-path node-key) :expanded?) true)
        (assoc-in (conj (expand-path node-key) :needs-confirm?) false))))

(defn- node-state
  "Pure read of the current node's expand-state from the supplied
  read-state map. Caller hands in the projection so the renderer
  stays a function of (value, state) — easy to unit-test."
  [read-state node-key]
  (get read-state node-key))

;; ---- chip renderers (one per sentinel shape) -------------------------

(defn redacted-chip
  "Opaque magenta chip rendering for `:rf/redacted`. Bytes optional —
  carries diagnostic size when the combined form
  `{:rf/redacted {:bytes N}}` is present.

  Per spec/015 §Causa rendering contract — sensitive sentinels are
  NEVER drillable. This chip has no on-click, no expand affordance, no
  reveal button. Italic small-caps lettering distinguishes it visually
  from a plain magenta keyword."
  [bytes]
  [:span {:data-testid "rf-causa-data-inspector-redacted"
          :style {:display       "inline-flex"
                  :align-items   "center"
                  :gap           "4px"
                  :padding       "0 6px"
                  :border-radius "3px"
                  :background    "rgba(232, 121, 249, 0.12)"
                  :color         (:magenta tokens)
                  :font-family   mono-stack
                  :font-size     "11px"
                  :font-style    "italic"
                  :text-transform "lowercase"
                  :letter-spacing "0.5px"
                  :user-select   "none"}}
   [:span {:style {:font-size "10px"}} "●"]
   "redacted"
   (when (and bytes (pos? bytes))
     [:span {:style {:color (:text-tertiary tokens)
                     :font-style "normal"
                     :text-transform "none"
                     :letter-spacing "normal"
                     :margin-left "4px"}}
      (str "· " bytes " bytes")])])

(defn truncate-head
  "Clip the `:head` preview to a short inline-safe length so the chip
  body stays one row tall."
  [head]
  (let [s (str head)
        n 48]
    (if (<= (count s) n)
      s
      (str (subs s 0 n) "…"))))

(defn- large-chip
  "Yellow chip for `:rf/large` sentinels.

  Click opens an inline expansion showing the full `:head` preview.
  When `:bytes` exceeds the size-confirm threshold the click first
  surfaces an inline confirm row (a textual prompt + Confirm button)
  rather than a full modal — modals are out of scope for Phase 1; the
  inline prompt matches the §12 spirit (user must explicitly accept
  the size) without dragging in modal infrastructure."
  [{:keys [bytes head]} state node-key]
  (let [s              (node-state state node-key)
        expanded?      (boolean (:expanded? s))
        confirmed?     (boolean (:confirmed? s))
        needs-confirm? (or (:needs-confirm? s)
                           (and bytes (> bytes large-fetch-warn-threshold-bytes)
                                (not confirmed?)
                                (not expanded?)
                                (:needs-confirm? s)))
        click-handler  (fn []
                         (if (and bytes
                                  (> bytes large-fetch-warn-threshold-bytes)
                                  (not confirmed?)
                                  (not expanded?))
                           (rf/dispatch [:rf.causa.data-inspector/request-large-confirm node-key]
                                        {:frame :rf/causa})
                           (rf/dispatch [:rf.causa.data-inspector/toggle-expanded node-key]
                                        {:frame :rf/causa})))]
    [:span {:data-testid "rf-causa-data-inspector-large"
            :style {:display "inline-block"}}
     [:span {:on-click click-handler
             :style    {:display        "inline-flex"
                        :align-items    "center"
                        :gap            "4px"
                        :padding        "0 6px"
                        :border-radius  "3px"
                        :background     "rgba(251, 191, 36, 0.12)"
                        :color          (:yellow tokens)
                        :font-family    mono-stack
                        :font-size      "11px"
                        :cursor         "pointer"
                        :user-select    "none"}}
      [:span {:style {:font-size "10px"}} "●"]
      "large"
      (when bytes
        [:span {:style {:color (:text-tertiary tokens) :margin-left "4px"}}
         (str "· " bytes " bytes")])
      (when head
        [:span {:style {:color (:text-secondary tokens) :margin-left "6px"
                        :max-width "240px"
                        :overflow "hidden"
                        :text-overflow "ellipsis"
                        :white-space "nowrap"
                        :display "inline-block"
                        :vertical-align "bottom"}}
         (str "\"" (truncate-head head) "\"")])
      [:span {:style {:color (:text-tertiary tokens) :margin-left "6px"}}
       (if expanded? "▼" "▶")]]
     (when needs-confirm?
       [:div {:data-testid "rf-causa-data-inspector-large-confirm"
              :style {:margin-top "4px"
                      :padding    "6px 8px"
                      :background (:bg-3 tokens)
                      :border     (str "1px solid " (:border-default tokens))
                      :border-radius "4px"
                      :font-family mono-stack
                      :font-size   "11px"
                      :color       (:text-secondary tokens)}}
        (str "Expand " bytes " bytes? (>" large-fetch-warn-threshold-bytes " threshold)")
        [:button {:on-click #(rf/dispatch [:rf.causa.data-inspector/confirm-large node-key]
                                          {:frame :rf/causa})
                  :data-testid "rf-causa-data-inspector-large-confirm-button"
                  :style {:margin-left "8px"
                          :background "transparent"
                          :border     (str "1px solid " (:yellow tokens))
                          :color      (:yellow tokens)
                          :padding    "1px 6px"
                          :border-radius "3px"
                          :cursor     "pointer"
                          :font-family mono-stack
                          :font-size  "10px"}}
         "Confirm expand"]])
     (when expanded?
       [:pre {:data-testid "rf-causa-data-inspector-large-expanded"
              :style {:margin "4px 0 0 12px"
                      :padding "6px 8px"
                      :background (:bg-3 tokens)
                      :border (str "1px solid " (:border-subtle tokens))
                      :border-radius "4px"
                      :color (:text-primary tokens)
                      :font-family mono-stack
                      :font-size "11px"
                      :white-space "pre-wrap"
                      :word-break  "break-word"
                      :max-height  "240px"
                      :overflow    "auto"}}
        (str head)])]))

;; ---- primitive renderers ---------------------------------------------

(defn- coloured
  "One-shot span helper — tag the value `v` with the colour bound to
  `kind` and stringify via `to-str`."
  ([kind s] (coloured kind s nil))
  ([kind s extra-style]
   [:span {:style (merge {:color (get colour kind (:default colour))}
                         extra-style)}
    s]))

(defn render-primitive
  "Render a leaf primitive — keyword / string / number / nil / boolean /
  symbol / fallback. Returns hiccup; never expands."
  [v]
  (cond
    (nil? v)        (coloured :nil "nil")
    (keyword? v)    (coloured :keyword (str v))
    (boolean? v)    (coloured :boolean (str v))
    (number? v)     (coloured :number (str v))
    (string? v)     (coloured :string (str "\"" v "\""))
    (symbol? v)     (coloured :symbol (str v))
    :else           (coloured :default (try (pr-str v)
                                             (catch :default _ (str v))))))

(defn- punct
  "Render structural punctuation (`{`, `}`, `[`, `]`, etc.)."
  [s]
  (coloured :punct s))

(declare render-value)

(defn- expanded?*
  [state node-key collapsed-by-default?]
  (let [s (node-state state node-key)]
    (if (contains? s :expanded?)
      (:expanded? s)
      (not collapsed-by-default?))))

(defn- expansion-toggle
  "The `▶ / ▼` toggle widget that prefixes every expandable collection."
  [state node-key collapsed-by-default?]
  (let [expanded? (expanded?* state node-key collapsed-by-default?)]
    [:span {:data-testid (str "rf-causa-data-inspector-toggle-" node-key)
            :on-click   #(rf/dispatch [:rf.causa.data-inspector/toggle-expanded node-key]
                                      {:frame :rf/causa})
            :style      {:display       "inline-block"
                         :width         "12px"
                         :color         (:text-tertiary tokens)
                         :cursor        "pointer"
                         :user-select   "none"
                         :margin-right  "2px"}}
     (if expanded? "▼" "▶")]))

(defn- entry-count
  "Element count for collapsed rendering — works for any seq-able."
  [coll]
  (count coll))

;; ---- collection renderers --------------------------------------------

(defn- indent
  [depth]
  {:padding-left (str (* depth 12) "px")})

(defn- render-map
  [m state node-key depth]
  (let [collapsed-default? (> (entry-count m) collapse-threshold)
        expanded?          (expanded?* state node-key collapsed-default?)]
    (if-not expanded?
      [:span {:data-testid (str "rf-causa-data-inspector-map-collapsed-" node-key)
              :style {:display "inline-flex" :flex-wrap "wrap" :align-items "baseline"}}
       (expansion-toggle state node-key collapsed-default?)
       (punct "{")
       [:span {:style {:color (:text-tertiary tokens) :margin "0 4px"}}
        (str (entry-count m) " entries")]
       (punct "}")]
      [:span {:data-testid (str "rf-causa-data-inspector-map-expanded-" node-key)
              :style {:display "inline-block" :vertical-align "top"}}
       [:span {:style {:display "inline-flex" :align-items "baseline"}}
        (expansion-toggle state node-key collapsed-default?)
        (punct "{")]
       (into
         [:div {:style (indent (inc depth))}]
         (map-indexed
           (fn [i [k v]]
             (let [child-key (str node-key "/" i)]
               [:div {:key i
                      :style {:display "flex"
                              :flex-wrap "wrap"
                              :gap "6px"
                              :padding "1px 0"
                              :align-items "baseline"}}
                [:span (render-value k state (str child-key "k") (inc depth))]
                [:span (render-value v state (str child-key "v") (inc depth))]]))
           m))
       (punct "}")])))

(defn- render-sequential
  "Shared renderer for vectors / lists / sets — `opener` and `closer`
  give the structural delimiters."
  [coll opener closer state node-key depth]
  (let [collapsed-default? (> (entry-count coll) collapse-threshold)
        expanded?          (expanded?* state node-key collapsed-default?)]
    (if-not expanded?
      [:span {:data-testid (str "rf-causa-data-inspector-seq-collapsed-" node-key)
              :style {:display "inline-flex" :flex-wrap "wrap" :align-items "baseline"}}
       (expansion-toggle state node-key collapsed-default?)
       (punct opener)
       [:span {:style {:color (:text-tertiary tokens) :margin "0 4px"}}
        (str (entry-count coll) " items")]
       (punct closer)]
      [:span {:data-testid (str "rf-causa-data-inspector-seq-expanded-" node-key)
              :style {:display "inline-block" :vertical-align "top"}}
       [:span {:style {:display "inline-flex" :align-items "baseline"}}
        (expansion-toggle state node-key collapsed-default?)
        (punct opener)]
       (into
         [:div {:style (indent (inc depth))}]
         (map-indexed
           (fn [i e]
             [:div {:key i
                    :style {:padding "1px 0"}}
              (render-value e state (str node-key "/" i) (inc depth))])
           coll))
       (punct closer)])))

(defn render-value
  "Recursive dispatch — pick the right renderer for the value's shape.
  Sentinels short-circuit collection / primitive paths.

  `state` is the projection of expand-state for every node under this
  inspect — a map from `node-key` to `{:expanded? bool …}`. Caller
  supplies it (in production, sourced via the
  `:rf.causa.data-inspector/expansion` sub)."
  [v state node-key depth]
  (cond
    ;; Bare opaque sentinel — never expandable.
    (redacted-sentinel? v)
    (redacted-chip nil)

    ;; Combined sensitive + large — size shown; still never expandable.
    (some? (redacted+size-meta v))
    (let [{:keys [bytes]} (redacted+size-meta v)]
      (redacted-chip bytes))

    ;; Large only — click-to-expand with confirm.
    (some? (large-meta v))
    (large-chip (large-meta v) state node-key)

    ;; Plain collections.
    (map? v)         (render-map v state node-key depth)
    (vector? v)      (render-sequential v "[" "]" state node-key depth)
    (set? v)         (render-sequential v "#{" "}" state node-key depth)
    (seq? v)         (render-sequential v "(" ")" state node-key depth)
    (sequential? v)  (render-sequential v "(" ")" state node-key depth)

    ;; Leaves.
    :else            (render-primitive v)))

;; ---- public API ------------------------------------------------------

(defn- ensure-key
  [k]
  (cond
    (nil? k)    "root"
    (string? k) k
    :else       (str k)))

(defn inspect
  "Expandable cljs-devtools-shaped renderer for one value.

  Drop this into any L4 detail panel where `pr-str` used to live.

  Per-node expand state lives in `:rf/causa` app-db under
  `[:data-inspector node-key …]`; pass a unique `node-key` per
  inspect invocation in a panel so adjacent inspects don't share
  state. Default `node-key` is `\"root\"`.

  Usage (one-arg):

      [d/inspect (:tags event)]

  Usage (with stable node-key):

      [d/inspect value (str \"event-detail/\" dispatch-id)]"
  ([v] (inspect v "root"))
  ([v node-key]
   (let [node-key  (ensure-key node-key)
         ;; Defensive deref — tests / pure-data smoke harnesses can
         ;; render the inspector without booting the `:rf/causa` frame
         ;; (the subs aren't registered there). In production rendering
         ;; the panel is mounted under the Causa shell where the sub
         ;; resolves and tracks expansion state.
         state-map (try
                     (or @(rf/subscribe [:rf.causa.data-inspector/all-expansion]) {})
                     (catch :default _ {}))]
     [:div {:data-testid "rf-causa-data-inspector"
            :style {:font-family mono-stack
                    :font-size   "12px"
                    :color       (:text-primary tokens)
                    :line-height "1.5"}}
      (render-value v state-map node-key 0)])))

(defn inspect-inline
  "Compact one-line rendering for hover tooltips, list cells, and any
  surface where a full expandable tree is overkill.

  No expansion affordance; collections collapse to their `{N entries}`
  / `[N items]` head; long strings tail-elide. Sentinels render via
  the same chip helpers as `inspect` (so a redacted leaf still renders
  as a magenta chip in a tooltip)."
  [v]
  (cond
    (redacted-sentinel? v)
    (redacted-chip nil)

    (some? (redacted+size-meta v))
    (redacted-chip (:bytes (redacted+size-meta v)))

    (some? (large-meta v))
    (let [{:keys [bytes head]} (large-meta v)]
      [:span {:style {:color (:yellow tokens) :font-family mono-stack}}
       (str "[large " (or bytes "?") " bytes"
            (when head (str " \"" (truncate-head head) "\""))
            "]")])

    (map? v)
    [:span {:style {:font-family mono-stack :color (:text-secondary tokens)}}
     (str "{" (entry-count v) " entries}")]

    (vector? v)
    [:span {:style {:font-family mono-stack :color (:text-secondary tokens)}}
     (str "[" (entry-count v) " items]")]

    (set? v)
    [:span {:style {:font-family mono-stack :color (:text-secondary tokens)}}
     (str "#{" (entry-count v) " items}")]

    (seq? v)
    [:span {:style {:font-family mono-stack :color (:text-secondary tokens)}}
     (str "(" (entry-count v) " items)")]

    (string? v)
    (let [s (str v)
          n string-inline-cap]
      (coloured :string (str "\""
                             (if (<= (count s) n) s (str (subs s 0 n) "…"))
                             "\"")))

    :else
    (render-primitive v)))
