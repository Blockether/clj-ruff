(ns com.blockether.ruff
  "Clojure binding to ruff (https://github.com/astral-sh/ruff) for Python code
   FORMATTING ONLY, through the JDK Foreign Function & Memory API.

   ruff does not publish a C-ABI library — only a CLI. So this binds a tiny
   first-party cdylib, `ruff-c` (native/ruff-c, a thin `extern \"C\"` wrapper over
   ruff's `ruff_python_formatter` crate), exactly the way clj-fff binds `fff-c`.
   Formatting runs IN-PROCESS via a downcall — no subprocess, no CLI, no config
   discovery. `format` takes Python source and returns it reformatted (long
   calls/collections wrapped multiline, black-compatible style).

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
    {:format  (down "ruff_format"      (fd addr addr u32))
     :free    (down "ruff_free_string" (fd nil addr))
     :version (down "ruff_version"     (fd addr))}))

(defonce ^:private handles (delay (bind!)))
(defn- h [k] (get @handles k))
(defn- invoke [k & args] (.invokeWithArguments ^MethodHandle (h k) (object-array args)))
(defn- null? [^MemorySegment p] (or (nil? p) (= 0 (.address p))))
(defn- cstr [^MemorySegment p] (when-not (null? p) (.getString (.reinterpret p Long/MAX_VALUE) 0)))

;; ---------------------------------------------------------------------------
;; Public API — formatting only
;; ---------------------------------------------------------------------------

(defn format
  "Format Python `code` and return the reformatted source as a String.

   Options:
     :line-length  int — wrap width (0 / omitted => ruff default 88).

   Runs entirely in-process (no subprocess, no pyproject.toml / ruff.toml
   discovery): output depends only on `code` and `:line-length`. Throws ex-info
   when ruff can't format the input (syntactically invalid Python); callers that
   want a verbatim fallback should use `format-or` or catch."
  (^String [code] (format code nil))
  (^String [^String code {:keys [line-length]}]
   (when (nil? code) (throw (ex-info "ruff/format: code is nil" {})))
   (with-open [arena (Arena/ofConfined)]
     (let [src (.allocateFrom ^Arena arena ^String code)
           ret ^MemorySegment (invoke :format src (int (or line-length 0)))]
       (if (null? ret)
         (throw (ex-info "ruff: format failed (syntactically invalid Python, or a parse/format error)"
                  {:line-length line-length}))
         (let [s (cstr ret)]
           (invoke :free ret)
           s))))))

(defn format-or
  "Like `format`, but returns `code` unchanged if ruff is unavailable or fails
   (the convenient display-side default — never lose the original source)."
  (^String [code] (format-or code nil))
  (^String [code opts]
   (try (format code opts)
        (catch Throwable _ code))))

(defn version
  "The bundled ruff release string (`clj-ruff-cdylib (ruff X.Y.Z)`)."
  ^String []
  (cstr (invoke :version)))

(defn available?
  "True if the ruff native library can be resolved + linked on this platform."
  []
  (try (boolean @handles) (catch Throwable _ false)))
