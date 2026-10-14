(ns re-frame.ex-message-safe-cljs-test
  "`rf.error/ex-message-safe` stamps `:exception-message` at the runtime's
  catch sites. CLJS can throw any value, and a thrown non-Error has no
  `.-message`, so the extractor renders it rather than yielding nil."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.error :as rf.error]))

(deftest message-of-each-thrown-value-kind
  (let [non-error (rf.error/ex-message-safe :boom)]
    (is (= ["boom" true nil]
           [(rf.error/ex-message-safe (ex-info "boom" {}))
            (and (string? non-error) (some? (re-find #"boom" non-error)))
            (rf.error/ex-message-safe nil)]))))
