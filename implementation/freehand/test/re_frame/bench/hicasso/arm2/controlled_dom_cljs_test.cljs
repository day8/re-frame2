(ns re-frame.bench.hicasso.arm2.controlled-dom-cljs-test
  "THE HARD GATE — the controlled-restore obligation on the 100-cell grid
  (rf2-2rtt6.10; witness `:controlled/grid-100`).

  architecture.md: *any PATCH spike that cannot demonstrate the
  controlled-restore on the 100-cell grid witness has failed regardless
  of its clock numbers.* This file is that demonstration, and it is
  written to be readable as evidence rather than as coverage: one
  `deftest` per named assertion in the witness set, each asserting the
  DOM the user would see and the caret they would find.

  ## The typing simulation is the browser's own order

  [[type-into!]] mutates the field **first** and fires `input`
  **second**, because that is what a browser does and it is the whole
  difficulty: by the time any handler runs, the character is already on
  screen. An arm that only writes what its model changed has, at that
  moment, nothing to write — which is why `:unchanged-model-rejection`
  is the assertion that decides this gate.

  ## Same turn means same turn

  Every assertion below runs on the line after `dispatchEvent` returns.
  There is no `act()`, no `flushSync`, no microtask yield and no
  animation frame anywhere in this file. If the arm's door were not
  synchronous, every one of these tests would fail on its first
  assertion rather than flake.

  Runtime: these are DOM claims, so the file carries the
  `-dom-cljs-test` suffix and `:browser-test` runs it for real; under
  `:node-test` each test degrades to a stated skip, which is the posture
  the other `*-dom` suites keep."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.arm2.controlled :as controlled]
            [re-frame.bench.hicasso.arm2.grid-witness :as grid]
            [re-frame.bench.hicasso.arm2.runtime :as rt]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private off-browser
  "no DOM on this runtime — the claim is a caret in a real text field,
  and :browser-test is where it is asked")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

;; ---------------------------------------------------------------------------
;; The page
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- with-grid
  "Mount the 100-cell grid, run `f` with `[container teardown]`, and tear
  it down whatever happens."
  [f]
  (rt/reset-runtime!)
  (let [c (container!)
        teardown (grid/mount! c)]
    (try (f c)
         (finally (teardown) (.remove c) (rt/reset-runtime!)))))

(defn- type-into!
  "Type `text` at the caret, the way a browser does it: the field changes
  first, the `input` event fires second."
  [node text]
  (let [start (.-selectionStart node)
        end   (.-selectionEnd node)
        v     (.-value node)]
    (set! (.-value node) (str (subs v 0 start) text (subs v end)))
    (let [caret (+ start (count text))]
      (.setSelectionRange node caret caret))
    (.dispatchEvent node (js/Event. "input" #js {:bubbles true}))
    nil))

(defn- set-model!
  "Put a value in the model out of band — the setup door, and also the
  `:async-normalisation` door."
  [i v]
  (rt/dispatch! [:grid/set i v]))

(defn- compose! [node kind]
  (.dispatchEvent node (js/Event. kind #js {:bubbles true})))

;; ---------------------------------------------------------------------------
;; :same-turn-echo
;; ---------------------------------------------------------------------------

(deftest the-field-and-the-model-agree-before-the-event-returns
  (testing "a keystroke reaches app-db and comes back to the DOM inside the
           discrete event — no act, no flushSync, no yield"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (grid/cell-input c 7)]
            (.focus n)
            (type-into! n "hello")
            (is (= "hello" (grid/model-value 7)) "the model took the keystroke")
            (is (= "hello" (.-value n)) "and the field shows it, on the next line")
            (is (= [] (controlled/disagreements c))
                "no cell in the grid disagrees with its model")))))))

(deftest the-echo-is-one-body-run-not-one-hundred
  (testing "the per-keystroke budget: a keystroke in cell 7 re-runs cell 7"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (grid/cell-input c 7)]
            (.focus n)
            (rt/reset-stats!)
            (type-into! n "x")
            (is (= 1 (:body-runs @rt/stats))
                (str "one boundary body ran, not " grid/cells))
            (is (= 1 (:dirty-boundaries @rt/stats))
                "and the index named exactly one dirty boundary")))))))

