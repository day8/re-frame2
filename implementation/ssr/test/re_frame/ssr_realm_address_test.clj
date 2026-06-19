(ns re-frame.ssr-realm-address-test
  "rf2-bzw8gd / EP-0013 §Realm Conformance: SSR per-frame state (the request /
  response / error-trace / head-snapshot side channels) and the SSR head/route
  registry lookups must carry the frame's REALM, so the SAME frame id in two
  different realms round-trips through SSR with its realm intact and never mixes
  state or registrations across realms.

  Spec 002 §Frames reference realms makes the same frame id legal in two realms
  (frame ids are unique WITHIN a realm). Before this bead the SSR side channels
  keyed on the bare frame id, so two live server frames sharing an id in
  different realms would mix request data, response status/headers/cookies,
  pending error projections, and head snapshots; and the head/route lookups read
  the default realm's registrations instead of the request frame's realm-owned
  ones.

  The fix keys every side channel by the (realm, frame) ADDRESS
  (`re-frame.frame/frame-address`, which collapses to the bare frame id for the
  default realm — so the single-realm round-trip is byte-identical and carries
  NO realm key) and routes the head/route lookups through the carried frame's
  own realm registrar.

  These tests are the round-trip + isolation proofs the bead asked for:
    * a realm-stamped frame's request / response / error-trace / head state
      round-trips through serialize→read with its realm intact;
    * a default-realm frame stays clean (bare-id key, no realm key);
    * the same frame id in two realms isolates every side channel;
    * teardown of a frame in one realm leaves the other realm's same-id state
      intact;
    * `render-head` / `active-head` resolve the carried frame's realm-owned
      head / route registrations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.frame :as frame]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.head :as head]
            [re-frame.ssr.head.registry :as head-registry]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; A module that installs the SAME frame id into whichever realm it is installed
;; against, so two realms can carry a frame by the same id (the bead's central
;; "same frame id in two realms" shape). `:shared/frame` mirrors the bead's
;; `:shared/frame` example.
(defn- shared-app [tag]
  (av/app {:id :addr/app
           :modules
           [(av/module
              {:id     :m
               :frames {:shared/frame {:doc "shared id across realms"}}
               :events {:addr/touch
                        {:handler (fn [{:keys [db]} _] {:db (assoc db :who tag)})}}})]}))

;; ---- default-realm round-trip stays clean (bare-id key, no realm key) -------

(deftest default-realm-frame-keys-by-bare-id
  (testing "a default-realm SSR frame's side-channel slots key by the BARE frame
            id (no realm key) — the round-trip is byte-identical to the pre-realm
            keying"
    (let [f (frame/make-anon-frame-record! {:platform :server :doc "default-realm SSR frame"})]
      ;; frame-address collapses to the bare id for the default realm.
      (is (= f (frame/frame-address f))
          "frame-address of a default-realm frame is the bare frame id")
      (ssr/set-request! f {:uri "/default"})
      (response/swap-response! f (fn [r] (assoc r :status 201)))
      ;; The slot atoms are keyed by the bare frame id — no [realm id] vector.
      (is (contains? @request/request-slots f)
          "the request slot is keyed by the bare frame id")
      (is (contains? @response/response-slots f)
          "the response slot is keyed by the bare frame id")
      (is (= {:uri "/default"} (ssr/get-request f))
          "the request round-trips")
      (is (= 201 (:status (response/response-of f)))
          "the response round-trips"))))

;; ---- two realms, same frame id: request slot isolation ----------------------

