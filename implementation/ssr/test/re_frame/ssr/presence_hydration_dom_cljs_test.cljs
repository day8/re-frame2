(ns re-frame.ssr.presence-hydration-dom-cljs-test
  "Real-DOM proof of PRESENCE × HYDRATION adoption (rf2-uqe1b — the unshipped
  rf2-d9yq3 / #6261 S5 audit rider on epic rf2-vxgfnd.97; Spec 004 §Presence,
  Spec 011 §Hydration).

  ## The gap this pins

  `PresenceComponent` seeds its committed state at `entries=[]`, so absent any
  hydration signal EVERY first client render classifies its children `:mounting`.
  On the JVM the structural render yields `:present`, so server markup carries the
  `:present` phase. Under SSR-then-hydrate the client therefore re-rendered
  server-adopted presence children `:mounting -> :present`, fabricating an enter
  transition on page load for content the user already sees (and a className
  hydration mismatch). The fix: presence consults the ROOT-SCOPED HYDRATION FACT
  the hydrate-root path already installs (`phase-context`, `:server` during the
  adoption pass — the same fact `ui/client-only` reads) on its FIRST render, and
  seeds server-adopted keys `:present`.

  ## Why this test lives in the ssr tree

  A real phase context (the root hydration fact) exists ONLY on a HYDRATING root,
  which `re-frame.ssr` + `re-frame.ui.client/hydrate-root` together produce. A
  test that drives real adoption therefore needs both artefacts loaded — what the
  `ssr` test tree provides (the same reason `phase-flip-hydration-dom-cljs-test`
  lives here).

  ## What this proves — the rider's fixture

    1. HYDRATED PRESENCE STARTS `:present` — a server-adopted presence child
       renders `:present` on its first (adoption) render and NEVER `:mounting`:
       no enter-animation replay, no mismatch.
    2. A CLIENT-MOUNTED SIBLING still observes `:mounting -> :present` — the fix
       is ROOT-SCOPED to the hydrating root; an ordinary `ui/mount` on the same
       page is unaffected and still runs its enter transition.
    3. FAILED-SIBLING ISOLATION — a good hydrated presence root adopts `:present`
       even when a sibling root fails to boot (no adjacent manifest).

  Every assertion reads an OBSERVABLE OUTCOME — the exact ordered sequence of
  phases each child rendered with (a render spy keyed by message), per root.

  Browser-only — adoption is a real-DOM hydration operation. The `-dom-cljs-test`
  suffix opts this file into the `:browser-test` build; the `:node-test` build
  loads it too (it matches `cljs-test$`) and every body gates on `(browser?)`, so
  a node run is an honest skip rather than a vacuous pass."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.ssr.constants :as constants]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---------------------------------------------------------------------------
;; The render spy — per-message ordered phase log. A presence child records the
;; phase it renders with; the test reads the sequence to prove adoption
;; (`[:present]`, never `:mounting`) vs a client enter (`[:mounting … :present]`).
;; Messages are unique per root, so one shared log distinguishes roots.
;; ---------------------------------------------------------------------------

(def ^:private render-log (atom {}))

(defn- spy-phase!
  "Record and return the current presence phase for child `msg`. Called once per
  render of `toast`, so it is a stable hook site (`ui/presence-phase` →
  `useContext`)."
  [msg]
  (let [p (ui/presence-phase)]
    (swap! render-log update msg (fnil conj []) p)
    p))

(defn- phases-of [msg] (get @render-log msg []))

;; ---------------------------------------------------------------------------
;; The app under the boot — a presence deck of statically-keyed children (no
;; sub, so server markup is deterministic and the children need no frame state).
;; ---------------------------------------------------------------------------

(defview toast [{:keys [msg]}]
  [:li {:data-testid "toast" :data-msg msg
        :data-phase (name (spy-phase! msg))}
   msg])

(defview toast-deck [{:keys [items]}]
  [:ul.deck
   (ui/presence {:timeout-ms 300}
     (for [m items]
       [toast {:key m :msg m}]))])

