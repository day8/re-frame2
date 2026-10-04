(ns re-frame.resources-infinite-state-cljs-test
  "Pure state-transition unit tests for the durable infinite-feed entry
  refinement (Spec 016 §Durable cache shape (R1) / §Causal event — load-more
  (R2)).

  An infinite feed is the SAME `:rf/resource-entry` whose `:data` is the
  ordered page vector (R1 — no new entry kind). These tests lock the PURE
  transition fns the load-more event drives from the work-ledger reply path:

    - `empty-infinite-entry` seeds the feed facts (page vector / params /
      cursor / page-error);
    - `next-param-for` / `prev-param-for` derive the cursor from the
      accumulated pages (nil = the SINGLE terminal);
    - `entry-append-page` appends a fetched page + advances the cursor +
      returns to :loaded;
    - `entry-page-failed` is the THIRD error channel (keeps the feed,
      records :page-error);
    - `entry-replace-page` refreshes a page IN PLACE (R6 window-preserving
      refetch settle) — incl. the structural-sharing identical-value branch
      and the delegate-to-append-past-tail branch;
    - `refetch-window-count` is the pure R6 multi-page REFRESH-window policy
      (default page-0-only + the all-pages / windowed opt-ins + clamp edges);
    - `refetch-sweep-tail` / `entry-begin-refetch-sweep` /
      `entry-advance-refetch-sweep` / `clear-refetch-sweep` arm + drive the
      ordered multi-page sweep cursor (the pages beyond 0 a windowed/all-pages
      refetch re-fetches in sequence, replacing in place — never truncating);
    - `resolve-page->items` lifts the R3 accessor.

  The load-more EVENT + subs are out of scope here."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.resources.state :as rf.resources.state]))

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
    (let [e (rf.resources.state/empty-infinite-entry :feed/timeline [:rf.scope/global :feed/timeline {:filter :recent}])]
      (is (true? (:infinite? e)))
      (is (rf.resources.state/infinite-entry? e))
      (is (= [] (:data e))            "page vector seeded empty (not nil)")
      (is (= [] (:page-params e)))
      (is (nil? (:next-page-param e)))
      (is (nil? (:prev-page-param e)))
      (is (nil? (:page-error e)))
      (is (= :idle (:status e)))
      (testing "it is still an ordinary resource entry (R1 — no new kind)"
        (is (= :feed/timeline (:resource/id e)))
        (is (= [:rf.scope/global :feed/timeline {:filter :recent}] (:resource/key e)))
        (is (contains? e :revision))
        (is (contains? e :active-owners))))))

(deftest ordinary-entry-not-infinite
  (testing "an ordinary entry is not an infinite feed"
    (is (false? (rf.resources.state/infinite-entry? (rf.resources.state/empty-entry :res/plain))))))

;; ---- next-param-for / prev-param-for / terminal ----------------------------

(deftest next-param-derivation
  (testing "next-param-for derives the next cursor from the last page"
    (is (= "c1" (rf.resources.state/next-param-for next-cursor [(page [:a] "c1")])))
    (is (= "c2" (rf.resources.state/next-param-for next-cursor [(page [:a] "c1") (page [:b] "c2")]))
        "derives from the LAST page, not the first"))
  (testing "nil last-page next-cursor is the SINGLE terminal"
    (is (nil? (rf.resources.state/next-param-for next-cursor [(page [:a] nil)]))))
  (testing "an empty page vector yields nil (no last page to derive from)"
    (is (nil? (rf.resources.state/next-param-for next-cursor []))))
  (testing "no :next-page-param fn yields nil"
    (is (nil? (rf.resources.state/next-param-for nil [(page [:a] "c1")])))))

(deftest prev-param-derivation-mirror
  (testing "prev-param-for derives from the FIRST page (R7 mirror)"
    (let [pages [{:items [:a] :page-info {:prev-cursor "p0"}}
                 {:items [:b] :page-info {:prev-cursor "p1"}}]]
      (is (= "p0" (rf.resources.state/prev-param-for prev-cursor pages))
          "the prev cursor comes from the head, not the tail")))
  (testing "no :prev-page-param fn yields nil (mirror not declared)"
    (is (nil? (rf.resources.state/prev-param-for nil [(page [:a] "c1")])))))

;; ---- entry-append-page -----------------------------------------------------

