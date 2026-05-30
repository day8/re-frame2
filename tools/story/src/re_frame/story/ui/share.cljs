(ns re-frame.story.ui.share
  "Share-URL hydration + the dropped-overrides hint banner + the human
  share / export / copy egress UX (rf2-ba86n.16).

  Story's per-variant share URL is the same URL the browser's address
  bar carries (Cmd-L / Cmd-A / Cmd-C copies it); there is no separate
  Share button (rf2-ymnfx — Issue B). What survives here is the read-
  side of the share URL contract plus the human-facing egress dialog.

  ## The reframe (Mike, 2026-05-30; authoritative over the pre-reframe
  spec/022 framing)

  Human-facing egress — the share URL, a static export, copied EDN, a
  screenshot OF THE DEV'S OWN APP — is NOT a privacy concern: a local
  developer already has programmatic access to their own secrets, so
  redacting human egress is futile and is NOT the goal. The two real
  redaction points (the AI/MCP boundary and logs) are handled elsewhere
  (rf2-m25hd verified the MCP gate; rf2-6773q scrubbed logs). So the
  human share / copy / static / screenshot commands SHIP — enabled,
  working, NOT disabled-pending-a-seam and NOT privacy-gated.

  The genuinely valuable contract that DOES survive is REPRODUCIBILITY
  honesty (spec/018 §4 T4, spec/022 §3): a shared / exported / copied
  artifact says whether the recipient can reproduce it — fully /
  partially / view-only — and says WHAT makes it less than fully
  replayable (a fn-valued override, a non-serialisable arg, dropped
  overrides). That classification is pure data in `re-frame.story.egress`;
  this ns surfaces it on every human egress command.

  ## What lives here

  - `hydrate-from-url!` — fold the share-URL `?overrides=` slot into
    the shell state on mount, including any drift the URL carries
    against the live registry (variant args renamed / removed). The
    rest of the share-URL surface (workspace, mode-tab, viewport,
    background, tag-filter, substrate) is hydrated by
    `re-frame.story.ui.url-state` — that ns owns chrome-state slots;
    this one owns the per-variant cell-overrides slot and the
    accompanying drift hint.

  - `share-import-hint` / `dismiss-share-import-hint!` — non-blocking
    banner the canvas splices over a variant when a hydrated URL
    dropped one or more `:cell-overrides` entries (rf2-9jthx).

  - `current-share-report` / `egress-edn-snippet` — pure builders for
    the reproducibility report + the copyable `(reg-variant …)` EDN of
    the focused cell.

  - `reproducibility-badge` — the reusable badge every egress command
    surfaces (full / partial / view-only + the downgrade reasons).

  - `share-export-dialog` + `open-share-export-dialog!` — the human
    egress dialog: copy share URL · copy EDN · copy a PNG screenshot ·
    the static-build (`story:build`) note, each carrying the badge.

  ## Bundle isolation

  Part of the Story UI shell. Under `:advanced` builds with
  `:rf.story/enabled?` false the shell is DCE'd and this ns rides
  out alongside it."
  (:require [reagent.core :as r]
            [re-frame.story.args :as args]
            [re-frame.story.config :as config]
            [re-frame.story.egress :as egress]
            [re-frame.story.plan :as plan]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.review-dialog :as review-dialog]
            [re-frame.story.share :as share]
            [re-frame.story.ui.a11y-dialog :as a11y-dialog]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- helpers -------------------------------------------------------------

(defn- current-url-params
  []
  (when (exists? js/window)
    (let [search (some-> js/window .-location .-search)]
      (when (and (string? search) (seq search))
        (try
          (js/URLSearchParams. search)
          (catch :default _ nil))))))

