(ns day8.re-frame2-xray.panels.l2-timeline-cljs-test
  "Pure-data tests for the L2 epoch-timeline helpers (rf2-gf58j).

  ## What's under test

    1. **source extraction** — `source-of` reads
       `[:dispatched :tags :source]` and nil-safes every step. Per
       rf2-1ve9h the prior `:rf/dispatch-origin` axis was collapsed
       into `:source` (Mike-approved Option A, 2026-05-28).
    2. **source → bucket → glyph mapping** — every closed-enum value
       projects onto its chrome bucket; silent buckets render no
       prefix; unknown / nil return nil.
    3. **source / bucket → title text** — present only when a glyph
       is present.
    4. **cascade activity flags** — per-class detection from `:other`
       events + the cascade's source bucket + the `:errors` slot.
    5. **activity-badges projection** — ordered, compressed, glyph-
       matched against the canonical mapping.
    6. **activity tooltip** — nil-safe + human-readable.

  Pure-fn shape — input cascade-aggregate map → expected output
  glyph string / hiccup-ready vector. No CLJS runtime touched."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.l2-timeline :as l2]))

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

(deftest source-bucket-of-test
  (testing "source → chrome bucket projection (post-rf2-1ve9h)"
    ;; silent — the default app-code path
    (is (nil? (l2/source-bucket-of (cascade-with-source :ui))))
    (is (nil? (l2/source-bucket-of (cascade-with-source :unknown))))
    (is (nil? (l2/source-bucket-of (cascade-with-source :other))))
    (is (nil? (l2/source-bucket-of (cascade-with-source :repl))))
    (is (nil? (l2/source-bucket-of (cascade-with-source :frame-init))))
    ;; substrate-internal — each maps to its named bucket
    (is (= :router        (l2/source-bucket-of (cascade-with-source :router))))
    (is (= :http          (l2/source-bucket-of (cascade-with-source :http))))
    (is (= :ssr           (l2/source-bucket-of (cascade-with-source :ssr-hydration))))
    (is (= :test          (l2/source-bucket-of (cascade-with-source :test))))
    (is (= :tool          (l2/source-bucket-of (cascade-with-source :tool))))
    (is (= :machine-spawn (l2/source-bucket-of (cascade-with-source :machine-spawn))))
    (is (= :websocket     (l2/source-bucket-of (cascade-with-source :websocket))))
    ;; collapsed fx-event-bundle kinds all bucket as :fx-emit
    (is (= :fx-emit       (l2/source-bucket-of (cascade-with-source :fx-dispatch))))
    (is (= :fx-emit       (l2/source-bucket-of (cascade-with-source :fx-dispatch-later))))
    (is (= :fx-emit       (l2/source-bucket-of (cascade-with-source :machine-action))))
    ;; collapsed timer kinds all bucket as :timer
    (is (= :timer         (l2/source-bucket-of (cascade-with-source :after-timer))))
    (is (= :timer         (l2/source-bucket-of (cascade-with-source :always)))))

  (testing "unknown / nil → nil"
    (is (nil? (l2/source-bucket-of nil)))
    (is (nil? (l2/source-bucket-of (cascade-with-source :unknown-axis))))))

;; ---- 2. source → glyph mapping ------------------------------------------

(deftest origin-prefix-glyph-test
  (testing "silent sources render no prefix — the common case stays clutter-free"
    (is (nil? (l2/origin-prefix-glyph :ui)))
    (is (nil? (l2/origin-prefix-glyph :unknown)))
    (is (nil? (l2/origin-prefix-glyph :other)))
    (is (nil? (l2/origin-prefix-glyph :repl)))
    (is (nil? (l2/origin-prefix-glyph :frame-init))))

  (testing "substrate sources render their bucket's glyph"
    (is (= "R"    (l2/origin-prefix-glyph :router)))
    (is (= "🌐"   (l2/origin-prefix-glyph :http)))     ; 🌐
    (is (= "💧"   (l2/origin-prefix-glyph :ssr-hydration))) ; 💧
    (is (= "⚡"     (l2/origin-prefix-glyph :fx-dispatch)))  ; ⚡
    (is (= "⚡"     (l2/origin-prefix-glyph :fx-dispatch-later)))
    (is (= "⚡"     (l2/origin-prefix-glyph :machine-action)))
    (is (= "⏲"     (l2/origin-prefix-glyph :after-timer)))   ; ⏲
    (is (= "⏲"     (l2/origin-prefix-glyph :always)))
    (is (= "T"    (l2/origin-prefix-glyph :test)))
    (is (= "🔧"   (l2/origin-prefix-glyph :tool)))     ; 🔧
    (is (= "i"    (l2/origin-prefix-glyph :machine-spawn)))
    (is (= "🌊"   (l2/origin-prefix-glyph :websocket)))) ; 🌊

  (testing "buckets resolve directly (callers may pass a bucket keyword)"
    (is (= "R"    (l2/origin-prefix-glyph :router)))
    (is (= "💧"   (l2/origin-prefix-glyph :ssr)))      ; bucket name itself
    (is (= "⚡"     (l2/origin-prefix-glyph :fx-emit)))  ; bucket name itself
    (is (= "⏲"     (l2/origin-prefix-glyph :timer))))   ; bucket name itself

  (testing "unknown / nil → nil (defence-in-depth, never throws)"
    (is (nil? (l2/origin-prefix-glyph nil)))
    (is (nil? (l2/origin-prefix-glyph :unknown-axis)))
    (is (nil? (l2/origin-prefix-glyph "string"))))

  (testing "bucket-glyphs covers the 9 substrate buckets (Spec 009 / Xray A.5)"
    (is (= #{:router :http :ssr :fx-emit :timer
             :test :tool :machine-spawn :websocket}
           (set (keys l2/bucket-glyphs))))))

