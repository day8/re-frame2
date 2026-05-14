(ns re-frame.story.predicates
  "Story-internal pure data → data helpers shared across the tree.

  Lives at the leaf of the require graph (depends on `clojure.core`
  only) so any sibling namespace can `:require` it without cycle risk.

  Companion leaf to `re-frame.story.late-bind` (run-time function
  resolution) — both are pure namespaces the rest of Story consumes.

  The five micro-fns here used to live as private mirrors across
  ~9 sites (args / assertions / recorder / docs / state / test-mode/pure).
  Each mirror was justified locally by a cycle dodge, but the systemic
  shape is one canonical leaf. See rf2-pzzbw / audit rf2-cgqam.")

(def reserved-assertion-ns
  "Reserved namespace string for assertion event ids per Conventions.md
  (the `:rf.assert/*` family)."
  "rf.assert")

(defn parent-story-id
  "Derive a variant's parent story id by stripping the variant's name
  per spec/007 §Canonical id grammar — a variant id `:story.foo/bar`
  has namespace `\"story.foo\"`; its parent is `:story.foo`.

  Returns nil for non-keywords or keywords without a namespace."
  [variant-id]
  (when (and (keyword? variant-id) (namespace variant-id))
    (keyword (namespace variant-id))))

(defn assertion-id?
  "True iff `id` is an `:rf.assert/*` event id. Always returns a
  primitive boolean."
  [id]
  (boolean (and (keyword? id) (= reserved-assertion-ns (namespace id)))))

(defn assertion-event?
  "True iff `event` is a vector whose head is an `:rf.assert/*` keyword.

  Used by the play-runner + recorder + test-mode pane to distinguish
  observed-trace dispatches from authored assertions. Always returns a
  primitive boolean."
  [event]
  (boolean (and (sequential? event)
                (seq event)
                (assertion-id? (first event)))))
