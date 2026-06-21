(ns re-frame.ssr.ring.pipeline-materialiser-test
  "Unit coverage for the response materialiser + header-fold contracts
  that the full-handler / Jetty e2e tests exercise only indirectly
  (rf2-ynjts.14 testing-review).

  The end-to-end suites (`ring_test`, `ring_e2e_validator_test`,
  `ring_streaming_test`) prove the happy + error wire paths through the
  whole handler. They do NOT pin the small public materialiser fns in
  isolation, so several documented branches were untested:

    1. `ssr-response->ring-response` redirect-target resolution — the fn
       reads the canonical `:location` key only (rf2-vngir pruned the
       `(or location url to)` back-door). A redirect map keyed on `:location`
       emits the `Location` header; a map carrying ONLY a retired `:url` /
       `:to` spelling has no `:location`, so the materialiser treats it as a
       target-less redirect (no `Location` header, the no-target warning
       trace fires). The runtime `redirect-fx` is the loud gate — it throws
       `:rf.error/redirect-retired-target-key` before such a map ever reaches
       the accumulator — but the adapter is the last line for a hand-built /
       alt-host response, so it must NOT silently honour the retired keys.
       Pinned here directly.

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
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

;; ===========================================================================
;; ssr-response->ring-response — redirect target resolution (:location only)
;;
;; rf2-vngir: the canonical (and only) redirect target key is `:location`.
;; The `(or location url to)` back-door was pruned along with the runtime
;; synonyms (EP-0007 one-name-per-fact). The materialiser reads `:location`
;; only; a redirect carrying ONLY a retired `:url` / `:to` spelling has no
;; target as far as the adapter is concerned (the no-target path fires). The
;; runtime is the loud gate (throws `:rf.error/redirect-retired-target-key`);
;; the adapter must simply NOT silently honour the retired keys.
;; ===========================================================================

(deftest redirect-resolves-location-key
  (testing "a redirect map keyed by :location emits a Location header"
    (let [resp {:redirect {:status 302 :location "/by-location"}}
          ring (pipeline/ssr-response->ring-response resp nil)]
      (is (= 302 (:status ring)))
      (is (= "/by-location" (get (:headers ring) "Location")))
      (is (= "" (:body ring)) "redirect has no body"))))

(deftest redirect-ignores-retired-url-spelling-no-location-header
  (testing "rf2-vngir: a redirect map carrying ONLY the retired :url spelling
            (NOT :location) is treated as target-less by the materialiser —
            the canonical key was pruned, so no Location header is emitted.
            The runtime rejects :url loudly before this point; the adapter's
            last-line behaviour must NOT silently resolve the retired key."
    (let [resp {:redirect {:status 303 :url "/by-url"}}
          ring (pipeline/ssr-response->ring-response resp nil)]
      (is (= 303 (:status ring)) "the redirect map's :status still rides through")
      (is (nil? (get (:headers ring) "Location"))
          ":url is NOT resolved as the Location target — retired spelling"))))

(deftest redirect-ignores-retired-to-spelling-no-location-header
  (testing "rf2-vngir: a redirect map carrying ONLY the retired :to spelling
            (NOT :location) likewise emits no Location header"
    (let [resp {:redirect {:status 307 :to "/by-to"}}
          ring (pipeline/ssr-response->ring-response resp nil)]
      (is (= 307 (:status ring)))
      (is (nil? (get (:headers ring) "Location"))
          ":to is NOT resolved as the Location target — retired spelling"))))

(deftest redirect-location-only-resolution-ignores-retired-co-keys
  (testing "rf2-vngir: when :location is present alongside retired :url / :to
            keys, ONLY :location is resolved (the retired keys are inert at
            the materialiser — :location is the canonical target)"
    (is (= "/loc"
           (get (:headers (pipeline/ssr-response->ring-response
                            {:redirect {:status 302
                                        :location "/loc" :url "/url" :to "/to"}}
                            nil))
                "Location"))
        ":location is resolved; the co-present retired keys are ignored")))

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
;; HOST-SERIALISABILITY FAIL-CLOSED (rf2-v0qbng)
;;
;; The `:rf.server/*` fx-args :schema boundary (set-status-args = :int,
;; set-header-args :value :string, …) SOFT-PASSES when the optional schemas
;; artefact is absent from the production classpath (Spec 010 §Recommended
;; soft-pass) — the DEFAULT ssr-ring runtime. The materialiser is the last
;; line: a non-int status / non-string header value / non-string Location
;; that slipped past the (optional) fx-args gate must NOT produce a Ring
;; response carrying a non-Ring type, or Jetty/http-kit may reject /
;; mis-serialise it WHILE committing — past the :on-error recovery point.
;;
;; These tests assert the wire-correct outcome with NO schemas dependency:
;; status is always an int, header values are always strings or vectors of
;; strings, the Location value is always a string.
;; ===========================================================================

(deftest non-integer-status-fails-closed-to-500
  (testing "rf2-v0qbng: a string :status (what a soft-passed
            `:rf.server/set-status \"404\"` leaves on the accumulator) does
            NOT reach the wire as a non-int — the materialiser fails closed
            to a valid 500 Ring response"
    (let [ring (pipeline/ssr-response->ring-response
                 {:status "404" :headers [["Content-Type" "text/html"]]}
                 "<p>x</p>")]
      (is (integer? (:status ring))
          "the Ring :status is an integer regardless of accumulator garbage")
      (is (= 500 (:status ring)) "a non-int status fails closed to 500")))
  (testing "rf2-v0qbng: a non-integer redirect :status also fails closed"
    (let [ring (pipeline/ssr-response->ring-response
                 {:redirect {:status "302" :location "/ok"}} nil)]
      (is (integer? (:status ring)) "redirect :status is an integer")
      (is (= 500 (:status ring)) "non-int redirect status → 500")))
  (testing "rf2-v0qbng: a float status (200.0) is not a valid Ring int → 500"
    (is (= 500 (:status (pipeline/ssr-response->ring-response
                          {:status 200.0 :headers []} "x"))))))

