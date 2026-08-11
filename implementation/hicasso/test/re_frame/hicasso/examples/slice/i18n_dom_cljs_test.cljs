(ns re-frame.hicasso.examples.slice.i18n-dom-cljs-test
  "THE RUNTIME LOCALE AND THEME WITNESS (rf2-hic-025; specification §7's
  i18n / theming row).

  The row's claim is a NEGATIVE one, and negative claims are the ones a
  test has to be built around rather than pointed at: *strings and theme
  tokens are ordinary data read through ordinary subscriptions, and
  switching either at runtime re-renders the page correctly with no
  adapter subsystem anywhere.*

  So this file is arranged to be able to FAIL if the claim were false:

  - the page is mounted **once** and never re-mounted, so what is
    measured is a re-render and not a fresh render. The identity of the
    `<main>` element is asserted across every switch — a mechanism that
    quietly tore the tree down and built it again would show a different
    node, and would also lose focus, scroll and field state on a language
    change, which is exactly the failure an i18n subsystem is bought to
    avoid;
  - the switch is driven by a **real click and a real `change` event** on
    the chrome's own controls, not by a dispatch, so the path under test
    is the one a user takes;
  - the theme reading is taken off the **applied CSS value** rather than
    off a class name, because a class name is a promise about a
    stylesheet the test cannot see;
  - the locale reading is taken in **two regions at once** — the chrome
    and the editor — so a switch that moved the header and left a form
    label behind is red;
  - one row **types into a controlled field and then switches locale**,
    and asserts the typed text survived. A re-render must not disturb the
    model, and this is the sharpest available statement of that.

  ## The one thing this file does not claim

  It says nothing about pluralisation, interpolation, number or date
  formatting, or locale-aware collation. The slice's string table is a
  map of complete sentences; a real application reaches `Intl` for the
  rest, and where it does so is inside the same sub — which is the point,
  and is also why there is nothing here to test about it.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too, and each row degrades there to a STATED
  skip rather than to a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.i18n :as i18n]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.hicasso.examples.slice.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a runtime switch needs a real React DOM — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (routes/register!))}))

(defn- node [m sel] (.querySelector (:container m) sel))
(defn- text [m sel] (some-> (node m sel) .-textContent))

(defn- at-article!
  [slug]
  (hm/mount! [views/app {}]
             {:initial-events [[::events/seed]
                               [:rf.route/navigate {:to routes/article
                                                    :params {:slug slug}}]]}))

