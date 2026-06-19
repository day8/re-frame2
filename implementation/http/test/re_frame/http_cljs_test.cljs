(ns re-frame.http-cljs-test
  "CLJS-side smoke for the `re-frame.http` call-site helpers (rf2-pf4k).

  The JVM `re-frame.http-test` covers the full shape contract. This
  smoke just confirms the helpers compile clean under CLJS (the file
  is `.cljc` with no host-specific bits, but a CLJS-side load is the
  fastest way to catch a regression that would otherwise only surface
  in shadow-cljs builds).

  Also covers rf2-r40km — the CLJS-only `:rf.http/cors` classification
  branch of `re-frame.http-transport-cljs/classify-cljs-error`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.http :as rf.http]
            ;; rf2-wj8vv — drive the full `:rf.http/managed` pipeline (fx
            ;; registration + in-flight registry) for the backoff-window
            ;; cancellation test below.
            [re-frame.http-managed :as http-managed]
            [re-frame.http-registry :as registry]
            [re-frame.http-transport-cljs :as transport-cljs]
            ;; rf2-f5pguu — assert the redacted `:rf.warning/http-header-invalid`
            ;; trace fires (instead of an escaping `:rf.error/fx-handler-
            ;; exception`) when an invalid request header hits the managed
            ;; CLJS path. `re-frame.trace.tooling` owns the listener surface
            ;; (rf2-qwm0a); `re-frame.http-url` carries the canonical redaction
            ;; sentinel for the denylist-scrub assertion (rf2-m00e7p).
            [re-frame.http-url :as http-url]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

;; rf2-hp772l — the CLJS Fetch transport + classifier now live in the
;; per-platform adapter ns `re-frame.http-transport-cljs` and are public
;; (named seams), so no `@#'` reach-through is needed.
(def ^:private classify-cljs-error
  transport-cljs/classify-cljs-error)

;; rf2-xu0sl — tolerance band for the MEASURED `:elapsed-ms` wall-clock
;; assertions below. `js/setTimeout(…, limit)` is NOT a hard floor: under
;; rounding and CI scheduling jitter a 40ms timer can be observed firing at
;; a measured ~39ms, so an exact `(>= elapsed limit)` flakes by ~1ms. We
;; assert `elapsed` lands within this band of `limit` instead, which still
;; proves `:elapsed-ms` is a genuine measured value (not the old synthetic
;; constant `== :limit-ms`, and not absent) without being jitter-fragile.
(def ^:private elapsed-jitter-ms 10)

;; rf2-5zj6t — the CLJS Fetch transport (`transport-cljs/cljs-fetch`,
;; now a public named seam) so we can assert it reads the correct
;; response body type per `:decode` (a Fetch Response body may be
;; consumed only once, so the reader is chosen up front).
(def ^:private cljs-fetch
  transport-cljs/cljs-fetch)

;; rf2-u5xwa — `cross-origin?` (the load-bearing half of the CORS
;; classification) reads `js/globalThis.location.origin`, which is ABSENT
;; under the node-runtime CLJS gate (`npm run test:cljs`). Inject a known
;; origin so the cross-origin heuristic runs deterministically in node and
;; the positive `:rf.http/cors` branch — plus the same-origin /
;; scheme-excluded negative branches — are actually exercised rather than
;; silently no-op'd. Mirrors the `with-stub-fetch` set!/restore idiom; this
;; variant is synchronous (the classifier is pure, no Promise) and uses
;; try/finally so the global is restored even if an assertion throws.
(defn- with-stub-location
  "Run `f` with `js/globalThis.location` stubbed to `{origin <origin>}`,
  restoring the original (typically `js/undefined` in node) afterwards.
  Returns whatever `f` returns."
  [origin f]
  (let [orig (aget js/globalThis "location")]
    (aset js/globalThis "location" #js {:origin origin})
    (try
      (f)
      (finally
        (aset js/globalThis "location" orig)))))

(deftest get-helper-shape
  (testing "(rf.http/get url) produces [:rf.http/managed {:request {:method :get :url url}}]"
    (is (= [:rf.http/managed
            {:request {:method :get :url "/api/items"}}]
           (rf.http/get "/api/items")))))

(deftest post-helper-shape
  (testing "(rf.http/post url args) merges :request body with helper's verb + url"
    (is (= [:rf.http/managed
            {:request    {:method :post
                          :url    "/api/items"
                          :body   {:title "new"}
                          :request-content-type :json}
             :on-success [:item/created]}]
           (rf.http/post "/api/items"
                         {:request    {:body {:title "new"}
                                       :request-content-type :json}
                          :on-success [:item/created]})))))

(deftest put-delete-patch-head-options-shapes
  (testing "every verb pins the right :method"
    (is (= :put     (-> (rf.http/put     "/x") second :request :method)))
    (is (= :delete  (-> (rf.http/delete  "/x") second :request :method)))
    (is (= :patch   (-> (rf.http/patch   "/x") second :request :method)))
    (is (= :head    (-> (rf.http/head    "/x") second :request :method)))
    (is (= :options (-> (rf.http/options "/x") second :request :method)))))

(deftest top-level-keys-pass-through
  (testing ":decode, :accept, :retry, :timeout-ms, :request-id, :abort-signal pass through"
    (let [accept (fn [v] {:ok v})
          retry  {:on #{:rf.http/transport} :max-attempts 2}
          out    (rf.http/get "/x"
                              {:decode       :json
                               :accept       accept
                               :retry        retry
                               :timeout-ms   5000
                               :on-success   [:loaded]
                               :on-failure   [:errored]
                               :request-id   :search})
          args   (second out)]
      (is (= :json (:decode args)))
      (is (identical? accept (:accept args)))
      (is (= retry (:retry args)))
      (is (= 5000 (:timeout-ms args)))
      (is (= [:loaded] (:on-success args)))
      (is (= [:errored] (:on-failure args)))
      (is (= :search (:request-id args))))))

;; ---- rf2-r40km — :rf.http/cors retry-set membership ----------------------

(deftest cors-is-a-valid-retry-on-member
  (testing "rf2-r40km / rf2-apwkm — `:rf.http/cors` is a valid member of
  `:retry :on`. CORS sits in the closed retryable set documented at
  Spec 014 §Closed-set `:retry :on` validation
  (#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx
  :rf.http/http-5xx}) and so composes cleanly with the helper arg path.
  A semantic decision on whether to AUTO-retry CORS belongs to the
  caller (typically NO — CORS is a config error, not transient — but a
  probing app may want to)."
    (let [retry {:on #{:rf.http/transport :rf.http/cors :rf.http/timeout}
                 :max-attempts 2}
          out   (rf.http/get "https://api.example.invalid/x"
                             {:retry retry})]
      (is (contains? (-> out second :retry :on) :rf.http/cors)
          ":rf.http/cors threads through the helper unchanged"))))

;; ---- rf2-r40km — classify-cljs-error CORS branch -------------------------

(deftest classify-cors-typeerror-cross-origin
  (testing "rf2-r40km — a TypeError on a cross-origin URL classifies as
  `:rf.http/cors` per Spec 014 §Failure categories. The heuristic is
  conservative: TypeError + parseable cross-origin URL = CORS; anything
  else falls through to `:rf.http/transport`.

  Note: this test only fires when `js/location.origin` is defined and
  parseable (browser-host targets). In node-runtime CLJS tests where
  the global is absent, the conservative path returns false and the
  classifier stays at `:rf.http/transport` — that branch is exercised
  by `classify-typeerror-relative-url-is-transport`."
    (when (and (exists? js/globalThis)
               (some-> js/globalThis (aget "location") (aget "origin")))
      (let [err (js/TypeError. "Failed to fetch")
            out (classify-cljs-error err "https://other.invalid/x?a=1")]
        (is (= :rf.http/cors (:kind out))
            "TypeError + cross-origin URL classifies as :rf.http/cors")
        (is (= "https://other.invalid/x?a=1" (:url out))
            ":url tag rides the failure shape (Spec 014 §Failure categories)")
        (is (some? (:message out)) ":message tag rides the failure shape")))))

(deftest classify-typeerror-relative-url-is-transport
  (testing "rf2-r40km — a TypeError on a relative URL (always same-origin
  by definition) stays at `:rf.http/transport`. The conservative path
  must not misclassify a same-origin network drop as CORS."
    (let [err (js/TypeError. "Failed to fetch")
          out (classify-cljs-error err "/api/items")]
      (is (= :rf.http/transport (:kind out))
          "relative URL never classifies as CORS"))))

