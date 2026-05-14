(ns re-frame.source-coords.editor-uri
  "Build an 'open in editor' URI from a source-coord map.

  Tools that surface a `{:file :line :column :ns}` source-coord — Story's
  variant canvas, Story's per-test failure detail, Causa's event-detail
  hero, etc. — wrap the coord in a clickable affordance that launches the
  user's editor at that file:line. The de-facto protocol in 2026 is a
  custom URI scheme per editor:

      vscode://file/<path>:<line>:<column>
      cursor://file/<path>:<line>:<column>
      windsurf://file/<path>:<line>:<column>
      zed://file/<path>:<line>:<column>
      idea://open?file=<path>&line=<line>&column=<column>

  No single scheme covers every editor; the user picks one at boot via a
  configuration key (`:rf.story/editor`, `:rf.causa/editor`). The pure
  helper here builds the URI string; the tool's UI layer attaches it to
  an `<a href>` or fires it via `window.location.href`.

  ## Editor keyword vocabulary

  - `:vscode` (default) — VS Code, the most-installed editor.
  - `:cursor` — Cursor (the VS Code fork).
  - `:windsurf` — Windsurf (a VS Code fork; registers its own
                `windsurf://` handler distinct from VS Code's).
  - `:zed`    — Zed (the `zed://` handler is registered via the
                editor's 'Register Zed Scheme' action; accepts the
                same `file/<path>:<line>:<column>` grammar).
  - `:idea`   — IntelliJ family (IDEA, WebStorm, PyCharm). The single
                `idea://` handler dispatches across all JetBrains IDEs.
  - `{:custom <uri-template>}` — user-supplied template with the
                placeholders `{path}` / `{line}` / `{column}` / `{file}`
                substituted from the source-coord. `{file}` is an alias
                for `{path}` so the template reads naturally either way.

  ## Why custom-template support

  Editor schemes evolve. The 2026 set is vscode/cursor/idea/sublime/
  zed; by 2027 a new editor will ship its own scheme. The custom slot
  means a host doesn't have to wait for an upstream PR to support its
  editor — set `{:custom \"my-editor://open?path={path}&row={line}\"}`
  and tooling picks it up.

  ## Path semantics

  Source-coord `:file` is what the macro layer captured at registration
  time. Under CLJS it's typically `\"path/to/file.cljs\"` relative to the
  classpath root (resolved via the form-meta `:file` slot).
  The editor opens the file from disk, so the URI must carry a path the
  editor's resolver can find.

  v1 ships the file string verbatim and lets the editor resolve it.
  Hosts that need workspace-absolute paths can use `:custom` with a
  prefix template like `\"vscode://file/{HOST_WORKSPACE_ROOT}/{path}:{line}\"`
  — the substitution itself stays pure-data; the host's template
  controls the prefix.

  ## What this namespace deliberately doesn't do

  - No editor detection (no `navigator.userAgent` sniffing) — the user
    picks the editor via config.
  - No fallback URI scheme — if the editor isn't installed, the click
    no-ops cleanly (the OS handler chain returns).
  - No async / Promise — pure data → data, JVM + CLJS portable.
  - No dispatch / re-frame plumbing — the UI layer attaches the URI to
    a DOM node and lets the browser fire it. Keeps this helper tool-
    agnostic so Story + Causa + future tools (Pair? a Helix DevTool?)
    consume it identically.

  ## Source-coord shape

  Matches `re-frame.source-coords` / `re-frame.story.registrar`:

      {:ns sym? :file string? :line int? :column int?}

  `:file` is required for any meaningful URI (an editor URI without a
  file is a no-op). `:line` and `:column` are optional; URIs fall back
  to line 1 / column 1 when absent so the editor at least opens the
  file. Missing `:file` → `nil` URI; the UI layer hides the button."
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- known schemes -------------------------------------------------------

(def ^:const known-editors
  "The keyword set of built-in editor schemes. The `:custom` form is
  not a member — `editor-uri` matches it via map-shape detection."
  #{:vscode :cursor :windsurf :zed :idea})

;; ---- forbidden schemes (rf2-vwcsq) ---------------------------------------
;;
;; Per rf2-vwcsq (pragmatic stance, 2026-05-14): the `{:custom ...}` editor
;; template surface stays — devs legitimately point it at JetBrains URI
;; handlers, sublime, org-tooling deep-links, etc. The minimum gate is a
;; reject-list for the three schemes that turn a "launch the editor"
;; affordance into in-tab script execution via `set! window.location`:
;;
;;   - `javascript:` — runs arbitrary JS in the current origin
;;   - `data:`       — can serve HTML/script that the browser renders inline
;;   - `vbscript:`   — legacy IE script scheme (still honoured in some
;;                     embedded WebView2 / Edge-compat surfaces)
;;
;; The built-in scheme builders (vscode/cursor/windsurf/zed/idea) cannot
;; emit any of these — the reject only ever fires on `{:custom <template>}`.
;; Detection is leading-token-only and case-insensitive so a template
;; whose scheme casing has been munged (`JavaScript:`, `DATA:`, leading
;; whitespace) still trips the gate. A rejected URI causes `editor-uri`
;; to return `nil`; the UI layer's existing `(when uri ...)` wrapper then
;; hides the chip rather than rendering a no-op link.

(def ^:const forbidden-uri-schemes
  "Leading URI schemes the launcher refuses. Matched case-insensitively
  against the URI's leading token (everything up to and including the
  first `:`). Per rf2-vwcsq."
  #{"javascript:" "data:" "vbscript:"})

(defn- forbidden-scheme?
  "Predicate — true iff `uri` carries one of `forbidden-uri-schemes` as
  its leading scheme. Tolerates leading whitespace and arbitrary scheme
  casing. Pure data → boolean.

  Per rf2-vwcsq: gates the `:custom` template surface against the three
  schemes that would turn the editor-launch affordance into in-tab
  script execution."
  [uri]
  (when (string? uri)
    (let [trimmed (str/triml uri)
          lower   (str/lower-case trimmed)]
      (boolean (some #(str/starts-with? lower %) forbidden-uri-schemes)))))

;; ---- positive scheme allowlist (rf2-cm93v / rf2-p887o) -------------------
;;
;; `forbidden-scheme?` (rf2-vwcsq) is a blocklist of three known-bad schemes
;; — it fails open against `http:` / `https:` and any scheme that hasn't
;; been catalogued as bad yet. The launch-time seam in each tool (Story's
;; and Causa's `open!`) layers a positive allowlist on top: anything
;; outside `allowed-editor-uri-schemes` is refused before `window.location`
;; gets assigned. Per spec/Security.md §Pragmatic stance the rationale is
;; "gate accidents, not theoretical attacks" — a `{:custom ...}` template
;; that resolves to `http://...` would navigate the tab rather than launch
;; an editor; the allowlist makes that an obvious no-op rather than a
;; silent surprise.
;;
;; Originally lived in `day8.re-frame2-causa.open-in-editor` (rf2-cm93v);
;; lifted to this shared ns per rf2-p887o so Story consumes the same
;; predicate Causa does.

(def ^:const allowed-editor-uri-schemes
  "Positive allowlist of URI schemes the launcher will hand off to the
  OS. Anything outside the set is refused at each tool's `open!`
  boundary.

  Members:
   - `vscode:`            VS Code
   - `vscode-insiders:`   VS Code Insiders
   - `cursor:`            Cursor (VS Code fork)
   - `windsurf:`          Windsurf (VS Code fork)
   - `zed:`               Zed
   - `idea:`              JetBrains (IDEA, WebStorm, PyCharm, …)
   - `jetbrains:`         JetBrains alt scheme
   - `fleet:`             JetBrains Fleet
   - `subl:`              Sublime Text
   - `emacs:` / `emacsclient:` / `org-protocol:`  Emacs family
   - `vim:` / `nvim:` / `mvim:`                    Vim family
   - `txmt:`              TextMate
   - `atom:`              Atom (legacy but still launchable)
   - `file:`              host-resolved file URL

  Per rf2-cm93v this is intentionally a positive list, not a reject
  list — pre-cm93v the editor-template surface relied on `editor-uri`'s
  three-scheme reject (`javascript:` / `data:` / `vbscript:`) which
  failed open against `http:` / `https:` and any scheme that hadn't
  been catalogued as bad yet."
  #{"vscode" "vscode-insiders"
    "cursor" "windsurf"
    "zed"
    "idea" "jetbrains" "fleet"
    "subl"
    "emacs" "emacsclient" "org-protocol"
    "vim" "nvim" "mvim"
    "txmt"
    "atom"
    "file"})

(defn- uri-scheme
  "Return the URI's leading scheme as a lower-case string, sans the
  trailing `:`. Returns nil when `uri` is not a string, has no scheme,
  or the scheme is empty.

  Tolerates leading whitespace so an attacker template like
  `\" javascript:...\"` (which would `triml` away before the browser
  parsed it) still produces the right scheme for the allowlist check."
  [uri]
  (when (string? uri)
    (let [trimmed  (str/triml uri)
          colon-ix (str/index-of trimmed ":")]
      (when (and colon-ix (pos? colon-ix))
        (str/lower-case (subs trimmed 0 colon-ix))))))

(defn allowed-uri?
  "Predicate — true iff `uri`'s scheme is in `allowed-editor-uri-schemes`.
  Pure data → boolean; safe to call from tests and from the click-time
  guard in each tool's `open!`. Per rf2-cm93v: positive allowlist,
  defense-in-depth alongside `editor-uri`'s three-scheme reject.

  Per rf2-p887o lives here (not in Causa) so Story consumes the same
  predicate — both surfaces gate `{:custom ...}` templates identically."
  [uri]
  (boolean
    (when-let [scheme (uri-scheme uri)]
      (contains? allowed-editor-uri-schemes scheme))))

(def ^:private default-editor
  "Default editor when no `:editor` config key is set. VS Code is the
  most-installed editor in 2026 (Stack Overflow Developer Survey 2025
  + JetBrains DevEcosystem 2025 both put it >70%)."
  :vscode)

;; ---- pure: source-coord normalisation -----------------------------------

(defn- coord-line
  "Pull a usable `:line` value out of `source-coord`. Returns `1` when
  `:line` is missing — editor URIs without a line scheme still resolve
  to the file's first line."
  [source-coord]
  (or (:line source-coord) 1))

(defn- coord-column
  "Pull a usable `:column` value out of `source-coord`. Returns `1` when
  `:column` is missing — editor URIs without a column resolve to the
  start of the line."
  [source-coord]
  (or (:column source-coord) 1))

(defn- coord-file
  "Return the `:file` slot of `source-coord`, or `nil` when absent / blank.
  Callers test the return value: a nil file → nil URI → the UI hides the
  button."
  [source-coord]
  (let [f (:file source-coord)]
    (when (and (string? f) (not (str/blank? f)))
      f)))

;; ---- pure: scheme builders ----------------------------------------------

(defn- vscode-uri
  "Build a `vscode://file/<path>:<line>:<column>` URI."
  [path line column]
  (str "vscode://file/" path ":" line ":" column))

(defn- cursor-uri
  "Build a `cursor://file/<path>:<line>:<column>` URI. Cursor inherits
  VS Code's URI grammar, only the scheme differs."
  [path line column]
  (str "cursor://file/" path ":" line ":" column))

(defn- windsurf-uri
  "Build a `windsurf://file/<path>:<line>:<column>` URI. Windsurf is a
  VS Code fork that registers its own URI handler — same colon-suffix
  grammar, distinct scheme."
  [path line column]
  (str "windsurf://file/" path ":" line ":" column))

(defn- zed-uri
  "Build a `zed://file/<path>:<line>:<column>` URI. Zed's `zed://`
  handler accepts the VS Code colon-suffix grammar (registered via
  Zed's 'Register Zed Scheme' action)."
  [path line column]
  (str "zed://file/" path ":" line ":" column))

(defn- idea-uri
  "Build an `idea://open?file=<path>&line=<line>&column=<column>` URI.
  The JetBrains scheme uses query parameters rather than the
  colon-suffix form."
  [path line column]
  (str "idea://open?file=" path "&line=" line "&column=" column))

;; ---- pure: custom-template substitution ---------------------------------

(defn- substitute-template
  "Substitute `{path}` / `{file}` / `{line}` / `{column}` placeholders in
  `template` with the corresponding values. Missing placeholders fall
  through unchanged so user templates with extra tokens stay readable.

  Substitution is order-independent and idempotent — a template that
  doesn't contain `{column}` simply omits the column. Used by the
  `:custom` editor form."
  [template path line column]
  (-> template
      (str/replace "{path}"   (str path))
      (str/replace "{file}"   (str path))
      (str/replace "{line}"   (str line))
      (str/replace "{column}" (str column))))

;; ---- public: editor-uri --------------------------------------------------

(defn editor-uri
  "Build an 'open in editor' URI for `source-coord` against `editor`.

  Returns a string URI when `source-coord` carries a non-blank `:file`,
  else `nil` (callers hide their open-in-editor affordance when the URI
  is nil).

  `editor` accepts:
    - `:vscode` (default when nil) — `vscode://file/<path>:<line>:<column>`
    - `:cursor`                    — `cursor://file/<path>:<line>:<column>`
    - `:windsurf`                  — `windsurf://file/<path>:<line>:<column>`
    - `:zed`                       — `zed://file/<path>:<line>:<column>`
    - `:idea`                      — `idea://open?file=<path>&line=<line>&column=<column>`
    - `{:custom <template>}`       — user template with `{path}` / `{file}`
                                     / `{line}` / `{column}` placeholders.
    - any unknown keyword          — treated as the default `:vscode`
                                     scheme (so a typoed editor key
                                     still produces a clickable URI
                                     rather than nothing).

  Pure data → data; JVM + CLJS portable. No URL-encoding of the path —
  editor handlers expect raw paths; URL-encoding `/` to `%2F` confuses
  the file resolver in every editor tested. Spaces in paths are the one
  edge case (rare in a Clojure project; absent from re-frame2 + its
  examples).

  Per rf2-vwcsq: returns `nil` when a `{:custom <template>}` resolves to
  a URI whose leading scheme is `javascript:`, `data:`, or `vbscript:` —
  the three schemes that turn `window.location =` into in-tab script
  execution. The built-in scheme builders cannot produce any of these,
  so the gate only ever fires on the `:custom` surface."
  [editor source-coord]
  (when-let [path (coord-file source-coord)]
    (let [line   (coord-line source-coord)
          column (coord-column source-coord)
          uri    (cond
                   ;; Custom template: `{:custom "<uri-template>"}`. Validate the
                   ;; template is a string; anything else falls through to the
                   ;; default editor.
                   (and (map? editor) (string? (:custom editor)))
                   (substitute-template (:custom editor) path line column)

                   (= :cursor editor)
                   (cursor-uri path line column)

                   (= :windsurf editor)
                   (windsurf-uri path line column)

                   (= :zed editor)
                   (zed-uri path line column)

                   (= :idea editor)
                   (idea-uri path line column)

                   ;; :vscode is the default; any unknown keyword also lands here.
                   :else
                   (vscode-uri path line column))]
      (when-not (forbidden-scheme? uri)
        uri))))

(defn open-button-title
  "Build the `title` attribute string an 'Open' button should carry.
  Includes the file:line so a hover reveals exactly where the click
  will land. Pure data → data; JVM + CLJS portable.

  Returns a generic 'Open in editor' when source-coord lacks `:file`."
  [source-coord]
  (if-let [path (coord-file source-coord)]
    (str "Open in editor — " path ":" (coord-line source-coord))
    "Open in editor"))

(defn has-source?
  "Predicate — true iff `source-coord` carries enough data for a
  meaningful editor URI (a non-blank `:file`). Tools gate the open
  button's render on this; a coord without `:file` means we never
  captured a usable location and the button would no-op."
  [source-coord]
  (some? (coord-file source-coord)))
