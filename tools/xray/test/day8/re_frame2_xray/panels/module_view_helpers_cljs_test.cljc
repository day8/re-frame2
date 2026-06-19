(ns day8.re-frame2-xray.panels.module-view-helpers-cljs-test
  "JVM + CLJS coverage for the Module-view pure-data helpers (rf2-wtg9z4 +
  rf2-at0oen per-module provenance — the EP-0013 disposition-6 demand-trigger
  surface).

  Verifies the per-module provenance projection (rf2-at0oen): the module-row
  shape (`:owns` / `:requires` / registration kinds+count / source) read off the
  default realm's installed app value, the no-modules (load-order / sugar-only)
  case, and the seam-fed `project-module-view` with `:provenance-available?`
  true. (The (realm, frame) address-space grouping was removed under rf2-70owfr —
  the afdlyr collapse leaves a single default realm, so there is no realm
  grouping to test.)"
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.module-view-helpers :as h]))

;; A representative module value (the shape the internal app-value substrate
;; produces — see re-frame.app-value/module): `:rf.module/id` · `:rf.module/owns` ·
;; `:rf.module/requires` · `:registrations` (kind→id→owner-stamped descriptor) ·
;; optional `:source`. The FACT keys are owner-qualified (`:rf.module/*`,
;; EP-0007 / EP-0017 v5 — rf2-yk6u2x); the structural slots stay bare.
(def ^:private cart-module
  {:rf.module/id       :shop/cart
   :rf.module/owns     {:app-db [[:cart]] :routes [:shop/checkout]}
   :rf.module/requires #{:rf.capability/http}
   :registrations {:event {:cart/add   {:kind :event :id :cart/add   :owner :shop/cart}}
                   :sub   {:cart/items {:kind :sub   :id :cart/items :owner :shop/cart}}}
   :source        {:ns "shop.cart" :file "cart.cljs" :line 12 :column 1}})

