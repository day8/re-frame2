(ns re-frame.classification-effects-cljs-test
  "EP-0025 B3 (rf2-g2pckt) — the four COMMIT-PLANE data-classification
  effects `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`,
  applied WITH the `:db` write at the commit point (a frame-state transform
  into the per-frame elision registry), NOT a post-commit `:fx`.

  Pins the acceptance legs the bead enumerates:

    1. classify-then-egress (SAME event) — a handler returning
       `{:sensitive [[:user :token]]}` alongside `:db` records the path in the
       per-frame registry AT COMMIT; a subsequent egress read redacts the value
       at that path. Classifying and egressing in the SAME event redacts.
    2. clear — `:clear-sensitive` / `:clear-large` remove the named paths.
    3. axes independent — clearing `:sensitive` does not touch `:large`, and
       vice versa.
    4. fail-loud pre-commit — a malformed payload (`{:sensitive :not-a-vector}`)
       throws `:rf.error/classification-effect-shape` on the pre-commit-
       transactional path so NO `:db` commit happens.
    5. value-independence — a path may be classified BEFORE a value lands there;
       the classification redacts whatever later occupies the path.

  EP-0025 (clean break complete): the durable `:sensitive` / `:large {:app-db
  …}` *frame annotation* and the imperative `add-marks` API are both REMOVED.
  These commit-plane effects (`:source :effect`) are the canonical durable
  app-db classification route; they populate the SAME registry slots that
  `reg-flow` outputs (`:source :flow`) and the subsystem projection-relative
  declarations (`:source :route` / `:source :machine`) write, unioning with
  them at egress-lookup time.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both run
  it. Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; A frame is auto-registered + scope-pinned by the reset fixture (the
;; `make-reset-runtime-fixture` ensures a default frame and binds it as the
;; ambient scope — the carried-invariant equivalent of `(with-frame …)`), so a
;; bare `dispatch-sync` cascades into it and a zero-arity `elide-wire-value`
;; / registry read resolves it.

(defn- sensitive-decls []
  (elision/sensitive-declarations))

(defn- large-decls []
  (elision/declarations))

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- error-events [recorded operation]
  (filterv (fn [ev]
             (and (= :error (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

;; ---------------------------------------------------------------------------
;; 1. classify-then-egress in the SAME event redacts
;; ---------------------------------------------------------------------------

(deftest sensitive-effect-records-path-and-redacts-at-egress
  (testing "a handler returning {:sensitive [[:user :token]]} alongside :db
            records the path in the per-frame registry AT COMMIT, and a
            subsequent egress read redacts the value there"
    (rf/reg-event :auth/login
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:user :token] "Bearer secret-xyz")
         :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:auth/login])
    ;; recorded in the registry, tagged :source :effect
    (is (contains? (sensitive-decls) [:user :token])
        "the classified path is in the per-frame sensitive registry")
    (is (= :effect (:source (get (sensitive-decls) [:user :token])))
        "the effect-sourced declaration is tagged :source :effect")
    ;; the application sees the REAL value in app-db (read-only-at-egress)
    (is (= "Bearer secret-xyz" (get-in (frame/frame-app-db-value :rf/default)
                                       [:user :token]))
        "app-db still holds the real value — classification is read only at egress")
    ;; egress read redacts the value at that path
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= privacy/redacted-sentinel (get-in wire [:user :token]))
          "the egress projection redacts the classified path"))))

(deftest classify-then-egress-in-the-same-event-redacts
  (testing "classifying and egressing in the SAME event redacts — the effect
            is applied WITH the :db write, so a value classified this event is
            redacted from its first egress. The handler classifies :user :token
            AND writes the secret in ONE event; immediately after the cascade
            the registry carries the classification, so an egress of the value
            written this event redacts (it flowed under a classification
            installed atomically with the :db commit)."
    (rf/reg-event :auth/login-same-event
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:user :token] "Bearer same-event")
         :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:auth/login-same-event])
    (is (contains? (sensitive-decls) [:user :token])
        "same-event classification is present immediately after commit")
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= privacy/redacted-sentinel (get-in wire [:user :token]))
          "the same-event classification redacts the same-event value at egress"))))

