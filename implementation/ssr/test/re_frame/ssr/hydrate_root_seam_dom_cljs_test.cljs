(ns re-frame.ssr.hydrate-root-seam-dom-cljs-test
  "Real-DOM proof of the `:ssr/discover-root-manifest` late-bind seam that
  connects `re-frame.ui/hydrate-root` to the optional SSR artefact (rf2-3omxp).

  ## Why this test lives in the ssr tree

  The seam is cross-artefact by necessity. `ui/hydrate-root` must resolve a
  container's Root Manifest, but the discovery code lives in
  `re-frame.ssr.manifest` — a different Maven jar. A direct `ui -> ssr` require
  is forbidden by the Independence rule AND would fail to compile every non-SSR
  ui app, so the connection is the late-bind hook the manifest namespace
  publishes at ns-load and `hydrate-root*` resolves through the core registry
  (`ui -> core late-bind <- ssr`, the shape `ui/route-link` already uses against
  routing).

  A test proving that seam has to have BOTH artefacts loaded, and the `ssr`
  test tree is where the repository already puts cross-artefact SSR DOM proofs
  (`re-frame.ssr.ssr-startup-recipe-dom-cljs-test` requires the Reagent
  adapter the same way).

  ## What this proves

    1. `hydrate-root-adopts-server-dom-with-server-state-and-prefix` — THE
       vertical. Plant server markup + an adjacent Root Manifest + an
       `__rf_payload`, then run the PUBLIC two-call boot in its documented
       order: `re-frame.ssr/hydrate!` (state) followed by
       `re-frame.ui/hydrate-root` (DOM adoption). Then assert three observable
       outcomes:

         (a) the FIRST compiled render saw SERVER STATE — the retained server
             `<span>` carries the greeting derived from the payload's app-db
             (`\"seam-server\"`), which exists nowhere in the client's initial
             db. A boot that mounted before `ssr/hydrate!` installed the
             payload would render the client default and turn this red;
         (b) the first compiled render ADOPTED that DOM — the exact `<span>`
             node object captured before the boot is still `identical?` and
             still `isConnected` afterwards. A `createRoot` path would mint a
             new node;
         (c) the root took the SERVER-AUTHORED identifier prefix — the
             `(react/use-id)` value the compiled view renders contains the
             manifest's `:identifier-prefix` (`\"srv7-\"`). The prefix is
             deliberately a string the client could not have synthesised: the
             ui default is derived from the root-id, so a `srv7-` substring can
             only have come from the manifest's content.

    2. `hydrate-root-without-adjacent-manifest-fails-loud` — the artefact IS
       present, discovery finds nothing adjacent: `:rf.error/root-manifest-invalid`
       with ex-data `{:missing :manifest}`.

    3. `hydrate-root-without-ssr-artefact-fails-loud` — the hook is UNBOUND
       (the state a ui-only app is in): `:rf.error/ssr-artefact-missing`,
       carrying the copy-pasteable `day8/re-frame2-ssr` Maven coordinate and
       the `re-frame.ssr` require-ns.

  Every assertion reads an OBSERVABLE OUTCOME — a DOM node's identity, its
  text, the id string actually rendered, the thrown `:rf.error/id` plus its
  ex-data. None of them merely assert that an exception occurred.

  Browser-only — hydration is a real-DOM operation and the discovery hook
  reaches `.nextElementSibling`. The `-dom-cljs-test` suffix opts this file
  into the `:browser-test` build; the `:node-test` build loads it too (it
  matches `cljs-test$`) and every body gates on `(browser?)`, so a node run is
  an honest skip rather than a vacuous pass."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.constants :as constants]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as ui-client]
            [re-frame.ui.react :as react]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---------------------------------------------------------------------------
;; The app under the boot — ids unique to this ns so the shared bundle cannot
;; collide at image assembly.
;; ---------------------------------------------------------------------------

(def ^:private app-frame ::frame)

(rf/reg-sub ::greeting
  (fn [db _] (or (:greeting db) "client-default")))

;; The compiled root. `(react/use-id)` is a supported ui hook and its output is
;; derived from the ROOT's `identifierPrefix` — which, for a hydrating root, is
;; whatever the manifest said the server used. Rendering it into the DOM is
;; what makes the server-authored prefix an observable outcome rather than an
;; invisible React option.
(defview seam-root []
  (let [id (react/use-id)]
    [:div.seam
     [:span.greeting (ui/sub [::greeting])]
     [:i.uid id]]))

;; ---------------------------------------------------------------------------
;; Server-side artefacts, planted by hand (this test proves the CLIENT half of
;; the seam; the server emitter is item 2's surface and already shipped).
;; ---------------------------------------------------------------------------

(def ^:private server-root-id ::seam-root)

;; A prefix the client could NEVER synthesise: ui's default prefix is derived
;; from the root-id, so `srv7-` appearing in the rendered `use-id` output can
;; only have come from the manifest's content.
(def ^:private server-prefix "srv7-")

(def ^:private server-manifest
  {:rf.root/schema-version 1
   :root-id                server-root-id
   :identifier-prefix      server-prefix
   :element-locator        {:id "seam-container"}})

(defn- plant-host!
  "Append the shape a real server render emits: the root's container, its Root
  Manifest as the IMMEDIATELY FOLLOWING element sibling (that adjacency is the
  whole discovery rule), and the page-wide `__rf_payload`. Returns the wrapper
  host so the test can remove it.

  `:manifest?` false plants the container with NO adjacent manifest — the
  `{:missing :manifest}` arm."
  [{:keys [manifest? server-html]}]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host)
          (str "<div id=\"seam-container\">" (or server-html "") "</div>"
               (when manifest?
                 (str "<script type=\"application/edn\" "
                      constants/root-manifest-marker-attribute ">"
                      (pr-str server-manifest)
                      "</script>"))
               "<script id=\"" constants/payload-script-id
               "\" type=\"application/edn\">"
               (pr-str {:rf/version 1
                        :rf/app-db  {:greeting "seam-server"}})
               "</script>"))
    (.appendChild (.-body js/document) host)
    host))

