(ns spike.graaljs
  "SPIKE, NOT FOR MERGE: does `react-dom/server` render the
  compiled Fresco login example inside this JVM under GraalJS, and how does
  that compare with the Node sidecar on the same machine?

  Two bundles, both release builds of `:examples/login-fresco-server`, built
  through the lane helper from `implementation/`:

      # what the sidecar loads today: npm deps stay runtime `require`s
      node scripts/lib/shadow.cjs release examples/login-fresco-server \\
        --config-merge '{:output-dir \"out/spike-graaljs/node\"
                         :output-to  \"out/spike-graaljs/node/server.js\"}'

      # self-contained: shadow bundles React itself, from its BROWSER
      # server entry, so nothing needs Node's async_hooks/stream/util
      node scripts/lib/shadow.cjs release examples/login-fresco-server \\
        --config-merge '{:output-dir \"out/spike-graaljs/graal\"
                         :output-to  \"out/spike-graaljs/graal/server.js\"
                         :js-options {:js-provider :shadow
                                      :resolve {\"react-dom/server\"
                                                {:target :npm
                                                 :require \"react-dom/server.browser\"}}}}'

  Then, from this directory: `clojure -M:spike -m spike.graaljs <step>`,
  where step is one of `compare`, `cold`, `bench`, `memory`, `cancel`,
  `pump`, `all`. Each writes its result map to
  `out/spike-graaljs/results/<SPIKE_LABEL>-<step>.edn` and prints it.
  `SPIKE_CHECKPOINTS`, `SPIKE_WARMUP`, `SPIKE_PER_THREAD`, `SPIKE_ROUNDS`
  and `SPIKE_PUMP_N` size the runs.

  Three runtimes, one harness:

      # stock JDK 21+, 25.x jars: Truffle's fallback runtime, no JIT
      clojure -M:spike -m spike.graaljs all

      # stock JDK 21 WITH JIT: the version-matched 23.1 line, JVMCI on, and
      # the compiler (with collections, word, nativeimage, truffle-compiler
      # from the same version) on the upgrade module path
      clojure -J-XX:+UnlockExperimentalVMOptions -J-XX:+EnableJVMCI \\
              -J--upgrade-module-path=<those five jars> \\
              -M:spike:jdk21-jit -m spike.graaljs all

      # a GraalVM CE JDK (libgraal built in), polyglot jars matching it
      JAVA_CMD=<graalvm>/bin/java clojure -J--enable-native-access=ALL-UNNAMED \\
              -M:spike:graalvm-jdk -m spike.graaljs all"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [fresco.login.host :as host]
            [fresco.login.policy :as policy]
            [login.model :as model])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.lang.management ManagementFactory]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent ArrayBlockingQueue CountDownLatch TimeUnit]
           [org.graalvm.polyglot Context Engine PolyglotException Source Value]))

;; ===========================================================================
;; Paths — this namespace runs from implementation/ssr-ring/spike-graaljs
;; ===========================================================================

(def impl-dir (.getCanonicalFile (io/file "../..")))

(defn- impl-file ^java.io.File [& parts] (apply io/file impl-dir parts))

(def graal-bundle (impl-file "out" "spike-graaljs" "graal" "server.js"))
(def node-bundle  (impl-file "out" "spike-graaljs" "node" "server.js"))
(def launcher     (impl-file "ssr-node" "bin" "serve.cjs"))
(def results-dir  (impl-file "out" "spike-graaljs" "results"))

(defn- now-ns ^long [] (System/nanoTime))
(defn- ms [^long start-ns ^long end-ns] (/ (double (- end-ns start-ns)) 1e6))
(defn- round2 [x] (when x (/ (Math/round (* 100.0 (double x))) 100.0)))

(defn- percentile [sorted p]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted))
                     (int (Math/floor (* p (count sorted))))))))

(defn- summary [samples-ms]
  (let [s (vec (sort samples-ms))]
    {:n    (count s)
     :p50  (round2 (percentile s 0.50))
     :p90  (round2 (percentile s 0.90))
     :p99  (round2 (percentile s 0.99))
     :max  (round2 (peek s))
     :mean (round2 (/ (reduce + s) (max 1 (count s))))}))

;; ===========================================================================
;; GraalJS — one Engine, one Context per pool slot
;; ===========================================================================

