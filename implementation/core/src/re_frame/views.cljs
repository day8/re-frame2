(ns re-frame.views
  "Views — reg-view, frame-provider. Per Spec 004.

  CLJS-only (Reagent-side). The pure-data render-tree contract lives in
  Spec 011 (SSR); this namespace ties view registration into the Reagent
  substrate.

  Form-1 (a render fn) is the canonical view shape. Form-2/3 are
  supported via Reagent's native handling.

  reg-view auto-defs the local Var (per Spec 004 §reg-view defs the Var
  by default); the Var is the canonical call-site reference. For
  late-binding by id (e.g. across module boundaries, runtime-computed
  ids, or hot-reload semantics), call `(re-frame.core/view :id)`
  to obtain the wrapped fn and use `[(rf/view :id) args]` as the
  hiccup head.

  Orchestration entry point that re-exports three internally-cohesive
  sub-namespaces:

    - `re-frame.views.provider`                — frame-provider + the
                                                 React-context bridge
                                                 and per-render
                                                 instance-token
                                                 machinery
    - `re-frame.views.source-coord-annotation` — Spec 006 source-coord
                                                 DOM annotation walk
    - `re-frame.views.warn-once`               — per-process warn-once
                                                 caches (non-DOM root,
                                                 plain-fn-under-non-
                                                 default-frame)

  The publicly-referenced surface is re-exported via plain `def` aliases
  below so existing call sites — `re-frame.core/reg-view*`, the test
  files, the late-bind hook table, and the adapter ns docstrings —
  continue to resolve through this ns unchanged.

  The `*render-key*` dynamic var lives in THIS ns (rather than under
  `re-frame.views.provider` alongside the rest of the instance-token
  machinery) because tests read it via `re-frame.views/*render-key*`
  from inside a render-fn while the wrapper below binds the same Var.
  Putting the canonical Var here makes the read and the binding hit
  identical Vars — a `(def ^:dynamic *render-key* provider/*render-key*)`
  re-export would create a SECOND Var whose binding the test wouldn't
  observe.

  The view-deref-sink + first-render? machinery (`*view-deref-sink*`,
  `record-view-deref!`, `first-render?!`, `clear-seen-render-keys!`)
  introduced in rf2-9hoos — see the per-block sections below for
  contract detail."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance :include-macros true]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace :include-macros true]
            [re-frame.views.provider :as provider]
            [re-frame.views.source-coord-annotation :as source-coord]
            [re-frame.views.warn-once :as warn-once]))

;; ---- *render-key* --------------------------------------------------------
;;
;; Bound by the `reg-view*` wrapper below for the duration of each render.
;; Lives here (the public ns) so `re-frame.views/*render-key*` is the
;; canonical Var that wrapper-binding and consumer-reads share — see
;; the ns docstring above for why this can't move under provider.

(def ^:dynamic *render-key*
  "The `:render-key` for the in-flight render — a tuple
  `[<view-id> <instance-token>]`. Bound by the wrapper emitted by
  `reg-view*` for the duration of each render. Nil outside a registered
  view's render (the trace recorder treats nil as
  `[:rf.view/anonymous nil]` per Spec 004 §Render-tree primitives)."
  nil)

(defn current-render-key
  "Return the `:render-key` for the in-flight render, or
  `[:rf.view/anonymous nil]` when none is bound (e.g. inside a plain
  Reagent fn that bypassed reg-view). Per Spec 004 §Render-tree
  primitives — the anonymous fallback is the documented unbound-shape."
  []
  (or *render-key* [:rf.view/anonymous nil]))

(defn reading-render-key
  "Return the `:render-key` of the view whose render is currently
  deref-ing a subscription, or nil when no view render is on the stack.

  Distinct from `current-render-key` (which substitutes the anonymous
  fallback): this returns the RAW `*render-key*` — nil when a sub is
  computed OUTSIDE any view render (a handler that subscribes, an SSR
  walk, a direct `compute-sub`). The reactive `:sub/run` emit
  (`re-frame.subs.memo`) stamps this onto its tag (rf2-vh1k3) so the
  epoch back-fill can attribute a post-settle render to the epoch in
  which the rendering view's OWN inputs actually changed — not whatever
  cascade happens to be settling when the late mount commit lands.

  Published through late-bind under `:views/reading-render-key` so the
  subs layer reads it without a static require on this CLJS-only ns."
  []
  *render-key*)

