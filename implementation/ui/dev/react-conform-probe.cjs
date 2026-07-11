/* [S1-CONFIRM] probe — react behaviour evidence for the DOM conversion
 * table (jvm-tree-and-conversion-contract.md). The rows embedded in
 * re-frame.ui.rules (standard-names, unitless-css, boolean-attrs,
 * void-tags, booleanish/overloaded sets) are version-pinned to the
 * probed react-dom release; re-run this on every React upgrade and diff
 * the output (the parity corpus, S1f, automates drift detection).
 *
 * Run from implementation/ (needs node_modules):
 *   node ui/dev/react-conform-probe.cjs out.json
 */
const React = require("react");
const { renderToStaticMarkup, renderToString } = require("react-dom/server");
const h = React.createElement;

const out = { reactVersion: React.version, sections: {} };
const S = (name, obj) => { out.sections[name] = obj; };

const tryRender = (el) => {
  try { return { html: renderToStaticMarkup(el) }; }
  catch (e) { return { threw: String(e && e.message || e).slice(0, 200) }; }
};

// ---- B. boolean attribute candidates ---------------------------------------
const boolCandidates = [
  ["div", "allowFullScreen"], ["script", "async"], ["input", "autoFocus"],
  ["video", "autoPlay"], ["input", "checked"], ["video", "controls"],
  ["track", "default"], ["script", "defer"], ["input", "disabled"],
  ["input", "formNoValidate"], ["div", "hidden"], ["div", "inert"],
  ["img", "isMap"], ["div", "itemScope"], ["video", "loop"],
  ["select", "multiple"], ["video", "muted"], ["script", "noModule"],
  ["form", "noValidate"], ["details", "open"], ["video", "playsInline"],
  ["input", "readOnly"], ["input", "required"], ["ol", "reversed"],
  ["option", "selected"],
];
{
  const res = {};
  for (const [tag, prop] of boolCandidates) {
    const t = tryRender(h(tag, { [prop]: true }));
    const f = tryRender(h(tag, { [prop]: false }));
    res[`${tag}.${prop}`] = { true: t.html ?? t.threw, false: f.html ?? f.threw };
  }
  S("booleans", res);
}

// ---- C. booleanish strings --------------------------------------------------
S("booleanish", {
  contentEditableTrue: tryRender(h("div", { contentEditable: true })),
  contentEditableFalse: tryRender(h("div", { contentEditable: false })),
  draggableTrue: tryRender(h("div", { draggable: true })),
  draggableFalse: tryRender(h("div", { draggable: false })),
  spellCheckTrue: tryRender(h("input", { spellCheck: true })),
  spellCheckFalse: tryRender(h("input", { spellCheck: false })),
});

// ---- D. overloaded booleans -------------------------------------------------
S("overloaded", {
  downloadTrue: tryRender(h("a", { download: true })),
  downloadFalse: tryRender(h("a", { download: false })),
  downloadStr: tryRender(h("a", { download: "file.txt" })),
  captureTrue: tryRender(h("input", { capture: true })),
  captureFalse: tryRender(h("input", { capture: false })),
  captureStr: tryRender(h("input", { capture: "user" })),
});

// ---- E. property-only -------------------------------------------------------
S("propertyOnly", {
  mutedTrue: tryRender(h("video", { muted: true })),
  mutedFalse: tryRender(h("video", { muted: false })),
});

// ---- F. form-control specials ----------------------------------------------
S("formControls", {
  inputValue: tryRender(h("input", { value: "x", readOnly: true })),
  textareaValue: tryRender(h("textarea", { value: "line", readOnly: true })),
  selectValue: tryRender(h("select", { value: "b", readOnly: true },
    h("option", { value: "a" }, "A"), h("option", { value: "b" }, "B"))),
  defaultValue: tryRender(h("input", { defaultValue: "dv" })),
  defaultChecked: tryRender(h("input", { type: "checkbox", defaultChecked: true })),
  textareaDefaultValue: tryRender(h("textarea", { defaultValue: "dv" })),
});

// ---- G. numerics ------------------------------------------------------------
S("numerics", {
  tabIndexIntegralDouble: tryRender(h("div", { tabIndex: 1.0 })),
  attrDouble: tryRender(h("div", { "data-x": 2.5 })),
  childIntegralDouble: tryRender(h("div", null, 3.0)),
  childDouble: tryRender(h("div", null, 2.5)),
  bigNum: tryRender(h("div", null, 1e21)),
  styleMix: tryRender(h("div", {
    style: { padding: 16, opacity: 0.5, zIndex: 3, "--custom": 4, width: 0, lineHeight: 1.5 },
  })),
});

