(ns day8.re-frame2-causa.open-in-editor
  "Causa-side 'Open in editor' affordance (rf2-evgf5, rf2-g5q8d).

  Mirrors `re-frame.story.ui.open-in-editor` — same chip shape, reads
  Causa's editor preference from `day8.re-frame2-causa.config`.

  This ns owns three surfaces:

    1. `open-chip` — render an `<a>` hiccup chip for a source-coord.
       Used by demo surfaces + any panel that wants the chip's exact
       presentation. The `:on-click` fires the OS scheme handler
       directly via `Location.assign` (per rf2-muvs8).

    2. `:rf.causa/open-in-editor` reg-event-fx — the panel-side
       dispatch shape (`[:rf.causa/open-in-editor coord]` or
       `[:rf.causa/open-in-editor {:source-coord coord}]`). Panels
       render their own button/code/span affordance and dispatch this
       event; the trace bus then captures the click as a first-class
       observable operation under the `:rf/causa` frame. The handler
       returns an effect map that fires `:rf.editor/open` with the
       resolved URI.

    3. `:rf.editor/open` reg-fx — the side-effectful launcher. Resolves
       the URI from the source-coord against `config/get-editor`,
       applies the rf2-cm93v positive allowlist, then calls
       `Location.assign` (per rf2-muvs8). Standalone so non-Causa
       callers (e.g. a future test-mode shortcut, MCP-side open-uri
       replay) can share the gate.

  Per rf2-g5q8d (P0 — the panel chip was a no-op previously). The
  earlier wiring routed clicks to a stub `reg-event-db` that recorded
  the coord into app-db and did nothing else; this ns now owns the
  full data-driven path end-to-end.

  ## Defense-in-depth scheme allowlist (rf2-cm93v / rf2-p887o)

  `editor-uri/editor-uri` already rejects `javascript:` / `data:` /
  `vbscript:` for `{:custom ...}` templates (per rf2-vwcsq). The
  click-time seam below layers a positive-allowlist gate on top
  (`editor-uri/allowed-uri?`): before assigning to `window.location`
  we verify the final URI's scheme is in
  `editor-uri/allowed-editor-uri-schemes`. Anything outside the set
  (in particular `http:` / `https:` / new bad schemes we did not
  anticipate) is rejected at the click-time seam. Per spec/Security.md
  §Pragmatic stance the rationale is 'gate accidents, not theoretical
  attacks' — a custom template that resolves to `https://...` would
  navigate the page rather than launch an editor; the allowlist makes
  that an obvious no-op rather than a silent surprise.

  The allowlist itself lives in the shared `editor-uri` ns (rf2-p887o)
  so Story consumes the same predicate Causa does."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack]]
            [re-frame.source-coords.editor-uri :as editor-uri]))

;; ---- styling -------------------------------------------------------------

(def ^:private chip-styles
  ;; Resolved through `theme/tokens` per rf2-5kfxe.4 — the palette has
  ;; exactly one source of truth. Inline styles for now; the CSS-
  ;; variable migration is the v1 styling pass.
  {:chip {:padding         "1px 8px"
          :background      "transparent"
          :color           (:accent-violet tokens)
          :border          (str "1px solid " (:border-default tokens))
          :border-radius   "3px"
          :cursor          "pointer"
          :font-family     mono-stack
          :font-size       "10px"
          :margin-left     "8px"
          :text-decoration "none"
          :display         "inline-block"
          :line-height     "16px"}})

;; ---- pure: resolve a source-coord to a launchable URI -------------------

