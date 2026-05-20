(ns day8.re-frame2-causa.panels.reactive-panel
  "Reactive panel facade (rf2-wyvf2 · spec/021 §3).

  Per spec/021 §11.5 the L4 tab displays as `Reactive` (renamed from
  `Views`) while the panel-registry key stays `:views` (internal id;
  not a user contract). The panel renders the canonical sub-cascade
  + view-re-render visualisation per §3.

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
     :label "Reactive"
     :mnem  "v"
     :modes #{:runtime}
     :order 2
     :panel Panel})
  nil)
