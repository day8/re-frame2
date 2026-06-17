(ns re-frame.story.ui.open-in-editor
  "The 'Open in editor' affordance — a small chip / button that opens
  the editor at a source-coord's file:line. Per rf2-evgf5 + Spec 005-
  SOTA-Features.md §'Open in editor' per variant.

  Mirrors `day8.re-frame2-xray.open-in-editor` — same shape, same
  click-time gate, same launcher seam. Per rf2-r2un8: the two surfaces
  were drifting; Xray's structure (resolve-uri helper + dispatch-based
  path + install! registration) is the canonical shape both tools
  consume now. Story keeps its existing public chip API
  (`open-chip` / `open-chip-for-variant` / `open-source-coord!`) but
  delegates URI resolution to `resolve-uri` and adds a parallel
  dispatch path (`:rf.story/open-in-editor` reg-event) so a panel
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

  ## Scheme-rejection denylist (rf2-vwcsq, rf2-ox357n)

  `editor-uri/editor-uri` rejects `javascript:` / `data:` / `vbscript:`
  for `{:custom ...}` templates at build time (per rf2-vwcsq) — the
  spec-mandated scheme-rejection list (Security.md / Tool-Pair.md
  §Editor URI scheme allowlist): everything other than the three
  known-bad schemes passes through. The click-time `open!` seam below
  re-applies the same cheap denylist (`editor-uri/forbidden-scheme?`)
  because the `:rf.editor/open` reg-fx accepts a pre-resolved
  `{:uri ...}` arg that bypasses `editor-uri`'s build-time gating —
  the denylist must fire at every handoff. Per rf2-ox357n the prior
  positive allowlist was removed: it failed CLOSED on any uncatalogued
  editor scheme (a silent dead button), the exact friction the spec
  forbids. Unknown custom non-dangerous schemes now pass through.

  ## Bundle isolation

  Lives in the Story CLJS bundle; production builds elide the entire
  UI shell so this ns never enters a release bundle."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.story.config :as config]
            [re-frame.source-coords.editor-uri :as editor-uri]
            [re-frame.source-coords.open-endpoint :as open-endpoint]
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
;; Per rf2-r2un8 (porting Xray's structure): URI building is extracted
;; into one helper so the chip path and the dispatch path share the same
;; logic — chip's `:href`, chip's `:on-click`, the inspector launcher, and
;; the `:rf.story/open-in-editor` event all call `resolve-uri`.

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
  unchanged. Mirrors Xray's `coerce-coord` (rf2-r2un8 port)."
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
  Xray's `resolve-uri` (rf2-r2un8 port)."
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

  Public so the element-inspector (rf2-h0jc0), the `:rf.editor/open`
  reg-fx (registered in `install!`), and any host panel that wants the
  click-time gate can share the exact same launcher. One launcher, one
  denylist seam.

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
  (which is non-configurable in modern browsers)."
  [uri]
  (when (and uri (not (editor-uri/forbidden-scheme? uri)))
    (js/console.log "[rf.story/open-in-editor] navigating to:" uri)
    (@navigator uri)
    nil))

;; ---- Option B: prefer the dev-server endpoint (rf2-wn3bh) -----------------
;;
;; The JS-ecosystem-standard jump-to-source path (Vite /__open-in-editor,
;; react-dev-utils, Next) is a dev-server endpoint that resolves the
;; (classpath-relative) source-coord against the live source-paths on the
;; dev machine at runtime and launches the editor via launch-editor. This
;; is ADDITIVE: `open-coord!` first tries the endpoint
;; (`open-endpoint/open-coord!`); on any failure (no dev server, network
;; error, non-2xx — static export / non-shadow host / production
;; inspection) it falls back to navigating the historic `editor://` URI via
;; `open!`. The URI path is never removed. Mirrors Xray's `open-coord!`.

