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
    rest of the share-URL surface (selection, workspace, mode-tab,
    viewport, background, tag-filter, substrate) is hydrated AND
    VALIDATED by `re-frame.story.ui.url-state` — that ns is the single
    authoritative owner of those slots; this pass reads (never rewrites)
    the selection it set and owns only the focused-variant cell-overrides
    slice + the accompanying drift hint (rf2-ovb1en — a prior version
    reparsed `substrate=` here without validation and resurrected a stale
    value `url-state` had already normalised to `:reagent`).

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
    the static-build (`story:build`) note, each carrying the badge. The
    screenshot row does a real capture-to-PNG + `navigator.clipboard.write`
    and surfaces an honest unavailable/error state when the host lacks a
    capture seam or the async clipboard image-write API (rf2-ehc5bq) — it
    never flashes a false 'copied' on a no-op.

  ## Bundle isolation

  Part of the Story UI shell. Under `:advanced` builds with
  `:rf.story/enabled?` false the shell is DCE'd and this ns rides
  out alongside it."
  (:require [reagent.core :as r]
            [re-frame.story.args :as args]
            [re-frame.story.config :as config]
            [re-frame.story.egress :as egress]
            [re-frame.story.malli-schema :as msu]
            [re-frame.story.plan :as plan]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.review-dialog :as review-dialog]
            [re-frame.story.share :as share]
            [re-frame.story.ui.a11y-dialog :as a11y-dialog]
            [re-frame.story.ui.state :as state]
            [re-frame.story.view-args :as view-args]
            [re-frame.story.theme.typography :as typography :refer [mono-stack sans-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- helpers -------------------------------------------------------------

(defn current-url-params
  "Parse `window.location.search` into a `URLSearchParams` for the
  share-overrides hydration seam. Returns nil when window is unavailable
  or no search params are present. Public (like
  `url-state/parse-current-url`) so the shell-level hydration regression
  test (rf2-ovb1en) can drive the real `hydrate-from-url!` under the node
  runner, where `window.location` is read-only."
  []
  (when (exists? js/window)
    (let [search (some-> js/window .-location .-search)]
      (when (and (string? search) (seq search))
        (try
          (js/URLSearchParams. search)
          (catch :default _ nil))))))

(defn declared-arg-keys
  "The set of TOP-LEVEL arg-keys `variant-id` currently declares — the
  share-import contract a stale URL override is checked against
  (rf2-76l69l). The same surface the controls panel exposes as editable
  args: the union of

    - the resolved args' keys (`args/resolve-args` WITHOUT cell-overrides
      — the global / story / variant-chain `:args` layers);
    - the variant's + parent story's explicit `:argtypes` keys;
    - the compiled view-args (props) schema's top-level `:map` entry keys
      (`view-args/compiled-view-args-schema`).

  Returns nil for an unregistered / uncompilable variant (no contract
  known) so `share/drop-stale-overrides` degrades to keeping every parsed
  override rather than dropping all. Best-effort: any read failure
  contributes its empty layer; the resolved-args layer alone is the
  floor."
  [variant-id]
  (when (and variant-id (registrar/registered? :variant variant-id))
    (let [story-id   (args/parent-story-id variant-id)
          vb         (registrar/handler-meta :variant variant-id)
          sb         (when story-id (registrar/handler-meta :story story-id))
          arg-keys   (set (keys (args/resolve-args variant-id)))
          argtype-ks (into (set (keys (:argtypes sb)))
                           (keys (:argtypes vb)))
          schema     (view-args/compiled-view-args-schema variant-id)
          schema-ks  (when (and (vector? schema) (= :map (msu/schema-op schema)))
                       (map msu/map-entry-key (msu/schema-children schema)))]
      (into (into arg-keys argtype-ks) schema-ks))))

(defn hydrate-from-url!
  "Second-pass URL hydration: own the focused-variant cell-overrides
  contract + the share-import drift hint. Runs AFTER
  `re-frame.story.ui.url-state/hydrate-from-url!` at shell mount.

  rf2-ovb1en — this pass is deliberately NARROW. `url-state` is the
  single authoritative owner of every URL-owned slot it writes —
  selection (`variant` / `workspace`), substrate, viewport, background,
  tag-filter, modes — and it VALIDATES each against the live registry
  (an unregistered `substrate=ghost` degrades to `:reagent`; an
  unregistered variant degrades to no selection). This pass must NOT
  reparse and rewrite any of those slots from the raw URL: doing so
  reverted `url-state`'s validation (a stale `substrate=ghost` was
  normalised to `:reagent` by the first pass, then resurrected to
  `:ghost` by an unvalidated reparse here). So substrate + selection are
  read ONLY — this pass reads the variant `url-state` already focused and
  speaks solely for that variant's cell-overrides slice and its hint.

  What this pass uniquely adds over `url-state` (which installs the raw
  parsed overrides under `[:cell-overrides variant-id]`) is the
  declared-key DRIFT filter `url-state` cannot run (it is pure, with no
  registrar access): `drop-stale-overrides` drops every parsed override
  whose arg-key the focused variant no longer DECLARES (args refactored /
  renamed / removed) so they are REPORTED, not merged as orphan live
  args. This pass is therefore the AUTHORITATIVE owner of the
  focused-variant cell-overrides slice on mount: it writes the
  declared-filtered slice when non-empty and CLEARS the slot otherwise —
  so an all-stale `overrides=` set (which `drop-stale-overrides` collapses
  to nil) does NOT leave the unfiltered slice `url-state` installed.

  Surfaces the count of dropped overrides (rf2-9jthx) under
  `[:rf.story/share-import-hint variant-id]` so the shell can render a
  non-blocking hint when a recorded URL has drifted. Returns nothing —
  side-effect only.

  rf2-76l69l — drift is detected at TWO stages: `parse-overrides-param*`
  drops UNPARSEABLE entries, then `share/drop-stale-overrides` drops every
  parsed override whose arg-key the focused variant no longer DECLARES.
  Both classes land in `:dropped` and feed the share-import hint, so a
  stale override is reported (reproducibility downgraded) instead of
  silently installed as an orphan live arg."
  []
  (when-let [params (current-url-params)]
    ;; rf2-ovb1en: read the variant `url-state` already focused (its swap
    ;; ran first) rather than re-deriving it from the raw URL — guarantees
    ;; this pass operates on `url-state`'s authoritative, validated
    ;; selection and can never diverge from it.
    (let [variant-id (:selected-variant @state/shell-state-atom)
          valid?     (and variant-id (registrar/registered? :variant variant-id))
          parsed0    (share/parse-overrides-param* (.get params "overrides"))
          ;; Second stage: drop overrides for args the focused variant no
          ;; longer declares (renamed / removed) so they are REPORTED, not
          ;; merged as orphan live args. Only meaningful for a valid variant
          ;; (its declared-key contract). `declared-arg-keys` returns nil →
          ;; keep-all degrade.
          parsed     (if valid?
                       (share/drop-stale-overrides parsed0
                                                   (declared-arg-keys variant-id))
                       parsed0)
          overrides  (:overrides parsed)
          dropped    (:dropped parsed)]
      (when valid?
        (state/swap-state!
          (fn [s]
            (cond-> s
              ;; AUTHORITATIVE owner of the focused variant's overrides slice:
              ;; install the declared-filtered slice when non-empty, else CLEAR
              ;; it — so an all-stale `overrides=` (collapsed to nil by
              ;; `drop-stale-overrides`) drops the unfiltered slice `url-state`
              ;; installed rather than leaving stale args live (rf2-ovb1en).
              (seq overrides)
              (assoc-in [:cell-overrides variant-id] overrides)

              (not (seq overrides))
              (update :cell-overrides dissoc variant-id)

              (seq dropped)
              (assoc-in [:rf.story/share-import-hint variant-id]
                        {:dropped-count (count dropped)
                         :dropped       (vec dropped)}))))))))

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
      (pred/reg-variant-form "story" vid [[:extends (pr-str vid)]
                                          [:args (pr-str eff)]]))))

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
  ;; the last-copied command id so the UI can flash a confirmation;
  ;; `:error` carries `{:cmd <id> :reason <string>}` when a command could
  ;; NOT complete (e.g. screenshot capture/clipboard unavailable) so the
  ;; row surfaces an honest unavailable/error state instead of a false
  ;; "copied ✓". A command writes EITHER `:copied` OR `:error`, never both
  ;; for the same id (rf2-ehc5bq).
  (r/atom {:open? false :copied nil :error nil}))

