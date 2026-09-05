(ns re-frame.nine-states-overlap-cljs-test
  "rf2-5bmi — the DETERMINISTIC overlap witness for the Nine States example's
  newest-intent-wins guarantee (rf2-t9va landed the shared stable
  `:request-id`; this proves it does the work claimed for it).

  ## Why it lives here and not beside the example

  The example tree is TEST-FREE by a locked project rule (rf2-8cevm) — no
  `*.spec.cjs`, and no test namespace of any kind, may live under `examples/`.
  So the fixtures that exercise an example live in the framework test tree, and
  `implementation/http/test/` is the right shelf for THIS one: its subject is
  the managed-HTTP in-flight registry and its same-`:request-id` supersession
  boundary, which is this artefact's own contract. The sibling
  `re-frame.http-managed-demo-frame-isolation-cljs-test` is the standing
  precedent — an example-behaviour regression parked here for exactly that
  reason. Both resolve their example namespace because the consolidated
  `:node-test` CLJS build carries `http/test` and the `../examples/*` roots on
  one classpath, so `nine-states.core` registers its events / subs / machine at
  ns-load and this suite drives the SHIPPED events rather than a copy of them.

  ## Why the example's own stub cannot prove this

  The running app routes `:rf.http/managed` through an `:fx-overrides` demo
  stub, and an override REPLACES the effect outright: the framework's in-flight
  registry — and with it supersession — never runs, and the canned replies
  settle synchronously one at a time. So no gate executed the superseding path
  for this example at all. These tests therefore create their frames with NO
  `:fx-overrides`, so the REAL `:rf.http/managed` effect runs, and control only
  the TRANSPORT.

  ## Why it is deterministic rather than a race

  Nothing here sleeps and hopes. `js/fetch` is stubbed with a transport that
  PARKS each attempt inside its own body read: the response headers resolve
  immediately, and `.text()` hands back a promise whose resolver the TEST holds.
  An attempt therefore sits open, mid-finalisation, until this file settles it
  by hand — so the interleaving is PLACED, not raced. `poll-until` waits only
  for a state this file then acts on, and never for a deadline to decide an
  assertion. That harness is the one `re-frame.http-cljs-test` already uses for
  its supersede-race coverage (`with-controlled-body-fetch`); this is the
  multi-attempt form of it.

  ## The teeth

  Each row holds attempt A open, issues attempt B through the shipped example
  event while A is still open, then settles A FIRST and proves the machine did
  not move — A's reply reached no app target and mutated nothing — before
  settling B and proving exactly B's result. One row crosses request KIND in
  each direction, so `:nine-states.demo/load` and
  `:nine-states.demo/load-with-failure` are proven to share ONE replacement
  lane. A reset-then-reload row covers the window `:reset` alone leaves open.
  And a negative control issues the overlap under a DIFFERENT id and asserts
  the OPPOSITE outcome — the stale reply lands and clobbers the machine —
  so the shared id is shown to be what does the work.

  ## Per-row isolation

  `cljs.test` runs a wrap-style `use-fixtures` around the whole `deftest`, which
  would tear down before an `async` body finished, and a `:each` fixture resets
  once per `deftest` and NOT once per row of a table. Both are wrong here, so
  this ns does neither: setup is inline (mirroring `re-frame.http-cljs-test`,
  which carries `async` tests for the same reason) and EVERY row builds its own
  fresh frame and clears the in-flight registry itself, inside the driver. Each
  row's assertions read that row's own frame, so a row cannot observe a board an
  earlier row left behind. `overlap-cross-kind-row-in-isolation` runs one row on
  its own to pin that claim from the other side."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as string]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Drives the REAL managed pipeline: fx registration + the
            ;; in-flight registry whose same-id supersession is the subject.
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            ;; The example's production source. Requiring it registers the
            ;; events / subs / machine this suite drives.
            [nine-states.core :as nine-states]))

;; ---------------------------------------------------------------------------
;; The controlled transport — the interleaving is PLACED, never raced
;; ---------------------------------------------------------------------------

