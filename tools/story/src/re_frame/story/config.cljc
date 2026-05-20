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
  (:require [re-frame.privacy :as privacy])
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

;; ---- *global-decorators* (rf2-835ey — preview.ts parity, F-1) -----------
;;
;; Per ai/findings/2026-05-20-story-tutorial-set.md Finding F-1 (P2) Story
;; ships story-level + variant-level decorators only; Storybook's canonical
;; "wrap every story in the design system's theme provider" recipe lives in
;; `preview.ts` `decorators: [...]` and Story has no equivalent. Without
;; one, the tutorial chapter on decorators cannot offer the canonical
;; theme-wrapping recipe, and a large project ends up listing the decorator
;; id on every `reg-story` manually.
;;
;; The global-decorators primitive plugs that gap. It is symmetric to
;; `global-args` (Layer 1 of args-resolution): a project sets it once at
;; boot, and every variant's resolved decorator stack is prefixed with
;; this ordered list. Story-level and variant-level slots still compose
;; on top — the full stack is `(concat globals story-decorators
;; variant-decorators)`, with globals as the outermost wrap layer.
;;
;; The atom holds an ORDERED VECTOR of `[decorator-id & args]` refs (the
;; same shape `:decorators` slots take), not just ids — so a global
;; decorator can carry ref-args exactly like a story-level reference can.
;; Earliest-registered first; re-registering the same id replaces in-place
;; (idempotent at the side-table level; same as `reg-decorator`).
;;
;; The atom is plain data; production builds with `enabled?` false DCE
;; the registration call sites so the vector stays empty in release
;; bundles.

