(ns re-frame.flight-booker-cljs-test
  "Behavioural regression coverage for the 7GUIs Flight Booker example
  (rf2-6t486x). The example demonstrates *derived UI state as a subscription*
  — the Book button's enabled-ness is `:flight/book-enabled?`, a keystone sub
  ANDing three smaller answers — yet had ZERO behavioural coverage: only
  `re-frame.example-frame-scoping-cljs-test` touched it, and only to assert its
  ns-load `[:flight]` app-schema landed on `:rf/default`. Nothing exercised the
  actual date logic or the sub graph, so a regression that ACCEPTS an
  impossible date, drops the deliberate `setUTCFullYear` low-year guard, or
  lights Book when `return < start` would ship silently green.

  These belong in the framework test tree, NOT under `examples/` (examples stay
  test-free per rf2-8cevm). They exercise the example by `:require`ing
  `seven-guis.flight-booker.core` — a Reagent-coupled `.cljs`-only namespace —
  so they run under the consolidated `:node-test` CLJS build, which has
  `../examples/core` on its source paths (mirrors the classpath reach of
  `re-frame.seven-guis-cells-parser-cljs-test`).

  Two axes:
    1. `valid-date?` — the PURE calendar-validity fn. Leap-year correctness via
       the UTC round-trip, the deliberate `setUTCFullYear` (NOT `js/Date.UTC`,
       which would corrupt low four-digit years like `0026` to `1926`), and
       rejection of shape-valid-but-impossible dates.
    2. The sub graph (`:flight/return-enabled?` / `:flight/start-valid?` /
       `:flight/return-valid?` / `:flight/dates-coherent?` /
       `:flight/book-enabled?`) — driven by the real event handlers via
       `dispatch-sync` on an anon frame, read back via `rf/compute-sub`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [seven-guis.flight-booker.core :as flight]))

;; `:ambient-frame nil` — these tests create their own top-level anon frames via
;; `make-anon-frame-record!` and drive them with an explicit `{:frame f}`, so
;; there must be no ambient `:rf/default` scope masking frame resolution (the
;; `[:flight]` app-schema is bound to `:rf/default`, not to these anon frames,
;; so commits here run unvalidated — behavioural logic is what's under test,
;; and the schema binding is already pinned by example-frame-scoping).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

;; ===========================================================================
;; valid-date? — pure calendar validity (leap-year UTC round-trip)
;; ===========================================================================

(deftest valid-date?-accepts-real-calendar-dates
  (testing "well-formed real dates round-trip cleanly and pass"
    (doseq [s ["2026-05-06"   ;; the seed date
               "2024-02-29"   ;; leap day of a divisible-by-4 year
               "2000-02-29"   ;; divisible-by-400 century — a real leap day
               "2026-12-31"   ;; year-end edge
               "2026-01-01"]] ;; year-start edge
      (is (true? (flight/valid-date? s)) (str s " is a real date")))))

(deftest valid-date?-rejects-impossible-but-shape-valid-dates
  (testing "the regex only checks SHAPE; these pass it but are not real dates,
            and the UTC round-trip must reject each (a silent-accept regression
            here is exactly what this example's derived-state design guards)"
    (doseq [s ["2026-02-31"   ;; February never has 31 days
               "2025-02-29"   ;; 2025 is NOT a leap year — overflows to March
               "1900-02-29"   ;; divisible-by-100-not-400 — NOT a leap year
               "2026-99-99"   ;; nonsense month and day
               "2026-13-01"   ;; month 13
               "2026-00-10"   ;; month 0
               "2026-05-00"   ;; day 0
               "2026-04-31"]] ;; April has 30 days
      (is (false? (flight/valid-date? s))
          (str s " is shape-valid but not a real calendar date")))))

(deftest valid-date?-rejects-malformed-shapes
  (testing "anything the ISO yyyy-mm-dd regex rejects is invalid, including nil"
    (doseq [s ["2026-5-6"          ;; single-digit month/day
               "20260506"          ;; no separators
               "2026/05/06"        ;; wrong separator
               "2026-05-06T00:00"  ;; trailing time
               "not-a-date"
               ""
               nil]]
      (is (false? (flight/valid-date? s))
          (str (pr-str s) " is not ISO yyyy-mm-dd")))))

(deftest valid-date?-low-year-uses-setUTCFullYear-not-date-utc
  (testing "rf2-6t486x — the DELIBERATE `setUTCFullYear` (not `js/Date.UTC`):
            a low four-digit year like 0026 must round-trip to 26, NOT be
            corrupted to 1926 (the 0-99 -> 1900-1999 mapping `js/Date.UTC`
            would apply). A revert to `js/Date.UTC` makes the round-trip fail
            its own year check and this date would be wrongly rejected"
    (is (true? (flight/valid-date? "0026-05-06"))
        "year 0026 is a real date under setUTCFullYear's faithful low-year handling")
    (is (true? (flight/valid-date? "0099-12-31"))
        "year 0099 too — still below the js/Date.UTC 1900-window boundary")
    (is (true? (flight/valid-date? "0001-01-01"))
        "year 0001 — the extreme low edge")))

;; ===========================================================================
;; sub graph — driven through the real handlers on an anon frame
;; ===========================================================================

(defn- flight-frame!
  "Boot a fresh anon frame seeded via `:flight/initialise` (one-way,
  start = return = 2026-05-06). Returns the frame id."
  []
  (let [f (frame/make-anon-frame-record! {:doc "flight-booker test frame"})]
    (rf/dispatch-sync [:flight/initialise] {:frame f})
    f))

