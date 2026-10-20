(ns re-frame.interop-debug-gate-test
  "The input vocabulary of the JVM debug gate: which `re-frame.debug` values
  `read-debug-flag` treats as on and as off. Despite the namespace's name, this suite is
  NOT THE LOAD-TIME GATE — it calls the private reader at test time, long after
  the gate itself was decided at namespace load."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [re-frame.interop]))

(def ^:private read-debug-flag #'re-frame.interop/read-debug-flag)

(def ^:private unset-default
  "What the gate reads on THIS JVM with nothing set: OFF when the classpath
  carries the SSR host marker, ON otherwise. It differs by classpath — core's
  own test alias carries no marker, while the `implementation/deps.edn`
  `:test` coordinator loads ssr-ring, which ships it — so it is computed here
  rather than assumed."
  (nil? (io/resource @#'re-frame.interop/ssr-host-marker)))

(defn- with-prop
  "Run `f` with the `re-frame.debug` system property set to `v`, restoring the
  prior value afterwards."
  [v f]
  (let [prior (System/getProperty "re-frame.debug")]
    (try
      (System/setProperty "re-frame.debug" v)
      (f)
      (finally
        (if (nil? prior)
          (System/clearProperty "re-frame.debug")
          (System/setProperty "re-frame.debug" prior))))))

(deftest read-debug-flag-reads-the-on-and-off-vocabularies
  ;; Documented values, case-insensitive and trimmed, read the same on every
  ;; classpath. Anything else, a typo like `disabled` included, takes this
  ;; JVM's unset default.
  (doseq [[v expected] [["false" false] ["0" false] ["no" false] ["off" false]
                        ["" false] ["FALSE" false] ["  false  " false]
                        ["true" true] ["1" true] ["yes" true] ["ON" true]
                        ["disabled" unset-default]]]
    (with-prop v
      (fn []
        (is (= expected (@read-debug-flag)) (pr-str v))))))
