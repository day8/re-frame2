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
            [re-frame.story.share         :as rf.story.share]
            [re-frame.story.ui.state      :as rf.story.ui.state]
            [re-frame.story.ui.url-state  :as rf.story.ui.url-state]))

;; ---- fixtures ------------------------------------------------------------

(defn- browser? []
  (and (exists? js/window) (exists? js/URLSearchParams)))

(defn reset-all! []
  (rf.story.ui.state/reset-shell-state!))

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
          url   (rf.story.ui.url-state/url-from-state shell {:pathname "/p/" :hash "#/stories"})]
      (is (re-find #"variant="    url))
      (is (re-find #"mode-tab="   url))
      (is (re-find #"modes="      url))
      (is (re-find #"viewport="   url))
      (is (re-find #"background=" url))
      (is (re-find #"tag-filter=" url))
      (is (re-find #"substrate="  url))
      (is (re-find #"#/stories$"  url)
          "hash route survives at the tail"))))

;; ---- rf2-gee8n: unowned params survive a state-driven address-bar write --
;;
;; The JVM half of this pin asserts the composed STRING. Only the real
;; `URLSearchParams` can answer what the shell will actually READ back off
;; that string: `params->getter` and `embed-flag-from-current-url` both go
;; through `.get`, whose first-value semantics are the reason rf2-b7je1
;; had to clear rather than append. So read the composed URL back through
;; the same API the hydrator uses.

(deftest url-from-state-preserves-unowned-params-through-urlsearchparams
  (testing "rf2-gee8n — url-from-state used to rebuild the query from shell
            state alone and drop location.search wholesale, erasing every
            param Story does not own on the first state change after mount.
            Read back through the REAL URLSearchParams: the unowned params
            are still there with their values, the embed flag still reads
            truthy for embed-flag-from-current-url, every stale Story key is
            absent (not merely later in the string — .get would return it),
            and parse-params restores the requested cell and nothing else."
    (let [stale  {"variant"    "story.old%2Fa"
                  "workspace"  "story.old%2Fws"
                  "mode-tab"   "docs"
                  "modes"      "Mode.app%2Fstale"
                  "viewport"   "tablet"
                  "background" "dark"
                  "tag-filter" "stale"
                  "overrides"  "%7B%3Afoo%201%7D"
                  "substrate"  "uix"}
          search (str "?"
                      (str/join "&" (map #(str (name %) "=" (get stale (name %)))
                                         rf.story.share/story-query-keys))
                      "&from=index&embed=1")
          url    (rf.story.ui.url-state/url-from-state
                   {:selected-variant :story.new/b}
                   {:pathname "/counter-with-stories/"
                    :search   search
                    :hash     "#/stories"})
          usp    (js/URLSearchParams.
                   (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))]
      (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "index" (.get usp "from"))
          "the referrer param survives with its value")
      (is (= "1" (.get usp "embed"))
          "embed=1 survives — chrome state the shell reads at every mount")
      (is (= "story.new/b" (.get usp "variant"))
          "URLSearchParams.get returns the variant this state asked for")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value")
      (doseq [k (map name rf.story.share/story-query-keys)
              :when (not= k "variant")]
        (is (zero? (count (.getAll usp k)))
            (str "URLSearchParams sees no stale " k "= at all")))
      (let [parsed (rf.story.share/parse-params
                     (into {} (map (fn [k] [k (.get usp k)]))
                           (map name rf.story.share/story-query-keys)))]
        (is (= :story.new/b (:variant-id parsed))
            "the hydrator's own read path recovers the requested variant")
        (doseq [slot [:workspace-id :mode-tab :active-modes :viewport
                      :background :tag-filter :cell-overrides :substrate]]
          (is (nil? (get parsed slot))
              (str "parse-params restores no stale " slot))))
      (is (str/ends-with? url "#/stories")
          "the hash route survives, after the query"))))

;; ---- rf2-b7je1: the LIVE address bar owns escaped key spellings ---------
;;
;; Unifying the two writers on `rf.story.share/apply-story-params` carried the
;; share builder's last ownership hole onto the address bar: ownership was
;; matched on raw key text, while the `URLSearchParams` this shell reads
;; with compares DECODED names. A `location.search` spelling a Story key
;; with escapes therefore survived a state-driven push, the generated
;; value was appended behind it, and the next reload's `.get` —
;; first-value — restored the stale cell. Asserted against the real
;; browser API, on the address-bar writer specifically: the repair is in
;; the shared helper, so this and the share-builder pin move together.

(deftest url-from-state-clears-escaped-story-keys-through-urlsearchparams
  (testing "rf2-b7je1 audit — `%76ariant=` IS `variant=` to the browser, so
            a state-driven push must clear it rather than append behind it;
            read back through the API the next mount will actually use"
    (let [url (rf.story.ui.url-state/url-from-state
                {:selected-variant :story.new/b}
                {:pathname "/p/"
                 :search   "?%76ariant=story.old%2Fa&embed=1"
                 :hash     "#/stories"})
          usp (js/URLSearchParams.
                (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))]
      (is (= "story.new/b" (.get usp "variant"))
          "the reload reads the cell this state asked for, not the stale one")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value — the escaped one is gone, not outranked")
      (is (= "1" (.get usp "embed"))
          "embed=1 survives — chrome state Story does not own")
      (is (= "/p/?embed=1&variant=story.new%2Fb#/stories" url)
          "and the composed string is exactly that, in order"))))

(deftest url-from-state-clears-every-escaped-story-key-cljs
  (testing "rf2-b7je1 audit — the whole vocabulary spelled with escapes.
            Derived from `rf.story.share/story-query-keys`, so a key added to the
            vocabulary is covered without editing this test."
    (let [escape #(str "%" (.toUpperCase (.toString (.charCodeAt % 0) 16))
                       (subs % 1))
          stale  {"variant"    "story.old%2Fa"
                  "workspace"  "story.old%2Fws"
                  "mode-tab"   "docs"
                  "modes"      "Mode.app%2Fstale"
                  "viewport"   "tablet"
                  "background" "dark"
                  "tag-filter" "stale"
                  "overrides"  "%7B%3Afoo%201%7D"
                  "substrate"  "uix"}
          search (str "?"
                      (str/join "&" (map #(str (escape (name %))
                                               "="
                                               (get stale (name %)))
                                         rf.story.share/story-query-keys))
                      "&from=index&embed=1")
          url    (rf.story.ui.url-state/url-from-state
                   {:selected-variant :story.new/b}
                   {:pathname "/p/" :search search :hash "#/stories"})
          usp    (js/URLSearchParams.
                   (second (str/split (first (str/split url #"#" 2)) #"\?" 2)))]
      (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "story.new/b" (.get usp "variant"))
          "URLSearchParams.get returns the variant this state asked for")
      (is (= 1 (count (.getAll usp "variant")))
          "exactly one variant value")
      (doseq [k (map name rf.story.share/story-query-keys)
              :when (not= k "variant")]
        (is (zero? (count (.getAll usp k)))
            (str "URLSearchParams sees no stale " k "= at all")))
      (is (= "index" (.get usp "from")) "unrelated from= survives")
      (is (= "1" (.get usp "embed")) "unrelated embed= survives")
      (is (= "/p/?from=index&embed=1&variant=story.new%2Fb#/stories" url)
          "every escaped Story key is cleared; both unowned params survive"))))

(deftest url-from-state-consumes-a-browser-shaped-location
  (testing "rf2-gee8n — `current-location-shape` snapshots the browser's own
            {pathname, search, hash} triple off `window.location`. Drive the
            composer from a real `js/URL`'s three properties — the same
            accessors, with the same `?`/`#` prefix conventions — so the
            merge is exercised against browser-produced values rather than
            hand-written strings."
    (let [loc (js/URL. (str "https://example.test/counter-with-stories/"
                            "?from=index&embed=1&variant=story.old%2Fa"
                            "#/stories"))
          url (rf.story.ui.url-state/url-from-state
                {:selected-variant :story.new/b}
                {:pathname (.-pathname loc)
                 :search   (.-search loc)
                 :hash     (.-hash loc)})]
      (is (= "/counter-with-stories/?from=index&embed=1&variant=story.new%2Fb#/stories"
             url)))))

;; ---- params-from-state via the public share encoder ---------------------

(deftest params-from-state-feeds-share-build-params
  (testing "rf2-o4u18 — the projection contract: params-from-state +
            rf.story.share/build-params produce a URL params vector that
            rf.story.share/parse-params round-trips back to the projection"
    (let [shell  {:selected-variant   :foo/bar
                  :active-mode-tab    {:foo/bar :test}
                  :active-modes       [:m/dark]
                  :viewport           {:width 800 :height 600}
                  :background         "#abc123"
                  :tag-filter         #{:tag/x}
                  :cell-overrides     {:foo/bar {:label "Hi"}}
                  :substrate          :uix}
          proj   (rf.story.ui.url-state/params-from-state shell)
          ps     (rf.story.share/build-params proj)
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
          out    (rf.story.share/parse-params getter)]
      (is (= :foo/bar (:variant-id out)))
      (is (= :test    (:mode-tab out)))
      (is (= [:m/dark] (:active-modes out)))
      (is (= {:width 800 :height 600} (:viewport out)))
      (is (= "#abc123" (:background out)))
      (is (= #{:tag/x} (:tag-filter out)))
      (is (= {:label "Hi"} (:cell-overrides out)))
      (is (= :uix (:substrate out))))))

;; ---- rf2-j0hwf: full override round-trip via URLSearchParams ------------

(deftest override-round-trip-through-urlsearchparams
  (testing "rf2-j0hwf — the focused-variant override round-trip as a single
            invariant: shell-state → params-from-state → build-params →
            URLSearchParams encode/decode → parse-params →
            apply-parsed-to-state restores [:cell-overrides variant-id]
            equal to the source slice — INCLUDING a string value carrying
            the list separator (comma), which the old comma-split codec
            dropped."
    (let [variant  :foo/bar
          slice    {:label "Save, continue" :count 3 :items [1 2 3]}
          shell    {:selected-variant variant
                    :cell-overrides   {variant slice}}
          ;; encode: project → build params → real URLSearchParams string
          proj     (rf.story.ui.url-state/params-from-state shell)
          ps       (rf.story.share/build-params proj)
          qs       (str/join "&" ps)
          usp      (js/URLSearchParams. qs)
          getter   {"variant"   (.get usp "variant")
                    "overrides" (.get usp "overrides")}
          ;; decode: parse-params → apply back into a fresh shell state
          parsed   (rf.story.share/parse-params getter)
          out      (rf.story.ui.url-state/apply-parsed-to-state {} parsed {})]
      (is (= variant (:selected-variant out)))
      (is (= slice (get-in out [:cell-overrides variant]))
          "decoded overrides equal the encoded overrides (round-trip)")
      ;; explicit: the comma-bearing value is intact, not shredded
      (is (= "Save, continue"
             (get-in out [:cell-overrides variant :label]))))))

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
        (rf.story.ui.url-state/push! cur)
        (try
          (is (= 0 (count @captured))
              "no pushState calls when URL matches")
          (finally (restore))))))

  (deftest push!-fires-when-url-differs
    (testing "rf2-o4u18 — push! pushes a different URL"
      (let [[captured restore] (with-history-spy)]
        (rf.story.ui.url-state/push! (str (.-pathname (.-location js/window))
                       "?variant=foo%2Fbar"
                       (.-hash (.-location js/window))))
        (try
          (is (= 1 (count @captured)))
          (is (re-find #"variant=foo" (first @captured)))
          (finally (restore)))))))

;; ---- state-watcher install/teardown -------------------------------------
;;
;; `install-state-watcher!` registers a single keyed `add-watch` under a
;; fixed watch-key, so a re-install REPLACES rather than stacks — the
;; "no doubled fires" invariant the old `(is true)` body only *named*. We
;; give it teeth by counting the ratom's registered watches directly
;; (`.-watches`, the same introspection reagent's own ratom suite uses):
;; a re-install must NOT grow the watch count, and a stacking regression
;; (a fresh key per install) would. Runs headlessly — `add-watch` needs
;; no window.

(deftest state-watcher-reinstall-replaces-under-same-key
  (testing "rf2-o4u18 / rf2-x76af2.21 — re-installing the state-watcher
            replaces under the same watch-key: the ratom carries exactly
            ONE url-state watch after a double-install (no doubled fires),
            and teardown removes it. A watcher that stacked (fresh key per
            install) would leave the count one higher and fail here."
    (let [a  rf.story.ui.state/shell-state-atom
          n0 (count (.-watches a))]
      (rf.story.ui.url-state/install-state-watcher! a)
      (let [n1 (count (.-watches a))]
        (rf.story.ui.url-state/install-state-watcher! a)          ; re-install under same key
        (let [n2 (count (.-watches a))]
          (try
            (is (= (inc n0) n1)
                "first install registers exactly one keyed watch")
            (is (= n1 n2)
                "re-install does NOT grow the watch count — replaces under
                 the same key (no stacked / doubled-fire watchers)")
            (finally (rf.story.ui.url-state/remove-state-watcher! a)))
          (is (= n0 (count (.-watches a)))
              "teardown removes the watch (back to baseline)"))))))

;; ---- popstate listener install/teardown ---------------------------------
;;
;; The node runner has no `window`, so the old test wrapped its whole body
;; in `(when (browser?) ...)` and executed ZERO assertions under
;; `npm run test:cljs` — a vacuous pass. Here we install a minimal `window`
;; stub on `js/globalThis` (the same pattern the routing suites use) whose
;; add/removeEventListener maintain a countable per-type listener registry.
;; That makes the "re-install replaces rather than stacks" invariant
;; node-runnable AND gives it teeth: after a double-install exactly ONE
;; popstate listener is registered and a single popstate event fires the
;; handler exactly once. A stacking regression leaves 2 listeners and
;; double-fires — so this fails under the default node gate rather than
;; passing vacuously.

(defn- install-window-stub!
  "Install a minimal `window` on `js/globalThis` with a countable
  event-listener registry and an empty-search location. Returns the
  registry atom `{event-type → [listener ...]}`."
  []
  (let [registry (atom {})
        location #js {:pathname "/" :search "" :hash ""}
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
    registry))

(defn- uninstall-window-stub! []
  (js-delete js/globalThis "window"))

(defn- popstate-listener-count [registry]
  (count (get @registry "popstate" [])))

(deftest popstate-listener-reinstall-replaces-rather-than-stacks
  (testing "rf2-o4u18 / rf2-x76af2.21 — re-installing the popstate listener
            replaces the previous handler rather than stacking: after a
            double-install exactly ONE popstate listener is registered, and
            a single popstate event fires the apply-fn exactly once. Runs
            under the default node gate via a window stub (no browser? gate),
            so a stacking regression fails here — not a vacuous pass."
    (let [registry (install-window-stub!)
          fires    (atom 0)
          apply-fn (fn [s _parsed] (swap! fires inc) s)]
      (try
        ;; Clear any process-wide handler left by a prior test/run so the
        ;; registry count reflects only this test's installs.
        (rf.story.ui.url-state/remove-popstate-listener!)
        (reset! fires 0)
        (rf.story.ui.url-state/install-popstate-listener! rf.story.ui.state/shell-state-atom apply-fn)
        (rf.story.ui.url-state/install-popstate-listener! rf.story.ui.state/shell-state-atom apply-fn) ; re-install
        (is (= 1 (popstate-listener-count registry))
            "exactly one popstate listener registered after double-install
             (replaced, not stacked)")
        ;; Drive a single popstate: the handler must fire exactly once.
        (.dispatchEvent js/window #js {:type "popstate"})
        (is (= 1 @fires)
            "single popstate fires the handler exactly once (no doubled fires)")
        (rf.story.ui.url-state/remove-popstate-listener!)
        (is (= 0 (popstate-listener-count registry))
            "remove-popstate-listener! removes the listener")
        (finally
          (rf.story.ui.url-state/remove-popstate-listener!)
          (uninstall-window-stub!))))))

;; ---- apply-fn integration through swap! ---------------------------------

(deftest apply-parsed-to-state-via-swap
  (testing "rf2-o4u18 — swap! threads apply-parsed-to-state through the
            live shell-state ratom"
    (let [apply-fn (fn [s parsed]
                     (rf.story.ui.url-state/apply-parsed-to-state s parsed {}))]
      (swap! rf.story.ui.state/shell-state-atom apply-fn
             {:variant-id :foo/bar
              :viewport   :tablet
              :background :dark
              :tag-filter #{:tag/a}})
      (let [s @rf.story.ui.state/shell-state-atom]
        (is (= :foo/bar (:selected-variant s)))
        (is (= :tablet  (:viewport s)))
        (is (= :dark    (:background s)))
        (is (= #{:tag/a} (:tag-filter s)))))))

;; ---- rf2-fkmnh: populated → omitted/default transition ------------------

(deftest parse-current-url-or-empty-returns-empty-shape-on-blank-search
  (testing "rf2-fkmnh — when the window is present but the search is empty
            `parse-current-url-or-empty` returns the all-nil parsed shape
            (not nil), so a no-query popstate drives the URL-owned slots to
            their defaults instead of no-op'ing. (The harness URL has no
            Story params, so this exercises the blank-search branch.)"
    (when (browser?)
      (let [parsed (rf.story.ui.url-state/parse-current-url-or-empty)]
        (is (map? parsed) "returns a parsed map, never nil, when window present")
        (is (nil? (:variant-id parsed)))
        (is (nil? (:active-modes parsed)))
        (is (nil? (:viewport parsed)))
        (is (nil? (:background parsed)))
        (is (nil? (:tag-filter parsed)))))))

(deftest popstate-to-empty-url-clears-prior-state-via-swap
  (testing "rf2-fkmnh — the back/forward-to-bare-URL scenario through the
            same swap path the popstate handler takes: a populated shell,
            then applying the all-nil parsed shape (what
            `parse-current-url-or-empty` yields for an empty search) clears
            every URL-owned slot. Drives the live shell-state ratom."
    (let [apply-fn (fn [s parsed] (rf.story.ui.url-state/apply-parsed-to-state s parsed {}))]
      ;; 1. populated: a deep-link with framing + filter + modes.
      (swap! rf.story.ui.state/shell-state-atom apply-fn
             {:variant-id   :foo/bar
              :active-modes [:m/dark]
              :viewport     :tablet
              :background   :dark
              :tag-filter   #{:tag/a}})
      (is (= :foo/bar (:selected-variant @rf.story.ui.state/shell-state-atom)))
      ;; 2. popstate to a no-query URL ⇒ all-nil parsed shape.
      (swap! rf.story.ui.state/shell-state-atom apply-fn (rf.story.share/parse-params {}))
      (let [s @rf.story.ui.state/shell-state-atom]
        (is (nil? (:selected-variant s)) "selection cleared on bare-URL pop")
        (is (= [] (:active-modes s))      "modes cleared")
        (is (nil? (:viewport s))          "viewport cleared")
        (is (nil? (:background s))        "background cleared")
        (is (= #{} (:tag-filter s))       "tag-filter cleared")))))

(deftest popstate-to-partial-url-clears-omitted-slots-only
  (testing "rf2-fkmnh — navigating from a fully-populated URL to one that
            keeps the variant but drops modes/viewport/background/tag-filter
            clears exactly the omitted slots (the variant survives)"
    (let [apply-fn (fn [s parsed] (rf.story.ui.url-state/apply-parsed-to-state s parsed {}))]
      (swap! rf.story.ui.state/shell-state-atom apply-fn
             {:variant-id   :foo/bar
              :active-modes [:m/dark]
              :viewport     :tablet
              :background   :dark
              :tag-filter   #{:tag/a}})
      ;; second URL: variant only.
      (swap! rf.story.ui.state/shell-state-atom apply-fn {:variant-id :foo/bar})
      (let [s @rf.story.ui.state/shell-state-atom]
        (is (= :foo/bar (:selected-variant s)) "variant preserved")
        (is (= [] (:active-modes s)))
        (is (nil? (:viewport s)))
        (is (nil? (:background s)))
        (is (= #{} (:tag-filter s)))))))