;; ---- per-render deref sink (view→sub edges) -----------------------------
;;
;; Captures the set of subscription query-vectors a view derefs DURING
;; its render, so the `:rf.view/rendered` trace can carry the view's OWN
;; sub set (the per-view "reason"), not just the cascade-wide
;; `:cause-subs` (which over-reports — it lists every sub that ran in the
;; cascade regardless of whether THIS view reads it). Distinct from the
;; rf2-vh1k3 `:reader-render-key` learning, which captures only subs that
;; RECOMPUTE synchronously in-render: this sink captures EVERY deref
;; (memo-hit AND recompute), so a view that re-renders structurally and
;; re-derefs unchanged subs still surfaces its full read-set.
;;
;; Bound per render by `build-frame-aware-view` to a fresh volatile set;
;; nil outside a render (a handler that subscribes, an SSR walk) so the
;; sink-push is a no-op there. The binding site rides
;; `interop/debug-enabled?` and the consumer push is gated at the
;; `re-frame.subs/subscribe` call site, so production DCEs the whole
;; surface.

(def ^:dynamic *view-deref-sink*
  "Per-render volatile holding the set of subscription query-vectors the
  in-flight view render has deref'd so far. Bound by the
  `build-frame-aware-view` wrapper for the duration of each render under
  `interop/debug-enabled?`; nil otherwise."
  nil)

(defn record-view-deref!
  "Union `query-v` into the in-flight render's deref sink.
  No-op when no render is on the stack (`*view-deref-sink*` nil — a
  handler-side subscribe, an SSR walk, a direct read). Published through
  late-bind under `:views/record-view-deref!` so `re-frame.subs/subscribe`
  records the edge without a static require on this CLJS-only ns. The
  caller gates the invocation on `interop/debug-enabled?`."
  [query-v]
  (when-let [sink *view-deref-sink*]
    (vswap! sink conj query-v))
  nil)

;; ---- mount-vs-rerender discrimination ------------------------------------
;;
;; `:rf.view/rendered` fires on every render; the `:mount?` flag
;; discriminates a component instance's FIRST render from its subsequent
;; re-renders so a consumer can label the action mount vs rerender.
;; Keyed by `:render-key` (`[view-id instance-token]`), which is stable
;; across re-renders of the same mounted instance and fresh per new
;; instance. The seen-set only grows within a process run; teardown of an
;; instance does not evict its key (a remount mints a fresh
;; instance-token, so the new key is unseen → `:mount? true` again).
;; Test fixtures reset via `clear-seen-render-keys!`.