;; A constructed app value seating that module (the shape the app-value
;; substrate / a seated `re-frame.realm/installed-app` returns): `:modules`
;; keyed by module id, `:rf.app/requires` the union capability set.
(def ^:private cart-app
  {:rf.app/id       :shop/app
   :modules         {:shop/cart cart-module}
   :registrations   (:registrations cart-module)
   :rf.app/requires #{:rf.capability/http}})

;; A projected (load-order / sugar-only) app value: registrations present, but
;; NO :modules and empty :rf.app/requires (re-frame.realm/installed-app returns
;; this for a realm seated through reg-* sugar with no install!).
(def ^:private projected-app
  {:rf.app/id       :rf.realm/default
   :registrations   {:event {:sugar/inc {:kind :event :id :sugar/inc}}}
   :rf.app/requires #{}})

;; A CONSTRUCTED app composed from ZERO modules (rf2-e0mq7a). The core
;; app-value contract (app_value.cljc:123-127) is explicit: an app constructed
;; from zero modules carries an EMPTY `:modules {}` map — distinct from a
;; projected/load-order app, which carries no `:modules` slot at all. Core
;; tests pin a zero-module constructed app → `:modules {}` + `:requires #{}`
;; (app_value_compose_cljs_test.cljc:141-147), so this fixture mirrors that
;; shape.
(def ^:private zero-module-app
  {:rf.app/id       :empty/app
   :modules         {}
   :rf.app/requires #{}})

;; ---- project-module-row -------------------------------------------------

(deftest project-module-row-shape
  (testing "a module value projects to the module-row: id, owns, requires,
            sorted registration kinds + total descriptor count, source"
    (let [row (h/project-module-row cart-module)]
      (is (= :shop/cart (:module-id row)))
      (is (= {:app-db [[:cart]] :routes [:shop/checkout]} (:owns row)))
      (is (= #{:rf.capability/http} (:requires row)))
      (is (= [:event :sub] (:registration-kinds row)) "kinds sorted by str")
      (is (= 2 (:registration-count row)) "one event + one sub")
      (is (= {:ns "shop.cart" :file "cart.cljs" :line 12 :column 1} (:source row))))))

(deftest project-module-row-defaults
  (testing "a bare module (no owns/requires/source) projects empty/nil slots"
    (let [row (h/project-module-row {:rf.module/id :m/empty :registrations {}})]
      (is (= :m/empty (:module-id row)))
      (is (= {} (:owns row)))
      (is (= #{} (:requires row)))
      (is (= [] (:registration-kinds row)))
      (is (= 0 (:registration-count row)))
      (is (nil? (:source row))))))

;; ---- project-app-modules ------------------------------------------------

(deftest project-app-modules-constructed
  (testing "a constructed app value projects its :modules to sorted module rows
            + the app's union requires"
    (let [{:keys [modules requires]} (h/project-app-modules cart-app)]
      (is (= [:shop/cart] (mapv :module-id modules)))
      (is (= #{:rf.capability/http} requires)))))

(deftest project-app-modules-sorted-by-id
  (testing "module rows sort by module-id str (stable render)"
    (let [m-b {:rf.module/id :b/mod :registrations {}}
          m-a {:rf.module/id :a/mod :registrations {}}
          app {:rf.app/id :x :modules {:b/mod m-b :a/mod m-a} :rf.app/requires #{}}
          {:keys [modules]} (h/project-app-modules app)]
      (is (= [:a/mod :b/mod] (mapv :module-id modules))))))

(deftest project-app-modules-projected-is-module-less
  (testing "a projected (load-order / sugar-only) app carries no :modules — the
            honest no-provenance case; :modules is nil (not [])"
    (let [{:keys [modules requires]} (h/project-app-modules projected-app)]
      (is (nil? modules) "module-less app → nil modules (distinct from [])")
      (is (= #{} requires))))
  (testing "nil app (no seam value) is likewise module-less"
    (is (nil? (:modules (h/project-app-modules nil))))))

(deftest project-app-modules-zero-module-constructed-app
  ;; rf2-e0mq7a — the adversarial case: an app CONSTRUCTED from zero modules
  ;; carries an explicit empty `:modules {}` map (the core contract). The
  ;; projection MUST preserve the present-but-empty distinction — an empty
  ;; VECTOR `[]`, NOT nil — so the panel never falsely renders the
  ;; load-order/no-provenance caption over a genuinely-installed app.
  (testing "a constructed app with `:modules {}` projects to `[]` (NOT nil) —
            present-but-empty provenance, distinct from no-provenance"
    (let [{:keys [modules requires]} (h/project-app-modules zero-module-app)]
      (is (= [] modules)
          "explicit empty `:modules {}` → empty VECTOR, not nil")
      (is (vector? modules)
          "the empty result is a vector (constructed) — `(vector? modules)`
           is the provenance signal `any-provenance?` reads")
      (is (not (nil? modules))
          "MUST NOT collapse to nil — that would erase the constructed app")
      (is (= #{} requires)))))

;; ---- project-module-view ------------------------------------------------

(deftest project-module-view-no-app-is-module-less
  (testing "the 0-arity (no installed app) projects module-less — the
            no-runtime / no-provenance call still works"
    (let [data (h/project-module-view)]
      (is (true? (:provenance-available? data))
          "the re-frame.realm/installed-app read seam has graduated (rf2-at0oen)")
      (is (nil? (:modules data)) "no installed app → no modules")
      (is (= #{} (:requires data))))))

(deftest project-module-view-fills-modules-from-seam
  (testing "project-module-view reads the default realm's installed app value
            and fills :modules + :requires"
    (let [data (h/project-module-view cart-app)]
      (is (true? (:provenance-available? data)))
      (is (= [:shop/cart] (mapv :module-id (:modules data))))
      (is (= #{:rf.capability/http} (:requires data))))))

(deftest project-module-view-projected-app-is-module-less
  (testing "a projected (load-order / sugar-only) app projects to nil :modules —
            the honest no-provenance case"
    (let [data (h/project-module-view projected-app)]
      (is (true? (:provenance-available? data)))
      (is (nil? (:modules data)) "load-order app → nil modules (no provenance)"))))

(deftest project-module-view-zero-module-app-is-present-but-empty
  (testing "a CONSTRUCTED zero-module app projects to `[]` (provenance present,
            no module rows) — distinct from the no-provenance nil"
    (let [data (h/project-module-view zero-module-app)]
      (is (= [] (:modules data)))
      (is (vector? (:modules data)) "present-but-empty provenance, not nil"))))

;; ---- empty-state decision: any-modules? / any-provenance? ---------------

(deftest any-modules?-detects-module-rows
  (testing "any-modules? is true when the projected :modules carries at least
            one MODULE ROW"
    (is (true? (h/any-modules? [{:module-id :m/a}])))
    (is (false? (h/any-modules? []))
        "a zero-module constructed app ([]) carries NO module rows → false")
    (is (false? (h/any-modules? nil))
        "no provenance (nil) → no module rows → false")))

(deftest any-provenance?-detects-constructed-app
  ;; rf2-e0mq7a — the predicate that separates the zero-module-app caption
  ;; (a constructed app with no modules) from the no-provenance caption (no
  ;; constructed app). `:modules` is a VECTOR exactly when the installed app was
  ;; CONSTRUCTED (`[]` for a zero-module app); it is nil for the load-order /
  ;; no-provenance case.
  (testing "true when the app is constructed — including a zero-module one (`[]`)"
    (is (true? (h/any-provenance? []))
        "an empty VECTOR is present provenance (a constructed zero-module app)")
    (is (true? (h/any-provenance? [{:module-id :m/a}]))
        "a non-empty module vector is provenance too"))
  (testing "false when no constructed app (:modules nil)"
    (is (false? (h/any-provenance? nil))
        "load-order / no-provenance → no provenance")))

(deftest empty-state-decision-three-way
  ;; rf2-e0mq7a — the decision the UI `modules-section-body` consumes. Pins the
  ;; three mutually-exclusive outcomes off the predicate pair so the panel's
  ;; caption choice is correct without a browser render.
  (testing "module rows present → render the module list (any-modules? true)"
    (is (true? (h/any-modules? [{:module-id :m/a}]))))
  (testing "constructed zero-module app → zero-module-app caption, NOT the
            load-order caption (any-modules? false, any-provenance? true)"
    (is (false? (h/any-modules? []))
        "no module rows → not the module list")
    (is (true? (h/any-provenance? []))
        "provenance present → the zero-module-app branch, not no-provenance"))
  (testing "no provenance → the load-order/no-provenance caption
            (any-modules? false AND any-provenance? false)"
    (is (false? (h/any-modules? nil)))
    (is (false? (h/any-provenance? nil)))))

(deftest no-modules-caption-explains-the-empty-state
  (testing "the no-modules caption names the load-order / sugar reason and the
            app-composition (compose-from-modules / install) remedy so the
            operator knows the section is the honest empty state, not broken.
            pl97nd.2 removed the rf/app · rf/module · rf/install! facade
            symbols, so the caption names the substrate ACTION, not the
            retired facade vars."
    (let [c h/no-modules-caption]
      (is (re-find #"(?i)load-order" c))
      (is (re-find #"(?i)compose an app from modules" c))
      (is (re-find #"(?i)install" c))
      (is (not (re-find #"rf/module" c))
          "the retired rf/module facade symbol must NOT appear (pl97nd.2)")
      (is (not (re-find #"rf/install!" c))
          "the retired rf/install! facade symbol must NOT appear (pl97nd.2)")))
  (testing "EP-0023: the caption teaches the image/frame PUBLIC model and
            frames the app-composition remedy as the retained-internal
            substrate (not the central public vocabulary)"
    (let [c h/no-modules-caption]
      (is (re-find #"(?i)image\s*(?:→|->)\s*frame" c)
          "names the image → frame public model")
      (is (re-find #"(?i)retained-internal|implementation structure|substrate" c)
          "frames app-composition as the retained-internal substrate"))))

(deftest zero-module-app-caption-distinct-from-no-provenance
  ;; rf2-e0mq7a — the caption an installed zero-module app renders. It MUST be
  ;; distinct from `no-modules-caption` (a different string) and MUST NOT claim
  ;; the process is on the load-order path (which is false for an installed
  ;; constructed app).
  (testing "the zero-module-app caption names the installed-but-empty state +
            the add-modules remedy (pl97nd.2 removed the rf/module facade
            symbol, so the caption names the action, not the retired var)"
    (let [c h/zero-module-app-caption]
      (is (re-find #"(?i)zero modules" c))
      (is (re-find #"(?i)constructed" c))
      (is (re-find #"(?i)add modules" c))
      (is (not (re-find #"rf/module" c))
          "the retired rf/module facade symbol must NOT appear (pl97nd.2)")
      (is (not (re-find #"(?i)load-order" c))
          "MUST NOT claim the load-order path — the app WAS constructed")))
  (testing "EP-0023: the caption teaches the image/frame PUBLIC model and
            frames the app-composition substrate as retained-internal"
    (let [c h/zero-module-app-caption]
      (is (re-find #"(?i)image\s*(?:→|->)\s*frame" c)
          "names the image → frame public model")
      (is (re-find #"(?i)retained-internal|implementation structure|substrate" c)
          "frames app/install! as the retained-internal substrate")))
  (testing "the two empty-state captions are different strings"
    (is (not= h/no-modules-caption h/zero-module-app-caption))))
