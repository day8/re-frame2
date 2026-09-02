(ns re-frame.hicasso.frame-doors-ssr-cljs-test
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
  instrumented: the kit's `re-frame.hicasso.test.server/render-twice`
  byte comparison is the standing determinism check. Both halves are
  asserted here — a body that reads the id and keeps it out of markup
  renders byte-identical documents, and a body that deliberately prints
  it does not. The second row is the reason the first is worth having:
  a check that has never been seen red is a claim about a check.

  ## W11 — the same seam on BOTH sides, for ONE reason

  The refusal Hicasso establishes over every render extent covers the
  server render exactly as it covers the client's, so what the server
  reports is the SUBSTRATE's answer and not a fact about
  `react-dom/server`: the ambient carry is admitted to the request's own
  frame, and an ambient read refuses — same operation, same substrate,
  same extent id as the browser gives. `frame_doors_cljs_test` witnesses
  both through `collector/render-body` directly; these rows are the same
  seam observed through `react-dom/server`.

  Runtime: `-cljs-test`, i.e. the `:node-test-hicasso` build (and the
  always-on `:node-test`), which is where `react-dom/server` resolves and
  where the rest of the entry's string-only rows run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.server :as server]
            [re-frame.hicasso.test.server :as ts]
            [re-frame.test-support :as test-support]))

;; Registered ABOVE `use-fixtures`, for the sibling suites' reason: the
;; reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it is
;; erased before the first row runs.

(rf/reg-sub ::remaining (fn [db _] (:remaining db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
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
     :init-fn       (fn [] (collector/reset-runtime!))}))

(def ^:private !seen
  "Every frame id a server body read, newest last."
  (atom []))

(def ^:private !ambient
  "What an ambient operation answered inside a server body — the ex-data
  of its refusal, or `::no-throw` with the value it produced."
  (atom ::unset))

(h/defview discreet
  "Reads the request's frame and keeps it OUT of the markup — the
  documented correct shape. What it renders is a subscription value, so
  the boundary is an ordinary one."
  [_]
  (swap! !seen conj (rf/current-frame-id))
  [:span.row (str (h/sub [::remaining]))])

(h/defview indiscreet
  "Renders the per-request id INTO the markup. This is the authorable
  hazard the guide warns about, written on purpose so the determinism
  check can be watched failing."
  [_]
  [:span.row {:data-frame (str (rf/current-frame-id))}])

(h/defview carrying
  "Tries the AMBIENT carry — `(rf/capture-frame)`, 0-arity — inside a
  server body, and records what it got. It catches its own throw so the
  render completes and the row can read the markup rather than a
  `renderToString` failure."
  [_]
  (reset! !ambient
          (try [::no-throw (rf/capture-frame)]
               (catch :default e (ex-data e))))
  [:span.row (str (h/sub [::remaining]))])

(h/defview reading
  "Tries an AMBIENT read — `(rf/subscribe …)` — inside a server body, and
  records what it got, for the same reason `carrying` catches its own
  throw."
  [_]
  (reset! !ambient
          (try [::no-throw @(rf/subscribe [::remaining])]
               (catch :default e (ex-data e))))
  [:span.row (str (h/sub [::remaining]))])

(defn- request [hiccup]
  {:hiccup   hiccup
   :snapshot {:remaining 3}
   :payload  [:remaining]})

;; ---------------------------------------------------------------------------
;; W8 — the per-request id, and the one authorable hazard
;; ---------------------------------------------------------------------------

