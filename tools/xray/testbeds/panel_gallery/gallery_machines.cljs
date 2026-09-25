(ns panel-gallery.gallery-machines
  "Story coverage for the **Machine tab** of the Xray 4-layer chrome
  (spec/018-Event-Spine).

  The Machines tab body is the `machine-inspector/Panel` view
  (spec/003-Machine-Inspector), an event-driven lens: when the focused
  event targeted a machine it renders that transition — the shared
  event-handler mini-pipeline and the topology chart — and otherwise the
  one-line placeholder, or the empty state when no machine is
  registered. It carries no machine picker and no Sim; the Sim lives on
  the Static Machines lens.

  The panel reads the `:rf.xray/machine-inspector-data` composite and the
  focused epoch. The composite is over:

    - `:rf.xray/registered-machines`  — defaults to the `:rf/machine?` filter
                                         over the generic registrar read;
                                         test override slot exists.
    - `:rf.xray/machine-snapshots`    — defaults to target-frame's
                                         `[:rf.runtime/machines
                                          :snapshots]` runtime-db subtree; test
                                         override slot exists.
    - `:rf.xray/machine-definitions`  — defaults to
                                         the `:rf/machine` registrar projection; test
                                         override slot exists.

  The variant seeds the override slots via the test-only events
  (`:rf.xray/set-registered-machines-override-for-test`,
  `:rf.xray/set-machine-snapshots-override-for-test`,
  `:rf.xray/set-machine-definitions-override-for-test`, installed by
  `panel-gallery.core/register-handlers!`) and the trace-buffer via
  `:rf.xray/sync-trace-buffer`."
  (:require [re-frame.story :as rf.story]
            [panel-gallery.fixtures-machines :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Machines tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (rf.story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (rf.story/reg-tag :feature/xray-machines
    {:axis :feature
     :doc  "Xray Machines tab — the focused event's machine
            transition (event-handler mini-pipeline + topology chart)
            per spec/003 + spec/018 §5.5."})

  (rf.story/reg-story :story.xray.machines
    {:doc        "Visual gallery of the Xray Machines tab. Each variant
                 seeds the registered-machine + snapshot + definition
                 override slots via the test-only events; the panel
                 projection reads the overrides without booting a host
                 that registers machines."
     :component  :panel-gallery.machines/Panel
     :tags       #{:dev :feature/xray-machines}
     :substrates #{:reagent}})

  ;; ----- 1. no machines registered ----------------------------------
  (rf.story/reg-variant :story.xray.machines/no-machines
    {:doc        "No machines registered. Panel renders the
                 :no-machines empty-state copy."
     :setup     [[:rf.xray/set-registered-machines-override-for-test []]
                  [:rf.xray/set-machine-snapshots-override-for-test {}]
                  [:rf.xray/set-machine-definitions-override-for-test {}]
                  [:rf.xray/sync-trace-buffer (fixtures/no-transitions-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (rf.story/reg-workspace :Workspace.xray.machines/all
    {:doc      "The Machines tab variants in one auto-grid — the
                no-machines empty state."
     :layout   :variants-grid
     :for      :story.xray.machines
     :columns  2
     :tags     #{:dev}}))

(register-all!)
