(ns re-frame.db-noop-commit-test
  "Per rf2-ekq28v — commit-level `:db` semantics:

    1. identical?-noop short-circuit (`commit-frame-transition!`,
       frame.cljc): when the next app-db is `identical?` to the current
       app-db (the handler returned the db UNCHANGED — the common
       `(if cond (assoc db …) db)` else-arm), SKIP the `replace-container!`
       write entirely rather than re-installing an equal value. `=` stays
       the deeper change-detection (a different-object-but-`=`-value commit
       still writes).

    2. db-noop trace (`commit-frame-effects!`, router.cljc): a `:db`
       commit that left app-db UNCHANGED emits `:rf.event/db-noop` (the
       complement of `:rf.event/db-changed`) so Xray can show
       \"event returned an unchanged db; nothing committed.\"

    3. nil-coercion (`commit-frame-effects!`, router.cljc): a `{:db nil}`
       effect is coerced to `{:db {}}` (app-db is always a map, never nil)
       and emits a dev-mode `:rf.warning/db-nil-coerced` diagnostic for
       accidental-wipe visibility. A deliberate clear (`{:db {}}`) does
       NOT fire the diagnostic.

  Adversarial discriminators:
    - identical? else-arm SKIPS the write (the stored frame-state object
      is `identical?` before and after the dispatch — no new map installed);
    - value-equal-but-distinct-object STILL commits (a new frame-state map
      IS installed; the object identity changes) — `=` is honoured;
    - the no-op trace op fires on the unchanged-db commit;
    - the nil-coercion path commits `{}` (never nil) + fires the diagnostic.

  Contract — Spec 009 §Canonical per-event trace sequence + §Error event
  catalogue (`:rf.warning/db-nil-coerced` is a diagnostic-channel warning)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing  :reload)
  (require 're-frame.ssr      :reload)
  (require 're-frame.machines :reload)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- ops-of [acc] (mapv :operation @acc))

;; ---- 1 + 2: identical?-noop SKIPS the write AND emits :rf.event/db-noop ----

(deftest identical-noop-skips-write-and-emits-db-noop
  (testing "a reg-event whose else-arm returns the SAME db object
   (identical?) SKIPS the container write — the stored frame-state object
   is identical? before and after the dispatch — and emits :rf.event/db-noop
   (NOT :rf.event/db-changed)"
    ;; Seed a known app-db first.
    (rf/reg-event :noop/seed (fn [{:keys [db]} _] {:db {:counter 1 :seeded? true}}))
    ;; The classic no-change idiom: the else-arm returns `db` UNCHANGED
    ;; (identical object), so the commit is a genuine no-op.
    (rf/reg-event :noop/maybe-inc
      (fn [{:keys [db]} [_ do-it?]]
        {:db (if do-it? (update db :counter inc) db)}))
    (rf/dispatch-sync [:noop/seed])
    (let [fs-before (frame/frame-state-value :rf/default)
          acc       (collect-traces! ::identical-noop)]
      (try
        ;; do-it? = false → the handler returns `db` unchanged (identical?).
        (rf/dispatch-sync [:noop/maybe-inc false])
        (let [fs-after (frame/frame-state-value :rf/default)
              ops      (ops-of acc)]
          (is (identical? fs-before fs-after)
              "identical?-noop SKIPPED replace-container! — the stored
               frame-state object is the SAME object (no equal value re-installed)")
          (is (some #{:rf.event/db-noop} ops)
              ":rf.event/db-noop fired on the unchanged-db commit")
          (is (not (some #{:rf.event/db-changed} ops))
              ":rf.event/db-changed did NOT fire (exactly one of the two fires)")
          (is (= {:counter 1 :seeded? true} (frame/frame-app-db-value :rf/default))
              "app-db is unchanged"))
        (finally
          (rf/unregister-listener! :trace ::identical-noop))))))

(deftest changed-db-commits-and-emits-db-changed-not-db-noop
  (testing "the SAME handler on the do-it? = true path returns a CHANGED db
   → :rf.event/db-changed fires, :rf.event/db-noop does NOT, and the write
   lands (a new frame-state object is installed)"
    (rf/reg-event :noop/seed (fn [{:keys [db]} _] {:db {:counter 1}}))
    (rf/reg-event :noop/maybe-inc
      (fn [{:keys [db]} [_ do-it?]] {:db (if do-it? (update db :counter inc) db)}))
    (rf/dispatch-sync [:noop/seed])
    (let [fs-before (frame/frame-state-value :rf/default)
          acc       (collect-traces! ::changed)]
      (try
        (rf/dispatch-sync [:noop/maybe-inc true])
        (let [fs-after (frame/frame-state-value :rf/default)
              ops      (ops-of acc)]
          (is (not (identical? fs-before fs-after))
              "a real change installed a new frame-state object (write happened)")
          (is (some #{:rf.event/db-changed} ops) ":rf.event/db-changed fired")
          (is (not (some #{:rf.event/db-noop} ops)) ":rf.event/db-noop did NOT fire")
          (is (= 2 (:counter (frame/frame-app-db-value :rf/default)))
              "the counter incremented"))
        (finally
          (rf/unregister-listener! :trace ::changed))))))

;; ---- `=`-but-not-identical? STILL commits (deeper equality honoured) -------

(deftest equal-but-distinct-object-still-commits
  (testing "a handler that returns a DISTINCT object that is `=` to the
   current db STILL commits (the cheap identical? fast-path does NOT change
   deeper-equality behaviour): a NEW frame-state object is installed. The
   change-DETECTION (`=`) reports no app-db change, so :rf.event/db-noop
   fires — but the WRITE was NOT skipped (only identical? skips), so the
   stored frame-state object identity changes."
    (rf/reg-event :eq/seed (fn [{:keys [db]} _] {:db {:counter 1 :tag :a}}))
    ;; Rebuild an equal-but-DISTINCT map (not the same object) every call.
    (rf/reg-event :eq/rebuild-equal
      (fn [{:keys [db]} _] {:db {:counter 1 :tag :a}}))
    (rf/dispatch-sync [:eq/seed])
    (let [db-before-obj (frame/frame-app-db-value :rf/default)
          fs-before     (frame/frame-state-value :rf/default)
          acc           (collect-traces! ::eq-distinct)]
      (try
        (rf/dispatch-sync [:eq/rebuild-equal])
        (let [db-after-obj (frame/frame-app-db-value :rf/default)
              fs-after     (frame/frame-state-value :rf/default)
              ops          (ops-of acc)]
          (is (= db-before-obj db-after-obj) "the values are = (no logical change)")
          ;; The handler built a NEW map, so the installed app-db object is a
          ;; DISTINCT object — the identical?-noop short-circuit did NOT skip
          ;; this write (only reference-identical carries-forward skips).
          (is (not (identical? fs-before fs-after))
              "the write was NOT skipped — a `=`-but-distinct commit installs a
               new frame-state object (only identical? short-circuits)")
          ;; Change-detection is `=`, so no app-db change is reported →
          ;; :rf.event/db-noop is the signal (not db-changed).
          (is (some #{:rf.event/db-noop} ops)
              "=-equal commit reports no change → db-noop fires")
          (is (not (some #{:rf.event/db-changed} ops))
              "db-changed does NOT fire for a =-equal value"))
        (finally
          (rf/unregister-listener! :trace ::eq-distinct))))))

;; ---- 3: nil-coercion — {:db nil} → {} + dev diagnostic --------------------

(deftest db-nil-coerced-to-empty-map-with-diagnostic
  (testing "{:db nil} is coerced to {} (app-db is ALWAYS a map, never nil)
   and emits the :rf.warning/db-nil-coerced dev diagnostic"
    (rf/reg-event :nil/seed (fn [{:keys [db]} _] {:db {:counter 7}}))
    ;; A reg-event handler returning a {:db nil} effect.
    (rf/reg-event :nil/return-nil (fn [{:keys [db]} _] {:db nil}))
    (rf/dispatch-sync [:nil/seed])
    (let [acc (collect-traces! ::nil-coerce)]
      (try
        (rf/dispatch-sync [:nil/return-nil])
        (let [db   (frame/frame-app-db-value :rf/default)
              ops  (ops-of acc)
              warn (first (filterv #(= :rf.warning/db-nil-coerced (:operation %)) @acc))]
          (is (= {} db) "app-db was coerced to {} (NOT nil)")
          (is (map? db) "app-db is a map after a {:db nil} return")
          (is (some? warn) ":rf.warning/db-nil-coerced diagnostic fired")
          (is (= :warning (:op-type warn)) "the diagnostic rides the :warning severity")
          (is (some #{:rf.event/db-changed} ops)
              "the coerced {} differs from the seeded {:counter 7}, so it COMMITS
               (db-changed), not a no-op"))
        (finally
          (rf/unregister-listener! :trace ::nil-coerce))))))

(deftest deliberate-empty-clear-does-not-fire-diagnostic
  (testing "a DELIBERATE clear ({:db {}}) commits {} WITHOUT firing the
   db-nil-coerced diagnostic — only the literal {:db nil} form is flagged"
    (rf/reg-event :clear/seed (fn [{:keys [db]} _] {:db {:counter 7}}))
    (rf/reg-event :clear/empty (fn [{:keys [db]} _] {:db {}}))
    (rf/dispatch-sync [:clear/seed])
    (let [acc (collect-traces! ::deliberate-clear)]
      (try
        (rf/dispatch-sync [:clear/empty])
        (let [db   (frame/frame-app-db-value :rf/default)
              ops  (ops-of acc)]
          (is (= {} db) "app-db is the deliberate empty map")
          (is (not (some #{:rf.warning/db-nil-coerced} ops))
              "no db-nil-coerced diagnostic for a deliberate {:db {}} clear")
          (is (some #{:rf.event/db-changed} ops)
              "the {} differs from {:counter 7} → it commits (db-changed)"))
        (finally
          (rf/unregister-listener! :trace ::deliberate-clear))))))

(deftest nil-coerced-to-empty-when-already-empty-is-a-noop
  (testing "{:db nil} when app-db is ALREADY {} coerces to {} which is
   = to current → the coerced commit is a NO-OP (db-noop), and the
   diagnostic STILL fires (the nil was supplied regardless of the no-op
   outcome). The partition is never nil."
    (rf/reg-event :nilnoop/seed (fn [{:keys [db]} _] {:db {}}))
    (rf/reg-event :nilnoop/return-nil (fn [{:keys [db]} _] {:db nil}))
    (rf/dispatch-sync [:nilnoop/seed])
    (let [acc (collect-traces! ::nil-noop)]
      (try
        (rf/dispatch-sync [:nilnoop/return-nil])
        (let [db  (frame/frame-app-db-value :rf/default)
              ops (ops-of acc)]
          (is (= {} db) "app-db is {} (never nil)")
          (is (some #{:rf.warning/db-nil-coerced} ops)
              "the diagnostic fires whenever the supplied :db was literally nil")
          (is (some #{:rf.event/db-noop} ops)
              "coerced {} == current {} → no change → db-noop")
          (is (not (some #{:rf.event/db-changed} ops))
              "no db-changed for the no-op coerced commit"))
        (finally
          (rf/unregister-listener! :trace ::nil-noop))))))
