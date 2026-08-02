(ns re-frame.bench.hicasso.link-decomp-probe-app
  "DIAGNOSTIC ONLY (rf2-cno31): DECOMPOSE the per-render route-link term
  `link_term_probe_app.cljs` priced at 8.21 µs/link (of which `route-url`
  synthesis alone was 5.19 µs).

  That probe answered *how big*; this one answers *where*, because the
  remedy depends entirely on the answer. It runs the SAME 207 acceptance-page
  addresses through the SAME render-body door, and adds the stages
  `link-model` is built out of as arms of their own:

    :floor       the loop and the `aget`, and nothing else
    :cedn        `identity/canonical-bytes` on ONE path-param value — the
                 call `route-url`'s `assert-url-value!` fail-closed guard
                 makes per path param per render, whose result it discards
    :lookup      `registrar/lookup :route` — the route-meta read
    :route-url   the whole path-form URL synthesis
    :strategy    `url-strategy-for-frame-id` — the render-time strategy
                 consult, which reads `frame/frame-meta`
    :link-model  the whole seam (route-url + strategy encode + payload +
                 native?)
    :ctl2        2× link-model, the positive control

  An arm here is NOT a bar row: these are in-page microsecond figures for
  a diagnostic, interleaved under the arm-order guard so a stage's figure
  does not depend on where in the plan it was measured."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.core :as rf]
            [re-frame.identity :as identity]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.routing :as routing]
            [re-frame.routing.strategy :as strategy]))

(def frame-id ::frame)

(def ^:private links-per-page 207)

(defn- link-targets
  "The acceptance page's own 207 link addresses: per card, two profile
  links and one article link, from the model's own seed."
  []
  (let [a #js []]
    (dotimes [i lt/article-count]
      (let [art (m/article i)
            u   (:username (:author art))]
        (.push a {:to :conduit.profile/show :params {:username u}})
        (.push a {:to :conduit.profile/show :params {:username u}})
        (.push a {:to :conduit.article/show :params {:slug (:slug art)}})))
    a))

(defn- param-values
  "The ONE path-param value each address carries — the exact scalar
  `assert-url-value!` runs `canonical-bytes` over."
  [targets]
  (let [a #js []]
    (dotimes [i (alength targets)]
      (let [p (:params (aget targets i))]
        (.push a (or (:username p) (:slug p)))))
    a))

(def ^:private passes-per-sample 4)

(defn- timed-door [pass!]
  (let [t0 (lane/now-ms)]
    (dotimes [_ passes-per-sample]
      (rt/render-body frame-id (fn [_] (pass!) [:span]) {}))
    (- (lane/now-ms) t0)))

(def ^:private arm-ids [:floor :cedn :lookup :route-url :strategy :link-model :ctl2])

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (lt/make-frame! frame-id)
          (lt/reseed! frame-id)
          (let [targets    (link-targets)
                values     (param-values targets)
                n          (alength targets)
                link-model (late-bind/require-fn! :routing/link-model
                                                  'link-decomp-probe {} {})
                sink       (volatile! nil)
                arms
                [{:id :floor
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (aget targets i))))}
                 {:id :cedn
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (identity/canonical-bytes (aget values i)))))}
                 {:id :lookup
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (registrar/lookup :route (:to (aget targets i))))))}
                 {:id :route-url
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (routing/route-url (aget targets i)))))}
                 {:id :strategy
                  :pass (fn []
                          (dotimes [_ n]
                            (vreset! sink (strategy/url-strategy-for-frame-id frame-id))))}
                 {:id :link-model
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (link-model (aget targets i) frame-id))))}
                 {:id :ctl2
                  :pass (fn []
                          (dotimes [_ 2]
                            (dotimes [i n]
                              (vreset! sink (link-model (aget targets i) frame-id)))))}]
                {:keys [readings samples]}
                (lane/rounds! arms {:warmup 4 :samples 8} 5
                              (fn [{:keys [pass]}] (timed-door pass)))
                rows (into {}
                           (map (fn [id]
                                  (let [xs (mapcat #(get % id) readings)]
                                    [id (lane/summarise (mapv #(/ % passes-per-sample) xs))])))
                           arm-ids)
                gv   (lane/guard! samples "link decomposition (in-page ms, diagnostic)")
                ctl  (lane/control-verdict (* 2.0 (:p50 (get rows :link-model)))
                                           (let [s (get rows :ctl2)]
                                             {:min (:min s) :max (:max s) :mean (:p50 s)})
                                           0.25)]
            (when-not (:ok? ctl)
              (throw (ex-info (str "positive control failed: " (:why ctl)) {})))
            (lane/record! :link-decomp rows)
            (js/console.log ";; ==== LINK DECOMPOSITION (ms per 207-call pass; diagnostic in-page clock) ====")
            (doseq [id arm-ids]
              (let [{:keys [p50 min max]} (get rows id)]
                (js/console.log (str ";;   " (name id) ": p50 " (.toFixed p50 4)
                                     " [" (.toFixed min 4) " - " (.toFixed max 4)
                                     "] ms/pass  (" (.toFixed (/ (* 1e3 p50) links-per-page) 2)
                                     " us/call)"))))
            (js/console.log (str ";;   control: " (:why ctl)))
            (when (:refuse? gv)
              (set! (.-HICASSO_GUARD_REFUSED js/window) true))
            (lane/done!))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
