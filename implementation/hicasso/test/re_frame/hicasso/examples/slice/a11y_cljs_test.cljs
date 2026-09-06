(ns re-frame.hicasso.examples.slice.a11y-cljs-test
  "THE SLICE'S STRUCTURAL ACCESSIBILITY (specification §7's accessibility
  row).

  The row's claim is that data-first markup makes roles and names plain
  attributes, so a structural assertion can hold them and a browser is
  needed only for focus. This file is the structural half. Its
  counterpart is
  `re-frame.hicasso.examples.slice.a11y-focus-dom-cljs-test`, which asks
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
  pair. Every field in the editor is asserted that way, and asserted
  again in French, so the name is proved to follow the string table
  rather than to have been read off something that happens to sit
  nearby.

  ## The sweep, and why it is a sweep

  [[every-operable-control-in-the-application-has-a-name]] runs
  `ht/unnamed-controls` over every one of the slice's bodies that carries
  an operable control — the original six, and the pager, whose three page
  positions each render a different set of links and end-stops — in every
  state each of them has, in both locales. One assertion per tree, in the
  form the application can be held to as it grows: a seventh control
  added tomorrow without a name reds it, and no row has to be written for
  it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.hicasso.examples.slice.i18n :as rf.hicasso.examples.slice.i18n]
            [re-frame.hicasso.examples.slice.routes :as rf.hicasso.examples.slice.routes]
            [re-frame.hicasso.examples.slice.subs :as rf.hicasso.examples.slice.subs]
            [re-frame.hicasso.examples.slice.views :as rf.hicasso.examples.slice.views]
            [re-frame.hicasso.test :as rf.hicasso.test]
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
  (into {} (map (fn [k] [[::rf.hicasso.examples.slice.subs/t k] (rf.hicasso.examples.slice.i18n/t locale k)])) ks))

(defn- classed [tree class] (rf.hicasso.test/find tree #(= class (:class (rf.hicasso.test/attrs %)))))

(defn- named [tree class] (rf.hicasso.test/accessible-name tree (classed tree class)))

(defn- by-id [tree id] (rf.hicasso.test/find tree #(= id (:id (rf.hicasso.test/attrs %)))))

(defn- theme-buttons
  "The chrome's two theme choices, in menu order. Reached through the
  switch's own container rather than by class, because the CURRENT one
  carries a second class (`theme-choice current`) and a class-equality
  hook would silently return one button and pass."
  [tree]
  (filterv #(= :button (:tag %)) (:children (classed tree "theme-switch"))))

;; --- chrome ----------------------------------------------------------------

(defn- chrome-tree [locale]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/chrome {}]
           {:subs (merge {[::rf.hicasso.examples.slice.subs/locale] locale
                          [::rf.hicasso.examples.slice.subs/theme]  :light}
                         (t* locale [:app/title :app/locale :app/theme
                                     :theme/light :theme/dark]))}))

;; --- a feed row ------------------------------------------------------------

(def ^:private row-props
  {:slug "intents" :title "Intents are data" :published? true :tags ["intents" "data"]})

(defn- row-tree [locale open?]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/article-row row-props]
           {:subs (merge {[::rf.hicasso.examples.slice.subs/tags-open? "intents"] open?}
                         (t* locale [:feed/tags]))}))

;; --- the feed page ---------------------------------------------------------

(defn- feed-tree [locale rows]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/feed-page {}]
           {:subs (merge {[::rf.hicasso.examples.slice.subs/feed] rows}
                         (t* locale [:feed/heading :feed/empty]))}))

;; --- the pager -------------------------------------------------------------

(defn- pager-tree
  "The pager on page `page` of three. Its controls are the extension's
  only new operable ones — the digest's retry lives on a `:fallback`,
  which is inert markup this tier reads as data rather than a node the
  sweep can walk."
  [locale page]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/pager {}]
           {:subs (merge {[::rf.hicasso.examples.slice.subs/current-page] page
                          [::rf.hicasso.examples.slice.subs/page-count]   3}
                         (t* locale [:feed/pagination :feed/previous :feed/next]))}))

;; --- the editor ------------------------------------------------------------

(defn- editor-tree
  [locale save]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/editor {:slug "intents"}]
           {:subs (merge {[::rf.hicasso.examples.slice.subs/draft "intents"]      {:title "T" :body "B"
                                                         :published? true}
                          [::rf.hicasso.examples.slice.subs/dirty? "intents"]     false
                          [::rf.hicasso.examples.slice.subs/save-state "intents"] save
                          [::rf.hicasso.examples.slice.subs/token :danger]        "rgb(176, 32, 32)"}
                         (t* locale [:editor/heading :editor/title :editor/body
                                     :editor/published :editor/save :editor/saving
                                     :editor/discard :editor/saved :editor/retry
                                     :problem/title-taken]))}))

;; --- the article page and the shell ----------------------------------------

