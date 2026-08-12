(ns re-frame.freehand.presence-commit-ownership-dom-cljs-test
  "Presence is COMMIT-OWNED — a render React abandons publishes nothing.

  The atomic shell's governing law (`re-frame.freehand.cell`) is that a
  speculative render owns nothing and only the SELECTED commit publishes.
  The presence boundary holds committed state of its own — the retained
  element per key — and it is under the same law: React may render a
  candidate and then throw it away (a suspended transition, a superseded
  render), and that candidate must not leave its elements where a later
  COMMITTED update can render them.

  The probe below is a real `react-dom/client` mount driving a genuinely
  abandoned render: a transition re-enters a retained key with a new
  element and then suspends in a sibling, so the candidate really renders
  and really never commits. An unrelated, EARLIER exit timer then fires and
  force-updates the current tree — the one commit that would publish a
  leaked element. The retained child must still show its last COMMITTED
  element.

  The retention runtime is SHARED by interpreted and compiled markup (one
  `presence-runtime/PresenceComponent`, two lowerings onto it), so this
  proof binds both modes: there is no second element store for a compiled
  presence boundary to leak through.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no DOM to mount
  and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.presence-runtime :as presence]
            [re-frame.freehand.react :as fr]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

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
;; The tree under probe
;;
;; `marker` carries its identity (`data-id`, stable) and its CONTENT
;; (`data-label`, what a re-entry changes) as separate attributes, so the
;; assertions can find a retained child by identity and read which element
;; is on screen.
;; ---------------------------------------------------------------------------

(def ^:private timeout-ms 100)

(v/defview marker
  [{:keys [id label]}]
  [:div.marker {:data-id    (str id)
                :data-label (str label)
                :data-phase (name (v/presence-phase))}
   (str label)])

(v/defview stack
  [{:keys [items]}]
  [:div#stack
   (v/presence {:timeout-ms timeout-ms}
     (for [{:keys [k label]} items]
       [marker {:key k :id k :label label}]))])

;; ---------------------------------------------------------------------------
;; The suspending sibling — how a render is genuinely ABANDONED
;;
;; A plain React component, deliberately: suspending is a React-host fact,
;; not something a Freehand view declares. It sits AFTER the presence
;; boundary in document order, so the candidate has already rendered the
;; boundary and its children by the time this throws. React renders
;; transition work that suspends but never commits it — which is exactly the
;; abandoned candidate the law is about.
;; ---------------------------------------------------------------------------

(defonce ^:private never-resolves (js/Promise. (fn [_ _] nil)))

(def ^:private suspender-renders
  "How many times the suspending sibling has been rendered. Non-zero is the
  evidence that the abandoned candidate really RAN."
  (atom 0))

(defn- suspender [^js props]
  (when (.-suspend props)
    (swap! suspender-renders inc)
    ;; `use` may be called conditionally — it is the one hook that may.
    (react/use never-resolves))
  nil)

