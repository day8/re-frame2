(ns day8.re-frame2-xray.testbed-managed-http-frame-scope-test
  "Guard the FRAME SCOPE of the MANAGED-HTTP testbed's registry seams
  (rf2-s4dp, parent rf2-o8ek).

  ## The problem

  PR #9195 made managed-HTTP cancellation frame-scoped: the in-flight and
  actor-in-flight indexes key on `(frame-id, id)` rather than the raw id,
  read off the `:frame` stamp the live transport puts on every handle it
  records. To avoid a flag day it deliberately KEPT the frame-less arities
  as documented ANY-FRAME seams.

  That combination has a sharp edge: a call site that was correct BEFORE
  #9195 is silently wrong after it. A handle recorded without a `:frame`
  keys under `nil` — a scope no frame-scoped abort can reach — and nothing
  errors. `tools/xray/testbeds/managed_http/core.cljs` seeds its handles by
  hand (a real Fetch against a dev-http static server resolves instantly,
  leaving nothing observably in-flight), so it is exactly such a site, and
  its step 5 dispatches the live `:rf.http/managed-abort` fx at the handle
  step 1 seeded. Measured on the pre-fix source with a JVM registry probe:

      {:keyed-under ([nil :xray/in-flight]),
       :any-frame-finds? true,
       :real-frame-finds? false,
       :managed-abort-hits? false}

  — the abort step resolved NOTHING, and did so silently. An any-frame
  sweep still \"works\" in a single-frame testbed, which is why no existing
  gate caught it.

  ## Why this guard is source-text rather than a live registry probe

  `day8/re-frame2-http` is NOT on `tools/xray`'s classpath (not even under
  the `:test` alias), so this suite cannot drive the real registry, and the
  testbed is a `.cljs` file compiled only by its shadow-cljs browser build,
  which carries no spec.cjs for this path. Slurping the committed source
  and asserting the frame-scoping laws is therefore the reachable witness —
  the same posture as `coverage_matrix_metadata_test.clj` and the other
  source-text guards in this directory.

  ## What this guard enforces

  Two specific laws, stated over EVERY `record-in-flight!` call in the
  testbed rather than over three hand-named functions, so a NEW seeding fx
  inherits them:

    1. An fx that records a registry handle must not discard its fx
       context (`_frame-ctx`), and every `record-in-flight!` call must
       stamp `:frame`.
    2. Cleanup and actor-destroy must use the FRAME-EXACT forms, never the
       any-frame seams (`clear-in-flight!` 1-arg, `abort-on-actor-destroy`
       1-arg), and a deferred `rf/dispatch` from inside an `:abort-fn` must
       carry `{:frame frame}` — by then there is no ambient frame, and in
       re-frame2 frame identity is carried, not found.
    3. Every recorded handle's `:abort-fn` must actually retire its OWN
       request-id from the issuing frame. `abort-on-actor-destroy` clears
       the ACTOR slot eagerly and then delegates the request-index half to
       each handle's closure (registry.cljc: \"the per-handle request-id
       cleanup is owned by `clear-in-flight!` ... called inside the
       `abort-fn` closure\"), so a no-op closure leaves a GHOST — an
       actor-destroy that empties the actor index while the request index
       keeps entries for an actor that no longer exists."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---- file resolution ----------------------------------------------------

(def ^:private testbed-rel
  ["tools" "xray" "testbeds" "managed_http" "core.cljs"])

(defn- find-repo-root
  "Walk up from `user.dir` until the testbed source resolves — robust
  whether the `:test` alias runs from `tools/xray` or the repo root.
  Mirrors `coverage_matrix_metadata_test.clj`."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when (nil? dir)
      (throw (ex-info (str "testbed-managed-http-frame-scope: could not locate "
                           (str/join "/" testbed-rel)
                           " walking up from " (System/getProperty "user.dir"))
                      {})))
    (if (.exists (apply io/file dir testbed-rel))
      dir
      (recur (.getParentFile dir)))))

(def ^:private source
  (delay (slurp (apply io/file (find-repo-root) testbed-rel))))