(defonce ^:private seen-render-keys (atom #{}))

(defn first-render?!
  "Return `true` the FIRST time `render-key` is seen this process run,
  `false` thereafter — the mount-vs-rerender discriminator.
  Side-effecting: records the key on first sighting. Caller gates on
  `interop/debug-enabled?`."
  [render-key]
  (let [[old _new] (swap-vals! seen-render-keys conj render-key)]
    (not (contains? old render-key))))

(defn clear-seen-render-keys!
  "Wipe the seen-render-keys set. Test fixtures call this so
  a sibling test's `:mount?` flag does not leak across cases (the
  per-process set would otherwise report a re-used render-key as a
  rerender in the next test). Wired into the chained
  `:adapter/clear-warn-once-caches!` late-bind hook (rf2-4edk) so a
  single fixture reset clears it alongside the warn-once caches."
  []
  (reset! seen-render-keys #{})
  nil)

;; ---- re-exported public surface ------------------------------------------
;;
;; Plain `def` aliases (rather than `:refer`-imports) so
;; `#'re-frame.views/<name>` resolves under this ns — `:refer` does
;; not surface a Var under the consuming ns.

(def frame-provider provider/frame-provider)
(def frame-provider-existing provider/frame-provider-existing)
(def build-frame-provider provider/build-frame-provider)
(def current-frame provider/current-frame)
(def mint-instance-token! provider/mint-instance-token!)

(def format-source-coord source-coord/format-source-coord)

(def clear-warned-non-dom-roots! warn-once/clear-warned-non-dom-roots!)

;; The React-context object is consumed by `reg-view*` below (the
;; `:contextType` static-field) and by the warn-once helpers in
;; `re-frame.views.warn-once`. Aliased privately here for parity with
;; the pre-split shape — no external caller reaches for it.
(def ^:private frame-context provider/frame-context)

;; rf2-25zo2: per-cascade cap on :rf.view/rendered emits. The Xray
;; Reactive panel needs cascade-attribution per re-render, but a
;; full-page re-render can fire hundreds of view-render emits and blow
;; the per-cascade buffer's heap budget. The cap matches the
;; cascade-cause sub-cap (100); when crossed, the view-render emission
;; site fires a single :rf.view/rendered-cap-reached marker once per
;; cascade and skips subsequent :rf.view/rendered emits (the existing
;; :view/render emit is NOT capped — that op rides the per-view-render
;; cost we already pay).
(def ^:private view-rendered-cap 100)

(defn- emit-view-render-trace!
  "Emit the `:view/render` render-event marker tagged with the in-flight
  `:render-key` and `:frame` (Spec 009 §Trace ops). Fires at the START
  of each render (before the user render-fn runs). Goes through late-bind
  so this ns doesn't depend on re-frame.trace (which itself routes
  through late-bind for registrar/views ordering reasons). Production
  builds elide via the `interop/debug-enabled?` gate the trace surface
  itself rides.

  Substrate-agnostic — every adapter composes `views.cljs`'s
  frame-aware-view wrapper around its user render-fn, so this emit rides
  Reagent / UIx / Helix renders. `frame-id` is resolved once by the
  caller and threaded into both this and the post-render
  `:rf.view/rendered` emit, so there is one `provider/current-frame`
  resolution per render."
  [render-key frame-id]
  (when interop/debug-enabled?
    ;; Sticky hook (rf2-f72pd) — `:trace/emit!` is published once at
    ;; re-frame.trace load and never withdrawn; this fires per render
    ;; under dev builds.
    (when-let [emit! (late-bind/get-fn-cached :trace/emit!)]
      (emit! :rf.view :rf.view/render
             {:rf.view/render-key render-key
              :frame              frame-id}))))

(defn- emit-view-rendered-trace!
  "Emit the `:rf.view/rendered` cascade-attribution marker (rf2-25zo2,
  consumed by Xray's Reactive panel for cascade graphing). Fires AFTER
  the user render-fn has run so the per-render deref sink is fully
  populated (rf2-9hoos). Carries:

    :render-key     — `[view-id instance-token]` (parity with :view/render).
    :view-id        — the registered view id.
    :frame          — the frame the render landed in.
    :mount?         — `true` on the component instance's first render,
                      `false` on every subsequent re-render (rf2-9hoos —
                      the mount-vs-rerender discriminator).
    :deref-subs     — the vector of subscription query-vectors THIS view
                      deref'd during the render (rf2-9hoos — the per-view
                      read-set, the precise per-view reactive 'reason').
                      First-seen order; absent when the view derefs no
                      subs (a pure structural render). Distinct from
                      cascade-wide `:cause-subs` (which over-reports).
    :render-args    — (when the view took render args) the vector of
                      positional render args/props passed to THIS render
                      (rf2-rpgq8 — the prerequisite for the Xray VIEWS
                      render-args diff column). Captured by the
                      substrate-agnostic `build-frame-aware-view` wrapper
                      (so it rides Reagent / UIx / Helix renders alike) and
                      threaded in here. Absent on a no-arg render. PRIVACY:
                      render args are arbitrary user data, so the value is
                      routed through the SAME emit-time elision chokepoint
                      every other user-data trace payload uses — the marks
                      projection's `:rf.view/rendered` arm runs
                      `re-frame.elision/elide-wire-value` against the
                      frame's app-db elision registry (sensitive paths →
                      `:rf/redacted`, large leaves → `:rf.size/large-elided`)
                      before the event reaches any listener / the wire.
                      Spec 009 §Privacy / Spec 015 §Data classification.
    :triggered-by   — (when derivable) the SINGLE sub-id that caused THIS
                      view to re-render (rf2-8wrzz.1): the first sub in the
                      view's own read-set (`deref-subs`) whose value
                      changed in the cascade. The precise per-view cause
                      Xray's Views panel shows as the re-render reason.
                      Absent on a structural re-render (no own sub changed)
                      or outside a cascade. Distinct from `:cause-subs`
                      (cascade-wide) and from `:deref-subs` (the full
                      per-view read-set, changed-or-not).
    :elapsed-ms     — wall-clock duration of the user render-fn for THIS
                      render, in fractional milliseconds (rf2-8wrzz.1).
                      Threaded in from the wrapper (measured around the
                      `mark-and-measure` bracket). Always present in dev
                      builds; the timing reads ride `interop/debug-enabled?`
                      so production DCEs them with the rest of the emit.
    :cause-event-id — (when in-cascade) the dispatching cascade's event-id.
    :cause-subs     — (when in-cascade) distinct sub-ids that ran in the
                      cascade, first-seen order, capped at 100.

  Resolved via the epoch capture's in-flight buffer; the attribution
  slots are absent (or the buffer returns nil) when re-frame.epoch is not
  on the classpath — in that case the op fires without attribution slots
  so consumers without the epoch artefact still see the marker. Capped at
  100 `:rf.view/rendered` per cascade with a one-shot
  `:rf.view/rendered-cap-reached` marker (carries `:frame` +
  `:dropped-after`)."
  [view-id render-key frame-id mount? deref-subs elapsed-ms render-args]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn-cached :trace/emit!)]
      ;; rf2-25zo2: :rf.view/rendered carries cascade-attribution.
      ;; Resolved via the epoch capture's in-flight buffer; absent
      ;; (or returns nil) when re-frame.epoch is not on the classpath.
      (let [cause-fn (late-bind/get-fn-cached :epoch/cascade-cause)
            cause    (when cause-fn (cause-fn frame-id))
            n-so-far (long (or (:rendered-so-far cause) 0))
            ;; rf2-8wrzz.1 — the per-view re-render cause: the first sub in
            ;; THIS view's read-set whose value changed in the cascade.
            ;; `deref-subs` are query-vectors `[query-id args]`;
            ;; `:value-changed-subs` is a set of query-ids — match on the
            ;; query-id (head of each deref'd query-vector). nil on a
            ;; structural re-render (no own sub changed) or outside a
            ;; cascade (`:value-changed-subs` absent → nil set).
            changed       (:value-changed-subs cause)
            triggered-by  (when (seq changed)
                            (some (fn [qv]
                                    (let [qid (if (vector? qv) (first qv) qv)]
                                      (when (contains? changed qid) qid)))
                                  deref-subs))]
        (cond
          ;; Past the cap — emit a one-shot marker on the threshold
          ;; cross. The marker rides the same per-cascade buffer so
          ;; consumers can detect truncation without inspecting state.
          (= n-so-far view-rendered-cap)
          (emit! :rf.view :rf.view/rendered-cap-reached
                 {:frame                 frame-id
                  :rf.view/dropped-after view-rendered-cap})

          (< n-so-far view-rendered-cap)
          (emit! :rf.view :rf.view/rendered
                 (cond-> {:rf.view/render-key render-key
                          :rf.view/id         view-id
                          :frame              frame-id
                          :rf.view/mount?     mount?}
                   (some? elapsed-ms)
                   (assoc :rf.view/elapsed-ms elapsed-ms)
                   (seq deref-subs)
                   (assoc :rf.view/deref-subs deref-subs)
                   ;; rf2-rpgq8: the view's positional render args/props. Stamped
                   ;; raw here (dev-only emit); the marks-projection chokepoint
                   ;; (`re-frame.classification/project-trace-event`, gated by the same
                   ;; `interop/debug-enabled?` upstream in `trace/emit!`) routes
                   ;; this slot through `elide-wire-value` against the frame's
                   ;; app-db elision registry BEFORE delivery — the identical
                   ;; emit-time treatment `:rf.event/db` gets — so sensitive /
                   ;; large user data never reaches a listener or the wire raw.
                   (seq render-args)
                   (assoc :rf.view/render-args (vec render-args))
                   (some? triggered-by)
                   (assoc :rf.view/triggered-by triggered-by)
                   (:cause-event-id cause)
                   (assoc :rf.view/cause-event-id (:cause-event-id cause))
                   (:cause-subs cause)
                   (assoc :rf.view/cause-subs (:cause-subs cause))))

          ;; n-so-far > cap — silent skip (the cap-reached marker
          ;; fired already on the threshold cross).
          :else nil)))))

