;;;; tests/runtime/dom_readback_redaction_test.clj
;;;;
;;;; Babashka-runnable verification of the DOM / readback derived-value
;;;; egress redaction in `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; THE CONTRACT. Spec 015 treats framework-known derivations from
;;;; sensitive inputs as sensitive unless explicitly public, and Tool-Pair
;;;; says rendered DOM text crossing off-box must not ride unconditionally.
;;;; A secret copied from a declared-sensitive app-db path (`[:auth :token]`
;;;; -> "SECRET") into rendered DOM text / attrs / a focus descriptor must
;;;; NOT cross the off-box MCP wire RAW. The trap to avoid: `dom-read`
;;;; returning `node->content` nodes with no redaction, `ui-read` running
;;;; the path-based `elide-wire-value` over just the anonymous `:text`
;;;; string (that walker redacts by app-db PATH, and rendered text has none,
;;;; so it never catches a secret copied INTO the DOM, and never touches
;;;; `:attrs`), or `sample-one-signal`'s `:dom` / `:focus` arms passing the
;;;; rendered textContent / attribute / focus descriptor through un-elided.
;;;;
;;;; THE MECHANISM. `maybe-redact-derived` value-redacts a DERIVED tree
;;;; (rendered text / attrs / focus descriptor) via
;;;; `re-frame.core/redact-derived-values` against the frame's
;;;; declared-`:sensitive?` app-db VALUES, under the off-box raw-state gate.
;;;; `dom-read` / `ui-read` / the `:dom` / `:focus` sample arms route their
;;;; derived output through it. A surface that needs frame policy but cannot
;;;; resolve a frame under the off-box gate FAILS CLOSED with
;;;; `:ambiguous-frame`, never synthesising `:rf/default`.
;;;;
;;;; Why a parallel implementation lives here. `runtime.cljs` is CLJS-only
;;;; (a shadow-cljs `:devtools :preloads` file) and depends on the live
;;;; re-frame2 frame registry / `rf/redact-derived-slots`, none of which
;;;; run under bb. This file MIRRORS the pure value-match + gate logic and
;;;; asserts behaviour against a canned sensitive frame; a structural pin
;;;; (below) keeps the mirror honest against the source so a regression in
;;;; the real cljs (e.g. someone drops the `maybe-redact-derived` call, or
;;;; re-introduces the path-only `elide-wire-value` on `:text`) trips a RED.
;;;;
;;;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs
;;;; (`maybe-redact-derived` / `dom-read` / `ui-read` / `sample-one-signal`
;;;; / `sample-signals` / `start-recording!`).
;;;;
;;;; Run: bb tests/runtime/dom_readback_redaction_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dom-readback-redaction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure.walk :as walk]
            [runtime-support :as rt]))

;; ---------------------------------------------------------------------------
;; Mirror of the runtime's value-based derived-tree redaction. KEEP IN SYNC
;; with `re-frame.core/redact-derived-slots` + `maybe-redact-derived`.
;;
;; The framework helper collects the live values at the frame's declared-
;; `:sensitive?` app-db paths from the source db and substitutes any matching
;; leaf in the derived tree with `:rf/redacted` (and declared-`:large` leaves
;; with the `:rf.size/large-elided` marker). We mirror the sensitive
;; value-match arm + the off-box gate posture.
;; ---------------------------------------------------------------------------

(def ^:private raw-state-config (atom {:allow-raw-state? true}))

(defn- collect-sensitive-values
  "The set of live values at `frame`'s declared-`:sensitive?` app-db paths,
  read out of `source-db`. Mirror of the framework's candidate collection
  (nil / boolean leaves excluded — never secrets)."
  [source-db sensitive-paths]
  (into #{}
        (comp (map #(get-in source-db %))
              (remove nil?)
              (remove boolean?))
        sensitive-paths))

(defn- redact-matching-values
  "Walk `tree`, substituting any leaf `=` to a member of `secrets` with
  `:rf/redacted`. Mirror of `re-frame.elision/redact-matching-values`."
  [tree secrets]
  (if (empty? secrets)
    tree
    (walk/postwalk (fn [x] (if (contains? secrets x) :rf/redacted x)) tree)))

(defn- redact-derived-values
  "Mirror of the sensitive arm of `re-frame.core/redact-derived-slots`. nil
  tree / nil source-db short-circuit unchanged."
  [tree source-db sensitive-paths]
  (cond
    (nil? tree)      tree
    (nil? source-db) tree
    :else            (redact-matching-values
                       tree (collect-sensitive-values source-db sensitive-paths))))

(defn- maybe-redact-derived
  "Mirror of the runtime gate: off-box (gate OFF) value-redacts the derived
  tree against the frame's secrets; trusted-local (gate ON) passes verbatim."
  [tree source-db sensitive-paths]
  (if (:allow-raw-state? @raw-state-config)
    tree
    (redact-derived-values tree source-db sensitive-paths)))

;; ---------------------------------------------------------------------------
;; The fixture: a frame declaring [:auth :token] sensitive, with "SECRET"
;; rendered into DOM text, DOM attrs, read-ui content, and a focus
;; descriptor — exactly the acceptance-criteria leak class.
;; ---------------------------------------------------------------------------

(def ^:private source-db
  {:auth {:token "SECRET"} :public {:name "Ada"}})

(def ^:private sensitive-paths [[:auth :token]])

(defn- with-gate [allow? f]
  (let [prev @raw-state-config]
    (reset! raw-state-config {:allow-raw-state? allow?})
    (try (f) (finally (reset! raw-state-config prev)))))

;; ---------------------------------------------------------------------------
;; Behaviour — default off-box egress redacts the rendered secret.
;; ---------------------------------------------------------------------------

(deftest dom-text-secret-redacted-by-default
  (with-gate false
    (fn []
      ;; A dom-read node {:tag :text :attrs} whose textContent / attribute IS
      ;; the secret (the leak class: a sensitive value copied VERBATIM into the
      ;; rendered node). `redact-derived-values` value-matches whole LEAVES, so
      ;; a leaf `=` to "SECRET" redacts.
      (let [node {:tag "div" :text "SECRET" :attrs {"data-token" "SECRET" "class" "card"}}
            out  (maybe-redact-derived [node] source-db sensitive-paths)]
        (testing "rendered DOM text carrying a sensitive value redacts"
          (is (= :rf/redacted (-> out first :text))
              "a text leaf = SECRET redacts")
          (is (= :rf/redacted (get-in (first out) [:attrs "data-token"]))
              "a sensitive attribute value redacts too (not just :text)")
          (is (= "card" (get-in (first out) [:attrs "class"]))
              "a non-sensitive sibling attr is untouched"))))))

(deftest ui-content-secret-redacted-by-default
  (with-gate false
    (fn []
      ;; ui-read returns the WHOLE :content (text AND attrs) through the
      ;; value-redaction pass — the bug was eliding only :text.
      (let [content {:tag "span" :text "SECRET" :attrs {"value" "SECRET" "id" "tok"}}
            out     (maybe-redact-derived content source-db sensitive-paths)]
        (is (= :rf/redacted (:text out)) ":text leaf redacts")
        (is (= :rf/redacted (get-in out [:attrs "value"])) ":attrs value redacts")
        (is (= "tok" (get-in out [:attrs "id"])) "non-sensitive attr untouched")))))

(deftest focus-descriptor-secret-redacted-by-default
  (with-gate false
    (fn []
      ;; A focus descriptor that happened to capture the secret in an attr.
      (let [focus {:tag "input" :id "tok" :name "SECRET" :rf2-src "my.app:f:1:1"}
            out   (maybe-redact-derived focus source-db sensitive-paths)]
        (is (= :rf/redacted (:name out)) "a secret in the focus descriptor redacts")
        (is (= "tok" (:id out)) "non-sensitive focus fields untouched")))))

(deftest non-sensitive-derived-content-survives
  (with-gate false
    (fn []
      (let [node {:tag "div" :text "Hello Ada" :attrs {"data-name" "Ada"}}
            out  (maybe-redact-derived [node] source-db sensitive-paths)]
        (is (= node (first out))
            "content with no secret value rides verbatim (no over-scrub)")))))

(deftest trusted-local-gate-ships-raw
  (with-gate true
    (fn []
      (let [node {:tag "div" :text "token: SECRET" :attrs {"data-token" "SECRET"}}
            out  (maybe-redact-derived [node] source-db sensitive-paths)]
        (is (= [node] out)
            "under --allow-sensitive-reads the operator's raw read ships verbatim")))))

;; ---------------------------------------------------------------------------
;; Source-level contract — pin the real runtime against the mirror so a
;; regression in the cljs trips RED.
;; ---------------------------------------------------------------------------

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
(def ^:private defn-form rt/defn-named)

(defn- mentions? [form needle]
  (let [hit? (atom false)]
    (walk/postwalk (fn [x] (when (= x needle) (reset! hit? true)) x) form)
    @hit?))

(deftest runtime-defines-the-derived-redaction-helper
  (let [f (defn-form 'maybe-redact-derived)]
    (is (some? f) "runtime must define maybe-redact-derived")
    ;; EP-0025 B4 (rf2-ojp8pi): the SINGLE public boundary a derived tree
    ;; projects through is re-frame.core/project-egress — the
    ;; :rf.observe/derived-tree record kind. project-egress reads the frame's
    ;; live app-db itself (the derived-tree record's default :source-db), so
    ;; the helper no longer hand-reads app-db-value.
    (is (mentions? f 'rf/project-egress)
        "maybe-redact-derived must delegate to re-frame.core/project-egress")
    (is (mentions? f :rf.observe/derived-tree)
        "maybe-redact-derived must project a :rf.observe/derived-tree record")))

(deftest dom-read-routes-content-through-redaction
  (let [f (defn-form 'dom-read)]
    (is (some? f))
    (is (mentions? f 'maybe-redact-derived)
        "dom-read must value-redact its matched nodes (no longer raw)")
    (is (mentions? f 'ambiguous-frame-error)
        "dom-read must fail closed on an ambiguous frame under the off-box gate")))

(deftest ui-read-redacts-whole-content-not-just-text
  (let [f (defn-form 'ui-read)
        s (pr-str f)]
    (is (some? f))
    (is (mentions? f 'maybe-redact-derived)
        "ui-read must value-redact the whole :content")
    ;; A path-based elide over JUST the :text string is the wrong shape.
    ;; Assert it is absent — ui-read must not call elide-wire-value on :text.
    (is (not (str/includes? s "(rf/elide-wire-value (:text base)"))
        "ui-read must NOT path-elide only :text (the rf2-p9scds bug)")
    (is (mentions? f 'ambiguous-frame-error)
        "ui-read must fail closed on an ambiguous frame under the off-box gate")))

(deftest dom-and-focus-sample-arms-redact
  (let [f (defn-form 'sample-one-signal)]
    (is (some? f))
    ;; Both DERIVED arms must route through the value-redactor.
    (is (<= 2 (count (re-seq #"maybe-redact-derived" (pr-str f))))
        "both the :dom and :focus arms must value-redact their derived output")))

(deftest recorder-and-watch-fail-closed-on-ambiguous-frame-for-derived-signals
  (let [start (defn-form 'start-recording!)
        samp  (defn-form 'sample-signals)]
    (is (mentions? start 'ambiguous-frame-error)
        "start-recording! must refuse an unresolvable frame")
    ;; needs-frame? must extend to :dom / :focus under the off-box gate.
    (is (and (mentions? start :dom) (mentions? start :focus))
        "start-recording! needs-frame? must cover :dom / :focus (off-box gate)")
    (is (mentions? samp 'ambiguous-frame-error)
        "sample-signals must fail closed on an ambiguous frame (watch-until path)")
    (is (and (mentions? samp :dom) (mentions? samp :focus))
        "sample-signals needs-frame? must cover :dom / :focus (off-box gate)")))

(let [{:keys [fail error]} (run-tests 'dom-readback-redaction-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
