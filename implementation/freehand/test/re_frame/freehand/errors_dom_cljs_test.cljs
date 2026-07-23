(ns re-frame.freehand.errors-dom-cljs-test
  "FH-ERROR-002 / FH-ERROR-003 in a real browser — the error boundary under
  the host it exists for.

  The cross-host law suite (`errors-cljs-test`) drives `capture!` /
  `reset-to!` / `promote!` directly and proves what the state machine SAYS:
  one intent and one egress record per failure generation, cleared by a
  changed reset key, closed field sets on both. This one proves what REACT
  DOES with it, and the two are not the same claim. A once-per-generation
  gate can be perfectly shaped around a lifecycle that never calls it — the
  React commit runs `componentDidMount` / `componentDidUpdate` BEFORE the
  update queue's error callback, so a driver that promotes from those two
  and captures in `componentDidCatch` promotes an EMPTY boundary and then
  captures a failure nothing will ever report. The fallback is on screen,
  the machine is in `:failed`, every headless assertion is green, and no
  record ever reaches telemetry.

  So: a real `react-dom/client` root, a real class boundary, real
  `StrictMode`, the fallback read back off `document`, and the two channels
  counted at their real seams — the router for the safe intent, the
  always-on error-listener registry for the private egress.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.error-emit :as error-emit]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.react :as fr]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (fr/reset-boundaries!)
                      (error-emit/clear-error-listeners!))}))

(def error-002 (conf/fixture :FH-ERROR-002))
(def error-003 (conf/fixture :FH-ERROR-003))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

;; ---------------------------------------------------------------------------
;; Views. Module-level, because a declared view cannot close over a test's
;; locals — `throwing?` is the seam a retry flips.
;; ---------------------------------------------------------------------------

(defonce ^:private throwing? (atom true))

(v/defview guarded-child
  "Throws while `throwing?` is set — the render-class failure a boundary
  exists to contain — and renders normally once a retry clears it."
  [_]
  (if @throwing?
    (throw (ex-info "a child render threw" {:secret "must-not-leak"}))
    [:p {:id "child"} "recovered"]))

(v/defview other-guarded-child
  "A SECOND failing descendant — the one attribution has to tell apart from
  [[guarded-child]]."
  [_]
  (throw (ex-info "a different child render threw" {})))

(v/defview deep-thrower
  "Throws from a view the boundary does not guard directly."
  [_]
  (throw (ex-info "a grandchild render threw" {})))

(v/defview failing-fallback
  "A FALLBACK that itself throws — the second failure, which the boundary
  above the failing one has to catch and name."
  [_]
  (throw (ex-info "a fallback render threw" {})))

(v/defview healthy-wrapper
  "Renders fine itself and mounts the view that throws — so the guarded
  child, the catcher, and the thrower are three different views."
  [_]
  [:div [deep-thrower {}]])

(defn- id-of [view] (:view-id (descriptor/describe view)))

;; ---------------------------------------------------------------------------
;; The two failure channels, counted at their real seams
;; ---------------------------------------------------------------------------

(defn- open-channels!
  "Install recorders on the two channels a promotion fans out — the safe
  `:on-error` intent (the router dispatch the boundary appends its summary
  to) and the private always-on egress record — and answer them with the
  teardown that removes both.

  The intent rides a `router/dispatch!` redefinition rather than a real
  handler because the assertion is an EXACT COUNT: an event that has to be
  processed before it can be counted would be counted on a later microtask
  than the commit that produced it, and \"exactly one\" would become
  \"one so far\"."
  []
  (let [intents (atom [])
        egress  (atom [])
        orig    router/dispatch!]
    (error-emit/register-error-listener! ::recorder (fn [r] (swap! egress conj r)))
    (set! router/dispatch!
          (fn ([e] (swap! intents conj e))
            ([e _opts] (swap! intents conj e))))
    {:intents intents
     :egress  egress
     :close!  (fn []
                (set! router/dispatch! orig)
                (error-emit/unregister-error-listener! ::recorder))}))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root close!]
  (.unmount root)
  (.remove container)
  (close!)
  nil)

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- boundary-element
  "The mounted boundary under test: `reset-key` is the caller-owned retry
  value, `child` is the ONE guarded region, and the fallback is what must be
  on screen when the child throws."
  ([reset-key] (boundary-element reset-key [guarded-child {}]))
  ([reset-key child]
   (fr/element
     [v/error-boundary {:fallback  [:p {:id "fallback"} "contained"]
                        :reset-key reset-key
                        :on-error  (:on-error error-002)}
      child])))

