(ns re-frame.elision-pr-str-bytes-cljs-test
  "rf2-2rtt6.135 — `re-frame.elision/pr-str-bytes` counts UTF-8 BYTES on BOTH
  hosts.

  THE DEFECT this pins against. The helper was

      #?(:clj  (count (.getBytes ^String (pr-str v) \"UTF-8\"))
         :cljs (count (pr-str v)))

  — bytes on the JVM, UTF-16 CODE UNITS in the browser, because `count` on a
  CLJS string is `.-length`. One figure, two rulers. The figure is PUBLISHED
  (every `:rf.size/large-elided` marker's `:bytes` slot, which Spec-Schemas
  §`:rf/elision-marker` types as the `pr-str` byte count and 009 §Size elision
  makes a per-field MUST) and it is READ as a threshold (against
  `:rf.size/threshold-bytes`, deciding whether an undeclared string leaf fires
  `:rf.warning/large-value-unschema'd`). So the same app-db leaf could warn on
  the JVM and pass silently in the browser, and a CLJS marker under-reported
  its payload by up to 3x — 4x on astral.

  WHY IT SURVIVED, and why the fixtures below look the way they do. Code units
  and UTF-8 bytes agree EXACTLY for ASCII, so the wrong expression prints the
  right number on an ASCII payload and a green suite never notices. AN
  ASCII-ONLY TEST CANNOT SEE THIS BUG. The three fixtures are therefore chosen
  so that all three have the SAME `pr-str` code-unit length (42) and THREE
  DIFFERENT byte lengths:

  | fixture      | code units | code points | UTF-8 bytes | `pr-str` bytes |
  |--------------|-----------:|------------:|------------:|---------------:|
  | `ascii-40`   |         40 |          40 |          40 |             42 |
  | `em-dash-40` |         40 |          40 |         120 |            122 |
  | `astral-20`  |         40 |          20 |          80 |             82 |

  Under the DEFECT all three answer 42. Under the fix they answer 42 / 122 / 82
  — and `astral-20` additionally separates code POINTS from code units, so a
  \"count code points instead\" mis-repair is caught too. `ascii-40` pins the
  OPPOSITE direction: a repair that inflated unconditionally (a fixed
  multiplier, a mis-hinted encoder) reds there.

  DUAL-RUNTIME: `*_cljs_test.cljc` so both the shadow-cljs `:node-test` build
  (`npm run test:cljs`) and the JVM `clojure -M:test` runner run it. Its green
  run on both IS the agreement between the arms — mutating either arm alone
  reds one host and leaves the other green."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The discriminating fixture set.
;;
;; The two non-ASCII fixtures are built from EXPLICIT CODE POINTS rather than
;; written as literal characters, deliberately. The bug is invisible to ASCII
;; payloads, so the fixtures cannot be ASCII -- but a literal astral character in
;; source is a surrogate PAIR that an editor, a re-encoding tool or a careless
;; normalisation pass can silently mangle into two lone halves, which would
;; change what these tests measure without changing what they look like. Naming
;; the code points keeps the source pure ASCII and makes the surrogate pair
;; explicit instead of invisible.
;;
;;   U+2014  EM DASH                  1 code unit  / 1 code point / 3 UTF-8 bytes
;;   U+1D11E MUSICAL SYMBOL G CLEF -- ASTRAL, hence the surrogate pair
;;           U+D834 U+DD1E:           2 code units / 1 code point / 4 UTF-8 bytes
;; ---------------------------------------------------------------------------

(def ^:private em-dash (str (char 0x2014)))

;; The surrogate PAIR for U+1D11E, spelled out. `str` of the two halves is the
;; same 2-code-unit string a literal would produce, on both hosts.
(def ^:private g-clef (str (char 0xD834) (char 0xDD1E)))

(def ^:private ascii-40   (apply str (repeat 40 "x")))
(def ^:private em-dash-40 (apply str (repeat 40 em-dash)))
(def ^:private astral-20  (apply str (repeat 20 g-clef)))

