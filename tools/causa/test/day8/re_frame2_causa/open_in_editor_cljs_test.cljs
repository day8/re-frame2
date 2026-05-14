(ns day8.re-frame2-causa.open-in-editor-cljs-test
  "CLJS smoke tests for Causa's 'Open in editor' surface (rf2-evgf5,
  rf2-g5q8d).

  The URI math + the scheme allowlist live in
  `re-frame.source-coords.editor-uri` and are matrix-tested at the
  core layer (rf2-p887o lifted the allowlist out of this ns). This
  file covers Causa-specific glue:

  - `config/set-editor!` round-trips on the CLJS side.
  - `open-chip` returns nil for source-coords without `:file`.
  - `open-chip` renders an `<a>` hiccup tag with the configured
    editor's URI scheme.
  - The chip carries `data-testid=\"causa-open-in-editor\"` so the
    e2e suite can target it.
  - `open-chip` hides when a `{:custom ...}` template resolves to a
    scheme outside `editor-uri/allowed-editor-uri-schemes`.
  - rf2-g5q8d — `:rf.causa/open-in-editor` reg-event-fx produces a
    `:rf.editor/open` fx with a URI resolved through the rf2-cm93v
    allowlist; runs on the `:rf/causa` frame without contaminating
    the host."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.open-in-editor :as open-in-editor]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

(defn reset-editor! []
  (config/set-editor! :vscode))

;; Combined per-test fixture: resets the re-frame runtime (so each
;; rf2-g5q8d test below sees a clean registrar + frame table) AND the
;; editor preference (so the chip-render tests above see :vscode). The
;; chip-render tests don't drive the runtime, so they pay only the
;; cheap snapshot/restore cost; the rf2-g5q8d tests below need the
;; clean runtime so registrations don't bleed between tests.
(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (reset-editor!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

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

;; ---- rf2-cm93v / rf2-p887o — Causa-side allowlist behaviour ------------
;;
;; The matrix tests for `allowed-uri?` itself live in the shared editor-uri
;; test ns (rf2-p887o). These cases cover the Causa chip's wiring: the
;; chip hides when a `{:custom ...}` template resolves to a disallowed
;; scheme, and renders normally for safe ones.

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
    (config/configure! {:editor {:custom "javascript:alert(1)"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/configure! {:editor {:custom "data:text/html,xxx"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; http: is NOT in editor-uri's reject list — the allowlist is
    ;; the seam that catches it.
    (config/configure! {:editor {:custom "http://evil.example/{path}"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; Same for https:
    (config/configure! {:editor {:custom "https://evil.example/{path}"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-renders-when-custom-template-resolves-to-safe-scheme
  (testing "open-chip renders normally when the resolved URI's scheme
            is in `editor-uri/allowed-editor-uri-schemes`"
    (config/configure! {:editor {:custom "subl://open?path={path}&line={line}"}})
    (let [hiccup (open-in-editor/open-chip {:file "src/x.cljs" :line 5})]
      (is (vector? hiccup))
      (is (= "subl://open?path=src/x.cljs&line=5" (:href (second hiccup)))))

    (config/configure! {:editor {:custom "emacsclient://{path}"}})
    (is (some? (open-in-editor/open-chip {:file "src/x.cljs"})))))

;; ---- rf2-g5q8d — :rf.causa/open-in-editor + :rf.editor/open ------------
;;
;; Per the rf2-3vucz audit, the four Causa panels (trace, issues-ribbon,
;; mcp-server, hydration-debugger) dispatch `[:rf.causa/open-in-editor
;; coord]` when their source-coord affordance is clicked. Pre-rf2-g5q8d
;; the handler was a stub reg-event-db that recorded the coord into
;; app-db and never opened anything — load-bearing UX silently broken.
;;
;; The block below pins the contract of the rewired event-fx + fx pair:
;;
;;   1. Dispatching the event produces a `:rf.editor/open` fx whose
;;      `:uri` resolves through `resolve-uri` (= rf2-cm93v allowlist).
;;   2. Both dispatch shapes are accepted: the bare-coord form (the
;;      hydration debugger's call site) and the `{:source-coord ...}`
;;      wrapper form (the other three panels' call site).
;;   3. A coord whose resolved URI is rejected by the allowlist (custom
;;      `javascript:` / `data:` / `http:` template) yields a fx whose
;;      `:uri` is nil — the side-effect fx is a no-op for nil.
;;   4. The handler runs on Causa's `:rf/causa` frame (per the panels'
;;      `{:frame :rf/causa}` dispatch opts); Causa's app-db is NOT
;;      written (the click is pure navigation).

(defonce ^:private captured-editor-fx (atom []))

(defn- setup!
  "Per-test bootstrap shared by every rf2-g5q8d test: register Causa's
  handlers, allocate the `:rf/causa` frame, and replace the
  `:rf.editor/open` reg-fx with a capture stub so assertions can
  inspect the fx args without touching `window.location`. Mirrors the
  fx-replacement pattern in `time_travel_cljs_test.cljs`."
  []
  (reset! captured-editor-fx [])
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args] (swap! captured-editor-fx conj args))))

(deftest open-in-editor-event-emits-fx-with-resolved-uri
  (testing "rf2-g5q8d — dispatching `:rf.causa/open-in-editor` with
            a bare coord (the hydration-debugger shape) produces a
            `:rf.editor/open` fx whose :uri is the resolved URI"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:ns 'app.events :file "src/app/events.cljs"
                          :line 17 :column 3}])
      (is (= 1 (count @captured-editor-fx))
          "exactly one open-fx fires per dispatch")
      (is (= "vscode://file/src/app/events.cljs:17:3"
             (:uri (first @captured-editor-fx)))
          "the resolved vscode:// URI rides on the fx args"))))

(deftest open-in-editor-event-accepts-wrapped-shape
  (testing "rf2-g5q8d — dispatching with `{:source-coord coord}` (the
            trace / issues-ribbon / mcp-server panels' shape) produces
            the same fx as the bare-coord form"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:source-coord {:file "src/x.cljs" :line 5 :column 1}}])
      (is (= "vscode://file/src/x.cljs:5:1"
             (:uri (first @captured-editor-fx)))
          "the wrapper shape unwraps + resolves the same way"))))

(deftest open-in-editor-event-parses-display-string-coord
  (testing "rf2-g5q8d — the three trace-style panels (trace, issues-
            ribbon, mcp-server) project the structured coord to a
            `\"file:line\"` display string at projection time; the
            handler parses the display string back to the structured
            form so the URI build works end-to-end. This is the
            single-failure path that landed the load-bearing UX in
            the rf2-3vucz audit."
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:source-coord "src/app/events.cljs:42"}])
      (is (= "vscode://file/src/app/events.cljs:42:1"
             (:uri (first @captured-editor-fx)))
          "display string parses to `:file` + `:line`; `:column`
           falls through to the editor-uri builder's default of 1"))))

(deftest open-in-editor-event-parses-bare-display-string
  (testing "rf2-g5q8d — defensive shape: bare display string with no
            wrapper map (no panel does this today; handler accepts it
            for future callers that don't wrap)"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor "src/x.cljs:7"])
      (is (= "vscode://file/src/x.cljs:7:1"
             (:uri (first @captured-editor-fx)))))))

(deftest open-in-editor-event-display-string-without-line
  (testing "rf2-g5q8d — display string with no trailing line number
            (degenerate: the projection helpers always include line,
            but the parser falls through gracefully to `{:file <s>}`)"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:source-coord "src/x.cljs"}])
      (is (= "vscode://file/src/x.cljs:1:1"
             (:uri (first @captured-editor-fx)))
          "`:line` defaults to 1 via editor-uri"))))

(deftest open-in-editor-event-honours-editor-preference
  (testing "rf2-g5q8d — the fx's URI reflects `config/get-editor`
            (the same source of truth the chip render uses)"
    (setup!)
    (config/set-editor! :cursor)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:file "src/x.cljs" :line 10}])
      (is (= "cursor://file/src/x.cljs:10:1"
             (:uri (first @captured-editor-fx)))))))

