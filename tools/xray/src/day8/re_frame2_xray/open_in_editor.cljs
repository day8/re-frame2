(ns day8.re-frame2-xray.open-in-editor
  "Xray-side 'Open in editor' affordance (rf2-evgf5, rf2-g5q8d).

  Mirrors `re-frame.story.ui.open-in-editor` — same chip shape, reads
  Xray's editor preference from `day8.re-frame2-xray.config`.

  This ns owns three surfaces:

    1. `open-chip` — render an `<a>` hiccup chip for a source-coord.
       Used by demo surfaces + any panel that wants the chip's exact
       presentation. The `:on-click` fires the OS scheme handler
       directly via `Location.assign` (per rf2-muvs8).

    2. `:rf.xray/open-in-editor` reg-event — the panel-side
       dispatch shape (`[:rf.xray/open-in-editor coord]` or
       `[:rf.xray/open-in-editor {:source-coord coord}]`). Panels
       render their own button/code/span affordance and dispatch this
       event; the trace bus then captures the click as a first-class
       observable operation under the `:rf/xray` frame. The handler
       returns an effect map that fires `:rf.editor/open` with the
       resolved URI.

    3. `:rf.editor/open` reg-fx — the side-effectful launcher. Resolves
       the URI from the source-coord against `config/get-editor`,
       re-applies the rf2-vwcsq scheme denylist, then calls
       `Location.assign` (per rf2-muvs8). Standalone so non-Xray
       callers (e.g. a future test-mode shortcut, MCP-side open-uri
       replay) can share the gate.

  Per rf2-g5q8d (P0 — the panel chip was a no-op previously). The
  earlier wiring routed clicks to a stub `reg-event` that recorded
  the coord into app-db and did nothing else; this ns now owns the
  full data-driven path end-to-end.

  ## Scheme-rejection denylist (rf2-vwcsq, rf2-ox357n)

  `editor-uri/editor-uri` rejects `javascript:` / `data:` / `vbscript:`
  for `{:custom ...}` templates at build time (per rf2-vwcsq) — the
  spec-mandated scheme-rejection list (Security.md / Tool-Pair.md
  §Editor URI scheme allowlist): everything other than the three
  known-bad schemes passes through. The click-time `open!` seam below
  re-applies the same cheap denylist (`editor-uri/forbidden-scheme?`)
  because the `:rf.editor/open` reg-fx accepts a pre-resolved
  `{:uri ...}` arg that bypasses `editor-uri`'s build-time gating — the
  denylist must fire at every handoff. Per rf2-ox357n the prior positive
  allowlist was removed: it failed CLOSED on any uncatalogued editor
  scheme (a silent dead button), the exact friction the spec forbids.
  Unknown custom non-dangerous schemes now pass through; only
  `javascript:` / `data:` / `vbscript:` stay blocked everywhere."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack]]
            [re-frame.source-coords.editor-uri :as editor-uri]
            [re-frame.source-coords.open-endpoint :as open-endpoint]))

;; ---- styling -------------------------------------------------------------

