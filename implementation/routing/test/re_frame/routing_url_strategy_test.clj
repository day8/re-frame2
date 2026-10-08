(ns re-frame.routing-url-strategy-test
  "URL-strategy seam tests: the pure `:encode` / `:decode` legs of the two
  shipped strategies — `history-url-strategy` (default, path-form) and
  `hash-url-strategy` (`#`-prefixed) — and of the `with-base-path` combinator,
  the registration-time preflight, and the frame-config resolution the consult
  points share. Per Spec 012 §URL strategies. The CLJS-only side-effecting legs
  are driven in `routing_url_strategy_cljs_test.cljs`.

  ## Posture split

  The fail-loud validation seam is FRAME CONSTRUCTION:
  `preflight-frame-config!` runs `validate-url-strategy!` UNCONDITIONALLY at
  `re-frame.frame/upsert-frame!`, the sole `frames`-store config writer. The
  preflight tests therefore carry no posture guard and run in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  The CONSULT path is a trusted read: `url-strategy-from-config` pays no
  per-render validation, and all that sits there is a
  `(when rf.interop/debug-enabled? …)` tripwire, DCE'd from production CLJS
  bundles and dead on a JVM started with `-Dre-frame.debug=false`.
  `custom-url-strategy-missing-leg-tripwire` therefore branches on
  `rf.interop/debug-enabled?`: the dev arm asserts the tripwire fails loud, and
  the production arm asserts the read returns the declared value verbatim,
  throwing nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing]
            [re-frame.routing.strategy :as rf.routing.strategy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest hash-encode-prefixes-hash
  (doseq [[path href] [["/"                   "#/"]
                       ["/articles/42?q=milk" "#/articles/42?q=milk"]
                       ["#/active"            "#/active"] ; already hashed: never double-hashed
                       [nil                   "#/"]]]
    (is (= href (rf.routing.strategy/hash-encode path)) (pr-str path))))

(deftest custom-url-strategy-missing-leg-tripwire
  (testing "the ungated preflight counts a non-callable leg as missing"
    (is (= [:encode]
           (:missing (thrown-data #(rf.routing.strategy/preflight-frame-config!
                                     :t/owner
                                     {:url-strategy {:encode "not-a-fn" :decode (constantly "/")}}))))))
  (let [half {:decode (constantly "/")}]
    (if rf.interop/debug-enabled?
      (testing "dev: the consult tripwire fails loud, naming the missing leg"
        (is (= {:rf.error/id :rf.error/invalid-url-strategy :missing [:encode]}
               (select-keys (thrown-data #(rf.routing.strategy/url-strategy-from-config
                                            {:url-strategy half}))
                            [:rf.error/id :missing]))))
      (testing "production: the consult is a trusted read, returning the declared value verbatim"
        (is (identical? half (rf.routing.strategy/url-strategy-from-config {:url-strategy half})))))))

;; ---- with-base-path combinator --------------------------------------------

(deftest with-base-path-prefixes-the-base-on-encode
  (testing "the base is re-added to every encoded href, the app root included,
            normalised to one leading and no trailing slash"
    (doseq [[base path href] [["/realworld"  "/active" "/realworld/active"]
                              ["/realworld"  "/"       "/realworld/"]
                              ["realworld"   "/active" "/realworld/active"]
                              ["/realworld/" "/active" "/realworld/active"]]]
      (is (= href ((:encode (rf.routing.strategy/with-base-path
                              rf.routing.strategy/history-url-strategy base))
                   path))
          (str base " " path)))))

(deftest with-base-path-strips-mount-root-before-query-and-fragment
  (testing "a URL is under base /app only when the base is followed by the end
            of the string, `/`, `?` or `#`; any other URL is returned unchanged"
    (doseq [[url expected] [["/app?tab=all"         "/?tab=all"]
                            ["/app#section"         "/#section"]
                            ["/app"                 "/"]
                            ["/app/items/ok"        "/items/ok"]
                            ["/application?tab=all" "/application?tab=all"] ; a sibling sharing the prefix
                            ["/xyz/path"            "/xyz/path"]]]          ; not under the base, `/` at its length
      (is (= expected (rf.routing.strategy/strip-base-path "/app" url)) url))))

