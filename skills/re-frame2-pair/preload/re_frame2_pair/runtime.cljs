;;;; re-frame2-pair.runtime — re-frame2-pair helper namespace, preloaded into the app.
;;;;
;;;; This file is loaded into the consumer app via shadow-cljs's
;;;; `:devtools :preloads` mechanism. See `skills/re-frame2-pair/SKILL.md`
;;;; for the one-line setup; the MCP server's `discover-app` tool calls
;;;; `(re-frame2-pair.runtime/health)` and refuses with a structured
;;;; `:reason :runtime-not-preloaded` error pointing at the setup doc
;;;; when the namespace isn't present.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - All trace and epoch reads consume re-frame2's public Tool-Pair
;;;;     surfaces (`re-frame.core/register-listener!`, `trace-buffer`,
;;;;     `register-epoch-listener!`, `epoch-history`, `restore-epoch`). No
;;;;     reaching into private namespaces.
;;;;   - Exactly one trace listener (`:re-frame2-pair`) and one epoch
;;;;     listener (`:re-frame2-pair-epoch`) are registered. Multi-tool
;;;;     coexistence is the expected default; per Spec 009 §Listener
;;;;     ordering, listener ordering is not contract.
;;;;   - Streaming subscriptions ride those same single
;;;;     listener slots — the listener fans matching events into per-
;;;;     subscription queues. `subscribe!` / `drain-subscription!` /
;;;;     `unsubscribe!` are the public surface the MCP server's
;;;;     `subscribe` op consumes.
;;;;   - The `session-id` sentinel below is read by the MCP server's
;;;;     preload probe. A mirror is also set on
;;;;     `js/globalThis.__re_frame2_pair_runtime` at load time so the
;;;;     probe can be a single bencode round-trip rather than a CLJS
;;;;     compile. A full page refresh wipes both — `discover-app`
;;;;     reports the missing preload with a structured setup hint.
;;;;
;;;; Naming surfaces — MCP vs runtime.
;;;;
;;;;   re-frame2-pair-mcp carries TWO vocabularies for the same logical
;;;;   surface: the MCP tool catalogue (operator-facing, disciplined to the
;;;;   cross-MCP NAMING.md `list-<things>` verb shape) and the runtime fn
;;;;   surface in THIS ns + `re-frame.core`. The pairs:
;;;;
;;;;     | MCP tool name           | Runtime fn name             |
;;;;     |-------------------------|-----------------------------|
;;;;     | `list-subscriptions`    | `sub-cache-info`            |
;;;;     | `list-streams`          | `subscription-info`         |
;;;;     | `list-handlers`         | `rf/registrations`          |
;;;;     | `read-dom`              | `dom-read`                  |
;;;;     | `read-ui`               | `ui-read`                   |
;;;;
;;;;   `read-dom` and `read-ui` share one DOM-read core (`node->content`),
;;;;   and both ship a thin `(…/dom-read …)` / `(…/ui-read …)` call via the
;;;;   same eval-form plumbing, so neither op's eval form can rot
;;;;   independently. `list-subscriptions` reads the live reactive sub-cache
;;;;   via `sub-cache-info`; `list-streams` carries the streaming-tap
;;;;   diagnostic, wrapping `subscription-info`.
;;;;
;;;;   An agent generating an eval form via `eval-cljs` uses the
;;;;   right-hand column; the same agent calling the MCP tool surface
;;;;   uses the left-hand column. The split is deliberate: the verb
;;;;   discipline stops at the MCP boundary — the runtime's audience
;;;;   is smaller than the MCP surface, and a runtime rename ripples
;;;;   into every eval-form caller. This paragraph documents the
;;;;   asymmetry so it's discoverable; see `spec/Principles.md` §"Tool
;;;;   verbs follow the cross-MCP convention" for the policy rationale.

(ns re-frame2-pair.runtime
  (:require [re-frame.core :as rf]
            ;; sub-cache-snapshot lives in re-frame.subs.tooling
            ;; (production-DCE split). re-frame2-pair is dev-tier — loading the
            ;; tooling sibling here is bundle-isolation-safe (the
            ;; preload is dev-only).
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.schemas :as schemas]
            [re-frame.machines :as machines]
            ;; register-listener! / trace-buffer (and the rest of
            ;; the listener + ring-buffer surface) live in
            ;; re-frame.trace.tooling, not re-frame.trace. CLJS deliberately
            ;; omits `rf/<name>` aliases for these so production counter
            ;; bundles DCE the tooling sibling wholesale; this preload is
            ;; dev-only, so requiring the tooling ns directly here is
            ;; bundle-isolation-safe.
            [re-frame.trace.tooling :as trace-tooling]
            ;; The canonical RAW trace-event frame reader
            ;; (`re-frame.trace/trace-event-frame`). Owned by the trace
            ;; contract ns; `re-frame.trace` is already loaded via
            ;; `re-frame.core` above, so this require adds no bundle weight
            ;; (dev-tier preload — bundle-isolation-safe).
            [re-frame.trace :as trace]
            ;; `flush-render!` (the SYNCHRONOUS render-commit contract fn,
            ;; Spec 006 §`flush-render!`) lives in
            ;; re-frame.substrate.adapter, not re-frame.core. It resolves
            ;; the INSTALLED adapter via `require-adapter!` and routes the
            ;; flush through that adapter's substrate-native impl (React
            ;; `flushSync` for the React-shaped substrates; `reagent.core/
            ;; flush` for the ratom family) — ZERO substrate hardcoding here.
            ;; `dispatch-and-settle!` calls it to flush pending
            ;; renders/unmounts synchronously so their `:rf.view/render` /
            ;; `:rf.view/unmounted` traces land in (and re-fan back to the
            ;; causing) epoch before we re-read it. Dev-tier preload, so the
            ;; direct require is bundle-isolation-safe.
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            ;; `parse-source-coord` (the canonical inverse of
            ;; `format-source-coord`) is the source-coord contract owner's
            ;; parser for the `data-rf2-source-coord` DOM attribute. The pair
            ;; preload routes through this one canonical impl in core.
            ;; Dev-tier preload, so the direct require is bundle-isolation-safe.
            [re-frame.source-coords :as source-coords]
            [clojure.data :as data]
            [clojure.set :as set]
            [clojure.string :as str]
            ;; cljs.reader is load-bearing for the eval-cljs typed result
            ;; codec: both `re-frame2-pair-mcp.tools.result-envelope/
            ;; wrap-form` (the DEFAULT eval path) and `…tools.await-promise/
            ;; read-mailbox-form` (the `:await true` path) emit wrapper
            ;; source that calls `cljs.reader/read-string` to round-trip-
            ;; probe serializability inside the running runtime. cljs.reader
            ;; is NOT auto-loaded by an arbitrary consumer build, so without
            ;; this require shadow's `cljs-eval` resolves the wrapper's
            ;; `cljs.reader/read-string` as an :undeclared-var, returns an
            ;; empty `:results`, and the server reads the blank value as a
            ;; bare `nil` — collapsing EVERY wrapped eval to `:value nil`.
            ;; The require keeps the symbol resolvable so the codec round-
            ;; trips correctly. The runtime preload is the codec's guaranteed-present
            ;; substrate (re-frame2-pair-mcp refuses every tool with
            ;; `:runtime-not-preloaded` when this ns is absent), so pinning
            ;; the require here makes the symbol resolvable in every app the
            ;; pair tool can attach to. The require exists purely to pull
            ;; the namespace into the compiled bundle; the only references
            ;; are inside `pr-str`'d wire-form STRINGS (the fully-qualified
            ;; `cljs.reader/read-string`), invisible to the analyzer. Left
            ;; bare (no `:as`) — clj-kondo does not flag an unaliased
            ;; load-for-side-effect require as unused.
            cljs.reader))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per preload. The MCP server probes either
;; `re-frame2-pair.runtime/session-id` (CLJS var) or its mirror at
;; `js/globalThis.__re_frame2_pair_runtime` (cheaper, no compile) to
;; confirm this namespace landed in the running browser session.

(def session-id
  (str (random-uuid)))

;; Load-time wall-clock stamp for this preload instance — the moment
;; THIS compiled `re-frame2-pair.runtime` namespace evaluated in the
;; browser. Captured ONCE at namespace-load and never mutated (a `def`,
;; not a per-call `(js/Date.now)`), so it answers "when did the running
;; code actually load?" rather than "what time is it now?".
;;
;; This is the browser half of the stale-BUILD detector:
;; `discover-app` cross-checks it against the JVM-side build's last
;; compile/flush timestamp. If the build recompiled AFTER this stamp,
;; the browser tab is serving OLD code (a hot-reload didn't land, or a
;; stale incremental build is being served) — a silent failure mode the
;; detector catches before an agent reasons off stale code. A full
;; page refresh re-evaluates the namespace and re-mints both `session-id`
;; and this stamp, so a fresh load reads as fresh.
(def loaded-at
  (js/Date.now))

;; Globally-visible mirror — the preload probe in
;; `re-frame2-pair-mcp.tools/ensure-runtime!` reads this rather than
;; resolving the CLJS var, so the probe is one bencode round-trip on
;; the persistent socket. Set at namespace-load time; cleared by a
;; full page refresh (along with everything else). The marker uses
;; `js-obj` (not the `#js` reader literal) so the bb-runnable
;; structural tests under `tests/runtime/` keep reading the rest of
;; the file — bb's reader rejects `#js` at top level.
(defonce ^:private install-global-sentinel!
  (do (when (exists? js/globalThis)
        (aset js/globalThis "__re_frame2_pair_runtime"
              (js-obj "session-id" session-id
                      "installed"  loaded-at)))
      true))

(defn sentinel
  "Return the session sentinel. Used by the shim to confirm the runtime
   is still alive in the current browser runtime."
  []
  {:ok?        true
   :session-id session-id
   :installed  loaded-at})

;; ---------------------------------------------------------------------------
;; Freshness / liveness token
;; ---------------------------------------------------------------------------
;;
;; A pair session is a THIN runtime-direct reader, not a stateful proxy.
;; Every read hits the live ring; the freshness token makes the OTHER
;; drift mode — "the connection / build I'm reading is stale" — obvious
;; up front rather than something an agent infers ~30 minutes later from
;; a wrong conclusion. The token carries the BROWSER half of the signal:
;;
;;   :runtime-instance-id  — the per-preload `session-id` UUID. Changes
;;                           on every full page reload / CLJS heap reset.
;;                           An agent that cached an id and sees a new one
;;                           knows the tab refreshed (stale handles must
;;                           re-discover).
;;   :runtime-loaded-at    — `loaded-at`, the wall-clock moment THIS code
;;                           evaluated. The JVM-side `discover-app` probe
;;                           compares it against the build's last compile
;;                           timestamp to detect a stale-BUILD runtime.
;;   :read-at              — `(js/Date.now)` at the moment of THIS read.
;;                           With `:runtime-loaded-at` it yields uptime;
;;                           it also proves the runtime answered the eval
;;                           (a blank/nil read means no runtime answered).
;;
;; The JVM half (monotonic compile-cycle, last flush timestamp, REPL
;; runtime ping/pong heartbeat) is assembled server-side in
;; `re-frame2-pair-mcp.tools.freshness` and merged with this map — only
;; the JVM holds the shadow-cljs worker state.

(defn freshness
  "The browser-runtime half of the freshness/liveness token.
   Cheap — three scalar reads, no app-db walk, no listener install. The
   MCP server merges this with the JVM-side build/heartbeat half.

   Returns:
     {:runtime-instance-id <uuid-string>
      :runtime-loaded-at   <ms>
      :read-at             <ms>}"
  []
  {:runtime-instance-id session-id
   :runtime-loaded-at   loaded-at
   :read-at             (js/Date.now)})

;; ---------------------------------------------------------------------------
;; Operating frame
;; ---------------------------------------------------------------------------
;;
;; re-frame2 is multi-frame (Spec 002). Every read/write op resolves an
;; *operating frame* — the session-cached default, overridable per call.
;; Mutating ops refuse with :ambiguous-frame when more than one APP frame
;; is registered and the session hasn't selected one.
;;
;; Reserved-frame-aware resolution
;; ---------------------------------------------
;;
;; A pairing session almost always runs against an app that ALSO carries
;; a `:rf/*` TOOL frame — Xray's `:rf/xray` inspector frame, a stories
;; build, an SSR slot. Those frames live under the framework-reserved
;; `:rf/*` root (spec/Conventions.md §Reserved namespaces). They are NOT
;; the app the operator is pairing against; they are devtool surfaces the
;; tooling itself mounted. Counting them toward ambiguity would make every
;; Xray-instrumented app (the common case) "ambiguous" on the first
;; mutating op — forcing a `frames/select` + retry up front for no real
;; choice (there is exactly one APP frame; the other is a tool frame).
;;
;; So the resolver is RESERVED-FRAME-AWARE: a `:rf/*`-namespaced frame is
;; a tool frame and is EXCLUDED from the ambiguity count, with ONE
;; deliberate exception — `:rf/default`, which per Conventions.md §Reserved
;; namespaces is an ordinary app frame id with no framework privilege. It
;; is an APP frame, not a tool frame, despite sharing the `:rf/*` root. We
;; key off the reserved-namespace RULE (namespace = "rf", minus the
;; `:rf/default` carve-out), never a literal `:rf/xray`, so the behaviour
;; holds for any tool frame any project mounts under `:rf/*`.
;;
;; When exactly one APP frame remains after excluding tool frames, tier 3
;; AUTO-SELECTS it: single-app + Xray is unambiguous with no `frames/
;; select` tax. Two-plus app frames stay genuinely ambiguous (tier 4).
;;
;; The public model is `image -> frame -> event stream` and the public address
;; is a FRAME id (a single process-local frame-id space). The resolver counts
;; app frames directly; there is no container/realm dimension to scope by.

(defonce ^:private selected-frame (atom nil))

(defn select-frame!
  "Pin the operating frame for this session. Subsequent ops use it
   unless an explicit `:frame` opt is passed."
  [frame-id]
  (reset! selected-frame frame-id)
  {:ok? true :frame frame-id})

(defn reserved-tool-frame?
  "True when `frame-id` names a framework-reserved `:rf/*` TOOL frame —
   a devtool surface (Xray's `:rf/xray`, an SSR slot, …) the tooling
   mounted, NOT an app frame the operator is pairing against.

   The rule is the reserved-namespace convention (spec/Conventions.md
   §Reserved namespaces — framework-owned ids live under the single `:rf/*`
   root), NOT a hardcoded id, so it holds for every `:rf/*` tool frame any
   project mounts. The SOLE carve-out is `:rf/default`: per Conventions.md
   §Reserved namespaces it is an ordinary app frame id with no framework
   privilege — it shares the `:rf/*` root but is a normal app frame an app
   may explicitly register, so it is never treated as a tool frame.

   Non-keyword / un-namespaced ids (a user's `:stories`, `:sandbox`) are
   app frames and return false."
  [frame-id]
  (and (keyword? frame-id)
       (= "rf" (namespace frame-id))
       (not= :rf/default frame-id)))

(defn app-frame-ids
  "The registered APP frame ids — `(rf/frame-ids)` with `:rf/*` reserved TOOL
   frames removed. `:rf/default` is retained (it is an app frame;
   see `reserved-tool-frame?`). The order/source mirrors `(rf/frame-ids)`."
  []
  (vec (remove reserved-tool-frame? (rf/frame-ids))))

(defn current-frame
  "Resolve the operating frame: explicit override -> session pin ->
   the sole registered APP frame -> nil (ambiguous).

   Tier 3 is reserved-frame-aware: `:rf/*` TOOL frames (Xray's
   `:rf/xray`, SSR slots, …) are EXCLUDED, so a single-app session that ALSO
   carries an Xray frame resolves to the one app frame instead of refusing.
   `:rf/default` is an app frame and is retained (see `reserved-tool-frame?`).
   When two-plus APP frames remain the resolver yields nil and mutating ops
   refuse via the `:ambiguous-frame` path — reads that nil-default to
   `:rf/default` would silently land in the wrong frame, so the resolver stays
   conservative: callers either pin via `select-frame!`, pass an explicit
   override, or get a clear refusal."
  ([] (current-frame nil))
  ([override]
   (or override
       @selected-frame
       (let [app-fids (app-frame-ids)]
         (when (= 1 (count app-fids))
           (first app-fids))))))

(defn frames-list
  "All registered, non-destroyed frame ids plus the operating frame (the
   PUBLIC address — the image -> frame -> event stream model).

   `:app-frames` exposes the reserved-frame-aware view: the
   registered frames with `:rf/*` tool frames removed. When it holds exactly
   one id while `:frames` holds more, the session is single-app-plus-tool-frame
   and `:operating` auto-resolved to that lone app frame (no `select-frame!`
   was needed)."
  []
  (let [fids (vec (rf/frame-ids))]
    {:ok?              true
     :frames           fids
     :app-frames       (app-frame-ids)
     :selected         @selected-frame
     :operating        (current-frame)}))

(defn frames-meta
  "Flat metadata map for frame `id` — `(rf/frame-meta id)`. Returns `:id`,
   `:created-at`, the preset-expansion keys (`:preset`, `:fx-overrides`,
   `:drain-depth`, …) and lifecycle fields (`:destroyed?`, `:listeners`)
   all at the top level. See `:rf/frame-meta` in Spec-Schemas."
  [id]
  (or (rf/frame-meta id)
      {:ok? false :reason :no-such-frame :frame-id id}))

;; ---------------------------------------------------------------------------
;; Ambiguous-frame diagnostics
;;
;; A read/mutate op that can't resolve a single operating frame in a
;; multi-frame session refuses with an ENRICHED envelope so the agent can
;; recover without a round-trip to `frames-list` / `discover-app` just to
;; learn WHICH frames it can pick from and HOW to pin one.
;; `ambiguous-frame-error` builds that envelope once so every refusal site
;; carries:
;;
;;   :operation       the op that refused (`:dispatch`, `:read-sub`, …) —
;;                    the machine handle for the failing call.
;;   :event / :query  the event-vector / query-vector when the op knows it
;;                    (omitted for context-free ops like `sub-cache-info`).
;;   :available-frames the registered APP frames —
;;                    the set the caller may pin / pass (`app-frame-ids`).
;;   :selected-frame   the current session pin (nil = none), so the caller
;;                    knows whether a prior `select-frame!` is in effect.
;;   :hint             the human sentence + the concrete fix (pass `frame`
;;                    or pin via `select-frame!` / `set-operating-frame`).
;;
;; `:reason :ambiguous-frame` is the SOLE machine discriminator (the
;; documented bare-dialect reason, Tool-Catalogue §307) — the other slots
;; are additive context only, not part of the discriminator.
;; ---------------------------------------------------------------------------

(defn ambiguous-frame-error
  "Build the enriched `:ambiguous-frame` refusal envelope.
   `operation` is the refusing op keyword; `extra` (optional) carries the
   op-specific context the caller knows — `:event`, `:query`, `:query-v`.
   The envelope always carries the available app frames, the current session
   pin, and the concrete fix in `:hint`."
  ([operation] (ambiguous-frame-error operation nil))
  ([operation extra]
   (let [frames (app-frame-ids)]
     (merge
       {:ok?              false
        :reason           :ambiguous-frame
        :operation        operation
        :available-frames frames
        :selected-frame   @selected-frame
        :hint             (str "multiple app frames are registered and no frame is "
                               "selected, so " (name operation) " cannot pick a target. "
                               "Pass `frame` (one of " (pr-str frames) ") or pin one with "
                               "`select-frame!` / set-operating-frame, then retry.")}
       extra))))

;; ---------------------------------------------------------------------------
;; app-db read/write
;; ---------------------------------------------------------------------------
;;
;; All app-db access is via the public Tool-Pair surfaces:
;;   (rf/app-db-value frame-id)        — current value
;;   (rf/snapshot-of path opts)        — path-scoped read with :frame opt

(defn snapshot
  "Full current app-db value for the operating frame. No-arg form uses
   the session's operating frame; arity-1 takes an explicit frame-id."
  ([] (snapshot (current-frame)))
  ([frame-id]
   (rf/app-db-value frame-id)))

(defn app-db-at
  "Read a path in app-db for the operating frame.
   Sugar over (rf/snapshot-of path {:frame frame-id})."
  ([path] (app-db-at path (current-frame)))
  ([path frame-id]
   (rf/snapshot-of path {:frame frame-id})))

;; ---------------------------------------------------------------------------
;; Raw-state posture
;; ---------------------------------------------------------------------------
;;
;; The MCP server (`tools/re-frame2-pair-mcp/`) carries a
;; `--allow-sensitive-reads` boot gate (default OFF; CLI flag name
;; aligned across MCP servers). The internal Clojure keyword
;; `:allow-raw-state?` below is the implementation-side identifier. When
;; OFF, the runtime MUST default-elide any verbatim app-db value before
;; emitting it through `tap>` — otherwise an `app-db-reset!` log entry
;; would surface the same raw payload that the wire path already redacts.
;;
;; The MCP server signals the runtime once per build per server lifetime
;; via `(configure-raw-state! {:allow-raw-state? bool})`. The flag is
;; consulted by `app-db-reset!` (and any other raw-state tap site)
;; before deciding whether to ship verbatim payloads or run them through
;; `re-frame.core/elide-wire-value`.
;;
;; Default raw-allowed — a runtime loaded into an app without a
;; re-frame2-pair-mcp server sees the gate as "raw allowed", so a direct
;; CLJS caller (developer at the REPL invoking `app-db-reset!`) gets
;; verbatim payloads. When a re-frame2-pair-mcp server attaches with its
;; own boot flag OFF, it flips the gate to "raw gated" on first tool use.

(defonce ^:private raw-state-config
  ;; {:allow-raw-state? bool}
  ;; Default true — raw `tap>` payloads ride unmodified UNTIL the MCP
  ;; server signals otherwise. A bare CLJS REPL session (no re-frame2-pair-mcp
  ;; attached) sees verbatim payloads.
  (atom {:allow-raw-state? true}))

(defn configure-raw-state!
  "Set the raw-state posture for tap>-emitting surfaces. Opts:

     :allow-raw-state?  boolean — when true (default), `app-db-reset!`
                        taps verbatim pre- and post-reset app-db
                        values. When false, the values are walked
                        through `re-frame.core/elide-wire-value` so
                        large / sensitive slots redact before any tap
                        consumer sees them.

   Returns the merged config map. Idempotent. Called by
   `re-frame2-pair-mcp.tools.raw-state/signal-runtime!` once per build
   per server lifetime; safe to call by hand from a REPL to flip the
   posture.

   Per Spec 009 §Privacy: re-frame2-pair-mcp's published-build default has the
   server's boot gate OFF, so the runtime ends up in
   `:allow-raw-state? false` mode the moment a state-emitting MCP tool
   first fires. Operators who passed `--allow-sensitive-reads` at server
   launch get `:allow-raw-state? true` instead."
  [{:keys [allow-raw-state?] :as opts}]
  (swap! raw-state-config merge (select-keys opts [:allow-raw-state?]))
  (assoc @raw-state-config :ok? true))

(defn- maybe-elide-for-tap
  "Walk `v` through `re-frame.core/elide-wire-value` when the raw-state
  gate is OFF, otherwise pass through. The walker substitutes large
  slots with the `:rf.size/large-elided` marker and sensitive slots
  with `:rf/redacted` — same redaction the wire path applies before
  emitting state over MCP, applied here BEFORE any registered tap
  consumer sees the payload.

  `frame-id` is supplied so the walker resolves the right
  `[:rf.runtime/elision]` registry (in the frame's runtime-db)."
  [v frame-id]
  (if (:allow-raw-state? @raw-state-config)
    v
    (rf/elide-wire-value v {:frame frame-id})))

(defn- maybe-redact-derived
  "PATH-project a DERIVED `tree` (rendered DOM text / an attribute map / a
  focus descriptor) for off-box egress.

  The path-based `elide-wire-value` walker redacts by DECLARED app-db path.
  Rendered DOM text / attribute values that sit AT a classified path within
  `tree` redact; values RE-KEYED to a non-app-db position the path walker can
  never reach ship RAW. The framework boundary `re-frame.core/project-egress`
  — the `:rf.observe/derived-tree` record kind (EP-0025 B4, rf2-ojp8pi) — walks
  the tree through `elide-wire-value` against the frame's classification (the
  SAME per-frame registry the `:app-db` path walker reads: frame- /
  EP-0025-commit-plane-effect- / flow-sourced declarations, unioned).

  EP-0025 FAIL-OPEN: the value-match (taint-by-equality) engine is REMOVED — a
  secret copied from a declared-sensitive app-db slot into a non-app-db DOM
  position ships off-box RAW. This is INTENDED (hygiene, not a guarantee); to
  keep a value out of rendered content, classify its app-db PATH so it is
  redacted at the source before a view renders it. This is the SAME boundary
  Story-MCP routes its rendered hiccup / `:effective-args` through.

  Gate posture mirrors `maybe-elide-sample` / the MCP read surfaces:

  - Gate OFF (`:allow-raw-state? false`, the published-build default the MCP
    server signals via `configure-raw-state!` when its boot gate is OFF): the
    tree is PATH-walked under the `:rf.egress/off-box-tool` profile. A secret
    AT a classified path within the tree lands as `:rf/redacted`; a re-keyed
    copy ships raw (fail-open).
  - Gate ON (`--allow-sensitive-reads`): the operator's deliberate trusted-
    local raw read — `project-egress` under `:rf.egress/local-raw` passes the
    tree through verbatim.

  `project-egress` resolves the off-box-tool profile to the egress floor and
  reads the frame's live app-db itself (the derived-tree record's default
  `:source-db`). A nil `tree` / non-live frame short-circuits to `tree`
  unchanged (handled inside the boundary)."
  [tree frame-id]
  (rf/project-egress
    {:kind  :rf.observe/derived-tree
     :frame frame-id
     :tree  tree}
    {:rf.egress/profile (if (:allow-raw-state? @raw-state-config)
                          :rf.egress/local-raw
                          :rf.egress/off-box-tool)}))

(declare attach-cascade db-diff-summary machine-transitions-summary cascade-summary)

