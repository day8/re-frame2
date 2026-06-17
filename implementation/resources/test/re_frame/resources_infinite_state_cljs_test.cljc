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
    - `entry-replace-page` refreshes a page IN PLACE (R6 window-preserving
      refetch settle) — incl. the structural-sharing identical-value branch
      and the delegate-to-append-past-tail branch;
    - `refetch-window-count` is the pure R6 multi-page REFRESH-window policy
      (default page-0-only + the all-pages / windowed opt-ins + clamp edges;
      rf2-byl7bk.3.3 — replaces the old truncate-the-tail keep-count);
    - `refetch-sweep-tail` / `entry-begin-refetch-sweep` /
      `entry-advance-refetch-sweep` / `clear-refetch-sweep` arm + drive the
      ordered multi-page sweep cursor (the pages beyond 0 a windowed/all-pages
      refetch re-fetches in sequence, replacing in place — never truncating);
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

;; ---- entry-replace-page (R6 window-preserving in-place replace) ------------
;;
;; The settle a window-preserving refetch's replacement page-0 performs: the
;; feed never collapses; page 0 is refreshed in place and the tail is kept. The
;; event tests only ever replace page-0 with a DIFFERENT value, so the
;; structural-sharing branch (identical refetch keeps the OLD value identical)
;; and the delegate-to-append branch (index past the tail) are pinned here.

(defn- accumulated-3
  "A loaded 3-page infinite entry [p0 p1 p2] with cursors c1/c2/c3 and one
  param per page ([nil c1 c2]). Built via the same pure appends the event
  layer drives, so the structural-sharing assertions ride a realistic shape."
  []
  (-> (state/empty-infinite-entry :feed/timeline)
      (state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                :next-page-param-fn next-cursor
                                :loaded-at 1000 :stale-at 2000})
      (state/entry-append-page {:page (page [:b] "c2") :page-param "c1"
                                :next-page-param-fn next-cursor
                                :loaded-at 1100 :stale-at 2100})
      (state/entry-append-page {:page (page [:c] "c3") :page-param "c2"
                                :next-page-param-fn next-cursor
                                :loaded-at 1200 :stale-at 2200})))

