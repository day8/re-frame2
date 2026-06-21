(ns re-frame.core-api-additions-test
  "JVM tests for three core-API additions landed together
   (rf2-7whh / rf2-ewku / rf2-t38q):

   - `rf/with-frame` (pin form) and `rf/with-new-frame` (eval-bind-
     run-destroy form). Per Spec 002 §with-frame and `spec/API.md`
     row 74. Split per rf2-twoc5 (Mike-approved 2026-05-28).

   - `(rf/registrations kind pred-fn)` 2-arity filter. Per `spec/API.md`
     row 304 and Spec 001 §Public registrar query API.

   - `(rf/frame-ids ns-prefix)` 1-arity filter. Per `spec/API.md`
     row 308 and Spec 002 §The public registrar query API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.machines]
            [re-frame.image :as image]
            [re-frame.routing :as routing]
            ;; rf2-q4i9ko — replace-app-db! delegates to the epoch artefact's
            ;; replace-app-db! (synthetic-epoch recording); load it so the
            ;; mutator round-trip below resolves a live hook.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`.
  ;; Register it explicitly as an ordinary frame so the registrar-query
  ;; tests below (which assert `:rf/default` is enumerable) have a real
  ;; frame to find. `current-frame-id` outside a scope still RAISES — the
  ;; with-frame tests assert the carried-invariant absence path directly.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ===========================================================================
;; rf2-7whh / rf2-twoc5 — with-frame (pin) + with-new-frame (eval/destroy)
;; ===========================================================================

(deftest with-frame-bare-keyword
  (testing "(with-frame :keyword body) binds *current-frame* across body;
            outside the macro current-frame-id raises :rf.error/no-frame-context
            (EP-0002 — no :rf/default floor, rf2-jue6sp)"
    (rf/reg-frame :wf/alpha {:doc "alpha"})
    ;; Outside the macro: the carried-invariant absence error.
    (is (= :rf.error/no-frame-context
           (:rf.error/id (ex-data
                           (try (rf/current-frame-id) nil
                                (catch clojure.lang.ExceptionInfo e e)))))
        "outside the macro: current-frame-id raises rather than defaulting")
    (let [observed (rf/with-frame :wf/alpha (rf/current-frame-id))]
      (is (= :wf/alpha observed)
          "inside the macro body: resolves to the bound id"))
    ;; After the macro returns the dynamic binding has unwound — absence again.
    (is (= :rf.error/no-frame-context
           (:rf.error/id (ex-data
                           (try (rf/current-frame-id) nil
                                (catch clojure.lang.ExceptionInfo e e)))))
        "after the macro returns: the dynamic binding unwinds, absence raises")))

(deftest with-frame-multi-form-body
  (testing "(with-frame :keyword expr1 expr2 ...) evaluates all body forms,
            returns the last"
    (rf/reg-frame :wf/beta {:doc "beta"})
    (let [side (atom [])
          result (rf/with-frame :wf/beta
                   (swap! side conj :first)
                   (swap! side conj :second)
                   (rf/current-frame-id))]
      (is (= [:first :second] @side)
          "all body forms were evaluated in order")
      (is (= :wf/beta result)
          "the last form's value is returned"))))

(deftest with-frame-subscriber-captures-frame
  (testing "subscriber called inside (with-frame :k ...) captures :k"
    (rf/reg-frame :wf/left  {:doc "left"})
    (rf/reg-frame :wf/right {:doc "right"})
    (rf/reg-event :wf/seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub :wf/n (fn [db _] (:n db)))
    (rf/dispatch-sync [:wf/seed 7]  {:frame :wf/left})
    (rf/dispatch-sync [:wf/seed 99] {:frame :wf/right})
    (let [sl (rf/with-frame :wf/left  (:subscribe (rf/frame-handle)))
          sr (rf/with-frame :wf/right (:subscribe (rf/frame-handle)))]
      (is (= 7  @(sl [:wf/n])) ":wf/left subscriber sees :wf/left's :n")
      (is (= 99 @(sr [:wf/n])) ":wf/right subscriber sees :wf/right's :n"))))

(deftest with-new-frame-let-binding-create-use-destroy
  (testing "(with-new-frame [f (make-frame opts)] body) creates, binds, destroys"
    (let [captured-id (atom nil)
          observed-current (atom nil)]
      (rf/with-new-frame [f (frame/make-anon-frame-record! {:doc "ephemeral"})]
        (reset! captured-id f)
        (reset! observed-current (rf/current-frame-id))
        (is (= f (rf/current-frame-id))
            "inside the body: *current-frame* is the freshly-made id")
        (is (some? (rf/frame-meta f))
            "the frame is alive during the body"))
      (is (some? @captured-id)
          "the macro yielded a frame id to the body")
      (is (= @captured-id @observed-current)
          "the body saw the just-created id as current-frame")
      (is (nil? (rf/frame-meta @captured-id))
          "the frame was destroyed on body exit")
      ;; EP-0002 (rf2-jue6sp): after the body the dynamic scope has
      ;; unwound — current-frame-id raises rather than reporting :rf/default.
      (is (= :rf.error/no-frame-context
             (:rf.error/id (ex-data
                             (try (rf/current-frame-id) nil
                                  (catch clojure.lang.ExceptionInfo e e)))))
          "*current-frame* reverted after the body — absence raises"))))

(deftest with-new-frame-destroys-on-exception
  (testing "(with-new-frame [f ...] body) destroys the frame even when body throws"
    (let [captured-id (atom nil)]
      (try
        (rf/with-new-frame [f (frame/make-anon-frame-record! {:doc "ephemeral-throw"})]
          (reset! captured-id f)
          (throw (ex-info "boom" {:kind ::boom})))
        (catch Exception e
          (is (= ::boom (:kind (ex-data e)))
              "the body's exception propagates")))
      (is (some? @captured-id))
      (is (nil? (rf/frame-meta @captured-id))
          "the frame is destroyed even on exception"))))

(deftest with-new-frame-initial-events-fires-and-state-is-readable
  (testing "(with-new-frame [f (make-frame {:initial-events [[...]]})] body) —
            :initial-events fires before body, body sees the seeded state,
            destroy runs after"
    (rf/reg-event :wf/initialise (fn [{:keys [db]} _] {:db {:counter 42}}))
    (let [captured-db (atom nil)]
      (rf/with-new-frame [f (frame/make-anon-frame-record! {:initial-events [[:wf/initialise]]})]
        (reset! captured-db (rf/app-db-value f)))
      (is (= {:counter 42} @captured-db)
          "the body observed the on-create-seeded app-db"))))

(deftest with-frame-rejects-vector-argument
  (testing "(with-frame [...] body) raises at compile time — caller meant with-new-frame (rf2-twoc5)"
    ;; `macroexpand` wraps macro-side ex-infos in a Compiler$CompilerException;
    ;; call the expansion helper directly so we observe the structured throw
    ;; that fires at compile time.
    (require 're-frame.core-reg-view-macro)
    (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-frame)]
      (let [e (try (expand '[f (make-frame {})] '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "vector argument must throw")
        (is (= :rf.error/with-frame-vector-form (:rf.error/id (ex-data e)))
            ":rf.error/id is the canonical discriminator")
        (is (= :use-with-new-frame (:recovery (ex-data e)))
            ":recovery points the caller at with-new-frame")
        (is (re-find #"did you mean `with-new-frame`"
                     (:reason (ex-data e)))
            ":reason names the sibling macro"))
      (let [e (try (expand [] '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "empty vector must throw")
        (is (= :rf.error/with-frame-vector-form (:rf.error/id (ex-data e))))))))

(deftest with-new-frame-rejects-keyword-argument
  (testing "(with-new-frame :keyword body) raises at compile time — caller meant with-frame (rf2-twoc5)"
    (require 're-frame.core-reg-view-macro)
    (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-new-frame)]
      (let [e (try (expand :existing/id '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "keyword argument must throw")
        (is (= :rf.error/with-new-frame-keyword-form (:rf.error/id (ex-data e)))
            ":rf.error/id is the canonical discriminator")
        (is (= :use-with-frame (:recovery (ex-data e)))
            ":recovery points the caller at with-frame")
        (is (re-find #"did you mean `with-frame`"
                     (:reason (ex-data e)))
            ":reason names the sibling macro")))))

(deftest with-new-frame-rejects-vector-bindings-with-wrong-arity
  (testing "(with-new-frame [...] body) with wrong vector arity raises at compile time (rf2-4ymm0 CQ4)"
    (require 're-frame.core-reg-view-macro)
    (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-new-frame)]
      ;; Empty vector — easy typo to omit both sides.
      (let [e (try (expand [] '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "[] must throw")
        (is (= :rf.error/with-new-frame-bad-binding (:rf.error/id (ex-data e)))
            ":rf.error/id is the canonical discriminator")
        (is (re-find #"binding must be \[sym expr\]"
                     (:reason (ex-data e)))
            ":reason carries the structured explanation"))
      ;; 3-element vector — typo of `[sym expr]` with extra tail.
      (let [e (try (expand '[f g h] '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "[f g h] must throw")
        (is (= :rf.error/with-new-frame-bad-binding (:rf.error/id (ex-data e)))
            ":rf.error/id is the canonical discriminator")
        (is (re-find #"binding must be \[sym expr\]"
                     (:reason (ex-data e)))
            ":reason carries the structured explanation"))
      ;; Non-vector binding (e.g. a bare symbol) — same collapsed
      ;; non-`[sym expr]` throw (rf2-a29uqh: one throw for the whole class).
      (let [e (try (expand 'not-a-vector '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a non-vector binding must throw")
        (is (= :rf.error/with-new-frame-bad-binding (:rf.error/id (ex-data e)))
            ":rf.error/id is the canonical discriminator (shared with the wrong-arity case)")
        (is (= :fix-registration (:recovery (ex-data e)))
            ":recovery is :fix-registration")
        (is (= 'not-a-vector (:got (ex-data e)))
            ":extra {:got bindings} carries the offending value")
        (is (re-find #"binding must be \[sym expr\]"
                     (:reason (ex-data e)))
            ":reason carries the structured explanation")))))

;; ===========================================================================
;; rf2-ewku — (rf/registrations kind pred-fn) filter arity
;; ===========================================================================

(deftest registrations-1-arity-returns-full-map
  (testing "(registrations kind) returns the full {id metadata} map"
    (rf/reg-event :hf/one (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf/two (fn [{:keys [db]} _] {:db db}))
    (let [all (rf/registrations :event)]
      (is (contains? all :hf/one))
      (is (contains? all :hf/two)))))

(deftest registrations-2-arity-filters
  (testing "(registrations kind pred-fn) filters by (pred meta) — metadata-only"
    ;; Per Spec 001 §The query API + API.md the predicate sees the
    ;; metadata-map only. Id-namespace filters ride a user-tag the
    ;; caller stamps onto the slot (or compose via `filter` over the
    ;; returned map's keys).
    (rf/reg-event :hf.alpha/one (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf.alpha/two (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf.beta/one  (fn [{:keys [db]} _] {:db db}))
    (registrar/register! :event :hf.alpha/one
      (assoc (rf/handler-meta :event :hf.alpha/one) :rf/group :alpha))
    (registrar/register! :event :hf.alpha/two
      (assoc (rf/handler-meta :event :hf.alpha/two) :rf/group :alpha))
    (let [alpha-only (rf/registrations :event
                                  (fn [m] (= :alpha (:rf/group m))))]
      (is (= #{:hf.alpha/one :hf.alpha/two}
             (set (keys alpha-only)))
          "only :hf.alpha/* survives the predicate")
      (is (not (contains? alpha-only :hf.beta/one))
          ":hf.beta/one is filtered out"))))

(deftest registrations-2-arity-pred-receives-meta
  (testing "the pred-fn receives the metadata-map only"
    (rf/reg-event :hf/marked   (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf/unmarked (fn [{:keys [db]} _] {:db db}))
    ;; Re-register :hf/marked with extra meta on the slot.
    (registrar/register! :event :hf/marked
      (assoc (rf/handler-meta :event :hf/marked) :rf/marker? true))
    (let [marked (rf/registrations :event (fn [m] (:rf/marker? m)))]
      (is (= #{:hf/marked} (set (keys marked)))
          "only handlers whose metadata satisfies the pred survive"))))

(deftest registrations-2-arity-empty-result
  (testing "a predicate that matches nothing returns {}"
    (rf/reg-event :hf/one (fn [{:keys [db]} _] {:db db}))
    (is (= {} (rf/registrations :event (constantly false)))
        "no entries match → empty map")))

(deftest registrations-2-arity-unknown-kind-returns-empty
  (testing "a kind with no registrations returns {} regardless of pred"
    ;; `:rf2-hf/never-a-kind` is intentionally not a registrar kind —
    ;; per Spec 001 §Registry model the kinds set is closed; an
    ;; unrecognised kind queried via `registrations` returns `{}`.
    (is (= {} (rf/registrations :rf2-hf/never-a-kind (constantly true)))
        "kind has no entries → empty map even when pred is permissive")))

;; ===========================================================================
;; rf2-t38q — (rf/frame-ids ns-prefix) filter arity
;; ===========================================================================

(deftest frame-ids-0-arity-returns-full-set
  (testing "(frame-ids) returns the full set of registered ids"
    (rf/reg-frame :fi/alpha {})
    (rf/reg-frame :fi/beta  {})
    (let [all (rf/frame-ids)]
      (is (contains? all :fi/alpha))
      (is (contains? all :fi/beta))
      ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`;
      ;; the fixture registers it explicitly, and frame-ids enumerates it
      ;; like any other ordinary frame.
      (is (contains? all :rf/default)
          "the explicitly-registered :rf/default frame is enumerated"))))

