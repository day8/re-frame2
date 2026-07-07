(ns re-frame.capture-frame-test
  "Design-pinning tests for the frame-affordance redesign (rf2-kkut0.1):
  `capture-frame` (the keystone OPERATION BUNDLE, and per API-shrink #1
  rf2-csbbwu the ONE public HOLD primitive), `re-frame.frame/bind-fn` (the
  INTERNAL relocated `frame-bound-fn*` dynamic-rebinding primitive),
  `current-frame-id`, `app-db-value`, and the absence of the removed public
  names (`bound-fn`, `dispatcher`, `subscriber`, `get-frame-db`,
  `current-frame`, `frame-bound-fn`, `frame-bound-fn*`, `frame-value->id`).
  Per Spec 002 §capture-frame and `re-frame.core.cljc`.

  `capture-frame` exists to support async callbacks where the dynamic-var
  frame binding has already unwound: it captures the frame at CREATION
  time and its `:dispatch` / `:dispatch-sync` / `:subscribe` ops always
  target THAT frame — not whatever the caller's current frame is when an
  op later fires.

  These JVM tests use `with-frame :A` to set the dynamic var, capture the
  handle, then EXIT the with-frame scope before invoking its ops —
  proving the captured frame survives the unwind."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`.
  ;; Register it explicitly as an ordinary frame so the tests that
  ;; observe `:rf/default`'s app-db (and the explicit-id capture cases)
  ;; have a real frame; the no-arg capture forms still REQUIRE a carried
  ;; scope at capture time (covered by the *-requires-scope tests below).
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shape: a handle is an operation bundle ------------------------------

(deftest capture-frame-returns-operation-bundle
  (testing "(capture-frame frame-id) returns {:frame :dispatch :dispatch-sync :subscribe}"
    (rf/reg-frame :fh/shape {:doc "shape probe"})
    (let [h (rf/capture-frame :fh/shape)]
      (is (= :fh/shape (:frame h))
          ":frame is the captured frame id")
      (is (fn? (:dispatch h))      ":dispatch is a fn")
      (is (fn? (:dispatch-sync h)) ":dispatch-sync is a fn")
      (is (fn? (:subscribe h))     ":subscribe is a fn"))))

;; ---- captures at creation, not op-call time ------------------------------

(deftest capture-frame-captures-frame-at-creation
  (testing "(capture-frame) captures the active frame at CREATION; the bundle's
            :dispatch routes to THAT frame after the with-frame scope unwinds"
    (rf/reg-frame :fh/A {:doc "frame A — the capture target"})
    (rf/reg-event :fh/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; Capture inside :fh/A; fire OUTSIDE.
    (let [{:keys [dispatch]} (rf/with-frame :fh/A (rf/capture-frame))]
      ;; The dynamic-var binding has unwound. The captured op must still
      ;; route to :fh/A.
      (dispatch [:fh/inc])
      (dispatch [:fh/inc])
      (test-support/poll-until #(= 2 (:n (rf/app-db-value :fh/A)))
                               {:label "captured handle drains to :fh/A"})
      (is (= 2 (:n (rf/app-db-value :fh/A)))
          "the captured handle routed events to :fh/A after the scope unwound")
      (is (nil? (:n (rf/app-db-value :rf/default)))
          ":rf/default's app-db was NOT touched — capture is frame-faithful"))))

(deftest capture-frame-subscribe-captures-frame
  (testing "the bundle's :subscribe op resolves against the captured frame
            after the with-frame scope unwinds"
    (rf/reg-frame :fh/B {:doc "frame B — the subscribe target"})
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/dispatch-sync [:fh/seed :B-value] {:frame :fh/B})
    ;; Seed :rf/default explicitly (EP-0002: no ambient :rf/default floor)
    ;; so the assertion can prove the captured subscribe reads :fh/B, not
    ;; :rf/default's app-db.
    (rf/dispatch-sync [:fh/seed :default-value] {:frame :rf/default})
    (let [{:keys [subscribe]} (rf/with-frame :fh/B (rf/capture-frame))
          reaction            (subscribe [:fh/value])]
      (is (= :B-value @reaction)
          "captured :subscribe resolves against :fh/B's app-db, not :rf/default"))))

;; ---- per-call :frame CANNOT override the captured frame ------------------

(deftest capture-frame-locked-frame-cannot-be-overridden
  (testing "a per-call :frame in dispatch opts MUST NOT override the captured
            frame — the handle is LOCKED to one frame"
    (rf/reg-frame :fh/locked {:doc "the locked target"})
    (rf/reg-frame :fh/other  {:doc "the would-be override"})
    (rf/reg-event :fh/touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (let [{:keys [dispatch]} (rf/capture-frame :fh/locked)]
      ;; Attempt to redirect to :fh/other via a per-call :frame opt.
      (dispatch [:fh/touch] {:frame :fh/other})
      (test-support/poll-until #(:touched? (rf/app-db-value :fh/locked))
                               {:label "locked handle drains to :fh/locked"})
      (is (true? (:touched? (rf/app-db-value :fh/locked)))
          "the event landed in the CAPTURED frame :fh/locked")
      (is (nil? (:touched? (rf/app-db-value :fh/other)))
          "the per-call :frame :fh/other was IGNORED — the handle is locked"))))

;; ---- contract: (capture-frame) outside any scope RAISES (EP-0002) ---------
;;
;; rf2-jue6sp: the no-arg capture form captures ONLY when a real scope
;; exists at capture time. Capturing outside any with-frame / provider
;; raises :rf.error/no-frame-context — it does NOT capture :rf/default.

(deftest capture-frame-outside-with-frame-raises-no-frame-context
  (testing "(capture-frame) with no active scope raises :rf.error/no-frame-context
            instead of capturing :rf/default (rf2-jue6sp)"
    (is (nil? frame/*current-frame*) "no with-frame scope established")
    (let [e (try (rf/capture-frame) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "no-arg capture-frame outside any scope must throw")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data e)))
          ":rf.error/id is the carried-invariant absence error")
      (is (= :capture-frame (:operation (ex-data e)))
          ":operation attributes the failure to capture-frame"))))

(deftest capture-frame-explicit-frame-works-outside-scope
  (testing "(capture-frame frame-id) needs no scope — the explicit-id capture
            shape for async callbacks / tools / tests (rf2-jue6sp)"
    (rf/reg-event :fh/explicit-touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (is (nil? frame/*current-frame*) "no scope established")
    (let [h (rf/capture-frame :rf/default)]
      (is (= :rf/default (:frame h))
          "the explicit frame-id is captured verbatim")
      ((:dispatch h) [:fh/explicit-touch])
      (test-support/poll-until #(:touched? (rf/app-db-value :rf/default))
                               {:label "explicit handle drains to :rf/default"})
      (is (true? (:touched? (rf/app-db-value :rf/default)))
          "the explicit handle routes to the named frame"))))

;; ---- re-frame.frame/bind-fn — the INTERNAL relocated frame-bound-fn* -----
;;
;; API-shrink #1 (rf2-csbbwu) DELETES the public `frame-bound-fn` macro /
;; `frame-bound-fn*` fn from the facade — `capture-frame` (above) is the
;; ONE public HOLD primitive. Its genuinely-different dynamic-rebinding
;; semantics (re-establish `*current-frame*` around an ARBITRARY already-
;; held fn, not a pre-bound op bundle) survive internally as
;; `re-frame.frame/bind-fn` — the Codex caveat this suite pins.

(deftest bind-fn-rebinds-frame-after-scope-unwinds
  (testing "(frame/bind-fn frame-id f) wraps f so *current-frame* is
            re-established on each call, even after the surrounding
            with-frame scope has unwound"
    (rf/reg-frame :fbf/A {:doc "bind-fn capture target"})
    (rf/reg-event :fbf/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [cb (rf/with-frame :fbf/A
               (frame/bind-fn :fbf/A (fn [] (rf/dispatch [:fbf/inc]))))]
      (is (nil? frame/*current-frame*) "the with-frame scope has unwound")
      (cb)
      (test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/A)))
                               {:label "bind-fn drains to :fbf/A"})
      (is (= 1 (:n (rf/app-db-value :fbf/A)))
          "bind-fn re-established :fbf/A inside the body"))))

(deftest bind-fn-binds-explicit-frame-with-no-surrounding-scope
  (testing "(frame/bind-fn frame-id f) binds an explicit frame — no
            surrounding with-frame needed"
    (rf/reg-frame :fbf/C {:doc "bind-fn explicit target"})
    (rf/reg-event :fbf/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [cb (frame/bind-fn :fbf/C (fn [] (rf/dispatch [:fbf/inc])))]
      (is (nil? frame/*current-frame*) "no with-frame scope was ever entered")
      (cb)
      (test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/C)))
                               {:label "bind-fn drains to :fbf/C"})
      (is (= 1 (:n (rf/app-db-value :fbf/C)))
          "the explicit frame-id was re-established inside the body"))))

(deftest bind-fn-rebinds-around-an-arbitrary-fn-body
  (testing "bind-fn re-establishes the dynamic binding around an ARBITRARY
            already-held fn (e.g. one that itself calls current-frame-id) —
            the genuinely-different semantics from capture-frame's pre-bound
            op bundle that the Codex caveat required to survive internally"
    (rf/reg-frame :fbf/D {:doc "bind-fn current-frame-id probe"})
    (let [captured (rf/with-frame :fbf/D
                     (frame/bind-fn :fbf/D rf/current-frame-id))]
      (is (nil? frame/*current-frame*) "scope has unwound")
      (is (= :fbf/D (captured))
          "the wrapped arbitrary fn (current-frame-id) reads :fbf/D"))))

;; ---- renamed reads -------------------------------------------------------

(deftest current-frame-id-requires-scope
  (testing "(current-frame-id) reads the established scope's id inside a scope,
            and raises :rf.error/no-frame-context outside any scope — no
            :rf/default floor (rf2-jue6sp / EP-0002)"
    (rf/reg-frame :cfi/probe {:doc "probe"})
    ;; Inside a scope: the bound id.
    (is (= :cfi/probe (rf/with-frame :cfi/probe (rf/current-frame-id)))
        "inside with-frame: the bound id")
    (is (keyword? (rf/with-frame :cfi/probe (rf/current-frame-id)))
        "always a keyword inside a scope")
    ;; Outside any scope: the carried-invariant absence error.
    (is (nil? frame/*current-frame*) "no with-frame scope established")
    (let [e (try (rf/current-frame-id) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "current-frame-id outside any scope must throw")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data e)))
          "raises the carried-invariant absence error instead of :rf/default")
      (is (= :current-frame-id (:operation (ex-data e)))
          ":operation attributes the failure to current-frame-id"))))

(deftest app-db-value-returns-a-value
  (testing "(app-db-value frame-id) returns the app-db VALUE (a plain map), not a container"
    (rf/reg-frame :fdb/probe {:doc "probe"})
    (rf/reg-event :fdb/seed (fn [{:keys [db]} _] {:db {:k :v}}))
    (rf/dispatch-sync [:fdb/seed] {:frame :fdb/probe})
    (let [db (rf/app-db-value :fdb/probe)]
      (is (map? db) "app-db-value returns a plain map value")
      (is (= :v (:k db)) "the value reflects app-db state")
      (is (not (instance? clojure.lang.IDeref db))
          "it is a VALUE — not a deref-able container"))
    (is (nil? (rf/app-db-value :fdb/never-registered))
        "nil for an unregistered frame")))

;; ---- removed public names are absent -------------------------------------

(deftest removed-public-names-are-absent
  (testing "the deleted public names are NOT interned in re-frame.core"
    ;; `ns-interns` (NOT `ns-resolve`) — `ns-resolve` would follow the
    ;; clojure.core referral for `bound-fn` (we dropped the
    ;; `:refer-clojure :exclude [bound-fn]`, so clojure.core/bound-fn is
    ;; visible again). The contract is that re-frame.core no longer
    ;; INTERNS its own Var under these names.
    (let [interned (ns-interns 're-frame.core)]
      (doseq [sym '[bound-fn dispatcher subscriber get-frame-db current-frame
                    frame-bound-fn frame-bound-fn* frame-value->id]]
        (is (nil? (get interned sym))
            (str "re-frame.core/" sym " must be removed (DELETE, not deprecate)"))))))
