(ns re-frame2-pair-mcp.epoch-page-fit-test
  "The paginated epoch reads fit their page to `max-tokens` rather than
  overflowing it whole.

  `limit` bounds RECORDS, and one real-app epoch record runs to thousands
  of tokens, so a page under the default 5000-token cap can hold only a few.
  Each read returns the longest prefix of its page that fits, stamped
  `:truncated-by :max-tokens`, with a cursor that resumes at the first record
  it left out — and when not even one record fits, an advisory naming the
  record's size and the `max-tokens` that admits it.

  `realistic-epochs` is calibrated to two pages measured on a real
  ~170-file Reagent app under the default `diff` and `dedup` modes: three
  records at about 13.6K tokens and two at about 9.2K. Every test drives the
  real tool through `tools/invoke`, so the cap that grades it is the wire's."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.cursor :as cursor]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn [] (set! nrepl/cljs-eval-value pristine-eval) (cache/clear!))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- invoke!
  "Run `tool` through the wire pipeline on `args`, against a runtime that
  answers the preload probe and every other form with `answer`."
  [tool args answer]
  (let [respond (fn [form]
                  (js/Promise.resolve
                    (if (str/includes? form "__re_frame2_pair_runtime") true answer)))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))
    (tools/invoke (fresh-conn) tool (tu/args->js args) nil)))

;; ---------------------------------------------------------------------------
;; A real-sized epoch: a small app-db shared across records, and per record
;; the trace events, sub runs and renders one interaction on a real app
;; produces — the part of a record no other record shares.
;; ---------------------------------------------------------------------------

(def ^:private t0 1761700000000)

(defn- app-db [i]
  {:orders  {:rows     (into {} (for [r (range 4)]
                                  [r {:id r :customer (str "Customer " r) :total (* 10 r)
                                      :status (if (= r (mod i 4)) :selected :open)}]))
             :selected (mod i 4)}
   :ui      {:route [:orders/list] :drawer-open? false}
   :session {:user "u-1" :locale "en-AU"}})

(def ^:private trace-ops
  [[:rf.event/dispatched :rf.event] [:rf.event/run-start :rf.event] [:rf.cofx/run :rf.cofx]
   [:rf.event/db-pending :rf.event] [:rf.event/run-end :rf.event] [:rf.event/db-changed :rf.event]
   [:rf.fx/do-fx :rf.fx] [:rf.fx/handled :rf.fx] [:rf.sub/run :rf.sub] [:rf.view/rendered :rf.view]])

(defn- trace-event [i j]
  (let [[op op-type] (nth trace-ops (mod j (count trace-ops)))]
    {:id        (+ (* 1000 i) j)
     :operation op
     :op-type   op-type
     :time      (+ t0 (* 50 i) j)
     :tags      {:frame                 :app/main
                 :rf.trace/dispatch-id  (+ 100 i)
                 :rf.trace/event-id     :orders/row-selected
                 :rf.sub/id             (keyword "orders" (str "visible-row-" j))
                 :rf.view/id            (keyword "app.views.orders" (str "order-row-" j))
                 :rf.event/elapsed-ms   (+ 0.25 (* 0.01 j))
                 :rf.sub/value-changed? (odd? j)}}))

(def ^:private trace-events-per-record
  "The one calibrated knob: the per-record bulk that sets a page's growth."
  19)