;; ---- view unmount (rf2-9hoos) --------------------------------------------
;;
;; `:rf.view/unmounted` fires when a registered-view component instance
;; tears down, so a consumer (Xray's Views table) can label the action
;; `unmount`. Not traced before rf2-9hoos.
;;
;; The teardown signal rides the per-render-instance reaction-dispose
;; mechanism — the same one `r/with-let`'s `finally` arm uses: a
;; reaction created in the wrapper, deref'd inside the render so the
;; substrate's per-component render reaction tracks it as a dependency,
;; with an `interop/add-on-dispose!` callback that fires
;; `:rf.view/unmounted` when the reaction is disposed. On the Reagent
;; family (stock + reagent-slim) the component's render reaction disposes
;; its tracked dependencies on `componentWillUnmount`, so the callback
;; fires exactly once per instance teardown. The whole surface rides
;; `interop/debug-enabled?` so production DCEs it (the reaction is never
;; created, the deref never happens, the emit never fires).

(defn emit-view-unmounted!
  "Emit the `:rf.view/unmounted` teardown marker for the view instance
  named by `render-key` in `frame-id` (rf2-9hoos). Carries at least
  `:view-id` + `:frame` (plus the `:render-key` instance tuple). Goes
  through the `:trace/emit!` late-bind hook so this ns stays free of a
  static re-frame.trace require. Gated on `interop/debug-enabled?` so
  production DCEs the body."
  [view-id render-key frame-id]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn-cached :trace/emit!)]
      (emit! :rf.view :rf.view/unmounted
             {:rf.view/render-key render-key
              :rf.view/id         view-id
              :frame              frame-id}))))

