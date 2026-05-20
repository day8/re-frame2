(ns re-frame.story.ui.open-in-editor
  "The 'Open in editor' affordance — a small chip / button that opens
  the editor at a source-coord's file:line. Per rf2-evgf5 + Spec 005-
  SOTA-Features.md §'Open in editor' per variant.

  The component reads the user's editor preference from
  `re-frame.story.config/editor` (set at boot via `story/configure!`)
  and consults `re-frame.source-coords.editor-uri/editor-uri` to build
  the URI. Click calls `(.assign js/window.location uri)` — the OS
  handler chain dispatches the URI to the registered editor.

  ## Why `Location.assign` rather than `window.open`

  Custom URI schemes don't open new windows; they hand off to the OS
  handler. `window.open(\"vscode://...\")` opens a blank popup which
  the user has to close manually. `Location.assign(...)` triggers the
  same OS dispatch without leaving an orphaned window.

  ## Why `.assign` rather than `(set! .-location)` (rf2-muvs8)

  The two should be semantically equivalent — the property setter on
  `window.location` calls `Location.assign` internally per the HTML
  spec. In practice some Chromium builds on Windows have been observed
  to silently no-op the property assignment for non-http(s) schemes
  while honouring the explicit `.assign` call from the same click
  handler. `.assign` is the more reliable seam; `(set! ...)` was the
  original implementation and the bug it caused (silent click on
  Windows + VSCode) is what rf2-muvs8 fixed.

  ## Diagnostic logging (rf2-muvs8)

  `open!` emits a single-line `console.log` of the URI before each
  navigation. The log is the lowest-friction observability seam for
  diagnosing silent OS-handler failures — a developer who clicks the
  chip and sees nothing happen can open devtools, click again, and
  confirm: 'the URI shipped X; the OS handler didn't pick it up'.
  Common silent failures the log unblocks:

    - Relative path in URI (host didn't plumb `:rf.story/project-root`).
    - VSCode/Cursor URI scheme not registered with the OS.
    - Spaces / non-ASCII in path tripping the OS resolver.
    - User on a custom editor whose template produced a malformed URI.

  ## When it renders nothing

  The chip render-fn returns nil when `source-coord` lacks `:file` —
  the macro layer didn't capture a usable file path (the
  `\"NO_SOURCE_PATH\"` sentinel under CLJS without a form-meta
  fallback, per rf2-mdjp). The UI hides the chip entirely so the user
  doesn't click a no-op.

  ## Defense-in-depth scheme allowlist (rf2-cm93v / rf2-p887o)

  `editor-uri/editor-uri` already rejects `javascript:` / `data:` /
  `vbscript:` for `{:custom ...}` templates (per rf2-vwcsq). The
  click-time seam below layers a positive-allowlist gate on top
  (`editor-uri/allowed-uri?`): before assigning to `window.location`
  we verify the final URI's scheme is in
  `editor-uri/allowed-editor-uri-schemes`. Anything outside the set
  (in particular `http:` / `https:` / new schemes we did not
  anticipate) is rejected at the click-time seam — a custom template
  that resolves to `https://...` would navigate the tab rather than
  launch an editor; the allowlist makes that an obvious no-op rather
  than a silent surprise. The predicate lives in the shared
  `editor-uri` ns so Causa's parallel surface consumes the same gate.

  ## Bundle isolation

  Lives in the Story CLJS bundle; production builds elide the entire
  UI shell so this ns never enters a release bundle."
  (:require [re-frame.story.config :as config]
            [re-frame.source-coords.editor-uri :as editor-uri]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- styling -------------------------------------------------------------

(def ^:private chip-styles
  {:chip      {:padding         "2px 8px"
               :background      (:bg-3 colors/tokens)
               :color           (:info colors/tokens)
               :border          "1px solid #555"
               :border-radius   "3px"
               :cursor          "pointer"
               :font-family     mono-stack
               :font-size       (:micro typography/type-scale)
               :margin-left     "8px"
               :text-decoration "none"
               :display         "inline-block"}
   :chip-test {:padding         "1px 6px"
               :background      (:bg-2 colors/tokens)
               :color           (:info colors/tokens)
               :border          "1px solid #444"
               :border-radius   "2px"
               :cursor          "pointer"
               :font-family     mono-stack
               :font-size       (:micro typography/type-scale)
               :margin-left     "8px"
               :text-decoration "none"
               :display         "inline-block"}})

;; ---- side-effect: open the editor ----------------------------------------
;;
;; Per rf2-muvs8: the navigator seam is held in an atom so tests can swap
;; it without trying to override `window.location` (which is non-
;; configurable in modern browsers and throws under `defineProperty`).
;; Production code uses the default `default-navigator!`; tests rebind
;; via `set-navigator!` to a capturing stub.

(defn- default-navigator!
  "Default navigation seam: `(.assign js/window.location uri)`.

  Uses `(.assign location uri)` rather than
  `(set! (.-location js/window) uri)` (the original implementation).
  Both should be semantically equivalent — the property-setter on
  `window.location` calls `Location.assign` internally per the HTML
  spec — but per rf2-muvs8 some Chromium builds on Windows have been
  observed to silently no-op the property-assignment form for non-
  http(s) schemes while honouring the explicit `.assign` call from
  the same click handler. `.assign` is the more reliable seam."
  [uri]
  (.assign (.-location js/window) uri))

(defonce ^:private navigator
  ;; Held in an atom so tests can swap (`set-navigator!`). Production
  ;; code never reassigns it. Per rf2-muvs8.
  (atom default-navigator!))

(defn set-navigator!
  "Replace the navigation seam used by `open!`. Returns the previous
  seam so tests can restore it. Test-only — production callers MUST
  NOT call this. Per rf2-muvs8."
  [f]
  (let [prev @navigator]
    (reset! navigator f)
    prev))

(defn open!
  "Navigate `js/window.location` to `uri` via the configured navigator
  seam (default `Location.assign`). Custom URI schemes hand off to
  the OS handler chain. Returns nothing.

  Public so the element-inspector (rf2-h0jc0) can share the exact same
  click-time gate the chip uses — one launcher, one allowlist seam.

  Per rf2-vwcsq: `uri` is the return of `editor-uri/editor-uri`, which
  already rejects `javascript:` / `data:` / `vbscript:` schemes by
  returning `nil`. Per rf2-cm93v / rf2-p887o this fn applies a second,
  positive allowlist gate (`editor-uri/allowed-uri?`) before handing
  the URI off — closing the `http:` / `https:` / unknown-scheme path
  that a `{:custom ...}` template could otherwise resolve to. A
  rejected URI is a click-time no-op (no navigation, no console noise
  — the chip's `(when uri ...)` earlier guard handles the absent
  case; the allowlist handles the shaped-but-disallowed case).

  Per rf2-muvs8: emits a `console.log` of the URI before navigation so
  developers can diagnose silent OS-handler failures (relative paths,
  unregistered protocol handlers, etc.) without needing a debugger
  break. The log is a single line per click — low noise.

  Per rf2-muvs8: navigation goes through `@navigator` (the atom-held
  seam, default `default-navigator!`) rather than a direct
  `(.assign js/window.location uri)` call — `(set! ...)` was the
  original implementation and the bug it caused (silent click on
  Windows + VSCode) is what rf2-muvs8 fixed. The atom seam lets
  tests stub the navigation without mutating `js/window.location`
  (which is non-configurable in modern browsers)."
  [uri]
  (when (and uri (editor-uri/allowed-uri? uri))
    (js/console.log "[rf.story/open-in-editor] navigating to:" uri)
    (@navigator uri)
    nil))

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'Open' chip for a source-coord. Reads the current editor
  preference from `config/editor`; constructs the URI via
  `editor-uri/editor-uri`; click fires `window.location.href := uri`.

  Returns nil when the source-coord has no usable `:file` slot, when
  `editor-uri/editor-uri` returns nil (a scheme rejected by the
  `editor-uri`-side gate per rf2-vwcsq), or when the resolved URI's
  scheme is not in `editor-uri/allowed-editor-uri-schemes` (the
  shared positive allowlist per rf2-cm93v / rf2-p887o). The UI hides
  the chip rather than rendering an unclickable affordance.

  `variant-of-style` is `:title` (default — for the canvas title bar)
  or `:test-detail` (for the per-test failure detail box, slightly
  more compact).

  `data-test` attribute is `\"story-open-in-editor\"` so the e2e suite
  can target the chip without relying on style selectors.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords` / `re-frame.story.registrar`."
  ([source-coord]
   (open-chip source-coord :title))
  ([source-coord variant-of-style]
   (when (editor-uri/has-source? source-coord)
     (let [editor (config/get-editor)
           ;; Per rf2-zfy1e: prepend the configured project-root to the
           ;; source-coord's `:file` slot (typically classpath-relative)
           ;; so the URI carries an absolute on-disk path the OS-side
           ;; editor handler can resolve. The `:project-root` opt is
           ;; nil-tolerant — when unset, behaviour matches v1 (file
           ;; ships verbatim) so legacy hosts and tests aren't broken.
           opts   {:project-root (config/get-project-root)}
           uri    (editor-uri/editor-uri editor source-coord opts)
           style  (case variant-of-style
                    :test-detail (:chip-test chip-styles)
                    (:chip chip-styles))]
       (when (and uri (editor-uri/allowed-uri? uri))
         [:a {:style       style
              :href        uri
              :title       (editor-uri/open-button-title source-coord)
              :data-test   "story-open-in-editor"
              :data-editor (cond
                             (map? editor) "custom"
                             :else         (name editor))
              :on-click    (fn [e]
                             ;; Prevent React/Reagent's default link
                             ;; navigation (otherwise the browser
                             ;; tries to render the custom URI inside
                             ;; the tab); explicitly call `open!` so
                             ;; the OS handler fires.
                             (.preventDefault e)
                             (open! uri))}
          "open"])))))

(defn open-source-coord!
  "Resolve `source-coord` to an editor URI via the current Story config
  (`config/get-editor` + `config/get-project-root`) and hand it off to
  `open!`. Returns true when the launcher was invoked with an allowed
  URI, false otherwise (missing :file, forbidden scheme, or scheme
  outside the rf2-cm93v allowlist).

  This is the imperative path the element-inspector (rf2-h0jc0) uses
  when the user clicks a DOM element while inspector mode is on. The
  chip's `:on-click` (above) takes the same shape: build URI via
  `editor-uri`, gate via `allowed-uri?`, hand to `open!`.

  `source-coord` shape: `{:file :line :column}` per
  `re-frame.source-coords`."
  [source-coord]
  (when (editor-uri/has-source? source-coord)
    (let [editor (config/get-editor)
          opts   {:project-root (config/get-project-root)}
          uri    (editor-uri/editor-uri editor source-coord opts)]
      (when (and uri (editor-uri/allowed-uri? uri))
        (open! uri)
        true))))

(defn open-chip-for-variant
  "Render an open-chip for a variant — reads the source-coord off the
  variant body's `:source` slot (the registrar/spec/001-captured map).
  Returns nil when no coord is present.

  `variant-body` is the body map returned by
  `re-frame.story.registrar/handler-meta :variant variant-id`."
  [variant-body]
  (open-chip (:source variant-body) :title))