(def ^:private chip-styles
  ;; Resolved through `theme/tokens` per rf2-5kfxe.4 — the palette has
  ;; exactly one source of truth. Inline styles for now; the CSS-
  ;; variable migration is the v1 styling pass.
  {:chip {:padding         "1px 8px"
          :background      "transparent"
          :color           (:accent tokens)
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
  when the coord lacks `:file` or when `editor-uri/editor-uri` returns
  nil (a forbidden scheme per rf2-vwcsq — `javascript:` / `data:` /
  `vbscript:`). Per rf2-ox357n there is no positive allowlist: any
  non-dangerous scheme (built-in or unknown custom) passes through.

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
    (let [opts {:project-root (config/get-project-root)}]
      ;; `editor-uri` already denylist-gates the resolved URI inline at
      ;; build time (rf2-vwcsq), returning nil on a forbidden scheme.
      (editor-uri/editor-uri (config/get-editor) source-coord opts))))

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

  Per rf2-vwcsq + rf2-ox357n: this fn re-applies the cheap scheme
  denylist (`editor-uri/forbidden-scheme?`) before handing the URI off.
  A URI built via `resolve-uri` was already denylist-gated at build
  time, but the `:rf.editor/open` reg-fx also accepts a pre-resolved
  `{:uri ...}` arg that bypasses build-time gating — so the denylist
  must fire here too. A URI on a forbidden scheme (`javascript:` /
  `data:` / `vbscript:`, case-insensitive, leading-whitespace tolerant)
  is a click-time no-op. Per rf2-ox357n there is no positive allowlist:
  unknown custom non-dangerous schemes navigate as the developer
  intended (the spec mandates a rejection list, not an allowlist).

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
  (when (and uri (not (editor-uri/forbidden-scheme? uri)))
    (js/console.log "[rf.xray/open-in-editor] navigating to:" uri)
    (@navigator uri)
    nil))

;; ---- Option B: prefer the dev-server endpoint (rf2-wn3bh) -----------------
;;
;; The JS-ecosystem-standard jump-to-source path (Vite /__open-in-editor,
;; react-dev-utils, Next) is a dev-server endpoint that resolves the
;; (classpath-relative) source-coord against the live source-paths on the
;; dev machine at runtime and launches the editor via launch-editor. This
;; is ADDITIVE: `open-coord!` first tries the endpoint
;; (`open-endpoint/try-endpoint!`); on any failure (no dev server, network
;; error, non-2xx — static export / non-shadow host / production
;; inspection) it falls back to navigating the historic `editor://` URI via
;; `open!`. The URI path is never removed.

(defn open-coord!
  "Open `source-coord` in the configured editor, PREFERRING the dev-server
  endpoint (Option B, rf2-wn3bh) and FALLING BACK to the `editor://` URI
  navigation (`open!`) when no dev server is present.

  The source-coord is sent verbatim to the endpoint (the server resolves a
  classpath-relative `:file` at runtime); the URI fallback resolves the
  coord through `resolve-uri` (rf2-wvsxg absolutisation + the build-time
  scheme denylist). When the coord cannot produce a usable URI either,
  the fallback is a harmless no-op. Returns nothing."
  [source-coord]
  (open-endpoint/open-coord!
    source-coord
    (config/get-editor)
    (fn [] (open! (resolve-uri source-coord))))
  nil)

;; ---- click routing: configured/hint decision (rf2-r4q6y3) ----------------

