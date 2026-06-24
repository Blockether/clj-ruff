# clj-ruff

Clojure binding to [ruff](https://github.com/astral-sh/ruff) for **Python code
formatting only**, through the JDK Foreign Function & Memory API (FFM).

ruff ships a CLI, not a C-ABI library — so clj-ruff binds a tiny first-party
cdylib (`native/ruff-c`, a thin `extern "C"` wrapper over ruff's
`ruff_python_formatter` crate) and calls it **in-process** via a downcall. No
subprocess, no CLI, no `pyproject.toml` / `ruff.toml` discovery: output depends
only on the source and the line length you pass. This mirrors how
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

(ruff/version)                            ; => "0.1.0 (ruff 0.15.19)"
(ruff/available?)                         ; => true
```

`format` throws `ex-info` on syntactically invalid Python; `format-or` returns
the input unchanged instead — the convenient display-side default.

### Options

| key            | meaning                                            |
|----------------|----------------------------------------------------|
| `:line-length` | wrap width (`0` / omitted → ruff default **88**)   |

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

The bundled ruff release is pinned in `native/ruff-c/Cargo.toml`. clj-ruff's own
(Clojars) version in `resources/VERSION` is independent of it.

## License

MIT.
