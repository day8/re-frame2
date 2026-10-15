(ns re-frame.testbed.open-in-editor-client-cljs-test
  "Endpoint requests and coordinate-preserving URI fallback."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]))

(def ^:private coord {:file "src/app.cljs" :line 27 :column 9})
(def ^:private custom-editor {:custom "myeditor://open?f={file}&l={line}&c={column}"})

;; Fetch is shared with every later namespace in the bundle.
(def ^:private real-fetch (.-fetch js/globalThis))

(defn- fetch-restored? []
  (identical? real-fetch (.-fetch js/globalThis)))

(defn- click!
  "Run the real client against one mocked endpoint response."
  [{:keys [editor status]}]
  (let [requested (atom nil)
        navigated (atom nil)
        fallbacks (atom 0)
        original  (.-fetch js/globalThis)
        restore!  (fn [] (set! (.-fetch js/globalThis) original))]
    (set! (.-fetch js/globalThis)
          (fn [url _opts]
            (reset! requested url)
            (js/Promise.resolve #js {:ok (<= 200 status 299) :status status})))
    (-> (rf.source-coords.open-endpoint/fetch-launcher!
          (rf.source-coords.open-endpoint/build-url coord editor)
          (fn []
            (swap! fallbacks inc)
            (reset! navigated (rf.source-coords.editor-uri/editor-uri editor coord))))
        (.then (fn [_]
                 (restore!)
                 {:requested @requested
                  :navigated @navigated
                  :fallbacks @fallbacks}))
        (.catch (fn [err] (restore!) (throw err))))))

(defn- click-each!
  "Serialize global fetch stubs so their restore scopes cannot overlap."
  [specs]
  (reduce (fn [p spec]
            (.then p (fn [acc] (.then (click! spec) #(conj acc %)))))
          (js/Promise.resolve [])
          specs))

(deftest endpoint-request-carries-the-whole-coordinate
  (testing "Named editors add a hint; nil/custom requests preserve the coordinate without one."
    (let [bare (str rf.source-coords.open-endpoint/endpoint-path
                    "?file=src%2Fapp.cljs&line=27&column=9")]
      (is (= [(str bare "&editor=windsurf") bare bare]
             (map #(rf.source-coords.open-endpoint/build-url coord %)
                  [:windsurf nil custom-editor]))))))

(deftest open-coord-hands-the-built-url-to-the-launcher
  (let [seen (atom nil)
        prev (rf.source-coords.open-endpoint/set-launcher!
               (fn [url _fallback!] (reset! seen url)))]
    (try
      (rf.source-coords.open-endpoint/open-coord! coord :windsurf (fn [] nil))
      (is (= (rf.source-coords.open-endpoint/build-url coord :windsurf) @seen))
      (finally
        (rf.source-coords.open-endpoint/set-launcher! prev)))))

(deftest a-2xx-answer-suppresses-the-fallback
  (testing "A successful endpoint answer suppresses URI fallback."
    (async done
      (-> (click! {:editor :windsurf :status 200})
          (.then (fn [{:keys [requested fallbacks]}]
                   (is (= [true 0 true] [(some? requested) fallbacks (fetch-restored?)]))
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest declined-no-hint-answer-still-lands-on-the-coordinate
  (testing "Declined default/custom requests preserve the coordinate in one fallback."
    (async done
      (-> (click-each! [{:editor nil :status 422} {:editor custom-editor :status 422}])
          (.then (fn [outcomes]
                   (is (= [[1 "vscode://file/src/app.cljs:27:9"]
                           [1 "myeditor://open?f=src/app.cljs&l=27&c=9"]
                           true]
                          (conj (mapv (juxt :fallbacks :navigated) outcomes) (fetch-restored?))))
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))
