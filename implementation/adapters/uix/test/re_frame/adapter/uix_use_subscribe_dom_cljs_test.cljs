(ns re-frame.adapter.uix-use-subscribe-dom-cljs-test
  "UIx DOM/browser entry-point for the use-subscribe twin of the
  parameterised React-adapter suite (`re-frame.adapter.react-shared-suite`).

  rf2-5or96 folded the UIx/Helix use-subscribe twins (rf2-518sp /
  rf2-7g959 / rf2-mwft2 / rf2-rcgsc) into the shared suite. UIx defines
  its probe components with `uix.core/defui` + `$` + uix hooks —
  substrate macros the suite cannot mint at runtime — so the probe vars,
  their side-channel observation atoms, and the substrate-baked
  frame/query keywords are built HERE and handed to the suite via the cfg
  map (Approach A: components passed in as elements + atoms + keywords).
  The orchestration (reg-frame, dispatch, mount under act, assert) lives
  once in the suite; a gap on UIx is a gap on Helix by construction.

  Coverage forwarded:
    - use-subscribe sees post-dispatch values via useSyncExternalStore
      (rf2-518sp)
    - 1-arg form resolves through the surrounding frame-provider
    - 2-arg form pins an explicit frame, no cross-frame leakage (rf2-rcgsc)
    - sub-cache refcount cleanup on unmount (rf2-7g959)
    - stable-deps-key: one subs/subscribe across N re-renders, unsubscribe
      unmount-only, spy assertions (rf2-mwft2)

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  (ns-regexp `-dom-cljs-test$`) discovers it for the real DOM assertions;
  `:node-test`'s `cljs-test$` regex also matches, where every suite fn
  self-gates on `(browser?)` and no-ops cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- side-channel atoms ----------------------------------------------------
;; Read by the UIx probe components below. The probes are defui top-levels
;; (uix `defui` defines a Var; it cannot sit inside a `let`) and close
;; over these atoms; the suite `reset!`s the atom each assertion cares
;; about at the top of its body.

(def ^:private probe-observed                (atom []))
(def ^:private probe-frame-provider-observed (atom []))
(def ^:private refcount-target               (atom nil))
(def ^:private stable-deps-set-tick          (atom nil))

(defui Probe []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/n])]
    (swap! probe-observed conj v)
    ($ :div (str "n=" v))))

(defui ProbeFrameProvider []
  (let [v (uix-adapter/use-subscribe [:rf.uix-use-subscribe-test/k])]
    (swap! probe-frame-provider-observed conj v)
    ($ :div (str "k=" v))))

(defui ProbeRefcount []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/m])]
    ($ :div (str "m=" v))))

;; ---- Suspense abort-before-commit probes (rf2-es09qq) ---------------------
;; ProbeSuspenseInner calls use-subscribe in its render phase, THEN renders a
;; child that suspends (throws a never-resolving thenable). Under a concurrent
;; root React begins rendering the subtree, runs the use-subscribe render
;; phase, the child suspends, and React commits the Suspense FALLBACK instead
;; — so the use-subscribe-calling fiber NEVER commits (its effects /
;; store-subscribe never run). With the fix the render phase is net-zero on
;; sub-cache ref-count, so the abandoned render leaks nothing.

(defonce ^:private uix-never-resolving-thenable
  ;; A thenable React will treat as a pending Suspense source. It never
  ;; resolves, so the boundary stays on its fallback for the whole test —
  ;; the inner probe's fiber is abandoned before commit and never retried
  ;; to completion within the act() flush.
  #js {:then (fn [_resolve _reject] nil)})

(defui ProbeSuspender []
  (throw uix-never-resolving-thenable))

(defui ProbeSuspenseInner []
  ;; Render-phase use-subscribe acquisition happens HERE, before the
  ;; suspending child unwinds the subtree.
  (let [target @refcount-target
        _v (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/m])]
    ($ :div ($ ProbeSuspender))))

