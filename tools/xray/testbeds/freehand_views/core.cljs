(ns freehand-views.core
  "FREEHAND-VIEWS testbed — the one shipped Xray driving surface whose views
  are FREEHAND views, so the Views panel renders POPULATED rows.

  ## Why this deck exists

  Every other Xray testbed is Reagent-hosted, and a Reagent host connects no
  Freehand occurrence. So the browser lane could prove the Mounted Views
  section RENDERS, and could prove nothing about what it renders: a section
  that is empty because nothing is mounted and a section that is empty
  because the read through `re-frame.freehand.tool` is BROKEN are the same
  DOM (rf2-6pohj). Populated-row proof lived only in the node suite, which
  renders the same view function but not in a real browser.

  This deck closes that. Three declared Freehand views connect three
  occurrences over one frame; the Views panel reads them through the tool
  door and renders a row each. Break the read and the section falls back to
  its empty state — which the sweep in
  `tools/xray/testbeds/feature_matrix/scenarios.cjs` fails on.

  ## The host shape, and why it is MIXED rather than pure Freehand

  `rf/init!` installs the REAGENT adapter here, and the views are Freehand.
  That is not a compromise made for the test — it is the only host shape in
  which the two can be seen together today, and the testbed exists to be
  honest about it.

  Xray's shell is authored in hiccup, so it can only mount through a
  ratom-family `:render`. `day8.re-frame2-xray.mount` therefore REFUSES the
  React-element-shaped adapters, `:rf.adapter/freehand` among them
  (rf2-qgfo4): on a host that installed `v/adapter` the shell does not mount
  at all, and there is no Views panel to populate. What the substrate
  contract does bless is exactly this arrangement — \"bring-your-own adapter
  ... stays legitimate for a mixed application under the single-adapter
  runtime\" (`re-frame.freehand.substrate`). The adapter supplies the
  OBSERVATION half, and supplying it takes more than handing back a
  watchable derived value. Reagent's are demand-driven: a
  `reagent.ratom/Reaction` learns its sources only by being run through
  `deref-capture`, so a read taken outside a reactive context leaves it
  subscribed to nothing — watchable, and silent for as long as it lives. A
  Reagent COMPONENT never meets that, because its render IS the capture
  context; a ViewCell is not a component, so nothing supplies one on its
  behalf. Reagent closes the gap by publishing the optional
  `:adapter/activate-derived-value!` late-bind hook, which puts the value on
  the substrate's push path (spec/006 §Reactive substrate — watchable is
  necessary, not sufficient; rf2-8cnxg). With that, a cell on this page
  genuinely REPAINTS when app-db moves. Freehand renders itself through
  `react-dom/client` either way.

  So the split is clean and deliberate:

    - the REAGENT adapter — app-db's state container, the watchable derived
      values Freehand cells observe through, and the `:render` slot Xray's
      hiccup shell mounts into.
    - FREEHAND — every view on the page, mounted with `v/mount` over the
      same `:rf/default` frame Xray reads by default.

  Nothing here is Reagent-rendered. `#app` is a Freehand root; the Xray
  shell is the only other tree on the page, and the preload mounts it into
  `[data-rf-xray-host]`.

  ## What the three views are FOR

  Each one exists to put a distinguishable fact on a row, so an assertion
  can name what it is reading rather than counting anonymous entries:

    - [[readout]]   — `{:compiled true}`, and the only view that READS. Its
                      row carries `1 read`, and its manifest carries a
                      subscription site, so the Declared View Sites section
                      has a `:basis :static-proof` arm to render.
    - [[controls]]  — `{:compiled true}`, and the only view that
                      DISPATCHES. Its manifest carries the event site.
    - [[app]]       — INTERPRETED, deliberately: its row is the `lowering`
                      contrast, and its site row is the `:basis :opaque` /
                      `no static analysis` arm — the axis the donor
                      evidence tier could not state at all.

  ## The fact that MOVES, and the one that does not

  A populated row can still be a fabricated row, so the sweep also asserts a
  fact that MOVES: the OCCURRENCE KEY. `Unmount root` disconnects the three
  cells, `Mount root` reconnects them, and the roster comes back with three
  freshly minted keys — the same view ids over different occurrences. A
  projection reading a static registry (which is what the donor tier had, and
  what the Freehand door deliberately does not) cannot produce that.

  What does NOT move here is `:generation`. It is the BODY REVISION — the
  hot-reload fence — not a render tally, so it stays 0 for a page that is
  never hot-reloaded. Naming it here because `gen 0` on every row looks like
  a stuck counter and is not one.

  ## A TEST surface

  No deliberate bugs, no teaching layers, no anti-patterns — the standing
  rule for every deck under `tools/xray/testbeds/`."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]
            ;; The OBSERVATION half of the substrate contract — including
            ;; the `:adapter/activate-derived-value!` hook a ViewCell's reads
            ;; need to be live at all — and the `:render` slot Xray's hiccup
            ;; shell needs. See the ns docstring: this is a blessed mixed
            ;; host, not a stand-in for `v/adapter`.
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's `configure!` to seed `:project-root` so the per-site
            ;; `[code]` chips in Declared View Sites resolve a
            ;; classpath-relative `:file` to an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper: derives the open-in-editor
            ;; project-root from the build env.
            [re-frame.testbed.config :as testbed-config]))

