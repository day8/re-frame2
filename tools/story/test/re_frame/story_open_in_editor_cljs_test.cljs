(ns re-frame.story-open-in-editor-cljs-test
  "CLJS tests for the Story-side 'Open in editor' chip (rf2-evgf5).

  The pure URI logic lives in `re-frame.source-coords.editor-uri` and is
  matrix-tested on the JVM. This file covers the Story-specific glue:

  - `config/set-editor!` round-trips on the CLJS side.
  - `open-chip` returns nil when the source-coord lacks `:file`.
  - `open-chip` renders an `<a>` hiccup tag with the current editor's
    URI when the coord carries `:file`.
  - The chip carries the `data-test` hook for the e2e suite.
  - `open-chip-for-variant` reads `:source` off the variant body.
  - rf2-r2un8 — `:rf.story/open-in-editor` reg-event + `:rf.editor/open`
    reg-fx produce a resolved URI through the same denylist seam the
    chip uses (Xray-parity port).
  - rf2-ox357n — the positive allowlist was removed; only the
    `javascript:` / `data:` / `vbscript:` denylist gates the chip now."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.source-coords.open-endpoint :as open-endpoint]
            [re-frame.story.config :as config]
            [re-frame.story.ui.open-in-editor :as open-in-editor])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ---- Option B endpoint seam (rf2-wn3bh) ---------------------------------
;;
;; `open-coord!` (the click launcher) PREFERS the dev-server endpoint and
;; FALLS BACK to the `editor://` URI navigation. To keep the URI / navigator
;; assertions below deterministic (no real `fetch`), the fixture swaps the
;; endpoint launcher for a synchronous stub that always invokes the fallback
;; — exercising exactly the URI path these tests pin. The endpoint-preference
;; path is covered in its own rf2-wn3bh block at the bottom of this file.

(defn- always-fall-back!
  "Stub endpoint launcher: ignore the URL, invoke the fallback synchronously.
  Mirrors the 'no dev server present' runtime case."
  [_url fallback!]
  (fallback!))

;; ---- fixtures ------------------------------------------------------------

(defn reset-editor! []
  (config/set-editor! :vscode)
  (config/set-project-root! nil)
  ;; rf2-wn3bh — pin the endpoint launcher to the synchronous fallback stub
  ;; so the URI / navigator assertions below exercise the deterministic
  ;; fallback path.
  (open-endpoint/set-launcher! always-fall-back!))

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

;; ---- open-source-coord! (rf2-h0jc0) --------------------------------------
;;
;; The element-inspector uses this helper to resolve a source-coord →
;; URI through Story config and hand it to `open!`. One launcher across
;; the chip + the inspector — same denylist gate, same navigator seam.

(deftest open-source-coord!-fires-navigator-with-resolved-uri
  (testing "open-source-coord! resolves through Story config + the
            navigator seam — same path the chip uses"
    (let [calls (atom [])
          nav   (fn [uri] (swap! calls conj uri))
          prev  (open-in-editor/set-navigator! nav)]
      (try
        (open-in-editor/open-source-coord!
          {:file "src/app.cljs" :line 17 :column 3})
        (is (= ["vscode://file/src/app.cljs:17:3"] @calls)
            "navigator invoked once with the resolved vscode:// URI")
        (finally
          (open-in-editor/set-navigator! prev))))))

(deftest open-source-coord!-no-op-without-file
  (testing "open-source-coord! returns false + no-ops when source-coord
            lacks :file"
    (let [calls (atom [])
          nav   (fn [uri] (swap! calls conj uri))
          prev  (open-in-editor/set-navigator! nav)]
      (try
        (is (false? (open-in-editor/open-source-coord! nil)))
        (is (false? (open-in-editor/open-source-coord! {:line 10})))
        (is (= [] @calls)
            "no navigation attempted when :file is absent")
        (finally
          (open-in-editor/set-navigator! prev))))))

(deftest open-source-coord!-respects-editor-preference
  (testing "the URI shipped by open-source-coord! reflects the live
            editor + project-root config — same source of truth the
            chip's :href reads"
    (config/set-editor! :cursor)
    (config/set-project-root! "C:/Users/me/code/my-app")
    (let [calls (atom [])
          nav   (fn [uri] (swap! calls conj uri))
          prev  (open-in-editor/set-navigator! nav)]
      (try
        (open-in-editor/open-source-coord!
          {:file "src/app.cljs" :line 17 :column 3})
        (is (= ["cursor://file/C:/Users/me/code/my-app/src/app.cljs:17:3"]
               @calls))
        (finally
          (open-in-editor/set-navigator! prev))))))

