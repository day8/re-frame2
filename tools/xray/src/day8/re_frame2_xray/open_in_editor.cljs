(ns day8.re-frame2-xray.open-in-editor
  "Xray-side 'Open in editor' affordance.

  Mirrors `re-frame.story.ui.open-in-editor` — same chip shape, reads
  Xray's editor preference from `day8.re-frame2-xray.config`.

  This ns owns three surfaces:

    1. `open-chip` — render an `<a>` hiccup chip for a source-coord.
       Used by demo surfaces + any panel that wants the chip's exact
       presentation. The `:on-click` fires the OS scheme handler
       directly via `Location.assign`.

    2. `:rf.xray/open-in-editor` reg-event — the panel-side
       dispatch shape (`[:rf.xray/open-in-editor coord]` or
       `[:rf.xray/open-in-editor {:source-coord coord}]`). Panels
       render their own button/code/span affordance and dispatch this
       event; the trace bus then captures the click as a first-class
       observable operation under the `:rf/xray` frame. The handler
       returns an effect map that fires `:rf.xray.fx/open-in-editor`
       with the resolved URI.

    3. `:rf.xray.fx/open-in-editor` reg-fx — the side-effectful
       launcher. Resolves the URI from the source-coord against
       `config/get-editor`, re-applies the scheme denylist,
       then calls `Location.assign`. Xray-owned: the effect closes over
       Xray's editor, project-root and navigator, so it is scoped under
       `:rf.xray.fx/*` like every other Xray fx rather than a shared
       cross-tool id.

  This ns owns the full data-driven path end-to-end.

  ## Scheme-rejection denylist

  `rf.source-coords.editor-uri/editor-uri` rejects `javascript:` / `data:` / `vbscript:`
  for `{:custom ...}` templates at build time — the
  spec-mandated scheme-rejection list (Security.md / Tool-Pair.md
  §Editor URI scheme allowlist): everything other than the three
  known-bad schemes passes through. The click-time `open!` seam below
  re-applies the same cheap denylist (`rf.source-coords.editor-uri/forbidden-scheme?`)
  because the `:rf.xray.fx/open-in-editor` reg-fx accepts a pre-resolved
  `{:uri ...}` arg that bypasses `editor-uri`'s build-time gating — the
  denylist must fire at every handoff. There is deliberately no positive
  allowlist: one would fail CLOSED on any uncatalogued editor scheme (a
  silent dead button), the exact friction the spec forbids. Unknown
  custom non-dangerous schemes pass through; only
  `javascript:` / `data:` / `vbscript:` are blocked everywhere."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]))

;; ---- styling -------------------------------------------------------------

(def ^:private chip-styles
  ;; Resolved through `theme/tokens` — the palette has
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
  structured source-coord map shape `rf.source-coords.editor-uri/editor-uri` expects.

  Two panel-side projection helpers (`trace_helpers.cljc`,
  `issues_ribbon_helpers.cljc`) flatten the structured coord to a
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

    - A bare structured map `{:file ... :line ...}` (defensive —
      nothing dispatches it BARE, but it is the shape the wrapper below
      carries, so this branch is live as the unwrapped tail of that
      path.)
    - A wrapper map `{:source-coord <coord>}` where `<coord>` is
      either a structured map OR a `\"file:line\"` display string.
      THIS is the live shape: the two shared affordances
      (`panels.shared.coord-chip`, `panels.shared.coord-link`) own the
      only dispatch sites in the tree and both emit it. Projection
      helpers flatten the coord at row-projection time so the chip can
      render `\"x.cljs:42\"`.
    - A bare display string `\"file:line\"` (defensive — no panel
      currently dispatches this directly, but the parser handles it).

  Returns the map form; callers feed it to `rf.source-coords.editor-uri/editor-uri`
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
  when the coord lacks `:file` or when `rf.source-coords.editor-uri/editor-uri` returns
  nil (a forbidden scheme — `javascript:` / `data:` /
  `vbscript:`). There is no positive allowlist: any
  non-dangerous scheme (built-in or unknown custom) passes through.

  Threads the configured project-root through
  `rf.source-coords.editor-uri/editor-uri`'s 3-arg form so a classpath-relative source-
  coord (the common case — macros capture the form-meta `:file` slot,
  typically classpath-relative) resolves to an absolute on-disk path
  the OS-side editor handler can find. The `:project-root` opt is
  nil-tolerant — when unset, the file ships verbatim, exactly as the
  2-arg call ships it.

  The chip render path and the `:rf.xray.fx/open-in-editor` reg-fx both call this
  — one source of truth for the URI shape across the data path and the
  side-effect path."
  [source-coord]
  (when (rf.source-coords.editor-uri/has-source? source-coord)
    (let [opts {:project-root (config/get-project-root)}]
      ;; `editor-uri` already denylist-gates the resolved URI inline at
      ;; build time, returning nil on a forbidden scheme.
      (rf.source-coords.editor-uri/editor-uri (config/get-editor) source-coord opts))))

;; ---- side-effect: open the editor ----------------------------------------
;;
;; The navigator seam is held in an atom so tests can swap
;; it without trying to override `window.location` (which is non-
;; configurable in modern browsers and throws under `defineProperty`).
;; Production code uses the default `default-navigator!`; tests rebind
;; via `set-navigator!` to a capturing stub.

(defn- default-navigator!
  "Default navigation seam: `(.assign js/window.location uri)`.

  Uses `(.assign location uri)` rather than
  `(set! (.-location js/window) uri)`.
  Both should be semantically equivalent — the property-setter on
  `window.location` calls `Location.assign` internally per the HTML
  spec — but some Chromium builds on Windows have been
  observed to silently no-op the property-assignment form for non-
  http(s) schemes while honouring the explicit `.assign` call from
  the same click handler. `.assign` is the more reliable seam."
  [uri]
  (.assign (.-location js/window) uri))

(defonce ^:private navigator
  ;; Held in an atom so tests can swap (`set-navigator!`). Production
  ;; code never reassigns it.
  (atom default-navigator!))

(defn set-navigator!
  "Replace the navigation seam used by `open!`. Returns the previous
  seam so tests can restore it. Test-only — production callers MUST
  NOT call this."
  [f]
  (let [prev @navigator]
    (reset! navigator f)
    prev))

(defn open!
  "Navigate `js/window.location` to `uri` via the configured navigator
  seam (default `Location.assign`). Custom URI schemes hand off to
  the OS handler chain. Returns nothing.

  This fn re-applies the cheap scheme
  denylist (`rf.source-coords.editor-uri/forbidden-scheme?`) before handing the URI off.
  A URI built via `resolve-uri` was already denylist-gated at build
  time, but the `:rf.xray.fx/open-in-editor` reg-fx also accepts a pre-resolved
  `{:uri ...}` arg that bypasses build-time gating — so the denylist
  must fire here too. A URI on a forbidden scheme (`javascript:` /
  `data:` / `vbscript:`, case-insensitive, leading-whitespace tolerant)
  is a click-time no-op. There is no positive allowlist:
  unknown custom non-dangerous schemes navigate as the developer
  intended (the spec mandates a rejection list, not an allowlist).

  Emits a `console.log` of the URI before navigation so
  developers can diagnose silent OS-handler failures (relative paths,
  unregistered protocol handlers, etc.) without needing a debugger
  break. The log is a single line per click — low noise.

  Navigation goes through `@navigator` (the atom-held
  seam, default `default-navigator!`) rather than a direct
  `(.assign js/window.location uri)` call, so tests can stub the
  navigation without mutating `js/window.location` (which is
  non-configurable in modern browsers).

  Public so the `:rf.xray.fx/open-in-editor` reg-fx (registered in `install!`) can
  share the exact same gate the in-DOM chip uses."
  [uri]
  (when (and uri (not (rf.source-coords.editor-uri/forbidden-scheme? uri)))
    (js/console.log "[rf.xray/open-in-editor] navigating to:" uri)
    (@navigator uri)
    nil))

;; ---- Option B: prefer the dev-server endpoint -----------------------------
;;
;; The JS-ecosystem-standard jump-to-source path (Vite /__open-in-editor,
;; react-dev-utils, Next) is a dev-server endpoint that resolves the
;; (classpath-relative) source-coord against the live source-paths on the
;; dev machine at runtime and launches the editor via launch-editor. This
;; is ADDITIVE: `open-coord!` first tries the endpoint
;; (`open-endpoint/try-endpoint!`); on any failure (no dev server, network
;; error, non-2xx — static export / non-shadow host / production
;; inspection) it falls back to navigating the `editor://` URI via
;; `open!`, which stays the always-available path.

(defn open-coord!
  "Open `source-coord` in the configured editor, PREFERRING the dev-server
  endpoint (Option B) and FALLING BACK to the `editor://` URI
  navigation (`open!`) when no dev server is present.

  The source-coord is sent verbatim to the endpoint (the server resolves a
  classpath-relative `:file` at runtime); the URI fallback resolves the
  coord through `resolve-uri` (project-root absolutisation + the build-time
  scheme denylist). When the coord cannot produce a usable URI either,
  the fallback is a harmless no-op. Returns nothing."
  [source-coord]
  (rf.source-coords.open-endpoint/open-coord!
    source-coord
    (config/get-editor)
    (fn [] (open! (resolve-uri source-coord))))
  nil)

;; ---- click routing: configured/hint decision ----------------------------

(defn chip-click!
  "Route a direct `open-chip` click through the SAME configured/hint
  decision the `:rf.xray/open-in-editor` event-fx applies.

  A chip `:on-click` calling `open!` directly would bypass the editor
  hint: an unconfigured host (never called
  `(xray-config/configure! {:rf.xray/editor …})`, no operator override)
  would silently navigate to the implicit `vscode:` URI and — if VS
  Code is not the developer's editor — the click would be a SILENT
  no-op with no feedback. The panel-side dispatch path routes that
  case to the hint toast, and the in-DOM chip matches it.

  Two-tier behaviour:

    1. **Editor configured** (`config/editor-configured?` true — host
       set `:rf.xray/editor` OR a valid operator override exists): open
       the editor via `open-coord!`, which PREFERS the dev-server
       endpoint (Option B) and FALLS BACK to the `editor://`
       URI. The hint never enters the path.

    2. **Editor NOT configured.** The chip needs a dispatch target for
       the hint toast, which lives on the `:rf/xray` shell frame:

         - **`:rf/xray` frame present** (the common case — the chip is
           rendered inside a live Xray shell): dispatch
           `[:rf.xray/editor-hint-show]` on `:rf/xray` so the toast
           appears, consistent with the panel-side event-fx. No silent
           navigation.

         - **No `:rf/xray` frame** (the standalone fallback — a static
           page / non-Xray host rendering the chip with no shell runtime,
           so no toast can mount): fall back to `open-coord!` (endpoint
           then URI). This is the documented standalone contract — there
           is nowhere for the hint to land, so the best-effort launch is
           the only sensible behaviour.

  `source-coord` may be unresolvable — `open-coord!`'s URI fallback is a
  no-op for a coord with no usable `:file`, so an unresolvable coord in
  the configured case is a harmless no-op."
  [source-coord]
  (if (config/editor-configured?)
    (open-coord! source-coord)
    (if (rf.frame/frame defaults/default-frame-id)
      (rf/dispatch [:rf.xray/editor-hint-show] {:frame defaults/default-frame-id})
      ;; Standalone fallback — no shell frame to host the hint toast.
      (open-coord! source-coord)))
  nil)

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'open' chip for a Xray source-coord. Reads the current
  editor preference from `config/get-editor`; builds the URI via
  `rf.source-coords.editor-uri/editor-uri`; click routes through `chip-click!`, which
  applies the configured/hint decision: a
  configured editor navigates via `open!`, an unconfigured host with a
  live `:rf/xray` shell shows the editor-hint toast instead of silently
  no-oping, and a standalone host with no shell falls back to `open!`.

  Returns nil when the source-coord lacks a usable `:file` slot or when
  `rf.source-coords.editor-uri/editor-uri` returns nil (a forbidden scheme rejected by
  the `editor-uri`-side denylist). There is no positive allowlist — any non-dangerous scheme produces a chip. The
  UI hides the chip rather than rendering an unclickable affordance.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords`. Xray receives source-coords on the trace
  events it buffers (`:source-coord` slot) and on the registry's
  `(rf/handler-meta {:source :store :kind kind :id id})` reads."
  [source-coord]
  (when-let [uri (resolve-uri source-coord)]
    (let [editor (config/get-editor)]
      [:a {:style       (:chip chip-styles)
           :href        uri
           :title       (rf.source-coords.editor-uri/open-button-title source-coord)
           :data-testid "xray-open-in-editor"
           :data-editor (cond
                          (map? editor) "custom"
                          :else         (name editor))
           :on-click    (fn [e]
                          ;; Stop the browser from trying to render
                          ;; the custom URI inline; route the handoff
                          ;; through `chip-click!` so the configured/hint
                          ;; decision applies — an unconfigured host
                          ;; shows the hint toast
                          ;; instead of silently navigating to the
                          ;; implicit `vscode:` URI. `chip-click!` opens
                          ;; via `open-coord!`, which prefers the
                          ;; dev-server endpoint (Option B)
                          ;; and falls back to this `:href`'s `editor://`
                          ;; URI when no dev server is present.
                          (.preventDefault e)
                          (chip-click! source-coord))}
       "open"])))

