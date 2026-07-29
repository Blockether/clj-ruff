//! Minimal C ABI over ruff's Python formatter AND linter so clj-ruff can call
//! them in-process via the JDK Foreign Function & Memory API (no subprocess,
//! no CLI).
//!
//! Symbols:
//!   char* ruff_format(const char* src, unsigned int line_length)
//!   char* ruff_lint(const char* src, unsigned int line_length,
//!                   const char* select, const char* ignore,
//!                   unsigned int preview)
//!   void  ruff_free_string(char* p)
//!   const char* ruff_version(void)
//!
//! `ruff_format` / `ruff_lint` return a heap C string the caller MUST free with
//! `ruff_free_string`; NULL signals failure (invalid UTF-8, parse/format error,
//! unknown rule selector, panic).
//!
//! `ruff_lint` returns a TAB-separated record per diagnostic, one per line:
//!
//!   code \t row \t col \t end_row \t end_col \t fixable \t message
//!
//! Rows/columns are 1-based, `end_*` inclusive-exclusive, `fixable` is 0/1, and
//! `message` has `\`, TAB and NEWLINE backslash-escaped so a record is always a
//! single line. No diagnostics => empty string (NOT null).

use std::ffi::{CStr, CString, c_char, c_uint};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::ptr;
use std::str::FromStr;
use std::sync::OnceLock;

use ruff_formatter::LineWidth;
use ruff_linter::directives;
use ruff_linter::line_width::LineLength;
use ruff_linter::linter::check_path;
use ruff_linter::registry::Rule;
use ruff_linter::rule_selector::{PreviewOptions, RuleSelector};
use ruff_linter::settings::{LinterSettings, flags, types::PreviewMode};
use ruff_linter::source_kind::SourceKind;
use ruff_linter::suppression::Suppressions;
use ruff_linter::Locator;
use ruff_python_ast::PySourceType;
use ruff_python_codegen::Stylist;
use ruff_python_formatter::{PyFormatOptions, format_module_source};
use ruff_python_index::Indexer;
use ruff_python_parser::{ParseOptions, parse_unchecked};
use ruff_source_file::PositionEncoding;

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
        .map(|s| RuleSelector::from_str(s).map_err(|_| ()))
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

// ---------------------------------------------------------------------------
// format
// ---------------------------------------------------------------------------

/// Format `src` (UTF-8, NUL-terminated). `line_length` 0 => ruff default (88).
#[unsafe(no_mangle)]
pub extern "C" fn ruff_format(src: *const c_char, line_length: c_uint) -> *mut c_char {
    let Some(source) = (unsafe { c_str(src) }) else {
        return ptr::null_mut();
    };

    catch_unwind(AssertUnwindSafe(|| {
        let mut options = PyFormatOptions::default();
        if line_length != 0
            && let Ok(w) = LineWidth::try_from(line_length as u16)
        {
            options = options.with_line_width(w);
        }

        match format_module_source(source, options) {
            Ok(printed) => into_c_string(printed.as_code().to_string()),
            Err(_) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
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
    let Some(source) = (unsafe { c_str(src) }) else {
        return ptr::null_mut();
    };
    // A non-null-but-invalid selector string is an error, not "no selectors".
    if (!select.is_null() && unsafe { c_str(select) }.is_none())
        || (!ignore.is_null() && unsafe { c_str(ignore) }.is_none())
    {
        return ptr::null_mut();
    }
    let select_spec = unsafe { c_str(select) };
    let ignore_spec = unsafe { c_str(ignore) };

    catch_unwind(AssertUnwindSafe(|| {
        match lint_to_tsv(source, line_length, select_spec, ignore_spec, preview != 0) {
            Ok(tsv) => into_c_string(tsv),
            Err(()) => ptr::null_mut(),
        }
    }))
    .unwrap_or(ptr::null_mut())
}

fn lint_to_tsv(
    source: &str,
    line_length: c_uint,
    select: Option<&str>,
    ignore: Option<&str>,
    preview: bool,
) -> Result<String, ()> {
    let preview_options = PreviewOptions {
        mode: if preview {
            PreviewMode::Enabled
        } else {
            PreviewMode::Disabled
        },
        ..PreviewOptions::default()
    };

    let mut settings = LinterSettings::default();
    settings.preview = preview_options.mode;

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

    let source_type = PySourceType::default();
    let source_kind = SourceKind::Python {
        code: source.to_string(),
        is_stub: source_type.is_stub(),
    };
    let target_version = settings.unresolved_target_version;
    let parse_options =
        ParseOptions::from(source_type).with_target_version(target_version.parser_version());
    let parsed = parse_unchecked(source_kind.source_code(), parse_options)
        .try_into_module()
        .ok_or(())?;

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
        Path::new("<source>"),
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

/// Free a string returned by `ruff_format` / `ruff_lint`.
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
