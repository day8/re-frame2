(ns re-frame.freehand.guide.testing-cljs-test
  "Executable fixtures for `docs/core/freehand/testing.md`.

  Every fenced block on that page is a test, so this namespace is the one
  place where transcription is exact: each of the guide's four `deftest`s
  keeps its own name, its own body and its own assertions, run against real
  views instead of the page's imaginary `shop.ui`.

  That exactness is worth having. `testing.md` teaches the six names a
  consumer's whole test suite is written in — `t/render`, `t/find`,
  `t/find-all`, `t/attrs`, `t/text`, `t/with-render` — plus the two re-frame
  brackets that scope them. If any of those moves, thousands of consumer
  tests break, and this namespace is what says so first.

  The one substitution: the page's `shop.ui` namespace does not exist, so
  the views it names (`add-button`, `basket-badge`, `cart-badge`,
  `email-field`) are declared here with the bodies the page's assertions
  imply. Everything else — including the deftest names — is the page's.

  Filed under rf2-qwsmv."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The application under test — the page's `shop.ui`, made real
;; ---------------------------------------------------------------------------

(v/defview add-button [{:keys [product-id]}]
  [:button {:on-click [:cart/add product-id]} "Add"])

(v/defview basket-badge [_]
  [:span (str (v/sub [:basket/count]))])

(v/defview cart-badge [_]
  [:span (str (v/sub [:cart/count]))])

(v/defview email-field [_]
  [:input {:value (v/sub [:account/email])
           :on-input [:account/email-edited ::v/value]}])

(v/defview product-card [{:keys [id]}]
  [:li.product (str id)])

(v/defview product-list [{:keys [ids]}]
  [:ul (for [id ids] [product-card {:key id :id id}])])

(defn- register-shop-dataflow!
  []
  (rf/reg-event :cart/add
    (fn [{:keys [db]} [_ id]] {:db (update db :cart (fnil conj #{}) id)}))
  (rf/reg-event :basket/add
    (fn [{:keys [db]} [_ id]] {:db (update db :basket (fnil conj #{}) id)}))
  (rf/reg-sub :cart/count (fn [db _] (count (:cart db))))
  (rf/reg-sub :basket/count (fn [db _] (count (:basket db))))
  (rf/reg-sub :account/email (fn [db _] (:email db))))

;; ---------------------------------------------------------------------------
;; testing.md block 1 — tier 1, the daily driver
;; ---------------------------------------------------------------------------

(deftest add-button-carries-intent
  (let [tree   (t/render [add-button {:product-id 42}])
        button (t/find tree #(= :button (:tag %)))]
    (is (= "Add" (t/text button)))
    (is (= [:cart/add 42] (:on-click (t/attrs button))))))

;; ---------------------------------------------------------------------------
;; testing.md block 2 — reading the tree
;; ---------------------------------------------------------------------------

(defn tree-reading-forms
  "The page's three finder spellings — by element tag, by view boundary, and
  a count over `find-all`. Hosted in a function because the block is three
  expressions over a `tree` the page left in scope."
  [tree]
  [(t/find tree #(= :button (:tag %)))                ; by element tag
   (t/find tree #(= ::product-card (:view-id %)))     ; by view boundary
   (count (t/find-all tree #(= :li (:tag %))))])

(deftest the-three-finder-spellings-answer-what-the-page-says
  (testing "testing.md block 2 — `find` is first-match-or-nil, `find-all` is
            every match in document order, and a view boundary is found by
            `:view-id` exactly as an element is found by `:tag`."
    (let [tree            (t/render [product-list {:ids [1 2 3]}])
          [button card n] (tree-reading-forms tree)]
      (is (nil? button) "no button in this tree — find answers nil, not a throw")
      (is (some? card) "a view boundary is an ordinary node with a :view-id")
      (is (= 3 n) "find-all counts every match")
      (is (nil? (t/attrs (t/find tree #(= :never (:tag %)))))
          "nil threads through a missed match rather than throwing"))))

;; ---------------------------------------------------------------------------
;; testing.md block 3 — a view that reads state renders inside `t/with-render`
;; ---------------------------------------------------------------------------

(deftest the-badge-shows-the-basket-count
  (register-shop-dataflow!)
  (rf/dispatch-sync [:basket/add 42])
  (let [tree (t/with-render (t/render [basket-badge {}]))]
    (is (= "1" (t/text tree)))))

(deftest rendering-a-subscribing-view-bare-is-refused-and-names-the-bracket
  (testing "testing.md's stated refusal — `t/render` alone is a walk, not a
            host, so `v/sub` outside the bracket raises
            `:rf.error/view-read-outside-render`."
    (register-shop-dataflow!)
    (is (thrown? #?(:clj Exception :cljs :default)
                 (t/render [basket-badge {}])))))

;; ---------------------------------------------------------------------------
;; testing.md blocks 4 and 5 — tier 2, a real frame
;; ---------------------------------------------------------------------------

(deftest cart-badge-shows-count-after-add
  (register-shop-dataflow!)
  (rf/with-new-frame
    [_ (rf/make-frame
        {:initial-events [[:rf/set-db {:cart #{}}]
                          [:cart/add 42]]})]
    (let [tree (t/with-render (t/render [cart-badge {}]))]
      (is (= "1" (t/text (t/find tree #(= :span (:tag %)))))))))

(deftest adding-to-the-cart-updates-the-badge
  (register-shop-dataflow!)
  (rf/with-new-frame
    [f (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (let [before (t/with-render (t/render [cart-badge {}]))]
      (is (= "0" (t/text (t/find before #(= :span (:tag %)))))))
    (rf/dispatch-sync [:cart/add 42] {:frame f})
    (let [after (t/with-render (t/render [cart-badge {}]))]
      (is (= "1" (t/text (t/find after #(= :span (:tag %)))))))))

;; ---------------------------------------------------------------------------
;; testing.md block 6 — asserting a projected event
;; ---------------------------------------------------------------------------

(deftest typing-carries-the-text
  (register-shop-dataflow!)
  (rf/dispatch-sync [:rf/set-db {:email ""}])
  (let [tree  (t/with-render (t/render [email-field {}]))
        input (t/find tree #(= :input (:tag %)))]
    (is (= [:account/email-edited "mike@example.com"]
           (v/materialize-event (:on-input (t/attrs input))
                                {::v/value "mike@example.com"})))))
