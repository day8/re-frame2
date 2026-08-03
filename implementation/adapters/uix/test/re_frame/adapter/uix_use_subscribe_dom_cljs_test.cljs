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
  The orchestration (make-frame, dispatch, mount under act, assert) lives
  once in the suite.

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

;; MAP-FORM fixture with `:async? true` (rf2-2rtt6.25): the provisional-horizon
;; assertions below are `(async done …)` tests, and cljs.test refuses to run an
;; async test under a plain-fn `:each` fixture ("Async tests require fixtures to
;; be specified as maps") — the `:after` half has to land after the async
;; `done`, which a plain fn cannot express. The horizon is one host macrotask —
;; `setTimeout 4` since rf2-2rtt6.71 — and nothing synchronous crosses it; the
;; suite's own settles wait PAST it (`settle-past-the-horizon!`).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter :async? true}))

;; ---- side-channel atoms ----------------------------------------------------
;; Read by the UIx probe components below. The probes are defui top-levels
;; (uix `defui` defines a Var; it cannot sit inside a `let`) and close
;; over these atoms; the suite `reset!`s the atom each assertion cares
;; about at the top of its body.

(def ^:private probe-observed                (atom []))
(def ^:private probe-frame-provider-observed (atom []))
(def ^:private refcount-target               (atom nil))
(def ^:private stable-deps-set-tick          (atom nil))
;; rf2-naz09e key-change probe side-channels: the child reads its (frame,
;; query-v) from these atoms (2-arg explicit-pin use-subscribe) and records
;; every use-subscribe return; the parent stashes its set-tick here so the
;; suite can swap the target on the MOUNTED child and force a re-render.
(def ^:private key-change-set-tick           (atom nil))
(def ^:private key-change-frame              (atom nil))
(def ^:private key-change-query              (atom nil))
(def ^:private key-change-observed           (atom []))

(defui Probe []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/n])]
    (swap! probe-observed conj v)
    ($ :div (str "n=" v))))

(defui ProbeFrameProvider []
  (let [v (uix-adapter/use-subscribe [:rf.uix-use-subscribe-test/k])]
    (swap! probe-frame-provider-observed conj v)
    ($ :div (str "k=" v))))

;; ---- use-frame probe (rf2-y6dz8t) ------------------------------------------
;; Pushes each render's `use-frame` ops map into a side-channel atom; the
;; suite asserts shape / provider resolution / dispatch lock / reference
;; stability once for both adapters.

(def ^:private use-frame-observed (atom []))

(defui ProbeUseFrame []
  (let [ops (uix-adapter/use-frame)]
    (swap! use-frame-observed conj ops)
    ($ :div "uf")))

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

;; ---- key-change probes (rf2-naz09e) ---------------------------------------
;; A child that reads its (frame, query-v) from side-channel atoms via the
;; 2-arg explicit-pin use-subscribe and records every observed value, under a
;; parent that owns a tick + stashes its set-tick. The suite swaps the atoms
;; and bumps the tick to change the subscription target on the MOUNTED child.

(defui ProbeKeyChangeChild []
  (let [fr @key-change-frame
        qv @key-change-query
        v  (uix-adapter/use-subscribe fr qv)]
    (swap! key-change-observed conj v)
    ($ :div (str "v=" v))))

(defui ProbeKeyChangeParent []
  (let [[tick set-tick] (uix/use-state 0)]
    (uix/use-effect
      (fn [] (reset! key-change-set-tick set-tick) js/undefined)
      [])
    ($ :div {:data-tick tick}
       ($ ProbeKeyChangeChild))))

;; ---- render→commit window probes (rf2-2rtt6.13) ---------------------------
;; Three components in one pass. The SUBSCRIBER reads the query. The MUTATOR
;; renders after it and writes app-db from its own render body, so the write
;; lands strictly between that read and the commit — deterministic, no timer.
;; The OBSERVER's layout effect records the FIRST committed, layout-visible
;; DOM (a render React discards before committing never runs one). The suite
;; installs the write thunk — nil for the unmoved control — arms the one shot,
;; and reads the atoms.

