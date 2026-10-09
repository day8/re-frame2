(ns day8.re-frame2-machines-viz.chart.container-lifecycle-cljs-test
  "A container — a compound state, a parallel region body, the machine
  root's frame — paints its own tags and entry / exit actions in a
  lifecycle band closing its header, and ELK reserves the band's height in
  that container's TOP padding so the first child lays out below it. A
  container declaring no tags and no lifecycle action is the control: it
  keeps the plain padding exactly. The rendered band, and that it fits the
  reservation, is pinned in `container-lifecycle-dom-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; The two density extremes: a band height `->elk-children` failed to take
;; from the density it was handed shows at either.
(def ^:private densities
  [[:compact vc/chart-compact]
   [:cosy    vc/chart-cosy]])

(def ^:private lifecycle-actions
  {:hello {:rf.cofx/requires [:rf/time-ms] :fn (fn [_ctx] nil)}
   :bye   (fn [_ctx] nil)})

(def ^:private compound-machine
  "`:player` declares tags, an entry action with requirements and an exit
  action; its sibling compound `:idle` declares none (the control)."
  {:initial :player
   :actions lifecycle-actions
   :states  {:player {:initial :stopped
                      :entry   :hello
                      :exit    :bye
                      :tags    #{:media/busy}
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}}}
             :idle   {:initial :a
                      :states  {:a {:on {:go :b}}
                                :b {}}}}})

(def ^:private root-machine
  "The machine root declares tags and entry / exit actions."
  {:initial :a
   :entry   :hello
   :exit    :bye
   :tags    #{:app/booted}
   :actions lifecycle-actions
   :states  {:a {:on {:go :b}}
             :b {}}})

(defn- node-with-id [parsed id]
  (first (filter #(= id (:id %)) (:nodes parsed))))

(defn- elk-padding-of
  "The `elk.padding` string `->elk-children` puts on the ELK container `id`,
  searched at any depth."
  [elk-children id]
  (let [walk (fn walk [c] (cons c (mapcat walk (:children c))))]
    (some #(when (= id (:id %)) (get-in % [:layoutOptions "elk.padding"]))
          (mapcat walk elk-children))))

(deftest compound-padding-reserves-its-lifecycle-band
  (testing "a compound declaring tags and entry / exit adds exactly its band
            to its TOP padding; its lifecycle-free sibling keeps the plain
            padding"
    (doseq [[density chart-vc] densities]
      (let [parsed  (layout/project-definition compound-machine)
            kids    (projection/->elk-children parsed nil chart-vc)
            player  (layout/node-id [:player])
            band-px (projection/lifecycle-band-height
                      chart-vc (node-with-id parsed player))]
        (is (pos? band-px) (str density " :player paints a band"))
        (is (= (projection/container-elk-padding chart-vc true band-px)
               (elk-padding-of kids player))
            (str density " :player's TOP, and only its TOP, grows by its band"))
        (is (= (projection/container-elk-padding chart-vc true)
               (elk-padding-of kids (layout/node-id [:idle])))
            (str density " the lifecycle-free :idle keeps the plain padding"))))))

(deftest root-padding-reserves-its-lifecycle-band
  (testing "the machine root's frame reserves its lifecycle band on TOP, on
            top of the Context band; a lifecycle-free root reserves only the
            Context band"
    (doseq [[density chart-vc] densities]
      (let [parsed  (layout/project-definition root-machine)
            bare    (layout/project-definition (dissoc root-machine :entry :exit :tags))
            band-px (projection/lifecycle-band-height
                      chart-vc (node-with-id parsed layout/root-container-id))
            ctx-px  (projection/context-band-height 3 (:container-divider-width chart-vc))]
        (is (pos? band-px) (str density " the root paints a band"))
        (doseq [[label p rows extra-top] [["band"                parsed 0 band-px]
                                          ["band + Context band" parsed 3 (+ band-px ctx-px)]
                                          ["no band"             bare   0 0]
                                          ["Context band only"   bare   3 ctx-px]]]
          (is (= (projection/container-elk-padding chart-vc true extra-top)
                 (elk-padding-of (projection/->elk-children p nil chart-vc rows)
                                 layout/root-container-id))
              (str density " " label)))))))
