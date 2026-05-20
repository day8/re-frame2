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
  - `hydrate-modes-from-storage!`/`hydrate-modes-from-url!` — idempotent
                                   one-shot hydrators run at shell
                                   mount.
  - `save-modes-to-storage!`     — persist after every change.

  ## Selection semantics

  Per spec/010 §Selection semantics — by axis the `:axis` slot on a
  `reg-mode` body governs the chip's toggle behaviour:

  - `:axis` present → single-select within axis. Toggling a mode in
    `:axis :theme` deactivates any sibling tagged with the same axis
    before adding the toggled mode.
  - `:axis` absent → multi-select. Any subset can be active.

  The pure logic lives in `re-frame.story.ui.state/toggle-mode` (JVM-
  testable); this ns wires the impure surfaces — localStorage,
  `js/window.location.search`, and the Reagent ratom — around it.

  ## Persistence

  Per spec/010 §Persistence — chrome-wide localStorage the toolbar's
  selection is **chrome-wide** (one selection for the whole shell
  instance). Persistence is a single localStorage key:

      re-frame.story/active-modes → \"[:Mode.app/dark :Mode.app/mobile]\"

  Stored as a `pr-str`-encoded vector of mode ids; `read-string` on
  load. The URL deep-link (`?modes=...`) takes precedence over
  localStorage on hydrate (last-shared wins over last-used).

  Mode ids that no longer resolve at the registrar (stale storage after
  a `reg-mode` rename) are silently dropped at hydrate time."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.backgrounds-switcher :as backgrounds-switcher]
            [re-frame.story.ui.element-inspector :as element-inspector]
            [re-frame.story.ui.play-status :as play-status]
            [re-frame.story.ui.recorder :as ui-recorder]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.motion :as motion]
            [re-frame.story.ui.viewport-switcher :as viewport-switcher]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- localStorage --------------------------------------------------------

(def ^:const ls-key
  "Chrome-wide localStorage key for the active-modes vector. Spec/010
  §Persistence — chrome-wide localStorage."
  "re-frame.story/active-modes")

(defn- safe-local-storage
  "Return `js/window.localStorage` if available, otherwise nil. Mirrors
  the defensive pattern in `mode-tabs` — private mode / file:// /
  embedded contexts can throw on touch."
  []
  (when (and (exists? js/window) (.-localStorage js/window))
    (try (.-localStorage js/window)
         (catch :default _ nil))))

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

;; ---- URL deep-link -------------------------------------------------------

(defn parse-modes-param
  "Parse a `modes=` URL query-param value into a vector of mode-id
  keywords. Each id arrives as `\"<ns>/<name>\"` (the canonical
  `(name kw)` form share.cljc emits via `kw->str`) — split on `,` and
  reconstruct via `keyword`. Pure data → data; JVM-testable.

  Returns nil if `s` is blank, an empty vector if no ids parse."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         (map (fn [part]
                (if-let [slash (str/index-of part "/")]
                  (keyword (subs part 0 slash) (subs part (inc slash)))
                  (keyword part))))
         vec)))

(defn modes-from-current-url
  "Extract the `modes=` deep-link from `js/window.location.search`, if
  any. Returns a vector of mode-id keywords or nil. CLJS-only — the
  pure parsing fn `parse-modes-param` does the heavy lifting and is
  JVM-testable."
  []
  (when (exists? js/window)
    (let [search (some-> js/window .-location .-search)]
      (when (and (string? search) (seq search))
        (try
          (let [params (js/URLSearchParams. search)
                raw   (.get params "modes")]
            (when (some? raw)
              (parse-modes-param raw)))
          (catch :default _ nil))))))

;; ---- registrar-pruning ---------------------------------------------------

(defn prune-unregistered
  "Drop mode ids from `modes` that no longer resolve at the
  registrar (stale storage / share-URL pointing at a renamed mode).
  Pure data → data; JVM-testable. `registered?` defaults to the live
  registrar; tests may inject."
  ([modes]
   (prune-unregistered modes (fn [mid] (registrar/registered? :mode mid))))
  ([modes registered?]
   (vec (filter registered? (or modes [])))))

;; ---- hydration -----------------------------------------------------------

(defn hydrate-modes-from-storage!
  "Seed the shell-state's `:active-modes` from localStorage on first
  shell mount. Idempotent: only writes when the slot is the default
  empty vector — so an already-populated state (deep-link, programmatic
  test fixture) is never clobbered. Spec/010 §Persistence — chrome-wide
  localStorage."
  []
  (let [shell @state/shell-state-atom]
    (when (empty? (:active-modes shell))
      (when-let [persisted (load-modes-from-storage)]
        (let [pruned (prune-unregistered persisted)]
          (when (seq pruned)
            (state/swap-state! state/set-active-modes pruned)))))))

(defn hydrate-modes-from-url!
  "Seed the shell-state's `:active-modes` from the `?modes=...` query
  param, if present. URL beats localStorage — spec/010 §URL deep-link
  'last-shared wins over last-used'. Idempotent and one-shot at shell
  mount."
  []
  (when-let [from-url (modes-from-current-url)]
    (let [pruned (prune-unregistered from-url)]
      (when (seq pruned)
        (state/swap-state! state/set-active-modes pruned)))))