(deftest frame-ids-1-arity-filters-by-prefix
  (testing "(frame-ids ns-prefix) returns ids whose keyword namespace
            starts with the prefix string"
    (rf/reg-frame :fi.story/login  {})
    (rf/reg-frame :fi.story/signup {})
    (rf/reg-frame :fi.test/login   {})
    (let [story-ids (rf/frame-ids "fi.story")]
      (is (= #{:fi.story/login :fi.story/signup} story-ids)
          "only :fi.story/* survives the prefix filter")
      (is (not (contains? story-ids :fi.test/login))
          ":fi.test/login is excluded"))))

(deftest frame-ids-1-arity-empty-result
  (testing "a prefix that matches no registered frame returns #{}"
    (rf/reg-frame :fi/alpha {})
    (is (= #{} (rf/frame-ids "no-such-ns"))
        "no namespaces start with this prefix → empty set")))

(deftest frame-ids-1-arity-excludes-destroyed
  (testing "destroyed frames do not appear in (frame-ids ns-prefix)"
    (rf/reg-frame :fi.zone/one {})
    (rf/reg-frame :fi.zone/two {})
    (rf/destroy-frame! :fi.zone/one)
    (let [zone-ids (rf/frame-ids "fi.zone")]
      (is (= #{:fi.zone/two} zone-ids)
          "destroyed :fi.zone/one is filtered out"))))

(deftest frame-ids-1-arity-broader-prefix
  (testing "a shorter prefix matches any longer matching namespace"
    (rf/reg-frame :wide.a/one {})
    (rf/reg-frame :wide.b/two {})
    (rf/reg-frame :elsewhere/one {})
    (let [wide-ids (rf/frame-ids "wide")]
      (is (contains? wide-ids :wide.a/one))
      (is (contains? wide-ids :wide.b/two))
      (is (not (contains? wide-ids :elsewhere/one))))))

;; ===========================================================================
;; rf2-q4i9ko — EP-0001 two-partition accessor/mutator helpers (bead 3)
;;
;; This bead introduces the SURFACE only (Mike ruling #1 / #10), no behavior
;; change. The physical runtime-db partition + one-container frame-state land
;; in rf2-adwcv6 (bead 5); the partition-aware tests come with beads 4-5.
;; These tests pin: the app-db read/replace round-trip, the frame-state
;; projection shape, runtime-db reading nil today, and the deferred-semantics
;; mutators throwing rather than silently dropping data.
;; ===========================================================================

(deftest app-db-value-and-replace-app-db-round-trip
  (testing "replace-app-db! then app-db-value round-trips the app-db partition"
    (rf/reg-frame :pp/round-trip {:doc "round-trip"})
    (rf/reg-event :pp/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed {:k 1}] {:frame :pp/round-trip})
    (is (= {:k 1} (rf/app-db-value :pp/round-trip))
        "app-db-value reads the seeded app-db")
    (is (true? (rf/replace-app-db! :pp/round-trip {:k 2 :j 9}))
        "replace-app-db! returns true on success (the renamed app-db state-injection surface)")
    (is (= {:k 2 :j 9} (rf/app-db-value :pp/round-trip))
        "app-db-value reads back exactly what replace-app-db! wrote")))

(deftest reset-app-db-resets-app-db-only-via-core-facade
  (testing "rf/reset-app-db! resets the app-db partition to {} while live
            runtime-db survives (EP-0001 rf2-tfepxu, Mike ruling #10 — the
            app-db sibling of whole-frame reset-frame!)"
    (rf/reg-frame :pp/reset-app {:doc "reset-app"})
    (rf/reg-event :pp/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed {:k 1 :cart {:items [9]}}] {:frame :pp/reset-app})
    (rf/replace-runtime-db! :pp/reset-app {:rf.runtime/machines {:m 1}})
    (is (= {:k 1 :cart {:items [9]}} (rf/app-db-value :pp/reset-app)))
    (is (= {:rf.runtime/machines {:m 1}} (rf/runtime-db-value :pp/reset-app)))

    (is (true? (rf/reset-app-db! :pp/reset-app))
        "reset-app-db! returns true on success")
    (is (= {} (rf/app-db-value :pp/reset-app))
        "app-db partition reset to {}")
    (is (= {:rf.runtime/machines {:m 1}} (rf/runtime-db-value :pp/reset-app))
        "runtime-db partition PRESERVED — reset-app-db! never touches it")))

(deftest app-db-value-unknown-frame-is-nil
  (testing "app-db-value returns nil for an unknown frame"
    (is (nil? (rf/app-db-value :pp/no-such-frame)))))

(deftest runtime-db-value-reads-real-partition
  (testing "runtime-db-value reads the real runtime-db partition (rf2-adwcv6, bead 5)"
    (rf/reg-frame :pp/runtime {:doc "runtime"})
    (is (= {} (rf/runtime-db-value :pp/runtime))
        "live frame: a fresh frame's runtime-db starts {} (the physical partition is real now)")
    (is (nil? (rf/runtime-db-value :pp/no-such-frame))
        "unknown frame: nil")
    (rf/replace-runtime-db! :pp/runtime {:rf.runtime/machines {:m 1}})
    (is (= {:rf.runtime/machines {:m 1}} (rf/runtime-db-value :pp/runtime))
        "runtime-db-value reads back exactly what replace-runtime-db! wrote")))

(deftest frame-state-value-projection-shape
  (testing "frame-state-value yields {:rf.db/app … :rf.db/runtime …} with the real runtime-db"
    (rf/reg-frame :pp/fs {:doc "frame-state"})
    (rf/reg-event :pp/seed-fs (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed-fs {:a 1}] {:frame :pp/fs})
    (is (= {:rf.db/app {:a 1} :rf.db/runtime {}}
           (rf/frame-state-value :pp/fs))
        "app-db slot carries the live app-db; runtime-db slot is the real (fresh {}) partition")
    (is (= {:a 1} (:rf.db/app (rf/frame-state-value :pp/fs)))
        "the :rf.db/app slot equals app-db-value")
    (is (= (rf/app-db-value :pp/fs) (:rf.db/app (rf/frame-state-value :pp/fs)))
        "frame-state :rf.db/app is the same value app-db-value returns")
    (is (= (rf/runtime-db-value :pp/fs) (:rf.db/runtime (rf/frame-state-value :pp/fs)))
        "frame-state :rf.db/runtime is the same value runtime-db-value returns")))

(deftest frame-state-value-unknown-frame-is-nil
  (testing "frame-state-value returns nil for an unknown frame"
    (is (nil? (rf/frame-state-value :pp/no-such-frame)))))

(deftest replace-runtime-db-writes-real-partition
  (testing "replace-runtime-db! writes the runtime-db partition only (rf2-adwcv6)"
    (rf/reg-frame :pp/rdb {:doc "runtime-mutate"})
    (rf/reg-event :pp/seed-app (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed-app {:app :data}] {:frame :pp/rdb})
    (rf/replace-runtime-db! :pp/rdb {:rf.runtime/machines {}})
    (is (= {:rf.runtime/machines {}} (rf/runtime-db-value :pp/rdb))
        "runtime-db partition replaced")
    (is (= {:app :data} (rf/app-db-value :pp/rdb))
        "app-db partition untouched by a runtime-db-only write")))

(deftest replace-frame-state-writes-both-partitions
  (testing "replace-frame-state! installs both partitions atomically (rf2-adwcv6)"
    (rf/reg-frame :pp/fsm {:doc "frame-state-mutate"})
    (rf/replace-frame-state! :pp/fsm {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}})
    (is (= {:a 7} (rf/app-db-value :pp/fsm))
        "app-db partition installed")
    (is (= {:rf.runtime/routing {:r 1}} (rf/runtime-db-value :pp/fsm))
        "runtime-db partition installed")
    (is (= {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}}
           (rf/frame-state-value :pp/fsm))
        "frame-state reads back the coherent both-partition snapshot")))

