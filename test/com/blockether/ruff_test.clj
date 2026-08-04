(ns com.blockether.ruff-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [com.blockether.ruff :as ruff]))

;; These run against the REAL ruff binary resolved from resources/prebuilds
;; (CI stages it before `clojure -X:test`) or a RUFF_NATIVE_PATH override.

(deftest resolves-binary
  (is (ruff/available?))
  (is (str/includes? (ruff/version) "ruff")))

(deftest wraps-long-call
  (let [out (ruff/format
              "result = some_function(argument_one, argument_two, argument_three, argument_four, keyword=value)"
              {:line-length 60})]
    (is (str/includes? out "some_function(\n"))
    (is (str/includes? out "    argument_one,"))
    ;; trailing magic comma on the wrapped call
    (is (str/includes? out "    keyword=value,\n)"))))

(deftest normalizes-style
  ;; black/ruff style: spaces around dict colons + after commas, double quotes.
  (is (= "x = {\"a\": 1, \"b\": 2}\n"
        (ruff/format "x={'a':1,'b':2}" {:line-length 88}))))

(deftest leaves-short-code-essentially-alone
  (is (= "y = 1 + 2\n" (ruff/format "y = 1 + 2" {}))))

(deftest format-throws-on-invalid-python
  (is (thrown? clojure.lang.ExceptionInfo (ruff/format "def (((broken" {}))))

(deftest format-or-falls-back-verbatim
  (testing "invalid python returns the original source unchanged"
    (is (= "def (((broken" (ruff/format-or "def (((broken" {})))))

