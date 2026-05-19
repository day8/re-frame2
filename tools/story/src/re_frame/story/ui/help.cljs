(ns re-frame.story.ui.help
  "First-time-user help overlay for the Story playground (rf2-381i).

  Renders a modal-style overlay that explains the playground UI:

  - what `:dev` / `:docs` / `:test` mode-tabs do
  - what the sidebar tree means (stories / variants / workspaces)
  - what to click first
  - what each right-panel section shows

  ## Behaviour

  - Shown automatically on first mount unless the user has previously
    dismissed it (tracked via `localStorage` under the key
    `re-frame.story/seen-help-v1`).
  - Re-openable on demand via [[help-button]] — a `?` chip the shell
    renders in its chrome.
  - Dismissed by clicking the backdrop, pressing Escape, or clicking
    the 'Got it' button.

  Local component state (open / not-open) lives in a Reagent ratom
  inside [[help-host]]; it is intentionally NOT in the shell-state
  atom — the welcome popup is ephemeral UI, not playground state.

  ## Voice + colour

  Matches the rest of the Story shell chrome: `#252526` panel ground,
  `#cccccc` body text, `#b0b0b0` muted labels (post rf2-2uwv contrast
  fixes — all foreground colours meet WCAG AA against the panel
  ground). Tight bulleted copy — no paragraphs.

  ## Bundle isolation

  Production builds with `re-frame.story.config/enabled?` false never
  reach this ns; Closure DCE drops the lot."
  (:require [reagent.core :as r]
            [re-frame.story.config :as config]
            [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.depth :as depth]
            [re-frame.story.theme.motion :as motion]))

;; ---- localStorage flag --------------------------------------------------

(def ^:const seen-key
  "localStorage key tracking whether the user has dismissed the help
  overlay. Bump the trailing version when the help content materially
  changes so returning users see the refreshed copy once."
  "re-frame.story/seen-help-v1")

(defn- safe-local-storage
  "Return js/window.localStorage if available, otherwise nil.

  Browsers can disable localStorage (private-mode quirks, embedded
  contexts, file:// in some configs) so every access guards. Returns
  nil rather than throwing — callers degrade to 'always-show'."
  []
  (when (and (exists? js/window) (.-localStorage js/window))
    (try (.-localStorage js/window)
         (catch :default _ nil))))

(defn seen?
  "Has the user dismissed the help overlay before? Returns false when
  localStorage is unavailable (so the overlay shows on every visit in
  that fallback path — better to over-show than miss the on-boarding)."
  []
  (boolean
    (when-let [ls (safe-local-storage)]
      (try (some? (.getItem ls seen-key))
           (catch :default _ false)))))

(defn mark-seen!
  "Persist the 'dismissed' flag so subsequent visits skip auto-open."
  []
  (when-let [ls (safe-local-storage)]
    (try (.setItem ls seen-key "1")
         (catch :default _ nil))))

(defn reset-seen!
  "Clear the dismissed flag. Useful for tests + a future
  'show me the help again' affordance. Not currently wired to chrome."
  []
  (when-let [ls (safe-local-storage)]
    (try (.removeItem ls seen-key)
         (catch :default _ nil))))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:backdrop    {:position    "fixed"
                 :top         0
                 :left        0
                 :right       0
                 :bottom      0
                 :background  "rgba(0,0,0,0.55)"
                 :z-index     2000
                 :display     "flex"
                 :align-items "center"
                 :justify-content "center"
                 :animation   (str "rf-story-overlay-in "
                                   (:overlay-fade motion/timing) " "
                                   (:enter motion/easing) " both")}
   :panel       {:background    (:overlay-glass depth/backdrops)
                 :color         (:text-primary colors/tokens)
                 :border        (str "1px solid " (:border-strong colors/tokens))
                 :border-radius "8px"
                 :box-shadow    (:elev-overlay depth/shadows)
                 :width         "560px"
                 :max-width     "92vw"
                 :max-height    "86vh"
                 :overflow      "auto"
                 :font-family   sans-stack
                 :font-size     (:body typography/type-scale)
                 :line-height   "1.5"
                 :backdrop-filter "blur(8px)"
                 :-webkit-backdrop-filter "blur(8px)"}
   :header      {:display         "flex"
                 :justify-content "space-between"
                 :align-items     "center"
                 :padding         "12px 16px"
                 :background      (:bg-2 colors/tokens)
                 :border-bottom   "1px solid #444"}
   :title       {:color       (:info colors/tokens)
                 :font-weight "bold"
                 :font-size   (:body typography/type-scale)
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"}
   :close       {:background  "transparent"
                 :border      "none"
                 :color       (:text-secondary colors/tokens)
                 :font-size   (:display typography/type-scale)
                 :cursor      "pointer"
                 :padding     "0 4px"
                 :line-height "1"}
   :body        {:padding "16px 20px"}
   :section-h   {:color          (:text-secondary colors/tokens)
                 :font-size      (:micro typography/type-scale)
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"
                 :margin         "12px 0 6px 0"
                 :font-weight    "bold"}
   :section-h-first {:margin-top "0"}
   :list        {:margin     "0 0 0 18px"
                 :padding    "0"}
   :list-item   {:margin-bottom "4px"}
   :kw          {:color       (:warning colors/tokens)
                 :font-family mono-stack}
   :muted       {:color (:text-secondary colors/tokens)}
   :footer      {:display         "flex"
                 :justify-content "flex-end"
                 :padding         "12px 16px"
                 :border-top      "1px solid #444"
                 :background      (:bg-2 colors/tokens)}
   :got-it      {:padding       "6px 16px"
                 :background    (:accent-amber colors/tokens)
                 :color         "white"
                 :border        "none"
                 :border-radius "3px"
                 :cursor        "pointer"
                 :font-size     (:body-tight typography/type-scale)
                 :font-family   sans-stack}
   :help-btn    {:padding       "2px 9px"
                 :background    (:bg-3 colors/tokens)
                 :color         (:info colors/tokens)
                 :border        "1px solid #555"
                 :border-radius "12px"
                 :cursor        "pointer"
                 :font-family   mono-stack
                 :font-size     (:caption typography/type-scale)
                 :line-height   "1.2"}})

