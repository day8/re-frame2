(ns re-frame.ssr-streaming-hydration-egress-test
  "rf2-uc3cs4 — streaming per-subtree hydration DELTAS obey the same
  allowlist-first-then-`:rf.egress/ssr-hydration`-project boundary the final
  `__rf_payload` does (rf2-bt9kct, EP-0015 §14).

  A streaming delta is browser-delivered hydration state — it arrives in the
  stream BEFORE the final payload and the client merges it into the live
  app-db. `subtree-delta` computes it as the raw changed/new top-level keys
  each mapped to the FULL after-db value, and the Ring adapter serialized
  `(pr-str delta)` directly. So a continuation mutating `:secret` streamed
  `{:secret …}` even when the handler `:payload` allowlist named only public
  keys, and a frame-sensitive child under an allowed changed key rode raw.

  `re-frame.ssr.streaming/project-delta` is the fix — the pure helper the Ring
  adapter calls before serialising the delta script. This pins it directly
  (allowlist drop + sensitive-child redaction + the empty-delta short-circuit)
  AND the streaming final-payload's app-db projection (the streaming arm of
  rf2-bt9kct) through the actual `build-final-payload` path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(def ^:private sframe :rf.uc3cs4/server)

(defn- reg-sensitive-server-frame!
  "Register a server frame whose classification marks [:session :token]
  sensitive and seed its app-db with a sensitive child + public siblings.

  EP-0025 B4-ssr follow-on (rf2-ux7983): the durable `:session :token` app-db
  path is classified through the post-purge mechanism — a B3 COMMIT-PLANE
  `:sensitive` effect returned by the frame's init event alongside `:db`
  (EP-0025 §How it works / §Examples). The effect writes the path into the
  per-frame `[:rf.runtime/elision]` registry the `:rf.egress/ssr-hydration`
  egress walk reads, replacing the retired `reg-frame :sensitive {:app-db}`
  durable annotation (deleted by the EP-0025 B1b purge)."
  [db]
  (rf/reg-event :rf.uc3cs4/seed
    (fn [_ [_ v]]
      {:db        v
       :sensitive [[:session :token]]}))
  (rf/reg-frame sframe
    {:platform       :server
     :initial-events [[:rf.uc3cs4/seed db]]}))

;; ---- project-delta: allowlist + projection on the streaming delta ---------

(deftest off-allowlist-changed-key-dropped-from-delta
  (testing "a continuation that changed an OFF-ALLOWLIST key (:secret) does
            NOT ride the streaming delta — the handler :payload allowlist drops
            it before the delta script is written"
    (reg-sensitive-server-frame! {})
    (let [;; the continuation mutated :secret (off-allowlist) and :public (allowed)
          raw-delta {:secret {:api-key "leak-me"}
                     :public {:page :dashboard}}
          projected (rf/with-frame sframe
                      (streaming/project-delta raw-delta sframe {:payload [:public]}))]
      (is (not (contains? projected :secret))
          ":secret (off-allowlist) is dropped from the streaming delta")
      (is (= {:page :dashboard} (:public projected))
          "the allowlisted changed key rides the delta")
      (is (not (.contains (pr-str projected) "leak-me"))
          "no off-allowlist secret survives in the projected delta"))))

(deftest sensitive-child-redacted-in-delta
  (testing "a frame-sensitive child (:token) inside an ALLOWED changed key
            (:session) is redacted in the streaming delta by the ssr-hydration
            projection; the public sibling rides verbatim"
    (reg-sensitive-server-frame! {})
    (let [raw-delta {:session {:token "secret-jwt" :user "bob"}}
          projected (rf/with-frame sframe
                      (streaming/project-delta raw-delta sframe {:payload [:session]}))]
      (is (= privacy/redacted-sentinel (get-in projected [:session :token]))
          "the frame-sensitive nested :token is redacted in the streamed delta")
      (is (= "bob" (get-in projected [:session :user]))
          "the public sibling rides verbatim")
      (is (not (.contains (pr-str projected) "secret-jwt"))
          "no raw token survives in the streamed delta"))))

(deftest whole-app-db-delta-still-redacts-sensitive-child
  (testing ":rf.ssr.payload/whole-app-db keeps every changed key in the delta
            but STILL redacts a frame-sensitive child"
    (reg-sensitive-server-frame! {})
    (let [raw-delta {:session {:token "secret-jwt" :user "bob"}
                     :secret  {:api-key "x"}}
          projected (rf/with-frame sframe
                      (streaming/project-delta
                        raw-delta sframe {:payload :rf.ssr.payload/whole-app-db}))]
      (is (= privacy/redacted-sentinel (get-in projected [:session :token])))
      (is (contains? projected :secret)
          "whole-app-db keeps the changed :secret key (no allowlist filtering)")
      (is (not (.contains (pr-str projected) "secret-jwt"))))))

(deftest empty-and-all-dropped-deltas-short-circuit
  (testing "an empty delta, and a delta whose every changed key is
            off-allowlist, both project to {} so the host emits no delta script"
    (reg-sensitive-server-frame! {})
    (rf/with-frame sframe
      (is (= {} (streaming/project-delta {} sframe {:payload [:public]}))
          "empty delta → {}")
      (is (= {} (streaming/project-delta {:secret {:k 1}} sframe {:payload [:public]}))
          "all-off-allowlist delta → {} (no :rf/redacted scalar)"))))

;; ---- the streaming arm of rf2-bt9kct: build-final-payload's :rf/app-db -----

(deftest streaming-final-payload-redacts-sensitive-app-db-child
  (testing "build-final-payload's :rf/app-db runs the allowlisted slice through
            the ssr-hydration projection so a frame-sensitive child redacts in
            the final __rf_payload (the streaming arm of rf2-bt9kct)"
    (reg-sensitive-server-frame!
      {:session {:token "secret-jwt-final" :user "carol"}
       :public  {:page :home}
       :secrets {:api-key "internal"}})
    (let [payload (rf/with-frame sframe
                    (streaming/build-final-payload
                      sframe "h1" {:version 1 :payload [:session :public]}))
          db      (:rf/app-db payload)]
      (is (= privacy/redacted-sentinel (get-in db [:session :token]))
          "the frame-sensitive :token redacts in the streaming final payload")
      (is (= "carol" (get-in db [:session :user])))
      (is (= {:page :home} (:public db)))
      (is (not (contains? db :secrets)) ":secrets (unlisted) omitted by allowlist")
      (is (not (.contains (pr-str payload) "secret-jwt-final"))
          "no raw token survives in the streaming final payload"))))