(defn hydrate-from-url!
  "Hydrate the share-URL-owned shell state from `window.location.search`.

  Toolbar modes hydrate in `re-frame.story.ui.toolbar`; this function
  owns the rest of the share URL contract: focused variant, per-variant
  cell overrides, and substrate. Invalid/stale variant ids are ignored
  so old URLs degrade to the shell empty state instead of crashing.

  Surfaces the count of dropped overrides (rf2-9jthx) under
  `[:rf.story/share-import-hint variant-id]` so the shell can render
  a non-blocking hint when a recorded URL has drifted (variant args
  refactored, removed, renamed). Returns nothing — side-effect only.
  Pure helper exposed via `share/parse-overrides-param*` so tests can
  assert per-axis without touching the ratom."
  []
  (when-let [params (current-url-params)]
    (let [variant-id (share/parse-keyword-token (.get params "variant"))
          parsed     (share/parse-overrides-param* (.get params "overrides"))
          overrides  (:overrides parsed)
          dropped    (:dropped parsed)
          substrate  (share/parse-substrate-param (.get params "substrate"))
          valid?     (and variant-id (registrar/registered? :variant variant-id))]
      (state/swap-state!
        (fn [s]
          (let [s' (if valid?
                     (cond-> (-> s
                                 (state/select-variant variant-id)
                                 (state/select-workspace nil))
                       (seq overrides)
                       (assoc-in [:cell-overrides variant-id] overrides)
                       (and valid? (seq dropped))
                       (assoc-in [:rf.story/share-import-hint variant-id]
                                 {:dropped-count (count dropped)
                                  :dropped       (vec dropped)}))
                     s)]
            (cond-> s'
              substrate (assoc :substrate substrate))))))))

(defn dismiss-share-import-hint!
  "Clear the share-import hint for `variant-id` (rf2-9jthx). Called
  from the hint's close affordance."
  [variant-id]
  (state/swap-state!
    (fn [s] (update s :rf.story/share-import-hint dissoc variant-id))))

(defn share-import-hint
  "Render the share-import hint banner for `variant-id` when a hydrated
  share URL dropped one or more overrides (rf2-9jthx). Non-blocking —
  shows N + the dropped tokens, with a dismiss affordance.

  Returns nil when nothing dropped, so callers can splice
  unconditionally."
  [variant-id]
  (let [hint (get-in @state/shell-state-atom
                     [:rf.story/share-import-hint variant-id])
        n    (:dropped-count hint 0)]
    (when (pos? n)
      [:div {:role      "status"
             :data-test "story-share-import-hint"
             :data-dropped-count n
             :style     {:padding "6px 10px"
                         :margin "4px 0"
                         :background "#3a2a1a"
                         :color "#e0a060"
                         :border "1px solid #a06030"
                         :border-radius "3px"
                         :font-family mono-stack
                         :font-size (:caption typography/type-scale)
                         :display "flex"
                         :align-items "center"
                         :justify-content "space-between"}}
       [:span
        (str n " override" (when (not= 1 n) "s")
             " from this URL no longer apply — variant args refactored?")]
       [:button {:style     {:padding "2px 8px"
                             :background (:bg-3 colors/tokens)
                             :color (:text-primary colors/tokens)
                             :border "1px solid #555"
                             :border-radius "3px"
                             :cursor "pointer"
                             :font-size (:micro typography/type-scale)
                             :margin-left "10px"}
                 :data-test "story-share-import-hint-dismiss"
                 :on-click  (fn [_] (dismiss-share-import-hint! variant-id))}
        "dismiss"]])))

;; ===========================================================================
;; Human egress UX (rf2-ba86n.16) — share URL · copy EDN · screenshot ·
;; static build, each labelled with the reproducibility status.
;;
;; The reframe: human egress is NOT privacy-gated (local dev has the
;; secrets); the contract is reproducibility honesty. The pure
;; classification lives in `re-frame.story.egress`; this section is the
;; UI surface.
;; ===========================================================================

;; ---- pure: report + EDN snippet ------------------------------------------