(defn app-db-reset!
  "Replace the operating frame's app-db with v. Logged explicitly via
   `tap>` so the human sees what the agent changed.

   Delegates to the canonical Tool-Pair write surface
   `(rf/replace-app-db! frame-id v)` (Tool-Pair §Pair-tool writes).
   That surface bypasses the dispatch loop (no event, no
   cascade) but DOES record a synthetic `:rf/epoch-record` with
   `:event-id :rf.epoch/db-replaced` so that `restore-epoch` can
   rewind past the injection. Use sparingly — prefer `dispatch` for
   any change you want the data loop to see.

   The `tap>` emission default-elides both `:previous`
   and `:next` slots when the raw-state gate is OFF (the published-
   build default for re-frame2-pair-mcp). The wire walker's normal large- and
   sensitive- predicates apply; large slots collapse to
   `:rf.size/large-elided` markers, sensitive slots to `:rf/redacted`.
   Operators who passed `--allow-sensitive-reads` see verbatim payloads.

   Returns `{:ok? true :frame frame-id :cascade-summary {...}}` on
   success. The cascade-summary slot projects the synthetic
   `:rf.epoch/db-replaced` epoch the framework just recorded — the
   `:event-id` is `:rf.epoch/db-replaced`, `:db-diff` summarises the
   before-vs-after delta at depth 1, and `:fx-fired` is empty (state
   injection bypasses fx).

   Failure modes (each is a no-op on app-db; corresponding
   `:rf.epoch/*` or `:rf.error/*` trace fires per Spec 009):
     :no-such-frame                  — frame not registered
     :replace-during-drain           — drain in flight
     :schema-mismatch                — v fails the frame's app-schema
     :epoch-artefact-missing         — re-frame2-epoch artefact not loaded"
  ([v] (app-db-reset! v (current-frame)))
  ([v frame-id]
   (tap> {:re-frame2-pair/op :app-db/reset
          :frame              frame-id
          :previous           (maybe-elide-for-tap (rf/app-db-value frame-id) frame-id)
          :next               (maybe-elide-for-tap v frame-id)
          :t                  (js/Date.now)})
   (try
     (if (rf/replace-app-db! frame-id v)
       ;; Surface the synthetic `:rf.epoch/db-replaced` epoch the
       ;; framework just appended (Tool-Pair §Pair-tool writes). The new
       ;; head IS this epoch by construction; reading the history head is
       ;; the canonical way to project it.
       (let [head-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
         (attach-cascade {:ok? true :frame frame-id :epoch-id head-id}
                         frame-id head-id))
       ;; replace-app-db! returns false on the soft-failure modes
       ;; (unknown frame, in-drain, schema-mismatch). The structured
       ;; reason is in the trace stream (`:rf.error/no-such-handler`,
       ;; `:rf.epoch/replace-during-drain`,
       ;; `:rf.epoch/replace-schema-mismatch`); we surface a
       ;; `:reset-rejected` umbrella so callers know the call did
       ;; not land without having to interpret the trace.
       {:ok?    false
        :frame  frame-id
        :reason :reset-rejected
        :hint   "rf/replace-app-db! returned false. Inspect (re-frame.trace.tooling/trace-buffer frame-id {:flat true :op-type :error}) (and :op-type :rf.epoch) for the structured reason — :rf.error/no-such-handler, :rf.epoch/replace-during-drain, or :rf.epoch/replace-schema-mismatch. Frame-id is the first positional arg; :op-type is a :flat-only filter. (rf/trace-buffer is JVM-only; CLJS callers use the re-frame.trace.tooling ns.)"})
     (catch :default e
       (let [{:keys [reason] :as data} (ex-data e)]
         {:ok?     false
          :frame   frame-id
          :reason  (or reason :reset-throw)
          :message (.-message e)
          :data    data})))))

(defn schemas
  "All registered app-schemas for the operating frame.
   Map of `path → schema`. (re-frame.schemas/app-schemas frame-id)"
  ([] (schemas (current-frame)))
  ([frame-id]
   (schemas/app-schemas frame-id)))

;; ---------------------------------------------------------------------------
;; Registrar introspection
;; ---------------------------------------------------------------------------

(defn registrar-list
  "Enumerate registered ids under a kind. (rf/registrations kind) returns
   `{id meta}`; we return the sorted id vector."
  [kind]
  (-> (rf/registrations kind) keys sort vec))

;; ---------------------------------------------------------------------------
;; Call-time id validation
;; ---------------------------------------------------------------------------
;;
;; The MCP wire boundary parses an event-vec / sub-vec / frame-id once,
;; ECHOes the resolved value, and VALIDATEs the id against the LIVE
;; registry. An unknown id returns a STRUCTURED error carrying nearest
;; matches — never a silent no-op success (the no-silent-swallow
;; principle, spec/Conventions.md, applied to the wire). The validation
;; is runtime-side because only the runtime holds the live registrar.
;;
;; This is the CALL-TIME VALUE check; it complements the attach-time pass
;; that generates and validates tool DESCRIPTORS from the registries.

(defn- levenshtein
  "Edit distance between two strings — the nearest-match ranking metric.
   Small bounded DP; ids are short keyword names so the O(mn) cost is
   negligible. Pure; no allocation beyond the rolling rows."
  [a b]
  (let [a (vec a) b (vec b)
        m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1
             prev (vec (range (inc n)))]
        (if (> i m)
          (peek prev)
          (let [cur (reduce
                      (fn [row j]
                        (let [cost (if (= (nth a (dec i)) (nth b (dec j))) 0 1)]
                          (conj row (min (inc (peek row))           ; deletion
                                         (inc (nth prev j))          ; insertion
                                         (+ (nth prev (dec j)) cost))))) ; substitution
                      [i]
                      (range 1 (inc n)))]
            (recur (inc i) cur)))))))

