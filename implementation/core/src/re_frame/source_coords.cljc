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

  ## Production elision (rf2-3un2g)

  Source-coord capture has TWO sinks:

    1. **Public registry-meta**: in dev the captured coords are merged
       into the registrar slot's metadata via [[merge-coords]] —
       `(rf/handler-meta kind id)` consumers (Xray Open-in-editor,
       re-frame-pair, IDE jump-to-source) read them from there. In
       CLJS production (`:advanced` + `goog.DEBUG=false`) [[merge-coords]]
       returns `user-meta` unchanged — the coord keys are stripped from
       the public meta. The `:column` literal in the macro-emitted
       coords-form additionally DCEs (the slim prod coords-form omits
       `:column` entirely).

    2. **Always-on error-coord registry**: [[remember-error-coords!]]
       populates [[error-coords-by-id]] at registration time. The
       error-emit substrate (`re-frame.error-emit/dispatch-on-error!`)
       looks up coords via [[error-coords-for]] when assembling the
       tight error-record and the structured policy-event — so
       Sentry/Honeybadger/Rollbar shippers still see source-line info
       in production builds where the trace surface is gone. This
       channel survives `goog.DEBUG=false` by construction.

  The DOM-annotation hook (per Tool-Pair §Source-mapping) is the dev-only
  piece, gated separately."
  (:require [re-frame.interop :as interop]
            [re-frame.source-coords.editor-uri :as editor-uri]))

#?(:clj (set! *warn-on-reflection* true))

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

