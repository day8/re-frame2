(ns re-frame.ssr.ring.pipeline-materialiser-test
  "Unit coverage for the response materialiser + header-fold contracts
  that the full-handler / Jetty e2e tests exercise only indirectly.

  The end-to-end suites (`ring_test`, `ring_e2e_validator_test`,
  `ring_streaming_test`) prove the happy + error wire paths through the
  whole handler. They do NOT pin the small public materialiser fns in
  isolation; this namespace pins these documented branches directly:

    1. `ssr-response->ring-response` redirect-target resolution — the fn
       reads the canonical `:location` key only (there is no
       `(or location url to)` back-door). A redirect map keyed on `:location`
       emits the `Location` header; a map carrying ONLY a retired `:url` /
       `:to` spelling has no `:location`, so the materialiser treats it as a
       target-less redirect (no `Location` header, the no-target warning
       trace fires). The runtime `redirect-fx` is the loud gate — it throws
       `:rf.error/redirect-retired-target-key` before such a map ever reaches
       the accumulator — but the adapter is the last line for a hand-built /
       alt-host response, so it must NOT silently honour the retired keys.
       Pinned here directly.

    2. `headers->ring-map+content-type-override` paths — the
       override-strips-existing path (a non-nil override replaces any-casing
       Content-Type) is covered by `ring_test/content-type-override-
       replaces-any-casing`. The complementary contracts here:
         (a) the override IS set when the pairs declare no Content-Type,
         (b) a `nil` `content-type` arg is NO override — the folded map
             (runtime seed / app-set Content-Type) flows verbatim,
         (c) repeated header names collapse into a vector
             (`merge-pair-into-header-map`'s string→vector→conj arms),
       the last being the load-bearing multi-valued-header round-trip
       (Set-Cookie / Vary / Link) the ns docstring promises.

    3. `ssr-middleware` DEFAULT `:match?` — every other middleware test
       supplies an explicit `:match?`, so without these rows a regression
       in the documented default (matches every GET; non-GET falls
       through to the wrapped handler) would pass.

  These are pure / synchronous contracts. No Jetty, no streaming, no
  network — deterministic by construction. The header-fold + materialiser
  fns are public (`defn`, not `defn-`), so the tests call them directly
  rather than reaching through `requiring-resolve`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.headers :as rf.ssr.ring.headers]
            [re-frame.ssr.ring.pipeline :as rf.ssr.ring.pipeline]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ===========================================================================
;; ssr-response->ring-response — redirect target resolution (:location only)
;;
;; The canonical (and only) redirect target key is `:location` (EP-0007
;; one-name-per-fact); there is no `(or location url to)` back-door and no
;; runtime synonym. The materialiser reads `:location`
;; only; a redirect carrying ONLY a retired `:url` / `:to` spelling has no
;; target as far as the adapter is concerned (the no-target path fires). The
;; runtime is the loud gate (throws `:rf.error/redirect-retired-target-key`);
;; the adapter must simply NOT silently honour the retired keys.
;; ===========================================================================

(deftest redirect-ignores-retired-target-keys
  (testing "a redirect map carrying ONLY a retired :url or :to spelling (NOT
            :location) is treated as target-less by the materialiser, so no
            Location header is emitted. The runtime rejects these keys loudly
            before this point; the adapter's last-line behaviour must NOT
            silently resolve them."
    (doseq [[k status] [[:url 303] [:to 307]]]
      (let [ring (rf.ssr.ring.pipeline/ssr-response->ring-response
                   {:redirect {:status status k "/by-retired-key"}} nil)]
        (is (= status (:status ring))
            (str k ": the redirect map's :status still rides through"))
        (is (nil? (get (:headers ring) "Location"))
            (str k " is NOT resolved as the Location target"))))))

;; ===========================================================================
;; The redirect target REPLACES any existing Location, whatever its
;; casing.
;;
;; The materialiser folds application headers case-insensitively
;; (`merge-pair-into-header-map` collapses case variants under the FIRST-SEEN
;; spelling), so inserting the redirect target with a case-SENSITIVE
;; `(assoc "Location" target)` alone would, for an app that has already written
;; a `location` / `LOCATION` header, ship BOTH spellings — two conflicting
;; singleton headers under one logical name — leaving the HTTP adapter or
;; client to pick one, possibly the stale one. `docs/ssr/response.md` promises
;; case-insensitive header names, and Spec 011 §Redirect precedence gives the
;; redirect the last word. So the materialiser strips every casing with the
;; `strip-header` helper — the same rule `headers->ring-map+content-type-override`
;; applies to the `:content-type` override — before associating the canonical
;; spelling.
;; ===========================================================================