// ---- H. void children -------------------------------------------------------
{
  const voids = ["area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "source", "track", "wbr", "param", "keygen", "menuitem"];
  const res = {};
  for (const t of voids) {
    res[t] = tryRender(h(t, null, "child"));
    res[t + "$plain"] = tryRender(h(t, null));
  }
  S("voidChildren", res);
}

// ---- I. hidden until-found --------------------------------------------------
S("hiddenUntilFound", {
  untilFound: tryRender(h("div", { hidden: "until-found" })),
  hiddenStrTrue: tryRender(h("div", { hidden: "true" })),
});

// ---- J. class/for aliases ---------------------------------------------------
S("aliases", {
  htmlFor: tryRender(h("label", { htmlFor: "x" })),
  className: tryRender(h("div", { className: "a b" })),
  charSet: tryRender(h("meta", { charSet: "utf-8" })),
});

// ---- K/L. svg + xlink + namespace-ish ---------------------------------------
S("svg", {
  viewBox: tryRender(h("svg", { viewBox: "0 0 10 10" })),
  strokeWidth: tryRender(h("svg", null, h("path", { strokeWidth: 2, d: "M0 0" }))),
  xlinkHref: tryRender(h("svg", null, h("use", { xlinkHref: "#i" }))),
  xmlLang: tryRender(h("svg", { xmlLang: "en" })),
  foreignObject: tryRender(h("svg", null, h("foreignObject", null, h("div", { tabIndex: 1 })))),
  clipPathTag: tryRender(h("svg", null, h("clipPath", { id: "c" }))),
  mathAnnotationXml: tryRender(h("math", null, h("annotation-xml", { encoding: "text/html" }, h("div", null, "t")))),
});

// ---- M. verbatim + unknown names ---------------------------------------------
S("names", {
  ariaHiddenFalse: tryRender(h("div", { "aria-hidden": false })),
  ariaHiddenTrue: tryRender(h("div", { "aria-hidden": true })),
  dataCamel: tryRender(h("div", { "data-fooBar": "x" })),
  unknownLower: tryRender(h("div", { foo: "bar" })),
  unknownCamel: tryRender(h("div", { fooBar: "x" })),
  unknownKebab: tryRender(h("div", { "foo-bar": "x" })),
  tabIndexKebabIgnored: tryRender(h("div", { tabIndex: 5 })),
});

// ---- N. duplicate keys (string coercion) -------------------------------------
{
  let warnings = [];
  const orig = console.error;
  console.error = (...a) => warnings.push(a.map(String).join(" ").slice(0, 160));
  renderToString(h("ul", null, [h("li", { key: 1 }, "a"), h("li", { key: "1" }, "b")]));
  console.error = orig;
  S("duplicateKeys", { warnings });
}

// ---- O. adjacent text separators ---------------------------------------------
S("textRuns", {
  renderToString: renderToString(h("div", null, "a", "b")),
  renderToStaticMarkup: renderToStaticMarkup(h("div", null, "a", "b")),
});

// ---- P. custom elements -------------------------------------------------------
S("customElements", {
  mixed: tryRender(h("my-el", {
    helpText: "x", "foo-bar": "y", disabled: true, muted: false,
    onMyEvent: () => {}, className: "c", style: { padding: 4 },
  })),
  objProp: tryRender(h("my-el", { data: { a: 1 } })),
});

// ---- Q/R. source-extracted sets ------------------------------------------------
const fs = require("fs");
const path = require("path");
function readReactDomSources() {
  const dir = path.join(require.resolve("react-dom/package.json"), "..", "cjs");
  return fs.readdirSync(dir)
    .filter((f) => f.endsWith(".development.js"))
    .map((f) => fs.readFileSync(path.join(dir, f), "utf8"))
    .join("\n");
}
{
  const src = readReactDomSources();
  // isUnitlessNumber: React 19 uses a Set('...') or object literal; capture both.
  let unitless = null;
  let m = src.match(/unitlessNumbers\s*=\s*new Set\(\[([\s\S]*?)\]\)/);
  if (m) {
    unitless = m[1].match(/["']([^"']+)["']/g).map((s) => s.slice(1, -1));
  }
  S("unitlessSet", { count: unitless ? unitless.length : 0, values: unitless });

  // possibleStandardNames (dev-only attribute-name warning table)
  let psn = null;
  m = src.match(/possibleStandardNames\s*=\s*\{([\s\S]*?)\n\s*\};/);
  if (m) {
    const body = m[1];
    psn = {};
    for (const mm of body.matchAll(/["']?([\w:-]+)["']?\s*:\s*["']([^"']+)["']/g)) {
      psn[mm[1]] = mm[2];
    }
  }
  S("possibleStandardNames", { count: psn ? Object.keys(psn).length : 0 });
  // svg-relevant aliases: entries whose standard name is camelCase or has colon
  if (psn) {
    const interesting = {};
    for (const [k, v] of Object.entries(psn)) {
      if (/[A-Z]/.test(v) || v.includes(":")) interesting[k] = v;
    }
    S("aliasInteresting", interesting);
  }
  // omitted close tags (void set React uses)
  m = src.match(/omittedCloseTags\s*=\s*\{([\s\S]*?)\};/);
  S("omittedCloseTags", m ? m[1].match(/(\w+):/g).map((s) => s.slice(0, -1)) : null);
}

fs.writeFileSync(process.argv[2] || "react-probe-out.json", JSON.stringify(out, null, 1));
console.log("wrote", process.argv[2] || "react-probe-out.json");
