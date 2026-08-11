(ns re-frame.hicasso.examples.editor.l0-cljs-test
  "L0 — THE EDITOR'S MODEL, WITH NO VIEW SUBSTRATE ANYWHERE (rf2-hic-078).

  `re-frame.hicasso.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: an event handler is a function of `db` and
  an event vector, a subscription is a function of its inputs, and a
  state transition is the pair. So this file requires neither
  `re-frame.hicasso` nor either half of the kit, and
  `examples.witness-surface-cljs-test` proves the same of the namespaces
  it tests.

  Frame scope is the programmer's ordinary bracket — `rf/with-new-frame`.

  ## The two halves, kept apart

  The PURE rows call `events/slugify` with a string, because a
  calculation over data is best tested as one. The TRANSITION rows go
  through a real frame and a real `dispatch-sync`, because a handler is
  only a handler once the registrar has it and the interceptor chain has
  run — a test that called the handler function by hand would be green
  for a registration that never happened.

  ## One row here is about the SHAPE of a write, not its value

  [[one-keystroke-moves-exactly-one-address]] is the L0 half of the
  per-keystroke budget. `flow-dom-cljs-test` counts the bodies that run;
  this counts the addresses that move, and the two together are the walk
  `docs/design/hicasso/draft-guide/18-performance.md` §Trace one
  controlled keystroke describes. It is asserted here rather than there
  because it is a property of the model and needs no React to be true."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.editor.app :as app]
            [re-frame.hicasso.examples.editor.events :as events]
            [re-frame.hicasso.examples.editor.subs :as subs]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- with-app
  "Run `f` against a fresh frame seeded exactly as the application seeds
  itself — `app/initial-events`, not a hand-written `:rf/set-db`, so
  these rows exercise the boot the application actually has."
  [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events app/initial-events})]
    (f frame)))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- type! [frame field text]
  (rf/dispatch-sync [::events/edit field text] {:frame frame}))

;; ---------------------------------------------------------------------------
;; Pure — the policies
;; ---------------------------------------------------------------------------

(deftest slugify-lower-cases-and-collapses
  (is (= "hello-world" (events/slugify "Hello, World")))
  (is (= "a-b" (events/slugify "A   B"))
      "a RUN of non-alphanumerics collapses to one dash, so this
       normalisation changes the length — which is what makes the slug
       field's echo row distinguishable from a field that echoed nothing")
  (is (= "" (events/slugify "")))
  (is (= "-" (events/slugify "!!!"))
      "recorded rather than defended: a leading or trailing dash is what
       this policy produces mid-typing, and trimming it would make the
       field fight the user's next keystroke"))

(deftest every-editable-field-has-a-policy
  ;; `editable-fields` is derived from `field-policy`, so a field added
  ;; without a policy would answer nil and take nothing. This is the row
  ;; that says so rather than leaving it to be discovered on screen.
  (is (= (set events/editable-fields) (set (keys events/field-policy))))
  (doseq [field events/editable-fields]
    (is (ifn? (get events/field-policy field))
        (str "field " field " has no policy function"))))

(deftest the-seed-carries-no-revision-key
  ;; The premise of the `fnil` below, stated where a reader meets the
  ;; seed. A consumer copying a starting `app-db` from a guide would not
  ;; put a counter in it either.
  (is (not (contains? events/seed :revision)))
  (is (= (:article events/seed) (:draft events/seed))
      "draft and article begin equal; the distance between them is what
       `echoes only committed state` is a claim about"))

;; ---------------------------------------------------------------------------
;; Transitions — through a real frame
;; ---------------------------------------------------------------------------

(deftest an-accepting-field-takes-what-was-typed
  (with-app
    (fn [frame]
      (type! frame :title "Intents, revisited")
      (is (= "Intents, revisited" (read-sub frame [::subs/field :title])))
      (is (true? (read-sub frame [::subs/dirty?]))))))

(deftest a-normalising-field-commits-the-normalised-value
  (with-app
    (fn [frame]
      (type! frame :slug "Hello, World")
      (is (= "hello-world" (read-sub frame [::subs/field :slug]))
          "the MODEL holds the normalised value, which is what the field
           will be handed back and therefore what it echoes — the
           controlled law's `a normalised value echoes as the committed
           one`, asserted here without a DOM and again with one in
           flow-dom-cljs-test"))))

(deftest the-checkbox-commits-a-boolean-and-not-a-truthy
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/set-published true] {:frame frame})
      (is (true? (read-sub frame [::subs/field :published?])))
      (rf/dispatch-sync [::events/set-published nil] {:frame frame})
      (is (false? (read-sub frame [::subs/field :published?]))
          "`nil` coerces to false rather than being stored. An owned
           `:checked` wins by PRESENCE and not by truthiness, so a model
           holding nil would leave the control uncontrolled"))))

(deftest one-keystroke-moves-exactly-one-address
  (with-app
    (fn [frame]
      (let [before (rf/app-db-value frame)
            _      (type! frame :title "x")
            after  (rf/app-db-value frame)]
        (is (= (dissoc before :draft) (dissoc after :draft))
            "nothing outside the draft moved")
        (is (= (dissoc (:draft before) :title) (dissoc (:draft after) :title))
            "and inside the draft, only the field that was typed into. One
             write is the first number in the per-keystroke walk; the
             others are counted in flow-dom-cljs-test")
        (is (not= (:title (:draft before)) (:title (:draft after)))
            "the premise — the write happened at all")))))

(deftest saving-commits-the-draft-and-leaves-it-standing
  (with-app
    (fn [frame]
      (type! frame :title "Committed")
      (is (= "Intents are data" (read-sub frame [::subs/committed :title]))
          "typing does not move the article")
      (rf/dispatch-sync [::events/save] {:frame frame})
      (is (= "Committed" (read-sub frame [::subs/committed :title])))
      (is (= "Committed" (read-sub frame [::subs/field :title]))
          "and the draft still holds it — a save is not a reset")
      (is (false? (read-sub frame [::subs/dirty?]))))))

(deftest discarding-restores-the-draft-and-bumps-the-revision-from-absent
  (with-app
    (fn [frame]
      (type! frame :title "typed")
      (type! frame :slug "Typed Slug")
      (is (= 0 (read-sub frame [::subs/revision]))
          "the subscription's own default answers 0 for the absent key, so
           the fields have a revision to render before any discard")
      (rf/dispatch-sync [::events/discard] {:frame frame})
      (is (= (get-in events/seed [:article :title])
             (read-sub frame [::subs/field :title]))
          "the model is back")
      (is (= "intents-are-data" (read-sub frame [::subs/field :slug])))
      (is (false? (read-sub frame [::subs/dirty?])))
      (is (= 1 (read-sub frame [::subs/revision]))
          "ONE, from a key that did not exist. `(fnil inc 0)` is what makes
           the FIRST discard a reset rather than a nil arithmetic error,
           and nothing on the public door says an application needs a
           counter at all — rf2-hic-025 finding 5, confirmed")
      (rf/dispatch-sync [::events/discard] {:frame frame})
      (is (= 2 (read-sub frame [::subs/revision]))
          "and a second discard bumps again, so two resets in a row are two
           distinct triggers rather than one repeated value"))))
