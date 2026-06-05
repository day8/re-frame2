(ns {{namespace}}.scratch
  "Scratch namespace — REPL-driven exploration of the running app.

   Connect your editor (Calva, CIDER, Cursive) to the shadow-cljs
   nREPL after `npx shadow-cljs watch app`, then evaluate the
   `(comment …)` forms below. Each call hits the same registrar /
   frame the live UI is using — dispatches you fire here mutate the
   app-db you see on screen."
  (:require [re-frame.core :as rf]))

(comment
  ;; Fire the counter event a few times and watch the on-screen value
  ;; tick up.
  (rf/dispatch [:counter/increment])
  (rf/dispatch [:counter/increment])

  ;; Obtain the subscription ref for the default frame. `subscribe`
  ;; returns a reactive ref — deref it (`@…`) to read the current
  ;; value, as the `with-frame` block below does.
  (rf/subscribe [:counter/value])

  ;; Run a scratch experiment against a throw-away frame — leaves
  ;; the live :rf/default frame untouched.
  ;;
  ;; `with-frame` takes a keyword frame-id and binds `*current-frame*`
  ;; to it for the duration of the body. (For an eval-bind-run-destroy
  ;; throwaway frame, reach for the sibling macro `with-new-frame`,
  ;; which takes `[sym expr]` and destroys the bound frame on exit —
  ;; see Spec 002 §with-frame.)
  (rf/with-frame :scratch
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    @(rf/subscribe [:counter/value]))
  )