(defn- epoch-record [i]
  {:kind               :rf/epoch-record
   :epoch-id           i
   :frame              :app/main
   :committed-at       (+ t0 (* 50 i))
   :event-id           :orders/row-selected
   :trigger-event      [:orders/row-selected]
   :dispatch-id        (+ 100 i)
   :outcome            :ok
   :frame-state-before {:rf.db/app (app-db i) :rf.db/runtime :rf/redacted}
   :frame-state-after  {:rf.db/app (app-db (inc i)) :rf.db/runtime :rf/redacted}
   :db-before          (app-db i)
   :db-after           (app-db (inc i))
   :effects            {:fx [[:analytics/track :rf/redacted]]}
   :trace-events       (mapv #(trace-event i %) (range trace-events-per-record))
   :sub-runs           (vec (for [j (range 6)]
                              {:sub-id         (keyword "orders" (str "visible-row-" j))
                               :query-v        [(keyword "orders" (str "visible-row-" j)) j]
                               :recomputed?    true
                               :value-changed? (odd? j)
                               :cause-event-id :orders/row-selected
                               :epoch-window   [i (inc i)]}))
   :renders            (vec (for [j (range 4)]
                              {:render-key     [(keyword "app.views.orders" (str "order-row-" j)) (+ (* 10 i) j)]
                               :mount?         false
                               :triggered-by   (keyword "orders" (str "visible-row-" j))
                               :elapsed-ms     0.4
                               :cause-event-id :orders/row-selected}))})

(defn- realistic-epochs [n] (mapv epoch-record (range n)))

(defn- trace-answer
  "trace-window's slice result for a window holding `epochs`."
  [epochs]
  {:epochs        epochs
   :id-aged-out?  false
   :head-id       (:epoch-id (peek epochs))
   :history-count 12
   :frame         :app/main
   :remaining     0})

(defn- watch-answer
  "watch-epochs' poll result when `matches` matched every epoch since the id."
  [matches]
  {:matches       matches
   :id-aged-out?  false
   :head-id       (:epoch-id (peek matches))
   :history-count 12
   :since-count   (count matches)
   :frame         :app/main
   :remaining     0})

(defn- page-tokens
  "Promise of what an uncapped trace-window page of `n` realistic records
  measures under the cap's own rule."
  [n]
  (-> (invoke! "trace-window" {:ms 600000 :max-tokens 0} (trace-answer (realistic-epochs n)))
      (.then cap/sum-payload-tokens)))

(defn- within? [pct target x]
  (<= (* target (- 1 pct)) x (* target (+ 1 pct))))

(deftest the-fixture-measures-like-a-real-app-epoch
  ;; Both field pages within 5%, so the per-record bulk and the shared part
  ;; are both real-sized. A page of ONE such record fitting the default cap
  ;; is what makes the prefix cut enough on its own: a first call always
  ;; returns at least one record of a real app.
  ;; One page at a time: each call installs its own eval stub.
  (async done
    (-> (reduce (fn [chain n] (.then chain (fn [acc] (.then (page-tokens n) #(conj acc %)))))
                (js/Promise.resolve [])
                [1 2 3])
        (.then (fn [[one two three]]
                 (is (within? 0.05 9200 two) (str "two records measure " two " tokens"))
                 (is (within? 0.05 13600 three) (str "three records measure " three " tokens"))
                 (is (< one cap/default-max-tokens)
                     (str "one record measures " one " tokens, under the default cap"))
                 (done))))))

(deftest an-over-cap-trace-window-page-returns-the-prefix-that-fits
  (async done
    (-> (invoke! "trace-window" {:ms 600000 :limit 3} (trace-answer (realistic-epochs 3)))
        (.then (fn [r]
                 (let [edn (tu/extract-edn r)]
                   (is (not (contains? edn :rf.mcp/overflow))
                       "the page is cut, not replaced by the overflow marker")
                   (is (= [0] (mapv :epoch-id (tu/dedup-expand (:epochs edn))))
                       "the longest prefix that fits: the first record")
                   (is (= {:count 1 :truncated-by :max-tokens :has-more? true :estimated-remaining 2}
                          (select-keys edn [:count :truncated-by :has-more? :estimated-remaining])))
                   (is (= {:after-id 0 :frame :app/main :ms 600000}
                          (select-keys (cursor/decode-cursor (:next-cursor edn)) [:after-id :frame :ms]))
                       "the cursor resumes at the first record the cut left out")
                   (is (<= (cap/sum-payload-tokens r) cap/default-max-tokens))
                   (is (not (contains? edn :advisory))))
                 (done))))))

(deftest an-over-cap-watch-epochs-page-returns-the-prefix-that-fits
  (async done
    (-> (invoke! "watch-epochs" {:pred #js {"event-id-prefix" ":orders/"}}
                 (watch-answer (realistic-epochs 2)))
        (.then (fn [r]
                 (let [edn (tu/extract-edn r)]
                   (is (not (contains? edn :rf.mcp/overflow)))
                   (is (= [0] (mapv :epoch-id (tu/dedup-expand (:matches edn)))))
                   (is (= {:count 1 :truncated-by :max-tokens :has-more? true :estimated-remaining 1}
                          (select-keys edn [:count :truncated-by :has-more? :estimated-remaining])))
                   (is (= {:after-id 0 :frame :app/main :pred {:event-id-prefix ":orders/"}}
                          (select-keys (cursor/decode-cursor (:next-cursor edn)) [:after-id :frame :pred]))
                       "the cursor resumes after the shown match and keeps the predicate")
                   (is (not (contains? edn :advisory))))
                 (done))))))

(deftest a-record-over-the-cap-alone-says-so-instead-of-blaming-the-window
  ;; At max-tokens 3000 not even one real-sized record fits. The page is
  ;; empty because of the cap, and the advisory says that — not that the
  ;; window or the predicate excluded the history.
  (async done
    (-> (invoke! "trace-window" {:ms 600000 :max-tokens 3000} (trace-answer (realistic-epochs 3)))
        (.then (fn [r]
                 (let [edn      (tu/extract-edn r)
                       advisory (:advisory edn)]
                   (is (= {:count 0 :truncated-by :max-tokens :has-more? true :estimated-remaining 3}
                          (select-keys edn [:count :truncated-by :has-more? :estimated-remaining])))
                   (is (= {:after-id nil :until-ms (:until-ms edn) :ms 600000 :frame :app/main}
                          (select-keys (cursor/decode-cursor (:next-cursor edn))
                                       [:after-id :until-ms :ms :frame]))
                       "a first page has no position to resume from, so its cursor carries the window")
                   (is (= {:reason :record-exceeds-max-tokens :frame :app/main :cap-tokens 3000}
                          (select-keys advisory [:reason :frame :cap-tokens])))
                   (is (< 3000 (:record-tokens advisory) cap/default-max-tokens))
                   (is (str/includes? (:hint advisory) "max-tokens 0"))
                   (is (str/includes? (:hint advisory) "next-cursor")))))
        (.then (fn [_]
                 (invoke! "watch-epochs" {:since-id "7" :max-tokens 3000}
                          (watch-answer (realistic-epochs 2)))))
        (.then (fn [r]
                 (let [edn (tu/extract-edn r)]
                   (is (= :record-exceeds-max-tokens (get-in edn [:advisory :reason]))
                       "not :pred-excludes-history — the predicate matched")
                   (is (= 7 (:after-id (cursor/decode-cursor (:next-cursor edn))))
                       "the cursor resumes from the since-id this poll read after")
                   (is (str/includes? (get-in edn [:advisory :hint]) "next-cursor")))
                 (done))))))

(deftest a-page-that-fits-ships-whole
  (async done
    (-> (invoke! "trace-window" {:ms 600000} (trace-answer (realistic-epochs 1)))
        (.then (fn [r]
                 (let [edn (tu/extract-edn r)]
                   (is (= {:count 1 :has-more? false :estimated-remaining 0}
                          (select-keys edn [:count :has-more? :estimated-remaining])))
                   (is (not (contains? edn :truncated-by))))
                 (done))))))
