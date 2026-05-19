(ns day8.re-frame2-causa.filters.pills
  "Top-ribbon IN/OUT filter pills for Causa (rf2-ak4ms).

  Per `tools/causa/spec/018-Event-Spine.md` §3 + §7 the L1 ribbon
  carries a filter cluster — IN pills (green `+`) + OUT pills (magenta
  `×`) + trailing `[ + ]` add-pill. Clicking any pill opens the rich
  edit popup (see `filters/edit_popup.cljs`); clicking `×` on a pill
  removes it without round-tripping through the popup. The trailing
  add-pill opens the popup empty + defaulted to IN.

  ## Replaces #1397's window.prompt stub

  PR #1397 shipped the 4-layer chrome with a `js/window.prompt` stub
  inside `shell.cljs`'s `ribbon-filter-pills`. This ns is the proper
  UI. The shell's `ribbon-filter-pills` delegates here so the only
  surviving consumer of the prompt stub is the `(throw …)` line in
  the test that asserts the stub is gone.

  ## Pills hover tooltip

  Per spec/018 §3 the filter cluster shows a hover tooltip with
  counts: `IN: 3 patterns / OUT: 5 patterns`. We surface this as the
  cluster's `title` attribute.

  ## Right-click context menu

  Per spec/018 §7 right-click on an event-row opens a context menu
  with 'Always hide this event-type' / 'Show only events with id
  …' / etc. The event-row's `on-context-menu` handler dispatches
  `:rf.causa/open-edit-popup` with the event-id pre-filled; the popup
  opens in 'add' mode with the OUT default selected. See
  `shell.cljs`'s `event-row` for the wiring.

  ## Pure hiccup

  Same posture as the rest of Causa's view code (rf2-tijr): pure
  hiccup, no per-substrate switches. Mount is via `reg-view` from the
  caller (`shell.cljs`'s `ribbon-filter-pills`)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.filters.typed-predicates :as typed]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens type-scale sans-stack mono-stack]]))

;; ---- one pill ------------------------------------------------------------

(defn- pill-glyph [mode]
  (case mode :in "+" :out "×"))

(defn- pill-tone [mode]
  (case mode :in (:green tokens) :out (:magenta tokens)))

(defn- format-pattern
  "Render the pill's pattern text. Keywords render with their leading
  `:`; strings render verbatim. nil / blank falls back to `<empty>`
  so a partially-saved pill is still visually addressable.

  Kept for back-compat call sites; new code routes through
  `typed/pill-label` so each kind picks its own format."
  [pattern]
  (cond
    (nil? pattern)              "<empty>"
    (keyword? pattern)          (str pattern)
    (and (string? pattern)
         (seq pattern))         pattern
    :else                       "<empty>"))

(defn- pill-kind
  "Read the pill's `:kind` slot, canonicalising via the typed-
  predicate ns so legacy `{:pattern …}` pills surface as
  `:event-id-pattern`."
  [pill]
  (:kind (typed/canonicalise-pill pill)))

(defn- pill-display
  "Compose the pill's visible label — kind-specific glyph (when set)
  followed by the kind's label per `typed/pill-label`. Used by the
  pill body's text content so each predicate kind reads with the
  same alphabet across the chrome."
  [pill]
  (let [glyph (typed/pill-glyph pill)
        label (typed/pill-label pill)]
    (if glyph
      (str glyph ": " label)
      label)))

(defn pill
  "One filter pill — clickable body that opens the edit popup (for
  the keyword-pattern kind), plus an inline `×` button that removes
  the pill directly (no popup round-trip for the common 'just delete
  it' case).

  ## Typed-predicate kinds (rf2-piye4)

  Per Mike's rf2-drcyb closure, v1 ships three typed-predicate
  kinds — `:machine`, `:http-correlation`, `:fx` — whose params are
  fully determined by the right-click source row. These pills are
  NOT editable in v1 (the popup is keyword-pattern-only); the body
  is non-clickable for typed pills and the user removes via the
  `×` button. The legacy `:event-id-pattern` kind (back-compat for
  pre-rf2-piye4 persisted shape) keeps its click-to-edit body.

  Props:
    `:mode`    `:in` | `:out`
    `:pill`    the pill record — either `{:pattern <kw-or-str>}`
               (legacy keyword-pattern) or `{:kind <kw> :params {…}}`
               (typed predicate)
    `:idx`     position in the mode bucket (drives the testid + the
               remove-filter event payload)"
  [{:keys [mode pill idx]}]
  (let [tone        (pill-tone mode)
        mode-glyph  (pill-glyph mode)
        kind        (pill-kind pill)
        editable?   (= kind :event-id-pattern)
        testid      (str "rf-causa-filter-pill-" (name mode) "-" idx)
        body-text   (pill-display pill)]
    [:span {:data-testid testid
            :data-pill-kind (name kind)
            :style {:display        "inline-flex"
                    :align-items    "center"
                    :gap            "4px"
                    :border         (str "1px solid " tone)
                    :color          tone
                    :padding        "1px 4px 1px 8px"
                    :border-radius  "10px"
                    :font-family    mono-stack
                    :font-size      (:caption type-scale)
                    :white-space    "nowrap"}}
     (if editable?
       [:button {:data-testid  (str testid "-body")
                 :on-click     #(rf/dispatch
                                  [:rf.causa/open-edit-popup
                                   {:source :pill :mode mode :idx idx :pill pill}]
                                  {:frame :rf/causa})
                 :title        "Edit"
                 :style {:background  "transparent"
                         :border      "none"
                         :color       tone
                         :cursor      "pointer"
                         :padding     "0"
                         :font-family mono-stack
                         :font-size   (:caption type-scale)
                         :white-space "nowrap"}}
        (str mode-glyph " " body-text " ")
        ;; Pencil glyph per spec/018 §7 'Pill visual contract' —
        ;; visual cue that the pill body is the click-to-edit target.
        [:span {:style {:opacity 0.7}} "✎"]]
       ;; Typed predicate — params are fully determined by the
       ;; clicked row, so no edit popup. Bare span (non-button) so
       ;; the cluster reads as 'tag + remove' rather than 'edit
       ;; button + remove'.
       [:span {:data-testid (str testid "-body")
               :title       (str (name kind) " predicate")
               :style {:color       tone
                       :font-family mono-stack
                       :font-size   (:caption type-scale)
                       :white-space "nowrap"}}
        (str mode-glyph " " body-text)])
     [:button {:data-testid  (str testid "-remove")
               :on-click     #(rf/dispatch
                                [:rf.causa/remove-filter mode idx]
                                {:frame :rf/causa})
               :title        "Remove this filter"
               :aria-label   (str "Remove " (name mode) " filter " body-text)
               :style {:background    "transparent"
                       :border        "none"
                       :color         tone
                       :cursor        "pointer"
                       :padding       "0 2px"
                       :margin-left   "2px"
                       :font-family   sans-stack
                       :font-size     (:caption type-scale)
                       :line-height   "1"
                       :border-left   (str "1px solid " tone)
                       :opacity       0.7}}
      "×"]]))

