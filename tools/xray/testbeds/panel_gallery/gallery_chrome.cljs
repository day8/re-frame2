(ns panel-gallery.gallery-chrome
  "Story coverage for the **full 4-layer Xray chrome**
  (rf2-durls — redo against the de-singletoned shell + the four-bucket
  Story authoring model; supersedes the rf2-sszlr / rf2-1w07r gallery).

  The variants mount `:panel-gallery.chrome/Shell` — the entire ribbon
  + event-list + tab-bar + detail-panel stack (`shell/shell-view`). Per
  spec/018-Event-Spine §2 the chrome is four stacked layers.

  ## Per-cell frame isolation (de-singletoned shell — rf2-1w07r)

  `shell/shell-view` now takes a `:frame-id` opt (default
  `defaults/default-frame-id` = `:rf/xray`). The `chrome-shell` wrapper
  (`panel_views.cljs`) is `reg-view*`-registered, so its render runs
  under the Story per-variant frame scope (the namespace-preserving
  `frame-provider-existing` twin) and `(rf/current-frame-id)`
  resolves to the variant frame the canvas allocated for THIS cell. We
  thread that frame into `[shell/shell-view {:frame-id …}]`, so each
  chrome cell's app-db (focused epoch, selected tab, theme, modal
  open-state, filters) lives in its OWN variant frame.

  Story's runtime dispatches every `:setup` step into the SAME variant
  frame (`runtime/dispatch-sync ev {:frame variant-id}`). So a variant
  seeds its chrome state with the CANONICAL Xray events directly — no
  re-dispatch indirection, no `:rf/xray` literal, no shared-state
  serialisation. N chrome cells render simultaneously in a
  `:variants-grid` and stay fully isolated: driving one does not move
  the others.

  Pre rf2-1w07r the shell hardcoded `[frame-provider-existing {:frame :rf/xray}]`,
  so every cell's subscribes collided on the single global `:rf/xray`
  app-db; the gallery had to serialise rendering and re-dispatch its
  seeds into `:rf/xray` through a testbed-local `:panel-gallery.chrome/
  seed!` event. The parameterized shell retires all of that.

  ## Seed events

  Each variant's `:setup` fires the same real Xray events the shell
  itself dispatches at runtime, against the variant frame:

    - `:rf.xray/sync-trace-buffer`  — the LIVE trace buffer (drives the
                                      event-list L2 + every tab body).
    - `:rf.xray/sync-epoch-history` — the epoch ring (drives App-db +
                                      Views tabs).
    - `:rf.xray/select-tab`         — the active L4 tab.
    - `:rf.xray/add-filter`         — one ribbon pill (IN / OUT). Each
                                      cell starts with empty
                                      `:active-filters`, so the variant
                                      simply adds its declared pills.
    - `:rf.xray/toggle-live-pause`  — flip the LIVE feed to paused.

  ## Feature-detect notes

    - Auto-filter pills (rf2-ak4ms) — basic ribbon pill round-trip is
      in main; the rich filter popup gallery lives in `gallery_filters`.
    - Settings popup (rf2-9poxq / rf2-ttnst) — its variants live in
      `gallery_settings`."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.fixtures-app-db :as fixtures-app-db]
            [panel-gallery.fixtures-trace :as fixtures-trace]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

;; ---- setup builders ------------------------------------------------------
;;
;; Each chrome variant seeds its own variant frame with the canonical
;; Xray events. These helpers keep the `:setup` vectors declarative —
;; the variant passes the state it wants and the helper lowers it to the
;; event sequence Story dispatch-syncs into the variant frame.

(defn- seed-filters
  "Lower a `{:in [pill …] :out [pill …]}` filter map into a sequence of
  `[:rf.xray/add-filter mode pill]` events. Each variant frame starts
  with empty `:active-filters`, so adding the declared pills is the
  whole story — no remove-all needed."
  [{:keys [in out]}]
  (concat (for [pill in]  [:rf.xray/add-filter :in pill])
          (for [pill out] [:rf.xray/add-filter :out pill])))