(def ^:dynamic *pending-form-source*
  "Per-thread (per-call) handler form-source captured by `reg-event-{db,
  fx,ctx}` macros and consumed by `re-frame.events/register-event!`. nil
  outside a macro invocation.

  Per Spec 009 §`:rf.handler/source` and Xray Spec 021 §11.2 B.7
  stretch: the macros stamp the whole `(reg-event-X :id ...)` form as
  a string into the handler's registry metadata under
  `:rf.handler/source` so Xray's Event panel can render the source
  inline (no need to leave the browser to read what code ran).

  CLJS production elision: the macro emission wraps the binding-value
  in an `(if interop/debug-enabled? <source-string> nil)` gate so
  Closure constant-folds the gate to `nil` under `:advanced` +
  `goog.DEBUG=false` and DCEs both the literal source string and the
  `:rf.handler/source` keyword's reachability from this slot. The
  elision probe asserts the absence; per-string DCE depends on no
  other surface keeping the same byte sequence reachable.

  JVM-side: always captured. The bundle-size argument doesn't apply on
  the JVM; SSR / test / tooling builds can read
  `(:rf.handler/source (rf/handler-meta :event id))` directly."
  nil)

(defn merge-coords
  "Merge `*pending-coords*` into `user-meta`. User-supplied :ns / :line
  / :file override auto-captured values per Spec 001. Returns user-meta
  unchanged when no coords are pending (programmatic registration,
  REPL eval without the macro path).

  Per rf2-3un2g §Production elision: in CLJS `:advanced` +
  `goog.DEBUG=false` builds (and JVM SSR with `re-frame.debug=false`)
  this fn returns `user-meta` unchanged regardless of any pending
  coords binding. Coord-keys are stripped from the public registry-meta
  in production; the always-on `error-coords-by-id` parallel registry
  (see [[remember-error-coords!]]) carries them through to the
  error-emit substrate for Sentry-style observability."
  [user-meta]
  (if-not interop/debug-enabled?
    ;; Production: strip the coord-keys from public meta. The always-on
    ;; error-coords parallel registry retains them for error-emit
    ;; observability — see [[remember-error-coords!]] / [[error-coords-for]].
    (or user-meta {})
    (let [coords *pending-coords*]
      (if coords
        (merge coords (or user-meta {}))
        (or user-meta {})))))

;; ---- always-on error-coord registry (rf2-3un2g) --------------------------
;;
;; The parallel registry that retains source-coords in production builds.
;; Populated unconditionally at registration time via [[remember-error-
;; coords!]]; the error-emit substrate reads it via [[error-coords-for]]
;; when assembling the tight error-record passed to corpus-wide listener
;; fans (Sentry / Honeybadger / Rollbar) and the per-frame `:on-error`
;; policy event. Survives `:advanced` + `goog.DEBUG=false` — the
;; namespace and the atom are unconditional; only the dev-side merge
;; into public registry-meta is elided.

(defonce
  ^{:doc "kind → id → coords-map. Atomic. Per-process. Mirrors the
          registrar shape so error-emit can pivot on `(kind, id)`. The
          values are coord-maps (`:rf/source-coord-meta` per
          Spec-Schemas — `:ns` / `:file` / `:line`; `:column` is dev-
          only). Survives production elision so Sentry-style shippers
          see source-line info even when the trace surface is gone."}
  error-coords-by-id
  (atom {}))

(defn remember-error-coords!
  "Store coord-map under `[kind id]` in the always-on parallel registry.
  Called by `re-frame.registrar/register!` from any path where
  `*pending-coords*` is bound (the public reg-* macro path). In CLJS
  production builds the coord-map's `:column` slot is absent — the
  prod-side macro emission omits it; only `:ns`/`:file`/`:line` ride
  through. Returns the stored coord-map.

  Per rf2-3un2g §Always-on error-coord registry."
  [kind id coords]
  (when (and kind id coords)
    (swap! error-coords-by-id assoc-in [kind id] coords))
  coords)

(defn error-coords-for
  "Look up the stored source-coord map for `[kind id]`. Returns nil when
  no coords were captured for that pair (programmatic registration, REPL
  eval that bypassed the macro path). The error-emit substrate uses this
  to stamp `:source-coord` on the tight record + policy-event in BOTH
  dev AND production. Per rf2-3un2g."
  [kind id]
  (get-in @error-coords-by-id [kind id]))

(defn forget-error-coords!
  "Clear the parallel registry. Test fixtures use this between cases.
  Mirrors `registrar/clear-all!`. Per rf2-3un2g."
  []
  (reset! error-coords-by-id {})
  nil)

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

  The CLJS analyzer never binds Clojure's `*file*` during macro
  expansion, so reading `*file*` alone returns the JVM
  `\"NO_SOURCE_PATH\"` sentinel under CLJS. Form-meta `:file` is the
  portable answer."
  [form-meta file]
  (let [meta-file (:file form-meta)]
    (cond
      (not (no-source-path? meta-file)) meta-file
      (not (no-source-path? file))      file
      :else                              nil)))

