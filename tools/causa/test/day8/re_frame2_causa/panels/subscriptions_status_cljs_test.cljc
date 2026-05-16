(ns day8.re-frame2-causa.panels.subscriptions-status-cljs-test
  "Per-leaf smoke test for `subscriptions-status` (rf2-nb8if).

  Asserts the status taxonomy lookups carry every status keyword
  with a non-nil mapping. Pure CLJC; JVM-runnable."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-causa.panels.subscriptions-status :as status]))

(deftest each-status-has-token-glyph-tooltip
  (doseq [s status/statuses]
    (is (some? (get status/status->token s)))
    (is (some? (get status/status->glyph s)))
    (is (some? (get status/status->tooltip s)))))

(deftest statuses-vector-has-five-canonical-entries
  (is (= #{:error :re-running :invalidated :fresh :cached-no-watcher}
         (set status/statuses))))
