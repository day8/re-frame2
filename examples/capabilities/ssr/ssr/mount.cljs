(ns ssr.mount
  "The client mount step of the SSR startup recipe, factored into a single
  registration-free helper so a test can drive the EXACT adopt-vs-fresh decision
  `ssr.core/run` ships — not a hand-kept copy of it.

  `mount!` is the payload-vs-client-only decision, and this is the one place it
  lives. `ssr.core/run` calls it after it has READ and HYDRATED state via
  `re-frame.ssr/hydrate!`; the browser DOM-adoption regression
  (`re-frame.ssr.ssr-startup-recipe-dom-cljs-test`) calls the SAME helper, so a
  regression here — dropping the `:hydrate?` option, say — turns that proof red.

  Why its own namespace, and not a private `defn-` inside `ssr.core`: the
  regression reaches the helper WITHOUT `:require`-ing `ssr.core`, so it
  loads the one decision it proves and none of the app. This namespace
  registers nothing, so no example ids enter the test bundles with it."
  (:require [re-frame.adapter.reagent :as rf.adapter.reagent]))

(defn mount!
  "Mount `tree` into the container `el` through the adapter's client-root
  `handle`, adopting the server-rendered DOM when a hydration `payload` is
  present.

  - payload present ⇒ the first render HYDRATES: React reconciles against the
    server markup (same nodes, listeners attached, no re-paint). A fresh mount
    here would throw the server HTML away — the adopt-vs-replace bug this
    option exists to avoid.
  - payload absent ⇒ a fresh root + render.

  Either way the adapter creates the root exactly once, and every later
  `render!` through the same handle (the `^:dev/after-load` hook) updates it.
  Returns nil; does nothing when `el` is absent (no container to mount into)."
  [handle el tree payload]
  (when el
    (rf.adapter.reagent/render! handle tree el {:hydrate? (some? payload)})))
