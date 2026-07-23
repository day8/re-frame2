(ns re-frame.freehand.check-branch-identity-views
  "One top-level reader conditional declaring a DIFFERENT view per target.

  `check-conditional-placement-views` covers the ordinary case: a declaration
  written across a conditional that says the same thing about itself on both
  targets — same name, same declared lowering — and differs only in what it
  binds and renders. That is one declaration, and one report answers for it.

  This is the other case, and it is two declarations wearing one form. The JVM
  build declares `jvm-view`; the browser build declares `browser-view`, with
  different params and a head bound from them. Nothing here is illegal — a
  `.cljc` namespace may absolutely offer a different view per host — but no
  single report is true of both, because a report names one view id and one
  lowering. The checker used to answer anyway, by preferring the `:clj` branch
  for identity and the `:cljs` branch for findings, which produced a confident
  report about a view that has no such body: `jvm-view`, ineligible, offending
  form `[tag \"browser view\"]`. `browser-view` never appeared at all.

  Loaded on the JVM (required by
  `re-frame.freehand.check-branch-identity-jvm-test`), where the reader takes
  the `:clj` branch, so only `jvm-view` exists here at runtime."
  (:require [re-frame.freehand :as v]))

#?(:clj
   (v/defview jvm-view
     "The view the JVM build declares."
     [_]
     [:div "JVM view"])

   :cljs
   (v/defview browser-view
     "The view the browser build declares - a different name, different
     params, and a head bound from them."
     [{:keys [tag]}]
     [tag "browser view"]))