(defn open-share-export-dialog!
  "Open the human-egress dialog. No-op under production builds (the whole
  shell is elided). Idempotent."
  []
  (when config/enabled?
    (reset! dialog-state {:open? true :copied nil :error nil})))

(defn close-share-export-dialog! []
  (reset! dialog-state {:open? false :copied nil :error nil}))

(defn dialog-state-snapshot
  "Read-only view of the egress dialog ratom (`:open?` / `:copied` /
  `:error`). Public so tests can assert that a command flashed `:copied`
  on real success — or recorded an honest `:error` on a no-op / failure —
  without poking the private ratom (rf2-ehc5bq)."
  []
  @dialog-state)

(defn- mark-copied!
  "Flash the `cmd` success confirmation and clear any prior error for it."
  [cmd]
  (swap! dialog-state
         (fn [s]
           (cond-> (assoc s :copied cmd)
             (= cmd (:cmd (:error s))) (assoc :error nil)))))

(defn- mark-error!
  "Record that `cmd` could NOT complete, with a short human `reason`. Clears
  any stale `:copied` flash for the same command so the UI never shows a
  false success alongside the failure (rf2-ehc5bq)."
  [cmd reason]
  (swap! dialog-state
         (fn [s]
           (cond-> (assoc s :error {:cmd cmd :reason reason})
             (= cmd (:copied s)) (assoc :copied nil)))))

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