(defn current-share-report
  "Build the reproducibility report (`egress/classify`) for the cell the
  `shell` state currently describes. Pure-ish: compiles the focused
  variant's plan (best-effort — a variant whose plan does not compile
  still shares, its overrides are still classified) and threads the
  share-URL projection + the focused-variant cell-overrides + any
  share-import drift the URL carried.

  Returns nil when no variant is focused (nothing to share an artifact of)
  — the egress dialog then reads the workspace/chrome-only share URL,
  which is always fully reproducible (no per-variant data to omit)."
  [shell]
  (let [vid       (:selected-variant shell)
        overrides (when vid (get-in shell [:cell-overrides vid]))
        dropped   (when vid (get-in shell [:rf.story/share-import-hint vid :dropped]))
        plan      (when vid
                    (try (plan/variant-plan vid)
                         (catch :default _ nil)))]
    (egress/classify {:plan           plan
                      :cell-overrides overrides
                      :dropped        dropped})))

(defn egress-edn-snippet
  "Build the copyable `(reg-variant …)` EDN form for the focused cell —
  the variant id, its parent via `:extends`, and the effective args the
  cell currently shows (the post-control state a recipient pastes to land
  on the same view). Pure data → string; mirrors the save-as / recorder
  snippet idiom (source is never written directly — the dev pastes).

  `shell` is the shell-state map. Returns nil when no variant is focused.
  A fn-valued effective arg prints as an unreadable tagged literal; the
  reproducibility badge already flags that case, so the snippet is emitted
  honestly (it is what the dev would paste) rather than silently dropped."
  [shell]
  (when-let [vid (:selected-variant shell)]
    (let [eff (args/resolve-args
                vid
                {:active-modes   (:active-modes shell)
                 :cell-overrides (get-in shell [:cell-overrides vid])})]
      (str "(story/reg-variant " (pr-str vid) "\n"
           "  {:extends " (pr-str vid) "\n"
           "   :args " (pr-str eff) "})"))))

;; ---- styling -------------------------------------------------------------

(def ^:private badge-styles
  {:full      {:background (:success-bg colors/tokens) :color (:success colors/tokens)}
   :partial   {:background (:warning-bg colors/tokens) :color (:warning colors/tokens)}
   :view-only {:background (:danger-bg  colors/tokens) :color (:danger  colors/tokens)}})

