(ns re-frame.freehand.check-branch-identity-split-views
  "The recovery `check-branch-identity-views` is refused with: two views, two
  top-level conditionals.

  A one-armed `#?(:clj …)` resolves to its declaration for the JVM target and
  to nothing for the browser one, so each view here is discovered exactly on
  the target that declares it, checked against that target, and reported under
  its OWN id at its OWN line. `browser-only` is deliberately ineligible — a
  head bound from props — so the file also proves the recovery answers the
  question the refused shape could not: which of the two declarations is
  outside the grammar.

  Loaded on the JVM (required by
  `re-frame.freehand.check-branch-identity-jvm-test`), where only `jvm-only`
  is declared; `browser-only` exists for the browser build and for the
  checker."
  (:require [re-frame.freehand :as v]))

#?(:clj
   (v/defview jvm-only
     "Declared for the JVM build alone - a literal element, inside the grammar."
     [_]
     [:div "JVM only"]))

#?(:cljs
   (v/defview browser-only
     "Declared for the browser build alone - a head bound from props, outside
     the grammar."
     [{:keys [tag]}]
     [tag "browser only"]))
