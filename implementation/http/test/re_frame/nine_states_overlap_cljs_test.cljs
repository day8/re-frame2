(ns re-frame.nine-states-overlap-cljs-test
  "The deterministic overlap witness for the Nine States example's
  newest-intent-wins guarantee: its loads share one stable `:request-id`, so a
  newer load supersedes an older one still in flight.

  It lives here because `examples/` is test-free and its subject is this
  artefact's same-`:request-id` supersession. The `:node-test` build carries the
  example on its classpath, so these tests drive the SHIPPED events. The
  example's own `:fx-overrides` demo stub replaces the effect outright, so these
  frames run the REAL `:rf.http/managed` and control only the transport: every
  attempt parks inside its body read until the test settles it, so the
  interleaving is placed rather than raced. Every row builds its own frame."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as string]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [nine-states.core :as nine-states]))

;; ---- the parked transport ------------------------------------------------------

(def ^:private fail-url-re
  "The example's rigged-to-fail endpoint, matched because the transport appends
  the query string."
  #"/api/todos/fail")

(defn- parked-response
  "A Fetch `Response` whose headers resolve at once and whose `.text()` promise
  the test settles through `settle`; `read-fired` goes true when it is read."
  [status read-fired settle]
  #js {:ok         (and (>= status 200) (< status 300))
       :status     status
       :statusText ""
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn []
                     (reset! read-fired true)
                     (js/Promise. (fn [res _] (reset! settle res))))})

(defn- with-parked-fetch
  "Stub `js/fetch` so every attempt parks in its body read. Returns
  `{:restore :attempts}`; `attempts` holds `{:url :read-fired :settle}` per
  attempt in issue order, so two loads of the same kind stay separable."
  []
  (let [orig     (.-fetch js/globalThis)
        attempts (atom [])]
    (set! (.-fetch js/globalThis)
          (fn [url _init]
            (let [url-str    (str url)
                  read-fired (atom false)
                  settle     (atom nil)
                  status     (if (re-find fail-url-re url-str) 500 200)]
              (swap! attempts conj {:url url-str :read-fired read-fired :settle settle})
              (js/Promise.resolve (parked-response status read-fired settle)))))
    {:restore  (fn [] (set! (.-fetch js/globalThis) orig))
     :attempts attempts}))

(defn- attempt-open? [attempts i]
  (let [a (get @attempts i)]
    (boolean (and a @(:read-fired a) (some? @(:settle a))))))

(defn- await-open [attempts i label]
  (rf.test-support/poll-until #(when (attempt-open? attempts i) true)
                              {:timeout-ms 2000 :label label}))

(defn- settle-attempt! [attempts i body-text]
  (@(:settle (get @attempts i)) body-text))

(defn- drained
  "Two macrotasks: every queued finalise microtask has run, so a reply that
  should have been suppressed has had its chance to land."
  []
  (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve 0)))
      (.then (fn [_] (js/Promise. (fn [resolve _] (js/setTimeout resolve 0)))))))

;; ---- example-shaped fixtures ---------------------------------------------------

(defn- todos-json
  "`n` todos as the JSON the example decodes into its `Todo` shape."
  [n]
  (str "["
       (string/join "," (for [i (range n)]
                          (str "{\"id\":\"todo-" i "\",\"title\":\"Todo #" (inc i) "\",\"done?\":false}")))
       "]"))

(defn- body-for [url n]
  (if (re-find fail-url-re url) "{\"message\":\"Network unreachable.\"}" (todos-json n)))

(defn- fresh-frame!
  "A frame per row, with no `:fx-overrides`. `:nine-states.app/initialise`
  broadcasts `:reset`, so the machine starts at `:nothing`."
  [label]
  (rf.http.managed/clear-all-in-flight!)
  (let [f (rf.frame/make-anon-frame-record! {:doc label})]
    (rf/dispatch-sync [:nine-states.app/initialise] {:frame f})
    f))

(defn- snapshot [frame]
  (get-in (rf/frame-state-value frame)
          [:rf.db/runtime :rf.runtime/machines :snapshots :ui/nine-states]))

(defn- data-state [frame] (get-in (snapshot frame) [:state :data]))
(defn- items [frame] (get-in (snapshot frame) [:data :items]))
(defn- render-model [frame] (rf/compute-sub [:ui/render] (rf/frame-state-value frame)))

(defn- board
  "The settled board: :data state, :ui/render, item count, and whether an
  :error is recorded."
  [frame]
  [(data-state frame) (render-model frame) (count (items frame))
   (some? (get-in (snapshot frame) [:data :error]))])

(defn- in-flight? [frame]
  (some? (rf.http.registry/lookup-in-flight frame nine-states/data-load-request-id)))

;; ---- newest intent wins --------------------------------------------------------