(deftest classify-non-typeerror-stays-transport
  (testing "rf2-r40km — a non-TypeError (e.g. a generic JS Error) on a
  cross-origin URL still classifies as `:rf.http/transport`. CORS
  rejections are always TypeErrors."
    (let [err (js/Error. "connection-reset")
          out (classify-cljs-error err "https://other.invalid/x")]
      (is (= :rf.http/transport (:kind out))
          "non-TypeError stays at :rf.http/transport regardless of URL"))))

;; ---- rf2-u5xwa — `cross-origin?` heuristic under a deterministic origin --

(deftest cross-origin-classification-under-injected-origin
  (testing "rf2-u5xwa — the load-bearing positive CORS branch
  (`cross-origin?` returning true → `:rf.http/cors`) runs DETERMINISTICALLY
  under the node gate by injecting a known `js/globalThis.location.origin`.
  The pre-existing `classify-cors-typeerror-cross-origin` silently no-ops in
  node (the ambient `location.origin` is absent), so this exercises the real
  `classify-cljs-error` → `cross-origin?` → `js/URL.` → origin-comparison
  path that otherwise ships dark on `npm run test:cljs`. Both the positive
  branch AND its same-origin / scheme-excluded negative siblings are
  asserted so an inverted comparison or a broken scheme-exclusion would
  turn this red (Spec 014 §Failure categories `:rf.http/cors`)."
    (with-stub-location "https://app.example"
      (fn []
        (testing "TypeError on a genuinely cross-origin URL → :rf.http/cors"
          (let [err (js/TypeError. "Failed to fetch")
                out (classify-cljs-error err "https://other.invalid/x?a=1")]
            (is (= :rf.http/cors (:kind out))
                "different origin (other.invalid ≠ app.example) classifies as CORS")
            (is (= "https://other.invalid/x?a=1" (:url out))
                ":url tag rides the failure shape")
            (is (some? (:message out)) ":message tag rides the failure shape")))

        (testing "TypeError on a SAME-origin absolute URL → :rf.http/transport
        (regression guard: an inverted origin comparison would misfire here)"
          (let [err (js/TypeError. "Failed to fetch")
                out (classify-cljs-error err "https://app.example/api/items")]
            (is (= :rf.http/transport (:kind out))
                "same origin must NOT classify as CORS")))

        (testing "data:/blob:/file: schemes are scheme-excluded → :rf.http/transport
        even though their parsed origin differs from the page origin"
          (doseq [url ["data:text/plain,hello"
                       "blob:https://app.example/uuid"
                       "file:///etc/hosts"]]
            (let [err (js/TypeError. "Failed to fetch")
                  out (classify-cljs-error err url)]
              (is (= :rf.http/transport (:kind out))
                  (str url " is scheme-excluded, never CORS")))))

        (testing "relative URLs short-circuit to same-origin → :rf.http/transport
        even with a non-nil page origin present"
          (doseq [url ["/api/items" "?q=1" "#frag"]]
            (let [err (js/TypeError. "Failed to fetch")
                  out (classify-cljs-error err url)]
              (is (= :rf.http/transport (:kind out))
                  (str url " is relative/same-origin, never CORS")))))

        ;; rf2-azrcs — protocol-relative URLs inherit the page SCHEME but
        ;; carry their OWN host, so the host decides cross-origin. The
        ;; pre-fix single-slash short-circuit (`starts-with? url "/"`)
        ;; misclassified BOTH a different-host and a same-host
        ;; protocol-relative URL as same-origin.
        (testing "a protocol-relative URL with a DIFFERENT host → :rf.http/cors"
          (let [err (js/TypeError. "Failed to fetch")
                out (classify-cljs-error err "//other.invalid/x")]
            (is (= :rf.http/cors (:kind out))
                "//other.invalid/x resolves to https://other.invalid (cross-origin)")
            (is (= "//other.invalid/x" (:url out))
                ":url tag rides the original (unresolved) URL")))

        (testing "a protocol-relative URL with the SAME host → :rf.http/transport"
          (let [err (js/TypeError. "Failed to fetch")
                out (classify-cljs-error err "//app.example/x")]
            (is (= :rf.http/transport (:kind out))
                "//app.example/x resolves to https://app.example (same-origin)")))

        ;; rf2-azrcs — URL schemes are case-insensitive (RFC 3986 §3.1).
        ;; The pre-fix lowercase-only prefix check let `DATA:`/`FILE:` fall
        ;; through to `js/URL.`, where their parsed origin is the literal
        ;; string "null" (≠ page origin) → false-classified as CORS.
        (testing "uppercase / mixed-case non-http schemes are scheme-excluded
        → :rf.http/transport (case-insensitive scheme match)"
          (doseq [url ["DATA:text/plain,hello"
                       "Blob:https://app.example/uuid"
                       "FILE:///etc/hosts"]]
            (let [err (js/TypeError. "Failed to fetch")
                  out (classify-cljs-error err url)]
              (is (= :rf.http/transport (:kind out))
                  (str url " is scheme-excluded case-insensitively, never CORS")))))))))

