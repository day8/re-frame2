(ns re-frame.story.config
  "Compile-time configuration for re-frame2-story.

  The headline export is `enabled?` — the sentinel that gates every
  Story registration form per `001-Authoring.md` §Registration macros.

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

  `001-Authoring.md` §Registration macros + Spec 009 §Production builds: PRESENT-in-control,
  ABSENT-in-release. The macros expand to one form when `enabled?` is
  true (the registration call); to `nil` when false. Production code
  that accidentally requires a stories ns sees the namespace load but
  with no registrations, every Story query returns empty."
  (:require [re-frame.privacy    :as privacy]
            ;; EP-0015 (rf2-3t26eh): the on-box dev visibility choice is a
            ;; named `:rf.egress/*` profile resolved through the framework's
            ;; centralized projection table — NOT a process-global on/off
            ;; toggle. `re-frame.projection` is the pure-CLJC home of the
            ;; six ruled profiles + their `:rf.size/*` resolution; requiring
            ;; it directly (rather than the whole `re-frame.core` facade)
            ;; keeps this config ns JVM-runnable for the test corpus and
            ;; pins the egress vocabulary to the ONE canonical source.
            [re-frame.projection :as projection])
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
;; Xray preloads are already absent from `shadow-cljs release` builds
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
;; constant and removes the branch. The macro layer (`re-frame.story.
;; macros`) lays down that `(when enabled? ...)` form inline; the
;; closure compiler does the rest. (rf2-ee38b.3 removed a dead
;; `elide-form` helper here — the macros open-code the wrapper and never
;; called it.)

;; ---- *global-args* (Stage 3, rf2-von3) ----------------------------------
;;
;; Per `002-Runtime.md` §Args resolution precedence the args-precedence chain starts with `global-args`:
;; defaults the story-tool's host application sets at boot (theme, locale).
;; Stage 3's args-resolution layer reads this atom; `configure!` writes it.
;;
;; The atom is a plain Clojure data store — surviving `:advanced` builds
;; is harmless because it's empty by default. The mutation surface
;; (`configure!`) lives behind the `enabled?` gate at its call site.

(defonce
  ^{:doc "Atom holding the global args map. Per `002-Runtime.md` §Args resolution precedence — Layer
         1 of the args-precedence chain. Defaults to `{}`; the host
         calls `re-frame.story/configure!` at boot to seed via the
         `:rf.story/global-args` key."}
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
;; once at boot via `(story/configure! {:rf.story/editor :cursor})`;
;; this atom holds the chosen editor.
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
         by `re-frame.story/configure!` via the `:rf.story/editor`
         key. Read by the UI shell's open-in-editor buttons."}
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
;; `(story/configure! {:rf.story/project-root "C:/Users/me/code/my-app/src"})`.
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
         `:rf.story/project-root` key. Read by the UI shell's
         open-in-editor chip — prepended to the source-coord's
         `:file` slot when building the editor URI."}
  project-root
  (atom nil))

;; ---- static-export project-root posture (rf2: static-export self-containment)
;;
;; The project-root is a DEV-time affordance: it turns a classpath-relative
;; source-coord into an absolute on-disk path so the open-in-editor chip can
;; launch the author's editor (`vscode://file/<root>/<file>...`). On a
;; PUBLISHED static export (`story:build`, `static-mode?` true) that absolute
;; path is (a) useless — a custom `editor://` URI does not resolve from a
;; published HTML page — and (b) a leak — it bakes the BUILD machine's
;; checkout root (often `C:/Users/<name>/...`) into a publicly-served bundle
;; and into every rendered open-chip `href`.
;;
;; So in static mode the project-root is FAIL-CLOSED: `set-project-root!`
;; ignores it (the slot stays nil; open-in-editor degrades to the same no-op
;; the spec already documents for a missing root) UNLESS a host has made the
;; explicit, static-export-aware opt-in below. The opt-in is DISTINCT from the
;; dev testbed root helper (`re-frame.testbed.config/resolve-project-root`):
;; that helper exists to wire dev testbeds, and a static export passing its
;; result is exactly the accidental-leak case this guard closes. A host that
;; genuinely wants a "published site that links back into the author's editor"
;; (e.g. an internal docs deploy) flips this opt-in deliberately.
;;
;; This is a runtime guard. The static build ALSO drops the `RF2_TESTBED_
;; PROJECT_ROOT` → `repo-root` goog-define seed (see implementation/
;; shadow-cljs.edn :story-static/*), so no ambient checkout STRING is inlined
;; into the advanced bundle in the first place; this guard is the framework-
;; level defence that protects every downstream static export regardless of
;; what its entry-point's `configure!` passes.

(defonce
  ^{:doc "Atom: has a host explicitly opted in to an absolute project-root in
         static-export mode? Default `false` (fail-closed). When false,
         `set-project-root!` ignores any project-root under
         `static-mode?` so a published bundle never bakes a build-machine
         checkout path into its open-in-editor URIs. A host that wants a
         published site to deep-link back into its own editor sets this true
         via `set-allow-static-project-root!` BEFORE `configure!`."}
  allow-static-project-root?
  (atom false))

(defn set-allow-static-project-root!
  "Opt a static-export build IN to an absolute project-root (rf2 static-export
  self-containment). Default is OFF — in `static-mode?` the open-in-editor
  project-root is fail-closed so a published bundle ships no build-machine
  checkout path. Call this (with `true`) BEFORE `configure!` only when the
  published site is meant to deep-link back into the author's editor and the
  baked absolute root is acceptable. Has no effect outside static mode
  (the dev project-root path is unguarded). `nil` resets to off."
  [allow?]
  (reset! allow-static-project-root? (boolean allow?))
  nil)

(defn allow-static-project-root?*
  "Return whether the static-export project-root opt-in is on."
  []
  @allow-static-project-root?)

(defn set-project-root!
  "Replace the project-root prefix. Accepts a string (the on-disk root,
  typically the directory above the classpath source-paths, joined to
  source-coords via `/`), or `nil` (clears the prefix). Story's
  `configure!` calls this.

  Fail-closed in static-export mode: under `static-mode?` a non-nil
  project-root is IGNORED (the slot stays nil) unless the host has opted in
  via `set-allow-static-project-root!`. This stops a published `story:build`
  bundle from baking the build machine's checkout root into its
  open-in-editor URIs (rf2 static-export self-containment). Dev builds
  (`static-mode?` false) are unaffected."
  [p]
  (let [suppress? (and static-mode? (not @allow-static-project-root?))]
    (reset! project-root
            (when (and (not suppress?) (string? p) (seq p)) p)))
  nil)

(defn get-project-root
  "Return the current project-root string, or nil when unset."
  []
  @project-root)

;; ---- per-(tool,frame) egress visibility (rf2-3t26eh / EP-0015 issue 7) ---
;;
;; EP-0015 (frame-owned egress policy, graduated 2026-06-11) retired the
;; process-global privacy on/off toggle in favour of (a) frame-owned
;; `:sensitive` / `:large` classification declared at frame creation, and
;; (b) the centralized `re-frame.core/project-egress` boundary primitive
;; resolved against one of the six ruled `:rf.egress/*` profiles.
;;
;; Issue 7 rules the on-box-visibility GRAIN explicitly (spec/015
;; §Cross-tool visibility grain): on-box visibility is per (tool, frame)
;; PAIR. There is NO single process-global `show-sensitive?` switch. Local
;; tools default `:rf.egress/local-redacted` (suppress sensitive display);
;; raw requires an explicit trusted-local operator act per frame
;; (`:rf.egress/local-raw`). Revealing one frame's sensitive data MUST NOT
;; reveal any other frame's. Session-wide pinning composes ON TOP as tool
;; UX, not as framework state.
;;
;; Story is the (tool) half of that pair: it is a framework-published trace
;; consumer (the recorder, the play-runner's per-frame listener, the
;; runtime's capture-phase listener, the UI trace-buffer, DOM capture).
;; Every value-bearing slot it projects onto a dev surface ships under the
;; profile resolved for the SPECIFIC frame the event / recording targets.
;; The "is this `:sensitive?` event visible?" decision is not a hand-held
;; boolean: it derives from the profile's `:rf.size/include-sensitive?`
;; resolution via the framework's projection table
;; (`projection/profile-size-opts`), the same table `project-egress`
;; consumes — one source of truth, no re-implemented redaction policy.
;;
;; Storage shape (issue 7 — per-frame, not process-global):
;;
;;   frame-egress-profiles  ─►  {frame-id → :rf.egress/local-raw, ...}
;;
;; Only frames an operator has EXPLICITLY revealed appear in the map; every
;; other frame — and any frameless / unknown event — resolves to the
;; fail-closed default `:rf.egress/local-redacted`. A reveal is therefore an
;; explicit per-frame act; narrowing a single frame back to the default
;; scrubs only THAT frame's buffered sensitive traces (see
;; `set-frame-egress-profile!` + the per-frame toggle-off callbacks), never
;; an unrelated frame's. This is exactly the per-(tool,frame) shape issue 7
;; substitutes for the retired process-global toggle.

(def default-egress-profile
  "Story's default per-frame local-render egress profile (EP-0015 issue 7):
  the on-box dev UI boundary that suppresses sensitive display. Fail-closed.
  Every frame not explicitly revealed resolves to this."
  :rf.egress/local-redacted)

(def trusted-local-profile
  "The trusted-local-operator opt-in profile (EP-0015 §10): includes
  sensitive AND large. The single boundary a story author flips on PER
  FRAME to see path-marked values verbatim on their own machine."
  :rf.egress/local-raw)

(def egress-profiles
  "The closed six-member `:rf.egress/profile` vocabulary — re-exported from
  the framework's `re-frame.projection/profiles` so `configure!` can
  validate `:rf.story/egress-profile` against the ONE canonical enum (no
  forked copy)."
  projection/profiles)

(defn known-egress-profile?
  "True iff `profile` is a member of the closed `:rf.egress/*` enum."
  [profile]
  (contains? egress-profiles profile))

(defonce
  ^{:doc "Atom holding Story's PER-FRAME local-render `:rf.egress/*` profile
         overrides — `{frame-id → profile}` (EP-0015 issue 7,
         per-(tool,frame) visibility grain). A frame absent from the map
         resolves to `:rf.egress/local-redacted` (suppress sensitive
         display — fail-closed); only a frame an operator has explicitly
         revealed to `:rf.egress/local-raw` carries an entry. Read by every
         Story trace listener via `include-sensitive?` /
         `suppress-sensitive?` — each call passes the frame the event /
         recording targets, so the whole-event `:sensitive?` redact/pass
         decision is FRAME-SCOPED: revealing one frame never reveals
         another. The decision derives from the resolved profile's
         `:rf.size/include-sensitive?` floor via the framework projection
         table, the same table `project-egress` consumes (one source of
         truth). Replaces the retired single process-global egress-profile
         atom (rf2-6z4znr) and the predecessor `:rf.privacy/show-sensitive?`
         boolean (rf2-bclgj)."}
  frame-egress-profiles
  (atom {}))

(defonce
  ^{:doc "Atom holding the SESSION-PIN profile — the tool-UX default a
         `(story/configure! {:rf.story/egress-profile ...})` call sets for
         frames that have no explicit per-frame override. Per EP-0015 issue
         7 session-wide pinning is tool UX layered ON TOP of the per-frame
         grain, NOT framework state — so it lives here, separate from
         `frame-egress-profiles`, and a per-frame `set-frame-egress-profile!`
         always wins over it. Default `:rf.egress/local-redacted`
         (fail-closed). Resetting it does NOT touch any per-frame override."}
  session-egress-profile
  (atom default-egress-profile))

;; ---- toggle-off callbacks (rf2-lqmje / rf2-6z4znr — retroactive scrub) ----
;;
;; Per Spec 009 §Privacy §Retroactive-scrub (rf2-lqmje), narrowing a frame's
;; egress profile from a sensitive-revealing boundary (`:rf.egress/local-raw`)
;; back to the redacting default MUST clear that frame's buffered sensitive
;; traces — the reveal is NOT a one-way trapdoor. The Story trace listener
;; (`re-frame.story.ui.trace-buffer`) only gates at ingest time via
;; `suppress-sensitive?`, so without this scrub a sensitive cascade buffered
;; while the raw profile was active would remain visible in every downstream
;; consumer of that frame's buffer after the operator narrowed it back
;; expecting privacy to be restored.
;;
;; Per EP-0015 issue 7 (rf2-6z4znr) the scrub is FRAME-SCOPED: narrowing
;; frame A scrubs frame A's buffer and leaves every unrelated frame
;; untouched. Each callback receives the narrowed `frame-id`; a callback
;; whose buffer is keyed per-frame clears only that frame.
;;
;; The cost: non-sensitive history buffered alongside the sensitive cascade
;; for THAT frame is also lost. This is the documented trade-off — selective
;; within-frame scrubbing is unsafe because a single sensitive event can have
;; caused later non-sensitive cascades (subs, renders, fx) whose payloads
;; structurally reveal the redacted value. Clearing the whole per-frame
;; buffer is the simplest correct semantic.
;;
;; The hook design avoids a circular require — `ui.trace` depends on
;; `config` to read the profile and bump the counter, so `config` cannot
;; directly invoke `ui.trace/clear-buffer!`. Instead, `ui.trace`
;; registers its clear-buffer fn into this atom at load time;
;; `set-frame-egress-profile!` walks the atom on every per-frame
;; reveal → redact transition, passing the narrowed frame-id. CLJC-pure so
;; the registration shape is testable under the JVM target.

(defonce
  ^{:doc "Atom holding `{id → (fn [frame-id] ...)}` callbacks invoked when
         `set-frame-egress-profile!` narrows a FRAME from a
         sensitive-revealing boundary back to a redacting one. Each callback
         receives the narrowed `frame-id` and clears only that frame's
         buffered sensitive state (EP-0015 issue 7 — frame-scoped scrub).
         `ui.trace` registers its `clear-buffer!` at load time. Internal —
         host applications should not register here."}
  toggle-off-callbacks
  (atom {}))

(defn register-toggle-off-callback!
  "Register `f` (a one-arg fn taking the narrowed `frame-id`) under `id` to
  be invoked when `set-frame-egress-profile!` narrows a frame from a
  sensitive-revealing boundary (`:rf.egress/local-raw`) back to a redacting
  one. Replaces any existing entry under the same id. Internal API — Story
  modules use it to wire their buffer-clear hooks; host applications should
  NOT register here.

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
  "Invoke every registered toggle-off callback with the narrowed `frame-id`.
  Each callback's exception is swallowed (logged via `tap>`) so one buggy
  hook does not prevent the others from running — privacy is the
  load-bearing concern, and a partial clear is strictly better than no
  clear."
  [frame-id]
  (doseq [[id f] @toggle-off-callbacks]
    (try
      (f frame-id)
      (catch #?(:clj Throwable :cljs :default) e
        (tap> {:tag ::toggle-off-callback-failed :id id :frame frame-id :error e})))))

(defn- profile-includes-sensitive?
  "True iff `profile` resolves (via the framework projection table) to a
  `:rf.size/include-sensitive? true` floor — i.e. it is a
  sensitive-revealing boundary. `:rf.egress/local-raw` is the only Story-
  relevant profile that does. An unknown / nil profile is fail-closed
  (does NOT reveal)."
  [profile]
  (boolean (:rf.size/include-sensitive? (projection/profile-size-opts profile))))

(defn resolve-egress-profile
  "Resolve the effective `:rf.egress/*` profile for `frame-id` (EP-0015
  issue 7 — per-(tool,frame) visibility). Resolution, fail-closed at every
  fork:

  1. an explicit per-frame override in `frame-egress-profiles` wins;
  2. else the session-pin (`session-egress-profile`, tool UX);
  3. a `nil` / unknown / frameless `frame-id` never reads an override — it
     resolves straight to the session-pin (which itself defaults to the
     redacting `:rf.egress/local-redacted`).

  So an event whose frame is unknown can never inherit another frame's
  reveal; the worst case is the session default, which is redacting."
  [frame-id]
  (or (when (some? frame-id)
        (get @frame-egress-profiles frame-id))
      @session-egress-profile))

(defn set-frame-egress-profile!
  "Set the per-frame local-render `:rf.egress/*` profile for `frame-id`
  (EP-0015 issue 7, rf2-6z4znr — the per-(tool,frame) visibility act). This
  is the explicit trusted-local operator act that reveals ONE frame; it
  never changes any other frame's visibility.

  - `:rf.egress/local-raw` reveals `frame-id`'s sensitive trace/recorder/
    DOM-capture data.
  - `:rf.egress/local-redacted` (or `nil`) narrows `frame-id` back to the
    fail-closed default and REMOVES its override entry. Per rf2-lqmje
    (Spec 009 §Privacy §Retroactive-scrub) a reveal → redact narrowing for
    this frame invokes the registered `toggle-off-callbacks` with
    `frame-id`, so that frame's buffered sensitive traces are scrubbed —
    other frames are untouched. The reveal is NOT a one-way trapdoor.
  - An unknown profile keyword is rejected by `configure!` /
    `set-frame-egress-profile!`'s caller before reaching here;
    defence-in-depth, a non-member value coerces to the fail-closed default
    (and removes the override). A `nil` `frame-id` is a no-op.

  A widening (redact → reveal) and any same-class transition do NOT invoke
  the callbacks. Returns nil."
  [frame-id profile]
  (when (some? frame-id)
    (let [next (if (contains? projection/profiles profile)
                 profile
                 default-egress-profile)
          prev (resolve-egress-profile frame-id)]
      (if (= next default-egress-profile)
        (swap! frame-egress-profiles dissoc frame-id)
        (swap! frame-egress-profiles assoc frame-id next))
      (when (and (profile-includes-sensitive? prev)
                 (not (profile-includes-sensitive? next)))
        (run-toggle-off-callbacks! frame-id))))
  nil)

(defn set-session-egress-profile!
  "Set the SESSION-PIN profile — the tool-UX default for frames with no
  explicit per-frame override (EP-0015 issue 7: session-wide pinning is
  layered tool UX, not framework state). Story's `configure!` calls this via
  `:rf.story/egress-profile`. `nil` resets to the fail-closed default
  (`:rf.egress/local-redacted`); an unknown value coerces fail-closed
  (defence-in-depth; `configure!` rejects it first).

  Narrowing the session-pin reveal → redact scrubs EVERY frame currently
  resolving (via the pin) to a revealing boundary — i.e. every revealed
  frame that has no contradicting per-frame override — because a pin-driven
  reveal can have buffered sensitive cascades for all of them. Frames with
  their own per-frame override keep their own profile and are scrubbed only
  by their own `set-frame-egress-profile!` narrowing. Returns nil."
  [profile]
  (let [next (if (contains? projection/profiles profile)
               profile
               default-egress-profile)
        prev @session-egress-profile]
    (reset! session-egress-profile next)
    (when (and (profile-includes-sensitive? prev)
               (not (profile-includes-sensitive? next)))
      ;; The pin narrowed reveal → redact: every frame WITHOUT its own
      ;; per-frame override was revealing via the pin and must be scrubbed.
      ;; Per-frame overrides are unaffected by a pin change — they keep
      ;; their own profile until their own `set-frame-egress-profile!`
      ;; narrows them. Config is leaf-level and does not enumerate live
      ;; frames, so we pass `nil` to signal "scrub all pin-revealed
      ;; frames" — a buffer-clear hook treats a nil frame-id as a clear-all
      ;; of its non-overridden buffers.
      (run-toggle-off-callbacks! nil)))
  nil)

;; ---- legacy single-knob shim (deprecated; configure! + tests) ------------
;;
;; The pre-rf2-6z4znr single process-global setter (`set-egress-profile!`) is
;; retained as a thin shim over the session-pin so the `configure!` session-
;; default path + existing fixtures keep working. It operates on the SESSION-
;; PIN, never on a per-frame override — the per-frame act is
;; `set-frame-egress-profile!`. To READ the session-pin, deref
;; `session-egress-profile`; for a frame's effective profile use
;; `resolve-egress-profile`.

(defn set-egress-profile!
  "DEPRECATED single-knob shim (rf2-6z4znr): sets the SESSION-PIN profile.
  Prefer `set-frame-egress-profile!` for the per-(tool,frame) operator act.
  Retained so existing fixtures + the `configure!` session-default path keep
  working. Delegates to `set-session-egress-profile!`."
  [profile]
  (set-session-egress-profile! profile))

(defn include-sensitive?
  "True iff the profile resolved for `frame-id` reveals sensitive values
  (i.e. resolves to `:rf.size/include-sensitive? true`). Every Story trace
  listener consults this (via `suppress-sensitive?`) — passing the frame the
  event / recording targets — to decide whether a `:sensitive?` event is
  shown or redacted. Frame-scoped (EP-0015 issue 7): revealing one frame
  never reveals another. Fail-closed by default. Pass `nil` to resolve
  against the session-pin (a frameless / unknown-frame seam)."
  [frame-id]
  (profile-includes-sensitive? (resolve-egress-profile frame-id)))

(defn sensitive-event?
  "True iff the trace event `ev` carries `:sensitive? true` at the top
  level. Thin alias over the framework-published `re-frame.privacy/sensitive?`
  predicate (re-exported as `re-frame.core/sensitive?`) — per rf2-sqxjn
  / rf2-iwqu9, every consumer of `:sensitive?` (Xray, Story,
  story-mcp, re-frame2-pair-mcp) composes against ONE framework
  primitive rather than reimplementing the five-token check. Per Spec
  009 §Privacy."
  [ev]
  (privacy/sensitive? ev))

(defn event-frame
  "Return the frame a trace event targets — its `[:tags :frame]` slot — or
  nil for a frameless emit (registration-time, outermost-dispatch lookup
  failures). The frame Story's listeners resolve the per-frame egress
  profile against (EP-0015 issue 7)."
  [ev]
  (when (map? ev)
    (get-in ev [:tags :frame])))

(defn suppress-sensitive?
  "Should this trace event be suppressed by a Story-registered listener
  under the egress profile resolved FOR THE EVENT'S FRAME (EP-0015 issue 7,
  rf2-6z4znr)?

  Returns `true` iff (a) the event is `:sensitive? true` AND (b) the profile
  resolved for the frame does NOT reveal sensitive values
  (`include-sensitive?` is false — the `:rf.egress/local-redacted` default).
  Frame-scoped: a sensitive event targeting a frame the operator has NOT
  revealed is suppressed even while a sibling frame is `:rf.egress/local-raw`.
  A frameless or unknown-frame sensitive event fails closed (resolves to the
  redacting session default), so it is suppressed.

  The single-arg arity derives the frame from the event itself
  (`event-frame`); the two-arg arity lets a caller that already knows the
  frame (e.g. the per-frame trace-buffer listener keyed by `variant-id`) pass
  it explicitly. Listeners wrap their body in
  `(when-not (suppress-sensitive? ev) ...)`. The redaction policy is the
  resolved profile's, via the framework projection table — NOT a
  re-implemented boolean."
  ([ev] (suppress-sensitive? ev (event-frame ev)))
  ([ev frame-id]
   (and (sensitive-event? ev)
        (not (include-sensitive? frame-id)))))

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

;; ---- *reset-all!* (rf2-6ez1u — config-leak test-isolation gap) -----------
;;
;; Every leakable process-global config atom above is a `defonce` that
;; survives across tests in the same JVM run / browser page. The test
;; fixtures (`re-frame.story/clear-all!`, `re-frame.story.test-support/
;; story-reset!`) historically reset only the registrar side-table, the
;; global-decorators vector, the canonical auto-install gate, and the
;; play run-state — they did NOT touch these config atoms.
;;
;; That left a green-but-wrong footgun: a test that called
;; `(story/configure! {:rf.story/global-args {...}})` or set
;; `:rf.story/egress-profile` leaked that global state into every
;; SUBSEQUENT test. Because `global-args` is Layer 1 of args resolution
;; (`re-frame.story.args/resolve-args` reads `(get-global-args)` at
;; resolve time), a leaked global arg silently changes the EFFECTIVE
;; args of an unrelated later variant — order-dependent, hard to debug,
;; and exactly the footgun class `test_support` exists to close.
;;
;; `reset-all!` restores every leakable atom to its load-time default so
;; the test fixtures can call it once and guarantee a clean config slate.
;; It deliberately does NOT clear `toggle-off-callbacks` — those are
;; load-time module registrations (`ui.trace` wires its `clear-buffer!`
;; once at ns-load), not per-test state; wiping them would silently
;; break the retroactive-scrub hook for the rest of the run.
;;
;; The egress atoms are `reset!` directly (not via the `set-*!` fns) so a
;; reset does NOT fire the reveal → redact toggle-off callbacks — `reset-all!`
;; restores the load-time default, it does not simulate a live runtime
;; profile narrowing.

(defn reset-all!
  "Reset every leakable process-global config atom to its load-time
  default. Called by the Story test fixtures (`re-frame.story/clear-all!`
  and `re-frame.story.test-support/story-reset!`) so a `configure!` (or
  any other config mutation) in one test cannot leak into the next.

  Resets, in declaration order:
  - `global-args`                 → `{}`     (Layer 1 of args resolution)
  - `global-decorators`           → `[]`
  - `editor`                      → `:vscode`
  - `project-root`                → `nil`
  - `allow-static-project-root?`  → `false`  (fail-closed static opt-in)
  - `frame-egress-profiles`       → `{}`     (per-(tool,frame) overrides cleared —
                                              reset directly, no toggle-off fire)
  - `session-egress-profile`      → `:rf.egress/local-redacted`
  - `suppressed-counters`         → `{}`

  Deliberately leaves `toggle-off-callbacks` intact — those are
  load-time module registrations, not per-test state (per rf2-6ez1u)."
  []
  (reset! global-args {})
  (reset! global-decorators [])
  (reset! editor :vscode)
  (reset! project-root nil)
  (reset! allow-static-project-root? false)
  (reset! frame-egress-profiles {})
  (reset! session-egress-profile default-egress-profile)
  (reset! suppressed-counters {})
  nil)
