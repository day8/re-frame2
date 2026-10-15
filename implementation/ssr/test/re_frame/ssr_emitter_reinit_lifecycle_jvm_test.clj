(ns re-frame.ssr-emitter-reinit-lifecycle-jvm-test
  "The plain-atom emitter survives destroy and re-init without rearming."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (fn [f]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (rf.substrate.adapter/reset-lifecycle-state-for-tests!)))))

(defn- render! []
  (rf.substrate.adapter/render-to-string [:div "ok"] {}))

(deftest jvm-lifecycle-is-repeatable
  (dotimes [_ 2]
    (rf/init! rf.substrate.plain-atom/adapter)
    (is (re-find #"<div>ok</div>" (render!)))
    (rf/destroy-adapter!)))
