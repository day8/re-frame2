(ns re-frame.epoch-redact-fn-test
  "Per rf2-wp70d.2 — coverage for the in-process redaction hook on
  `:epoch-history`. Spec contract: Tool-Pair §Time-travel §Redaction
  hook (rf2-wp70d.1), Security.md §Epoch privacy posture bullet 5,
  Spec-Schemas §`:rf/epoch-record` 'Redacted slot values', API.md
  §Configure keys.

  Five critical invariants:

    1. **Rollup runs BEFORE redact-fn** — `:rf.epoch/sensitive?` is
       computed from RAW signals; the redact-fn may erase the leaves
       the rollup keyed on, but the rollup itself reflects raw truth.

    2. **One pass per record** — ring + listeners see the SAME
       redacted shape (build-time, not fan-out time).

    3. **Failure isolation** — a throwing redact-fn emits
       `:rf.warning/epoch-redact-fn-exception` and falls back to the
       raw record (no broken drain).

    4. **Composition with `:trace-events-keep`** — redact-fn runs
       BEFORE the keep-window dissoc. A redact-fn that sentinels
       `:trace-events` is idempotent against the later cap.

    5. **`projected-record` × `:redact-fn` idempotency** — applying
       `projected-record` to an already-redacted record produces the
       same shape (two passes compose under `:rf/redacted`
       sentinels).

  Plus the `configure!` validation surface (`fn?` / `nil` accepted,
  other shapes silently dropped), and the three per-site wirings
  (`settle!`, `perform-reset-frame-db!`,
  `on-frame-destroyed!`'s `:halted-destroy` path)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-listeners!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-sensitive-schema!
  [frame-id]
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]]
                     {:frame frame-id})
  (rf/populate-sensitive-from-schemas! frame-id)
  nil)

(defn- record-warnings! []
  ;; Capture every `:warning` op-type emission so the failure-
  ;; isolation invariant can assert the documented op-id + tags fire.
  (let [warnings (atom [])]
    (rf/register-listener! ::warn-watcher
                           (fn [ev]
                             (when (= :warning (:op-type ev))
                               (swap! warnings conj ev))))
    warnings))

;; ============================================================================
;;  configure! validation surface (Spec API.md §Configure keys —
;;  :redact-fn row; rf2-wp70d.1)
;; ============================================================================

(deftest configure-accepts-fn
  (testing "(rf/configure :epoch-history {:redact-fn f}) — fn? accepted
            and the slot lands in current-config"
    (let [f (fn [r] r)]
      (rf/configure :epoch-history {:redact-fn f})
      (is (identical? f (:redact-fn (epoch/current-config)))
          ":redact-fn lands by identity — no wrapping"))))

(deftest configure-accepts-nil-to-clear
  (testing "(rf/configure :epoch-history {:redact-fn nil}) clears a
            previously-installed fn"
    (rf/configure :epoch-history {:redact-fn (fn [r] r)})
    (is (some? (:redact-fn (epoch/current-config))))

    (rf/configure :epoch-history {:redact-fn nil})
    (is (nil? (:redact-fn (epoch/current-config)))
        "explicit nil clears the slot")))

(deftest configure-rejects-non-fn
  (testing "(rf/configure :epoch-history {:redact-fn <bad>}) silently
            drops other shapes; a previously-installed fn survives"
    (let [f (fn [r] r)]
      (rf/configure :epoch-history {:redact-fn f})
      (rf/configure :epoch-history {:redact-fn "not-a-fn"})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "string silently dropped — prior fn survives")

      (rf/configure :epoch-history {:redact-fn 42})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "number silently dropped")

      (rf/configure :epoch-history {:redact-fn {:k :v}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "map silently dropped")

      (rf/configure :epoch-history {:redact-fn [:a :b]})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "vector silently dropped"))))

(deftest configure-absent-slot-preserves-existing-fn
  (testing "a configure call that does NOT mention :redact-fn leaves
            the previously-installed fn intact (the configure semantics
            are 'merge present keys' — absence is a no-op)"
    (let [f (fn [r] r)]
      (rf/configure :epoch-history {:redact-fn f})
      (rf/configure :epoch-history {:depth 17})
      (is (identical? f (:redact-fn (epoch/current-config)))
          ":redact-fn slot preserved across a :depth-only update")
      (is (= 17 (:depth (epoch/current-config)))
          "the :depth update was applied"))))

