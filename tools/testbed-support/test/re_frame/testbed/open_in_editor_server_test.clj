(ns re-frame.testbed.open-in-editor-server-test
  "JVM tests for the dev-only open-in-editor endpoint.

  Core owns classpath URL decoding. This suite verifies that the endpoint
  delegates there, adds cwd fallback, guards the launch boundary, and emits
  valid responses."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.source-coords :as source-coords]
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
