(ns re-frame.fresco.test-kit-images-dom-cljs-test
  "THE MOUNTED FACADE BUILDS ITS FRAME FROM THE COMPOSITION IT IS GIVEN.

  Every door that mints a frame — `hm/mount!`, `hm/hydrate!`, and
  `hm/shadow!` through them — takes core's own `:images` option and hands
  it to `rf/make-frame` as given. Omitting it is the default image, the
  whole source store, exactly as before.

  ## Why the default image is not enough

  An application that overrides a library's registration — one `[kind id]`
  registered from two namespaces — is a supported composition, but not an
  implicit one. The default image refuses it (`:rf.error/image-duplicate-id`),
  because it will not let load order pick the winner; disjoint ordered
  images are how the override is SAID, and the later image wins. A test
  kit that always built from the default image could not mount such an
  application at all.

  [[register-the-override!]] builds that shape from invented ids: one
  `[:fx :kit.transport/send]` registered from `kit.library` and again from
  `kit.app`, each recording which implementation ran and in which frame.
  Every row registers it inside its own body and never at load, because a
  load-time collision would sit in the shared source store and refuse the
  default image of every suite loaded after this one. The reset fixture's
  source-store restore takes both rows back out after each row.

  ## What each row holds fixed

  | Row | Claim |
  |---|---|
  | [[an-override-the-default-image-refuses-mounts-under-ordered-images]] | the default image refuses (the control) and the ordered composition mounts, with the later image's implementation live |
  | [[two-owned-mounts-each-resolve-their-own-winner]] | two mounts, two orders, two winners, and two clean teardowns |
  | [[initial-events-and-the-clock-are-unchanged-beside-images]] | seed steps still run in order before the render, and a timer a seed step arms is still the mount's virtual clock's |
  | [[core-refuses-a-composition-it-cannot-build]] | a collision WITHIN one selected image, and an empty `:images`, are refused with core's own ids, and leave nothing behind |
  | [[hydrate-adopts-under-ordered-images]] | the same composition through the adoption door |
  | [[shadow-builds-both-mounts-from-one-composition]] | both sides of a shadow run resolve the same winner |
  | [[unknown-options-are-refused-before-anything-is-allocated]] | an option outside a door's closed roster is refused, naming the roster, before the clock or the frame exists |
  | [[hydrate-refuses-an-unknown-option-as-a-rejection]] | the same refusal through the promise door, as a rejection |

  ## Lane

  Every row that mounts needs a real React DOM and runs under
  `:browser-test`; in `:node-test` each degrades to a stated skip. The
  synchronous refusal row mounts nothing, so it runs in both lanes."
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

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------
;;
;; Registered ABOVE `use-fixtures`, deliberately: the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; registration written below it is erased before the first row runs.

(def ^:private fx-id
  "The one effect id two namespaces register."
  :kit.transport/send)

(defonce ^:private !deliveries (atom []))
(defonce ^:private !timers (atom []))

(rf/reg-sub ::log (fn [db _] (:log db)))

(rf/reg-event ::seed (fn [_ [_ v]] {:db {:log [v]}}))

(rf/reg-event ::append (fn [{:keys [db]} [_ v]] {:db (update db :log (fnil conj []) v)}))

(rf/reg-event ::announce (fn [_ [_ v]] {:fx [[:kit.transport/send v]]}))

(rf/reg-event ::arm-timer (fn [_ [_ ms v]] {:fx [[::timer {:ms ms :v v}]]}))

;; A plain platform timer, armed from inside a handler. Under `{:clock true}`
;; `js/setTimeout` is the mount's virtual clock, so whether this timer fires on
;; `advance-clock!` is the reading of whose clock it was armed on.
(rf/reg-fx ::timer
           (fn [{:keys [frame]} {:keys [ms v]}]
             (js/setTimeout #(swap! !timers conj [frame v]) ms)))

(rf.fresco/defview screen
  "Reads one subscription and announces through the overridden effect."
  [_]
  [:div.screen
   [:span.log (str/join "," (rf.fresco/sub [::log]))]
   [:button.announce {:on-click [::announce "clicked"]} "announce"]])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; The MAP shape, because most rows here are `async`.
     :async?        true
     :init-fn       (fn []
                      (rf.fresco.roots-frames-support/leave-act-environment!)
                      (rf.fresco.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The override, and the images that say which side of it wins
;; ---------------------------------------------------------------------------

(defn- register-the-override!
  "The library's transport and the application's override of it: ONE
  `[:fx :kit.transport/send]`, registered from `kit.library` and from
  `kit.app`. Each implementation records `[owner frame value]`, so a
  reading names the winner AND the frame it ran in. Called inside a row
  only — see the namespace docstring on why never at load."
  []
  (reset! !deliveries [])
  (reset! !timers [])
  (rf/reg-fx :kit.transport/send {:ns 'kit.library}
             (fn [{:keys [frame]} v] (swap! !deliveries conj [:library frame v])))
  (rf/reg-fx :kit.transport/send {:ns 'kit.app}
             (fn [{:keys [frame]} v] (swap! !deliveries conj [:app frame v])))
  nil)

(def ^:private screen-image
  "This namespace's own registrations: the screen, its sub, its events."
  (rf/image {:id        :kit.image/screen
             :select-ns {:include ["re-frame.fresco.test-kit-images-dom-cljs-test"]}}))

(def ^:private library-image
  (rf/image {:id :kit.image/library :select-ns {:include ["kit.library"]}}))

(def ^:private app-image
  (rf/image {:id :kit.image/app :select-ns {:include ["kit.app"]}}))

(def ^:private both-image
  "One image selecting BOTH sides — the collision an override must not be
  written as."
  (rf/image {:id :kit.image/both :select-ns {:include ["kit.library" "kit.app"]}}))

(def ^:private app-wins
  "The override as it is meant: the application's image last."
  [screen-image library-image app-image])

(def ^:private library-wins
  "The same three images in the other order."
  [screen-image app-image library-image])

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn- text-at [m sel] (some-> (.querySelector (:container m) sel) .-textContent))

(defn- body-children [] (.-childElementCount js/document.body))

(defn- attempt
  "Run `f`, answering `{:handle h}` or, when it throws, `{:refused data
  :message text}`. A row reads the outcome rather than letting a throw
  escape, so a door that refuses where it should mount is a RED that names
  the refusal — and `done` is still reached."
  [f]
  (try {:handle (f)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- unmount-stray!
  "Take down a mount a refusal row did not expect to get. A door that
  mounted where it should have refused is already a red; leaving its root
  standing would also skew every reading taken after it."
  [outcome]
  (some-> (:handle outcome) rf.fresco.test.mounted/unmount!)
  nil)

(defn- server-bytes!
  "The bytes an SSR route would deliver for the screen, rendered on a
  throwaway default-image frame BEFORE any override exists, and released
  so the runtime a hydration is measured on is empty."
  []
  (let [bytes-frame ::server
        _           (rf/make-frame {:id bytes-frame})
        html        (rf.fresco.roots-frames-support/server-html! bytes-frame [screen {}])]
    (rf/destroy-frame! bytes-frame)
    (rf.fresco.impl.collector/reset-runtime!)
    html))

(defn- mounted-or-fail!
  "`(:handle outcome)`, or nil after failing the row with the refusal."
  [what outcome]
  (or (:handle outcome)
      (do (is false (str what " refused: " (pr-str (:refused outcome))
                         " — " (:message outcome)))
          nil)))

(defn- clean!
  "Take `ms` down in order and assert each clean; answers a promise."
  [ms]
  (doseq [m ms] (rf.fresco.test.mounted/unmount! m))
  (reduce (fn [p m]
            (.then p (fn [_]
                       (.then (rf.fresco.test.mounted/assert-clean! m)
                              (fn [report]
                                (is (true? (:clean? report))
                                    (str "residue: " (pr-str (:leaked report)))))))))
          (js/Promise.resolve nil)
          ms))

;; ---------------------------------------------------------------------------
;; The override: refused by default, mounted when composed
;; ---------------------------------------------------------------------------

(deftest an-override-the-default-image-refuses-mounts-under-ordered-images
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (register-the-override!)
      (let [before   (rf.fresco.test.mounted/census)
            children (body-children)
            default  (attempt #(rf.fresco.test.mounted/mount! [screen {}]))]
        (testing "THE CONTROL: with no :images the mount runs the default image,
                  which refuses the override rather than let load order pick a
                  winner — so the green below is differential, not vacuous"
          (is (= :rf.error/image-duplicate-id (:rf.error/id (:refused default))))
          (is (= [:fx fx-id] [(:kind (:refused default)) (:id (:refused default))]))
          (is (nil? (:image (:refused default)))
              "refused by the DEFAULT image, which carries no id"))
        (testing "and the refusal leaves nothing behind"
          (is (= before (rf.fresco.test.mounted/census)))
          (is (= children (body-children))))
        (if-some [m (mounted-or-fail! "mount! with ordered :images"
                                      (attempt #(rf.fresco.test.mounted/mount!
                                                  [screen {}] {:images app-wins})))]
          (do
            (testing "the ordered composition mounts the screen"
              (is (= "announce" (text-at m ".announce"))))
            (testing "and the frame runs the LATER image's implementation"
              (rf.fresco.test.mounted/dispatch-and-settle! m [::announce "hello"])
              (is (= [[:app (:frame m) "hello"]] @!deliveries)))
            (-> (clean! [m]) (.then (fn [_] (done)))))
          (done))))))

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
            (testing "each mount minted a frame of its own"
              (is (not= (:frame a) (:frame b))))
            (testing "a REAL click on each page reaches each frame's own winner —
                      the composition is a fact about the frame, so a second
                      mount with another order cannot change the first's"
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
          (testing "the seed steps ran in order and drained before the first
                    render — the page shows both, in that order"
            (is (= "one,two" (text-at m ".log"))))
          (testing "the timer a seed step armed is on the mount's virtual clock:
                    nothing fires one millisecond short, and it fires on the
                    deadline"
            (is (= [] @!timers))
            (rf.fresco.test.mounted/advance-clock! m 299)
            (is (= [] @!timers))
            (rf.fresco.test.mounted/advance-clock! m 1)
            (is (= [[(:frame m) "seeded"]] @!timers)))
          (testing "and the composition is still the one the frame runs"
            (rf.fresco.test.mounted/dispatch-and-settle! m [::announce "after"])
            (is (= [[:app (:frame m) "after"]] @!deliveries)))
          (-> (clean! [m]) (.then (fn [_] (done)))))
        (done)))))

(deftest core-refuses-a-composition-it-cannot-build
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (do
      (register-the-override!)
      (let [before   (rf.fresco.test.mounted/census)
            children (body-children)]
        (testing "a collision WITHIN one selected image is core's
                  :rf.error/image-duplicate-id, naming THAT image — an override
                  is between images, never inside one"
          (let [{:keys [refused]} (attempt #(rf.fresco.test.mounted/mount!
                                              [screen {}] {:images [screen-image both-image]}))]
            (is (= :rf.error/image-duplicate-id (:rf.error/id refused)))
            (is (= :kit.image/both (:image refused))
                (str "refused by " (pr-str (:image refused))
                     " — nil is the default image, which means :images never arrived"))
            (is (= [:fx fx-id] [(:kind refused) (:id refused)]))))
        (testing "an empty :images is core's :rf.error/make-frame-bad-images,
                  passed through rather than read as 'use the default'"
          (is (= :rf.error/make-frame-bad-images
                 (:rf.error/id (:refused (attempt #(rf.fresco.test.mounted/mount!
                                                     [screen {}] {:images []})))))))
        (testing "and neither refusal left a frame, a container or a counter"
          (is (= before (rf.fresco.test.mounted/census)))
          (is (= children (body-children))))))))

(deftest hydrate-adopts-under-ordered-images
  (if-not (rf.fresco.impl.mount/browser?)
    (rf.fresco.roots-frames-support/skip! ":node-test has no React DOM")
    (async done
      (let [html (server-bytes!)
            _    (register-the-override!)]
        (-> (rf.fresco.test.mounted/hydrate! [screen {}] {:html html :images app-wins})
            (.then (fn [m]
                     (testing "the adopted page is live under the composition"
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
      (let [{:keys [handle refused message]}
            (attempt #(rf.fresco.test.mounted/shadow!
                        {:reference      [screen {}]
                         :candidate      [screen {}]
                         :images         app-wins
                         :initial-events [[::seed "one"]]
                         :script         [{:click "button.announce"}]}))]
        (testing "shadow! accepts the composition and compares green"
          (is (= {:status :green :checkpoints 2} handle)
              (str "refused: " (pr-str refused) " — " message)))
        (testing "and BOTH sides ran the application's implementation, each in
                  its own frame"
          (is (= [:app :app] (mapv first @!deliveries)))
          (is (= 2 (count (set (map second @!deliveries))))))))))

;; ---------------------------------------------------------------------------
;; Closed rosters
;; ---------------------------------------------------------------------------

(deftest unknown-options-are-refused-before-anything-is-allocated
  (let [platform-timeout js/setTimeout]
    (testing "a near miss of :images is refused, named, and the message lists
              what IS accepted — an option quietly ignored is a setting its
              author believes is in force"
      (let [{:keys [refused message] :as outcome}
            (attempt #(rf.fresco.test.mounted/mount! [screen {}] {:image [screen-image]}))]
        (unmount-stray! outcome)
        (is (= :rf.error/fresco-test-bad-option (:rf.error/id refused)))
        (is (= [:image] (:unknown refused)))
        (is (some? (re-find #":images" (str message))) (str "got " (pr-str message)))))
    (testing "refused before the clock is installed, so a clocked call that
              never became a mount leaves the platform's timers in place"
      (let [{:keys [refused] :as outcome}
            (attempt #(rf.fresco.test.mounted/mount! [screen {}] {:clock true :seed []}))]
        (unmount-stray! outcome)
        (is (= :rf.error/fresco-test-bad-option (:rf.error/id refused)))
        (is (= [:seed] (:unknown refused)))
        (is (identical? platform-timeout js/setTimeout))))
    (testing "options are a map"
      (let [outcome (attempt #(rf.fresco.test.mounted/mount! [screen {}] [:initial-events]))]
        (unmount-stray! outcome)
        (is (= :rf.error/fresco-test-bad-option (:rf.error/id (:refused outcome))))))
    (testing "shadow!'s roster is closed too, with :images now inside it"
      (let [{:keys [refused]}
            (attempt #(rf.fresco.test.mounted/shadow! {:reference [screen {}]
                                                       :candidate [screen {}]
                                                       :imagez    app-wins}))]
        (is (= :rf.error/fresco-test-bad-option (:rf.error/id refused)))
        (is (= [:imagez] (:unknown refused)))))))

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
                     (testing "the promise is the whole error channel: the
                               refusal is a rejection, not a throw past every
                               .catch"
                       (is (= :rf.error/fresco-test-bad-option (:rf.error/id (ex-data e))))
                       (is (= [:imagez] (:unknown (ex-data e)))))
                     (testing "and it came before the container"
                       (is (= children (body-children))))))
            (.then (fn [_] (done))))))))
