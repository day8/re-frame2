(ns {{namespace}}.server
  "SSR host — a Ring/Jetty server that renders {{name}} to HTML per
   request, then serves the compiled client bundle + the CSS scaffold so
   the browser can hydrate a styled page.

   Emitted by `day8/re-frame2-template` under `:include-ssr? true`. Run it
   with:

     clojure -X:server

   and open http://127.0.0.1:8030. The client bundle is built separately
   with `npx shadow-cljs watch app` (or `release app`), which writes
   `main.js` into `resources/public/js/` — under the `:static-root` this
   server serves from.

   ## The shape

   `re-frame.ssr.ring/ssr-handler` is the Ring-shaped (req -> resp) SSR
   handler. It owns the whole per-request lifecycle — frame create ->
   drain -> response read -> render -> frame destroy — and materialises
   the runtime's response accumulator into a Ring response. We wrap it in
   a tiny composite handler that ALSO serves the static assets the live
   shell references, all from one public root:

     GET /main.js        -> the compiled client bundle (so the page can
                            hydrate)
     GET /css/…          -> the selected CSS scaffold (text/css)
     GET /js/…           -> the client bundle + its dev hot-reload chunks
     GET /favicon.ico    -> 204 (keeps the browser's automatic favicon
                            request out of the SSR path)
     GET (anything else) -> ssr-handler renders the app

   The live shell (`app-html-shell` below) is the ONE host page the SSR
   path serves. It is composed from the framework's `default-html-shell`
   via the structured `:head` / `:body-end` content-hooks, so the emitted
   static `resources/public/index.html` is a reference only — the SSR
   server never serves it. `:head` gains the stylesheet `<link>` (Tailwind
   mode also the dev compiler); `:body-end` gains the Xray devtools host.
   Everything else — the `#app` root, the `__rf_payload` hydration
   `<script>`, the render/head hash markers, the `/main.js` bootstrap, and
   the route-resolved `<head>` title + meta — is single-sourced by
   `default-html-shell`.

   Reference: `implementation/ssr-ring/`'s `ssr-handler` docstring and its
   `ring_e2e_validator_test.clj` Jetty bring-up in the re-frame2 repo.

   ## Beyond Jetty

   Jetty is the dev/test default here for its zero-ceremony bring-up. Any
   Ring-shaped host — HttpKit, Pedestal, Reitit-ring — consumes the same
   `ssr-handler` without change; swap the `run-jetty` call for your host's
   equivalent. For production, put a real static-asset server / CDN in
   front for `/main.js` + `/css/` and reverse-proxy the SSR route."
  (:require [ring.adapter.jetty :as jetty]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [{{namespace}}.core :as app]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; The live SSR shell — the selected CSS scaffold + Xray host, composed onto
;; the framework shell via structured content-hooks (never string surgery on
;; rendered HTML).
;; ---------------------------------------------------------------------------

(def ^:private tailwind?
  "True when this project was scaffolded with `:css :tailwind`. The live
   shell then ALSO loads the `@tailwindcss/browser` dev compiler, which
   compiles the utilities used on the page at runtime — mirroring the SPA
   scaffold's index.html. `{{css-name}}` is `\"tailwind\"` or `\"\"`
   (deps-new substitution)."
  (= "{{css-name}}" "tailwind"))

(def ^:private head-assets
  "Raw `<head>` HTML the live shell injects on every SSR response so the
   selected CSS scaffold actually loads: the stylesheet `<link>`, plus — in
   Tailwind mode — the `@tailwindcss/browser@4` dev compiler. Appended to the
   route-resolved head model through the `:head` content-hook, so the title +
   meta the route declares are preserved, not replaced."
  (str
    (when tailwind?
      "<script src=\"https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4\"></script>")
    "<link rel=\"stylesheet\" href=\"/css/app.css\">"))

(def ^:private xray-host-aside
  "The Xray devtools layout host, mirroring the SPA scaffold's index.html.
   Injected via the `:body-end` content-hook so the LIVE SSR shell — not the
   unused static index.html — carries the `[data-rf-xray-host]` mount the dev
   Xray preload docks into. It sits OUTSIDE `#app`, so it never participates
   in hydration."
  "<aside class=\"rf2-xray-host\" data-rf-xray-host></aside>")

(defn- app-html-shell
  "The live SSR document shell. Delegates to the framework's
   `default-html-shell` — which single-sources the doctype, the
   `<html>`/`<body>` attribute bags, the route-resolved `<head>` (title +
   meta), the `__rf_payload` hydration `<script>`, the render/head hash
   markers, the `/main.js` bootstrap, and all attribute escaping — and
   augments ONLY the two structured content-hooks: `:head` gains the
   stylesheet link (+ Tailwind compiler), `:body-end` gains the Xray host.
   Both are APPENDED to whatever the route resolved, never replaced."
  [body-html payload-edn opts]
  (ssr-ring/default-html-shell
    body-html payload-edn
    (-> opts
        (update :head     (fn [resolved] (str resolved head-assets)))
        (update :body-end (fn [resolved] (str resolved xray-host-aside))))))

;; ---------------------------------------------------------------------------
;; Static assets — served from one public root, with normalised containment so
;; a URI can't traverse out of it.
;; ---------------------------------------------------------------------------

(defn- content-type-for
  "The static Content-Type for `rel-path`, by extension. CSS is served as
   `text/css`, the JS bundle + its chunks as `text/javascript`."
  [rel-path]
  (cond
    (str/ends-with? rel-path ".css")  "text/css; charset=utf-8"
    (str/ends-with? rel-path ".js")   "text/javascript; charset=utf-8"
    (str/ends-with? rel-path ".map")  "application/json; charset=utf-8"
    (str/ends-with? rel-path ".ico")  "image/x-icon"
    (str/ends-with? rel-path ".json") "application/json; charset=utf-8"
    :else                             "application/octet-stream"))

(defn- contained-file
  "Resolve `rel-path` under `public-root` and return the `java.io.File` IFF the
   normalised (canonical, `..`-resolved) target stays WITHIN `public-root`.
   Returns nil on any escape — the static-asset boundary rejects URI traversal
   (`/css/../../secret`) rather than reading outside the configured root. The
   containment check is path-component containment on canonical paths, not a
   string prefix, so it is not fooled by sibling-prefix directories."
  ^File [public-root rel-path]
  (let [root (.getCanonicalFile (io/file public-root))
        f    (.getCanonicalFile (io/file root rel-path))]
    (when (.startsWith (.toPath f) (.toPath root))
      f)))

(defn- static-response
  "Serve `rel-path` from `public-root` with normalised containment:

     - contained + present -> 200, extension-derived Content-Type, file body
     - contained + missing -> 404 (with an optional build hint)
     - escapes public root -> 403

   It NEVER falls through to the SSR renderer, so a static-looking asset
   request can't come back as an SSR HTML document mislabelled CSS/JS."
  ([public-root rel-path] (static-response public-root rel-path nil))
  ([public-root rel-path not-found-hint]
   (if-let [f (contained-file public-root rel-path)]
     (if (.isFile f)
       {:status  200
        :headers {"Content-Type" (content-type-for rel-path)}
        :body    f}
       {:status  404
        :headers {"Content-Type" "text/plain; charset=utf-8"}
        :body    (or not-found-hint
                     (str rel-path " not found under " public-root "."))})
     {:status  403
      :headers {"Content-Type" "text/plain; charset=utf-8"}
      :body    "Forbidden: the requested asset path escapes the public root."})))

;; ---------------------------------------------------------------------------
;; The composite handler.
;; ---------------------------------------------------------------------------

(defn make-handler
  "Build the composite Ring handler: the contained static assets under
   `public-root` + a favicon 204 + the SSR renderer for everything else.
   `public-root` is the directory that holds both the CSS scaffold
   (`css/app.css`) and the `shadow-cljs`-built client bundle (`js/main.js`)
   — `resources/public` by default."
  [public-root]
  (let [ssr (ssr-ring/ssr-handler
              {:initial-events app/server-init-events
               :root-view      app/root-view
               ;; Ship only the counter slice in the hydration payload —
               ;; the allowlist form (recommended). `:payload` is REQUIRED
               ;; and fail-closed; omit it and construction throws
               ;; `:rf.error/ssr-missing-payload-policy`.
               :payload        [:counter/value]
               ;; The live shell loads the selected CSS scaffold + hosts Xray.
               :html-shell     app-html-shell})]
    (fn handler [{:keys [uri request-method] :as request}]
      (cond
        (not= :get request-method)
        (ssr request)

        ;; The `/main.js` bootstrap the shell emits, served from js/main.js.
        (= "/main.js" uri)
        (static-response public-root "js/main.js"
                         (str "main.js not found under " public-root "/js"
                              " — build the client bundle first "
                              "(`npx shadow-cljs watch app`)."))

        (= "/favicon.ico" uri)
        {:status 204 :headers {} :body ""}

        ;; The CSS scaffold + the client bundle's dev hot-reload chunks. Both
        ;; go through the contained server so `..` can't escape public-root.
        (or (str/starts-with? uri "/css/")
            (str/starts-with? uri "/js/"))
        (static-response public-root (subs uri 1))

        :else
        (ssr request)))))

(defn -main
  "Entry point for `clojure -X:server`. Reads `:port` + `:public-root` from
   the alias's `:exec-args` (see deps.edn). Installs the JVM-side SSR render
   adapter, then boots Jetty on 127.0.0.1."
  [{:keys [port public-root]
    :or   {port 8030 public-root "resources/public"}}]
  ;; Install the JVM-side SSR render adapter (re-frame.ssr/adapter). The
  ;; server render path walks hiccup -> HTML on the JVM, so it seats the
  ;; SSR adapter, NOT the CLJS-only Reagent adapter (that one boots the
  ;; browser mount in core.cljc's :cljs branch).
  (rf/init! ssr/adapter)
  (println (str "SSR server for {{name}} on http://127.0.0.1:" port
                " (serving static assets from " public-root ")"))
  (jetty/run-jetty (make-handler public-root)
                   {:port  port
                    :host  "127.0.0.1"
                    :join? true}))
