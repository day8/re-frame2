(ns panel-gallery.gallery-settings
  "Story coverage for the **Settings popup modal** (rf2-mpn8m
  follow-on to rf2-9poxq + rf2-sszlr chrome gallery).

  The Settings popup mounts at `shell-view` root — same frame-
  provider discipline as `palette/Modal` and `filters/Modal`. Each
  variant here renders the full Causa 4-layer chrome via
  `:panel-gallery.chrome/Shell` AND seeds the chrome's `:rf/causa`
  slots so the popup opens against the declared tab + pre-populated
  settings state.

  ## Frame discipline

  The chrome's `shell-view` body hardcodes `[rf/frame-provider
  {:frame :rf/causa}]` so every subscribe inside the chrome — and
  every subscribe inside the modal it mounts — reads from the
  global `:rf/causa` frame regardless of the variant frame Story
  pre-allocated. Variants therefore route their seed events
  through `:panel-gallery.chrome/seed!`'s `:after-seeds` lane,
  which dispatches each event with `{:frame :rf/causa}` so the
  writes land on the frame the modal reads.

  ## Shared-state caveat (same as gallery-chrome)

  Because every variant writes to the same `:rf/causa` frame, a
  `:variants-grid` layout renders every cell simultaneously and
  the last-seeded variant wins in the shared interior. Use canvas
  mode (sidebar pick) for per-variant fidelity — the grid still
  proves the modal mounts + paints across each declared shape."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Settings popup Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-settings-popup
    {:axis :feature
     :doc  "Causa Settings popup modal — 6-tab strip (General /
            Theme / Filters / Keybindings / Buffer / Diff) per
            spec/007-UX-IA.md §Settings popup (rf2-ttnst expansion of
            the original 3-tab strip from rf2-9poxq). Telemetry tab
            was removed earlier per rf2-jh9ws (no endpoint exists)."})

  (story/reg-story :story.causa.settings-popup
    {:doc        "Visual gallery of the Causa Settings popup modal.
                 Each variant opens the popup pre-positioned on a
                 different tab with different pre-populated values.
                 All writes route through the chrome seed event's
                 `:after-seeds` lane so they land on `:rf/causa`
                 (where the chrome + modal read)."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/causa-settings-popup}
     :substrates #{:reagent}})

  ;; ----- 1. General tab — text-size mid-range, panel-position
  ;; right-rail, auto-open-on-error OFF (the explicit-defaults
  ;; baseline). The variant pre-writes the slot values so the
  ;; render is deterministic.
  (story/reg-variant :story.causa.settings-popup/general
    {:doc        "Settings popup open on General tab. Text-size
                 slider seeded mid-range (14 px), panel-position
                 :right-rail (default), auto-open-on-error OFF
                 (default)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/settings-update :general :text-size 14]
                     [:rf.causa/settings-update :general :panel-position :right-rail]
                     [:rf.causa/settings-update :general :auto-open-on-error? false]
                     [:rf.causa/settings-open]
                     [:rf.causa/settings-select-tab :general]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 2. Filters tab — auto-filter ns is on the classpath in
  ;; main (rf2-ak4ms landed) so the feature-detect surfaces the
  ;; 'Open auto-filter UI' button rather than the install-hint.
  (story/reg-variant :story.causa.settings-popup/filters-present
    {:doc        "Settings popup open on Filters tab — auto-filter
                 ns is on the classpath so the section renders the
                 'Open auto-filter UI' button (feature-detect path
                 from `settings/view.cljs` §filters-section)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/settings-open]
                     [:rf.causa/settings-select-tab :filters]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 3. Theme tab — dark theme selected (the default but
  ;; explicit so the radio selection is observable).
  (story/reg-variant :story.causa.settings-popup/theme-dark
    {:doc        "Settings popup open on Theme tab with the Dark
                 radio selected (default). The Light option is the
                 alternative; pre-alpha there is no accent picker."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/settings-update :theme nil :dark]
                     [:rf.causa/settings-open]
                     [:rf.causa/settings-select-tab :theme]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. Keybindings tab — read-only chord catalogue (rf2-ttnst).
  (story/reg-variant :story.causa.settings-popup/keybindings
    {:doc        "Settings popup open on Keybindings tab. v1 is
                 READ-ONLY — a chord catalogue mirroring spec/007-
                 UX-IA.md §Keyboard plus a master 'Handle keys?'
                 toggle. Rebind UI lands in v1.1."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/settings-open]
                     [:rf.causa/settings-select-tab :keybindings]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. Buffer tab — three numeric knobs + destructive Clear
  ;; (rf2-ttnst).
  (story/reg-variant :story.causa.settings-popup/buffer
    {:doc        "Settings popup open on Buffer tab. Three numeric
                 inputs (retained-epochs, trace-buffer/keep,
                 inspector-collapse-threshold) plus a destructive
                 'Clear buffer now' button. Clicking Clear opens a
                 confirmation modal (Cancel / Clear)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/settings-open]
                     [:rf.causa/settings-select-tab :buffer]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. Diff tab — opt-in fn-ref-changes toggle (rf2-i39w2).
  (story/reg-variant :story.causa.settings-popup/diff
    {:doc        "Settings popup open on Diff tab. The opt-in
                 :highlight-fn-ref-changes? toggle for the hiccup-diff
                 micro-engine (rf2-i39w2 Phase 3)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/settings-open]
                     [:rf.causa/settings-select-tab :diff]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; (Telemetry tab removed per rf2-jh9ws — no endpoint exists,
  ;; chrome must not pretend; section + variant deleted.)

  ;; ----- workspace ---------------------------------------------------
  ;;
  ;; Same shared-state caveat as `Workspace.causa.chrome/all` — every
  ;; cell shares `:rf/causa` (the chrome's hardcoded frame-provider).
  ;; The last variant to seed wins the shared interior; canvas-mode
  ;; (sidebar pick) is where per-variant fidelity is fully observable.
  (story/reg-workspace :Workspace.causa.settings-popup/all
    {:doc      "All Settings popup variants (General / Filters /
                Theme / Keybindings / Buffer / Diff). The chrome
                internally wraps :rf/causa via a hardcoded frame-
                provider, so workspace cells share interior state —
                see canvas-mode (sidebar pick) for per-variant
                fidelity."
     :layout   :variants-grid
     :story    :story.causa.settings-popup
     :columns  1
     :tags     #{:dev}}))

(register-all!)
