//! Minimal C ABI over ruff's Python formatter AND linter so clj-ruff can call
//! them in-process via the JDK Foreign Function & Memory API (no subprocess,
//! no CLI).
//!
//! Symbols:
//!   char* ruff_format(const char* src, unsigned int line_length)
//!   char* ruff_format_with_config(const char* src, const char* config_path,
//!                                 const char* file_path,
//!                                 unsigned int line_length)
//!   char* ruff_lint(const char* src, unsigned int line_length,
//!                   const char* select, const char* ignore,
//!                   unsigned int preview)
//!   char* ruff_lint_with_config(const char* src, const char* config_path,
//!                               const char* file_path,
//!                               unsigned int line_length,
//!                               const char* select, const char* ignore,
//!                               unsigned int preview)
//!   char* ruff_find_config(const char* path)
//!   char* ruff_last_error(void)
//!   void  ruff_free_string(char* p)
//!   const char* ruff_version(void)
//!
//! Every `char*` return except `ruff_version` is a heap C string the caller MUST
//! free with `ruff_free_string`; NULL signals failure (invalid UTF-8,
//! parse/format error, unknown rule selector, unreadable configuration, panic).
//! After a NULL, `ruff_last_error()` returns a human-readable reason for the
//! CALLING THREAD (also heap, also freed by the caller), or NULL if none.
//!
//! `ruff_*_with_config` take the path of a ruff configuration file
//! (`pyproject.toml` with a `[tool.ruff]` table, `ruff.toml` or `.ruff.toml`);
//! it is resolved with ruff's OWN loader, so `extend = …` chains, `[lint]` /
//! `[format]` tables, `select` / `ignore` / `line-length` / `target-version` /
//! `quote-style` / `indent-style` / `docstring-code-format` … all behave exactly
//! as they do for the `ruff` CLI. A NULL `config_path` means "ruff defaults".
//! `file_path` (optional) supplies the file name ruff sees: it selects the
//! source type (`.py` vs `.pyi`) and resolves per-file `target-version`
//! overrides. Explicit `line_length` / `select` / `ignore` / `preview`
//! arguments are applied ON TOP of the configuration file.
//!
//! `ruff_find_config` performs ruff's own upward discovery from a file or
//! directory and returns the nearest configuration file path, or NULL when the
//! tree declares none (that is NOT an error: `ruff_last_error()` stays empty).
//!
//! `ruff_lint*` returns a TAB-separated record per diagnostic, one per line:
//!
//!   code \t row \t col \t end_row \t end_col \t fixable \t message
//!
//! Rows/columns are 1-based, `end_*` inclusive-exclusive, `fixable` is 0/1, and
//! `message` has `\`, TAB and NEWLINE backslash-escaped so a record is always a
//! single line. No diagnostics => empty string (NOT null).

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_uint};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::ptr;
use std::str::FromStr;
use std::sync::{Arc, Mutex, OnceLock};

use ruff_formatter::LineWidth;
use ruff_linter::Locator;
use ruff_linter::directives;
use ruff_linter::line_width::LineLength;
use ruff_linter::linter::check_path;
use ruff_linter::registry::Rule;
use ruff_linter::rule_selector::{PreviewOptions, RuleSelector};
use ruff_linter::settings::{flags, types::PreviewMode};
use ruff_linter::source_kind::SourceKind;
use ruff_linter::suppression::Suppressions;
use ruff_python_ast::PySourceType;
use ruff_python_codegen::Stylist;
use ruff_python_formatter::{PyFormatOptions, format_module_source};
use ruff_python_index::Indexer;
use ruff_python_parser::{ParseOptions, parse_unchecked};
use ruff_source_file::PositionEncoding;
use ruff_workspace::Settings;
use ruff_workspace::configuration::Configuration;
use ruff_workspace::pyproject::find_settings_toml;
use ruff_workspace::resolver::{
    ConfigurationOrigin, ConfigurationTransformer, resolve_root_settings,
};

