(ns re-frame.testbed.open-in-editor-client-cljs-test
  "Client half of the open-in-editor contract (rf2-1i1ec).

  `re-frame.testbed.open-in-editor-server` decides; this namespace pins what
  the BROWSER does with that decision, because the defect rf2-1i1ec fixes
  lives across the seam rather than on either side of it. The endpoint's 200
  was a claim about a child process EXITING, not about the source coordinate
  ARRIVING: `launch-editor` has no `windsurf` case in its `get-args.js`
  switch, so it launched Windsurf with the bare file, exited 0, and the
  endpoint answered 200 — and that 200 is exactly what makes
  `fetch-launcher!` skip the `windsurf://file/…:27:9` fallback that would
  have carried the coordinate. Every layer reported success; the programmer
  landed at the wrong line.

  A server-only test asserting a status code would pass while that
  user-visible defect survived, so the property pinned here is the one the
  user feels: a DECLINED endpoint answer runs the coordinate-preserving
  fallback exactly once, and a 2xx suppresses it.

  The server-side half is `re-frame.testbed.open-in-editor-server-test`.

  This suite drives the REAL client seam — `build-url` and `fetch-launcher!`
  from `re-frame.source-coords.open-endpoint`, unmodified — with
  `globalThis.fetch` stubbed to answer a chosen status. Nothing is launched
  and no dev server is required, which is what makes it runnable in CI."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]))

(def ^:private coord
  "The bead's reproduction coordinate: `src/app.cljs`, line 27, column 9."
  {:file "src/app.cljs" :line 27 :column 9})

(def ^:private windsurf-uri
  "What the coordinate-preserving fallback produces for `coord`. Derived from
  `editor-uri` rather than typed, so this suite cannot drift from the URI
  builder it is asserting reaches the OS."
  (rf.source-coords.editor-uri/editor-uri :windsurf coord))

(def ^:private declining-statuses
  "Every non-2xx the server can answer with: 400 (`missing-file`,
  `malformed-query`), 403 (`forbidden`), 405 (`method-not-allowed`), 422
  (`file-not-found`, `launch-failed`, and — rf2-1i1ec — the Windsurf
  `editor-position-unsupported` decline). The client's contract is on the
  CLASS, not on any one member: whatever the endpoint declines with, the
  coordinate-preserving URI gets its turn."
  [400 403 405 422])

(def ^:private real-fetch
  "The platform `fetch`, captured at load.

  `click!` swaps this process global and puts it back. Every test below
  asserts the global is `identical?` to THIS again afterwards, because a stub
  left installed would silently answer for every later namespace in the shared
  `:node-test` build — a leak a green suite hides rather than reports."
  (.-fetch js/globalThis))

