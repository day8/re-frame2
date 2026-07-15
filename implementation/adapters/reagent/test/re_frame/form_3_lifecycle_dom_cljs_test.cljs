(ns re-frame.form-3-lifecycle-dom-cljs-test
  "Frozen stock-Reagent compatibility proof for the Form-3 migration recipe.

  The supported shape is deliberately small: `reg-view*` registers a per-mount
  outer callable, that callable captures one immutable frame handle, and the
  returned `reagent.core/create-class` closes over it. Lifecycle callbacks run
  after ambient resolver scope has unwound, so one-shot reads and imperative
  teardown name the captured frame explicitly. Ordinary reactive deref stays in
  `:reagent-render`; `r/with-let` releases that render-owned cache reference only
  when Reagent destroys the render owner.

  Browser-only because the proof needs real React class mount/unmount ordering.
  The `-dom-cljs-test` suffix selects the existing `:browser-test` build; the
  consolidated node build loads the namespace but takes the no-DOM branch."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter :async? true :ambient-frame nil}))

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

(defn- get-react-act []
  ;; React 19 exports act directly. Unlike flushSync, this drives StrictMode's
  ;; complete mount→unmount→mount replay and returns a thenable we can await.
  (.-act React))

(defn- next-microtask []
  ;; Stock Reagent defers real render-reaction disposal to a microtask so a
  ;; StrictMode remount can cancel it. A promise queued after unmount observes
  ;; the completed genuine-unmount cleanup.
  (js/Promise.resolve nil))

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so Reagent's render queue and React's
  StrictMode remount have both settled before lifecycle assertions run."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [remaining]
                (if (zero? remaining)
                  (resolve nil)
                  (js/setTimeout #(step (dec remaining)) 4)))]
        (step n)))))

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

(defn- form-3-class [events instance-id handle]
  (let [{:keys [frame subscribe]} handle
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
         ;; `with-let` acquires it once for that owner and runs `finally`
         ;; only when Reagent really destroys the owner. In particular, it
         ;; does NOT release during StrictMode's transient class
         ;; will-unmount/did-mount replay.
         (r/with-let [render-reaction (subscribe render-query)]
           (let [value @render-reaction]
             (record! :render {:value value})
             [:div {:data-form-3-instance (name instance-id)} (str value)])
           (finally
             (rf/unsubscribe frame render-query)
             (record! :render-release {}))))

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
           (swap! state #(-> %
                             (update :mount-count inc)
                             (assoc :mounted? true)))
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
           ;; This user lifecycle owns only the imperative subscription.
           ;; Reagent owns the render reaction through `with-let`; releasing
           ;; it here would break StrictMode's transient replay.
           (rf/unsubscribe frame lifecycle-query)
           (swap! state assoc :mounted? false)
           (record! :will-unmount
                    {:bare-unsubscribe-error bare-unsubscribe-error
                     :before-bare before-bare
                     :after-bare after-bare
                     :after-explicit (cache-state frame)})))})))