(defn hydrate!
  "One-shot hydration entry-point: URL takes precedence over storage.
  Called from the shell's `:component-did-mount`. Spec/010 §URL deep-
  link — already wired."
  []
  ;; URL first — clobbers localStorage on hydrate per spec/010.
  (let [url-modes (modes-from-current-url)
        pruned    (when (seq url-modes) (prune-unregistered url-modes))]
    (if (seq pruned)
      (state/swap-state! state/set-active-modes pruned)
      (hydrate-modes-from-storage!))))

;; ---- programmatic toggle -------------------------------------------------

(defn toggle-mode!
  "Flip `mode-id` in the active-modes vector + persist. Public so tests
  / programmatic callers can drive the toolbar without going through
  the DOM."
  [mode-id]
  (state/swap-state!
    (fn [s]
      (state/set-active-modes s (state/toggle-mode (:active-modes s) mode-id))))
  (save-modes-to-storage! (:active-modes (state/get-state))))

(defn reset-modes!
  "Clear every active mode + persist. The toolbar's `[reset]` action."
  []
  (state/swap-state! state/clear-active-modes)
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
                 :background     (:bg-2 colors/tokens)
                 :border-bottom  (str "1px solid " (:border-default colors/tokens))
                 :font-family    mono-stack
                 :font-size      (:caption typography/type-scale)
                 :min-height     "32px"
                 :box-sizing     "border-box"
                 :flex-wrap      "wrap"
                 :row-gap        "6px"}
   :axis-label  {:font-family     typography/sans-stack
                 :font-size       (:micro typography/type-scale)
                 :font-weight     (str (:semibold typography/weights))
                 :text-transform  "uppercase"
                 :color           (:text-tertiary colors/tokens)
                 :letter-spacing  (:label-wide typography/letter-spacing)
                 :margin-right    "6px"}
   :axis-group  {:display     "flex"
                 :align-items "center"
                 :gap         "4px"}
   :chip-row    {:display   "flex"
                 :gap       "4px"
                 :flex-wrap "wrap"}
   :chip        {:padding         "3px 9px"
                 :background      (:bg-3 colors/tokens)
                 :color           (:text-primary colors/tokens)
                 :border          (str "1px solid " (:border-subtle colors/tokens))
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     mono-stack
                 :font-size       (:caption typography/type-scale)
                 :max-width       "20em"
                 :overflow        "hidden"
                 :text-overflow   "ellipsis"
                 :white-space     "nowrap"
                 :user-select     "none"
                 :transition      (:chip motion/transitions)}
   :chip-active {:background (:accent-amber colors/tokens)
                 :color      (:text-on-accent colors/tokens)
                 :border     (str "1px solid " (:accent-amber-deep colors/tokens))}
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
   :cluster-label {:font-family    typography/sans-stack
                   :font-size      (:micro typography/type-scale)
                   :font-weight    (str (:semibold typography/weights))
                   :text-transform "uppercase"
                   :color          (:text-tertiary colors/tokens)
                   :letter-spacing (:label-wide typography/letter-spacing)
                   :margin-right   "6px"}
   ;; rf2-v58dm — vertical divider between clusters. Token-driven
   ;; hairline; carries an inline height so the rule sits centred on
   ;; the strip rather than spanning it edge-to-edge.
   :divider     {:width        "1px"
                 :align-self   "stretch"
                 :margin       "2px 4px"
                 :background   (:border-subtle colors/tokens)
                 :flex-shrink  "0"}
   :reset       {:padding       "3px 9px"
                 :background    "transparent"
                 :color         (:text-secondary colors/tokens)
                 :border        (str "1px solid " (:border-default colors/tokens))
                 :border-radius "10px"
                 :cursor        "pointer"
                 :font-family   mono-stack
                 :font-size     (:micro typography/type-scale)
                 :transition    (:chip motion/transitions)}
   :empty       {:color       (:text-tertiary colors/tokens)
                 :font-style  "italic"
                 :font-size   (:caption typography/type-scale)}})

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
  `(registrar/registrations :mode)` per render — newly-registered modes
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
  (let [shell    @state/shell-state-atom
        active   (set (:active-modes shell))
        modes    (registrar/registrations :mode)
        variant  (:selected-variant shell)
        {:keys [axes unaxed]} (state/group-modes-by-axis modes)
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
     ;; is focused AND the variant carries a `:play-script` body.
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
                       (state/swap-state!
                         (fn [s]
                           (assoc-in s [:panel-visibility :dispatch-console]
                                     (not dc-effective?)))))}
         (if dc-effective? "Dispatch ▾" "Dispatch ▸")]
        [play-status/chip-when-enabled variant]])
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
      [viewport-switcher/chip-when-enabled]
      [backgrounds-switcher/chip-when-enabled]]
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
      [element-inspector/inspect-chip]]
     [divider]
     ;; ── REC cluster (actions) ─────────────────────────────────────
     ;; rf2-5fc15 — Test Codegen REC chip. Lives just before the reset
     ;; affordance so the chrome-wide recorder is reachable regardless
     ;; of which variant the user has focused.
     [:span {:style       (:cluster styles)
             :data-test   "story-toolbar-cluster"
             :data-cluster "rec"}
      [cluster-label "Rec"]
      [ui-recorder/rec-chip]
      (when (seq (:active-modes shell))
        [:button
         {:style     (:reset styles)
          :data-test "story-toolbar-reset"
          :on-click  (fn [_] (reset-modes!))}
         "reset"])]]))
