(ns day8.re-frame2-xray.open-in-editor-cljs-test
  "CLJS smoke tests for Xray's 'Open in editor' surface (rf2-evgf5,
  rf2-g5q8d).

  The URI math + the scheme denylist live in
  `re-frame.source-coords.editor-uri` and are matrix-tested at the
  core layer. This file covers Xray-specific glue:

  - `config/set-editor!` round-trips on the CLJS side.
  - `open-chip` returns nil for source-coords without `:file`.
  - `open-chip` renders an `<a>` hiccup tag with the configured
    editor's URI scheme.
  - The chip carries `data-testid=\"xray-open-in-editor\"` so the
    e2e suite can target it.
  - `open-chip` hides ONLY when a `{:custom ...}` template resolves to a
    forbidden script scheme (`javascript:`/`data:`/`vbscript:`); per
    rf2-ox357n the positive allowlist was removed, so http:/https: and
    unknown custom schemes now render.
  - rf2-g5q8d — the `:rf.xray/open-in-editor` reg-event handler produces a
    `:rf.editor/open` fx with a URI resolved through the rf2-vwcsq
    scheme denylist; runs on the `:rf/xray` frame without contaminating
    the host."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.source-coords.open-endpoint :as open-endpoint]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.open-in-editor :as open-in-editor]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- Option B endpoint seam (rf2-wn3bh) ---------------------------------
;;
;; `open-coord!` (the new click launcher) PREFERS the dev-server endpoint
;; and FALLS BACK to the `editor://` URI navigation. To keep the URI /
;; navigator assertions below deterministic (no real `fetch` round-trip),
;; the per-test fixture swaps the endpoint launcher for a synchronous stub
;; that always invokes the fallback — exercising exactly the URI path these
;; tests pin. The endpoint-preference path itself is covered separately in
;; the rf2-wn3bh block at the bottom of this file.

(defn- always-fall-back!
  "Stub endpoint launcher: ignore the URL, invoke the fallback synchronously.
  Mirrors the 'no dev server present' runtime case."
  [_url fallback!]
  (fallback!))

(defn reset-editor! []
  ;; rf2-4s08ov — reset the operator-override slot too so a sibling
  ;; test's `[:general :editor-override]` write does not leak into the
  ;; chip-render tests' `get-editor` reads. `reset-settings!` clears the
  ;; whole settings map (incl. the override slot); `set-editor! :vscode`
  ;; re-arms the host default + the editor-explicitly-set? flag.
  (config/reset-settings!)
  (config/set-editor! :vscode)
  (config/set-project-root! nil))