(deftest open!-denylist-gates-pre-resolved-uri
  (testing "rf2-ox357n — `open!` re-applies the scheme denylist at the
            pre-resolved {:uri ...} handoff (the :rf.editor/open reg-fx
            path that bypasses editor-uri's build-time gating). Forbidden
            schemes never reach the navigator; non-dangerous schemes
            (incl. unknown custom) navigate."
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          ;; forbidden script schemes are refused at the click-time seam,
          ;; case-insensitively + leading-whitespace tolerant
          (open-in-editor/open! "javascript:alert(1)")
          (open-in-editor/open! "JavaScript:alert(1)")
          (open-in-editor/open! " data:text/html,xxx")
          (open-in-editor/open! "vbscript:msgbox(1)")
          (is (= [] @calls)
              "no forbidden-scheme URI reaches the navigator")
          ;; an unknown, non-dangerous scheme passes through (no allowlist)
          (open-in-editor/open! "lapce://open?file=src/x.cljs&line=1")
          (is (= ["lapce://open?file=src/x.cljs&line=1"] @calls)
              "an unknown custom non-dangerous scheme navigates"))))))

(deftest open-chip-title-attribute-shape
  (testing "the chip's :title attr surfaces file:line for hover"
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/app.cljs" :line 99 :column 4})
          props  (second hiccup)]
      (is (= "Open in editor — src/app.cljs:99"
             (:title props))))))

;; ---- rf2-vwcsq / rf2-ox357n — Story-side scheme-denylist behaviour ----
;;
;; The matrix tests for `forbidden-scheme?` itself live in the shared
;; editor-uri test ns. These cases cover the Story chip's wiring: the chip
;; hides ONLY when a `{:custom ...}` template resolves to one of the three
;; forbidden script schemes (javascript:/data:/vbscript:); everything else
;; — including http:/https: and unknown custom schemes — renders a chip
;; (rf2-ox357n removed the positive allowlist; the spec mandates a
;; rejection list, not an allowlist). Parity with Xray's surface.

