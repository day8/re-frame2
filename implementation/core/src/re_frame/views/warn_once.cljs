(ns re-frame.views.warn-once
  "Warn-once caches and detection helpers for the Reagent-side views ns.
  Per rf2-lh7p — split out of `re-frame.views` so the views file stays
  focused on registration orchestration. Re-frame.views re-exports the
  publicly-referenced surface (`clear-warned-non-dom-roots!`,
  `maybe-warn-plain-fn-under-non-default-frame!`,
  `clear-plain-fn-warned-pairs!`) so existing call sites continue to work
  unchanged.

  Two cohesive warn-once concerns live here:

  1. Non-DOM-root warning (Spec 006 §Source-coord annotation,
     documented exemption). When a reg-view'd component returns a
     non-DOM root (fn/class component or React Fragment) the
     source-coord walk skips the annotation and emits a one-shot
     console.warn per id. Pair tools fall back to the registry's
     `:rf/id`. The `warned-non-dom-roots` defonce holds the per-process
     set; `reset-runtime-fixture` clears it via the chained
     `:adapter/clear-warn-once-caches!` hook per rf2-4edk.

  2. Plain-fn-under-non-default-frame warning (Spec 004 §Plain Reagent
     fns and Spec 006 §706). Detected at subscribe-time; suppression is
     keyed by `[component-id non-default-frame-id]` pairs. Production
     builds elide the entire body via the `interop/debug-enabled?`
     gate."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]
            [re-frame.views.provider :as provider]))

;; ---- non-DOM-root warning (rf2-4edk) -------------------------------------

