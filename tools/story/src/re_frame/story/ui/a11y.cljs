(ns re-frame.story.ui.a11y
  "Accessibility (axe-core) panel. Per IMPL-SPEC §11.1 (v1.0 item 2)
  + Stage 6 (rf2-zhwd). Phase-2 §5.1 #2.

  Runs axe-core against the variant's rendered DOM and surfaces
  violations:
  - inline as red overlays on offending elements (via a stylesheet
    that targets `[data-rf-a11y-violation]`)
  - in a violations list rendered in the right-panel slot

  Integrates with `:rf.assert/no-warnings` — when an axe-core run
  produces violations the panel emits warning trace events into the
  active variant's frame so a play sequence with
  `[:rf.assert/no-warnings]` records the violation as a failure.

  ## axe-core source: opt-in CDN (rf2-20w5i)

  Per rf2-20w5i (security audit): axe-core is **opt-in only**. Pre-fix
  the panel injected `axe.min.js` from a public CDN on first panel
  open, unconditionally. Post-fix the CDN fetch is **default-OFF**;
  the panel surfaces a clear consent prompt explaining that running
  the scan loads remote JS with full DOM access to the dev's session,
  and the dev must click 'enable' to proceed. The opt-in survives
  reloads (persisted in `localStorage` under `:rf.story.a11y/cdn-opt-in`).

  Why not vendor axe-core directly? The audit's preferred fix
  (`:require [\"axe-core\" ...]` static-bundling) trips Closure
  :advanced's strict ECMAScript parser on axe-core's UMD wrapper
  (`function te(e){return(te=...)(e)}` reads as a duplicate
  block-scoped declaration). Closure 2025-vintage rejects this with
  `Block-scoped variable _typeof declared more than once`. Until
  shadow-cljs upgrades Closure (or axe-core ships an ESM build), the
  pragmatic path is opt-in-CDN: the dev's first scan is gated on
  explicit consent, the variant's app-db never traverses the wire
  (axe-core's protocol is one-way: the dev's browser loads the JS,
  there is no telemetry channel back), and the load only fires when
  the dev clicks 'enable'.

  Production builds (`:rf.story/enabled?` false under `:advanced`)
  never reach this ns; Closure DCE drops the panel along with the
  rest of the Story UI shell, so the opt-in path is dev-only.

  ## State

  Violations are stored in a local atom `frame-id → [violation ...]`
  keyed by the variant frame id. The right-panel component reads this
  atom reactively."
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.trace :as trace]
            [re-frame.story.config :as config]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- state ---------------------------------------------------------------

