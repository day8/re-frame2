(ns day8.re-frame2-causa.panels.shared.film-strip.header
  "Shared film-strip header — `[◀ Prev] [Next ▶]` epoch navigation
  consumed by every L4 panel (rf2-h7nqh).

  Per `tools/causa/spec/021-Dynamic-Panel-Designs.md`:

    - §2.5 — film-strip back/forward semantics: `[◀ Prev] [Next ▶]`
      walks the L2 spine chronologically. MVP: next chronological
      epoch regardless of dispatch-origin (per super-prompt B.5).
      Stretch: per-panel filter slot (e.g. Issues panel's 'next
      epoch with ⚠').
    - §17.1.5 — iconography: `◀ Prev` left-pointing triangle glyph
      at 12px JetBrains Mono; hit target 28×20px (≥ 24×24 AA target-
      size + 4px vertical padding for the operator's fingertip
      target).
    - §17.1.3 — palette: chevrons render `:text-secondary` default ·
      `:text-primary` on hover. Disabled state demotes the foreground
      to `:text-tertiary` with `cursor: not-allowed`.
    - §17.2 — interaction-state matrix: pressed → `translateY(1px)`
      ~60ms; disabled → `:text-tertiary` + `cursor: not-allowed`,
      tabindex removed. Focus-ring is the global `:focus-visible`
      amber — inherited from `theme/global_styles`, never suppressed.

  ## Shape

      [header {:panel-id   \"event\"
               :prev-fn    #(rf/dispatch [::nav-prev])
               :next-fn    #(rf/dispatch [::nav-next])
               :has-prev?  true
               :has-next?  true
               :indicator  [:span … ]}]   ; optional middle slot

  ## Contract

    `:panel-id`    — required; stable string used in `:data-testid`s
                     so per-panel tests can anchor the buttons
                     (`rf-causa-<panel-id>-film-strip-prev`, etc.)
                     and walk the rendered header by id.

    `:prev-fn`     — required; 0-arg fn fired on click of `◀ Prev`.
                     Pure handler — the parent panel computes the
                     destination epoch and dispatches the rewind /
                     focus event.

    `:next-fn`     — required; 0-arg fn fired on click of `Next ▶`.

    `:has-prev?`   — required boolean. When false the button
                     renders in the disabled visual state per
                     §17.2 (no handler attached, no tabindex).

    `:has-next?`   — required boolean. Same semantics as
                     `:has-prev?`.

    `:indicator`   — optional hiccup, rendered between the two
                     buttons. Parent passes whatever epoch
                     indicator / context it wants (`epoch #42`,
                     dispatch-origin pill, filter chip, …).

    `:filter-fn`   — STRETCH, optional. When supplied the header
                     surfaces a `data-rf-causa-filter` attribute
                     carrying the filter-fn's truthy marker so
                     panel-level tests can assert the filter is
                     wired through. The component does NOT compute
                     prev/next here — that's the parent panel's
                     job; the slot just accommodates the per-panel
                     narrowing surface (e.g. Issues panel's 'next
                     epoch with ⚠' per §17.5 follow-on).

  ## Pure component

  No app-db touches, no subscriptions, no global mutation. Parent
  panel computes prev/next epoch + has-prev?/has-next? from its
  own state (typically a `:rf.causa/focus` sub + the L2 spine) and
  passes the resolved fns in. This keeps every L4 panel free to
  shape its own navigation semantics (chronological for MVP,
  filtered for stretch) without forking the visual treatment.

  ## Why a separate ns

  Every L4 panel (Event, Reactive, App-db, Trace, Machines,
  Routing, Issues, Chrome-A11y) renders a film-strip in its header.
  Inlining the hiccup eight times invites drift; hoisting it here
  gives the visual rhythm one source of truth — a future tweak
  (chevron weight, hit-target geometry, focus-ring radius) lands
  in one place."
  (:require [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack type-scale]]))

;; ---- visual tokens ------------------------------------------------------
;;
;; §17.1.5 + §17.1.3 lock the visual treatment. Catalogued here as
;; defs so the test suite can assert the rendered hiccup picks up the
;; canonical tokens (test-by-data, not test-by-pixel).

(def ^:private button-base-style
  "Shared style map for both `◀ Prev` and `Next ▶` buttons.

  Hit target: 28×20px per §17.1.5 (≥ 24×24 AA target-size; 28×20
  with 4px vertical padding for the operator's fingertip target).

  Mono font + 12px size per §17.1.5 (`◀ Prev` — left-pointing
  triangle glyph, 12px JetBrains Mono)."
  {:display          "inline-flex"
   :align-items      "center"
   :justify-content  "center"
   :min-width        "28px"
   :min-height       "20px"
   :padding          "4px 8px"
   :border           "none"
   :background       "transparent"
   :font-family      mono-stack
   :font-size        (:caption type-scale)
   :line-height      1
   :user-select      "none"
   :-webkit-user-select "none"})

(defn- enabled-style
  "Default (interactive) style — chevron in `:text-secondary` per
  §17.1.3 row 'Film-strip back/forward chevron'."
  []
  (merge button-base-style
         {:color  (:text-secondary tokens)
          :cursor "pointer"}))

(defn- disabled-style
  "Disabled style — foreground demoted to `:text-tertiary` per
  §17.2 'Disabled' row + cursor: not-allowed."
  []
  (merge button-base-style
         {:color  (:text-tertiary tokens)
          :cursor "not-allowed"}))

;; ---- the buttons --------------------------------------------------------

(defn- film-strip-button
  "Render one of the two film-strip buttons.

  `direction` is `:prev` or `:next`; the glyph + label + testid
  derive from it. `enabled?` flips between the interactive and
  disabled visual states (no handler / no tabindex when disabled
  per §17.2)."
  [{:keys [panel-id direction enabled? on-click]}]
  (let [label  (case direction
                 :prev "◀ Prev"     ;; ◀ Prev
                 :next "Next ▶")    ;; Next ▶
        suffix (case direction
                 :prev "prev"
                 :next "next")
        testid (str "rf-causa-" panel-id "-film-strip-" suffix)]
    (if enabled?
      [:button {:data-testid testid
                :type        "button"
                :aria-label  (case direction
                               :prev "Previous epoch"
                               :next "Next epoch")
                :on-click    on-click
                :style       (enabled-style)}
       label]
      ;; Disabled — no handler attached, no tabindex (per §17.2).
      ;; `:disabled true` is the canonical HTML signal; mirroring it
      ;; as `:aria-disabled "true"` keeps the AT story intact.
      [:button {:data-testid    testid
                :type           "button"
                :disabled       true
                :aria-disabled  "true"
                :aria-label     (case direction
                                  :prev "Previous epoch"
                                  :next "Next epoch")
                :tab-index      -1
                :style          (disabled-style)}
       label])))

;; ---- the header --------------------------------------------------------

(defn header
  "Render the shared `[◀ Prev] [Next ▶]` film-strip header for an
  L4 panel.

  Pure component — no app-db touches, no subscriptions. The parent
  panel computes prev/next-fn + has-prev?/has-next? and passes them
  in. See ns doc for the full contract.

  Returns hiccup."
  [{:keys [panel-id prev-fn next-fn has-prev? has-next? indicator
           filter-fn]}]
  (let [;; The optional filter-fn surfaces as a data-attribute so
        ;; per-panel tests can assert the slot is wired through.
        ;; The component itself does NOT call the filter — prev/next
        ;; semantics live in the parent.
        filter-attr (when filter-fn {:data-rf-causa-filter "active"})]
    [:div (merge {:data-testid (str "rf-causa-" panel-id "-film-strip")
                  :style       {:display         "inline-flex"
                                :align-items     "center"
                                :gap             "8px"      ;; §17.1.1 :gap-2
                                :font-family     mono-stack
                                :font-size       (:caption type-scale)}}
                 filter-attr)
     (film-strip-button
       {:panel-id  panel-id
        :direction :prev
        :enabled?  (boolean has-prev?)
        :on-click  (when has-prev? prev-fn)})
     (when (some? indicator)
       [:span {:data-testid (str "rf-causa-" panel-id
                                 "-film-strip-indicator")
               :style       {:color       (:text-tertiary tokens)
                             :font-family mono-stack
                             :font-size   (:caption type-scale)}}
        indicator])
     (film-strip-button
       {:panel-id  panel-id
        :direction :next
        :enabled?  (boolean has-next?)
        :on-click  (when has-next? next-fn)})]))
