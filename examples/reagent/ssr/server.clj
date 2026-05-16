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

   ## Frame-id workaround (rf2-j3dlc finding)

   `re-frame.ssr.ring.pipeline/setup-request-frame!` builds the
   per-request frame-id via
       (keyword \"rf.frame\" (str (gensym \"\")))
   which produces names like `:rf.frame/1234` — a digit-only LOCAL
   part. Clojure's keyword constructor accepts that, but the EDN
   spec (https://github.com/edn-format/edn) requires symbol names
   (and keywords are identifier-shaped) to begin with a non-numeric
   character. CLJS's `cljs.tools.reader.edn` enforces the spec and
   throws `Invalid keyword: :rf.frame/1234.` when the client tries to
   read the embedded `__rf_payload`.

   This is a latent ssr-ring bug — until rf2-j3dlc the only browser-
   driven SSR coverage was over PRE-BAKED HTML that didn't ship the
   frame-id from `pipeline/setup-request-frame!`. The live-SSR smoke
   surfaces it. Per the dispatch rules, `implementation/ssr-ring/` is
   a sibling agent's hot zone (rf2-o6ndb) so the fix lives in a
   follow-on bead; this server papers over it with a custom
   `:html-shell` that rewrites the gensym-shaped frame-id to a
   leading-letter form before the EDN reaches the wire.

   Boundaries:
   - JVM-side Ring/SSR e2e details (header round-trip, cookie wire,
     redirect, CRLF rejection) live in
     `implementation/ssr-ring/test/.../ring_e2e_validator_test.clj`.
     This server is intentionally minimal — it exists to give the
     browser-driven smoke a real handler to talk to.
   - The example's `:rf.http/managed` flow goes through canned-success
     via `:fx-overrides`; we do NOT make outbound HTTP calls."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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

;; ---- custom html-shell ------------------------------------------------------
;;
;; Wrap `ssr-ring/default-html-shell` with a tiny payload-EDN rewrite
;; that fixes the digit-only `:rf.frame/<gensym>` keyword surfaced by
;; the live-SSR smoke (see ns docstring §Frame-id workaround).
;;
;; The shell receives `payload-edn` as a string (already pr-str'd by
;; the pipeline). We read it back to data, replace `:rf/frame-id` with
;; a leading-letter form (`:rf.frame/f<digits>`) — still unique per
;; request, still namespaced, EDN-spec-compliant — then re-serialise
;; and hand off to the default shell.

(def ^:private digit-only-frame-id-pattern
  "Match `:rf.frame/<digits>` keyword literals inline in the payload's
   EDN string. The gensym-induced shape always carries pure digits
   in the local-part — that's what makes it cljs-EDN-invalid and what
   makes the regex unambiguous (no risk of matching a legitimate
   keyword)."
  #":rf\.frame/(\d+)")

(defn- rewriting-html-shell
  "Custom `:html-shell` — patches the payload's frame-id keyword in
   the raw EDN string and delegates to `default-html-shell`.

   Why regex-on-string rather than read/rewrite/pr-str: both
   `clojure.edn/read-string` AND `cljs.tools.reader.edn/read-string`
   refuse `:rf.frame/<digits>` (EDN spec — identifier names start
   non-numeric). So reading the payload to data on the JVM side
   would throw before we could rewrite it. A targeted text
   substitution sidesteps the reader entirely.

   The substitution rewrites `:rf.frame/<digits>` →
   `:rf.frame/f<digits>` (still unique per request, still namespaced,
   EDN-spec-compliant on both JVM and CLJS). Conservative — leaves
   any other keyword shape alone."
  [body-html payload-edn opts]
  (let [patched-edn (str/replace payload-edn
                                 digit-only-frame-id-pattern
                                 ":rf.frame/f$1")]
    (ssr-ring/default-html-shell body-html patched-edn opts)))

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
              :html-shell     rewriting-html-shell
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
