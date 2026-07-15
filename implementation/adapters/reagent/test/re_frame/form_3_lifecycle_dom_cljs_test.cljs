(ns re-frame.form-3-lifecycle-dom-cljs-test
  "Frozen stock-Reagent compatibility proof for the Form-3 migration recipe.

  The supported shape is deliberately small: `reg-view*` registers a per-mount
  outer callable, that callable captures one immutable frame handle, and the
  returned `reagent.core/create-class` closes over it. Lifecycle callbacks run
  after ambient resolver scope has unwound, so one-shot reads and imperative
  teardown name the captured frame explicitly. Ordinary reactive deref stays in
  `:reagent-render`; the outer callable's cache reference is released explicitly.

  Browser-only because the proof needs real React class mount/unmount ordering.
  The `-dom-cljs-test` suffix selects the existing `:browser-test` build; the
  consolidated node build loads the namespace but takes the no-DOM branch."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter :ambient-frame nil}))

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)
(def ^:private render-query [::render-value])
(def ^:private source-query [::source-value])
(def ^:private lifecycle-query [::lifecycle-value])

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  ;; Keep this synchronous: returning React 19's act() thenable from a plain
  ;; deftest makes cljs.test wait for an async result the test did not declare.
  react-dom/flushSync)

(defn- caught [f]
  (try
    (f)
    nil
    (catch :default e e)))

(defn- no-frame-context? [e]
  (= :rf.error/no-frame-context (:rf.error/id (ex-data e))))

(defn- cache-state
  "Stable, reaction-free view of a frame's real subscription cache."
  [frame-id]
  (into {}
        (map (fn [[query-v {:keys [inputs ref-count]}]]
               [query-v {:inputs inputs :ref-count ref-count}]))
        @(:sub-cache (frame/frame frame-id))))

(defn- ref-count [cache query-v]
  (or (get-in cache [query-v :ref-count]) 0))

(defn- setup-frames-and-subs! []
  (rf/make-frame {:id frame-a :doc "Form-3 lifecycle fixture frame A"})
  (rf/make-frame {:id frame-b :doc "Form-3 lifecycle fixture frame B"})
  (rf/reg-event ::seed
    (fn [{:keys [db]} [_ n]]
      {:db (assoc db :n n)}))
  (rf/reg-sub ::render-value (fn [db _] (:n db)))
  (rf/reg-sub ::source-value (fn [db _] (:n db)))
  (rf/reg-sub ::lifecycle-value
    :<- source-query
    (fn [n _] (* 2 n)))
  (rf/dispatch-sync [::seed 10] {:frame frame-a})
  (rf/dispatch-sync [::seed 100] {:frame frame-b}))