(deftest integer-status-passes-through-unchanged
  (testing "rf2-v0qbng: a genuine integer status is untouched by the
            fail-closed guard (no false-positive coercion)"
    (is (= 201 (:status (pipeline/ssr-response->ring-response
                          {:status 201 :headers []} "x"))))
    (is (= 302 (:status (pipeline/ssr-response->ring-response
                          {:redirect {:status 302 :location "/x"}} nil))))
    (is (= 200 (:status (pipeline/ssr-response->ring-response
                          {:headers []} "x")))
        "absent status still defaults to 200")
    (is (= 302 (:status (pipeline/ssr-response->ring-response
                          {:redirect {:location "/x"}} nil)))
        "absent redirect status still defaults to 302")))

(deftest non-string-header-value-coerced-in-materialiser
  (testing "rf2-v0qbng: a non-string header value on the accumulator (what a
            soft-passed `:rf.server/set-header {:name \"X-Count\" :value 5}`
            leaves) is coerced to a string in the emitted Ring header map —
            the value is never a raw scalar on the wire"
    (let [ring (pipeline/ssr-response->ring-response
                 {:status 200 :headers [["X-Count" 5]
                                        ["X-Flag" true]
                                        ["X-Kw" :enabled]]}
                 "<p>x</p>")
          hdrs (:headers ring)]
      (is (= "5" (get hdrs "X-Count")) "number → string")
      (is (= "true" (get hdrs "X-Flag")) "boolean → string")
      (is (= ":enabled" (get hdrs "X-Kw")) "keyword → string")
      (is (every? (fn [[_ v]] (or (string? v)
                                  (and (vector? v) (every? string? v))))
                  hdrs)
          "EVERY emitted header value is a string or vector-of-strings"))))

(deftest non-string-redirect-location-coerced-to-string
  (testing "rf2-v0qbng: a non-string redirect :location (the fx is
            caller-trusted and `(str loc)` passes its shape gate, so a raw
            scalar can reach the accumulator) is coerced to a string in the
            emitted Location header"
    (let [ring (pipeline/ssr-response->ring-response
                 {:redirect {:status 302 :location 5}} nil)
          loc  (get (:headers ring) "Location")]
      (is (= 302 (:status ring)))
      (is (string? loc) "the Location header value is a string")
      (is (= "5" loc) "the non-string target is coerced via str"))))

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

