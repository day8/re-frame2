(ns ssr.mount
  "The client mount step of the SSR startup recipe, factored into a single
  registration-free helper so a test can drive the EXACT adopt-vs-fresh React
  root branch `ssr.core/run` ships — not a hand-kept copy of it.

  `mount!` is the payload-vs-client-only decision, and this is the one place it
  lives. `ssr.core/run` calls it after it has READ+HYDRATE+VERIFIED state via
  `re-frame.ssr/hydrate!`; the browser DOM-adoption regression
  (`re-frame.ssr.ssr-startup-recipe-dom-cljs-test`) calls the SAME helper, so a
  regression here — swapping `hydrate-root` for `create-root` — turns that proof
  red.

  Why its own namespace, and not a private `defn-` inside `ssr.core`: the
  regression has to reach the helper WITHOUT `:require`-ing `ssr.core`, whose app
  registrations (`:auth.session/store`, `:articles/loaded`, …) collide at image
  assembly with the login + realworld examples already sharing the consolidated
  test bundle (`:rf.error/image-duplicate-id`). This namespace registers nothing,
  so a test loads only the mount logic and no example ids enter the bundle."
  (:require [reagent.dom.client :as rdc]))

(defn mount!
  "Mount `tree` into the container `el`, adopting the server-rendered DOM when a
  hydration `payload` is present.

  - payload present ⇒ `hydrate-root`: React reconciles against the server markup
    (same nodes, listeners attached, no re-paint) and the retained root is
    returned. `create-root` + `render` here would throw the server HTML away and
    mount fresh — the adopt-vs-replace bug this branch exists to avoid.
  - payload absent ⇒ a fresh `create-root` + `render`.

  Returns the retained React root, or nil when `el` is absent (no container to
  mount into)."
  [el tree payload]
  (when el
    (if payload
      (rdc/hydrate-root el tree)
      (let [root (rdc/create-root el)]
        (rdc/render root tree)
        root))))