(deftest redirect-replaces-every-location-casing
  (testing "`location`, `LOCATION`, `Location`, `LoCaTiOn` — each is
            the same logical header, so each is replaced by the target"
    (doseq [spelling ["location" "LOCATION" "Location" "LoCaTiOn"]]
      (let [resp {:redirect {:status 303 :location "/new"}
                  :headers  [[spelling "/old"]]}
            ring (rf.ssr.ring.pipeline/ssr-response->ring-response resp nil)
            headers (:headers ring)
            location-keys (filter #(= "location" (str/lower-case (str %)))
                                  (keys headers))]
        (is (= 1 (count location-keys))
            (str "one Location entry for the " (pr-str spelling) " spelling"))
        (is (= ["/new"] (mapv #(get headers %) location-keys))
            (str "the " (pr-str spelling) " value was replaced by the target"))
        (is (not-any? #(= "/old" %) (vals headers))
            (str "no stale target survives under any key ("
                 (pr-str spelling) ")"))))))

(deftest redirect-location-replacement-preserves-unrelated-headers
  (testing "VACUITY: the strip is scoped to Location — unrelated
            headers, cookies, status and the empty body are untouched"
    (let [resp {:redirect {:status 302 :location "/new"}
                :headers  [["location" "/old"]
                           ["X-Trace" "abc"]
                           ["vary" "Accept"]
                           ["Vary" "Cookie"]]
                :cookies  [{:name "sid" :value "s1" :path "/"}]}
          ring (rf.ssr.ring.pipeline/ssr-response->ring-response resp nil)
          headers (:headers ring)]
      (is (= 302 (:status ring)))
      (is (= "" (:body ring)) "a redirect still has no body")
      (is (= "/new" (get headers "Location")))
      (is (= "abc" (get headers "X-Trace")) "unrelated header retained")
      (is (= ["Accept" "Cookie"] (get headers "vary"))
          "the unrelated case-insensitive fold is untouched")
      (is (some? (get headers "Set-Cookie")) "cookies retained")
      (is (str/includes? (str (get headers "Set-Cookie")) "sid=s1")))))

(deftest redirect-no-target-leaves-an-existing-location-alone
  (testing "VACUITY: the strip runs only where a target replaces it.
            A target-LESS redirect adds nothing, so it must remove nothing —
            stripping there would delete an app-set header and leave the 3xx
            with no Location at all, which is strictly worse than the
            malformed-but-warned shape the no-target branch already ships."
    (let [resp {:redirect {:status 302}
                :headers  [["location" "/app-set"]]}
          ring (rf.ssr.ring.pipeline/ssr-response->ring-response resp nil)]
      (is (= 302 (:status ring)))
      (is (= "/app-set" (get (:headers ring) "location"))
          "the app's own header survives a target-less redirect"))))

;; ===========================================================================
;; ssr-response->ring-response — non-redirect (body) path
;; ===========================================================================

(deftest body-response-nil-body-becomes-empty-string
  (testing "a nil body materialises to the empty string, not
            nil (Ring bodies must be writable — `(or body \"\")`)"
    (let [ring (rf.ssr.ring.pipeline/ssr-response->ring-response
                 {:status 204 :headers []} nil)]
      (is (= 204 (:status ring)))
      (is (= "" (:body ring)) "nil body → empty string"))))

;; ===========================================================================
;; HOST-SERIALISABILITY FAIL-CLOSED
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
  (testing "a float status (200.0) is not a valid Ring int → 500"
    (is (= 500 (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                          {:status 200.0 :headers []} "x"))))))

(deftest integer-status-passes-through-unchanged
  (testing "a genuine integer status is untouched by the
            fail-closed guard (no false-positive coercion)"
    (is (= 201 (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                          {:status 201 :headers []} "x"))))
    (is (= 302 (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                          {:redirect {:status 302 :location "/x"}} nil))))
    (is (= 200 (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                          {:headers []} "x")))
        "absent status still defaults to 200")
    (is (= 302 (:status (rf.ssr.ring.pipeline/ssr-response->ring-response
                          {:redirect {:location "/x"}} nil)))
        "absent redirect status still defaults to 302")))