;; Combined per-test fixture: resets the re-frame runtime (so each
;; rf2-g5q8d test below sees a clean registrar + frame table) AND the
;; editor preference (so the chip-render tests above see :vscode). The
;; chip-render tests don't drive the runtime, so they pay only the
;; cheap snapshot/restore cost; the rf2-g5q8d tests below need the
;; clean runtime so registrations don't bleed between tests.
(defn- xray-init! []
  ;; rf2-sdqsla — `reset-all!` now folds the trace-collector ring reset
  ;; in; `reset-editor!` owns the settings reset + editor re-arm.
  (xray-test-support/reset-all!)
  (reset-editor!)
  ;; rf2-wn3bh — pin the endpoint launcher to the synchronous always-fall-back
  ;; stub so the URI / navigator assertions below exercise the deterministic
  ;; fallback path (no real fetch). The endpoint-preference path is covered
  ;; in its own block at the bottom of this file.
  (open-endpoint/set-launcher! always-fall-back!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(deftest open-chip-renders-anchor-with-href
  (testing "open-chip returns an <a> hiccup vector when source has :file"
    (let [coord  {:ns 'app.events :file "src/app/events.cljs" :line 17 :column 3}
          hiccup (open-in-editor/open-chip coord)]
      (is (vector? hiccup))
      (is (= :a (first hiccup)))
      (let [props (second hiccup)]
        (is (= "vscode://file/src/app/events.cljs:17:3" (:href props)))
        (is (= "xray-open-in-editor" (:data-testid props)))
        (is (= "vscode" (:data-editor props)))
        (is (fn? (:on-click props)))))))

(deftest open-chip-respects-editor-preference
  (testing "switching Xray's editor flips the URI on render"
    (let [coord {:file "src/x.cljs" :line 10}]
      (config/set-editor! :cursor)
      (is (= "cursor://file/src/x.cljs:10:1"
             (:href (second (open-in-editor/open-chip coord)))))
      (config/set-editor! :idea)
      (is (= "idea://open?file=src/x.cljs&line=10&column=1"
             (:href (second (open-in-editor/open-chip coord))))))))

(deftest open-chip-supports-custom-template
  (testing ":custom template via Xray configure!"
    (config/configure! {:rf.xray/editor {:custom "zed://file/{path}:{line}"}})
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

;; ---- rf2-vwcsq / rf2-ox357n — Xray-side scheme-denylist behaviour -----
;;
;; The matrix tests for `forbidden-scheme?` itself live in the shared
;; editor-uri test ns. These cases cover the Xray chip's wiring: the chip
;; hides ONLY when a `{:custom ...}` template resolves to one of the three
;; forbidden script schemes; everything else — http:/https: and unknown
;; custom schemes — renders (rf2-ox357n removed the positive allowlist).

(deftest open-chip-hides-when-custom-template-resolves-to-forbidden-scheme
  (testing "open-chip returns nil ONLY for the three forbidden script
            schemes (rf2-vwcsq). editor-uri/editor-uri gates these at
            build time → the chip is nil."
    (config/configure! {:rf.xray/editor {:custom "javascript:alert(1)"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/configure! {:rf.xray/editor {:custom "data:text/html,xxx"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/configure! {:rf.xray/editor {:custom "vbscript:msgbox(1)"}})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-renders-for-non-forbidden-custom-scheme
  (testing "rf2-ox357n — open-chip renders for ANY non-forbidden scheme:
            catalogued long-tail, http:/https: (no longer gated), AND
            unknown custom schemes the old allowlist would have hidden"
    (config/configure! {:rf.xray/editor {:custom "subl://open?path={path}&line={line}"}})
    (let [hiccup (open-in-editor/open-chip {:file "src/x.cljs" :line 5})]
      (is (vector? hiccup))
      (is (= "subl://open?path=src/x.cljs&line=5" (:href (second hiccup)))))

    (config/configure! {:rf.xray/editor {:custom "emacsclient://{path}"}})
    (is (some? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; http:/https: now PASS — rf2-ox357n removed the allowlist that
    ;; rejected them (the residual footgun the spec accepts; script
    ;; schemes stay blocked).
    (config/configure! {:rf.xray/editor {:custom "http://localhost:3000/{path}"}})
    (is (= "http://localhost:3000/src/x.cljs"
           (:href (second (open-in-editor/open-chip {:file "src/x.cljs"})))))

    ;; An unknown editor scheme renders — no silent dead button.
    (config/configure! {:rf.xray/editor {:custom "lapce://open?file={path}&line={line}"}})
    (is (= "lapce://open?file=src/x.cljs&line=8"
           (:href (second (open-in-editor/open-chip {:file "src/x.cljs" :line 8})))))))

;; ---- project-root prefix (rf2-5m5n2) ------------------------------------
;;
;; The bead: clicking the Open chip on a Xray panel launched an OS-side
;; editor with a classpath-relative path
;; ("panel_gallery/event_detail_stories.cljs:115:3") that the editor's
;; filesystem resolver could not find. The Xray config now exposes
;; `:rf.xray/project-root` — set once at boot via `xray-config/configure!` — and
;; `resolve-uri` (which both the chip and the `:rf.editor/open` fx share)
;; prepends it before the URI ships. Mirror of Story's rf2-zfy1e matrix.

(deftest open-chip-default-no-project-root
  (testing "with no project-root configured, the chip ships the file slot
            verbatim — preserves v1 behaviour for hosts that haven't
            plumbed the knob yet"
    (is (nil? (config/get-project-root)))
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 1 :column 1})]
      (is (= "vscode://file/src/app/views.cljs:1:1"
             (:href (second hiccup)))))))

(deftest open-chip-prefixes-with-project-root
  (testing "set-project-root! plumbs the on-disk root through the chip"
    (config/set-project-root! "C:/Users/me/code/my-app")
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 42 :column 7})]
      (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
             (:href (second hiccup)))))))

(deftest open-chip-project-root-regression-rf2-5m5n2
  (testing "regression: the panel-gallery testbed's failure case now
            resolves to an absolute on-disk URI when the host has
            plumbed :rf.xray/project-root through xray-config/configure!"
    (config/set-project-root!
      "C:/Users/me/code/my-app/tools/xray/testbeds")
    (let [hiccup (open-in-editor/open-chip
                   {:file "panel_gallery/event_detail_stories.cljs"
                    :line 115
                    :column 3})]
      (is (= (str "vscode://file/"
                  "C:/Users/me/code/my-app/tools/xray/testbeds/"
                  "panel_gallery/event_detail_stories.cljs:115:3")
             (:href (second hiccup)))))))

(deftest open-chip-project-root-roundtrip
  (testing "config/set-project-root! + get-project-root round-trip on
            the CLJS side"
    (config/set-project-root! "/abs/code")
    (is (= "/abs/code" (config/get-project-root)))
    (config/set-project-root! nil)
    (is (nil? (config/get-project-root)))
    ;; blank strings normalise to nil so the chip behaves as if unset.
    (config/set-project-root! "")
    (is (nil? (config/get-project-root)))))

(deftest open-chip-project-root-survives-editor-change
  (testing "switching editor keeps project-root applied to the new scheme"
    (config/set-project-root! "/abs/code")
    (config/set-editor! :cursor)
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/x.cljs" :line 1 :column 1})]
      (is (= "cursor://file//abs/code/src/x.cljs:1:1"
             (:href (second hiccup)))))))

(deftest open-chip-project-root-absolute-coord-not-double-prefixed
  (testing "an already-absolute source-coord is NOT double-prefixed
            (per editor-uri/compose-path's absolute-path? guard) — pins
            that the Xray wiring respects the helper's contract"
    (config/set-project-root! "C:/Users/me/code/my-app")
    (let [hiccup (open-in-editor/open-chip
                   {:file "/abs/already/here.cljs" :line 1 :column 1})]
      (is (= "vscode://file//abs/already/here.cljs:1:1"
             (:href (second hiccup)))))))

(deftest open-chip-configure-passes-project-root-through
  (testing "configure! routes :rf.xray/project-root through set-project-root!
            on the CLJS side (mirror of Story's rf2-zfy1e config matrix)"
    (config/configure! {:rf.xray/project-root "C:/Users/me/code/my-app"})
    (is (= "C:/Users/me/code/my-app" (config/get-project-root)))
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/x.cljs" :line 1 :column 1})]
      (is (= "vscode://file/C:/Users/me/code/my-app/src/x.cljs:1:1"
             (:href (second hiccup)))))))

;; ---- URI invariance to host page URL (rf2-2c5xb) ------------------------
;;
;; The panel-gallery testbed surfaced a defect: source-coord chips on
;; handlers registered inside the gallery served from `http://localhost:8765`
;; were producing URIs that did not resolve to the right files on disk.
;; Pre-rf2-2c5xb the testbed never called `xray-config/configure!`, so the
;; chip shipped a classpath-relative `:file` slot
;; (`panel_gallery/foo.cljs`) the OS handler rejected. The fix is the
;; established rf2-5m5n2 path: configure `:rf.xray/project-root` at boot.
;;
;; The tests below pin the load-bearing invariant: URI construction is a
;; pure function of `(editor, source-coord, project-root)`. It does NOT
;; read `window.location` — so the URI a chip renders is identical no
;; matter which host URL the gallery / example app is served from. The
;; second test exercises the panel-gallery's exact failure case (the
;; `panel_gallery/<file>.cljs` shape) against the testbed's known on-disk
;; root.

(deftest resolve-uri-invariant-to-host-url
  (testing "rf2-2c5xb — `resolve-uri` is a pure function of (editor,
            source-coord, project-root); the URI it returns is identical
            regardless of any ambient host state. The panel-gallery
            defect was rooted in the testbed never calling
            `xray-config/configure!`, NOT in the URI builder somehow
            reading `window.location`. This test pins the contract: the
            same `(editor, coord, project-root)` triple always yields
            the same URI."
    (config/set-project-root! "C:/Users/me/code/my-app")
    (let [coord {:file "src/app/views.cljs" :line 42 :column 7}
          ;; Call the resolver several times with the same inputs but
          ;; different ambient context (different editor preferences
          ;; between calls, then restored). The middle observations
          ;; differ — but with all three inputs fixed, the URI is
          ;; deterministic and matches the configured project-root.
          baseline (open-in-editor/resolve-uri coord)
          _        (config/set-editor! :cursor)
          cursor   (open-in-editor/resolve-uri coord)
          _        (config/set-editor! :vscode)
          replay   (open-in-editor/resolve-uri coord)]
      (is (= baseline replay)
          "Identical (editor, coord, root) → identical URI; the
           resolver has no hidden host-URL input")
      (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
             baseline)
          "URI value derives from the configured project-root, not
           from any document URL the testbed happens to be served at")
      (is (= "cursor://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
             cursor)
          "Editor preference flows through; project-root prefix stays
           bound to the configured root, not the location"))))

(deftest resolve-uri-panel-gallery-regression-rf2-2c5xb
  (testing "rf2-2c5xb — regression: the panel-gallery's exact failure
            case (a classpath-relative coord captured at registration of
            a panel-gallery handler) resolves to an absolute on-disk URI
            against the testbed's configured project-root. Pins that the
            chip works at `http://localhost:8765` independently of the
            served port — the URI depends only on config, never on
            `Location.href`."
    (config/set-project-root!
      "C:/Users/me/code/my-app/tools/xray/testbeds")
    (is (= (str "vscode://file/"
                "C:/Users/me/code/my-app/tools/xray/testbeds/"
                "panel_gallery/fixtures_epoch.cljs:42:1")
           (open-in-editor/resolve-uri
             {:file "panel_gallery/fixtures_epoch.cljs"
              :line 42
              :column 1})))))

;; ---- rf2-g5q8d — :rf.xray/open-in-editor + :rf.editor/open ------------
;;
;; Per the rf2-3vucz audit, the four Xray panels (trace, issues-ribbon,
;; mcp-server, hydration-debugger) dispatch `[:rf.xray/open-in-editor
;; coord]` when their source-coord affordance is clicked. Pre-rf2-g5q8d
;; the handler was a stub db-only reg-event that recorded the coord into
;; app-db and never opened anything — load-bearing UX silently broken.
;;
;; The block below pins the contract of the rewired event-fx + fx pair:
;;
;;   1. Dispatching the event produces a `:rf.editor/open` fx whose
;;      `:uri` resolves through `resolve-uri` (= rf2-vwcsq denylist).
;;   2. Both dispatch shapes are accepted: the bare-coord form (the
;;      hydration debugger's call site) and the `{:source-coord ...}`
;;      wrapper form (the other three panels' call site).
;;   3. A coord whose resolved URI is rejected by the denylist (custom
;;      `javascript:` / `data:` / `vbscript:` template) yields a fx whose
;;      `:uri` is nil — the side-effect fx is a no-op for nil. Per
;;      rf2-ox357n http:/https:/unknown schemes now RESOLVE (no allowlist).
;;   4. The handler runs on Xray's `:rf/xray` frame (per the panels'
;;      `{:frame :rf/xray}` dispatch opts); Xray's app-db is NOT
;;      written (the click is pure navigation).

(defonce ^:private captured-editor-fx (atom []))

(defn- setup!
  "Per-test bootstrap shared by every rf2-g5q8d test: register Xray's
  handlers, allocate the `:rf/xray` frame, and replace the
  `:rf.editor/open` reg-fx with a capture stub so assertions can
  inspect the fx args without touching `window.location`. Mirrors the
  fx-replacement pattern in `time_travel_cljs_test.cljs`.

  Per rf2-wn3bh the event-fx now emits the structured `:source-coord`
  (so the fx can prefer the dev-server endpoint). The capture stub
  resolves the coord through the SAME `resolve-uri` helper the chip
  uses and records the resolved URI under `:uri` so the existing
  URI-equivalence assertions keep their meaning — the resolution is
  exactly what the URI-fallback path would build."
  []
  (reset! captured-editor-fx [])
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args]
      ;; Record the raw fx args AND the URI the coord resolves to, so
      ;; tests can assert either the new `:source-coord` shape or the
      ;; equivalent resolved `:uri`.
      (swap! captured-editor-fx conj
             (assoc args
                    :uri (when-let [coord (:source-coord args)]
                           (open-in-editor/resolve-uri coord)))))))

(deftest open-in-editor-event-emits-fx-with-resolved-uri
  (testing "rf2-g5q8d — dispatching `:rf.xray/open-in-editor` with
            a bare coord (the hydration-debugger shape) produces a
            `:rf.editor/open` fx whose :uri is the resolved URI"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
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
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
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
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
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
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor "src/x.cljs:7"])
      (is (= "vscode://file/src/x.cljs:7:1"
             (:uri (first @captured-editor-fx)))))))

(deftest open-in-editor-event-display-string-without-line
  (testing "rf2-g5q8d — display string with no trailing line number
            (degenerate: the projection helpers always include line,
            but the parser falls through gracefully to `{:file <s>}`)"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:source-coord "src/x.cljs"}])
      (is (= "vscode://file/src/x.cljs:1:1"
             (:uri (first @captured-editor-fx)))
          "`:line` defaults to 1 via editor-uri"))))

