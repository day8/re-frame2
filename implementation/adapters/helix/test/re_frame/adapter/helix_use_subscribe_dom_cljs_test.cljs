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
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [helix.hooks :as helix-hooks]
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
   :frame-provider        helix-adapter/frame-provider
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
   ;; stable deps key
   :probe-stable-deps-element (fn [] ($ ProbeStableDepsParent))
   :stable-deps-set-tick  stable-deps-set-tick
   :stable-deps-frame     :rf.helix-stable-deps/probe-frame
   :stable-deps-query     :rf.helix-stable-deps/p})

(deftest use-subscribe-tracks-app-db-changes
  (suite/assert-use-subscribe-tracks-app-db-changes cfg))

(deftest use-subscribe-frame-provider-resolution
  (suite/assert-use-subscribe-frame-provider-resolution cfg))

(deftest use-subscribe-2-arg-pins-explicit-frame
  (suite/assert-use-subscribe-2-arg-pins-explicit-frame cfg))

(deftest use-subscribe-cleanup-decrements-sub-cache-refcount
  (suite/assert-use-subscribe-cleanup-decrements-refcount cfg))

(deftest use-subscribe-stable-deps-key
  (suite/assert-use-subscribe-stable-deps-key cfg))

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
