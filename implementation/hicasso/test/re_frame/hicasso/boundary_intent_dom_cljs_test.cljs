(ns re-frame.hicasso.boundary-intent-dom-cljs-test
  "AN INTENT ON A BOUNDARY'S FALLBACK, AND ON ITS CHILDREN (rf2-uo9di).

  The sibling of [[re-frame.hicasso.presence-intent-dom-cljs-test]], and
  the identical mechanism one component along. A `h/boundary`'s
  `:fallback` and its
  `:children` are hiccup **data**, written in the parent boundary's body
  — and both are **lowered inside the class component's own React
  render**, after that body's dynamic extent has unwound. With no ambient
  frame re-bound there, `intent/*dispatch*` was nil at the moment the
  codec walked those props, so

      [boundary {:fallback [:button {:on-click [:app/retry]} \"try again\"]
                 :reset-key attempt}
       [risky {}]]

  raised `:rf.error/hicasso-intent-outside-boundary` while the boundary
  rendered its fallback — and an `h/fn` at an event position raised the
  same id at invocation.

  ## Why the fallback half is the sharp one

  HD-020(c) ships `:fallback` **beside** `:reset-key`, and the reason it
  gives is that \"the retry is the CALLER's to schedule\". The control that
  schedules it is a button, the button lives in the fallback, and its
  `:on-click` is an intent — so the ruling's own worked example was the
  one thing the component could not render. Worse than unwritable: a
  fallback that throws while rendering does not fail quietly in a corner,
  it takes the *next* boundary up, so an application's error path became
  an application-wide failure.

  Seven claims:

  1. a **retry button on the fallback** dispatches, and the `:reset-key`
     it moves actually re-mounts the child — HD-020(c)'s whole story,
     end to end;
  2. an **`h/fn` at an event position on a function fallback** dispatches
     what it returns, closing over the error the fallback was handed;
  3. a **bare intent vector on a native child** of `h/boundary`
     dispatches;
  4. an **`h/fn` on a native child** does;
  5. the intent lands in the frame the **boundary** was mounted under,
     proved against a second live frame rather than against an absence;
  6. a boundary with no frame above it is still legal until something
     below it writes an intent, and that intent is still the loud error,
     **named**;
  7. the class still spends **no hook** — in its error state as well as
     its healthy one, so the repair did not buy the fence what
     `impl.presence-react` had to buy it.

  ## Why the shapes are on SEPARATE subjects

  The two intent shapes fail at different **moments**. A bare vector is
  refused at LOWERING, during the boundary's own render, so a subject
  carrying one never reaches the screen at all; an `h/fn` is lowered
  happily with no frame (the dispatch is captured, not required), renders,
  and raises at INVOCATION. Mixed onto one subject the first would mask
  the second, and every red on the callback path could be blamed on its
  louder sibling. So rows 2 and 4 carry the callback form and nothing
  else.

  ## Why most rows mount under a watching boundary

  A throw while the boundary lowers its own fallback or children escapes
  the class and lands on the **next** boundary above it, and React does
  not print the `ex-info` it swallowed. [[watched-root!]] puts an
  ordinary `h/boundary` there for no reason except to catch that and hand
  it to the assertion message, so a mutation names
  `:rf.error/hicasso-intent-outside-boundary` in the test output rather
  than in a browser console somebody has to go and read. Its own fallback
  is deliberately intent-free — it is the instrument, and an instrument
  that can fail the way the subject fails measures nothing.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.impl.boundary :refer [boundary]]
            [re-frame.hicasso.hook-probe :as probe]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::boundary-intent)
(def ^:private other-frame-id ::boundary-intent-other)

;; Registered ABOVE `use-fixtures`, deliberately — the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED and
;; restores it before every test, so a `reg-sub` written below it is erased
;; before the first row runs.

(rf/reg-sub :hicasso.bdy/boom? (fn [db _] (:boom? db)))
(rf/reg-sub :hicasso.bdy/attempt (fn [db _] (:attempt db)))

(rf/reg-event :hicasso.bdy/seed (fn [_ _] {:db {:boom? true :attempt 0}}))

(rf/reg-event :hicasso.bdy/retry
              (fn [{:keys [db]} _]
                {:db (-> db (assoc :boom? false) (update :attempt inc))}))

(rf/reg-event :hicasso.bdy/dismissed
              (fn [{:keys [db]} [_ id]]
                {:db (update db :dismissed (fnil conj []) id)}))

