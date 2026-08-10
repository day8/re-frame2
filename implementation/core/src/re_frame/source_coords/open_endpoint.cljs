(ns re-frame.source-coords.open-endpoint
  "Client seam for the dev-server 'open in editor' endpoint (Option B,
  rf2-wn3bh).

  The JS-ecosystem standard for jump-to-source is a dev-server endpoint
  (Vite `/__open-in-editor`, react-dev-utils, Next). The server side lives
  in `re-frame.testbed.open-in-editor-server` (a JVM-only shadow `:dev-http`
  handler); THIS namespace is the browser-side client both tool open-seams
  (`day8.re-frame2-xray.open-in-editor` and `re-frame.story.ui.open-in-editor`)
  call to PREFER the endpoint when a dev server is present, and FALL BACK to
  the historic `editor://` URI navigation when it is not (static export,
  non-shadow host, production-mode inspection).

  ## Why prefer the endpoint

  The `editor://` URI path needs an absolute on-disk `:file` so the OS-side
  handler can stat it. rf2-wvsxg bakes that absolute path at macro-expansion
  time, which works for the common classpath-`file:` case but leaves a
  RELATIVE coord for JAR/in-jar/odd-classpath sources (the gap Option B
  closes) and bakes the builder's home path into the bundle — the RELEASE
  bundle as much as the dev one, since the production coord-form
  absolutises too (`spec/Privacy.md` §What the production bundle itself
  discloses). The endpoint resolves the relative `:file` against the live
  source-paths on the dev machine at runtime and launches via the
  cross-platform `launch-editor` package — zero-config for everyone.

  ## Additive — the URI fallback stays

  This is purely ADDITIVE. `open-coord!` first probes the endpoint; on any
  failure (no dev server, network error, non-2xx) it invokes the caller's
  `fallback!` thunk, which navigates the `editor://` URI exactly as before.
  B never removes the URI path.

  ## Swappable launcher seam (for tests)

  The endpoint-launch function is held in an atom (`launcher`, swappable via
  `set-launcher!`) — the same pattern the tool seams use for their
  `navigator` (rf2-muvs8). Production code never reassigns it; tests swap it
  for a synchronous stub so the URI-fallback path stays deterministic
  without a real `fetch` round-trip. `set-launcher!` returns the previous
  launcher so tests can restore it.

  ## Bundle isolation

  This `.cljs` is referenced ONLY from the two tool open-seams, which are
  dev-only (gated behind the tools' `:devtools/preloads`). Nothing on a
  production classpath `:require`s it, so it DCEs out of release bundles —
  the same posture as the open-seams themselves."
  (:require [clojure.string :as str]))

(def endpoint-path
  "Request path the dev-server endpoint answers. Mirrors the server-side
  `re-frame.testbed.open-in-editor-server/endpoint-path`."
  "/__rf-open-in-editor")

(defn ^:private editor->param
  "Project the configured editor preference to the `editor` query value the
  server maps to a launch-editor command hint. Keyword editors ship their
  name; `{:custom …}` and anything else ship nil (server auto-detects)."
  [editor]
  (cond
    (keyword? editor) (name editor)
    (and (map? editor) (:custom editor)) nil
    (string? editor)  editor
    :else             nil))

(defn build-url
  "Build the endpoint URL for a source-coord + editor preference. `:file`
  is required; `:line` / `:column` are optional. `editor` is the configured
  editor (keyword / `{:custom …}` / nil). Returns nil when the coord lacks
  a `:file`."
  [{:keys [file line column]} editor]
  (when (and (string? file) (not (str/blank? file)))
    (let [enc      js/encodeURIComponent
          ed-param (editor->param editor)
          params   (cond-> (str "file=" (enc file))
                     line     (str "&line=" line)
                     column   (str "&column=" column)
                     ed-param (str "&editor=" (enc ed-param)))]
      (str endpoint-path "?" params))))

;; ---- the swappable launcher seam -----------------------------------------

(defn fetch-launcher!
  "Default launcher: POST to the dev-server endpoint, preferring it over the
  `editor://` URI when a dev server answers. ADDITIVE — on any failure (no
  dev server, network error, non-2xx, missing `fetch`) invoke `fallback!`
  (the caller's URI-navigation thunk) exactly once.

  `url` is the pre-built endpoint URL (or nil). Never throws — a thrown
  fetch is caught and routed to the fallback."
  [url fallback!]
  (cond
    (nil? url)
    (fallback!)

    (not (exists? js/fetch))
    (fallback!)

    :else
    (-> (js/fetch url #js {:method "POST" :cache "no-store"})
        (.then (fn [resp]
                 (if (and resp (.-ok resp))
                   (js/console.log "[rf/open-in-editor] opened via dev-server endpoint")
                   ;; Endpoint reachable but refused (400/422 — e.g.
                   ;; launch-editor found no editor): fall back to the URI
                   ;; so the OS handler chain still gets a shot.
                   (do (js/console.log
                         "[rf/open-in-editor] dev-server endpoint declined; falling back to editor:// URI")
                       (fallback!)))))
        (.catch (fn [_err]
                  ;; No dev server / network error / static export: the URI
                  ;; path is the documented standalone contract.
                  (fallback!))))))

(defonce ^:private launcher
  ;; Held in an atom so tests can swap (`set-launcher!`). Production code
  ;; never reassigns it. Per the rf2-muvs8 navigator-seam pattern.
  (atom fetch-launcher!))

(defn set-launcher!
  "Replace the endpoint launcher seam used by `open-coord!`. Returns the
  previous launcher so tests can restore it. Test-only — production callers
  MUST NOT call this."
  [f]
  (let [prev @launcher]
    (reset! launcher f)
    prev))

(defn open-coord!
  "Open `source-coord` in `editor`, PREFERRING the dev-server endpoint
  (Option B, rf2-wn3bh) and FALLING BACK to `fallback!` (the caller's
  `editor://` URI-navigation thunk) when no dev server is present.

  Arguments:
    `source-coord` — `{:file :line :column}`. Sent verbatim; the server
                     resolves a classpath-relative `:file` at runtime.
    `editor`       — the configured editor preference (keyword / `{:custom …}`
                     / nil), projected to the launch-editor command hint.
    `fallback!`    — zero-arg thunk navigating the `editor://` URI (the
                     historic path). Called on endpoint failure.

  Returns nothing (the launch is fire-and-forget). Delegates to the
  swappable `launcher` seam so tests can make the path synchronous."
  [source-coord editor fallback!]
  (@launcher (build-url source-coord editor) fallback!)
  nil)