;; ---- rf2-5zj6t — binary decode reads the native Fetch body ---------------

(defn- fake-response
  "A minimal Fetch `Response` stand-in. Each body-reader resolves to a
  distinct sentinel so the test can prove which reader the transport
  picked. `.text` returns a string; the binary readers return native
  stand-in objects."
  [{:keys [status content-type blob-val ab-val fd-val text-val]}]
  #js {:ok          (and (>= status 200) (< status 300))
       :status      status
       :statusText  ""
       ;; A Fetch-`Headers`-like object: `forEach (v k)` per `fetch-headers->map`.
       :headers     #js {:forEach (fn [cb]
                                    (when content-type (cb content-type "content-type")))}
       :text        (fn [] (js/Promise.resolve text-val))
       :blob        (fn [] (js/Promise.resolve blob-val))
       :arrayBuffer (fn [] (js/Promise.resolve ab-val))
       :formData    (fn [] (js/Promise.resolve fd-val))})

(defn- with-stub-fetch
  "Run `f` with `js/fetch` stubbed to resolve `resp`, restoring the
  original afterwards. Returns the Promise `f` produces."
  [resp f]
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis) (fn [_url _init] (js/Promise.resolve resp)))
    (-> (f)
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

(deftest binary-decode-reads-native-blob
  (testing "rf2-5zj6t — `:decode :blob` reads the response via `.blob()`,
  riding the native Blob under `:body-binary` (NOT the lossy `.text()`
  string under `:body-text`). The pre-fix transport always read `.text`,
  so a `:blob` decode resolved to the body-TEXT string."
    (async done
      (let [blob (js-obj "__kind" "blob")
            resp (fake-response {:status       200
                                 :content-type "image/png"
                                 :blob-val     blob
                                 :text-val     "lossy-utf8-text"})]
        (-> (with-stub-fetch resp
              #(cljs-fetch {:method  :get
                            :url     "/img.png"
                            :headers {}
                            :decode  :blob
                            :internal-controller (js/AbortController.)}))
            (.then (fn [result]
                     (is (identical? blob (:body-binary result))
                         ":body-binary carries the native Blob from `.blob()`")
                     (is (nil? (:body-text result))
                         "the lossy `.text()` string is NOT read for a `:blob` decode")
                     (is (true? (:ok? result)))
                     (done)))
            (.catch (fn [e] (is false (str "unexpected reject: " e)) (done))))))))

(deftest array-buffer-and-form-data-read-native-bodies
  (testing "rf2-5zj6t — `:array-buffer` reads via `.arrayBuffer()` and
  `:form-data` reads via `.formData()`, each riding `:body-binary`."
    (async done
      (let [ab (js-obj "__kind" "ab")
            fd (js-obj "__kind" "fd")]
        (-> (with-stub-fetch (fake-response {:status 200 :content-type "application/octet-stream"
                                             :ab-val ab :text-val "txt"})
              #(cljs-fetch {:method :get :url "/x" :headers {} :decode :array-buffer
                            :internal-controller (js/AbortController.)}))
            (.then (fn [result]
                     (is (identical? ab (:body-binary result))
                         ":array-buffer rides the native ArrayBuffer")
                     (is (nil? (:body-text result)))))
            (.then (fn [_]
                     (with-stub-fetch (fake-response {:status 200 :content-type "multipart/form-data"
                                                      :fd-val fd :text-val "txt"})
                       #(cljs-fetch {:method :get :url "/y" :headers {} :decode :form-data
                                     :internal-controller (js/AbortController.)}))))
            (.then (fn [result]
                     (is (identical? fd (:body-binary result))
                         ":form-data rides the native FormData")
                     (is (nil? (:body-text result)))
                     (done)))
            (.catch (fn [e] (is false (str "unexpected reject: " e)) (done))))))))

(deftest text-and-auto-text-still-read-body-text
  (testing "rf2-5zj6t — non-binary decodes (`:text`, `:json`, omitted/`:auto`
  over a text Content-Type) still read `.text()` into `:body-text`. The
  binary-reader change must not regress the common path."
    (async done
      (let [resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{\"ok\":true}" :blob-val (js-obj)})]
        (-> (with-stub-fetch resp
              #(cljs-fetch {:method :get :url "/api" :headers {} :decode :auto
                            :internal-controller (js/AbortController.)}))
            (.then (fn [result]
                     (is (= "{\"ok\":true}" (:body-text result))
                         ":auto over application/json reads `.text()`")
                     (is (nil? (:body-binary result))
                         "no binary body is read for a text/JSON decode")
                     (done)))
            (.catch (fn [e] (is false (str "unexpected reject: " e)) (done))))))))

(deftest non-2xx-binary-decode-still-reads-text
  (testing "rf2-5zj6t — a non-OK response (e.g. 404) ALWAYS reads `.text()`
  regardless of `:decode`, because decode never runs on non-2xx and the
  4xx/5xx failure paths carry the raw body-text."
    (async done
      (let [resp (fake-response {:status 404 :content-type "image/png"
                                 :blob-val (js-obj "__kind" "blob")
                                 :text-val "Not Found"})]
        (-> (with-stub-fetch resp
              #(cljs-fetch {:method :get :url "/missing.png" :headers {} :decode :blob
                            :internal-controller (js/AbortController.)}))
            (.then (fn [result]
                     (is (= "Not Found" (:body-text result))
                         "a 404 reads `.text()` even when `:decode :blob`")
                     (is (nil? (:body-binary result)))
                     (is (false? (:ok? result)))
                     (done)))
            (.catch (fn [e] (is false (str "unexpected reject: " e)) (done))))))))

;; ---- rf2-ee38b.7 — `:timeout-ms 0` is the opt-out, not a near-instant abort ----

