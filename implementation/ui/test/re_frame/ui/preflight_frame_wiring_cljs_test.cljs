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

(defn- err-data [thunk]
  (try (thunk) nil
       (catch cljs.core/ExceptionInfo e (ex-data e))))

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

(deftest cross-root-conflict-ex-data-carries-config-fingerprint-shape
  ;; The reconciled ex-data contract (rf2-vxgfnd.33 / 004C §7): the runtime
  ;; plan-conflict arm carries :config-fingerprint (NOT :digest) in BOTH the
  ;; :installed record and the :arriving slot, so tooling written to the 004C
  ;; roster finds the fingerprint under the spec-named key. (:extra merges to
  ;; top-level ex-data keys — see re-frame.error/thrown-ex-info.)
  (reg-events!)
  (frames/execute-frame-plans!
   :page/shop [(plan :frame/session {} "cf1-1111111111111111")])
  (let [data (err-data
              #(frames/execute-frame-plans!
                :page/admin [(plan :frame/session {} "cf1-2222222222222222")]))]
    (is (= :rf.error/frame-payload-conflict (:rf.error/id data)))
    (is (= :frame/session (:frame-id data)))
    (is (= {:config-fingerprint "cf1-1111111111111111" :installed-by :page/shop}
           (:installed data))
        ":installed carries :config-fingerprint + :installed-by (004C §7)")
    (is (= {:config-fingerprint "cf1-2222222222222222" :root-id :page/admin}
           (:arriving data))
        ":arriving carries :config-fingerprint + :root-id (004C §7) — never :digest")))

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

;; ---- ADOPT — a live plan-less (boot-created) frame (rf2-vxgfnd.26) --------
;; ENSURE is create-if-absent (03 §8 / 004C §6): when a plan meets a frame that
;; is already LIVE but was never plan-installed (a boot `rf/make-frame`), the
;; frame is ADOPTED — its config + image generation are AUTHORITATIVE and left
;; untouched. Only the plan's fingerprint is recorded (for cross-root conflict
;; scoping). Regression: a config-carrying `make-frame` used to run here, swapping
;; the generation to the default (discarding the boot `:images`) and wiping the
;; boot config (`:fx-overrides` / `:doc` / …); the durable app-db survived, which
;; masked the clobber.

