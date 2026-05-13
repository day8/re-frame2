(ns re-frame.story-runtime-test
  "JVM tests for re-frame2-story Stage 3 (rf2-von3) — runtime.

  Covers:

  - Args precedence: global < story < mode < variant < cell-overrides
    with deep-merge on nested maps and replace on vectors.
  - Decorator composition: classification by `:kind`, ordering (story
    decorators before variant decorators), hiccup wrap composition,
    fx-override registration.
  - Snapshot-identity: stability across re-runs; sensitivity to
    every input axis per IMPL-SPEC §5.6.
  - Lifecycle state machine: `:pre-mount → :mounting → :loading →
    :ready` via runtime fns + watcher firing.
  - `run-variant` end-to-end: registered variant → frame allocated →
    events drained → result map populated.
  - Frame teardown via `destroy-variant!`.
  - Error projection per IMPL-SPEC §5.5.

  All tests run on the JVM via `clojure -M:test`. Per the
  `jvm_interop_must_work` user-feedback rule the runtime must be JVM-
  portable — `run-variant` returns a CompletableFuture on JVM (vs JS
  Promise on CLJS); the tests `deref` it for the result map."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as frame]
            [re-frame.machines        :as machines]
            [re-frame.registrar       :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story           :as story]
            [re-frame.story.args      :as args]
            [re-frame.story.async     :as async]
            [re-frame.story.config    :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames    :as frames]
            [re-frame.story.identity  :as ident]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.runtime   :as runtime]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  ;; Tear down Story's side-table.
  (story/clear-all!)
  ;; Tear down the framework registrar so test isolation holds.
  (registrar/clear-all!)
  ;; Tear down every non-default frame.
  (reset! frame/frames {})
  ;; Install the plain-atom adapter (matches the machines test fixture
  ;; pattern). `rf/init!` is idempotent once seated; we tolerate the
  ;; double-install error if the adapter is already in place.
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  ;; Re-require machines so its `reg-sub :rf/machine` survives the
  ;; registrar/clear-all! call. Mirrors the machines test fixtures.
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  ;; Re-install the canonical vocabulary (tags + lifecycle machine).
  (story/install-canonical-vocabulary!)
  ;; Make sure :rf/default is always present.
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ---- stage marker --------------------------------------------------------

(deftest stage-marker-runtime
  (testing "Stage 6 supersedes Stage 5 — the loaded surface advertises :sota-features"
    (is (= :sota-features story/stage))))

;; ===========================================================================
;; ARGS PRECEDENCE
;; ===========================================================================

(deftest deep-merge-merges-nested-maps
  (testing "deep-merge recurses into maps"
    (is (= {:a {:b 1 :c 2}}
           (args/deep-merge {:a {:b 1}} {:a {:c 2}}))))

  (testing "non-map values replace"
    (is (= {:a [3]}
           (args/deep-merge {:a [1 2]} {:a [3]}))))

  (testing "nil right-hand is a no-op"
    (is (= {:a 1} (args/deep-merge {:a 1} nil))))

  (testing "scalar replaces nested map"
    (is (= {:a 5} (args/deep-merge {:a {:b 1}} {:a 5})))))

(deftest resolve-args-precedence-chain
  (testing "global < story < mode < variant < cell-overrides"
    (story/configure! {:global-args {:theme :light :verbose? false}})
    (story/reg-story :story.ui.button
      {:doc       "btn"
       :component :app.ui/button
       :args      {:label "Story label" :verbose? true}})
    (story/reg-mode :Mode.app/dark
      {:args {:theme :dark}})
    (story/reg-variant :story.ui.button/default
      {:args   {:label "Variant label" :icon :star}
       :events []})
    (let [resolved (story/resolve-args :story.ui.button/default
                                       {:active-modes  [:Mode.app/dark]
                                        :cell-overrides {:label "Cell label"}})]
      (is (= :dark         (:theme resolved))    "mode wins over global")
      (is (= true          (:verbose? resolved)) "story wins over global")
      (is (= "Cell label"  (:label resolved))    "cell-overrides win over variant")
      (is (= :star         (:icon resolved))     "variant arg passes through"))))

(deftest resolve-args-deep-merge-on-nested
  (testing "nested maps deep-merge across layers"
    (story/configure! {:global-args {:layout {:max-width 1024 :padding 8}}})
    (story/reg-story :story.layout.box
      {:args {:layout {:padding 16}}})
    (story/reg-variant :story.layout.box/deep
      {:args   {:layout {:margin 4}}
       :events []})
    (let [r (story/resolve-args :story.layout.box/deep)]
      (is (= 1024 (get-in r [:layout :max-width])))
      (is (= 16   (get-in r [:layout :padding]))   "story wins")
      (is (= 4    (get-in r [:layout :margin]))))))

