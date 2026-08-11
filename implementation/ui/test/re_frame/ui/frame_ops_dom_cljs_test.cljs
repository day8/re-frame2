(ns re-frame.ui.frame-ops-dom-cljs-test
  "rf2-vxgfnd.184 — the compiled `(frame)` operation bundle proven MOUNTED,
  end to end on a real react-dom root through a real ViewCell:

    - a view under `frame-provider` observes the bundle locked to the
      provider's frame (the ambient React-context tier — the same
      committed-frame resolution as `sub`);
    - dispatching THROUGH the bundle drives the frame's event loop and the
      mounted `sub` repaints — the round trip is live;
    - across sub-driven re-renders within one live frame incarnation the
      bundle identity is STABLE (the re-render observes the IDENTICAL
      bundle object — no per-render construction).

  Browser-only bodies — `-dom-cljs-test$` opts this file into
  `:browser-test`; under `:node-test` every DOM body gates on `(browser?)`
  and exits early. The headless/host-shared semantics (fencing, canonical
  diagnostics, caching) ride frame-ops-cljs-test; the JVM structural
  placement rides frame-ops-view-jvm-test."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview sub]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

;; Module-level capture seam: a top-level view cannot close over a test's
;; atom, and the mounted lifecycle is async (a dynamic binding would unwind
;; before React commits), so the probe hands every render's bundle out here.
(defonce ^:private captured (atom []))
(defn- capture! [b] (swap! captured conj b) nil)

(defview ops-view
  "The Guide 05 spelling, mounted: bundle + ambient sub in one body."
  []
  (let [{:keys [frame] :as bundle} (ui/frame)
        _ (capture! bundle)
        n (sub [:scope/n])]
    [:output {:data-role "ops-n"} (str "n=" n " f=" frame)]))

(defn- reg! []
  (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
  (rf/reg-sub :scope/n (fn [db _] (:n db))))

(deftest mounted-bundle-binds-to-the-committed-frame-and-drives-it
  (if-not (browser?)
    (is true "DOM body — runs under :browser-test")
    (do
      (reset! captured [])
      (frames/reset-frame-ops-cache!)
      (reg!)
      (let [f        (rf/make-frame {:initial-events [[:rf/set-db {:n 42}]]})
            frame-id (frame/frame-target->id f)
            text     #(.-textContent (.querySelector %"[data-role='ops-n']"))]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [ops-view]]]
                (let [bundle (first @captured)]
                  (testing "the committed frame is the provider's frame"
                    (is (= #{:frame :dispatch :dispatch-sync :subscribe}
                           (set (keys bundle)))
                        "the standard capture-frame bundle shape")
                    (is (= frame-id (:frame bundle))
                        "the ambient React-context tier resolved the bundle —
                         the same chain the compiled sub uses")
                    (is (= (str "n=42 f=" frame-id) (text root))
                        "the mounted body rendered bundle + sub together"))
                  (-> (uit/flush! #((:dispatch-sync bundle) [:test/set-db {:n 43}]))
                      (.then
                       (fn []
                         (testing "dispatch THROUGH the bundle repaints the mounted sub"
                           (is (= (str "n=43 f=" frame-id) (text root))
                               "the bundle's op drove the locked frame's event
                                loop and the ViewCell committed the movement"))
                         (testing "bundle identity is stable across re-renders"
                           (is (>= (count @captured) 2)
                               "the sub movement re-rendered the view")
                           (is (every? #(identical? (first @captured) %)
                                       @captured)
                               "every render of one live incarnation observed
                                the IDENTICAL bundle — rf= memo-friendly, no
                                per-render construction")))))))
              ;; The rejection handler sits UPSTREAM of the single trailing
              ;; `done` (rf2-qpns): `done` runs the whole remainder of the run
              ;; synchronously, so a `.catch` after it claims a foreign throw
              ;; as this row's and fires `done` a second time.
              (.catch (fn [e]
                        (is false (str "mounted (frame) fixture rejected: " e))
                        nil))
              (.then (fn [] (done)))))))))