(rf/reg-event :hicasso.bdy/noted
              (fn [{:keys [db]} [_ tag]]
                {:db (update db :noted (fnil conj []) tag)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; `:ambient-frame nil` is load-bearing. The fixture's default leaves a
     ;; dynamic-var frame stamp in scope, and a boundary that resolved its
     ;; frame through that tier instead of through React context would look
     ;; correct here for the wrong reason. With no ambient stamp, the only
     ;; thing that can name a frame is the provider above the tree.
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "an intent-on-a-boundary claim needs a real React DOM — " why)))

(defn- fresh! [frame-kw]
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-kw})
  (rf/with-frame frame-kw (rf/dispatch-sync [:hicasso.bdy/seed]))
  frame-kw)

(defn- db [frame-kw] (rf/app-db-value frame-kw))

(defn- query [handle sel] (.querySelector (:container handle) sel))

(defn- click! [node]
  (.click node)
  ;; TWICE, and not for luck. The click's own dispatch commits on the first
  ;; settle; a `:reset-key` that moved is then read by `componentDidUpdate`,
  ;; which clears the caught error with a `setState` of its own — a second
  ;; commit, from a lifecycle rather than from the event. Row 1 reads the DOM
  ;; after both.
  (mount/settle!)
  (mount/settle!))

;; ---------------------------------------------------------------------------
;; The instrument — a boundary that records what escaped the subject
;; ---------------------------------------------------------------------------

(def ^:private !caught (atom nil))

(defn- watched-root!
  "Mount `hiccup` under an ordinary `h/boundary` whose only job is to
  catch what the subject threw and hand it to the assertion message. Its
  fallback and its `:on-error` are intent-free: an instrument that fails
  the way the subject fails measures nothing."
  [frame-kw hiccup]
  (reset! !caught nil)
  (mount/root! (mount/fresh-container!) frame-kw
               [boundary {:fallback [:p.escaped "the subject refused to render"]
                          :on-error (fn [e] (reset! !caught e))}
                hiccup]))

(defn- escaped
  "What the watching boundary caught, as the two fields a reader wants."
  []
  (select-keys (ex-data @!caught) [:rf.error/id :intent :position]))

;; ---------------------------------------------------------------------------
;; The screens
;; ---------------------------------------------------------------------------

(h/defview risky
  "Throws from the render phase while the model says so — which is where a
  React error boundary is the only thing that can catch."
  [_]
  (when (collector/sub [:hicasso.bdy/boom?])
    (throw (ex-info "the child threw" {:planted true})))
  [:p.ok "recovered"])

(h/defview retry-screen
  "HD-020(c)'s ADVERTISED CASE, and the reason this bead is P2: a fallback
  whose whole point is a retry control, beside the `:reset-key` that makes
  the retry the caller's to schedule rather than the boundary's to guess."
  [_]
  [boundary {:fallback  [:div.fb
                         [:p "that did not work"]
                         [:button.retry {:on-click [:hicasso.bdy/retry]} "try again"]]
             :reset-key (collector/sub [:hicasso.bdy/attempt])}
   [risky {}]])

(h/defview note-fallback-screen
  "The FUNCTION fallback, carrying **only** the one callback form. Two
  things at once: `h/fn` is the second row of the position table, and the
  closure reads the error the fallback was handed — so this is also the
  `(fn [error] hiccup)` contract exercised at an event position rather
  than at a text node."
  [_]
  [boundary {:fallback (fn [error]
                         [:div.fb
                          [:button.note
                           {:data-role "note"
                            :on-click  (h/hfn [e]
                                         ;; A live event read, and ONE
                                         ;; intent returned — the event
                                         ;; contract the position imposes.
                                         (when (= "note" (.. e -target -dataset -role))
                                           [:hicasso.bdy/noted (ex-message error)]))}
                           "note"]])}
   [risky {}]])

(h/defview child-intent-screen
  "The CHILDREN half. Nothing throws here: these are ordinary native
  children of `h/boundary`, written in this body and lowered one render
  later inside the class's."
  [_]
  [boundary {:fallback [:p.fb "unused — nothing throws on this screen"]}
   [:div.body
    [:button.dismiss {:on-click [:hicasso.bdy/dismissed 1]} "dismiss"]]])

