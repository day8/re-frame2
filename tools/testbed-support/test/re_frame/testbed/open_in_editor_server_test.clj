(ns re-frame.testbed.open-in-editor-server-test
  "JVM tests for the dev-only open-in-editor endpoint.

  Core owns classpath URL decoding. This suite verifies that the endpoint
  delegates there, adds cwd fallback, guards the launch boundary, and emits
  valid responses."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.source-coords :as source-coords]
            [re-frame.source-coords.editor-uri :as editor-uri]
            [re-frame.testbed.open-in-editor-server :as oies])
  (:import [java.net URL URLClassLoader]
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
            (let [resolved (oies/resolve-file rel-path)]
              (is (some? resolved) "the classpath resource resolved")
              (is (= (#'source-coords/absolutise-file rel-path) resolved)
                  "resolve-file returns core's absolutise-file result;
                   the classpath stage is delegated, not re-implemented")
              (is (.contains ^String resolved "+")
                  "the literal + in the classpath root survived (core owns the
                   `URI.getPath` decode that preserves it)")
              (is (= (.getCanonicalPath src-file)
                     (.getCanonicalPath (File. ^String resolved)))
                  "resolved to the REAL on-disk fixture file, not a
                   space-corrupted sibling that does not exist"))
            (finally
              (.setContextClassLoader (Thread/currentThread) prev))))
        (finally
          ;; Best-effort cleanup.
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

(deftest resolve-file-passes-absolute-and-blank-through
  (testing "an already-absolute path is returned unchanged (incl. a + in it)"
    (is (= "/abs/re-frame2+wip/core.cljs"
           (oies/resolve-file "/abs/re-frame2+wip/core.cljs"))))
  (testing "nil / blank resolve to nil"
    (is (nil? (oies/resolve-file nil)))
    (is (nil? (oies/resolve-file "")))
    (is (nil? (oies/resolve-file "   ")))))

;; Launch is stubbed while the method, Host, Origin, and CORS guards are tested.

(defn ^:private req
  "Build a minimal endpoint request; nil host/origin values omit the header."
  [{:keys [method host origin file]
    :or   {method :post host "localhost:8031"}}]
  {:uri            oies/endpoint-path
   :request-method method
   :query-string   (when file (str "file=" file "&line=10"))
   :headers        (cond-> {}
                     host   (assoc "host" host)
                     origin (assoc "origin" origin))})

(defmacro ^:private with-launch-spy
  "Record launch calls without opening an editor."
  [calls & body]
  `(with-redefs [oies/launch! (fn [& args#]
                                (swap! ~calls conj (vec args#))
                                {:ok true})]
     ~@body))

(deftest guard-allows-valid-local-post
  (testing "a POST addressed to a loopback Host with a loopback Origin
            (a cross-PORT dev request: Story shell on :8042 → app on :8031)
            reaches the launch path and answers 200"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "localhost:8031"
                           :origin "http://localhost:8042"
                           :file   "fake_ns/core.cljs"}))]
          (is (= 200 (:status resp)) "valid local POST is accepted")
          (is (= 1 (count @calls)) "launch! was invoked exactly once")
          (is (= "http://localhost:8042"
                 (get-in resp [:headers "access-control-allow-origin"]))
              "CORS reflects the validated loopback origin, not `*`")))))
  (testing "a same-origin POST that omits Origin entirely still passes on the
            loopback Host check alone"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "127.0.0.1:8031"
                           :origin nil
                           :file   "fake_ns/core.cljs"}))]
          (is (= 200 (:status resp)))
          (is (= 1 (count @calls))))))))

(deftest guard-rejects-cross-origin-post
  (testing "a POST whose Origin is a REMOTE page is rejected 403 and never
            reaches launch! — the drive-by vector the guard closes"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "localhost:8031"
                           :origin "https://evil.example"
                           :file   "/etc/passwd"}))]
          (is (= 403 (:status resp)) "cross-origin POST is forbidden")
          (is (zero? (count @calls)) "launch! was not called")
          (is (not= "*" (get-in resp [:headers "access-control-allow-origin"]))
              "no wildcard CORS — the remote origin is not reflected"))))))

(deftest guard-rejects-non-loopback-host
  (testing "a POST addressed to a non-loopback Host (a public binding /
            DNS-rebinding attempt) is rejected 403 before launch!"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "app.evil.example"
                           :origin nil
                           :file   "/etc/passwd"}))]
          (is (= 403 (:status resp)))
          (is (zero? (count @calls)))))))
  (testing "a missing Host header is also rejected"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post :host nil :origin nil
                           :file "/etc/passwd"}))]
          (is (= 403 (:status resp)))
          (is (zero? (count @calls))))))))

(deftest guard-rejects-non-post-drive-by
  (testing "a simple GET drive-by (the `<img>`/`<form>`/`no-cors` class) is
            rejected 405 even from a loopback Host — it never launches"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :get
                           :host   "localhost:8031"
                           :origin nil
                           :file   "/etc/passwd"}))]
          (is (= 405 (:status resp)) "GET is method-not-allowed")
          (is (zero? (count @calls)) "launch! was not called"))))))

(deftest guard-options-preflight-reflects-loopback-origin
  (testing "an OPTIONS preflight from a loopback origin reflects that origin
            and offers POST only (no GET) — never launches"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :options
                           :host   "localhost:8031"
                           :origin "http://localhost:8042"}))]
          (is (= 204 (:status resp)))
          (is (= "http://localhost:8042"
                 (get-in resp [:headers "access-control-allow-origin"])))
          (is (= "POST, OPTIONS"
                 (get-in resp [:headers "access-control-allow-methods"]))
              "GET is no longer an allowed method")
          (is (zero? (count @calls))))))))

;; Safety negatives assert both the response and absence of a launch call.

(deftest guard-rejects-opaque-null-origin-post
  (testing "an opaque Origin is rejected before launch"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "localhost:8031"
                           :origin "null"
                           :file   "/etc/passwd"}))]
          (is (= 403 (:status resp)) "opaque-origin POST is forbidden")
          (is (re-find #"\"error\":\"forbidden\"" (:body resp)))
          (is (zero? (count @calls)) "launch! was not called")
          (is (= "null" (get-in resp [:headers "access-control-allow-origin"]))
              "CORS denies via `null` — never reflects the opaque origin, never `*`")
          (is (not= "*" (get-in resp [:headers "access-control-allow-origin"]))))))))

(deftest guard-options-preflight-denies-remote-origin
  (testing "a remote preflight receives no usable CORS origin"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :options
                           :host   "localhost:8031"
                           :origin "https://evil.example"}))]
          (is (= 204 (:status resp)) "preflight still answers 204")
          (is (= "null" (get-in resp [:headers "access-control-allow-origin"]))
              "the remote origin is DENIED via `null`")
          (is (not= "*" (get-in resp [:headers "access-control-allow-origin"]))
              "never a wildcard")
          (is (not= "https://evil.example"
                    (get-in resp [:headers "access-control-allow-origin"]))
              "the remote origin is never reflected back")
          (is (zero? (count @calls)) "launch! was not called")))))
  (testing "a preflight without a validated loopback Origin is denied"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :options
                           :host   "app.evil.example"
                           :origin nil}))]
          (is (= 204 (:status resp)))
          (is (= "null" (get-in resp [:headers "access-control-allow-origin"])))
          (is (zero? (count @calls))))))))

(deftest guard-non-endpoint-path-falls-through
  (testing "a request for any other path still falls through (nil) untouched"
    (is (nil? (oies/handle {:uri "/something/else"
                            :request-method :post
                            :headers {"host" "localhost:8031"}})))))

;; URI decoding failures must become JSON 400 responses at the Ring boundary.

(deftest malformed-query-returns-clean-400
  (testing "a lone `%` in the query string (an incomplete percent-escape)
            answers a clean 400, not an uncaught IllegalArgumentException"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=%"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 400 (:status resp)) "malformed query is a clean 400, not a throw")
          (is (re-find #"\"ok\":false" (:body resp)))
          (is (re-find #"\"error\":\"malformed-query\"" (:body resp)))
          (is (zero? (count @calls)) "launch! was never called")))))
  (testing "a `%` not followed by two hex digits (e.g. `%zz`) also answers
            a clean 400"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=abc%zz"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 400 (:status resp)))
          (is (re-find #"\"error\":\"malformed-query\"" (:body resp)))
          (is (zero? (count @calls))))))))

;; Query parsing uses URI semantics so a literal `+` in a path stays intact.

(deftest parse-query-preserves-literal-plus
  (testing "a literal `+` is not form-decoded to a space"
    (is (= "C:/code/re-frame2+wip/core.cljs"
           (get (#'oies/parse-query
                 "file=C:/code/re-frame2+wip/core.cljs&line=10")
                "file"))
        "a literal + in the query value survives verbatim"))
  (testing "percent-escapes still decode with decodeURIComponent semantics"
    (let [q (#'oies/parse-query "file=re-frame2%2Bwip%2Fa%20b.cljs")]
      (is (= "re-frame2+wip/a b.cljs" (get q "file"))
          "%2B decodes to a literal +, %2F to /, %20 to a space")))
  (testing "keys and multiple params round-trip; last value wins"
    (let [q (#'oies/parse-query "file=a+b&line=10&file=c+d")]
      (is (= "c+d" (get q "file")) "last value wins, + preserved")
      (is (= "10" (get q "line"))))))

(deftest endpoint-preserves-literal-plus-through-to-launch
  (testing "a POST whose `file` carries a literal `+` hands launch! the path
            with the `+` intact"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=deep/re-frame2+wip/core.cljs&line=7"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 200 (:status resp)) "the launch path is reached")
          (is (= 1 (count @calls)) "launch! invoked once")
          (is (str/includes? (first (first @calls)) "re-frame2+wip")
              "the literal + survived parse-query into the abs-path launch! got")
          (is (not (str/includes? (first (first @calls)) "re-frame2 wip"))
              "the + was NOT form-decoded to a space"))))))

(deftest loopback-host?-classifies-correctly
  (testing "loopback hosts (with/without port, IPv4, IPv6, case)"
    (is (#'oies/loopback-host? "localhost"))
    (is (#'oies/loopback-host? "localhost:8031"))
    (is (#'oies/loopback-host? "LocalHost:8031"))
    (is (#'oies/loopback-host? "127.0.0.1"))
    (is (#'oies/loopback-host? "127.0.0.1:8042"))
    (is (#'oies/loopback-host? "127.5.6.7"))
    (is (#'oies/loopback-host? "::1"))
    (is (#'oies/loopback-host? "[::1]:8080")))
  (testing "non-loopback hosts are rejected"
    (is (not (#'oies/loopback-host? "evil.example")))
    (is (not (#'oies/loopback-host? "app.evil.example:8031")))
    (is (not (#'oies/loopback-host? "10.0.0.5")))
    (is (not (#'oies/loopback-host? "0.0.0.0")))
    ;; A textual 127 prefix is not an IPv4 loopback address.
    (is (not (#'oies/loopback-host? "127malicious.example")))
    (is (not (#'oies/loopback-host? nil)))
    (is (not (#'oies/loopback-host? "")))))

(deftest origin-host-extracts-and-rejects-opaque
  (testing "the host is extracted from a serialized origin"
    (is (= "localhost" (#'oies/origin-host "http://localhost:8042")))
    (is (= "127.0.0.1" (#'oies/origin-host "http://127.0.0.1:8031"))))
  (testing "the opaque `null` origin and blank/nil yield nil (never loopback)"
    (is (nil? (#'oies/origin-host "null")))
    (is (nil? (#'oies/origin-host nil)))
    (is (nil? (#'oies/origin-host "")))))

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
  (testing "a 200 response whose `:file` carries a raw newline (as a
            url-decoded `file=...%0A...` request would produce) is escaped
            to a `\\n` sequence, not a literal newline byte — the body
            parses as valid JSON and round-trips the original value"
    (let [calls (atom [])
          file  "day8/re_frame2_xray/core.cljs\ninjected-line\ttabbed"]
      (with-launch-spy calls
        (with-redefs [oies/resolve-file (constantly file)]
          (let [resp (oies/handle
                       (req {:method :post
                             :host   "localhost:8031"
                             :origin nil
                             :file   "day8%2Fre_frame2_xray%2Fcore.cljs%0Ainjected-line%09tabbed"}))]
            (is (= 200 (:status resp)))
            (is (not (str/includes? (:body resp) "\n"))
                "no raw newline byte reaches the wire — it must be escaped")
            (is (not (str/includes? (:body resp) "\t"))
                "no raw tab byte reaches the wire — it must be escaped")
            (is (= file (json-body->file-value (:body resp) "\"file\":\""))
                "the escaped value round-trips to the exact original string
                 through a real JSON-string decode"))))))
  (testing "a 422 response whose launch error carries a raw CR + control
            char (as a multi-line node stderr trace would) is likewise
            valid, round-tripping JSON"
    (let [calls (atom [])
          msg   (str "launch-editor: boom\r\nat frame " (char 0x01) "end")]
      (with-redefs [oies/launch! (fn [& args#]
                                   (swap! calls conj (vec args#))
                                   {:ok false :message msg})]
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "localhost:8031"
                           :origin nil
                           :file   "fake_ns/core.cljs"}))]
          (is (= 422 (:status resp)))
          (is (not (str/includes? (:body resp) "\r")))
          (is (= msg (json-body->file-value (:body resp) "\"error\":\"")))))))
  (testing "the plain ASCII fast path is unaffected — no spurious escaping"
    (is (= "\"plain/path.cljs\""
           (str "\"" (#'oies/escape-json-string "plain/path.cljs") "\"")))))

;; launch-editor parses the first numeric suffix as a line, so column-only
;; coordinates must be encoded as `path:1:column`.

(deftest build-file-spec-normalizes-column-only-to-line-1
  (testing "column with no line is normalized to line 1, NOT encoded as a
            bare `path:<column>` that launch-editor would misread as a line"
    (is (= "/abs/core.cljs:1:7" (#'oies/build-file-spec "/abs/core.cljs" nil 7))
        "column-only → line 1 + column, matching the editor:// URI fallback"))
  (testing "line with no column is unaffected (baseline — no phantom column)"
    (is (= "/abs/core.cljs:3" (#'oies/build-file-spec "/abs/core.cljs" 3 nil))))
  (testing "both line and column present"
    (is (= "/abs/core.cljs:3:7" (#'oies/build-file-spec "/abs/core.cljs" 3 7))))
  (testing "neither present: bare path (no spurious `:1`)"
    (is (= "/abs/core.cljs" (#'oies/build-file-spec "/abs/core.cljs" nil nil)))))

(deftest launch-passes-column-without-line-through-to-file-spec
  (testing "end-to-end through the endpoint: a request with `column` and no
            `line` reaches `launch!` with `line` nil / `column` 7 — parsing
            never drops it — and folding those exact values through
            `build-file-spec` (the function `launch!` delegates to) yields
            the NORMALIZED `path:1:<column>` spec, not a bare `path:<column>`
            launch-editor would misread as a line jump"
    (let [calls (atom [])]
      (with-redefs [oies/launch! (fn [& args] (swap! calls conj (vec args))
                                   {:ok true})]
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=fake_ns/core.cljs&column=7"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 200 (:status resp)))
          (is (= 1 (count @calls)))
          (let [[abs-path line column _cmd] (first @calls)]
            (is (nil? line) "no line param was sent")
            (is (= 7 column) "column parsed through, not dropped upstream")
            (is (= (str abs-path ":1:7") (#'oies/build-file-spec abs-path line column))
                "build-file-spec normalizes column-only to line 1")))))))

;; launch-editor silently ignores missing files, so the JVM must reject them.

(deftest file-exists?-detects-real-and-missing-paths
  (testing "an existing file is detected"
    (let [tmp (File/createTempFile "oies-exists" ".cljs")]
      (try
        (is (true? (#'oies/file-exists? (.getAbsolutePath tmp)))
            "a real on-disk file exists")
        (finally (.delete tmp)))))
  (testing "a nonexistent path, nil, and blank are all 'does not exist'"
    (is (false? (#'oies/file-exists?
                  (str (System/getProperty "java.io.tmpdir")
                       "/oies-absent-" (System/nanoTime) ".cljs"))))
    (is (false? (#'oies/file-exists? nil)))
    (is (false? (#'oies/file-exists? "   ")))))

(deftest launch-rejects-missing-file-before-spawning-node
  (testing "launch! on a path that does not exist short-circuits to a
            file-not-found failure WITHOUT shelling out to node — mirroring
            launch-editor's own existence gate so the endpoint never reports
            a false success (this test does not require node to be present)"
    (let [missing (str (System/getProperty "java.io.tmpdir")
                       "/oies-launch-absent-" (System/nanoTime) ".cljs")]
      (is (= {:ok false :message "file-not-found"}
             (oies/launch! missing 10 5 nil))
          "missing file rejected before the node spawn")))
  (testing "a nil / blank abs-path is likewise file-not-found (never node)"
    (is (= {:ok false :message "file-not-found"} (oies/launch! nil 1 1 nil)))
    (is (= {:ok false :message "file-not-found"} (oies/launch! "   " 1 1 nil)))))

;; ---------------------------------------------------------------------------
;; Real-subprocess pipe-drain regressions (rf2-j538f7.21).
;;
;; OS pipes are bounded (~64 KiB). The pre-fix `launch!` called `.waitFor`
;; BEFORE reading either child pipe and never read stdout at all — so a child
;; that filled either pipe blocked on the write, could not reach `process.exit`,
;; and the JVM timed out waiting for an exit its own undrained pipe prevented.
;; These spawn REAL node children (the boundary the unit path never exercised
;; — every other launch! test stubs launch! or stops at missing-file rejection)
;; that write MORE than a pipe's worth to stdout / stderr. With the fix (stdout
;; DISCARDed, stderr drained concurrently) each child exits and `launch!`
;; classifies it correctly. Each test rebinds a short `*launch-timeout-ms*`
;; budget so a reintroduced wait-before-drain fails as a BOUNDED timeout result
;; rather than hanging the suite.
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; A SKIP MUST NOT READ AS A PASS IN A LANE THAT ARMED THE PREREQUISITE
;; (rf2-cl8mg).
;;
;; Every real-subprocess block below self-skips when `node` — or the pinned
;; `launch-editor` package — is missing, so a node-less developer box stays
;; green. That is right for a laptop and wrong for CI: the exit code cannot
;; distinguish a run from a skip, and the `jvm-tools-testbed-support` job used
;; to install no node deps at all, so every launch-editor-backed block skipped
;; and the job reported green on coverage it never had (42 tests / 229
;; assertions there against 42 / 260 with the dependency present — the
;; 31-assertion delta was the only signal, and nothing read it).
;;
;; The job now installs the dependency AND sets `RF2_REQUIRE_NODE_PROBES`,
;; which flips the skip into a failure: a lane that declared the prerequisite
;; present and then reached a skip has lost the gate it exists to be, and says
;; so instead of passing quietly.
;; ---------------------------------------------------------------------------

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
           "install node and run `npm ci` in `implementation/` (rf2-cl8mg).")))

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
  ;; Criterion 1: a child that floods STDOUT (>1 MiB) and exits 0 is a SUCCESS,
  ;; reached promptly — never the timeout the undrained-stdout deadlock
  ;; manufactured. The 8 s budget bounds a reintroduced wait-before-drain to a
  ;; timeout RESULT at ~8 s (this assertion then goes red) instead of hanging.
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f    (tmp-existing-file)
          shim (str "var b='x'.repeat(" one-mib-plus ");"
                    "process.stdout.write(b);process.exit(0);")]
      (with-redefs [oies/launch-shim shim]
        (binding [oies/*launch-timeout-ms* 8000]
          (let [[res ms] (timed (oies/launch! (.getAbsolutePath f) nil nil nil))]
            (is (= {:ok true} res)
                "a >1 MiB stdout flood + exit 0 is a prompt success, not a timeout")
            (is (< ms 8000)
                "returned well within the budget — the child was not wedged by its own pipe")))))))

(deftest launch-drains-huge-stderr-and-reports-bounded-failure
  ;; Criterion 2: a child that floods STDERR (>1 MiB) and exits nonzero is a
  ;; FAILURE carrying a BOUNDED diagnostic (never the timeout, never unbounded
  ;; memory), plus a small-stderr control.
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f (tmp-existing-file)]
      (testing "huge stderr + nonzero exit ⇒ bounded non-timeout diagnostic"
        (let [shim (str "var b='y'.repeat(" one-mib-plus ");"
                        "process.stderr.write(b);process.exit(7);")]
          (with-redefs [oies/launch-shim shim]
            (binding [oies/*launch-timeout-ms* 8000]
              (let [[res ms] (timed (oies/launch! (.getAbsolutePath f) nil nil nil))]
                (is (false? (:ok res)))
                (is (not= "launch-editor timed out" (:message res))
                    "a drained stderr flood is a real failure, not the deadlock timeout")
                (is (<= (count (:message res)) 8192)
                    "the retained diagnostic is bounded — no unbounded parent memory")
                (is (< ms 8000) "returned within the budget"))))))
      (testing "small stderr control still round-trips verbatim"
        (with-redefs [oies/launch-shim "process.stderr.write('controlled failure');process.exit(7);"]
          (binding [oies/*launch-timeout-ms* 8000]
            (is (= {:ok false :message "controlled failure"}
                   (oies/launch! (.getAbsolutePath f) nil nil nil)))))))))

(deftest launch-genuine-timeout-honours-short-budget
  ;; Criterion 4 (a): a child that never exits yields the timeout message
  ;; WITHIN the (short, test-configurable) budget — proving the wait is bounded
  ;; and the budget is honoured, not the hardcoded 10 s.
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [f (tmp-existing-file)]
      (with-redefs [oies/launch-shim "setInterval(function(){},1000);"] ;; never exits
        (binding [oies/*launch-timeout-ms* 500]
          (let [[res ms] (timed (oies/launch! (.getAbsolutePath f) nil nil nil))]
            (is (= {:ok false :message "launch-editor timed out"} res)
                "a non-exiting child times out")
            (is (< ms 5000)
                "the short budget was honoured — nowhere near the old 10 s stall")))))))

(deftest terminate!-force-kills-a-child-that-ignores-graceful-destroy
  ;; Criterion 4 (b): after cleanup the child is no longer alive, INCLUDING the
  ;; force-termination fallback when a graceful destroy does not complete (a
  ;; child that traps SIGTERM — the force path is exercised on POSIX CI; on
  ;; Windows `.destroy` already terminates forcibly).
  (if-not (node-available?)
    (skip-or-fail "node not on PATH")
    (let [pb   (doto (ProcessBuilder. ["node" "-e"
                                       "process.on('SIGTERM',function(){});setInterval(function(){},1000);"])
                 (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                 (.redirectErrorStream true))
          proc (.start pb)]
      (try
        (is (.isAlive proc) "the child started and is running")
        (is (true? (#'oies/terminate! proc))
            "terminate! confirms the child is dead (force-destroy fallback used if needed)")
        (is (not (.isAlive proc)) "the child is no longer alive after cleanup")
        (finally
          (when (.isAlive proc) (.destroyForcibly proc)))))))

(deftest endpoint-rejects-missing-file-with-422
  (testing "request-level regression: a valid local POST whose `:file`
            resolves to a nonexistent path answers 422 file-not-found (NOT a
            false 200) — `launch!` runs FOR REAL (not stubbed) and
            short-circuits before node, so the client gets a non-2xx and
            falls back to the editor:// URI"
    (let [missing (str "oies_missing_" (System/nanoTime) "/nope.cljs")
          resp    (oies/handle
                    {:uri            oies/endpoint-path
                     :request-method :post
                     :query-string   (str "file=" missing "&line=10&column=3")
                     :headers        {"host" "localhost:8031"}})]
      (is (= 422 (:status resp)) "missing file is a non-2xx, not a false 200")
      (is (re-find #"\"ok\":false" (:body resp)))
      (is (re-find #"\"error\":\"file-not-found\"" (:body resp))
          "the client-visible error names the missing file, not launch-failed"))))

;; A missing query parameter is a 400; a resolved but absent file is a 422.

(deftest endpoint-missing-file-param-returns-400
  (testing "a blank or absent file parameter returns 400 before launch"
    (let [calls (atom [])]
      (with-launch-spy calls
        (testing "no `file` param in the query string"
          (let [resp (oies/handle
                       {:uri            oies/endpoint-path
                        :request-method :post
                        :query-string   "line=10&column=3"
                        :headers        {"host" "localhost:8031"}})]
            (is (= 400 (:status resp)))
            (is (re-find #"\"ok\":false" (:body resp)))
            (is (re-find #"\"error\":\"missing-file\"" (:body resp)))))
        (testing "an empty `file=` value"
          (let [resp (oies/handle
                       {:uri            oies/endpoint-path
                        :request-method :post
                        :query-string   "file=&line=10"
                        :headers        {"host" "localhost:8031"}})]
            (is (= 400 (:status resp)))
            (is (re-find #"\"error\":\"missing-file\"" (:body resp)))))
        (testing "no query string at all"
          (let [resp (oies/handle
                       {:uri            oies/endpoint-path
                        :request-method :post
                        :query-string   nil
                        :headers        {"host" "localhost:8031"}})]
            (is (= 400 (:status resp)))
            (is (re-find #"\"error\":\"missing-file\"" (:body resp)))))
        (is (zero? (count @calls))
            "launch! was never called on any missing-file path")))))

;; The query vocabulary maps to launch-editor binary names.

(deftest editor-hint-maps-keyword-to-launch-command
  (testing "a known editor keyword resolves to its launch command"
    (is (= "code"          (oies/editor-hint "vscode")))
    (is (= "code-insiders" (oies/editor-hint "vscode-insiders")))
    (is (= "cursor"        (oies/editor-hint "cursor")))
    (is (= "windsurf"      (oies/editor-hint "windsurf")))
    (is (= "zed"           (oies/editor-hint "zed")))
    (is (= "idea"          (oies/editor-hint "idea"))))
  (testing "the value is lower-cased and trimmed before lookup"
    (is (= "code"   (oies/editor-hint "VSCode")))
    (is (= "cursor" (oies/editor-hint "  Cursor  ")))
    (is (= "idea"   (oies/editor-hint "IDEA"))))
  (testing "blank / unknown / non-string → nil (launch-editor auto-detects)"
    (is (nil? (oies/editor-hint "")))
    (is (nil? (oies/editor-hint "   ")))
    (is (nil? (oies/editor-hint "custom")) "the {:custom …} shape is not in the map")
    (is (nil? (oies/editor-hint "emacs")) "an editor with no mapping → auto-detect")
    (is (nil? (oies/editor-hint nil)))
    (is (nil? (oies/editor-hint 42)) "a non-string is rejected")))

(deftest editor-command-by-keyword-is-the-launch-command-vocabulary
  (testing "the public map defines the supported launch-command vocabulary"
    (is (= {"vscode"          "code"
            "vscode-insiders" "code-insiders"
            "cursor"          "cursor"
            "windsurf"        "windsurf"
            "zed"             "zed"
            "idea"            "idea"}
           oies/editor-command-by-keyword))))

(deftest endpoint-passes-editor-hint-through-to-launch
  (testing "the editor query value reaches launch! as a command hint"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=fake_ns/core.cljs&line=3&editor=vscode"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 200 (:status resp)))
          (is (= 1 (count @calls)))
          (let [[_abs _line _col cmd] (first @calls)]
            (is (= "code" cmd)
                "editor=vscode resolved to launch-editor's `code` command
                 (the mapping is applied, not the raw keyword)"))))))
  (testing "an unknown editor keyword → nil command hint (auto-detect)"
    (let [calls (atom [])]
      (with-launch-spy calls
        (oies/handle
          {:uri            oies/endpoint-path
           :request-method :post
           :query-string   "file=fake_ns/core.cljs&editor=emacs"
           :headers        {"host" "localhost:8031"}})
        (is (nil? (nth (first @calls) 3))
            "unknown editor → nil hint"))))
  (testing "no `editor` param → nil command hint (baseline)"
    (let [calls (atom [])]
      (with-launch-spy calls
        (oies/handle
          {:uri            oies/endpoint-path
           :request-method :post
           :query-string   "file=fake_ns/core.cljs"
           :headers        {"host" "localhost:8031"}})
        (is (nil? (nth (first @calls) 3))
            "no editor param → nil hint")))))

;; rf2-1i1ec — endpoint success must mean the COORDINATE arrived.
;;
;; `launch-editor`'s `get-args.js` switches on the command basename: `code`,
;; `code-insiders`, `cursor`, `zed` and the JetBrains binaries get a position
;; argument, everything else falls through to a bare-file launch that exits 0.
;; Windsurf is in this endpoint's vocabulary and NOT in that switch, so before
;; this decline the endpoint answered 200 to a `line=27&column=9` request that
;; had opened the file at an arbitrary prior cursor position — and that 200
;; suppressed the client's `windsurf://…:27:9` fallback, which does carry it.
;;
;; The complementary client-side half (a declined answer runs the
;; coordinate-preserving fallback exactly once; a 200 suppresses it) is
;; `re-frame.testbed.open-in-editor-client-cljs-test`.

(deftest position-blind-commands-are-declared-not-guessed
  (testing "the position-blind set names exactly the vocabulary commands
            launch-editor has no get-args case for"
    (is (= #{"windsurf"} oies/commands-without-position-support))
    (is (every? (set (vals oies/editor-command-by-keyword))
                oies/commands-without-position-support)
        "every declared position-blind command is a command this endpoint can
         actually be asked for — the set cannot drift onto a phantom binary"))
  (testing "position-would-be-dropped? fires only for a coordinate-BEARING
            request to a position-blind command"
    (is (true?  (oies/position-would-be-dropped? "windsurf" 27 9)))
    (is (true?  (oies/position-would-be-dropped? "windsurf" 27 nil))
        "a line alone is a coordinate")
    (is (true?  (oies/position-would-be-dropped? "windsurf" nil 9))
        "a column alone is a coordinate (build-file-spec supplies line 1)")
    (is (false? (oies/position-would-be-dropped? "windsurf" nil nil))
        "no coordinate → nothing to lose; the endpoint's classpath resolution
         is still worth having")
    (is (false? (oies/position-would-be-dropped? "code" 27 9)))
    (is (false? (oies/position-would-be-dropped? "cursor" 27 9)))
    (is (false? (oies/position-would-be-dropped? "zed" 27 9)))
    (is (false? (oies/position-would-be-dropped? "idea" 27 9)))
    (is (false? (oies/position-would-be-dropped? "code-insiders" 27 9)))
    (is (false? (oies/position-would-be-dropped? nil 27 9))
        "this predicate answers for NAMED commands only. nil is auto-detect,
         whose binary launch-editor chooses from the running process list —
         so the capability question is asked of the dependency at launch time
         instead, by launch-shim's probe. That auto-detect route is NOT
         undeclined: it is covered by
         launch-declines-when-the-resolved-editor-would-drop-the-position and
         endpoint-turns-a-launch-time-decline-into-the-same-422 below")))

(deftest endpoint-declines-coordinate-bearing-windsurf-request
  (testing "editor=windsurf with line+column is DECLINED before Node is
            spawned: a bare-file launch is not success for a
            coordinate-bearing request"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=fake_ns/core.cljs&line=27&column=9&editor=windsurf"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 422 (:status resp))
              "a 200 here would be a false claim that 27:9 reached the editor")
          (is (not (<= 200 (:status resp) 299))
              "non-2xx is the whole contract with the client: `fetch-launcher!`
               runs the coordinate-preserving URI fallback on any non-2xx")
          (is (re-find #"\"ok\":false" (:body resp)))
          (is (re-find #"\"error\":\"editor-position-unsupported\"" (:body resp))
              "the client-visible error names the capability, not launch-failed")
          (is (zero? (count @calls))
              "launch! was never called — no editor opens at the wrong place")))))
  (testing "a coordinate-FREE windsurf request still uses the endpoint: it
            loses nothing, and classpath resolution is what the URI fallback
            cannot do"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     {:uri            oies/endpoint-path
                      :request-method :post
                      :query-string   "file=fake_ns/core.cljs&editor=windsurf"
                      :headers        {"host" "localhost:8031"}})]
          (is (= 200 (:status resp)))
          (is (= 1 (count @calls)))
          (is (= "windsurf" (nth (first @calls) 3))
              "the windsurf hint still reaches launch! when no coordinate is
               at stake — the vocabulary is unchanged"))))))

(deftest endpoint-still-serves-every-position-carrying-editor
  (testing "rf2-1i1ec must not make every editor fall back: each vocabulary
            entry launch-editor CAN encode a position for still reaches
            launch! with 27:9 and returns 2xx"
    (doseq [[editor expected-cmd] [["vscode"          "code"]
                                   ["vscode-insiders" "code-insiders"]
                                   ["cursor"          "cursor"]
                                   ["zed"             "zed"]
                                   ["idea"            "idea"]]]
      (testing (str "editor=" editor)
        (let [calls (atom [])]
          (with-launch-spy calls
            (let [resp (oies/handle
                         {:uri            oies/endpoint-path
                          :request-method :post
                          :query-string   (str "file=fake_ns/core.cljs&line=27&column=9&editor=" editor)
                          :headers        {"host" "localhost:8031"}})]
              (is (<= 200 (:status resp) 299)
                  "the endpoint is still preferred for this editor")
              (is (= 1 (count @calls)) "launch! was invoked")
              (let [[_abs line column cmd] (first @calls)]
                (is (= expected-cmd cmd))
                (is (= 27 line)   "the line survived to the launcher")
                (is (= 9 column)  "the column survived to the launcher")))))))))

;; The endpoint's own `path:line:column` encoding is pinned by
;; `build-file-spec-normalizes-column-only-to-line-1` above, which walks all
;; four coordinate branches; the dependency-side loss the decline exists for is
;; pinned by `launch-editor-2-14-1-really-does-drop-these-positions` below,
;; which asks the installed `launch-editor/get-args` and proves windsurf is
;; invoked with the bare file. A third test asserting only
;; `(build-file-spec "/abs/src/app.cljs" 27 9)` witnessed neither (rf2-6r9j.123).

;; Windows paths exercise the JSON backslash and quote rules.

(deftest escape-json-string-escapes-backslash-and-doublequote
  (testing "backslashes and double-quotes are escaped"
    (is (= "a\\\\b" (#'oies/escape-json-string "a\\b"))
        "one backslash → two")
    (is (= "say \\\"hi\\\"" (#'oies/escape-json-string "say \"hi\""))
        "double-quotes are escaped")
    (is (= "C:\\\\Users\\\\me\\\\core.cljs"
           (#'oies/escape-json-string "C:\\Users\\me\\core.cljs"))
        "a Windows abs-path's backslashes are all doubled")))

(deftest json-resp-escapes-windows-backslash-path
  (testing "a Windows path with a quote round-trips through the JSON response"
    (let [calls (atom [])
          file  "C:\\Users\\me\\code\\re-frame2\\src\\a\"b.cljs"]
      (with-launch-spy calls
        (with-redefs [oies/resolve-file (constantly file)]
          (let [resp (oies/handle
                       (req {:method :post
                             :host   "localhost:8031"
                             :origin nil
                             :file   "resolve-file-is-redefed"}))]
            (is (= 200 (:status resp)))
            (is (str/includes? (:body resp) "\\\\")
                "backslashes are doubled in the body")
            (is (str/includes? (:body resp) "\\\"")
                "the double-quote is escaped in the body")
            (is (= file (json-body->file-value (:body resp) "\"file\":\""))
                "the escaped Windows path round-trips to the exact original")))))))

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
        (let [resolved (oies/resolve-file rel-path)]
          (is (some? resolved)
              "the off-classpath relative coord resolved via the cwd branch")
          (is (.isAbsolute (File. ^String resolved))
              "the cwd branch returns an ABSOLUTE path (branch 3 would return
               the raw relative input unchanged)")
          (is (= (.getCanonicalPath src-file)
                 (.getCanonicalPath (File. ^String resolved)))
              "resolved to the REAL on-disk fixture under the working dir"))
        (finally
          (System/setProperty "user.dir" prev-cwd)
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp sub))
          (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; Consumer-shaped resolution witness (rf2-3xq1v)
;; ---------------------------------------------------------------------------
;;
;; The suite above proves the endpoint's MECHANISM with a synthetic fixture on
;; a throwaway classpath root. rf2-3xq1v retired the browser-side source-root
;; pipeline the repository testbeds used to carry, on the premise that this
;; endpoint already resolves what that pipeline resolved. That premise is a
;; claim about REAL testbed coordinates, so it is witnessed with real ones:
;; the two source roots shadow-cljs actually puts on the dev JVM's classpath
;; (`../tools/story/testbeds` and `../tools/xray/testbeds`), and a
;; classpath-relative coordinate under each that exists on disk today.
;;
;; The server carries no project-root concept at all — which is stronger than
;; the CLJS-side condition "with Story and Xray project-root config unset",
;; because there is no such slot here to leave unset. Nothing configures a
;; checkout path; `launch!` is stubbed, so no editor opens.

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

(deftest consumer-coords-name-files-that-exist
  (testing "the witness below is only worth its green if its coordinates are
            REAL — a renamed testbed file must fail here, loudly, rather than
            quietly turn the resolution assertions into assertions about a
            path that resolves to nothing"
    (doseq [{:keys [tool root file]} consumer-coords]
      (let [root-dir (io/file @repo-root root)
            src      (io/file root-dir file)]
        (is (.isDirectory root-dir)
            (str tool " source root " root " exists in this checkout"))
        (is (.isFile src)
            (str tool " coordinate " file " exists under " root))))))

(deftest real-relative-testbed-coords-resolve-through-the-endpoint
  (testing "a real relative Story coordinate and a real relative Xray
            coordinate each reach the handler and resolve to the intended
            existing file — with no project-root anywhere in the request, the
            client, or this server. This is the endpoint capability the
            retired checkout-root pipeline duplicated"
    (with-testbed-source-roots*
      (fn []
        (doseq [{:keys [tool root file]} consumer-coords]
          (let [expected (io/file @repo-root root file)
                calls    (atom [])]
            (with-launch-spy calls
              (let [resp (oies/handle
                           {:uri            oies/endpoint-path
                            :request-method :post
                            :query-string   (str "file=" file "&line=12&column=3")
                            :headers        {"host" "localhost:8042"}})]
                (is (= 200 (:status resp))
                    (str tool " relative coordinate was accepted"))
                (is (= 1 (count @calls))
                    (str tool " reached launch! exactly once"))
                (let [[abs-path line column _cmd] (first @calls)]
                  (is (= (.getCanonicalPath expected)
                         (.getCanonicalPath (File. ^String abs-path)))
                      (str tool " resolved to the REAL on-disk source file"))
                  (is (= 12 line) (str tool " kept its line"))
                  (is (= 3 column) (str tool " kept its column")))))))))))

(deftest off-endpoint-request-404s-and-the-uri-fallback-stays-relative
  (testing "the NEGATIVE half, stated as what it actually executes. A
            `:dev-http` entry with no re-frame2 handler serves static files
            only, so an off-endpoint POST never reaches this namespace's
            resolution at all: `handle` falls through and `handler` answers
            shadow's own 404 — a non-2xx, which is exactly what sends the
            browser to its `editor://` URI fallback (pinned client-side in
            `re-frame.testbed.open-in-editor-client-cljs-test`). That
            fallback composes the RAW coordinate, which is relative, so an OS
            editor handler cannot stat it.

            No source roots are installed here, deliberately: every
            expression below is either an early fall-through or a pure
            string/path-shape check, so a context classloader could not
            change a single answer. The positive witness above is where the
            real roots earn their keep"
    (doseq [{:keys [tool file]} consumer-coords]
      ;; An unwired port never reaches this namespace at all.
      (is (nil? (oies/handle {:uri            "/index.html"
                              :request-method :post
                              :query-string   (str "file=" file)
                              :headers        {"host" "localhost:8042"}}))
          (str tool ": a request that is not the endpoint path is not
               handled here"))
      (is (= 404 (:status (oies/handler {:uri            "/index.html"
                                         :request-method :post
                                         :query-string   (str "file=" file)
                                         :headers        {"host" "localhost:8042"}})))
          (str tool ": the non-endpoint answer is a non-2xx, so the client
               falls back"))
      ;; …and the fallback's own input is still relative.
      (is (not (editor-uri/absolute-path? file))
          (str tool " coordinate is relative — the URI fallback alone
               cannot reach the file"))
      (is (= file (#'editor-uri/compose-path nil file))
          (str tool " composes to itself with no project-root — the
               composition step the retired pipeline used to feed")))))

;; ---------------------------------------------------------------------------
;; rf2-1i1ec (audit) — auto-detect is a capability question too
;; ---------------------------------------------------------------------------
;;
;; The declared-vocabulary decline above closes `editor=windsurf`. It cannot
;; close the path where no `editor` is sent at all — which the client takes
;; for a nil preference AND for `{:custom …}` — because launch-editor then
;; picks the binary itself, from the running process list. Its registries
;; reach editors `get-args.js` has no case for, so that route could still
;; launch a bare file, exit 0, answer 200, and suppress the coordinate-
;; preserving URI fallback.
;;
;; `launch-shim` therefore asks the dependency rather than predicting it. The
;; tests below pin that in three places: the dependency really does behave
;; this way (a probe of the installed package, which also guards the declared
;; set against drift), the shim really does decline it (real node children,
;; none of which can open an editor), and a launch-time decline really does
;; reach the client as the same 422 the declared route emits.

(def ^:private implementation-dir
  "The directory `shadow-cljs watch` runs the dev server from — and the only
  one from which `require('launch-editor')` resolves, since `node -e` walks
  up from the working directory."
  (delay (io/file @repo-root "implementation")))

(defn ^:private launch-editor-installed?
  "Whether the pinned `launch-editor` package is present in this checkout.
  A checkout that never ran `npm ci` self-skips rather than failing red —
  unless `RF2_REQUIRE_NODE_PROBES` is set, which is how a lane that DID
  install it refuses to pass on the skip (`skip-or-fail`, rf2-cl8mg)."
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
  line. `F` is a sentinel filename: `get-args.js` interpolates the position
  into every case it encodes and falls through to `return [fileName]` for the
  rest, so an argv of the sentinel ALONE is exactly the documented drop."
  (str "var g=require('launch-editor/guess');"
       "var a=require('launch-editor/get-args');"
       "function say(k,v){process.stdout.write(k+'\\t'+JSON.stringify(v)+'\\n');}"
       "['code','code-insiders','cursor','zed','idea','windsurf'].forEach("
       "function(c){say(c,a(c,'F',27,9));});"
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
          (is (= 11 (count probe)) "every probed key came back")
          (is (contains? probe "windsurf")))

        (testing "every command this endpoint DECLARES position-blind really is
                  — and no more. This is the drift guard: a launch-editor
                  release that learns one of these makes it red, which is the
                  signal to drop the entry from the set"
          (doseq [cmd oies/commands-without-position-support]
            (is (= "[\"F\"]" (get probe cmd))
                (str cmd " is invoked with the bare file — the coordinate is
                     dropped inside the dependency"))))

        (testing "every OTHER command in the vocabulary carries the position,
                  so the decline is as narrow as the invariant allows"
          (doseq [cmd (remove oies/commands-without-position-support
                              (vals oies/editor-command-by-keyword))]
            (let [argv (get probe cmd)]
              (is (some? argv) (str cmd " was probed"))
              (is (not= "[\"F\"]" argv)
                  (str cmd " is not a bare-file launch"))
              (is (str/includes? argv "27")
                  (str cmd " argv carries the requested line")))))

        (testing "AUTO-DETECT reaches position-blind binaries the declared set
                  cannot name — the audit's finding. Brackets is in all three
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
      (let [f (.getAbsolutePath (tmp-existing-file))]
        (with-dev-cwd*
          (fn []
            (testing "a position-blind command the endpoint never names — the
                      class auto-detect reaches"
              (is (= {:ok false :message oies/position-unsupported-error}
                     (oies/launch! f 27 9 "nonexistent-dir/Brackets"))))

            (testing "windsurf reaches the same verdict here too, so the
                      handler's pre-spawn fast path is an optimisation and not
                      the only thing standing between it and a false 200"
              (is (= {:ok false :message oies/position-unsupported-error}
                     (oies/launch! f 27 9 "windsurf"))))

            (testing "POSITIVE CONTROL — a position-CARRYING command is passed
                      through to a real launch attempt. It still fails, because
                      the binary does not exist, but NOT as the decline: the
                      probe is discriminating, not refusing everything"
              (let [{:keys [ok message]} (oies/launch! f 27 9 "nonexistent-dir/zed")]
                (is (false? ok) "the nonexistent binary could not be launched")
                (is (not= oies/position-unsupported-error message)
                    "…and it was a launch failure, not a capability refusal")))

            (testing "a coordinate-FREE launch is never refused: there is no
                      position to lose, so even a position-blind command
                      reaches the launcher"
              (let [{:keys [ok message]} (oies/launch! f nil nil "nonexistent-dir/Brackets")]
                (is (false? ok))
                (is (not= oies/position-unsupported-error message)
                    "the empty coordinate argv tokens read as absent")))

            ;; A COLUMN with no line is the coordinate shape every probe above
            ;; misses: they all pass 27 AND 9. `build-file-spec` normalises it
            ;; to `path:1:<column>`, and `position-would-be-dropped?` already
            ;; calls it a coordinate — but the shim gated its probe on the
            ;; LINE argv token alone, which is empty here, so the whole
            ;; capability check was skipped and a position-blind binary could
            ;; strip `:1:7`, exit 0 and win a 200 (rf2-1i1ec audit).
            (testing "COLUMN-ONLY, position-blind: refused on the same terms
                      as a line-bearing launch. The argv line token is empty,
                      so this is precisely the request the old `if(line)` gate
                      waved through"
              (is (= {:ok false :message oies/position-unsupported-error}
                     (oies/launch! f nil 7 "nonexistent-dir/Brackets"))
                  "a column alone is a coordinate the launcher can lose"))

            (testing "COLUMN-ONLY POSITIVE CONTROL — the same column-only
                      request to a position-CAPABLE command still reaches a
                      real launch attempt. It fails (the binary does not
                      exist) but NOT as the decline, so widening the gate did
                      not turn column-only into a blanket refusal"
              (let [{:keys [ok message]} (oies/launch! f nil 7 "nonexistent-dir/zed")]
                (is (false? ok) "the nonexistent binary could not be launched")
                (is (not= oies/position-unsupported-error message)
                    "…and it was a launch failure, not a capability refusal")))))))))

(deftest endpoint-turns-a-launch-time-decline-into-the-same-422
  (testing "the wiring that makes the shim's refusal user-visible: a
            coordinate-bearing request with NO editor param — what the client
            sends for a nil preference and for {:custom …} — answers 422
            `editor-position-unsupported` when the launch declines. Same
            status and same token as the declared-vocabulary route, so the
            browser has one contract to honour rather than two.

            `launch!` is stubbed with the verdict the previous test proves the
            real shim returns; what is under test here is the mapping"
    (with-redefs [oies/launch! (fn [& _]
                                 {:ok false
                                  :message oies/position-unsupported-error})]
      (let [resp (oies/handle
                   {:uri            oies/endpoint-path
                    :request-method :post
                    :query-string   "file=fake_ns/core.cljs&line=27&column=9"
                    :headers        {"host" "localhost:8031"}})]
        (is (= 422 (:status resp))
            "a 200 here would claim 27:9 reached an editor that never got it")
        (is (not (<= 200 (:status resp) 299))
            "non-2xx is what runs the client's coordinate-preserving fallback")
        (is (re-find #"\"error\":\"editor-position-unsupported\"" (:body resp))
            "the same token the declared-vocabulary decline emits"))))

  (testing "the same mapping for a COLUMN-ONLY request, which is the shape
            that used to bypass the shim's probe altogether: `column=7` with
            no `line` and no `editor`. The coordinate at stake is the
            normalised 1:7 the endpoint would have handed the launcher, and
            the client's URI fallback is what carries it"
    (is (= "/abs/src/app.cljs:1:7"
           (#'oies/build-file-spec "/abs/src/app.cljs" nil 7))
        "column-only normalises to line 1 — a real coordinate, not an absent
         one, which is why the shim must probe for it")
    (with-redefs [oies/launch! (fn [& _]
                                 {:ok false
                                  :message oies/position-unsupported-error})]
      (let [resp (oies/handle
                   {:uri            oies/endpoint-path
                    :request-method :post
                    :query-string   "file=fake_ns/core.cljs&column=7"
                    :headers        {"host" "localhost:8031"}})]
        (is (= 422 (:status resp))
            "a 200 here would claim 1:7 reached an editor that never got it")
        (is (re-find #"\"error\":\"editor-position-unsupported\"" (:body resp))
            "one contract for the client, whatever shape the coordinate had")))))
