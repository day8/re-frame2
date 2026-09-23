(ns re-frame.subs-render-owned-retention-cljs-test
  "A render-owned holding (Spec 006 §Which lifetime governs a ratom adapter,
  rf2-ty246) ends with EITHER end of it: the owner's dispose or the claimed
  reaction's. This namespace pins the second half, the one rf2-3x7nj.3.1
  found missing.

  THE DEFECT. `re-frame.subs/claim-render-owned-ref!` tied each holding to
  the OWNER alone: every new claim recorded the reaction on the owner and
  pushed one more release closure onto the owner's on-dispose callbacks, and
  nothing removed either when the CLAIMED reaction was disposed. So a mounted
  component whose conditional read was toggled, or whose parametric query
  changed, kept every disposed reaction it had ever read — each still closing
  over its memo's last app-db — for as long as it stayed mounted.

  WHICH DIRECTION EACH ROW PINS.

    * `...-toggled-read-...` and `...-parametric-read-...` are the RED rows:
      the owner's callbacks must not grow with claims, and no reaction the
      owner no longer reads may still be reachable from its holdings.
    * `...-owner-churn-...` is GREEN IN BOTH DIRECTIONS on the old code and
      pins the MIRROR failure a repair could introduce: the claimed reaction
      must not accumulate a callback, or a record, per owner that ever read it.

  WHY THIS LANE. `claim-render-owned-ref!` is `#?(:cljs ...)`-only and fires
  only under the `:adapter/reactive-owner` hook the ratom family publishes, so
  no JVM artefact reaches it. Both ratom adapters run through one scenario
  body. As in `re-frame.subs-render-owned-frame-slot-cljs-test`, a
  `make-reaction` driven by `activate!`/`run` and `flush!` IS the owning
  render reaction, and each re-render is driven off a plain ratom the test
  owns, so a render is one hop from the test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.ratom :as stock-ratom]
            [reagent2.ratom :as slim-ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]))

;; ---- per-adapter configuration --------------------------------------------

(def ^:private slim-config
  {:label     "reagent-slim"
   :adapter   rf.adapter.reagent-slim/adapter
   :ratom     slim-ratom/atom
   :reaction  slim-ratom/make-reaction
   :start!    slim-ratom/activate!
   :flush!    slim-ratom/flush!
   :release!  slim-ratom/dispose!})

(def ^:private stock-config
  {:label     "reagent"
   :adapter   rf.adapter.reagent/adapter
   :ratom     stock-ratom/atom
   :reaction  stock-ratom/make-reaction
   :start!    stock-ratom/run
   :flush!    stock-ratom/flush!
   :release!  stock-ratom/dispose!})

;; ---- harness --------------------------------------------------------------

(def ^:private frame-id ::frame)

(def ^:private cycles
  "Claims per scenario. Enough that growth reads unmistakably as growth."
  5)

