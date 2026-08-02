(ns re-frame.bench.hicasso.shapes.ordinary-dom-cljs-test
  "**SHAPE 1'S WITNESS** — the ~50-element form/list/layout screen renders
  what the census screen renders, and behaves like it (rf2-2rtt6.51).

  Five claims, and the last two are the ones that could not be made by
  looking at the file:

  1. **It is the shape.** Layout, form and list are all present, and the
     page lands in the ~50-element band the charter names.
  2. **The list is the model's.** Five keyed cards, bodies from the model,
     and the author-only delete control on exactly the reader's own two.
  3. **The form works through the synchronous door** — the controlled
     textarea echoes in the caller's turn, and submit creates a card and
     clears the draft by explicit revision.
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
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.bench.hicasso.shapes.ordinary :as shape]
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
;; 3 — the form, through the synchronous door
;; ---------------------------------------------------------------------------

(deftest the-controlled-textarea-echoes-in-the-callers-turn
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (is (= "" (.-value (q handle "textarea"))))
          ;; The intent's own path: the arm's synchronous door, exactly as
          ;; the lowered `:on-input` closure takes it.
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
