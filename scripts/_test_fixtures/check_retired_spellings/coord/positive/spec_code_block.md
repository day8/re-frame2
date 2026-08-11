# POSITIVE fixture (d/string, Markdown surface)

A fenced example in the spec carries the same weight as a test assertion: a
reader copies it. The string shape must fire inside a fence as it does in
source, and the symbol pattern must NOT also fire on it — a coordinate opening
a string literal is denied token start by the `"`, so this file yields exactly
one finding, not two.

```clojure
(is (str/starts-with? (str (:where refusal)) "front.codec/"))
```
