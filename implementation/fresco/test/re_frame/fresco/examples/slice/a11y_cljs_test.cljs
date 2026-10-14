(ns re-frame.fresco.examples.slice.a11y-cljs-test
  "THE SLICE'S STRUCTURAL ACCESSIBILITY (specification §7's accessibility
  row).

  The row's claim is that data-first markup makes roles and names plain
  attributes, so a structural assertion can hold them and a browser is
  needed only for focus. This file is the structural half. Its
  counterpart is
  `re-frame.fresco.examples.slice.a11y-focus-dom-cljs-test`, which asks
  a real engine whether it agrees.

  ## Every row here is about a VALUE, and most are about a value that MOVES

  Asserting that an `aria-*` attribute is PRESENT is close to worthless:
  the assertion survives every change that makes the attribute wrong, and
  it survives the attribute being wired to a constant. So the rows below
  are arranged around two sharper questions.

  **Does the attribute carry the right value for the state the page is
  in, and does it change when the state changes?** `[[the-disclosures-
  expanded-state-moves-while-its-name-holds-still]]` is the clearest
  case: `aria-expanded` must flip and the toggle's accessible NAME must
  not, because a control that renames itself as it changes state is a
  control a screen-reader user loses track of.

  **Is the name RESOLVED, or merely present?** A `<label for=\"x\">`
  beside an `<input id=\"y\">` has both halves of the pairing and no
  pairing. `ht/accessible-name` is what makes the difference visible: it
  resolves the reference, so a row asserting the name is asserting the
  pair.

  ## The sweep, and why it is a sweep

  [[every-operable-control-in-the-application-has-a-name]] runs
  `ht/unnamed-controls` over every one of the slice's bodies that carries
  an operable control — six bodies, and the pager, whose three page
  positions each render a different set of links and end-stops — in every
  state each of them has. One assertion per tree, in the
  form the application can be held to as it grows: a seventh control
  added tomorrow without a name reds it, and no row has to be written for
  it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.fresco.examples.slice.i18n :as rf.fresco.examples.slice.i18n]
            [re-frame.fresco.examples.slice.routes :as rf.fresco.examples.slice.routes]
            [re-frame.fresco.examples.slice.subs :as rf.fresco.examples.slice.subs]
            [re-frame.fresco.examples.slice.views :as rf.fresco.examples.slice.views]
            [re-frame.fresco.test :as rf.fresco.test]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The fixture maps, generated from the REAL string table
;; ---------------------------------------------------------------------------
;;
;; `[::subs/t k]` fixtures are taken from `i18n/strings` through `i18n/t`
;; rather than typed here, so a French tree really is the French table's
;; and the locale rows below compare two renderings of one application
;; rather than two hand-written maps. The ASSERTED names are still
;; literals — that is where the claim lives.

(defn- t* [locale ks]
  (into {} (map (fn [k] [[::rf.fresco.examples.slice.subs/t k] (rf.fresco.examples.slice.i18n/t locale k)])) ks))

(defn- classed [tree class] (rf.fresco.test/find tree #(= class (:class (rf.fresco.test/attrs %)))))

(defn- named [tree class] (rf.fresco.test/accessible-name tree (classed tree class)))

;; --- chrome ----------------------------------------------------------------

(defn- chrome-tree [locale]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/chrome {}]
           {:subs (merge {[::rf.fresco.examples.slice.subs/locale] locale
                          [::rf.fresco.examples.slice.subs/theme]  :light}
                         (t* locale [:app/title :app/locale :app/theme
                                     :theme/light :theme/dark]))}))

;; --- a feed row ------------------------------------------------------------

(def ^:private row-props
  {:slug "intents" :title "Intents are data" :published? true :tags ["intents" "data"]})

(defn- row-tree [locale open?]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/article-row row-props]
           {:subs (merge {[::rf.fresco.examples.slice.subs/tags-open? "intents"] open?}
                         (t* locale [:feed/tags]))}))

;; --- the feed page ---------------------------------------------------------

(defn- feed-tree [locale rows]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/feed-page {}]
           {:subs (merge {[::rf.fresco.examples.slice.subs/feed] rows}
                         (t* locale [:feed/heading :feed/empty]))}))

;; --- the pager -------------------------------------------------------------

(defn- pager-tree
  "The pager on page `page` of three. The digest's retry is not swept
  beside it: it lives on a `:fallback`, which is inert markup this tier
  reads as data rather than a node the sweep can walk."
  [locale page]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/pager {}]
           {:subs (merge {[::rf.fresco.examples.slice.subs/current-page] page
                          [::rf.fresco.examples.slice.subs/page-count]   3}
                         (t* locale [:feed/pagination :feed/previous :feed/next]))}))

;; --- the editor ------------------------------------------------------------

(defn- editor-tree
  [locale save]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/editor {:slug "intents"}]
           {:subs (merge {[::rf.fresco.examples.slice.subs/draft "intents"]      {:title "T" :body "B"
                                                         :published? true}
                          [::rf.fresco.examples.slice.subs/dirty? "intents"]     false
                          [::rf.fresco.examples.slice.subs/save-state "intents"] save
                          [::rf.fresco.examples.slice.subs/token :danger]        "rgb(176, 32, 32)"}
                         (t* locale [:editor/heading :editor/title :editor/body
                                     :editor/published :editor/save :editor/saving
                                     :editor/discard :editor/saved :editor/retry
                                     :problem/title-taken]))}))

;; --- the article page and the shell ----------------------------------------

(defn- article-tree [locale article]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/article-page {}]
           {:subs (merge {[:rf.route/params]         {:slug "intents"}
                          [::rf.fresco.examples.slice.subs/article "intents"] article}
                         (t* locale [:article/back :article/missing]))}))