(defn install-unmount-hook!
  "Wire `:rf.view/unmounted` emission to the teardown of the view
  instance named by `render-key` (rf2-9hoos). Creates a per-instance
  lifecycle reaction via `interop/make-reaction`, registers the unmount
  emit as an on-dispose callback, and returns the reaction so the caller
  can deref it inside the render — that deref registers the reaction as a
  dependency of the substrate's per-component render reaction, which
  disposes it (firing the callback) on `componentWillUnmount`.

  Returns nil when no reaction primitive is available (the active adapter
  did not publish `:adapter/make-reaction` — e.g. a React-hook substrate
  or a headless build); in that case no unmount hook is installed and the
  caller skips the deref. Gated on `interop/debug-enabled?`.

  Idempotent per instance: the wrapper installs at most one lifecycle
  reaction per `:render-key`, cached on the substrate component instance,
  so re-renders of the same mounted instance reuse the same reaction and
  the unmount emit fires exactly once."
  [view-id render-key frame-id]
  (when interop/debug-enabled?
    (when-let [rea (interop/make-reaction (fn [] render-key))]
      (interop/add-on-dispose! rea
        (fn [] (emit-view-unmounted! view-id render-key frame-id)))
      rea)))

;; ---- reg-view -------------------------------------------------------------
;;
;; The wrapper-builder is decomposed into named helpers, each owning
;; one phase of the registration pipeline:
;;
;;   1. `apply-adapter-wrap-view`         — consult the substrate hook
;;   2. `view-coord-attr`                 — debug-only source-coord stamp
;;   3. `trace/handler-scope-from-meta`   — pre-compute view's HandlerScope
;;   4. `build-frame-aware-view`          — assemble the per-render wrapped fn
;;   5. `registrar/register!`             — install in the :view kind
;;
;; reg-view* itself becomes a five-line straight-line composition.