;; ---- add-pill affordance -------------------------------------------------

(defn- add-pill
  "Trailing `[ + ]` affordance — opens the edit popup empty +
  defaulted to IN per spec/018 §7 'Trailing +'."
  []
  [:button {:data-testid "rf-causa-filter-add"
            :on-click    #(rf/dispatch
                            [:rf.causa/open-edit-popup
                             {:source :add :mode :in}]
                            {:frame :rf/causa})
            ;; Accessible name doubles the visible label so screen-
            ;; reader users hear 'Add filter pill' not the bare
            ;; brackets. The `[ + ]` glyph (not `+` alone) avoids
            ;; colliding with host apps that own a `+` button —
            ;; Playwright `getByRole('button', {name: '+'})` lassoes
            ;; both surfaces when names overlap.
            :aria-label  "Add filter pill"
            :title       "Add filter pill"
            :style       {:background    "transparent"
                          :border        (str "1px dashed "
                                              (:text-tertiary tokens))
                          :color         (:text-tertiary tokens)
                          :cursor        "pointer"
                          :padding       "2px 8px"
                          :border-radius "10px"
                          :font-family   sans-stack
                          :font-size     (:caption type-scale)
                          :white-space   "nowrap"}}
   "[ + ]"])

;; ---- cluster -------------------------------------------------------------

(defn- counts-tooltip
  "Per spec/018 §3 the cluster carries a hover tooltip with pill
  counts. Pluralises 'pattern' / 'patterns' for honesty (a count of
  `1 patterns` reads as a bug)."
  [filters]
  (let [in-n  (count (:in filters))
        out-n (count (:out filters))
        word  (fn [n] (if (= 1 n) "pattern" "patterns"))]
    (str "IN: " in-n " " (word in-n)
         " / OUT: " out-n " " (word out-n))))

(defn pills-view
  "The full ribbon filter cluster — IN pills, then OUT pills, then the
  add-pill. Reads `:rf.causa/active-filters` via the caller; the
  caller is expected to be inside a `reg-view` so subscribes resolve
  to `:rf/causa`."
  [{:keys [filters]}]
  [:div {:data-testid "rf-causa-ribbon-filters"
         :title (counts-tooltip filters)
         :style {:display     "flex"
                 :align-items "center"
                 :gap         "6px"
                 :flex        "1 1 auto"
                 :flex-wrap   "wrap"}}
   (for [[idx p] (map-indexed vector (:in filters))]
     ^{:key (str "in-" idx)}
     [pill {:mode :in :pill p :idx idx}])
   (for [[idx p] (map-indexed vector (:out filters))]
     ^{:key (str "out-" idx)}
     [pill {:mode :out :pill p :idx idx}])
   [add-pill]])
