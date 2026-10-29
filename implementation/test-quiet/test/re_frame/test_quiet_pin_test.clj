(ns re-frame.test-quiet-pin-test
  "The quiet reporter's in-process output for a known-green target: the two
  summary lines and nothing else. The stock reporter adds a `Testing <ns>`
  banner, so this goes red if the overrides stop being installed."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [re-frame.test-quiet]
            [re-frame.test-quiet-pin-passing-test]))

(deftest green-run-prints-only-the-summary
  (let [captured (with-out-str
                   (binding [clojure.test/*test-out* *out*]
                     (run-tests 're-frame.test-quiet-pin-passing-test)))
        non-blank (remove str/blank? (str/split-lines captured))
        ;; `RF2_VERBOSE_TESTS=1` asks for the banner on green.
        banner    (when (= "1" (System/getenv "RF2_VERBOSE_TESTS"))
                    ["Testing re-frame.test-quiet-pin-passing-test"])]
    (is (= (concat banner ["Ran 1 tests containing 1 assertions."
                           "0 failures, 0 errors."])
           non-blank)
        captured)))
