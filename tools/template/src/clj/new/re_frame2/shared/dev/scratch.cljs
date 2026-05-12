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

  ;; Read the current value out of the default frame.
  (rf/subscribe [:counter/value])

  ;; Run a scratch experiment against a throw-away frame — leaves
  ;; the live :rf/default frame untouched.
  (rf/with-frame {:rf/id ::scratch}
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    @(rf/subscribe [:counter/value]))
  )