(defn- uix-suspense-abort-element
  "A React `Suspense` boundary (built with React/createElement so we don't
  depend on a substrate suspense helper) wrapping a UIx probe that calls
  use-subscribe then suspends. Returns a React element."
  []
  (React/createElement React/Suspense
                       #js {:fallback ($ :div "fallback")}
                       ($ ProbeSuspenseInner)))

;; ---- sibling-collision probes (rf2-e4pyb) ---------------------------------
;; Two INDEPENDENT siblings reading the SAME query under the SAME frame —
;; they share one cached reaction. Each renders its observed value into a
;; distinct text node so the suite reads "a=N b=N" off the parent's
;; textContent. The bug (hash-of-reaction watch key) leaves the
;; first-mounted sibling stale; the fix (unique per-invocation key) keeps
;; both subscribers' useSyncExternalStore callbacks alive.

(def ^:private siblings-observed-a (atom []))
(def ^:private siblings-observed-b (atom []))

(defui ProbeSiblingA []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-siblings/n])]
    (swap! siblings-observed-a conj v)
    ($ :span (str "a=" v))))

(defui ProbeSiblingB []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-siblings/n])]
    (swap! siblings-observed-b conj v)
    ($ :span (str " b=" v))))

;; ---- 2-arg explicit-pin probes (rf2-rcgsc) --------------------------------

(def ^:private probe-2arg-a-observed (atom []))
(def ^:private probe-2arg-b-observed (atom []))

(defui Probe2ArgA []
  (let [v (uix-adapter/use-subscribe :rf.uix-explicit-pin/tenant-a [:rf.uix-explicit-pin/n])]
    (swap! probe-2arg-a-observed conj v)
    ($ :div (str "a=" v))))

(defui Probe2ArgB []
  (let [v (uix-adapter/use-subscribe :rf.uix-explicit-pin/tenant-b [:rf.uix-explicit-pin/n])]
    (swap! probe-2arg-b-observed conj v)
    ($ :div (str "b=" v))))

;; ---- stable-deps-key probes (rf2-mwft2) -----------------------------------
;; A parent that owns a tick state (used to force re-renders) plus a child
;; that reads a fixed query-v via use-subscribe. The literal
;; `[:rf.uix-stable-deps/p]` vector evaluates to a fresh JS object each
;; render — exactly the shape the bug-without-fix walks into. The parent
;; stashes its set-tick fn into a side-channel atom so the suite can drive
;; forced re-renders from outside.

(defui ProbeStableDepsChild []
  (let [v (uix-adapter/use-subscribe :rf.uix-stable-deps/probe-frame [:rf.uix-stable-deps/p])]
    ($ :div (str "p=" v))))

(defui ProbeStableDepsParent []
  (let [[tick set-tick] (uix/use-state 0)]
    (uix/use-effect
      ;; React state-setters have stable identity across renders so an
      ;; empty deps vec is correct — silences UIx's lint and matches
      ;; React's "set-state setter is stable" guarantee. The effect runs
      ;; once on mount to stash the setter for the test driver.
      (fn [] (reset! stable-deps-set-tick set-tick) js/undefined)
      [])
    ($ :div {:data-tick tick}
       ($ ProbeStableDepsChild))))

;; ---- cfg + forwarded deftests ---------------------------------------------

