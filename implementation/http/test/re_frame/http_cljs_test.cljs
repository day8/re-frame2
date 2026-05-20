(ns re-frame.http-cljs-test
  "CLJS-side smoke for the `re-frame.http` call-site helpers (rf2-pf4k).

  The JVM `re-frame.http-test` covers the full shape contract. This
  smoke just confirms the helpers compile clean under CLJS (the file
  is `.cljc` with no host-specific bits, but a CLJS-side load is the
  fastest way to catch a regression that would otherwise only surface
  in shadow-cljs builds).

  Also covers rf2-r40km — the CLJS-only `:rf.http/cors` classification
  branch of `re-frame.http-transport/classify-cljs-error`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.http :as rf.http]
            [re-frame.http-transport :as transport]))

;; Reach the private classifier via #' so the test doesn't widen the
;; transport's public surface for one CLJS-only branch.
(def ^:private classify-cljs-error
  @#'transport/classify-cljs-error)

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
