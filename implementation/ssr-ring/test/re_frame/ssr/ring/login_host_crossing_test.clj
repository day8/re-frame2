(ns re-frame.ssr.ring.login-host-crossing-test
  "rf2-8arzr.5 — THE LOGIN ARM'S JVM HOST WITNESS.

  Slice E shipped `examples/substrates/hicasso/login/` as the native-Hicasso
  PRODUCT witness for the ssr-node crossing, and the merged-PR audit found
  that half of it did not run: `host.clj` commented out its `login.model`
  require because the shared model was ClojureScript-only, the example
  README said in so many words that the JVM host could not run, and no gate
  loaded the namespace or drove the advertised Ring handler. A compile error
  in it passed every gate silently.

  This namespace is the gate that was missing, in two tiers.

  UNTAGGED — runs in the default `:test` lane, no Node. Loading this
  namespace requires `hicasso.login.host`, which requires the shared
  `login.model` on a plain Clojure classpath. That is the compile witness:
  the namespace cannot rot without a red gate. The untagged tests then
  assert the things that hold without a sidecar — that the handler
  constructed at all, that the model's registrations are really in the
  registrar, and that the render-state policy is ONE source both halves of
  the deployment read.

  `:crossing` — runs under `clojure -M:crossing-test` (CI's
  `jvm-node-crossing` job; Node 24 on PATH). Each spawns
  implementation/ssr-node's serve launcher on `test/fixtures/node/
  login_host.cjs`, over a real socket on port 0, and drives
  `hicasso.login.host/make-handler` — the same constructor the shipped
  `handler` Var is built from, pointed at the ephemeral endpoint the
  spawned sidecar reported. The fixture is configured FROM
  `hicasso.login.policy`, so the sidecar enforces the application's own
  entry allowlists rather than a second copy of them.

  ## What this witnesses, and what it does not

  It witnesses the JVM half of the documented build -> sidecar -> Ring page
  path: the host loads, `ssr-handler` drains `:initial-events` against real
  `auth.login` registrations, the renderer projects the settled frame under
  the shared policy, the bytes cross a real socket to the real sidecar
  launcher, and a complete JVM-owned document comes back with Node's body
  inserted verbatim.

  It does NOT re-witness the Hicasso render itself — that is React on Node,
  and `re-frame.hicasso.login-server-crossing-ssr-dom-cljs-test` drives the
  real views, the real registrations and the real published entry table
  against the sidecar's own request validator. Compiling the login server
  bundle inside a JVM test would buy nothing that test does not already
  hold, and would put shadow-cljs on this lane.

  ## The fail-open the audit named, proved closed

  The sidecar refuses a host that asks for MORE than the entry allows. It
  cannot refuse one that asks for LESS — that host is served a page
  rendered from incomplete state, with nothing to notice. Two copies of one
  list drift both ways; one Var cannot. `render-state-is-one-source`
  asserts the single owner, and `a-widened-host-is-refused` proves the
  other direction still bites at the seam."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            ;; THE SUBJECT. Requiring it is half the witness.
            [hicasso.login.host :as host]
            [hicasso.login.policy :as policy])
  (:import [java.util.concurrent TimeUnit]))

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
      (require 're-frame.http.test-support :reload)
      (require 're-frame.machines :reload)
      (require 'login.model :reload)
      (f))))

;; ===========================================================================
;; The sidecar — slice B's launcher on the login fixture, port 0
;; ===========================================================================

(def ^:private launcher
  (.getCanonicalPath (io/file "../ssr-node/bin/serve.cjs")))

(def ^:private fixture-module
  (.getCanonicalPath (io/file "test/fixtures/node/login_host.cjs")))

(def ^:private boot-timeout-ms 30000)

(defn- pump-lines!
  [stream on-line on-eof]
  (doto (Thread. (fn []
                   (try
                     (with-open [r (io/reader stream)]
                       (doseq [line (line-seq r)] (on-line line)))
                     (finally (on-eof))))
                 "rf2-login-host-sidecar-pump")
    (.setDaemon true)
    (.start)))

(defn- entry-env
  "The environment the fixture module reads its entry table out of — the
  application's OWN policy, JSON-encoded for a CommonJS module to parse.
  Nothing in the fixture spells a key; this is where they come from, and
  it is the same Var `server.cljs` derives the shipped bundle's
  `stateAllowlist` / `runtimeAllowlist` from."
  []
  {"RF2_LOGIN_ENTRY"             policy/root-entry
   "RF2_LOGIN_BUILD_ID"          host/build-id
   "RF2_LOGIN_STATE_ALLOWLIST"   (json/write-str
                                   (mapv pr-str (:app-db policy/render-state-policy)))
   "RF2_LOGIN_RUNTIME_ALLOWLIST" (json/write-str
                                   (mapv pr-str (:runtime-db policy/render-state-policy)))})

(defn- spawn-sidecar!
  "Spawn `node bin/serve.cjs --module <login fixture> --port 0 …` and wait
  for the launcher's ONE ready line. Returns `{:process :url :stderr}`."
  []
  (let [pb     (ProcessBuilder. ^java.util.List
                                ["node" launcher
                                 "--module" fixture-module
                                 "--port" "0"
                                 "--isolates" "2"
                                 "--timeout-ms" "1000"
                                 "--admission-ms" "250"])
        _      (doto (.environment pb)
                 (.putAll ^java.util.Map (entry-env)))
        p      (.start pb)
        stderr (StringBuilder.)
        ready  (promise)]
    (pump-lines! (.getInputStream p)
                 (fn [line]
                   (when-not (realized? ready)
                     (when-let [m (try (json/read-str line) (catch Exception _ nil))]
                       (when (= "ready" (get m "rf.ssr-node"))
                         (deliver ready m)))))
                 #(deliver ready ::eof))
    (pump-lines! (.getErrorStream p)
                 (fn [line] (locking stderr (.append stderr line) (.append stderr "\n")))
                 (fn []))
    (let [m (deref ready boot-timeout-ms ::timeout)]
      (when-not (map? m)
        (.destroyForcibly p)
        (throw (ex-info (str "the sidecar produced no ready line (" (name m) ")"
                             "\nstderr:\n" stderr)
                        {:launcher launcher :module fixture-module})))
      {:process p :url (get m "url") :stderr stderr})))

(defn- stop-sidecar! [{:keys [^Process process]}]
  (.destroy process)
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroyForcibly process)))

(def ^:private sidecar
  "Spawned by the first `:crossing` test that asks, never by namespace
  load, so the default lane loading this ns costs nothing."
  (atom nil))

(defn- sidecar! []
  (or @sidecar (reset! sidecar (spawn-sidecar!))))

(defn- with-sidecar [f]
  (try
    (f)
    (finally
      (when-let [s @sidecar]
        (reset! sidecar nil)
        (stop-sidecar! s)))))

(use-fixtures :once with-sidecar)
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
;; UNTAGGED — no Node. The compile witness and its neighbours.
;; ===========================================================================

(deftest the-jvm-host-loads-and-constructs
  (testing "the namespace this test requires is the deployment's own host"
    (is (fn? host/handler)
        "hicasso.login.host/handler — constructed at namespace load, on a
         plain Clojure classpath, with the shared model required")
    (is (fn? (host/make-handler {:endpoint "http://127.0.0.1:8148"
                                 :build-id "login-hicasso-dev"}))
        "make-handler builds one against any endpoint — the seam this
         witness drives, and the constructor `handler` itself is built from"))

  (testing "the shared model really is in the registrar, not merely on the
            classpath — `:initial-events` names a registration that exists"
    (is (some? (rf.registrar/lookup :event :auth.login/initialise-form))
        ":auth.login/initialise-form — the host's one boot event")
    (is (some? (rf.registrar/lookup :fx :auth.login.demo/managed-stub))
        ":auth.login.demo/managed-stub — the demo backend the host's
         :fx-overrides remaps :rf.http/managed to")
    (is (some? (rf.registrar/lookup :sub :auth.login/draft))
        ":auth.login/draft — a named sub the server-rendered view reads")))

(deftest render-state-is-one-source
  (testing "the policy is a Var in a .cljc namespace, and it is the ONLY
            place the login arm spells the render-state keys"
    (is (= {:app-db     [:auth :auth.login/server-notice]
            :runtime-db [:rf.runtime/machines]}
           policy/render-state-policy)
        "hicasso.login.policy/render-state-policy — the one list")
    (is (= "hicasso.login/root" policy/root-entry)
        "and the one entry id"))

  (testing "neither reader keeps a copy. host.clj is Clojure and server.cljs
            is ClojureScript, so neither can be read by the other's compiler
            — which is exactly why a copy in either would be silent. Read
            them as TEXT and assert they name the Var instead."
    (doseq [f ["../../examples/substrates/hicasso/login/host.clj"
               "../../examples/substrates/hicasso/login/server.cljs"]]
      (let [src (slurp (io/file f))
            ;; The forms in `policy.cljc`'s own docstring do not appear
            ;; here; what would appear in a COPY is the key vector itself.
            copies (count (re-seq #"\[:auth :auth\.login/server-notice\]" src))]
        (is (str/includes? src "policy/render-state-policy")
            (str f " reads the shared Var"))
        (is (zero? copies)
            (str f " keeps no copy of the app-db key list (found "
                 copies " — a second copy can drift the SAFE way, which "
                 "the sidecar cannot refuse)"))))))

;; ===========================================================================
;; :crossing — the real launcher, a real socket, the advertised handler
;; ===========================================================================

(deftest ^:crossing the-login-host-renders-a-page
  (let [{:keys [url]} (sidecar!)
        h             (host/make-handler {:endpoint url :build-id host/build-id})
        {:keys [status headers body]} (h request)]

    (testing "a complete JVM-owned document"
      (is (= 200 status))
      (is (str/includes? body "<!DOCTYPE html>") "shell: JVM-built")
      (is (str/includes? body "<div id=\"app\"")
          "shell: the #app root the client hydrator adopts by id")
      (is (str/includes? body "src=\"/js/main.js\"")
          "shell: the client bundle the example's README names")
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
      ;; MEASURED, and worth stating because the prose used to imply
      ;; otherwise: the runtime partition is EMPTY on this deployment. The
      ;; `:auth.login/flow` machine is self-seeding — it materialises a
      ;; snapshot the first time it is dispatched at or subscribed to — and
      ;; the shipped host does neither, because the render happens in Node.
      ;; So Node's own frame seeds `:idle` and renders the form, which is
      ;; the right page. `the-machine-snapshot-crosses-when-the-host-drives-it`
      ;; below proves the partition is live rather than decorative.
      (is (= "" (marked body "login-runtime-keys"))
          "nothing in the runtime partition: the host never drives the machine")
      (println (format "[rf2-8arzr.5] login host render-state: app-db keys %s / runtime-db keys %s"
                       (pr-str (marked body "login-state-keys"))
                       (pr-str (marked body "login-runtime-keys")))))

    (testing "render-state is NOT the payload"
      (let [payload (payload-edn-of body)]
        (is (some? payload) "__rf_payload — JVM-built, from the JVM app-db")
        (is (str/includes? payload ":auth") "the browser gets the form slice")
        (is (not (str/includes? payload ":auth.login/server-notice"))
            "and never the server-only notice key")))))

(deftest ^:crossing the-machine-snapshot-crosses-when-the-host-drives-it
  (testing "`:rf.runtime/machines` is on the render-state list because a host
            that HAS driven the flow — a session-restoring one, say — must
            hand the render the state that decides which of the page's three
            faces it draws. The shipped host drives nothing, so nothing
            crosses; drive the machine from `:initial-events` and the
            snapshot rides the runtime partition."
    (let [{:keys [url]} (sidecar!)
          h (rf.ssr.ring/ssr-handler
              {:initial-events [[:auth.login/initialise-form]
                                ;; `:idle` -> `:submitting`, action
                                ;; `:clear-error`. A pure data transition —
                                ;; the HTTP request is fired by
                                ;; `submit-form`, not by the machine.
                                [:auth.login/flow [:auth.login/submit]]]
               :payload        [:auth]
               :renderer       (rf.ssr.ring.node/renderer
                                 {:endpoint     url
                                  :entry        policy/root-entry
                                  :build-id     host/build-id
                                  :render-state policy/render-state-policy
                                  :timeout-ms   1000})})
          {:keys [status body]} (h request)]
      (is (= 200 status))
      (is (= ":rf.runtime/machines" (marked body "login-runtime-keys"))
          "the machine partition crossed")
      (is (str/includes? (str (marked body "login-machines")) ":submitting")
          "carrying the state the JVM drove the flow into")
      (is (not (str/includes? (str (payload-edn-of body)) ":rf.runtime/machines"))
          "and it is render state, not payload: the browser's allowlist is
           app-db keys and names none of this"))))

(deftest ^:crossing a-widened-host-is-refused
  (testing "a host that asks for a key the entry does not allow is refused
            by the sidecar rather than served — the direction that CAN be
            caught at the seam, and the reason the other direction is
            closed by construction instead"
    ;; The widened key has to be PRESENT in app-db, or the projection's
    ;; `select-keys` omits it and the sidecar never sees the overreach.
    (rf/reg-event :login-host-test/seed-extra
      {:platforms #{:server}}
      (fn [{:keys [db]} _]
        {:db (assoc db :auth.login/not-on-the-list "widened")}))
    (let [{:keys [url]} (sidecar!)
          widened (rf.ssr.ring/ssr-handler
                    {:initial-events [[:auth.login/initialise-form]
                                      [:login-host-test/seed-extra]]
                     :payload        [:auth]
                     :renderer       (rf.ssr.ring.node/renderer
                                       {:endpoint     url
                                        :entry        policy/root-entry
                                        :build-id     host/build-id
                                        :render-state (update policy/render-state-policy
                                                              :app-db conj
                                                              :auth.login/not-on-the-list)
                                        :timeout-ms   1000})
                     :error-view     (fn [{:keys [code]}]
                                       [:main#login-error [:p (str "projected:" (name code))]])})
          {:keys [status body]} (widened request)]
      (is (>= status 500) (str "a refusal is a projected 5xx, got " status))
      (is (str/includes? body "projected:")
          "the error view rendered, so the refusal reached the projector")
      (is (not (str/includes? body "<main data-entry="))
          "and no partial page was served"))))
