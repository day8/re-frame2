(ns hicasso-counter.events
  "Model for the hicasso story testbed — plain re-frame2, with nothing
  substrate-specific in it.

  This is the point of the file being separate. A `h/defview` boundary
  reads subscriptions and dispatches events through exactly the same
  registrar every Reagent view uses, so the model an author writes for a
  Hicasso app is the model they would have written anyway. Nothing here
  names Hicasso, Story, or a substrate.

  The tally is deliberately small — a count and the step it moves by —
  because the claim under test is that a NATIVE-SUBSTRATE app can be
  storied, not that Hicasso can express something elaborate. A bigger app
  would put more between a red gate and its cause."
  (:require [re-frame.core :as rf]))

(rf/reg-event :hicasso-counter/initialise
  {:doc "Seed the tally. Idempotent, so a play script that re-runs — the
        Story shell auto-runs on a deep link AND on a watcher edge —
        reaches the same terminal state either way."}
  (fn [{:keys [db]} [_ n]]
    {:db (assoc db :count (or n 0) :step (or (:step db) 1))}))

(rf/reg-event :hicasso-counter/bump
  {:doc "Advance the tally by the current step. The one interaction the
        live page and the play script both drive."}
  (fn [{:keys [db]} _]
    {:db (update db :count + (or (:step db) 1))}))

(rf/reg-event :hicasso-counter/set-step
  {:doc "Set the step size. Reads its value off the field through the
        `::h/value` marker at the call site, so the event handler takes
        an ordinary string and coerces it here rather than in the view."}
  (fn [{:keys [db]} [_ typed]]
    {:db (assoc db :step (if (re-matches #"-?[0-9]+" (str typed))
                           (js/parseInt typed 10)
                           (:step db)))}))
