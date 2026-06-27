(ns reagent-slim.counter.bundle-isolation-fixture
  "Bundle-isolation fixture for the slim counter — NOT something to copy.

   Another file to read past if you're learning the example; the teaching
   surface is `reagent-slim.counter.core`. This is the example-side half
   of an adapter-owned CI gate, and it has exactly one job: make the slim
   adapter's pure-CLJS-SSR isolation contract provable on the advanced
   bundle. The other half — the script that judges the result — is
   `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` (run via
   `npm run test:reagent-slim:bundle-isolation`), and it owns the contract.

   So what does this half actually do? It runs slim's pure-CLJS
   `render-to-static-markup` at boot, which forces the SSR path to be
   compiled into the bundle. That's the bit that gives the gate teeth: its
   real claim is 'react-dom/server is absent', and that's only meaningful if
   an SSR path is present to be checked against — otherwise you'd be proving
   the absence of server rendering in a bundle that never renders on the
   server anyway. The host-global write below keeps the exercise from being
   optimised away under `:advanced`. For the two HTML paths slim ships, see
   docs/guide/how-to/use-uix-helix-or-slim.md."
  (:require [reagent2.dom.server :as rds]
            [re-frame.core       :as rf]))

(defn prove-pure-cljs-ssr!
  "Run slim's pure-CLJS SSR path once, so the bundle-isolation gate has
   something real to inspect. `hiccup` is the root example view in hiccup
   form, e.g. `[counter-app]`.

   Two mechanics are at work, both pure fixture concerns the example itself
   never has to think about:

     - Surviving `:advanced`. We write the rendered markup onto the
       `counterSlimPrerender` host-global. The closure compiler can't prove
       that a write to an extern-shaped `globalThis` property is harmless,
       so it treats it as a side effect and leaves the
       `render-to-static-markup` call standing instead of pruning it as dead
       code. The gate then greps for that host-global plus a literal only
       the `reagent2.dom.server` serializer would emit.

     - Cleaning up after ourselves. `render-to-static-markup` is slim's
       pure-CLJS static walker: it calls the view as a plain function and
       walks the hiccup it returns, with no Reagent component lifecycle in
       sight. Our view derefs a subscription, so that walk builds and caches
       a reaction in the frame's sub-cache — and because no component ever
       mounted, nothing will ever unmount to dispose it. So we do it by
       hand. The `try`/`finally` disposes that orphaned subscription, which
       leaves the client mount owning the one live reaction; `finally` makes
       sure the teardown happens even if the render blows up partway."
  [hiccup]
  (try
    (set! (.-counterSlimPrerender js/globalThis)
          (rds/render-to-static-markup hiccup))
    (finally
      (rf/clear-sub-cache!))))
