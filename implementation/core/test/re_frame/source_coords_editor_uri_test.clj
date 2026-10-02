(ns re-frame.source-coords-editor-uri-test
  "JVM tests for `re-frame.source-coords.editor-uri`.

  Pure data → data — the same expected URIs verify on the CLJS side via
  `re-frame.source-coords-editor-uri-cljs-test`."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]))

(def ^:private sample-coord
  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7})

(deftest each-built-in-editor-builds-its-documented-uri
  (testing "nil falls through to :vscode; every other built-in keyword builds
            its own scheme — colon-suffixed for the VS Code family and Zed,
            query parameters for IntelliJ"
    (are [editor expected]
         (= expected (rf.source-coords.editor-uri/editor-uri editor sample-coord))
      nil       "vscode://file/src/app/views.cljs:42:7"
      :vscode   "vscode://file/src/app/views.cljs:42:7"
      :cursor   "cursor://file/src/app/views.cljs:42:7"
      :windsurf "windsurf://file/src/app/views.cljs:42:7"
      :zed      "zed://file/src/app/views.cljs:42:7"
      :idea     "idea://open?file=src/app/views.cljs&line=42&column=7")))

(deftest custom-template-substitutes-its-placeholders
  (testing "{path} / {line} / {column} are substituted, {file} is an alias for
            {path}, and a template without {column} simply omits the column"
    (are [template expected]
         (= expected (rf.source-coords.editor-uri/editor-uri {:custom template} sample-coord))
      "my-editor://open?p={path}&l={line}&c={column}" "my-editor://open?p=src/app/views.cljs&l=42&c=7"
      "x://{file}/{line}"                             "x://src/app/views.cljs/42"
      "vscode://file/{path}:{line}"                   "vscode://file/src/app/views.cljs:42")))

(deftest missing-line-and-column-default-to-1
  (testing "a coord without :line / :column still opens the file, at 1:1"
    (are [editor coord expected]
         (= expected (rf.source-coords.editor-uri/editor-uri editor coord))
      :vscode   {:file "src/x.cljs" :line 10} "vscode://file/src/x.cljs:10:1"
      :vscode   {:file "src/x.cljs"}          "vscode://file/src/x.cljs:1:1"
      :windsurf {:file "src/x.cljs"}          "windsurf://file/src/x.cljs:1:1"
      :zed      {:file "src/x.cljs"}          "zed://file/src/x.cljs:1:1")))

(deftest missing-file-returns-nil
  (testing "no usable :file → nil URI (UI hides the open button)"
    (are [editor coord]
         (nil? (rf.source-coords.editor-uri/editor-uri editor coord))
      :vscode   {:line 10 :column 1}
      :vscode   nil
      :vscode   {:file ""}
      :vscode   {:file "   "}
      :windsurf {:line 10 :column 1}
      :zed      {:line 10 :column 1})))

(deftest unknown-editor-falls-back-to-vscode
  (testing "unknown editor keyword treated as :vscode (typo-tolerant)"
    (is (= "vscode://file/src/x.cljs:5:1"
           (rf.source-coords.editor-uri/editor-uri :emacs {:file "src/x.cljs" :line 5})))))

(deftest custom-template-non-string-falls-back
  (testing "custom map with non-string :custom slot falls back to default"
    (is (= "vscode://file/src/x.cljs:5:1"
           (rf.source-coords.editor-uri/editor-uri {:custom nil} {:file "src/x.cljs" :line 5})))
    (is (= "vscode://file/src/x.cljs:5:1"
           (rf.source-coords.editor-uri/editor-uri {:custom 42} {:file "src/x.cljs" :line 5})))))

(deftest has-source-predicate
  (testing "has-source? gates the open button render"
    (is (rf.source-coords.editor-uri/has-source? {:file "x.cljs"}))
    (is (rf.source-coords.editor-uri/has-source? {:file "x.cljs" :line 1 :column 1}))
    (is (not (rf.source-coords.editor-uri/has-source? {:line 10})))
    (is (not (rf.source-coords.editor-uri/has-source? {:file ""})))
    (is (not (rf.source-coords.editor-uri/has-source? nil)))))

(deftest open-button-title-shape
  (testing "open-button-title carries file:line hover text"
    (is (= "Open in editor — src/x.cljs:42"
           (rf.source-coords.editor-uri/open-button-title {:file "src/x.cljs" :line 42}))))
  (testing "no :file → generic Open-in-editor label"
    (is (= "Open in editor" (rf.source-coords.editor-uri/open-button-title nil)))
    (is (= "Open in editor" (rf.source-coords.editor-uri/open-button-title {:line 10})))))

(deftest known-editors-set
  (testing "known-editors enumerates exactly the built-in scheme keywords;
            :custom is a map-shape, not a member of the keyword set"
    (is (= #{:vscode :cursor :windsurf :zed :idea}
           rf.source-coords.editor-uri/known-editors))))