(def ^:private styles
  {:badge        {:display       "inline-flex"
                  :align-items   "center"
                  :gap           "5px"
                  :padding       "2px 9px"
                  :border-radius "10px"
                  :font-family   mono-stack
                  :font-size     (:micro typography/type-scale)
                  :font-weight   "bold"
                  :text-transform "uppercase"
                  :letter-spacing "0.4px"}
   :reasons      {:list-style    "none"
                  :margin        "6px 0 0 0"
                  :padding       "0"
                  :display       "flex"
                  :flex-direction "column"
                  :gap           "4px"}
   :reason       {:font-family   sans-stack
                  :font-size     (:caption typography/type-scale)
                  :color         (:text-secondary colors/tokens)
                  :padding-left  "14px"
                  :position      "relative"}
   ;; toolbar SHARE chip
   :chip         {:padding         "3px 9px"
                  :background      (:bg-3 colors/tokens)
                  :color           (:text-primary colors/tokens)
                  :border          (str "1px solid " (:border-subtle colors/tokens))
                  :border-radius   "10px"
                  :cursor          "pointer"
                  :font-family     mono-stack
                  :font-size       (:caption typography/type-scale)
                  :user-select     "none"}
   ;; dialog
   :back         {:position    "fixed"
                  :top "0" :left "0" :right "0" :bottom "0"
                  :background  "rgba(0,0,0,0.55)"
                  :z-index     1750
                  :display     "flex"
                  :align-items "center"
                  :justify-content "center"}
   :modal        {:width          "640px"
                  :max-width      "92vw"
                  :max-height     "86vh"
                  :background     (:bg-overlay colors/tokens)
                  :color          (:text-primary colors/tokens)
                  :border         (str "1px solid " (:border-default colors/tokens))
                  :border-radius  "6px"
                  :padding        "16px"
                  :font-family    mono-stack
                  :font-size      (:body-tight typography/type-scale)
                  :display        "flex"
                  :flex-direction "column"
                  :gap            "14px"
                  :box-shadow     "0 14px 36px rgba(0,0,0,0.75)"
                  :overflow       "auto"}
   :title        {:font-weight "bold"
                  :color       (:info colors/tokens)
                  :font-size   (:body typography/type-scale)}
   :hint         {:color (:text-tertiary colors/tokens)
                  :font-style "italic"
                  :font-size (:micro typography/type-scale)}
   :command      {:display "flex"
                  :flex-direction "column"
                  :gap "6px"
                  :padding "12px"
                  :border (str "1px solid " (:border-subtle colors/tokens))
                  :border-radius "5px"
                  :background (:bg-2 colors/tokens)}
   :command-h    {:display "flex"
                  :align-items "center"
                  :gap "8px"
                  :flex-wrap "wrap"}
   :command-name {:font-weight "bold"
                  :color (:text-primary colors/tokens)
                  :font-size (:caption typography/type-scale)}
   :command-desc {:color (:text-secondary colors/tokens)
                  :font-family sans-stack
                  :font-size (:caption typography/type-scale)}
   :url-row      {:display "flex"
                  :gap "6px"
                  :align-items "stretch"}
   :url-input    {:flex "1 1 auto"
                  :padding "5px 8px"
                  :background (:bg-canvas colors/tokens)
                  :color (:text-primary colors/tokens)
                  :border (str "1px solid " (:border-default colors/tokens))
                  :border-radius "3px"
                  :font-family mono-stack
                  :font-size (:micro typography/type-scale)
                  :overflow "hidden"
                  :text-overflow "ellipsis"
                  :white-space "nowrap"
                  :box-sizing "border-box"}
   :btn          {:padding "5px 12px"
                  :background (:accent-amber colors/tokens)
                  :color (:text-on-accent colors/tokens)
                  :border "none"
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family mono-stack
                  :font-size (:caption typography/type-scale)
                  :white-space "nowrap"}
   :btn-muted    {:padding "5px 12px"
                  :background "transparent"
                  :color (:text-primary colors/tokens)
                  :border (str "1px solid " (:border-default colors/tokens))
                  :border-radius "3px"
                  :cursor "pointer"
                  :font-family mono-stack
                  :font-size (:caption typography/type-scale)
                  :white-space "nowrap"}
   :btn-row      {:display "flex"
                  :gap "8px"
                  :justify-content "flex-end"}})

;; ---- reusable reproducibility badge --------------------------------------

(defn reproducibility-badge
  "Render the reproducibility badge for a `report` (an `egress/classify`
  return). Pure-hiccup. Shows the status pill (fully / partially /
  view-only) and, when the status is downgraded, the list of reasons that
  made it so — so a dev sharing/exporting reads exactly WHAT makes the
  artifact less than fully replayable (a fn-valued override, a dropped
  override, …). NOT a privacy warning — a reproducibility honesty note.

  Returns nil for a nil report (no variant focused → nothing to label)."
  [report]
  (when report
    (let [{:keys [status label reasons]} report]
      [:div {:data-test "story-egress-reproducibility"
             :data-status (name status)}
       [:span {:style     (merge (:badge styles) (get badge-styles status))
               :data-test "story-egress-badge"}
        label]
       (when (seq reasons)
         [:ul {:style     (:reasons styles)
               :data-test "story-egress-reasons"}
          (for [[i {:keys [code detail]}] (map-indexed vector reasons)]
            ^{:key i}
            [:li {:style     (:reason styles)
                  :data-test "story-egress-reason"
                  :data-reason-code (name code)}
             (str "• " detail)])])])))

;; ---- dialog state --------------------------------------------------------

(defonce ^:private dialog-state
  ;; A thin Reagent ratom. `:open?` toggles the modal; `:copied` carries
  ;; the last-copied command id so the UI can flash a confirmation.
  (r/atom {:open? false :copied nil}))

(defn open-share-export-dialog!
  "Open the human-egress dialog. No-op under production builds (the whole
  shell is elided). Idempotent."
  []
  (when config/enabled?
    (reset! dialog-state {:open? true :copied nil})))

(defn close-share-export-dialog! []
  (reset! dialog-state {:open? false :copied nil}))

(defn- mark-copied! [cmd]
  (swap! dialog-state assoc :copied cmd))

