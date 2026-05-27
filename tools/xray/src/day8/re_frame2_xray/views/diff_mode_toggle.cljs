(ns day8.re-frame2-xray.views.diff-mode-toggle
  "Shared three-mode diff toggle `[diff][full][full+diff]` (rf2-yqjrd).

  ## What this is

  ONE button-bar component that every Xray surface uses to switch
  between the three lenses on a value that has both a `before` and an
  `after`:

  - `:diff`      — flat path-prefixed change list. The pure-diff lens.
                   Operator sees ONLY what changed.
  - `:full`      — full data tree, no diff chrome. The pure-data lens.
                   Operator sees the entire `after` value with no
                   annotations.
  - `:full+diff` — mode-3 — the full data tree WITH inline diff
                   annotations (gutter glyphs, row washes, R3 `[N∆]`
                   chips, R4 vertical rails, R5/6/7/8 grammar). Shape +
                   delta in one read. Default for the operator's most-
                   useful view.

  ## Why a shared widget

  Per rf2-yqjrd Mike's pair-debug 2026-05-27: every Xray surface that
  shows `(before, after)` data should use the SAME mode toggle + the
  SAME Editscript-backed engine. One affordance, one rendering
  pipeline, one mental model — regardless of which panel the operator
  is in (Epoch HANDLER `:db`, App-DB, Machine Inspector snapshot,
  SUBSCRIPTIONS sub-value).

  Foundation bead rf2-n2jig (#2230) shipped the toggle + engine for
  Epoch HANDLER `:db`. This widget extracts the toggle component
  verbatim so the other surfaces in this bead's scope adopt it
  uniformly instead of re-implementing.

  ## Public API

      [diff-mode-toggle
       {:mode       <keyword>   ;; current mode :diff / :full / :full+diff
        :on-change  (fn [mode]) ;; called on click; pure fn (callers
                                ;; bridge to rf/dispatch as needed)
        :testid     <str>       ;; data-testid prefix for the bar + buttons
        ;; optional —
        :label      <str>       ;; prefix label e.g. \"View\" (rf2-fytu4);
                                ;; omit for bare bar; supply for the
                                ;; canonical uniform discoverability
                                ;; affordance every Xray consumer passes.
        :modes      <vec>       ;; defaults to [:diff :full :full+diff]
       }]

  Returns a hiccup `<span>` with three buttons (and an optional prefix
  label). Active button paints in `:accent` token colour; inactive
  buttons are transparent with muted text. The widget is stateless
  chrome — the caller owns the mode value (typically via a sub) and
  the dispatch (typically via `with-frame :rf/xray`).

  ## Per-surface storage convention

  Each surface registers its own sub + event for the mode keyword
  via the `reg-mode-sub+event!` helper (one call per surface):

      ;; top-level surfaces — surface-id is an unqualified keyword
      :rf.xray.<surface>/diff-mode        ;; sub returning the keyword
      :rf.xray.<surface>/set-diff-mode    ;; event-db setting the keyword

      ;; sub-surfaces — surface-id is a namespaced keyword
      :rf.xray.<surface-ns>/<sub>-diff-mode      ;; sub
      :rf.xray.<surface-ns>/set-<sub>-diff-mode  ;; event-db

  Mode is stored at the sub-id key in Xray's app-db (one slot per
  surface, no cross-surface collisions). See `reg-mode-sub+event!`
  below for the one-call helper that installs the standard pair.

  ## Frame-anchor pattern (rf2-p56sk / rf2-7sdja)

  Toggle state lives in the Xray app-db. The caller's `on-change`
  must dispatch with `(rf/with-frame :rf/xray …)` (or the
  `{:frame :rf/xray}` opts envelope where it works) so the dispatch
  hits the same app-db the sub reads from regardless of which host
  frame's React-context the click landed in. This widget does NOT
  embed the frame-anchor — the caller composes it because the right
  frame depends on where the toggle's home app-db lives (Xray for
  every current consumer; future tools may differ).

  Pure hiccup; substrate-agnostic. JVM-load-safe (no React imports;
  no `:require` of `re-frame.core`)."
  (:require [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack]]
            [re-frame.core :as rf]))

;; =========================================================================
;; styles
;; =========================================================================
;;
;; Hoisted at ns-load so React's reconciler sees stable object identities
;; (rf2-zlk6h). `tokens` resolves to `var(--rf-xray-*)` strings — theme
;; switching rides the CSS-variable seam.

