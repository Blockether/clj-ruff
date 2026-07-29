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
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File InputStream]
           [java.lang.foreign Arena AddressLayout FunctionDescriptor Linker Linker$Option
            MemoryLayout MemorySegment SymbolLookup ValueLayout ValueLayout$OfInt]
           [java.lang.invoke MethodHandle]
           [java.net URL]
           [java.nio.file CopyOption Files LinkOption Path StandardCopyOption]
           [java.util.jar JarFile]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Platform + library resolution (mirrors clj-fff / rift-clojure)
;; ---------------------------------------------------------------------------

(defn- platform []
  (let [os   (.. (System/getProperty "os.name") toLowerCase)
        arch (.. (System/getProperty "os.arch") toLowerCase)
        os*  (cond
               (or (.contains os "mac") (.contains os "darwin")) "darwin"
               (.contains os "linux") "linux"
               (.contains os "win")   "windows"
               :else (throw (ex-info (str "Unsupported OS for ruff: " os) {:os os})))
        arch* (cond
                (#{"aarch64" "arm64"} arch) "arm64"
                (#{"x86_64" "amd64"} arch)  "x64"
                :else (throw (ex-info (str "Unsupported arch for ruff: " arch) {:arch arch})))]
    [os* arch*]))

(defn- lib-file-name [os]
  (case os
    "darwin"  "libruff_c.dylib"
    "linux"   "libruff_c.so"
    "windows" "ruff_c.dll"))

(defn- native-artifact [platform] (str "ruff-native-" platform))

(defn- configured-native-path ^Path []
  (when-let [p (or (System/getenv "RUFF_NATIVE_PATH")
                   (System/getProperty "com.blockether.ruff.native.path"))]
    (.toPath (io/file p))))

(defn- bundled-library-path ^Path [res fname]
  (when-let [^URL url (io/resource res)]
    (if (= "file" (.getProtocol url))
      (.toPath (io/file url))
      (let [dot (.lastIndexOf ^String fname ".")
            tmp (doto (File/createTempFile "libruff_c" (subs fname dot)) .deleteOnExit)]
        (with-open [in (io/input-stream url)]
          (io/copy in tmp))
        (.toPath tmp)))))

(defn- artifact-version []
  ;; NAMESPACED resource (ruff/VERSION), never the jar root — an unqualified
  ;; "VERSION" collides with every other lib that ships one (fff, rift, svar…),
  ;; whichever is first on the classpath wins, so a lib could resolve a FOREIGN
  ;; version and 404 a nonexistent <lib>-native-<that>.
  (str/trim (slurp (io/resource "ruff/VERSION"))))

(defn- cache-root ^Path []
  (if-let [p (or (System/getenv "RUFF_CACHE_DIR")
                 (System/getProperty "com.blockether.ruff.cache-dir"))]
    (.toPath (io/file p))
    (.toPath (io/file (System/getProperty "user.home") ".cache" "clj-ruff"))))

(defn- resolve-native-jar
  "Resolve the per-platform native jar through `clojure.tools.deps` — the same
   resolver the `clojure` CLI uses, so configured Maven repositories, mirrors and
   `~/.m2/settings.xml` are honoured. tools.deps is loaded via `requiring-resolve`
   so it's only touched on this runtime download path (never pulled into a native
   image)."
  ^Path [version platform]
  (let [lib          (symbol "com.blockether" (native-artifact platform))
        create-basis (or (requiring-resolve 'clojure.tools.deps/create-basis)
                         (throw (ex-info "org.clojure/tools.deps is not on the classpath; cannot resolve the ruff native artifact. Add com.blockether/<artifact>, set RUFF_NATIVE_PATH, or add tools.deps."
                                  {:lib lib})))
        basis        (create-basis {:project nil :extra {:deps {lib {:mvn/version version}}}})
        path         (-> basis :libs (get lib) :paths first)]
    (when-not path
      (throw (ex-info (str "Could not resolve " lib " " version
                        " via Clojure's dependency resolver. Check your Maven repositories / mirrors.")
               {:lib lib :version version})))
    (.toPath (io/file path))))

(defn- extract-native! ^Path [^Path jar-path res ^Path dest]
  (Files/createDirectories (.getParent dest) (make-array java.nio.file.attribute.FileAttribute 0))
  (with-open [jar (JarFile. (.toFile jar-path))]
    (let [entry (.getEntry jar res)]
      (when-not entry
        (throw (ex-info (str "Native artifact is missing " res) {:jar (str jar-path) :resource res})))
      (with-open [^InputStream in (.getInputStream jar entry)]
        (let [^"[Ljava.nio.file.CopyOption;" opts (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])]
          (Files/copy in dest opts)))))
  dest)

(defn- downloaded-library-path ^Path [platform res fname]
  (when-not (#{"1" "true" "yes"} (some-> (System/getenv "RUFF_DISABLE_DOWNLOAD") str/lower-case))
    (let [version  (artifact-version)
          root     (cache-root)
          lib-path (.resolve root (str version "/" platform "/" fname))]
      (if (Files/exists lib-path (make-array LinkOption 0))
        lib-path
        (extract-native! (resolve-native-jar version platform) res lib-path)))))

(defn- library-path ^Path []
  (let [[os arch] (platform)
        platform  (str os "-" arch)
        fname     (lib-file-name os)
        res       (str "prebuilds/" platform "/" fname)]
    (or (configured-native-path)
        (bundled-library-path res fname)
        (downloaded-library-path platform res fname)
        (throw (ex-info (str "No ruff native library for " platform
                          ". Add com.blockether/" (native-artifact platform)
                          ", set RUFF_NATIVE_PATH, or enable runtime download.")
                 {:platform platform :resource res})))))

;; ---------------------------------------------------------------------------
;; FFM binding
;; ---------------------------------------------------------------------------

(def ^AddressLayout ^:private addr ValueLayout/ADDRESS)
(def ^ValueLayout$OfInt ^:private u32 ValueLayout/JAVA_INT)

(defn- fd [ret & args]
  (if ret
    (FunctionDescriptor/of ret (into-array MemoryLayout args))
    (FunctionDescriptor/ofVoid (into-array MemoryLayout args))))

(defn- bind! []
  (let [linker (Linker/nativeLinker)
        ;; ofAuto (GC-managed, process-lifetime), NOT ofShared: a SHARED arena is
        ;; incompatible with Truffle runtime compilation, so a native image that
        ;; also embeds GraalPy (e.g. vis) fails to build with "Arena.ofShared is
        ;; not supported with runtime compilations". ofAuto needs no flag and
        ;; keeps the lookup + downcall handles alive as long as they're reachable.
        arena  (Arena/ofAuto)
        lookup (SymbolLookup/libraryLookup (library-path) arena)
        opts   (make-array Linker$Option 0)
        sym    (fn [name] (.orElseThrow (.find lookup name)))
        down   (fn [name desc] (.downcallHandle linker (sym name) desc opts))]
    {:format      (down "ruff_format"             (fd addr addr u32))
     :format-cfg  (down "ruff_format_with_config" (fd addr addr addr addr u32))
     :lint        (down "ruff_lint"               (fd addr addr u32 addr addr u32))
     :lint-cfg    (down "ruff_lint_with_config"   (fd addr addr addr addr u32 addr addr u32))
     :find-config (down "ruff_find_config"        (fd addr addr))
     :last-error  (down "ruff_last_error"         (fd addr))
     :free        (down "ruff_free_string"        (fd nil addr))
     :version     (down "ruff_version"            (fd addr))}))

(defonce ^:private handles (delay (bind!)))
(defn- h [k] (get @handles k))
(defn- invoke [k & args] (.invokeWithArguments ^MethodHandle (h k) (object-array args)))
(defn- null? [^MemorySegment p] (or (nil? p) (= 0 (.address p))))
(defn- cstr [^MemorySegment p] (when-not (null? p) (.getString (.reinterpret p Long/MAX_VALUE) 0)))

(defn- take-string!
  "Read an owned `char*` result and free it. nil when the call returned NULL."
  [^MemorySegment p]
  (when-not (null? p)
    (let [s (cstr p)]
      (invoke :free p)
      s)))

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
      (take-string! (invoke :find-config (.allocateFrom ^Arena arena (str path)))))))

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

;; ---------------------------------------------------------------------------
;; Public API — linting
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
   honoured. Not supported: `per-file-ignores` (there is no project file walk
   here — one source, one call) and `exclude` patterns; apply those at the call
   site. Throws ex-info when ruff cannot lint the input (unknown selector,
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
