(ns re-frame.ssr.ring.status-rewrite-always-on-test
  "rf2-gblft — the Ring materialiser's fail-closed `:status` rewrite reports
  itself in PRODUCTION.

  ## What was wrong

  `re-frame.ssr.ring.pipeline/fail-closed-status` turns a non-integer `:status`
  on the resolved response accumulator into a 500. That is the right call — Ring
  statuses must be integers, `\"404\"` has no faithful coercion, and a malformed
  map is rejected by Jetty/http-kit AFTER the handler returned, past the
  `:on-error` recovery point. But the rewrite changes the answer the app gave,
  and until this bead its ONLY signal was
  `trace/emit! :warning :rf.ssr/ssr-non-integer-status` — the DEV bus. Measured
  under the real gate (`clojure -J-Dre-frame.debug=false -M:test`, never a
  `with-redefs` rebind, which cannot reach a load-time gate):

      warning count under -Dre-frame.debug=false : 0
      always-on records                          : []

  So on a production JVM an operator saw a 500 with no record of why on either
  axis.

  ## What still reaches the rewrite (re-measured after rf2-dtpfv / PR #7204)

  #7204 made the reserved `:rf.server/*` fx guard their own args in EVERY build,
  which cut the framework's own feeder into this arm. Measured at
  `ssr/get-response` under the real gate AFTER that change:

      [:rf.server/set-status \"not-an-int\"]              → :status 500 (Long)
      [:rf.server/redirect {:location \"/ok\"
                            :status \"302\"}]              → :redirect nil

  — the guard throws, containment fans `:rf.error/fx-handler-exception`, and the
  SSR projector stamps a 500. No `:rf.server/*` fx can put a non-integer status
  on the accumulator any more.

  What is still LIVE is the path the rf2-dtpfv ruling explicitly reserved this
  net for: a HOST that hand-builds the accumulator (the public
  `re-frame.ssr.response/swap-response!`) or calls the public materialiser with
  its own response map. `ssr/get-response` is a documented host-adapter surface,
  so that is a real caller — which is exactly why a silent backstop is not good
  enough. The arm is a backstop, not dead code.

  ## Why this namespace is separate from `pipeline_materialiser_test`

  Every assertion here is POSTURE-INDEPENDENT: the always-on record must fan in
  a dev build and a `-Dre-frame.debug=false` build alike, so this namespace runs
  green under BOTH and can be mutation-proved under the real gate. Its sibling
  `pipeline-materialiser-test` keeps the DEV-trace assertions, which are
  posture-dependent by construction (`trace/emit!` is gated).

  The one exception is [[the-two-axes-split-by-posture]], which derives its
  expectation FROM `interop/debug-enabled?` rather than assuming one — it is the
  test that proves the two axes are genuinely different channels rather than one
  channel counted twice."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

(def ^:private status-rewrite-category
  :rf.error/ssr-ring-response-status-invalid)

(def ^:private expected-record-slots
  "The CLOSED key set the always-on record must carry — the test-side mirror of
  `re-frame.ssr.ring.pipeline/status-defect-record-slots` (private, so it is
  restated here rather than reached through `requiring-resolve`; the two are
  pinned equal by [[record-key-set-is-closed]] failing loudly on any drift).

  A slot added to the record later reaches Sentry / Datadog, so the pin is `=`
  and not `clojure.set/subset?`."
  #{:error :frame :time :where :status-type :reason :recovery})

(defn- collect-error-records
  "Run `thunk` while listening on the ALWAYS-ON `:errors` stream (the
  `register-error-listener!` axis — production-survivable, NOT the dev trace
  bus). Returns the records fanned during `thunk`."
  [thunk]
  (let [records (atom [])]
    (rf/register-listener! :errors ::status-rewrite-watch
      (fn [record] (swap! records conj record)))
    (try
      (thunk)
      (finally
        (rf/unregister-listener! :errors ::status-rewrite-watch)))
    @records))

(defn- collect-status-rewrite-records
  "The [[collect-error-records]] output narrowed to this bead's category, so an
  unrelated always-on record fanned by the fixture cannot green or red a count
  assertion."
  [thunk]
  (filterv #(= status-rewrite-category (:error %)) (collect-error-records thunk)))

;; ===========================================================================
;; The promotion itself
;; ===========================================================================

(deftest status-rewrite-reports-on-the-always-on-axis
  (testing "rf2-gblft: a non-integer :status rewritten to 500 fans a record on
            the ALWAYS-ON error-emit axis — in every build. Before this bead the
            rewrite reported only on the dev trace bus, so a production JVM
            answered 500 in complete silence."
    (let [ring    (atom nil)
          records (collect-status-rewrite-records
                    (fn []
                      (reset! ring (pipeline/ssr-response->ring-response
                                     {:status "404" :headers [["content-type" "text/html"]]}
                                     "<p>x</p>"))))]
      (is (= 1 (count records))
          "exactly one always-on record for one rewrite")
      (is (= 500 (:status @ring))
          "the wire outcome is unchanged — promotion adds a record, not a status")
      (is (= "<p>x</p>" (:body @ring))
          "and does not disturb the rest of the materialised response"))))

(deftest record-key-set-is-closed
  (testing "rf2-gblft: the always-on record carries EXACTLY the closed slot set
            it is built FROM. Pinned with `=`, not `subset?` — this record
            reaches an off-box shipper, so a slot someone adds later must red
            here rather than ship."
    (let [record (first (collect-status-rewrite-records
                          (fn [] (pipeline/ssr-response->ring-response
                                   {:status "404" :headers []} "x"))))]
      (is (some? record) "a record was fanned")
      (is (= expected-record-slots (set (keys record)))
          "the record's key set is exactly the closed set")
      (is (= status-rewrite-category (:error record)))
      (is (nil? (:frame record))
          "FRAMELESS by design — the materialiser is a pure map→map fn with no
           frame argument, and an ambient read would populate the slot on the
           error arm while leaving it nil on the very path this bead is about")
      (is (integer? (:time record)) "the union record's emit instant")
      (is (= :ssr-ring/ssr-response->ring-response (:where record))
          "the call site, as a constant keyword")
      (is (= "java.lang.String" (:status-type record))
          "the offending value's CLASS NAME — program structure, not app data")
      (is (= :non-integer-status (:reason record))
          ":reason is a closed framework keyword, never prose (rf2-6jqa8) —
           free prose on this axis is how raw material finds its way back in")
      (is (= :failed-closed-to-500 (:recovery record))))))

(deftest the-record-never-carries-the-offending-value
  (testing "rf2-gblft / the rf2-s3n6h precedent: the raw `:status` is
            DEV-TRACE-ONLY. A response status is caller-supplied and unbounded —
            it can be any object at all — so it has no place on a record that is
            shipped off-box unredacted. Asserted by NAME, and again by scanning
            every value, so a rename cannot quietly reintroduce it."
    (let [secret "sekrit-session-token"
          record (first (collect-status-rewrite-records
                          (fn [] (pipeline/ssr-response->ring-response
                                   {:status secret :headers []} "x"))))]
      (is (some? record))
      (is (not (contains? record :status))
          "the raw offending value is absent from the record BY NAME")
      (is (not-any? #(= secret %) (vals record))
          "and absent from every slot's value — no slot smuggles it through")
      (is (= "java.lang.String" (:status-type record))
          "only its TYPE crosses to the always-on axis"))))

;; ===========================================================================
;; No false positives
;; ===========================================================================

(deftest a-well-formed-status-reports-nothing
  (testing "rf2-gblft: the record is a DEFECT-only signal, not a per-request
            emission. A genuine integer status, an absent status (defaulted to
            200) and an absent redirect status (defaulted to 302) all fan
            nothing — otherwise the promotion would flood a shipper with one
            record per request."
    (let [records (collect-status-rewrite-records
                    (fn []
                      (pipeline/ssr-response->ring-response {:status 201 :headers []} "x")
                      (pipeline/ssr-response->ring-response {:headers []} "x")
                      (pipeline/ssr-response->ring-response
                        {:redirect {:status 302 :location "/x"}} nil)
                      (pipeline/ssr-response->ring-response
                        {:redirect {:location "/x"}} nil)))]
      (is (= [] records)
          "no record for a valid, absent or defaulted status"))))

;; ===========================================================================
;; One rewrite, one record (the double-emit this promotion would have doubled)
;; ===========================================================================

(deftest one-rewrite-fans-exactly-one-record
  (testing "rf2-gblft: a target-less redirect carrying a non-integer :status
            called `fail-closed-status` TWICE — once to fill in the no-target
            warning's `:status` payload, once to build the response map — so ONE
            rewrite reported itself twice (measured as 2
            `:rf.ssr/ssr-non-integer-status` warnings in a dev build). Harmless
            while the only consumer was a dev warning; a double record on an
            off-box shipper is a double alert and a doubled metric. The wire
            status is now resolved once and shared."
    (let [ring    (atom nil)
          records (collect-status-rewrite-records
                    (fn []
                      (reset! ring (pipeline/ssr-response->ring-response
                                     {:redirect {:status "302"}} nil))))]
      (is (= 1 (count records))
          "ONE rewrite fans ONE record, even on the redirect arm that reports
           the status a second time in its no-target warning")
      (is (= 500 (:status @ring))
          "and the redirect arm still fails closed to 500"))))

;; ===========================================================================
;; The two axes are genuinely two channels
;; ===========================================================================

(deftest the-two-axes-split-by-posture
  (testing "rf2-gblft: the always-on record and the dev warning are DIFFERENT
            channels, not one channel counted twice. This test derives its
            expectation from `interop/debug-enabled?` rather than assuming a
            posture, so it is green in both and states the contract in each:
            the record fires ALWAYS, the warning fires only in a dev build.
            Under `-Dre-frame.debug=false` this is the whole bead — one signal
            where there were none."
    (let [warnings (atom [])
          records  (do
                     (rf/register-listener! :trace ::status-rewrite-trace-watch
                       (fn [ev] (when (= :rf.ssr/ssr-non-integer-status (:operation ev))
                                  (swap! warnings conj ev))))
                     (try
                       (collect-status-rewrite-records
                         (fn [] (pipeline/ssr-response->ring-response
                                  {:status "404" :headers []} "x")))
                       (finally
                         (rf/unregister-listener! :trace ::status-rewrite-trace-watch))))]
      (is (= 1 (count records))
          "axis 1 (always-on) carries the rewrite in EVERY build")
      (if interop/debug-enabled?
        (do
          (is (= 1 (count @warnings))
              "axis 2 (dev trace) also carries it in a dev build")
          (is (= "404" (:status (:tags (first @warnings))))
              "and axis 2 — and ONLY axis 2 — keeps the offending value"))
        (is (= [] @warnings)
            "axis 2 is silent under -Dre-frame.debug=false — which is exactly
             the silence this bead removed from the wire-facing axis")))))
