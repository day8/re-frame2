(ns re-frame.fresco.frame-doors-ssr-cljs-test
  "THE PURE FRAME DOORS ON THE SERVER — `rf/current-frame-id` and
  zero-arity `rf/capture-frame` under `server/render`.

  Every claim here is about the RUNTIME under a server render rather than
  about the tree's shape: the body runs on Node through the same shell
  path, so the doors answer the request's own per-request gensym and
  per-request isolation holds by construction.

  ## W8 — the id is process-local identity, and must never reach markup

  Two same-process renders take two different gensyms, so a body that
  RENDERS the value makes the document nondeterministic. That is an
  authorable hazard rather than a framework one, and it is already
  instrumented: the kit's `re-frame.fresco.test.server/render-twice`
  byte comparison is the standing determinism check.
  `server_render_ssr_dom_cljs_test`'s
  `two-renders-of-one-request-are-the-same-bytes` asserts its green half
  — a body that keeps the id out of markup renders byte-identical
  documents — and the row here asserts the red one: a body that
  deliberately prints it does not. The red row is the reason the green
  one is worth having: a check that has never been seen red is a claim
  about a check.

  ## W11 — the same seam on BOTH sides, for ONE reason

  The refusal Fresco establishes over every render extent covers the
  server render exactly as it covers the client's, so what the server
  reports is the SUBSTRATE's answer and not a fact about
  `react-dom/server`: an ambient read refuses — same operation, same
  substrate, same extent id as the browser gives. `frame_doors_cljs_test`
  witnesses the admitted carry and the refused read through
  `collector/render-body` directly; the row here is the same seam
  observed through `react-dom/server`.

  Runtime: `-cljs-test`, i.e. the `:node-test-fresco` build (and the
  always-on `:node-test`), which is where `react-dom/server` resolves and
  where the rest of the entry's string-only rows run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.server :as rf.fresco.server]
            [re-frame.fresco.test.server :as rf.fresco.test.server]
            [re-frame.test-support :as rf.test-support]))

;; Registered ABOVE `use-fixtures`, for the sibling suites' reason: the
;; reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it is
;; erased before the first row runs.

(rf/reg-sub ::remaining (fn [db _] (:remaining db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; `:ambient-frame nil`, and here it is load-bearing rather than
     ;; tidiness. The default root-binds `frame/*current-frame*` to
     ;; `:rf/default`, which is a CARRIED tier-1 stamp naming a frame the
     ;; request is NOT rendering — the mismatch case, pinned deliberately
     ;; in `frame_doors_cljs_test`'s
     ;; `a-carried-outer-scope-refuses-rather-than-answering-the-wrong-frame`.
     ;; A server request has no such stamp: `server/render` establishes
     ;; no scope and a host calls it at top of stack. Measuring the doors
     ;; against the fixture's own stamp would be measuring the fixture.
     :ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(def ^:private !ambient
  "What an ambient operation answered inside a server body — the ex-data
  of its refusal, or `::no-throw` with the value it produced."
  (atom ::unset))

(rf.fresco/defview indiscreet
  "Renders the per-request id INTO the markup. This is the authorable
  hazard the guide warns about, written on purpose so the determinism
  check can be watched failing."
  [_]
  [:span.row {:data-frame (str (rf/current-frame-id))}])

(rf.fresco/defview reading
  "Tries an AMBIENT read — `(rf/subscribe …)` — inside a server body, and
  records what it got. It catches its own throw so the row reads the
  read's own refusal rather than a `renderToString` failure."
  [_]
  (reset! !ambient
          (try [::no-throw @(rf/subscribe [::remaining])]
               (catch :default e (ex-data e))))
  [:span.row (str (rf.fresco/sub [::remaining]))])

(defn- request [hiccup]
  {:hiccup   hiccup
   :snapshot {:remaining 3}
   :payload  [:remaining]})

;; ---------------------------------------------------------------------------
;; W8 — the per-request id, and the one authorable hazard
;; ---------------------------------------------------------------------------

(deftest a-body-that-renders-the-id-breaks-determinism-and-the-check-says-so
  (testing "THE ROW THAT MAKES THE DETERMINISM CHECK MEAN SOMETHING. Rendering the
           per-request id is an authorable hazard, and the claim is that
           `ts/render-twice`'s byte comparison catches it. A claim about a
           check that has never been watched failing is not a check, so
           here it is failing"
    (let [{:keys [identical? differs-at] a :first} (rf.fresco.test.server/render-twice (request [indiscreet {}]))]
      (is (not identical?)
          "a document carrying the per-request gensym cannot be
           deterministic, and the determinism witness must say so")
      (is (some? differs-at) "with a diagnosable diff position")
      (is (str/includes? (:document a) (name (:frame-id a)))
          "and the id is visibly in the markup, which is the mistake"))))

;; ---------------------------------------------------------------------------
;; W11 — the same seam on both sides, for one reason
;; ---------------------------------------------------------------------------

(deftest an-ambient-read-refuses-server-side-for-the-substrates-own-reason
  (testing "the stateful half of the same extent: an ambient read refuses,
           and what the server reports is the SUBSTRATE's refusal — same
           operation, same substrate, same extent — and not a fact about
           `react-dom/server`"
    (reset! !ambient ::unset)
    ;; The page does not come back. `:rf.error/ambient-frame-refused` is a
    ;; PROMOTED category — `require-current-frame!` emits it on the always-on
    ;; error stream and then throws — so it lands in `server/render`'s
    ;; recovered-error window, and the door refuses the page. That verdict is deliberately blunt: any record
    ;; inside the window fails the render, INCLUDING one the view caught
    ;; itself, because the stream cannot tell a handled refusal from an
    ;; unhandled one and a check that guessed would be guessing on exactly
    ;; the class of fault it exists to catch. The row measures WHOSE refusal
    ;; the read got, and that the door names that same refusal as its reason.
    (let [thrown (try (rf.fresco.server/render (request [reading {}]))
                      (catch :default e e))
          data   @!ambient]
      (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
          (str "expected the ambient refusal for the read; got " (pr-str data)))
      (is (= :subscribe (:operation data)))
      (is (= :fresco (:substrate data))
          "the substrate refused, not the renderer — which is what makes
           this the same answer the browser gives")
      (is (= 'fresco/boundary-render (:extent data)))
      (testing "and the page is refused rather than shipped, naming the
                read's refusal as its reason: the refusal is the read's,
                and the render will not paper over it"
        (let [page (ex-data thrown)]
          (is (= :rf.error/ssr-render-failed (:rf.error/id page))
              (str "expected the whole-page door to fail the render; got " (pr-str page)))
          (is (= :rf.error/ambient-frame-refused (:error (:record page)))
              "and the record the door names is the read's own"))))))
