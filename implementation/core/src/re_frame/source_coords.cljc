(ns re-frame.source-coords
  "Compile-time source-coordinate capture for registration macros.
  Per Spec 001 §Source-coordinate capture (CLJS reference) and
  Tool-Pair §Source-mapping.

  Every registration's metadata carries `:ns` / `:line` / `:file`
  auto-supplied at compile time. Tools (re-frame-pair, re-frame-10x,
  IDE jump-to-source) consume these via `(rf/handler-meta kind id)`.

  The capture mechanism:

    1. Each public reg-* macro at the re-frame.core boundary captures
       :line / :column from `(meta &form)` and :ns / :file from the
       compile-time environment, builds a `coords` literal map, and
       binds `*pending-coords*` around the underlying registration
       fn call.
    2. The registration fn merges *pending-coords* into the metadata
       it stores in the registrar slot. User-supplied :ns / :line /
       :file override the auto-captured values (so tooling that
       synthesises registrations from another source can stamp the
       original coordinates).

  The data itself is fine in production (static metadata on the
  registry slot — bytes, not behaviour). The DOM-annotation hook
  (per Tool-Pair §Source-mapping) is the dev-only piece, gated
  separately. Source-coord capture itself stays unconditional — the
  runtime cost is one map merge at registration time.")

(def ^:dynamic *pending-coords*
  "Per-thread (per-call) source coords captured by a reg-* macro and
  consumed by the underlying registration fn. nil outside a macro
  invocation.

  Shape: `{:ns sym :line int :file string :column int}` — see Spec 001
  §The metadata map. :ns / :line / :file are the locked keys; :column
  is an optional refinement. All keys are present when a macro
  captured the call site; nil otherwise (programmatic / REPL
  registrations that bypass the macro path)."
  nil)

(defn merge-coords
  "Merge `*pending-coords*` into `user-meta`. User-supplied :ns / :line
  / :file override auto-captured values per Spec 001. Returns user-meta
  unchanged when no coords are pending (programmatic registration,
  REPL eval without the macro path)."
  [user-meta]
  (let [coords *pending-coords*]
    (if coords
      (merge coords (or user-meta {}))
      (or user-meta {}))))
