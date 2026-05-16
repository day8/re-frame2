(ns re-frame.source-coords-editor-uri-test
  "JVM tests for `re-frame.source-coords.editor-uri` (rf2-evgf5).

  Pure data → data — the same expected URIs verify on the CLJS side via
  `re-frame.source-coords-editor-uri-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.source-coords.editor-uri :as eu]))

(def ^:private sample-coord
  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7})

(deftest vscode-default-scheme
  (testing "nil editor falls through to :vscode"
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (eu/editor-uri nil sample-coord))))
  (testing ":vscode produces vscode://file/<path>:<line>:<column>"
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (eu/editor-uri :vscode sample-coord)))))

(deftest cursor-scheme
  (testing ":cursor produces cursor://file/<path>:<line>:<column>"
    (is (= "cursor://file/src/app/views.cljs:42:7"
           (eu/editor-uri :cursor sample-coord)))))

(deftest windsurf-scheme
  (testing ":windsurf produces windsurf://file/<path>:<line>:<column>"
    (is (= "windsurf://file/src/app/views.cljs:42:7"
           (eu/editor-uri :windsurf sample-coord))))
  (testing ":windsurf with missing :line / :column defaults to 1:1"
    (is (= "windsurf://file/src/x.cljs:1:1"
           (eu/editor-uri :windsurf {:file "src/x.cljs"}))))
  (testing ":windsurf with missing :file → nil URI"
    (is (nil? (eu/editor-uri :windsurf {:line 10 :column 1})))))

(deftest zed-scheme
  (testing ":zed produces zed://file/<path>:<line>:<column>"
    (is (= "zed://file/src/app/views.cljs:42:7"
           (eu/editor-uri :zed sample-coord))))
  (testing ":zed with missing :line / :column defaults to 1:1"
    (is (= "zed://file/src/x.cljs:1:1"
           (eu/editor-uri :zed {:file "src/x.cljs"}))))
  (testing ":zed with missing :file → nil URI"
    (is (nil? (eu/editor-uri :zed {:line 10 :column 1})))))

(deftest idea-scheme
  (testing ":idea produces idea://open?file=&line=&column="
    (is (= "idea://open?file=src/app/views.cljs&line=42&column=7"
           (eu/editor-uri :idea sample-coord)))))

(deftest custom-template-substitutes-all-placeholders
  (testing "{path} / {line} / {column} placeholders are substituted"
    (is (= "my-editor://open?p=src/app/views.cljs&l=42&c=7"
           (eu/editor-uri
             {:custom "my-editor://open?p={path}&l={line}&c={column}"}
             sample-coord)))))

(deftest custom-template-file-alias
  (testing "{file} is an alias for {path}"
    (is (= "x://src/app/views.cljs/42"
           (eu/editor-uri
             {:custom "x://{file}/{line}"}
             sample-coord)))))

(deftest custom-template-omits-missing-placeholders
  (testing "custom template without {column} simply omits the column"
    (is (= "vscode://file/src/app/views.cljs:42"
           (eu/editor-uri
             {:custom "vscode://file/{path}:{line}"}
             sample-coord)))))

(deftest missing-column-defaults-to-1
  (testing ":column missing on source-coord → URI carries column 1"
    (is (= "vscode://file/src/x.cljs:10:1"
           (eu/editor-uri :vscode
                          {:file "src/x.cljs" :line 10})))))

(deftest missing-line-defaults-to-1
  (testing ":line missing on source-coord → URI carries line 1"
    (is (= "vscode://file/src/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "src/x.cljs"})))))

(deftest missing-file-returns-nil
  (testing "no :file → nil URI (UI hides the open button)"
    (is (nil? (eu/editor-uri :vscode {:line 10 :column 1})))
    (is (nil? (eu/editor-uri :vscode nil)))
    (is (nil? (eu/editor-uri :vscode {:file ""})))
    (is (nil? (eu/editor-uri :vscode {:file "   "})))))

(deftest unknown-editor-falls-back-to-vscode
  (testing "unknown editor keyword treated as :vscode (typo-tolerant)"
    (is (= "vscode://file/src/x.cljs:5:1"
           (eu/editor-uri :emacs {:file "src/x.cljs" :line 5})))))

(deftest custom-template-non-string-falls-back
  (testing "custom map with non-string :custom slot falls back to default"
    (is (= "vscode://file/src/x.cljs:5:1"
           (eu/editor-uri {:custom nil} {:file "src/x.cljs" :line 5})))
    (is (= "vscode://file/src/x.cljs:5:1"
           (eu/editor-uri {:custom 42} {:file "src/x.cljs" :line 5})))))

(deftest has-source-predicate
  (testing "has-source? gates the open button render"
    (is (eu/has-source? {:file "x.cljs"}))
    (is (eu/has-source? {:file "x.cljs" :line 1 :column 1}))
    (is (not (eu/has-source? {:line 10})))
    (is (not (eu/has-source? {:file ""})))
    (is (not (eu/has-source? nil)))))

(deftest open-button-title-shape
  (testing "open-button-title carries file:line hover text"
    (is (= "Open in editor — src/x.cljs:42"
           (eu/open-button-title {:file "src/x.cljs" :line 42}))))
  (testing "no :file → generic Open-in-editor label"
    (is (= "Open in editor" (eu/open-button-title nil)))
    (is (= "Open in editor" (eu/open-button-title {:line 10})))))

(deftest known-editors-set
  (testing "known-editors enumerates the built-in scheme keywords"
    (is (contains? eu/known-editors :vscode))
    (is (contains? eu/known-editors :cursor))
    (is (contains? eu/known-editors :windsurf))
    (is (contains? eu/known-editors :zed))
    (is (contains? eu/known-editors :idea))
    ;; :custom is a map-shape, not a member of the keyword set.
    (is (not (contains? eu/known-editors :custom)))))

;; ---- forbidden schemes (rf2-vwcsq) --------------------------------------

(deftest custom-rejects-javascript-scheme
  (testing "{:custom javascript:...} returns nil (in-tab script execution gate)"
    (is (nil? (eu/editor-uri
                {:custom "javascript:alert('xss')"}
                sample-coord)))
    (is (nil? (eu/editor-uri
                {:custom "javascript:fetch('/exfil',{method:'POST',body:document.cookie})"}
                sample-coord)))))

(deftest custom-rejects-data-scheme
  (testing "{:custom data:...} returns nil"
    (is (nil? (eu/editor-uri
                {:custom "data:text/html,<script>alert(1)</script>"}
                sample-coord)))
    (is (nil? (eu/editor-uri
                {:custom "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="}
                sample-coord)))))

(deftest custom-rejects-vbscript-scheme
  (testing "{:custom vbscript:...} returns nil"
    (is (nil? (eu/editor-uri
                {:custom "vbscript:msgbox(\"xss\")"}
                sample-coord)))))

(deftest forbidden-schemes-case-insensitive
  (testing "scheme detection is case-insensitive"
    (is (nil? (eu/editor-uri {:custom "JavaScript:alert(1)"}    sample-coord)))
    (is (nil? (eu/editor-uri {:custom "JAVASCRIPT:alert(1)"}    sample-coord)))
    (is (nil? (eu/editor-uri {:custom "Data:text/html,xxx"}     sample-coord)))
    (is (nil? (eu/editor-uri {:custom "DATA:text/html,xxx"}     sample-coord)))
    (is (nil? (eu/editor-uri {:custom "VBScript:msgbox(1)"}     sample-coord)))
    (is (nil? (eu/editor-uri {:custom "VBSCRIPT:msgbox(1)"}     sample-coord)))))

(deftest forbidden-schemes-tolerate-leading-whitespace
  (testing "leading whitespace doesn't disguise a forbidden scheme"
    (is (nil? (eu/editor-uri {:custom " javascript:alert(1)"}   sample-coord)))
    (is (nil? (eu/editor-uri {:custom "\tdata:text/html,xxx"}   sample-coord)))
    (is (nil? (eu/editor-uri {:custom "  vbscript:msgbox(1)"}   sample-coord)))))

(deftest legitimate-custom-schemes-still-pass
  (testing "ordinary custom editor schemes round-trip cleanly"
    (is (some? (eu/editor-uri {:custom "jetbrains://idea/{path}:{line}"}      sample-coord)))
    (is (some? (eu/editor-uri {:custom "subl://open?path={path}&line={line}"} sample-coord)))
    (is (some? (eu/editor-uri {:custom "emacsclient://open?file={path}"}      sample-coord)))
    (is (some? (eu/editor-uri {:custom "org-protocol://capture?path={path}"}  sample-coord)))
    (is (some? (eu/editor-uri {:custom "vscode-insiders://file/{path}:{line}"} sample-coord)))
    (is (some? (eu/editor-uri {:custom "file://{path}"}                       sample-coord)))))

(deftest builtin-schemes-cannot-trip-the-gate
  (testing "the built-in scheme builders never produce a forbidden scheme"
    (doseq [editor [nil :vscode :cursor :windsurf :zed :idea]]
      (let [uri (eu/editor-uri editor sample-coord)]
        (is (string? uri))
        (is (not (#'eu/forbidden-scheme? uri))
            (str editor " produced a URI that trips the forbidden-scheme gate: "
                 uri))))))

(deftest forbidden-scheme-substring-is-not-rejected
  (testing "the gate matches the LEADING scheme only — substrings elsewhere are fine"
    ;; A path that contains "javascript:" deep inside is not the scheme.
    (is (some? (eu/editor-uri
                 :custom-fallback ; unknown -> vscode default
                 {:file "src/has-javascript:keyword.cljs" :line 1 :column 1})))
    ;; Custom template whose substitution lands "javascript:" mid-URI but
    ;; not at the start.
    (is (some? (eu/editor-uri
                 {:custom "myeditor://open?file={path}"}
                 {:file "javascript:not-a-scheme.cljs" :line 1 :column 1})))))

;; ---- positive scheme allowlist (rf2-cm93v / rf2-p887o) -------------------
;;
;; The allowlist was lifted from `day8.re-frame2-causa.open-in-editor` into
;; this shared ns per rf2-p887o so Story and Causa consume the same predicate.

(deftest allowed-uri-accepts-builtin-editor-schemes
  (testing "the built-in editor schemes pass the allowlist"
    (is (eu/allowed-uri? "vscode://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "cursor://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "windsurf://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri? "zed://file/src/x.cljs:1:1"))
    (is (eu/allowed-uri?
          "idea://open?file=src/x.cljs&line=1&column=1"))))

(deftest allowed-uri-accepts-other-editor-schemes
  (testing "other catalogued editor schemes pass — emacs / vim / sublime
            family, jetbrains alt, file:"
    (is (eu/allowed-uri? "subl://open?path=src/x.cljs"))
    (is (eu/allowed-uri? "emacs:src/x.cljs"))
    (is (eu/allowed-uri? "emacsclient://src/x.cljs"))
    (is (eu/allowed-uri? "vim://src/x.cljs"))
    (is (eu/allowed-uri? "nvim://src/x.cljs"))
    (is (eu/allowed-uri? "txmt://open?url=file://src/x.cljs"))
    (is (eu/allowed-uri? "jetbrains://idea/src/x.cljs"))
    (is (eu/allowed-uri? "file:///abs/path/src/x.cljs"))))

(deftest allowed-uri-rejects-javascript-scheme
  (testing "javascript: is rejected — in-tab script execution vector"
    (is (not (eu/allowed-uri? "javascript:alert(1)")))
    (is (not (eu/allowed-uri? "JavaScript:alert(1)")))
    (is (not (eu/allowed-uri? "JAVASCRIPT:alert(1)")))))

(deftest allowed-uri-rejects-data-scheme
  (testing "data: is rejected — inline-rendered HTML / script vector"
    (is (not (eu/allowed-uri?
               "data:text/html,<script>alert(1)</script>")))
    (is (not (eu/allowed-uri?
               "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==")))))

(deftest allowed-uri-rejects-http-scheme
  (testing "http: is rejected — a custom editor template that resolves
            to `http://...` would navigate the page rather than launch
            an editor (rf2-cm93v)"
    (is (not (eu/allowed-uri? "http://evil.example/x")))
    (is (not (eu/allowed-uri? "HTTP://evil.example/x")))))

