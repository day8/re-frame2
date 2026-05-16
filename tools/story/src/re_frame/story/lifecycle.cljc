(ns re-frame.story.lifecycle
  "Stage-3 variant-lifecycle public surface — the four-phase
  variant lifecycle (loaders → events → render → play), the snapshot-
  identity helper, and the frame teardown / enumeration helpers.

  Per the rf2-l8eso Phase-2 facade thinning: these symbols are
  re-exported from `re-frame.story` so users continue calling
  `re-frame.story/run-variant` etc. The implementation weight — the
  delegators into `re-frame.story.runtime`, `re-frame.story.frames`,
  and `re-frame.story.loaders` — lives here.

  Every fn delegates 1:1 to its owning module; this ns is the
  cohesive grouping for the Stage-3 lifecycle surface."
  (:require [re-frame.story.frames    :as frames]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.runtime   :as runtime]))

;; ---- variant → EDN serialisation ----------------------------------------
;;
;; Per IMPL-SPEC §3.2 the variant body is round-trippable through the
;; registrar side-table; `variant->edn` returns the registered body
;; verbatim (canonicalisation for snapshot-identity is handled inside
;; `re-frame.story.identity`).

(defn variant->edn
  "Per IMPL-SPEC §3.2 — return the registered body of the variant as
  serialisable EDN. The body is the side-table value verbatim;
  canonicalisation (sorted keys, deterministic vector order) for
  snapshot-identity lives in `re-frame.story.identity`.

  Returns nil when the variant is unregistered."
  [variant-id]
  (registrar/handler-meta :variant variant-id))

(defn workspace->edn
  "Per IMPL-SPEC §3.2 — same for workspaces."
  [workspace-id]
  (registrar/handler-meta :workspace workspace-id))

;; ---- run-variant / reset-variant / snapshot-identity --------------------
;;
;; The four-phase variant lifecycle (loaders → events → render → play).
;; These call into `re-frame.story.runtime`. Each returns a promise
;; (CLJS) or CompletableFuture (JVM); see `re-frame.story.async` for
;; the result shape.

(defn run-variant
  "Per IMPL-SPEC §3.2. Allocate a frame for `variant-id`, run the four-
  phase lifecycle (loaders → events → render → play), and return a
  promise/future of the result map.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)
    :render?         when truthy, the UI shell renders into
                     `:rendered-hiccup`. Defaults to nil.
    :assertions      assertions hook (see `re-frame.story.assertions`).

  Result map:

      {:frame           <variant-id>
       :app-db          {...}
       :assertions      [...]
       :rendered-hiccup nil
       :elapsed-ms      <ms>
       :snapshot        {:variant-id ... :content-hash \"...\"}
       :decorators      {:hiccup [...] :frame-setup [...] :fx-override [...]
                         :errors [...]}
       :effective-args  {...}
       :lifecycle       :ready | :error}"
  ([variant-id]       (runtime/run-variant variant-id nil))
  ([variant-id opts]  (runtime/run-variant variant-id opts)))

(defn reset-variant
  "Tear down + re-run `variant-id`. Per IMPL-SPEC §3.2."
  ([variant-id]       (runtime/reset-variant variant-id nil))
  ([variant-id opts]  (runtime/reset-variant variant-id opts)))

(defn watch-variant
  "Subscribe to lifecycle transitions for `variant-id`'s frame. Per
  IMPL-SPEC §3.2. `callback` receives
  `{:frame-id <id> :from <state> :to <state> :event <inner-event>}`
  on every transition. Returns a 0-arity unsubscribe fn."
  [variant-id callback]
  (runtime/watch-variant variant-id callback))

(defn snapshot-identity
  "Per IMPL-SPEC §3.2 + §5.6. Content-hash over the canonicalised
  `(variant × resolved-args × decorators × loaders × substrate × modes)`
  tuple. Stable across hosts.

  Returns `{:variant-id ... :active-modes [...] :substrate ...
  :content-hash \"<8-char hex>\"}`."
  ([variant-id]       (runtime/snapshot-identity variant-id))
  ([variant-id opts]  (runtime/snapshot-identity variant-id opts)))

(defn destroy-variant!
  "Tear down a variant frame allocated via `run-variant`. Per IMPL-
  SPEC §5.1 — the caller (UI shell / test fixture) owns teardown."
  [variant-id]
  (frames/destroy! variant-id))

(defn variant-frames
  "Return every registered variant frame id. The UI shell uses this
  to lay out the active variant pane."
  []
  (frames/variant-frames))

(defn variant-frame?
  "True iff `frame-id` is a variant frame."
  [frame-id]
  (frames/variant-frame? frame-id))

(defn lifecycle-state
  "Return the lifecycle's current discrete state for the variant's
  frame (`:pre-mount`, `:mounting`, `:loading`, `:ready`, `:error`).
  Returns `:pre-mount` if the variant hasn't been run yet."
  [variant-id]
  (loaders/current-state variant-id))
