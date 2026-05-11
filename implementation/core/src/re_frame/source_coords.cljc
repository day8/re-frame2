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

;; ---- :file resolution at macro-expansion time (rf2-mdjp) ------------------
;;
;; The reg-* macros in `re-frame.core` capture `(meta &form)` and `*file*`
;; from their compile-time environment and emit a `*pending-coords*`
;; binding map. The naive `*file*`-only path is wrong under CLJS: the
;; CLJS analyzer's macro-expansion entry point (`cljs.analyzer/
;; macroexpand-1*`, cljs/analyzer.cljc ~L4284) binds `*cljs-file*` rather
;; than Clojure's `*file*` during expansion — so `*file*` retains the
;; JVM compiler's default `"NO_SOURCE_PATH"` sentinel under CLJS. That
;; sentinel would then get baked into the `:file` slot of every
;; registration's source-coord, defeating jump-to-source and tooling
;; that reads `(rf/handler-meta kind id)`.
;;
;; The fix mirrors rf2-ulxi (PR #340, Story's `coords-form`):
;; prefer `(:file (meta &form))` — tools.reader's indexing-push-back-reader
;; stamps `:file` on every collection-form's metadata, which survives the
;; macro-expansion handoff to cljs.analyzer. Fall back to `*file*` (the
;; JVM-only correct path). Reject the `"NO_SOURCE_PATH"` sentinel from
;; either source — if both resolve to it, omit `:file` entirely (better
;; no `:file` than a poison value).

(defn ^:private no-source-path? [s]
  (or (nil? s) (= "NO_SOURCE_PATH" s)))

(defn resolve-file
  "Pick the right `:file` value for a reg-* macro's emitted source-coord
  map. `form-meta` is `(meta &form)`; `file` is `*file*` at expansion
  time. Returns the form-meta `:file` when non-sentinel, else the
  `*file*` arg when non-sentinel, else `nil` (caller `cond->`s it in,
  so nil means omit the slot).

  Per rf2-mdjp: the CLJS analyzer never binds Clojure's `*file*` during
  macro expansion, so reading `*file*` alone returns the JVM
  `\"NO_SOURCE_PATH\"` sentinel under CLJS. Form-meta `:file` is the
  portable answer."
  [form-meta file]
  (let [meta-file (:file form-meta)]
    (cond
      (not (no-source-path? meta-file)) meta-file
      (not (no-source-path? file))      file
      :else                              nil)))