(deftest partition-reader-docstrings-describe-post-landing-contract
  (testing "the `runtime-db-value` / `frame-state-value` public docstrings
            describe the LIVE post-rf2-adwcv6 contract — no pre-landing
            placeholder wording (`nil` on a live frame / `until … lands`)
            may reappear in `re-frame.core` (rf2-rxnnxh)"
    ;; The facade is the REPL / tooling / agent-read surface; stale
    ;; pre-partition text taught a false contract (nil on live frames).
    ;; Pin both docstrings so the stale phrases can never silently return.
    (let [stale-phrases ["until then" "until the physical partition lands"
                         "reads nil even for a live frame"
                         "lands in" "is nil until"]]
      (doseq [sym ['runtime-db-value 'frame-state-value]]
        (let [doc (:doc (meta (ns-resolve 're-frame.core sym)))]
          (is (some? doc) (str sym " has a docstring"))
          (doseq [phrase stale-phrases]
            (is (not (str/includes? doc phrase))
                (str sym "'s docstring must not carry the stale pre-landing "
                     "phrase " (pr-str phrase)))))))))

;; ===========================================================================
;; rf2-xhdwms / rf2-jkdycj / rf2-sd6amv — API-consistency renames
;;
;; Adversarial contract tests: the NEW (post-rename) public-facade names must
;; resolve, and the OLD names must be GONE (pre-alpha — no back-compat alias).
;;   - restore-epoch          → restore-epoch!           (bang: mutates state)
;;   - reg-observability-sink! → register-observability-sink! (runtime install)
;;   - unregister-route!      → clear-route              (declarative clear-*)
;; ===========================================================================

(deftest renamed-facade-exports-resolve-old-names-gone
  (testing "the renamed re-frame.core facade exports resolve under their
            NEW names and the OLD names are absent (rf2-xhdwms / rf2-jkdycj /
            rf2-sd6amv)"
    ;; NEW names present on the façade. `restore-epoch!` (epoch) +
    ;; `register-observability-sink!` (observability) remain façade exports
    ;; (epoch is a documented late-bind façade exception; observability has no
    ;; owned public ns). `clear-route` was DEMOTED off the façade by rf2-wad2fl
    ;; (front-porch shrink) — it lives in `re-frame.routing` now (asserted
    ;; below + in `renamed-impl-exports-resolve-old-names-gone`).
    (doseq [sym ['restore-epoch! 'register-observability-sink!]]
      (is (some? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must resolve after the rename")))
    ;; OLD names gone — pre-alpha, no compatibility alias.
    (doseq [sym ['restore-epoch 'reg-observability-sink! 'unregister-route!]]
      (is (nil? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must be GONE (no back-compat alias)")))
    ;; rf2-wad2fl: `clear-route` is no longer a façade export — the routing
    ;; URL/lifecycle helpers moved to their owned namespace.
    (is (nil? (ns-resolve 're-frame.core 'clear-route))
        "re-frame.core/clear-route is GONE (demoted to re-frame.routing by rf2-wad2fl)")
    (require 're-frame.routing)
    (is (some? (ns-resolve 're-frame.routing 'clear-route))
        "clear-route is reached via re-frame.routing/clear-route")))

(deftest renamed-impl-exports-resolve-old-names-gone
  (testing "the impl-side artefact functions are renamed in lock-step with the
            facade (re-frame.epoch / re-frame.routing / re-frame.observability)"
    (require 're-frame.epoch :reload)
    (require 're-frame.routing :reload)
    (require 're-frame.observability)
    ;; NEW names present on the impl namespaces.
    (is (some? (ns-resolve 're-frame.epoch 'restore-epoch!))
        "re-frame.epoch/restore-epoch! resolves")
    (is (some? (ns-resolve 're-frame.routing 'clear-route))
        "re-frame.routing/clear-route resolves")
    (is (some? (ns-resolve 're-frame.observability 'register-observability-sink!))
        "re-frame.observability/register-observability-sink! resolves")
    ;; OLD names gone.
    (is (nil? (ns-resolve 're-frame.epoch 'restore-epoch))
        "re-frame.epoch/restore-epoch is GONE")
    (is (nil? (ns-resolve 're-frame.routing 'unregister-route!))
        "re-frame.routing/unregister-route! is GONE")
    (is (nil? (ns-resolve 're-frame.observability 'reg-observability-sink!))
        "re-frame.observability/reg-observability-sink! is GONE")))

(deftest restore-epoch-bang-round-trips-under-new-name
  (testing "(rf/restore-epoch! frame-id epoch-id) rewinds a frame to a recorded
            epoch — the renamed time-travel surface resolves a live hook and
            mutates state (rf2-xhdwms)"
    (rf/reg-frame :rn/epoch {:doc "rename-epoch"})
    (rf/reg-event :rn/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:rn/seed {:step 1}] {:frame :rn/epoch})
    (let [target (last (rf/epoch-history :rn/epoch))]
      (rf/dispatch-sync [:rn/seed {:step 2}] {:frame :rn/epoch})
      (is (= {:step 2} (rf/app-db-value :rn/epoch)) "advanced to step 2")
      (is (true? (rf/restore-epoch! :rn/epoch (:epoch-id target)))
          "restore-epoch! returns true rewinding to the recorded epoch")
      (is (= {:step 1} (rf/app-db-value :rn/epoch))
          "state rewound to the target epoch via the renamed surface"))))

(deftest clear-route-removes-route-under-new-name
  (testing "(rf/clear-route id) removes a registered route — the renamed
            declarative-removal surface (rf2-sd6amv)"
    (require 're-frame.routing :reload)
    (rf/reg-route :rn/route {} "/rn")
    (is (some? (routing/match-url "/rn")) "route registered + matchable")
    (routing/clear-route :rn/route)
    (is (nil? (routing/match-url "/rn"))
        "clear-route removed the route — it no longer matches")))

(deftest register-observability-sink-installs-under-new-name
  (testing "(rf/register-observability-sink! sink-id f) installs a sink and
            (rf/unregister-observability-sink! sink-id) is its inverse
            (rf2-jkdycj)"
    (is (= :rn.sinks/test
           (rf/register-observability-sink! :rn.sinks/test (fn [_record] nil)))
        "register-observability-sink! returns the sink-id on install")
    (is (nil? (rf/unregister-observability-sink! :rn.sinks/test))
        "unregister-observability-sink! is the confirmed inverse — returns nil")))

;; ===========================================================================
;; rf2-32siq3.17 — EP-0023 `rf/image` facade export
;; ===========================================================================

(deftest image-resolves-on-the-facade
  (testing "re-frame.core/image is the public `rf/image` constructor — it
            resolves to the re-frame.image/image var and builds an image value
            through the facade (EP-0023 §Image, §Public API)"
    ;; The facade var IS the constructor — `rf/image` and
    ;; `re-frame.image/image` are the identical fn.
    (is (identical? @#'rf/image @#'image/image)
        "rf/image aliases re-frame.image/image (same constructor var value)")
    ;; And it actually constructs the normalized, INERT image value through the
    ;; facade — a `rf/image` call is data, not registration.
    (let [img (rf/image {:id :docs.counter/v2
                         :include-ns ["docs.quickstart.counter.v2"]})]
      (is (= :docs.counter/v2 (:rf.image/id img))
          "the :id is normalized to the owner-qualified :rf.image/id slot")
      (is (= ["docs.quickstart.counter.v2"] (:rf.image/include-ns img))
          ":include-ns is carried as the glob-pattern vector")
      (is (= [] (:rf.image/inline img))
          "no :registrations → empty inline-descriptor vector")
      (is (= img (rf/image {:id :docs.counter/v2
                            :include-ns ["docs.quickstart.counter.v2"]}))
          "PURE: equal spec maps return equal image values"))))
