(ns re-frame.testbed.open-in-editor-client-cljs-test
  "Client half of the open-in-editor contract: what the browser does with the
  endpoint's answer. A declined answer must run the coordinate-preserving
  `editor://` fallback exactly once and a 2xx must suppress it, because a 200
  for a launch that dropped the coordinate would leave the user at the wrong
  line. Drives the real `build-url` and `fetch-launcher!` with
  `globalThis.fetch` stubbed. The server half is
  `re-frame.testbed.open-in-editor-server-test`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]))

(def ^:private coord {:file "src/app.cljs" :line 27 :column 9})

(def ^:private custom-editor
  "A `{:custom …}` preference, which like nil sends no `editor=`."
  {:custom "myeditor://open?f={file}&l={line}&c={column}"})

(def ^:private real-fetch
  "The platform `fetch`. A stub left installed would answer for every later
  namespace in the shared `:node-test` build."
  (.-fetch js/globalThis))

(defn- fetch-restored? []
  (identical? real-fetch (.-fetch js/globalThis)))

(defn- click!
  "One source-coord click through the real client seam, with `fetch` answering
  `status`. Resolves to `{:requested url :navigated uri-or-nil :fallbacks n}`."
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
  "`click!` over `specs` one at a time: overlapping runs would nest the global
  `fetch` save/restore and leave a stub installed."
  [specs]
  (reduce (fn [p spec]
            (.then p (fn [acc] (.then (click! spec) #(conj acc %)))))
          (js/Promise.resolve [])
          specs))

(deftest endpoint-request-carries-the-whole-coordinate
  (testing "every request carries 27:9; only a named editor adds `editor=`, so
            a nil or `{:custom …}` preference leaves the server to auto-detect"
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

(deftest every-declining-status-reaches-the-fallback
  (testing "each non-2xx the endpoint answers with (400, 403, 405, 422) runs
            the fallback exactly once"
    (async done
      (-> (click-each! (for [status [400 403 405 422]] {:editor :windsurf :status status}))
          (.then (fn [outcomes]
                   (is (= [[1 1 1 1] true] [(mapv :fallbacks outcomes) (fetch-restored?)]))
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest a-2xx-answer-suppresses-the-fallback
  (testing "a 200 is final: the fallback never runs"
    (async done
      (-> (click! {:editor :windsurf :status 200})
          (.then (fn [{:keys [requested fallbacks]}]
                   (is (= [true 0 true] [(some? requested) fallbacks (fetch-restored?)]))
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest declined-no-hint-answer-still-lands-on-the-coordinate
  (testing "a declined auto-detect request falls back once to a URI that keeps
            27:9: the default scheme for nil, the user's own template for
            `{:custom …}`"
    (async done
      (-> (click-each! [{:editor nil :status 422} {:editor custom-editor :status 422}])
          (.then (fn [outcomes]
                   (is (= [[1 "vscode://file/src/app.cljs:27:9"]
                           [1 "myeditor://open?f=src/app.cljs&l=27&c=9"]
                           true]
                          (conj (mapv (juxt :fallbacks :navigated) outcomes) (fetch-restored?))))
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))