(def ^:private cfg
  {:adapter               uix-adapter/adapter
   :name                  "UIx"
   ;; rf2-z7hfp / rf2-7kii2 — mount the NATIVE frame-provider component via
   ;; UIx's `$` using the idiomatic TRAILING-CHILDREN shape (no `:children`
   ;; prop-map key), not a direct CLJS-fn invocation.
   :frame-provider-mount-element
   (fn [frame-kw child-el]
     ($ uix-adapter/frame-provider {:frame frame-kw} child-el))
   ;; tracks-app-db
   :probe-element         (fn [] (uix/$ Probe))
   :probe-observed        probe-observed
   :refcount-target       refcount-target
   :us-frame              :rf.uix-use-subscribe-test/probe-frame
   :us-query              :rf.uix-use-subscribe-test/n
   ;; frame-provider 1-arg
   :probe-frame-provider-element  (fn [] (uix/$ ProbeFrameProvider))
   :probe-frame-provider-observed probe-frame-provider-observed
   :frame-provider-frame          :rf.uix-use-subscribe-test/frame-provider-frame
   :frame-provider-query          :rf.uix-use-subscribe-test/k
   ;; rf2-4mi2zj — 1-arg full frame-resolution chain (reuses ProbeFrameProvider
   ;; + :frame-provider-query :k; isolated frame ids per case).
   :provider-tier-frame               :rf.uix-4mi2zj/provider-tier-frame
   :dynamic-precedence-provider-frame :rf.uix-4mi2zj/precedence-provider-frame
   :dynamic-precedence-dynamic-frame  :rf.uix-4mi2zj/precedence-dynamic-frame
   :no-scope-frame                    :rf.uix-4mi2zj/no-scope-frame
   :substrate-kw                      :uix
   ;; 2-arg explicit pin
   :probe-2arg-element    (fn [] (uix/$ :div (uix/$ Probe2ArgA) (uix/$ Probe2ArgB)))
   :probe-2arg-a-observed probe-2arg-a-observed
   :probe-2arg-b-observed probe-2arg-b-observed
   :tenant-a-frame        :rf.uix-explicit-pin/tenant-a
   :tenant-b-frame        :rf.uix-explicit-pin/tenant-b
   :explicit-pin-query    :rf.uix-explicit-pin/n
   ;; refcount cleanup
   :probe-refcount-element (fn [] (uix/$ ProbeRefcount))
   :rc-frame              :rf.uix-use-subscribe-test/refcount-frame
   :rc-query              :rf.uix-use-subscribe-test/m
   ;; rf2-es09qq — Suspense abort-before-commit probe (reuses :rc-frame /
   ;; :rc-query so the abandoned render and the committed control mount race
   ;; on the SAME (frame, query)).
   :probe-suspense-abort-element uix-suspense-abort-element
   ;; sibling-collision (rf2-e4pyb) — both siblings under one parent div so
   ;; the suite reads "a=N b=N" off textContent.
   :probe-siblings-element (fn [] (uix/$ :div (uix/$ ProbeSiblingA) (uix/$ ProbeSiblingB)))
   :siblings-observed-a   siblings-observed-a
   :siblings-observed-b   siblings-observed-b
   :sib-frame             :rf.uix-siblings/frame
   :sib-query             :rf.uix-siblings/n
   ;; stable deps key
   :probe-stable-deps-element (fn [] (uix/$ ProbeStableDepsParent))
   :stable-deps-set-tick  stable-deps-set-tick
   :stable-deps-frame     :rf.uix-stable-deps/probe-frame
   :stable-deps-query     :rf.uix-stable-deps/p
   ;; rf2-40a84 — flush-render! synchronous-commit proof. Reuses the Probe
   ;; (reads :refcount-target for its frame, queries :rf.uix-use-subscribe-test/n)
   ;; under a fresh isolated frame so it can't collide with the use-subscribe
   ;; cases above.
   :fr-frame              :rf.uix-flush-render/probe-frame
   :fr-query              :rf.uix-use-subscribe-test/n})

(deftest use-subscribe-tracks-app-db-changes
  (suite/assert-use-subscribe-tracks-app-db-changes cfg))

(deftest use-subscribe-frame-provider-resolution
  (suite/assert-use-subscribe-frame-provider-resolution cfg))

(deftest use-subscribe-2-arg-pins-explicit-frame
  (suite/assert-use-subscribe-2-arg-pins-explicit-frame cfg))

;; rf2-4mi2zj — 1-arg full frame-resolution chain (the bug: spine fed the
;; raw use-context read into the explicit 2-arg path, bypassing the chain).
(deftest use-subscribe-provider-tier-resolution-ambient-cleared
  (suite/assert-use-subscribe-provider-tier-resolution-ambient-cleared cfg))

(deftest use-subscribe-dynamic-var-precedence-over-provider
  (suite/assert-use-subscribe-dynamic-var-precedence-over-provider cfg))

