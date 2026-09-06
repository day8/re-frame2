(ns re-frame.ssr-doc-example-projector-test
  "rf2-0aqd4 ACCEPTANCE — the `reg-error-projector` example printed in
  `docs/api/re-frame.ssr.md` is executed, not merely read.

  WHY THIS SUITE EXISTS. An API-page example is the artefact a reader
  COPIES, and nothing was checking it. The api-manifest doc gate
  (`re-frame.api-manifest.doc-api-check`) verifies that every public var
  HAS a docs/api entry — var existence, the APPEARANCE of documentation.
  The runtime suites (`re-frame.ssr-route-miss-404-production-test`,
  `re-frame.ssr-error-projector-substrate-test`) verify the BUILT-IN
  projector. Between those two sits the one projector a reader actually
  ships: the documented one. It drifted twice, silently, because no gate
  evaluated it.

    - rf2-0aqd4 as filed: the example returned two of the four locked
      `public-error-keys`, so `project-error` discarded it on EVERY error
      in favour of the locked generic-500. The page stated the closed-shape
      rule 22 lines below the example that broke it.
    - rf2-0aqd4 after the #7168 audit: the repaired example branched on
      `:rf.error/no-such-route` — a category the same page describes as
      caller misuse of `route-url` riding the DEV-gated trace stream, so a
      release build never reaches it. The example's only 404 arm was dead
      under production hardening, while the category that DOES answer an
      unroutable URL in production (`:rf.error/no-such-handler` with
      `[:tags :kind]` `:route`) fell through to the example's generic 500.

  Both defects are invisible to a reader and to every existing gate. Both
  are caught below by DRIVING the documented projector, so the test cannot
  drift from the page: the fn under test is read out of the markdown fence
  at run time rather than copied here.

  WHY 404-ON-THE-WIRE IS THE CONFORMANCE PROOF. A non-conformant projector
  return is replaced by `fallback-public-error` — status 500, message
  \"Something went wrong\". So observing the EXAMPLE'S OWN 404 and its own
  message on the response accumulator proves the example passed
  `public-error-shape?`; there is no path by which a malformed return
  produces them. That makes one assertion cover both the closed-key-set
  rule and the `:kind` gate's positive arm.

  POSTURE-INDEPENDENT. Every assertion reads the response accumulator and
  the pure projector; none touches the dev trace bus. The namespace
  therefore executes under `scripts/test-ssr-prod-gate.sh`'s real
  `-Dre-frame.debug=false` gate as well as in the ordinary lane — which is
  the posture that matters, since the drift this suite pins was
  specifically a 404 arm that only ever fired in dev."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; The fixture must NOT clear the always-on error-listener registry:
;; `re-frame.ssr` installs its `::error-projection` listener at ns-load
;; time and that listener IS the production status-projection path these
;; assertions exercise. Wiping it would turn every 404 below into a
;; vacuous 200. Same note as `re-frame.ssr-route-miss-404-production-test`.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; Reading the documented example out of the page
;; ---------------------------------------------------------------------------

(def ^:private api-page
  "`docs/api/re-frame.ssr.md`, anchored to a CLASSPATH RESOURCE rather than
  the working directory (rf2-ywrwkl; the same anchoring
  `re-frame.ssr-conformance-test` uses for the conformance corpus). A
  `(io/file \"../../docs/...\")` form would resolve correctly under the
  per-artefact gate run from `implementation/ssr/` and silently MIS-SCOPE
  under the combined `implementation/deps.edn :test` alias. This namespace's
  own source is on the test classpath, so five parents
  (`…_test.clj → re_frame → test → ssr → implementation → repo root`)
  reach the repo root from wherever the JVM was started."
  (let [res (io/resource "re_frame/ssr_doc_example_projector_test.clj")]
    (assert res
            (str "ssr-doc-example-projector-test cannot locate its own source "
                 "on the classpath — the ssr test/ dir must be on the test "
                 "classpath for the API page to be found."))
    (-> (io/file res)  ; …/ssr/test/re_frame/ssr_doc_example_projector_test.clj
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "docs" "api" "re-frame.ssr.md")
        .getCanonicalFile)))

