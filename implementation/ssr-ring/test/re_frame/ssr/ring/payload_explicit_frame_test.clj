(ns re-frame.ssr.ring.payload-explicit-frame-test
  "rf2-f02diw — the NON-streaming Ring hydration payload wrapper
  (`re-frame.ssr.ring.payload/build-payload`) must project the runtime-db slice
  under the EXPLICIT carried frame-id, never the ambient `rf/with-frame` scope.

  Before the clean break the wrapper called the one-arity
  `payload-policy/project-runtime-db`, which resolved the projection frame
  AMBIENTLY (`frame/resolve-current-frame`) — while its sibling app-db
  projection (`project-app-db-egress`) already ran at the explicit target. A
  build run OUTSIDE a matching `rf/with-frame` (ambient nil) or under a
  DIFFERENT ambient frame therefore projected the durable route `:current`
  slice under no / the wrong frame's policy: `project-routing-egress` fails
  OPEN with no live frame, so a classified `:query :token` rode the hydration
  blob RAW. The host structural fix (rf2-p026f5) MASKED this by binding the
  request frame around the build, but the wrapper itself was unsound —
  rf2-f02diw threads the explicit frame so correctness no longer depends on a
  matching ambient binding.

  These regressions drive `ring.payload/build-payload` directly with an
  explicit server frame A carrying a classified route slice, once with NO
  ambient scope and once under a MISMATCHED ambient frame B, and prove frame
  A's route classification redacts the token regardless of ambient scope. They
  would FAIL on the pre-clean-break one-arity wrapper — the token would ride
  raw."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.ssr.ring.payload :as payload]
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

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
      (elision/apply-classification-effects
        {:sensitive [[:rf.runtime/routing :current :query :token]]
         :large     [[:rf.runtime/routing :current :params :payload]]})
      (assoc :rf.runtime/routing
             {:current            {:route-id route-id
                                   :query    {:token     "secret-oauth-token"
                                              :return-to "/dashboard"}
                                   :params   {:payload "huge-callback-blob-value"}}
              :pending-navigation {:id "pn-1"}})))

(defn- setup-frames! []
  (rf/reg-frame server-frame
    {:doc      "rf2-f02diw non-streaming ring explicit-frame regression frame A"
     :platform :server})
  ;; Frame B exists as a real (registered) ambient frame with NO classifications
  ;; — the strongest "wrong frame" case: projecting the route slice under B
  ;; consults B's empty registry and the token would ride verbatim.
  (rf/reg-frame ambient-frame
    {:doc      "rf2-f02diw mismatched ambient frame B (no classifications)"
     :platform :server})
  (frame/swap-runtime-db! server-frame (constantly (frame-a-runtime-db))))

(defn- build-a
  "Call the non-streaming Ring wrapper for frame A, reading A's live runtime-db
  value and handing it in as the wrapper's `runtime-db` arg. app-db is empty so
  the test isolates the runtime-db route projection."
  []
  (payload/build-payload
    server-frame {} (frame/frame-runtime-db-value server-frame) "hash"
    {:payload :rf.ssr.payload/whole-app-db}))

(defn- assert-frame-a-redacts [payload where]
  (let [current (get-in payload [:rf/runtime-db :rf.runtime/routing :current])]
    (is (= server-frame (:rf/frame-id payload))
        (str where ": the payload names frame A as its target"))
    (is (= :rf/redacted (get-in current [:query :token]))
        (str where ": route-declared sensitive :query :token redacted under frame A"))
    (is (contains? (get-in current [:params :payload]) :rf.size/large-elided)
        (str where ": route-declared large :params :payload elided under frame A"))
    (is (= "/dashboard" (get-in current [:query :return-to]))
        (str where ": the unclassified route sibling rides verbatim"))
    (is (not (.contains (pr-str payload) "secret-oauth-token"))
        (str where ": no raw route token survives anywhere in the payload"))
    (is (not (.contains (pr-str payload) "huge-callback-blob-value"))
        (str where ": no raw route blob survives"))))

(deftest build-payload-honours-explicit-frame-outside-with-frame
  (testing "ring.payload/build-payload called OUTSIDE any rf/with-frame projects
            the runtime-db under the EXPLICIT frame A — the classified route
            :query / :params redact/elide, no raw secret rides (rf2-f02diw). On
            the pre-clean-break one-arity wrapper resolve-current-frame → nil,
            project-routing-egress fails OPEN, and the token would ride raw."
    (setup-frames!)
    (let [payload (binding [frame/*current-frame* nil] (build-a))]
      (assert-frame-a-redacts payload "outside with-frame"))))

(deftest build-payload-explicit-frame-wins-over-ambient
  (testing "with a DIFFERENT frame ambient (B) and frame A passed explicitly,
            frame A's route classification wins — the wrapper THREADS the
            explicit target rather than borrowing the ambient scope (rf2-f02diw).
            Under B's empty registry the token would otherwise ride raw."
    (setup-frames!)
    (is (= ambient-frame (rf/with-frame ambient-frame (frame/resolve-current-frame)))
        "sanity: the ambient frame inside the body is B, not A")
    (let [payload (rf/with-frame ambient-frame (build-a))]
      (assert-frame-a-redacts payload "mismatched ambient B"))))