;; ---------------------------------------------------------------------------
;; Server-side artefacts, planted by hand. A server render of `toast-deck` emits
;; each keyed child at phase `:present` (the JVM structural `:present`), the
;; presence boundary + phase Providers being transparent in HTML. A byte-match
;; keeps hydration a clean adoption.
;; ---------------------------------------------------------------------------

(defn- server-body [msgs]
  (str "<ul class=\"deck\">"
       (apply str
              (for [m msgs]
                (str "<li data-testid=\"toast\" data-msg=\"" m
                     "\" data-phase=\"present\">" m "</li>")))
       "</ul>"))

(defn- manifest-for [root-id container-id]
  {:rf.root/schema-version 1
   :root-id                root-id
   :identifier-prefix      (str (name root-id) "-")
   :element-locator        {:id container-id}})

(defn- manifest-script [root-id container-id]
  (str "<script type=\"application/edn\" "
       constants/root-manifest-marker-attribute ">"
       (pr-str (manifest-for root-id container-id))
       "</script>"))

(defn- container [host container-id]
  (.querySelector host (str "#" container-id)))

(defn- teardown! [host & roots]
  (doseq [ra roots]
    (when-let [root @ra]
      (try (ui/unmount! root) (catch :default _ nil)))
    (reset! ra nil))
  (when-let [p (.-parentNode host)] (.removeChild p host)))

;; Hydration commits on React's own schedule here, so turn the act environment
;; off for the duration (same treatment `phase-flip-hydration-dom-cljs-test`
;; applies). With act off, an ordinary `ui/mount` sibling commits without an
;; "not wrapped in act" warning too.
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(defn- restore-act-env! [prev]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter ui/adapter :async? true :ambient-frame nil}))

(defn- thrown-error [f]
  (try (f) nil
       (catch :default e
         {:id (:rf.error/id (ex-data e)) :data (ex-data e)})))