(deftest repeated-non-string-value-coerced-and-does-not-wipe-header-map
  (testing "rf2-v0qbng (was rf2-5t8mr.14): a repeated header name whose value
            is a non-string scalar (the runtime's set/append-header fxs gate
            CR/LF/NUL but do NOT coerce to string, and the fx-args :schema
            soft-passes off the optional schemas classpath) must (a) collapse
            into a 2-vector via the :else arm — NOT fall through the cond and
            return nil, which silently wiped the ENTIRE accumulated header
            map — AND (b) be coerced to its string form so the Ring header
            value is a vector OF STRINGS, never of raw scalars"
    (is (= {"X-Count" ["5" "6"]}
           (-> {}
               (headers/merge-pair-into-header-map ["X-Count" 5])
               (headers/merge-pair-into-header-map ["X-Count" 6])))
        "two non-string values under one name collapse to a vector of strings")
    (let [result (headers/headers->ring-map+default-content-type
                   [["X-Count" 5]
                    ["X-Count" 6]
                    ["X-Other" "keep"]]
                   "text/html")]
      (is (= ["5" "6"] (get result "X-Count"))
          "the repeated non-string header survives as a 2-vector of strings")
      (is (every? string? (get result "X-Count"))
          "every member of the multi-value vector is a string (Ring contract)")
      (is (= "keep" (get result "X-Other"))
          "headers folded AFTER the repeat are NOT wiped (no nil-map)")
      (is (= "text/html" (get result "Content-Type"))
          "the defaulted Content-Type survives — the map was never nulled"))))

;; ===========================================================================
;; merge-pair-into-header-map — dev-gated warning on a non-string value
;;
;; rf2-b0jlr: a non-string header value reaching the fold is host-dependent
;; and almost certainly a caller bug (Ring header values must be strings).
;; The fold still tolerates it (the :else arm), but it surfaces a dev-gated
;; :warning trace naming the offending key + value-type rather than silently
;; coercing or passing it through. A string value emits NO warning.
;; ===========================================================================

(defn- collect-non-string-header-warnings
  "Run `thunk` while listening for `:rf.ssr/ssr-non-string-header-value`
  warning traces; return the collected events."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::non-string-header-watch
      (fn [ev] (when (= :rf.ssr/ssr-non-string-header-value (:operation ev))
                 (swap! traces conj ev))))
    (try
      (thunk)
      (finally
        (rf/unregister-listener! :trace ::non-string-header-watch)))
    @traces))

(deftest non-string-header-value-emits-exactly-one-dev-warning
  (testing "rf2-b0jlr + rf2-v0qbng: a non-string header value reaching the
            fold emits exactly one :warning trace naming the offending key +
            value-type; the value folds in COERCED to its string form (the
            fail-closed coercion — the wire value is always a string)"
    (let [warnings (collect-non-string-header-warnings
                     (fn []
                       (let [result (headers/merge-pair-into-header-map
                                      {} ["X-Count" 5])]
                         (is (= {"X-Count" "5"} result)
                             "the non-string value folds in coerced to a string"))))]
      (is (= 1 (count warnings)) "exactly one warning for one non-string value")
      (let [ev   (first warnings)
            tags (:tags ev)]
        (is (= :rf.ssr/ssr-non-string-header-value (:operation ev)))
        (is (= :warning (:op-type ev)) "emitted at :warning severity")
        (is (= "X-Count" (:header tags)) "the warning names the offending header key")
        (is (= "java.lang.Long" (:value-type tags))
            "the warning names the value's concrete type")))))

(deftest string-header-value-emits-no-warning
  (testing "rf2-b0jlr: a string header value (the contract-compliant common
            path) emits NO warning"
    (let [warnings (collect-non-string-header-warnings
                     (fn []
                       (headers/merge-pair-into-header-map {} ["X-Custom" "v"])
                       (-> {}
                           (headers/merge-pair-into-header-map ["Vary" "Accept"])
                           (headers/merge-pair-into-header-map ["Vary" "Cookie"]))))]
      (is (= [] warnings)
          "no non-string-header-value warning for string-valued headers"))))

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
  (rf/reg-event :init/mw-blank {:platforms #{:server}} (fn [_ _] {}))
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
                 {:initial-events      [[:init/mw-blank]]
                  :root-view      [:pages/mw-blank]
                  :payload :rf.ssr.payload/whole-app-db})
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
                 {:initial-events      [[:init/mw-blank]]
                  :root-view      [:pages/mw-blank]
                  :payload :rf.ssr.payload/whole-app-db})
               wrapped)]
      (doseq [method [:post :put :delete :head]]
        (reset! wrapped-called false)
        (let [response (app {:uri "/" :request-method method})]
          (is @wrapped-called
              (str method " request fell through to the wrapped handler"))
          (is (= 201 (:status response))
              (str method " response came from the wrapped handler, not SSR"))
          (is (= "from wrapped" (:body response))))))))
