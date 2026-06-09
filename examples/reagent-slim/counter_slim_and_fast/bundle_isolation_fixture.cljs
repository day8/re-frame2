(ns counter-slim-and-fast.bundle-isolation-fixture
  "Bundle-isolation fixture for the slim counter — NOT example/app practice.

   This namespace exists only to make the slim adapter's pure-CLJS-SSR
   bundle-isolation contract (S3-005) provable on the advanced bundle. A
   reader studying the example should ignore it; the teaching surface is
   `counter-slim-and-fast.core`. The plumbing here is the example-side
   half of an adapter-owned CI gate.

   The gate that consumes this fixture is
   `implementation/scripts/check-reagent-slim-bundle-isolation.cjs`
   (the `cljs-reagent-slim-bundle-isolation` CI job, run via
   `npm run test:reagent-slim:bundle-isolation`). That script is the
   single source of truth for the sentinel methodology and the four
   contracts; the contract narrative lives there and in the slim
   adapter's IMPL-SPEC §1.4 + §1.8 + §8 — it is deliberately not
   duplicated into the example source.

   What this fixture contributes to the gate: it exercises the slim's
   pure-CLJS `render-to-static-markup` at boot so the SSR path is
   actually compiled into the bundle. That makes Contract 3
   (`react-dom/server` is absent) a NON-vacuous proof — without an
   exercised SSR path, the absence check would pass against a bundle
   that does no SSR at all. The host-global write below is the DCE
   anchor that keeps the exercise alive under `:advanced`."
  (:require [reagent2.dom.server :as rds]
            [re-frame.core       :as rf]))

(defn prove-pure-cljs-ssr!
  "Compile-and-run the slim's pure-CLJS SSR path so the bundle-isolation
   gate's non-vacuity contract has signal. `hiccup` is the root example
   view in hiccup form (e.g. `[counter-app]`).

   Mechanics (all fixture concerns, none of which the example needs):

     - DCE anchor: the rendered markup is written onto the
       `counterSlimPrerender` host-global. The closure compiler treats
       writes to extern-shaped `globalThis` properties as side effects,
       so the `render-to-static-markup` call survives `:advanced` (a no-op
       like `js/console.log` would be elided). The gate greps for this
       host-global plus a `reagent2.dom.server` serializer-owned literal.

     - Sub-cache teardown: `render-to-static-markup` is the pure-CLJS
       static walker — it invokes the view as a plain fn and walks the
       resulting hiccup, mounting NO Reagent component lifecycle. The view
       derefs a subscription, so the static render builds and caches that
       reaction in the frame's sub-cache with ref-count 1, and nothing
       auto-unsubscribes it (there is no component to unmount). Left as-is
       this fixture would leak a headless reaction into the live runtime.
       The `try`/`finally` disposes the orphaned SSR subscription so the
       client mount owns the only live reaction; `finally` guarantees
       teardown even if the static render throws."
  [hiccup]
  (try
    (set! (.-counterSlimPrerender js/globalThis)
          (rds/render-to-static-markup hiccup))
    (finally
      (rf/clear-sub-cache!))))