(deftest allowed-uri-rejects-https-scheme
  (testing "https: is rejected — same navigation vector as http:"
    (is (not (eu/allowed-uri? "https://evil.example/x")))
    (is (not (eu/allowed-uri? "HTTPS://evil.example/x")))))

(deftest allowed-uri-rejects-vbscript-scheme
  (testing "vbscript: is rejected — legacy IE / WebView2 script vector"
    (is (not (eu/allowed-uri? "vbscript:msgbox(1)")))))

(deftest allowed-uri-handles-non-string-and-empty
  (testing "non-string / empty / scheme-less URIs are rejected"
    (is (not (eu/allowed-uri? nil)))
    (is (not (eu/allowed-uri? "")))
    (is (not (eu/allowed-uri? "no-scheme-here")))
    (is (not (eu/allowed-uri? ":leading-colon")))))

(deftest allowed-uri-tolerates-leading-whitespace
  (testing "leading whitespace doesn't disguise a forbidden scheme"
    (is (not (eu/allowed-uri? " javascript:alert(1)")))
    (is (not (eu/allowed-uri? "\thttp://evil.example/x")))
    (is (eu/allowed-uri? " vscode://file/src/x.cljs:1:1"))))

(deftest allowed-editor-uri-schemes-set-shape
  (testing "the allowlist enumerates the catalogued editor schemes"
    (is (contains? eu/allowed-editor-uri-schemes "vscode"))
    (is (contains? eu/allowed-editor-uri-schemes "cursor"))
    (is (contains? eu/allowed-editor-uri-schemes "windsurf"))
    (is (contains? eu/allowed-editor-uri-schemes "zed"))
    (is (contains? eu/allowed-editor-uri-schemes "idea"))
    (is (contains? eu/allowed-editor-uri-schemes "subl"))
    (is (contains? eu/allowed-editor-uri-schemes "emacs"))
    (is (contains? eu/allowed-editor-uri-schemes "vim"))
    (is (contains? eu/allowed-editor-uri-schemes "file"))
    ;; http: / https: must NOT be on the list — that's the whole point.
    (is (not (contains? eu/allowed-editor-uri-schemes "http")))
    (is (not (contains? eu/allowed-editor-uri-schemes "https")))))

