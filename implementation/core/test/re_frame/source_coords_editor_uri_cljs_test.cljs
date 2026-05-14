(ns re-frame.source-coords-editor-uri-cljs-test
  "CLJS smoke tests for `re-frame.source-coords.editor-uri` (rf2-evgf5).

  The pure helper is JVM + CLJS portable; the bulk of the matrix lives
  in the JVM test ns. This file verifies the CLJS build path resolves
  the same URIs."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.source-coords.editor-uri :as eu]))

(def ^:private coord
  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7})

(deftest vscode-cursor-idea-portable-shape
  (testing ":vscode / :cursor / :idea produce the documented URIs under CLJS"
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (eu/editor-uri :vscode coord)))
    (is (= "cursor://file/src/app/views.cljs:42:7"
           (eu/editor-uri :cursor coord)))
    (is (= "idea://open?file=src/app/views.cljs&line=42&column=7"
           (eu/editor-uri :idea coord)))))

(deftest windsurf-zed-portable-shape
  (testing ":windsurf / :zed produce the documented URIs under CLJS"
    (is (= "windsurf://file/src/app/views.cljs:42:7"
           (eu/editor-uri :windsurf coord)))
    (is (= "zed://file/src/app/views.cljs:42:7"
           (eu/editor-uri :zed coord)))))

(deftest custom-template-on-cljs
  (testing ":custom template substitutes placeholders identically on CLJS"
    (is (= "x://file/src/app/views.cljs?line=42"
           (eu/editor-uri
             {:custom "x://file/{path}?line={line}"}
             coord)))))

(deftest nil-file-cljs
  (testing "missing :file on CLJS → nil URI"
    (is (nil? (eu/editor-uri :vscode {:line 10})))))

(deftest has-source-cljs
  (testing "has-source? on CLJS"
    (is (eu/has-source? coord))
    (is (not (eu/has-source? nil)))))

(deftest forbidden-custom-schemes-on-cljs
  (testing "javascript: / data: / vbscript: custom URIs return nil on CLJS (rf2-vwcsq)"
    (is (nil? (eu/editor-uri {:custom "javascript:alert(1)"}                 coord)))
    (is (nil? (eu/editor-uri {:custom "JavaScript:alert(1)"}                 coord)))
    (is (nil? (eu/editor-uri {:custom "data:text/html,<script>x</script>"}   coord)))
    (is (nil? (eu/editor-uri {:custom "DATA:text/html,xxx"}                  coord)))
    (is (nil? (eu/editor-uri {:custom "vbscript:msgbox(1)"}                  coord)))
    (is (nil? (eu/editor-uri {:custom " javascript:alert(1)"}                coord)))))

(deftest legitimate-custom-schemes-pass-on-cljs
  (testing "ordinary custom editor templates still resolve on CLJS"
    (is (some? (eu/editor-uri {:custom "jetbrains://idea/{path}:{line}"}      coord)))
    (is (some? (eu/editor-uri {:custom "subl://open?path={path}&line={line}"} coord)))
    (is (some? (eu/editor-uri {:custom "emacsclient://open?file={path}"}      coord)))))
