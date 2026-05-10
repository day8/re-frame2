(ns reagent2.ratom
  "CLJS-side macros for reagent2.ratom.

  Per IMPL-SPEC §2.3:

    - `reaction` ships as a 5-line indirection over `make-reaction`.
      Required for rf8 wizard/reports_util.cljs:158, 166 (rf2-kfpf §3).
      Dash8 also uses the function form `reagent.core/reaction` (25
      sites); that surface lives in `reagent2.core` (Stage 4-D).

    - `run!` is NOT shipped — audit-confirmed zero usage across re-com /
      10x / Dash8 / rf8 (per §2.3 \"Symbols not shipped\" list).

  No CLJ-side runtime code lives here; only the macros consumed by CLJS
  build sites via `:require-macros`."
  (:refer-clojure :exclude [run!]))

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

  Per IMPL-SPEC §2.3: 5-line indirection. The function form
  `reagent2.core/reaction` (Stage 4-D) is equivalent but takes a
  thunk argument explicitly."
  [& body]
  `(reagent2.ratom/make-reaction (fn [] ~@body)))
