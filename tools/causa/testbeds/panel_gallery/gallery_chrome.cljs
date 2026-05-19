(ns panel-gallery.gallery-chrome
  "Story coverage for the **full 4-layer Causa chrome**
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The variants mount `shell/shell-view` — the entire ribbon + event-
  list + tab-bar + detail-panel stack. Per spec/018 §2 the chrome is
  four stacked layers.

  ## A note on shared :rf/causa state

  `shell/shell-view`'s body explicitly wraps itself in
  `[rf/frame-provider {:frame :rf/causa}]`, so EVERY subscribe inside
  the chrome resolves to the global `:rf/causa` frame regardless of
  the variant frame the Story canvas pre-allocated for each variant.
  Variant `:events` dispatched into the variant frame therefore
  DON'T flow into the chrome's reads.

  To exercise distinct chrome states per variant, this gallery
  registers a testbed-local seed event
  `:panel-gallery.chrome/seed!` that re-dispatches its payload into
  `:rf/causa` directly. The handler is registered in `core.cljs`
  alongside the gallery boot. Variants here invoke that seed event
  via Story `:events`.

  Because of the shared `:rf/causa` state, the workspace uses
  `:layout :tabs` (not `:variants-grid`) — one variant rendered at a
  time, each one re-seeding `:rf/causa` on tab activation. A grid
  would mount all chrome cells simultaneously, and the last-seeded
  variant's state would bleed into all cells.

  ## Feature-detect notes

    - Auto-filter pills (rf2-ak4ms) — basic ribbon pill round-trip is
      in main; rich filter popup hasn't landed. The
      `ribbon-filters-loaded` variant exercises the basic
      add-filter dispatch.
    - Settings popup (rf2-9poxq) — `:rf.causa/open-settings` is a
      stub event in registry.cljs. No Settings modal renders yet;
      gallery variants for the modal wait on the Settings impl
      landing."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.fixtures-app-db :as fixtures-app-db]
            [panel-gallery.fixtures-trace :as fixtures-trace]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the chrome Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-chrome
    {:axis :feature
     :doc  "Causa 4-layer chrome — ribbon + event-list + tab-bar +
            detail per spec/018 §2."})

  (story/reg-story :story.causa.chrome
    {:doc        "Visual gallery of the full Causa 4-layer chrome.
                 Each variant re-seeds the global :rf/causa frame via
                 `:panel-gallery.chrome/seed!`; the chrome reads its
                 hardcoded :rf/causa frame; the :tabs workspace
                 layout serialises rendering so state never bleeds."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/causa-chrome}
     :substrates #{:reagent}})

  ;; ----- 1. Event tab pre-selected (default) -------------------------
  (story/reg-variant :story.causa.chrome/tab-event
    {:doc        "Chrome with the Event tab pre-selected (default).
                 Trace buffer has six cascades; the event-list (L2)
                 surfaces them; the detail panel (L4) renders
                 event-detail with the head cascade focused."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer  (fixtures/n-cascades 6)
                    :selected-tab  :event}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 2. App-db tab pre-selected ---------------------------------
  (story/reg-variant :story.causa.chrome/tab-app-db
    {:doc        "Chrome with the App-db tab pre-selected. Trace
                 buffer has cascades; epoch-history has the five-key-
                 change buffer; the detail panel renders app-db-diff."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer  (fixtures/n-cascades 3)
                    :epoch-history (fixtures-app-db/five-key-changes-buffer)
                    :selected-tab  :app-db}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. Views tab pre-selected ----------------------------------
  (story/reg-variant :story.causa.chrome/tab-views
    {:doc        "Chrome with the Views tab pre-selected. Detail
                 panel renders the Views panel against the seeded
                 epoch-history; the epoch lacks render rows so the
                 panel surfaces the no-renders branch — a real
                 production state worth pinning."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer  (fixtures/n-cascades 2)
                    :epoch-history (fixtures-app-db/single-key-change-buffer)
                    :selected-tab  :views}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 4. Trace tab pre-selected ----------------------------------
  (story/reg-variant :story.causa.chrome/tab-trace
    {:doc        "Chrome with the Trace tab pre-selected. Trace
                 buffer carries 10 events spanning every op-type;
                 the detail panel renders the raw-event feed."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures-trace/ten-events-buffer)
                    :selected-tab :trace}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 5. Machines tab pre-selected -------------------------------
  (story/reg-variant :story.causa.chrome/tab-machines
    {:doc        "Chrome with the Machines tab pre-selected. No
                 machine-registry overrides; the panel surfaces the
                 :no-machines empty-state — a real production state
                 worth pinning in the gallery."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 2)
                    :selected-tab :machines}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 6. Issues tab pre-selected ---------------------------------
  (story/reg-variant :story.causa.chrome/tab-issues
    {:doc        "Chrome with the Issues tab pre-selected. Trace
                 buffer carries an issue mix (errors / warnings /
                 info); the panel projection surfaces the issue
                 feed."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures-trace/error-buffer)
                    :selected-tab :issues}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 7. Mode pill LIVE (default) --------------------------------
  (story/reg-variant :story.causa.chrome/mode-live
    {:doc        "Mode pill in LIVE mode (default). Trace buffer has
                 cascades; spine auto-focuses on head; mode pill
                 renders as `● LIVE`."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :event}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 8. Mode pill LIVE (paused) ---------------------------------
  (story/reg-variant :story.causa.chrome/mode-paused
    {:doc        "Mode pill in LIVE (paused) mode. The spine's
                 `:paused?` flag is set; the LIVE buffer continues
                 collecting but auto-scrolling stops. Mode pill
                 renders as `● LIVE (paused)`."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 4)
                    :paused?      true
                    :selected-tab :event}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. Ribbon empty (no filters) -------------------------------
  (story/reg-variant :story.causa.chrome/ribbon-empty
    {:doc        "Ribbon resting state — no IN / OUT filter pills.
                 Only the `[+]` add-pill is visible alongside the
                 nav cluster + frame picker + mode pill + right
                 icons."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :filters      {:in [] :out []}}]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 10. Ribbon with filters loaded -----------------------------
  (story/reg-variant :story.causa.chrome/ribbon-filters-loaded
    {:doc        "Ribbon with two IN pills (`:cart/*`, `:auth/*`) +
                 one OUT pill (`-:mouse-move`). Exercises the pill
                 visual contract per spec/018 §7 — IN pills tint
                 green, OUT pills tint magenta; each carries an `✎`
                 edit affordance."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 4)
                    :selected-tab :event
                    :filters      {:in  [{:pattern ":cart/*"}
                                         {:pattern ":auth/*"}]
                                   :out [{:pattern ":mouse-move"}]}}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  ;;
  ;; ## :variants-grid with the shared-state caveat
  ;;
  ;; All cells render simultaneously, but the chrome internally
  ;; hardcodes `[rf/frame-provider {:frame :rf/causa}]` inside
  ;; `shell-view`'s body — so every chrome cell reads the SAME
  ;; `:rf/causa` app-db. Each variant's `:events` seed writes that
  ;; one shared db; the LAST variant to run's seed wins in the grid.
  ;;
  ;; That's a real limitation. The grid still proves the chrome
  ;; mounts + renders under each declared state shape (the cell
  ;; mounts the shell against the seed), and clicking a single
  ;; variant in the canvas (side panel selection) re-runs its seed
  ;; against `:rf/causa` deterministically. The workspace cell view
  ;; is the secondary surface; the canvas-mode single-variant view
  ;; (sidebar pick) is where per-variant chrome state is fully
  ;; observable.
  ;;
  ;; A `:tabs` layout would in principle render one variant at a
  ;; time, but Story v1 wires `:tabs` through the same
  ;; simultaneously-rendered cell path as `:variants-grid`
  ;; (`tools/story/src/re_frame/story/ui/workspace.cljc` §workspace-
  ;; view: the `:else` branch handles both). Genuine one-at-a-time
  ;; tab rendering is a follow-on Story enhancement; until then the
  ;; grid is the workspace surface.
  (story/reg-workspace :Workspace.causa.chrome/all
    {:doc      "All ten chrome variants. The chrome internally
                wraps :rf/causa via a hardcoded frame-provider, so
                workspace cells share interior state — see canvas-
                mode (sidebar pick) for per-variant chrome state."
     :layout   :variants-grid
     :story    :story.causa.chrome
     :columns  1
     :tags     #{:dev}}))

(register-all!)
