(ns re-frame.adapter.uix-use-sub-dom-cljs-test
  "Native UIx probes for the shared hook contracts. Separate frame IDs keep cold reads cold."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.react-shared-suite :as rf.adapter.react-shared-suite]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true}))

(def ^:private probe-observed                (atom []))
(def ^:private probe-frame-provider-observed (atom []))
(def ^:private refcount-target               (atom nil))
(def ^:private stable-deps-set-tick          (atom nil))
(def ^:private key-change-set-tick           (atom nil))
(def ^:private key-change-frame              (atom nil))
(def ^:private key-change-query              (atom nil))
(def ^:private key-change-observed           (atom []))

(defui Probe []
  (let [target @refcount-target
        v (rf.adapter.uix/use-sub [:rf.uix-use-sub-test/n] {:frame target})]
    (swap! probe-observed conj v)
    ($ :div (str "n=" v))))

(defui ProbeFrameProvider []
  (let [v (rf.adapter.uix/use-sub [:rf.uix-use-sub-test/k])]
    (swap! probe-frame-provider-observed conj v)
    ($ :div (str "k=" v))))

(def ^:private ssr-slot-observed (atom nil))

;; Observe both React renderer slots and the public readers during server rendering.
(defui ProbeSsrSlots []
  (let [ctx rf.adapter.context/frame-context]
    (reset! ssr-slot-observed
            {:current-value  (.-_currentValue ^js ctx)
             :current-value2 (.-_currentValue2 ^js ctx)
             :use-context    (React/useContext ctx)
             :reader         (rf.adapter.context/function-component-current-frame)
             :resolve        (rf.frame/resolve-current-frame)})
    ($ :div "slots")))

(def ^:private use-frame-observed (atom []))

(defui ProbeUseFrame []
  (let [ops (rf.adapter.uix/use-frame)]
    (swap! use-frame-observed conj ops)
    ($ :div "uf")))

(defui ProbeRefcount []
  (let [target @refcount-target
        v (rf.adapter.uix/use-sub [:rf.uix-use-sub-test/m] {:frame target})]
    ($ :div (str "m=" v))))

