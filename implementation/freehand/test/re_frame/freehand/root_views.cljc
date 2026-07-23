(ns re-frame.freehand.root-views
  "The declared views the `FH-ROOT` rows mount.

  They live apart from `re-frame.freehand.tree-views` on purpose: that file
  is diffed declaration-for-declaration against its compiled twin
  (`compiled_source_delta_jvm_test`), so a view added there for an
  unrelated row would break a pairing it has nothing to do with. A root
  view is host-neutral — no subscriptions, no host objects, no React — so
  the same declaration mounts to real DOM in the browser and answers a
  structural tree on the JVM."
  (:require [re-frame.freehand :as v]))

(v/defview app
  "The minimal single-root view — a plain element with an id and text,
  enough to read back off the document and to pin one small structural
  tree. Its qualified id is the root's derived identity."
  [{:keys [label]}]
  [:main#app label])