(def ^:private fail-url-re
  "The example's rigged-to-fail endpoint. `:nine-states.demo/load-with-failure`
  issues it; every other load hits the succeeding one. Matched rather than
  compared because the transport appends the query string."
  #"/api/todos/fail")

(defn- parked-response
  "A Fetch `Response` stand-in whose HEADERS resolve immediately but whose
  `.text()` body reader returns a promise this file settles by hand. Sets
  `read-fired` true the moment the reader is invoked (the framework has the
  Response and is mid-finalisation) and captures the resolver into `settle`.
  `status` selects the success / failure arm, matching the example's own
  URL routing."
  [status read-fired settle]
  #js {:ok         (and (>= status 200) (< status 300))
       :status     status
       :statusText ""
       ;; Fetch-`Headers`-like: `forEach (value key)`.
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn []
                     (reset! read-fired true)
                     (js/Promise. (fn [res _] (reset! settle res))))})

(defn- with-parked-fetch
  "Stub `js/fetch` so EVERY attempt parks inside its own body read. Returns
  `{:restore :attempts}`, where `attempts` is an atom holding one map per
  attempt IN ISSUE ORDER — `{:url :read-fired :settle}`. Ordering by issue is
  what lets a row address 'the older attempt' and 'the newer attempt' without
  keying off a URL, so two loads of the SAME kind are still separable."
  []
  (let [orig     (.-fetch js/globalThis)
        attempts (atom [])]
    (set! (.-fetch js/globalThis)
          (fn [url _init]
            (let [url-str    (str url)
                  read-fired (atom false)
                  settle     (atom nil)
                  status     (if (re-find fail-url-re url-str) 500 200)]
              (swap! attempts conj {:url        url-str
                                    :read-fired read-fired
                                    :settle     settle})
              (js/Promise.resolve (parked-response status read-fired settle)))))
    {:restore  (fn [] (set! (.-fetch js/globalThis) orig))
     :attempts attempts}))

(defn- attempt-open?
  "True once attempt `i` has been issued AND is parked inside its body read —
  the exact moment this file can act on it."
  [attempts i]
  (let [a (get @attempts i)]
    (boolean (and a @(:read-fired a) (some? @(:settle a))))))

(defn- settle-attempt!
  "Settle attempt `i`'s parked body with `body-text`."
  [attempts i body-text]
  (let [res @(:settle (get @attempts i))]
    (res body-text)))

(defn- next-macrotask
  "Resolves on the next `setTimeout 0` macrotask, so every queued microtask
  (handle-response! -> finalise-*) drains. A suppressed reply would have landed
  by the time this settles, which is what makes the 'machine did not move'
  assertions load-bearing rather than merely early."
  []
  (js/Promise. (fn [resolve _] (js/setTimeout resolve 0))))

;; ---------------------------------------------------------------------------
;; Example-shaped fixtures
;; ---------------------------------------------------------------------------

(defn- todos-json
  "`n` todos as the JSON body the example's decode expects. `json-parse`
  keywordizes object keys, so this decodes to the `Todo` shape the machine's
  `[:schemas :data]` slot validates on every transition."
  [n]
  (str "["
       (string/join
         ","
         (for [i (range n)]
           (str "{\"id\":\"todo-" i "\",\"title\":\"Todo #" (inc i) "\",\"done?\":false}")))
       "]"))

(def ^:private failure-json
  "A body for the rigged-to-fail endpoint. The 500 classifies before the body
  matters, but the reader still runs, so it must be well-formed."
  "{\"message\":\"Network unreachable.\"}")

(defn- body-for
  "The body text an attempt at `url` should settle with. `n` sizes the success
  payload so a row can pin WHICH load won by cardinality alone."
  [url n]
  (if (re-find fail-url-re url)
    failure-json
    (todos-json n)))

(defn- fresh-frame!
  "A frame per ROW — this is the per-row reset. No `:fx-overrides`, so the REAL
  `:rf.http/managed` runs. `:nine-states.app/initialise` seeds the form slice
  and broadcasts `:reset`, so the machine starts every row at `:nothing` with
  its owned data cleared."
  [label]
  (rf.http.managed/clear-all-in-flight!)
  (let [f (rf.frame/make-anon-frame-record! {:doc label})]
    (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
    f))

(defn- snapshot
  "The `:ui/nine-states` machine snapshot — `:state` (region -> state for a
  parallel machine), the `:tags` union, and the shared `:data`."
  [frame]
  (get-in (rf/frame-state-value frame)
          [:rf.db/runtime :rf.runtime/machines :snapshots :ui/nine-states]))

