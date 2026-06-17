(ns re-frame.resources-infinite-state-cljs-test
  "Pure state-transition unit tests for the durable infinite-feed entry
  refinement (EP-0021 wave 2, Spec 016 §Durable cache shape (R1) / §Causal
  event — load-more (R2)).

  An infinite feed is the SAME `:rf/resource-entry` whose `:data` is the
  ordered page vector (R1 — no new entry kind). These tests lock the PURE
  transition fns the wave-3 load-more event will drive from the work-ledger
  reply path:

    - `empty-infinite-entry` seeds the feed facts (page vector / params /
      cursor / page-error);
    - `next-param-for` / `prev-param-for` derive the cursor from the
      accumulated pages (nil = the SINGLE terminal);
    - `entry-append-page` appends a fetched page + advances the cursor +
      returns to :loaded;
    - `entry-page-failed` is the THIRD error channel (keeps the feed,
      records :page-error);
    - `resolve-page->items` lifts the R3 accessor.

  The load-more EVENT (wave 3) + subs (wave 4) are out of scope."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.resources.state :as state]))

(def ^:private next-cursor
  "A :next-page-param fn: read the next cursor off the last page's envelope;
  nil ⇒ terminal."
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  "A non-vector / enveloped page: items + a page-info cursor envelope."
  [items next-c]
  {:items items :page-info {:next-cursor next-c}})

;; ---- empty-infinite-entry --------------------------------------------------

(deftest empty-infinite-entry-shape
  (testing "an empty infinite entry carries the infinite facts (R1)"
    (let [e (state/empty-infinite-entry :feed/timeline [:rf.scope/global :feed/timeline {:filter :recent}])]
      (is (true? (:infinite? e)))
      (is (state/infinite-entry? e))
      (is (= [] (:data e))            "page vector seeded empty (not nil)")
      (is (= [] (:page-params e)))
      (is (nil? (:next-page-param e)))
      (is (nil? (:prev-page-param e)))
      (is (nil? (:page-error e)))
      (is (= :idle (:status e)))
      (is (= 0 (state/page-count e)))
      (testing "it is still an ordinary resource entry (R1 — no new kind)"
        (is (= :feed/timeline (:resource/id e)))
        (is (= [:rf.scope/global :feed/timeline {:filter :recent}] (:resource/key e)))
        (is (contains? e :revision))
        (is (contains? e :active-owners))))))

(deftest ordinary-entry-not-infinite
  (testing "an ordinary entry is not an infinite feed"
    (is (false? (state/infinite-entry? (state/empty-entry :res/plain))))))

;; ---- next-param-for / prev-param-for / terminal ----------------------------

(deftest next-param-derivation
  (testing "next-param-for derives the next cursor from the last page"
    (is (= "c1" (state/next-param-for next-cursor [(page [:a] "c1")])))
    (is (= "c2" (state/next-param-for next-cursor [(page [:a] "c1") (page [:b] "c2")]))
        "derives from the LAST page, not the first"))
  (testing "nil last-page next-cursor is the SINGLE terminal"
    (is (nil? (state/next-param-for next-cursor [(page [:a] nil)]))))
  (testing "an empty page vector yields nil (no last page to derive from)"
    (is (nil? (state/next-param-for next-cursor []))))
  (testing "no :next-page-param fn yields nil"
    (is (nil? (state/next-param-for nil [(page [:a] "c1")])))))

(deftest prev-param-derivation-mirror
  (testing "prev-param-for derives from the FIRST page (R7 mirror)"
    (let [pages [{:items [:a] :page-info {:prev-cursor "p0"}}
                 {:items [:b] :page-info {:prev-cursor "p1"}}]]
      (is (= "p0" (state/prev-param-for prev-cursor pages))
          "the prev cursor comes from the head, not the tail")))
  (testing "no :prev-page-param fn yields nil (mirror not declared)"
    (is (nil? (state/prev-param-for nil [(page [:a] "c1")])))))

(deftest terminal-rule
  (testing "terminal? is the nil-next-param rule (R8 single terminal)"
    (is (true? (state/terminal? nil)))
    (is (false? (state/terminal? "c1")))
    (is (false? (state/terminal? 0)) "0 is a legit cursor, not terminal")))

;; ---- entry-append-page -----------------------------------------------------

(deftest append-first-page
  (testing "appending page-0 to an empty feed accumulates + advances the cursor"
    (let [e0 (state/empty-infinite-entry :feed/timeline)
          e1 (state/entry-append-page
               e0 {:page (page [:a :b] "c1")
                   :page-param nil           ;; page-0 param is nil (initial)
                   :next-page-param-fn next-cursor
                   :loaded-at 1000 :stale-at 61000})]
      (is (= [(page [:a :b] "c1")] (:data e1)))
      (is (= [nil] (:page-params e1)))
      (is (= "c1" (:next-page-param e1)) "cursor advanced to next")
      (is (= :loaded (:status e1)))
      (is (= 1000 (:loaded-at e1)))
      (is (= 61000 (:stale-at e1)))
      (is (= 1 (state/page-count e1)))
      (is (> (:revision e1) (:revision e0)) "authoritative write bumps revision (EP-0019)"))))

(deftest append-multiple-pages-accumulates
  (testing "successive appends grow the feed in order + re-derive the cursor"
    (let [e (-> (state/empty-infinite-entry :feed/timeline)
                (state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1000 :stale-at 2000})
                (state/entry-append-page {:page (page [:b] "c2") :page-param "c1"
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1100 :stale-at 2100})
                (state/entry-append-page {:page (page [:c] "c3") :page-param "c2"
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1200 :stale-at 2200}))]
      (is (= [(page [:a] "c1") (page [:b] "c2") (page [:c] "c3")] (:data e)))
      (is (= [nil "c1" "c2"] (:page-params e)) "one param per page, page-0 = nil")
      (is (= "c3" (:next-page-param e)))
      (is (= 3 (state/page-count e)))
      (is (= 1200 (:loaded-at e)) "loaded-at re-stamped each append"))))