;; ---- :file absolutisation (rf2-wvsxg) ------------------------------------
;;
;; Both shadow-cljs and the JVM compiler put the **classpath-relative**
;; portion of a source file in the form's `:file` slot — for
;; `tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs` (whose
;; classpath root is `tools/xray/src/`), the form-meta `:file` is just
;; `"day8/re_frame2_xray/views/edn_inspector.cljs"`. The source-root
;; segment is invisible to anyone consuming the captured coord, which
;; defeats Story / Xray's `open-in-editor` chip — `:project-root` plus
;; the classpath-relative tail produces the wrong on-disk path
;; whenever the project-root isn't the same as the classpath root that
;; resolved the file.
;;
;; Live failure shape (rf2-wvsxg):
;;   project-root  C:/Users/me/code/my-app/tools/xray/testbeds
;;   :file         day8/re_frame2_xray/views/edn_inspector.cljs
;;   composed      C:/.../tools/xray/testbeds/day8/.../edn_inspector.cljs
;;   actual        C:/.../tools/xray/src/day8/.../edn_inspector.cljs
;;
;; The fix: at macro-expansion time on the JVM, resolve the classpath-
;; relative `:file` to its on-disk URL via the context class-loader and
;; bake the **absolute on-disk path** into the emitted coord. The
;; downstream URI builder's `compose-path` already detects absolute
;; paths and passes them through unchanged, so a coord baked with an
;; absolute `:file` ships the right URI regardless of which
;; `:project-root` the host configured. Absolutisation runs once per
;; macro expansion (JVM-side); the CLJS runtime sees a literal string
;; (cheap), and production builds elide both the literal and the
;; surrounding coord-form per the existing rf2-3un2g gate.
;;
;; Failure modes that fall through to the unchanged input:
;;   - Already-absolute path (e.g. a JVM-compile `*file*` that
;;     happened to be absolute, or a synthetic coord with an absolute
;;     `:file`): detected by `editor-uri/absolute-path?` — the same
;;     predicate `editor-uri/compose-path` uses (leading `/`, leading
;;     drive letter, leading `file:` scheme, leading backslash).
;;   - File not resolvable on classpath (REPL-eval forms with synthetic
;;     `:file`, a test fixture's fabricated path, a path under a
;;     classpath root the JVM doesn't have when the macro expands):
;;     pass through unchanged; the downstream URI builder still gets a
;;     coord, just one whose `:project-root` join is the legacy
;;     behaviour.
;;   - CLJS-side calls (no class-loader access): no-op pass-through;
;;     the macro path is JVM-side by construction so this branch only
;;     fires when callers reach the fn from CLJS runtime code (rare).

#?(:clj
   (defn ^:private context-class-loader ^ClassLoader []
     (.getContextClassLoader (Thread/currentThread))))

#?(:clj
   (defn absolutise-file
     "JVM-only. Resolve a classpath-relative source file path to its
     absolute on-disk path via the context class-loader. Returns the
     input unchanged when the path is already absolute, when classpath
     resolution fails (no resource found, non-`file:` URL), or when
     `path` is nil / blank.

     Used by `coords-form` / `prod-coords-form` / `form-coords` at
     macro-expansion time to bake an absolute `:file` value into each
     emitted source-coord literal — defeating the source-root
     ambiguity that bites multi-source-path builds (shadow-cljs lists
     both `tools/xray/src` and `tools/xray/testbeds` for the panel-
     gallery testbed; the form-meta's classpath-relative `:file` carries
     no signal about which source root resolved it).

     Per rf2-wvsxg."
     [path]
     (if (or (nil? path) (.isEmpty ^String path) (editor-uri/absolute-path? path))
       path
       (try
         (if-let [url (.getResource (context-class-loader) path)]
           (if (= "file" (.getProtocol url))
             ;; URL paths come out URL-encoded (e.g. `%20` for spaces)
             ;; and use `/` on every platform. URLDecoder handles the
             ;; encoding; the result on Windows is `/C:/Users/...` so
             ;; strip a leading slash before a drive-letter to get the
             ;; canonical `C:/Users/...` shape `compose-path` already
             ;; handles.
             (let [decoded (java.net.URLDecoder/decode (.getPath url) "UTF-8")]
               (if (and (> (.length ^String decoded) 2)
                        (= \/ (.charAt ^String decoded 0))
                        (= \: (.charAt ^String decoded 2)))
                 (.substring ^String decoded 1)
                 decoded))
             ;; jar:/zip:/http: — leave the input unchanged; an
             ;; in-jar source file isn't editable on disk anyway.
             path)
           ;; Not on classpath (REPL eval, synthetic coord, test
           ;; fabrication): pass through.
           path)
         (catch Throwable _
           ;; Defensive — classpath probing must never break macro
           ;; expansion. Any failure → preserve original behaviour.
           path)))))

