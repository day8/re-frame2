(ns re-frame.fresco.cell-disposal-callback-cljs-test
  "A CELL TAKES ITS DISPOSAL CALLBACK WITH IT WHEN IT GOES.

  `collector/wire-cell!` acquires a cell's reaction through core's seam,
  `subs/acquire-watched-reaction!`, which arms a callback on the reaction
  so the cell hears when the substrate disposes it. The reaction is
  SHARED: a layer-2 subscription whose input it is, or a UIx or Reagent
  reader on the same frame, keeps it cached long after the last Fresco
  reader of the key has gone. So `dispose-cell!` has to disarm the
  callback as well as return the share, or every cell lifetime leaves one
  closure on the reaction, retaining the dead cell, until the reaction
  itself finally disposes.

  `rt/residue` cannot see this: it counts cells and reader memberships,
  and a disposed cell is in neither. The reading here is the removable
  callback set core keeps on the reaction (`.-rfRemovableOnDispose`, the
  field the UIx suite reads for the same claim), taken while a Fresco
  reader is committed — where it must be one above its baseline, which is
  what proves the instrument sees Fresco's callback at all — and again
  after each release, where it must be back at the baseline.

  The harness is the commit seam (`rt/render-and-commit!`), so the row
  runs on the node lane with no React root."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.subs :as rf.subs]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::cell-disposal)

(def ^:private input-q [:cell-disposal/input])

(def ^:private cycles
  "Mount/unmount cycles, each past the reaper's grace so every one is a
  separate cell lifetime."
  5)

(rf/reg-event :cell-disposal/seed (fn [_ _] {:db {:n 1}}))
(rf/reg-sub   :cell-disposal/input (fn [db _] (:n db)))
(rf/reg-sub   :cell-disposal/derived {:inputs [input-q]} (fn [[n] _] (inc n)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(defn- armed
  "How many removable disposal callbacks `reaction` carries."
  [^js reaction]
  (or (some-> reaction .-rfRemovableOnDispose .-size) 0))

(defn- body [_props] (rf.fresco.impl.collector/sub input-q))

(defn- cycle!
  "Commit one Fresco reader of the input, release it, and wait until the
  reaper has disposed its cell. Answers a promise."
  [input baseline i]
  (let [{:keys [release]} (rf.fresco.test.runtime/render-and-commit! frame-id body)]
    (is (= (inc baseline) (armed input))
        (str "cycle " i ": while the reader is committed its cell's callback
              is armed on the shared reaction"))
    (release)
    (.then (rf.fresco.test.runtime/quiesced!)
           (fn [_]
             (is (= 0 (:cells (rf.fresco.test.runtime/residue)))
                 (str "cycle " i ": the reaper disposed the cell"))
             (is (= baseline (armed input))
                 (str "cycle " i ": the disposed cell's callback left with it"))))))

(deftest a-disposed-cell-disarms-its-callback-on-a-shared-reaction
  (async done
    (rf/make-frame {:id frame-id})
    (rf/with-frame frame-id (rf/dispatch-sync [:cell-disposal/seed]))
    (let [held     (rf/subscribe [:cell-disposal/derived] {:frame frame-id})
          input    (rf.subs/peek-cache-reaction frame-id input-q)
          baseline (armed input)]
      (is (some? input) "premise: the layer-2 subscription holds its input cached")
      (-> (reduce (fn [p i] (.then p (fn [_] (cycle! input baseline i))))
                  (js/Promise.resolve nil)
                  (range cycles))
          (.then (fn [_]
                   (is (identical? input (rf.subs/peek-cache-reaction frame-id input-q))
                       "premise: the input reaction outlived every cell, so each
                        cycle armed and disarmed on the same object")))
          (.catch (fn [e] (is false (str "the churn threw: " (.-message e)))))
          ;; The single trailing step: `done` runs the rest of the suite
          ;; synchronously, so nothing may sit downstream of it.
          (.then (fn [_]
                   (rf/unsubscribe held)
                   (done)))))))
