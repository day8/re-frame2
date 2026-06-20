(ns day8.re-frame2-xray.filters.pills
  "Top-ribbon IN/OUT filter pills for Xray.

  Per `tools/xray/spec/018-Event-Spine.md` §3 + §7 the ribbon carries
  a filter cluster — IN pills (green-bordered) + OUT pills (red-
  bordered) per the authoritative reference events-ribbon.
  Clicking any pill opens the rich edit popup (see
  `filters/edit_popup.cljs`); clicking `×` on a pill removes it without
  round-tripping through the popup. The `[ + ]` add-pill opens the popup
  empty + defaulted to IN.

  ## Two-bar split

  The committed pills (`pills-view`) live on bar-2 (the events ribbon);
  the add(+) affordance (`add-pill`) is mounted separately on bar-1 (the
  chrome ribbon) beside the `Filters:` label, matching the reference
  chrome-ribbon / events-ribbon split.

  ## Pills hover tooltip

  Per spec/018 §3 the filter cluster shows a hover tooltip with
  counts: `IN: 3 patterns / OUT: 5 patterns`. We surface this as the
  cluster's `title` attribute.

  ## Right-click context menu

  Per spec/018 §7 right-click on an event-row opens a context menu
  with 'Always hide this event-type' / 'Show only events with id
  …' / etc. The event-row's `on-context-menu` handler dispatches
  `:rf.xray/open-edit-popup` with the event-id pre-filled; the popup
  opens in 'add' mode with the OUT default selected. See
  `shell.cljs`'s `event-row` for the wiring.

  ## Pure hiccup

  Same posture as the rest of Xray's view code: pure
  hiccup, no per-substrate switches. Mount is via `reg-view` from the
  caller (`shell.cljs`'s `ribbon-filter-pills`)."
  (:require [day8.re-frame2-xray.filters.typed-predicates :as typed]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale sans-stack mono-stack]]))

;; ---- one pill ------------------------------------------------------------

;; The Figma authority shows pills as
;; `[text label] [trailing × delete button]` only — the include/exclude
;; signal is carried by the BORDER COLOUR (green = include, red = exclude,
;; resolved by `pill-tone` below), with no leading glyph. The trailing
;; remove button is rendered as `×` (distinct role: it's the
;; click-target that removes the pill, scoped to the remove `<button>`
;; with its own aria-label).

(defn- pill-tone [mode]
  ;; Green-bordered include (`:in`) pills, red-bordered
  ;; exclude (`:out`) pills, per the authoritative reference events-ribbon
  ;; (`border: 1px solid var(--devtools-success)` for include,
  ;; `--devtools-error` for exclude). `:success`/`:error` are the
  ;; reference's green/red tokens.
  (case mode :in (:success tokens) :out (:error tokens)))

(defn- pill-kind
  "Read the pill's `:kind` slot, canonicalising via the typed-
  predicate ns so bare `{:pattern …}` pills surface as
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

  ## Typed-predicate kinds

  v1 ships three typed-predicate
  kinds — `:machine`, `:http-correlation`, `:fx` — whose params are
  fully determined by the right-click source row. These pills are
  NOT editable in v1 (the popup is keyword-pattern-only); the body
  is non-clickable for typed pills and the user removes via the
  `×` button. The `:event-id-pattern` kind (the bare
  `{:pattern …}` shape) keeps its click-to-edit body.

  Props:
    `:mode`    `:in` | `:out`
    `:pill`    the pill record — either `{:pattern <kw-or-str>}`
               (keyword-pattern) or `{:kind <kw> :params {…}}`
               (typed predicate)
    `:idx`     position in the mode bucket (drives the testid + the
               remove-filter event payload)

  `dispatch` is the frame-aware dispatcher captured by the
  caller's `reg-view` body so the edit / remove clicks land on the
  surrounding instance frame, not a `{:frame :rf/xray}` literal."
  [dispatch {:keys [mode pill idx]}]
  (let [tone        (pill-tone mode)
        kind        (pill-kind pill)
        editable?   (= kind :event-id-pattern)
        testid      (str "rf-xray-filter-pill-" (name mode) "-" idx)
        body-text   (pill-display pill)]
    [:span {:data-testid testid
            :data-pill-kind (name kind)
            ;; Reference events-ribbon pill shape:
            ;; `border-radius 4px`, `border 1px solid <tone>` (green for
            ;; include, red for exclude), transparent bg, tone-coloured
            ;; text + an `x` to remove.
            :style {:display        "inline-flex"
                    :align-items    "center"
                    :gap            "4px"
                    :border         (str "1px solid " tone)
                    :background     "transparent"
                    :color          tone
                    :padding        "1px 4px 1px 8px"
                    :border-radius  "4px"
                    :font-family    mono-stack
                    :font-size      (:caption type-scale)
                    :white-space    "nowrap"}}
     (if editable?
       [:button {:data-testid  (str testid "-body")
                 :on-click     #(dispatch
                                  [:rf.xray/open-edit-popup
                                   {:source :pill :mode mode :idx idx :pill pill}])
                 :title        "Edit"
                 :style {:background  "transparent"
                         :border      "none"
                         :color       tone
                         :cursor      "pointer"
                         :padding     "0"
                         :font-family mono-stack
                         :font-size   (:caption type-scale)
                         :white-space "nowrap"}}
        ;; Body is label + trailing pencil only — no leading mode glyph
        ;; (the border colour carries the include/exclude signal).
        (str body-text " ")
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
        ;; Body is label only — no leading mode glyph (border colour
        ;; carries the include/exclude signal).
        body-text])
     ;; A VERTICAL DIVIDER sits before the `✕` (Figma-Make
     ;; surface): a 1px tone-coloured rule separating the pill body from
     ;; the remove affordance. The remove button keeps its own border-left
     ;; as the divider so it round-trips through the same tone; the explicit
     ;; padding gives the `✕` a square hit-area that reads against the rule.
     [:button {:data-testid  (str testid "-remove")
               :on-click     #(dispatch
                                [:rf.xray/remove-filter mode idx])
               :title        "Remove this filter"
               :aria-label   (str "Remove " (name mode) " filter " body-text)
               :style {:background    "transparent"
                       :border        "none"
                       :color         tone
                       :cursor        "pointer"
                       :padding       "0 4px 0 6px"
                       :margin-left   "4px"
                       :font-family   sans-stack
                       :font-size     (:caption type-scale)
                       :line-height   "1"
                       :border-left   (str "1px solid " tone)
                       :opacity       0.7}}
      "×"]]))