;; ============================================================================
;; HANDLERS — ordinary re-frame2
;; ============================================================================

(rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))

(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(rf/reg-sub ::count (fn [db _] (:count db)))

;; ============================================================================
;; THE VIEWS
;; ============================================================================

(v/defview readout
  "The reading view — compiled, so its declaration carries a proven
  subscription site, and the only `v/sub` on the page. One read, so its
  roster row states `1 read` exactly."
  {:compiled true}
  [{:keys [label]}]
  [:div {:data-testid "fh-readout"}
   [:span {:data-testid "fh-label"} label]
   [:output {:data-testid "fh-count"} (str (v/sub [::count]))]])

(v/defview controls
  "The dispatching view — compiled, so its declaration carries a proven
  event site, and the only handler on the page. Pressing `+` moves app-db
  and [[readout]] repaints. Not its `:generation` — that is the hot-reload
  body revision, not a render tally, and it stays 0 here (see the ns
  docstring)."
  {:compiled true}
  [_]
  [:div {:data-testid "fh-controls"}
   [:button {:data-testid "fh-bump" :on-click [::bump]} "+"]])

(v/defview app
  "The mounted root — INTERPRETED on purpose. It is the lowering contrast on
  the roster and the un-analysed arm in Declared View Sites, so both halves
  of the projection vocabulary are on screen at once."
  [_]
  [:main {:data-testid "fh-app"}
   [:h1 "Xray Testbed: Freehand Views"]
   [readout {:label "count"}]
   [controls {}]])

;; ============================================================================
;; BOOT — and the two HOST controls
;; ============================================================================
;;
;; `Mount root` and `Unmount root` are page controls, not application state.
;; They drive `v/mount` / `v/unmount!`, the host's imperative root verbs
;; (they take a DOM node), so they belong to the boot script and are wired
;; onto plain buttons in index.html rather than authored as views. Together
;; they are the OCCURRENCE-IDENTITY lever: unmount disconnects the three
;; cells, mount reconnects them, and the roster comes back keyed on three
;; freshly minted occurrences. `v/mount` is idempotent per root, so the same
;; button re-renders a live root and mounts a fresh one after an unmount.
;;
;; They are NOT how you move a cell, though this comment used to say they
;; were. Pressing `+` repaints [[readout]] directly: the dispatch lands, the
;; App-db panel shows `{:count 1 ← was 0}`, and the cell follows. It did not
;; always — under a ratom-family adapter the cell's committed read once
;; received no change notification at all, so `cell/pending-count` stayed 0
;; and the readout sat at its first value while app-db moved underneath it.
;; The cause was in the observation port, not the host: it installed a watch
;; on a `reagent.ratom/Reaction` that had captured no sources, because a
;; `Reaction` captures only through `deref-capture` and a ViewCell, not being
;; a component, supplies no capture context. The port now ACTIVATES the value
;; through `:adapter/activate-derived-value!` (rf2-8cnxg / rf2-jt8vz), so
;; this host both repaints a Freehand cell and shows Xray's Views panel. The
;; only refusal still standing is Xray's own: it will not mount on the
;; React-element-shaped adapters, `:rf.adapter/freehand` among them
;; (rf2-qgfo4).

(defn- resolve-source-root []
  (testbed-config/resolve-source-root "tools/xray/testbeds"))

(def ^:private host-frame
  "The one frame on the page, and the one Xray selects by default — so the
  epoch pump that refreshes `:rf.xray/mounted-views` and the commits the
  roster reports are the same frame's."
  :rf/default)

(defonce ^:private root-handle
  ;; The host's own handle on the live Freehand root — the boot script's
  ;; property, never application state (it is not read by any view and never
  ;; reaches app-db).
  (atom nil))

(defn- mount! []
  (reset! root-handle
          (v/mount [app {}]
                   (js/document.getElementById "app")
                   {:frame host-frame})))

(defn- unmount! []
  (when-some [root @root-handle]
    ;; The root SCOPES `:rf/default` rather than owning it, so unmounting
    ;; releases the reference and leaves the frame — and Xray, which reads
    ;; it — alive.
    (v/unmount! root)
    (reset! root-handle nil)))

(defn- on-click! [element-id f]
  (some-> (js/document.getElementById element-id)
          (.addEventListener "click" f)))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads the
  ;; right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-source-root)})
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002: the runtime never synthesises a frame from absence. Make the
  ;; host frame, seed it, then SCOPE it from the root — `:frame <keyword>`
  ;; scopes a frame something else owns, which is what this is: the deck owns
  ;; the frame, the root only renders over it.
  (rf/make-frame {:id host-frame})
  (rf/dispatch-sync [::seed] {:frame host-frame})
  (mount!)
  (on-click! "fh-mount" (fn [_] (mount!)))
  (on-click! "fh-unmount" (fn [_] (unmount!))))