(deftest use-subscribe-no-provider-no-dynamic-raises-no-frame-context
  (suite/assert-use-subscribe-no-provider-no-dynamic-raises-no-frame-context cfg))

(deftest use-subscribe-cleanup-decrements-sub-cache-refcount
  (suite/assert-use-subscribe-cleanup-decrements-refcount cfg))

;; rf2-e4pyb — two sibling components subscribing to the SAME cached
;; reaction must BOTH receive invalidation after one dispatch (the
;; hash-of-reaction watch key let the last-mounted sibling overwrite the
;; earlier one's useSyncExternalStore callback → stale UI).
(deftest use-subscribe-siblings-same-query-both-invalidate
  (suite/assert-use-subscribe-siblings-same-query-both-invalidate cfg))

;; rf2-nymuy — StrictMode double-mount: the refcount/disposal dance under
;; React's default-dev double-invoke (the riskiest seam, previously
;; untested). Reuses the refcount-probe cfg surface.
(deftest use-subscribe-strictmode-double-mount-refcount-balances
  (suite/assert-use-subscribe-strictmode-double-mount-refcount-balances cfg))

;; rf2-8u8tx.2 — a useMemo factory re-run on unchanged deps (React's
;; documented perf-opt discard) must not leak a sub-cache ref-count.
(deftest use-subscribe-memo-recompute-no-refcount-leak
  (suite/assert-use-subscribe-memo-recompute-no-refcount-leak cfg))

;; rf2-879fe — an abandoned/restarted render that ran use-subscribe before
;; commit must leave no pinned sub-cache ref-count.
(deftest use-subscribe-abandoned-render-no-refcount-leak
  (suite/assert-use-subscribe-abandoned-render-no-refcount-leak cfg))

;; rf2-es09qq — a first-mount render aborted BEFORE commit via Suspense must
;; leak no sub-cache ref-count (the real abort-before-commit path the rf2-879fe
;; ledger could not reach — React discards the never-committed fiber).
(deftest use-subscribe-suspense-abort-before-commit-no-refcount-leak
  (suite/assert-use-subscribe-suspense-abort-before-commit-no-refcount-leak cfg))

(deftest use-subscribe-stable-deps-key
  (suite/assert-use-subscribe-stable-deps-key cfg))

;; rf2-sqhjtu — getSnapshot must deref the durable COMMITTED reaction, not the
;; disposed render-phase handle (the React useSyncExternalStore disposed-
;; reaction hazard). Object-identity proof; reuses the refcount-probe surface.
(deftest use-subscribe-getsnapshot-tracks-committed-reaction
  (suite/assert-use-subscribe-getsnapshot-tracks-committed-reaction cfg))

;; rf2-40a84 — flush-render! synchronously commits a pending render (the
;; proof the pair-MCP headless dispatch→render loop depends on).
(deftest flush-render-synchronously-commits
  (suite/assert-flush-render-synchronously-commits cfg))

;; rf2-gizlj — lock the rf2-cmfln 2-arity contract at the spine cleanup
;; call site (regression: the 3-arity grace-opts shape sneaking back in
;; would break sync-dispose silently).
(deftest use-subscribe-cleanup-calls-unsubscribe-with-2-args
  (suite/assert-use-subscribe-cleanup-calls-unsubscribe-with-2-args cfg))

;; rf2-te71r — :rf.view/unmounted parity for the React-hook spine. The
;; probe view + element are built in the suite (raw React/createElement),
;; so this forwards on :substrate-kw alone (no substrate `defui`/`$`).
(deftest view-unmount-emits-on-react-hook-teardown
  (suite/assert-view-unmount-emits-on-react-hook-teardown
    {:substrate-kw :uix :name "UIx"}))

;; rf2-ghfkkk — a registered view returning a VOID DOM root (<input>) mounts
;; + unmounts with no React void-element warning/error, and still fires
;; exactly one :rf.view/unmounted (the unmount sentinel rides as a Fragment
;; sibling, not a child of the void element). Probe built in the suite.
(deftest void-root-view-unmount-no-warning
  (suite/assert-void-root-view-unmount-no-warning
    {:substrate-kw :uix :name "UIx"}))

