(ns panel-gallery.gallery-machines
  "Story coverage for the **Machines tab** of the new 6-tab Causa chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The Machines tab body is the `machine-inspector/Panel` view
  (rf2-2tkza Phase 1 + rf2-v869p Phase 2; spec/003-Machine-Inspector).
  The panel reads:

    - `:rf.causa/registered-machines`  — defaults to `(rf/machines)`;
                                         test override slot exists.
    - `:rf.causa/machine-snapshots`    — defaults to target-frame's
                                         `:rf/machines`; test override
                                         slot exists.
    - `:rf.causa/machine-definitions`  — defaults to
                                         `(rf/machine-meta ...)`; test
                                         override slot exists.
    - `:rf.causa/trace-buffer`         — drives transition-history.
    - `:rf.causa/selected-machine-id`  — picker focus.

  Each variant seeds the override slots via the test-only events
  (`:rf.causa/set-registered-machines-override-for-test`,
  `:rf.causa/set-machine-snapshots-override-for-test`,
  `:rf.causa/set-machine-definitions-override-for-test`) and the
  trace-buffer via `:rf.causa/sync-trace-buffer`.

  ## UC1 Sim sub-mode (Phase 2)

  When a Mode-A panel has Sim active, the chart tints amber + a side-
  rail surfaces an event picker + Step / Reset buttons. The Sim
  state slot is `:sim/by-machine {<id> <sim-state>}` — the
  `:rf.causa/sim-start` event seeds it. The 'sim mid-step' variant
  fires sim-start then sim-step to land mid-execution. The
  'sim pending-input' variant fires sim-start then seeds the
  controlled-input slots (`:pending-event` / `:pending-data`) via
  `:rf.causa/sim-set-pending-event` + `-pending-data` so the side
  rail's compose box is populated mid-type but not yet stepped."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-machines :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Machines tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-machines
    {:axis :feature
     :doc  "Causa Machines tab — Mode A (definition) / Mode B
            (instance) chart + transition-history ribbon + UC1 Sim
            sub-mode per spec/003 + spec/018 §5.5."})

  (story/reg-story :story.causa.machines
    {:doc        "Visual gallery of the Causa Machines tab under
                 varying registry shapes. Each variant seeds the
                 registered-machine + snapshot + definition override
                 slots via the test-only events; the panel projection
                 reads the overrides without booting a host that
                 registers machines."
     :component  :panel-gallery.machines/Panel
     :tags       #{:dev :feature/causa-machines}
     :substrates #{:reagent}})

  ;; ----- 1. no machines registered ----------------------------------
  (story/reg-variant :story.causa.machines/no-machines
    {:doc        "No machines registered. Panel renders the
                 :no-machines empty-state copy."
     :events     [[:rf.causa/set-registered-machines-override-for-test []]
                  [:rf.causa/set-machine-snapshots-override-for-test {}]
                  [:rf.causa/set-machine-definitions-override-for-test {}]
                  [:rf.causa/sync-trace-buffer (fixtures/no-transitions-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. single machine, Mode A (definition view) ----------------
  (story/reg-variant :story.causa.machines/single-mode-a
    {:doc        "Single :loader machine registered with a populated
                 definition but NO live snapshot. The panel surfaces
                 the picker with the definition chart (Mode A) — the
                 chart renders the static state graph."
     :events     [[:rf.causa/set-registered-machines-override-for-test [:loader]]
                  [:rf.causa/set-machine-snapshots-override-for-test {}]
                  [:rf.causa/set-machine-definitions-override-for-test
                   {:loader fixtures/loader-definition}]
                  [:rf.causa/sync-trace-buffer (fixtures/no-transitions-buffer)]
                  [:rf.causa/select-machine-id :loader]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. single machine, Mode B (instance) -----------------------
  (story/reg-variant :story.causa.machines/single-mode-b
    {:doc        "Single :loader machine registered WITH a live
                 snapshot (`:loaded` state, populated data). The
                 panel renders the chart in Mode B — current state
                 highlight overlays the static graph; the picker
                 surfaces the current state."
     :events     [[:rf.causa/set-registered-machines-override-for-test [:loader]]
                  [:rf.causa/set-machine-snapshots-override-for-test
                   {:loader (fixtures/snapshot :loaded
                              {:result :data :attempts 1 :error nil})}]
                  [:rf.causa/set-machine-definitions-override-for-test
                   {:loader fixtures/loader-definition}]
                  [:rf.causa/sync-trace-buffer (fixtures/loader-transition-buffer)]
                  [:rf.causa/select-machine-id :loader]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 4. multiple machines ---------------------------------------
  (story/reg-variant :story.causa.machines/multi-machine
    {:doc        "Three machines registered (`:loader`, `:auth`,
                 `:checkout`) each with a populated snapshot +
                 definition. The picker offers all three; the
                 default selection is the alphabetically-first row
                 (`:auth`)."
     :events     [[:rf.causa/set-registered-machines-override-for-test
                   (fixtures/registered-machines-multi)]
                  [:rf.causa/set-machine-snapshots-override-for-test
                   (fixtures/machine-snapshots-multi)]
                  [:rf.causa/set-machine-definitions-override-for-test
                   (fixtures/machine-definitions-multi)]
                  [:rf.causa/sync-trace-buffer (fixtures/no-transitions-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 5. many transitions -----------------------------------------
  (story/reg-variant :story.causa.machines/many-transitions
    {:doc        "Single :loader machine with eighteen transitions
                 (mixed outer + microstep) in the trace buffer. Pins
                 the transition-history ribbon at scroll depth +
                 exercises microstep rendering."
     :events     [[:rf.causa/set-registered-machines-override-for-test [:loader]]
                  [:rf.causa/set-machine-snapshots-override-for-test
                   {:loader (fixtures/snapshot :loaded {:result :ok})}]
                  [:rf.causa/set-machine-definitions-override-for-test
                   {:loader fixtures/loader-definition}]
                  [:rf.causa/sync-trace-buffer (fixtures/many-transitions-buffer)]
                  [:rf.causa/select-machine-id :loader]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 6. UC1 Sim mid-step ----------------------------------------
  ;;
  ;; Seed the machine + definition, then sim-start (clones the
  ;; definition into Causa's app-db), then sim-step with the
  ;; `:start` event so the cloned snapshot advances :idle → :loading
  ;; mid-execution. The variant renders the side rail + amber tint.
  (story/reg-variant :story.causa.machines/uc1-sim-mid-step
    {:doc        "Single :loader machine + UC1 Sim active mid-step:
                 sim-start clones the definition, sim-step fires
                 `[:start]` advancing the cloned snapshot :idle →
                 :loading. The side rail renders an event picker +
                 Step / Reset buttons + the audit trail; the chart
                 tints amber."
     :events     [[:rf.causa/set-registered-machines-override-for-test [:loader]]
                  [:rf.causa/set-machine-snapshots-override-for-test
                   {:loader (fixtures/snapshot :idle
                              {:result nil :attempts 0 :error nil})}]
                  [:rf.causa/set-machine-definitions-override-for-test
                   {:loader fixtures/loader-definition}]
                  [:rf.causa/sync-trace-buffer (fixtures/no-transitions-buffer)]
                  [:rf.causa/select-machine-id :loader]
                  [:rf.causa/sim-start {:machine-id :loader
                                        :definition fixtures/loader-definition}]
                  [:rf.causa/sim-step  {:machine-id :loader
                                        :event [:start]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. UC1 Sim pending-input ------------------------------------
  ;;
  ;; Same UC1 Sim sub-mode as variant 6, but instead of stepping the
  ;; cloned snapshot we seed the controlled-input slots
  ;; (`:pending-event` + `:pending-data`) via the existing sim API
  ;; (`:rf.causa/sim-set-pending-event` +
  ;; `:rf.causa/sim-set-pending-data`). The snapshot stays at
  ;; `:idle`; the side-rail event-input shows `[:start]` already
  ;; typed and the payload input shows a sample EDN map, so the
  ;; Step button is primed but not yet pressed.
  ;;
  ;; Approach (b) per rf2-cujgr — uses the existing sim API instead of
  ;; bypass-writing app-db. Deterministic because the variant `:events`
  ;; vector is dispatched in order at boot, the same order the existing
  ;; `uc1-sim-mid-step` variant already relies on.
  (story/reg-variant :story.causa.machines/uc1-sim-pending-input
    {:doc        "Single :loader machine + UC1 Sim active with the
                 side-rail controlled inputs populated mid-compose:
                 `:pending-event` is `[:start]` and `:pending-data` is
                 a sample EDN map. The snapshot stays at `:idle` — the
                 user has typed but not yet pressed Step. Renders the
                 side rail + amber tint with the inputs primed."
     :events     [[:rf.causa/set-registered-machines-override-for-test [:loader]]
                  [:rf.causa/set-machine-snapshots-override-for-test
                   {:loader (fixtures/snapshot :idle
                              {:result nil :attempts 0 :error nil})}]
                  [:rf.causa/set-machine-definitions-override-for-test
                   {:loader fixtures/loader-definition}]
                  [:rf.causa/sync-trace-buffer (fixtures/no-transitions-buffer)]
                  [:rf.causa/select-machine-id :loader]
                  [:rf.causa/sim-start {:machine-id :loader
                                        :definition fixtures/loader-definition}]
                  [:rf.causa/sim-set-pending-event
                   {:machine-id :loader :text "[:start]"}]
                  [:rf.causa/sim-set-pending-data
                   {:machine-id :loader :text "{:result :data}"}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.machines/all
    {:doc      "All seven Machines tab variants in one auto-grid.
                Scroll to see the panel's response across no-machines
                / single Mode A / single Mode B / multi-machine /
                many-transitions / UC1 Sim mid-step / UC1 Sim
                pending-input."
     :layout   :variants-grid
     :story    :story.causa.machines
     :columns  2
     :tags     #{:dev}}))

(register-all!)