(deftest append-first-page
  (testing "appending page-0 to an empty feed accumulates + advances the cursor"
    (let [e0 (rf.resources.state/empty-infinite-entry :feed/timeline)
          e1 (rf.resources.state/entry-append-page
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
      (is (> (:revision e1) (:revision e0)) "authoritative write bumps revision (EP-0019)"))))

(deftest append-multiple-pages-accumulates
  (testing "successive appends grow the feed in order + re-derive the cursor"
    (let [e (-> (rf.resources.state/empty-infinite-entry :feed/timeline)
                (rf.resources.state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1000 :stale-at 2000})
                (rf.resources.state/entry-append-page {:page (page [:b] "c2") :page-param "c1"
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1100 :stale-at 2100})
                (rf.resources.state/entry-append-page {:page (page [:c] "c3") :page-param "c2"
                                          :next-page-param-fn next-cursor
                                          :loaded-at 1200 :stale-at 2200}))]
      (is (= [(page [:a] "c1") (page [:b] "c2") (page [:c] "c3")] (:data e)))
      (is (= [nil "c1" "c2"] (:page-params e)) "one param per page, page-0 = nil")
      (is (= "c3" (:next-page-param e)))
      (is (= 1200 (:loaded-at e)) "loaded-at re-stamped each append"))))

;; ---- resolve-page->items (R3 accessor) -------------------------------------

(deftest page-accessor-resolution
  (testing "a keyword accessor lifts to its get fn"
    (let [acc (rf.resources.state/resolve-page->items :items)]
      (is (fn? acc))
      (is (= [:a :b] (acc {:items [:a :b]})))))
  (testing "a fn accessor passes through"
    (let [f (fn [p] (:rows p))
          acc (rf.resources.state/resolve-page->items f)]
      (is (= [:x] (acc {:rows [:x]})))))
  (testing "nil accessor resolves to nil (caller applies vector-identity / raises at merge)"
    (is (nil? (rf.resources.state/resolve-page->items nil))))
  (testing "a non-keyword / non-fn accessor resolves to nil"
    (is (nil? (rf.resources.state/resolve-page->items 99)))))

;; ---- entry-replace-page (R6 window-preserving in-place replace) ------------
;;
;; The settle a window-preserving refetch's replacement page-0 performs: the
;; feed never collapses; page 0 is refreshed in place and the tail is kept. The
;; event tests only ever replace page-0 with a DIFFERENT value, so the
;; structural-sharing branch (identical refetch keeps the OLD value identical)
;; is pinned here. The delegate-to-append branch (index past the tail) is the
;; path every load-more page takes, so the load-more suite pins it.

(defn- accumulated-3
  "A loaded 3-page infinite entry [p0 p1 p2] with cursors c1/c2/c3 and one
  param per page ([nil c1 c2]). Built via the same pure appends the event
  layer drives, so the structural-sharing assertions ride a realistic shape."
  []
  (-> (rf.resources.state/empty-infinite-entry :feed/timeline)
      (rf.resources.state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                :next-page-param-fn next-cursor
                                :loaded-at 1000 :stale-at 2000})
      (rf.resources.state/entry-append-page {:page (page [:b] "c2") :page-param "c1"
                                :next-page-param-fn next-cursor
                                :loaded-at 1100 :stale-at 2100})
      (rf.resources.state/entry-append-page {:page (page [:c] "c3") :page-param "c2"
                                :next-page-param-fn next-cursor
                                :loaded-at 1200 :stale-at 2200})))

(deftest replace-page-in-place-preserves-window
  (testing "replacing page-0 of a 3-page feed refreshes index 0 in place +
            keeps the accumulated tail (window-preserving R6 settle)"
    (let [e0 (accumulated-3)
          e1 (rf.resources.state/entry-replace-page
               e0 {:page (page [:a*] "c1") :page-param nil :page-index 0
                   :next-page-param-fn next-cursor
                   :loaded-at 9000 :stale-at 9999})]
      (is (= [(page [:a*] "c1") (page [:b] "c2") (page [:c] "c3")] (:data e1))
          "page-0 replaced in place; the tail is preserved and the feed NOT grown")
      (is (= [nil "c1" "c2"] (:page-params e1)) "page-0 param replaced in step; tail params kept")
      (is (= :loaded (:status e1)))
      (is (= 9000 (:loaded-at e1)) ":loaded-at re-stamped")
      (is (= 9999 (:stale-at e1)) ":stale-at re-stamped")
      (is (nil? (:current-work e1)) ":current-work cleared")
      (is (> (:revision e1) (:revision e0))
          "authoritative durable write bumps revision UNCONDITIONALLY (EP-0019)"))))