;; ---- forbidden schemes --------------------------------------------------

(deftest custom-template-with-a-forbidden-scheme-returns-nil
  (testing "javascript: / data: / vbscript: would turn the launch affordance
            into in-tab script execution, so a {:custom ...} template resolving
            to one returns nil — whatever its casing, and however much leading
            whitespace hides it"
    (are [template]
         (nil? (rf.source-coords.editor-uri/editor-uri {:custom template} sample-coord))
      "javascript:alert('xss')"
      "javascript:fetch('/exfil',{method:'POST',body:document.cookie})"
      "data:text/html,<script>alert(1)</script>"
      "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="
      "vbscript:msgbox(\"xss\")"
      "JavaScript:alert(1)"
      "JAVASCRIPT:alert(1)"
      "Data:text/html,xxx"
      "DATA:text/html,xxx"
      "VBScript:msgbox(1)"
      "VBSCRIPT:msgbox(1)"
      " javascript:alert(1)"
      "\tdata:text/html,xxx"
      "  vbscript:msgbox(1)")))

(deftest editor-uri-does-not-reject-forbidden-scheme-substring
  (testing "the gate matches the LEADING scheme only, so `editor-uri`
            returns a URI when a substring elsewhere in the path looks like a
            forbidden scheme"
    ;; A path that contains "javascript:" deep inside is not the scheme.
    (is (some? (rf.source-coords.editor-uri/editor-uri
                 :custom-fallback ; unknown -> vscode default
                 {:file "src/has-javascript:keyword.cljs" :line 1 :column 1})))
    ;; Custom template whose substitution lands "javascript:" mid-URI but
    ;; not at the start.
    (is (some? (rf.source-coords.editor-uri/editor-uri
                 {:custom "myeditor://open?file={path}"}
                 {:file "javascript:not-a-scheme.cljs" :line 1 :column 1})))))

;; ---- public forbidden-scheme? predicate ----------------------------------
;;
;; There is no positive allowlist (no allowed-uri? /
;; allowed-editor-uri-schemes) — the spec mandates a scheme-REJECTION list,
;; not an allowlist (Security.md / Tool-Pair.md §Editor URI scheme
;; allowlist). `forbidden-scheme?` is PUBLIC so the tool `open!` seams can
;; re-apply the cheap denylist at the pre-resolved `{:uri ...}` handoff.
;;
;; The builder block above drives `editor-uri`; the tests below drive the
;; `forbidden-scheme?` PREDICATE directly, so a caller that re-applies the
;; denylist at its own handoff is held to the same rows. The names say which
;; surface is under test — were two tests to share one name, the later
;; `deftest` would silently replace the earlier one.

(deftest forbidden-scheme-flags-the-three-script-schemes
  (testing "forbidden-scheme? is true for javascript: / data: / vbscript:,
            whatever the casing and despite leading whitespace"
    (are [uri] (rf.source-coords.editor-uri/forbidden-scheme? uri)
      "javascript:alert(1)"
      "data:text/html,<script>alert(1)</script>"
      "vbscript:msgbox(1)"
      "JavaScript:alert(1)"
      "JAVASCRIPT:alert(1)"
      "Data:text/html,xxx"
      "DATA:text/html,xxx"
      "VBScript:msgbox(1)"
      "VBSCRIPT:msgbox(1)"
      " javascript:alert(1)"
      "\tdata:text/html,xxx"
      "  vbscript:msgbox(1)")))

(deftest forbidden-scheme-passes-everything-else
  (testing "NO positive allowlist — every non-dangerous scheme passes
            (built-in editors, catalogued long-tail, AND unknown custom
            schemes an allowlist would dead-button)"
    ;; Built-in + catalogued.
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "vscode://file/src/x.cljs:1:1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "cursor://file/src/x.cljs:1:1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "idea://open?file=src/x.cljs&line=1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "subl://open?path=src/x.cljs")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "vim://src/x.cljs")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "file:///abs/path/src/x.cljs")))
    ;; http: / https: PASS — an allowlist would reject these, the
    ;; denylist does not (the spec says do NOT over-gate; only the three
    ;; script schemes are XSS vectors).
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "http://localhost:3000/x")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "https://localhost:3000/x")))
    ;; Unknown custom non-dangerous schemes pass — the whole point of
    ;; having no allowlist.
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "lapce://open?file=src/x.cljs&line=1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "future-editor-9://src/x.cljs:1:1")))))

(deftest forbidden-scheme-handles-non-string-and-empty
  (testing "non-string / empty / scheme-less URIs are not forbidden (the
            absent case is handled by the caller's `(when uri ...)` guard)"
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? nil)))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "no-scheme-here")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? 42)))))

