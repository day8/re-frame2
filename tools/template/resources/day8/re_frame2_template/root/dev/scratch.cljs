(ns {{namespace}}.scratch
  "Scratch namespace — REPL-driven exploration of the running app.

   Connect your editor (Calva, CIDER, Cursive) to the shadow-cljs
   nREPL after `npx shadow-cljs watch app`, then evaluate the
   `(comment …)` forms below.

   The runtime never synthesises a frame from absence; there is no implicit
   `:rf/default` floor.
   A bare `rf/dispatch` / `rf/subscribe` evaluated at the REPL with no
   established scope raises `:rf.error/no-frame-context`. So every
   example below names a frame explicitly: the live-app forms pin
   `:rf/default` (the id `core.cljs` registers for this app) with
   `rf/with-frame`; the throw-away experiment makes its own frame with
   `rf/with-new-frame`."
  (:require [re-frame.core :as rf]))

(comment
  ;; --- Drive the live app -------------------------------------------------
  ;;
  ;; `core.cljs` registers `:rf/default` as this app's frame and renders the
  ;; UI under `(rf/frame-provider {:frame :rf/default} …)`. At the REPL no
  ;; lexical scope is in effect, so pin `:rf/default` with `with-frame` — the
  ;; body then resolves to the SAME frame the on-screen UI uses. Dispatches
  ;; you fire here mutate the app-db you see on screen; the deref reads the
  ;; live value back.
  (rf/with-frame :rf/default
    ;; Fire the counter event a few times and watch the on-screen value
    ;; tick up.
    (rf/dispatch [:counter/increment])
    (rf/dispatch [:counter/increment])

    ;; `subscribe` returns a reactive ref — deref it (`@…`) to read the
    ;; current value.
    @(rf/subscribe [:counter/value]))

  ;; A single one-off form can name the frame inline instead of opening a
  ;; `with-frame` block — both `dispatch` and `subscribe` take a trailing
  ;; `:frame` opt:
  (rf/dispatch [:counter/increment] {:frame :rf/default})
  @(rf/subscribe [:counter/value] {:frame :rf/default})

  ;; --- Experiment in a throw-away frame -----------------------------------
  ;;
  ;; `with-new-frame` is the eval-bind-run-destroy form: it evaluates the
  ;; expr (here `make-frame`), binds the new frame to the symbol, runs the
  ;; body under that frame's scope, then destroys the frame on exit. The live
  ;; `:rf/default` frame is untouched. `:initial-events` seeds the new frame
  ;; before the body runs. Use `with-frame` for an existing frame and
  ;; `with-new-frame` to create a temporary one.
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:rf/set-db {:counter/value 0}]]})]
    (rf/dispatch-sync [:counter/increment])
    @(rf/subscribe [:counter/value]))
  )