(deftest adoption-of-a-live-plan-less-frame-preserves-config-and-generation
  ;; boot an app frame with a real image generation + rich config, OUTSIDE the
  ;; plan registry (the app's own rf/make-frame at boot).
  (rf/make-frame {:id :app/session
                  :images       [(rf/image {:id :app/img})]
                  :fx-overrides {:app/save :app/mock-save}
                  :doc          "boot-authored config"})
  (let [gen0 (frame/frame-generation :app/session)]
    (is (some? gen0) "sanity: the boot frame resolved an image generation")
    (is (nil? (frames/installed-plan-entry :app/session))
        "sanity: the boot frame is LIVE but PLAN-LESS (no install record)")
    ;; a root declares [frame-root {:id :app/session}] purely to SHARE it — a
    ;; plain, config-less plan.
    (frames/execute-frame-plans!
     :page/app [(plan :app/session {} "cf1-adopt-aaaaaaaaa")])
    (is (identical? gen0 (frame/frame-generation :app/session))
        "the boot generation is the SAME object — NOT re-resolved to the default")
    (is (= [:app/img]
           (mapv :rf.image/id (:rf.gen/images (frame/frame-generation :app/session))))
        "the boot frame's :images are preserved (not discarded for the default)")
    (is (= {:app/save :app/mock-save}
           (:fx-overrides (frame/frame-meta :app/session)))
        ":fx-overrides survive adoption (the boot config is not replaced wholesale)")
    (is (= "boot-authored config" (:doc (frame/frame-meta :app/session)))
        "the boot :doc config survives adoption")
    (is (= {:config-fingerprint "cf1-adopt-aaaaaaaaa" :adopted-by :page/app :adopted true}
           (frames/installed-plan-entry :app/session))
        "adoption records a NON-OWNING record (fingerprint + adopter, marked :adopted) — never installed-by")))

(deftest adoption-then-same-fingerprint-second-root-is-a-found-live-no-op
  (rf/make-frame {:id :app/session :images [(rf/image {:id :app/img})]})
  (let [gen0 (frame/frame-generation :app/session)
        p    (plan :app/session {} "cf1-adopt-bbbbbbbbb")]
    (frames/execute-frame-plans! :page/one [p])
    (is (= :page/one (:adopted-by (frames/installed-plan-entry :app/session)))
        "the first adopter is recorded")
    ;; a second root sharing the same frame with the same (config-less) plan
    (frames/execute-frame-plans! :page/two [p])
    (is (identical? gen0 (frame/frame-generation :app/session))
        "the second-root found-live pass leaves the generation untouched too")
    (is (= :page/one (:adopted-by (frames/installed-plan-entry :app/session)))
        "found-live writes nothing — the first adopter is retained")))

(deftest adoption-coexists-with-a-fresh-install-in-the-same-root
  ;; both ways in one run: adopt a live boot frame AND install a genuinely-new
  ;; one — the new-frame INSTALL path still creates + seeds correctly.
  (reg-events!)
  (rf/make-frame {:id :app/session
                  :images       [(rf/image {:id :app/img})]
                  :fx-overrides {:app/save :app/mock-save}})
  (let [gen0 (frame/frame-generation :app/session)]
    (frames/execute-frame-plans!
     :page/app
     [(plan :app/session {} "cf1-adopt-ccccccccc")
      (plan :app/fresh {:initial-events [[:test/set-db {:n 5}]]} "cf1-fresh-ddddddddd")])
    ;; adopt arm — untouched
    (is (identical? gen0 (frame/frame-generation :app/session))
        "the adopted boot frame's generation is untouched")
    (is (= {:app/save :app/mock-save}
           (:fx-overrides (frame/frame-meta :app/session)))
        "the adopted boot frame keeps its :fx-overrides")
    ;; install arm — the genuinely-new frame is created + seeded
    (is (some? (frame/frame :app/fresh)) "the absent frame is created (install)")
    (is (= 5 (:n (rf/app-db-value :app/fresh)))
        ":initial-events drain synchronously for the newly-installed frame")
    (is (= {:config-fingerprint "cf1-fresh-ddddddddd" :installed-by :page/app}
           (frames/installed-plan-entry :app/fresh))
        "the new install is recorded with its fingerprint + installing root")))

;; ---- rf2-vxgfnd.30 (a): destroy PRUNES the install record ----------------
;; `installed-plan-entry` used to only HIDE a dead record behind a liveness
;; guard, so a boot re-create of the same id resurrected the stale record as a
;; false frame-payload-conflict blaming the dead lifetime's installer. The
;; `:ui/on-frame-destroyed!` hook now prunes the record on destroy.

(deftest destroy-then-recreate-then-replan-no-false-conflict
  (reg-events!)
  ;; root A installs :frame/f (fingerprint X)
  (frames/execute-frame-plans!
   :root/a [(plan :frame/f {:initial-events [[:test/set-db {:n 1}]]}
                  "cf1-x111111111111111")])
  (frame/destroy-frame! :frame/f)
  (is (nil? (frames/installed-plan-entry :frame/f))
      "the destroyed frame's install record is pruned, not merely hidden")
  ;; a boot rf/make-frame re-creates :frame/f OUTSIDE the plan registry
  (rf/make-frame {:id :frame/f :doc "re-booted"})
  (is (nil? (frames/installed-plan-entry :frame/f))
      "the re-created frame carries NO resurrected record from the dead lifetime")
  ;; root B (fingerprint Y) declares a config-less [frame-root {:id :frame/f}]
  ;; to SCOPE the re-booted frame — it ADOPTS cleanly, NO false conflict on A.
  (frames/execute-frame-plans! :root/b [(plan :frame/f {} "cf1-y222222222222222")])
  (is (= :root/b (:adopted-by (frames/installed-plan-entry :frame/f)))
      "root B adopts the re-booted frame — the dead A-lifetime record never resurrected")
  (is (= "re-booted" (:doc (frame/frame-meta :frame/f)))
      "the re-booted boot config is authoritative (adoption does not clobber it)"))

;; ---- rf2-vxgfnd.30 (b): a failed mount tags surviving records -------------
;; When a later plan in a root's run throws, the siblings it already installed
;; stay live (irreversible :initial-events) but the mount did NOT complete —
;; their records are tagged :mount-incomplete so a later conflict names an
;; incomplete mount, not a phantom completed owner.

(deftest failed-mount-tags-surviving-records-mount-incomplete
  (reg-events!)
  ;; root A: plan 1 installs :frame/ok; plan 2 (:frame/bad) throws in make-frame.
  (let [id (err-id
            #(frames/execute-frame-plans!
              :root/a [(plan :frame/ok {:initial-events [[:test/set-db {:n 1}]]}
                             "cf1-ok11111111111111")
                       (plan :frame/bad {:initial-events [:not-a-vector]}
                             "cf1-bad1111111111111")]))]
    (is (some? id) "the malformed sibling plan throws — the mount fails"))
  (is (some? (frame/frame :frame/ok))
      "the earlier sibling stays live (irreversible-fx atomicity posture)")
  (let [entry (frames/installed-plan-entry :frame/ok)]
    (is (= :root/a (:installed-by entry)) "the record still names its installing root")
    (is (true? (:mount-incomplete entry))
        "but is tagged :mount-incomplete — the root's mount never completed"))
  ;; a later cross-root config conflict on :frame/ok names root A AND flags the
  ;; incomplete mount, instead of presenting A as a clean completed owner.
  (let [msg (try (frames/execute-frame-plans!
                  :root/c [(plan :frame/ok {:initial-events [[:test/set-db {:n 9}]]}
                                 "cf1-ccc1111111111111")])
                 nil
                 (catch cljs.core/ExceptionInfo e (ex-message e)))]
    (is (some? msg) "the cross-root arrival still fails loud")
    (is (some? (re-find #"did NOT complete" msg))
        "the conflict diagnostic flags the phantom installer's incomplete mount")))

(deftest completed-retry-clears-the-mount-incomplete-tag
  (reg-events!)
  ;; A's first mount fails at plan 2 -> :frame/ok tagged incomplete
  (err-id #(frames/execute-frame-plans!
            :root/a [(plan :frame/ok {:initial-events [[:test/set-db {:n 1}]]}
                           "cf1-ok22222222222222")
                     (plan :frame/bad {:initial-events [:not-a-vector]}
                           "cf1-bad2222222222222")]))
  (is (true? (:mount-incomplete (frames/installed-plan-entry :frame/ok)))
      "sanity: the failed mount tagged the surviving record")
  ;; A retries WITHOUT the bad plan -> the mount completes -> the tag clears.
  (frames/execute-frame-plans!
   :root/a [(plan :frame/ok {:initial-events [[:test/set-db {:n 1}]]}
                  "cf1-ok22222222222222")])
  (let [entry (frames/installed-plan-entry :frame/ok)]
    (is (nil? (:mount-incomplete entry))
        "a completed run clears the stale :mount-incomplete tag")
    (is (= :root/a (:installed-by entry)) "the ownership record is intact")))

;; ---- rf2-vxgfnd.56: adoption never grants installation authority ---------
;; A boot/external frame is boot-authoritative. A config-less [frame-root
;; {:id …}] may ADOPT (scope) it, but an adopted record must NEVER enter a
;; root-owned refresh path, and a CONFIG-BEARING plan meeting a boot frame
;; must fail loud rather than silently discard its config then later clobber.

(deftest config-bearing-plan-against-a-boot-frame-fails-loud
  (reg-events!)
  ;; a boot rf/make-frame owns :app/boot with authoritative config + state
  (rf/make-frame {:id :app/boot :doc "boot-authoritative"})
  (rf/dispatch-sync [:test/set-db {:n 42}] {:frame :app/boot})
  (let [id (err-id
            #(frames/execute-frame-plans!
              :page/x [(plan :app/boot {:initial-events [[:test/set-db {:n 0}]]}
                             "cf1-cfgbearingaaaa1")]))]
    (is (= :rf.error/frame-payload-conflict id)
        "a config-BEARING frame-root cannot install its config over a boot frame"))
  (is (= 42 (:n (rf/app-db-value :app/boot)))
      "preflight rollback: boot state untouched — the conflict fails before any write")
  (is (= "boot-authoritative" (:doc (frame/frame-meta :app/boot)))
      "the boot config is untouched")
  (is (nil? (frames/installed-plan-entry :app/boot))
      "no record is written for the rejected config-bearing plan"))

