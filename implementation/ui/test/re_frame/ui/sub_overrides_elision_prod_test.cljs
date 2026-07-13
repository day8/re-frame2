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
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(defview prod-sub-value []
  [:output {:data-role "prod-sub-value"}
   (str (ui/sub [::prod-value]))])

(defn prod-provider-tree []
  (sub-overrides/provider-element
   {[::prod-value] "forbidden-override"}
   (React/createElement prod-sub-value nil)))

(defview prod-mounted-tree []
  (ui/raw (React/createElement prod-provider-tree nil)))

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
          (let [cells (reactive/current-live-cells)]
            (is (= 1 (count cells)))
            (is (= #{sub-key}
                   (reactive/committed-target-keys (first cells))))
            (is (some? (get @(:sub-cache (frame/frame frame-id))
                            [::prod-value])))))
        (catch :default e
          (is false (str "advanced mounted override-elision control rejected: " e)))
        (finally
          (when @root
            (ReactDOM/flushSync #(ui/unmount! @root)))
          (.remove container)
          (rf/destroy-frame! f)
          (done))))))