(deftest a-server-body-reads-the-requests-own-frame
  (testing "the frame `rf/current-frame-id` answers on the server IS the
           request's per-request gensym, and two requests answer two
           different ones — which is what per-request isolation means when
           the id is the thing being read"
    (reset! !seen [])
    (let [a (server/render (request [discreet {}]))
          b (server/render (request [discreet {}]))]
      (is (= 2 (count @!seen)) "one read per request")
      (is (= [(:frame-id a) (:frame-id b)] @!seen)
          "each body read its OWN request's id, in order")
      (is (not= (:frame-id a) (:frame-id b))
          "precondition: the two requests really did take different frames,
           so the row above is not comparing a constant to itself"))))

(deftest a-body-that-reads-the-id-and-keeps-it-out-of-markup-is-deterministic
  (testing "the correct shape. Two renders take two gensyms, and the
           documents are byte-identical anyway — because the value went
           into a read and not into the page"
    (let [{:keys [identical? differs-at] a :first b :second}
          (ts/render-twice (request [discreet {}]))]
      (is identical? (str "the two documents differ at index " differs-at))
      (is (not= (:frame-id a) (:frame-id b))
          "and they were different requests — this is the whole point")
      (is (not (str/includes? (:document a) (name (:frame-id a))))
          "the id is nowhere in the markup"))))

(deftest a-body-that-renders-the-id-breaks-determinism-and-the-check-says-so
  (testing "THE ROW THAT MAKES THE ONE ABOVE MEAN SOMETHING. Rendering the
           per-request id is an authorable hazard, and the claim is that
           `ts/render-twice`'s byte comparison catches it. A claim about a
           check that has never been watched failing is not a check, so
           here it is failing"
    (let [{:keys [identical? differs-at] a :first} (ts/render-twice (request [indiscreet {}]))]
      (is (not identical?)
          "a document carrying the per-request gensym cannot be
           deterministic, and the determinism witness must say so")
      (is (some? differs-at) "with a diagnosable diff position")
      (is (str/includes? (:document a) (name (:frame-id a)))
          "and the id is visibly in the markup, which is the mistake"))))

;; ---------------------------------------------------------------------------
;; W11 — the same seam on both sides, for one reason
;; ---------------------------------------------------------------------------

(deftest the-ambient-carry-is-admitted-server-side-to-the-requests-own-frame
  (testing "the carry inside a server body captures the REQUEST's frame —
           not a host frame, not `:rf/default`, and not a refusal. Before
           rf2-t32wg this row asserted the refusal; the seam admits the
           pure doors to the extent's declared frame, and the server
           render extent declares it exactly as the client's does"
    (reset! !ambient ::unset)
    (let [{:keys [document frame-id]} (server/render (request [carrying {}]))
          data                        @!ambient]
      (is (vector? data)
          (str "expected the carry to be admitted; got " (pr-str data)))
      (is (= frame-id (:frame (second data)))
          "and locked to the request's own per-request frame")
      (is (str/includes? document "class=\"row\"")
          "and the render completed"))))

(deftest an-ambient-read-refuses-server-side-for-the-substrates-own-reason
  (testing "the stateful half of the same extent: an ambient read refuses,
           and what the server reports is the SUBSTRATE's refusal — same
           operation, same substrate, same extent — and not a fact about
           `react-dom/server`"
    (reset! !ambient ::unset)
    ;; The page no longer comes back. `:rf.error/ambient-frame-refused` is a
    ;; PROMOTED category — `require-current-frame!` emits it on the always-on
    ;; error stream and then throws — so it lands in the recovered-error
    ;; window `server/render` gained with rf2-ypom / rf2-ct24, and the door
    ;; refuses the page. That verdict is deliberately blunt: any record
    ;; inside the window fails the render, INCLUDING one the view caught
    ;; itself, because the stream cannot tell a handled refusal from an
    ;; unhandled one and a check that guessed would be guessing on exactly
    ;; the class of fault it exists to catch. The row's subject is untouched
    ;; — it still measures WHOSE refusal the read got, and it now also
    ;; measures that the door names that same refusal as its reason.
    (let [thrown (try (server/render (request [reading {}]))
                      (catch :default e e))
          data   @!ambient]
      (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
          (str "expected the ambient refusal for the read; got " (pr-str data)))
      (is (= :subscribe (:operation data)))
      (is (= :hicasso (:substrate data))
          "the substrate refused, not the renderer — which is what makes
           this the same answer the browser gives")
      (is (= 'hicasso/boundary-render (:extent data)))
      (testing "and the page is refused rather than shipped, naming the
                read's refusal as its reason: the refusal is still the
                read's, and the render will not paper over it"
        (let [page (ex-data thrown)]
          (is (= :rf.error/ssr-render-failed (:rf.error/id page))
              (str "expected the whole-page door to fail the render; got " (pr-str page)))
          (is (= :rf.error/ambient-frame-refused (:error (:record page)))
              "and the record the door names is the read's own"))))))
