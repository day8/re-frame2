(ns re-frame.hicasso.tool-views-dom-cljs-test
  "THE VIEW NAME RIDES ON THE REFERENCE — UNDER REAL REACT.

  `tool_reads_cljs_test` states the two attribution laws at the
  collector's published seam: a row's `:views` names the views HOLDING
  the edge set, so a view that unmounts leaves the row its twin still
  holds, and a render React discards names nothing. The seam only
  imitates React. This file has React itself perform the commit and the
  cleanup — `useSyncExternalStore`'s passive-effect `subscribe` and the
  cleanup it holds until unmount — which is the order the attribution
  is keyed on, and StrictMode's mount → unmount → mount over every
  effect is the case a count survives and a flag does not.

  The roster is read between React's unmount and the fixture's reset,
  as the residue witnesses do, because `mount/release!` empties every
  table by fiat. On the node lane every row states a skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.tool :as rf.hicasso.tool]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]))

(def ^:private frame-id ::tool-views-dom)

(rf/reg-sub ::left (fn [db _] (:left db)))
(rf/reg-event ::seed (fn [_ _] {:db {:left 1}}))

(rf.hicasso/defview alpha-view [_] [:b.alpha (str (rf.hicasso/sub [::left]))])
(rf.hicasso/defview beta-view  [_] [:i.beta  (str (rf.hicasso/sub [::left]))])
(rf.hicasso/defhost strict-mode react/StrictMode {:server :render})
(rf.hicasso/defview strict-alpha
  "`alpha-view` under React's own StrictMode, which double-invokes the
  body and runs mount/unmount/mount over the subscription effect."
  [_]
  [strict-mode [alpha-view {}]])

(def ^:private alpha-name "re-frame.hicasso.tool-views-dom-cljs-test/alpha-view")
(def ^:private beta-name  "re-frame.hicasso.tool-views-dom-cljs-test/beta-view")

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (rf.hicasso.roots-frames-support/leave-act-environment!)
                      (rf.hicasso.impl.collector/reset-runtime!))}))

(def ^:private k [frame-id [::left]])

(defn- names-of
  "The view names on the row holding `::left`, or nil when no row does."
  []
  (some (fn [r] (when (some #(= ::left (:sub-id %)) (:reads r))
                  (mapv :view (:views r))))
        (:boundaries (rf.hicasso.tool/read-mounted-boundaries))))

(defn- mount-root!
  "A root of its own for `hiccup`, joining `frame-id` and seeding it on
  the first mount only."
  [hiccup]
  (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-id hiccup
               {:initial-events [[::seed]]}))

(defn- finish!
  "Release every root and end the row once."
  [done handles]
  (fn [_]
    (doseq [h handles] (rf.hicasso.impl.mount/release! (assoc h :root nil)))
    (done)))

(deftest a-view-react-unmounts-leaves-the-row-its-twin-still-holds
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM") (done))
      (let [a (mount-root! [alpha-view {}])
            b (mount-root! [beta-view {}])]
        (-> (rf.hicasso.roots-frames-support/wait-until! #(= 2 (rf.hicasso.roots-frames-support/readers-of k)))
            (.then
              (fn [subscribed?]
                (testing "the premise: React committed both roots — two
                          readers on the one cell, and both views named"
                  (is (true? subscribed?))
                  (is (= [alpha-name beta-name] (names-of))))
                (testing "React's unmount cleanup released alpha's reference,
                          and the name went with it: beta's row names beta"
                  (rf.hicasso.impl.mount/unmount! a)
                  (is (= 1 (rf.hicasso.roots-frames-support/readers-of k)))
                  (is (= [beta-name] (names-of))))
                (testing "and the last holder leaving leaves no row at all"
                  (rf.hicasso.impl.mount/unmount! b)
                  (is (nil? (names-of))))
                nil))
            (.catch (fn [e] (is false (str "twin unmount — " (.-message e)))))
            (.then (finish! done [a b])))))))

(deftest strict-modes-discarded-render-and-replayed-effect-name-the-view-once
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no React DOM") (done))
      (let [a (mount-root! [strict-alpha {}])]
        (-> (rf.hicasso.roots-frames-support/wait-until! #(= 1 (rf.hicasso.roots-frames-support/readers-of k)))
            (.then
              (fn [subscribed?]
                (testing "the premise: one reader — StrictMode's replayed
                          effect acquired once, as the runtime already proves"
                  (is (true? subscribed?)))
                (testing "the discarded first render wrote no name, and the
                          effect's unmount/mount replay left the count at one"
                  (let [row (first (filter (fn [r] (some #(= ::left (:sub-id %)) (:reads r)))
                                           (:boundaries (rf.hicasso.tool/read-mounted-boundaries))))]
                    (is (= 1 (:instances row)))
                    (is (= [alpha-name] (mapv :view (:views row))))))
                (testing "and its unmount takes the name with it"
                  (rf.hicasso.impl.mount/unmount! a)
                  (is (nil? (names-of))))
                nil))
            (.catch (fn [e] (is false (str "StrictMode naming — " (.-message e)))))
            (.then (finish! done [a])))))))
