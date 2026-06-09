(ns re-frame.mcp-conformance.progress-notification-test
  "`notifications/progress` streaming gate (rf2-i3ffz F-GAP-1). Split out
  of `wire_vocab_test.clj` by rf2-7ckmwx.

  re-frame2-pair-mcp's `subscribe` streaming tool emits exactly one
  `notifications/progress` per matching batch (per `NAMING.md`
  §\"subscribe / unsubscribe\"). Before this gate landed:

    - `end-to-end-re-frame2-pair.cjs` exercised `subscribe` only in degraded
      mode (returns `isError: true` with `:nrepl-port-not-found`); the
      streaming wire-shape was never asserted.
    - `live-re-frame2-pair-overflow.cjs` only exercised `eval-cljs`.
    - No test observed a real `notifications/progress` frame.

  The pin shape mirrors the ReFrame2PairOverflowBody cross-encoding posture:

    1. fixture validates against `ReFrame2PairProgressNotificationParams`.
    2. literal `\"notifications/progress\"` appears in re-frame2-pair-mcp's emit
       site (`tools/subscribe.cljs`).
    3. the JS-side hand-rolled assertion in
       `live-re-frame2-pair-subscribe.cjs` carries a substring for every
       required field on the Malli schema (the cross-encoding gate;
       a tightening on one side without the other trips this test)."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-conformance.fixtures :as fx]
            [re-frame.mcp-conformance.wire-vocab.schemas
             :refer [ReFrame2PairProgressNotificationParams]]))

(def ^:private re-frame2-pair-progress-fixture
  "Canonical re-frame2-pair-mcp `notifications/progress` params shape — what
  `subscribe` emits per tick. The `:message` slot is the EDN-printed
  batch (variable per-tick); `:_meta.data` carries the structured
  counts + overflow-reason slot."
  {:progressToken "probe-token-42"
   :progress      1
   :message       "{:sub-id \"sub-abc\" :events [] :dropped-events 0 :dropped-bytes 0}"
   :_meta         {:data {:dropped-events  0
                          :dropped-bytes   0
                          :overflow-reason nil}}})

(deftest re-frame2-pair-progress-fixture-conforms-to-schema
  (is (m/validate ReFrame2PairProgressNotificationParams re-frame2-pair-progress-fixture)
      (str "Fixture for notifications/progress failed schema validation:\n"
           (me/humanize
             (m/explain ReFrame2PairProgressNotificationParams re-frame2-pair-progress-fixture)))))

(deftest re-frame2-pair-progress-overflow-reason-variant-conforms
  ;; The :overflow-reason slot is `[:maybe :string]` — it carries
  ;; either a pr-str'd keyword (`:max-buffered-events` /
  ;; `:max-buffered-bytes`) or nil. Validate both shapes.
  (is (m/validate
        ReFrame2PairProgressNotificationParams
        (assoc-in re-frame2-pair-progress-fixture
                  [:_meta :data :overflow-reason]
                  ":max-buffered-events"))
      "overflow-reason as pr-str'd keyword MUST validate")
  (is (m/validate
        ReFrame2PairProgressNotificationParams
        (assoc-in re-frame2-pair-progress-fixture
                  [:_meta :data :overflow-reason]
                  ":max-buffered-bytes"))
      "overflow-reason as pr-str'd keyword (bytes) MUST validate"))

(deftest re-frame2-pair-progress-rejects-missing-required-slots
  ;; Tightening: an emit missing `:progressToken`, `:progress`, or
  ;; `:_meta.data` MUST fail. The slot is the load-bearing contract; a
  ;; future regression that drops one would silently break agent-host
  ;; correlation (progressToken) or polling cadence (progress).
  (is (not (m/validate ReFrame2PairProgressNotificationParams
                       (dissoc re-frame2-pair-progress-fixture :progressToken)))
      "missing :progressToken MUST fail")
  (is (not (m/validate ReFrame2PairProgressNotificationParams
                       (dissoc re-frame2-pair-progress-fixture :progress)))
      "missing :progress MUST fail")
  (is (not (m/validate ReFrame2PairProgressNotificationParams
                       (dissoc re-frame2-pair-progress-fixture :_meta)))
      "missing :_meta MUST fail")
  (is (not (m/validate ReFrame2PairProgressNotificationParams
                       (update-in re-frame2-pair-progress-fixture [:_meta] dissoc :data)))
      "missing :_meta.data MUST fail")
  (is (not (m/validate ReFrame2PairProgressNotificationParams
                       (update-in re-frame2-pair-progress-fixture [:_meta :data] dissoc :dropped-events)))
      "missing :_meta.data.dropped-events MUST fail"))