(defn- wait-for
  "Poll `pred` every 5ms up to ~1s, then call `k`. Bounded so a transition that
  never arrives lets the assertions in `k` fail honestly rather than hang."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 200))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

;; ---------------------------------------------------------------------------
;; 1. The vertical — a hydrated presence child adopts :present; a client-mounted
;;    sibling on the same page still enters :mounting -> :present (root-scoped).
;; ---------------------------------------------------------------------------

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(deftest hydrated-presence-adopts-present-client-sibling-still-enters
  (when (browser?)
    (async done
      (reset! render-log {})
      (let [act-prev  (disable-act-env!)
            ;; Root A: server markup for one presence child ("a") + its manifest,
            ;; hydrated. Root B: an EMPTY container, ordinary client mount ("b").
            host      (.createElement js/document "div")
            _         (set! (.-innerHTML host)
                            (str "<div id=\"a-root\">" (server-body ["a"]) "</div>"
                                 (manifest-script ::a-root "a-root")
                                 "<div id=\"b-root\"></div>"))
            _         (.appendChild (.-body js/document) host)
            root-a    (atom nil)
            root-b    (atom nil)]
        (rf/init! ui/adapter)
        (rf/make-frame {:id frame-a :platform :client})
        (rf/make-frame {:id frame-b :platform :client})
        ;; Root A HYDRATES — its first render is the :server-phase adoption pass.
        ;; hydrateRoot schedules its render on React's own (async) clock here, so
        ;; the render spy is read after `wait-for`, never synchronously.
        (reset! root-a
                (ui/hydrate-root (container host "a-root")
                                 [ui/frame-provider {:frame frame-a}
                                  [toast-deck {:items ["a"]}]]))
        ;; Root B is an ordinary CLIENT MOUNT on the same page — no phase
        ;; Provider above it, so it reads the :client default and must still run
        ;; its enter transition.
        (reset! root-b
                (ui/mount [ui/frame-provider {:frame frame-b}
                           [toast-deck {:items ["b"]}]]
                          (container host "b-root")
                          {:root-id ::b-root}))
        (wait-for
         #(and (some #{:present} (phases-of "a"))
               (some #{:present} (phases-of "b")))
         (fn []
           (try
             (testing "the server-adopted child adopts :present — no enter replay"
               ;; The red→green core: WITH the fix 'a' renders ONLY :present;
               ;; WITHOUT it the first render is :mounting (a fabricated enter
               ;; transition + className mismatch on hydrate).
               (is (not-any? #{:mounting} (phases-of "a"))
                   (str "adopted child 'a' NEVER re-entered :mounting — it began "
                        ":present, matching the server markup"))
               (is (some #{:present} (phases-of "a"))
                   "root A rendered its adopted child :present"))
             (testing "the client-mounted sibling still enters :mounting -> :present"
               (is (= :mounting (first (phases-of "b")))
                   "client child 'b' rendered :mounting FIRST — its enter runs")
               (is (some #{:present} (phases-of "b"))
                   "…then flipped to :present"))
             (testing "ROOT-SCOPED — the hydration adoption fact did not leak"
               (is (not-any? #{:mounting} (phases-of "a"))
                   "root A (hydrated) never re-entered :mounting")
               (is (= :mounting (first (phases-of "b")))
                   "root B (client) was untouched by A's :server phase"))
             (finally
               (teardown! host root-a root-b)
               (restore-act-env! act-prev)
               (done)))))))))

;; ---------------------------------------------------------------------------
;; 2. Failed-sibling isolation — a good hydrated presence root adopts :present
;;    even when a sibling fails to boot (no adjacent manifest; composes with the
;;    rf2-1b0po missing-manifest failure — no scenario multiplication).
;; ---------------------------------------------------------------------------

(def ^:private frame-c ::frame-c)
(def ^:private frame-d ::frame-d)

(deftest failed-sibling-does-not-break-hydrated-presence-adoption
  (when (browser?)
    (async done
      (reset! render-log {})
      (let [act-prev (disable-act-env!)
            ;; Root C: good (markup "c" + manifest). Root D: BROKEN (markup but
            ;; NO adjacent manifest), hydrated first and failing to boot.
            host     (.createElement js/document "div")
            _        (set! (.-innerHTML host)
                           (str "<div id=\"c-root\">" (server-body ["c"]) "</div>"
                                (manifest-script ::c-root "c-root")
                                "<div id=\"d-root\">" (server-body ["d"]) "</div>"))
            _        (.appendChild (.-body js/document) host)
            root-c   (atom nil)]
        (rf/init! ui/adapter)
        (rf/make-frame {:id frame-c :platform :client})
        (rf/make-frame {:id frame-d :platform :client})
        ;; Sibling D fails to boot BEFORE C is even mounted — a contained failure.
        (let [{:keys [id data]}
              (thrown-error
               #(ui/hydrate-root (container host "d-root")
                                 [ui/frame-provider {:frame frame-d}
                                  [toast-deck {:items ["d"]}]]))]
          (is (= :rf.error/root-manifest-invalid id)
              "sibling D failed to boot — a contained missing-manifest failure")
          (is (= :manifest (:missing data))
              "ex-data names which part is missing"))
        ;; Root C boots normally; its presence adoption must be unaffected. The
        ;; spy is read after `wait-for` (hydrateRoot renders on React's clock).
        (reset! root-c
                (ui/hydrate-root (container host "c-root")
                                 [ui/frame-provider {:frame frame-c}
                                  [toast-deck {:items ["c"]}]]))
        (wait-for
         #(some #{:present} (phases-of "c"))
         (fn []
           (try
             (testing "the good root's presence child adopts :present despite the failed sibling"
               (is (not-any? #{:mounting} (phases-of "c"))
                   (str "adopted child 'c' rendered ONLY :present — the sibling "
                        "failure never perturbed its root-scoped adoption"))
               (is (some #{:present} (phases-of "c"))
                   "root C rendered its adopted child :present")
               (is (not-any? #{:mounting} (phases-of "d"))
                   "the failed root never produced a live :mounting child either"))
             (finally
               (teardown! host root-c)
               (restore-act-env! act-prev)
               (done)))))))))