(defonce ^:private warned-non-dom-roots (atom #{}))

(defn clear-warned-non-dom-roots!
  "Reset the warn-once cache for non-DOM-root warnings. Tests use this
  between cases (via `reset-runtime-fixture` and the chained
  `:adapter/clear-warn-once-caches!` hook) so a sibling test's first-
  encounter warning cannot silently swallow a later test's same-id
  warning. Per rf2-4edk — the cache is a process-wide `defonce` so
  the user-facing warn-once UX is unchanged in production; test-time
  clearing is the only effect."
  []
  (reset! warned-non-dom-roots #{})
  nil)

(defn warn-non-dom-root!
  "Emit a one-shot warning per id that the reg-view'd component returned
  a non-DOM root (a fn/class component, or a React Fragment). Pair tools
  fall back to the registry's `:rf/id`; documented exemption per Spec 006
  §Source-coord annotation."
  [id head]
  (when-not (contains? @warned-non-dom-roots id)
    (swap! warned-non-dom-roots conj id)
    (when (exists? js/console)
      (.warn js/console
        (str "[re-frame] reg-view " id " — root element is "
             (pr-str head) "; data-rf2-source-coord skipped (Spec 006 "
             "§Source-coord annotation: pair tools fall back to :rf/id "
             "for non-DOM roots).")))))

;; ---- plain-fn-under-non-default-frame warning (rf2-d3k3) -----------------
;;
;; Per Spec 004 §Plain Reagent fns and Spec 006 §Plain-fn-under-non-default-
;; frame warning: a plain Reagent fn (not registered via `reg-view`, so
;; without the `^{:contextType frame-context}` metadata `reg-view` attaches)
;; cannot read the surrounding React-context frame. When such a fn renders
;; inside a non-default `frame-provider` and calls `(rf/subscribe ...)` or
;; `(rf/dispatch ...)`, the resolution chain falls through to `:rf/default`
;; — almost certainly not what the author intended.
;;
;; The runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` at
;; most once per `(component-id, non-default-frame-id)` pair, per Spec 004
;; §The footgun is loud, but at most once per (component, non-default-frame)
;; pair. Detection sits at subscribe-time per Spec 006 §706: subscribe
;; consults this helper through the late-bind hook table; the JVM build
;; never sees this ns and the lookup returns nil there.
;;
;; Suppression: a process-wide `defonce` atom set keyed by [component-id
;; frame-id] pairs. The suppression cache survives hot-reload (`defonce`)
;; so repeated re-renders of the same plain fn produce exactly one
;; warning per pair across the JS process. Per Spec 004 §The suppression
;; cache: destroying and re-creating a frame resets the warning history
;; for that frame — implemented by `clear-plain-fn-warned-pairs-for-frame!`
;; which the frame-destroy path can call (today the cache is process-wide
;; and the helper is exported for tests / future destroy-frame integration).
;;
;; Production-elision: every code path is gated on `interop/debug-enabled?`.
;; Under :advanced + `goog.DEBUG=false` the closure compiler constant-folds
;; the gate and the entire detection branch, the trace emission, and the
;; `console.warn` fallback are dead-code-eliminated. Per Spec 009
;; §Production builds.

(defonce ^:private warned-plain-fn-frame-pairs
  ;; Set of `[component-id frame-id]` pairs already warned about. Per
  ;; Spec 004 §The suppression cache.
  (atom #{}))

(defn clear-plain-fn-warned-pairs!
  "Reset the warn-once suppression cache. Tests use this between cases
  so each case starts from a clean slate. Per Spec 004 §The suppression
  cache."
  []
  (reset! warned-plain-fn-frame-pairs #{})
  nil)

(defn- plain-fn-component-id
  "Identify the rendering component for warn-once keying. Reagent
  components carry the user's render-fn as `.-cljsLegacyRender` (form-1)
  or as the bound fn proper. We prefer `displayName` (which Reagent /
  React tend to set for named fns), then fall back to the constructor's
  `name`, then a string repr. The id is opaque text used only as the
  cache key and the warning's `:fn-name` payload — stable across renders
  of the same component, distinct across different component fns."
  [cmp]
  (or (when-let [n (.-displayName ^js cmp)]
        (when (and (string? n) (not= "" n)) n))
      (let [c (.-constructor ^js cmp)
            n (when c (.-name ^js c))]
        (when (and (string? n) (not= "" n) (not= "Object" n)) n))
      (pr-str cmp)))

(defn- read-react-context-frame
  "Read the value the closest enclosing frame-provider has pushed onto
  the shared React context. Per rf2-d4sf this is now a thin wrapper
  around `adapter-context/current-frame` minus the dynamic-var tier —
  the warn-once detection below has already filtered to the case where
  `*current-frame*` is unset (Condition 4), so we want the React-
  context value alone.

  The canonical user-facing surface (`rf/frame-provider`) mounts the
  Provider via Reagent's `:r>` interop head so the props map flows to
  React as a raw JS object — `convert-prop-value` is bypassed and the
  keyword reaches React unchanged. A raw-hiccup mount via
  `[:> (.-Provider frame-context) {:value :foo}]` directly still
  passes through stock Reagent's `convert-prop-value`, which
  stringifies the keyword. The keyword/string coercion below tolerates
  both shapes so the detection logic sees a keyword regardless of how
  the closest Provider was authored. The createContext default —
  `:rf/default` — survives as a keyword because it never passed
  through prop-conversion."
  []
  (let [v (.-_currentValue ^js adapter-context/frame-context)]
    (cond
      (keyword? v)                  v
      (and (string? v) (not= "" v)) (keyword v))))

(defn maybe-warn-plain-fn-under-non-default-frame!
  "Detection per Spec 006 §706 (plain-fn-under-non-default-frame
  warning). Called from `re-frame.subs/subscribe` after frame
  resolution; `resolved-frame-id` is the frame the subscribe call
  routed to.

  Conditions for the warning to fire (all must hold):
    1. We are mid-render — `(current-component)` returns a component.
    2. The component is NOT a reg-view-wrapped one — its `contextType`
       is not the shared `frame-context`. (reg-view-wrapped components
       carry the `:contextType` so they read the surrounding Provider's
       value into `(.-context cmp)`; plain fns do not.)
    3. The closest enclosing Provider names a non-default frame.
    4. The dynamic `*current-frame*` tier is unset — i.e. no `with-frame`
       binding shadows the React-context read. When `with-frame` IS set,
       the plain fn picks up the frame correctly via the dynamic var,
       and no warning is needed.

  Suppression: warn-once per `[component-id non-default-frame-id]` pair.
  Subsequent renders of the same plain fn under the same Provider stay
  silent. Per Spec 004 §at most once per (component, non-default-frame)
  pair.

  Returns nil. Production builds elide the entire body via the
  `interop/debug-enabled?` gate the trace surface itself rides — the
  call site in subs already pays a `(when interop/debug-enabled? ...)`
  test, so this fn is reached only in dev / JVM (where it is unbound
  via late-bind and not called)."
  [resolved-frame-id _query-v]
  (when interop/debug-enabled?
    (let [cmp (provider/current-component)]
      ;; Condition 1: must be mid-render.
      (when (some? cmp)
        ;; Condition 2: component must NOT be reg-view-wrapped. The
        ;; sentinel is the `contextType` static — reg-view's wrapper
        ;; sets it to `frame-context`; plain fns leave it unset (or
        ;; React's empty default).
        (let [ctx-type (some-> ^js cmp .-constructor .-contextType)]
          (when-not (identical? ctx-type adapter-context/frame-context)
            ;; Condition 3: closest enclosing Provider names a
            ;; non-default frame.
            (let [provider-frame (read-react-context-frame)]
              (when (and (keyword? provider-frame)
                         (not= :rf/default provider-frame))
                ;; Condition 4: no with-frame binding shadowing the
                ;; React-context read.
                (when (nil? frame/*current-frame*)
                  (let [fn-id (plain-fn-component-id cmp)
                        pair  [fn-id provider-frame]]
                    (when-not (contains? @warned-plain-fn-frame-pairs pair)
                      (swap! warned-plain-fn-frame-pairs conj pair)
                      (let [reason (str "Plain Reagent fns do not pick "
                                        "up the surrounding frame; their "
                                        "dispatch/subscribe targets "
                                        ":rf/default. To capture the "
                                        "surrounding frame, register the "
                                        "view via reg-view.")]
                        (trace/emit! :warning
                                     :rf.warning/plain-fn-under-non-default-frame-once
                                     {:fn-name        fn-id
                                      :rendered-under provider-frame
                                      :routed-to      resolved-frame-id
                                      :reason         reason
                                      :recovery       :warned-and-replaced})
                        ;; Per Spec 004 §The footgun is loud: in dev,
                        ;; the runtime also `console.warn`s the first
                        ;; occurrence. The trace event is the
                        ;; programmatic surface (10x, re-frame-pair);
                        ;; the console message is the human-eyeballs
                        ;; surface.
                        (when (exists? js/console)
                          (.warn js/console
                            (str "[re-frame] :rf.warning/plain-fn-under-"
                                 "non-default-frame-once — " fn-id
                                 " rendered under " provider-frame
                                 "; subscribe/dispatch routed to "
                                 resolved-frame-id ". " reason)))))))))))))
    nil))

;; Publish through the late-bind hook table so re-frame.subs (a leaf
;; namespace, .cljc, JVM-runnable) can call the helper without
;; statically requiring this CLJS-only ns. JVM builds never load this
;; file; the lookup returns nil there and subs no-ops the warning
;; check. Per re-frame.late-bind §Hook keys.
(late-bind/set-fn! :views/maybe-warn-plain-fn-under-non-default-frame!
                   maybe-warn-plain-fn-under-non-default-frame!)
(late-bind/set-fn! :views/clear-plain-fn-warned-pairs!
                   clear-plain-fn-warned-pairs!)

;; Per rf2-4edk: register a clear of the `warned-non-dom-roots` cache
;; under the chained `:adapter/clear-warn-once-caches!` hook. The hook
;; is chained — each adapter (helix, uix) and this views ns all
;; contribute a clear-step; `reset-runtime-fixture` invokes the top of
;; the chain and every contributor's reset runs. Production behaviour
;; is unchanged: the warn-once `defonce` is still per-process for users;
;; only test-time clearing is new.
(let [previous (late-bind/get-fn :adapter/clear-warn-once-caches!)]
  (late-bind/set-fn! :adapter/clear-warn-once-caches!
    (fn views-clear-warn-once-caches! []
      (clear-warned-non-dom-roots!)
      (when previous (previous))
      nil)))
