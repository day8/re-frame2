(ns re-frame.hicasso.server-render-recovered-error-ssr-cljs-test
  "`re-frame.hicasso.server/render` — the whole-page DOCUMENT door — FAILS a
  render the runtime recorded a recovered error during (rf2-ypom, rf2-ct24:
  one defect reached from two directions).

  ## What was wrong

  A sub that throws mid-render does not take the render down. The
  framework's built-in recovery yields `nil`, the view renders a hole, and
  the pass returns a string — so `render` assembled a payload and a
  document around markup the application never meant to produce, and
  answered it as a success.

  The obvious instrument does not see it, which is why the defect survived.
  `re-frame.ssr`'s per-frame buffer is filled by
  `error-emit-projection-listener`, and that listener buffers a record only
  when THREE things hold: the category is outside the
  recoverable-degradation skip set, the record carries a non-nil `:frame`,
  and that frame is a registered `:platform :server` frame. Hicasso's cold
  reads go through pure `compute-sub`, whose `:rf.error/sub-exception` is
  stamped `:frame nil` BY CONSTRUCTION — a pure fn has no frame in scope to
  stamp — so the second drops it; and `render` does not set
  `:platform :server` on its per-request frame, so the third would drop it
  independently. A per-frame peek reads CLEAN on exactly the failure it was
  named for. The door therefore watches the ALWAYS-ON error-emit stream,
  which sits upstream of all three, exactly as `render-body` has since
  slice E.

  ## The four claims

  1. **The refusal.** An ordinary registered sub that throws, rendered
     through `render`, raises `:rf.error/ssr-render-failed` — and names
     `render` as the raiser, not `render-body`.
  2. **The control.** The same door, the same shape of tree, a sub that
     does not throw: the existing map and document come back UNCHANGED.
     Read the pair together — a refusal row on its own cannot tell 'the
     check fired' from 'the render was broken all along', and a control
     row on its own cannot tell 'the shape is intact' from 'the check
     never fires'.
  3. **Release on all THREE exits** — success, recovered-error refusal, and
     a direct render throw. Both the request frame and the error listener,
     because a listener leaked per request is a leak per request under any
     real load, and a leaked `:errors` listener also takes corpus-wide
     ownership away from whatever owns it next.
  4. **The two doors do not collide.** They register under DIFFERENT ids,
     and §4 proves it from INSIDE each render rather than by reading the
     source: a view is a callback that runs during `renderToString`, so it
     can observe the live listener registry at the one moment both windows
     would be open if they shared an id.

  Runtime: `-cljs-test`, so the focused `:node-test-hicasso` build and the
  always-on `:node-test`. Every row renders to a string; none needs a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.server :as server]
            [re-frame.test-support :as test-support]))

;; Registered ABOVE `use-fixtures`, for the sibling suites' reason: the reset
;; fixture captures its source-store baseline when the `use-fixtures` form is
;; EVALUATED, so a registration written below it is erased before the first
;; row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))