(defn- register-form-3-fixture! [view-id events]
  (rf/reg-view* view-id
    (fn form-3-outer [instance-id]
      ;; This is the only ambient capture site. It runs once per mount under
      ;; reg-view*'s context-aware wrapper; neither render nor a hook mutates or
      ;; replaces the locked handle.
      (let [{:keys [frame subscribe] :as handle} (rf/capture-frame)
            ;; Frozen classic-Reagent spelling: acquire once in the outer
            ;; callable, deref reactively in reagent-render, and release the
            ;; matching cache reference explicitly on class teardown.
            render-reaction (subscribe render-query)
            state (r/atom {:instance instance-id :mount-count 0})
            record! (fn [phase more]
                      (swap! events conj
                             (merge {:phase phase
                                     :instance instance-id
                                     :frame frame
                                     :handle handle
                                     :state state}
                                    more)))]
        (record! :outer {})
        (r/create-class
          {:display-name "rf2-form-3-lifecycle-fixture"

           :reagent-render
           (fn [_instance-id]
             ;; The ordinary reactive read belongs to Reagent's render owner.
             (let [value @render-reaction]
               (record! :render {:value value})
               [:div {:data-form-3-instance (name instance-id)} (str value)]))

           :component-did-mount
           (fn [_this]
             ;; Negative controls execute after resolver scope has unwound.
             (let [bare-once-error (caught #(rf/subscribe-once lifecycle-query))
                   hook-capture-error (caught rf/capture-frame)
                   ;; The migration recipe's explicit one-shot form retains no
                   ;; cache reference.
                   one-shot-value
                   (rf/subscribe-once lifecycle-query {:frame frame})
                   ;; Deliberately rare imperative subscription: this fixture
                   ;; owns it and pairs it with frame-first teardown below.
                   live-value @(subscribe lifecycle-query)]
               (swap! state update :mount-count inc)
               (record! :did-mount
                        {:bare-once-error bare-once-error
                         :hook-capture-error hook-capture-error
                         :one-shot-value one-shot-value
                         :live-value live-value})))

           :component-will-unmount
           (fn [_this]
             (let [before-bare (cache-state frame)
                   bare-unsubscribe-error
                   (caught #(rf/unsubscribe lifecycle-query))
                   after-bare (cache-state frame)]
               ;; The failed bare teardown must not be mistaken for cleanup.
               ;; The explicit frame-first forms release exactly the refs this
               ;; outer callable acquired for lifecycle and reactive render.
               (rf/unsubscribe frame lifecycle-query)
               (rf/unsubscribe frame render-query)
               (swap! state assoc :unmounted? true)
               (record! :will-unmount
                        {:bare-unsubscribe-error bare-unsubscribe-error
                         :before-bare before-bare
                         :after-bare after-bare
                         :after-explicit (cache-state frame)})))})))))

(defn- sibling-tree [frame-id view instance-ids]
  [rf/frame-provider {:frame frame-id}
   (into [:div]
         (map (fn [instance-id]
                (with-meta [view instance-id]
                  {:key (name instance-id)})))
         instance-ids)])

(defn- retarget-tree [frame-id view]
  [rf/frame-provider {:frame frame-id}
   ;; Capture-once is frame-invariant. A provider retarget therefore changes
   ;; the child key so React destroys A and creates a newly captured B mount.
   (with-meta [view :switchable]
     {:key (str frame-id)})])

(defn- expected-cache [holders]
  {render-query {:inputs [] :ref-count holders}
   lifecycle-query {:inputs [source-query] :ref-count holders}
   source-query {:inputs [] :ref-count 1}})

(deftest outer-capture-is-instance-and-frame-exact
  (testing "two A mounts have isolated captures/state; a same-query B mount is
            isolated; bare hook operations fail loudly; explicit teardown and
            class-owned render cleanup restore each frame's cache baseline"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the fixture")
      (let [act-fn (get-act)]
        (if-not (fn? act-fn)
          (is true "React act() unavailable in this runner")
          (let [events (atom [])
                view-id ::isolated-form-3
                _ (setup-frames-and-subs!)
                _ (register-form-3-fixture! view-id events)
                view (rf/view view-id)
                root-a (rdc/create-root (.createElement js/document "div"))
                root-b (rdc/create-root (.createElement js/document "div"))]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (try
              (act-fn #(rdc/render root-a
                                   (sibling-tree frame-a view [:a-one :a-two])))
              (act-fn #(rdc/render root-b
                                   (sibling-tree frame-b view [:b-one])))

              (let [outer-events (filter #(= :outer (:phase %)) @events)
                    a-outers (filter #(= frame-a (:frame %)) outer-events)
                    mount-events (filter #(= :did-mount (:phase %)) @events)]
                (is (= 2 (count a-outers)) "both A instances ran the outer callable")
                (is (= 3 (count outer-events)) "one fresh outer capture per mount")
                (is (not (identical? (:handle (first a-outers))
                                     (:handle (second a-outers))))
                    "A instances do not share a captured frame bundle")
                (is (not (identical? (:state (first a-outers))
                                     (:state (second a-outers))))
                    "A instances do not share lifecycle state")
                (is (every? #(= 1 (:mount-count @(:state %))) outer-events)
                    "each per-instance state saw exactly its own mount")
                (is (every? #(no-frame-context? (:bare-once-error %))
                            mount-events)
                    "bare subscribe-once in did-mount raises no-frame-context")
                (is (every? #(no-frame-context? (:hook-capture-error %))
                            mount-events)
                    "capture-frame attempted in did-mount raises no-frame-context")
                (is (= {frame-a #{20} frame-b #{200}}
                       (into {}
                             (map (fn [[fid xs]]
                                    [fid (set (map :one-shot-value xs))]))
                             (group-by :frame mount-events)))
                    "explicit subscribe-once reads the captured A/B frame")
                (is (= {frame-a #{20} frame-b #{200}}
                       (into {}
                             (map (fn [[fid xs]]
                                    [fid (set (map :live-value xs))]))
                             (group-by :frame mount-events)))
                    "imperative captured subscribe reads the same locked frame"))

              (is (= (expected-cache 2) (cache-state frame-a))
                  "A cache records two render owners and two imperative owners")
              (let [b-mounted-cache (cache-state frame-b)]
                (is (= (expected-cache 1) b-mounted-cache)
                    "B holds the same queries in B's independent cache")

                ;; Unmounting both A instances must not decrement or dispose B.
                (act-fn #(rdc/unmount root-a))
                (is (= {} (cache-state frame-a))
                    "A render + lifecycle refcounts/dependencies return to baseline")
                (is (= b-mounted-cache (cache-state frame-b))
                    "A teardown leaves B's same-query cache byte-for-byte alone"))

              (let [a-unmounts (filter #(and (= :will-unmount (:phase %))
                                             (= frame-a (:frame %)))
                                       @events)]
                (is (= 2 (count a-unmounts)) "each A instance cleaned up once")
                (doseq [{:keys [bare-unsubscribe-error before-bare after-bare
                                after-explicit]}
                        a-unmounts]
                  (is (no-frame-context? bare-unsubscribe-error)
                      "bare will-unmount unsubscribe raises no-frame-context")
                  (is (= before-bare after-bare)
                      "the failed bare teardown does not alter cache state")
                  (is (= (dec (ref-count before-bare lifecycle-query))
                         (ref-count after-explicit lifecycle-query))
                      "explicit frame-first teardown releases exactly one owner")))

              (act-fn #(rdc/unmount root-b))
              (is (= {} (cache-state frame-b))
                  "B returns to its own empty baseline after its unmount")
              (finally
                (try (act-fn #(rdc/unmount root-a)) (catch :default _ nil))
                (try (act-fn #(rdc/unmount root-b)) (catch :default _ nil))))))))))

(deftest keyed-provider-retarget-remounts-the-locked-capture
  (testing "provider A→B changes the Form-3 key: outgoing cleanup uses its
            locked A handle, incoming outer/render/mount use B, and no A work
            occurs after A's will-unmount"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the fixture")
      (let [act-fn (get-act)]
        (if-not (fn? act-fn)
          (is true "React act() unavailable in this runner")
          (let [events (atom [])
                view-id ::retargeted-form-3
                _ (setup-frames-and-subs!)
                _ (register-form-3-fixture! view-id events)
                view (rf/view view-id)
                root (rdc/create-root (.createElement js/document "div"))]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (try
              (act-fn #(rdc/render root (retarget-tree frame-a view)))
              (is (= (expected-cache 1) (cache-state frame-a))
                  "initial keyed mount owns A")

              (act-fn #(rdc/render root (retarget-tree frame-b view)))
              (is (= {} (cache-state frame-a))
                  "outgoing A cleanup releases A completely")
              (is (= (expected-cache 1) (cache-state frame-b))
                  "incoming keyed mount owns B")

              (let [indexed (map-indexed vector @events)
                    a-unmount (first (filter (fn [[_ e]]
                                               (and (= :will-unmount (:phase e))
                                                    (= frame-a (:frame e))))
                                             indexed))
                    b-mount (first (filter (fn [[_ e]]
                                             (and (= :did-mount (:phase e))
                                                  (= frame-b (:frame e))))
                                           indexed))
                    outers (filter #(= :outer (:phase %)) @events)
                    b-phases (set (map :phase (filter #(= frame-b (:frame %))
                                                      @events)))]
                (is (some? a-unmount) "changing the key causes outgoing A unmount")
                (is (some? b-mount) "the replacement class mounts under B")
                (is (< (first a-unmount) (first b-mount))
                    "A cleanup commits before B did-mount")
                (is (every? #(= frame-b (:frame %))
                            (map second (drop (inc (first a-unmount)) indexed)))
                    "no later action uses the stale A capture")
                (is (every? b-phases [:outer :render :did-mount])
                    "incoming outer factory, reagent-render and lifecycle all own B")
                (is (= 2 (count outers))
                    "the keyed retarget creates a second per-mount outer capture")
                (is (not (identical? (:handle (first outers))
                                     (:handle (second outers))))
                    "B does not mutate or reuse A's locked handle")
                (is (not (identical? (:state (first outers))
                                     (:state (second outers))))
                    "the singleton-to-outer migration creates fresh mount state"))

              (act-fn #(rdc/unmount root))
              (is (= {} (cache-state frame-b))
                  "final B unmount restores B's cache baseline")
              (finally
                (try (act-fn #(rdc/unmount root)) (catch :default _ nil))))))))))