// ---------------------------------------------------------------------------
// error channel
// ---------------------------------------------------------------------------

thread_local! {
    /// Why the LAST call on this thread returned NULL. Cleared at entry of every
    /// public entry point, so it never reports a stale failure.
    static LAST_ERROR: RefCell<Option<String>> = const { RefCell::new(None) };
}

fn clear_error() {
    LAST_ERROR.with(|e| *e.borrow_mut() = None);
}

fn set_error(message: impl Into<String>) {
    let message = message.into();
    LAST_ERROR.with(|e| *e.borrow_mut() = Some(message));
}

/// Take the calling thread's last failure reason, or NULL when the last call
/// succeeded. The string is heap-allocated; free it with `ruff_free_string`.
#[unsafe(no_mangle)]
pub extern "C" fn ruff_last_error() -> *mut c_char {
    match LAST_ERROR.with(|e| e.borrow_mut().take()) {
        Some(message) => into_c_string(message),
        None => ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/// Borrow a NUL-terminated C string as `&str`. `None` when null or not UTF-8.
///
/// # Safety
/// `p` must be null or a valid NUL-terminated C string.
unsafe fn c_str<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(p) }.to_str().ok()
}

/// Like `c_str`, but distinguishes "absent" (null pointer => `Ok(None)`) from
/// "present but not decodable" (`Err(())`).
///
/// # Safety
/// `p` must be null or a valid NUL-terminated C string.
unsafe fn opt_str<'a>(p: *const c_char, what: &str) -> Result<Option<&'a str>, ()> {
    if p.is_null() {
        return Ok(None);
    }
    match unsafe { c_str(p) } {
        Some(s) => Ok(Some(s)),
        None => {
            set_error(format!("{what} is not valid UTF-8"));
            Err(())
        }
    }
}

fn into_c_string(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(c) => c.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Comma/whitespace separated rule selectors ("F,E501" / "" / null).
fn parse_selectors(spec: Option<&str>) -> Result<Vec<RuleSelector>, ()> {
    let Some(spec) = spec else { return Ok(vec![]) };
    spec.split([',', ' ', '\t', '\n'])
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            RuleSelector::from_str(s).map_err(|_| {
                set_error(format!("unknown ruff rule selector `{s}`"));
            })
        })
        .collect()
}

fn escape_message(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            _ => out.push(ch),
        }
    }
    out
}

fn source_type_of(file_path: Option<&str>) -> PySourceType {
    file_path
        .map(|p| PySourceType::from(Path::new(p)))
        .unwrap_or_default()
}

// ---------------------------------------------------------------------------
// configuration
// ---------------------------------------------------------------------------

/// `resolve_root_settings` insists on a transformer (the CLI overlays its flags
/// through one). We have no CLI, so the configuration file is the whole truth.
struct Verbatim;

impl ConfigurationTransformer for Verbatim {
    fn transform(&self, config: Configuration) -> Configuration {
        config
    }
}

/// Resolved `Settings` per (config path, mtime): reading + parsing a
/// `pyproject.toml` for EVERY file of a directory walk is the difference between
/// a snappy and a sluggish `format_code`, and keying on the mtime keeps an edited
/// config honest.
fn settings_for(config_path: &str) -> Result<Arc<Settings>, String> {
    static CACHE: OnceLock<Mutex<HashMap<(String, Option<u128>), Arc<Settings>>>> = OnceLock::new();

    let stamp = std::fs::metadata(config_path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_nanos());
    let key = (config_path.to_string(), stamp);

    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(guard) = cache.lock()
        && let Some(settings) = guard.get(&key)
    {
        return Ok(Arc::clone(settings));
    }

    let settings = resolve_root_settings(
        Path::new(config_path),
        &Verbatim,
        ConfigurationOrigin::UserSpecified,
    )
    .map_err(|err| format!("invalid ruff configuration `{config_path}`: {err:#}"))?;
    let settings = Arc::new(settings);

    if let Ok(mut guard) = cache.lock() {
        guard.insert(key, Arc::clone(&settings));
    }
    Ok(settings)
}