;; ---------------------------------------------------------------------------
;; :mid-string-caret
;; ---------------------------------------------------------------------------

(deftest editing-mid-string-leaves-the-caret-where-the-typing-put-it
  (if-not (browser?)
    (is true off-browser)
    (with-grid
      (fn [c]
        (let [n (grid/cell-input c 7)]
          (set-model! 7 "abcd")
          (.focus n)
          (.setSelectionRange n 2 2)
          (type-into! n "X")
          (is (= "abXcd" (grid/model-value 7)))
          (is (= "abXcd" (.-value n)))
          (is (= [3 3] (controlled/caret n))
              "the caret did not jump to the end — the model agreed, so nothing was written"))))))

;; ---------------------------------------------------------------------------
;; :unchanged-model-rejection — the assertion that decides the gate
;; ---------------------------------------------------------------------------

(deftest a-refused-keystroke-disappears-from-the-field
  (testing "cell 11 takes digits only. The browser has already shown the
           letter; the model does not move; NOTHING re-renders — and the
           field must still come back to the model"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (grid/cell-input c 11)]
            (set-model! 11 "12")
            (.focus n)
            (.setSelectionRange n 2 2)
            (rt/reset-stats!)
            (type-into! n "a")
            (is (= "12" (grid/model-value 11)) "the model refused the letter")
            (is (= "12" (.-value n))
                "and the renderer took it off the screen — this is the obligation")
            (is (= [2 2] (controlled/caret n))
                "the caret sits where it was before the refused character")
            (is (zero? (:dirty-boundaries @rt/stats))
                "and the restore happened with NO boundary re-run at all — a
                 renderer that only writes what changed would have written nothing")))))))

(deftest a-refusal-mid-string-does-not-drag-the-caret-to-the-end
  (if-not (browser?)
    (is true off-browser)
    (with-grid
      (fn [c]
        (let [n (grid/cell-input c 11)]
          (set-model! 11 "12345")
          (.focus n)
          (.setSelectionRange n 2 2)
          (type-into! n "z")
          (is (= "12345" (.-value n)))
          (is (= [2 2] (controlled/caret n))))))))

;; ---------------------------------------------------------------------------
;; Normalisation — the length-preserving and length-changing cases
;; ---------------------------------------------------------------------------

(deftest an-uppercasing-model-keeps-the-caret-mid-string
  (if-not (browser?)
    (is true off-browser)
    (with-grid
      (fn [c]
        (let [n (grid/cell-input c 13)]
          (set-model! 13 "ABCD")
          (.focus n)
          (.setSelectionRange n 2 2)
          (type-into! n "x")
          (is (= "ABXCD" (grid/model-value 13)) "the model normalised the case")
          (is (= "ABXCD" (.-value n)) "and the field followed")
          (is (= [3 3] (controlled/caret n))
              "the caret is still after the character just typed"))))))

(deftest a-grouping-model-keeps-the-caret-at-the-same-distance-from-the-end
  (testing "1,234 + \"5\" becomes 12,345 — one character longer than what the
           user typed, which is exactly the case an absolute caret offset
           gets wrong"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (grid/cell-input c 17)]
            (set-model! 17 "1,234")
            (.focus n)
            (.setSelectionRange n 5 5)
            (type-into! n "5")
            (is (= "12,345" (grid/model-value 17)))
            (is (= "12,345" (.-value n)))
            (is (= [6 6] (controlled/caret n))
                "the caret is still after the 5")))))))

;; ---------------------------------------------------------------------------
;; :selection-preserved
;; ---------------------------------------------------------------------------