(deftest builtin-schemes-all-pass-the-allowlist
  (testing "every built-in scheme builder produces a URI the allowlist accepts"
    (doseq [editor [nil :vscode :cursor :windsurf :zed :idea]]
      (let [uri (eu/editor-uri editor sample-coord)]
        (is (eu/allowed-uri? uri)
            (str editor " produced a URI the allowlist rejects: " uri))))))

;; ---- project-root prefix (rf2-zfy1e) -------------------------------------
;;
;; Per rf2-zfy1e: source-coord `:file` is classpath-relative; editor URI
;; handlers reject relative paths ("Path does not exist"). The 3-arg form
;; takes a `:project-root` opt that prefixes the file string before the
;; scheme builder runs. Both Unix and Windows-flavoured roots are
;; supported; absolute source-coord paths are passed through unchanged.

(deftest project-root-prefixes-relative-file
  (testing "{:project-root ...} is prepended to a relative source-coord :file"
    (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
           (eu/editor-uri
             :vscode
             {:file "src/app/views.cljs" :line 42 :column 7}
             {:project-root "C:/Users/me/code/my-app"})))))

(deftest project-root-applies-to-every-builtin-scheme
  (testing "project-root reaches the path through every scheme builder"
    (let [opts  {:project-root "/abs/root"}
          coord {:file "x.cljs" :line 1 :column 1}]
      (is (= "vscode://file//abs/root/x.cljs:1:1"
             (eu/editor-uri :vscode coord opts)))
      (is (= "cursor://file//abs/root/x.cljs:1:1"
             (eu/editor-uri :cursor coord opts)))
      (is (= "windsurf://file//abs/root/x.cljs:1:1"
             (eu/editor-uri :windsurf coord opts)))
      (is (= "zed://file//abs/root/x.cljs:1:1"
             (eu/editor-uri :zed coord opts)))
      (is (= "idea://open?file=/abs/root/x.cljs&line=1&column=1"
             (eu/editor-uri :idea coord opts))))))

