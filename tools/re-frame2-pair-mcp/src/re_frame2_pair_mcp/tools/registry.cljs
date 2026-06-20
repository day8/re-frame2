(ns re-frame2-pair-mcp.tools.registry
  "Single source of truth for the re-frame2-pair-mcp tool catalogue (rf2-47g8l).

  ## Why one registry

  Before this ns landed, three places enumerated the tool list with no
  compile-time validation they agreed:

  - `tool-descriptors` in `descriptors.cljs` — the `tools/list` surface.
  - `dispatch-tool*` in `tools.cljs` — the `tools/call` dispatcher.
  - `cacheable-tools` in `cache.cljs` — the per-tool cache opt-in.

  A new tool added to the dispatcher without a descriptor silently
  vanished from `tools/list`; a tool added to the descriptor without a
  `cacheable-tools` entry silently no-op'd the `:cache` knob. The drift
  was always *possible* and only caught (at best) by hand-review.

  ## What's here

  One ordered vector — `tools` — whose entries bundle every fact about
  a tool that any consumer needs:

  ```clojure
  {:name        \"snapshot\"          ;; MCP wire name
   :handler     snapshot-handler      ;; (fn [conn args extra] => Promise)
   :cacheable?  true                  ;; per-session response cache opt-in
   :descriptor  {...}}                ;; tools/list inputSchema + doc
  ```

  Generators emit the three downstream views from this one source:

  - `tool-descriptors` — `(mapv :descriptor tools)` (consumed by
    `descriptors.cljs` for the wire surface).
  - `handler-for`     — `{name handler}` lookup (consumed by
    `tools.cljs/dispatch-tool*`).
  - `cacheable?`      — predicate over registered names (consumed by
    `cache.cljs` and `descriptors-knobs.cljs`).

  ## Handler arity convention

  Every registered handler is a 3-arity fn `(fn [conn args extra])`.
  Only the streaming `subscribe` tool actually consults `extra` (the
  MCP `signal` + `sendNotification` + `_meta.progressToken` payload);
  the rest accept it for shape uniformity and ignore it. This is the
  single shape the dispatcher invokes — there is no second arity.

  Cross-MCP note: story-mcp uses a different (1-arity) handler shape
  because its JVM-side single-process deploy has no nREPL `conn` and
  no streaming tool. The divergence is deliberate and documented at
  `tools/mcp-base/spec/handler-arity.md`; a phase-2 unification awaits
  a third server instance and lands as a separate bead.

  ## Adding a new tool

  Land *one* map entry in the `tools` vector below — name, handler,
  cacheable flag, descriptor. The three downstream views update
  automatically; drift is structurally impossible. Per-tool ns provides
  the handler implementation as before.

  ## Layout

  This file owns the catalogue **data** only — handler implementations
  and descriptor knob splicers live in the per-tool / per-concern
  files. Registry is the index, not the implementation."
  (:require [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.dispatch :as dispatch]
            [re-frame2-pair-mcp.tools.dispatch-dry-run :as dispatch-dry-run]
            [re-frame2-pair-mcp.tools.restore-epoch :as restore-epoch]
            [re-frame2-pair-mcp.tools.replace-app-db :as replace-app-db]
            [re-frame2-pair-mcp.tools.trace-window :as trace-window]
            [re-frame2-pair-mcp.tools.watch-epochs :as watch-epochs]
            [re-frame2-pair-mcp.tools.tail-build :as tail-build]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.read-sub :as read-sub]
            [re-frame2-pair-mcp.tools.orient :as orient]
            [re-frame2-pair-mcp.tools.read-dom :as read-dom]
            [re-frame2-pair-mcp.tools.read-ui :as read-ui]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]
            [re-frame2-pair-mcp.tools.subscribe :as subscribe]
            [re-frame2-pair-mcp.tools.unsubscribe :as unsubscribe]
            [re-frame2-pair-mcp.tools.list-subscriptions :as list-subscriptions]
            [re-frame2-pair-mcp.tools.list-streams :as list-streams]
            [re-frame2-pair-mcp.tools.get-stream-controls :as get-stream-controls]
            [re-frame2-pair-mcp.tools.handler-meta :as handler-meta]
            [re-frame2-pair-mcp.tools.describe-image :as describe-image]
            [re-frame2-pair-mcp.tools.operating-frame :as operating-frame]
            [re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions :as get-re-frame2-pair-instructions]
            [re-frame2-pair-mcp.tools.descriptors-data :as data]))

