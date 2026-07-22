(ns re-frame.ui.digest-probe.warm-watch.client
  "The UI-client entry that pulls every fixture source into ONE real Shadow dev
  build graph, so re-frame.ui harvests the accepted custom-element manifest and
  view aggregate.

  The runner rewrites the `:require` list below to drop `leaf` (on the RENAME and
  DELETE passes) so the removed/renamed saved source really leaves Shadow's
  authoritative `:build-sources`; every other pass leaves this file untouched.

  `main` is the `:node-script` entry (rf2-4vm19): the runner spawns the emitted
  runtime as a live node child connected to the watch's devtools server, so real
  hot reloads drive the runtime custom-element ledger end-to-end (no DOM). It
  installs the publish watch, writes the `boot` record — the proof this bundle's
  `current-build-id` resolves the REAL dev build id at the runtime seam — and
  keeps the event loop alive across devtools reconnects."
  (:require [re-frame.ui.client]
            ;; warm-watch-client-requires:start
            [re-frame.ui.digest-probe.warm-watch.card]
            [re-frame.ui.digest-probe.warm-watch.view]
            [re-frame.ui.digest-probe.warm-watch.leaf]
            ;; warm-watch-client-requires:end
            [re-frame.ui.digest-probe.warm-watch.trigger]
            [re-frame.ui.digest-probe.warm-watch.observe :as observe]))

(defn main
  "Node-script entry: wire the runtime observer, then stay alive. The devtools
  websocket keeps the loop busy while connected; the explicit interval keeps
  the process alive across a daemon restart's reconnect window (the S6
  warm-restart arm stops one watch daemon and starts another)."
  [& _]
  (observe/install!)
  (observe/record! "boot" {})
  (js/setInterval (fn keep-alive []) 2147483647))
