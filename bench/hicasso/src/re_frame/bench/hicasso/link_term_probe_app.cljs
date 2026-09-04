(ns re-frame.bench.hicasso.link-term-probe-app
  "DIAGNOSTIC ONLY (rf2-6c237): price the per-render route-link term the
  rf2-2rtt6.54 migration added to every census mount — the 207
  `link-model` calls the acceptance page performs (69 cards x 3 links),
  which the hand-ported uix/reagent twins do not perform at all.

  One question, in-page clock, stated as diagnostic: what do 207
  link-model syntheses cost inside a body render, decomposed against a
  `route-url`-only floor? Runs the calls through the runtime's own
  render-body door exactly as `route-link` performs them (same seam, same
  frame), interleaved with the arm-order guard adjudicating."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.large-template :as rf.bench.hicasso.shapes.large-template]
            [re-frame.bench.hicasso.shapes.model :as rf.bench.hicasso.shapes.model]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]))

(def frame-id ::frame)

(def ^:private links-per-page 207)

(defn- link-targets
  "The acceptance page's own 207 link addresses: per card, two profile
  links and one article link, from the model's own seed."
  []
  (let [a #js []]
    (dotimes [i rf.bench.hicasso.shapes.large-template/article-count]
      (let [art (rf.bench.hicasso.shapes.model/article i)
            u   (:username (:author art))]
        (.push a {:to :conduit.profile/show :params {:username u}})
        (.push a {:to :conduit.profile/show :params {:username u}})
        (.push a {:to :conduit.article/show :params {:slug (:slug art)}})))
    a))

(def ^:private passes-per-sample 4)

(defn- timed-door [pass!]
  (let [t0 (rf.bench.hicasso.lane/now-ms)]
    (dotimes [_ passes-per-sample]
      (rf.bench.hicasso.arm1.runtime/render-body frame-id (fn [_] (pass!) [:span]) {}))
    (- (rf.bench.hicasso.lane/now-ms) t0)))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (rf.bench.hicasso.shapes.large-template/make-frame! frame-id)
          (rf.bench.hicasso.shapes.large-template/reseed! frame-id)
          (let [targets    (link-targets)
                n          (alength targets)
                link-model (rf.late-bind/require-fn! :routing/link-model
                                                  'link-term-probe {} {})
                sink       (volatile! nil)
                arms
                [{:id :link-model
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (link-model (aget targets i) frame-id))))}
                 {:id :route-url
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (rf.routing/route-url (aget targets i)))))}
                 {:id :ctl2
                  :pass (fn []
                          (dotimes [_ 2]
                            (dotimes [i n]
                              (vreset! sink (link-model (aget targets i) frame-id)))))}]
                {:keys [readings samples]}
                (rf.bench.hicasso.lane/rounds! arms {:warmup 3 :samples 8} 5
                              (fn [{:keys [pass]}] (timed-door pass)))
                rows (into {}
                           (map (fn [id]
                                  (let [xs (mapcat #(get % id) readings)]
                                    [id (rf.bench.hicasso.lane/summarise (mapv #(/ % passes-per-sample) xs))])))
                           [:link-model :route-url :ctl2])
                gv   (rf.bench.hicasso.lane/guard! samples "link-term probe (in-page ms, diagnostic)")
                ctl  (rf.bench.hicasso.lane/control-verdict (* 2.0 (:p50 (get rows :link-model)))
                                           (let [s (get rows :ctl2)]
                                             {:min (:min s) :max (:max s) :mean (:p50 s)})
                                           0.25)]
            (when-not (:ok? ctl)
              (throw (ex-info (str "positive control failed: " (:why ctl)) {})))
            (rf.bench.hicasso.lane/record! :link-term rows)
            (js/console.log ";; ==== LINK TERM (ms per 207-link pass; diagnostic in-page clock) ====")
            (doseq [id [:link-model :route-url :ctl2]]
              (let [{:keys [p50 min max]} (get rows id)]
                (js/console.log (str ";;   " (name id) ": p50 " (.toFixed p50 4)
                                     " [" (.toFixed min 4) " - " (.toFixed max 4)
                                     "] ms/pass  (" (.toFixed (/ (* 1e3 p50) links-per-page) 2)
                                     " us/link)"))))
            (js/console.log (str ";;   control: " (:why ctl)))
            (when (:refuse? gv)
              (set! (.-HICASSO_GUARD_REFUSED js/window) true))
            (rf.bench.hicasso.lane/done!))))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.hicasso.lane/done!)))))