;; ---- the panel ----------------------------------------------------------

(defn- kw
  "Render a keyword-style inline token with the monospace yellow chrome."
  [s]
  [:span {:style (:kw styles)} s])

(defn help-content
  "The body of the help overlay — pure hiccup, factored out so future
  tests can render it in isolation and so the copy stays in one place.

  Total copy: ~150 words, bulleted, tight."
  []
  [:div {:style (:body styles)}
   [:div {:style (merge (:section-h styles) (:section-h-first styles))}
    "Mode tabs"]
   [:ul {:style (:list styles)}
    [:li {:style (:list-item styles)}
     [kw ":dev"] " — interactive playground (default; full chrome)."]
    [:li {:style (:list-item styles)}
     [kw ":docs"] " — read-only prose view for embedding."]
    [:li {:style (:list-item styles)}
     [kw ":test"] " — runs the variant's assertions and reports pass/fail."]]

   [:div {:style (:section-h styles)} "Sidebar (left)"]
   [:ul {:style (:list styles)}
    [:li {:style (:list-item styles)}
     [:b "Stories"] " group related "
     [:b "variants"] " (the renderable units)."]
    [:li {:style (:list-item styles)}
     [:b "Workspaces"] " compose multiple variants into one screen."]
    [:li {:style (:list-item styles)}
     "Click a variant for a solo view; click a workspace for the composition."]]

   [:div {:style (:section-h styles)} "Start here"]
   [:ul {:style (:list styles)}
    [:li {:style (:list-item styles)}
     "Pick any variant in the sidebar — the canvas renders it; the right rail unlocks."]]

   [:div {:style (:section-h styles)} "Inspectors (right)"]
   [:ul {:style (:list styles)}
    [:li {:style (:list-item styles)}
     [:b "args"] " edit live arguments; "
     [:b "modes"] " toggle registered modes; "
     [:b "decorators"] " show the resolved wrap stack."]
    [:li {:style (:list-item styles)}
     [:b "time-travel"] " scrub past epochs; "
     [:b "trace"] " tails the six-domino cascade per event."]
    [:li {:style (:list-item styles)}
     [:b "notes / a11y / layout-debug"] " — author notes, axe-core scan, visual guides."]]

   [:div {:style (:section-h styles)} "Keyboard shortcuts"]
   [:ul {:style (:list styles)
         :data-test "story-help-shortcuts-table"}
    [:li {:style (:list-item styles)}
     [kw "f"] " — full-screen mode (canvas fills viewport)."]
    [:li {:style (:list-item styles)}
     [kw "s"] " — toggle the sidebar."]
    [:li {:style (:list-item styles)}
     [kw "a"] " — toggle the inspectors (RHS / addons)."]
    [:li {:style (:list-item styles)}
     [kw "t"] " — toggle the toolbar."]
    [:li {:style (:list-item styles)}
     [kw "⌘K"] " / " [kw "Ctrl-K"] " — open the command palette."]
    [:li {:style (:list-item styles)}
     [kw "Esc"] " — exit full-screen / close palette / clear search."]]

   [:div {:style (:section-h styles)} "Re-open"]
   [:ul {:style (:list styles)}
    [:li {:style (:list-item styles)}
     "Click the " [:span {:style (:kw styles)} "?"]
     " in the top-left anytime."]]])

