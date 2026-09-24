(ns re-frame.core-api-additions-test
  "JVM tests for three core-API surfaces:

   - `rf/with-frame` (pin form) and `rf/with-new-frame` (eval-bind-
     run-destroy form). Per Spec 002 §with-frame and `spec/API.md`
     row 74.

   - registrar-query FILTERING. `registrations` takes exactly one query
     map, and filtering is `filter` over the returned map; these tests pin
     that idiom. Per `spec/API.md` §Public registrar query API and Spec 001
     §The query API.

   - `(rf/frame-ids ns-prefix)` 1-arity filter. Per `spec/API.md`
     row 308 and Spec 002 §The public registrar query API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.machines]
            [re-frame.image :as rf.image]
            [re-frame.routing :as rf.routing]
            ;; `replace-frame-state!` delegates to the epoch artefact's
            ;; `replace-frame-state!` (synthetic-epoch recording) through the
            ;; `:epoch/replace-frame-state!` hook; load it so the mutator
            ;; round-trip below resolves a live hook.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.flows/reset-last-inputs!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default`.
  ;; Register it explicitly as an ordinary frame so the registrar-query
  ;; tests below (which assert `:rf/default` is enumerable) have a real
  ;; frame to find. `current-frame-id` outside a scope still RAISES — the
  ;; with-frame tests assert the carried-invariant absence path directly.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ===========================================================================
;; with-frame (pin) + with-new-frame (eval/destroy)
;; ===========================================================================

(deftest with-frame-bare-keyword
  (testing "(with-frame :keyword body) binds *current-frame* across body;
            outside the macro current-frame-id raises :rf.error/no-frame-context
            (EP-0002 — no :rf/default floor)"
    (rf/make-frame {:id :wf/alpha :doc "alpha"})
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
    (rf/make-frame {:id :wf/beta :doc "beta"})
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
    (rf/make-frame {:id :wf/left :doc "left"})
    (rf/make-frame {:id :wf/right :doc "right"})
    (rf/reg-event :wf/seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-sub :wf/n (fn [db _] (:n db)))
    (rf/dispatch-sync [:wf/seed 7]  {:frame :wf/left})
    (rf/dispatch-sync [:wf/seed 99] {:frame :wf/right})
    (let [sl (rf/with-frame :wf/left  (:subscribe (rf/capture-frame)))
          sr (rf/with-frame :wf/right (:subscribe (rf/capture-frame)))]
      (is (= 7  @(sl [:wf/n])) ":wf/left subscriber sees :wf/left's :n")
      (is (= 99 @(sr [:wf/n])) ":wf/right subscriber sees :wf/right's :n"))))

(deftest with-new-frame-let-binding-create-use-destroy
  (testing "(with-new-frame [f (make-frame opts)] body) creates, binds, destroys"
    (let [captured-id (atom nil)
          observed-current (atom nil)]
      (rf/with-new-frame [f (rf.frame/make-anon-frame-record! {:doc "ephemeral"})]
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
      ;; EP-0002: after the body the dynamic scope has
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
        (rf/with-new-frame [f (rf.frame/make-anon-frame-record! {:doc "ephemeral-throw"})]
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
      (rf/with-new-frame [f (rf.frame/make-anon-frame-record! {:initial-events [[:wf/initialise]]})]
        (reset! captured-db (rf/app-db-value f)))
      (is (= {:counter 42} @captured-db)
          "the body observed the on-create-seeded app-db"))))

(deftest with-frame-rejects-vector-argument
  (testing "(with-frame [...] body) raises at compile time — caller meant with-new-frame"
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
  (testing "(with-new-frame :keyword body) raises at compile time — caller meant with-frame"
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
  (testing "(with-new-frame [...] body) with wrong vector arity raises at compile time"
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
      ;; non-`[sym expr]` throw (one throw for the whole class).
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
;; registrar-query filtering (`filter` over the result, not an arity)
;; ===========================================================================

(deftest registrations-1-arity-returns-full-map
  (testing "(registrations kind) returns the full {id metadata} map"
    (rf/reg-event :hf/one (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf/two (fn [{:keys [db]} _] {:db db}))
    (let [all (rf/registrations {:source :store :kind :event})]
      (is (contains? all :hf/one))
      (is (contains? all :hf/two)))))

(deftest registrations-filter-over-result
  (testing "filtering is `filter` over the returned map, keyed on metadata"
    ;; Per Spec 001 §The query API + API.md the predicate sees the
    ;; metadata-map only. Id-namespace filters ride a user-tag the
    ;; caller stamps onto the slot (or compose via `filter` over the
    ;; returned map's keys).
    (rf/reg-event :hf.alpha/one (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf.alpha/two (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf.beta/one  (fn [{:keys [db]} _] {:db db}))
    (rf.registrar/register! :event :hf.alpha/one
      (assoc (rf/handler-meta {:source :store :kind :event :id :hf.alpha/one}) :rf/group :alpha))
    (rf.registrar/register! :event :hf.alpha/two
      (assoc (rf/handler-meta {:source :store :kind :event :id :hf.alpha/two}) :rf/group :alpha))
    (let [alpha-only (into {}
                           (filter (fn [[_id m]] (= :alpha (:rf/group m))))
                           (rf/registrations {:source :store :kind :event}))]
      (is (= #{:hf.alpha/one :hf.alpha/two}
             (set (keys alpha-only)))
          "only :hf.alpha/* survives the predicate")
      (is (not (contains? alpha-only :hf.beta/one))
          ":hf.beta/one is filtered out"))))

(deftest registrations-filter-sees-the-metadata-map
  (testing "the filter predicate sees the [id metadata] pair"
    (rf/reg-event :hf/marked   (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :hf/unmarked (fn [{:keys [db]} _] {:db db}))
    ;; Re-register :hf/marked with extra meta on the slot.
    (rf.registrar/register! :event :hf/marked
      (assoc (rf/handler-meta {:source :store :kind :event :id :hf/marked}) :rf/marker? true))
    (let [marked (into {}
                       (filter (fn [[_id m]] (:rf/marker? m)))
                       (rf/registrations {:source :store :kind :event}))]
      (is (= #{:hf/marked} (set (keys marked)))
          "only handlers whose metadata satisfies the pred survive"))))

(deftest registrations-filter-empty-result
  (testing "a predicate that matches nothing returns {}"
    (rf/reg-event :hf/one (fn [{:keys [db]} _] {:db db}))
    (is (= {} (into {}
                   (filter (constantly false))
                   (rf/registrations {:source :store :kind :event})))
        "no entries match → empty map")))

(deftest registrations-unknown-kind-throws
  (testing "an unknown kind THROWS rather than returning an authoritative {}"
    ;; `:rf2-hf/never-a-kind` is not a registrar kind; answering `{}` for it
    ;; would be indistinguishable from "this kind exists and is empty". The
    ;; query path throws the registrar's own catalogued id, with `where`
    ;; naming the QUERY fn.
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                  (rf/registrations {:source :store :kind :rf2-hf/never-a-kind})))]
      (is (= :rf.error/unknown-registry-kind (:rf.error/id (ex-data e)))
          "the catalogued id names the closed kind set"))))

;; ===========================================================================
;; (rf/frame-ids ns-prefix) filter arity
;; ===========================================================================

(deftest frame-ids-0-arity-returns-full-set
  (testing "(frame-ids) returns the full set of registered ids"
    (rf/make-frame {:id :fi/alpha})
    (rf/make-frame {:id :fi/beta})
    (let [all (rf/frame-ids)]
      (is (contains? all :fi/alpha))
      (is (contains? all :fi/beta))
      ;; `init!` does not synthesise `:rf/default`;
      ;; the fixture registers it explicitly, and frame-ids enumerates it
      ;; like any other ordinary frame.
      (is (contains? all :rf/default)
          "the explicitly-registered :rf/default frame is enumerated"))))

(deftest frame-ids-1-arity-filters-by-prefix
  (testing "(frame-ids ns-prefix) returns ids whose keyword namespace
            starts with the prefix string"
    (rf/make-frame {:id :fi.story/login})
    (rf/make-frame {:id :fi.story/signup})
    (rf/make-frame {:id :fi.test/login})
    (let [story-ids (rf/frame-ids "fi.story")]
      (is (= #{:fi.story/login :fi.story/signup} story-ids)
          "only :fi.story/* survives the prefix filter")
      (is (not (contains? story-ids :fi.test/login))
          ":fi.test/login is excluded"))))

(deftest frame-ids-1-arity-empty-result
  (testing "a prefix that matches no registered frame returns #{}"
    (rf/make-frame {:id :fi/alpha})
    (is (= #{} (rf/frame-ids "no-such-ns"))
        "no namespaces start with this prefix → empty set")))

(deftest frame-ids-1-arity-excludes-destroyed
  (testing "destroyed frames do not appear in (frame-ids ns-prefix)"
    (rf/make-frame {:id :fi.zone/one})
    (rf/make-frame {:id :fi.zone/two})
    (rf/destroy-frame! :fi.zone/one)
    (let [zone-ids (rf/frame-ids "fi.zone")]
      (is (= #{:fi.zone/two} zone-ids)
          "destroyed :fi.zone/one is filtered out"))))

(deftest frame-ids-1-arity-broader-prefix
  (testing "a shorter prefix matches any longer matching namespace"
    (rf/make-frame {:id :wide.a/one})
    (rf/make-frame {:id :wide.b/two})
    (rf/make-frame {:id :elsewhere/one})
    (let [wide-ids (rf/frame-ids "wide")]
      (is (contains? wide-ids :wide.a/one))
      (is (contains? wide-ids :wide.b/two))
      (is (not (contains? wide-ids :elsewhere/one))))))

;; ===========================================================================
;; The frame-state read/write surface
;;
;; `replace-frame-state!` is the ONE frame-state write surface — a partial
;; map naming the partitions it replaces — and `frame-state-value` /
;; `app-db-value` are the reads. These tests
;; pin: the app-db-only / runtime-db-only / both-partition partial-patch
;; contracts (present key replaces, absent key preserves), the frame-state
;; projection shape, and the reject-bad-keys contract.
;; ===========================================================================

(deftest app-db-value-and-replace-frame-state-app-only-round-trip
  (testing "replace-frame-state! with an app-only map then app-db-value
            round-trips the app-db partition"
    (rf/make-frame {:id :pp/round-trip :doc "round-trip"})
    (rf/reg-event :pp/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed {:k 1}] {:frame :pp/round-trip})
    (is (= {:k 1} (rf/app-db-value :pp/round-trip))
        "app-db-value reads the seeded app-db")
    (is (true? (rf/replace-frame-state! :pp/round-trip {:rf.db/app {:k 2 :j 9}}))
        "replace-frame-state! returns true on success (app-db state injection is a one-key partial map)")
    (is (= {:k 2 :j 9} (rf/app-db-value :pp/round-trip))
        "app-db-value reads back exactly what replace-frame-state! wrote")))

(deftest replace-frame-state-app-reset-preserves-runtime-via-core-facade
  (testing "rf/replace-frame-state! with {:rf.db/app {}} resets the app-db
            partition to {} while live runtime-db survives — the ABSENT
            :rf.db/runtime key is PRESERVED, not nilled (EP-0001; an app-db
            reset is a one-key partial map)"
    (rf/make-frame {:id :pp/reset-app :doc "reset-app"})
    (rf/reg-event :pp/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed {:k 1 :cart {:items [9]}}] {:frame :pp/reset-app})
    (rf/replace-frame-state! :pp/reset-app {:rf.db/runtime {:rf.runtime/machines {:m 1}}})
    (is (= {:k 1 :cart {:items [9]}} (rf/app-db-value :pp/reset-app)))
    (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :pp/reset-app))))

    (is (true? (rf/replace-frame-state! :pp/reset-app {:rf.db/app {}}))
        "replace-frame-state! returns true on success")
    (is (= {} (rf/app-db-value :pp/reset-app))
        "app-db partition reset to {}")
    (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :pp/reset-app)))
        "runtime-db partition PRESERVED — the absent :rf.db/runtime key was never touched")))

(deftest app-db-value-unknown-frame-is-nil
  (testing "app-db-value returns nil for an unknown frame"
    (is (nil? (rf/app-db-value :pp/no-such-frame)))))

(deftest frame-state-value-projection-shape
  (testing "frame-state-value yields {:rf.db/app … :rf.db/runtime …} with the real runtime-db"
    (rf/make-frame {:id :pp/fs :doc "frame-state"})
    (rf/reg-event :pp/seed-fs (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed-fs {:a 1}] {:frame :pp/fs})
    (is (= {:rf.db/app {:a 1} :rf.db/runtime {}}
           (rf/frame-state-value :pp/fs))
        "app-db slot carries the live app-db; runtime-db slot is the real (fresh {}) partition")
    (is (= {:a 1} (:rf.db/app (rf/frame-state-value :pp/fs)))
        "the :rf.db/app slot equals app-db-value")
    (is (= (rf/app-db-value :pp/fs) (:rf.db/app (rf/frame-state-value :pp/fs)))
        "frame-state :rf.db/app is the same value app-db-value returns")))

(deftest frame-state-value-unknown-frame-is-nil
  (testing "frame-state-value returns nil for an unknown frame"
    (is (nil? (rf/frame-state-value :pp/no-such-frame)))))

(deftest replace-frame-state-runtime-only-preserves-app-via-core-facade
  (testing "replace-frame-state! with a runtime-only map writes ONLY the
            runtime-db partition — the absent :rf.db/app key is PRESERVED"
    (rf/make-frame {:id :pp/rdb :doc "runtime-mutate"})
    (rf/reg-event :pp/seed-app (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:pp/seed-app {:app :data}] {:frame :pp/rdb})
    (rf/replace-frame-state! :pp/rdb {:rf.db/runtime {:rf.runtime/machines {}}})
    (is (= {:rf.runtime/machines {}} (:rf.db/runtime (rf/frame-state-value :pp/rdb)))
        "runtime-db partition replaced")
    (is (= {:app :data} (rf/app-db-value :pp/rdb))
        "app-db partition untouched — the absent :rf.db/app key was never touched")))

(deftest replace-frame-state-writes-both-partitions
  (testing "replace-frame-state! with a both-partition map installs both
            atomically"
    (rf/make-frame {:id :pp/fsm :doc "frame-state-mutate"})
    (rf/replace-frame-state! :pp/fsm {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}})
    (is (= {:a 7} (rf/app-db-value :pp/fsm))
        "app-db partition installed")
    (is (= {:rf.runtime/routing {:r 1}} (:rf.db/runtime (rf/frame-state-value :pp/fsm)))
        "runtime-db partition installed")
    (is (= {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}}
           (rf/frame-state-value :pp/fsm))
        "frame-state reads back the coherent both-partition snapshot")))

(deftest replace-frame-state-rejects-no-recognized-keys
  (testing "replace-frame-state! rejects a map carrying no recognized
            partition key — an empty map, or a map of only unrelated keys
            — with :rf.error/replace-frame-state-bad-keys rather than
            silently no-opping while returning true"
    (rf/make-frame {:id :pp/bad-keys-empty :doc "bad-keys-empty"})
    (is (false? (rf/replace-frame-state! :pp/bad-keys-empty {}))
        "an empty map carries no recognized partition key — rejected")
    (is (false? (rf/replace-frame-state! :pp/bad-keys-empty {:unrelated 1}))
        "a map of only unrelated keys carries no recognized partition key — rejected")))

(deftest replace-frame-state-rejects-unknown-keys
  (testing "replace-frame-state! rejects a map carrying an unrecognized key
            alongside a recognized one — a typo'd partition key (e.g.
            :rf.db/apps) is never silently ignored"
    (rf/make-frame {:id :pp/bad-keys-typo :doc "bad-keys-typo"})
    (is (false? (rf/replace-frame-state! :pp/bad-keys-typo {:rf.db/app {:k 1} :rf.db/apps {:k 2}}))
        "an unrecognized key alongside a recognized one is rejected")
    (is (= {} (rf/app-db-value :pp/bad-keys-typo))
        "the rejected call did not install anything — app-db stays at the fresh-frame {} value")))

(deftest partition-reader-docstrings-describe-post-landing-contract
  (testing "the `frame-state-value` public docstring describes the LIVE
            contract — no placeholder wording (`nil` on a live frame /
            `until … lands`) may appear in `re-frame.core`"
    ;; The facade is the REPL / tooling / agent-read surface, so placeholder
    ;; text claiming nil on live frames would teach a false contract. Pin the
    ;; docstring so those phrases cannot silently appear.
    (let [stale-phrases ["until then" "until the physical partition lands"
                         "reads nil even for a live frame"
                         "lands in" "is nil until"]]
      (doseq [sym ['frame-state-value]]
        (let [doc (:doc (meta (ns-resolve 're-frame.core sym)))]
          (is (some? doc) (str sym " has a docstring"))
          (doseq [phrase stale-phrases]
            (is (not (str/includes? doc phrase))
                (str sym "'s docstring must not carry the placeholder "
                     "phrase " (pr-str phrase)))))))))

;; ===========================================================================
;; API-consistency naming
;;
;; Adversarial contract tests: the public-facade names below must resolve,
;; and the rejected spellings beside them must NOT (no alias).
;;   - restore-epoch!               not restore-epoch           (bang: mutates state)
;;   - register-observability-sink! not reg-observability-sink! (runtime install)
;;   - (rf/clear :route id)         not unregister-route!       (declarative clear)
;; ===========================================================================

(deftest renamed-facade-exports-resolve-old-names-gone
  (testing "the re-frame.core facade exports resolve under their bang /
            register- names and the rejected spellings are absent"
    ;; Names present on the façade. `restore-epoch!` (epoch) +
    ;; `register-observability-sink!` (observability) are façade exports
    ;; (epoch is a documented late-bind façade exception; observability has no
    ;; owned public ns). There is no public `clear-route` (asserted below + in
    ;; `renamed-impl-exports-resolve-old-names-gone`).
    (doseq [sym ['restore-epoch! 'register-observability-sink!]]
      (is (some? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must resolve")))
    ;; The rejected spellings do not resolve — no compatibility alias.
    (doseq [sym ['restore-epoch 'reg-observability-sink! 'unregister-route!]]
      (is (nil? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must not resolve (no alias)")))
    ;; There is no public `clear-route` NAME at all — the registrar inverse is
    ;; the one kind-keyed `(rf/clear :route id)`, which routes through the
    ;; `:routing/clear-route` late-bind hook so the removal emits
    ;; `:rf.route/cleared`.
    (is (nil? (ns-resolve 're-frame.core 'clear-route))
        "re-frame.core/clear-route does not resolve")
    (require 're-frame.routing)
    (is (nil? (ns-resolve 're-frame.routing 'clear-route))
        "re-frame.routing/clear-route does not resolve either")
    (is (some? (ns-resolve 're-frame.core 'clear))
        "rf/clear is the one public registrar inverse")))

;; ===========================================================================
;; Frame-state io
;;
;; Adversarial contract test: `replace-frame-state!` is the ONE frame-state
;; write surface, and the retired per-partition names do not resolve on the
;; façade (no alias).
;; ===========================================================================

(deftest frame-state-io-shrink-old-names-gone
  (testing "the retired frame-state names do not resolve on
            re-frame.core — replace-frame-state! is the ONE frame-state
            write surface, and frame-state-value / app-db-value are the
            reads"
    (doseq [sym ['snapshot-of 'reset-app-db! 'replace-runtime-db!
                 'replace-app-db! 'runtime-db-value]]
      (is (nil? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must not resolve (no alias)")))
    (doseq [sym ['replace-frame-state! 'frame-state-value 'app-db-value]]
      (is (some? (ns-resolve 're-frame.core sym))
          (str "re-frame.core/" sym " must resolve")))))

(deftest renamed-impl-exports-resolve-old-names-gone
  (testing "the impl-side artefact functions carry the same names as the
            facade (re-frame.epoch / re-frame.routing / re-frame.observability)"
    (require 're-frame.epoch :reload)
    (require 're-frame.routing :reload)
    (require 're-frame.observability)
    ;; Names present on the impl namespaces.
    (is (some? (ns-resolve 're-frame.epoch 'restore-epoch!))
        "re-frame.epoch/restore-epoch! resolves")
    ;; `clear-route` is not a public name on the routing
    ;; artefact either — `(rf/clear :route id)` reaches
    ;; `re-frame.routing.registry/clear-route` through the late-bind hook.
    (is (nil? (ns-resolve 're-frame.routing 'clear-route))
        "re-frame.routing/clear-route does not resolve")
    (is (some? (ns-resolve 're-frame.observability 'register-observability-sink!))
        "re-frame.observability/register-observability-sink! resolves")
    ;; Rejected spellings do not resolve.
    (is (nil? (ns-resolve 're-frame.epoch 'restore-epoch))
        "re-frame.epoch/restore-epoch does not resolve")
    (is (nil? (ns-resolve 're-frame.routing 'unregister-route!))
        "re-frame.routing/unregister-route! does not resolve")
    (is (nil? (ns-resolve 're-frame.observability 'reg-observability-sink!))
        "re-frame.observability/reg-observability-sink! does not resolve")))

(deftest restore-epoch-bang-round-trips-under-new-name
  (testing "(rf/restore-epoch! frame-id epoch-id) rewinds a frame to a recorded
            epoch — the time-travel surface resolves a live hook and
            mutates state"
    (rf/make-frame {:id :rn/epoch :doc "rename-epoch"})
    (rf/reg-event :rn/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:rn/seed {:step 1}] {:frame :rn/epoch})
    (let [target (last (rf/epoch-history :rn/epoch))]
      (rf/dispatch-sync [:rn/seed {:step 2}] {:frame :rn/epoch})
      (is (= {:step 2} (rf/app-db-value :rn/epoch)) "advanced to step 2")
      (is (true? (rf/restore-epoch! :rn/epoch (:epoch-id target)))
          "restore-epoch! returns true rewinding to the recorded epoch")
      (is (= {:step 1} (rf/app-db-value :rn/epoch))
          "state rewound to the target epoch via restore-epoch!"))))

(deftest clear-route-removes-route-under-new-name
  (testing "(rf/clear :route id) removes a registered route — the
            declarative-removal surface"
    (require 're-frame.routing :reload)
    (rf/reg-route :rn/route {} "/rn")
    (is (some? (rf.routing/match-url "/rn")) "route registered + matchable")
    (rf/clear :route :rn/route)
    (is (nil? (rf.routing/match-url "/rn"))
        "clear-route removed the route — it no longer matches")))

(deftest register-observability-sink-installs-under-new-name
  (testing "(rf/register-observability-sink! sink-id f) installs a sink and
            (rf/unregister-observability-sink! sink-id) is its inverse"
    (is (= :rn.sinks/test
           (rf/register-observability-sink! :rn.sinks/test (fn [_record] nil)))
        "register-observability-sink! returns the sink-id on install")
    (is (nil? (rf/unregister-observability-sink! :rn.sinks/test))
        "unregister-observability-sink! is the confirmed inverse — returns nil")))

;; ===========================================================================
;; EP-0023 `rf/image` facade export
;; ===========================================================================

(deftest image-resolves-on-the-facade
  (testing "re-frame.core/image is the public `rf/image` constructor — a MACRO
            that gates literal inline `:doc` bytes at the authoring
            seam then delegates to the re-frame.image/image value fn, and builds
            an image value through the facade (EP-0023 §Image, §Public API)"
    ;; The facade `rf/image` is a MACRO: `rf/image` is value-oriented,
    ;; but a LITERAL inline `:registrations` metadata map `{:doc "…"}` is built at
    ;; the call site before any runtime normalization runs, and per Spec 001
    ;; §Production elision contract a runtime strip cannot DCE those call-site
    ;; string bytes. The macro gates each literal doc-bearing inline metadata slot
    ;; behind `(if interop/debug-enabled? <full> <stripped>)` then delegates to
    ;; the plain value constructor `re-frame.image/image`.
    (is (:macro (meta #'rf/image))
        "rf/image is a macro (the compile-time :doc-elision authoring seam)")
    (is (fn? @#'rf.image/image)
        "re-frame.image/image is a plain value fn (programmatic / computed-spec callers)")
    (is (= 're-frame.image/image
           (first (macroexpand-1 '(re-frame.core/image {:id :x}))))
        "rf/image expands to a re-frame.image/image constructor call")
    ;; And it actually constructs the normalized, INERT image value through the
    ;; facade — a `rf/image` call is data, not registration.
    (let [img (rf/image {:id :docs.counter/v2
                         :select-ns {:include ["docs.quickstart.counter.v2"]}})]
      (is (= :docs.counter/v2 (:rf.image/id img))
          "the :id is normalized to the owner-qualified :rf.image/id slot")
      (is (= ["docs.quickstart.counter.v2"] (:rf.image/include-ns img))
          ":select-ns :include is carried as the glob-pattern vector")
      (is (= [] (:rf.image/inline img))
          "no :registrations → empty inline-descriptor vector")
      (is (= img (rf/image {:id :docs.counter/v2
                            :select-ns {:include ["docs.quickstart.counter.v2"]}}))
          "PURE: equal spec maps return equal image values"))
    ;; A LITERAL inline `:doc` metadata slot is rewritten at
    ;; expansion time to the `(if interop/debug-enabled? <full> <stripped>)`
    ;; gate Closure constant-folds under :advanced + goog.DEBUG=false, DCEing the
    ;; `:doc` string bytes (the elision-probe pins the CLJS bundle absence). A
    ;; NON-literal / computed spec passes through un-gated to the runtime strip.
    (let [gated   (macroexpand-1
                    '(re-frame.core/image
                       {:registrations {:reg-event [[:x {:doc "gated"} identity]]}}))
          ungated (macroexpand-1 '(re-frame.core/image some-computed-spec))]
      (is (re-find #"interop/debug-enabled\?" (pr-str gated))
          "a literal inline :doc metadata slot rides the debug-gate for prod byte elision")
      (is (not (re-find #"interop/debug-enabled\?" (pr-str ungated)))
          "a non-literal spec is passed through un-gated (runtime strip handles it)"))))
