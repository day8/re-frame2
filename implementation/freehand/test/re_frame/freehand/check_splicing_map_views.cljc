(ns re-frame.freehand.check-splicing-map-views
  "One declaration carrying the single shape the checker's read mode cannot
  read: a map literal containing a SPLICING reader conditional.

  `#?@` inside a map is ordinary, legal Clojure. Every compile reads this file
  with `:read-cond :allow`, which splices the branch's forms into the map's
  form sequence before the map is built, so the JVM sees `{:class \"card\"
  :title \"JVM\"}` and the browser build sees `{:class \"card\" :title
  \"browser\"}` — two well-formed maps, and a view that is inside the grammar
  on both targets.

  The CHECKER reads with `:read-cond :preserve` instead, because that is the
  only way past the JVM reader's unconditional `:clj` platform feature to the
  `:cljs` branch. Under `:preserve` a splicing conditional stays ONE form, and
  Clojure's map reader counts its forms before splicing — so this map is odd
  and the read fails. The limitation belongs to the checker, not to this file,
  and the checker says so rather than raising a reader exception at an author
  who wrote nothing wrong.

  Loaded on the JVM (required by `re-frame.freehand.check-splicing-map-jvm-test`)
  precisely to prove that: the namespace loads without incident, so a refusal
  from the checker cannot be blamed on the source being unloadable."
  (:require [re-frame.freehand :as v]))

(v/defview splicing-map-attrs
  "An attribute map assembled by a splicing conditional - legal for both
  targets, unreadable with conditionals preserved."
  [_]
  [:div {:class "card" #?@(:clj [:title "JVM"] :cljs [:title "browser"])}
   "a map literal carrying a splicing conditional"])
