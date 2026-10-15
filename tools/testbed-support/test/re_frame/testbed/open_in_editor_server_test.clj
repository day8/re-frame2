(ns re-frame.testbed.open-in-editor-server-test
  "Tests for endpoint admission, source resolution and launch outcomes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.testbed.open-in-editor-server :as rf.testbed.open-in-editor-server]
            [shadow.http.push-state :as shadow.push-state])
  (:import [java.net InetAddress InetSocketAddress URL URLClassLoader]
           [java.io File]))

;; A `+`-bearing classpath root confirms that delegation preserves URI semantics.

(deftest resolve-file-delegates-classpath-stage-to-core
  (testing "Classpath resolution preserves a + in the source root."
    ;; Install a throwaway source root on the thread context classloader.
    (let [tmp       (File. (System/getProperty "java.io.tmpdir")
                           (str "oies+test-" (System/nanoTime)))
          rel-path  "fake_ns/core.cljs"
          src-file  (io/file tmp "fake_ns" "core.cljs")]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        (let [root-url (.toURL (.toURI tmp))
              cl       (URLClassLoader. (into-array URL [root-url])
                                        (.getContextClassLoader (Thread/currentThread)))
              prev     (.getContextClassLoader (Thread/currentThread))]
          (try
            (.setContextClassLoader (Thread/currentThread) cl)
            (is (= (.getCanonicalPath src-file)
                   (.getCanonicalPath (File. ^String (rf.testbed.open-in-editor-server/resolve-file rel-path))))
                "resolved to the REAL on-disk fixture file, not a
                 space-corrupted sibling that does not exist")
            (finally
              (.setContextClassLoader (Thread/currentThread) prev))))
        (finally
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

(deftest resolve-file-passes-an-absolute-path-through
  (is (= "/abs/re-frame2+wip/core.cljs"
         (rf.testbed.open-in-editor-server/resolve-file "/abs/re-frame2+wip/core.cljs"))
      "an already-absolute path is returned unchanged, a + in it included"))

;; Launch is stubbed while the peer, method, Host and Origin guards are tested.

(defn ^:private req
  "Build a loopback POST; :query overrides :file and :peer :absent omits the peer."
  [{:keys [method host origin file peer query]
    :or   {method :post host "localhost:8031" peer "127.0.0.1"}
    :as   opts}]
  (cond-> {:uri            rf.testbed.open-in-editor-server/endpoint-path
           :request-method method
           :query-string   (if (contains? opts :query)
                             query
                             (when file (str "file=" file "&line=10")))
           :headers        (cond-> {}
                             host   (assoc "host" host)
                             origin (assoc "origin" origin))}
    (not= :absent peer) (assoc :remote-addr peer)))

(defmacro ^:private with-launch-spy
  "Record launch calls without opening an editor."
  [calls & body]
  `(with-redefs [rf.testbed.open-in-editor-server/launch! (fn [& args#]
                                (swap! ~calls conj (vec args#))
                                {:ok true})]
     ~@body))

(defn- cors-headers
  "CORS header names and Vary, case-insensitively sorted."
  [resp]
  (->> (keys (:headers resp))
       (map #(str/lower-case (name %)))
       (filter #(or (str/starts-with? % "access-control-") (= "vary" %)))
       sort
       vec))

;; Use real socket renderings: transport peers are not bare IP strings.

(defn- socket-peer
  "`:remote-addr` exactly as shadow-http renders an accepted socket's peer:
  `str` of an `InetSocketAddress` over `addr`."
  [^InetAddress addr]
  (str (InetSocketAddress. addr (int 54321))))

(defn- ip
  "Parse a numeric literal without DNS."
  ^InetAddress [^String literal]
  (InetAddress/getByName literal))

(defn- named
  "Give an address an untrusted reverse-lookup hostname without DNS."
  ^InetAddress [^String host ^String literal]
  (InetAddress/getByAddress host (.getAddress (ip literal))))

(defn- answer
  "What a client sees for request `r`, and every `launch!` call it caused:
  `[status body cors-headers launches]`."
  [r]
  (let [calls (atom [])]
    (with-launch-spy calls
      (let [resp (rf.testbed.open-in-editor-server/handle r)]
        [(:status resp) (:body resp) (cors-headers resp) @calls]))))

(defn- refused
  "The answer to a request declined with `status` and the JSON `error` token:
  no CORS header, and no launch."
  [status error]
  [status (str "{\"ok\":false,\"error\":\"" error "\"}") [] []])

(defn- launched
  "A 200 with the resolved file, no CORS header and one coordinate-preserving launch."
  [file line column command]
  [200 (str "{\"ok\":true,\"file\":\"" file "\"}") [] [[file line column command]]])

(deftest handle-answers-and-launches-per-request
  (testing "Admission failures never launch; admitted requests preserve the coordinate."
    (doseq [[label r expected]
            [;; Admission must precede method and launch checks.
             ["a non-loopback Host (a public binding, or DNS rebinding)"
              (req {:host "app.evil.example" :file "/etc/passwd"})
              (refused 403 "forbidden")]
             ["no Host header" (req {:host nil :file "/etc/passwd"}) (refused 403 "forbidden")]
             ["a remote peer spoofing `Host: localhost` with no Origin — the shape a
               direct HTTP client sends to a testbed bound to 0.0.0.0"
              (req {:peer "203.0.113.7" :file "/etc/passwd"})
              (refused 403 "forbidden")]
             ["no :remote-addr at all: a missing transport fact is never permission"
              (req {:peer :absent :file "/etc/passwd"})
              (refused 403 "forbidden")]
             ["a remote peer behind loopback X-Forwarded-For / X-Real-IP headers"
              (update (req {:peer "203.0.113.7" :file "/etc/passwd"}) :headers assoc
                      "x-forwarded-for" "127.0.0.1" "x-real-ip" "127.0.0.1")
              (refused 403 "forbidden")]
             ["a remote Origin" (req {:origin "https://evil.example" :file "/etc/passwd"})
              (refused 403 "forbidden")]
             ["an opaque Origin, never reflected" (req {:origin "null" :file "/etc/passwd"})
              (refused 403 "forbidden")]
             ["a remote socket peer whose Host, Origin and hostname half all read loopback"
              (req {:origin "http://localhost:8042" :file "/etc/passwd"
                    :peer   (socket-peer (named "localhost" "203.0.113.7"))})
              (refused 403 "forbidden")]
             ["an OPTIONS from a remote IPv6 socket peer"
              (req {:method :options :origin "http://localhost:8042"
                    :peer   (socket-peer (ip "2001:db8::5"))})
              (refused 403 "forbidden")]
             ["an OPTIONS with a remote Origin"
              (req {:method :options :origin "https://evil.example"})
              (refused 403 "forbidden")]
             ["an OPTIONS to a non-loopback Host"
              (req {:method :options :host "app.evil.example"})
              (refused 403 "forbidden")]
             ;; Neither GET nor OPTIONS may launch.
             ["a GET" (req {:method :get :file "/etc/passwd"}) (refused 405 "method-not-allowed")]
             ["a same-origin OPTIONS" (req {:method :options}) (refused 405 "method-not-allowed")]
             ;; Missing input and malformed percent escapes are 400s.
             ["no `file` param" (req {:query "line=10&column=3"}) (refused 400 "missing-file")]
             ["an empty `file=`" (req {:query "file=&line=10"}) (refused 400 "missing-file")]
             ["no query string" (req {}) (refused 400 "missing-file")]
             ["a lone `%`" (req {:query "file=%"}) (refused 400 "malformed-query")]
             ;; A 200 promises that the position survived.
             ["a coordinate for windsurf"
              (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=windsurf"})
              (refused 422 "editor-position-unsupported")]
             ;; Launches.
             ["a same-origin POST" (req {:file "fake_ns/core.cljs"})
              (launched "fake_ns/core.cljs" 10 nil nil)]
             ["a POST from another local port"
              (req {:origin "http://localhost:8042" :file "fake_ns/core.cljs"})
              (launched "fake_ns/core.cljs" 10 nil nil)]
             ["a column with no line" (req {:query "file=fake_ns/core.cljs&column=7"})
              (launched "fake_ns/core.cljs" nil 7 nil)]
             ["a literal `+` in the path is not form-decoded to a space"
              (req {:query "file=deep/re-frame2+wip/core.cljs&line=7"})
              (launched "deep/re-frame2+wip/core.cljs" 7 nil nil)]
             ["percent-escapes decode with URI semantics, and the last `file` wins"
              (req {:query "file=a+b&file=re-frame2%2Bwip%2Fa%20b.cljs&line=10"})
              (launched "re-frame2+wip/a b.cljs" 10 nil nil)]
             ["an editor outside the vocabulary leaves launch-editor to auto-detect"
              (req {:query "file=fake_ns/core.cljs&editor=emacs"})
              (launched "fake_ns/core.cljs" nil nil nil)]
             ["windsurf with no coordinate loses nothing, so it still launches"
              (req {:query "file=fake_ns/core.cljs&editor=windsurf"})
              (launched "fake_ns/core.cljs" nil nil "windsurf")]
             ;; Named commands preserve 27:9; hints are case-insensitive and trimmed.
             ["editor=vscode" (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=vscode"})
              (launched "fake_ns/core.cljs" 27 9 "code")]
             ["editor=vscode-insiders"
              (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=vscode-insiders"})
              (launched "fake_ns/core.cljs" 27 9 "code-insiders")]
             ["editor=%20Cursor%20"
              (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=%20Cursor%20"})
              (launched "fake_ns/core.cljs" 27 9 "cursor")]
             ["editor=zed" (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=zed"})
              (launched "fake_ns/core.cljs" 27 9 "zed")]
             ["editor=idea" (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=idea"})
              (launched "fake_ns/core.cljs" 27 9 "idea")]]]
      (is (= expected (answer r)) label))))

(deftest loopback-peer?-classifies-correctly
  (testing "Bare literals and real socket renderings admit loopback."
    (doseq [addr ["127.0.0.1" "127.0.0.53" "::1" "0:0:0:0:0:0:0:1"
                  "::ffff:127.0.0.1" "::1%1"
                  (socket-peer (ip "127.0.0.1"))
                  (socket-peer (ip "::1"))
                  (socket-peer (named "attacker.example" "127.0.0.1"))]]
      (is (#'rf.testbed.open-in-editor-server/loopback-peer? addr)
          (str "loopback peer: " addr))))
  (testing "Missing, remote and malformed peers fail closed."
    (doseq [addr [nil "   " 12345 "10.0.0.5" "::" "0:0:0:0:0:0:0:2"
                  "1127.0.0.1" "127.0.0.1.evil.example" "127malicious.example"
                  "localhost" "127.0.0.1, 10.0.0.5" "127.0.0.1:52344"
                  (socket-peer (ip "10.0.0.1"))
                  (socket-peer (ip "2606:4700:4700::1111"))
                  (socket-peer (named "localhost" "10.0.0.1"))
                  (socket-peer (named "127.0.0.1" "203.0.113.7"))
                  (str (InetSocketAddress/createUnresolved "localhost" 54321))
                  "/" "/:54321" "localhost/" "localhost/localhost:54321"]]
      (is (not (#'rf.testbed.open-in-editor-server/loopback-peer? addr))
          (str "refused peer: " (pr-str addr))))))

(deftest peer-literal-never-hands-getByName-a-name
  (testing "Invalid literals must not reach DNS, even when a hosts entry maps them to loopback."
    (doseq [s ["127.0.0.999" ".::1" "localhost/1.2.3.456:54321"]]
      (is (nil? (#'rf.testbed.open-in-editor-server/peer-literal s))
          (str "never reaches getByName: " (pr-str s))))))

(deftest loopback-host?-classifies-correctly
  (testing "loopback Host values, with or without a port, IPv4 and IPv6, any case"
    (doseq [host ["LocalHost:8031" "127.5.6.7" "::1" "[::1]:8080"]]
      (is (#'rf.testbed.open-in-editor-server/loopback-host? host) host)))
  (testing "everything else, a textual 127 prefix included"
    (doseq [host ["app.evil.example:8031" "127malicious.example" "127.0.0.1.evil.example"
                  "10.0.0.5" nil]]
      (is (not (#'rf.testbed.open-in-editor-server/loopback-host? host)) (pr-str host)))))

;; File values and launch stderr may contain controls, so verify JSON round trips.

(defn ^:private read-json-string-literal
  "Decode one JSON string literal without depending on the encoder under test."
  [^String s]
  (let [n (.length s)]
    (loop [i (inc 0) sb (StringBuilder.)]
      (when (>= i n)
        (throw (ex-info "unterminated JSON string" {:s s})))
      (let [c (.charAt s i)]
        (cond
          (= c \") [(.toString sb) (inc i)]
          (= c \\) (let [e (.charAt s (inc i))]
                     (case e
                       \" (recur (+ i 2) (.append sb \"))
                       \\ (recur (+ i 2) (.append sb \\))
                       \n (recur (+ i 2) (.append sb \newline))
                       \r (recur (+ i 2) (.append sb \return))
                       \t (recur (+ i 2) (.append sb \tab))
                       \u (let [hex (.substring s (+ i 2) (+ i 6))]
                            (recur (+ i 6)
                                   (.append sb (char (Integer/parseInt hex 16)))))
                       (throw (ex-info "bad escape" {:esc e}))))
          :else (recur (inc i) (.append sb c)))))))

(defn ^:private json-body->file-value
  "Decode a named string value from the small JSON response shape."
  [^String body key-prefix]
  (let [idx (.indexOf body ^String key-prefix)]
    (when (neg? idx)
      (throw (ex-info "key not found in body" {:body body :key key-prefix})))
    (first (read-json-string-literal (.substring body (+ idx (.length key-prefix) -1))))))

(deftest json-resp-escapes-embedded-control-chars
  (testing "Control characters, quotes and backslashes round-trip as valid JSON."
    (doseq [[label status k value]
            [["a 200 :file with a newline and a tab" 200 "file"
              "day8/re_frame2_xray/core.cljs\ninjected-line\ttabbed"]
             ["a 200 :file that is a Windows path with a quote" 200 "file"
              "C:\\Users\\me\\code\\re-frame2\\src\\a\"b.cljs"]
             ["a 422 launch error with a CR and a C0 control" 422 "error"
              (str "launch-editor: boom\r\nat frame " (char 0x01) "end")]]]
      (with-redefs [rf.testbed.open-in-editor-server/resolve-file (constantly value)
                    rf.testbed.open-in-editor-server/launch!
                    (fn [& _] (if (= 200 status) {:ok true} {:ok false :message value}))]
        (let [{body :body :as resp} (rf.testbed.open-in-editor-server/handle
                                      (req {:file "fake_ns/core.cljs"}))]
          (is (= [status value nil]
                 [(:status resp)
                  (json-body->file-value body (str "\"" k "\":\""))
                  (re-find #"[\x00-\x1f]" body)])
              label))))))

;; launch-editor parses the first numeric suffix as a line, so column-only
;; coordinates must be encoded as `path:1:column`.

(deftest build-file-spec-normalizes-column-only-to-line-1
  (testing "Column-only coordinates target line 1; absent parts stay absent."
    (is (= ["/abs/core.cljs:1:7" "/abs/core.cljs:3" "/abs/core.cljs:3:7" "/abs/core.cljs"]
           (map #(apply #'rf.testbed.open-in-editor-server/build-file-spec "/abs/core.cljs" %)
                [[nil 7] [3 nil] [3 7] [nil nil]])))))

;; Real children exceed a pipe buffer; short budgets turn drain regressions into failures.

(defn ^:private node-available?
  "Whether Node probes can run; skip-or-fail enforces CI prerequisites."
  []
  (try
    (let [p (.start (ProcessBuilder. ["node" "--version"]))]
      (and (.waitFor p 10 java.util.concurrent.TimeUnit/SECONDS)
           (zero? (.exitValue p))))
    (catch Throwable _ false)))

;; CI sets RF2_REQUIRE_NODE_PROBES so missing subprocess coverage fails.

(def ^:private node-probes-required?
  "Require real subprocess probes when RF2_REQUIRE_NODE_PROBES is set."
  (delay (some? (System/getenv "RF2_REQUIRE_NODE_PROBES"))))

(defn ^:private skip-or-fail
  "Fail a missing prerequisite when this lane declared it installed."
  [missing]
  (is (not @node-probes-required?)
      (str "skipped: " missing
           " — but RF2_REQUIRE_NODE_PROBES is set, so this lane declared the "
           "prerequisite present. A skip here is lost coverage, not a pass: "
           "install node and run `npm ci` in `implementation/`.")))

(def ^:private one-mib-plus
  "Comfortably more than a plausible OS pipe buffer (~64 KiB)."
  1200000)

(defn ^:private tmp-existing-file
  "An existing source path accepted by launch! without opening an editor."
  []
  (doto (File/createTempFile "oies-drain-" ".cljs") (.deleteOnExit)))

(defmacro ^:private timed
  "Eval `body`, returning `[result elapsed-ms]`."
  [& body]
  `(let [t0# (System/nanoTime)
         r#  (do ~@body)]
     [r# (quot (- (System/nanoTime) t0#) 1000000)]))

(deftest launch-drains-both-pipes-and-honours-its-budget
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f      (.getAbsolutePath (tmp-existing-file))
          launch (fn [shim budget-ms]
                   (with-redefs [rf.testbed.open-in-editor-server/launch-shim shim]
                     (binding [rf.testbed.open-in-editor-server/*launch-timeout-ms* budget-ms]
                       (timed (rf.testbed.open-in-editor-server/launch! f nil nil nil)))))
          flood  (fn [stream ch exit-code]
                   (str "var b='" ch "'.repeat(" one-mib-plus ");"
                        "process." stream ".write(b);process.exit(" exit-code ");"))]
      (is (= {:ok true} (first (launch (flood "stdout" "x" 0) 8000)))
          "a >1 MiB stdout flood + exit 0 is a prompt success, not a timeout")
      (is (re-matches #"y{1,8192}" (str (:message (first (launch (flood "stderr" "y" 7) 8000)))))
          "a drained stderr flood is a real failure carrying the flood's head — not
           the deadlock timeout — and that head is bounded, so a runaway child
           costs no unbounded parent memory")
      (is (= {:ok false :message "controlled failure"}
             (first (launch "process.stderr.write('controlled failure');process.exit(7);" 8000)))
          "a small stderr round-trips verbatim")
      (let [[res ms] (launch "setInterval(function(){},1000);" 500)]
        (is (= [{:ok false :message "launch-editor timed out"} true] [res (< ms 5000)])
            "a child that never exits times out within the short budget, nowhere
             near the default 10 s stall")))))

(deftest terminate!-force-kills-a-child-that-ignores-graceful-destroy
  ;; The child traps SIGTERM, so on POSIX the force-destroy fallback is what
  ;; ends it; on Windows `.destroy` already terminates forcibly.
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [pb   (doto (ProcessBuilder. ["node" "-e"
                                       "process.on('SIGTERM',function(){});setInterval(function(){},1000);"])
                 (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                 (.redirectErrorStream true))
          proc (.start pb)]
      (try
        (is (.isAlive proc) "the child started and is running")
        (is (true? (#'rf.testbed.open-in-editor-server/terminate! proc))
            "terminate! confirms the child is dead (force-destroy fallback used if needed)")
        (finally
          (when (.isAlive proc) (.destroyForcibly proc)))))))

(deftest endpoint-rejects-missing-file-with-422
  (testing "A real missing file returns 422 so the client can fall back."
    (let [missing (str "oies_missing_" (System/nanoTime) "/nope.cljs")
          resp    (rf.testbed.open-in-editor-server/handle
                    (req {:query (str "file=" missing "&line=10&column=3")}))]
      (is (= [422 "{\"ok\":false,\"error\":\"file-not-found\"}"] [(:status resp) (:body resp)])
          "a non-2xx, not a false 200, naming the missing file rather than launch-failed"))))

;; A decline must reach the client's coordinate-preserving fallback.

(deftest position-blind-commands-are-declared-not-guessed
  ;; The real-shim windsurf row pins dependency drift; this checks the named fast path.
  (testing "Only coordinate-bearing named position-blind commands decline."
    (is (= [true true true false false]
           (map #(apply rf.testbed.open-in-editor-server/position-would-be-dropped? %)
                [["windsurf" 27 9] ["windsurf" 27 nil] ["windsurf" nil 9]
                 ["windsurf" nil nil] [nil 27 9]])))))

;; Off-classpath relative coordinates fall back to the dev process cwd.

(deftest resolve-file-resolves-cwd-relative-off-classpath
  (testing "an off-classpath relative file resolves against user.dir"
    (let [tmp      (File. (System/getProperty "java.io.tmpdir")
                          (str "oies-cwd-" (System/nanoTime)))
          sub      (str "off_classpath_" (System/nanoTime))
          rel-path (str sub "/probe.cljs")
          src-file (io/file tmp sub "probe.cljs")
          prev-cwd (System/getProperty "user.dir")]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        ;; resolve-file reads user.dir at request time.
        (System/setProperty "user.dir" (.getAbsolutePath tmp))
        (is (= (.getCanonicalPath src-file)
               (.getCanonicalPath (File. ^String (rf.testbed.open-in-editor-server/resolve-file rel-path))))
            "resolved to the REAL on-disk fixture under the working dir, as an
             absolute path: the raw relative input would canonicalise against
             the JVM's own start directory instead")
        (finally
          (System/setProperty "user.dir" prev-cwd)
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp sub))
          (.delete tmp))))))

;; Real source roots witness the configuration-free endpoint used by both tools.

(def ^:private repo-root
  "Derive the checkout from the classpath because test runners use different cwds."
  (delay
    (let [url (.getResource (.getContextClassLoader (Thread/currentThread))
                            "re_frame/testbed/open_in_editor_server.clj")]
      (assert (and url (= "file" (.getProtocol url)))
              "the endpoint ns must be on a file: classpath for this witness")
      ;; …/tools/testbed-support/src/re_frame/testbed/open_in_editor_server.clj
      (nth (iterate #(.getParentFile ^File %) (File. (.toURI url))) 6))))

(def ^:private consumer-coords
  "One real relative coordinate per tool, paired with the shadow-cljs
  `:source-paths` entry that puts it on the dev JVM's classpath."
  [{:tool "Story" :root "tools/story/testbeds" :file "counter_with_stories/stories.cljs"}
   {:tool "Xray"  :root "tools/xray/testbeds"  :file "standard_epochs/core.cljs"}])

(defn ^:private with-testbed-source-roots*
  "Install the real testbed source roots as shadow-cljs does."
  [f]
  (let [roots (mapv #(io/file @repo-root (:root %)) consumer-coords)
        urls  (into-array URL (map #(.toURL (.toURI ^File %)) roots))
        prev  (.getContextClassLoader (Thread/currentThread))
        cl    (URLClassLoader. urls prev)]
    (try
      (.setContextClassLoader (Thread/currentThread) cl)
      (f)
      (finally
        (.setContextClassLoader (Thread/currentThread) prev)))))

(deftest real-relative-testbed-coords-resolve-through-the-endpoint
  (testing "Story and Xray coordinates resolve without a configured project root."
    (with-testbed-source-roots*
      (fn []
        (doseq [{:keys [tool root file]} consumer-coords]
          (let [expected (io/file @repo-root root file)
                calls    (atom [])]
            (with-launch-spy calls
              (let [resp (rf.testbed.open-in-editor-server/handle
                           (req {:host "localhost:8042" :query (str "file=" file "&line=12&column=3")}))
                    [[abs-path line column] :as launches] @calls]
                (is (= [200 1 (.getCanonicalPath expected) 12 3]
                       [(:status resp) (count launches)
                        (.getCanonicalPath (File. ^String abs-path)) line column])
                    (str tool " was accepted and reached launch! once, at the REAL on-disk "
                         "source file, line and column intact"))))))))))

;; A custom :dev-http handler must preserve push-state page loads.

(def ^:private index-body "<!doctype html><title>oies index</title>")

(defn- with-index-root*
  "Call `f` with the absolute path of a throwaway root holding an `index.html`."
  [f]
  (let [root  (.toFile (java.nio.file.Files/createTempDirectory
                         "oies-index-root-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        index (io/file root "index.html")]
    (try
      (spit index index-body)
      (f (.getAbsolutePath root))
      (finally
        (.delete index)
        (.delete root)))))

(defn- page-req
  "A page request with shadow's HTTP root and config keys."
  [method uri root]
  {:uri            uri
   :request-method method
   :remote-addr    "127.0.0.1"
   :headers        {"host"   "localhost:8043"
                    "accept" "text/html,application/xhtml+xml,*/*;q=0.8"}
   :http-roots     [root]
   :http-config    {}})

(deftest off-endpoint-page-load-serves-the-root-index
  (testing "GET and HEAD serve shadow's root index."
    (with-index-root*
      (fn [root]
        (doseq [method [:get :head]]
          (let [r    (page-req method "/" root)
                resp (rf.testbed.open-in-editor-server/handler r)]
            (is (= [200 index-body (shadow.push-state/handle r)]
                   [(:status resp) (:body resp) resp])
                (str (name method) " `/` serves the root's index.html — exactly what "
                     "shadow answers on a port with no handler")))))))
  (testing "An off-endpoint POST stays a 404 even when push-state would serve it."
    (with-index-root*
      (fn [root]
        (let [r (page-req :post "/" root)]
          (is (= 200 (:status (shadow.push-state/handle r)))
              "control: shadow's own handler WOULD serve this POST the index,
               so the method gate is what keeps the 404")
          (is (= 404 (:status (rf.testbed.open-in-editor-server/handler r)))
              "handler keeps the 404, so the client's editor:// fallback
               still fires"))))))

;; Push-state joins raw paths, so refuse traversal before delegation.

(def ^:private outside-body "<!doctype html><title>outside the root</title>")

(defn- with-sibling-of-root*
  "A root index beside an outside directory with its own index."
  [f]
  (let [parent  (.toFile (java.nio.file.Files/createTempDirectory
                           "oies-traversal-"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        root    (io/file parent "root")
        outside (io/file parent "outside")
        files   [(io/file root "index.html") (io/file outside "index.html")]]
    (try
      (.mkdirs root)
      (.mkdirs outside)
      (spit (first files) index-body)
      (spit (second files) outside-body)
      (f (.getAbsolutePath root))
      (finally
        (doseq [x (conj files root outside parent)] (.delete ^File x))))))

(deftest off-endpoint-page-load-refuses-a-dot-dot-path
  (testing "Both separators refuse a .. segment."
    (with-sibling-of-root*
      (fn [root]
        (doseq [uri ["/../outside/" "/..\\outside\\"]]
          (let [r     (page-req :get uri root)
                label (str "GET " (pr-str uri))]
            ;; `\` separates path segments only where the filesystem says so,
            ;; so the backslash spelling traverses on Windows alone; the 404
            ;; below is asserted everywhere.
            (when (or (not (str/includes? uri "\\")) (= "\\" File/separator))
              (is (= outside-body (:body (shadow.push-state/handle r)))
                  (str "control: shadow's own push-state serves the sibling for "
                       label)))
            (let [resp (rf.testbed.open-in-editor-server/handler r)]
              (is (= 404 (:status resp))
                  (str label " answers 404, never the sibling's index.html"))))))))
  (testing "A name containing two dots still falls through."
    (with-sibling-of-root*
      (fn [root]
        (let [resp (rf.testbed.open-in-editor-server/handler
                     (page-req :get "/a..b/" root))]
          (is (= [200 index-body] [(:status resp) (:body resp)])))))))

;; Probe installed launch-editor so auto-detection cannot silently drop a coordinate.

(def ^:private implementation-dir
  "Working directory that resolves the private launch-editor dependency."
  (delay (io/file @repo-root "implementation")))

(defn ^:private launch-editor-installed?
  "Whether this checkout installed launch-editor."
  []
  (.isDirectory (io/file @implementation-dir "node_modules" "launch-editor")))

(defn ^:private with-dev-cwd*
  "Run launch! from the dev-server working directory."
  [f]
  (let [prev (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" (.getAbsolutePath ^File @implementation-dir))
      (f)
      (finally (System/setProperty "user.dir" prev)))))

(deftest launch-declines-when-the-resolved-editor-would-drop-the-position
  (testing "The real shim declines lost coordinates and admits capable or coordinate-free controls."
    (if-not (and (node-available?) (launch-editor-installed?))
      (skip-or-fail "node or launch-editor not installed")
      (let [f       (.getAbsolutePath (tmp-existing-file))
            decline {:ok false :message rf.testbed.open-in-editor-server/position-unsupported-error}]
        (with-dev-cwd*
          (fn []
            ;; Column-only input and line-only editors exercise distinct probe branches.
            (doseq [[label line column command declined?]
                    [["windsurf, so the handler's pre-spawn fast path is an optimisation, not the only guard"
                      27 9 "windsurf" true]
                     ["CONTROL: a position-carrying command" 27 9 "nonexistent-dir/zed" false]
                     ["a coordinate-free launch: the empty argv tokens read as absent"
                      nil nil "nonexistent-dir/Brackets" false]
                     ["a line alone, position-blind: no column to compare, so the bare-file check alone declines it"
                      27 nil "nonexistent-dir/Brackets" true]
                     ["a column alone, position-blind" nil 7 "nonexistent-dir/Brackets" true]
                     ["CONTROL: a column alone, position-carrying" nil 7 "nonexistent-dir/zed" false]
                     ["gvim at 27:9: line 27 would arrive, column 9 would not"
                      27 9 "nonexistent-dir/gvim" true]
                     ["gvim at a column alone: it normalises to 1:9, and the 9 is still lost"
                      nil 9 "nonexistent-dir/gvim" true]
                     ["CONTROL: gvim with a line alone keeps the coordinate it asked for"
                      27 nil "nonexistent-dir/gvim" false]]]
              (is (= declined? (= decline (rf.testbed.open-in-editor-server/launch! f line column command)))
                  (str (if declined? "declined: " "not declined: ") label)))))))))
