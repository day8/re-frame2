(ns day8.re-frame2-causa.open-in-editor-cljs-test
  "CLJS smoke tests for Causa's 'Open in editor' chip (rf2-evgf5).

  The URI math lives in `re-frame.source-coords.editor-uri` and is
  matrix-tested at the core layer. This file covers Causa-specific
  glue:

  - `config/set-editor!` round-trips on the CLJS side.
  - `open-chip` returns nil for source-coords without `:file`.
  - `open-chip` renders an `<a>` hiccup tag with the configured
    editor's URI scheme.
  - The chip carries `data-testid=\"causa-open-in-editor\"` so the
    e2e suite can target it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]))

(defn reset-editor! []
  (config/set-editor! :vscode))

(use-fixtures :each {:before reset-editor!
                     :after  reset-editor!})

(deftest open-chip-renders-anchor-with-href
  (testing "open-chip returns an <a> hiccup vector when source has :file"
    (let [coord  {:ns 'app.events :file "src/app/events.cljs" :line 17 :column 3}
          hiccup (open-in-editor/open-chip coord)]
      (is (vector? hiccup))
      (is (= :a (first hiccup)))
      (let [props (second hiccup)]
        (is (= "vscode://file/src/app/events.cljs:17:3" (:href props)))
        (is (= "causa-open-in-editor" (:data-testid props)))
        (is (= "vscode" (:data-editor props)))
        (is (fn? (:on-click props)))))))

(deftest open-chip-respects-editor-preference
  (testing "switching Causa's editor flips the URI on render"
    (let [coord {:file "src/x.cljs" :line 10}]
      (config/set-editor! :cursor)
      (is (= "cursor://file/src/x.cljs:10:1"
             (:href (second (open-in-editor/open-chip coord)))))
      (config/set-editor! :idea)
      (is (= "idea://open?file=src/x.cljs&line=10&column=1"
             (:href (second (open-in-editor/open-chip coord))))))))

(deftest open-chip-supports-custom-template
  (testing ":custom template via Causa configure!"
    (config/configure! {:editor {:custom "zed://file/{path}:{line}"}})
    (is (= "zed://file/src/x.cljs:5"
           (:href (second (open-in-editor/open-chip
                            {:file "src/x.cljs" :line 5 :column 2}))))))
  (testing ":custom data-editor attr"
    (is (= "custom"
           (:data-editor
             (second (open-in-editor/open-chip
                       {:file "src/x.cljs" :line 5})))))))

(deftest open-chip-nil-when-source-missing
  (testing "open-chip returns nil when source-coord lacks :file"
    (is (nil? (open-in-editor/open-chip nil)))
    (is (nil? (open-in-editor/open-chip {:line 1})))
    (is (nil? (open-in-editor/open-chip {:file ""})))))

;; ---- rf2-cm93v — scheme allowlist (defense-in-depth) -------------------

(deftest allowed-uri-accepts-builtin-editor-schemes
  (testing "the built-in editor schemes pass the allowlist"
    (is (open-in-editor/allowed-uri? "vscode://file/src/x.cljs:1:1"))
    (is (open-in-editor/allowed-uri? "cursor://file/src/x.cljs:1:1"))
    (is (open-in-editor/allowed-uri? "windsurf://file/src/x.cljs:1:1"))
    (is (open-in-editor/allowed-uri? "zed://file/src/x.cljs:1:1"))
    (is (open-in-editor/allowed-uri?
          "idea://open?file=src/x.cljs&line=1&column=1"))))

(deftest allowed-uri-accepts-other-editor-schemes
  (testing "other catalogued editor schemes pass — emacs / vim / sublime
            family, jetbrains alt, file:"
    (is (open-in-editor/allowed-uri? "subl://open?path=src/x.cljs"))
    (is (open-in-editor/allowed-uri? "emacs:src/x.cljs"))
    (is (open-in-editor/allowed-uri? "emacsclient://src/x.cljs"))
    (is (open-in-editor/allowed-uri? "vim://src/x.cljs"))
    (is (open-in-editor/allowed-uri? "nvim://src/x.cljs"))
    (is (open-in-editor/allowed-uri? "txmt://open?url=file://src/x.cljs"))
    (is (open-in-editor/allowed-uri? "jetbrains://idea/src/x.cljs"))
    (is (open-in-editor/allowed-uri? "file:///abs/path/src/x.cljs"))))

(deftest allowed-uri-rejects-javascript-scheme
  (testing "javascript: is rejected — in-tab script execution vector"
    (is (not (open-in-editor/allowed-uri? "javascript:alert(1)")))
    (is (not (open-in-editor/allowed-uri? "JavaScript:alert(1)")))
    (is (not (open-in-editor/allowed-uri? "JAVASCRIPT:alert(1)")))))

(deftest allowed-uri-rejects-data-scheme
  (testing "data: is rejected — inline-rendered HTML / script vector"
    (is (not (open-in-editor/allowed-uri?
               "data:text/html,<script>alert(1)</script>")))
    (is (not (open-in-editor/allowed-uri?
               "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==")))))

(deftest allowed-uri-rejects-http-scheme
  (testing "http: is rejected — a custom editor template that resolves
            to `http://...` would navigate the page rather than launch
            an editor (rf2-cm93v)"
    (is (not (open-in-editor/allowed-uri? "http://evil.example/x")))
    (is (not (open-in-editor/allowed-uri? "HTTP://evil.example/x")))))

(deftest allowed-uri-rejects-https-scheme
  (testing "https: is rejected — same navigation vector as http:"
    (is (not (open-in-editor/allowed-uri? "https://evil.example/x")))
    (is (not (open-in-editor/allowed-uri? "HTTPS://evil.example/x")))))

(deftest allowed-uri-rejects-vbscript-scheme
  (testing "vbscript: is rejected — legacy IE / WebView2 script vector"
    (is (not (open-in-editor/allowed-uri? "vbscript:msgbox(1)")))))

(deftest allowed-uri-handles-non-string-and-empty
  (testing "non-string / empty / scheme-less URIs are rejected"
    (is (not (open-in-editor/allowed-uri? nil)))
    (is (not (open-in-editor/allowed-uri? "")))
    (is (not (open-in-editor/allowed-uri? "no-scheme-here")))
    (is (not (open-in-editor/allowed-uri? ":leading-colon")))))

(deftest allowed-uri-tolerates-leading-whitespace
  (testing "leading whitespace doesn't disguise a forbidden scheme"
    (is (not (open-in-editor/allowed-uri? " javascript:alert(1)")))
    (is (not (open-in-editor/allowed-uri? "\thttp://evil.example/x")))
    (is (open-in-editor/allowed-uri? " vscode://file/src/x.cljs:1:1"))))

(deftest open-chip-hides-when-custom-template-resolves-to-unsafe-scheme
  (testing "open-chip returns nil when the resolved URI's scheme is not
            in `allowed-editor-uri-schemes` (rf2-cm93v). Defense-in-depth
            alongside the editor-uri-side javascript:/data:/vbscript:
            reject from rf2-vwcsq — closes the http:/https:/etc. surface
            an upstream {:custom ...} template could otherwise resolve
            to."
    ;; editor-uri/editor-uri already gates javascript:/data:/vbscript:
    ;; — for these the chip is nil regardless of the Causa-side
    ;; allowlist. Verify those cases still hide.
    (config/configure! {:editor {:custom "javascript:alert(1)"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/configure! {:editor {:custom "data:text/html,xxx"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; http: is NOT in editor-uri's reject list — the Causa-side
    ;; allowlist is the seam that catches it.
    (config/configure! {:editor {:custom "http://evil.example/{path}"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; Same for https:
    (config/configure! {:editor {:custom "https://evil.example/{path}"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-renders-when-custom-template-resolves-to-safe-scheme
  (testing "open-chip renders normally when the resolved URI's scheme
            is in `allowed-editor-uri-schemes` (rf2-cm93v positive
            half)"
    (config/configure! {:editor {:custom "subl://open?path={path}&line={line}"}})
    (let [hiccup (open-in-editor/open-chip {:file "src/x.cljs" :line 5})]
      (is (vector? hiccup))
      (is (= "subl://open?path=src/x.cljs&line=5" (:href (second hiccup)))))

    (config/configure! {:editor {:custom "emacsclient://{path}"}})
    (is (some? (open-in-editor/open-chip {:file "src/x.cljs"})))))
