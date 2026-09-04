(ns re-frame.story.ui.toolbar
  "Chrome-level toolbar — the horizontal strip above the three-pane row
  that exposes every registered `reg-mode` tuple as a toggle chip.

  Per spec/010 (rf2-p0mv) Storybook 8's `theme` / `viewport` / `locale`
  toolbar refactored to re-frame2 idioms: one registry (`:mode`), one
  shell-state slot (`:active-modes`), one persistence key
  (`re-frame.story/active-modes`), one URL deep-link key (`modes=`).

  ## Surface

  - `(toolbar-strip)`            — Reagent component; renders the
                                   horizontal strip with chips per
                                   registered mode + a `[reset]` button.
  - `toggle-mode!`               — programmatic toggle for tests.
  - `hydrate-modes-from-storage!` — idempotent one-shot localStorage
                                   fallback hydrator, run at shell mount
                                   BEFORE the URL hydrator.
  - `save-modes-to-storage!`     — persist after every change.

  ## Selection semantics

  Per spec/010 §Selection semantics — by axis the `:axis` slot on a
  `reg-mode` body governs the chip's toggle behaviour:

  - `:axis` present → single-select within axis. Toggling a mode in
    `:axis :theme` deactivates any sibling tagged with the same axis
    before adding the toggled mode.
  - `:axis` absent → multi-select. Any subset can be active.

  The pure logic lives in `re-frame.story.ui.state/toggle-mode` (JVM-
  testable); this ns wires the impure surfaces — localStorage and the
  Reagent ratom — around it.

  ## URL ownership boundary (rf2-96y71s)

  This ns owns ONLY rendering, toggles, and localStorage persistence.
  It does NOT read or parse `js/window.location` — all `modes=` URL
  parsing lives in `re-frame.story.share` (`parse-modes-param`,
  `parse-params`) and all URL-derived `:active-modes` hydration runs
  through `re-frame.story.ui.url-state/apply-parsed-to-state`, the
  single authoritative URL writer (spec/022 §Share semantics). The
  toolbar's localStorage fallback (`hydrate-modes-from-storage!`) runs
  FIRST on mount; the URL hydrator then either authoritatively-clears
  `:active-modes` (when the URL carries query params) or — on a fresh
  mount with no URL state at all — leaves the localStorage seed intact.
  That ordering is the URL-over-localStorage precedence (last-shared
  wins over last-used).

  ## Persistence

  Per spec/010 §Persistence — chrome-wide localStorage the toolbar's
  selection is **chrome-wide** (one selection for the whole shell
  instance). Persistence is a single localStorage key:

      re-frame.story/active-modes → \"[:Mode.app/dark :Mode.app/mobile]\"

  Stored as a `pr-str`-encoded vector of mode ids; `read-string` on
  load. Mode ids that no longer resolve at the registrar (stale storage
  after a `reg-mode` rename) are silently dropped at hydrate time via
  `re-frame.story.share/prune-unregistered-modes`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.story.local-storage :refer [safe-local-storage]]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.share :as rf.story.share]
            [re-frame.story.ui.backgrounds-switcher :as rf.story.ui.backgrounds-switcher]
            [re-frame.story.ui.element-inspector :as rf.story.ui.element-inspector]
            [re-frame.story.ui.play-status :as rf.story.ui.play-status]
            [re-frame.story.ui.recorder :as rf.story.ui.recorder]
            [re-frame.story.ui.share :as rf.story.ui.share]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.theme.motion :as rf.story.theme.motion]
            [re-frame.story.ui.viewport-switcher :as rf.story.ui.viewport-switcher]
            [re-frame.story.theme.typography :as rf.story.theme.typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as rf.story.theme.colors]))

;; ---- localStorage --------------------------------------------------------

(def ^:const ls-key
  "Chrome-wide localStorage key for the active-modes vector. Spec/010
  §Persistence — chrome-wide localStorage."
  "re-frame.story/active-modes")

(defn load-modes-from-storage
  "Read the persisted active-modes vector from localStorage. Returns
  a vector of mode-id keywords on success, nil on missing /
  unparseable. Pure: a `nil`-on-failure read."
  []
  (when-let [ls (safe-local-storage)]
    (try
      (let [raw (.getItem ls ls-key)]
        (when (string? raw)
          (let [parsed (edn/read-string raw)]
            (when (and (vector? parsed)
                       (every? keyword? parsed))
              parsed))))
      (catch :default _ nil))))