;; ---- 2b. source → source-tag (Figma `source` column, rf2-ad7zx.12) ------

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

;; ---- 3. source → title text ---------------------------------------------

(deftest origin-prefix-title-test
  (testing "title text present only when the glyph is present"
    (is (nil?              (l2/origin-prefix-title :ui)))
    (is (nil?              (l2/origin-prefix-title :unknown)))
    (is (nil?              (l2/origin-prefix-title nil)))
    (is (some?             (l2/origin-prefix-title :router)))
    (is (some?             (l2/origin-prefix-title :tool)))
    (is (some?             (l2/origin-prefix-title :http))))

  (testing "title carries information about the bucket so the operator can read
            the closed-enum value"
    (is (re-find #":router"      (l2/origin-prefix-title :router)))
    (is (re-find #":fx-"         (l2/origin-prefix-title :fx-dispatch)))
    (is (re-find #":websocket"   (l2/origin-prefix-title :websocket)))))

;; ---- 3b. duration column (Figma `duration` column, rf2-lnod7) ------------

(defn- cascade-with-duration
  "Build a synthetic cascade whose `:handler` (`:rf.event/run-end`) trace
  event carries the given handler `:duration-ms`. Mirrors the shape
  `re-frame.trace.projection/group-by-event` buckets into `:handler`."
  [duration-ms]
  {:dispatch-id 7
   :event       [:poll/tick]
   :handler     {:operation :rf.event/run-end
                 :op-type   :rf.event
                 :tags      {:duration-ms duration-ms
                             :rf.trace/dispatch-id 7}}})

(deftest event-bundle-duration-ms-test
  (testing "reads :duration-ms from [:handler :tags :duration-ms]"
    (is (= 1.234 (l2/event-bundle-duration-ms (cascade-with-duration 1.234))))
    (is (= 0     (l2/event-bundle-duration-ms (cascade-with-duration 0)))))

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

;; ---- 4. cascade activity flags ------------------------------------------

(deftest event-bundle-activity-flags-defaults-test
  (testing "an empty cascade yields all-false flags"
    (let [flags (l2/event-bundle-activity-flags {})]
      (is (= {:error? false :machine? false :http? false
              :timer? false :fx-emit? false}
             flags))))

  (testing "nil cascade is safe — returns all-false flags"
    (let [flags (l2/event-bundle-activity-flags nil)]
      (is (= {:error? false :machine? false :http? false
              :timer? false :fx-emit? false}
             flags)))))

(deftest event-bundle-activity-flags-error-test
  (testing ":errors slot populated → error? true (legacy axis)"
    (let [flags (l2/event-bundle-activity-flags
                 {:errors [{:operation :rf.error/handler-throw}]})]
      (is (true? (:error? flags)))))

  (testing ":op-type :error on :other → error? true"
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.error/handler-throw :op-type :error)]})]
      (is (true? (:error? flags)))))

  (testing ":rf.error/* operation on :other (no :op-type) → error? true"
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.error/bad-fx)]})]
      (is (true? (:error? flags))))))