(def ^:private mode-toggle-bar-style
  {:display       "inline-flex"
   :align-items   "center"
   :gap           0
   :border        (str "1px solid " (:border-subtle tokens))
   :border-radius "3px"
   :overflow      "hidden"
   :margin-left   "8px"
   :line-height   1})

(def ^:private mode-toggle-button-base-style
  {:border         "none"
   :padding        "2px 8px"
   :font-family    sans-stack
   :font-size      "10px"
   :font-weight    700
   :text-transform "uppercase"
   :letter-spacing "0.5px"
   :cursor         "pointer"
   :line-height    1})

(def ^:private mode-toggle-button-active-style
  (assoc mode-toggle-button-base-style
         :background (:accent tokens)
         :color      (:white tokens)))

(def ^:private mode-toggle-button-inactive-style
  (assoc mode-toggle-button-base-style
         :background "transparent"
         :color      (:text-secondary tokens)))

(def ^:private mode-toggle-prefix-label-style
  ;; rf2-fytu4 — discoverability label rendered at the head of the
  ;; toggle. Same typography envelope as section sub-headers across
  ;; Xray (11px uppercase semibold muted) so the widget reads as part
  ;; of the surrounding header chrome.
  {:font-family    sans-stack
   :font-size      "11px"
   :font-weight    600
   :text-transform "uppercase"
   :letter-spacing "0.5px"
   :color          (:text-secondary tokens)
   :margin-right   "8px"
   :line-height    1})

(def ^:private mode-toggle-wrapper-style
  ;; rf2-fytu4 — wrapper span used when a `:label` is supplied so the
  ;; prefix label + button-bar align baseline-to-baseline.
  {:display      "inline-flex"
   :align-items  "center"})

;; =========================================================================
;; mode keywords
;; =========================================================================

(def ^:private default-modes
  "Canonical mode order for the three-button bar — `[diff][full][full+diff]`.
  Per Mike's pair-debug 2026-05-27 the operator's eye reads left → right:
  least-rich (just-what-changed) → richest (full tree + annotations).
  Surfaces may override but should not depart from this ordering."
  [:diff :full :full+diff])

(def ^:private default-default-mode
  "The widget's recommended default mode keyword across every surface
  per pair-debug 2026-05-27: mode-3 is the operator's most-useful
  lens because it carries shape + delta in one read. Surfaces with
  no `before` available (e.g. SUBSCRIPTIONS first-run rows, App-DB
  cold-boot) fall back to `:full` automatically — that's a render-time
  policy not a default-mode override."
  :full+diff)

(defn- mode-label
  "Render the human-readable button label for a mode keyword. The
  literal `+` in `:full+diff` is intentional per the findings-doc
  grammar lock — the symbol IS the cue (combining two lenses)."
  [m]
  (case m
    :diff      "diff"
    :full      "full"
    :full+diff "full+diff"
    (name m)))

(defn- mode-testid-suffix
  "Sanitised testid suffix — the `+` in `:full+diff` is not a valid
  CSS selector tail downstream, so substitute a hyphenated alias.
  All other mode keywords use their `name`."
  [m]
  (case m
    :full+diff "full-with-diff"
    (name m)))

;; =========================================================================
;; widget
;; =========================================================================