(defn save-modes-to-storage!
  "Persist `modes` (a vector of mode-id keywords) to localStorage.
  Silently no-ops if storage is unavailable. Idempotent."
  [modes]
  (when-let [ls (safe-local-storage)]
    (try (.setItem ls ls-key (pr-str (vec modes)))
         (catch :default _ nil))))

;; ---- registrar-pruning ---------------------------------------------------

(defn prune-unregistered
  "Drop mode ids from `modes` that no longer resolve at the live
  registrar (stale localStorage after a `reg-mode` rename). Delegates
  to the CLJC production helper `rf.story.share/prune-unregistered-modes` with
  the live registrar predicate injected — the pure logic is JVM-tested
  against `rf.story.share/prune-unregistered-modes` directly, not a copy here."
  [modes]
  (rf.story.share/prune-unregistered-modes
    modes (fn [mid] (rf.story.registrar/registered? :mode mid))))

;; ---- hydration -----------------------------------------------------------
;;
;; rf2-96y71s: the toolbar owns ONLY the localStorage FALLBACK. URL-
;; derived `:active-modes` hydration is the url-state engine's job
;; (`url-state/apply-parsed-to-state`, the single authoritative URL
;; writer). This fallback runs FIRST on mount; the URL hydrator then
;; either authoritatively-clears `:active-modes` (URL carries params)
;; or leaves this seed intact (fresh mount, no URL state) — the
;; URL-over-localStorage precedence (spec/022 §Share semantics).

(defn hydrate-modes-from-storage!
  "Seed the shell-state's `:active-modes` from localStorage on first
  shell mount. Idempotent: only writes when the slot is the default
  empty vector — so an already-populated state (programmatic test
  fixture) is never clobbered. Stale ids are pruned against the live
  registrar. Spec/010 §Persistence — chrome-wide localStorage.

  Mirrors the `rf.story.ui.viewport-switcher/hydrate!` / `rf.story.ui.backgrounds-switcher/
  hydrate!` shape: a localStorage-only seed that the URL hydrator may
  later override."
  []
  (let [shell @rf.story.ui.state/shell-state-atom]
    (when (empty? (:active-modes shell))
      (when-let [persisted (load-modes-from-storage)]
        (let [pruned (prune-unregistered persisted)]
          (when (seq pruned)
            (rf.story.ui.state/swap-state! rf.story.ui.state/set-active-modes pruned)))))))

;; ---- programmatic toggle -------------------------------------------------

(defn toggle-mode!
  "Flip `mode-id` in the active-modes vector + persist. Public so tests
  / programmatic callers can drive the toolbar without going through
  the DOM."
  [mode-id]
  (rf.story.ui.state/swap-state!
    (fn [s]
      (rf.story.ui.state/set-active-modes s (rf.story.ui.state/toggle-mode (:active-modes s) mode-id))))
  (save-modes-to-storage! (:active-modes (rf.story.ui.state/get-state))))

(defn reset-modes!
  "Clear every active mode + persist. The toolbar's `[reset]` action."
  []
  (rf.story.ui.state/swap-state! rf.story.ui.state/clear-active-modes)
  (save-modes-to-storage! []))

;; ---- styling -------------------------------------------------------------
;;
;; Per rf2-v58dm the toolbar now reads as ~5 distinct affordance
;; clusters separated by token-driven vertical dividers + a
;; left-edge upper-cased cluster label so users scan groups rather
;; than flat chips.  Tokens (rf2-2rwdc / rf2-i3i5j / rf2-3lt89)
;; carry the surface vocabulary; no hex literals.
;;
;; Cluster shape:
;;   [MODES axis-groups …]  divider  [DATA dispatch / play]  divider
;;   [VIEW viewport / backgrounds]  divider  [DEBUG inspector]
;;   divider  [REC recorder]  [reset]
;;
;; Modes occupy the left edge (variable width — registry-driven);
;; everything else is right-aligned via the spacer slot. Wrapping
;; preserves on narrow viewports: each `:cluster` is a self-contained
;; flex-item so it stays cohesive across rows.

