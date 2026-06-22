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
;; 2b. SOURCE-SCOPED clear (rf2-34jrb6) — a clear removes only the effect's own
;;     contribution; a path ALSO claimed by another source stays redacted
;; ---------------------------------------------------------------------------

(deftest clear-sensitive-is-source-scoped-does-not-un-redact-another-source
  (testing ":clear-sensitive removes only the effect's OWN declaration for a
            path; a path ALSO claimed by another source (e.g. a reg-flow output
            under :source :flow) stays classified — the clear must not silently
            un-redact a path another owner still considers sensitive (a privacy
            fail-open). rf2-34jrb6."
    ;; A flow (another source) declares [:user :token] sensitive in the SAME
    ;; per-frame elision registry slot the effects write. reg-flow lives in a
    ;; separate artefact, so simulate its registry write directly via the shared
    ;; swap-elision-slot! seam (the exact slot + shape reg-flow installs:
    ;; {:source :flow :flow-id …}).
    (elision/swap-elision-slot! :rf/default
      (fn [reg]
        (assoc-in reg [:sensitive-declarations [:user :token]]
                  {:source :flow :flow-id :token-watch})))
    (is (= :flow (:source (get (sensitive-decls) [:user :token])))
        "the flow-sourced declaration is standing in the registry")
    ;; An effect ALSO classifies the same path — collapses to ONE registry entry
    ;; per path, now stamped :source :effect (the SET is the later write).
    (rf/reg-event :effect-classify-token
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:user :token] "Bearer secret-xyz")
         :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:effect-classify-token])
    (is (= :effect (:source (get (sensitive-decls) [:user :token])))
        "the same-path effect SET overwrote the entry to :source :effect")
    ;; The effect now CLEARS the path. Source-scoped clear removes the effect's
    ;; own contribution — but the path is STILL claimed by the flow, so it must
    ;; remain classified and the value must stay REDACTED at egress.
    ;; Re-install the flow declaration after the effect SET overwrote it (the
    ;; collapse-to-one-entry means the flow's mark must be re-asserted; a live
    ;; reg-flow would re-fold on its own lifecycle — here we model the standing
    ;; flow claim).
    (elision/swap-elision-slot! :rf/default
      (fn [reg]
        (assoc-in reg [:sensitive-declarations [:user :token]]
                  {:source :flow :flow-id :token-watch})))
    (rf/reg-event :effect-clear-token
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
    (rf/dispatch-sync [:effect-clear-token])
    (is (contains? (sensitive-decls) [:user :token])
        "the path is STILL classified — the flow's claim survives the effect clear")
    (is (= :flow (:source (get (sensitive-decls) [:user :token])))
        "the surviving entry is the flow's, not the effect's")
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= privacy/redacted-sentinel (get-in wire [:user :token]))
          "the value stays REDACTED at egress — the clear did not un-redact it"))))

(deftest clear-sensitive-removes-effect-sourced-entry-when-sole-claimant
  (testing "when the effect is the SOLE source for a path, the source-scoped
            clear removes it (no other-source entry shields it) — the ordinary
            set/unset case is unchanged. rf2-34jrb6."
    (rf/reg-event :effect-only-classify
      (fn [{:keys [db]} _] {:db db :sensitive [[:only :effect]]}))
    (rf/dispatch-sync [:effect-only-classify])
    (is (= :effect (:source (get (sensitive-decls) [:only :effect]))))
    (rf/reg-event :effect-only-clear
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:only :effect]]}))
    (rf/dispatch-sync [:effect-only-clear])
    (is (not (contains? (sensitive-decls) [:only :effect]))
        "an effect-sourced path with no other claimant is removed by its clear")))

;; ---------------------------------------------------------------------------
;; 2c. CLEAR over an ABSENT / wrong-axis path is a harmless NO-OP (rf2-26p9yg)
;;     The fail-open clear contract relies on a clear being a harmless dissoc.
;;     A regression that throws on an absent-key clear, or that prunes the
;;     wrong axis slot, would be a fail-open privacy hazard — pin it.
;; ---------------------------------------------------------------------------

