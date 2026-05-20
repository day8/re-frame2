(ns day8.re-frame2-causa.panels.chrome-a11y.panel
  "Chrome accessibility (axe-core) panel — Runtime L4 tab (rf2-5r2yj,
  parent rf2-4w88j #28).

  ## Why a separate tab

  Causa is itself a complex interactive surface — tablist, modals,
  drag handles, palette, settings popup, filter pills. Like every
  dev tool it earns the same a11y discipline it expects of host apps.
  This panel dogfoods axe-core against Causa's OWN chrome (the
  `#rf-causa-root` mount node + everything inside it), surfacing
  regressions during dev without depending on the host app to scan
  the tool's chrome.

  Story shipped the equivalent panel in PR #1695 (`re-frame.story.ui.
  chrome-a11y`). This panel mirrors that pattern: same axe-core engine
  (loaded via CDN with explicit opt-in + SRI hash pin), same panel
  shape, scoped to Causa's chrome root rather than Story's.

  ## Relationship to host-app a11y

  Causa does NOT scan host-app DOM. The host app may have its own
  a11y tooling (Story's variant-a11y panel, browser extension, axe-
  core run from the console, etc.) — Causa's chrome-a11y panel is
  strictly self-scoped. The scan root is `#rf-causa-root` (the mount
  node `mount.cljs` allocates and ids); axe-core walks every
  descendant — the L1 ribbon, L2 event list, L3 tab bar, L4 panels,
  the resize handle, every modal that's currently open. Host-app
  chrome is excluded by construction.

  ## CDN opt-in (mirrors rf2-20w5i)

  Per Story's security audit (rf2-20w5i): axe-core is **opt-in only**.
  Running a scan loads `axe-core@4.10.0` from a public CDN
  (cdn.jsdelivr.net) with an SRI integrity hash pinned so a
  compromised mirror fails closed. The dev's first scan is gated on
  an explicit consent click; subsequent scans re-use the persisted
  opt-in. Pre-alpha posture: no Settings toggle, no in-shell
  config — the consent prompt lives inline in the panel.

  Why not vendor axe-core directly? Same Closure :advanced UMD
  parser issue Story documented — axe-core's UMD wrapper trips
  `Block-scoped variable _typeof declared more than once` under
  shadow-cljs's pinned Closure. Until shadow-cljs upgrades Closure
  (or axe-core ships an ESM build), opt-in CDN is the pragmatic path.

  ## Pre-alpha posture

  No toggle to opt out — the panel is always available as an L4 tab,
  runs on demand via the 'run' button. Production builds where the
  Causa shell is gone (Closure DCE drops the entire tool surface
  under `:advanced` when the preload isn't required) never reach
  this ns.

  ## State

  `violations` + `run-state` are single atoms (not per-frame). There's
  exactly one chrome surface per Causa mount, so a per-frame map
  would be over-structured. Mirror of Story's chrome-a11y.

  ## Pure hiccup discipline (rf2-tijr)

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references at the panel boundary. Frame
  isolation comes from the enclosing `[rf/frame-provider {:frame
  :rf/causa}]` in `shell.cljs`. The `r/atom`s for violations / run-
  state are local component state (the panel re-renders by Reagent's
  reactive-deref on @-reads inside `Panel`), not Causa app-db slots —
  the data has no other consumer and survives only as long as the
  shell is mounted."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack display-stack type-scale]]))

;; ---- chrome root selector ------------------------------------------------

(def ^:const chrome-root-id
  "The DOM id `mount.cljs` stamps on the Causa mount root. The shell
  body lives inside this node; axe-core scans every descendant."
  "rf-causa-root")

(def ^:const chrome-root-selector
  "CSS selector resolving to the Causa chrome root. Single-instance:
  `mount.cljs` allocates exactly one `#rf-causa-root` per page so
  this selector resolves to at most one element."
  (str "#" chrome-root-id))

(defn find-chrome-root
  "Resolve the DOM element marked as the Causa chrome root, or nil if
  Causa isn't mounted (e.g. node-runtime tests, or before
  `mount.cljs/open!`).

  Wrapped in try/catch so node-runtime callers receive nil rather
  than a ReferenceError on `js/document`."
  []
  (try
    (let [doc (.-document js/globalThis)]
      (when doc
        (or (.getElementById doc chrome-root-id)
            (.querySelector doc chrome-root-selector))))
    (catch :default _ nil)))