(defn- shell-tree [locale route]
  (rf.fresco.test/tree [rf.fresco.examples.slice.views/app {}]
           {:subs (merge {[:rf.route/id]          route
                          [::rf.fresco.examples.slice.subs/token :surface] "rgb(255, 255, 255)"
                          [::rf.fresco.examples.slice.subs/token :ink]     "rgb(26, 26, 26)"}
                         (t* locale [:app/pane-error]))}))

;; ---------------------------------------------------------------------------
;; Values that MOVE with the state, and values that must not
;; ---------------------------------------------------------------------------

(deftest the-disclosures-expanded-state-moves-while-its-name-holds-still
  (let [closed (row-tree :en false)
        open   (row-tree :en true)]
    (testing "the state, both ways"
      (is (= "false" (:aria-expanded (rf.fresco.test/attrs (classed closed "tags-toggle")))))
      (is (= "true" (:aria-expanded (rf.fresco.test/attrs (classed open "tags-toggle"))))))
    (testing "and the NAME, unchanged across the flip — a control that
              renames itself as its state changes is a control a
              screen-reader user has to re-find, and the two halves of
              this row are what separate a live `aria-expanded` from a
              label that encodes the state in its text"
      (is (= "Tags" (named closed "tags-toggle")))
      (is (= "Tags" (named open "tags-toggle"))))
    (testing "in French too, so the name moves with the LOCALE and not
              with the disclosure"
      (is (= "Étiquettes" (named (row-tree :fr false) "tags-toggle")))
      (is (= "Étiquettes" (named (row-tree :fr true) "tags-toggle"))))))

(deftest the-editors-live-region-carries-the-role-its-state-earns
  (testing "idle: neither region exists, so neither role is announced"
    (let [tree (editor-tree :en {:status :idle :problem nil})]
      (is (nil? (classed tree "save-problem")))
      (is (nil? (classed tree "save-ok")))))

  (testing "failed: an ALERT, which interrupts"
    (let [tree (editor-tree :en {:status :failed :problem :problem/title-taken})]
      (is (= :alert (rf.fresco.test/role (classed tree "save-problem"))))
      (is (nil? (classed tree "save-ok")))))

  (testing "saved: a STATUS, which does not — the two roles are different
            promises about how urgently a screen reader interrupts, and a
            row asserting only that `a role is present` could not tell
            them apart"
    (let [tree (editor-tree :en {:status :saved :problem nil})]
      (is (= :status (rf.fresco.test/role (classed tree "save-ok"))))
      (is (nil? (classed tree "save-problem")))))

  (testing "and a live region takes no name from its own text: it is
            announced BECAUSE it appeared, and naming it would be this
            kit inventing something the platform does not give"
    (let [tree (editor-tree :en {:status :failed :problem :problem/title-taken})]
      (is (nil? (rf.fresco.test/accessible-name tree (classed tree "save-problem")))))))

;; ---------------------------------------------------------------------------
;; The sweep — the accessibility acceptance, as one assertion per rendering
;; ---------------------------------------------------------------------------

(defn- states
  "Every rendering this application has, in one locale: each body, and
  every branch of the ones that have branches. Named, so a failure says
  which screen it came from."
  [locale]
  (concat
    [["chrome" (chrome-tree locale)]
     ["the pager, on the first page" (pager-tree locale 1)]
     ["the pager, in the middle" (pager-tree locale 2)]
     ["the pager, on the last page" (pager-tree locale 3)]
     ["a feed row, closed" (row-tree locale false)]
     ["a feed row, open" (row-tree locale true)]
     ["a feed row, unpublished"
      (rf.fresco.test/tree [rf.fresco.examples.slice.views/article-row (assoc row-props :published? false)]
               {:subs (merge {[::rf.fresco.examples.slice.subs/tags-open? "intents"] false}
                             (t* locale [:feed/tags]))})]
     ;; Both reads sit at the top of the row's `let`, so they run whether
     ;; or not the row has tags, and a tagless row owes both fixtures too.
     ["a feed row with no tags"
      (rf.fresco.test/tree [rf.fresco.examples.slice.views/article-row (assoc row-props :tags [])]
               {:subs (merge {[::rf.fresco.examples.slice.subs/tags-open? "intents"] false}
                             (t* locale [:feed/tags]))})]
     ["the feed, populated" (feed-tree locale [{:slug "a" :title "A"
                                                :published? true :tags []}])]
     ["the feed, empty" (feed-tree locale [])]
     ["the article page" (article-tree locale {:slug "intents"
                                               :title "Intents are data"})]
     ["the article page, no such article" (article-tree locale nil)]
     ["the shell, on the feed" (shell-tree locale rf.fresco.examples.slice.routes/feed)]
     ["the shell, on an article" (shell-tree locale rf.fresco.examples.slice.routes/article)]
     ["the shell, before the first navigation" (shell-tree locale nil)]]
    (for [save [{:status :idle :problem nil}
                {:status :saving :problem nil}
                {:status :saved :problem nil}
                {:status :failed :problem :problem/title-taken}]]
      [(str "the editor, " (name (:status save))) (editor-tree locale save)])))

(deftest every-operable-control-in-the-application-has-a-name
  ;; One locale: `i18n/t` answers a visible sentinel for a missing key,
  ;; so another locale cannot leave a control nameless where this one does not.
  (doseq [[what tree] (states :en)]
    (is (= [] (rf.fresco.test/unnamed-controls tree))
        (str what
             " — every button, link, field and select a user can operate "
             "must carry an accessible name, and this is the whole "
             "application asked at once"))))
