(ns re-frame.ssr-routing-egress-production-test
  "rf2-u2x6w — route sub-classification egresses in PRODUCTION here too, and
  this namespace says so under the real gate.

  ## What this adds to `ssr-route-slice-projection-test`

  rf2-7vk3z established that \"no cross-frame classification bleed\" is a
  PRODUCTION-real privacy invariant, and found the boundary of what
  `implementation/core` can witness: the PROJECTION half of sub classification
  has no production egress inside core, because
  `classification/project-sub-tags` is reached only from `trace/build-event`
  inside `trace/emit!`'s `interop/debug-enabled?` gate. Under
  `-Dre-frame.debug=false` no `:rf.sub/run` event is built at all.

  `payload-policy/project-routing-egress` is one of the two sites outside core
  where that classification DOES leave the box in a production build — the
  hydration blob every visitor receives. `ssr-route-slice-projection-test`
  already pins the projector and is, as it happens, green under the gate; what
  it does not do is drive the LOWERING. It installs the re-rooted declarations
  directly (`elision/swap-elision-slot!` over
  `routing.classification/apply-route-classification`), which proves the
  projector reads what is in the registry but ASSUMES the thing that puts it
  there. Route activation is the other half of the production path, and it is
  the half a debug gate could plausibly be added to. So this namespace drives
  `reg-route` plus a real `:rf.route/transitioned` and projects the frame's
  ACTUAL runtime-db, end to end.

  The two namespaces are therefore complements, not duplicates: one pins the
  projector against a known registry, this one pins the whole chain —
  activation → per-frame registry → `project-runtime-db` → `build-payload` —
  in the posture a shipped server runs in.

  ## Posture

  Every assertion below holds in dev AND under `-Dre-frame.debug=false`.
  Nothing here rebinds `interop/debug-enabled?`: the flag is read once at
  namespace-load time and a `with-redefs` cannot reach it (rf2-f7qj4). The
  posture is supplied by the JVM:

      clojure -J-Dre-frame.debug=false -M:test -n re-frame.ssr-routing-egress-production-test

  Measured both ways at rf2-u2x6w: 4 tests / 15 assertions, 0 failures, exit 0.

  There is no `jvm-ssr-prod-gate` job today — `jvm-core-prod-gate` covers
  `implementation/core` only, so this artefact is not in its reach. The lane
  recommendation is recorded on rf2-u2x6w."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            ;; Loading routing publishes the route classification machinery and
            ;; the routing events; the reset fixture reloads it.
            [re-frame.routing]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private token-secret "secret-oauth-token-u2x6w")
(def ^:private blob-secret "huge-callback-blob-value-u2x6w")

(defn- navigate-to-classified-route!
  "Register an OAuth-callback-shaped route with a projection-relative
  `:sensitive` / `:large` declaration and navigate to it FOR REAL. `reg-route`
  plus a genuine `:rf.route/transitioned` runs the production activation
  lowering (`routing.classification/apply-route-classification`, `:source
  :route`), which is what writes the re-rooted absolute declarations into
  `:rf/default`'s per-frame elision registry. `:return-to` is a plain
  navigation breadcrumb classified by nothing — the over-redaction control."
  []
  (rf/reg-route :route/oauth-callback
                {:sensitive [[:query :token]]
                 :large     [[:query :payload]]
                 :query     [:map
                             [:token     :string]
                             [:payload   :string]
                             [:return-to :string]]}
                "/oauth/callback")
  (rf/dispatch-sync [:rf.route/transitioned
                     (str "/oauth/callback?token=" token-secret
                          "&payload=" blob-secret
                          "&return-to=/dashboard")]))

(defn- live-runtime-db
  "`:rf/default`'s ACTUAL runtime-db — the state a request frame would be
  holding when the hydration payload is built, not a hand-written fixture map."
  []
  (rf.frame/frame-runtime-db-value :rf/default))

;; ===========================================================================
;; (1) The always-on precondition — activation really lowers, in this posture
;; ===========================================================================

