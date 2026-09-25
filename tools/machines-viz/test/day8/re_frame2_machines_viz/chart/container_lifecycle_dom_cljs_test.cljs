(ns day8.re-frame2-machines-viz.chart.container-lifecycle-dom-cljs-test
  "Browser-side pins for the lifecycle band a chart CONTAINER paints: a
  compound state, a parallel region body and the machine root's frame each
  show their own tags and entry / exit actions — requirement text included —
  in the leaf state's tag-chip and action-row presentation, in a band
  closing the header. The band fits inside the TOP padding ELK reserved for
  the header (`data-reserved-top`), and a container declaring no tags and no
  lifecycle action shows no band and keeps its plain header reservation.

  These assert the RENDERED chart, mounted from a machine definition, so
  lifecycle data that reached a container's `:data` without being painted
  fails them. The reservation model and ELK wiring are pinned at the JVM
  layer (`container-lifecycle-cljs-test`).

  ns ends in `-dom-cljs-test` so it runs under the `:browser-test` build
  (real DOM + headless Chromium). Under `:node-test` (no DOM) every test
  short-circuits via `(browser?)`. The container DOM mounts on the first
  commit, so nothing here awaits the async elkjs layout (see
  `chart-dom-cljs-test`'s async-layout note)."
  (:require ["react"            :as React]
            ["react-dom/client" :as react-dom-client]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- machines -------------------------------------------------------------

(def ^:private lifecycle-actions
  {:hello {:rf.cofx/requires [:rf/time-ms] :fn (fn [_ctx] nil)}
   :bye   (fn [_ctx] nil)})

(def ^:private compound-machine
  "`:player` declares a tag, an entry action with requirements and an exit
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
  "The machine root declares a tag and entry / exit actions."
  {:initial :a
   :entry   :hello
   :exit    :bye
   :tags    #{:app/booted}
   :actions lifecycle-actions
   :states  {:a {:on {:go :b}}
             :b {}}})

(def ^:private parallel-machine
  "The `:audio` region body declares a tag and an entry action; the `:video`
  region declares none (the control)."
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

(def ^:private plain-machine
  "No container declares tags or lifecycle actions."
  {:initial :a
   :states  {:a {:on {:go :b}}
             :b {}}})

;; ---- mount helpers ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- with-mounted-chart
  "Mount `MachineChart` with `props` through the React bridge under act(),
  call `(f host-node)`, then unmount."
  [props f]
  (let [act-fn (when (exists? (.-act React)) (.-act React))]
    (when (and (browser?) act-fn)
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      (let [node (.createElement js/document "div")
            _    (set! (.. node -style -width) "800px")
            _    (set! (.. node -style -height) "600px")
            _    (.appendChild (.-body js/document) node)
            root (react-dom-client/createRoot node)]
        (try
          (act-fn (fn [] (.render root (react-chart/chart-element props))))
          (f node)
          (finally
            (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
            (try (.removeChild (.-body js/document) node) (catch :default _ nil))))))))

(defn- by-testid [^js el testid]
  (.querySelector el (str "[data-testid=\"" testid "\"]")))

(defn- band-of [^js container-el id]
  (by-testid container-el (str "rf-mv-chart-lifecycle-band-" id)))

(defn- any-band [^js container-el]
  (.querySelector container-el "[data-testid^=\"rf-mv-chart-lifecycle-band-\"]"))

(defn- reserved-top [^js container-el]
  (js/parseInt (.getAttribute container-el "data-reserved-top") 10))

(def ^:private plain-reserved-top
  "The header reservation of a container painting no band: the title strip
  plus the body-pad band, at the chart's default (regular) density."
  (+ (:container-title-height vc/chart-regular)
     (:container-body-pad vc/chart-regular)))

;; ---- compound -------------------------------------------------------------

(deftest compound-shows-its-tags-and-lifecycle-actions
  (testing "a compound's band paints its tag chip, its entry action with the
            requirement it declares, and its exit action, inside the header
            ELK reserved"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/player :definition compound-machine}
        (fn [node]
          (let [id    (layout/node-id [:player])
                el    (by-testid node (str "rf-mv-chart-compound-" id))
                band  (some-> el (band-of id))
                tag   (some-> band (by-testid "rf-mv-chart-state-tag-busy"))
                entry (some-> band (by-testid "rf-mv-chart-state-entry"))
                needs (some-> band (by-testid "rf-mv-chart-state-entry-requires"))
                exit  (some-> band (by-testid "rf-mv-chart-state-exit"))]
            (is (some? el) "the :player compound mounted")
            (is (some? band) "the compound paints a lifecycle band")
            (is (pos? (some-> band .-offsetHeight)) "the band takes visible height")
            (is (= "media/busy" (some-> tag (.getAttribute "data-tag")))
                "the tag chip carries the declared tag")
            (is (re-find #"media/busy" (str (some-> tag .-textContent)))
                "the tag chip reads the declared tag")
            (is (= "hello" (some-> entry (.getAttribute "data-entry")))
                "the entry action row names the action")
            (is (re-find #"Entry actions" (str (some-> entry .-textContent)))
                "the entry row carries the leaf's caption")
            (is (= "needs rf/time-ms" (some-> needs .-textContent))
                "the entry action's requirement text shows")
            (is (= "bye" (some-> exit (.getAttribute "data-exit")))
                "the exit action row names the action")
            (is (nil? (some-> band (by-testid "rf-mv-chart-state-exit-requires")))
                "an exit action declaring no requirement shows no `needs` line")
            (is (> (reserved-top el) plain-reserved-top)
                "the header reservation includes the band")
            (is (<= (+ (.-offsetTop band) (.-offsetHeight band)) (reserved-top el))
                "the band ends inside the reserved header, above the first child")))))))

(deftest lifecycle-free-compound-shows-no-band
  (testing "a compound declaring no tags and no lifecycle action shows no band
            and keeps the plain header reservation"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/player :definition compound-machine}
        (fn [node]
          (let [el (by-testid node (str "rf-mv-chart-compound-" (layout/node-id [:idle])))]
            (is (some? el) "the :idle compound mounted")
            (is (nil? (any-band el)) "no lifecycle band")
            (is (nil? (by-testid el "rf-mv-chart-state-entry")) "no entry row")
            (is (nil? (by-testid el "rf-mv-chart-state-tags")) "no tag row")
            (is (= plain-reserved-top (reserved-top el))
                "the header reservation is the plain title + body-pad")))))))

;; ---- parallel region ------------------------------------------------------

(deftest region-shows-its-tags-and-lifecycle-actions
  (testing "a region body's band paints its tag chip and its entry action with
            its requirement text, inside the header ELK reserved; the
            lifecycle-free region shows none"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/media :definition parallel-machine}
        (fn [node]
          (let [audio-id (layout/region-node-id :audio)
                audio    (by-testid node (str "rf-mv-chart-region-" audio-id))
                video    (by-testid node (str "rf-mv-chart-region-"
                                              (layout/region-node-id :video)))
                band     (some-> audio (band-of audio-id))
                tag      (some-> band (by-testid "rf-mv-chart-state-tag-live"))
                entry    (some-> band (by-testid "rf-mv-chart-state-entry"))
                needs    (some-> band (by-testid "rf-mv-chart-state-entry-requires"))]
            (is (some? audio) "the :audio region mounted")
            (is (some? band) "the region paints a lifecycle band")
            (is (pos? (some-> band .-offsetHeight)) "the band takes visible height")
            (is (= "audio/live" (some-> tag (.getAttribute "data-tag")))
                "the tag chip carries the region's declared tag")
            (is (= "hello" (some-> entry (.getAttribute "data-entry")))
                "the entry action row names the region's action")
            (is (= "needs rf/time-ms" (some-> needs .-textContent))
                "the entry action's requirement text shows")
            (is (nil? (some-> band (by-testid "rf-mv-chart-state-exit")))
                "a region declaring no exit shows no exit row")
            (is (> (reserved-top audio) plain-reserved-top)
                "the header reservation includes the band")
            (is (<= (+ (.-offsetTop band) (.-offsetHeight band)) (reserved-top audio))
                "the band ends inside the reserved header, above the first child")
            (is (some? video) "the :video region mounted")
            (is (nil? (any-band video)) "the lifecycle-free region paints no band")
            (is (= plain-reserved-top (reserved-top video))
                "the lifecycle-free region keeps the plain header reservation")))))))

