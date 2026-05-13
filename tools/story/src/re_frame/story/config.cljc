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

;; ---- the static-export flag (rf2-8wgpm) ---------------------------------
;;
;; A second goog-define that flips Story's chrome into "static export"
;; mode — the bundle is intended to be served as a publishable HTML
;; playground (Storybook 8 / Histoire / Ladle's `story build` equivalent)
;; rather than mounted under a live `shadow-cljs watch` dev session.
;;
;; In static mode the shell:
;;   - Does NOT start the registrar-fingerprint hot-reload poll (no
;;     dev-time registrations will land, so the 500ms setInterval is
;;     wasted work and emits a periodic ratom-write that thrashes the
;;     React tree).
;;   - Suppresses the first-visit help overlay by default (visitors
;;     arriving at a published docs site already know what they're
;;     looking at; the overlay is a dev-time onboarding affordance).
;;
;; Causa preloads are already absent from `shadow-cljs release` builds
;; (per shadow-cljs's docs on the :devtools key, `:preloads` is
;; honoured by `watch`/`compile` but NOT by `release`), so no flag-side
;; gate is required there.
;;
;; The flag is a CLJS-side `goog-define` defaulting to false; on the
;; JVM it's a plain `def` because the JVM has no notion of a static
;; export — JVM tests always see `static-mode?` as false.

#?(:cljs (goog-define ^boolean static-mode?
           ;; @define {boolean}
           ;; Defaults to `false`. The `story:build` invocation (per
           ;; tools/story/spec/013-Static-Build.md) sets this to true
           ;; via :closure-defines so the shell drops its dev-time
           ;; affordances.
           false)
   :clj  (def ^:const static-mode?
           "JVM-side: never in static mode. JVM consumers always operate
           in the development-flavoured branch."
           false))

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

;; ---- *editor* (rf2-evgf5 — 'Open in editor' affordance) ----------------
;;
;; Per Spec 005-SOTA-Features.md §'Open in editor' per variant, every
;; source-coord-bearing Story surface (variant canvas title, per-test
;; failure detail, etc.) renders a small 'Open' button that launches the
;; user's editor at the registered file:line. The user picks the editor
;; once at boot via `(story/configure! {:editor :cursor})`; this atom
;; holds the chosen editor.
;;
;; Defaults to `:vscode` — the most-installed editor in 2026 (Stack
;; Overflow Developer Survey 2025 + JetBrains DevEcosystem 2025).
;; Accepts the keywords `:vscode` / `:cursor` / `:idea` plus the
;; `{:custom "<uri-template>"}` form (see
;; `re-frame.source-coords.editor-uri/editor-uri`).
;;
;; The atom is plain data; production builds with `enabled?` false DCE
;; the UI shell that reads it, so the editor preference itself is
;; harmless if it survives into a release bundle.