/// Settings for "no configuration file": ruff's OWN defaults, i.e. exactly what
/// the `ruff` CLI applies in a directory with no `pyproject.toml`/`ruff.toml`
/// (`E4`, `E7`, `E9`, `F`; line length 88). NOT `LinterSettings::default()`,
/// whose rule table is the internal "everything" set and would fire `S`/`I`
/// rules nobody asked for.
fn default_settings() -> Arc<Settings> {
    static DEFAULTS: OnceLock<Arc<Settings>> = OnceLock::new();
    Arc::clone(DEFAULTS.get_or_init(|| {
        let root = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
        Arc::new(
            Configuration::default()
                .into_settings(&root)
                .unwrap_or_else(|_| Settings::default()),
        )
    }))
}

/// Nearest `pyproject.toml` (with `[tool.ruff]`) / `ruff.toml` / `.ruff.toml`
/// at or above `path`, exactly as the `ruff` CLI discovers it. NULL means "no
/// configuration in this tree" — not an error.
#[unsafe(no_mangle)]
pub extern "C" fn ruff_find_config(path: *const c_char) -> *mut c_char {
    clear_error();
    let Some(path) = (unsafe { c_str(path) }) else {
        set_error("path is null or not valid UTF-8");
        return ptr::null_mut();
    };

    catch_unwind(AssertUnwindSafe(|| {
        // `find_settings_toml` walks ANCESTORS, so a file path starts one level
        // too high; canonicalize first so a relative path still has ancestors.
        let start: PathBuf = std::fs::canonicalize(path).unwrap_or_else(|_| PathBuf::from(path));
        let start = if start.is_file() {
            start.parent().map(Path::to_path_buf).unwrap_or(start)
        } else {
            start
        };
        match find_settings_toml(&start) {
            Ok(Some(found)) => into_c_string(found.to_string_lossy().to_string()),
            Ok(None) => ptr::null_mut(),
            Err(err) => {
                set_error(format!("ruff configuration discovery failed: {err:#}"));
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or_else(|_| {
        set_error("ruff panicked during configuration discovery");
        ptr::null_mut()
    })
}

// ---------------------------------------------------------------------------
// format
// ---------------------------------------------------------------------------

/// Format `src` (UTF-8, NUL-terminated) with ruff's defaults. `line_length` 0 =>
/// ruff default (88).
#[unsafe(no_mangle)]
pub extern "C" fn ruff_format(src: *const c_char, line_length: c_uint) -> *mut c_char {
    ruff_format_with_config(src, ptr::null(), ptr::null(), line_length)
}

/// Format `src` using the configuration file at `config_path` (may be null) for
/// the file named `file_path` (may be null). `line_length` != 0 overrides the
/// configured width.
#[unsafe(no_mangle)]
pub extern "C" fn ruff_format_with_config(
    src: *const c_char,
    config_path: *const c_char,
    file_path: *const c_char,
    line_length: c_uint,
) -> *mut c_char {
    clear_error();
    let Some(source) = (unsafe { c_str(src) }) else {
        set_error("source is null or not valid UTF-8");
        return ptr::null_mut();
    };
    let (Ok(config_path), Ok(file_path)) = (
        unsafe { opt_str(config_path, "config path") },
        unsafe { opt_str(file_path, "file path") },
    ) else {
        return ptr::null_mut();
    };

    catch_unwind(AssertUnwindSafe(|| {
        let settings = match config_path.map(settings_for) {
            Some(Ok(settings)) => Some(settings),
            Some(Err(message)) => {
                set_error(message);
                return ptr::null_mut();
            }
            None => None,
        };

        let source_type = source_type_of(file_path);
        let mut options = match settings.as_deref() {
            Some(settings) => {
                settings
                    .formatter
                    .to_format_options(source_type, source, file_path.map(Path::new))
            }
            None => PyFormatOptions::from_source_type(source_type),
        };
        if line_length != 0
            && let Ok(w) = LineWidth::try_from(line_length as u16)
        {
            options = options.with_line_width(w);
        }

        match format_module_source(source, options) {
            Ok(printed) => into_c_string(printed.as_code().to_string()),
            Err(err) => {
                set_error(format!("ruff could not format the source: {err}"));
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or_else(|_| {
        set_error("ruff panicked while formatting");
        ptr::null_mut()
    })
}

// ---------------------------------------------------------------------------
// lint
// ---------------------------------------------------------------------------

/// Lint `src` with ruff's default rule set, optionally narrowed by `select` /
/// widened-then-narrowed by `ignore` (comma separated selectors, may be null).
/// `line_length` 0 => ruff default (88). `preview` != 0 enables preview rules.
///
/// Returns the TSV record stream documented at the top of this file, or NULL on
/// invalid UTF-8 / unknown selector / panic. An empty string means "clean".
#[unsafe(no_mangle)]
pub extern "C" fn ruff_lint(
    src: *const c_char,
    line_length: c_uint,
    select: *const c_char,
    ignore: *const c_char,
    preview: c_uint,
) -> *mut c_char {
    ruff_lint_with_config(
        src,
        ptr::null(),
        ptr::null(),
        line_length,
        select,
        ignore,
        preview,
    )
}

/// Lint `src` under the configuration file at `config_path` (may be null) as the
/// file named `file_path` (may be null). Explicit `line_length` / `select` /
/// `ignore` / `preview` arguments override the configuration.
#[unsafe(no_mangle)]
pub extern "C" fn ruff_lint_with_config(
    src: *const c_char,
    config_path: *const c_char,
    file_path: *const c_char,
    line_length: c_uint,
    select: *const c_char,
    ignore: *const c_char,
    preview: c_uint,
) -> *mut c_char {
    clear_error();
    let Some(source) = (unsafe { c_str(src) }) else {
        set_error("source is null or not valid UTF-8");
        return ptr::null_mut();
    };
    // A non-null-but-invalid string is an error, not "absent".
    let (Ok(config_path), Ok(file_path), Ok(select_spec), Ok(ignore_spec)) = (
        unsafe { opt_str(config_path, "config path") },
        unsafe { opt_str(file_path, "file path") },
        unsafe { opt_str(select, "select") },
        unsafe { opt_str(ignore, "ignore") },
    ) else {
        return ptr::null_mut();
    };

    catch_unwind(AssertUnwindSafe(|| {
        let settings = match config_path.map(settings_for) {
            Some(Ok(settings)) => Some(settings),
            Some(Err(message)) => {
                set_error(message);
                return ptr::null_mut();
            }
            None => None,
        };

        match lint_to_tsv(
            source,
            settings.as_deref(),
            file_path,
            line_length,
            select_spec,
            ignore_spec,
            preview,
        ) {
            Ok(tsv) => into_c_string(tsv),
            Err(()) => ptr::null_mut(),
        }
    }))
    .unwrap_or_else(|_| {
        set_error("ruff panicked while linting");
        ptr::null_mut()
    })
}

fn lint_to_tsv(
    source: &str,
    settings: Option<&Settings>,
    file_path: Option<&str>,
    line_length: c_uint,
    select: Option<&str>,
    ignore: Option<&str>,
    preview: c_uint,
) -> Result<String, ()> {
    let no_config = settings.is_none();
    let fallback;
    let settings: &Settings = match settings {
        Some(settings) => settings,
        None => {
            fallback = default_settings();
            &fallback
        }
    };
    let mut settings = settings.linter.clone();
    // `preview` is tri-state at the C boundary: 0 = off, 1 = on, anything else
    // (we use 2) = "whatever the configuration says".
    match preview {
        0 => settings.preview = PreviewMode::Disabled,
        1 => settings.preview = PreviewMode::Enabled,
        _ => {}
    }
    let preview_options = PreviewOptions {
        mode: settings.preview,
        ..PreviewOptions::default()
    };

    // With no configuration file, apply the rule set the `ruff` CLI applies in a
    // bare directory — `E4`, `E7`, `E9`, `F` — not ruff's internal all-rules table.
    if no_config {
        settings.rules = parse_selectors(Some("E4,E7,E9,F"))?
            .iter()
            .flat_map(|s| s.rules(&preview_options))
            .collect();
    }

    let selected = parse_selectors(select)?;
    if !selected.is_empty() {
        settings.rules = selected
            .iter()
            .flat_map(|s| s.rules(&preview_options))
            .collect();
    }
    for rule in parse_selectors(ignore)?
        .iter()
        .flat_map(|s| s.rules(&preview_options))
        .collect::<Vec<Rule>>()
    {
        settings.rules.disable(rule);
    }
    if line_length != 0
        && let Ok(w) = LineLength::try_from(line_length as u16)
    {
        settings.line_length = w;
        // E501 reads the pycodestyle-specific limit, which defaults independently.
        settings.pycodestyle.max_line_length = w;
    }

    let source_type = source_type_of(file_path);
    let source_kind = SourceKind::Python {
        code: source.to_string(),
        is_stub: source_type.is_stub(),
    };
    let target_version = settings.unresolved_target_version;
    let parse_options =
        ParseOptions::from(source_type).with_target_version(target_version.parser_version());
    let parsed = parse_unchecked(source_kind.source_code(), parse_options)
        .try_into_module()
        .ok_or_else(|| {
            set_error("ruff could not parse the source as a Python module");
        })?;

    let locator = Locator::new(source);
    let stylist = Stylist::from_tokens(parsed.tokens(), locator.contents());
    let indexer = Indexer::from_tokens(parsed.tokens(), locator.contents());
    let directives = directives::extract_directives(
        parsed.tokens(),
        directives::Flags::from_settings(&settings),
        &locator,
        &indexer,
    );
    let suppressions =
        Suppressions::from_tokens(locator.contents(), parsed.tokens(), &indexer, &settings);

    let diagnostics = check_path(
        Path::new(file_path.unwrap_or("<source>")),
        None,
        &locator,
        &stylist,
        &indexer,
        &directives,
        &settings,
        flags::Noqa::Enabled,
        &source_kind,
        source_type,
        &parsed,
        target_version,
        &suppressions,
    );

    let source_code = locator.to_source_code();
    let mut out = String::new();
    for msg in diagnostics {
        let range = msg.range().unwrap_or_default();
        let start = source_code.source_location(range.start(), PositionEncoding::Utf8);
        let end = source_code.source_location(range.end(), PositionEncoding::Utf8);
        let code = msg
            .secondary_code()
            .map(|c| c.as_str().to_string())
            .unwrap_or_else(|| msg.id().as_str().to_string());
        out.push_str(&format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\n",
            code,
            start.line.get(),
            start.character_offset.get(),
            end.line.get(),
            end.character_offset.get(),
            u8::from(msg.fix().is_some()),
            escape_message(msg.concise_message().to_string().as_str()),
        ));
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// misc
// ---------------------------------------------------------------------------

/// Free a string returned by any of the `char*` entry points.
#[unsafe(no_mangle)]
pub extern "C" fn ruff_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)) };
    }
}

/// The bundled ruff release this cdylib was built against. Static; caller must
/// NOT free it.
#[unsafe(no_mangle)]
pub extern "C" fn ruff_version() -> *const c_char {
    static VERSION: OnceLock<CString> = OnceLock::new();
    VERSION
        .get_or_init(|| {
            CString::new(format!(
                "{} (ruff {})",
                env!("CARGO_PKG_VERSION"),
                ruff_linter::VERSION
            ))
            .unwrap_or_else(|_| CString::new("unknown").unwrap())
        })
        .as_ptr()
}
