(ns re-frame.bench.hicasso.arm2.controlled
  "THE CONTROLLED-RESTORE OBLIGATION — Arm 2's hard gate (rf2-2rtt6.10).

  architecture.md states it without hedging: *off React's discrete-event
  path there is no free end-of-event value restore; the renderer must
  converge controlled `value`/`checked` against the live node after every
  controlled dispatch, caret preserved, composition fenced. Any PATCH
  spike that cannot demonstrate the controlled-restore on the 100-cell
  grid witness has failed regardless of its clock numbers.*

  This namespace is that obligation, discharged. It is small on purpose —
  the obligation is not large, it is *load-bearing*, and the reason arms
  fail it is that the four rules below interact.

  ## What React actually does, and what therefore has to be replaced

  React's controlled input is not a diffing trick. At the end of a
  **discrete** event React re-asserts the value it last rendered onto the
  DOM node, whether or not its own render produced a change. That is what
  makes `onChange` able to *reject* a keystroke: the browser has already
  put the character in the field, the model refuses it, React's restore
  puts the field back. A renderer that only writes what changed has no
  such moment, and its rejected keystroke stays on screen — the field and
  the model disagree, permanently, and every later edit compounds it.

  Arm 2 has no React on this path, so it owns the moment. It is
  [[converge-focused!]], run by the runtime at the end of every commit.

  ## The four rules

  1. **Write only on disagreement.** `(set! (.-value node) v)` is not a
     no-op when `v` is what is already there: per HTML, setting `value`
     moves the text entry cursor to the end. Every write is guarded by an
     inequality, which is what makes an *unchanged* model free.

  2. **Preserve the caret from the END of the string.** When a write is
     genuinely needed — normalisation, rejection, an async correction —
     the caret is restored at the same distance from the end, not the
     same absolute offset. Distance-from-end is the measure that survives
     the cases that actually happen: uppercasing (unchanged), digit
     grouping `1234 → 1,234` (caret stays after the same digit), and
     rejection (the refused character disappears and the caret lands
     where it was before it). Absolute offset gets grouping wrong every
     time.

  3. **Fence composition.** Between `compositionstart` and
     `compositionend` the field belongs to the IME, and writing `value`
     destroys the in-flight composition — on some IMEs it commits
     garbage, on others it cancels silently. So convergence is suppressed
     while composing and **replayed at `compositionend`**, which is the
     part that is easy to leave out and impossible to notice on a Latin
     keyboard. The front half's key-map gate already refuses to
     *dispatch* mid-composition (`isComposing` / keyCode 229); this is
     the other half — refusing to *write*.

  4. **Restore selections, not just carets.** A range selection has two
     ends; both are restored from the end of the string, so a converge
     that fires while text is selected does not collapse the selection.

  ## Where the model value lives

  On the node, as one own property (`__hicassoValue` / `__hicassoChecked`),
  written whenever the renderer sets a controlled prop. The convergence
  needs the model value at a moment when no hiccup is in hand — the
  commit has finished patching, and the only node that can disagree is
  the focused one. Reaching back into the boundary tree for it would mean
  retaining a map from node to hiccup; one expando per controlled node is
  smaller and cannot go stale, because it is written by the same code
  path that writes the DOM.

  Resets are **by explicit caller revision, never value equality**
  (HD-019, keeping the predecessor's ruled reset law). Nothing here
  clears a field because a value looked empty."
  (:require [clojure.string :as str]))

(def ^:private value-key    "__hicassoValue")
(def ^:private checked-key  "__hicassoChecked")
(def ^:private composing-key "__hicassoComposing")
(def ^:private fenced-key   "__hicassoFenced")

;; ---------------------------------------------------------------------------
;; Composition fencing
;; ---------------------------------------------------------------------------

(defn composing?
  "Is this node inside an IME composition the renderer must not disturb?"
  [node]
  (true? (unchecked-get node composing-key)))

(declare converge!)

(defn- fence!
  "Install the composition listeners once per controlled node. The
  `compositionend` handler replays the convergence that was suppressed
  while the IME held the field."
  [node]
  (when-not (true? (unchecked-get node fenced-key))
    (unchecked-set node fenced-key true)
    (.addEventListener node "compositionstart" (fn [_] (unchecked-set node composing-key true)))
    (.addEventListener node "compositionend"
                       (fn [_]
                         (unchecked-set node composing-key false)
                         (converge! node)))
    nil))

;; ---------------------------------------------------------------------------
;; Caret arithmetic
;; ---------------------------------------------------------------------------

(defn- caret-capable?
  "Does this element expose a text entry cursor? `selectionStart` throws
  on `number`, `email`, `color` and friends in several browsers, so the
  question is asked by reading it defensively rather than by keeping a
  roster of input types."
  [node]
  (try (some? (.-selectionStart node)) (catch :default _ false)))

(defn- focused? [node]
  (and (some? js/document) (identical? node (.-activeElement js/document))))

(defn- restore-caret!
  "Put the caret (or the selection) back at the same distance from the
  end of the string it held before the write."
  [node start-from-end end-from-end]
  (let [n (.-length (.-value node))
        s (max 0 (- n start-from-end))
        e (max 0 (- n end-from-end))]
    (try (.setSelectionRange node (min s e) (max s e))
         (catch :default _ nil))
    nil))

;; ---------------------------------------------------------------------------
;; The writes
;; ---------------------------------------------------------------------------

(defn write-value!
  "Set a controlled `value`, obeying all four rules. Called by the
  emitter for every `:value` prop — mount and patch alike — so there is
  exactly one code path that ever writes a controlled value."
  [node v]
  (let [v (if (nil? v) "" (str v))]
    (unchecked-set node value-key v)
    (fence! node)
    (when-not (composing? node)
      (let [live (.-value node)]
        (when-not (= live v)
          (if (and (focused? node) (caret-capable? node))
            (let [len (.-length live)
                  s   (- len (or (.-selectionStart node) len))
                  e   (- len (or (.-selectionEnd node) len))]
              (set! (.-value node) v)
              (restore-caret! node s e))
            (set! (.-value node) v))))))
  nil)

(defn write-checked!
  "Set a controlled `checked`. No caret, but the same
  write-only-on-disagreement rule: assigning `checked` fires no event but
  does reset an `indeterminate` flag, so an unconditional write is not
  free."
  [node v]
  (let [b (boolean v)]
    (unchecked-set node checked-key b)
    (when-not (= (.-checked node) b) (set! (.-checked node) b)))
  nil)

(defn controlled?
  "Has the renderer ever written a controlled prop to this node?"
  [node]
  (or (some? (unchecked-get node value-key))
      (some? (unchecked-get node checked-key))))

;; ---------------------------------------------------------------------------
;; The end-of-event restore
;; ---------------------------------------------------------------------------

(defn converge!
  "Re-assert the model value on one node. This is the moment React
  supplies for free and Arm 2 has to name: after the commit, the field
  must read what the model says, whatever the browser put there."
  [node]
  (when (and (some? node) (not (composing? node)))
    (when-some [v (unchecked-get node value-key)]
      (let [live (.-value node)]
        (when-not (= live v)
          (if (and (focused? node) (caret-capable? node))
            (let [len (.-length live)
                  s   (- len (or (.-selectionStart node) len))
                  e   (- len (or (.-selectionEnd node) len))]
              (set! (.-value node) v)
              (restore-caret! node s e))
            (set! (.-value node) v)))))
    (when-some [c (unchecked-get node checked-key)]
      (when-not (= (.-checked node) c) (set! (.-checked node) c))))
  nil)

(defn converge-focused!
  "The end-of-commit restore. **Only the focused element can disagree**
  with the model: every other controlled node was last written by the
  renderer and nothing but user input moves a field. So the whole
  obligation costs one `document.activeElement` read and — on the one
  node that can have diverged — one comparison.

  That is the cost claim the 100-cell grid witness exists to check: a
  keystroke in cell 7 converges cell 7, and does not walk the other 99."
  []
  (when (some? js/document)
    (let [node (.-activeElement js/document)]
      (when (and (some? node) (controlled? node)) (converge! node))))
  nil)

;; ---------------------------------------------------------------------------
;; Observation — for the witnesses
;; ---------------------------------------------------------------------------

(defn model-value
  "The value the renderer last told this node to hold."
  [node]
  (unchecked-get node value-key))

(defn agreement
  "`{:model … :live … :agree? …}` for one node — the witness's readable
  form of \"the field and the model agree\"."
  [node]
  (let [m (unchecked-get node value-key)
        l (.-value node)]
    {:model m :live l :agree? (= m l)}))

(defn disagreements
  "Every controlled descendant of `root` whose live value differs from
  its model value. The grid witness asserts this is empty after every
  keystroke; a non-empty result names the cells."
  [root]
  (let [out (volatile! [])]
    (letfn [(walk [n]
              (when (and (identical? 1 (.-nodeType n)) (controlled? n))
                (let [a (agreement n)]
                  (when-not (:agree? a) (vswap! out conj (assoc a :id (.getAttribute n "id"))))))
              (loop [c (.-firstChild n)]
                (when c (walk c) (recur (.-nextSibling c)))))]
      (walk root))
    @out))

(defn caret
  "`[start end]` for a caret-capable node, or nil."
  [node]
  (when (caret-capable? node)
    [(.-selectionStart node) (.-selectionEnd node)]))

(defn describe
  "One-line readable state of a controlled node, for failure output."
  [node]
  (str/join " " [(str "value=" (pr-str (.-value node)))
                 (str "model=" (pr-str (unchecked-get node value-key)))
                 (str "caret=" (pr-str (caret node)))
                 (str "composing=" (composing? node))]))
