(ns re-frame.story.macros
  "Macro-expansion helpers for re-frame2-story.

  This namespace is `:clj`-only — these are the helpers the actual
  `defmacro` forms in `re-frame.story` call from inside their bodies.
  The defmacros themselves live in `re-frame.story` so end-users
  writing `(story/reg-story ...)` find `reg-story` as a Var on the
  public ns (matching the pattern `re-frame.core` uses for
  `reg-event`).

  Per `001-Authoring.md` §Registration macros every emitted form threads through
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

  Both the picking rule and the `:file` ABSOLUTISATION are core's:
  `coords-form` delegates wholesale to
  `re-frame.source-coords/coords-form`, the same stamp every
  `re-frame.core` `reg-*` macro emits. See `coords-form` for what that
  buys and why Story must not re-derive it.

  ## Form-B `:variants` desugaring

  `expand-reg-story` checks for a literal `:variants` map in the body
  and, if present, emits N independent `reg-variant*` calls as siblings
  of the parent `reg-story*` call. Per `001-Authoring.md` §Registration macros this preserves
  hot-reload-by-variant: each variant is a separate top-level form so
  save-and-reload only invalidates the changed slot."
  (:require [re-frame.source-coords :as rf.source-coords]))

;; ---- source-coord helper -------------------------------------------------

(defn coords-form
  "Construct the compile-time coord MAP LITERAL that the macro expansion
  assigns to `re-frame.story.registrar/*pending-coords*`. `form-meta` is
  the value of `(meta &form)` from the calling macro; `file` is `*file*`
  from the macro's compile environment; `ns-sym` is the consumer's
  namespace symbol.

  Delegates wholesale to `re-frame.source-coords/coords-form` — the
  canonical stamp every `re-frame.core` `reg-*` macro emits. Story
  authors a separate macro pipeline, but the coordinate it stamps is the
  same artefact core stamps and is read back by the same consumers
  (`re-frame.source-coords.editor-uri`, the Story / Xray open-in-editor
  chips, the Pair MCP source surface), so the derivation belongs in one
  place. Three properties come with that reuse, and Story previously
  carried only the first:

  1. **`:file` picking.** `(:file form-meta)` wins over `*file*`, and the
     `\"NO_SOURCE_PATH\"` sentinel is rejected from either source (with
     `:file` omitted outright when both resolve to it — better no `:file`
     than a poison value that defeats jump-to-source). This is rf2-ulxi:
     `cljs.analyzer/macroexpand-1*` binds `*cljs-file*`, not Clojure's
     `*file*`, so under CLJS `*file*` retains the JVM compiler's
     `\"NO_SOURCE_PATH\"` default while tools.reader HAS stamped `{:file
     ...}` onto every collection form's metadata. Form-meta is the answer
     that survives both compilation hosts.

  2. **`:file` absolutisation, at MACRO-EXPANSION time** (rf2-wvsxg).
     Both shadow-cljs and the JVM compiler put only the
     CLASSPATH-RELATIVE portion of the source file in `:file` —
     `counter_with_stories/stories.cljs`, never the on-disk path — and
     which source root resolved it is invisible to every consumer.
     `re-frame.source-coords/absolutise-file` resolves it through the
     context class-loader on the JVM side of the expansion and bakes the
     absolute path into the emitted literal.

     This is not optional polish for Story. The open-in-editor client
     PREFERS the dev-server endpoint and FALLS BACK to an `editor://`
     URI on any non-2xx — including the 422 the endpoint answers when
     `launch-editor` declines a coordinate-bearing request. With a
     relative `:file` that fallback ships
     `windsurf://file/counter_with_stories/stories.cljs:196:3`, which no
     editor's scheme handler can resolve, and the chip silently misses.

     Note where the resolution happens: ONCE, in Clojure, while the macro
     expands. The CLJS runtime only ever sees a baked literal string.
     Story does NOT discover a source root in the browser — the
     repository's browser-side checkout-root pipeline was retired
     precisely because the endpoint plus this compile-time stamp make it
     redundant, and nothing here reintroduces it. Hosts that need a root
     at runtime (static exports, non-shadow hosts, in-jar sources whose
     classpath probe finds no `file:` URL) still set the public
     `:rf.story/project-root` knob, which `editor-uri/compose-path`
     applies only to a `:file` that is still relative.

  3. **Expansion-time literal, not an emitted runtime `cond->`**
     (rf2-i3dvj evaluation-order transparency): the returned value is a
     map, not a form that builds one.

  Story registrations are dev-only — every expansion sits under
  `(when re-frame.story.config/enabled? ...)` and elides under
  `:advanced` — so there is no production coord-form counterpart to pick
  between here the way core's `reg-*` macros must."
  [form-meta file ns-sym]
  (rf.source-coords/coords-form form-meta file ns-sym))

(defn variant-id-for
  "Build the variant id from a story id and a variant-name key.

  - story-id `:story.auth.login-form`, variant-name `:empty`
    → `:story.auth.login-form/empty`

  Per /spec/007-Stories.md §Canonical id grammar."
  [story-id variant-name]
  (when-not (keyword? story-id)
    (throw (ex-info ":rf.error/story-bad-id"
                    {:rf.error/id :rf.error/story-bad-id
                     :where    'rf.story/reg-story
                     :recovery :fix-registration
                     :reason   "re-frame2-story: story id must be a keyword"
                     :story-id story-id})))
  (when-not (keyword? variant-name)
    (throw (ex-info ":rf.error/story-bad-variant-name"
                    {:rf.error/id :rf.error/story-bad-variant-name
                     :where    'rf.story/reg-story
                     :recovery :fix-registration
                     :reason   (str "re-frame2-story: variant-name in :variants map "
                                    "must be a keyword (got " (pr-str variant-name) ")")
                     :variant-name variant-name})))
  (let [story-str (subs (str story-id) 1)]    ; strip leading colon
    (keyword story-str (name variant-name))))

(defn emit-reg
  "Emit the canonical `(when enabled? (binding [*pending-coords* coords]
  (<reg-fn> id body)))` wrapper. `coords` is a pre-computed coords FORM
  (per `coords-form`); `reg-fn-sym` is a fully-qualified registrar helper
  like `re-frame.story.registrar/reg-story*`. The single place the
  elision-gate + source-coord binding shape is laid down."
  [coords reg-fn-sym id body]
  `(when re-frame.story.config/enabled?
     (binding [re-frame.story.registrar/*pending-coords* ~coords]
       (~reg-fn-sym ~id ~body))))

(defn gen-reg-call
  "Emit a single registration form for `reg-fn-sym`. `reg-fn-sym` is a
  fully-qualified symbol like `re-frame.story.registrar/reg-story*`.
  `form-meta` is the caller's `(meta &form)`; `file` is the caller's
  `*file*`; `ns-sym` is the consumer's `(ns-name *ns*)`."
  [form-meta file ns-sym reg-fn-sym id body]
  (emit-reg (coords-form form-meta file ns-sym) reg-fn-sym id body))

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
        story-call  (emit-reg coords
                              're-frame.story.registrar/reg-story*
                              id body-form)]
    (if variants
      `(do
         ~story-call
         ~@(for [[v-name v-body] variants]
             (let [v-id (variant-id-for id v-name)]
               (emit-reg coords
                         're-frame.story.registrar/reg-variant*
                         v-id v-body))))
      story-call)))
