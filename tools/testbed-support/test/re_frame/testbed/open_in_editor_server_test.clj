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

(deftest guard-non-endpoint-path-falls-through
  (testing "a request for any other path still falls through (nil) untouched"
    (is (nil? (oies/handle {:uri "/something/else"
                            :request-method :post
                            :headers {"host" "localhost:8031"}})))))

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