(defn- run-overlap-row!
  "Hold the older attempt open, issue the newer, settle the OLDER first and
  prove the machine did not move, then settle the newer and prove its result."
  [{:keys [label older newer older-n newer-n expect]}]
  (let [{:keys [restore attempts]} (with-parked-fetch)
        frame (fresh-frame! label)]
    (testing label
      (rf/dispatch-sync older {:frame frame})
      (-> (await-open attempts 0 "nine-states overlap: older attempt open")
          (.then (fn [_]
                   (is (= [:loading :loading true]
                          [(data-state frame) (render-model frame) (in-flight? frame)]))
                   (rf/dispatch-sync newer {:frame frame})
                   (await-open attempts 1 "nine-states overlap: newer attempt open")))
          (.then (fn [_]
                   (settle-attempt! attempts 0 (body-for (:url (get @attempts 0)) older-n))
                   (drained)))
          (.then (fn [_]
                   (is (= [:loading :loading 0 false] (board frame))
                       "the superseded attempt's completion moved and wrote nothing")
                   (settle-attempt! attempts 1 (body-for (:url (get @attempts 1)) newer-n))
                   (drained)))
          (.then (fn [_]
                   (is (= (:board expect) (board frame)))
                   (is (contains? (:tags (snapshot frame)) (:tag expect)))))
          (.catch (fn [e] (is false (str "unexpected in row " label ": " e)) nil))
          (.finally (fn [] (restore)))))))

(def ^:private overlap-rows
  ;; The two cross-kind rows prove `load` and `load-with-failure` share one
  ;; replacement lane. 25 items is :too-many, 4 is :some, 1 is :one.
  [{:label   "same kind: an older success is superseded by a newer success"
    :older   [:nine-states.demo/load {:n 25}]
    :newer   [:nine-states.demo/load {:n 1}]
    :older-n 25
    :newer-n 1
    :expect  {:board [:one :one 1 false] :tag :data/one}}
   {:label   "cross kind: an older success is superseded by a newer failure"
    :older   [:nine-states.demo/load {:n 4}]
    :newer   [:nine-states.demo/load-with-failure]
    :older-n 4
    :newer-n 0
    :expect  {:board [:error :error 0 true] :tag :data/error}}
   {:label   "cross kind: an older failure is superseded by a newer success"
    :older   [:nine-states.demo/load-with-failure]
    :newer   [:nine-states.demo/load {:n 4}]
    :older-n 0
    :newer-n 4
    :expect  {:board [:some :some 4 false] :tag :data/some}}])

(defn- run-rows!
  "Sequential, because each row owns the global `js/fetch` stub."
  [rows]
  (if-let [row (first rows)]
    (-> (run-overlap-row! row)
        (.then (fn [_] (run-rows! (rest rows)))))
    (js/Promise.resolve :done)))

(deftest overlapping-loads-are-newest-intent-wins
  (async done
    (rf/init! rf.adapter.reagent/adapter)
    (rf.frame/ensure-default-frame!)
    (-> (run-rows! overlap-rows)
        (.catch (fn [e] (is false (str "unexpected: " e)) nil))
        (.then (fn [_] (done))))))

(deftest reset-then-reload-cannot-be-overwritten-by-the-pre-reset-attempt
  ;; `:reset` drops :data to :nothing but leaves the request live. The reload
  ;; puts the region back at :loading, which would accept the abandoned reply;
  ;; the reload's same-id supersession is what closes that window.
  (async done
    (rf/init! rf.adapter.reagent/adapter)
    (rf.frame/ensure-default-frame!)
    (let [{:keys [restore attempts]} (with-parked-fetch)
          frame (fresh-frame! "reset-then-reload")]
      (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame frame})
      (-> (await-open attempts 0 "reset-then-reload: pre-reset attempt open")
          (.then (fn [_]
                   (rf/dispatch-sync [:nine-states.app/initialise] {:frame frame})
                   (is (= [:nothing true] [(data-state frame) (in-flight? frame)]))
                   (rf/dispatch-sync [:nine-states.demo/load {:n 1}] {:frame frame})
                   (await-open attempts 1 "reset-then-reload: reload attempt open")))
          (.then (fn [_]
                   (settle-attempt! attempts 0 (todos-json 25))
                   (drained)))
          (.then (fn [_]
                   (is (= [:loading 0] [(data-state frame) (count (items frame))])
                       "the pre-reset attempt's completion resurrected nothing")
                   (settle-attempt! attempts 1 (todos-json 1))
                   (drained)))
          (.then (fn [_] (is (= [:one :one 1] (pop (board frame))))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))

;; ---- negative control: the shared id is what does the work ---------------------

(rf/reg-event :nine-states.overlap-test/load-under-a-different-id
  {:doc "The shipped `:nine-states.demo/load` request with a different `:request-id`."}
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
  ;; The same overlap under a different id inverts the witness: nothing
  ;; supersedes the older attempt, so its late reply clobbers the machine.
  (async done
    (rf/init! rf.adapter.reagent/adapter)
    (rf.frame/ensure-default-frame!)
    (let [{:keys [restore attempts]} (with-parked-fetch)
          frame (fresh-frame! "negative control: different request id")]
      (rf/dispatch-sync [:nine-states.demo/load {:n 25}] {:frame frame})
      (-> (await-open attempts 0 "negative control: older attempt open")
          (.then (fn [_]
                   (rf/dispatch-sync [:nine-states.overlap-test/load-under-a-different-id {:n 1}]
                                     {:frame frame})
                   (await-open attempts 1 "negative control: newer attempt open")))
          (.then (fn [_]
                   (is (in-flight? frame) "nothing superseded the older attempt")
                   (settle-attempt! attempts 0 (todos-json 25))
                   (drained)))
          (.then (fn [_] (is (= [:too-many 25] [(data-state frame) (count (items frame))]))))
          (.catch (fn [e] (is false (str "unexpected: " e)) nil))
          (.then (fn [_] (restore) (done)))))))
