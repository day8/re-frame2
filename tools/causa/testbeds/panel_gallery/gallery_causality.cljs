(ns panel-gallery.gallery-causality
  "Story coverage for the **Causality popover** (rf2-pt1e1 follow-on
  to rf2-dqnuu + rf2-sszlr chrome gallery).

  The Causality popover replaces the legacy Causality tab — it's a
  c-key triggered modal overlay rooted at the spine-focused event.
  Per spec/018-Event-Spine.md §10 it renders the focused-event's
  causal graph: ancestor chain (LR, root-cause → focused) + a TB
  descendants tree, with ELK supplying pixel layout when available
  and a flat-list fallback when ELK is unavailable.

  Each variant renders the full Causa 4-layer chrome via
  `:panel-gallery.chrome/Shell`, seeds `:rf/causa` with a trace
  buffer that carries `:parent-dispatch-id` lineage tags, focuses
  the spine on a mid-chain cascade, then dispatches the popover-
  open event. The fallback variant additionally pins the ELK loader
  state to `:failed` via the testbed-local
  `:panel-gallery.causality/force-elk-fallback!` event.

  ## Frame discipline (same caveat as gallery-chrome)

  The chrome's `shell-view` body wraps itself in `[rf/frame-provider
  {:frame :rf/causa}]`. All seed events route through the testbed's
  `:panel-gallery.chrome/seed!` `:after-seeds` lane so the writes
  land on `:rf/causa` (where the chrome + popover read), not on the
  variant frame Story pre-allocated."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-causality :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Causality popover Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-causality-popover
    {:axis :feature
     :doc  "Causa Causality popover — c-key triggered overlay
            with ELK-laid-out graph + fallback list per
            spec/018-Event-Spine §10."})

  (story/reg-story :story.causa.causality-popover
    {:doc        "Visual gallery of the Causa Causality popover.
                 Variants exercise the default TB layout, the
                 footer-flipped LR layout, the ELK-unavailable
                 fallback render, and a 20-node graph that pushes
                 the popover's scroll + truncation surfaces."
     :component  :panel-gallery.chrome/Shell
     :tags       #{:dev :feature/causa-causality-popover}
     :substrates #{:reagent}})

  ;; ----- 1. TB layout (default) — 5-node sample graph.
  (story/reg-variant :story.causa.causality-popover/tb
    {:doc        "Popover open with TB layout (default). Focused on
                 cascade C (200) of the five-node sample graph —
                 popover renders ancestor chain (A → B) above the
                 focused node, with descendant (D) hanging below.
                 TB orientation matches spec/018 §10 Q12 v1
                 default — the descendants tree dominates the
                 visual weight."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/five-node-buffer)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/focus-cascade
                      (fixtures/focused-dispatch-id-for-five)]
                     [:rf.causa/causality-popover-open]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 2. LR layout (footer-flipped) — same graph.
  (story/reg-variant :story.causa.causality-popover/lr
    {:doc        "Popover open with LR layout (footer toggle to
                 :lr). Same 5-node graph as the TB variant; the
                 layout flip rotates the ancestor + descendant
                 stacks horizontally."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/five-node-buffer)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/focus-cascade
                      (fixtures/focused-dispatch-id-for-five)]
                     [:rf.causa/causality-popover-open]
                     [:rf.causa/causality-popover-toggle-layout]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 3. ELK-unavailable fallback — list render with the
  ;; 'Causality graph unavailable' footer status hint. Uses the
  ;; testbed-local fallback forcer so the variant doesn't depend
  ;; on a real ELK load failure.
  (story/reg-variant :story.causa.causality-popover/fallback
    {:doc        "Popover open in ELK-unavailable fallback mode.
                 The view drops to a flat `<ul>` listing the
                 focused event + its ancestors + descendants;
                 footer surfaces 'Causality graph unavailable
                 (ELK.js failed to load)'. The testbed-local
                 forcer (`:panel-gallery.causality/force-elk-
                 fallback!`) pins the loader state to `:failed`
                 deterministically — no dependency on real ELK
                 load behaviour."
     :events     [[:panel-gallery.causality/force-elk-fallback!]
                  [:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/five-node-buffer)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/focus-cascade
                      (fixtures/focused-dispatch-id-for-five)]
                     [:rf.causa/causality-popover-open]]}]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. Deep — 20-node graph exercising scroll + per-level
  ;; breadth-cap truncation disclosure ('… N more children').
  (story/reg-variant :story.causa.causality-popover/deep
    {:doc        "Popover open over a 20-node graph (A → B → C
                 with 9 children + 9 siblings of B). Tests the
                 popover's scrolling behaviour and the per-level
                 breadth-cap (8) truncation disclosure ('… 1
                 more children' on the focused node's child
                 row). Focused on cascade C (200) at mid-chain."
     :events     [[:panel-gallery.chrome/seed!
                   {:trace-buffer (fixtures/twenty-node-buffer)
                    :selected-tab :event
                    :after-seeds
                    [[:rf.causa/focus-cascade
                      (fixtures/focused-dispatch-id-for-twenty)]
                     [:rf.causa/causality-popover-open]]}]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.causality-popover/all
    {:doc      "All four Causality popover variants. The chrome
                internally wraps :rf/causa via a hardcoded frame-
                provider, so workspace cells share interior state
                + the popover's ELK loader atom is process-global
                — see canvas-mode (sidebar pick) for per-variant
                fidelity. In particular, the `:fallback` variant
                pins the loader to `:failed` for the duration of
                its render; subsequent variants in the same
                workspace will also surface the fallback render
                unless re-pinned."
     :layout   :variants-grid
     :story    :story.causa.causality-popover
     :columns  1
     :tags     #{:dev}}))

(register-all!)