(defn- apply-adapter-wrap-view
  "Consult the `:adapter/wrap-view` late-bind hook (rf2-00li) for a
  substrate-side wrap. Returns `[render-fn wrap-applied?]`.

  UIx / Helix register a substrate wrap-view because their render-fn
  output is a React element — neither a hiccup vector nor a fn — so
  the inline `inject-source-coord-attr` walk would mis-classify the
  root and skip annotation. Those adapters supply a wrap-view that
  injects `data-rf2-source-coord` via `React.cloneElement`. The
  Reagent adapter does NOT publish the hook; the inline hiccup walk
  in `build-frame-aware-view` continues to serve it.

  The hook may be registered (e.g. test bundle loaded UIx + Helix
  adapter ns's) yet return nil — each adapter's routing closure
  returns nil when its own adapter is NOT the installed one (per
  rf2-0d35), so the chain bottoms out at nil when the Reagent adapter
  is installed. A nil from the hook means \"no substrate wrap
  applied\" — keep render-fn unchanged and let the inline walk run.

  The adapter's wrap-view body itself sits inside
  `(when interop/debug-enabled? ...)`, so under :advanced +
  goog.DEBUG=false the wrapped fn collapses to the bare user-fn (no
  cloneElement) — keeping the elision contract."
  [id metadata render-fn]
  ;; Per rf2-f72pd sticky-hook convention: `:adapter/wrap-view` is
  ;; published once at adapter ns-load via `substrate-adapter/route-hook!`
  ;; and never withdrawn in production, so the resolution is cacheable.
  ;; `route-hook!` calls `late-bind/set-fn!` which invalidates the
  ;; cache, so dev-time hot-reload of an adapter re-resolves on the
  ;; next reg-view call.
  (let [hook    (late-bind/get-fn-cached :adapter/wrap-view)
        wrapped (when hook (hook id metadata render-fn))]
    (if (some? wrapped)
      [wrapped true]
      [render-fn false])))

(defn- view-coord-attr
  "Capture the source-coord stamp for the inline hiccup-walk
  annotation path (Spec 006 §Source-coord annotation, rf2-z7f7 /
  rf2-z9n1). Returns nil under :advanced + goog.DEBUG=false, and
  also nil when the substrate hook has already wrapped render-fn
  (its own cloneElement path supersedes the hiccup walk)."
  [id metadata wrap-applied?]
  (when (and interop/debug-enabled? (not wrap-applied?))
    (source-coord/format-source-coord id metadata)))

(defn- maybe-arm-unmount!
  "Install (once per mounted instance) the `:rf.view/unmounted` teardown
  hook for `render-key` and deref its lifecycle reaction so the
  substrate's per-component render reaction tracks it (rf2-9hoos). The
  reaction is cached on the component instance via
  `provider/component-lifecycle-reaction` so re-renders reuse it and the
  unmount emit fires exactly once. Returns nil; called for side effect
  inside the render under `interop/debug-enabled?`.

  No-op when the active adapter publishes no `:adapter/make-reaction`
  primitive (`install-unmount-hook!` returns nil) — a React-hook
  substrate or a headless build — or when there is no component instance
  to cache against (a direct headless invocation of the wrapper)."
  [id render-key frame-id]
  (when interop/debug-enabled?
    (let [rea (provider/component-lifecycle-reaction
                (fn [] (install-unmount-hook! id render-key frame-id)))]
      ;; Deref so the per-component render reaction registers `rea` as a
      ;; dependency and disposes it (firing the unmount emit) on teardown.
      (when (some? rea) @rea))))

(defn- build-frame-aware-view
  "Build the per-render wrapped fn that ties view registration into
  Reagent: each render binds `*render-key*`, `*handler-scope*` and the
  per-render deref sink (`*view-deref-sink*`, rf2-9hoos), emits the
  `:view/render` trace, brackets the user render-fn in performance
  marks, emits the `:rf.view/rendered` trace (carrying the mount flag +
  the view's deref'd subs, rf2-9hoos), arms the `:rf.view/unmounted`
  teardown hook, and (when serving the Reagent inline path) annotates
  the rendered hiccup root with the source-coord attribute.

  Emit ordering (rf2-9hoos): `:view/render` fires BEFORE the user
  render-fn (the render-start marker, unchanged shape);
  `:rf.view/rendered` fires AFTER so the per-render deref sink is fully
  populated and the trace can carry the view's own `:deref-subs`.

  The returned fn carries `{:contextType frame-context}` meta so
  Reagent's create-class / fn-to-class machinery hooks it up to the
  React frame-context (rf2-kdwc — note the camelCase static-field
  name; the earlier kebab `:context-type` shape was silently ignored
  by Reagent)."
  [id render-fn view-scope coord-attr wrap-applied?]
  (let [wrapped
        (with-meta
          (fn frame-aware-view [& args]
            (let [tok        (provider/reagent-component-token)
                  render-key [id tok]
                  ;; rf2-9hoos: fresh per-render deref sink (dev-only). The
                  ;; volatile is bound below so `re-frame.subs/subscribe`'s
                  ;; gated `record-view-deref!` call unions each deref'd
                  ;; query-v into it; read back AFTER the render to stamp
                  ;; `:deref-subs` onto `:rf.view/rendered`.
                  sink       (when interop/debug-enabled? (volatile! []))]
              (binding [*render-key*     render-key
                        *view-deref-sink* sink]
                (trace/with-handler-scope view-scope
                  ;; Resolve the frame once per render — threaded into the
                  ;; unmount hook + both render emits (rf2-9hoos).
                  (let [frame-id (when interop/debug-enabled? (provider/current-frame))]
                    ;; rf2-9hoos: arm the unmount hook + compute the mount flag
                    ;; BEFORE the render so `first-render?!` reflects whether
                    ;; this is the instance's first render (the seen-set is
                    ;; updated here, not in the post-render emit).
                    (when interop/debug-enabled?
                      (maybe-arm-unmount! id render-key frame-id))
                    (let [mount? (when interop/debug-enabled? (first-render?! render-key))
                          ;; rf2-8wrzz.1: wall-clock the user render-fn (dev-only)
                          ;; so `:rf.view/rendered` can carry `:elapsed-ms` — the
                          ;; per-view render timing Xray's Views panel shows. The
                          ;; read rides `interop/debug-enabled?` so production
                          ;; DCEs it alongside the rest of the emit; nil in prod.
                          t0     (when interop/debug-enabled? (interop/now-ms))]
                      (emit-view-render-trace! render-key frame-id)
                      ;; Per Spec 009 §Performance instrumentation (rf2-du3i):
                      ;; every render of a registered view brackets the user
                      ;; render-fn in performance marks so prod builds with the
                      ;; perf flag enabled produce a `rf:render:<view-id>`
                      ;; measure entry. Default-off; under :advanced +
                      ;; `re-frame.performance/enabled?=false` the bracket DCEs
                      ;; and the form collapses to the bare `(apply render-fn
                      ;; args)` call.
                      (let [out        (performance/mark-and-measure :render id
                                         (apply render-fn args))
                            elapsed-ms (when interop/debug-enabled?
                                         (- (interop/now-ms) t0))]
                        ;; rf2-9hoos: emit AFTER the render so the deref sink is
                        ;; populated; carry the mount flag + the view's read-set.
                        ;; rf2-8wrzz.1: also carry the render's `:elapsed-ms`.
                        ;; rf2-rpgq8: also carry the view's positional render
                        ;; args/props — substrate-agnostic capture (this wrapper
                        ;; is the OUTERMOST fn every adapter composes, so `args`
                        ;; are the same values reaching the user render-fn on
                        ;; Reagent / UIx / Helix alike). Gated on
                        ;; `interop/debug-enabled?` so production passes nil and
                        ;; DCEs the capture with the rest of the emit; the marks
                        ;; chokepoint elides the slot before delivery.
                        (emit-view-rendered-trace! id render-key frame-id mount?
                                                   (when sink @sink) elapsed-ms
                                                   (when interop/debug-enabled? args))
                        (if (and interop/debug-enabled? (not wrap-applied?))
                          (source-coord/inject-source-coord-attr id coord-attr
                                                                 out)
                          out))))))))
          {:contextType frame-context})]
    ;; rf2-fa4ly: stamp the React `displayName` to the registered view-id so
    ;; React DevTools shows `<:cart/total-line>` in the component tree rather
    ;; than the CLJS-munged fn name (`day8.cart.total.total_line`) or an
    ;; anonymous Reagent wrapper. Reagent's create-class / fn-to-class
    ;; machinery picks up `.-displayName` off the input fn and forwards it to
    ;; the constructed React component. Dev-only — gated so the literal name
    ;; string never lands in the production bundle. The bundle-isolation gate
    ;; pins absence (the elision contract is broader — `displayName` itself is
    ;; a React surface, but assigning it from a user-derived string belongs
    ;; behind `interop/debug-enabled?`).
    (when interop/debug-enabled?
      (set! (.-displayName ^js wrapped) (str id)))
    wrapped))