(deftest append-terminal-page
  (testing "a page whose next-cursor is nil sets :next-page-param nil (terminal)"
    (let [e (-> (state/empty-infinite-entry :feed/timeline)
                (state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1 :stale-at 2})
                (state/entry-append-page {:page (page [:b] nil) :page-param "c1"
                                          :next-page-param-fn next-cursor
                                          :loaded-at 3 :stale-at 4}))]
      (is (nil? (:next-page-param e)) "terminal — no more pages")
      (is (state/terminal? (:next-page-param e)))
      (is (= 2 (state/page-count e)) "the terminal page IS accumulated"))))

(deftest append-recomputes-prev-mirror
  (testing "append re-derives :prev-page-param from the head when a fn is supplied"
    (let [e (state/entry-append-page
              (state/empty-infinite-entry :feed/timeline)
              {:page {:items [:a] :page-info {:prev-cursor "p0"}}
               :page-param nil
               :next-page-param-fn (fn [_ _] nil)
               :prev-page-param-fn prev-cursor
               :loaded-at 1 :stale-at 2})]
      (is (= "p0" (:prev-page-param e))))))

(deftest append-clears-page-error
  (testing "a successful append clears a prior :page-error (load-more recovery)"
    (let [failed (-> (state/empty-infinite-entry :feed/timeline)
                     (state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                               :next-page-param-fn next-cursor
                                               :loaded-at 1 :stale-at 2})
                     (state/entry-page-failed {:error {:kind :rf.http/server :status 503}}))
          recovered (state/entry-append-page failed
                                              {:page (page [:b] "c2") :page-param "c1"
                                               :next-page-param-fn next-cursor
                                               :loaded-at 3 :stale-at 4})]
      (is (some? (:page-error failed)) "the failure recorded a page-error")
      (is (nil? (:page-error recovered)) "the next success cleared it")
      (is (= 2 (state/page-count recovered))))))

(deftest append-nil-entry-noop
  (testing "appending to a nil entry returns nil unchanged (no feed to append to)"
    (is (nil? (state/entry-append-page nil {:page (page [:a] "c1")
                                            :next-page-param-fn next-cursor
                                            :loaded-at 1 :stale-at 2})))))

;; ---- entry-page-failed (the THIRD error channel) ---------------------------

(deftest page-failure-keeps-feed
  (testing "a load-more failure keeps ALL pages + records :page-error (third channel)"
    (let [loaded (-> (state/empty-infinite-entry :feed/timeline)
                     (state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                               :next-page-param-fn next-cursor
                                               :loaded-at 1 :stale-at 2})
                     (state/entry-append-page {:page (page [:b] "c2") :page-param "c1"
                                               :next-page-param-fn next-cursor
                                               :loaded-at 3 :stale-at 4}))
          envelope {:kind :rf.http/server :status 503}
          failed (state/entry-page-failed loaded {:error envelope})]
      (is (= :loaded (:status failed)) "feed returns to :loaded, NOT :error")
      (is (= 2 (state/page-count failed)) "all accumulated pages kept")
      (is (= (:data loaded) (:data failed)) "page vector untouched")
      (is (= "c2" (:next-page-param failed)) "cursor untouched")
      (is (= envelope (:page-error failed)) ":page-error recorded")
      (is (nil? (:error failed)) "NOT the first-load :error channel")
      (is (nil? (:refresh-error failed)) "NOT the refresh :refresh-error channel")
      (is (nil? (:current-work failed)) ":current-work cleared"))))

(deftest page-failed-nil-entry-noop
  (testing "page-failed on a nil entry returns nil unchanged"
    (is (nil? (state/entry-page-failed nil {:error {:kind :rf.http/server}})))))

;; ---- resolve-page->items (R3 accessor) -------------------------------------

(deftest page-accessor-resolution
  (testing "a keyword accessor lifts to its get fn"
    (let [acc (state/resolve-page->items :items)]
      (is (fn? acc))
      (is (= [:a :b] (acc {:items [:a :b]})))))
  (testing "a fn accessor passes through"
    (let [f (fn [p] (:rows p))
          acc (state/resolve-page->items f)]
      (is (= [:x] (acc {:rows [:x]})))))
  (testing "nil accessor resolves to nil (caller applies vector-identity / raises at merge)"
    (is (nil? (state/resolve-page->items nil))))
  (testing "a non-keyword / non-fn accessor resolves to nil"
    (is (nil? (state/resolve-page->items 99)))))

;; ---- page-param-for-spec (page-0 cursor) -----------------------------------

(deftest page-0-param-default
  (testing "page-0 param defaults to nil (TanStack initialPageParam analogue)"
    (is (nil? (state/page-param-for-spec {})))
    (is (nil? (state/page-param-for-spec {:initial-page-param nil}))))
  (testing "an :initial-page-param override is honoured"
    (is (= "p0" (state/page-param-for-spec {:initial-page-param "p0"})))))
