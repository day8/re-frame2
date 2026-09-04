(ns re-frame.story-open-in-editor-cljs-test
  "CLJS tests for the Story-side 'Open in editor' chip (rf2-evgf5).

  The pure URI logic lives in `re-frame.source-coords.editor-uri` and is
  matrix-tested on the JVM. This file covers the Story-specific glue:

  - `rf.story.config/set-editor!` round-trips on the CLJS side.
  - `open-chip` returns nil when the source-coord lacks `:file`.
  - `open-chip` renders an `<a>` hiccup tag with the current editor's
    URI when the coord carries `:file`.
  - The chip carries the `data-test` hook for the e2e suite.
  - `open-chip-for-variant` reads `:source` off the variant body.
  - rf2-r2un8 — `:rf.story/open-in-editor` reg-event + `:rf.story.fx/open-in-editor`
    reg-fx produce a resolved URI through the same denylist seam the
    chip uses (Xray-parity port).
  - rf2-ox357n — the positive allowlist was removed; only the
    `javascript:` / `data:` / `vbscript:` denylist gates the chip now.
  - rf2-3xq1v — a REAL Story registration, macro-stamped by this very
    compile, survives a 422 endpoint decline with a URI the editor can
    actually resolve. See the block at the foot of this file."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]
            [re-frame.source-store :as rf.source-store]
            [re-frame.story :as rf.story]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.ui.open-in-editor :as rf.story.ui.open-in-editor]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
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
  (rf.story.config/set-editor! :vscode)
  (rf.story.config/set-project-root! nil)
  ;; rf2-wn3bh — pin the endpoint launcher to the synchronous fallback stub
  ;; so the URI / navigator assertions below exercise the deterministic
  ;; fallback path.
  (rf.source-coords.open-endpoint/set-launcher! always-fall-back!))

(use-fixtures :each {:before reset-editor!
                     :after  reset-editor!})

;; ---- navigator seam helpers (rf2-muvs8) ----------------------------------
;;
;; The click-time navigation seam (`set-navigator!`) is swappable so tests
;; capture navigation without mutating `js/window.location`. These two
;; helpers are shared across the whole file — including
;; `open!-denylist-gates-pre-resolved-uri` below — so they live here, above
;; their first use, rather than beside the click-time deftests (a forward
;; reference would trip the CLJS `:undeclared-var` analyzer).

(defn- with-stub-navigator
  "Swap the navigator seam for `stub-fn` for the duration of `body-fn`.
  Restores the original navigator afterward (even on throw)."
  [stub-fn body-fn]
  (let [prev (rf.story.ui.open-in-editor/set-navigator! stub-fn)]
    (try
      (body-fn)
      (finally
        (rf.story.ui.open-in-editor/set-navigator! prev)))))

(defn- capturing-navigator
  "Build a navigator fn that pushes its URI argument onto the shared
  `calls` atom. Returns `[navigator-fn, calls-atom]`."
  []
  (let [calls (atom [])
        nav   (fn [uri] (swap! calls conj uri))]
    [nav calls]))

;; ---- chip rendering ------------------------------------------------------

(deftest open-chip-renders-anchor-with-href
  (testing "open-chip returns an <a> hiccup vector when source has :file"
    (let [coord  {:ns 'app.views :file "src/app/views.cljs" :line 42 :column 7}
          hiccup (rf.story.ui.open-in-editor/open-chip coord)]
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
      (rf.story.config/set-editor! :cursor)
      (is (= "cursor://file/src/x.cljs:10:1"
             (:href (second (rf.story.ui.open-in-editor/open-chip coord)))))
      (rf.story.config/set-editor! :idea)
      (is (= "idea://open?file=src/x.cljs&line=10&column=1"
             (:href (second (rf.story.ui.open-in-editor/open-chip coord))))))))

(deftest open-chip-supports-custom-template
  (testing ":custom template is read live from config"
    (rf.story.config/set-editor! {:custom "zed://file/{path}:{line}"})
    (let [coord {:file "src/x.cljs" :line 5 :column 2}]
      (is (= "zed://file/src/x.cljs:5"
             (:href (second (rf.story.ui.open-in-editor/open-chip coord)))))
      (is (= "custom"
             (:data-editor (second (rf.story.ui.open-in-editor/open-chip coord))))))))

(deftest open-chip-nil-when-source-missing
  (testing "open-chip returns nil when source-coord lacks :file"
    (is (nil? (rf.story.ui.open-in-editor/open-chip nil)))
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:line 10})))
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:file ""})))))

