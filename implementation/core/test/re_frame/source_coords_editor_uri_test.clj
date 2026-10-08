(ns re-frame.source-coords-editor-uri-test
  "`re-frame.source-coords.editor-uri`: pure data → data with no host branch,
  so this JVM run covers the CLJS build too."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]))

(def ^:private sample-coord
  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7})

(deftest each-built-in-editor-builds-its-documented-uri
  (testing "nil, an unknown keyword and a non-string :custom fall through to
            :vscode; every other built-in keyword builds its own scheme"
    (are [editor expected]
         (= expected (rf.source-coords.editor-uri/editor-uri editor sample-coord))
      nil          "vscode://file/src/app/views.cljs:42:7"
      :vscode      "vscode://file/src/app/views.cljs:42:7"
      :emacs       "vscode://file/src/app/views.cljs:42:7"
      {:custom 42} "vscode://file/src/app/views.cljs:42:7"
      :cursor      "cursor://file/src/app/views.cljs:42:7"
      :windsurf    "windsurf://file/src/app/views.cljs:42:7"
      :zed         "zed://file/src/app/views.cljs:42:7"
      :idea        "idea://open?file=src/app/views.cljs&line=42&column=7")))

(deftest custom-template-substitutes-its-placeholders
  (testing "{path} / {line} / {column} are substituted, and {file} is an alias for {path}"
    (are [template expected]
         (= expected (rf.source-coords.editor-uri/editor-uri {:custom template} sample-coord))
      "my-editor://open?p={path}&l={line}&c={column}" "my-editor://open?p=src/app/views.cljs&l=42&c=7"
      "x://{file}/{line}"                             "x://src/app/views.cljs/42")))

(deftest missing-line-and-column-default-to-1
  (is (= "vscode://file/src/x.cljs:1:1"
         (rf.source-coords.editor-uri/editor-uri :vscode {:file "src/x.cljs"}))))

(deftest missing-file-returns-nil
  (testing "no usable :file → nil URI, so the UI hides the button; a
            project-root cannot conjure one"
    (are [coord opts]
         (nil? (rf.source-coords.editor-uri/editor-uri :vscode coord opts))
      {:line 10 :column 1} nil
      {:file "   "}        nil
      {}                   {:project-root "/abs"})))

(deftest has-source-predicate
  (is (= [true false]
         (mapv rf.source-coords.editor-uri/has-source? [{:file "x.cljs"} {:line 10}]))))

(deftest open-button-title-shape
  (is (= ["Open in editor — src/x.cljs:42" "Open in editor"]
         (mapv rf.source-coords.editor-uri/open-button-title
               [{:file "src/x.cljs" :line 42} {:line 10}]))))

;; ---- forbidden schemes --------------------------------------------------
;;
;; javascript: / data: / vbscript: would turn the launch affordance into in-tab
;; script execution. The gate is a denylist on the LEADING scheme, matched
;; case-insensitively after leading whitespace (Security.md, Tool-Pair.md
;; §Editor URI scheme allowlist). `forbidden-scheme?` is public so a tool's
;; `open!` seam re-applies it to a pre-resolved URI.

(deftest custom-template-with-a-forbidden-scheme-returns-nil
  (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "javascript:alert('xss')"}
                                                    sample-coord))))

(deftest forbidden-scheme-flags-the-three-script-schemes
  (testing "each scheme, whatever its casing and despite leading whitespace"
    (are [uri] (rf.source-coords.editor-uri/forbidden-scheme? uri)
      "JavaScript:alert(1)"
      "\tdata:text/html,xxx"
      "vbscript:msgbox(1)")))

(deftest forbidden-scheme-passes-everything-else
  (testing "no positive allowlist: built-in, http and unknown custom schemes
            pass, a forbidden word past the leading scheme is not the scheme,
            and non-string input is not forbidden"
    (are [uri] (not (rf.source-coords.editor-uri/forbidden-scheme? uri))
      "vscode://file/src/x.cljs:1:1"
      "http://localhost:3000/x"
      "future-editor-9://src/x.cljs:1:1"
      "vscode://file/src/has-javascript:x.cljs:1:1"
      nil)))

;; ---- project-root prefix -------------------------------------------------
;;
;; Source-coord :file is classpath-relative and editor handlers reject relative
;; paths, so `:project-root` prefixes it. A blank root is unset, trailing
;; separators on the root are stripped, and an already-absolute :file (POSIX,
;; drive letter, leading backslash, file: URI) is left alone.

(deftest project-root-prefixes-a-relative-file
  (are [file root expected]
       (= expected (rf.source-coords.editor-uri/editor-uri :vscode {:file file} {:project-root root}))
    "x.cljs"              "/abs/root"         "vscode://file//abs/root/x.cljs:1:1"
    "x.cljs"              "/abs/root///"      "vscode://file//abs/root/x.cljs:1:1"
    "x.cljs"              "C:/code/proj\\"    "vscode://file/C:/code/proj/x.cljs:1:1"
    "src/x.cljs"          "   "               "vscode://file/src/x.cljs:1:1"
    "/etc/a.cljs"         "/should/not/apply" "vscode://file//etc/a.cljs:1:1"
    "C:/abs/x.cljs"       "/should/not/apply" "vscode://file/C:/abs/x.cljs:1:1"
    "c:/abs/x.cljs"       "/should/not/apply" "vscode://file/c:/abs/x.cljs:1:1"
    "\\Users\\me\\x.cljs" "/should/not/apply" "vscode://file/\\Users\\me\\x.cljs:1:1"
    "file:///abs/x.cljs"  "/should/not/apply" "vscode://file/file:///abs/x.cljs:1:1"))
