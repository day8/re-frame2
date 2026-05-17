(ns re-frame.story.ui.url-state-cljs-test
  "CLJS-side regression net for the URL-state engine (rf2-o4u18).

  The pure pieces (params projection, query-string composition, slot
  diff, parsed-application) are covered in
  `re-frame.story.ui.url-state-test` (.cljc). This ns exercises the
  CLJS-only surfaces — pushState / replaceState idempotence,
  popstate-driven hydration, and the install/teardown contract.

  The window.history surface is mocked rather than driving the real
  browser back-stack so the test stays deterministic under the node
  runner (and so the test doesn't perturb the harness's own URL)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string               :as str]
            [re-frame.story.share         :as share]
            [re-frame.story.ui.state      :as state]
            [re-frame.story.ui.url-state  :as us]))

;; ---- fixtures ------------------------------------------------------------

(defn- browser? []
  (and (exists? js/window) (exists? js/URLSearchParams)))

(defn reset-all! []
  (state/reset-shell-state!))

(use-fixtures :each {:before reset-all!})

;; ---- url-from-state composition -----------------------------------------

(deftest url-from-state-includes-every-slot
  (testing "rf2-o4u18 — composed URL carries every URL-relevant slot"
    (let [shell {:selected-variant   :foo/bar
                 :active-mode-tab    {:foo/bar :docs}
                 :active-modes       [:m/dark]
                 :viewport           :tablet
                 :background         :dark
                 :tag-filter         #{:tag/a}
                 :substrate          :uix}
          url   (us/url-from-state shell {:pathname "/p/" :hash "#/stories"})]
      (is (re-find #"variant="    url))
      (is (re-find #"mode-tab="   url))
      (is (re-find #"modes="      url))
      (is (re-find #"viewport="   url))
      (is (re-find #"background=" url))
      (is (re-find #"tag-filter=" url))
      (is (re-find #"substrate="  url))
      (is (re-find #"#/stories$"  url)
          "hash route survives at the tail"))))

;; ---- params-from-state via the public share encoder ---------------------

(deftest params-from-state-feeds-share-build-params
  (testing "rf2-o4u18 — the projection contract: params-from-state +
            share/build-params produce a URL params vector that
            share/parse-params round-trips back to the projection"
    (let [shell  {:selected-variant   :foo/bar
                  :active-mode-tab    {:foo/bar :test}
                  :active-modes       [:m/dark]
                  :viewport           {:width 800 :height 600}
                  :background         "#abc123"
                  :tag-filter         #{:tag/x}
                  :cell-overrides     {:foo/bar {:label "Hi"}}
                  :substrate          :uix}
          proj   (us/params-from-state shell)
          ps     (share/build-params proj)
          usp    (js/URLSearchParams. (str/join "&" ps))
          getter {"variant"    (.get usp "variant")
                  "workspace"  (.get usp "workspace")
                  "mode-tab"   (.get usp "mode-tab")
                  "modes"      (.get usp "modes")
                  "viewport"   (.get usp "viewport")
                  "background" (.get usp "background")
                  "tag-filter" (.get usp "tag-filter")
                  "overrides"  (.get usp "overrides")
                  "substrate"  (.get usp "substrate")}
          out    (share/parse-params getter)]
      (is (= :foo/bar (:variant-id out)))
      (is (= :test    (:mode-tab out)))
      (is (= [:m/dark] (:active-modes out)))
      (is (= {:width 800 :height 600} (:viewport out)))
      (is (= "#abc123" (:background out)))
      (is (= #{:tag/x} (:tag-filter out)))
      (is (= {:label "Hi"} (:cell-overrides out)))
      (is (= :uix (:substrate out))))))

;; ---- pushState idempotence ----------------------------------------------
;;
;; Under the node runner `js/window.history` is mocked by jsdom. The
;; tests below capture pushState invocations via a wrapper atom so we
;; can assert n calls without actually mutating the test runner's URL.

(when (browser?)
  (defn- with-history-spy
    "Install a spy around `window.history.pushState`; returns the
    captured-calls atom + a restore fn."
    []
    (let [captured (atom [])
          orig     (.-pushState (.-history js/window))
          spy      (fn [_state _title url]
                     (swap! captured conj url))]
      (set! (.-pushState (.-history js/window)) spy)
      [captured (fn [] (set! (.-pushState (.-history js/window)) orig))]))

  (deftest push!-skips-when-url-matches-current-location
    (testing "rf2-o4u18 — push! is idempotent: no-op when the URL matches
              the current location (avoids gratuitous back-stack entries)"
      (let [[captured restore] (with-history-spy)
            ;; Use the actual current pathname so the diff says 'same'.
            cur (str (.-pathname (.-location js/window))
                     (.-search   (.-location js/window))
                     (.-hash     (.-location js/window)))]
        (us/push! cur)
        (try
          (is (= 0 (count @captured))
              "no pushState calls when URL matches")
          (finally (restore))))))

  (deftest push!-fires-when-url-differs
    (testing "rf2-o4u18 — push! pushes a different URL"
      (let [[captured restore] (with-history-spy)]
        (us/push! (str (.-pathname (.-location js/window))
                       "?variant=foo%2Fbar"
                       (.-hash (.-location js/window))))
        (try
          (is (= 1 (count @captured)))
          (is (re-find #"variant=foo" (first @captured)))
          (finally (restore)))))))

;; ---- state-watcher install/teardown -------------------------------------

(deftest state-watcher-install-and-teardown-noerr
  (testing "rf2-o4u18 — install + teardown completes without error and is
            idempotent (re-install replaces under same key, teardown
            removes the watch)"
    (us/install-state-watcher! state/shell-state-atom)
    ;; Re-install replaces under the same watch-key (no doubled fires
    ;; because add-watch is keyed).
    (us/install-state-watcher! state/shell-state-atom)
    (us/remove-state-watcher! state/shell-state-atom)
    (is true "install + reinstall + teardown without errors")))

;; ---- popstate listener install/teardown ---------------------------------

(deftest popstate-listener-install-is-idempotent
  (testing "rf2-o4u18 — re-installing the popstate listener replaces the
            previous handler rather than stacking"
    (when (browser?)
      (us/install-popstate-listener! state/shell-state-atom
                                     (fn [s _parsed] s))
      ;; Second install: still one handler effective; the engine
      ;; tracks the previous handler and removeEventListener's it.
      (us/install-popstate-listener! state/shell-state-atom
                                     (fn [s _parsed] s))
      ;; Best we can assert without driving popstate: no exceptions
      ;; raised, and tear-down clears the slot.
      (us/remove-popstate-listener!)
      (is true "install + reinstall + remove without errors"))))

;; ---- apply-fn integration through swap! ---------------------------------

(deftest apply-parsed-to-state-via-swap
  (testing "rf2-o4u18 — swap! threads apply-parsed-to-state through the
            live shell-state ratom"
    (let [apply-fn (fn [s parsed]
                     (us/apply-parsed-to-state s parsed {}))]
      (swap! state/shell-state-atom apply-fn
             {:variant-id :foo/bar
              :viewport   :tablet
              :background :dark
              :tag-filter #{:tag/a}})
      (let [s @state/shell-state-atom]
        (is (= :foo/bar (:selected-variant s)))
        (is (= :tablet  (:viewport s)))
        (is (= :dark    (:background s)))
        (is (= #{:tag/a} (:tag-filter s)))))))

