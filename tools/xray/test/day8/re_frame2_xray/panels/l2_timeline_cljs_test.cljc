(ns day8.re-frame2-xray.panels.l2-timeline-cljs-test
  "Pure-data tests for the L2 epoch-timeline helpers (rf2-gf58j).

  ## What's under test

    1. **source extraction** — `source-of` reads
       `[:dispatched :tags :source]` and nil-safes every step. Per
       rf2-1ve9h the prior `:rf/dispatch-origin` axis was collapsed
       into `:source` (Mike-approved Option A, 2026-05-28).
    2. **source → text tag** — `origin-source-tag` renders the bare
       source name for substrate origins and `ui` for every app-code
       source (and for nil), never a blank cell.
    3. **duration column** — `event-bundle-duration-ms` plucks the
       handler wall-time, `format-duration-ms` renders it as `N.N ms`
       on both runtimes, `event-bundle-duration-label` composes them.
    4. **epoch-has-an-issue signal** — `event-bundle-has-issue?`,
       which drives the L2 row's light-pink wash.

  Pure-fn shape — input cascade-aggregate map → expected output
  string / number / bool. No CLJS runtime touched."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.l2-timeline :as l2]
            [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]))

;; ---- fixture builders ---------------------------------------------------

(defn- cascade-with-source
  "Build a synthetic cascade record whose `:dispatched` carries the
  given `:source` tag. Mirrors the shape produced by
  `re-frame.trace.projection/group-by-event` post-rf2-1ve9h."
  [source]
  {:dispatch-id 42
   :event       [:cart/add-item {:id 99}]
   :dispatched  {:operation :rf.event/dispatched
                 :op-type   :rf.event
                 :tags      {:source                source
                             :rf.trace/dispatch-id  42}}
   :other       []
   :errors      []})

(defn- ev
  "Build a synthetic trace event with the given operation."
  [operation & {:keys [op-type tags] :or {op-type nil tags {}}}]
  (cond-> {:operation operation :tags tags}
    op-type (assoc :op-type op-type)))

;; ---- 1. source extraction (post-rf2-1ve9h) ------------------------------

(deftest source-of-test
  (testing "reads :source from :dispatched :tags (the post-rf2-1ve9h axis)"
    (is (= :tool           (l2/source-of (cascade-with-source :tool))))
    (is (= :router         (l2/source-of (cascade-with-source :router))))
    (is (= :ui             (l2/source-of (cascade-with-source :ui))))
    (is (= :after-timer    (l2/source-of (cascade-with-source :after-timer))))
    (is (= :machine-spawn  (l2/source-of (cascade-with-source :machine-spawn))))
    (is (= :fx-dispatch    (l2/source-of (cascade-with-source :fx-dispatch)))))

  (testing "nil-safe on missing slots"
    (is (nil? (l2/source-of nil)))
    (is (nil? (l2/source-of {})))
    (is (nil? (l2/source-of {:dispatched nil})))
    (is (nil? (l2/source-of {:dispatched {}})))
    (is (nil? (l2/source-of {:dispatched {:tags {}}}))))

  (testing "non-map input returns nil"
    (is (nil? (l2/source-of "not a cascade")))
    (is (nil? (l2/source-of 42)))))

;; ---- 2. source → source-tag (Figma `source` column, rf2-ad7zx.12) -------