(defn- probe-element [items suspend?]
  (react/createElement
    react/Suspense
    #js {:fallback (react/createElement "div" #js {:id "fallback"} "waiting")}
    (fr/element [stack {:items items}])
    (react/createElement suspender #js {:suspend suspend?})))

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

(defn- marker-el [container id]
  (.querySelector container (str ".marker[data-id='" id "']")))

(defn- label-of [container id]
  (some-> (marker-el container id) (.getAttribute "data-label")))

(defn- phase-of [container id]
  (some-> (marker-el container id) (.getAttribute "data-phase")))

(defn- render! [root items]
  (act #(.render root (probe-element items false))))

(defn- render-abandoned! [root items]
  (act #(react/startTransition (fn [] (.render root (probe-element items true))))))

(def ^:private all      [{:k "a" :label "a"}
                         {:k "b" :label "committed-b"}
                         {:k "c" :label "c"}])
(def ^:private minus-c  [{:k "a" :label "a"} {:k "b" :label "committed-b"}])
(def ^:private minus-bc [{:k "a" :label "a"}])
(def ^:private re-enter [{:k "a" :label "a"} {:k "b" :label "abandoned-b"}])

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

;; ===========================================================================
;; The law
;; ===========================================================================

(deftest an-abandoned-render-publishes-no-presence-element
  (testing "A candidate render that re-enters a retained key with a NEW
            element and then suspends never commits, so the element it
            derived must stay unpublished: when an unrelated, earlier exit
            timer force-updates the CURRENT tree, the retained child still
            renders its last COMMITTED element."
    (if-not (browser? )
      (skip! "the browser job runs the commit-ownership assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (reset! suspender-renders 0)
        (let [[container root] (mount!)]
          (-> (render! root all)
              (.then (fn [_]
                       (is (= "committed-b" (label-of container "b")) "b mounted committed")
                       (is (some? (marker-el container "c")) "c mounted")
                       (is (= 0 (presence/pending-count)) "nothing is retained yet")
                       ;; t=0 — c departs, its exit is due at t=100
                       (render! root minus-c)))
              (.then (fn [_]
                       (is (= "unmounting" (phase-of container "c")) "c is retained :unmounting")
                       (is (= 1 (presence/pending-count)) "c's exit is scheduled")
                       ;; advance to t=50 without firing anything
                       (act #(presence/flush-presence! 50))))
              (.then (fn [_]
                       (is (= 1 (presence/pending-count)) "c's exit is still pending at t=50")
                       ;; t=50 — b departs, its exit is due at t=150 (AFTER c's)
                       (render! root minus-bc)))
              (.then (fn [_]
                       (is (= "unmounting" (phase-of container "b")) "b is retained :unmounting")
                       (is (= "committed-b" (label-of container "b"))
                           "and retains its last committed element")
                       (is (= 2 (presence/pending-count)) "two exits are pending")
                       ;; the abandoned candidate: b re-enters as "abandoned-b",
                       ;; then a sibling suspends, so nothing commits
                       (render-abandoned! root re-enter)))
              (.then (fn [_]
                       (is (pos? @suspender-renders)
                           "the candidate really rendered — it reached the suspending sibling")
                       (is (= "committed-b" (label-of container "b"))
                           "and really did not commit — the DOM is untouched")
                       (is (= "unmounting" (phase-of container "b"))
                           "b's committed phase is untouched too")
                       (is (= 2 (presence/pending-count))
                           "and the abandoned candidate cancelled no exit")
                       ;; t=101 — ONLY c's earlier exit comes due. Its removal
                       ;; callback force-updates the CURRENT tree: the one commit
                       ;; a leaked element would ride out on.
                       (act #(presence/flush-presence! 51))))
              (.then (fn [_]
                       (is (nil? (marker-el container "c"))
                           "c's exit fired and removed it terminally")
                       (is (some? (marker-el container "b"))
                           "b is still retained — its own exit is not due")
                       (is (= "committed-b" (label-of container "b"))
                           "and STILL renders its last committed element: the abandoned
                            candidate published nothing")
                       (is (= 1 (presence/pending-count)) "only b's exit remains")
                       ;; the retention machine is otherwise untouched
                       (act #(presence/flush-presence!))))
              (.then (fn [_]
                       (is (nil? (marker-el container "b"))
                           "b's own exit still removes it terminally")
                       (is (some? (marker-el container "a")) "a is untouched throughout")
                       (is (= 0 (presence/pending-count)) "every exit fired exactly once")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Both arms tore down identically, so the teardown rides the
              ;; single trailing step: written once, run once per path.
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; A COMMITTED render still owns its elements
;; ===========================================================================

(deftest a-committed-render-still-publishes-its-elements
  (testing "The other half of the law: withholding publication from a
            candidate must not withhold it from a COMMIT. A same-key prop
            update still replaces the rendered element, and a committed
            re-entry still cancels the exit and returns the child to
            `:present` — with the element the re-entry supplied."
    (if-not (browser?)
      (skip! "the browser job runs the committed-publication assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[container root] (mount!)]
          (-> (render! root all)
              (.then (fn [_]
                       (is (= "committed-b" (label-of container "b")) "b mounted committed")
                       ;; a plain same-key prop update
                       (render! root [{:k "a" :label "a"}
                                      {:k "b" :label "updated-b"}
                                      {:k "c" :label "c"}])))
              (.then (fn [_]
                       (is (= "updated-b" (label-of container "b"))
                           "a committed prop update replaces the rendered element")
                       ;; b departs, then re-enters with a THIRD element
                       (render! root [{:k "a" :label "a"} {:k "c" :label "c"}])))
              (.then (fn [_]
                       (is (= "unmounting" (phase-of container "b")) "b is retained :unmounting")
                       (is (= "updated-b" (label-of container "b"))
                           "retaining the LAST committed element, not the first")
                       (is (= 1 (presence/pending-count)) "its exit is scheduled")
                       (render! root [{:k "a" :label "a"}
                                      {:k "b" :label "re-entered-b"}
                                      {:k "c" :label "c"}])))
              (.then (fn [_]
                       (is (= "present" (phase-of container "b"))
                           "a committed re-entry returns the child to :present")
                       (is (= "re-entered-b" (label-of container "b"))
                           "with the element the re-entry supplied")
                       (is (= 0 (presence/pending-count))
                           "and cancels the pending exit")))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared teardown, hoisted onto the single trailing step.
              (.then (fn [_] (teardown! container root) (done)))))))))
