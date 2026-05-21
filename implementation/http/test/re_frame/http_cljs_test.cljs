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
            [re-frame.http :as rf.http]
            [re-frame.http-transport :as transport]))

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