(defn chip-click!
  "Route a direct `open-chip` click through the SAME configured/hint
  decision the `:rf.xray/open-in-editor` event-fx applies (rf2-r4q6y3).

  Before this seam the chip's `:on-click` called `open!` directly,
  bypassing the rf2-4s08ov hint: an unconfigured host (never called
  `(xray-config/configure! {:rf.xray/editor …})`, no operator override)
  would silently navigate to the implicit `vscode:` URI and — if VS
  Code is not the developer's editor — the click was a SILENT no-op
  with no feedback. The panel-side dispatch path already routes that
  case to the hint toast; the in-DOM chip now matches.

  Two-tier behaviour:

    1. **Editor configured** (`config/editor-configured?` true — host
       set `:rf.xray/editor` OR a valid operator override exists): open
       the editor via `open-coord!`, which PREFERS the dev-server
       endpoint (Option B, rf2-wn3bh) and FALLS BACK to the `editor://`
       URI. The hint never enters the path.

    2. **Editor NOT configured.** The chip needs a dispatch target for
       the hint toast, which lives on the `:rf/xray` shell frame:

         - **`:rf/xray` frame present** (the common case — the chip is
           rendered inside a live Xray shell): dispatch
           `[:rf.xray/editor-hint-show]` on `:rf/xray` so the toast
           appears, consistent with the panel-side event-fx (rf2-4s08ov)
           and #3486. No silent navigation.

         - **No `:rf/xray` frame** (the standalone fallback — a static
           page / non-Xray host rendering the chip with no shell runtime,
           so no toast can mount): fall back to `open-coord!` (endpoint
           then URI). This is the documented standalone contract — there
           is nowhere for the hint to land, so the historic best-effort
           launch is the only sensible behaviour.

  `source-coord` may be unresolvable — `open-coord!`'s URI fallback is a
  no-op for a coord with no usable `:file`, so an unresolvable coord in
  the configured case is a harmless no-op."
  [source-coord]
  (if (config/editor-configured?)
    (open-coord! source-coord)
    (if (frame/frame defaults/default-frame-id)
      (rf/dispatch [:rf.xray/editor-hint-show] {:frame defaults/default-frame-id})
      ;; Standalone fallback — no shell frame to host the hint toast.
      (open-coord! source-coord)))
  nil)

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'open' chip for a Xray source-coord. Reads the current
  editor preference from `config/get-editor`; builds the URI via
  `editor-uri/editor-uri`; click routes through `chip-click!`, which
  applies the rf2-4s08ov configured/hint decision (rf2-r4q6y3): a
  configured editor navigates via `open!`, an unconfigured host with a
  live `:rf/xray` shell shows the editor-hint toast instead of silently
  no-oping, and a standalone host with no shell falls back to `open!`.

  Returns nil when the source-coord lacks a usable `:file` slot or when
  `editor-uri/editor-uri` returns nil (a forbidden scheme rejected by
  the `editor-uri`-side denylist per rf2-vwcsq). Per rf2-ox357n there is
  no positive allowlist — any non-dangerous scheme produces a chip. The
  UI hides the chip rather than rendering an unclickable affordance.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords`. Xray receives source-coords on the trace
  events it buffers (`:source-coord` slot) and on the registry's
  `(rf/handler-meta kind id)` reads."
  [source-coord]
  (when-let [uri (resolve-uri source-coord)]
    (let [editor (config/get-editor)]
      [:a {:style       (:chip chip-styles)
           :href        uri
           :title       (editor-uri/open-button-title source-coord)
           :data-testid "xray-open-in-editor"
           :data-editor (cond
                          (map? editor) "custom"
                          :else         (name editor))
           :on-click    (fn [e]
                          ;; Stop the browser from trying to render
                          ;; the custom URI inline; route the handoff
                          ;; through `chip-click!` so the rf2-4s08ov
                          ;; configured/hint decision applies (rf2-r4q6y3)
                          ;; — an unconfigured host shows the hint toast
                          ;; instead of silently navigating to the
                          ;; implicit `vscode:` URI. `chip-click!` opens
                          ;; via `open-coord!`, which prefers the
                          ;; dev-server endpoint (Option B, rf2-wn3bh)
                          ;; and falls back to this `:href`'s `editor://`
                          ;; URI when no dev server is present.
                          (.preventDefault e)
                          (chip-click! source-coord))}
       "open"])))

;; ---- registration: the data-driven open-in-editor path ------------------

