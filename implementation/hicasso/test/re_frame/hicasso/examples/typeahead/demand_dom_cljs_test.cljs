(ns re-frame.hicasso.examples.typeahead.demand-dom-cljs-test
  "L3 — THE RESOURCE WITNESS, MOUNTED (rf2-hic-044).

  The rows the model tier cannot state. `l0-cljs-test` owns the ceremony
  census and the defect mutations; this file owns the three facts that
  only exist once React is really rendering the screen, and each one is
  evidence for a criterion frozen at `afbb58febc`.

  | row | criterion | what it establishes |
  |---|---|---|
  | [[a-committed-read-that-wants-a-resource-acquires-nothing]] | C1, C4 | the premise of the whole census: mounting a page whose panel is already open asks for nothing, because no path runs from commit to acquisition |
  | [[strictmodes-double-invoke-is-a-counted-abandonment-population]] | C3 | abandoned renders genuinely occur on THIS witness and the instrument counts them, so the fence is gateable |
  | [[a-request-outlives-the-read-when-the-page-unmounts]] | C2 | *demand outlives the read that wanted it*, on the shipped path with no mutation at all |
  | [[the-retained-structures-move-with-the-panel]] | C5 | the exact per-read and per-boundary structures the status quo retains, with the control that moves them |

  ## The instrument, and what it can and cannot say

  `hm/bodies-run` is the kit's page-wide work counter and `hm/census` its
  residue counter — the two readers on `re-frame.hicasso.test.mounted`
  that take no handle, because neither reads a mount. Nothing here reaches
  `impl.collector` or `impl.inventory` for a number the facade already
  publishes.

  **No figure in this file is a duration.** Every reading is a count of
  bodies, requests or retained entries, and every one of them is paired
  with a control that moves it in the stated direction — StrictMode on or
  off for the abandonment population, the panel open or closed for the
  retained structures, a release site present or absent for the request
  that outlives its read.

  ## Waiting

  On the condition, never on a duration: `re-frame.test-support/poll-until`,
  for the same reason the slice's flow suite gives. The stand-in service
  replies through `rf/dispatch`, exactly as a real HTTP client does, so
  every reply arrives through a router drain that `hm/settle!` cannot
  reach and a virtual clock deliberately does not drive.

  The one place that shape matters is the negative. A row that asserts *no
  request was made* has to give the absence at least as long as the
  positive arm needed, or it is asserting that nothing has happened YET.
  [[a-request-outlives-the-read-when-the-page-unmounts]] therefore arms
  its released arm FIRST and reads it LAST, after the unreleased arm's
  whole round trip has completed.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`), and
  each row degrades there to a STATED skip rather than to a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.typeahead.db :as db]
            [re-frame.hicasso.examples.typeahead.events :as events]
            [re-frame.hicasso.examples.typeahead.service :as service]
            [re-frame.hicasso.examples.typeahead.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a mounted resource witness needs a real React DOM — " why)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The MAP shape, because every row is `async`: `cljs.test` refuses an
     ;; async test under a fn-form fixture and aborts the namespace.
     :async?        true
     :init-fn       (fn []
                      ;; React's `act` queue is not the browser's
                      ;; scheduler, and every reading here is taken
                      ;; outside it.
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (service/reset-log!))}))

;; ---------------------------------------------------------------------------
;; Reading and driving the page
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))
(defn- nodes [m sel] (vec (array-seq (.querySelectorAll (:container m) sel))))
(defn- text [m sel] (some-> (node m sel) .-textContent))

(defn- type-into!
  "Type `v` into the search field — a foreign write followed by a real
  `input` event, which is what a keystroke is from React's side.

  Written through the PROTOTYPE's own `value` setter because React patches
  the instance setter to maintain its change tracker: a plain `set!`
  updates the tracker too, after which React reads the node as already
  agreeing and the `input` below reaches no handler."
  [m v]
  (let [n (node m "input.term")
        d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)
    (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
    (hm/settle! m)))

(defn- app-db [m] (rf/app-db-value (:frame m)))

(defn- searches [] (filterv #(= :search (:kind %)) (service/requests)))

(defn- seeded
  "An `app-db` the application could have arrived at, handed to a mount so
  that the page opens in a named state. Core's own `[:rf/set-db …]`
  vocabulary, which is what `hm/mount!`'s `:initial-events` documents."
  [search]
  [[:rf/set-db (update db/seed :search merge search)]])

(defn- mount-screen!
  ([] (mount-screen! [[::events/seed]]))
  ([initial-events] (hm/mount! [views/screen {}] {:initial-events initial-events})))

(defn- mount-strict!
  "The same screen under `React.StrictMode`, which runs every body twice
  and commits once. Written here rather than in the application because
  StrictMode is this file's variable: a consumer's own root would carry it
  in `app.cljs`, and a witness that baked it in could not measure with and
  without."
  ([] (mount-strict! [[::events/seed]]))
  ([initial-events]
   (hm/mount! [:> react/StrictMode {} [views/screen {}]]
              {:initial-events initial-events})))

(defn- settled [m pred label]
  (-> (test-support/poll-until pred {:label label})
      (.then (fn [_] (hm/settle! m)))))

;; ---------------------------------------------------------------------------
;; The premise — the page really is a typeahead
;; ---------------------------------------------------------------------------

(deftest the-typeahead-searches-and-chooses
  ;; Asserted before anything is asserted about it. Every row below reads
  ;; a number off this page, and a page that never searched would give
  ;; each of them a flattering zero.
  (if-not (browser?)
    (skip! "this row types into a real field and waits for a real reply")
    (async done
      (let [m (mount-screen!)]
        (type-into! m "ca")
        (is (nil? (node m ".suggestion")) "nothing is on screen yet")
        (-> (settled m #(node m ".suggestion") "the debounced search replies")
            (.then (fn [_]
                     (is (= 1 (count (searches))) "one keystroke burst, one request")
                     (is (= 3 (count (nodes m ".suggestion")))
                         "three catalogue rows start with `ca`")
                     (.click (node m ".suggestion-choose"))
                     (hm/settle! m)
                     (is (nil? (node m ".suggestion"))
                         "choosing closes the panel, so the suggestion read is gone")
                     (settled m #(node m ".detail-name") "the detail replies")))
            (.then (fn [_]
                     (is (= "Cataract" (text m ".detail-name")))
                     (-> (hm/unmount! m) (hm/assert-clean!))))
            (.then done))))))

;; ---------------------------------------------------------------------------
;; C1 and C4 — the premise the whole census rests on
;; ---------------------------------------------------------------------------

(deftest a-committed-read-that-wants-a-resource-acquires-nothing
  ;; THE FINDING THIS WITNESS EXISTS TO MAKE, and it needs no mutation:
  ;; the page mounts with its panel already open over a searchable term,
  ;; so `[::subs/suggestions "ca"]` is a COMMITTED READ from the first
  ;; frame — and the service is never asked. Nothing connects a commit to
  ;; an acquisition, which is why every OWNERSHIP row of the census had to
  ;; be written by hand at an intent instead.
  (if-not (browser?)
    (skip! "the read has to be genuinely committed, which needs a real commit")
    (async done
      (let [m (mount-screen! (seeded {:term "ca" :open? true}))]
        (is (some? (node m ".typeahead-panel"))
            "the panel is on screen, so its body ran and its read committed")
        (is (= [] (searches))
            "and NOTHING was asked for. A committed read that wants a
             resource acquires nothing, because no path runs from commit
             to acquisition")

        ;; The control: the same page, the same read, one intent. The
        ;; request appears — so the acquisition is real and it is the
        ;; INTENT that carries it, not the read.
        (hm/dispatch-and-settle! m [::events/focus])
        (is (= 1 (count (searches)))
            "an intent acquires; a commit does not")
        (-> (settled m #(node m ".suggestion") "the reply lands")
            (.then (fn [_] (-> (hm/unmount! m) (hm/assert-clean!))))
            (.then done))))))

;; ---------------------------------------------------------------------------
;; C3 — the abandonment population, counted on this witness
;; ---------------------------------------------------------------------------

(deftest strictmodes-double-invoke-is-a-counted-abandonment-population
  ;; C3 asks that abandoned renders genuinely OCCUR on the witness and
  ;; that the instrument can name them with a counted population. Under
  ;; `React.StrictMode` React runs every body twice and commits once, so
  ;; the second run of each pair is a render whose work is thrown away —
  ;; and `hm/bodies-run` counts both. The population is therefore the
  ;; DIFFERENCE, it is non-empty, and StrictMode on or off is the control
  ;; that moves it.
  (if-not (browser?)
    (skip! "StrictMode's double-invoke is React's, and needs React")
    (async done
      ;; The two arms are taken ONE MOUNT AT A TIME. `bodies-run` is a
      ;; page-wide reading, so a standing peer would be inside the number
      ;; and the comparison would stop being about StrictMode.
      ;;
      ;; The measured keystroke is the one that CROSSES THE THRESHOLD —
      ;; the page opens holding `"c"`, which is too short to search, and
      ;; `"cc"` is not — so it runs three bodies: `screen`, `field`, and
      ;; the `panel` that has just appeared. Chosen because it is the
      ;; keystroke on which a demand mechanism would have work to do, and
      ;; because three is a number with a reason rather than whatever a
      ;; steady-state keystroke happened to cost.
      (let [keystroke (fn [m] (hm/bodies-run #(type-into! m "cc")))
            plain-m   (mount-screen! (seeded {:term "c" :open? true}))
            plain     (keystroke plain-m)]
        (-> (hm/assert-clean! (hm/unmount! plain-m))
            (.then
              (fn [_]
                (let [strict-m (mount-strict! (seeded {:term "c" :open? true}))
                      strict   (keystroke strict-m)]
                  (is (pos? plain)
                      "the keystroke ran a body at all — a zero here would
                       make every comparison below vacuous")
                  (is (= (* 2 plain) strict)
                      "StrictMode runs each body twice and commits once")
                  (is (pos? (- strict plain))
                      "so the abandoned-render population on this witness
                       is non-empty and counted: `strict - plain` renders
                       ran and were discarded")
                  (hm/assert-clean! (hm/unmount! strict-m)))))
            (.then done))))))

(deftest an-abandoned-render-asks-the-service-for-nothing
  ;; The other half of C3, and the half that decides whether the fence is
  ;; gateable: with every body running twice, the number of requests is
  ;; unchanged. Today that is true by construction rather than by care —
  ;; acquisition happens in a handler and a handler is not a render — and
  ;; recording it now is what lets the implementation bead inherit a
  ;; blocking test with a population it can force.
  (if-not (browser?)
    (skip! "needs a real render to abandon")
    (async done
      (let [m (mount-strict!)]
        (type-into! m "ca")
        (-> (settled m #(node m ".suggestion") "the debounced search replies")
            (.then (fn [_]
                     (is (= 1 (count (searches)))
                         "one request, although every body on the page ran
                          twice and half of those renders were discarded")
                     (is (= 3 (count (nodes m ".suggestion")))
                         "and the page is the same page")
                     (-> (hm/unmount! m) (hm/assert-clean!))))
            (.then done))))))

;; ---------------------------------------------------------------------------
;; C2 — demand outlives the read that wanted it, on the shipped path
;; ---------------------------------------------------------------------------

(deftest a-request-outlives-the-read-when-the-page-unmounts
  ;; NO MUTATION. The three intents that end a suggestion read each carry
  ;; their release; unmount is the fourth way a read ends and the
  ;; application cannot carry that one, because the public door gives
  ;; application code no unmount signal. So the model goes on believing a
  ;; read wants a term, the debounce tick fires into a page that is gone,
  ;; the request goes out, and its answer lands.
  ;;
  ;; The RELEASED arm is armed FIRST and read LAST: an absence asserted
  ;; before the positive arm's round trip has even had time to run would
  ;; be asserting that nothing has happened yet.
  (if-not (browser?)
    (skip! "a read only ends by unmount if there was a mount")
    (async done
      (let [released (mount-screen!)]
        (type-into! released "ca")
        (hm/dispatch-and-settle! released [::events/dismiss])
        (is (nil? (db/wanted (app-db released)))
            "the dismissal ended the read, and the model knows it")
        (hm/unmount! released)

        (let [orphaned (mount-screen!)]
          (type-into! orphaned "ca")
          (hm/unmount! orphaned)
          (is (nil? (node orphaned ".typeahead-panel"))
              "the page is gone")
          (-> (test-support/poll-until
                #(some? (get-in (app-db orphaned) [:search :shown]))
                {:label "the reply for the unmounted page lands"})
              (.then
                (fn [_]
                  (is (= 1 (count (searches)))
                      "a request was ISSUED after the page unmounted, and
                       answered — the whole round trip was paid for a read
                       that no longer exists")
                  (is (nil? (get-in (app-db released) [:search :shown]))
                      "while the released arm, armed first and read last,
                       asked for nothing at all: its intent carried the
                       release the unmount could not")
                  (-> (hm/assert-clean! orphaned)
                      (.then (fn [_] (hm/assert-clean! released))))))
              (.then done)))))))

;; ---------------------------------------------------------------------------
;; C5 — the retained per-read and per-boundary structures of the status quo
;; ---------------------------------------------------------------------------

(deftest the-retained-structures-move-with-the-panel
  ;; C5 asks the report to state the exact retained per-read and
  ;; per-boundary structures of the status quo, so that a proposed
  ;; design's delta against them can be checked against the recogniser.
  ;; They are `hm/census`'s five counters, and this row reads them at
  ;; three named moments with the panel as the control.
  (if-not (browser?)
    (skip! "a retained-structure reading is only worth what built the page")
    (async done
      (let [m       (mount-screen! (seeded {:term "ca" :open? false}))
            closed  (hm/census)
            _       (hm/dispatch-and-settle! m [::events/focus])
            open    (hm/census)
            _       (hm/dispatch-and-settle! m [::events/dismiss])
            again   (hm/census)]
        (is (some? (node m ".typeahead-field")) "the page is up")

        ;; The closed page is two boundaries and five reads, and every one
        ;; of them can be named: `screen` reads `::wanted`, `::open?` and
        ;; `::chosen`; `field` reads `::term` and `::revision`. So the
        ;; baseline is `{:cells 5 :cell-refs 5 :boundaries 2 :edges 5
        ;; :entries 2}`, and the deltas below are exact rather than
        ;; directional.
        (testing "opening the panel adds one boundary and its three reads"
          (is (= 1 (- (:boundaries open) (:boundaries closed)))
              "the panel is a boundary and it was not there before")
          (is (= 3 (- (:cell-refs open) (:cell-refs closed)))
              "its body reads [::suggestions term], ::status and
               ::held-rows — three memberships on the runtime's cells")
          (is (= 3 (- (:edges open) (:edges closed)))
              "and each read is an edge")
          (is (= 1 (- (:entries open) (:entries closed)))
              "with one cached read-set entry, which is per BOUNDARY"))

        (testing "closing it takes the read-shaped structures back exactly"
          ;; THE FACT C5 TURNS ON. Memberships, boundaries and edges are a
          ;; function of what is COMMITTED: the panel stops rendering and
          ;; they are gone, to the unit, with nothing to maintain and
          ;; nothing that could drift.
          (is (= (:boundaries closed) (:boundaries again)))
          (is (= (:cell-refs closed) (:cell-refs again)))
          (is (= (:edges closed) (:edges again))))

        (testing "while the resource-shaped structures wait for the reaper"
          ;; And this is the other half, which matters just as much: cells
          ;; and cached entries are keyed by the QUERY, not by the read,
          ;; so they outlive the last reader by design and are released at
          ;; the runtime's own quiescence horizon. A demand mechanism
          ;; keyed by resource would be joining these, not the ones above.
          (is (= 3 (- (:cells again) (:cells closed)))
              "the three cells the panel's reads minted are still there")
          (is (= 1 (- (:entries again) (:entries closed)))
              "and so is its read-set entry"))
        ;; THE CONTROL on the paragraph above: `assert-clean!` waits for
        ;; quiescence before it reads, and it reports clean — so the three
        ;; cells and the entry are released, just later than the
        ;; memberships were. A row that stopped at `again` would have
        ;; published a leak that is not one.
        (-> (hm/unmount! m)
            (hm/assert-clean!)
            (.then done))))))