(deftest forbidden-scheme-predicate-does-not-flag-substring
  (testing "the gate matches the LEADING scheme only — a 'javascript:'
            substring deep in the URI is not the scheme"
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "vscode://file/src/has-javascript:x.cljs:1:1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "myeditor://open?file=javascript:not-a-scheme.cljs")))))

;; ---- project-root prefix -------------------------------------------------
;;
;; Source-coord `:file` is classpath-relative; editor URI
;; handlers reject relative paths ("Path does not exist"). The 3-arg form
;; takes a `:project-root` opt that prefixes the file string before the
;; scheme builder runs. Both Unix and Windows-flavoured roots are
;; supported; absolute source-coord paths are passed through verbatim.

(deftest project-root-prefixes-relative-file
  (testing "{:project-root ...} is prepended to a relative source-coord :file"
    (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
           (rf.source-coords.editor-uri/editor-uri
             :vscode
             {:file "src/app/views.cljs" :line 42 :column 7}
             {:project-root "C:/Users/me/code/my-app"})))))

(deftest project-root-applies-to-every-builtin-scheme
  (testing "project-root reaches the path through every scheme builder"
    (let [opts  {:project-root "/abs/root"}
          coord {:file "x.cljs" :line 1 :column 1}]
      (is (= "vscode://file//abs/root/x.cljs:1:1"
             (rf.source-coords.editor-uri/editor-uri :vscode coord opts)))
      (is (= "cursor://file//abs/root/x.cljs:1:1"
             (rf.source-coords.editor-uri/editor-uri :cursor coord opts)))
      (is (= "windsurf://file//abs/root/x.cljs:1:1"
             (rf.source-coords.editor-uri/editor-uri :windsurf coord opts)))
      (is (= "zed://file//abs/root/x.cljs:1:1"
             (rf.source-coords.editor-uri/editor-uri :zed coord opts)))
      (is (= "idea://open?file=/abs/root/x.cljs&line=1&column=1"
             (rf.source-coords.editor-uri/editor-uri :idea coord opts))))))

(deftest project-root-flows-into-custom-template
  (testing "{:project-root ...} composes with {:custom <tpl>} via {path} / {file}"
    (is (= "myeditor://open?path=/abs/root/x.cljs&line=1"
           (rf.source-coords.editor-uri/editor-uri
             {:custom "myeditor://open?path={path}&line={line}"}
             {:file "x.cljs" :line 1}
             {:project-root "/abs/root"})))
    (is (= "myeditor:///abs/root/x.cljs:1"
           (rf.source-coords.editor-uri/editor-uri
             {:custom "myeditor://{file}:{line}"}
             {:file "x.cljs" :line 1}
             {:project-root "/abs/root"})))))

(deftest project-root-nil-or-blank-leaves-file-verbatim
  (testing "nil project-root leaves the file verbatim"
    (is (= "vscode://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode {:file "src/x.cljs"} nil)))
    (is (= "vscode://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode {:file "src/x.cljs"} {})))
    (is (= "vscode://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode {:file "src/x.cljs"} {:project-root nil}))))
  (testing "blank / whitespace project-root is treated as unset"
    (is (= "vscode://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode {:file "src/x.cljs"} {:project-root ""})))
    (is (= "vscode://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode {:file "src/x.cljs"} {:project-root "   "})))))

(deftest project-root-strips-trailing-separators
  (testing "trailing `/` or `\\` on the root is stripped so we don't double up"
    (is (= "vscode://file//abs/root/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "/abs/root/"})))
    (is (= "vscode://file//abs/root/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "/abs/root///"})))
    (is (= "vscode://file/C:/code/proj/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "C:/code/proj/"})))
    (is (= "vscode://file/C:/code/proj/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "C:/code/proj\\"})))))

(deftest absolute-source-coord-file-is-not-prefixed
  (testing "absolute :file passes through verbatim regardless of project-root"
    ;; POSIX absolute.
    (is (= "vscode://file//etc/already-abs.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "/etc/already-abs.cljs"}
                          {:project-root "/should/not/apply"})))
    ;; Windows drive-letter absolute.
    (is (= "vscode://file/C:/abs/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "C:/abs/x.cljs"}
                          {:project-root "/should/not/apply"})))
    (is (= "vscode://file/c:/abs/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "c:/abs/x.cljs"}
                          {:project-root "/should/not/apply"})))
    ;; Windows backslash-leading absolute.
    (is (= "vscode://file/\\Users\\me\\x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "\\Users\\me\\x.cljs"}
                          {:project-root "/should/not/apply"})))
    ;; file: URI passes through.
    (is (= "vscode://file/file:///abs/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "file:///abs/x.cljs"}
                          {:project-root "/should/not/apply"})))))

(deftest project-root-with-blank-file-still-returns-nil
  (testing "project-root cannot conjure a URI when :file is missing"
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode {} {:project-root "/abs"})))
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode {:file ""} {:project-root "/abs"})))
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode nil {:project-root "/abs"})))))