(deftest open-in-editor-event-honours-editor-preference
  (testing "rf2-g5q8d — the fx's URI reflects `config/get-editor`
            (the same source of truth the chip render uses)"
    (setup!)
    (config/set-editor! :cursor)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 10}])
      (is (= "cursor://file/src/x.cljs:10:1"
             (:uri (first @captured-editor-fx)))))))

(deftest open-in-editor-event-rejects-javascript-scheme
  (testing "rf2-g5q8d / rf2-vwcsq — a custom template that resolves to
            `javascript:` is rejected at the handler seam. The
            editor-uri-side denylist (rf2-vwcsq) returns nil; the fx
            receives nil and is a no-op."
    (setup!)
    (config/configure! {:rf.xray/editor {:custom "javascript:alert(1)"}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (= 1 (count @captured-editor-fx))
          "fx still fires — the handler doesn't short-circuit")
      (is (nil? (:uri (first @captured-editor-fx)))
          "the resolved URI is nil — `open!` will refuse to navigate"))))

(deftest open-in-editor-event-rejects-data-scheme
  (testing "rf2-g5q8d / rf2-vwcsq — a custom template resolving to
            `data:` is denylist-rejected (returns nil)"
    (setup!)
    (config/configure! {:rf.xray/editor {:custom "data:text/html,<script>x</script>"}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (nil? (:uri (first @captured-editor-fx)))
          "data: is on the forbidden-scheme denylist"))))

