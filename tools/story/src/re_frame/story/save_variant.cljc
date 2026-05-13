(ns re-frame.story.save-variant
  "Save-current-canvas-state-as-new-variant — the SB9 'Save' affordance
  (rf2-one3t; SB9 story-from-UI parity per rf2-v05qb §2.2).

  Story-from-UI in two affordances: (a) record-as-`:play` (Test Codegen,
  rf2-5fc15 — `re-frame.story.recorder`); (b) snapshot-args-as-`:args`
  (this namespace). Both surface the captured state through the same
  review-then-commit modal pattern — Story emits an EDN snippet the user
  copies into source. Source is never written directly; the modal-preview
  step is the elegant escape hatch from Storybook's auto-write-to-file
  approach (which entangles the playground with Prettier configuration,
  source-control conflicts, and the project's editor settings).

  ## What this namespace does

  1. Pure helper `snapshot-args` — given a variant-id (+ optional active
     modes + cell overrides) returns the live effective args via
     `re-frame.story.args/resolve-args`. The args map IS the snapshot.
  2. Pure code-gen `gen-variant-snippet` — emits an EDN
     `(reg-variant <id> {:extends <src-id> :args {...}})` form the user
     copies into source.
  3. Impure trigger `save-current-as-variant!` — looks up the focused
     variant in the shell-state, takes a snapshot, opens the save-as
     dialog. Called from the controls panel's 'Save variant' button and
     dispatchable as `:rf.story/save-current-as-variant`.

  ## Why a pure ns

  Mirrors `re-frame.story.recorder`'s split — pure snippet machinery + a
  thin CLJS-only UI surface (`re-frame.story.ui.save-variant`). The JVM
  test corpus exercises the snippet generator + the snapshot helper
  end-to-end without pulling Reagent / DOM into the test classpath.

  ## Elision

  Every public fn opens with `(when config/enabled? ...)` or is pure data
  → data so production CLJS builds short-circuit before any side effect.
  The dialog ratom + button component live in
  `re-frame.story.ui.save-variant` (CLJS-only)."
  (:require [clojure.string :as str]
            [re-frame.core                  :as rf]
            [re-frame.story.args            :as args]
            [re-frame.story.config          :as config]
            [re-frame.story.review-dialog   :as review-dialog]
            [re-frame.story.ui.state        :as state]))

;; ---------------------------------------------------------------------------
;; Pure: args-snapshot helper
;;
;; The snapshot IS `(args/resolve-args variant-id opts)` — the effective
;; args after the five-layer precedence chain (global < story < modes <
;; variant < cell-overrides) collapses. This wrapper exists so callers
;; have a single entry point + so the JVM test corpus can pin the
;; snapshot semantics without coupling to the args ns directly.
;; ---------------------------------------------------------------------------

(defn snapshot-args
  "Return the effective args map for `variant-id` — the snapshot the
  save-as-variant flow writes into the generated `(reg-variant ...)`
  form. Pure data → data; JVM-testable.

  `opts` (optional):
  - `:active-modes`   — sequential coll of mode ids active for the snapshot.
  - `:cell-overrides` — `{arg-key → value}` runtime overrides for THIS
                       variant (NOT the whole `:cell-overrides` map).

  When the variant has no resolvable args (the variant body has no
  `:args` slot and no override is in flight) the snapshot is `{}`. The
  fn never throws on an unregistered variant — the caller's preview
  shows the resulting form so the missing slot is visible."
  ([variant-id]
   (snapshot-args variant-id nil))
  ([variant-id {:keys [active-modes cell-overrides] :as _opts}]
   (args/resolve-args variant-id
                      {:active-modes   (or active-modes [])
                       :cell-overrides (or cell-overrides {})})))

;; ---------------------------------------------------------------------------
;; Pure: code-gen — `(reg-variant ... :args {...})` snippet
;; ---------------------------------------------------------------------------

(defn- pr-args-map
  "Pretty-print an args map as a multi-line EDN form. Each top-level
  key/value pair renders on its own line indented two columns under the
  enclosing brace. Empty maps render as `{}`."
  [m]
  (if (empty? m)
    "{}"
    (str "{"
         (->> (sort-by (fn [[k _]] (str k)) m)
              (map (fn [[k v]] (str (pr-str k) " " (pr-str v))))
              (str/join "\n            "))
         "}")))

(defn gen-variant-snippet
  "Build the EDN snippet for a save-as-variant flow. Pure data → string.

  Returns a `(re-frame.story/reg-variant <id> {:extends <src> :args {...}})`
  form the user can paste into source. The new variant id, source variant
  id (for `:extends`), and the args snapshot come from `opts`:

      :variant-id  required — keyword id of the new variant
                              (e.g. `:story.counter/saved-739221`)
      :extends     optional — keyword id of the source variant
                              (carries `:component`, `:decorators`,
                              non-overridden args)
      :args        required — args map captured from the live canvas
      :doc         optional — docstring
      :alias       optional — short alias to use in the form
                              (default `\"story\"`)

  The output is human-readable EDN — args render on their own lines,
  sorted by key for determinism. The form is `read-string`-able and
  round-trips through re-frame's registrar machinery.

  Unlike `re-frame.story.recorder/gen-play-snippet` (which produces a
  `:play` body for record-as-test), this generator captures the canvas
  STATE — the args snapshot — so the new variant renders with the same
  controls as the source the user was tweaking when they clicked Save."
  [{:keys [variant-id extends args doc alias]
    :or   {alias "story"}}]
  (let [body-keys (cond-> []
                    doc     (conj [:doc (pr-str doc)])
                    extends (conj [:extends (pr-str extends)])
                    true    (conj [:args (pr-args-map (or args {}))]))
        body-str  (->> body-keys
                       (map (fn [[k v]] (str k " " v)))
                       (str/join "\n   "))]
    (str "(" alias "/reg-variant "
         (pr-str (or variant-id :story.saved/example))
         "\n  {" body-str "})")))

;; ---------------------------------------------------------------------------
;; Default-id derivation + dialog state shape
;;
;; The dialog state machine + the default-id derivation live in the
;; shared `re-frame.story.review-dialog` ns (rf2-7jpky); the save-
;; variant flow's only flavour is the `\"saved-\"` prefix on the auto-
;; derived id and the `{:args <snapshot>}` shape stashed in the
;; dialog's `:context` slot. Thin re-exports below for ergonomics +
;; the legacy `:args` key in the opened state (preserved for callers
;; that read it via the open-dialog callback).
;; ---------------------------------------------------------------------------

(def ^:const default-id-prefix
  "Per-flow prefix for the auto-derived default new-variant id."
  "saved")

(defn default-variant-id
  "Derive the save-variant default new-variant id from a source variant
  id + a wall-clock millis stamp. Pure data → keyword | nil. Delegates
  to `review-dialog/default-variant-id-with-prefix`; nil for non-
  qualified-keyword sources."
  [source-variant-id now-ms]
  (review-dialog/default-variant-id-with-prefix
    source-variant-id now-ms default-id-prefix))

(def initial-dialog-state
  "Alias for `review-dialog/initial-state` — kept for call-site
  ergonomics so the dialog ratom seeding form reads as
  `save-variant/initial-dialog-state`."
  review-dialog/initial-state)

(defn open
  "Open the save-variant dialog against `source-variant-id` with the
  captured `args-snapshot`. `now-ms` seeds the default id. The args
  ride in the dialog state's `:context` slot (per the review-dialog
  contract); a top-level `:args` key is preserved so callers reading
  the legacy slot keep working."
  [_state source-variant-id args-snapshot now-ms]
  (let [base (review-dialog/open review-dialog/initial-state
                                 source-variant-id
                                 {:args args-snapshot}
                                 now-ms
                                 default-id-prefix)]
    (assoc base :args args-snapshot)))

