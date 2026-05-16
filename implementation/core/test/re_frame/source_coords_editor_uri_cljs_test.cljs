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

;; ---- positive scheme allowlist (rf2-cm93v / rf2-p887o) -------------------

(deftest allowed-uri-on-cljs
  (testing "the positive allowlist round-trips identically on CLJS"
    ;; built-in schemes pass
    (is (eu/allowed-uri? "vscode://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "cursor://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "windsurf://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "zed://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "idea://open?file=src/x.cljs&line=1&column=1"))
    ;; other catalogued schemes
    (is (eu/allowed-uri? "subl://open?path=src/x.cljs"))
    (is (eu/allowed-uri? "emacsclient://src/x.cljs"))
    (is (eu/allowed-uri? "file:///abs/path/src/x.cljs"))
    ;; rejected schemes
    (is (not (eu/allowed-uri? "javascript:alert(1)")))
    (is (not (eu/allowed-uri? "data:text/html,xxx")))
    (is (not (eu/allowed-uri? "vbscript:msgbox(1)")))
    (is (not (eu/allowed-uri? "http://evil.example/x")))
    (is (not (eu/allowed-uri? "https://evil.example/x")))
    ;; non-strings / shape edge cases
    (is (not (eu/allowed-uri? nil)))
    (is (not (eu/allowed-uri? "")))
    (is (not (eu/allowed-uri? "no-scheme-here")))
    ;; leading whitespace is tolerated
    (is (not (eu/allowed-uri? " javascript:alert(1)")))
    (is (eu/allowed-uri? " vscode://file/src/x.cljs:1:1"))))

;; ---- project-root prefix (rf2-zfy1e) -------------------------------------

(deftest project-root-cljs-smoke
  (testing "{:project-root ...} prefixes the relative source-coord file on CLJS"
    (is (= "vscode://file/C:/code/my-app/src/app/views.cljs:42:7"
           (eu/editor-uri :vscode coord
                          {:project-root "C:/code/my-app"})))
    (is (= "idea://open?file=C:/code/my-app/src/app/views.cljs&line=42&column=7"
           (eu/editor-uri :idea coord
                          {:project-root "C:/code/my-app"}))))
  (testing "nil / blank :project-root falls back to verbatim path"
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (eu/editor-uri :vscode coord nil)))
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (eu/editor-uri :vscode coord {:project-root nil}))))
  (testing "absolute :file is not double-prefixed on CLJS"
    (is (= "vscode://file/C:/abs/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "C:/abs/x.cljs"}
                          {:project-root "/should-not-apply"})))
    (is (= "vscode://file//etc/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "/etc/x.cljs"}
                          {:project-root "/should-not-apply"})))))
