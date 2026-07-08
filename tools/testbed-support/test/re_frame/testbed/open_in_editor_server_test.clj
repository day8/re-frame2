(ns re-frame.testbed.open-in-editor-server-test
  "JVM regression tests for the dev-only open-in-editor endpoint's
  `:file` resolution path.

  The endpoint is a JVM-only `.clj` (it runs on the shadow-cljs SERVER
  JVM), so it cannot be exercised by the node CLJS suites — this is its
  `clojure -M:test` gate.

  The load-bearing regression: a classpath checkout path containing a
  literal `+` (e.g. `C:/code/re-frame2+wip`) must survive `:file`
  resolution verbatim. Decoding the `file:` resource URL with `URLDecoder`
  is wrong here — it is a FORM-body decoder that maps `+` → space,
  so such a path would resolve to a nonexistent `re-frame2 wip` dir and the
  endpoint would launch the editor at the wrong place. `file-url->path`
  decodes via `URI` instead, which leaves a literal `+` intact while still
  decoding `%20` / `%2B`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.testbed.open-in-editor-server :as oies])
  (:import [java.net URL URLClassLoader]
           [java.io File]))

;; ---- file-url->path: the decode contract ---------------------------------

(deftest file-url->path-preserves-literal-plus
  (testing "a `file:` URL with a LITERAL `+` in the path keeps the `+`
            (it is NOT form-decoded to a space — the URLDecoder bug)"
    (is (= "/home/dev/re-frame2+wip/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/home/dev/re-frame2+wip/core.cljs")))
        "literal + survives verbatim, never becomes a space")))

(deftest file-url->path-decodes-percent-escapes
  (testing "percent-escapes still decode: `%20` → space, `%2B` → +"
    (is (= "/home/dev/my project/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/home/dev/my%20project/core.cljs")))
        "%20 decodes to a real space")
    (is (= "/home/dev/re-frame2+wip/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/home/dev/re-frame2%2Bwip/core.cljs")))
        "%2B (the encoded plus) decodes to a literal +")))

(deftest file-url->path-strips-windows-drive-leading-slash
  (testing "a Windows `file:` URL comes out as `/C:/...`; the leading
            slash before the drive letter is stripped to the canonical
            `C:/...` shape"
    (is (= "C:/code/re-frame2+wip/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/C:/code/re-frame2+wip/core.cljs")))
        "leading slash stripped AND the literal + preserved (the exact
         cross-of-the-two-bugs the Windows author hits)")
    (is (= "C:/Users/me/my project/core.cljs"
           (#'oies/file-url->path
            (URL. "file:/C:/Users/me/my%20project/core.cljs")))
        "Windows drive shape with an encoded space round-trips")))

;; ---- resolve-file: end-to-end over a real `+`-bearing classpath ----------

(deftest resolve-file-resolves-classpath-path-with-plus
  (testing "resolve-file resolves a classpath-relative `:file` to its
            on-disk absolute path when the classpath root directory itself
            contains a literal `+` — the path is returned verbatim, not
            corrupted to a space-bearing nonexistent path"
    ;; Build a throwaway classpath root dir whose name carries a `+`, drop
    ;; a fake source file under it, push a class-loader rooted there onto
    ;; the context, and confirm resolve-file finds the real on-disk file.
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
              (is (.contains ^String resolved "+")
                  "the literal + in the classpath root survived resolution")
              (is (= (.getCanonicalPath src-file)
                     (.getCanonicalPath (File. ^String resolved)))
                  "resolved to the REAL on-disk fixture file, not a
                   space-corrupted sibling that does not exist"))
            (finally
              (.setContextClassLoader (Thread/currentThread) prev))))
        (finally
          ;; Best-effort cleanup of the throwaway tree.
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

;; ---- absolute / blank pass-through contract ------------------------------

(deftest resolve-file-passes-absolute-and-blank-through
  (testing "an already-absolute path is returned unchanged (incl. a + in it)"
    (is (= "/abs/re-frame2+wip/core.cljs"
           (oies/resolve-file "/abs/re-frame2+wip/core.cljs"))))
  (testing "nil / blank resolve to nil"
    (is (nil? (oies/resolve-file nil)))
    (is (nil? (oies/resolve-file "")))
    (is (nil? (oies/resolve-file "   ")))))

;; ---- security: the loopback / origin / method guard ----------------------
;;
;; The endpoint launches the editor on a local file path, so it must NOT be
;; drivable by a drive-by GET or a cross-origin POST from a remote page. The
;; guard pins it to a POST addressed to a loopback Host with (when present)
;; a loopback Origin. These tests redirect `launch!` to a recording stub so
;; no real editor is spawned, and assert: the one valid local POST reaches
;; the launch path and answers 200, while every unauthenticated / wrong-
;; method / cross-origin / non-loopback variant is rejected (403/405) BEFORE
;; `launch!` is ever called.

(defn ^:private req
  "Build a minimal Ring request map for the endpoint. An explicit nil `host`
  / `origin` omits that header (an explicit nil is respected over the :or
  default, which only fills an ABSENT key)."
  [{:keys [method host origin file]
    :or   {method :post host "localhost:8031"}}]
  {:uri            oies/endpoint-path
   :request-method method
   :query-string   (when file (str "file=" file "&line=10"))
   :headers        (cond-> {}
                     host   (assoc "host" host)
                     origin (assoc "origin" origin))})

(defmacro ^:private with-launch-spy
  "Run `body` with `launch!` redirected to a stub that records each call in
  `calls` (an atom holding a vector) and returns `{:ok true}`."
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
          (is (zero? (count @calls)) "launch! was NEVER called")
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
          (is (zero? (count @calls)) "launch! was NEVER called"))))))

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