(defn- ->response
  "A minimal `fetch` Response stand-in. `fetch-launcher!` reads only `.ok`,
  which the platform derives from the status the same way."
  [status]
  #js {:ok (and (>= status 200) (<= status 299)) :status status})

(defn- click!
  "Drive one source-coord click through the real client seam and return a
  promise of what the user would have got.

  `globalThis.fetch` is replaced by a stub answering `status` — standing in
  for the dev-server's decision — and the fallback thunk is the one the Xray
  and Story open-seams pass: it resolves the coordinate through
  `rf.source-coords.editor-uri/editor-uri` and hands the URI to a capturing navigator instead
  of `Location.assign`.

  Resolves to `{:requested <endpoint url> :navigated <uri-or-nil>
  :fallbacks <n>}`. The original `fetch` is restored on both outcomes."
  [{:keys [editor status]}]
  (let [requested (atom nil)
        navigated (atom nil)
        fallbacks (atom 0)
        original  (.-fetch js/globalThis)
        restore!  (fn [] (set! (.-fetch js/globalThis) original))]
    (set! (.-fetch js/globalThis)
          (fn [url _opts]
            (reset! requested url)
            (js/Promise.resolve (->response status))))
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
  "Run `click!` over `specs` ONE AT A TIME, resolving to a vector of outcomes
  each merged with its spec.

  Sequential deliberately. `click!` swaps a process global, so overlapping
  runs nest their save/restore — the second saves the first's stub as the
  `original` it will later reinstate, and the suite finishes with a stub still
  answering `fetch` for every namespace after it. `js/Promise.all` over these
  is exactly that mistake; `real-fetch` is the assertion that catches it."
  [specs]
  (reduce (fn [p spec]
            (.then p (fn [acc]
                       (.then (click! spec)
                              (fn [outcome] (conj acc (merge spec outcome)))))))
          (js/Promise.resolve [])
          specs))

(defn- fetch-restored?
  "Whether the process-global `fetch` is the platform's again."
  []
  (identical? real-fetch (.-fetch js/globalThis)))

;; ---- the request the client sends ---------------------------------------

(deftest endpoint-request-carries-the-whole-coordinate
  (testing "the client asks for 27:9 explicitly — the coordinate is present
            in the request, so any later loss is the server's or the
            launcher's, not a malformed ask"
    (let [url (rf.source-coords.open-endpoint/build-url coord :windsurf)]
      (is (= (str rf.source-coords.open-endpoint/endpoint-path
                  "?file=src%2Fapp.cljs&line=27&column=9&editor=windsurf")
             url)))))

(deftest open-coord-hands-the-built-url-to-the-launcher
  (testing "`open-coord!` composes `build-url` with the launcher seam, so the
            contract pinned below on `fetch-launcher!` is the contract the
            tool open-seams actually get"
    (let [seen (atom nil)
          prev (rf.source-coords.open-endpoint/set-launcher!
                 (fn [url _fallback!] (reset! seen url)))]
      (try
        (rf.source-coords.open-endpoint/open-coord! coord :windsurf (fn [] nil))
        (is (= (rf.source-coords.open-endpoint/build-url coord :windsurf) @seen))
        (finally
          (rf.source-coords.open-endpoint/set-launcher! prev))))))

;; ---- what the client does with the server's answer -----------------------

(deftest declined-answer-runs-the-coordinate-preserving-fallback-once
  (testing "rf2-1i1ec — a declined endpoint answer hands the launch to the
            `windsurf://` URI, which carries 27:9, exactly once"
    (async done
      (-> (click! {:editor :windsurf :status 422})
          (.then (fn [{:keys [requested navigated fallbacks]}]
                   (is (some? requested)
                       "the stub fetch was reached — the endpoint IS preferred;
                        this also proves the stub took effect, so the assertions
                        below are about the chosen status and not a thrown fetch")
                   (is (= 1 fallbacks) "the fallback ran exactly once, not twice")
                   (is (= "windsurf://file/src/app.cljs:27:9" navigated))
                   (is (= windsurf-uri navigated)
                       "and it is the URI `editor-uri` builds — line 27, column 9
                        reach Windsurf after all")
                   (is (fetch-restored?) "the fetch stub was not left installed")
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest every-declining-status-reaches-the-fallback
  (testing "the client's contract is on non-2xx as a CLASS: each status the
            endpoint can decline with runs the coordinate-preserving fallback
            exactly once, so the server may choose any of them"
    (async done
      (-> (click-each! (map (fn [status] {:editor :windsurf :status status})
                            declining-statuses))
          (.then (fn [outcomes]
                   (is (= (count declining-statuses) (count outcomes))
                       "every declining status was actually exercised")
                   (doseq [{:keys [status navigated fallbacks]} outcomes]
                     (is (= 1 fallbacks) (str "status " status " → one fallback"))
                     (is (= windsurf-uri navigated)
                         (str "status " status " → the coordinate survived")))
                   (is (fetch-restored?) "the fetch stub was not left installed")
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest a-2xx-answer-suppresses-the-fallback
  (testing "rf2-1i1ec, the other half of the defect: a 200 is FINAL to this
            client — the URI fallback never runs, so the coordinate a
            bare-file launch dropped is gone for good. This is why the repair
            had to be the endpoint declining rather than anything downstream"
    (async done
      (-> (click! {:editor :windsurf :status 200})
          (.then (fn [{:keys [requested navigated fallbacks]}]
                   (is (some? requested) "the endpoint was asked")
                   (is (zero? fallbacks)
                       "a 2xx suppresses the fallback — had the endpoint kept
                        answering 200 for Windsurf, nothing downstream could
                        have recovered 27:9")
                   (is (nil? navigated)
                       "no coordinate-preserving URI was ever navigated")
                   (is (fetch-restored?) "the fetch stub was not left installed")
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

;; ---- the no-hint path (rf2-1i1ec audit) ----------------------------------
;;
;; `editor->param` sends no `editor=` at all for a nil preference and for
;; `{:custom …}`, which puts the server on `launch-editor`'s auto-detect —
;; where the binary is chosen from the running process list and can be one
;; `get-args.js` has no position case for. The server now declines that too.
;; What the tests below witness is the half the server cannot: that declining
;; actually leaves the user better off, because the fallback these two
;; preferences reach still carries 27:9.

(def ^:private custom-editor
  "A `{:custom …}` preference, the other shape that sends no `editor=`."
  {:custom "myeditor://open?f={file}&l={line}&c={column}"})

(deftest no-editor-hint-requests-take-the-auto-detect-path
  (testing "both preferences the audit names omit `editor=` entirely, so the
            server auto-detects — this is the request that could return a
            bare-file 200 before the repair"
    (is (= (str rf.source-coords.open-endpoint/endpoint-path
                "?file=src%2Fapp.cljs&line=27&column=9")
           (rf.source-coords.open-endpoint/build-url coord nil))
        "a nil preference sends the coordinate and no editor")
    (is (= (str rf.source-coords.open-endpoint/endpoint-path
                "?file=src%2Fapp.cljs&line=27&column=9")
           (rf.source-coords.open-endpoint/build-url coord custom-editor))
        "a {:custom …} preference likewise — the template is the client's own
         business, so the server is told nothing about it")
    (is (not (re-find #"editor=" (rf.source-coords.open-endpoint/build-url coord nil)))
        "control: `editor=` really is absent, not merely differently spelled")
    (is (re-find #"editor=" (rf.source-coords.open-endpoint/build-url coord :windsurf))
        "control the other way: the same assertion FINDS `editor=` when a
         keyword preference is set, so its absence above is a real difference")))

(deftest declined-no-hint-answer-still-lands-on-the-coordinate
  (testing "rf2-1i1ec audit — declining the auto-detect path is only a repair
            if the fallback it hands over to keeps 27:9. For a nil preference
            that is `editor-uri`'s default scheme; for `{:custom …}` it is the
            user's own template, which the endpoint's auto-detect ignored
            entirely. Both carry the coordinate the bare-file launch dropped"
    (async done
      (-> (click-each! [{:editor nil            :status 422}
                        {:editor custom-editor :status 422}])
          (.then (fn [outcomes]
                   (is (= 2 (count outcomes)) "both preferences were exercised")
                   (doseq [{:keys [editor fallbacks]} outcomes]
                     (is (= 1 fallbacks)
                         (str editor " → the fallback ran exactly once")))
                   (let [{:keys [navigated]} (first outcomes)]
                     (is (= "vscode://file/src/app.cljs:27:9" navigated)
                         "a nil preference falls back to the default scheme,
                          line 27 column 9 intact"))
                   (let [{:keys [navigated]} (second outcomes)]
                     (is (= "myeditor://open?f=src/app.cljs&l=27&c=9" navigated)
                         "a {:custom …} preference gets its OWN template with
                          27 and 9 substituted — declining honours the
                          configuration the auto-detect launch overrode"))
                   (is (fetch-restored?) "the fetch stub was not left installed")
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest a-2xx-no-hint-answer-suppresses-the-fallback-too
  (testing "the defect's other half on the auto-detect path: a 200 is final
            here as well, so a bare-file launch behind it is unrecoverable.
            This is why the server had to decline rather than let the client
            decide"
    (async done
      (-> (click! {:editor nil :status 200})
          (.then (fn [{:keys [requested navigated fallbacks]}]
                   (is (some? requested) "the endpoint was asked")
                   (is (zero? fallbacks)
                       "no fallback — nothing downstream could recover 27:9")
                   (is (nil? navigated))
                   (is (fetch-restored?) "the fetch stub was not left installed")
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))

(deftest position-carrying-editors-keep-preferring-the-endpoint
  (testing "the repair must not make every editor fall back: for an editor the
            endpoint still serves, a 2xx is final and no URI is navigated —
            the positive control for rf2-1i1ec"
    (async done
      (-> (click-each! (map (fn [editor] {:editor editor :status 200})
                            [:vscode :cursor :zed :idea]))
          (.then (fn [outcomes]
                   (is (= 4 (count outcomes))
                       "every position-carrying editor was actually exercised")
                   (doseq [{:keys [editor requested navigated fallbacks]} outcomes]
                     (is (some? requested) (str editor " reached the endpoint"))
                     (is (zero? fallbacks)
                         (str editor " kept the endpoint's success — no fallback"))
                     (is (nil? navigated) (str editor " navigated no URI")))
                   (is (fetch-restored?) "the fetch stub was not left installed")
                   (done)))
          (.catch (fn [err] (is false (str "click! threw: " err)) (done)))))))
