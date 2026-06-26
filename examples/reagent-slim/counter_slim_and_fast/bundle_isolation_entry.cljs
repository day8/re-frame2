(ns counter-slim-and-fast.bundle-isolation-entry
  "Gate-owned entrypoint for the slim counter — NOT example/app practice.

   This namespace exists ONLY to drive the slim adapter's bundle-isolation
   gate. It is the `:init-fn` of the `:examples/counter-slim-and-fast` build
   (implementation/shadow-cljs.edn), so the build it inspects compiles the
   pure-CLJS SSR path the gate's non-vacuity contract (Contract 4) requires —
   while the teaching surface `counter-slim-and-fast.core` stays clean of any
   fixture plumbing.

   A reader studying the example should ignore this file and `run` in
   `counter-slim-and-fast.core` instead. The two halves of the gate machinery
   live together here and in `counter-slim-and-fast.bundle-isolation-fixture`;
   the contract narrative + sentinel methodology are owned by the gate at
   `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` and the
   slim adapter's IMPL-SPEC §1.4 + §1.8 + §8 — deliberately not duplicated.

   The boot is NOT re-copied here: this entry calls the shared
   `counter-slim-and-fast.core/boot!` (the single source of truth for the
   boot — install the slim adapter, then mount under a
   `frame-provider {:id …}` that creates + seeds the app frame in one spot),
   so the gate-owned path cannot drift from the teaching `core/run`. The one
   fixture concern is the `on-frame` pre-mount hook `boot!` accepts: the
   pure-CLJS `render-to-static-markup` exercise runs in a TRANSIENT frame
   scope `boot!` opens for it, before the client mount, so the static
   render's orphaned SSR subscription is torn down
   (fixture/prove-pure-cljs-ssr! clears the sub-cache) and the browser mount
   owns the only live `[:counter/value]` reaction."
  (:require [re-frame.views]
            [counter-slim-and-fast.core                     :as core]
            [counter-slim-and-fast.bundle-isolation-fixture :as fixture]))

(defn run []
  ;; Run the one canonical boot in `core/boot!`, passing the SSR fixture as
  ;; its `on-frame` pre-mount hook. `boot!` runs the hook in a transient
  ;; frame before the client mount; the fixture clears the sub-cache when it
  ;; finishes, so the browser mount owns the only live `[:counter/value]`
  ;; reaction. (Mechanics: `fixture/prove-pure-cljs-ssr!` and the docstring.)
  (core/boot! #(fixture/prove-pure-cljs-ssr! [core/counter-app])))