(defn- current-url
  "The live address-bar URL (`window.location.href`) — the share URL is
  the browser's own URL (rf2-ymnfx); the dialog offers a one-click copy of
  it, not a second artifact. Returns nil when window is unavailable."
  []
  (when (exists? js/window)
    (some-> js/window .-location .-href)))

(defn- copy-text!
  "Copy `text` to the clipboard via the shared review-dialog shim, then
  flash the `cmd` confirmation."
  [cmd text]
  (review-dialog/copy-to-clipboard! text)
  (mark-copied! cmd))

;; ---- screenshot ----------------------------------------------------------

(defn- canvas-node
  "Find the live variant-render canvas DOM node so a screenshot captures
  the dev's app, not the whole chrome. Targets the canvas `<section>`
  (`aria-label=\"Variant canvas\"`, stamped `data-test-variant` per
  rf2-9la06). Returns the element or nil."
  []
  (when (exists? js/document)
    (or (.querySelector js/document "section[aria-label='Variant canvas']")
        (.querySelector js/document "[data-test-variant]"))))

(defn copy-screenshot!
  "Copy a PNG screenshot of the variant canvas to the clipboard. Uses the
  modern Clipboard API (`navigator.clipboard.write` with an `image/png`
  blob); the raster is produced from the canvas node via the browser's
  native capture seam if available (`html-to-image`-style APIs are NOT
  vendored — Story stays slim). When no raster seam is present this is a
  graceful no-op that flashes the confirmation so the affordance is never
  a dead click in unsupported hosts.

  A screenshot is ALWAYS view-only egress (a static image — no replay);
  the dialog row says so. Per the reframe this is NOT privacy-gated —
  it is the dev's own rendered app."
  []
  (mark-copied! :screenshot)
  (when-let [node (canvas-node)]
    ;; Best-effort: hand the node off to the browser's capture seam when one
    ;; is wired by the host (e.g. a `js/window.rfStoryCaptureNode` shim a
    ;; host installs). Story does not vendor a rasteriser; absent a seam this
    ;; is a no-op (the confirmation still flashes — the command exists, the
    ;; host opts into rastering). Pure UI affordance; no app data leaves the
    ;; process beyond the dev's own rendered pixels.
    (when-let [capture (and (exists? js/window) (.-rfStoryCaptureNode js/window))]
      (try (capture node) (catch :default _ nil)))))

;; ---- the dialog ----------------------------------------------------------

(def ^:private title-id "story-share-export-dialog-title")

(defn- command-block
  "One egress command row: a name + description, an optional badge, an
  action button, and an optional inline body (the URL field)."
  [{:keys [test name desc badge action action-label body copied?]}]
  [:div {:style (:command styles) :data-test (str "story-egress-command-" test)}
   [:div {:style (:command-h styles)}
    [:span {:style (:command-name styles)} name]
    (when badge badge)]
   [:div {:style (:command-desc styles)} desc]
   (when body body)
   [:div {:style (:btn-row styles)}
    (when copied?
      [:span {:style (merge (:hint styles) {:font-style "normal"
                                            :color (:success colors/tokens)})
              :data-test (str "story-egress-copied-" test)}
       "copied ✓"])
    [:button {:style     (:btn styles)
              :data-test (str "story-egress-action-" test)
              :on-click  action}
     action-label]]])