(defn- on-key-down
  "Escape closes the overlay. Bound to a window listener while open."
  [close!]
  (fn [^js evt]
    (when (= "Escape" (.-key evt))
      (close!))))

(defn help-panel
  "The overlay itself — backdrop + centred panel. `close!` is the
  zero-arg fn the parent passes; it's invoked on Got-it / Escape /
  backdrop click. The component owns the window-level keydown listener
  via Reagent lifecycle so it cleans up on unmount."
  [close!]
  (let [handler (atom nil)]
    (r/create-class
      {:display-name "rf-story-help-panel"
       :component-did-mount
       (fn [_]
         (let [f (on-key-down close!)]
           (reset! handler f)
           (when (exists? js/window)
             (.addEventListener js/window "keydown" f))))
       :component-will-unmount
       (fn [_]
         (when-let [f @handler]
           (when (exists? js/window)
             (.removeEventListener js/window "keydown" f))))
       :reagent-render
       (fn [close!]
         [:div {:style    (:backdrop styles)
                :role     "dialog"
                :aria-modal "true"
                :aria-label "Story playground help"
                :on-click (fn [_] (close!))}
          [:div {:style    (:panel styles)
                 :on-click (fn [^js e] (.stopPropagation e))}
           [:div {:style (:header styles)}
            [:div {:style (:title styles)} "Welcome to the Story playground"]
            [:button {:style    (:close styles)
                      :aria-label "Close help"
                      :on-click (fn [_] (close!))}
             "x"]]
           [help-content]
           [:div {:style (:footer styles)}
            [:button {:style    (:got-it styles)
                      :on-click (fn [_] (close!))}
             "Got it"]]]])})))

;; ---- mounted host: handles first-time auto-open + manual button --------

(defonce ^:private open?
  ;; Local reactive flag. Reagent ratom so the host re-renders when the
  ;; help button toggles it. Initial value computed lazily on mount.
  (r/atom false))

(defn open!
  "Open the help overlay. Public so other chrome (or tests) can trigger
  it. Idempotent — opening when already open is a no-op."
  []
  (reset! open? true))

(defn close!
  "Close the help overlay and mark it as seen so subsequent visits
  skip the auto-open."
  []
  (reset! open? false)
  (mark-seen!))

(defn help-button
  "The `?` chip — rendered by the shell chrome. Clicking opens the
  help overlay on demand."
  []
  [:button {:style    (:help-btn styles)
            :title    "Show playground help"
            :aria-label "Show playground help"
            :on-click (fn [_] (open!))}
   "?"])

(defn help-host
  "The mounted host component — owns the open?/closed? state and
  decides whether to auto-open on first visit.

  Renders nothing when closed. The host should be mounted at the top
  of the shell tree so the modal layers above every other pane."
  []
  (r/create-class
    {:display-name "rf-story-help-host"
     :component-did-mount
     (fn [_]
       ;; Auto-open on first visit. Guarded so reseting / re-mounting
       ;; the shell mid-session (e.g. via hot-reload) doesn't pop the
       ;; modal again — only fires when no flag is persisted.
       ;;
       ;; Per rf2-8wgpm (static-build): suppress the auto-open in
       ;; static-export mode. A visitor landing on a published docs
       ;; site already arrived with intent; the dev-time onboarding
       ;; overlay just gets in their way. The manual ? chip is still
       ;; rendered so on-demand help remains reachable.
       (when (and (not config/static-mode?)
                  (not (seen?)))
         (reset! open? true)))
     :reagent-render
     (fn []
       (when @open?
         [help-panel close!]))}))