(defn engine ^Engine []
  (-> (Engine/newBuilder (into-array String []))
      ;; Kept visible on purpose: on a stock JDK this is the line that says
      ;; the optimizing runtime is absent.
      (.option "engine.WarnInterpreterOnly" "true")
      (.build)))

(defn- js-source ^Source [^java.io.File f]
  (.build (Source/newBuilder "js" f)))

(def ^:private driver-text
  (delay (slurp (io/resource "spike/driver.js"))))

(defn- driver-source ^Source []
  (.build (Source/newBuilder "js" ^CharSequence @driver-text "spike-driver.js")))

(def ^:private shims-source
  (delay (.build (Source/newBuilder "js" ^CharSequence (slurp (io/resource "spike/shims.js"))
                                    "spike-shims.js"))))

(defn slot
  "One pool slot: a Context on the shared engine with the bundle evaluated,
  the module's `boot` run and the driver's render fn looked up. Returns
  `{:context :render-fn :timings}`."
  [^Engine engine ^Source bundle ^Source driver]
  (let [t0  (now-ns)
        ctx (-> (Context/newBuilder (into-array String ["js"]))
                (.engine engine)
                (.build))
        t1  (now-ns)
        _   (.eval ctx ^Source @shims-source)
        _   (.eval ctx bundle)
        t2  (now-ns)
        build-id (.asString (.eval ctx driver))
        t3  (now-ns)]
    {:context   ctx
     :build-id  build-id
     :render-fn (.getMember (.getBindings ctx "js") "__spikeRender")
     :timings   {:context-ms (round2 (ms t0 t1))
                 :bundle-eval-ms (round2 (ms t1 t2))
                 :boot-ms (round2 (ms t2 t3))}}))

(defn render-in-slot ^String [{:keys [^Value render-fn]} ^String request-json]
  (.asString (.execute render-fn (object-array [request-json]))))

(defn slot-stats
  "What the shims counted in this slot, and how many tasks are still queued."
  [{:keys [^Context context]}]
  (json/read-str (.asString (.execute (.getMember (.getBindings context "js") "__spikeStats")
                                      (object-array [])))))

(defn close-slot! [{:keys [^Context context]}]
  (try (.close context true) (catch Exception _ nil)))

;; ===========================================================================
;; The call the sidecar receives — captured from the real JVM host
;; ===========================================================================

(defn- with-capture-peer
  "Run `(f url seen)` against a loopback peer that answers `/render` the way
  the sidecar does and records every request body it is sent."
  [f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        seen   (atom [])
        body   (.getBytes "<p>captured</p>" "UTF-8")]
    (.createContext server "/render"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange ex]
                        (swap! seen conj (slurp (.getRequestBody ex) :encoding "UTF-8"))
                        (.add (.getResponseHeaders ex) "x-rf-ssr-build" host/build-id)
                        (.sendResponseHeaders ex 200 (alength body))
                        (with-open [out (.getResponseBody ex)] (.write out body))
                        nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) seen)
      (finally (.stop server 0)))))

(def page-request {:uri "/" :request-method :get :headers {}})

(def scenarios
  "Two frames: the shipped host's (app-db partition only) and one that drives
  the login machine, so the runtime partition crosses too."
  {:shipped {:frame model/frame-config}
   :machine {:frame {:initial-events [[:auth.login/initialise-form]
                                      [:auth.login/flow [:auth.login/submit]]]}}})

(def renderer-opts
  {:entry        policy/root-entry
   :build-id     host/build-id
   :render-state policy/render-state-policy
   :timeout-ms   1000})

(defn page-handler
  "`fresco.login.host/make-handler`'s handler with the renderer swapped."
  [scenario renderer]
  (rf.ssr.ring/ssr-handler
    {:frame          (get-in scenarios [scenario :frame])
     :payload        [:auth]
     :renderer       renderer
     :app-element-id "app"
     :script-src     "/main.js"}))

(defn capture-calls
  "{scenario request-json}: the exact JSON body `rf.ssr.ring.node/renderer`
  POSTs for each scenario."
  []
  (host/init!)
  (into {}
        (for [scenario (keys scenarios)]
          [scenario
           (with-capture-peer
             (fn [url seen]
               (let [h (page-handler scenario
                                     (rf.ssr.ring.node/renderer
                                       (assoc renderer-opts :endpoint url)))
                     {:keys [status]} (h page-request)]
                 (assert (= 200 status) (str "capture: status " status))
                 (first @seen))))])))