(def ^:private styles
  {:strip       {:display        "flex"
                 :align-items    "center"
                 :gap            "6px"
                 :padding        "6px 10px"
                 :background     (:bg-2 rf.story.theme.colors/tokens)
                 :border-bottom  (str "1px solid " (:border-default rf.story.theme.colors/tokens))
                 :font-family    mono-stack
                 :font-size      (:caption rf.story.theme.typography/type-scale)
                 :min-height     "32px"
                 :box-sizing     "border-box"
                 :flex-wrap      "wrap"
                 :row-gap        "6px"}
   :axis-label  {:font-family     rf.story.theme.typography/sans-stack
                 :font-size       (:micro rf.story.theme.typography/type-scale)
                 :font-weight     (str (:semibold rf.story.theme.typography/weights))
                 :text-transform  "uppercase"
                 :color           (:text-tertiary rf.story.theme.colors/tokens)
                 :letter-spacing  (:label-wide rf.story.theme.typography/letter-spacing)
                 :margin-right    "6px"}
   :axis-group  {:display     "flex"
                 :align-items "center"
                 :gap         "4px"}
   :chip-row    {:display   "flex"
                 :gap       "4px"
                 :flex-wrap "wrap"}
   :chip        {:padding         "3px 9px"
                 :background      (:bg-3 rf.story.theme.colors/tokens)
                 :color           (:text-primary rf.story.theme.colors/tokens)
                 :border          (str "1px solid " (:border-subtle rf.story.theme.colors/tokens))
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     mono-stack
                 :font-size       (:caption rf.story.theme.typography/type-scale)
                 :max-width       "20em"
                 :overflow        "hidden"
                 :text-overflow   "ellipsis"
                 :white-space     "nowrap"
                 :user-select     "none"
                 :transition      (:chip rf.story.theme.motion/transitions)}
   :chip-active {:background (:accent-amber rf.story.theme.colors/tokens)
                 :color      (:text-on-accent rf.story.theme.colors/tokens)
                 :border     (str "1px solid " (:accent-amber-deep rf.story.theme.colors/tokens))}
   :spacer      {:flex "1"}
   ;; rf2-v58dm — a `:cluster` is a self-contained flex-item carrying
   ;; one logical group of affordances. The strip composes ~5 clusters
   ;; separated by `:divider` strokes.
   :cluster     {:display     "inline-flex"
                 :align-items "center"
                 :gap         "4px"
                 :padding     "0 2px"}
   ;; rf2-v58dm — left-edge upper-cased label per cluster. Mirrors the
   ;; existing axis-label vocabulary so MODES / DATA / VIEW / DEBUG /
   ;; REC all share the same small-caps grammar.
   :cluster-label {:font-family    rf.story.theme.typography/sans-stack
                   :font-size      (:micro rf.story.theme.typography/type-scale)
                   :font-weight    (str (:semibold rf.story.theme.typography/weights))
                   :text-transform "uppercase"
                   :color          (:text-tertiary rf.story.theme.colors/tokens)
                   :letter-spacing (:label-wide rf.story.theme.typography/letter-spacing)
                   :margin-right   "6px"}
   ;; rf2-v58dm — vertical divider between clusters. Token-driven
   ;; hairline; carries an inline height so the rule sits centred on
   ;; the strip rather than spanning it edge-to-edge.
   :divider     {:width        "1px"
                 :align-self   "stretch"
                 :margin       "2px 4px"
                 :background   (:border-subtle rf.story.theme.colors/tokens)
                 :flex-shrink  "0"}
   :reset       {:padding       "3px 9px"
                 :background    "transparent"
                 :color         (:text-secondary rf.story.theme.colors/tokens)
                 :border        (str "1px solid " (:border-default rf.story.theme.colors/tokens))
                 :border-radius "10px"
                 :cursor        "pointer"
                 :font-family   mono-stack
                 :font-size     (:micro rf.story.theme.typography/type-scale)
                 :transition    (:chip rf.story.theme.motion/transitions)}
   :empty       {:color       (:text-tertiary rf.story.theme.colors/tokens)
                 :font-style  "italic"
                 :font-size   (:caption rf.story.theme.typography/type-scale)}})

;; ---- chip rendering ------------------------------------------------------

(defn- truncate-label
  "Spec/010 §Chip visual contract — chip label is `(str mode-id)`
  truncated at 28 chars. The full id + `:doc` sits on `title=`."
  [s]
  (if (> (count s) 28)
    (str (subs s 0 27) "…")
    s))

