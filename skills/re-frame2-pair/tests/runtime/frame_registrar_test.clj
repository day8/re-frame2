;;;; tests/runtime/frame_registrar_test.clj
;;;;
;;;; Babashka-runnable structural pin for the frame-derived preload fns:
;;;; the per-frame registrar reads and the `describe-image` generation read.
;;;;
;;;; Why this test exists:
;;;;
;;;; The MCP `handler-meta` / `list-handlers` / `describe-image` tools
;;;; re-key registration resolution through the OPERATING FRAME's running
;;;; image generation (the same `(kind, id)` can resolve differently per
;;;; frame). Tools must not consume `re-frame.live-frame` /
;;;; `re-frame.image-assembly` internals directly — the preload routes
;;;; through the PUBLIC facade reads:
;;;;
;;;;   (rf/handler-meta {:frame f :kind k :id id})
;;;;   (rf/handler-ids  {:frame f :kind k})
;;;;   (rf/registrations {:frame f :kind k})
;;;;   (rf/frame-generation f)
;;;;
;;;; This pin asserts the four preload fns exist and route through the
;;;; `:frame`-arity facade reads / `frame-generation` — NOT the internal
;;;; live-frame / image-assembly namespaces. A regression that reached into
;;;; the internals (or dropped the per-frame fns) turns this red.
;;;;
;;;; Run: bb tests/runtime/frame_registrar_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns frame-registrar-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
(def ^:private defn-form rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(defn- calls? [form sym]
  ;; True when `form` invokes `sym` as the head of any sub-list.
  (form-contains? (fn [node] (and (seq? node) (= sym (first node)))) form))

;; ---------------------------------------------------------------------------
;; The four new preload fns are present.
;; ---------------------------------------------------------------------------

(def ^:private fn-syms
  '[frame-registrar-describe frame-registrar-list
    frame-registrar-registrations frame-capability-requires describe-image
    coordinate-summary])

(deftest all-frame-derived-fns-present
  (doseq [sym fn-syms]
    (is (some? (defn-form sym))
        (str "preload/re_frame2_pair/runtime.cljs must define `" sym
             "` (the EP-0023 forward-direction frame-derived read; rf2-srobm0)."))))

;; ---------------------------------------------------------------------------
;; They route through the PUBLIC facade `:frame` reads, not the internals.
;; ---------------------------------------------------------------------------