(deftest lint-reports-unused-import
  (let [ds   (ruff/lint "import os\nx = 1\n")
        f401 (first (filter #(= "F401" (:code %)) ds))]
    (is (contains? (set (map :code ds)) "F401"))
    (is (= {:code "F401" :row 1 :col 8 :end-row 1 :end-col 10 :is-fixable true}
           (dissoc f401 :message)))
    (is (str/includes? (:message f401) "imported but unused"))))

(deftest lint-clean-source-is-empty
  (is (= [] (ruff/lint "x = 1\n"))))

(deftest lint-default-rule-set-is-ruff-cli-default
  (testing "no configuration => exactly ruff's own default selection: E4, E7, E9, F"
    (is (= ["E701" "E401" "E722" "E741" "F401"]
           (distinct (map :code (ruff/lint (str "import os\n"
                                                "import sys, collections\n"
                                                "try:\n  pass\nexcept: pass\n"
                                                "l = 1\n"))))))
    (testing "and nothing outside it — no isort (I), no flake8-bandit (S), no E501"
      (is (empty? (ruff/lint (str "import sys\nimport os\n"
                                  "print(os, sys)\n"
                                  "y = \"" (apply str (repeat 120 "a")) "\"\n"
                                  "print(y)\n")))))))

(deftest lint-select-replaces-default-rule-set
  (testing "explicit :select narrows to just that rule — string, keyword and collection forms"
    (doseq [sel ["E711" :E711 ["E711"] ["F" "E711"]]]
      (is (= ["E711"] (map :code (ruff/lint "x = 1\nif x == None:\n    pass\n" {:select sel})))
          (str "select " (pr-str sel))))))

(deftest lint-ignore-subtracts
  (is (empty? (ruff/lint "import os\n" {:ignore "F401"})))
  (is (empty? (ruff/lint "import os\n" {:ignore [:F]}))))

(deftest lint-line-length-drives-e501
  (let [long-line "import os_this_is_a_very_long_module_name_indeed_yes\n"]
    (is (empty? (ruff/lint long-line {:select "E501"})))
    (is (= ["E501"] (map :code (ruff/lint long-line {:select "E501" :line-length 20}))))))

(deftest lint-honours-inline-noqa
  (is (empty? (ruff/lint "import os  # noqa: F401\n"))))

(deftest lint-reports-syntax-errors-as-diagnostics
  (let [ds (ruff/lint "def (((broken")]
    (is (seq ds))
    (is (every? #(= "invalid-syntax" (:code %)) ds))
    (is (every? #(false? (:is-fixable %)) ds))))

(deftest lint-throws-on-unknown-selector
  (is (thrown? clojure.lang.ExceptionInfo (ruff/lint "x = 1\n" {:select "NOPE9"}))))

(deftest lint-or-returns-the-default-on-failure
  (is (= :fallback (ruff/lint-or "x = 1\n" {:select "NOPE9"} :fallback)))
  (is (nil? (ruff/lint-or "x = 1\n" {:select "NOPE9"})))
  (testing "and the real diagnostics otherwise"
    (is (= ["F401"] (map :code (ruff/lint-or "import os\n"))))))

(deftest lint-messages-survive-escaping
  ;; the cdylib escapes \\, TAB and NEWLINE so each diagnostic is one TSV line;
  ;; backticks/quotes in ruff messages must come back verbatim.
  (let [msg (:message (first (ruff/lint "import os\n")))]
    (is (= "`os` imported but unused" msg))))

(deftest isolated-ignores-cwd-config
  ;; `--isolated` means a stray ruff.toml/pyproject.toml in the working dir
  ;; cannot change the result — line-length here is the only authority.
  (let [out (ruff/format "a_very_long_variable_name_here = another_long_name + yet_another_long_name_x"
                         {:line-length 40})]
    (is (str/includes? out "(\n"))))

;; ---------------------------------------------------------------------------
;; configuration files
;; ---------------------------------------------------------------------------

(defn- tmp-project
  "A throwaway directory holding `files` ({relative-name content}). Returns its
   java.io.File."
  [files]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "clj-ruff-cfg" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (doseq [[name content] files
            :let [f (java.io.File. dir ^String name)]]
      (.mkdirs (.getParentFile f))
      (spit f content)
      (.deleteOnExit f))
    dir))

(deftest config-file-discovery
  (testing "ruff.toml is found from a file inside the tree, like the ruff CLI"
    (let [dir (tmp-project {"ruff.toml" "line-length = 50\n"
                            "pkg/a.py"  "x = 1\n"})]
      (is (= (.getCanonicalPath (java.io.File. dir "ruff.toml"))
             (ruff/config-file (str (java.io.File. dir "pkg/a.py")))
             (ruff/config-file (str (java.io.File. dir "pkg")))))))
  (testing "pyproject.toml counts only when it carries [tool.ruff]"
    (let [bare (tmp-project {"pyproject.toml" "[project]\nname = \"x\"\n"})
          real (tmp-project {"pyproject.toml" "[project]\nname = \"x\"\n[tool.ruff]\nline-length = 50\n"})]
      (is (nil? (ruff/config-file (str bare))))
      (is (= (.getCanonicalPath (java.io.File. real "pyproject.toml"))
             (ruff/config-file (str real))))))
  (testing "no configuration anywhere in the tree is nil, not an error"
    (is (nil? (ruff/config-file (str (tmp-project {"a.py" "x = 1\n"})))))))

(deftest config-drives-format-and-lint
  (let [dir  (tmp-project {"ruff.toml" "line-length = 40\n[lint]\nselect = [\"E\", \"F\", \"I\"]\n"})
        cfg  (str (java.io.File. dir "ruff.toml"))
        long "import sys\nimport os\nx = [\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\", \"bbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"]\n"]
    (testing "the config's line-length wraps the formatter"
      (is (str/includes? (ruff/format long {:config cfg}) "x = [\n"))
      (is (not (str/includes? (ruff/format long) "x = [\n"))))
    (testing "the config's select turns on rules outside the default set"
      (let [codes (set (map :code (ruff/lint long {:config cfg})))]
        (is (contains? codes "E501"))
        (is (contains? codes "I001"))))
    (testing ":line-length still overrides the configuration"
      (is (not (str/includes? (ruff/format long {:config cfg :line-length 200}) "x = [\n"))))
    (testing "an unreadable configuration is an ex-info, not a silent default"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ruff/format "x = 1\n" {:config (str (java.io.File. dir "nope.toml"))}))))))

(deftest path-selects-source-type
  (testing ":path .pyi formats as a stub — blank lines between defs collapse"
    (let [src "def f() -> int: ...\n\n\n\ndef g() -> int: ...\n"]
      (is (= "def f() -> int: ...\ndef g() -> int: ...\n"
             (ruff/format src {:path "s.pyi"})))
      (is (= "def f() -> int: ...\n\n\ndef g() -> int: ...\n"
             (ruff/format src {:path "mod.py"})
             (ruff/format src))))))

(deftest config-relative-globs-anchor-at-the-config-directory
  (testing "per-file-ignores match paths relative to the config, not the CWD"
    (let [dir (tmp-project {"ruff.toml" (str "[lint]\nselect = [\"F\"]\n"
                                             "[lint.per-file-ignores]\n"
                                             "\"shims/*.py\" = [\"F401\"]\n")
                            "shims/a.py" "import os\n"
                            "pkg/b.py"   "import os\n"})
          cfg (str (java.io.File. dir "ruff.toml"))
          codes (fn [rel] (mapv :code (ruff/lint "import os\n"
                                                 {:config cfg
                                                  :path (str (java.io.File. dir ^String rel))})))]
      (is (= [] (codes "shims/a.py")))
      (is (= ["F401"] (codes "pkg/b.py"))))))

;; ── Release version consistency ──────────────────────────────────────────────
;; Regression: `resources/VERSION` was EMPTY while the cdylib crate in
;; native/ruff-c/Cargo.toml still said 0.3.2 and Clojars carried 0.3.3. Every
;; local `-T:build jar` therefore stamped the namespaced `ruff/VERSION`
;; resource — the one the native resolver uses to fetch
;; `com.blockether/ruff-native-<platform>` — with a bare "-SNAPSHOT", and
;; `(ruff/version)` reported a release number that had never been released.

(def ^:private declared-version (str/trim (slurp "resources/VERSION")))

(defn- crate-version []
  (second (re-find #"(?m)^version\s*=\s*\"([^\"]+)\"" (slurp "native/ruff-c/Cargo.toml"))))

(deftest version-sources-agree
  (testing "resources/VERSION carries a real release number"
    (is (re-matches #"\d+\.\d+\.\d+" declared-version)))
  (testing "the cdylib crate version IS clj-ruff's own version"
    (is (= declared-version (crate-version))))
  (testing "the linked library reports <clj-ruff> (ruff <pinned>)"
    (is (re-matches #"\d+\.\d+\.\d+ \(ruff \d+\.\d+\.\d+\)" (ruff/version)))))