(def ^:private gap-write!       (atom nil))
(def ^:private gap-armed?       (atom false))
(def ^:private gap-first-commit (atom nil))
(def ^:private gap-mount-node   (atom nil))
(def ^:private gap-db-read      (atom nil))
(def ^:private gap-observed     (atom []))

(defui ProbeGapSubscriber []
  (let [v (uix-adapter/use-subscribe @refcount-target [:rf.uix-gap/n])]
    (swap! gap-observed conj v)
    ($ :div (str "g=" v))))

(defui ProbeGapMutator []
  ;; ONE-SHOT render-phase write. Disarming BEFORE the write is what lets a
  ;; corrective re-render converge: React discarding a torn concurrent render
  ;; re-runs this body, and a second write would keep the store moving forever.
  (when @gap-armed?
    (reset! gap-armed? false)
    (when-let [w @gap-write!] (w)))
  ($ :span))

(defui ProbeGapObserver []
  (uix/use-layout-effect
    (fn []
      (when (nil? @gap-first-commit)
        (reset! gap-first-commit
                {:dom (.-textContent ^js @gap-mount-node)
                 :db  (when-let [read-db @gap-db-read] (read-db))}))
      js/undefined)
    [])
  ($ :span))

(defui ProbeGapRoot []
  ($ :div
     ($ ProbeGapSubscriber)
     ($ ProbeGapMutator)
     ($ ProbeGapObserver)))

;; ---- PUBLIC-mount-schedule probes (rf2-2rtt6.25, audit of #7305) -----------
;; Nothing here forces React's schedule: these mount through
;; `re-frame.substrate.adapter/render` with no act and no flushSync, and the
;; suite reads its numbers from the probes' own effects.

(def ^:private pm-on-commit         (atom nil))
(def ^:private gap-public-set-phase (atom nil))

(defui ProbePublicMount []
  ;; The `use-effect` is declared AFTER the read on purpose: React pushes
  ;; `useSyncExternalStore`'s `subscribeToStore` passive effect while the hook
  ;; runs and this one after it, and a fiber's passive effects run in push
  ;; order — so this callback is the first instant after the commit-owned
  ;; subscribe. That ordering, not a timer, is what makes the suite's snapshot
  ;; of the numbers deterministic.
  (let [target @refcount-target
        v      (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/m])]
    (uix/use-effect
      (fn [] (when-let [f @pm-on-commit] (f)) js/undefined)
      [])
    ($ :div (str "m=" v))))

