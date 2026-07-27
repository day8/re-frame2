(ns re-frame.ssr-route-miss-404-production-test
  "rf2-ov56u (ruling rf2-rqje9) ACCEPTANCE — an SSR request for an
  UNROUTABLE URL answers HTTP 404 under the REAL production gate.

  THE DEFECT. `re-frame.interop/debug-enabled?` reads
  `-Dre-frame.debug=false` ONCE at namespace-load time. Until this bead the
  URL-driven route miss reached the outside world through ONE channel —
  `plan/emit-intents!` → `trace/emit-error!`, which sits inside that
  gate. On a production JVM nothing buffered, `flush-response!` had nothing
  to project, and `:status` stayed 200: RFC 9110 calls 200 a success and
  Google calls a 200'd not-found page a soft 404 and drops it from Search.
  The fix promotes that ONE emit site onto the always-on error axis
  (`error-emit/dispatch-error-record!`, via the
  `:error-emit/dispatch-error-record` late-bind hook) while retaining its
  dev trace.

  WHY THIS SUITE IS POSTURE-INDEPENDENT, AND WHY THAT MATTERS. Every
  assertion below is true under BOTH postures and mentions the dev trace
  bus NOWHERE, so the namespace joins `scripts/test-ssr-prod-gate.sh` by
  default (that roster is an EXCLUSION list) and executes under
  `-Dre-frame.debug=false` for real. A `with-redefs [interop/debug-enabled?
  false]` rebind CANNOT reach a load-time gate — it is not evidence for
  this bead, which is precisely why the sibling suites that use it stayed
  green while production shipped a 200.

  WHAT IS PINNED:

    1. The wire. A route miss projects `{:status 404 :code :not-found}`
       onto the response accumulator, with NO redirect, and the app's
       not-found body still renders — a status-only 404.
    2. The record. EXACTLY ONE always-on `:rf.error/no-such-handler`
       record reaches an off-box shipper, carrying `:kind :route`, the
       emitting `:frame`, `:time`, `:recovery`, the structured `:reason`
       and a REDACTED `:url` — never a raw query / fragment carrier value,
       never an app-db slice (EP-0015 fail-closed; the `:url` slot is the
       class most likely to carry `?token=…` / `#access_token=…`).
    3. The `:kind` gate. An unregistered EVENT dispatch is a SERVER defect
       and answers 500, not 404 — the adjacent correction that makes the
       promotion safe, since before it no `:rf.error/no-such-handler`
       reached the projector in production at all.
    4. The seam. A projector registered through `reg-error-projector`
       still wins over the built-in default on the promoted path.

  Companion suites:
    - `re-frame.ssr-end-to-end-test` — the dev-posture end-to-end cascade.
    - `re-frame.ssr-error-projector-substrate-test` — the always-on
      substrate install.
    - `re-frame.ssr-routing-egress-production-test` (rf2-u2x6w) — the
      other always-on witness written to run in this lane."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

;; NOTE the fixture does NOT clear the always-on error-listener registry.
;; `re-frame.ssr` installs its own `::error-projection` listener there at
;; ns-load time, and that listener IS the production status-projection
;; path this suite exercises; wiping the registry would silently disarm
;; every 404 assertion below into a vacuous 200. Each test unregisters
;; only the shipper stand-in it registered.
(use-fixtures :each tf/reset-runtime)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

;; A URL that matches no registered route AND carries both carrier classes
;; the EP-0015 scrub targets: a query VALUE and an opaque `#fragment`.
(def ^:private hostile-miss-url
  "/no-such-page?token=s3cr3t-query-value&flag#s3cr3t-fragment-value")

(defn- register-routes! []
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/not-found")
  (rf/reg-view* :pages/not-found
                (fn [] [:main.not-found [:h1 "No such page"]])))

(defn- server-frame
  "A `:platform :server` frame wired to `projector-id` (the built-in
  default unless a test names its own)."
  ([] (server-frame :rf.ssr/default-error-projector))
  ([projector-id]
   (frame/make-anon-frame-record!
     {:platform :server
      :ssr      {:public-error-id   projector-id
                 :dev-error-detail? false}})))

(defn- capture-always-on!
  "Register an off-box-shipper stand-in on the ALWAYS-ON error axis (the
  `:errors` stream of `register-listener!` — surface #4, not the dev trace
  bus). Returns the atom collecting every record it receives."
  [id]
  (let [seen (atom [])]
    (rf/register-listener! :errors id (fn [record] (swap! seen conj record)))
    seen))