(defn- choose-locale!
  "Pick a locale in the chrome's `<select>` — a real `change` event after
  a write through the prototype's own value setter, which is what a user
  choosing an option produces."
  [m locale]
  (let [n (node m "#slice-locale")
        d (js/Object.getOwnPropertyDescriptor js/HTMLSelectElement.prototype "value")]
    (.call (.-set d) n (name locale))
    (.dispatchEvent n (js/Event. "change" #js {:bubbles true}))
    (hm/settle! m)))

(defn- click-theme!
  "Click the theme button by its VISIBLE LABEL in the page's current
  locale — the way a user picks it — rather than by position. Position
  would still pass if the two buttons swapped, and a locale switch that
  reordered them is one of the things this file is watching for."
  [m theme]
  (let [buttons (array-seq (.querySelectorAll (:container m) ".theme-choice"))
        locale  (rf/subscribe-once [::subs/locale] {:frame (:frame m)})
        want    (i18n/t locale (keyword "theme" (name theme)))
        button  (first (filter #(= want (.-textContent %)) buttons))]
    (is (some? button)
        (str "no theme button reads " (pr-str want) " in " (pr-str locale)
             "; the page offers " (pr-str (mapv #(.-textContent %) buttons))))
    (.click button)
    (hm/settle! m)))

(defn- surface-of [m] (.. (node m ".slice") -style -background))

(defn- finish [m done]
  (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))

;; ---------------------------------------------------------------------------
;; Locale
;; ---------------------------------------------------------------------------

(deftest switching-the-locale-moves-every-region-of-one-live-page
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m     (at-article! "intents")
            shell (node m ".slice")]
        (testing "English, the seeded locale"
          (is (= "Chronicle" (text m ".slice-title")))
          (is (= "Language" (text m ".locale-label")))
          (is (= "Edit" (text m ".editor h3")))
          (is (= "All articles" (text m ".back")))
          (is (= "Save" (text m ".save"))))

        (choose-locale! m :fr)

        (testing "French, after ONE change event and no remount"
          (is (= "Chronique" (text m ".slice-title")) "the chrome")
          (is (= "Langue" (text m ".locale-label")))
          (is (= "Modifier" (text m ".editor h3")) "and the editor, together")
          (is (= "Tous les articles" (text m ".back")))
          (is (= "Enregistrer" (text m ".save")))
          (is (identical? shell (node m ".slice"))
              "the SAME element — a re-render, not a rebuild. A mechanism
               that tore the tree down and rebuilt it would lose focus,
               scroll and every scrap of component state on a language
               change, which is the failure an i18n subsystem is bought
               to avoid")
          (is (= "fr" (.-value (node m "#slice-locale")))
              "and the control is controlled: its value is the model's"))

        (testing "and back, so the switch is a function rather than a latch"
          (choose-locale! m :en)
          (is (= "Chronicle" (text m ".slice-title")))
          (is (= "Save" (text m ".save"))))

        (finish m done)))))

(deftest the-content-is-NOT-translated-and-that-is-the-boundary
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")]
        (choose-locale! m :fr)
        (is (= "Intents are data" (text m ".article-title"))
            "the article's own title is DATA, not a string-table key —
             stated here because the line between chrome and content is
             the first thing a reader of this slice will want, and a test
             is where it belongs")
        (is (= "Intents are data" (.-value (node m ".field-title"))))
        (finish m done)))))

(deftest a-locale-switch-does-not-disturb-what-was-typed
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")
            n (node m ".field-title")
            d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
        (.call (.-set d) n "à moitié écrit")
        (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
        (hm/settle! m)
        (choose-locale! m :fr)
        (is (= "à moitié écrit" (.-value (node m ".field-title")))
            "the re-render did not reach past the labels into the model")
        (is (identical? n (node m ".field-title"))
            "and it is the same field, so a caret would still be in it")
        (is (= "Annuler les modifications" (text m ".discard"))
            "while the label beside it did change")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; Theme
;; ---------------------------------------------------------------------------

(deftest switching-the-theme-repaints-from-tokens-on-one-live-page
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m     (at-article! "intents")
            shell (node m ".slice")]
        (is (= "rgb(255, 255, 255)" (surface-of m))
            "the APPLIED CSS value, not a class name — a class name is a
             promise about a stylesheet this test cannot see")

        (click-theme! m :dark)
        (is (= "rgb(18, 21, 26)" (surface-of m)))
        (is (= "rgb(232, 234, 237)" (.. (node m ".slice") -style -color)))
        (is (identical? shell (node m ".slice")) "still the same element")

        (click-theme! m :light)
        (is (= "rgb(255, 255, 255)" (surface-of m)))
        (finish m done)))))

(deftest the-error-region-takes-its-colour-from-the-live-theme
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")
            n (node m ".field-title")
            d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
        ;; A locally invalid draft, so the region appears with no request
        ;; and no clock.
        (.call (.-set d) n "   ")
        (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
        (hm/settle! m)
        (.click (node m ".save"))
        (hm/settle! m)

        (is (= "rgb(176, 32, 32)" (.. (node m ".save-problem") -style -color)))
        (click-theme! m :dark)
        (is (= "rgb(255, 138, 128)" (.. (node m ".save-problem") -style -color))
            "a region that is already on screen repaints, because it read
             the token through a subscription like everything else — no
             theme object was threaded to it and none had to be")
        (finish m done)))))

;; ---------------------------------------------------------------------------
;; Both at once
;; ---------------------------------------------------------------------------

(deftest locale-and-theme-are-independent
  (if-not (browser?)
    (skip! ":node-test has no React DOM")
    (async done
      (let [m (at-article! "intents")]
        (click-theme! m :dark)
        (choose-locale! m :fr)
        (is (= "rgb(18, 21, 26)" (surface-of m))
            "the locale switch did not reset the theme")
        (is (= "Chronique" (text m ".slice-title")))
        (click-theme! m :light)
        (is (= "Chronique" (text m ".slice-title"))
            "and the theme switch did not reset the locale — two keys in
             app-db, and nothing that couples them")
        (finish m done)))))