(defn- container [host] (.querySelector host "#seam-container"))

(defn- teardown! [host root-atom]
  (when-let [root @root-atom]
    (try (ui/unmount! root) (catch :default _ nil)))
  (reset! root-atom nil)
  (when-let [p (.-parentNode host)] (.removeChild p host)))

;; The shared `:browser-test` page leaves `IS_REACT_ACT_ENVIRONMENT` true from
;; sibling hook suites. Hydration commits on React's own schedule here, so turn
;; it off for the duration and restore it on teardown (the same treatment
;; `ssr-startup-recipe-dom-cljs-test` applies).
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(defn- restore-act-env! [prev]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter ui/adapter :async? true :ambient-frame nil}))

(defn- thrown-error
  "Run `f`, returning `{:id :data :msg}` for the ex-info it throws, or nil when
  it does not throw. Never asserts merely that something was raised — the
  caller pins the canonical `:rf.error/id` and the ex-data."
  [f]
  (try (f) nil
       (catch :default e
         {:id   (:rf.error/id (ex-data e))
          :data (ex-data e)
          :msg  (ex-message e)})))

;; ---------------------------------------------------------------------------
;; 1. The vertical
;; ---------------------------------------------------------------------------

(deftest hydrate-root-adopts-server-dom-with-server-state-and-prefix
  (when (browser?)
    (async done
      (let [act-prev (disable-act-env!)
            ;; The markup the server render emitted for `seam-root` against the
            ;; SERVER's app-db. The `use-id` slot is left empty: its value is a
            ;; React-internal token no hand-authored fixture can predict, and
            ;; the point under test is which PREFIX the client root was created
            ;; with, which is read off the rendered output below.
            host     (plant-host!
                      {:manifest?   true
                       :server-html (str "<div class=\"seam\">"
                                         "<span class=\"greeting\">seam-server</span>"
                                         "<i class=\"uid\"></i>"
                                         "</div>")})
            root-atom (atom nil)]
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        ;; THE PUBLIC TWO-CALL BOOT, in its documented order.
        ;; (1) state: read `__rf_payload`, install it, dispatch `:rf/hydrate`.
        (ssr/hydrate! {:frame app-frame})
        ;; The captured server node — adoption is proven by OBJECT IDENTITY, so
        ;; it must be read before any React work touches the container.
        (let [server-span (.querySelector host "span.greeting")]
          ;; (2) DOM adoption: identity comes from the manifest, via the hook.
          (reset! root-atom
                  (ui/hydrate-root (container host)
                                   [ui/frame-provider {:frame app-frame}
                                    [seam-root]]))
          (js/setTimeout
           (fn []
             (try
               (testing "the first compiled render saw SERVER state"
                 (is (= "seam-server" (.-textContent (.querySelector host "span.greeting")))
                     (str "the greeting rendered from the payload's app-db — "
                          "'client-default' here would mean the compiled render "
                          "ran before ssr/hydrate! installed the server state"))
                 (is (not= "client-default"
                           (.-textContent (.querySelector host "span.greeting")))
                     "and it is definitively not the client default"))

               (testing "the first compiled render ADOPTED the server DOM"
                 (is (identical? server-span (.querySelector host "span.greeting"))
                     (str "the exact server-emitted node object is still the "
                          "mounted one — a createRoot path would have minted a "
                          "fresh node"))
                 (is (true? (.-isConnected server-span))
                     "and the retained node is still in the document"))

               (testing "the root took the SERVER-AUTHORED identifier prefix"
                 (let [uid (.-textContent (.querySelector host "i.uid"))]
                   (is (seq uid)
                       "the compiled view rendered a use-id value")
                   (is (str/includes? uid server-prefix)
                       (str "the rendered use-id " (pr-str uid) " carries the "
                            "manifest's :identifier-prefix " (pr-str server-prefix)
                            " — a client-synthesised default is derived from the "
                            "root-id and could not contain it"))))

               (testing "the root is registered under the MANIFEST's root-id"
                 (is (= server-root-id (ui-client/root-id-of @root-atom))
                     "identity came from the manifest content, not from opts"))
               (finally
                 (teardown! host root-atom)
                 (restore-act-env! act-prev)
                 (done))))
           0))))))