(deftest open-chip-for-variant-reads-source-slot
  (testing "open-chip-for-variant pulls :source off the variant body"
    (let [body {:setup []
                :source {:ns 'app.stories
                         :file "src/app/stories.cljs"
                         :line 17
                         :column 3}}
          hiccup (rf.story.ui.open-in-editor/open-chip-for-variant body)]
      (is (vector? hiccup))
      (is (= "vscode://file/src/app/stories.cljs:17:3"
             (:href (second hiccup))))))
  (testing "open-chip-for-variant nil when variant body has no :source"
    (is (nil? (rf.story.ui.open-in-editor/open-chip-for-variant {:setup []})))
    (is (nil? (rf.story.ui.open-in-editor/open-chip-for-variant nil)))))

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
          prev  (rf.story.ui.open-in-editor/set-navigator! nav)]
      (try
        (rf.story.ui.open-in-editor/open-source-coord!
          {:file "src/app.cljs" :line 17 :column 3})
        (is (= ["vscode://file/src/app.cljs:17:3"] @calls)
            "navigator invoked once with the resolved vscode:// URI")
        (finally
          (rf.story.ui.open-in-editor/set-navigator! prev))))))

(deftest open-source-coord!-no-op-without-file
  (testing "open-source-coord! returns false + no-ops when source-coord
            lacks :file"
    (let [calls (atom [])
          nav   (fn [uri] (swap! calls conj uri))
          prev  (rf.story.ui.open-in-editor/set-navigator! nav)]
      (try
        (is (false? (rf.story.ui.open-in-editor/open-source-coord! nil)))
        (is (false? (rf.story.ui.open-in-editor/open-source-coord! {:line 10})))
        (is (= [] @calls)
            "no navigation attempted when :file is absent")
        (finally
          (rf.story.ui.open-in-editor/set-navigator! prev))))))

(deftest open-source-coord!-respects-editor-preference
  (testing "the URI shipped by open-source-coord! reflects the live
            editor + project-root config — same source of truth the
            chip's :href reads"
    (rf.story.config/set-editor! :cursor)
    (rf.story.config/set-project-root! "C:/Users/me/code/my-app")
    (let [calls (atom [])
          nav   (fn [uri] (swap! calls conj uri))
          prev  (rf.story.ui.open-in-editor/set-navigator! nav)]
      (try
        (rf.story.ui.open-in-editor/open-source-coord!
          {:file "src/app.cljs" :line 17 :column 3})
        (is (= ["cursor://file/C:/Users/me/code/my-app/src/app.cljs:17:3"]
               @calls))
        (finally
          (rf.story.ui.open-in-editor/set-navigator! prev))))))

(deftest open!-denylist-gates-pre-resolved-uri
  (testing "rf2-ox357n — `open!` re-applies the scheme denylist at the
            pre-resolved {:uri ...} handoff (the :rf.story.fx/open-in-editor reg-fx
            path that bypasses editor-uri's build-time gating). Forbidden
            schemes never reach the navigator; non-dangerous schemes
            (incl. unknown custom) navigate."
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          ;; forbidden script schemes are refused at the click-time seam,
          ;; case-insensitively + leading-whitespace tolerant
          (rf.story.ui.open-in-editor/open! "javascript:alert(1)")
          (rf.story.ui.open-in-editor/open! "JavaScript:alert(1)")
          (rf.story.ui.open-in-editor/open! " data:text/html,xxx")
          (rf.story.ui.open-in-editor/open! "vbscript:msgbox(1)")
          (is (= [] @calls)
              "no forbidden-scheme URI reaches the navigator")
          ;; an unknown, non-dangerous scheme passes through (no allowlist)
          (rf.story.ui.open-in-editor/open! "lapce://open?file=src/x.cljs&line=1")
          (is (= ["lapce://open?file=src/x.cljs&line=1"] @calls)
              "an unknown custom non-dangerous scheme navigates"))))))