;; ---- add-pill affordance -------------------------------------------------

(defn add-pill
  "The `[ + ]` add-filter affordance — opens the edit popup empty +
  defaulted to IN per spec/018 §7 'Trailing +'.

  Mounted on the bar-1 chrome ribbon (beside the
  `Filters:` label) per the authoritative reference chrome-ribbon, which
  carries the add(+) on bar-1 while the committed pills live on bar-2
  (the events ribbon). Public so the shell mounts it directly in the
  chrome ribbon's left cluster.

  `dispatch` is the frame-aware dispatcher captured by the
  caller's `reg-view` body."
  [dispatch]
  [:button {:data-testid "rf-xray-filter-add"
            :on-click    #(dispatch
                            [:rf.xray/open-edit-popup
                             {:source :add :mode :in}])
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
                          :border-radius "4px"
                          :font-family   sans-stack
                          :font-size     (:caption type-scale)
                          :white-space   "nowrap"}}
   "[ + ]"])

(defn chrome-add-filter-button
  "The chrome-ribbon `+ filter` TEXT button (Figma-Make
  surface). A single outlined text button on bar-1. Opens the SAME edit
  popup as `add-pill` (`:rf.xray/open-edit-popup {:source :add :mode
  :in}`).

  Outlined on the dark chrome band: a muted `chrome-ribbon-text-muted`
  border + ink so it reads as a secondary chrome affordance against the
  near-black ribbon.

  `dispatch` is the frame-aware dispatcher captured by the
  caller's `reg-view` body."
  [dispatch]
  [:button {:data-testid "rf-xray-filter-add"
            :on-click    #(dispatch
                            [:rf.xray/open-edit-popup
                             {:source :add :mode :in}])
            :aria-label  "Add filter pill"
            :title       "Add filter pill"
            :style       {:background    "transparent"
                          :border        (str "1px solid "
                                              (:chrome-ribbon-text-muted tokens))
                          :color         (:chrome-ribbon-text-muted tokens)
                          :cursor        "pointer"
                          :padding       "2px 8px"
                          :border-radius "4px"
                          :font-family   sans-stack
                          :font-size     (:caption type-scale)
                          :white-space   "nowrap"}}
   "+ filter"])

(defn events-add-filter-button
  "The events-ribbon add-filter `+` ICON button (Figma-Make
  surface). Sits after the `filters:` contextual label on bar-2, before
  the committed pills. Opens the SAME edit popup as `add-pill`. A
  square bordered `+` icon-button on the light
  data canvas (the events ribbon stays on `bg-2`, not the dark chrome
  band).

  `dispatch` is the frame-aware dispatcher captured by the
  caller's `reg-view` body."
  [dispatch]
  [:button {:data-testid "rf-xray-filter-add-events"
            :on-click    #(dispatch
                            [:rf.xray/open-edit-popup
                             {:source :add :mode :in}])
            :aria-label  "Add filter pill"
            :title       "Add filter pill"
            :style       {:background      "transparent"
                          :border          (str "1px solid "
                                                (:border-default tokens))
                          :color           (:text-secondary tokens)
                          :cursor          "pointer"
                          :width           "21px"
                          :height          "21px"
                          :padding         "0"
                          :border-radius   "4px"
                          :display         "inline-flex"
                          :align-items     "center"
                          :justify-content "center"
                          :font-family     sans-stack
                          :font-size       (:body type-scale)
                          :line-height     "1"}}
   [:span {:aria-hidden "true"} "+"]])

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
  "The committed filter pills cluster — green-bordered IN pills, then
  red-bordered OUT pills. Reads `:rf.xray/active-filters`
  via the caller; the caller is expected to be inside a `reg-view` so
  subscribes resolve to `:rf/xray`.

  The add(+) affordance is not part of this
  cluster: per the authoritative reference the committed pills live on
  bar-2 (the events ribbon) while the add(+) sits on bar-1 (the chrome
  ribbon, beside the `Filters:` label). The shell mounts `add-pill`
  directly in the chrome ribbon's left cluster and `pills-view` in the
  events ribbon.

  `dispatch` is the frame-aware dispatcher captured by the
  caller's `reg-view` body, threaded to each `pill`."
  [dispatch {:keys [filters]}]
  [:div {:data-testid "rf-xray-ribbon-filters"
         :title (counts-tooltip filters)
         :style {:display     "flex"
                 :align-items "center"
                 :gap         "6px"
                 :flex-wrap   "wrap"}}
   (for [[idx p] (map-indexed vector (:in filters))]
     ^{:key (str "in-" idx)}
     [pill dispatch {:mode :in :pill p :idx idx}])
   (for [[idx p] (map-indexed vector (:out filters))]
     ^{:key (str "out-" idx)}
     [pill dispatch {:mode :out :pill p :idx idx}])])
