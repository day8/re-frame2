(ns re-frame.ssr.ring.pipeline-materialiser-test
  "Unit coverage for the response materialiser + header-fold contracts
  that the full-handler / Jetty e2e tests exercise only indirectly
  (rf2-ynjts.14 testing-review).

  The end-to-end suites (`ring_test`, `ring_e2e_validator_test`,
  `ring_streaming_test`) prove the happy + error wire paths through the
  whole handler. They do NOT pin the small public materialiser fns in
  isolation, so several documented branches were untested:

    1. `ssr-response->ring-response` redirect-target precedence — the fn
       independently resolves `(or location url to)`. The RUNTIME's
       `redirect-fx` normalises `:url` / `:to` into `:location` before
       the accumulator is read, so the only surface that ever feeds the
       adapter a non-normalised `:url` / `:to` is a direct call (a future
       alt-host, a hand-built response). Those two alias arms therefore
       had zero coverage end-to-end — a regression dropping one would
       pass every existing test. Pinned here directly.

    2. `headers->ring-map+default-content-type` POSITIVE paths — the
       suppression path (a caller Content-Type in any casing suppresses
       the default) is covered by `ring_test/content-type-default-no-
       duplicate-on-mixed-case`. The complementary contracts were not:
         (a) the default IS appended when the pairs declare no
             Content-Type,
         (b) a `nil` `content-type` arg means NO default is appended,
         (c) repeated header names collapse into a vector
             (`merge-pair-into-header-map`'s string→vector→conj arms),
       the last being the load-bearing multi-valued-header round-trip
       (Set-Cookie / Vary / Link) the ns docstring promises.

    3. `ssr-middleware` DEFAULT `:match?` — every middleware test to date
       supplies an explicit `:match?`. The documented default (matches
       every GET; non-GET falls through to the wrapped handler) had no
       coverage, so a regression in the default predicate would pass.

  These are pure / synchronous contracts. No Jetty, no streaming, no
  network — deterministic by construction. The header-fold + materialiser
  fns are public (`defn`, not `defn-`), so the tests call them directly
  rather than reaching through `requiring-resolve`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.headers :as headers]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ===========================================================================
;; ssr-response->ring-response — redirect target precedence (:location / :url / :to)
;;
;; Spec 011 accepts `:location`, `:url`, or `:to` as the redirect target
;; key. The adapter materialiser resolves them with `(or location url to)`
;; INDEPENDENTLY of the runtime (the runtime normalises to :location, but
;; the adapter is the last line for any host / hand-built response that
;; didn't). Pin all three keys + the precedence order.
;; ===========================================================================

(deftest redirect-resolves-location-key
  (testing "a redirect map keyed by :location emits a Location header"
    (let [resp {:redirect {:status 302 :location "/by-location"}}
          ring (pipeline/ssr-response->ring-response resp nil)]
      (is (= 302 (:status ring)))
      (is (= "/by-location" (get (:headers ring) "Location")))
      (is (= "" (:body ring)) "redirect has no body"))))

(deftest redirect-resolves-url-alias
  (testing "rf2-ynjts.14: a redirect map keyed by :url (NOT :location) still
            emits the Location header — the adapter resolves the alias the
            runtime would normalise, covering hosts/responses that didn't"
    (let [resp {:redirect {:status 303 :url "/by-url"}}
          ring (pipeline/ssr-response->ring-response resp nil)]
      (is (= 303 (:status ring)) "the redirect map's :status rides through")
      (is (= "/by-url" (get (:headers ring) "Location"))
          ":url is resolved as the Location target"))))

(deftest redirect-resolves-to-alias
  (testing "rf2-ynjts.14: a redirect map keyed by :to (NOT :location / :url)
            still emits the Location header"
    (let [resp {:redirect {:status 307 :to "/by-to"}}
          ring (pipeline/ssr-response->ring-response resp nil)]
      (is (= 307 (:status ring)))
      (is (= "/by-to" (get (:headers ring) "Location"))
          ":to is resolved as the Location target"))))

(deftest redirect-target-precedence-location-wins
  (testing "rf2-ynjts.14: when more than one target key is present the
            precedence is :location > :url > :to (the `(or location url to)`
            order in the materialiser)"
    (is (= "/loc"
           (get (:headers (pipeline/ssr-response->ring-response
                            {:redirect {:status 302
                                        :location "/loc" :url "/url" :to "/to"}}
                            nil))
                "Location"))
        ":location wins over :url and :to")
    (is (= "/url"
           (get (:headers (pipeline/ssr-response->ring-response
                            {:redirect {:status 302 :url "/url" :to "/to"}}
                            nil))
                "Location"))
        ":url wins over :to when :location absent")))

(deftest redirect-default-status-302-when-absent
  (testing "rf2-ynjts.14: a redirect map with a target but no :status
            defaults to 302 (the materialiser's `(or redirect-status
            status 302)` fallback)"
    (let [ring (pipeline/ssr-response->ring-response
                 {:redirect {:location "/x"}} nil)]
      (is (= 302 (:status ring))
          "absent redirect :status → 302 default")
      (is (= "/x" (get (:headers ring) "Location"))))))

(deftest redirect-no-target-omits-location-header
  (testing "rf2-ynjts.14: a redirect with no :location / :url / :to emits
            the status but NO Location header (the malformed-redirect
            tolerance — the warning-trace branch is pinned end-to-end by
            ring_test/handler-redirect-no-target-warns; here we pin the
            materialiser's header-omission half directly)"
    (let [ring (pipeline/ssr-response->ring-response
                 {:redirect {:status 302}} nil)]
      (is (= 302 (:status ring)) "status still emitted (no target to invent)")
      (is (nil? (get (:headers ring) "Location"))
          "no Location header when no target key is present")
      (is (= "" (:body ring))))))

;; ===========================================================================
;; ssr-response->ring-response — non-redirect (body) path
;; ===========================================================================

(deftest body-response-defaults-status-200
  (testing "rf2-ynjts.14: a non-redirect response with no :status defaults
            to 200 and carries the supplied body verbatim"
    (let [ring (pipeline/ssr-response->ring-response
                 {:headers [["Content-Type" "text/html"]]}
                 "<p>hi</p>")]
      (is (= 200 (:status ring)) "absent :status → 200 default")
      (is (= "<p>hi</p>" (:body ring)) "body rides through verbatim"))))

(deftest body-response-nil-body-becomes-empty-string
  (testing "rf2-ynjts.14: a nil body materialises to the empty string, not
            nil (Ring bodies must be writable — `(or body \"\")`)"
    (let [ring (pipeline/ssr-response->ring-response
                 {:status 204 :headers []} nil)]
      (is (= 204 (:status ring)))
      (is (= "" (:body ring)) "nil body → empty string"))))

;; ===========================================================================
;; headers->ring-map+default-content-type — POSITIVE default-append +
;; nil-content-type paths (the suppression path is covered by
;; ring_test/content-type-default-no-duplicate-on-mixed-case)
;; ===========================================================================

(deftest content-type-default-appended-when-absent
  (testing "rf2-ynjts.14: pairs that declare no Content-Type get the default
            appended (the positive complement of the suppression test)"
    (let [result (headers/headers->ring-map+default-content-type
                   [["X-Custom" "v"]]
                   "text/html; charset=utf-8")]
      (is (= "text/html; charset=utf-8" (get result "Content-Type"))
          "default Content-Type appended when the pairs carry none")
      (is (= "v" (get result "X-Custom")) "other pairs survive the fold"))))

(deftest content-type-no-default-when-content-type-arg-nil
  (testing "rf2-ynjts.14: a nil `content-type` arg means NO default is
            appended even when the pairs declare none — the caller opted
            out of defaulting (the redirect path passes nil)"
    (let [result (headers/headers->ring-map+default-content-type
                   [["X-Custom" "v"]]
                   nil)]
      (is (not (contains? result "Content-Type"))
          "no Content-Type key when the default arg is nil and pairs carry none")
      (is (= "v" (get result "X-Custom"))))))

(deftest empty-pairs-with-nil-default-yields-empty-map
  (testing "rf2-ynjts.14: empty pairs + nil default → empty header map (no
            spurious keys)"
    (is (= {} (headers/headers->ring-map+default-content-type [] nil)))))

;; ===========================================================================
;; merge-pair-into-header-map — repeated names collapse into a vector
;;
;; The ns docstring promises multi-valued headers (Set-Cookie, Vary, Link)
;; round-trip via the string→vector→conj arms. The full-handler test only
;; exercises this through Set-Cookie; pin the fold contract for arbitrary
;; repeated names directly so all three arms (nil / string / vector) are
;; covered.
;; ===========================================================================

(deftest single-value-header-stays-scalar
  (testing "rf2-ynjts.14: a name seen once stays a scalar string (nil arm)"
    (is (= {"Vary" "Accept"}
           (headers/merge-pair-into-header-map {} ["Vary" "Accept"])))))

(deftest second-value-promotes-to-vector
  (testing "rf2-ynjts.14: a name seen twice promotes scalar → 2-vector
            (string arm)"
    (is (= {"Vary" ["Accept" "Cookie"]}
           (-> {}
               (headers/merge-pair-into-header-map ["Vary" "Accept"])
               (headers/merge-pair-into-header-map ["Vary" "Cookie"]))))))

(deftest third-value-conjs-onto-vector-preserving-order
  (testing "rf2-ynjts.14: a name seen three+ times conjs onto the vector,
            preserving per-name insertion order (vector arm — the
            load-bearing multi-valued-header round-trip)"
    (is (= {"Link" ["a" "b" "c"]}
           (-> {}
               (headers/merge-pair-into-header-map ["Link" "a"])
               (headers/merge-pair-into-header-map ["Link" "b"])
               (headers/merge-pair-into-header-map ["Link" "c"]))))))

(deftest header-fold-collapses-repeated-names-through-full-fold
  (testing "rf2-ynjts.14: the full fold collapses repeated names into a
            vector AND keeps singletons scalar in one pass — the contract
            the materialiser relies on for multi-valued headers"
    (let [result (headers/headers->ring-map+default-content-type
                   [["Set-Cookie" "a=1"]
                    ["Set-Cookie" "b=2"]
                    ["X-One" "only"]]
                   nil)]
      (is (= ["a=1" "b=2"] (get result "Set-Cookie"))
          "repeated Set-Cookie collapses to an ordered vector")
      (is (= "only" (get result "X-One"))
          "a singleton header stays a scalar string"))))

;; ===========================================================================
;; append-set-cookies — structured cookies → Set-Cookie pairs folded in
;; ===========================================================================

(deftest append-set-cookies-folds-multiple-into-vector
  (testing "rf2-ynjts.14: two structured cookies fold into a 2-vector under
            Set-Cookie, each serialised per RFC 6265"
    (let [result (headers/append-set-cookies
                   {}
                   [{:name "session" :value "abc"}
                    {:name "theme" :value "dark"}])
          sc     (get result "Set-Cookie")]
      (is (vector? sc) "two cookies → vector")
      (is (= 2 (count sc)))
      (is (some #(str/starts-with? % "session=abc") sc))
      (is (some #(str/starts-with? % "theme=dark") sc)))))

(deftest append-set-cookies-empty-is-noop
  (testing "rf2-ynjts.14: no cookies → header map unchanged"
    (is (= {"X" "y"} (headers/append-set-cookies {"X" "y"} [])))))

;; ===========================================================================
;; ssr-middleware — DEFAULT :match? (matches every GET; non-GET falls through)
;;
;; Every prior middleware test supplies an explicit :match?. The documented
;; default predicate had no coverage.
;; ===========================================================================

(defn- register-blank-app! []
  (rf/reg-event-fx :init/mw-blank {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-view* :pages/mw-blank (fn [] [:div "ssr body"])))

(deftest middleware-default-match-renders-get
  (testing "rf2-ynjts.14: with NO :match? supplied, the default predicate
            matches every GET — SSR renders, the wrapped handler is not
            called"
    (register-blank-app!)
    (let [wrapped-called (atom false)
          wrapped        (fn [_req] (reset! wrapped-called true)
                           {:status 204 :headers {} :body ""})
          app ((ssr-ring/ssr-middleware
                 {:on-create      [:init/mw-blank]
                  :root-view      [:pages/mw-blank]
                  :payload-policy :rf.ssr.payload/whole-app-db})
               wrapped)
          response (app {:uri "/" :request-method :get})]
      (is (= 200 (:status response)) "GET matched the default predicate → SSR rendered")
      (is (str/includes? (:body response) "ssr body"))
      (is (false? @wrapped-called) "the wrapped handler was NOT called for a GET"))))

(deftest middleware-default-match-falls-through-on-non-get
  (testing "rf2-ynjts.14: with NO :match? supplied, a non-GET request does
            NOT match the default predicate and falls through to the
            wrapped handler"
    (register-blank-app!)
    (let [wrapped-called (atom false)
          wrapped        (fn [_req] (reset! wrapped-called true)
                           {:status 201 :headers {} :body "from wrapped"})
          app ((ssr-ring/ssr-middleware
                 {:on-create      [:init/mw-blank]
                  :root-view      [:pages/mw-blank]
                  :payload-policy :rf.ssr.payload/whole-app-db})
               wrapped)]
      (doseq [method [:post :put :delete :head]]
        (reset! wrapped-called false)
        (let [response (app {:uri "/" :request-method method})]
          (is @wrapped-called
              (str method " request fell through to the wrapped handler"))
          (is (= 201 (:status response))
              (str method " response came from the wrapped handler, not SSR"))
          (is (= "from wrapped" (:body response))))))))
