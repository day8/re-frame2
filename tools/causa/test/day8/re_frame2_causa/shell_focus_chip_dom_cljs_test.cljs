(ns day8.re-frame2-causa.shell-focus-chip-dom-cljs-test
  "Live-DOM test for the focus-chip 'reveal pivot row' gesture
  (rf2-w738i). The chip body's click delegates to
  `shell/scroll-row-into-view-by-id!`, which locates the pivot row by
  the `rf-causa-event-row-<id>` data-testid the L2 renderer stamps and
  calls `scrollIntoView` on it.

  This needs a real DOM (`document.querySelector` against a mounted
  subtree + an element carrying `scrollIntoView`), so the filename ends
  in `_dom_cljs_test.cljs` to run under the `:browser-test` build. Under
  `:node-test` the `(when (exists? js/document) …)` guards keep it green.

  The hiccup-level wiring of the chip (role=button, handlers present,
  stopPropagation on clear) is covered node-side in `shell-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.shell :as shell]))

(defn- mk-row!
  "Append a stub L2 row with the canonical data-testid for `id` under
  `host`. Stamps a `scrollIntoView` that records calls into `calls`.
  Returns the element."
  [host id calls]
  (let [el (.createElement js/document "div")]
    (.setAttribute el "data-testid" (str "rf-causa-event-row-" (str id)))
    (set! (.-scrollIntoView el) (fn [_opts] (swap! calls inc)))
    (.appendChild host el)
    el))

(deftest scroll-row-into-view-by-id-finds-and-scrolls-pivot
  (testing "rf2-w738i — `scroll-row-into-view-by-id!` locates the row by
            its data-testid and calls scrollIntoView on it"
    (when (exists? js/document)
      (let [host  (.createElement js/document "div")
            calls (atom 0)]
        (.appendChild (.-body js/document) host)
        (mk-row! host 1 calls)
        (let [pivot (mk-row! host 7 calls)]
          (mk-row! host 9 calls)
          (try
            (let [found (#'shell/scroll-row-into-view-by-id! 7)]
              (is (= pivot found) "returns the located pivot row element")
              (is (= 1 @calls) "scrollIntoView fired exactly once on the pivot"))
            (finally
              (.removeChild (.-body js/document) host))))))))

(deftest scroll-row-into-view-by-id-noop-when-row-absent
  (testing "rf2-w738i — when the pivot row isn't mounted (scrolled out of
            the virtual window), the helper is a clean no-op (nil, no
            throw)"
    (when (exists? js/document)
      (let [host  (.createElement js/document "div")
            calls (atom 0)]
        (.appendChild (.-body js/document) host)
        (mk-row! host 1 calls)
        (try
          (is (nil? (#'shell/scroll-row-into-view-by-id! 999))
              "absent row → nil")
          (is (zero? @calls) "no scroll fired")
          (finally
            (.removeChild (.-body js/document) host)))))))

(deftest scroll-row-into-view-by-id-noop-on-nil-id
  (testing "rf2-w738i — a nil pivot-id (degenerate focus-set) is a no-op"
    (when (exists? js/document)
      (is (nil? (#'shell/scroll-row-into-view-by-id! nil))))))
