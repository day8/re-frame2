(ns re-frame.hicasso.boundary-intent-dom-cljs-test
  "AN INTENT ON A BOUNDARY'S FALLBACK, AND ON ITS CHILDREN.

  The sibling of [[re-frame.hicasso.presence-intent-dom-cljs-test]], and
  the identical mechanism one component along. An `h/error-boundary`'s
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
  rendered its fallback — and an `h/event` at an event position raised the
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

  ## The closed roster rides here too

  Rows 8 and 9 are a different subject on the same instrument, and they
  are here because they need exactly what [[watched-root!]] already
  builds: a refusal raised inside the class's own `render` escapes to the
  boundary ABOVE, so reading it takes a watcher. The claim is that
  `h/error-boundary`'s four props are a CLOSED roster and a shape, refused
  rather than dropped — `{:on-errors …}` used to mint, cross `rfProps`
  intact and be consulted by nothing, and `{:on-error :app/failed}` used
  to reach `report!`'s `:else nil` and swallow every caught error.

  Each row carries its **near-miss positive control** in the same
  `deftest`, one character or one bracket away from the refused form, so
  a guard that grew too eager reddens the row that proves it exact rather
  than passing quietly: `:on-error` beside `:on-errors`, `[:app/failed]`
  beside `:app/failed`, and an explicit `{:on-error nil}` — which means
  *no reporting was asked for* and must stay legal.

  Seven claims about intents:

  1. a **retry button on the fallback** dispatches, and the `:reset-key`
     it moves actually re-mounts the child — HD-020(c)'s whole story,
     end to end;
  2. an **`h/event` at an event position on a function fallback** dispatches
     what it returns, closing over the error the fallback was handed;
  3. a **bare intent vector on a native child** of `h/error-boundary`
     dispatches;
  4. an **`h/event` on a native child** does;
  5. the intent lands in the frame the **boundary** was mounted under,
     proved against a second live frame rather than against an absence;
  6. a boundary with no frame above it is still legal until something
     below it writes an intent, and that intent is still the loud error,
     **named** — and the boundary's OWN `:on-error` vector is one of
     those intents, refused at its first render rather than accepted and
     dropped when it catches;
  7. the class still spends **no hook** — in its error state as well as
     its healthy one, so the repair did not buy the fence what
     `impl.presence-react` had to buy it.

  ## Why the shapes are on SEPARATE subjects

  The two intent shapes fail at different **moments**. A bare vector is
  refused at LOWERING, during the boundary's own render, so a subject
  carrying one never reaches the screen at all; an `h/event` is lowered
  happily with no frame (the dispatch is captured, not required), renders,
  and raises at INVOCATION. Mixed onto one subject the first would mask
  the second, and every red on the callback path could be blamed on its
  louder sibling. So rows 2 and 4 carry the callback form and nothing
  else.

  ## Why most rows mount under a watching boundary

  A throw while the boundary lowers its own fallback or children escapes
  the class and lands on the **next** boundary above it, and React does
  not print the `ex-info` it swallowed. [[watched-root!]] puts an
  ordinary `h/error-boundary` there for no reason except to catch that
  and hand it to the assertion message, so a mutation names
  `:rf.error/hicasso-intent-outside-boundary` in the test output rather
  than in a browser console somebody has to go and read. Its own fallback
  is deliberately intent-free — it is the instrument, and an instrument
  that can fail the way the subject fails measures nothing.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.hicasso.impl.boundary :refer [boundary]]
            [re-frame.hicasso.hook-probe :as rf.hicasso.hook-probe]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

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

;; The StrictMode row's pair: `:on-error` is dispatched with the error
;; appended, so the handler's second element IS the error; and a `:rearm`
;; is a FAILED reset — the retry the caller scheduled with the cause
;; still in place, `:attempt` moved and `:boom?` deliberately left true.
(rf/reg-event :hicasso.bdy/record-error
              (fn [{:keys [db]} [_ error]]
                {:db (update db :errors (fnil conj []) (ex-message error))}))