(defn reg-view*
  "Reagent-aware view registration. Wraps `render-fn` with the React
  `:contextType` static-field metadata used to resolve the surrounding
  frame at render time, then registers it in the :view kind of the
  registrar.

  Per Spec 004 §reg-view*: this is the plain-fn surface delegated to
  by `re-frame.core/reg-view*` on CLJS. `metadata` is merged into the
  registry slot's metadata as-is; source-coord capture is performed
  by the caller (`re-frame.core/reg-view*`).

  Each render binds `*render-key*` to `[id instance-token]` so the
  trace recorder can attribute the render. The instance-token is
  minted at mount and reused across re-renders of the same component
  instance (per Spec 004 §Render-tree primitives).

  The view's HandlerScope is pre-computed once at registration time
  from `metadata` (source-coord stamp + `:rf.trace/no-emit?` — fixed
  for the life of the registered view). Each render binds the scope
  (via `with-handler-scope`, which inherits parent's `:call-site` /
  `:dispatch-id`) around the render-fn invocation. Errors emitted
  during render ride the view's `:trigger-handler` coord;
  `:view/render` emits short-circuit when `:no-emit?` is true.
  (Handler-meta `:sensitive?` annotation has been removed; per-path
  classification is the v2 mechanism.)

  The body is a five-line pipeline; the work lives in the named
  helpers above."
  [id metadata render-fn]
  (let [[render-fn wrap-applied?] (apply-adapter-wrap-view id metadata render-fn)
        coord-attr (view-coord-attr id metadata wrap-applied?)
        view-scope (trace/handler-scope-from-meta :view id metadata)
        wrapped    (build-frame-aware-view id render-fn view-scope coord-attr
                                           wrap-applied?)]
    (registrar/register! :view id (assoc metadata :handler-fn wrapped))
    wrapped))

