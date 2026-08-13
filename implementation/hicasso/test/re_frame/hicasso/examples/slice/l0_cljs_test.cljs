(ns re-frame.hicasso.examples.slice.l0-cljs-test
  "L0 — THE SLICE'S HANDLERS, SUBSCRIPTIONS AND TRANSITIONS (rf2-hic-025).

  `re-frame.hicasso.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: *an event handler is a function of `db`
  and an event vector; a subscription is a function of its inputs; a
  state transition is the pair.* None of that needs a view substrate, so
  none of it is written with one, and this file `:require`s neither
  `re-frame.hicasso` nor either half of the test kit.

  Frame scope is the programmer's ordinary bracket — `rf/with-new-frame`
  — exactly as the ladder says.

  ## The two halves, kept apart on purpose

  The PURE rows call `re-frame.hicasso.examples.slice.db` directly with a
  map, because a calculation over data is best tested as one. The
  TRANSITION rows go through a real frame and a real `dispatch-sync`,
  because a handler is only a handler once the registrar has it and the
  interceptor chain has run — a test that called the handler function by
  hand would be green for a registration that never happened."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.slice.db :as db]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.i18n :as i18n]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.routing :as routing]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The reset restores the registrar to a baseline captured when this
     ;; form was EVALUATED, which is before `routes` finished loading — so
     ;; the URL rows below would meet an empty route registry. See that
     ;; namespace on why `register!` is exposed at all.
     :init-fn       (fn [] (routes/register!))}))

(defn- with-app
  "Run `f` against a fresh frame seeded with the slice's own starting
  `app-db`, and destroy it afterwards. `f` takes the frame.

  The seed is `[::events/seed]` rather than `[:rf/set-db db/seed]` so the
  rows below exercise the handler the application actually boots with."
  [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::events/seed]]})]
    (f frame)))

(defn- read-sub
  "One-shot read of `query-v` against `frame`."
  [frame query-v]
  (rf/subscribe-once query-v {:frame frame}))

(defn- app-db-of [frame] (rf/app-db-value frame))

;; ---------------------------------------------------------------------------
;; Pure — the calculations, with no runtime anywhere
;; ---------------------------------------------------------------------------

(deftest a-slug-with-no-draft-reads-the-article
  (testing "db/draft-for is what makes the editor populated with no load step"
    (is (= {:title "Intents are data"
            :body  "A click carries a vector, and two renders are equal."
            :published? true}
           (db/draft-for db/seed "intents")))
    (is (false? (db/dirty? db/seed "intents"))
        "reading the article is not editing it"))

  (testing "a slug the URL invented has no draft and no article"
    (is (nil? (db/draft-for db/seed "nonsense")))
    (is (nil? (db/article db/seed "nonsense")))))

(deftest the-feed-takes-its-order-from-order
  (is (= ["hicasso" "intents" "controls" "keys" "boundaries" "revision" "pages"]
         (mapv :slug (db/listed db/seed))))
  (testing "an article missing from :articles is skipped rather than nil-padded"
    (is (= ["intents"] (mapv :slug (db/listed (assoc db/seed :order ["gone" "intents"])))))))

;; ---------------------------------------------------------------------------
;; Pagination — arithmetic, and a page number nobody may trust (rf2-hic-074)
;; ---------------------------------------------------------------------------

(def ^:private slugs (mapv :slug (db/listed db/seed)))

(defn- page-slugs [n] (mapv :slug (db/page-rows (db/listed db/seed) n)))

(deftest pages-are-cut-from-the-order-and-the-last-one-is-short
  (is (= 7 (count slugs)) "the seed's roster, which the sizes below are read against")
  (is (= 3 (db/page-count (db/listed db/seed)))
      "seven articles at a page size of three")
  (is (= ["hicasso" "intents" "controls"] (page-slugs 1)))
  (is (= ["keys" "boundaries" "revision"] (page-slugs 2)))
  (is (= ["pages"] (page-slugs 3))
      "a short last page, which is the case a `subvec` past the end gets
       wrong and the one worth pinning")
  (is (= slugs (into [] cat (map page-slugs [1 2 3])))
      "and the three pages are the list — no article is on two pages, and
       none is on none"))

(deftest an-empty-feed-is-one-empty-page-rather-than-zero-pages
  (let [empty-db (assoc db/seed :order [])]
    (is (= 1 (db/page-count (db/listed empty-db)))
        "zero pages would make `page 1` a question with no answer, and the
         pager would have no number to call the page the user is on")
    (is (= [] (db/page-rows (db/listed empty-db) 1)))))

(deftest a-page-number-off-the-url-is-clamped-rather-than-trusted
  (let [rows (db/listed db/seed)]
    (is (= 1 (db/clamp-page rows nil)) "no ?page= at all is the first page")
    (is (= 1 (db/clamp-page rows 1)))
    (is (= 3 (db/clamp-page rows 3)))
    (testing "a number outside the range is a PAGE, not an error — a URL is
              user input and `/slice?page=900` is something anybody can type"
      (is (= 1 (db/clamp-page rows 0)))
      (is (= 1 (db/clamp-page rows -3)))
      (is (= 3 (db/clamp-page rows 900)))
      (is (= ["pages"] (page-slugs 900))
          "and the rows follow the clamp, so the page renders the nearest
           thing that exists rather than nothing at all"))))

;; ---------------------------------------------------------------------------
;; The page rides the URL, so Back and Forward are routing's (rf2-hic-074)
;; ---------------------------------------------------------------------------

(deftest the-page-round-trips-through-the-url
  ;; THE WHOLE OF BACK/FORWARD CONDUCT, at the tier that can state it. A
  ;; browser's history entry is a URL and nothing else: Back hands the
  ;; application the string it was on, and what the application does with
  ;; it is `match-url`. So a page survives Back exactly when it survives
  ;; this round trip — no history listener, no `popstate` handler and no
  ;; navigation event of the application's own is involved, and there is
  ;; none in this slice to involve.
  (testing "the pager's own destination becomes a URL that carries the page"
    (is (= "/slice?page=2" (routing/route-url {:to routes/feed :query {:page 2}}))))

  (testing "and the URL comes back as the same page, as a NUMBER"
    (let [match (routing/match-url "/slice?page=2")]
      (is (= routes/feed (:route-id match)))
      (is (= {:page 2} (:query match))
          "`:int` coercion is what makes this a 2 rather than a \"2\" — the
           subscription that reads it does arithmetic, and a string would
           clamp and compare as one all the way to the page numbers")))

  (testing "a bare /slice is page one, because :query-defaults says so
            rather than because a view remembered to"
    (is (= {:page 1} (:query (routing/match-url "/slice")))))

  (testing "the article route is untouched by any of it"
    (is (= routes/article (:route-id (routing/match-url "/slice/article/intents"))))))

(deftest validation-answers-keywords-in-a-stable-order
  (is (= [] (db/problems {:title "t" :body "b"})))
  (is (= [:problem/title-blank] (db/problems {:title "  " :body "b"})))
  (is (= [:problem/body-blank] (db/problems {:title "t" :body nil})))
  (is (= [:problem/title-blank :problem/body-blank] (db/problems {}))
      "title first, always — the editor shows the first problem and a set
       would make which one it shows depend on hashing"))

(deftest a-title-is-taken-only-by-another-article
  (is (true? (db/title-taken? db/seed "controls" "Intents are data")))
  (is (false? (db/title-taken? db/seed "intents" "Intents are data"))
      "an article does not collide with itself, or no article could ever
       be saved without renaming it")
  (is (true? (db/title-taken? db/seed "controls" "  intents ARE data  "))
      "case and surrounding space are not what makes two titles different"))

(deftest commit-folds-the-draft-and-clears-it
  (let [db' (db/commit db/seed "intents" {:title "Renamed" :body "New body" :published? false})]
    (is (= "Renamed" (:title (db/article db' "intents"))))
    (is (false? (db/dirty? db' "intents")))
    (is (= #{:articles :order :drafts :save :digest :locale :theme}
           (set (keys db')))
        "a commit moves two keys and mints none — in particular no
         `:revision` counter, which this application measured and does not
         carry (rf2-36bd; see db's namespace docstring)")))

;; ---------------------------------------------------------------------------
;; i18n — a hole is visible
;; ---------------------------------------------------------------------------

(deftest a-missing-translation-shows-itself
  (is (= "Articles" (i18n/t :en :feed/heading)))
  (is (= "Rien de publié pour l'instant." (i18n/t :fr :feed/empty)))
  (is (= "‹feed/heading›" (i18n/t :de :feed/heading))
      "an unknown locale answers the sentinel rather than falling back to
       English, because a silent fallback makes a hole and a translation
       look identical on screen and in a test")
  (is (= "‹app/nonexistent›" (i18n/t :en :app/nonexistent))))

(deftest every-locale-carries-every-key
  (let [ks (set (keys (:en i18n/strings)))]
    (doseq [locale i18n/locales]
      (is (= ks (set (keys (get i18n/strings locale))))
          (str "locale " locale " and :en disagree about which strings exist")))))

(deftest every-theme-carries-every-token
  (let [ks (set (keys (:light i18n/themes)))]
    (doseq [[theme tokens] i18n/themes]
      (is (= ks (set (keys tokens)))
          (str "theme " theme " and :light disagree about which tokens exist")))))

;; ---------------------------------------------------------------------------
;; Transitions — through a real frame
;; ---------------------------------------------------------------------------

(deftest the-first-keystroke-edits-on-top-of-the-article
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/edit "intents" :title "Intents, revisited"] {:frame frame})
      (is (= {:title "Intents, revisited"
              :body  "A click carries a vector, and two renders are equal."
              :published? true}
             (read-sub frame [::subs/draft "intents"]))
          "the body and the published flag survive an edit that named
           neither — without db/draft-for underneath, the first character
           would land in an empty map and blank them")
      (is (true? (read-sub frame [::subs/dirty? "intents"]))))))

(deftest discarding-restores-the-article
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/edit "intents" :title "typed"] {:frame frame})
      (is (true? (read-sub frame [::subs/dirty? "intents"])))
      (rf/dispatch-sync [::events/discard {:slug "intents"}] {:frame frame})
      (is (= "Intents are data" (:title (read-sub frame [::subs/draft "intents"])))
          "the MODEL moved, which is the whole of the reset here — the
           counter this handler used to bump beside it was measured inert
           and removed (rf2-36bd)")
      (is (false? (read-sub frame [::subs/dirty? "intents"]))))))

(deftest a-local-problem-never-reaches-the-server
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/edit "intents" :title "   "] {:frame frame})
      (rf/dispatch-sync [::events/save {:slug "intents"}] {:frame frame})
      (is (= {:status :failed :problem :problem/title-blank}
             (read-sub frame [::subs/save-state "intents"]))
          "no request is made, so the status goes straight to :failed —
           there is nothing in flight and nothing that can arrive late")
      (is (true? (read-sub frame [::subs/dirty? "intents"]))
          "a refused save keeps the draft; losing what the user typed
           because it was invalid is the cruellest form of validation"))))

(deftest a-save-that-passes-validation-goes-in-flight
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/edit "intents" :title "Fine"] {:frame frame})
      (rf/dispatch-sync [::events/save {:slug "intents"}] {:frame frame})
      (is (= {:status :saving :problem nil}
             (read-sub frame [::subs/save-state "intents"])))
      (is (true? (read-sub frame [::subs/dirty? "intents"]))
          "the draft is kept until the reply lands, so a failure has
           something to fail back to"))))

(deftest the-reply-is-checked-against-the-request
  (testing "a reply for the article this frame is saving is folded in"
    (with-app
      (fn [frame]
        (rf/dispatch-sync [::events/edit "intents" :title "Fine"] {:frame frame})
        (rf/dispatch-sync [::events/save {:slug "intents"}] {:frame frame})
        (rf/dispatch-sync [::events/saved {:slug "intents" :draft {:title "Fine"}}]
                          {:frame frame})
        (is (= "Fine" (:title (db/article (app-db-of frame) "intents"))))
        (is (= :saved (:status (read-sub frame [::subs/save-state "intents"])))))))

  (testing "a reply for an article it is no longer saving is DROPPED"
    (with-app
      (fn [frame]
        (rf/dispatch-sync [::events/edit "intents" :title "Fine"] {:frame frame})
        (rf/dispatch-sync [::events/save {:slug "intents"}] {:frame frame})
        ;; The user moved on and started saving a different article.
        (rf/dispatch-sync [::events/edit "controls" :title "Also fine"] {:frame frame})
        (rf/dispatch-sync [::events/save {:slug "controls"}] {:frame frame})
        ;; …and now the first request's reply lands.
        (rf/dispatch-sync [::events/saved {:slug "intents" :draft {:title "Fine"}}]
                          {:frame frame})
        (is (= "Intents are data" (:title (db/article (app-db-of frame) "intents")))
            "the stale reply moved nothing")
        (is (= :saving (:status (read-sub frame [::subs/save-state "controls"])))
            "and did not disturb the save that is actually in flight")))))

(deftest the-save-region-is-projected-per-article
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/edit "intents" :title "Fine"] {:frame frame})
      (rf/dispatch-sync [::events/save {:slug "intents"}] {:frame frame})
      (is (= {:status :saving :problem nil} (read-sub frame [::subs/save-state "intents"])))
      (is (= {:status :idle :problem nil} (read-sub frame [::subs/save-state "controls"]))
          "the projection is what keeps the stale-reply rule out of the
           view: an editor asks one question, not two"))))

;; ---------------------------------------------------------------------------
;; i18n and theming, through the frame
;; ---------------------------------------------------------------------------

(deftest switching-the-locale-moves-every-string
  (with-app
    (fn [frame]
      (is (= "Articles" (read-sub frame [::subs/t :feed/heading])))
      (rf/dispatch-sync [::events/set-locale "fr"] {:frame frame})
      (is (= :fr (read-sub frame [::subs/locale]))
          "the handler keywords the string a <select> hands it")
      (is (= "Modifier" (read-sub frame [::subs/t :editor/heading]))))))

(deftest switching-the-theme-moves-every-token
  (with-app
    (fn [frame]
      (is (= "rgb(255, 255, 255)" (read-sub frame [::subs/token :surface])))
      (rf/dispatch-sync [::events/set-theme {:theme :dark}] {:frame frame})
      (is (= "rgb(18, 21, 26)" (read-sub frame [::subs/token :surface]))))))

;; ---------------------------------------------------------------------------
;; Pagination and the digest, through the frame (rf2-hic-074)
;; ---------------------------------------------------------------------------

(defn- at-page
  "Run `f` against a frame seeded and navigated to page `n` of the feed —
  `nil` for a bare `/slice`, which is what a first visit is."
  [n f]
  (rf/with-new-frame
    [frame (rf/make-frame
             {:initial-events [[::events/seed]
                               [:rf.route/navigate
                                (cond-> {:to routes/feed}
                                  (some? n) (assoc :query {:page n}))]]})]
    (f frame)))

(deftest the-list-shows-the-page-the-ROUTE-asks-for-and-holds-no-copy-of-it
  (testing "a bare /slice"
    (at-page nil
      (fn [frame]
        (is (= 1 (read-sub frame [::subs/current-page])))
        (is (= 3 (read-sub frame [::subs/page-count])))
        (is (= ["hicasso" "intents" "controls"]
               (mapv :slug (read-sub frame [::subs/feed])))))))

  (testing "?page=2 moves the list and nothing else"
    (at-page 2
      (fn [frame]
        (is (= 2 (read-sub frame [::subs/current-page])))
        (is (= ["keys" "boundaries" "revision"]
               (mapv :slug (read-sub frame [::subs/feed]))))
        (is (= 7 (count (read-sub frame [::subs/listed])))
            "the whole list is still the whole list — pagination is a
             projection over it, not a filter applied to `app-db`")
        (is (nil? (:page (app-db-of frame)))
            "AND THE PAGE IS NOT IN app-db's application partition. It is
             routing state, read through `[:rf.route/query]`; a second copy
             here is the thing that lets the address bar and the list
             disagree after a Back"))))

  (testing "?page=900 clamps, and the RAW ask stays visible beside it"
    (at-page 900
      (fn [frame]
        (is (= 900 (read-sub frame [::subs/page])) "what the URL asked for")
        (is (= 3 (read-sub frame [::subs/current-page])) "what exists")
        (is (= ["pages"] (mapv :slug (read-sub frame [::subs/feed]))))))))

(deftest the-digest-is-seeded-whole-and-a-partial-delivery-is-taken-as-it-came
  (with-app
    (fn [frame]
      (is (= db/digest (read-sub frame [::subs/digest-blocks]))
          "the application opens with content; the digest ships with the page")
      (is (false? (read-sub frame [::subs/digest-loading?])))

      (testing "a delivery that arrived cut short is stored UNINSPECTED —
                the client validates no block, and the region's own error
                boundary is why it does not have to"
        (rf/dispatch-sync [::events/digest-arrived {:blocks db/digest-truncated}]
                          {:frame frame})
        (is (= db/digest-truncated (read-sub frame [::subs/digest-blocks])))
        (is (nil? (:block/items (second (read-sub frame [::subs/digest-blocks]))))
            "the list block lost its items, which is the shape a renderer
             refuses")))))

(deftest reloading-the-digest-goes-in-flight-and-keeps-what-is-on-screen
  (with-app
    (fn [frame]
      (rf/dispatch-sync [::events/digest-arrived {:blocks db/digest-truncated}]
                        {:frame frame})
      (rf/dispatch-sync [::events/reload-digest] {:frame frame})
      (is (true? (read-sub frame [::subs/digest-loading?])))
      (is (= db/digest-truncated (read-sub frame [::subs/digest-blocks]))
          "the blocks did not move. Emptying them would clear the region's
           error boundary — its reset key is the CONTENT — so a failed
           region would blink through an empty success on its way back, and
           a reply that never came would leave the user looking at nothing
           instead of at the failure and the button that retries it"))))

;; ---------------------------------------------------------------------------
;; The instance-key sugar — two rows do not share one flag
;; ---------------------------------------------------------------------------

(deftest per-row-disclosure-state-is-per-row
  (with-app
    (fn [frame]
      (is (false? (read-sub frame [::subs/tags-open? "intents"]))
          "the registered :default, with nothing in app-db")
      (rf/dispatch-sync [::subs/tags-open? "intents" true] {:frame frame})
      (is (true? (read-sub frame [::subs/tags-open? "intents"])))
      (is (false? (read-sub frame [::subs/tags-open? "hicasso"]))
          "the bug h/reg-state deletes: a hand-rolled [:ui :tags-open?]
           path would have opened every row on the page at once")
      (is (= {"intents" true} (get-in (app-db-of frame) [:ui ::subs/tags-open?]))
          "and it lives in app-space at [:ui ::concern ikey], readable by
           an ordinary handler and dumpable in a tool"))))
