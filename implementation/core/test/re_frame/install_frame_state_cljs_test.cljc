(ns re-frame.install-frame-state-cljs-test
  "The framework-standard `:rf/install-frame-state` event (Spec 002
  §Installing a persisted frame-state): a present `:rf.db/app` replaces app-db,
  a present `:rf.db/runtime` replaces per top-level subtree, an absent partition
  is untouched. A malformed payload or a resource-runtime subtree makes the
  handler throw, so the router reports `:rf.error/handler-exception` and
  commits nothing.

  The one dev diagnostic this event must NOT fire rides the dev trace bus, so
  it sits inside `(when rf.interop/debug-enabled? ...)`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj [re-frame.test-support :as rf.test-support
                     :refer [with-emit-recorder! with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support]))
  #?(:cljs (:require-macros [re-frame.test-support
                             :refer [with-emit-recorder! with-trace-recorder!]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A registered frame under an id no other test in this process has used."
  []
  (let [fid (keyword "rf.install" (str "frame" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid})
    fid))

(defn- seed!
  "Put `frame-id` into a known frame-state: `app` in app-db (through the
  canonical `:rf/set-db` event) and `runtime` in runtime-db (through the
  internal partition writer — the seed stands for state a live session built)."
  [frame-id app runtime]
  (rf/dispatch-sync [:rf/set-db app] {:frame frame-id})
  (rf.frame/replace-runtime-db! frame-id runtime)
  (rf/frame-state-value frame-id))

(def ^:private live-ssr
  {:hydration {:server-hash "aaaa1111" :version 1}})

(def ^:private live-routing
  {:current {:id :route/home :params {} :query {} :nav-token 7}})

(def ^:private saved-ssr
  {:hydration {:server-hash "bbbb2222" :version 2}})

(deftest install-frame-state-is-a-core-standard-with-framework-authority
  ;; framework-write authority keeps the runtime-db it returns in bounds; the
  ;; standard registry unions it into every resolved image generation
  (rf.events/register-install-frame-state-standard!)
  (is (true? (:rf/framework-authority? (rf.registrar/handler-meta :event :rf/install-frame-state))))
  (is (= {:standard true :rf/framework-authority? true}
         (-> (->> (rf.image-assembly/standard-descriptors)
                  (filter #(and (= :event (:kind %))
                                (= :rf/install-frame-state (:id %))))
                  first)
             (select-keys [:standard :rf/framework-authority?])))))

(deftest a-present-app-partition-replaces-app-db-and-runtime-db-is-untouched
  (let [fid    (fresh-frame!)
        before (seed! fid {:old :value :other 1}
                      {:rf.runtime/ssr live-ssr})]
    (rf/dispatch-sync [:rf/install-frame-state {:rf.db/app {:restored :yes}}]
                      {:frame fid})
    (is (= (assoc before :rf.db/app {:restored :yes}) (rf/frame-state-value fid))
        "app-db replaced, not merged; runtime-db untouched")))

(deftest a-present-runtime-partition-replaces-per-subtree
  (let [fid    (fresh-frame!)
        before (seed! fid {:app :kept}
                      {:rf.runtime/ssr     live-ssr
                       :rf.runtime/routing live-routing})]
    (rf/dispatch-sync [:rf/install-frame-state
                       {:rf.db/runtime {:rf.runtime/ssr saved-ssr}}]
                      {:frame fid})
    (is (= (assoc-in before [:rf.db/runtime :rf.runtime/ssr] saved-ssr)
           (rf/frame-state-value fid))
        "the supplied subtree replaced whole; :routing and app-db untouched")))

(deftest an-empty-payload-changes-nothing
  (let [fid    (fresh-frame!)
        before (seed! fid {:app :kept} {:rf.runtime/ssr live-ssr})]
    (with-emit-recorder! [errs]
      (rf/dispatch-sync [:rf/install-frame-state {}] {:frame fid})
      (is (empty? @errs) "legal, not an error"))
    (is (= before (rf/frame-state-value fid)))))

(defn- refused-install
  "Dispatch `[:rf/install-frame-state payload]` at a seeded frame and return
  `[error-ids before after]`."
  [payload]
  (let [fid    (fresh-frame!)
        before (seed! fid {:app :kept} {:rf.runtime/ssr live-ssr})]
    (with-emit-recorder! [errs]
      (rf/dispatch-sync [:rf/install-frame-state payload] {:frame fid})
      [(mapv :error @errs) before (rf/frame-state-value fid)])))

(deftest a-malformed-payload-is-refused-and-the-frame-state-is-untouched
  (doseq [[label payload] [["non-map payload"          [:rf.db/app {}]]
                           ["nil app partition"        {:rf.db/app nil}]
                           ["non-map runtime partition" {:rf.db/runtime "x"}]
                           ["valid app, bad runtime"   {:rf.db/app     {:ok 1}
                                                        :rf.db/runtime [:no]}]]]
    (let [[errors before after] (refused-install payload)]
      (is (= [:rf.error/handler-exception] errors) label)
      (is (= before after) (str label ": no partial commit")))))

(deftest a-resource-runtime-subtree-is-refused
  ;; a persisted resource cache is refused rather than installed raw; the frame
  ;; refetches instead
  (let [[errors before after]
        (refused-install {:rf.db/app     {:would :replace}
                          :rf.db/runtime {:rf.runtime/ssr       saved-ssr
                                          :rf.runtime/resources {}}})]
    (is (= [:rf.error/handler-exception] errors))
    (is (= before after) "the valid parts did not land alone")))

(deftest no-runtime-effect-ownership-diagnostic
  (when rf.interop/debug-enabled?
    (let [fid      (fresh-frame!)
          warning? #(= :rf.warning/app-handler-runtime-effect (:operation %))]
      (rf.events/reg-event :install-test/app-writes-runtime
        (fn [{rt :rf.db/runtime} _]
          {:rf.db/runtime (assoc rt :rf.runtime/ssr saved-ssr)}))
      (with-trace-recorder! [traces {:pred warning?}]
        (rf/dispatch-sync [:install-test/app-writes-runtime] {:frame fid})
        (is (= 1 (count @traces)) "control: an unstamped app handler fires it"))
      (with-trace-recorder! [traces {:pred warning?}]
        (rf/dispatch-sync [:rf/install-frame-state
                           {:rf.db/runtime {:rf.runtime/ssr live-ssr}}]
                          {:frame fid})
        (is (empty? @traces) "the framework-authority install does not")))))
