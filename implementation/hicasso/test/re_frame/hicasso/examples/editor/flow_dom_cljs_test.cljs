(ns re-frame.hicasso.examples.editor.flow-dom-cljs-test
  "L3 — THE FOUR-FIELD EDITOR, MOUNTED AND TYPED INTO (rf2-hic-078).

  `l2-cljs-test` says what the bodies MEAN. This file puts them on a real
  React root against a real DOM and types into them, because three of
  this application's claims are about what a user sees and cannot be
  read off a tree:

  - a keystroke's echo lands in the turn that typed it;
  - a NORMALISED value echoes as the committed one rather than as what
    was typed;
  - a discard re-baselines a field that some OTHER agent drifted without
    firing an event, which is the whole of what `::h/revision` buys.

  The word *eventless* in that third claim is load-bearing rather than
  descriptive, and it cost this file a row (rf2-5h9k). A keystroke's own
  divergence is converged in the turn that typed it — the second claim
  above IS that mechanism — so a reset row built on typing asserts a
  value that has been on the glass since before the reset and stays green
  with the counter deleted.
  [[what-the-revision-bump-is-actually-load-bearing-FOR]] carries the
  reasoning and the measurement.

  ## The per-keystroke measurement lives here (§13)

  Specification §6 asks that the four-field editor publish the mechanical
  per-keystroke path — state writes, subscription recomputations,
  boundary runs, commit and visible echo — and rf2-hic-045 publishes the
  numbers from this application. Two of those five are counted here:
  `l0-cljs-test/one-keystroke-moves-exactly-one-address` is the write,
  and [[the-per-keystroke-body-count-is-one-and-does-not-grow]] is the
  body count, read off [[re-frame.hicasso.test.mounted/bodies-run]].

  **That door is the kit's, and it used to be an internal's.** This file
  read `impl.collector/body-runs` directly, because nothing else answered
  *how many boundary bodies ran*: `hm/census` counts cells, edges and
  boundaries — residue, not work — and Spec 009's render measures are
  compiled out unless `re-frame.performance/enabled?`, which no PR-lane
  build sets. A test is ALLOWED that reach (the fence in
  `examples.witness-surface-cljs-test` is over APPLICATION namespaces),
  so this was never a breach; it was an application's own witness unable
  to state its budget in the vocabulary of the facade that mounted it.
  Reported by rf2-hic-078, closed by rf2-5mxe, and rf2-hic-045 and
  rf2-hic-071 both read the same door.

  ## Typing is a real DOM event, not a dispatch

  [[type-into!]] writes through `HTMLInputElement.prototype`'s own value
  setter and then fires a real `input` event, which is the only way to
  exercise React's per-instance change tracker and the converge that
  stands in front of it. A `dispatch-sync` of the same intent would prove
  the model and nothing about the glass."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.editor.app :as app]
            [re-frame.hicasso.examples.editor.events :as events]
            [re-frame.hicasso.examples.editor.subs :as subs]
            [re-frame.hicasso.examples.editor.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a mounted React root needs a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; The instruments
;; ---------------------------------------------------------------------------

(defn- mount-editor! []
  (hm/mount! [views/editor {}] {:initial-events app/initial-events}))

(defn- node [m selector] (.querySelector (:container m) selector))

(defn- field-node [m field] (node m (str "[data-field='" (name field) "']")))

(defn- committed-text [m field]
  (.-textContent (node m (str "[data-committed='" (name field) "']"))))

(defn- model [m field]
  (rf/with-frame (:frame m) (deref (rf/subscribe [::subs/field field]))))

(defn- set-native-value!
  "Write `v` through the element prototype's OWN `value` setter, bypassing
  React's per-instance change tracker — the only way a scripted keystroke
  reaches the same code path a real one does."
  [n v]
  (let [proto (if (= "TEXTAREA" (.-tagName n))
                js/HTMLTextAreaElement.prototype
                js/HTMLInputElement.prototype)
        d     (js/Object.getOwnPropertyDescriptor proto "value")]
    (.call (.-set d) n v)))

(defn- type-into!
  "Type `text` at the end of `n`, the way a browser does it: the field
  changes first, the `input` event fires second."
  [n text]
  (set-native-value! n (str (.-value n) text))
  (.dispatchEvent n (js/Event. "input" #js {:bubbles true}))
  nil)

(defn- click! [n]
  (.dispatchEvent n (js/MouseEvent. "click" #js {:bubbles true})))

;; ---------------------------------------------------------------------------
;; The echo
;; ---------------------------------------------------------------------------

(deftest an-accepted-keystroke-echoes-in-the-turn-that-typed-it
  (if-not (browser?)
    (skip! "the accepted keystroke's survival")
    (let [m (mount-editor!)
          n (field-node m :title)]
      (type-into! n "!")
      (hm/settle! m)
      (is (= "Intents are data!" (.-value n))
          "the character the model TOOK is on the glass. This is the
           converge's own trap: writing the change handler's stale closure
           value back here would wipe the character just typed")
      (is (= "Intents are data!" (model m :title)))
      (hm/unmount! m))))

(deftest a-normalised-keystroke-echoes-the-COMMITTED-value
  (if-not (browser?)
    (skip! "the normalised echo")
    (let [m (mount-editor!)
          n (field-node m :slug)]
      (set-native-value! n "")
      (type-into! n "Hello,   World")
      (hm/settle! m)
      (is (= "hello-world" (model m :slug))
          "the model normalised")
      (is (= "hello-world" (.-value n))
          "AND THE FIELD SHOWS THE MODEL, not what was typed — at a length
           the typing did not have. A field that echoed nothing would be
           green on a length-preserving normalisation and red here")
      (hm/unmount! m))))

(deftest the-checkbox-writes-a-boolean-through-a-real-click
  (if-not (browser?)
    (skip! "a real click on a real checkbox")
    (let [m (mount-editor!)
          n (field-node m :published)]
      (is (false? (.-checked n)))
      (click! n)
      (hm/settle! m)
      (is (true? (.-checked n)))
      (is (true? (model m :published?)))
      (hm/unmount! m))))

(deftest the-readout-holds-still-while-you-type-and-moves-when-you-save
  (if-not (browser?)
    (skip! "the committed readout")
    (let [m (mount-editor!)]
      (type-into! (field-node m :title) "!")
      (hm/settle! m)
      (is (= "Intents are data" (committed-text m :title))
          "\"echoes only committed state\", readable off the page: the
           readout subscribes to `::subs/committed` and a keystroke moves
           the draft, so the readout is not notified at all")
      (click! (node m "#save"))
      (hm/settle! m)
      (is (= "Intents are data!" (committed-text m :title)))
      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; The reset — rf2-hic-025's finding 5, measured
;; ---------------------------------------------------------------------------

(deftest a-discard-restores-every-field-and-bumps-the-revision
  (if-not (browser?)
    (skip! "the reset")
    (let [m     (mount-editor!)
          title (field-node m :title)
          slug  (field-node m :slug)
          body  (field-node m :body)
          box   (field-node m :published)]
      (type-into! title "!")
      (type-into! slug "-x")
      (type-into! body " More.")
      (click! box)
      (hm/settle! m)
      (is (= 0 (rf/with-frame (:frame m) (deref (rf/subscribe [::subs/revision]))))
          "nothing has reset yet")

      (click! (node m "#discard"))
      (hm/settle! m)

      (is (= 1 (rf/with-frame (:frame m) (deref (rf/subscribe [::subs/revision]))))
          "ONE, from an `app-db` that had no `:revision` key — the
           `(fnil inc 0)` nothing on the door asks an author to write")
      (is (= "Intents are data" (.-value title)))
      (is (= "intents-are-data" (.-value slug)))
      (is (= false (.-checked box))
          "the checkbox restores WITHOUT a revision, which is why
           `published-box` carries none")
      (is (= (get-in events/seed [:article :body]) (.-value body))
          "and the textarea restores on the same law as the inputs")
      (hm/unmount! m))))

(deftest what-the-revision-bump-is-actually-load-bearing-FOR
  ;; rf2-hic-025's finding 5 says the bump is needed because dropping a
  ;; draft "moves the model back to a value the field is already
  ;; showing". This application is the place to say what can reach that
  ;; state, because it has one field of each policy on one page.
  ;;
  ;; ## What was here before, and why it was replaced rather than fixed
  ;;
  ;; The first row written here (rf2-5h9k) typed into the slug a value
  ;; its policy normalises back to what the model already held, discarded,
  ;; and asserted the field showed the model. It was measured with
  ;; `::events/discard`'s `(update :revision (fnil inc 0))` DELETED, and it
  ;; stayed GREEN — so it was never asserting the counter at all.
  ;;
  ;; The reason is mechanical and is one file away. A keystroke's
  ;; divergence is not drift: `impl.controlled/converge!` runs at the end
  ;; of the change handler, in the same discrete event, and writes the
  ;; model's value onto the glass — which is exactly what
  ;; [[a-normalised-keystroke-echoes-the-COMMITTED-value]] asserts three
  ;; rows above, on this same field with this same setup. By the time that
  ;; row clicked `#discard` the field had shown `"intents-are-data"` since
  ;; the keystroke, and the closing assertion re-read a value nothing had
  ;; disturbed. A row that cannot see its own subject is not patched into
  ;; seeing it; the stimulus was wrong, so the stimulus is what changed.
  ;;
  ;; ## The drift the reset law is about is the drift NO HANDLER RAN FOR
  ;;
  ;; A password manager, an autofill, a browser extension assigning
  ;; `.value`. No change event, so no handler, so the in-turn converge
  ;; never sees it — and React's own end-of-event restore is not on this
  ;; path either. The only thing that repairs it is React's per-commit
  ;; controlled re-assert, and that runs when the BOUNDARY RE-RENDERS.
  ;; (`impl.codec/revision-key` states the whole delivery in those terms.)
  ;;
  ;; Which makes this a claim about the read set of ONE BOUNDARY rather
  ;; than about the form. `views/text-field` reads exactly two addresses,
  ;; its own field and the counter, so a discard that leaves this field's
  ;; value where it is can notify it through `::subs/revision` and through
  ;; nothing else. The form is therefore dirtied from ANOTHER field: that
  ;; makes `#discard` live and gives the discard real work — `:draft
  ;; :title` moves and `::subs/dirty?` moves — while neither address is
  ;; read by the boundary under measurement. `::subs/dirty?` feeds
  ;; `views/buttons`, which is a separate boundary for the separate reason
  ;; [[the-per-keystroke-body-count-is-one-and-does-not-grow]] measures,
  ;; and that separation is what makes this row reachable at all.
  (if-not (browser?)
    (skip! "the drift case")
    (let [m     (mount-editor!)
          title (field-node m :title)
          slug  (field-node m :slug)]
      (type-into! title "!")
      (hm/settle! m)
      (is (true? (rf/with-frame (:frame m) (deref (rf/subscribe [::subs/dirty?]))))
          "dirtied from the OTHER field, so `#discard` is live and the
           slug's own address is not among what the discard will move")

      ;; The drift, and it is EVENTLESS on purpose.
      (set-native-value! slug "typed-by-nobody")
      (is (= "typed-by-nobody" (.-value slug))
          "the glass moved")
      (is (= "intents-are-data" (model m :slug))
          "and the model did not. That gap is the drift, and it is the
           one kind nothing in this runtime closes on its own: no change
           event fired, so no handler ran, so the converge that repairs a
           keystroke's divergence never saw it")

      (click! (node m "#discard"))
      (hm/settle! m)
      (is (= "intents-are-data" (.-value slug))
          "re-baselined, and the counter is the only thing that could have
           done it. `:draft :slug` never moved, so of this boundary's two
           reads the discard touched only `::subs/revision`; the bump
           re-ran the body, the re-run re-committed the element, and the
           commit re-asserted the model over a draft React's own value
           diff had nothing to say about. Delete the `(update :revision
           (fnil inc 0))` from `::events/discard` and THIS row reds, with
           the field still showing `typed-by-nobody` — measured, and that
           is why the counter cannot be dropped as bookkeeping nobody can
           see a reason for")
      (hm/unmount! m))))

(h/defview bad-revision-box
  "A DELIBERATE DEFECT, and the only one in this bead's two applications.

  It lives in a test namespace and not in either witness, because a
  witness models proper re-frame2 and a control has to be able to make a
  gate red. `published-box`'s docstring claims the door refuses a
  revision here; this is the view that asks it to."
  [_]
  [:input {:type "checkbox" :checked false ::h/revision 1}])

(deftest a-revision-on-a-value-less-checkbox-is-refused-at-the-element
  ;; The L3 half of `l2-cljs-test`'s stated limit: `ht/controlled?` builds
  ;; props with `convert-props`, which never carries the trigger, so no L1
  ;; door can witness this refusal. A real element can.
  ;;
  ;; The view below is a deliberate defect and lives HERE rather than in
  ;; the application, because a witness app models proper re-frame2 and an
  ;; anti-pattern belongs in the control that has to be able to go red.
  (if-not (browser?)
    (skip! "an element is only created on a real root")
    (let [data (try (hm/mount! [bad-revision-box {}] {}) nil
                    (catch :default e (ex-data e)))]
      (is (= {:rf.error/id :rf.error/hicasso-revision-not-controlled
              :recovery    :put-the-revision-on-a-controlled-input-or-textarea}
             (select-keys data [:rf.error/id :recovery]))
          "asserted as an identity and not as `thrown?`: in a layered
           runtime there is nearly always a second defence, and a bare
           throw assertion buys its silence rather than the first one's
           conduct"))))

;; ---------------------------------------------------------------------------
;; The per-keystroke body count — §13's second number
;; ---------------------------------------------------------------------------

(deftest the-per-keystroke-body-count-is-one-and-does-not-grow
  (if-not (browser?)
    (skip! "a body count needs bodies")
    (let [m (mount-editor!)
          n (field-node m :title)]
      ;; The FIRST keystroke of an editing session moves `::subs/dirty?`
      ;; too, so the button row runs with the field. That is the ordinary
      ;; second body run and it is named rather than tuned away.
      (let [first-burst (hm/bodies-run #(do (type-into! n "a") (hm/settle! m)))]
        (is (= 2 first-burst)
            "the field and the button row — `::subs/dirty?` goes false to
             true exactly once per session"))

      (testing "and every keystroke after it runs ONE body"
        (doseq [ch ["b" "c" "d"]]
          (is (= 1 (hm/bodies-run #(do (type-into! n ch) (hm/settle! m))))
              (str "typing " ch " ran a body that was not the title
                    field's. Four controls are on this page and three of
                    them read addresses this keystroke did not move; the
                    form body reads nothing at all, so it is not notified
                    and does not props-compare its six children"))))

      (testing "including the field that normalises — a policy is not a cost"
        (let [slug (field-node m :slug)]
          (is (= 1 (hm/bodies-run #(do (type-into! slug "z") (hm/settle! m)))))))

      (testing "the count is a property of the TOPOLOGY, not of the form's size"
        ;; The editor has four controls. `grid.scaling-dom-cljs-test`
        ;; runs the same measurement over a hundred, and gets the same
        ;; answer — which is the sentence specification §6 asks for.
        (is (= 1 (hm/bodies-run #(do (type-into! n "e") (hm/settle! m))))))

      (hm/unmount! m))))
