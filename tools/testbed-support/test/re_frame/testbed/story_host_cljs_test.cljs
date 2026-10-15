(ns re-frame.testbed.story-host-cljs-test
  "Hot-reload listener replacement for the Story host."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.testbed.story-host :as rf.testbed.story-host]))

(defn- make-fake-window []
  (let [registry (js/Set.)
        window   #js {}]
    (set! (.-location window) #js {:hash "#/"})
    (set! (.-addEventListener window)
          (fn [type listener]
            (when (= type "hashchange")
              (.add registry listener))))
    (set! (.-removeEventListener window)
          (fn [type listener]
            (when (= type "hashchange")
              (.delete registry listener))))
    {:window window :registry registry}))

;; Node resolves js/window through Closure's global object.
(def ^:private real-window (when (exists? js/window) js/window))

(defn- install-window! [w]
  (set! (.-window js/goog.global) w))

(defn- clear-window! []
  (if real-window
    (set! (.-window js/goog.global) real-window)
    (js-delete js/goog.global "window")))

(defn- reset-host-handles! []
  (reset! @#'rf.testbed.story-host/hash-listener* nil)
  (reset! @#'rf.testbed.story-host/root-view* nil))

(use-fixtures :each
  {:before reset-host-handles!
   :after  (fn [] (clear-window!) (reset-host-handles!))})

(defn- dummy-view [] [:div "dummy"])

(deftest many-re-runs-never-accumulate-listeners
  (testing "Hot reload replaces the listener and one hash change mounts once."
    (let [{:keys [window registry]} (make-fake-window)
          switches (atom 0)]
      (install-window! window)
      (with-redefs [rf.testbed.story-host/mount-app! (fn [] (swap! switches inc))]
        (rf.testbed.story-host/mount-with-hash-routing! dummy-view))
      (is (= 1 @switches))
      ;; Replace both the original handler and a reloaded handler.
      (dotimes [_ 2]
        (with-redefs [rf.testbed.story-host/on-hash-change! (fn [] (swap! switches inc))]
          (rf.testbed.story-host/mount-with-hash-routing! dummy-view)))
      (reset! switches 0)
      (.forEach registry (fn [listener] (listener)))
      (is (= 1 @switches) "One hash change mounts exactly once."))))
