;; Probe 5b (second sibling review, at trunk): the upgrade snippet parses AND the child it describes
;; compiles to a plan whose fidelity no longer carries :sub-overrides.
(ns probe5b
  (:require [re-frame.core :as rf] [re-frame.frame :as rf.frame] [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as pa] [re-frame.story :as story]
            [re-frame.story.play.runner-events :as re] [re-frame.epoch]
            [re-frame.story.ui.view-state :as vs] [clojure.edn :as edn] [clojure.walk :as walk]))
(defn line [& xs] (println (apply str xs)))
(story/clear-all!) (rf.registrar/clear-all!) (reset! rf.frame/frames {})
(rf/init! pa/adapter) (reset! re/run-state {}) (story/install-canonical-vocabulary!) (rf.frame/ensure-default-frame!)
(rf/reg-event :probe/set (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
(rf/reg-sub :probe/v (fn [db _] (:v db)))
(story/reg-story* :story.probe {:doc "probe"})
(story/reg-variant* :story.probe/pinned {:sub-overrides {[:probe/v] "PINNED"} :doc "cheap picture"})

(let [s (vs/upgrade-snippet :story.probe/pinned :real-setup)]
  (line "P5b snippet:") (println s)
  (let [form (try (edn/read-string s) (catch Throwable e (line "P5b read-string THREW " (.getMessage e)) nil))]
    (line "P5b read-string ok? " (boolean form) " form-head=" (pr-str (take 2 form)))
    (when form
      (let [child-id (second form) body (nth form 2)
            ;; fill the placeholder setup with a real event so the child compiles
            body' (walk/postwalk (fn [x] (if (and (vector? x) (= :your/setup-event (first x))) [:probe/set 7] x)) body)
            body' (update body' :setup (fn [st] (mapv (fn [step] (if (and (vector? step) (= :dispatch (first step))) (second step) step)) st)))]
        (line "   child id=" child-id " body keys=" (pr-str (sort (keys body'))) " :extends=" (pr-str (:extends body')) " :sub-overrides=" (pr-str (:sub-overrides body')))
        (story/reg-variant* child-id body')
        (let [ex (story/explain child-id)
              fid (or (:fidelity ex) (get-in ex [:plan :fidelity]) (get-in ex [:world :fidelity]))
              mentions (atom 0)]
          (walk/postwalk (fn [x] (when (= x :sub-overrides) (swap! mentions inc)) x) ex)
          (line "   explain keys=" (pr-str (sort (keys ex))))
          (line "   fidelity=" (pr-str fid) "  :sub-overrides mentions anywhere in explain=" @mentions)
          (let [r (deref (story/run child-id) 15000 ::timeout)]
            (line "   child run status=" (:status r) " db :v=" (pr-str (get-in r [:db :v])) " (a pinned picture would not have run :probe/set)")))))))
(line "DONE") (shutdown-agents) (System/exit 0)