(rf/reg-event :hicasso.bdy/rearm
              (fn [{:keys [db]} _]
                {:db (update db :attempt inc)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; `:ambient-frame nil` is load-bearing. The fixture's default leaves a
     ;; dynamic-var frame stamp in scope, and a boundary that resolved its
     ;; frame through that tier instead of through React context would look
     ;; correct here for the wrong reason. With no ambient stamp, the only
     ;; thing that can name a frame is the provider above the tree.
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "an intent-on-a-boundary claim needs a real React DOM — " why)))

(defn- fresh! [frame-kw]
  (rf.hicasso.checkpoint-support/leave-act-environment!)
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
  (rf.hicasso.impl.mount/settle!)
  (rf.hicasso.impl.mount/settle!))

;; ---------------------------------------------------------------------------
;; The instrument — a boundary that records what escaped the subject
;; ---------------------------------------------------------------------------

(def ^:private !caught (atom nil))

(defn- watched-root!
  "Mount `hiccup` under an ordinary `h/error-boundary` whose only job is
  to catch what the subject threw and hand it to the assertion message.
  Its fallback and its `:on-error` are intent-free: an instrument that
  fails the way the subject fails measures nothing."
  [frame-kw hiccup]
  (reset! !caught nil)
  (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-kw
               [boundary {:fallback [:p.escaped "the subject refused to render"]
                          :on-error (fn [e] (reset! !caught e))}
                hiccup]))

(defn- escaped
  "What the watching boundary caught, as the two fields a reader wants."
  []
  (select-keys (ex-data @!caught) [:rf.error/id :intent :position]))

(defn- caught-id
  "The stable id of whatever escaped to the watcher, or nil if nothing
  did. Rows 8 and 9 branch on the id rather than on the message."
  []
  (:rf.error/id (ex-data @!caught)))

;; ---------------------------------------------------------------------------
;; The screens
;; ---------------------------------------------------------------------------

(rf.hicasso/defview risky
  "Throws from the render phase while the model says so — which is where a
  React error boundary is the only thing that can catch."
  [_]
  (when (rf.hicasso.impl.collector/sub [:hicasso.bdy/boom?])
    (throw (ex-info "the child threw" {:planted true})))
  [:p.ok "recovered"])

(rf.hicasso/defview retry-screen
  "HD-020(c)'s ADVERTISED CASE, and the reason this bead is P2: a fallback
  whose whole point is a retry control, beside the `:reset-key` that makes
  the retry the caller's to schedule rather than the boundary's to guess."
  [_]
  [boundary {:fallback  [:div.fb
                         [:p "that did not work"]
                         [:button.retry {:on-click [:hicasso.bdy/retry]} "try again"]]
             :reset-key (rf.hicasso.impl.collector/sub [:hicasso.bdy/attempt])}
   [risky {}]])

(rf.hicasso/defview note-fallback-screen
  "The FUNCTION fallback, carrying **only** the one callback form. Two
  things at once: `h/event` is the second row of the position table, and the
  closure reads the error the fallback was handed — so this is also the
  `(fn [error] hiccup)` contract exercised at an event position rather
  than at a text node."
  [_]
  [boundary {:fallback (fn [error]
                         [:div.fb
                          [:button.note
                           {:data-role "note"
                            :on-click  (rf.hicasso/event [e]
                                         ;; A live event read, and ONE
                                         ;; intent returned — the event
                                         ;; contract the position imposes.
                                         (when (= "note" (.. e -target -dataset -role))
                                           [:hicasso.bdy/noted (ex-message error)]))}
                           "note"]])}
   [risky {}]])

(rf.hicasso/defview child-intent-screen
  "The CHILDREN half. Nothing throws here: these are ordinary native
  children of `h/error-boundary`, written in this body and lowered one
  render later inside the class's."
  [_]
  [boundary {:fallback [:p.fb "unused — nothing throws on this screen"]}
   [:div.body
    [:button.dismiss {:on-click [:hicasso.bdy/dismissed 1]} "dismiss"]]])

