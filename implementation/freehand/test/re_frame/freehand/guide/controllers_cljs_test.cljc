(ns re-frame.freehand.guide.controllers-cljs-test
  "Executable fixtures for `docs/core/freehand/semantic-controllers.md`.

  That page is the one a component-library author reads, and it is the one
  place the guide teaches three verbs an application never touches:
  `v/controller-key`, `v/controller-revision` and `v/controller-current?`.
  Their contracts are subtle — the key is a PAIR, the revision is the
  caller's and must not be the value, and the fence is total and SAFE IN THE
  MISSING DIRECTION — so the page's claims are asserted here against the real
  functions rather than restated.

  Transcription conventions are the ones in
  `re-frame.freehand.guide.first-view-cljs-test`. Two extra notes:

  - The page shows `rf/reg-sub` / `rf/reg-event` at a consumer namespace's
    top level. Here they are hosted in a `register-…!` function, because a
    test namespace's runtime is minted per test rather than at load.
  - Two blocks declare `line-row` / `lines-table` with different bodies (the
    live-domain wiring, then the buffered one). The fixture vars are
    suffixed `-live` / `-buffered`; the roster names the fixture var.

  Filed under rf2-qwsmv."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The library sketch the whole page builds toward
;; ---------------------------------------------------------------------------

;; semantic-controllers.md block 14 — `buffered-field`, which is a normal
;; `v/defview` plus ordinary library events and subs, and NOT a substrate
;; special form. Declared first here so the earlier call-site blocks can name
;; it; the page introduces it last because it earns it last.
(defn register-buffered-field-dataflow!
  []
  ;; ---- library dataflow ------------------------------------------------
  (rf/reg-sub :fh.buffer/value
    (fn [db [_ k revision baseline]]
      (let [record (get-in db [:fh.buffer/by-key k])]
        (if (v/controller-current? (:reset-key record) revision)
          (:draft record)
          (or baseline "")))))

  (rf/reg-event :fh.buffer/edited
    (fn [{:keys [db]} [_ k revision text]]
      {:db (assoc-in db [:fh.buffer/by-key k]
                     {:reset-key revision :draft text})}))

  (rf/reg-event :fh.buffer/committed
    (fn [{:keys [db]} [_ k revision on-commit]]
      (let [record (get-in db [:fh.buffer/by-key k])]
        (if (v/controller-current? (:reset-key record) revision)
          {:db (update db :fh.buffer/by-key dissoc k)
           :fx [[:dispatch (conj (vec on-commit) (:draft record))]]}
          {}))))   ; superseded generation / already cancelled → no-op

  (rf/reg-event :fh.buffer/cancelled
    (fn [{:keys [db]} [_ k revision]]
      (let [record (get-in db [:fh.buffer/by-key k])]
        (if (v/controller-current? (:reset-key record) revision)
          {:db (update db :fh.buffer/by-key dissoc k)}
          {})))))

;; ---- the control ---------------------------------------------------------
;; The view asks for its record key and its generation FIRST and never
;; destructures `:control` or `:reset-key` itself — the two verbs are the only
;; door onto them, which is what buys the four refusals the page teaches.
(v/defview buffered-field
  [{:keys [value on-commit placeholder] :as props}]
  (let [k        (v/controller-key ::buffered-field props)
        revision (v/controller-revision ::buffered-field props)
        text     (sub [:fh.buffer/value k revision value])]
    [:input
     {:value       text
      :placeholder placeholder
      :on-input    [:fh.buffer/edited k revision ::v/value]
      :on-blur     [:fh.buffer/committed k revision on-commit]
      :on-key-down (v/event [e]
                     (case (.-key e)
                       "Enter"  [:fh.buffer/committed k revision on-commit]
                       "Escape" [:fh.buffer/cancelled k revision]
                       nil))}]))

;; ---------------------------------------------------------------------------
;; semantic-controllers.md — the page in order
;; ---------------------------------------------------------------------------

;; block 1 — you can just use `on-blur`: valid Freehand, no controller.
(v/defview keymap-field [{:keys [id]}]
  [:input {:value   (v/sub [:lines/draft id])
           :on-input [:lines/draft-changed id ::v/value]
           :on-blur  [:lines/label-committed id]
           :on-key-down (v/event [e]
                          (case (.-key e)
                            "Enter"  [:lines/label-committed id]
                            "Escape" [:lines/draft-cancelled id]
                            nil))}])

;; block 2 — most controls should be props-only / controlled, and a
;; disclosure is the archetype.
(v/defview disclosure [{:keys [open? on-toggle label children]}]
  [:section.disclosure
   [:button {:aria-expanded open? :on-click on-toggle} label]
   (when open? [:div.body children])])

(v/defview faq-disclosure [{:keys [question-id]}]
  [disclosure
   {:open?     (v/sub [:faq/open? question-id])
    :on-toggle [:faq/toggled question-id]
    :label     "Why?"}])

;; block 3 — a debounced, generation-fenced search, entirely in ordinary
;; re-frame2: the view holds no protocol state at all.
(defn register-search-dataflow!
  []
  ;; Each keystroke updates the query string (controlled field A) and kicks search.
  ;; re-frame2: :dispatch-later is an fx id; the map key for the event is :event
  ;; (not v1's :dispatch).
  (rf/reg-event :search/query-typed
    (fn [{:keys [db]} [_ text]]
      (let [gen (inc (get-in db [:search :generation] 0))]
        {:db (-> db
                 (assoc-in [:search :query] text)
                 (assoc-in [:search :generation] gen)
                 (assoc-in [:search :status] :pending))
         :fx [[:dispatch-later
               {:ms 200
                :event [:search/run gen text]}]]})))

  (rf/reg-event :search/run
    (fn [{:keys [db]} [_ gen text]]
      (if (not= gen (get-in db [:search :generation]))
        {}   ; superseded — do nothing
        {:fx [[:search/fetch {:q text :gen gen}]]})))

  (rf/reg-event :search/results-arrived
    (fn [{:keys [db]} [_ gen results]]
      (if (not= gen (get-in db [:search :generation]))
        {}   ; stale network reply
        {:db (-> db
                 (assoc-in [:search :results] results)
                 (assoc-in [:search :status] :ready))}))))

;; block 4 — and the view that drives it stays an ordinary controlled field.
(v/defview result-row [{:keys [id]}]
  [:li.result (str "result " id)])

(v/defview search-box [_]
  [:div.search
   [:input {:value (v/sub [:search/query])
            :on-input [:search/query-typed ::v/value]
            :aria-autocomplete :list}]
   [:ul
    (for [id (v/sub [:search/result-ids])]
      [result-row {:key id :id id}])]])

;; block 7 (and state.md block 6) — the call site a semantic controller
;; earns: an address, a value, a generation and an intent.
(v/defview buffered-field-call [{:keys [invoice-id]}]
  [buffered-field
   {:control   [:invoice invoice-id :amount]
    :value     (v/sub [:invoice/amount invoice-id])
    :reset-key (v/sub [:invoice/amount-revision invoice-id])
    :on-commit [:invoice/amount-committed invoice-id]}])

;; block 8 — the live-domain wiring, per row.
(v/defview line-row-live [{:keys [line-id]}]
  (let [label (v/sub [:lines/label line-id])]
    [:tr
     [:td
      [:input {:value    label
               :on-input [:lines/set-label line-id ::v/value]}]]]))

(v/defview lines-table-live [_]
  [:table
   [:tbody
    (for [id (v/sub [:lines/visible-ids])]
      [line-row-live {:key id :line-id id}])]])

;; block 9 — the same table with the buffered controller, and the `:control`
;; address that is unique per row while `:key` answers list identity.
(v/defview line-row-buffered [{:keys [line-id]}]
  (let [label    (v/sub [:lines/label line-id])
        revision (v/sub [:lines/label-revision line-id])]
    [:tr
     [:td
      [buffered-field
       {:control   [:line line-id :label]   ;; unique per row
        :value     label
        :reset-key revision
        :on-commit [:lines/label-committed line-id]}]]]))

(v/defview lines-table-buffered [_]
  [:table
   [:tbody
    (for [id (v/sub [:lines/visible-ids])]
      [line-row-buffered {:key id :line-id id}])]])

;; block 13 — the fence at both boundaries of a buffered controller. The
;; READ is a registration; the WRITE is a fragment of an event handler, so
;; it is hosted in the function whose free names the page left in scope.
(def records-root :acme.buffered/by-control)

(defn register-acme-buffered-text!
  []
  ;; the READ — display the draft only while it is current
  (rf/reg-sub :acme.buffered/text
    (fn [db [_ k revision baseline]]
      (let [record (get-in db [records-root k])]
        (if (v/controller-current? (:reset-key record) revision)
          (:draft record)
          baseline)))))

(defn commit-when-current
  [record revision on-commit]
  ;; the WRITE — only a current record may produce the caller's intent
  (when (v/controller-current? (:reset-key record) revision)
    {:fx [[:dispatch (conj on-commit (:draft record))]]}))

;; ---------------------------------------------------------------------------
;; The samples, executed
;; ---------------------------------------------------------------------------

(defn- seed!
  [db]
  (register-buffered-field-dataflow!)
  (register-acme-buffered-text!)
  (rf/reg-sub :lines/draft (fn [d [_ id]] (get-in d [:drafts id])))
  (rf/reg-sub :lines/label (fn [d [_ id]] (get-in d [:labels id])))
  (rf/reg-sub :lines/label-revision (fn [d [_ id]] (get-in d [:revisions id])))
  (rf/reg-sub :lines/visible-ids (fn [d _] (:line-ids d)))
  (rf/reg-sub :faq/open? (fn [d [_ id]] (get-in d [:open id])))
  (rf/reg-sub :search/query (fn [d _] (get-in d [:search :query])))
  (rf/reg-sub :search/result-ids (fn [d _] (get-in d [:search :result-ids])))
  (rf/reg-sub :invoice/amount (fn [d [_ id]] (get-in d [:invoices id :amount])))
  (rf/reg-sub :invoice/amount-revision (fn [d [_ id]] (get-in d [:invoices id :rev])))
  (rf/dispatch-sync [:rf/set-db db]))

(deftest the-controller-key-is-the-pair-of-kind-and-address
  (testing "semantic-controllers.md block 10 — the key is the library's own
            kind PAIRED with the caller's `:control` address, so two
            controllers at one domain identity read two records."
    (let [props {:control [:invoice 42 :amount] :reset-key 0}]
      (is (= [::buffered-field [:invoice 42 :amount]]
             (v/controller-key ::buffered-field props))
          "exactly the pair the page prints")
      (is (not= (v/controller-key ::buffered-field props)
                (v/controller-key ::some-dropdown props))
          "the kind is half the key — a dropdown does not read the field's record"))))

(deftest the-controller-revision-is-the-callers-reset-key
  (testing "semantic-controllers.md block 11 — the generation is the
            CALLER's `:reset-key`, whatever EDN it likes, and it is
            required rather than optional."
    (is (= 7 (v/controller-revision ::buffered-field
                                    {:control [:invoice 42 :amount] :reset-key 7})))
    (is (= [:decision 3] (v/controller-revision ::buffered-field
                                                 {:control [:x] :reset-key [:decision 3]}))
        "any EDN — the fence only ever asks whether two are equal")
    (is (thrown? #?(:clj Exception :cljs :default)
                 (v/controller-revision ::buffered-field {:control [:x]}))
        "absent is refused, because optional would ship a defect that only
         shows up at the first rejection")))

(deftest the-generation-fence-is-total-and-safe-when-the-stamp-is-missing
  (testing "semantic-controllers.md block 12 — one predicate at both
            boundaries, and the half a hand-rolled `(= a b)` gets wrong."
    (is (true? (v/controller-current? 3 3)) "equal generations are current")
    (is (false? (v/controller-current? 2 3)) "a superseded stamp is not")
    (is (false? (v/controller-current? nil 3))
        "an absent stamp is NOT current — two nils must not compare equal here")))

(deftest the-fence-decides-what-the-read-shows
  (testing "block 13's READ, run through the registration the page shows:
            a current record displays its draft, a superseded one falls
            through to the baseline and is invisible rather than erased."
    (seed! {records-root {[:k] {:reset-key 1 :draft "mid-edit"}}})
    (is (= "mid-edit" @(rf/subscribe [:acme.buffered/text [:k] 1 "baseline"]))
        "current — the draft shows")
    (is (= "baseline" @(rf/subscribe [:acme.buffered/text [:k] 2 "baseline"]))
        "the caller moved on — the draft is invisible, the baseline shows")
    (is (= "baseline" @(rf/subscribe [:acme.buffered/text [:missing] 1 "baseline"]))
        "no record at all — total, and safe in the missing direction")))

(deftest the-write-half-asks-the-same-question
  (testing "block 13's WRITE — only a current record produces the caller's
            intent, and the draft rides on the end of the prefix."
    (is (= {:fx [[:dispatch [:lines/label-committed 5 "Alpha"]]]}
           (commit-when-current {:reset-key 1 :draft "Alpha"} 1
                                [:lines/label-committed 5])))
    (is (nil? (commit-when-current {:reset-key 1 :draft "Alpha"} 2
                                   [:lines/label-committed 5]))
        "a superseded record commits nothing")))

(deftest a-buffered-row-addresses-its-own-record
  (testing "blocks 8 and 9 side by side — the live table writes the domain
            value per keystroke, the buffered one addresses a per-row
            record and commits on blur."
    (seed! {:line-ids [5 12]
            :labels {5 "five" 12 "twelve"}
            :revisions {5 0 12 2}})
    (let [live (t/with-render (t/render [lines-table-live {}]))]
      (is (= [[:lines/set-label 5 ::v/value] [:lines/set-label 12 ::v/value]]
             (mapv #(:on-input (t/attrs %))
                   (t/find-all live #(= :input (:tag %)))))
          "the live wiring writes the domain value straight from the site"))
    (let [buffered (t/with-render (t/render [lines-table-buffered {}]))
          inputs   (t/find-all buffered #(= :input (:tag %)))]
      (is (= [[:fh.buffer/edited [::buffered-field [:line 5 :label]] 0 ::v/value]
              [:fh.buffer/edited [::buffered-field [:line 12 :label]] 2 ::v/value]]
             (mapv #(:on-input (t/attrs %)) inputs))
          "each row edits its OWN record, addressed by the (kind, address) pair
           `v/controller-key` answers, under its own generation")
      (is (= ["five" "twelve"] (mapv #(:value (t/attrs %)) inputs))
          "and with no draft in flight both fall through to the domain value"))))

(deftest a-props-only-disclosure-needs-no-controller
  (testing "block 2 — the page's own advice, executed: open state is the
            caller's, and the control holds nothing."
    (seed! {:open {:q1 true}})
    (let [tree (t/with-render (t/render [faq-disclosure {:question-id :q1}]))
          btn  (t/find tree #(= :button (:tag %)))]
      (is (true? (:aria-expanded (t/attrs btn))))
      (is (= [:faq/toggled :q1] (:on-click (t/attrs btn)))))))

(deftest the-pages-earlier-blocks-render-and-run-as-written
  (testing "semantic-controllers.md builds its argument from blocks 1, 3, 4
            and 7, and each was transcribed but never rendered — which
            proves nothing, because a bad site is a RENDER-time refusal
            (rf2-kem4o)."
    (seed! {:drafts {7 "typed"}
            :search {:query "ada" :result-ids [1 2]}
            :invoices {9 {:amount "120.00" :rev 3}}})

    (testing "block 1 — plain `on-blur` is valid Freehand and needs no controller"
      (let [attrs (t/attrs (t/find (t/with-render (t/render [keymap-field {:id 7}]))
                                   #(= :input (:tag %))))]
        (is (= "typed" (:value attrs)))
        (is (= [:lines/label-committed 7] (:on-blur attrs)))
        (is (= {:rf.ui/opaque :fn} (:on-key-down attrs))
            "the keymap rides as a carrier, opaque in the tree")))

    (testing "block 4 — the view driving the debounced search stays ordinary"
      (let [tree (t/with-render (t/render [search-box {}]))]
        (is (= "ada" (:value (t/attrs (t/find tree #(= :input (:tag %)))))))
        (is (= [:search/query-typed ::v/value]
               (:on-input (t/attrs (t/find tree #(= :input (:tag %)))))))
        (is (= ["result 1" "result 2"]
               (mapv t/text (t/find-all tree #(= :li (:tag %)))))
            "and the results are ordinary keyed children")))

    (testing "block 7 — the call site a semantic controller earns"
      (let [attrs (t/attrs (t/find (t/with-render (t/render [buffered-field-call
                                                             {:invoice-id 9}]))
                                   #(= :input (:tag %))))]
        (is (= "120.00" (:value attrs))
            "no draft in flight — the read falls through to the domain value")
        (is (= [:fh.buffer/edited [::buffered-field [:invoice 9 :amount]] 3 ::v/value]
               (:on-input attrs))
            "and the record is addressed by the (kind, address) pair under its
             own generation")))))

(deftest the-debounced-search-fences-its-own-generation
  (testing "semantic-controllers.md block 3 — the whole claim is about what
            the handlers DO with a generation, so the block is registered
            and run rather than merely declared."
    (let [later (atom [])]
      (rf/reg-fx :guide/captured-later (fn [_ v] (swap! later conj v)))
      (register-search-dataflow!)
      (rf/dispatch-sync [:rf/set-db {}])
      (rf/with-fx-overrides {:dispatch-later :guide/captured-later}
        (rf/dispatch-sync [:search/query-typed "ad"])
        (rf/dispatch-sync [:search/query-typed "ada"]))
      (is (= [{:ms 200 :event [:search/run 1 "ad"]}
              {:ms 200 :event [:search/run 2 "ada"]}]
             @later)
          "each keystroke kicks its own generation, and `:event` is the v2 key")
      (rf/reg-sub :guide/search (fn [db _] (:search db)))
      (is (= {:query "ada" :generation 2 :status :pending} @(rf/subscribe [:guide/search])))
      (rf/dispatch-sync [:search/results-arrived 1 [:stale]])
      (is (= :pending (:status @(rf/subscribe [:guide/search])))
          "a stale reply is dropped by the fence")
      (rf/dispatch-sync [:search/results-arrived 2 [:fresh]])
      (is (= {:query "ada" :generation 2 :status :ready :results [:fresh]}
             @(rf/subscribe [:guide/search]))
          "and the current one lands"))))