(defn- sub
  "Compute a flight sub against the frame's current app-db snapshot. Every
  flight sub is an app-db (layer-1/`:<-`) sub, so a bare app-db value suffices."
  [f query-v]
  (rf/compute-sub query-v (rf/app-db-value f)))

(deftest seed-state-lights-book
  (testing "the seeded one-way form (both dates the same valid ISO date) is a
            fully bookable state — every leg of the AND holds"
    (let [f (flight-frame!)]
      (is (false? (sub f [:flight/return-enabled?])) "one-way disables the return input")
      (is (true?  (sub f [:flight/start-valid?])))
      (is (true?  (sub f [:flight/return-valid?])) "one-way: return validity is not required")
      (is (true?  (sub f [:flight/dates-coherent?])))
      (is (true?  (sub f [:flight/book-enabled?])) "Book is clickable"))))

(deftest invalid-start-date-blocks-book
  (testing "an impossible start date fails start-valid? and drops book-enabled?"
    (let [f (flight-frame!)]
      (rf/dispatch-sync [:flight/set-start "2026-02-31"] {:frame f})
      (is (false? (sub f [:flight/start-valid?])))
      (is (false? (sub f [:flight/book-enabled?]))))))

(deftest one-way-skips-return-validation
  (testing "on a one-way trip the return field can hold total garbage and Book
            stays enabled — return-valid? short-circuits to true when the
            return input is disabled"
    (let [f (flight-frame!)]
      (rf/dispatch-sync [:flight/set-return "not-a-date-at-all"] {:frame f})
      (is (false? (sub f [:flight/return-enabled?])))
      (is (true?  (sub f [:flight/return-valid?])) "return validity waived while one-way")
      (is (true?  (sub f [:flight/dates-coherent?])) "coherence is trivially true one-way")
      (is (true?  (sub f [:flight/book-enabled?]))))))

(deftest return-trip-requires-a-valid-return-date
  (testing "switching to a return trip enables the return input, and an invalid
            return date now blocks Book"
    (let [f (flight-frame!)]
      (rf/dispatch-sync [:flight/set-trip-type :return] {:frame f})
      (is (true? (sub f [:flight/return-enabled?])))
      (rf/dispatch-sync [:flight/set-return "2026-02-31"] {:frame f})
      (is (false? (sub f [:flight/return-valid?])))
      (is (false? (sub f [:flight/book-enabled?]))))))

(deftest return-before-start-is-incoherent
  (testing "rf2-6t486x — the silent bug this example prevents: Book must NOT
            light when a return trip's return date precedes its start date.
            Coherence is a lexicographic ISO compare (return >= start)"
    (let [f (flight-frame!)]
      (rf/dispatch-sync [:flight/set-trip-type :return] {:frame f})
      (rf/dispatch-sync [:flight/set-start "2026-05-10"] {:frame f})
      (rf/dispatch-sync [:flight/set-return "2026-05-06"] {:frame f})
      (is (true?  (sub f [:flight/start-valid?])) "both dates parse")
      (is (true?  (sub f [:flight/return-valid?])))
      (is (false? (sub f [:flight/dates-coherent?])) "return < start")
      (is (false? (sub f [:flight/book-enabled?])) "Book stays disabled"))))

(deftest return-on-or-after-start-is-coherent
  (testing "a return trip with return >= start is coherent and bookable, and the
            boundary case return = start (>=, not >) also lights Book"
    (let [f (flight-frame!)]
      (rf/dispatch-sync [:flight/set-trip-type :return] {:frame f})
      (rf/dispatch-sync [:flight/set-start "2026-05-06"] {:frame f})
      ;; return strictly after start
      (rf/dispatch-sync [:flight/set-return "2026-05-10"] {:frame f})
      (is (true? (sub f [:flight/dates-coherent?])))
      (is (true? (sub f [:flight/book-enabled?])))
      ;; return exactly equal to start — the >= boundary
      (rf/dispatch-sync [:flight/set-return "2026-05-06"] {:frame f})
      (is (true? (sub f [:flight/dates-coherent?])) "return = start is coherent")
      (is (true? (sub f [:flight/book-enabled?]))))))
