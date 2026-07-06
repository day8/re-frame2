(ns re-frame.adapter.reagent
  "The Reagent adapter — browser default. Implements the substrate
  contract; see `re-frame.substrate.adapter` for the contract itself
  (canonical home: `core/src/re_frame/substrate/adapter.cljc`) and
  Spec 006 §CLJS reference for the per-slot semantics."
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.substrate.spine :as spine]
            [re-frame.views :as views]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; The container quartet, the React-root renderer, the dispose body and
;; the SSR emitter helpers are the SAME shape as reagent-slim, modulo the
;; reactive-atom impl (stock `reagent.*` here, `reagent2.*` there). They
;; live in `re-frame.substrate.spine/make-ratom-spine` (rf2-rzex9) — one
;; implementation, two adapters, zero drift, mirroring the React-hook
;; `make-react-spine` that backs UIx/Helix. The reactive-atom ops are
;; INJECTED here as a flat set of bare fns so the spine never `:require`s a
;; reactive-atom ns: this adapter passes its stock `reagent.*` impls; the
;; slim adapter passes `reagent2.*`. (Load-bearing for reagent-slim bundle
;; isolation — the slim adapter's deps.edn carries zero direct `reagent.*`
;; requires.)
;;
;; rf2-0u5em6: the spine assembles its internal ratom-ops map from these
;; bare fns. Each adapter passes ~7 bare fns (flat config keys, mirroring
;; `make-react-spine`'s bare hook fns) instead of a hand-shaped keyword
;; map — earlier this adapter and the slim one each built a structurally-
;; identical 7-key `:ratom-ops` map differing only by ns-alias, a "keep two
;; maps in lockstep" hazard the flat-bare-fn shape removes.

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Substrate-scoped gensym prefix (rf2-l4dmr) so a cross-substrate
     ;; test bundle / log / inspector can attribute a watch to the
     ;; Reagent adapter rather than confusing it with a slim watch.
     :gensym-prefix-sub "rf-reagent-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; adapter-render / dispose-drain pins rely on (capturing the bare
     ;; Var value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :r-atom        (fn [v] (r/atom v))
     :make-reaction (fn [thunk] (ratom/make-reaction thunk))
     :create-root   (fn [mount-point] (rdc/create-root mount-point))
     :render-root   (fn [root tree] (rdc/render root tree))
     :hydrate-root  (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
     :unmount-root  (fn [root] (rdc/unmount root))
     ;; rf2-40a84 — synchronous render-flush op. Run `f`
     ;; (which may mutate a ratom / dispatch), then
     ;; `reagent.core/flush` drains Reagent's render queue
     ;; SYNCHRONOUSLY — bypassing the rAF-scheduled
     ;; `next-tick` drain — and (on React 19) commits the
     ;; forced component re-renders to the DOM via
     ;; react-dom/flushSync. NOT rAF-scheduled ⇒ fires even
     ;; in a backgrounded / headless tab.
     :flush-render! (fn [f] (f) (r/flush))}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending Reagent renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (reagent.core/flush)));
  with `f`, runs `f` then the synchronous render drain inside act. Returns
  nil. When act() is unreachable in the current React build it degrades to
  a plain synchronous flush (still runs `f` and drains the render queue),
  so a `:node-test` runner with no real React render path still flushes.

  Per rf2-3yij Decision 6 / rf2-b6nm5: the canonical test-flush hook,
  surfaced identically (same name, same adapter-ns location, same
  nil-return shape) across all four substrates — Reagent, reagent-slim,
  UIx, Helix — so a test suite ports across substrates touching only the
  init! Var. Previously stock Reagent surfaced no adapter-level flush-views!
  and tests reached for reagent.core/flush or react/act directly."
  (:flush-views! spine-fns))