(deftest replace-page-in-place-preserves-window
  (testing "replacing page-0 of a 3-page feed refreshes index 0 in place +
            keeps the accumulated tail (window-preserving R6 settle)"
    (let [e0 (accumulated-3)
          e1 (state/entry-replace-page
               e0 {:page (page [:a*] "c1") :page-param nil :page-index 0
                   :next-page-param-fn next-cursor
                   :loaded-at 9000 :stale-at 9999})]
      (is (= 3 (state/page-count e1)) "feed NOT grown — replace, not append")
      (is (= (page [:a*] "c1") (nth (:data e1) 0)) "page-0 replaced with the fresh value")
      (is (= (page [:b] "c2") (nth (:data e1) 1)) "tail page-1 preserved")
      (is (= (page [:c] "c3") (nth (:data e1) 2)) "tail page-2 preserved")
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
      (let [e1 (state/entry-replace-page
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
          e1    (state/entry-replace-page
                  e0 {:page fresh :page-param nil :page-index 0
                      :next-page-param-fn next-cursor
                      :loaded-at 9000 :stale-at 9999})]
      (is (identical? fresh (nth (:data e1) 0))
          "a value-distinct page is stored as the fresh object"))))

(deftest replace-page-recomputes-cursor-from-replaced-tail
  (testing "replacing the LAST page re-derives :next-page-param from the new tail"
    (let [e0 (accumulated-3)
          ;; replace the terminal page-2 with one whose next-cursor differs
          e1 (state/entry-replace-page
               e0 {:page (page [:c*] "c3*") :page-param "c2" :page-index 2
                   :next-page-param-fn next-cursor
                   :loaded-at 9000 :stale-at 9999})]
      (is (= "c3*" (:next-page-param e1)) "cursor recomputed from the replaced last page")
      (is (= 3 (state/page-count e1))))))

(deftest replace-page-clears-error-channels
  (testing "an in-place replace clears :page-error / :refresh-error (a fresh authoritative write)"
    (let [e0     (-> (accumulated-3)
                     (state/entry-page-failed {:error {:kind :rf.http/server :status 503}}))
          e1     (state/entry-replace-page
                   e0 {:page (page [:a*] "c1") :page-param nil :page-index 0
                       :next-page-param-fn next-cursor
                       :loaded-at 9000 :stale-at 9999})]
      (is (some? (:page-error e0)) "the prior failure recorded a page-error")
      (is (nil? (:page-error e1)) "the replace cleared it")
      (is (nil? (:refresh-error e1)))
      (is (nil? (:invalidated-at e1))))))

(deftest replace-page-past-tail-delegates-to-append
  (testing "a replace at page-index >= page-count is an APPEND (replacement past the
            tail — e.g. a window-preserving refetch of a feed emptied to page 0)"
    (let [e0 (-> (state/empty-infinite-entry :feed/timeline)
                 (state/entry-append-page {:page (page [:a] "c1") :page-param nil
                                           :next-page-param-fn next-cursor
                                           :loaded-at 1 :stale-at 2}))
          ;; page-index 1 == page-count (1) → delegates to entry-append-page
          e1 (state/entry-replace-page
               e0 {:page (page [:b] "c2") :page-param "c1" :page-index 1
                   :next-page-param-fn next-cursor
                   :loaded-at 3 :stale-at 4})]
      (is (= 2 (state/page-count e1)) "the page was APPENDED (feed grew), not replaced")
      (is (= [(page [:a] "c1") (page [:b] "c2")] (:data e1)))
      (is (= [nil "c1"] (:page-params e1)) "params appended in step")
      (is (= "c2" (:next-page-param e1)) "cursor advanced from the appended tail")))
  (testing "a replace into an EMPTY feed at index 0 appends page-0"
    (let [e0 (state/empty-infinite-entry :feed/timeline)
          e1 (state/entry-replace-page
               e0 {:page (page [:a] "c1") :page-param nil :page-index 0
                   :next-page-param-fn next-cursor
                   :loaded-at 1 :stale-at 2})]
      (is (= 1 (state/page-count e1)) "index 0 == count 0 → append page-0")
      (is (= [(page [:a] "c1")] (:data e1))))))

(deftest replace-page-nil-entry-noop
  (testing "replace on a nil entry returns nil unchanged (no feed to replace into)"
    (is (nil? (state/entry-replace-page nil {:page (page [:a] "c1") :page-index 0
                                             :next-page-param-fn next-cursor
                                             :loaded-at 1 :stale-at 2})))))

;; ---- refetch-window-count (R6 — multi-page refresh window, rf2-byl7bk.3.3) --
;;
;; Spec 016 §Refetch defines the opt-ins as a multi-page REFRESH of the
;; accumulation IN SEQUENCE (NOT a truncate-the-tail): the default refreshes
;; page 0 in place; `:refetch-all-pages?` refreshes EVERY page; `:refetch-window
;; n` the first n. The accumulation length is preserved; its contents are
;; re-fetched. These tests assert the new contract (the prior tests pinned the
;; truncate-to-page-0 BUG — rf2-byl7bk.3.3).

(deftest refetch-window-count-empty-feed
  (testing "an empty feed (page-count 0) refreshes 0 pages — nothing to refresh"
    (is (= 0 (state/refetch-window-count nil 0)))
    (is (= 0 (state/refetch-window-count {:refetch-all-pages? true} 0)))
    (is (= 0 (state/refetch-window-count {:refetch-window 5} 0))
        "window over an empty feed is still 0 (zero-page short-circuits first)")))

(deftest refetch-window-count-default-refreshes-page-0-only
  (testing "the ruled DEFAULT (no policy / empty policy) refreshes PAGE 0 only —
            the window-preserving default (replace page 0 in place, keep tail)"
    (is (= 1 (state/refetch-window-count nil 3)))
    (is (= 1 (state/refetch-window-count {} 3)))
    (is (= 1 (state/refetch-window-count {} 1)))))

(deftest refetch-window-count-all-pages-opt-in
  (testing ":refetch-all-pages? true refreshes EVERY accumulated page (TanStack parity)"
    (is (= 3 (state/refetch-window-count {:refetch-all-pages? true} 3))
        "all 3 pages refreshed (not collapsed to page 0)")
    (is (= 1 (state/refetch-window-count {:refetch-all-pages? true} 1)))
    (testing "all-pages? wins over a co-present :refetch-window (cond order)"
      (is (= 3 (state/refetch-window-count {:refetch-all-pages? true :refetch-window 2} 3))))))

(deftest refetch-window-count-window-clamps
  (testing ":refetch-window n refreshes the first n pages"
    (is (= 2 (state/refetch-window-count {:refetch-window 2} 3)) "in-range window verbatim"))
  (testing "CLAMP HIGH — a window beyond the page count never invents pages"
    (is (= 3 (state/refetch-window-count {:refetch-window 5} 3))
        "window > page-count clamps DOWN to page-count")
    (is (= 3 (state/refetch-window-count {:refetch-window 3} 3)) "window == page-count is exact"))
  (testing "CLAMP LOW — a refetch always refreshes at least page 0"
    (is (= 1 (state/refetch-window-count {:refetch-window 1} 3)) "window 1 refreshes exactly page 0")
    (is (= 1 (state/refetch-window-count {:refetch-window 0} 3)) "window 0 clamps UP to 1")
    (is (= 1 (state/refetch-window-count {:refetch-window -4} 3)) "a negative window clamps UP to 1")))

;; ---- refetch-sweep-tail (R6 — the ordered pages beyond 0 to re-fetch) ------

(deftest refetch-sweep-tail-default-is-empty
  (testing "the window-preserving DEFAULT starts NO sweep — page 0 is the
            issue-time replacement; there is no tail to chain"
    (is (= [] (state/refetch-sweep-tail (accumulated-3) nil)))
    (is (= [] (state/refetch-sweep-tail (accumulated-3) {})))))

(deftest refetch-sweep-tail-all-pages
  (testing ":refetch-all-pages? sweeps pages 1..N-1 in order (page 0 is issue-time),
            each pair carrying that page's durable :page-param"
    (let [tail (state/refetch-sweep-tail (accumulated-3) {:refetch-all-pages? true})]
      ;; accumulated-3 :page-params == [nil "c1" "c2"]
      (is (= [["c1" 1] ["c2" 2]] tail)
          "pages 1 and 2, each with its original param + index, in order"))))

(deftest refetch-sweep-tail-window
  (testing ":refetch-window 2 sweeps only page 1 (the bounded leading window
            minus the issue-time page 0)"
    (is (= [["c1" 1]] (state/refetch-sweep-tail (accumulated-3) {:refetch-window 2})))
    (testing ":refetch-window 1 is page-0-only ⇒ empty tail (no sweep)"
      (is (= [] (state/refetch-sweep-tail (accumulated-3) {:refetch-window 1}))))))