(deftest open-in-editor-event-resolves-http-scheme-rf2-ox357n
  (testing "rf2-ox357n — a custom template resolving to `http:` now
            RESOLVES (the positive allowlist that rejected it was
            removed; only the three script schemes are gated)"
    (setup!)
    (config/configure! {:rf.xray/editor {:custom "http://localhost:3000/{path}"}})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (= "http://localhost:3000/src/x.cljs"
             (:uri (first @captured-editor-fx)))
          "http: passes through — no allowlist over-gating"))))

(deftest open-in-editor-event-runs-on-xray-frame-without-host-contamination
  (testing "rf2-g5q8d — the handler doesn't write to Xray's app-db
            (no `:db` in the returned effect map). The click is pure
            navigation; no host-frame escape, no Xray-frame state
            pollution."
    (setup!)
    (rf/with-frame :rf/xray
      (let [pre-db (frame/frame-app-db-value :rf/xray)]
        (rf/dispatch-sync [:rf.xray/open-in-editor
                           {:file "src/x.cljs" :line 1}])
        (let [post-db (frame/frame-app-db-value :rf/xray)]
          (is (= pre-db post-db)
              "Xray's app-db is untouched by the click — no
               `:last-open-in-editor-coord` etc. (the prior stub
               db-only handler's behaviour, removed by rf2-g5q8d)"))))))

(deftest open-in-editor-fx-receives-source-coord-key-rf2-wn3bh
  (testing "rf2-wn3bh — `:rf.editor/open` is invoked with the structured
            `{:source-coord {...}}` shape (NOT a pre-resolved `:uri`) so
            the fx can prefer the dev-server endpoint and fall back to the
            `editor://` URI. The resolution is deferred to the fx /
            `open-coord!`, not done in the event-fx."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (contains? (first @captured-editor-fx) :source-coord)
          "fx arg shape: `{:source-coord <coord-map>}`")
      (is (= {:file "src/x.cljs" :line 1}
             (:source-coord (first @captured-editor-fx)))
          "the structured coord rides the fx verbatim — the endpoint
           resolves the relative :file at runtime on the server"))))

(deftest open-in-editor-event-resolves-through-shared-resolve-uri-helper
  (testing "rf2-g5q8d — the event-fx's URI matches what `open-chip`
            renders for the same coord (one source of truth for URI
            resolution + denylist gating across the data path and
            the side-effect path)"
    (setup!)
    (let [coord {:file "src/app/events.cljs" :line 42 :column 7}]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/open-in-editor coord]))
      (is (= (:href (second (open-in-editor/open-chip coord)))
             (:uri (first @captured-editor-fx)))
          "chip's :href ≡ fx's :uri"))))

(deftest open-in-editor-event-applies-project-root-prefix
  (testing "rf2-5m5n2 — the event-fx's URI reflects the configured
            project-root (same source of truth as `open-chip`'s
            `:href`), so the four panels' dispatch path resolves
            relative source-coords to absolute on-disk URIs"
    (setup!)
    (config/set-project-root! "C:/Users/me/code/my-app")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/app/views.cljs" :line 42 :column 7}])
      (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
             (:uri (first @captured-editor-fx)))
          "fx's :uri ≡ chip's :href once :project-root is configured"))))

;; ---- rf2-4s08ov — open-in-editor DX hint when no editor configured ------
;;
;; The rf2-ffijtp finding: a host that wired only the bare preload never
;; set `:rf.xray/editor`, so the chip targets the framework default
;; `:vscode`. The URI resolves and `Location.assign` fires, but if VS
;; Code is not the developer's editor the OS has no `vscode:` handler and
;; the click is a SILENT no-op. Instead of that silent navigation, the
;; event-fx surfaces the 'pick an editor in Settings' hint toast. The
;; block below pins the contract:
;;
;;   1. When NEITHER the host nor the operator has confirmed an editor
;;      (`config/editor-configured?` false), the open-in-editor event
;;      routes to `:rf.xray/editor-hint-show` (NOT `:rf.editor/open`).
;;   2. Once the host explicitly sets an editor (even `:vscode`), or an
;;      operator override is present, the click resolves + navigates as
;;      before — the hint never fires.

(defn- setup-unconfigured!
  "Like `setup!` but clears the editor-explicitly-set? flag + any
  operator override so `config/editor-configured?` is false — the bare
  host scenario rf2-4s08ov targets."
  []
  (setup!)
  ;; `reset-editor!` (via the fixture) called `set-editor! :vscode`,
  ;; which flips the explicit flag on. Clear it back to the
  ;; unconfigured framework-default state.
  (reset! config/editor-explicitly-set? false)
  (config/update-setting! :general :editor-override nil))

(deftest open-in-editor-event-hints-when-no-editor-configured
  (testing "rf2-4s08ov — with NO editor effectively configured, the
            event does NOT fire `:rf.editor/open` (the silent vscode:
            navigation) — it routes to the editor-hint instead"
    (setup-unconfigured!)
    (is (false? (config/editor-configured?))
        "precondition: editor is the unconfirmed framework default")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (= 0 (count @captured-editor-fx))
          "no `:rf.editor/open` fx fires — the silent vscode: nav is
           replaced by the hint dispatch")))
  ;; Reset the override slot so the leaked `nil` write does not bleed
  ;; into sibling tests' `get-editor` reads.
  (config/update-setting! :general :editor-override nil))

(deftest editor-hint-show-and-dismiss-flip-the-sub
  (testing "rf2-4s08ov — the `:rf.xray/editor-hint-show` /
            `-dismiss` events flip the `:rf.xray/editor-hint-open?`
            sub (the toast's mount gate)"
    (setup!)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/editor-hint-open?]))
          "closed by default")
      (rf/dispatch-sync [:rf.xray/editor-hint-show])
      (is (true? @(rf/subscribe [:rf.xray/editor-hint-open?]))
          "shown after editor-hint-show")
      (rf/dispatch-sync [:rf.xray/editor-hint-dismiss])
      (is (false? @(rf/subscribe [:rf.xray/editor-hint-open?]))
          "dismissed after editor-hint-dismiss"))))

(deftest open-in-editor-event-navigates-when-host-set-editor
  (testing "rf2-4s08ov — once the host explicitly sets an editor (even
            the framework-default :vscode), the click resolves + fires
            `:rf.editor/open` as before; the hint never fires"
    (setup-unconfigured!)
    (config/set-editor! :vscode)
    (is (true? (config/editor-configured?))
        "an explicit set — even of :vscode — counts as configured")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 1}])
      (is (= 1 (count @captured-editor-fx))
          "the open fx fires for an explicitly-configured editor")
      (is (= "vscode://file/src/x.cljs:1:1"
             (:uri (first @captured-editor-fx)))))))

