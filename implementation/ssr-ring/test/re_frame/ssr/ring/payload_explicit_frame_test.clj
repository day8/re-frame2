(ns re-frame.ssr.ring.payload-explicit-frame-test
  "The NON-streaming Ring hydration payload wrapper
  (`re-frame.ssr.ring.payload/build-payload`) must project the runtime-db slice
  under the EXPLICIT carried frame-id, never the ambient `rf/with-frame` scope.

  A wrapper that resolved the projection frame
  AMBIENTLY (`frame/resolve-current-frame`) — while its sibling app-db
  projection (`project-app-db-egress`) runs at the explicit target — would,
  for a build run OUTSIDE a matching `rf/with-frame` (ambient nil) or under a
  DIFFERENT ambient frame, project the durable route `:current`
  slice under no / the wrong frame's policy: `project-routing-egress` fails
  OPEN with no live frame, so a classified `:query :token` would ride the
  hydration blob RAW. A host binding the request frame around the build masks
  that, so the wrapper threads the explicit frame and its correctness does
  not depend on a matching ambient binding.

  These regressions drive `ring.payload/build-payload` directly with an
  explicit server frame A carrying a classified route slice, once with NO
  ambient scope and once under a MISMATCHED ambient frame B, and prove frame
  A's route classification redacts the token regardless of ambient scope. An
  ambient-resolving wrapper would FAIL them — the token would ride raw."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.ring.payload :as rf.ssr.ring.payload]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private server-frame :review/ssr-ring-target)
(def ^:private ambient-frame :review/other-ambient)
(def ^:private route-id      :route/oauth-callback)

(defn- frame-a-runtime-db
  "Frame A's live runtime-db: the per-frame elision registry classifying the
  durable route `:query :token` SENSITIVE + `:params :payload` LARGE — as the
  ABSOLUTE `[:rf.runtime/routing :current …]` paths route activation lowers —
  plus the durable `:current` route slice carrying the raw secret + blob. Only
  frame A declares these; frame B (the mismatched ambient) declares nothing, so
  a projection run under B would leak the raw token."
  []
  (-> {}
      (rf.elision/apply-classification-effects
        {:sensitive [[:rf.runtime/routing :current :query :token]]
         :large     [[:rf.runtime/routing :current :params :payload]]})
      (assoc :rf.runtime/routing
             {:current            {:route-id route-id
                                   :query    {:token     "secret-oauth-token"
                                              :return-to "/dashboard"}
                                   :params   {:payload "huge-callback-blob-value"}}
              :pending-navigation {:id "pn-1"}})))

(defn- setup-frames! []
  (rf/make-frame {:id server-frame :doc      "non-streaming ring explicit-frame regression frame A"
                  :platform :server})
  ;; Frame B exists as a real (registered) ambient frame with NO classifications
  ;; — the strongest "wrong frame" case: projecting the route slice under B
  ;; consults B's empty registry and the token would ride verbatim.
  (rf/make-frame {:id ambient-frame :doc      "mismatched ambient frame B (no classifications)"
                  :platform :server})
  (rf.frame/swap-runtime-db! server-frame (constantly (frame-a-runtime-db))))

(defn- build-a
  "Call the non-streaming Ring wrapper for frame A, reading A's live runtime-db
  value and handing it in as the wrapper's `runtime-db` arg. app-db is empty so
  the test isolates the runtime-db route projection."
  []
  (rf.ssr.ring.payload/build-payload
    server-frame {} (rf.frame/frame-runtime-db-value server-frame) "hash"
    {:payload :rf.ssr.payload/whole-app-db}))

(defn- assert-frame-a-redacts [payload where]
  (let [current (get-in payload [:rf/runtime-db :rf.runtime/routing :current])]
    ;; The WIRE `:rf/frame-id` is decoupled from the projection
    ;; frame. `build-a` passes no `:client-frame-id`, so the anonymous
    ;; per-request frame is NOT stamped on the wire (it is omitted). The
    ;; projection still runs under the EXPLICIT frame A — proved by the
    ;; redaction assertions below (only frame A declares the classifications).
    (is (not (contains? payload :rf/frame-id))
        (str where ": anonymous per-request frame omits the wire :rf/frame-id"))
    (is (= :rf/redacted (get-in current [:query :token]))
        (str where ": route-declared sensitive :query :token redacted under frame A"))
    ;; The hydration wire applies no size elision, so the
    ;; route-declared large value rides whole; the sensitive token above is the
    ;; proof the walk ran under frame A.
    (is (= "huge-callback-blob-value" (get-in current [:params :payload]))
        (str where ": route-declared large :params :payload rides whole (no size elision on the hydration wire)"))
    (is (= "/dashboard" (get-in current [:query :return-to]))
        (str where ": the unclassified route sibling rides verbatim"))
    (is (not (.contains (pr-str payload) "secret-oauth-token"))
        (str where ": no raw route token survives anywhere in the payload"))))

(deftest build-payload-honours-explicit-frame-outside-with-frame
  (testing "ring.payload/build-payload called OUTSIDE any rf/with-frame projects
            the runtime-db under the EXPLICIT frame A — the classified route
            :query :token redacts, no raw secret rides. Under an
            ambient-resolving wrapper resolve-current-frame → nil,
            project-routing-egress fails OPEN, and the token would ride raw."
    (setup-frames!)
    (let [payload (binding [rf.frame/*current-frame* nil] (build-a))]
      (assert-frame-a-redacts payload "outside with-frame"))))

(deftest build-payload-explicit-frame-wins-over-ambient
  (testing "with a DIFFERENT frame ambient (B) and frame A passed explicitly,
            frame A's route classification wins — the wrapper THREADS the
            explicit target rather than borrowing the ambient scope.
            Under B's empty registry the token would otherwise ride raw."
    (setup-frames!)
    (is (= ambient-frame (rf/with-frame ambient-frame (rf.frame/resolve-current-frame)))
        "sanity: the ambient frame inside the body is B, not A")
    (let [payload (rf/with-frame ambient-frame (build-a))]
      (assert-frame-a-redacts payload "mismatched ambient B"))))

;; ===========================================================================
;; The non-streaming Ring wrapper fails CLOSED on a frame lost
;; during teardown, mirroring the streaming builder. A frame whose route slice
;; was captured while it was live must not have that slice ride RAW once the
;; frame is destroyed between capture and projection — its classification
;; authority is gone, so app-db + routing fail closed.
;; ===========================================================================

(deftest build-payload-fails-closed-on-destroyed-frame
  (testing "ring.payload/build-payload called
            with an EXPLICIT frame that was destroyed after its app-db /
            runtime-db were captured fails closed: app-db redacts whole and the
            classified route :current slice redacts, so no raw :query :token /
            :params blob / app-db secret rides. Mirrors the streaming builder's
            teardown-race fail-closed."
    (setup-frames!)
    (let [runtime-db (rf.frame/frame-runtime-db-value server-frame)
          app-db     {:secret "app-db-secret" :public "ok"}]
      ;; Teardown race: the request frame is gone before the wrapper projects.
      (rf/destroy-frame! server-frame)
      (let [payload (rf.ssr.ring.payload/build-payload
                      server-frame app-db runtime-db "hash"
                      {:payload :rf.ssr.payload/whole-app-db})]
        ;; The wire :rf/frame-id is decoupled from the (dead) projection
        ;; frame; no `:client-frame-id` opt ⇒ omitted. The fail-closed redaction
        ;; below still proves the projection targeted the destroyed frame A.
        (is (not (contains? payload :rf/frame-id))
            "anonymous per-request frame omits the wire :rf/frame-id")
        (is (= :rf/redacted (:rf/app-db payload))
            "app-db fails closed (project-app-db-egress redacts whole under the dead frame)")
        (is (= :rf/redacted (get-in payload [:rf/runtime-db :rf.runtime/routing]))
            "the routing slice fails closed via project-routing-egress")
        (is (not (.contains (pr-str payload) "secret-oauth-token"))
            "no raw route token survives")
        (is (not (.contains (pr-str payload) "huge-callback-blob-value"))
            "no raw route blob survives")
        (is (not (.contains (pr-str payload) "app-db-secret"))
            "no raw app-db secret survives")))))