(deftest a-converge-does-not-collapse-a-selection
  (testing "both ends ride the distance from the end of the string"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (grid/cell-input c 7)]
            (set-model! 7 "abcdef")
            (.focus n)
            (.setSelectionRange n 1 4)
            (set-model! 7 "Xabcdef")
            (is (= "Xabcdef" (.-value n)))
            (let [[s e] (controlled/caret n)]
              (is (not= s e) "the selection was not collapsed to a caret")
              (is (= [2 5] [s e])
                  "and it still spans the same characters"))))))))

(deftest an-unchanged-model-leaves-the-selection-exactly-alone
  (if-not (browser?)
    (is true off-browser)
    (with-grid
      (fn [c]
        (let [n (grid/cell-input c 7)]
          (set-model! 7 "abcdef")
          (.focus n)
          (.setSelectionRange n 1 4)
          ;; a commit that changes a DIFFERENT cell
          (set-model! 23 "elsewhere")
          (is (= [1 4] (controlled/caret n))
              "nothing was written to this node, so nothing moved"))))))

;; ---------------------------------------------------------------------------
;; :ime-composition-commits-nothing
;; ---------------------------------------------------------------------------

(deftest the-renderer-does-not-write-a-composing-field
  (testing "between compositionstart and compositionend the field belongs to
           the IME; writing value there destroys the composition"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (grid/cell-input c 7)]
            (set-model! 7 "base")
            (.focus n)
            (compose! n "compositionstart")
            (is (true? (controlled/composing? n)) "the fence is up")
            ;; the IME puts provisional text in the field
            (set! (.-value n) "baseあ")
            ;; and an out-of-band correction arrives mid-composition
            (set-model! 7 "OTHER")
            (is (= "baseあ" (.-value n))
                "the renderer must not have touched the composing field")
            (compose! n "compositionend")
            (is (false? (controlled/composing? n)) "the fence is down")
            (is (= "OTHER" (.-value n))
                "and the suppressed convergence was replayed at compositionend")))))))

;; ---------------------------------------------------------------------------
;; :async-normalisation
;; ---------------------------------------------------------------------------

(deftest a-correction-that-arrives-later-still-converges
  (testing "the same restore, driven by a timer instead of a keystroke —
           the shape a server normalisation or a debounced validation takes"
    (if-not (browser?)
      (is true off-browser)
      (async done
        (with-grid
          (fn [c]
            (let [n (grid/cell-input c 17)]
              (set-model! 17 "1234")
              (.focus n)
              (.setSelectionRange n 4 4)
              (js/setTimeout
               (fn []
                 (set-model! 17 (grid/group-digits "1234"))
                 (is (= "1,234" (.-value n)) "the late correction reached the field")
                 (is (= [5 5] (controlled/caret n))
                     "and the caret is still at the same distance from the end")
                 (done))
               0))))))))

;; ---------------------------------------------------------------------------
;; The gate, stated once
;; ---------------------------------------------------------------------------

(deftest the-whole-grid-agrees-with-the-model-after-a-mixed-edit-session
  (testing "the obligation is not one field's behaviour; it is that no field
           in a hundred can be left disagreeing"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (doseq [[i text] [[3 "three"] [11 "99"] [11 "x"] [13 "mixed"] [17 "9876"] [42 "forty two"]]]
            (let [n (grid/cell-input c i)]
              (.focus n)
              (.setSelectionRange n (.-length (.-value n)) (.-length (.-value n)))
              (type-into! n text)))
          (is (= [] (controlled/disagreements c))
              "every controlled cell reads what its model says")
          (is (= grid/cells (rt/boundary-count))
              "and the grid still holds exactly its cells"))))))

(deftest teardown-leaves-no-boundary-and-no-edge
  (if-not (browser?)
    (is true off-browser)
    (do (rt/reset-runtime!)
        (let [c (container!)
              teardown (grid/mount! c)]
          (is (= grid/cells (rt/boundary-count)))
          (teardown)
          (is (zero? (rt/boundary-count)) "no boundary survives the teardown")
          (is (empty? (rt/watched-keys)) "and no subscription value is retained")
          (teardown)
          (is (zero? (rt/boundary-count)) "the teardown is idempotent")
          (.remove c)
          (rt/reset-runtime!)))))
