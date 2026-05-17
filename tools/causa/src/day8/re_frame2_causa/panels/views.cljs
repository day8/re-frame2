(ns day8.re-frame2-causa.panels.views
  "Views panel facade (rf2-21ob3).

  Replaces the legacy Subscriptions panel
  (`day8.re-frame2-causa.panels.subscriptions`). Per
  `tools/causa/spec/012-Views.md`: subscriptions are no longer a
  top-level tab — they nest under the views that consumed them. The
  view is the natural unit developers reason about; subs hang
  beneath each view row with their return values visible inline.

  ## Public surface

  - `Panel`         — the canonical embed (per
                       `tools/causa/spec/008-Embedding-Contract.md`).
                       `reg-view`-registered so the React-context
                       frame tier carries the enclosing `:rf/causa`
                       through to descendant subscribes.
  - `install!`      — idempotent install for the panel's
                       `:rf.causa/*` sub + event registrations.

  ## Pure hiccup

  The view body lives in `views_view.cljs`; this facade invokes the
  leaf as a plain function call (not a Reagent component vector) so
  the leaf's subscribes stay inlined into the facade's reg-view
  wrapper render — the React-context tier resolves to `:rf/causa`
  inside the body. See `tools/causa/spec/Conventions.md` §View body
  delegation and the rf2-043uz lesson documented in the legacy
  `subscriptions.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.views-events :as events]
            [day8.re-frame2-causa.panels.views-subs :as subs]
            [day8.re-frame2-causa.panels.views-view :as view]))

(rf/reg-view Panel
  "The Views panel's root view. Plain function-call delegation to the
  body per the rf2-043uz lesson (frame-context tier resolves through
  the facade reg-view wrapper to leaf subscribes)."
  []
  (view/views-panel))

(defn install!
  "Idempotent install for the Views panel's Causa-side registrations.
  Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  nil)
