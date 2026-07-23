(ns re-frame.freehand.check-conditional-set-views
  "The control for `check-conditional-set-jvm-test`: reader conditionals inside
  SET literals that every target can read.

  Refusing the sets a target's reader refuses is only worth anything if the
  sets it accepts stay accepted — and a set has more accepted shapes than a
  map does, because a member that selects nothing simply is not there. All
  three sets below diverge across the two targets and collide on neither: an
  ordinary conditional selecting a different member on each, a splice
  contributing a different number of members to each, and a one-armed
  conditional that contributes to `:clj` and vanishes for `:cljs`. Every target
  gets a well-formed set, so this view is inside the grammar wherever it is
  compiled and the checker must say so.

  Nothing invalid lives in this file, and nothing invalid can: a set whose
  `:cljs` selection collides does not READ, and would break the browser build
  of this test tree. The colliding shapes are written to a temp file by the
  suite itself, loaded, checked, and thrown away.

  Loaded on the JVM (required by
  `re-frame.freehand.check-conditional-set-jvm-test`)."
  (:require [re-frame.freehand :as v]))

(v/defview conditional-set-uniform
  "Three sets whose conditional selection collides on no target - eligible
  wherever it is compiled."
  [_]
  [:div {:title       (str #{#?(:clj :a :cljs :b) :c})
         :data-splice (str #{#?@(:clj [:a] :cljs [:a :b])})
         :data-elided (str #{#?(:clj :jvm-only) :always})}
   "sets whose conditionals all select something readable"])