(deftest request-slots-isolate-by-realm
  (testing "the same frame id in two realms carries INDEPENDENT request data —
            and round-trips through set-request!→get-request with its realm"
    (let [ra (realm/construct-realm {:id :addr/a})
          rb (realm/construct-realm {:id :addr/b})]
      (try
        (av/install! ra (shared-app :a))
        (av/install! rb (shared-app :b))
        ;; The side-channel ops run UNDER the frame's realm scope, exactly as the
        ;; router binds `*current-realm*` around a real drain.
        (frame/call-with-realm :addr/a
          (fn [] (ssr/set-request! :shared/frame {:uri "/a" :realm :a})))
        (frame/call-with-realm :addr/b
          (fn [] (ssr/set-request! :shared/frame {:uri "/b" :realm :b})))

        (is (= {:uri "/a" :realm :a}
               (frame/call-with-realm :addr/a
                 (fn [] (ssr/get-request :shared/frame))))
            "realm a's frame reads realm a's request — its realm round-trips")
        (is (= {:uri "/b" :realm :b}
               (frame/call-with-realm :addr/b
                 (fn [] (ssr/get-request :shared/frame))))
            "realm b's frame reads realm b's request — no leak from a")
        ;; The slots are keyed by the full (realm, frame) address.
        (is (contains? @request/request-slots [:addr/a :shared/frame])
            "realm a's slot is keyed by the [realm frame] address")
        (is (contains? @request/request-slots [:addr/b :shared/frame])
            "realm b's slot is keyed by the [realm frame] address")
        (finally
          (realm/dispose-realm! :addr/a)
          (realm/dispose-realm! :addr/b))))))

;; ---- two realms, same frame id: response slot isolation + teardown ----------

(deftest response-slots-isolate-and-teardown-is-realm-scoped
  (testing "the same frame id in two realms accumulates INDEPENDENT responses,
            and tearing down one realm's frame leaves the other realm's same-id
            response intact"
    (let [ra (realm/construct-realm {:id :addr/a})
          rb (realm/construct-realm {:id :addr/b})]
      (try
        (av/install! ra (shared-app :a))
        (av/install! rb (shared-app :b))
        (frame/call-with-realm :addr/a
          (fn [] (response/swap-response! :shared/frame (fn [r] (assoc r :status 418)))))
        (frame/call-with-realm :addr/b
          (fn [] (response/swap-response! :shared/frame (fn [r] (assoc r :status 503)))))

        (is (= 418 (frame/call-with-realm :addr/a
                     (fn [] (:status (response/response-of :shared/frame)))))
            "realm a's response status is its own")
        (is (= 503 (frame/call-with-realm :addr/b
                     (fn [] (:status (response/response-of :shared/frame)))))
            "realm b's response status is its own — no cross-realm mix")

        ;; Teardown of realm a's frame must NOT clear realm b's same-id response.
        (frame/call-with-realm :addr/a
          (fn [] (response/clear-response! :shared/frame)))
        (is (not (contains? @response/response-slots [:addr/a :shared/frame]))
            "realm a's response slot was cleared")
        (is (contains? @response/response-slots [:addr/b :shared/frame])
            "realm b's response slot survives realm a's teardown")
        (is (= 503 (frame/call-with-realm :addr/b
                     (fn [] (:status (response/response-of :shared/frame)))))
            "realm b's response is untouched by realm a's teardown")
        (finally
          (realm/dispose-realm! :addr/a)
          (realm/dispose-realm! :addr/b))))))

;; ---- two realms, same frame id: error-trace buffer isolation ----------------
;;
;; The error-trace buffer is framework-private; exercise it through the same
;; (realm, frame) addressing the runtime uses. `buffer-error-trace!` is private,
;; so drive the public clear seam + the address directly to prove the keying.

(deftest pending-error-traces-isolate-by-realm
  (testing "the pending-error-traces buffer keys by the (realm, frame) address —
            clearing one realm's frame leaves the other realm's buffered traces"
    (let [ra (realm/construct-realm {:id :addr/a})
          rb (realm/construct-realm {:id :addr/b})]
      (try
        (av/install! ra (shared-app :a))
        (av/install! rb (shared-app :b))
        ;; Seed each realm's buffer directly at its address (mirrors what the
        ;; always-on error listener does under the realm-bound drain).
        (swap! error-listener/pending-error-traces
               assoc [:addr/a :shared/frame] [{:operation :rf.error/a}])
        (swap! error-listener/pending-error-traces
               assoc [:addr/b :shared/frame] [{:operation :rf.error/b}])

        ;; Clear realm a's frame via the public teardown seam, under realm a.
        (frame/call-with-realm :addr/a
          (fn [] (error-listener/clear-pending-error-traces! :shared/frame)))

        (is (not (contains? @error-listener/pending-error-traces
                            [:addr/a :shared/frame]))
            "realm a's error-trace buffer was cleared")
        (is (contains? @error-listener/pending-error-traces
                       [:addr/b :shared/frame])
            "realm b's error-trace buffer survives realm a's teardown")
        (finally
          (realm/dispose-realm! :addr/a)
          (realm/dispose-realm! :addr/b))))))

