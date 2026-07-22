(ns re-frame.freehand.check-fixture-views
  "A file of ORDINARY interpreted declarations for the checker to read.

  Nothing here is written for the checker's convenience: these are two
  views an author would write without a thought about compilation, and
  that is the whole point. The checker's subject is a declaration as it
  stands BEFORE `{:compiled true}` is added, so the fixture must be a
  real file on disk that compiles, loads, and renders exactly as it did
  before anyone thought about the compiled tier — otherwise the suite
  would be checking a shape invented to be checked.

  `roster` is the D010 cliff in its most ordinary clothing: a helper
  that returns markup, mapped over a collection. The interpreted walk
  accepts it without comment; the compiled grammar cannot see through
  it. `row` is the same page's leaf, and is already inside the grammar.

  Read by `re-frame.freehand.check-jvm-test`, which also asserts this
  file is byte-identical after a run."
  (:require [re-frame.freehand :as v]))

(defn person-item
  "A helper that manufactures markup — a plain `defn`, direct-called."
  [person]
  [:li.person (:name person)])

(v/defview row
  "Inside the grammar: a literal element over declared prop slots."
  [{:keys [label done?]}]
  [:li.row {:class {:done done?}} label])

(v/defview roster
  "Outside the grammar: the markup is manufactured by a helper call."
  [{:keys [people]}]
  [:ul.roster
   (map person-item people)])
