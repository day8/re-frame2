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
            [re-frame2-pair-mcp.tools.trace-window :as trace-window]
            [re-frame2-pair-mcp.tools.watch-epochs :as watch-epochs]
            [re-frame2-pair-mcp.tools.tail-build :as tail-build]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.subscribe :as subscribe]
            [re-frame2-pair-mcp.tools.unsubscribe :as unsubscribe]
            [re-frame2-pair-mcp.tools.subscription-info :as subscription-info]
            [re-frame2-pair-mcp.tools.handler-meta :as handler-meta]
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
  3-arity convention `(fn [conn args _extra])`. Thirteen of the
  fourteen registered tools ignore `extra` (only `subscribe`
  consults it);
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
;; and `subscription_info_test` order-sensitive expectations.
;; ---------------------------------------------------------------------------

(def tools
  "The fourteen-tool catalogue. Single source of truth for the
  `tools/list` descriptors, the `tools/call` dispatcher, and the
  per-tool cache opt-in. See ns docstring for the entry shape.

  Each `:handler` is a thin late-binding wrapper around the per-tool
  fn — `ignoring-extra` for the thirteen tools that ignore `extra`,
  or an inline `(fn [conn args extra] ...)` for `subscribe` (which
  uses `extra` for its progress-callback plumbing). Both shapes
  resolve the underlying fn per-call so test seams that `set!` the
  var (rf2-nogok) take effect on the next dispatch."
  [{:name       "discover-app"
    :handler    (ignoring-extra #(discover-app/discover-app %1 %2))
    :cacheable? true
    :descriptor data/discover-app}
   {:name       "eval-cljs"
    :handler    (ignoring-extra #(eval-cljs/eval-cljs-tool %1 %2))
    :cacheable? false
    :descriptor data/eval-cljs}
   {:name       "dispatch"
    :handler    (ignoring-extra #(dispatch/dispatch-tool %1 %2))
    :cacheable? false
    :descriptor data/dispatch}
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
   {:name       "subscribe"
    :handler    (fn [conn args extra] (subscribe/subscribe-tool conn args extra))
    :cacheable? false
    :descriptor data/subscribe}
   {:name       "unsubscribe"
    :handler    (ignoring-extra #(unsubscribe/unsubscribe-tool %1 %2))
    :cacheable? false
    :descriptor data/unsubscribe}
   {:name       "subscription-info"
    :handler    (ignoring-extra #(subscription-info/subscription-info-tool %1 %2))
    :cacheable? false
    :descriptor data/subscription-info}
   {:name       "handler-meta"
    :handler    (ignoring-extra #(handler-meta/handler-meta-tool %1 %2))
    :cacheable? true
    :descriptor data/handler-meta}
   {:name       "registry-list"
    :handler    (ignoring-extra #(handler-meta/registry-list-tool %1 %2))
    :cacheable? true
    :descriptor data/registry-list}
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

(def ^:private cacheable-set
  "Materialised set of tool names whose `:cacheable?` is truthy.
  Built once at load time so `cacheable?` is an O(1) membership test."
  (into #{} (comp (filter :cacheable?) (map :name)) tools))

(defn cacheable?
  "Predicate — should this tool consult the per-session response cache?

  True for read tools whose return value is a function of state
  (`snapshot`, `get-path`, `trace-window`, `watch-epochs`,
  `discover-app`) and for the inline `get-re-frame2-pair-instructions`
  onboarding text (which is a pure-data function with no state
  whatsoever — once is forever). False for action tools (`dispatch`,
  `eval-cljs`, `tail-build`) and streaming tools (`subscribe`,
  `unsubscribe`, `subscription-info`) — their return value is the
  result of an action, not a read of state.

  Unknown names return false."
  [tool]
  (contains? cacheable-set tool))