(defn- with-deferred-fetch
  "Like `with-stub-fetch` but `js/fetch` resolves `resp` on the NEXT
  macrotask (`setTimeout 0`) rather than synchronously. This is the
  trap that exposes the pre-fix timeout-0 bug: pre-fix `:timeout-ms 0`
  armed its own `setTimeout(…, 0)` that would race this resolution and
  abort+reject the request. Post-fix the `(pos? timeout-ms)` guard arms
  no timeout, so the deferred fetch resolution always wins."
  [resp f]
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis)
          (fn [_url _init]
            (js/Promise. (fn [resolve _reject]
                           (js/setTimeout (fn [] (resolve resp)) 0)))))
    (-> (f)
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

;; ---- rf2-ee38b.7 — CLJS Fetch threads `:redirect` into the init ----------

(defn- with-init-capturing-fetch
  "Run `f` with `js/fetch` stubbed to resolve `resp` while recording the
  `init` arg into `captured-init`. Restores the original afterwards."
  [resp captured-init f]
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis)
          (fn [_url init]
            (reset! captured-init init)
            (js/Promise.resolve resp)))
    (-> (f)
        (.finally (fn [] (set! (.-fetch js/globalThis) orig))))))

(deftest cljs-fetch-passes-redirect-into-init
  (testing "rf2-ee38b.7 — the CLJS transport threads `:redirect` into the
  Fetch `init` (cross-host parity with the JVM redirect-policy fix).
  Explicit `:error` rides through name-stringified."
    (async done
      (let [captured-init (atom nil)
            resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{}"})]
        (-> (with-init-capturing-fetch resp captured-init
              #(cljs-fetch {:method   :get
                            :url      "/x"
                            :headers  {}
                            :decode   :json
                            :redirect :error
                            :internal-controller (js/AbortController.)}))
            (.then (fn [_]
                     (is (= "error" (aget @captured-init "redirect"))
                         ":redirect is name-stringified into the Fetch init")
                     (done)))
            (.catch (fn [e] (is false (str "unexpected reject: " e)) (done))))))))

;; ---- rf2-rznrz — CLJS multi-valued request headers -----------------------

(deftest cljs-fetch-multi-valued-request-header-appends-each-value
  (testing "rf2-rznrz — a request header whose value is a vector of strings
  is normalised into a Fetch `Headers` object with one APPEND per element,
  so the multi-valued wire form (`X-Multi: alpha` + `X-Multi: beta`) is
  produced. The pre-fix transport `aset` the vector straight into a plain
  JS object, serialising it as a single malformed `[\"alpha\" \"beta\"]`
  value."
    (async done
      (let [captured-init (atom nil)
            resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{}"})]
        (-> (with-init-capturing-fetch resp captured-init
              #(cljs-fetch {:method  :get
                            :url     "/x"
                            :headers {"X-Multi" ["alpha" "beta" "gamma"]
                                      "X-One"   "solo"}
                            :decode  :json
                            :internal-controller (js/AbortController.)}))
            (.then (fn [_]
                     (let [h (aget @captured-init "headers")]
                       (is (instance? js/Headers h)
                           "headers is a Fetch `Headers` object, not a plain JS map")
                       ;; `Headers.get` joins repeated values with ", " — three
                       ;; appended values produce the joined multi-value string,
                       ;; NOT a serialised-vector blob.
                       (is (= "alpha, beta, gamma" (.get h "X-Multi"))
                           "each vector element was appended as its own value")
                       (is (not (re-find #"\[" (.get h "X-Multi")))
                           "no serialised-vector bracket leaked onto the wire value")
                       (is (= "solo" (.get h "X-One"))
                           "a scalar header value is a single appended value"))
                     (done)))
            (.catch (fn [e] (is false (str "unexpected reject: " e)) (done))))))))

;; ---- rf2-f5pguu — invalid request headers stay on the managed path -------
;;
;; The Fetch `Headers.append` throws a `TypeError` on an invalid header
;; name (empty / control chars) or a value carrying `\r`/`\n` (the
;; response-splitting guard). Pre-fix, that throw fired SYNCHRONOUSLY
;; inside `cljs-fetch` — after the in-flight handle was registered but
;; before any Promise existed — and propagated up through `run-attempt!`'s
;; CLJS branch (no try/catch there) as a generic `:rf.error/fx-handler-
;; exception`, bypassing `:on-failure`, retry, abort precedence, trace
;; privacy, and registry cleanup. The fix mirrors the JVM
;; `jvm-build-request`: catch PER `.append`, emit a redacted
;; `:rf.warning/http-header-invalid` trace, omit the bad pair, and
;; continue with the valid headers. These tests pin the managed-path
;; behaviour so a regression re-opening the unmanaged escape is caught.

(defn- with-trace-capture
  "Install a recording trace listener, run `f` (which returns a Promise),
  and invoke `(on-events events-atom)` once `f`'s Promise settles. The
  listener is unregistered in `.finally`. The events-atom collects every
  trace event so callers filter for `:rf.warning/http-header-invalid`."
  [f on-events]
  (let [seen   (atom [])
        cb-key (keyword (str "http-cljs-invalid-header-" (gensym)))]
    (trace-tooling/register-listener! cb-key (fn [ev] (swap! seen conj ev)))
    (-> (f)
        (.then (fn [result] (on-events seen result)))
        (.finally (fn [] (trace-tooling/unregister-listener! cb-key))))))

(deftest cljs-fetch-invalid-header-name-surfaces-managed-warning-not-escape
  (testing "rf2-f5pguu — an EMPTY header name (which `Headers.append`
  rejects with a TypeError) is caught inside the managed CLJS path: a
  redacted `:rf.warning/http-header-invalid` trace fires naming the bad
  header, the bad pair is OMITTED, the valid header still rides, and the
  `cljs-fetch` Promise RESOLVES normally rather than rejecting / throwing
  synchronously (which pre-fix escaped as `:rf.error/fx-handler-exception`)."
    (async done
      (let [captured-init (atom nil)
            resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{}"})]
        (-> (with-trace-capture
              #(with-init-capturing-fetch resp captured-init
                 (fn []
                   (cljs-fetch {:method  :get
                                :url     "https://example.invalid/v1"
                                ;; "" is an invalid header name → TypeError.
                                :headers {""       "anything"
                                          "X-Good" "kept"}
                                :decode  :json
                                :internal-controller (js/AbortController.)})))
              (fn [seen _result]
                (let [warns (filter #(= :rf.warning/http-header-invalid
                                        (:operation %))
                                    @seen)]
                  (is (seq warns)
                      (str "expected a managed :rf.warning/http-header-invalid "
                           "trace, not an escaping fx-handler-exception; saw ops: "
                           (pr-str (mapv :operation @seen))))
                  (let [w    (first warns)
                        tags (:tags w)]
                    (is (= :warning (:op-type w)))
                    (is (= "" (:header tags))
                        "trace names the offending header (the empty name)")
                    (is (some? (:cause tags))
                        "trace carries the TypeError message at :cause")
                    (is (not (contains? tags :value))
                        "trace MUST NOT carry the rejected value — values can be secrets"))
                  ;; The bad pair is omitted; the valid header survives.
                  (let [h (aget @captured-init "headers")]
                    (is (instance? js/Headers h)
                        "a Fetch Headers object is still built and passed to fetch")
                    (is (= "kept" (.get h "X-Good"))
                        "the valid header rides; only the bad pair was dropped")))))
            (.then (fn [_] (done)))
            ;; A rejection / synchronous throw here is the PRE-FIX bug — the
            ;; invalid header escaped the managed path.
            (.catch (fn [e]
                      (is false
                          (str "rf2-f5pguu regression — invalid header ESCAPED "
                               "the managed CLJS path (threw/rejected) instead "
                               "of surfacing a managed warning: " e))
                      (done))))))))