(deftest open-chip-title-attribute-shape
  (testing "the chip's :title attr surfaces file:line for hover"
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
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
            schemes (rf2-vwcsq). rf.source-coords.editor-uri/editor-uri gates these at
            build time → the chip is nil."
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"})))

    (rf.story.config/set-editor! {:custom "data:text/html,xxx"})
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"})))

    (rf.story.config/set-editor! {:custom "vbscript:msgbox(1)"})
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"})))))

(deftest open-chip-renders-for-non-forbidden-custom-scheme
  (testing "rf2-ox357n — open-chip renders for ANY non-forbidden scheme:
            catalogued long-tail, http:/https: (no longer gated), AND
            unknown custom schemes the old allowlist would have hidden"
    (rf.story.config/set-editor! {:custom "subl://open?path={path}&line={line}"})
    (let [hiccup (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs" :line 5})]
      (is (vector? hiccup))
      (is (= "subl://open?path=src/x.cljs&line=5" (:href (second hiccup)))))

    (rf.story.config/set-editor! {:custom "emacsclient://{path}"})
    (is (some? (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"})))

    ;; http:/https: now PASS — rf2-ox357n removed the allowlist that
    ;; rejected them. The residual risk (an http template navigates the
    ;; tab on a trusted localhost dev surface) is the documented footgun
    ;; the spec accepts; script schemes stay blocked.
    (rf.story.config/set-editor! {:custom "http://localhost:3000/{path}"})
    (is (= "http://localhost:3000/src/x.cljs"
           (:href (second (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"})))))

    ;; An unknown editor scheme renders — no silent dead button.
    (rf.story.config/set-editor! {:custom "lapce://open?file={path}&line={line}"})
    (is (= "lapce://open?file=src/x.cljs&line=8"
           (:href (second (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs" :line 8})))))))

(deftest open-chip-for-variant-hides-on-forbidden-scheme
  (testing "open-chip-for-variant inherits the denylist gate"
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (rf.story.ui.open-in-editor/open-chip-for-variant
                {:source {:file "src/x.cljs" :line 1}})))))

;; ---- project-root prefix (rf2-zfy1e) -------------------------------------
;;
;; The bead: clicking the Open chip launched an OS-side editor with a
;; classpath-relative path ("\panel_gallery\event_detail_stories.cljs:115:3")
;; that the editor's filesystem resolver could not find. The Story config
;; now exposes `:rf.story/project-root` — set once at boot via `rf.story/configure!` —
;; and the chip prepends it before the URI ships.

(deftest open-chip-default-no-project-root
  (testing "with no project-root configured, the chip ships the file slot
            verbatim — preserves v1 behaviour for hosts that haven't
            plumbed the knob yet"
    (is (nil? (rf.story.config/get-project-root)))
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 1 :column 1})]
      (is (= "vscode://file/src/app/views.cljs:1:1"
             (:href (second hiccup)))))))

(deftest open-chip-prefixes-with-project-root
  (testing "set-project-root! plumbs the on-disk root through the chip"
    (rf.story.config/set-project-root! "C:/Users/me/code/my-app")
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 42 :column 7})]
      (is (= "vscode://file/C:/Users/me/code/my-app/src/app/views.cljs:42:7"
             (:href (second hiccup)))))))

(deftest open-chip-project-root-regression-rf2-zfy1e
  (testing "regression: the panel-gallery testbed's failure case now
            resolves to an absolute on-disk URI when the host has
            plumbed :rf.story/project-root through Story's configure!"
    (rf.story.config/set-project-root!
      "C:/Users/me/code/my-app/tools/xray/testbeds")
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
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
            `rf.story/configure!` alongside the existing xray-config
            call in panel-gallery's `run` so Story's atom carries the
            same on-disk root Xray uses.

            This test pins the variant-toolbar Open behaviour under
            the panel-gallery's source-coord shape end-to-end: with
            no project-root configured, the URI is relative (the
            pre-fix bug); with `:rf.story/project-root` plumbed in
            via `rf.story/configure!`, the URI is absolute."
    ;; Pre-fix bug shape: no project-root → relative URI (which the OS
    ;; editor handler rejects).
    (rf.story.config/set-project-root! nil)
    (let [hiccup-pre (rf.story.ui.open-in-editor/open-chip
                       {:file "panel_gallery/gallery_app_db.cljs"
                        :line 42
                        :column 7})]
      (is (= "vscode://file/panel_gallery/gallery_app_db.cljs:42:7"
             (:href (second hiccup-pre)))
          "without :rf.story/project-root the URI is relative (pre-fix
           panel-gallery shape)"))
    ;; Post-fix shape: `rf.story/configure!` seeds the atom; URI is absolute.
    (rf.story.config/set-project-root!
      "C:/Users/me/code/my-app/tools/xray/testbeds")
    (let [hiccup-post (rf.story.ui.open-in-editor/open-chip
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
  (testing "rf.story.config/set-project-root! + get-project-root round-trip"
    (rf.story.config/set-project-root! "/abs/code")
    (is (= "/abs/code" (rf.story.config/get-project-root)))
    (rf.story.config/set-project-root! nil)
    (is (nil? (rf.story.config/get-project-root)))
    ;; blank strings normalise to nil so the chip behaves as if unset.
    (rf.story.config/set-project-root! "")
    (is (nil? (rf.story.config/get-project-root)))))

(deftest open-chip-project-root-survives-editor-change
  (testing "switching editor keeps project-root applied to the new scheme"
    (rf.story.config/set-project-root! "/abs/code")
    (rf.story.config/set-editor! :cursor)
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/x.cljs" :line 1 :column 1})]
      (is (= "cursor://file//abs/code/src/x.cljs:1:1"
             (:href (second hiccup)))))
    (rf.story.config/set-editor! :idea)
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
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
;; the navigator with the same URI carried in the :href. The
;; `with-stub-navigator` / `capturing-navigator` seam helpers used here
;; are defined in the helpers section near the top of the file (they are
;; first used earlier, by `open!-denylist-gates-pre-resolved-uri`).

(deftest click-handler-calls-navigator-with-uri
  (testing "rf2-muvs8 — clicking the chip invokes the navigator seam
            with the same URI carried in the :href"
    (let [hiccup       (rf.story.ui.open-in-editor/open-chip
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
    (let [hiccup       (rf.story.ui.open-in-editor/open-chip
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
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (rf.story.ui.open-in-editor/open-chip {:file "src/x.cljs"}))
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
    (rf.story.config/set-project-root! "C:/Users/me/code/my-app")
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
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
    (rf.story.config/set-project-root! "/home/me/code/myapp")
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
                   {:file "src/app/views.cljs" :line 1 :column 1})]
      (is (= "vscode://file//home/me/code/myapp/src/app/views.cljs:1:1"
             (:href (second hiccup)))
          "POSIX URI shape — double-slash after `file` because the root
           starts with `/`"))))

(deftest windows-backslash-path-uri-shape
  (testing "rf2-muvs8 — Windows project-root with trailing backslash
            still produces a valid URI (trailing separators stripped)"
    (rf.story.config/set-project-root! "C:\\Users\\me\\code\\myapp\\")
    (let [hiccup (rf.story.ui.open-in-editor/open-chip
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
           (rf.story.ui.open-in-editor/resolve-uri
             {:file "src/app.cljs" :line 42 :column 7})))))

(deftest resolve-uri-respects-editor-preference
  (testing "switching editor flips the URI scheme"
    (rf.story.config/set-editor! :cursor)
    (is (= "cursor://file/src/x.cljs:1:1"
           (rf.story.ui.open-in-editor/resolve-uri
             {:file "src/x.cljs" :line 1 :column 1})))))

(deftest resolve-uri-applies-project-root
  (testing "configured project-root prepends to the source-coord file"
    (rf.story.config/set-project-root! "C:/Users/me/code/my-app")
    (is (= "vscode://file/C:/Users/me/code/my-app/src/app.cljs:17:3"
           (rf.story.ui.open-in-editor/resolve-uri
             {:file "src/app.cljs" :line 17 :column 3})))))

(deftest resolve-uri-nil-when-source-missing
  (testing "resolve-uri returns nil for coords without :file"
    (is (nil? (rf.story.ui.open-in-editor/resolve-uri nil)))
    (is (nil? (rf.story.ui.open-in-editor/resolve-uri {:line 1})))
    (is (nil? (rf.story.ui.open-in-editor/resolve-uri {:file ""})))))

(deftest resolve-uri-nil-for-forbidden-custom-scheme
  (testing "resolve-uri returns nil ONLY when a {:custom ...} template
            resolves to a forbidden script scheme (rf2-vwcsq). Per
            rf2-ox357n http:/https:/unknown schemes now resolve through."
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (is (nil? (rf.story.ui.open-in-editor/resolve-uri {:file "src/x.cljs"})))
    (rf.story.config/set-editor! {:custom "data:text/html,xxx"})
    (is (nil? (rf.story.ui.open-in-editor/resolve-uri {:file "src/x.cljs"})))
    ;; http: + unknown schemes now resolve (no positive allowlist).
    (rf.story.config/set-editor! {:custom "http://localhost:3000/{path}"})
    (is (= "http://localhost:3000/src/x.cljs"
           (rf.story.ui.open-in-editor/resolve-uri {:file "src/x.cljs"})))
    (rf.story.config/set-editor! {:custom "lapce://open?file={path}"})
    (is (= "lapce://open?file=src/x.cljs"
           (rf.story.ui.open-in-editor/resolve-uri {:file "src/x.cljs"})))))

;; ---- :rf.story/open-in-editor + :rf.story.fx/open-in-editor (rf2-r2un8) ------------
;;
;; The dispatch-based path Story exposes alongside the imperative chip.
;; Hosts that don't render the chip directly (agents replaying via MCP,
;; custom panels) can dispatch `[:rf.story/open-in-editor coord]` and
;; let the registered fx fire the URI through the same denylist gate.
;; Mirrors Xray's `:rf.xray/open-in-editor` + `:rf.story.fx/open-in-editor`
;; pairing (port per rf2-r2un8).
;;
;; ## The capture seam is PER-FRAME, never a registry write (rf2-oslyz)
;;
;; These tests need the fx ARGS, not the navigation, so the effect is
;; captured. The capture must NOT be a second `rf/reg-fx` of
;; `:rf.story.fx/open-in-editor` from this namespace: `registrar/register!`
;; keeps a provenance-stamped source slot per registering namespace, so a
;; test-namespace registration leaves `[:fx :rf.story.fx/open-in-editor]`
;; claimed by BOTH `re-frame.story.ui.open-in-editor` and this ns. The next
;; `rf/make-frame` anywhere in the process then fails default-image assembly
;; with `:rf.error/image-duplicate-id` — which is exactly how this file used
;; to break `re-frame.story.open-in-editor-ownership-cljs-test` whenever the
;; selector ran the two namespaces in that order.
;;
;; The capture is therefore the per-frame `:fx-overrides` FUNCTION-VALUE seam
;; (Spec 002 §Per-frame and per-call overrides): scoped to the one frame these
;; dispatches land on, invisible to the source store, and gone the moment the
;; frame is. `capture-frame` is private to this ns so the override can never
;; leak onto `:rf/default` (which other suites dispatch through).
;;
;; A fn-value override runs WITHOUT a registry lookup of the id it shadows, so
;; on its own it would happily capture an effect production never registered —
;; the vacuous green this bead is about. `assert-production-registration!`
;; below is the positive control that forbids that, and
;; `production-fx-navigates-without-any-override` drives the REAL registered
;; effect end-to-end with no override at all.

(defonce ^:private captured-editor-fx (atom []))

(def ^:private capture-frame
  "Frame the dispatch-path tests land on. Private to this ns: it carries the
  `:fx-overrides` capture, and `:rf/default` must stay override-free for the
  suites that dispatch through it."
  :story.open-in-editor/capture)

(def ^:private production-fx-ns
  "The ONE namespace allowed to own `[:fx :rf.story.fx/open-in-editor]`."
  "re-frame.story.ui.open-in-editor")

(defn- ensure-adapter!
  "Install the plain-atom test adapter unless one is already installed —
  `rf/make-frame` needs a state-container factory, and the consolidated run
  may or may not have had `init!` called by an earlier namespace."
  []
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil)))

(defn- assert-production-registration!
  "POSITIVE CONTROL. `:rf.story.fx/open-in-editor` must be registered, and
  registered from PRODUCTION only. Fails if `install!` stopped registering the
  effect (which would make every capture below a fn-value override standing in
  for nothing), and fails if any test namespace re-registers the id (the
  provenance collision that used to break image assembly)."
  []
  (is (= #{production-fx-ns}
         (set (keys (rf.source-store/descriptors-for
                      :fx :rf.story.fx/open-in-editor))))
      "`:rf.story.fx/open-in-editor` is registered, and ONLY by
       re-frame.story.ui.open-in-editor — no test-namespace shadow"))

(defn- install-with-capture!
  "Install Story's open-in-editor handlers, verify the production effect
  registration, then build `capture-frame` carrying an `:fx-overrides`
  function-value that records the fx args instead of touching
  `window.location`.

  Per rf2-wn3bh the event emits the structured `:source-coord` (so the fx can
  prefer the dev-server endpoint). The capture resolves the coord through the
  SAME `resolve-uri` helper the chip uses and records the resolved URI under
  `:uri`, so the URI-equivalence assertions keep their meaning."
  []
  (reset! captured-editor-fx [])
  (ensure-adapter!)
  (rf.story.ui.open-in-editor/install!)
  (assert-production-registration!)
  ;; EP-0002 (rf2-bd4div): `:rf.story/open-in-editor` dispatches under a
  ;; carried frame stamp, so the `with-frame`-scoped dispatches below need a
  ;; live frame to land on. Re-`make-frame`-ing the same id is idempotent
  ;; replacement, so this is safe once per test.
  (rf/make-frame
    {:id capture-frame
     :doc "open-in-editor dispatch-path test frame (carries the fx capture)"
     :fx-overrides
     {:rf.story.fx/open-in-editor
      (fn [_ctx args]
        (swap! captured-editor-fx conj
               (assoc args
                      :uri (when-let [coord (:source-coord args)]
                             (rf.story.ui.open-in-editor/resolve-uri coord)))))}}))

(deftest open-in-editor-event-emits-fx-with-resolved-uri
  (testing "rf2-r2un8 — dispatching `:rf.story/open-in-editor` with a
            bare coord produces a `:rf.story.fx/open-in-editor` fx whose :uri is the
            resolved URI"
    (install-with-capture!)
    (with-frame capture-frame
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
    (with-frame capture-frame
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
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:source-coord "src/app/events.cljs:42"}]))
    (is (= "vscode://file/src/app/events.cljs:42:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-parses-bare-display-string
  (testing "rf2-r2un8 — bare display string (no wrapper) defensively
            handled by the parser"
    (install-with-capture!)
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor "src/x.cljs:7"]))
    (is (= "vscode://file/src/x.cljs:7:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-honours-editor-preference
  (testing "rf2-r2un8 — the fx's URI reflects `rf.story.config/get-editor`
            (the same source of truth the chip render uses)"
    (install-with-capture!)
    (rf.story.config/set-editor! :cursor)
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 10}]))
    (is (= "cursor://file/src/x.cljs:10:1"
           (:uri (first @captured-editor-fx))))))

(deftest open-in-editor-event-rejects-forbidden-scheme
  (testing "rf2-r2un8 / rf2-vwcsq — a custom template that resolves to a
            forbidden script scheme produces a fx with nil :uri (which
            `open!` is a no-op for); the handler doesn't short-circuit"
    (install-with-capture!)
    (rf.story.config/set-editor! {:custom "javascript:alert(1)"})
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 1}]))
    (is (= 1 (count @captured-editor-fx))
        "fx still fires — the handler doesn't short-circuit")
    (is (nil? (:uri (first @captured-editor-fx)))
        "the resolved URI is nil — `open!` will refuse to navigate")))

(deftest open-in-editor-fx-receives-source-coord-key-rf2-wn3bh
  (testing "rf2-wn3bh — `:rf.story/open-in-editor` emits the structured
            `{:source-coord {...}}` shape (NOT a pre-resolved `:uri`) so
            `:rf.story.fx/open-in-editor` can prefer the dev-server endpoint and fall
            back to the `editor://` URI"
    (install-with-capture!)
    (with-frame capture-frame
      (rf/dispatch-sync [:rf.story/open-in-editor
                         {:file "src/x.cljs" :line 1}]))
    (is (= {:file "src/x.cljs" :line 1}
           (:source-coord (first @captured-editor-fx)))
        "the structured coord rides the fx verbatim — the endpoint
         resolves the relative :file at runtime on the server")))

(deftest production-fx-navigates-without-any-override
  (testing "rf2-oslyz — the tests above capture through a per-frame
            `:fx-overrides` fn-value, and a fn-value override runs without
            any registry lookup of the id it shadows. So this one drives the
            REAL registered `:rf.story.fx/open-in-editor` on a frame carrying
            NO overrides at all, observing Story's own navigator seam. If
            `install!` stopped registering the effect, the capture tests
            would still pass and only this one would red — which is the
            whole point of it being here."
    (ensure-adapter!)
    (rf.story.ui.open-in-editor/install!)
    (assert-production-registration!)
    (rf.story.config/set-editor! :vscode)
    (rf/make-frame {:id  :story.open-in-editor/production
                    :doc "no-override frame — drives the REAL effect"})
    (let [[nav calls] (capturing-navigator)]
      (with-stub-navigator nav
        (fn []
          (with-frame :story.open-in-editor/production
            (rf/dispatch-sync [:rf.story/open-in-editor
                               {:file "src/app.cljs" :line 17 :column 3}]))))
      (is (= ["vscode://file/src/app.cljs:17:3"] @calls)
          "the production event → production fx → production navigator
           chain is live end-to-end, with nothing stubbed but the OS
           handoff itself"))))

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
    (is (= (str rf.source-coords.open-endpoint/endpoint-path
                "?file=panel_gallery%2Ffoo.cljs&line=42&column=7&editor=cursor")
           (rf.source-coords.open-endpoint/build-url
             {:file "panel_gallery/foo.cljs" :line 42 :column 7}
             :cursor)))
    (is (nil? (rf.source-coords.open-endpoint/build-url {:line 1} :vscode))
        "no :file → no endpoint URL")))

(deftest open-coord-prefers-endpoint-when-it-succeeds
  (testing "rf2-wn3bh — when the endpoint launcher reports success, the URI
            fallback does NOT fire"
    (let [[nav calls] (capturing-navigator)
          prev        (rf.source-coords.open-endpoint/set-launcher! (fn [_url _fallback!] nil))]
      (try
        (with-stub-navigator nav
          #(rf.story.ui.open-in-editor/open-coord! {:file "src/x.cljs" :line 1}))
        (is (= [] @calls)
            "endpoint preferred → no editor:// URI navigation")
        (finally
          (rf.source-coords.open-endpoint/set-launcher! prev))))))

(deftest open-coord-falls-back-to-uri-when-no-endpoint
  (testing "rf2-wn3bh — when the launcher invokes the fallback (no dev
            server), the `editor://` URI navigates via the navigator seam"
    (rf.story.config/set-editor! :vscode)
    (let [[nav calls] (capturing-navigator)
          prev        (rf.source-coords.open-endpoint/set-launcher! always-fall-back!)]
      (try
        (with-stub-navigator nav
          #(rf.story.ui.open-in-editor/open-coord! {:file "src/x.cljs" :line 9 :column 2}))
        (is (= ["vscode://file/src/x.cljs:9:2"] @calls)
            "no dev server → URI fallback navigates exactly as before")
        (finally
          (rf.source-coords.open-endpoint/set-launcher! prev))))))

;; ---- rf2-3xq1v — a real Story coordinate through a 422 decline -----------
;;
;; Every URI assertion above is written against a HAND-TYPED coord
;; (`{:file "src/x.cljs" ...}`), which is the right shape for pinning the
;; URI grammar and the wrong shape for pinning what Story's macro pipeline
;; actually stamps. The rf2-3xq1v defect lived exactly in that gap: the
;; grammar was fine, the coordinate feeding it was classpath-relative.
;;
;; `re-frame.story.macros/coords-form` now delegates to
;; `re-frame.source-coords/coords-form`, which absolutises `:file` through
;; the context class-loader ON THE JVM, AT MACROEXPANSION. So the
;; registration below is the assertion's own fixture in the strictest
;; sense: THIS compile stamped it, from THIS file, and what the compile
;; baked into the bundle is what the block reads back.
;;
;; The scenario is the audit's: the endpoint is PREFERRED and declines
;; with 422 (what `re-frame.testbed.open-in-editor-server` answers when
;; `launch-editor` cannot carry a coordinate to the configured editor), no
;; `:rf.story/project-root` is configured (the state every repository dev
;; testbed is in since the browser-side checkout-root pipeline was
;; retired), and the client falls back to the `windsurf://` URI. Before
;; the fix that URI was `windsurf://file/counter_with_stories/stories.cljs
;; :196:3` — relative, unresolvable, a chip that silently misses.
;;
;; The real client seam runs: `fetch-launcher!` unmodified over a stubbed
;; `globalThis.fetch`, Story's own `open-coord!` (so `resolve-uri` and the
;; navigator seam are in the path too). Only the promise is captured, so
;; the async test can await the decision.

(def ^:private real-fetch
  "The platform `fetch`, captured at load. A stub left installed would
  answer for every later namespace in the shared `:node-test` build — a
  leak a green suite hides rather than reports — so the test asserts the
  global is `identical?` to this again afterwards."
  (.-fetch js/globalThis))

(defn- strip-uri-position
  "`windsurf://file/<path>:<line>:<column>` → `<path>`."
  [uri]
  (-> uri
      (subs (count "windsurf://file/"))
      (str/replace #":\d+:\d+$" "")))

(deftest endpoint-422-falls-back-to-an-absolute-uri-for-a-real-story-coord
  (testing "rf2-3xq1v — a real `rf.story/reg-story` coordinate, stamped by this
            compile, reaches the editor through the 422 fallback as an
            ABSOLUTE path"
    (rf.story/reg-story :story.source-coords.cljs-pin
      {:doc "rf2-3xq1v fixture — this form's own coordinate is the subject."})
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.cljs-pin))]
      (is (some? coord) "the registration carries a :source coord at all")
      (is (some? (:file coord)) "and that coord carries a :file")
      (is (rf.source-coords.editor-uri/absolute-path? (:file coord))
          (str "the macro must bake an ABSOLUTE :file at expansion — got "
               (pr-str (:file coord))))
      (is (str/ends-with? (str/replace (:file coord) "\\" "/")
                          "re_frame/story_open_in_editor_cljs_test.cljs")
          "and it must still end in the classpath-relative tail it started as")
      (rf.story.config/set-editor! :windsurf)
      (rf.story.config/set-project-root! nil)
      (async done
        ;; The navigator stub is installed for the whole ASYNC duration, not
        ;; just the synchronous call: `fetch-launcher!` runs `fallback!` in a
        ;; promise callback, so a `with-stub-navigator` scope would already
        ;; have restored `default-navigator!` — which reaches for `js/window`
        ;; and blows up under node. Worse, `fetch-launcher!`'s own `.catch`
        ;; would then run `fallback!` a SECOND time on that throw.
        (let [[nav calls]  (capturing-navigator)
              pending      (atom nil)
              orig-fetch   (.-fetch js/globalThis)
              prev-nav     (rf.story.ui.open-in-editor/set-navigator! nav)
              prev-launch  (rf.source-coords.open-endpoint/set-launcher!
                             (fn [url fallback!]
                               ;; The REAL launcher; the atom only lets the
                               ;; async test await the decision.
                               (reset! pending
                                       (rf.source-coords.open-endpoint/fetch-launcher! url fallback!))))
              restore!     (fn []
                             (set! (.-fetch js/globalThis) orig-fetch)
                             (rf.source-coords.open-endpoint/set-launcher! prev-launch)
                             (rf.story.ui.open-in-editor/set-navigator! prev-nav)
                             (rf.story.config/set-editor! :vscode))]
          (set! (.-fetch js/globalThis)
                (fn [_url _opts]
                  (js/Promise.resolve #js {:ok false :status 422})))
          (rf.story.ui.open-in-editor/open-coord! coord)
          ;; Bind the launcher's promise to a LOCAL before threading. A
          ;; multi-step form in the `->` head (an `or`, say) is lowered by the
          ;; compiler to an awaited async IIFE — the rf2-i3dvj shape — and the
          ;; await unwraps the promise to the `nil` `fetch-launcher!`'s own
          ;; `.then` returns, leaving `.then` to be called on null.
          (let [p @pending]
            (if (nil? p)
              (do (restore!)
                  (is false "the launcher seam was never reached — the endpoint
                             is supposed to be PREFERRED, so `fetch-launcher!`
                             must have run")
                  (done))
              (-> p
                  (.then (fn [_]
                           (restore!)
                           (is (= 1 (count @calls))
                               "the 422 decline ran the URI fallback exactly once")
                           (let [uri  (first @calls)
                                 path (strip-uri-position uri)]
                             (is (str/starts-with? uri "windsurf://file/")
                                 "the fallback is the historic editor:// URI path")
                             (is (rf.source-coords.editor-uri/absolute-path? path)
                                 (str "the URI the editor receives must name an "
                                      "ABSOLUTE path — got " (pr-str path)))
                             (is (not (str/starts-with? path "re_frame/"))
                                 "the pre-fix shape — a bare classpath-relative
                                  path in the URI — is what no editor could open"))
                           (is (identical? real-fetch (.-fetch js/globalThis))
                               "the fetch stub was not left installed")
                           (done)))
                  (.catch (fn [err]
                            (restore!)
                            (is false (str "the 422 fallback path threw: " err))
                            (done)))))))))))

(deftest project-root-knob-is-inert-over-an-absolutised-story-coord
  (testing "rf2-3xq1v — the public `:rf.story/project-root` option is KEPT for
            external / static / non-shadow hosts, and stays inert over a
            coordinate the macro already absolutised: `compose-path` passes an
            absolute `:file` through rather than double-prefixing it"
    (rf.story/reg-story :story.source-coords.cljs-root-pin
      {:doc "rf2-3xq1v fixture — same coordinate, two project-root settings."})
    (let [coord (:source (rf.story/handler-meta :story :story.source-coords.cljs-root-pin))]
      (rf.story.config/set-editor! :windsurf)
      (rf.story.config/set-project-root! nil)
      (let [bare (rf.story.ui.open-in-editor/resolve-uri coord)]
        (rf.story.config/set-project-root! "/some/external/root")
        (is (= bare (rf.story.ui.open-in-editor/resolve-uri coord))
            "an absolute :file is not re-rooted by the knob")
        (rf.story.config/set-project-root! nil)
        (rf.story.config/set-editor! :vscode)))))