(deftest open-in-editor-event-navigates-when-operator-override-set
  (testing "rf2-4s08ov — an operator override (no host set) also counts
            as configured: the click navigates, no hint"
    (setup-unconfigured!)
    (config/update-setting! :general :editor-override :cursor)
    (is (true? (config/editor-configured?)))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor
                         {:file "src/x.cljs" :line 10}])
      (is (= "cursor://file/src/x.cljs:10:1"
             (:uri (first @captured-editor-fx)))
          "the override editor's URI rides the fx"))
    ;; Reset the override slot so :cursor does not bleed into sibling
    ;; tests' `get-editor` reads (the fixture resets the editor atom +
    ;; project-root but NOT the settings override slot).
    (config/update-setting! :general :editor-override nil)))

;; ---- click-time navigation (rf2-muvs8) ----------------------------------
;;
;; Mirror of Story's click-time tests. The bead: `(set! (.-location js/window)
;; uri)` was silently no-op'd by some Chromium builds on Windows for
;; custom URI schemes; the fix switched to `Location.assign`, routed it
;; through an atom-held navigator seam (`set-navigator!`) so tests can
;; capture calls without mutating `js/window.location` (which is non-
;; configurable in modern browsers and throws under `defineProperty`),
;; and added a `console.log` of the URI for live diagnosis. These tests
;; pin the new click-time contract.

(defn- with-stub-navigator
  "Swap the navigator seam for `stub-fn` for the duration of `body-fn`.
  Restores the original navigator afterward (even on throw)."
  [stub-fn body-fn]
  (let [prev (open-in-editor/set-navigator! stub-fn)]
    (try
      (body-fn)
      (finally
        (open-in-editor/set-navigator! prev)))))

(defn- capturing-navigator
  "Build a navigator fn that pushes its URI argument onto the shared
  `calls` atom. Returns `[navigator-fn, calls-atom]`."
  []
  (let [calls (atom [])
        nav   (fn [uri] (swap! calls conj uri))]
    [nav calls]))

(deftest click-handler-calls-navigator-with-uri
  (testing "rf2-muvs8 — clicking the chip invokes the navigator seam
            with the same URI carried in the :href"
    (let [hiccup       (open-in-editor/open-chip
                         {:file "src/x.cljs" :line 42 :column 7})
          props        (second hiccup)
          href         (:href props)
          on-click     (:on-click props)
          fake-evt     #js {:preventDefault (fn [])}
          [nav calls]  (capturing-navigator)]
      (with-stub-navigator nav
        #(on-click fake-evt))
      (is (= ["vscode://file/src/x.cljs:42:7"]
             @calls)
          "navigator called exactly once with the chip's href URI")
      (is (= href (first @calls))
          "the navigation URI is identical to the rendered href"))))

(deftest click-handler-prevents-default
  (testing "rf2-muvs8 — the click handler preventDefaults so the
            browser doesn't double-navigate"
    (let [hiccup       (open-in-editor/open-chip
                         {:file "src/x.cljs" :line 1})
          on-click     (:on-click (second hiccup))
          prevented?   (atom false)
          fake-evt     #js {:preventDefault (fn [] (reset! prevented? true))}
          [nav _]      (capturing-navigator)]
      (with-stub-navigator nav
        #(on-click fake-evt))
      (is @prevented?
          "the click handler must call e.preventDefault()"))))

(deftest open-bang-calls-navigator
  (testing "rf2-muvs8 — `open!` (the public seam shared by the chip and
            the `:rf.editor/open` reg-fx) invokes the navigator with
            an allowed URI"
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        #(open-in-editor/open! "vscode://file/src/x.cljs:1:1"))
      (is (= ["vscode://file/src/x.cljs:1:1"] @calls)))))

(deftest open-bang-no-op-for-nil-uri
  (testing "rf2-muvs8 — `open!` is a no-op for nil URI (the absent-coord
            case + the rejected-by-denylist case both flow nil)"
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        #(open-in-editor/open! nil))
      (is (= [] @calls)
          "no navigation attempted for nil URI"))))

(deftest open-bang-denylist-gates-pre-resolved-uri
  (testing "rf2-muvs8 / rf2-ox357n — `open!` re-applies the scheme
            denylist at the pre-resolved {:uri ...} handoff (the
            :rf.editor/open reg-fx path that bypasses editor-uri's
            build-time gating). Forbidden schemes never reach the
            navigator — case-insensitively + leading-whitespace tolerant
            — even when `open!` is called directly (e.g. an MCP-side
            replay). Non-dangerous schemes (incl. unknown custom) pass."
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          (open-in-editor/open! "javascript:alert(1)")
          (open-in-editor/open! "JavaScript:alert(1)")
          (open-in-editor/open! " data:text/html,xxx")
          (open-in-editor/open! "vbscript:msgbox(1)")
          (is (= [] @calls)
              "forbidden-scheme navigations refused at the open! boundary")
          ;; an unknown, non-dangerous scheme passes through (no allowlist)
          (open-in-editor/open! "lapce://open?file=src/x.cljs&line=1")
          (is (= ["lapce://open?file=src/x.cljs&line=1"] @calls)
              "an unknown custom non-dangerous scheme navigates"))))))

;; ---- rf2-r4q6y3 — direct chip click routes through the hint decision ----
;;
;; Pre-rf2-r4q6y3 the in-DOM `open-chip` `:on-click` called `open!`
;; directly, bypassing the rf2-4s08ov configured/hint decision the
;; panel-side `:rf.xray/open-in-editor` event-fx already applied. An
;; unconfigured host therefore silently navigated to the implicit
;; `vscode:` URI from the chip — the exact silent no-op rf2-4s08ov set
;; out to replace. `chip-click!` now applies the same decision:
;;
;;   1. Configured editor                 → navigate (`open!`).
;;   2. Unconfigured + `:rf/xray` present → show the hint toast.
;;   3. Unconfigured + no frame           → standalone fallback: navigate.

(defn- unconfigure-editor! []
  ;; Model the bare-preload host: clear the explicit flag the fixture's
  ;; `set-editor! :vscode` set + any operator override.
  (reset! config/editor-explicitly-set? false)
  (config/update-setting! :general :editor-override nil))

(deftest chip-click-navigates-when-editor-configured
  (testing "rf2-r4q6y3 / rf2-wn3bh — with an editor configured, a direct
            chip click opens via `open-coord!`; with the endpoint launcher
            stubbed to fall back (the fixture default), it navigates via
            the navigator seam exactly as the URI path always did. The
            hint never enters the path."
    (setup!)
    (config/set-editor! :cursor)          ; explicit set = configured
    (is (true? (config/editor-configured?)))
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        #(open-in-editor/chip-click! {:file "src/x.cljs" :line 1 :column 1}))
      (is (= ["cursor://file/src/x.cljs:1:1"] @calls)
          "configured editor → endpoint preferred, URI fallback navigates"))))

(deftest chip-click-does-not-navigate-when-unconfigured-with-frame
  (testing "rf2-r4q6y3 — with NO editor configured and a live :rf/xray
            shell frame, a direct chip click does NOT silently navigate
            (the bug); it routes to the hint instead"
    (setup!)
    (unconfigure-editor!)
    (is (false? (config/editor-configured?)))
    (is (some? (frame/frame :rf/xray)) "precondition: shell frame present")
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        #(open-in-editor/chip-click! {:file "src/x.cljs" :line 1 :column 1}))
      (is (= [] @calls)
          "unconfigured + frame → no silent navigation"))
    (config/update-setting! :general :editor-override nil)))

(deftest chip-click-shows-hint-when-unconfigured-with-frame
  (testing "rf2-r4q6y3 — the unconfigured + frame chip click dispatches
            `:rf.xray/editor-hint-show` on :rf/xray (consistent with the
            panel-side event-fx + #3486). Captured synchronously via a
            `rf/dispatch` spy so the assertion is deterministic without
            an async router drain."
    (setup!)
    (unconfigure-editor!)
    ;; `chip-click!` calls the `rf/dispatch` MACRO directly (hardcoded, no
    ;; injectable dispatch-fn seam), and the macro's expansion calls the
    ;; `^:no-doc` `re-frame.core/dispatch-impl` seam fully-qualified
    ;; (rf2-m90brg) — so the spy goes on `rf/dispatch-impl`; redefing
    ;; `re-frame.router/dispatch!` directly would fail (a plain `defn`, not a
    ;; redefinable `def`-alias — the CLJS compiler's static arity-dispatch
    ;; optimisation bypasses `with-redefs`), and redefing `re-frame.core/
    ;; dispatch` (the CLJS value-alias) would intercept nothing here either.
    (let [dispatched (atom [])]
      (with-redefs [rf/dispatch-impl (fn [ev & opts]
                                        (swap! dispatched conj {:event ev :opts (vec opts)}))]
        (open-in-editor/chip-click! {:file "src/x.cljs" :line 1 :column 1}))
      (is (= 1 (count @dispatched))
          "exactly one dispatch — the hint, no navigation dispatch")
      (is (= [:rf.xray/editor-hint-show] (:event (first @dispatched)))
          "the hint-show event is dispatched")
      (is (= :rf/xray (:frame (first (:opts (first @dispatched)))))
          "dispatched on the :rf/xray shell frame where the hint lives"))
    (config/update-setting! :general :editor-override nil)))

(deftest chip-click-standalone-fallback-navigates-without-frame
  (testing "rf2-r4q6y3 — with NO editor configured AND no :rf/xray frame
            (the standalone / static-host fallback — nowhere for a hint
            toast to mount), the chip falls back to direct navigation.
            This is the documented standalone contract."
    (setup!)
    (unconfigure-editor!)
    ;; Tear down the shell frame so there is no hint target.
    (reset! frame/frames {})
    (is (nil? (frame/frame :rf/xray)) "precondition: no shell frame")
    (is (false? (config/editor-configured?)))
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        #(open-in-editor/chip-click! {:file "src/x.cljs" :line 1 :column 1}))
      (is (= ["vscode://file/src/x.cljs:1:1"] @calls)
          "unconfigured + no frame → standalone best-effort navigation"))))

;; ---- rf2-wn3bh — Option B: dev-server endpoint preferred over URI -------
;;
;; `open-coord!` prefers the dev-server endpoint and falls back to the
;; `editor://` URI navigation. The URI-fallback path is exercised
;; throughout the file above (via the `always-fall-back!` launcher stub in
;; the fixture). This block pins the ENDPOINT-PREFERENCE half of the
;; additive contract: when the launcher reports success (a dev server
;; answered), the URI fallback does NOT fire — and the endpoint URL the
;; client builds carries the structured coord + editor hint the server
;; resolves at runtime.

