(ns re-frame.core-api-additions-test
  "JVM tests for three core-API additions landed together
   (rf2-7whh / rf2-ewku / rf2-t38q):

   - `rf/with-frame` macro form (replacing the fn form). Two shapes:
     bare-keyword (Shape 1) and let-binding (Shape 2). Per Spec 002
     §with-frame and `spec/API.md` row 74.

   - `(rf/registrations kind pred-fn)` 2-arity filter. Per `spec/API.md`
     row 304 and Spec 001 §Public registrar query API.

   - `(rf/frame-ids ns-prefix)` 1-arity filter. Per `spec/API.md`
     row 308 and Spec 002 §The public registrar query API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.machines]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ===========================================================================
;; rf2-7whh — with-frame macro form
;; ===========================================================================

(deftest with-frame-shape-1-bare-keyword
  (testing "(with-frame :keyword body) binds *current-frame* across body"
    (rf/reg-frame :wf/alpha {:doc "alpha"})
    (is (= :rf/default (rf/current-frame))
        "outside the macro: resolves to :rf/default")
    (let [observed (rf/with-frame :wf/alpha (rf/current-frame))]
      (is (= :wf/alpha observed)
          "inside the macro body: resolves to the bound id"))
    (is (= :rf/default (rf/current-frame))
        "after the macro returns: dynamic binding unwinds")))

(deftest with-frame-shape-1-multi-form-body
  (testing "(with-frame :keyword expr1 expr2 ...) evaluates all body forms,
            returns the last"
    (rf/reg-frame :wf/beta {:doc "beta"})
    (let [side (atom [])
          result (rf/with-frame :wf/beta
                   (swap! side conj :first)
                   (swap! side conj :second)
                   (rf/current-frame))]
      (is (= [:first :second] @side)
          "all body forms were evaluated in order")
      (is (= :wf/beta result)
          "the last form's value is returned"))))

(deftest with-frame-shape-1-subscriber-captures-frame
  (testing "subscriber called inside (with-frame :k ...) captures :k"
    (rf/reg-frame :wf/left  {:doc "left"})
    (rf/reg-frame :wf/right {:doc "right"})
    (rf/reg-event-db :wf/seed (fn [_ [_ n]] {:n n}))
    (rf/reg-sub :wf/n (fn [db _] (:n db)))
    (rf/dispatch-sync [:wf/seed 7]  {:frame :wf/left})
    (rf/dispatch-sync [:wf/seed 99] {:frame :wf/right})
    (let [sl (rf/with-frame :wf/left  (rf/subscriber))
          sr (rf/with-frame :wf/right (rf/subscriber))]
      (is (= 7  @(sl [:wf/n])) ":wf/left subscriber sees :wf/left's :n")
      (is (= 99 @(sr [:wf/n])) ":wf/right subscriber sees :wf/right's :n"))))

(deftest with-frame-shape-2-let-binding-create-use-destroy
  (testing "(with-frame [f (make-frame opts)] body) creates, binds, destroys"
    (let [captured-id (atom nil)
          observed-current (atom nil)]
      (rf/with-frame [f (rf/make-frame {:doc "ephemeral"})]
        (reset! captured-id f)
        (reset! observed-current (rf/current-frame))
        (is (= f (rf/current-frame))
            "inside the body: *current-frame* is the freshly-made id")
        (is (some? (rf/frame-meta f))
            "the frame is alive during the body"))
      (is (some? @captured-id)
          "the macro yielded a frame id to the body")
      (is (= @captured-id @observed-current)
          "the body saw the just-created id as current-frame")
      (is (nil? (rf/frame-meta @captured-id))
          "the frame was destroyed on body exit")
      (is (= :rf/default (rf/current-frame))
          "*current-frame* reverted after the body"))))

(deftest with-frame-shape-2-destroys-on-exception
  (testing "(with-frame [f ...] body) destroys the frame even when body throws"
    (let [captured-id (atom nil)]
      (try
        (rf/with-frame [f (rf/make-frame {:doc "ephemeral-throw"})]
          (reset! captured-id f)
          (throw (ex-info "boom" {:kind ::boom})))
        (catch Exception e
          (is (= ::boom (:kind (ex-data e)))
              "the body's exception propagates")))
      (is (some? @captured-id))
      (is (nil? (rf/frame-meta @captured-id))
          "the frame is destroyed even on exception"))))