(deftest clear-sensitive-over-never-classified-path-is-a-silent-no-op
  (testing ":clear-sensitive over a path that was NEVER classified is a silent
            no-op — no throw, no error trace, the registry is unchanged.
            rf2-26p9yg."
    ;; nothing is classified yet
    (is (not (contains? (sensitive-decls) [:never :classified]))
        "precondition: the path is absent from the sensitive registry")
    (rf/reg-event :clear-absent-sensitive
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:never :classified]]}))
    (let [recorded (record-traces! :clear-absent-sensitive-probe)]
      (rf/dispatch-sync [:clear-absent-sensitive])
      (is (empty? (error-events recorded :rf.error/classification-effect-shape))
          "no classification-effect error — clearing an absent path does not throw")
      (rf/unregister-listener! :trace :clear-absent-sensitive-probe))
    (is (not (contains? (sensitive-decls) [:never :classified]))
        "the registry is unchanged — the clear of an absent path is a no-op")))

(deftest clear-large-over-never-classified-path-is-a-silent-no-op
  (testing ":clear-large over a path that was NEVER classified is a silent
            no-op — no throw, no error trace, the large registry is unchanged.
            rf2-26p9yg."
    (is (not (contains? (large-decls) [:never :large]))
        "precondition: the path is absent from the large registry")
    (rf/reg-event :clear-absent-large
      (fn [{:keys [db]} _] {:db db :clear-large [[:never :large]]}))
    (let [recorded (record-traces! :clear-absent-large-probe)]
      (rf/dispatch-sync [:clear-absent-large])
      (is (empty? (error-events recorded :rf.error/classification-effect-shape))
          "no classification-effect error — clearing an absent large path does not throw")
      (rf/unregister-listener! :trace :clear-absent-large-probe))
    (is (not (contains? (large-decls) [:never :large]))
        "the large registry is unchanged — the clear of an absent path is a no-op")))

(deftest clear-sensitive-on-a-large-only-path-leaves-the-large-axis-intact
  (testing ":clear-sensitive over a path classified on the OTHER axis only
            (:large) is a no-op on the sensitive axis AND leaves the large
            classification intact — the wrong-axis clear must not prune the
            large slot. rf2-26p9yg."
    (rf/reg-event :classify-large-only
      (fn [{:keys [db]} _] {:db db :large [[:docs :blob]]}))
    (rf/dispatch-sync [:classify-large-only])
    (is (contains? (large-decls) [:docs :blob]))
    (is (not (contains? (sensitive-decls) [:docs :blob]))
        "precondition: the path is large-only, never sensitive")
    ;; clear on the WRONG axis for this path
    (rf/reg-event :wrong-axis-clear
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:docs :blob]]}))
    (let [recorded (record-traces! :wrong-axis-clear-probe)]
      (rf/dispatch-sync [:wrong-axis-clear])
      (is (empty? (error-events recorded :rf.error/classification-effect-shape))
          "no error — the wrong-axis clear does not throw")
      (rf/unregister-listener! :trace :wrong-axis-clear-probe))
    (is (contains? (large-decls) [:docs :blob])
        "the large classification is INTACT — :clear-sensitive did not prune the large axis")
    (is (not (contains? (sensitive-decls) [:docs :blob]))
        "the sensitive axis is still empty for this path — the clear was a no-op there")))

;; ---------------------------------------------------------------------------
;; 2d. SAME-EVENT SET + CLEAR of one path — CLEAR WINS (rf2-9zylo0)
;;     elision/apply-classification-effects reduces SET axes before CLEAR
;;     axes within one effect map, so a same-event set+clear of one path on
;;     one axis resolves to UNclassified. A reorder regression (clear-then-set)
;;     would invert this and ship the path RAW or leave it redacted — pin both
;;     the registry outcome AND the egress (the path ships raw / unclassified).
;; ---------------------------------------------------------------------------

