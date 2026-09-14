;; JVM probe of Story's three verbs, honesty controls and force-fx-stub.
;; Run from tools/story:  clojure -Sdeps '{:deps {day8/re-frame2-epoch {:local/root "../../implementation/epoch"}}}' -M -i <this file>
(ns probe
  (:require [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.epoch]
            [clojure.test :as t]))

(defn deref* [f] (deref f 15000 ::timeout))
(defn result [f] (let [r (deref* f)] (if (= r ::timeout) {:status :TIMEOUT} r)))
(defn line [& xs] (println (apply str xs)))

;; ---------------------------------------------------------------- D: no adapter
(line "D  no-adapter run:")
(try
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.story/reg-variant* :story.probe/noadapter {:setup [[:probe/ok]]})
  (let [r (result (rf.story/run :story.probe/noadapter))]
    (line "   status=" (:status r) " lifecycle=" (:lifecycle r) " assertions=" (count (:assertions r))
          " first-assertion=" (select-keys (first (:assertions r)) [:assertion :status :error-id])))
  (catch Throwable e (line "   THREW " (.getName (class e)) " " (some-> (ex-data e) :rf/error) " " (.getMessage e))))

;; ---------------------------------------------------------------- fixture
(rf.story/clear-all!)
(rf.registrar/clear-all!)
(reset! rf.frame/frames {})
(rf/init! rf.substrate.plain-atom/adapter)
(reset! rf.story.play.runner-events/run-state {})
(rf.story/install-canonical-vocabulary!)
(rf.frame/ensure-default-frame!)
(def http-calls (atom []))
(rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(rf/reg-event :probe/ok  (fn [{:keys [db]} _] {:db (assoc db :ok true)}))
(rf/reg-event :probe/fire (fn [_ _] {:fx [[:probe/http {:url "/x"}]]}))
(rf/reg-fx :probe/http (fn [payload] (swap! http-calls conj payload)))
(rf.story/reg-story* :story.probe {:doc "probe story"})

;; ---------------------------------------------------------------- A / B: pass + fail control
(rf.story/reg-variant* :story.probe/pass {:setup [[:probe/set 1]] :assertions [[:rf.assert/path-equals [:v] 1]]})
(rf.story/reg-variant* :story.probe/fail {:setup [[:probe/set 1]] :assertions [[:rf.assert/path-equals [:v] 2]]})
(let [t0 (System/nanoTime) r (result (rf.story/run :story.probe/pass)) ms (/ (- (System/nanoTime) t0) 1e6)]
  (line "A  pass  status=" (:status r) " assertions=" (count (:assertions r)) " runner=" (:runner r) " keys=" (sort (keys r)) " wall-ms=" (long ms)))
(let [r (result (rf.story/run :story.probe/fail))]
  (line "B  fail-control status=" (:status r) " record=" (select-keys (first (:assertions r)) [:assertion :status :expected :actual])))

;; ---------------------------------------------------------------- C: reg-check executes (rf2-b2mt)
(rf.story/reg-check* :check.probe/must-fail {:assertions [[:rf.assert/path-equals [:v] 99]]})
(rf.story/reg-variant* :story.probe/checked {:setup [[:probe/set 1]] :checks [:check.probe/must-fail]})
(let [r (result (rf.story/run :story.probe/checked))]
  (line "C  check-with-false-atom status=" (:status r) " assertions=" (count (:assertions r)) " checks=" (mapv (juxt :check :status) (:checks r))))

;; ---------------------------------------------------------------- E: same-id rerun tape scope (rf2-3okc)
(rf.story/reg-variant* :story.probe/tape {:script [[:dispatch [:probe/ok]] [:assert [:rf.assert/dispatched? [:probe/ok]]]]})
(let [r (result (rf.story/run :story.probe/tape))] (line "E1 tape first run status=" (:status r)))
(rf.story/reg-variant* :story.probe/tape {:script [[:assert [:rf.assert/dispatched? [:probe/ok]]]]})
(let [r (result (rf.story/run :story.probe/tape))] (line "E2 same-id rerun (no dispatch) status=" (:status r) " (expect :fail)"))

;; ---------------------------------------------------------------- F: explain over :extends
(rf.story/reg-variant* :story.probe/base  {:setup [[:probe/set 1]] :args {:a 1 :b 2} :tags #{:dev}})
(rf.story/reg-variant* :story.probe/child {:extends :story.probe/base :setup [[:probe/set 2]] :args {:b 3} :assertions [[:rf.assert/path-equals [:v] 2]]})
(let [ex (rf.story/explain :story.probe/child)]
  (line "F  explain keys=" (sort (keys ex)))
  (line "   source-chain=" (:source-chain ex) " parent-chain=" (:parent-chain ex))
  (line "   setup-order=" (:setup-order ex))
  (line "   effective-args=" (:effective-args ex) " required-runner=" (:required-runner ex)))
(let [r (result (rf.story/run :story.probe/child))] (line "   child run status=" (:status r) " (expect :pass, setup appended parent-then-child)"))

;; ---------------------------------------------------------------- G: inline plan
(let [r (result (rf.story/run {:setup [[:probe/set 5]] :assertions [[:rf.assert/path-equals [:v] 5]]}))]
  (line "G  inline-plan status=" (:status r) " plan-hash?=" (boolean (:plan-hash r))))

;; ---------------------------------------------------------------- H: force-fx-stub
(reset! http-calls [])
(rf.story/reg-variant* :story.probe/stubbed {:decorators [[rf.story/force-fx-stub-id :probe/http {:status 200}]]
                                          :script [[:dispatch-sync [:probe/fire]] [:assert [:rf.assert/effect-emitted :probe/http]]]})
(let [r (result (rf.story/run :story.probe/stubbed))]
  (line "H1 stubbed status=" (:status r) " real-http-calls=" (count @http-calls) " (expect :pass, 0)"))
(reset! http-calls [])
(rf.story/reg-variant* :story.probe/unstubbed {:script [[:dispatch-sync [:probe/fire]] [:assert [:rf.assert/effect-emitted :probe/http]]]})
(let [r (result (rf.story/run :story.probe/unstubbed))]
  (line "H2 unstubbed status=" (:status r) " real-http-calls=" (count @http-calls) " (expect :pass, 1)"))

;; ---------------------------------------------------------------- I: cannot-run
(rf.story/reg-variant* :story.probe/dom {:setup [[:probe/set 1]] :assertions [[:rf.assert/dom-visible "[data-test=x]"]]})
(let [r (result (rf.story/run :story.probe/dom))]
  (line "I  dom-assertion headless status=" (:status r) " cannot-run=" (mapv #(select-keys % [:reason :missing :required-runner]) (:cannot-run r))))
(rf.story/reg-variant* :story.probe/dom-and-fail {:setup [[:probe/set 1]] :assertions [[:rf.assert/dom-visible "[data-test=x]"] [:rf.assert/path-equals [:v] 2]]})
(let [r (result (rf.story/run :story.probe/dom-and-fail))]
  (line "I2 dom + real failure status=" (:status r) " (expect :fail — refusal never masks a failure)"))

;; ---------------------------------------------------------------- K: snapshot identity
(rf.story/reg-variant* :story.probe/snap-a {:setup [[:probe/set 1]] :args {:x 1}})
(rf.story/reg-variant* :story.probe/snap-b {:setup [[:probe/set 1]] :args {:x 1}})
(rf.story/reg-variant* :story.probe/snap-c {:setup [[:probe/set 1]] :args {:x 2}})
(let [a (rf.story/snapshot-identity :story.probe/snap-a) b (rf.story/snapshot-identity :story.probe/snap-b) c (rf.story/snapshot-identity :story.probe/snap-c)]
  (line "K  snapshot a==b? " (= (:content-hash a) (:content-hash b)) " a==c? " (= (:content-hash a) (:content-hash c)) " keys=" (sort (keys a))))

;; ---------------------------------------------------------------- L / N: unknown check / unknown assertion refused
(line "L  unknown :checks id:")
(try (rf.story/reg-variant* :story.probe/badcheck {:setup [[:probe/set 1]] :checks [:check.probe/nope]})
     (let [r (result (rf.story/run :story.probe/badcheck))] (line "   registered; run status=" (:status r) " first=" (select-keys (first (:assertions r)) [:assertion :status :error-id])))
     (catch Throwable e (line "   REFUSED " (some-> (ex-data e) :rf/error) " :: " (subs (str (.getMessage e)) 0 (min 160 (count (str (.getMessage e))))))))
(line "N  unknown assertion id:")
(try (rf.story/reg-variant* :story.probe/badassert {:setup [[:probe/set 1]] :assertions [[:rf.assert/typo [:v] 1]]})
     (let [r (result (rf.story/run :story.probe/badassert))] (line "   registered; run status=" (:status r)))
     (catch Throwable e (line "   REFUSED " (some-> (ex-data e) :rf/error) " :: " (subs (str (.getMessage e)) 0 (min 160 (count (str (.getMessage e))))))))

;; ---------------------------------------------------------------- M: rf.story/is reports
(let [reports (atom [])]
  (binding [t/report (fn [m] (when (#{:pass :fail :error} (:type m)) (swap! reports conj (:type m))))]
    (rf.story/is :story.probe/fail))
  (line "M  rf.story/is on failing variant → clojure.test reports=" @reports))

;; ---------------------------------------------------------------- P: variant->edn round trip is data
(line "P  variant->edn=" (pr-str (rf.story/variant->edn :story.probe/stubbed)))
(line "DONE")
(shutdown-agents)
(System/exit 0)
