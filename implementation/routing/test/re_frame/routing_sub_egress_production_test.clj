(ns re-frame.routing-sub-egress-production-test
  "rf2-u2x6w — route sub-classification egresses in PRODUCTION, and this is the
  namespace that says so under the real gate.

  ## Why this exists beside `routing-egress-test`

  rf2-7vk3z established that \"no cross-frame classification bleed\" is a
  PRODUCTION-real privacy invariant and built a production-visible witness for
  it inside `implementation/core`. It also found the boundary of what core can
  witness: the PROJECTION half of sub classification has no production egress
  there at all, because `classification/project-sub-tags` is reached only from
  `trace/build-event` inside `trace/emit!`'s `interop/debug-enabled?` gate.
  Under `-Dre-frame.debug=false` no `:rf.sub/run` event is built, so there is
  nothing to project. That is correct and expected.

  BUT SUB CLASSIFICATION DOES EGRESS IN PRODUCTION, from two sites outside
  core, and this is one of them: the `:routing/route-sub-egress-path` hook,
  which re-seeds `rf.elision/elide-wire-value` at a route sub's runtime-db storage
  position so the route's re-rooted `:sensitive` / `:large` declarations match
  the BARE slice value the sub returns. Nothing on that path reads
  `interop/debug-enabled?` — not route activation's classification lowering, not
  the per-frame elision registry, not the wire walker — so every off-box
  direct-read surface that names a sub through `:query-v` (Pair MCP `read-sub`,
  `list-subscriptions :include-values`, `snapshot :sub-cache`, Xray) redacts in
  a production build exactly as it does in a dev one. This namespace is the
  proof of that sentence.

  `re-frame.routing-egress-test` already covers the mechanism, but it CANNOT
  carry this claim: run under the gate it is 36 failures / 7 errors, because it
  interleaves the always-on egress legs with `:rf.fx/handled` /
  `:rf.route/navigation-blocked` TRACE assertions that a production build does
  not emit. A lane would have to exclude the whole namespace, and — exactly as
  `scripts/test-core-prod-gate.sh` warns of `conformance-test` — excluding it
  for the trace legs takes the always-on legs with it. So the production-real
  half lives here, in a namespace that is green in BOTH postures and can join a
  gate lane by default.

  ## Posture

  Every assertion below holds in dev AND under `-Dre-frame.debug=false`.
  Nothing here rebinds `interop/debug-enabled?`: the flag is read once at
  namespace-load time and a `with-redefs` cannot reach it (rf2-f7qj4), so a
  rebind would prove nothing about the gate. The posture is supplied by the
  JVM:

      clojure -J-Dre-frame.debug=false -M:test -n re-frame.routing-sub-egress-production-test

  Measured both ways at rf2-u2x6w: 7 tests / 16 assertions, 0 failures, exit 0.

  There is no `jvm-routing-prod-gate` job today — `jvm-core-prod-gate` covers
  `implementation/core` only, so neither this artefact nor `ssr` is in its
  reach. The lane recommendation is recorded on rf2-u2x6w."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.routing.sub-egress :as rf.routing.sub-egress]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private token-secret "secret-oauth-token-u2x6w")
(def ^:private param-secret "topsecret-upload-key")

(defn- navigate-to-classified-route!
  "Register an OAuth-callback-shaped route carrying a projection-relative
  `:sensitive` / `:large` declaration and navigate to it FOR REAL — `reg-route`
  plus a genuine `:rf.route/transitioned`, so the classification reaches the
  live frame's elision registry through the production activation lowering
  (`routing.classification/apply-route-classification`, `:source :route`) rather
  than through a hand-installed registry. That the lowering is a production path
  is half of what this namespace claims; a hand-seeded registry would assume it."
  []
  (rf/reg-route :route/oauth
                {:sensitive [[:query :token]]
                 :large     [[:query :payload]]
                 :query     [:map [:token :string] [:payload :string]]}
                "/oauth")
  (rf/dispatch-sync [:rf.route/transitioned
                     (str "/oauth?token=" token-secret "&payload=blobdata")]))

(defn- route-slice
  "The raw durable route slice from `:rf/default`'s runtime-db — byte-for-byte
  what `@(rf/subscribe [:rf/route])` returns in-process, and what an off-box
  read surface hands to the wire walker."
  []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current]))

;; ===========================================================================
;; (1) The always-on precondition — activation really lowers, in this posture
;; ===========================================================================

