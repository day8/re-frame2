(ns panel-gallery.gallery-settings
  "Story coverage for the **Settings popup modal** (rf2-mpn8m
  follow-on to rf2-9poxq + rf2-sszlr chrome gallery).

  The Settings popup mounts at `shell-view` root — same frame-
  provider discipline as `palette/Modal` and `filters/Modal`. Each
  variant here renders the full Xray 4-layer chrome via
  `:panel-gallery.chrome/Shell` AND seeds the chrome's `:rf/xray`
  slots so the popup opens against the declared tab + pre-populated
  settings state.

  ## Frame discipline

  The chrome's `shell-view` body hardcodes `[rf/frame-provider
  {:frame :rf/xray}]` so every subscribe inside the chrome — and
  every subscribe inside the modal it mounts — reads from the
  global `:rf/xray` frame regardless of the variant frame Story
  pre-allocated. Variants therefore route their seed events
  through `:panel-gallery.chrome/seed!`'s `:after-seeds` lane,
  which dispatches each event with `{:frame :rf/xray}` so the
  writes land on the frame the modal reads.

  ## Shared-state caveat (same as gallery-chrome)

  Because every variant writes to the same `:rf/xray` frame, a
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

  (story/reg-tag :feature/xray-settings-popup
    {:axis :feature
     :doc  "Xray Settings popup modal — 4-tab strip (General /
            Keybindings / Buffer / Diff) per
            spec/007-UX-IA.md §Settings popup (rf2-ttnst expansion of
            the original 3-tab strip from rf2-9poxq). Telemetry tab
            was removed earlier per rf2-jh9ws (no endpoint exists);
            Theme tab retired per rf2-ou3pn — the ribbon's sun/moon
            icon is now the canonical light/dark affordance; Filters
            tab retired per rf2-wknb3 — full pill management lives
            in the ribbon + per-pill edit popup + mute manager."})

  (story/reg-story :story.xray.settings-popup
    {:doc        "Visual gallery of the Xray Settings popup modal.
                 Each variant opens the popup pre-positioned on a
                 different tab with different pre-populated values.
                 All writes route through the chrome seed event's
                 `:after-seeds` lane so they land on `:rf/xray`
                 (where the chrome + modal read)."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/xray-settings-popup}
     :substrates #{:reagent}})

  ;; ----- 1. General tab — text-size mid-range, panel-position
  ;; right-rail, auto-open-on-error OFF (the explicit-defaults
  ;; baseline). The variant pre-writes the slot values so the
  ;; render is deterministic.
  (story/reg-variant :story.xray.settings-popup/general
    {:doc        "Settings popup open on General tab. Text-size
                 slider seeded mid-range (14 px), panel-position
                 :right-rail (default), auto-open-on-error OFF
                 (default)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.xray/settings-update :general :text-size 14]
                     [:rf.xray/settings-update :general :panel-position :right-rail]
                     [:rf.xray/settings-update :general :auto-open-on-error? false]
                     [:rf.xray/settings-open]
                     [:rf.xray/settings-select-tab :general]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; (Filters tab variant retired per rf2-wknb3 — the popup no
  ;; longer carries a Filters tab. Full pill management lives in
  ;; the top-ribbon pill strip + per-pill edit popup + mute manager
  ;; modal; the settings tab was a discoverability pointer whose
  ;; only button dispatched an unregistered event.)

  ;; (Theme tab variant retired per rf2-ou3pn — the popup no longer
  ;; carries a Theme tab. The light/dark cycle is driven by the
  ;; top-ribbon sun/moon icon, which already has its own Story
  ;; coverage under the chrome gallery.)

  ;; ----- 2. Keybindings tab — read-only chord catalogue (rf2-ttnst).
  (story/reg-variant :story.xray.settings-popup/keybindings
    {:doc        "Settings popup open on Keybindings tab. v1 is
                 READ-ONLY — a chord catalogue mirroring spec/007-
                 UX-IA.md §Keyboard plus a master 'Handle keys?'
                 toggle. Rebind UI lands in v1.1."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.xray/settings-open]
                     [:rf.xray/settings-select-tab :keybindings]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 3. Buffer tab — epoch-history slider + two numeric knobs
  ;; + destructive Clear (rf2-ttnst; rf2-pu9sb consolidation).
  (story/reg-variant :story.xray.settings-popup/buffer
    {:doc        "Settings popup open on Buffer tab. Epoch history
                 slider (range 10–500, slot `:general :epoch-history`
                 — relocated from General per rf2-pu9sb) plus two
                 numeric inputs (cascades-retained,
                 inspector-collapse-threshold) plus a destructive
                 'Clear buffer now' button. Clicking Clear opens a
                 confirmation modal (Cancel / Clear)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.xray/settings-open]
                     [:rf.xray/settings-select-tab :buffer]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. Diff tab — opt-in fn-ref-changes toggle (rf2-i39w2).
  (story/reg-variant :story.xray.settings-popup/diff
    {:doc        "Settings popup open on Diff tab. The opt-in
                 :highlight-fn-ref-changes? toggle for the hiccup-diff
                 micro-engine (rf2-i39w2 Phase 3)."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/n-cascades 3)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.xray/settings-open]
                     [:rf.xray/settings-select-tab :diff]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; (Telemetry tab removed per rf2-jh9ws — no endpoint exists,
  ;; chrome must not pretend; section + variant deleted.)

  ;; ----- workspace ---------------------------------------------------
  ;;
  ;; Same shared-state caveat as `Workspace.xray.chrome/all` — every
  ;; cell shares `:rf/xray` (the chrome's hardcoded frame-provider).
  ;; The last variant to seed wins the shared interior; canvas-mode
  ;; (sidebar pick) is where per-variant fidelity is fully observable.
  (story/reg-workspace :Workspace.xray.settings-popup/all
    {:doc      "All Settings popup variants (General / Keybindings
                / Buffer / Diff). The chrome internally wraps
                :rf/xray via a hardcoded frame-provider, so
                workspace cells share interior state — see canvas-
                mode (sidebar pick) for per-variant fidelity. The
                Theme tab retired per rf2-ou3pn — the ribbon's
                sun/moon icon is the canonical light/dark
                affordance. The Filters tab retired per rf2-wknb3
                — full pill management lives in the ribbon strip +
                per-pill edit popup + mute manager modal."
     :layout   :variants-grid
     :story    :story.xray.settings-popup
     :columns  1
     :tags     #{:dev}}))

(register-all!)
