(ns re-frame.ui.digest-probe.warm-watch.client
  "The UI-client entry that pulls every fixture source into ONE real Shadow dev
  build graph, so `ui-client-build?` holds and re-frame.ui harvests the accepted
  custom-element manifest and view aggregate.

  The runner rewrites the `:require` list below to drop `leaf` (on the RENAME and
  DELETE passes) so the removed/renamed saved source really leaves Shadow's
  authoritative `:build-sources`; every other pass leaves this file untouched."
  (:require [re-frame.ui.client]
            ;; warm-watch-client-requires:start
            [re-frame.ui.digest-probe.warm-watch.card]
            [re-frame.ui.digest-probe.warm-watch.view]
            [re-frame.ui.digest-probe.warm-watch.leaf]
            ;; warm-watch-client-requires:end
            [re-frame.ui.digest-probe.warm-watch.trigger]))
