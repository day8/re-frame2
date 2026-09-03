(ns re-frame.adapter.reagent
  "Default browser adapter, implementing the Spec 006 substrate contract
  with stock Reagent."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.substrate.spine :as spine]
            [re-frame.views :as views]))

;; ---- exactly-once Reaction disposal (rf2-rzeko) ---------------------------
;;
;; Spec 006 §On-dispose hooks promises disposal that is idempotent and
;; re-entrant safe: every on-dispose callback fires EXACTLY ONCE in
;; registration order, a second `dispose!` is a no-op, and a `dispose!`
;; re-entered from inside a callback cannot recurse. Stock Reagent 2.0.1's
;; `reagent.ratom/dispose!` keeps neither half: it nils `watching`/`state`
;; but leaves `on-dispose` / `on-dispose-arr` intact and re-invokes them on
;; every call, and `Reaction.-remove-watch` AUTO-disposes when the last
;; outward watch drops (with `auto-run` nil). So explicit adapter disposal
;; followed by a host unmount — or the reverse — fires the whole callback
;; set twice (double-releasing sub-cache input refs, repeating user
;; teardown), and a callback that defensively re-enters `dispose!` re-enters
;; the same callback array and can recurse to stack overflow.
;;
;; The guard is adapter-owned and per-Reaction (the reagent-slim Reaction
;; bakes the same snapshot-and-clear pattern into its own `dispose!` —
;; rf2-1bzlai — which an external stock artefact cannot).
;; `install-dispose-guard!` registers a marker as the FIRST
;; `add-on-dispose!` callback AT CONSTRUCTION, before any cache/user
;; callback can register, so on the first disposal — whichever side
;; initiates it, the adapter's disposers or a stock-internal auto-dispose —
;; the marker runs before every later callback: it stamps the terminal
;; disposed marker (so a re-entry through the routed `dispose!` mid-firing
;; sees it) and nils both callback holders. Stock `dispose!` captures
;; `on-dispose-arr` into a local before iterating, so the first firing
;; still completes in registration order; any LATER `dispose!` — including
;; a stock-internal auto-dispose the adapter never sees — finds empty
;; holders and re-fires nothing. `dispose-once!` is the single disposal
;; boundary both the claimed-generation disposer (`make-ratom-spine`'s
;; `:dispose!`) and the routed `:adapter/dispose!` hook delegate through:
;; it consults the same marker and no-ops outright on a marked Reaction.
;;
;; The marker key is a string-keyed expando property: never renamed under
;; `:advanced`, colliding with no Reagent field. The holder writes use the
;; `^clj` field-access idiom this ns already relies on for `.-watching`.

(def ^:private disposed-marker-key "re-frame.adapter.reagent/disposed")

(defn- disposed-marker-set? [reaction]
  (true? (unchecked-get reaction disposed-marker-key)))

(defn- install-dispose-guard!
  "Arm `reaction` with the exactly-once disposal marker (see the section comment
  above). MUST run at construction, before any other callback registers,
  so the marker is first in `on-dispose-arr`. Returns `reaction`."
  [reaction]
  (ratom/add-on-dispose! reaction
    (fn mark-disposed! [reaction]
      (unchecked-set reaction disposed-marker-key true)
      (set! (.-on-dispose ^clj reaction) nil)
      (set! (.-on-dispose-arr ^clj reaction) nil)))
  reaction)

(defn- make-guarded-reaction
  "Stock `ratom/make-reaction` plus the exactly-once disposal guard. The
  construction path for every adapter-created Reaction: the spine's
  `make-derived-value` (sub-cache reactions) and the routed
  `:adapter/make-reaction` hook (`re-frame.interop/make-reaction`)."
  [thunk]
  (install-dispose-guard! (ratom/make-reaction thunk)))

(defn- dispose-once!
  "Dispose `reaction` through stock `ratom/dispose!` unless its exactly-once
  marker shows it already disposed (explicitly, or by stock auto-disposal).
  A Reaction without the marker — one not created through this adapter —
  delegates unconditionally, preserving raw stock behaviour."
  [reaction]
  (when-not (disposed-marker-set? reaction)
    (ratom/dispose! reaction))
  nil)

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; Reagent and reagent-slim share the ratom spine but inject different
;; reactive implementations. The spine must not require either ratom namespace:
;; that dependency direction keeps stock Reagent out of slim bundles.

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Keep generated watch ids attributable in mixed-adapter test bundles.
     :gensym-prefix-sub "rf-reagent-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; adapter-render / dispose-drain pins rely on (capturing the bare
     ;; Var value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :r-atom        (fn [v] (r/atom v))
     ;; Guarded ctor/disposer pair (rf2-rzeko): every Reaction this spine
     ;; creates carries the exactly-once disposal marker, and this
     ;; generation's claimed disposer consults it — see the section above.
     :make-reaction (fn [thunk] (make-guarded-reaction thunk))
     :create-root   (fn [mount-point] (rdc/create-root mount-point))
     :render-root   (fn [root tree] (rdc/render root tree))
     :hydrate-root  (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
     :unmount-root  (fn [root] (rdc/unmount root))
     ;; Cleanup owns this exact substrate dispatch even after the process
     ;; lifecycle's terminal claim closes every public routed hook.
     :disposable?   (fn [a] (satisfies? ratom/IDisposable a))
     :dispose!      (fn [reaction] (dispose-once! reaction))
     ;; Drain Reagent synchronously after `f`; unlike its normal next-tick path,
     ;; this works in backgrounded and headless tabs.
     :flush-render! (fn [f] (f) (r/flush))}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Published through a late-bound hook so `re-frame.ssr` can install it
  without a static adapter-to-SSR dependency."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending Reagent renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (reagent.core/flush)));
  with `f`, runs `f` then the synchronous render drain inside act. Returns
  nil. When act() is unreachable in the current React build it degrades to
  a plain synchronous flush (still runs `f` and drains the render queue),
  so a `:node-test` runner with no real React render path still flushes.
  It publishes a render phase, so do not call it from inside a
  `dispatch-sync` handler (rf2-0c23j)."
  (:flush-views! spine-fns))

