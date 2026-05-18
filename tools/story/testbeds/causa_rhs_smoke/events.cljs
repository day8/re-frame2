(ns causa-rhs-smoke.events
  "Trivial counter handlers for the causa-rhs-smoke testbed (rf2-drprn).

  Three handlers are enough to drive every Causa-side scenario in the
  companion `spec.cjs`:

    :counter/initialise  → seed `:count` slot
    :counter/inc         → dispatch surface for Trace / L2 list
                          / Event-detail / mode-pill scenarios"
  (:require [re-frame.core :as rf]))

(rf/reg-event-db :counter/initialise
  (fn [_db [_ n]] {:count (or n 0)}))

(rf/reg-event-db :counter/inc
  (fn [db _ev] (update db :count inc)))
