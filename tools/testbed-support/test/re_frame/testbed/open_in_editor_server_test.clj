(ns re-frame.testbed.open-in-editor-server-test
  "JVM tests for the dev-only open-in-editor endpoint.

  Core owns classpath URL decoding. This suite verifies that the endpoint
  delegates there, adds cwd fallback, guards the launch boundary, and emits
  valid responses."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.testbed.open-in-editor-server :as rf.testbed.open-in-editor-server]
            [shadow.http.push-state :as shadow.push-state])
  (:import [java.net InetAddress InetSocketAddress URL URLClassLoader]
           [java.io File]))

;; A `+`-bearing classpath root confirms that delegation preserves URI semantics.

(deftest resolve-file-delegates-classpath-stage-to-core
  (testing "resolve-file's classpath stage IS core's absolutise-file: a
            classpath-relative `:file` resolves to exactly the absolute
            on-disk path core produces — verbatim, `+` preserved, never a
            space-corrupted sibling"
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
          ;; Best-effort cleanup.
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

(deftest resolve-file-passes-an-absolute-path-through
  (is (= "/abs/re-frame2+wip/core.cljs"
         (rf.testbed.open-in-editor-server/resolve-file "/abs/re-frame2+wip/core.cljs"))
      "an already-absolute path is returned unchanged, a + in it included"))

;; Launch is stubbed while the peer, method, Host and Origin guards are tested.

(defn ^:private req
  "Build a minimal endpoint request; nil host/origin values omit the header.

  `:remote-addr` carries the TCP peer as a bare numeric literal — the shape a
  `getHostAddress`-based Ring adapter sends — and defaults to loopback because
  every honest testbed caller is one. shadow-http's own `SocketAddress`
  rendering is built from real objects by `socket-peer`. Pass `:peer` to model a
  remote caller; pass `:peer :absent` to model an adapter that supplied none.

  `:file` is shorthand for the query `file=<file>&line=10`; `:query` sets the
  raw query string instead, nil included."
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
  "The response's CORS header names, plus `vary`, lower-cased and sorted.
  Matched CASE-INSENSITIVELY because shadow-http keeps response headers in a
  case-sensitive map, so a lowercase `access-control-allow-origin` would go
  out as a SECOND header beside shadow's own `Access-Control-Allow-Origin: *`."
  [resp]
  (->> (keys (:headers resp))
       (map #(str/lower-case (name %)))
       (filter #(or (str/starts-with? % "access-control-") (= "vary" %)))
       sort
       vec))

;; shadow-http 0.1.8, which serves shadow-cljs 3.4.10's `:dev-http`, sets
;; `:remote-addr (str (.getRemoteAddress request))`: the accepted socket's
;; `InetSocketAddress.toString`, not a bare literal. A suite of hand-typed bare
;; peers would stay green even if the check refused every real loopback caller
;; on that server, so these peers are BUILT the way shadow-http builds them,
;; from real `java.net` objects.

(defn- socket-peer
  "`:remote-addr` exactly as shadow-http renders an accepted socket's peer:
  `str` of an `InetSocketAddress` over `addr`."
  [^InetAddress addr]
  (str (InetSocketAddress. addr (int 54321))))

(defn- ip
  "The `InetAddress` for a numeric literal; `getByName` parses one without a
  lookup."
  ^InetAddress [^String literal]
  (InetAddress/getByName literal))

(defn- named
  "An `InetAddress` whose hostname half is `host` over the bytes of `literal`,
  built with no lookup in either direction: the object an accepted socket's
  address becomes once something reverse-resolves it, with the answer chosen
  by the test, as a PTR record's owner would choose it."
  ^InetAddress [^String host ^String literal]
  (InetAddress/getByAddress host (.getAddress (ip literal))))

;; --- What `handle` answers, and what it launches --------------------------

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
  "The answer to an admitted launch: a 200 naming the resolved file, no CORS
  header, and exactly one `launch!` call carrying `file line column command`.
  The files here resolve nowhere, so `resolve-file` hands them on unchanged."
  [file line column command]
  [200 (str "{\"ok\":true,\"file\":\"" file "\"}") [] [[file line column command]]])

(deftest handle-answers-and-launches-per-request
  (testing "the peer, Host, Origin, method, query and capability checks all
            answer before `launch!`; every answer is JSON and carries no CORS
            header of its own (shadow-cljs `:dev-http` adds its own
            `Access-Control-Allow-Origin: *`, and the endpoint neither relies
            on it nor adds a second value); an admitted launch reaches
            `launch!` with its coordinate and editor command intact"
    (doseq [[label r expected]
            [;; Admission. The TCP peer is the boundary: Host, Origin and every
             ;; forwarding header are strings the client writes.
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
             ;; Method. A GET must never launch, and the only client posts a
             ;; relative URL, so an admitted OPTIONS is no preflight either.
             ["a GET" (req {:method :get :file "/etc/passwd"}) (refused 405 "method-not-allowed")]
             ["a same-origin OPTIONS" (req {:method :options}) (refused 405 "method-not-allowed")]
             ["an OPTIONS from another local port"
              (req {:method :options :origin "http://localhost:8042"})
              (refused 405 "method-not-allowed")]
             ["an OPTIONS from a loopback IPv6 socket peer"
              (req {:method :options :origin "http://localhost:8042"
                    :peer   (socket-peer (ip "::1"))})
              (refused 405 "method-not-allowed")]
             ;; Query. A missing file is a 400; a malformed escape is a clean
             ;; 400, never an uncaught IllegalArgumentException.
             ["no `file` param" (req {:query "line=10&column=3"}) (refused 400 "missing-file")]
             ["an empty `file=`" (req {:query "file=&line=10"}) (refused 400 "missing-file")]
             ["no query string" (req {}) (refused 400 "missing-file")]
             ["a lone `%`" (req {:query "file=%"}) (refused 400 "malformed-query")]
             ["a `%` without two hex digits" (req {:query "file=abc%zz"})
              (refused 400 "malformed-query")]
             ;; Capability. A 200 claims the COORDINATE arrived: a coordinate
             ;; for a position-blind editor is declined before Node spawns, so
             ;; the client's coordinate-preserving `editor://` fallback runs.
             ["a coordinate for windsurf"
              (req {:query "file=fake_ns/core.cljs&line=27&column=9&editor=windsurf"})
              (refused 422 "editor-position-unsupported")]
             ;; Launches.
             ["a same-origin POST" (req {:file "fake_ns/core.cljs"})
              (launched "fake_ns/core.cljs" 10 nil nil)]
             ["a POST from another local port"
              (req {:origin "http://localhost:8042" :file "fake_ns/core.cljs"})
              (launched "fake_ns/core.cljs" 10 nil nil)]
             ["a POST from a loopback IPv4 socket peer"
              (req {:peer (socket-peer (ip "127.0.0.1")) :file "fake_ns/core.cljs"})
              (launched "fake_ns/core.cljs" 10 nil nil)]
             ["a POST from a loopback IPv6 socket peer"
              (req {:peer (socket-peer (ip "::1")) :file "fake_ns/core.cljs"})
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
             ;; Every position-carrying editor keeps the endpoint at 27:9; the
             ;; editor value is trimmed and lower-cased before the lookup.
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

;; --- The peer, Host and Origin classifiers ---------------------------------

(deftest loopback-peer?-classifies-correctly
  (testing "accepted: the IPv4 loopback block, both IPv6 loopback spellings,
            the IPv4-mapped form and a scope id — as a bare literal, and as
            shadow-http renders an accepted socket, whose hostname half is
            never read"
    (doseq [addr ["127.0.0.1" "127.0.0.53" "::1" "0:0:0:0:0:0:0:1"
                  "::ffff:127.0.0.1" "::1%1"
                  (socket-peer (ip "127.0.0.1"))
                  (socket-peer (ip "::1"))
                  (socket-peer (named "attacker.example" "127.0.0.1"))]]
      (is (#'rf.testbed.open-in-editor-server/loopback-peer? addr)
          (str "loopback peer: " addr))))
  (testing "refused: every other address, anything that would need name
            resolution, a hostname half however loopback it reads (a PTR
            record's owner chooses it), and any socket rendering this check
            does not recognise"
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
  (testing "a string `InetAddress/getByName` would RESOLVE rather than parse is
            never returned. With a JVM hosts file mapping each value to
            127.0.0.1, a looser filter would admit it as loopback"
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
  (testing "a value carrying control characters, backslashes or quotes — a
            `:file` a url-decoded `%0A` puts a newline in, a Windows path, a
            multi-line node stderr trace as the launch error — reaches the
            wire with no raw control byte, and round-trips exactly through a
            real JSON-string decode"
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
  (testing "a column with no line takes line 1, matching the editor:// URI
            fallback, rather than a bare `path:<column>` launch-editor would
            misread as a line; a line alone gets no phantom column, and no
            coordinate no spurious `:1`"
    (is (= ["/abs/core.cljs:1:7" "/abs/core.cljs:3" "/abs/core.cljs:3:7" "/abs/core.cljs"]
           (map #(apply #'rf.testbed.open-in-editor-server/build-file-spec "/abs/core.cljs" %)
                [[nil 7] [3 nil] [3 7] [nil nil]])))))

;; launch-editor silently ignores missing files, so the JVM must reject them.

(deftest launch-rejects-missing-file-before-spawning-node
  (testing "launch! on a path that does not exist short-circuits to a
            file-not-found failure WITHOUT shelling out to node — mirroring
            launch-editor's own existence gate so the endpoint never reports
            a false success (this test does not require node to be present)"
    (let [missing (str (System/getProperty "java.io.tmpdir")
                       "/oies-launch-absent-" (System/nanoTime) ".cljs")]
      (is (= {:ok false :message "file-not-found"}
             (rf.testbed.open-in-editor-server/launch! missing 10 5 nil))
          "missing file rejected before the node spawn"))))

;; --- Real node children ------------------------------------------------------
;;
;; OS pipes are bounded (~64 KiB), so a `launch!` that waited before draining
;; would leave a child that fills a pipe blocked on the write, never exiting.
;; These spawn REAL node children that write more than a pipe's worth; each
;; rebinds a short `*launch-timeout-ms*`, so a wedge comes back as a timeout
;; RESULT rather than a hung suite.

(defn ^:private node-available?
  "Whether a `node` binary is on PATH — the real-subprocess regressions below
  need it. Returns false (⇒ the test self-skips with a note, or FAILS when
  `RF2_REQUIRE_NODE_PROBES` is set — see `skip-or-fail`) rather than
  hard-failing on a node-less box."
  []
  (try
    (let [p (.start (ProcessBuilder. ["node" "--version"]))]
      (and (.waitFor p 10 java.util.concurrent.TimeUnit/SECONDS)
           (zero? (.exitValue p))))
    (catch Throwable _ false)))

;; Every node-backed test self-skips where `node` or the pinned `launch-editor`
;; is missing, so a node-less box stays green. The `jvm-tools-testbed-support`
;; CI job installs both and sets `RF2_REQUIRE_NODE_PROBES`, which turns that
;; skip into a failure: a lane that declared the prerequisite cannot pass on
;; coverage it never had.

(def ^:private node-probes-required?
  "Whether a missing node prerequisite must FAIL rather than self-skip — true
  when `RF2_REQUIRE_NODE_PROBES` is set in the environment. Read once."
  (delay (some? (System/getenv "RF2_REQUIRE_NODE_PROBES"))))

(defn ^:private skip-or-fail
  "Record the self-skip for `missing`, or FAIL when the environment declares
  the prerequisites present. One assertion either way, so the suite's
  assertion count does not move with the skip."
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
  "A real on-disk file whose absolute path `launch!` accepts (file-exists?),
  so the launch path is reached without opening an editor."
  []
  (doto (File/createTempFile "oies-drain-" ".cljs") (.deleteOnExit)))

(defmacro ^:private timed
  "Eval `body`, returning `[result elapsed-ms]`."
  [& body]
  `(let [t0# (System/nanoTime)
         r#  (do ~@body)]
     [r# (quot (- (System/nanoTime) t0#) 1000000)]))

(deftest launch-drains-huge-stdout-and-reports-success
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f    (tmp-existing-file)
          shim (str "var b='x'.repeat(" one-mib-plus ");"
                    "process.stdout.write(b);process.exit(0);")]
      (with-redefs [rf.testbed.open-in-editor-server/launch-shim shim]
        (binding [rf.testbed.open-in-editor-server/*launch-timeout-ms* 8000]
          (is (= {:ok true} (rf.testbed.open-in-editor-server/launch! (.getAbsolutePath f) nil nil nil))
              "a >1 MiB stdout flood + exit 0 is a prompt success, not a timeout"))))))

(deftest launch-drains-huge-stderr-and-reports-bounded-failure
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f (tmp-existing-file)]
      (testing "huge stderr + nonzero exit ⇒ bounded non-timeout diagnostic"
        (let [shim (str "var b='y'.repeat(" one-mib-plus ");"
                        "process.stderr.write(b);process.exit(7);")]
          (with-redefs [rf.testbed.open-in-editor-server/launch-shim shim]
            (binding [rf.testbed.open-in-editor-server/*launch-timeout-ms* 8000]
              (is (re-matches #"y{1,8192}"
                              (str (:message (rf.testbed.open-in-editor-server/launch!
                                               (.getAbsolutePath f) nil nil nil))))
                  "a drained stderr flood is a real failure carrying the flood's
                   head — not the deadlock timeout — and that head is bounded,
                   so a runaway child costs no unbounded parent memory")))))
      (testing "small stderr control still round-trips verbatim"
        (with-redefs [rf.testbed.open-in-editor-server/launch-shim "process.stderr.write('controlled failure');process.exit(7);"]
          (binding [rf.testbed.open-in-editor-server/*launch-timeout-ms* 8000]
            (is (= {:ok false :message "controlled failure"}
                   (rf.testbed.open-in-editor-server/launch! (.getAbsolutePath f) nil nil nil)))))))))

(deftest launch-genuine-timeout-honours-short-budget
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f (tmp-existing-file)]
      (with-redefs [rf.testbed.open-in-editor-server/launch-shim "setInterval(function(){},1000);"] ;; never exits
        (binding [rf.testbed.open-in-editor-server/*launch-timeout-ms* 500]
          (let [[res ms] (timed (rf.testbed.open-in-editor-server/launch! (.getAbsolutePath f) nil nil nil))]
            (is (= {:ok false :message "launch-editor timed out"} res)
                "a non-exiting child times out")
            (is (< ms 5000)
                "the short budget was honoured — nowhere near the default 10 s stall")))))))

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
  (testing "request-level regression: a valid local POST whose `:file`
            resolves to a nonexistent path answers 422 file-not-found (NOT a
            false 200) — `launch!` runs FOR REAL (not stubbed) and
            short-circuits before node, so the client gets a non-2xx and
            falls back to the editor:// URI"
    (let [missing (str "oies_missing_" (System/nanoTime) "/nope.cljs")
          resp    (rf.testbed.open-in-editor-server/handle
                    (req {:query (str "file=" missing "&line=10&column=3")}))]
      (is (= [422 "{\"ok\":false,\"error\":\"file-not-found\"}"] [(:status resp) (:body resp)])
          "a non-2xx, not a false 200, naming the missing file rather than launch-failed"))))

;; Endpoint success must mean the COORDINATE arrived. launch-editor's
;; `get-args.js` has no `windsurf` case, so Windsurf would open the bare file,
;; exit 0, and a 200 would suppress the client's `windsurf://…:27:9` fallback.
;; The client half — a decline runs that fallback once, a 200 suppresses it —
;; is `re-frame.testbed.open-in-editor-client-cljs-test`.

(deftest position-blind-commands-are-declared-not-guessed
  ;; The set's contents are graded against the installed dependency by
  ;; launch-editor-2-14-1-really-does-drop-these-positions, and through
  ;; `handle` by the editor rows of handle-answers-and-launches-per-request.
  ;; A line alone and a column alone are each a coordinate (build-file-spec
  ;; supplies line 1); a request with none loses nothing, and the endpoint's
  ;; classpath resolution is still worth having. nil is auto-detect, whose
  ;; binary launch-editor picks from the running process list, so that
  ;; capability question goes to the dependency at launch time instead —
  ;; launch-declines-when-the-resolved-editor-would-drop-the-position below.
  (testing "position-would-be-dropped? fires only for a coordinate-BEARING
            request to a NAMED position-blind command"
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

;; --- Real testbed coordinates ---------------------------------------------
;;
;; The repository testbeds carry no browser-side source-root pipeline, on the
;; premise that this endpoint resolves their coordinates itself. That premise
;; is about REAL coordinates, so it is witnessed with real ones, under the two
;; source roots shadow-cljs puts on the dev JVM's classpath. Nothing configures
;; a checkout path; `launch!` is stubbed, so no editor opens.

(def ^:private repo-root
  "This repository's root, derived from the endpoint namespace's own location
  on the classpath rather than from `user.dir` — the JVM lane runs from
  `tools/testbed-support/`, the fast spine and IDEs run from elsewhere, and a
  cwd-relative walk would silently resolve to a different tree."
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
  "Run `f` with the real testbed source roots installed on the thread context
  classloader — the shape `shadow-cljs watch` gives the dev JVM."
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
  (testing "a real relative Story coordinate and a real relative Xray
            coordinate each reach the handler and resolve to the intended
            existing file — with no project-root anywhere in the request, the
            client, or this server. This is the endpoint capability that makes
            a browser-side checkout-root pipeline unnecessary"
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

;; --- A page load falls through to shadow's own index handling --------------
;;
;; Naming a `:handler` on a `:dev-http` entry replaces shadow's push-state
;; default, so a `handler` answering 404 to everything off-endpoint would make
;; every wired port 404 at `/`. These requests carry the two keys shadow's
;; `start-build-server` adds before calling the handler — `:http-roots` and
;; `:http-config` — over a throwaway root holding an `index.html`.

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
  "A request for `uri` as shadow hands it to the handler: a browser's HTML
  Accept, and the root list shadow assocs in."
  [method uri root]
  {:uri            uri
   :request-method method
   :remote-addr    "127.0.0.1"
   :headers        {"host"   "localhost:8043"
                    "accept" "text/html,application/xhtml+xml,*/*;q=0.8"}
   :http-roots     [root]
   :http-config    {}})

(deftest off-endpoint-page-load-serves-the-root-index
  (testing "a browser GET of `/` — which no static root answers, because shadow
            serves its roots with index files off — reaches shadow's own
            push-state handler through `handler` and gets the root's
            index.html, so the `/#/stories` URLs that shadow-cljs.edn and the
            dev-testbed launcher print open as written"
    (with-index-root*
      (fn [root]
        (doseq [method [:get :head]]
          (let [r    (page-req method "/" root)
                resp (rf.testbed.open-in-editor-server/handler r)]
            (is (= [200 index-body (shadow.push-state/handle r)]
                   [(:status resp) (:body resp) resp])
                (str (name method) " `/` serves the root's index.html — exactly what "
                     "shadow answers on a port with no handler")))))))
  (testing "…and ONLY a page load. The same request as a POST — same index on
            disk, same HTML Accept — still answers a non-2xx, which is what
            sends the client to its `editor://` URI fallback"
    (with-index-root*
      (fn [root]
        (let [r (page-req :post "/" root)]
          (is (= 200 (:status (shadow.push-state/handle r)))
              "control: shadow's own handler WOULD serve this POST the index,
               so the method gate is what keeps the 404")
          (is (= 404 (:status (rf.testbed.open-in-editor-server/handler r)))
              "handler keeps the 404, so the client's editor:// fallback
               still fires"))))))

;; Push-state concatenates the RAW request URI onto each root,
;; so a `..` segment reaches any `index.html` beside it. Every wired port binds
;; `0.0.0.0`, and push-state never consults the peer, so the fallthrough is the
;; only place to refuse it.

(def ^:private outside-body "<!doctype html><title>outside the root</title>")

(defn- with-sibling-of-root*
  "Call `f` with the absolute path of a throwaway root holding an `index.html`,
  beside a sibling `outside/` directory holding one of its own."
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
  (testing "a page load whose path carries a `..` segment — either separator —
            answers the plain 404 instead of the sibling's index.html"
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
  (testing "…and only a `..` SEGMENT: a name merely containing two dots still
            falls through to push-state and gets the root's index.html"
    (with-sibling-of-root*
      (fn [root]
        (let [resp (rf.testbed.open-in-editor-server/handler
                     (page-req :get "/a..b/" root))]
          (is (= [200 index-body] [(:status resp) (:body resp)])))))))

;; --- Auto-detect is a capability question too ------------------------------
;;
;; With no `editor` sent (a nil preference, or `{:custom …}`), launch-editor
;; picks the binary from the running process list, and its registries reach
;; editors `get-args.js` has no case for. So `launch-shim` asks the dependency
;; rather than predicting it. Below: the installed dependency really behaves
;; that way (also the declared set's drift guard), and the shim really
;; declines it. `handle` answers every launch failure as a 422 carrying the
;; launch message, so a launch-time decline reaches the client as the same
;; 422 and token the declared route emits.

(def ^:private implementation-dir
  "The directory `shadow-cljs watch` runs the dev server from — and the only
  one from which `require('launch-editor')` resolves, since `node -e` walks
  up from the working directory."
  (delay (io/file @repo-root "implementation")))

(defn ^:private launch-editor-installed?
  "Whether the pinned `launch-editor` package is present in this checkout.
  A checkout that never ran `npm ci` self-skips rather than failing red —
  unless `RF2_REQUIRE_NODE_PROBES` is set, which is how a lane that DID
  install it refuses to pass on the skip (`skip-or-fail`)."
  []
  (.isDirectory (io/file @implementation-dir "node_modules" "launch-editor")))

(defn ^:private with-dev-cwd*
  "Run `f` with `user.dir` at `implementation/` — `launch!` reads it at
  request time to set the child's working directory, so this is what makes
  the shim's `require` resolve exactly as it does under `shadow-cljs watch`."
  [f]
  (let [prev (System/getProperty "user.dir")]
    (try
      (System/setProperty "user.dir" (.getAbsolutePath ^File @implementation-dir))
      (f)
      (finally (System/setProperty "user.dir" prev)))))

(def ^:private dependency-probe-script
  "Ask the INSTALLED `launch-editor` what it would do — the same two questions
  `launch-shim` asks, put to the same two modules. Emits `key<TAB><json>` per
  line. `F` is a sentinel filename: `get-args.js` falls through to
  `return [fileName]` for every command it has no case for, so an argv of the
  sentinel ALONE is exactly the documented TOTAL drop."
  (str "var g=require('launch-editor/guess');"
       "var a=require('launch-editor/get-args');"
       "function say(k,v){process.stdout.write(k+'\\t'+JSON.stringify(v)+'\\n');}"
       "['code','code-insiders','cursor','zed','idea','windsurf'].forEach("
       "function(c){say(c,a(c,'F',27,9));});"
       ;; The PARTIAL-drop class: cases `get-args.js` DOES encode, which carry
       ;; the line and discard the column. Neither is the bare file, so the
       ;; total-drop test cannot see them — this is what the shim's column
       ;; differential is for. Two shapes, one probe each.
       "say('gvim',a('gvim','F',27,9));"
       "say('rmate',a('rmate','F',27,9));"
       ;; The auto-detect class: names that appear in the process registries
       ;; but not in the get-args switch.
       "say('brackets',a('Brackets','F',27,9));"
       "say('win-cursor-exe',a('C:\\\\x\\\\Cursor.exe','F',27,9));"
       ;; …and the registries that can select them, one per platform.
       "say('win-process',g.getEditorFromWindowsProcesses("
       "'C:\\\\Program Files\\\\Brackets\\\\Brackets.exe\\r\\n'));"
       "say('linux-process',g.getEditorFromLinuxProcesses('Brackets\\n'));"
       "say('mac-process',g.getEditorFromMacProcesses("
       "'/Applications/Brackets.app/Contents/MacOS/Brackets'));"))

(defn ^:private run-dependency-probe
  "Run `dependency-probe-script` under node from `implementation/` and return
  its `key → raw JSON` map. Values are compared as JSON text: exact, and with
  no JSON dependency on this artefact's tiny test classpath."
  []
  (let [pb   (doto (ProcessBuilder. ^java.util.List ["node" "-e" dependency-probe-script])
               (.directory ^File @implementation-dir)
               (.redirectErrorStream false))
        proc (.start pb)
        out  (slurp (.getInputStream proc))]
    (.waitFor proc 30 java.util.concurrent.TimeUnit/SECONDS)
    (is (zero? (.exitValue proc))
        (str "the dependency probe itself ran: " (slurp (.getErrorStream proc))))
    (into {}
          (for [line  (str/split-lines out)
                :when (str/includes? line "\t")]
            (let [[k v] (str/split line #"\t" 2)]
              [k v])))))

(deftest launch-editor-2-14-1-really-does-drop-these-positions
  (testing "the installed dependency's own answers — the premise every decline
            in this namespace rests on, asked of the package rather than
            asserted from prose"
    (if-not (and (node-available?) (launch-editor-installed?))
      (skip-or-fail "node or launch-editor not installed")
      (let [probe (run-dependency-probe)]
        (testing "the probe returned the keys it was asked for (a silently
                  empty map must not read as a pass)"
          (is (= 13 (count probe)) "every probed key came back"))

        (testing "every command this endpoint DECLARES position-blind really is
                  — and no more. This is the drift guard: a launch-editor
                  release that learns one of these makes it red, which is the
                  signal to drop the entry from the set"
          (doseq [cmd rf.testbed.open-in-editor-server/commands-without-position-support]
            (is (= "[\"F\"]" (get probe cmd))
                (str cmd " is invoked with the bare file — the coordinate is
                     dropped inside the dependency"))))

        (testing "every OTHER command in the vocabulary carries the position,
                  so the decline is as narrow as the invariant allows"
          (doseq [cmd (remove rf.testbed.open-in-editor-server/commands-without-position-support
                              (vals rf.testbed.open-in-editor-server/editor-command-by-keyword))]
            (let [argv (get probe cmd)]
              (is (str/includes? argv "27")
                  (str cmd " was probed, is not a bare-file launch, and its"
                       " argv carries the requested line"))
              ;; The column too: `gvim` below encodes a line and drops the column.
              (is (str/includes? argv "9")
                  (str cmd " argv carries the requested COLUMN")))))

        (testing "PARTIAL DROP — cases the dependency DOES encode, which carry
                  the line and discard the column. Neither is a bare-file
                  launch, so the check above passes them; this is the premise
                  the shim's column differential rests on, and a release that
                  learns either editor's column makes it red"
          (is (= "[\"+27\",\"F\"]" (get probe "gvim"))
              "gvim gets the line as `+27` and no column at all")
          (is (= "[\"--line\",27,\"F\"]" (get probe "rmate"))
              "rmate gets `--line 27` and no column at all"))

        (testing "AUTO-DETECT reaches position-blind binaries the declared set
                  cannot name. Brackets is in all three
                  process registries with no get-args case; on Windows so is
                  Cursor.exe, whose basename the lowercase `cursor` case
                  does not match"
          (is (= "[\"F\"]" (get probe "brackets")))
          (is (= "[\"F\"]" (get probe "win-cursor-exe")))
          (is (= "\"C:\\\\Program Files\\\\Brackets\\\\Brackets.exe\""
                 (get probe "win-process"))
              "the Windows registry selects Brackets from a process list")
          (is (= "\"brackets\"" (get probe "linux-process")))
          (is (= "\"brackets\"" (get probe "mac-process"))))))))

(deftest launch-declines-when-the-resolved-editor-would-drop-the-position
  (testing "the shim's probe runs for real: a coordinate-bearing launch whose
            resolved editor has no position syntax is refused BEFORE
            launch-editor is called, and comes back as the same
            client-visible token the declared route emits.

            Every command below is under a directory that does not exist, so
            the position-CAPABLE control cannot open anything either — the two
            cases differ only in the probe's verdict"
    (if-not (and (node-available?) (launch-editor-installed?))
      (skip-or-fail "node or launch-editor not installed")
      (let [f       (.getAbsolutePath (tmp-existing-file))
            decline {:ok false :message rf.testbed.open-in-editor-server/position-unsupported-error}]
        (with-dev-cwd*
          (fn []
            ;; A COLUMN alone is a coordinate (`build-file-spec` normalises it
            ;; to `path:1:<column>`), so a probe gated on the line token alone
            ;; would wave it through. `gvim` HAS a get-args case — `['+<line>',
            ;; file]` — so it passes the bare-file test while the column is
            ;; gone, and auto-detect reaches it (the Linux process registry
            ;; maps a running `gvim`); the shim's column differential is what
            ;; declines it. Each CONTROL still fails to launch (the binary does
            ;; not exist) but not as the decline: the probe discriminates.
            (doseq [[label line column command declined?]
                    [["a position-blind command the endpoint never names — the class auto-detect reaches"
                      27 9 "nonexistent-dir/Brackets" true]
                     ["windsurf, so the handler's pre-spawn fast path is an optimisation, not the only guard"
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
