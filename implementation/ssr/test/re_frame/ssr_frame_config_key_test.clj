(ns re-frame.ssr-frame-config-key-test
  "`:ssr` is a frame-config key this artefact reads, so loading `re-frame.ssr`
  claims it and an SSR frame that sets it draws no dev
  `:rf.warning/unknown-frame-config-key`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- unknown-config-keys
  "Run `f`, returning the `:unknown-keys` of every
  `:rf.warning/unknown-frame-config-key` it emits."
  [f]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::probe
                           (fn [ev]
                             (when (= :rf.warning/unknown-frame-config-key (:operation ev))
                               (swap! seen conj (:unknown-keys (:tags ev))))))
    (try (f) (finally (rf/unregister-listener! :trace ::probe)))
    @seen))

(deftest an-ssr-frame-config-draws-no-unknown-key-warning
  ;; `:sssr` is the control: a bare key nothing claims, so the warning is
  ;; seen to fire, and to name only it. The warning is dev-only.
  (is (= (if rf.interop/debug-enabled? [[:sssr]] [])
         (unknown-config-keys
           #(rf/make-frame {:id       ::server
                            :platform :server
                            :ssr      {:public-error-id :rf.ssr/default-error-projector}
                            :sssr     {}})))))
