(ns panel-gallery.gallery-views
  "Story coverage for the **Views tab** of the new 6-tab Causa chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The Views tab body is the `views/Panel` view (rf2-21ob3;
  spec/012-Views.md). The panel reads its data from
  `:rf.causa/views-data`, a composite over the spine's
  `:rf.causa/focus` + the current frame's `:epoch-history`. Each
  variant seeds the variant frame's `:epoch-history` via
  `:rf.causa/sync-epoch-history`; the spine's default `:live` mode
  auto-focuses on the head cascade so no explicit focus event is
  required."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-views :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Views tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-views
    {:axis :feature
     :doc  "Causa Views tab — three-group (Mounted / Re-rendered /
            Unmounted) classification + clustering per spec/012-Views.md."})

  (story/reg-story :story.causa.views
    {:doc        "Visual gallery of the Causa Views tab under varying
                 render-set magnitude + shape. Each variant seeds
                 its frame's :epoch-history via
                 :rf.causa/sync-epoch-history; the panel composite
                 reads the spine-focused cascade pair from the
                 variant frame in isolation."
     :component  :panel-gallery.views/Panel
     :tags       #{:dev :feature/causa-views}
     :substrates #{:reagent}})

  ;; ----- 1. empty (no cascade) ---------------------------------------
  (story/reg-variant :story.causa.views/empty
    {:doc        "No epochs in history. Panel renders the no-cascade
                 empty-state."
     :events     [[:rf.causa/sync-epoch-history (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. sparse subs ---------------------------------------------
  (story/reg-variant :story.causa.views/sparse-subs
    {:doc        "Two epochs with two views each + a single sub-run.
                 Pins the panel's sparse-resting render shape."
     :events     [[:rf.causa/sync-epoch-history (fixtures/sparse-subs-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. dense subs ----------------------------------------------
  (story/reg-variant :story.causa.views/dense-subs
    {:doc        "Mid-load: ~30 views + ~15 sub-runs in the focused
                 cascade. Comfortable density that mirrors a busy
                 list+detail page."
     :events     [[:rf.causa/sync-epoch-history (fixtures/dense-subs-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. grid-explosion clustering -------------------------------
  (story/reg-variant :story.causa.views/grid-explosion
    {:doc        "One epoch whose render list contains 80 grid cells
                 sharing the same `(view-id, triggered-by)` tuple.
                 Pushes past the default cluster-threshold (50) so
                 the panel collapses them into one aggregate row
                 with `× N · total ms · avg µs` stats per spec
                 §Grid-explosion clustering."
     :events     [[:rf.causa/sync-epoch-history (fixtures/grid-explosion-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. three-group (mounted / re-rendered / unmounted) ---------
  (story/reg-variant :story.causa.views/three-group
    {:doc        "Two epochs designed to surface all three groups
                 (Mounted / Re-rendered / Unmounted) populated
                 simultaneously: prior had cart header + list +
                 footer; current has header (re) + list (re) +
                 promo (new, mounted), footer gone (unmounted)."
     :events     [[:rf.causa/sync-epoch-history (fixtures/three-group-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. filter-applied (component-filter active) ----------------
  (story/reg-variant :story.causa.views/filter-applied
    {:doc        "Two epochs with renders across multiple view-ids;
                 variant seeds the panel's component-filter to
                 `:cart/list` so only those rows surface."
     :events     [[:rf.causa/sync-epoch-history (fixtures/filter-applied-buffer)]
                  [:rf.causa/views-set-component-filter :cart/list]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.views/all
    {:doc      "All six Views tab variants in one auto-grid. Scroll
                to see the panel's response across empty / sparse /
                dense / grid-explosion / three-group / filter-applied."
     :layout   :variants-grid
     :story    :story.causa.views
     :columns  2
     :tags     #{:dev}}))

(register-all!)
