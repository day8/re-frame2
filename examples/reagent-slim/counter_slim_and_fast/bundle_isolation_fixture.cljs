(ns counter-slim-and-fast.bundle-isolation-fixture
  "Bundle-isolation fixture for the slim counter — NOT example/app practice.

   This namespace exists only to make the slim adapter's pure-CLJS-SSR
   bundle-isolation contract provable on the advanced bundle. A reader
   studying the example should ignore it; the teaching surface is
   `counter-slim-and-fast.core`. This is the example-side half of an
   adapter-owned CI gate.

   The gate that consumes this fixture is
   `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` (run
   via `npm run test:reagent-slim:bundle-isolation`). That script owns the
   contract details.

   What this fixture contributes: it exercises slim's pure-CLJS
   `render-to-static-markup` at boot so the SSR path is actually compiled
   into the bundle. That makes the gate's check that `react-dom/server` is
   absent a real proof — without an exercised SSR path, the absence check
   would pass against a bundle that does no SSR at all. The host-global
   write below keeps the exercise alive under `:advanced`. For the two HTML
   paths slim ships, see docs/guide/how-to/use-uix-helix-or-slim.md."
  (:require [reagent2.dom.server :as rds]
            [re-frame.core       :as rf]))

(defn prove-pure-cljs-ssr!
  "Run slim's pure-CLJS SSR path so the bundle-isolation gate has signal.
   `hiccup` is the root example view in hiccup form (e.g. `[counter-app]`).

   Mechanics (all fixture concerns, none of which the example needs):

     - Keeping it alive under `:advanced`: the rendered markup is written
       onto the `counterSlimPrerender` host-global. The closure compiler
       treats writes to extern-shaped `globalThis` properties as side
       effects, so the `render-to-static-markup` call is not eliminated as
       dead code. The gate greps for this host-global plus a literal owned
       by the `reagent2.dom.server` serializer.

     - Sub-cache teardown: `render-to-static-markup` is the pure-CLJS
       static walker — it calls the view as a plain fn and walks the
       resulting hiccup, mounting no Reagent component lifecycle. The view
       derefs a subscription, so the static render builds and caches that
       reaction in the frame's sub-cache, and nothing auto-unsubscribes it
       (there is no component to unmount). The `try`/`finally` disposes the
       orphaned subscription so the client mount owns the only live
       reaction; `finally` guarantees teardown even if the render throws."
  [hiccup]
  (try
    (set! (.-counterSlimPrerender js/globalThis)
          (rds/render-to-static-markup hiccup))
    (finally
      (rf/clear-sub-cache!))))