(deftest origin-source-tag-test
  (testing "app-code sources render the `ui` tag (rf2-lnod7) — the
            reference tags EVERY row; the dominant app-code source reads
            `ui` rather than a blank cell"
    (is (= "ui" (l2/origin-source-tag :ui)))
    (is (= "ui" (l2/origin-source-tag :unknown)))
    (is (= "ui" (l2/origin-source-tag :other)))
    (is (= "ui" (l2/origin-source-tag :repl)))
    (is (= "ui" (l2/origin-source-tag :frame-init)))
    (is (= "ui" l2/ui-source-tag)))

  (testing "every substrate-internal closed-enum value renders its bare name"
    (is (= "router"            (l2/origin-source-tag :router)))
    (is (= "http"              (l2/origin-source-tag :http)))
    (is (= "ssr-hydration"     (l2/origin-source-tag :ssr-hydration)))
    (is (= "fx-dispatch"       (l2/origin-source-tag :fx-dispatch)))
    (is (= "fx-dispatch-later" (l2/origin-source-tag :fx-dispatch-later)))
    (is (= "machine-action"    (l2/origin-source-tag :machine-action)))
    (is (= "after-timer"       (l2/origin-source-tag :after-timer)))
    (is (= "always"            (l2/origin-source-tag :always)))
    (is (= "test"              (l2/origin-source-tag :test)))
    (is (= "tool"              (l2/origin-source-tag :tool)))
    (is (= "machine-spawn"     (l2/origin-source-tag :machine-spawn)))
    (is (= "websocket"         (l2/origin-source-tag :websocket))))

  (testing "nil → the `ui` default (rf2-lnod7) so synthetic / pre-source-
            tag cascades still render a concrete source rather than blank;
            never throws"
    (is (= "ui" (l2/origin-source-tag nil)))
    ;; an unknown keyword still yields its name — the column is the bare
    ;; source axis, not gated on the closed-enum glyph map.
    (is (= "unknown-axis" (l2/origin-source-tag :unknown-axis)))))

;; ---- 3. duration column (Figma `duration` column, rf2-lnod7) ------------

(defn- cascade-with-duration
  "Build a synthetic cascade whose `:handler` (`:rf.event/run-end`) trace
  event carries the given handler duration under `:rf.event/elapsed-ms`,
  the key the producer stamps (rf2-3x7nj.22.5 — a live run-end carries
  `:frame :rf.event/elapsed-ms :rf.event/v :rf.trace/dispatch-id
  :rf.trace/event-id :rf.trace/phase` and no `:duration-ms`). Mirrors the
  shape `re-frame.trace.projection/group-by-event` buckets into `:handler`."
  [duration-ms]
  {:dispatch-id 7
   :event       [:poll/tick]
   :handler     {:operation :rf.event/run-end
                 :op-type   :rf.event
                 :tags      {:rf.event/elapsed-ms  duration-ms
                             :rf.trace/dispatch-id 7}}})

(deftest event-bundle-duration-ms-test
  (testing "reads the producer's :rf.event/elapsed-ms off the :handler trace"
    (is (= 1.234 (l2/event-bundle-duration-ms (cascade-with-duration 1.234))))
    (is (= 0     (l2/event-bundle-duration-ms (cascade-with-duration 0))))
    (is (= "1.2 ms" (l2/event-bundle-duration-label (cascade-with-duration 1.234)))))

  (testing "falls back to the legacy :duration-ms when elapsed-ms is absent"
    (is (= 2.5 (l2/event-bundle-duration-ms
                 {:handler {:operation :rf.event/run-end
                            :tags      {:duration-ms 2.5}}}))))

  (testing "nil-safe on missing / non-numeric slots"
    (is (nil? (l2/event-bundle-duration-ms nil)))
    (is (nil? (l2/event-bundle-duration-ms {})))
    (is (nil? (l2/event-bundle-duration-ms {:handler nil})))
    (is (nil? (l2/event-bundle-duration-ms {:handler {:tags {}}})))
    (is (nil? (l2/event-bundle-duration-ms (cascade-with-duration "1.2"))))
    (is (nil? (l2/event-bundle-duration-ms "not a cascade")))))

(deftest format-duration-ms-test
  (testing "formats to one decimal place + ` ms` (Figma EventList style)"
    (is (= "1.2 ms"    (l2/format-duration-ms 1.234)))
    (is (= "0.4 ms"    (l2/format-duration-ms 0.4)))
    (is (= "0.6 ms"    (l2/format-duration-ms 0.62)))
    (is (= "0.0 ms"    (l2/format-duration-ms 0)))
    (is (= "12.0 ms"   (l2/format-duration-ms 12)))
    (is (= "1234.5 ms" (l2/format-duration-ms 1234.5))))

  (testing "nil for nil / non-numeric (cell stays empty), never throws"
    (is (nil? (l2/format-duration-ms nil)))
    (is (nil? (l2/format-duration-ms "1.2")))))