(defn- register-form-3-fixture! [view-id events]
  (rf/reg-view* view-id
    (fn form-3-outer [instance-id]
      ;; This is the only ambient capture site. It runs once per mount under
      ;; reg-view*'s context-aware wrapper; neither render nor a hook mutates or
      ;; replaces the locked handle.
      (form-3-class events instance-id (rf/capture-frame)))))

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
      (async done
        (let [act-fn (get-act)]
          (if-not (fn? act-fn)
            (do (is true "React flushSync unavailable in this runner") (done))
            (let [events (atom [])
                  done? (atom false)
                  done! (fn [] (when (compare-and-set! done? false true) (done)))
                  view-id ::isolated-form-3
                  _ (setup-frames-and-subs!)
                  _ (register-form-3-fixture! view-id events)
                  view (rf/view view-id)
                  root-a (rdc/create-root (.createElement js/document "div"))
                  root-b (rdc/create-root (.createElement js/document "div"))
                  cleanup! (fn []
                             (try (act-fn #(rdc/unmount root-a)) (catch :default _ nil))
                             (try (act-fn #(rdc/unmount root-b)) (catch :default _ nil)))
                  fail! (fn [err]
                          (is false (str "Form-3 isolation fixture threw: " (pr-str err)))
                          (cleanup!)
                          (done!))]
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
                  (is (every? #(no-frame-context? (:bare-once-error %)) mount-events)
                      "bare subscribe-once in did-mount raises no-frame-context")
                  (is (every? #(no-frame-context? (:hook-capture-error %)) mount-events)
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
                      "imperative captured subscribe reads the locked frame"))

                (is (= (expected-cache 2) (cache-state frame-a))
                    "A cache records two render owners and two imperative owners")
                (let [b-mounted-cache (cache-state frame-b)]
                  (is (= (expected-cache 1) b-mounted-cache)
                      "B holds the same queries in B's independent cache")

                  ;; User lifecycle teardown is synchronous; Reagent-owned
                  ;; render cleanup is deliberately deferred one microtask.
                  (act-fn #(rdc/unmount root-a))
                  (is (= 0 (ref-count (cache-state frame-a) lifecycle-query))
                      "A's hook-owned subscriptions release synchronously")
                  (is (= 2 (ref-count (cache-state frame-a) render-query))
                      "A's render owners survive until Reagent's real-unmount cleanup")
                  (is (= b-mounted-cache (cache-state frame-b))
                      "A teardown leaves B's same-query cache byte-for-byte alone"))

                (let [a-unmounts (filter #(and (= :will-unmount (:phase %))
                                               (= frame-a (:frame %)))
                                         @events)]
                  (is (= 2 (count a-unmounts)) "each A instance cleaned up once")
                  (doseq [{:keys [bare-unsubscribe-error before-bare after-bare
                                  after-explicit]} a-unmounts]
                    (is (no-frame-context? bare-unsubscribe-error)
                        "bare will-unmount unsubscribe raises no-frame-context")
                    (is (= before-bare after-bare)
                        "the failed bare teardown does not alter cache state")
                    (is (= (dec (ref-count before-bare lifecycle-query))
                           (ref-count after-explicit lifecycle-query))
                        "explicit frame-first teardown releases exactly one owner")))

                (act-fn #(rdc/unmount root-b))
                (-> (next-microtask)
                    (.then
                      (fn [_]
                        (is (= {} (cache-state frame-a))
                            "A reaches exact baseline after Reagent's deferred cleanup")
                        (is (= {} (cache-state frame-b))
                            "B reaches exact baseline after its deferred cleanup")
                        (is (= 3 (count (filter #(= :render-release (:phase %))
                                                @events)))
                            "each Reagent-owned render reaction releases exactly once")
                        (cleanup!)
                        (done!)))
                    (.catch fail!))
                (catch :default e (fail! e))))))))))

(deftest keyed-provider-retarget-remounts-the-locked-capture
  (testing "provider A→B changes the Form-3 key: outgoing cleanup uses its
            locked A handle, incoming outer/render/mount use B, and no A work
            occurs after A's will-unmount"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises the fixture")
      (async done
        (let [act-fn (get-act)]
          (if-not (fn? act-fn)
            (do (is true "React flushSync unavailable in this runner") (done))
            (let [events (atom [])
                  done? (atom false)
                  done! (fn [] (when (compare-and-set! done? false true) (done)))
                  view-id ::retargeted-form-3
                  _ (setup-frames-and-subs!)
                  _ (register-form-3-fixture! view-id events)
                  view (rf/view view-id)
                  root (rdc/create-root (.createElement js/document "div"))
                  cleanup! #(try (act-fn (fn [] (rdc/unmount root)))
                                 (catch :default _ nil))
                  fail! (fn [err]
                          (is false (str "Form-3 retarget fixture threw: " (pr-str err)))
                          (cleanup!)
                          (done!))]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              (try
                (act-fn #(rdc/render root (retarget-tree frame-a view)))
                (is (= (expected-cache 1) (cache-state frame-a))
                    "initial keyed mount owns A")

                (act-fn #(rdc/render root (retarget-tree frame-b view)))
                (is (= 0 (ref-count (cache-state frame-a) lifecycle-query))
                    "outgoing A hook-owned subscription releases at will-unmount")
                (is (= 1 (ref-count (cache-state frame-a) render-query))
                    "outgoing A render owner awaits Reagent's cleanup microtask")
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
                      "A will-unmount commits before B did-mount")
                  (is (every? #(= frame-b (:frame %))
                              (map second
                                   (remove #(= :render-release (:phase (second %)))
                                           (drop (inc (first a-unmount)) indexed))))
                      "no later user lifecycle/render action uses stale A")
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

                (-> (next-microtask)
                    (.then
                      (fn [_]
                        (is (= {} (cache-state frame-a))
                            "outgoing A reaches baseline after deferred render cleanup")
                        (is (= (expected-cache 1) (cache-state frame-b))
                            "A's deferred cleanup leaves B byte-for-byte intact")
                        (act-fn #(rdc/unmount root))
                        (next-microtask)))
                    (.then
                      (fn [_]
                        (is (= {} (cache-state frame-b))
                            "final B unmount restores B's exact baseline")
                        (cleanup!)
                        (done!)))
                    (.catch fail!))
                (catch :default e (fail! e))))))))))

(deftest strict-mode-replay-preserves-render-ownership
  (testing "React 19 StrictMode's transient will-unmount/did-mount replay
            balances hook-owned subscriptions without disposing the
            Reagent-owned render reaction; a post-replay update still renders"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test exercises StrictMode")
      (async done
        (let [act-fn (get-react-act)]
          (if-not (fn? act-fn)
            (do (is true "React.act unavailable in this runner") (done))
            (let [events (atom [])
                  done? (atom false)
                  done! (fn [] (when (compare-and-set! done? false true) (done)))
                  _ (setup-frames-and-subs!)
                  ;; Build one stable class constructor from an explicit
                  ;; capture. The registered outer-callable path is covered by
                  ;; the two tests above; stability here lets React replay this
                  ;; exact committed class lifecycle under root StrictMode.
                  klass (form-3-class events :strict-one
                                            (rf/capture-frame frame-a))
                  root (rdc/create-root (.createElement js/document "div"))
                  tree [rf/frame-provider {:frame frame-a}
                        [klass :strict-one]]
                  cleanup! #(try (rdc/unmount root) (catch :default _ nil))
                  fail! (fn [err]
                          (is false (str "Form-3 StrictMode fixture threw: " (pr-str err)))
                          (cleanup!)
                          (done!))]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              ;; The four-arity stock-Reagent renderer installs StrictMode
              ;; outside its two internal bridge components. An authored
              ;; `[:> React.StrictMode ...]` would sit below those bridges, and
              ;; React intentionally does not run the initial lifecycle replay
              ;; when StrictMode is not at the actual root.
              (-> (js/Promise.resolve
                    (act-fn #(rdc/render root tree nil true)))
                  (.then (fn [_] (settle-macrotasks 3)))
                  (.then
                    (fn [_]
                      (let [mounts (filter #(and (= :did-mount (:phase %))
                                                 (= frame-a (:frame %))) @events)
                            transient-unmounts
                            (filter #(and (= :will-unmount (:phase %))
                                          (= frame-a (:frame %))) @events)]
                        (is (>= (count mounts) 2)
                            "StrictMode replayed component-did-mount")
                        (is (>= (count transient-unmounts) 1)
                            "StrictMode replayed component-will-unmount")
                        (is (= (inc (count transient-unmounts)) (count mounts))
                            "the mounted state has one live acquisition after replay")
                        (is (= (expected-cache 1) (cache-state frame-a))
                            "StrictMode leaves one render owner and one hook owner")
                        (is (empty? (filter #(= :render-release (:phase %)) @events))
                            "transient will-unmount did not release render ownership"))
                      (js/Promise.resolve
                        (act-fn
                          (fn []
                            (rf/dispatch-sync [::seed 11] {:frame frame-a})
                            (r/flush))))))
                  (.then
                    (fn [_]
                      (let [renders (filter #(and (= :render (:phase %))
                                                  (= frame-a (:frame %))) @events)]
                        (is (= 11 (:value (last renders)))
                            "post-replay app-db update re-rendered the Form-3 body")
                        (is (= (expected-cache 1) (cache-state frame-a))
                            "post-replay render did not duplicate either owner"))
                      (js/Promise.resolve (act-fn #(rdc/unmount root)))))
                  (.then (fn [_] (next-microtask)))
                  (.then
                    (fn [_]
                      (let [mounts (filter #(= :did-mount (:phase %)) @events)
                            unmounts (filter #(= :will-unmount (:phase %)) @events)]
                        (is (= (count mounts) (count unmounts))
                            "real unmount balances every StrictMode hook acquisition")
                        (is (= 1 (count (filter #(= :render-release (:phase %))
                                                @events)))
                            "Reagent destroys the retained render owner exactly once")
                        (is (= {} (cache-state frame-a))
                            "real unmount restores the exact cache baseline"))
                      (cleanup!)
                      (done!)))
                  (.catch fail!)))))))))
