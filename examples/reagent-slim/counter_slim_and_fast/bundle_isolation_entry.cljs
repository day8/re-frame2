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

   The boot mirrors `counter-slim-and-fast.core/run` exactly (same adapter,
   frame, boot dispatch, lazy client mount), with the one fixture concern
   woven in: the pure-CLJS `render-to-static-markup` exercise runs under the
   frame scope, before the client mount, so the static render's orphaned SSR
   subscription is torn down (fixture/prove-pure-cljs-ssr! clears the
   sub-cache) and the browser mount below owns the only live
   `[:counter/value]` reaction."
  (:require [reagent2.dom.client                            :as rdc]
            [re-frame.core                                  :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent-slim                  :as reagent-slim-adapter]
            [counter-slim-and-fast.core                     :as core]
            [counter-slim-and-fast.bundle-isolation-fixture :as fixture]))

(defn run []
  ;; Same boot as core/run — init the slim adapter, register the app frame,
  ;; dispatch the boot event under the frame scope — plus the fixture's
  ;; pure-CLJS SSR exercise woven in at the one point its ordering requires.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/reg-frame core/app-frame {})
  (rf/with-frame core/app-frame
    (rf/dispatch-sync [:counter/initialise])
    ;; Bundle-isolation fixture, not app practice. The static render derefs
    ;; `[:counter/value]`, so it runs inside the frame scope established above;
    ;; it caches an orphaned reaction with no component to unmount it, so the
    ;; fixture clears the sub-cache in a `finally`. Running it here — before the
    ;; client mount — means the browser mount below starts from a clean
    ;; sub-cache and owns the only live `[:counter/value]` reaction.
    (fixture/prove-pure-cljs-ssr! [core/counter-app]))
  (when (exists? js/document)
    (when-not @core/react-root
      (reset! core/react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @core/react-root
                [rf/frame-provider-existing {:frame core/app-frame}
                 [core/counter-app]])))