(defonce
  ^{:doc "Atom holding the ordered vector of global decorator references.
         Per rf2-835ey — Storybook `preview.ts` `decorators: [...]`
         parity. Defaults to `[]`; the host calls
         `re-frame.story/reg-global-decorator` at boot to append.
         Each entry is a `[decorator-id & args]` vector, same shape as
         a story-level or variant-level `:decorators` reference. The
         resolved decorator stack for every variant is prefixed with
         this vector (earliest-registered = outermost wrap)."}
  global-decorators
  (atom []))

(defn set-global-decorators!
  "Replace the global-decorators ref vector. Accepts a vector of
  `[decorator-id & args]` refs, or `nil` (clears). Story's
  `reg-global-decorator` and test fixtures call this."
  [v]
  (reset! global-decorators (vec (or v [])))
  nil)

(defn get-global-decorators
  "Return the current global-decorators ref vector."
  []
  @global-decorators)

(defn add-global-decorator!
  "Append `ref` to the global-decorators vector. If a ref with the same
  decorator id already exists, REPLACE it in place (same position) so
  hot-reloading the body does not reshuffle the global stack order.

  `ref` is `[decorator-id & args]` — same shape as a story-level or
  variant-level `:decorators` entry.

  Returns the decorator id."
  [ref]
  (let [id (first ref)]
    (swap! global-decorators
           (fn [v]
             (let [idx (->> v
                            (keep-indexed (fn [i r] (when (= id (first r)) i)))
                            first)]
               (if idx
                 (assoc v idx ref)
                 (conj v ref)))))
    id))

(defn remove-global-decorator!
  "Remove every entry whose decorator id is `id` from the
  global-decorators vector. Idempotent."
  [id]
  (swap! global-decorators
         (fn [v] (vec (remove (fn [r] (= id (first r))) v))))
  nil)

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

;; ---- *project-root* (rf2-zfy1e — 'Open in editor' path prefix) ----------
;;
;; Per rf2-zfy1e: source-coords stamped at registration time are
;; classpath-relative (the form-meta `:file` slot, e.g.
;; `"panel_gallery/event_detail_stories.cljs"`). Editor URI handlers
;; (`vscode://file/<path>...`, `cursor://...`, `idea://...`, etc.)
;; resolve `<path>` against the filesystem; a relative path fails with
;; "Path does not exist". Story's 'Open' chip therefore needs to know
;; the on-disk root to prepend before the URI ships.
;;
;; The host application sets this once at boot via
;; `(story/configure! {:project-root "C:/Users/me/code/my-app/src"})`.
;; Default is nil — when unset, the source-coord file ships verbatim
;; and the Open chip behaves exactly as it did pre-rf2-zfy1e (useful
;; for hosts whose source-paths are already absolute, and for tests).
;;
;; The atom is plain data; production builds with `enabled?` false DCE
;; the UI shell that reads it, so the value is harmless if it survives
;; into a release bundle.

(defonce
  ^{:doc "Atom holding the project-root prefix for 'Open in editor'.
         Default `nil` (no prefix; ship the source-coord file
         verbatim). Set by `re-frame.story/configure!` via the
         `:project-root` key. Read by the UI shell's open-in-editor
         chip — prepended to the source-coord's `:file` slot when
         building the editor URI."}
  project-root
  (atom nil))

(defn set-project-root!
  "Replace the project-root prefix. Accepts a string (the on-disk root,
  typically the directory above the classpath source-paths, joined to
  source-coords via `/`), or `nil` (clears the prefix). Story's
  `configure!` calls this."
  [p]
  (reset! project-root (when (and (string? p) (seq p)) p))
  nil)

(defn get-project-root
  "Return the current project-root string, or nil when unset."
  []
  @project-root)

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

;; ---- toggle-off callbacks (rf2-lqmje — retroactive scrub) ----------------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
;; false (rf2-lqmje), toggling the flag from true → false MUST clear
;; the per-variant trace buffers — the flag is NOT a one-way trapdoor.
;; The Story trace listener (`re-frame.story.ui.trace-buffer`) only
;; gates at ingest time via `suppress-sensitive?`, so without this
;; scrub a sensitive cascade emitted while the flag was true would
;; remain visible in every downstream consumer of the per-variant
;; buffer after the user flipped the flag back to false expecting
;; privacy to be restored.
;;
;; The cost: non-sensitive history that was buffered alongside the
;; sensitive cascade is also lost. This is the documented trade-off —
;; selective scrubbing is unsafe because a single sensitive event can
;; have caused later non-sensitive cascades (subs, renders, fx) whose
;; payloads structurally reveal the redacted value. Clearing every
;; per-variant buffer is the simplest correct semantic.
;;
;; The hook design avoids a circular require — `ui.trace` depends on
;; `config` to read the flag and bump the counter, so `config` cannot
;; directly invoke `ui.trace/clear-buffer!`. Instead, `ui.trace`
;; registers its clear-buffer fn into this atom at load time;
;; `set-show-sensitive!` walks the atom on every true → false
;; transition. CLJC-pure so the registration shape is testable under
;; the JVM target.

(defonce
  ^{:doc "Atom holding `{id → (fn [] ...)}` callbacks invoked when
         `set-show-sensitive!` transitions the flag from true → false.
         `ui.trace` registers its `clear-buffer!` at load time.
         Internal — host applications should not register here."}
  toggle-off-callbacks
  (atom {}))

(defn register-toggle-off-callback!
  "Register `f` (a zero-arg fn) under `id` to be invoked when
  `set-show-sensitive!` transitions the flag from `true` → `false`.
  Replaces any existing entry under the same id. Internal API — Story
  modules use it to wire their buffer-clear hooks; host applications
  should NOT register here.

  Returns `id`."
  [id f]
  (swap! toggle-off-callbacks assoc id f)
  id)

(defn unregister-toggle-off-callback!
  "Remove the toggle-off callback registered under `id`. Idempotent."
  [id]
  (swap! toggle-off-callbacks dissoc id)
  nil)

(defn- run-toggle-off-callbacks!
  "Invoke every registered toggle-off callback. Each callback's
  exception is swallowed (logged via `tap>`) so one buggy hook does
  not prevent the others from running — privacy is the load-bearing
  concern, and a partial clear is strictly better than no clear."
  []
  (doseq [[id f] @toggle-off-callbacks]
    (try
      (f)
      (catch #?(:clj Throwable :cljs :default) e
        (tap> {:tag ::toggle-off-callback-failed :id id :error e})))))

(defn set-show-sensitive!
  "Replace the `:trace/show-sensitive?` flag. Story's `configure!`
  calls this. `nil` resets to the default (`false`).

  Per rf2-lqmje (Spec 009 §Privacy §Retroactive-scrub): when the call
  transitions the flag from `true` → `false`, every per-variant trace
  buffer is cleared by invoking the registered
  `toggle-off-callbacks`. The trade-off — non-sensitive history
  buffered alongside the sensitive cascade is also lost — is
  intentional: clearing the whole buffer is the simplest correct
  semantic because a sensitive event emitted while the flag was true
  can have caused later cascades whose payloads structurally reveal
  the redacted value. The flag is NOT a one-way trapdoor; toggling it
  false MUST restore privacy fully. true → true and false → ANY
  transitions do NOT invoke the callbacks."
  [v]
  (let [prev @show-sensitive?
        next (boolean v)]
    (reset! show-sensitive? next)
    (when (and prev (not next))
      (run-toggle-off-callbacks!)))
  nil)

(defn get-show-sensitive
  "Return the current `:trace/show-sensitive?` flag value."
  []
  @show-sensitive?)

(defn sensitive-event?
  "True iff the trace event `ev` carries `:sensitive? true` at the top
  level. Thin alias over the framework-published `re-frame.privacy/sensitive?`
  predicate (re-exported as `re-frame.core/sensitive?`) — per rf2-sqxjn
  / rf2-iwqu9, every consumer of `:sensitive?` (Causa, Story,
  story-mcp, re-frame2-pair-mcp) composes against ONE framework
  primitive rather than reimplementing the five-token check. Per Spec
  009 §Privacy."
  [ev]
  (privacy/sensitive? ev))

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