;; ---- regression: frame-provider under the idiomatic `$` trailing-children shape (rf2-8svnm / rf2-z7hfp / rf2-7kii2) -
;;
;; The UIx twin of the Helix rf2-9ok1s defect, now pinning the moved-up
;; seam (rf2-z7hfp) AND the unified trailing-children call shape
;; (rf2-7kii2). HISTORY: `frame-provider` used to be a plain re-exported
;; spine CLJS fn (not a `defui`), so UIx's `$` routed it through
;; `uix.compiler.alpha/react-component-element` → `interpret-attrs`, which
;; stringified keyword prop values and DROPPED the namespace — `:frame`
;; silently resolved to `:rf/default` and the subtree rendered nothing. A
;; bespoke un-mangling wrapper (manual `.-uix-component?` marker +
;; `glue-uix-props`) patched it per-adapter.
;;
;; rf2-z7hfp MOVED THE SEAM UP: `frame-provider` is now a NATIVE UIx
;; `defui` component. `$` therefore routes its props through the LOSSLESS
;; `uix-component-element` (`argv`) path by construction (a `defui` is
;; stamped `.-uix-component?` automatically), so keyword frame-ids survive
;; intact with no per-adapter patch.
;;
;; rf2-7kii2 UNIFIED THE CALL SHAPE: children now ride the native `$`
;; TRAILING-ARGS channel — `($ frame-provider {:frame :f} c1 c2)` — exactly
;; as for every other UIx component and mirroring Reagent's trailing hiccup.
;; The old `:children`-in-props-map form (and its silent-drop footgun) is
;; gone. This test mounts the provider via the idiomatic trailing shape
;; with TWO children and asserts BOTH descendant `use-subscribe`s read the
;; WRAPPED frame's value — the structural guarantee that (a) the prop-
;; mangling class cannot reopen and (b) native trailing children propagate
;; the frame and render.

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

(deftest frame-provider-trailing-children-propagate-frame
  (testing "UIx — ($ frame-provider {:frame :f} c1 c2) trailing children propagate :frame + render (rf2-7kii2)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (let [frame-kw :rf.uix-use-subscribe-test/frame-provider-frame
                query-v  [:rf.uix-use-subscribe-test/k]]
            ;; rf2-4mi2zj: clear the fixture's ambient `:rf/default` dynamic
            ;; scope so the 1-arg `use-subscribe` in ProbeFrameProvider
            ;; resolves via the React-context (provider) tier rather than
            ;; reading the shadowing :rf/default frame. See the suite's
            ;; assert-use-subscribe-frame-provider-resolution masking note.
            (binding [frame/*current-frame* nil]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              (reset! probe-frame-provider-observed [])
              (rf/reg-frame frame-kw {:doc "rf2-7kii2 trailing-children frame-provider probe"})
              (rf/reg-event ::dollar-shape-seed (fn [{:keys [db]} _] {:db {:k :wrapped}}))
              (rf/dispatch-sync [::dollar-shape-seed] {:frame frame-kw})
              (rf/reg-sub (first query-v) (fn [db _] (:k db)))
              (let [mount-node (.createElement js/document "div")
                    root       (react-dom-client/createRoot mount-node)]
                (try
                  (act-fn
                    (fn []
                      ;; The idiomatic public call shape — TWO native trailing
                      ;; children (no `:children` key), flowing through UIx's
                      ;; `$` → `glue-args` → the native `defui` shell.
                      (.render root
                        ($ uix-adapter/frame-provider
                           {:frame frame-kw}
                           ($ ProbeFrameProvider)
                           ($ ProbeFrameProvider)))))
                  (is (some #{:wrapped} @probe-frame-provider-observed)
                      "trailing children's use-subscribe read the wrapped frame's value, not :rf/default")
                  (is (= 2 (count (filterv #{:wrapped} @probe-frame-provider-observed)))
                      "both trailing children rendered (not dropped)")
                  (finally
                    (try (.unmount root) (catch :default _ nil))))))))))))
