(ns re-frame.ui.resource-lease-lifecycle-dom-cljs-test
  "rf2-vxgfnd.106 — mounted React proof for the resource-ownership family:
  lease-only StrictMode/Activity/unmount, same-signature HMR retention/removal,
  and eight queued ensure writes settling before one subscribed read render."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as ReactDOMClient]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.resources]
            [re-frame.resources.test-support]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as runtime]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(def ^:private Activity React/Activity)
(defonce ^:private resource-events (atom []))
(defonce ^:private batch-evidence (atom {:ensures 0 :read-bodies 0}))
(def ^:private this-ns
  "re-frame.ui.resource-lease-lifecycle-dom-cljs-test")

(defn- install-resource-control!
  []
  (reset! resource-events [])
  (rf/reg-resource ::feed
                   {:scope :rf.scope/global
                    :params-schema [:map [:n {:optional true} :int]]}
                   (fn [_params _ctx]
                     {:request {:method :get :url "/ui-lease-proof"}}))
  ;; Keep the proof about ui/lease ownership and ordinary FIFO drains. The
  ;; Resources artefact is genuinely loaded (the feature probe is real), while
  ;; deterministic handlers avoid transport/network work.
  (rf/reg-event :rf.resource/ensure
                (fn [{:keys [db]} [_ payload]]
                  (swap! resource-events conj [:ensure payload])
                  {:db db}))
  (rf/reg-event :rf.resource/release-owner
                (fn [{:keys [db]} [_ payload]]
                  (swap! resource-events conj [:release payload])
                  {:db db})))

(defn- make-test-frame
  [id app-db]
  ;; The resource artefact owns the standard handlers in the base image. This
  ;; test's deterministic handlers are an intentional later-image override,
  ;; never an ambiguous same-image duplicate.
  (rf/make-frame
   {:id id
    :images [(rf/image {:id ::base
                        :select-ns {:include ["**"]
                                    :exclude [this-ns]}})
             (rf/image {:id ::lease-test-overrides
                        :select-ns {:include [this-ns]}})]
    :initial-events [[:rf/set-db app-db]]}))

(defn- settle!
  []
  (-> (uit/flush!)
      (.then (fn [] (uit/flush!)))))

(defn- lease-cell
  []
  (some #(when (seq (reactive/resource-reservations %)) %)
        (reactive/current-live-cells)))

(defview mounted-lease-leaf []
  (ui/lease {:resource ::feed :params {}})
  [:output {:data-role "mounted-lease-leaf"} "leased"])

(defview mounted-lease-activity []
  [Activity {:mode (if (ui/sub [::hidden?]) "hidden" "visible")}
   [mounted-lease-leaf]])

(defview strict-mounted-lease-activity []
  (ui/raw
   (React/createElement (.-StrictMode React) nil
                        (React/createElement mounted-lease-activity nil))))