(defn nearest-ids
  "Up to `n` registered ids closest to `id` by edit distance over their
   `pr-str` rendering, nearest first. `known` is the seq of registered
   ids to rank against. Ties broken by `pr-str` for stable output. Used
   to build the 'unknown X; did you mean …?' hint."
  ([id known] (nearest-ids id known 3))
  ([id known n]
   (let [target (pr-str id)]
     (->> known
          (sort-by (juxt #(levenshtein target (pr-str %)) pr-str))
          (take n)
          vec))))

(defn validate-registered
  "Validate that `id` is registered under registrar `kind` against the
   LIVE registry. Returns:

     {:ok? true  :kind kind :id id}                         — registered
     {:ok? false :reason :unknown-id :kind kind :id id
      :nearest [...] :known-count N :hint \"...\"}            — not found

   The `:nearest` vector carries up to three closest registered ids by
   edit distance so the agent gets 'unknown :rf/xrayy; did you mean
   :rf/xray?' instead of a silent no-op. Never throws — a registrar that
   doesn't exist yields an empty known set, so an unknown id there still
   reports structured-unknown with `:known-count 0`."
  [kind id]
  (let [known (try (keys (rf/registrations kind)) (catch :default _ nil))
        known (vec (or known []))]
    (if (some #(= id %) known)
      {:ok? true :kind kind :id id}
      (let [near (nearest-ids id known)]
        {:ok?         false
         :reason      :unknown-id
         :kind        kind
         :id          id
         :nearest     near
         :known-count (count known)
         :hint        (if (seq near)
                        (str "unknown " kind " " (pr-str id) "; did you mean "
                             (clojure.string/join ", " (map pr-str near)) "?")
                        (str "unknown " kind " " (pr-str id)
                             "; nothing is registered under " kind "."))}))))

(defn validate-event-id
  "Validate the head of an event vector against the `:event` registrar.
   `event-v` is the parsed event vector; the id is its
   first element. Returns the `validate-registered` shape, plus echoes
   the `:event` vector so the wire result carries the resolved value."
  [event-v]
  (let [id (when (sequential? event-v) (first event-v))
        r  (validate-registered :event id)]
    (assoc r :event event-v)))

(defn validate-sub-id
  "Validate the head of a subscription query-vector against the `:sub`
   registrar. `query-v` is the parsed sub vector; the id is
   its first element. Returns the `validate-registered` shape, plus echoes
   the `:query-v` so the wire result carries the resolved value.

   The read-side counterpart of `validate-event-id`: a typo'd sub-id
   (`[:current-userr]`) returns `:reason :unknown-id` with `:nearest`
   matches instead of silently subscribing to a non-existent sub and
   handing back nil/garbage (the typo-silent-nil mistake class a raw
   `eval-cljs` read invites)."
  [query-v]
  (let [id (when (sequential? query-v) (first query-v))
        r  (validate-registered :sub id)]
    (assoc r :query-v query-v)))

(defn- handler-fn-hash
  "Opaque hash for hot-reload probe comparisons. Function refs aren't
   reliably `=`, so hash a stringified form."
  [meta-map]
  (some-> meta-map :handler-fn str hash))

(def ^:private fn-slot-sentinel
  "Readable EDN placeholder substituted for a Function value anywhere in a
   handler-meta map. `pr-str` of a raw Function emits `#object[Function …]`
   — unreadable EDN on the MCP wire, and the result-envelope codec then
   tags the WHOLE response `:unserializable`, hiding the serializable
   structure around it. Replacing each fn with this keyword
   keeps the map EDN-clean while still surfacing THAT a fn slot is declared
   — so an agent reads the shape (e.g. a resource-scope's `:inputs` map,
   `:whole-db?` flag, or a mutation's declared `:invalidates`/`:populates`)
   without the unreadable handle."
  :rf/fn)

(defn- strip-fns
  "Recursively replace every Function value in `x` with `fn-slot-sentinel`,
   leaving all other structure intact. Hand-rolled (the runtime preload
   does not require `clojure.walk`) and shallow-cheap: a handler-meta map is
   small. Maps/vectors/sets/seqs recurse; a fn becomes the sentinel; every
   other scalar passes through.

   This is the general counterpart to the top-level `:handler-fn` dissoc:
   the resources-artefact kinds (`:resource` / `:mutation` /
   `:resource-scope`) store their spec under `:rf/resource` /
   `:rf/mutation` / `:rf/resource-scope` with NESTED fns (`:request`,
   `:tags`, `:invalidates`, `:populates`, `:resolve`). The top-level dissoc
   alone leaves those nested handles in the map, so the response would still
   `pr-str` to `#object[Function]` and trip the codec's `:unserializable`
   path. Stripping at every depth keeps the inspectable structure (input
   shapes, scope policy, declared-consequence presence) on the wire."
  [x]
  (cond
    (fn? x)         fn-slot-sentinel
    (map? x)        (reduce-kv (fn [m k v] (assoc m k (strip-fns v))) (empty x) x)
    (map-entry? x)  x
    (vector? x)     (mapv strip-fns x)
    (set? x)        (into (empty x) (map strip-fns) x)
    (seq? x)        (map strip-fns x)
    :else           x))

(defn registrar-describe
  "Return public handler metadata for kind+id. (rf/handler-meta kind id)
   already gives the source coords (:ns :line :file :column), the
   :handler-fn, the :rf/machine? flag where applicable, and any extra
   keys the registrar carries (e.g. retained source forms when present).

   Augments with :handler-fn-hash for use as a probe over hot-reload.

   The raw :handler-fn slot is a Function, and `pr-str` of a Function
   emits `#object[Function ...]` — unreadable EDN on the MCP wire that
   would tag the whole handler-meta envelope :unexpected-shape. The hash
   covers every hot-reload probing use, so the raw fn ref is dropped
   before returning, leaving the response EDN-clean by construction.

   The resources-artefact kinds store their spec under `:rf/resource` /
   `:rf/mutation` / `:rf/resource-scope` with NESTED handler fns
   (`:request`, `:tags`, `:invalidates`, `:populates`, `:resolve`).
   Dropping only the top-level `:handler-fn` would leave those nested
   handles, so the response would still trip the wire codec's
   `:unserializable` path. `strip-fns` replaces every fn at any depth with
   the readable `:rf/fn` sentinel, so the serializable structure (a
   resource-scope's `:inputs` map + `:whole-db?` flag, a mutation's
   declared consequences, a resource's scope policy) survives on the wire."
  [kind id]
  (if-let [m (rf/handler-meta kind id)]
    (-> m
        (assoc :handler-fn-hash (handler-fn-hash m))
        (dissoc :handler-fn)
        strip-fns)
    {:ok? false :reason :not-registered :kind kind :id id}))

(defn registrar-handler-ref
  "Stable opaque identifier for the currently-registered handler. Used
   as a hot-reload probe: capture before edit, compare after. The hash
   changes on every re-registration (new fn ref, new source coords)."
  [kind id]
  (handler-fn-hash (rf/handler-meta kind id)))

;; ---------------------------------------------------------------------------
;; Frame-derived registrar introspection
;;
;; A frame's inspectable registration set is its RESOLVED IMAGE GENERATION —
;; the same `(kind, id)` can resolve DIFFERENTLY per frame (two frames running
;; different images each resolve their own descriptor). The process-global
;; registrar reads above answer "what is registered process-globally"; these
;; answer "what does THIS FRAME's running image resolve `(kind, id)` to" —
;; keyed off the frame's sealed generation, not the process-global registrar.
;;
;; They consume ONLY the PUBLIC facade reads — the
;; `{:frame f :kind k …}` arities of `rf/registrations` / `rf/handler-meta` /
;; `rf/handler-ids`, and `rf/frame-generation`. Tools must not consume
;; `re-frame.live-frame` / `re-frame.image-assembly` internals directly; the
;; facade re-surfaces the BEHAVIOUR through these reads, so this preload stays
;; on the public surface.
;;
;; PROVENANCE: a resolved descriptor carries the source coordinate that
;; identifies where the winning registration came from — `:rf.provenance/ns`
;; (a registered descriptor's authoring namespace), `:rf.provenance/inline` +
;; `:rf.provenance/image` (an image-supplied inline section), or `:standard
;; true` (a framework standard). These ride VERBATIM on the `:frame`-arity
;; `rf/handler-meta` result (the resolved descriptor), so an agent reading a
;; frame's `(kind, id)` sees WHICH source won — the distinguishing fact the
;; process-global reads cannot surface (they read the flat registrar atom, which
;; has no per-frame resolution / provenance).
;; ---------------------------------------------------------------------------

(defn- coordinate-summary
  "The provenance/standard coordinate facts a resolved descriptor carries,
   as a small EDN-clean map (or nil when the descriptor carries none — a
   process-global meta map). Surfaces WHICH source won the
   `(kind, id)` resolution:

     {:source :registered :ns \"my.app.events\"}      a registered descriptor
     {:source :inline :image <id> :inline [sec id]}   an image inline section
     {:source :standard}                              a framework standard

   `:rf.provenance/ns` rides through unchanged on the meta map too; this is the
   normalized rollup so agents read one `:rf.image/coordinate` slot rather than
   sniffing the raw provenance keys. Pure read of the meta map's slots."
  [m]
  (cond
    (:standard m)
    {:source :standard}

    (:rf.provenance/inline m)
    {:source :inline
     :image  (:rf.provenance/image m)
     :inline (:rf.provenance/inline m)}

    (some? (:rf.provenance/ns m))
    {:source :registered
     :ns     (:rf.provenance/ns m)}))

(defn frame-registrar-describe
  "Per-FRAME handler metadata for `(kind, id)` — the registration resolved
   through frame `frame-id`'s OWN sealed image generation (NOT the
   process-global registrar). Routes through the PUBLIC facade read
   `(rf/handler-meta {:frame f :kind k :id id})`.

   Surfaces the resolved descriptor's `:rf.provenance/ns` + inline/image +
   `:standard` facts the process-global reads can't — plus a normalized
   `:rf.image/coordinate` rollup naming WHICH source won. Same EDN-hygiene as
   `registrar-describe`: the raw `:handler-fn` is dropped (replaced by
   `:handler-fn-hash`) and every nested fn becomes the `:rf/fn` sentinel.

   Returns the meta map merged with the coordinate rollup on a hit, or
   `{:ok? false :reason :not-registered …}` when the frame's image carries no
   such `(kind, id)`. FAILS LOUD up the eval boundary when `frame-id` is not a
   live frame carrying a generation (`:rf.error/frame-no-generation`) — the
   facade's no-silent-fallback contract."
  [frame-id kind id]
  (if-let [m (rf/handler-meta {:frame frame-id :kind kind :id id})]
    (let [coord (coordinate-summary m)]
      (cond-> (-> m
                  (assoc :handler-fn-hash (handler-fn-hash m))
                  (dissoc :handler-fn)
                  strip-fns)
        coord (assoc :rf.image/coordinate coord)))
    {:ok? false :reason :not-registered :kind kind :id id :frame frame-id}))

(defn frame-registrar-list
  "Enumerate the ids registered under `kind` resolved through frame
   `frame-id`'s OWN image generation — only the ids that frame's image
   carries (NOT the process-global registrar). Routes through the PUBLIC facade
   read `(rf/handler-ids {:frame f :kind k})`. Returns the sorted
   id vector. FAILS LOUD up the eval boundary on an unresolvable frame."
  [frame-id kind]
  (-> (rf/handler-ids {:frame frame-id :kind kind}) sort vec))

(defn frame-registrar-registrations
  "The `{id meta}` map for `kind` resolved through frame `frame-id`'s OWN image
   generation, each meta carrying its provenance/coordinate facts. Routes
   through the PUBLIC facade read `(rf/registrations {:frame f :kind k})`.
   Fns are stripped (the `:rf/fn` sentinel) so the map is
   EDN-clean. FAILS LOUD up the eval boundary on an unresolvable frame."
  [frame-id kind]
  (reduce-kv
    (fn [acc id m]
      (let [coord (coordinate-summary m)]
        (assoc acc id (cond-> (strip-fns (dissoc m :handler-fn))
                        coord (assoc :rf.image/coordinate coord)))))
    {}
    (rf/registrations {:frame frame-id :kind kind})))

(defn describe-image
  "Describe the IMAGE GENERATION a frame is running. Answers \"what
   behaviour does THIS frame run, and where did each piece come from?\" in
   one round-trip, over the PUBLIC `rf/frame-generation` read; tools must
   not consume the `re-frame.image-assembly` internals directly.

   Frame resolution mirrors every other read op: no-arg / `{:frame nil}` uses
   the OPERATING frame, an explicit `:frame` targets that frame. Returns
   `{:ok? false :reason :ambiguous-frame …}` when no frame can be resolved
   (multi-frame session with no selection) rather than silently reading
   `:rf/default`.

   Slices (all derived from the sealed generation's `:rf.gen/*` keys):

     :images        the image ids composed into the generation (the
                    normalized `:rf.gen/images`, reduced to `:rf.image/id`
                    where present so the listing stays compact).
     :kinds         the registrar kinds the generation carries
                    (`:rf.gen/kinds`), sorted.
     :counts        `{kind N …}` — selected-registration counts per kind off
                    the resolver, so an agent sees the SELECTED universe size
                    without enumerating every id.
     :registrations when `:include-ns?` is true, `{[kind id] coordinate …}`
                    over the resolver — every selected `(kind, id)` with its
                    provenance/standard coordinate (which source won). Default
                    OFF (the full resolver can be large); the counts + the
                    per-kind `frame-registrar-list` drill cover the common
                    case.

   `:include-ns?` (default false) gates the per-registration provenance map.

   NO-GENERATION FRAME: only an EXPLICIT `:images` key triggers
   image resolution, so a frame configured with NO `:images` is an ordinary
   frame on the shared registrar that carries no composed image — and the public
   `rf/frame-generation` read FAILS LOUD (`:rf.error/frame-no-generation`) for
   it (the intended no-generation contract). That is not an error for THIS read:
   an imageless frame simply runs no composed image, so `describe-image` reports
   it gracefully — `{:ok? true … :images [] :kinds [] :counts {}
   :no-generation? true}` — rather than letting the fail-loud escape up the eval
   boundary. The discriminator is the error's `:live-frame-ids`: a frame-id that
   IS a live frame but carries no generation is the graceful imageless case; an
   UNRESOLVABLE target (an id naming no live frame at all) still FAILS LOUD up
   the eval boundary, the facade's no-silent-fallback contract."
  ([] (describe-image {}))
  ([opts]
   (let [{:keys [frame include-ns?]} opts
         frame-id (current-frame frame)]
     (if (nil? frame-id)
       (ambiguous-frame-error :describe-image)
       (let [gen      (try
                        (rf/frame-generation frame-id)
                        (catch :default e
                          (let [{err-id :rf.error/id
                                 live   :live-frame-ids} (ex-data e)]
                            ;; A live frame that carries no generation (an
                            ;; imageless frame) is the graceful
                            ;; no-image case, NOT a read failure. Surface the
                            ;; sentinel and let the caller report it cleanly.
                            ;; Any other thrown error — including a :frame
                            ;; target that names NO live frame — re-throws and
                            ;; fails loud up the eval boundary unchanged.
                            (if (and (= :rf.error/frame-no-generation err-id)
                                     (some #(= % frame-id) live))
                              ::no-generation
                              (throw e)))))]
         (if (= ::no-generation gen)
           {:ok?            true
            :frame          frame-id
            :images         []
            :kinds          []
            :counts         {}
            :no-generation? true}
           (let [resolver (:rf.gen/resolver gen)
                 kinds    (vec (sort (:rf.gen/kinds gen)))
                 counts   (reduce-kv
                            (fn [acc [k _id] _d] (update acc k (fnil inc 0)))
                            {}
                            resolver)]
             (cond-> {:ok?      true
                      :frame    frame-id
                      ;; Each composed image reduces to its `:rf.image/id` when
                      ;; it carries one (the named case); an ANONYMOUS image (no
                      ;; `:id`) has none, so surface its `:select-ns` include
                      ;; globs — what the image selected — rather than the whole
                      ;; map.
                      :images   (mapv (fn [img]
                                        (or (:rf.image/id img)
                                            (when (map? img)
                                              {:rf.image/include-ns (:rf.image/include-ns img)})
                                            img))
                                      (:rf.gen/images gen))
                      :kinds    kinds
                      :counts   counts}
               include-ns?
               (assoc :registrations
                      (reduce-kv
                        (fn [acc [k id] d]
                          (assoc acc [k id] (coordinate-summary d)))
                        {}
                        resolver))))))))))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(defn sub-cache
  "(rf/sub-cache frame-id) — public Tool-Pair surface returning
   `{query-v {:value v :ref-count n}}` for every materialised
   subscription in the operating frame. CLJS-only; nil on JVM."
  ([] (sub-cache (current-frame)))
  ([frame-id]
   (subs-tooling/sub-cache-snapshot frame-id)))

(defn sub-cache-info
  "List the LIVE reactive subscriptions materialised in a frame's
   per-frame sub-cache — the answer to \"what subscriptions are
   currently active?\".

   Reads the SAME source `snapshot`'s `:sub-cache` slice reads
   (`subs-tooling/sub-cache-snapshot`, via the `sub-cache` fn above), so
   the two never disagree. This is the live reactive cache: an entry
   appears the moment a view subscribes and DISAPPEARS when the last
   consumer disposes the reaction — so a disposed sub no longer shows
   here, matching the framework's ref-counted lifecycle.

   Distinct from `subscription-info` (the streaming-tap registry that
   `subscribe` / `unsubscribe` mutate — trace/epoch/fx/error queues, NOT
   reactive subs). The two surfaces answer different questions: this one
   is the reactive sub-cache; that one is the MCP streaming-tap
   diagnostic.

   Frame resolution mirrors every other read op — no-arg uses the
   operating frame, arity-1 takes an explicit frame-id. Returns
   `{:ok? false :reason :ambiguous-frame}` when no frame can be resolved
   (multi-frame session with no selection) rather than silently reading
   `:rf/default`.

   `:include-values?` (default false) controls payload size: when false
   only the query-vectors ride the wire (the cheap \"what's subscribed\"
   read); when true each entry also carries `:value` (the current
   deref), `:ref-count`, `:input-kind`, and `:realized-inputs`.

   `:input-kind` + `:realized-inputs` surface the
   subscription's input topology PER LIVE CACHE ENTRY — the realized
   counterpart to the static `sub-topology`. `:input-kind` is `:db` /
   `:static` / `:parametric`; `:realized-inputs` is the REALIZED input
   query-vectors for the concrete outer query-v (the literal `:<-` list
   for `:static`, the `(input-fn query-v)` result for `:parametric`,
   `[]` for layer-1). These are query-vectors (sub-id + args), NOT
   computed values, so they ride raw — the egress redaction in the
   `list-subscriptions` tool elides only the `:value` slot.

   Returns:
     `{:ok? true :frame <id> :count N
       :subs [<query-v> ...]}`                       ; :include-values? false
     `{:ok? true :frame <id> :count N
       :subs [{:query-v <v> :value v :ref-count n
               :input-kind k :realized-inputs [...]}]}` ; :include-values? true

   `:subs` is the empty vector when nothing is subscribed in the frame —
   never `:ok? false` for the empty case. The query-vectors are sorted
   (by `pr-str`) so the listing is stable across calls."
  ([] (sub-cache-info {}))
  ([opts]
   (let [{:keys [frame include-values?]} opts
         frame-id (current-frame frame)]
     (if (nil? frame-id)
       (ambiguous-frame-error :sub-cache-info)
       (let [cache (or (subs-tooling/sub-cache-snapshot frame-id) {})
             qvs   (sort-by pr-str (keys cache))]
         {:ok?   true
          :frame frame-id
          :count (count qvs)
          :subs  (if include-values?
                   (mapv (fn [q]
                           (let [{:keys [value ref-count input-kind realized-inputs]}
                                 (get cache q)]
                             {:query-v         q
                              :value           value
                              :ref-count       ref-count
                              :input-kind      input-kind
                              :realized-inputs realized-inputs}))
                         qvs)
                   (vec qvs))})))))

(defn subs-sample
  "Subscribe to query-v in the operating frame and deref once. Goes
   through `rf/subscribe` so the cache lifecycle is the standard one —
   fine for one-shot probes, not for repeated polling outside a
   reactive context.

   Threads the resolved operating frame through `(rf/subscribe
   frame-id query-v)` so a prior `select-frame!` (or an explicit
   `frame-id` arg) actually steers the read. Returns
   `{:ok? false :reason :ambiguous-frame}` if no frame can be
   resolved — read ops shouldn't silently fall back to `:rf/default`
   in a multi-frame session."
  ([query-v] (subs-sample query-v (current-frame)))
  ([query-v frame-id]
   (cond
     (nil? frame-id)
     (ambiguous-frame-error :subs-sample {:query query-v})

     :else
     (try
       @(rf/subscribe frame-id query-v)
       (catch :default e
         {:ok? false :reason :sub-error :message (.-message e) :frame frame-id})))))

(defn read-sub!
  "Validated one-shot subscription read — the read-side
   no-silent-swallow counterpart to `dispatch-consequence!`.

   The #1 read on any re-frame2 app is a subscription value, and dropping
   to raw `eval-cljs` `@(rf/subscribe [:foo])` is stringly + UNVALIDATED:
   a typo'd sub-id silently subscribes to a non-existent sub and returns
   nil/garbage. `read-sub!` closes that mistake class with the SAME
   discipline `dispatch-consequence!` applies on the write side:

     1. VALIDATE the sub-id (the query-vector's head) against the LIVE
        `:sub` registrar FIRST (`validate-sub-id`). An unknown id returns
        the structured `:reason :unknown-id` + `:nearest` matches WITHOUT
        subscribing — never a silent nil. The result echoes `:query-v`.
     2. Resolve the operating frame the same way every other read op does
        (explicit override -> session pin -> sole app frame). No frame ->
        `:reason :ambiguous-frame` (never silently reads `:rf/default`).
     3. Subscribe + deref ONCE through `rf/subscribe` (the standard cache
        lifecycle), inside a `try`. A deref/computation throw returns
        `:reason :sub-error` carrying the message — a structured error,
        never a bare nil.

   On success returns `{:ok? true :query-v <v> :frame <id> :value <v>}`.
   The `:value` is the RAW deref; the MCP `read-sub` tool wraps it through
   `re-frame.core/elide-wire-value` at the wire boundary (the privacy
   posture, like snapshot's `:sub-cache` slice + `get-path`) — this
   runtime fn returns the verbatim value so direct CLJS callers (and the
   wire wrapper) see the real thing.

   `query-v` must be a non-empty sequential (a sub vector). A non-vector /
   empty shape returns `:reason :not-a-sub-vector` — the read-side
   analogue of dispatch's `:not-an-event-vector`."
  ([query-v] (read-sub! query-v (current-frame)))
  ([query-v frame-id]
   (cond
     (or (not (sequential? query-v)) (empty? query-v))
     {:ok?    false
      :reason :not-a-sub-vector
      :query-v query-v
      :hint   "a subscription read needs a non-empty query vector, e.g. [:current-user] or [:cart/total]."}

     :else
     (let [v (validate-sub-id query-v)]
       (cond
         ;; Unknown sub-id — structured error, NO subscribe (no silent
         ;; nil). Echo the resolved query-v alongside the nearest matches.
         (not (:ok? v))
         (assoc v :subscribed? false)

         (nil? frame-id)
         (ambiguous-frame-error :read-sub {:query query-v :query-v query-v})

         :else
         (try
           {:ok?     true
            :query-v query-v
            :frame   frame-id
            :value   @(rf/subscribe frame-id query-v)}
           (catch :default e
             {:ok?     false
              :reason  :sub-error
              :query-v query-v
              :frame   frame-id
              :message (.-message e)
              :hint    "the subscription handler threw while computing its value — inspect the sub's reg-sub body."})))))))

;; ---------------------------------------------------------------------------
;; Machines (Spec 005)
;; ---------------------------------------------------------------------------

(defn machines-list
  "(rf/machines) — all registered machine ids."
  []
  (vec (machines/machines)))

(defn machine-describe
  "(rf/machine-meta id) — registered spec map for one machine, or
   `{:ok? false :reason :not-a-machine}`."
  [machine-id]
  (or (machines/machine-meta machine-id)
      {:ok? false :reason :not-a-machine :id machine-id}))

(defn machine-state
  "Snapshot of one machine in the operating frame. Per Spec 005,
   machine snapshots live at `[:rf.runtime/machines :snapshots machine-id]`
   in the durable RUNTIME-DB partition — read via `rf/runtime-db-value`,
   NOT `rf/snapshot-of`, which reads app-db."
  ([machine-id] (machine-state machine-id (current-frame)))
  ([machine-id frame-id]
   (get-in (rf/runtime-db-value frame-id)
           [:rf.runtime/machines :snapshots machine-id])))

;; ---------------------------------------------------------------------------
;; Epoch history & assembled-stream listener
;; ---------------------------------------------------------------------------
;;
;; re-frame2 ships first-class epoch recording. The listener fires once
;; per drain-settle with the assembled `:rf/epoch-record`. We register
;; ours under id :re-frame2-pair-epoch — multi-tool coexistence per
;; Spec 009 §Listener ordering.
;;
;; Runtime-direct reads, no parallel epoch capture buffer.
;; The pair session is a THIN, runtime-direct reader: every epoch read
;; (`epoch-history`, `epochs-since`, `last-epoch`, `find-where`, and the
;; MCP `trace-window` / `watch-epochs` tools that eval them) hits
;; `(rf/epoch-history frame-id)` — the framework's AUTHORITATIVE ring,
;; the SAME source `eval-cljs` reaches directly. There is deliberately
;; NO session-side capture buffer that mirrors the ring: such a buffer
;; only fills while a listener is attached, so it would return EMPTY while
;; the ring HELD epochs — a silent-WRONG-read. The epoch
;; listener below drives ONLY the per-frame app-db-hash cache (a derived
;; scalar, not a record copy) and the streaming-subs fan-out.

(defonce ^:private pair-epoch-ids
  ;; Set of epoch-ids that this skill itself dispatched (used by
  ;; last-pair-epoch). Populated by the dispatch helpers below.
  (atom #{}))

;; ---- O(1) per-frame app-db hash cache ------------------------------------
;;
;; The re-frame2-pair-mcp precheck issues `(hash app-db)` to decide a
;; cache hit before running the tool eval. `(hash <persistent-map>)` is
;; cached on the map node itself in CLJS — the first call walks; every
;; subsequent call returns the cached integer in O(1) (per
;; `cljs.core/-hash` on `PersistentArrayMap` / `PersistentHashMap`). So
;; the wire saving from a precheck-side cache here is small for the
;; precheck hot path itself.
;;
;; What *is* expensive is the route through `(re-frame2-pair.runtime/
;; snapshot frame)` → `(rf/app-db-value frame-id)` → dereferences and
;; map lookups, which the precheck eval form has to thread on every
;; call. With the per-frame cached integer here, the precheck form
;; resolves to a single atom deref + map lookup, completely independent
;; of app-db size or structure.
;;
;; The cache is updated whenever an epoch settles — every mutation path
;; (dispatch via the router, `rf/replace-app-db!` synthetic `:rf.epoch/
;; db-replaced`, `rf/restore-epoch!`) produces an assembled-epoch record
;; that arrives at `on-epoch-streaming`. We update the cache there from
;; `(:db-after record)`. On the first read for a frame, if the slot is
;; absent (no epoch has fired yet for this frame), we compute it lazily
;; from `(rf/app-db-value frame-id)` and stash it.
;;
;; `app-db-hash` is the only accessor; callers needing a path-scoped
;; hash hash the slice themselves until a sub-tree accessor is filed.

(defonce ^:private frame-db-hashes
  ;; frame-id -> cached `(hash app-db)` integer
  (atom {}))

(defn- update-frame-db-hash!
  "Update the cached hash for `frame-id` from the epoch record's
   `:db-after` slot. Called from the epoch listener."
  [frame-id db-after]
  (swap! frame-db-hashes assoc frame-id (hash db-after)))

(defn app-db-hash
  "Cheap O(1) accessor for the current `(hash app-db)` of `frame-id`.

   Cached by the epoch listener at every settled mutation. On the first
   read for a frame whose hash hasn't been observed yet (no epoch
   fired since session start), the value is computed lazily from
   `(rf/app-db-value frame-id)` and stashed.

   Returns an integer hash, or `nil` if the frame doesn't exist.

   The re-frame2-pair-mcp precheck form threads through this accessor
   so the cache-hit decision is a single integer compare rather than a
   full app-db walk."
  ([] (app-db-hash (current-frame)))
  ([frame-id]
   (when frame-id
     (or (get @frame-db-hashes frame-id)
         (let [db (rf/app-db-value frame-id)
               h  (hash db)]
           (swap! frame-db-hashes assoc frame-id h)
           h)))))

;; The per-frame app-db-hash cache and the streaming dispatch both ride
;; the same `register-epoch-listener!` slot — combined into
;; `on-epoch-streaming` below to keep listener ordering deterministic.
;; The listener derives a scalar hash and fans events to subscribers; it
;; does NOT retain a copy of the ring (reads go straight to
;; `(rf/epoch-history frame-id)`).

(declare on-epoch-streaming)

(defn- ensure-epoch-listener!
  "Register the assembled-epoch listener if it isn't already. Idempotent —
   passing the same id twice replaces (per `register-epoch-listener!` contract).

   Installs the streaming-aware listener. The streaming
   dispatch is a no-op when no subscriptions are active, so this is
   safe to install unconditionally."
  []
  (rf/register-epoch-listener! :re-frame2-pair-epoch on-epoch-streaming))

(defn epoch-history
  "Pass-through to (rf/epoch-history frame-id) — the framework's
   per-frame ring, oldest-first."
  ([] (epoch-history (current-frame)))
  ([frame-id]
   (vec (rf/epoch-history frame-id))))

(defn last-epoch
  "Most recent epoch in the operating frame's history."
  ([] (last-epoch (current-frame)))
  ([frame-id]
   (peek (rf/epoch-history frame-id))))

(defn last-pair-epoch
  "Most recent epoch this skill dispatched. Walks the operating frame's
   history backward, filtering by epoch-id membership in pair-epoch-ids."
  ([] (last-pair-epoch (current-frame)))
  ([frame-id]
   (let [ours @pair-epoch-ids]
     (->> (rf/epoch-history frame-id)
          reverse
          (some (fn [r] (when (contains? ours (:epoch-id r)) r)))))))

(defn epoch-by-id
  "Look up an epoch by id in the operating frame's history."
  ([epoch-id] (epoch-by-id epoch-id (current-frame)))
  ([epoch-id frame-id]
   (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
         (rf/epoch-history frame-id))))

(defn epochs-since
  "Records appended *after* the given epoch-id in the operating frame.
   Returns `{:epochs [...] :id-aged-out? bool :head-id <last-id>}`.

   Semantics:
     - `id` nil                  -> all records, :id-aged-out? false
     - `id` matches current head -> [], :id-aged-out? false
     - `id` matches some record  -> records strictly after it
     - `id` not found            -> [], :id-aged-out? true"
  ([epoch-id] (epochs-since epoch-id (current-frame)))
  ([epoch-id frame-id]
   (let [history (vec (rf/epoch-history frame-id))
         head-id (some-> history peek :epoch-id)]
     (cond
       (nil? epoch-id)
       {:epochs history :id-aged-out? false :head-id head-id}

       (some #(= epoch-id (:epoch-id %)) history)
       {:epochs (vec (rest (drop-while #(not= epoch-id (:epoch-id %)) history)))
        :id-aged-out? false
        :head-id head-id}

       :else
       {:epochs [] :id-aged-out? true :head-id head-id :requested-id epoch-id}))))

(defn epochs-in-last-ms
  "Records whose `:committed-at` falls inside the last N ms in the
   operating frame's history."
  ([ms] (epochs-in-last-ms ms (current-frame)))
  ([ms frame-id]
   (let [cutoff (- (js/Date.now) ms)]
     (->> (rf/epoch-history frame-id)
          (filterv #(>= (or (:committed-at %) 0) cutoff))))))

(defn find-where
  "Walk the operating frame's epoch-history in reverse chronological
   order and return the first record matching the predicate, or nil.

   Primary forensic op — 'find the epoch where X happened'. Examples:

     ;; find the epoch where :auth-state flipped to :expired
     (find-where
       (fn [e] (= :expired (get-in (:db-after e) [:auth-state]))))

     ;; find the epoch that triggered a specific event id
     (find-where
       (fn [e] (= :user/sign-out (:event-id e))))

   Most recent match wins — usually what you want for 'how did I get
   into this state?' post-mortems."
  ([pred] (find-where pred (current-frame)))
  ([pred frame-id]
   (->> (rf/epoch-history frame-id)
        reverse
        (filter pred)
        first)))

(defn find-all-where
  "Like find-where but returns every matching epoch, newest first.
   Use when you want the trajectory of a path — 'every epoch where
   :cart changed' — not just the most recent transition."
  ([pred] (find-all-where pred (current-frame)))
  ([pred frame-id]
   (->> (rf/epoch-history frame-id)
        reverse
        (filterv pred))))

(defn epoch-diff
  "Pre-computed diff between an epoch's `:db-before` and `:db-after`,
   shaped to the vocabulary the skill uses:
       {:only-before <map> :only-after <map> :common <map>}"
  [{:keys [db-before db-after]}]
  (let [[ob oa c] (data/diff db-before db-after)]
    {:only-before ob :only-after oa :common c}))

(defn frame-diff
  "Cross-frame counterpart to `epoch-diff`: diff the current `app-db`
   of two frames. Use to compare two Story variants (variant-id IS the
   frame-id) or any two live frames. Returns the cross-frame vocabulary:
       {:only-in-a <map> :only-in-b <map> :common <map>}
   where A is `frame-id-a` and B is `frame-id-b`."
  [frame-id-a frame-id-b]
  (let [[a b c] (data/diff (rf/app-db-value frame-id-a)
                           (rf/app-db-value frame-id-b))]
    {:only-in-a a :only-in-b b :common c}))

;; ---------------------------------------------------------------------------
;; Trace stream listener (raw-trace, retain-N buffer is in framework)
;; ---------------------------------------------------------------------------
;;
;; The framework already maintains a retain-N ring buffer accessible via
;; `(re-frame.trace.tooling/trace-buffer frame-id)` (the `rf/` alias is
;; JVM-only; frame-id is the first positional arg). We register one listener here for callers
;; that want a programmatic side-channel (e.g. a watch loop's idle
;; detector); the buffer remains the canonical query surface.

(defonce ^:private last-trace-id (atom 0))

;; The `last-trace-id` cursor and the streaming dispatch both ride the
;; same `register-listener!` slot — combined into `on-trace-streaming`
;; below.

(declare on-trace-streaming)

(defn- ensure-trace-listener!
  "Register the raw-trace listener if it isn't already.

   Installs the streaming-aware listener. The streaming
   dispatch is a no-op when no subscriptions are active, so this is
   safe to install unconditionally — `last-trace-event-id` keeps
   working through it."
  []
  (trace-tooling/register-listener! :re-frame2-pair on-trace-streaming))

(defn last-trace-event-id
  "Last trace event id observed by the skill's listener. Useful as a
   `:since` cursor for `(re-frame.trace.tooling/trace-buffer frame-id {:flat true :since N})`
   (`:since` is a `:flat-only` filter; frame-id is the first positional arg)."
  []
  @last-trace-id)

;; ---------------------------------------------------------------------------
;; Streaming subscriptions
;; ---------------------------------------------------------------------------
;;
;; A subscription is a server-side filtered tap on the trace bus or the
;; epoch bus. The MCP server registers a subscription via `subscribe!`,
;; then polls `drain-subscription!` in a tight loop to retrieve queued
;; events between polls; each batch is pushed back to the MCP client as
;; a `notifications/progress` notification.
;;
;; Why poll-from-server rather than push-from-runtime? The runtime lives
;; in the browser tab; the only side-channel back to the MCP server is
;; the nREPL socket (controlled by the server). Polling at ~100ms is
;; well below the perceptual threshold for the agent loop, costs one
;; bencode round-trip per tick, and stays correct across page reloads
;; (a reload wipes the runtime's subscription registry along with
;; everything else; the server's poll loop sees an empty drain + the
;; sub-id absent from `subscription-info`, and exits cleanly).
;;
;; Per-subscription state:
;;   {:id <uuid>
;;    :topic    :trace | :epoch | :fx | :error
;;    :filter   <filter-map>         ;; vocab depends on topic
;;    :queue    <vector of events>   ;; appended-to by the cb, drained by the server
;;    :queue-bytes <integer>         ;; running sum of (count (pr-str ev)) for queued events
;;    :dropped-events <integer>      ;; events evicted because EITHER budget tripped
;;    :dropped-bytes  <integer>      ;; bytes evicted alongside :dropped-events
;;    :overflow-reason :max-buffered-events | :max-buffered-bytes | nil
;;                                   ;; budget that tripped LAST — surfaced verbatim
;;                                   ;; so the AI client knows WHICH limit it should
;;                                   ;; tune. Reset at every drain alongside the
;;                                   ;; counters.
;;    :created-at <ms>
;;    :max-buffered-events <integer> ;; queue cap in events; default 500
;;    :max-buffered-bytes  <integer>} ;; queue cap in bytes; default 5_000_000 (~5 MB)
;;
;; Overflow policy (drop-oldest, byte+event budget):
;;   On enqueue, we first append the new event, then evict from the
;;   FRONT until BOTH budgets hold. Event-count overflow trips
;;   `:overflow-reason :max-buffered-events`; byte overflow trips
;;   `:overflow-reason :max-buffered-bytes`. When both trip on the
;;   same enqueue, the more-recently-tripped budget wins (typically
;;   bytes — a single fat event can put us over bytes while still
;;   inside events). Drop-oldest is the only sensible policy for a
;;   byte budget: a single fat newcomer can require evicting many
;;   small predecessors to fit, and there's no way to know that on
;;   entry without already having admitted it.
;;
;; Topic semantics:
;;   :trace     — every event in the raw trace stream matching `:filter`
;;                (filter map mirrors `(re-frame.trace.tooling/trace-buffer)` filter vocab —
;;                see the trace-buffer surface). Cascade-bundle delivery:
;;                per drain, matched events are grouped by
;;                `:rf.trace/dispatch-id` and projected into one cascade
;;                bundle per cascade (`group-cascades` shape with a
;;                `:trace-events` slot carrying the raw events). Events
;;                without a `:rf.trace/dispatch-id` tag (frameless
;;                registry / lifecycle emits) NEVER ride this topic —
;;                consumers wanting those subscribe to `:frameless`.
;;   :epoch     — every assembled `:rf/epoch-record` matching `:filter`
;;                (filter map mirrors `epoch-matches?` — see watch-epochs).
;;                One event per committed epoch. `:rf/epoch-record` is
;;                already a cascade-bundle by construction.
;;   :fx        — sugar for `:topic :trace :filter {:op-type :rf.fx}` with
;;                optional `:fx-id` and `:event-id` axes from the trace
;;                filter vocabulary. Cascade-bundle delivery as for :trace.
;;   :error     — sugar for `:topic :trace :filter {:op-type :error}`,
;;                with `:event-id`/`:handler-id`/`:source` available.
;;                Cascade-bundle delivery as for :trace.
;;   :frameless — every trace event matching `:filter` whose
;;                `:rf.trace/dispatch-id` tag is absent. Registration
;;                emits, REPL evals, lifecycle outside any cascade flow
;;                here per Spec 002 / Tool-Pair §Frameless trace events.
;;                Single-event delivery (no cascade to bundle).
;;
;; The :fx and :error topics compose with axes from the trace filter
;; vocabulary verbatim — they just default `:op-type` to `:rf.fx` /
;; `:error` and let callers override the rest.
;;
;; Cascade-bundle wire format — emitted on `:trace`/`:fx`/
;; `:error` drain ticks:
;;
;;   {:dispatch-id        <id>                  ;; cascade id
;;    :frame              <frame-id or nil>
;;    :event              <event-vector or nil> ;; from :rf.event/dispatched :tags
;;    :dispatched         <trace-event or nil>  ;; the full :rf.event/dispatched event
;;    :handler            <trace-event or nil>  ;; the :rf.event/run-end emit
;;    :fx                 <trace-event or nil>  ;; :rf.fx/do-fx
;;    :effects            [<trace-event> ...]   ;; :op-type :rf.fx (other ops)
;;    :subs               [<trace-event> ...]   ;; :rf.sub/run + :rf.sub/skip + :rf.sub/create
;;    :renders            [<trace-event> ...]   ;; :rf.view/render
;;    :other              [<trace-event> ...]   ;; everything else
;;    :trace-events       [<trace-event> ...]   ;; raw events for the cascade
;;    :parent-dispatch-id <id or nil>}          ;; causal-parent link
;;
;; Mirrors the framework's `(rf/trace-buffer frame-id)` shape so an
;; agent that already pattern-matches on the in-process per-frame
;; trace-ring sees the same shape on the wire (per Spec 009 §Cascade
;; projection + Tool-Pair §Reading the per-frame trace ring).
;;
;; Cross-frame cascade reconstruction — a cascade can fan
;; out across frames; every emit on every frame shares the same
;; `:rf.trace/dispatch-id`. Consumers merge by `:dispatch-id` across
;; per-frame bundles to reconstruct the cross-frame view (per Tool-Pair
;; §Cross-frame cascade reconstruction). The runtime emits one bundle
;; per (frame, dispatch-id) pair per drain — the merge is the
;; consumer's job, cheap because the key is already on each bundle.

(defonce ^:private subscriptions
  ;; sub-id -> subscription map (see above)
  (atom {}))

(def ^:private default-max-buffered-events 500)
(def ^:private default-max-buffered-bytes
  ;; ~5 MB — sized to match the 5,000-token wire-cap posture of the
  ;; MCP egress boundary scaled up by the per-tick batching ratio.
  ;; The streaming progress payload is metered per-tick, but the
  ;; underlying runtime queue is the upstream bound: it must be big
  ;; enough that a normal drain cadence (~100 ms server poll) drains
  ;; bursts before they back up, while small enough that an idle
  ;; subscription forgotten for hours doesn't accumulate hundreds of
  ;; megabytes of stale events.
  5000000)

;; ---------------------------------------------------------------------------
;; Privacy posture for the streaming surface
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Privacy / sensitive data: framework-published listener
;; integrations — including the re-frame2-pair server — MUST default-suppress
;; `:sensitive? true` trace events before forwarding to the AI surface.
;;
;; The trust boundary is "any trace data that leaves the browser tab and
;; reaches the LLM-facing channel". Streaming subscriptions are how that
;; happens in re-frame2-pair: the MCP server registers a subscription, polls
;; `drain-subscription!`, and forwards every drained event back to the
;; agent as a `notifications/progress` payload. The retain-N ring buffer
;; reached via `(re-frame.trace.tooling/trace-buffer)` is a separate, explicit read surface;
;; agents asking for it are making a deliberate request and the filter
;; vocabulary already exposes `:sensitive? false` for tools that want to
;; pre-filter (see Spec 009 §Filter vocabulary).
;;
;; The default is **drop**. Apps that need sensitive cascades visible to
;; the pair tool (rare; only when the tool is itself the trust boundary)
;; opt in explicitly via `configure-privacy!`.
;;
;; The flag is consulted at `on-trace-streaming` entry — before any
;; subscription's queue sees the event. Dropped events still update the
;; `last-trace-id` cursor (so `last-trace-event-id` keeps incrementing
;; monotonically) and still ride `(re-frame.trace.tooling/trace-buffer)` unchanged — only
;; the streaming dispatch is gated.

(defonce ^:private privacy-config
  ;; {:include-sensitive? bool}
  ;; Default: false — suppress `:sensitive? true` events from the
  ;; streaming dispatch path. See namespace docs above.
  (atom {:include-sensitive? false}))

(defn configure-privacy!
  "Set the privacy posture for the streaming surface. Opts:

     :include-sensitive?  boolean — when true, `:sensitive? true` trace
                          events ride the streaming dispatch unchanged.
                          Default: **false** (drop) per Spec 009 §Privacy.

   Returns the merged config map. Idempotent. Use sparingly — the
   default exists because re-frame2-pair forwards events to an LLM-facing
   channel, and the framework's privacy contract is that sensitive
   data does not cross that boundary by accident."
  [{:keys [include-sensitive?] :as opts}]
  (swap! privacy-config merge (select-keys opts [:include-sensitive?]))
  (assoc @privacy-config :ok? true))

(defn- streaming-drop?
  "True when the streaming surface should drop `ev` for privacy reasons.
   Today: any trace event stamped `:sensitive? true` at the top level
   unless the operator has opted in via `configure-privacy!`."
  [ev]
  (and (true? (:sensitive? ev))
       (not (true? (:include-sensitive? @privacy-config)))))

(defn- topic->base-filter
  "Map a topic keyword to its base trace-filter constraints. `:fx` and
   `:error` are sugar over `:op-type`; `:trace`, `:epoch`, `:frameless`
   add no base constraint here (the user-supplied filter is the only
   constraint). `:frameless` is gated additionally by
   `frameless-event?` at dispatch time — the base filter doesn't model
   the dispatch-id-absence test."
  [topic]
  (case topic
    :fx    {:op-type :rf.fx}
    :error {:op-type :error}
    {}))

;; ---------------------------------------------------------------------------
;; Cascade-bundle projection
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Cascade projection and Tool-Pair §Reading the per-frame
;; trace ring, the wire-delivery unit for the streaming subscribe is
;; the cascade bundle (one entry per `:rf.trace/dispatch-id`) rather
;; than the flat per-event slice. We mirror the framework's per-frame
;; trace-ring shape: `rf/group-cascades` projection PLUS a
;; `:trace-events` slot carrying the raw events.
;;
;; The bundling happens at drain time (not enqueue), so the per-event
;; queue's byte+event budget still works as the upstream bound — an
;; oversized cascade still evicts oldest events per the documented
;; policy. Each bundle's `:trace-events` slot carries ONLY the events
;; that survived eviction.
;;
;; Frameless events (`:rf.trace/dispatch-id` tag absent) NEVER ride
;; cascade-bundle topics — `dispatch-trace-to-subs!` filters them out
;; for those topics. The `:frameless` topic exists to deliver them
;; explicitly (Tool-Pair §Frameless trace events — live channel only).

(defn- frameless-event?
  "True when `ev` carries no `:rf.trace/dispatch-id` tag — a registration
   emit, REPL eval, or lifecycle event that never rode a dispatch
   cascade. The cascade-bundle topics filter these out; the `:frameless`
   topic accepts only these."
  [ev]
  (nil? (get-in ev [:tags :rf.trace/dispatch-id])))

(defn- cascade-bundle-events
  "Group a vector of raw trace events into cascade-bundle maps matching
   the framework's `(rf/trace-buffer frame-id)` shape — the
   `group-cascades` projection PLUS a `:trace-events` slot carrying the
   raw events for the cascade. The returned vector is sorted by emission
   order (lowest `:id` first, the order the projection returns).

   Delegates the grouping entirely to `rf/group-cascades-with-events` —
   the framework projection that keys cascades by `[frame dispatch-id]`.
   This is load-bearing: dispatch ids are unique only WITHIN a frame
   (the portable trace contract), so two cascades sharing a dispatch id
   across frames are distinct bundles, each carrying only its own frame's
   raw `:trace-events`. Grouping by `:rf.trace/dispatch-id` alone would
   merge foreign-frame events into both bundles the moment per-frame id
   allocation lets two frames collide on a dispatch id — so reuse the
   framework key, never re-derive a weaker one.

   Events whose `:rf.trace/dispatch-id` tag is missing are NOT included
   — the caller is expected to have filtered them upstream (cascade-
   bundle topics) or routed them to the frameless channel."
  [events]
  (->> (rf/group-cascades-with-events events)
       (remove #(= :ungrouped (:dispatch-id %)))
       vec))

(defn- compose-trace-filter
  "Compose the topic's base trace-filter with the user-supplied filter.
   User keys win on conflict — the topic is a default, not a lock."
  [topic user-filter]
  (merge (topic->base-filter topic) (or user-filter {})))

(declare epoch-matches?) ;; resolved below

(defn- trace-matches?
  "Test a raw trace event against a filter map. Mirrors the filter
   vocabulary of `(re-frame.trace.tooling/trace-buffer frame-id opts)` (the
   `:flat-only` keys :operation / :op-type / :since / :severity included) —
   composes AND-wise, absent key means no constraint on that axis."
  [filter-map ev]
  (let [{:keys [operation op-type frame severity
                event-id handler-id source origin
                dispatch-id since-ms between]}
        filter-map
        [t0 t1] (when (and (sequential? between) (= 2 (count between)))
                  between)]
    (boolean
      (and (or (nil? operation)  (= operation (:operation ev)))
           (or (nil? op-type)    (= op-type   (:op-type ev)))
           (or (nil? severity)   (= severity  (:op-type ev)))
           (or (nil? frame)      (= frame (trace/trace-event-frame ev)))
           (or (nil? event-id)   (= event-id
                                    (get-in ev [:tags :rf.trace/event-id])))
           (or (nil? handler-id) (= handler-id
                                    (get-in ev [:tags :handler-id])))
           (or (nil? source)     (= source
                                    (or (:source ev)
                                        (get-in ev [:tags :source]))))
           (or (nil? origin)     (= origin
                                    (get-in ev [:tags :rf.event/origin])))
           (or (nil? dispatch-id)(= dispatch-id
                                    (get-in ev [:tags :rf.trace/dispatch-id])))
           (or (nil? since-ms)   (and (number? (:time ev))
                                      (> (:time ev) since-ms)))
           (or (nil? t0)         (and (number? (:time ev))
                                      (<= t0 (:time ev) t1)))))))

(defn- event-byte-size
  "Cheap, monotonic estimate of an event's on-wire byte cost. Uses the
   same `pr-str`-char-count discipline as the wire-cap helper in
   `tools.cljs` (`token-estimate`) — keeps the runtime queue's budget
   in the same units as the egress cap, so the two budgets stay
   coherent. `pr-str` failure (a reader-unfriendly value somehow on
   the bus) falls back to `0` rather than blowing up enqueue."
  [event]
  (try (count (pr-str event))
       (catch :default _ 0)))

(defn- evict-oldest
  "Drop events from the FRONT of `sub`'s queue until BOTH budgets
   hold (or the queue is empty). Returns the updated sub with the
   queue/byte-running-total trimmed and `:dropped-events` /
   `:dropped-bytes` / `:overflow-reason` updated. Drop-oldest is the
   only sensible policy for a byte budget — see the namespace docs
   above."
  [sub max-events max-bytes]
  (loop [q       (:queue sub)
         bytes   (:queue-bytes sub 0)
         dropped-n 0
         dropped-b 0
         reason    nil]
    (let [n (count q)
          over-events? (> n max-events)
          over-bytes?  (> bytes max-bytes)]
      (if (and (or over-events? over-bytes?)
               (pos? n))
        (let [head     (nth q 0)
              head-bs  (event-byte-size head)]
          (recur (subvec q 1)
                 (max 0 (- bytes head-bs))
                 (inc dropped-n)
                 (+ dropped-b head-bs)
                 ;; bytes wins ties — if both budgets trip on the
                 ;; same enqueue, the byte budget is the one the
                 ;; agent likely needs to know about. (Event-count
                 ;; alone tripping is the easy case; bytes tripping
                 ;; signals a large-payload storm.)
                 (cond over-bytes?  :max-buffered-bytes
                       over-events? :max-buffered-events
                       :else        reason)))
        (cond-> (assoc sub :queue q :queue-bytes bytes)
          (pos? dropped-n)
          (-> (update :dropped-events (fnil + 0) dropped-n)
              (update :dropped-bytes  (fnil + 0) dropped-b)
              (assoc :overflow-reason reason)))))))

(defn- enqueue!
  "Append an event to a subscription's queue, honouring the byte+event
   buffer budget. Drop-oldest semantics: we always admit
   the new event first, then evict from the FRONT until both budgets
   hold. A single fat newcomer can therefore evict an arbitrary
   number of small predecessors — that's correct: the byte budget is
   a hard upstream bound, and the agent draining the sub gets the
   most recent state of the world."
  [sub-state sub-id event]
  (update sub-state sub-id
          (fn [sub]
            (when sub
              (let [max-events (:max-buffered-events sub default-max-buffered-events)
                    max-bytes  (:max-buffered-bytes  sub default-max-buffered-bytes)
                    ev-bytes   (event-byte-size event)
                    sub'       (-> sub
                                   (update :queue       conj event)
                                   (update :queue-bytes (fnil + 0) ev-bytes))]
                (evict-oldest sub' max-events max-bytes))))))

(defn- dispatch-trace-to-subs!
  "Called from the raw-trace listener — iterates active subscriptions of
   trace-like topics, matches, enqueues. Cheap when no subs exist (the
   common path).

   Channel split:
   - Cascade-bundle topics (`:trace`/`:fx`/`:error`) receive events
     carrying a `:rf.trace/dispatch-id` tag. The bundling happens at
     drain time; the queue stays per-event for the byte+event budget.
   - The `:frameless` topic receives only events whose
     `:rf.trace/dispatch-id` tag is absent (registration / REPL /
     lifecycle emits outside any cascade)."
  [ev]
  (let [frameless? (frameless-event? ev)]
    (swap! subscriptions
           (fn [m]
             (reduce-kv
               (fn [acc sub-id sub]
                 (let [topic (:topic sub)
                       routes? (cond
                                 (contains? #{:trace :fx :error} topic)
                                 ;; Cascade-bundle topics — never deliver
                                 ;; frameless events; consumers wanting
                                 ;; those opt into the `:frameless` topic.
                                 (and (not frameless?)
                                      (trace-matches? (:compiled-filter sub) ev))

                                 (= :frameless topic)
                                 ;; The frameless channel — only frameless
                                 ;; events, then filter-matched.
                                 (and frameless?
                                      (trace-matches? (:compiled-filter sub) ev))

                                 :else
                                 false)]
                   (if routes?
                     (enqueue! acc sub-id ev)
                     acc)))
               m m)))))

(defn- dispatch-epoch-to-subs!
  "Called from the assembled-epoch listener — iterates active epoch
   subscriptions, matches, enqueues."
  [record]
  (swap! subscriptions
         (fn [m]
           (reduce-kv
             (fn [acc sub-id sub]
               (if (and (= :epoch (:topic sub))
                        (epoch-matches? (or (:filter sub) {}) record))
                 (enqueue! acc sub-id record)
                 acc))
             m m))))

(defn- on-trace-streaming
  "Raw-trace listener that drives both the last-trace-id cursor and the
   streaming subs dispatch.

   Privacy filter: trace events stamped `:sensitive? true`
   at the top level are dropped from the streaming dispatch by default,
   per Spec 009 §Privacy. The `last-trace-id` cursor still advances so
   the `since`-based ring-buffer reads remain monotonic — only
   the LLM-facing streaming surface is gated. Opt in via
   `(configure-privacy! {:include-sensitive? true})`."
  [ev]
  (when-let [id (:id ev)]
    (when (number? id) (reset! last-trace-id id)))
  (when-not (streaming-drop? ev)
    (dispatch-trace-to-subs! ev)))

(defn- on-epoch-streaming
  "Assembled-epoch listener. Drives ONLY derived state — the per-frame
   app-db-hash cache (a scalar, for the precheck cache-hit decision) —
   and the streaming-subs fan-out. It does NOT retain a copy of the
   epoch ring: every epoch read hits `(rf/epoch-history frame-id)`
   directly, so there is no session-side buffer to drift."
  [record]
  (when-let [frame-id (:frame record)]
    (update-frame-db-hash! frame-id (:db-after record)))
  (dispatch-epoch-to-subs! record))

(defn subscribe!
  "Open a streaming subscription on the trace or epoch bus. Returns
   `{:ok? true :sub-id <uuid>}`. Subsequent calls to
   `drain-subscription!` return queued events matching `:filter`.

   Opts:
     :topic   :trace | :epoch | :fx | :error | :frameless  (required)
     :filter  filter map — vocab depends on topic. See namespace docs.
     :max-buffered-events  cap on the in-runtime queue in events.
                           Default 500. When either budget trips, the
                           OLDEST events are evicted (drop-oldest FIFO).
     :max-buffered-bytes   cap on the in-runtime queue in pr-str bytes.
                           Default 5_000_000 (~5 MB). Same drop-oldest
                           policy. The two budgets are OR-combined:
                           whichever trips first evicts.

   Drop counts surface on `drain-subscription!` as `:dropped-events`
   and `:dropped-bytes`, with `:overflow-reason` carrying the budget
   keyword (`:max-buffered-events` or `:max-buffered-bytes`) that
   tripped LAST. The bookkeeping reset happens on drain — each tick
   reports the deltas since the previous tick.

   Idempotency: each call returns a fresh sub-id — repeated `subscribe!`
   calls do not share state. Use `unsubscribe!` to release.

   Topic delivery shape:
     :trace / :fx / :error — drain returns `:cascades [<bundle> ...]`
                             with each bundle in the `(rf/trace-buffer
                             frame-id)` shape (per Spec 009 §Cascade
                             projection). One bundle per cascade per
                             drain.
     :epoch                — drain returns `:events [<:rf/epoch-record>
                             ...]` unchanged.
     :frameless            — drain returns `:events [<trace-event>
                             ...]` for events with no
                             `:rf.trace/dispatch-id` tag (registration
                             emits, REPL evals, lifecycle outside any
                             cascade)."
  [{:keys [topic filter max-buffered-events max-buffered-bytes] :as opts}]
  (cond
    (not (contains? #{:trace :epoch :fx :error :frameless} topic))
    {:ok? false :reason :unknown-topic
     :hint "Recognised topics: :trace :epoch :fx :error :frameless"
     :given topic}

    :else
    (let [sub-id (str (random-uuid))
          compiled (when (#{:trace :fx :error :frameless} topic)
                     (compose-trace-filter topic filter))
          sub {:id              sub-id
               :topic           topic
               :filter          (or filter {})
               :compiled-filter compiled
               :queue           []
               :queue-bytes     0
               :dropped-events  0
               :dropped-bytes   0
               :overflow-reason nil
               :created-at      (js/Date.now)
               :max-buffered-events (or max-buffered-events
                                        default-max-buffered-events)
               :max-buffered-bytes  (or max-buffered-bytes
                                        default-max-buffered-bytes)}]
      ;; Make sure the upgraded listeners are wired (idempotent — same
      ;; id, replaces the basic listeners installed by `health`).
      (trace-tooling/register-listener! :re-frame2-pair on-trace-streaming)
      (rf/register-epoch-listener! :re-frame2-pair-epoch on-epoch-streaming)
      (swap! subscriptions assoc sub-id sub)
      {:ok? true :sub-id sub-id :topic topic :filter (:filter sub)})))

(defn unsubscribe!
  "Drop subscription `sub-id`. Returns `{:ok? true :sub-id ...}` even
   if the id was unknown — callers (the MCP server's poll loop) want
   idempotent close."
  [sub-id]
  (let [existed? (contains? @subscriptions sub-id)]
    (swap! subscriptions dissoc sub-id)
    {:ok? true :sub-id sub-id :existed? existed?}))

(defn drain-subscription!
  "Pop every queued event for `sub-id` and return them in order.
   Returns one of two envelopes per the sub's topic:

   - Cascade-bundle topics (`:trace`/`:fx`/`:error`):
     `{:ok? true :sub-id ... :cascades [<bundle> ...] :dropped-events <n>
       :dropped-bytes <m> :overflow-reason <kw|nil> :gone? bool}`
     — queued raw events are grouped by `:rf.trace/dispatch-id` and
     projected into cascade bundles (`group-cascades` shape with a
     `:trace-events` slot) per Spec 009 §Cascade projection.

   - Flat topics (`:epoch`/`:frameless`):
     `{:ok? true :sub-id ... :events [...] :dropped-events <n>
       :dropped-bytes <m> :overflow-reason <kw|nil> :gone? bool}`
     — `:epoch` ships `:rf/epoch-record`s; `:frameless` ships raw
     trace events with no `:rf.trace/dispatch-id` tag.

   If the subscription doesn't exist (already unsubscribed or runtime
   was reloaded), `:gone? true` (envelope shape: `:events []`).

   The `:dropped-events`, `:dropped-bytes`, and `:overflow-reason`
   counters report what got EVICTED from the QUEUE between drains —
   they reset on every drain so the next tick reports the delta. AI
   clients pattern-match on `:overflow-reason` to know which budget
   tripped (`:max-buffered-events` or `:max-buffered-bytes`); the
   `:dropped-bytes` figure tells them how much state they missed.

   Note on cascade-bundle counters: `:dropped-events` counts the raw
   trace events evicted from the queue, NOT cascades. An evicted event
   may have left its sibling cascade-members in place — consumers
   reconstructing a cascade should be tolerant of partially-truncated
   bundles when `:dropped-events` is non-zero."
  [sub-id]
  ;; Atomically reset the drained sub's queue + counters and capture the
  ;; PRE-drain state in one shot via `swap-vals!` (returns [old new]) — no
  ;; scratch atom threaded out of the swap closure. The reset is a pure
  ;; `update`-of-the-sub; the drained snapshot is just the old map's entry.
  (let [[old _] (swap-vals! subscriptions
                            (fn [m]
                              (if (contains? m sub-id)
                                (update m sub-id #(-> %
                                                      (assoc :queue [])
                                                      (assoc :queue-bytes 0)
                                                      (assoc :dropped-events 0)
                                                      (assoc :dropped-bytes 0)
                                                      (assoc :overflow-reason nil)))
                                m)))]
    (if-let [{:keys [queue topic dropped-events dropped-bytes overflow-reason]}
             (get old sub-id)]
      (let [base {:ok?             true
                  :sub-id          sub-id
                  :dropped-events  (or dropped-events 0)
                  :dropped-bytes   (or dropped-bytes  0)
                  :overflow-reason overflow-reason
                  :gone?           false}]
        (cond
          ;; Cascade-bundle delivery — group raw queued
          ;; trace events by `:rf.trace/dispatch-id` into bundles. The
          ;; cascade-bundle topics never enqueue frameless events
          ;; (`dispatch-trace-to-subs!` filters them out at the gate),
          ;; so `cascade-bundle-events`'s `:ungrouped`-drop is purely
          ;; defensive.
          (contains? #{:trace :fx :error} topic)
          (assoc base :cascades (cascade-bundle-events queue))

          ;; Flat delivery — :epoch and :frameless.
          :else
          (assoc base :events queue)))
      {:ok? true :sub-id sub-id :events []
       :dropped-events 0
       :dropped-bytes  0
       :overflow-reason nil
       :gone? true})))

(defn subscription-info
  "Return active STREAMING-tap subscription metadata — the trace / epoch
   / fx / error queues opened via `subscribe!` and torn down by
   `unsubscribe!`. Handy for diagnostics: confirm a stream is still
   alive, inspect its queue depth / overflow-reason. Does not drain.

   NOT the reactive sub-cache — for \"what reactive subscriptions are
   currently materialised in a frame?\" use `sub-cache-info`, which
   reads the per-frame reactive cache `snapshot`'s `:sub-cache` slice
   reads. The MCP `list-streams` tool wraps THIS fn; the MCP
   `list-subscriptions` tool wraps `sub-cache-info`.

   Returns
   `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes
                       :dropped-events :dropped-bytes :overflow-reason
                       :created-at}]}`."
  []
  {:ok? true
   :subs (mapv (fn [[sub-id sub]]
                 {:id              sub-id
                  :topic           (:topic sub)
                  :filter          (:filter sub)
                  :queue-depth     (count (:queue sub))
                  :queue-bytes     (:queue-bytes sub 0)
                  :dropped-events  (:dropped-events sub 0)
                  :dropped-bytes   (:dropped-bytes  sub 0)
                  :overflow-reason (:overflow-reason sub)
                  :created-at      (:created-at sub)})
               @subscriptions)})

;; ---------------------------------------------------------------------------
;; Dispatch correlation (Spec 009 §Dispatch correlation)
;; ---------------------------------------------------------------------------

(defn cascade-of
  "Reconstruct the cascade tree from a root `:dispatch-id` by walking
   the per-frame trace-ring cascade bundles for matching parent links.
   Returns a tree of `{:dispatch-id <id> :event <ev> :children [...]}`.

   The trace ring is per-frame and cascade-keyed; the read unit is the
   cascade bundle (`group-cascades` shape).
   A single cascade can fan out across frames (per Spec 002
   §Cross-frame dispatch) — every emit on every frame shares the same
   `:rf.trace/dispatch-id`, so cross-frame reconstruction iterates
   every registered frame's bundles and merges by `:dispatch-id`.
   Per-frame depth is configurable via
   `(rf/configure! {:trace-buffer {:cascades-retained N}})`."
  [root-dispatch-id]
  (let [;; Per-frame rings, cascade-bundle reads — merge across
        ;; frames so cross-frame cascades reconstruct correctly. Each
        ;; bundle carries `:parent-dispatch-id` (the causal-parent
        ;; link) and `:dispatched :tags :rf.event/origin` directly,
        ;; so the tree walk reads them off the bundle slot rather
        ;; than re-deriving from raw trace events.
        bundles    (into []
                         (mapcat #(trace-tooling/trace-buffer %))
                         (rf/frame-ids))
        by-parent  (group-by :parent-dispatch-id bundles)
        node       (fn node [did]
                     (let [b (some (fn [bundle] (when (= did (:dispatch-id bundle)) bundle))
                                   bundles)]
                       {:dispatch-id did
                        :event       (:event b)
                        :origin      (get-in b [:dispatched :tags :rf.event/origin])
                        :children    (mapv #(node (:dispatch-id %))
                                           (get by-parent did []))}))]
    (node root-dispatch-id)))

(declare epoch-elapsed-ms)

;; ---------------------------------------------------------------------------
;; Cascade summary
;; ---------------------------------------------------------------------------
;;
;; A compact projection of the framework's `:rf/epoch-record` that answers
;; the universal "what did my dispatch do?" question in one call. Surfaced
;; by `pair-dispatch!` / `pair-dispatch-sync!` / `dispatch-and-collect` /
;; `app-db-reset!` / `restore-epoch` / `dispatch-dry-run` whenever a new
;; epoch settled as a consequence of the call.
;;
;; The shape MIRRORS the assembled-epoch projection that
;; `register-epoch-listener!` consumers already know (Spec 009 §Epoch
;; records) — operators familiar with `watch-epochs` see the same
;; vocabulary in dispatch responses. The mirror is intentionally LOSSY
;; (counts instead of full vectors, db-diff path-only summary instead of
;; the raw `:db-before`/`:db-after` pair) so the cascade-summary rides
;; under the 5K-token wire-cap without further elision; an operator who
;; wants the full epoch reads `(rf/epoch-history)` or runs `trace-window`
;; / `watch-epochs` for the same id.
;;
;; Slot inventory (every slot is optional — absent on epochs that lack
;; that signal; e.g. synthetic `:rf.epoch/db-replaced` epochs have no
;; `:event-id`):
;;
;;   :epoch-id            — the assembled epoch's id (`:any` per Spec-
;;                          Schemas; integer in the reference runtime)
;;   :event-id            — the triggering event-id keyword (absent on
;;                          synthetic / halted-trigger-less paths)
;;   :event-vector        — the original dispatch vector (absent on the
;;                          same paths as :event-id)
;;   :frame               — the frame-id the cascade settled in
;;   :outcome             — the consumer-facing tier (`:ok` / `:blocked`
;;                          / `:error`, per `outcome->consumer-facing`).
;;                          Forced `:error` when the cascade
;;                          contained a thrown handler / machine action
;;                          (a `:rf.error/*` op in `:trace-events`), even
;;                          though the epoch itself settled `:outcome :ok`
;;                          (the interceptor seam contained the throw).
;;   :errors              — vector of compact `{:operation :message?
;;                          :machine-id? :action-id?}` descriptors, one per
;;                          contained cascade exception. Absent
;;                          when the cascade threw nothing. Present ⇒
;;                          `:outcome :error` and the downstream
;;                          `dispatch-consequence!` reports `:no-op? false`.
;;   :db-diff             — {:changed-paths [...] :added-paths [...]
;;                           :removed-paths [...]} — top-level depth-1
;;                          summary computed from `:db-before` /
;;                          `:db-after`. NO raw values cross the wire
;;                          here; an operator inspects the addressed
;;                          subtree via `get-path` or `snapshot {:path
;;                          ...}` on demand.
;;   :fx-fired            — vector of distinct fx-ids that fired
;;                          (`(:fx-id %)` over the epoch's `:effects`
;;                          projection); duplicates collapsed
;;   :subs-recomputed     — count of unique sub-runs in this cascade
;;   :renders             — count of render emits in this cascade
;;   :machine-transitions — vector of `{:machine-id :from :to :phase}`
;;                          projections (absent on cascades without
;;                          machine activity)
;;   :elapsed-ms          — wall-clock elapsed-ms (`epoch-elapsed-ms`)
;;   :sensitive?          — the record's `:rf.epoch/sensitive?` rollup;
;;                          when true, consumers branch on the absent-
;;                          slot pattern in `:db-diff` (sensitive paths
;;                          are dropped from the projection by the
;;                          framework's redact-fn before we read it)
;;
;; Production builds elide the entire epoch-record path under
;; `interop/debug-enabled? false`; cascade-summary inherits that —
;; the projection is dev-only by construction. The :elision /
;; :sensitive-paths machinery upstream of us already ran by the time
;; we read :db-before / :db-after, so we never see raw sensitive values.

(defn- db-diff-summary
  "Top-level (depth-1) path summary of the db-before -> db-after delta.
  Returns `{:changed-paths [...] :added-paths [...] :removed-paths [...]}`.
  Each path is a one-key vector (e.g. `[:cart]`) — operators drill in
  via `get-path` for the full subtree. Bounded by the depth-1 walk so
  cascade-summary stays under the wire cap regardless of db size."
  [db-before db-after]
  (cond
    (and (map? db-before) (map? db-after))
    (let [ks-b   (set (keys db-before))
          ks-a   (set (keys db-after))
          common (set/intersection ks-b ks-a)]
      {:added-paths   (vec (sort (map vector (set/difference ks-a ks-b))))
       :removed-paths (vec (sort (map vector (set/difference ks-b ks-a))))
       :changed-paths (vec (sort (for [k common
                                       :when (not= (get db-before k) (get db-after k))]
                                   [k])))})

    (= db-before db-after)
    {:added-paths [] :removed-paths [] :changed-paths []}

    :else
    {:added-paths [] :removed-paths [] :changed-paths [[]]}))

(defn- machine-transitions-summary
  "Project machine-transition trace events out of an epoch's
  `:trace-events`. Returns a vector of compact `{:machine-id :from :to
  :phase}` maps, or nil when no machine activity. Per Spec 005 the
  machine-step trace stream uses `:rf.machine/transition` ops with
  `:tags {:machine-id :from :to :phase}`."
  [trace-events]
  (let [picks (->> trace-events
                   (filter (fn [ev] (= :rf.machine/transition (:operation ev))))
                   (mapv (fn [ev]
                           (let [t (:tags ev)]
                             (cond-> {}
                               (:machine-id t) (assoc :machine-id (:machine-id t))
                               (:from t)       (assoc :from (:from t))
                               (:to t)         (assoc :to (:to t))
                               (:phase t)      (assoc :phase (:phase t)))))))]
    (when (seq picks) picks)))

(defn- outcome-tier
  "Project the epoch's detailed `:outcome` cause onto the consumer-
  facing three-tier summary (`:ok` / `:blocked` / `:error`). Mirrors
  `re-frame.epoch.assembly/outcome->consumer-facing` (the same projection
  pinned in the framework). When `:outcome` is absent, defaults to `:ok`."
  [outcome]
  (case outcome
    :ok                       :ok
    :halted-depth             :blocked
    :halted-destroy           :blocked
    :halted-handler-exception :error
    :ok))

;; ---- contained cascade errors --------------------------------------------
;;
;; A handler / machine-action throw does NOT halt the drain: the
;; interceptor error-capture seam contains it, the epoch settles
;; `:outcome :ok`, and the throw rides the trace stream under a
;; `:rf.error/*` op (Spec-Schemas §`:rf/epoch-record` §Outcomes; the
;; reference runtime never emits `:halted-handler-exception`). So
;; `outcome-tier` alone reads such an epoch as `:ok` — and because the
;; aborted action committed no `:db` and fired no fx, the `:no-op?`
;; heuristic (no db-change AND no fx) would also misfire. Left unhandled
;; that is a silent-green-on-error trap for any NON-visual consumer
;; triaging on `:outcome` / `:no-op?` (the human pink-card path is
;; unaffected — it reads the trace directly).
;;
;; The structured summary closes the gap by scanning `:trace-events` for
;; a contained cascade exception and, when one is present, projecting
;; `:outcome :error` + surfacing the errors under an `:errors` slot
;; (`:no-op?` exclusion lives downstream in `consequence-from-summary`).
;;
;; `cascade-error-ops` MIRRORS the Xray Epoch panel's `cascade-exception-
;; ops` (`tools/xray/.../panels/epoch/projection.cljc`) — the structured
;; summary and the human panel agree on exactly which `:rf.error/*` ops
;; count as a cascade-level throw. Schema-VALIDATION failures
;; (`:rf.error/schema-validation-failure`) are deliberately NOT here: a
;; rejected-but-rolled-back cascade is not a thrown action, and its
;; outcome is governed elsewhere.

(def ^:private cascade-error-ops
  "Closed set of cascade-level `:rf.error/*` trace ops that mark an epoch
  whose cascade contained a thrown handler / machine action.
  Mirrors Xray's `cascade-exception-ops` so the structured summary and the
  human Epoch panel agree on what counts as a throw."
  #{:rf.error/coeffect-exception
    :rf.error/interceptor-exception
    :rf.error/handler-exception
    :rf.error/fx-handler-exception
    :rf.error/no-such-fx
    :rf.error/flow-eval-exception
    :rf.error/machine-action-exception})

(defn- cascade-errors
  "Project the contained cascade-exception trace events out of an epoch's
  `:trace-events` into a vector of compact descriptors, or nil when the
  cascade carried no contained throw.

  Each descriptor carries `:operation` (the `:rf.error/*` op) plus, when
  the trace event stamped them, `:message` (the exception's `.getMessage`
  via `:exception-message`) and the machine attribution
  (`:machine-id` / `:action-id`) a machine-action throw rides. The shape
  is intentionally compact — an operator who wants the full exception
  (stack / ex-data) reads the epoch's `:trace-events` directly or opens
  the Xray Epoch panel."
  [trace-events]
  (let [picks (->> trace-events
                   (filter (fn [ev] (contains? cascade-error-ops (:operation ev))))
                   (mapv (fn [ev]
                           (let [t (:tags ev)]
                             (cond-> {:operation (:operation ev)}
                               (string? (:exception-message t))
                               (assoc :message (:exception-message t))
                               (:machine-id t) (assoc :machine-id (:machine-id t))
                               (:action-id t)  (assoc :action-id (:action-id t)))))))]
    (when (seq picks) picks)))

(defn- redact-sensitive-event-vector
  "Egress guard for the cascade-summary `:event-vector` slot — the
  fail-closed projection the framework's `projected-record` applies to a
  record's `:trigger-event` slot (rf2-nm611o,
  `epoch/tool_pair.cljc` §`elide-trigger-event-slot`), reproduced here
  because the cascade-summary rides OUTSIDE the wire-path projection.

  The `:event-vector` slot copies the epoch's RAW `:trigger-event` — the
  original dispatch vector, e.g. `[:auth/login {:password \"hunter2\"}]`
  or `[:login \"topsecret\"]`. The event ARGS are registration-owned
  transient payloads (Spec 015 §151 §Registration-owned transient
  classification) — the SAME class as the `:effects` `:args` slot — NOT
  rooted at the frame's app-db, so the app-db-path classification walker
  cannot prove ANY of them safe. A secret carried IN the event vector
  therefore rides off-box verbatim regardless of whether the epoch is
  declared `:rf.epoch/sensitive?`: the old guard keyed redaction to the
  `:rf.epoch/sensitive?` rollup ALONE, so a NON-declared trigger-event
  (`[:login \"topsecret\"]` with no declared-sensitive db slot) leaked
  the password off-box (rf2-6klf02). `cascade-summary` is the ONE place
  the trigger-event leaves the runtime, and the consuming MCP tools
  (`restore-epoch` passes the runtime map through verbatim;
  `dispatch-dry-run` deliberately does NOT walk `:cascade-summary`,
  treating it as a counts-only projection) trust this projection to be
  already-safe.

  Fail-closed (gate OFF — the published-build default; the MCP server
  flips `raw-state-config` to OFF the moment a state-emitting tool first
  fires unless the operator launched with `--allow-sensitive-reads`):
  the head `<event-id>` keyword (a non-payload summary — the SAME value
  the record carries in its `:event-id` slot) is RETAINED while every
  positional / map arg is replaced with the `:rf/redacted` sentinel, so
  `[:login \"topsecret\"]` egresses as `[:login :rf/redacted]` and
  `[:auth/login {:password p}]` as `[:auth/login :rf/redacted]`. A
  consumer still sees WHICH event ran, never its args. This matches
  `elide-trigger-event-slot` exactly — the fail-close fires on EVERY
  epoch (sensitive or not), because the args are unprovable regardless.
  A degenerate non-vector / empty slot (or a value a `:redact-fn` already
  scalarised) redacts wholesale to `:rf/redacted` — nothing safe to
  expose.

  Raw only on opt-in: when the gate is ON the operator deliberately
  asked for raw reads (the cascade-summary's equivalent of the
  `:include-event-args? true` trusted-local opt), so the verbatim
  trigger-event rides through.

  `sensitive?` is the epoch's `:rf.epoch/sensitive?` rollup — retained in
  the signature because callers thread it, but the args fail closed
  whether or not it is set (it governs only the cascade-summary's
  `:sensitive?` annotation slot, not this redaction). Idempotent: a
  second pass over an already-projected `[<id> :rf/redacted …]`
  re-redacts the (already-`:rf/redacted`) tail to the same sentinels.
  Nil-preserving."
  [trigger-event sensitive?]
  (cond
    ;; Gate ON ⇒ the operator opted into raw reads; ship verbatim.
    (:allow-raw-state? @raw-state-config) trigger-event
    (nil? trigger-event)                  trigger-event
    ;; The canonical shape: retain the head event-id, fail-close the args.
    (and (vector? trigger-event) (seq trigger-event))
    (into [(first trigger-event)]
          (repeat (dec (count trigger-event)) :rf/redacted))
    ;; Degenerate non-vector / empty slot — nothing safe to expose.
    :else :rf/redacted))

(defn cascade-summary
  "Project an assembled `:rf/epoch-record` into the compact wire shape
  surfaced by dispatch / replace-app-db / restore-epoch / dispatch-dry-
  run. See the §Cascade summary section header above for
  the slot inventory.

  Pure data — `(epoch-record) -> cascade-summary-map`. Returns nil for a
  nil record so callers can `(when summary ...)` without an explicit
  nil-check at each call site."
  [{:keys [epoch-id event-id trigger-event frame outcome
           db-before db-after effects sub-runs renders trace-events]
    :as record}]
  (when record
    (let [diff       (db-diff-summary db-before db-after)
          fx-fired   (->> effects (map :fx-id) distinct vec)
          transitions (machine-transitions-summary trace-events)
          elapsed    (epoch-elapsed-ms record)
          sensitive? (:rf.epoch/sensitive? record)
          ;; A contained throw (handler / machine action) rides the trace
          ;; stream while the epoch settles `:outcome :ok`. When present it
          ;; OVERRIDES the outcome tier to `:error` and surfaces under
          ;; `:errors` so a non-visual consumer detects the failure.
          errors     (cascade-errors trace-events)]
      (cond-> {:epoch-id        epoch-id
               :frame           frame
               :outcome         (if errors :error (outcome-tier outcome))
               :db-diff         diff
               :fx-fired        fx-fired
               :subs-recomputed (count (or sub-runs []))
               :renders         (count (or renders []))}
        event-id      (assoc :event-id event-id)
        ;; The trigger-event's ARGS fail closed off-box under the OFF
        ;; gate (head event-id retained, args → `:rf/redacted`) for EVERY
        ;; epoch — the event args are registration-owned transient
        ;; payloads the app-db classification walker cannot prove safe,
        ;; so a secret carried IN the vector redacts whether or not the
        ;; epoch is declared sensitive (rf2-nm611o / rf2-6klf02). Raw only
        ;; on the operator's `--allow-sensitive-reads` opt-in. See
        ;; `redact-sensitive-event-vector`.
        trigger-event (assoc :event-vector
                             (redact-sensitive-event-vector trigger-event sensitive?))
        transitions   (assoc :machine-transitions transitions)
        elapsed       (assoc :elapsed-ms elapsed)
        errors        (assoc :errors errors)
        sensitive?    (assoc :sensitive? true)))))

(defn- attach-cascade
  "Attach a `:cascade-summary` slot to `result` when `epoch-id` resolves
  to a record in the operating frame's history. No-op when the head did
  not advance (queued dispatch that hasn't drained yet, dispatch-sync
  whose cascade settled without a recorded epoch)."
  [result frame-id epoch-id]
  (if-let [record (epoch-by-id epoch-id frame-id)]
    (assoc result :cascade-summary (cascade-summary record))
    result))

;; ---------------------------------------------------------------------------
;; Pair-tagged dispatch
;; ---------------------------------------------------------------------------
;;
;; Per Spec 002 §Dispatch origin tagging, dispatches carry an :origin opt
;; (default :app). The skill stamps :pair so `:rf.event/dispatched` traces
;; can be filtered by who fired them. Pair-epoch tracking populates
;; `pair-epoch-ids` from the assembled-epoch listener.

(defn- mark-pair! [epoch-id]
  (when epoch-id (swap! pair-epoch-ids conj epoch-id)))

(defn pair-dispatch!
  "Queued dispatch with `:origin :pair`. Returns
   `{:ok? true :queued? true :event ...}`. The epoch-id appears once
   the cascade settles; callers can read it via `last-pair-epoch`.

   The response carries a `:cascade-summary` slot when
   the runtime drained the queue synchronously (the typical CLJS
   single-threaded case — `rf/dispatch` enqueues, the goog.async tick
   drains, and by the time our `(rf/epoch-history)` read fires the
   head has advanced). When the head did NOT advance (the cascade is
   still pending), the response omits `:cascade-summary` and ships
   `:cascade-summary-pending? true :before-epoch-id <prior-head>` so
   the caller can poll `watch-epochs` for the eventual settlement.

   `:queued? true` rides on the response, but the cascade slot is the
   canonical 'what happened?' surface."
  ([event-v] (pair-dispatch! event-v {}))
  ([event-v opts]
   (let [frame-id  (or (:frame opts) (current-frame))
         ;; The nREPL eval thread carries no ambient `with-frame` scope, so
         ;; `rf/dispatch` would `require-current-frame!` and throw
         ;; `:rf.error/no-frame-context`. Thread the resolved operating frame
         ;; in as the explicit `:frame` override (the carried-invariant escape
         ;; the router honours first) so the dispatch lands on the frame
         ;; `current-frame` chose.
         opts      (cond-> opts frame-id (assoc :frame frame-id))
         before-id (when frame-id (some-> (rf/epoch-history frame-id) peek :epoch-id))]
     (rf/dispatch event-v (merge {:origin :pair} opts))
     (let [after-id (when frame-id (some-> (rf/epoch-history frame-id) peek :epoch-id))
           base     {:ok? true :queued? true :event event-v :opts opts}]
       (cond
         (and after-id (not= before-id after-id))
         (do (mark-pair! after-id)
             (attach-cascade (assoc base :epoch-id after-id :frame frame-id)
                             frame-id after-id))

         :else
         (assoc base
                :frame frame-id
                :cascade-summary-pending? true
                :before-epoch-id before-id
                :hint (str "rf/dispatch enqueued; head did not advance synchronously. "
                           "Poll watch-epochs (since-id before-epoch-id) for the "
                           "eventual cascade.")))))))

(defn pair-dispatch-sync!
  "Synchronous dispatch with `:origin :pair`. Reads the operating
   frame's epoch-history before and after; the new head is reported
   as the pair-attributed epoch.

   On real success returns {:ok? true :epoch-id <id> :event ...
   :cascade-summary {...}} — the cascade summary rides
   under `:cascade-summary` (the compact projection defined above; see
   §Cascade summary). When epoch-history depth is 0 (recording
   disabled) or the frame isn't registered, reports the failure mode
   rather than claiming success."
  ([event-v] (pair-dispatch-sync! event-v {}))
  ([event-v opts]
   (let [frame-id  (or (:frame opts) (current-frame))
         _         (when-not frame-id
                     ;; Carry the enriched ambiguous-frame context
                     ;; (operation, event, available frames, current pin, fix)
                     ;; as ex-data so the MCP `.catch` → `err->result`
                     ;; surfaces it verbatim, not a bare reason.
                     (throw (ex-info "ambiguous frame"
                                     (ambiguous-frame-error :dispatch {:event event-v}))))
         ;; Thread the resolved operating frame in as the explicit `:frame`
         ;; override — the nREPL eval thread carries no ambient `with-frame`
         ;; scope, so `rf/dispatch-sync` would otherwise
         ;; `require-current-frame!` and throw `:rf.error/no-frame-context`.
         opts      (assoc opts :frame frame-id)
         before-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
     (rf/dispatch-sync event-v (merge {:origin :pair} opts))
     (let [after-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
       (cond
         (and after-id (not= before-id after-id))
         (do (mark-pair! after-id)
             (attach-cascade {:ok? true :epoch-id after-id :event event-v :frame frame-id}
                             frame-id after-id))

         (and (nil? before-id) (nil? after-id))
         {:ok? false
          :reason :no-epoch-recorded
          :event event-v
          :frame frame-id
          :hint (str "epoch-history is empty after dispatch. Either depth "
                     "is 0 (disabled), the frame is destroyed, or "
                     "interop/debug-enabled? is false (production build).")}

         :else
         {:ok? false
          :reason :no-new-epoch
          :event event-v
          :frame frame-id
          :hint "dispatch-sync returned, but epoch-history head did not advance."})))))

;; ---------------------------------------------------------------------------
;; Dispatch CONSEQUENCE — the default sync result
;; ---------------------------------------------------------------------------
;;
;; A bare transport ACK (`{:mode :sync}`) makes a no-op indistinguishable
;; from success — a malformed-frame dispatch (the `::rf/xray`
;; colon-coercion) would report success-shaped while doing NOTHING, and
;; only a separate state read would reveal the no-op.
;;
;; `dispatch-consequence!` returns the re-frame2 CONSEQUENCE by default:
;;
;;   {:ok? true :epoch-id <id> :db-changed? <bool> :changed-paths [...]
;;    :effects-fired [...] :no-op? <bool> :event <vec> :frame <id>
;;    :cascade-summary {...}}
;;
;; A no-op then VISIBLY returns `:db-changed? false :effects-fired []
;; :no-op? true` instead of a fake success — and dispatch+verify
;; collapses into one call. The data is already assembled (the epoch
;; history the cascade-summary projects); full trace-collect
;; (`dispatch-and-collect`, `:epoch`) stays opt-in for the whole async
;; cascade.

(defn- consequence-from-summary
  "Project a `pair-dispatch-sync!` success envelope into the
   dispatch-consequence shape. `result` carries
   `:ok? true :epoch-id :cascade-summary`. The summary's `:db-diff`
   (`{:changed-paths :added-paths :removed-paths}`) and `:fx-fired`
   feed the consequence's `:changed-paths` / `:effects-fired`. A cascade
   that changed NO app-db path AND fired NO effect is a visible no-op."
  [result]
  (let [{:keys [cascade-summary]} result
        {:keys [db-diff fx-fired outcome errors]} cascade-summary
        changed (vec (concat (:changed-paths db-diff)
                             (:added-paths db-diff)
                             (:removed-paths db-diff)))
        effects (vec (or fx-fired []))
        db-changed? (boolean (seq changed))
        ;; A contained throw (handler / machine action) is NOT
        ;; a no-op even though it committed no db-change and fired no fx:
        ;; the cascade did nothing PRECISELY BECAUSE the action aborted.
        ;; The `:errors` slot (set by `cascade-summary` when the trace
        ;; carried a `:rf.error/*` exception) excludes the epoch from the
        ;; quiescence heuristic so a non-visual consumer reads the throw,
        ;; not a clean no-op.
        threw?  (boolean (seq errors))
        no-op?  (and (not threw?) (not db-changed?) (empty? effects))]
    (-> result
        (assoc :db-changed?   db-changed?
               :changed-paths changed
               :effects-fired effects
               :no-op?        no-op?)
        (cond-> (= :error outcome) (assoc :outcome :error)))))

(defn dispatch-consequence!
  "Synchronous dispatch returning the re-frame2 CONSEQUENCE by default.
   Validates the event-id against the live `:event`
   registrar FIRST: an unknown id returns the structured
   `validate-registered` error (`:reason :unknown-id` + `:nearest`)
   WITHOUT dispatching — never a silent no-op success. On a known id,
   dispatches synchronously and projects the consequence:

     {:ok? true :epoch-id :db-changed? :changed-paths :effects-fired
      :no-op? :event :frame :resolved :cascade-summary}

   `:resolved` echoes the parsed event vector so the wire
   result carries the value the runtime actually saw. A genuine no-op
   (handler ran, changed nothing, fired nothing) returns `:db-changed?
   false :effects-fired [] :no-op? true` — VISIBLE, not a fake ack.

   On a frame-untargetable / no-epoch failure, the
   `pair-dispatch-sync!` `:ok? false` envelope rides through verbatim —
   the tool surfaces it as an error rather than a fake success."
  ([event-v] (dispatch-consequence! event-v {}))
  ([event-v opts]
   (let [v (validate-event-id event-v)]
     (if-not (:ok? v)
       ;; Unknown event-id — structured error, NO dispatch (no silent
       ;; no-op). Echo the resolved value alongside the nearest matches.
       (assoc v :resolved event-v :dispatched? false)
       (let [result (pair-dispatch-sync! event-v opts)]
         (if (:ok? result)
           (-> (consequence-from-summary result)
               (assoc :resolved event-v))
           ;; Frame-untargetable / no-epoch — pass the structured failure
           ;; through, still echoing the resolved value.
           (assoc result :resolved event-v)))))))

;; ---------------------------------------------------------------------------
;; Dispatch dry-run
;; ---------------------------------------------------------------------------
;;
;; "If I dispatch X, will it do what I expect?" answered without
;; committing. The framework's existing `:fx-overrides` seam (Spec 002
;; §Per-frame and per-call overrides) + the existing `restore-epoch`
;; primitive (Tool-Pair §Time-travel) compose into a true dry-run with
;; NO framework hack required:
;;
;;   1. snapshot the head epoch-id (the rollback target)
;;   2. fx-override EVERY registered fx-id to a recording stub, so
;;      every fx the cascade WOULD have fired is collected — but
;;      none actually executes (including :dispatch / :dispatch-later
;;      / http / navigation / persisted writes / machine spawns)
;;   3. dispatch-sync — the reducer + interceptor chain run normally
;;      (this is where schema validation lives, where the would-be db
;;      shape comes from, where sub-runs / renders / machine
;;      transitions trace); the cascade ASSEMBLES a real epoch
;;   4. read the new head epoch (this IS the cascade-summary source)
;;   5. restore-epoch back to the pre-call head — the framework's
;;      canonical undo gesture rewinds db and trims the epoch ring
;;
;; The recorded fx calls AND the would-be epoch's cascade-summary
;; project together into the response shape:
;;
;;   {:ok? true :dry-run? true :rolled-back? true
;;    :cascade-summary {...}
;;    :would-fire-effects [{:fx-id ... :args ...} ...]
;;    :db-state-after-simulation <would-be-db>}
;;
;; Edge cases:
;;
;; - **`:dispatch` / `:dispatch-later`** — caught by the override and
;;   recorded as would-fire entries; the recursive dispatch never
;;   happens. This is the bead's `:max-effect-chain-depth 1` default:
;;   simulate this event's reducer + its direct fx + LIST what those
;;   fx would dispatch (don't simulate that next level).
;; - **Schema violation** — the reducer's schema check fires the same
;;   way; the epoch settles with the violation in `:trace-events`,
;;   cascade-summary surfaces it via `:outcome`.
;; - **Machine transitions** — the machine-step machinery runs (it's
;;   pure data per Spec 005); transitions appear in the cascade
;;   summary's `:machine-transitions` slot. Machine-fired fx (timer
;;   schedules, spawn/destroy) are stubbed.
;; - **Frame mismatch** — when `:frame` is unregistered, the
;;   pair-dispatch-sync! error path kicks in; no rollback needed.
;; - **Listener fan-out** — `register-listener!` / `register-epoch-
;;   listener!` consumers DO see the epoch land between step 3 and
;;   step 5. This is a documented limitation: the framework has no
;;   "private dispatch" primitive. Production builds elide the entire
;;   listener path anyway; dev-tier listeners observing a phantom
;;   epoch is acceptable in exchange for the simpler composition.
;;   (A follow-on bead can elevate this to a first-class framework
;;   primitive once the cost is justified.)

(defn- registered-fx-ids
  "Every fx-id registered under the `:fx` registrar. Used to build the
  dry-run override map that redirects ALL fx to the recorder. The
  recorder is registered under `:rf2-pair/dry-run-recorder` (a
  reserved namespace per spec/Conventions.md); it's excluded from the
  redirect set so the override `{:dispatch :rf2-pair/dry-run-recorder
  ...}` can resolve."
  []
  (let [all (-> (rf/registrations :fx) keys set)]
    (disj all :rf2-pair/dry-run-recorder)))

(def ^:private dry-run-recordings
  "Atom that the dry-run recorder fx appends `{:fx-id <orig> :args
  <args>}` to during a dispatch. The current dry-run call reads + resets
  this atom inside the same synchronous extent — no concurrent
  contention possible in single-threaded CLJS."
  (atom []))

(defn- record-fx!
  "Append a would-fire entry. Called by the dry-run recorder fx;
  `:fx-id` is the ORIGINAL id (before override), `:args` is the fx's
  args payload — `pr-str`-able for the wire."
  [fx-id args]
  (swap! dry-run-recordings conj {:fx-id fx-id :args args}))

;; The dry-run recorder fx. Each entry it records carries the ORIGINAL
;; fx-id (which is the key the override mapped FROM). The framework
;; doesn't pass the original fx-id to the handler — only the args — so
;; we register one handler per fx-id... actually, we can register a
;; SINGLE recorder that's selected via the override and rely on the
;; `:rf.fx/override-applied` trace event to recover the original id
;; from the cascade's trace stream after the dispatch settles. Cleaner
;; still: register a single recorder, but feed it both the original
;; id and the args by recording PER override-mapping at call time.
;; Since `:fx-overrides` accepts fn-value (a 2-arity `(fn [m args])`)
;; we can synthesise a closure per fx-id that knows its own id. That
;; avoids the trace-walk and keeps the recording local.

(defn- build-dry-run-overrides
  "Build the `:fx-overrides` map for a dry-run dispatch. Each key is
  a registered fx-id; each value is a fn-value override (Spec 002
  §`:fx-overrides` form 3) that records the call into
  `dry-run-recordings` and returns nil. Using fn-value (not id-
  redirect to a registered recorder) lets each closure carry its own
  original fx-id without a trace round-trip."
  []
  (into {}
        (map (fn [fx-id]
               [fx-id
                ;; fn-value override: (fn [_m args] ...). The first arg
                ;; is the fx-context map (unused here); the second is
                ;; the args payload that would have been passed to the
                ;; registered fx handler.
                (fn [_m args] (record-fx! fx-id args) nil)]))
        (registered-fx-ids)))

(defn dispatch-dry-run
  "Run a dispatch through the cascade pipeline without committing it.
   Full reducer + interceptor chain runs, schema validation fires,
   machine transitions simulate, sub-runs and renders are recorded —
   but NO fx execute (every fx is overridden to a recording stub) and
   the framework rolls back to the pre-call epoch head via
   `restore-epoch`.

   Returns:

     {:ok? true
      :dry-run? true
      :rolled-back? true
      :event <event-v>
      :frame <frame-id>
      :cascade-summary {...}             ;; what WOULD have happened
      :would-fire-effects [{:fx-id ...
                            :args ...} ...]  ;; per-call recording
      :db-state-after-simulation <db>}   ;; the would-be db verbatim

   Failure paths:

     - `:reason :no-epoch-recorded`     — epoch-history empty / frame
                                          unregistered / debug-enabled?
                                          false. No rollback needed.
     - `:reason :no-new-epoch`          — dispatch-sync returned but the
                                          head did not advance (the
                                          reducer was a no-op against
                                          the rejected event).
     - `:reason :rollback-failed`       — the would-be epoch assembled
                                          but restore-epoch returned
                                          false. The recorded would-be
                                          db IS the live db; the caller
                                          needs to re-restore manually.
                                          Should not occur in practice
                                          (the id we just produced is
                                          at the head of the ring).

   This primitive is the framework-side surface; the MCP
   tool `dispatch-dry-run` wraps it. Bound by the registered fx set
   at call time — fx registered after the dry-run start (a rare race)
   would slip through the override; the cost is one un-stubbed fx
   firing. Production builds elide the entire epoch + listener path
   so dry-run is dev-only by construction."
  ([event-v] (dispatch-dry-run event-v {}))
  ([event-v opts]
   (let [frame-id  (or (:frame opts) (current-frame))
         _         (when-not frame-id
                     ;; Enriched ambiguous-frame context (see
                     ;; pair-dispatch-sync!); dry-run knows the event vector.
                     (throw (ex-info "ambiguous frame"
                                     (ambiguous-frame-error :dispatch-dry-run {:event event-v}))))
         before-id (some-> (rf/epoch-history frame-id) peek :epoch-id)
         overrides (build-dry-run-overrides)
         _         (reset! dry-run-recordings [])
         ;; Compose user-supplied overrides on top of the dry-run set:
         ;; if the caller passed `:fx-overrides {...}` (some fx
         ;; redirected to OTHER registered fx as part of the
         ;; experiment), those wins on conflict — the recorder fires
         ;; only for fx the caller did NOT pre-stub.
         user-overrides (:fx-overrides opts)
         all-overrides  (merge overrides user-overrides)
         dispatch-opts  (merge opts {:origin :pair
                                     :fx-overrides all-overrides
                                     :frame frame-id})]
     (rf/dispatch-sync event-v dispatch-opts)
     (let [after-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
       (cond
         (and (nil? before-id) (nil? after-id))
         {:ok?     false
          :reason  :no-epoch-recorded
          :event   event-v
          :frame   frame-id
          :hint    (str "epoch-history empty after dry-run dispatch. "
                        "Either depth is 0 (disabled), the frame is "
                        "destroyed, or interop/debug-enabled? is false "
                        "(production build).")}

         (= before-id after-id)
         {:ok?     false
          :reason  :no-new-epoch
          :event   event-v
          :frame   frame-id
          :hint    (str "dispatch-sync returned but no new epoch landed. "
                        "The reducer rejected the event (schema, "
                        "interceptor early-return) or the cascade halted "
                        "before recording. No rollback needed.")}

         :else
         (let [target-epoch (epoch-by-id after-id frame-id)
               recorded     @dry-run-recordings
               ;; Roll back to the pre-call head. The framework's
               ;; restore-epoch rewinds app-db AND trims the ring back
               ;; to the target (the assembled would-be epoch is
               ;; removed from history).
               rolled-back? (boolean (rf/restore-epoch! frame-id before-id))
               base {:ok?                       true
                     :dry-run?                  true
                     :rolled-back?              rolled-back?
                     :event                     event-v
                     :frame                     frame-id
                     :before-epoch-id           before-id
                     ;; Project the would-be epoch into the same
                     ;; cascade-summary shape dispatch /
                     ;; replace-app-db / restore-epoch use.
                     ;; Operators read one vocabulary across all four.
                     :cascade-summary           (cascade-summary target-epoch)
                     :would-fire-effects        recorded
                     :db-state-after-simulation (:db-after target-epoch)}]
           (if rolled-back?
             base
             (assoc base :rollback-hint
                    (str "restore-epoch returned false; the would-be db "
                         "IS the live db. Re-restore manually via "
                         "(rf/restore-epoch! <frame> <before-epoch-id>)."
                         " Should not occur — the id we just produced "
                         "is at the head of the ring.")))))))))

;; ---------------------------------------------------------------------------
;; Time-travel — first-class via re-frame2
;; ---------------------------------------------------------------------------

(defn- restore-cascade-summary
  "Build a cascade-summary projection for a successful `restore-epoch`
   call. A restore is NOT a real cascade — the framework
   rewinds the app-db in-place without recording a new epoch — so the
   normal `epoch-by-id` lookup wouldn't surface anything. Instead we
   project against the TARGET epoch (the one we restored TO) and
   compute the `:db-diff` from `pre-db` (the db state immediately
   before the restore) to the target's `:db-after`. That answers the
   programmer's actual question — 'what is now different from where I
   was?' — using the cascade-summary vocabulary everyone already knows.

   Returns nil if the target epoch isn't in the ring (defensive — a
   successful restore implies the id is in the ring, but the read may
   race against a ring rotation in a heavy concurrent setting).

   Additionally returns the `:unreplayable-effects` slot per the bead
   spec: every fx that the ORIGINAL cascade fired and that the restore
   cannot undo (http requests already sent, navigation already pushed,
   storage already written). Programmers reading 'I just rewound' need
   to know which side-effects already escaped the framework."
  [pre-db frame-id target-epoch-id]
  (when-let [target (epoch-by-id target-epoch-id frame-id)]
    (let [diff       (db-diff-summary pre-db (:db-after target))
          fx-fired   (->> (:effects target) (map :fx-id) distinct vec)
          transitions (machine-transitions-summary (:trace-events target))
          ;; A restore of a SENSITIVE historical
          ;; epoch must not ship the target's raw trigger-event under the
          ;; default off-box posture. Read the target epoch's
          ;; `:rf.epoch/sensitive?` rollup and redact the `:event-vector`
          ;; through the same fail-closed gate cascade-summary uses.
          sensitive?  (:rf.epoch/sensitive? target)
          ;; Every fx in the target's :effects fired BEFORE the restore;
          ;; the restore rewinds db only. They are unreplayable by
          ;; construction.
          unreplayable (mapv (fn [eff]
                               (cond-> {:fx-id (:fx-id eff)}
                                 (:coord eff) (assoc :coord (:coord eff))))
                             (:effects target))]
      {:cascade-summary
       (cond-> {:epoch-id       target-epoch-id
                :frame          frame-id
                :outcome        :ok
                :db-diff        diff
                :fx-fired       fx-fired
                :subs-recomputed (count (or (:sub-runs target) []))
                :renders         (count (or (:renders target) []))
                :restore?       true}
         (:event-id target)      (assoc :event-id (:event-id target))
         (:trigger-event target) (assoc :event-vector
                                        (redact-sensitive-event-vector
                                          (:trigger-event target) sensitive?))
         sensitive?              (assoc :sensitive? true)
         transitions             (assoc :machine-transitions transitions))
       :unreplayable-effects unreplayable})))

(defn restore-epoch
  "(rf/restore-epoch! frame-id epoch-id). Returns a structured envelope:

     - `{:ok? true :restored? true :epoch-id <id> :frame <id>
         :cascade-summary {...} :unreplayable-effects [...]}` on success.
       The cascade-summary projects the TARGET epoch's shape; the
       `:db-diff` slot is computed from the live db at restore-time to
       the target's `:db-after`. `:unreplayable-effects` enumerates the
       fx the original cascade fired that the restore cannot undo.
     - `false` on any failure mode. Failure traces fire under
       `:rf.epoch/*` — read them with
       `(re-frame.trace.tooling/trace-buffer frame-id {:flat true :op-type :error})`
       (frame-id first; `:op-type` is a `:flat-only` filter).

   The two arities mirror `pair-dispatch-sync!`'s shape — 1-arity reads
   `(current-frame)`, 2-arity is explicit."
  ([epoch-id] (restore-epoch epoch-id (current-frame)))
  ([epoch-id frame-id]
   (let [pre-db (rf/app-db-value frame-id)
         ok?    (rf/restore-epoch! frame-id epoch-id)]
     (if ok?
       (let [extras (restore-cascade-summary pre-db frame-id epoch-id)]
         (merge {:ok? true :restored? true :epoch-id epoch-id :frame frame-id}
                extras))
       ;; Return the framework's `false` on failure — the MCP
       ;; restore-epoch tool turns that into a structured envelope at
       ;; the wire boundary. Mirrors how replace-app-db! returns a
       ;; soft-failure envelope on the runtime side but the tool can
       ;; elide the framework's `false`.
       false))))

(defn undo-step-back
  "Restore the previous epoch in the operating frame. Returns
   `{:ok? true :epoch-id <previous> :restored? true :cascade-summary
   {...} :unreplayable-effects [...]}` on success or
   `{:ok? false :reason :no-prior-epoch}` when there is no previous
   record. Carries a cascade-summary slot."
  ([] (undo-step-back (current-frame)))
  ([frame-id]
   (let [history (vec (rf/epoch-history frame-id))
         n       (count history)]
     (if (< n 2)
       {:ok? false :reason :no-prior-epoch :history-size n :frame frame-id}
       (let [prior     (nth history (- n 2))
             epoch-id  (:epoch-id prior)
             pre-db    (rf/app-db-value frame-id)
             ok?       (rf/restore-epoch! frame-id epoch-id)]
         (if ok?
           (merge {:ok? true :epoch-id epoch-id :restored? true :frame frame-id}
                  (restore-cascade-summary pre-db frame-id epoch-id))
           {:ok? false :epoch-id epoch-id :restored? false :frame frame-id
            :reason :restore-rejected}))))))

(defn undo-to-epoch
  "Restore a specific epoch by id. Returns the same shape as
   `restore-epoch`'s success envelope, carrying a cascade-summary slot."
  ([epoch-id] (undo-to-epoch epoch-id (current-frame)))
  ([epoch-id frame-id]
   (let [pre-db (rf/app-db-value frame-id)
         ok?    (rf/restore-epoch! frame-id epoch-id)]
     (if ok?
       (merge {:ok? true :epoch-id epoch-id :restored? true :frame frame-id}
              (restore-cascade-summary pre-db frame-id epoch-id))
       {:ok?       true
        :epoch-id  epoch-id
        :restored? false
        :frame     frame-id
        :reason    :restore-rejected}))))

;; ---------------------------------------------------------------------------
;; DOM ↔ source bridge
;; ---------------------------------------------------------------------------
;;
;; Two attribute sources, in priority order:
;;   1. data-rf2-source-coord (re-frame2's own annotation, mandatory on
;;      registered-view roots in debug builds — Tool-Pair §Source-mapping;
;;      Spec 006 §Source-coord annotation. No configure! opt-in.)
;;   2. data-rc-src (re-com's debug attribute, fallback)
;;
;; The two attributes resolve to different schemas — re-frame2's
;; carries the registry-id derived <ns>/<handler-id> with <line>/<col>;
;; re-com's carries <file>/<line>/<column>. The runtime returns
;; whichever map the first present attribute parses to.

(def ^:private parse-rf2-coord
  "Parse re-frame2's `data-rf2-source-coord` attribute into
   {:ns :handler-id :line :col} or nil.

   Alias of the canonical `re-frame.source-coords/parse-source-coord`
   (the source-coord contract owner) — the inverse of
   `format-source-coord`. The pair preload and Story's
   element_inspector.cljc both route through this one canonical impl in
   core. See that fn for
   the four-segment value format (Spec 006 §Attribute value format),
   the `?`-placeholder degradation, and the Tool-Pair opacity caveat
   (downstream callers MUST NOT depend on the parsed shape's stability
   across re-frame2 versions)."
  source-coords/parse-source-coord)

(def ^:private parse-view-id
  "Parse re-frame2's `data-rf-view` attribute into the registry id keyword
   (or the raw string for a non-keyword id), or nil.

   Alias of the canonical `re-frame.source-coords/parse-view-id` (the source-
   coord contract owner) — the inverse of `format-view-id`. `view-entity` here
   and Xray's fallback view-walker both route through this one canonical impl
   in core. See that fn
   for the value format (Spec 006 §View tagging contract §Attribute value
   format) and the Tool-Pair opacity caveat (downstream callers MUST NOT depend
   on the parsed shape's stability across re-frame2 versions)."
  source-coords/parse-view-id)

(defn- parse-rc-src
  "Parse re-com's `data-rc-src` attribute into {:file :line :column}.
   Expected shapes: 'app/cart.cljs:42', 'app/cart.cljs:42:8'."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [parts (str/split attr-val #":")
          valid-line? (fn [s] (and s (re-matches #"\d+" s)))]
      (when (and (>= (count parts) 2) (valid-line? (nth parts 1 nil)))
        {:file   (first parts)
         :line   (js/parseInt (nth parts 1) 10)
         :column (when (and (>= (count parts) 3) (valid-line? (nth parts 2 nil)))
                   (js/parseInt (nth parts 2) 10))}))))

(defn- read-coord-from-element
  "Try data-rf2-source-coord first, then data-rc-src. Returns the
   parsed map or nil."
  [el]
  (or (some-> (.getAttribute el "data-rf2-source-coord") parse-rf2-coord)
      (some-> (.getAttribute el "data-rc-src") parse-rc-src)))

(defn coord-annotation-enabled?
  "Heuristic: at least one element on the page carries either
   `data-rf2-source-coord` or `data-rc-src`. False when neither
   annotation source is producing attributes (no registered-view
   coverage / non-DOM adapter / production build, and no re-com
   :src (at) call sites). Reads may be unreliable on a freshly-loaded
   page that hasn't rendered."
  []
  (or (some? (.querySelector js/document "[data-rf2-source-coord]"))
      (some? (.querySelector js/document "[data-rc-src]"))))

;; Last-clicked capture — passive listener that records the element
;; most recently clicked anywhere on the page. Installed once by
;; `install-last-click-capture!` during injection so ops like
;; `dom/source-at :last-clicked` have something to resolve.

(defonce ^:private last-clicked (atom nil))

(defn install-last-click-capture!
  "Install a single capturing click listener on document that records
   the most recently clicked element. Idempotent — calling twice does
   not double-register (guard via a marker on window)."
  []
  (when-not (aget js/window "__rfp2_click_capture__")
    (aset js/window "__rfp2_click_capture__" true)
    (.addEventListener
     js/document
     "click"
     (fn [e] (reset! last-clicked (.-target e)))
     #js {:capture true :passive true})))

(defn last-clicked-element
  "Return the DOM element most recently clicked, or nil if nothing has
   been clicked yet this session."
  []
  @last-clicked)

(defn- selector-or-last-clicked [selector]
  (cond
    (or (= selector :last-clicked) (= selector "last-clicked"))
    (last-clicked-element)

    (string? selector)
    (.querySelector js/document selector)

    :else nil))

(defn dom-source-at
  "Given a CSS selector (or `:last-clicked`), return the source coord
   attached by re-frame2's annotation or re-com's debug path. Shape
   depends on which attribute matched:
     - re-frame2: {:ns :handler-id :line :col}  (Spec 006 §Source-coord
       annotation)
     - re-com:    {:file :line :column}
   Returns a structured result wrapping the coord under :src."
  [selector]
  (if-let [el (selector-or-last-clicked selector)]
    (if-let [coord (read-coord-from-element el)]
      {:ok? true :src coord :selector selector}
      {:ok? true :src nil :selector selector
       :reason (if (coord-annotation-enabled?)
                 :no-coord-at-this-element
                 :source-coord-annotation-disabled)})
    {:ok? false :reason :no-element :selector selector
     :hint (when (or (= selector :last-clicked) (= selector "last-clicked"))
             "Nothing clicked this session; interact with the page first, or pass a CSS selector instead.")}))

(defn dom-find-by-src
  "Find live DOM elements whose source-coord attributes mention the
   needle `<file-or-id>:<line>`. Searches both `data-rf2-source-coord`
   and `data-rc-src`.

   For `data-rc-src` the needle pairs the source file and line.
   For `data-rf2-source-coord` (whose value is `<ns>:<handler-id>:<line>:<col>`,
   per Spec 006 §Source-coord annotation) callers typically pass the
   handler-id as the first argument so the substring match hits the
   handler-id segment. Pair-shaped consumers wanting <ns>-scoped
   queries should construct their own selector."
  [file-or-id line]
  (let [needle (str file-or-id ":" line)
        nodes  (.querySelectorAll js/document
                                  (str "[data-rf2-source-coord*='" needle "'],"
                                       "[data-rc-src*='" needle "']"))]
    (->> (array-seq nodes)
         (mapv (fn [node]
                 {:tag   (.toLowerCase (.-tagName node))
                  :id    (not-empty (.-id node))
                  :class (not-empty (.-className node))
                  :src   (read-coord-from-element node)})))))

(defn dom-fire-click
  "Synthesise a click on the element whose source-coord attribute
   contains the substring `<file-or-id>:<line>`. Picks the first
   match if multiple. Returns {:ok? true :clicked {...}} — the
   resulting epoch lands asynchronously, fetch it with `last-epoch`.
   See `dom-find-by-src` for the needle semantics."
  [file-or-id line]
  (let [needle   (str file-or-id ":" line)
        selector (str "[data-rf2-source-coord*='" needle "'],"
                      "[data-rc-src*='" needle "']")
        el       (.querySelector js/document selector)]
    (if el
      (let [ev (js/Event. "click" #js {:bubbles true :cancelable true})]
        (.dispatchEvent el ev)
        {:ok?     true
         :clicked {:tag (.toLowerCase (.-tagName el))
                   :id  (not-empty (.-id el))}})
      {:ok? false :reason :no-element-at-src :file-or-id file-or-id :line line})))

(defn dom-describe
  "Summarise a DOM element."
  [selector]
  (if-let [el (.querySelector js/document selector)]
    {:ok?      true
     :tag      (.toLowerCase (.-tagName el))
     :id       (not-empty (.-id el))
     :class    (not-empty (.-className el))
     :src      (read-coord-from-element el)
     :rf2-src  (some-> (.getAttribute el "data-rf2-source-coord") parse-rf2-coord)
     :rc-src   (some-> (.getAttribute el "data-rc-src") parse-rc-src)
     :text     (let [t (.-textContent el)]
                 (when (and t (< (count t) 200)) t))}
    {:ok? false :reason :no-element :selector selector}))

;; ---------------------------------------------------------------------------
;; ui-read — view-plane RENDERED-CONTENT read + producing ENTITY
;; ---------------------------------------------------------------------------
;;
;; The two DOM-read planes. `dom-read` (above) is the RAW DOM
;; plane: a CSS selector → matched nodes, multi-node, NO re-frame2 awareness
;; — "what does this exact node SAY?". `ui-read` (here) is the re-frame2 VIEW
;; plane: it rides the `data-rf-view` map → content PLUS the producing ENTITY
;; (view-id, source-coord, subs-read, render-key) — "what is this view, and
;; what produced it?". They share the per-node projection core (`node->content`)
;; so the projection cannot rot on one plane alone, but the planes stay
;; semantically distinct. Pick `dom-read` when you have a selector and want
;; raw content across N nodes; pick `ui-read` when you want a view's content
;; AND its re-frame2 provenance in one round-trip.
;;
;; The DOM-to-source bridge above (dom-source-at / dom-describe /
;; dom-find-by-src) answers "where in the SOURCE did this come from?" — a
;; gesture/selector → source-coord + registration meta. It does NOT answer
;; the most common UI-pairing question: "what does the thing I'm looking
;; at actually SHOW, and which re-frame2 entity produced it?" Answering
;; that meant hand-rolling an eval-cljs `querySelectorAll` + `textContent`
;; slice with GUESSED selectors, then a SECOND round-trip to map the node
;; back to a view-id. `ui-read` first-classes the whole gesture.
;;
;; ## Riding the view↔DOM map (zero testids)
;;
;; re-frame2 ALREADY maintains the view-id↔DOM-node mapping: every
;; registered view's rendered root carries `data-rf-view="<id>"` (Spec 006
;; §View tagging contract; Spec-Schemas §`:rf/view-id-attr`) — the SAME
;; attribute the Xray pink hover-highlight resolves
;; (`apply-view-highlight!` builds `[data-rf-view='<id>']` and toggles a
;; class). The sibling `data-rf2-source-coord` lands on the same root
;; (Spec 006 §Source-coord annotation). `ui-read` reuses BOTH: it never
;; guesses a selector and never re-implements view discovery — it reads
;; the attributes the substrate adapter already stamps, so it works on ANY
;; re-frame2 app with NO app-specific test ids.
;;
;; ## Three entry points → one element → one entity
;;
;;   :view-id   — `(view-element view-id)` resolves `[data-rf-view='id']`
;;                directly. The element IS the producing view's root.
;;   :point     — `{:x N :y N}` → `document.elementFromPoint` → walk up to
;;                the nearest `[data-rf-view]` ancestor (the producing
;;                view). "What's under the cursor at (x,y)?"
;;   :selector  — a CSS selector → `querySelector` → walk up to the
;;                nearest tagged ancestor. Narrows a coarse hover to the
;;                view that owns it.
;;
;; The resolved producing ENTITY is the headline payload — view-id,
;; source-coord (attribute + `handler-meta :view` :file augmentation),
;; render-key (a stable node hash, the fallback view-walker's `:node-key`
;; vocabulary), and the live `subs-read` (the frame's materialised
;; sub-cache query-vectors — "which subs feed this view?").
;;
;; ## Privacy — elide like snapshot / get-path
;;
;; Rendered DOM text can carry user data (a name, an email, a PDF dump).
;; The returned text is routed through `re-frame.core/elide-wire-value`
;; with off-box defaults (the SAME walker `snapshot` / `get-path` use, per
;; Tool-Pair §Direct-read privacy posture) so a declared-large blob
;; collapses to `:rf.size/large-elided` rather than shipping raw user DOM
;; text unconditionally. A hard per-node character cap (`max-text`) trims
;; the common case before the walker even sees it.
;;
;; Read-only by construction: only `textContent` / attribute strings /
;; `elementFromPoint` / `querySelector` are read — never a write, a
;; dispatch, or a node mutation.

(defn- view-element
  "Resolve the rendered root DOM element of a registered view by its id,
   via the `[data-rf-view='<id>']` attribute the substrate adapter stamps
   (Spec 006 §View tagging contract). Mirrors the selector
   `apply-view-highlight!` (Xray) builds — the SAME view↔DOM map. Accepts
   a keyword or string id; both stringify to the attribute value
   (`(str id)`, the format the adapter writes). Returns the first match,
   or nil."
  [view-id]
  (when (and (exists? js/document) (some? view-id))
    (.querySelector js/document (str "[data-rf-view='" view-id "']"))))

(defn- nearest-view-root
  "Return `el` itself when it carries `data-rf-view`, else the nearest
   ancestor that does — the producing view's root for an arbitrary node
   (a point hit or a sub-selector match). Mirrors the fallback
   view-walker's `nearest-tagged-ancestor` containment rule (Spec 006)."
  [el]
  (loop [n el]
    (cond
      (nil? n) nil
      (and (some? (.-getAttribute n))
           (some? (.getAttribute n "data-rf-view"))) n
      :else (recur (.-parentNode n)))))

;; ---------------------------------------------------------------------------
;; Shared DOM-read core — used by BOTH `dom-read` (raw DOM plane) and
;; `ui-read` (re-frame2 view plane), so the per-node projection cannot rot
;; on one op alone.
;;
;; Both ops run through THIS runtime ns (which `:require`s
;; `[clojure.string :as str]`), so the projection's `(str/lower-case …)` and
;; any other aliased call resolve. A copy inlined as a raw eval string in an
;; MCP tool would run in a BARE browser cljs-eval context that aliases
;; NOTHING — `str` would be unresolved, the form would nil out, and the read
;; would come back blank. Keeping the projection here, in a namespace with
;; real requires, is the single place a fix or a break lands.
;;
;; The SEMANTICS stay distinct — `dom-read` is the raw DOM plane (a CSS
;; selector → matched nodes, multi-node, no re-frame2 awareness) and
;; `ui-read` is the view plane (rides the `data-rf-view` map → content PLUS
;; the producing entity). Only the per-node PROJECTION (tag + capped text +
;; attribute map) is shared.

(def ^:private structural-attrs
  "The structural identity attributes both planes surface by name when no
   explicit attr list is supplied. Carries rendered identity / state."
  #{"id" "class" "role" "type" "name" "value" "href"
    "title" "placeholder" "disabled" "checked" "selected" "hidden"})

(def ^:private internal-attrs
  "Framework-internal annotations dropped from the view-plane attr map —
   they're already surfaced structurally under `:entity`, so leaving them
   in the raw attr map would be noise."
  #{"data-rf-view" "data-rf2-source-coord"})

(defn- collect-attrs
  "Collect a node's attributes as a `{name value}` map.

   `opts`:
     :names         when a non-nil vector of attribute-name strings, ONLY
                    those names are read (the caller is in control — the
                    raw-DOM-plane explicit-`:attrs` mode). When nil, the
                    curated `structural-attrs` set rides.
     :prefix-sweep? when true, ALSO sweep every `data-*` / `aria-*`
                    attribute the node carries (the view-plane idiom for
                    surfacing rendered state).
     :drop-internal? when true, omit the framework-internal annotations
                    (`internal-attrs`) — the view plane drops them; the
                    raw DOM plane keeps whatever the caller named.

   Implemented as a single attribute walk so both planes share the
   selection rules."
  [el {:keys [names prefix-sweep? drop-internal?]}]
  (let [named (when names (set names))]
    (persistent!
      (reduce
        (fn [acc a]
          (let [nm (.-name a)]
            (if (and (or (nil? drop-internal?)
                         (not (contains? internal-attrs nm)))
                     (or (if named
                           (contains? named nm)
                           (contains? structural-attrs nm))
                         (and prefix-sweep?
                              (or (.startsWith nm "data-")
                                  (.startsWith nm "aria-")))))
              (assoc! acc nm (.-value a))
              acc)))
        (transient {})
        (array-seq (.-attributes el))))))

(defn- cap-text
  "Read `el`'s `textContent`, capped at `max-text` characters. Over-cap
   text collapses to the framework size-elision marker shape
   `{:rf.size/large-elided {:type :dom-text :chars N :preview \"…\"}}` —
   the SAME shape `get-path` / `snapshot` emit, so an agent
   recognises an elision uniformly. Under-cap text rides as the raw
   string. Pure read."
  [el max-text]
  (let [t  (let [tc (.-textContent el)] (if (string? tc) tc ""))
        tn (count t)]
    (if (> tn max-text)
      {:rf.size/large-elided
       {:type :dom-text :chars tn :preview (subs t 0 (min tn 120))}}
      t)))

(defn- node->content
  "Project ONE DOM element to the structured `{:tag :text :attrs}` shape
   both DOM-read planes return. `max-text` caps the text (see `cap-text`);
   `attr-opts` selects the attribute strategy (see `collect-attrs`). The
   single place the per-node projection lives — a fix or a break here
   lands on BOTH `dom-read` and `ui-read`, never one alone."
  [el max-text attr-opts]
  {:tag   (str/lower-case (or (.-tagName el) ""))
   :text  (cap-text el max-text)
   :attrs (collect-attrs el attr-opts)})

;; ---------------------------------------------------------------------------
;; dom-read — raw DOM plane: CSS selector → matched nodes {:tag :text :attrs}
;;
;; The complement to `ui-read`: NO re-frame2 awareness. A plain
;; `querySelectorAll`, optional sub-selector run relative to each match, a
;; matched-node `:limit`, and the shared per-node projection. Caps run
;; HERE (browser-side) so only bounded EDN crosses the wire — a 5 MB <pre>
;; never leaves the tab.

(defn dom-read
  "Read rendered DOM content by CSS selector — the RAW DOM plane.
   Returns the matched-node count + per-node
   `{:tag :text :attrs}`, capped at the source. The view-plane
   counterpart is `ui-read` (which rides the `data-rf-view` map and also
   returns the producing re-frame2 entity).

   `opts`:
     :selector      CSS selector (required) — `querySelectorAll`.
     :sub-selector  optional CSS selector run RELATIVE to each matched
                    node (`node.querySelectorAll`) — narrows a coarse
                    match to its inner parts. When supplied the result's
                    `:nodes` are the sub-matches.
     :limit         max matched nodes returned (default 50). Excess nodes
                    drop and `:truncated?` flips true; `:count` still
                    reports the full tally.
     :max-text      per-node `textContent` char cap (default 2000).
                    Over-cap text → `:rf.size/large-elided` marker.
     :attrs         optional vector of attribute-name strings. When
                    supplied ONLY those are read (the caller is in
                    control). When omitted the curated structural set
                    rides PLUS a `data-*` / `aria-*` prefix sweep.
     :frame         optional operating-frame override — names the frame
                    whose declared classification the rendered text / attrs
                    are PATH-projected against (see below).

   PRIVACY. Rendered DOM text / attribute values that sit AT a declared-
   classified app-db PATH within the projected tree redact. Under the off-box
   egress posture (raw-state gate OFF — the published-build default) the
   matched nodes are PATH-projected via `maybe-redact-derived`
   (`re-frame.core/project-egress`, the :rf.observe/derived-tree boundary)
   against the operating frame's classification, so a value at a classified
   path lands as `:rf/redacted` before crossing the off-box wire. EP-0025
   FAIL-OPEN: value-match (taint-by-equality) is REMOVED, so a secret copied
   out of a declared-sensitive app-db slot INTO a non-app-db DOM position
   ships RAW — the path walker reaches only values at a classified path. To
   keep a value out of rendered content, classify its app-db PATH so it is
   redacted at the source before a view renders it. Gate ON
   (`--allow-sensitive-reads`) passes the nodes through verbatim (the
   operator's deliberate trusted-local raw read). When the gate is OFF and the
   frame is AMBIGUOUS (multi-app, none pinned) the op FAILS CLOSED with
   `:reason :ambiguous-frame` rather than ship raw DOM or synthesise
   `:rf/default`.

   Returns (success):
     {:ok? true :selector <sel> [:sub-selector <sub>] :count <total>
      :truncated? <bool> :nodes [{:tag :text :attrs} …]}

   Failure (each :ok? false, never a silent empty):
     :rf.error/read-dom-no-document   — no DOM (server-side / headless)
     :rf.error/read-dom-bad-selector  — malformed CSS selector
     :ambiguous-frame                 — gate OFF + no resolvable frame

   Read-only by construction: only `querySelectorAll` / `textContent` /
   attribute strings are read — never a write, a dispatch, or a node
   mutation."
  ([] (dom-read {}))
  ([opts]
   (let [{:keys [selector sub-selector limit max-text attrs frame]} opts
         limit    (if (and (number? limit) (pos? limit)) (long limit) 50)
         max-text (if (and (number? max-text) (pos? max-text)) (long max-text) 2000)
         ;; nil attrs ⇒ curated structural set + data-*/aria- sweep; an
         ;; explicit vector ⇒ exactly those names, no sweep (the caller is
         ;; in control). The raw DOM plane keeps whatever the caller named
         ;; (no internal-annotation drop — that's a view-plane concern).
         attr-opts (if attrs
                     {:names attrs :prefix-sweep? false}
                     {:names nil   :prefix-sweep? true})
         ;; Resolve the frame whose declared classification the rendered
         ;; nodes are PATH-projected against. Only load-bearing under the
         ;; off-box gate (gate ON passes raw, frame irrelevant).
         gate-on?  (:allow-raw-state? @raw-state-config)
         frame-id  (current-frame frame)]
     (cond
       (not (exists? js/document))
       {:ok? false :reason :rf.error/read-dom-no-document}

       ;; Fail CLOSED: off-box posture needs a frame to source the
       ;; classification for the PATH projection; an ambiguous frame can't
       ;; pick one, so refuse rather than ship raw DOM (acceptance: never
       ;; synthesise :rf/default).
       (and (not gate-on?) (nil? frame-id))
       (ambiguous-frame-error :read-dom {:selector selector})

       :else
       (try
         (let [nodes  (array-seq (.querySelectorAll js/document selector))
               scoped (if (some? sub-selector)
                        (mapcat (fn [n] (array-seq (.querySelectorAll n sub-selector))) nodes)
                        nodes)
               total  (count scoped)
               want   (take limit scoped)
               ;; PATH-project rendered text + attrs against the frame's
               ;; classification (off-box default); gate ON passes verbatim.
               proj   (-> (mapv #(node->content % max-text attr-opts) want)
                          (maybe-redact-derived frame-id))]
           (cond-> {:ok?        true
                    :selector   selector
                    :count      total
                    :truncated? (> total (count want))
                    :nodes      proj}
             (some? sub-selector) (assoc :sub-selector sub-selector)))
         (catch :default e
           {:ok?      false
            :reason   :rf.error/read-dom-bad-selector
            :selector selector
            :message  (.-message e)}))))))

(defn- view-entity
  "Resolve the re-frame2 ENTITY that produced `view-root` — the headline
   of a `ui-read`. `view-root` is a DOM element carrying `data-rf-view`
   (or nil when the hit element had no tagged ancestor, e.g. a portal /
   fragment root — Spec 006 §Documented edge cases). `frame-id` resolves
   the sub-cache for the `:subs-read` slice.

   Returns:
     {:view-id      <keyword|string|nil>   ;; parsed from data-rf-view
      :source-coord {:ns :handler-id :line :col :file}  ;; attr + handler-meta
      :render-key   <int>                  ;; stable node hash (view-walker :node-key)
      :subs-read    [<query-v> ...]}        ;; the frame's live materialised subs

   When `view-root` is nil → `{:view-id nil :reason :no-tagged-view-root}`
   (the content still rides; the entity is simply unresolvable for that
   node)."
  [view-root frame-id]
  (if (nil? view-root)
    {:view-id nil :reason :no-tagged-view-root}
    (let [attr     (.getAttribute view-root "data-rf-view")
          ;; The canonical `data-rf-view` reader (the inverse of
          ;; `format-view-id`): leading-colon → keyword, else raw string
          ;; (Spec 006 §View tagging contract). Same parse the fallback
          ;; view-walker uses — both alias core's `parse-view-id`.
          view-id  (parse-view-id attr)
          ;; Source-coord: the attribute carries <ns>:<sym>:<line>:<col>;
          ;; handler-meta augments with :file (the four-segment attr can't
          ;; carry an absolute path — Tool-Pair §Where the DOM-to-source
          ;; helpers live).
          attr-coord (some-> (.getAttribute view-root "data-rf2-source-coord")
                             parse-rf2-coord)
          meta-coord (when (some? view-id)
                       (try (rf/handler-meta :view view-id) (catch :default _ nil)))
          coord      (when (or attr-coord meta-coord)
                       (merge (select-keys meta-coord [:ns :line :column :file])
                              attr-coord))
          ;; subs-read: the frame's live materialised sub-cache query
          ;; vectors — "which subscriptions are feeding this frame's
          ;; views?" The reactive cache is per-frame, not per-view (subs
          ;; are frame-scoped), so this is the frame's reactive surface
          ;; that the view consumes. Sorted by pr-str for stable output.
          subs-read  (when frame-id
                       (try
                         (->> (or (subs-tooling/sub-cache-snapshot frame-id) {})
                              keys
                              (sort-by pr-str)
                              vec)
                         (catch :default _ nil)))]
      {:view-id      view-id
       :source-coord coord
       :render-key   (hash view-root)
       :subs-read    (or subs-read [])})))

(defn ui-read
  "Read the RENDERED content of a view (or the view at a point / selector)
   as structured, ELIDED data, PLUS the re-frame2 entity that produced it.
   The re-frame2 VIEW plane — answers \"what does the thing
   I'm looking at SHOW, and what produced it?\" in ONE round-trip, on ANY
   re-frame2 app with zero testids.

   Sibling op `dom-read` is the RAW DOM plane (a CSS selector → matched
   nodes, multi-node, no re-frame2 awareness). `ui-read` is the VIEW plane:
   it rides the `data-rf-view` map to return content PLUS provenance
   (view-id / source-coord / subs-read / render-key). They share the
   per-node projection core (`node->content`) but stay semantically
   distinct — pick `dom-read` for raw content by selector, `ui-read` for a
   view + its re-frame2 entity.

   `opts` selects exactly one entry point (`:view-id` wins, then `:point`,
   then `:selector`) and tunes the read:

     :view-id   keyword/string — resolve `[data-rf-view='<id>']` (the same
                view↔DOM map the Xray hover-highlight rides).
     :point     {:x N :y N}    — `elementFromPoint`, then walk up to the
                nearest tagged view root. \"What's under the cursor?\"
     :selector  CSS string     — `querySelector`, then walk up to the
                producing view root.
     :max-text  per-node textContent char cap (default 2000). Over-cap
                text is replaced with a `:rf.size/large-elided` marker
                BEFORE the redaction pass runs.
     :frame     operating-frame override for the `:subs-read` slice +
                the PATH-projection source-db.

   PRIVACY. The rendered `:content` (text AND attrs) is PATH-projected: a
   value sitting AT a declared-classified app-db PATH within the content
   redacts. Under the off-box egress posture (raw-state gate OFF — the
   published-build default) the whole `:content` is PATH-projected via
   `maybe-redact-derived` (`re-frame.core/project-egress`, the
   :rf.observe/derived-tree boundary) against the frame's classification, so a
   value at a classified path lands as `:rf/redacted`. EP-0025 FAIL-OPEN:
   value-match (taint-by-equality) is REMOVED, so a secret copied out of a
   declared-sensitive app-db slot INTO a non-app-db DOM position ships RAW —
   the path walker reaches only values at a classified path. To keep a value
   out of rendered content, classify its app-db PATH so it is redacted at the
   source before a view renders it. The hard per-node `max-text` cap still
   trims the common large case first. Gate ON (`--allow-sensitive-reads`)
   passes the content through verbatim (trusted-local raw). When the gate is
   OFF and the frame is AMBIGUOUS (multi-app, none pinned) the op FAILS CLOSED
   with `:reason :ambiguous-frame` rather than ship raw content or synthesise
   `:rf/default`.

   Returns:
     {:ok?     true
      :via     :view-id | :point | :selector
      :entity  {:view-id <id> :source-coord {...} :render-key <int>
                :subs-read [<query-v> ...]}
      :content {:tag \"div\" :text <string|large-elided-marker|redacted>
                :attrs {<name> <value> ...}}}

   Failure modes (each :ok? false, never a silent empty):
     :no-document            — no DOM (server-side / headless eval target)
     :no-target-arg          — none of :view-id / :point / :selector given
     :no-element             — the entry point matched nothing
     :ambiguous-frame        — gate OFF + no resolvable frame
     :rf.error/ui-read-bad-selector — a malformed CSS selector"
  ([] (ui-read {}))
  ([opts]
   (let [{:keys [view-id point selector max-text frame]} opts
         max-text (if (and (number? max-text) (pos? max-text)) (long max-text) 2000)
         gate-on? (:allow-raw-state? @raw-state-config)
         frame-id (current-frame frame)]
     (cond
       (not (exists? js/document))
       {:ok? false :reason :no-document}

       (not (or (some? view-id) (some? point) (some? selector)))
       {:ok?  false :reason :no-target-arg
        :hint "pass exactly one of :view-id, :point {:x N :y N}, or :selector"}

       ;; Fail CLOSED: off-box posture needs a frame to source the PATH-based
       ;; classification; an ambiguous frame can't pick one, so refuse rather
       ;; than ship content with no frame to project against (acceptance:
       ;; never synthesise :rf/default). NB EP-0025: the projection is
       ;; path-based — a re-keyed DOM secret ships raw even with a frame.
       (and (not gate-on?) (nil? frame-id))
       (ambiguous-frame-error :read-ui)

       :else
       (try
         (let [via    (cond (some? view-id) :view-id
                            (some? point)   :point
                            :else           :selector)
               ;; Resolve the HIT element for the chosen entry point.
               hit-el (case via
                        :view-id  (view-element view-id)
                        :point    (let [{:keys [x y]} point]
                                    (.elementFromPoint js/document x y))
                        :selector (.querySelector js/document selector))]
           (if (nil? hit-el)
             {:ok? false :reason :no-element :via via}
             (let [;; The producing view root: for :view-id the hit IS the
                   ;; root; for :point / :selector walk up to the nearest
                   ;; tagged ancestor (a portal / fragment leaf may have
                   ;; none — entity then reports :no-tagged-view-root).
                   view-root (if (= via :view-id)
                               hit-el
                               (nearest-view-root hit-el))
                   ;; Shared per-node projection — the SAME
                   ;; tag + capped-text + attr core dom-read uses. The
                   ;; view plane reads the curated structural set PLUS the
                   ;; data-*/aria- sweep, and DROPS the framework-internal
                   ;; annotations (already surfaced under :entity). The
                   ;; hard text cap (`max-text`) runs HERE, inside the
                   ;; shared `cap-text`, BEFORE the redaction pass — keeps
                   ;; a 5 MB <pre> from ever reaching the wire.
                   base      (node->content hit-el max-text
                                            {:names nil :prefix-sweep? true
                                             :drop-internal? true})
                   ;; PATH-project the WHOLE rendered content (text AND attrs)
                   ;; against the frame's declared classification.
                   ;; `project-egress` (:rf.observe/derived-tree) walks the
                   ;; tree through the path-based `elide-wire-value`: a value
                   ;; AT a classified app-db path redacts. EP-0025 FAIL-OPEN —
                   ;; value-match is removed, so a secret re-keyed INTO a
                   ;; non-app-db DOM position ships raw. Off-box default
                   ;; projects; gate ON passes verbatim (trusted-local).
                   content   (maybe-redact-derived base frame-id)]
               {:ok?     true
                :via     via
                :entity  (view-entity view-root frame-id)
                :content content})))
         (catch :default e
           {:ok?     false
            :reason  :rf.error/ui-read-bad-selector
            :message (.-message e)}))))))

;; ---------------------------------------------------------------------------
;; Signal recorder
;; ---------------------------------------------------------------------------
;;
;; Intermittent / human-in-the-loop bugs (a render-timing race only
;; reproducible under real mouse input) need a recorder:
;; install an observer, let the human interact, read back a change-log.
;; Hand-rolling that each session — a `requestAnimationFrame` loop pushing
;; focus-slot + DOM snapshots into a window global — is decisive but
;; footgun-prone: rAF timing, change-dedup, teardown. This first-classes
;; the whole gesture.
;;
;; A SIGNAL is a read-only sample of one observable: an app-db path, a
;; subscription value, a DOM text/attribute read, or the currently-focused
;; element. A RECORDING samples its signal-set every animation frame,
;; records each CHANGE (per signal, structural `=` against the last value)
;; with a timestamp + frame counter, and tears itself down at a STOP
;; condition (after N ms, after N changes, or when a predicate over the
;; current sample holds). The change-log is read back via `read-recording`.
;;
;; Footguns handled once, here:
;;   - rAF timing      — the sampler runs inside the rAF callback, so it
;;                       reads post-layout state once per paint and never
;;                       busy-loops; environments without rAF fall back to
;;                       `next-tick`.
;;   - change-dedup    — only a structural change against the per-signal
;;                       last value appends an entry. A signal that holds
;;                       steady across 10,000 frames yields ONE baseline
;;                       entry, not 10,000.
;;   - teardown        — the rAF loop cancels itself the instant the stop
;;                       condition trips, and `stop-recording!` is an
;;                       idempotent manual escape hatch. A capped ring
;;                       (`max-entries`, drop-oldest) bounds memory even if
;;                       a recording is forgotten.
;;
;; Read-only by construction: every signal sampler only READS (get-in /
;; subscribe-deref / querySelector text+attr / activeElement). A recording
;; never dispatches, never mutates app-db, never writes the DOM.

(defonce ^:private recordings
  ;; recording-id -> recording map (see start-recording! for the shape)
  (atom {}))

(def ^:private default-recording-max-entries
  ;; Drop-oldest ring cap on a single recording's change-log. Sized so a
  ;; busy multi-signal session (focus + a handful of DOM/app-db signals,
  ;; each flipping a few times a second over a minute) fits comfortably,
  ;; while a forgotten recording can't accumulate unboundedly.
  2000)

(def ^:private default-recording-stop-ms
  ;; Default wall-clock stop when the caller names no stop condition.
  ;; 30 s is a generous "let me interact while you watch" window.
  30000)

(defn- maybe-elide-sample
  "Elide a sampled `:app-db` / `:sub` value for off-box egress.

   The signal recorder (`record` / `read-recording`) and the blocking watch
   (`watch-until`) ship the sampled VALUES of `{:app-db [path]}` and
   `{:sub [query-v]}` signals back to the model. Those values derive
   straight from a live app's app-db — a declared-sensitive slot (or a sub
   deriving from one) would cross the AI/off-box boundary RAW, exactly the
   leak class `get-path` / `read-sub` / `snapshot` / `list-subscriptions`
   already close via `re-frame.core/elide-wire-value`. This routes the
   value through the SAME walker, server-side (app-side, where the
   `[:rf.runtime/elision]` registry in runtime-db is reachable).

   Gate posture mirrors `maybe-elide-for-tap` + the MCP read surfaces:

   - Gate OFF (`:allow-raw-state? false`, the published-build default the
     MCP server signals via `configure-raw-state!`): the value is walked
     with `:rf.size/include-sensitive? false` — declared-sensitive slots
     land as `:rf/redacted`, declared-large slots as
     `:rf.size/large-elided`. A hostile per-call opt-in cannot ship raw
     when the operator did not pass `--allow-sensitive-reads`.
   - Gate ON (`:allow-raw-state? true`): `elide-opts` carries the caller's
     per-call posture. `:include-sensitive? true` (the operator's explicit
     opt-in) passes declared-sensitive slots through verbatim; absent /
     false still elides. `elide-opts` `nil` ⇒ gate-OFF-equivalent
     fail-closed defaults so a bare REPL caller is never less safe than
     the MCP path.

   `frame-id` is supplied so the walker resolves the right per-frame
   elision registry."
  [v frame-id elide-opts]
  (let [gate-on? (:allow-raw-state? @raw-state-config)
        ;; Fail-closed: when the gate is OFF, force include-sensitive? false
        ;; regardless of what the caller threaded — the launch flag wins.
        opts     (cond-> (merge {:frame frame-id} elide-opts)
                   (not gate-on?) (assoc :rf.size/include-sensitive? false))]
    (rf/elide-wire-value v opts)))

(defn- sample-one-signal
  "Read ONE signal's current value. Pure READ — never mutates. `signal`
   is a map naming exactly one observable; the recognised shapes:

     {:app-db <path-vector>}     — `(get-in app-db path)` for the frame.
     {:sub <query-vector>}       — current deref of the subscription.
     {:dom <css-selector>}       — the first matching node's textContent
       [:attr <name>]              (string), or, with `:attr`, that
                                    attribute's value. nil when no match.
     {:focus true}               — a stable descriptor of
                                    `document.activeElement` (tag + id +
                                    a short data-/aria- attr digest) — the
                                    focus-slot.

   `frame-id` resolves the operating frame for :app-db / :sub signals.
   Returns the sampled value (any EDN-able shape) or nil. Errors degrade
   to `{:rf.recording/error <message>}` so one bad signal never collapses
   the whole sampler tick.

   The value-bearing `:app-db` / `:sub` arms route the
   sampled value through `maybe-elide-sample` (the off-box-egress walker)
   so a declared-sensitive app-db slot (or a sub deriving from one) is
   redacted before it crosses the MCP boundary, under the default
   `--allow-sensitive-reads OFF` gate. `elide-opts` carries the caller's
   per-call posture (gate-ON `:include-sensitive` opt-in); `nil` ⇒
   fail-closed defaults.

   The `:dom` / `:focus` arms are DERIVED reads: a node's
   textContent / attribute / focus descriptor can carry a secret copied out
   of a declared-sensitive app-db slot into a NON-app-db position. They are
   PATH-projected via `maybe-redact-derived` (`re-frame.core/project-egress`,
   the :rf.observe/derived-tree boundary). EP-0025 FAIL-OPEN: value-match is
   removed, so a RE-KEYED secret in a `:dom` / `:focus` sample ships RAW —
   classify its app-db PATH to redact it at the source. `start-recording!` /
   `watch-until` still refuse a `:dom` / `:focus` signal under the off-box
   gate when no frame resolves (no frame to project against), so `frame-id` is
   non-nil here whenever the projection runs."
  ([signal frame-id] (sample-one-signal signal frame-id nil))
  ([signal frame-id elide-opts]
  (try
    (cond
      (contains? signal :app-db)
      (-> (get-in (rf/app-db-value frame-id) (vec (:app-db signal)))
          (maybe-elide-sample frame-id elide-opts))

      (contains? signal :sub)
      (when frame-id
        (-> @(rf/subscribe frame-id (vec (:sub signal)))
            (maybe-elide-sample frame-id elide-opts)))

      (contains? signal :dom)
      (when-let [el (.querySelector js/document (:dom signal))]
        (-> (if-let [a (:attr signal)]
              (.getAttribute el (name a))
              (let [t (.-textContent el)]
                (when (string? t) t)))
            (maybe-redact-derived frame-id)))

      (contains? signal :focus)
      (when-let [el (and (exists? js/document) (.-activeElement js/document))]
        ;; A stable, EDN-able descriptor — comparing whole DOM nodes by
        ;; `=` is meaningless, so we project to the identity fields that
        ;; actually change as focus moves.
        (-> {:tag   (some-> (.-tagName el) str/lower-case)
             :id    (not-empty (.-id el))
             :class (not-empty (.-className el))
             :name  (not-empty (.getAttribute el "name"))
             :rf2-src (some-> (.getAttribute el "data-rf2-source-coord"))}
            (maybe-redact-derived frame-id)))

      :else
      {:rf.recording/error "unrecognised signal shape — expected one of :app-db :sub :dom :focus"})
    (catch :default e
      {:rf.recording/error (.-message e)}))))

(defn sample-signals
  "One-shot read of a signal-set against the operating frame — the pure,
   non-installing counterpart to a recording tick. `signals` is a vector
   of signal maps (see `sample-one-signal`). Returns
   `{:ok? true :t <ms> :sample {<signal-index> <value>}}`. The MCP
   `watch-until` op polls this server-side (like `tail-build`) so it can
   block on a predicate without installing a rAF loop. The keys of
   `:sample` are the signals' positional indices so the predicate can
   address `(get sample 0)` regardless of signal shape.

   `elide-opts` (the optional 3rd arg) carries the off-box
   egress posture for `:app-db` / `:sub` value sampling (gate-ON
   `:include-sensitive` opt-in). `watch-until` threads it from the MCP
   gate + per-call args; absent ⇒ fail-closed defaults via
   `sample-one-signal`.

   FAIL CLOSED: under the off-box gate a signal that needs
   frame policy (`:app-db` / `:sub` always; `:dom` / `:focus` because their
   derived values are PATH-projected against the frame's classification)
   cannot be sampled when `frame-id` is nil (ambiguous frame). Rather than
   sample against a nil frame — which would ship raw derived DOM / focus text
   — the
   one-shot returns an `:ambiguous-frame` refusal so `watch-until` surfaces
   a clear error instead of silently leaking. Under the trusted-local gate
   raw is the operator's choice, so a nil frame still samples (verbatim)."
  ([signals] (sample-signals signals (current-frame) nil))
  ([signals frame-id] (sample-signals signals frame-id nil))
  ([signals frame-id elide-opts]
   (let [gate-on?     (:allow-raw-state? @raw-state-config)
         needs-frame? (some #(or (contains? % :app-db) (contains? % :sub)
                                 (and (not gate-on?)
                                      (or (contains? % :dom) (contains? % :focus))))
                            (vec signals))]
     (if (and needs-frame? (nil? frame-id))
       (ambiguous-frame-error :watch-until)
       {:ok?    true
        :t      (js/Date.now)
        :sample (into {}
                      (map-indexed (fn [i s] [i (sample-one-signal s frame-id elide-opts)]))
                      (vec signals))}))))

(defn- recording-sampler-tick!
  "Run one sampler tick for recording `rid`: read every signal, append a
   change entry for any signal whose value differs structurally from its
   last-seen value, advance the frame counter, then evaluate the stop
   condition. Returns true when the recording should KEEP running, false
   when it should stop (the rAF driver reads this to self-cancel)."
  [rid]
  (let [rec (get @recordings rid)]
    (if (or (nil? rec) (not= :recording (:status rec)))
      false
      (let [{:keys [signals frame-id last-values frame-count started-at
                    stop max-entries elide-opts]} rec
            now      (js/Date.now)
            ;; Each `:app-db` / `:sub` sample is elided for
            ;; off-box egress before it lands in the change-log (which
            ;; `read-recording` ships to the model). `elide-opts` carries
            ;; the gate-ON `:include-sensitive` opt-in; absent ⇒ the
            ;; runtime's fail-closed gate default.
            samples  (mapv #(sample-one-signal % frame-id elide-opts) signals)
            ;; Per-signal change detection — structural `=` against the
            ;; last recorded value. The first tick records every signal
            ;; as a baseline (last-values starts empty for each index).
            changes  (keep-indexed
                       (fn [i v]
                         (when (not= v (get last-values i ::unset))
                           {:i i :signal (nth signals i) :value v
                            :t now :frame frame-count}))
                       samples)
            new-last (reduce (fn [m {:keys [i value]}] (assoc m i value))
                             last-values changes)
            ;; Stop predicates evaluate against the positional sample map
            ;; (same shape `sample-signals` returns) so a recording's stop
            ;; predicate and a watch-until predicate read identically.
            sample-map (into {} (map-indexed vector samples))
            elapsed    (- now started-at)
            n-changes  (+ (count (:entries rec)) (count changes))
            pred-fn    (:pred-fn stop)
            stop-hit?  (cond
                         (and (:ms stop) (>= elapsed (:ms stop)))         :ms
                         (and (:changes stop) (>= n-changes (:changes stop))) :changes
                         (and pred-fn (try (pred-fn sample-map)
                                           (catch :default _ false)))    :predicate
                         :else nil)]
        (swap! recordings update rid
               (fn [r]
                 (when r
                   (let [entries' (into (:entries r) changes)
                         ;; Drop-oldest ring cap — bounds memory on a
                         ;; forgotten recording. Trims from the FRONT.
                         capped   (let [n (count entries')]
                                    (if (> n max-entries)
                                      (subvec entries' (- n max-entries))
                                      entries'))]
                     (cond-> (assoc r
                                    :entries     capped
                                    :last-values new-last
                                    :frame-count (inc frame-count)
                                    :last-tick-at now)
                       stop-hit? (assoc :status :stopped
                                        :stopped-reason stop-hit?
                                        :stopped-at now))))))
        (not stop-hit?)))))

(defn- drive-recording!
  "Install the self-cancelling rAF (or next-tick) loop that runs the
   sampler each frame until the stop condition trips. Stores the cancel
   handle on the recording so `stop-recording!` can pre-empt it. The loop
   reads `recording-sampler-tick!`'s boolean to decide whether to
   reschedule — teardown is automatic and single-sourced."
  [rid]
  (let [raf?   (exists? js/requestAnimationFrame)
        schedule (fn [f]
                   (if raf?
                     (js/requestAnimationFrame f)
                     (interop/next-tick f)))]
    (letfn [(step [_]
              (when (recording-sampler-tick! rid)
                (let [h (schedule step)]
                  (swap! recordings update rid
                         (fn [r] (when r (assoc r :raf-handle h)))))))]
      (let [h (schedule step)]
        (swap! recordings update rid
               (fn [r] (when r (assoc r :raf-handle h))))))))

(defn start-recording!
  "Install a read-only observer over `signals` and begin recording each
   CHANGE with a timestamp. Returns `{:ok? true :recording-id <uuid>
   :signals [...] :stop {...} :frame <frame-id>}` immediately — the
   recording runs in the background while the human interacts; read the
   change-log back via `read-recording`.

   Opts:
     :signals  vector of signal maps (REQUIRED, non-empty). Each names
               one observable — see `sample-one-signal` for the shapes
               (:app-db / :sub / :dom / :focus).
     :stop     stop condition map. Recognised keys (first to trip wins):
                 :ms       wall-clock milliseconds (default 30000 when no
                           stop key is supplied at all).
                 :changes  total recorded change-entry count.
                 :pred-fn  a 1-arg fn over the positional sample map
                           `{<signal-index> <value>}`; truthy ⇒ stop. The
                           MCP layer compiles a data predicate into this.
     :frame    operating frame for :app-db / :sub signals. Defaults to the
               session's operating frame.
     :max-entries  drop-oldest ring cap on the change-log (default 2000).
     :elide-opts  off-box-egress walker opts threaded into
                  every `:app-db` / `:sub` sample so a declared-sensitive
                  slot is redacted before it lands in the change-log
                  `read-recording` ships to the model. The MCP `record`
                  tool passes the gate + per-call `:include-sensitive`
                  posture here; absent ⇒ the runtime's fail-closed gate
                  default (redact under `--allow-sensitive-reads OFF`).

   Refuses with `{:ok? false :reason :no-signals}` when `signals` is
   empty, and `{:ok? false :reason :ambiguous-frame}` when a signal needs
   frame policy but no frame can be resolved (multi-frame session, no
   selection) — read ops must not silently fall back to :rf/default.
   `:app-db` / `:sub` always need a frame (they read it); `:dom` / `:focus`
   need one under the off-box gate (their derived values are PATH-projected
   against the frame's classification, so the source-db must be pickable).
   Under the trusted-local gate (`--allow-sensitive-reads`) a
   `:dom` / `:focus`-only recording needs no frame (it ships raw)."
  [{:keys [signals stop frame max-entries elide-opts]}]
  (let [signals  (vec signals)
        gate-on? (:allow-raw-state? @raw-state-config)
        needs-frame? (some #(or (contains? % :app-db) (contains? % :sub)
                                ;; :dom / :focus are PATH-projected under the
                                ;; off-box gate, so they need a frame to
                                ;; source the classification.
                                (and (not gate-on?)
                                     (or (contains? % :dom) (contains? % :focus))))
                           signals)
        frame-id (current-frame frame)]
    (cond
      (empty? signals)
      {:ok? false :reason :no-signals
       :hint "pass a non-empty :signals vector, e.g. [{:focus true} {:dom \"#count\"} {:app-db [:cart :items]}]"}

      (and needs-frame? (nil? frame-id))
      (ambiguous-frame-error :record)

      :else
      (let [rid (str "rec-" (random-uuid))
            ;; Default the stop to a wall-clock window when the caller
            ;; named nothing — a recording with no stop is the forgotten-
            ;; observer footgun this op exists to kill.
            stop' (if (and (nil? (:ms stop)) (nil? (:changes stop)) (nil? (:pred-fn stop)))
                    (assoc stop :ms default-recording-stop-ms)
                    stop)
            rec  {:id          rid
                  :signals     signals
                  :frame-id    frame-id
                  :stop        stop'
                  :max-entries (or max-entries default-recording-max-entries)
                  ;; Carried through each tick so `:app-db` /
                  ;; `:sub` samples are elided for off-box egress before
                  ;; landing in the change-log.
                  :elide-opts  elide-opts
                  :status      :recording
                  :entries     []
                  :last-values {}
                  :frame-count 0
                  :started-at  (js/Date.now)
                  :raf-handle  nil}]
        (swap! recordings assoc rid rec)
        (drive-recording! rid)
        {:ok?          true
         :recording-id rid
         :signals      signals
         :frame        frame-id
         :stop         (dissoc stop' :pred-fn)}))))

(defn read-recording
  "Read back a recording's change-log. Returns
   `{:ok? true :recording-id <id> :status :recording|:stopped
     :stopped-reason <kw|nil> :frames-sampled <n> :count <n>
     :entries [{:i <signal-index> :signal {...} :value <v> :t <ms>
                :frame <frame-counter>} ...]}`.

   Each entry is one CHANGE: the moment signal `:i` took a new value.
   `:t` is the wall clock (ms); `:frame` is the rAF frame counter at the
   sample, so two signals that changed on the same paint share a `:frame`.

   Opts:
     :drain   when true, returns the buffered entries AND clears them from
              the recording (so the next read sees only subsequent
              changes) — the live-watch idiom: poll, consume, repeat. The
              recording keeps running (or stays stopped) either way.
     :stop    when true, tears the recording down after reading (a
              read-and-close in one round-trip).

   Unknown id ⇒ `{:ok? false :reason :no-such-recording}`."
  ([recording-id] (read-recording recording-id {}))
  ([recording-id {:keys [drain stop]}]
   (if-let [rec (get @recordings recording-id)]
     (let [entries (:entries rec)
           result  {:ok?            true
                    :recording-id   recording-id
                    :status         (:status rec)
                    :stopped-reason (:stopped-reason rec)
                    :frames-sampled (:frame-count rec)
                    :count          (count entries)
                    :entries        entries}]
       (when drain
         (swap! recordings update recording-id
                (fn [r] (when r (assoc r :entries [])))))
       (when stop
         (some-> (get @recordings recording-id) :raf-handle
                 (#(when (exists? js/cancelAnimationFrame)
                     (js/cancelAnimationFrame %))))
         (swap! recordings dissoc recording-id))
       result)
     {:ok? false :reason :no-such-recording :recording-id recording-id})))

(defn stop-recording!
  "Tear down recording `recording-id` — cancel its rAF loop and drop it
   from the registry. Idempotent: an unknown / already-stopped id returns
   `{:ok? true :existed? false}`. The recording's change-log is discarded;
   read it with `read-recording` BEFORE stopping if you still need it."
  [recording-id]
  (let [rec (get @recordings recording-id)]
    (when-let [h (:raf-handle rec)]
      (when (exists? js/cancelAnimationFrame)
        (js/cancelAnimationFrame h)))
    (swap! recordings dissoc recording-id)
    {:ok? true :recording-id recording-id :existed? (some? rec)}))

(defn recording-info
  "List active / stopped recordings — diagnostic counterpart to
   `subscription-info`. Returns `{:ok? true :recordings [{:id :status
   :signals :count :frames-sampled :frame :started-at :stopped-reason}]}`.
   Does not drain or stop."
  []
  {:ok? true
   :recordings (mapv (fn [[rid rec]]
                       {:id             rid
                        :status         (:status rec)
                        :signals        (:signals rec)
                        :count          (count (:entries rec))
                        :frames-sampled (:frame-count rec)
                        :frame          (:frame-id rec)
                        :started-at     (:started-at rec)
                        :stopped-reason (:stopped-reason rec)})
                     @recordings)})

;; ---------------------------------------------------------------------------
;; Watch predicate matching
;; ---------------------------------------------------------------------------
;;
;; Matches against re-frame2's :rf/epoch-record shape — :event-id and
;; :trigger-event are top-level slots; :sub-runs / :renders / :effects
;; are pre-projected; the trace-events vector carries everything else.

(defn epoch-elapsed-ms
  "Compute the handler's wall-clock elapsed-ms for an epoch by pairing
   the cascade's `:rf.event/run-start` and `:rf.event/run-end` trace events
   on `:time` (the host-clock timestamp every trace event carries per
   Spec 009 §Trace event shape).

   The epoch record itself has no top-level timing slot — derivation
   happens here. Multiple run-start/run-end pairs can appear inside a
   single epoch when a handler synchronously dispatches further events;
   we span from the FIRST run-start to the LAST run-end so the value
   answers 'how long did this cascade's handler-chain hold the
   thread?', which is the intuition `--timing-ms` users have from the
   bash shim.

   Returns nil when neither bracket is present (degenerate cascades, or
   epochs whose `:trace-events` slot was elided for ring-buffer age —
   see Spec-Schemas §`:rf/epoch-record`)."
  [{:keys [trace-events]}]
  (let [run-event? (fn [op ev]
                     (and (= :rf.event (:op-type ev))
                          (= op (:operation ev))))
        first-time (some (fn [ev] (when (run-event? :rf.event/run-start ev) (:time ev))) trace-events)
        last-time  (reduce (fn [acc ev]
                             (if (run-event? :rf.event/run-end ev)
                               (let [t (:time ev)] (if (and (number? t) (or (nil? acc) (> t acc))) t acc))
                               acc))
                           nil
                           trace-events)]
    (when (and (number? first-time) (number? last-time) (>= last-time first-time))
      (- last-time first-time))))

(defn ^:private parse-timing-pred
  "Parse a `:timing-ms` predicate value into a one-arg matcher fn.

   Accepts:
     - a number `N` — sugar for `>= N` (the common 'slow events'
       intuition: `100` ≡ '100 ms or slower').
     - a string `\">N\"`, `\">=N\"`, `\"<N\"`, `\"<=N\"`, `\"=N\"` —
       comparison against the parsed numeric threshold.

   Returns `nil` for unparseable inputs; callers treat nil as
   'predicate absent' so a malformed filter doesn't accidentally
   match everything."
  [v]
  (cond
    (number? v)
    (fn [ms] (and (number? ms) (>= ms v)))

    (string? v)
    (let [m (re-matches #"\s*(>=|<=|>|<|=)?\s*(-?\d+(?:\.\d+)?)\s*" v)]
      (when m
        (let [op (or (nth m 1) ">=")
              n  (js/parseFloat (nth m 2))]
          (when-not (js/isNaN n)
            (case op
              ">"  (fn [ms] (and (number? ms) (> ms n)))
              ">=" (fn [ms] (and (number? ms) (>= ms n)))
              "<"  (fn [ms] (and (number? ms) (< ms n)))
              "<=" (fn [ms] (and (number? ms) (<= ms n)))
              "="  (fn [ms] (and (number? ms) (= ms n)))
              nil)))))))

(defn epoch-matches?
  "Test an epoch record against a predicate map built from
   `watch-epochs.sh` CLI args.

   Recognised keys: :event-id, :event-id-prefix, :effects (matches
   :fx-id in the projection), :touches-path (anywhere in db-before /
   db-after), :sub-ran (matches :sub-id or first of :query-v),
   :render (matches :render-key as a string), :origin (matches
   :rf.event/origin in :rf.event/dispatched trace events), :frame, :timing-ms.

   `:timing-ms` — server-side timing filter. Accepts a
   number (sugar for `>= N`) or a comparison string (`\">100\"`,
   `\"<=50\"`, `\">=100\"`, `\"<200\"`, `\"=42\"`). Compares against
   the epoch's wall-clock elapsed-ms derived from the cascade's
   `:rf.event/run-start` / `:rf.event/run-end` trace pair (see
   `epoch-elapsed-ms`). Filtering rides server-side so the wire
   payload shrinks before bytes cross the boundary — matters for the
   'alert me on slow events' recipe under streaming subscriptions.

   Prefix matching uses `str` on both sides so `:cart` matches
   `:cart/apply-coupon`."
  [pred {:keys [event-id trigger-event sub-runs renders effects
                trace-events frame db-before db-after] :as epoch}]
  (let [{p-eid    :event-id
         p-prefix :event-id-prefix
         p-fx     :effects
         p-path   :touches-path
         p-sub    :sub-ran
         p-render :render
         p-origin :origin
         p-frame  :frame
         p-timing :timing-ms} pred
        timing-fn (when (some? p-timing) (parse-timing-pred p-timing))]
    (boolean
     (and
      (if p-eid    (= p-eid event-id) true)
      (if p-prefix (some-> event-id str (str/starts-with? (str p-prefix))) true)
      (if p-fx     (some #(= p-fx (:fx-id %)) effects) true)
      (if p-path   (or (some? (get-in db-before p-path))
                       (some? (get-in db-after p-path)))
                   true)
      (if p-sub    (some #(or (= p-sub (:sub-id %))
                              (= p-sub (first (:query-v %))))
                         sub-runs) true)
      (if p-render (some #(= p-render (str (:render-key %))) renders) true)
      (if p-origin (some (fn [t] (and (= :rf.event/dispatched (:operation t))
                                      (= p-origin (get-in t [:tags :rf.event/origin]))))
                         trace-events)
                   true)
      (if p-frame  (= p-frame frame) true)
      (if timing-fn (timing-fn (epoch-elapsed-ms epoch)) true)))))

;; ---------------------------------------------------------------------------
;; Dispatch-and-collect
;; ---------------------------------------------------------------------------

(defn dispatch-and-collect
  "Synchronously dispatch (origin :pair) and return the resulting
   epoch record. Drain-settle is synchronous in re-frame2's
   `dispatch-sync`, so the new epoch is in the frame's history by the
   time this call returns.

   The response carries BOTH the full `:epoch` (the verbatim assembled
   record — the trace mode's full payload) AND a `:cascade-summary`
   slot. Callers that only need the headline 'what
   happened' read the compact summary; callers that need the raw
   trace-events / db-before / db-after pair read `:epoch`."
  ([event-v] (dispatch-and-collect event-v {}))
  ([event-v opts]
   (let [result (pair-dispatch-sync! event-v opts)]
     (if (:ok? result)
       (assoc result :epoch (epoch-by-id (:epoch-id result)
                                         (:frame result)))
       result))))

;; ---------------------------------------------------------------------------
;; Dispatch-and-settle
;; ---------------------------------------------------------------------------
;;
;; Closes the observe→drive loop SYNCHRONOUSLY in ONE call: dispatch →
;; flush renders → return the SETTLED epoch (the one carrying
;; `:rf.view/render` + `:rf.view/unmounted` entries). This KILLS the
;; `dispatch + setTimeout + flush! + re-read` dance, the rAF / tab-focus
;; dependence, and the "guess `reagent.core/flush!` → null" trap.
;;
;; Why a distinct fn from `dispatch-and-collect` / the `:await-render`
;; path:
;;
;;   - `dispatch-and-collect` returns the epoch the cascade recorded —
;;     but renders fire at React COMMIT time, AFTER the cascade settles
;;     (Spec 009 §post-settle render attribution). So the epoch it reads
;;     has the cascade's effects but NOT yet the view renders/unmounts;
;;     those land a tick later and re-fan into the record. Reading once,
;;     immediately, misses them.
;;
;;   - The `:await-render` path flushes via
;;     `interop/after-render` — the rAF-scheduled, post-commit /
;;     pre-paint hook. It is ASYNC (returns a Promise the server awaits
;;     through the mailbox), and on a backgrounded / unfocused tab the
;;     underlying React lane commit is rAF-throttled, so it can stall.
;;
;;   - `dispatch-and-settle!` flushes via the substrate adapter's
;;     `flush-render!`: React `flushSync` for the React-shaped
;;     substrates, `reagent.core/flush` for the ratom family. NOT
;;     rAF-scheduled, so it commits even headless / backgrounded and is
;;     fully SYNCHRONOUS — no Promise, no mailbox. ZERO substrate
;;     hardcoding: `adapter/flush-render!` resolves the INSTALLED adapter
;;     and routes to its native impl (re-frame2 knows its own registered
;;     adapter).
;;
;; The render/unmount emits fire SYNCHRONOUSLY inside `flush-render!`'s
;; `flushSync` extent (CLJS is single-threaded), so by the time it
;; returns the framework has back-filled them into the causing epoch and
;; re-fanned the record to listeners (Spec 009 §post-settle render /
;; sub-run / unmount back-fill; `:epoch/record-render!` /
;; `:epoch/record-unmount!`). We then re-read the epoch by id — it now
;; carries the renders in `:renders` and the unmounts in `:trace-events`.
;;
;; SCOPE (per the bead): "settle" = the SYNCHRONOUS cascade + render
;; flush. Async fx (http / `:dispatch-later` / timers) stay observed via
;; `watch-epochs` — `flush-render!` does NOT and cannot settle those.

(defn- render-trace-events
  "The `:rf.view/render` / `:rf.view/rendered` + `:rf.view/unmounted`
  events folded into an epoch's `:trace-events` — the raw view-lifecycle
  signal `dispatch-and-settle!` exists to surface. Returns a vector
  (possibly empty); nil-safe on a record without `:trace-events`."
  [epoch]
  (->> (:trace-events epoch)
       (filterv (fn [ev]
                  (contains? #{:rf.view/render :rf.view/rendered
                               :rf.view/rendered-cap-reached :rf.view/unmounted}
                             (:operation ev))))))

(defn dispatch-and-settle!
  "Dispatch (origin :pair) and SYNCHRONOUSLY settle the view layer, then
   return the assembled epoch INCLUDING the view-lifecycle signal.
   ONE call = dispatch → render → complete epoch.

   Steps:
     1. `dispatch-sync` — run the cascade; the epoch records its db-diff,
        effects, sub-runs (`dispatch-and-collect` shape).
     2. `(adapter/flush-render!)` — SYNCHRONOUSLY commit the installed
        substrate's pending renders + unmounts (React `flushSync` /
        `reagent.core/flush`, resolved from the live adapter — no
        substrate name appears here). Their `:rf.view/render` /
        `:rf.view/unmounted` emits fire inside this synchronous extent and
        the framework back-fills them into the causing epoch, re-fanning
        the record to listeners.
     3. RE-READ the epoch by id — it now carries `:renders` (the
        view-render structured rows) and any `:rf.view/unmounted` in
        `:trace-events`.

   Returns the `dispatch-and-collect` shape PLUS:
     :settled?      true  — the render layer flushed synchronously.
     :epoch                — the SETTLED record (post-flush, post-backfill).
     :render-events        — the `:rf.view/render` + `:rf.view/unmounted`
                             trace events folded into the epoch (the
                             headline view-lifecycle signal). Empty vector
                             when nothing mounted/unmounted (e.g. a no-op
                             dispatch or a state change no view reads).
     :cascade-summary      — the compact projection (its `:renders` count
                             reflects the flushed renders).

   On a frame-untargetable / no-epoch dispatch the `pair-dispatch-sync!`
   `:ok? false` envelope rides through verbatim — NO flush is attempted
   (nothing settled).

   The `flush-render!` call is a no-op-safe contract fn: under the
   plain-atom / SSR adapters (no live React commit) it returns nil and
   `:settled?` still holds — there were no pending renders to flush. It
   raises `:rf.error/no-adapter-installed` only if called before
   `(rf/init! ...)`; we let that propagate (the dispatch itself would
   already have failed for an un-booted frame)."
  ([event-v] (dispatch-and-settle! event-v {}))
  ([event-v opts]
   (let [result (pair-dispatch-sync! event-v opts)]
     (if-not (:ok? result)
       result
       (let [;; Flush the INSTALLED adapter's pending renders synchronously.
             ;; Zero-arity form flushes already-pending work — the cascade
             ;; in step 1 invalidated the views; this commits them now.
             _        (adapter/flush-render!)
             ;; Re-read AFTER the flush: the post-settle render/unmount
             ;; back-fill (re-fanned to listeners) has updated the record.
             epoch    (epoch-by-id (:epoch-id result) (:frame result))]
         (-> result
             (assoc :settled?      true
                    :epoch         epoch
                    :render-events (render-trace-events epoch))
             ;; Refresh :cascade-summary from the SETTLED epoch so its
             ;; `:renders` count reflects the flushed renders (the
             ;; pre-flush summary attached by pair-dispatch-sync! counted
             ;; zero — renders hadn't committed yet).
             (cond-> epoch (assoc :cascade-summary (cascade-summary epoch)))))))))

;; ---------------------------------------------------------------------------
;; Coarse-grained snapshot — one round-trip per investigate-X workflow.
;; ---------------------------------------------------------------------------
;;
;; Many investigate-X tasks chain 5-10 reads — each one a fresh nREPL
;; round-trip plus Claude-think latency. The runtime-side composer below
;; assembles every per-frame slice (:app-db, :sub-cache, :machines, :epochs,
;; :traces) in one CLJS eval, so the MCP `snapshot` op is a single bencode
;; round-trip producing one map keyed by frame-id.
;;
;; Each slice delegates to the existing per-slice reader — no parallel
;; reimplementation. The composer just routes by `:include` keys.
;;
;; Wire-boundary wrapping. `snapshot-state` here returns the
;; full slice values; the MCP server's `snapshot` tool then wraps the
;; `:app-db` slice with path-slicing + lazy-summary before crossing the
;; wire. The default mode at the wire is `:summary` — the slice value
;; is replaced with a `{:rf.mcp/summary {:type :map :keys [...] :count
;; ... :bytes ~...}}` marker carrying the top-level shape only. Callers
;; pass `:path [...]` to receive `(get-in db path)` (`:mode
;; :path-sliced`); root `:path []` opts back into the full slice.
;; Out-of-range paths surface
;; per-frame in a `:path-not-found` map with `:deepest-valid-prefix`
;; so the agent can re-aim. The `get-path` tool exposes the same
;; targeted-read primitive directly — `:exists?` distinguishes a path
;; that legitimately points at `nil` from one that doesn't resolve.

(def ^:private all-snapshot-slices
  [:app-db :sub-cache :machines :epochs :traces])

(defn- snapshot-frame-slice
  "Compute one slice for one frame-id. Delegates to the existing per-slice
   readers."
  [frame-id slice]
  (case slice
    :app-db     (rf/app-db-value frame-id)
    :sub-cache  (subs-tooling/sub-cache-snapshot frame-id)
    ;; The global machine-id list is registrar-level (not per-frame).
    ;; Per Spec 005 each frame holds its own machine
    ;; snapshots at [:rf.runtime/machines :snapshots machine-id] in the
    ;; durable RUNTIME-DB partition (read via `rf/runtime-db-value`, NOT
    ;; `rf/app-db-value`), so the per-frame
    ;; slice returns {:ids [...] :state {machine-id snapshot}}.
    :machines   (let [ids (vec (machines/machines))
                      state (or (get-in (rf/runtime-db-value frame-id)
                                        [:rf.runtime/machines :snapshots])
                                {})]
                  {:ids ids :state state})
    :epochs     (vec (rf/epoch-history frame-id))
    ;; The trace ring is per-frame and cascade-bundle-shaped: this slot
    ;; delivers bundles (the storage unit), matching the cascade-bundle
    ;; wire format emitted by the streaming subscribe surface (Tool-Pair
    ;; §Reading the per-frame trace ring + Tool-Pair §`watch-epochs` /
    ;; `trace-window` consumer shape).
    :traces     (vec (trace-tooling/trace-buffer frame-id))
    nil))

(defn- snapshot-frame
  "Assemble the requested slices for one frame-id."
  [frame-id slices]
  (reduce (fn [m slice]
            (assoc m slice (snapshot-frame-slice frame-id slice)))
          {}
          slices))

(defn snapshot-state
  "Coarse-grained per-frame state read. Returns one map keyed by
   frame-id whose values are slice maps.

   Opts:
     :frames    Which frames to snapshot. One of:
                  :app  — the APP frames only (default): every registered
                          frame with reserved `:rf/*` TOOL frames removed.
                          `:rf/default` is an app frame and
                          is retained (see `reserved-tool-frame?`). This is
                          the first-read default so a snapshot doesn't
                          OVERFLOW on Xray / Story / SSR tool-frame state.
                  :all  — EVERY registered frame, INCLUDING `:rf/*` tool
                          frames. The explicit opt-in to tool-frame state.
                  [...] — an explicit vector of frame-ids (honoured
                          verbatim; naming a tool frame opts into it).
     :include   vector of slice keywords. Defaults to
                [:app-db :sub-cache :machines :epochs :traces].
                Pass a subset to skip slices.

   Example return shape:

     {:rf/default {:app-db {...}
                   :sub-cache {[:cart/total] {:value 42 :ref-count 2}}
                   :machines {:ids [:auth] :state {:auth {...}}}
                   :epochs [{...}{...}]
                   :traces [{...}{...}]}
      :stories    {...}}

   Routes through existing per-slice readers — no parallel implementation.
   Side effects: installs the trace + epoch listeners (idempotent via
   `health`).

   Note: this runtime-side form returns the *full* `:app-db` slice for
   every frame. The MCP `snapshot` tool wraps this output with
   path-slicing + lazy-summary at the wire boundary — see
   the section header above for the modes. The wrapping is wire-side
   only; direct callers of this CLJS form see slices verbatim."
  ([] (snapshot-state {}))
  ([{:keys [frames include]
     :or   {frames  :app
            include all-snapshot-slices}}]
   (install-last-click-capture!)
   (ensure-trace-listener!)
   (ensure-epoch-listener!)
   (let [fids       (cond
                      ;; The DEFAULT scope is the APP frames (reserved
                      ;; `:rf/*` tool frames excluded). `:all` is the
                      ;; explicit opt-in to tool-frame state.
                      (= :app frames)      (app-frame-ids)
                      (= :all frames)      (vec (rf/frame-ids))
                      (sequential? frames) (vec frames)
                      :else                (app-frame-ids))
         slices     (vec include)]
     (reduce (fn [m fid]
               (assoc m fid (snapshot-frame fid slices)))
             {}
             fids))))

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn debug-enabled?
  "Probe `re-frame.interop/debug-enabled?`. False in production builds;
   trace and epoch surfaces elide entirely when this is false."
  []
  (boolean interop/debug-enabled?))

(defn health
  "One-call summary of the runtime's view of the world. Used by
   `discover-app.sh` to confirm the environment is healthy.

   Side effects: installs the last-click capture listener; registers
   the trace and epoch listeners. All idempotent."
  []
  (install-last-click-capture!)
  (ensure-trace-listener!)
  (ensure-epoch-listener!)
  (let [fids     (rf/frame-ids)
        app-fids (app-frame-ids)]
    {:ok?                       true
     :session-id                session-id
     ;; The browser half of the freshness token rides on
     ;; `health` so `discover-app` surfaces liveness in its very first
     ;; call. `:runtime-instance-id` mirrors `:session-id` (carried under
     ;; both names: `:session-id` is the sentinel slot, the
     ;; freshness vocabulary names it `:runtime-instance-id`);
     ;; `:runtime-loaded-at` is the stale-build cross-check input.
     :runtime-instance-id       session-id
     :runtime-loaded-at         loaded-at
     :read-at                   (js/Date.now)
     :debug-enabled?            (debug-enabled?)
     :coord-annotation-enabled? (coord-annotation-enabled?)
     :last-click-capture?       true
     :frames                    (vec fids)
     ;; The reserved-frame-aware view: registered frames
     ;; with `:rf/*` TOOL frames (Xray's `:rf/xray`, SSR slots, …)
     ;; removed. `:rf/default` is retained (it is an app frame). When this
     ;; holds exactly one id while `:frames` holds more, the session is
     ;; single-app-plus-tool-frame and resolution auto-selects the lone app
     ;; frame — see `:ambiguous-frame?` below.
     :app-frames                app-fids
     :selected-frame            @selected-frame
     :operating-frame           (current-frame)
     ;; Ambiguity counts APP frames, not raw frames. A
     ;; single-app session that ALSO carries an Xray (or other `:rf/*`
     ;; tool) frame has exactly one app frame, so it is NOT ambiguous and
     ;; pays no `frames/select` tax. Genuinely multi-app sessions (two-plus
     ;; non-tool frames) stay ambiguous until the session pins one.
     :ambiguous-frame?          (and (> (count app-fids) 1)
                                     (nil? @selected-frame))
     :epoch-history-depth       (try
                                  (let [requiring (resolve 're-frame.epoch/current-config)]
                                    (when requiring (:depth (requiring))))
                                  (catch :default _ nil))
     :epoch-counts              (into {} (map (fn [fid]
                                                [fid (count (rf/epoch-history fid))])
                                              fids))
     :pair-epoch-count          (count @pair-epoch-ids)}))

;; ---------------------------------------------------------------------------
;; App-shape orientation summary
;; ---------------------------------------------------------------------------

(def ^:private orient-registrar-kinds
  "The registrar kinds whose COUNTS the orientation summary reports — the
   closed registrar set (mirrors `handler-meta`'s `registrar-kinds`),
   including `:interceptor` (the registered-interceptor kind — `reg-interceptor`
   stores a `{:before}` / `{:after}` / `{:factory}` descriptor under it; event/
   frame `:interceptors` chains carry REFERENCES into this kind) so an
   app using `reg-interceptor` surfaces its registered-interceptor count on
   first contact, and the three resources-artefact kinds (`:resource` /
   `:mutation` / `:resource-scope`) so a resources-heavy
   app's orientation summary names its cached-read / write / scope-resolver
   counts (zero on an app that uses none — `registrar-count` is defensively
   zero on an empty registrar). `:event` / `:sub` / `:fx` additionally
   surface their full sorted id vectors (the most navigable surfaces for
   'what can I drive / read'); the rest contribute counts only so the
   summary stays compact and under the wire cap. Drill via `list-handlers
   {kind ...}` for the full ids of any kind."
  [:event :sub :fx :cofx :interceptor :view :frame :route :flow :head
   :error-projector :resource :mutation :resource-scope])

(defn- registrar-count
  "Count of registered ids under `kind`, defensively zero on a registrar
   that doesn't exist (an app that registered nothing of that kind)."
  [kind]
  (count (try (keys (rf/registrations kind)) (catch :default _ nil))))

(defn- process-registry-view
  "The PROCESS-WIDE registry view — counts for every registrar kind plus the
   full sorted id vectors for the three most navigable kinds, off the
   process-global registrar. The fallback orient uses when no single operating
   frame resolves (a multi-frame ambiguous session, or a core whose operating
   frame carries no sealed image generation). `:basis :process` labels which
   registrar these counts came from."
  []
  {:basis  :process
   :counts (into {} (map (fn [k] [k (registrar-count k)])) orient-registrar-kinds)
   :events (registrar-list :event)
   :subs   (registrar-list :sub)
   :fx     (registrar-list :fx)})

(defn- frame-registry-view
  "The OPERATING-FRAME registry view — counts + the high-value id vectors
   resolved through `frame-id`'s OWN sealed image generation rather than the
   process-wide registrar. The same `(kind, id)` can resolve
   differently per frame, so the SELECTED universe a frame actually runs is
   the registry an agent driving that frame should see — not the union of
   every namespace's registrations the flat registrar holds.

   Routes through the PUBLIC `rf/frame-generation` read: the
   resolver's `[kind id]` keys give the per-kind counts and the navigable id
   vectors directly off the generation. `:basis :frame` + `:frame` label
   which frame these counts resolved through. Returns nil when the frame does
   not resolve to a live generation (caller falls back to the process view)."
  [frame-id]
  (when frame-id
    (try
      (let [gen      (rf/frame-generation frame-id)
            resolver (:rf.gen/resolver gen)
            by-kind  (reduce-kv
                       (fn [acc [k id] _d] (update acc k (fnil conj #{}) id))
                       {}
                       resolver)
            ids-of   (fn [k] (vec (sort (get by-kind k []))))]
        {:basis  :frame
         :frame  frame-id
         :counts (into {} (map (fn [k] [k (count (get by-kind k []))]))
                       orient-registrar-kinds)
         :events (ids-of :event)
         :subs   (ids-of :sub)
         :fx     (ids-of :fx)})
      ;; A frame that does not carry a generation (`:rf.error/frame-no-
      ;; generation`) is an imageless frame with no sealed image. Fall back
      ;; to the process view rather than fail the whole orientation.
      (catch :default _ nil))))

(defn orient
  "App-shape orientation summary — answer 'what is this app
   and what can I drive?' in ONE round-trip.

   When an agent connects to an UNFAMILIAR app, orienting otherwise takes
   several calls (discover-app + snapshot top-keys + list-handlers +
   list-subscriptions + machines/routes). `orient` composes those into a
   single compact map by reusing the existing introspection surfaces — no
   reinvention:

     :liveness        the `health` liveness fact (debug-enabled? +
                      frame counts) — the freshness check stays on
                      discover-app; orient names enough to know the read
                      is trustworthy.
     :frames          {:all [...] :app [...] :operating <id>} — the
                      `frames-list` view (reserved `:rf/*` tool frames
                      split out via `app-frame-ids`). `:all` / `:app` /
                      `:operating` are the PUBLIC frame addressing surface
                      (the image -> frame -> event stream model).
     :app-db-top-keys {<app-frame-id> [<top-level key> ...]} — the
                      top-level app-db keys per APP frame (the cheap
                      'what state shape is this' read; drill with
                      `get-path` / `snapshot`). Tool frames are excluded
                      so the summary doesn't
                      overflow on Xray/SSR inspection state.
     :registry        {:basis :frame|:process :frame <id>?
                       :counts {<kind> N ...}
                       :events [...] :subs [...] :fx [...]} — registrar
                      COUNTS for every kind, plus the full sorted id
                      vectors for the three most navigable kinds. Drill
                      any other kind via `list-handlers {kind ...}`.
                      RE-BASED on the OPERATING FRAME's resolved image
                      generation when a single operating frame resolves —
                      the SELECTED universe that
                      frame actually runs, not the process-wide registrar
                      union (the same `(kind, id)` can resolve differently
                      per frame). `:basis :frame` + `:frame <id>` name the
                      resolution; falls back to `:basis :process` (the
                      process-wide registrar counts) in a multi-frame
                      ambiguous session or against an operating frame that
                      carries no sealed image generation.
     :machines        the registered machine ids (`rf/machines`).

   Compact + summarized by design (respect the wire cap): counts + the
   high-value id vectors + per-frame top-keys, NOT the full app-db. The
   MCP `orient` op (or `discover-app :orient true`) routes here.

   Side effects: installs the trace/epoch/last-click listeners via
   `health` (idempotent)."
  []
  (let [h        (health)
        app-fids (app-frame-ids)
        op-frame (:operating-frame h)
        ;; Re-base the registry on the OPERATING FRAME's resolved
        ;; image generation when one resolves; fall back to the process-wide
        ;; registrar counts otherwise (ambiguous multi-frame session, or an
        ;; operating frame with no sealed image generation).
        registry (or (frame-registry-view op-frame) (process-registry-view))]
    {:ok?      true
     :liveness {:debug-enabled?      (:debug-enabled? h)
                :frame-count         (count (:frames h))
                :app-frame-count     (count app-fids)
                :ambiguous-frame?    (:ambiguous-frame? h)
                :runtime-instance-id (:runtime-instance-id h)}
     :frames   {:all             (:frames h)
                :app             app-fids
                :operating       (:operating-frame h)}
     :app-db-top-keys
     (into {}
           (map (fn [fid]
                  [fid (let [db (rf/app-db-value fid)]
                         (when (map? db) (vec (sort-by pr-str (keys db)))))]))
           app-fids)
     :registry registry
     :machines (vec (machines/machines))}))
