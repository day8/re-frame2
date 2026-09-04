(ns re-frame.story.lifecycle
  "Variant-lifecycle public facade — setup, loaders, events, and play;
  the snapshot-
  identity helper, and the frame teardown / enumeration helpers.

  These symbols are re-exported from `re-frame.story`, so users call
  `re-frame.story/run-variant` etc. The implementation weight — the
  delegators into `re-frame.story.runtime`, `re-frame.story.frames`,
  and `re-frame.story.loaders` — lives here.

  Every fn delegates 1:1 to its owning module. Rendering stays in the UI
  shell and `re-frame.story.render`; it is not a hidden runtime phase."
  (:require [re-frame.story.frames    :as rf.story.frames]
            [re-frame.story.loaders   :as rf.story.loaders]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.runtime   :as rf.story.runtime]))

;; ---- variant → EDN serialisation ----------------------------------------
;;
;; Per `002-Runtime.md` §Programmatic API the variant body is round-trippable through the
;; registrar side-table; `variant->edn` returns the registered body
;; verbatim (canonicalisation for snapshot-identity is handled inside
;; `re-frame.story.identity`).

(defn variant->edn
  "Per `002-Runtime.md` §Programmatic API — return the registered body of the variant as
  serialisable EDN. The body is the side-table value verbatim;
  canonicalisation (sorted keys, deterministic vector order) for
  snapshot-identity lives in `re-frame.story.identity`.

  Returns nil when the variant is unregistered."
  [variant-id]
  (rf.story.registrar/handler-meta :variant variant-id))

(defn workspace->edn
  "Per `002-Runtime.md` §Programmatic API — same for workspaces."
  [workspace-id]
  (rf.story.registrar/handler-meta :workspace workspace-id))

;; ---- run-variant / reset-variant / snapshot-identity --------------------
;;
;; The four-phase variant lifecycle (loaders → events → render → play).
;; These call into `re-frame.story.runtime`. Each returns a promise
;; (CLJS) or CompletableFuture (JVM); see `re-frame.story.async` for
;; the result shape.

(defn run-variant
  "Allocate a frame for `variant-id`, run setup, loaders, events, and play,
  and return the unified result asynchronously.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)

  The result contract is owned by `re-frame.story.result`; rendering is
  driven separately by `render-variant` or the UI shell."
  ([variant-id]       (rf.story.runtime/run-variant variant-id nil))
  ([variant-id opts]  (rf.story.runtime/run-variant variant-id opts)))

(defn reset-variant
  "Tear down + re-run `variant-id`. Per `002-Runtime.md` §Programmatic API."
  ([variant-id]       (rf.story.runtime/reset-variant variant-id nil))
  ([variant-id opts]  (rf.story.runtime/reset-variant variant-id opts)))

(defn run-inline-plan
  "Per spec/017 §Inline plan — run an inline plan MAP and
  return a promise/future of the unified run-result (the SAME shape a
  registered-variant run returns). The plan is compiled, run against a
  fresh anonymous frame, and the frame is torn down on resolve; it is
  NEVER registered in the Story side-table. Delegates to
  `re-frame.story.runtime/run-inline-plan`."
  ([inline-plan]      (rf.story.runtime/run-inline-plan inline-plan nil))
  ([inline-plan opts] (rf.story.runtime/run-inline-plan inline-plan opts)))

(defn watch-variant
  "Subscribe to lifecycle transitions for `variant-id`'s frame. Per
  `002-Runtime.md` §Programmatic API. `callback` receives
  `{:frame-id <id> :from <state> :to <state> :event <inner-event>}`
  on every transition. Returns a 0-arity unsubscribe fn."
  [variant-id callback]
  (rf.story.runtime/watch-variant variant-id callback))

(defn snapshot-identity
  "Per `002-Runtime.md` §Snapshot-identity computation. Content-hash over the canonicalised
  `(variant × resolved-args × decorators × loaders × substrate × modes)`
  tuple. Stable across hosts.

  Returns `{:variant-id ... :active-modes [...] :substrate ...
  :content-hash \"<8-char hex>\"}`."
  ([variant-id]       (rf.story.runtime/snapshot-identity variant-id))
  ([variant-id opts]  (rf.story.runtime/snapshot-identity variant-id opts)))

(defn destroy-variant!
  "Tear down a variant frame allocated via `run-variant`. Per IMPL-
  SPEC §5.1 — the caller (UI shell / test fixture) owns teardown."
  [variant-id]
  (rf.story.frames/destroy! variant-id))

(defn variant-frames
  "Return every registered variant frame id. The UI shell uses this
  to lay out the active variant pane."
  []
  (rf.story.frames/variant-frames))

(defn variant-frame?
  "True iff `frame-id` is a variant frame."
  [frame-id]
  (rf.story.frames/variant-frame? frame-id))

(defn lifecycle-state
  "Return the lifecycle's current discrete state for the variant's
  frame (`:pre-mount`, `:mounting`, `:loading`, `:ready`, `:error`).
  Returns `:pre-mount` if the variant hasn't been run yet."
  [variant-id]
  (rf.story.loaders/current-state variant-id))
