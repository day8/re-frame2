(ns re-frame.ui.preflight-frame-wiring-cljs-test
  "S2c (rf2-vxgfnd.9) — the LIVE preflight ENSURE executor + the ambient
  frame chain, host-agnostic (node; no React). The mount-integration +
  Q49 + provider-scope-context arms live in the DOM twin
  (`re-frame.ui.preflight-frame-wiring-dom-cljs-test`).

  G-4 (preflight ENSURE): install / :initial-events drain / idempotent
  no-op (no re-seed) / same-root refresh / cross-root config conflict /
  zero frame residue on a failing plan / destroy-then-new-lifetime replay.

  G-6 (frame scope + ambient chain): resolve-frame's four tiers
  (pin > dynamic > React context > loud), require-scope-frame!'s three
  fail-loud boundaries, and the provider scope element carrying the frame
  into the shared React context."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.frames :as frames]))

;; `:ambient-frame nil` — opt out of the `:rf/default` ambient scope so
;; (a) our top-level make-frame ENSURE drains :initial-events synchronously
;; (not as a mid-cascade child creation), and (b) the ambient-chain tests
;; see a genuinely CLEAR scope (no dynamic binding) to exercise the React
;; context + loud tiers.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t] (frames/reset-installed-plans!) (t) (frames/reset-installed-plans!)))

(defn- reg-events! []
  (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
  (rf/reg-event :test/inc    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})))

(defn- err-id [thunk]
  (try (thunk) nil
       (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- plan
  ([frame-id] (plan frame-id {} "cf1-aaaaaaaaaaaaaaaa"))
  ([frame-id config fp]
   {:frame-id frame-id :config config :config-fingerprint fp}))

;; ===========================================================================
;; G-4 — preflight ENSURE
;; ===========================================================================

(deftest install-creates-frame-and-drains-initial-events
  (reg-events!)
  (frames/execute-frame-plans!
   :page/shop
   [(plan :frame/session {:initial-events [[:test/set-db {:n 3}] [:test/inc]]}
          "cf1-1111111111111111")])
  (is (some? (frame/frame :frame/session)) "the absent frame is created")
  (is (= 4 (:n (rf/app-db-value :frame/session)))
      ":initial-events drained synchronously at preflight, in order")
  (is (= {:config-fingerprint "cf1-1111111111111111" :installed-by :page/shop}
         (frames/installed-plan-entry :frame/session))
      "the install is recorded with its fingerprint + installing root"))

(deftest same-fingerprint-reinstall-is-a-pure-no-op
  (reg-events!)
  (let [p (plan :frame/session {:initial-events [[:test/set-db {:n 1}]]}
                "cf1-2222222222222222")]
    (frames/execute-frame-plans! :page/shop [p])
    ;; mutate durable state, then re-run the SAME plan (HMR / remount /
    ;; a second root referencing the same live frame)
    (rf/dispatch-sync [:test/inc] {:frame :frame/session})
    (is (= 2 (:n (rf/app-db-value :frame/session))))
    (frames/execute-frame-plans! :page/other [p])
    (is (= 2 (:n (rf/app-db-value :frame/session)))
        "found-live same-fingerprint = no re-seed (the HMR / shared-frame guarantee)")
    (is (= :page/shop (:installed-by (frames/installed-plan-entry :frame/session)))
        "the original installer is retained (a found-live plan writes nothing)")))

(deftest same-root-different-fingerprint-refreshes-without-replay
  (reg-events!)
  (frames/execute-frame-plans!
   :page/shop [(plan :frame/session {:initial-events [[:test/set-db {:n 7}]]}
                     "cf1-3333333333333333")])
  (rf/dispatch-sync [:test/inc] {:frame :frame/session})
  (is (= 8 (:n (rf/app-db-value :frame/session))))
  ;; the SAME root re-declares its own frame with new config (an HMR edit)
  (frames/execute-frame-plans!
   :page/shop [(plan :frame/session {:initial-events [[:test/set-db {:n 99}]]}
                     "cf1-4444444444444444")])
  (is (= 8 (:n (rf/app-db-value :frame/session)))
      "surgical refresh: durable state preserved, :initial-events RE-RECORDED not REPLAYED")
  (is (= "cf1-4444444444444444"
         (:config-fingerprint (frames/installed-plan-entry :frame/session)))
      "the recorded fingerprint advances to the re-declared config"))

(deftest cross-root-config-conflict-fails-the-arriving-root
  (reg-events!)
  (frames/execute-frame-plans!
   :page/shop [(plan :frame/session {:initial-events [[:test/set-db {:n 1}]]}
                     "cf1-5555555555555555")])
  (let [id (err-id
            #(frames/execute-frame-plans!
              :page/admin [(plan :frame/session
                                 {:initial-events [[:test/set-db {:n 2}]]}
                                 "cf1-6666666666666666")]))]
    (is (= :rf.error/frame-payload-conflict id)
        "a DIFFERENT root with a differing fingerprint fails loud"))
  (is (= 1 (:n (rf/app-db-value :frame/session)))
      "the installed frame is untouched — no last-wins overwrite")
  (is (= :page/shop (:installed-by (frames/installed-plan-entry :frame/session)))
      "the installing root's record survives the rejected arrival"))

(deftest conflict-detection-is-pure-before-any-install
  ;; a root carries a GOOD plan followed by a CONFLICTING plan; conflict
  ;; detection runs over ALL plans FIRST, so the good plan never installs.
  (reg-events!)
  (frames/execute-frame-plans!
   :owner/a [(plan :frame/taken {} "cf1-7777777777777777")])
  (let [id (err-id
            #(frames/execute-frame-plans!
              :owner/b [(plan :frame/fresh {:initial-events [[:test/set-db {:n 1}]]}
                              "cf1-8888888888888888")
                        (plan :frame/taken {} "cf1-9999999999999999")]))]
    (is (= :rf.error/frame-payload-conflict id))
    (is (nil? (frame/frame :frame/fresh))
        "the earlier good plan never installed — conflict fails with ZERO writes")))