(deftest event-bundle-activity-flags-machine-test
  (testing ":rf.machine/* operation → machine? true"
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.machine/transition)]})]
      (is (true? (:machine? flags))))
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.machine/spawn)
                          (ev :rf.machine/despawn)]})]
      (is (true? (:machine? flags)))))
  (testing "rf2-uxp0u5 — SUB-namespaced machine ops set the ◆ badge too.
            Most non-transition machine activity is emitted under
            sub-namespaces (`rf.machine.spawn`, `.lifecycle`, `.timer`,
            `.microstep`, `.spawn-all`). An exact `= \"rf.machine\"` match
            missed them all, so a spawn/despawn/timer/microstep-only epoch
            under-reported its machine activity — contradicting the fn
            docstring. Prefix-matching the `rf.machine*` family fixes it."
    (doseq [op [:rf.machine.spawn/spawned
                :rf.machine.lifecycle/destroyed
                :rf.machine.timer/scheduled
                :rf.machine.timer/fired
                :rf.machine.timer/cancelled
                :rf.machine.microstep/transition
                :rf.machine.spawn-all/spawned]]
      (is (true? (:machine? (l2/event-bundle-activity-flags {:other [(ev op)]})))
          (str op " should set :machine?")))
    ;; a spawn/timer-only bundle (NO accompanying bare :rf.machine/transition)
    ;; still surfaces the ◆ badge — the false-negative the bug produced.
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.machine.spawn/spawned)
                          (ev :rf.machine.timer/fired)]})]
      (is (true? (:machine? flags))))
    ;; guard against over-broad matching: a non-machine op stays false.
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.event/dispatched)]})]
      (is (false? (:machine? flags))))))

(deftest event-bundle-activity-flags-http-test
  (testing ":rf.http/* operation → http? true"
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.http/managed-request-settle)]})]
      (is (true? (:http? flags)))))

  (testing ":http/* legacy operation → http? true"
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :http/request-success)]})]
      (is (true? (:http? flags))))))

(deftest event-bundle-activity-flags-timer-test
  (testing ":rf.timer/* operation → timer? true"
    (let [flags (l2/event-bundle-activity-flags
                 {:other [(ev :rf.timer/fired)]})]
      (is (true? (:timer? flags)))))

  (testing "cascade source :after-timer alone (no :rf.timer trace) → timer? true"
    ;; The runtime stamps :source :after-timer on machine-:after dispatches;
    ;; the bucket projection (`source->bucket`) maps both `:after-timer` and
    ;; `:always` onto the `:timer` chrome bucket so the flag lights up
    ;; without an additional `:rf.timer/*` trace.
    (let [flags (l2/event-bundle-activity-flags (cascade-with-source :after-timer))]
      (is (true? (:timer? flags))))
    (let [flags (l2/event-bundle-activity-flags (cascade-with-source :always))]
      (is (true? (:timer? flags))))))

(deftest event-bundle-activity-flags-fx-emit-test
  (testing "cascade source :fx-dispatch / :fx-dispatch-later / :machine-action → fx-emit? true"
    ;; Per rf2-1ve9h: all three fx-event-bundle kinds project onto the
    ;; `:fx-emit` chrome bucket.
    (let [flags (l2/event-bundle-activity-flags (cascade-with-source :fx-dispatch))]
      (is (true? (:fx-emit? flags))))
    (let [flags (l2/event-bundle-activity-flags (cascade-with-source :fx-dispatch-later))]
      (is (true? (:fx-emit? flags))))
    (let [flags (l2/event-bundle-activity-flags (cascade-with-source :machine-action))]
      (is (true? (:fx-emit? flags)))))

  (testing "cascade source :ui → fx-emit? false"
    (let [flags (l2/event-bundle-activity-flags (cascade-with-source :ui))]
      (is (false? (:fx-emit? flags))))))

(deftest event-bundle-activity-flags-mixed-test
  (testing "multiple activity classes light up independently"
    (let [c (-> (cascade-with-source :fx-dispatch)
                (assoc :other [(ev :rf.machine/transition)
                               (ev :rf.http/managed-request-settle)
                               (ev :rf.error/handler-throw :op-type :error)]))
          flags (l2/event-bundle-activity-flags c)]
      (is (= {:error? true :machine? true :http? true
              :timer? false :fx-emit? true}
             flags)))))

