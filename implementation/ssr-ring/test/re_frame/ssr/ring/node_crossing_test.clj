(ns re-frame.ssr.ring.node-crossing-test
  "rf2-8arzr.4 — THE JVM -> Node -> JVM CROSSING WITNESS (slice D of the
  ssr-node crossing programme; Spec 011 §HTTP response contract).

  The tests tagged `:crossing` spawn implementation/ssr-node's serve
  launcher on a plain fixture render module (`test/fixtures/node/
  crossing.cjs`) via ProcessBuilder on port 0, parse its ready line, build
  `ssr-handler` with `re-frame.ssr.ring.node/renderer`, and prove —

    (a) the JVM drains `:initial-events` FIRST and the fixture receives the
        drained value through the state partition (it echoes the value into
        its markup);
    (b) the response is a complete JVM-owned document — JVM head,
        `__rf_payload` built from the JVM app-db, JVM shell / status /
        headers — with Node's body bytes inserted verbatim;
    (c) render-state != payload: a server-only key is rendered by the
        fixture and ABSENT from `__rf_payload`;
    (d) the deadline arm: an entry that sleeps past `timeoutMs` -> the
        sidecar's 504 -> a projected 5xx through `:error-view`, a distinct
        code, no partial page (plus the refusal, unreachable and build-skew
        arms, each its own distinct code, since they cost a row apiece);
    (e) cleanup on success AND on failure: the request frame is destroyed
        (the frame registry is back to what it was) and the sidecar's
        `/health` shows no in-flight work;
    (f) ONE observational complete-crossing timing, printed — a number,
        not a threshold.

  `clojure -M:crossing-test` runs exactly these (Node 24 on PATH); the
  default `:test` alias excludes the tag and never touches Node. The
  sidecar is spawned lazily by the first `:crossing` test and killed in
  the `:once` fixture's `finally`. The untagged construction-contract
  tests at the bottom need no sidecar and run in the default lane."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.node :as node]
            [re-frame.ssr.ring.test-support :as ts])
  (:import [java.net InetSocketAddress ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent TimeUnit]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]))

;; ===========================================================================
;; The sidecar — slice B's launcher on the fixture module, port 0
;; ===========================================================================

(def ^:private launcher
  (.getCanonicalPath (io/file "../ssr-node/bin/serve.cjs")))

(def ^:private fixture-module
  (.getCanonicalPath (io/file "test/fixtures/node/crossing.cjs")))

(def ^:private boot-timeout-ms 30000)

(defn- pump-lines!
  "Read `stream` line by line on a daemon thread, calling `on-line` for
  each; `on-eof` once the stream closes."
  [stream on-line on-eof]
  (doto (Thread. (fn []
                   (try
                     (with-open [r (io/reader stream)]
                       (doseq [line (line-seq r)] (on-line line)))
                     (finally (on-eof))))
                 "rf2-crossing-sidecar-pump")
    (.setDaemon true)
    (.start)))

(defn- spawn-sidecar!
  "Spawn `node bin/serve.cjs --module <fixture> --port 0 …` and wait for
  the ready line — the ONE JSON object the launcher writes to stdout,
  discriminated by its `\"rf.ssr-node\": \"ready\"` key (scanned line by
  line, as the README says a reader should). Returns
  `{:process :ready :url :stderr}`; throws with the captured stderr when
  no ready line arrives."
  []
  (let [pb      (ProcessBuilder. ^java.util.List
                                 ["node" launcher
                                  "--module" fixture-module
                                  "--port" "0"
                                  "--isolates" "2"
                                  "--timeout-ms" "1000"
                                  "--admission-ms" "250"])
        p       (.start pb)
        stderr  (StringBuilder.)
        ready   (promise)]
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
      {:process p :ready m :url (get m "url") :stderr stderr})))

(defn- stop-sidecar! [{:keys [^Process process]}]
  ;; SIGTERM where the platform has one (a graceful close, exit 0); Windows
  ;; terminates outright either way. A second, forcible kill if it lingers.
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
(use-fixtures :each ts/reset-runtime)

;; ---- /health ----------------------------------------------------------------

(def ^:private ^HttpClient probe-client (ts/new-http-client))

(defn- health
  "The sidecar's `/health` body, parsed."
  [url]
  (let [req  (-> (HttpRequest/newBuilder (URI/create (str url "/health")))
                 (.timeout (Duration/ofSeconds 5))
                 (.GET)
                 (.build))
        resp (.send probe-client req (HttpResponse$BodyHandlers/ofString))]
    (json/read-str (.body resp))))

(defn- await-idle-isolates!
  "Poll `/health` until no isolate is busy and nothing waits, or
  `timeout-ms` elapses; RETURNS the last `isolates` map either way, so the
  caller's assertion names the state it saw."
  [url timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [{:strs [busy waiting] :as isolates} (get (health url) "isolates")]
        (if (or (and (zero? busy) (zero? waiting))
                (>= (System/currentTimeMillis) deadline))
          isolates
          (do (Thread/sleep 25) (recur)))))))