;; ---- resource-lease mount-lifecycle helper (rf2-cxozh4, EP-0020 §Open Issue #1) --

(defonce ^:private lease-token-counter
  ;; Monotone per-runtime counter minting a UNIQUE lease token per mounted
  ;; `with-resource-lease` instance, so two components leasing the same
  ;; resource+scope hold INDEPENDENT leases (releasing one never drops the
  ;; other). The Reagent twin of the React-hook helper's counter.
  (atom 0))

(defn- lease-args
  "Normalise `with-resource-lease` call args `[descriptor & args]` into
  `{:descriptor :cause :frame :body}`. The optional `opts` map may sit
  between the descriptor and the body thunk; a leading fn is the body."
  [[descriptor & args]]
  (let [[opts body] (if (fn? (first args))
                      [nil (first args)]
                      [(first args) (second args)])]
    {:descriptor descriptor
     :cause      (get opts :cause [:lease :mount])
     :frame      (get opts :frame)
     :body       body}))

(defn- lease-frame-from-context
  "Resolve the frame this instance should lease into: an explicit `:frame`
  opt wins; else the frame-provider's keyword read off the instance's React
  context (`:contextType` = the shared frame-context; the no-provider
  sentinel coerces to nil); else the dynamic-var tier; else a loud
  `:rf.error/no-frame-context`. Mirrors the React-hook helper's resolution
  contract, in the Reagent (class-context) idiom."
  [^js this explicit-frame]
  (or explicit-frame
      (let [ctx (.-context this)]
        (when-not (= ctx adapter-context/no-provider-sentinel)
          (adapter-context/coerce-context-value ctx)))
      frame/*current-frame*
      (frame/require-current-frame!
        :with-resource-lease
        {:where 're-frame.adapter.reagent/with-resource-lease})))

(def with-resource-lease
  "Reagent component that takes a resource liveness LEASE for its mounted
  lifetime (rf2-cxozh4, EP-0020 §Open Issue #1) — the Reagent (Form-3)
  counterpart of the UIx / Helix `use-resource-lease` hook. On mount it
  dispatches `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on
  unmount it releases that lease via `:rf.resource/release-owner`. Use it so
  a view declaratively OWNS a polled / cached resource for as long as it is
  mounted — the mount-lifecycle half of resource ownership that otherwise
  needs a hand-wired Form-3.

  Call it as a Reagent component with the resource descriptor and a body
  thunk (a 0-arg fn returning hiccup — the children rendered while the lease
  is held):

      [reagent/with-resource-lease
       {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
       (fn [] [feed-view])]

  Or with opts (a map between the descriptor and the body thunk):

      [reagent/with-resource-lease
       {:resource :my/feed :scope … :params …}
       {:cause :dashboard-widget :frame :some-frame}
       (fn [] [feed-view])]

  `descriptor` is the resource-instance identity `{:resource :scope
  :params}` (the ensure payload's read keys, Spec 016 §Events). `opts`:
    :cause  — recorded on the ensure (observability; free-form data value).
              Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing ambient
              frame-provider / dynamic-var resolution.

  A single Form-3 `create-class` (NOT a Form-2 returning one — that would
  mint a fresh component type per render and remount). It declares
  `:context-type` = the shared frame-context (via
  `re-frame.adapter.context/frame-context`) so the instance receives the
  surrounding `frame-provider`'s frame — the same wiring `reg-view` uses —
  and resolves the lease frame in `:component-did-mount` (explicit `:frame`
  opt → React-context → dynamic-var → loud `:rf.error/no-frame-context`),
  stashing it so `:component-will-unmount` releases into the SAME frame even
  though it runs after the render scope has unwound.

  Idempotency: the lease token is minted ONCE per instance (stashed on the
  instance in `did-mount`), so a hot-reload re-mount settles to exactly one
  held lease (ensure re-attaches the same lease; Spec 016 §Active owners).
  Under SSR (`render-to-string`) lifecycle methods do not run, so the
  acquire/release is a natural no-op — the lease is a client-lifetime
  concern."
  (r/create-class
    {:display-name "re-frame.adapter.reagent/with-resource-lease"
     ;; Reagent's create-class copies `:context-type` (camelCased to the
     ;; React static `contextType`) onto the class, so `(.-context this)`
     ;; carries the enclosing frame-provider's value — the class-component
     ;; parity of the reg-view `{:contextType frame-context}` wiring.
     :context-type adapter-context/frame-context
     :component-did-mount
     (fn [^js this]
       (let [{:keys [descriptor cause frame]} (lease-args (rest (r/argv this)))
             {:keys [resource scope params]}  descriptor
             frame-id (lease-frame-from-context this frame)
             token    (swap! lease-token-counter inc)
             lease    [:lease token]]
         ;; Stash the per-instance lease + frame so unmount releases exactly
         ;; this instance's lease into the same frame.
         (set! (.-rf2ResourceLease this) #js {:frame frame-id :lease lease})
         (rf/dispatch* [:rf.resource/ensure
                        {:resource resource :scope scope :params params
                         :owner lease :cause cause}]
                       {:frame frame-id})))
     :component-will-unmount
     (fn [^js this]
       (when-let [held (.-rf2ResourceLease this)]
         (rf/dispatch* [:rf.resource/release-owner {:owner (.-lease held)}]
                       {:frame (.-frame held)})))
     :reagent-render
     (fn [_descriptor & args]
       ((:body (lease-args (cons _descriptor args)))))}))

(def adapter
  "The Reagent adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent :as reagent])
      (rf/init! reagent/adapter)

  See Spec 006 §CLJS reference: Reagent as default adapter for the
  bridging pseudocode. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site.

  The container quartet, renderer, render-to-string and dispose body
  come from `spine/make-ratom-spine` (rf2-rzex9, shared with
  reagent-slim); the adapter map + the nine-call ratom-family
  `route-hook!` table + the chained SSR-emitter install are assembled by
  `spine/make-ratom-adapter` (rf2-ee38b.1, also shared with reagent-slim).
  `register-context-provider` is passed in (NOT spine-built) because it
  is the Reagent-component-shaped frame-provider from `re-frame.views`,
  distinct from the React-hook spine's hook-shaped one — keeping the core
  spine free of a spine→views dependency edge. That provider keeps
  children as trailing-positional hiccup (`[provider frame-kw & children]`)
  — Reagent's idiomatic shape and the cross-substrate baseline the UIx /
  Helix `$`-trailing-children providers were aligned toward (rf2-7kii2,
  which moved only those React-hook adapters; the Reagent shape is
  unchanged).

  The hook ops are injected stock-`reagent.*` impls (flat bare-fn config
  keys, rf2-0u5em6) so the spine never names a reactive-atom ns (bundle
  isolation, identical to `make-ratom-spine`'s bare-fn contract). Per-hook
  rationale lives at `spine/make-ratom-adapter`."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent
     ;; The frame-keyword arg is ignored — `build-frame-provider` is
     ;; 0-arity (rf2-4y60); the returned component takes the frame keyword
     ;; at render time. The arg stays in the substrate signature per
     ;; Spec 006 §Frame-provider via React context.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; Injected stock-Reagent ops for the ratom-family late-bind hooks
     ;; (flat bare-fn config keys, rf2-0u5em6 — the spine assembles the
     ;; route-hook table from them). rf2-jicu2: the dual-IDisposable
     ;; dispatch (re-frame-owned first, then the substrate's) is handled
     ;; inside make-ratom-adapter — this adapter supplies only the
     ;; substrate-side `disposable?` predicate + `add-on-dispose!` /
     ;; `dispose!` impls.
     :current-frame     views/current-frame
     :current-component r/current-component
     :atom              r/atom
     :ratom?            (fn [x] (satisfies? ratom/IReactiveAtom x))
     :make-reaction     ratom/make-reaction
     :disposable?       (fn [a] (satisfies? ratom/IDisposable a))
     :add-on-dispose!   ratom/add-on-dispose!
     :dispose!          ratom/dispose!
     :reactive?         ratom/reactive?
     :after-render      r/after-render}))
