(ns re-frame.frame-destroy-incarnation-facade-cljs-test
  "BLACK-BOX facade-only regression for frame-value EXACT-INCARNATION teardown
  (rf2-clgup — u5jgq AC5/AC6, the shipped semantics from rf2-dlld6 #6186 +
  rf2-kvpmr #6197).

  `frame_destroy_incarnation_jvm_test.clj` proves the same contract by reading
  PRIVATE incarnation state — `frame/frame-incarnation-token`, `epoch-state/*`,
  `late-bind/*` — under JVM latches. This regression proves the SAME shipped
  behaviour observed ONLY through the PUBLIC re-frame.core facade: the frame
  constructors/destructors (`make-frame` / `destroy-frame!`), the seed event
  `:rf/set-db`, and the public value read `app-db-value`. It reaches into NO
  private incarnation token or lifecycle table — a frame's existence is read
  purely as `app-db-value` (a map for a live frame, nil for a destroyed/absent
  one, per Spec 002 §The public registrar query API).

  The headline contract (EP-0024 §Operation target grammar / rf2-moftbs; the
  `make-frame` / `destroy-frame!` docstrings):

    * `make-frame`'s returned VALUE is an EXACT-INCARNATION token — destroying it
      tears down ONLY the incarnation that call produced. A STALE value (whose
      incarnation was destroyed and replaced under the same id) carries no live
      authority and NO-OPS against the same-id successor;
    * a frame-id KEYWORD is ADDRESS-directed — it tears down whatever incarnation
      is currently live under the id.

  Two incarnations under one id are distinguished at the facade by the DB each
  seeds via `:initial-events [[:rf/set-db {…}]]` — `app-db-value` reads back the
  seeded map, so `{:who :a}` vs `{:who :b}` positively identifies WHICH
  incarnation survives, with no token peek.

  `.cljc` ends `-cljs-test` so it rides `npm run test:cljs` AND `clojure -M:test`
  — the JVM + CLJS coverage the bead asks for in one artefact."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; Fixture — reset the runtime (registrar, frames, trace listeners) and install
;; the plain-atom adapter so the constructed frames are runnable. `reset-runtime!`
;; re-seeds the framework-standard `:rf/set-db` each case, so the `:initial-events`
;; db-seed step below resolves through every frame's generation (EP-0027).
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private id :clgup/frame)

(defn- seed
  "Public constructor for an id-bearing frame whose app-db is seeded to `db` via
  the framework-standard `:rf/set-db` setup event — the only construction surface
  used here. Returns the frame VALUE (`make-frame`'s exact-incarnation token)."
  [db]
  (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))

;; ===========================================================================
;; The headline regression — a stale frame VALUE is an exact-incarnation no-op
;; against a same-id successor, while the id KEYWORD is address-directed. The
;; whole scenario is observed through `app-db-value` only.
;; ===========================================================================

(deftest stale-frame-value-teardown-noops-vs-successor-through-the-facade
  (testing "(1) make-frame A seeds and registers incarnation A under the id —
            app-db-value reads A's seeded db back (A is live at the facade)"
    (let [a (seed {:who :a})]
      (is (= {:who :a} (rf/app-db-value id))
          "the live frame A exposes its seeded app-db through the facade")

      (testing "(2) destroy A by its id (address-directed) — app-db-value goes
                nil: no incarnation is live under the id"
        (rf/destroy-frame! id)
        (is (nil? (rf/app-db-value id))
            "a destroyed frame is facade-observably absent (nil app-db-value)"))

      (testing "(3) make-frame a same-id SUCCESSOR B, seeded distinctly — this is
                a FRESH construction (A was fully destroyed first), so B is a new
                incarnation, facade-identified by its own seeded db"
        (seed {:who :b})
        (is (= {:who :b} (rf/app-db-value id))
            "the successor B exposes ITS seeded app-db (a distinct value from A)"))

      (testing "(4) destroy-frame! the STALE value A — it carries A's
                exact-incarnation authority, which no longer names the live
                incarnation, so it is a silent NO-OP: B survives UNCHANGED"
        (is (nil? (rf/destroy-frame! a))
            "destroy-frame! keeps its nil return whether it acts or no-ops")
        (is (= {:who :b} (rf/app-db-value id))
            "the stale value A did NOT tear down the same-id successor B")
        (testing "and B is a fully LIVE incarnation, not a half-torn-down husk —
                  it still accepts a public dispatch that rewrites its app-db"
          (rf/dispatch-sync [:rf/set-db {:who :b-live}] {:frame id})
          (is (= {:who :b-live} (rf/app-db-value id))
              "B remained writable through the facade after the stale teardown")))

      (testing "(5) destroy B by its id (address-directed) — this DOES tear down
                whatever incarnation is live under the id, so B is removed"
        (rf/destroy-frame! id)
        (is (nil? (rf/app-db-value id))
            "the keyword teardown removed the live successor B (nil app-db-value)")))))

;; ===========================================================================
;; Positive control — a NON-stale frame value DOES tear down its own live
;; incarnation. Isolates the variable: step (4)'s no-op above is attributable to
;; the value being STALE, not to value-directed teardown being generally inert.
;; ===========================================================================

(deftest a-live-frame-values-teardown-removes-its-own-incarnation-through-the-facade
  (testing "a frame VALUE whose incarnation is still live tears its OWN
            incarnation down — the exact-incarnation authority is live, so the
            frame is facade-observably removed"
    (let [f (seed {:who :only})]
      (is (= {:who :only} (rf/app-db-value id))
          "the frame is live before its owning value tears it down")
      (is (nil? (rf/destroy-frame! f))
          "destroy-frame! by a LIVE value returns nil like any teardown")
      (is (nil? (rf/app-db-value id))
          "the value tore down its own live incarnation (nil app-db-value)"))))
