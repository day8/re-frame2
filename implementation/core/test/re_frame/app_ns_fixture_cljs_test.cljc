(ns re-frame.app-ns-fixture-cljs-test
  "Contract for `make-reset-runtime-fixture`'s `:app-ns` option (Spec 008
  §Test-support): bundle co-load hygiene.

  A CLJS test bundle loads every namespace before any test runs, so two example
  apps registering the same per-app id leave two provenance rows and default-image
  assembly fails with `:rf.error/image-duplicate-id`. `:app-ns` names the suite's
  OWN app: the fixture hides those rows when it is built and reinstates them for
  each test. Captures are unioned per prefix and read at test time, because CLJS
  loads a namespace once, so a second suite for the same app captures nothing.

  App registrations are interleaved with fixture builds because that is the
  bundle's own order: an app is hidden the moment its suite's `use-fixtures`
  form loads, before any rival."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clojure.string]
            [re-frame.core                 :as rf]
            [re-frame.registrar            :as rf.registrar]
            [re-frame.source-store         :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

;; the plain default: every claim below drives a separately built fixture
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register through the real `register!` path with `provenance-ns` as the
  descriptor's `:ns`; `tag` shows which app owns the resolver slot."
  ([kind id provenance-ns] (reg! kind id provenance-ns provenance-ns))
  ([kind id provenance-ns tag]
   (rf.registrar/register! kind id
                           {:ns         (symbol provenance-ns)
                            :path       "/"
                            :app-tag    tag
                            :handler-fn (fn [& _] tag)})
   nil))

(defn- reg-slot
  "The live registrar metadata for `(kind, id)`, or nil."
  [kind id]
  (get-in @rf.registrar/kind->id->metadata [kind id]))

(defn- slot-tag
  "Which app's registration currently owns the resolver slot for `(kind, id)`."
  [kind id]
  (:app-tag (reg-slot kind id)))

(defn- src-row
  "The source-store descriptor for the exact `(kind, id, provenance-ns)` slot."
  [kind id provenance-ns]
  (get-in @rf.source-store/kind->id->ns->descriptor [kind id provenance-ns]))

(defn- src-rows-under
  "Every `[kind id provenance-ns]` source slot whose provenance namespace starts
  with `prefix`."
  [prefix]
  (vec (for [[kind id->ns] @rf.source-store/kind->id->ns->descriptor
             [id ns->d]    id->ns
             [pns _]       ns->d
             :when (and (string? pns) (clojure.string/starts-with? pns prefix))]
         [kind id pns])))

(defn- err-id
  "The `:rf.error/id` `thunk` failed with, or nil when it did not fail."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- app-fixture
  "A fixture that hides the app under `prefix`. `:ambient-frame nil` because
  every body below makes its own top-level frame."
  [prefix]
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     :ambient-frame nil
     :app-ns        prefix}))

(defn- drive!
  "Run `body` inside `fixture` (the fn-form — this ns has no async rows)."
  [fixture body]
  (fixture body))

;; Two synthetic apps that share id vocabulary the way the RealWorld twins do:
;; the reserved per-app not-found route, plus one event id both implement.
(def ^:private shared-route :rf.route/not-found)
(def ^:private shared-event :kuky27.shared/load)

(defn- register-app!
  "Bring an app live: its two shared-vocabulary rows plus one id of its own."
  [prefix tag]
  (reg! :route shared-route (str prefix "routing") tag)
  (reg! :event shared-event (str prefix "settings") tag)
  (reg! :sub   (keyword (str "kuky27." tag) "own") (str prefix "subs") tag))

(deftest second-suite-for-one-app-still-sees-the-whole-app
  (register-app! "kuky27a." "a")
  (let [first-suite  (app-fixture "kuky27a.")
        hidden-mid   (src-rows-under "kuky27a.")
        second-suite (app-fixture "kuky27a.")]
    (is (= [] hidden-mid) "the second build finds nothing left to capture")
    (doseq [[label fixture] [["first-built suite" first-suite]
                             ["second-built suite" second-suite]]]
      (drive! fixture
        (fn []
          (is (= "a" (slot-tag :route shared-route)) label)
          (is (= "a" (slot-tag :event shared-event)) label)
          (is (some? (src-row :sub :kuky27.a/own "kuky27a.subs")) label)
          (is (nil? (err-id #(rf/make-frame {}))) label))))))

(deftest a-late-loading-part-of-an-app-reaches-a-fixture-built-before-it
  (register-app! "kuky27b." "b")
  (let [early (app-fixture "kuky27b.")]
    (reg! :sub :kuky27.b/late "kuky27b.late" "b")
    (let [late (app-fixture "kuky27b.")]
      (is (= [] (src-rows-under "kuky27b.")))
      (doseq [[label fixture] [["built before the late part loaded" early]
                               ["built after it" late]]]
        (drive! fixture
          (fn []
            (is (some? (reg-slot :sub :kuky27.b/own)) label)
            (is (some? (reg-slot :sub :kuky27.b/late)) label)
            (is (nil? (err-id #(rf/make-frame {}))) label)))))))

(deftest rival-apps-really-do-collide-without-the-option
  ;; positive control: the clash the option prevents is real
  (register-app! "kuky27x." "x")
  (is (nil? (err-id #(rf/make-frame {}))))
  (register-app! "kuky27y." "y")
  (is (= :rf.error/image-duplicate-id (err-id #(rf/make-frame {})))))

(deftest two-rival-apps-each-hiding-itself-both-assemble
  (register-app! "kuky27c." "c")
  (let [c-suite (app-fixture "kuky27c.")
        _       (register-app! "kuky27d." "d")
        d-suite (app-fixture "kuky27d.")]
    (doseq [[fixture own rival] [[c-suite "c" "kuky27d.routing"]
                                 [d-suite "d" "kuky27c.routing"]]]
      (drive! fixture
        (fn []
          (is (= own (slot-tag :route shared-route)))
          (is (= own (slot-tag :event shared-event)))
          (is (nil? (src-row :route shared-route rival)) "the rival's row is hidden")
          (is (nil? (err-id #(rf/make-frame {})))))))))

(deftest a-throwing-test-still-leaves-no-app-row-behind
  (register-app! "kuky27h." "h")
  (let [fixture (app-fixture "kuky27h.")
        seen    (atom nil)
        thrown  (try
                  (drive! fixture
                    (fn []
                      (reset! seen (slot-tag :route shared-route))
                      (throw (ex-info "boom" {:rf.error/id :kuky27/deliberate}))))
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core/ExceptionInfo) e
                    (:rf.error/id (ex-data e))))]
    (is (= :kuky27/deliberate thrown))
    (is (= "h" @seen) "the app was live inside the body")
    (is (= [] (src-rows-under "kuky27h.")) "the fixture's finally removed the rows")
    (is (nil? (err-id #(rf/make-frame {}))))))

(deftest sibling-live-registration-is-not-clobbered
  ;; the registrar slot is the LAST writer's (here app j's); hiding app i
  ;; forgets only i's source row
  (register-app! "kuky27i." "i")
  (register-app! "kuky27j." "j")
  (app-fixture "kuky27i.")
  (is (= "j" (slot-tag :route shared-route)))
  (is (nil? (src-row :route shared-route "kuky27i.routing")))
  (is (some? (src-row :route shared-route "kuky27j.routing"))))

(deftest the-owning-apps-live-slot-is-removed
  (register-app! "kuky27k." "k")
  (app-fixture "kuky27k.")
  (is (nil? (reg-slot :route shared-route)))
  (is (= [] (src-rows-under "kuky27k."))))