;; ===========================================================================
;; fail-closed-status — the :warning trace on a non-integer status
;;
;; `non-integer-status-fails-closed-to-500` above pins the 500 OUTCOME; this
;; test pins the `:rf.ssr/ssr-non-integer-status` :warning trace the fail-closed
;; arm emits (`report-non-integer-status!` in pipeline.clj), as the sibling
;; `ssr-non-string-header-value` warning is pinned below and
;; `redirect-no-target` end-to-end. It mirrors the
;; `collect-non-string-header-warnings` listener pattern so the operator-facing
;; diagnostic is contract-pinned, not merely emitted. That one emit site also
;; fans the always-on record, so the one-report-per-rewrite and
;; no-false-positive rows in `status_rewrite_always_on_test` cover this trace
;; too.
;; ===========================================================================

(defn- collect-non-integer-status-warnings
  "Run `thunk` while listening for `:rf.ssr/ssr-non-integer-status` warning
  traces; return the collected events."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::non-integer-status-watch
      (fn [ev] (when (= :rf.ssr/ssr-non-integer-status (:operation ev))
                 (swap! traces conj ev))))
    (try
      (thunk)
      (finally
        (rf/unregister-listener! :trace ::non-integer-status-watch)))
    @traces))

(deftest non-integer-status-emits-exactly-one-fail-closed-warning
  (testing "a non-integer :status reaching the materialiser emits
            exactly one :warning trace naming the offending value-type + the
            fail-closed-to-500 recovery"
    (let [warnings (collect-non-integer-status-warnings
                     (fn []
                       (rf.ssr.ring.pipeline/ssr-response->ring-response
                         {:status "404"
                          :headers [["Content-Type" "text/html"]]}
                         "<p>x</p>")))]
      (is (= 1 (count warnings))
          "exactly one non-integer-status warning for one non-int status")
      (let [ev   (first warnings)
            tags (:tags ev)]
        (is (= :warning (:op-type ev)) "emitted at :warning severity")
        (is (= :ssr-ring/ssr-response->ring-response (:where tags))
            "the warning names the materialiser call site")
        (is (= "404" (:status tags))
            "the warning carries the offending status value")
        (is (= "java.lang.String" (:status-type tags))
            "the warning names the value's concrete type")
        ;; `:recovery` is hoisted from :tags to a top-level event slot per
        ;; Spec 009 §Core-field hoist (trace.cljc dissocs it from :tags).
        (is (= :failed-closed-to-500 (:recovery ev))
            "the warning carries the fail-closed recovery disposition")))))

(deftest non-string-redirect-location-coerced-to-string
  (testing "a non-string redirect :location (the fx is
            caller-trusted and `(str loc)` passes its shape gate, so a raw
            scalar can reach the accumulator) is coerced to a string in the
            emitted Location header"
    (let [ring (rf.ssr.ring.pipeline/ssr-response->ring-response
                 {:redirect {:status 302 :location 5}} nil)
          loc  (get (:headers ring) "Location")]
      (is (= 302 (:status ring)))
      (is (= "5" loc) "the non-string target is coerced via str"))))

;; ===========================================================================
;; merge-pair-into-header-map — repeated names collapse into a vector
;;
;; The ns docstring promises multi-valued headers (Set-Cookie, Vary, Link)
;; round-trip via the string→vector→conj arms.
;; ===========================================================================

