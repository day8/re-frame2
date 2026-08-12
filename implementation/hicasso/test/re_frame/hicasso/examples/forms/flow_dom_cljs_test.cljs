(ns re-frame.hicasso.examples.forms.flow-dom-cljs-test
  "L3/L4 — THE RECIPES ON A REAL REACT ROOT OVER A REAL DOM (rf2-hic-051).

  The claims the node lane cannot decide. A blur ordering, a node's
  identity across a commit, where the caret and the focus are, and — the
  row this file exists for — whether `::h/revision` is doing any work.

  ## The revision row is an EXPERIMENT, not a regression

  [[the-revision-is-what-repairs-a-drift-a-session-end-leaves-behind]]
  is written so it can fail. In an application whose draft lives in
  `app-db`, ending a session usually moves the value the field reads, and
  a moved value re-renders the boundary and re-asserts the model for
  free — a revision would be decoration. The one arrangement where it is
  not is a session whose draft is EQUAL to the committed value: ending
  that session moves nothing the field reads except the revision. Add a
  foreign write React never saw — an autofill, an extension, a refused
  keystroke, a composition the carve-out held off — and the revision is
  the only thing left that can repair the box.

  So the row builds exactly that arrangement. If it were green with the
  bump removed, the recipe would be carrying a prop for nothing and the
  honest deliverable would be to say so.

  ## What separates the field's reads from the hint's

  `views/subject-hint` is its own boundary for that same reason. Left
  inside the field's body, `::subs/editing?` changing at every session
  end would re-render the field, re-commit the element and re-assert the
  model — and the revision would test green while doing nothing.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`),
  and each row degrades there to a STATED skip rather than a false
  green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.forms.events :as events]
            [re-frame.hicasso.examples.forms.subs :as subs]
            [re-frame.hicasso.examples.forms.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.http.managed]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted form needs a real React DOM — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The MAP shape, because every row is `async`: `cljs.test` refuses an
     ;; async test under a fn-form fixture and aborts the namespace.
     :async?        true
     :init-fn       (fn []
                      ;; React's `act` queue is not the browser's scheduler,
                      ;; and every reading here is taken outside it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; The reset restores the registrar to a baseline and the
                      ;; resources artefact clears the mutation kind, so the
                      ;; ns-load registration is not still there when a row
                      ;; runs. `events/register-save!` says what that costs
                      ;; when it is forgotten (rf2-06lp).
                      (events/register-save!))}))

(def ^:private ticket 7)
(def ^:private committed "Login page hangs on submit")

;; ---------------------------------------------------------------------------
;; Reading and driving the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))

(defn- subject-node
  ([m] (subject-node m ticket))
  ([m ikey] (node m (str "#ticket-" ikey "-subject"))))

(defn- type-into!
  "Type `v` into `n` — a foreign write followed by a real `input` event,
  which is what a keystroke is from React's side.

  Written through the PROTOTYPE's own `value` setter because React
  patches the instance setter to maintain its change tracker: a plain
  `set!` updates the tracker too, after which React reads the node as
  already agreeing and the `input` below reaches no handler."
  [m n v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)
    (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
    (hm/settle! m)))

(defn- drift!
  "Move the field's value WITHOUT telling React — the foreign write an
  autofill, a password manager or a non-React script performs. The same
  prototype setter as [[type-into!]] and deliberately NO `input` event:
  nothing is dispatched, so no handler runs and the model never learns."
  [n v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)
    nil))

(defn- press!
  "A real `keydown` carrying `key`. React's synthetic `onKeyDown` is
  driven by exactly this event, so the key map's own `.key` lookup is the
  real one."
  [m n key]
  (.dispatchEvent n (js/KeyboardEvent. "keydown" #js {:key key :bubbles true}))
  (hm/settle! m))

(defn- blur!
  "Take focus off `n` for real, which is what fires React's `onBlur`."
  [m n]
  (.blur n)
  (hm/settle! m))

(defn- read-sub [m query-v] (rf/subscribe-once query-v {:frame (:frame m)}))

(defn- mount-screen!
  ([] (mount-screen! [views/screen {:ikey ticket}]))
  ([form] (hm/mount! form {:initial-events [[::events/seed]]})))

(defn- finish
  "Tear down, assert this mount left nothing behind, and end the row."
  [m done]
  (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered field, driven
;; ---------------------------------------------------------------------------

(deftest a-real-commit-moves-the-subject-and-keeps-the-node
  (async done
    (if-not (browser?)
      (do (skip! "Enter on a real field") (done))
      (let [m (mount-screen!)
            n (subject-node m)]
        (is (= committed (.-value n)) "populated before anybody types")
        (.focus n)
        (type-into! m n "Login times out")
        (is (= "Login times out" (.-value n)))
        (is (= committed (get-in (rf/app-db-value (:frame m)) [:ticket :subject]))
            "and the committed subject has NOT moved — the draft is in front
             of it, which is what buffered means")
        (press! m n "Enter")
        (is (= "Login times out" (get-in (rf/app-db-value (:frame m)) [:ticket :subject])))
        (is (identical? n (subject-node m))
            "the SAME DOM node. A reset spelled as a remount would pass a
             value assertion and lose the focus, the caret and any IME
             composition with it")
        (is (identical? n js/document.activeElement)
            "and focus is still in the box")
        (finish m done)))))

(deftest escape-reverts-a-still-mounted-field-and-the-late-blur-commits-nothing
  ;; The bead's cancel-unmounts-then-blur, in the harder arrangement: this
  ;; field STAYS on the page after the cancel, so the blur that follows
  ;; reaches a live handler rather than a torn-down one.
  (async done
    (if-not (browser?)
      (do (skip! "Escape then blur, in order, on a live field") (done))
      (let [m (mount-screen!)
            n (subject-node m)]
        (.focus n)
        (type-into! m n "half-typed nonsense")
        (is (some? (node m ".subject-hint")) "the session is open")
        (press! m n "Escape")
        (is (= committed (.-value n)) "the box shows the committed subject again")
        (is (nil? (node m ".subject-hint")) "and the session is over")
        (blur! m n)
        (is (= committed (.-value n)))
        (is (= committed (get-in (rf/app-db-value (:frame m)) [:ticket :subject]))
            "the trailing blur found no session and committed nothing —
             cancel beats the late blur through the model, not through
             ordering")
        (is (= 1 (read-sub m [::subs/subject-revision ticket]))
            "and it moved the revision exactly once: the cancel's. A blur
             that ran would have moved it a second time, which is the
             narrower thing this line is watching for")
        (finish m done)))))

(deftest a-blank-commit-puts-the-committed-subject-back-in-the-box
  (async done
    (if-not (browser?)
      (do (skip! "a refusal, painted") (done))
      (let [m (mount-screen!)
            n (subject-node m)]
        (.focus n)
        (type-into! m n "   ")
        (is (= "   " (.-value n)) "the field echoes what was typed, verbatim")
        (press! m n "Enter")
        (is (= committed (.-value n))
            "refused — and VISIBLY so. A refusal the user cannot see is the
             defect; leaving the blank text sitting there would look
             accepted")
        (is (= committed (get-in (rf/app-db-value (:frame m)) [:ticket :subject])))
        (finish m done)))))

(deftest the-revision-is-what-repairs-a-drift-a-session-end-leaves-behind
  ;; THE EXPERIMENT. See the namespace docstring: this is the one
  ;; arrangement in which ending a session moves nothing the field reads
  ;; except `::h/revision`.
  (async done
    (if-not (browser?)
      (do (skip! "a foreign write and a per-commit re-assert") (done))
      (let [m (mount-screen!)
            n (subject-node m)]
        (.focus n)
        ;; A session whose draft ends EXACTLY at the committed subject.
        ;; Two keystrokes and not one: React's own change tracker drops an
        ;; `input` event that carries the value the node already held, so
        ;; typing `committed` straight into a box showing `committed` would
        ;; reach no handler and open no session at all.
        (type-into! m n "Login page hangs on submi")
        (type-into! m n committed)
        (is (= committed (read-sub m [::subs/subject-shown ticket]))
            "so the value the field reads is what it was before the session
             opened, and ending the session will not move it")
        (is (true? (read-sub m [::subs/editing? ticket])))
        ;; The drift: a write React never saw and no handler ran for.
        (drift! n "autofilled@example.com")
        (is (= "autofilled@example.com" (.-value n)))
        (press! m n "Escape")
        (is (= committed (.-value n))
            "REPAIRED. Nothing the field reads changed but the revision, so
             this line is the revision's whole effect. Delete the bump from
             `events/end-session` and it reds with the autofill still in the
             box")
        (finish m done)))))

(deftest a-draft-survives-the-field-leaving-the-page-and-coming-back
  ;; TRAP: re-minted ephemeral state. A draft held in a render closure or a
  ;; component's own state is destroyed by exactly this.
  (async done
    (if-not (browser?)
      (do (skip! "a real unmount and a real remount") (done))
      (let [m (mount-screen!)]
        (.focus (subject-node m))
        (type-into! m (subject-node m) "half typed")
        (hm/render! m [views/details-form {}])
        (is (nil? (subject-node m)) "the field is off the page")
        (hm/render! m [views/screen {:ikey ticket}])
        (is (= "half typed" (.-value (subject-node m)))
            "and it comes back holding the draft — which lives at an address
             in app-db, not in a fiber that was just thrown away")
        (is (= committed (get-in (rf/app-db-value (:frame m)) [:ticket :subject]))
            "while the committed subject never moved: leaving the page is
             neither a commit nor a cancel")
        (finish m done)))))

(h/defview two-tickets
  "Two of the same field on one page, on different instance keys — the
  arrangement `h/reg-state` exists for. The seed holds one ticket, so
  both fields start from the same committed text, which makes a
  collision harder to miss rather than easier."
  [_]
  [:div.two-tickets
   [views/subject-field {:ikey 7}]
   [views/subject-field {:ikey 9}]])

(deftest two-tickets-edit-independently-on-one-page
  (async done
    (if-not (browser?)
      (do (skip! "two live fields and two ids") (done))
      (let [m  (mount-screen! [two-tickets {}])
            n7 (subject-node m 7)
            n9 (subject-node m 9)]
        (is (and (some? n7) (some? n9)) "two fields, two ids")
        (.focus n7)
        (type-into! m n7 "only the seventh")
        (is (= "only the seventh" (.-value n7)))
        (is (= committed (.-value n9))
            "a hand-picked `[:ui :subject-draft]` path would have moved both,
             and nothing on screen would have said so")
        (press! m n7 "Escape")
        (is (= 0 (read-sub m [::subs/subject-revision 9]))
            "and the cancel moved one revision, not two — a reset is per
             instance")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; Recipes 2 and 3 — the gate and the write, on the page
;; ---------------------------------------------------------------------------

(def ^:private !requests (atom []))

(defn- capture-transport! []
  (reset! !requests [])
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! !requests conj args) nil)))

(deftest a-refused-submission-reveals-the-problem-it-has-and-no-other
  (async done
    (if-not (browser?)
      (do (skip! "a real form submission") (done))
      (let [_ (capture-transport!)
            m (mount-screen!)]
        (is (nil? (node m ".field-problem")) "a blank form reports nothing")
        (.requestSubmit (node m "form.details"))
        (hm/settle! m)
        (is (= 1 (.-length (.querySelectorAll (:container m) ".field-problem")))
            "one problem — the blank assignee. The note is empty and empty is
             legal, so a gate that revealed both would be reporting a
             problem that does not exist")
        ;; `[]` here would be a false green on its own: `::submit`'s write
        ;; would have gone out on the ASYNC dispatch queue, and reading the
        ;; recorder on the next line reads it before that turn. So a marker
        ;; goes through the same queue and the assertion waits behind it —
        ;; anything the submission enqueued has run by then.
        (rf/dispatch [::events/edit :notes "marker"] {:frame (:frame m)})
        (-> (test-support/poll-until
              #(= "marker" (read-sub m [::subs/field :notes]))
              {:label "the dispatch queue to reach a marker enqueued after the submit"})
            (.then (fn [_]
                     (is (= [] @!requests)
                         "and nothing was asked of the server")
                     (finish m done))))))))

(deftest a-valid-submission-goes-busy-from-the-writes-own-status
  (async done
    (if-not (browser?)
      (do (skip! "a real button and a real disabled attribute") (done))
      (let [_      (capture-transport!)
            m      (mount-screen!)
            button (node m "button.save")]
        (is (false? (.-disabled button))
            "operable while invalid — which is what makes the submit-attempt
             gate reachable at all")
        (is (= "true" (.getAttribute button "aria-disabled")))
        (type-into! m (node m "#ticket-assignee") "ada")
        (is (= "false" (.getAttribute button "aria-disabled")))
        (.requestSubmit (node m "form.details"))
        ;; The write leaves on the async dispatch queue — an intent's own
        ;; dispatch is synchronous, but the `:fx [[:dispatch …]]` a handler
        ;; returns is a turn of its own. `hm/settle!` is a React flush and
        ;; cannot help, because nothing is scheduled in React yet.
        (-> (test-support/poll-until #(seq @!requests)
                                     {:label "the write to reach the transport"})
            (.then
              (fn [_]
                (hm/settle! m)
                (is (= 1 (count @!requests)))
                (is (true? (.-disabled button)) "busy — from the instance")
                (is (true? (.-disabled (node m "#ticket-assignee")))
                    "and so are the fields, off the same read")
                (hm/dispatch-and-settle! m (conj (:on-success (last @!requests))
                                                 {:status :ok :value {:ok true}}))
                (is (false? (.-disabled button)))
                (is (= "" (.-value (node m "#ticket-assignee")))
                    "the reply landed at the named event and blanked the form —
                     there is no completion callback anywhere in this
                     application")
                (finish m done))))))))