(defn close
  "Close the dialog — returns the idle state."
  [_state]
  review-dialog/initial-state)

(defn set-draft-id
  "Replace the draft id in the dialog state. `id` may be a keyword or a
  raw string the user is typing."
  [state id]
  (review-dialog/set-draft-id state id))

;; ---------------------------------------------------------------------------
;; Impure: capture + open
;;
;; Called by the controls-panel button and by the
;; `:rf.story/save-current-as-variant` event handler. Reads the shell
;; state for the focused variant + active modes + overrides, takes a
;; snapshot, and signals the UI layer (which owns the dialog ratom) to
;; open against the snapshot.
;;
;; The CLJS-only UI layer registers a callback at install-time so this
;; .cljc helper can stay free of Reagent / DOM coupling. Pure-logic
;; tests can stub the callback to assert the snapshot shape without
;; pulling in the UI.
;; ---------------------------------------------------------------------------

(defonce ^{:doc "The UI-layer callback to open the save-variant dialog.
                 Registered once at shell mount; called by
                 `save-current-as-variant!` with the snapshot + source-
                 variant-id. JVM tests can register a probe callback to
                 inspect what the helper would emit."}
  open-dialog-fn (atom nil))

(defn set-open-dialog-fn!
  "Register the UI-layer dialog-open callback. The callback is invoked
  as `(callback source-variant-id args-snapshot)`. Idempotent — calling
  again replaces."
  [f]
  (reset! open-dialog-fn f))

(defn focused-variant-id
  "Pure helper: return the shell's currently-focused variant id, or nil.
  Read off the shell-state map so callers can pass either the live atom
  deref or a probe map in tests."
  [shell-state]
  (:selected-variant shell-state))

(defn save-current-as-variant!
  "Capture the current canvas state as a save-as-variant snapshot and
  surface the EDN form in the dialog modal. Idempotent — calling while
  the dialog is already open re-opens with a fresh snapshot.

  `opts` (optional):
  - `:variant-id` — override the source variant id; defaults to the
                    shell's `:selected-variant`. Used by the MCP / agent
                    paths that drive the save against a specific target.
  - `:now-ms`     — wall-clock millis for the default-id seed; defaults
                    to the current time on the platform.

  Returns the captured snapshot map `{:source-id ... :args ...}` so the
  caller (the event handler / the MCP tool) can echo it back. Returns
  nil when no variant is focused — the UI button is disabled in that
  case, so the only path that hits the nil branch is a programmatic
  call without a focused variant."
  ([] (save-current-as-variant! nil))
  ([{:keys [variant-id now-ms]}]
   (when config/enabled?
     (let [shell      (state/get-state)
           target     (or variant-id (focused-variant-id shell))
           now-ms     (or now-ms #?(:clj  (System/currentTimeMillis)
                                    :cljs (.now js/Date)))]
       (when target
         (let [snapshot (snapshot-args
                          target
                          {:active-modes   (:active-modes shell)
                           :cell-overrides (get-in shell [:cell-overrides target])})
               record   {:source-id target
                         :args      snapshot
                         :now-ms    now-ms}]
           (when-let [cb @open-dialog-fn]
             (cb target snapshot now-ms))
           record))))))

