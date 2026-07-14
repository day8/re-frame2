(ns re-frame.ui.preflight-authority-cljs-test
  "rf2-5svfa1 — the bounded, non-design-bearing core split out of parent
  rf2-vxgfnd.191: preflight ENSURE binds its plan decisions to the EXACT frame
  authority and FAILS CLOSED (a typed `:rf.error/frame-preflight-lifecycle-loss`)
  when that authority is lost mid-preflight. Two arms, host-shared (.cljc: node
  `test:cljs` / `test:ui` AND JVM `clojure -M:test`), against the REAL executor:

    (A) LIFECYCLE-LOSS FAIL-CLOSED — a self-destroying `:initial-events` setup
        (the handler destroys the very frame it is seating) leaves the frame
        ABSENT when its record would publish. Previously the executor silently
        skipped the write and returned a normal receipt, so the client mounted a
        host root scoped to an absent frame; now it throws. The :install arm
        (self-destroy) and the :adopt arm (a sibling plan destroys the boot frame
        a later config-less plan scopes) are both pinned.

    (B) DECISION-TIME TOKEN REVALIDATION — a `:refresh` decided against the
        incarnation live at decision time must not apply to a same-id replacement
        that overtook it before the surgical `make-frame`. The executor captures
        the decision-time token in phase 1 and revalidates it before mutating in
        phase 2; a stale target fails closed BEFORE any create-if-absent.

  The concurrent-destroy CLOSING race (a still-PRESENT rejected incarnation) is
  deliberately NOT covered here — its coordination is parent .191 part C; the
  pre-existing `frame-plan-publication-race` skip-not-throw contract stands.

  Each fail-loud assertion checks the `:rf.error/id` discriminator + `:kind`,
  never message bytes (Spec 009 §The thrown-error shape rule 3)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.frames            :as frames]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter plain-atom/adapter :ambient-frame nil
    :init-fn (fn []
               ;; The setup steps the tests drive their frames' :initial-events
               ;; with — one destroys its OWN frame (self-destroy), one destroys
               ;; a named sibling. destroy-frame! is not handler-guarded (only
               ;; construction is), so both run inside the synchronous drain.
               (rf/reg-event :test/destroy-self
                             (fn [_ [_ fid]] (frame/destroy-frame! fid) {}))
               (rf/reg-event :test/destroy-other
                             (fn [_ [_ fid]] (frame/destroy-frame! fid) {})))})
  (fn [f]
    (frames/reset-installed-plans!)
    (try (f) (finally (frames/reset-installed-plans!)))))

(defn- plan
  ([fid fp] (plan fid fp {}))
  ([fid fp cfg] {:frame-id fid :config cfg :config-fingerprint fp}))

(defn- thrown
  "Run `thunk`; return the ex-data of a thrown ExceptionInfo (nil if none)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (ex-data e))))

;; ---------------------------------------------------------------------------
;; (A) lifecycle-loss fail-closed
;; ---------------------------------------------------------------------------

(deftest self-destroying-install-setup-fails-closed
  (testing "an :install whose :initial-events destroy their own frame no longer
            returns a normal receipt over an absent frame — it throws the typed
            lifecycle-loss error, leaving no frame and no plan record"
    (let [fid :auth/self-destroy
          ed  (thrown
               #(frames/execute-frame-plans!
                 :root/a [(plan fid "cfa-aaaaaaaaaaaaaa"
                                {:initial-events [[:test/destroy-self fid]]})]))]
      (is (= :rf.error/frame-preflight-lifecycle-loss (:rf.error/id ed))
          "the self-destroying setup fails the preflight closed")
      (is (= :ensured-frame-lost (:kind ed)))
      (is (= fid (:frame-id ed)))
      (is (= :root/a (:root-id ed)))
      (is (nil? (frame/frame fid)) "the self-destroyed frame stays absent")
      (is (nil? (frames/installed-plan-entry fid))
          "no plan record was published for the absent frame"))))

(deftest sibling-destroyed-adopt-target-fails-closed
  (testing "a config-less plan that would ADOPT a boot frame fails closed when an
            earlier plan's :initial-events destroyed that boot frame first —
            ENSURE cannot scope an absent frame"
    (let [boot :auth/boot-target
          g    :auth/adopt-driver]
      (live-frame/make-frame {:id boot})            ; a live, record-less boot frame
      (is (some? (frame/frame boot)))
      (let [ed (thrown
                #(frames/execute-frame-plans!
                  :root/a [(plan g "cfg-gggggggggggggg"
                                 {:initial-events [[:test/destroy-other boot]]})
                           (plan boot "cf-boot-bbbbbbbb")]))]  ; config-less ⇒ adopt
        (is (= :rf.error/frame-preflight-lifecycle-loss (:rf.error/id ed)))
        (is (= :ensured-frame-lost (:kind ed)))
        (is (= boot (:frame-id ed)))
        (is (nil? (frame/frame boot)) "the destroyed boot frame stays absent")
        (is (nil? (frames/installed-plan-entry boot))
            "no adoption record over the absent boot frame")))))

