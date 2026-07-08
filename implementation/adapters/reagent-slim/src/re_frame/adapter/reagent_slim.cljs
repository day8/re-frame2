(ns re-frame.adapter.reagent-slim
  "The day8/reagent-slim adapter — emits the substrate map for
  `re-frame.substrate.adapter` (6 required + 3 optional + 1 lifecycle
  fn). Shape-compatible with the bridge adapter `re-frame.adapter.reagent`;
  internals route through the `reagent2.*` rewrite of Reagent (no
  stock-Reagent dep).

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  See IMPL-SPEC.md §2.1 (adapter map contract), §9 (late-bind hook table),
  §13.1 (artefact-publication shape) and DESIGN-RATIONALE.md."
  (:require [reagent2.core             :as r]
            [reagent2.ratom            :as ratom]
            [reagent2.dom.client       :as rdc]
            [reagent2.impl.template    :as template]
            [re-frame.substrate.spine   :as spine]
            [re-frame.views            :as views]
            ;; Resource-lease helper deps (rf2-zxxc9f). All CORE re-frame
            ;; namespaces — no stock `reagent.*` edge, so bundle isolation
            ;; (IMPL-SPEC §1.8) is preserved (the bridge adapter requires the
            ;; same three). `re-frame.adapter.context` is already on the slim
            ;; classpath transitively via `re-frame.views`.
            [re-frame.frame            :as frame]
            [re-frame.router           :as router]
            [re-frame.adapter.context  :as adapter-context]))