(defn diff-mode-toggle
  "Three-button toggle bar `[diff][full][full+diff]` (rf2-yqjrd / rf2-n2jig).

  Stateless chrome — the caller owns `:mode` (typically a sub) and the
  `:on-change` dispatch (typically `(rf/with-frame :rf/xray (rf/dispatch
  [:rf.xray.<surface>/set-diff-mode mode]))`).

  Returns a `<span>` with the three buttons; active button paints in
  `:accent`, inactive buttons are transparent with muted text.

  Opts:

  - `:mode`       — current mode keyword (`:diff` / `:full` / `:full+diff`).
                    The widget renders the matching button as `:active`.
  - `:on-change`  — `(fn [next-mode])`, called with the chosen mode
                    keyword on button click. Pure surface — the caller
                    chooses how to bridge to `rf/dispatch` (including
                    any `with-frame :rf/xray` envelope).
  - `:testid`     — data-testid prefix used for the bar + per-button
                    testids. The bar gets `<testid>` verbatim; buttons
                    get `<testid>-<mode-suffix>` (e.g. `…-diff`,
                    `…-full`, `…-full-with-diff`).
  - `:label`      — optional prefix label (rf2-fytu4). When supplied the
                    widget renders `[:span <label> [bar...]]` so the
                    operator has a contextual anchor (`View: [diff]
                    [full] [full+diff]`). Omit for a bare bar. Every
                    current Xray consumer passes `\"View\"`.
  - `:modes`      — optional ordered vector of mode keywords. Defaults to
                    `[:diff :full :full+diff]`. Surfaces that want to
                    omit a mode (rare; mode-3 is the canonical full
                    bar) pass a subset.

  rf2-xlmhh — the bar no longer carries a `:data-mode` attribute.
  Per `tools/xray/spec/Conventions.md` §264 `data-mode` was retired
  as a duplicate axis; the active mode is observable via the active
  button's `aria-pressed=\"true\"` instead. Surfaces that need the
  mode as a section-level decoration use the canonical
  `data-rf-xray-diff-mode` attribute on their own enclosing section
  (rf2-xvu24).

  rf2-yqjrd — extracted from `panels.epoch.view/db-diff-mode-toggle`
  (rf2-n2jig) so every Xray surface consumes the same chrome verbatim."
  [{:keys [mode on-change testid label modes]
    :or   {modes default-modes}}]
  (let [bar [:span {:data-testid testid
                    :style mode-toggle-bar-style}
             (for [m modes
                   :let [active? (= mode m)]]
               ^{:key (name m)}
               [:button {:data-testid (str testid "-" (mode-testid-suffix m))
                         :aria-pressed (str active?)
                         :on-click (fn [e]
                                     (.stopPropagation e)
                                     (on-change m))
                         :style (if active?
                                  mode-toggle-button-active-style
                                  mode-toggle-button-inactive-style)}
                (mode-label m)])]]
    (if (some? label)
      [:span {:data-testid (str testid "-wrapper")
              :style mode-toggle-wrapper-style}
       [:span {:data-testid (str testid "-label")
               :style mode-toggle-prefix-label-style}
        label]
       bar]
      bar)))

;; =========================================================================
;; helpers — per-surface sub + event registration
;; =========================================================================

(defn reg-mode-sub+event!
  "Register the canonical sub + event pair for a per-surface diff
  mode. Idempotent across hot-reload (re-registering replaces).

  `surface-id` is a keyword. Two shapes are supported:

  - Top-level surface — a plain (unqualified) keyword whose `name`
    is itself a dotted namespace, e.g. `:rf.xray.app-db` or
    `:rf.xray.machine-inspector`. Registers:

        :rf.xray.<surface>/diff-mode       ;; sub
        :rf.xray.<surface>/set-diff-mode   ;; event-db

  - Sub-surface — a namespaced keyword, e.g. `:rf.xray.epoch/db` or
    `:rf.xray.epoch/subs-value`. Registers:

        :rf.xray.<surface-ns>/<sub>-diff-mode     ;; sub
        :rf.xray.<surface-ns>/set-<sub>-diff-mode ;; event-db

  The mode is stored under the sub-id keyword in Xray's app-db (one
  slot per surface; no cross-surface collisions). Returns
  `{:sub-id … :event-id … :slot …}` so callers can introspect the
  canonical names.

  Mode persists in Xray's app-db so the operator's preference survives
  focus shifts (rf2-n2jig). The `:default-mode` opt overrides the
  widget's `:full+diff` default for surfaces where a different starting
  lens makes more sense."
  [surface-id & [{:keys [default-mode]
                  :or   {default-mode default-default-mode}}]]
  (let [ns-part  (namespace surface-id)
        nm-part  (name surface-id)
        sub-id   (if ns-part
                   (keyword ns-part (str nm-part "-diff-mode"))
                   (keyword nm-part "diff-mode"))
        event-id (if ns-part
                   (keyword ns-part (str "set-" nm-part "-diff-mode"))
                   (keyword nm-part "set-diff-mode"))
        slot     sub-id]
    (rf/reg-sub sub-id
      (fn [db _]
        (get db slot default-mode)))
    (rf/reg-event-db event-id
      (fn [db [_ mode]]
        (assoc db slot mode)))
    {:sub-id   sub-id
     :event-id event-id
     :slot     slot}))