;; ===========================================================================
;; The in-process renderer — a sibling of `rf.ssr.ring.node/renderer`
;; ===========================================================================

(defn- request-body
  "`rf.ssr.ring.node`'s private `request-body`, verbatim in effect: the same
  JSON the sidecar would be POSTed."
  [{:keys [entry build-id timeout-ms]} frame-id partitions]
  (let [wire (rf.ssr.render-state/serialize partitions)]
    {"protocol"  1
     "entry"     entry
     "state"     (:rf/app-db wire)
     "runtime"   (:rf/runtime-db wire)
     "buildId"   build-id
     "timeoutMs" timeout-ms
     "requestId" (str (symbol frame-id))}))

(defn pool
  "A pool of `n` slots on one engine. Borrow with `borrow!`, return with
  `return!`."
  [^Engine engine n]
  (let [bundle (js-source graal-bundle)
        driver (driver-source)
        q      (ArrayBlockingQueue. (int n))
        slots  (vec (repeatedly n #(slot engine bundle driver)))]
    (doseq [s slots] (.put q s))
    {:engine engine :bundle bundle :driver driver :queue q :slots slots}))

(defn borrow! [{:keys [^ArrayBlockingQueue queue]} admission-ms]
  (or (.poll queue (long admission-ms) TimeUnit/MILLISECONDS)
      (throw (ex-info "graal pool saturated" {:admission-ms admission-ms}))))

(defn return! [{:keys [^ArrayBlockingQueue queue]} s]
  (.put queue s))

(defn graal-renderer
  "The `:renderer` seam backed by the pool. No deadline here: `cancel`
  measures that separately."
  [the-pool opts]
  (fn graal-node-free-renderer [{frame-id :frame-id handler-opts :opts}]
    (let [partitions (rf.ssr.render-state/project
                       frame-id
                       (assoc opts :payload-include-sensitive
                              (:payload-include-sensitive handler-opts)))
          request-json (json/write-str (request-body opts frame-id partitions)
                                       :escape-slash false)
          s (borrow! the-pool 250)]
      (try
        {:body-html (render-in-slot s request-json)}
        (finally (return! the-pool s))))))

;; ===========================================================================
;; The Node sidecar — the real launcher
;; ===========================================================================

(defn- pump-lines! [stream on-line on-eof]
  (doto (Thread. (fn []
                   (try
                     (with-open [r (io/reader stream)]
                       (doseq [line (line-seq r)] (on-line line)))
                     (catch Exception _ nil)
                     (finally (on-eof)))))
    (.setDaemon true)
    (.start)))

(defn spawn-sidecar!
  "Spawn the launcher on `bundle` with `isolates` workers, NODE_ENV=production
  (React's production build, which is what an operator runs). Returns
  `{:process :url :ready-ms :stderr}`."
  [^java.io.File bundle isolates]
  (let [pb     (ProcessBuilder. ^java.util.List
                                ["node" (.getPath launcher)
                                 "--module" (.getPath bundle)
                                 "--port" "0"
                                 "--isolates" (str isolates)
                                 "--timeout-ms" "5000"
                                 "--max-timeout-ms" "5000"
                                 "--admission-ms" "5000"])
        _      (.put (.environment pb) "NODE_ENV" "production")
        t0     (now-ns)
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
    (let [m (deref ready 60000 ::timeout)]
      (when-not (map? m)
        (.destroyForcibly p)
        (throw (ex-info (str "sidecar: no ready line (" m ")\n" stderr) {})))
      {:process p :url (get m "url") :ready-ms (round2 (ms t0 (now-ns))) :stderr stderr})))

(defn stop-sidecar! [{:keys [^Process process]}]
  (.destroy process)
  (when-not (.waitFor process 5 TimeUnit/SECONDS)
    (.destroyForcibly process)))

(def ^HttpClient http-client
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn sidecar-render
  "POST one captured call; answer `{:status :body :render-ms}`."
  [{:keys [url]} ^String request-json]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str url "/render")))
                (.timeout (Duration/ofSeconds 10))
                (.header "content-type" "application/json; charset=utf-8")
                (.POST (HttpRequest$BodyPublishers/ofString request-json))
                (.build))
        ^HttpResponse resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
    {:status    (.statusCode resp)
     :body      (.body resp)
     :render-ms (some-> (.firstValue (.headers resp) "x-rf-ssr-render-ms") (.orElse nil) parse-double)}))