(defonce
  ^{:doc "Per-frame violations bag. `{frame-id → [violation ...]}`.
         A violation is an axe-core result map with `:id`, `:impact`,
         `:description`, `:help`, `:nodes`."}
  violations-by-frame
  (r/atom {}))

(defonce
  ^{:doc "Per-frame run state.
         `{frame-id → :idle|:loading|:running|:done|:error|:no-root|:no-consent}`.
         `:no-consent` means the dev hasn't approved the CDN load yet
         (per rf2-20w5i)."}
  run-state
  (r/atom {}))

(defn drop-frame-state!
  "Clear all a11y state for `frame-id`. Called from the canvas /
  shell teardown when a variant frame is destroyed."
  [frame-id]
  (swap! violations-by-frame dissoc frame-id)
  (swap! run-state           dissoc frame-id)
  nil)

(defn reset-state!
  "Clear every per-frame slot. Test-fixture helper. Note that the
  CDN opt-in is NOT cleared here — the opt-in is a persisted user
  decision, not per-frame state. Tests that need to assert against
  a specific opt-in shape should call `set-cdn-opt-in!` explicitly."
  []
  (reset! violations-by-frame {})
  (reset! run-state {})
  nil)

;; ---- axe-core CDN load (opt-in per rf2-20w5i) ---------------------------
;;
;; Per the security audit, axe-core is loaded from a public CDN only
;; when the dev explicitly opts in. The opt-in is persisted in
;; `localStorage` under `cdn-opt-in-key` so a single click per session
;; (and not per panel-open) gives consent. The pinned version is
;; `axe-core@4.10.0`; the URL uses the `integrity` attribute pre-fix
;; was omitted, post-fix is added so a compromised mirror is detected
;; client-side.

(def ^:const axe-cdn-url
  "URL the panel loads axe-core from when the dev has opted in. Pinned
  to a specific version + carries an SRI integrity hash so a mirror
  serving altered content fails closed."
  "https://cdn.jsdelivr.net/npm/axe-core@4.10.0/axe.min.js")

(def ^:const axe-cdn-integrity
  "Subresource Integrity hash for `axe-cdn-url`'s 4.10.0 axe.min.js.
  Computed from the published bytes via:

      curl -s https://cdn.jsdelivr.net/npm/axe-core@4.10.0/axe.min.js \\
        | openssl dgst -sha384 -binary | openssl base64 -A

  Without this, a compromised CDN could serve altered JS to the
  dev's session and Story would happily execute it. With it, the
  browser rejects the load on hash mismatch — the supply-chain
  trust boundary lands at the byte-level, not at the URL."
  "sha384-hU7+BBSOB5dIfLKxLW/kXBTPxNWTSmiQ8F4jiCU0++kNwNoOt7zVkEum1ZqDhASc")

(def ^:const cdn-opt-in-key
  "localStorage key under which the dev's CDN opt-in lives. A string
  `\"true\"` means the load is approved; absent / any other value means
  the panel shows the consent prompt."
  "rf.story.a11y/cdn-opt-in")

(defonce
  ^{:doc "In-memory mirror of the persisted opt-in. Initialised from
         `localStorage` on first read; falls back to this when the
         host environment lacks `localStorage` (node-runtime tests,
         strict-CSP browsers with storage blocked). Wrapped in an
         `r/atom` so the panel re-renders when the opt-in flips."}
  cdn-opt-in-atom
  (r/atom false))

(defonce ^:private cdn-opt-in-bootstrapped? (atom false))

(defn- read-storage-opt-in
  "Best-effort read from `localStorage`. Returns true iff the key
  exists with value `\"true\"`; returns nil (NOT false) on any error,
  so the in-memory atom stays authoritative when storage is blocked."
  []
  (try
    (let [ls (.-localStorage js/globalThis)]
      (when ls
        (= "true" (.getItem ls cdn-opt-in-key))))
    (catch :default _ nil)))

(defn- write-storage-opt-in!
  "Best-effort write to `localStorage`. Silently no-ops if storage is
  unavailable. The in-memory atom is always written by the caller; this
  is purely for persistence across reloads."
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
  "Read the dev's CDN opt-in. Returns true iff the dev has approved
  the load in this session. On first call the value is bootstrapped
  from `localStorage` (so a prior session's approval survives a
  reload); subsequent calls read the in-memory atom."
  []
  (when-not @cdn-opt-in-bootstrapped?
    (when-let [stored (read-storage-opt-in)]
      (reset! cdn-opt-in-atom stored))
    (reset! cdn-opt-in-bootstrapped? true))
  @cdn-opt-in-atom)

(defn set-cdn-opt-in!
  "Persist the dev's opt-in decision. `approve?` true approves; false
  retracts. The in-memory atom always reflects the choice; the
  `localStorage` write is best-effort (no-op when storage is blocked)."
  [approve?]
  (let [v (boolean approve?)]
    (reset! cdn-opt-in-atom v)
    (reset! cdn-opt-in-bootstrapped? true)
    (write-storage-opt-in! v))
  nil)

(defonce
  ^{:doc "True once axe-core's `<script>` tag has been injected and
         the global `js/axe` is available. Idempotent loader."}
  axe-loaded?
  (atom false))

(defn ensure-axe-loaded!
  "Inject the axe-core `<script>` tag if not already present. Returns
  a `js/Promise` that resolves once `js/axe` is available.

  The injection only fires when the dev has opted in via
  `set-cdn-opt-in!`. Without the opt-in the returned promise rejects
  with `:rf.story.a11y/cdn-not-opted-in` so callers surface the
  consent prompt rather than silently loading remote code.

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
        (reject (js/Error. "rf.story.a11y/cdn-not-opted-in"))

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
                  (reject (js/Error. "rf.story.a11y/cdn-load-failed"))))
          (.appendChild (.-head js/document) script))))))