;; ===========================================================================
;; The app under test, and the handler around the Node renderer
;; ===========================================================================

(def ^:private request
  {:uri "/crossing" :request-method :get :headers {}})

(def ^:private server-only-value
  "Rendered by the fixture (it is on the render-state allowlist) and never
  in `__rf_payload` (it is not on the payload allowlist)."
  "jvm-only-7f3a")

(defn- register-app! []
  (rf/reg-event :rf.test.crossing/init
    {:platforms #{:server}}
    (fn [_ [_ {:keys [delay-ms]}]]
      {:db (cond-> {:heading     "Crossing"
                    :server-only server-only-value
                    :hidden      "on-no-allowlist"}
             delay-ms (assoc :delay-ms delay-ms))})))

(defn- node-renderer
  [url & {:keys [timeout-ms render-state build-id]
          :or   {timeout-ms 1000 build-id "crossing-build-1"}}]
  (node/renderer {:endpoint     url
                  :entry        "app/root"
                  :args         {:root :crossing}
                  :build-id     build-id
                  :timeout-ms   timeout-ms
                  :render-state (or render-state
                                    {:app-db [:heading :server-only :delay-ms]})}))

(defn- handler
  "`ssr-handler` around `renderer`: the boot event, a payload policy
  NARROWER than the render-state policy (c), and an `:error-view` that
  stamps a marker no success page carries."
  [renderer & {:keys [delay-ms]}]
  (ssr-ring/ssr-handler
    {:initial-events [[:rf.test.crossing/init {:delay-ms delay-ms}]]
     :payload        [:heading]
     :renderer       renderer
     :error-view     (fn [{:keys [code]}]
                       [:main#crossing-error [:p (str "projected:" (name code))]])}))

(defn- capture-errors!
  "Record every always-on error record — the production off-box shipper's
  view of the crossing. Returns the atom."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors ::crossing-recorder
                           (fn [record] (swap! seen conj record)))
    seen))

(defn- render-failure-ids
  "The distinct `:rf.error/id`s carried by the `:rf.error/ssr-render-failed`
  records' exceptions — the codes an operator reads in the trace stream."
  [records]
  (->> records
       (filter #(= :rf.error/ssr-render-failed (:error %)))
       (map #(-> % :exception ex-data :rf.error/id))
       vec))

(defn- render-failure-data [records]
  (->> records
       (filter #(= :rf.error/ssr-render-failed (:error %)))
       (map #(-> % :exception ex-data))
       first))

(defn- payload-edn-of [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

;; ===========================================================================
;; (a) (b) (c) (e) (f) — one complete crossing
;; ===========================================================================

(deftest ^:crossing one-complete-crossing-a-b-c-e-f
  (register-app!)
  (let [{:keys [url]} (sidecar!)
        seen          (capture-errors!)
        frames-before (set (rf/frame-ids))
        h             (handler (node-renderer url))
        t0            (System/nanoTime)
        {:keys [status headers body]} (h request)
        elapsed-ms    (/ (- (System/nanoTime) t0) 1e6)]
    ;; (f) — observational. A number in the job log, not a threshold.
    (println (format "[rf2-8arzr.4] one complete JVM->Node->JVM crossing: %.1f ms (observational, not a threshold)"
                     elapsed-ms))

    (testing "(a) the JVM drained :initial-events FIRST; the fixture received
              the drained value through the state partition and echoed it"
      (is (= 200 status) "the JVM's status — the seam never touches it")
      (is (str/includes? body "<h1 class=\"crossing-heading\">\"Crossing\"</h1>")
          "the boot event's value, as the EDN text the wire carried, in Node's markup"))

    (testing "(b) a complete JVM-owned document around Node's body bytes"
      (is (str/includes? body "<!DOCTYPE html>") "shell: JVM-built")
      (is (str/includes? body "<div id=\"app\"") "shell: the #app root wraps the body")
      (is (str/includes? body "<main data-entry=\"app/root\" data-args=\"{:root :crossing}\">")
          "Node's body, verbatim, with the entry and the EDN-text args it was handed")
      (is (str/includes? body "data-rf-head-hash") "head: JVM-resolved, its marker rides")
      (is (some? (payload-edn-of body)) "__rf_payload: JVM-built")
      (is (str/includes? (payload-edn-of body) "\"Crossing\"")
          "…from the JVM's own post-drain app-db")
      (is (str/includes? (str (or (get headers "content-type") (get headers "Content-Type")))
                         "text/html")
          "headers: the JVM's")
      (is (not (str/includes? body "data-rf-render-hash"))
          "a native root carries no structural hash: no wire marker (S1)")
      (is (not (str/includes? body "render-hash"))
          "…and no payload :rf/render-hash"))

    (testing "(c) render-state != payload: a server-only key is rendered by the
              fixture and ABSENT from __rf_payload"
      (is (str/includes? body (str "<p class=\"crossing-server-only\">\"" server-only-value "\"</p>"))
          "on the render-state allowlist: the fixture rendered it")
      (is (not (str/includes? (payload-edn-of body) server-only-value))
          "not on the payload allowlist: the hydration payload never carries it")
      (is (not (str/includes? body "on-no-allowlist"))
          "a key on NEITHER allowlist crosses nowhere"))

    (testing "(e) cleanup on success: the request frame is destroyed and the
              sidecar shows no in-flight work"
      (is (= frames-before (set (rf/frame-ids))) "the frame registry is back to what it was")
      (let [{:strs [busy waiting]} (await-idle-isolates! url 2000)]
        (is (= 0 busy)) (is (= 0 waiting))))

    (is (empty? (render-failure-ids @seen)) "no render failure was projected")
    (rf/unregister-listener! :errors ::crossing-recorder)))

;; ===========================================================================
;; (d) — the deadline arm, and the other distinct codes
;; ===========================================================================

(defn- assert-projected-5xx!
  "The S5 shape every failure arm shares: a projected 500 through
  `:error-view`, the JVM's status (never the sidecar's), no hydration
  payload, no partial page, and the request frame gone."
  [{:keys [status body]} frames-before]
  (is (= 500 status) "projected, fail-closed — the JVM's status, never the sidecar's")
  (is (str/includes? body "<main id=\"crossing-error\">") "the :error-view rendered")
  (is (str/includes? body "projected:") "…from the sanitised public error")
  (is (not (str/includes? body "__rf_payload")) "no hydration payload on the error arm")
  (is (not (str/includes? body "<main data-entry")) "no partial success page")
  (is (not (str/includes? body "crossing-build-1")) "no sidecar detail on the wire")
  (is (= frames-before (set (rf/frame-ids))) "(e) cleanup on failure: the frame is destroyed"))

(deftest ^:crossing d-deadline-arm-sidecar-504-crosses-back-as-a-projected-5xx
  (register-app!)
  (let [{:keys [url]}  (sidecar!)
        seen           (capture-errors!)
        before         (get (health url) "isolates")
        frames-before  (set (rf/frame-ids))
        ;; A 300 ms deadline against a 3 s render: the sidecar terminates the
        ;; isolate at the deadline and answers 504 long before the JVM's own
        ;; derived timeout (300 + 250 + 500 ms) could.
        h              (handler (node-renderer url :timeout-ms 300) :delay-ms 3000)
        response       (h request)]
    (testing "(d) an entry that sleeps past timeoutMs -> sidecar 504 -> projected
              5xx through :error-view, a distinct code, no partial page"
      (assert-projected-5xx! response frames-before)
      (is (= [:rf.error/ssr-node-deadline] (render-failure-ids @seen))
          "ONE render failure, with the deadline's own code")
      (let [{:keys [observed-by timeout-ms http-timeout-ms]} (render-failure-data @seen)]
        (is (= :sidecar observed-by) "the sidecar's 504 observed it, not the JVM's timer")
        (is (= 300 timeout-ms))
        (is (= (+ 300 250 500) http-timeout-ms) "the derived HTTP timeout rode in ex-data")))
    (testing "(e) cleanup on failure: the sidecar replaced the terminated isolate
              and shows no in-flight work"
      (let [{:strs [busy waiting replacements]} (await-idle-isolates! url 5000)]
        (is (= 0 busy)) (is (= 0 waiting))
        (is (= (inc (get before "replacements")) replacements)
            "exactly one isolate was terminated and replaced — the deadline was the sidecar's")))
    (rf/unregister-listener! :errors ::crossing-recorder)))

(deftest ^:crossing refusal-arm-a-key-the-entry-does-not-allowlist
  (register-app!)
  (let [{:keys [url]} (sidecar!)
        seen          (capture-errors!)
        frames-before (set (rf/frame-ids))
        ;; `:hidden` is on the JVM's render-state allowlist but NOT on the
        ;; entry's stateAllowlist: the sidecar refuses (400) — the allowlist
        ;; belongs to the entry, so a caller cannot widen its own allowance.
        h             (handler (node-renderer url :render-state {:app-db [:heading :hidden]}))
        response      (h request)]
    (assert-projected-5xx! response frames-before)
    (is (= [:rf.error/ssr-node-refused] (render-failure-ids @seen)))
    (let [{:keys [status refusal detail]} (render-failure-data @seen)]
      (is (= 400 status) "the sidecar's status rides in ex-data…")
      ;; The code is the sidecar's vocabulary, carried opaquely: asserted by
      ;; its parts so nothing on the JVM spells the namespace (the sidecar's
      ;; absence witness holds that, over raw text, everywhere).
      (is (= ["rf.ssr-node" "state-key-not-allowed"]
             [(namespace refusal) (name refusal)])
          "…with its refusal code, as a keyword")
      (is (= ":hidden" (get detail "key")) "…and its detail"))
    (let [{:strs [busy waiting]} (await-idle-isolates! url 2000)]
      (is (= 0 busy)) (is (= 0 waiting)))
    (rf/unregister-listener! :errors ::crossing-recorder)))

(deftest ^:crossing refusal-arm-build-identity-mismatch-is-refused-by-the-sidecar
  (register-app!)
  (let [{:keys [url]} (sidecar!)
        seen          (capture-errors!)
        frames-before (set (rf/frame-ids))
        h             (handler (node-renderer url :build-id "some-other-build"))
        response      (h request)]
    (assert-projected-5xx! response frames-before)
    (is (= [:rf.error/ssr-node-refused] (render-failure-ids @seen)))
    (let [{:keys [status refusal]} (render-failure-data @seen)]
      (is (= 409 status))
      (is (= ["rf.ssr-node" "build-identity-mismatch"]
             [(namespace refusal) (name refusal)])))
    (rf/unregister-listener! :errors ::crossing-recorder)))

(defn- closed-port
  "A loopback port nothing listens on: bind one, read it, release it."
  []
  (with-open [ss (ServerSocket. 0)]
    (.getLocalPort ss)))

(deftest ^:crossing unreachable-arm-no-sidecar-at-the-endpoint
  (register-app!)
  (let [seen          (capture-errors!)
        frames-before (set (rf/frame-ids))
        endpoint      (str "http://127.0.0.1:" (closed-port))
        h             (handler (node-renderer endpoint))
        response      (h request)]
    (assert-projected-5xx! response frames-before)
    (is (= [:rf.error/ssr-node-unreachable] (render-failure-ids @seen)))
    (let [{:keys [endpoint ex-class]} (render-failure-data @seen)]
      (is (str/starts-with? endpoint "http://127.0.0.1:"))
      (is (string? ex-class)))
    (rf/unregister-listener! :errors ::crossing-recorder)))

(defn- with-stub-sidecar
  "Run `f` with the URL of a JDK HttpServer answering every request with
  `status`, `headers` and `body` — a sidecar impostor for the answers the
  real one can never give (a 200 from the wrong build, and a 200 that names
  no build at all)."
  [status headers ^String body f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        bytes  (.getBytes body "UTF-8")]
    (.createContext server "/render"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange ex]
                        (doseq [[^String k ^String v] headers]
                          (.add (.getResponseHeaders ex) k v))
                        (.sendResponseHeaders ex status (alength bytes))
                        (with-open [out (.getResponseBody ex)] (.write out bytes))
                        nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally (.stop server 0)))))

(deftest ^:crossing build-skew-arm-a-200-from-the-wrong-build-is-refused-on-the-jvm
  (register-app!)
  (with-stub-sidecar 200 {"x-rf-ssr-build" "drifted-build-9"} "<main>impostor</main>"
    (fn [url]
      (let [seen          (capture-errors!)
            frames-before (set (rf/frame-ids))
            response      ((handler (node-renderer url)) request)]
        (assert-projected-5xx! response frames-before)
        (is (not (str/includes? (:body response) "impostor")) "the skewed body never ships")
        (is (= [:rf.error/ssr-node-build-skew] (render-failure-ids @seen)))
        (is (= {:expected "crossing-build-1" :serving "drifted-build-9"}
               (select-keys (render-failure-data @seen) [:expected :serving])))
        (rf/unregister-listener! :errors ::crossing-recorder)))))

(deftest ^:crossing build-skew-arm-a-200-that-names-no-build-is-refused-on-the-jvm
  ;; The companion to the row above, and the one absence needs a row of its
  ;; own for: the skew row sends a header, so it can never exercise the arm
  ;; where there is none. A 200 carrying no `x-rf-ssr-build` is not a
  ;; verifiable match — a proxy that strips the header, a malformed service,
  ;; an impostor answering in the sidecar's place all present identically —
  ;; and this adapter returns `:render-hash nil` for a native root, so the
  ;; structural hydration-hash channel offers no second check. Unverified
  ;; bytes must not reach the JVM-owned document.
  (register-app!)
  (with-stub-sidecar 200 {} "<main>impostor</main>"
    (fn [url]
      (let [seen          (capture-errors!)
            frames-before (set (rf/frame-ids))
            response      ((handler (node-renderer url)) request)]
        (assert-projected-5xx! response frames-before)
        (is (not (str/includes? (:body response) "impostor"))
            "the unverified body never ships")
        (is (= [:rf.error/ssr-node-build-skew] (render-failure-ids @seen))
            "the same refusal the wrong build gets — no new error category")
        (let [data (render-failure-data @seen)]
          (is (= "crossing-build-1" (:expected data)) "the configured expectation")
          (is (contains? data :serving) "…and an honest missing-serving slot")
          (is (nil? (:serving data)) "…carrying nil, because the answer named none"))
        (rf/unregister-listener! :errors ::crossing-recorder)))))

;; ===========================================================================
;; The construction contract — no sidecar, runs in the default lane
;; ===========================================================================

(def ^:private good-opts
  {:entry "app/root" :build-id "b1" :render-state {:app-db [:heading]}})

(defn- construction-error [opts]
  (try (node/renderer opts) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest the-node-renderer-validates-its-opts-at-construction
  (testing "the defaults: endpoint, deadline, admission budget, and the derived timeout"
    (is (fn? (node/renderer good-opts)) "entry + build-id + render-state suffice")
    (is (= (+ 1000 250 500)
           (node/http-timeout-ms {:timeout-ms 1000 :admission-ms 250}))
        "the explicit per-request HTTP timeout = timeoutMs + admission + wire margin"))
  (testing "each required opt fails closed with :rf.error/ssr-node-renderer-opt-invalid naming it"
    (doseq [[opt opts] [[:entry    (dissoc good-opts :entry)]
                        [:entry    (assoc good-opts :entry "")]
                        [:build-id (dissoc good-opts :build-id)]
                        [:endpoint (assoc good-opts :endpoint "127.0.0.1:8148")]
                        [:endpoint (assoc good-opts :endpoint "ftp://x")]
                        [:timeout-ms (assoc good-opts :timeout-ms 0)]
                        [:timeout-ms (assoc good-opts :timeout-ms "1000")]
                        [:admission-ms (assoc good-opts :admission-ms -1)]
                        [:args     (assoc good-opts :args (fn []))]
                        [:args     (assoc good-opts :args 1/3)]]]
      (let [data (construction-error opts)]
        (is (= :rf.error/ssr-node-renderer-opt-invalid (:rf.error/id data)) (pr-str opt))
        (is (= opt (:opt data)) (pr-str opt))
        (is (keyword? (:recovery data))))))
  (testing "a non-loopback endpoint is NOT refused (S7 — trust the programmer)"
    (is (fn? (node/renderer (assoc good-opts :endpoint "https://render.internal:8148")))))
  (testing ":args is optional; nil is a value and rides"
    (is (fn? (node/renderer (assoc good-opts :args nil)))))
  (testing ":render-state is required — the payload family's missing-policy id, with :opt :render-state"
    (let [data (construction-error (dissoc good-opts :render-state))]
      (is (= :rf.error/ssr-missing-payload-policy (:rf.error/id data)))
      (is (= :render-state (:opt data))))
    (let [data (construction-error (assoc good-opts :render-state {:app-db []}))]
      (is (= :rf.error/ssr-malformed-payload-allowlist (:rf.error/id data)))
      (is (= :render-state (:opt data)))))
  (testing "the escape-hatch projector constructs"
    (is (fn? (node/renderer (assoc good-opts :render-state (fn [_] {:rf/app-db {}})))))))