(deftest resolve-args-unregistered-variant
  (testing "unregistered variant resolves to {} without throwing"
    (is (= {} (story/resolve-args :story.missing/x)))))

;; ===========================================================================
;; DECORATOR COMPOSITION
;; ===========================================================================

(deftest decorators-classified-by-kind
  (testing "hiccup / frame-setup / fx-override decorators land in their slots"
    (story/reg-decorator :centered
      {:kind :hiccup
       :wrap (fn [body _args] [:div.centered body])})
    (story/reg-decorator :mock-auth
      {:kind :frame-setup
       :init [[:auth/restore-session {:user "alice"}]]})
    (story/reg-decorator :stub-http
      {:kind :fx-override
       :fx-id :http
       :response {:status :pending}})
    (story/reg-variant :story.composed/v
      {:decorators [[:centered] [:mock-auth] [:stub-http]]
       :events     []})
    (let [r (story/resolve-decorators :story.composed/v)]
      (is (= 1 (count (:hiccup r))))
      (is (= :centered (-> r :hiccup first :id)))
      (is (= 1 (count (:frame-setup r))))
      (is (= :mock-auth (-> r :frame-setup first :id)))
      (is (= 1 (count (:fx-override r))))
      (is (= :stub-http (-> r :fx-override first :id)))
      (is (empty? (:errors r))))))

(deftest decorators-story-then-variant-order
  (testing "story decorators come before variant decorators in declared order"
    (story/reg-decorator :outer
      {:kind :hiccup
       :wrap (fn [body _args] [:div.outer body])})
    (story/reg-decorator :inner
      {:kind :hiccup
       :wrap (fn [body _args] [:div.inner body])})
    (story/reg-story :story.compose
      {:decorators [[:outer]]})
    (story/reg-variant :story.compose/v
      {:decorators [[:inner]]
       :events     []})
    (let [r       (story/resolve-decorators :story.compose/v)
          ids     (mapv :id (:hiccup r))]
      (is (= [:outer :inner] ids)
          "story decorators precede variant decorators in declared order"))))

(deftest decorators-apply-hiccup-outermost-first
  (testing "apply-hiccup-decorators wraps innermost first, outermost last"
    (story/reg-decorator :outer
      {:kind :hiccup
       :wrap (fn [body _args] [:div.outer body])})
    (story/reg-decorator :inner
      {:kind :hiccup
       :wrap (fn [body _args] [:div.inner body])})
    (story/reg-story :story.wrap
      {:decorators [[:outer]]})
    (story/reg-variant :story.wrap/v
      {:decorators [[:inner]]
       :events     []})
    (let [r       (story/resolve-decorators :story.wrap/v)
          wrapped (decorators/apply-hiccup-decorators (:hiccup r) [:span "x"] {})]
      ;; Outermost is :outer; inside it is :inner; inside that is the span.
      (is (= [:div.outer [:div.inner [:span "x"]]] wrapped)))))

(deftest decorators-unknown-id-becomes-error
  (testing "an unregistered decorator id surfaces in :errors"
    (story/reg-variant :story.bad/v
      {:decorators [[:totally-unregistered]]
       :events     []})
    (let [r (story/resolve-decorators :story.bad/v)]
      (is (= 1 (count (:errors r))))
      (is (= :rf.error/decorator-unknown
             (-> r :errors first :rf.error))))))

(deftest fx-overrides-map-last-wins
  (testing "two fx-override decorators with the same :fx-id resolve last-wins"
    (story/reg-decorator :first-stub
      {:kind :fx-override :fx-id :http :response {:n 1}})
    (story/reg-decorator :second-stub
      {:kind :fx-override :fx-id :http :response {:n 2}})
    (story/reg-variant :story.fx/v
      {:decorators [[:first-stub] [:second-stub]]
       :events     []})
    (let [r       (story/resolve-decorators :story.fx/v)
          stack   (decorators/fx-overrides-map (:fx-override r))]
      (is (= 1 (count (:overrides stack)))
          "last-wins: only one entry per fx-id")
      (is (contains? (:overrides stack) :http))
      ;; Stage 5 (rf2-h8et) — the stub-id is now namespaced by fx-id
      ;; so the ref-args-driven `:rf.story/force-fx-stub` decorator
      ;; can register distinct stubs for distinct fx-ids referenced
      ;; from the same decorator id. The decorator-id segment is
      ;; preserved verbatim; the fx-id is appended with `+` separator.
      (is (= :rf.story.fx-stub/second-stub+http
             (get-in stack [:overrides :http]))))))