;; ---- registration: the data-driven open-in-editor path ------------------

(defn install!
  "Idempotent install for the panel-side dispatch wiring.

  Registers two framework primitives:

    - `:rf.xray/open-in-editor` reg-event — the dispatch shape a panel
      uses when its source-coord affordance is clicked. The affordance
      is SHARED: `panels.shared.coord-chip` and
      `panels.shared.coord-link` carry the only dispatch sites in the
      tree, so their requirers ARE the roster (Trace, Epoch and
      Reactive today). Read those rather than a list restated here: a
      restated list goes stale as panels change.
      The handler unwraps the payload, resolves the URI, and returns
      `{:fx [[:rf.xray.fx/open-in-editor {:uri ...}]]}`. The dispatch lands in
      the trace bus as a first-class observable operation under the
      `:rf/xray` frame — agents reading the buffer see the click as
      `{:operation :rf.xray/open-in-editor :tags {:frame :rf/xray}
        ...}` rather than as a silent `window.location` write.

    - `:rf.xray.fx/open-in-editor` reg-fx — the side-effectful
      launcher. Calls `open!` (which re-applies the scheme
      denylist + writes `window.location.href`). Xray-owned and
      scoped under `:rf.xray.fx/*` like every other Xray fx.
      It closes over Xray's editor preference, project
      root and navigator seam, so it is NOT interchangeable with
      Story's parallel `:rf.story.fx/open-in-editor` — the two tools
      register distinct ids and neither can commandeer the other's
      policy by load order. What genuinely deserves sharing (URI
      build, scheme denylist, dev-server endpoint fallback) is shared
      as core fns in `re-frame.source-coords.*`, not as a shared
      registration.

  Called from `registry.cljs/register-xray-handlers!` alongside the
  per-panel `install!` fns."
  []
  ;; ---- :rf.xray.fx/open-in-editor ----
  ;;
  ;; The side-effect handler. Arg shapes accepted:
  ;;
  ;;   {:source-coord {:file ... :line ...}} — PREFERRED:
  ;;       opens via `open-coord!`, which tries the dev-server endpoint
  ;;       first (Option B) then falls back to the `editor://` URI.
  ;;   {:uri "vscode://..."}                 — pre-resolved URI only;
  ;;       navigates the URI directly via `open!` (the endpoint needs
  ;;       the structured coord, so a uri-only arg uses the URI path).
  ;;
  ;; The event-fx emits `:source-coord` (not `:uri`) so the endpoint is
  ;; preferred; the `:uri` shape serves callers that hold a
  ;; pre-resolved URI (e.g. an MCP-side open-uri replay).
  (rf/reg-fx :rf.xray.fx/open-in-editor
    (fn [_ctx args]
      (if-let [coord (:source-coord args)]
        (open-coord! coord)
        (open! (:uri args)))))

  ;; ---- :rf.xray/open-in-editor ----
  ;;
  ;; The handler routes the structured coord to
  ;; `:rf.xray.fx/open-in-editor`, which opens it (dev-server endpoint
  ;; first, then the denylist-gated URI).
  ;;
  ;; The handler does NOT write to `db` — the click is a pure
  ;; navigation, not a state transition. Per Spec 002 §Effect map
  ;; shape, omitting `:db` from the return leaves Xray's app-db
  ;; untouched. The trace bus still records the dispatch so the
  ;; "click → open" trail is observable.
  (rf/reg-event :rf.xray/open-in-editor
    (fn [_ctx [_event-id payload]]
      ;; DX hint when no editor is effectively configured.
      ;; A host that wired only the bare preload (never called
      ;; `(xray-config/configure! {:rf.xray/editor …})`) and an operator
      ;; who never set an override are both targeting the implicit
      ;; framework default `:vscode`. The URI resolves fine and
      ;; `Location.assign` fires — but if VS Code is not the developer's
      ;; editor the OS has no `vscode:` handler and the click is a
      ;; silent no-op. Instead of that silent navigation,
      ;; surface the 'pick an editor in Settings' toast so the chip
      ;; itself guides the developer. Once EITHER the host or the
      ;; operator has confirmed an editor (`config/editor-configured?`),
      ;; the click resolves + navigates — the hint never fires.
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
          ;; Emit the structured `:source-coord` (not a
          ;; pre-resolved `:uri`) so `:rf.xray.fx/open-in-editor` can
          ;; prefer the dev-server endpoint and fall back to the URI.
          {:fx [[:rf.xray.fx/open-in-editor {:source-coord coord}]]})))))