(deftest large-effect-records-path-and-marks-at-egress
  (testing "a :large effect records the path; an oversized value at that path
            elides to an :rf.size/large-elided marker at egress"
    (rf/reg-event :docs/upload
      (fn [{:keys [db]} _]
        {:db    (assoc-in db [:docs :csv] (apply str (repeat 500 "X")))
         :large [[:docs :csv]]}))
    (rf/dispatch-sync [:docs/upload])
    (is (contains? (large-decls) [:docs :csv])
        "the classified path is in the per-frame large registry")
    (is (= :effect (:source (get (large-decls) [:docs :csv]))))
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))
          slot (get-in wire [:docs :csv])]
      (is (elision/marker? slot)
          "the large path elides to an :rf.size/large-elided marker at egress")
      (is (= [:docs :csv] (get-in slot [:rf.size/large-elided :path]))))))

;; ---------------------------------------------------------------------------
;; 5. value-independence — classify BEFORE a value exists
;; ---------------------------------------------------------------------------

(deftest classification-is-value-independent
  (testing "a path may be classified BEFORE any value lands there; the
            classification redacts whatever later occupies the path"
    ;; classify with NO value written
    (rf/reg-event :pre/classify
      (fn [{:keys [db]} _] {:db db :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:pre/classify])
    (is (contains? (sensitive-decls) [:user :token])
        "the path is classified even though no value exists there")
    ;; later, a value lands
    (rf/reg-event :late/write
      (fn [{:keys [db]} _] {:db (assoc-in db [:user :token] "late-secret")}))
    (rf/dispatch-sync [:late/write])
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= privacy/redacted-sentinel (get-in wire [:user :token]))
          "the standing classification redacts the later-written value"))))

;; ---------------------------------------------------------------------------
;; 2. clear — :clear-sensitive / :clear-large remove the named paths
;; ---------------------------------------------------------------------------

