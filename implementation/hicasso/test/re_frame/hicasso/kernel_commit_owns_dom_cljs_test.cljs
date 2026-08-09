(ns re-frame.hicasso.kernel-commit-owns-dom-cljs-test
  "INVARIANT I5, UNDER REAL REACT — render probes, commit owns
  (rf2-hic-010).

  > Render is speculative: it probes reads, acquires no durable ownership,
  > and publishes no committed evidence. The selected commit reconciles
  > exactly its read and host set; disconnect releases it. Abandoned
  > renders leave no subscriptions and no diagnostic records; teardown is
  > exact and testable, with zero residue after quiescence.
  >
  > — `docs/design/hicasso/product/invariants.md`, I5

  [[re-frame.hicasso.kernel-commit-owns-cljs-test]] states the acquisition
  laws exactly, at the collector's published seam, and runs on every PR.
  **It cannot say that React abandons anything** — it drives the render and
  the commit itself. This file is where that claim is made, and it is made
  the only way it can be: by making React 19 abandon a render, four
  different ways, and reading what the runtime kept.

  ## Abandonment here is REAL, and each row proves its own premise

  A test that asserts \"nothing was acquired\" is worthless if no body ever
  ran — the assertion would hold for a render that never happened, which
  is the cheapest possible false green. **Every row below therefore asserts
  the premise first**: `collector/body-runs` moved, so React genuinely ran
  the body it then threw away. Where the premise is what the row waits on,
  it is also the poll condition, so the row cannot proceed without it.

  The four mechanisms, all of them React's own:

  | row | how React abandons the render |
  |---|---|
  | [[a-suspended-attempt-acquires-nothing-and-its-retry-acquires-exactly-once]] | a sibling throws a thenable; React discards the attempt and commits the fallback |
  | [[an-aborted-transition-acquires-nothing-and-its-completion-acquires-exactly-once]] | the tree a `startTransition` rendered suspends, so React keeps the old UI and drops the new render entirely |
  | [[strictmodes-double-invoke-is-not-additive]] | React runs every body twice and mounts, unmounts and remounts every effect |
  | [[a-body-that-throws-after-its-reads-is-caught-and-the-retry-acquires-exactly-once]] | a render-phase throw, caught by `h/boundary`, retried through `:reset-key` |

  A fifth property the bead names — **delayed commit** — is
  [[a-write-landing-in-the-render-to-commit-gap-heals-the-boundary]]: a
  sibling's `useLayoutEffect` writes after every body has returned and
  before React runs the passive effect that acquires, which is the
  render→commit gap with a real write inside it.

  ## The observable, and why it can tell this runtime from plain React

  Two kinds, chosen for two different failure modes.

  **Reader membership** (`inventory/cell-readers`) carries every
  abandonment row. The reason is the lesson a sibling witness paid for on
  the controlled-input surface the same night: a value assertion stays
  green under a real leak. If an abandoned attempt DID acquire, the page
  still paints the correct text — React committed the right tree, and the
  leaked registration is simply an extra slot on a cell that no cleanup
  will ever remove. `exactly 1 reader` and `2 readers` render identically
  and differ absolutely, so the reader slot is what is read. Plain React
  has no such structure to read; this is the runtime's own retention, and
  a leak is invisible anywhere else.

  **The painted value** carries the gap row, and only that one, because
  there the failure mode IS a stale value: a staged read that moved
  between render and commit and was never corrected paints the old number
  forever. That is precisely what plain `useSyncExternalStore` over a
  store with no commit basis would do, so the value is the observable that
  distinguishes them.

  ## No clock

  Every wait is [[re-frame.test-support/poll-until]] on the condition
  itself, or [[re-frame.hicasso.impl.inventory/quiesced!]] — the runtime's
  own reaper horizon. Nothing here sleeps a chosen number of
  milliseconds, and nothing assumes a reaper has run at a point the
  runtime has not said it has.

  ## The census is taken BEFORE the fixture resets anything

  `mount/release!` resets the runtime, so a residue reading taken after it
  answers zero whether teardown worked or not — a gate that cannot go red
  (rf2-2rtt6.48). Every row here therefore ends with
  [[teardown-census!]]: `mount/unmount!`, settle at the runtime's horizon,
  ASSERT, and only then release.

  ## On the node lane this file states a skip, never a green

  `:node-test` has no DOM. Every row degrades to an explicit skip rather
  than to an assertion that passes because nothing ran."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set :as set]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]))

(def ^:private frame-id ::kernel-commit-owns-dom)

(rf/reg-sub :kcod/left  (fn [db _] (:left db)))
(rf/reg-sub :kcod/right (fn [db _] (:right db)))

(rf/reg-event :kcod/seed      (fn [_ [_ db]] {:db db}))
(rf/reg-event :kcod/bump-left (fn [{:keys [db]} _] {:db (update db :left inc)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The exercised population — a MEASUREMENT, not a claim
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  "The abandonment and commit mechanisms this file undertakes to exercise.
  [[the-declared-population-was-actually-exercised]] asserts that every one
  of them was reached at runtime, so the roster cannot drift into a list of
  things the suite used to do."
  #{:suspense/abandon-and-retry
    :transition/abort-and-complete
    :strict-mode/double-invoke
    :error-boundary/throw-and-retry
    :render-to-commit-gap/heal})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "kernel-commit-owns DOM witness needs a real React DOM — " why)))