;; ---------------------------------------------------------------------------
;; (B) decision-time token revalidation
;; ---------------------------------------------------------------------------

(deftest stale-refresh-against-replacement-fails-closed
  (testing "a :refresh decided against incarnation A no longer applies to a
            post-make-frame replacement: when an earlier plan destroys A, the
            refresh fails closed BEFORE its create-if-absent make-frame can bind
            root-a's config to a fresh incarnation"
    (let [fid :auth/refresh-target
          g   :auth/refresh-driver]
      ;; seat A under root-a with an install record (config-fingerprint cf1)
      (frames/execute-frame-plans! :root/a [(plan fid "cf1-aaaaaaaaaaaaaa")])
      (is (= :root/a (:installed-by (frames/installed-plan-entry fid))))
      (let [token-a (frame/frame-incarnation-token fid)
            ;; multi-plan run: G's :initial-events destroy A; a later same-root
            ;; plan for fid with a DIFFERENT fingerprint would be a :refresh.
            ed      (thrown
                     #(frames/execute-frame-plans!
                       :root/a [(plan g "cfg-gggggggggggggg"
                                      {:initial-events [[:test/destroy-other fid]]})
                                (plan fid "cf2-aaaaaaaaaaaaaa")]))]
        (is (= :rf.error/frame-preflight-lifecycle-loss (:rf.error/id ed)))
        (is (= :refresh-target-replaced (:kind ed))
            "the refresh is caught as a lost decision-time authority, not adopted")
        (is (= fid (:frame-id ed)))
        (is (nil? (frame/frame fid))
            "the refresh did NOT create-if-absent a spurious replacement incarnation")
        (is (nil? (frame/frame-incarnation-token fid)))
        (is (not (identical? token-a (frame/frame-incarnation-token fid))))))))

;; ---------------------------------------------------------------------------
;; regression: the exact-authority binding leaves every live-authority path
;; unchanged (install / refresh / adopt / found-live all still succeed)
;; ---------------------------------------------------------------------------

(deftest live-authority-paths-unchanged
  (testing "install, same-root refresh, boot adopt, and the found-live no-op all
            still succeed when the decided authority stays live"
    (let [inst :auth/ok-install
          ref  :auth/ok-refresh
          boot :auth/ok-adopt]
      ;; install
      (is (nil? (:rf.error/id
                 (thrown #(frames/execute-frame-plans! :root/a [(plan inst "cfi-iiiiiiiiiiiiii")])))))
      (is (= :root/a (:installed-by (frames/installed-plan-entry inst))))
      ;; found-live no-op — same fingerprint, no churn, no throw
      (is (nil? (:rf.error/id
                 (thrown #(frames/execute-frame-plans! :root/a [(plan inst "cfi-iiiiiiiiiiiiii")])))))
      (is (= "cfi-iiiiiiiiiiiiii" (:config-fingerprint (frames/installed-plan-entry inst))))
      ;; same-root refresh — the incarnation stays live, so it applies
      (frames/execute-frame-plans! :root/a [(plan ref "cfr1-rrrrrrrrrrrr")])
      (let [token-1 (frame/frame-incarnation-token ref)]
        (is (nil? (:rf.error/id
                   (thrown #(frames/execute-frame-plans! :root/a [(plan ref "cfr2-rrrrrrrrrrrr")])))))
        (is (= "cfr2-rrrrrrrrrrrr" (:config-fingerprint (frames/installed-plan-entry ref)))
            "a live-authority refresh applies the new fingerprint")
        (is (identical? token-1 (frame/frame-incarnation-token ref))
            "refresh is a surgical update of the SAME incarnation, not a replace"))
      ;; boot adopt — a live record-less frame scoped by a config-less plan
      (live-frame/make-frame {:id boot})
      (is (nil? (:rf.error/id
                 (thrown #(frames/execute-frame-plans! :root/a [(plan boot "cfab-aaaaaaaaaaa")])))))
      (is (= :root/a (:adopted-by (frames/installed-plan-entry boot)))))))