(deftest decode-inverts-encode-for-every-shipped-form
  (let [hash+app (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy "/app")]
    (testing "(decode (encode p)) = p — history, hash, and both based forms"
      (doseq [[label {:keys [encode decode]}]
              {"history"        rf.routing.strategy/history-url-strategy
               "hash"           rf.routing.strategy/hash-url-strategy
               "history + /app" (rf.routing.strategy/with-base-path
                                  rf.routing.strategy/history-url-strategy "/app")
               "hash + /app"    hash+app}
              p ["/" "/users/42?tab=2" "/users/42#section" "/app/item"]]
        (is (= p (decode (encode p))) (str label ": " p))))
    (testing "the based hash form puts the base outside the fragment, so its
              decode (the /app/item round trip above) never strips it twice"
      (is (= "/app#/app/item" ((:encode hash+app) "/app/item"))))
    (testing "hash :decode reads only what follows the first `#`"
      (is (= ["/" "/" "/active" "/users/7#section"]
             (map rf.routing.strategy/hash-decode ["/" "/#" "/#active" "/app#/users/7#section"]))))))

(deftest with-base-path-blank-base-is-a-no-op
  (doseq [base [nil "  "]]
    (is (identical? rf.routing.strategy/hash-url-strategy
                    (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy base))
        (pr-str base))))

;; ---- registration-time frame-config preflight ----------------------------

(deftest preflight-explicit-nil-url-strategy-is-malformed
  (testing "an EXPLICIT nil :url-strategy is a present declaration and fails
            loud — presence semantics; only omission selects the default"
    (is (= {:rf.error/id :rf.error/invalid-url-strategy :frame :t/owner}
           (select-keys (thrown-data #(rf.routing.strategy/preflight-frame-config!
                                        :t/owner {:url-bound? true :url-strategy nil}))
                        [:rf.error/id :frame])))))

(deftest make-frame-engine-rejects-malformed-strategy-before-any-write
  (testing "rf.frame/upsert-frame! preflights a declared :url-strategy before
            any write: a malformed first registration throws, naming the frame
            and the missing leg, and leaves no frame record"
    (is (= {:rf.error/id :rf.error/invalid-url-strategy :frame :ktmto9/bad-jvm :missing [:encode]}
           (select-keys (thrown-data #(rf.frame/upsert-frame! :ktmto9/bad-jvm
                                                              {:url-bound?   true
                                                               :url-strategy {:decode (constantly "/")}}))
                        [:rf.error/id :frame :missing])))
    (is (nil? (rf.frame/frame-meta :ktmto9/bad-jvm)))))

(deftest make-frame-missing-routing-artefact-fails-loud
  (testing "declaring :url-strategy while the :routing/preflight-frame-config!
            hook is unpublished fails loud and seats no frame — storing a
            strategy nobody can validate is worse than a dependency error"
    (try
      (rf.late-bind/set-fn! :routing/preflight-frame-config! nil)
      (is (= {:rf.error/id :rf.error/routing-artefact-missing :frame :ktmto9/no-artefact}
             (select-keys (thrown-data #(rf.frame/upsert-frame! :ktmto9/no-artefact
                                                                {:url-bound?   true
                                                                 :url-strategy rf.routing.strategy/history-url-strategy}))
                          [:rf.error/id :frame])))
      (is (nil? (rf.frame/frame-meta :ktmto9/no-artefact)))
      (finally
        (rf.late-bind/set-fn! :routing/preflight-frame-config!
                              rf.routing.strategy/preflight-frame-config!)))))

(deftest url-strategy-for-frame-id-reads-frames-store
  (testing "the frame's declared strategy, else the history default — for a
            frame declaring none, an unregistered frame and a nil frame id"
    (rf/init! rf.substrate.plain-atom/adapter)
    (try
      (rf.frame/upsert-frame! :test/hash-owner
                              {:url-bound? true :url-strategy rf.routing.strategy/hash-url-strategy})
      (rf.frame/upsert-frame! :test/plain {:url-bound? true})
      (doseq [[frame-id strategy] [[:test/hash-owner        rf.routing.strategy/hash-url-strategy]
                                   [:test/plain             rf.routing.strategy/history-url-strategy]
                                   [:test/never-registered  rf.routing.strategy/history-url-strategy]
                                   [nil                     rf.routing.strategy/history-url-strategy]]]
        (is (identical? strategy (rf.routing.strategy/url-strategy-for-frame-id frame-id))
            (pr-str frame-id)))
      (finally
        (rf.frame/destroy-frame! :test/hash-owner)
        (rf.frame/destroy-frame! :test/plain)))))
