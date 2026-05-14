(ns re-frame.error
  "Shared error-message helpers — short tag fns and reason-string
  primitives used by error / warning trace emit sites across core and
  the per-feature artefacts.

  `type-of-value` renders a short type tag in the `:reason` string of
  a `:rf.error/schema-validation-failure` trace event; consumed by
  `re-frame.spec` and `re-frame.schemas.validate`.

  Lives in core because schemas depends on core (Spec 006 §Adapter
  shipping convention) — pushing the helper down means the schemas-
  side validate.cljc can `:require` it without inverting the dep
  direction. Apps that don't load the schemas
  artefact still get the helper for free under `re-frame.spec`'s
  boundary-validation interceptor.

  Pure; no runtime state. Hot-path safe (single keyword cond cascade
  with no allocation on the common branches).")

#?(:clj (set! *warn-on-reflection* true))

(defn type-of-value
  "Best-effort short tag for a value's type — surfaced inside the
  `:reason` slot of a `:rf.error/schema-validation-failure` (or
  similar) trace event. Returns a stable lowercase string for the
  primitive Clojure shapes; falls back to `(str (type v))` for
  anything else.

  Stable contract — the eight enumerated tags
  (`string` / `integer` / `number` / `boolean` / `keyword` / `map`
  / `vector` / `nil`) are part of the framework's reason-string
  vocabulary. Adding new fast-path tags is additive; renaming an
  existing one breaks consumers' grep-pinned reason-string fixtures."
  [v]
  (cond
    (string? v)  "string"
    (integer? v) "integer"
    (number? v)  "number"
    (boolean? v) "boolean"
    (keyword? v) "keyword"
    (map? v)     "map"
    (vector? v)  "vector"
    (nil? v)     "nil"
    :else        (str (type v))))