(defn install!
  "Idempotent install for the panel-side dispatch wiring (rf2-g5q8d).

  Registers two framework primitives:

    - `:rf.xray/open-in-editor` reg-event — the dispatch shape the
      four panels (trace, issues-ribbon, mcp-server, hydration-
      debugger) use when their source-coord affordance is clicked.
      The handler unwraps the payload, resolves the URI, and returns
      `{:fx [[:rf.editor/open {:uri ...}]]}`. The dispatch lands in
      the trace bus as a first-class observable operation under the
      `:rf/xray` frame — agents reading the buffer see the click as
      `{:operation :rf.xray/open-in-editor :tags {:frame :rf/xray}
        ...}` rather than as a silent `window.location` write.

    - `:rf.editor/open` reg-fx — the side-effectful launcher. Calls
      `open!` (which re-applies the rf2-vwcsq scheme denylist + writes
      `window.location.href`). Lives under the `:rf.editor/*` prefix
      rather than `:rf.xray.fx/*` because the gate is editor-related,
      not Xray-specific — a future Story / re-frame2-pair caller can fire
      `[:rf.editor/open {:uri ...}]` and share the same denylist
      seam.

  Called from `registry.cljs/register-xray-handlers!` alongside the
  per-panel `install!` fns."
  []
  ;; ---- :rf.editor/open ----
  ;;
  ;; The side-effect handler. Arg shapes accepted:
  ;;
  ;;   {:source-coord {:file ... :line ...}} — PREFERRED (rf2-wn3bh):
  ;;       opens via `open-coord!`, which tries the dev-server endpoint
  ;;       first (Option B) then falls back to the `editor://` URI.
  ;;   {:uri "vscode://..."}                 — pre-resolved URI only;
  ;;       navigates the URI directly via `open!` (the endpoint needs
  ;;       the structured coord, so a uri-only arg uses the URI path).
  ;;
  ;; Per rf2-wn3bh the event-fx now emits `:source-coord` (not `:uri`)
  ;; so the endpoint is preferred; the `:uri` shape stays for callers
  ;; that hold a pre-resolved URI (e.g. an MCP-side open-uri replay).
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args]
      (if-let [coord (:source-coord args)]
        (open-coord! coord)
        (open! (:uri args)))))

  ;; ---- :rf.xray/open-in-editor ----
  ;;
  ;; Pre-rf2-g5q8d this was a `reg-event` stub that recorded the
  ;; coord into app-db and did nothing else; the editor never opened.
  ;; The handler now resolves the URI through the denylist seam and
  ;; routes it to `:rf.editor/open`.
  ;;
  ;; The handler does NOT write to `db` — the click is a pure
  ;; navigation, not a state transition. Per Spec 002 §Effect map
  ;; shape, omitting `:db` from the return leaves Xray's app-db
  ;; untouched. The trace bus still records the dispatch so the
  ;; "click → open" trail is observable.
  (rf/reg-event :rf.xray/open-in-editor
    (fn [_ctx [_event-id payload]]
      ;; rf2-4s08ov — DX hint when no editor is effectively configured.
      ;; A host that wired only the bare preload (never called
      ;; `(xray-config/configure! {:rf.xray/editor …})`) and an operator
      ;; who never set an override are both targeting the implicit
      ;; framework default `:vscode`. The URI resolves fine and
      ;; `Location.assign` fires — but if VS Code is not the developer's
      ;; editor the OS has no `vscode:` handler and the click is a
      ;; silent no-op (rf2-ffijtp). Instead of that silent navigation,
      ;; surface the 'pick an editor in Settings' toast so the chip
      ;; itself guides the developer. Once EITHER the host or the
      ;; operator has confirmed an editor (`config/editor-configured?`),
      ;; the click resolves + navigates exactly as before — the hint
      ;; never fires.
      (if-not (config/editor-configured?)
        {:fx [[:dispatch [:rf.xray/editor-hint-show]]]}
        (let [coord (coerce-coord payload)]
          ;; Always emit the fx — even when the coord is unresolvable.
          ;; `open-coord!`'s URI fallback is a no-op for a coord with no
          ;; usable `:file`, and routing through the fx (rather than
          ;; short-circuiting in the handler) keeps the side-effect
          ;; bookkeeping in one place + makes the fx the single
          ;; instrumentable seam for replay/dev-tools.
          ;;
          ;; Per rf2-wn3bh emit the structured `:source-coord` (not a
          ;; pre-resolved `:uri`) so `:rf.editor/open` can prefer the
          ;; dev-server endpoint and fall back to the URI.
          {:fx [[:rf.editor/open {:source-coord coord}]]})))))