(deftest adopted-frame-later-config-bearing-same-root-cannot-refresh
  ;; THE rf2-vxgfnd.56 repro: boot-authoritative -> config-less adopt (A) ->
  ;; a later same-root CONFIG-BEARING plan (B) must NOT be classified :refresh
  ;; and make-frame over the boot state.
  (reg-events!)
  (rf/make-frame {:id :app/boot :doc "boot-authoritative"})
  (rf/dispatch-sync [:test/set-db {:n 7}] {:frame :app/boot})
  ;; step A: a config-less frame-root adopts the boot frame (scope only)
  (frames/execute-frame-plans! :page/x [(plan :app/boot {} "cf1-adopt-eeeeeeeee")])
  (is (= :page/x (:adopted-by (frames/installed-plan-entry :app/boot)))
      "the config-less plan adopts the boot frame (non-owning record)")
  ;; step B: the SAME root now sends a CONFIG-BEARING plan (different fp)
  (let [id (err-id
            #(frames/execute-frame-plans!
              :page/x [(plan :app/boot {:initial-events [[:test/set-db {:n 999}]]}
                             "cf1-refresh-ffffff1")]))]
    (is (= :rf.error/frame-payload-conflict id)
        "an adopt record confers NO refresh rights — the config-bearing edit fails loud"))
  (is (= 7 (:n (rf/app-db-value :app/boot)))
      "boot authority preserved: no make-frame, no reseed, no clobber")
  (is (= "boot-authoritative" (:doc (frame/frame-meta :app/boot)))
      "the boot config survives the rejected refresh"))