(deftest cljs-fetch-crlf-header-value-surfaces-managed-warning-not-escape
  (testing "rf2-f5pguu — a header VALUE carrying a CR (`\\r`) is the
  classic response-splitting vector; `Headers.append` rejects it with a
  TypeError. It is caught inside the managed path (warning emitted, pair
  omitted, valid headers preserved, Promise resolves) — not escaped."
    (async done
      (let [captured-init (atom nil)
            resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{}"})]
        (-> (with-trace-capture
              #(with-init-capturing-fetch resp captured-init
                 (fn []
                   (cljs-fetch {:method  :get
                                :url     "https://example.invalid/v1"
                                :headers {"X-Bad"  "value-with-\rCR"
                                          "X-Good" "kept"}
                                :decode  :json
                                :internal-controller (js/AbortController.)})))
              (fn [seen _result]
                (let [warns (filter #(= :rf.warning/http-header-invalid
                                        (:operation %))
                                    @seen)]
                  (is (seq warns)
                      "expected a managed :rf.warning/http-header-invalid trace for the CR/LF value")
                  (let [tags (:tags (first warns))]
                    (is (= "X-Bad" (:header tags))
                        "trace names the offending header")
                    (is (not (contains? tags :value))
                        "the CR/LF value MUST NOT ride the trace surface"))
                  (let [h (aget @captured-init "headers")]
                    (is (= "kept" (.get h "X-Good"))
                        "the valid header survives the dropped CR/LF pair")
                    (is (nil? (.get h "X-Bad"))
                        "the response-splitting header was omitted, not sent")))))
            (.then (fn [_] (done)))
            (.catch (fn [e]
                      (is false
                          (str "rf2-f5pguu regression — CR/LF header value escaped "
                               "the managed path: " e))
                      (done))))))))

(deftest cljs-fetch-invalid-header-warning-redacts-denylisted-query-param
  (testing "rf2-f5pguu — the managed CLJS header-validation warning routes
  its `:url` through `re-frame.http-privacy/prepare-emit-tags` (same as the JVM path,
  rf2-1jcpm): a denylisted query param (`?api_key=…`) is scrubbed and
  `:sensitive?` is stamped at the top level of the trace event."
    (async done
      (let [captured-init (atom nil)
            resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{}"})]
        (-> (with-trace-capture
              #(with-init-capturing-fetch resp captured-init
                 (fn []
                   (cljs-fetch {:method  :get
                                :url     "https://example.invalid/v1?api_key=SECRET&page=2"
                                :headers {"" "anything"}
                                :decode  :json
                                :internal-controller (js/AbortController.)})))
              (fn [seen _result]
                (let [w (first (filter #(= :rf.warning/http-header-invalid
                                           (:operation %))
                                       @seen))]
                  (is (some? w) "warning event should have been captured")
                  (let [tags (:tags w)]
                    (is (= "https://example.invalid/v1?api_key=:rf/redacted&page=2"
                           (:url tags))
                        "denylisted query-param value MUST be scrubbed in the trace URL")
                    (is (true? (:sensitive? w))
                        ":sensitive? stamped at top level — a denylisted param name is a signal")
                    (is (= http-url/redacted-sentinel :rf/redacted)
                        "sanity: the redaction sentinel is the reserved keyword")))))
            (.then (fn [_] (done)))
            (.catch (fn [e]
                      (is false (str "unexpected reject: " e))
                      (done))))))))

(deftest cljs-fetch-valid-headers-emit-no-invalid-warning
  (testing "rf2-f5pguu — a request with only VALID headers emits NO
  `:rf.warning/http-header-invalid` trace (the catch arm never fires) and
  resolves normally — the multi-valued behaviour (rf2-rznrz) is preserved."
    (async done
      (let [captured-init (atom nil)
            resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{}"})]
        (-> (with-trace-capture
              #(with-init-capturing-fetch resp captured-init
                 (fn []
                   (cljs-fetch {:method  :get
                                :url     "https://example.invalid/v1"
                                :headers {"Authorization" "Bearer xyz"
                                          "X-Multi"       ["alpha" "beta"]}
                                :decode  :json
                                :internal-controller (js/AbortController.)})))
              (fn [seen _result]
                (let [warns (filter #(= :rf.warning/http-header-invalid
                                        (:operation %))
                                    @seen)]
                  (is (empty? warns)
                      "no header-invalid warning for valid headers"))
                (let [h (aget @captured-init "headers")]
                  (is (= "alpha, beta" (.get h "X-Multi"))
                      "valid multi-valued header still accumulates per-element"))))
            (.then (fn [_] (done)))
            (.catch (fn [e]
                      (is false (str "unexpected reject: " e))
                      (done))))))))