(deftest project-root-flows-into-custom-template
  (testing "{:project-root ...} composes with {:custom <tpl>} via {path} / {file}"
    (is (= "myeditor://open?path=/abs/root/x.cljs&line=1"
           (eu/editor-uri
             {:custom "myeditor://open?path={path}&line={line}"}
             {:file "x.cljs" :line 1}
             {:project-root "/abs/root"})))
    (is (= "myeditor:///abs/root/x.cljs:1"
           (eu/editor-uri
             {:custom "myeditor://{file}:{line}"}
             {:file "x.cljs" :line 1}
             {:project-root "/abs/root"})))))

(deftest project-root-nil-or-blank-leaves-file-verbatim
  (testing "nil project-root falls back to v1 behaviour (file ships verbatim)"
    (is (= "vscode://file/src/x.cljs:1:1"
           (eu/editor-uri :vscode {:file "src/x.cljs"} nil)))
    (is (= "vscode://file/src/x.cljs:1:1"
           (eu/editor-uri :vscode {:file "src/x.cljs"} {})))
    (is (= "vscode://file/src/x.cljs:1:1"
           (eu/editor-uri :vscode {:file "src/x.cljs"} {:project-root nil}))))
  (testing "blank / whitespace project-root is treated as unset"
    (is (= "vscode://file/src/x.cljs:1:1"
           (eu/editor-uri :vscode {:file "src/x.cljs"} {:project-root ""})))
    (is (= "vscode://file/src/x.cljs:1:1"
           (eu/editor-uri :vscode {:file "src/x.cljs"} {:project-root "   "})))))