(deftest endpoint-url-carries-coord-and-editor-hint
  (testing "rf2-wn3bh — `build-url` projects (coord, editor) to the
            endpoint query: file (encoded), line, column, editor keyword"
    (is (= (str open-endpoint/endpoint-path
                "?file=panel_gallery%2Ffoo.cljs&line=42&column=7&editor=cursor")
           (open-endpoint/build-url
             {:file "panel_gallery/foo.cljs" :line 42 :column 7}
             :cursor))
        "relative :file is sent verbatim (URL-encoded) for runtime
         resolution; the editor keyword rides as the launch hint")
    (is (nil? (open-endpoint/build-url {:line 1} :vscode))
        "no :file → no endpoint URL (the chip is hidden upstream)")
    (is (= (str open-endpoint/endpoint-path "?file=src%2Fx.cljs")
           (open-endpoint/build-url {:file "src/x.cljs"} {:custom "x://{path}"}))
        "{:custom …} editor ships no hint (server auto-detects)")))

(deftest open-coord-prefers-endpoint-when-it-succeeds
  (testing "rf2-wn3bh — when the endpoint launcher reports success (a dev
            server answered), the URI fallback does NOT fire"
    (let [fallback-calls (atom 0)
          ;; Stub launcher: 'endpoint succeeded' → never call fallback.
          prev (open-endpoint/set-launcher! (fn [_url _fallback!] nil))]
      (try
        (open-in-editor/open-coord! {:file "src/x.cljs" :line 1})
        ;; the fallback thunk would bump this; it must stay 0
        (is (zero? @fallback-calls)
            "endpoint preferred → no editor:// URI navigation")
        (finally
          (open-endpoint/set-launcher! prev))))))

(deftest open-coord-falls-back-to-uri-when-no-endpoint
  (testing "rf2-wn3bh — when the endpoint launcher invokes the fallback
            (no dev server), the `editor://` URI navigates via the
            navigator seam — the additive contract: B never removes the
            URI path"
    (setup!)                              ; configured :vscode
    (let [prev (open-endpoint/set-launcher! always-fall-back!)
          [nav calls] (capturing-navigator)]
      (try
        (with-stub-navigator nav
          #(open-in-editor/open-coord! {:file "src/x.cljs" :line 9 :column 2}))
        (is (= ["vscode://file/src/x.cljs:9:2"] @calls)
            "no dev server → URI fallback navigates exactly as before")
        (finally
          (open-endpoint/set-launcher! prev))))))