(defn coords-form
  "Construct the compile-time `(cond-> {:ns 'sym} ...)` form that every
  reg-* macro emits as the value of its `*pending-coords*` binding.

  `form-meta` is `(meta &form)`; `file` is `*file*`; `ns-sym` is the
  consumer's namespace symbol. The returned form is syntax-quote-safe
  data the caller splices into its expansion.

  :file picks the form-meta value over `*file*` and rejects the
  `\"NO_SOURCE_PATH\"` sentinel per `resolve-file` (rf2-mdjp)."
  [form-meta file ns-sym]
  (let [chosen-file (resolve-file form-meta file)]
    `(cond-> {:ns '~ns-sym}
       ~chosen-file         (assoc :file ~chosen-file)
       ~(:line form-meta)   (assoc :line ~(:line form-meta))
       ~(:column form-meta) (assoc :column ~(:column form-meta)))))

;; ---- per-element spec stamping (rf2-8bp3) --------------------------------
;;
;; Per Spec 005 §Source-coord stamping (rf2-8bp3): the `reg-machine` macro
;; walks its literal machine-spec form at expansion time and produces a
;; per-element coord index keyed by **path through the spec**. Tools (pair,
;; 10x, IDE jump-to-source) read the index back via `(rf/handler-meta :event
;; machine-id)` → `:rf/machine` → `:rf.machine/source-coords`.
;;
;; The index is a flat map `{<path-tuple> {:ns sym :line int :column int :file
;; string}, ...}`. Stamping covers BOTH definition sites (where a fn literal
;; lives — `:guards`/`:actions`/`:on-spawn-actions` map values) AND reference
;; sites (where a keyword reference is mentioned — `:guard`/`:action`/`:entry`/
;; `:exit`/`:on-spawn`/`:always` slots inside `:states`). Mike's rule (per the
;; bead's exemption case): a keyword `:guard :form-valid?` is stamped at the
;; reference site too, not just the definition. Tools wanting "where is the
;; guard defined?" read `[:guards :form-valid?]`; tools wanting "where is the
;; transition that calls it?" read `[:states :idle :on :submit :guard]`. Both
;; coords elide together under `goog.DEBUG=false`.
;;
;; Path tuples are vectors of keys mirroring the spec's tree structure. The
;; walker runs at compile time on JVM only (the Clojure side of the macro)
;; and emits a literal map into the macro expansion; the runtime sees
;; ordinary data.

(defn ^:private form-coords
  "Read source coords off a Clojure form's metadata. Forms the reader has
  decorated (lists, vectors, maps, symbols) carry `:line` / `:column` from
  the source position. Returns nil when the form has no positional meta.

  Per rf2-mdjp the same `:file` resolution as the call-site path applies:
  prefer the reader-attached `:file` on the form's metadata over the
  macro's `*file*` arg, and reject the `\"NO_SOURCE_PATH\"` sentinel."
  [form ns-sym file]
  (let [m            (meta form)
        chosen-file  (resolve-file m file)]
    (when (and m (or (:line m) (:column m)))
      (cond-> {:ns ns-sym}
        chosen-file (assoc :file chosen-file)
        (:line m)   (assoc :line (:line m))
        (:column m) (assoc :column (:column m))))))

(defn- walk-states-tree
  "Recursively walk the literal `:states` map. `path` accumulates the
  spec-path from the spec's root. Adds entries into the mutable `acc`
  transient for each captured reference site / state-node.

  Note on style: this walker is mutation-heavy (transient `acc` threaded
  through nested `reduce-kv` / `doseq` with `assoc!`) rather than the
  more functional shape of a visitor that returns collected entries.
  The imperative shape is deliberate — this code runs at macro-expansion
  time and gets called on every `reg-machine` form. Transients avoid the
  per-state allocation cost of building intermediate persistent maps
  during expansion. The result is materialised once at the edge in
  `walk-machine-spec`. Refactoring to a fully declarative visitor is
  feasible but would need to be benchmarked against current
  compile-time numbers before adoption."
  [states-form path acc ns-sym file]
  (when (map? states-form)
    (reduce-kv
      (fn [acc state-id node]
        (let [node-path (conj path state-id)]
          (when-let [c (form-coords node ns-sym file)]
            (assoc! acc node-path c))
          (when (map? node)
            ;; :entry / :exit references
            (when-let [e (:entry node)]
              (when-let [c (form-coords e ns-sym file)]
                (assoc! acc (conj node-path :entry) c)))
            (when-let [e (:exit node)]
              (when-let [c (form-coords e ns-sym file)]
                (assoc! acc (conj node-path :exit) c)))
            ;; :invoke {:on-spawn ...}
            (when-let [inv (:invoke node)]
              (when-let [c (form-coords inv ns-sym file)]
                (assoc! acc (conj node-path :invoke) c))
              (when (map? inv)
                (when-let [os (:on-spawn inv)]
                  (when-let [c (form-coords os ns-sym file)]
                    (assoc! acc (conj node-path :invoke :on-spawn) c)))))
            ;; :on transitions — map of event-id → transition-or-vector
            (when-let [on-map (:on node)]
              (when (map? on-map)
                (reduce-kv
                  (fn [_ ev-id t]
                    (let [tp (conj node-path :on ev-id)]
                      (when-let [c (form-coords t ns-sym file)]
                        (assoc! acc tp c))
                      (cond
                        (map? t)
                        (do
                          (when-let [g (:guard t)]
                            (when-let [c (form-coords g ns-sym file)]
                              (assoc! acc (conj tp :guard) c)))
                          (when-let [a (:action t)]
                            (when-let [c (form-coords a ns-sym file)]
                              (assoc! acc (conj tp :action) c))))
                        (vector? t)
                        (doseq [[i tx] (map-indexed vector t)
                                :when (map? tx)]
                          (let [tp' (conj tp i)]
                            (when-let [c (form-coords tx ns-sym file)]
                              (assoc! acc tp' c))
                            (when-let [g (:guard tx)]
                              (when-let [c (form-coords g ns-sym file)]
                                (assoc! acc (conj tp' :guard) c)))
                            (when-let [a (:action tx)]
                              (when-let [c (form-coords a ns-sym file)]
                                (assoc! acc (conj tp' :action) c))))))
                      nil))
                  nil on-map)))
            ;; :always — vector of transition maps
            (when-let [always (:always node)]
              (when (vector? always)
                (doseq [[i tx] (map-indexed vector always)
                        :when (map? tx)]
                  (let [tp (conj node-path :always i)]
                    (when-let [c (form-coords tx ns-sym file)]
                      (assoc! acc tp c))
                    (when-let [g (:guard tx)]
                      (when-let [c (form-coords g ns-sym file)]
                        (assoc! acc (conj tp :guard) c)))
                    (when-let [a (:action tx)]
                      (when-let [c (form-coords a ns-sym file)]
                        (assoc! acc (conj tp :action) c)))))))
            ;; :after — map of delay → target-or-transition
            (when-let [after (:after node)]
              (when (map? after)
                (reduce-kv
                  (fn [_ delay t]
                    (let [tp (conj node-path :after delay)]
                      (when-let [c (form-coords t ns-sym file)]
                        (assoc! acc tp c))
                      (when (map? t)
                        (when-let [a (:action t)]
                          (when-let [c (form-coords a ns-sym file)]
                            (assoc! acc (conj tp :action) c))))
                      nil))
                  nil after)))
            ;; recurse into nested :states
            (walk-states-tree (:states node) (conj node-path :states) acc ns-sym file))
          acc))
      acc states-form)))

(defn walk-machine-spec
  "Compile-time helper. Walk a literal machine-spec form (a Clojure map
  literal as it appears in user code) and return a flat map
  `{<path-tuple> {:ns :line :column :file}, ...}` capturing per-element
  source coordinates.

  Definition sites: each fn literal under `:guards` / `:actions` /
  `:on-spawn-actions` is keyed by `[:guards :id]` / `[:actions :id]` /
  `[:on-spawn-actions :id]`.

  Reference sites: each keyword reference under `:entry` / `:exit` /
  `:guard` / `:action` / `:on-spawn` (and the enclosing transition map)
  inside the `:states` tree is keyed by its full spec path, e.g.
  `[:states :idle :on :submit :guard]`.

  When the spec form is not a map literal (a symbol, a let-bound expr),
  returns `{}` — there's no literal tree to walk; tools fall back to the
  reg-machine call-site coords on the spec's top-level handler-meta.

  `ns-sym` and `file` come from the calling macro's compile environment.

  JVM-only — runs on the Clojure side of the macro. Returns a plain map
  literal that the macro splices into the expansion; under `goog.DEBUG=false`
  the closure compiler DCEs the entire literal."
  [spec-form ns-sym file]
  (if-not (map? spec-form)
    {}
    (let [acc (transient {})]
      ;; Definition-site stamping for :guards / :actions / :on-spawn-actions.
      (doseq [[def-key path-key] [[:guards            :guards]
                                  [:actions           :actions]
                                  [:on-spawn-actions  :on-spawn-actions]]]
        (when-let [m (get spec-form def-key)]
          (when (map? m)
            (reduce-kv
              (fn [_ id fn-form]
                (when-let [c (form-coords fn-form ns-sym file)]
                  (assoc! acc [path-key id] c))
                nil)
              nil m))))
      ;; Reference-site stamping under :states.
      (walk-states-tree (:states spec-form) [:states] acc ns-sym file)
      (persistent! acc))))
