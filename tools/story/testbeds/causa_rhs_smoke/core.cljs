(ns causa-rhs-smoke.core
  "Entry point for the causa-rhs-smoke testbed (rf2-drprn).

  This testbed is a minimal Story shell with a single variant + the
  Causa preload, used by the companion `spec.cjs` to assert that the
  Story RHS's `[data-rf-causa-host]` slot (rf2-sgdd3) is mounted by
  the embedded Causa shell and that variant interactions surface in
  Causa's lenses (Trace tab counts, L2 event list, L1 nav focus
  stepping, mode pill LIVE/RETRO).

  No hash routing — page-load mounts the Story shell directly. No
  modes / tags / a11y / schema-validation / command-palette wiring —
  every feature beyond the four regression scenarios is deliberately
  omitted to keep the testbed small and the Story/Causa-collision
  surface absent (the broader counter-with-stories testbed has the
  full Story chrome and does NOT preload Causa for that reason)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core      :as rf]
            [re-frame.story     :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-causa.config :as causa-config]
            [causa-rhs-smoke.stories]))

(defn ^:export run []
  ;; Disable Causa's standalone auto-open — the Story shell drives
  ;; `mount/open!` itself via `causa-preset/ensure-causa-mounted!`
  ;; on the selection-watcher edge. Keep the preload's keybinding
  ;; + trace collectors live (they're needed for the embedded shell
  ;; to receive trace bus events).
  (causa-config/configure! {:launch/auto-open? false})
  (rf/init! reagent-adapter/adapter)
  (story/configure! {:global-args {}})
  (story/mount-shell! (js/document.getElementById "app")))