(deftest repeated-non-string-value-coerced-and-does-not-wipe-header-map
  (testing "a repeated header name whose value
            is a non-string scalar (the runtime's set/append-header fxs gate
            CR/LF/NUL but do NOT coerce to string, and the fx-args :schema
            soft-passes off the optional schemas classpath) must (a) collapse
            into a 2-vector via the :else arm — NOT fall through the cond and
            return nil, which would silently wipe the ENTIRE accumulated
            header map — AND (b) be coerced to its string form so the Ring header
            value is a vector OF STRINGS, never of raw scalars"
    (is (= {"X-Count" ["5" "6"]}
           (-> {}
               (rf.ssr.ring.headers/merge-pair-into-header-map ["X-Count" 5])
               (rf.ssr.ring.headers/merge-pair-into-header-map ["X-Count" 6])))
        "two non-string values under one name collapse to a vector of strings")
    (let [result (rf.ssr.ring.headers/headers->ring-map+content-type-override
                   [["X-Count" 5]
                    ["X-Count" 6]
                    ["X-Other" "keep"]]
                   "text/html")]
      (is (= ["5" "6"] (get result "X-Count"))
          "the repeated non-string header survives as a 2-vector of strings")
      (is (= "keep" (get result "X-Other"))
          "headers folded AFTER the repeat are NOT wiped (no nil-map)")
      (is (= "text/html" (get result "Content-Type"))
          "the override Content-Type is set — the map was never nulled"))))

;; ===========================================================================
;; merge-pair-into-header-map — case-insensitive collapse
;;
;; HTTP header field names are case-insensitive (RFC 7230 §3.2), and the
;; ns docstring promises a case-insensitive name match. Pairs whose
;; names differ only by ASCII case are ONE logical header: they collapse
;; under the first-seen spelling with values in declaration order, exactly
;; like same-case repeats. `:rf.server/append-header` preserves the caller's
;; spelling, so mixed-case pairs are reachable through the public effect.
;; A case-SENSITIVE `(get m k)` fold would emit a separate map entry per
;; casing — a non-deterministic Ring header model.
;; ===========================================================================

