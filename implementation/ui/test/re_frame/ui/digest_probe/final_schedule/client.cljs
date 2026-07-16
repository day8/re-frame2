(ns re-frame.ui.digest-probe.final-schedule.client
  "The UI-client entry that pulls the compiler-owned digest carrier and both
  fixture views into ONE real Shadow build graph, so `ui-client-build?` holds and
  re-frame.ui projects a whole-build digest across A's and B's accepted rows."
  (:require [re-frame.ui.client]
            [re-frame.ui.digest-carrier]
            [re-frame.ui.digest-probe.final-schedule.app-a]
            [re-frame.ui.digest-probe.final-schedule.app-b]
            [re-frame.ui.digest-probe.final-schedule.trigger]))
