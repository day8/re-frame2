(ns re-frame.adapter.helix-use-subscribe-dom-cljs-test
  "Helix DOM/browser entry-point for the use-subscribe twin of the
  parameterised React-adapter suite (`re-frame.adapter.react-shared-suite`).

  rf2-5or96 folded the UIx/Helix use-subscribe twins (rf2-518sp /
  rf2-7g959 / rf2-mwft2 / rf2-y0db2 — parity with UIx's rf2-rcgsc) into
  the shared suite. Helix defines its probe components with
  `helix.core/defnc` + `$` + `helix.dom` + `helix.hooks` — substrate
  macros the suite cannot mint at runtime — so the probe vars, their
  side-channel observation atoms, and the substrate-baked frame/query
  keywords are built HERE and handed to the suite via the cfg map
  (Approach A: components passed in as elements + atoms + keywords). The
  orchestration (reg-frame, dispatch, mount under act, assert) lives once
  in the suite; a gap on Helix is a gap on UIx by construction.

  Coverage forwarded:
    - use-subscribe sees post-dispatch values via useSyncExternalStore
      (rf2-518sp)
    - 1-arg form resolves through the surrounding frame-provider
    - 2-arg form pins an explicit frame, no cross-frame leakage (rf2-y0db2)
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
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [helix.hooks :as helix-hooks]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.adapter.react-shared-suite :as suite]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- side-channel atoms ----------------------------------------------------
;; Read by the Helix probe components below. The probes are defnc
;; top-levels (helix `defnc` defines a Var; it cannot sit inside a `let`)
;; and close over these atoms; the suite `reset!`s the atom each assertion
;; cares about at the top of its body.

(def ^:private probe-observed                (atom []))
(def ^:private probe-frame-provider-observed (atom []))
(def ^:private refcount-target               (atom nil))
(def ^:private stable-deps-set-tick          (atom nil))

(defnc Probe []
  (let [target @refcount-target
        v (helix-adapter/use-subscribe target [:rf.helix-use-subscribe-test/n])]
    (swap! probe-observed conj v)
    (d/div (str "n=" v))))

(defnc ProbeFrameProvider []
  (let [v (helix-adapter/use-subscribe [:rf.helix-use-subscribe-test/k])]
    (swap! probe-frame-provider-observed conj v)
    (d/div (str "k=" v))))

(defnc ProbeRefcount []
  (let [target @refcount-target
        v (helix-adapter/use-subscribe target [:rf.helix-use-subscribe-test/m])]
    (d/div (str "m=" v))))

;; ---- Suspense abort-before-commit probes (rf2-es09qq) ---------------------
;; ProbeSuspenseInner calls use-subscribe in its render phase, THEN renders a
;; child that suspends (throws a never-resolving thenable). Under a concurrent
;; root React begins rendering the subtree, runs the use-subscribe render
;; phase, the child suspends, and React commits the Suspense FALLBACK instead
;; — so the use-subscribe-calling fiber NEVER commits (its effects /
;; store-subscribe never run). With the fix the render phase is net-zero on
;; sub-cache ref-count, so the abandoned render leaks nothing.

(defonce ^:private helix-never-resolving-thenable
  ;; A thenable React treats as a pending Suspense source; it never resolves
  ;; so the boundary stays on its fallback for the whole test.
  #js {:then (fn [_resolve _reject] nil)})

(defnc ProbeSuspender []
  (throw helix-never-resolving-thenable))

(defnc ProbeSuspenseInner []
  ;; Render-phase use-subscribe acquisition happens HERE, before the
  ;; suspending child unwinds the subtree.
  (let [target @refcount-target
        _v (helix-adapter/use-subscribe target [:rf.helix-use-subscribe-test/m])]
    (d/div ($ ProbeSuspender))))

(defn- helix-suspense-abort-element
  "A React `Suspense` boundary (built with React/createElement so we don't
  depend on a substrate suspense helper) wrapping a Helix probe that calls
  use-subscribe then suspends. Returns a React element."
  []
  (React/createElement React/Suspense
                       #js {:fallback (d/div "fallback")}
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

(defnc ProbeSiblingA []
  (let [target @refcount-target
        v (helix-adapter/use-subscribe target [:rf.helix-siblings/n])]
    (swap! siblings-observed-a conj v)
    (d/span (str "a=" v))))

(defnc ProbeSiblingB []
  (let [target @refcount-target
        v (helix-adapter/use-subscribe target [:rf.helix-siblings/n])]
    (swap! siblings-observed-b conj v)
    (d/span (str " b=" v))))

;; ---- 2-arg explicit-pin probes (rf2-y0db2 — parity with UIx's rf2-rcgsc) --

(def ^:private probe-2arg-a-observed (atom []))
(def ^:private probe-2arg-b-observed (atom []))

(defnc Probe2ArgA []
  (let [v (helix-adapter/use-subscribe :rf.helix-explicit-pin/tenant-a [:rf.helix-explicit-pin/n])]
    (swap! probe-2arg-a-observed conj v)
    (d/div (str "a=" v))))

(defnc Probe2ArgB []
  (let [v (helix-adapter/use-subscribe :rf.helix-explicit-pin/tenant-b [:rf.helix-explicit-pin/n])]
    (swap! probe-2arg-b-observed conj v)
    (d/div (str "b=" v))))

;; ---- stable-deps-key probes (rf2-mwft2) -----------------------------------
;; A parent that owns a tick state (used to force re-renders) plus a child
;; that reads a fixed query-v via use-subscribe. The literal
;; `[:rf.helix-stable-deps/p]` vector evaluates to a fresh JS object each
;; render — exactly the shape the bug-without-fix walks into. The parent
;; stashes its set-tick fn into a side-channel atom so the suite can drive
;; forced re-renders from outside.

(defnc ProbeStableDepsChild []
  (let [v (helix-adapter/use-subscribe :rf.helix-stable-deps/probe-frame [:rf.helix-stable-deps/p])]
    (d/div (str "p=" v))))

(defnc ProbeStableDepsParent []
  (let [[tick set-tick] (helix-hooks/use-state 0)]
    (helix-hooks/use-effect
      ;; React state-setters have stable identity across renders so an
      ;; empty deps vec is correct — matches React's "set-state setter is
      ;; stable" guarantee. The effect runs once on mount to stash the
      ;; setter for the test driver.
      []
      (reset! stable-deps-set-tick set-tick)
      (fn cleanup [] nil))
    (d/div {:data-tick tick}
           ($ ProbeStableDepsChild))))

;; ---- cfg + forwarded deftests ---------------------------------------------

(def ^:private cfg
  {:adapter               helix-adapter/adapter
   :name                  "Helix"
   ;; rf2-z7hfp / rf2-7kii2 — mount the NATIVE frame-provider component via
   ;; Helix's `$` using the idiomatic TRAILING-CHILDREN shape (no
   ;; `:children` prop-map key), not a direct CLJS-fn invocation.
   :frame-provider-mount-element
   (fn [frame-kw child-el]
     ($ helix-adapter/frame-provider-existing {:frame frame-kw} child-el))
   ;; tracks-app-db
   :probe-element         (fn [] ($ Probe))
   :probe-observed        probe-observed
   :refcount-target       refcount-target
   :us-frame              :rf.helix-use-subscribe-test/probe-frame
   :us-query              :rf.helix-use-subscribe-test/n
   ;; frame-provider 1-arg
   :probe-frame-provider-element  (fn [] ($ ProbeFrameProvider))
   :probe-frame-provider-observed probe-frame-provider-observed
   :frame-provider-frame          :rf.helix-use-subscribe-test/frame-provider-frame
   :frame-provider-query          :rf.helix-use-subscribe-test/k
   ;; rf2-4mi2zj — 1-arg full frame-resolution chain (reuses ProbeFrameProvider
   ;; + :frame-provider-query :k; isolated frame ids per case).
   :provider-tier-frame               :rf.helix-4mi2zj/provider-tier-frame
   :dynamic-precedence-provider-frame :rf.helix-4mi2zj/precedence-provider-frame
   :dynamic-precedence-dynamic-frame  :rf.helix-4mi2zj/precedence-dynamic-frame
   :no-scope-frame                    :rf.helix-4mi2zj/no-scope-frame
   :substrate-kw                      :helix
   ;; 2-arg explicit pin
   :probe-2arg-element    (fn [] ($ :div ($ Probe2ArgA) ($ Probe2ArgB)))
   :probe-2arg-a-observed probe-2arg-a-observed
   :probe-2arg-b-observed probe-2arg-b-observed
   :tenant-a-frame        :rf.helix-explicit-pin/tenant-a
   :tenant-b-frame        :rf.helix-explicit-pin/tenant-b
   :explicit-pin-query    :rf.helix-explicit-pin/n
   ;; refcount cleanup
   :probe-refcount-element (fn [] ($ ProbeRefcount))
   :rc-frame              :rf.helix-use-subscribe-test/refcount-frame
   :rc-query              :rf.helix-use-subscribe-test/m
   ;; rf2-es09qq — Suspense abort-before-commit probe (reuses :rc-frame /
   ;; :rc-query so the abandoned render and the committed control mount race
   ;; on the SAME (frame, query)).
   :probe-suspense-abort-element helix-suspense-abort-element
   ;; sibling-collision (rf2-e4pyb) — both siblings under one parent div so
   ;; the suite reads "a=N b=N" off textContent.
   :probe-siblings-element (fn [] ($ :div ($ ProbeSiblingA) ($ ProbeSiblingB)))
   :siblings-observed-a   siblings-observed-a
   :siblings-observed-b   siblings-observed-b
   :sib-frame             :rf.helix-siblings/frame
   :sib-query             :rf.helix-siblings/n
   ;; stable deps key
   :probe-stable-deps-element (fn [] ($ ProbeStableDepsParent))
   :stable-deps-set-tick  stable-deps-set-tick
   :stable-deps-frame     :rf.helix-stable-deps/probe-frame
   :stable-deps-query     :rf.helix-stable-deps/p
   ;; rf2-40a84 — flush-render! synchronous-commit proof. Reuses the Probe
   ;; (reads :refcount-target for its frame, queries :rf.helix-use-subscribe-test/n)
   ;; under a fresh isolated frame so it can't collide with the use-subscribe
   ;; cases above.
   :fr-frame              :rf.helix-flush-render/probe-frame
   :fr-query              :rf.helix-use-subscribe-test/n})

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
;; so this forwards on :substrate-kw alone (no substrate `defnc`/`$`).
(deftest view-unmount-emits-on-react-hook-teardown
  (suite/assert-view-unmount-emits-on-react-hook-teardown
    {:substrate-kw :helix :name "Helix"}))

;; rf2-ghfkkk — a registered view returning a VOID DOM root (<input>) mounts
;; + unmounts with no React void-element warning/error, and still fires
;; exactly one :rf.view/unmounted (the unmount sentinel rides as a Fragment
;; sibling, not a child of the void element). Probe built in the suite.
(deftest void-root-view-unmount-no-warning
  (suite/assert-void-root-view-unmount-no-warning
    {:substrate-kw :helix :name "Helix"}))

;; ---- regression: frame-provider under the idiomatic `$` trailing-children shape (rf2-9ok1s / rf2-z7hfp / rf2-7kii2) -
;;
;; Pins the moved-up seam (rf2-z7hfp) AND the unified trailing-children
;; call shape (rf2-7kii2). HISTORY: `frame-provider` used to be a plain
;; re-exported spine CLJS fn (not a `defnc`), so Helix's `$` routed props
;; through `helix.impl.props/-props` and handed the fn a *raw JS object*
;; with string keys "frame"/"children" — the fn read `nil` for both,
;; `:frame` silently resolved to `:rf/default`, and the subtree rendered
;; nothing. A bespoke `gobj/get` un-mangling wrapper patched it per-adapter.
;;
;; rf2-z7hfp MOVED THE SEAM UP: `frame-provider` is now a NATIVE Helix
;; `defnc` component. `$` therefore routes its props through
;; `extract-cljs-props`, which beans the JS object back into a CLJS map
;; with keyword keys (and Helix preserves keyword VALUES) — so `:frame`
;; destructures cleanly by construction, with no per-adapter patch.
;;
;; rf2-7kii2 UNIFIED THE CALL SHAPE: children now ride the native `$`
;; TRAILING-ARGS channel — `($ frame-provider-existing {:frame :f} c1 c2)` — exactly
;; as for every other Helix component and mirroring Reagent's trailing
;; hiccup. The old `:children`-in-props-map form (and its silent-drop
;; footgun) is gone. This test mounts the provider via the idiomatic
;; trailing shape with TWO children and asserts BOTH descendant
;; `use-subscribe`s read the WRAPPED frame's value — the structural
;; guarantee that (a) the prop-mangling class cannot reopen and (b) native
;; trailing children propagate the frame and render.

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
  (testing "Helix — ($ frame-provider-existing {:frame :f} c1 c2) trailing children propagate :frame + render (rf2-7kii2)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (let [frame-kw :rf.helix-use-subscribe-test/frame-provider-frame
                query-v  [:rf.helix-use-subscribe-test/k]]
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
                      ;; children (no `:children` key), flowing through Helix's
                      ;; `$` → `extract-cljs-props` → the native `defnc` shell.
                      (.render root
                        ($ helix-adapter/frame-provider
                           {:frame frame-kw}
                           ($ ProbeFrameProvider)
                           ($ ProbeFrameProvider)))))
                  (is (some #{:wrapped} @probe-frame-provider-observed)
                      "trailing children's use-subscribe read the wrapped frame's value, not :rf/default")
                  (is (= 2 (count (filterv #{:wrapped} @probe-frame-provider-observed)))
                      "both trailing children rendered (not dropped)")
                  (finally
                    (try (.unmount root) (catch :default _ nil))))))))))))