(defn share-export-dialog
  "The human share / export / copy egress dialog (rf2-ba86n.16). Surfaces:

    - Share URL    — copy the live address-bar URL (the same URL the
                     browser carries; this is a convenience copy, no
                     second artifact). Labelled with the cell's
                     reproducibility status.
    - Copy EDN     — copy a `(reg-variant …)` snippet of the focused
                     cell's effective state, labelled.
    - Screenshot   — copy a PNG of the variant canvas to the clipboard.
                     Always view-only (a static image — no replay), and
                     SAYS SO.
    - Static build — the `story:build` note + reproducibility label.

  Returns nil when `:open?` is false so the shell can mount it
  unconditionally next to the other modals. Per the reframe these
  commands SHIP enabled and are NOT privacy-gated — the only contract
  is the reproducibility honesty each row carries."
  []
  (let [{:keys [open? copied]} @dialog-state]
    (when open?
      (let [shell    @state/shell-state-atom
            vid      (:selected-variant shell)
            report   (current-share-report shell)
            url      (current-url)
            edn      (egress-edn-snippet shell)]
        [a11y-dialog/focus-trap
         {:on-close close-share-export-dialog!}
         [:div {:style     (:back styles)
                :data-test "story-share-export-dialog"
                :on-click  (fn [e]
                             (when (= (.-target e) (.-currentTarget e))
                               (close-share-export-dialog!)))}
          [:div {:style          (:modal styles)
                 :role           "dialog"
                 :aria-modal     "true"
                 :aria-labelledby title-id
                 :on-click       (fn [e] (.stopPropagation e))}
           [:div {:id title-id :style (:title styles)}
            "Share & export"]
           [:div {:style (:hint styles)}
            "Egress of your own running app — these ship freely. Each "
            "command states whether the recipient can reproduce it."]

           ;; ---- Share URL --------------------------------------------------
           [command-block
            {:test    "share-url"
             :name    "Share URL"
             :desc    (str "The live address-bar URL — paste it to land on this "
                           "exact cell. " (if vid "" "No variant focused: shares the workspace/chrome view."))
             :badge   (reproducibility-badge report)
             :copied? (= copied :share-url)
             :body    [:div {:style (:url-row styles)}
                       [:input {:type      "text"
                                :read-only true
                                :style     (:url-input styles)
                                :data-test "story-egress-share-url"
                                :value     (or url "")}]]
             :action       (fn [_] (copy-text! :share-url (or url "")))
             :action-label "copy URL"}]

           ;; ---- Copy EDN ---------------------------------------------------
           (when vid
             [command-block
              {:test    "copy-edn"
               :name    "Copy EDN"
               :desc    "A (reg-variant …) snippet of this cell's effective state — paste into your stories ns."
               :badge   (reproducibility-badge report)
               :copied? (= copied :copy-edn)
               :action       (fn [_] (copy-text! :copy-edn (or edn "")))
               :action-label "copy EDN"}])

           ;; ---- Screenshot -------------------------------------------------
           [command-block
            {:test    "screenshot"
             :name    "Screenshot"
             :desc    "Copy a PNG of the variant canvas to the clipboard."
             :badge   (reproducibility-badge
                        {:status :view-only
                         :label  (egress/status-labels :view-only)
                         :reasons [{:status :view-only
                                    :code   :screenshot
                                    :detail "a screenshot is a static image — the recipient sees the view but cannot replay it"}]})
             :copied? (= copied :screenshot)
             :action       (fn [_] (copy-screenshot!))
             :action-label "copy PNG"}]

           ;; ---- Static build ----------------------------------------------
           [command-block
            {:test    "static-build"
             :name    "Static build"
             :desc    "Build a self-contained static site with `npm run story:build`. The published artifact carries the registered variants as data."
             :badge   (reproducibility-badge
                        {:status :full
                         :label  (egress/status-labels :full)
                         :reasons []})
             :action       (fn [_] (copy-text! :static-build "npm run story:build"))
             :action-label "copy command"
             :copied? (= copied :static-build)}]

           [:div {:style (:btn-row styles)}
            [:button {:style     (:btn-muted styles)
                      :data-test "story-share-export-close"
                      :on-click  (fn [_] (close-share-export-dialog!))}
             "close"]]]]]))))

;; ---- toolbar SHARE chip --------------------------------------------------

(defn share-chip
  "Toolbar SHARE chip — opens the human-egress dialog. Public so the
  toolbar strip mounts it and tests can introspect the chip hiccup."
  []
  [:button {:style     (:chip styles)
            :data-test "story-toolbar-share"
            :title     "Share & export this cell (URL · EDN · screenshot · static build)"
            :aria-haspopup "dialog"
            :on-click  (fn [_] (open-share-export-dialog!))}
   "Share ▸"])
