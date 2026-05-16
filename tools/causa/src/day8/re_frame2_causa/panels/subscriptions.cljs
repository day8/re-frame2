(ns day8.re-frame2-causa.panels.subscriptions
  "Subscriptions panel shell.

  Follows the canonical panel facade pattern documented in
  `tools/causa/spec/Conventions.md` — facade owns the public
  `reg-view`; the body delegates to a plain Reagent fn in
  `subscriptions-views` because the body is too large to inline
  cohesively."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.subscriptions-events
             :as events]
            [day8.re-frame2-causa.panels.subscriptions-subs :as subs]
            [day8.re-frame2-causa.panels.subscriptions-views
             :as views]))

(rf/reg-view subscriptions-view
  "The Subscriptions panel's root view.

  The body invokes the leaf as a plain function call —
  `(views/subscriptions-panel)`, not the Reagent component vector
  `[views/subscriptions-panel]`. That keeps the leaf body inlined into
  this reg-view wrapper's render, so the leaf's `subscribe` /
  `dispatch` calls reach `:rf/causa` through the wrapper's React-
  context tier. The vector form would mount the leaf as a plain
  Reagent fn component that drops out of the surrounding frame
  (Spec 004 §Plain Reagent fns / Spec 006 §706); see
  `tools/causa/spec/Conventions.md` §View body delegation."
  []
  (views/subscriptions-panel))

(defn install!
  "Idempotent install for the Subscriptions panel's Causa-side
  registrations. Returns nil per the facade convention."
  []
  (subs/install!)
  (events/install!)
  nil)
