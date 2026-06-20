(ns re-frame2-pair-mcp.shadow-discovery-test
  "Unit tests for the shadow-cljs HTTP probe.

  Two surfaces under test:

    - `extract-project-home` — pure transit-json string → string|nil.
      Driven directly from synthetic JSON bodies; no HTTP.
    - `discover-project-home` — wraps `fetch-project-info`. We swap the
      module-level `fetch-project-info` for a stub via the
      `with-redefs` macro so the cascade observes the three outcomes
      (success / non-200 / connection refused / parse failure) without
      a real socket.

  No test in this file talks to a live shadow server — the live probe
  is exercised by hand against `http://localhost:9630/api/project-info`
  during development and by the integration testbed at boot. CI runs
  fully offline."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.shadow-discovery :as sd]))

;; ===========================================================================
;; extract-project-home — transit-json parser.
;; ===========================================================================

(deftest extract-project-home-pulls-canonical-payload
  (testing "the live shadow /api/project-info shape returns :project-home"
    ;; Verbatim from `curl http://localhost:9630/api/project-info` on a
    ;; running shadow-cljs instance — the contract under test.
    (let [body (str "[\"^ \","
                    "\"~:project-config\",\"C:\\\\Users\\\\me\\\\proj\\\\shadow-cljs.edn\","
                    "\"~:project-home\",\"C:\\\\Users\\\\me\\\\proj\","
                    "\"~:version\",\"3.4.10\"]")]
      (is (= "C:\\Users\\me\\proj" (sd/extract-project-home body))))))

(deftest extract-project-home-handles-posix-path
  (let [body (str "[\"^ \","
                  "\"~:project-config\",\"/home/me/proj/shadow-cljs.edn\","
                  "\"~:project-home\",\"/home/me/proj\","
                  "\"~:version\",\"3.4.10\"]")]
    (is (= "/home/me/proj" (sd/extract-project-home body)))))

(deftest extract-project-home-tolerates-key-reordering
  (testing "the parser walks key/value pairs — key order isn't load-bearing"
    (let [body (str "[\"^ \","
                    "\"~:version\",\"3.4.10\","
                    "\"~:project-home\",\"/x/y\","
                    "\"~:project-config\",\"/x/y/shadow-cljs.edn\"]")]
      (is (= "/x/y" (sd/extract-project-home body))))))

(deftest extract-project-home-missing-key-returns-nil
  (testing "a payload without :project-home returns nil, not throw"
    (let [body (str "[\"^ \","
                    "\"~:project-config\",\"/x/y/shadow-cljs.edn\","
                    "\"~:version\",\"3.4.10\"]")]
      (is (nil? (sd/extract-project-home body))))))

(deftest extract-project-home-non-string-value-returns-nil
  (testing "an integer / null at :project-home is rejected"
    (is (nil? (sd/extract-project-home
                "[\"^ \",\"~:project-home\",42]")))
    (is (nil? (sd/extract-project-home
                "[\"^ \",\"~:project-home\",null]")))))

(deftest extract-project-home-non-map-payload-returns-nil
  (testing "non-transit-map shapes (a bare object, a bare array, a string) → nil"
    (is (nil? (sd/extract-project-home "{\"project-home\":\"/x\"}"))
        "vanilla JSON object isn't the transit-map-as-array shape")
    (is (nil? (sd/extract-project-home "[1,2,3]"))
        "array without the \"^ \" sentinel isn't a transit map")
    (is (nil? (sd/extract-project-home "\"hello\""))
        "string body — no map shape at all")))

(deftest extract-project-home-malformed-json-returns-nil
  (testing "JSON parse failure must not throw — every error path collapses to nil"
    (is (nil? (sd/extract-project-home "not-json-at-all")))
    (is (nil? (sd/extract-project-home "")))
    (is (nil? (sd/extract-project-home "[\"^ \", \"~:project-home\""))
        "truncated array")))

;; ===========================================================================
;; discover-project-home* — fetch + parse composition.
;;
;; The HTTP-fetch fn is injected (see `discover-project-home*`); tests
;; pass stubs. The fn under test never opens a socket in these tests;
;; the real HTTP path is covered manually via the live-nrepl integration
;; test and by the boot smoke (start the server with shadow up, see
;; "nREPL port =" log line).
;; ===========================================================================

(deftest discover-project-home-success-returns-path
  (async done
    (let [stub-fetch (fn [_host _port]
                       (js/Promise.resolve
                         "[\"^ \",\"~:project-home\",\"/abs/proj\"]"))]
      (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
          (.then (fn [v]
                   (is (= "/abs/proj" v))
                   (done)))))))

(deftest discover-project-home-fetch-rejection-yields-nil
  (testing "shadow unreachable / non-200 / timeout — every reject path → nil"
    (async done
      (let [stub-fetch (fn [_host _port]
                         (js/Promise.reject (js/Error. "ECONNREFUSED")))]
        (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
            (.then (fn [v]
                     (is (nil? v)
                         "rejection must surface as nil so the cascade falls through")
                     (done))))))))

(deftest discover-project-home-malformed-payload-yields-nil
  (testing "fetch succeeded but the body wasn't the transit-map shape"
    (async done
      (let [stub-fetch (fn [_host _port]
                         (js/Promise.resolve "not-the-shape-we-want"))]
        (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
            (.then (fn [v]
                     (is (nil? v)
                         "extract-project-home returned nil; cascade falls through")
                     (done))))))))

(deftest discover-project-home-missing-key-yields-nil
  (testing "fetch succeeded, transit-shape valid, but :project-home absent"
    (async done
      (let [stub-fetch (fn [_host _port]
                         (js/Promise.resolve
                           "[\"^ \",\"~:version\",\"3.4.10\"]"))]
        (-> (sd/discover-project-home* "127.0.0.1" 9630 stub-fetch)
            (.then (fn [v]
                     (is (nil? v))
                     (done))))))))

(deftest discover-project-home-args-thread-through
  (testing "host + port supplied to the wrapper reach the fetch-fn"
    (async done
      (let [seen-host (atom nil)
            seen-port (atom nil)
            stub-fetch (fn [host port]
                         (reset! seen-host host)
                         (reset! seen-port port)
                         (js/Promise.resolve
                           "[\"^ \",\"~:project-home\",\"/x\"]"))]
        (-> (sd/discover-project-home* "10.0.0.5" 9700 stub-fetch)
            (.then (fn [_]
                     (is (= "10.0.0.5" @seen-host))
                     (is (= 9700 @seen-port))
                     (done))))))))