(deftest boot-frame-destroyed-then-absent-plan-installs-a-root-owned-lifetime
  ;; acceptance: destroying the external frame invalidates the adoption record;
  ;; a later absent-frame plan installs a NEW root-owned lifetime and then uses
  ;; normal same-owner refresh semantics.
  (reg-events!)
  (rf/make-frame {:id :app/boot :doc "boot-authoritative"})
  (frames/execute-frame-plans! :page/x [(plan :app/boot {} "cf1-adopt-ggggggggg")])
  (is (:adopted (frames/installed-plan-entry :app/boot)) "sanity: adopted")
  (frame/destroy-frame! :app/boot)
  (is (nil? (frames/installed-plan-entry :app/boot))
      "destroy prunes the adoption record")
  ;; now an absent-frame config-bearing plan INSTALLS a root-owned lifetime
  (frames/execute-frame-plans!
   :page/x [(plan :app/boot {:initial-events [[:test/set-db {:n 3}]]}
                  "cf1-install-hhhhhh1")])
  (is (= :page/x (:installed-by (frames/installed-plan-entry :app/boot)))
      "the re-declared plan now OWNS the frame (installed-by, not adopted)")
  (is (= 3 (:n (rf/app-db-value :app/boot))) "the new lifetime re-seeds")
  ;; and normal same-owner refresh now works over the root-owned frame
  (frames/execute-frame-plans!
   :page/x [(plan :app/boot {:initial-events [[:test/set-db {:n 3}]]}
                  "cf1-install-iiiiii2")])
  (is (= "cf1-install-iiiiii2"
         (:config-fingerprint (frames/installed-plan-entry :app/boot)))
      "same-owner refresh advances the fingerprint on the now root-owned frame"))

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