;; Never resolving keeps the use-sub fiber abandoned before commit.
(defonce ^:private uix-never-resolving-thenable
  #js {:then (fn [_resolve _reject] nil)})

(defui ProbeSuspender []
  (throw uix-never-resolving-thenable))

(defui ProbeSuspenseInner []
  (let [target @refcount-target
        _v (rf.adapter.uix/use-sub [:rf.uix-use-sub-test/m] {:frame target})]
    ($ :div ($ ProbeSuspender))))

(defn- uix-suspense-abort-element
  "Suspend after use-sub has acquired its render-phase subscription."
  []
  (React/createElement React/Suspense
                       #js {:fallback ($ :div "fallback")}
                       ($ ProbeSuspenseInner)))

(def ^:private siblings-observed-a (atom []))
(def ^:private siblings-observed-b (atom []))

(defui ProbeSiblingA []
  (let [target @refcount-target
        v (rf.adapter.uix/use-sub [:rf.uix-siblings/n] {:frame target})]
    (swap! siblings-observed-a conj v)
    ($ :span (str "a=" v))))

(defui ProbeSiblingB []
  (let [target @refcount-target
        v (rf.adapter.uix/use-sub [:rf.uix-siblings/n] {:frame target})]
    (swap! siblings-observed-b conj v)
    ($ :span (str " b=" v))))

(def ^:private probe-2arg-a-observed (atom []))
(def ^:private probe-2arg-b-observed (atom []))

(defui Probe2ArgA []
  (let [v (rf.adapter.uix/use-sub [:rf.uix-explicit-pin/n] {:frame :rf.uix-explicit-pin/tenant-a})]
    (swap! probe-2arg-a-observed conj v)
    ($ :div (str "a=" v))))

(defui Probe2ArgB []
  (let [v (rf.adapter.uix/use-sub [:rf.uix-explicit-pin/n] {:frame :rf.uix-explicit-pin/tenant-b})]
    (swap! probe-2arg-b-observed conj v)
    ($ :div (str "b=" v))))

(defui ProbeStableDepsChild []
  (let [v (rf.adapter.uix/use-sub [:rf.uix-stable-deps/p] {:frame :rf.uix-stable-deps/probe-frame})]
    ($ :div (str "p=" v))))

(defui ProbeStableDepsParent []
  (let [[tick set-tick] (uix/use-state 0)]
    (uix/use-effect
      (fn [] (reset! stable-deps-set-tick set-tick) js/undefined)
      [])
    ($ :div {:data-tick tick}
       ($ ProbeStableDepsChild))))

(defui ProbeKeyChangeChild []
  (let [fr @key-change-frame
        qv @key-change-query
        v  (rf.adapter.uix/use-sub qv {:frame fr})]
    (swap! key-change-observed conj v)
    ($ :div (str "v=" v))))

(defui ProbeKeyChangeParent []
  (let [[tick set-tick] (uix/use-state 0)]
    (uix/use-effect
      (fn [] (reset! key-change-set-tick set-tick) js/undefined)
      [])
    ($ :div {:data-tick tick}
       ($ ProbeKeyChangeChild))))

(def ^:private gap-write!       (atom nil))
(def ^:private gap-armed?       (atom false))
(def ^:private gap-first-commit (atom nil))
(def ^:private gap-mount-node   (atom nil))
(def ^:private gap-db-read      (atom nil))
(def ^:private gap-observed     (atom []))

(defui ProbeGapSubscriber []
  (let [v (rf.adapter.uix/use-sub [:rf.uix-gap/n] {:frame @refcount-target})]
    (swap! gap-observed conj v)
    ($ :div (str "g=" v))))

;; Disarm before writing so a corrective render can converge.
(defui ProbeGapMutator []
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

(def ^:private pm-on-commit         (atom nil))
(def ^:private gap-public-set-phase (atom nil))

;; Declare this effect after use-sub so it observes the committed store subscription.
(defui ProbePublicMount []
  (let [target @refcount-target
        v      (rf.adapter.uix/use-sub [:rf.uix-use-sub-test/m] {:frame target})]
    (uix/use-effect
      (fn [] (when-let [f @pm-on-commit] (f)) js/undefined)
      [])
    ($ :div (str "m=" v))))

;; Mount idle so the transition updates the root created by the public render slot.
(defui ProbeGapPublicRoot []
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

(def ^:private evict-target                  (atom nil))
(def ^:private probe-evict-observed          (atom []))
(def ^:private probe-evict-successor-observed (atom []))

(defui ProbeEvict []
  (let [target @evict-target
        v (rf.adapter.uix/use-sub [:rf.uix-evict/n] {:frame target})]
    (swap! probe-evict-observed conj v)
    ($ :div (str "n=" v))))

(defui ProbeEvictSuccessor []
  (let [target @evict-target
        v (rf.adapter.uix/use-sub [:rf.uix-evict/n] {:frame target})]
    (swap! probe-evict-successor-observed conj v)
    ($ :div (str "s=" v))))

(def ^:private nil-frame-opts     (atom {}))
(def ^:private nil-frame-observed (atom []))

(defui ProbeNilFrame []
  (let [opts @nil-frame-opts
        v (rf.adapter.uix/use-sub [:rf.uix-nil-frame/v] opts)]
    (swap! nil-frame-observed conj v)
    ($ :div (str "v=" v))))

(def ^:private cfg
  {:adapter               rf.adapter.uix/adapter
   :name                  "UIx"
   :frame-provider-mount-element
   (fn [frame-kw child-el]
     ($ rf.adapter.uix/frame-provider {:frame frame-kw} child-el))
   :probe-element         (fn [] (uix/$ Probe))
   :probe-observed        probe-observed
   :refcount-target       refcount-target
   :us-frame              :rf.uix-use-sub-test/probe-frame
   :us-query              :rf.uix-use-sub-test/n
   :probe-frame-provider-element  (fn [] (uix/$ ProbeFrameProvider))
   :probe-frame-provider-observed probe-frame-provider-observed
   :frame-provider-query          :rf.uix-use-sub-test/k
   :ssr-ambient-frame             :rf.uix-5rqn/ssr-ambient-frame
   :ssr-dynamic-frame             :rf.uix-5rqn/ssr-dynamic-frame
   :probe-ssr-slots-element       (fn [] (uix/$ ProbeSsrSlots))
   :ssr-slot-observed             ssr-slot-observed
   :probe-use-frame-element (fn [] (uix/$ ProbeUseFrame))
   :use-frame-observed      use-frame-observed
   :uf-frame                :rf.uix-use-frame/probe-frame
   :provider-tier-frame               :rf.uix-4mi2zj/provider-tier-frame
   :dynamic-precedence-provider-frame :rf.uix-4mi2zj/precedence-provider-frame
   :dynamic-precedence-dynamic-frame  :rf.uix-4mi2zj/precedence-dynamic-frame
   :no-scope-frame                    :rf.uix-4mi2zj/no-scope-frame
   :substrate-kw                      :uix
   :probe-2arg-element    (fn [] (uix/$ :div (uix/$ Probe2ArgA) (uix/$ Probe2ArgB)))
   :probe-2arg-a-observed probe-2arg-a-observed
   :probe-2arg-b-observed probe-2arg-b-observed
   :tenant-a-frame        :rf.uix-explicit-pin/tenant-a
   :tenant-b-frame        :rf.uix-explicit-pin/tenant-b
   :explicit-pin-query    :rf.uix-explicit-pin/n
   :probe-refcount-element (fn [] (uix/$ ProbeRefcount))
   :rc-frame              :rf.uix-use-sub-test/refcount-frame
   :rc-query              :rf.uix-use-sub-test/m
   :nr-frame              :rf.uix-no-retain/probe-frame
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
   :ad-frame              :rf.uix-handoff/adoption-frame
   :hz-frame              :rf.uix-handoff/horizon-frame
   :ssr-frame             :rf.uix-handoff/ssr-frame
   :probe-public-mount-element (fn [] (uix/$ ProbePublicMount))
   :pm-on-commit          pm-on-commit
   :pm-frame              :rf.uix-handoff/public-schedule-frame
   :probe-gap-public-element (fn [] (uix/$ ProbeGapPublicRoot))
   :gap-public-set-phase  gap-public-set-phase
   :pm-gap-frame          :rf.uix-handoff/public-gap-frame
   :pm-gap-control-frame  :rf.uix-handoff/public-gap-control-frame
   :rv-frame              :rf.uix-handoff/reaped-provisional-frame
   :probe-suspense-abort-element uix-suspense-abort-element
   :probe-siblings-element (fn [] (uix/$ :div (uix/$ ProbeSiblingA) (uix/$ ProbeSiblingB)))
   :siblings-observed-a   siblings-observed-a
   :siblings-observed-b   siblings-observed-b
   :sib-frame             :rf.uix-siblings/frame
   :sib-query             :rf.uix-siblings/n
   :probe-stable-deps-element (fn [] (uix/$ ProbeStableDepsParent))
   :stable-deps-set-tick  stable-deps-set-tick
   :stable-deps-frame     :rf.uix-stable-deps/probe-frame
   :stable-deps-query     :rf.uix-stable-deps/p
   :probe-key-change-element (fn [] (uix/$ ProbeKeyChangeParent))
   :key-change-set-tick   key-change-set-tick
   :key-change-frame      key-change-frame
   :key-change-query      key-change-query
   :key-change-observed   key-change-observed
   :kc-frame              :rf.uix-key-change/frame-a
   :kc-frame2             :rf.uix-key-change/frame-b
   :kc-query-a            :rf.uix-key-change/qa
   :kc-query-b            :rf.uix-key-change/qb
   :fr-frame              :rf.uix-flush-render/probe-frame
   :fr-query              :rf.uix-use-sub-test/n
   :probe-evict-element            (fn [] (uix/$ ProbeEvict))
   :probe-evict-observed           probe-evict-observed
   :probe-evict-successor-element  (fn [] (uix/$ ProbeEvictSuccessor))
   :probe-evict-successor-observed probe-evict-successor-observed
   :evict-target                   evict-target
   :ev-frame                       :rf.uix-evict/frame
   :ev-query                       :rf.uix-evict/n
   :probe-nil-frame-element        (fn [] (uix/$ ProbeNilFrame))
   :nil-frame-opts                 nil-frame-opts
   :nil-frame-observed             nil-frame-observed
   :nf-frame                       :rf.uix-nil-frame/frame
   :nf-query                       :rf.uix-nil-frame/v})

(deftest use-sub-tracks-app-db-changes
  (rf.adapter.react-shared-suite/assert-use-sub-tracks-app-db-changes cfg))

(deftest use-sub-ambient-under-ssr
  (rf.adapter.react-shared-suite/assert-use-sub-ambient-under-ssr cfg))

(deftest use-sub-2-arg-pins-explicit-frame
  (rf.adapter.react-shared-suite/assert-use-sub-2-arg-pins-explicit-frame cfg))

(deftest use-frame-capture-frame-in-hook-position
  (rf.adapter.react-shared-suite/assert-use-frame-capture-frame-in-hook-position cfg))

(deftest use-frame-retargets-across-a-same-id-reincarnation
  (rf.adapter.react-shared-suite/assert-use-frame-retargets-across-a-same-id-reincarnation cfg))

(deftest use-sub-provider-tier-resolution-ambient-cleared
  (rf.adapter.react-shared-suite/assert-use-sub-provider-tier-resolution-ambient-cleared cfg))

(deftest use-sub-provider-precedence-over-dynamic-var
  (rf.adapter.react-shared-suite/assert-use-sub-provider-precedence-over-dynamic-var cfg))

(deftest use-sub-no-provider-no-dynamic-raises-no-frame-context
  (rf.adapter.react-shared-suite/assert-use-sub-no-provider-no-dynamic-raises-no-frame-context cfg))

(deftest hook-no-provider-with-dynamic-scope-raises-no-frame-context
  (rf.adapter.react-shared-suite/assert-hook-no-provider-with-dynamic-scope-raises-no-frame-context cfg))

(deftest hook-scheduled-render-after-unwind-reads-context-only
  (rf.adapter.react-shared-suite/assert-hook-scheduled-render-after-unwind-reads-context-only cfg))

(deftest use-sub-cleanup-decrements-sub-cache-refcount
  (rf.adapter.react-shared-suite/assert-use-sub-cleanup-decrements-refcount cfg))

(deftest use-sub-siblings-same-query-both-invalidate
  (rf.adapter.react-shared-suite/assert-use-sub-siblings-same-query-both-invalidate cfg))

(deftest use-sub-strictmode-double-mount-refcount-balances
  (rf.adapter.react-shared-suite/assert-use-sub-strictmode-double-mount-refcount-balances cfg))

(deftest use-sub-memo-recompute-no-refcount-leak
  (rf.adapter.react-shared-suite/assert-use-sub-memo-recompute-no-refcount-leak cfg))

(deftest use-sub-abandoned-render-no-refcount-leak
  (rf.adapter.react-shared-suite/assert-use-sub-abandoned-render-no-refcount-leak cfg))

(deftest use-sub-suspense-abort-before-commit-no-refcount-leak
  (rf.adapter.react-shared-suite/assert-use-sub-suspense-abort-before-commit-no-refcount-leak cfg))

(deftest use-sub-stable-deps-key
  (rf.adapter.react-shared-suite/assert-use-sub-stable-deps-key cfg))

(deftest use-sub-getsnapshot-tracks-committed-reaction
  (rf.adapter.react-shared-suite/assert-use-sub-getsnapshot-tracks-committed-reaction cfg))

(deftest use-sub-render-phase-reaction-not-retained
  (rf.adapter.react-shared-suite/assert-use-sub-render-phase-reaction-not-retained cfg))

(deftest use-sub-render-to-commit-window-first-commit
  (rf.adapter.react-shared-suite/assert-use-sub-render-to-commit-window-first-commit cfg))

(deftest use-sub-commit-adopts-the-render-phase-reaction
  (rf.adapter.react-shared-suite/assert-use-sub-commit-adopts-the-render-phase-reaction cfg))

(deftest use-sub-browser-runner-schedule-rebuilds
  (rf.adapter.react-shared-suite/assert-use-sub-browser-runner-schedule-rebuilds cfg))

(deftest use-sub-escrow-leg-answers-on-the-public-mount-schedule
  (rf.adapter.react-shared-suite/assert-use-sub-escrow-leg-answers-on-the-public-mount-schedule cfg))

(deftest use-sub-adopted-provisional-reaper-is-a-noop
  (rf.adapter.react-shared-suite/assert-use-sub-adopted-provisional-reaper-is-a-noop cfg))

(deftest use-sub-abandoned-layer-2-render-cascades-at-the-horizon
  (rf.adapter.react-shared-suite/assert-use-sub-abandoned-layer-2-render-cascades-at-the-horizon cfg))

(deftest use-sub-reaped-provisional-is-never-adopted-by-a-later-mount
  (rf.adapter.react-shared-suite/assert-use-sub-reaped-provisional-is-never-adopted-by-a-later-mount cfg))

(deftest use-sub-ssr-render-without-commit-nets-zero-at-the-horizon
  (rf.adapter.react-shared-suite/assert-use-sub-ssr-render-without-commit-nets-zero-at-the-horizon cfg))

(deftest use-sub-key-change-serves-new-target
  (rf.adapter.react-shared-suite/assert-use-sub-key-change-serves-new-target cfg))

(deftest flush-render-synchronously-commits
  (rf.adapter.react-shared-suite/assert-flush-render-synchronously-commits cfg))

(deftest use-sub-cleanup-calls-unsubscribe-with-2-args
  (rf.adapter.react-shared-suite/assert-use-sub-cleanup-calls-unsubscribe-with-2-args cfg))

(deftest view-unmount-emits-on-react-hook-teardown
  (rf.adapter.react-shared-suite/assert-view-unmount-emits-on-react-hook-teardown
    {:substrate-kw :uix :name "UIx"}))

(deftest void-root-view-unmount-no-warning
  (rf.adapter.react-shared-suite/assert-void-root-view-unmount-no-warning
    {:substrate-kw :uix :name "UIx"}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

;; Multiple trailing children exercise the native $ marshalling path.
(deftest frame-provider-trailing-children-propagate-frame
  (testing "UIx — ($ frame-provider {:frame :f} c1 c2) trailing children propagate :frame + render"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (let [frame-kw :rf.uix-use-sub-test/frame-provider-frame
                query-v  [:rf.uix-use-sub-test/k]]
            (binding [rf.frame/*current-frame* nil]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              (reset! probe-frame-provider-observed [])
              (rf/make-frame {:id frame-kw :doc "trailing-children frame-provider probe"})
              (rf/reg-event ::dollar-shape-seed (fn [_ _] {:db {:k :wrapped}}))
              (rf/dispatch-sync [::dollar-shape-seed] {:frame frame-kw})
              (rf/reg-sub (first query-v) (fn [db _] (:k db)))
              (let [mount-node (.createElement js/document "div")
                    root       (react-dom-client/createRoot mount-node)]
                (try
                  (act-fn
                    (fn []
                      (.render root
                        ($ rf.adapter.uix/frame-provider
                           {:frame frame-kw}
                           ($ ProbeFrameProvider)
                           ($ ProbeFrameProvider)))))
                  (is (= 2 (count (filterv #{:wrapped} @probe-frame-provider-observed)))
                      "both trailing children rendered and read the wrapped frame's value, not :rf/default")
                  (finally
                    (try (.unmount root) (catch :default _ nil))))))))))))

(deftest use-sub-reacquires-after-eviction
  (rf.adapter.react-shared-suite/assert-use-sub-reacquires-after-eviction cfg))

(deftest use-sub-stale-cleanup-does-not-release-a-successor
  (rf.adapter.react-shared-suite/assert-use-sub-stale-cleanup-does-not-release-a-successor cfg))

(deftest use-sub-nil-explicit-frame-refuses-without-retaining
  (rf.adapter.react-shared-suite/assert-use-sub-nil-explicit-frame-refuses-without-retaining cfg))

(deftest use-sub-live-frame-value-target-balances
  (rf.adapter.react-shared-suite/assert-use-sub-live-frame-value-target-balances cfg))

(def ^:private reincarnation-frame    :rf.uix-reincarnation/frame)
(def ^:private reincarnation-query    [:rf.uix-reincarnation/n])
(def ^:private reincarnation-set-tick (atom nil))
(def ^:private reincarnation-observed (atom []))

(defui ProbeReincarnationChild []
  (let [ambient  (rf.adapter.uix/use-sub reincarnation-query)
        explicit (rf.adapter.uix/use-sub reincarnation-query {:frame reincarnation-frame})]
    (swap! reincarnation-observed conj [ambient explicit])
    ($ :div (str ambient "/" explicit))))

(defui ProbeReincarnationParent []
  (let [[tick set-tick] (uix/use-state 0)]
    (uix/use-effect
      (fn [] (reset! reincarnation-set-tick set-tick) js/undefined)
      [])
    ($ :div {:data-tick tick}
       ($ ProbeReincarnationChild))))

(defn- reincarnation-ref-count []
  (or (get-in @(:sub-cache (rf.frame/frame reincarnation-frame))
              [reincarnation-query :ref-count])
      0))

(deftest use-sub-follows-a-same-id-reincarnation
  (testing "UIx — a mounted use-sub reads and listens to a same-id successor frame"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (binding [rf.frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (reset! reincarnation-observed [])
            (reset! reincarnation-set-tick nil)
            (rf/reg-event ::reincarnation-set (fn [_ [_ n]] {:db {:n n}}))
            (rf/reg-sub (first reincarnation-query) (fn [db _] (:n db)))
            (rf/make-frame {:id reincarnation-frame :doc "use-sub reincarnation probe — A"})
            (rf/dispatch-sync [::reincarnation-set 1] {:frame reincarnation-frame})
            (let [mount-node (.createElement js/document "div")
                  root       (react-dom-client/createRoot mount-node)
                  re-render! #(act-fn (fn [] (@reincarnation-set-tick inc)))]
              (try
                (act-fn
                  (fn []
                    (.render root
                      ($ rf.adapter.uix/frame-provider {:frame reincarnation-frame}
                         ($ ProbeReincarnationParent)))))
                (is (= [1 1] (peek @reincarnation-observed)) "mounted against A")

                (testing "control: a re-render under the same incarnation keeps
                          the two durable references and the value"
                  (re-render!)
                  (is (= [[1 1] 2] [(peek @reincarnation-observed) (reincarnation-ref-count)])))

                (rf/destroy-frame! reincarnation-frame)
                (rf/make-frame {:id reincarnation-frame :doc "use-sub reincarnation probe — B"})
                (rf/dispatch-sync [::reincarnation-set 2] {:frame reincarnation-frame})

                (testing "the next render of the SAME instance reads the successor,
                          in the ambient and the explicit form alike"
                  (re-render!)
                  (is (= [2 2] (peek @reincarnation-observed))))

                (testing "and listens to it: a later write to the successor
                          re-renders the instance"
                  (act-fn (fn [] (rf/dispatch-sync [::reincarnation-set 3]
                                                   {:frame reincarnation-frame})))
                  (is (= [3 3] (peek @reincarnation-observed))))

                (testing "and the successor's sub-cache references balance: two
                          while mounted, none after unmount"
                  (is (= 2 (reincarnation-ref-count)))
                  (act-fn (fn [] (.unmount root)))
                  (is (zero? (reincarnation-ref-count))))
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))
