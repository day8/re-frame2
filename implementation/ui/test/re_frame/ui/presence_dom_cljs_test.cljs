(ns re-frame.ui.presence-dom-cljs-test
  "S4 presence (rf2-uckeg) client behaviour: the fake-clock exit scheduler and
  the mounted three-phase machine (Spec 004 §Presence).

    - the presence clock: advance-clock! fires DUE timers deterministically, in
      order, EXACTLY ONCE (the double-cleanup guard); a partial advance leaves a
      not-yet-due exit retained (the timeout-fires-before-exit adversarial case);
    - mounted: a keyed child enters :mounting → :present; a removed key is
      RETAINED :unmounting until flush-presence! reaches :timeout-ms, then it is
      removed and its ownership released exactly once; reinsertion re-enters;
    - ownership identity: a key claimed twice at one boundary retains ONE child
      (the first claimant) and owns ONE exit timer, and the collision is
      diagnosed as `:rf.error/ui-duplicate-key`."
  (:require [clojure.string :as str]
            [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.presence-runtime :as presence]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

;; The mounted machine's app surface. Registered at ns-load BEFORE the
;; `use-fixtures` form below so `make-reset-runtime-fixture` captures them in its
;; ns-load baseline (test_support.cljc §Stable ns-load baseline — the snapshot is
;; taken eagerly at fixture-build time). The per-test reset then folds that
;; baseline back over a fresh registrar, so `::toasts` is registered again at
;; every mount — otherwise the strict mounted-React observation port throws
;; `:rf.error/no-such-sub` on the unknown ENTRY sub.
(rf/reg-event ::set-toasts (fn [{:keys [db]} [_ ts]] {:db (assoc db :toasts ts)}))
(rf/reg-sub ::toasts (fn [db _] (:toasts db)))

;; ---------------------------------------------------------------------------
;; Fixtures — the async runtime reset PLUS a deterministic presence clock
;; (reset + wall-clock disabled, so advance-clock! is the SOLE removal driver).
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before #(do (presence/reset-clock!) (presence/set-wall-clock! false))
   :after  #(do (presence/reset-clock!) (presence/set-wall-clock! true))})

(deftest advance-clock-fires-due-timers-in-order
  (let [fired (atom [])]
    (presence/schedule-exit! 300 #(swap! fired conj :a))
    (presence/schedule-exit! 100 #(swap! fired conj :b))
    (presence/schedule-exit! 200 #(swap! fired conj :c))
    (testing "a partial advance fires only the due timers, in fire-at order"
      (is (= 1 (presence/advance-clock! 100)) "one exit due at t=100")
      (is (= [:b] @fired))
      (is (= 2 (presence/pending-count)) "two exits still retained"))
    (testing "advancing the rest fires the remainder in order"
      (is (= 2 (presence/advance-clock! 250)) "c (200) then a (300) come due")
      (is (= [:b :c :a] @fired))
      (is (= 0 (presence/pending-count))))))

(deftest advance-to-quiescence-fires-everything
  (let [fired (atom 0)]
    (presence/schedule-exit! 300 #(swap! fired inc))
    (presence/schedule-exit! 999 #(swap! fired inc))
    (is (= 2 (presence/advance-clock!)) "no-arg advance drains to quiescence")
    (is (= 2 @fired))
    (is (zero? (presence/pending-count)))))

(deftest removal-is-exactly-once
  ;; the double-cleanup guard: firing a timer twice (a real setTimeout landing
  ;; after a test flush; a double advance) runs the removal ONCE.
  (let [runs (atom 0)
        id   (presence/schedule-exit! 100 #(swap! runs inc))]
    (presence/fire-timer! id)
    (presence/fire-timer! id)                 ; second fire: no-op
    (presence/advance-clock! 10000)           ; nothing left to fire
    (is (= 1 @runs) "terminal removal runs exactly once")))

(deftest cancel-does-not-fire
  ;; re-entry / unmount cancels a pending exit WITHOUT running its removal.
  (let [runs (atom 0)
        id   (presence/schedule-exit! 100 #(swap! runs inc))]
    (presence/cancel-timer! id)
    (presence/advance-clock! 10000)
    (is (= 0 @runs) "a cancelled exit never removes")
    (is (zero? (presence/pending-count)))))

;; ---------------------------------------------------------------------------
;; Mounted three-phase machine (jsdom / browser)
;; ---------------------------------------------------------------------------

(defview toast-card [{:keys [msg]}]
  [:li {:data-testid "toast" :data-msg msg
        :data-phase (name (ui/presence-phase))}
   msg])

(defview toast-list []
  (ui/presence {:timeout-ms 300}
    (for [t (ui/sub [::toasts])]
      [toast-card {:key (:id t) :msg (:msg t)}])))

(defn- toasts [root]
  (vec (.querySelectorAll root "[data-testid='toast']")))

(defn- phase-of [root msg]
  (some #(when (= msg (.getAttribute % "data-msg"))
           (.getAttribute % "data-phase"))
        (toasts root)))

(defn- reject-unexpectedly! [done label e]
  (is false (str label ": " (some-> e ex-message)))
  (done))

(deftest mounted-presence-machine
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — covered in the browser job")
    (async done
      (let [f (rf/make-frame {:initial-events
                              [[::set-toasts [{:id 1 :msg "a"} {:id 2 :msg "b"}]]]})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [toast-list]]]
              (-> (uit/flush!)     ; settle the enter flip (:mounting → :present)
                  (.then (fn [_]
                           (testing "enter: both keyed children present"
                             (is (= 2 (count (toasts root))))
                             (is (= "present" (phase-of root "a")))
                             (is (= "present" (phase-of root "b"))))
                           ;; remove toast "b" from the source list
                           (rf/dispatch-sync [::set-toasts [{:id 1 :msg "a"}]] {:frame f})
                           (uit/flush!)))
                  (.then (fn [_]
                           (testing "exit: the removed child is RETAINED :unmounting"
                             (is (= 2 (count (toasts root)))
                                 "b stays mounted while exiting")
                             (is (= "present" (phase-of root "a")))
                             (is (= "unmounting" (phase-of root "b"))))
                           ;; advance the clock by LESS than :timeout-ms — still retained
                           (uit/flush-presence! 100)))
                  (.then (fn [_]
                           (testing "timeout-fires-before-exit: below :timeout-ms, still retained"
                             (is (= 2 (count (toasts root))))
                             (is (= "unmounting" (phase-of root "b"))))
                           ;; advance past :timeout-ms — the safety bound removes it
                           (uit/flush-presence! 300)))
                  (.then (fn [_]
                           (testing "removal: the timeout removes the retained child"
                             (is (= 1 (count (toasts root))) "b is gone")
                             (is (= "a" (.-textContent (first (toasts root)))))
                             (is (zero? (presence/pending-count))
                                 "no retention timer left — cleanup ran"))))))
            (.then (fn [_] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (reject-unexpectedly! done "mounted presence rejected" e))))))))

(deftest reinsertion-interrupts-exit
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — covered in the browser job")
    (async done
      (let [f (rf/make-frame {:initial-events [[::set-toasts [{:id 1 :msg "a"}]]]})]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [toast-list]]]
              (-> (uit/flush!)
                  (.then (fn [_]
                           ;; remove, then re-insert BEFORE the timeout fires
                           (rf/dispatch-sync [::set-toasts []] {:frame f})
                           (uit/flush!)))
                  (.then (fn [_]
                           (is (= "unmounting" (phase-of root "a")) "a is exiting")
                           (rf/dispatch-sync [::set-toasts [{:id 1 :msg "a"}]] {:frame f})
                           (uit/flush!)))
                  (.then (fn [_]
                           (testing "re-entry: the exit is interrupted, a is :present again"
                             (is (= 1 (count (toasts root))))
                             (is (= "present" (phase-of root "a")))
                             (is (zero? (presence/pending-count))
                                 "the pending exit timer was cancelled"))
                           ;; and a later flush-presence! does not remove it
                           (uit/flush-presence!)))
                  (.then (fn [_]
                           (is (= 1 (count (toasts root)))
                               "a survives — its exit was interrupted")))))
            (.then (fn [_] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (reject-unexpectedly! done "reinsertion rejected" e))))))))

;; ---------------------------------------------------------------------------
;; Ownership identity at a MOUNTED boundary (rf2-vxgfnd.96.2)
;;
;; The compiler admits keyed siblings and keyed `for` rows independently, so
;; nothing upstream can see that an explicit key and a dynamic one alias at the
;; boundary. Left alone, the two children reconcile to two entries under one
;; key, render under duplicate Provider keys, and share ONE exit timer whose
;; removal drops both.
;; ---------------------------------------------------------------------------

(defview aliased-toasts []
  (ui/presence {:timeout-ms 300}
    ;; explicit sibling and a `for` row in a separate flattened array, both
    ;; claiming "dup" — the collision no per-list-site check can see.
    [toast-card {:key "dup" :msg "first"}]
    [toast-card {:key "dup" :msg "second"}]
    (for [t (ui/sub [::toasts])]
      [toast-card {:key (:id t) :msg (:msg t)}])))

(defn- capture-console-warn!
  "Redirect `js/console.warn` into `sink` until `(restore)` is called. The
  presence warning fires inside React's render, which `flush!` drives
  ASYNCHRONOUSLY — so the capture has to span the promise, not a thunk."
  [sink]
  (let [prior (.-warn js/console)]
    (set! (.-warn js/console) (fn [& args] (swap! sink conj (str/join " " (map str args)))))
    (fn [] (set! (.-warn js/console) prior))))

(deftest duplicate-identities-collapse-to-one-retained-child
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — covered in the browser job")
    (async done
      (let [f       (rf/make-frame {:initial-events
                                    [[::set-toasts [{:id "dup" :msg "third"}
                                                    {:id "solo" :msg "solo"}]]]})
            warns   (atom [])
            restore (capture-console-warn! warns)]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [aliased-toasts]]]
              (-> (uit/flush!)
                  (.then (fn [_]
                           (restore)
                           (testing "one key, one child — three claimants collapse to the first"
                             (is (= 2 (count (toasts root)))
                                 "the aliased identity renders ONCE, alongside the distinct key")
                             (is (= "present" (phase-of root "first"))
                                 "the FIRST claimant in document order owns the identity")
                             (is (nil? (phase-of root "second")) "the later sibling is dropped")
                             (is (nil? (phase-of root "third")) "the aliasing `for` row is dropped")
                             (is (= "present" (phase-of root "solo"))
                                 "a distinct key is completely unaffected"))
                           (testing "the collision is diagnosed loudly in dev"
                             (let [dup (filter #(str/includes? % ":rf.error/ui-duplicate-key") @warns)]
                               (is (seq dup) "the catalogued duplicate-key id is reported")
                               (is (some #(str/includes? % "dup") dup)
                                   "the warning names the colliding key")
                               (is (some #(str/includes? % "presence") dup)
                                   "…and the boundary it collided at")))
                           ;; drop the whole source list: the aliased identity must
                           ;; own exactly ONE exit timer, not one shared by two entries.
                           (rf/dispatch-sync [::set-toasts []] {:frame f})
                           (uit/flush!)))
                  (.then (fn [_]
                           (testing "exit ownership stays 1:1"
                             (is (= 1 (presence/pending-count))
                                 "only the departed `solo` key retains an exit timer")
                             (is (= "present" (phase-of root "first"))
                                 "the aliased identity is still claimed, so it never exits")
                             (is (= "unmounting" (phase-of root "solo"))))
                           (uit/flush-presence!)))
                  (.then (fn [_]
                           (testing "the timeout removes only the identity that left"
                             (is (= 1 (count (toasts root))))
                             (is (= "present" (phase-of root "first")))
                             (is (zero? (presence/pending-count))))))))
            (.then (fn [_] (restore) (rf/destroy-frame! f) (done))
                   (fn [e] (restore) (rf/destroy-frame! f)
                     (reject-unexpectedly! done "duplicate presence identities rejected" e))))))))

