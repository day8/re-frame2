(ns re-frame.hicasso.server-render-body-ssr-cljs-test
  "`re-frame.hicasso.server/render-body` — the body-only entry, measured on
  its own (rf2-8arzr.5, slice E of the ssr-node crossing programme; the
  parent's shared contract S2/S3).

  The product witness for the whole crossing is
  `re-frame.hicasso.login-server-crossing-ssr-cljs-test`, which drives the
  real login example through the real sidecar module contract. This file is
  the narrow one: every row here is a claim about the ENTRY, written so a
  failure names the entry rather than the example.

  ## The four claims, and why the fourth is the one that matters

  1. It answers INNER MARKUP. No document, no payload, no `<script>` — the
     JVM owns those, and a renderer that emitted one would be a renderer
     deciding what the browser may see.
  2. It restores BOTH partitions in one write, with no boot-event replay.
     `render`'s `:snapshot` door seeds app-db alone; the render state is
     app-db AND runtime-db, because the route slice and the machine
     snapshots live in the second one.
  3. It hands React the caller's `identifierPrefix`, so a `useId` agrees
     with the hydrating client root that was handed the same string.
  4. **It FAILS the render when the runtime recorded an error it recovered
     from.** A reactive sub that throws mid-render does not take the render
     down — the framework's built-in recovery yields `nil` and the pass
     returns a string with a hole in it. On a JVM host that is survivable
     because the same process holds the buffered trace and projects it into
     a 5xx before any bytes ship. Across this crossing the buffer is in the
     NODE process and the projector is on the JVM, so the JVM cannot see
     it: a 200 would ship a wrong page as a success. §4 is therefore
     written as a PAIR — the refusal, and the control that renders the same
     tree with a sub that does not throw and gets its markup — because a
     refusal row on its own cannot tell 'the check fired' from 'the render
     was broken all along'.

  Runtime: `-cljs-test`, so the focused `:node-test-hicasso` build and the
  always-on `:node-test`. Every row renders to a string; none needs a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.server :as server]
            [re-frame.subs :as subs]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

;; Registered ABOVE `use-fixtures`, for the sibling suites' reason: the reset
;; fixture captures its source-store baseline when the `use-fixtures` form is
;; EVALUATED, so a registration written below it is erased before the first
;; row runs.

(rf/reg-sub ::greeting (fn [db _] (:greeting db)))

(subs/reg-runtime-sub ::flavour
  (fn [runtime-db _] (get-in runtime-db [::kitchen :flavour])))

(def ^:private !boot-events-run
  "Every event id the frame's setup vector managed to run. MUST stay empty:
  the JVM drained the boot events and the projection is the settled result."
  (atom []))

(rf/reg-event ::boot-event
  (fn [{:keys [db]} _]
    (swap! !boot-events-run conj ::boot-event)
    {:db (assoc db :greeting "FROM A REPLAYED BOOT EVENT")}))

;; The throwing sub of §4. An ORDINARY registered sub whose body throws —
;; not a hand-planted trace event — so the row measures the path a real
;; application takes rather than the shape of a fixture.
(rf/reg-sub ::detonates
  (fn [_ _] (throw (js/Error. "the sub that recovers to nil"))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `:ambient-frame nil` — `render-body` makes its own top-level frame,
     ;; and the fixture's carried `:rf/default` stamp would be a scope the
     ;; request is not rendering. The sibling `frame_doors_ssr` suite opts
     ;; out for the same reason.
     :ambient-frame nil
     :init-fn       (fn []
                      (reset! !boot-events-run [])
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The probes
;; ---------------------------------------------------------------------------

(h/defview both-partitions
  "Reads one app-db sub and one runtime-db sub, so a row can tell WHICH
  partition failed to restore rather than only that something did."
  [_]
  [:div.page
   [:p.greeting (str (h/sub [::greeting]))]
   [:p.flavour (str (h/sub [::flavour]))]])

(h/defview detonating
  "Reads the sub that throws. The framework recovers it to `nil`, so this
  body returns normally and the render produces markup — which is exactly
  the hazard §4 is about."
  [_]
  [:div.page [:p.greeting (str (h/sub [::detonates]))]])

(defn- id-probe
  "One `useId`, rendered as text. A React hook, so it is written where React
  hooks are written — a raw React component behind a `{:server :render}`
  host, the only policy that puts a foreign body into a server response."
  [^js _props]
  (react/createElement "b" #js {:className "probe"} (react/useId)))

(h/defhost id-host id-probe {:server :render})

(h/defview id-page
  [_]
  [:div.page [id-host {}]])

;; ---------------------------------------------------------------------------
;; The request
;; ---------------------------------------------------------------------------

(defn- state
  "A render-state envelope in the shape `render-state/deserialize` produces:
  both partitions, both maps."
  ([] (state "hello"))
  ([greeting]
   {:rf/app-db     {:greeting greeting}
    :rf/runtime-db {::kitchen {:flavour "vanilla"}}}))

(defn- render-body! [hiccup opts]
  (server/render-body (merge {:hiccup hiccup :render-state (state)} opts)))

(defn- live-frame-ids []
  (set (keys @frame/frames)))

;; ---------------------------------------------------------------------------
;; §1 — inner markup, and nothing else
;; ---------------------------------------------------------------------------

(deftest answers-the-inner-markup-alone
  (let [html (render-body! [both-partitions {}] {})]
    (is (string? html) "the entry answers a string, not a map")
    (is (str/includes? html "hello")
        "the app-db partition reached the view")
    (testing "no envelope of any kind"
      (is (not (str/includes? html "<!DOCTYPE")) "no document")
      (is (not (str/includes? html "<script")) "no payload script")
      (is (not (str/includes? html "__rf_payload")) "no payload at all")
      (is (not (str/includes? html "<body")) "no shell"))))

;; ---------------------------------------------------------------------------
;; §2 — both partitions, restored, with nothing replayed
;; ---------------------------------------------------------------------------

(deftest restores-both-partitions
  (let [html (render-body! [both-partitions {}] {})]
    (is (str/includes? html "hello")   "the app-db partition")
    (is (str/includes? html "vanilla") "the runtime-db partition")))

(deftest replays-no-boot-events-even-when-frame-opts-declares-them
  (let [html (render-body! [both-partitions {}]
                           {:frame-opts {:initial-events [[::boot-event]]}})]
    (is (= [] @!boot-events-run)
        "the JVM drained the boot events; a second drain here would run them against a state that has already moved past them")
    (is (str/includes? html "hello")
        "and the projection — not the replay — is what the view read")
    (is (not (str/includes? html "FROM A REPLAYED BOOT EVENT")))))

(deftest a-non-envelope-render-state-is-refused-before-anything-renders
  (let [thrown (try (render-body! [both-partitions {}] {:render-state {:nope 1}})
                    (catch :default e (ex-data e)))]
    (is (= :rf.error/ssr-render-state-invalid (:rf.error/id thrown))
        "restore!'s own fail-closed envelope guard, reached through this entry")
    (is (= :envelope (:invalid thrown)))))

;; ---------------------------------------------------------------------------
;; §3 — the identifier prefix
;; ---------------------------------------------------------------------------

(deftest the-identifier-prefix-reaches-react
  (let [a (render-body! [id-page {}] {:identifier-prefix "pfx-a-"})
        b (render-body! [id-page {}] {:identifier-prefix "pfx-b-"})]
    (is (str/includes? a "pfx-a-") "React derived the id under the caller's prefix")
    (is (str/includes? b "pfx-b-"))
    (is (not= a b)
        "two prefixes, two documents — the premise the equality above rests on")))

;; ---------------------------------------------------------------------------
;; §4 — the recovered render error, and its control
;; ---------------------------------------------------------------------------
;;
;; Read the pair together. The control proves the tree renders and the
;; refusal proves the check bites; either alone is a green that means
;; nothing.

(deftest a-recovered-render-error-fails-the-render
  (let [thrown (try (render-body! [detonating {}] {})
                    (catch :default e e))]
    (is (instance? ExceptionInfo thrown)
        "a sub that throws mid-render must not answer 200 with a hole in the page")
    (let [data (ex-data thrown)]
      (is (= :rf.error/ssr-render-failed (:rf.error/id data)))
      (is (= 're-frame.hicasso.server/render-body (:where data)))
      (is (= :fail-the-render (:recovery data)))
      (is (pos? (:recorded data)) "the refusal counts what it saw")
      (is (= :rf.error/sub-exception (:error (:record data)))
          "and names the category, so the sidecar log points at the real surface"))))

(deftest the-control-a-tree-with-no-recovered-error-renders
  (let [html (render-body! [both-partitions {}] {})]
    (is (string? html))
    (is (str/includes? html "hello")
        "the same entry, the same shape of tree, no refusal — so the row above measures the recovered error and not the entry")))

;; ---------------------------------------------------------------------------
;; §5 — teardown, on both exits
;; ---------------------------------------------------------------------------

(deftest the-request-frame-is-destroyed-on-success-and-on-failure
  (let [before (live-frame-ids)]
    (render-body! [both-partitions {}] {})
    (is (= before (live-frame-ids)) "success leaves no frame behind")
    (try (render-body! [detonating {}] {}) (catch :default _ nil))
    (is (= before (live-frame-ids)) "and neither does a refusal")))