(h/defview child-callback-screen
  "The children half, carrying **only** the callback form — the same
  separation rows 1 and 2 keep, for the same reason."
  [_]
  [boundary {:fallback [:p.fb "unused — nothing throws on this screen"]}
   [:button.note
    {:data-role "note"
     :on-click  (h/hfn [e]
                  (when (= "note" (.. e -target -dataset -role))
                    [:hicasso.bdy/noted "child"]))}
    "note"]])

;; ---------------------------------------------------------------------------
;; 1 — the retry button HD-020(c) is sold on
;; ---------------------------------------------------------------------------

(deftest a-fallbacks-retry-button-dispatches-and-the-reset-key-retries
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (let [handle (watched-root! frame-id [retry-screen {}])]
        (try
          (is (nil? @!caught)
              (str "the boundary lowered its fallback with no ambient frame, so "
                   "the retry intent raised while it rendered and the failure "
                   "escaped to the boundary ABOVE — an application's error path "
                   "becoming an application-wide one. Escaped: " (pr-str (escaped))))
          (is (some? (query handle ".retry"))
              "the fallback, and the retry control on it, are on screen")
          (is (nil? (query handle ".ok"))
              "and the child that threw rendered nothing")
          (testing "the click reaches the frame, and the reset-key it moved
                    re-mounts the child — the whole of what `:fallback` beside
                    `:reset-key` was sold on"
            (click! (query handle ".retry"))
            (is (= 1 (:attempt (db frame-id)))
                "the intent on the fallback dispatched")
            (is (some? (query handle ".ok"))
                "and the retry succeeded: a changed :reset-key cleared the caught
                 failure and the child rendered")
            (is (nil? (query handle ".fb"))
                "with the fallback gone"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — the one callback form on a function fallback
;; ---------------------------------------------------------------------------

(deftest a-callback-on-the-fallback-dispatches-what-it-returned
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      ;; The callback-ONLY fallback, deliberately: see the namespace docstring.
      ;; A bare vector on the same fallback would fail earlier and louder, and
      ;; would take the credit for this row's red.
      (let [handle (watched-root! frame-id [note-fallback-screen {}])]
        (try
          (is (some? (query handle ".note"))
              "the fallback rendered — an h/fn is LOWERED with no frame in
               scope, because the dispatch is captured rather than required, so
               this path fails at invocation and not here")
          (click! (query handle ".note"))
          (is (= ["the child threw"] (:noted (db frame-id)))
              "at invocation the h/fn read the real event, closed over the error
               the fallback was handed, and the vector it RETURNED drained
               through the arm's synchronous door")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3 — a bare intent vector on a native child of h/boundary
;; ---------------------------------------------------------------------------

(deftest a-native-child-of-a-boundary-dispatches-its-inline-intent
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (let [handle (watched-root! frame-id [child-intent-screen {}])]
        (try
          (is (nil? @!caught)
              (str "the boundary lowered its CHILDREN with no ambient frame. "
                   "Escaped: " (pr-str (escaped))))
          (is (some? (query handle ".dismiss"))
              "the screen rendered at all — before this repair the intent on the
               child raised during the class's own render and there was no
               button to click")
          (is (nil? (:dismissed (db frame-id))))
          (click! (query handle ".dismiss"))
          (is (= [1] (:dismissed (db frame-id))))
          (click! (query handle ".dismiss"))
          (is (= [1 1] (:dismissed (db frame-id)))
              "and again, so this is a live handler and not a one-shot")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — the one callback form on a native child
;; ---------------------------------------------------------------------------

(deftest a-callback-on-a-native-child-dispatches-what-it-returned
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (let [handle (watched-root! frame-id [child-callback-screen {}])]
        (try
          (is (some? (query handle ".note")))
          (click! (query handle ".note"))
          (is (= ["child"] (:noted (db frame-id)))
              "the callback path, at a child position, on its own subject")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 5 — the frame is the BOUNDARY's, proved against a second live frame
;; ---------------------------------------------------------------------------

(deftest a-fallbacks-intent-lands-in-the-frame-the-boundary-was-mounted-under
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (fresh! other-frame-id)
      (let [a (watched-root! frame-id [retry-screen {}])
            b (mount/root! (mount/fresh-container!) other-frame-id
                           [retry-screen {}])]
        (try
          (click! (query a ".retry"))
          (is (= 1 (:attempt (db frame-id))))
          (is (= 0 (:attempt (db other-frame-id)))
              "the second frame's app-db did not move — a frame is an isolated
               context, and a fallback that dispatched to whatever was ambient
               rather than to its own provider would have moved both")
          (click! (query b ".retry"))
          (is (= 1 (:attempt (db other-frame-id))))
          (is (= 1 (:attempt (db frame-id)))
              "and symmetrically the other way")
          (finally (mount/release! a) (mount/release! b)))))))

;; ---------------------------------------------------------------------------
;; 6 — a frameless boundary is legal; an intent under one is still the loud error
;; ---------------------------------------------------------------------------

(defn- frameless-root!
  "A root whose provider carries the **no-provider sentinel** — the React
  context default, so this is exactly a tree with no frame boundary above
  it, reached without a second door in `mount`. The children are native
  hiccup throughout: a `defview` product refuses to render frameless on
  its own account (`:rf.error/no-frame-context`), which would answer a
  different question."
  [hiccup]
  (reset! !caught nil)
  (mount/root! (mount/fresh-container!) adapter-context/no-provider-sentinel
               [boundary {:fallback [:p.escaped "the subject refused to render"]
                          :on-error (fn [e] (reset! !caught e))}
                hiccup]))

(deftest a-frameless-boundary-is-legal-until-something-below-writes-an-intent
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (support/leave-act-environment!)
      (testing "a boundary with no frame above it and no intent below it
                renders. The class reads no subscription, so requiring a frame
                it does not need would refuse a legal page — the binding is
                unconditional and simply carries nil"
        (let [handle (frameless-root!
                       [boundary {:fallback [:p.fb "unused"]}
                        [:p.body "quiet"]])]
          (try
            (is (some? (query handle ".body")))
            (is (nil? @!caught) (str "nothing was raised. Escaped: " (pr-str (escaped))))
            (finally (mount/release! handle)))))
      (testing "and an intent under one is the loud error, NAMED — the
                diagnostic points at the intent rather than at the boundary"
        (let [handle (frameless-root!
                       [boundary {:fallback [:p.fb "unused"]}
                        [:button.dismiss {:on-click [:hicasso.bdy/dismissed 1]} "x"]])]
          (try
            (is (some? (query handle ".escaped"))
                "the boundary failed rather than rendering an inert handler")
            (is (= :rf.error/hicasso-intent-outside-boundary
                   (:rf.error/id (ex-data @!caught)))
                (str "and it named the intent: " (pr-str (ex-data @!caught))))
            (is (= [:hicasso.bdy/dismissed 1] (:intent (ex-data @!caught))))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 7 — the fence: the class still spends no hook, in its error state too
;; ---------------------------------------------------------------------------

(deftest the-boundary-spends-no-hook-in-its-error-state-either
  (testing "`impl.presence-react` had to buy a `useContext` to resolve its frame,
           and paid for it in HD-025's stated cost. This class did not: it
           already resolves its frame through `contextType`, which is a
           property of the component rather than a hook call, so the repair
           is invisible at React's dispatcher. Counted on the page where the
           new binding actually runs — a boundary in its ERROR state,
           rendering the fallback"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (if-not (probe/install!)
        (is false (str "React's internals slot was not found, so this claim is "
                       "UNWITNESSED on this build. A gate nobody has watched "
                       "fire is not evidence — fix "
                       (pr-str 're-frame.hicasso.hook-probe)
                       " rather than reading this as a pass."))
        (do
          (fresh! frame-id)
          (let [handle (volatile! nil)
                names  (probe/record!
                         (fn [] (vreset! handle
                                         (watched-root! frame-id
                                                        [retry-screen {}]))))]
            (try
              (is (some? (query @handle ".retry"))
                  "the fallback really did render, so the counts below were
                   taken over the path this repair changed")
              (is (= #{"useContext" "useSyncExternalStore"} (set names))
                  (str "every dispatcher read on this page belongs to a "
                       "`defview` SHELL — the two `collector/shell-hook-ledger` "
                       "declares. Two `h/boundary` classes are in this tree "
                       "(the watcher and the subject) and between them they "
                       "contributed none. Raw: " (pr-str names)))
              (is (= 2 (count collector/shell-hook-ledger) (count (distinct names)))
                  "and the declared shell ledger is the measured roster")
              (is (empty? (filter #{"useRef" "useMemo" "useCallback" "useState"
                                    "useEffect"}
                                  names))
                  "no useRef, no memo, no state and no effect — the class is
                   still a class")
              (finally (mount/release! @handle)))))))))