;; ---- two realms, same frame id: head-snapshot isolation ---------------------

(defn- reg-head-in-realm!
  "Register a `:head` producer under `head-id` into the realm's OWN registrar.
  `:head` is a step-8-DEFERRED kind that `install!` does not seat — it is
  registered through `reg-head` sugar — so bind the realm's registrar around the
  call (mirrors how `app-value/install!` binds `*registrar*` to seat a realm's
  program, and how the SSR render path binds it to RESOLVE one)."
  [rid head-id head-fn]
  (binding [registrar/*registrar* (realm/registrar (realm/realm rid))]
    (head/reg-head head-id {:doc "realm-scoped head"} head-fn)))

(deftest head-snapshots-isolate-by-realm
  (testing "render-head records the produced head-model under the (realm, frame)
            address, so the same frame id in two realms keeps independent head
            snapshots — and resolves each realm's OWN head registration"
    (let [ra (realm/construct-realm {:id :addr/a})
          rb (realm/construct-realm {:id :addr/b})]
      (try
        ;; install! seats the frame (a wired kind) into each realm.
        (av/install! ra (shared-app :a))
        (av/install! rb (shared-app :b))
        ;; Each realm registers a DIFFERENT head fn under the SAME head id into
        ;; its OWN registrar — proves the render-head lookup is realm-scoped.
        (reg-head-in-realm! :addr/a :addr/title (fn [_db _route] {:title "realm-a"}))
        (reg-head-in-realm! :addr/b :addr/title (fn [_db _route] {:title "realm-b"}))

        (let [model-a (frame/call-with-realm :addr/a
                        (fn [] (head/render-head :addr/title :shared/frame)))
              model-b (frame/call-with-realm :addr/b
                        (fn [] (head/render-head :addr/title :shared/frame)))]
          (is (= {:title "realm-a"} model-a)
              "realm a resolved realm a's OWN head registration")
          (is (= {:title "realm-b"} model-b)
              "realm b resolved realm b's OWN head registration — no default-realm leak"))

        ;; Each realm's snapshot is keyed by its address and holds its own model.
        (is (= {:addr/title {:title "realm-a"}}
               (frame/call-with-realm :addr/a
                 (fn [] (head/head-snapshot :shared/frame))))
            "realm a's head snapshot is its own")
        (is (= {:addr/title {:title "realm-b"}}
               (frame/call-with-realm :addr/b
                 (fn [] (head/head-snapshot :shared/frame))))
            "realm b's head snapshot is its own — independent of realm a")
        (is (contains? @head-registry/head-snapshots [:addr/a :shared/frame])
            "realm a's snapshot keys by the [realm frame] address")
        (is (contains? @head-registry/head-snapshots [:addr/b :shared/frame])
            "realm b's snapshot keys by the [realm frame] address")
        (finally
          (realm/dispose-realm! :addr/a)
          (realm/dispose-realm! :addr/b))))))

;; ---- frame-address resolution semantics ------------------------------------

(deftest frame-address-resolves-carried-then-frame-realm
  (testing "frame-address keys a non-default-realm frame by [realm frame] under
            the carried realm, and a default-realm frame by the bare id"
    (let [ra (realm/construct-realm {:id :addr/a})]
      (try
        (av/install! ra (shared-app :a))
        ;; Under the carried realm: the [realm frame] address.
        (is (= [:addr/a :shared/frame]
               (frame/call-with-realm :addr/a
                 (fn [] (frame/frame-address :shared/frame))))
            "the carried realm produces the [realm frame] address")
        ;; The default-realm frame from the fixture keys by the bare id.
        (is (= :rf/default (frame/frame-address :rf/default))
            "a default-realm frame keys by its bare id (no realm key)")
        (finally
          (realm/dispose-realm! :addr/a))))))
