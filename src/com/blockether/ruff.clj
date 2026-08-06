(ns com.blockether.ruff
  "Clojure binding to ruff (https://github.com/astral-sh/ruff) for Python code
   FORMATTING and LINTING, through the JDK Foreign Function & Memory API.

   ruff does not publish a C-ABI library — only a CLI. So this binds a tiny
   first-party cdylib, `ruff-c` (native/ruff-c, a thin `extern \"C\"` wrapper over
   ruff's `ruff_python_formatter` + `ruff_linter` crates), exactly the way
   clj-fff binds `fff-c`. Both run IN-PROCESS via a downcall — no subprocess, no
   CLI. `format` takes Python source and returns it reformatted (long
   calls/collections wrapped multiline, black-compatible style); `lint` returns
   ruff's diagnostics as plain Clojure maps.

   Ruff CONFIGURATION FILES are first class: `config-file` finds the nearest
   `.ruff.toml` / `ruff.toml` / `pyproject.toml` with `[tool.ruff]` exactly the
   way the CLI does, and passing it as `:config` makes `format`/`lint` honour
   that file's real options (`select`/`ignore`, `line-length`, `target-version`,
   `preview`, `[format]` quote/indent/line-ending/docstring settings, `extend`
   chains). Discovery is never implicit: no `:config`, no file lookup.

   Run the JVM with `--enable-native-access=ALL-UNNAMED` so the foreign linker
   may load the library without a restricted-method warning.

   The library is resolved ONCE, lazily, the first time it's needed:
     1. RUFF_NATIVE_PATH env / `com.blockether.ruff.native.path` system property
        — an explicit path to the cdylib (used verbatim).
     2. A bundled classpath resource `prebuilds/<platform>/<lib>`, shipped by
        `com.blockether/ruff-native-<platform>` (extracted to the cache dir).
        This is the native-image path.
     3. A runtime download: the `ruff-native-<platform>` jar resolved through
        `clojure.tools.deps` (honouring Maven repos/mirrors/settings.xml),
        extracted + cached. Disable with RUFF_DISABLE_DOWNLOAD=1.

   `<platform>` ∈ { linux-x64 linux-arm64 darwin-arm64 darwin-x64 windows-x64 }."
  (:refer-clojure :exclude [format])
  (:require [clojure.string :as str]
            [com.blockether.nativeresolver :as nr]
            [com.blockether.nativeresolver.ffm :as ffm])
  (:import [java.lang.foreign Arena MemorySegment]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Native library + FFM binding (com.blockether/nativeresolver)
;; ---------------------------------------------------------------------------

(def ^:private native-spec
  "How libruff_c is found. Everything derives mechanically from `:lib`:
   RUFF_NATIVE_PATH / `com.blockether.ruff.native.path` (verbatim), the bundled
   `prebuilds/<platform>/<lib>` resource shipped by
   `com.blockether/ruff-native-<platform>`, then the tools.deps download cached
   under ~/.cache/clj-ruff (RUFF_DISABLE_DOWNLOAD=1 to disable). The version
   comes from the NAMESPACED `ruff/VERSION` resource."
  (nr/spec {:lib "ruff"}))

(defonce ^:private handles
  (delay
    (nr/bind! native-spec
              {:format      [:ptr "ruff_format" :ptr :u32]
               :format-cfg  [:ptr "ruff_format_with_config" :ptr :ptr :ptr :u32]
               :lint        [:ptr "ruff_lint" :ptr :u32 :ptr :ptr :u32]
               :lint-cfg    [:ptr "ruff_lint_with_config" :ptr :ptr :ptr :u32 :ptr :ptr :u32]
               :fix-cfg     [:ptr "ruff_fix_with_config" :ptr :ptr :ptr :u32 :ptr :ptr :u32 :u32]
               :find-config [:ptr "ruff_find_config" :ptr]
               :last-error  [:ptr "ruff_last_error"]
               :free        [nil  "ruff_free_string" :ptr]
               :version     [:ptr "ruff_version"]})))

(defn- invoke [k & args] (apply nr/invoke @handles k args))
(defn- cstr [^MemorySegment p] (ffm/c-string p))

(defn- take-string!
  "Read an owned `char*` result and free it. nil when the call returned NULL."
  [^MemorySegment p]
  (ffm/take-string! #(invoke :free %) p))

(defn- last-error
  "The message the cdylib recorded for the failing call on THIS thread, if any.
   Config-aware entry points always set one; the legacy ones may not."
  []
  (take-string! (invoke :last-error)))

;; ---------------------------------------------------------------------------
;; Public API — configuration discovery
;; ---------------------------------------------------------------------------

(defn config-file
  "Nearest ruff configuration file governing `path` (a file or a directory),
   searched exactly the way the ruff CLI searches: `.ruff.toml`, then
   `ruff.toml`, then a `pyproject.toml` that actually has a `[tool.ruff]`
   table — in `path`'s own directory, then every ancestor. Returns the absolute
   path as a String, or nil when the tree has no ruff configuration at all.

   That nil is the signal to tell a user \"this project has no ruff config\";
   `format`/`lint` fall back to ruff's built-in defaults when it happens."
  ^String [path]
  (when (some? path)
    (with-open [arena (Arena/ofConfined)]
      (when-let [s (take-string! (invoke :find-config (.allocateFrom ^Arena arena (str path))))]
        ;; Rust canonicalisation hands back Windows extended-length paths
        ;; (`\\?\C:\…`); callers want the plain path they passed in.
        (cond (str/starts-with? s "\\\\?\\UNC\\") (str "\\\\" (subs s 8))
              (str/starts-with? s "\\\\?\\") (subs s 4)
              :else s)))))

;; ---------------------------------------------------------------------------
;; Public API — formatting
;; ---------------------------------------------------------------------------

(defn format
  "Format Python `code` and return the reformatted source as a String.

   Options:
     :config       path — a ruff configuration file (`ruff.toml`, `.ruff.toml`
                          or a `pyproject.toml` with `[tool.ruff]`). Its
                          `[format]` section is honoured in full: `quote-style`,
                          `indent-style`, `indent-width`, `line-ending`,
                          `skip-magic-trailing-comma`, `docstring-code-format`,
                          `docstring-code-line-length`, `line-length`,
                          `target-version`, `preview` — plus `extend` chains.
                          Use `config-file` to discover it.
     :path         path — the file the source came from. Selects the source type
                          (`.pyi` stubs format differently) and resolves
                          path-specific `target-version` overrides.
     :line-length  int  — explicit wrap width; OVERRIDES the configuration
                          (0 / omitted => the config's, else ruff's 88).

   Runs entirely in-process (no subprocess). With no `:config` the result
   depends only on `code`, `:path` and `:line-length` — ruff's defaults, no
   implicit discovery. Throws ex-info when ruff can't format the input
   (syntactically invalid Python, or an unreadable/invalid config); callers that
   want a verbatim fallback should use `format-or` or catch."
  (^String [code] (format code nil))
  (^String [^String code {:keys [line-length config path]}]
   (when (nil? code) (throw (ex-info "ruff/format: code is nil" {})))
   (with-open [arena (Arena/ofConfined)]
     (let [cs  (fn [s] (if s (.allocateFrom ^Arena arena (str s)) MemorySegment/NULL))
           ret ^MemorySegment (if (or config path)
                                (invoke :format-cfg (cs code) (cs config) (cs path)
                                        (int (or line-length 0)))
                                (invoke :format (cs code) (int (or line-length 0))))]
       (or (take-string! ret)
           (let [err (last-error)]
             (throw (ex-info (str "ruff: format failed" (when err (str " — " err)))
                             {:line-length line-length :config (some-> config str)
                              :path (some-> path str) :error err}))))))))

(defn format-or
  "Like `format`, but returns `code` unchanged if ruff is unavailable or fails
   (the convenient display-side default — never lose the original source)."
  (^String [code] (format-or code nil))
  (^String [code opts]
   (try (format code opts)
        (catch Throwable _ code))))
(declare selector-spec)

(defn fix
  "Apply Ruff lint fixes to Python `code` and return the resulting source.

   Options match `lint` (`:config`, `:path`, `:line-length`, `:select`, `:ignore`,
   `:preview`) plus `:unsafe-fixes`, which opts into Ruff's unsafe fixes."
  (^String [code] (fix code nil))
  (^String [^String code {:keys [line-length config path select ignore preview unsafe-fixes]}]
   (when (nil? code) (throw (ex-info "ruff/fix: code is nil" {})))
   (with-open [arena (Arena/ofConfined)]
     (let [cs  (fn [s] (if s (.allocateFrom ^Arena arena (str s)) MemorySegment/NULL))
           ret ^MemorySegment (invoke :fix-cfg
                                      (cs code)
                                      (cs config)
                                      (cs path)
                                      (int (or line-length 0))
                                      (cs (selector-spec select))
                                      (cs (selector-spec ignore))
                                      (int (if preview 1 0))
                                      (int (if unsafe-fixes 1 0)))]
       (or (take-string! ret)
           (let [err (last-error)]
             (throw (ex-info (str "ruff: fix failed" (when err (str " — " err)))
                             {:select select :ignore ignore :line-length line-length
                              :config (some-> config str) :path (some-> path str)
                              :unsafe-fixes unsafe-fixes :error err}))))))))

;; ---------------------------------------------------------------------------
;; Public API — linting
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------

(defn- selector-spec
  "Normalise a rule-selector option to the comma-separated string the cdylib
   takes: nil, a String (\"F,E501\"), a keyword/symbol, or a collection of those."
  ^String [sel]
  (cond
    (nil? sel)        nil
    (string? sel)     sel
    (coll? sel)       (let [s (str/join "," (map selector-spec sel))]
                        (when-not (str/blank? s) s))
    :else             (name sel)))

(defn- unescape [^String s]
  (if (neg? (.indexOf s (int \\)))
    s
    (let [sb (StringBuilder.)
          n  (.length s)]
      (loop [i 0]
        (if (>= i n)
          (.toString sb)
          (let [c (.charAt s i)]
            (if (and (= \\ c) (< (inc i) n))
              (do (.append sb (case (.charAt s (inc i))
                                \t \tab
                                \n \newline
                                \r \return
                                \\ \\
                                (.charAt s (inc i))))
                  (recur (+ i 2)))
              (do (.append sb c) (recur (inc i)))))))
      (.toString sb))))

(defn- parse-diagnostic [^String line]
  (let [f (str/split line #"\t" 7)]
    (when (= 7 (count f))
      {:code        (nth f 0)
       :row         (parse-long (nth f 1))
       :col         (parse-long (nth f 2))
       :end-row     (parse-long (nth f 3))
       :end-col     (parse-long (nth f 4))
       :is-fixable  (= "1" (nth f 5))
       :message     (unescape (nth f 6))})))

(defn lint
  "Lint Python `code` with ruff and return a vector of diagnostic maps, in ruff's
   own order:

     {:code \"F401\" :message \"`os` imported but unused\"
      :row 1 :col 1 :end-row 1 :end-col 10 :is-fixable true}

   Rows and columns are 1-based; `:end-row`/`:end-col` are exclusive; `:code` is
   the noqa code (`F401`) when the rule has one, else the rule id.

   Options:
     :config       path       — a ruff configuration file (`ruff.toml`,
                                `.ruff.toml`, or a `pyproject.toml` carrying
                                `[tool.ruff]`). Its `[lint]` section is honoured
                                in full: `select`, `extend-select`, `ignore`,
                                `fixable`, `dummy-variable-rgx`, per-rule
                                sections (`[lint.pydocstyle]`, `[lint.isort]`,
                                …), `line-length`, `target-version`, `preview`
                                and `extend` chains. Use `config-file` to
                                discover it; without one you get ruff's built-in
                                default selection (E4, E7, E9, F).
     :path         path       — the file the source came from; reported in
                                diagnostics and used for `.pyi` source typing.
     :line-length  int        — width for line-length rules; OVERRIDES config.
     :select       selectors  — REPLACE the selected rule set (\"F,E501\", :F401,
                                [\"F\" \"B\"]…); OVERRIDES config.
     :ignore       selectors  — disable these on top of the selection.
     :preview      boolean    — also run preview rules; OVERRIDES config.

   Runs entirely in-process. Inline `# noqa` comments in the source are always
   honoured, and `per-file-ignores` from the config apply when you pass `:path`
   (the config's relative globs are matched against the config file's own
   directory, exactly like the CLI). Not supported: `exclude` patterns — one
   source, one call, so apply those at the call site. Throws ex-info when ruff
   cannot lint the input (unknown selector,
   unreadable/invalid config, or unparsable source); use `lint-or` for the
   never-throw variant."
  ([code] (lint code nil))
  ([^String code {:keys [line-length select ignore preview config path]}]
   (when (nil? code) (throw (ex-info "ruff/lint: code is nil" {})))
   (with-open [arena (Arena/ofConfined)]
     (let [cs  (fn [s] (if s (.allocateFrom ^Arena arena (str s)) MemorySegment/NULL))
           ret ^MemorySegment (if (or config path)
                                (invoke :lint-cfg
                                        (cs code)
                                        (cs config)
                                        (cs path)
                                        (int (or line-length 0))
                                        (cs (selector-spec select))
                                        (cs (selector-spec ignore))
                                        (int (if preview 1 0)))
                                (invoke :lint
                                        (cs code)
                                        (int (or line-length 0))
                                        (cs (selector-spec select))
                                        (cs (selector-spec ignore))
                                        (int (if preview 1 0))))]
       (if-let [s (take-string! ret)]
         (into []
               (keep parse-diagnostic)
               (str/split-lines (str/trim-newline s)))
         (let [err (last-error)]
           (throw (ex-info (str "ruff: lint failed" (when err (str " — " err)))
                           {:select select :ignore ignore :line-length line-length
                            :config (some-> config str) :path (some-> path str)
                            :error err}))))))))

(defn lint-or
  "Like `lint`, but returns `default` (nil unless given) if ruff is unavailable
   or fails — the display-side variant that never throws."
  ([code] (lint-or code nil nil))
  ([code opts] (lint-or code opts nil))
  ([code opts default]
   (try (lint code opts)
        (catch Throwable _ default))))

;; ---------------------------------------------------------------------------
;; Public API — misc
;; ---------------------------------------------------------------------------

(defn version
  "The bundled ruff release string (`clj-ruff-cdylib (ruff X.Y.Z)`)."
  ^String []
  (cstr (invoke :version)))

(defn available?
  "True if the ruff native library can be resolved + linked on this platform."
  []
  (try (boolean @handles) (catch Throwable _ false)))
