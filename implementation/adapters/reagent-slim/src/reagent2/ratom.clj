(ns reagent2.ratom
  "CLJS-side macros for reagent2.ratom.

  Per IMPL-SPEC §2.3:

    - `reaction` ships as a 5-line indirection over `make-reaction`.
      Required for rf8 wizard/reports_util.cljs:158, 166 (rf2-kfpf §3).
      `reagent2.core/reaction` (src/reagent2/core.clj) is the same macro
      under the stock `reagent.core` spelling — the one Dash8's 25
      `r/reaction` sites use.

    - `run!` is NOT shipped — audit-confirmed zero usage across re-com /
      10x / Dash8 / rf8 (per §2.3 \"Symbols not shipped\" list).

  No CLJ-side runtime code lives here; only the macros consumed by CLJS
  build sites via `:require-macros`.")

(defmacro reaction
  "Sugar for (make-reaction (fn [] body)). The body executes inside a
  reactive context — derefs of RAtoms / Reactions inside `body` register
  as dependencies, and the Reaction recomputes when any of them change.

  Example:

    (def first-name (r/atom \"Alice\"))
    (def last-name  (r/atom \"Tan\"))
    (def full-name  (reaction (str @first-name \" \" @last-name)))
    @full-name  ;=> \"Alice Tan\"
    (reset! first-name \"Bob\")
    @full-name  ;=> \"Bob Tan\"

  Per IMPL-SPEC §2.3: 5-line indirection. `reagent2.core/reaction` has
  the same expansion; a caller that already holds a thunk uses
  `make-reaction` directly."
  [& body]
  `(reagent2.ratom/make-reaction (fn [] ~@body)))
