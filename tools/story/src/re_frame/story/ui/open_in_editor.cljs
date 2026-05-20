(ns re-frame.story.ui.open-in-editor
  "The 'Open in editor' affordance — a small chip / button that opens
  the editor at a source-coord's file:line. Per rf2-evgf5 + Spec 005-
  SOTA-Features.md §'Open in editor' per variant.

  Mirrors `day8.re-frame2-causa.open-in-editor` — same shape, same
  click-time gate, same launcher seam. Per rf2-r2un8: the two surfaces
  were drifting; Causa's structure (resolve-uri helper + dispatch-based
  path + install! registration) is the canonical shape both tools
  consume now. Story keeps its existing public chip API
  (`open-chip` / `open-chip-for-variant` / `open-source-coord!`) but
  delegates URI resolution to `resolve-uri` and adds a parallel
  dispatch path (`:rf.story/open-in-editor` reg-event-fx) so a panel
  that doesn't render the chip directly can still hand off a
  source-coord through re-frame.

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
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.story.config :as config]
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

;; ---- pure: resolve a source-coord to a launchable URI --------------------
;;
;; Per rf2-r2un8 (porting Causa's structure): URI building is extracted
;; into one helper so the chip path and the dispatch path share the same
;; logic — chip's `:href`, chip's `:on-click`, the inspector launcher, and
;; the `:rf.story/open-in-editor` event-fx all call `resolve-uri`.

(defn- parse-file-line
  "Parse a `\"file:line\"` (or bare `\"file\"`) display string into the
  structured source-coord map shape `editor-uri/editor-uri` expects.

  Some Story-host integrations (e.g. agents replaying open-in-editor
  via the MCP surface, panels that flatten a coord to a display
  string at projection time) ship the coord as a string rather than a
  map. The parser walks the string back to the structured form.

  The split is on the LAST `:` so a Windows-style
  `\"C:/users/.../x.cljs:42\"` parses correctly (drive-letter colon
  stays with the path). Returns `{:file ... :line <int-or-nil>}` —
  `:column` falls through to `editor-uri`'s default of 1."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [trimmed  (str/triml s)
          colon-ix (str/last-index-of trimmed ":")
          tail     (when (and colon-ix (pos? colon-ix))
                     (subs trimmed (inc colon-ix)))]
      (if (and tail (seq tail) (re-matches #"\d+" tail))
        {:file (subs trimmed 0 colon-ix)
         :line (js/parseInt tail 10)}
        {:file trimmed}))))

(defn- coerce-coord
  "Normalise a dispatch payload to a structured source-coord map.

  Accepts (in order of preference):

    - A bare structured map `{:file ... :line ...}` (the canonical
      shape every Story panel writes).
    - A wrapper map `{:source-coord <coord>}` where `<coord>` is
      either a structured map OR a `\"file:line\"` display string
      (the dispatch shape an external integration may emit).
    - A bare display string `\"file:line\"` (defensive).

  Returns the map form; callers feed it to `editor-uri/editor-uri`
  unchanged. Mirrors Causa's `coerce-coord` (rf2-r2un8 port)."
  [payload]
  (let [unwrapped (if (and (map? payload) (contains? payload :source-coord))
                    (:source-coord payload)
                    payload)]
    (cond
      (map? unwrapped)    unwrapped
      (string? unwrapped) (parse-file-line unwrapped)
      :else               nil)))

(defn resolve-uri
  "Pure-data: source-coord → launchable URI string, or nil. Returns nil
  when the coord lacks `:file`, when `editor-uri/editor-uri` returns nil
  (a forbidden scheme per rf2-vwcsq), or when the resolved URI's scheme
  is outside `editor-uri/allowed-editor-uri-schemes` (the shared
  positive allowlist per rf2-cm93v / rf2-p887o).

  Per rf2-zfy1e: threads the configured project-root through
  `editor-uri/editor-uri`'s 3-arg form so a classpath-relative source-
  coord (the common case — macros capture the form-meta `:file` slot,
  typically classpath-relative) resolves to an absolute on-disk path
  the OS-side editor handler can find. The `:project-root` opt is
  nil-tolerant — when unset, behaviour matches v1 (file ships
  verbatim) so legacy hosts and tests aren't broken.

  The chip render path, `open-source-coord!` (element-inspector), and
  the `:rf.editor/open` reg-fx all call this — one source of truth for
  the URI shape across the data path and the side-effect path. Mirrors
  Causa's `resolve-uri` (rf2-r2un8 port)."
  [source-coord]
  (when (editor-uri/has-source? source-coord)
    (let [opts {:project-root (config/get-project-root)}
          uri  (editor-uri/editor-uri (config/get-editor) source-coord opts)]
      (when (and uri (editor-uri/allowed-uri? uri))
        uri))))

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

  Public so the element-inspector (rf2-h0jc0), the `:rf.editor/open`
  reg-fx (registered in `install!`), and any host panel that wants the
  click-time gate can share the exact same launcher. One launcher, one
  allowlist seam.

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
  `resolve-uri`; click fires `(open! uri)`.

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
   (when-let [uri (resolve-uri source-coord)]
     (let [editor (config/get-editor)
           style  (case variant-of-style
                    :test-detail (:chip-test chip-styles)
                    (:chip chip-styles))]
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
        "open"]))))