(defn- parse-file-line
  "Parse a `\"file:line\"` (or bare `\"file\"`) display string into the
  structured source-coord map shape `editor-uri/editor-uri` expects.

  Three panel-side projection helpers (trace_helpers, issues_ribbon_
  helpers, mcp_server_helpers) flatten the structured coord to a
  display string at projection time so the row's chip can render it.
  When the user clicks the chip, the dispatch ships that display
  string — the handler has to walk back to the structured form to
  build the editor URI.

  The fallback split is on the LAST `:` so a Windows-style
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
  "Normalise the dispatch payload to a structured source-coord map.

  Accepts (in order of preference):

    - A bare structured map `{:file ... :line ...}` (the hydration
      debugger panel's dispatch shape).
    - A wrapper map `{:source-coord <coord>}` where `<coord>` is
      either a structured map OR a `\"file:line\"` display string
      (the trace / issues-ribbon / mcp-server panels' dispatch
      shape — projection helpers flatten the coord at row-projection
      time so the chip can render `\"x.cljs:42\"`).
    - A bare display string `\"file:line\"` (defensive — no panel
      currently dispatches this directly, but the parser handles it).

  Returns the map form; callers feed it to `editor-uri/editor-uri`
  unchanged."
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

  Per rf2-5m5n2: threads the configured project-root through
  `editor-uri/editor-uri`'s 3-arg form so a classpath-relative source-
  coord (the common case — macros capture the form-meta `:file` slot,
  typically classpath-relative) resolves to an absolute on-disk path
  the OS-side editor handler can find. The `:project-root` opt is
  nil-tolerant — when unset, behaviour matches the v1 2-arg call (file
  ships verbatim) so legacy hosts and tests aren't broken.

  The chip render path and the `:rf.editor/open` reg-fx both call this
  — one source of truth for the URI shape across the data path and the
  side-effect path."
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

  Per rf2-vwcsq: `uri` is the return of `editor-uri/editor-uri`, which
  already rejects `javascript:` / `data:` / `vbscript:` schemes by
  returning `nil`. Per rf2-cm93v this fn applies a second, positive
  allowlist gate (`editor-uri/allowed-uri?`) before handing the URI
  off — closing the `http:` / `https:` / unknown-scheme path that a
  `{:custom ...}` template could otherwise resolve to. A rejected URI
  is a click-time no-op (no navigation, no console noise — the chip's
  `(when uri ...)` earlier guard handles the absent case; the
  allowlist handles the shaped-but-disallowed case).

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
  (which is non-configurable in modern browsers).

  Public so the `:rf.editor/open` reg-fx (registered in `install!`) can
  share the exact same gate the in-DOM chip uses."
  [uri]
  (when (and uri (editor-uri/allowed-uri? uri))
    (js/console.log "[rf.causa/open-in-editor] navigating to:" uri)
    (@navigator uri)
    nil))

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'open' chip for a Causa source-coord. Reads the current
  editor preference from `config/get-editor`; builds the URI via
  `editor-uri/editor-uri`; click fires `window.location.href`.

  Returns nil when the source-coord lacks a usable `:file` slot, when
  `editor-uri/editor-uri` returns nil (a scheme rejected by the
  `editor-uri`-side gate per rf2-vwcsq), or when the resolved URI's
  scheme is not in `editor-uri/allowed-editor-uri-schemes` (the
  shared positive allowlist per rf2-cm93v / rf2-p887o). The UI hides
  the chip rather than rendering an unclickable affordance.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords`. Causa receives source-coords on the trace
  events it buffers (`:source-coord` slot) and on the registry's
  `(rf/handler-meta kind id)` reads."
  [source-coord]
  (when-let [uri (resolve-uri source-coord)]
    (let [editor (config/get-editor)]
      [:a {:style       (:chip chip-styles)
           :href        uri
           :title       (editor-uri/open-button-title source-coord)
           :data-testid "causa-open-in-editor"
           :data-editor (cond
                          (map? editor) "custom"
                          :else         (name editor))
           :on-click    (fn [e]
                          ;; Stop the browser from trying to render
                          ;; the custom URI inline; fire the handoff
                          ;; explicitly so the OS scheme handler
                          ;; dispatches.
                          (.preventDefault e)
                          (open! uri))}
       "open"])))

;; ---- registration: the data-driven open-in-editor path ------------------

(defn install!
  "Idempotent install for the panel-side dispatch wiring (rf2-g5q8d).

  Registers two framework primitives:

    - `:rf.causa/open-in-editor` reg-event-fx — the dispatch shape the
      four panels (trace, issues-ribbon, mcp-server, hydration-
      debugger) use when their source-coord affordance is clicked.
      The handler unwraps the payload, resolves the URI, and returns
      `{:fx [[:rf.editor/open {:uri ...}]]}`. The dispatch lands in
      the trace bus as a first-class observable operation under the
      `:rf/causa` frame — agents reading the buffer see the click as
      `{:operation :rf.causa/open-in-editor :tags {:frame :rf/causa}
        ...}` rather than as a silent `window.location` write.

    - `:rf.editor/open` reg-fx — the side-effectful launcher. Calls
      `open!` (which applies the rf2-cm93v allowlist + writes
      `window.location.href`). Lives under the `:rf.editor/*` prefix
      rather than `:rf.causa.fx/*` because the gate is editor-related,
      not Causa-specific — a future Story / re-frame2-pair caller can fire
      `[:rf.editor/open {:uri ...}]` and share the same allowlist
      seam.

  Called from `registry.cljs/register-causa-handlers!` alongside the
  per-panel `install!` fns."
  []
  ;; ---- :rf.editor/open ----
  ;;
  ;; The side-effect handler. Two arg shapes are accepted:
  ;;
  ;;   {:uri "vscode://..."}                — pre-resolved URI
  ;;   {:source-coord {:file ... :line ...}} — resolve via
  ;;                                            `resolve-uri` first
  ;;
  ;; The pre-resolved form is the canonical shape the
  ;; `:rf.causa/open-in-editor` event-fx emits (handler does the
  ;; resolution); the `:source-coord` form is a convenience for
  ;; callers that want one-step dispatch and don't need the resolve
  ;; step visible in the trace.
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args]
      (let [uri (or (:uri args)
                    (when-let [coord (:source-coord args)]
                      (resolve-uri coord)))]
        (open! uri))))

  ;; ---- :rf.causa/open-in-editor ----
  ;;
  ;; Pre-rf2-g5q8d this was a `reg-event-db` stub that recorded the
  ;; coord into app-db and did nothing else; the editor never opened.
  ;; The handler now resolves the URI through the allowlist seam and
  ;; routes it to `:rf.editor/open`.
  ;;
  ;; The handler does NOT write to `db` — the click is a pure
  ;; navigation, not a state transition. Per Spec 002 §Effect map
  ;; shape, omitting `:db` from the return leaves Causa's app-db
  ;; untouched. The trace bus still records the dispatch so the
  ;; "click → open" trail is observable.
  (rf/reg-event-fx :rf.causa/open-in-editor
    (fn [_ctx [_event-id payload]]
      (let [coord (coerce-coord payload)
            uri   (resolve-uri coord)]
        ;; Always emit the fx — even when uri is nil. `open!` is a
        ;; no-op for nil, and routing through the fx (rather than
        ;; short-circuiting in the handler) keeps the side-effect
        ;; bookkeeping in one place + makes the fx the single
        ;; instrumentable seam for replay/dev-tools.
        {:fx [[:rf.editor/open {:uri uri}]]}))))
