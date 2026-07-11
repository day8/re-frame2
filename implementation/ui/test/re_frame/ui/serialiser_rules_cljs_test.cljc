(ns re-frame.ui.serialiser-rules-cljs-test
  "Cross-host pins for the serialiser-half rules the parity corpus
  leans on (S1f). This .cljc suite runs under BOTH the node build and
  the JVM runner — the SAME assertions must hold on both hosts, which
  is exactly the cross-host contract:

    - `js-number-str` implements ECMA-262 `Number::toString(10)` on the
      JVM (plain layout in the (-6, 21] decimal-exponent window,
      e-notation outside; integral doubles without `.0` — [S1-CONFIRM]
      row 10 incl. the formerly-open exponent residual);
    - `dom-attr-name` is the final-attribute-NAME half of the table
      (probed against react-dom/server 19.2.0 — the parity corpus is
      the live drift detector)."
  (:require [clojure.test :refer [deftest is are]]
            [re-frame.ui.rules :as rules]))

(deftest js-number-str-ecma-tostring
  (are [n s] (= s (rules/js-number-str n))
    3        "3"
    -7       "-7"
    1.0      "1"
    -7.0     "-7"
    -0.0     "0"
    2.5      "2.5"
    0.5      "0.5"
    0        "0"
    1234.5678 "1234.5678"
    16.0     "16"
    ;; the plain-notation window below 1
    0.0001   "0.0001"
    1.23E-5  "0.0000123"
    0.000001 "0.000001"
    ;; e-notation thresholds (ECMA: n > 21 above, n <= -6 below)
    1.0E21   "1e+21"
    1.5E22   "1.5e+22"
    1.0E-7   "1e-7"
    1.5E-7   "1.5e-7"
    ;; large integral doubles inside the window stay plain
    1.2345E7 "12345000"
    ;; non-finite
    ##NaN    "NaN"
    ##Inf    "Infinity"
    ##-Inf   "-Infinity"))

(deftest dom-attr-name-serialiser-half
  (are [author dom] (= dom (rules/dom-attr-name author))
    ;; probed HTML specials
    "class"      "class"
    "for"        "for"
    "tab-index"  "tabindex"
    ;; unaliased React props serialise verbatim (HTML names are ASCII
    ;; case-insensitive; react-dom 19.2.0 emits readOnly verbatim)
    "read-only"  "readOnly"
    "max-length" "maxLength"
    ;; hyphenated HTML originals keep their hyphens
    "http-equiv"     "http-equiv"
    "accept-charset" "accept-charset"
    ;; SVG: camel aliases survive camel, SVG's own hyphens survive hyphenated
    "view-box"     "viewBox"
    "stroke-width" "stroke-width"
    "clip-path"    "clip-path"
    "text-anchor"  "text-anchor"
    "fill-opacity" "fill-opacity"
    ;; xlink:/xml: colon families
    "xlink-href" "xlink:href"
    "xml-lang"   "xml:lang"
    ;; data-*/aria-* verbatim, casing preserved
    "data-fooBar"  "data-fooBar"
    "aria-hidden"  "aria-hidden"
    ;; unrecognized names pass through verbatim (React 16+ behaviour)
    "foo-bar" "foo-bar"))
