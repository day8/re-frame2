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

  ## The fallback, and why it is NOT dead code

  `:adapter/as-element` is published by the ratom family ALONE — there is
  no substrate-neutral React element, an element being correct only for
  the renderer that made it — and it is ROUTED, so it answers only while
  its own adapter is the installed one and returns nil otherwise.

  That nil is reachable, and the reachable case is a REAL one rather than
  a defensive flourish: Xray can own its own React root, and the
  acceptance harness mounts the Static surface through Fresco's root with
  **UIx** installed as the application adapter. The installed adapter is
  then not ratom-family, no walk answers, and the island still has to
  reach React — Fresco's codec passes an already-built React element
  through untouched, which is what made this work before rf2-7ds8 named
  the walk statically.

  So the door PREFERS the installed ratom walk and falls back to stock
  Reagent's, which is byte-for-byte the behaviour every one of these
  crossings had before. The fallback cannot mask the defect it was
  written for: under reagent-slim the routed hook answers, so the
  fallback is never reached, and `w1-as-element-door-is-the-installed-
  builds-walk` pins that it is reagent2's walk that answers rather than
  this one.

  AND THE FALLBACK IS NOW REACHABLE IN PRODUCTION, not only under the
  acceptance harness. This paragraph used to end the other way: `mount.cljs`
  refused the element-shaped substrates for the public `open!`, so a
  production Xray only ever mounted on a ratom-family adapter and only ever
  took the first arm. rf2-k97c.3 gave Xray its own React root and
  rf2-k97c.4 retired that refusal, so a host on UIx or Fresco now gets a
  mounted Xray — the installed adapter publishes no `:adapter/as-element`,
  the routed hook answers nil, and the SECOND arm carries every surviving
  Reagent island. That is the case the section above describes and the
  acceptance harness already measures; what changed is only that a real
  host can be in it."
  (:require [reagent.core       :as stock]
            [re-frame.late-bind :as rf.late-bind]))

(defn as-element
  "Convert `hiccup` to a React element through the INSTALLED substrate
  adapter's own walk (`:adapter/as-element`).

  This is the `as-child` spelling every Xray Fresco boundary passes for
  its surviving Reagent islands. Pass `identity` instead from a plain
  hiccup caller or the node lane, where the island stays a vector.

  Falls back to stock Reagent's walk when no ratom-family adapter is
  installed to answer — see the namespace docstring for why that case is
  real and why the fallback cannot mask the defect."
  [hiccup]
  (let [walk     (rf.late-bind/get-fn-cached :adapter/as-element)
        installed (when walk (walk hiccup))]
    (if (some? installed)
      installed
      (stock/as-element hiccup))))