(deftest event-bundle-duration-label-test
  (testing "read + format in one step"
    (is (= "1.2 ms" (l2/event-bundle-duration-label (cascade-with-duration 1.234))))
    (is (= "0.4 ms" (l2/event-bundle-duration-label (cascade-with-duration 0.4)))))

  (testing "nil when no measured handler duration"
    (is (nil? (l2/event-bundle-duration-label {})))
    (is (nil? (l2/event-bundle-duration-label nil)))))

;; ---- 4. epoch-has-an-issue signal (rf2-b8guz) --------------------------
;;
;; `event-bundle-has-issue?` drives the L2 row's light-pink `:bg-issue-row`
;; wash. It must light up for EXACTLY the set the Issues ribbon/feed
;; aggregates — it reuses `issues-ribbon-helpers/issue-event?`, which is
;; severity-driven off `:op-type` (`:error` / `:warning`), so a lifecycle /
;; success-path trace — an `:info` activity row included — is NOT an issue.

(deftest event-bundle-has-issue?-clean-test
  (testing "a clean cascade (no issue traces) → false; nil-safe"
    (is (false? (l2/event-bundle-has-issue? {})))
    (is (false? (l2/event-bundle-has-issue? nil)))
    (is (false? (l2/event-bundle-has-issue? "not a cascade")))
    (is (false? (l2/event-bundle-has-issue? (cascade-with-source :ui))))
    ;; lifecycle / success-path traces in :other are NOT issues — the
    ;; row must stay unstyled (no wash) for a clean cascade that merely
    ;; carried machine / frame / registry chatter.
    (is (false? (l2/event-bundle-has-issue?
                 {:other [(ev :rf.machine/transition :op-type :rf.machine)
                          (ev :rf.frame/created      :op-type :rf.frame)]})))))

(deftest event-bundle-has-issue?-issue-test
  (testing "an error trace (`:op-type :error`) in :other → true"
    (is (true? (l2/event-bundle-has-issue?
                {:other [(ev :rf.error/handler-exception :op-type :error)]}))))

  (testing "a warning trace (`:op-type :warning`) in :other → true"
    (is (true? (l2/event-bundle-has-issue?
                {:other [(ev :rf.warning/schema-violation :op-type :warning)]}))))

  (testing "a hydration mismatch in :other → true — it arrives as an
            `:error` envelope (the hiccup tier), so the wash covers it"
    (is (true? (l2/event-bundle-has-issue?
                {:other [(ev :rf.ssr/hydration-mismatch :op-type :error)]}))))

  (testing "the cascade's legacy :errors slot alone → true (defence in
            depth for synthetic / older traces that populate it directly)"
    (is (true? (l2/event-bundle-has-issue?
                {:errors [{:operation :rf.error/no-such-fx}]}))))

  (testing "an issue mixed with lifecycle chatter still lights up"
    (let [c (-> (cascade-with-source :fx-dispatch)
                (assoc :other [(ev :rf.machine/transition :op-type :rf.machine)
                               (ev :rf.error/handler-exception :op-type :error)]))]
      (is (true? (l2/event-bundle-has-issue? c))))))

(deftest event-bundle-has-issue?-info-activity-test
  (testing "REGRESSION rf2-3x7nj.24.1 — a healthy managed-HTTP event does NOT
            wash. The runtime emits `:rf.http/issued` at `:info` inside the
            issuing fx handler on every managed request, so the row lands in
            the issuing bundle's :other beside a green `:ok` status; `:info`
            is activity, never an issue. The bundle is the producer's shape."
    (let [b (-> (cascade-with-source :ui)
                (assoc :effects [(teb/fx-handled-ev :rf.http/managed {} 1)]
                       :other   [(teb/http-issued-ev :app/load "/api/load")]))]
      (is (false? (l2/event-bundle-has-issue? b)))))
  (testing "CONTROLS — beside the same :info row, a `:warning` and an
            `:error` still wash, so the false above is about :info"
    (is (true? (l2/event-bundle-has-issue?
                {:other [(teb/http-issued-ev :app/load "/api/load")
                         (teb/ev :warning :rf.fx/skipped-on-platform
                                 {:rf.fx/id :app/clip})]})))
    (is (true? (l2/event-bundle-has-issue?
                {:other [(teb/http-issued-ev :app/load "/api/load")
                         (teb/handler-exception-ev :app/load "boom")]})))))
