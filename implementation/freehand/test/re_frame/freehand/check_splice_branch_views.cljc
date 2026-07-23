(ns re-frame.freehand.check-splice-branch-views
  "The control for `check-splice-branch-jvm-test`: `#?@` splices whose selected
  branch is a LIST or VECTOR on every target — the shapes a real reader splices.

  Refusing the splices a target's reader refuses is only worth anything if the
  splices it accepts stay accepted. A `#?@` copies its branch's members into the
  surrounding form and the target reader requires that branch to implement
  `java.util.List`, so a list or a vector splices and a set or map does not. All
  three splices below select a list or vector on both targets — a divergent
  vector, a divergent list, and a vector spliced into a SET — so every target
  gets a well-formed collection and this view is inside the grammar wherever it
  is compiled. The checker must say so.

  Nothing invalid lives in this file, and nothing invalid can: a `#?@` whose
  `:cljs` selection is a set or map does not READ, and would break the browser
  build of this test tree. The refused shapes are written to a temp file by the
  suite itself, loaded, checked, and thrown away.

  Loaded on the JVM (required by
  `re-frame.freehand.check-splice-branch-jvm-test`)."
  (:require [re-frame.freehand :as v]))

(v/defview splice-branch-uniform
  "Splices whose selected branch is a list or vector on every target - eligible
  wherever it is compiled."
  [_]
  [:div {:data-vec  (str [#?@(:clj [:a :b] :cljs [:c])])
         :data-list (str [#?@(:clj (:a :b) :cljs (:c))])
         :data-set  (str #{#?@(:clj [:a] :cljs [:b :c])})}
   "splices whose branches are all lists or vectors"])