(deftest route-activation-lowers-its-classification-in-this-posture
  (testing "rf2-u2x6w — the hydration redaction below is downstream of ONE
            always-on fact: a real navigation re-roots the route's
            projection-relative declarations to absolute runtime-db paths and
            writes them into the live frame's elision registry. If a production
            build ever stopped doing that the payload assertions would go quiet
            rather than red — an empty registry withholds nothing and a
            value-shaped assertion cannot tell that apart from a clean walk."
    (navigate-to-classified-route!)
    (let [reg (:rf.runtime/elision (live-runtime-db))]
      (is (contains? (:sensitive-declarations reg)
                     [:rf.runtime/routing :current :query :token])
          "the `:sensitive` decl re-rooted to its absolute runtime-db path")
      (is (contains? (:declarations reg)
                     [:rf.runtime/routing :current :query :payload])
          "and the `:large` one alongside it"))
    (testing "and the durable slice really is carrying the secret in-process"
      (is (= token-secret
             (get-in (live-runtime-db)
                     [:rf.runtime/routing :current :query :token]))
          "FIXTURE — the runtime-db the projection is about does hold the raw
           value, so a clean payload below is a redaction and not an absence"))))

;; ===========================================================================
;; (2) The egress site itself — the hydration blob redacts in production
;; ===========================================================================

(deftest hydration-runtime-db-redacts-the-live-classified-route-slice
  (testing "rf2-u2x6w — `project-runtime-db` over the frame's REAL runtime-db,
            under the explicit target frame the security-critical builders
            carry. The allowlisted durable routing slice goes through
            `project-routing-egress` at the `[:rf.runtime/routing]` offset, so
            the registry's re-rooted absolute route paths match and the
            classified values never reach the wire."
    (navigate-to-classified-route!)
    (let [slice   (rf.ssr.payload-policy/project-runtime-db (live-runtime-db) :rf/default)
          current (get-in slice [:rf.runtime/routing :current])]
      (is (= :rf/redacted (get-in current [:query :token]))
          "the `:sensitive` query value redacts in the hydration slice")
      (is (rf.elision/marker? (get-in current [:query :payload]))
          "the `:large` one elides to the size marker")
      (is (= "/dashboard" (get-in current [:query :return-to]))
          "the unclassified sibling rides verbatim — path-precise, not a
           blanket scrub")
      (is (= :route/oauth-callback (:route-id current))
          "and so does the structural `:route-id`")
      (is (not (.contains (pr-str slice) token-secret))
          "GUARD: no raw token anywhere in the projected runtime-db")
      (is (not (.contains (pr-str slice) blob-secret))
          "GUARD: nor the large value"))))

(deftest the-hydration-payload-a-visitor-receives-carries-no-raw-route-secret
  (testing "rf2-u2x6w — one step further out, at the artefact a browser
            actually gets. `build-payload` is where the projected runtime-db
            becomes the serialized hydration blob; asserting on the projector's
            return value alone would leave the last hop unwitnessed."
    (navigate-to-classified-route!)
    (let [rt-slice (rf.ssr.payload-policy/project-runtime-db (live-runtime-db) :rf/default)
          payload  (rf.ssr.payload-policy/build-payload
                     :rf/default {:public/page :callback} "h1"
                     {:version 1 :runtime-db rt-slice})
          current  (get-in payload [:rf/runtime-db :rf.runtime/routing :current])]
      (is (= :rf/redacted (get-in current [:query :token])))
      (is (rf.elision/marker? (get-in current [:query :payload])))
      (is (not (.contains (pr-str payload) token-secret))
          "GUARD: the blob the client receives carries no raw secret")
      (is (not (.contains (pr-str payload) blob-secret))))))

;; ===========================================================================
;; (3) The boundary — an unclassified route is not over-redacted
;; ===========================================================================

(deftest an-unclassified-route-ships-its-slice-verbatim
  (testing "rf2-u2x6w — the over-redaction control, driven through the same
            real activation. A route that declares nothing lowers nothing, and
            its durable slice rides the hydration wire intact. Without this the
            assertions above would also pass under a blanket scrub, which would
            be a different framework."
    (rf/reg-route :route/home {:query [:map [:token :string]]} "/home")
    (rf/dispatch-sync [:rf.route/transitioned "/home?token=not-classified"])
    (let [slice   (rf.ssr.payload-policy/project-runtime-db (live-runtime-db) :rf/default)
          current (get-in slice [:rf.runtime/routing :current])]
      (is (= "not-classified" (get-in current [:query :token]))
          "an unclassified route query rides the hydration wire verbatim")
      (is (= :route/home (:route-id current))))))
