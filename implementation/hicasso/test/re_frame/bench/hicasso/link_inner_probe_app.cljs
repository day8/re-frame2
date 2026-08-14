(ns re-frame.bench.hicasso.link-inner-probe-app
  "DIAGNOSTIC ONLY (rf2-cno31), SECOND PASS. `link_decomp_probe_app`
  attributed the 8.21 µs/link term to `route-url` (4.71 µs) and found the
  two suspects it named were NOT the cost — the CEDN fail-closed guard is
  0.36 µs/call and the route-meta `registrar/lookup` is 0.12. That leaves
  ~4.2 µs of `route-url` unattributed, so this probe replicates
  `route-url`'s remaining inner stages VERBATIM as arms of their own and
  prices them against an `:ideal` floor (what building `/profile/jane`
  costs when nothing but the string is built).

    :floor      the loop and the `aget`
    :ideal      `(str \"/profile/\" (encodeURIComponent v))` — the operation
    :emit-loop  route-url's char-by-char pattern walk, `(conj parts (str ch))`
                per literal char + `(apply str parts)` — replicated exactly
    :set-build  `uncaptured-param-keys`' per-call `(into #{} (map keyword) names)`
                + `(remove captured (keys path-params))`
    :addr-keys  the address-key reject scan + the `emitted-query` build
                (`sort-by canonical-bytes` into an array-map) on an EMPTY query
    :route-url  the whole synthesis, the anchor both probes share
    :ctl2       2× route-url, the positive control

  A replica is not the fn — it is the same expression on the same data,
  which is what a decomposition needs when the stages are `defn-`
  private. The anchor arm (`:route-url`) keeps both probes on one scale."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.core :as rf]
            [re-frame.identity :as identity]
            [re-frame.routing :as routing]
            [re-frame.routing.address :as address]))

(def frame-id ::frame)

(def ^:private links-per-page 207)

(defn- link-targets []
  (let [a #js []]
    (dotimes [i lt/article-count]
      (let [art (m/article i)
            u   (:username (:author art))]
        (.push a {:to :conduit.profile/show :params {:username u}})
        (.push a {:to :conduit.profile/show :params {:username u}})
        (.push a {:to :conduit.article/show :params {:slug (:slug art)}})))
    a))

(defn- ideal-parts
  "Per target, `[literal-prefix scalar]` — the two things the URL is made
  of, so the `:ideal` arm builds the string and nothing else."
  [targets]
  (let [a #js []]
    (dotimes [i (alength targets)]
      (let [t (aget targets i)
            p (:params t)]
        (.push a (if (= :conduit.profile/show (:to t))
                   #js ["/profile/" (:username p)]
                   #js ["/article/" (:slug p)]))))
    a))

(defn- pattern-for [t]
  (if (= :conduit.profile/show (:to t)) "/profile/:username" "/article/:slug"))

(defn- names-for [t]
  (if (= :conduit.profile/show (:to t)) ["username"] ["slug"]))

(defn- seg-end
  "`match/segment-end`'s boundary scan, replicated."
  [^string pattern n start]
  (loop [idx start]
    (if (>= idx n)
      idx
      (let [c (.charAt pattern idx)]
        (if (or (= c "/") (= c "{") (= c "}") (= c "?"))
          idx
          (recur (inc idx)))))))

(defn- emit-replica
  "`route-url`'s top-level emission loop, verbatim in shape: one
  `(conj parts (str ch))` per LITERAL character, one `url-encode` per
  captured param, `(apply str parts)` at the end."
  [^string pattern params]
  (let [n (count pattern)]
    (loop [i     0
           parts []]
      (if-not (< i n)
        (let [s (apply str parts)] (if (= "" s) "/" s))
        (let [ch (.charAt pattern i)]
          (if (or (= ch ":") (= ch "*"))
            (let [start (inc i)
                  end   (seg-end pattern n start)
                  k     (keyword (subs pattern start end))
                  v     (get params k)]
              (recur end (conj parts (js/encodeURIComponent (str v)))))
            (recur (inc i) (conj parts (str ch)))))))))

(def ^:private passes-per-sample 4)

(defn- timed-door [pass!]
  (let [t0 (lane/now-ms)]
    (dotimes [_ passes-per-sample]
      (rt/render-body frame-id (fn [_] (pass!) [:span]) {}))
    (- (lane/now-ms) t0)))

(def ^:private arm-ids [:floor :ideal :emit-loop :set-build :addr-keys :route-url :ctl2])

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (lt/make-frame! frame-id)
          (lt/reseed! frame-id)
          (let [targets (link-targets)
                ideals  (ideal-parts targets)
                n       (alength targets)
                sink    (volatile! nil)
                arms
                [{:id :floor
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (aget targets i))))}
                 {:id :ideal
                  :pass (fn []
                          (dotimes [i n]
                            (let [p (aget ideals i)]
                              (vreset! sink (str (aget p 0)
                                                 (js/encodeURIComponent (aget p 1)))))))}
                 {:id :emit-loop
                  :pass (fn []
                          (dotimes [i n]
                            (let [t (aget targets i)]
                              (vreset! sink (emit-replica (pattern-for t) (:params t))))))}
                 {:id :set-build
                  :pass (fn []
                          (dotimes [i n]
                            (let [t        (aget targets i)
                                  captured (into #{} (map keyword) (names-for t))]
                              (vreset! sink (seq (remove captured (keys (:params t))))))))}
                 {:id :addr-keys
                  :pass (fn []
                          (dotimes [i n]
                            (let [t (aget targets i)]
                              (vreset! sink
                                       [(seq (remove address/address-keys (keys t)))
                                        (into (array-map)
                                              (sort-by (comp identity/canonical-bytes key)
                                                       (remove (fn [[_ v]] (nil? v)) {})))]))))}
                 {:id :route-url
                  :pass (fn []
                          (dotimes [i n]
                            (vreset! sink (routing/route-url (aget targets i)))))}
                 {:id :ctl2
                  :pass (fn []
                          (dotimes [_ 2]
                            (dotimes [i n]
                              (vreset! sink (routing/route-url (aget targets i))))))}]
                {:keys [readings samples]}
                (lane/rounds! arms {:warmup 4 :samples 8} 5
                              (fn [{:keys [pass]}] (timed-door pass)))
                rows (into {}
                           (map (fn [id]
                                  (let [xs (mapcat #(get % id) readings)]
                                    [id (lane/summarise (mapv #(/ % passes-per-sample) xs))])))
                           arm-ids)
                gv   (lane/guard! samples "link inner decomposition (in-page ms, diagnostic)")
                ctl  (lane/control-verdict (* 2.0 (:p50 (get rows :route-url)))
                                           (let [s (get rows :ctl2)]
                                             {:min (:min s) :max (:max s) :mean (:p50 s)})
                                           0.25)]
            (when-not (:ok? ctl)
              (throw (ex-info (str "positive control failed: " (:why ctl)) {})))
            (lane/record! :link-inner rows)
            (js/console.log ";; ==== LINK INNER DECOMPOSITION (ms per 207-call pass; diagnostic) ====")
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