(deftest zero-timeout-ms-does-not-arm-near-instant-abort
  (testing "rf2-ee38b.7 — `:timeout-ms 0` is an explicit opt-out (no
  per-attempt timeout) per Spec 014 §`:timeout-ms` security defaults,
  semantically identical to `:timeout-ms nil`. Pre-fix `0` was truthy,
  so `(when (and timeout-ms internal-controller) …)` armed a
  `setTimeout(…, 0)` that aborted the request on the next macrotask and
  rejected with the timeout ex-info. This test defers the fetch
  resolution by one macrotask: pre-fix the timeout abort would win and
  reject; post-fix the request resolves successfully."
    (async done
      (let [resp (fake-response {:status 200 :content-type "application/json"
                                 :text-val "{\"ok\":true}"})]
        (-> (with-deferred-fetch resp
              #(cljs-fetch {:method     :get
                            :url        "/slow"
                            :headers    {}
                            :decode     :json
                            :timeout-ms 0
                            :internal-controller (js/AbortController.)}))
            (.then (fn [result]
                     (is (= "{\"ok\":true}" (:body-text result))
                         ":timeout-ms 0 must NOT abort — the deferred fetch resolves normally")
                     (is (true? (:ok? result)))
                     (done)))
            (.catch (fn [e]
                      (is false (str "rf2-ee38b.7 regression — :timeout-ms 0 "
                                     "armed a near-instant abort and rejected: " e))
                      (done))))))))

;; ---- rf2-4zldh — `:timeout-ms` bounds the BODY read, not just headers ----
;;
;; A slow-loris upstream can resolve the Fetch Response (headers) promptly
;; and then stall the body reader (`.text()` / `.blob()` / …) indefinitely.
;; Spec 014 §`:timeout-ms` security defaults (:323) requires the per-attempt
;; timeout to protect against exactly this. The pre-fix transport cleared
;; the timer the instant the Response resolved and only THEN began the body
;; read, so a stalled body left the body-reader promise pending forever and
;; the in-flight handle live. The fix races the timer against the FULL
;; fetch→body-read chain and disarms it only when that chain settles.

(defn- fake-response-stalled-body
  "A Fetch `Response` stand-in whose HEADERS resolve immediately (the
  outer `js/fetch` Promise) but whose selected body reader returns a
  Promise that NEVER settles. Models a server that sends headers then
  stalls the body — the slow-loris the per-attempt timeout must bound.
  `read-fired` (an atom) is set true the moment the body reader is
  invoked, so the test can assert the reader was reached before the
  timeout fired."
  [{:keys [status content-type read-fired]}]
  #js {:ok          (and (>= status 200) (< status 300))
       :status      status
       :statusText  ""
       :headers     #js {:forEach (fn [cb]
                                    (when content-type (cb content-type "content-type")))}
       :text        (fn [] (reset! read-fired true) (js/Promise. (fn [_ _])))
       :blob        (fn [] (reset! read-fired true) (js/Promise. (fn [_ _])))
       :arrayBuffer (fn [] (reset! read-fired true) (js/Promise. (fn [_ _])))
       :formData    (fn [] (reset! read-fired true) (js/Promise. (fn [_ _])))})

(deftest cljs-timeout-bounds-stalled-body-read
  (testing "rf2-4zldh — `cljs-fetch` REJECTS with the canonical
  :rf.error/http-timeout ex-info when the Response resolves (headers) but
  the selected body reader never settles past `:timeout-ms`. Pre-fix the
  timer was cleared on header arrival, so this promise hung forever."
    (async done
      (let [read-fired (atom false)
            resp       (fake-response-stalled-body
                         {:status 200 :content-type "application/json"
                          :read-fired read-fired})]
        (-> (with-stub-fetch resp
              #(cljs-fetch {:method     :get
                            :url        "/slow-body"
                            :headers    {}
                            :decode     :json
                            :timeout-ms 40
                            :internal-controller (js/AbortController.)}))
            (.then (fn [result]
                     (is false (str "rf2-4zldh regression — a stalled body "
                                    "read RESOLVED instead of timing out: " (pr-str result)))
                     (done)))
            (.catch (fn [err]
                      (is (true? @read-fired)
                          "the body reader was reached (headers resolved) before the timeout fired")
                      (let [data (ex-data err)]
                        (is (= :rf.error/http-timeout (:rf.error/id data))
                            "the stalled-body timeout rejects with the canonical :rf.error/http-timeout")
                        (is (true? (:rf.http/timeout? data))
                            "the registry-hook timeout signal is co-stamped")
                        (is (= 40 (:limit-ms data)))
                        ;; rf2-6ecc6 — CLJS `:elapsed-ms` is now a MEASURED
                        ;; wall-clock delta (~:limit-ms by the scheduling
                        ;; margin), the SAME value-semantics the JVM path
                        ;; reports — not the synthetic constant == :limit-ms
                        ;; it used to be. (The timer fires at ~40ms, so the
                        ;; measured elapsed is in that neighbourhood.)
                        (is (number? (:elapsed-ms data))
                            ":elapsed-ms is a measured number, not absent")
                        ;; rf2-xu0sl — jitter-tolerant bound: `elapsed` must
                        ;; land within `elapsed-jitter-ms` BELOW `limit` (the
                        ;; timer can fire a hair early under rounding/CI
                        ;; scheduling jitter — an exact `>= limit` flaked by
                        ;; ~1ms). This still rejects a synthetic/absent value
                        ;; and any wildly-wrong measurement, just not 1ms noise.
                        (is (>= (:elapsed-ms data) (- (:limit-ms data) elapsed-jitter-ms))
                            (str ":elapsed-ms (measured) is within " elapsed-jitter-ms
                                 "ms below :limit-ms — measured, JVM-parity semantics,"
                                 " jitter-tolerant")))
                      (done))))))))

(deftest cljs-timeout-stalled-body-finalises-as-timeout-and-clears-registry
  (testing "rf2-4zldh — driven through the full `:rf.http/managed` pipeline,
  a response whose body reader stalls past `:timeout-ms` finalises as a
  :rf.http/timeout failure reply AND clears the in-flight registry. This is
  the end-to-end acceptance: the slow-loris no longer pins an in-flight
  handle indefinitely."
    (async done
      (rf/init! reagent-adapter/adapter)
      ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
      ;; and the managed-HTTP fxs require a carried frame stamp. Register
      ;; `:rf/default` explicitly; each dispatch below carries `{:frame
      ;; :rf/default}` (the override) so the sync dispatch AND the async
      ;; abort continuation both target it without an ambient scope.
      (frame/ensure-default-frame!)
      (http-managed/clear-all-in-flight!)
      (let [replies    (atom [])
            read-fired (atom false)
            resp       (fake-response-stalled-body
                         {:status 200 :content-type "application/json"
                          :read-fired read-fired})
            orig       (.-fetch js/globalThis)]
        (set! (.-fetch js/globalThis) (fn [_url _init] (js/Promise.resolve resp)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/slow-body
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url "/slow-body"}
                    :decode     :json
                    :timeout-ms 40
                    :request-id :loris
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/slow-body] {:frame :rf/default})
        (-> (test-support/poll-until
              #(seq @replies)
              {:timeout-ms 2000 :label "cljs stalled-body timeout reply"})
            (.then (fn [_]
                     (is (true? @read-fired)
                         "the body reader was reached before the timeout fired")
                     (let [reply (first @replies)]
                       (is (= :failure (:kind reply)))
                       (is (= :rf.http/timeout (get-in reply [:failure :kind]))
                           "a stalled body read finalises as the canonical :rf.http/timeout failure"))
                     (is (empty? (registry/in-flight-snapshot))
                         "the in-flight registry is cleared — the slow-loris handle is not pinned")
                     (set! (.-fetch js/globalThis) orig)
                     (done)))
            (.catch (fn [e]
                      (set! (.-fetch js/globalThis) orig)
                      (is false (str "rf2-4zldh — unexpected: " e))
                      (done))))))))

