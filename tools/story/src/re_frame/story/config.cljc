(ns re-frame.story.config
  "Compile-time configuration for re-frame2-story.

  The headline export is `enabled?` — the sentinel that gates every
  Story registration form per IMPL-SPEC §6.1.

  In a CLJS build, `enabled?` is a `goog-define`'d boolean that defaults
  to `true`. Production builds set
  `:closure-defines {re-frame.story.config/enabled? false}` (typically
  alongside `goog.DEBUG false`) and every registration macro expands to
  `nil`. Closure DCE then removes the entire registration-side ns graph
  from the production bundle.

  On the JVM side this is just a `def` — JVM consumers of Story (tests,
  REPL exploration) always see `enabled?` true. Production CLJS builds
  are the only environment that flips the flag.

  ## Why a separate ns

  The flag lives in its own ns so consumers can override it via
  `:closure-defines` keyed by a stable, framework-private symbol. The
  macros ns can't host the define directly because macros live in `.clj`
  and `goog-define` is `.cljs`-only.

  ## The elision contract this enables

  IMPL-SPEC §6 + Spec 009 §Production builds: PRESENT-in-control,
  ABSENT-in-release. The macros expand to one form when `enabled?` is
  true (the registration call); to `nil` when false. Production code
  that accidentally requires a stories ns sees the namespace load but
  with no registrations, every Story query returns empty."
  #?(:cljs (:require-macros [re-frame.story.config])))

;; ---- the compile-time flag -----------------------------------------------

#?(:cljs (goog-define ^boolean enabled?
           ;; @define {boolean}
           ;; Defaults to `true` (dev builds). Production sets this to false
           ;; via :closure-defines.
           true)
   :clj  (def ^:const enabled?
           "JVM-side: Story is always considered enabled. JVM consumers
           (tests, REPL) operate on the full registration surface. The
           compile-time elision only meaningfully applies under CLJS
           `:advanced`."
           true))

;; ---- macro-side access ---------------------------------------------------
;;
;; Macros emit code that checks the flag *at compile time* so the
;; expansion is the dead-code-friendly form: a single registration call
;; or a literal `nil`. The check uses `enabled?` as a normal JVM-side
;; value — at macro-expansion the goog-define hasn't fired yet (it's a
;; runtime CLJS define), so the macro layer effectively always-on.
;;
;; The actual DCE happens because the macro expansion threads through
;; `(when re-frame.story.config/enabled? ...)`: in CLJS-advanced mode
;; with `enabled?` defined as `false`, Closure sees the boolean
;; constant and removes the branch. The macro's job is to lay down that
;; `(when enabled? ...)` form; the closure compiler does the rest.

#?(:clj
   (defn elide-form
     "Wrap `expansion` in `(when re-frame.story.config/enabled? ...)` so
     the closure compiler can prune the registration call under
     `:advanced` builds where `enabled?` is defined as `false`.

     Returns the wrapped form."
     [expansion]
     `(when re-frame.story.config/enabled?
        ~expansion)))

;; ---- *global-args* (Stage 3, rf2-von3) ----------------------------------
;;
;; Per IMPL-SPEC §5.2 the args-precedence chain starts with `global-args`:
;; defaults the story-tool's host application sets at boot (theme, locale).
;; Stage 3's args-resolution layer reads this atom; `configure!` writes it.
;;
;; The atom is a plain Clojure data store — surviving `:advanced` builds
;; is harmless because it's empty by default. The mutation surface
;; (`configure!`) lives behind the `enabled?` gate at its call site.

(defonce
  ^{:doc "Atom holding the global args map. Per IMPL-SPEC §5.2 — Layer
         1 of the args-precedence chain. Defaults to `{}`; the host
         calls `re-frame.story/configure!` at boot to seed."}
  global-args
  (atom {}))

(defn set-global-args!
  "Replace the global args map. Story's `configure!` calls this."
  [m]
  (reset! global-args (or m {}))
  nil)

(defn get-global-args
  "Return the current global args map."
  []
  @global-args)
