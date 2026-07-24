(ns re-frame.freehand.bench.b5-mount-dom-cljs-test
  "B5's initial-MOUNT reading: the wall time to mount the MIXED shape into a
  real browser, first render committed.

  D021's B5 row names an initial-mount time beside the shipped bytes. Bytes
  are a fact about a FILE and are measured off the release artefact
  ([[re-frame.freehand.bench.b5-bundle-cljs-test]]); mount time is a fact
  about RUNTIME and can only be taken in a browser. The release build carries
  no `:dev-http`, so there is nothing to point a browser at — which is why
  this reading is taken the way every other Freehand runtime reading is: by
  MOUNTING the shape in the real browser the DOM suite already runs in, over
  `react-dom/client`, and timing the first commit.

  ## The mixed SHAPE, reproduced

  The subject is the shape the mixed `:freehand-release` bundle ships — one
  interpreted leaf and its compiled twin, under one interpreted root — so the
  views below mirror `re-frame.freehand.release-app` exactly: `counter`
  interpreted, `counter-compiled` promoted, and an interpreted `app` root
  that composes both over one frame. Timing the mount of these is timing the
  mount of the mixed shape; it is not timing the `:advanced` bundle's load,
  which is what the byte and parse/compile readings are for.

  ## Evidence, never a gate

  The reading is a distribution of wall-clock milliseconds, published with
  provenance and asserted against NOTHING. D021 sets no mount-time threshold,
  and one set here would be measuring the CI runner, not the substrate —
  exactly the discipline B4's latency half keeps. The only assertions are
  non-vacuity: the shape actually mounted, both tiers reached the DOM, and
  the timer moved.

  ## Citable there, readable here

  A browser has no environment and no git, so the revision a record must
  name can only be compiled in — `re-frame.freehand.bench.provenance`'s
  `build-revision` define. The per-PR `:browser-test` gate sets none and the
  record it publishes is honestly marked NOT CITABLE; the evidence lane's
  `:browser-test-freehand-bench` build reads the sha from `RF2_REVISION` and
  the same record becomes citable, validated through `provenance/result`
  with no change here.

  Node has no browser to mount into, so under the node lane this SKIPS
  cleanly on `(browser?)` — the same posture the other `*-dom` benches keep.
  It runs for real under `npm run test:browser`, and for the record under
  `npm run bench:freehand-browser`.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The mixed shape — mirroring re-frame.freehand.release-app
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))
(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub ::count (fn [db _] (:count db)))

(v/defview counter
  "The interpreted paved path — the release app's `counter`, verbatim."
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview counter-compiled
  "The compiled hot tier over the same body — `{:compiled true}` and nothing
  else changed."
  {:compiled true}
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview app
  "The mounted root: both tiers on one page, over one frame — the mixed
  shape the release bundle ships."
  [_]
  [:main.freehand-release-mixed
   [counter {:label "interpreted"}]
   [counter-compiled {:label "compiled"}]])

;; ---------------------------------------------------------------------------
;; Sampling
;; ---------------------------------------------------------------------------

(def ^:private warmup 3)
(def ^:private samples 12)

;; ---------------------------------------------------------------------------
;; Browser plumbing — the b4-dom shape, pared to what a mount needs
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

;; ---------------------------------------------------------------------------
;; One timed mount
;; ---------------------------------------------------------------------------

;; A monotonic counter minting a distinct identity for every mount, warmup
;; and measured alike. React tears a root down asynchronously, so a
;; disambiguator reused across two mounts can collide with its own still-live
;; predecessor; a fresh number per mount sidesteps the teardown race entirely.
(defonce ^:private mount-seq (atom 0))

(defn- mount-once!
  "Mount the mixed root into a FRESH container under a FRESH frame, owning
  the frame and draining its `:initial-events` — the same preflight the
  release build's `-main` runs — and answer the wall time from just before
  the mount to when React has committed the first render, alongside the arm
  so the caller can tear it down.

  Each sample uses its own frame id and disambiguator: mounting one root view
  many times is many occurrences of it, not one root reused, so each needs
  its own derived identity — minted from [[mount-seq]] so no two ever share
  one."
  []
  (let [n         (swap! mount-seq inc)
        container (js/document.createElement "div")
        fid       (keyword "b5-mount" (str "frame-" n))
        tag       (keyword "b5-mount" (str "arm-" n))]
    (.appendChild js/document.body container)
    (let [t0 (m/now-ms)]
      (-> (act #(v/mount [app {}] container
                         {:frame         {:id fid :initial-events [[::seed]]}
                          :disambiguator tag}))
          (.then (fn [mounted]
                   {:ms (- (m/now-ms) t0) :container container :mounted mounted}))
          (.catch (fn [e] (.remove container) (js/Promise.reject e)))))))

