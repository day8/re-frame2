(ns re-frame.freehand.presence-matrix-dom-cljs-test
  "F6b matrix 2/8 — KEYED PRESENCE re-entry and ACCESSIBILITY, in a real
  browser, across BOTH execution modes (EP-0036 §6, gate row \"browser
  correctness\"; acceptance 2 — dynamic accessibility is owned by
  real-browser tests).

  A presence boundary RETAINS a departed keyed child so it can leave on
  its own terms, and the child owns its exit accessibility by reading its
  own `(v/presence-phase)`. None of that is a structural fact: a reconcile
  that is perfectly shaped can still leave a departed child in the DOM
  forever, never let it read `:unmounting`, or unmount it twice. So this
  mounts, and reads every claim back off `document`.

  The matrix dimension is mode. The presence RUNTIME is a shared,
  mode-independent substrate; the emitters differ only in how the boundary
  and its children are BUILT. So each claim below is asserted in the
  interpreted AND the compiled lowering — a departed key retained
  `:unmounting` with `aria-hidden`/`inert` stamped by the child's own
  phase read, a terminal flush that removes it exactly once, and a
  re-entry that cancels the pending exit — proving promotion feeds the
  presence runtime the same page either way.

  Retention is driven by the deterministic `flush-presence!` clock — the
  real `setTimeout` arm is disabled — so a window closes exactly when the
  test says and the run carries no wall-clock flake.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node
  it has no DOM and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.presence-runtime :as presence]
            [re-frame.freehand.react :as fr]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

;; ---------------------------------------------------------------------------
;; Twins — the phase-aware child, and the boundary, in each mode. The child
;; reads its OWN phase and, while :unmounting, hides itself from assistive
;; technology and leaves the interaction tree. The boundary carries a
;; literal keyed child and a CONDITIONAL keyed child, so the second can
;; leave the presence list and be retained — the compiled grammar takes a
;; literal/conditional presence child (a dynamic `for` is interpreted-only).
;; ---------------------------------------------------------------------------

(v/defview toast-interpreted
  [{:keys [label]}]
  (let [phase    (v/presence-phase)
        exiting? (= :unmounting phase)]
    [:div.toast {:data-label  (str label)
                 :data-phase  (name phase)
                 :aria-hidden (when exiting? true)
                 :inert       (when exiting? true)}
     (str label)]))

(v/defview toast-compiled
  {:compiled true}
  [{:keys [label]}]
  (let [phase    (v/presence-phase)
        exiting? (= :unmounting phase)]
    [:div.toast {:data-label  (str label)
                 :data-phase  (name phase)
                 :aria-hidden (when exiting? true)
                 :inert       (when exiting? true)}
     (str label)]))

;; The terminal `:timeout-ms` is a LITERAL: the compiled presence grammar
;; settles it at analysis time. It is irrelevant to the run — retention is
;; driven by the deterministic `flush-presence!` clock below, never the
;; wall clock — but it must be a literal number in both twins for the
;; declarations to be byte-identical bar the marker.
(v/defview toaster-interpreted
  [{:keys [show-b?]}]
  [:div#stack
   (v/presence {:timeout-ms 300}
     [toast-interpreted {:key "a" :label "a"}]
     (when show-b? [toast-interpreted {:key "b" :label "b"}]))])

(v/defview toaster-compiled
  {:compiled true}
  [{:keys [show-b?]}]
  [:div#stack
   (v/presence {:timeout-ms 300}
     [toast-compiled {:key "a" :label "a"}]
     (when show-b? [toast-compiled {:key "b" :label "b"}]))])

(def ^:private modes
  [["interpreted" toaster-interpreted]
   ["compiled"    toaster-compiled]])

;; ---------------------------------------------------------------------------
;; Reading the stack
;; ---------------------------------------------------------------------------

(defn- toast-el [container label]
  (.querySelector container (str ".toast[data-label='" label "']")))

(defn- phase-of [container label]
  (some-> (toast-el container label) (.getAttribute "data-phase")))

(defn- present? [container label] (some? (toast-el container label)))