(deftest lease-only-strictmode-activity-and-unmount-own-exactly-one-token
  (if-not (browser?)
    (is true ":node — browser gate runs mounted lease lifecycle")
    (do
      (install-resource-control!)
      (rf/reg-sub ::hidden? (fn [db _] (:hidden? db)))
      (rf/reg-event ::hide (fn [{:keys [db]} _]
                             {:db (assoc db :hidden? true)}))
      (rf/reg-event ::show (fn [{:keys [db]} _]
                             {:db (assoc db :hidden? false)}))
      (let [f    (make-test-frame :ui.lease/lifecycle-frame
                                  {:hidden? false})
            cell* (atom nil)
            owner* (atom nil)]
        (async done
          (->
           (uit/with-root [root [ui/frame-provider {:frame f}
                                 [strict-mounted-lease-activity]]]
             (-> (settle!)
                 (.then
                  (fn []
                    (let [cell  (lease-cell)
                          owner (-> (reactive/resource-reservations cell)
                                    vals first :owner)]
                      (reset! cell* cell)
                      (reset! owner* owner)
                      (is (some? cell))
                      (is (= :connected (reactive/lifecycle cell)))
                      (is (= #{owner} (set (keys (reactive/resource-held cell)))))
                      (is (= "leased"
                             (.-textContent
                              (uit/query root "[data-role='mounted-lease-leaf']"))))
                      (uit/flush! #(uit/dispatch! f [::hide])))))
                 (.then (fn [] (settle!)))
                 (.then
                  (fn []
                    (testing "Activity hide releases but preserves reservation"
                      (is (= :disconnected (reactive/lifecycle @cell*)))
                      (is (empty? (reactive/resource-held @cell*)))
                      (is (= @owner* (-> (reactive/resource-reservations @cell*)
                                         vals first :owner))))
                    (uit/flush! #(uit/dispatch! f [::show]))))
                 (.then (fn [] (settle!)))
                 (.then
                  (fn []
                    (testing "reveal reuses the exact owner under StrictMode"
                      (is (identical? @cell* (lease-cell)))
                      (is (= :connected (reactive/lifecycle @cell*)))
                      (is (= #{@owner*}
                             (set (keys (reactive/resource-held @cell*)))))
                      (is (= @owner* (-> (reactive/resource-reservations @cell*)
                                         vals first :owner))))))))
           (.then
            (fn []
              (testing "final root unmount releases and forgets reservation"
                (is (= :dead (reactive/lifecycle @cell*)))
                (is (empty? (reactive/resource-held @cell*)))
                (is (empty? (reactive/resource-reservations @cell*))))
              (rf/destroy-frame! f)
              (done))
            (fn [e]
              (rf/destroy-frame! f)
              (is false (str "mounted lease lifecycle rejected: " e))
              (done)))))))))

(defn- act-promise [thunk]
  (try
    (js/Promise.resolve
     (React/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- register-hmr-lease!
  [id descriptor]
  (runtime/register-view!
   id
   (fn [_props]
     (reactive/lease-site "sid1-hmr-lease" descriptor)
     (React/createElement "output" #js {:data-hmr-lease true}
                          (if descriptor "leased" "plain")))
   (fn [_ _] true)
   "HmrLease"
   {:view-id id
    :display-name "HmrLease"
    :template-fingerprint "tf1-hmr-lease"
    :hook-signature "hs1-hmr-lease"
    :sites {"sid1-hmr-lease" {:kind :lease}}}))

(deftest same-signature-hmr-retains-equal-owner-and-releases-removed-site
  (if-not (browser?)
    (is true ":node — browser gate runs mounted lease HMR")
    (do
      (install-resource-control!)
      (let [id        ::hmr-lease
            fid       :ui.lease/hmr-frame
            descriptor {:resource ::feed :params {}}
            container (js/document.createElement "div")
            root      (ReactDOMClient/createRoot container)
            shell     (register-hmr-lease! id descriptor)
            cell*     (atom nil)
            owner*    (atom nil)]
        (make-test-frame fid {})
        (.appendChild js/document.body container)
        (async done
          (-> (act-promise
               #(.render root
                         (frames/scope-element
                          fid (array (React/createElement shell nil)))))
              (.then
               (fn []
                 (let [cell  (lease-cell)
                       owner (-> (reactive/resource-reservations cell)
                                 vals first :owner)]
                   (reset! cell* cell)
                   (reset! owner* owner)
                   (act-promise #(register-hmr-lease! id (into {} descriptor))))))
              (.then
               (fn []
                 (testing "rf=-equal same-site refresh keeps the exact owner"
                   (is (identical? @cell* (lease-cell)))
                   (is (= @owner* (-> (reactive/resource-reservations @cell*)
                                      vals first :owner))))
                 (act-promise #(register-hmr-lease! id nil))))
              (.then
               (fn []
                 (testing "removing the lease in place releases and forgets it"
                   (is (= "plain"
                          (.-textContent
                           (.querySelector container "[data-hmr-lease]"))))
                   (is (empty? (reactive/resource-held @cell*)))
                   (is (empty? (reactive/resource-reservations @cell*))))))
              (.then (fn [] (act-promise #(.unmount root))))
              (.then
               (fn []
                 (.remove container)
                 (rf/destroy-frame! fid)
                 (done)))
              (.catch
               (fn [e]
                 (try (.unmount root) (catch :default _ nil))
                 (.remove container)
                 (rf/destroy-frame! fid)
                 (is false (str "mounted lease HMR rejected: " e))
                 (done)))))))))

(defview lease-write-batch []
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 0}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 1}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 2}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 3}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 4}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 5}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 6}}))
  (ui/lease (when (ui/sub [::activate?]) {:resource ::feed :params {:n 7}}))
  [:span {:data-role "lease-batch-owner"} "owner"])

(defview lease-write-reader []
  (let [_ (swap! batch-evidence update :read-bodies inc)]
    [:output {:data-role "lease-write-count"}
     (str (ui/sub [::write-count]))]))

(defview lease-write-app []
  [:section [lease-write-batch] [lease-write-reader]])

(deftest one-lease-passive-batch-drains-eight-writes-before-one-read-render
  (if-not (browser?)
    (is true ":node — browser gate runs one-drain/one-render proof")
    (do
      (install-resource-control!)
      (rf/reg-sub ::activate? (fn [db _] (:activate? db)))
      (rf/reg-sub ::write-count (fn [db _] (:writes db)))
      (rf/reg-event ::activate (fn [{:keys [db]} _]
                                 {:db (assoc db :activate? true)}))
      ;; Replace only the deterministic ensure handler: all eight ordinary
      ;; queued events write in one run-to-completion drain.
      (rf/reg-event :rf.resource/ensure
                    (fn [{:keys [db]} [_ payload]]
                      (swap! batch-evidence update :ensures inc)
                      {:db (update db :writes inc)}))
      (let [f       (make-test-frame :ui.lease/batch-frame
                                     {:activate? false :writes 0})
            frame-id (frame/frame-target->id f)
            read-key [:sub frame-id [::write-count]]]
        (async done
          (->
           (uit/with-root [root [ui/frame-provider {:frame f}
                                 [lease-write-app]]]
             (let [reader-cell (some #(when (contains?
                                                   (reactive/committed-target-keys %)
                                                   read-key)
                                                %)
                                     (reactive/current-live-cells))
                   revision0 (reactive/revision reader-cell)]
               (reset! batch-evidence {:ensures 0 :read-bodies 0})
               (-> (uit/flush! #(uit/dispatch! f [::activate]))
                   (.then (fn [] (settle!)))
                   (.then
                    (fn []
                      (is (= 8 (:ensures @batch-evidence)))
                      (is (= "8"
                             (.-textContent
                              (uit/query root "[data-role='lease-write-count']"))))
                      (is (= 1 (:read-bodies @batch-evidence))
                          "eight event epochs settle before one read body")
                      (is (= (inc revision0) (reactive/revision reader-cell))
                          "the aggregate ViewCell revision advances once"))))))
           (.then (fn []
                    (rf/destroy-frame! f)
                    (done))
                  (fn [e]
                    (rf/destroy-frame! f)
                    (is false (str "lease batch/render proof rejected: " e))
                    (done)))))))))