;; ---- late-bind publication (rf2-vh1k3) ------------------------------------
;;
;; The reactive `:sub/run` emit (`re-frame.subs.memo/validate-and-trace`)
;; stamps the reading view's render-key onto its tag so the epoch
;; back-fill can tell a view's GENUINE re-render (its own input changed)
;; from a mount-burst tail that re-derefs unchanged subs. Reaching
;; `reading-render-key` through late-bind keeps the subs layer free of a
;; static require on this CLJS-only views ns (subs is .cljc + must not
;; hard-couple to the substrate). Sticky-hook shape (rf2-f72pd): set once
;; at views ns-load, never withdrawn. Whole stamp rides
;; `interop/debug-enabled?` at the consumer so production DCEs it.

(late-bind/set-fn! :views/reading-render-key reading-render-key)

;; ---- late-bind publication (rf2-9hoos) ------------------------------------
;;
;; `re-frame.subs/subscribe` records each view→sub edge by pushing the
;; query-v into the in-flight render's deref sink (`*view-deref-sink*`).
;; Reaching `record-view-deref!` through late-bind keeps the subs layer
;; (.cljc, must not hard-couple to the substrate) free of a static
;; require on this CLJS-only views ns. Sticky-hook shape (rf2-f72pd): set
;; once at views ns-load, never withdrawn. The subscribe-side call is
;; gated on `interop/debug-enabled?` so production never resolves the
;; hook.

(late-bind/set-fn! :views/record-view-deref! record-view-deref!)

;; ---- late-bind publication (rf2-te71r) -----------------------------------
;;
;; React-hook substrates (UIx / Helix) run this ns's frame-aware-view
;; wrapper inside a function component with no tracked render reaction, so
;; the phase-A (rf2-9hoos) reaction-dispose unmount hook no-ops there
;; (`interop/make-reaction` returns nil). The shared React-hook spine
;; (`re-frame.substrate.spine/make-wrap-view`) arms a `React.useEffect`
;; empty-deps cleanup that emits `:rf.view/unmounted` on instance
;; teardown — restoring unmount parity. Reaching `emit-view-unmounted!`
;; from the spine through late-bind keeps the spine (core/substrate) free
;; of a static require on this CLJS-only views ns (the same edge-avoidance
;; rationale as `:views/record-view-deref!` and `:views/reading-render-
;; key`). Sticky-hook shape (rf2-f72pd): set once at views ns-load, never
;; withdrawn. The spine-side call is gated on `interop/debug-enabled?` (as
;; is `emit-view-unmounted!` itself) so production never resolves the hook.

(late-bind/set-fn! :views/emit-view-unmounted! emit-view-unmounted!)

;; ---- chained fixture-reset step (rf2-9hoos) -------------------------------
;;
;; The `:mount?` discriminator (rf2-9hoos) keys off a per-process
;; seen-render-keys set; a fixture that reuses a render-key across cases
;; would otherwise see the second case's first render reported as a
;; rerender. Enrol `clear-seen-render-keys!` into the existing
;; `:adapter/clear-warn-once-caches!` reset hook (rf2-4edk) so the
;; standard runtime-reset fixture wipes it alongside the warn-once
;; caches — no new fixture wiring needed at call sites. Routed through
;; the canonical governance chokepoint `register-warn-once-clear-fn!`
;; (rf2-z79p8) so this cache is enrolled in the warn-once-clear registry
;; the governance assertion checks.

(late-bind/register-warn-once-clear-fn!
  {:label    :views/seen-render-keys
   :clear-fn clear-seen-render-keys!
   :arm      (fn [] (swap! seen-render-keys conj ::governance-sentinel))
   :armed?   (fn [] (contains? @seen-render-keys ::governance-sentinel))})
