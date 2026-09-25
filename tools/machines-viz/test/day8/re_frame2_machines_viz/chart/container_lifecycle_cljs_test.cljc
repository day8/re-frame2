(ns day8.re-frame2-machines-viz.chart.container-lifecycle-cljs-test
  "A container — a compound state, a parallel region body, the machine
  root's frame — paints its own tags and entry / exit actions in a
  lifecycle band closing its header, and ELK reserves the band's height in
  that container's TOP padding so the first child lays out below it.

  These pins sit at the cheap JVM layer: the band-height model
  (`projection/lifecycle-band-height`) and the padding `->elk-children`
  derives from it, fed from real machine definitions through
  `layout/project-definition`. A container declaring no tags and no
  lifecycle action is the control — its padding is byte-identical to the
  plain container padding, so it lays out exactly as a container without
  the band. The rendered band itself is pinned in the browser suite
  (`container-lifecycle-dom-cljs-test`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

(def ^:private densities
  [[:compact vc/chart-compact]
   [:regular vc/chart-regular]
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

(def ^:private parallel-machine
  "The `:audio` region body declares a tag and an entry action; the
  `:video` region declares none (the control)."
  {:type    :parallel
   :actions lifecycle-actions
   :regions {:audio {:initial :muted
                     :entry   :hello
                     :tags    #{:audio/live}
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden {:on {:show :shown}}
                               :shown  {:on {:hide :hidden}}}}}})

;; ---- helpers --------------------------------------------------------------

(defn- node-with-id [parsed id]
  (first (filter #(= id (:id %)) (:nodes parsed))))

(defn- elk-padding-of
  "The `elk.padding` string `->elk-children` puts on the ELK container `id`,
  searched at any depth."
  [elk-children id]
  (let [walk (fn walk [c] (cons c (mapcat walk (:children c))))]
    (some #(when (= id (:id %)) (get-in % [:layoutOptions "elk.padding"]))
          (mapcat walk elk-children))))

(defn- elk-padding-top [pad]
  #?(:clj  (Integer/parseInt (second (re-find #"top=(\d+)" pad)))
     :cljs (js/parseInt (second (re-find #"top=(\d+)" pad)) 10)))

(defn- sides-other-than-top [pad]
  (str/replace pad #"top=\d+" "top=X"))

;; ---- the band-height model ------------------------------------------------

(deftest band-height-is-zero-without-tags-or-lifecycle
  (testing "a container declaring no tags and no entry / exit paints no band,
            so it reserves nothing"
    (doseq [[density chart-vc] densities]
      (is (= 0 (projection/lifecycle-band-height chart-vc {}))
          (str density " no lifecycle slots"))
      (is (= 0 (projection/lifecycle-band-height chart-vc {:tags #{}}))
          (str density " an empty tag set"))
      (is (= 0 (projection/lifecycle-band-height chart-vc {:tags []}))
          (str density " the empty tag vector the renderer reads")))))

(deftest band-height-models-the-rendered-rows
  (testing "the band is its vertical padding, one chip-high tag row, an action
            row per lifecycle action (caption + gap + chip, plus gap + `needs`
            line when the action declares requirements), the gaps between the
            rows and the bottom divider"
    (doseq [[density chart-vc] densities]
      (let [{:keys [state-body-pad-y state-body-gap tag-pill-height
                    action-caption-px action-caption-gap action-pill-height
                    container-divider-width]} chart-vc
            chip-border 2
            tag-row     (+ tag-pill-height chip-border)
            action-row  (+ action-caption-px action-caption-gap
                           action-pill-height chip-border)
            needs-line  (+ action-caption-gap action-caption-px)
            band        (fn [& rows]
                          (+ (* 2 state-body-pad-y)
                             (reduce + rows)
                             (* (dec (count rows)) state-body-gap)
                             container-divider-width))]
        (is (= (band tag-row)
               (projection/lifecycle-band-height chart-vc {:tags #{:x}}))
            (str density " tags alone: one chip-high row"))
        (is (= (band action-row)
               (projection/lifecycle-band-height chart-vc {:entry "e"}))
            (str density " one action row"))
        (is (= (band tag-row (+ action-row needs-line) action-row)
               (projection/lifecycle-band-height
                 chart-vc {:tags #{:x :y} :entry "e" :entry-requires ["r"] :exit "x"}))
            (str density " tags, an entry with requirements and an exit"))
        (is (= (projection/lifecycle-band-height chart-vc {:tags #{:x}})
               (projection/lifecycle-band-height chart-vc {:tags #{:x :y :z}}))
            (str density " more tags stay on the one row"))))))

;; ---- ELK reserves the band in the container's TOP padding -----------------

(deftest compound-padding-reserves-its-lifecycle-band
  (testing "a compound declaring tags and entry / exit reserves its band on
            TOP; its lifecycle-free sibling keeps the plain padding exactly"
    (doseq [[density chart-vc] densities]
      (let [parsed  (layout/project-definition compound-machine)
            kids    (projection/->elk-children parsed nil chart-vc)
            player  (layout/node-id [:player])
            idle    (layout/node-id [:idle])
            plain   (projection/container-elk-padding chart-vc true)
            band-px (projection/lifecycle-band-height
                      chart-vc (node-with-id parsed player))]
        (is (pos? band-px) (str density " :player paints a band"))
        (is (= (+ (elk-padding-top plain) band-px)
               (elk-padding-top (elk-padding-of kids player)))
            (str density " :player's TOP grows by exactly its band height"))
        (is (= (sides-other-than-top plain)
               (sides-other-than-top (elk-padding-of kids player)))
            (str density " only :player's TOP side moves"))
        (is (= plain (elk-padding-of kids idle))
            (str density " the lifecycle-free :idle keeps the plain padding"))))))

(deftest region-padding-reserves-its-lifecycle-band
  (testing "a region body declaring a tag and an entry reserves its band on
            TOP; the lifecycle-free region keeps the plain padding exactly"
    (doseq [[density chart-vc] densities]
      (let [parsed  (layout/project-definition parallel-machine)
            kids    (projection/->elk-children parsed nil chart-vc)
            audio   (layout/region-node-id :audio)
            video   (layout/region-node-id :video)
            plain   (projection/container-elk-padding chart-vc true)
            band-px (projection/lifecycle-band-height
                      chart-vc (node-with-id parsed audio))]
        (is (pos? band-px) (str density " :audio paints a band"))
        (is (= (+ (elk-padding-top plain) band-px)
               (elk-padding-top (elk-padding-of kids audio)))
            (str density " :audio's TOP grows by exactly its band height"))
        (is (= plain (elk-padding-of kids video))
            (str density " the lifecycle-free :video keeps the plain padding"))))))

(deftest root-padding-reserves-its-lifecycle-band
  (testing "the machine root's frame reserves its lifecycle band on TOP, on
            top of the Context band; a lifecycle-free root keeps the plain
            frame padding"
    (doseq [[density chart-vc] densities]
      (let [root-id  layout/root-container-id
            dw       (:container-divider-width chart-vc)
            parsed   (layout/project-definition root-machine)
            band-px  (projection/lifecycle-band-height
                       chart-vc (node-with-id parsed root-id))
            bare     (layout/project-definition (dissoc root-machine :entry :exit :tags))
            pad      (fn [p rows]
                       (elk-padding-of (projection/->elk-children p nil chart-vc rows)
                                       root-id))]
        (is (pos? band-px) (str density " the root paints a band"))
        (is (= (projection/container-elk-padding chart-vc true band-px)
               (pad parsed 0))
            (str density " the root's TOP reserves its band"))
        (is (= (projection/container-elk-padding
                 chart-vc true (+ band-px (projection/context-band-height 3 dw)))
               (pad parsed 3))
            (str density " the band and a 3-row Context band both reserve TOP"))
        (is (= (projection/container-elk-padding chart-vc true) (pad bare 0))
            (str density " a lifecycle-free root keeps the plain frame padding"))
        (is (= (projection/container-elk-padding
                 chart-vc true (projection/context-band-height 3 dw))
               (pad bare 3))
            (str density " a lifecycle-free root reserves only its Context band"))))))