(defn- data-state [frame] (get-in (snapshot frame) [:state :data]))
(defn- tags [frame] (:tags (snapshot frame)))
(defn- items [frame] (get-in (snapshot frame) [:data :items]))
(defn- recorded-error [frame] (get-in (snapshot frame) [:data :error]))
(defn- render-model [frame] (rf/compute-sub [:ui/render] (rf/frame-state-value frame)))

;; ---------------------------------------------------------------------------
;; The row driver
;; ---------------------------------------------------------------------------

(defn- run-overlap-row!
  "Drive ONE overlap row and return a promise that settles when its assertions
  are done.

  `older` / `newer` are shipped example events. `older-n` / `newer-n` size each
  attempt's success payload. `expect` pins the settled board.

  The sequence is fixed and fully placed:
    1. issue `older`      -> park attempt 0 mid body read
    2. issue `newer`      -> park attempt 1; same `:request-id` supersedes 0
    3. settle attempt 0   -> drain -> the machine MUST NOT have moved
    4. settle attempt 1   -> drain -> exactly the newer result"
  [{:keys [label older newer older-n newer-n expect]}]
  (let [{:keys [restore attempts]} (with-parked-fetch)
        frame (fresh-frame! label)]
    (testing label
      (is (= :nothing (data-state frame))
          "the row starts from a clean board — the per-row frame + :reset")
      (rf/dispatch-sync older {:frame frame})
      (-> (rf.test-support/poll-until
            #(when (attempt-open? attempts 0) true)
            {:timeout-ms 2000 :label "nine-states overlap: older attempt open"})
          (.then
            (fn [_]
              (is (= :loading (data-state frame))
                  "the older intent moved the :data region to :loading")
              (is (= :loading (render-model frame)) "and :ui/render says :loading")
              (is (some? (rf.http.registry/lookup-in-flight
                           frame nine-states/data-load-request-id))
                  "the older attempt is registered under the page's ONE data-load id")
              ;; The newer intent, issued while the older is still open.
              (rf/dispatch-sync newer {:frame frame})
              (rf.test-support/poll-until
                #(when (attempt-open? attempts 1) true)
                {:timeout-ms 2000 :label "nine-states overlap: newer attempt open"})))
          (.then
            (fn [_]
              (is (= 2 (count @attempts))
                  "both intents really reached the transport — the overlap is real")
              (is (some? (rf.http.registry/lookup-in-flight
                           frame nine-states/data-load-request-id))
                  "the newer attempt REPLACED the older one under the same id")
              ;; Settle the SUPERSEDED attempt first. This is the whole point.
              (settle-attempt! attempts 0 (body-for (:url (get @attempts 0)) older-n))
              (next-macrotask)))
          (.then (fn [_] (next-macrotask)))
          (.then
            (fn [_]
              (testing "TOOTH — the superseded attempt's completion reaches NO app
                        target and cannot move or mutate the machine"
                (is (= :loading (data-state frame))
                    "the :data region is STILL :loading — the stale reply moved nothing")
                (is (= :loading (render-model frame))
                    ":ui/render is still :loading")
                (is (empty? (items frame))
                    "no :items were written by the superseded attempt")
                (is (nil? (recorded-error frame))
                    "no :error was recorded by the superseded attempt"))
              ;; Now the winner.
              (settle-attempt! attempts 1 (body-for (:url (get @attempts 1)) newer-n))
              (next-macrotask)))
          (.then (fn [_] (next-macrotask)))
          (.then
            (fn [_]
              (testing "the NEWER intent's result is the one that lands, whole"
                (is (= (:data-state expect) (data-state frame))
                    "the :data region settled on the newer attempt's bucket")
                (is (contains? (tags frame) (:tag expect))
                    "the newer attempt's tag is in the machine's tag union")
                (is (= (:render expect) (render-model frame))
                    ":ui/render resolves to the newer attempt's view")
                (is (= (:item-count expect) (count (items frame)))
                    "exactly the newer attempt's :items")
                (if (:error? expect)
                  (is (some? (recorded-error frame))
                      "the newer FAILURE recorded its :error")
                  (is (nil? (recorded-error frame))
                      "a newer SUCCESS leaves no :error behind")))))
          (.catch (fn [e]
                    (is false (str "rf2-5bmi — unexpected in row " label ": " e))
                    nil))
          (.finally (fn [] (restore)))))))

