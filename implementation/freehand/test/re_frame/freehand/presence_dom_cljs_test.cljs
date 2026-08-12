(ns re-frame.freehand.presence-dom-cljs-test
  "FH-PRESENCE-003 — presence retention, timeout and accessibility in a real
  browser.

  The structural row (FH-PRESENCE-001) proves what the tree SAYS; the pure
  row (FH-PRESENCE-002) proves the transition algebra. This one proves what
  REACT DOES with a retained subtree, which is a different claim: a
  reconcile that is perfectly shaped can still leave a departed child in the
  DOM forever, unmount it twice, or never let it read `:unmounting` — and no
  headless assertion would notice, because the assertion and the bug would
  share an author.

  So: a real `react-dom/client` mount, a real `useContext` presence-phase
  read inside the child, and every claim read back off `document`. Retention
  is driven by the deterministic `flush-presence!` clock — the real
  `setTimeout` arm is disabled — so a retention window closes exactly when
  the test says and the run carries no wall-clock flake.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no DOM to mount
  and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.presence-runtime :as presence]
            [re-frame.freehand.react :as fr]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def fx (conf/fixture :FH-PRESENCE-003))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit and its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

;; ---------------------------------------------------------------------------
;; Views. Module-level — a declared view cannot close over a test's locals.
;;
;; `toast` is the presence-aware CHILD: it reads its own phase and, while
;; `:unmounting`, stamps `aria-hidden` and an exit class. That is the whole
;; DOM-agnostic contract — the boundary stamps nothing; the child owns its
;; exit accessibility against `(v/presence-phase)`.
;; ---------------------------------------------------------------------------

(v/defview toast
  [{:keys [label]}]
  (let [phase    (v/presence-phase)
        exiting? (= :unmounting phase)]
    [:div.toast {:data-label  (str label)
                 :data-phase  (name phase)
                 :class       (when exiting? (:exit-class fx))
                 :aria-hidden (when exiting? true)
                 :inert       (when exiting? true)}
     (str label)]))

(v/defview toaster
  [{:keys [ids timeout-ms]}]
  [:div#stack
   (v/presence {:timeout-ms timeout-ms}
     (for [k ids]
       [toast {:key k :label k}]))])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- toast-el [container label]
  (.querySelector container (str ".toast[data-label='" label "']")))

(defn- phase-of [container label]
  (some-> (toast-el container label) (.getAttribute "data-phase")))

(defn- present? [container label] (some? (toast-el container label)))

(defn- render-ids [root ids]
  (act #(.render root (fr/element [toaster {:ids ids :timeout-ms (:timeout-ms fx)}]))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

;; ===========================================================================
;; FH-PRESENCE-003 — retention, exit accessibility, terminal timeout
;; ===========================================================================

(deftest fh-presence-003-a-departed-key-is-retained-then-flushed
  (testing "Per FH-PRESENCE-003 (browser): removing a key RETAINS its child
            in the DOM `:unmounting`, the child hides itself from assistive
            technology by reading its own phase, and the deterministic
            timeout finally removes it — exactly once."
    (if-not (browser?)
      (skip! "the browser job runs the retention assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[container root] (mount!)]
          (-> (render-ids root ["a" "b"])
              (.then (fn [_]
                       (is (present? container "a") "a mounted")
                       (is (present? container "b") "b mounted")
                       (is (= "present" (phase-of container "a")) "a settled :present")
                       (is (= "present" (phase-of container "b")) "b settled :present")
                       (is (nil? (.getAttribute (toast-el container "b") "aria-hidden"))
                           "a present child is NOT hidden from assistive tech")
                       (is (= 0 (presence/pending-count)) "no exits pending yet")
                       ;; remove b — it must be retained, not gone
                       (render-ids root ["a"])))
              (.then (fn [_]
                       (is (present? container "a") "a stays")
                       (is (present? container "b")
                           "b is RETAINED in the DOM while exiting, not removed")
                       (is (= "present" (phase-of container "a")) "a is still :present")
                       (is (= "unmounting" (phase-of container "b"))
                           "b's own (v/presence-phase) read reaches :unmounting")
                       (is (= (get (:exit-aria fx) "aria-hidden")
                              (.getAttribute (toast-el container "b") "aria-hidden"))
                           "the exiting child hid itself from assistive technology")
                       (is (some? (.getAttribute (toast-el container "b") "inert"))
                           "and took itself out of the interaction tree")
                       (is (= 1 (presence/pending-count)) "exactly one exit is retained")
                       ;; drive the deterministic clock: the exit fires
                       (act #(presence/flush-presence!))))
              (.then (fn [_]
                       (is (present? container "a") "a is untouched by b's removal")
                       (is (not (present? container "b"))
                           "the timeout removed b terminally — its subtree is unmounted")
                       (is (= 0 (presence/pending-count))
                           "and the exit fired exactly once, leaving nothing pending")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Both arms tore down identically, so the teardown rides the
              ;; single trailing step: written once, run once per path.
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; FH-PRESENCE-003 — re-entry cancels the exit
;; ===========================================================================

(deftest fh-presence-003-re-entry-before-the-flush-cancels-removal
  (testing "Per FH-PRESENCE-003 (browser): re-adding a departed key BEFORE
            its timeout fires interrupts the exit — the child returns to
            `:present`, drops its exit accessibility, and the pending removal
            is cancelled so the later flush never unmounts it."
    (if-not (browser?)
      (skip! "the browser job runs the re-entry assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[container root] (mount!)]
          (-> (render-ids root ["a" "b"])
              (.then (fn [_]
                       (is (= "present" (phase-of container "b")) "b starts :present")
                       (render-ids root ["a"])))          ; b departs -> :unmounting
              (.then (fn [_]
                       (is (= "unmounting" (phase-of container "b")) "b is retained :unmounting")
                       (is (= 1 (presence/pending-count)) "its exit is scheduled")
                       (render-ids root ["a" "b"])))       ; b re-enters BEFORE the flush
              (.then (fn [_]
                       (is (present? container "b") "b is still in the DOM")
                       (is (= "present" (phase-of container "b"))
                           "re-entry flipped b back to :present, interrupting the exit")
                       (is (nil? (.getAttribute (toast-el container "b") "aria-hidden"))
                           "b dropped its exit accessibility on re-entry")
                       (is (= 0 (presence/pending-count))
                           "the pending exit was cancelled — re-entry is not a second child")
                       ;; a flush now must NOT remove the re-entered child
                       (act #(presence/flush-presence!))))
              (.then (fn [_]
                       (is (present? container "b")
                           "a flush after re-entry leaves the re-entered child mounted")))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared teardown, hoisted onto the single trailing step.
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest the-fixture-is-loaded
  (testing "Non-vacuity: the browser assertions read their expectations from
            the fixture, so a suite that silently loaded no fixture would
            assert nothing. Pin the shape the tests above depend on."
    (is (number? (:timeout-ms fx)) "the fixture carries a terminal timeout")
    (is (= "true" (get (:exit-aria fx) "aria-hidden"))
        "and the exit accessibility the retained child must show")
    (is (string? (:exit-class fx)) "and the exit class the child stamps")))
