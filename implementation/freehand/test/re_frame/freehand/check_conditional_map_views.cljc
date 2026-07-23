(ns re-frame.freehand.check-conditional-map-views
  "The control for `check-conditional-map-jvm-test`: reader conditionals in
  map KEY and map VALUE position that every target can read.

  Refusing the map shapes a target's reader refuses is only worth anything if
  the shapes it accepts stay accepted. Both entries here diverge across the
  two targets — a different key on each, a different value on each — and both
  targets get a well-formed map with the same number of entries, so this view
  is inside the grammar wherever it is compiled and the checker must say so.

  Nothing invalid lives in this file, and nothing invalid can: source whose
  `:cljs` branch does not READ would break the browser build of this test
  tree. The unreadable shapes are written to a temp file by the suite itself,
  loaded, checked, and thrown away.

  Loaded on the JVM (required by
  `re-frame.freehand.check-conditional-map-jvm-test`)."
  (:require [re-frame.freehand :as v]))

(v/defview conditional-map-uniform
  "A conditional key and a conditional value, both selecting a form on every
  target - eligible wherever it is compiled."
  [_]
  [:div {#?(:clj :title :cljs :data-title) "hover"
         :class                            #?(:clj "jvm" :cljs "spa")}
   "a map whose conditionals all select something"])
