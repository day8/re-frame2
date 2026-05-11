(ns re-frame.story.macros
  "Macro-expansion helpers for re-frame2-story.

  This namespace is `:clj`-only — these are the helpers the actual
  `defmacro` forms in `re-frame.story` call from inside their bodies.
  The defmacros themselves live in `re-frame.story` so end-users
  writing `(story/reg-story ...)` find `reg-story` as a Var on the
  public ns (matching the pattern `re-frame.core` uses for
  `reg-event-db`).

  Per IMPL-SPEC §6.1 every emitted form threads through
  `(when re-frame.story.config/enabled? ...)` so the closure compiler
  elides the registration call under `:advanced` builds with
  `enabled?` set to `false`. The dev/prod expand split is the
  PRESENT-in-control / ABSENT-in-release pattern Spec 009 locks for
  instrumentation.

  ## Source-coord stamping

  Each expansion captures `:line` / `:column` / `:file` / `:ns` from
  the caller's `&form` and `*file*`, and binds them into
  `re-frame.story.registrar/*pending-coords*` around the runtime helper
  call. The helper merges the coords into the registered body under
  the `:source` key — tools / 10x / IDE jump-to-source consume this via
  `(story/handler-meta kind id)`.

  `:file` resolution prefers `(:file (meta &form))` over `*file*` —
  see `coords-form` for the rationale (rf2-ulxi). The short version:
  the CLJS analyzer never binds Clojure's `*file*` during macro
  expansion, so reading it returns `\"NO_SOURCE_PATH\"`; the reader-
  attached `:file` on the form's metadata is the portable answer.

  ## Form-B `:variants` desugaring

  `expand-reg-story` checks for a literal `:variants` map in the body
  and, if present, emits N independent `reg-variant*` calls as siblings
  of the parent `reg-story*` call. Per IMPL-SPEC §4.9 this preserves
  hot-reload-by-variant: each variant is a separate top-level form so
  save-and-reload only invalidates the changed slot.")

;; ---- source-coord helper -------------------------------------------------

(defn coords-form
  "Construct the literal map that the macro expansion will assign to
  `re-frame.story.registrar/*pending-coords*`. `form-meta` is the value
  of `(meta &form)` from the calling macro; `file` is `*file*` from the
  macro's compile environment; `ns-sym` is the consumer's namespace
  symbol.

  Returns a compile-time Clojure form (a `cond->` expression) that
  evaluates to the map at runtime. Mirrors
  `re-frame.core/reg-event-db`'s coord-capture pattern.

  ## :file resolution (rf2-ulxi)

  `:file` comes from `(:file form-meta)` first — the CLJS analyzer reads
  source files via `tools.reader/indexing-push-back-reader` with the
  filename argument set, and tools.reader stamps `{:file ...}` on every
  collection-form's metadata. Falling back to `*file*` covers the JVM
  compilation path where `clojure.lang.Compiler` binds `*file*` itself
  but the reader does NOT attach `:file` to form metadata.

  Reading `*file*` alone is wrong for CLJS because `cljs.analyzer/
  macroexpand-1*` binds `*cljs-file*` (not Clojure's `*file*`) during
  macro expansion — so `*file*` retains whatever the JVM compiler last
  set it to, which is `\"NO_SOURCE_PATH\"` on a fresh classloader. The
  form-meta path is the portable answer because the reader-attached
  `:file` survives across both compilation hosts.

  Also rejects the `\"NO_SOURCE_PATH\"` sentinel from either source — if
  *both* sources resolve to the sentinel, omit `:file` entirely (better
  no `:file` than a poison value that defeats jump-to-source)."
  [form-meta file ns-sym]
  (let [meta-file  (:file form-meta)
        no-source? #(or (nil? %) (= "NO_SOURCE_PATH" %))
        chosen     (cond
                     (not (no-source? meta-file)) meta-file
                     (not (no-source? file))      file
                     :else                        nil)]
    `(cond-> {:ns '~ns-sym}
       ~chosen              (assoc :file ~chosen)
       ~(:line form-meta)   (assoc :line ~(:line form-meta))
       ~(:column form-meta) (assoc :column ~(:column form-meta)))))

(defn variant-id-for
  "Build the variant id from a story id and a variant-name key.

  - story-id `:story.auth.login-form`, variant-name `:empty`
    → `:story.auth.login-form/empty`

  Per spec/007 §Canonical id grammar."
  [story-id variant-name]
  (when-not (keyword? story-id)
    (throw (ex-info "re-frame2-story: story id must be a keyword"
                    {:story-id story-id})))
  (when-not (keyword? variant-name)
    (throw (ex-info (str "re-frame2-story: variant-name in :variants map "
                         "must be a keyword (got " (pr-str variant-name) ")")
                    {:variant-name variant-name})))
  (let [story-str (subs (str story-id) 1)]    ; strip leading colon
    (keyword story-str (name variant-name))))

(defn gen-reg-call
  "Emit a single `(when enabled? (binding [*pending-coords* coords]
  (registrar/<reg-fn>* id body)))` form. `reg-fn-sym` is a fully-qualified
  symbol like `re-frame.story.registrar/reg-story*`. `form-meta` is the
  caller's `(meta &form)`; `file` is the caller's `*file*`; `ns-sym` is
  the consumer's `(ns-name *ns*)`."
  [form-meta file ns-sym reg-fn-sym id body]
  `(when re-frame.story.config/enabled?
     (binding [re-frame.story.registrar/*pending-coords*
               ~(coords-form form-meta file ns-sym)]
       (~reg-fn-sym ~id ~body))))

;; ---- reg-story Form-B desugaring -----------------------------------------

(defn expand-reg-story
  "Macro-side expansion for `reg-story`. Handles the Form-B `:variants`
  sugar: if `metadata` is a literal map with `:variants`, emit a `do`
  block with the parent registration plus N independent `reg-variant*`
  calls. Otherwise emit a single registration call.

  Returns the syntax-quoted expansion."
  [form-meta file ns-sym id metadata]
  (let [coords      (coords-form form-meta file ns-sym)
        literal-map (when (map? metadata) metadata)
        variants    (when literal-map (:variants literal-map))
        ;; The runtime helper expects the parent slice (no :variants);
        ;; for literal maps strip it at expansion time, for non-literal
        ;; metadata punt to runtime (helper drops :variants).
        body-form   (if variants
                      (dissoc literal-map :variants)
                      metadata)
        story-call  `(when re-frame.story.config/enabled?
                       (binding [re-frame.story.registrar/*pending-coords*
                                 ~coords]
                         (re-frame.story.registrar/reg-story* ~id ~body-form)))]
    (if variants
      `(do
         ~story-call
         ~@(for [[v-name v-body] variants]
             (let [v-id (variant-id-for id v-name)]
               `(when re-frame.story.config/enabled?
                  (binding [re-frame.story.registrar/*pending-coords*
                            ~coords]
                    (re-frame.story.registrar/reg-variant* ~v-id ~v-body))))))
      story-call)))
