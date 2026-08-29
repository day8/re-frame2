(ns re-frame.bench.hicasso.shapes.ordinary-dom-cljs-test
  "**SHAPE 1'S WITNESS** — the ~50-element form/list/layout screen renders
  what the census screen renders, and behaves like it (rf2-2rtt6.51).

  Five claims, and the last two are the ones that could not be made by
  looking at the file:

  1. **It is the shape.** Layout, form and list are all present, and the
     page lands in the ~50-element band the charter names.
  2. **The list is the model's.** Five keyed cards, bodies from the model,
     and the author-only delete control on exactly the reader's own two.
  3. **The form works through the browser's own door.** A real,
     cancelable `input` at the rendered `<textarea>` echoes in the
     caller's turn; a real, cancelable `submit` at the rendered
     `<form>` creates a card, clears the draft by explicit revision,
     and **auto-prevents**. The store-write rows beside them witness the
     fan-out from a commit and are named for that — they reach the
     model through `rt/dispatch!`, so a missing `:on-input`, a
     substituted value placeholder or a lost submit prevent leaves every
     one of them green. That was PR #7372's audit finding, and §3b is
     the repair.
  4. **Typing moves the form and nothing else.** The draft key is read by
     the form boundary alone, so a keystroke re-runs one body out of
     seven. A screen that re-rendered its list on every keystroke would
     look identical in the DOM and pass claims 1–3.
  5. **The conditional read costs what the branch costs.** A card showing
     somebody else's comment holds two edges; a card showing your own
     holds three. Asserted as an arithmetic identity over the whole
     screen, so it fails if the read moves back to the top of the body.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against real React
  DOM; under `:node-test` every claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.controlled :as controlled]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.bench.hicasso.shapes.ordinary :as shape]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; Load-bearing, for the reason `arm1_dogfood_dom_cljs_test` records:
     ;; the fixture's default leaves a dynamic-var frame stamp in scope and
     ;; the carried-invariant chain resolves it BEFORE React context.
     :ambient-frame nil
     ;; The map shape, because the teardown claim is `async`: the cell and
     ;; entry reapers are macrotasks, so the residue a React unmount leaves
     ;; is not readable inside one synchronous test body.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::shape-ordinary)

(def ^:private seed
  "Five comments, of which the reader's own are ids 0 and 4 — see
  `model/comment-for`, which makes every fourth `mine?`."
  {:articles 2 :comments 5 :tags 2})

(def ^:private comment-count 5)
(def ^:private mine-count 2)