;; ---------------------------------------------------------------------------
;; Keyless child at a MOUNTED boundary (rf2-vz6a1)
;;
;; A presence child whose runtime React `.-key` is nil is dropped by
;; `collect-elements` — no phase, no DOM. The analyzer guarantees a `:key` FORM,
;; but a nil `.-key` still arises when the key VALUE is `undefined` (a JS-interop
;; read of an absent property): React's jsx-runtime leaves `undefined` as a null
;; key. IMPORTANT (verified React 19): a CLJS-nil key value does NOT drop —
;; jsx coerces `null` to the string "null" (a valid key), so `{:key (:id t)}`
;; with a missing :id RENDERS with key "null". The genuine drop needs an
;; `undefined` key, spelled here as `(.-id #js {})` (reading an absent prop). A
;; goog.DEBUG-only warning names the otherwise-silent drop; the drop itself is
;; unchanged.
;; ---------------------------------------------------------------------------

(defview keyless-toasts []
  (ui/presence {:timeout-ms 300}
    [toast-card {:key "keep" :msg "kept"}]
    ;; `(.-id #js {})` is `undefined` at runtime → jsx leaves a null `.-key` →
    ;; `collect-elements` drops it. (A CLJS-nil key would render as "null".)
    [toast-card {:key (.-id #js {}) :msg "dropped"}]))

(deftest keyless-child-drops-with-dev-warning
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — covered in the browser job")
    (async done
      (let [f       (rf/make-frame {})
            warns   (atom [])
            restore (capture-console-warn! warns)]
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [keyless-toasts]]]
              (-> (uit/flush!)
                  (.then (fn [_]
                           (restore)
                           (testing "the keyless (undefined-key) child is dropped; the keyed one renders"
                             (is (= 1 (count (toasts root)))
                                 "only the keyed child renders")
                             (is (= "present" (phase-of root "kept")))
                             (is (nil? (phase-of root "dropped"))
                                 "the keyless child never rendered — no phase, no DOM"))
                           (testing "a dev-console warning names the silent drop"
                             ;; render may run more than once (StrictMode / the
                             ;; enter flip), so assert the warning FIRED — same
                             ;; not-exactly-once shape as the duplicate-key test.
                             ;; "keyless child" is distinct from the dup-key warn.
                             (let [drops (filter #(str/includes? % "keyless child") @warns)]
                               (is (seq drops) "the keyless-drop warning fired")
                               (is (some #(str/includes? % "presence") drops)
                                   "…naming the presence boundary")))))))
            (.then (fn [_] (restore) (rf/destroy-frame! f) (done))
                   (fn [e] (restore) (rf/destroy-frame! f)
                     (reject-unexpectedly! done "keyless presence child rejected" e))))))))
