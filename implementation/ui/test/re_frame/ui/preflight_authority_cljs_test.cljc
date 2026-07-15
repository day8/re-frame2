(ns re-frame.ui.preflight-authority-cljs-test
  "rf2-5svfa1 — the bounded, non-design-bearing core split out of parent
  rf2-vxgfnd.191: preflight ENSURE binds its plan decisions to the EXACT frame
  authority and FAILS CLOSED (a typed `:rf.error/frame-preflight-lifecycle-loss`)
  when that authority is lost mid-preflight. Two arms, host-shared (.cljc: node
  `test:cljs` / `test:ui` AND JVM `clojure -M:test`), against the REAL executor:

    (A) FAIL-CLOSED SETUP — a same-owner destroy from `:initial-events` joins
        the transaction and makes its provisional row lifecycle-dead; ordinary
        construction cannot revive it and fails with the uniform construction
        error before exact rollback. The distinct :adopt arm still pins
        preflight lifecycle loss when a sibling plan destroys the boot frame a
        later config-less plan scopes.

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
            [re-frame.error-emit           :as error-emit]
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
               ;; a named sibling. The same-id destroy joins its construction
               ;; owner; the sibling destroy remains an ordinary teardown.
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

(defn- owner-root
  [entry]
  (or (:installed-by entry) (:adopted-by entry)))

;; ---------------------------------------------------------------------------
;; (A) setup fail-closed
;; ---------------------------------------------------------------------------

(deftest self-destroying-install-setup-fails-closed
  (testing "an :install whose :initial-events destroy their own provisional
            frame reports the uniform construction conflict for the resulting
            dead row, then rolls back without a frame or plan record"
    (let [fid :auth/self-destroy
          ed  (thrown
               #(frames/execute-frame-plans!
                 :root/a [(plan fid "cfa-aaaaaaaaaaaaaa"
                                {:initial-events [[:test/destroy-self fid]]})]))]
      (is (= :rf.error/frame-construction-in-progress (:rf.error/id ed))
          "construction cannot continue through its lifecycle-dead provisional row")
      (is (= fid (:frame ed)))
      (is (= :lifecycle-dead (:reason ed)))
      (is (nil? (frame/frame fid)) "the owning construction rolls back")
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

(deftest stale-found-live-decision-cannot-settle-an-absent-or-replaced-frame
  (testing "a found-live decision is pinned to its decision-time incarnation and
            record: an earlier sibling may destroy that incarnation, but the
            stale arm cannot return a settleable receipt for it"
    (let [fid :auth/found-live-target
          g   :auth/found-live-driver
          fp  "cff-ffffffffffffff"]
      (frames/execute-frame-plans! :root/a [(plan fid fp)])
      (let [ed (thrown
                #(frames/execute-frame-plans!
                  :root/a [(plan g "cfg-gggggggggggggg"
                                 {:initial-events [[:test/destroy-other fid]]})
                           (plan fid fp)]))]
        (is (= :rf.error/frame-preflight-lifecycle-loss (:rf.error/id ed)))
        (is (= :found-live-authority-lost (:kind ed)))
        (is (= fid (:frame-id ed)))
        (is (nil? (frame/frame fid))
            "the destroyed decision-time incarnation stays absent")
        (is (nil? (frames/installed-plan-entry fid))
            "the stale found-live arm cannot resurrect or settle its pruned record")))))

;; ---------------------------------------------------------------------------
;; (C) fail-fast per-id reservation: same-id nesting loses, disjoint/plan-free
;; nesting proceeds on both hosts without a process-wide monitor.
;; ---------------------------------------------------------------------------

(deftest initial-event-nesting-is-per-id-and-never-blocks
  (let [same-id-result (atom nil)
        disjoint-result (atom nil)
        plan-free-result (atom nil)
        outer :auth/nested-outer
        other :auth/nested-disjoint]
    ;; The disjoint inner run scopes a boot-authoritative frame. It therefore
    ;; exercises nested preflight admission without asking core to construct a
    ;; frame from inside an event handler (which EP-0027 independently forbids).
    (live-frame/make-frame {:id other :doc "boot-disjoint"})
    (rf/reg-event
     :test/nested-preflights
     (fn [_ _]
       (reset! same-id-result
               (try
                 (frames/execute-frame-plans!
                  :root/inner-same [(plan outer "cfo-oooooooooooooo")])
                 ::won
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                   (ex-data e))))
       (reset! disjoint-result
               (try
                 (frames/execute-frame-plans!
                  :root/inner-disjoint [(plan other "cfd-dddddddddddddd")])
                 ::won
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                   (ex-data e))))
       (reset! plan-free-result
               (try
                 (frames/execute-frame-plans! :root/inner-plan-free [])
                 ::won
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                   (ex-data e))))
       {}))
    (let [outer-receipt
          (frames/execute-frame-plans!
           :root/outer
           [(plan outer "cfo-oooooooooooooo"
                  {:initial-events [[:test/nested-preflights]]})])]
      (is (= :root/outer (:root-id outer-receipt))
          "the outer same-id owner succeeds")
      (is (= :rf.error/frame-preflight-overlap
             (:rf.error/id @same-id-result))
          "the nested same-id run loses immediately with the UI preflight error")
      (is (= outer (:frame-id @same-id-result)))
      (is (= :root/inner-same (:root-id @same-id-result)))
      (is (= ::won @disjoint-result)
          "a disjoint nested plan proceeds independently")
      (is (= ::won @plan-free-result)
          "a plan-free nested run proceeds without touching reservations")
      (is (= :root/outer
             (:installed-by (frames/installed-plan-entry outer)))
          "exactly the outer same-id run owns the record")
      (is (= :root/inner-disjoint
             (:adopted-by (frames/installed-plan-entry other)))
          "the disjoint nested run publishes its own adoption"))))