;; The throwing sub of §1. An ORDINARY registered sub whose body throws —
;; not a hand-planted trace event — so the row measures the path a real
;; application takes rather than the shape of a fixture.
(rf/reg-sub ::detonates
  (fn [_ _] (throw (js/Error. "the sub that recovers to nil"))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `:ambient-frame nil` — `render` makes its own top-level frame, and
     ;; the fixture's carried `:rf/default` stamp would be a scope the
     ;; request is not rendering. The sibling `server_render_body_ssr` and
     ;; `frame_doors_ssr` suites opt out for the same reason.
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The listener ids, spelled out
;; ---------------------------------------------------------------------------
;;
;; `re-frame.hicasso.server` keeps these private, and §3 and §4 are the only
;; readers of them in the corpus: proving a listener was RELEASED needs a
;; name to look for, and proving the two doors cannot collide needs both
;; names. Renaming an id in the source breaks these two rows and nothing
;; else — which is the intended blast radius, since the rows exist to pin
;; that the ids are two and that each door clears its own.

(def ^:private render-listener-id      :re-frame.hicasso.server/render-recovered-error)
(def ^:private render-body-listener-id :re-frame.hicasso.server/render-body-recovered-error)

(defn- live-error-listener-ids []
  (set (keys @error-emit/listeners)))

(defn- live-frame-ids []
  (set (keys @frame/frames)))

;; ---------------------------------------------------------------------------
;; The probes
;; ---------------------------------------------------------------------------

(h/defview page
  "Reads one ordinary sub. The tree the control renders and the tree the
  refusal renders differ in the SUB and nothing else."
  [_]
  [:div.page [:p.label (str (h/sub [::label]))]])

(h/defview detonating
  "Reads the sub that throws. The framework recovers it to `nil`, so this
  body returns normally and the render produces markup — which is exactly
  the hazard §1 is about."
  [_]
  [:div.page [:p.label (str (h/sub [::detonates]))]])

(def ^:private !listeners-mid-render
  "What the `:errors` registry held while a render was in flight. §4's
  whole instrument."
  (atom ::unset))

(h/defview watching
  "Reads the live `:errors` listener registry from INSIDE the render — a
  view body is a callback React runs during `renderToString`, so this is
  the one moment the door's window is observably open."
  [_]
  (reset! !listeners-mid-render (live-error-listener-ids))
  [:div.page [:p.label (str (h/sub [::label]))]])

;; ---------------------------------------------------------------------------
;; The request
;; ---------------------------------------------------------------------------

(def ^:private wire-frame ::app-main)

(defn- request
  "One request's options, with `extra` merged over the defaults. `:payload`
  is REQUIRED by the framework's fail-closed policy, so it is in the
  defaults rather than left to each row."
  [& {:as extra}]
  (merge {:hiccup          [page {}]
          :snapshot        {:label "alpha"}
          :payload         [:label]
          :client-frame-id wire-frame}
         extra))

;; ---------------------------------------------------------------------------
;; §1 — the refusal
;; ---------------------------------------------------------------------------

(deftest a-recovered-render-error-fails-the-whole-page-render
  (let [thrown (try (server/render (request :hiccup [detonating {}]))
                    (catch :default e e))]
    (is (instance? ExceptionInfo thrown)
        "a sub that throws mid-render must not answer a document with a hole in the page")
    (let [data (ex-data thrown)]
      (is (= :rf.error/ssr-render-failed (:rf.error/id data)))
      (is (= 're-frame.hicasso.server/render (:where data))
          "the WHOLE-PAGE door names itself — the sibling entry's row pins
           `render-body`, and a shared raiser symbol would let one row pass
           for the other")
      (is (= :fail-the-render (:recovery data)))
      (is (pos? (:recorded data)) "the refusal counts what it saw")
      (is (= :rf.error/sub-exception (:error (:record data)))
          "and names the category, so the host's log points at the real surface"))))

(deftest the-refusal-lands-before-the-envelope-is-built
  (testing "a page being refused has no payload worth assembling, and
            building one first would let `apply-policy`'s own fail-closed
            refusal mask the render's. Omitting `:payload` from a
            DETONATING request must still answer the render's verdict, not
            the policy's"
    (let [data (try (server/render (dissoc (request :hiccup [detonating {}]) :payload))
                    (catch :default e (ex-data e)))]
      (is (= :rf.error/ssr-render-failed (:rf.error/id data))
          "the render's verdict wins"))
    (testing "control: the same omission on a CLEAN render does reach the
              policy, so the row above is about ordering and not about
              `:payload` being optional"
      (let [data (try (server/render (dissoc (request) :payload))
                      (catch :default e (ex-data e)))]
        (is (= :rf.error/ssr-missing-payload-policy (:rf.error/id data)))))))

;; ---------------------------------------------------------------------------
;; §2 — the control: the existing shape, unchanged
;; ---------------------------------------------------------------------------

(deftest the-control-a-tree-with-no-recovered-error-returns-the-existing-shape
  (let [{:keys [frame-id html payload payload-edn payload-script document] :as result}
        (server/render (request))]
    (is (= #{:frame-id :html :payload :payload-edn :payload-script :document}
           (set (keys result)))
        "the response map's keys are exactly the six the door has always answered")
    (is (keyword? frame-id))
    (is (str/includes? html "alpha")   "the snapshot reached the view")
    (is (str/includes? html "class=\"label\""))
    (is (= "alpha" (:label payload))   "the allowlisted key rides the payload")
    (is (= wire-frame (:rf/frame-id payload))
        "the WIRE id, not the per-request gensym")
    (is (= payload-edn (pr-str payload)))
    (is (str/includes? payload-script payload-edn))
    (is (str/starts-with? document "<!DOCTYPE html>"))
    (is (str/includes? document html)          "the document wraps the markup")
    (is (str/includes? document payload-script) "and carries the payload script")
    (is (not (str/includes? document (name frame-id)))
        "and the per-request gensym is nowhere in it")))

;; ---------------------------------------------------------------------------
;; §3 — release, on all three exits
;; ---------------------------------------------------------------------------
;;
;; Success, recovered-error refusal, and a direct render throw. Each row
;; asserts BOTH releases, because the `finally` releases both and a
;; regression that dropped one would otherwise hide behind the other.

(deftest the-frame-and-the-listener-are-released-on-every-exit
  (let [frames-before    (live-frame-ids)
        listeners-before (live-error-listener-ids)]
    (is (not (contains? listeners-before render-listener-id))
        "precondition: the door's listener is not already registered, so the
         absences below are the `finally` doing its job and not a vacuous
         never-registered")

    (testing "success"
      (server/render (request))
      (is (= frames-before (live-frame-ids))    "no frame left behind")
      (is (= listeners-before (live-error-listener-ids)) "and no listener"))

    (testing "recovered-error refusal"
      (is (thrown? :default (server/render (request :hiccup [detonating {}]))))
      (is (= frames-before (live-frame-ids)))
      (is (= listeners-before (live-error-listener-ids))))

    (testing "a direct render throw — the view takes the render down, so the
              verdict is never reached and only the `finally` can clean up"
      (is (thrown? :default
                   (server/render (request :hiccup [(fn [] (throw (js/Error. "boom")))]))))
      (is (= frames-before (live-frame-ids)))
      (is (= listeners-before (live-error-listener-ids))))

    (testing "and the runtime is not left mid-render: the next request still
              renders, which is what makes the three rows above evidence of
              cleanup rather than of a door that stopped working"
      (is (str/includes? (:html (server/render (request))) "alpha")))))

;; ---------------------------------------------------------------------------
;; §4 — the two doors do not share a listener id
;; ---------------------------------------------------------------------------
;;
;; A shared id is the obvious hazard: re-registering REPLACES, so a
;; re-entrant call would let the inner `finally` unregister the OUTER
;; door's listener and leave the outer render blind for the rest of its
;; pass — the exact silent-wrong-page failure both doors exist to catch.
;; The ids are two, and these rows read them at the one moment it matters.

(deftest each-door-arms-its-own-listener-and-only-its-own
  (testing "`render`'s window, observed from inside `render`"
    (reset! !listeners-mid-render ::unset)
    (server/render (request :hiccup [watching {}]))
    (let [live @!listeners-mid-render]
      (is (set? live) "the probe ran inside the render")
      (is (contains? live render-listener-id)
          "the whole-page door's listener is armed while its render is in flight")
      (is (not (contains? live render-body-listener-id))
          "and the body-only door's is not — the ids are two, so neither
           door can unregister the other's")))

  (testing "`render-body`'s window, observed from inside `render-body`"
    (reset! !listeners-mid-render ::unset)
    (server/render-body {:hiccup       [watching {}]
                         :render-state {:rf/app-db {:label "alpha"} :rf/runtime-db {}}})
    (let [live @!listeners-mid-render]
      (is (set? live))
      (is (contains? live render-body-listener-id))
      (is (not (contains? live render-listener-id))))))

(deftest render-body-keeps-its-refusal-and-leaves-no-residue
  (testing "the sibling door's semantics are unchanged by the whole-page
            door gaining the same verdict, and running one after the other
            leaves nothing behind"
    (let [listeners-before (live-error-listener-ids)
          data             (try (server/render-body
                                  {:hiccup       [detonating {}]
                                   :render-state {:rf/app-db {:label "alpha"} :rf/runtime-db {}}})
                                (catch :default e (ex-data e)))]
      (is (= :rf.error/ssr-render-failed (:rf.error/id data)))
      (is (= 're-frame.hicasso.server/render-body (:where data))
          "still the body-only door's own symbol")
      (is (= listeners-before (live-error-listener-ids)))
      (is (thrown? :default (server/render (request :hiccup [detonating {}])))
          "and the whole-page door refuses right after it")
      (is (= listeners-before (live-error-listener-ids))
          "with both registries back where they started"))))
