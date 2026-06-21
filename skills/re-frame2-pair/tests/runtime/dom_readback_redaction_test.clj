;;;; tests/runtime/dom_readback_redaction_test.clj
;;;;
;;;; Babashka-runnable verification of the DOM / readback derived-value
;;;; egress redaction in `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; THE CONTRACT (EP-0025 fail-open). Spec 015 / Tool-Pair: a DERIVED tree
;;;; (rendered DOM text / attrs / a focus descriptor) is PATH-projected
;;;; against the frame's classification before off-box egress. EP-0025
;;;; REMOVED the value-match (taint-by-equality) engine that used to scrub a
;;;; secret COPIED from a declared-sensitive app-db path (`[:auth :token]` ->
;;;; "SECRET") into a re-keyed DOM position — value-match is propagation/taint
;;;; by another name, which a hygiene helper does not earn (§"What is
;;;; removed"). So such a re-keyed DOM secret ships off-box RAW (FAIL-OPEN);
;;;; to keep it out of rendered content, classify its app-db PATH so it is
;;;; redacted at the source before a view renders it. Only a value that still
;;;; occupies a CLASSIFIED path WITHIN the derived tree redacts.
;;;;
;;;; THE MECHANISM. `maybe-redact-derived` PATH-projects a DERIVED tree via
;;;; `re-frame.core/project-egress` (the `:rf.observe/derived-tree` boundary)
;;;; against the frame's classification, under the off-box raw-state gate.
;;;; `dom-read` / `ui-read` / the `:dom` / `:focus` sample arms route their
;;;; derived output through it. A surface that needs frame policy but cannot
;;;; resolve a frame under the off-box gate FAILS CLOSED with
;;;; `:ambiguous-frame`, never synthesising `:rf/default` — that guard is
;;;; INDEPENDENT of value-match and is KEPT.
;;;;
;;;; Why a parallel implementation lives here. `runtime.cljs` is CLJS-only
;;;; (a shadow-cljs `:devtools :preloads` file) and depends on the live
;;;; re-frame2 frame registry / `rf/project-egress`, none of which run under
;;;; bb. This file MIRRORS the PATH-based projection + gate logic and asserts
;;;; behaviour against a canned sensitive frame; a structural pin (below)
;;;; keeps the mirror honest against the source so a regression in the real
;;;; cljs (e.g. someone drops the `maybe-redact-derived` call, or
;;;; re-introduces value-match) trips a RED.
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
;; Mirror of the runtime's PATH-based derived-tree projection (EP-0025
;; fail-open). KEEP IN SYNC with `re-frame.core/project-egress`
;; (`:rf.observe/derived-tree`) + `maybe-redact-derived`.
;;
;; EP-0025 removed value-match. The framework walks the derived tree at the
;; frame's declared-`:sensitive?` PATHS: a value that occupies a classified
;; path WITHIN the tree redacts; a value RE-KEYED to a non-matching position
;; ships RAW. We mirror the path walk: `elide-wire-value` descends a map only
;; by following the declared path KEYS (so a leaf reached via a matching path
;; redacts), and is structurally blind to a value sitting under any other key.
;; ---------------------------------------------------------------------------

(def ^:private raw-state-config (atom {:allow-raw-state? true}))

(defn- path-redact
  "Mirror of `elide-wire-value`'s PATH walk for ONE declared `path`: descend
  `tree` by the path's KEY segments (vectors/seqs are traversed transparently,
  not by index) and redact the leaf there to `:rf/redacted`. A path that does
  not resolve to a leaf (the value is re-keyed elsewhere) leaves `tree`
  unchanged — EP-0025 fail-open."
  [tree path]
  (letfn [(walk [node ps]
            (cond
              (empty? ps) :rf/redacted
              (map? node) (let [k (first ps)]
                            (if (contains? node k)
                              (assoc node k (walk (get node k) (rest ps)))
                              node))
              ;; vectors/seqs are traversed transparently (no path segment
              ;; consumed), mirroring the elider's seq walk.
              (sequential? node) (mapv #(walk % ps) node)
              :else node))]
    (walk tree path)))

(defn- maybe-redact-derived
  "Mirror of the runtime gate: off-box (gate OFF) PATH-projects the derived
  tree against the frame's declared-sensitive paths; trusted-local (gate ON)
  passes verbatim. EP-0025 fail-open: only a value AT a classified path within
  the tree redacts; a re-keyed copy ships raw."
  [tree _source-db sensitive-paths]
  (if (or (:allow-raw-state? @raw-state-config) (nil? tree))
    tree
    (reduce path-redact tree sensitive-paths)))

;; ---------------------------------------------------------------------------
;; The fixture: a frame declaring [:auth :token] sensitive, with "SECRET"
;; rendered into DOM text, DOM attrs, read-ui content, and a focus
;; descriptor — exactly the EP-0025 fail-open leak class (the secret is
;; RE-KEYED into a non-app-db DOM position).
;; ---------------------------------------------------------------------------

(def ^:private source-db
  {:auth {:token "SECRET"} :public {:name "Ada"}})

(def ^:private sensitive-paths [[:auth :token]])

(defn- with-gate [allow? f]
  (let [prev @raw-state-config]
    (reset! raw-state-config {:allow-raw-state? allow?})
    (try (f) (finally (reset! raw-state-config prev)))))

;; ---------------------------------------------------------------------------
;; Behaviour — EP-0025 fail-open: a RE-KEYED DOM secret ships RAW.
;; ---------------------------------------------------------------------------

(deftest dom-text-re-keyed-secret-ships-raw-fail-open
  (with-gate false
    (fn []
      ;; A dom-read node {:tag :text :attrs} whose textContent / attribute IS
      ;; the secret — the secret was COPIED from app-db [:auth :token] into a
      ;; re-keyed DOM position ([:text], [:attrs "data-token"]). The path walk
      ;; keyed at [:auth :token] never reaches those positions, so EP-0025
      ;; ships them RAW (fail-open).
      (let [node {:tag "div" :text "SECRET" :attrs {"data-token" "SECRET" "class" "card"}}
            out  (maybe-redact-derived [node] source-db sensitive-paths)]
        (testing "EP-0025 fail-open: the re-keyed DOM secret ships raw"
          (is (= "SECRET" (-> out first :text))
              "fail-open: the re-keyed :text leaf ships raw")
          (is (= "SECRET" (get-in (first out) [:attrs "data-token"]))
              "fail-open: the re-keyed attribute value ships raw too")
          (is (= "card" (get-in (first out) [:attrs "class"]))
              "a non-sensitive sibling attr is untouched"))))))

(deftest ui-content-re-keyed-secret-ships-raw-fail-open
  (with-gate false
    (fn []
      ;; ui-read returns the WHOLE :content (text AND attrs). The secret is
      ;; re-keyed into [:text] / [:attrs "value"] — neither at [:auth :token].
      (let [content {:tag "span" :text "SECRET" :attrs {"value" "SECRET" "id" "tok"}}
            out     (maybe-redact-derived content source-db sensitive-paths)]
        (is (= "SECRET" (:text out)) "fail-open: re-keyed :text ships raw")
        (is (= "SECRET" (get-in out [:attrs "value"])) "fail-open: re-keyed :attrs value ships raw")
        (is (= "tok" (get-in out [:attrs "id"])) "non-sensitive attr untouched")))))

(deftest focus-descriptor-re-keyed-secret-ships-raw-fail-open
  (with-gate false
    (fn []
      ;; A focus descriptor that happened to capture the secret in an attr —
      ;; at [:name], a re-keyed position.
      (let [focus {:tag "input" :id "tok" :name "SECRET" :rf2-src "my.app:f:1:1"}
            out   (maybe-redact-derived focus source-db sensitive-paths)]
        (is (= "SECRET" (:name out)) "fail-open: the re-keyed focus-descriptor field ships raw")
        (is (= "tok" (:id out)) "non-sensitive focus fields untouched")))))

(deftest value-at-classified-path-still-redacts
  (with-gate false
    (fn []
      ;; The KEPT guarantee: a derived tree that carries the value AT its
      ;; classified path ([:auth :token]) — not re-keyed — redacts through the
      ;; path walk. (A derived slice that mirrors the app-db shape.)
      (let [tree {:auth {:token "SECRET"} :public {:name "Ada"}}
            out  (maybe-redact-derived tree source-db sensitive-paths)]
        (is (= :rf/redacted (get-in out [:auth :token]))
            "a value at its declared path redacts (path-based, KEPT)")
        (is (= "Ada" (get-in out [:public :name]))
            "benign siblings survive untouched")))))

(deftest non-sensitive-derived-content-survives
  (with-gate false
    (fn []
      (let [node {:tag "div" :text "Hello Ada" :attrs {"data-name" "Ada"}}
            out  (maybe-redact-derived [node] source-db sensitive-paths)]
        (is (= node (first out))
            "content with no classified-path value rides verbatim")))))

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
        "dom-read must PATH-project its matched nodes through maybe-redact-derived")
    (is (mentions? f 'ambiguous-frame-error)
        "dom-read must fail closed on an ambiguous frame under the off-box gate")))

(deftest ui-read-projects-whole-content-not-just-text
  (let [f (defn-form 'ui-read)
        s (pr-str f)]
    (is (some? f))
    (is (mentions? f 'maybe-redact-derived)
        "ui-read must PATH-project the whole :content (not just :text)")
    ;; A path-based elide over JUST the :text string is the wrong shape.
    ;; Assert it is absent — ui-read must not call elide-wire-value on :text.
    (is (not (str/includes? s "(rf/elide-wire-value (:text base)"))
        "ui-read must NOT path-elide only :text (the rf2-p9scds shape)")
    (is (mentions? f 'ambiguous-frame-error)
        "ui-read must fail closed on an ambiguous frame under the off-box gate")))

(deftest dom-and-focus-sample-arms-project
  (let [f (defn-form 'sample-one-signal)]
    (is (some? f))
    ;; Both DERIVED arms must route through the path-projector.
    (is (<= 2 (count (re-seq #"maybe-redact-derived" (pr-str f))))
        "both the :dom and :focus arms must PATH-project their derived output")))

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