(defn- release! [arm]
  (when arm
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (when-let [mounted (:mounted arm)]
      (.unmount (.-react-root ^root/Root mounted)))
    (.remove (:container arm)))
  nil)

(defn- publish!
  "Write the record to the console as EDN, prefixed with whether it is
  citable.

  A citable record goes through `provenance/result` — the ONE documented
  emission door — so a browser record is validated by exactly what validates
  a JVM one, rather than by a bypass that logs whatever it was handed. An
  unattributable one (a build that compiled no revision in) is printed as it
  stands rather than forced through the door, which would throw; it is still
  worth reading and is not worth citing, and the line says which."
  [record]
  (let [ds (prov/defects record)]
    (js/console.log
      (str ";; B5 initial-mount evidence — "
           (if (seq ds)
             (str "NOT CITABLE (" (count ds) " provenance field(s) unnamed by this build)")
             "citable")
           "\n" (pr-str (if (seq ds) record (prov/result record)))))))

;; ---------------------------------------------------------------------------
;; The reading
;; ---------------------------------------------------------------------------

(defn- run-samples!
  "Mount, time and unmount `n` times, threading the collected millisecond
  readings through a promise chain (one mount in flight at a time, so a
  sample times a mount into a quiet document rather than into a pile of
  siblings). `keep?` gates whether a sample's reading is recorded, so the
  warmup mounts pay React's first-run costs without polluting the figures."
  [acc n keep?]
  (reduce
    (fn [p i]
      (.then p (fn [readings]
                 (-> (mount-once!)
                     (.then (fn [arm]
                              (when (zero? i)
                                (is (some? (.querySelector (:container arm) ".freehand-release-mixed"))
                                    "the mixed root mounted into the real DOM")
                                (is (= 2 (.-length (.querySelectorAll (:container arm) ".counter")))
                                    "both tiers — interpreted and compiled — reached the DOM"))
                              (let [ms (:ms arm)]
                                (release! arm)
                                (if keep? (conj readings ms) readings))))))))
    (js/Promise.resolve acc)
    (range n)))

(defn- mount-row! [done]
  (-> (run-samples! [] warmup false)
      (.then (fn [_] (run-samples! [] samples true)))
      (.then (fn [readings]
               (is (= samples (count readings)) "every measured mount produced a reading")
               (is (every? #(and (number? %) (not (neg? %))) readings)
                   "each initial-mount time is a non-negative duration")
               (let [dist (assoc (m/summarise readings)
                                 :doc "wall time to mount the mixed shape and commit the first render"
                                 :observable :duration-ms)]
                 (publish!
                   {:benchmark :B5/initial-mount-mixed
                    :doc       (str "initial mount of the mixed Freehand shape "
                                    "(one interpreted leaf, one compiled twin) in a real browser")
                    :revision  (prov/detect-revision)
                    :fixture   {:shape :mixed
                                :leaves 2
                                :tiers [:interpreted :compiled]
                                :measurement-method
                                (str "wall time across act(v/mount) resolution — mount into a "
                                     "fresh container under a fresh owned frame whose "
                                     ":initial-events seed app-db, to React's first committed "
                                     "render; " warmup " warmup mounts discarded, " samples
                                     " measured, one mount in flight at a time")}
                    :build     (prov/detect-build)
                    :host      (prov/detect-host)
                    :sampling  {:warmup warmup :samples samples}
                    :baseline  {:kind      :before-vs-after
                                :reference {:revision :the-revision-before-this
                                            :note "the same shape's mount time on the preceding revision"}}
                    :distribution {:B5/initial-mount-ms dist}
                    :status    :evidence})
                 nil)))
      (.catch (fn [e] (is false (str "B5 mount row rejected: " e))))
      (.finally done)))

(deftest initial-mount-of-the-mixed-shape-is-measured-in-a-real-browser
  (testing "D021's B5 initial-mount reading: the wall time to mount the mixed
            Freehand shape — one interpreted leaf and its compiled twin under
            one root — into a real browser, first render committed. Published
            as a distribution with provenance and gated against nothing; the
            only assertions are that the shape mounted, both tiers reached the
            DOM, and the timer moved. Node has no browser, so it SKIPS there."
    (if-not (browser?)
      (is true "a real browser mount is required — the browser job runs this reading")
      (async done (mount-row! done)))))
