(ns {{namespace}}.server
  "SSR host — a Ring/Jetty server that renders {{name}} to HTML per
   request, then serves the compiled client bundle so the browser can
   hydrate it.

   Emitted by `day8/re-frame2-template` under `:include-ssr? true`. Run it
   with:

     clojure -X:server

   and open http://127.0.0.1:8030. The client bundle is built separately
   with `npx shadow-cljs watch app` (or `release app`), which writes
   `main.js` into the `:static-root` this server serves from.

   ## The shape

   `re-frame.ssr.ring/ssr-handler` is the Ring-shaped (req -> resp) SSR
   handler. It owns the whole per-request lifecycle — frame create ->
   drain -> response read -> render -> frame destroy — and materialises
   the runtime's response accumulator into a Ring response. We wrap it in
   a tiny composite handler that also serves two static routes:

     GET /main.js        -> the compiled client bundle (so the page can
                            hydrate)
     GET /favicon.ico    -> 204 (keeps the browser's automatic favicon
                            request out of the SSR path)
     GET (anything else) -> ssr-handler renders the app

   Reference: `implementation/ssr-ring/`'s `ssr-handler` docstring and its
   `ring_e2e_validator_test.clj` Jetty bring-up in the re-frame2 repo.

   ## Beyond Jetty

   Jetty is the dev/test default here for its zero-ceremony bring-up. Any
   Ring-shaped host — HttpKit, Pedestal, Reitit-ring — consumes the same
   `ssr-handler` without change; swap the `run-jetty` call for your host's
   equivalent. For production, put a real static-asset server / CDN in
   front for `/main.js` and reverse-proxy the SSR route."
  (:require [ring.adapter.jetty :as jetty]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [{{namespace}}.core :as app]
            [clojure.java.io :as io]))

(defn- static-file-response
  "Serve `filename` from `static-root` as a 200 with `content-type`, or
   404 when it isn't there yet (e.g. the client bundle hasn't been built).
   The 404 body names the fix so a first-run miss is self-explanatory."
  [static-root filename content-type]
  (let [f (io/file static-root filename)]
    (if (.isFile f)
      {:status  200
       :headers {"Content-Type" content-type}
       :body    f}
      {:status  404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body    (str filename " not found under " static-root
                     " — build the client bundle first "
                     "(`npx shadow-cljs watch app`).")})))

(defn make-handler
  "Build the composite Ring handler: static `/main.js` + favicon 204 +
   the SSR renderer for everything else. `static-root` is the directory
   `shadow-cljs` writes `main.js` into (the `:app` build's
   `:output-dir`)."
  [static-root]
  (let [ssr (ssr-ring/ssr-handler
              {:initial-events app/server-init-events
               :root-view      app/root-view
               ;; Ship only the counter slice in the hydration payload —
               ;; the allowlist form (recommended). `:payload` is REQUIRED
               ;; and fail-closed; omit it and construction throws
               ;; `:rf.error/ssr-missing-payload-policy`.
               :payload        [:counter/value]})]
    (fn handler [{:keys [uri request-method] :as request}]
      (cond
        (and (= :get request-method) (= "/main.js" uri))
        (static-file-response static-root "main.js" "text/javascript; charset=utf-8")

        (and (= :get request-method) (= "/favicon.ico" uri))
        {:status 204 :headers {} :body ""}

        :else
        (ssr request)))))

(defn -main
  "Entry point for `clojure -X:server`. Reads `:port` + `:static-root`
   from the alias's `:exec-args` (see deps.edn). Installs the JVM-side SSR
   render adapter, then boots Jetty on 127.0.0.1."
  [{:keys [port static-root]
    :or   {port 8030 static-root "resources/public/js"}}]
  ;; Install the JVM-side SSR render adapter (re-frame.ssr/adapter). The
  ;; server render path walks hiccup -> HTML on the JVM, so it seats the
  ;; SSR adapter, NOT the CLJS-only Reagent adapter (that one boots the
  ;; browser mount in core.cljc's :cljs branch).
  (rf/init! ssr/adapter)
  (println (str "SSR server for {{name}} on http://127.0.0.1:" port
                " (serving client bundle from " static-root ")"))
  (jetty/run-jetty (make-handler static-root)
                   {:port  port
                    :host  "127.0.0.1"
                    :join? true}))