(deftest route-activation-lowers-its-classification-in-this-posture
  (testing "rf2-u2x6w — every redaction below is downstream of ONE always-on
            fact: navigating to a classified route re-roots its
            projection-relative declarations to absolute runtime-db paths and
            writes them into the live frame's per-frame elision registry. If a
            production build ever stopped doing that, the redactions below would
            go quiet rather than red — nothing would be declared, so nothing
            would be withheld, and a value-shaped assertion cannot tell that
            apart from a clean walk. This is the non-vacuity pin that can."
    (navigate-to-classified-route!)
    (let [reg (:rf.runtime/elision (rf.frame/frame-runtime-db-value :rf/default))]
      (is (contains? (:sensitive-declarations reg)
                     [:rf.runtime/routing :current :query :token])
          "the `:sensitive [[:query :token]]` decl re-rooted to its absolute
           runtime-db path")
      (is (contains? (:declarations reg)
                     [:rf.runtime/routing :current :query :payload])
          "and the `:large` one alongside it"))
    (testing "and the routing artefact really published the seam core consults"
      (is (some? (rf.late-bind/get-fn :routing/route-sub-egress-path))
          "`:routing/route-sub-egress-path` is bound — core reaches routing
           through this hook and nothing else"))))

;; ===========================================================================
;; (2) The egress site itself — off-box direct reads redact in production
;; ===========================================================================

(deftest route-sub-egress-redacts-the-classified-slice-off-box
  (testing "rf2-u2x6w — the Pair MCP `read-sub` server-side call shape:
            `elide-wire-value` naming the sub through `:query-v` consults the
            routing-owned seed table and walks the BARE slice at the slice's
            runtime-db storage position, so the registry's re-rooted absolute
            declarations match. This is the production egress of a route sub's
            classification, and it is not behind any debug gate."
    (navigate-to-classified-route!)
    (let [wire (rf/elide-wire-value (route-slice)
                                    {:query-v [:rf/route] :frame :rf/default})]
      (is (= rf.privacy/redacted-sentinel (get-in wire [:query :token]))
          "the `:sensitive` query value redacts off-box")
      (is (rf.elision/marker? (get-in wire [:query :payload]))
          "the `:large` one elides to the size marker")
      (is (= :route/oauth (:route-id wire))
          "unclassified structural facts ride verbatim — the walk is
           path-precise, not a blanket scrub")
      (is (not (.contains (pr-str wire) token-secret))
          "GUARD: the raw token appears NOWHERE on the wire value"))))

(deftest the-re-seed-is-what-does-it
  (testing "rf2-u2x6w — the counterfactual, and the reason the assertion above
            is not passing for some unrelated reason. The SAME value walked
            WITHOUT `:query-v` gets no route seed, so the whole-value root never
            meets the re-rooted absolute declaration and the token rides raw.
            The hook is the mechanism; remove it and this suite reds rather than
            silently over-approving."
    (navigate-to-classified-route!)
    (let [wire (rf/elide-wire-value (route-slice) {:frame :rf/default})]
      (is (= token-secret (get-in wire [:query :token]))
          "no `:query-v` ⇒ no re-seed ⇒ the bare slice walks at the root"))))

(deftest the-other-two-seed-table-entries-redact-too
  (testing "rf2-u2x6w — `:rf.route/query` and `:rf.route/params` return
            SUB-projections of the same durable slice, each with its own storage
            position. A seed table that covered only `:rf/route` would leave
            both shipping raw, so both are driven here rather than asserted from
            the table's data."
    (navigate-to-classified-route!)
    (let [wire (rf/elide-wire-value (:query (route-slice))
                                    {:query-v [:rf.route/query] :frame :rf/default})]
      (is (= rf.privacy/redacted-sentinel (:token wire))
          "`:rf.route/query`'s bare query map redacts through its own seed"))
    (rf/reg-route :route/upload {:sensitive [[:params :secret]]} "/upload/:secret")
    (rf/dispatch-sync [:rf.route/transitioned (str "/upload/" param-secret)])
    (let [wire (rf/elide-wire-value (:params (route-slice))
                                    {:query-v [:rf.route/params] :frame :rf/default})]
      (is (= rf.privacy/redacted-sentinel (:secret wire))
          "`:rf.route/params`' bare params map redacts through its own seed"))))

;; ===========================================================================
;; (3) The two boundaries the projection must not cross
;; ===========================================================================

(deftest the-in-process-read-stays-raw-in-production
  (testing "rf2-u2x6w — classification is read ONLY at egress. The durable
            slice the handler, the views and the app's own subs see keeps the
            real values, in a production build as in a dev one; a redaction that
            reached in-process would be a correctness bug wearing a privacy
            fix's clothes."
    (navigate-to-classified-route!)
    (let [slice (route-slice)]
      (is (= token-secret (get-in slice [:query :token])))
      (is (= "blobdata" (get-in slice [:query :payload]))))))

(deftest a-non-route-sub-is-untouched
  (testing "rf2-u2x6w — NARROW, and deliberately so: this is not generic
            sub-output propagation. Only the framework-owned route read surfaces
            are treated as alternate projections of the route-owned durable
            fact; an app sub whose value happens to wear the same shape gets no
            seed and no projection."
    (navigate-to-classified-route!)
    (is (= {:query {:token token-secret}}
           (rf.routing.sub-egress/project-route-sub-egress
             :some-app/sub {:query {:token token-secret}} {:frame :rf/default}))
        "a non-route sub-id rides verbatim through the projector")
    (is (nil? (rf.routing.sub-egress/route-sub-seed-path :rf.route/id))
        "and a route sub OUTSIDE the classification contract resolves no seed")))

(deftest frameless-route-sub-egress-fails-closed-in-production
  (testing "rf2-u2x6w — the fail-closed posture is the half of this that a
            production build most needs, because it is the half that runs when
            something has gone wrong. With no LIVE frame the per-frame registry
            is unreachable, so there is no policy to walk under; the value
            redacts WHOLE rather than falling through to a permissive identity
            walk that would ship every route slot verbatim."
    (navigate-to-classified-route!)
    (let [slice (route-slice)]
      (is (= rf.privacy/redacted-sentinel
             (rf/elide-wire-value slice {:query-v [:rf/route] :frame :no/such-frame}))
          "an explicit frame id that resolves to no LIVE frame redacts the
           whole slice — a stale carried scope is treated exactly like no
           scope at all")
      (is (= rf.privacy/redacted-sentinel
             (rf/elide-wire-value slice {:query-v [:rf/route] :frame :rf/destroyed}))
          "and the same holds for any id the frame registry does not know"))))