(deftest replace-page-structural-sharing-identical-value
  (testing "a refetch that returns an = page-0 keeps the OLD page value identical
            (identical?) so downstream stays quiet — only a different value is new"
    (let [e0       (accumulated-3)
          old-page (nth (:data e0) 0)
          ;; a freshly-decoded, structurally-EQUAL but NOT identical page-0
          fresh    (page [:a] "c1")]
      (is (= old-page fresh) "the fresh page is value-equal to the old page-0")
      (is (not (identical? old-page fresh)) "but it is a distinct object (a fresh decode)")
      (let [e1 (rf.resources.state/entry-replace-page
                 e0 {:page fresh :page-param nil :page-index 0
                     :next-page-param-fn next-cursor
                     :loaded-at 9000 :stale-at 9999})]
        (is (identical? old-page (nth (:data e1) 0))
            "structural sharing — the OLD page-0 object is retained, not the fresh decode")
        (is (identical? (nth (:data e0) 1) (nth (:data e1) 1))
            "untouched tail pages are shared by identity")
        (is (> (:revision e1) (:revision e0))
            "revision still bumps — the durable write happened even though the value shared"))))
  (testing "a DIFFERENT page-0 value is taken as-is (no spurious sharing)"
    (let [e0    (accumulated-3)
          fresh (page [:a*] "c1")
          e1    (rf.resources.state/entry-replace-page
                  e0 {:page fresh :page-param nil :page-index 0
                      :next-page-param-fn next-cursor
                      :loaded-at 9000 :stale-at 9999})]
      (is (identical? fresh (nth (:data e1) 0))
          "a value-distinct page is stored as the fresh object"))))

(deftest replace-page-recomputes-cursor-from-replaced-tail
  (testing "replacing the LAST page re-derives :next-page-param from the new tail"
    (let [e0 (accumulated-3)
          ;; replace the terminal page-2 with one whose next-cursor differs
          e1 (rf.resources.state/entry-replace-page
               e0 {:page (page [:c*] "c3*") :page-param "c2" :page-index 2
                   :next-page-param-fn next-cursor
                   :loaded-at 9000 :stale-at 9999})]
      (is (= "c3*" (:next-page-param e1)) "cursor recomputed from the replaced last page")
      (is (= 3 (rf.resources.state/page-count e1))))))

(deftest replace-page-clears-error-channels
  (testing "an in-place replace clears :page-error / :refresh-error (a fresh authoritative write)"
    (let [e0     (-> (accumulated-3)
                     (rf.resources.state/entry-page-failed {:error {:kind :rf.http/server :status 503}}))
          e1     (rf.resources.state/entry-replace-page
                   e0 {:page (page [:a*] "c1") :page-param nil :page-index 0
                       :next-page-param-fn next-cursor
                       :loaded-at 9000 :stale-at 9999})]
      (is (some? (:page-error e0)) "the prior failure recorded a page-error")
      (is (nil? (:page-error e1)) "the replace cleared it")
      (is (nil? (:refresh-error e1)))
      (is (nil? (:invalidated-at e1))))))

(deftest page-ops-on-a-nil-entry-are-noops
  ;; With no feed to write into, each pure page op returns nil unchanged.
  (testing "append"
    (is (nil? (rf.resources.state/entry-append-page nil {:page (page [:a] "c1")
                                            :next-page-param-fn next-cursor
                                            :loaded-at 1 :stale-at 2}))))
  (testing "page-failed"
    (is (nil? (rf.resources.state/entry-page-failed nil {:error {:kind :rf.http/server}}))))
  (testing "replace"
    (is (nil? (rf.resources.state/entry-replace-page nil {:page (page [:a] "c1") :page-index 0
                                             :next-page-param-fn next-cursor
                                             :loaded-at 1 :stale-at 2})))))

;; ---- refetch-window-count (R6 — multi-page refresh window) -----------------
;;
;; Spec 016 §Refetch defines the opt-ins as a multi-page REFRESH of the
;; accumulation IN SEQUENCE (NOT a truncate-the-tail): the default refreshes
;; page 0 in place; `:refetch-all-pages?` refreshes EVERY page; `:refetch-window
;; n` the first n. The accumulation length is preserved; its contents are
;; re-fetched.