(defn- fence
  "The text of the one ```clojure fence on the API page containing
  `needle`. Hard-errors on zero or many matches, so a rewritten page fails
  loudly rather than silently pinning nothing.

  Line endings are normalised first: the page is stored LF but checks out
  CRLF on a Windows dev box (`core.autocrlf`), and a `\\n`-only fence regex
  matches zero blocks there while passing on the Linux CI runner."
  [needle]
  (let [md   (str/replace (slurp api-page) "\r\n" "\n")
        hits (->> (re-seq #"(?s)```clojure\n(.*?)```" md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE ```clojure fence in " api-page
                 " containing " (pr-str needle) ", found " (count hits)
                 " — the pin has lost its anchor."))
    (first hits)))

(def ^:private documented-projector-fn
  "The `(fn [trace-event] …)` from the page's `reg-error-projector`
  example, read and evaluated. `read-string` drops the fence's `;;`
  comments; the body uses only `clojure.core`, so evaluating it in this
  namespace resolves every symbol."
  (delay
    (let [form (read-string (fence "rf/reg-error-projector :app/public-error"))]
      (is (= 'rf/reg-error-projector (first form))
          "the example still calls reg-error-projector")
      (binding [*ns* (find-ns 're-frame.ssr-doc-example-projector-test)]
        (eval (last form))))))

;; ---------------------------------------------------------------------------
;; Fixtures — the proven production path from the route-miss suite
;; ---------------------------------------------------------------------------

(defn- register-routes! []
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/not-found")
  (rf/reg-view* :pages/not-found (fn [] [:main.not-found [:h1 "No such page"]])))

(defn- server-frame-using-the-documented-projector []
  (rf/reg-error-projector :app/public-error @documented-projector-fn)
  (rf.frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id   :app/public-error
                :dev-error-detail? false}}))

;; ===========================================================================
;; (1) The documented projector is CONFORMANT and answers a real route miss
;; ===========================================================================

(deftest the-documented-example-answers-an-unroutable-url-with-its-own-404
  (testing "rf2-0aqd4: a reader who pastes the page's `reg-error-projector`
            example gets a projector that survives `public-error-shape?`
            (its own 404 reaches the wire rather than the locked
            generic-500) AND fires on the category that actually reports an
            unroutable URL in production."
    (register-routes!)
    (let [f (server-frame-using-the-documented-projector)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (let [{:keys [response public-error]} (rf.ssr/flush-response-result! f)]
        (is (= 404 (:status response))
            "the documented projector's 404 reached the response accumulator")
        (is (= :not-found (:code public-error))
            "and it is the example's OWN projection, not the locked fallback")
        (is (= "We couldn't find that page." (:message public-error))
            "the example's own message proves the four-key shape passed
             conformance — a non-conforming return would have been replaced
             by fallback-public-error's \"Something went wrong\"")
        (is (= rf.ssr/public-error-keys (set (keys public-error)))
            "exactly the four locked keys, none extra (:dev-error-detail?
             is false, so the runtime appended no :details)")))))

;; ===========================================================================
;; (2) The documented projector carries the `:kind :route` gate
;; ===========================================================================

(deftest the-documented-example-gates-its-404-on-kind-route
  (testing "rf2-0aqd4 (#7168 audit): `:rf.error/no-such-handler` covers three
            misses discriminated by `:kind`. An example that branched on
            `:operation` alone would answer 404 for an event id the server
            forgot to register — telling the client its URL was wrong when
            the server was. Driven through a real dispatch, then unit-tested
            on the extracted fn so a regression names the arm."
    (register-routes!)
    (let [f (server-frame-using-the-documented-projector)]
      (rf/dispatch-sync [:never/registered] {:frame f})
      (is (= 500 (:status (rf.ssr/flush-response! f)))
          "an unregistered EVENT dispatch is a server defect — generic 500"))
    (let [project @documented-projector-fn]
      (is (= 404 (:status (project {:operation :rf.error/no-such-handler
                                    :tags      {:kind :route}})))
          ":kind :route → 404")
      (is (= 500 (:status (project {:operation :rf.error/no-such-handler
                                    :tags      {:kind :event}})))
          ":kind :event → 500")
      (is (= 500 (:status (project {:operation :rf.error/no-such-handler
                                    :tags      {:kind :frame}})))
          ":kind :frame → 500")
      (is (= 500 (:status (project {:operation :rf.error/no-such-handler
                                    :tags      {}})))
          "no :kind → 500; the 404 arm is opt-in on the discriminator"))))

;; ===========================================================================
;; (3) The `project-error` example's `;; =>` result comment is the real one
;; ===========================================================================

(deftest the-project-error-example-result-comment-matches-the-runtime
  (testing "rf2-0aqd4 acceptance 2: the page's `project-error` example claims
            a return value in a `;; =>` comment. It is the default
            projector's actual 404 projection — all four locked keys,
            `:retryable?` included (it was the key the original filing found
            missing)."
    (let [claimed (-> (fence "ssr/project-error :rf/default trace-event")
                      (->> (re-find #"(?m)^\s*;;\s*=>\s*(\{.*\})\s*$"))
                      second
                      edn/read-string)]
      (is (some? claimed) "the example still carries a `;; =>` result comment")
      (is (= rf.ssr/public-error-keys (set (keys claimed)))
          "the documented result names exactly the four locked keys")
      (is (= claimed
             (rf.ssr/default-error-projector-fn {:operation :rf.error/no-such-handler
                                              :tags      {:kind :route}}))
          "and it is byte-for-byte what the default projector returns for the
           route miss the example describes"))))