(deftest open-chip-hides-when-custom-template-resolves-to-forbidden-scheme
  (testing "open-chip returns nil ONLY for the three forbidden script
            schemes (rf2-vwcsq). editor-uri/editor-uri gates these at
            build time → the chip is nil."
    (config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/set-editor! {:custom "data:text/html,xxx"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))

    (config/set-editor! {:custom "vbscript:msgbox(1)"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-renders-for-non-forbidden-custom-scheme
  (testing "rf2-ox357n — open-chip renders for ANY non-forbidden scheme:
            catalogued long-tail, http:/https: (no longer gated), AND
            unknown custom schemes the old allowlist would have hidden"
    (config/set-editor! {:custom "subl://open?path={path}&line={line}"})
    (let [hiccup (open-in-editor/open-chip {:file "src/x.cljs" :line 5})]
      (is (vector? hiccup))
      (is (= "subl://open?path=src/x.cljs&line=5" (:href (second hiccup)))))

    (config/set-editor! {:custom "emacsclient://{path}"})
    (is (some? (open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; http:/https: now PASS — rf2-ox357n removed the allowlist that
    ;; rejected them. The residual risk (an http template navigates the
    ;; tab on a trusted localhost dev surface) is the documented footgun
    ;; the spec accepts; script schemes stay blocked.
    (config/set-editor! {:custom "http://localhost:3000/{path}"})
    (is (= "http://localhost:3000/src/x.cljs"
           (:href (second (open-in-editor/open-chip {:file "src/x.cljs"})))))

    ;; An unknown editor scheme renders — no silent dead button.
    (config/set-editor! {:custom "lapce://open?file={path}&line={line}"})
    (is (= "lapce://open?file=src/x.cljs&line=8"
           (:href (second (open-in-editor/open-chip {:file "src/x.cljs" :line 8})))))))

(deftest open-chip-for-variant-hides-on-forbidden-scheme
  (testing "open-chip-for-variant inherits the denylist gate"
    (config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (open-in-editor/open-chip-for-variant
                {:source {:file "src/x.cljs" :line 1}})))))

;; ---- project-root prefix (rf2-zfy1e) -------------------------------------
;;
;; The bead: clicking the Open chip launched an OS-side editor with a
;; classpath-relative path ("\panel_gallery\event_detail_stories.cljs:115:3")
;; that the editor's filesystem resolver could not find. The Story config
;; now exposes `:rf.story/project-root` — set once at boot via `story/configure!` —
;; and the chip prepends it before the URI ships.

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

(deftest open-chip-project-root-regression-rf2-zfy1e
  (testing "regression: the panel-gallery testbed's failure case now
            resolves to an absolute on-disk URI when the host has
            plumbed :rf.story/project-root through Story's configure!"
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

(deftest open-chip-project-root-regression-rf2-ymnfx
  (testing "regression: panel-gallery testbed used to call
            xray-config/configure! ONLY — Story's project-root atom
            stayed nil, so the Story variant-toolbar 'Open' button
            shipped a relative `:file` slot (`panel_gallery/foo.cljs`)
            that the OS-side editor handler rejected. The fix wires
            `story/configure!` alongside the existing xray-config
            call in panel-gallery's `run` so Story's atom carries the
            same on-disk root Xray uses.

            This test pins the variant-toolbar Open behaviour under
            the panel-gallery's source-coord shape end-to-end: with
            no project-root configured, the URI is relative (the
            pre-fix bug); with `:rf.story/project-root` plumbed in
            via `story/configure!`, the URI is absolute."
    ;; Pre-fix bug shape: no project-root → relative URI (which the OS
    ;; editor handler rejects).
    (config/set-project-root! nil)
    (let [hiccup-pre (open-in-editor/open-chip
                       {:file "panel_gallery/gallery_app_db.cljs"
                        :line 42
                        :column 7})]
      (is (= "vscode://file/panel_gallery/gallery_app_db.cljs:42:7"
             (:href (second hiccup-pre)))
          "without :rf.story/project-root the URI is relative (pre-fix
           panel-gallery shape)"))
    ;; Post-fix shape: `story/configure!` seeds the atom; URI is absolute.
    (config/set-project-root!
      "C:/Users/me/code/my-app/tools/xray/testbeds")
    (let [hiccup-post (open-in-editor/open-chip
                        {:file "panel_gallery/gallery_app_db.cljs"
                         :line 42
                         :column 7})]
      (is (= (str "vscode://file/"
                  "C:/Users/me/code/my-app/tools/xray/testbeds/"
                  "panel_gallery/gallery_app_db.cljs:42:7")
             (:href (second hiccup-post)))
          "with :rf.story/project-root the URI is absolute — the
           OS-side editor handler can resolve it"))))

(deftest open-chip-project-root-roundtrip
  (testing "config/set-project-root! + get-project-root round-trip"
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
             (:href (second hiccup)))))
    (config/set-editor! :idea)
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/x.cljs" :line 1 :column 1})]
      (is (= "idea://open?file=/abs/code/src/x.cljs&line=1&column=1"
             (:href (second hiccup)))))))

;; ---- click-time navigation (rf2-muvs8) ----------------------------------
;;
;; The bead: Mike clicked the chip on Windows + Chrome and VSCode never
;; opened, despite the chip rendering with a well-formed `vscode://...`
;; href. Root cause hypothesis: `(set! (.-location js/window) uri)`
;; (the CLJS form for `window.location = uri`) was being silently no-
;; op'd by the browser for custom URI schemes in some Chromium builds,
;; while the explicit `Location.assign(uri)` from the same click
;; handler reliably fires OS handoff.
;;
;; The fix replaced the `set!` form with `Location.assign`, routed the
;; navigation through a swappable atom-held seam (`set-navigator!`)
;; so tests can capture calls without mutating `js/window.location`
;; (which is non-configurable in modern browsers and throws under
;; `defineProperty`), and added a `console.log` of the URI so silent
;; OS-handler failures (relative paths, unregistered protocol
;; handlers) are diagnosable from devtools without a debugger break.
;;
;; These tests pin the click-time contract: clicking the chip invokes
;; the navigator with the same URI carried in the :href.

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
            browser doesn't double-navigate (once via the <a href>
            native click, once via the JS Location.assign call)"
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

(deftest click-handler-hides-chip-for-forbidden-scheme
  (testing "rf2-muvs8 / rf2-vwcsq — the chip's render-time gate hides
            the chip entirely for the forbidden script schemes, so the
            click never wires up at all. Pins that the user can never
            click a javascript:/data:/vbscript: URI to navigation."
    (config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (open-in-editor/open-chip {:file "src/x.cljs"}))
        "render-time gate hides chip for forbidden scheme")))

;; ---- Windows-path URI shape regression (rf2-muvs8) ----------------------
;;
;; The bead repro path: panel-gallery testbed on Windows 11. Without
;; `:project-root` configured, the chip would build `vscode://file/
;; panel_gallery/event_detail_stories.cljs:115:3` (a relative path) and
;; VSCode would silently fail to open. With `:project-root` set to the
;; on-disk root, the URI becomes absolute and VSCode resolves it.
;;
;; The matrix below pins URI shapes for the common Windows + Mac/Linux
;; path combinations — guarding against a future refactor regressing
;; the project-root prefix semantics.

(deftest windows-path-uri-shape
  (testing "rf2-muvs8 — Windows project-root + relative source-coord
            produces a URI VSCode's OS handler can resolve"
    (config/set-project-root! "C:/Users/me/code/my-app")
    (let [hiccup (open-in-editor/open-chip
                   {:file "tools/story/src/re_frame/story/ui/open_in_editor.cljs"
                    :line 92
                    :column 5})]
      (is (= (str "vscode://file/"
                  "C:/Users/me/code/my-app/"
                  "tools/story/src/re_frame/story/ui/open_in_editor.cljs:92:5")
             (:href (second hiccup)))
          "absolute Windows URI shape — drive letter + forward slashes
           per VSCode's documented format"))))

(deftest posix-path-uri-shape
  (testing "rf2-muvs8 — POSIX project-root + relative source-coord
            produces a URI VSCode/Cursor's OS handler can resolve"
    (config/set-project-root! "/home/me/code/myapp")
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 1 :column 1})]
      (is (= "vscode://file//home/me/code/myapp/src/app/views.cljs:1:1"
             (:href (second hiccup)))
          "POSIX URI shape — double-slash after `file` because the root
           starts with `/`"))))

(deftest windows-backslash-path-uri-shape
  (testing "rf2-muvs8 — Windows project-root with trailing backslash
            still produces a valid URI (trailing separators stripped)"
    (config/set-project-root! "C:\\Users\\me\\code\\myapp\\")
    (let [hiccup (open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 1 :column 1})]
      (is (= "vscode://file/C:\\Users\\me\\code\\myapp/src/app/views.cljs:1:1"
             (:href (second hiccup)))
          "trailing separator stripped; backslashes inside the root
           preserved (VSCode accepts both on Windows)"))))

;; ---- resolve-uri (rf2-r2un8) --------------------------------------------
;;
;; `resolve-uri` is the extracted URI-building helper the chip path, the
;; `open-source-coord!` imperative path, and the dispatch-based event
;; all share. Pinning its contract here means the chip's `:href`, the
;; inspector launcher, and the fx-emitted `:uri` always agree on the
;; URI shape — one source of truth. Mirrors Xray's resolve-uri (port
;; per rf2-r2un8).

(deftest resolve-uri-returns-vscode-default
  (testing "resolve-uri builds a vscode://file URI by default"
    (is (= "vscode://file/src/app.cljs:42:7"
           (open-in-editor/resolve-uri
             {:file "src/app.cljs" :line 42 :column 7})))))

(deftest resolve-uri-respects-editor-preference
  (testing "switching editor flips the URI scheme"
    (config/set-editor! :cursor)
    (is (= "cursor://file/src/x.cljs:1:1"
           (open-in-editor/resolve-uri
             {:file "src/x.cljs" :line 1 :column 1})))))

(deftest resolve-uri-applies-project-root
  (testing "configured project-root prepends to the source-coord file"
    (config/set-project-root! "C:/Users/me/code/my-app")
    (is (= "vscode://file/C:/Users/me/code/my-app/src/app.cljs:17:3"
           (open-in-editor/resolve-uri
             {:file "src/app.cljs" :line 17 :column 3})))))

(deftest resolve-uri-nil-when-source-missing
  (testing "resolve-uri returns nil for coords without :file"
    (is (nil? (open-in-editor/resolve-uri nil)))
    (is (nil? (open-in-editor/resolve-uri {:line 1})))
    (is (nil? (open-in-editor/resolve-uri {:file ""})))))

(deftest resolve-uri-nil-for-forbidden-custom-scheme
  (testing "resolve-uri returns nil ONLY when a {:custom ...} template
            resolves to a forbidden script scheme (rf2-vwcsq). Per
            rf2-ox357n http:/https:/unknown schemes now resolve through."
    (config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (open-in-editor/resolve-uri {:file "src/x.cljs"})))
    (config/set-editor! {:custom "data:text/html,xxx"})
    (is (nil? (open-in-editor/resolve-uri {:file "src/x.cljs"})))
    ;; http: + unknown schemes now resolve (no positive allowlist).
    (config/set-editor! {:custom "http://localhost:3000/{path}"})
    (is (= "http://localhost:3000/src/x.cljs"
           (open-in-editor/resolve-uri {:file "src/x.cljs"})))
    (config/set-editor! {:custom "lapce://open?file={path}"})
    (is (= "lapce://open?file=src/x.cljs"
           (open-in-editor/resolve-uri {:file "src/x.cljs"})))))

;; ---- :rf.story/open-in-editor + :rf.editor/open (rf2-r2un8) ------------
;;
;; The dispatch-based path Story exposes alongside the imperative chip.
;; Hosts that don't render the chip directly (agents replaying via MCP,
;; custom panels) can dispatch `[:rf.story/open-in-editor coord]` and
;; let the registered fx fire the URI through the same denylist gate.
;; Mirrors Xray's `:rf.xray/open-in-editor` + `:rf.editor/open`
;; pairing (port per rf2-r2un8). Tests stub the `:rf.editor/open` reg-fx
;; with a capture, mirroring the Xray test pattern — no `window.location`
;; mutation under the test runner.

(defonce ^:private captured-editor-fx (atom []))

(defn- install-with-capture!
  "Install Story's open-in-editor handlers then replace the
  `:rf.editor/open` reg-fx with a capture stub so the test can inspect
  the fx args without touching `window.location`. Same pattern Xray's
  test suite uses.

  Per rf2-wn3bh the event now emits the structured `:source-coord`
  (so the fx can prefer the dev-server endpoint). The capture stub
  resolves the coord through the SAME `resolve-uri` helper the chip uses
  and records the resolved URI under `:uri` so the existing
  URI-equivalence assertions keep their meaning."
  []
  (reset! captured-editor-fx [])
  ;; EP-0002 (rf2-bd4div): `:rf.story/open-in-editor` dispatches under a
  ;; carried frame stamp. Register the ordinary `:rf/default` frame so the
  ;; `with-frame :rf/default`-scoped dispatches below have a frame to land
  ;; on (these tests exercise the dispatch→fx glue, not a variant frame).
  (frame/ensure-default-frame!)
  (open-in-editor/install!)
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args]
      (swap! captured-editor-fx conj
             (assoc args
                    :uri (when-let [coord (:source-coord args)]
                           (open-in-editor/resolve-uri coord)))))))

(deftest open-in-editor-event-emits-fx-with-resolved-uri
  (testing "rf2-r2un8 — dispatching `:rf.story/open-in-editor` with a
            bare coord produces a `:rf.editor/open` fx whose :uri is the
            resolved URI"
    (install-with-capture!)
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/app/events.cljs" :line 17 :column 3}]))
    (is (= 1 (count @captured-editor-fx))
        "exactly one open-fx fires per dispatch")
    (is (= "vscode://file/src/app/events.cljs:17:3"
           (:uri (first @captured-editor-fx)))
        "the resolved vscode:// URI rides on the fx args")))

(deftest open-in-editor-event-accepts-wrapped-shape
  (testing "rf2-r2un8 — dispatching with `{:source-coord coord}` (the
            wrapper shape some panels use) produces the same fx as the
            bare-coord form"
    (install-with-capture!)
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:source-coord {:file "src/x.cljs" :line 5 :column 1}}]))
    (is (= "vscode://file/src/x.cljs:5:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-parses-display-string-coord
  (testing "rf2-r2un8 — the dispatch handler parses `\"file:line\"`
            display strings back to the structured form so panels that
            flatten coords at projection time can dispatch them as
            strings without losing line info"
    (install-with-capture!)
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:source-coord "src/app/events.cljs:42"}]))
    (is (= "vscode://file/src/app/events.cljs:42:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-parses-bare-display-string
  (testing "rf2-r2un8 — bare display string (no wrapper) defensively
            handled by the parser"
    (install-with-capture!)
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor "src/x.cljs:7"]))
    (is (= "vscode://file/src/x.cljs:7:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-honours-editor-preference
  (testing "rf2-r2un8 — the fx's URI reflects `config/get-editor`
            (the same source of truth the chip render uses)"
    (install-with-capture!)
    (config/set-editor! :cursor)
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 10}]))
    (is (= "cursor://file/src/x.cljs:10:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-rejects-forbidden-scheme
  (testing "rf2-r2un8 / rf2-vwcsq — a custom template that resolves to a
            forbidden script scheme produces a fx with nil :uri (which
            `open!` is a no-op for); the handler doesn't short-circuit"
    (install-with-capture!)
    (config/set-editor! {:custom "javascript:alert(1)"})
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 1}]))
    (is (= 1 (count @captured-editor-fx))
        "fx still fires — the handler doesn't short-circuit")
    (is (nil? (:uri (first @captured-editor-fx)))
        "the resolved URI is nil — `open!` will refuse to navigate")))