(defn- with-adapter
  "Cold-start `adapter`, run `body-fn`, then tear the lifecycle back down —
  the fixture `re-frame.subs-render-owned-frame-slot-cljs-test` uses."
  [adapter body-fn]
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (reset! rf.frame/frames {})
  (rf/init! adapter)
  (rf.frame/ensure-default-frame!)
  (try
    (body-fn)
    (finally
      (when (rf.substrate.adapter/current-adapter)
        (rf.substrate.adapter/dispose-adapter!))
      (reset! rf.frame/frames {})
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))

(defn- register-fixtures! []
  (rf/make-frame {:id frame-id})
  (rf/reg-event ::seed (fn [_ _] {:db {:n 1 :details "d" :items (vec (range 100))}}))
  (rf/reg-sub ::n (fn [db _] (:n db)))
  (rf/reg-sub ::details (fn [db _] (:details db)))
  (rf/reg-sub ::item (fn [db [_ i]] (get-in db [:items i])))
  (rf/dispatch-sync [::seed] {:frame frame-id}))

(defn- cached
  "The sub-cache entry `frame-id` holds for `query-v`, or nil."
  [query-v]
  (some-> (get @rf.frame/frames frame-id) :sub-cache deref (get query-v)))

(defn- owner-harness
  "An owning reaction whose body is `(read-fn @lever)`. `lever` is a plain
  ratom the test owns, so `pull!` is exactly one re-render with a new
  argument. `read-fn` records every reaction `subscribe` hands it in `seen`."
  [{:keys [ratom reaction start! flush! release!]} initial read-fn]
  (let [lever   (ratom initial)
        renders (volatile! 0)
        seen    (volatile! [])
        owner   (reaction (fn []
                            (vswap! renders inc)
                            (read-fn @lever (fn [r] (vswap! seen conj r) r))))]
    (start! owner)
    {:owner    owner
     :renders  renders
     :seen     seen
     :pull!    (fn [v] (reset! lever v) (flush!))
     :dispose! (fn [] (release! owner))}))

(defn- callback-count
  "How many on-dispose callbacks `reaction` carries. Both ratom
  implementations keep them in the same `on-dispose-arr` holder."
  [reaction]
  (or (some-> (.-on-dispose-arr ^clj reaction) .-length) 0))

(defn- holdings
  "The owner's recorded holdings, whatever shape one takes."
  [owner]
  (some-> (.-rfSubRefs ^js owner) deref))

(defn- reachable?
  "True when `r` appears anywhere in `owner`'s recorded holdings."
  [owner r]
  (boolean (some #(identical? r %) (tree-seq coll? seq (vals (holdings owner))))))

(defn- distinct-count [rs]
  (count (into #{} (map goog/getUid) rs)))

;; ---- the red rows ---------------------------------------------------------

(defn- toggled-read-scenario
  "The bead's own scenario: one sub read unconditionally, one read only while
  a flag is on. Each OFF render drops the conditional reaction's last
  watcher, so it disposes and leaves the cache; each ON render builds and
  claims a fresh one."
  [{:keys [label] :as config}]
  (testing (str label ": toggling a conditional read leaves the owner holding "
                "only what it still reads")
    (register-fixtures!)
    (let [{:keys [owner renders seen pull! dispose!]}
          (owner-harness config true
                         (fn [show? note!]
                           [@(rf/subscribe [::n] {:frame frame-id})
                            (when show?
                              @(note! (rf/subscribe [::details] {:frame frame-id})))]))
          callbacks-after-first-render (callback-count owner)]
      (dotimes [_ cycles]
        (pull! false)
        (pull! true))
      (pull! false)
      (is (= (+ 2 (* 2 cycles)) @renders)
          (str label ": precondition — every toggle re-ran the owner"))
      (is (= (inc cycles) (distinct-count @seen))
          (str label ": precondition — each ON render was handed a NEW reaction, "
               "so the previous one really was disposed and evicted"))
      (is (nil? (cached [::details]))
          (str label ": precondition — after the final OFF render the conditional "
               "slot is gone"))
      (is (= callbacks-after-first-render (callback-count owner))
          (str label ": the owner's on-dispose callbacks do not grow with claims "
               "(one per claim before the fix, each capturing a disposed reaction)"))
      (is (= #{[frame-id [::n]]} (set (keys (holdings owner))))
          (str label ": the owner holds exactly the slot it still reads"))
      (is (not-any? #(reachable? owner %) @seen)
          (str label ": no disposed conditional reaction is reachable from the "
               "owner's holdings"))
      (is (= 1 (:ref-count (cached [::n])))
          (str label ": the unconditional read is still ONE reference"))
      (dispose!)
      (is (nil? (cached [::n]))
          (str label ": disposing the owner still releases what it holds")))))

(defn- parametric-read-scenario
  "The no-toggle form: a parametric query whose argument changes on every
  render. Each render reads a new slot and the previous slot's reaction
  disposes."
  [{:keys [label] :as config}]
  (testing (str label ": a changing parametric argument leaves the owner holding "
                "only the current query")
    (register-fixtures!)
    (let [{:keys [owner renders seen pull! dispose!]}
          (owner-harness config 0
                         (fn [i note!]
                           @(note! (rf/subscribe [::item i] {:frame frame-id}))))
          callbacks-after-first-render (callback-count owner)]
      (doseq [i (range 1 (inc cycles))]
        (pull! i))
      (is (= (inc cycles) @renders)
          (str label ": precondition — every argument change re-ran the owner"))
      (is (= (inc cycles) (distinct-count @seen))
          (str label ": precondition — every render read a distinct reaction"))
      (is (every? #(nil? (cached [::item %])) (range cycles))
          (str label ": precondition — every earlier query's slot was evicted"))
      (is (= callbacks-after-first-render (callback-count owner))
          (str label ": the owner's on-dispose callbacks do not grow with claims"))
      (is (= #{[frame-id [::item cycles]]} (set (keys (holdings owner))))
          (str label ": the owner holds only the query it reads now, not one "
               "record per argument it has ever read"))
      (is (not-any? #(reachable? owner %) (butlast @seen))
          (str label ": no earlier, disposed reaction is reachable from the owner"))
      (is (reachable? owner (last @seen))
          (str label ": control — the live reaction IS reachable, so the probe "
               "can see a holding when there is one"))
      (dispose!)
      (is (nil? (cached [::item cycles]))
          (str label ": disposing the owner still releases what it holds")))))

;; ---- the mirror control ---------------------------------------------------

(defn- owner-churn-scenario
  "One long-lived owner keeps a reaction alive while short-lived owners read
  it and dispose. The reaction must not collect a callback or a record per
  owner that ever read it — the failure a repair that registered on the
  claimed reaction once PER CLAIM would introduce."
  [{:keys [label] :as config}]
  (testing (str label ": owners that come and go leave nothing behind on a "
                "reaction that outlives them")
    (register-fixtures!)
    (let [read-n     (fn [_ note!] @(note! (rf/subscribe [::n] {:frame frame-id})))
          keeper     (owner-harness config nil read-n)
          r          (:reaction (cached [::n]))
          callbacks0 (callback-count r)]
      (dotimes [_ cycles]
        (let [visitor (owner-harness config nil read-n)]
          (is (= 2 (:ref-count (cached [::n])))
              (str label ": precondition — a visiting owner holds its own reference"))
          ((:dispose! visitor))))
      (is (identical? r (:reaction (cached [::n])))
          (str label ": precondition — the keeper held ONE reaction alive throughout"))
      (is (= 1 (:ref-count (cached [::n])))
          (str label ": every visitor's reference was released on its dispose"))
      (is (= callbacks0 (callback-count r))
          (str label ": the reaction's on-dispose callbacks do not grow per owner"))
      (is (<= (or (some-> (.-rfSubHolders ^js r) .-size) 0) 1)
          (str label ": the reaction records at most its one live owner"))
      ((:dispose! keeper))
      (is (nil? (cached [::n]))
          (str label ": the keeper's dispose releases the last reference")))))

;; ---- entries --------------------------------------------------------------

(deftest render-owned-toggled-read-is-not-retained-reagent-slim
  (with-adapter (:adapter slim-config) #(toggled-read-scenario slim-config)))

(deftest render-owned-toggled-read-is-not-retained-reagent
  (with-adapter (:adapter stock-config) #(toggled-read-scenario stock-config)))

(deftest render-owned-parametric-read-is-not-retained-reagent-slim
  (with-adapter (:adapter slim-config) #(parametric-read-scenario slim-config)))

(deftest render-owned-parametric-read-is-not-retained-reagent
  (with-adapter (:adapter stock-config) #(parametric-read-scenario stock-config)))

(deftest render-owned-owner-churn-leaves-the-reaction-clean-reagent-slim
  (with-adapter (:adapter slim-config) #(owner-churn-scenario slim-config)))

(deftest render-owned-owner-churn-leaves-the-reaction-clean-reagent
  (with-adapter (:adapter stock-config) #(owner-churn-scenario stock-config)))
