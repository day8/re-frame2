(ns re-frame.fresco.examples.editor.l0-cljs-test
  "L0 — THE EDITOR'S MODEL, WITH NO VIEW SUBSTRATE ANYWHERE.

  `re-frame.fresco.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: an event handler is a function of `db` and
  an event vector, a subscription is a function of its inputs, and a
  state transition is the pair. So this file requires neither
  `re-frame.fresco` nor either half of the kit, and neither do the
  namespaces it tests: `examples.fence-cljs-test` holds the kit half of
  that for every application namespace in the tree, and the
  `re-frame.fresco` half is read off their `ns` forms.

  Frame scope is the programmer's ordinary bracket — `rf/with-new-frame`.

  ## Through a real frame

  The row goes through a real frame and a real `dispatch-sync`, because
  a handler is only a handler once the registrar has it and the
  interceptor chain has run — a test that called the handler function by
  hand would be green for a registration that never happened.

  ## The row is about the SHAPE of a write, not its value

  [[one-keystroke-moves-exactly-one-address]] is the L0 half of the
  per-keystroke budget. `flow-dom-cljs-test` counts the bodies that run;
  this counts the addresses that move, and the two together are the walk
  `docs/core/fresco/19-performance.md` §Trace one
  controlled keystroke describes. It is asserted here rather than there
  because it is a property of the model and needs no React to be true."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.examples.editor.app :as rf.fresco.examples.editor.app]
            [re-frame.fresco.examples.editor.events :as rf.fresco.examples.editor.events]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(defn- with-app
  "Run `f` against a fresh frame seeded exactly as the application seeds
  itself — `app/initial-events`, not a hand-written `:rf/set-db`, so
  these rows exercise the boot the application actually has."
  [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events rf.fresco.examples.editor.app/initial-events})]
    (f frame)))

(defn- type! [frame field text]
  (rf/dispatch-sync [::rf.fresco.examples.editor.events/edit field text] {:frame frame}))

(deftest one-keystroke-moves-exactly-one-address
  (with-app
    (fn [frame]
      (let [before (rf/app-db-value frame)
            _      (type! frame :title "x")
            after  (rf/app-db-value frame)]
        (is (= (assoc-in before [:draft :title] "x") after)
            "one write, to the field typed into, and nothing else moved —
             the first number in the per-keystroke walk; the others are
             counted in flow-dom-cljs-test")))))