(deftest refetch-window-count-by-policy
  (doseq [[label policy page-count expected]
          [["an empty feed refreshes 0 pages — nothing to refresh"
            nil 0 0]
           ["an empty feed refreshes 0 pages under :refetch-all-pages? too"
            {:refetch-all-pages? true} 0 0]
           ["a window over an empty feed is still 0 (zero-page short-circuits first)"
            {:refetch-window 5} 0 0]
           ["the DEFAULT (no policy) refreshes page 0 only — replace page 0 in place, keep the tail"
            nil 3 1]
           ["the DEFAULT (empty policy) refreshes page 0 only"
            {} 3 1]
           ["the DEFAULT on a one-page feed refreshes page 0"
            {} 1 1]
           [":refetch-all-pages? true refreshes EVERY page, not collapsed to page 0 (TanStack parity)"
            {:refetch-all-pages? true} 3 3]
           [":refetch-all-pages? true on a one-page feed refreshes that page"
            {:refetch-all-pages? true} 1 1]
           ["all-pages? wins over a co-present :refetch-window (cond order)"
            {:refetch-all-pages? true :refetch-window 2} 3 3]
           [":refetch-window n refreshes the first n pages (in-range window verbatim)"
            {:refetch-window 2} 3 2]
           ["CLAMP HIGH — a window beyond the page count clamps DOWN to it, never inventing pages"
            {:refetch-window 5} 3 3]
           ["a window equal to the page count is exact"
            {:refetch-window 3} 3 3]
           ["window 1 refreshes exactly page 0"
            {:refetch-window 1} 3 1]
           ["CLAMP LOW — window 0 clamps UP to 1, since a refetch always refreshes page 0"
            {:refetch-window 0} 3 1]
           ["CLAMP LOW — a negative window clamps UP to 1"
            {:refetch-window -4} 3 1]]]
    (testing label
      (is (= expected (rf.resources.state/refetch-window-count policy page-count))
          (str "policy " (pr-str policy) " over " page-count " pages")))))

;; ---- entry-begin / advance / clear refetch sweep (R6 cursor) ---------------

(deftest entry-begin-refetch-sweep-default-noop
  (testing "the window-preserving DEFAULT arms NO sweep cursor (returns the entry
            unchanged — a plain refetch refreshes page 0 only)"
    (let [e0 (accumulated-3)]
      (is (identical? e0 (rf.resources.state/entry-begin-refetch-sweep e0 nil)))
      (is (identical? e0 (rf.resources.state/entry-begin-refetch-sweep e0 {}))))))

(deftest entry-begin-refetch-sweep-opt-in-arms-cursor
  (testing ":refetch-all-pages? arms the cursor with pages 1..N-1 (issue-time
            page 0 excluded) WITHOUT touching :data / :status / :revision"
    (let [e0 (accumulated-3)
          e1 (rf.resources.state/entry-begin-refetch-sweep e0 {:refetch-all-pages? true})]
      (is (= [["c1" 1] ["c2" 2]] (:refetch-sweep e1)) "cursor armed with the sweep tail")
      (is (= (:data e0) (:data e1)) ":data UNTOUCHED — never truncated")
      (is (= (:status e0) (:status e1)))
      (is (= (:revision e0) (:revision e1)) "revision NOT bumped by arming the cursor")))
  (testing ":refetch-window 2 arms a cursor with only page 1"
    (let [e1 (rf.resources.state/entry-begin-refetch-sweep (accumulated-3) {:refetch-window 2})]
      (is (= [["c1" 1]] (:refetch-sweep e1))))))

(deftest entry-advance-and-clear-refetch-sweep
  (testing "advance pops the head leg; clearing the last leg removes the key"
    (let [e0 (rf.resources.state/entry-begin-refetch-sweep (accumulated-3) {:refetch-all-pages? true})]
      (is (= ["c1" 1] (rf.resources.state/next-refetch-sweep-leg e0)) "head leg is page 1")
      (let [e1 (rf.resources.state/entry-advance-refetch-sweep e0)]
        (is (= [["c2" 2]] (:refetch-sweep e1)) "page 1 popped, page 2 remains")
        (is (= ["c2" 2] (rf.resources.state/next-refetch-sweep-leg e1)))
        (let [e2 (rf.resources.state/entry-advance-refetch-sweep e1)]
          (is (not (contains? e2 :refetch-sweep)) "last leg popped ⇒ cursor removed")
          (is (nil? (rf.resources.state/next-refetch-sweep-leg e2)) "no next leg on an exhausted sweep")))))
  (testing "clear-refetch-sweep drops an in-progress cursor (a failed/aborted leg stops it)"
    (let [e0 (rf.resources.state/entry-begin-refetch-sweep (accumulated-3) {:refetch-all-pages? true})]
      (is (not (contains? (rf.resources.state/clear-refetch-sweep e0) :refetch-sweep)))))
  (testing "next-refetch-sweep-leg on an unarmed entry is nil"
    (is (nil? (rf.resources.state/next-refetch-sweep-leg (accumulated-3))))))
