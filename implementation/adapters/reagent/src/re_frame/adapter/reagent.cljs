(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Per Spec 006 §CLJS reference:
  Reagent as default adapter.

  Ships in its own Maven artefact (day8/re-frame2-reagent) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use
  Reagent depend on both day8/re-frame2 (core) and this artefact;
  apps targeting a different substrate (UIx, Helix) depend on the
  matching adapter artefact instead. Core does *not* :require this ns —
  the dependency direction is adapter → core.

  Per rf2-uo7v the SSR surface ships in `day8/re-frame2-ssr` —
  this adapter MUST NOT statically `:require [re-frame.ssr]` either,
  because that would drag the SSR namespace, the FNV-1a render-tree-hash
  machinery, the per-request `[:rf/response]` accumulator, and every
  `:rf.ssr/*` / `:rf.server/*` keyword string into every Reagent app's
  bundle even when no server-side rendering is performed. Instead the
  adapter publishes its `set-hiccup-emitter!` callback through the
  late-bind hook table; if the SSR artefact is on the classpath, its
  ns-load resolves the hook and wires the emitter."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.views :as views]))

;; ---- container ------------------------------------------------------------

(defn- make-state-container [initial-value]
  (r/atom initial-value))

(defn- read-container [container]
  @container)

(defn- replace-container! [container new-value]
  (reset! container new-value)
  nil)

(defn- subscribe-container [container on-change]
  (let [k (gensym "rf-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

;; ---- derived (reactions) --------------------------------------------------

(defn- make-derived-value [source-containers compute-fn]
  (ratom/make-reaction
    (fn [] (apply compute-fn (map deref source-containers)))))

;; ---- render ---------------------------------------------------------------

(defn- render [render-tree mount-point opts]
  (let [hydrate? (boolean (:hydrate? opts))]
    (if hydrate?
      (rdc/hydrate-root mount-point render-tree)
      (rdc/render        mount-point render-tree))
    (fn unmount [] (rdc/unmount mount-point))))

(defonce ^:private hiccup-emitter (atom nil))

(defn set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Idempotent."
  [f]
  (reset! hiccup-emitter f))

(defn- render-to-string [render-tree opts]
  ;; Reagent ships server-side rendering via reagent.dom.server — but in
  ;; CLJS browser builds we don't typically render-to-string. Install
  ;; the emitter via set-hiccup-emitter! if you need this in CLJS.
  (if-let [emit @hiccup-emitter]
    (emit render-tree opts)
    (throw (ex-info ":rf.error/no-hiccup-emitter-bound"
                    {:reason "use the plain-atom adapter on the JVM for SSR, or install via set-hiccup-emitter!"
                     :render-tree render-tree}))))

;; ---- context provider -----------------------------------------------------

(defn- register-context-provider [_frame-keyword]
  ;; Implementation lives in re-frame.views (CLJS-only). The frame-
  ;; keyword arg is ignored — `build-frame-provider` is 0-arity
  ;; (rf2-4y60); the returned component takes the frame keyword at
  ;; render time. The arg stays in the substrate signature per
  ;; Spec 006 §Frame-provider via React context.
  (views/build-frame-provider))

;; ---- disposal -------------------------------------------------------------

(defn- dispose-adapter! []
  ;; Reagent's reaction caches GC themselves when their owners go away.
  ;; Nothing special to do here.
  nil)

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  See Spec 006 §CLJS reference: Reagent as default adapter for the
  bridging pseudocode. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site."
  {:make-state-container      make-state-container
   :read-container            read-container
   :replace-container!        replace-container!
   :subscribe-container       subscribe-container
   :make-derived-value        make-derived-value
   :render                    render
   :render-to-string          render-to-string
   :register-context-provider register-context-provider
   :dispose-adapter!          dispose-adapter!})

;; Wire ssr's render-to-string into this adapter's :render-to-string
;; slot. Per rf2-uo7v ssr ships in day8/re-frame2-ssr; this adapter
;; cannot statically `:require [re-frame.ssr]` without dragging the SSR
;; namespace into every Reagent bundle. Publish set-hiccup-emitter!
;; through the late-bind hook table — when the ssr artefact is loaded,
;; its ns-load resolves the hook and wires the emitter through it. When
;; ssr is absent the hook is never consumed and render-to-string raises
;; the "no-hiccup-emitter-bound" error on first call. Per Spec 006
;; §Adapter shipping convention (rf2-0hxm).
(late-bind/set-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

;; Per rf2-d4sf: publish the Reagent-shape React-context-aware
;; `current-frame` impl through the late-bind hook so
;; `re-frame.subs/subscribe` and the dispatch envelope's `:frame`
;; default consult the React-context tier of the 3-tier resolution
;; chain (dynamic var → React context → :rf/default). Before rf2-d4sf
;; those call sites called `re-frame.frame/current-frame` directly,
;; which only honours tiers 1 and 3 — the React-context tier was
;; implemented in `re-frame.views/current-frame` but never reached by
;; subscribe / dispatch, so any subscribe / dispatch under a
;; non-default `frame-provider` silently routed to `:rf/default`.
;;
;; The Reagent impl uses `(.-context cmp)` on the in-flight Reagent
;; component — class-component machinery surfaces context only to
;; components whose `:contextType` matches the context object, so
;; `reg-view*`-wrapped components route to the surrounding provider's
;; frame while plain Reagent fns (without the `:contextType` wiring)
;; fall through to `:rf/default`. That narrowness is what makes
;; `:rf.warning/plain-fn-under-non-default-frame-once` (rf2-d3k3) a
;; meaningful warning — only plain fns route to default, and the
;; warning targets exactly that footgun. UIx / Helix substrates use
;; `re-frame.adapter.context/function-component-current-frame` which
;; reads `_currentValue` directly (function components have no
;; class-context slot).
;;
;; Per rf2-0d35: route `:adapter/current-frame` through the installed
;; adapter rather than blindly overwriting at ns-load time. In test
;; bundles that load multiple adapter ns's (e.g. the in-tree shadow-cljs
;; build, which loads reagent + reagent-slim + uix + helix), each
;; adapter ns publishes its own impl; a plain `set-fn!` means only the
;; last-loaded adapter's reader survives — and an app installed via
;; `(rf/init! reagent/adapter)` then silently uses (say) UIx's
;; `function-component-current-frame` reader, which reads
;; `_currentValue` directly and breaks the
;; `plain-fn-under-non-default-frame-once` warning's narrowness contract
;; (plain Reagent fns should resolve to `:rf/default`, not the
;; Provider's frame). The wrapper consults
;; `(substrate-adapter/current-adapter)` and runs THIS adapter's reader
;; only when this adapter is the installed one; otherwise it chains to
;; the previously-installed reader (which itself does the same active-
;; adapter check for ITS adapter). The chain terminates with
;; `frame/current-frame` when no adapter is installed, preserving the
;; pre-rf2-d4sf headless / pre-init shape.
(let [previous (late-bind/get-fn :adapter/current-frame)]
  (late-bind/set-fn! :adapter/current-frame
    (fn reagent-adapter-current-frame-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (views/current-frame)
        (if previous
          (previous)
          (frame/current-frame))))))

;; Per rf2-wbnl: publish stock Reagent's `current-component` through
;; the late-bind hook so `re-frame.views` can read the in-flight
;; component without statically `:require`ing `reagent.core`. This is
;; what lets the slim adapter (which ships its own `reagent2.core`
;; build) install a different `current-component` reader. The classic
;; bridge wires the hook to stock Reagent's reader; the slim adapter
;; wires it to `reagent2.core/current-component`. Without this hook,
;; views.cljs would always call stock Reagent's reader and miss
;; slim-adapter components in mixed-mode environments.
;;
;; Per rf2-0d35: route through the installed adapter (same pattern as
;; `:adapter/current-frame` above). In test bundles that load BOTH
;; adapter ns's a plain `set-fn!` would mean only the last-loaded
;; adapter's reader survives — and the OTHER adapter's render path
;; silently misses the React-context tier of the frame resolution
;; chain. Symptom (Stage 4-E regression, rf2-0d35): cross-spec
;; `plain-fn-under-non-default-frame` asserted 2 warnings, got 0,
;; because `(current-component)` was reading the slim adapter's
;; `*current-component*` (always nil during stock Reagent renders) so
;; the warning's Condition 1 (some? cmp) failed and no warning fired.
(let [previous (late-bind/get-fn :adapter/current-component)]
  (late-bind/set-fn! :adapter/current-component
    (fn reagent-adapter-current-component-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/current-component)
        (when previous (previous))))))

;; Per rf2-s36l: publish the reactive-substrate surfaces consumed by
;; `re-frame.interop` (`ratom`, `ratom?`, `make-reaction`,
;; `add-on-dispose!`, `dispose!`, `reactive?`, `after-render`) through
;; the late-bind hook table. Before rf2-s36l, `interop.cljs` statically
;; `:require`d `reagent.core` / `reagent.ratom` and forwarded every call
;; to stock Reagent's namespace — which silently misrouted slim-adapter
;; reactions (`reagent2.ratom/Reaction` doesn't reify stock Reagent's
;; `IDisposable`) and broke the counter-slim-and-fast smoke at first
;; `subscribe`. Each adapter ns now publishes its substrate's impls
;; here; this adapter wires the classic bridge to stock Reagent.
;;
;; Per rf2-0d35: route through the installed adapter, mirroring the
;; pattern used by `:adapter/current-frame` and `:adapter/current-
;; component` above. In test bundles that load multiple adapter ns's
;; the last-loaded would otherwise silently win at the hook regardless
;; of which adapter was actually `(rf/init!)`-installed. Each routed
;; hook runs THIS adapter's impl only when this adapter is the
;; installed one; otherwise it chains to the previously-registered
;; reader (which itself does the same active-adapter check for ITS
;; adapter). The chain terminates with nil when no adapter is
;; installed — interop.cljs treats nil cleanly (callers either return
;; nil/false or no-op).
;;
;; UIx and Helix adapters reify stock `reagent.ratom/IDisposable` on
;; their derived values (uix.cljs:121, helix.cljs:124), so they wire
;; their hooks to the same stock-Reagent impls below.
(let [previous (late-bind/get-fn :adapter/ratom)]
  (late-bind/set-fn! :adapter/ratom
    (fn reagent-adapter-ratom-routed [v]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/atom v)
        (when previous (previous v))))))