(deftest same-event-set-and-clear-of-one-sensitive-path-clear-wins
  (testing "one event returning BOTH :sensitive [[:p]] and :clear-sensitive
            [[:p]] resolves to the path being UNCLASSIFIED — the SET is applied
            before its CLEAR (set axes reduced first), so the CLEAR is the
            later write and wins. Pins the effect ordering so a reorder
            regression that left the path classified-then-shipped-raw OR
            raw-then-redacted is caught. rf2-9zylo0."
    (rf/reg-event :set-and-clear-same
      (fn [{:keys [db]} _]
        {:db              (assoc-in db [:user :token] "Bearer set-then-cleared")
         :sensitive       [[:user :token]]
         :clear-sensitive [[:user :token]]}))
    (let [recorded (record-traces! :set-and-clear-probe)]
      (rf/dispatch-sync [:set-and-clear-same])
      (is (empty? (error-events recorded :rf.error/classification-effect-shape))
          "the same-event set+clear is well-shaped — no error")
      (rf/unregister-listener! :trace :set-and-clear-probe))
    (is (not (contains? (sensitive-decls) [:user :token]))
        "CLEAR WINS — the path is NOT in the sensitive registry after commit")
    ;; the :db value DID commit (the classification effects are not the :db)
    (is (= "Bearer set-then-cleared"
           (get-in (frame/frame-app-db-value :rf/default) [:user :token]))
        "the :db write committed — only the classification was set-then-cleared")
    ;; and because the path ends UNclassified, the value ships RAW at egress
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))]
      (is (= "Bearer set-then-cleared" (get-in wire [:user :token]))
          "the path ships RAW at egress — clear won, so nothing redacts it"))))

(deftest same-event-set-and-clear-of-one-large-path-clear-wins
  (testing "the same set-before-clear ordering holds on the :large axis: an
            event returning both :large [[:p]] and :clear-large [[:p]] ends
            with the path UNclassified, so an oversized value there ships RAW
            (no large marker) at egress. rf2-9zylo0."
    (rf/reg-event :set-and-clear-large
      (fn [{:keys [db]} _]
        {:db          (assoc-in db [:docs :csv] (apply str (repeat 500 "Y")))
         :large       [[:docs :csv]]
         :clear-large [[:docs :csv]]}))
    (rf/dispatch-sync [:set-and-clear-large])
    (is (not (contains? (large-decls) [:docs :csv]))
        "CLEAR WINS on the large axis — the path is NOT in the large registry")
    (let [wire (elision/elide-wire-value (frame/frame-app-db-value :rf/default))
          slot (get-in wire [:docs :csv])]
      (is (not (elision/marker? slot))
          "no large marker — the cleared path ships RAW at egress")
      (is (= (apply str (repeat 500 "Y")) slot)
          "the raw oversized value is shipped unchanged (clear won)"))))

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
;; 4b. fail-loud negatives across ALL FOUR axes (rf2-mz582u)
;;     The original negatives only exercised :sensitive. elision.cljc
;;     classification-effect-defect validates all four keys (:sensitive
;;     :large :clear-sensitive :clear-large) and reports a distinct
;;     :offending-key. A regression that skipped validation on the clear keys
;;     (or :large) would ship a malformed clear SILENTLY. Feed each of the
;;     other three a malformed payload — a NON-VECTOR value and a NON-VECTOR
;;     path entry — and assert each raises the SAME error id with its own
;;     :offending-key, with NO :db commit. Each test feeds ONLY the one
;;     malformed key so :offending-key is unambiguous (defect detection
;;     iterates the key set, returning the first defect).
;; ---------------------------------------------------------------------------

