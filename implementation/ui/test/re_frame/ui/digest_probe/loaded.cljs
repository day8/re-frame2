(ns re-frame.ui.digest-probe.loaded
  "A LOADED application source in the base module (required by base.cljs, so it
  executes on the page — unlike the lazy module). The runtime-activation probe
  (counterexample 2, rf2-vxgfnd.193) rewrites the two markers below in one warm
  Shadow watch: the view text moves the whole-build digest, and the inert
  top-level guard is flipped so the slot throws BEFORE this view re-registers.
  A compile-valid edit that throws at top-level during hot reload must leave
  digest/descriptor reads fail-closed — the carrier may only stage the
  candidate, never publish it before after-load.

  The two rewritten literals are kept out of THIS docstring so the fixture's
  exactly-one-occurrence replace stays unambiguous."
  (:require [re-frame.ui :as ui]))

;; Injectable top-level slot: inert as checked in, a top-level throw once the
;; probe flips the guard. Placed BEFORE the registration so a thrown reload
;; stops before `loaded-view` re-registers.
(when false
  (throw (js/Error. "rf2 counterexample-2 top-level throw (before registration)")))

(ui/defview loaded-view []
  [:section {:data-probe "loaded"} "loaded-probe-v1"])

(set! (.-__rf2LoadedExecuted js/globalThis) true)
