(ns re-frame.freehand
  "The Freehand view substrate — the single public door of the re-frame2
  view layer (EP-0036; ruled by D001, conventionally aliased `v`).

  Freehand is one re-frame-native substrate with two execution modes over
  one semantic model: an interpreted paved path, and a compiled hot tier
  selected by `{:compiled true}` on the same declaration. A declared view
  var is a non-`IFn` descriptor in both modes.

  F1a skeleton (rf2-drpa3.15): empty-but-loadable, and deliberately so.
  This artefact currently exports NO public surface — `defview`, `sub`,
  `mount` and the rest of the paved path land with the F1 spine slices,
  and anything not yet ruled into the surface table does not exist. What
  this file proves today is that the artefact compiles as `.cljc` on both
  hosts, which is the precondition for the two emitters F1 needs.")

(def ^:no-doc artefact-id
  "Build probe — NOT product surface, and no part of the Freehand API.

  `re-frame.freehand.skeleton-cljs-test` pins this value so that the
  test's `:require` of this namespace is load-bearing in BOTH runtimes:
  a missing or unloadable artefact fails at CLJS compile / JVM load time
  rather than passing vacuously. Delete it the moment F1b gives the suite
  a real surface to load."
  :day8/re-frame2-freehand)
