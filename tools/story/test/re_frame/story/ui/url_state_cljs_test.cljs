(ns re-frame.story.ui.url-state-cljs-test
  "CLJS-only surfaces of the URL-state engine: the browser codec
  round-trip, pushState idempotence, `parse-current-url-or-empty` and the
  popstate listener's install contract. The pure pieces are in
  `re-frame.story.ui.url-state-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string               :as str]
            [re-frame.story.share         :as rf.story.share]
            [re-frame.story.ui.url-state  :as rf.story.ui.url-state]))

;; ---- the window stub ----------------------------------------------------
;;
;; `:node-test` has no `window` and `:browser-test` never loads a
;; `-cljs-test` namespace, so the rows that touch `window` run against this
;; stub. `url-state/safe-window` is `(when (exists? js/window) js/window)`,
;; so setting `globalThis.window` satisfies the whole module.

(defn- install-window-stub!
  "Install a minimal `window` whose `location.search` is `search-str`
  (default blank) and whose listeners are counted. Returns the registry
  atom `{event-type → [listener ...]}`."
  ([] (install-window-stub! ""))
  ([search-str]
    (let [registry (atom {})
          location #js {:pathname "/" :search search-str :hash ""}
          window   #js {:location location
                        :history  #js {:pushState    (fn [& _] nil)
                                       :replaceState (fn [& _] nil)}
                        :addEventListener
                        (fn [type listener]
                          (swap! registry update type (fnil conj []) listener))
                        :removeEventListener
                        (fn [type listener]
                          (swap! registry update type
                                 (fnil (fn [xs] (vec (remove #(= % listener) xs)))
                                       [])))
                        :dispatchEvent
                        (fn [event]
                          (doseq [l (get @registry (.-type event) [])]
                            (l event)))}]
      (set! (.-window js/globalThis) window)
      registry)))

(defn- uninstall-window-stub! []
  (js-delete js/globalThis "window"))

(defn- popstate-listener-count [registry]
  (count (get @registry "popstate" [])))

;; ---- params-from-state via the public share encoder ---------------------

(deftest params-from-state-feeds-share-build-params
  (testing "params-from-state → build-params → the browser's URLSearchParams
            → parse-params returns the projection, through the CLJS
            encoder and decoders"
    (let [usp (js/URLSearchParams.
                (str/join "&" (rf.story.share/build-params
                                (rf.story.ui.url-state/params-from-state
                                  {:selected-variant :foo/bar
                                   :active-mode-tab  {:foo/bar :test}
                                   :active-modes     [:m/dark]
                                   :viewport         {:width 800 :height 600}
                                   :background       "#abc123"
                                   :tag-filter       #{:tag/x}
                                   :cell-overrides   {:foo/bar {:label "Hi"}}
                                   :substrate        :uix}))))]
      (is (= {:variant-id     :foo/bar
              :workspace-id   nil
              :mode-tab       :test
              :active-modes   [:m/dark]
              :viewport       {:width 800 :height 600}
              :background     "#abc123"
              :tag-filter     #{:tag/x}
              :cell-overrides {:label "Hi"}
              :substrate      :uix}
             (rf.story.share/parse-params
               (into {} (map (fn [k] [k (.get usp k)]))
                     (map name rf.story.share/story-query-keys))))))))

;; ---- pushState idempotence ----------------------------------------------

(defn- with-history-spy
  "Spy on `window.history.pushState`; returns the captured-URLs atom and a
  restore fn."
  []
  (let [captured (atom [])
        orig     (.-pushState (.-history js/window))
        spy      (fn [_state _title url]
                   (swap! captured conj url))]
    (set! (.-pushState (.-history js/window)) spy)
    [captured (fn [] (set! (.-pushState (.-history js/window)) orig))]))

(defn- current-url-str []
  (str (.-pathname (.-location js/window))
       (.-search   (.-location js/window))
       (.-hash     (.-location js/window))))

(deftest push!-skips-when-url-matches-current-location
  (testing "push! pushes a differing URL and declines the current one, so
            the back-stack gets no gratuitous entries"
    (install-window-stub!)
    (try
      (let [[captured restore] (with-history-spy)
            cur                (current-url-str)
            next-url           (str cur "?variant=foo%2Fbar")]
        (try
          (rf.story.ui.url-state/push! next-url)
          (is (= [next-url] @captured) "a differing URL is pushed as given")
          (reset! captured [])
          (rf.story.ui.url-state/push! cur)
          (is (= [] @captured) "no pushState when the URL matches")
          (finally (restore))))
      (finally (uninstall-window-stub!)))))

;; ---- popstate listener install/teardown ---------------------------------

(deftest popstate-listener-reinstall-replaces-rather-than-stacks
  (testing "after a double install exactly ONE popstate listener is
            registered and one popstate fires the apply-fn once; teardown
            removes it"
    (let [registry (install-window-stub!)
          fires    (atom 0)
          apply-fn (fn [s _parsed] (swap! fires inc) s)
          shell    (atom {})]
      (try
        (rf.story.ui.url-state/install-popstate-listener! shell apply-fn)
        (rf.story.ui.url-state/install-popstate-listener! shell apply-fn)
        (is (= 1 (popstate-listener-count registry)))
        (.dispatchEvent js/window #js {:type "popstate"})
        (is (= 1 @fires))
        (rf.story.ui.url-state/remove-popstate-listener!)
        (is (= 0 (popstate-listener-count registry)))
        (finally
          (rf.story.ui.url-state/remove-popstate-listener!)
          (uninstall-window-stub!))))))

;; ---- populated → omitted/default transition -----------------------------

(deftest parse-current-url-or-empty-returns-empty-shape-on-blank-search
  (testing "a blank search answers the all-nil parsed shape, never nil, so
            a no-query popstate drives the URL-owned slots to their defaults"
    (install-window-stub! "")
    (try
      (let [parsed (rf.story.ui.url-state/parse-current-url-or-empty)]
        (is (map? parsed))
        (is (every? nil? (vals parsed))))
      (finally (uninstall-window-stub!))))
  (testing "a populated search is parsed — the nils above are about the
            blank search, not a function that never reads the URL"
    (install-window-stub! "?variant=foo%2Fbar&viewport=tablet")
    (try
      (is (= [:foo/bar :tablet]
             ((juxt :variant-id :viewport)
              (rf.story.ui.url-state/parse-current-url-or-empty))))
      (finally (uninstall-window-stub!)))))