(deftest frame-registrar-describe-uses-facade-frame-read
  (let [f (defn-form 'frame-registrar-describe)]
    (is (calls? f 'rf/handler-meta)
        "frame-registrar-describe MUST route through (rf/handler-meta {:frame …}) — the public facade read (rf2-wkw8na).")
    (is (form-contains? (fn [n] (= :frame n)) f)
        "frame-registrar-describe MUST pass a :frame-keyed query map (the frame-targeted arity).")))

(deftest frame-registrar-list-uses-facade-frame-read
  (let [f (defn-form 'frame-registrar-list)]
    (is (calls? f 'rf/handler-ids)
        "frame-registrar-list MUST route through (rf/handler-ids {:frame …}) — the public facade read.")))

(deftest frame-registrar-registrations-uses-facade-frame-read
  (let [f (defn-form 'frame-registrar-registrations)]
    (is (calls? f 'rf/registrations)
        "frame-registrar-registrations MUST route through (rf/registrations {:frame …}) — the public facade read.")))

(deftest describe-image-uses-public-frame-generation
  (let [f (defn-form 'describe-image)]
    (is (calls? f 'rf/frame-generation)
        "describe-image MUST route through (rf/frame-generation frame) — the public facade read (rf2-wkw8na), NOT re-frame.image-assembly internals.")
    (is (form-contains? (fn [n] (= :rf.gen/resolver n)) f)
        "describe-image reads the sealed generation's :rf.gen/resolver for the per-kind counts / registrations.")
    (is (form-contains? (fn [n] (= :rf.gen/requires n)) f)
        "describe-image surfaces :rf.gen/requires — the missing-capability discriminator (EP-0023 Use-Case 7).")))

(deftest frame-capability-requires-reads-generation-requires
  (let [f (defn-form 'frame-capability-requires)]
    (is (calls? f 'rf/frame-generation)
        "frame-capability-requires MUST read the public frame-generation.")
    (is (form-contains? (fn [n] (= :rf.gen/requires n)) f)
        "frame-capability-requires reports the image-declared :rf.gen/requires set.")))

;; ---------------------------------------------------------------------------
;; describe-image guards the no-generation fail-loud.
;;
;; Only an EXPLICIT :images key triggers image resolution, so an imageless
;; frame carries NO generation and the public rf/frame-generation read FAILS
;; LOUD (:rf.error/frame-no-generation) for it. describe-image must GUARD that
;; call — catch the no-generation fail-loud for a LIVE frame and report it
;; gracefully (:no-generation?), rather than letting it escape up the eval
;; boundary — while still failing loud on a genuinely unresolvable target.
;; ---------------------------------------------------------------------------

(deftest describe-image-guards-no-generation-fail-loud
  (let [f (defn-form 'describe-image)]
    (is (calls? f 'try)
        "describe-image MUST wrap the rf/frame-generation read in a `try` so the EP-0024 no-generation fail-loud can be guarded.")
    (is (calls? f 'catch)
        "describe-image MUST `catch` the rf/frame-generation throw rather than letting an imageless frame's fail-loud escape the eval boundary.")
    (is (form-contains? (fn [n] (= :rf.error/frame-no-generation n)) f)
        "describe-image MUST discriminate on :rf.error/frame-no-generation — the EP-0024 no-generation error id — so only the no-generation case is softened (any other throw re-raises).")
    (is (form-contains? (fn [n] (= :live-frame-ids n)) f)
        "describe-image MUST check the error's :live-frame-ids so a LIVE imageless frame degrades gracefully while a target naming NO live frame still fails loud.")
    (is (form-contains? (fn [n] (= :no-generation? n)) f)
        "describe-image MUST surface a :no-generation? graceful result for an imageless frame (it runs no composed image — not a read error).")
    (is (calls? f 'throw)
        "describe-image MUST re-`throw` any non-no-generation error so a genuinely unresolvable :frame target still fails loud up the eval boundary.")))

;; ---------------------------------------------------------------------------
;; No internal-namespace leakage (tools must not consume these).
;; ---------------------------------------------------------------------------

(deftest no-live-frame-or-image-assembly-internals-in-frame-fns
  (doseq [sym '[frame-registrar-describe frame-registrar-list
                frame-registrar-registrations frame-capability-requires
                describe-image]]
    (let [f (defn-form sym)
          leaks? (form-contains?
                   (fn [n]
                     (and (symbol? n)
                          (let [nsp (namespace n)]
                            (contains? #{"live-frame" "image-assembly"
                                         "re-frame.live-frame" "re-frame.image-assembly"}
                                       nsp))))
                   f)]
      (is (not leaks?)
          (str sym " MUST NOT reach into re-frame.live-frame / "
               "re-frame.image-assembly internals — EP-0023 routes tools "
               "through the public facade reads only (rf2-srobm0).")))))

;; ---------------------------------------------------------------------------
;; orient re-bases its registry on the operating frame's generation.
;; ---------------------------------------------------------------------------

(deftest orient-rebased-on-frame-registry-view
  (let [orient (defn-form 'orient)
        view   (defn-form 'frame-registry-view)]
    (is (some? view)
        "preload must define `frame-registry-view` — the operating-frame registry projection (rf2-srobm0).")
    (is (calls? view 'rf/frame-generation)
        "frame-registry-view MUST resolve through the public rf/frame-generation read.")
    (is (calls? orient 'frame-registry-view)
        "orient MUST re-base its :registry on the operating-frame generation (frame-registry-view), falling back to the process view.")
    (is (calls? orient 'process-registry-view)
        "orient MUST keep the process-wide registry view as the fallback (ambiguous multi-frame / pre-EP-0023 core).")))

(let [{:keys [fail error]} (run-tests 'frame-registrar-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
