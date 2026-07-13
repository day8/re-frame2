(ns re-frame.ui.sub-overrides-elision-prod-test
  "Advanced-production control for the mounted compiled ViewCell override
  carriage (rf2-vxgfnd.12.2).

  This namespace runs ONLY in `:browser-test-prod-elision` (`:advanced`,
  goog.DEBUG=false).  The ordinary elision probe calls `sub-read` directly and
  therefore cannot reach the ViewCell's React hook/provider path.  This test
  mounts a generated sub-bearing view through that exact path and proves:

  - the shared override Context was never constructed;
  - the internal Provider is transparent;
  - compiled `ui/sub` takes the ordinary owned-node path, never a static
    override lease.

  The normal mounted browser gate supplies the DEBUG=true positive control: the
  same provider helper produces nested static leases there."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as ReactDOM]
            [re-frame.adapter.sub-override-context :as override-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.sub-overrides :as sub-overrides]
            [re-frame.ui.test :as uit])
  (:require-macros [re-frame.ui.hidden-sub-macros
                    :refer [hidden-public-sub]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(defview prod-sub-value []
  [:output {:data-role "prod-sub-value"}
   (str (ui/sub [::prod-value]))])

(defn prod-provider-tree []
  (sub-overrides/provider-element
   {[::prod-value] "forbidden-override"}
   (React/createElement React/Fragment nil
                        (React/createElement prod-sub-value nil)
                        (React/createElement prod-sub-value nil))))

(defview prod-mounted-tree []
  (ui/raw (React/createElement prod-provider-tree nil)))

(defn hidden-ordinary-helper []
  (ui/sub [:hidden/ordinary-helper]))

(defn hidden-macro-helper []
  (hidden-public-sub))

(deftest advanced-production-hidden-reads-fail-instead-of-going-nonreactive
  (doseq [helper [hidden-ordinary-helper hidden-macro-helper]]
    (is (= :rf.error/ui-tree-malformed
           (try (helper) nil
                (catch :default e (:rf.error/id (ex-data e)))))
        "advanced production preserves fail-loud public authoring semantics")))

(deftest mounted-viewcell-provider-carriage-elides-in-production
  (rf/reg-sub ::prod-value (fn [db _] (:prod-value db)))
  (let [f        (uit/frame {:app-db {:prod-value "ordinary"}})
        frame-id (frame/frame-target->id f)
        sub-key  [:sub frame-id [::prod-value]]
        container (js/document.createElement "div")
        root      (volatile! nil)]
    (async done
      (is (nil? override-context/override-context)
          "goog.DEBUG=false performed no React.createContext construction")
      (.appendChild js/document.body container)
      (try
        ;; Production React intentionally exports no `act`; flushSync is the
        ;; host-supported way to make this advanced-build mount observable.
        (ReactDOM/flushSync
         #(vreset! root
                   (ui/mount [ui/frame-provider {:frame f}
                              [prod-mounted-tree]]
                             container
                             {:root-id ::prod-mounted-elision})))
        (testing "provider is transparent and generated ui/sub stays ordinary"
          (is (= "ordinary"
                 (.-textContent
                  (.querySelector container "[data-role='prod-sub-value']"))))
          (let [cells   (vec (reactive/current-live-cells))
                records (mapv #(first (vals (reactive/committed-sites %))) cells)
                sids    (mapv #(first (keys (reactive/committed-sites %))) cells)]
            (is (= 2 (count cells)))
            (is (= 2 (.-length
                      (.querySelectorAll container
                                         "[data-role='prod-sub-value']"))))
            (is (every? #(= #{sub-key}
                            (reactive/committed-target-keys %))
                        cells))
            (is (= 1 (count (distinct sids)))
                "both view instances execute the same compiler lexical site")
            (is (.startsWith (first sids) "sid1-")
                "the advanced hot call retains the compact versioned sid")
            (is (identical? (:query (first records))
                            (:query (second records)))
                "the literal query is one module constant, not rebuilt per render")
            (is (= 2 (:ref-count
                      (get @(:sub-cache (frame/frame frame-id))
                           [::prod-value])))
                "each lexical view instance remains an independent owner")))
        (catch :default e
          (is false (str "advanced mounted override-elision control rejected: " e)))
        (finally
          (when @root
            (ReactDOM/flushSync #(ui/unmount! @root)))
          (.remove container)
          (rf/destroy-frame! f)
          (done))))))
