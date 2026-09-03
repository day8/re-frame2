(ns re-frame.test-quiet.warn-buffer
  "Pure bounded-ring helper for the CLJS `:node-test` runner's buffered
  `console.warn` diagnostic (see `re-frame.test-quiet.shadow-node`).

  Lives apart from the runner so it can be unit-pinned: the runner ns
  carries `{:dev/always true}` and expands `shadow.test.env/get-test-data`
  (a macro that enumerates the build's test namespaces at compile time),
  so a test ns requiring the runner directly forms a compile cycle — the
  same reason `re-frame.test-quiet.shadow-node-cli` is factored out.  This
  helper has no such dependency, so `re-frame.test-quiet-shadow-node-cljs-test`
  can pin the entry-count and backing-vector bound directly.")

(def warn-buffer-cap
  "Bounded warning-call count. The newest `warn-buffer-cap` calls are
  retained and older ones are dropped. Individual warning arguments remain
  unbounded, so this is not a byte-size limit."
  256)

(defn bound-conj
  "Append `entry` to ring vector `buffer`, retaining at most `capacity` (default
  `warn-buffer-cap`) NEWEST entries.

  The trimmed window is MATERIALISED into a fresh vector rather than
  returned as a `subvec`.  This is the whole point of the helper: a
  ClojureScript `Subvec` shares — and thereby RETAINS — its entire
  underlying vector via `.-v`, and `conj` on a `Subvec` grows that
  underlying vector rather than the window.  So trimming with `subvec`
  alone would keep every discarded warning (and its arg vectors) alive
  until process exit, silently defeating the bound. `into
  []` copies only the window into a new `PersistentVector`, dropping the
  discarded head so retained entry count stays bounded to `capacity`."
  ([buffer entry]
   (bound-conj buffer entry warn-buffer-cap))
  ([buffer entry capacity]
   (let [appended-buffer (conj buffer entry)
         entry-count     (count appended-buffer)]
     (if (> entry-count capacity)
       (into [] (subvec appended-buffer (- entry-count capacity)))
       appended-buffer))))