;; ---------------------------------------------------------------------------
;; 2. Artefact present, no adjacent manifest
;; ---------------------------------------------------------------------------

(deftest hydrate-root-without-adjacent-manifest-fails-loud
  (when (browser?)
    (let [act-prev (disable-act-env!)
          host     (plant-host! {:manifest? false})
          root-atom (atom nil)]
      (try
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        (let [{:keys [id data msg]}
              (thrown-error #(ui/hydrate-root (container host)
                                              [ui/frame-provider {:frame app-frame}
                                               [seam-root]]))]
          (is (= :rf.error/root-manifest-invalid id)
              "the canonical discriminator — a hydrating root with no manifest")
          (is (= :manifest (:missing data))
              "ex-data names WHICH part is missing, per Spec 011 §Discovery")
          (is (= 're-frame.ui/hydrate-root (:where data))
              "and the error is stamped at the hydrate site")
          (is (error/message-has-id-token? msg)
              "the message carries the greppable [:rf.error/...] token"))
        (finally
          (teardown! host root-atom)
          (restore-act-env! act-prev))))))

;; ---------------------------------------------------------------------------
;; 3. The SSR artefact is absent entirely
;; ---------------------------------------------------------------------------

(deftest hydrate-root-without-ssr-artefact-fails-loud
  (when (browser?)
    (let [act-prev (disable-act-env!)
          host     (plant-host! {:manifest? true})
          root-atom (atom nil)
          ;; The ssr artefact IS on this page's classpath (this ns requires
          ;; it), so the absent state is re-established by flipping the hook to
          ;; nil for the duration — the established mechanism from
          ;; `re-frame.ssr.late-bind-missing-test`. Restored in `finally`.
          original (late-bind/get-fn :ssr/discover-root-manifest)]
      (try
        (rf/init! ui/adapter)
        (rf/make-frame {:id app-frame :platform :client})
        (late-bind/set-fn! :ssr/discover-root-manifest nil)
        (let [{:keys [id data msg]}
              (thrown-error #(ui/hydrate-root (container host)
                                              [ui/frame-provider {:frame app-frame}
                                               [seam-root]]))]
          (is (= :rf.error/ssr-artefact-missing id)
              "a ui-only app calling hydrate-root is told the artefact is absent")
          (is (= 're-frame.ui/hydrate-root (:where data))
              "stamped at the hydrate site, not somewhere inside ssr")
          (is (= :no-recovery (:recovery data))
              "there is no in-process recovery — the app must add a dependency")
          (is (str/includes? msg "day8/re-frame2-ssr")
              (str "the message carries the COPY-PASTEABLE Maven coordinate — "
                   "saw " (pr-str msg)))
          (is (str/includes? msg "re-frame.ssr")
              "and the namespace to require at app boot")
          (is (error/message-has-id-token? msg)
              "plus the greppable [:rf.error/ssr-artefact-missing] token"))
        (finally
          (late-bind/set-fn! :ssr/discover-root-manifest original)
          (teardown! host root-atom)
          (restore-act-env! act-prev))))))