(defn- seeded!
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:kcod/seed {:left 1 :right 7}]))
  frame-id)

(defn- k [query-v] [frame-id query-v])

(def ^:private nothing-owned {:cells 0 :cell-refs 0 :boundaries 0 :edges 0})

(defn- ownership [] (dissoc (inventory/residue) :entries))

(defn- readers-of [query-v] (count (inventory/cell-readers (k query-v))))

(defn- app
  "The hicasso subtree: the frame provider over a root element."
  [hiccup]
  (mount/provider frame-id (codec/root-element frame-id hiccup)))

(defn- mount-concurrent!
  "A concurrent root, rendered WITHOUT `flushSync`.

  Deliberately not `mount/root!`. That door renders inside `flushSync`
  because the witnesses it was built for read the DOM on the next line;
  here a synchronous flush would be inventing a schedule — Suspense and
  transitions are React's concurrent business, and a row that forced them
  sync would be a row about the forced schedule. Every wait below is a
  poll on the condition instead."
  [container element]
  (let [root (react-dom-client/createRoot container)]
    (.render root element)
    {:root root :container container :frame frame-id}))

(defn- text [handle] (.-textContent ^js (:container handle)))

(defn- poll
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000}))

(defn- teardown-census!
  "Unmount, settle at the runtime's own horizon, ASSERT, and only then
  release. `mount/release!` resets the runtime, so a census taken after it
  cannot go red — see the namespace docstring."
  [handle]
  (mount/unmount! handle)
  (.then (inventory/quiesced!)
         (fn [_]
           (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                  (inventory/residue))
               "teardown is exact: zero residue after quiescence")
           (mount/release! handle)
           nil)))

(defn- fail-and-finish!
  [done label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | DOM was " (pr-str (when handle (text handle)))
                   " | ownership " (pr-str (ownership))))
    (when handle (mount/release! handle))
    (done)))

(defn- make-gate
  "A REAL React suspension: a component that throws a thenable until it is
  released. Everything after the release is React's own retry machinery —
  nothing here schedules, re-renders or commits anything."
  []
  (let [!released (atom false)
        !resolve  (atom nil)
        promise   (js/Promise. (fn [res] (reset! !resolve res)))
        component (fn gate [_] (when-not @!released (throw promise)) nil)]
    (unchecked-set component "displayName" "kco/gate")
    {:element  (react/createElement component nil)
     :release! (fn [] (reset! !released true) (@!resolve nil) nil)}))

;; ---------------------------------------------------------------------------
;; The views
;; ---------------------------------------------------------------------------

(h/defview left-line  [_] [:p {:id "left"} (str "left=" (h/sub [:kcod/left]))])
(h/defview right-line [_] [:p {:id "right"} (str "right=" (h/sub [:kcod/right]))])

(def ^:private !throw? (atom false))

(h/defview thrower
  "Reads FIRST and throws SECOND — the bead's rollback shape. The reads are
  real reads, taken before the failure, so a runtime that acquired at
  render would have acquired them."
  [_]
  (let [v (h/sub [:kcod/right])]
    (when @!throw? (throw (js/Error. "planted kernel-commit-owns throw")))
    [:p {:id "right"} (str "right=" v)]))

;; ---------------------------------------------------------------------------
;; 1. Suspense — React discards the attempt and commits the fallback
;; ---------------------------------------------------------------------------