(deftest mixed-case-names-collapse-into-one-first-seen-key
  (testing "pairs differing only by ASCII case are ONE logical
            header — they collapse under the first-seen spelling with values
            in declaration order (the case-insensitive fold)"
    (is (= {"Vary" ["Accept" "Origin" "Accept-Encoding"]}
           (-> {}
               (rf.ssr.ring.headers/merge-pair-into-header-map ["Vary" "Accept"])
               (rf.ssr.ring.headers/merge-pair-into-header-map ["vary" "Origin"])
               (rf.ssr.ring.headers/merge-pair-into-header-map ["VARY" "Accept-Encoding"])))
        "later `vary` / `VARY` fold under the first-seen `Vary` key; values stay
         in declaration order")))

(deftest mixed-case-content-type-collapses-under-nil-override
  (testing "mixed-case Content-Type with a nil
            handler override collapses to exactly one logical key carrying
            both values in order (vector semantics follow append-header)"
    (let [result (rf.ssr.ring.headers/headers->ring-map+content-type-override
                   [["content-type" "text/html"]
                    ["Content-Type" "application/json"]]
                   nil)
          ct-keys (filter (fn [k] (= "content-type" (str/lower-case (str k))))
                          (keys result))]
      (is (= 1 (count ct-keys))
          "exactly one logical content-type key with a nil override")
      (is (= ["text/html" "application/json"] (get result (first ct-keys)))
          "both values survive in declaration order under the first-seen key"))))

(deftest mixed-case-content-type-with-override-strips-every-casing
  (testing "a non-nil override still strips every
            prior spelling and emits one canonical `Content-Type` scalar"
    (let [result (rf.ssr.ring.headers/headers->ring-map+content-type-override
                   [["content-type" "text/html"]
                    ["Content-Type" "application/json"]]
                   "text/xml; charset=utf-8")
          ct-keys (filter (fn [k] (= "content-type" (str/lower-case (str k))))
                          (keys result))]
      (is (= 1 (count ct-keys)) "exactly one content-type key after override")
      (is (= "Content-Type" (first ct-keys)) "the canonical spelling is emitted")
      (is (= "text/xml; charset=utf-8" (get result "Content-Type"))
          "the override value wins — no casing survivor, no vector"))))

(deftest mixed-case-set-cookie-append-collapses-under-existing-key
  (testing "structured cookies appended to a
            pre-existing differently-cased Set-Cookie entry yield ONE logical
            key preserving every value in order"
    (let [result (rf.ssr.ring.headers/append-set-cookies
                   {"set-cookie" "pre=1"}
                   [{:name "session" :value "abc"}
                    {:name "theme" :value "dark"}])
          sc-keys (filter (fn [k] (= "set-cookie" (str/lower-case (str k))))
                          (keys result))
          sc      (get result (first sc-keys))]
      (is (= 1 (count sc-keys))
          "the lower-case `set-cookie` pre-existing key absorbs the cookies")
      (is (= "set-cookie" (first sc-keys)) "first-seen spelling is retained")
      (is (= 3 (count sc)) "the pre-existing value plus both cookies are present")
      (is (= "pre=1" (first sc)) "the pre-existing value stays first (declaration order)")
      (is (some #(str/starts-with? % "session=abc") sc))
      (is (some #(str/starts-with? % "theme=dark") sc)))))

;; ===========================================================================
;; merge-pair-into-header-map — dev-gated warning on a non-string value
;;
;; A non-string header value reaching the fold is host-dependent
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
  (testing "a non-string header value reaching the
            fold emits exactly one :warning trace naming the offending key +
            value-type; the value folds in COERCED to its string form (the
            fail-closed coercion — the wire value is always a string)"
    (let [warnings (collect-non-string-header-warnings
                     (fn []
                       (let [result (rf.ssr.ring.headers/merge-pair-into-header-map
                                      {} ["X-Count" 5])]
                         (is (= {"X-Count" "5"} result)
                             "the non-string value folds in coerced to a string"))))]
      (is (= 1 (count warnings)) "exactly one warning for one non-string value")
      (let [ev   (first warnings)
            tags (:tags ev)]
        (is (= :warning (:op-type ev)) "emitted at :warning severity")
        (is (= "X-Count" (:header tags)) "the warning names the offending header key")
        (is (= "java.lang.Long" (:value-type tags))
            "the warning names the value's concrete type")))))

(deftest string-header-value-emits-no-warning
  (testing "a string header value (the contract-compliant common
            path) emits NO warning"
    (let [warnings (collect-non-string-header-warnings
                     (fn []
                       (rf.ssr.ring.headers/merge-pair-into-header-map {} ["X-Custom" "v"])
                       (-> {}
                           (rf.ssr.ring.headers/merge-pair-into-header-map ["Vary" "Accept"])
                           (rf.ssr.ring.headers/merge-pair-into-header-map ["Vary" "Cookie"]))))]
      (is (= [] warnings)
          "no non-string-header-value warning for string-valued headers"))))

;; ===========================================================================
;; ssr-middleware — DEFAULT :match? (matches every GET; non-GET falls through)
;;
;; Every other middleware test supplies an explicit :match?, so these rows
;; are the documented default predicate's coverage.
;; ===========================================================================

(defn- register-blank-app! []
  (rf/reg-event :init/mw-blank {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-view* :pages/mw-blank (fn [] [:div "ssr body"])))

(deftest middleware-default-match-renders-get-and-passes-other-methods-through
  (register-blank-app!)
  (let [wrapped-called (atom false)
        wrapped        (fn [_req] (reset! wrapped-called true)
                         {:status 201 :headers {} :body "from wrapped"})
        app ((rf.ssr.ring/ssr-middleware
               {:initial-events [[:init/mw-blank]]
                :root-view      [(rf/view :pages/mw-blank)]
                :payload        :rf.ssr.payload/whole-app-db})
             wrapped)]
    (testing "with NO :match? supplied, the default predicate matches every
              GET — SSR renders, the wrapped handler is not called"
      (let [response (app {:uri "/" :request-method :get})]
        (is (= 200 (:status response)) "GET matched the default predicate → SSR rendered")
        (is (str/includes? (:body response) "ssr body"))
        (is (false? @wrapped-called) "the wrapped handler was NOT called for a GET")))
    (testing "with NO :match? supplied, a non-GET request does NOT match the
              default predicate and falls through to the wrapped handler"
      (doseq [method [:post :put :delete :head]]
        (reset! wrapped-called false)
        (let [response (app {:uri "/" :request-method method})]
          (is @wrapped-called
              (str method " request fell through to the wrapped handler"))
          (is (= 201 (:status response))
              (str method " response came from the wrapped handler, not SSR"))
          (is (= "from wrapped" (:body response))))))))