;; ---- the client root ------------------------------------------------------
;;
;; rf2-k5r9t. A browser boot needs one React Root for the life of the page:
;; created (or hydrated) once, re-rendered on every hot reload, released on
;; teardown. `client-root` + `render!` + `unmount!` give it that without a
;; caller-owned raw Root or a create/hydrate branch, and the Root they manage
;; is tracked by the SAME active-root ownership as the one-shot substrate
;; `render` slot, so `rf/destroy-adapter!` releases it too. The trio lives in
;; the shared ratom spine; reagent-slim re-exports the same three names.

(def client-root
  "Allocate an inert client-root handle. No DOM work — safe at namespace
  load under a `defonce`, in tests, and on Node. The React Root is created
  (or hydrated) by the first `render!` through it:

      (defonce app-root (reagent-adapter/client-root))

      (defn ^:dev/after-load mount! []
        (when-let [el (js/document.getElementById \"app\")]
          (reagent-adapter/render! app-root
            [rf/frame-root {:id :rf/default :initial-events [[:app/initialise]]}
             [app-view]]
            el)))

  The handle is opaque: hold it, hand it to `render!` and `unmount!`, and
  nothing else. Returns the handle."
  (:client-root spine-fns))

(def render!
  "Render `render-tree` (hiccup) through the client-root `handle` at the DOM
  element `mount-point`. Returns nil.

      (render! handle render-tree mount-point)
      (render! handle render-tree mount-point {:hydrate? true})

  The first call creates the React Root at `mount-point` and renders into
  it — or, with `{:hydrate? true}`, hydrates the server-rendered markup
  already inside `mount-point` (once; Spec 011). Every later call updates
  that same Root with the new tree: no second `create-root`, no second
  hydration. That is what makes the one call both the boot path and the
  `^:dev/after-load` hook. `mount-point` is read on the first call only.

  Backed by the adapter's active-root ownership: `rf/destroy-adapter!`
  releases a Root this handle still holds, exactly once, and a `render!`
  after that mounts afresh."
  (:render-client-root! spine-fns))

(def unmount!
  "Unmount the React Root `handle` holds and return the handle to inert.
  Idempotent: a second call, or a call after `rf/destroy-adapter!` has
  already released the Root, does nothing. Returns nil."
  (:unmount-client-root! spine-fns))

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  Adapter installation is explicit; there is no default-adapter registry.
  `make-ratom-spine` and `make-ratom-adapter` own the logic shared with
  reagent-slim. The Reagent-shaped frame-provider remains injected from
  `re-frame.views`, keeping the spine independent of that component layer."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent
     ;; The returned component receives the frame keyword at render time.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; The spine handles re-frame-owned disposal before these substrate ops.
     :current-frame     views/current-frame
     :current-component r/current-component
     :atom              r/atom
     :ratom?            (fn [x] (satisfies? ratom/IReactiveAtom x))
     ;; Guarded ctor/disposer pair (rf2-rzeko): the routed
     ;; `:adapter/make-reaction` / `:adapter/dispose!` hooks share the
     ;; exactly-once disposal marker with the claimed-generation disposer
     ;; above — one coherent disposal owner per adapter generation.
     :make-reaction     make-guarded-reaction
     ;; rf2-8cnxg — the missing `deref-capture`. A stock `Reaction` learns
     ;; its sources ONLY by being run through `deref-capture`; `ratom/run`
     ;; (its `IRunnable` op) is exactly that run, and after it the reaction
     ;; is on Reagent's ordinary batched push path (`_handle-change` →
     ;; enqueue → `ratom/flush!` → notify). A plain `deref` outside
     ;; `*ratom-context*` deliberately does NOT do this, which is why an
     ;; `add-watch`-only observer — the observation port over a compiled
     ;; ViewCell — never heard from a Reagent-hosted subscription.
     ;;
     ;; Guarded twice, and both guards are load-bearing. `IRunnable` skips
     ;; anything that is not a Reagent `Reaction` (a base `r/atom`, or a
     ;; spine-produced derived value inherited through a cross-substrate
     ;; test bundle — rf2-jicu2). A non-nil `watching` means the reaction is
     ;; ALREADY capturing, so re-running it would recompute a live node for
     ;; nothing; skipping keeps activation idempotent across the second and
     ;; subsequent ViewCells that acquire the same cached node.
     ;; `^clj` on the field read: `watching` is a CLJS deftype field, not a
     ;; JS-object property, so externs inference must not be asked to
     ;; reason about it (an inferred extern would also defeat the very
     ;; renaming that keeps the read correct under `:advanced`).
     :activate-reaction! (fn [reaction]
                            (when (and (satisfies? ratom/IRunnable reaction)
                                       (nil? (.-watching ^clj reaction)))
                              (ratom/run reaction))
                            nil)
     :disposable?       (fn [a] (satisfies? ratom/IDisposable a))
     :add-on-dispose!   ratom/add-on-dispose!
     :dispose!          dispose-once!
     :reactive?         ratom/reactive?
     :after-render      r/after-render}))