(deftest open-in-editor-fx-receives-source-coord-key-rf2-wn3bh
  (testing "rf2-wn3bh — `:rf.story/open-in-editor` emits the structured
            `{:source-coord {...}}` shape (NOT a pre-resolved `:uri`) so
            `:rf.editor/open` can prefer the dev-server endpoint and fall
            back to the `editor://` URI"
    (install-with-capture!)
    (with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 1}]))
    (is (= {:file "src/x.cljs" :line 1}
           (:source-coord (first @captured-editor-fx)))
        "the structured coord rides the fx verbatim — the endpoint
         resolves the relative :file at runtime on the server")))

;; ---- rf2-wn3bh — Option B: dev-server endpoint preferred over URI -------
;;
;; The URI-fallback path is exercised throughout this file (via the
;; `always-fall-back!` launcher stub in the fixture). This block pins the
;; ENDPOINT-PREFERENCE half: when the launcher succeeds (a dev server
;; answered) the URI fallback does NOT fire; when it falls back (no dev
;; server) the URI navigates — B is additive, never removing the URI path.

(deftest endpoint-url-carries-coord-and-editor-hint
  (testing "rf2-wn3bh — `build-url` projects (coord, editor) to the
            endpoint query"
    (is (= (str open-endpoint/endpoint-path
                "?file=panel_gallery%2Ffoo.cljs&line=42&column=7&editor=cursor")
           (open-endpoint/build-url
             {:file "panel_gallery/foo.cljs" :line 42 :column 7}
             :cursor)))
    (is (nil? (open-endpoint/build-url {:line 1} :vscode))
        "no :file → no endpoint URL")))

(deftest open-coord-prefers-endpoint-when-it-succeeds
  (testing "rf2-wn3bh — when the endpoint launcher reports success, the URI
            fallback does NOT fire"
    (let [[nav calls] (capturing-navigator)
          prev        (open-endpoint/set-launcher! (fn [_url _fallback!] nil))]
      (try
        (with-stub-navigator nav
          #(open-in-editor/open-coord! {:file "src/x.cljs" :line 1}))
        (is (= [] @calls)
            "endpoint preferred → no editor:// URI navigation")
        (finally
          (open-endpoint/set-launcher! prev))))))

(deftest open-coord-falls-back-to-uri-when-no-endpoint
  (testing "rf2-wn3bh — when the launcher invokes the fallback (no dev
            server), the `editor://` URI navigates via the navigator seam"
    (config/set-editor! :vscode)
    (let [[nav calls] (capturing-navigator)
          prev        (open-endpoint/set-launcher! always-fall-back!)]
      (try
        (with-stub-navigator nav
          #(open-in-editor/open-coord! {:file "src/x.cljs" :line 9 :column 2}))
        (is (= ["vscode://file/src/x.cljs:9:2"] @calls)
            "no dev server → URI fallback navigates exactly as before")
        (finally
          (open-endpoint/set-launcher! prev))))))