;; ---------------------------------------------------------------------------
;; Handler shape — every registered handler is `(fn [conn args extra])`.
;; Per-tool fns that don't need `extra` (i.e. everything except `subscribe`)
;; are adapted via `ignoring-extra` so the dispatcher can call all
;; handlers uniformly.
;;
;; Handlers in the registry are LATE-BINDING — they call the per-tool fn
;; on each invocation rather than capturing a direct reference at load
;; time. The `invoke-test` suite (rf2-nogok) stubs per-tool fns by
;; `set!`-ing their vars and expects subsequent dispatches to hit the
;; replacement. A captured reference would freeze the original and
;; break the seam. `ignoring-extra` takes a NAMESPACE-QUALIFIED var
;; (not a value) so the deref happens per-call — preserving the seam.
;; ---------------------------------------------------------------------------

(defn- ignoring-extra
  "Adapt a 2-arity per-tool fn `(fn [conn args])` into the registry's
  3-arity convention `(fn [conn args _extra])`. All but one of the
  registered tools ignore `extra` (only `subscribe` consults it);
  this adapter collapses the verbatim `(fn [conn args _extra]
  (per-tool-fn conn args))` boilerplate at the call sites.

  Callers wrap the per-tool fn in a `#(per-tool-fn %1 %2)` literal
  so the namespaced-symbol lookup happens per-call. That preserves
  the test seam (rf2-nogok): `(set! snapshot/snapshot-tool ...)`
  updates the namespace's JS slot and the next dispatch finds the
  replacement."
  [f]
  (fn [conn args _extra] (f conn args)))

;; ---------------------------------------------------------------------------
;; The catalogue — one entry per tool. ORDER matters: `tool-descriptors`
;; preserves it for the `tools/list` wire surface so AI hosts see a
;; stable listing (discover-app first, mega-ops in the middle, streaming
;; tools last). Don't shuffle without checking the `typical_tokens_test`
;; and `list_subscriptions_test` order-sensitive expectations.
;; ---------------------------------------------------------------------------

