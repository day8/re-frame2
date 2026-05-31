(ns re-frame.http-cljs-test
  "CLJS-side smoke for the `re-frame.http` call-site helpers (rf2-pf4k).

  The JVM `re-frame.http-test` covers the full shape contract. This
  smoke just confirms the helpers compile clean under CLJS (the file
  is `.cljc` with no host-specific bits, but a CLJS-side load is the
  fastest way to catch a regression that would otherwise only surface
  in shadow-cljs builds).

  Also covers rf2-r40km — the CLJS-only `:rf.http/cors` classification
  branch of `re-frame.http-transport/classify-cljs-error`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.http :as rf.http]
            ;; rf2-wj8vv — drive the full `:rf.http/managed` pipeline (fx
            ;; registration + in-flight registry) for the backoff-window
            ;; cancellation test below.
            [re-frame.http-managed :as http-managed]
            [re-frame.http-registry :as registry]
            [re-frame.http-transport :as transport]
            [re-frame.test-support :as test-support]))

;; Reach the private classifier via #' so the test doesn't widen the
;; transport's public surface for one CLJS-only branch.
(def ^:private classify-cljs-error
  @#'transport/classify-cljs-error)

;; rf2-5zj6t — reach the private CLJS Fetch transport so we can assert it
;; reads the correct response body type per `:decode` (a Fetch Response
;; body may be consumed only once, so the reader is chosen up front).
(def ^:private cljs-fetch
  @#'transport/cljs-fetch)

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
      (http-managed/clear-all-in-flight!)
      (let [fetch-count (atom 0)
            replies     (atom [])
            restore     (with-counting-500-fetch fetch-count)
            ;; 80ms backoff — long enough to abort inside deterministically,
            ;; short enough to keep the test fast.
            backoff-ms  80]
        (rf/reg-event-fx :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event-fx :issue
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
        (rf/reg-event-fx :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue])
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
                     (rf/dispatch-sync [:do/abort])
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