(deftest failing-plan-leaves-zero-frame-residue
  ;; a plan whose make-frame throws (a malformed :initial-events step) leaves
  ;; NO frame and NO install record; an earlier sibling plan stays live.
  (reg-events!)
  (frames/execute-frame-plans!
   :owner/x [(plan :frame/good {:initial-events [[:test/set-db {:n 5}]]}
                   "cf1-aaaaaaaaaaaaaaa1")])
  (let [id (err-id
            #(frames/execute-frame-plans!
              :owner/y [(plan :frame/bad {:initial-events [:not-a-vector]}
                              "cf1-bbbbbbbbbbbbbbb1")]))]
    (is (some? id) "the malformed plan throws"))
  (is (nil? (frame/frame :frame/bad)) "no half-registered frame residue")
  (is (nil? (frames/installed-plan-entry :frame/bad)) "no install record")
  (is (some? (frame/frame :frame/good))
      "the earlier sibling plan stays live (irreversible-fx atomicity posture)"))

(deftest destroy-then-replan-is-a-new-lifetime
  (reg-events!)
  (let [p (plan :frame/session {:initial-events [[:test/set-db {:n 1}]]}
                "cf1-ccccccccccccccc1")]
    (frames/execute-frame-plans! :page/shop [p])
    (is (some? (frame/frame :frame/session)) "sanity: the frame is live")
    (is (some? (frames/installed-plan-entry :frame/session))
        "sanity: the install is recorded while the frame is live")
    (frame/destroy-frame! :frame/session)
    (is (nil? (frames/installed-plan-entry :frame/session))
        "a destroyed frame invalidates its install record")
    ;; a later plan for the same id is a genuinely new lifetime: created
    ;; fresh, :initial-events REPLAYED (destroy-then-reseat composition)
    (frames/execute-frame-plans! :page/shop [p])
    (is (= 1 (:n (rf/app-db-value :frame/session)))
        "new lifetime re-seeds from :initial-events")))

;; ===========================================================================
;; G-6 — the ambient frame chain + scope
;; ===========================================================================

(defn- with-context [frame-kw thunk]
  (let [ctx adapter-context/frame-context]
    (set! (.-_currentValue ^js ctx) frame-kw)
    (try (thunk)
         (finally (set! (.-_currentValue ^js ctx)
                        adapter-context/no-provider-sentinel)))))

(deftest resolve-frame-explicit-pin-wins
  (rf/make-frame {:id :app/pinned :doc "pin target"})
  (is (= :app/pinned (frames/resolve-frame :app/pinned :subscribe 'test))
      "a keyword pin resolves to its id")
  (with-context :app/ctx
    (fn []
      (binding [frame/*current-frame* :app/dyn]
        (is (= :app/pinned (frames/resolve-frame :app/pinned :subscribe 'test))
            "the explicit pin beats both the dynamic binding and the context")))))

(deftest resolve-frame-dynamic-binding-tier
  (binding [frame/*current-frame* :app/dyn]
    (is (= :app/dyn (frames/resolve-frame :subscribe 'test))
        "with no pin, the dynamic binding resolves")))

(deftest resolve-frame-react-context-tier
  (with-context :app/ctx
    (fn []
      (is (= :app/ctx (frames/resolve-frame :subscribe 'test))
          "with no pin and no dynamic binding, the React context resolves")))
  (with-context :app/ctx
    (fn []
      (binding [frame/*current-frame* :app/dyn]
        (is (= :app/dyn (frames/resolve-frame :subscribe 'test))
            "the dynamic binding beats the context tier")))))

(deftest resolve-frame-loud-when-no-scope
  (is (= :rf.error/no-frame-context
         (err-id #(frames/resolve-frame :subscribe 'test)))
      "no pin, no dynamic binding, no provider context -> loud, never :rf/default"))

;; ---- require-scope-frame! — the frame-provider SCOPE validation ----------

(deftest require-scope-frame-live-frame-resolves
  (rf/make-frame {:id :app/live :doc "a live scope target"})
  (is (= :app/live (frames/require-scope-frame! :app/live 'test))
      "a live frame id scopes")
  (is (= :app/live (frames/require-scope-frame! (lf/make-frame {:id :app/live})
                                                'test))
      "a live frame VALUE normalises to its id"))

(deftest require-scope-frame-absent-fails-loud
  (is (= :rf.error/frame-provider-frame-absent
         (err-id #(frames/require-scope-frame! :app/ghost 'test)))
      "scoping a frame that was never created fails loud"))

(deftest require-scope-frame-nil-and-bad-arg
  (is (= :rf.error/no-frame-context
         (err-id #(frames/require-scope-frame! nil 'test)))
      "a nil :frame establishes no scope -> no-frame-context")
  (is (= :rf.error/bad-frame-provider-arg
         (err-id #(frames/require-scope-frame! "app" 'test)))
      "a non-keyword non-frame-value :frame is a bad provider arg"))

(deftest provider-scope-element-carries-the-frame-into-context
  (rf/make-frame {:id :app/live :doc "scope target"})
  (let [el (frames/provider-scope-element :app/live #js ["child"])]
    (is (= :app/live (.. el -props -value))
        "the scope element wraps children in the frame-context Provider")))
