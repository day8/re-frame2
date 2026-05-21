(ns day8.re-frame2-causa.panels.reactive-panel
  "View panel facade (rf2-e33ad · Mike-direction 2026-05-21 ·
  prior name: Reactive · prior bead: rf2-wyvf2 · spec/021 §3).

  Per Mike's 2026-05-21 design direction the tab is renamed from
  `Reactive` to `View` — the panel's primary subject is the rendered
  view (the operator hovers a view-row, the rendered DOM highlights)
  while the sub cascade is the supporting context.

  The internal panel-registry key stays `:views` (it was always the
  internal id, never a user contract). The panel renders the full
  reactive cascade per spec/021 §3 reorganised into four sections:

      Subs ran (count)            entries with [code] chip
      Subs whose value changed    entries
      Subs that cascaded          entries
      Views re-rendered           entries named via `reg-view :name`,
                                  `[code] chip` + hover-highlight

  ## Public surface

  - `Panel`    — the canonical embed (per
                 `tools/causa/spec/008-Embedding-Contract.md`);
                 `reg-view`-registered so the React-context frame tier
                 carries the enclosing `:rf/causa` through to
                 descendant subscribes.
  - `install!` — idempotent install for `:rf.causa/reactive-data` +
                 the panel-local toggle + the L4 tab registration."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panel-registry :as panel-registry]
            [day8.re-frame2-causa.panels.reactive-panel-events :as events]
            [day8.re-frame2-causa.panels.reactive-panel-subs :as subs]
            [day8.re-frame2-causa.panels.reactive-panel-view :as view]))

(rf/reg-view Panel
  "The Reactive panel's root view. Plain function-call delegation to
  the body so the React-context frame tier resolves through the
  facade reg-view wrapper to leaf subscribes."
  []
  (view/reactive-panel))

(defn install!
  "Idempotent install for the Reactive panel's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  ;; rf2-wyvf2 — register with the L4 tab registry. Display label is
  ;; 'Reactive' per spec/021 §11.5; the tab key stays `:views` (the
  ;; internal id is not a user contract — pre-alpha posture preserves
  ;; the slot for the smaller diff).
  (panel-registry/reg-l4-tab!
    {:id    :views
     ;; rf2-e33ad — display label renamed from `Reactive` to `View`
     ;; per Mike-direction 2026-05-21. Internal id stays `:views`.
     :label "View"
     :mnem  "v"
     :modes #{:dynamic}
     :order 2
     :panel Panel})
  nil)