(deftest with-frame-shape-2-on-create-fires-and-state-is-readable
  (testing "(with-frame [f (make-frame {:on-create [...]})] body) — on-create
            fires before body, body sees the seeded state, destroy runs after"
    (rf/reg-event-db :wf/initialise (fn [_ _] {:counter 42}))
    (let [captured-db (atom nil)]
      (rf/with-frame [f (rf/make-frame {:on-create [:wf/initialise]})]
        (reset! captured-db (rf/get-frame-db f)))
      (is (= {:counter 42} @captured-db)
          "the body observed the on-create-seeded app-db"))))

(deftest with-frame-rejects-vector-bindings-with-wrong-arity
  (testing "(with-frame [...] body) with vector-but-not-2-count raises at compile time (rf2-4ymm0 CQ4)"
    ;; `macroexpand` wraps macro-side ex-infos in a Compiler$CompilerException;
    ;; call the expansion helper directly so we observe the structured throw
    ;; that fires at compile time when the user types `with-frame []`.
    (require 're-frame.core-reg-view-macro)
    (let [expand (resolve 're-frame.core-reg-view-macro/expand-with-frame)]
      ;; Empty vector — easy typo of Shape 2 to omit both sides.
      (let [e (try (expand [] '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "[] must throw")
        (is (re-find #"with-frame: vector binding must be \[sym expr\]"
                     (.getMessage ^Throwable e))
            "the message carries the structured reason"))
      ;; 3-element vector — typo of `[sym expr]` with extra tail.
      (let [e (try (expand '[f g h] '((do nil))) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "[f g h] must throw")
        (is (re-find #"with-frame: vector binding must be \[sym expr\]"
                     (.getMessage ^Throwable e))
            "the message carries the structured reason")))))

;; ===========================================================================
;; rf2-ewku — (rf/registrations kind pred-fn) filter arity
;; ===========================================================================

(deftest registrations-1-arity-returns-full-map
  (testing "(registrations kind) returns the full {id metadata} map"
    (rf/reg-event-db :hf/one (fn [db _] db))
    (rf/reg-event-db :hf/two (fn [db _] db))
    (let [all (rf/registrations :event)]
      (is (contains? all :hf/one))
      (is (contains? all :hf/two)))))

(deftest registrations-2-arity-filters
  (testing "(registrations kind pred-fn) filters by (pred meta) — metadata-only"
    ;; Per Spec 001 §The query API + API.md the predicate sees the
    ;; metadata-map only. Id-namespace filters ride a user-tag the
    ;; caller stamps onto the slot (or compose via `filter` over the
    ;; returned map's keys).
    (rf/reg-event-db :hf.alpha/one (fn [db _] db))
    (rf/reg-event-db :hf.alpha/two (fn [db _] db))
    (rf/reg-event-db :hf.beta/one  (fn [db _] db))
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
    (rf/reg-event-db :hf/marked   (fn [db _] db))
    (rf/reg-event-db :hf/unmarked (fn [db _] db))
    ;; Re-register :hf/marked with extra meta on the slot.
    (registrar/register! :event :hf/marked
      (assoc (rf/handler-meta :event :hf/marked) :rf/marker? true))
    (let [marked (rf/registrations :event (fn [m] (:rf/marker? m)))]
      (is (= #{:hf/marked} (set (keys marked)))
          "only handlers whose metadata satisfies the pred survive"))))

(deftest registrations-2-arity-empty-result
  (testing "a predicate that matches nothing returns {}"
    (rf/reg-event-db :hf/one (fn [db _] db))
    (is (= {} (rf/registrations :event (constantly false)))
        "no entries match → empty map")))

(deftest registrations-2-arity-unknown-kind-returns-empty
  (testing "a kind with no registrations returns {} regardless of pred"
    (is (= {} (rf/registrations :app-schema (constantly true)))
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
      (is (contains? all :rf/default)
          "the default frame is always present"))))

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
