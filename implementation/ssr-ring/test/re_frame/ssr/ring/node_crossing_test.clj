(ns re-frame.ssr.ring.node-crossing-test
  "THE JVM -> Node -> JVM CROSSING WITNESS (Spec 011 §HTTP response
  contract).

  The tests tagged `:crossing` run the sidecar ssr-ring's jar carries on a
  plain fixture render module (`test/fixtures/node/crossing.cjs`), build
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
        not a threshold;
    (g) under `:module` the renderer owns the sidecar: it launches it and
        renders through it, refuses a bundle built as another id at
        construction, relaunches it when it dies, and leaves no node
        process behind once closed.

  `clojure -M:crossing-test` runs exactly these (Node 24 on PATH); the
  default `:test` alias excludes the tag and never touches Node. The rows
  (a)-(f) share one owned sidecar, launched lazily by the first of them and
  stopped in the `:once` fixture's `finally`. The untagged
  construction-contract tests at the bottom need no sidecar and run in the
  default lane."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support])
  (:import [java.lang ProcessHandle]
           [java.net InetSocketAddress ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent TimeUnit]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]))

;; ===========================================================================
;; The sidecar — the fixture module under the renderer's own supervision
;; ===========================================================================

(def ^:private fixture-module
  (.getCanonicalPath (io/file "test/fixtures/node/crossing.cjs")))

(def ^:private sidecar
  "The owned sidecar rows (a)-(f) dial by `:endpoint`: they read its
  `/health`, which a renderer does not expose, so they take the renderer's
  own supervisor privately. Launched by the first `:crossing` test that
  asks, never by namespace load, so the default lane loading this ns costs
  nothing."
  (atom nil))

(defn- sidecar-url []
  ((:endpoint (or @sidecar
                  (reset! sidecar (#'rf.ssr.ring.node/own-sidecar!
                                   {:module       fixture-module
                                    :node         "node"
                                    :build-id     "crossing-build-1"
                                    :isolates     2
                                    :timeout-ms   1000
                                    :admission-ms 250}))))))

(defn- with-sidecar [f]
  (try
    (f)
    (finally
      (when-let [s @sidecar]
        (reset! sidecar nil)
        ((:stop! s))))))

