(ns re-frame.ex-message-safe-cljs-test
  "`rf.error/ex-message-safe` stamps `:exception-message` at the runtime's
  catch sites. CLJS can throw any value, and a thrown non-Error has no
  `.-message`, so the extractor renders it rather than yielding nil."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.error :as rf.error]))

(deftest host-exception-message-rides-through
  (is (= "boom" (rf.error/ex-message-safe (ex-info "boom" {})))))

(deftest thrown-non-error-values-render-rather-than-yield-nil
  (let [msg (rf.error/ex-message-safe :boom)]
    (is (and (string? msg) (re-find #"boom" msg)))))

(deftest nil-input-yields-nil
  (is (nil? (rf.error/ex-message-safe nil))))