(rf.hicasso/defview child-callback-screen
  "The children half, carrying **only** the callback form — the same
  separation rows 1 and 2 keep, for the same reason."
  [_]
  [boundary {:fallback [:p.fb "unused — nothing throws on this screen"]}
   [:button.note
    {:data-role "note"
     :on-click  (rf.hicasso/event [e]
                  (when (= "note" (.. e -target -dataset -role))
                    [:hicasso.bdy/noted "child"]))}
    "note"]])

;; ---------------------------------------------------------------------------
;; 1 — the retry button HD-020(c) is sold on
;; ---------------------------------------------------------------------------

(deftest a-fallbacks-retry-button-dispatches-and-the-reset-key-retries
  (if-not (rf.hicasso.impl.mount/browser?)
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
          (finally (rf.hicasso.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — the one callback form on a function fallback
;; ---------------------------------------------------------------------------

(deftest a-callback-on-the-fallback-dispatches-what-it-returned
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      ;; The callback-ONLY fallback, deliberately: see the namespace docstring.
      ;; A bare vector on the same fallback would fail earlier and louder, and
      ;; would take the credit for this row's red.
      (let [handle (watched-root! frame-id [note-fallback-screen {}])]
        (try
          (is (some? (query handle ".note"))
              "the fallback rendered — an h/event is LOWERED with no frame in
               scope, because the dispatch is captured rather than required, so
               this path fails at invocation and not here")
          (click! (query handle ".note"))
          (is (= ["the child threw"] (:noted (db frame-id)))
              "at invocation the h/event read the real event, closed over the error
               the fallback was handed, and the vector it RETURNED drained
               through the arm's synchronous door")
          (finally (rf.hicasso.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3 — a bare intent vector on a native child of h/error-boundary
;; ---------------------------------------------------------------------------

(deftest a-native-child-of-a-boundary-dispatches-its-inline-intent
  (if-not (rf.hicasso.impl.mount/browser?)
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
          (finally (rf.hicasso.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — the one callback form on a native child
;; ---------------------------------------------------------------------------

(deftest a-callback-on-a-native-child-dispatches-what-it-returned
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (let [handle (watched-root! frame-id [child-callback-screen {}])]
        (try
          (is (some? (query handle ".note")))
          (click! (query handle ".note"))
          (is (= ["child"] (:noted (db frame-id)))
              "the callback path, at a child position, on its own subject")
          (finally (rf.hicasso.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 5 — the frame is the BOUNDARY's, proved against a second live frame
;; ---------------------------------------------------------------------------

(deftest a-fallbacks-intent-lands-in-the-frame-the-boundary-was-mounted-under
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (fresh! other-frame-id)
      (let [a (watched-root! frame-id [retry-screen {}])
            b (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) other-frame-id
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
          (finally (rf.hicasso.impl.mount/release! a) (rf.hicasso.impl.mount/release! b)))))))

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
  (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) rf.adapter.context/no-provider-sentinel
               [boundary {:fallback [:p.escaped "the subject refused to render"]
                          :on-error (fn [e] (reset! !caught e))}
                hiccup]))

(deftest a-frameless-boundary-is-legal-until-something-below-writes-an-intent
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (rf.hicasso.checkpoint-support/leave-act-environment!)
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
            (finally (rf.hicasso.impl.mount/release! handle)))))
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
            (finally (rf.hicasso.impl.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 6b — and the boundary's OWN `:on-error` vector is one of those intents
;; ---------------------------------------------------------------------------

(deftest a-frameless-boundarys-own-on-error-vector-is-refused-at-first-render
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (rf.hicasso.checkpoint-support/leave-act-environment!)
      (testing "a vector `:on-error` is an INTENT, and a boundary with no frame
                above it has nothing that could dispatch it. Accepting the
                declaration and finding that out in `componentDidCatch` costs
                the application its error record at the exact moment the error
                path is exercised — and the loss LOOKS like successful error
                handling, because the fallback still renders over the failed
                subtree. So the declaration is refused at the boundary's own
                first render, on a HEALTHY child, which is what makes the proof
                independent of when a descendant happens to fail"
        (let [handle (frameless-root!
                       [boundary {:fallback [:p.fb "unused"]
                                  :on-error [:hicasso.bdy/noted "unroutable"]}
                        [:p.body "quiet"]])]
          (try
            (is (some? (query handle ".escaped"))
                "the subject refused rather than rendering under an :on-error
                 nothing could ever fire")
            (is (nil? (query handle ".body"))
                "and nothing below it reached the screen — a refusal that let
                 the page up anyway would be a warning wearing a throw's
                 clothes")
            (is (= {:rf.error/id :rf.error/hicasso-intent-outside-boundary
                    :intent      [:hicasso.bdy/noted "unroutable"]
                    :position    :on-error}
                   (escaped))
                (str "the same stable id an intent written UNDER a frameless
                      boundary already raises, named at the position and
                      carrying the intent, so the recovery is readable off the
                      refusal itself. Escaped: " (pr-str (ex-data @!caught))))
            (finally (rf.hicasso.impl.mount/release! handle)))))
      (testing "THE NEAR MISS, on the axis this guard was one step from
                over-reaching: a FUNCTION `:on-error` needs no frame, so the
                same frameless boundary takes one, catches a deliberate
                descendant failure and renders its fallback. It is the
                instrument the rows above already lean on — `frameless-root!`'s
                own watcher — asserted here as the subject rather than assumed"
        (let [!fired (atom [])
              handle (frameless-root!
                       [boundary {:fallback [:p.fb "caught"]
                                  :on-error (fn [e] (swap! !fired conj (ex-message e)))}
                        [:> (fn [] (throw (ex-info "the foreign child threw" {})))]])]
          (try
            (is (some? (query handle ".fb"))
                "the frameless boundary caught and its fallback is on screen")
            (is (nil? @!caught)
                (str "nothing escaped to the watcher. Escaped: " (pr-str (escaped))))
            (is (= ["the foreign child threw"] @!fired)
                (str "and the function fired ONCE, with the error. Got: "
                     (pr-str @!fired)))
            (finally (rf.hicasso.impl.mount/release! handle))))))))

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
    (if-not (rf.hicasso.impl.mount/browser?)
      (skip! ":node-test has no DOM")
      (if-not (rf.hicasso.hook-probe/install!)
        (is false (str "React's internals slot was not found, so this claim is "
                       "UNWITNESSED on this build. A gate nobody has watched "
                       "fire is not evidence — fix "
                       (pr-str 're-frame.hicasso.hook-probe)
                       " rather than reading this as a pass."))
        (do
          (fresh! frame-id)
          (let [handle (volatile! nil)
                names  (rf.hicasso.hook-probe/record!
                         (fn [] (vreset! handle
                                         (watched-root! frame-id
                                                        [retry-screen {}]))))]
            (try
              (is (some? (query @handle ".retry"))
                  "the fallback really did render, so the counts below were
                   taken over the path this repair changed")
              (is (= #{"useContext" "useSyncExternalStore"} (set names))
                  (str "every dispatcher read on this page belongs to a "
                       "`defview` SHELL — the two `runtime/shell-hook-ledger` "
                       "declares. Two `h/error-boundary` classes are in this "
                       "tree (the watcher and the subject) and between them they "
                       "contributed none. Raw: " (pr-str names)))
              (is (= 2 (count rf.hicasso.test.runtime/shell-hook-ledger) (count (distinct names)))
                  "and the declared shell ledger is the measured roster")
              (is (empty? (filter #{"useRef" "useMemo" "useCallback" "useState"
                                    "useEffect"}
                                  names))
                  "no useRef, no memo, no state and no effect — the class is
                   still a class")
              (finally (rf.hicasso.impl.mount/release! @handle)))))))))

;; ---------------------------------------------------------------------------
;; 8 — the roster is closed: a misspelled prop is refused, not dropped
;; ---------------------------------------------------------------------------

(deftest a-prop-outside-the-roster-is-refused-and-the-right-spelling-still-reports
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (testing "`:on-errors` mints, crosses `rfProps` intact and is consulted by
                nothing. That is not an ignored option — it is an error boundary
                that reports nothing, wearing a declaration that says it does, so
                the declaration is REFUSED at the boundary's own render"
        (let [handle (watched-root! frame-id
                       [boundary {:on-errors [:hicasso.bdy/noted "typo"]
                                  :fallback  [:p.fb "unused"]}
                        [:p.body "quiet"]])]
          (try
            (is (= :rf.error/hicasso-boundary-unknown-prop (caught-id))
                (str "the misspelling was refused and named. Escaped: "
                     (pr-str (ex-data @!caught))))
            (is (= :on-errors (:prop (ex-data @!caught)))
                "and the refusal carries the offending key rather than only the roster")
            (is (= #{:on-error :reset-key :fallback :children}
                   (:props (ex-data @!caught)))
                "with the four it checked against, so the recovery is readable
                 off the refusal itself")
            (is (nil? (query handle ".body"))
                "and the subject did not render — a refusal that let the page up
                 anyway would be a warning wearing a throw's clothes")
            (finally (rf.hicasso.impl.mount/release! handle)))))
      (testing "THE NEAR MISS. One character away, `:on-error` is the real key
                and still reports — beside `:fallback`, `:reset-key` and the
                trailing children, so the whole legal roster is exercised by the
                control rather than only the key under test"
        (let [handle (watched-root! frame-id
                       [boundary {:on-error  [:hicasso.bdy/noted "spelled right"]
                                  :fallback  [:p.fb "caught"]
                                  :reset-key 0}
                        [risky {}]])]
          (try
            (is (nil? @!caught)
                (str "nothing escaped the subject. Escaped: " (pr-str (escaped))))
            (is (some? (query handle ".fb"))
                "the child threw and the fallback is on screen")
            (rf.hicasso.impl.mount/settle!)
            (is (= ["spelled right"] (:noted (db frame-id)))
                "and the report reached the frame — the guard refuses the
                 misspelling without touching the spelling it exists to protect")
            (finally (rf.hicasso.impl.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 9 — the shape is closed too: an `:on-error` nothing can fire is refused
;; ---------------------------------------------------------------------------

(deftest an-on-error-nothing-can-fire-is-refused-and-a-wrapped-intent-still-is-not
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (testing "a BARE intent keyword is what somebody writes who has not yet
                noticed intents are vectors here. It matched neither `vector?`
                nor `fn?`, fell to `report!`'s `:else nil`, and swallowed every
                caught error — so it is refused at the declaration, in `render`,
                BEFORE anything has thrown"
        (let [handle (watched-root! frame-id
                       [boundary {:on-error :hicasso.bdy/noted
                                  :fallback [:p.fb "unused"]}
                        [:p.body "nothing throws on this screen"]])]
          (try
            (is (= :rf.error/hicasso-boundary-bad-on-error (caught-id))
                (str "the shape was refused and named. Escaped: "
                     (pr-str (ex-data @!caught))))
            (is (= :hicasso.bdy/noted (:value (ex-data @!caught)))
                "and the refusal carries the value it was given")
            (is (nil? (query handle ".body"))
                "nothing below the subject rendered")
            (is (nil? (:noted (db frame-id)))
                "and — the point of raising in `render` rather than in `report!`
                 — the refusal arrived with no error caught at all, so it cost
                 no application error path to find")
            (finally (rf.hicasso.impl.mount/release! handle)))))
      (testing "THE NEAR MISS. The same keyword inside brackets is the real
                thing, and reports"
        (let [handle (watched-root! frame-id
                       [boundary {:on-error [:hicasso.bdy/noted "wrapped"]
                                  :fallback [:p.fb "caught"]}
                        [risky {}]])]
          (try
            (is (nil? @!caught)
                (str "nothing escaped. Escaped: " (pr-str (escaped))))
            (rf.hicasso.impl.mount/settle!)
            (is (= ["wrapped"] (:noted (db frame-id)))
                "one bracket apart from the refused form, and it fires")
            (finally (rf.hicasso.impl.mount/release! handle)))))
      (testing "THE OTHER NEAR MISS. An explicit `nil` says no reporting was
                asked for, which is the one value `report!`'s last arm still
                means. A guard that refused it would refuse a legal page"
        (fresh! frame-id)
        (let [handle (watched-root! frame-id
                       [boundary {:on-error nil
                                  :fallback [:p.fb "caught"]}
                        [risky {}]])]
          (try
            (is (nil? @!caught)
                (str "nothing escaped. Escaped: " (pr-str (escaped))))
            (is (some? (query handle ".fb"))
                "the boundary caught, rendered its fallback, and reported
                 nowhere — quietly, because that is what was declared")
            (is (nil? (:noted (db frame-id))))
            (finally (rf.hicasso.impl.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 10 — once per FAILURE, measured where React re-runs renders: StrictMode
;; ---------------------------------------------------------------------------
;;
;; `impl.boundary`'s namespace docstring stakes the guarantee on the
;; lifecycle — `:on-error` fires from `componentDidCatch` and from nowhere
;; else, StrictMode runs the failing render TWICE and `componentDidCatch`
;; still fires ONCE — and cites its witness by name in the fenced bench
;; tree (`arm1_lifecycle_dom_cljs_test/the-boundary-reports-once-under-strictmode`),
;; whose green no longer transfers: the bench twin stopped being
;; digest-pinned to the shipped class when the freeze manifest retired
;; `arm1/boundary.cljs`. This is that witness restated against the shipped
;; door — provenance, not a dependency — with the failed-reset arm folded
;; in: a reset the caller schedules with the cause still in place is a NEW
;; failure, so it reports exactly once more.

(rf.hicasso/defview strict-guarded
  "The retry shape under test, with `:on-error` wired: reads `:attempt` as
  its `:reset-key` so a `:rearm` dispatch retries the child, and records
  every report so ONCE is a count rather than a flag."
  [_]
  [boundary {:fallback  [:p.fb "caught"]
             :reset-key (rf.hicasso.impl.collector/sub [:hicasso.bdy/attempt])
             :on-error  [:hicasso.bdy/record-error]}
   [risky {}]])

(defn- strict-root!
  "`mount/root!`, wrapped in `React.StrictMode`. Written here rather than
  in the shared mount door because StrictMode is this row's variable and
  every other row in this file must keep measuring the ordinary tree —
  the same reasoning `frame-doors-dom-cljs-test`'s W6 records for its copy."
  [container frame-kw hiccup]
  (let [root (react-dom-client/createRoot container)]
    (react-dom/flushSync
      (fn [] (.render root (react/createElement
                             react/StrictMode nil
                             (rf.hicasso.impl.mount/provider frame-kw (rf.hicasso.impl.codec/as-element hiccup))))))
    {:root root :frame frame-kw :container container}))

(deftest the-boundary-reports-once-per-failure-under-strictmode
  ;; What reds this row is reporting from anything React runs more than
  ;; once per failure — the render, in StrictMode, is the visible case.
  ;; The instance-flag alternative is argued away in `impl.boundary`'s
  ;; docstring; this row is the measurement that argument leans on.
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id)
      (let [handle (strict-root! (rf.hicasso.impl.mount/fresh-container!) frame-id [strict-guarded {}])]
        (try
          (rf.hicasso.impl.mount/settle!)
          (is (some? (query handle ".fb")) "the throw was caught")
          (is (= ["the child threw"] (:errors (db frame-id)))
              (str "ONE report, however many times StrictMode invoked the "
                   "render that threw. Got: " (pr-str (:errors (db frame-id)))))

          (testing "and a FAILED reset is a new failure, reporting exactly
                    once more: the retry re-mounts the child, the cause is
                    still in place, it throws again — one more record, not
                    two for StrictMode's re-run and not zero for a boundary
                    that stopped counting"
            (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.bdy/rearm]))
            ;; Twice, for `click!`'s reason: the dispatch commits on the
            ;; first settle; the moved `:reset-key` is then read by
            ;; `componentDidUpdate`, whose own `setState` is a second
            ;; commit, and the re-thrown child's report follows it.
            (rf.hicasso.impl.mount/settle!)
            (rf.hicasso.impl.mount/settle!)
            (is (some? (query handle ".fb")) "it threw again; the fallback stands")
            (is (= ["the child threw" "the child threw"] (:errors (db frame-id)))
                (str "Got: " (pr-str (:errors (db frame-id))))))
          (finally (rf.hicasso.impl.mount/release! handle)))))))
