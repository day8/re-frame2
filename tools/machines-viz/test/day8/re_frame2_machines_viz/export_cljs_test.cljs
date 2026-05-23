(ns day8.re-frame2-machines-viz.export-cljs-test
  "Tests for the chart exporters (rf2-8d7w1 · v1.0).

  The PNG / SVG rasterisers are browser-DOM-only (canvas, ClipboardItem)
  and are not exercised under node-test; the seam-derivation +
  Mermaid-delegation + share-URL paths are testable with a stub element
  carrying the `_rfMvChartState` JS property the chart's root `:ref`
  sets in production.

  Coverage:
  - `share-url` derives a valid share-URL from the DOM seam.
  - `chart-as-mermaid` delegates to the Mermaid emitter using the
    seam's definition.
  - An element with no seam throws `:no-chart-state`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.export :as export]
            [day8.re-frame2-machines-viz.share :as share]))

(def definition
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(defn- stub-element
  "A minimal stand-in for the chart's root DOM node: it carries the
  `_rfMvChartState` seam (a CLJS map, exactly as the chart's root `:ref`
  stores it) directly, so `chart-root` returns it without calling
  `closest` / `querySelector`."
  [chart-state]
  (let [^js el #js {}]
    (set! (.-_rfMvChartState el) chart-state)
    el))

(def seam
  {:machine-id    :auth/login-flow
   :definition    definition
   :current-state :loading
   :node-count    4
   :edge-count    4
   :region-count  0})

(deftest share-url-from-seam
  (testing "share-url derives an encodable ChartState off the DOM seam"
    (let [el  (stub-element seam)
          url (export/share-url el)
          env (share/decode-share-url url)
          cs  (:rf.machines-viz.share/chart env)]
      (is (str/includes? url "#machine="))
      (is (= :auth/login-flow (:machine-id cs)))
      (is (= definition (:definition cs)))
      (is (= {:state :loading} (:snapshot cs))
          "active-state name rides as the snapshot; no :data"))))

(deftest share-url-honours-host-and-frame
  (testing ":host + :frame-id opts thread through"
    (let [el  (stub-element seam)
          url (export/share-url el {:host "https://acme/v.html" :frame-id :app/main})
          cs  (:rf.machines-viz.share/chart (share/decode-share-url url))]
      (is (str/starts-with? url "https://acme/v.html#machine="))
      (is (= :app/main (:frame-id cs))))))

(deftest share-url-no-snapshot-when-idle
  (testing "no :current-state on the seam → no snapshot in the payload"
    (let [el  (stub-element (dissoc seam :current-state))
          cs  (:rf.machines-viz.share/chart
                (share/decode-share-url (export/share-url el)))]
      (is (not (contains? cs :snapshot))))))

(deftest mermaid-from-seam
  (testing "chart-as-mermaid emits a fenced Mermaid block from the seam definition"
    (let [el (stub-element seam)
          md (export/chart-as-mermaid el)]
      (is (string? md))
      (is (str/includes? md "stateDiagram-v2"))
      (is (str/includes? md "mermaid"))))
  (testing "opts pass through (unfenced body)"
    (let [el (stub-element seam)
          md (export/chart-as-mermaid el {:fenced? false :header-comment? false})]
      (is (not (str/starts-with? (str/trim md) "```"))))))

(deftest no-seam-throws
  (testing "an element with no _rfMvChartState seam throws :no-chart-state"
    (let [d (try (export/share-url #js {})
                 (catch :default e (ex-data e)))]
      (is (= "chart-element carries no MachineChart state seam" (:reason d))))))