(def ^:private overlap-rows
  "Newest-intent-wins across every combination that matters. `too-many-threshold`
  is 7, so 25 items is `:too-many`, 1 is `:one`, 4 is `:some`."
  [{:label      "same kind — an older success is superseded by a newer success"
    :older      [:nine-states.demo/load {:n 25}]
    :newer      [:nine-states.demo/load {:n 1}]
    :older-n    25
    :newer-n    1
    :expect     {:data-state :one :tag :data/one :render :one
                 :item-count 1 :error? false}}

   {:label      "CROSS KIND — an older success is superseded by a newer failure"
    :older      [:nine-states.demo/load {:n 4}]
    :newer      [:nine-states.demo/load-with-failure]
    :older-n    4
    :newer-n    0
    :expect     {:data-state :error :tag :data/error :render :error
                 :item-count 0 :error? true}}

   {:label      "CROSS KIND — an older failure is superseded by a newer success"
    :older      [:nine-states.demo/load-with-failure]
    :newer      [:nine-states.demo/load {:n 4}]
    :older-n    0
    :newer-n    4
    :expect     {:data-state :some :tag :data/some :render :some
                 :item-count 4 :error? false}}])

(defn- run-rows!
  "Sequential promise chain over the rows. Sequential because each row owns the
  global `js/fetch` stub for its duration."
  [rows]
  (if-let [row (first rows)]
    (-> (run-overlap-row! row)
        (.then (fn [_] (run-rows! (rest rows)))))
    (js/Promise.resolve :done)))

;; ---------------------------------------------------------------------------
;; The witness
;; ---------------------------------------------------------------------------