;; ---- rf2-wj8vv — the retry backoff window is cancellable (CLJS) -----------
;;
;; The JVM suite (re-frame.http-backoff-cancellation-test) covers all three
;; cancellation paths against a real server with real threads. This CLJS
;; counterpart pins the same invariant on the `js/setTimeout`-backed backoff
;; timer + `js/clearTimeout` cancellation primitive: an abort issued DURING
;; the backoff window cancels the pending retry (no second fetch) and clears
;; the in-flight registry. Pre-fix the request was invisible to the abort
;; path for the whole backoff, so the retry fetched again regardless.

(defn- with-counting-500-fetch
  "Stub `js/fetch` to always resolve a 500 and increment `count-atom` on
  every call. Returns a 0-arg restore fn."
  [count-atom]
  (let [orig (.-fetch js/globalThis)
        resp (fake-response {:status 500 :content-type "application/json"
                             :text-val "boom"})]
    (set! (.-fetch js/globalThis)
          (fn [_url _init]
            (swap! count-atom inc)
            (js/Promise.resolve resp)))
    (fn [] (set! (.-fetch js/globalThis) orig))))

(deftest cljs-abort-during-backoff-cancels-pending-retry
  (testing "rf2-wj8vv — a :rf.http/managed-abort issued while the request sleeps in the `js/setTimeout` backoff window cancels the pending retry (no second fetch) and clears the registry"
    (async done
      ;; Self-contained runtime setup — this ns also carries `async`
      ;; pure-transport tests, so cljs.test forbids a wrap-style
      ;; `use-fixtures` reset here (it would tear down before the async
      ;; body completes). Install the adapter + clear the registry inline.
      (rf/init! reagent-adapter/adapter)
      ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
      ;; and the managed-HTTP fxs require a carried frame stamp. Register
      ;; `:rf/default` explicitly; each dispatch below carries `{:frame
      ;; :rf/default}` (the override) so the sync dispatch AND the async
      ;; abort continuation both target it without an ambient scope.
      (frame/ensure-default-frame!)
      (http-managed/clear-all-in-flight!)
      (let [fetch-count (atom 0)
            replies     (atom [])
            restore     (with-counting-500-fetch fetch-count)
            ;; 80ms backoff — long enough to abort inside deterministically,
            ;; short enough to keep the test fast.
            backoff-ms  80]
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url "/always-500"}
                    :decode     :json
                    :retry      {:on           #{:rf.http/http-5xx}
                                 :max-attempts 5
                                 :backoff      {:base-ms backoff-ms :factor 1
                                                :max-ms  backoff-ms}}
                    :request-id :race
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue] {:frame :rf/default})
        ;; Poll (microtask-paced) until attempt #1 has fetched, failed 5xx,
        ;; and the request is sleeping in the backoff window. The backoff
        ;; handle is distinguishable from the in-flight-fetch handle by the
        ;; ABSENCE of the `:finalised?` cell (the fetch handle carries it;
        ;; the backoff handle does not) — gating on this ensures we abort
        ;; the BACKOFF state, not a still-in-flight attempt #1. The defect
        ;; emptied the registry entirely during this window.
        (-> (test-support/poll-until
              #(let [handle (get (registry/in-flight-snapshot) :race)]
                 (and (= 1 @fetch-count)
                      (some? handle)
                      (nil? (:finalised? handle))))
              {:timeout-ms 2000 :label "cljs backoff sleeping"})
            (.then (fn [_]
                     ;; Abort squarely inside the backoff window.
                     (rf/dispatch-sync [:do/abort] {:frame :rf/default})
                     (is (empty? (registry/in-flight-snapshot))
                         "the registry is cleared the instant the backoff is cancelled")
                     ;; The aborted reply dispatches through the async router;
                     ;; poll for it (it lands well before the backoff would
                     ;; have elapsed).
                     (test-support/poll-until
                       #(seq @replies)
                       {:timeout-ms 2000 :label "cljs abort reply"})))
            (.then (fn [_]
                     (let [reply (first @replies)]
                       (is (= :failure (:kind reply)))
                       (is (= :rf.http/aborted (get-in reply [:failure :kind]))
                           "abort during backoff dispatches the canonical :rf.http/aborted reply")
                       (is (= :user (get-in reply [:failure :reason]))))
                     ;; Wait past the original backoff deadline and assert the
                     ;; retry never fetched again (proving the timer was
                     ;; cancelled, not merely that the reply arrived first).
                     (js/Promise.
                       (fn [resolve _]
                         (js/setTimeout resolve (+ backoff-ms 120))))))
            (.then (fn [_]
                     (is (= 1 @fetch-count)
                         "the retry MUST NOT fetch after an abort issued during the backoff window")
                     (is (= 1 (count @replies))
                         "exactly one reply — the cancelled retry never produced a second outcome")
                     (restore)
                     (done)))
            (.catch (fn [e]
                      (restore)
                      (is false (str "rf2-wj8vv — unexpected: " e))
                      (done))))))))

;; ---- rf2-065xo — managed body-prep failure delivery (CLJS) ----------------
;;
;; The JVM suite (re-frame.http-body-prep-failure-test) pins the same
;; contract against Cheshire encode failures + real threads. This CLJS
;; counterpart proves it on the Fetch path: a throwing `:body` thunk and a
;; non-serialisable body (circular ref → `js/JSON.stringify` throws) are
;; caught in the `prepare-body!` phase and delivered as ONE :on-failure
;; reply with :rf.http/transport — NOT a generic :rf.error/fx-handler-
;; exception — and a configured retry re-invokes the thunk per attempt.
;;
;; The throw happens in the prep phase BEFORE any fetch, so `js/fetch` is
;; stubbed to FAIL the test loudly if it is ever reached (proving the
;; request never left the prep phase).

