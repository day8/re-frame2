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

(v/defview panel
  "The MULTI-root view: one declaration mounted twice on one page.

  It carries an UNCONTROLLED input, seeded once and never written to
  again, because that is the cheapest piece of state that is genuinely
  per-occurrence — whatever is typed into one panel's field exists in
  that occurrence and nowhere else. Two roots that shared identity, a
  host root, or a boundary would show it in both."
  [{:keys [label seed]}]
  [:section.panel
   [:span.label label]
   [:input.field {:type "text" :default-value seed}]])

(v/defview counter
  "A view whose body READS — an ordinary subscription against whatever
  frame the root bound. It is the preflight proof (the read must resolve
  on the FIRST render, so the frame has to be live before React saw
  anything) and the teardown proof (the read is a dependency the live
  sub-cache holds, and unmounting must release it)."
  [_]
  [:p.count (str (v/sub [:root/n]))])

(v/defview greeting
  "The HYDRATION view: static markup a server can render byte-for-byte,
  with no reads and no host state, so the only thing a hydration test
  measures is adoption."
  [{:keys [name]}]
  [:section#greeting
   [:h1#title "Hello"]
   [:p#who name]])