;; ---- machine root ---------------------------------------------------------

(deftest root-shows-its-tags-and-lifecycle-actions
  (testing "the machine root's frame header paints the root's tag chip, entry
            action with its requirement text and exit action, and the whole
            header fits inside the TOP padding ELK reserved"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/app :definition root-machine}
        (fn [node]
          (let [id     layout/root-container-id
                frame  (.querySelector node "[data-root-container=\"true\"]")
                header (some-> frame (by-testid (str "rf-mv-chart-root-container-header-" id)))
                band   (some-> header (band-of id))]
            (is (some? frame) "the root frame mounted")
            (is (some? band) "the frame header paints the root's lifecycle band")
            (is (pos? (some-> band .-offsetHeight)) "the band takes visible height")
            (is (= "app/booted"
                   (some-> band (by-testid "rf-mv-chart-state-tag-booted")
                           (.getAttribute "data-tag")))
                "the tag chip carries the root's declared tag")
            (is (= "hello" (some-> band (by-testid "rf-mv-chart-state-entry")
                                   (.getAttribute "data-entry")))
                "the entry action row names the root's action")
            (is (= "needs rf/time-ms"
                   (some-> band (by-testid "rf-mv-chart-state-entry-requires") .-textContent))
                "the entry action's requirement text shows")
            (is (= "bye" (some-> band (by-testid "rf-mv-chart-state-exit")
                                 (.getAttribute "data-exit")))
                "the exit action row names the root's action")
            (is (> (reserved-top frame) plain-reserved-top)
                "the header reservation includes the band")
            (is (<= (.-offsetHeight header) (reserved-top frame))
                "the whole header fits inside the reserved TOP padding")))))))

(deftest lifecycle-free-root-shows-no-band
  (testing "a root declaring no tags and no lifecycle action shows no band and
            keeps the plain header reservation"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises this")
      (with-mounted-chart
        {:machine-id :test/plain :definition plain-machine}
        (fn [node]
          (let [frame (.querySelector node "[data-root-container=\"true\"]")]
            (is (some? frame) "the root frame mounted")
            (is (nil? (any-band frame)) "no lifecycle band")
            (is (= plain-reserved-top (reserved-top frame))
                "the header reservation is the plain title + body-pad")))))))