(defn- render! [root view show-b?]
  (ms/act #(.render root (fr/element [view {:show-b? show-b?}]))))

;; ===========================================================================
;; Row 1 — both lowerings mount the same stack
;; ===========================================================================

(deftest presence-matrix-both-modes-mount-the-same-stack
  (testing "The presence boundary and its two phase-aware children mount to
            the SAME real DOM in each mode — same keyed children, same
            settled `:present` phase, same absence of exit accessibility."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the parity assertion")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[ci ri] (ms/create-root!)
              [cc rc] (ms/create-root!)]
          (-> (render! ri toaster-interpreted true)
              (.then (fn [_] (render! rc toaster-compiled true)))
              (.then (fn [_]
                       (ms/outlines-agree? (ms/q ci "#stack") (ms/q cc "#stack")
                                           "presence stack")
                       (doseq [[label container] [["interpreted" ci] ["compiled" cc]]]
                         (is (present? container "a") (str label ": a mounted"))
                         (is (present? container "b") (str label ": b mounted"))
                         (is (= "present" (phase-of container "a")) (str label ": a is :present"))
                         (is (nil? (.getAttribute (toast-el container "b") "aria-hidden"))
                             (str label ": a present child is not hidden")))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "a presence mount rejected: " e)) nil))
              ;; Both arms tore both roots down identically, so the teardown
              ;; rides the single trailing step: written once, run once per path.
              (.then (fn [_] (ms/destroy-root! ci ri) (ms/destroy-root! cc rc) (done)))))))))

;; ===========================================================================
;; Row 2 — a departed key is retained, hides itself, and flushes once
;; ===========================================================================

(deftest presence-matrix-a-departed-key-is-retained-and-hides-itself-in-both-modes
  (testing "Removing key b RETAINS its child `:unmounting`, the child hides
            itself from assistive technology and leaves the interaction tree
            by reading its own phase, and the deterministic timeout removes
            it exactly once — in each mode. This is acceptance 2's dynamic
            accessibility, owned by a real-browser test."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the retention assertions")
      (async done
        (ms/each-mode
          modes
          (fn [[label view]]
            (presence/reset-clock!)
            (presence/set-wall-clock! false)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view true)
                  (.then (fn [_]
                           (is (= "present" (phase-of container "b")) (str label ": b starts :present"))
                           (is (= 0 (presence/pending-count)) (str label ": nothing pending"))
                           (render! root view false)))     ; b departs
                  (.then (fn [_]
                           (is (present? container "b")
                               (str label ": b is RETAINED in the DOM while exiting"))
                           (is (= "unmounting" (phase-of container "b"))
                               (str label ": b's own phase read reaches :unmounting"))
                           (is (= "true" (.getAttribute (toast-el container "b") "aria-hidden"))
                               (str label ": the exiting child hid itself from assistive tech"))
                           (is (some? (.getAttribute (toast-el container "b") "inert"))
                               (str label ": and took itself out of the interaction tree"))
                           (is (= 1 (presence/pending-count)) (str label ": exactly one exit retained"))
                           (ms/act #(presence/flush-presence!))))
                  (.then (fn [_]
                           (is (present? container "a") (str label ": a is untouched"))
                           (is (not (present? container "b"))
                               (str label ": the timeout removed b terminally"))
                           (is (= 0 (presence/pending-count))
                               (str label ": the exit fired exactly once"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 3 — re-entry before the flush cancels the exit
;; ===========================================================================

(deftest presence-matrix-re-entry-before-the-flush-cancels-removal-in-both-modes
  (testing "Re-adding key b BEFORE its timeout fires interrupts the exit —
            the child returns to `:present`, drops its exit accessibility,
            and the pending removal is cancelled so a later flush never
            unmounts it — in each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the re-entry assertions")
      (async done
        (ms/each-mode
          modes
          (fn [[label view]]
            (presence/reset-clock!)
            (presence/set-wall-clock! false)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view true)
                  (.then (fn [_] (render! root view false)))    ; b departs -> :unmounting
                  (.then (fn [_]
                           (is (= "unmounting" (phase-of container "b"))
                               (str label ": b is retained :unmounting"))
                           (is (= 1 (presence/pending-count)) (str label ": its exit is scheduled"))
                           (render! root view true)))            ; b re-enters before the flush
                  (.then (fn [_]
                           (is (present? container "b") (str label ": b is still in the DOM"))
                           (is (= "present" (phase-of container "b"))
                               (str label ": re-entry flipped b back to :present"))
                           (is (nil? (.getAttribute (toast-el container "b") "aria-hidden"))
                               (str label ": b dropped its exit accessibility on re-entry"))
                           (is (= 0 (presence/pending-count))
                               (str label ": the pending exit was cancelled"))
                           (ms/act #(presence/flush-presence!))))
                  (.then (fn [_]
                           (is (present? container "b")
                               (str label ": a flush after re-entry leaves b mounted"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))