(defn- assert-axis-fails-loud
  "Drive an event whose ONLY classification key is `effect-key` with the
  malformed `payload`, and assert it fails loud: exactly one
  :rf.error/classification-effect-shape with `:offending-key` == `effect-key`,
  and the :db commit is aborted (the seeded :n stays 1, not 2)."
  [effect-key payload probe-id ev-id]
  (rf/reg-event :seed-axis (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf/dispatch-sync [:seed-axis])
  (rf/reg-event ev-id
    (fn [{:keys [db]} _]
      {:db (assoc db :n 2) effect-key payload}))
  (let [recorded (record-traces! probe-id)]
    (rf/dispatch-sync [ev-id])
    (let [errs (error-events recorded :rf.error/classification-effect-shape)]
      (is (= 1 (count errs))
          (str "exactly one classification-effect-shape error for " effect-key))
      (is (= effect-key (:offending-key (:tags (first errs))))
          (str "the diagnostic names " effect-key " as the offending key")))
    (rf/unregister-listener! :trace probe-id))
  (is (= 1 (:n (frame/frame-app-db-value :rf/default)))
      (str "no :db commit happened on the malformed " effect-key " abort")))

(deftest malformed-large-payload-fails-loud
  (testing "a non-vector :large payload fails :rf.error/classification-effect-shape
            with :offending-key :large and no :db commit. rf2-mz582u."
    (assert-axis-fails-loud :large :not-a-vector
                            :bad-large-probe :bad-large)))

(deftest malformed-large-path-entry-fails-loud
  (testing "a non-vector path entry inside an otherwise-vector :large payload
            fails loud with :offending-key :large. rf2-mz582u."
    (assert-axis-fails-loud :large [:not-a-path-vector]
                            :bad-large-entry-probe :bad-large-entry)))

(deftest malformed-clear-sensitive-payload-fails-loud
  (testing "a non-vector :clear-sensitive payload fails
            :rf.error/classification-effect-shape with :offending-key
            :clear-sensitive and no :db commit — the clear keys are validated
            too, so a malformed clear is never shipped silently. rf2-mz582u."
    (assert-axis-fails-loud :clear-sensitive :not-a-vector
                            :bad-clear-sensitive-probe :bad-clear-sensitive)))

(deftest malformed-clear-large-payload-fails-loud
  (testing "a non-vector :clear-large payload fails
            :rf.error/classification-effect-shape with :offending-key
            :clear-large and no :db commit. rf2-mz582u."
    (assert-axis-fails-loud :clear-large :not-a-vector
                            :bad-clear-large-probe :bad-clear-large)))

(deftest malformed-clear-large-path-entry-fails-loud
  (testing "a non-vector path entry inside an otherwise-vector :clear-large
            payload fails loud with :offending-key :clear-large. rf2-mz582u."
    (assert-axis-fails-loud :clear-large [:not-a-path-vector]
                            :bad-clear-large-entry-probe :bad-clear-large-entry)))

(deftest non-segment-path-element-is-reported-as-a-defect
  (testing "a path entry whose SEGMENT is not a valid :rf/path segment (a
            non-EDN-identity element — e.g. a function) makes
            re-frame.path/normalize-concrete throw :rf.error/bad-path, which
            classification-effect-defect catches and re-reports as the SAME
            classification-effect-shape defect (one error id for the whole
            fail-closed :rf/path boundary). No :db commit. rf2-mz582u."
    (rf/reg-event :seed-seg (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:seed-seg])
    ;; a function is not a legal path segment — a sequential path whose
    ;; element is a non-EDN-identity value drives normalize-concrete to throw.
    (rf/reg-event :bad-segment
      (fn [{:keys [db]} _]
        {:db (assoc db :n 2) :sensitive [[(fn [] :nope)]]}))
    (let [recorded (record-traces! :bad-segment-probe)]
      (rf/dispatch-sync [:bad-segment])
      (let [errs (error-events recorded :rf.error/classification-effect-shape)]
        (is (= 1 (count errs))
            "a non-segment path element fails loud as ONE classification-effect-shape error")
        (is (= :sensitive (:offending-key (:tags (first errs))))
            "the offending key is named"))
      (rf/unregister-listener! :trace :bad-segment-probe))
    (is (= 1 (:n (frame/frame-app-db-value :rf/default)))
        "no :db commit happened on the non-segment-path abort")))

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
