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

(def compound-definition
  {:initial :authenticated
   :states  {:authenticated {:initial :cart
                             :states  {:cart {:initial :browsing
                                              :states  {:browsing {} :checkout {}}}}}}})

(def parallel-definition
  {:type :parallel
   :regions {:data {:initial :clean :states {:clean {} :dirty {}}}
             :form {:initial :idle  :states {:idle {} :busy {}}}}})

(deftest share-url-compound-current-state-rides-through
  (testing "a COMPOUND vector-path :current-state on the seam round-trips (rf2-9l8h8)"
    (let [el  (stub-element (assoc seam
                                   :definition compound-definition
                                   :current-state [:authenticated :cart :browsing]))
          cs  (:rf.machines-viz.share/chart (share/decode-share-url (export/share-url el)))]
      (is (= {:state [:authenticated :cart :browsing]} (:snapshot cs))))))

(deftest share-url-parallel-current-state-rides-through
  (testing "a PARALLEL region-map :current-state on the seam round-trips (rf2-9l8h8)"
    (let [el  (stub-element (assoc seam
                                   :definition parallel-definition
                                   :region-count 2
                                   :current-state {:data :dirty :form :busy}))
          cs  (:rf.machines-viz.share/chart (share/decode-share-url (export/share-url el)))]
      (is (= {:state {:data :dirty :form :busy}} (:snapshot cs))))))

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

;; ---- rf2-85a9do — SVG <title>/<desc> XML escaping -----------------------
;;
;; `chart-as-svg` is browser-DOM-only, but it builds <title>/<desc> by hand
;; (bypassing the XMLSerializer that escapes the foreignObject viewport
;; clone). `svg-title+desc` is the PURE seam that does that construction, so
;; the escaping contract is node-testable here.

(deftest svg-title+desc-escapes-xml-significant-chars
  (testing "a machine id carrying & < > emits ESCAPED entities, never raw markup"
    (let [s (export/svg-title+desc
              {:machine-id (keyword "evil/a&b<c>d")
               :node-count 1 :edge-count 0 :region-count 0})]
      ;; The raw XML-significant chars from the id never appear unescaped
      ;; INSIDE the title text (the surrounding <title>…</title> tag chars
      ;; are the only legitimate `<`/`>` — assert on the entities instead).
      (is (str/includes? s "&amp;") "& is escaped to &amp;")
      (is (str/includes? s "&lt;")  "< is escaped to &lt;")
      (is (str/includes? s "&gt;")  "> is escaped to &gt;")
      ;; No double-escaping: a single & became &amp;, not &amp;amp;.
      (is (not (str/includes? s "&amp;amp;")) "& not double-escaped")
      ;; The raw `a&b` substring must NOT survive as injected markup.
      (is (not (str/includes? s "a&b")) "raw ampersand not injected")
      (is (not (str/includes? s "b<c")) "raw < not injected")
      (is (not (str/includes? s "c>d")) "raw > not injected"))))

(deftest svg-title+desc-escapes-current-state-label
  (testing "an XML-significant current-state label is escaped inside <desc>"
    (let [s (export/svg-title+desc
              {:machine-id    :ok/plain
               :current-state (keyword "weird" "x&<y>")
               :node-count    2 :edge-count 1 :region-count 0})]
      (is (str/includes? s "<desc>") "still emits a <desc>")
      ;; The label's significant chars land in the summary, escaped.
      (is (str/includes? s "&amp;"))
      (is (str/includes? s "&lt;"))
      (is (str/includes? s "&gt;"))
      ;; The literal label fragment must not appear raw.
      (is (not (str/includes? s "x&<y>")) "raw label markup not injected"))))

(deftest svg-title+desc-default-and-quotes
  (testing "missing machine-id defaults to :machine; quotes/apostrophes escaped"
    (let [missing (export/svg-title+desc {:node-count 0 :edge-count 0})
          quoted  (export/svg-title+desc
                    {:machine-id (keyword "q\"a'b")
                     :node-count 0 :edge-count 0})]
      (is (str/includes? missing "<title>machine</title>")
          "nil :machine-id defaults to :machine")
      (is (str/includes? quoted "&quot;") "\" escaped to &quot;")
      (is (str/includes? quoted "&apos;") "' escaped to &apos;"))))
