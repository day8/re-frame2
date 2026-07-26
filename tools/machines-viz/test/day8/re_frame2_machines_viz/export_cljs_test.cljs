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
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.export :as export]
            [day8.re-frame2-machines-viz.share :as share]))

;; `share-url` has no default host (rf2-8m344) — every caller names the
;; viewer page it hosts, tests included.
(def ^:private test-host "https://x/viewer.html")

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
          url (export/share-url el {:host test-host})
          env (share/decode-share-url url)
          cs  (:rf.machines-viz.share/chart env)]
      (is (str/includes? url "#machine="))
      (is (= :auth/login-flow (:machine-id cs)))
      (is (= definition (:definition cs)))
      (is (not (contains? cs :frame-id))
          "no :frame-id is fabricated off the seam (v2 / EP-0023 — frame-id is optional)")
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
          cs  (:rf.machines-viz.share/chart (share/decode-share-url (export/share-url el {:host test-host})))]
      (is (= {:state [:authenticated :cart :browsing]} (:snapshot cs))))))

(deftest share-url-parallel-current-state-rides-through
  (testing "a PARALLEL region-map :current-state on the seam round-trips (rf2-9l8h8)"
    (let [el  (stub-element (assoc seam
                                   :definition parallel-definition
                                   :region-count 2
                                   :current-state {:data :dirty :form :busy}))
          cs  (:rf.machines-viz.share/chart (share/decode-share-url (export/share-url el {:host test-host})))]
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
                (share/decode-share-url (export/share-url el {:host test-host})))]
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
  (testing "an element that is not a rendered MachineChart throws :no-chart-state"
    (let [d (try (export/share-url #js {} {:host test-host})
                 (catch :default e (ex-data e)))]
      ;; rf2-vvixub / rf2-s6rzia — branch on the canonical :rf.error/id, and
      ;; the :reason names the PUBLIC concept (a rendered MachineChart
      ;; element) without leaking the private "state seam" implementation term.
      (is (= :rf.machines-viz.export/no-chart-state (:rf.error/id d)))
      (is (string? (:reason d)))
      (is (re-find #"(?i)machinechart" (:reason d)))
      (is (not (re-find #"(?i)seam" (:reason d)))
          ":reason no longer leaks the private 'state seam' implementation term"))))

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

;; ---- rf2-848byi — clipboard guard tolerates an ABSENT ClipboardItem -----
;;
;; `copy-svg-to-clipboard!` / `copy-png-to-clipboard!` guard clipboard
;; availability with `(exists? js/ClipboardItem)`. On a browser that has
;; `navigator.clipboard` but NO `ClipboardItem` global (older Firefox that
;; shipped `clipboard.writeText` before `ClipboardItem`; some non-secure
;; contexts), the guard must fall through to the typed `:no-clipboard`
;; REJECTION — not throw a bare-global ReferenceError (the pre-fix bare
;; `js/ClipboardItem` reference did, SYNCHRONOUSLY for copy-svg).
;;
;; The SVG/PNG producers are browser-DOM-only, so we `with-redefs` them to
;; isolate the GUARD (the unit under test) and drive it under node with a
;; STUBBED `navigator.clipboard` present + `ClipboardItem` deleted.

(defn- install-clipboard-env!
  "Install `globalThis.navigator` = a stub carrying a `.clipboard.write`, and
  DELETE `globalThis.ClipboardItem`. Returns a 0-arg `restore!` thunk, or nil
  when the runtime pins `navigator` non-configurably (the caller then skips —
  the browser gate exercises the guard)."
  []
  (let [g        js/globalThis
        nav-desc (js/Object.getOwnPropertyDescriptor g "navigator")
        ci-desc  (js/Object.getOwnPropertyDescriptor g "ClipboardItem")]
    (when (or (nil? nav-desc) (.-configurable nav-desc))
      (js/Object.defineProperty
        g "navigator"
        #js {:value #js {:clipboard #js {:write (fn [_] (js/Promise.resolve "ok"))}}
             :configurable true :writable true})
      (js/Reflect.deleteProperty g "ClipboardItem")
      (fn restore! []
        (if nav-desc
          (js/Object.defineProperty g "navigator" nav-desc)
          (js/Reflect.deleteProperty g "navigator"))
        (when ci-desc
          (js/Object.defineProperty g "ClipboardItem" ci-desc))))))

(deftest clipboard-guard-tolerates-absent-clipboarditem
  (testing "rf2-848byi — with navigator.clipboard present but ClipboardItem
            ABSENT, copy-svg/png-to-clipboard! REJECT with :no-clipboard —
            never a bare-global ReferenceError, and copy-svg does not throw
            synchronously (the `.catch` contract holds)."
    (async done
      (if-let [restore! (install-clipboard-env!)]
        (with-redefs [export/chart-as-svg  (fn [_] "<svg/>")
                      export/chart-as-png! (fn [_] (js/Promise.resolve #js {}))]
          (let [;; a valid-seam element so copy-png's synchronous
                ;; chart-state-of / alt-text preamble does not throw — the
                ;; GUARD (isolated by the with-redefs above) is what's tested.
                el       (stub-element seam)
                no-clip? (fn [e]
                           (= :rf.machines-viz.export/no-clipboard
                              (:rf.error/id (ex-data e))))
                ;; Drive one copy fn and assert it REJECTS with :no-clipboard.
                ;; A two-arg `.then` attaches BOTH the fulfil + reject handlers
                ;; in ONE call, synchronously on the copy result — so no
                ;; rejected promise is ever momentarily handler-less (the
                ;; node-test runner treats a stray unhandled rejection as
                ;; fatal). The `try` guards the copy-svg SYNCHRONOUS-throw bug:
                ;; pre-fix the bare `js/ClipboardItem` reference threw right
                ;; here (before any Promise), which this turns into a
                ;; :test/sync-throw rejection → a visible assertion failure.
                run      (fn [thunk label]
                           (let [p (try (thunk)
                                        (catch :default e
                                          (js/Promise.reject
                                            (ex-info "threw synchronously"
                                                     {:rf.error/id :test/sync-throw
                                                      :threw e}))))]
                             (.then p
                                    (fn [_]
                                      (is false (str label " unexpectedly RESOLVED; "
                                                     "expected a :no-clipboard rejection")))
                                    (fn [e]
                                      (is (no-clip? e)
                                          (str label " rejects with :no-clipboard; got "
                                               (pr-str (ex-data e))))))))]
            (-> (js/Promise.all
                  #js [(run #(export/copy-svg-to-clipboard! el) "copy-svg-to-clipboard!")
                       (run #(export/copy-png-to-clipboard! el) "copy-png-to-clipboard!")])
                (.then (fn [_] (restore!) (done))
                       (fn [_] (restore!) (done))))))
        (do
          (is true (str ":node-test: navigator is non-configurable in this runtime "
                        "— the browser gate exercises the ClipboardItem guard"))
          (done))))))
