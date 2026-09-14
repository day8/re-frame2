;; Follow-up JVM probe: unstubbed fx path, identity stability, story-level args in explain,
;; db-seed and sub-overrides fidelity + honesty. Run from tools/story like probe.clj.
(ns probe2
  (:require [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as pa]
            [re-frame.story :as story]
            [re-frame.story.play.runner-events :as re]
            [re-frame.epoch]))

(defn deref* [f] (deref f 15000 ::timeout))
(defn result [f] (let [r (deref* f)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))

(story/clear-all!)
(rf.registrar/clear-all!)
(reset! rf.frame/frames {})
(rf/init! pa/adapter)
(reset! re/run-state {})
(story/install-canonical-vocabulary!)
(rf.frame/ensure-default-frame!)
(def http-calls (atom []))
(rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(rf/reg-event :probe/fire (fn [_ _] {:fx [[:probe/http {:url "/x"}]]}))
(rf/reg-fx :probe/http (fn [payload] (swap! http-calls conj payload)))
(rf/reg-sub :probe/v (fn [db _] (:v db)))

;; H2 again, with diagnostics
(story/reg-story* :story.probe {:doc "probe" :args {:heading "Sign in"}})
(story/reg-variant* :story.probe/unstubbed {:script [[:dispatch-sync [:probe/fire]] [:assert [:rf.assert/effect-emitted :probe/http]]]})
(let [r (result (story/run :story.probe/unstubbed))]
  (line "H2 unstubbed status=" (:status r) " real-calls=" (count @http-calls))
  (line "   effects=" (pr-str (:effects r)))
  (line "   warnings=" (pr-str (mapv #(select-keys % [:operation :op-type :reason]) (:warnings r))))
  (line "   assertion=" (pr-str (select-keys (first (:assertions r)) [:assertion :status :reason :actual :expected])))
  (line "   epoch-tape count=" (count (:epoch-tape r)) " first-epoch keys=" (some-> (:epoch-tape r) first keys sort)))
;; control: same event outside Story on the default frame
(reset! http-calls [])
(rf/with-frame :rf/default (rf/dispatch-sync [:probe/fire]))
(line "   control: dispatch on :rf/default → real-calls=" (count @http-calls))
;; and a variant that dispatches in :setup rather than :script
(reset! http-calls [])
(story/reg-variant* :story.probe/unstubbed-setup {:setup [[:probe/fire]] :assertions [[:rf.assert/effect-emitted :probe/http]]})
(let [r (result (story/run :story.probe/unstubbed-setup))]
  (line "H3 unstubbed via :setup status=" (:status r) " real-calls=" (count @http-calls) " effects=" (pr-str (:effects r))))

;; K: identity — same id re-registered with same body → same hash? id in hash?
(story/reg-variant* :story.probe/snap {:setup [[:probe/set 1]] :args {:x 1}})
(def h1 (:content-hash (story/snapshot-identity :story.probe/snap)))
(story/reg-variant* :story.probe/snap {:setup [[:probe/set 1]] :args {:x 1}})
(def h2 (:content-hash (story/snapshot-identity :story.probe/snap)))
(story/reg-variant* :story.probe/snap {:setup [[:probe/set 1]] :args {:x 2}})
(def h3 (:content-hash (story/snapshot-identity :story.probe/snap)))
(story/reg-variant* :story.probe/snap2 {:setup [[:probe/set 1]] :args {:x 1}})
(def h4 (:content-hash (story/snapshot-identity :story.probe/snap2)))
(line "K  same-id same-body stable? " (= h1 h2) "  body-change moves? " (not= h1 h3) "  renamed-same-body equal? " (= h1 h4) " (" h1 " vs " h4 ")")

;; F2: story-level args reach explain / effective-args?
(story/reg-variant* :story.probe/noargs {:setup [[:probe/set 1]]})
(let [ex (story/explain :story.probe/noargs)]
  (line "F2 explain args=" (pr-str (:args ex)) " effective-args=" (pr-str (:effective-args ex)) " resolve-args=" (pr-str (story/resolve-args :story.probe/noargs))))

;; Q: db-seed rung
(story/reg-variant* :story.probe/seeded {:db-seed {:v 7} :assertions [[:rf.assert/path-equals [:v] 7]]})
(let [r (result (story/run :story.probe/seeded)) ex (story/explain :story.probe/seeded)]
  (line "Q  db-seed status=" (:status r) " fidelity=" (pr-str (:fidelity ex)) " app-db=" (pr-str (:app-db r))))

;; R: sub-overrides honesty — pinned value must NOT satisfy sub-equals
(story/reg-variant* :story.probe/pinned {:setup [[:probe/set 1]] :sub-overrides {[:probe/v] 42} :assertions [[:rf.assert/sub-equals [:probe/v] 42]]})
(let [r (result (story/run :story.probe/pinned)) ex (story/explain :story.probe/pinned)]
  (line "R  sub-overrides status=" (:status r) " (expect :fail — override never satisfies sub-equals) fidelity=" (pr-str (:fidelity ex))
        " record=" (pr-str (select-keys (first (:assertions r)) [:actual :expected :status]))))

;; S: run-result plan-hash / run-hash availability via the facade fns
(let [r (result (story/run :story.probe/seeded))]
  (line "S  plan-hash fn=" (pr-str (try (story/plan-hash (story/variant-plan :story.probe/seeded)) (catch Throwable e (str "THREW " (.getMessage e)))))
        " run-hash fn=" (pr-str (try (story/run-hash r) (catch Throwable e (str "THREW " (.getMessage e)))))
        " result has :plan-hash? " (contains? r :plan-hash) " :run-hash? " (contains? r :run-hash) " :evidence? " (contains? r :evidence)))

;; T: run-artifact + promotion round trip
(let [r (result (story/run :story.probe/pinned))]
  (try (let [art (story/make-run-artifact r)
             plan (story/materialize-variant-plan art {})]
         (line "T  run-artifact? " (story/run-artifact? art) " materialized plan keys=" (sort (keys plan)))
         (story/promote-run-artifact! art {:variant/id :story.probe/promoted})
         (line "   promoted registered? " (story/registered? :variant :story.probe/promoted) " body keys=" (sort (keys (story/handler-meta :variant :story.probe/promoted)))))
       (catch Throwable e (line "T  THREW " (.getMessage e)))))

;; U: variants-with-tags + registrations query (agent discovery surface)
(line "U  :test-tagged=" (pr-str (story/variants-with-tags #{:test})) " all variants=" (count (story/ids :variant)))
(line "DONE")
(shutdown-agents)
(System/exit 0)