(defonce
  ^{:doc "Atom holding the editor preference. Default `:vscode`. Set
         by `re-frame.story/configure!` via the `:editor` key. Read by
         the UI shell's open-in-editor buttons."}
  editor
  (atom :vscode))

(defn set-editor!
  "Replace the editor preference. Accepts `:vscode` / `:cursor` /
  `:idea` / `{:custom \"<template>\"}` / nil (resets to `:vscode`).
  Story's `configure!` calls this."
  [e]
  (reset! editor (or e :vscode))
  nil)

(defn get-editor
  "Return the current editor preference."
  []
  @editor)

;; ---- *show-sensitive?* (rf2-bclgj — :sensitive? trace-event policy) ------
;;
;; Per Spec 009 §Privacy (resolved by rf2-a32kd): framework-published
;; trace-consuming integrations MUST default-suppress `:sensitive? true`
;; events. Story is a framework-published consumer — the trace panel,
;; actions panel, recorder, and play assertions module all subscribe to
;; the raw trace stream — so each listener body gates on this flag.
;;
;; Default is `false` (suppress sensitive events). A story author
;; debugging redaction policy flips this on via
;; `(story/configure! {:trace/show-sensitive? true})`.
;;
;; The flag is read at the head of every listener body, so toggling it
;; takes effect on the next trace event without re-registering listeners.

(defonce
  ^{:doc "Atom holding the `:trace/show-sensitive?` flag. Default
         `false`. When `false` (default), every Story-registered trace
         listener short-circuits on events whose `:sensitive?` field is
         true, and the UI surface tracks how many were suppressed.
         When `true`, listeners receive every event unchanged. Per
         Spec 009 §Privacy + bead rf2-bclgj."}
  show-sensitive?
  (atom false))

(defn set-show-sensitive!
  "Replace the `:trace/show-sensitive?` flag. Story's `configure!`
  calls this. `nil` resets to the default (`false`)."
  [v]
  (reset! show-sensitive? (boolean v))
  nil)

(defn get-show-sensitive
  "Return the current `:trace/show-sensitive?` flag value."
  []
  @show-sensitive?)

(defn sensitive-event?
  "True iff the trace event `ev` carries `:sensitive? true` at the top
  level. Per Spec 009 §Privacy + §Listener filtering semantics — the
  framework stamps `:sensitive? true` on every trace event emitted
  inside a registration that opted in, and consumers branch on this
  flag. Absent/false means non-sensitive."
  [ev]
  (boolean (and (map? ev) (= true (:sensitive? ev)))))

(defn suppress-sensitive?
  "Should this trace event be suppressed by a Story-registered
  listener under the current `:trace/show-sensitive?` setting?

  Returns `true` iff (a) the event is `:sensitive? true` AND (b) the
  show-sensitive flag is `false`. Listeners wrap their body in
  `(when-not (suppress-sensitive? ev) ...)`."
  [ev]
  (and (sensitive-event? ev)
       (not @show-sensitive?)))

;; ---- *suppressed-counters* (rf2-bclgj — UI redaction indicator) ----------
;;
;; The UI panels (trace, actions) render a `[● REDACTED]` hint when
;; sensitive events were suppressed for the focused variant. The hint
;; tells the user "you're seeing fewer events than the runtime emitted
;; because the privacy gate is on" — useful when a sensitive cascade
;; would otherwise vanish silently.
;;
;; The counter is per-variant (keyed by `:tags :frame`), with a `:global`
;; bucket for events that have no frame scope (registration-time emits,
;; outermost-dispatch lookup failures — `:sensitive?` is rarely set on
;; those, but we keep the bucket so a count is never lost).

(defonce
  ^{:doc "Atom: `{variant-id → suppressed-count}`. Each Story-side
         listener that drops a sensitive event bumps the counter for
         the event's `:tags :frame`. The UI's redaction hint reads
         this counter; `reset-suppressed-count!` clears it (e.g. on
         variant teardown or 'clear' button)."}
  suppressed-counters
  (atom {}))

(defn note-suppressed!
  "Bump the suppressed-events counter for the variant the event
  targeted. Called by every Story-registered listener when
  `suppress-sensitive?` returned true. `variant-id` may be `nil` /
  absent — those count under `:global`."
  [variant-id]
  (let [k (or variant-id :global)]
    (swap! suppressed-counters update k (fnil inc 0)))
  nil)

(defn suppressed-count
  "Return the count of sensitive trace events that have been
  suppressed for `variant-id`. Zero if no events have been suppressed
  (or no listener has reported any) for that variant. `nil` /
  unspecified returns the `:global` bucket."
  ([] (suppressed-count :global))
  ([variant-id]
   (or (get @suppressed-counters (or variant-id :global)) 0)))

(defn reset-suppressed-count!
  "Reset the suppressed-events counter. With no arg, clears all.
  With a `variant-id`, clears just that bucket. Called from the UI's
  'clear' button and on variant teardown."
  ([] (reset! suppressed-counters {}) nil)
  ([variant-id]
   (swap! suppressed-counters dissoc (or variant-id :global))
   nil))
