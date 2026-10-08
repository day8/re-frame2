(ns re-frame.resources-infinite-state-cljs-test
  "The pure transitions behind an infinite feed entry, whose :data is the
  ordered page vector (Spec 016 §Durable cache shape / §Causal event —
  load-more): cursor derivation, page append and in-place replace (with
  structural sharing), the multi-page refetch window, and the refetch sweep
  cursor. The load-more event and subs are their own suites."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.resources.state :as rf.resources.state]))

(def ^:private next-cursor
  "A :next-page-param fn: the last page's next cursor; nil is terminal."
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page [items next-c]
  {:items items :page-info {:next-cursor next-c}})

(defn- append [e pg param loaded-at]
  (rf.resources.state/entry-append-page
    e {:page pg :page-param param :next-page-param-fn next-cursor
       :loaded-at loaded-at :stale-at (+ loaded-at 1000)}))

(defn- replace-page [e pg param index]
  (rf.resources.state/entry-replace-page
    e {:page pg :page-param param :page-index index :next-page-param-fn next-cursor
       :loaded-at 9000 :stale-at 9999}))

(defn- accumulated-3
  "A loaded three-page feed [p0 p1 p2], cursors c1/c2/c3, params [nil c1 c2],
  built through the same appends the event layer drives."
  []
  (-> (rf.resources.state/empty-infinite-entry :feed/timeline)
      (append (page [:a] "c1") nil 1000)
      (append (page [:b] "c2") "c1" 1100)
      (append (page [:c] "c3") "c2" 1200)))

(deftest next-param-derivation
  (is (= ["c1" "c2" nil]
         (map #(rf.resources.state/next-param-for next-cursor %)
              [[(page [:a] "c1")]
               [(page [:a] "c1") (page [:b] "c2")]
               [(page [:a] nil)]]))
      "derived from the last page; a nil cursor is the terminal"))

(deftest prev-param-derivation-mirror
  (let [pages [{:items [:a] :page-info {:prev-cursor "p0"}}
               {:items [:b] :page-info {:prev-cursor "p1"}}]]
    (is (= ["p0" nil] [(rf.resources.state/prev-param-for prev-cursor pages)
                       (rf.resources.state/prev-param-for nil pages)])
        "derived from the first page; nil when no :prev-page-param is declared")))

(deftest append-first-page
  (let [e0 (rf.resources.state/empty-infinite-entry :feed/timeline)
        e1 (rf.resources.state/entry-append-page
             e0 {:page (page [:a :b] "c1") :page-param nil :next-page-param-fn next-cursor
                 :loaded-at 1000 :stale-at 61000})]
    (is (= {:data [(page [:a :b] "c1")] :page-params [nil] :next-page-param "c1"
            :status :loaded :loaded-at 1000 :stale-at 61000}
           (select-keys e1 [:data :page-params :next-page-param :status :loaded-at :stale-at])))
    (is (> (:revision e1) (:revision e0)) "an authoritative write bumps :revision")))

(deftest append-multiple-pages-accumulates
  (is (= {:data [(page [:a] "c1") (page [:b] "c2") (page [:c] "c3")] :page-params [nil "c1" "c2"]
          :next-page-param "c3" :loaded-at 1200}
         (select-keys (accumulated-3) [:data :page-params :next-page-param :loaded-at]))
      "pages grow in order, one param each, the cursor and :loaded-at re-derived per append"))

(deftest page-accessor-resolution
  ;; nil leaves the page as the item vector; anything but a keyword or fn is nil
  (let [f (fn [p] (:rows p))]
    (is (= [[:a :b] [:x] nil nil]
           [((rf.resources.state/resolve-page->items :items) {:items [:a :b]})
            ((rf.resources.state/resolve-page->items f) {:rows [:x]})
            (rf.resources.state/resolve-page->items nil)
            (rf.resources.state/resolve-page->items 99)]))))

(deftest replace-page-in-place-preserves-window
  (let [e0 (accumulated-3)
        e1 (replace-page e0 (page [:a*] "c1") nil 0)]
    (is (= {:data [(page [:a*] "c1") (page [:b] "c2") (page [:c] "c3")] :page-params [nil "c1" "c2"]
            :status :loaded :loaded-at 9000 :stale-at 9999}
           (select-keys e1 [:data :page-params :status :loaded-at :stale-at]))
        "page 0 refreshed in place, the tail kept and the feed not grown")
    (is (= [nil true] [(:current-work e1) (> (:revision e1) (:revision e0))]))))

(deftest replace-page-structural-sharing-identical-value
  (let [e0       (accumulated-3)
        old-page (nth (:data e0) 0)
        fresh    (page [:a] "c1")]
    (is (= [true false] [(= old-page fresh) (identical? old-page fresh)])
        "precondition: an equal but distinct fresh decode")
    (let [e1 (replace-page e0 fresh nil 0)]
      (is (= [true true true]
             [(identical? old-page (nth (:data e1) 0)) (identical? (nth (:data e0) 1) (nth (:data e1) 1))
              (> (:revision e1) (:revision e0))])
          "an equal refetch keeps the old page object and the tail, and still bumps :revision")))
  (testing "a different page value is stored as the fresh object"
    (let [fresh (page [:a*] "c1")]
      (is (identical? fresh (nth (:data (replace-page (accumulated-3) fresh nil 0)) 0))))))

(deftest replace-page-recomputes-cursor-from-replaced-tail
  (let [e1 (replace-page (accumulated-3) (page [:c*] "c3*") "c2" 2)]
    (is (= ["c3*" 3] [(:next-page-param e1) (rf.resources.state/page-count e1)]))))

(deftest replace-page-clears-error-channels
  (let [e0 (rf.resources.state/entry-page-failed (accumulated-3) {:error {:kind :rf.http/server :status 503}})
        e1 (replace-page e0 (page [:a*] "c1") nil 0)]
    (is (some? (:page-error e0)) "precondition: the failure recorded a page error")
    (is (= [nil nil nil] ((juxt :page-error :refresh-error :invalidated-at) e1)))))

;; Spec 016 §Refetch: the default refreshes page 0 in place; :refetch-all-pages?
;; refreshes every page and :refetch-window n the first n, in sequence, never
;; truncating the accumulation.

(deftest refetch-window-count-by-policy
  (doseq [[policy page-count expected]
          [[nil 0 0]
           [nil 3 1]
           [{:refetch-all-pages? true} 3 3]
           [{:refetch-all-pages? true :refetch-window 2} 3 3]
           [{:refetch-window 2} 3 2]
           [{:refetch-window 5} 3 3]
           [{:refetch-window 0} 3 1]]]
    (is (= expected (rf.resources.state/refetch-window-count policy page-count))
        (str "policy " (pr-str policy) " over " page-count " pages"))))

(deftest entry-begin-refetch-sweep-default-noop
  (let [e0 (accumulated-3)]
    (is (= [true true] [(identical? e0 (rf.resources.state/entry-begin-refetch-sweep e0 nil))
                        (identical? e0 (rf.resources.state/entry-begin-refetch-sweep e0 {}))])
        "the default arms no sweep")))

(deftest entry-begin-refetch-sweep-opt-in-arms-cursor
  (let [e0 (accumulated-3)
        e1 (rf.resources.state/entry-begin-refetch-sweep e0 {:refetch-all-pages? true})]
    (is (= [[["c1" 1] ["c2" 2]] true]
           [(:refetch-sweep e1) (= (select-keys e0 [:data :status :revision]) (select-keys e1 [:data :status :revision]))])
        "pages 1..N-1 are armed; :data, :status and :revision are untouched"))
  (is (= [["c1" 1]] (:refetch-sweep (rf.resources.state/entry-begin-refetch-sweep (accumulated-3) {:refetch-window 2})))))

(deftest entry-advance-and-clear-refetch-sweep
  (let [e0 (rf.resources.state/entry-begin-refetch-sweep (accumulated-3) {:refetch-all-pages? true})
        e1 (rf.resources.state/entry-advance-refetch-sweep e0)
        e2 (rf.resources.state/entry-advance-refetch-sweep e1)]
    (is (= [["c1" 1] [["c2" 2]] ["c2" 2] false nil]
           [(rf.resources.state/next-refetch-sweep-leg e0) (:refetch-sweep e1)
            (rf.resources.state/next-refetch-sweep-leg e1) (contains? e2 :refetch-sweep)
            (rf.resources.state/next-refetch-sweep-leg e2)])
        "each advance pops the head leg; popping the last removes the cursor")
    (is (not (contains? (rf.resources.state/clear-refetch-sweep e0) :refetch-sweep))
        "a failed or aborted leg clears the sweep"))
  (is (nil? (rf.resources.state/next-refetch-sweep-leg (accumulated-3))) "an unarmed entry has no leg"))
