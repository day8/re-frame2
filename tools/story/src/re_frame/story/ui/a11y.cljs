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

  ## Lazy loading

  axe-core is heavy (~700KB). The script tag injects on first panel
  open; subsequent opens reuse the loaded global. Production builds
  with `:rf.story/enabled?` false never reach this ns (the require
  graph from the disabled shell is DCE-pruned), so the lazy-load logic
  doesn't ship to prod.

  ## Bundle isolation

  - axe-core itself is fetched from a CDN (`cdn.jsdelivr.net`) so the
    Story bundle stays lean.
  - The CLJS wrapper is part of the Story bundle but DCE'd when
    disabled.

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
            [re-frame.story.ui.state :as state]))

;; ---- state ---------------------------------------------------------------

(defonce
  ^{:doc "Per-frame violations bag. `{frame-id → [violation ...]}`.
         A violation is an axe-core result map with `:id`, `:impact`,
         `:description`, `:help`, `:nodes`."}
  violations-by-frame
  (r/atom {}))

(defonce
  ^{:doc "Per-frame run state. `{frame-id → :idle|:loading|:running|:done|:error}`."}
  run-state
  (r/atom {}))

(defonce
  ^{:doc "True once axe-core's `<script>` tag has been injected and
         the global `js/axe` is available. Idempotent loader."}
  axe-loaded?
  (atom false))

(defn drop-frame-state!
  "Clear all a11y state for `frame-id`. Called from the canvas /
  shell teardown when a variant frame is destroyed."
  [frame-id]
  (swap! violations-by-frame dissoc frame-id)
  (swap! run-state           dissoc frame-id)
  nil)

(defn reset-state!
  "Clear every per-frame slot. Test-fixture helper."
  []
  (reset! violations-by-frame {})
  (reset! run-state {})
  nil)

;; ---- lazy load axe-core --------------------------------------------------

(def ^:const axe-cdn-url
  "axe-core's CDN URL. Pin to a recent stable version (4.x line).
  Per IMPL-SPEC §6.2 only loads on first panel open — the script tag
  injects from `ensure-axe-loaded!`.

  Per rf2-su313 (pragmatic stance, 2026-05-14): kept as a third-party
  CDN fetch in v1. Bundling axe-core (~700 KB minified) would balloon
  the Story bundle for the minority of devs who open the a11y panel;
  the lazy-load shape means the bundle pays nothing until the first
  panel open. Hosts on offline / strict-CSP networks see a load-failure
  message in the panel; the rest of the Story shell is unaffected.
  Documented at the v1 SOTA tier in
  `tools/story/spec/005-SOTA-Features.md` §Third-party network egress."
  "https://cdn.jsdelivr.net/npm/axe-core@4.10.0/axe.min.js")

(defn ensure-axe-loaded!
  "Inject the axe-core `<script>` tag if not already present. Returns
  a `js/Promise` that resolves once `js/axe` is available.

  Idempotent — subsequent calls return a resolved promise immediately."
  []
  (js/Promise.
    (fn [resolve reject]
      (cond
        @axe-loaded?
        (resolve js/axe)

        (some? (.-axe js/window))
        (do (reset! axe-loaded? true)
            (resolve js/axe))

        :else
        (let [script (.createElement js/document "script")]
          (set! (.-src script) axe-cdn-url)
          (set! (.-async script) true)
          (set! (.-onload script)
                (fn [_]
                  (reset! axe-loaded? true)
                  (resolve (.-axe js/window))))
          (set! (.-onerror script)
                (fn [e]
                  (reject (str "axe-core failed to load: " e))))
          (.appendChild (.-head js/document) script))))))

;; ---- running axe --------------------------------------------------------

(defn- emit-warning-for-violation
  "Per IMPL-SPEC §11.1: axe-core violations integrate with
  `:rf.assert/no-warnings`. We emit a `:warning` trace event into the
  variant's frame so the play-runner's per-frame trace listener
  captures it and `:rf.assert/no-warnings` records a failure.

  The trace event piggybacks on the existing warning op-type per
  spec/009 §Trace bus."
  [frame-id violation]
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
  [scope-el violation]
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

     :else
     (do
       (swap! run-state assoc frame-id :loading)
       (-> (ensure-axe-loaded!)
           (.then
             (fn [axe]
               (swap! run-state assoc frame-id :running)
               (.run axe context)))
           (.then
             (fn [results]
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
                 :background "#252526"
                 :border-top "1px solid #444"
                 :color "#cccccc"
                 :font-family "monospace"
                 :font-size "11px"}
   :header      {:display "flex"
                 :justify-content "space-between"
                 :align-items "center"
                 :margin-bottom "8px"}
   :section-h   {:font-weight "bold"
                 :color "#b0b0b0"
                 :text-transform "uppercase"
                 :font-size "10px"
                 :letter-spacing "0.5px"}
   :run-button  {:padding "4px 10px"
                 :background "#0e639c"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"}
   :run-busy    {:background "#666"
                 :cursor "wait"}
   :status      {:color "#b0b0b0" :font-size "10px" :margin-top "4px"}
   :empty       {:color "#9a9a9a" :font-style "italic" :padding "4px 0"}
   :violation   {:padding "6px 8px"
                 :margin "4px 0"
                 :border-left "3px solid"
                 :background "#332"}
   :impact-critical {:border-left-color "#ff4040"
                     :color "#fdd"}
   :impact-serious  {:border-left-color "#ff8000"
                     :color "#fed"}
   :impact-moderate {:border-left-color "#f1c40f"
                     :color "#ffd"}
   :impact-minor    {:border-left-color "#888"
                     :color "#ccc"}
   :v-help      {:font-weight "bold" :margin-bottom "2px"}
   :v-desc      {:color "#aaa" :font-size "10px"}
   :v-target    {:color "#9cdcfe" :font-size "10px"
                 :font-family "monospace"
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
  [v]
  (let [impact (.-impact v)
        nodes  (.-nodes v)
        first-target (when (and nodes (pos? (.-length nodes)))
                       (let [n0 (aget nodes 0)
                             targets (.-target n0)]
                         (when (and targets (pos? (.-length targets)))
                           (aget targets 0))))]
    [:div {:style (merge (:violation styles) (impact-style impact))}
     [:div {:style (:v-help styles)} (.-help v)]
     [:div {:style (:v-desc styles)} (.-description v)]
     (when first-target
       [:div {:style (:v-target styles)} (str "→ " first-target)])
     [:div {:style {:color "#9a9a9a" :font-size "10px"}}
      (str ":" (.-id v) " · " (or impact "moderate"))]]))

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
         :loading  "loading…"
         :running  "running…"
         :error    "retry"
         :no-root  "retry"
         :idle     "run"
         "re-run")]]
     [:div {:style (:status styles)}
      (case state
        :idle    "click run to scan the variant (Story chrome is excluded)"
        :loading "fetching axe-core…"
        :running "scanning…"
        :error   "axe-core failed to load (offline or CSP)"
        :no-root "no variant mounted — switch to :dev mode and re-run"
        :done    (str (count vs) " violation(s) found in variant"))]
     (cond
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
