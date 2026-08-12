(ns re-frame.freehand.projection-roster-dom-cljs-test
  "FH-EVENT-001 in a REAL browser — the two demonstrated-need members of
  the closed projection roster, read off live platform events.

  `projection-roster-cljs-test` proves what the two members MEAN: the
  intent is data, the two front ends agree, and a toggle report
  reconciles itself. It cannot prove the thing they were admitted for,
  because neither fact exists outside a browser — there is no top layer
  in Node to report a `ToggleEvent`, and no layout to give an element a
  scroll offset. So both rows here are read back off application state
  after a real `react-dom/client` commit and a real platform event.

  The toggle row is the defect stated as a test. The top-layer runtime
  tells an author to reconcile `:on-toggle` \"with ordinary event
  intent\"; before `::v/new-state` the grammar had no spelling for the
  report's own state, so the substrate advised a path it refused. What
  passes below is the advisory's own instruction, written as an ordinary
  event vector, with the platform's word — \"open\", then \"closed\" —
  arriving in app-db as data.

  This file rides the browser lane through its `-dom-cljs-test` suffix.
  It also matches the node suites' broader regex, where it has no top
  layer and no layout to drive and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.web :as web]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :dom/projection-roster)

;; ---------------------------------------------------------------------------
;; Views. Module-level — a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview toggle-probe
  "A controlled popover whose dismissal reconciliation is an ORDINARY
  EVENT VECTOR. This is the exact spelling the top-layer advisory asks
  for, and before `::v/new-state` it could not be written."
  {:props [:map [:open? :boolean]]}
  [{:keys [open?]}]
  [:div {:id                 "roster-menu"
         :popover            :auto
         ::web/popover-open? open?
         :on-toggle          [:probe/toggle-reported ::v/new-state]}
   "menu"])

(v/defview scroll-probe
  "A scrolling viewport whose offset rides an ordinary event vector — no
  `v/event` body, and therefore no reader conditional in what would be a
  host-neutral library."
  {:props [:map [:rows :int]]}
  [{:keys [rows]}]
  [:div {:id       "roster-viewport"
         :style    {:height 100 :overflow-y "auto"}
         :on-scroll [:probe/scrolled ::v/scroll-top]}
   [:div {:id "roster-canvas" :style {:height (* rows 32)}}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- browser?
  "A real top layer and real layout, not merely a DOM."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))
       (some? (.-showPopover (.-prototype js/HTMLElement)))))

(defn- skip! [why]
  (is true (str "a real browser is required — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- settle
  "A generous macrotask boundary. The platform queues a popover's `toggle`
  and an element's `scroll` as TASKS rather than firing them
  synchronously, and each costs a dispatch and the re-render that
  dispatch schedules."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 60))))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (.unmount root)
  (.remove container)
  nil)

(defn- setup! []
  (live-frame/make-frame {:id fid})
  (rf/reg-event :probe/toggle-reported
    (fn [{:keys [db]} [_ state]]
      {:db (update db ::reports (fnil conj []) state)}))
  (rf/reg-event :probe/scrolled
    (fn [{:keys [db]} [_ top]]
      {:db (assoc db ::scroll-top top)}))
  (frame/replace-app-db! fid {})
  nil)

(defn- element [form] (shell/provide-frame fid (fr/element form)))

(defn- by-id [id] (js/document.getElementById id))
(defn- reports [] (get (frame/frame-app-db-value fid) ::reports))
(defn- scroll-top [] (get (frame/frame-app-db-value fid) ::scroll-top))

;; ===========================================================================
;; `::v/new-state` — the advisory's own instruction, made writable
;; ===========================================================================

(deftest fh-event-001-a-live-toggle-report-reaches-app-db-as-its-own-state
  (testing "Per FH-EVENT-001 (browser): a controlled popover reconciling
            `:on-toggle` with an ORDINARY EVENT VECTOR receives the
            platform's own `ToggleEvent.newState` as data. Opening writes
            \"open\"; a browser-initiated dismissal writes \"closed\". The
            WORD is the assertion — a projection that read the wrong
            property would still dispatch a well-formed vector and write
            a well-formed db."
    (if-not (browser?)
      (skip! "the browser job runs the top-layer assertions")
      (async done
        (let [[container root] (mount!)]
          (setup!)
          (-> (act #(.render root (element [toggle-probe {:open? true}])))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (.matches (by-id "roster-menu") ":popover-open"))
                           "the popover opened at the commit")
                       (is (= ["open"] (reports))
                           "and the platform's opening report reached app-db as data")
                       ;; The browser closing it of its own accord — what
                       ;; Escape and a light dismiss both come down to, and
                       ;; the case the advisory exists for.
                       (.hidePopover (by-id "roster-menu"))
                       (settle)))
              (.then (fn [_]
                       (is (false? (.matches (by-id "roster-menu") ":popover-open"))
                           "the browser closed it without asking")
                       (is (= ["open" "closed"] (reports))
                           "and the dismissal is the WORD the platform used — no
                            counting, no inference, and distinguishable from the
                            opening report by equality")
                       ;; ASYMMETRIC, so it stays put: the rejection arm below
                       ;; tore nothing down, and there is nothing here that both
                       ;; arms already wrote for the trailing step to carry.
                       (teardown! container root)))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; `::v/scroll-top` — the offset a windowed table windows on
;; ===========================================================================

(deftest fh-event-001-a-live-scroll-event-reaches-app-db-as-its-offset
  (testing "Per FH-EVENT-001 (browser): a scrolling viewport whose
            `:on-scroll` is an ordinary event vector materializes the
            element's live scroll offset into the dispatched vector. The
            NUMBER is the assertion, and it is compared against the
            offset the element actually holds — an extractor reading a
            neighbouring property would still produce a number."
    (if-not (browser?)
      (skip! "the browser job runs the layout assertions")
      (async done
        (let [[container root] (mount!)]
          (setup!)
          (-> (act #(.render root (element [scroll-probe {:rows 100}])))
              (.then (fn [_]
                       (is (nil? (scroll-top))
                           "no scroll has happened yet, so nothing was dispatched")
                       (set! (.-scrollTop (by-id "roster-viewport")) 320)
                       (settle)))
              (.then (fn [_]
                       (is (= 320 (.-scrollTop (by-id "roster-viewport")))
                           "the element really scrolled")
                       (is (= 320 (scroll-top))
                           "and the offset in app-db is the element's own")
                       (set! (.-scrollTop (by-id "roster-viewport")) 640)
                       (settle)))
              (.then (fn [_]
                       (is (= 640 (scroll-top))
                           "each scroll reads its OWN event, so a windowed table
                            follows the viewport rather than one render's snapshot")
                       ;; Success-only, as above.
                       (teardown! container root)))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))
