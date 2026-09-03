(ns re-frame.source-coords-editor-uri-test
  "JVM tests for `re-frame.source-coords.editor-uri` (rf2-evgf5).

  Pure data → data — the same expected URIs verify on the CLJS side via
  `re-frame.source-coords-editor-uri-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]))

(def ^:private sample-coord
  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7})

(deftest vscode-default-scheme
  (testing "nil editor falls through to :vscode"
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (rf.source-coords.editor-uri/editor-uri nil sample-coord))))
  (testing ":vscode produces vscode://file/<path>:<line>:<column>"
    (is (= "vscode://file/src/app/views.cljs:42:7"
           (rf.source-coords.editor-uri/editor-uri :vscode sample-coord)))))

(deftest cursor-scheme
  (testing ":cursor produces cursor://file/<path>:<line>:<column>"
    (is (= "cursor://file/src/app/views.cljs:42:7"
           (rf.source-coords.editor-uri/editor-uri :cursor sample-coord)))))

(deftest windsurf-scheme
  (testing ":windsurf produces windsurf://file/<path>:<line>:<column>"
    (is (= "windsurf://file/src/app/views.cljs:42:7"
           (rf.source-coords.editor-uri/editor-uri :windsurf sample-coord))))
  (testing ":windsurf with missing :line / :column defaults to 1:1"
    (is (= "windsurf://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :windsurf {:file "src/x.cljs"}))))
  (testing ":windsurf with missing :file → nil URI"
    (is (nil? (rf.source-coords.editor-uri/editor-uri :windsurf {:line 10 :column 1})))))

(deftest zed-scheme
  (testing ":zed produces zed://file/<path>:<line>:<column>"
    (is (= "zed://file/src/app/views.cljs:42:7"
           (rf.source-coords.editor-uri/editor-uri :zed sample-coord))))
  (testing ":zed with missing :line / :column defaults to 1:1"
    (is (= "zed://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :zed {:file "src/x.cljs"}))))
  (testing ":zed with missing :file → nil URI"
    (is (nil? (rf.source-coords.editor-uri/editor-uri :zed {:line 10 :column 1})))))

(deftest idea-scheme
  (testing ":idea produces idea://open?file=&line=&column="
    (is (= "idea://open?file=src/app/views.cljs&line=42&column=7"
           (rf.source-coords.editor-uri/editor-uri :idea sample-coord)))))

(deftest custom-template-substitutes-all-placeholders
  (testing "{path} / {line} / {column} placeholders are substituted"
    (is (= "my-editor://open?p=src/app/views.cljs&l=42&c=7"
           (rf.source-coords.editor-uri/editor-uri
             {:custom "my-editor://open?p={path}&l={line}&c={column}"}
             sample-coord)))))

(deftest custom-template-file-alias
  (testing "{file} is an alias for {path}"
    (is (= "x://src/app/views.cljs/42"
           (rf.source-coords.editor-uri/editor-uri
             {:custom "x://{file}/{line}"}
             sample-coord)))))

(deftest custom-template-omits-missing-placeholders
  (testing "custom template without {column} simply omits the column"
    (is (= "vscode://file/src/app/views.cljs:42"
           (rf.source-coords.editor-uri/editor-uri
             {:custom "vscode://file/{path}:{line}"}
             sample-coord)))))

(deftest missing-column-defaults-to-1
  (testing ":column missing on source-coord → URI carries column 1"
    (is (= "vscode://file/src/x.cljs:10:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "src/x.cljs" :line 10})))))

(deftest missing-line-defaults-to-1
  (testing ":line missing on source-coord → URI carries line 1"
    (is (= "vscode://file/src/x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "src/x.cljs"})))))

(deftest missing-file-returns-nil
  (testing "no :file → nil URI (UI hides the open button)"
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode {:line 10 :column 1})))
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode nil)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode {:file ""})))
    (is (nil? (rf.source-coords.editor-uri/editor-uri :vscode {:file "   "})))))

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
  (testing "known-editors enumerates the built-in scheme keywords"
    (is (contains? rf.source-coords.editor-uri/known-editors :vscode))
    (is (contains? rf.source-coords.editor-uri/known-editors :cursor))
    (is (contains? rf.source-coords.editor-uri/known-editors :windsurf))
    (is (contains? rf.source-coords.editor-uri/known-editors :zed))
    (is (contains? rf.source-coords.editor-uri/known-editors :idea))
    ;; :custom is a map-shape, not a member of the keyword set.
    (is (not (contains? rf.source-coords.editor-uri/known-editors :custom)))))

;; ---- forbidden schemes (rf2-vwcsq) --------------------------------------

(deftest custom-rejects-javascript-scheme
  (testing "{:custom javascript:...} returns nil (in-tab script execution gate)"
    (is (nil? (rf.source-coords.editor-uri/editor-uri
                {:custom "javascript:alert('xss')"}
                sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri
                {:custom "javascript:fetch('/exfil',{method:'POST',body:document.cookie})"}
                sample-coord)))))

(deftest custom-rejects-data-scheme
  (testing "{:custom data:...} returns nil"
    (is (nil? (rf.source-coords.editor-uri/editor-uri
                {:custom "data:text/html,<script>alert(1)</script>"}
                sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri
                {:custom "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="}
                sample-coord)))))

(deftest custom-rejects-vbscript-scheme
  (testing "{:custom vbscript:...} returns nil"
    (is (nil? (rf.source-coords.editor-uri/editor-uri
                {:custom "vbscript:msgbox(\"xss\")"}
                sample-coord)))))

(deftest forbidden-schemes-case-insensitive
  (testing "scheme detection is case-insensitive"
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "JavaScript:alert(1)"}    sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "JAVASCRIPT:alert(1)"}    sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "Data:text/html,xxx"}     sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "DATA:text/html,xxx"}     sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "VBScript:msgbox(1)"}     sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "VBSCRIPT:msgbox(1)"}     sample-coord)))))

(deftest forbidden-schemes-tolerate-leading-whitespace
  (testing "leading whitespace doesn't disguise a forbidden scheme"
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom " javascript:alert(1)"}   sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "\tdata:text/html,xxx"}   sample-coord)))
    (is (nil? (rf.source-coords.editor-uri/editor-uri {:custom "  vbscript:msgbox(1)"}   sample-coord)))))

(deftest legitimate-custom-schemes-still-pass
  (testing "ordinary custom editor schemes round-trip cleanly"
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "jetbrains://idea/{path}:{line}"}      sample-coord)))
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "subl://open?path={path}&line={line}"} sample-coord)))
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "emacsclient://open?file={path}"}      sample-coord)))
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "org-protocol://capture?path={path}"}  sample-coord)))
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "vscode-insiders://file/{path}:{line}"} sample-coord)))
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "file://{path}"}                       sample-coord))))
  (testing "rf2-ox357n: an UNKNOWN, uncatalogued, non-dangerous custom
            scheme passes through — there is no positive allowlist, so a
            future editor's scheme is NOT a silent dead button"
    ;; `lapce:` is a real editor scheme that was never in the old
    ;; allowlist; under denylist-only it must produce a clickable URI.
    (is (= "lapce://open?file=src/app/views.cljs&line=42"
           (rf.source-coords.editor-uri/editor-uri {:custom "lapce://open?file={path}&line={line}"} sample-coord)))
    ;; A wholly-made-up scheme also passes — the gate rejects ONLY the
    ;; three known-bad schemes, everything else is the developer's call.
    (is (some? (rf.source-coords.editor-uri/editor-uri {:custom "future-editor-9://{path}:{line}"} sample-coord)))))

(deftest builtin-schemes-cannot-trip-the-gate
  (testing "the built-in scheme builders never produce a forbidden scheme"
    (doseq [editor [nil :vscode :cursor :windsurf :zed :idea]]
      (let [uri (rf.source-coords.editor-uri/editor-uri editor sample-coord)]
        (is (string? uri))
        (is (not (rf.source-coords.editor-uri/forbidden-scheme? uri))
            (str editor " produced a URI that trips the forbidden-scheme gate: "
                 uri))))))

(deftest editor-uri-does-not-reject-forbidden-scheme-substring
  (testing "the gate matches the LEADING scheme only, so `editor-uri` still
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

;; ---- public forbidden-scheme? predicate (rf2-ox357n) ---------------------
;;
;; rf2-ox357n removed the positive allowlist (allowed-uri? /
;; allowed-editor-uri-schemes) — the spec mandates a scheme-REJECTION list,
;; not an allowlist (Security.md / Tool-Pair.md §Editor URI scheme
;; allowlist). `forbidden-scheme?` is now PUBLIC so the tool `open!` seams
;; can re-apply the cheap denylist at the pre-resolved `{:uri ...}` handoff.
;;
;; Each test here has a twin in the rf2-vwcsq block above: that block drives
;; the `editor-uri` BUILDER, this one drives the `forbidden-scheme?` PREDICATE
;; directly. rf2-9e4lf: the substring pair was the one that collided — both
;; deftests were named `forbidden-scheme-substring-is-not-rejected`, so when
;; this block landed (f2b24e146f) its test silently replaced the builder-level
;; one, which had not run since. The axis is which surface is under test, so
;; that is what the two names now say.

(deftest forbidden-scheme-rejects-the-three-known-bad
  (testing "forbidden-scheme? is true for javascript: / data: / vbscript:"
    (is (rf.source-coords.editor-uri/forbidden-scheme? "javascript:alert(1)"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "data:text/html,<script>alert(1)</script>"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "vbscript:msgbox(1)"))))

(deftest forbidden-scheme-is-case-insensitive
  (testing "rf2-ox357n: bad schemes are rejected regardless of casing"
    (is (rf.source-coords.editor-uri/forbidden-scheme? "JavaScript:alert(1)"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "JAVASCRIPT:alert(1)"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "Data:text/html,xxx"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "DATA:text/html,xxx"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "VBScript:msgbox(1)"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "VBSCRIPT:msgbox(1)"))))

(deftest forbidden-scheme-tolerates-leading-whitespace
  (testing "leading whitespace doesn't disguise a forbidden scheme"
    (is (rf.source-coords.editor-uri/forbidden-scheme? " javascript:alert(1)"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "\tdata:text/html,xxx"))
    (is (rf.source-coords.editor-uri/forbidden-scheme? "  vbscript:msgbox(1)"))))

(deftest forbidden-scheme-passes-everything-else
  (testing "rf2-ox357n: NO positive allowlist — every non-dangerous scheme
            passes (built-in editors, catalogued long-tail, AND unknown
            custom schemes that the old allowlist would have dead-buttoned)"
    ;; Built-in + catalogued.
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "vscode://file/src/x.cljs:1:1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "cursor://file/src/x.cljs:1:1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "idea://open?file=src/x.cljs&line=1")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "subl://open?path=src/x.cljs")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "vim://src/x.cljs")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "file:///abs/path/src/x.cljs")))
    ;; http: / https: now PASS — the old allowlist rejected these, the
    ;; denylist does not (the spec says do NOT over-gate; only the three
    ;; script schemes are XSS vectors).
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "http://localhost:3000/x")))
    (is (not (rf.source-coords.editor-uri/forbidden-scheme? "https://localhost:3000/x")))
    ;; Unknown custom non-dangerous schemes pass — the whole point of
    ;; dropping the allowlist.
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
  (testing "nil project-root falls back to v1 behaviour (file ships verbatim)"
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

(deftest two-arg-form-still-works
  (testing "2-arg form is equivalent to 3-arg with nil opts (back-compat seam)"
    (is (= (rf.source-coords.editor-uri/editor-uri :vscode sample-coord)
           (rf.source-coords.editor-uri/editor-uri :vscode sample-coord nil)))
    (is (= (rf.source-coords.editor-uri/editor-uri :idea sample-coord)
           (rf.source-coords.editor-uri/editor-uri :idea sample-coord nil)))))

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

(deftest project-root-leading-separators-treated-as-absolute
  (testing "a `:file` with a leading `/` or `\\` is treated as absolute and
            passes through without the project-root prefix. The compose-
            path step is conservative: when in doubt about a path's
            absolute-ness, leave it alone rather than risk producing
            `<root>/<root-relative-path>` double-roots."
    (is (= "vscode://file//x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "/x.cljs"}
                          {:project-root "/should-not-apply"})))
    (is (= "vscode://file/\\x.cljs:1:1"
           (rf.source-coords.editor-uri/editor-uri :vscode
                          {:file "\\x.cljs"}
                          {:project-root "/should-not-apply"})))))

(deftest absolute-source-coord-file-is-not-prefixed
  (testing "absolute :file passes through unchanged regardless of project-root"
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

(deftest panel-gallery-regression-rf2-zfy1e
  (testing "regression: the panel-gallery testbed coord shape resolves to an
            absolute URI when the host has plumbed :project-root through"
    ;; Recreates the bead's exact failure: source-coord file shipped as
    ;; `panel_gallery/event_detail_stories.cljs` (classpath-relative);
    ;; with a local checkout as the project root, the URI must
    ;; absolute-path the file the OS-side editor handler resolves.
    (is (= (str "vscode://file/"
                "C:/Users/me/code/my-app/tools/xray/testbeds/"
                "panel_gallery/event_detail_stories.cljs:115:3")
           (rf.source-coords.editor-uri/editor-uri
             :vscode
             {:file "panel_gallery/event_detail_stories.cljs"
              :line 115
              :column 3}
             {:project-root
              "C:/Users/me/code/my-app/tools/xray/testbeds"})))))