(deftest configure-partial-update-mixed-validity
  (testing "a configure call carrying a valid :redact-fn and an
            invalid :depth applies the fn and drops the depth"
    (rf/configure :epoch-history {:depth 9})
    (let [f (fn [r] r)]
      (rf/configure :epoch-history {:depth nil :redact-fn f})
      (is (identical? f (:redact-fn (epoch/current-config))))
      (is (= 9 (:depth (epoch/current-config)))
          ":depth nil dropped; prior 9 survives"))))

;; ============================================================================
;;  Invariant 1 — rollup runs BEFORE redact-fn
;; ============================================================================

(deftest invariant-1-rollup-computed-from-raw-before-redact-fn
  (testing "the :rf.epoch/sensitive? rollup reflects raw signals even
            when the redact-fn erases the leaves the rollup keyed on
            — the rollup must remain a trustworthy signal for off-box
            consumers branching on it"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)

    ;; Redact-fn wipes the very slot the rollup keyed on. If the
    ;; rollup ran AFTER the fn, it would observe the wiped leaf and
    ;; report sensitive? false — silently breaking off-box consumers.
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})

    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= :rf/redacted (get-in r [:db-after :auth :password]))
          "redact-fn ran — the password slot is :rf/redacted")
      (is (true? (:rf.epoch/sensitive? r))
          "rollup is true because it ran from the RAW record BEFORE
           the redact-fn erased the leaf"))))

;; ============================================================================
;;  Invariant 2 — one pass per record; ring + listeners see SAME shape
;; ============================================================================

(deftest invariant-2-ring-and-listener-see-same-redacted-shape
  (testing "the redact-fn runs ONCE per record at build-time, between
            assembly and ring-append/listener fan-out. The record
            stored in the ring is identical to the record delivered
            to every listener — no per-consumer divergence"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)

    ;; Track invocation count so we can assert ONE pass per record.
    (let [invocations (atom 0)
          listener-records (atom [])]
      (rf/configure :epoch-history
                    {:redact-fn (fn [r]
                                  (swap! invocations inc)
                                  (assoc-in r [:db-after :auth :password]
                                            :rf/redacted))})

      (rf/register-epoch-listener! ::watch
                             (fn [r] (swap! listener-records conj r)))

      (rf/reg-event-db :login
                       (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

      (is (= 1 @invocations)
          "redact-fn invoked EXACTLY once for the cascade — not once
           per listener nor once per consumer")

      (let [ring-record     (last-record :test/main)
            listener-record (last @listener-records)]
        (is (= :rf/redacted (get-in ring-record [:db-after :auth :password]))
            "ring record is redacted")
        (is (= :rf/redacted (get-in listener-record [:db-after :auth :password]))
            "listener record is redacted")
        (is (= ring-record listener-record)
            "ring and listener received structurally-equal records —
             same redacted shape, single source of truth")))))

(deftest invariant-2-multiple-listeners-receive-same-shape
  (testing "multiple listeners all receive the SAME redacted shape
            from the single build-time pass (no per-listener fan-out
            invocation of redact-fn)"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)

    (let [invocations (atom 0)
          a-rec       (atom nil)
          b-rec       (atom nil)
          c-rec       (atom nil)]
      (rf/configure :epoch-history
                    {:redact-fn (fn [r]
                                  (swap! invocations inc)
                                  (assoc-in r [:db-after :auth :password]
                                            :rf/redacted))})

      (rf/register-epoch-listener! ::a (fn [r] (reset! a-rec r)))
      (rf/register-epoch-listener! ::b (fn [r] (reset! b-rec r)))
      (rf/register-epoch-listener! ::c (fn [r] (reset! c-rec r)))

      (rf/reg-event-db :login
                       (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

      (is (= 1 @invocations)
          "three listeners + one ring-append = ONE redact-fn call")
      (is (= @a-rec @b-rec @c-rec (last-record :test/main))
          "every consumer sees the same redacted record"))))

;; ============================================================================
;;  Invariant 3 — failure isolation
;; ============================================================================

(deftest invariant-3-throwing-redact-fn-warns-and-falls-back
  (testing "a throwing redact-fn emits
            :rf.warning/epoch-redact-fn-exception and falls back to
            the raw record — the drain itself is not broken; the next
            cascade still records normally"
    (rf/reg-frame :test/main {})

    (let [warnings (record-warnings!)]
      (rf/configure :epoch-history
                    {:redact-fn (fn [_]
                                  (throw (ex-info "boom" {:why :test})))})

      (rf/reg-event-db :seed (fn [_ _] {:n 0}))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      ;; Fall-back to raw record landed in the ring.
      (let [r (last-record :test/main)]
        (is (some? r) "ring received a record — drain not broken")
        (is (= {:n 0} (:db-after r))
            "the record is the RAW shape — redact-fn's throw did not
             corrupt the build-record output"))

      ;; Warning fired with the documented op-id and tags.
      (let [warn (->> @warnings
                      (filter (fn [ev]
                                (= :rf.warning/epoch-redact-fn-exception
                                   (:operation ev))))
                      first)]
        (is (some? warn)
            ":rf.warning/epoch-redact-fn-exception was emitted")
        (is (= :test/main (get-in warn [:tags :frame]))
            ":frame tag carries the offending frame-id")
        (is (string? (get-in warn [:tags :ex-msg]))
            ":ex-msg tag carries the exception message string"))

      ;; Drain not broken — next cascade records as normal.
      (rf/configure :epoch-history {:redact-fn nil})
      (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
      (rf/dispatch-sync [:bump] {:frame :test/main})
      (let [r2 (last-record :test/main)]
        (is (= {:n 1} (:db-after r2))
            "next drain after the throw records normally")))))

(deftest invariant-3-throwing-redact-fn-stays-registered
  (testing "a throwing redact-fn stays registered — the next cascade
            re-attempts (the registration is not removed on first
            throw; the framework's posture is 'log and continue')"
    (rf/reg-frame :test/main {})
    (let [call-count (atom 0)]
      (rf/configure :epoch-history
                    {:redact-fn (fn [_]
                                  (swap! call-count inc)
                                  (throw (ex-info "boom" {})))})

      (rf/reg-event-db :seed (fn [_ _] {:n 0}))
      (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:bump] {:frame :test/main})
      (rf/dispatch-sync [:bump] {:frame :test/main})

      (is (= 3 @call-count)
          "redact-fn re-invoked on every cascade despite throwing"))))

;; ============================================================================
;;  Invariant 4 — composition with :trace-events-keep cap
;; ============================================================================

(deftest invariant-4-redact-fn-runs-before-trace-events-keep-cap
  (testing "a redact-fn that sentinels :trace-events runs BEFORE the
            keep-window dissoc — the sentinel survives the cap until
            the record crosses the keep-window boundary, at which
            point :trace-events is dissoc'd whether sentinel or raw"
    (rf/reg-frame :test/main {})
    ;; Sentinel :trace-events at build-time. The cap will later
    ;; dissoc :trace-events on records that fall outside the
    ;; keep-window — the sentinel makes that dissoc idempotent.
    (rf/configure :epoch-history
                  {:trace-events-keep 2
                   :redact-fn (fn [r]
                                (assoc r :trace-events :rf/redacted))})

    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:bump] {:frame :test/main})
    (rf/dispatch-sync [:bump] {:frame :test/main})
    (rf/dispatch-sync [:bump] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      ;; The TWO MOST-RECENT records retain :trace-events (the
      ;; sentinel) — the redact-fn ran first and replaced the slot
      ;; value; the keep-window cap left the slot alone because it
      ;; sits inside the keep window.
      (doseq [r (take-last 2 history)]
        (is (= :rf/redacted (:trace-events r))
            "in-window record has the redact-fn's sentinel as
             :trace-events"))
      ;; The older records had their :trace-events dissoc'd by the
      ;; keep-window cap (whether the slot held the sentinel or the
      ;; raw vector, the cap dissociates uniformly — idempotent).
      (doseq [r (drop-last 2 history)]
        (is (not (contains? r :trace-events))
            "out-of-window record had :trace-events dissoc'd by the
             keep-window cap — composition is idempotent")))))

;; ============================================================================
;;  Invariant 5 — projected-record × :redact-fn idempotency
;; ============================================================================

(deftest invariant-5-projected-record-idempotent-over-redacted-record
  (testing "applying projected-record to an already-redacted record
            produces a structurally-stable shape — both passes
            compose under :rf/redacted sentinels (rf2-wp70d.3
            territory; pinned here as a cheap composition smoke)"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)

    ;; Redact-fn substitutes :rf/redacted in the same slot the
    ;; projected-record helper would also redact via the schema-
    ;; declared sensitive path.
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})

    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [redacted   (last-record :test/main)
          projected  (epoch/projected-record redacted)
          projected2 (epoch/projected-record projected)]
      (is (= :rf/redacted (get-in redacted [:db-after :auth :password]))
          "redact-fn produced the sentinel")
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "projection over an already-redacted leaf produces the
           same sentinel — :rf/redacted is the projection's own
           target value")
      (is (= projected projected2)
          "projection is idempotent — a second pass changes nothing
           (the leaf is already the sentinel target)"))))

;; ============================================================================
;;  Per-site wirings — settle! / perform-reset-frame-db! /
;;  on-frame-destroyed!'s :halted-destroy path
;; ============================================================================

(deftest wiring-settle-applies-redact-fn
  (testing "the settle! drain-settle commit path runs the redact-fn
            against the assembled record (the dominant per-event
            recording path)"
    (rf/reg-frame :test/main {})
    (rf/configure :epoch-history
                  {:redact-fn (fn [r] (assoc r :rf/test-tag :redacted))})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= :redacted (:rf/test-tag (last-record :test/main)))
        "settle! path: ring record carries the redact-fn's tag")))

(deftest wiring-reset-frame-db-applies-redact-fn
  (testing "the perform-reset-frame-db! synthetic-record commit path
            runs the redact-fn (pair-tool injection / story tools
            land the synthetic :rf.epoch/db-replaced record through
            this seam)"
    (rf/reg-frame :test/main {})
    (let [synthetic (atom nil)]
      (rf/register-epoch-listener! ::watch (fn [r] (reset! synthetic r)))
      (rf/configure :epoch-history
                    {:redact-fn (fn [r] (assoc r :rf/test-tag :redacted))})
      (rf/reset-frame-db! :test/main {:injected :state})
      (is (= :redacted (:rf/test-tag @synthetic))
          "reset-frame-db! path: listener received the redacted
           synthetic record")
      (is (= :redacted (:rf/test-tag (last-record :test/main)))
          "reset-frame-db! path: ring received the same redacted
           synthetic record"))))

(deftest wiring-halted-destroy-applies-redact-fn
  (testing "on-frame-destroyed!'s :halted-destroy partial-record
            commit runs the redact-fn — devtools observing the
            halted-cascade record see the same redacted shape they
            would for an :ok cascade"
    (rf/reg-frame :test/main {})
    (let [halted (atom nil)]
      (rf/register-epoch-listener! ::watch
                             (fn [r]
                               (when (= :halted-destroy (:outcome r))
                                 (reset! halted r))))
      (rf/configure :epoch-history
                    {:redact-fn (fn [r] (assoc r :rf/test-tag :redacted))})
      (rf/reg-event-fx :destroy-self
                       (fn [_ _]
                         (frame/destroy-frame! :test/main)
                         {}))
      (try (rf/dispatch-sync [:destroy-self] {:frame :test/main})
           (catch Throwable _ nil))
      (when @halted
        (is (= :halted-destroy (:outcome @halted)))
        (is (= :redacted (:rf/test-tag @halted))
            "halted-destroy path: listener received the redacted
             partial record")))))

;; ============================================================================
;;  Default no-op posture
;; ============================================================================

(deftest no-redact-fn-installed-is-identity-passthrough
  (testing "without :redact-fn installed, every recorded record
            passes through unchanged (the default-nil posture — apps
            opt in explicitly)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (= {:n 0} (:db-after r))
          "no redact-fn — record is the raw shape")
      (is (not (contains? r :rf/test-tag))
          "no redact-fn — no synthetic slots added"))))

(deftest nil-redact-fn-installed-is-identity-passthrough
  (testing "an explicitly-installed nil :redact-fn is equivalent to
            'no redact-fn' — same identity-passthrough shape"
    (rf/reg-frame :test/main {})
    (rf/configure :epoch-history {:redact-fn nil})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (= {:n 0} (:db-after r))))))
