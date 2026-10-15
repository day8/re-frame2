(ns re-frame.test-quiet-pin-test
  "Bound the reporter output from a known-green target."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [re-frame.test-quiet]
            [re-frame.test-quiet-pin-passing-test]))

(deftest stdout-line-count-pin
  (let [captured (with-out-str
                   (binding [clojure.test/*test-out* *out*]
                     (run-tests 're-frame.test-quiet-pin-passing-test)))
        non-blank (remove str/blank? (str/split-lines captured))]
    (is (<= (count non-blank) 5) captured)))
