(ns re-frame.ssr.ring.login-host-crossing-test
  "THE LOGIN ARM'S JVM HOST WITNESS.

  `examples/substrates/fresco/login/` is the native-Fresco PRODUCT witness
  for the ssr-node crossing, and its JVM host is half of it. Without a gate
  that loads the namespace and drives the advertised Ring handler, a compile
  error in the host would pass every gate silently.

  This namespace is that gate, in two tiers.

  UNTAGGED — runs in the default `:test` lane, no Node. Loading this
  namespace requires `fresco.login.host`, which requires the shared
  `login.model` on a plain Clojure classpath. That is the compile witness:
  the namespace cannot rot without a red gate. The untagged tests then
  assert what holds without a sidecar: the render-state policy is ONE
  source both halves of the deployment read, the documented boot seats the
  adapter, and the composed app routes the client bundle.

  `:crossing` — runs under `clojure -M:crossing-test` (CI's
  `jvm-node-crossing` job; Node 24 on PATH). Each builds its renderer with
  `:module` on `test/fixtures/node/login_host.cjs`, so the renderer
  launches the sidecar ssr-ring's jar carries and talks to it over a real
  socket, and drives `fresco.login.host/make-handler` — the same constructor
  the shipped `app` is built from, around `fresco.login.host/renderer`. The
  fixture takes its entry id FROM `fresco.login.policy`, the Var the shipped
  bundle publishes as its entry table.

  ## What this witnesses, and what it does not

  It witnesses the JVM half of the documented build -> sidecar -> Ring page
  path: the host loads, `ssr-handler` drains `:initial-events` against real
  `auth.login` registrations, the renderer projects the settled frame under
  the shared policy, the bytes cross a real socket to the sidecar the
  renderer launched, and a complete JVM-owned document comes back with
  Node's body inserted verbatim.

  It does NOT re-witness the Fresco render itself — that is React on Node,
  and `re-frame.fresco.login-server-crossing-ssr-dom-cljs-test` drives the
  real views, the real registrations and the real published entry table
  against the sidecar's own request validator. Compiling the login server
  bundle inside a JVM test would buy nothing that test does not already
  hold, and would put shadow-cljs on this lane.

  ## One policy, one owner

  Which keys Node sees is the host's `:render-state` alone; the sidecar
  checks their shape, never their names. `render-state-is-one-source`
  asserts the single owner, and `a-key-the-entry-never-names-renders`
  proves a key the JVM projects reaches the render although the bundle's
  entry table names no keys, and stays out of `__rf_payload`."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            ;; THE SUBJECT. Requiring it is half the witness.
            [fresco.login.host :as host]
            [fresco.login.policy :as policy])
  (:import [java.net InetSocketAddress]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]))

;; ===========================================================================
;; The runtime reset, plus the application's registrations
;; ===========================================================================