(defn chip
  "Render a single chip for `mode-id`. Pure-hiccup view; click handler
  delegates to `toggle-mode!`. Public so tests can introspect the
  chip-level hiccup without driving the full strip.

  rf2-vxpq1 — `role=\"button\"` was redundant on a native `<button>`
  (the audit flagged this nit; the implicit role already carries).
  Dropping it removes 14 chars × N-chips noise from the rendered DOM
  without changing AT behaviour."
  [mode-id body active?]
  [:button
   {:style              (merge (:chip styles)
                               (when active? (:chip-active styles)))
    :aria-pressed       (if active? "true" "false")
    :title              (if-let [d (:doc body)]
                          (str (pr-str mode-id) " — " d)
                          (pr-str mode-id))
    :data-toolbar-mode  (pr-str mode-id)
    :on-click           (fn [_] (toggle-mode! mode-id))}
   (truncate-label (pr-str mode-id))])

(defn- axis-label
  "Render the axis-group label. Pure-hiccup."
  [axis]
  [:span {:style (:axis-label styles)} (str/upper-case (name axis))])

(defn- cluster-label
  "Render an upper-cased cluster label (`MODES` / `DATA` / `VIEW` /
  `DEBUG` / `REC`). Per rf2-v58dm the toolbar reads as ~5 distinct
  affordance clusters; this label leads each one."
  [text]
  [:span {:style (:cluster-label styles)
          :aria-hidden "true"}
   text])

(defn- divider
  "A token-driven vertical divider between clusters (rf2-v58dm)."
  []
  [:span {:style (:divider styles)
          :aria-hidden "true"}])

;; ---- public component ----------------------------------------------------

