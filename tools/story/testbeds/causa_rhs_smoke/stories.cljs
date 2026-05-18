(ns causa-rhs-smoke.stories
  "Single story + single variant for the causa-rhs-smoke testbed
  (rf2-drprn).

  The browser-scenario suite needs ONE pickable variant to drive the
  Causa-as-RHS regression coverage. No tags, no modes, no decorators —
  the testbed is deliberately under-featured so the Story / Causa
  collision surfaces that the broader counter testbed exposes (Ctrl+K
  command-palette double-binding, schema-panel text overlap with the
  Causa frame picker) stay out of the picture.

  The single variant fires `:counter/initialise 5` so the canvas has a
  known starting value when the regression scenarios start clicking
  `[data-test=\"inc\"]`."
  (:require [re-frame.story :as story]
            [causa-rhs-smoke.events]
            [causa-rhs-smoke.views]))

(defn register-all!
  "Register the testbed's Story artefacts. Idempotent."
  []
  (story/install-canonical-vocabulary!)

  (story/reg-story :story.counter
    {:doc        "Single-variant counter parent for the Causa-as-RHS
                 regression scenarios."
     :component  :causa-rhs-smoke.views/counter-card
     :substrates #{:reagent}})

  (story/reg-variant :story.counter/loaded
    {:doc    "Counter seeded at 5; clicking `[data-test=\"inc\"]`
             dispatches `:counter/inc` on the variant frame, which
             flows through to the embedded Causa shell."
     :events [[:counter/initialise 5]]
     :substrates #{:reagent}}))

(register-all!)
