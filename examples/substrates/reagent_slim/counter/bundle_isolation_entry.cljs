(ns reagent-slim.counter.bundle-isolation-entry
  "Gate plumbing for the slim counter — NOT something to copy into an app.

   If you're here to learn the example, you're in the wrong file: read `run`
   in `reagent-slim.counter.core` instead. You will also never run this one,
   because it is not in the example's build. It is the `:init-fn` of a
   separate, deliberately non-runnable build — `:reagent-slim-ssr-isolation-
   fixture` in implementation/shadow-cljs.edn — which emits to an `out/`
   directory of its own and is compiled by nothing but the bundle-isolation
   gate.

   That separation is the point (rf2-kjx1). The runnable
   `:examples/counter-slim-and-fast` build boots `core/run` and stops there,
   so the artefact a reader serves, and the bundle they weigh against stock
   Reagent's, holds the counter and none of the machinery below. Two
   bundles, two jobs: one to run, one to grep.

   What this entry adds is a single move. It boots the app exactly as
   `core/run` does, then runs slim's pure-CLJS `render-to-static-markup`
   over the same view in a throwaway frame, so the gate's S3-005
   non-vacuity contract has something real to inspect —
   `fixture/prove-pure-cljs-ssr!` explains the two mechanics involved. The
   gate itself, `implementation/scripts/check-reagent-slim-bundle-
   isolation.cjs`, owns the contract and asserts BOTH halves: the SSR path
   present here, and absent from the runnable bundle."
  (:require [re-frame.views]
            [re-frame.core                                 :as rf]
            [reagent-slim.counter.core                     :as core]
            [reagent-slim.counter.bundle-isolation-fixture :as fixture]))

(defn run []
  ;; Boot the example first, by the same call the runnable build makes. Keeping
  ;; the fixture strictly additive is what lets the gate assert that the
  ;; runnable bundle is this one minus the SSR exercise, and nothing else.
  (core/run)
  ;; The SSR exercise gets a frame of its own — created here, destroyed on
  ;; exit — so it never touches the app frame the provider set up above.
  (rf/with-new-frame [_ (rf/make-frame {})]
    (fixture/prove-pure-cljs-ssr! [core/counter-app])))
