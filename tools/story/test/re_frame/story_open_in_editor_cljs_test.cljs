(ns re-frame.story-open-in-editor-cljs-test
  "CLJS tests for the Story-side 'Open in editor' chip (rf2-evgf5).

  The pure URI logic lives in `re-frame.source-coords.editor-uri` and is
  matrix-tested on the JVM. This file covers the Story-specific glue:

  - `config/set-editor!` round-trips on the CLJS side.
  - `open-chip` returns nil when the source-coord lacks `:file`.
  - `open-chip` renders an `<a>` hiccup tag with the current editor's
    URI when the coord carries `:file`.
  - The chip carries the `data-test` hook for the e2e suite.
  - `open-chip-for-variant` reads `:source` off the variant body."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.config :as config]
            [re-frame.story.ui.open-in-editor :as open-in-editor]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-editor! []
  (config/set-editor! :vscode))

(use-fixtures :each {:before reset-editor!
                     :after  reset-editor!})

;; ---- chip rendering ------------------------------------------------------

(deftest open-chip-renders-anchor-with-href
  (testing "open-chip returns an <a> hiccup vector when source has :file"
    (let [coord  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7}
          hiccup (open-in-editor/open-chip coord)]
      (is (vector? hiccup))
      (is (= :a (first hiccup)))
      (let [props (second hiccup)]
        (is (string? (:href props)))
        (is (= "vscode://file/src/app/views.cljs:42:7" (:href props)))
        (is (= "story-open-in-editor" (:data-test props)))
        (is (= "vscode" (:data-editor props)))
        (is (fn? (:on-click props)))))))

(deftest open-chip-respects-editor-preference
  (testing "switching editor flips the URI scheme on subsequent renders"
    (let [coord {:file "src/x.cljs" :line 10 :column 1}]
      (config/set-editor! :cursor)
      (is (= "cursor://file/src/x.cljs:10:1"
             (:href (second (open-in-editor/open-chip coord)))))
      (config/set-editor! :idea)
      (is (= "idea://open?file=src/x.cljs&line=10&column=1"
             (:href (second (open-in-editor/open-chip coord))))))))

(deftest open-chip-supports-custom-template
  (testing ":custom template is read live from config"
    (config/set-editor! {:custom "zed://file/{path}:{line}"})
    (let [coord {:file "src/x.cljs" :line 5 :column 2}]
      (is (= "zed://file/src/x.cljs:5"
             (:href (second (open-in-editor/open-chip coord)))))
      (is (= "custom"
             (:data-editor (second (open-in-editor/open-chip coord))))))))

(deftest open-chip-nil-when-source-missing
  (testing "open-chip returns nil when source-coord lacks :file"
    (is (nil? (open-in-editor/open-chip nil)))
    (is (nil? (open-in-editor/open-chip {:line 10})))
    (is (nil? (open-in-editor/open-chip {:file ""})))))

(deftest open-chip-for-variant-reads-source-slot
  (testing "open-chip-for-variant pulls :source off the variant body"
    (let [body {:events []
                :source {:ns 'app.stories
                         :file "src/app/stories.cljs"
                         :line 17
                         :column 3}}
          hiccup (open-in-editor/open-chip-for-variant body)]
      (is (vector? hiccup))
      (is (= "vscode://file/src/app/stories.cljs:17:3"
             (:href (second hiccup))))))
  (testing "open-chip-for-variant nil when variant body has no :source"
    (is (nil? (open-in-editor/open-chip-for-variant {:events []})))
    (is (nil? (open-in-editor/open-chip-for-variant nil)))))

(deftest open-chip-title-attribute-shape
  (testing "the chip's :title attr surfaces file:line for hover"
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/app.cljs" :line 99 :column 4})
          props  (second hiccup)]
      (is (= "Open in editor — src/app.cljs:99"
             (:title props))))))

;; ---- rf2-cm93v / rf2-p887o — Story-side allowlist behaviour -----------
;;
;; The matrix tests for `allowed-uri?` itself live in the shared editor-uri
;; test ns. These cases cover the Story chip's wiring: the chip hides when
;; a `{:custom ...}` template resolves to a disallowed scheme, and renders
;; normally for safe ones — parity with Causa's surface (rf2-p887o).

(deftest open-chip-hides-when-custom-template-resolves-to-unsafe-scheme
  (testing "open-chip returns nil when the resolved URI's scheme is not
            in `editor-uri/allowed-editor-uri-schemes`. Defense-in-depth
            alongside the editor-uri-side javascript:/data:/vbscript:
            reject from rf2-vwcsq — closes the http:/https:/etc. surface
            an upstream {:custom ...} template could otherwise resolve
            to."
    ;; editor-uri/editor-uri already gates javascript:/data:/vbscript:
    ;; — for these the chip is nil regardless of the allowlist. Verify
    ;; those cases still hide.
    (config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/set-editor! {:custom "data:text/html,xxx"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; http: is NOT in editor-uri's reject list — the allowlist is
    ;; the seam that catches it. Without rf2-p887o this used to render
    ;; a clickable chip that would navigate the tab.
    (config/set-editor! {:custom "http://evil.example/{path}"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; Same for https:
    (config/set-editor! {:custom "https://evil.example/{path}"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-renders-when-custom-template-resolves-to-safe-scheme
  (testing "open-chip renders normally when the resolved URI's scheme
            is in `editor-uri/allowed-editor-uri-schemes`"
    (config/set-editor! {:custom "subl://open?path={path}&line={line}"})
    (let [hiccup (open-in-editor/open-chip {:file "src/x.cljs" :line 5})]
      (is (vector? hiccup))
      (is (= "subl://open?path=src/x.cljs&line=5" (:href (second hiccup)))))

    (config/set-editor! {:custom "emacsclient://{path}"})
    (is (some? (open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-for-variant-hides-on-unsafe-scheme
  (testing "open-chip-for-variant inherits the allowlist gate"
    (config/set-editor! {:custom "http://evil.example/{path}"})
    (is (nil? (open-in-editor/open-chip-for-variant
                {:source {:file "src/x.cljs" :line 1}})))))
