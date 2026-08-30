(ns reagent2.core
  "CLJS-side macros for reagent2.core.

  Stock Reagent defines `reagent.core/reaction` as a macro in
  `reagent/core.clj` (Reagent 2.0.1); its `core.cljs` carries no
  `reaction` function at all. This file is the same arrangement under
  the `reagent2.core` spelling, so the mechanical `s/reagent\\./reagent2./g`
  import rename (IMPL-SPEC §13.1) leaves every `(r/reaction body...)`
  call site meaning what it meant.

  No CLJ-side runtime code lives here; only the macro consumed by CLJS
  build sites via `:require-macros`.")

(defmacro reaction
  "Creates a Reaction from `body` and returns it: a derefable holding the
  body's result. Every reagent2 atom or Reaction deref'd inside `body`
  registers as a dependency, and the Reaction recomputes when any of
  them change. The body does not run until the first deref.

  Expands to `(reagent2.ratom/make-reaction (fn [] body...))` — the same
  expansion as stock `reagent.core/reaction` and as the sibling
  `reagent2.ratom/reaction`. A caller that already holds a thunk uses
  `reagent2.ratom/make-reaction` directly.

  A new Reaction is created on every call, so build it once (a Form-2
  closure, Form-3 state) rather than inside a render body."
  [& body]
  `(reagent2.ratom/make-reaction (fn [] ~@body)))