(deftest overlapping-loads-are-newest-intent-wins
  (testing "rf2-5bmi / rf2-t9va — both shipped load events fill ONE data-load
  slot under one stable :request-id, so issuing either while the other is still
  in flight supersedes it. The superseded attempt's completion — success or
  failure — reaches no app target and cannot move the :ui/nine-states machine;
  the newest intent's result lands whole."
    (async done
      ;; Inline setup: a wrap-style `use-fixtures` would tear down before this
      ;; async body finished, and a `:each` fixture would reset once for the
      ;; whole deftest rather than once per ROW. Each row resets itself.
      (rf/init! rf.adapter.reagent/adapter)
      (rf.frame/ensure-default-frame!)
      (-> (run-rows! overlap-rows)
          (.catch (fn [e] (is false (str "rf2-5bmi — unexpected: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest overlap-cross-kind-row-in-isolation
  (testing "rf2-5bmi — the same cross-kind row run ALONE. A table-driven suite
  can pass only because an earlier row left the board in a helpful state, so one
  row is pinned in isolation too; it must reach the identical verdict."
    (async done
      (rf/init! rf.adapter.reagent/adapter)
      (rf.frame/ensure-default-frame!)
      (-> (run-overlap-row! (second overlap-rows))
          (.catch (fn [e] (is false (str "rf2-5bmi — unexpected: " e)) nil))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Reset-then-reload — the window `:reset` alone leaves open
;; ---------------------------------------------------------------------------

(deftest reset-then-reload-cannot-be-overwritten-by-the-pre-reset-attempt
  (testing "rf2-5bmi — `:reset` abandons the view but NOT the request: it drops
  :data to :nothing and leaves the in-flight attempt live. The existing
  reset-to-:nothing coverage settles a completion while still AT :nothing, where
  it is trivially ignored. The dangerous window is the RELOAD, which puts the
  region back at :loading — a state that accepts :fetch-succeeded. What closes
  it is the same-id supersession on that reload, and this is that proof."
    (async done
      (rf/init! rf.adapter.reagent/adapter)
      (rf.frame/ensure-default-frame!)
      (let [{:keys [restore attempts]} (with-parked-fetch)
            frame (fresh-frame! "reset-then-reload")]
        (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame frame})
        (-> (rf.test-support/poll-until
              #(when (attempt-open? attempts 0) true)
              {:timeout-ms 2000 :label "reset-then-reload: pre-reset attempt open"})
            (.then
              (fn [_]
                (is (= :loading (data-state frame)) "the pre-reset load is in flight")
                ;; `:reset` — the view goes back to square one, the request does not.
                (rf/dispatch-sync [:nine-states.app/initialise] {:frame frame})
                (is (= :nothing (data-state frame))
                    ":reset dropped the :data region to :nothing")
                (is (some? (rf.http.registry/lookup-in-flight
                             frame nine-states/data-load-request-id))
                    "TOOTH — :reset did NOT abort the in-flight attempt; it is still registered")
                ;; The reload. THIS is what supersedes the abandoned attempt.
                (rf/dispatch-sync [:nine-states.demo/load {:n 1}] {:frame frame})
                (rf.test-support/poll-until
                  #(when (attempt-open? attempts 1) true)
                  {:timeout-ms 2000 :label "reset-then-reload: reload attempt open"})))
            (.then
              (fn [_]
                (is (= :loading (data-state frame))
                    "the reload put the region back at :loading — the state that would
                     have accepted the abandoned attempt's reply")
                (settle-attempt! attempts 0 (todos-json 25))
                (next-macrotask)))
            (.then (fn [_] (next-macrotask)))
            (.then
              (fn [_]
                (is (= :loading (data-state frame))
                    "the pre-reset attempt's completion did NOT resurrect its result")
                (is (empty? (items frame))
                    "no pre-reset :items were written")
                (settle-attempt! attempts 1 (todos-json 1))
                (next-macrotask)))
            (.then (fn [_] (next-macrotask)))
            (.then
              (fn [_]
                (is (= :one (data-state frame)) "the RELOAD's result is the one that lands")
                (is (= 1 (count (items frame))) "exactly the reload's single item")
                (is (= :one (render-model frame)) ":ui/render follows the reload")))
            (.catch (fn [e] (is false (str "rf2-5bmi — unexpected: " e)) nil))
            (.then (fn [_] (restore) (done))))))))

;; ---------------------------------------------------------------------------
;; Negative control — the shared id is what does the work
;; ---------------------------------------------------------------------------

(rf/reg-event :nine-states.overlap-test/load-under-a-different-id
  {:doc "NEGATIVE CONTROL (rf2-5bmi). Byte-for-byte the shipped
         `:nine-states.demo/load` request, except for the one field under test:
         a DIFFERENT `:request-id`. Test-local; the example is untouched. If
         the shared id were incidental rather than load-bearing, this event
         would behave exactly like the shipped one and the control below would
         be unable to tell them apart."}
  (fn handler-overlap-test-load [_ [_ {:keys [n]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           {:request    {:method :get
                         :url    "/api/todos"
                         :query  {:n (or n 0)}}
            :decode     :json
            :request-id :nine-states.overlap-test/a-different-slot
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

(deftest a-different-request-id-lets-the-stale-reply-through
  (testing "rf2-5bmi NEGATIVE CONTROL — run the identical overlap with the newer
  request issued under a DIFFERENT :request-id and the witness inverts: nothing
  supersedes the older attempt, so its late completion DOES reach
  :nine-states.demo/loaded and DOES clobber the machine with the stale result.
  That is the failure the shared `data-load-request-id` exists to prevent, and
  it is what makes every assertion above a property of the shared id rather than
  of the harness."
    (async done
      (rf/init! rf.adapter.reagent/adapter)
      (rf.frame/ensure-default-frame!)
      (let [{:keys [restore attempts]} (with-parked-fetch)
            frame (fresh-frame! "negative control — different request id")]
        (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame frame})
        (-> (rf.test-support/poll-until
              #(when (attempt-open? attempts 0) true)
              {:timeout-ms 2000 :label "negative control: older attempt open"})
            (.then
              (fn [_]
                ;; Same overlap, one field different.
                (rf/dispatch-sync
                  [:nine-states.overlap-test/load-under-a-different-id {:n 1}]
                  {:frame frame})
                (rf.test-support/poll-until
                  #(when (attempt-open? attempts 1) true)
                  {:timeout-ms 2000 :label "negative control: newer attempt open"})))
            (.then
              (fn [_]
                (is (some? (rf.http.registry/lookup-in-flight
                             frame nine-states/data-load-request-id))
                    "the older attempt is STILL registered under its own id — nothing superseded it")
                (settle-attempt! attempts 0 (todos-json 25))
                (next-macrotask)))
            (.then (fn [_] (next-macrotask)))
            (.then
              (fn [_]
                (testing "TOOTH (inverted) — with no shared id the stale reply lands"
                  (is (= :too-many (data-state frame))
                      "the SUPERSEDED-in-intent attempt moved the machine — the very
                       thing the shared id prevents")
                  (is (= 25 (count (items frame)))
                      "and wrote its stale :items"))))
            (.catch (fn [e] (is false (str "rf2-5bmi — unexpected: " e)) nil))
            (.then (fn [_] (restore) (done))))))))