(defn- chrome-setup
  "Build the `:setup` event vector for a chrome variant from a
  declarative state map. Order matters only in that filters add after
  the buffer seed; the shell reads each slot reactively.

    :trace-buffer  — vector → `:rf.xray/sync-trace-buffer`
    :epoch-history — vector → `:rf.xray/sync-epoch-history`
    :selected-tab  — kw     → `:rf.xray/select-tab`
    :filters       — {:in … :out …} → one `:rf.xray/add-filter` per pill
    :paused?       — true   → `:rf.xray/toggle-live-pause`"
  [{:keys [trace-buffer epoch-history selected-tab filters paused?]}]
  (cond-> []
    (some? trace-buffer)  (conj [:rf.xray/sync-trace-buffer trace-buffer])
    (some? epoch-history) (conj [:rf.xray/sync-epoch-history epoch-history])
    selected-tab          (conj [:rf.xray/select-tab selected-tab])
    (some? filters)       (into (seed-filters filters))
    paused?               (conj [:rf.xray/toggle-live-pause])))

(defn register-all!
  "Register the chrome Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/xray-chrome
    {:axis :feature
     :doc  "Xray 4-layer chrome — ribbon + event-list + tab-bar +
            detail per spec/018 §2."})

  (story/reg-story :story.xray.chrome
    {:doc        "Visual gallery of the full Xray 4-layer chrome. Each
                 variant mounts the shell in its OWN isolated frame
                 (the de-singletoned shell threads the Story per-variant
                 frame) and seeds that frame with the canonical Xray
                 events. Cells in the grid are fully isolated — driving
                 one does not move the others."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/xray-chrome}
     :substrates #{:reagent}})

  ;; ----- 1. Epoch tab pre-selected (default) -------------------------
  ;; Post rf2-5gl5r the Epoch tab supersedes the retired Event/Handler
  ;; tab as the default landing (:order -1, leftmost).
  (story/reg-variant :story.xray.chrome/tab-epoch
    {:doc        "Chrome with the Epoch tab pre-selected (default).
                 Trace buffer has six cascades; the event-list (L2)
                 surfaces them; the detail panel (L4) renders the
                 epoch panel with the head cascade focused."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures/n-cascades 6)
                    :selected-tab :epoch})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 2. App-db tab pre-selected ---------------------------------
  (story/reg-variant :story.xray.chrome/tab-app-db
    {:doc        "Chrome with the App-db tab pre-selected. Trace
                 buffer has cascades; epoch-history has the five-key-
                 change buffer; the detail panel renders app-db-diff."
     :setup      (chrome-setup
                   {:trace-buffer  (fixtures/n-cascades 3)
                    :epoch-history (fixtures-app-db/five-key-changes-buffer)
                    :selected-tab  :app-db})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. Views tab pre-selected ----------------------------------
  (story/reg-variant :story.xray.chrome/tab-views
    {:doc        "Chrome with the Views tab pre-selected. Detail
                 panel renders the Views panel against the seeded
                 epoch-history; the epoch lacks render rows so the
                 panel surfaces the no-renders branch — a real
                 production state worth pinning."
     :setup      (chrome-setup
                   {:trace-buffer  (fixtures/n-cascades 2)
                    :epoch-history (fixtures-app-db/single-key-change-buffer)
                    :selected-tab  :views})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 4. Trace tab pre-selected ----------------------------------
  (story/reg-variant :story.xray.chrome/tab-trace
    {:doc        "Chrome with the Trace tab pre-selected. Trace
                 buffer carries 10 events spanning every op-type;
                 the detail panel renders the raw-event feed."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures-trace/ten-events-buffer)
                    :selected-tab :trace})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 5. Machines tab pre-selected -------------------------------
  (story/reg-variant :story.xray.chrome/tab-machines
    {:doc        "Chrome with the Machines tab pre-selected. No
                 machine-registry overrides; the panel surfaces the
                 :no-machines empty-state — a real production state
                 worth pinning in the gallery."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures/n-cascades 2)
                    :selected-tab :machines})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 6. Chrome under issue load (rf2-gbz39) ---------------------
  ;; The dedicated Issues tab was removed per Mike's Option (c) ruling;
  ;; issues now surface INLINE in the Epoch panel + via the L2 event-
  ;; row pink-wash + the always-on issues ribbon signal. This variant
  ;; keeps the issue-bearing trace buffer but pre-selects the Epoch tab
  ;; — pinning the chrome's response under issue load (the L2 rows wash
  ;; pink; the Epoch panel surfaces the exception inline) rather than a
  ;; standalone Issues feed that no longer exists.
  (story/reg-variant :story.xray.chrome/issue-load
    {:doc        "Chrome under issue load. Trace buffer carries an
                 issue mix (errors / warnings / info); the L2 event
                 rows wash pink (rf2-b8guz) and the Epoch panel
                 surfaces the issues inline (rf2-ahhgn). No dedicated
                 Issues tab post rf2-gbz39 (Option (c))."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures-trace/error-buffer)
                    :selected-tab :epoch})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 7. Mode pill LIVE (default) --------------------------------
  (story/reg-variant :story.xray.chrome/mode-live
    {:doc        "Mode pill in LIVE mode (default). Trace buffer has
                 cascades; spine auto-focuses on head; mode pill
                 renders as `● LIVE`."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :epoch})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 8. Mode pill LIVE (paused) ---------------------------------
  (story/reg-variant :story.xray.chrome/mode-paused
    {:doc        "Mode pill in LIVE (paused) mode. The spine's
                 `:paused?` flag is set; the LIVE buffer continues
                 collecting but auto-scrolling stops. Mode pill
                 renders as `● LIVE (paused)`."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures/n-cascades 4)
                    :paused?      true
                    :selected-tab :epoch})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. Ribbon empty (no filters) -------------------------------
  (story/reg-variant :story.xray.chrome/ribbon-empty
    {:doc        "Ribbon resting state — no IN / OUT filter pills.
                 Only the `[+]` add-pill is visible alongside the
                 nav cluster + frame picker + mode pill + right
                 icons."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :epoch
                    :filters      {:in [] :out []}})
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 10. Ribbon with filters loaded -----------------------------
  (story/reg-variant :story.xray.chrome/ribbon-filters-loaded
    {:doc        "Ribbon with two IN pills (`:cart/*`, `:auth/*`) +
                 one OUT pill (`-:mouse-move`). Exercises the pill
                 visual contract per spec/018 §7 — IN pills tint
                 green, OUT pills tint magenta; each carries an `✎`
                 edit affordance."
     :setup      (chrome-setup
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :epoch
                    :filters      {:in  [{:pattern ":cart/*"}
                                         {:pattern ":auth/*"}]
                                   :out [{:pattern ":mouse-move"}]}})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  ;;
  ;; `:variants-grid` — all ten cells render simultaneously. Each cell's
  ;; `chrome-shell` threads ITS variant frame into the de-singletoned
  ;; shell (rf2-1w07r), and Story seeds each cell's frame independently,
  ;; so the cells are fully isolated: every cell paints its own declared
  ;; state with no last-seed-wins bleed. Single-column so the four-layer
  ;; chrome has room to breathe.
  (story/reg-workspace :Workspace.xray.chrome/all
    {:doc      "All ten chrome variants in one auto-grid. Each cell
                mounts the shell in its own isolated frame and paints
                its declared state independently — driving one cell
                does not move the others."
     :layout   :variants-grid
     :for      :story.xray.chrome
     :columns  1
     :tags     #{:dev}}))

(register-all!)
