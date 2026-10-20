(ns re-frame.fresco.test-kit-images-dom-cljs-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.test-support :as rf.test-support]))

(def ^:private fx-id
  :kit.transport/send)

(defonce ^:private !deliveries (atom []))
(defonce ^:private !timers (atom []))

;; Register above the fixture, which snapshots the source store when defined.
(rf/reg-sub ::log (fn [db _] (:log db)))

(rf/reg-event ::seed (fn [_ [_ v]] {:db {:log [v]}}))

(rf/reg-event ::append (fn [{:keys [db]} [_ v]] {:db (update db :log (fnil conj []) v)}))

(rf/reg-event ::announce (fn [_ [_ v]] {:fx [[:kit.transport/send v]]}))

(rf/reg-event ::arm-timer (fn [_ [_ ms v]] {:fx [[::timer {:ms ms :v v}]]}))
(rf/reg-fx ::timer
           (fn [{:keys [frame]} {:keys [ms v]}]
             (js/setTimeout #(swap! !timers conj [frame v]) ms)))

(rf.fresco/defview screen
  [_]
  [:div.screen
   [:span.log (str/join "," (rf.fresco/sub [::log]))]
   [:button.announce {:on-click [::announce "clicked"]} "announce"]])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (rf.fresco.roots-frames-support/leave-act-environment!)
                      (rf.fresco.impl.collector/reset-runtime!))}))

;; Register collisions inside each test so sibling fixture baselines stay unambiguous.
(defn- register-the-override!
  []
  (reset! !deliveries [])
  (reset! !timers [])
  (rf/reg-fx :kit.transport/send {:ns 'kit.library}
             (fn [{:keys [frame]} v] (swap! !deliveries conj [:library frame v])))
  (rf/reg-fx :kit.transport/send {:ns 'kit.app}
             (fn [{:keys [frame]} v] (swap! !deliveries conj [:app frame v])))
  nil)

(def ^:private screen-image
  (rf/image {:id        :kit.image/screen
             :select-ns {:include ["re-frame.fresco.test-kit-images-dom-cljs-test"]}}))

(def ^:private library-image
  (rf/image {:id :kit.image/library :select-ns {:include ["kit.library"]}}))

(def ^:private app-image
  (rf/image {:id :kit.image/app :select-ns {:include ["kit.app"]}}))

(def ^:private both-image
  (rf/image {:id :kit.image/both :select-ns {:include ["kit.library" "kit.app"]}}))

(def ^:private app-wins
  [screen-image library-image app-image])

(def ^:private library-wins
  [screen-image app-image library-image])

(defn- text-at [m sel] (some-> (.querySelector (:container m) sel) .-textContent))

(defn- body-children [] (.-childElementCount js/document.body))

(defn- attempt
  [f]
  (try {:handle (f)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- unmount-stray!
  [outcome]
  (some-> (:handle outcome) rf.fresco.test.mounted/unmount!)
  nil)

(defn- server-bytes!
  []
  (let [bytes-frame ::server
        _           (rf/make-frame {:id bytes-frame})
        html        (rf.fresco.roots-frames-support/server-html! bytes-frame [screen {}])]
    (rf/destroy-frame! bytes-frame)
    (rf.fresco.impl.collector/reset-runtime!)
    html))

(defn- mounted-or-fail!
  [what outcome]
  (or (:handle outcome)
      (do (is false (str what " refused: " (pr-str (:refused outcome))
                         " — " (:message outcome)))
          nil)))

(defn- clean!
  [ms]
  (doseq [m ms] (rf.fresco.test.mounted/unmount! m))
  (reduce (fn [p m]
            (.then p (fn [_]
                       (.then (rf.fresco.test.mounted/assert-clean! m)
                              (fn [report]
                                (is (true? (:clean? report))))))))
          (js/Promise.resolve nil)
          ms))

(deftest an-override-is-ambiguous-without-images-and-mounts-under-ordered-images
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (register-the-override!)
      (if-some [d (mounted-or-fail! "mount! with no :images"
                                    (attempt #(rf.fresco.test.mounted/mount! [screen {}])))]
        (do
          (testing "the default image leaves an override ambiguous"
            (is (= :rf.error/ambiguous-registration
                   (:rf.error/id (:refused (attempt #(rf/handler-meta
                                                       {:frame (:frame d) :kind :fx :id fx-id})))))))
          (-> (clean! [d])
              (.then
                (fn [_]
                  (if-some [m (mounted-or-fail! "mount! with ordered :images"
                                                (attempt #(rf.fresco.test.mounted/mount!
                                                            [screen {}] {:images app-wins})))]
                    (do
                      (testing "later images win"
                        (rf.fresco.test.mounted/dispatch-and-settle! m [::announce "hello"])
                        (is (= [[:app (:frame m) "hello"]] @!deliveries)))
                      (-> (clean! [m]) (.then (fn [_] (done)))))
                    (done))))))
        (done)))))

(deftest two-owned-mounts-each-resolve-their-own-winner
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (register-the-override!)
      (let [a (mounted-or-fail! "mount! with the app image last"
                                (attempt #(rf.fresco.test.mounted/mount!
                                            [screen {}] {:images app-wins})))
            b (when a
                (mounted-or-fail! "mount! with the library image last"
                                  (attempt #(rf.fresco.test.mounted/mount!
                                              [screen {}] {:images library-wins}))))]
        (if (and a b)
          (do

            (testing "each mount owns its image order"
              (.click (.querySelector (:container a) ".announce"))
              (.click (.querySelector (:container b) ".announce"))
              (rf.fresco.test.mounted/settle! a)
              (is (= [[:app (:frame a) "clicked"] [:library (:frame b) "clicked"]]
                     @!deliveries)))
            (-> (clean! [a b]) (.then (fn [_] (done)))))
          (-> (clean! (filterv some? [a b])) (.then (fn [_] (done)))))))))