(defn open-source-coord!
  "Resolve `source-coord` to an editor URI via the current Story config
  (`config/get-editor` + `config/get-project-root`) and hand it off to
  `open!`. Returns true when the launcher was invoked with an allowed
  URI, false otherwise (missing :file, forbidden scheme, or scheme
  outside the rf2-cm93v allowlist).

  This is the imperative path the element-inspector (rf2-h0jc0) uses
  when the user clicks a DOM element while inspector mode is on. The
  chip's `:on-click` (above) takes the same shape: build URI via
  `resolve-uri`, hand to `open!`.

  `source-coord` shape: `{:file :line :column}` per
  `re-frame.source-coords`."
  [source-coord]
  (when-let [uri (resolve-uri source-coord)]
    (open! uri)
    true))

(defn open-chip-for-variant
  "Render an open-chip for a variant — reads the source-coord off the
  variant body's `:source` slot (the registrar/spec/001-captured map).
  Returns nil when no coord is present.

  `variant-body` is the body map returned by
  `re-frame.story.registrar/handler-meta :variant variant-id`."
  [variant-body]
  (open-chip (:source variant-body) :title))

;; ---- registration: the data-driven open-in-editor path ------------------
;;
;; Per rf2-r2un8 (porting Causa's structure): a dispatch-based path
;; alongside the imperative chip. Hosts that don't render the chip
;; directly — agents replaying via MCP, custom Story-host panels — can
;; dispatch `[:rf.story/open-in-editor coord]` and let the registered
;; fx fire the URI through the same allowlist gate the chip uses. The
;; `:rf.editor/open` reg-fx is namespaced under `:rf.editor/*` (not
;; `:rf.story.fx/*`) because the gate is editor-related, not Story-
;; specific — Causa registers the same fx-id, idempotently. Either tool
;; loading first wins; the registered handler is the same shape so the
;; runtime cost of double-registration is zero.

(defn install!
  "Idempotent install for the dispatch-side open-in-editor wiring
  (rf2-r2un8).

  Registers two framework primitives:

    - `:rf.story/open-in-editor` reg-event-fx — the dispatch shape any
      host panel that wants the trace bus to record the click can fire.
      Accepts either a bare source-coord map or a wrapper
      `{:source-coord <coord-or-string>}`. The handler resolves the URI
      and returns `{:fx [[:rf.editor/open {:uri ...}]]}`.

    - `:rf.editor/open` reg-fx — the side-effectful launcher. Calls
      `open!` (which applies the rf2-cm93v allowlist + writes
      `window.location` via the navigator seam). Shares the
      `:rf.editor/*` namespace with Causa's parallel registration so
      both tools observe a single registered fx-id at runtime; whichever
      preload loads first wins, the handler body is identical so it
      doesn't matter.

  Idempotent — safe to call on every reload. Hosts that drive the
  Story shell don't need to call this directly; the shell mount path
  calls it once at boot.

  The handler does NOT write to `db` — the click is a pure navigation,
  not a state transition. Per Spec 002 §Effect map shape, omitting
  `:db` from the return leaves the app-db untouched."
  []
  ;; ---- :rf.editor/open ----
  ;;
  ;; Side-effect handler. Two arg shapes accepted:
  ;;
  ;;   {:uri "vscode://..."}                 — pre-resolved URI
  ;;   {:source-coord {:file ... :line ...}} — resolve via `resolve-uri`
  ;;                                            first
  ;;
  ;; The pre-resolved form is the canonical shape the
  ;; `:rf.story/open-in-editor` event-fx emits; the `:source-coord`
  ;; form is a convenience for callers that want one-step dispatch.
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args]
      (let [uri (or (:uri args)
                    (when-let [coord (:source-coord args)]
                      (resolve-uri coord)))]
        (open! uri))))

  ;; ---- :rf.story/open-in-editor ----
  ;;
  ;; Event handler. Accepts the same coord-shape `coerce-coord`
  ;; recognises (bare map, `{:source-coord ...}` wrapper, or a
  ;; `"file:line"` display string). Resolves the URI through the
  ;; allowlist seam and routes it to `:rf.editor/open`.
  (rf/reg-event-fx :rf.story/open-in-editor
    (fn [_ctx [_event-id payload]]
      (let [coord (coerce-coord payload)
            uri   (resolve-uri coord)]
        ;; Always emit the fx — even when uri is nil. `open!` is a
        ;; no-op for nil, and routing through the fx (rather than
        ;; short-circuiting in the handler) keeps the side-effect
        ;; bookkeeping in one place + makes the fx the single
        ;; instrumentable seam for replay/dev-tools.
        {:fx [[:rf.editor/open {:uri uri}]]}))))