(deftest open-in-editor-event-rejects-javascript-scheme
  (testing "rf2-g5q8d / rf2-cm93v — a custom template that resolves to
            `javascript:` is rejected at the handler seam. The
            editor-uri-side gate (rf2-vwcsq) returns nil; the fx
            receives nil and is a no-op. Defense-in-depth — the test
            pins the contract even though the editor-uri gate fires
            first."
    (setup!)
    (config/configure! {:editor {:custom "javascript:alert(1)"}})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (= 1 (count @captured-editor-fx))
          "fx still fires — the handler doesn't short-circuit")
      (is (nil? (:uri (first @captured-editor-fx)))
          "the resolved URI is nil — `open!` will refuse to navigate"))))

(deftest open-in-editor-event-rejects-http-scheme
  (testing "rf2-g5q8d / rf2-cm93v — a custom template that resolves to
            `http:` is rejected by the Causa-side positive allowlist
            (http: is NOT in the editor-uri-side reject list, so this
            is the seam that catches it)"
    (setup!)
    (config/configure! {:editor {:custom "http://evil.example/{path}"}})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (nil? (:uri (first @captured-editor-fx)))
          "http: is outside `allowed-editor-uri-schemes`"))))

(deftest open-in-editor-event-runs-on-causa-frame-without-host-contamination
  (testing "rf2-g5q8d — the handler doesn't write to Causa's app-db
            (no `:db` in the returned effect map). The click is pure
            navigation; no host-frame escape, no Causa-frame state
            pollution."
    (setup!)
    (rf/with-frame :rf/causa
      (let [pre-db (frame/frame-app-db-value :rf/causa)]
        (rf/dispatch-sync [:rf.causa/open-in-editor
                           {:file "src/x.cljs" :line 1}])
        (let [post-db (frame/frame-app-db-value :rf/causa)]
          (is (= pre-db post-db)
              "Causa's app-db is untouched by the click — no
               `:last-open-in-editor-coord` etc. (the prior stub
               reg-event-db's behaviour, removed by rf2-g5q8d)"))))))

(deftest open-in-editor-fx-receives-uri-key-not-source-coord
  (testing "rf2-g5q8d — `:rf.editor/open` invoked via the canonical
            pre-resolved shape `{:uri \"...\"}`. The handler in this
            test is the capture stub; the assertion here pins the
            contract that the producer event-fx passes a `:uri` key
            (vs `:source-coord`)."
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (contains? (first @captured-editor-fx) :uri)
          "fx arg shape: `{:uri <string-or-nil>}`")
      (is (not (contains? (first @captured-editor-fx) :source-coord))
          "the resolve step happens in the event-fx, not the fx"))))

(deftest open-in-editor-event-resolves-through-shared-resolve-uri-helper
  (testing "rf2-g5q8d — the event-fx's URI matches what `open-chip`
            renders for the same coord (one source of truth for URI
            resolution + allowlist gating across the data path and
            the side-effect path)"
    (setup!)
    (let [coord {:file "src/app/events.cljs" :line 42 :column 7}]
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/open-in-editor coord]))
      (is (= (:href (second (open-in-editor/open-chip coord)))
             (:uri (first @captured-editor-fx)))
          "chip's :href ≡ fx's :uri"))))