(defn- skip! [why]
  (is true (str "shape 1's witness needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (m/make-frame! frame-id seed)
  (m/reseed! frame-id seed)
  (shape/reset-runs!)
  frame-id)

(defn- mount! []
  (mount/root! (mount/fresh-container!) frame-id [shape/screen {}]))

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- q* [handle sel] (array-seq (.querySelectorAll (:container handle) sel)))

;; ---------------------------------------------------------------------------
;; The model side of every agreement below — read from the frame, never
;; from the renderer. A DOM reading alone cannot tell "the intent reached
;; app-db" from "the field still shows what the keystroke wrote".
;; ---------------------------------------------------------------------------

(defn- draft [] (get-in (rf/app-db-value frame-id) [:drafts m/comment-draft-key] ""))
(defn- comment-ids [] (:comment-order (rf/app-db-value frame-id)))
(defn- body-of [id] (get-in (rf/app-db-value frame-id) [:comments id :body]))

;; ---------------------------------------------------------------------------
;; 1 — it is the shape the charter names
;; ---------------------------------------------------------------------------

(deftest the-screen-is-layout-form-and-list-at-the-fifty-element-band
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (testing "layout — the census's own container/row/column chrome"
            (is (some? (q handle ".article-page")))
            (is (some? (q handle ".container.page")))
            (is (some? (q handle ".row > .col-xs-12.col-md-8.offset-md-2"))))
          (testing "form — one controlled textarea with a submit"
            (is (some? (q handle "form.comment-form")))
            (is (some? (q handle "textarea.form-control")))
            (is (some? (q handle "button[data-testid=\"comment-submit\"]"))))
          (testing "list — keyed cards"
            (is (= comment-count (count (q* handle ".comments-list > .card")))))
          (testing "and the whole screen is the ~50-element shape, at the size
                   the arithmetic predicts"
            (let [predicted (shape/element-arithmetic comment-count mine-count)
                  n         (lane/element-count (:container handle))]
              (is (= predicted n)
                  (str "chrome " shape/chrome-elements " + form "
                       shape/form-elements " + " (- comment-count mine-count)
                       " cards x " shape/elements-per-card " + " mine-count
                       " own cards x " shape/elements-per-own-card " = " predicted
                       "; the DOM holds " n))
              (is (<= 40 n 60)
                  (str "and that is the charter's ~50-element shape (" n ")"))))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — the list is the model's, including the per-row branch
;; ---------------------------------------------------------------------------

(deftest the-cards-carry-the-models-comments-and-the-author-only-control
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (doseq [i (range comment-count)]
            (let [card (q handle (str "[data-testid=\"comment-card-" i "\"]"))]
              (is (some? card) (str "card " i " rendered"))
              (is (= (:body (m/comment-for i))
                     (.-textContent (.querySelector card "[data-testid=\"comment-body\"]")))
                  (str "card " i " carries the model's body"))))
          (testing "the delete control appears on exactly the reader's own comments"
            (is (= mine-count (count (q* handle ".mod-options")))
                "two of five comments are the signed-in reader's")
            (is (some? (q handle "[data-testid=\"delete-comment-0\"]")))
            (is (nil? (q handle "[data-testid=\"delete-comment-1\"]"))
                "and a comment that is not yours renders no delete control"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3a — the form's fan-out, from a commit
;; ---------------------------------------------------------------------------
;;
;; These three reach the model through `rt/dispatch!` and commit with an
;; explicit `settle!`. That is the right door for a FAN-OUT claim — *when
;; this key moves, this is what the page shows* — and the wrong one for
;; any claim about the handlers the page is written with, because it
;; never runs one. So the first of them is no longer named for the
;; caller's turn (PR #7372's audit): a `settle!` supplied by the test is
;; the test supplying the very commit the name claimed to witness. §3b
;; owns that claim now, through the door the browser uses.

(deftest a-draft-commit-fans-out-to-the-controlled-textarea
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (is (= "" (.-value (q handle "textarea"))))
          ;; The key's own path: the arm's synchronous door, which is what
          ;; the lowered `:on-input` closure eventually calls — but NOT
          ;; the closure, and not the event that reaches it. §3b is where
          ;; that half is witnessed.
          (rt/dispatch! frame-id [:conduit/edit-draft m/comment-draft-key "nice piece"])
          (mount/settle!)
          (is (= "nice piece" (.-value (q handle "textarea")))
              "the echo landed without waiting for a later turn")
          (finally (mount/release! handle)))))))

(deftest submitting-creates-a-card-and-clears-the-draft
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (rt/dispatch! frame-id [:conduit/edit-draft m/comment-draft-key "nice piece"])
          (mount/settle!)
          (rt/dispatch! frame-id [:conduit/post-comment])
          (mount/settle!)
          (is (= (inc comment-count) (count (q* handle ".comments-list > .card"))))
          (is (= "nice piece"
                 (.-textContent (q handle (str "[data-testid=\"comment-card-" comment-count "\"] "
                                               "[data-testid=\"comment-body\"]"))))
              "the new card carries what was typed")
          (is (= "" (.-value (q handle "textarea")))
              "and the draft cleared by explicit caller revision, never by
               value equality")
          (is (some? (q handle (str "[data-testid=\"delete-comment-" comment-count "\"]")))
              "a comment you just posted is your own, so it gets the control")
          (finally (mount/release! handle)))))))

