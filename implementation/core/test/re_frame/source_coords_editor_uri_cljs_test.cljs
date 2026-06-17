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
    (is (some? (eu/editor-uri {:custom "emacsclient://open?file={path}"}      coord))))
  (testing "rf2-ox357n: an UNKNOWN, non-dangerous custom scheme passes
            through on CLJS — no positive allowlist, no silent dead button"
    (is (= "lapce://open?file=src/app/views.cljs&line=42"
           (eu/editor-uri {:custom "lapce://open?file={path}&line={line}"} coord)))
    (is (some? (eu/editor-uri {:custom "future-editor-9://{path}:{line}"} coord)))))

;; ---- public forbidden-scheme? predicate (rf2-ox357n) ---------------------

(deftest forbidden-scheme-on-cljs
  (testing "rf2-ox357n: the public denylist predicate round-trips on CLJS"
    ;; the three known-bad schemes are forbidden, case-insensitively +
    ;; leading-whitespace tolerant
    (is (eu/forbidden-scheme? "javascript:alert(1)"))
    (is (eu/forbidden-scheme? "JavaScript:alert(1)"))
    (is (eu/forbidden-scheme? "data:text/html,xxx"))
    (is (eu/forbidden-scheme? "DATA:text/html,xxx"))
    (is (eu/forbidden-scheme? "vbscript:msgbox(1)"))
    (is (eu/forbidden-scheme? " javascript:alert(1)"))
    ;; everything else passes — built-in, catalogued, http(s), unknown
    (is (not (eu/forbidden-scheme? "vscode://file/src/x.cljs:1:1")))
    (is (not (eu/forbidden-scheme? "subl://open?path=src/x.cljs")))
    (is (not (eu/forbidden-scheme? "file:///abs/path/src/x.cljs")))
    (is (not (eu/forbidden-scheme? "http://localhost:3000/x")))
    (is (not (eu/forbidden-scheme? "https://localhost:3000/x")))
    (is (not (eu/forbidden-scheme? "lapce://open?file=src/x.cljs&line=1")))
    ;; shape edge cases
    (is (not (eu/forbidden-scheme? nil)))
    (is (not (eu/forbidden-scheme? "")))
    (is (not (eu/forbidden-scheme? "no-scheme-here")))))

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
