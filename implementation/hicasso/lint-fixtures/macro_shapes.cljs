(ns macro-shapes
  "The macro-shape smoke for Hicasso's clj-kondo export (rf2-r3r00).

  Every documented declaration shape of `defview`, `event` and `defhost` —
  optional docstrings and destructuring included — written CORRECTLY, so that
  linting this file with the shipped export must produce nothing but the one
  sentinel below. If a rewrite stops loading, every name it defines and every
  prop it binds reads as `Unresolved symbol`, which is the red
  `check_lint_export.py` asserts on.

  THE SENTINEL: `sentinel` declares a prop its body never reads, and the gate
  requires exactly that one `:unused-binding` finding. It is the proof that
  kondo's ordinary analysis actually ran over the rewritten forms — a gate
  that expected pure silence would stay green while linting nothing at all."
  (:require [re-frame.hicasso :as rf.hicasso]))

;; A stand-in for a foreign React component, so `defhost` has something to
;; wrap.
(def foreign-widget :stub)

;; `defview`, docstring + destructuring: the name defines a var, the props
;; bind, and the body's uses resolve.
(rf.hicasso/defview greeting
  "One paragraph, both props read."
  [{:keys [salutation subject]}]
  [:p.greeting salutation ", " subject])

;; `defview`, no docstring, whole-map prop.
(rf.hicasso/defview badge [props]
  [:span.badge (:label props)])

;; `defhost`, docstring + opts: the component and opts expressions are
;; analysed as ordinary code, so `foreign-widget` is a use, not a mystery.
(rf.hicasso/defhost fancy-widget
  "The interop door, fully dressed."
  foreign-widget
  {:fallback [:div "loading"]})

;; `defhost`, bare: no docstring, no opts.
(rf.hicasso/defhost plain-widget foreign-widget)

;; `event` is `fn`-shaped: its argument binds and its body's use resolves —
;; and every view and host above is referenced here, so a rewrite that stops
;; defining one reds this body too.
(rf.hicasso/defview page [_]
  [:main
   [greeting {:salutation "Hello" :subject "world"}]
   [badge {:label "new"}]
   [fancy-widget]
   [plain-widget]
   [:button {:on-click (rf.hicasso/event [payload] payload)} "echo"]])

;; THE SENTINEL — `unread` is deliberately never used. The gate pins the
;; `:unused-binding` finding this row produces; see the ns docstring.
(rf.hicasso/defview sentinel [{:keys [shown unread]}]
  [:p shown])