;; ---- security negatives: the load-bearing rejection branches (rf2-jwdi4r) --
;;
;; The two adversarial paths the existing guard suite did NOT drive
;; end-to-end. Both are safety branches — a false negative here re-opens the
;; drive-by editor-launch vector — so each asserts the request is rejected
;; AND `launch!` is never reached.

(deftest guard-rejects-opaque-null-origin-post
  (testing "rf2-jwdi4r: a POST carrying the opaque `Origin: null` (a
            sandboxed iframe / `file:` page drive-by — the very context
            `origin-host` documents as yielding nil) is rejected 403 and
            NEVER reaches launch!. The Host is loopback, so this isolates the
            ORIGIN gate: `origin-host` maps \"null\" → nil, which is not a
            loopback origin, so `local-request?` fails and the launch path is
            never touched. (origin-host-extracts-and-rejects-opaque only
            proves the \"null\" → nil unit step; this proves the POST is
            actually refused end-to-end.)"
    (let [calls (atom [])]
      (with-launch-spy calls
        (let [resp (oies/handle
                     (req {:method :post
                           :host   "localhost:8031"
                           :origin "null"
                           :file   "/etc/passwd"}))]
          (is (= 403 (:status resp)) "opaque-origin POST is forbidden")
          (is (re-find #"\"error\":\"forbidden\"" (:body resp)))
          (is (zero? (count @calls)) "launch! was NEVER called")
          (is (= "null" (get-in resp [:headers "access-control-allow-origin"]))
              "CORS denies via `null` — never reflects the opaque origin, never `*`")
          (is (not= "*" (get-in resp [:headers "access-control-allow-origin"]))))))))

(deftest guard-options-preflight-denies-remote-origin
  (testing "rf2-jwdi4r: an OPTIONS preflight is answered BEFORE the
            `local-request?` guard (it is the first `cond` branch in
            `handle`), so `allow-origin` is the SOLE gate on preflight CORS.
            A preflight from a REMOTE origin must therefore get
            `access-control-allow-origin: null` (deny) — never `*` and never
            the reflected remote origin — so the browser blocks the follow-up
            cross-origin POST. launch! is never touched. (The existing
            preflight test only covers the loopback-origin ALLOW case.)"
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
          (is (zero? (count @calls)) "launch! was NEVER called")))))
  (testing "rf2-jwdi4r: an OPTIONS preflight from a non-loopback Host with no
            Origin likewise denies (ao=null) — the allow-origin gate never
            emits a usable CORS header for anything but a validated loopback
            origin"
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

;; ---- malformed query string: clean 400, never an uncaught throw ----------
;;
;; `parse-query` URL-decodes every key/value; a malformed percent-escape (a
;; lone `%`, or `%` not followed by two hex digits) makes `URLDecoder/decode`
;; throw `IllegalArgumentException`. Every other error path on this endpoint
;; (missing file, forbidden, method-not-allowed, launch failure) answers a
;; clean JSON response — a malformed query must too, rather than propagating
;; uncaught into the shadow-cljs `:dev-http` Ring plumbing (rf2-bhejni).

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

;; ---- parse-query: literal `+` survives (rf2-62hu6k) ----------------------
;;
;; `parse-query` decodes with decodeURIComponent semantics (via
;; `decode-component`'s `URI` fragment decode), NOT `URLDecoder/decode`'s
;; application/x-www-form-urlencoded semantics that map a literal `+` to a
;; space. This is the SAME contract `file-url->path` upholds for `file:` URLs
;; and `config.cljs`'s `js/decodeURIComponent` upholds on the client — so a
;; checkout path carrying a literal `+` (`C:/code/re-frame2+wip`) survives
;; verbatim through the query rather than being corrupted to `re-frame2 wip`.

(deftest parse-query-preserves-literal-plus
  (testing "a literal `+` in a value is preserved, NOT form-decoded to a space
            (the URLDecoder bug — rf2-62hu6k)"
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
            with the `+` intact (never corrupted to a space — rf2-62hu6k)"
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

;; ---- header / origin parsing helpers -------------------------------------

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
    ;; not in 127.0.0.0/8 despite the `127` prefix textually
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

;; ---- json-resp: control-char escaping (rf2-2loouf finding 5) -------------
;;
;; The hand-rolled encoder previously escaped only `\` and `"`. Both the
;; 200 response (echoes the url-decoded `:file` verbatim) and the 422
;; response (echoes trimmed `node` stderr, which can be a multi-line stack
;; trace) can carry a raw control character — a `file=...%0A...` request or
;; a multi-line launch-failure message. A real JSON reader (`clojure.edn`
;; cannot parse JSON, so this test hand-rolls a minimal JSON-string reader
;; over the escaped body) must round-trip the exact original value.

(defn ^:private read-json-string-literal
  "Minimal JSON-string-literal reader: given `s` positioned so `s`'s first
  char is the opening `\"`, decode the escaped literal and return
  `[decoded-value chars-consumed]`. Only used to independently verify
  `json-resp`'s output is valid JSON (no reliance on the encoder under
  test, or on a JSON library dependency this JVM-only tool artefact does
  not otherwise carry)."
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
  "Pull the decoded `\"file\"` string value out of a `json-resp` body by
  locating the `\"file\":\"` key and decoding the quoted literal that
  follows with `read-json-string-literal`. Throws if the body around it is
  not well-formed JSON (a bare unescaped control char inside the literal
  makes this parse fail exactly as a real JSON.parse would)."
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

;; ---- build-file-spec: column-only normalization (rf2-bj02en) --------------
;;
;; `build-file-spec` is the pure argv-token builder `launch!` delegates to
;; before shelling out to `node`. History: `column` was once nested inside
;; `(when line ...)`, which silently dropped a column-without-line request
;; (rf2-2loouf finding 6). The independent `cond->` fix stopped the drop but
;; encoded a column-only request as `path:<column>` — and `launch-editor`'s
;; `file:line:column` grammar reads that lone number as the LINE, so the
;; column was misread as a line jump (rf2-bj02en). `build-file-spec` now
;; NORMALIZES a column-only request to line 1, emitting `path:1:<column>` —
;; matching the `editor://` URI fallback (`editor-uri/coord-line` defaults a
;; missing line to 1) so both open paths land on the same file:line:column.

(deftest build-file-spec-normalizes-column-only-to-line-1
  (testing "column with no line is normalized to line 1, NOT encoded as a
            bare `path:<column>` that launch-editor would misread as a line
            (the rf2-bj02en regression)"
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
                "build-file-spec normalizes column-only to line 1 —
                 the rf2-bj02en regression, now fixed")))))))

;; ---- missing-file guard: no false 200 (rf2-i877cj) -----------------------
;;
;; `launch-editor` SILENTLY no-ops on a nonexistent file — its internal
;; `if (!fs.existsSync(fileName)) return` returns WITHOUT calling the error
;; callback, so the node process exits 0 and `launch!` would report a false
;; `{:ok true}`, making the endpoint answer HTTP 200 for an unresolved /
;; nonexistent `:file`. That suppresses the client's `editor://` fallback
;; (the client only falls back on a non-2xx). `launch!` now checks the
;; resolved path's existence BEFORE spawning node and short-circuits to
;; `{:ok false :message "file-not-found"}`, which the endpoint answers 422.

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

;; ---- missing-file 400: the blank/absent `file` param branch (rf2-bnv3cu) --
;;
;; `handle` returns 400 `{:ok false :error "missing-file"}` when the `file`
;; query param is blank or absent (before any resolution / launch). The
;; suite covered malformed-query 400 and file-not-found 422 but never this
;; distinct earlier branch.

(deftest endpoint-missing-file-param-returns-400
  (testing "rf2-bnv3cu: a valid local POST with a blank / absent `file`
            param answers a clean 400 missing-file and never reaches launch!"
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

;; ---- editor hint: keyword -> launch-editor command (rf2-bnv3cu) -----------
;;
;; `editor-hint` + `editor-command-by-keyword` are PUBLIC and were 100%
;; untested. The `editor` query param maps through `editor-hint` to
;; `launch!`'s 4th-arg command hint; an untested map means a regression
;; could silently drop a configured editor (`:rf.xray/editor :cursor`) back
;; to launch-editor's auto-detect.

(deftest editor-hint-maps-keyword-to-launch-command
  (testing "rf2-bnv3cu: a known editor keyword (bare string) resolves to
            launch-editor's launch command"
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
  (testing "rf2-bnv3cu: the public keyword→command map maps the open-in-editor
            keyword vocabulary to launch-editor bin names (a public-surface
            sentinel — a new editor is a deliberate, reviewed addition)"
    (is (= {"vscode"          "code"
            "vscode-insiders" "code-insiders"
            "cursor"          "cursor"
            "windsurf"        "windsurf"
            "zed"             "zed"
            "idea"            "idea"}
           oies/editor-command-by-keyword))))

(deftest endpoint-passes-editor-hint-through-to-launch
  (testing "rf2-bnv3cu: the `editor` query param maps through editor-hint to
            launch!'s 4th-arg command hint — a request for a configured editor
            reaches launch! with the resolved command, not auto-detect"
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

;; ---- escape-json-string: backslash + doublequote (rf2-bnv3cu) -------------
;;
;; The control-char round-trip test covered \n/\t/\r + C0 + plain ASCII but
;; never a value carrying a literal `\` or `"`. On Windows the resolved
;; abs-path echoed in the 200 `:file` field is `C:\Users\...`, so the
;; backslash rule is Windows-load-bearing: unescaped, the JSON body is
;; invalid despite the `application/json` header.

(deftest escape-json-string-escapes-backslash-and-doublequote
  (testing "rf2-bnv3cu: a lone backslash and a double-quote are escaped
            directly"
    (is (= "a\\\\b" (#'oies/escape-json-string "a\\b"))
        "one backslash → two")
    (is (= "say \\\"hi\\\"" (#'oies/escape-json-string "say \"hi\""))
        "double-quotes are escaped")
    (is (= "C:\\\\Users\\\\me\\\\core.cljs"
           (#'oies/escape-json-string "C:\\Users\\me\\core.cljs"))
        "a Windows abs-path's backslashes are all doubled")))

(deftest json-resp-escapes-windows-backslash-path
  (testing "rf2-bnv3cu: a 200 response whose resolved `:file` is a Windows
            abs-path (literal `\\` separators) plus a stray `\"` is escaped so
            the `application/json` body is valid and round-trips to the exact
            path through a real JSON-string decode — the Windows-relevant path
            the prior backslash/quote-only encoder would still have handled,
            but never had a regression test"
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

;; ---- resolve-file: the cwd-relative branch (rf2-bnv3cu) -------------------
;;
;; resolve-file's branch 2 (relative to the dev process cwd) is the
;; JAR / off-classpath case Option B exists to close — a relative coord that
;; getResource cannot reach on the classpath still resolves against the dev
;; JVM's working directory. Branch 1 (classpath) is covered by
;; resolve-file-resolves-classpath-path-with-plus; this pins branch 2's
;; SUCCESS path directly.

(deftest resolve-file-resolves-cwd-relative-off-classpath
  (testing "rf2-bnv3cu: a relative `:file` that is NOT on the classpath
            resolves against the working directory (`user.dir`) to its real
            on-disk absolute path — the off-classpath branch"
    (let [tmp      (File. (System/getProperty "java.io.tmpdir")
                          (str "oies-cwd-" (System/nanoTime)))
          sub      (str "off_classpath_" (System/nanoTime))
          rel-path (str sub "/probe.cljs")
          src-file (io/file tmp sub "probe.cljs")
          prev-cwd (System/getProperty "user.dir")]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        ;; Point the JVM working dir at the throwaway tree; the explicit
        ;; cwd branch reads `user.dir` live, so resolution finds the fixture.
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
