(ns re-frame.adapter.capabilities-cljs-test
  "An adapter's capabilities are keys on its map (Spec 006 §The adapter API
  contract). `install-adapter!` publishes exactly the installed map's into the
  `:adapter/*` late-bind slots — a key the map lacks as absent — and teardown
  disposes through the departing map before it clears them. Real Reagent and
  UIx adapters are both loaded here, so a capability that answered for a
  loaded-but-uninstalled adapter would show.

  The rollback and stale-finalization cases run on both hosts in
  `re-frame.adapter.ssr-emitter-replay-atomic-cljs-test`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent]
            [re-frame.adapter.uix :as uix]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; Each test installs its own adapter; the tail leaves the slot never-installed
;; for whichever namespace runs next.
(use-fixtures :each
  (let [reset-runtime (rf.test-support/make-reset-runtime-fixture {})]
    (fn [t]
      (reset-runtime
        (fn []
          (try
            (t)
            (finally
              (rf.substrate.adapter/dispose-adapter!)
              (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))))))

(def ^:private reagent-only-slots
  "The slots Reagent's map fills and UIx's leaves absent."
  [:adapter/current-component :adapter/ratom :adapter/ratom?
   :adapter/make-reaction :adapter/activate-derived-value! :adapter/reactive?
   :adapter/reactive-owner :adapter/read-container-untracked
   :adapter/derived-container?])

(defn- published
  "The slots in `ks` that resolve to something, as `{slot value}`."
  [ks]
  (into {} (keep (fn [k] (when-some [f (rf.late-bind/get-fn k)] [k f]))) ks))

(deftest a-copied-map-overrides-a-capability
  (let [scheduled (atom [])
        traced    (fn traced-after-render [f] (swap! scheduled conj f) nil)
        f         (fn [] :after)]
    (rf/init! (assoc reagent/adapter :after-render traced))
    (rf.interop/after-render f)
    (is (= [f] @scheduled)
        "the copy's own :after-render answers, not the closure Reagent's namespace built")))

(deftest a-loaded-adapter-contributes-nothing-until-installed
  ;; UIx is loaded (required above) but Reagent is the installed adapter.
  (rf/init! reagent/adapter)
  (is (= {} (published [:adapter/wrap-view :adapter/componentize-view]))
      "UIx's view capabilities do not answer while Reagent is installed"))

(deftest a-view-registered-before-init-takes-the-installed-adapters-head
  (let [id ::row-before-init]
    (rf/reg-view* id (fn [] [:div "row"]))
    (rf/init! uix/adapter)
    (is (true? (.-uix-component? ^js (rf/view id)))
        "the head is UIx's marked component, derived against the adapter installed at lookup")))

(deftest a-swap-leaves-no-capability-of-the-departed-adapter
  (rf/init! reagent/adapter)
  (is (= (set reagent-only-slots) (set (keys (published reagent-only-slots))))
      "control: Reagent fills every one of these slots")
  (rf/destroy-adapter!)
  (rf/init! uix/adapter)
  (is (= {} (published reagent-only-slots))
      "after the swap no Reagent-only slot resolves")
  (is (identical? (:after-render uix/adapter) (rf.late-bind/get-fn :adapter/after-render))
      "and the shared slots hold UIx's own capabilities"))

(defn- seed-layered-subs! [frame-id]
  (rf/reg-event ::seed (fn [_ctx [_ v]] {:db {:n v}}))
  (rf/reg-sub ::n (fn [db _] (:n db)))
  (rf/reg-sub ::n+1 {:inputs [[::n]]} (fn [[n] _] (inc n)))
  (rf/make-frame {:id frame-id})
  (rf/dispatch-sync [::seed 1] {:frame frame-id})
  (is (= 2 @(rf/subscribe [::n+1] {:frame frame-id})) "control: the layer-2 sub computes"))

(defn- assert-teardown-disposes-through-the-departing-map [adapter frame-id]
  (let [seen     (atom [])
        original (:dispose! adapter)
        counting (fn counting-dispose! [x]
                   (swap! seen conj [x (rf.substrate.adapter/current-adapter)])
                   (original x))]
    (rf/init! (assoc adapter :dispose! counting))
    (seed-layered-subs! frame-id)
    (let [cached (set (keep :reaction (vals @(:sub-cache (rf.frame/frame frame-id)))))]
      (is (= 2 (count cached)) "control: the layer-2 sub and its input are cached")
      (rf/destroy-adapter!)
      (is (= cached (set (map first @seen)))
          "every cached derived value went through the departing map's :dispose!")
      (is (every? nil? (map second @seen))
          "although current-adapter already read nil when it ran")
      (is (= {} @(:sub-cache (rf.frame/frame frame-id)))
          "core's teardown emptied the frame's sub-cache"))))

(deftest teardown-disposes-through-the-departing-ratom-map
  (assert-teardown-disposes-through-the-departing-map reagent/adapter ::ratom-frame))

(deftest teardown-disposes-through-the-departing-plain-atom-map
  (assert-teardown-disposes-through-the-departing-map rf.substrate.plain-atom/adapter
                                                      ::plain-atom-frame))

(deftest teardown-walks-every-frame-and-surfaces-the-first-dispose-failure
  ;; Two entries in one cache, so a walk cut short to each cache's first entry
  ;; fails here; the poison entry's dispose throws without stopping the walk.
  (let [disposed (atom #{})
        poison   (js/Error. "poison dispose")
        dispose! (fn [x]
                   (when (= ::poison x) (throw poison))
                   (swap! disposed conj x))]
    (rf/init! (assoc rf.substrate.plain-atom/adapter :dispose! dispose!))
    (rf/make-frame {:id ::walk-a})
    (rf/make-frame {:id ::walk-b})
    (reset! (:sub-cache (rf.frame/frame ::walk-a))
            {[::x] {:reaction ::a-x} [::y] {:reaction ::a-y} [::p] {:reaction ::poison}})
    (reset! (:sub-cache (rf.frame/frame ::walk-b)) {[::z] {:reaction ::b-z}})
    (let [thrown (try (rf/destroy-adapter!) nil (catch :default e e))]
      (is (identical? poison thrown) "the dispose failure reaches the destroy-adapter! caller")
      (is (= #{::a-x ::a-y ::b-z} @disposed) "every other entry in every frame was disposed")
      (is (= [{} {}] [@(:sub-cache (rf.frame/frame ::walk-a)) @(:sub-cache (rf.frame/frame ::walk-b))])
          "both caches were emptied")
      (is (and (nil? (rf/current-adapter)) (rf.substrate.adapter/adapter-disposed?))
          "the teardown still finalized"))))

(deftest a-same-kind-reinit-republishes-the-fresh-maps-capabilities
  ;; Hot reload: re-evaluating an adapter namespace hands `init!` a map of the
  ;; same canonical kind with fresh function identities.
  (rf/init! reagent/adapter)
  (let [scheduled (atom 0)
        reloaded  (assoc reagent/adapter
                         :after-render (fn [_f] (swap! scheduled inc) nil))]
    (rf/init! reloaded)
    (rf.interop/after-render (fn []))
    (is (= 1 @scheduled) "the reloaded map's capability answers")
    (is (identical? reagent/adapter (rf/current-adapter))
        "the seated generation, which owns the live containers and roots, is kept")
    (testing "an identical re-init has nothing new to publish"
      (let [stub (fn [_f] nil)]
        (rf.late-bind/set-fn! :adapter/after-render stub)
        (rf/init! reagent/adapter)
        (is (identical? stub (rf.late-bind/get-fn :adapter/after-render)))))))
