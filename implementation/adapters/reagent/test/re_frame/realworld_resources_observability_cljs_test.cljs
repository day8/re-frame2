(ns re-frame.realworld-resources-observability-cljs-test
  "Mirror test for the RealWorld-on-resources example's production
   error-reporting wiring — the frame `:observability` sink (rf2-gzp5).

   WHY IT EXISTS. The frame `:observability` sink is the documented normal
   production observation surface (spec/API.md, spec/009,
   docs/core/observability.md and the report-errors-in-production how-to all
   teach it first), but until rf2-gzp5 NO example in the tree configured one,
   so a reader who followed the guide had nothing to copy from. The example now
   carries the CONFIGURATION — `realworld-resources.core/observability` (the
   frame's policy) and `install-error-monitor!` (the gated sink registration) —
   and this ns carries every assertion about it. That split is the standing
   test-free-examples lock (rf2-8cevm): no `*.spec.cjs` under `examples/`, so
   the example is configuration and the framework test tree does the grading.

   WHAT IS PINNED, and each row is a claim the guide makes that a reader would
   be entitled to rely on:

     1. THE POLICY IS WELL-FORMED and names the same sink id the example's own
        registration uses — the two halves cannot drift, because both read one
        def.
     2. THE POLICY ACTUALLY ROUTES. A handler exception in a frame configured
        with the EXAMPLE'S OWN policy map delivers exactly one
        `:rf.observe/error` record to a sink registered under the example's own
        id — and that record arrives ALREADY PROJECTED, with the JWT the app
        classifies sensitive redacted. A sink that had to scrub for itself
        would make the whole design pointless, so this is the load-bearing one.
     3. ROUTING IS FAIL-CLOSED. The same registered sink and the same throwing
        handler, in a frame that declares NO policy, route nothing. This is
        what makes the example's unconditional declaration meaningful: it is
        the declaration, not the registration, that opens the channel.
     4. THE GATE IS ON THE REGISTRATION, NOT THE DECLARATION.
        `install-error-monitor!` follows `rf.interop/debug-enabled?` — so a dev
        build registers no sink and the framework's own console fallback takes
        the record, which is the posture the how-to argues for at length.

   The fixture clears the sink registry and the always-on error-listener
   registry around each test, so a sink registered here cannot leak into a
   sibling namespace sharing the consolidated node-test bundle.

   ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up. No
   DOM: the records are read off a capturing sink, not off a rendered page."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.observability :as rf.observability]
            [re-frame.projection :as rf.projection]
            [re-frame.test-support :as rf.test-support]
            ;; The example's production source — the subject. Requiring it
            ;; registers the whole app at ns-load; all this ns reads off it are
            ;; the two error-reporting defs and the gated installer.
            [realworld-resources.core :as app]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private sensitive-token
  "Stands in for the Conduit JWT. The example marks [:auth :token] sensitive at
   frame creation (`:auth/classify-token`), so this string must never reach a
   sink raw."
  "CONDUIT-JWT-SENTINEL-4f21c9")

(defn- classify-token-sensitive!
  "Mirror of the example's `:auth/classify-token` initial event — the durable
   app-db classification is a commit-plane effect (EP-0025), so declaring it
   here is the same declaration the app makes at boot."
  [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [[:auth :token]]}))))

(defn- reg-throwing-event! [frame-id]
  (rf/reg-event ::boom
    {:frame frame-id}
    (fn [_ _] (throw (ex-info "kaboom" {:cause :test})))))

(defn- drive-failure! [frame-id]
  (rf/dispatch-sync [::boom {:auth {:token sensitive-token}}] {:frame frame-id}))

;; ---------------------------------------------------------------------------
;; 1. the policy is well-formed and single-sourced
;; ---------------------------------------------------------------------------