(defn- article-tree [locale article]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/article-page {}]
           {:subs (merge {[:rf.route/params]         {:slug "intents"}
                          [::rf.hicasso.examples.slice.subs/article "intents"] article}
                         (t* locale [:article/back :article/missing]))}))

(defn- shell-tree [locale route]
  (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/app {}]
           {:subs (merge {[:rf.route/id]          route
                          [::rf.hicasso.examples.slice.subs/token :surface] "rgb(255, 255, 255)"
                          [::rf.hicasso.examples.slice.subs/token :ink]     "rgb(26, 26, 26)"}
                         (t* locale [:app/pane-error]))}))

;; ---------------------------------------------------------------------------
;; Names that are RESOLVED through a label, not merely present beside one
;; ---------------------------------------------------------------------------

(deftest every-field-in-the-editor-is-named-by-the-label-that-points-at-it
  (let [tree (editor-tree :en {:status :idle :problem nil})]
    (is (= "Title" (rf.hicasso.test/accessible-name tree (by-id tree "slice-title")))
        "the `<label for=\"slice-title\">` and the `<input id=\"slice-title\">`
         are two elements and one pairing, and only the resolved name can
         tell a correct pairing from two attributes that happen to sit in
         the same form")
    (is (= "Body" (rf.hicasso.test/accessible-name tree (by-id tree "slice-body")))
        "and a textarea is labelled the same way")
    (is (= "Published" (rf.hicasso.test/accessible-name tree (by-id tree "slice-published")))
        "the checkbox is inside its label AND carries the `for` — the two
         mechanisms at once, and the name is the label's text once rather
         than twice")))

(deftest the-locale-select-is-named-by-the-chromes-own-label
  (let [tree (chrome-tree :en)]
    (is (= :combobox (rf.hicasso.test/role (by-id tree "slice-locale"))))
    (is (= "Language" (rf.hicasso.test/accessible-name tree (by-id tree "slice-locale"))))))

(deftest a-name-follows-the-string-table-into-the-other-language
  (testing "the same three fields, the same three pairings, one locale
            later — which is what says the name is READ from the label
            rather than baked beside the control"
    (let [tree (editor-tree :fr {:status :idle :problem nil})]
      (is (= "Titre" (rf.hicasso.test/accessible-name tree (by-id tree "slice-title"))))
      (is (= "Corps" (rf.hicasso.test/accessible-name tree (by-id tree "slice-body"))))
      (is (= "Publié" (rf.hicasso.test/accessible-name tree (by-id tree "slice-published"))))))
  (is (= "Langue" (let [tree (chrome-tree :fr)]
                    (rf.hicasso.test/accessible-name tree (by-id tree "slice-locale"))))))

(deftest the-buttons-are-named-by-what-they-say
  (let [tree (editor-tree :en {:status :idle :problem nil})]
    (is (= "Save" (named tree "save")))
    (is (= "Discard changes" (named tree "discard"))))
  (let [tree (editor-tree :fr {:status :idle :problem nil})]
    (is (= "Enregistrer" (named tree "save")))
    (is (= "Annuler les modifications" (named tree "discard"))))
  (testing "and the theme switch's two buttons, which have no label element
            anywhere — content is their whole name and the row proves the
            content path is live"
    (let [tree (chrome-tree :en)]
      (is (= ["Light" "Dark"] (mapv #(rf.hicasso.test/accessible-name tree %) (theme-buttons tree)))))
    (let [tree (chrome-tree :fr)]
      (is (= ["Clair" "Sombre"] (mapv #(rf.hicasso.test/accessible-name tree %) (theme-buttons tree)))))))

(deftest the-article-link-is-a-link-because-routing-gave-it-an-href
  (let [tree (row-tree :en false)
        link (rf.hicasso.test/find tree #(= :a (:tag %)))]
    (is (= :link (rf.hicasso.test/role link))
        "an `<a>` with no href is not a link, and this one's href is the
         routing artefact's synthesis rather than a string the view
         wrote — so the role is evidence that the crossing happened")
    (is (= "Intents are data" (rf.hicasso.test/accessible-name tree link))
        "named by the article's own title, which is CONTENT and is
         therefore not translated")))

;; ---------------------------------------------------------------------------
;; Values that MOVE with the state, and values that must not
;; ---------------------------------------------------------------------------

(deftest the-disclosures-expanded-state-moves-while-its-name-holds-still
  (let [closed (row-tree :en false)
        open   (row-tree :en true)]
    (testing "the state, both ways"
      (is (= "false" (:aria-expanded (rf.hicasso.test/attrs (classed closed "tags-toggle")))))
      (is (= "true" (:aria-expanded (rf.hicasso.test/attrs (classed open "tags-toggle"))))))
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
      (is (= :alert (rf.hicasso.test/role (classed tree "save-problem"))))
      (is (nil? (classed tree "save-ok")))))

  (testing "saved: a STATUS, which does not — the two roles are different
            promises about how urgently a screen reader interrupts, and a
            row asserting only that `a role is present` could not tell
            them apart"
    (let [tree (editor-tree :en {:status :saved :problem nil})]
      (is (= :status (rf.hicasso.test/role (classed tree "save-ok"))))
      (is (nil? (classed tree "save-problem")))))

  (testing "and a live region takes no name from its own text: it is
            announced BECAUSE it appeared, and naming it would be this
            kit inventing something the platform does not give"
    (let [tree (editor-tree :en {:status :failed :problem :problem/title-taken})]
      (is (nil? (rf.hicasso.test/accessible-name tree (classed tree "save-problem")))))))

(deftest the-save-button-renames-itself-while-it-is-working
  (testing "the one control in the application whose NAME is supposed to
            move — a button reporting its own progress — asserted here so
            the `name holds still` row above is a claim about the
            disclosure rather than a rule this file applies blindly"
    (is (= "Save" (named (editor-tree :en {:status :idle :problem nil}) "save")))
    (is (= "Saving…" (named (editor-tree :en {:status :saving :problem nil}) "save")))))

(deftest the-retry-appears-with-the-failure-and-is-named
  (let [tree (editor-tree :en {:status :failed :problem :problem/title-taken})]
    (is (= "Try again" (named tree "retry")))
    (is (= :button (rf.hicasso.test/role (classed tree "retry")))))
  (is (nil? (classed (editor-tree :en {:status :idle :problem nil}) "retry"))))

(deftest the-shell-is-a-main-landmark-and-the-feed-is-a-list
  (is (= :main (rf.hicasso.test/role (shell-tree :en rf.hicasso.examples.slice.routes/feed))))
  (let [tree (feed-tree :en [{:slug "a" :title "A" :published? true :tags []}])]
    (is (= :list (rf.hicasso.test/role (rf.hicasso.test/find tree #(= :ul (:tag %))))))
    (is (= :heading (rf.hicasso.test/role (rf.hicasso.test/find tree #(= :h2 (:tag %))))))))

;; ---------------------------------------------------------------------------
;; The sweep — the bead's acceptance, as one assertion per rendering
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
      (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/article-row (assoc row-props :published? false)]
               {:subs (merge {[::rf.hicasso.examples.slice.subs/tags-open? "intents"] false}
                             (t* locale [:feed/tags]))})]
     ;; Both reads sit at the top of the row's `let`, so they run whether
     ;; or not the row has tags, and a tagless row owes both fixtures too.
     ["a feed row with no tags"
      (rf.hicasso.test/tree [rf.hicasso.examples.slice.views/article-row (assoc row-props :tags [])]
               {:subs (merge {[::rf.hicasso.examples.slice.subs/tags-open? "intents"] false}
                             (t* locale [:feed/tags]))})]
     ["the feed, populated" (feed-tree locale [{:slug "a" :title "A"
                                                :published? true :tags []}])]
     ["the feed, empty" (feed-tree locale [])]
     ["the article page" (article-tree locale {:slug "intents"
                                               :title "Intents are data"})]
     ["the article page, no such article" (article-tree locale nil)]
     ["the shell, on the feed" (shell-tree locale rf.hicasso.examples.slice.routes/feed)]
     ["the shell, on an article" (shell-tree locale rf.hicasso.examples.slice.routes/article)]
     ["the shell, before the first navigation" (shell-tree locale nil)]]
    (for [save [{:status :idle :problem nil}
                {:status :saving :problem nil}
                {:status :saved :problem nil}
                {:status :failed :problem :problem/title-taken}]]
      [(str "the editor, " (name (:status save))) (editor-tree locale save)])))

(deftest every-operable-control-in-the-application-has-a-name
  (doseq [locale rf.hicasso.examples.slice.i18n/locales
          [what tree] (states locale)]
    (is (= [] (rf.hicasso.test/unnamed-controls tree))
        (str what ", in " (name locale)
             " — every button, link, field and select a user can operate "
             "must carry an accessible name, and this is the whole "
             "application asked at once"))))

(deftest the-sweep-would-notice
  (testing "the control that proves the sweep is not vacuous: the SAME
            editor rendering with the title field's label detached from
            it. Nothing else changes — the label is still there, the
            input still has its id — and the sweep must find exactly one
            offender. Without this row, an empty answer above would be
            equally consistent with `unnamed-controls` never finding
            anything at all"
    (let [tree     (editor-tree :en {:status :idle :problem nil})
          detached (rf.hicasso.test/find tree #(and (= :label (:tag %))
                                       (= "slice-title" (:for (rf.hicasso.test/attrs %)))))
          sabotaged (update tree :children
                            (fn [cs] (mapv #(if (identical? % detached)
                                              (assoc % :attrs {:for "elsewhere"})
                                              %)
                                           cs)))
          found     (rf.hicasso.test/unnamed-controls sabotaged)]
      (is (= 1 (count found)))
      (is (= "slice-title" (:id (rf.hicasso.test/attrs (first found))))))))