(deftest a-suspended-attempt-acquires-nothing-and-its-retry-acquires-exactly-once
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            gate   (make-gate)
            before (collector/body-runs)
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (react/createElement
                       (.-Suspense react)
                       #js {:fallback (react/createElement "p" nil "waiting")}
                       (react/createElement (.-Fragment react) nil
                                            (app [left-line {}])
                                            (:element gate))))]
        (-> (poll #(= "waiting" (text handle)) "the fallback commits")
            (.then
              (fn [_]
                (testing "the premise: React RAN the boundary body before it
                          threw the attempt away. Without this the zeros
                          below are the zeros of a render that never was"
                  (is (pos? (- (collector/body-runs) before))))

                (testing "and the attempt was genuinely abandoned — the
                          fallback is on the page, the boundary's markup is
                          not"
                  (is (= "waiting" (text handle))))

                (testing "so the abandoned render acquired nothing: no cell,
                          no reference, no edge, and no reader on the key it
                          read"
                  (is (= nothing-owned (ownership)))
                  (is (zero? (readers-of [:kcod/left]))))

                ((:release! gate))
                (poll #(= "left=1" (text handle)) "the retry commits")))
            (.then
              (fn [_]
                (testing "the retry committed the boundary React had thrown
                          away, and acquired its read set EXACTLY ONCE — two
                          readers here would paint the identical page and
                          leak a subscription for the life of the mount"
                  (is (= 1 (readers-of [:kcod/left])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (ownership))))
                (exercised! :suspense/abandon-and-retry)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "suspense witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 2. Transition abort — React renders the new tree and drops it whole
;; ---------------------------------------------------------------------------

(def ^:private !set-phase (atom nil))

(defn- phase-host
  [^js props]
  (let [[phase set-phase] (react/useState 0)]
    (react/useEffect (fn [] (reset! !set-phase set-phase) js/undefined)
                     #js [set-phase])
    (if (zero? phase)
      (react/createElement "p" nil "old")
      (unchecked-get props "next"))))

(unchecked-set phase-host "displayName" "kco/phase-host")

(deftest an-aborted-transition-acquires-nothing-and-its-completion-acquires-exactly-once
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_    (seeded!)
            gate (make-gate)
            next (react/createElement (.-Fragment react) nil
                                      (app [right-line {}])
                                      (:element gate))
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (react/createElement
                       (.-Suspense react)
                       #js {:fallback (react/createElement "p" nil "fallback")}
                       (react/createElement phase-host #js {:next next})))]
        (-> (poll #(and (= "old" (text handle)) (some? @!set-phase))
                  "the old tree commits")
            (.then
              (fn [_]
                (let [before (collector/body-runs)]
                  ;; A REAL transition. React renders the new tree at
                  ;; transition priority; it suspends; React keeps the
                  ;; committed UI and throws the new render away.
                  ((.-startTransition react) (fn [] (@!set-phase 1)))
                  ;; The poll condition IS the premise — the row cannot
                  ;; proceed until React has actually run the new body.
                  (-> (poll #(> (collector/body-runs) before)
                            "the transition renders the new body")
                      (.then
                        (fn [_]
                          (testing "React kept the committed UI rather than
                                    showing the fallback, which is what makes
                                    this an ABORTED transition and not a
                                    suspension"
                            (is (= "old" (text handle))))

                          (testing "the new body ran and was discarded, so it
                                    acquired nothing at all"
                            (is (zero? (readers-of [:kcod/right])))
                            (is (= nothing-owned (ownership))))

                          ((:release! gate))
                          (poll #(= "right=7" (text handle))
                                "the transition completes")))))))
            (.then
              (fn [_]
                (testing "and the completion acquires the read set exactly
                          once — the abandoned attempt contributed no second
                          reader"
                  (is (= 1 (readers-of [:kcod/right])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (ownership))))
                (exercised! :transition/abort-and-complete)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "transition witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 3. StrictMode — two body runs, one mount/unmount/remount, one acquisition
;; ---------------------------------------------------------------------------

(deftest strictmodes-double-invoke-is-not-additive
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            before (collector/body-runs)
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (react/createElement (.-StrictMode react) nil
                                          (app [left-line {}])))]
        (-> (poll #(= "left=1" (text handle)) "the strict tree commits")
            (.then
              (fn [_]
                (testing "the premise, and it is the whole point of the row:
                          StrictMode really did run the body TWICE. A green
                          here with a delta of 1 would be a green for a
                          StrictMode that never engaged"
                  (is (= 2 (- (collector/body-runs) before))))

                (testing "two body runs, and ONE acquisition. The scratch is
                          reset by overwrite at the top of every body, so the
                          second run replaces the first's reads instead of
                          being appended to them"
                  (is (= 1 (readers-of [:kcod/left])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (ownership))))

                (testing "and StrictMode's mount/unmount/remount of every
                          effect left exactly one live registration, not a
                          stranded one beside it"
                  (is (= 1 (:entries (inventory/residue)))))

                (testing "the boundary is the LIVE one: a write still reaches
                          it, so the surviving registration is the remounted
                          one and not a detached first"
                  (mount/dispatch! handle [:kcod/bump-left])
                  (poll #(= "left=2" (text handle)) "the write reaches it"))))
            (.then
              (fn [_]
                (is (= 1 (readers-of [:kcod/left]))
                    "and the write added no reader")
                (exercised! :strict-mode/double-invoke)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "strictmode witness" handle)))))))

;; ---------------------------------------------------------------------------
;; 4. Error then retry — a render-phase throw, caught and retried
;; ---------------------------------------------------------------------------

(defn- guarded
  [reset-key]
  (app [h/boundary {:fallback [:p {:id "fb"} "caught"] :reset-key reset-key}
        [thrower {}]]))

(deftest a-body-that-throws-after-its-reads-is-caught-and-the-retry-acquires-exactly-once
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            _      (reset! !throw? true)
            before (collector/body-runs)
            handle (mount-concurrent! (mount/fresh-container!) (guarded 0))]
        (-> (poll #(= "caught" (text handle)) "the error boundary catches")
            (.then
              (fn [_]
                (testing "the premise: the body RAN, took its read, and then
                          threw"
                  (is (pos? (- (collector/body-runs) before))))

                (testing "the throwing attempt is rolled back to nothing —
                          there is nothing to roll back, because a
                          render-phase read acquires nothing"
                  (is (zero? (readers-of [:kcod/right])))
                  (is (= nothing-owned (ownership))))

                (reset! !throw? false)
                ;; The retry is the CALLER's to schedule: a changed
                ;; `:reset-key` clears the caught failure and remounts.
                (.render ^js (:root handle) (guarded 1))
                (poll #(= "right=7" (text handle)) "the retry commits")))
            (.then
              (fn [_]
                (testing "and the retry acquires exactly its read set, with
                          no contribution from the attempt that threw"
                  (is (= 1 (readers-of [:kcod/right])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (ownership))))
                (exercised! :error-boundary/throw-and-retry)
                (teardown-census! handle)))
            (.then (fn [_] (reset! !throw? false) (done)))
            (.catch (fn [e]
                      (reset! !throw? false)
                      ((fail-and-finish! done "error-retry witness" handle) e))))))))

;; ---------------------------------------------------------------------------
;; 5. Delayed commit — a write inside the render→commit gap
;; ---------------------------------------------------------------------------

(defn- gap-writer
  "Writes from a LAYOUT effect: after every body in the tree has returned,
  and before React runs the passive effect in which
  `useSyncExternalStore` acquires. That interval is the render→commit gap,
  and this is a real write landing inside it."
  [_]
  (react/useLayoutEffect
    (fn [] (collector/dispatch! frame-id [:kcod/bump-left]) js/undefined)
    #js [])
  nil)

(unchecked-set gap-writer "displayName" "kco/gap-writer")

(deftest a-write-landing-in-the-render-to-commit-gap-heals-the-boundary
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (let [_      (seeded!)
            handle (mount-concurrent!
                     (mount/fresh-container!)
                     (react/createElement (.-Fragment react) nil
                                          (app [left-line {}])
                                          (react/createElement gap-writer nil)))]
        ;; `:kcod/left` is STAGED at render — no cell holds it, so it has no
        ;; watch and no epoch, and the flush generation is structurally
        ;; blind to a move. `commit-basis` is what is not blind, and the
        ;; painted value is what says whether the repair worked: a boundary
        ;; that never heard about the write paints `left=1` forever.
        (-> (poll #(= "left=2" (text handle)) "the boundary heals to the value that moved")
            (.then
              (fn [_]
                (testing "the body rendered against left=1, the layout effect
                          wrote left=2 before the commit acquired, and React's
                          post-subscribe re-read of the snapshot corrected the
                          boundary rather than leaving it stale"
                  (is (= "left=2" (text handle))))

                (testing "and the healed boundary holds exactly one reader —
                          the correcting re-render replaced its registration,
                          it did not add one"
                  (is (= 1 (readers-of [:kcod/left])))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (ownership))))

                (exercised! :render-to-commit-gap/heal)
                (teardown-census! handle)))
            (.then (fn [_] (done)))
            (.catch (fail-and-finish! done "render-to-commit-gap witness" handle)))))))

;; ---------------------------------------------------------------------------
;; The population, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  ;; Declared LAST so every row above has run. The roster is not prose: a
  ;; mechanism that stops being reached — a row deleted, a row that returns
  ;; early, a row whose poll silently degrades — fails here instead of
  ;; quietly shrinking what the suite covers.
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM, so no mechanism is exercised there")
    (is (= declared-population @!exercised)
        (str "every declared abandonment mechanism must be reached; missing: "
             (pr-str (set/difference declared-population @!exercised))))))