;; ---- shared ratom-spine wiring --------------------------------------------
;;
;; The container quartet, the React-root renderer, the dispose body and
;; the SSR emitter helpers are the SAME shape as the bridge adapter
;; `re-frame.adapter.reagent`, modulo the reactive-atom impl (`reagent2.*`
;; here, stock `reagent.*` there). They live in
;; `re-frame.substrate.spine/make-ratom-spine` (rf2-rzex9) — one
;; implementation, two adapters, zero drift, mirroring the React-hook
;; `make-react-spine` that backs UIx/Helix.
;;
;; CRITICAL — bundle isolation (IMPL-SPEC §1.8 / the
;; `test:reagent-slim:bundle-isolation` gate). The spine MUST NOT
;; `:require` stock `reagent.*`; the reactive-atom ops are INJECTED here
;; as a flat set of bare fns so this adapter's `reagent2.*` requires stay
;; confined to this ns and the spine never names a reactive-atom ns.
;; deps.edn carries zero direct `reagent.*` requires (the slim framing: a
;; re-implementation, not a thin wrapper).
;;
;; rf2-0u5em6: the spine assembles its internal ratom-ops map from these
;; bare fns. This adapter passes ~7 bare fns (flat config keys, mirroring
;; `make-react-spine`'s bare hook fns) instead of a hand-shaped keyword
;; map — earlier this adapter and the bridge one each built a structurally-
;; identical 7-key `:ratom-ops` map differing only by ns-alias, a "keep two
;; maps in lockstep" hazard the flat-bare-fn shape removes.

(def ^:private spine-fns
  (spine/make-ratom-spine
    {;; Substrate-scoped gensym prefix (rf2-l4dmr) so a cross-substrate
     ;; test bundle / log / inspector can attribute a watch to the slim
     ;; adapter rather than confusing it with a stock-Reagent watch.
     :gensym-prefix-sub "rf-reagent-slim-sub-"
     ;; Each op is a thin call-through lambda rather than the bare Var
     ;; value so the spine resolves the namespaced fn at CALL time. This
     ;; keeps the `with-redefs [rdc/create-root …]` test-observability the
     ;; slim render / dispose-drain pins rely on (capturing the bare Var
     ;; value at load time would freeze the original impls past any
     ;; `with-redefs` rebind). Runtime behaviour is identical.
     :r-atom        (fn [v] (r/atom v))
     :make-reaction (fn [thunk] (ratom/make-reaction thunk))
     :create-root   (fn [mount-point] (rdc/create-root mount-point))
     :render-root   (fn [root tree] (rdc/render root tree))
     :hydrate-root  (fn [mount-point tree] (rdc/hydrate-root mount-point tree))
     :unmount-root  (fn [root] (rdc/unmount root))
     ;; rf2-40a84 / rf2-0bz5ah — synchronous render-commit
     ;; op. Runs `f` (which may mutate a ratom / dispatch),
     ;; then drains the rea-queue + forceUpdates every dirty
     ;; component via `reagent2.impl.batching/flush!`, INSIDE
     ;; a `react-dom/flushSync` boundary so the forced
     ;; re-renders COMMIT TO THE DOM synchronously before the
     ;; op returns. Under React 19 `createRoot` a bare
     ;; `forceUpdate` from outside React's batching is
     ;; auto-batched (scheduled, not committed), so the
     ;; boundary is load-bearing — see
     ;; `reagent2.dom.client/flush-render!` for the full
     ;; rationale + the empirical proof
     ;; (`reagent_slim_flush_render_dom_cljs_test`). The
     ;; commit is NOT microtask/rAF-deferred and fires even
     ;; in a backgrounded / headless tab. (Distinct from
     ;; `reagent2.dom.client/flush-views!`, the goog.DEBUG-
     ;; gated act()-composing TEST primitive.)
     :flush-render! rdc/flush-render!}))

(def set-hiccup-emitter!
  "Install the hiccup → HTML fn used by render-to-string. Last call wins.
  Per rf2-uo7v / IMPL-SPEC §2.1: published through the late-bind hook
  `:reagent/set-hiccup-emitter!` so the SSR seam at re-frame.ssr
  resolves it at load time without a static :require."
  (:set-hiccup-emitter! spine-fns))

(def flush-views!
  "Flush pending slim renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act (fn [] (batching/flush!)));
  with `f`, runs `f` then the synchronous render drain inside act.
  Returns nil. No-op when act() is unreachable in the current React build.

  Per rf2-3yij Decision 6 / rf2-b6nm5: the canonical test-flush hook,
  surfaced identically (same name, same ADAPTER-ns location, same
  nil-return shape) across all four substrates — Reagent, reagent-slim,
  UIx, Helix — so a test suite ports across substrates touching only the
  init! Var. This is the canonical convergence: previously the only slim
  flush-views! lived in the SUBSTRATE ns `reagent2.dom.client` and RETURNED
  A PROMISE (the goog.DEBUG-gated microtask→act→microtask Suspense-ordering
  primitive, IMPL-SPEC §4.6), which diverged in both location and return
  type. That substrate-level primitive remains for Suspense-deterministic
  callers; this adapter-ns Var is the cross-substrate canonical surface."
  (:flush-views! spine-fns))

;; ---- resource-lease mount-lifecycle helper (rf2-zxxc9f, EP-0020 §Open Issue #1) --
;;
;; The slim counterpart of `re-frame.adapter.reagent/with-resource-lease` — the
;; fourth browser adapter's copy of EP-0020 leasing, which shipped on the
;; bridge + UIx + Helix but was missed here (rf2-zxxc9f). At publication the
;; slim ns is renamed to the canonical `re-frame.adapter.reagent`, so an app
;; swapping `day8/re-frame2-reagent` → `day8/reagent-slim` MUST find the same
;; `with-resource-lease` Var (else an unresolved-var compile error). Authored
;; to avoid the two bridge-lease bugs FROM THE START: it re-leases on
;; descriptor/frame/cause change via `:component-did-update` (rf2-04fky9) and
;; resolves the frame at RENDER time honouring EP-0002 tier precedence —
;; dynamic-var wins over the React-context tier (rf2-5bo24q). The substrate-
;; agnostic helpers below are the shape of the bridge's; the divergences are
;; the `reagent2.*` create-class + the 7-key-cap contextType wiring (below).

(defonce ^:private lease-token-counter
  ;; Monotone per-runtime counter minting a UNIQUE lease token per mounted
  ;; instance, so two components leasing the same resource+scope hold
  ;; INDEPENDENT leases (releasing one never drops the other).
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

(defn- resolve-lease-frame
  "Resolve the frame this instance leases into, at RENDER time, honouring the
  EP-0002 carried-invariant tier precedence (Spec 002 §Frame target
  resolution): an explicit `:frame` opt pins the target; otherwise delegate
  to the canonical reader `frame/require-current-frame!` — dynamic-var tier
  (`*current-frame*`) FIRST, React-context tier (the `:adapter/current-frame`
  hook — here the class `contextType` read on the in-flight slim component)
  SECOND, else the always-on `:rf.error/no-frame-context`.

  Called from `:reagent-render` so the dynamic var is observed while the
  render scope's `with-frame` binding is still in effect — the render-time,
  single-sourced resolution the React-hook twin `use-resource-lease` uses,
  authored correct here from the start (never carrying the bridge's
  did-mount-resolution / React-context-first inversion, rf2-5bo24q)."
  [explicit-frame]
  (or explicit-frame
      (frame/require-current-frame!
        :with-resource-lease
        {:where 're-frame.adapter.reagent-slim/with-resource-lease})))

(defn- ensure-lease!
  "Dispatch `:rf.resource/ensure` for the render-time-resolved `desired`
  target (`#js {:frame :descriptor :cause}`) under owner `lease`. Returns the
  HELD-lease record (frame + owner + descriptor + cause snapshot) so a later
  re-lease diff and the unmount release act on the SAME target the ensure
  used, even after the render scope has unwound."
  [^js desired lease]
  (let [frame-id (.-frame desired)
        {:keys [resource scope params]} (.-descriptor desired)]
    (router/dispatch! [:rf.resource/ensure
                       {:resource resource :scope scope :params params
                        :owner lease :cause (.-cause desired)}]
                      {:frame frame-id})
    #js {:frame frame-id :lease lease
         :descriptor (.-descriptor desired) :cause (.-cause desired)}))

(defn- release-lease!
  "Dispatch `:rf.resource/release-owner` for the `held` lease into the frame
  it was ensured against."
  [^js held]
  (router/dispatch! [:rf.resource/release-owner {:owner (.-lease held)}]
                    {:frame (.-frame held)}))

(defn- lease-target-changed?
  "True when the render-time `desired` target differs from the currently
  `held` lease on frame, descriptor, or cause — the same
  `[frame descriptor cause]` tuple the React-hook twin keys its effect on."
  [^js held ^js desired]
  (or (not= (.-frame held)      (.-frame desired))
      (not= (.-descriptor held) (.-descriptor desired))
      (not= (.-cause held)      (.-cause desired))))

(def with-resource-lease
  "reagent-slim component that takes a resource liveness LEASE for its mounted
  lifetime (rf2-zxxc9f, EP-0020 §Open Issue #1) — the slim (Form-3)
  counterpart of the UIx / Helix `use-resource-lease` hook and the shape-twin
  of `re-frame.adapter.reagent/with-resource-lease`. On mount it dispatches
  `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on unmount it
  releases that lease via `:rf.resource/release-owner`. Use it so a view
  declaratively OWNS a polled / cached resource for as long as it is mounted.

  Call it as a component with the resource descriptor and a body thunk:

      [reagent-slim/with-resource-lease
       {:resource :my/feed :scope :rf.scope/global :params {:page 0}}
       (fn [] [feed-view])]

  Or with opts (a map between the descriptor and the body thunk):

      [reagent-slim/with-resource-lease
       {:resource :my/feed :scope … :params …}
       {:cause :dashboard-widget :frame :some-frame}
       (fn [] [feed-view])]

  `descriptor` is the resource-instance identity `{:resource :scope :params}`
  (the ensure payload's read keys, Spec 016 §Events). `opts`:
    :cause  — recorded on the ensure (observability; free-form data value).
              Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing ambient
              frame-provider / dynamic-var resolution.

  Frame resolution + timing (EP-0002 tier precedence): the lease frame is
  resolved in `:reagent-render` via `resolve-lease-frame` — explicit `:frame`
  opt, else `frame/require-current-frame!` (dynamic-var FIRST, then the
  React-context tier). Resolving at RENDER time keeps the dynamic-var tier
  able to win and mirrors the twin.

  Re-lease on change: a `:component-did-update` diffs the render-time
  `[frame descriptor cause]` against the held lease and, on any change,
  RELEASES the old lease then ENSURES the new target (same token) — so a
  descriptor whose `:params` change across re-renders releases the old
  resource and ensures the new one WITHOUT waiting for unmount; a value-equal
  descriptor holds ONE lease with no churn.

  Idempotency: the lease token is minted ONCE per instance and reused across
  re-leases, so a hot-reload re-mount settles to exactly one held lease.
  Under SSR (`render-to-string`) lifecycle methods do not run, so the
  acquire/release is a natural no-op — a client-lifetime concern."
  ;; The slim 7-key create-class cap (IMPL-SPEC §6.1) excludes `:context-type`,
  ;; so React contextType is wired directly on the returned class — mirroring
  ;; `reagent2.impl.template/fn-to-class`'s `:contextType` threading and the
  ;; reg-view `{:contextType frame-context}` wiring, so the in-flight
  ;; component's `.-context` carries the enclosing frame-provider's frame for
  ;; `resolve-lease-frame`'s React-context tier.
  (let [klass
        (r/create-class
          {:display-name "re-frame.adapter.reagent/with-resource-lease"
           :reagent-render
           (fn [d & args]
             (let [{:keys [cause descriptor frame body]} (lease-args (cons d args))
                   frame-id (resolve-lease-frame frame)]
               ;; Stash the render-time-resolved lease target so the commit-
               ;; phase lifecycle methods take + re-lease against it.
               (set! (.-rf2LeaseDesired ^js (r/current-component))
                     #js {:frame frame-id :descriptor descriptor :cause cause})
               (body)))
           :component-did-mount
           (fn [^js this]
             ;; Mint the per-instance lease token ONCE (reused across
             ;; re-leases, matching the twin's stable useRef token).
             (let [lease [:lease (swap! lease-token-counter inc)]]
               (set! (.-rf2ResourceLease this)
                     (ensure-lease! (.-rf2LeaseDesired this) lease))))
           ;; slim `:component-did-update` is invoked (this prev-argv snapshot)
           ;; per IMPL-SPEC §6.6 — a 3-arity signature (the bridge's stock-
           ;; Reagent path is 4-arity). We read the render-time stash, not the
           ;; prev args, so the extra args are ignored.
           :component-did-update
           (fn [^js this _prev-argv _snapshot]
             (let [held    (.-rf2ResourceLease this)
                   desired (.-rf2LeaseDesired this)]
               (when (and held desired (lease-target-changed? held desired))
                 (release-lease! held)
                 (set! (.-rf2ResourceLease this)
                       (ensure-lease! desired (.-lease held))))))
           :component-will-unmount
           (fn [^js this]
             (when-let [held (.-rf2ResourceLease this)]
               (release-lease! held)))})]
    (set! (.-contextType ^js klass) adapter-context/frame-context)
    klass))

(def adapter
  "The reagent-slim adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.reagent-slim :as reagent-slim])
      (rf/init! reagent-slim/adapter)

  Drop-in shape-compatible with `re-frame.adapter.reagent/adapter` per
  IMPL-SPEC §2.1 — the only difference is the substrate, not the keys.
  Per Spec 006 §CLJS reference + rf2-agql: there is no default-adapter
  registry; adapter wiring is explicit at the call site.

  The container quartet, renderer, render-to-string and dispose body
  come from `spine/make-ratom-spine` (rf2-rzex9, shared with the bridge
  Reagent adapter under an injected `reagent2.*` bare-fn set); the adapter
  map + the nine-call ratom-family `route-hook!` table + the chained
  SSR-emitter install are assembled by `spine/make-ratom-adapter`
  (rf2-ee38b.1, also shared with the bridge adapter under an injected
  `reagent2.*` bare-fn hook set). `register-context-provider` is passed in
  (NOT spine-built) because it is the Reagent-component-shaped frame-
  provider from `re-frame.views`, distinct from the React-hook spine's
  hook-shaped one — keeping the core spine free of a spine→views edge.
  CRITICAL: bundle isolation is preserved — the hook ops are injected
  `reagent2.*` impls (flat bare-fn config keys, rf2-0u5em6), so the spine
  names no reactive-atom ns (the `test:reagent-slim:bundle-isolation` gate
  by construction). Per-hook rationale lives at `spine/make-ratom-adapter`."
  (spine/make-ratom-adapter
    spine-fns
    {:kind :rf.adapter/reagent-slim
     ;; The frame-keyword arg is ignored — `build-frame-provider` is
     ;; 0-arity (rf2-4y60); the returned component takes the frame keyword
     ;; at render time. Per IMPL-SPEC §9.4: views.cljs continues to back
     ;; the frame-provider; the rewrite doesn't replace it.
     :register-context-provider (fn [_frame-keyword] (views/build-frame-provider))
     ;; Injected reagent2.* ops for the ratom-family late-bind hooks (flat
     ;; bare-fn config keys, rf2-0u5em6 — the spine assembles the route-hook
     ;; table from them). Wiring reagent2.* impls is load-bearing (rf2-s36l):
     ;; without this seam the first (interop/add-on-dispose! ...) under the
     ;; slim adapter threw because reagent2.ratom/Reaction does NOT reify
     ;; stock Reagent's IDisposable. rf2-jicu2: the dual-IDisposable dispatch
     ;; (re-frame-owned first, then reagent2's) is handled inside
     ;; make-ratom-adapter — this adapter supplies the substrate-side
     ;; `disposable?` predicate + `add-on-dispose!` / `dispose!` impls.
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

;; ---- warn-once cache reset wiring (rf2-qy6cl) -----------------------------
;;
;; The slim hiccup interpreter carries a second warn-once cache beyond the
;; spine's source-coord/non-DOM-root one: `reagent2.impl.template`'s
;; `warned-keyword-prop` defonce (the §7.2 D2 keyword-on-non-HTML-prop
;; notice). The source-coord cache is already chained into
;; `:adapter/clear-warn-once-caches!` by `make-ratom-adapter` (rf2-4edk);
;; the keyword-prop cache was left behind. Chain its clear-step here too —
;; via the same `spine/install-clear-warn-once-step!` helper — so
;; `make-reset-runtime-fixture` re-arms BOTH slim caches between tests and
;; a sibling test cannot silently swallow a later same-pair warning. This
;; lives in the adapter ns (not in reagent2.impl.template) to keep the
;; vendored `reagent2.*` tree free of any `re-frame.*` edge — bundle
;; isolation by construction.
;;
;; rf2-z79p8: enrol with an explicit label but NO arm/armed? probes — the
;; cache atom is `^:private` in reagent2.impl.template and reaching into it
;; from here would breach the same bundle-isolation rule. The empirical
;; governance assertion skips probe-less entries; the source-enumeration
;; assertion and the dedicated template_keyword_prop re-arm test cover this
;; cache.
(spine/install-clear-warn-once-step! template/clear-warned-keyword-prop!
                                     {:label :reagent-slim/warned-keyword-prop})
