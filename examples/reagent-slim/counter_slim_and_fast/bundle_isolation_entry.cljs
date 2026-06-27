(ns counter-slim-and-fast.bundle-isolation-entry
  "Gate-owned entrypoint for the slim counter — NOT example/app practice.

   This namespace exists only to drive the slim adapter's bundle-isolation
   gate. It is the `:init-fn` of the `:examples/counter-slim-and-fast` build
   (implementation/shadow-cljs.edn), so the build the gate inspects compiles
   the pure-CLJS SSR path. The teaching surface `counter-slim-and-fast.core`
   stays clean of fixture plumbing.

   A reader studying the example should ignore this file and read `run` in
   `counter-slim-and-fast.core` instead. The gate that consumes this
   entrypoint is `implementation/scripts/check-reagent-slim-bundle-isolation.cjs`,
   which owns the contract details.

   This entry calls the shared `counter-slim-and-fast.core/boot!` rather
   than re-copying the boot. The one fixture concern is the `on-frame`
   pre-mount hook `boot!` accepts: the pure-CLJS `render-to-static-markup`
   exercise runs in a transient frame `boot!` opens for it, before the
   client mount, so the static render's orphaned SSR subscription is torn
   down (`fixture/prove-pure-cljs-ssr!` clears the sub-cache) and the
   browser mount owns the only live `[:counter/value]` reaction."
  (:require [re-frame.views]
            [counter-slim-and-fast.core                     :as core]
            [counter-slim-and-fast.bundle-isolation-fixture :as fixture]))

(defn run []
  ;; Boot via `core/boot!`, passing the SSR fixture as its `on-frame`
  ;; pre-mount hook. See the ns docstring and `fixture/prove-pure-cljs-ssr!`
  ;; for what the hook does.
  (core/boot! #(fixture/prove-pure-cljs-ssr! [core/counter-app])))