(deftest two-arg-form-still-works
  (testing "2-arg form is equivalent to 3-arg with nil opts (back-compat seam)"
    (is (= (eu/editor-uri :vscode sample-coord)
           (eu/editor-uri :vscode sample-coord nil)))
    (is (= (eu/editor-uri :idea sample-coord)
           (eu/editor-uri :idea sample-coord nil)))))

(deftest project-root-strips-trailing-separators
  (testing "trailing `/` or `\\` on the root is stripped so we don't double up"
    (is (= "vscode://file//abs/root/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "/abs/root/"})))
    (is (= "vscode://file//abs/root/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "/abs/root///"})))
    (is (= "vscode://file/C:/code/proj/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "C:/code/proj/"})))
    (is (= "vscode://file/C:/code/proj/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "x.cljs"}
                          {:project-root "C:/code/proj\\"})))))

(deftest project-root-leading-separators-treated-as-absolute
  (testing "a `:file` with a leading `/` or `\\` is treated as absolute and
            passes through without the project-root prefix. The compose-
            path step is conservative: when in doubt about a path's
            absolute-ness, leave it alone rather than risk producing
            `<root>/<root-relative-path>` double-roots."
    (is (= "vscode://file//x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "/x.cljs"}
                          {:project-root "/should-not-apply"})))
    (is (= "vscode://file/\\x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "\\x.cljs"}
                          {:project-root "/should-not-apply"})))))

(deftest absolute-source-coord-file-is-not-prefixed
  (testing "absolute :file passes through unchanged regardless of project-root"
    ;; POSIX absolute.
    (is (= "vscode://file//etc/already-abs.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "/etc/already-abs.cljs"}
                          {:project-root "/should/not/apply"})))
    ;; Windows drive-letter absolute.
    (is (= "vscode://file/C:/abs/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "C:/abs/x.cljs"}
                          {:project-root "/should/not/apply"})))
    (is (= "vscode://file/c:/abs/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "c:/abs/x.cljs"}
                          {:project-root "/should/not/apply"})))
    ;; Windows backslash-leading absolute.
    (is (= "vscode://file/\\Users\\me\\x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "\\Users\\me\\x.cljs"}
                          {:project-root "/should/not/apply"})))
    ;; file: URI passes through.
    (is (= "vscode://file/file:///abs/x.cljs:1:1"
           (eu/editor-uri :vscode
                          {:file "file:///abs/x.cljs"}
                          {:project-root "/should/not/apply"})))))

(deftest project-root-with-blank-file-still-returns-nil
  (testing "project-root cannot conjure a URI when :file is missing"
    (is (nil? (eu/editor-uri :vscode {} {:project-root "/abs"})))
    (is (nil? (eu/editor-uri :vscode {:file ""} {:project-root "/abs"})))
    (is (nil? (eu/editor-uri :vscode nil {:project-root "/abs"})))))

(deftest panel-gallery-regression-rf2-zfy1e
  (testing "regression: the panel-gallery testbed coord shape resolves to an
            absolute URI when the host has plumbed :project-root through"
    ;; Recreates the bead's exact failure: source-coord file shipped as
    ;; `panel_gallery/event_detail_stories.cljs` (classpath-relative);
    ;; with Mike's local re-frame2 worktree as the project root, the URI
    ;; must absolute-path the file the OS-side editor handler resolves.
    (is (= (str "vscode://file/"
                "C:/Users/miket/code/re-frame2/tools/causa/testbeds/"
                "panel_gallery/event_detail_stories.cljs:115:3")
           (eu/editor-uri
             :vscode
             {:file "panel_gallery/event_detail_stories.cljs"
              :line 115
              :column 3}
             {:project-root
              "C:/Users/miket/code/re-frame2/tools/causa/testbeds"})))))
