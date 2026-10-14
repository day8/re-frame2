(ns re-frame.ssr.head-emit-cljs-test
  "The JSON-LD body of `head-model->html` on both hosts. The JVM prints it by
  hand and the CLJS branch through `JSON.stringify`; both then run the same
  script-body escape, so a `<` in a key or a value cannot close the
  `<script type=\"application/ld+json\">` envelope."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ssr.head.emit :as rf.ssr.head.emit]))

(deftest ld-json-string-escapes-script-close-in-keys-and-values
  (is (= (str "<script type=\"application/ld+json\">"
              "{\"@type\":\"Article\",\"headline\":\"\\u003c/script>\\u003cscript>x\","
              "\"\\u003c/script>\":\"v\"}"
              "</script>")
         (rf.ssr.head.emit/head-model->html
           {:json-ld [{"@type" "Article" "headline" "</script><script>x" "</script>" "v"}]}))))
