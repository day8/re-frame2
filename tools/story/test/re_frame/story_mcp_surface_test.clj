(ns re-frame.story-mcp-surface-test
  "The spec/015 §MCP surface scenarios, from the vantage of the story-mcp jar's
  in-process calls: the id-set reads, the run-variant result shape, a
  frame-scoped dispatch, and snapshot-identity stability."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-all [t]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-all)

(defn- run-v! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000))

(defn- content-hash [vid opts]
  (:content-hash (rf.story/snapshot-identity vid opts)))

(deftest list-stories-returns-id-set
  (rf.story/reg-story :story.mcp.list-s-a {:doc "story A"})
  (rf.story/reg-story :story.mcp.list-s-b {:doc "story B"})
  (is (= #{:story.mcp.list-s-a :story.mcp.list-s-b} (rf.story/ids :story))))

(deftest list-variants-returns-id-set
  (is (= #{} (rf.story/ids :variant)) "an empty registry answers #{}, not nil")
  (rf.story/reg-variant :story.mcp.list-v/probe {:setup []})
  (rf.story/reg-variant :story.mcp.list-v/probe-two {:setup []})
  (is (= #{:story.mcp.list-v/probe :story.mcp.list-v/probe-two} (rf.story/ids :variant))))

(deftest list-modes-returns-id-set
  (is (= #{} (rf.story/list-modes)))
  (rf.story/reg-mode :Mode.mcp.list/dark  {:args {:theme :dark}})
  (rf.story/reg-mode :Mode.mcp.list/light {:args {:theme :light}})
  (is (= #{:Mode.mcp.list/dark :Mode.mcp.list/light} (rf.story/list-modes) (rf.story/ids :mode))))

(deftest run-variant-return-shape-matches-spec
  (rf/reg-event :mcp/seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf.story/reg-variant :story.mcp.run/probe
    {:setup  [[:mcp/seed 42]]
     :script [[:dispatch-sync [:rf.assert/path-equals [:n] 42]]]})
  (let [result (run-v! :story.mcp.run/probe)]
    (is (= [:story.mcp.run/probe 42 [true]]
           [(:frame result) (-> result :app-db :n) (mapv :passed? (:assertions result))]))
    (is (vector? (:assertions result)))
    (is (number? (:elapsed-ms result)))
    (is (every? #(contains? result %) [:snapshot :decorators]))))

(deftest run-variant-empty-play-still-returns-shape
  (rf.story/reg-variant :story.mcp.run/no-play {:setup []})
  (let [result (run-v! :story.mcp.run/no-play)]
    (is (= [:story.mcp.run/no-play [] true]
           [(:frame result) (:assertions result) (map? (:app-db result))]))))

;; A same-process shape pin: register through `reg-variant*`, run, then
;; dispatch with `{:frame variant-id}` (the MCP jar ships no dispatch tool).

(deftest dispatch-via-mcp-writes-to-variant-frame-app-db
  (rf/reg-event :mcp.dispatch/set (fn [{:keys [db]} [_ v]] {:db (assoc db :payload v)}))
  (rf.story/reg-variant* :story.mcp.dispatch/probe {:setup [] :args {}})
  (run-v! :story.mcp.dispatch/probe)
  (rf/dispatch-sync [:mcp.dispatch/set "hello"] {:frame :story.mcp.dispatch/probe})
  (is (= ["hello" nil] [(:payload (rf/app-db-value :story.mcp.dispatch/probe))
                        (:payload (rf/app-db-value :rf/default))])
      "the write lands on the variant's frame and not on the default frame"))

(deftest dispatch-via-mcp-multiple-events-accumulate
  (rf/reg-event :mcp.dispatch/push (fn [{:keys [db]} [_ v]] {:db (update db :log (fnil conj []) v)}))
  (rf.story/reg-variant* :story.mcp.dispatch.seq/probe {:setup []})
  (run-v! :story.mcp.dispatch.seq/probe)
  (doseq [v ["a" "b" "c"]]
    (rf/dispatch-sync [:mcp.dispatch/push v] {:frame :story.mcp.dispatch.seq/probe}))
  (is (= ["a" "b" "c"] (:log (rf/app-db-value :story.mcp.dispatch.seq/probe)))))

;; Render-relevant inputs move the hash; a cosmetic `:source` edit does not
;; (002-Runtime §Snapshot-identity computation).

(deftest snapshot-identity-stable-across-cosmetic-edits
  (rf.story/reg-variant* :story.mcp.snap/probe {:args {:label "v1"} :setup []})
  (let [h1 (content-hash :story.mcp.snap/probe nil)]
    (rf.story/reg-variant* :story.mcp.snap/probe
      {:args {:label "v1"} :setup [] :source {:file "agent.cljs" :line 99}})
    (is (= h1 (content-hash :story.mcp.snap/probe nil)))))

(deftest snapshot-identity-changes-on-args-edit
  (rf.story/reg-variant* :story.mcp.snap.args/probe {:args {:label "before"} :setup []})
  (let [with-co #(content-hash :story.mcp.snap.args/probe {:cell-overrides {:label "after"}})]
    (is (not= (content-hash :story.mcp.snap.args/probe nil) (with-co)))
    (is (= (with-co) (with-co)) "same input, same hash")))

(deftest snapshot-identity-changes-on-active-modes
  (rf.story/reg-mode :Mode.mcp.snap/dark  {:args {:theme :dark}})
  (rf.story/reg-mode :Mode.mcp.snap/light {:args {:theme :light}})
  (rf.story/reg-variant* :story.mcp.snap.modes/probe {:args {:label "x"} :setup []})
  (is (= 3 (count (set (map #(content-hash :story.mcp.snap.modes/probe {:active-modes %})
                            [[:Mode.mcp.snap/dark] [:Mode.mcp.snap/light] []]))))
      "dark, light and no mode hash three ways"))