(deftest re-frame2-pair-progress-emit-literal-in-source
  ;; Source-text pin: the literal `"notifications/progress"` MUST
  ;; appear in re-frame2-pair-mcp's `subscribe.cljs` emit site. The MCP spec
  ;; pins the method name; a regression that emitted
  ;; `"notifications/progressing"` or moved the emit to a non-streaming
  ;; method would surface here.
  ;;
  ;; NOTE: this literal lives INSIDE a string slot (`{:method
  ;; "notifications/progress"}`), so the usual
  ;; `strip-comments-and-strings` discriminator can't be applied — it
  ;; would zero out the string body we need to grep. Raw `str/includes?`
  ;; against the source is the right tool: docstrings on this file do
  ;; not mention the method name, so a false positive from a comment
  ;; cannot happen.
  (let [rel "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/subscribe.cljs"
        src (fx/read-source rel)]
    (is (str/includes? src "\"notifications/progress\"")
        (str "literal \"notifications/progress\" missing from " rel
             ". The emit moved or the method name drifted."))))

(def ^:private live-re-frame2-pair-subscribe-js-rel
  "Relative path to the hand-rolled JS `notifications/progress`
  assertion. Mirrors `live-re-frame2-pair-overflow-js-rel` for the
  ReFrame2PairOverflowBody cross-encoding gate."
  "tools/mcp-conformance/test/live-re-frame2-pair-subscribe.cjs")

(def ^:private re-frame2-pair-progress-js-required-grep-markers
  "Substrings the JS `assertProgressParams` MUST contain to pin every
  JS-OBSERVABLE required field on `ReFrame2PairProgressNotificationParams`.
  Each entry is `[malli-path js-substring]` — the path for error
  reporting, the substring as the grep target. Mirrors the
  ReFrame2PairOverflowBody table: a JS-observable field added to
  the Malli schema MUST add a row here; one removed MUST remove a row.

  `:progressToken` is INTENTIONALLY ABSENT (rf2-ee38b.20 correctness
  fix). The MCP SDK strips `progressToken` out of `notification.params`
  before invoking the `onprogress` callback, so the JS-side
  `assertProgressParams` literally cannot observe the slot — a grep pin
  here would assert a JS check that can never fail (it would only ever
  see a value the test injected). The Malli schema still REQUIRES
  `:progressToken` (the WIRE carries it; the fixture test
  `re-frame2-pair-progress-fixture-conforms` + the negative-shape
  `dissoc :progressToken` test pin that JVM-side). The slot's wire
  presence is asserted operationally by the SDK's own numeric-token
  routing: a renamed / dropped token fails to correlate, zero frames
  arrive, and the live harness's \"at least one frame\" gate trips. This
  table pins only the slots the JS callback can actually inspect."
  [[":progress : int"
    "'progress',       (v) => typeof v === 'number',"]
   [":message : string"
    "'message',        (v) => typeof v === 'string',"]
   [":_meta : map"
    "'_meta',          (v) => v && typeof v === 'object',"]
   [":_meta.data : map"
    "params._meta.data MUST be map"]
   [":_meta.data.dropped-events : nat-int"
    "'dropped-events', (v) => typeof v === 'number' && v >= 0"]
   [":_meta.data.dropped-bytes : nat-int"
    "'dropped-bytes',  (v) => typeof v === 'number' && v >= 0"]
   [":_meta.data.overflow-reason : maybe-string"
    "'overflow-reason'"]])

(deftest js-assertProgressParams-pins-every-re-frame2-pair-progress-required-field
  ;; Cross-encoding sanity gate (rf2-i3ffz F-GAP-1, mirrors rf2-0zqox
  ;; for ReFrame2PairOverflowBody). For every required field on the Malli
  ;; `ReFrame2PairProgressNotificationParams` schema, the JS
  ;; `assertProgressParams` function MUST carry a substring that
  ;; asserts the same shape. Missing fields trip this gate with the
  ;; field name in the error.
  (let [js-src (fx/read-source live-re-frame2-pair-subscribe-js-rel)]
    (doseq [[field grep-pattern] re-frame2-pair-progress-js-required-grep-markers]
      (testing (str "JS assertProgressParams pins field " field)
        (is (str/includes? js-src grep-pattern)
            (str "Field `" field
                 "` (Malli `ReFrame2PairProgressNotificationParams`) is not "
                 "pinned by the JS `assertProgressParams` in "
                 live-re-frame2-pair-subscribe-js-rel
                 ". Looked for substring: " (pr-str grep-pattern)
                 ".\nIf you tightened the Malli schema, mirror the "
                 "change in the JS assertion; if you loosened it, "
                 "remove the entry from "
                 "`re-frame2-pair-progress-js-required-grep-markers`."))))))