(defn- working-set-bytes
  "The OS working set of process `pid`, read through PowerShell (Windows)."
  [pid]
  (let [p (-> (ProcessBuilder. ^java.util.List
                               ["powershell" "-NoProfile" "-Command"
                                (str "(Get-Process -Id " pid ").WorkingSet64")])
              (.redirectErrorStream true)
              (.start))
        out (str/trim (slurp (.getInputStream p)))]
    (.waitFor p)
    (parse-long out)))

(defn- mib [b] (when b (round2 (/ (double b) 1048576.0))))

;; ===========================================================================
;; Steps
;; ===========================================================================

(defn- first-diff [^String a ^String b]
  (let [n (min (count a) (count b))
        i (or (first (filter #(not= (.charAt a %) (.charAt b %)) (range n))) n)]
    {:index i
     :a (subs a (max 0 (- i 40)) (min (count a) (+ i 80)))
     :b (subs b (max 0 (- i 40)) (min (count b) (+ i 80)))}))

(defn- same-bytes [a b]
  (if (= a b)
    {:identical true :bytes (count (.getBytes ^String a "UTF-8"))}
    {:identical false :first-diff (first-diff a b)}))

(defn step-compare
  "Byte-for-byte: GraalJS vs the sidecar on the shipped bundle, and the same
  self-contained bundle on Node, for both captured calls; then the whole
  Ring document through `ssr-handler` with each renderer."
  []
  (let [calls   (capture-calls)
        eng     (engine)
        s       (slot eng (js-source graal-bundle) (driver-source))
        graal   (into {} (for [[k j] calls] [k (render-in-slot s j)]))
        node-a  (spawn-sidecar! node-bundle 2)
        node-b  (spawn-sidecar! graal-bundle 2)]
    (try
      (let [side-a (into {} (for [[k j] calls] [k (sidecar-render node-a j)]))
            side-b (into {} (for [[k j] calls] [k (sidecar-render node-b j)]))
            the-pool {:queue (doto (ArrayBlockingQueue. 1) (.put s))}
            page-g (into {} (for [k (keys scenarios)]
                              [k ((page-handler k (graal-renderer the-pool renderer-opts)) page-request)]))
            page-n (into {} (for [k (keys scenarios)]
                              [k ((page-handler k (rf.ssr.ring.node/renderer
                                                    (assoc renderer-opts :endpoint (:url node-a))))
                                  page-request)]))]
        {:engine-implementation (.getImplementationName eng)
         :shim-use-after-renders (slot-stats s)
         :calls calls
         :graal-html graal
         :body-vs-sidecar-shipped-bundle
         (into {} (for [k (keys calls)]
                    [k (assoc (same-bytes (graal k) (get-in side-a [k :body]))
                              :sidecar-status (get-in side-a [k :status]))]))
         :body-vs-sidecar-same-bundle
         (into {} (for [k (keys calls)]
                    [k (assoc (same-bytes (graal k) (get-in side-b [k :body]))
                              :sidecar-status (get-in side-b [k :status]))]))
         :document-vs-sidecar
         (into {} (for [k (keys scenarios)]
                    [k (merge (same-bytes (:body (page-g k)) (:body (page-n k)))
                              {:status-graal (:status (page-g k))
                               :status-node  (:status (page-n k))
                               :headers-equal (= (:headers (page-g k)) (:headers (page-n k)))})]))})
      (finally
        (stop-sidecar! node-a)
        (stop-sidecar! node-b)
        (close-slot! s)
        (.close eng)))))

(defn- time-renders [f n]
  (vec (for [_ (range n)]
         (let [t0 (now-ns)] (f) (ms t0 (now-ns))))))

(defn- env-longs [k default]
  (mapv parse-long (str/split (or (System/getenv k) default) #",")))

(defn- checkpoint-windows
  "Run `f` (one render, answering the latency to record) until each
  checkpoint count, timing a window of `window` renders at each one."
  [f checkpoints window]
  (loop [done 0 [c & more] checkpoints acc {}]
    (if-not c
      acc
      (do (dotimes [_ (max 0 (- c done))] (f))
          (let [samples (vec (repeatedly window f))]
            (recur (+ c window) more (assoc acc c (summary samples))))))))

(defn step-cold
  "Cold and warm single-thread render times: GraalJS from a fresh engine, and
  the sidecar (one isolate) from spawn. Warm is a window of renders at each
  checkpoint in `SPIKE_CHECKPOINTS`, so a JIT's warm-up curve is visible."
  []
  (let [calls  (capture-calls)
        j      (:shipped calls)
        checks (env-longs "SPIKE_CHECKPOINTS" "100,2000")
        window 200
        t0     (now-ns)
        eng    (engine)
        t1     (now-ns)
        bundle (js-source graal-bundle)
        s1     (slot eng bundle (driver-source))
        first-ms (first (time-renders #(render-in-slot s1 j) 1))
        windows  (checkpoint-windows
                   (fn [] (let [t0 (now-ns)] (render-in-slot s1 j) (ms t0 (now-ns))))
                   checks window)
        ;; A SECOND slot on the same engine: what a replacement costs once
        ;; the engine has seen (and compiled) the bundle.
        s2       (slot eng bundle (driver-source))
        s2-first (first (time-renders #(render-in-slot s2 j) 1))
        s2-next  (summary (time-renders #(render-in-slot s2 j) window))
        graal {:engine-implementation (.getImplementationName eng)
               :engine-ms (round2 (ms t0 t1))
               :first-slot (:timings s1)
               :first-render-ms (round2 first-ms)
               :warm-windows windows
               :second-slot-same-engine (:timings s2)
               :second-slot-first-render-ms (round2 s2-first)
               :second-slot-next-renders s2-next}
        _ (close-slot! s1) _ (close-slot! s2) _ (.close eng)
        side (spawn-sidecar! node-bundle 1)]
    (try
      (let [in-isolate (atom [])
            f (fn [] (let [t0 (now-ns) r (sidecar-render side j)]
                       (swap! in-isolate conj (:render-ms r))
                       (ms t0 (now-ns))))
            first-rt (f)
            first-in (first @in-isolate)
            windows  (checkpoint-windows f checks window)]
        {:graal graal
         :sidecar {:spawn-to-ready-ms (:ready-ms side)
                   :first-render-roundtrip-ms (round2 first-rt)
                   :first-render-in-isolate-ms first-in
                   :warm-windows-roundtrip windows
                   :last-window-in-isolate (summary (take-last window @in-isolate))}})
      (finally (stop-sidecar! side)))))

(defn- run-threads
  "Run `(f thread-index)` once per renderer call, `per-thread` times on each
  of `threads` threads after `warmup` untimed calls each. Answer throughput
  and latency over the timed calls."
  [threads warmup per-thread f]
  (let [samples (atom [])
        errors  (atom 0)
        start   (CountDownLatch. 1)
        done    (CountDownLatch. (int threads))
        warmed  (CountDownLatch. (int threads))
        ths     (vec (for [i (range threads)]
                       (doto (Thread.
                               (fn []
                                 (try
                                   (dotimes [_ warmup] (f i))
                                   (.countDown warmed)
                                   (.await start)
                                   (let [mine (transient [])]
                                     (dotimes [_ per-thread]
                                       (let [t0 (now-ns)]
                                         (try (f i) (catch Exception _ (swap! errors inc)))
                                         (conj! mine (ms t0 (now-ns)))))
                                     (swap! samples into (persistent! mine)))
                                   (finally (.countDown done)))))
                         (.start))))]
    (.await warmed)
    (let [t0 (now-ns)]
      (.countDown start)
      (.await done)
      (let [wall (ms t0 (now-ns))
            n    (count @samples)]
        (doseq [^Thread t ths] (.join t))
        (merge {:threads threads :warmup-per-thread warmup :timed-renders n
                :errors @errors :wall-ms (round2 wall)
                :throughput-per-s (round2 (/ (* 1000.0 n) wall))}
               (select-keys (summary @samples) [:p50 :p90 :p99 :max :mean]))))))

(defn step-bench
  "4 threads. GraalJS: one Context per pool slot (4 slots on one engine).
  Sidecar: the JVM's own HTTP client against the launcher with 2 isolates
  (its default) and with 4. `SPIKE_ROUNDS` alternates the three, so load
  from elsewhere on the machine lands on all of them alike."
  []
  (let [calls  (capture-calls)
        j      (:shipped calls)
        warm   (parse-long (or (System/getenv "SPIKE_WARMUP") "300"))
        per    (parse-long (or (System/getenv "SPIKE_PER_THREAD") "500"))
        rounds (parse-long (or (System/getenv "SPIKE_ROUNDS") "1"))
        eng    (engine)
        p      (pool eng 4)
        graal  (fn [warm]
                 (run-threads 4 warm per
                              (fn [_]
                                (let [s (borrow! p 5000)]
                                  (try (render-in-slot s j) (finally (return! p s)))))))
        side   (fn [isolates]
                 (let [sc (spawn-sidecar! node-bundle isolates)]
                   (try (assoc (run-threads 4 warm per
                                            (fn [_] (let [r (sidecar-render sc j)]
                                                      (when-not (= 200 (:status r))
                                                        (throw (ex-info "non-200" r))))))
                               :isolates isolates)
                        (finally (stop-sidecar! sc)))))
        result {:engine-implementation (.getImplementationName eng)
                :rounds (vec (for [r (range rounds)]
                               {:graal-4-slots      (graal (if (zero? r) warm (quot warm 4)))
                                :sidecar-2-isolates (side 2)
                                :sidecar-4-isolates (side 4)}))}]
    (run! close-slot! (:slots p))
    (.close eng)
    result))

(defn- heap-used []
  (dotimes [_ 3] (System/gc) (Thread/sleep 200))
  (let [m (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    (.getUsed m)))

(defn step-memory
  "Heap after GC and process working set: before the engine, with 1 and with
  4 warmed slots; the sidecar's working set with 2 and 4 isolates, warmed."
  []
  (let [calls (capture-calls)
        j     (:shipped calls)
        pid   (.pid (java.lang.ProcessHandle/current))
        h0    (heap-used) w0 (working-set-bytes pid)
        eng   (engine)
        bundle (js-source graal-bundle)
        s1    (slot eng bundle (driver-source))
        _     (dotimes [_ 200] (render-in-slot s1 j))
        h1    (heap-used) w1 (working-set-bytes pid)
        more  (vec (repeatedly 3 #(slot eng bundle (driver-source))))
        _     (doseq [s more] (dotimes [_ 200] (render-in-slot s j)))
        h4    (heap-used) w4 (working-set-bytes pid)
        _     (run! close-slot! (cons s1 more))
        _     (.close eng)
        side  (fn [isolates]
                (let [sc (spawn-sidecar! node-bundle isolates)]
                  (try
                    (dotimes [_ 400] (sidecar-render sc j))
                    (let [spid (.pid ^Process (:process sc))]
                      {:isolates isolates :working-set-mib (mib (working-set-bytes spid))})
                    (finally (stop-sidecar! sc)))))]
    {:graal {:heap-used-mib {:before-engine (mib h0) :one-slot (mib h1) :four-slots (mib h4)
                             :per-extra-slot (mib (/ (- h4 h1) 3))}
             :jvm-working-set-mib {:before-engine (mib w0) :one-slot (mib w1) :four-slots (mib w4)}}
     :sidecar [(side 2) (side 4)]}))

(defn- spin-and-stop
  "Start `__spikeSpin` in `s`'s context on a fresh thread; after
  `deadline-ms`, call `stop!` on the context. Answer how long after `stop!`
  the spinning thread came back, and what it came back with."
  [s deadline-ms stop!]
  (let [ctx      ^Context (:context s)
        outcome  (promise)
        started  (CountDownLatch. 1)
        t (doto (Thread.
                  (fn []
                    (.countDown started)
                    (try
                      (.execute (.getMember (.getBindings ctx "js") "__spikeSpin") (object-array []))
                      (deliver outcome {:returned true :at (now-ns)})
                      (catch PolyglotException e
                        (deliver outcome {:at (now-ns)
                                          :cancelled (.isCancelled e)
                                          :interrupted (.isInterrupted e)
                                          :message (.getMessage e)}))
                      (catch Throwable e
                        (deliver outcome {:at (now-ns) :other (str e)})))))
            (.setDaemon true)
            (.start))]
    (.await started)
    (Thread/sleep (long deadline-ms))
    (let [t-stop (now-ns)
          stop-result (try (stop! ctx) :ok (catch Throwable e (str e)))
          o (deref outcome 10000 nil)]
      (.join t 1000)
      {:stop-call stop-result
       :thread-alive-after (.isAlive t)
       :stopped-within-ms (when o (round2 (ms t-stop (:at o))))
       :outcome (dissoc o :at)})))

(defn step-cancel
  "Hard cancellation of a render that loops forever, both ways the polyglot
  API offers, and the slot's replacement."
  []
  (let [calls  (capture-calls)
        j      (:shipped calls)
        eng    (engine)
        bundle (js-source graal-bundle)
        expected (let [s (slot eng bundle (driver-source))]
                   (try (render-in-slot s j) (finally (close-slot! s))))
        ;; close(true): the context is gone; replace the slot.
        s1     (slot eng bundle (driver-source))
        closed (spin-and-stop s1 1000 (fn [^Context c] (.close c true)))
        t0     (now-ns)
        s1b    (slot eng bundle (driver-source))
        t1     (now-ns)
        after-replace (render-in-slot s1b j)
        ;; interrupt: the context survives; does it still render correctly?
        s2     (slot eng bundle (driver-source))
        interrupted (spin-and-stop s2 1000 (fn [^Context c] (.interrupt c (Duration/ofSeconds 5))))
        after-interrupt (try (render-in-slot s2 j) (catch Throwable e (str "threw: " e)))]
    (run! close-slot! [s1b s2])
    (.close eng)
    {:close-true (assoc closed
                        :replacement-slot-ms (round2 (ms t0 t1))
                        :replacement-renders-identical (= expected after-replace))
     :interrupt  (assoc interrupted
                        :same-context-renders-identical-after (= expected after-interrupt))}))

(defn step-pump
  "Does a host have to run the context's timers? Render `SPIKE_PUMP_N` times
  in two slots — one through the pumping driver, one never pumping — and
  compare heap growth after GC."
  []
  (let [calls  (capture-calls)
        j      (:shipped calls)
        n      (parse-long (or (System/getenv "SPIKE_PUMP_N") "5000"))
        eng    (engine)
        bundle (js-source graal-bundle)
        run    (fn [fn-name]
                 (let [s   (slot eng bundle (driver-source))
                       f   (.getMember (.getBindings ^Context (:context s) "js") fn-name)
                       _   (dotimes [_ 50] (.execute f (object-array [j])))
                       h0  (heap-used)
                       _   (dotimes [_ n] (.execute f (object-array [j])))
                       ;; Let anything due fall due, then one pump, as the
                       ;; pumping host's next render would.
                       _   (Thread/sleep 50)
                       h1  (heap-used)
                       st  (slot-stats s)]
                   (close-slot! s)
                   {:renders n :heap-growth-mib (mib (- h1 h0))
                    :per-render-bytes (long (/ (- h1 h0) n))
                    :shim-use st}))
        result {:engine-implementation (.getImplementationName eng)
                :pumped   (run "__spikeRender")
                :unpumped (run "__spikeRenderNoPump")}]
    (.close eng)
    result))

(def steps
  {"pump"    step-pump
   "compare" step-compare
   "cold"    step-cold
   "bench"   step-bench
   "memory"  step-memory
   "cancel"  step-cancel})

(defn- write-result! [step result]
  (.mkdirs results-dir)
  (let [label (or (System/getenv "SPIKE_LABEL") "run")
        f     (io/file results-dir (str label "-" step ".edn"))]
    (spit f (with-out-str (pp/pprint result)))
    (println "==" step "->" (.getPath f))
    (pp/pprint (if (= "compare" step) (dissoc result :calls :graal-html) result))))

(defn -main [& [step]]
  (let [names (if (or (nil? step) (= "all" step))
                ["compare" "cold" "bench" "memory" "cancel"]
                [step])]
    (println "java" (System/getProperty "java.version") (System/getProperty "java.vm.name"))
    (doseq [n names]
      (write-result! n ((steps n))))
    (shutdown-agents)
    (System/exit 0)))