;; ===========================================================================
;; SNAPSHOT IDENTITY
;; ===========================================================================

(deftest snapshot-identity-stable-across-runs
  (testing "two calls with the same inputs produce the same hash"
    (story/reg-story :story.id
      {:component :app/v :args {:a 1}})
    (story/reg-variant :story.id/v
      {:events [[:init]] :args {:b 2} :tags #{:dev}})
    (let [a (story/snapshot-identity :story.id/v {:substrate :reagent})
          b (story/snapshot-identity :story.id/v {:substrate :reagent})]
      (is (= (:content-hash a) (:content-hash b)))
      (is (= 8 (count (:content-hash a)))
          "8-char hex"))))

(deftest snapshot-identity-changes-with-args
  (testing "changing a variant's :args changes the hash"
    (story/reg-story :story.id-args
      {:component :app/v})
    (story/reg-variant :story.id-args/v {:args {:x 1} :events []})
    (let [h1 (-> (story/snapshot-identity :story.id-args/v) :content-hash)]
      (story/reg-variant :story.id-args/v {:args {:x 2} :events []})
      (let [h2 (-> (story/snapshot-identity :story.id-args/v) :content-hash)]
        (is (not= h1 h2))))))

(deftest snapshot-identity-changes-with-mode
  (testing "different active-modes produce different hashes"
    (story/reg-story :story.id-mode
      {:component :app/v :args {:theme :light}})
    (story/reg-mode :Mode.app/dark  {:args {:theme :dark}})
    (story/reg-mode :Mode.app/light {:args {:theme :light}})
    (story/reg-variant :story.id-mode/v {:events []})
    (let [hd (-> (story/snapshot-identity :story.id-mode/v
                                          {:active-modes [:Mode.app/dark]})
                 :content-hash)
          hl (-> (story/snapshot-identity :story.id-mode/v
                                          {:active-modes [:Mode.app/light]})
                 :content-hash)]
      (is (not= hd hl)))))

(deftest snapshot-identity-changes-with-substrate
  (testing "different substrate produces different hash"
    (story/reg-story :story.id-sub
      {:component :app/v})
    (story/reg-variant :story.id-sub/v {:events []})
    (let [hr (-> (story/snapshot-identity :story.id-sub/v {:substrate :reagent})
                 :content-hash)
          hu (-> (story/snapshot-identity :story.id-sub/v {:substrate :uix})
                 :content-hash)]
      (is (not= hr hu)))))

(deftest snapshot-identity-canonical-key-stable
  (testing "the canonical-form key :rf/snapshot-canonical-v1 is included"
    (let [canon (ident/canonical-form [:rf/snapshot-canonical-v1 :x])]
      (is (some? canon)))
    (is (= (ident/content-hash {:a 1 :b 2})
           (ident/content-hash {:b 2 :a 1}))
        "map key order doesn't affect the hash")))

;; ===========================================================================
;; LIFECYCLE STATE MACHINE
;; ===========================================================================

(deftest lifecycle-machine-registered
  (testing "the lifecycle machine is registered after install-canonical-vocabulary!"
    (is (some (set [loaders/lifecycle-machine-id]) (machines/machines)))))

(deftest lifecycle-transitions-pre-mount-to-ready
  (testing "the lifecycle progresses through every documented state"
    (story/reg-variant :story.life/v {:events [] :loaders []})
    (let [r       (story/resolve-decorators :story.life/v)]
      (frames/allocate! :story.life/v r)
      (is (= :mounting (loaders/current-state :story.life/v)))
      (loaders/start-loaders! :story.life/v)
      (is (= :loading (loaders/current-state :story.life/v)))
      (loaders/finish-loaders! :story.life/v)
      (is (= :ready (loaders/current-state :story.life/v)))
      (frames/destroy! :story.life/v))))

(deftest lifecycle-mirror-to-friendly-path
  (testing "the discrete state is mirrored to [:rf.story/lifecycle]"
    (story/reg-variant :story.mirror/v {:events []})
    (let [r (story/resolve-decorators :story.mirror/v)]
      (frames/allocate! :story.mirror/v r)
      (loaders/start-loaders! :story.mirror/v)
      (let [db (rf/get-frame-db :story.mirror/v)]
        (is (= :loading (:rf.story/lifecycle db))))
      (frames/destroy! :story.mirror/v))))

(deftest lifecycle-watcher-fires-on-transitions
  (testing "watch-variant callbacks see every transition"
    (story/reg-variant :story.watch/v {:events []})
    (let [transitions (atom [])
          unsubscribe (story/watch-variant
                        :story.watch/v
                        (fn [t] (swap! transitions conj t)))
          r           (story/resolve-decorators :story.watch/v)]
      (frames/allocate! :story.watch/v r)
      (loaders/start-loaders! :story.watch/v)
      (loaders/finish-loaders! :story.watch/v)
      (is (= [:pre-mount :mounting :loading]
             (mapv :from @transitions)))
      (is (= [:mounting :loading :ready]
             (mapv :to @transitions)))
      (unsubscribe)
      (frames/destroy! :story.watch/v))))

;; ===========================================================================
;; RUN-VARIANT END-TO-END
;; ===========================================================================

(deftest run-variant-basic
  (testing "run-variant returns a future of the result map"
    (rf/reg-event-db :test/inc
      (fn [db _] (update db :counter (fnil inc 0))))
    (story/reg-variant :story.run/v
      {:events [[:test/inc] [:test/inc]]})
    (let [fut (story/run-variant :story.run/v)
          r   (async/deref-blocking fut 5000)]
      (is (= :story.run/v        (:frame r)))
      (is (= :ready              (:lifecycle r)))
      (is (= 2                   (:counter (:app-db r))))
      (is (number?               (:elapsed-ms r)))
      (is (= :story.run/v        (-> r :snapshot :variant-id)))
      (is (string?               (-> r :snapshot :content-hash))))
    (story/destroy-variant! :story.run/v)))

(deftest run-variant-with-loaders-and-events
  (testing "run-variant drains loaders before events"
    (rf/reg-event-db :test/load
      (fn [db _] (assoc db :loaded? true)))
    (rf/reg-event-db :test/use
      (fn [db _]
        (assoc db :used-loaded? (boolean (:loaded? db)))))
    (story/reg-variant :story.flow/v
      {:loaders [[:test/load]]
       :events  [[:test/use]]})
    (let [r (async/deref-blocking (story/run-variant :story.flow/v) 5000)]
      (is (true? (-> r :app-db :loaded?)))
      (is (true? (-> r :app-db :used-loaded?))
          "events ran AFTER loaders"))
    (story/destroy-variant! :story.flow/v)))

(deftest run-variant-frame-setup-decorator
  (testing ":frame-setup decorators fire :init events before loaders"
    (rf/reg-event-db :test/mock-init
      (fn [db _] (assoc db :mock {:user "alice"})))
    (rf/reg-event-db :test/observe
      (fn [db _] (assoc db :observed-mock (:mock db))))
    (story/reg-decorator :mock-frame
      {:kind :frame-setup
       :init [[:test/mock-init]]})
    (story/reg-variant :story.fs/v
      {:decorators [[:mock-frame]]
       :events     [[:test/observe]]})
    (let [r (async/deref-blocking (story/run-variant :story.fs/v) 5000)]
      (is (= {:user "alice"} (-> r :app-db :observed-mock))
          ":init events ran before :events; observe saw the mock"))
    (story/destroy-variant! :story.fs/v)))

(deftest run-variant-unknown-variant
  (testing "run-variant of an unregistered variant produces an error result"
    (let [r (async/deref-blocking (story/run-variant :story.nope/x) 5000)]
      (is (= :error (:lifecycle r)))
      (is (= :rf.error/unknown-variant
             (-> r :assertions first :assertion))))))

(deftest reset-variant-tears-down-then-runs-fresh
  (testing "reset-variant produces a fresh app-db"
    (rf/reg-event-db :test/inc
      (fn [db _] (update db :counter (fnil inc 0))))
    (story/reg-variant :story.reset/v
      {:events [[:test/inc]]})
    (let [r1 (async/deref-blocking (story/run-variant :story.reset/v) 5000)
          r2 (async/deref-blocking (story/reset-variant :story.reset/v) 5000)]
      (is (= 1 (:counter (:app-db r1))))
      (is (= 1 (:counter (:app-db r2)))
          "reset gives a fresh frame; counter starts again at 0 then increments to 1"))
    (story/destroy-variant! :story.reset/v)))

;; ===========================================================================
;; FRAME-META INTROSPECTION
;; ===========================================================================

(deftest variant-frames-marked
  (testing "variant frames carry :rf/story? + :rf/variant on their config"
    (story/reg-variant :story.fm/v {:events []})
    (let [r (story/resolve-decorators :story.fm/v)]
      (frames/allocate! :story.fm/v r)
      (let [m (rf/frame-meta :story.fm/v)]
        (is (true?              (:rf/story? m)))
        (is (= :story.fm/v      (:rf/variant m)))
        (is (= :story           (:preset m))))
      (is (contains? (story/variant-frames) :story.fm/v))
      (is (true? (story/variant-frame? :story.fm/v)))
      (frames/destroy! :story.fm/v))))

;; ===========================================================================
;; ERROR PROJECTION
;; ===========================================================================

(deftest event-throwing-projects-as-assertion
  (testing "a thrown exception during :events lands in :assertions"
    (rf/reg-event-db :test/boom
      (fn [_ _] (throw (ex-info "bang" {:why :test}))))
    (story/reg-variant :story.err/v
      {:events [[:test/boom]]})
    (let [r (async/deref-blocking (story/run-variant :story.err/v) 5000)]
      ;; Phase-2 errors don't roll back the lifecycle; :ready is the
      ;; terminal state per IMPL-SPEC §5.5 — we record and continue.
      (is (some #(= :rf.error/exception (:assertion %)) (:assertions r))
          "an exception assertion was recorded")
      (is (some #(= :phase-2-events (:phase %)) (:assertions r))))
    (story/destroy-variant! :story.err/v)))

;; ===========================================================================
;; CONFIGURE!
;; ===========================================================================

(deftest configure-sets-global-args
  (testing "configure! writes the global-args layer"
    (story/configure! {:global-args {:theme :dark}})
    (is (= {:theme :dark} (config/get-global-args)))
    (story/reg-variant :story.cfg/v {:events []})
    (let [r (story/resolve-args :story.cfg/v)]
      (is (= :dark (:theme r))))))

(deftest configure-sets-editor-preference
  (testing "configure! writes the :editor preference (rf2-evgf5)"
    ;; Default is :vscode.
    (config/set-editor! :vscode)
    (is (= :vscode (config/get-editor)))
    (story/configure! {:editor :cursor})
    (is (= :cursor (config/get-editor)))
    (story/configure! {:editor :idea})
    (is (= :idea (config/get-editor)))
    (story/configure! {:editor {:custom "zed://file/{path}:{line}"}})
    (is (= {:custom "zed://file/{path}:{line}"} (config/get-editor)))
    ;; Reset for downstream tests.
    (config/set-editor! :vscode))
  (testing "configure! with no :editor leaves the preference untouched"
    (config/set-editor! :cursor)
    (story/configure! {:global-args {:theme :dark}})
    (is (= :cursor (config/get-editor)))
    (config/set-editor! :vscode)))

;; ===========================================================================
;; ASYNC ABSTRACTION
;; ===========================================================================

(deftest async-resolved-and-then
  (testing "async/resolved produces a complete future"
    (is (= 42 (async/deref-blocking (async/resolved 42) 1000)))))

(deftest async-then-chains
  (testing "async/then chains over a resolved promise"
    (is (= 43
           (async/deref-blocking
             (async/then (async/resolved 42) inc)
             1000)))))

(deftest async-rejected-catch
  (testing "async/catch* recovers a rejection"
    (let [recovered (async/catch* (async/rejected (ex-info "no" {}))
                                  (fn [_] :recovered))]
      (is (= :recovered (async/deref-blocking recovered 1000))))))

(deftest async-promise-resolver
  (testing "async/promise's resolver completes the future"
    (let [p (async/promise (fn [resolve] (resolve :ok)))]
      (is (= :ok (async/deref-blocking p 1000))))))

;; ===========================================================================
;; PUBLIC API STABILITY
;; ===========================================================================

(deftest public-api-surface
  (testing "every Stage 3 IMPL-SPEC §3.2 fn is present on the public ns"
    (is (fn? @#'story/run-variant))
    (is (fn? @#'story/reset-variant))
    (is (fn? @#'story/watch-variant))
    (is (fn? @#'story/snapshot-identity))
    (is (fn? @#'story/destroy-variant!))
    (is (fn? @#'story/configure!))
    (is (fn? @#'story/resolve-args))
    (is (fn? @#'story/resolve-decorators))
    (is (fn? @#'story/lifecycle-state))
    (is (fn? @#'story/variant-frames))
    (is (fn? @#'story/variant-frame?))))