(deftest refetch-sweep-tail-guard-arms
  (testing "a nil / non-infinite / empty-feed entry yields an empty tail"
    (is (= [] (state/refetch-sweep-tail nil {:refetch-all-pages? true})))
    (is (= [] (state/refetch-sweep-tail (state/empty-entry :res/plain) {:refetch-all-pages? true})))
    (is (= [] (state/refetch-sweep-tail (state/empty-infinite-entry :feed/timeline)
                                        {:refetch-all-pages? true})))))

;; ---- entry-begin / advance / clear refetch sweep (R6 cursor) ---------------

(deftest entry-begin-refetch-sweep-default-noop
  (testing "the window-preserving DEFAULT arms NO sweep cursor (returns the entry
            unchanged — a plain refetch refreshes page 0 only)"
    (let [e0 (accumulated-3)]
      (is (identical? e0 (state/entry-begin-refetch-sweep e0 nil)))
      (is (identical? e0 (state/entry-begin-refetch-sweep e0 {})))
      (is (not (contains? (state/entry-begin-refetch-sweep e0 nil) :refetch-sweep))))))

(deftest entry-begin-refetch-sweep-opt-in-arms-cursor
  (testing ":refetch-all-pages? arms the cursor with pages 1..N-1 (issue-time
            page 0 excluded) WITHOUT touching :data / :status / :revision"
    (let [e0 (accumulated-3)
          e1 (state/entry-begin-refetch-sweep e0 {:refetch-all-pages? true})]
      (is (= [["c1" 1] ["c2" 2]] (:refetch-sweep e1)) "cursor armed with the sweep tail")
      (is (= 3 (state/page-count e1)) ":data UNTOUCHED — never truncated")
      (is (= (:data e0) (:data e1)))
      (is (= (:status e0) (:status e1)))
      (is (= (:revision e0) (:revision e1)) "revision NOT bumped by arming the cursor")))
  (testing ":refetch-window 2 arms a cursor with only page 1"
    (let [e1 (state/entry-begin-refetch-sweep (accumulated-3) {:refetch-window 2})]
      (is (= [["c1" 1]] (:refetch-sweep e1))))))

(deftest entry-advance-and-clear-refetch-sweep
  (testing "advance pops the head leg; clearing the last leg removes the key"
    (let [e0 (state/entry-begin-refetch-sweep (accumulated-3) {:refetch-all-pages? true})]
      (is (= ["c1" 1] (state/next-refetch-sweep-leg e0)) "head leg is page 1")
      (let [e1 (state/entry-advance-refetch-sweep e0)]
        (is (= [["c2" 2]] (:refetch-sweep e1)) "page 1 popped, page 2 remains")
        (is (= ["c2" 2] (state/next-refetch-sweep-leg e1)))
        (let [e2 (state/entry-advance-refetch-sweep e1)]
          (is (not (contains? e2 :refetch-sweep)) "last leg popped ⇒ cursor removed")
          (is (nil? (state/next-refetch-sweep-leg e2)) "no next leg on an exhausted sweep")))))
  (testing "clear-refetch-sweep drops an in-progress cursor (a failed/aborted leg stops it)"
    (let [e0 (state/entry-begin-refetch-sweep (accumulated-3) {:refetch-all-pages? true})]
      (is (not (contains? (state/clear-refetch-sweep e0) :refetch-sweep)))))
  (testing "next-refetch-sweep-leg on an unarmed entry is nil"
    (is (nil? (state/next-refetch-sweep-leg (accumulated-3))))))
