(ns reagent-slim.counter.bundle-isolation-entry
  "Gate plumbing for the slim counter — NOT something to copy into an app.

   If you're here to learn the example, you're in the wrong file: read `run`
   in `reagent-slim.counter.core` instead. This namespace exists for one
   reason, and it's a CI reason. It's the `:init-fn` of the
   `:examples/counter-slim-and-fast` build (implementation/shadow-cljs.edn),
   which means the build the bundle-isolation gate inspects compiles in the
   pure-CLJS SSR path. Keeping that here lets the teaching file stay clean.
   The gate itself —
   `implementation/scripts/check-reagent-slim-bundle-isolation.cjs` — owns
   the real contract.

   It boots through the shared `reagent-slim.counter.core/boot!` rather
   than copy-pasting the boot. The only fixture-specific move is the
   `on-frame` pre-mount hook `boot!` accepts: `boot!` opens a transient
   frame, runs the pure-CLJS `render-to-static-markup` exercise in it before
   the client mounts, then tears it down — `fixture/prove-pure-cljs-ssr!`
   clears the sub-cache — so the browser mount ends up owning the one live
   `[:counter/value]` reaction."
  (:require [re-frame.views]
            [reagent-slim.counter.core                     :as core]
            [reagent-slim.counter.bundle-isolation-fixture :as fixture]))

(defn run []
  ;; Same `core/boot!` the example uses, with the SSR fixture handed in as
  ;; the `on-frame` pre-mount hook. The ns docstring and
  ;; `fixture/prove-pure-cljs-ssr!` explain what that hook is for.
  (core/boot! #(fixture/prove-pure-cljs-ssr! [core/counter-app])))
