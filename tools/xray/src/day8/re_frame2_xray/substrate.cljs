(ns day8.re-frame2-xray.substrate
  "Xray's door to the INSTALLED substrate adapter's hiccup → React element
  walk (rf2-7ds8).

  ## Why this exists

  Xray's shell is hiccup, and several of its views are now FRESCO
  BOUNDARIES. A boundary body may hand a surviving `rf/reg-view` island
  down to React as a child, and Fresco's component ABI wants a React
  ELEMENT there rather than a fn-headed hiccup vector. Producing that
  element is a per-substrate act, and the boundaries take the spelling as
  an `as-child` PARAMETER precisely so it can vary: a plain hiccup caller
  (and the node lane) passes `identity`, leaving the island the vector it
  has always been; a boundary passes this fn.

  Until rf2-7ds8 the boundaries passed `reagent.core/as-element` — STOCK
  Reagent's walk, named statically. Under the **reagent-slim** adapter
  that is the wrong runtime, and the way it is wrong is quiet:

    * React mounts the element perfectly well, and the `:contextType` the
      `reg-view` head carries is honoured, so the frame keyword genuinely
      reaches the mounted class.
    * But the subtree is rendered by STOCK Reagent, which binds STOCK
      Reagent's in-flight-component slot — while `:adapter/current-
      component` routes to the INSTALLED adapter, i.e. `reagent2`, and so
      answers nil inside that subtree.
    * `re-frame.views.provider/current-frame` reads `(.-context cmp)` off
      that component. With no component it returns nil, and every ambient
      `subscribe` / `dispatch` beneath raises
      `:rf.error/no-frame-context`.
    * The raise happens in React's render phase, so React unmounts the
      subtree. The developer gets a BLANK Xray, not a diagnostic.

  Reading the walk off the installed adapter removes the whole class: the
  island is rendered by the same build whose in-flight component the frame
  resolver consults, whichever ratom-family adapter the host installed.

  ## Why it fails loud rather than returning nil

  There is no substrate-neutral React element — an element is only correct
  for the renderer that made it — so `:adapter/as-element` is published by
  the ratom family ALONE and is deliberately routed with no chain-bottom
  fallback. A nil-returning door would hand React a nil child, paint
  nothing, and look exactly like the defect this namespace exists to
  remove. `mount.cljs` already refuses the element-shaped substrates
  before any of this runs, so reaching here with no walk published is a
  wiring fault and is reported as one."
  (:require [re-frame.error     :as rf.error]
            [re-frame.late-bind :as rf.late-bind]))

(defn as-element
  "Convert `hiccup` to a React element through the INSTALLED substrate
  adapter's own walk (`:adapter/as-element`).

  This is the `as-child` spelling every Xray Fresco boundary passes for
  its surviving Reagent islands. Pass `identity` instead from a plain
  hiccup caller or the node lane, where the island stays a vector.

  Raises `:rf.error/no-substrate-as-element` when the installed adapter
  publishes no walk — see the namespace docstring for why that is not a
  nil return."
  [hiccup]
  (if-let [walk (rf.late-bind/get-fn-cached :adapter/as-element)]
    (walk hiccup)
    (rf.error/throw-error!
      :rf.error/no-substrate-as-element
      'day8.re-frame2-xray.substrate/as-element
      (str "The installed substrate adapter publishes no hiccup → React "
           "element walk (:adapter/as-element), so Xray cannot cross a "
           "Reagent island into a Fresco boundary. The ratom-family "
           "adapters (Reagent / reagent-slim) publish one; install one of "
           "those via (rf/init! ...) before opening Xray.")
      {:recovery :install-ratom-adapter})))
