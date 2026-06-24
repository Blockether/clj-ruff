//! Minimal C ABI over ruff's Python formatter so clj-ruff can call it in-process
//! via the JDK Foreign Function & Memory API (no subprocess, no CLI).
//!
//! Two symbols:
//!   char* ruff_format(const char* src, unsigned int line_length)
//!   void  ruff_free_string(char* p)
//! `ruff_format` returns a heap C string the caller MUST free with
//! `ruff_free_string`. NULL on invalid UTF-8 / parse error / format error.

use std::ffi::{c_char, c_uint, CStr, CString};
use std::ptr;

use ruff_formatter::LineWidth;
use ruff_python_formatter::{format_module_source, PyFormatOptions};

/// Format `src` (UTF-8, NUL-terminated). `line_length` 0 => ruff default (88).
#[no_mangle]
pub extern "C" fn ruff_format(src: *const c_char, line_length: c_uint) -> *mut c_char {
    if src.is_null() {
        return ptr::null_mut();
    }
    let source = match unsafe { CStr::from_ptr(src) }.to_str() {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };

    let mut options = PyFormatOptions::default();
    if line_length != 0 {
        if let Ok(w) = LineWidth::try_from(line_length as u16) {
            options = options.with_line_width(w);
        }
    }

    match format_module_source(source, options) {
        Ok(printed) => match CString::new(printed.as_code()) {
            Ok(c) => c.into_raw(),
            Err(_) => ptr::null_mut(),
        },
        Err(_) => ptr::null_mut(),
    }
}

/// Free a string returned by `ruff_format`.
#[no_mangle]
pub extern "C" fn ruff_free_string(p: *mut c_char) {
    if !p.is_null() {
        unsafe { drop(CString::from_raw(p)) };
    }
}

/// The bundled ruff release this cdylib was built against. Static; caller must
/// NOT free it.
#[no_mangle]
pub extern "C" fn ruff_version() -> *const c_char {
    // NUL-terminated, 'static.
    concat!(env!("CARGO_PKG_VERSION"), " (ruff ", "0.15.19", ")\0").as_ptr() as *const c_char
}