(defn- with-failing-fetch
  "Stub `js/fetch` to reject loudly — used by the prep-failure tests where
  the body throws BEFORE any network call, so `fetch` must never run.
  Returns a 0-arg restore fn."
  []
  (let [orig (.-fetch js/globalThis)]
    (set! (.-fetch js/globalThis)
          (fn [_url _init]
            (js/Promise.reject
              (js/Error. "rf2-065xo — fetch must NOT be reached: body-prep threw before any network call"))))
    (fn [] (set! (.-fetch js/globalThis) orig))))

(deftest cljs-throwing-body-thunk-delivers-managed-transport-failure
  (testing "rf2-065xo — a `:body` thunk that throws delivers ONE :on-failure reply with :rf.http/transport (NOT :rf.error/fx-handler-exception) and clears the registry"
    (async done
      (rf/init! reagent-adapter/adapter)
      ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
      ;; and the managed-HTTP fxs require a carried frame stamp. Register
      ;; `:rf/default` explicitly; each dispatch below carries `{:frame
      ;; :rf/default}` (the override) so the sync dispatch AND the async
      ;; abort continuation both target it without an ambient scope.
      (frame/ensure-default-frame!)
      (http-managed/clear-all-in-flight!)
      (let [replies (atom [])
            restore (with-failing-fetch)]
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/throwing-thunk
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    "/x"
                                 :method :post
                                 :body   (fn [] (throw (js/Error. "boom-thunk")))}
                    :request-id :prep-thunk
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/throwing-thunk] {:frame :rf/default})
        (-> (test-support/poll-until
              #(seq @replies)
              {:timeout-ms 2000 :label "cljs body-thunk prep failure reply"})
            (.then (fn [_]
                     (is (= 1 (count @replies))
                         "exactly one reply — the prep failure is delivered once")
                     (let [reply (first @replies)]
                       (is (= :failure (:kind reply)))
                       (is (= :rf.http/transport (get-in reply [:failure :kind]))
                           "a throwing body thunk surfaces as the managed :rf.http/transport category")
                       (is (= :request-prep (get-in reply [:failure :stage]))
                           "the :stage discriminator marks this as a request-preparation failure"))
                     (is (empty? (registry/in-flight-snapshot))
                         "the in-flight registry is cleared — the failed-prep request is not pinned")
                     (restore)
                     (done)))
            (.catch (fn [e]
                      (restore)
                      (is false (str "rf2-065xo — unexpected: " e))
                      (done))))))))

(deftest cljs-unencodable-body-delivers-managed-transport-failure
  (testing "rf2-065xo — a non-serialisable body (circular ref → JSON.stringify throws) delivers ONE :on-failure reply with :rf.http/transport"
    (async done
      (rf/init! reagent-adapter/adapter)
      ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
      ;; and the managed-HTTP fxs require a carried frame stamp. Register
      ;; `:rf/default` explicitly; each dispatch below carries `{:frame
      ;; :rf/default}` (the override) so the sync dispatch AND the async
      ;; abort continuation both target it without an ambient scope.
      (frame/ensure-default-frame!)
      (http-managed/clear-all-in-flight!)
      (let [replies   (atom [])
            restore   (with-failing-fetch)
            ;; A circular JS object — `js/JSON.stringify` throws a TypeError
            ;; converting it, exercising the encode-failure prep path.
            circular  (let [o (js-obj)]
                        (aset o "self" o)
                        o)]
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/unencodable
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    "/x"
                                 :method :post
                                 :request-content-type :json
                                 :body   circular}
                    :request-id :prep-encode
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/unencodable] {:frame :rf/default})
        (-> (test-support/poll-until
              #(seq @replies)
              {:timeout-ms 2000 :label "cljs encode prep failure reply"})
            (.then (fn [_]
                     (is (= 1 (count @replies)))
                     (let [reply (first @replies)]
                       (is (= :rf.http/transport (get-in reply [:failure :kind]))
                           "an encode failure surfaces as the managed :rf.http/transport category")
                       (is (= :request-prep (get-in reply [:failure :stage]))))
                     (is (empty? (registry/in-flight-snapshot)))
                     (restore)
                     (done)))
            (.catch (fn [e]
                      (restore)
                      (is false (str "rf2-065xo — unexpected: " e))
                      (done))))))))

(deftest cljs-throwing-body-thunk-retries-when-configured
  (testing "rf2-065xo — with `:retry {:on #{:rf.http/transport} …}` a throwing body thunk RETRIES (re-invoking the thunk per attempt) then finally fails :rf.http/transport"
    (async done
      (rf/init! reagent-adapter/adapter)
      ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
      ;; and the managed-HTTP fxs require a carried frame stamp. Register
      ;; `:rf/default` explicitly; each dispatch below carries `{:frame
      ;; :rf/default}` (the override) so the sync dispatch AND the async
      ;; abort continuation both target it without an ambient scope.
      (frame/ensure-default-frame!)
      (http-managed/clear-all-in-flight!)
      (let [replies     (atom [])
            invocations (atom 0)
            restore     (with-failing-fetch)]
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue/retry-thunk
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url    "/x"
                                 :method :post
                                 :body   (fn []
                                           (swap! invocations inc)
                                           (throw (js/Error. "boom-retry")))}
                    :retry      {:on           #{:rf.http/transport}
                                 :max-attempts 3
                                 :backoff      {:base-ms 1 :factor 1 :max-ms 1}}
                    :request-id :prep-retry
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue/retry-thunk] {:frame :rf/default})
        (-> (test-support/poll-until
              #(seq @replies)
              {:timeout-ms 4000 :label "cljs prep-failure retry exhaustion reply"})
            (.then (fn [_]
                     (is (= 3 @invocations)
                         "the throwing thunk was re-invoked once per attempt (max-attempts 3)")
                     (is (= 1 (count @replies))
                         "exactly one FINAL reply after the retries exhaust")
                     (let [reply (first @replies)]
                       (is (= :rf.http/transport (get-in reply [:failure :kind]))
                           "the final reply carries the :rf.http/transport prep-failure category"))
                     (is (empty? (registry/in-flight-snapshot)))
                     (restore)
                     (done)))
            (.catch (fn [e]
                      (restore)
                      (is false (str "rf2-065xo — unexpected: " e))
                      (done))))))))