(deftest an-in-flight-post-disables-the-form
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (is (false? (.-disabled (q handle "textarea"))))
          (rt/dispatch! frame-id [:conduit/begin [:post-comment]])
          (mount/settle!)
          (is (true? (.-disabled (q handle "textarea"))) "R-A10: busy discipline")
          (is (true? (.-disabled (q handle "[data-testid=\"comment-submit\"]"))))
          (is (= "Posting…" (.-textContent (q handle "[data-testid=\"comment-submit\"]"))))
          (rt/dispatch! frame-id [:conduit/settle [:post-comment]])
          (mount/settle!)
          (is (false? (.-disabled (q handle "textarea"))))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3b — the same form, through the door the browser uses
;; ---------------------------------------------------------------------------
;;
;; PR #7372's audit, and the repair it asked for. §3a never dispatches a
;; DOM event, so it never runs `:on-input`, never materialises
;; `::h/value`, and never gives `:on-submit`'s auto-prevent anything to
;; prevent. Delete any of the three from `shapes/ordinary` and every row
;; above stays green. These two rows are the ones that go red, and they
;; go red because a real, cancelable event was dispatched at a rendered
;; node and the receipt — model, DOM, body counts, `defaultPrevented`,
;; and what the page reported — was read off what came back.
;;
;; **Cancelable on purpose, and on both.** The browser's own `input` is
;; not cancelable — `arm1/controlled_burst_dom_cljs_test` builds it that
;; way because its claim is about what a keystroke *is*. The claim here
;; is about what the arm *does*: `:on-submit` prevents and `:on-input`
;; must not, and `defaultPrevented` on a non-cancelable event reads
;; `false` however hard anything calls `preventDefault`. A reading that
;; cannot move is not a reading, so both events are cancelable and the
;; pair is asserted together — the same shape `large_template_dom`'s
;; prevent pair takes, on the other half of the grammar (there the head
;; opts IN, here the position's default).
;;
;; **No `settle!` and no `act` in either row**, which is the whole point:
;; a `settle!` is an empty `flushSync`, so a row that calls one is the
;; test supplying the very commit it then reads. Taking the two events
;; the way the browser takes them turned the arm's commit timing into a
;; MEASUREMENT rather than an assumption, and the two halves differ:
;;
;; - **A keystroke echoes inside `dispatchEvent`.** The controlled
;;   converge (`front/controlled/converge!`) runs at the end of the change
;;   handler and its step 1 is a `flushSync`, so the field and the model
;;   agree on the line after the event returns. That is the arm's own
;;   flush, not this file's.
;; - **A submit's page update lands on React's next microtask.** The
;;   model moves synchronously — `rt/dispatch!` is the arm's synchronous
;;   door — but nothing flushes React inside the event, and React 19
;;   schedules sync-lane work in a microtask (`ensureRootIsScheduled` →
;;   `processRootScheduleInMicrotask`) rather than at the end of
;;   `dispatchDiscreteEvent`. Measured here, and it is React's schedule
;;   rather than a defect: the commit is still before paint. So the DOM
;;   half of the submit row is read after a `queueMicrotask`, which lets
;;   React's own task run and forces nothing.
;;
;; The asymmetry is worth reading twice, because it is the authoring
;; answer to "why does a controlled field need anything at all": without
;; the converge, a keystroke's echo would land on that same later
;; microtask, one turn behind the character the user can already see.

(defn- set-native-value!
  "Write `v` through `HTMLTextAreaElement.prototype`'s OWN `value` setter,
  bypassing React's per-instance change tracker — a plain `set!` updates
  the tracker too, after which React discards the `input` event as a
  no-op change and the handler under test never runs."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLTextAreaElement.prototype "value")]
    (.call (.-set d) node v)))

(defn- type-into!
  "Type `text` at the caret the way a browser does it: the field changes
  first, the `input` event fires second. Answers the event, so the caller
  can read `defaultPrevented` off it on the line after."
  [node text]
  (let [start (.-selectionStart node)
        end   (.-selectionEnd node)
        v     (.-value node)]
    (set-native-value! node (str (subs v 0 start) text (subs v end)))
    (let [c (+ start (count text))]
      (.setSelectionRange node c c))
    (let [ev (js/InputEvent. "input" #js {:bubbles    true
                                          :cancelable true
                                          :data       text
                                          :inputType  "insertText"})]
      (.dispatchEvent node ev)
      ev)))