(defn toolbar-strip
  "Render the chrome-level toolbar strip. Reads
  `(rf.story.registrar/registrations :mode)` per render — newly-registered modes
  appear immediately. Renders an empty-state placeholder when the
  registry has no `:mode` entries.

  Spec/010 §Placement in the shell chrome — the strip lives ABOVE the
  three-pane row. Caller (`shell/shell`) wraps the strip in a
  `<header role=\"toolbar\">` landmark — the strip itself is a plain
  hiccup `<div>` so axe-core's region rule sees the landmark.

  rf2-v58dm: chips are organised into ~5 logical affordance clusters
  separated by token-driven dividers — MODES (registry-driven axes /
  unaxed modes), DATA (dispatch + play status), VIEW (viewport +
  backgrounds), DEBUG (element inspector), REC (recorder + reset).
  Each cluster carries a small-caps label so the strip reads as a
  set of named groups rather than a flat chip row."
  []
  (let [shell    @rf.story.ui.state/shell-state-atom
        active   (set (:active-modes shell))
        modes    (rf.story.registrar/registrations :mode)
        variant  (:selected-variant shell)
        {:keys [axes unaxed]} (rf.story.ui.state/group-modes-by-axis modes)
        vis-flag (get-in shell [:panel-visibility :dispatch-console])
        dc-effective? (cond
                        (true?  vis-flag) true
                        (false? vis-flag) false
                        :else             false)]
    [:header
     {:style      (:strip styles)
      :role       "toolbar"
      :aria-label "Story modes"
      :data-test  "story-toolbar"}
     ;; ── MODES cluster (left) ──────────────────────────────────────
     (if (empty? modes)
       [:span {:style (:empty styles)} "no modes registered"]
       [:span {:style       (:cluster styles)
               :data-test   "story-toolbar-cluster"
               :data-cluster "modes"}
        [cluster-label "Modes"]
        (doall
          (concat
            (for [[axis ids] axes]
              ^{:key (str axis)}
              [:span {:style (:axis-group styles)}
               [axis-label axis]
               [:span {:style (:chip-row styles)}
                (for [mid ids]
                  ^{:key mid}
                  [chip mid (get modes mid) (contains? active mid)])]])
            (when (seq unaxed)
              [^{:key "unaxed"}
               [:span {:style (:axis-group styles)}
                [:span {:style (:chip-row styles)}
                 (for [mid unaxed]
                   ^{:key mid}
                   [chip mid (get modes mid) (contains? active mid)])]]])))])
     [:span {:style (:spacer styles)}]
     ;; ── DATA cluster (variant-scoped affordances) ─────────────────
     ;; rf2-q9kv5 — Dispatch console toolbar toggle. The chip flips the
     ;; chrome-level visibility override; the right-panel resolves
     ;; story-flag + chrome-toggle together. Shown only when a variant
     ;; is focused (the panel is per-variant — no variant, nothing to
     ;; dispatch into).
     ;; rf2-8i2a9 — Play-script status chip. Visible only when a variant
     ;; is focused AND the variant carries a `:script` body.
     (when variant
       [:span {:style       (:cluster styles)
               :data-test   "story-toolbar-cluster"
               :data-cluster "data"}
        [cluster-label "Data"]
        [:button
         {:style     (merge (:chip styles)
                            (when dc-effective? (:chip-active styles)))
          :data-test "story-toolbar-dispatch-console"
          :aria-pressed (str dc-effective?)
          :title     (if dc-effective?
                       "Hide dispatch console"
                       "Show dispatch console")
          :on-click  (fn [_]
                       (rf.story.ui.state/swap-state!
                         (fn [s]
                           (assoc-in s [:panel-visibility :dispatch-console]
                                     (not dc-effective?)))))}
         (if dc-effective? "Dispatch ▾" "Dispatch ▸")]
        [rf.story.ui.play-status/chip-when-enabled variant]])
     (when variant [divider])
     ;; ── VIEW cluster (framing chips) ──────────────────────────────
     ;; rf2-zll4h — viewport + backgrounds switchers (Storybook addon-
     ;; viewport + addon-backgrounds parity). Both chips are chrome-wide
     ;; dropdowns. Each chip uses `aria-haspopup`/`aria-expanded`
     ;; (NOT `aria-pressed`) so the toolbar reset assertion in
     ;; story-feature-load (which counts `[aria-pressed="true"]`
     ;; post-reset) is not tripped by viewport / background state.
     [:span {:style       (:cluster styles)
             :data-test   "story-toolbar-cluster"
             :data-cluster "view"}
      [cluster-label "View"]
      [rf.story.ui.viewport-switcher/chip-when-enabled]
      [rf.story.ui.backgrounds-switcher/chip-when-enabled]]
     [divider]
     ;; ── DEBUG cluster (pick-mode) ─────────────────────────────────
     ;; rf2-h0jc0 — element-level click-to-code inspector chip. Toggles
     ;; the React-Devtools-style pick mode that hovers / highlights any
     ;; rendered DOM element and opens its view-fn source on click.
     ;; Uses `aria-haspopup` (not `aria-pressed`) per rf2-zll4h
     ;; convention so the reset gate is unaffected.
     [:span {:style       (:cluster styles)
             :data-test   "story-toolbar-cluster"
             :data-cluster "debug"}
      [cluster-label "Debug"]
      [rf.story.ui.element-inspector/inspect-chip]]
     [divider]
     ;; ── SHARE cluster (egress) ────────────────────────────────────
     ;; rf2-ba86n.16 — human share / export / copy egress. Opens the
     ;; Share & export dialog (share URL · copy EDN · screenshot · static
     ;; build), each command labelled with its reproducibility status.
     ;; The reframe: human egress ships freely (NOT privacy-gated — a
     ;; local dev already has their own secrets); the contract the dialog
     ;; carries is reproducibility honesty, not redaction. Chrome-wide
     ;; (the workspace/chrome URL is always shareable), so shown
     ;; unconditionally rather than gated on a focused variant.
     [:span {:style       (:cluster styles)
             :data-test   "story-toolbar-cluster"
             :data-cluster "share"}
      [cluster-label "Share"]
      [rf.story.ui.share/share-chip]]
     [divider]
     ;; ── REC cluster (actions) ─────────────────────────────────────
     ;; rf2-5fc15 — Test Codegen REC chip. Lives just before the reset
     ;; affordance so the chrome-wide recorder is reachable regardless
     ;; of which variant the user has focused.
     [:span {:style       (:cluster styles)
             :data-test   "story-toolbar-cluster"
             :data-cluster "rec"}
      [cluster-label "Rec"]
      [rf.story.ui.recorder/rec-chip]
      (when (seq (:active-modes shell))
        [:button
         {:style     (:reset styles)
          :data-test "story-toolbar-reset"
          :on-click  (fn [_] (reset-modes!))}
         "reset"])]]))