;; ---- running axe --------------------------------------------------------

(defn- emit-warning-for-violation
  "Per IMPL-SPEC §11.1: axe-core violations integrate with
  `:rf.assert/no-warnings`. We emit a `:warning` trace event into the
  variant's frame so the play-runner's per-frame trace listener
  captures it and `:rf.assert/no-warnings` records a failure.

  The trace event piggybacks on the existing warning op-type per
  spec/009 §Trace bus."
  [frame-id ^js violation]
  (trace/emit!
    :warning
    :rf.story.a11y/violation
    {:frame  frame-id
     :impact (.-impact violation)
     :id     (.-id violation)
     :help   (.-help violation)}))

(defn variant-root-selector
  "CSS selector for the variant root element of `frame-id`. Per
  rf2-qgms1: canvas.cljs / workspace.cljc stamp
  `data-rf-story-variant-root` (with the variant id pr-str'd) on the
  immediate wrapper around the user-authored decorated view, so a11y
  can scope axe-core's scan to ONLY the variant's tree — excluding
  Story chrome (sidebar, toolbar, panels, title bar).

  Returns a string CSS selector (`[data-rf-story-variant-root='…']`)
  with the pr-str'd id properly escaped so namespaced keywords (e.g.
  `:story.counter/incrementing`) survive the quoting boundary."
  [frame-id]
  (let [printed (pr-str frame-id)
        ;; CSS attribute-selector values quoted in single-quotes: escape
        ;; backslashes and embedded single-quotes. pr-str of a keyword
        ;; produces ASCII without quotes, so in practice this is a
        ;; no-op for normal frame-ids — but defensive against odd ids.
        escaped (-> printed
                    (string/replace #"\\" "\\\\\\\\")
                    (string/replace #"'"  "\\\\'"))]
    (str "[data-rf-story-variant-root='" escaped "']")))

(defn find-variant-root
  "Resolve the DOM element marked as `frame-id`'s variant root, or nil
  if not mounted (e.g. the user is viewing the variant in :docs or
  :test mode, or has selected a workspace). Per rf2-qgms1 the canvas /
  workspace stamp `data-rf-story-variant-root` on the wrapper around
  the user-authored tree.

  Wrapped in a try/catch so the helper is safe to call in node-runtime
  test contexts where `js/document` is undefined (raw access throws
  ReferenceError); a node-runtime call returns nil and `run-axe!`'s
  caller surfaces a :no-root state."
  [frame-id]
  (try
    (let [doc (.-document js/globalThis)]
      (when doc
        (.querySelector doc (variant-root-selector frame-id))))
    (catch :default _ nil)))

(defn- record-violation-overlay!
  "Decorate the violation's DOM nodes so the inline stylesheet
  highlights them. Each axe-core node has `:target` (a CSS selector
  list); we attach `data-rf-a11y-violation` to each matching element.

  Per rf2-qgms1 the query is rooted at `scope-el` (the variant root)
  so we never decorate Story-chrome nodes — even if axe-core somehow
  returned a selector matching outside the scope, the overlay stays
  inside the variant.

  Best-effort: if the selectors don't match (e.g. the DOM mutated
  since the run) the overlay simply doesn't appear."
  [scope-el ^js violation]
  (let [nodes (.-nodes violation)
        root  (or scope-el js/document)]
    (doseq [node (array-seq nodes)]
      (doseq [target (array-seq (.-target node))]
        (try
          (let [el (.querySelector root target)]
            (when el
              (.setAttribute el "data-rf-a11y-violation"
                             (or (.-impact violation) "moderate"))))
          (catch :default _ nil))))))

(defn run-axe!
  "Run axe-core against the variant root for `frame-id` and store
  violations under `frame-id`. Returns a `js/Promise` that resolves to
  the violations vector — or nil when the variant root cannot be
  resolved (e.g. the user is in :docs/:test mode, or the variant is
  not currently mounted).

  Per rf2-qgms1 the default scope is the variant's
  `data-rf-story-variant-root` element, NOT `document.body`. Scanning
  the whole body flagged Story's OWN chrome (sidebar buttons, toolbar
  tabs, side-rail items) as violations — which is wrong: Story chrome
  a11y is Story's concern, not the variant author's.

  An explicit `context` second-arg overrides the lookup (used by
  tests). Pass an Element, a CSS-selector string, or an axe-core
  context object.

  Per IMPL-SPEC §11.1 surfaces violations into `:rf.assert/no-warnings`
  via the trace-warning hook."
  ([frame-id]
   (run-axe! frame-id (find-variant-root frame-id)))
  ([frame-id context]
   (cond
     ;; No variant root and no explicit context → surface the
     ;; degraded state instead of silently scanning the wrong tree.
     (nil? context)
     (do
       (swap! run-state assoc frame-id :no-root)
       (js/console.warn
         "[story.a11y] no variant root found for"
         (pr-str frame-id)
         "— switch to :dev mode to mount the variant, or pass an explicit context.")
       (js/Promise.resolve nil))

     ;; CDN opt-in gate (rf2-20w5i): surface the consent prompt instead
     ;; of silently triggering the load. Callers must call `set-cdn-opt-in!`
     ;; (typically wired to a 'enable axe-core' button in the panel
     ;; that explains the egress) before re-invoking `run-axe!`.
     (not (cdn-opt-in?))
     (do
       (swap! run-state assoc frame-id :no-consent)
       (js/Promise.resolve nil))

     :else
     (do
       (swap! run-state assoc frame-id :loading)
       (-> (ensure-axe-loaded!)
           (.then
             (fn [^js axe]
               (swap! run-state assoc frame-id :running)
               (.run axe context)))
           (.then
             (fn [^js results]
               (let [vs        (.-violations results)
                     scope-el  (when (and (some? context)
                                          (some? (.-nodeType context)))
                                 context)]
                 (swap! violations-by-frame assoc frame-id (vec (array-seq vs)))
                 (doseq [v (array-seq vs)]
                   (record-violation-overlay! scope-el v)
                   (emit-warning-for-violation frame-id v))
                 (swap! run-state assoc frame-id :done)
                 vs)))
           (.catch
             (fn [e]
               (swap! run-state assoc frame-id :error)
               (js/console.error "[story.a11y]" e)
               nil)))))))

;; ---- styling ------------------------------------------------------------

(def ^:private styles
  {:wrap        {:padding "8px"
                 :background (:bg-2 colors/tokens)
                 :border-top "1px solid #444"
                 :color (:text-primary colors/tokens)
                 :font-family mono-stack
                 :font-size "11px"}
   :header      {:display "flex"
                 :justify-content "space-between"
                 :align-items "center"
                 :margin-bottom "8px"}
   :section-h   {:font-weight "bold"
                 :color (:text-secondary colors/tokens)
                 :text-transform "uppercase"
                 :font-size "10px"
                 :letter-spacing "0.5px"}
   :run-button  {:padding "4px 10px"
                 :background (:accent-amber colors/tokens)
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"}
   :run-busy    {:background "#666"
                 :cursor "wait"}
   :status      {:color (:text-secondary colors/tokens) :font-size "10px" :margin-top "4px"}
   :empty       {:color (:text-tertiary colors/tokens) :font-style "italic" :padding "4px 0"}
   :violation   {:padding "6px 8px"
                 :margin "4px 0"
                 :border-left "3px solid"
                 :background (:danger-bg colors/tokens)}
   :impact-critical {:border-left-color "#ff4040"
                     :color (:danger colors/tokens)}
   :impact-serious  {:border-left-color "#ff8000"
                     :color "#fed"}
   :impact-moderate {:border-left-color "#f1c40f"
                     :color "#ffd"}
   :impact-minor    {:border-left-color "#888"
                     :color "#ccc"}
   :v-help      {:font-weight "bold" :margin-bottom "2px"}
   :v-desc      {:color "#aaa" :font-size "10px"}
   :v-target    {:color (:info colors/tokens) :font-size "10px"
                 :font-family mono-stack
                 :margin-top "2px"}
   :overlay-css {:position "absolute"
                 :pointer-events "none"
                 :z-index 9999}})

;; ---- inline-violations stylesheet ---------------------------------------
;;
;; A single global `<style>` tag in the host document targets
;; `[data-rf-a11y-violation]`. Critical / serious / moderate / minor
;; each get a distinct outline colour.

(def ^:const violations-stylesheet
  (str
    "[data-rf-a11y-violation] { outline: 2px solid #f04; }"
    "[data-rf-a11y-violation='critical'] { outline-color: #ff0040; }"
    "[data-rf-a11y-violation='serious']  { outline-color: #ff8000; }"
    "[data-rf-a11y-violation='moderate'] { outline-color: #f1c40f; }"
    "[data-rf-a11y-violation='minor']    { outline-color: #888; }"))

(defonce ^:private stylesheet-injected? (atom false))

(defn- ensure-stylesheet!
  []
  (when (and config/enabled?
             (not @stylesheet-injected?)
             (some? js/document))
    (let [style (.createElement js/document "style")]
      (set! (.-textContent style) violations-stylesheet)
      (.setAttribute style "data-rf-story-a11y" "true")
      (.appendChild (.-head js/document) style)
      (reset! stylesheet-injected? true))
    nil))

;; ---- panel components ---------------------------------------------------

(defn- impact-style [impact]
  (case impact
    "critical" (:impact-critical styles)
    "serious"  (:impact-serious styles)
    "moderate" (:impact-moderate styles)
    "minor"    (:impact-minor styles)
    (:impact-moderate styles)))

(defn- violation-row
  [^js v]
  (let [impact (.-impact v)
        nodes  (.-nodes v)
        first-target (when (and nodes (pos? (.-length nodes)))
                       (let [n0 (aget nodes 0)
                             targets (.-target n0)]
                         (when (and targets (pos? (.-length targets)))
                           (aget targets 0))))]
    [:div {:style            (merge (:violation styles) (impact-style impact))
           :data-test        "story-a11y-violation"
           :data-a11y-id     (.-id v)
           :data-a11y-impact (or impact "")
           :data-a11y-help   (.-help v)
           :data-a11y-target (or first-target "")}
     [:div {:style (:v-help styles)} (.-help v)]
     [:div {:style (:v-desc styles)} (.-description v)]
     (when first-target
       [:div {:style (:v-target styles)} (str "→ " first-target)])
     [:div {:style {:color (:text-tertiary colors/tokens) :font-size "10px"}}
      (str ":" (.-id v) " · " (or impact "moderate"))]]))

(defn- consent-prompt
  "Rendered when the dev hasn't yet opted in to the CDN load (per
  rf2-20w5i). Clicking 'enable' persists the approval to
  `localStorage` so subsequent panel opens re-use it. The text is
  load-bearing — it describes the egress in plain words so the dev
  can decide whether their environment permits it."
  [variant-id]
  [:div {:style {:padding "8px 0"
                 :border-top "1px dashed #555"
                 :margin-top "4px"
                 :color (:text-primary colors/tokens)}}
   [:div {:style {:font-weight "bold"
                  :color (:danger colors/tokens)
                  :margin-bottom "6px"}}
    "axe-core not loaded"]
   [:div {:style {:font-size "10px"
                  :line-height "1.4"
                  :color (:text-secondary colors/tokens)
                  :margin-bottom "6px"}}
    "Running an a11y scan loads "
    [:code {:style {:color (:info colors/tokens)}} "axe-core@4.10.0"]
    " from a public CDN ("
    [:code {:style {:color (:info colors/tokens)}} "cdn.jsdelivr.net"]
    "). The remote JS gets full DOM access to this Story page; the SRI "
    "hash pinned in the loader detects tampering, but the dependency "
    "itself is a trust call. No variant state leaves the browser."]
   [:div {:style {:font-size "10px"
                  :color (:text-secondary colors/tokens)
                  :margin-bottom "8px"}}
    "Approve once per browser; the opt-in is remembered in "
    [:code {:style {:color (:info colors/tokens)}} "localStorage"] "."]
   [:button {:style    (:run-button styles)
             :on-click (fn [_]
                         (set-cdn-opt-in! true)
                         (when variant-id (run-axe! variant-id)))}
    "enable axe-core + scan"]])

(defn panel
  "The a11y panel. Renders into the right panel of the shell. Stage 6
  (rf2-zhwd) — registers as `:rf.story.panel/a11y`."
  [variant-id]
  (ensure-stylesheet!)
  (let [vs    (get @violations-by-frame variant-id [])
        state (get @run-state           variant-id :idle)
        busy? (or (= state :loading) (= state :running))]
    [:div {:style (:wrap styles)}
     [:div {:style (:header styles)}
      [:span {:style (:section-h styles)} "A11y (axe-core)"]
      [:button {:style    (merge (:run-button styles)
                                 (when busy? (:run-busy styles)))
                :disabled busy?
                :on-click (fn [_] (when (and variant-id (not busy?))
                                    (run-axe! variant-id)))}
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
        :idle       "click run to scan the variant (Story chrome is excluded)"
        :loading    "fetching axe-core from CDN…"
        :running    "scanning…"
        :error      "axe-core failed to load (offline, CSP, or SRI mismatch)"
        :no-root    "no variant mounted — switch to :dev mode and re-run"
        :no-consent "axe-core load needs your approval (see below)"
        :done       (str (count vs) " violation(s) found in variant"))]
     (cond
       (= state :no-consent)
       [consent-prompt variant-id]

       (= state :idle)
       nil

       (empty? vs)
       [:div {:style (:empty styles)} "no violations"]

       :else
       [:div
        (for [[i v] (map-indexed vector vs)]
          ^{:key i} [violation-row v])])]))

;; ---- panel registration -------------------------------------------------

(def ^:const panel-id
  "Registered story-panel id for the a11y panel."
  :rf.story.panel/a11y)

(def ^:const panel-render-id
  "View id used by the panel registration. The Story shell looks up the
  view via `re-frame.core/view` (late-bind per spec/004). The panel
  component is the view itself."
  :rf.story.panel/a11y-view)

(defn install-canonical-a11y!
  "Register the a11y panel under `:rf.story.panel/a11y` via
  `reg-story-panel*`. The panel renders in the `:right` placement
  (the canonical right-panel slot). Stage 6 (rf2-zhwd).

  The panel registration is opt-in by the consumer via the shell's
  `:panel-visibility` map. By default the a11y slot is off (don't
  bloat the right pane until the user explicitly opens it)."
  []
  (when config/enabled?
    ;; Register the panel-view as a re-frame view so the panel system
    ;; can resolve it via the standard late-bind path.
    (rf/reg-view* panel-render-id (fn [variant-id] [panel variant-id]))
    ;; Register the story-panel itself.
    (story-registrar/reg-story-panel*
      panel-id
      {:doc       "axe-core accessibility scanner — inline panel."
       :title     "a11y"
       :placement :right
       :render    panel-render-id})))