;; ---- axe-core CDN load (opt-in, mirrors Story rf2-20w5i) ----------------

(def ^:const axe-cdn-url
  "Pinned axe-core CDN URL. SRI hash below ensures byte-level
  integrity (a compromised mirror fails closed at the browser's
  hash-verification gate)."
  "https://cdn.jsdelivr.net/npm/axe-core@4.10.0/axe.min.js")

(def ^:const axe-cdn-integrity
  "Subresource Integrity hash for `axe-cdn-url`'s 4.10.0 axe.min.js.
  Identical to Story's pin (rf2-20w5i)."
  "sha384-hU7+BBSOB5dIfLKxLW/kXBTPxNWTSmiQ8F4jiCU0++kNwNoOt7zVkEum1ZqDhASc")

(def ^:const cdn-opt-in-key
  "localStorage key under which the Causa-side CDN opt-in lives.
  Distinct from Story's `rf.story.a11y/cdn-opt-in` — Causa and Story
  approvals are independent (a dev may opt in to Causa's scanner
  without approving Story's)."
  "rf.causa.chrome-a11y/cdn-opt-in")

(defonce
  ^{:doc "In-memory mirror of the persisted opt-in. Initialised from
         `localStorage` on first read; falls back to this when the
         host environment lacks `localStorage`. Wrapped in an
         `r/atom` so the panel re-renders when the opt-in flips."}
  cdn-opt-in-atom
  (r/atom false))

(defonce ^:private cdn-opt-in-bootstrapped? (atom false))

(defn- read-storage-opt-in
  "Best-effort read from `localStorage`. Returns true iff the key
  exists with value `\"true\"`; nil on any error so the in-memory
  atom stays authoritative when storage is blocked."
  []
  (try
    (let [ls (.-localStorage js/globalThis)]
      (when ls
        (= "true" (.getItem ls cdn-opt-in-key))))
    (catch :default _ nil)))

(defn- write-storage-opt-in!
  "Best-effort write to `localStorage`. Silent no-op when storage is
  unavailable. The in-memory atom is always written by the caller;
  this is purely for persistence across reloads."
  [approve?]
  (try
    (let [ls (.-localStorage js/globalThis)]
      (when ls
        (if approve?
          (.setItem ls cdn-opt-in-key "true")
          (.removeItem ls cdn-opt-in-key))))
    (catch :default _ nil))
  nil)

(defn cdn-opt-in?
  "Read the dev's CDN opt-in. Bootstraps from `localStorage` on first
  call so a prior session's approval survives a reload."
  []
  (when-not @cdn-opt-in-bootstrapped?
    (when-let [stored (read-storage-opt-in)]
      (reset! cdn-opt-in-atom stored))
    (reset! cdn-opt-in-bootstrapped? true))
  @cdn-opt-in-atom)

(defn set-cdn-opt-in!
  "Persist the dev's opt-in decision. `approve?` true approves; false
  retracts. The in-memory atom always reflects the choice; the
  `localStorage` write is best-effort."
  [approve?]
  (let [v (boolean approve?)]
    (reset! cdn-opt-in-atom v)
    (reset! cdn-opt-in-bootstrapped? true)
    (write-storage-opt-in! v))
  nil)

(defonce
  ^{:doc "True once axe-core's `<script>` tag has been injected and
         `js/axe` is available. Idempotent loader."}
  axe-loaded?
  (atom false))

(defn ensure-axe-loaded!
  "Inject the axe-core `<script>` tag if not already present. Returns
  a `js/Promise` resolving to `js/axe` once available.

  The injection only fires when the dev has opted in via
  `set-cdn-opt-in!`. Without the opt-in the returned promise rejects
  with `:rf.causa.chrome-a11y/cdn-not-opted-in` so callers surface
  the consent prompt rather than silently loading remote code.

  The injected `<script>` carries an SRI `integrity` attribute pinning
  the expected hash + `crossorigin=\"anonymous\"` so the browser
  enforces hash verification."
  []
  (js/Promise.
    (fn [resolve reject]
      (cond
        @axe-loaded?
        (resolve (.-axe js/window))

        (some? (.-axe js/window))
        (do (reset! axe-loaded? true)
            (resolve (.-axe js/window)))

        (not (cdn-opt-in?))
        (reject (js/Error. "rf.causa.chrome-a11y/cdn-not-opted-in"))

        :else
        (let [script (.createElement js/document "script")]
          (set! (.-src script) axe-cdn-url)
          (set! (.-async script) true)
          (.setAttribute script "integrity" axe-cdn-integrity)
          (.setAttribute script "crossorigin" "anonymous")
          (set! (.-onload script)
                (fn [_]
                  (reset! axe-loaded? true)
                  (resolve (.-axe js/window))))
          (set! (.-onerror script)
                (fn [_]
                  (reject (js/Error. "rf.causa.chrome-a11y/cdn-load-failed"))))
          (.appendChild (.-head js/document) script))))))

;; ---- violations stylesheet ----------------------------------------------

(def ^:const violations-stylesheet
  "Inline outline for any element axe-core flagged. Mirrors Story's
  `[data-rf-a11y-violation]` overlay so devs running both tools see
  the same red outline shape."
  (str
    "[data-rf-causa-a11y-violation] { outline: 2px solid #f04; }"
    "[data-rf-causa-a11y-violation='critical'] { outline-color: #ff0040; }"
    "[data-rf-causa-a11y-violation='serious']  { outline-color: #ff8000; }"
    "[data-rf-causa-a11y-violation='moderate'] { outline-color: #f1c40f; }"
    "[data-rf-causa-a11y-violation='minor']    { outline-color: #888; }"))

(defonce ^:private stylesheet-injected? (atom false))

(defn ensure-stylesheet!
  "Inject the violations stylesheet exactly once per page. Idempotent."
  []
  (when (and (not @stylesheet-injected?)
             (exists? js/document))
    (let [style (.createElement js/document "style")]
      (set! (.-textContent style) violations-stylesheet)
      (.setAttribute style "data-rf-causa-chrome-a11y" "true")
      (.appendChild (.-head js/document) style)
      (reset! stylesheet-injected? true))
    nil))

;; ---- state --------------------------------------------------------------

(defonce
  ^{:doc "Latest chrome-scan violations as a vector. Single atom — one
         chrome surface per Causa mount."}
  violations
  (r/atom []))

(defonce
  ^{:doc "Run state: :idle|:loading|:running|:done|:error|:no-root|:no-consent.
         Drives the panel's button label + status line."}
  run-state
  (r/atom :idle))

(defn reset-state!
  "Test-fixture helper. Clears violations + resets run-state. The CDN
  opt-in is NOT cleared (persisted user decision)."
  []
  (reset! violations [])
  (reset! run-state :idle)
  nil)

;; ---- running axe --------------------------------------------------------

(defn record-violation-overlay!
  "Stamp `data-rf-causa-a11y-violation` on every DOM node axe-core
  flagged. Selectors are scoped to `scope-el` so the overlay never
  decorates host-app nodes outside the Causa chrome root.

  Best-effort: a selector that doesn't match (DOM mutated since the
  scan) is silently skipped."
  [scope-el ^js violation]
  (let [nodes (.-nodes violation)
        root  (or scope-el js/document)]
    (doseq [node (array-seq nodes)]
      (doseq [target (array-seq (.-target node))]
        (try
          (let [el (.querySelector root target)]
            (when el
              (.setAttribute el "data-rf-causa-a11y-violation"
                             (or (.-impact violation) "moderate"))))
          (catch :default _ nil))))))

(defn run-axe!
  "Run axe-core against the Causa chrome root and store violations.
  Returns a `js/Promise` resolving to the violations vector — or nil
  when the chrome root cannot be resolved.

  Reuses the same CDN opt-in gate Story documents in rf2-20w5i: the
  first run prompts for consent; subsequent runs re-use the persisted
  decision."
  ([] (run-axe! (find-chrome-root)))
  ([context]
   (cond
     (nil? context)
     (do
       (reset! run-state :no-root)
       (js/console.warn
         "[causa.chrome-a11y] no chrome root found"
         "— the Causa shell does not appear to be mounted.")
       (js/Promise.resolve nil))

     (not (cdn-opt-in?))
     (do
       (reset! run-state :no-consent)
       (js/Promise.resolve nil))

     :else
     (do
       (reset! run-state :loading)
       (-> (ensure-axe-loaded!)
           (.then
             (fn [^js axe]
               (reset! run-state :running)
               (.run axe context)))
           (.then
             (fn [^js results]
               (let [vs       (.-violations results)
                     scope-el (when (and (some? context)
                                         (some? (.-nodeType context)))
                                context)]
                 (reset! violations (vec (array-seq vs)))
                 (doseq [v (array-seq vs)]
                   (record-violation-overlay! scope-el v))
                 (reset! run-state :done)
                 vs)))
           (.catch
             (fn [e]
               (reset! run-state :error)
               (js/console.error "[causa.chrome-a11y]" e)
               nil)))))))

;; ---- per-violation render -----------------------------------------------

(defn- impact-style
  "Inline-style map for the violation row's left border + colour.
  `impact` is axe-core's string (`critical` / `serious` / `moderate` /
  `minor`); unknown values fall back to `moderate`."
  [impact]
  (case impact
    "critical" {:border-left-color "#ff4040" :color (:red tokens)}
    "serious"  {:border-left-color "#ff8000" :color "#fed"}
    "moderate" {:border-left-color "#f1c40f" :color "#ffd"}
    "minor"    {:border-left-color "#888"    :color "#ccc"}
    {:border-left-color "#f1c40f" :color "#ffd"}))

(defn- violation-row
  "One axe-core violation, rendered as a stripe-bordered row. Hiccup
  only; no Reagent imports."
  [^js v]
  (let [impact       (.-impact v)
        nodes        (.-nodes v)
        first-target (when (and nodes (pos? (.-length nodes)))
                       (let [n0      (aget nodes 0)
                             targets (.-target n0)]
                         (when (and targets (pos? (.-length targets)))
                           (aget targets 0))))]
    [:div {:data-testid      "rf-causa-chrome-a11y-violation"
           :data-a11y-id     (.-id v)
           :data-a11y-impact (or impact "")
           :style            (merge {:padding       "6px 8px"
                                     :margin        "4px 0"
                                     :border-left   "3px solid"
                                     :background    (:bg-3 tokens)
                                     :border-radius "3px"
                                     :font-family   mono-stack
                                     :font-size     (:caption type-scale)}
                                    (impact-style impact))}
     [:div {:style {:font-weight 700 :margin-bottom "2px"}}
      (.-help v)]
     [:div {:style {:color (:text-secondary tokens)
                    :font-size (:micro type-scale)}}
      (.-description v)]
     (when first-target
       [:div {:style {:color       (:cyan tokens)
                      :font-family mono-stack
                      :font-size   (:micro type-scale)
                      :margin-top  "2px"}}
        (str "→ " first-target)])
     [:div {:style {:color     (:text-tertiary tokens)
                    :font-size (:micro type-scale)}}
      (str ":" (.-id v) " · " (or impact "moderate"))]]))

;; ---- consent prompt -----------------------------------------------------

(defn- consent-prompt
  "Inline consent UI shown when the dev hasn't opted in to the CDN
  load. Text describes the egress in plain words so the dev can
  decide whether their environment permits it."
  []
  [:div {:data-testid "rf-causa-chrome-a11y-consent"
         :style       {:padding     "12px"
                       :border-top  (str "1px dashed " (:border-default tokens))
                       :margin-top  "4px"
                       :font-family sans-stack
                       :color       (:text-primary tokens)}}
   [:div {:style {:font-weight 700
                  :color       (:red tokens)
                  :margin-bottom "6px"
                  :font-size   (:caption type-scale)}}
    "axe-core not loaded"]
   [:div {:style {:font-size   (:micro type-scale)
                  :line-height "1.4"
                  :color       (:text-secondary tokens)
                  :margin-bottom "6px"}}
    "Running an a11y scan loads "
    [:code {:style {:color (:cyan tokens) :font-family mono-stack}} "axe-core@4.10.0"]
    " from a public CDN ("
    [:code {:style {:color (:cyan tokens) :font-family mono-stack}} "cdn.jsdelivr.net"]
    "). The remote JS gets full DOM access to this page; an SRI hash "
    "pinned in the loader detects tampering, but the dependency itself "
    "is a trust call. Causa's app state never leaves the browser."]
   [:div {:style {:font-size (:micro type-scale)
                  :color     (:text-secondary tokens)
                  :margin-bottom "10px"}}
    "Approve once per browser; the opt-in is remembered in "
    [:code {:style {:color (:cyan tokens) :font-family mono-stack}} "localStorage"]
    " under "
    [:code {:style {:color (:cyan tokens) :font-family mono-stack}} cdn-opt-in-key]
    "."]
   [:button {:data-testid "rf-causa-chrome-a11y-approve"
             :on-click    (fn [_]
                            (set-cdn-opt-in! true)
                            (run-axe!))
             :style       {:background    (:accent-violet tokens)
                           :color         "white"
                           :border        "none"
                           :border-radius "3px"
                           :padding       "4px 12px"
                           :cursor        "pointer"
                           :font-family   sans-stack
                           :font-size     (:caption type-scale)}}
    "enable axe-core + scan"]])

;; ---- public Panel view --------------------------------------------------

(rf/reg-view Panel
  "The Chrome A11y tab's root view. Mirrors Story's chrome-a11y
  panel — header strip with title + run button, status line, and
  the per-violation feed below.

  Per rf2-in6l2 `reg-view`-registered so any subs the panel reads
  resolve through the enclosing `[rf/frame-provider {:frame
  :rf/causa}]` in `shell.cljs`. Today the panel reads no Causa
  app-db slots — its state lives in the module-level r/atoms — but
  the reg-view discipline keeps the door open for future
  reactive surfaces (e.g. surfacing the violation count on the L3
  tab badge)."
  []
  (ensure-stylesheet!)
  (let [vs    @violations
        state @run-state
        busy? (or (= state :loading) (= state :running))]
    [:section {:data-testid "rf-causa-chrome-a11y"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      (:body type-scale)}}
     [:header {:style {:padding       "12px 16px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))}}
      [:div {:style {:display     "flex"
                     :align-items "center"
                     :gap         "12px"}}
       ;; rf2-5kfxe.8 — domain-coloured accent stripe. Chrome a11y
       ;; sits in the diagnostics group alongside Issues — the
       ;; `:chrome-a11y` entry in `theme.tokens/panel-domain->token`
       ;; maps to `:red` so the two tabs read as one family at the
       ;; right edge of the tab strip.
       [:h1 {:style (merge {:font-size      "20px"
                            :font-family    display-stack
                            :font-weight    600
                            :letter-spacing "-0.01em"
                            :margin         0
                            :color          (:text-primary tokens)
                            :display        "flex"
                            :align-items    "center"
                            :gap            "8px"}
                           (t/accent-stripe-style :chrome-a11y))}
        ;; rf2-ezx8w — spec/021 §17.1.5 per-panel header icon. ✦ in
        ;; :red (Chrome A11y's diagnostics-group sibling accent, shared
        ;; with Issues per panel-domain->token).
        [:span {:data-testid "rf-causa-chrome-a11y-panel-icon"
                :aria-hidden "true"
                :style       (t/panel-icon-style :chrome-a11y)}
         (:chrome-a11y t/panel-icon)]
        "Chrome A11y"]
       [:span {:data-testid "rf-causa-chrome-a11y-count"
               :style       {:font-size   (:caption type-scale)
                             :color       (:text-tertiary tokens)
                             :font-family mono-stack}}
        (cond
          (= state :done) (str (count vs) " violation"
                               (when (not= 1 (count vs)) "s"))
          (= state :idle) "axe-core · click run"
          :else           "")]
       [:button {:data-testid "rf-causa-chrome-a11y-run"
                 :disabled    busy?
                 :on-click    (fn [_] (when-not busy? (run-axe!)))
                 :style       {:margin-left   "auto"
                               :background    (if busy?
                                                (:bg-3 tokens)
                                                (:accent-violet tokens))
                               :color         "white"
                               :border        "none"
                               :border-radius "3px"
                               :padding       "4px 12px"
                               :cursor        (if busy? "wait" "pointer")
                               :font-family   sans-stack
                               :font-size     (:caption type-scale)}}
        (case state
          :loading    "loading…"
          :running    "running…"
          :error      "retry"
          :no-root    "retry"
          :no-consent "approve…"
          :idle       "run"
          "re-run")]]
      [:div {:data-testid "rf-causa-chrome-a11y-status"
             :style       {:color       (:text-secondary tokens)
                           :font-size   (:micro type-scale)
                           :font-family sans-stack
                           :margin-top  "6px"}}
       (case state
         :idle       "click run to scan the Causa chrome at #rf-causa-root"
         :loading    "fetching axe-core from CDN…"
         :running    "scanning Causa chrome…"
         :error      "axe-core failed to load (offline, CSP, or SRI mismatch)"
         :no-root    "Causa shell not mounted — open it (Ctrl+Shift+C) and retry"
         :no-consent "axe-core load needs your approval (see below)"
         :done       (let [n (count vs)]
                       (cond
                         (zero? n) "no violations in the Causa chrome"
                         (= 1 n)   "1 violation found in the Causa chrome"
                         :else     (str n " violations found in the Causa chrome"))))]]
     [:div {:style {:flex 1 :overflow "auto" :padding "8px 16px"}}
      (cond
        (= state :no-consent)
        [consent-prompt]

        (= state :idle)
        [:div {:data-testid "rf-causa-chrome-a11y-idle"
               :style       {:color       (:text-tertiary tokens)
                             :font-style  "italic"
                             :font-family sans-stack
                             :font-size   (:caption type-scale)
                             :padding     "12px 0"}}
         "Click run to scan the Causa chrome. The scan is scoped to "
         [:code {:style {:font-family mono-stack
                         :color (:cyan tokens)}}
          chrome-root-selector]
         " — host-app DOM is excluded."]

        (empty? vs)
        [:div {:data-testid "rf-causa-chrome-a11y-empty"
               :style       {:padding     "12px 0"
                             :font-family sans-stack
                             :font-size   (:caption type-scale)
                             :color       (:text-secondary tokens)}}
         [:span {:style {:color       (:green tokens)
                         :font-size   "16px"
                         :font-weight 700
                         :margin-right "8px"}}
          "✓"]
         "No violations."]

        :else
        (into [:div {:data-testid "rf-causa-chrome-a11y-violations"}]
              (for [[i v] (map-indexed vector vs)]
                ^{:key i} [violation-row v])))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Chrome A11y tab's Causa-side
  registrations. Registers the Runtime L4 tab under
  `:id :chrome-a11y`, `:mnem y` (for a11Y — `a` / `c` / `i` are taken
  by App-db / Machines Canvas / Issues respectively), `:modes
  #{:runtime}`, `:order 8` (sits after Issues at order 7 — both tabs
  occupy the diagnostics group at the right end of the tab strip).

  Today the panel has no Causa app-db registrations — its state lives
  in module-level r/atoms (`violations`, `run-state`, `cdn-opt-in-
  atom`) because the data has no other consumer. Future iterations
  that surface the violation count on the L3 tab badge would register
  a `:rf.causa.chrome-a11y/violation-count` sub here.

  The `:panel` value resolves through the `reg-view`-registered
  `Panel` above so subscriptions inside the panel resolve through
  the shell's `[rf/frame-provider {:frame :rf/causa}]`."
  []
  (panel-registry/reg-l4-tab!
    {:id    :chrome-a11y
     :label "Chrome A11y"
     :mnem  "y"
     :modes #{:runtime}
     :order 8
     :panel Panel})
  nil)