;; ---- 4b. epoch-has-an-issue signal (rf2-b8guz) --------------------------
;;
;; `event-bundle-has-issue?` drives the L2 row's light-pink `:bg-issue-row`
;; wash. It must light up for EXACTLY the set the Issues ribbon/feed
;; aggregates — it reuses `issues-ribbon-helpers/issue-event?`, which is
;; severity-driven off `:op-type` (`:error` / `:warning` / `:info`), so a
;; lifecycle / success-path trace (no severity `:op-type`) is NOT an issue.

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

  (testing "an advisory trace (`:op-type :info`) in :other → true — the
            wash covers the FULL Issues set (error + warning + advisory),
            matching the Issues ribbon/feed"
    (is (true? (l2/event-bundle-has-issue?
                {:other [(ev :rf.ssr/hydration-mismatch :op-type :info)]}))))

  (testing "the cascade's legacy :errors slot alone → true (defence in
            depth for synthetic / older traces that populate it directly)"
    (is (true? (l2/event-bundle-has-issue?
                {:errors [{:operation :rf.error/no-such-fx}]}))))

  (testing "an issue mixed with lifecycle chatter still lights up"
    (let [c (-> (cascade-with-source :fx-dispatch)
                (assoc :other [(ev :rf.machine/transition :op-type :rf.machine)
                               (ev :rf.error/handler-exception :op-type :error)]))]
      (is (true? (l2/event-bundle-has-issue? c))))))

;; ---- 5. activity-badges projection --------------------------------------

(deftest activity-badges-empty-test
  (testing "a quiet cascade yields []"
    (is (= [] (l2/activity-badges {})))
    (is (= [] (l2/activity-badges nil)))
    (is (= [] (l2/activity-badges (cascade-with-source :ui))))))

(deftest activity-badges-single-test
  (testing "one flag → one badge"
    (is (= ["⚠"]
           (l2/activity-badges {:errors [{:operation :rf.error/throw}]})))
    (is (= ["◆"]
           (l2/activity-badges {:other [(ev :rf.machine/transition)]})))
    (is (= ["🌐"]
           (l2/activity-badges {:other [(ev :rf.http/managed-request-settle)]})))
    (is (= ["⚡"]
           (l2/activity-badges (cascade-with-source :fx-dispatch))))
    (is (= ["⏲"]
           (l2/activity-badges (cascade-with-source :after-timer))))))

(deftest activity-badges-ordered-test
  (testing "badges render in the canonical order ⚠ ◆ 🌐 ⚡ ⏲"
    (let [c (-> (cascade-with-source :fx-dispatch)
                (assoc :other [(ev :rf.timer/fired)
                               (ev :rf.http/managed-request-settle)
                               (ev :rf.machine/transition)
                               (ev :rf.error/handler-throw :op-type :error)]))]
      (is (= ["⚠" "◆" "🌐" "⚡" "⏲"]
             (l2/activity-badges c))))))

(deftest activity-badges-glyph-mapping-test
  (testing "every flag maps to its canonical glyph"
    (is (= "⚠" (get l2/activity-badge-glyphs :error?)))
    (is (= "◆" (get l2/activity-badge-glyphs :machine?)))
    (is (= "🌐" (get l2/activity-badge-glyphs :http?)))
    (is (= "⚡" (get l2/activity-badge-glyphs :fx-emit?)))
    (is (= "⏲" (get l2/activity-badge-glyphs :timer?))))

  (testing "the mapping covers exactly the five activity flags"
    (is (= #{:error? :machine? :http? :fx-emit? :timer?}
           (set (keys l2/activity-badge-glyphs))))))

;; ---- 6. activity tooltip ------------------------------------------------

(deftest activity-badges-tooltip-test
  (testing "nil when no badges are present"
    (is (nil? (l2/activity-badges-tooltip {})))
    (is (nil? (l2/activity-badges-tooltip nil)))
    (is (nil? (l2/activity-badges-tooltip (cascade-with-source :ui)))))

  (testing "human-readable cluster description"
    (let [c (-> (cascade-with-source :fx-dispatch)
                (assoc :other [(ev :rf.machine/transition)
                               (ev :rf.http/managed-request-settle)
                               (ev :rf.error/handler-throw :op-type :error)]))
          tip (l2/activity-badges-tooltip c)]
      (is (string? tip))
      (is (re-find #"^Activity:" tip))
      (is (re-find #"issues raised" tip))
      (is (re-find #"machine transition" tip))
      (is (re-find #"HTTP activity" tip))
      (is (re-find #"fx-emit child" tip))))

  (testing "single-badge tooltip"
    (is (= "Activity: issues raised"
           (l2/activity-badges-tooltip
            {:errors [{:operation :rf.error/throw}]})))))
