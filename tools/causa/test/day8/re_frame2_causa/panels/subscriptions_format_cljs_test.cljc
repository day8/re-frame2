(ns day8.re-frame2-causa.panels.subscriptions-format-cljs-test
  "Per-leaf smoke test for `subscriptions-format` (rf2-nb8if).

  Pure CLJC; JVM-runnable."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-causa.panels.subscriptions-format :as f]))

(deftest format-query-v-pretty-prints
  (is (= "[:cart/total]" (f/format-query-v [:cart/total]))))

(deftest format-sub-id-handles-keywords-and-symbols
  (is (= ":cart/total" (f/format-sub-id :cart/total)))
  (is (= "my-sub" (f/format-sub-id 'my-sub))))
