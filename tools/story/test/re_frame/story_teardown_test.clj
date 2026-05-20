(ns re-frame.story-teardown-test
  "JVM tests for the `:teardown` slot on `:frame-setup` decorators.

  Spec coverage (rf2-dg2uh):
    - `tools/story/spec/001-Authoring.md` §`:teardown` — symmetric
      counterpart of `:init`.
    - `tools/story/spec/002-Runtime.md`   §Loader teardown contract,
      §What the runtime guarantees.

  The `:teardown` slot is the lightweight cleanup path symmetric with
  `:init`. On `destroy-variant!` the runtime walks the resolved
  `:frame-setup` decorator stack IN REVERSE-DECLARATION ORDER and
  dispatch-syncs each decorator's `:teardown` events into the variant
  frame. Exceptions thrown by teardown events are caught and projected
  into the variant frame's `[:rf.story/assertions]` as
  `:rf.error/exception` records with `:phase :phase-teardown`. The walk
  never aborts `rf/destroy-frame!`.

  Test surface (minimum-viable contract):

  - **fires** — a single-decorator teardown actually runs on destroy.
  - **reverse-order composition** — innermost (variant-level) before
    outermost (story-level). Mirrors function-scope cleanup.
  - **exception caught** — a throwing teardown event does not propagate;
    `destroy-frame!` still completes.
  - **assertion record** — a thrown teardown lands as
    `:rf.error/exception` with `:phase :phase-teardown` in the variant
    frame's `[:rf.story/assertions]`.
  - **schema accepts the optional slot** — `reg-decorator` rejects a
    `:frame-setup` body that omits all of `:init` / `:app-db-patch` /
    `:teardown`, but accepts `{:teardown [...]}` standalone."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.frames     :as frames]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.schemas    :as schemas]
            [malli.core                :as m]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (reset! frames/stub-call-log {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; SCHEMA — `:teardown` is an optional slot on `:frame-setup` bodies
;; ===========================================================================

(deftest schema-accepts-frame-setup-with-only-teardown
  (testing "a :frame-setup decorator body carrying only :teardown is valid
            — the schema's at-least-one-of guard counts :teardown as a
            satisfying slot. Symmetric with :init / :app-db-patch."
    (is (m/validate schemas/Decorator
                    {:kind :frame-setup :teardown [[:noop]]}))
    (is (m/validate schemas/Decorator
                    {:kind :frame-setup :init [[:setup]] :teardown [[:cleanup]]}))
    (is (m/validate schemas/Decorator
                    {:kind :frame-setup :app-db-patch {:x 1} :teardown [[:cleanup]]}))))

(deftest schema-rejects-empty-frame-setup-body
  (testing "a :frame-setup body with NONE of :init / :app-db-patch /
            :teardown still fails — the at-least-one-of guard holds."
    (is (not (m/validate schemas/Decorator {:kind :frame-setup})))))

(deftest schema-rejects-non-vector-teardown
  (testing ":teardown must be a vector of event vectors — a bare keyword
            or a single event vector at the top level is invalid."
    (is (not (m/validate schemas/Decorator
                         {:kind :frame-setup :teardown :not-a-vector})))
    (is (not (m/validate schemas/Decorator
                         {:kind :frame-setup :teardown [:not-a-vector-of-vectors]})))))

;; ===========================================================================
;; FIRES — a single-decorator teardown actually runs at destroy
;; ===========================================================================

(deftest teardown-events-fire-on-destroy
  (testing ":teardown events dispatched at destroy reach the variant
            frame's event handlers"
    (let [fired (atom [])]
      (rf/reg-event-db :feed/close-socket
        (fn [db _] (swap! fired conj :feed/close-socket) db))
      (story/reg-decorator :feed/live-subscription
        {:kind     :frame-setup
         :init     [[:feed/noop-init]]
         :teardown [[:feed/close-socket]]})
      (rf/reg-event-db :feed/noop-init (fn [db _] db))
      (story/reg-variant :story.feed/teardown-fires
        {:decorators [[:feed/live-subscription]]
         :events     []})
      ;; Allocate + destroy. The single :frame-setup decorator's
      ;; :teardown event must fire on destroy.
      (async/deref-blocking
        (story/run-variant :story.feed/teardown-fires) 5000)
      (is (= [] @fired)
          "teardown has NOT fired yet — the variant is still live")
      (story/destroy-variant! :story.feed/teardown-fires)
      (is (= [:feed/close-socket] @fired)
          "teardown fired exactly once on destroy"))))

(deftest teardown-events-fire-in-declared-order-within-a-decorator
  (testing "within a single decorator's :teardown vector, events fire
            in declared order — symmetric with :init"
    (let [fired (atom [])]
      (rf/reg-event-db :step/one
        (fn [db _] (swap! fired conj :one) db))
      (rf/reg-event-db :step/two
        (fn [db _] (swap! fired conj :two) db))
      (rf/reg-event-db :step/three
        (fn [db _] (swap! fired conj :three) db))
      (story/reg-decorator :multi-step-teardown
        {:kind     :frame-setup
         :init     [[:step/noop]]
         :teardown [[:step/one] [:step/two] [:step/three]]})
      (rf/reg-event-db :step/noop (fn [db _] db))
      (story/reg-variant :story.feed/multi-step
        {:decorators [[:multi-step-teardown]]
         :events     []})
      (async/deref-blocking
        (story/run-variant :story.feed/multi-step) 5000)
      (story/destroy-variant! :story.feed/multi-step)
      (is (= [:one :two :three] @fired)
          "within one decorator, teardown events run in declared order"))))

;; ===========================================================================
;; REVERSE-ORDER COMPOSITION — innermost (variant) fires BEFORE outermost
;; (story). Per 001-Authoring.md §Composition order.
;; ===========================================================================

(deftest teardown-composes-in-reverse-declaration-order
  (testing "a stack of two :frame-setup decorators (one at story level,
            one at variant level) tears down innermost-first: the
            variant-level decorator's :teardown runs BEFORE the story-
            level decorator's :teardown. Mirrors function-scope cleanup."
    (let [fired (atom [])]
      (rf/reg-event-db :outer/cleanup
        (fn [db _] (swap! fired conj :outer) db))
      (rf/reg-event-db :inner/cleanup
        (fn [db _] (swap! fired conj :inner) db))
      (story/reg-decorator :outer-dec
        {:kind :frame-setup :init [[:outer/noop]] :teardown [[:outer/cleanup]]})
      (story/reg-decorator :inner-dec
        {:kind :frame-setup :init [[:inner/noop]] :teardown [[:inner/cleanup]]})
      (rf/reg-event-db :outer/noop (fn [db _] db))
      (rf/reg-event-db :inner/noop (fn [db _] db))
      (story/reg-story :story.teardown.order
        {:decorators [[:outer-dec]]})
      (story/reg-variant :story.teardown.order/v
        {:decorators [[:inner-dec]]
         :events     []})
      (async/deref-blocking
        (story/run-variant :story.teardown.order/v) 5000)
      (story/destroy-variant! :story.teardown.order/v)
      (is (= [:inner :outer] @fired)
          "reverse-declaration: innermost (variant-level :inner-dec)
           tears down BEFORE outermost (story-level :outer-dec).
           spec/002 §Loader teardown contract step 3."))))

(deftest teardown-composes-reverse-within-a-single-level
  (testing "two decorators declared at the SAME level (variant) still
            tear down in reverse-declaration order — the resolved
            :frame-setup vector reversed is the walk order"
    (let [fired (atom [])]
      (rf/reg-event-db :dec-a/cleanup
        (fn [db _] (swap! fired conj :a) db))
      (rf/reg-event-db :dec-b/cleanup
        (fn [db _] (swap! fired conj :b) db))
      (story/reg-decorator :dec-a
        {:kind :frame-setup :init [[:dec-a/noop]] :teardown [[:dec-a/cleanup]]})
      (story/reg-decorator :dec-b
        {:kind :frame-setup :init [[:dec-b/noop]] :teardown [[:dec-b/cleanup]]})
      (rf/reg-event-db :dec-a/noop (fn [db _] db))
      (rf/reg-event-db :dec-b/noop (fn [db _] db))
      (story/reg-variant :story.teardown.same-level/v
        {:decorators [[:dec-a] [:dec-b]]
         :events     []})
      (async/deref-blocking
        (story/run-variant :story.teardown.same-level/v) 5000)
      (story/destroy-variant! :story.teardown.same-level/v)
      (is (= [:b :a] @fired)
          "later-declared :dec-b tears down before earlier-declared :dec-a"))))

;; ===========================================================================
;; EXCEPTION HANDLING — throwing teardown caught; record projected
;; ===========================================================================

(deftest throwing-teardown-event-does-not-abort-destroy
  (testing "a teardown event that throws is caught by the runtime —
            destroy-frame! still runs. spec/002 §Loader teardown
            contract: teardown never aborts destroy-frame!"
    (rf/reg-event-db :boom/cleanup
      (fn [_ _] (throw (ex-info "teardown boom" {:why :test}))))
    (story/reg-decorator :boom-dec
      {:kind :frame-setup :init [[:boom/noop]] :teardown [[:boom/cleanup]]})
    (rf/reg-event-db :boom/noop (fn [db _] db))
    (story/reg-variant :story.teardown.boom/v
      {:decorators [[:boom-dec]]
       :events     []})
    (async/deref-blocking
      (story/run-variant :story.teardown.boom/v) 5000)
    ;; The destroy walk catches the exception. After destroy, the
    ;; variant frame is gone (rf/destroy-frame! ran).
    (is (nil? (story/destroy-variant! :story.teardown.boom/v))
        "destroy-variant! returns nil — exception caught, walk completed")
    (is (not (contains? (story/variant-frames) :story.teardown.boom/v))
        "the frame is destroyed despite the teardown throw")))

(deftest throwing-teardown-records-exception-assertion
  (testing "a teardown event that throws is projected into the variant
            frame's [:rf.story/assertions] as an :rf.error/exception
            record with :phase :phase-teardown. spec/002 §Error
            projection + §Loader teardown contract.

            Capture strategy: a probe decorator at the STORY level fires
            its :teardown LAST (reverse-order walk: variant-level boom
            first, story-level probe last). The probe reads
            [:rf.story/assertions] off the variant frame's app-db and
            copies it to a side-atom, so the test can inspect the record
            after destroy-frame! evicts the frame."
    (let [captured (atom nil)]
      (rf/reg-event-db :boom/cleanup
        (fn [_ _] (throw (ex-info "teardown boom" {:why :test}))))
      (rf/reg-event-db :boom/noop (fn [db _] db))
      (rf/reg-event-db ::probe-snapshot
        (fn [db _]
          (reset! captured (:rf.story/assertions db))
          db))
      (story/reg-decorator :boom-dec
        {:kind :frame-setup :init [[:boom/noop]] :teardown [[:boom/cleanup]]})
      (story/reg-decorator :probe-dec
        {:kind :frame-setup
         :init [[:boom/noop]]
         :teardown [[::probe-snapshot]]})
      ;; Story-level decorator runs OUTERMOST. Reverse-order at destroy
      ;; means variant-level :boom-dec fires first; story-level
      ;; :probe-dec fires last — AFTER the boom's exception record has
      ;; landed on [:rf.story/assertions].
      (story/reg-story :story.teardown.record
        {:decorators [[:probe-dec]]})
      (story/reg-variant :story.teardown.record/v
        {:decorators [[:boom-dec]]
         :events     []})
      (async/deref-blocking
        (story/run-variant :story.teardown.record/v) 5000)
      (story/destroy-variant! :story.teardown.record/v)
      (let [asserts @captured
            err     (first (filter #(= :rf.error/exception (:assertion %))
                                   asserts))]
        (is (some? err)
            "an :rf.error/exception record landed in [:rf.story/assertions]
             before destroy-frame! evicted the variant frame")
        (is (= :phase-teardown (:phase err))
            ":phase :phase-teardown carried on the record")
        (is (false? (:passed? err))
            ":passed? false — error records never pass")
        (is (= [:boom/cleanup] (:event err))
            ":event slot carries the throwing event vector")
        (is (= :story.teardown.record/v (:variant-id err))
            ":variant-id carries the variant id")
        (is (string? (:message (:error err)))
            ":error :message is the thrown exception's message")
        (is (= {:why :test} (:data (:error err)))
            ":error :data carries the ex-info data map")))))

;; ===========================================================================
;; UNAFFECTED SHAPES — decorators without :teardown still work
;; ===========================================================================

(deftest decorator-without-teardown-is-untouched
  (testing "a :frame-setup decorator that declares no :teardown still
            tears down cleanly — the walk is a no-op for that decorator"
    (rf/reg-event-db :seed/init
      (fn [db _] (assoc db :seeded? true)))
    (story/reg-decorator :seed-only
      {:kind :frame-setup :init [[:seed/init]]})
    (story/reg-variant :story.teardown.none/v
      {:decorators [[:seed-only]]
       :events     []})
    (async/deref-blocking
      (story/run-variant :story.teardown.none/v) 5000)
    (is (nil? (story/destroy-variant! :story.teardown.none/v))
        "destroy returns nil — no-op teardown walk completes cleanly")
    (is (not (contains? (story/variant-frames) :story.teardown.none/v))
        "frame still destroyed")))
