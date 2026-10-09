(ns day8.re-frame2-machines-viz.export-cljs-test
  "The chart exporters' node-testable paths, driven through a stub element
  carrying the `_rfMvChartState` seam the chart's root `:ref` sets in
  production. The PNG / SVG rasterisers are browser-DOM-only and live in
  `export-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.export :as export]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.share :as share]))

(def ^:private test-host "https://x/viewer.html")

(def definition
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(defn- stub-element
  "A stand-in for the chart's root DOM node carrying the seam directly, so
  `chart-root` returns it without a DOM walk."
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

(defn- shared-chart [el opts]
  (:rf.machines-viz.share/chart (share/decode-share-url (export/share-url el opts))))

(deftest share-url-from-seam
  (testing "share-url projects the seam to a ChartState: every :current-state
            arm rides verbatim as the snapshot (none → no snapshot), and no
            :frame-id is fabricated"
    (doseq [state [:loading [:authenticated :cart :browsing] {:data :dirty :form :busy} nil]]
      (is (= (cond-> {:machine-id :auth/login-flow :definition definition}
               state (assoc :snapshot {:state state}))
             (shared-chart (stub-element (assoc seam :current-state state)) {:host test-host}))
          (pr-str state)))))

(deftest share-url-honours-host-and-frame
  (testing ":host + :frame-id opts thread through"
    (let [url (export/share-url (stub-element seam) {:host "https://acme/v.html" :frame-id :app/main})]
      (is (str/starts-with? url "https://acme/v.html#machine="))
      (is (= :app/main (get-in (share/decode-share-url url)
                               [:rf.machines-viz.share/chart :frame-id]))))))

(deftest mermaid-from-seam
  (testing "chart-as-mermaid emits the seam definition's Mermaid, passing opts
            through"
    (let [el (stub-element seam)]
      (is (= (mermaid/emit definition) (export/chart-as-mermaid el)))
      (is (= (mermaid/emit definition {:fenced? false})
             (export/chart-as-mermaid el {:fenced? false}))))))

(deftest no-seam-throws
  (testing "an element that is not a rendered MachineChart throws :no-chart-state"
    (is (= :rf.machines-viz.export/no-chart-state
           (:rf.error/id (try (export/share-url #js {} {:host test-host})
                              (catch :default e (ex-data e))))))))

(deftest svg-title+desc-escapes-xml-significant-chars
  (testing "all five XML-significant chars in a machine id are escaped exactly
            once, in both the <title> and the <desc> (`chart-as-svg` builds
            them by hand, outside the XMLSerializer that escapes the viewport)"
    (let [s (export/svg-title+desc {:machine-id (keyword "evil" "a&b<c>d\"e'f")
                                    :node-count 1 :edge-count 0})]
      (is (= 2 (count (re-seq #"a&amp;b&lt;c&gt;d&quot;e&apos;f" s))) s)))
  (testing "a missing machine-id titles the SVG `machine`"
    (is (str/includes? (export/svg-title+desc {:node-count 0 :edge-count 0})
                       "<title>machine</title>"))))

;; ---- clipboard guard tolerates an ABSENT ClipboardItem -----------------
;;
;; A browser can carry `navigator.clipboard` but no `ClipboardItem` global
;; (older Firefox, some non-secure contexts). The copy fns' guard must then
;; REJECT with `:no-clipboard`, never throw the ReferenceError a bare
;; `js/ClipboardItem` reference raises (synchronously, for copy-svg). The
;; SVG / PNG producers are browser-only, so they are redefined away to
;; isolate the guard under node.

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
  (testing "with navigator.clipboard present but ClipboardItem ABSENT,
            copy-svg/png-to-clipboard! REJECT with :no-clipboard — never a
            ReferenceError, and copy-svg does not throw synchronously"
    (async done
      (if-let [restore! (install-clipboard-env!)]
        (with-redefs [export/chart-as-svg  (fn [_] "<svg/>")
                      export/chart-as-png! (fn [_] (js/Promise.resolve #js {}))]
          (let [el       (stub-element seam)
                no-clip? (fn [e]
                           (= :rf.machines-viz.export/no-clipboard
                              (:rf.error/id (ex-data e))))
                ;; A synchronous throw becomes a rejection the assertion sees;
                ;; the two-arg `.then` attaches both handlers at once, so no
                ;; rejection is ever momentarily unhandled (fatal under node).
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