(deftest clear-sensitive-removes-the-path
  (testing ":clear-sensitive removes the named path from the sensitive registry"
    (rf/reg-event :classify
      (fn [{:keys [db]} _] {:db db :sensitive [[:user :token] [:user :pin]]}))
    (rf/dispatch-sync [:classify])
    (is (contains? (sensitive-decls) [:user :token]))
    (is (contains? (sensitive-decls) [:user :pin]))
    (rf/reg-event :unclassify
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
    (rf/dispatch-sync [:unclassify])
    (is (not (contains? (sensitive-decls) [:user :token]))
        "the cleared path is removed")
    (is (contains? (sensitive-decls) [:user :pin])
        "an unnamed sibling path survives the clear")))

(deftest clear-large-removes-the-path
  (testing ":clear-large removes the named path from the large registry"
    (rf/reg-event :classify-l
      (fn [{:keys [db]} _] {:db db :large [[:docs :csv]]}))
    (rf/dispatch-sync [:classify-l])
    (is (contains? (large-decls) [:docs :csv]))
    (rf/reg-event :unclassify-l
      (fn [{:keys [db]} _] {:db db :clear-large [[:docs :csv]]}))
    (rf/dispatch-sync [:unclassify-l])
    (is (not (contains? (large-decls) [:docs :csv]))
        "the cleared large path is removed")))

;; ---------------------------------------------------------------------------
;; 3. axes independent — sensitive vs large clears do not cross
;; ---------------------------------------------------------------------------

(deftest axes-are-independent
  (testing "clearing the sensitive axis does not touch the large axis and
            vice versa"
    (rf/reg-event :classify-both
      (fn [{:keys [db]} _]
        {:db        db
         :sensitive [[:user :token]]
         :large     [[:docs :csv]]}))
    (rf/dispatch-sync [:classify-both])
    (is (contains? (sensitive-decls) [:user :token]))
    (is (contains? (large-decls) [:docs :csv]))
    ;; clearing sensitive must leave large untouched
    (rf/reg-event :clear-s
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
    (rf/dispatch-sync [:clear-s])
    (is (not (contains? (sensitive-decls) [:user :token]))
        "the sensitive path is cleared")
    (is (contains? (large-decls) [:docs :csv])
        "the large path is UNTOUCHED by a sensitive clear (axes independent)")
    ;; clearing large must leave sensitive untouched (re-classify sensitive first)
    (rf/dispatch-sync [:classify-both])
    (rf/reg-event :clear-l
      (fn [{:keys [db]} _] {:db db :clear-large [[:docs :csv]]}))
    (rf/dispatch-sync [:clear-l])
    (is (not (contains? (large-decls) [:docs :csv]))
        "the large path is cleared")
    (is (contains? (sensitive-decls) [:user :token])
        "the sensitive path is UNTOUCHED by a large clear (axes independent)")))

;; ---------------------------------------------------------------------------
;; 4. fail-loud pre-commit — malformed payload, NO :db commit
;; ---------------------------------------------------------------------------

(deftest malformed-payload-fails-loud-with-no-db-commit
  (testing "a malformed payload (:sensitive :not-a-vector) is rejected FAIL-LOUD
            on the pre-commit-transactional (FINAL-effects) boundary: it emits
            :rf.error/classification-effect-shape and aborts the event with NO
            :db commit (no partial commit). In-band — like the legacy-root
            rejection — so it does not escape the drain."
    ;; seed a known app-db value first
    (rf/reg-event :seed
      (fn [{:keys [db]} _] {:db (assoc db :counter 1)}))
    (rf/dispatch-sync [:seed])
    (is (= 1 (:counter (frame/frame-app-db-value :rf/default))))
    ;; a handler returning a malformed classification effect ALSO tries to write
    ;; :db — the rejection must abort BEFORE the :db commit
    (rf/reg-event :bad-classify
      (fn [{:keys [db]} _]
        {:db        (assoc db :counter 99)
         :sensitive :not-a-vector}))
    (let [recorded (record-traces! :bad-classify-probe)]
      (rf/dispatch-sync [:bad-classify])
      (let [errs (error-events recorded :rf.error/classification-effect-shape)]
        (is (= 1 (count errs))
            "exactly one :rf.error/classification-effect-shape error was emitted")
        (is (= :sensitive (:offending-key (:tags (first errs))))
            "the diagnostic names the offending effect key"))
      (rf/unregister-listener! :trace :bad-classify-probe))
    ;; the :db commit did NOT happen — app-db is still at the pre-handler value
    (is (= 1 (:counter (frame/frame-app-db-value :rf/default)))
        "NO :db commit happened — the malformed classification aborted pre-commit")))

(deftest malformed-path-entry-fails-loud
  (testing "a non-sequential path entry in an otherwise-vector payload also
            fails loud pre-commit"
    (rf/reg-event :seed2 (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:seed2])
    (rf/reg-event :bad-entry
      (fn [{:keys [db]} _]
        {:db (assoc db :n 2) :sensitive [:not-a-path-vector]}))
    (let [recorded (record-traces! :bad-entry-probe)]
      (rf/dispatch-sync [:bad-entry])
      (is (= 1 (count (error-events recorded :rf.error/classification-effect-shape)))
          "a non-sequential path entry fails loud (one error emitted)")
      (rf/unregister-listener! :trace :bad-entry-probe))
    (is (= 1 (:n (frame/frame-app-db-value :rf/default)))
        "no :db commit happened on the malformed-entry abort")))

;; ---------------------------------------------------------------------------
;; classification-only effect (no :db) still commits the registry write
;; ---------------------------------------------------------------------------

(deftest classification-only-effect-commits-registry
  (testing "a handler returning ONLY a classification effect (no :db) still
            commits the registry write — the runtime-db partition participates"
    (rf/reg-event :classify-only
      (fn [_ _] {:sensitive [[:secret :value]]}))
    (rf/dispatch-sync [:classify-only])
    (is (contains? (sensitive-decls) [:secret :value])
        "a classification-only effect writes the registry")))