;; ---------------------------------------------------------------------------
;; Event registration — `:rf.story/save-current-as-variant`
;;
;; Dispatchable from the agent surface (story-mcp) and from any host
;; chrome that wants to drive the save flow without holding a direct
;; reference to `save-current-as-variant!`. Payload is an optional
;; opts map matching `save-current-as-variant!`'s arity:
;;
;;     (rf/dispatch [:rf.story/save-current-as-variant])
;;     (rf/dispatch [:rf.story/save-current-as-variant
;;                   {:variant-id :story.counter/happy-path}])
;;
;; The handler delegates to `save-current-as-variant!` and returns no
;; effects — the dialog opens via the UI-layer callback registered at
;; shell mount. Registered by `install-canonical-event-handlers!`,
;; called from `re-frame.story/install-canonical-vocabulary!` at boot.
;; Idempotent.
;;
;; The event id is `:rf.story/save-current-as-variant`; the namespace
;; prefix `:rf.story/*` is filtered by the Test Codegen recorder's
;; `recordable-event?` predicate, so a save dispatched during an active
;; recording never appears in the recorded `:play` body.
;; ---------------------------------------------------------------------------

(def ^:const id-save-current-as-variant
  "The canonical event id for the save-as-variant trigger. Per spec/007
  §Reserved namespaces the `:rf.story/*` prefix is reserved for the
  Story runtime's own helper events."
  :rf.story/save-current-as-variant)

(defn install-canonical-event-handlers!
  "Register the canonical `:rf.story/save-current-as-variant` event
  handler. Idempotent. Production builds (with `config/enabled?` false)
  skip the registration."
  []
  (when config/enabled?
    (rf/reg-event-fx
      id-save-current-as-variant
      (fn [_cofx [_ opts]]
        (save-current-as-variant! opts)
        {}))
    nil))