(deftest fixtures-are-actually-discriminating
  (testing "the fixture set separates code units, code points and bytes — if
            this deftest ever passed vacuously the rest of the file would prove
            nothing"
    ;; Same code-unit length, so the DEFECTIVE expression answers identically
    ;; for all three. This is the assertion that makes the file a real test
    ;; rather than three numbers that happen to be right.
    (is (= 40 (count ascii-40) (count em-dash-40) (count astral-20))
        "all three fixtures are 40 UTF-16 code units")
    (is (= 42
           (count (pr-str ascii-40))
           (count (pr-str em-dash-40))
           (count (pr-str astral-20)))
        "and all three pr-str to 42 code units (40 + two quote characters) —
         so `(count (pr-str v))` cannot tell them apart")))

(deftest pr-str-bytes-counts-utf8-bytes-not-code-units
  (testing "rf2-2rtt6.135: UTF-8 bytes on BOTH hosts. Under the pre-fix `:cljs`
            arm every one of these answered 42"
    (is (= 42 (elision/pr-str-bytes ascii-40))
        "ASCII: bytes and code units agree exactly — the opposite-direction pin")
    (is (= 122 (elision/pr-str-bytes em-dash-40))
        "40 em-dashes are 120 bytes, not 40; + 2 quote bytes")
    ;; 82 also proves the surrogate PAIRS survive intact into the encoder: two
    ;; LONE surrogates encode as replacement characters at 3 bytes each, which
    ;; would read 2 + (20 * 6) = 122 here, not 82.
    (is (= 82 (elision/pr-str-bytes astral-20))
        "20 astral chars are 80 bytes across 40 code units; + 2 quote bytes"))

  (testing "the relationship, stated as the law rather than as three constants:
            UTF-8 bytes are NEVER FEWER than UTF-16 code units, and are STRICTLY
            MORE for a non-ASCII payload. This is why the correction can only
            ever TIGHTEN — no leaf that warned before can fall silent now"
    (doseq [[label s] [["ascii" ascii-40] ["em-dash" em-dash-40] ["astral" astral-20]]]
      (is (>= (elision/pr-str-bytes s) (count (pr-str s)))
          (str label ": bytes >= code units, always")))
    (is (> (elision/pr-str-bytes em-dash-40) (count (pr-str em-dash-40)))
        "em-dash: STRICTLY more")
    (is (> (elision/pr-str-bytes astral-20) (count (pr-str astral-20)))
        "astral: STRICTLY more")
    (is (= (elision/pr-str-bytes ascii-40) (count (pr-str ascii-40)))
        "ascii: exactly equal — which is precisely why the defect failed OPEN")))

(deftest pr-str-bytes-measures-the-printed-form
  (testing "the name says `pr-str`: the delimiters count, an embedded quote
            counts as its two-character escape, and a non-string prints bare"
    (is (= 2 (elision/pr-str-bytes "")) "the two quote characters")
    ;; (pr-str "a\"b") is the 6-character text: " a \ " b "
    (is (= 6 (elision/pr-str-bytes "a\"b")))
    (is (= 2 (elision/pr-str-bytes 42)))
    (is (= 6 (elision/pr-str-bytes {:a 1})))))

;; ---------------------------------------------------------------------------
;; The PUBLISHED half: the marker's `:bytes` slot.
;; ---------------------------------------------------------------------------

(defn- install-large!
  "Seed the default frame's elision registry through the EP-0025 commit-plane
  classification effect path — the same registry write a `reg-event` returning
  `:large` alongside `:db` performs. Mirrors `elision_test.clj`'s
  `install-class!`."
  [large]
  (frame/swap-runtime-db! :rf/default
    (fn [rt] (elision/apply-classification-effects rt {:large (mapv vec large)}))))

(deftest marker-publishes-utf8-bytes
  (testing "rf2-2rtt6.135: `->marker`'s `:bytes` is the byte count Spec-Schemas
            §`:rf/elision-marker` types it as, on both hosts"
    (let [body (:rf.size/large-elided (elision/->marker em-dash-40 [:user :bio] {}))]
      (is (= 122 (:bytes body))
          "the published figure is bytes; it read 42 on CLJS before the fix")
      (is (= :string (:type body)))
      (is (= [:user :bio] (:path body))))
    (let [body (:rf.size/large-elided (elision/->marker astral-20 [:user :bio] {}))]
      (is (= 82 (:bytes body))
          "astral payload publishes 82 bytes, not 42 code units"))))

(deftest walker-published-marker-carries-utf8-bytes
  (testing "and through the REAL walker on a live frame, not just the marker
            constructor — a declared-`:large` path"
    (install-large! [[:user :bio]])
    (let [out  (rf/elide-wire-value {:user {:bio em-dash-40}})
          body (:rf.size/large-elided (get-in out [:user :bio]))]
      (is (some? body) "the declared path elided to a marker")
      (is (= 122 (:bytes body))
          "the wire marker an off-box agent reads publishes BYTES on both hosts"))))

;; ---------------------------------------------------------------------------
;; The ENFORCED half: the `:rf.size/threshold-bytes` comparison.
;;
;; NOTE what is and is not being proven. Per Privacy.md the threshold is
;; ADVISORY, NOT A CAP, and 009's error-catalogue row records the recovery as
;; `:warned-and-replaced`: an over-threshold value at an UNDECLARED path fires
;; the warning and then SHIPS UNCHANGED. So this correction cannot lose data —
;; it can only make a diagnostic fire that should always have fired. The
;; always-on assertions (the value rides through intact either way) are the
;; load-bearing ones and sit OUTSIDE the debug-gated arms, matching the posture
;; split `elision_test.clj`'s ns docstring sets out (rf2-d2841): the warning is
;; a dev-only `trace/emit!` site and is the threshold's ONLY observable, so
;; every assertion that reads it must be gated.
;; ---------------------------------------------------------------------------

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- unschema'd-warnings [traces]
  (filterv #(= :rf.warning/large-value-unschema'd (:operation %)) @traces))

(deftest threshold-compares-utf8-bytes-on-both-hosts
  (testing "rf2-2rtt6.135: a payload whose CODE-UNIT length is UNDER the
            threshold but whose BYTE length is OVER it now warns. At a
            threshold of 50 all three fixtures are 42 code units — under it —
            but em-dash is 122 bytes and astral is 82"
    (elision/clear-warning-cache!)
    (let [traces (collect-traces! ::over)
          out    (rf/elide-wire-value {:user {:bio em-dash-40}}
                                      {:rf.size/threshold-bytes 50})]
      ;; ALWAYS-ON. Advisory, not a cap: the value returns verbatim whether or
      ;; not it warned. A correction that started ELIDING here would be the
      ;; actual defect.
      (is (= em-dash-40 (get-in out [:user :bio]))
          "over-threshold undeclared values ship UNCHANGED — advisory, not a cap")
      (when interop/debug-enabled?
        (let [warnings (unschema'd-warnings traces)]
          (is (= 1 (count warnings))
              "122 bytes > 50 warns. Pre-fix on CLJS this measured 42 and was SILENT")
          (is (= [:user :bio] (get-in (first warnings) [:tags :path])))
          (is (= 122 (get-in (first warnings) [:tags :bytes]))
              "and the figure the warning reports is bytes")))
      (rf/unregister-listener! :trace ::over)))

  (testing "the astral payload crosses the same threshold on the same evidence"
    (elision/clear-warning-cache!)
    (let [traces (collect-traces! ::astral)
          out    (rf/elide-wire-value {:user {:bio astral-20}}
                                      {:rf.size/threshold-bytes 50})]
      (is (= astral-20 (get-in out [:user :bio])))
      (when interop/debug-enabled?
        (let [warnings (unschema'd-warnings traces)]
          (is (= 1 (count warnings)) "82 bytes > 50 warns")
          (is (= 82 (get-in (first warnings) [:tags :bytes])))))
      (rf/unregister-listener! :trace ::astral)))

  (testing "OPPOSITE DIRECTION — the ASCII control of the SAME code-unit length
            stays UNDER the same threshold. Without this, a repair that simply
            inflated every measurement would pass"
    (elision/clear-warning-cache!)
    (let [traces (collect-traces! ::under)
          out    (rf/elide-wire-value {:user {:bio ascii-40}}
                                      {:rf.size/threshold-bytes 50})]
      (is (= ascii-40 (get-in out [:user :bio])))
      (when interop/debug-enabled?
        (is (= [] (unschema'd-warnings traces))
            "42 bytes < 50 stays silent, exactly as before the fix"))
      (rf/unregister-listener! :trace ::under))))

(deftest threshold-zero-still-disables-auto-detect
  (testing "the correction did not disturb the documented `0 disables` arm —
            no `pr-str-bytes` walk happens at all"
    (elision/clear-warning-cache!)
    (let [traces (collect-traces! ::zero)
          out    (rf/elide-wire-value {:user {:bio em-dash-40}}
                                      {:rf.size/threshold-bytes 0})]
      (is (= em-dash-40 (get-in out [:user :bio])))
      (when interop/debug-enabled?
        (is (= [] (unschema'd-warnings traces))))
      (rf/unregister-listener! :trace ::zero))))