(defn- clipboard-write-image-supported?
  "True iff the host exposes the modern async Clipboard API image-write seam
  (`navigator.clipboard.write` + the `ClipboardItem` constructor). PNG-to-
  clipboard needs BOTH; `writeText` alone is not enough. Node/JSDOM and
  non-secure contexts have neither, so this returns false there."
  []
  (boolean
    (and (exists? js/navigator)
         (.-clipboard js/navigator)
         (fn? (.-write (.-clipboard js/navigator)))
         (exists? js/ClipboardItem))))

(defn- rasterise-node
  "Produce an `image/png` Blob from `node` using the host-installed capture
  seam `js/window.rfStoryCaptureNode`. Story does NOT vendor a rasteriser
  (`html-to-image`-style libs would bloat the slim bundle); the host opts in
  by installing the shim, which MUST return a `Blob`/`Promise<Blob>` of the
  rendered pixels.

  Returns a `js/Promise` resolving to the PNG Blob. Rejects (or resolves a
  non-Blob) when no seam is installed or the seam throws — the caller turns
  that into the honest unavailable/error state. Never silently succeeds."
  [node]
  (js/Promise.
    (fn [resolve reject]
      (let [capture (and (exists? js/window) (.-rfStoryCaptureNode js/window))]
        (cond
          (not (fn? capture))
          (reject (js/Error. "no-capture-seam"))

          :else
          (try
            (-> (js/Promise.resolve (capture node))
                (.then (fn [blob]
                         (if (instance? js/Blob blob)
                           (resolve blob)
                           (reject (js/Error. "capture-returned-no-blob")))))
                (.catch reject))
            (catch :default e (reject e))))))))

