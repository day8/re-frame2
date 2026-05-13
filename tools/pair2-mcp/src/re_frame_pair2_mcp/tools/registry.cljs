(ns re-frame-pair2-mcp.tools.registry
  "Single source of truth for the pair2-mcp tool catalogue (rf2-47g8l).

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

  ## Adding a new tool

  Land *one* map entry in the `tools` vector below — name, handler,
  cacheable flag, descriptor. The three downstream views update
  automatically; drift is structurally impossible. Per-tool ns provides
  the handler implementation as before.

  ## Layout

  This file owns the catalogue **data** only — handler implementations
  and descriptor knob splicers live in the per-tool / per-concern
  files. Registry is the index, not the implementation."
  (:require [re-frame-pair2-mcp.tools.discover-app :as discover-app]
            [re-frame-pair2-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame-pair2-mcp.tools.dispatch :as dispatch]
            [re-frame-pair2-mcp.tools.trace-window :as trace-window]
            [re-frame-pair2-mcp.tools.watch-epochs :as watch-epochs]
            [re-frame-pair2-mcp.tools.tail-build :as tail-build]
            [re-frame-pair2-mcp.tools.snapshot :as snapshot]
            [re-frame-pair2-mcp.tools.get-path :as get-path]
            [re-frame-pair2-mcp.tools.subscribe :as subscribe]
            [re-frame-pair2-mcp.tools.unsubscribe :as unsubscribe]
            [re-frame-pair2-mcp.tools.subscription-info :as subscription-info]
            [re-frame-pair2-mcp.tools.descriptors-data :as data]))

;; ---------------------------------------------------------------------------
;; Handler shape — every registered handler is `(fn [conn args extra])`.
;; Per-tool fns that don't need `extra` (i.e. everything except `subscribe`)
;; are wrapped inline at registration so the dispatcher can call all
;; handlers uniformly.
;;
;; Handlers in the registry are LATE-BINDING — they call the per-tool fn
;; on each invocation rather than capturing a direct reference at load
;; time. The `invoke-test` suite (rf2-nogok) stubs per-tool fns by
;; `set!`-ing their vars and expects subsequent dispatches to hit the
;; replacement. A captured reference would freeze the original and
;; break the seam.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; The catalogue — one entry per tool. ORDER matters: `tool-descriptors`
;; preserves it for the `tools/list` wire surface so AI hosts see a
;; stable listing (discover-app first, mega-ops in the middle, streaming
;; tools last). Don't shuffle without checking the `typical_tokens_test`
;; and `subscription_info_test` order-sensitive expectations.
;; ---------------------------------------------------------------------------

(def tools
  "The eleven-tool catalogue. Single source of truth for the
  `tools/list` descriptors, the `tools/call` dispatcher, and the
  per-tool cache opt-in. See ns docstring for the entry shape.

  Each `:handler` is a thin late-binding wrapper around the per-tool fn
  — `(fn [conn args _] (per-tool-fn conn args))` — so test seams that
  `set!` the underlying var (rf2-nogok) take effect on the next
  dispatch."
  [{:name       "discover-app"
    :handler    (fn [conn args _extra] (discover-app/discover-app conn args))
    :cacheable? true
    :descriptor data/discover-app}
   {:name       "eval-cljs"
    :handler    (fn [conn args _extra] (eval-cljs/eval-cljs-tool conn args))
    :cacheable? false
    :descriptor data/eval-cljs}
   {:name       "dispatch"
    :handler    (fn [conn args _extra] (dispatch/dispatch-tool conn args))
    :cacheable? false
    :descriptor data/dispatch}
   {:name       "trace-window"
    :handler    (fn [conn args _extra] (trace-window/trace-window-tool conn args))
    :cacheable? true
    :descriptor data/trace-window}
   {:name       "watch-epochs"
    :handler    (fn [conn args _extra] (watch-epochs/watch-epochs-tool conn args))
    :cacheable? true
    :descriptor data/watch-epochs}
   {:name       "tail-build"
    :handler    (fn [conn args _extra] (tail-build/tail-build-tool conn args))
    :cacheable? false
    :descriptor data/tail-build}
   {:name       "snapshot"
    :handler    (fn [conn args _extra] (snapshot/snapshot-tool conn args))
    :cacheable? true
    :descriptor data/snapshot}
   {:name       "get-path"
    :handler    (fn [conn args _extra] (get-path/get-path-tool conn args))
    :cacheable? true
    :descriptor data/get-path}
   {:name       "subscribe"
    :handler    (fn [conn args extra]  (subscribe/subscribe-tool conn args extra))
    :cacheable? false
    :descriptor data/subscribe}
   {:name       "unsubscribe"
    :handler    (fn [conn args _extra] (unsubscribe/unsubscribe-tool conn args))
    :cacheable? false
    :descriptor data/unsubscribe}
   {:name       "subscription-info"
    :handler    (fn [conn args _extra] (subscription-info/subscription-info-tool conn args))
    :cacheable? false
    :descriptor data/subscription-info}])

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

(def ^:private cacheable-set
  "Materialised set of tool names whose `:cacheable?` is truthy.
  Built once at load time so `cacheable?` is an O(1) membership test."
  (into #{} (comp (filter :cacheable?) (map :name)) tools))

(defn cacheable?
  "Predicate — should this tool consult the per-session response cache?

  True for read tools whose return value is a function of state
  (`snapshot`, `get-path`, `trace-window`, `watch-epochs`,
  `discover-app`). False for action tools (`dispatch`, `eval-cljs`,
  `tail-build`) and streaming tools (`subscribe`, `unsubscribe`,
  `subscription-info`) — their return value is the result of an
  action, not a read of state.

  Unknown names return false."
  [tool]
  (contains? cacheable-set tool))
