(ns day8.re-frame2-xray.panels.reactive-panel
  "View panel facade (rf2-e33ad · Mike-direction 2026-05-21 ·
  prior name: Reactive · prior bead: rf2-wyvf2 · spec/021 §3).

  Per Mike's 2026-05-21 design direction the tab is renamed from
  `Reactive` to `View` — the panel's primary subject is the rendered
  view (the operator hovers a view-row, the rendered DOM highlights)
  while the sub event-bundle is the supporting context.

  The internal panel-registry key stays `:views` (it was always the
  internal id, never a user contract). Per rf2-8ve8z (phase-B of the
  Views-tab redesign) the panel renders the reactive event-bundle as THREE
  STACKED TABLES, top→bottom mirroring the event-bundle flowing toward the
  UI:

      Level 1 subs (observe app-db)   name | changed | code — the subs
                                      that read app-db directly
                                      (`:inputs []` per sub-topology)
      Level 2+ subs                   name | changed | inputs | code —
                                      composed subs; inputs lists the
                                      input-sub names one per line
      Views:                          name | action | reason — one row
                                      per view render/unmount. `name` is
                                      hoverable (pink DOM highlight,
                                      rf2-8l03l); `action` is mount /
                                      rerender / unmount; `reason` is the
                                      changed subs THIS view reads, or
                                      `← parent re-render` (structural,
                                      UNNAMED).

  The Level 1 / Level 2+ split + the inputs/code columns come from
  `re-frame.subs.tooling/sub-topology`; the view action + reason ride
  phase-A's (rf2-9hoos) `:mount?` / `:deref-subs` fields on
  `:rf.view/rendered` + the new `:rf.view/unmounted` op.

  ## Public surface

  - `Panel`    — the canonical embed (per
                 `tools/xray/spec/008-Embedding-Contract.md`);
                 `reg-view`-registered so the React-context frame tier
                 carries the enclosing `:rf/xray` through to
                 descendant subscribes.
  - `install!` — idempotent install for `:rf.xray/reactive-data` +
                 the panel-local toggle + the L4 tab registration."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.reactive-panel-events :as events]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as subs]
            [day8.re-frame2-xray.panels.reactive-panel-view :as view]))

(rf/reg-view Panel
  "The Reactive panel's root view. Plain function-call delegation to
  the body so the React-context frame tier resolves through the
  facade reg-view wrapper to leaf subscribes."
  []
  (view/reactive-panel))

(defn install!
  "Idempotent install for the Reactive panel's Xray-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-wyvf2 — register with the L4 tab registry. Display label is
  ;; 'Views' per spec/021 §11.5 (rf2-5i8nn); the tab key stays `:views`
  ;; (the internal id is not a user contract — pre-alpha posture
  ;; preserves the slot for the smaller diff).
  (panel-registry/reg-l4-tab!
    {:id    :views
     ;; Display label renamed `Reactive` -> `View` -> `Views`. The
     ;; all-plural-domain-noun convention (Mike-direction 2026-05-21)
     ;; aligns the tab vocabulary across both tab sets — Views / Flows /
     ;; Schemas / Routes / Machines are all plural. Internal id stays
     ;; `:views`.
     :label "Views"
     :mnem  "v"
     :modes #{:dynamic}
     :order 2
     :panel Panel})
  nil)
