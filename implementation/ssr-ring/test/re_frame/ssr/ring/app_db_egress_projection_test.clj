(ns re-frame.ssr.ring.app-db-egress-projection-test
  "rf2-bt9kct (+ rf2-mx9w6q dup) — the final hydration app-db slice MUST be
  run through the centralized `:rf.egress/ssr-hydration` projection AFTER the
  `:payload` allowlist, so a frame-classified sensitive child inside an
  allowlisted (or whole-app-db) top-level key redacts before it serializes into
  `:rf/app-db`.

  EP-0015 §14: SSR/hydration is allowlist-FIRST, and frame classification
  COMPOSES as defense-in-depth. The prior `re-frame.ssr.ring.payload/build-payload`
  handed `(apply-policy app-db policy-opts)` straight to `build-payload` — only
  allowlisting, never the frame-owned egress projector. A frame declaring
  `:sensitive {:app-db [[:session :token]]}` and shipping `:session` (via the
  allowlist OR `:rf.ssr.payload/whole-app-db`) serialized the raw token.

  This drives the ACTUAL non-streaming payload-build path
  (`re-frame.ssr.ring.payload/build-payload`) against a registered server frame
  carrying a `:sensitive :app-db` declaration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as privacy]
            [re-frame.ssr.ring.payload :as payload]
            [re-frame.ssr.ring.test-support :as ts]))

(use-fixtures :each ts/reset-runtime)

(def ^:private server-frame :rf.bt9kct/server)

(defn- reg-sensitive-frame! []
  ;; A server frame whose classification marks the nested :session :token path
  ;; sensitive — the canonical durable app-db egress route. EP-0025 B4-ssr
  ;; follow-on (rf2-ux7983): the path is classified through the post-purge
  ;; mechanism — a B3 COMMIT-PLANE `:sensitive` effect the frame's init event
  ;; returns alongside `:db` (EP-0025 §How it works / §Examples) — writing it
  ;; into the per-frame `[:rf.runtime/elision]` registry the
  ;; `:rf.egress/ssr-hydration` egress walk reads. Replaces the retired
  ;; `reg-frame :sensitive {:app-db}` durable annotation (deleted by the
  ;; EP-0025 B1b purge). Value-independent — classified at init, read at egress.
  (rf/reg-event :rf.bt9kct/classify
    (fn [_ _] {:sensitive [[:session :token]]}))
  (rf/reg-frame server-frame
    {:platform       :server
     :initial-events [[:rf.bt9kct/classify]]}))

(def ^:private app-db
  "An app-db whose allowlisted :session key carries a frame-sensitive child
  (:token) alongside a public sibling (:user), plus a server-only :secrets key
  that must never be allowlisted."
  {:session {:token "secret-jwt" :user "alice"}
   :public  {:page :dashboard}
   :secrets {:api-key "internal-only"}})

;; ---- allowlist + projection compose ---------------------------------------

(deftest allowlisted-key-sensitive-child-redacted
  (testing "an allowlisted top-level key (:session) ships, but its
            frame-sensitive child (:token) is redacted by the ssr-hydration
            projection; the public sibling (:user) rides verbatim; unlisted
            keys (:public / :secrets) are omitted by the allowlist"
    (reg-sensitive-frame!)
    (let [out    (payload/build-payload server-frame app-db "h1"
                                        {:payload [:session :public]})
          db     (:rf/app-db out)]
      (is (= privacy/redacted-sentinel (get-in db [:session :token]))
          "the frame-sensitive nested :token is redacted on the hydration wire")
      (is (= "alice" (get-in db [:session :user]))
          "the public sibling rides verbatim")
      (is (= {:page :dashboard} (:public db))
          "another allowlisted key rides through the projection unchanged")
      (is (not (contains? db :secrets))
          ":secrets (unlisted) is omitted by the allowlist")
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw token survives anywhere in the emitted payload"))))

(deftest whole-app-db-still-projects-sensitive-children
  (testing "an explicit :rf.ssr.payload/whole-app-db opt-in ships every key,
            but STILL projects frame-sensitive children — the raw token never
            rides even when the whole app-db is opted in"
    (reg-sensitive-frame!)
    (let [out (payload/build-payload server-frame app-db "h1"
                                     {:payload :rf.ssr.payload/whole-app-db})
          db  (:rf/app-db out)]
      (is (= privacy/redacted-sentinel (get-in db [:session :token]))
          "whole-app-db still redacts the frame-sensitive :token")
      (is (= "alice" (get-in db [:session :user])))
      (is (= "internal-only" (get-in db [:secrets :api-key]))
          ":secrets rides under whole-app-db opt-in (no frame mark on it)")
      (is (not (.contains (pr-str out) "secret-jwt"))))))

;; ---- fail-closed: an unknown / unregistered frame redacts the whole slice --

(deftest missing-frame-policy-fails-closed
  (testing "building the payload against an UNREGISTERED frame (no resolvable
            elision policy) fails CLOSED — the whole app-db slice redacts to
            the :rf/redacted sentinel rather than ship under no policy"
    ;; deliberately do NOT register :rf.bt9kct/ghost
    (let [out (payload/build-payload :rf.bt9kct/ghost app-db "h1"
                                     {:payload [:session]})]
      (is (= privacy/redacted-sentinel (:rf/app-db out))
          "an unresolvable frame redacts the whole slice (fail-closed)")
      (is (not (.contains (pr-str out) "secret-jwt"))
          "no raw value escapes under a missing frame policy"))))

(deftest no-classification-frame-rides-allowlisted-slice-verbatim
  (testing "a registered frame with NO :sensitive classification ships its
            allowlisted slice verbatim (the projection is a precise
            classification walk, not a blanket scrub)"
    (rf/reg-frame :rf.bt9kct/plain {:platform :server})
    (let [out (payload/build-payload :rf.bt9kct/plain app-db "h1"
                                     {:payload [:session]})
          db  (:rf/app-db out)]
      (is (= {:token "secret-jwt" :user "alice"} (:session db))
          "no frame classification → allowlisted slice rides verbatim")
      (is (not (contains? db :public))))))