(let [previous (late-bind/get-fn :adapter/ratom?)]
  (late-bind/set-fn! :adapter/ratom?
    (fn reagent-adapter-ratom?-routed [x]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (satisfies? ratom/IReactiveAtom ^js x)
        (if previous (previous x) false)))))

(let [previous (late-bind/get-fn :adapter/make-reaction)]
  (late-bind/set-fn! :adapter/make-reaction
    (fn reagent-adapter-make-reaction-routed [f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/make-reaction f)
        (when previous (previous f))))))

(let [previous (late-bind/get-fn :adapter/add-on-dispose!)]
  (late-bind/set-fn! :adapter/add-on-dispose!
    (fn reagent-adapter-add-on-dispose!-routed [a-ratom f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/add-on-dispose! a-ratom f)
        (when previous (previous a-ratom f))))))

(let [previous (late-bind/get-fn :adapter/dispose!)]
  (late-bind/set-fn! :adapter/dispose!
    (fn reagent-adapter-dispose!-routed [a-ratom]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/dispose! a-ratom)
        (when previous (previous a-ratom))))))

(let [previous (late-bind/get-fn :adapter/reactive?)]
  (late-bind/set-fn! :adapter/reactive?
    (fn reagent-adapter-reactive?-routed []
      (if (identical? adapter (substrate-adapter/current-adapter))
        (ratom/reactive?)
        (if previous (previous) false)))))

(let [previous (late-bind/get-fn :adapter/after-render)]
  (late-bind/set-fn! :adapter/after-render
    (fn reagent-adapter-after-render-routed [f]
      (if (identical? adapter (substrate-adapter/current-adapter))
        (r/after-render f)
        (when previous (previous f))))))
