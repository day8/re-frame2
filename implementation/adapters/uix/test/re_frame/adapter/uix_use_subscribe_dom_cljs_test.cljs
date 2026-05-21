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
  (:require [cljs.test :refer-macros [deftest use-fixtures]]
            [uix.core :as uix :refer-macros [defui $]]
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

(def ^:private probe-observed    (atom []))
(def ^:private probe-fp-observed (atom []))
(def ^:private refcount-target   (atom nil))
(def ^:private mwft2-set-tick    (atom nil))

(defui Probe []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/n])]
    (swap! probe-observed conj v)
    ($ :div (str "n=" v))))

(defui ProbeFp []
  (let [v (uix-adapter/use-subscribe [:rf.uix-use-subscribe-test/k])]
    (swap! probe-fp-observed conj v)
    ($ :div (str "k=" v))))

(defui ProbeRc []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target [:rf.uix-use-subscribe-test/m])]
    ($ :div (str "m=" v))))

;; ---- rf2-rcgsc 2-arg explicit-pin probes ----------------------------------

(def ^:private probe-2arg-a-observed (atom []))
(def ^:private probe-2arg-b-observed (atom []))

(defui Probe2ArgA []
  (let [v (uix-adapter/use-subscribe :rf.uix-rcgsc/tenant-a [:rf.uix-rcgsc/n])]
    (swap! probe-2arg-a-observed conj v)
    ($ :div (str "a=" v))))

(defui Probe2ArgB []
  (let [v (uix-adapter/use-subscribe :rf.uix-rcgsc/tenant-b [:rf.uix-rcgsc/n])]
    (swap! probe-2arg-b-observed conj v)
    ($ :div (str "b=" v))))

;; ---- rf2-mwft2 stable-deps-key probes -------------------------------------
;; A parent that owns a tick state (used to force re-renders) plus a child
;; that reads a fixed query-v via use-subscribe. The literal
;; `[:rf.uix-mwft2/p]` vector evaluates to a fresh JS object each render —
;; exactly the shape the bug-without-fix walks into. The parent stashes its
;; set-tick fn into a side-channel atom so the suite can drive forced
;; re-renders from outside.

(defui ProbeMwft2Child []
  (let [v (uix-adapter/use-subscribe :rf.uix-mwft2/probe-frame [:rf.uix-mwft2/p])]
    ($ :div (str "p=" v))))

(defui ProbeMwft2Parent []
  (let [[tick set-tick] (uix/use-state 0)]
    (uix/use-effect
      ;; React state-setters have stable identity across renders so an
      ;; empty deps vec is correct — silences UIx's lint and matches
      ;; React's "set-state setter is stable" guarantee. The effect runs
      ;; once on mount to stash the setter for the test driver.
      (fn [] (reset! mwft2-set-tick set-tick) js/undefined)
      [])
    ($ :div {:data-tick tick}
       ($ ProbeMwft2Child))))

;; ---- cfg + forwarded deftests ---------------------------------------------

(def ^:private cfg
  {:adapter               uix-adapter/adapter
   :name                  "UIx"
   :frame-provider        uix-adapter/frame-provider
   ;; tracks-app-db
   :probe-element         (fn [] (uix/$ Probe))
   :probe-observed        probe-observed
   :refcount-target       refcount-target
   :us-frame              :rf.uix-use-subscribe-test/probe-frame
   :us-query              :rf.uix-use-subscribe-test/n
   ;; frame-provider 1-arg
   :probe-fp-element      (fn [] (uix/$ ProbeFp))
   :probe-fp-observed     probe-fp-observed
   :fp-frame              :rf.uix-use-subscribe-test/fp-frame
   :fp-query              :rf.uix-use-subscribe-test/k
   ;; 2-arg explicit pin
   :probe-2arg-element    (fn [] (uix/$ :div (uix/$ Probe2ArgA) (uix/$ Probe2ArgB)))
   :probe-2arg-a-observed probe-2arg-a-observed
   :probe-2arg-b-observed probe-2arg-b-observed
   :tenant-a-frame        :rf.uix-rcgsc/tenant-a
   :tenant-b-frame        :rf.uix-rcgsc/tenant-b
   :rcgsc-query           :rf.uix-rcgsc/n
   ;; refcount cleanup
   :probe-rc-element      (fn [] (uix/$ ProbeRc))
   :rc-frame              :rf.uix-use-subscribe-test/refcount-frame
   :rc-query              :rf.uix-use-subscribe-test/m
   ;; stable deps key
   :probe-mwft2-element   (fn [] (uix/$ ProbeMwft2Parent))
   :mwft2-set-tick        mwft2-set-tick
   :mwft2-frame           :rf.uix-mwft2/probe-frame
   :mwft2-query           :rf.uix-mwft2/p})

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