(use-fixtures :once with-sidecar)
(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ---- /health ----------------------------------------------------------------

(def ^:private ^HttpClient probe-client (rf.ssr.ring.test-support/new-http-client))

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
  (rf.ssr.ring.node/renderer {:endpoint     url
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
  (rf.ssr.ring/ssr-handler
    {:frame {:initial-events [[:rf.test.crossing/init {:delay-ms delay-ms}]]}
     :payload        [:heading]
     :renderer       renderer
     :error-view     (fn [{:keys [code]}]
                       [:main#crossing-error [:p (str "projected:" (name code))]])}))

(defn- capture-errors!
  "Record every always-on error record — the production off-box shipper's
  view of the crossing. Returns the atom."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::crossing-recorder
                           (fn [record] (swap! seen conj record)))
    seen))

(defn- render-failure-ids
  "The distinct `:rf.error/id`s carried by the `:rf.error/ssr-render-failed`
  records' exceptions — the codes an operator reads in the trace stream."
  [records]
  (->> records
       (filter #(= :rf.error/ssr-render-failed (:rf.error/id %)))
       (map #(-> % :exception ex-data :rf.error/id))
       vec))

(defn- render-failure-data [records]
  (->> records
       (filter #(= :rf.error/ssr-render-failed (:rf.error/id %)))
       (map #(-> % :exception ex-data))
       first))

(defn- payload-edn-of [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

;; ===========================================================================
;; (a) (b) (c) (e) (f) — one complete crossing
;; ===========================================================================

(deftest ^:crossing one-complete-crossing-a-b-c-e-f
  (register-app!)
  (let [url           (sidecar-url)
        seen          (capture-errors!)
        frames-before (set (rf/frame-ids))
        h             (handler (node-renderer url))
        t0            (System/nanoTime)
        {:keys [status headers body]} (h request)
        elapsed-ms    (/ (- (System/nanoTime) t0) 1e6)]
    ;; (f) — observational. A number in the job log, not a threshold.
    (println (format "[node-crossing] one complete JVM->Node->JVM crossing: %.1f ms (observational, not a threshold)"
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
      (is (str/includes? body "<head><meta charset=\"utf-8\">") "head: JVM-built")
      (is (some? (payload-edn-of body)) "__rf_payload: JVM-built")
      (is (str/includes? (payload-edn-of body) "\"Crossing\"")
          "…from the JVM's own post-drain app-db")
      (is (str/includes? (str (or (get headers "content-type") (get headers "Content-Type")))
                         "text/html")
          "headers: the JVM's"))

    (testing "(c) render-state != payload: a server-only key, named by no entry,
              is rendered by the fixture and ABSENT from __rf_payload"
      (is (str/includes? body (str "<p class=\"crossing-server-only\">\"" server-only-value "\"</p>"))
          "on the JVM's render-state allowlist, which alone decides what crosses: the fixture rendered it")
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
    (rf.error-emit/unregister-error-listener! ::crossing-recorder)))

;; ===========================================================================
;; (d) — the deadline arm, and the other distinct codes
;; ===========================================================================

(defn- assert-projected-5xx!
  "The shape every failure arm shares: a projected 500 through
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
  (let [url            (sidecar-url)
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
    (rf.error-emit/unregister-error-listener! ::crossing-recorder)))

(deftest ^:crossing refusal-arm-build-identity-mismatch-is-refused-by-the-sidecar
  (register-app!)
  (let [url           (sidecar-url)
        seen          (capture-errors!)
        frames-before (set (rf/frame-ids))
        h             (handler (node-renderer url :build-id "some-other-build"))
        response      (h request)]
    (assert-projected-5xx! response frames-before)
    (is (= [:rf.error/ssr-node-refused] (render-failure-ids @seen)))
    (let [{:keys [status refusal]} (render-failure-data @seen)]
      (is (= 409 status) "the sidecar's status rides in ex-data…")
      ;; The code is the sidecar's vocabulary, carried opaquely: asserted by
      ;; its parts so nothing on the JVM spells the namespace (the sidecar's
      ;; absence witness holds that, over raw text, everywhere).
      (is (= ["rf.ssr-node" "build-identity-mismatch"]
             [(namespace refusal) (name refusal)])))
    (rf.error-emit/unregister-error-listener! ::crossing-recorder)))

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
    (rf.error-emit/unregister-error-listener! ::crossing-recorder)))

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
        (rf.error-emit/unregister-error-listener! ::crossing-recorder)))))

(deftest ^:crossing build-skew-arm-a-200-that-names-no-build-is-refused-on-the-jvm
  ;; The companion to the row above, and the one absence needs a row of its
  ;; own for: the skew row sends a header, so it can never exercise the arm
  ;; where there is none. A 200 carrying no `x-rf-ssr-build` is not a
  ;; verifiable match — a proxy that strips the header, a malformed service,
  ;; an impostor answering in the sidecar's place all present identically —
  ;; and nothing downstream checks the bytes. Unverified bytes must not reach
  ;; the JVM-owned document.
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
            "the same refusal the wrong build gets — no separate error category")
        (let [data (render-failure-data @seen)]
          (is (= "crossing-build-1" (:expected data)) "the configured expectation")
          (is (contains? data :serving) "…and an honest missing-serving slot")
          (is (nil? (:serving data)) "…carrying nil, because the answer named none"))
        (rf.error-emit/unregister-error-listener! ::crossing-recorder)))))

(defn- construction-error [opts]
  (try (rf.ssr.ring.node/renderer opts) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ===========================================================================
;; (g) — `:module`: the renderer owns the sidecar
;; ===========================================================================

(def ^:private module-opts
  {:module       fixture-module
   :entry        "app/root"
   :args         {:root :crossing}
   :build-id     "crossing-build-1"
   :isolates     2
   :render-state {:app-db [:heading :server-only :delay-ms]}})

(defn- sidecar-children
  "The live direct children of this JVM — where an owned sidecar's node
  process sits."
  []
  (->> (.iterator (.children (ProcessHandle/current)))
       iterator-seq
       (filter #(.isAlive ^ProcessHandle %))
       set))

(defn- the-new-child
  "The one live child that is not in `before`."
  ^ProcessHandle [before]
  (let [fresh (remove before (sidecar-children))]
    (is (= 1 (count fresh)) "exactly one new node process")
    (first fresh)))

(deftest ^:crossing g-module-mode-launches-the-sidecar-and-renders-through-it
  (register-app!)
  (let [before (sidecar-children)]
    (with-open [renderer (rf.ssr.ring.node/renderer module-opts)]
      (is (.isAlive (the-new-child before)) "construction launched the sidecar")
      (let [{:keys [status body]} ((handler renderer) request)]
        (is (= 200 status))
        (is (str/includes? body "<h1 class=\"crossing-heading\">\"Crossing\"</h1>")
            "the drained value crossed to the owned sidecar and back")
        (is (str/includes? body "<main data-entry=\"app/root\" data-args=\"{:root :crossing}\">")
            "Node's body, verbatim")))))

(deftest ^:crossing g-module-mode-refuses-a-bundle-built-as-another-id-at-construction
  (let [before (sidecar-children)
        data   (construction-error (assoc module-opts :build-id "some-other-build"))]
    (is (= :rf.error/ssr-node-build-skew (:rf.error/id data))
        "refused at boot, under the answer-side refusal's id")
    (is (= {:expected "some-other-build" :serving "crossing-build-1"}
           (select-keys data [:expected :serving])))
    (is (= before (sidecar-children)) "and the refused sidecar is not left running")))

(defn- render-until-200
  "Render `request` through `h` every 50 ms until one answers 200 or
  `timeout-ms` passes; the statuses, in order."
  [h timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [statuses []]
      (let [statuses (conj statuses (:status (h request)))]
        (if (or (= 200 (peek statuses)) (> (System/currentTimeMillis) deadline))
          statuses
          (do (Thread/sleep 50) (recur statuses)))))))

(deftest ^:crossing g-module-mode-relaunches-a-sidecar-that-dies
  (register-app!)
  (let [before (sidecar-children)
        seen   (capture-errors!)]
    (with-open [renderer (rf.ssr.ring.node/renderer module-opts)]
      (let [h     (handler renderer)
            first-child (the-new-child before)]
        (is (= 200 (:status (h request))))
        (.destroyForcibly first-child)
        (.get (.onExit first-child) 10 TimeUnit/SECONDS)
        (let [statuses (render-until-200 h 20000)]
          (is (= 200 (peek statuses)) "a request after the relaunch renders")
          (is (every? #{:rf.error/ssr-node-unreachable} (render-failure-ids @seen))
              (str "every request while it was down failed as unreachable: " statuses)))
        (is (not= (.pid first-child) (.pid (the-new-child before)))
            "the sidecar answering now is a new process")))
    (rf.error-emit/unregister-error-listener! ::crossing-recorder)))

(deftest ^:crossing g-closing-a-module-renderer-leaves-no-node-process
  (register-app!)
  (let [before   (sidecar-children)
        renderer (rf.ssr.ring.node/renderer module-opts)
        child    (the-new-child before)
        seen     (capture-errors!)]
    (.close ^java.io.Closeable renderer)
    (is (not (.isAlive child)) "the sidecar is gone by the time .close returns")
    (is (= before (sidecar-children)) "and nothing else was left behind")
    (is (= 500 (:status ((handler renderer) request))) "a closed renderer renders nothing…")
    (is (= [:rf.error/ssr-node-unreachable] (render-failure-ids @seen)) "…and says so")
    (.close ^java.io.Closeable renderer)
    (is (= before (sidecar-children)) "a second close is harmless")
    (rf.error-emit/unregister-error-listener! ::crossing-recorder)))

;; ===========================================================================
;; Construction — no sidecar needed, so these run in the default lane
;; ===========================================================================

(def ^:private good-opts
  {:entry "app/root" :build-id "b1" :render-state {:app-db [:heading]}})

(deftest the-node-renderer-validates-its-opts-at-construction
  (is (= 1750 (rf.ssr.ring.node/http-timeout-ms {:timeout-ms 1000 :admission-ms 250}))
      "the per-request HTTP timeout = timeoutMs + admission + wire margin")
  (testing "accepted: the minimal opts, a non-loopback endpoint (trust the
            programmer), and the escape-hatch projector"
    (doseq [opts [good-opts
                  (assoc good-opts :endpoint "https://render.internal:8148")
                  (assoc good-opts :render-state (fn [_] {:rf/app-db {}}))]]
      (with-open [renderer (rf.ssr.ring.node/renderer opts)]
        (is (fn? renderer) (pr-str opts)))))
  (testing "refused at construction, naming the opt — before any sidecar is
            launched, so none of these needs Node"
    (doseq [[id opt opts] [[:rf.error/ssr-node-renderer-opt-invalid :entry (dissoc good-opts :entry)]
                           [:rf.error/ssr-node-renderer-opt-invalid :build-id (dissoc good-opts :build-id)]
                           [:rf.error/ssr-node-renderer-opt-invalid :endpoint (assoc good-opts :endpoint "127.0.0.1:8148")]
                           [:rf.error/ssr-node-renderer-opt-invalid :timeout-ms (assoc good-opts :timeout-ms 0)]
                           [:rf.error/ssr-node-renderer-opt-invalid :admission-ms (assoc good-opts :admission-ms -1)]
                           [:rf.error/ssr-node-renderer-opt-invalid :args (assoc good-opts :args (fn []))]
                           [:rf.error/ssr-missing-payload-policy :render-state (dissoc good-opts :render-state)]
                           [:rf.error/ssr-node-renderer-opt-invalid :endpoint (assoc good-opts :module fixture-module :endpoint "http://127.0.0.1:8148")]
                           [:rf.error/ssr-node-renderer-opt-invalid :module (assoc good-opts :module "")]
                           [:rf.error/ssr-node-renderer-opt-invalid :module (assoc good-opts :module "test/fixtures/node/no-such-bundle.cjs")]
                           [:rf.error/ssr-node-renderer-opt-invalid :node (assoc good-opts :module fixture-module :node "")]
                           [:rf.error/ssr-node-renderer-opt-invalid :isolates (assoc good-opts :module fixture-module :isolates 0)]
                           [:rf.error/ssr-node-renderer-opt-invalid :node (assoc good-opts :node "node")]
                           [:rf.error/ssr-node-renderer-opt-invalid :isolates (assoc good-opts :isolates 2)]]]
      (is (= [id opt] ((juxt :rf.error/id :opt) (construction-error opts))) (pr-str opt)))))

(deftest the-launch-copies-every-sidecar-file-the-classpath-carries
  ;; `sidecar-files` is what construction copies out of the jar; the
  ;; resource directory is what the jar carries. A file the sidecar gains,
  ;; copied into the resources but not named here, would be left behind and
  ;; the launch would fail on a missing module.
  (let [root    (io/file "resources/re_frame/ssr_node")
        carried (->> (file-seq root)
                     (filter #(.isFile ^java.io.File %))
                     (map #(.replace (str (.relativize (.toPath root) (.toPath ^java.io.File %)))
                                     java.io.File/separatorChar \/))
                     sort)]
    (is (some #{"bin/serve.cjs"} carried) "the resource directory was read")
    (is (= carried (sort @#'rf.ssr.ring.node/sidecar-files)))))

;; ===========================================================================
;; The handler's `:payload-include-sensitive` reaches the render state, so the
;; markup the renderer prints and the payload agree on a permitted value. A
;; capturing stub stands in for the sidecar, so this runs in the default lane.
;; ===========================================================================

(defn- with-capturing-stub-sidecar
  "Run `f` with the URL of a JDK HttpServer that records each request body in
  `seen` and answers 200 as build `build-id`."
  [seen build-id f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        bytes  (.getBytes "<main>stub</main>" "UTF-8")]
    (.createContext server "/render"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange ex]
                        (reset! seen (slurp (.getRequestBody ex) :encoding "UTF-8"))
                        (.add (.getResponseHeaders ex) "x-rf-ssr-build" build-id)
                        (.sendResponseHeaders ex 200 (alength bytes))
                        (with-open [out (.getResponseBody ex)] (.write out bytes))
                        nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally (.stop server 0)))))

(deftest the-handlers-sensitive-permit-reaches-the-render-state
  (rf/reg-event :rf.test.crossing/seed-session
    {:platforms #{:server}}
    (fn [_ _]
      {:db        {:heading "Crossing"
                   :session {:csrf "csrf-abc-123" :upstream-key "sk-server-only" :user "alice"}}
       :sensitive [[:session :csrf] [:session :upstream-key]]}))
  (let [seen (atom nil)]
    (with-capturing-stub-sidecar seen "crossing-build-1"
      (fn [url]
        ((rf.ssr.ring/ssr-handler
           {:frame {:initial-events            [[:rf.test.crossing/seed-session]]}
            :payload                   [:heading :session]
            :payload-include-sensitive [[:session :csrf]]
            :renderer                  (node-renderer url :render-state {:app-db [:session]})})
         request)
        (is (= {:csrf "csrf-abc-123" :upstream-key :rf/redacted :user "alice"}
               (some-> @seen json/read-str (get "state") (get ":session") edn/read-string)))
        (is (not (str/includes? (str @seen) "sk-server-only"))
            "the withheld value crosses to the renderer by no route")))))