(defn open-coord!
  "Open `source-coord` in the configured editor, PREFERRING the dev-server
  endpoint (Option B, rf2-wn3bh) and FALLING BACK to the `editor://` URI
  navigation (`open!`) when no dev server is present.

  The source-coord is sent verbatim to the endpoint (the server resolves a
  classpath-relative `:file` at runtime); the URI fallback resolves the
  coord through `resolve-uri` (rf2-zfy1e project-root prefix + the
  build-time scheme denylist). When the coord cannot produce a usable
  URI either, the fallback is a harmless no-op. Returns nothing."
  [source-coord]
  (open-endpoint/open-coord!
    source-coord
    (config/get-editor)
    (fn [] (open! (resolve-uri source-coord))))
  nil)

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'Open' chip for a source-coord. Reads the current editor
  preference from `config/editor`; constructs the URI via
  `resolve-uri`; click fires `(open! uri)`.

  Returns nil when the source-coord has no usable `:file` slot or when
  `editor-uri/editor-uri` returns nil (a forbidden scheme rejected by
  the `editor-uri`-side denylist per rf2-vwcsq). Per rf2-ox357n there is
  no positive allowlist — any non-dangerous scheme produces a chip. The
  UI hides the chip rather than rendering an unclickable affordance.

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
                           ;; the tab); route through `open-coord!`,
                           ;; which prefers the dev-server endpoint
                           ;; (Option B, rf2-wn3bh) and falls back to
                           ;; this `:href`'s `editor://` URI when no dev
                           ;; server is present.
                           (.preventDefault e)
                           (open-coord! source-coord))}
        "open"]))))

(defn open-source-coord!
  "Open `source-coord` in the configured editor, PREFERRING the dev-server
  endpoint (Option B, rf2-wn3bh) and FALLING BACK to the `editor://` URI
  via `open!`. Returns true when the coord has a usable `:file` (so the
  launch was attempted), false otherwise (missing :file).

  This is the imperative path the element-inspector (rf2-h0jc0) uses
  when the user clicks a DOM element while inspector mode is on. The
  chip's `:on-click` (above) takes the same shape — `open-coord!` is the
  single launcher both paths share.

  `source-coord` shape: `{:file :line :column}` per
  `re-frame.source-coords`."
  [source-coord]
  (if (editor-uri/has-source? source-coord)
    (do (open-coord! source-coord)
        true)
    false))

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
;; Per rf2-r2un8 (porting Xray's structure): a dispatch-based path
;; alongside the imperative chip. Hosts that don't render the chip
;; directly — agents replaying via MCP, custom Story-host panels — can
;; dispatch `[:rf.story/open-in-editor coord]` and let the registered
;; fx fire the URI through the same denylist gate the chip uses. The
;; `:rf.editor/open` reg-fx is namespaced under `:rf.editor/*` (not
;; `:rf.story.fx/*`) because the gate is editor-related, not Story-
;; specific — Xray registers the same fx-id, idempotently. Either tool
;; loading first wins; the registered handler is the same shape so the
;; runtime cost of double-registration is zero.

(defn install!
  "Idempotent install for the dispatch-side open-in-editor wiring
  (rf2-r2un8).

  Registers two framework primitives:

    - `:rf.story/open-in-editor` reg-event — the dispatch shape any
      host panel that wants the trace bus to record the click can fire.
      Accepts either a bare source-coord map or a wrapper
      `{:source-coord <coord-or-string>}`. The handler resolves the URI
      and returns `{:fx [[:rf.editor/open {:uri ...}]]}`.

    - `:rf.editor/open` reg-fx — the side-effectful launcher. Calls
      `open!` (which re-applies the rf2-vwcsq scheme denylist + writes
      `window.location` via the navigator seam). Shares the
      `:rf.editor/*` namespace with Xray's parallel registration so
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
  ;; Side-effect handler. Arg shapes accepted:
  ;;
  ;;   {:source-coord {:file ... :line ...}} — PREFERRED (rf2-wn3bh):
  ;;       opens via `open-coord!`, which tries the dev-server endpoint
  ;;       first (Option B) then falls back to the `editor://` URI.
  ;;   {:uri "vscode://..."}                 — pre-resolved URI only;
  ;;       navigates the URI directly via `open!` (the endpoint needs the
  ;;       structured coord, so a uri-only arg uses the URI path).
  ;;
  ;; Per rf2-wn3bh the event now emits `:source-coord` (not `:uri`) so
  ;; the endpoint is preferred; the `:uri` shape stays for callers that
  ;; hold a pre-resolved URI.
  ;;
  ;; Xray registers the same fx-id idempotently — whichever preload loads
  ;; first wins; the handler body is the same shape so it doesn't matter.
  (rf/reg-fx :rf.editor/open
    (fn [_ctx args]
      (if-let [coord (:source-coord args)]
        (open-coord! coord)
        (open! (:uri args)))))

  ;; ---- :rf.story/open-in-editor ----
  ;;
  ;; Event handler. Accepts the same coord-shape `coerce-coord`
  ;; recognises (bare map, `{:source-coord ...}` wrapper, or a
  ;; `"file:line"` display string). Resolves the URI through the
  ;; denylist seam and routes it to `:rf.editor/open`.
  (rf/reg-event :rf.story/open-in-editor
    (fn [_ctx [_event-id payload]]
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
        {:fx [[:rf.editor/open {:source-coord coord}]]}))))