(defn- with-login-app
  "`ts/reset-runtime` wipes the registrar before each test, which drops the
  `auth.login` registrations `login.model` installed at namespace load —
  and Clojure's `require` is idempotent, so a plain re-require would not
  re-fire them. Reload the framework namespaces whose own load-time
  registrations the model leans on, then the model itself. Same pattern,
  and same reason, as `re-frame.examples-test`'s fixture."
  [f]
  (rf.ssr.ring.test-support/reset-runtime
    (fn []
      (require 're-frame.cofx :reload)
      (require 're-frame.http.managed :reload)
      (require 're-frame.machines :reload)
      (require 'login.model :reload)
      (f))))

;; ===========================================================================
;; The render module — the login fixture, bound to the application's identity
;; ===========================================================================

(def ^:private login-module
  "A one-line module that calls the fixture's factory with
  `policy/root-entry` and `host/build-id`, so nothing in the fixture spells
  either: the identity the sidecar publishes is the application's own.
  Written once per JVM, on first use."
  (delay
    (let [dir    (.toFile (Files/createTempDirectory "rf2-login-host-module"
                                                     (into-array FileAttribute [])))
          module (io/file dir "module.cjs")
          ;; JSON is a JavaScript literal, so the path and the identity are
          ;; quoted for Node whatever they contain.
          source (str "module.exports = require("
                      (json/write-str (.getCanonicalPath (io/file "test/fixtures/node/login_host.cjs")))
                      ")("
                      (json/write-str {"entry" policy/root-entry "buildId" host/build-id})
                      ");\n")]
      (.deleteOnExit dir)
      (spit module source)
      (.deleteOnExit module)
      (.getCanonicalPath module))))

(use-fixtures :each with-login-app)

;; ===========================================================================
;; The request, and small readers over the response body
;; ===========================================================================

(def ^:private request
  {:uri "/login" :request-method :get :headers {}})

(defn- marked
  "The text of the fixture's `<div class=\"<mark>\">…</div>` echo — what
  Node was actually handed, read back out of the document the JVM built
  around it."
  [body mark]
  (second (re-find (re-pattern (str "<div class=\"" mark "\">(.*?)</div>")) body)))

(defn- payload-edn-of [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

;; ===========================================================================
;; UNTAGGED — no Node. The peer below is an in-process JDK `HttpServer`
;; answering `/render` the way the sidecar does.
;; ===========================================================================

(deftest render-state-is-one-source
  ;; host.clj is Clojure and server.cljs ClojureScript, so neither compiler
  ;; can read the other's file and a copy in either would drift silently.
  ;; The host projects under the shared policy; the bundle publishes the
  ;; shared entry id.
  (let [src-of     #(slurp (io/file (str "../../examples/substrates/fresco/login/" %)))
        host-src   (src-of "host.clj")
        server-src (src-of "server.cljs")]
    (is (str/includes? host-src "policy/render-state-policy") "host.clj reads the shared policy Var")
    (is (str/includes? server-src "policy/root-entry") "server.cljs reads the shared entry id")
    (doseq [[f src] [["host.clj" host-src] ["server.cljs" server-src]]]
      (is (not (str/includes? src (pr-str (:app-db policy/render-state-policy))))
          (str f " keeps no copy of the app-db key list")))))

(defn- with-render-peer
  "Run `(f url hits)` against a loopback peer that answers `/render` with
  `host/build-id` and `body`. `hits` counts the renders it served."
  [^String body f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        bytes  (.getBytes body "UTF-8")
        hits   (atom 0)]
    (.createContext server "/render"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange ex]
                        (swap! hits inc)
                        (.add (.getResponseHeaders ex) "x-rf-ssr-build" host/build-id)
                        (.sendResponseHeaders ex 200 (alength bytes))
                        (with-open [out (.getResponseBody ex)] (.write out bytes))
                        nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) hits)
      (finally (.stop server 0)))))

(def ^:private rendered-marker "<form id=\"stub-rendered-login\"></form>")

(deftest the-documented-boot-installs-the-adapter
  ;; Requiring `re-frame.ssr` publishes its adapter; only `rf/init!` seats one.
  ;; The fixture seats it, so unseat it first: `host/init!` must be what does.
  (with-render-peer rendered-marker
    (fn [url _]
      (let [h (host/make-handler (host/renderer {:endpoint url}))]
        (rf/destroy-adapter!)
        (is (nil? (rf.substrate.adapter/current-adapter)))
        (host/init!)
        (let [{:keys [status body]} (h request)]
          (is (= 200 status))
          (is (str/includes? body rendered-marker)))))))

(deftest the-composed-app-serves-the-client-bundle-at-the-shell-s-script-url
  ;; `ssr-handler` renders a document for EVERY request, so served bare it
  ;; would answer the page's own `<script src>` with another login page.
  (with-render-peer rendered-marker
    (fn [url hits]
      (let [^Path client-dir (Files/createTempDirectory "rf2-login-fresco-client"
                                                         (into-array FileAttribute []))
            dir-file         (.toFile client-dir)
            app              (host/make-app (host/make-handler (host/renderer {:endpoint url}))
                                            (.getAbsolutePath dir-file))
            get!             #(app {:uri % :request-method :get :headers {}})]
        (try
          (spit (io/file dir-file "main.js") "console.log('login client');")
          ;; Fetch the URL the shell advertises, not a literal copy of it.
          (let [script-src (second (re-find #"<script src=\"([^\"]+)\"" (:body (get! "/"))))
                {:keys [status headers body]} (get! script-src)]
            (is (= [200 "text/javascript" "console.log('login client');"]
                   [status (get headers "Content-Type") (slurp body)])))
          (is (= 404 (:status (get! "/no-such-asset.js"))) "a miss, not a login page")
          (is (= 404 (:status (get! "/../host.clj"))) "confined to the build's own tree")
          (is (= 1 @hits) "only the page request rendered")
          (finally
            (doseq [^java.io.File f (reverse (file-seq dir-file))]
              (.delete f))))))))

;; ===========================================================================
;; :crossing — the sidecar the renderer launches, a real socket, the handler
;; ===========================================================================

(deftest ^:crossing the-login-host-renders-a-page
  (with-open [renderer (host/renderer {:module @login-module})]
    (let [h (host/make-handler renderer)
          {:keys [status headers body]} (h request)]

      (testing "a complete JVM-owned document"
        (is (= 200 status))
        (is (str/includes? body "<!DOCTYPE html>") "shell: JVM-built")
        (is (str/includes? body "<div id=\"app\"")
            "shell: the #app root the client hydrator adopts by id")
        (is (str/includes? body "src=\"/main.js\"")
            "shell: the client bundle, at the URL `host/make-app` maps it to")
        ;; The host names no `:content-type`, so the handler emits none and
        ;; leaves the header to the runtime — asserted as measured rather
        ;; than as assumed.
        (is (nil? (get headers "Content-Type"))
            "no Content-Type override: the host does not declare one"))

      (testing "Node's body markup, inserted verbatim, for the login entry"
        (is (str/includes? body (str "<main data-entry=\"" policy/root-entry "\">"))
            "the entry the host named is the entry the sidecar dispatched to"))

      (testing "the render-state projection crossed under the shared policy"
        ;; `select-keys` omits an absent key, so the state partition carries
        ;; the policy keys the settled app-db HAS. `:auth` is seeded by the
        ;; boot event; `:auth.login/server-notice` is deliberately unset in
        ;; this deployment (the README's rule: a render-state key the payload
        ;; does not carry must not change the markup).
        (is (= ":auth" (marked body "login-state-keys"))
            "the app-db partition Node received")
        (is (str/includes? (str (marked body "login-draft")) ":login-form")
            "and it carried the form slice the inputs are bound to")
        (is (str/includes? (str (marked body "login-draft")) ":rf/redacted")
            "with the draft password redacted at the projection — the render
             cannot print a secret it was never handed")
        ;; The runtime partition is EMPTY on this deployment. The
        ;; `:auth.login/flow` machine is self-seeding — it materialises a
        ;; snapshot the first time it is dispatched at or subscribed to — and
        ;; the shipped host does neither, because the render happens in Node.
        ;; So Node's own frame seeds `:idle` and renders the form, which is
        ;; the right page. `the-machine-snapshot-crosses-when-the-host-drives-it`
        ;; below proves the partition is live rather than decorative.
        (is (= "" (marked body "login-runtime-keys"))
            "nothing in the runtime partition: the host never drives the machine")
        (println (format "[login-host-crossing] login host render-state: app-db keys %s / runtime-db keys %s"
                         (pr-str (marked body "login-state-keys"))
                         (pr-str (marked body "login-runtime-keys")))))

      (testing "render-state is NOT the payload"
        (let [payload (payload-edn-of body)]
          (is (some? payload) "__rf_payload — JVM-built, from the JVM app-db")
          (is (str/includes? payload ":auth") "the browser gets the form slice")
          (is (not (str/includes? payload ":auth.login/server-notice"))
              "and never the server-only notice key"))))))

(deftest ^:crossing the-machine-snapshot-crosses-when-the-host-drives-it
  (testing "`:rf.runtime/machines` is on the render-state list because a host
            that HAS driven the flow — a session-restoring one, say — must
            hand the render the state that decides which of the page's three
            faces it draws. The shipped host drives nothing, so nothing
            crosses; drive the machine from `:initial-events` and the
            snapshot rides the runtime partition."
    (with-open [renderer (rf.ssr.ring.node/renderer
                           {:module       @login-module
                            :entry        policy/root-entry
                            :build-id     host/build-id
                            :render-state policy/render-state-policy
                            :timeout-ms   1000})]
      (let [h (rf.ssr.ring/ssr-handler
                {:frame          {:initial-events
                                  [[:auth.login/initialise-form]
                                   ;; `:idle` -> `:submitting`, action
                                   ;; `:clear-error`. A pure data transition —
                                   ;; the HTTP request is fired by
                                   ;; `submit-form`, not by the machine.
                                   [:auth.login/flow [:auth.login/submit]]]}
                 :payload        [:auth]
                 :renderer       renderer})
            {:keys [status body]} (h request)]
        (is (= 200 status))
        (is (= ":rf.runtime/machines" (marked body "login-runtime-keys"))
            "the machine partition crossed")
        (is (str/includes? (str (marked body "login-machines")) ":submitting")
            "carrying the state the JVM drove the flow into")
        (is (not (str/includes? (str (payload-edn-of body)) ":rf.runtime/machines"))
            "and it is render state, not payload: the browser's allowlist is
             app-db keys and names none of this")))))

(deftest ^:crossing a-key-the-entry-never-names-renders
  (testing "a key the host's :render-state projects reaches the render
            although the bundle's entry table names no keys, and stays out
            of __rf_payload because :payload does not name it"
    ;; The extra key has to be PRESENT in app-db, or the projection's
    ;; `select-keys` omits it and nothing crosses.
    (rf/reg-event :login-host-test/seed-extra
      {:platforms #{:server}}
      (fn [{:keys [db]} _]
        {:db (assoc db :auth.login/not-on-the-list "widened")}))
    (with-open [renderer (rf.ssr.ring.node/renderer
                           {:module       @login-module
                            :entry        policy/root-entry
                            :build-id     host/build-id
                            :render-state (update policy/render-state-policy
                                                  :app-db conj
                                                  :auth.login/not-on-the-list)
                            :timeout-ms   1000})]
      (let [widened (rf.ssr.ring/ssr-handler
                      {:frame          {:initial-events [[:auth.login/initialise-form]
                                                         [:login-host-test/seed-extra]]}
                       :payload        [:auth]
                       :renderer       renderer})
            {:keys [status headers body]} (widened request)]
        (is (= 200 status))
        (is (= ":auth :auth.login/not-on-the-list" (marked body "login-state-keys"))
            "the projected key crossed, and the render received it")
        (is (not (str/includes? (str (payload-edn-of body)) "not-on-the-list"))
            "the browser's payload never carries it")
        (is (not (str/includes? (pr-str headers) "not-on-the-list"))
            "nor does any response header")))))