(defn coords-form
  "Construct the compile-time `(cond-> {:ns 'sym} ...)` form that every
  reg-* macro emits as the value of its `*pending-coords*` binding.

  `form-meta` is `(meta &form)`; `file` is `*file*`; `ns-sym` is the
  consumer's namespace symbol. The returned form is syntax-quote-safe
  data the caller splices into its expansion.

  :file picks the form-meta value over `*file*` and rejects the
  `\"NO_SOURCE_PATH\"` sentinel via `resolve-file`.

  Per rf2-3un2g §Production elision: callers SHOULD wrap the dev
  emission alongside [[prod-coords-form]] under
  `(if interop/debug-enabled? <dev> <prod>)` so Closure DCEs the dev
  shape (with `:column`) under `:advanced` + `goog.DEBUG=false`. The
  `with-coords-form` / `expand-reg-machine` helpers do this internally;
  per-element machine stamping and call-site stamping handle elision
  through their own outer gates and call this fn directly.

  Per rf2-wvsxg: when running on the JVM (the macro-expansion side),
  the picked `:file` is fed through [[absolutise-file]] to resolve the
  classpath-relative form-meta `:file` to its absolute on-disk path.
  Downstream URI builders' `compose-path` detects absolute paths and
  passes them through unchanged, so the emitted coord ships the right
  on-disk path regardless of the host's `:project-root` configuration."
  [form-meta file ns-sym]
  (let [chosen-file (resolve-file form-meta file)
        chosen-file #?(:clj  (when chosen-file (absolutise-file chosen-file))
                       :cljs chosen-file)]
    `(cond-> {:ns '~ns-sym}
       ~chosen-file         (assoc :file ~chosen-file)
       ~(:line form-meta)   (assoc :line ~(:line form-meta))
       ~(:column form-meta) (assoc :column ~(:column form-meta)))))

#?(:clj
   (defn prod-coords-form
     "Slim production-side variant of [[coords-form]]: omits `:column`.
     Per rf2-3un2g — `:column` is dev-tooling-only (IDE jump-to-source
     refinement); Sentry-style observability needs only `:ns`/`:file`/
     `:line`. Emitting the slim form under the prod branch of an
     `(if interop/debug-enabled? ...)` lets Closure DCE the dev coords
     literal (with `:column`) under `:advanced` + `goog.DEBUG=false`,
     so the bundle ships the slimmer payload only.

     Caller wraps:

         `(if re-frame.interop/debug-enabled?
            ~(coords-form form-meta file ns-sym)
            ~(prod-coords-form form-meta file ns-sym))

     Both branches use `cond->` so absent keys (e.g. nil `:line` on a
     programmatic synthesis) elide cleanly.

     Per rf2-wvsxg: the picked `:file` is absolutised via
     [[absolutise-file]] at macro-expansion time so the downstream URI
     builder receives an absolute on-disk path regardless of which
     source-root resolved the file on shadow-cljs's classpath."
     [form-meta file ns-sym]
     (let [chosen-file (resolve-file form-meta file)
           chosen-file (when chosen-file (absolutise-file chosen-file))]
       `(cond-> {:ns '~ns-sym}
          ~chosen-file       (assoc :file ~chosen-file)
          ~(:line form-meta) (assoc :line ~(:line form-meta))))))

;; ---- per-element spec stamping -------------------------------------------
;;
;; Per Spec 005 §Source-coord stamping: the `reg-machine` macro
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

#?(:clj
   (do

(defn ^:private form-coords
  "Read source coords off a Clojure form's metadata. Forms the reader has
  decorated (lists, vectors, maps, symbols) carry `:line` / `:column` from
  the source position. Returns nil when the form has no positional meta.

  The same `:file` resolution as the call-site path applies: prefer
  the reader-attached `:file` on the form's metadata over the macro's
  `*file*` arg, and reject the `\"NO_SOURCE_PATH\"` sentinel.

  Per rf2-wvsxg: the picked `:file` is absolutised via
  [[absolutise-file]] (JVM macro-expansion path) so per-machine-element
  coords ship absolute on-disk paths matching the reg-* macro coords."
  [form ns-sym file]
  (let [form-meta    (meta form)
        chosen-file  (resolve-file form-meta file)
        chosen-file  (when chosen-file (absolutise-file chosen-file))]
    (when (and form-meta (or (:line form-meta) (:column form-meta)))
      (cond-> {:ns ns-sym}
        chosen-file        (assoc :file chosen-file)
        (:line form-meta)  (assoc :line (:line form-meta))
        (:column form-meta) (assoc :column (:column form-meta))))))

(defmacro ^:private stamp!
  "Compile-time helper for the machine-spec walker. Reads source coords off
  `form` and, when any are present, stamps them into the transient
  accumulator at `path`. Inlines to the equivalent
  `(when-let [c (form-coords form ns-sym file)] (assoc! acc path c))` so
  the imperative-mutation shape `walk-states-tree` relies on for
  macro-expansion-time performance is preserved.

  Lexical-capture contract: callers must have `acc` (a transient map),
  `ns-sym` (a symbol), and `file` (a string or nil) in scope. The macro
  is private to this namespace and used only inside `walk-states-tree`
  and `walk-machine-spec`, both of which bind those three locals.

  Hides the repetitive shape behind a single two-arg call at every
  reference-site stamp."
  [path form]
  `(when-let [c# (form-coords ~form ~'ns-sym ~'file)]
     (assoc! ~'acc ~path c#)))

(defn- walk-states-tree
  "Recursively walk the literal `:states` map. `path` accumulates the
  spec-path from the spec's root. Adds entries into the mutable `acc`
  transient for each captured reference site / state-node.

  Note on style: this walker is mutation-heavy (transient `acc` threaded
  through nested `reduce-kv` / `doseq` with `assoc!` via the `stamp!`
  macro) rather than the more functional shape of a visitor that returns
  collected entries. The imperative shape is deliberate — this code runs
  at macro-expansion time and gets called on every `reg-machine` form.
  Transients avoid the per-state allocation cost of building intermediate
  persistent maps during expansion. The result is materialised once at
  the edge in `walk-machine-spec`. Refactoring to a fully declarative
  visitor is feasible but would need to be benchmarked against current
  compile-time numbers before adoption."
  [states-form path acc ns-sym file]
  (when (map? states-form)
    (reduce-kv
      (fn [acc state-id node]
        (let [node-path (conj path state-id)]
          (stamp! node-path node)
          (when (map? node)
            ;; :entry / :exit references
            (when-let [e (:entry node)]
              (stamp! (conj node-path :entry) e))
            (when-let [e (:exit node)]
              (stamp! (conj node-path :exit) e))
            ;; :spawn {:on-spawn ...}
            (when-let [inv (:spawn node)]
              (stamp! (conj node-path :spawn) inv)
              (when (map? inv)
                (when-let [os (:on-spawn inv)]
                  (stamp! (conj node-path :spawn :on-spawn) os))))
            ;; :on transitions — map of event-id → transition-or-vector
            (when-let [on-map (:on node)]
              (when (map? on-map)
                (reduce-kv
                  (fn [_ ev-id t]
                    (let [tp (conj node-path :on ev-id)]
                      (stamp! tp t)
                      (cond
                        (map? t)
                        (do
                          (when-let [g (:guard t)]  (stamp! (conj tp :guard) g))
                          (when-let [a (:action t)] (stamp! (conj tp :action) a)))
                        (vector? t)
                        (doseq [[i tx] (map-indexed vector t)
                                :when (map? tx)]
                          (let [tp' (conj tp i)]
                            (stamp! tp' tx)
                            (when-let [g (:guard tx)]  (stamp! (conj tp' :guard) g))
                            (when-let [a (:action tx)] (stamp! (conj tp' :action) a)))))
                      nil))
                  nil on-map)))
            ;; :always — vector of transition maps
            (when-let [always (:always node)]
              (when (vector? always)
                (doseq [[i tx] (map-indexed vector always)
                        :when (map? tx)]
                  (let [tp (conj node-path :always i)]
                    (stamp! tp tx)
                    (when-let [g (:guard tx)]  (stamp! (conj tp :guard) g))
                    (when-let [a (:action tx)] (stamp! (conj tp :action) a))))))
            ;; :after — map of delay → target-or-transition
            (when-let [after (:after node)]
              (when (map? after)
                (reduce-kv
                  (fn [_ delay t]
                    (let [tp (conj node-path :after delay)]
                      (stamp! tp t)
                      (when (map? t)
                        (when-let [a (:action t)] (stamp! (conj tp :action) a)))
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
                (stamp! [path-key id] fn-form)
                nil)
              nil m))))
      ;; Reference-site stamping under :states.
      (walk-states-tree (:states spec-form) [:states] acc ns-sym file)
      (persistent! acc))))

;; ---- machine guard / action form-source capture (rf2-ypu5i) --------------
;;
;; Mirrors `*pending-form-source*` for the reg-event-* path (rf2-xgfuy) but
;; per-id under the `:guards` / `:actions` slots of a literal machine spec.
;; The `reg-machine` macro walks the spec at expansion time, `pr-str`s each
;; fn-form, and emits the per-id source-string map literal into the
;; expansion under the spec's `:rf.machine/handler-source` key. The
;; `core-machines/reg-machine-impl` runtime then reads this key and writes
;; the per-(machine-id, id) `:rf.handler/source` slots into the registrar
;; under the `:machine-guard` / `:machine-action` kinds, so tooling can
;; read `(rf/handler-meta :machine-guard [<machine-id> <guard-id>])` and
;; receive `{:rf/guard-id <guard-id> :rf.handler/source <pr-str-of-fn> ...}`.
;;
;; The :guards / :actions values may be plain fn-forms `(fn [ctx] …)`
;; OR keyword references (a guard-id keyword shared between transitions
;; that re-uses the same predicate). Only the fn-literal entries have
;; source to capture; keyword references are skipped here — the
;; reference-site coords already live in `walk-machine-spec`.

(defn walk-machine-handler-source
  "Compile-time helper. Walk a literal machine-spec form and return a
  map of the form

      {:guards  {<guard-id>  <pr-str-of-fn-form>, ...}
       :actions {<action-id> <pr-str-of-fn-form>, ...}}

  Only `(fn ...)` / `(fn* ...)` literal entries under `:guards` and
  `:actions` contribute — keyword-reference entries are skipped (their
  definition lives under the referenced id). Returns `{}` when the spec
  form is not a map literal (a symbol, a let-bound expr).

  JVM-only — runs on the Clojure side of the `reg-machine` macro.
  Returns a plain map literal the macro splices into its expansion;
  under `goog.DEBUG=false` the closure compiler DCEs the entire literal
  (every value is a `pr-str` of source text — a literal string
  reachable only from the splice site)."
  [spec-form]
  (if-not (map? spec-form)
    {}
    (let [walk-slot
          (fn [slot-key]
            (when-let [m (get spec-form slot-key)]
              (when (map? m)
                (reduce-kv
                  (fn [acc id fn-form]
                    ;; Skip keyword references — only literal fn-forms
                    ;; carry source. Symbols / let-bound exprs that
                    ;; aren't literal `(fn ...)` forms also contribute
                    ;; the surface `pr-str` here (best-effort; tools
                    ;; rendering source as code can still show "(fn-ref)").
                    (if (keyword? fn-form)
                      acc
                      (assoc acc id (pr-str fn-form))))
                  {} m))))
          guards  (walk-slot :guards)
          actions (walk-slot :actions)]
      (cond-> {}
        (seq guards)  (assoc :guards  guards)
        (seq actions) (assoc :actions actions)))))

   )) ;; end #?(:clj (do ...))
