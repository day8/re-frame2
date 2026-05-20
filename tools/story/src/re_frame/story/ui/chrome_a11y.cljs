(ns re-frame.story.ui.chrome-a11y
  "Chrome accessibility (axe-core) panel — companion to
  `re-frame.story.ui.a11y` (rf2-18t6p, parent rf2-4w88j).

  The existing `ui/a11y.cljs` panel scans VARIANT trees only (per
  rf2-qgms1) — chrome a11y is Story's concern, not the variant
  author's. But Story didn't dogfood the scanner against its OWN
  chrome. This panel closes that gap: it runs the same axe-core engine
  scoped to the chrome root element (`[data-rf-story-root]` stamped by
  `shell.cljs`) so Story-chrome a11y regressions surface during dev.

  ## Relationship to ui/a11y.cljs

  The two panels share:

  - The CDN-load gate + opt-in persistence (`a11y/cdn-opt-in?`,
    `a11y/set-cdn-opt-in!`, `a11y/ensure-axe-loaded!`) — one consent
    decision approves loading axe-core for both panels.
  - The violations stylesheet that renders red outlines on offending
    elements (`a11y/ensure-stylesheet!`).
  - The violation-row hiccup + impact-style colour scheme — pure UI
    leaves, identical for both panels.
  - The `record-violation-overlay!` decorator that stamps
    `data-rf-a11y-violation` on offending DOM nodes.

  This panel owns its own state atoms (`violations`, `run-state`) so
  chrome violations never pollute the variant panel's per-frame state
  and vice versa.

  ## Scope

  Scans `document.querySelector(\"[data-rf-story-root]\")` — the
  outermost chrome wrapper. The variant tree is contained inside this
  root via `[data-rf-story-variant-root]`, so a chrome scan WILL also
  walk variant DOM. axe-core has no first-class 'exclude' API for raw
  selectors that survives all rule families, so we trade: the chrome
  scan returns chrome violations + variant violations rolled into one
  list, and the variant panel still surfaces variant-only violations
  cleanly. The author's workflow: fix variant violations in the variant
  panel; track chrome regressions here, ignoring rows whose `target`
  starts with `[data-rf-story-variant-root` (those are variant-tree
  hits the variant panel owns).

  ## Pre-alpha posture

  No toggle to opt out — the panel is always available, runs on demand
  via the 'run' button. Production builds with `config/enabled?` false
  never reach this ns.

  ## State

  `violations` is a single atom (not per-frame) — there's exactly one
  chrome surface per Story shell, so a per-frame map would be over-
  structured."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.story.config :as config]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.a11y :as a11y]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- chrome root selector ------------------------------------------------

(def ^:const chrome-root-selector
  "CSS selector for the chrome root element stamped by
  `re-frame.story.ui.shell/shell` (line ~862, `:data-rf-story-root
  true`). Single-instance: the shell-singleton mounts exactly one root
  per page so this selector resolves to at most one element."
  "[data-rf-story-root]")

(defn find-chrome-root
  "Resolve the DOM element marked as the Story chrome root, or nil if
  the shell isn't mounted (e.g. node-runtime tests, or pre-mount).

  Wrapped in try/catch so node-runtime callers receive nil rather than
  a ReferenceError on `js/document`."
  []
  (try
    (let [doc (.-document js/globalThis)]
      (when doc
        (.querySelector doc chrome-root-selector)))
    (catch :default _ nil)))

;; ---- state ---------------------------------------------------------------

(defonce
  ^{:doc "Latest chrome-scan violations as a vector. Single atom (not
         per-frame) because there's one chrome surface per shell."}
  violations
  (r/atom []))

(defonce
  ^{:doc "Run state: :idle|:loading|:running|:done|:error|:no-root|:no-consent.
         Mirrors the per-frame run-state slot in `ui/a11y.cljs` for UX
         parity with the variant panel."}
  run-state
  (r/atom :idle))

(defn reset-state!
  "Test-fixture helper. Clears the violations vector + resets the run-
  state to :idle. The CDN opt-in is NOT cleared (that's the variant
  panel's concern + a persisted user decision)."
  []
  (reset! violations [])
  (reset! run-state :idle)
  nil)

;; ---- Use-system-colors? toggle (rf2-846h2) ------------------------------
;;
;; Operator-controlled opt-in for the same system-token chrome the
;; `@media (forced-colors: active)` block in `theme/motion.cljc` paints
;; under Windows HCM. When the toggle is on, the chrome root carries
;; `data-rf-force-colors="active"` and the sibling selectors in motion-
;; css fire — identical chrome to the OS HCM path, without flipping the
;; OS-level switch. Default OFF; the OS HCM detection still works
;; either way.
;;
;; ## Why the Chrome A11y panel
;;
;; Story has no Settings panel surface; the closest accessibility-
;; focused surface is the Chrome A11y panel which already carries an
;; opt-in toggle (the axe-core CDN consent) and persists state through
;; localStorage. Co-locating the system-colors toggle here means the
;; user finds both accessibility knobs in the same surface.
;;
;; ## Persistence
;;
;; The toggle survives reload via `localStorage` under
;; `force-colors-opt-in-key`. The pattern mirrors `a11y/cdn-opt-in-key`
;; (same module): in-memory ratom for the live read, persisted string
;; for the round-trip across reloads. Browsers that block localStorage
;; (private mode, embedded contexts, file://) degrade to in-memory-only
;; — the toggle still works for the session, just doesn't survive
;; reload.

(def ^:const force-colors-opt-in-key
  "localStorage key under which the dev's 'Use system colors' opt-in
  lives. A string `\"true\"` means the toggle is on; absent / any
  other value means the toggle is off."
  "rf.story.a11y/force-colors-opt-in")

(def ^:const force-colors-attribute
  "Attribute name the toggle stamps on the chrome root + `<html>` to
  activate the system-token chrome on demand. Sibling-selectors in
  `theme/motion.cljc` match
  `[data-rf-force-colors=\"active\"]` so the same rules the `@media
  (forced-colors: active)` block paints also fire under operator
  opt-in."
  "data-rf-force-colors")

(def ^:const force-colors-active-value
  "The single value the `data-rf-force-colors` attribute carries when
  the toggle is on. Sibling-selectors in motion.cljc match this exact
  value; future states (e.g. an explicit `\"none\"` override) would
  extend the enumeration."
  "active")

(defonce
  ^{:doc "In-memory mirror of the persisted 'Use system colors' opt-in.
         Initialised from `localStorage` on first read; falls back to
         this when the host environment lacks `localStorage` (node-
         runtime tests, strict-CSP browsers with storage blocked).
         Wrapped in an `r/atom` so the panel re-renders when the
         toggle flips."}
  force-colors-opt-in-atom
  (r/atom false))

(defonce ^:private force-colors-opt-in-bootstrapped? (atom false))

(defn- read-storage-force-colors
  "Best-effort read from `localStorage`. Returns true iff the key
  exists with value `\"true\"`; returns nil (NOT false) on any error,
  so the in-memory atom stays authoritative when storage is blocked."
  []
  (try
    (let [ls (.-localStorage js/globalThis)]
      (when ls
        (= "true" (.getItem ls force-colors-opt-in-key))))
    (catch :default _ nil)))

(defn- write-storage-force-colors!
  "Best-effort write to `localStorage`. Silently no-ops if storage is
  unavailable. The in-memory atom is always written by the caller;
  this is purely for persistence across reloads."
  [on?]
  (try
    (let [ls (.-localStorage js/globalThis)]
      (when ls
        (if on?
          (.setItem ls force-colors-opt-in-key "true")
          (.removeItem ls force-colors-opt-in-key))))
    (catch :default _ nil))
  nil)

(defn- html-root-element
  "The `<html>` element. Always present in a real browser; nil under
  Node test runtimes that don't simulate a document."
  []
  (try
    (when-let [doc (.-document js/globalThis)]
      (.-documentElement doc))
    (catch :default _ nil)))

(defn apply-force-colors-attribute!
  "Stamp / clear `data-rf-force-colors=\"active\"` on the Story chrome
  root AND `<html>` so the sibling selectors in `theme/motion.cljc`
  fire even when the OS HCM is OFF.

  `on?` truthy stamps the attribute; falsey removes it. Idempotent —
  repeated calls leave the DOM in the same state. No-op when neither
  the chrome root nor `<html>` is present (test runtimes without a
  `document` or before the shell mounts)."
  [on?]
  (let [active? (boolean on?)]
    (doseq [el [(find-chrome-root) (html-root-element)]
            :when el]
      (try
        (if active?
          (.setAttribute el force-colors-attribute force-colors-active-value)
          (.removeAttribute el force-colors-attribute))
        (catch :default _ nil))))
  nil)

(defn force-colors-opt-in?
  "Read the dev's 'Use system colors' opt-in. Returns true iff the
  toggle is on in this session. On first call the value is
  bootstrapped from `localStorage` (so a prior session's choice
  survives reload); subsequent calls read the in-memory atom."
  []
  (when-not @force-colors-opt-in-bootstrapped?
    (when-let [stored (read-storage-force-colors)]
      (reset! force-colors-opt-in-atom stored))
    (reset! force-colors-opt-in-bootstrapped? true))
  @force-colors-opt-in-atom)

(defn set-force-colors-opt-in!
  "Persist the dev's 'Use system colors' opt-in. `on?` truthy stamps
  the chrome attribute and writes `\"true\"` to `localStorage`;
  falsey clears both. The in-memory atom always reflects the choice;
  the `localStorage` write is best-effort (no-op when storage is
  blocked).

  Also stamps / clears the attribute on the live DOM so the change
  takes effect immediately — no reload required. The matching
  bootstrap on shell mount (`bootstrap-force-colors!`) re-applies
  the persisted state before first paint."
  [on?]
  (let [v (boolean on?)]
    (reset! force-colors-opt-in-atom v)
    (reset! force-colors-opt-in-bootstrapped? true)
    (write-storage-force-colors! v)
    (apply-force-colors-attribute! v))
  nil)

(defn bootstrap-force-colors!
  "Restore the persisted 'Use system colors' opt-in by stamping /
  clearing `data-rf-force-colors=\"active\"` on the live chrome root +
  `<html>`. Idempotent — safe to call on every shell mount + on the
  first lookup of the in-memory atom. Intended caller: `shell.cljs`
  after the chrome root is in the DOM but before first paint settles."
  []
  (apply-force-colors-attribute! (force-colors-opt-in?))
  nil)

;; ---- running axe ---------------------------------------------------------

(def ^:const chrome-frame-id
  "Pseudo-frame-id stamped on `:warning` trace events emitted from
  chrome-scoped a11y violations. Distinct from any real variant id so
  `:rf.assert/no-warnings` listeners can filter chrome noise out of a
  variant's failure budget if desired."
  :rf.story.chrome-a11y/chrome)

(defn run-axe!
  "Run axe-core against the Story chrome root and store violations.
  Returns a `js/Promise` resolving to the violations vector — or nil
  when the chrome root cannot be resolved (e.g. shell not mounted).

  Reuses `a11y/ensure-axe-loaded!` so the CDN load + consent gate is
  shared with the variant panel: one opt-in approves both.

  Per IMPL-SPEC §11.1 surfaces violations into the global trace bus
  via `a11y/emit-warning-for-violation` keyed on `chrome-frame-id`."
  ([] (run-axe! (find-chrome-root)))
  ([context]
   (cond
     ;; No chrome root and no explicit context → surface the degraded
     ;; state instead of silently scanning document.body (which would
     ;; flag any pre-shell content the page hosts).
     (nil? context)
     (do
       (reset! run-state :no-root)
       (js/console.warn
         "[story.chrome-a11y] no chrome root found"
         "— the Story shell does not appear to be mounted.")
       (js/Promise.resolve nil))

     ;; CDN opt-in gate — same prompt the variant panel uses; one
     ;; consent approves both panels.
     (not (a11y/cdn-opt-in?))
     (do
       (reset! run-state :no-consent)
       (js/Promise.resolve nil))

     :else
     (do
       (reset! run-state :loading)
       (-> (a11y/ensure-axe-loaded!)
           (.then
             (fn [^js axe]
               (reset! run-state :running)
               (.run axe context)))
           (.then
             (fn [^js results]
               (let [vs        (.-violations results)
                     scope-el  (when (and (some? context)
                                          (some? (.-nodeType context)))
                                 context)]
                 (reset! violations (vec (array-seq vs)))
                 (doseq [v (array-seq vs)]
                   (a11y/record-violation-overlay! scope-el v)
                   (a11y/emit-warning-for-violation chrome-frame-id v))
                 (reset! run-state :done)
                 vs)))
           (.catch
             (fn [e]
               (reset! run-state :error)
               (js/console.error "[story.chrome-a11y]" e)
               nil)))))))

;; ---- panel components ----------------------------------------------------

(def ^:private styles
  {:wrap      {:padding "8px"
               :background (:bg-2 colors/tokens)
               :border-top "1px solid #444"
               :color (:text-primary colors/tokens)
               :font-family mono-stack
               :font-size (:caption typography/type-scale)}
   :header    {:display "flex"
               :justify-content "space-between"
               :align-items "center"
               :margin-bottom "8px"}
   :section-h {:font-weight "bold"
               :color (:text-secondary colors/tokens)
               :text-transform "uppercase"
               :font-size (:micro typography/type-scale)
               :letter-spacing "0.5px"}
   :status    {:color (:text-secondary colors/tokens)
               :font-size (:micro typography/type-scale)
               :margin-top "4px"}
   :empty     {:color (:text-tertiary colors/tokens)
               :font-style "italic"
               :padding "4px 0"}})

(defn- consent-prompt-chrome
  "Rendered when the dev hasn't yet opted in to the CDN load. Mirrors
  the variant panel's consent prompt but enables the chrome scan on
  click. The text reuses the variant panel's wording (same egress, same
  trust call) — one approval covers both panels."
  []
  [:div {:style {:padding "8px 0"
                 :border-top "1px dashed #555"
                 :margin-top "4px"
                 :color (:text-primary colors/tokens)}}
   [:div {:style {:font-weight "bold"
                  :color (:danger colors/tokens)
                  :margin-bottom "6px"}}
    "axe-core not loaded"]
   [:div {:style {:font-size (:micro typography/type-scale)
                  :line-height "1.4"
                  :color (:text-secondary colors/tokens)
                  :margin-bottom "6px"}}
    "Running an a11y scan loads "
    [:code {:style {:color (:info colors/tokens)}} "axe-core@4.10.0"]
    " from a public CDN ("
    [:code {:style {:color (:info colors/tokens)}} "cdn.jsdelivr.net"]
    "). The remote JS gets full DOM access to this Story page; the SRI "
    "hash pinned in the loader detects tampering, but the dependency "
    "itself is a trust call. No shell state leaves the browser."]
   [:div {:style {:font-size (:micro typography/type-scale)
                  :color (:text-secondary colors/tokens)
                  :margin-bottom "8px"}}
    "Approve once per browser; the opt-in is remembered in "
    [:code {:style {:color (:info colors/tokens)}} "localStorage"]
    " and shared with the per-variant a11y panel."]
   [:button {:style    (:run-button a11y/styles)
             :on-click (fn [_]
                         (a11y/set-cdn-opt-in! true)
                         (run-axe!))}
    "enable axe-core + scan"]])

(defn- force-colors-toggle
  "rf2-846h2 — 'Use system colors' opt-in toggle. Renders a checkbox
  + hint inside the Chrome A11y panel so the operator can preview /
  live in the system-token chrome on demand. Mirrors the in-memory
  ratom so the checkbox state stays in lockstep with the live
  attribute."
  []
  (let [on? (force-colors-opt-in?)]
    [:div {:data-test "story-chrome-a11y-use-system-colors"
           :style     {:padding "8px 0 4px 0"
                       :border-top "1px dashed #444"
                       :margin-top "8px"
                       :color (:text-primary colors/tokens)}}
     [:label {:style {:display     "flex"
                      :align-items "center"
                      :gap         "8px"
                      :cursor      "pointer"
                      :font-size   (:caption typography/type-scale)}}
      [:input {:data-test "story-chrome-a11y-use-system-colors-input"
               :type      "checkbox"
               :checked   (boolean on?)
               :on-change (fn [^js e]
                            (set-force-colors-opt-in!
                              (boolean (.. e -target -checked))))}]
      [:span "Use system colors"]]
     [:div {:style {:font-size   (:micro typography/type-scale)
                    :line-height "1.4"
                    :color       (:text-secondary colors/tokens)
                    :margin-top  "4px"}}
      "Render the Story chrome using your OS' high-contrast palette ("
      [:code {:style {:color (:info colors/tokens)}} "Highlight"]
      ", "
      [:code {:style {:color (:info colors/tokens)}} "CanvasText"]
      ", "
      [:code {:style {:color (:info colors/tokens)}} "Mark"]
      ", "
      [:code {:style {:color (:info colors/tokens)}} "GrayText"]
      ") — the same chrome Windows High Contrast Mode paints, on "
      "demand without flipping the OS-level switch. Default OFF; the "
      "OS HCM detection still works either way. Persists across "
      "reloads in "
      [:code {:style {:color (:info colors/tokens)}} "localStorage"]
      "."]]))

(defn panel
  "The chrome-a11y panel. Renders into a `:right`-placement slot per
  the panel-registration contract. The `_variant-id` arg is accepted
  for signature parity with other story-panel `:render` views but is
  unused — chrome a11y is single-instance and not per-variant.

  Per rf2-18t6p: scans `[data-rf-story-root]` (the chrome wrapper
  stamped by `shell.cljs`), NOT the variant root. Variant a11y lives
  in the sibling `re-frame.story.ui.a11y` panel."
  [_variant-id]
  (a11y/ensure-stylesheet!)
  (let [vs    @violations
        state @run-state
        busy? (or (= state :loading) (= state :running))]
    [:div {:style (:wrap styles) :data-test "story-chrome-a11y-panel"}
     [:div {:style (:header styles)}
      [:span {:style (:section-h styles)} "Chrome A11y (axe-core)"]
      [:button {:style    (merge (:run-button a11y/styles)
                                 (when busy? (:run-busy a11y/styles)))
                :data-test "story-chrome-a11y-run"
                :disabled busy?
                :on-click (fn [_] (when-not busy? (run-axe!)))}
       (case state
         :loading    "loading…"
         :running    "running…"
         :error      "retry"
         :no-root    "retry"
         :no-consent "approve…"
         :idle       "run"
         "re-run")]]
     [:div {:style (:status styles)}
      (case state
        :idle       "click run to scan the Story chrome (variant tree may be included)"
        :loading    "fetching axe-core from CDN…"
        :running    "scanning chrome…"
        :error      "axe-core failed to load (offline, CSP, or SRI mismatch)"
        :no-root    "Story shell not mounted — mount it and re-run"
        :no-consent "axe-core load needs your approval (see below)"
        :done       (str (count vs) " violation(s) found in chrome"))]
     (cond
       (= state :no-consent)
       [consent-prompt-chrome]

       (= state :idle)
       nil

       (empty? vs)
       [:div {:style (:empty styles)} "no violations"]

       :else
       [:div {:data-test "story-chrome-a11y-violations"}
        (for [[i v] (map-indexed vector vs)]
          ^{:key i} [a11y/violation-row v])])
     ;; rf2-846h2 — 'Use system colors' toggle rendered at the foot of
     ;; the panel so the operator-controlled HCM preview shares the
     ;; same accessibility surface as the axe-core consent + run knobs.
     [force-colors-toggle]]))

;; ---- panel registration --------------------------------------------------

(def ^:const panel-id
  "Registered story-panel id for the chrome-a11y panel (rf2-18t6p)."
  :rf.story.panel/chrome-a11y)

(def ^:const panel-render-id
  "View id used by the panel registration. Story's panel-host resolves
  this via `re-frame.core/view` (the standard late-bind lookup)."
  :rf.story.panel/chrome-a11y-view)

(defn install-canonical-chrome-a11y!
  "Register the chrome-a11y panel under `:rf.story.panel/chrome-a11y`
  via `reg-story-panel*`. The panel renders in the `:right` placement
  alongside the variant a11y panel (rf2-18t6p).

  Idempotent. Production builds with `:rf.story/enabled?` false skip
  registration via the `config/enabled?` gate.

  rf2-846h2 — also schedules a one-tick `bootstrap-force-colors!`
  so the persisted 'Use system colors' opt-in re-applies to the live
  DOM as soon as the chrome root mounts. The setTimeout matches the
  recorder-dom / element-inspector shape in `shell.cljs` so the
  React tree has committed `[data-rf-story-root]` before the
  attribute write tries to find it."
  []
  (when config/enabled?
    (rf/reg-view* panel-render-id (fn [variant-id] [panel variant-id]))
    (story-registrar/reg-story-panel*
      panel-id
      {:doc       "axe-core accessibility scanner scoped to the Story chrome root."
       :title     "Chrome a11y"
       :placement :right
       :render    panel-render-id})
    (when (exists? js/setTimeout)
      (js/setTimeout
        (fn []
          (try (bootstrap-force-colors!) (catch :default _ nil)))
        0))))