(defn- submit!
  "A real, cancelable `submit` at the form node — the event the browser's
  own form-submission algorithm fires, and whose canceled flag is what
  decides whether the page navigates. Answers the event.

  Dispatched at the `<form>` rather than clicked at the `<button>` on
  purpose: a dispatched click on a `type=\"submit\"` runs the button's
  activation behaviour, so the moment the auto-prevent is the thing being
  broken — which is the whole point of the row — the harness page
  navigates and takes the run with it."
  [node]
  (let [ev (js/SubmitEvent. "submit" #js {:bubbles true :cancelable true})]
    (.dispatchEvent node ev)
    ev))

(defn- error-watch!
  "Start collecting what the page reports; answers the 0-arity that stops
  collecting and returns the messages.

  React does not let a throw from inside a discrete event escape
  `dispatchEvent`: it hands it to `reportError`, which dispatches an
  `error` event on `window`. A `try`/`catch` around the dispatch sees
  NOTHING, so a row that took its green through one would be green over a
  live exception, and only the runner's uncaught-pageerror gate would
  know. The same is true of the commit React schedules on its own
  microtask — which is why this is a stoppable watch rather than a
  wrapper around a single call."
  []
  (let [!errs  (atom [])
        on-err (fn [e] (swap! !errs conj (or (some-> (.-error e) (.-message))
                                             (.-message e))))]
    (.addEventListener js/window "error" on-err)
    (fn [] (.removeEventListener js/window "error" on-err) @!errs)))

(deftest a-real-keystroke-moves-the-model-and-the-field-in-the-callers-turn
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [node (q handle "textarea.form-control")]
            (.focus node)
            (.setSelectionRange node 0 0)
            (is (= "" (draft)) "the model holds no draft before anything is typed")
            (is (= "" (.-value node)) "and the field agrees with it")
            (shape/reset-runs!)
            (let [stop! (error-watch!)
                  ev    (type-into! node "n")
                  errs  (stop!)]
              ;; Every reading below is on the line after `dispatchEvent`
              ;; returned, in the caller's own turn.
              (is (= "n" (draft))
                  "the :on-input intent reached the model, and ::h/value
                   materialised as the value the field carried — a
                   substituted placeholder lands here")
              (is (= "n" (controlled/last-rendered node))
                  "and React COMMITTED it back into this element in the same
                   turn. Read off React's own mirror of the committed value
                   prop rather than off `.value`, which would read \"n\"
                   whether or not anything rendered — the keystroke wrote it")
              (is (= "n" (.-value node)) "the field shows it too")
              (is (false? (.-defaultPrevented ev))
                  "a cancelable input is NOT prevented: the arm prevents at
                   :on-submit and where ::h/prevent is written, nowhere else")
              (is (= [] errs)
                  (str "and nothing threw inside the handler — React would have
                        routed it to reportError and left this row green: "
                       (pr-str errs)))
              (is (= 1 (shape/runs-of :comment-form)) "one keystroke re-ran the form")
              (is (= 0 (shape/runs-of :screen)) "and not the screen")
              (is (= 0 (shape/runs-of :comment-card)) "and no card at all"))
            ;; A SECOND keystroke, because one cannot tell a live
            ;; placeholder from a constant: the value the intent carries has
            ;; to be what the field holds NOW, not what it held when the
            ;; closure was minted.
            (let [ev (type-into! node "i")]
              (is (= "ni" (draft)) "the placeholder read the field again")
              (is (= "ni" (controlled/last-rendered node)))
              (is (= "ni" (.-value node)))
              (is (false? (.-defaultPrevented ev)))))
          (finally (mount/release! handle)))))))