(defui ProbeGapPublicRoot []
  ;; Mounts idle, so the gap probe arrives as an UPDATE the suite can put on a
  ;; transition lane — on the root the PUBLIC render slot created, rather than
  ;; on a raw createRoot of the test's own. The mount effect stashes the setter;
  ;; the suite drives the phase change inside `React/startTransition`.
  (let [[phase set-phase] (uix/use-state :idle)]
    (uix/use-effect
      (fn [] (reset! gap-public-set-phase set-phase) js/undefined)
      [])
    (if (= phase :idle)
      ($ :span "idle")
      ($ :div
         ($ ProbeGapSubscriber)
         ($ ProbeGapMutator)
         ($ ProbeGapObserver)))))

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
   ;; rf2-y6dz8t — use-frame (capture-frame in hook position)
   :probe-use-frame-element (fn [] (uix/$ ProbeUseFrame))
   :use-frame-observed      use-frame-observed
   :uf-frame                :rf.uix-use-frame/probe-frame
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
   ;; rf2-2rtt6.13 — its own frame (so its own sub-cache), because the
   ;; assertion is only load-bearing on a COLD read and it says so.
   :nr-frame              :rf.uix-no-retain/probe-frame
   ;; rf2-2rtt6.13 (audit) — the render→commit window observed at the FIRST
   ;; commit. Four rows = four frames, because the pre-commit path is only
   ;; reachable on a COLD read and each row must be one.
   :probe-gap-element     (fn [] (uix/$ ProbeGapRoot))
   :gap-write!            gap-write!
   :gap-armed?            gap-armed?
   :gap-first-commit      gap-first-commit
   :gap-mount-node        gap-mount-node
   :gap-db-read           gap-db-read
   :gap-observed          gap-observed
   :gap-query             :rf.uix-gap/n
   :gap-blocking-frame            :rf.uix-gap/blocking-frame
   :gap-blocking-control-frame    :rf.uix-gap/blocking-control-frame
   :gap-concurrent-frame          :rf.uix-gap/concurrent-frame
   :gap-concurrent-control-frame  :rf.uix-gap/concurrent-control-frame
   ;; rf2-2rtt6.25 — the provisional hand-off's three own frames, for the same
   ;; reason: adoption, the one-shot reaper, the layer-2 horizon cascade and
   ;; the SSR horizon are all COLD-read properties, so each needs a sub-cache
   ;; no other assertion has warmed.
   :ad-frame              :rf.uix-handoff/adoption-frame
   :hz-frame              :rf.uix-handoff/horizon-frame
   :ssr-frame             :rf.uix-handoff/ssr-frame
   ;; rf2-2rtt6.25 (audit of #7305) — the PUBLIC mount schedule: adapter render
   ;; slot, no act, no flushSync. Own frames again, for the same cold-read
   ;; reason, and one per row of the escrow-leg pair.
   :probe-public-mount-element (fn [] (uix/$ ProbePublicMount))
   :pm-on-commit          pm-on-commit
   :pm-frame              :rf.uix-handoff/public-schedule-frame
   :probe-gap-public-element (fn [] (uix/$ ProbeGapPublicRoot))
   :gap-public-set-phase  gap-public-set-phase
   :pm-gap-frame          :rf.uix-handoff/public-gap-frame
   :pm-gap-control-frame  :rf.uix-handoff/public-gap-control-frame
   ;; rf2-2rtt6.25 (audit of #7326) — the reaped provisional's own frame. The
   ;; abandonment and the later mount must race on the SAME (frame, query), and
   ;; the frame must be cold before the abandoned render, so it is its own.
   :rv-frame              :rf.uix-handoff/reaped-provisional-frame
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
   ;; rf2-naz09e — key-change serves the NEW target
   :probe-key-change-element (fn [] (uix/$ ProbeKeyChangeParent))
   :key-change-set-tick   key-change-set-tick
   :key-change-frame      key-change-frame
   :key-change-query      key-change-query
   :key-change-observed   key-change-observed
   :kc-frame              :rf.uix-key-change/frame-a
   :kc-frame2             :rf.uix-key-change/frame-b
   :kc-query-a            :rf.uix-key-change/qa
   :kc-query-b            :rf.uix-key-change/qb
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

;; rf2-y6dz8t — use-frame returns EXACTLY the capture-frame ops map for the
;; ambient provider frame: shape, provider resolution, dispatch lock, and
;; reference stability across re-renders.
(deftest use-frame-capture-frame-in-hook-position
  (suite/assert-use-frame-capture-frame-in-hook-position cfg))

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

;; rf2-2rtt6.13 — the disposed render-phase reaction must be unreachable: every
;; deref the spine performs hits the sub-cache's CURRENT tenant, and the cold
;; mount itself shows a deref of the committed reaction (React's post-subscribe
;; getSnapshot call, which is what still catches a render→commit write).
(deftest use-subscribe-render-phase-reaction-not-retained
  (suite/assert-use-subscribe-render-phase-reaction-not-retained cfg))

;; rf2-2rtt6.13 (merged-PR audit of #7304) — a write landing in the
;; render→commit gap, observed AT THE FIRST COMMIT (layout-visible, i.e.
;; paint-eligible) rather than after the dust settles: two lanes, each with an
;; unmoved control. The blocking row pins React's own no-pre-commit-check
;; behaviour; the concurrent row pins that our pre-commit snapshot can still
;; REPORT the movement, so React discards the torn render instead of painting
;; state the app-db has already revoked.
(deftest use-subscribe-render-to-commit-window-first-commit
  (suite/assert-use-subscribe-render-to-commit-window-first-commit cfg))

;; rf2-2rtt6.25 — the hook-scoped provisional hand-off. A cold mount's commit
;; ADOPTS the reaction its render built (one construction, not two); the reaper
;; armed at acquisition is a no-op once that adoption has spent the token; an
;; abandoned layer-2 cold render releases parent AND inputs at the horizon; and
;; a server render, which never commits at all, nets zero there too.
(deftest use-subscribe-commit-adopts-the-render-phase-reaction
  (suite/assert-use-subscribe-commit-adopts-the-render-phase-reaction cfg))

;; rf2-2rtt6.25 (merged-PR audit of #7305), flipped by rf2-2rtt6.71 — the same
;; two integers, on the PUBLIC mount schedule: `re-frame.substrate.adapter/
;; render`, no act, no flushSync. The row above forces React's passive subscribe
;; forward, which is the ordering the hand-off needs; this one forces nothing.
;; With the reaper at `setTimeout 0` the reaper won there and the commit
;; rebuilt, so the row pinned TWO builds and named the retracted claim; the
;; ruled `setTimeout 4` horizon wins instead, so it now pins ONE — by a measured
;; margin and never a React guarantee, which is exactly what makes it the
;; standing drift tripwire. Its companion pins that `get-snap`'s escrow leg is
;; reachable on that schedule either way, because the pre-commit consistency
;; check runs in the render's own task, before any macrotask can reap.
(deftest use-subscribe-public-mount-schedule-rebuilds
  (suite/assert-use-subscribe-public-mount-schedule-rebuilds cfg))

(deftest use-subscribe-escrow-leg-answers-on-the-public-mount-schedule
  (suite/assert-use-subscribe-escrow-leg-answers-on-the-public-mount-schedule cfg))

(deftest use-subscribe-adopted-provisional-reaper-is-a-noop
  (suite/assert-use-subscribe-adopted-provisional-reaper-is-a-noop cfg))

(deftest use-subscribe-abandoned-layer-2-render-cascades-at-the-horizon
  (suite/assert-use-subscribe-abandoned-layer-2-render-cascades-at-the-horizon cfg))

;; rf2-2rtt6.25 (merged-PR audit of #7326) — the adversarial row, and the reason
;; the ruled margin is a performance bet and never a correctness one. A
;; provisional the reaper released must be unreachable, and a later mount must
;; paint an app-db movement the abandoned render never saw. That holds whether
;; the later mount adopts its own render build or rebuilds after its own reaper
;; — so if the 4 ms margin is ever lost, what comes back is a second
;; construction, never a stale paint.
(deftest use-subscribe-reaped-provisional-is-never-adopted-by-a-later-mount
  (suite/assert-use-subscribe-reaped-provisional-is-never-adopted-by-a-later-mount cfg))

(deftest use-subscribe-ssr-render-without-commit-nets-zero-at-the-horizon
  (suite/assert-use-subscribe-ssr-render-without-commit-nets-zero-at-the-horizon cfg))

;; rf2-naz09e — a query-v / frame change on a MOUNTED component must render the
;; NEW target's value on the change-commit (parity with Reagent's in-render
;; recompute), never the previous target's. Value + object-identity proof, plus
;; a stable-key control (no over-invalidation).
(deftest use-subscribe-key-change-serves-new-target
  (suite/assert-use-subscribe-key-change-serves-new-target cfg))

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
;; The UIx counterpart of the rf2-9ok1s defect (found on the Helix adapter
;; before its W13 removal), now pinning the moved-up seam (rf2-z7hfp) AND
;; the unified trailing-children call shape (rf2-7kii2).
;; HISTORY: `frame-provider` used to be a plain re-exported
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
              (rf/make-frame {:id frame-kw :doc "rf2-7kii2 trailing-children frame-provider probe"})
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
