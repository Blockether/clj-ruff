# clj-ruff

Clojure binding to [ruff](https://github.com/astral-sh/ruff) for **Python code
formatting and linting**, through the JDK Foreign Function & Memory API (FFM).

ruff ships a CLI, not a C-ABI library — so clj-ruff binds a tiny first-party
cdylib (`native/ruff-c`, a thin `extern "C"` wrapper over ruff's
`ruff_python_formatter` and `ruff_linter` crates) and calls it **in-process**
via a downcall. No subprocess, no CLI, and no *implicit* `pyproject.toml` /
`ruff.toml` discovery: output depends only on the source, the options you pass
and the config you ask for (`config-file`, below). This mirrors how
[`clj-fff`](https://github.com/Blockether/clj-fff) and
[`rift-clojure`](https://github.com/Blockether/rift-clojure) bind their native
libraries.

## Install

```clojure
;; deps.edn
com.blockether/ruff {:mvn/version "RELEASE"}
```

Run the JVM with `--enable-native-access=ALL-UNNAMED` so the foreign linker can
load the library without a restricted-method warning.

The native library is resolved lazily on first use:

1. `RUFF_NATIVE_PATH` (env) / `-Dcom.blockether.ruff.native.path` — explicit path
   to the cdylib.
2. A bundled classpath resource from `com.blockether/ruff-native-<platform>`
   (the native-image path).
3. A runtime download of `ruff-native-<platform>` via `clojure.tools.deps`
   (honours your Maven repos/mirrors/`settings.xml`). Disable with
   `RUFF_DISABLE_DOWNLOAD=1`.

`<platform>` ∈ `linux-x64 linux-arm64 darwin-arm64 darwin-x64 windows-x64`.

For a slim deploy, also add the one native artifact for your target, e.g.
`com.blockether/ruff-native-darwin-arm64 {:mvn/version "RELEASE"}`.

## Use

```clojure
(require '[com.blockether.ruff :as ruff])

(ruff/format "result=some_function(a_long_argument, another_long_argument, and_one_more, keyword=value)"
             {:line-length 60})
;; =>
;; result = some_function(
;;     a_long_argument,
;;     another_long_argument,
;;     and_one_more,
;;     keyword=value,
;; )

(ruff/format "x={'a':1,'b':2}")          ; => "x = {\"a\": 1, \"b\": 2}\n"   (ruff default width 88)

(ruff/format-or "def (((broken" {})       ; => "def (((broken"  (verbatim fallback; never throws)

(ruff/version)                            ; => "0.3.4 (ruff 0.16.0)"
(ruff/available?)                         ; => true
```

`format` throws `ex-info` on syntactically invalid Python; `format-or` returns
the input unchanged instead — the convenient display-side default.

### Format options

| key            | meaning                                                                 |
|----------------|------------------------------------------------------------------------|
| `:config`      | path to a `ruff.toml` / `.ruff.toml` / `pyproject.toml` to apply        |
| `:path`        | the file the source came from (`.pyi` formats as a stub; per-path opts) |
| `:line-length` | wrap width — **overrides** the config (`0` / omitted → the config's, else ruff's **88**) |

## Lint

```clojure
(ruff/lint "import os\nx = 1\n")
;; => [{:code "F401" :message "`os` imported but unused"
;;      :row 1 :col 8 :end-row 1 :end-col 10 :is-fixable true}]

(ruff/lint "if x == None: pass" {:select "E711"})
(ruff/lint code {:select ["F" "E7"] :ignore "F401" :line-length 120 :preview true})

(ruff/lint-or code {} [])   ; never throws; returns the default on failure
```

Rows/columns are **1-based**, `:end-row`/`:end-col` exclusive, `:code` is the
noqa code (`F401`) when the rule has one and otherwise the rule id. With no
`:select` you get exactly the rule set the `ruff` CLI applies with no config —
**`E4`, `E7`, `E9`, `F`** — and `:select` **replaces** it while `:ignore`
subtracts from whatever is selected. Inline `# noqa` comments in the source are
honoured. Syntax errors come back as ordinary diagnostics with code
`invalid-syntax` (they do **not** throw) — an unknown rule selector does throw,
and `lint-or` swallows it.

### Lint options

| key             | meaning                                                              |
|-----------------|----------------------------------------------------------------------|
| `:select`       | replace the default rule set — `"F,E501"`, `:F401`, `["F" "B"]`, `"ALL"` |
| `:ignore`       | disable these selectors on top of the selection                      |
| `:line-length`  | limit for `E501` / wrap-width rules — overrides the config (`0` / omitted → **88**) |
| `:preview`      | also run ruff's preview rules                                        |
| `:config`       | path to a ruff configuration file to apply                           |
| `:path`         | the file the source came from (source type + per-path overrides)     |

## Configuration files

Nothing is discovered implicitly: with no `:config` the result depends only on
the source and the explicit options, which is what makes an editor integration
reproducible. Ask for discovery when you want it:

```clojure
(ruff/config-file "src/pkg/mod.py")   ; => "/repo/ruff.toml", or nil when the
                                      ;    tree has none (nil is not an error)
(ruff/lint code {:config (ruff/config-file "src/pkg/mod.py")
                 :path   "src/pkg/mod.py"})
```

`config-file` walks ancestors exactly like the `ruff` CLI: `.ruff.toml`,
`ruff.toml`, then a `pyproject.toml` **that carries a `[tool.ruff]` table**.
A passed `:config` is resolved with ruff's own loader, so `extend`, `[lint]` /
`[format]` tables, `select`, `ignore`, `line-length`, `target-version`,
`per-file-ignores` and friends all behave as they do for the CLI. Resolved
settings are cached per (path, mtime), so walking a directory parses the config
once and an edited config is still picked up.

## GraalVM native-image

This jar ships `META-INF/native-image/com.blockether/ruff/` (FFM downcalls +
the `prebuilds/**` resource glob), auto-applied on the native-image classpath.
Bundle the matching `ruff-native-<platform>` jar (so the cdylib is embedded) or
set `RUFF_NATIVE_PATH` at run time. The binding uses `Arena.ofAuto` /
`Arena.ofConfined` (never `Arena.ofShared`), so it is safe inside an image that
also embeds GraalPy/Truffle.

## Build

```bash
# build the cdylib for this host and stage it
scripts/build-natives.sh darwin-arm64

clojure -X:test                 # run tests against the staged cdylib
clojure -T:build jar            # main jar
clojure -T:build native-jar :platform darwin-arm64   # per-platform native jar
```

The bundled **ruff** release is pinned in `native/ruff-c/Cargo.toml`'s
dependency tags and is independent of clj-ruff's own version. That version —
the Clojars coordinate, the release tag `vX.Y.Z`, the `ruff-c` **crate**
version `ruff_version` reports, and the namespaced `ruff/VERSION` resource the
native resolver reads — has ONE source, `resources/VERSION`; `-T:build jar`
and `native-jar` refuse to build when those disagree.

## License

MIT.