(defn copy-screenshot!
  "Copy a PNG screenshot of the variant canvas to the clipboard.

  Capture-to-PNG then clipboard write, in the standard browser path:

    1. locate the canvas node;
    2. rasterise it to an `image/png` Blob via the host capture seam
       (`js/window.rfStoryCaptureNode` — Story stays slim and does not
       vendor a rasteriser);
    3. write the Blob to the clipboard via `navigator.clipboard.write`
       wrapping a `ClipboardItem`.

  Marks `:copied :screenshot` ONLY when the clipboard write actually
  resolves. When the canvas node is missing, no capture seam is installed,
  the seam fails, the async Clipboard image-write API is unavailable, or the
  write rejects, it records an HONEST unavailable/error state (`mark-error!`)
  — it does NOT flash a false 'copied ✓' on a no-op (rf2-ehc5bq).

  A screenshot is ALWAYS view-only egress (a static image — no replay); the
  dialog row says so. Per the reframe this is NOT privacy-gated — it is the
  dev's own rendered app. Returns the `js/Promise` of the write (or a
  rejected/immediate-resolve sentinel) so tests can await the outcome."
  []
  (let [node (canvas-node)]
    (cond
      ;; Early-exit unavailable paths record the honest error and resolve
      ;; `false` (NOT a rejected Promise) so a fire-and-forget `:on-click`
      ;; caller does not leak an unhandled rejection — the error state is
      ;; already in the ratom for the row to surface.
      (nil? node)
      (do (mark-error! :screenshot "no variant canvas to capture")
          (js/Promise.resolve false))

      (not (clipboard-write-image-supported?))
      (do (mark-error! :screenshot
                       "clipboard image write unavailable in this browser")
          (js/Promise.resolve false))

      :else
      (-> (rasterise-node node)
          (.then (fn [blob]
                   (let [item (js/ClipboardItem. #js {"image/png" blob})]
                     (.write (.-clipboard js/navigator) #js [item]))))
          (.then (fn [_]
                   (mark-copied! :screenshot)
                   true))
          (.catch (fn [e]
                    (let [reason (case (some-> e .-message)
                                   "no-capture-seam"
                                   "screenshot capture seam not installed on this host"
                                   "capture-returned-no-blob"
                                   "capture seam did not return a PNG"
                                   "could not capture or copy the screenshot")]
                      (mark-error! :screenshot reason))
                    false))))))

;; ---- the dialog ----------------------------------------------------------

(def ^:private title-id "story-share-export-dialog-title")

(defn- command-block
  "One egress command row: a name + description, an optional badge, an
  action button, and an optional inline body (the URL field).

  Surfaces EITHER a `copied ✓` flash (`copied?`) OR an honest
  unavailable/error note (`error` — a short reason string), never both: a
  command that could not complete (e.g. screenshot capture/clipboard
  unavailable) MUST NOT show a false success (rf2-ehc5bq)."
  [{:keys [test name desc badge action action-label body copied? error]}]
  [:div {:style (:command styles) :data-test (str "story-egress-command-" test)}
   [:div {:style (:command-h styles)}
    [:span {:style (:command-name styles)} name]
    (when badge badge)]
   [:div {:style (:command-desc styles)} desc]
   (when body body)
   [:div {:style (:btn-row styles)}
    (when (and copied? (not error))
      [:span {:style (merge (:hint styles) {:font-style "normal"
                                            :color (:success colors/tokens)})
              :data-test (str "story-egress-copied-" test)}
       "copied ✓"])
    (when error
      [:span {:style (merge (:hint styles) {:font-style "normal"
                                            :color (:danger colors/tokens)})
              :role      "status"
              :data-test (str "story-egress-error-" test)}
       (str "unavailable — " error)])
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
  is the reproducibility honesty each row carries.

  EP-0015 scope (rf2-nnc06c): these ARE feature-created artifacts, so the
  EP scopes them in — but the recorded ruling is that human-local
  share/copy/export/screenshot is a trusted-local operator act
  (the `:rf.egress/local-raw` intent) and ships unredacted; the off-box
  boundaries (MCP / logs) are where redaction lives. See
  `re-frame.story.egress` §EP-0015 scope reconciliation + spec/022 §3.0."
  []
  (let [{:keys [open? copied error]} @dialog-state]
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
             :error   (when (= :screenshot (:cmd error)) (:reason error))
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