(deftest initial-events-and-the-clock-are-unchanged-beside-images
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (register-the-override!)
      (if-some [m (mounted-or-fail! "mount! with :images, :initial-events and :clock"
                                    (attempt #(rf.fresco.test.mounted/mount!
                                                [screen {}]
                                                {:images         app-wins
                                                 :initial-events [[::seed "one"]
                                                                  [::append "two"]
                                                                  [::arm-timer 300 "seeded"]]
                                                 :clock          true})))]
        (do
          (testing "initial events settle before rendering"
            (is (= "one,two" (text-at m ".log"))))
          (testing "the virtual clock honors its deadline"
            (rf.fresco.test.mounted/advance-clock! m 299)
            (is (= [] @!timers))
            (rf.fresco.test.mounted/advance-clock! m 1)
            (is (= [[(:frame m) "seeded"]] @!timers)))
          (-> (clean! [m]) (.then (fn [_] (done)))))
        (done)))))

(deftest core-refuses-a-composition-it-cannot-build
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (do
      (register-the-override!)
      (let [before [(rf.fresco.test.mounted/census) (body-children)]]
        (testing "core refuses a duplicate within one image"
          (let [{:keys [refused]} (attempt #(rf.fresco.test.mounted/mount!
                                              [screen {}] {:images [screen-image both-image]}))]
            (is (= {:rf.error/id :rf.error/image-duplicate-id :image :kit.image/both :kind :fx :id fx-id}
                   (select-keys refused [:rf.error/id :image :kind :id])))))
        (testing "core refuses an empty composition"
          (is (= :rf.error/make-frame-bad-images
                 (:rf.error/id (:refused (attempt #(rf.fresco.test.mounted/mount!
                                                     [screen {}] {:images []})))))))
        (testing "refusals allocate nothing"
          (is (= before [(rf.fresco.test.mounted/census) (body-children)])))))))

(deftest hydrate-adopts-under-ordered-images
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [html (server-bytes!)
            _    (register-the-override!)]
        (-> (rf.fresco.test.mounted/hydrate! [screen {}] {:html html :images app-wins})
            (.then (fn [m]
                     (testing "hydration uses the composition"
                       (rf.fresco.test.mounted/dispatch-and-settle! m [::announce "adopted"])
                       (is (= [[:app (:frame m) "adopted"]] @!deliveries)))
                     (clean! [m]))
                   (fn [e]
                     (is false (str "hydrate! with ordered :images rejected: "
                                    (pr-str (ex-data e)) " — " (ex-message e)))))
            (.then (fn [_] (done))))))))

(deftest shadow-builds-both-mounts-from-one-composition
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (do
      (register-the-override!)
      (let [{:keys [handle]}
            (attempt #(let [s (rf.fresco.test.mounted/shadow!
                                {:reference      [screen {}]
                                 :candidate      [screen {}]
                                 :images         app-wins
                                 :initial-events [[::seed "one"]]})]
                        (try
                          ((:checkpoint! s))
                          (doseq [m [(:reference s) (:candidate s)]]
                            (.click (.querySelector (:container m) "button.announce")))
                          ((:checkpoint! s))
                          (finally ((:stop! s))))))]
        (testing "shadow comparison accepts images"
          (is (= {:status :green :checkpoints 2} handle)))
        (testing "each shadow mount runs the image's winner"
          (is (= [[:app :app] 2]
                 [(mapv first @!deliveries)
                  (count (set (map second @!deliveries)))])))))))

(deftest unknown-options-are-refused-before-anything-is-allocated
  (let [platform-timeout js/setTimeout
        {:keys [refused message] :as outcome}
        (attempt #(rf.fresco.test.mounted/mount!
                    [screen {}] {:clock true :image [screen-image]}))]
    (unmount-stray! outcome)
    (is (= {:rf.error/id :rf.error/fresco-test-bad-option :unknown [:image]}
           (select-keys refused [:rf.error/id :unknown])))
    (is (some? (re-find #":images" (str message))))
    (is (identical? platform-timeout js/setTimeout))
    (testing "options are a map"
      (let [outcome (attempt #(rf.fresco.test.mounted/mount! [screen {}] [:initial-events]))]
        (unmount-stray! outcome)
        (is (= :rf.error/fresco-test-bad-option (:rf.error/id (:refused outcome))))))))

(deftest hydrate-refuses-an-unknown-option-as-a-rejection
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [html     (server-bytes!)
            children (body-children)]
        (-> (rf.fresco.test.mounted/hydrate! [screen {}] {:html html :imagez app-wins})
            (.then (fn [m]
                     (is false "hydrate! resolved a handle despite an unknown option")
                     (clean! [m]))
                   (fn [e]
                     (testing "hydration refuses through its promise"
                       (is (= {:rf.error/id :rf.error/fresco-test-bad-option :unknown [:imagez]}
                              (select-keys (ex-data e) [:rf.error/id :unknown]))))
                     (testing "refusal precedes allocation"
                       (is (= children (body-children))))))
            (.then (fn [_] (done))))))))
