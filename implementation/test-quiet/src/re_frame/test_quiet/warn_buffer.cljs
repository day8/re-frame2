(ns re-frame.test-quiet.warn-buffer
  "Pure bounded-ring helper for the CLJS `:node-test` runner's buffered
  `console.warn` diagnostic (see `re-frame.test-quiet.shadow-node`).

  Lives apart from the runner so it can be unit-pinned: the runner ns
  carries `{:dev/always true}` and expands `shadow.test.env/get-test-data`
  (a macro that enumerates the build's test namespaces at compile time),
  so a test ns requiring the runner directly forms a compile cycle — the
  same reason `re-frame.test-quiet.shadow-node-cli` is factored out.  This
  helper has no such dependency, so `re-frame.test-quiet-shadow-node-cljs-test`
  can pin the memory bound directly.")

(def warn-buffer-cap
  "Bounded warning ring size.  Caps memory + replay volume on a red run
  while keeping enough recent context to explain a failure (the newest
  `warn-buffer-cap` warnings are retained; older ones are dropped)."
  256)

(defn bound-conj
  "Append `x` to ring vector `buf`, retaining at most `cap` (default
  `warn-buffer-cap`) NEWEST entries.

  The trimmed window is MATERIALISED into a fresh vector rather than
  returned as a `subvec`.  This is the whole point of the helper: a
  ClojureScript `Subvec` shares — and thereby RETAINS — its entire
  underlying vector via `.-v`, and `conj`-ing onto a `Subvec` grows that
  underlying vector rather than the window.  So trimming with `subvec`
  alone would keep every discarded warning (and its arg vectors) alive
  until process exit, silently defeating the bound (rf2-6emzlh).  `into
  []` copies only the window into a new `PersistentVector`, dropping the
  discarded head so memory stays bounded to `cap` entries."
  ([buf x] (bound-conj buf x warn-buffer-cap))
  ([buf x cap]
   (let [buf' (conj buf x)
         n    (count buf')]
     (if (> n cap)
       (into [] (subvec buf' (- n cap)))
       buf'))))
