(ns day8.re-frame2-xray.testbed-managed-http-frame-scope-test
  "Guard the FRAME SCOPE of the MANAGED-HTTP testbed's registry seams.

  ## The problem

  Managed-HTTP cancellation is frame-scoped: the registry files every
  request under its issuing frame, read off the `:frame` stamp the live
  transport puts on every record it publishes, and looks requests up within
  a frame. The frame-less arities remain, as documented ANY-FRAME seams.

  That combination has a sharp edge. A handle recorded without a `:frame`
  is filed under `nil` — a scope no frame-scoped abort can reach — and
  nothing errors. `tools/xray/testbeds/managed_http/core.cljs` seeds its
  handles by hand (a real Fetch against a dev-http static server resolves
  instantly, leaving nothing observably in-flight), so it is exactly such a
  site, and its step 5 dispatches the live `:rf.http/managed-abort` fx at the
  handle step 1 seeded. Seeded without `:frame`, that handle is still found
  by an any-frame lookup but not by its real frame's, so the abort step
  resolves NOTHING, and silently. An any-frame sweep still \"works\" in a
  single-frame testbed, so only a frame-scope law catches it.

  ## Why this guard is source-text rather than a live registry probe

  `day8/re-frame2-http` is NOT on `tools/xray`'s classpath (not even under
  the `:test` alias), so this suite cannot drive the real registry, and the
  testbed is a `.cljs` file compiled only by its shadow-cljs browser build,
  which carries no spec.cjs for this path. Slurping the committed source
  and asserting the frame-scoping laws is therefore the reachable witness —
  the same posture as `coverage_matrix_metadata_test.clj` and the other
  source-text guards in this directory.

  ## What this guard enforces

  Two laws, the first stated over EVERY `record-in-flight!` call in the
  testbed rather than over hand-named functions, so a NEW seeding fx
  inherits it:

    1. An fx that records a registry handle must not discard its fx
       context (`_frame-ctx`), and every `record-in-flight!` call must
       stamp `:frame`.
    2. Actor destroy must use the FRAME-SCOPED form, never the any-frame
       seam (`abort-on-actor-destroy`'s 1-arity), and a deferred
       `rf/dispatch` from inside an `:abort-fn` must carry `{:frame frame}`
       — by then there is no ambient frame, and in re-frame2 frame identity
       is carried, not found.

  A seeded `:abort-fn` owes no cleanup: the registry drops every record it
  aborts."
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

;; A `record-in-flight!` call site is the thing law 1 binds. Matching on the
;; CALL rather than on a function name is what makes a newly added seeding fx
;; inherit the guard instead of escaping it.
(defn- records-handle? [form] (str/includes? form "record-in-flight!"))

(defn- recorded-handle
  "The text of `form`'s `record-in-flight!` call with its `:abort-fn` body
  cut out — the handle map's own keys and nothing nested in a callback, so
  an `{:frame frame}` dispatch opt inside the abort-fn cannot stand in for
  the handle's `:frame` stamp. nil when `form` records nothing."
  [form]
  (when-let [idx (str/index-of form "(rf.http.registry/record-in-flight!")]
    (when-let [call (balanced-form form idx)]
      (if-let [fn-idx (some-> (str/index-of call ":abort-fn")
                              (as-> i (str/index-of call "(fn" i)))]
        (let [abort-fn (balanced-form call fn-idx)]
          (str (subs call 0 fn-idx) (subs call (+ fn-idx (count abort-fn)))))
        call))))

;; ---- vacuity control ------------------------------------------------------

(deftest testbed-source-is-readable
  ;; Law 1 iterates the recording forms, so an empty extraction would pass it
  ;; by vacuity.
  (is (>= (count (filter records-handle? (fx-forms))) 2)
      "both seeding fxs record a registry handle"))

;; ---- law 1: every recorded handle carries its issuing frame -------------

(deftest recorded-handles-are-frame-stamped
  (doseq [form (filter records-handle? (fx-forms))]
    (testing "an fx that records a registry handle keeps its fx context"
      (is (not (str/includes? form "_frame-ctx"))
          (str "a `record-in-flight!` fx discards its frame context; the "
               "issuing frame is available on it and MUST be stamped on the "
               "handle. Offending form:\n" form)))
    (testing "and stamps :frame on the handle it records"
      (is (re-find #":frame\s+frame" (or (recorded-handle form) ""))
          (str "handle recorded without a `:frame` stamp keys under nil — a "
               "scope no frame-scoped abort can reach. Offending form:\n"
               form)))))

;; ---- law 2: destroy and deferred dispatch stay frame-scoped --------------

(deftest cleanup-paths-are-frame-exact
  (testing "the seeded handle's deferred dispatch carries the frame"
    ;; The abort-fn runs later, with no ambient frame around it; a bare
    ;; `rf/dispatch` in there raises :rf.error/no-frame-context.
    (let [form (fx-form ":managed-http/seed-in-flight")]
      (is (re-find #"\{:frame\s+frame\}" form))))

  (testing "actor destroy uses the frame-scoped 2-arity"
    (let [form (fx-form ":managed-http/destroy-actor")]
      (is (re-find #"abort-on-actor-destroy\s+\(:frame\s+frame-ctx\)\s+actor-id"
                   form)
          "the 1-arity is the documented ANY-FRAME seam; it sweeps the
           actor-id in EVERY frame, which is the reach the frame-scoped
           keys deny the abort half"))))