;; ---------------------------------------------------------------------------
;; (D) settlement authority: rev + root ownership, with foreign equal-plan
;; found-live remaining a valid scoping no-op and receiving no settlement rights.
;; ---------------------------------------------------------------------------

(deftest receipts-carry-root-authority-and-foreign-found-live-cannot-settle
  (let [fid       :auth/receipt-owner
        fp        "cfr-rrrrrrrrrrrrrr"
        owner-r   (frames/execute-frame-plans! :root/owner [(plan fid fp)])
        foreign-r (frames/execute-frame-plans! :root/foreign [(plan fid fp)])]
    (is (= [:root/owner] (mapv :root-id (:writes owner-r)))
        "every settleable write carries the root authority that minted it")
    (is (= :root/foreign (:root-id foreign-r)))
    (is (empty? (:writes foreign-r))
        "foreign equal-fingerprint found-live is valid scoping, not settlement authority")
    (frames/finalize-preflight-attempt! foreign-r)
    (frames/abort-preflight-attempt! foreign-r)
    (let [entry (frames/installed-plan-entry fid)]
      (is (= :root/owner (owner-root entry)))
      (is (not (:committed entry))
          "the foreign root cannot finalize the owner's record")
      (is (not (:mount-incomplete entry))
          "the foreign root cannot abort the owner's record"))
    (frames/finalize-preflight-attempt! owner-r)
    (is (true? (:committed (frames/installed-plan-entry fid)))
        "the legitimate owner still settles normally")))

(deftest settlement-rejects-a-foreign-controller-with-typed-mismatch-evidence
  (let [abort-id    :auth/foreign-abort
        finalize-id :auth/foreign-finalize
        fp-a        "cfa-aaaaaaaaaaaaaa"
        fp-f        "cff-ffffffffffffff"
        abort-r     (frames/execute-frame-plans! :root/owner [(plan abort-id fp-a)])
        finalize-r  (frames/execute-frame-plans! :root/owner [(plan finalize-id fp-f)])
        records     (atom [])
        listener-id (keyword "test" (str (gensym "preflight-evidence")))]
    (error-emit/register-error-listener! listener-id #(swap! records conj %))
    (try
      ;; Model a foreign lifecycle controller presenting another root's exact
      ;; receipt. The per-write owner remains :root/owner; the outer receipt
      ;; claims :root/foreign. Rev-only guards incorrectly admit both calls.
      (frames/abort-preflight-attempt! (assoc abort-r :root-id :root/foreign))
      (frames/finalize-preflight-attempt! (assoc finalize-r :root-id :root/foreign))
      (finally
        (error-emit/unregister-error-listener! listener-id)))
    (is (nil? (:mount-incomplete (frames/installed-plan-entry abort-id)))
        "a foreign controller cannot abort the exact owner's record")
    (is (nil? (:committed (frames/installed-plan-entry finalize-id)))
        "a foreign controller cannot finalize the exact owner's record")
    (is (= 2 (count (filter #(= :rf.error/frame-preflight-evidence-mismatch
                                (:error %))
                           @records)))
        "each rejected settlement emits the typed evidence-mismatch diagnostic")))

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
