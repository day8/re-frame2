(ns re-frame.ssr.head-emit-cljs-test
  "Node-runtime smoke for the CLJS branch of `head/emit/ld-json-string`.
  Per rf2-usio0 (testcov audit ai/findings/2026-05-21-testcov-ssr.md
  §Layer gap).

  `ld-json-string` is a reader-conditional: the JVM side is a hand-rolled
  JSON printer (exhaustively tested in `ssr_head_test.clj`), while the
  CLJS side delegates to `js/JSON.stringify` then runs the SAME
  script-body escape (`html/escape-script-body-string`). Before rf2-usio0
  the CLJS branch had NO Node test — the hand-rolled JVM printer diverges
  from the `JSON.stringify` path, and the security-critical `<script>`
  close-tag escape on the CLJS side was unverified on Node.

  This file ends in `-cljs-test` so the shadow `:node-test` build
  (`:ns-regexp \"cljs-test$\"`) picks it up. It is `.cljc` so the same
  assertions also run under the JVM test gate (a free belt-and-braces
  cross-check that the two `ld-json-string` branches agree on the
  observable contract: valid JSON envelope + escaped `<`).

  We drive through `head-model->html` — the public surface that composes
  `ld-json-string` via `emit-json-ld` — rather than the private helper,
  so the test pins the contract a consumer actually sees."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.head.emit :as head-emit]))

(deftest ld-json-string-serialises-structured-map
  (testing "rf2-usio0 — the CLJS JSON-LD path serialises a structured map
            and rides the <script type=\"application/ld+json\"> envelope
            (the JSON.stringify branch, on Node)."
    (let [html (head-emit/head-model->html
                 {:json-ld [{"@context" "https://schema.org"
                             "@type"    "Article"
                             "headline" "Hello"}]})]
      (is (str/includes? html "type=\"application/ld+json\""))
      (is (str/includes? html "\"@context\""))
      (is (str/includes? html "\"@type\":\"Article\"")
          "JSON.stringify emits compact `key:value` with no spaces")
      (is (str/includes? html "\"headline\":\"Hello\""))
      (is (str/ends-with? html "</script>")
          "the genuine envelope-closing </script> is present"))))

(deftest ld-json-string-escapes-script-close-in-string-values
  (testing "rf2-usio0 / rf2-m5u23 — the CLJS path escapes every `<` in a
            string value as `\\u003c` AFTER `JSON.stringify`, so a value
            carrying `</script>` cannot close the surrounding
            `<script type=\"application/ld+json\">` envelope. This is the
            security-critical assertion the CLJS branch lacked."
    (let [hostile "</script><script>alert(document.cookie)</script>"
          html    (head-emit/head-model->html
                    {:json-ld [{"@context" "https://schema.org"
                                "@type"    "Article"
                                "headline" hostile}]})]
      (is (not (str/includes? html "</script><script>alert"))
          "the closing-tag pattern is broken — no raw </script> survives
           in the JSON-LD body")
      (is (str/includes? html "\\u003c/script>\\u003cscript>")
          "every `<` in the string value is escaped as the JSON `\\u003c`
           escape — round-trips through the client's JSON.parse")
      (is (str/ends-with? html "</script>")
          "the genuine envelope-closing </script> is unaffected"))))

(deftest ld-json-string-escapes-script-close-in-keys
  (testing "rf2-usio0 / rf2-m5u23 — a `<` inside a JSON-LD KEY is also
            escaped on the CLJS path (the escape walks the whole
            stringified payload, keys included)."
    (let [hostile-key "</script>"
          html        (head-emit/head-model->html
                        {:json-ld [{hostile-key "value"}]})]
      (is (not (str/includes? html "</script>\":"))
          "</script> as a key cannot close the envelope")
      (is (str/includes? html "\\u003c/script>")
          "`<` in keys comes through escaped"))))