(defn- mount-one-failure!
  "Mount a boundary over `child`, let the failure commit, and answer a
  promise of the ONE private egress record it promoted — the report an
  off-box shipper would receive."
  [child]
  (let [[container root] (mount!)
        {:keys [egress close!]} (open-channels!)]
    (-> (act #(.render root (boundary-element (:reset-key-1 error-002) child)))
        (.then (fn [_]
                 (let [record (first @egress)]
                   (teardown! container root close!)
                   record))
               (fn [e]
                 (teardown! container root close!)
                 (js/Promise.reject e))))))

(defn- mount-form!
  "Render one Freehand `form` into a fresh root and answer a promise of every
  private egress record the commit promoted, in promotion order."
  [form]
  (let [[container root] (mount!)
        {:keys [egress close!]} (open-channels!)]
    (-> (act #(.render root (fr/element form)))
        (.then (fn [_]
                 (let [records @egress]
                   (teardown! container root close!)
                   records))
               (fn [e]
                 (teardown! container root close!)
                 (js/Promise.reject e))))))

(defn- guard
  "One boundary over `child`, reporting through the fixture's `:on-error`."
  [child]
  [v/error-boundary {:fallback  [:p {:class "fallback"} "contained"]
                     :reset-key (:reset-key-1 error-002)
                     :on-error  (:on-error error-002)}
   child])

;; ===========================================================================
;; FH-ERROR-002 (browser) — the INITIAL-MOUNT generation is promoted.
;; ===========================================================================

(deftest fh-error-002-an-initial-mount-failure-reports-without-a-second-render
  (testing "Per FH-ERROR-002 (browser): a child that throws on its very
            first mounted render is contained AND reported — the fallback is
            on screen and both channels have fired exactly once — after ONE
            commit, with no unrelated parent render to prompt them. The
            React commit calls `componentDidMount` before the update queue's
            error callback, so a driver that promoted from the mount hook
            would promote a boundary that has captured nothing, and the
            capture that arrives moments later would sit in `:failed`
            forever with nothing to report it."
    (if-not (browser?)
      (skip! "the browser job runs the mounted-promotion assertions")
      (async done
        (reset! throwing? true)
        (let [[container root] (mount!)
              {:keys [intents egress close!]} (open-channels!)]
          (-> (act #(.render root (boundary-element (:reset-key-1 error-002))))
              (.then (fn [_]
                       (is (= "contained" (text container "#fallback"))
                           "the fallback committed — the throw was contained")
                       (is (= (:intents-per-generation error-002) (count @intents))
                           "exactly one safe intent, from the mounting commit itself")
                       (is (= (:egress-records-per-generation error-002) (count @egress))
                           "and exactly one private egress record")
                       (is (= (conj (vec (:on-error error-002))
                                    (:summary (first @egress)))
                              (first @intents))
                           "the intent is the author's prefix with the safe summary appended")
                       (is (= (:egress-category error-003) (:error (first @egress)))
                           "the record rides the always-on view-render-failed category")
                       (teardown! container root close!)
                       (done))
                    (fn [e]
                      (is false (str "mount rejected: " e))
                      (teardown! container root close!)
                      (done)))))))))

(deftest fh-error-002-a-reset-immediately-after-the-fallback-cannot-erase-the-report
  (testing "Per FH-ERROR-002 (browser): the very next render may already
            carry a changed `:reset-key` — a route move, a retry button
            pressed the instant the fallback appeared. The captured
            generation was reported by the commit that captured it, so the
            reset clears a failure that has already been accounted for, and
            the retry re-mounts the child. A driver that reconciled the
            reset before promoting would drop that generation on the floor:
            one contained failure, zero reports, forever."
    (if-not (browser?)
      (skip! "the browser job runs the reset-ordering assertions")
      (async done
        (reset! throwing? true)
        (let [[container root] (mount!)
              {:keys [intents egress close!]} (open-channels!)]
          (-> (act #(.render root (boundary-element (:reset-key-1 error-002))))
              (.then (fn [_]
                       (is (= "contained" (text container "#fallback")))
                       (is (= (:intents-per-generation error-002) (count @intents))
                           "the first generation reported before any reset could reach it")
                       ;; The retry: the child stops throwing, and the caller
                       ;; moves the reset value in the same render.
                       (reset! throwing? false)
                       (act #(.render root (boundary-element (:reset-key-2 error-002))))))
              (.then (fn [_]
                       (is (= "recovered" (text container "#child"))
                           "the changed reset key re-mounted and retried the child")
                       (is (nil? (text container "#fallback"))
                           "and the fallback is gone")
                       (is (= (:intents-per-generation error-002) (count @intents))
                           "still exactly one intent — the reset erased nothing unreported")
                       (is (= (:egress-records-per-generation error-002) (count @egress))
                           "and exactly one egress record")
                       (teardown! container root close!)
                       (done))
                    (fn [e]
                      (is false (str "mount rejected: " e))
                      (teardown! container root close!)
                      (done)))))))))

(deftest fh-error-002-strictmode-and-same-key-rerenders-do-not-duplicate
  (testing "Per FH-ERROR-002 (browser): under `StrictMode` — double-invoked
            renders and a simulated unmount/remount of every effect — and
            under repeated parent renders with an UNCHANGED reset key, one
            failure generation still produces exactly one safe intent and
            exactly one egress record. Promoting from the capture path is
            what makes the report prompt; the once-per-generation gate is
            what keeps it single."
    (if-not (browser?)
      (skip! "the browser job runs the StrictMode assertions")
      (async done
        (reset! throwing? true)
        (let [[container root] (mount!)
              {:keys [intents egress close!]} (open-channels!)
              strict (fn [] (react/createElement
                              react/StrictMode nil
                              (boundary-element (:reset-key-1 error-002))))]
          (-> (act #(.render root (strict)))
              (.then (fn [_] (act #(.render root (strict)))))
              (.then (fn [_] (act #(.render root (strict)))))
              (.then (fn [_]
                       (is (= "contained" (text container "#fallback"))
                           "the fallback is still what the boundary shows")
                       (is (= (:intents-per-generation error-002) (count @intents))
                           "exactly one safe intent across three StrictMode renders")
                       (is (= (:egress-records-per-generation error-002) (count @egress))
                           "and exactly one private egress record")
                       (teardown! container root close!)
                       (done))
                    (fn [e]
                      (is false (str "mount rejected: " e))
                      (teardown! container root close!)
                      (done)))))))))

;; ===========================================================================
;; FH-ERROR-003 (browser) — the mounted record names the failing DESCENDANT.
;; ===========================================================================

(deftest fh-error-003-a-mounted-report-names-the-failing-descendant
  (testing "Per FH-ERROR-003 (browser): `:view-id` is the declared view that
            THREW and `:boundary-view-id` is the catcher. Here the guarded
            child, the catcher and the thrower are three different views —
            the boundary guards a healthy wrapper which mounts the view that
            fails — so a record naming either the boundary or the guarded
            child is caught. Getting this right is the whole value of the
            report: a summary naming the catcher sends a reader to the wrong
            file, and every failure under one boundary would carry it."
    (if-not (browser?)
      (skip! "the browser job runs the mounted-attribution assertions")
      (async done
        (reset! throwing? true)
        (-> (mount-one-failure! [healthy-wrapper {}])
            (.then (fn [record]
                     (let [summary (:summary record)]
                       (is (= (id-of deep-thrower) (:view-id summary))
                           "the view that actually threw, not the one guarded")
                       (is (= eb/boundary-view-id (:boundary-view-id summary))
                           "and the catcher keeps its own separate field")
                       (is (not= eb/boundary-view-id (:view-id summary))
                           "the boundary is never named as the thrower")
                       (is (= (:view-id summary) (:view-id (:summary record)))
                           "the safe summary the app receives carries the same attribution"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

(deftest fh-error-003-two-mounted-descendants-of-one-boundary-fingerprint-apart
  (testing "Per FH-ERROR-003 (browser): the fingerprint is derived from the
            failing view and the phase, so two DIFFERENT descendants failing
            under the same boundary shape must fingerprint APART. Naming the
            shared catcher would collapse every bug beneath a boundary onto
            one correlation token and make two unrelated failures
            indistinguishable in telemetry."
    (if-not (browser?)
      (skip! "the browser job runs the mounted-fingerprint assertions")
      (async done
        (reset! throwing? true)
        (-> (mount-one-failure! [guarded-child {}])
            (.then (fn [first-record]
                     (-> (mount-one-failure! [other-guarded-child {}])
                         (.then (fn [second-record]
                                  [first-record second-record])))))
            (.then (fn [[a b]]
                     (is (= (id-of guarded-child) (:view-id (:summary a))))
                     (is (= (id-of other-guarded-child) (:view-id (:summary b))))
                     (is (not= (:fingerprint (:summary a))
                               (:fingerprint (:summary b)))
                         "two failing descendants, two fingerprints")
                     (done))
                   (fn [e]
                     (is false (str "mount rejected: " e))
                     (done))))))))

(deftest fh-error-003-two-sibling-boundaries-failing-together-each-name-their-own
  (testing "Per FH-ERROR-003 (browser): two sibling boundaries over two
            different failing views, mounted in ONE React commit. React
            finishes rendering BOTH failed subtrees before either
            `componentDidCatch` runs, so the two failures are in flight at
            the same time — and each report must still name the view that
            threw it. Attribution held in one shared slot cannot do this: the
            first thrower's id is the only one kept, so one report names a
            view that did not fail this failure and the other goes out as
            `unknown-view` for a thrower that was plainly observed. Both
            readers are then sent to the wrong file, and the two bugs share
            one correlation token."
    (if-not (browser?)
      (skip! "the browser job runs the sibling-attribution assertions")
      (async done
        (reset! throwing? true)
        (-> (mount-form! [:div
                          (guard [guarded-child {}])
                          (guard [other-guarded-child {}])])
            (.then (fn [records]
                     (let [ids (set (map #(:view-id (:summary %)) records))]
                       (is (= 2 (count records))
                           "both boundaries contained and both reported")
                       (is (= #{(id-of guarded-child) (id-of other-guarded-child)} ids)
                           "each report names its own thrower")
                       (is (not (contains? ids eb/unknown-view-id))
                           "neither failure was suppressed into unknown by the other")
                       (is (= 2 (count (set (map #(:fingerprint (:summary %)) records))))
                           "so the two failures carry two correlation tokens"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

(deftest fh-error-003-a-failure-inside-a-fallback-is-attributed-to-the-fallback
  (testing "Per FH-ERROR-003 (browser): a fallback is ordinary markup and may
            fail like any other, so it may guard itself. That makes two
            failures in ONE commit that are causally ordered — the second is
            rendered BECAUSE the first was caught — and the second is
            observed before the first has been reported. Each boundary must
            still name its own thrower: the outer one the guarded child, the
            one inside the fallback the view that failed inside the fallback.
            A shared relay hands the first thrower to whichever catch runs
            first and leaves the other with `unknown-view`."
    (if-not (browser?)
      (skip! "the browser job runs the nested-fallback assertions")
      (async done
        (reset! throwing? true)
        (-> (mount-form!
              [v/error-boundary
               {:fallback  [v/error-boundary
                            {:fallback  [:p {:class "inner"} "fallback contained"]
                             :reset-key (:reset-key-1 error-002)
                             :on-error  (:on-error error-002)}
                            [failing-fallback {}]]
                :reset-key (:reset-key-1 error-002)
                :on-error  (:on-error error-002)}
               [guarded-child {}]])
            (.then (fn [records]
                     (let [ids (set (map #(:view-id (:summary %)) records))]
                       (is (= 2 (count records))
                           "both failures were contained and both reported")
                       (is (= #{(id-of guarded-child) (id-of failing-fallback)} ids)
                           "the guarded child's failure and the fallback's are told apart")
                       (is (not (contains? ids eb/unknown-view-id))
                           "and neither borrowed or lost the other's identity"))
                     (done))
                  (fn [e]
                    (is false (str "mount rejected: " e))
                    (done))))))))

;; ===========================================================================
;; Non-vacuity probe — the channels are really open and the child really
;; throws.
;; ===========================================================================

(deftest non-vacuity-the-channels-and-the-child-are-real
  (testing "Non-vacuity: the recorders this suite counts on are wired to the
            real seams — a promotion driven by hand reaches both — and the
            guarded child really throws while `throwing?` is set. Neither a
            silent channel nor a child that quietly rendered nothing could
            produce the counts above."
    (reset! throwing? true)
    (let [{:keys [intents egress close!]} (open-channels!)
          b (eb/boundary eb/boundary-view-id (:reset-key-1 error-002))]
      (eb/capture! b {:exception (ex-info "boom" {})
                      :phase     (:phase error-002)
                      :view-id   (:view-id error-002)
                      :frame-id  nil})
      (eb/promote! b {:on-error (:on-error error-002)
                      :frame-id nil
                      :time     0})
      (is (= (:intents-per-generation error-002) (count @intents))
          "the intent recorder is on the router seam the boundary dispatches through")
      (is (= (:egress-records-per-generation error-002) (count @egress))
          "and the egress recorder is on the always-on error axis")
      (close!))
    (is (thrown? :default ((:render (.-entry guarded-child)) {}))
        "the guarded child's body throws while `throwing?` is set")))