(deftest example-declares-a-well-formed-error-sink-policy
  (testing "the example's frame policy names ONE :errors sink, under a member
            of the closed egress-profile enum, using the same id its own
            registration uses"
    (is (keyword? app/error-sink-id)
        "the sink id is a keyword — what a frame entry's :sink must carry")
    (let [entries (:errors app/observability)]
      (is (= [:errors] (keys app/observability))
          "the policy declares the :errors stream and nothing else")
      (is (= 1 (count entries))
          "exactly one sink entry — the example teaches one monitor")
      (let [entry (first entries)]
        (is (= app/error-sink-id (:sink entry))
            "the entry names the example's own sink id, so the policy and the
             registration cannot drift apart")
        (is (contains? rf.projection/profiles (:rf.egress/profile entry))
            "the egress profile is a member of the closed EP-0015 enum")
        (is (= :rf.egress/off-box-observability (:rf.egress/profile entry))
            "and it is the off-box boundary — this record leaves the box for a
             hosted monitor, which is the profile that decides how much of it
             survives projection")))))

;; ---------------------------------------------------------------------------
;; 2. the policy routes, and the record arrives already projected
;; ---------------------------------------------------------------------------

(deftest example-policy-routes-one-projected-error-record
  (testing "a handler exception in a frame configured with the EXAMPLE'S OWN
            :observability map delivers exactly one projected
            :rf.observe/error record to a sink registered under the example's
            own id, with the sensitive JWT redacted before the sink sees it"
    (let [seen (atom [])]
      (rf/register-observability-sink! app/error-sink-id
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id ::routed :observability app/observability})
      (classify-token-sensitive! ::routed)
      (reg-throwing-event! ::routed)
      (drive-failure! ::routed)

      (is (= 1 (count @seen))
          "the declared sink fired exactly once")
      (let [r (first @seen)]
        (is (= :rf.observe/error (:kind r))
            "the record is a canonical :rf.observe/error")
        (is (= ::routed (:frame r))
            "it names the frame it failed in — the context worth having at 3am")
        (is (= :rf.error/handler-exception (:error r))
            "the canonical discriminator a sink branches on")
        (is (= ::boom (:event-id r))
            "and the event that was in flight")
        ;; The load-bearing assertion: the sink did no scrubbing, and the token
        ;; is redacted anyway, because the runtime projected the record under
        ;; the frame's classification before handing it over.
        (is (= :rf/redacted (get-in (:event r) [1 :auth :token]))
            "the sensitive JWT inside the error's :event is REDACTED — the sink
             received an already-projected record and re-implements nothing")
        (is (not= sensitive-token (get-in (:event r) [1 :auth :token]))
            "explicitly: the raw token did not reach the sink")))))

;; ---------------------------------------------------------------------------
;; 3. fail-closed — the DECLARATION is what opens the channel
;; ---------------------------------------------------------------------------

(deftest a-frame-without-the-policy-routes-nothing
  (testing "the same registered sink and the same throwing handler route
            NOTHING from a frame that declares no :observability policy — which
            is why the example declares its policy unconditionally"
    (let [seen (atom [])]
      (rf/register-observability-sink! app/error-sink-id
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id ::unclassified})
      (classify-token-sensitive! ::unclassified)
      (reg-throwing-event! ::unclassified)
      (drive-failure! ::unclassified)
      (is (empty? @seen)
          "no record routed: there is no :rf/default sink synthesised on your
           behalf, so you cannot leak from a frame you never classified"))))

;; ---------------------------------------------------------------------------
;; 4. the gate is on the REGISTRATION
;; ---------------------------------------------------------------------------

(deftest the-sink-registration-is-gated-on-build-posture
  (testing "install-error-monitor! follows debug-enabled? — a dev build
            registers nothing (and the framework's console fallback takes the
            record instead), while an optimised build registers the sink"
    (let [result (app/install-error-monitor!)]
      (if ^boolean rf.interop/debug-enabled?
        (is (nil? result)
            "dev build: the gate is closed, nothing registered — the policy is
             still declared, so the record routes to no sink and the framework
             prints it for you")
        (is (= app/error-sink-id result)
            "optimised build: the gate is open and the sink registers under the
             id the frame policy names")))))
