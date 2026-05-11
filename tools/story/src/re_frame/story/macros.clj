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
  `re-frame.core/reg-event-db`'s coord-capture pattern."
  [form-meta file ns-sym]
  `(cond-> {:ns '~ns-sym}
     ~file               (assoc :file ~file)
     ~(:line form-meta)   (assoc :line ~(:line form-meta))
     ~(:column form-meta) (assoc :column ~(:column form-meta))))

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