;; ===========================================================================
;; (1) THE WIRE — an unroutable URL answers 404, body intact, no redirect
;; ===========================================================================

(deftest unroutable-url-projects-404-under-the-production-gate
  (testing "rf2-ov56u: `[:rf.route/handle-url-change \"/no-such-page\"]` on a
            server frame projects the default projector's 404 onto
            `:rf/response`. This assertion is the whole bead: before the
            promotion it observed 200 under `-Dre-frame.debug=false` while
            passing in dev, because the miss only ever rode the dev trace."
    (register-routes!)
    (let [f (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})

      (let [{:keys [response public-error]} (ssr/flush-response-result! f)]
        (is (= 404 (:status response))
            "the drain projects the route miss onto :status — 404, not a soft-404 200")
        (is (nil? (:redirect response))
            "status-only: a route miss is not a redirect")
        (is (= {:status     404
                :code       :not-found
                :message    "Page not found"
                :retryable? false}
               public-error)
            "the projected :rf/public-error is the locked 404 shape per
             Spec 011 §Default projector — the host classifies on the
             projection, not by re-inferring from (:status response)")))))

(deftest the-404-is-status-only-the-not-found-body-still-renders
  (testing "rf2-ov56u: a 404 is a STATUS decision, not a rendering one. The
            app's own not-found view still produces markup, so the host ships
            a real page under the 404 rather than an error page."
    (register-routes!)
    (let [f    (server-frame)
          _    (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"]
                                 {:frame f})
          html (rf/with-frame f
                 (rf/render-to-string [(rf/view :pages/not-found)]))]
      (is (str/includes? html "No such page")
          "the app's not-found body rendered — the projection did not
           short-circuit the render")
      (is (= 404 (:status (ssr/flush-response! f)))
          "and the response still carries the 404"))))

(deftest the-route-slice-records-the-miss-alongside-the-404
  (testing "rf2-ov56u: the durable `:rf.route/not-found` slice (the app's
            source of truth for WHAT to render) and the per-request HTTP
            status (the wire decision) are BOTH present and stay distinct
            surfaces — the ruling rejected deriving one from the other."
    (register-routes!)
    (let [f (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (is (= :rf.route/not-found
             (get-in (rf/frame-state-value f)
                     [:rf.db/runtime :rf.runtime/routing :current :route-id]))
          "the navigation slice committed the not-found fallback")
      (is (= 404 (:status (ssr/flush-response! f)))
          "and the response accumulator carries the projected 404"))))

;; ===========================================================================
;; (2) THE RECORD — exactly one, tight, redacted
;; ===========================================================================

(deftest route-miss-fans-exactly-one-always-on-record
  (testing "rf2-ov56u: the promotion delivers ONE record per miss on the
            always-on axis (Spec 009's one-runtime-error law) — not one per
            channel, and not one per telemetry intent."
    (register-routes!)
    (let [seen (capture-always-on! ::one-record)
          f    (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (rf/unregister-listener! :errors ::one-record)
      (is (= [:rf.error/no-such-handler] (mapv :error @seen))
          "exactly one always-on record, and it is the route-miss category"))))

(deftest the-always-on-record-carries-the-route-discriminator-and-attribution
  (testing "rf2-ov56u: the production record is the enumerated tight shape —
            category + `:kind :route` + emitting `:frame` + `:time` +
            `:recovery`. `:kind` is what the default projector gates its 404
            arm on, so a record without it would silently fall to 500."
    (register-routes!)
    (let [seen (capture-always-on! ::shape)
          f    (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (rf/unregister-listener! :errors ::shape)
      (let [record (first @seen)]
        (is (= :rf.error/no-such-handler (:error record)))
        (is (= :route (:kind record))
            ":kind :route — the mandatory discriminator per Spec 009's
             catalogue row, and the default projector's 404 gate")
        (is (= f (:frame record))
            "the emitting server frame — per-frame attribution is what lets
             the projection listener route this to the right response
             accumulator with many concurrent request frames live")
        (is (integer? (:time record))
            "the union record's mandatory :time slot")
        (is (= :replaced-with-default (:recovery record))
            "the framework-owned recovery, not an app-steerable policy")))))

(deftest the-always-on-record-redacts-url-carrier-values
  (testing "rf2-ov56u / EP-0015 (rf2-n1f4rh) EGRESS: the promotion changes
            what a PRODUCTION build sends off-box, so the fail-closed scrub
            has to cover the always-on record and not just the dev trace. A
            route-miss URL has no matched route and therefore no
            `:params` / `:query` schema to target, yet it is the class most
            likely to carry `?token=…` / `#access_token=…`. The redaction is
            applied at the emit site, BEFORE either axis sees the tags."
    (register-routes!)
    (let [seen (capture-always-on! ::redaction)
          f    (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change hostile-miss-url] {:frame f})
      (rf/unregister-listener! :errors ::redaction)
      (let [url (:url (first @seen))]
        (is (some? url) "the record names the requested URL")
        (is (not (str/includes? url "s3cr3t-query-value"))
            "the query carrier VALUE never reaches an off-box shipper")
        (is (not (str/includes? url "s3cr3t-fragment-value"))
            "nor does the opaque #fragment, which could be an implicit-grant
             token")
        (is (str/starts-with? url "/no-such-page")
            "the structured PATH survives — an error UI / SEO dashboard still
             sees WHICH page was missing")
        (is (str/includes? url "token=")
            "and the query KEY survives: a security dashboard can still see
             that a `token` parameter was present")))))

(deftest the-always-on-record-carries-no-app-db-and-no-stray-slots
  (testing "rf2-ov56u EGRESS: `dispatch-error-record!` delivers the record
            UNCHANGED to every registered shipper — it is not privacy-gated
            the way the dev trace is, so the emit site is contracted to keep
            it tight. Pin the key set CLOSED: a slot added here reaches
            Sentry / Datadog in production, so it must be a deliberate
            change, not a drift."
    (register-routes!)
    (let [seen (capture-always-on! ::closed-shape)
          f    (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (rf/unregister-listener! :errors ::closed-shape)
      (is (= #{:error :kind :frame :time :recovery :url}
             (set (keys (first @seen))))
          "exactly the enumerated slots — no :db, no :params, no event
           vector, no exception, no raw carrier"))))

(deftest a-malformed-miss-carries-its-structured-reason
  (testing "rf2-4ic0f / rf2-ov56u: the `:reason` vocabulary is uniform across
            the route slice and BOTH error axes, so a production shipper can
            tell a plain miss from a malformed URL without the dev trace."
    (register-routes!)
    (let [seen (capture-always-on! ::reason)
          f    (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/bad%ZZ-encoding"] {:frame f})
      (rf/unregister-listener! :errors ::reason)
      (let [record (first @seen)]
        (is (= :rf.error/no-such-handler (:error record)))
        (is (= :malformed-url (:reason record))
            "the malformed percent-encoding is named on the production record")
        (is (= 404 (:status (ssr/flush-response! f)))
            "and it is still a 404 on the wire — a malformed URL matches no
             route")))))

;; ===========================================================================
;; (3) THE :kind GATE — a server defect is a 500, not a 404
;; ===========================================================================

(deftest an-unregistered-event-dispatch-projects-500-not-404
  (testing "rf2-ov56u ADJACENT CORRECTION: `:rf.error/no-such-handler` covers
            three misses discriminated by `:kind`. Only `:kind :route` is a
            missing-PAGE condition. A dispatch to an event id the server
            forgot to register is a SERVER defect — telling the client its
            URL was wrong would be a lie, and would poison crawler
            behaviour. Before the gate, promoting the route miss would have
            made every unregistered-event dispatch answer 404."
    (register-routes!)
    (let [f (server-frame)]
      (rf/dispatch-sync [:never/registered] {:frame f})
      (is (= 500 (:status (ssr/flush-response! f)))
          "the event-kind miss falls through to the locked generic-500"))))

(deftest default-projector-gates-the-404-arm-on-kind-route
  (testing "rf2-ov56u: the gate, unit-tested directly on the pure projector
            fn. A miss with no `:kind` at all falls through too — the 404 arm
            is opt-in on the route discriminator (fail-safe), symmetric with
            the `:where`-gated schema-validation-failure arm."
    (is (= {:status 404 :code :not-found :message "Page not found" :retryable? false}
           (ssr/default-error-projector-fn
             {:operation :rf.error/no-such-handler :tags {:kind :route}}))
        ":kind :route → 404")
    (is (= ssr/fallback-public-error
           (ssr/default-error-projector-fn
             {:operation :rf.error/no-such-handler :tags {:kind :event}}))
        ":kind :event (an unregistered event id) → the locked 500")
    (is (= ssr/fallback-public-error
           (ssr/default-error-projector-fn
             {:operation :rf.error/no-such-handler :tags {:kind :frame}}))
        ":kind :frame (a Tool-Pair surface naming an unknown frame) → 500")
    (is (= ssr/fallback-public-error
           (ssr/default-error-projector-fn
             {:operation :rf.error/no-such-handler :tags {}}))
        "no :kind → 500; the 404 arm never fires on an unclassified miss")
    (is (= {:status 404 :code :not-found :message "Page not found" :retryable? false}
           (ssr/default-error-projector-fn {:operation :rf.error/no-such-route}))
        ":rf.error/no-such-route keeps its UNCONDITIONAL 404 — one failure
         mode, no :kind discriminator")))

;; ===========================================================================
;; (4) THE SEAM — a registered projector still wins
;; ===========================================================================

(deftest a-custom-projector-still-wins-on-the-promoted-path
  (testing "rf2-ov56u: the promotion feeds the EXISTING `reg-error-projector`
            seam rather than bypassing it — which is the reason the ruling
            rejected deriving `:status` from the routing slice at the host
            boundary. A frame that names its own projector gets its own
            mapping, in production, on the route-miss path."
    (register-routes!)
    (rf/reg-error-projector :myapp/route-miss-projector
      {:doc "Custom projector — a route miss is a 410 Gone."}
      (fn [trace-event]
        (if (= :rf.error/no-such-handler (:operation trace-event))
          {:status 410 :code :gone :message "This page is gone" :retryable? false}
          {:status 500 :code :internal-error :message "Nope" :retryable? false})))

    (let [f (server-frame :myapp/route-miss-projector)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (let [{:keys [response public-error]} (ssr/flush-response-result! f)]
        (is (= 410 (:status response))
            "the custom projector's status reaches the wire — the runtime
             default did not shadow it")
        (is (= :gone (:code public-error)))))))

(deftest a-redirect-still-takes-precedence-over-the-projected-404
  (testing "rf2-ov56u: redirect precedence (Spec 011 §Redirect precedence) is
            unchanged by the promotion. A handler that redirects during the
            same drain as a route miss ships the redirect bodiless; the
            projected 404 does not overwrite it."
    (register-routes!)
    (rf/reg-event :auth/bounce
      (fn [_ _] {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))
    (let [f (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (rf/dispatch-sync [:auth/bounce] {:frame f})
      (let [response (ssr/get-response f)]
        (is (= {:status 302 :location "/login"} (:redirect response))
            "the redirect survived the drain")
        (is (not= 404 (:status response))
            "the projected 404 did not overwrite the redirect's status")))))

;; ===========================================================================
;; (5) CONCURRENCY — the miss lands on the emitting frame only
;; ===========================================================================

(deftest the-404-lands-on-the-emitting-frame-only
  (testing "rf2-7d30s / rf2-ov56u: under concurrent SSR many server frames
            are live at once. The promoted record carries the emitting
            frame's `:frame` slot, so the projection routes to THAT response
            accumulator; a sibling request serving a routable URL keeps its
            200. Without the `:frame` stamp the projection would be
            unroutable and stamp nothing — a silent 200 for a request that
            should have been a 404."
    (register-routes!)
    (let [miss-frame (server-frame)
          ok-frame   (server-frame)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"]
                        {:frame miss-frame})
      (rf/dispatch-sync [:rf.route/handle-url-change "/"] {:frame ok-frame})
      (is (= 404 (:status (ssr/flush-response! miss-frame)))
          "the frame that missed carries the 404")
      (is (= 200 (:status (ssr/flush-response! ok-frame)))
          "its concurrent sibling, which routed fine, is untouched"))))

(deftest a-client-frame-route-miss-stamps-no-status
  (testing "rf2-ov56u: promotion does not give a CLIENT frame an HTTP
            response. The record fans to off-box shippers on both hosts, but
            the projection listener no-ops for a non-server frame — there is
            no request to fail."
    (register-routes!)
    (let [seen     (capture-always-on! ::client)
          client-f (frame/make-anon-frame-record! {:platform :client})]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"]
                        {:frame client-f})
      (rf/unregister-listener! :errors ::client)
      (is (= [:rf.error/no-such-handler] (mapv :error @seen))
          "the always-on record still fans — a CLJS production build's error
           shipper sees the client-side route miss too")
      (is (= 200 (:status (ssr/get-response client-f)))
          "but no status is stamped: a client frame has no HTTP response"))))