(deftest submitting-the-rendered-form-posts-and-auto-prevents
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)
            node   (q handle "textarea.form-control")
            form   (q handle "form.comment-form")
            finish (fn [] (mount/release! handle) (done))]
        (.focus node)
        (.setSelectionRange node 0 0)
        ;; Composed on purpose: the draft this posts arrived through the
        ;; field's own handler, so a broken `:on-input` reds this row too.
        (type-into! node "nice piece")
        (is (= "nice piece" (draft)) "typed through the field, not written past it")
        (shape/reset-runs!)
        (let [stop! (error-watch!)
              ev    (submit! form)]
          (is (true? (.-defaultPrevented ev))
              ":on-submit takes the census-weighted default and AUTO-PREVENTS
               — read on the line after dispatchEvent, so the form never
               navigates and the author wrote no preventDefault. The one
               reading in this file that no store write can produce")
          (testing "and the model moved in the same turn, through the arm's
                   synchronous door"
            (is (= (inc comment-count) (count (comment-ids))))
            (is (= "nice piece" (body-of comment-count)))
            (is (= "" (draft)) "the draft cleared by explicit revision"))
          ;; React's own commit. `queueMicrotask` is queued BEHIND the one
          ;; `ensureRootIsScheduled` already posted, so this lets React's
          ;; task run and forces nothing — no `settle!`, no `act`.
          (js/queueMicrotask
            (fn []
              (try
                (let [errs (stop!)]
                  (is (= [] errs)
                      (str "nothing threw, in the event OR in the commit it
                            scheduled — React routes both to reportError, and
                            a row without this listener is green over either: "
                           (pr-str errs))))
                (testing "the page committed on React's own microtask"
                  (is (= (inc comment-count) (count (q* handle ".comments-list > .card"))))
                  (is (= "nice piece"
                         (.-textContent (q handle (str "[data-testid=\"comment-card-"
                                                       comment-count "\"] "
                                                       "[data-testid=\"comment-body\"]")))))
                  (is (= "" (.-value node)) "and the field cleared with it")
                  (is (some? (q handle (str "[data-testid=\"delete-comment-"
                                            comment-count "\"]")))
                      "the comment you just posted is your own, so it gets
                       the control"))
                (testing "and the commit woke the boundaries that read what moved"
                  (is (= 1 (shape/runs-of :screen)) "the list boundary reads the order")
                  (is (= 1 (shape/runs-of :comment-form)) "the form reads the draft")
                  (is (= 1 (shape/runs-of :comment-card))
                      "and exactly ONE card body ran — the new row's. The five
                       already on screen bailed out on equal props, so a post
                       is a narrow write and not a list rebuild"))
                (finally (finish))))))))))

;; ---------------------------------------------------------------------------
;; 4 — a keystroke moves one body out of seven
;; ---------------------------------------------------------------------------

(deftest typing-re-runs-the-form-boundary-and-nothing-else
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (is (= comment-count (shape/runs-of :comment-card)) "the mount ran every card once")
          (shape/reset-runs!)
          (rt/dispatch! frame-id [:conduit/edit-draft m/comment-draft-key "n"])
          (mount/settle!)
          (is (= 1 (shape/runs-of :comment-form))
              "the keystroke re-ran the form")
          (is (= 0 (shape/runs-of :screen))
              "and did NOT re-run the screen, which reads the order and not the draft")
          (is (= 0 (shape/runs-of :comment-card))
              "and re-ran no card at all — the claim a DOM comparison cannot make")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 5 — the conditional read costs what the branch costs
;; ---------------------------------------------------------------------------

(deftest a-card-that-cannot-delete-does-not-hold-the-delete-edge
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [edges (:edges (rt/stats))
                ;; screen: [:conduit/comment-ids]                      = 1
                ;; form:   [:conduit/comment-pending?] + [:conduit/draft k] = 2
                ;; card:   [:conduit/comment id] + [:conduit/user]     = 2 each
                ;; card (mine, additionally): [:conduit/delete-pending? id] = 1
                predicted (+ 1 2 (* 2 comment-count) mine-count)]
            (is (= predicted edges)
                (str "a branch not taken contributes no edge: " comment-count
                     " cards, " mine-count " of which can delete, is " predicted
                     " edges — measured " edges))
            (is (< edges (+ 1 2 (* 3 comment-count)))
                "and it is strictly fewer than a declaration that named all
                 three reads up front would have cost"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; Teardown — read while the runtime still holds it
;; ---------------------------------------------------------------------------
;;
;; `mount/release!` resets the runtime, so a residue reading taken after it
;; answers zero however badly teardown went (rf2-2rtt6.48). This one is
;; taken between the unmount and the reset.

(deftest the-screen-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (rt/dispatch! frame-id [:conduit/edit-draft m/comment-draft-key "x"])
        (mount/settle!)
        (is (pos? (:cell-refs (rt/stats)))
            "the mounted screen holds references, so the reading below is a
             reading of something")
        (mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rt/residue))
                             "React's own cleanup released every edge, reference
                              and cached entry")
                         (rt/reset-runtime!)
                         (done))
                       8)))))
