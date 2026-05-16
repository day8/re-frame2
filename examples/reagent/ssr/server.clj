(ns ssr.server
  "Live SSR Ring server for the Playwright `ssr-live.spec.cjs` smoke
   test (rf2-j3dlc).

   Companion to the existing `ssr.spec.cjs` smoke. That spec exercises
   the hydration path against PRE-BAKED server-shaped HTML staged from
   `index.html`. This namespace stands up a REAL live SSR handler:

   - Boot the SSR adapter (`re-frame.ssr/adapter`).
   - Load `ssr.core` (registers events / views / fx).
   - Register a per-request `:fx-overrides` redirect so
     `:rf.http/managed` resolves to a canned-success stub serving the
     example's two articles (mirrors the headless `ssr-tests` fn in
     `ssr.core`).
   - Wrap `re-frame.ssr.ring/ssr-handler` with a tiny dispatch handler
     that also serves `/main.js` from the shadow-cljs output dir so the
     browser can hydrate on the same origin (matches the `<script
     src='/main.js'>` tag in the SSR-emitted HTML envelope).
   - Run Jetty on the configured port, in this process.

   Exec entrypoint: `clojure -X:live-ssr-server :port 8031`. Stays alive
   until the orchestrator tears the process down (per
   examples/scripts/serve-and-run-examples-tests.cjs).

   Boundaries:
   - JVM-side Ring/SSR e2e details (header round-trip, cookie wire,
     redirect, CRLF rejection) live in
     `implementation/ssr-ring/test/.../ring_e2e_validator_test.clj`.
     This server is intentionally minimal — it exists to give the
     browser-driven smoke a real handler to talk to.
   - The example's `:rf.http/managed` flow goes through canned-success
     via `:fx-overrides`; we do NOT make outbound HTTP calls."
  (:require [clojure.java.io :as io]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [ring.adapter.jetty :as jetty])
  (:import [java.io File]
           [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

;; ---- canned-articles fx (mirrors ssr.core/ssr-tests) ----------------------

(defn- register-canned-articles-fx! []
  ;; The example's `:rf.http/managed` call in `:rf/server-init` would
  ;; otherwise hit the network. Per-frame `:fx-overrides` rewires it to
  ;; this stub at request time. The stub delegates to the framework-
  ;; shipped `:rf.http/managed-canned-success` (Spec 014 §Testing) with
  ;; a fixed `:value` slice — same reply shape a live request would
  ;; produce — so the drain settles synchronously inside `make-frame`
  ;; on the server.
  (rf/reg-fx :ssr.http/canned-articles
    {:platforms #{:server :client}}
    (fn [frame-ctx args-map]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx
              (assoc args-map
                     :value [{:id "a" :title "Article A" :body "Body A"}
                             {:id "b" :title "Article B" :body "Body B"}]))))))

;; ---- static-asset handler --------------------------------------------------

(defn- read-file-bytes ^bytes [^File f]
  (with-open [in (io/input-stream f)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- static-handler
  "Serve a single static file (rooted in `static-root`) when the request
   URI matches `match-uri`. Returns nil for any other request — caller
   chains to the next handler.

   Deliberately narrow: the live-SSR harness needs to serve `/main.js`
   (and nothing else) so the browser can hydrate. Static-asset hosting
   in the general case is the job of http-server / a real CDN; we don't
   reinvent it here."
  [static-root match-uri content-type]
  (fn [{:keys [uri request-method]}]
    (when (and (= uri match-uri) (= :get request-method))
      (let [f (io/file static-root (subs match-uri 1))]
        (when (.isFile f)
          {:status  200
           :headers {"Content-Type"  content-type
                     "Cache-Control" "no-store"}
           :body    (java.io.ByteArrayInputStream. (read-file-bytes f))})))))

;; ---- request-method gate ---------------------------------------------------
;;
;; Browsers issue a HEAD favicon probe to discover the icon; with no
;; favicon at the URL we return a fast 204 so the spec doesn't pick up
;; a stray 404 in the console / pageerror stream.

(defn- favicon-handler [{:keys [uri]}]
  (when (= uri "/favicon.ico")
    {:status 204 :headers {} :body ""}))

;; ---- on-error logger -------------------------------------------------------
;;
;; Default `:on-error` from `ssr-ring` emits a fixed 500 body and
;; swallows the throwable per its no-topology-leak contract (rf2-kzvwq).
;; That's the right production posture, but here in a test harness we
;; want the stack trace on stderr so the live-SSR smoke can diagnose
;; render-path failures without re-running through a debugger. The 500
;; body stays identical so the production contract is honoured at the
;; wire layer.

(defn- logging-on-error
  [_request ^Throwable t]
  (binding [*out* *err*]
    (println (str "ssr.server: render failed: " (.getMessage t)))
    (.printStackTrace t))
  {:status  500
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    "Internal error"})

;; ---- top-level handler -----------------------------------------------------

(defn make-handler
  "Build the composite Ring handler for the live-SSR harness:

      /main.js     → static bundle from `:static-root`
      /favicon.ico → 204 no-content
      anything else → ssr-handler (live SSR + hydration payload)

   `static-root` must be the shadow-cljs `:output-dir` of the
   `:examples/ssr` build (`implementation/out/examples/ssr` by
   convention)."
  [{:keys [static-root]}]
  (let [ssr (ssr-ring/ssr-handler
             {:on-create      [:rf/server-init]
              :root-view      [:app/root]
              :fx-overrides   {:rf.http/managed :ssr.http/canned-articles}
              :payload-policy :rf.ssr.payload/whole-app-db
              :on-error       logging-on-error})
        main-js (static-handler static-root "/main.js"
                                "application/javascript; charset=utf-8")]
    (fn handler [request]
      (or (main-js request)
          (favicon-handler request)
          (ssr request)))))

;; ---- entry point -----------------------------------------------------------

(defn -main
  "`clojure -X:live-ssr-server :port 8031 :static-root \"...\"`.

   Boots the SSR adapter, loads ssr.core (which registers events /
   views / fx via its toplevel forms), registers the canned-articles
   fx, and runs Jetty on `:port` (127.0.0.1 only, never a public
   interface in CI). Blocks until killed."
  [{:keys [port static-root] :or {port 8031}}]
  (when-not static-root
    (binding [*out* *err*]
      (println "ssr.server: missing :static-root (path to shadow-cljs out/examples/ssr/ for main.js)"))
    (System/exit 2))
  (let [^File root-file (io/file static-root)]
    (when-not (.isDirectory root-file)
      (binding [*out* *err*]
        (println (str "ssr.server: :static-root does not exist or is not a dir: "
                      (.getAbsolutePath root-file))))
      (System/exit 2)))
  ;; Boot the SSR adapter (idempotent). Then load ssr.core so its
  ;; toplevel reg-* forms run on the live registrar.
  (rf/init! ssr/adapter)
  (require 'ssr.core)
  (register-canned-articles-fx!)
  (let [handler (make-handler {:static-root static-root})
        ^Server server (jetty/run-jetty handler
                                        {:port                 (long port)
                                         :host                 "127.0.0.1"
                                         :join?                false
                                         :send-server-version? false
                                         :send-date-header?    false})]
    ;; Print the bound URL on the line the orchestrator looks for
    ;; (cross-platform parsing-friendly; matches the harness ready-probe).
    (println (str "ssr.server: listening on http://127.0.0.1:" port "/"))
    (flush)
    ;; Stay alive until JVM teardown. The orchestrator kills this
    ;; subprocess in its `finally` cleanup.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (.stop server))))
    @(promise)))
