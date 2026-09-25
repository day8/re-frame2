(ns panel-gallery.gallery-settings
  "Story coverage for the **Settings popup modal**, built against the
  per-frame shell + the four-bucket Story model.

  Each variant renders the full Xray 4-layer chrome via
  `:panel-gallery.chrome/Shell` and opens the Settings popup
  pre-positioned on a tab with pre-populated settings state.

  ## Frame discipline

  The chrome's `shell-view` takes a `:frame-id` opt; the `chrome-shell`
  wrapper threads the Story per-variant frame (`(rf/current-frame-id)`)
  into it, so the shell — and the modal it mounts — read from THIS
  variant's frame. Story's runtime dispatches every `:setup` step into
  that same variant frame, so a variant seeds its chrome + opens the
  popup with the CANONICAL Xray events directly — no `:rf/xray` literal,
  no re-dispatch indirection. Cells in the grid are fully isolated."
  (:require [re-frame.story :as rf.story]
            [panel-gallery.fixtures :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn- settings-setup
  "Build the `:setup` event vector for a Settings-popup variant. Seeds
  the trace buffer + active tab, applies any pre-populated settings
  writes, then opens the popup on the requested tab — all dispatched
  into the variant frame the chrome cell reads.

    :trace-buffer  — vector → `:rf.xray/sync-trace-buffer`
    :selected-tab  — kw     → `:rf.xray/select-tab`
    :settings      — extra event vectors run BEFORE the popup opens
                     (e.g. `:rf.xray/settings-update` pre-population)
    :settings-tab  — kw     → opens the popup on this tab"
  [{:keys [trace-buffer selected-tab settings settings-tab]}]
  (cond-> []
    (some? trace-buffer) (conj [:rf.xray/sync-trace-buffer trace-buffer])
    selected-tab         (conj [:rf.xray/select-tab selected-tab])
    (seq settings)       (into (vec settings))
    true                 (conj [:rf.xray/settings-open])
    settings-tab         (conj [:rf.xray/settings-select-tab settings-tab])))

(defn register-all!
  "Register the Settings popup Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (rf.story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (rf.story/reg-tag :feature/xray-settings-popup
    {:axis :feature
     :doc  "Xray Settings popup modal — 4-tab strip (General /
            Keybindings / Buffer / Diff) per
            spec/007-UX-IA.md §Settings popup. There is no Telemetry
            tab (no endpoint exists), no Theme tab (the ribbon's
            sun/moon icon is the canonical light/dark affordance) and
            no Filters tab (full pill management lives in the ribbon +
            per-pill edit popup + mute manager)."})

  (rf.story/reg-story :story.xray.settings-popup
    {:doc        "Visual gallery of the Xray Settings popup modal.
                 Each variant opens the popup pre-positioned on a
                 different tab with different pre-populated values, in
                 its own isolated frame (the per-frame shell)."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/xray-settings-popup}
     :substrates #{:reagent}})

  ;; ----- 1. General tab — text-size mid-range, panel-position
  ;; right-rail, auto-open-on-error OFF (the explicit-defaults
  ;; baseline). The variant pre-writes the slot values so the
  ;; render is deterministic.
  (rf.story/reg-variant :story.xray.settings-popup/general
    {:doc        "Settings popup open on General tab. Text-size
                 slider seeded mid-range (14 px), panel-position
                 :right-rail (default), auto-open-on-error OFF
                 (default)."
     :setup      (settings-setup
                   {:trace-buffer (fixtures/cascades 3)
                    :selected-tab :epoch
                    :settings
                    [[:rf.xray/settings-update :general :text-size 14]
                     [:rf.xray/settings-update :general :panel-position :right-rail]
                     [:rf.xray/settings-update :general :auto-open-on-error? false]]
                    :settings-tab :general})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; (There is no Filters tab variant: the popup carries no Filters
  ;; tab. Full pill management lives in the top-ribbon pill strip +
  ;; per-pill edit popup + mute manager modal.)

  ;; (There is no Theme tab variant: the popup carries no Theme tab.
  ;; The light/dark cycle is driven by the top-ribbon sun/moon icon,
  ;; which has its own Story coverage under the chrome gallery.)

  ;; ----- 2. Keybindings tab — read-only chord catalogue.
  (rf.story/reg-variant :story.xray.settings-popup/keybindings
    {:doc        "Settings popup open on Keybindings tab. It is
                 READ-ONLY — a chord catalogue mirroring spec/007-
                 UX-IA.md §Keyboard plus a master 'Handle keys?'
                 toggle; there is no rebind UI."
     :setup      (settings-setup
                   {:trace-buffer (fixtures/cascades 3)
                    :selected-tab :epoch
                    :settings-tab :keybindings})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 3. Buffer tab — cascades-retained knob + destructive Clear.
  (rf.story/reg-variant :story.xray.settings-popup/buffer
    {:doc        "Settings popup open on Buffer tab. A single
                 `cascades-retained` numeric input (writes through to
                 `(rf/configure! {:trace-buffer {:events-retained N}})`)
                 plus a destructive 'Clear buffer now'
                 button. Clicking Clear opens a confirmation modal
                 (Cancel / Clear). The epoch-history slider lives in
                 General; there is no inspector-collapse-threshold
                 input."
     :setup      (settings-setup
                   {:trace-buffer (fixtures/cascades 3)
                    :selected-tab :epoch
                    :settings-tab :buffer})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. Diff tab — opt-in fn-ref-changes toggle.
  (rf.story/reg-variant :story.xray.settings-popup/diff
    {:doc        "Settings popup open on Diff tab. The opt-in
                 :highlight-fn-ref-changes? toggle for the hiccup-diff
                 micro-engine."
     :setup      (settings-setup
                   {:trace-buffer (fixtures/cascades 3)
                    :selected-tab :epoch
                    :settings-tab :diff})
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; (There is no Telemetry tab: no endpoint exists, so the chrome
  ;; does not pretend to have one.)

  ;; ----- workspace ---------------------------------------------------
  ;;
  ;; `:variants-grid` — each cell mounts the shell in its own variant
  ;; frame (the per-frame shell threads the per-cell frame), so the
  ;; four popups render side-by-side with no shared-state bleed.
  (rf.story/reg-workspace :Workspace.xray.settings-popup/all
    {:doc      "All Settings popup variants (General / Keybindings /
                Buffer / Diff) in one grid, each in its own isolated
                frame. There is no Theme tab — the
                ribbon's sun/moon icon is the canonical light/dark
                affordance — and no Filters tab —
                full pill management lives in the ribbon strip +
                per-pill edit popup + mute manager modal."
     :layout   :variants-grid
     :for      :story.xray.settings-popup
     :columns  1
     :tags     #{:dev}}))

(register-all!)
