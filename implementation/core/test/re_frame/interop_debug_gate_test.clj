(ns re-frame.interop-debug-gate-test
  "The input vocabulary of the JVM debug gate: which `re-frame.debug` values
  `read-debug-flag` treats as off. Despite the namespace's name, this suite is
  NOT THE LOAD-TIME GATE — it calls the private reader at test time, long after
  the gate itself was decided at namespace load."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.interop]))

(def ^:private read-debug-flag #'re-frame.interop/read-debug-flag)

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

(deftest read-debug-flag-disables-only-on-the-false-y-vocabulary
  ;; Documented off-values, case-insensitive and trimmed. Anything else leaves
  ;; debug ON, so a typo like `disabled` keeps dev tracing rather than silently
  ;; misconfiguring.
  (doseq [[v expected] [["false" false] ["0" false] ["no" false] ["off" false]
                        ["" false] ["FALSE" false] ["  false  " false]
                        ["true" true] ["disabled" true]]]
    (with-prop v
      (fn []
        (is (= expected (@read-debug-flag)) (pr-str v))))))