(def tools
  "The tool catalogue (canonical count: spec/003-Tool-Catalogue.md).
  Single source of truth for the `tools/list` descriptors, the
  `tools/call` dispatcher, and the per-tool cache opt-in. See ns
  docstring for the entry shape.

  Each `:handler` is a thin late-binding wrapper around the per-tool
  fn — `ignoring-extra` for every tool that ignores `extra`, or an
  inline `(fn [conn args extra] ...)` for `subscribe` (which
  uses `extra` for its progress-callback plumbing). Both shapes
  resolve the underlying fn per-call so test seams that `set!` the
  var (rf2-nogok) take effect on the next dispatch."
  [{:name       "discover-app"
    :handler    (ignoring-extra #(discover-app/discover-app %1 %2))
    :cacheable? true
    :descriptor data/discover-app}
   {:name       "orient"
    :handler    (ignoring-extra #(orient/orient-tool %1 %2))
    ;; App-shape orientation summary (rf2-3bu3d.8) — registrar counts/ids +
    ;; per-app-frame app-db top-keys + machines, composed from the existing
    ;; introspection surfaces. A pure function of the frame registry + app-db
    ;; state, idempotent across same-state calls — cacheable like the other
    ;; read tools.
    :cacheable? true
    :descriptor data/orient}
   {:name       "eval-cljs"
    :handler    (ignoring-extra #(eval-cljs/eval-cljs-tool %1 %2))
    :cacheable? false
    :descriptor data/eval-cljs}
   {:name       "dispatch"
    :handler    (ignoring-extra #(dispatch/dispatch-tool %1 %2))
    :cacheable? false
    :descriptor data/dispatch}
   {:name       "dispatch-dry-run"
    :handler    (ignoring-extra #(dispatch-dry-run/dispatch-dry-run-tool %1 %2))
    :cacheable? false
    :descriptor data/dispatch-dry-run}
   {:name       "restore-epoch"
    :handler    (ignoring-extra #(restore-epoch/restore-epoch-tool %1 %2))
    :cacheable? false
    :descriptor data/restore-epoch}
   {:name       "replace-app-db"
    :handler    (ignoring-extra #(replace-app-db/replace-app-db-tool %1 %2))
    :cacheable? false
    :descriptor data/replace-app-db}
   {:name       "trace-window"
    :handler    (ignoring-extra #(trace-window/trace-window-tool %1 %2))
    :cacheable? true
    :descriptor data/trace-window}
   {:name       "watch-epochs"
    :handler    (ignoring-extra #(watch-epochs/watch-epochs-tool %1 %2))
    :cacheable? true
    :descriptor data/watch-epochs}
   {:name       "tail-build"
    :handler    (ignoring-extra #(tail-build/tail-build-tool %1 %2))
    :cacheable? false
    :descriptor data/tail-build}
   {:name       "snapshot"
    :handler    (ignoring-extra #(snapshot/snapshot-tool %1 %2))
    :cacheable? true
    :descriptor data/snapshot}
   {:name       "get-path"
    :handler    (ignoring-extra #(get-path/get-path-tool %1 %2))
    :cacheable? true
    :descriptor data/get-path}
   {:name       "read-sub"
    :handler    (ignoring-extra #(read-sub/read-sub-tool %1 %2))
    ;; Validated one-shot subscription read (rf2-3bu3d.7). A function of
    ;; frame state (the sub's deref) — idempotent across same-state calls,
    ;; cacheable like get-path / snapshot. The precheck-hash short-circuit
    ;; keys on (hash app-db); a sub value derives from app-db, so a cache
    ;; keyed on app-db is correct for the common case (the :cache default
    ;; is opt-in per-call, rf2-c4fmh).
    :cacheable? true
    :descriptor data/read-sub}
   {:name       "read-dom"
    :handler    (ignoring-extra #(read-dom/read-dom-tool %1 %2))
    ;; Read of live rendered DOM — a function of view-plane state, not
    ;; frame state. NOT cacheable: the precheck-hash short-circuit keys
    ;; on `(hash app-db)`, but the DOM can change without an app-db
    ;; mutation (a portal, a third-party widget, an async layout), so a
    ;; cache keyed on app-db would serve stale render reads. Same posture
    ;; as the action / streaming tools.
    :cacheable? false
    :descriptor data/read-dom}
   {:name       "read-ui"
    :handler    (ignoring-extra #(read-ui/read-ui-tool %1 %2))
    ;; The typed ui/read op (rf2-3bu3d.1) — rendered view content + the
    ;; producing entity, riding the view<->DOM map. Read of live rendered
    ;; DOM, same posture as read-dom: NOT cacheable — the precheck-hash
    ;; short-circuit keys on `(hash app-db)`, but the DOM (and the live
    ;; sub-cache the :subs-read slice reads) can change without an app-db
    ;; mutation, so a cache keyed on app-db would serve stale render reads.
    :cacheable? false
    :descriptor data/read-ui}
   {:name       "record"
    :handler    (ignoring-extra #(record/record-tool %1 %2))
    ;; Installs a live recorder on the runtime (mints a recording-id, runs
    ;; a background rAF sampler) — an action with an observable side-effect
    ;; on the recording registry, NOT a function of frame state. Same
    ;; non-cacheable posture as the streaming / action tools.
    :cacheable? false
    :descriptor data/record}
   {:name       "read-recording"
    :handler    (ignoring-extra #(record/read-recording-tool %1 %2))
    ;; Reads the volatile recording change-log (and may drain / stop it) —
    ;; the return value is the live recording buffer, not a pure function
    ;; of app-db, so a precheck-hash cache would serve stale logs.
    :cacheable? false
    :descriptor data/read-recording}
   {:name       "watch-until"
    :handler    (ignoring-extra #(watch-until/watch-until-tool %1 %2))
    ;; Blocks (server-polls) until a predicate holds — the result depends
    ;; on WHEN the condition trips, not on a single app-db snapshot.
    :cacheable? false
    :descriptor data/watch-until}
   {:name       "subscribe"
    :handler    (fn [conn args extra] (subscribe/subscribe-tool conn args extra))
    :cacheable? false
    :descriptor data/subscribe}
   {:name       "unsubscribe"
    :handler    (ignoring-extra #(unsubscribe/unsubscribe-tool %1 %2))
    :cacheable? false
    :descriptor data/unsubscribe}
   {:name       "list-subscriptions"
    :handler    (ignoring-extra #(list-subscriptions/list-subscriptions-tool %1 %2))
    :cacheable? true
    :descriptor data/list-subscriptions}
   {:name       "list-streams"
    :handler    (ignoring-extra #(list-streams/list-streams-tool %1 %2))
    :cacheable? false
    :descriptor data/list-streams}
   {:name       "get-stream-controls"
    :handler    (ignoring-extra #(get-stream-controls/get-stream-controls-tool %1 %2))
    ;; Pure read over the server's IN-PROCESS resource-control atoms
    ;; (rf2-a0kxsb). NOT cacheable: the active-stream / token-bucket /
    ;; abuse-window state is volatile session-local state, not a function
    ;; of app-db — a precheck-hash cache keyed on app-db would serve stale
    ;; control readings. Same posture as `list-streams` (volatile
    ;; streaming-registry read).
    :cacheable? false
    :descriptor data/get-stream-controls}
   {:name       "handler-meta"
    :handler    (ignoring-extra #(handler-meta/handler-meta-tool %1 %2))
    :cacheable? true
    :descriptor data/handler-meta}
   {:name       "list-handlers"
    :handler    (ignoring-extra #(handler-meta/list-handlers-tool %1 %2))
    :cacheable? true
    :descriptor data/list-handlers}
   {:name       "describe-image"
    :handler    (ignoring-extra #(describe-image/describe-image-tool %1 %2))
    ;; Pure read of a frame's resolved image generation (rf2-srobm0) — a
    ;; function of the frame's sealed generation, cacheable like the other
    ;; read tools. The generation is inert until reload-images!, so the
    ;; precheck-hash cache opt-in is safe.
    :cacheable? true
    :descriptor data/describe-image}
   {:name       "set-operating-frame"
    :handler    (ignoring-extra #(operating-frame/set-operating-frame-tool %1 %2))
    ;; Mutates the per-session frame pin — NOT cacheable (the result is
    ;; the outcome of a session-state write, not a function of frame
    ;; state). Same posture as the action tools.
    :cacheable? false
    :descriptor data/set-operating-frame}
   {:name       "reset-operating-frame"
    :handler    (ignoring-extra #(operating-frame/reset-operating-frame-tool %1 %2))
    ;; Clears the session pin — a session-state write. Not cacheable.
    :cacheable? false
    :descriptor data/reset-operating-frame}
   {:name       "get-operating-frame"
    :handler    (ignoring-extra #(operating-frame/get-operating-frame-tool %1 %2))
    ;; NOT cacheable — same posture as `get-stream-controls` / `list-streams`:
    ;; a read of volatile process/runtime state, not a function of frame
    ;; app-db. The resolved triple (`:frames` / `:app-frames` / `:selected`
    ;; / `:operating`) depends on the live frame registry plus the per-
    ;; session pin, and BOTH axes can move WITHOUT an app-db mutation and
    ;; WITHOUT a `set-operating-frame` / `reset-operating-frame` call —
    ;; e.g. a frame mounts/unmounts or a connected runtime reloads with a
    ;; different live frame set. The cache key is only
    ;; `[tool build (args->fingerprint args)]` (see `cache/cache-key`); it
    ;; cannot fold in the registry/pin, and the result-hash cache only
    ;; clears on an explicit operating-frame mutation (see
    ;; `operating-frame-mutating?` in `tools.cljs`). So a repeated
    ;; `get-operating-frame {cache true}` over byte-identical empty args
    ;; could serve a `:rf.mcp/cache-hit` marker telling the agent to reuse
    ;; stale frame-discovery data — hiding a newly ambiguous session or a
    ;; newly available app frame. Marking it non-cacheable keeps the read
    ;; always-fresh; the triple is cheap to recompute. If caching is ever
    ;; wanted here, add a runtime frame-registry/session-pin generation
    ;; token to the cache identity — do NOT key it on tool/build/args
    ;; alone.
    :cacheable? false
    :descriptor data/get-operating-frame}
   {:name       "get-re-frame2-pair-instructions"
    :handler    (ignoring-extra #(get-re-frame2-pair-instructions/get-re-frame2-pair-instructions-tool %1 %2))
    :cacheable? true
    :descriptor data/get-re-frame2-pair-instructions}])

;; ---------------------------------------------------------------------------
;; Derived views — generated once at load time from `tools`. Consumers
;; reach for these instead of redeclaring the names.
;; ---------------------------------------------------------------------------

(def tool-descriptors
  "Ordered vector of just the `:descriptor` payload from each entry.
  Consumed by `descriptors.cljs/tool-descriptors-js` (which composes
  the universal knob splicers over this) and by tests that pin the
  `tools/list` shape."
  (mapv :descriptor tools))

(def handler-for
  "`{tool-name handler}` lookup. The dispatcher in `tools.cljs` reaches
  in here by name; unknown names return `nil` and the dispatcher emits
  the `:unknown-tool` error envelope."
  (into {} (map (juxt :name :handler)) tools))

(def tool-names
  "Ordered vector of every registered tool name — the catalogue an agent
  sees via `tools/list`, in the same authoring order as `tools`. The
  dispatcher in `tools.cljs` folds this into the `:unknown-tool` error
  `:hint` so a model that typo'd a name (or called a removed alias) can
  see the live catalogue and recover without a round-trip."
  (mapv :name tools))

(def ^:private cacheable-set
  "Materialised set of tool names whose `:cacheable?` is truthy.
  Built once at load time so `cacheable?` is an O(1) membership test."
  (into #{} (comp (filter :cacheable?) (map :name)) tools))

(defn cacheable?
  "Predicate — should this tool consult the per-session response cache?

  True for read tools whose return value is a function of state
  (`snapshot`, `get-path`, `trace-window`, `watch-epochs`,
  `discover-app`, `list-subscriptions` — the last reads the live
  reactive sub-cache, a pure function of frame state, rf2-qicji) and
  for the inline `get-re-frame2-pair-instructions` onboarding text
  (which is a pure-data function with no state whatsoever — once is
  forever). False for action tools (`dispatch`, `eval-cljs`,
  `tail-build`), streaming tools (`subscribe`, `unsubscribe`,
  `list-streams`, `get-stream-controls`), and volatile runtime-state
  reads whose value can move without an app-db mutation —
  `get-operating-frame`, whose resolved triple is a function of the
  live frame registry plus the per-session pin (rf2-flm0iz). Their
  return value is the result of an action / a read of volatile
  process/runtime state, not frame app-db.

  Unknown names return false."
  [tool]
  (contains? cacheable-set tool))