;; ---- balanced-form extraction -------------------------------------------
;;
;; Text scanning, not `read-string`: the source is CLJS carrying
;; auto-resolved keywords and hiccup metadata, and we only need each fx
;; form's own text. The scanner tracks strings, escapes and line comments
;; so a paren inside any of them cannot unbalance the form.

(defn- balanced-form
  "The substring of `s` starting at `start` (which must index a `(`) up to
  and including its matching `)`. Returns nil when unbalanced."
  [^String s ^long start]
  (loop [i     start
         depth 0
         in-str? false
         in-cmt? false]
    (if (>= i (.length s))
      nil
      (let [c (.charAt s i)]
        (cond
          in-cmt? (recur (inc i) depth in-str? (not= c \newline))
          in-str? (cond
                    (= c \\) (recur (+ i 2) depth true false)
                    (= c \") (recur (inc i) depth false false)
                    :else    (recur (inc i) depth true false))
          (= c \\) (recur (+ i 2) depth false false)   ; char literal, e.g. \(
          (= c \") (recur (inc i) depth true false)
          (= c \;) (recur (inc i) depth false true)
          (= c \() (recur (inc i) (inc depth) false false)
          (= c \)) (if (= depth 1)
                     (subs s start (inc i))
                     (recur (inc i) (dec depth) false false))
          :else    (recur (inc i) depth false false))))))

(defn- fx-form
  "The full text of the `(rf/reg-fx <fx-id> ...)` form registering `fx-id`."
  [fx-id]
  (let [s     @source
        needle (str "(rf/reg-fx " fx-id)
        idx   (str/index-of s needle)]
    (when idx (balanced-form s idx))))

(defn- fx-forms
  "Every `(rf/reg-fx ...)` form in the testbed, as text."
  []
  (let [s @source]
    (loop [from 0 acc []]
      (if-let [idx (str/index-of s "(rf/reg-fx " from)]
        (if-let [form (balanced-form s idx)]
          (recur (+ idx (count form)) (conj acc form))
          (recur (+ idx 11) acc))
        acc))))

;; A `record-in-flight!` call site is the thing these laws bind. Matching on
;; the CALL rather than on a function name is what makes a newly added
;; seeding fx inherit the guard instead of escaping it.
(defn- records-handle? [form] (str/includes? form "record-in-flight!"))

(defn- abort-fn-form
  "The text of the closure a form hands `record-in-flight!` under
  `:abort-fn` — the substring from the first `(` after the key to its
  matching `)`. Returns nil when the key is absent or unbalanced."
  [form]
  (when-let [k (str/index-of form ":abort-fn")]
    (when-let [open (str/index-of form "(" k)]
      (balanced-form form open))))

;; ---- the extractor itself is under test ---------------------------------
;;
;; A source-text guard whose scanner silently returned nil would pass every
;; assertion below by vacuity, so pin the scanner before trusting it.

(deftest balanced-form-scanner-works
  (testing "matching paren, ignoring parens in strings, comments and char literals"
    (is (= "(a b)"            (balanced-form "(a b) trailing" 0)))
    (is (= "(a (b c) d)"      (balanced-form "(a (b c) d)" 0)))
    (is (= "(a \")\" b)"      (balanced-form "(a \")\" b)" 0)))
    (is (= "(a ; )\nb)"       (balanced-form "(a ; )\nb)" 0)))
    (is (= "(a \\) b)"        (balanced-form "(a \\) b)" 0)))
    (is (nil? (balanced-form "(a b" 0)) "unbalanced input returns nil")))

(deftest abort-fn-extractor-works
  (testing "the closure is lifted out of the handle map, not the whole map"
    (is (= "(fn [r] (clear! r))"
           (abort-fn-form "{:url u :abort-fn (fn [r] (clear! r)) :frame f}")))
    (is (= "(fn [_reason] nil)"
           (abort-fn-form "{:abort-fn (fn [_reason] nil) :frame frame}"))))
  (testing "and a handle with no :abort-fn reports nil rather than passing"
    (is (nil? (abort-fn-form "{:url u :frame f}")))))

(deftest testbed-source-is-readable
  (testing "the testbed source is present and non-trivial"
    (is (str/includes? @source "managed-http.core"))
    (is (> (count @source) 5000)
        "a truncated or empty read would make every law below vacuous"))
  (testing "the fx forms the laws bind are actually found"
    (is (seq (fx-forms)))
    (is (some? (fx-form ":managed-http/seed-in-flight")))
    (is (some? (fx-form ":managed-http/issue-as-actor")))
    (is (some? (fx-form ":managed-http/destroy-actor")))
    (is (>= (count (filter records-handle? (fx-forms))) 2)
        "both seeding fxs record a registry handle")))

;; ---- law 1: every recorded handle carries its issuing frame -------------

(deftest recorded-handles-are-frame-stamped
  (doseq [form (filter records-handle? (fx-forms))]
    (testing "an fx that records a registry handle keeps its fx context"
      (is (not (str/includes? form "_frame-ctx"))
          (str "a `record-in-flight!` fx discards its frame context; the "
               "issuing frame is available on it and MUST be stamped on the "
               "handle. Offending form:\n" form)))
    (testing "and stamps :frame on the handle it records"
      (is (re-find #":frame\s+frame" form)
          (str "handle recorded without a `:frame` stamp keys under nil — a "
               "scope no frame-scoped abort can reach. Offending form:\n"
               form)))))

;; ---- law 2: cleanup and destroy use the frame-exact forms ---------------

(deftest cleanup-paths-are-frame-exact
  (testing "the seeded handle's abort-fn clears frame-exactly"
    (let [form (fx-form ":managed-http/seed-in-flight")]
      (is (str/includes? form "clear-in-flight-in-frame!"))
      (is (not (re-find #"clear-in-flight!\s+request-id" form))
          "the one-arg `clear-in-flight!` is an ANY-FRAME sweep: it would
           deregister a sibling frame's live slot under the same id")
      (testing "and its deferred dispatch carries the frame"
        ;; The abort-fn runs later, with no ambient frame around it; a bare
        ;; `rf/dispatch` in there raises :rf.error/no-frame-context.
        (is (re-find #"\{:frame\s+frame\}" form)))))

  (testing "actor destroy uses the frame-scoped 2-arity"
    (let [form (fx-form ":managed-http/destroy-actor")]
      (is (re-find #"abort-on-actor-destroy\s+\(:frame\s+frame-ctx\)\s+actor-id"
                   form)
          "the 1-arity is the documented ANY-FRAME seam; it sweeps the
           actor-id in EVERY frame, which is the reach the frame-scoped
           keys removed from the abort half")))

  (testing "the reset fx may still clear globally — that is its job"
    ;; `clear-all-in-flight!` is global BY INTENT (the deck's reset button
    ;; drops every registry slot). Pinned so the law above is not misread as
    ;; banning every unscoped registry call.
    (is (str/includes? (fx-form ":managed-http/clear-registry")
                       "clear-all-in-flight!"))))

;; ---- law 3: every handle's abort-fn retires its own request-id ----------
;;
;; `abort-on-actor-destroy` dissocs the ACTOR slot eagerly and then walks the
;; handles calling `(:abort-fn handle)`. The request-index half of the
;; cleanup is owned by that closure and by nothing else, so a handle whose
;; abort-fn does nothing survives its own actor's destruction: the actor
;; index empties, the request index does not, and the deck's registry strip
;; shows request-ids belonging to an actor that is gone.
;;
;; Stated over EVERY recording form for the same reason laws 1 and 2 are: a
;; new seeding fx must inherit the obligation rather than escape it.

(deftest recorded-handles-retire-their-own-request-id
  (doseq [form (filter records-handle? (fx-forms))]
    (let [abort (abort-fn-form form)]
      (testing "every recorded handle carries an :abort-fn closure"
        (is (some? abort)
            (str "a handle recorded with no `:abort-fn` can never be retired "
                 "from the request index. Offending form:\n" form)))
      (testing "and that closure clears its own request-id, frame-exactly"
        (is (and (some? abort)
                 (str/includes? abort "clear-in-flight-in-frame!"))
            (str "`abort-on-actor-destroy` clears the ACTOR slot itself and "
                 "delegates the REQUEST-id half to this closure, so a no-op "
                 "abort-fn leaves a ghost handle in the request index after "
                 "its actor is destroyed (rf2-s4dp). Offending closure:\n"
                 (pr-str abort)))))))
