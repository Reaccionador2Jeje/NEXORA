//! Native desktop QEMU discovery and diagnostics.
//!
//! This module intentionally does not use the Android/Bionic QEMU binary.
//! Desktop hosts must provide a native x86_64 system emulator.

use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;

const OVERRIDE: &str = "NEXORA_QEMU_BIN";

/// Locate the native x86_64 QEMU executable.
pub fn locate() -> Result<PathBuf, String> {
    if let Some(explicit) = env::var_os(OVERRIDE) {
        let path = PathBuf::from(explicit);
        if path.is_file() {
            return Ok(path);
        }
        return Err(format!(
            "{OVERRIDE} points to a file that does not exist: {}",
            path.display()
        ));
    }

    let names = executable_names();
    let paths = env::var_os("PATH").ok_or_else(|| "PATH is not configured".to_string())?;

    for directory in env::split_paths(&paths) {
        for name in &names {
            let candidate = directory.join(name);
            if candidate.is_file() {
                return Ok(candidate);
            }
        }
    }

    Err(
        "QEMU was not found. Install the native QEMU system emulator and add its folder to PATH, or set NEXORA_QEMU_BIN to its full executable path."
            .to_string(),
    )
}

/// Return the executable names appropriate for the current host.
fn executable_names() -> Vec<&'static str> {
    if cfg!(windows) {
        vec!["qemu-system-x86_64.exe", "qemu-system-x86_64"]
    } else {
        vec!["qemu-system-x86_64"]
    }
}

/// Run QEMU's version probe and return its output.
pub fn version() -> Result<String, String> {
    let executable = locate()?;
    let output = Command::new(&executable)
        .arg("--version")
        .output()
        .map_err(|error| {
            format!(
                "QEMU was found at '{}' but could not be started: {error}",
                executable.display()
            )
        })?;

    if !output.status.success() {
        return Err(format!(
            "QEMU version probe failed with status {}",
            output.status
        ));
    }

    let mut text = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if text.is_empty() {
        text = String::from_utf8_lossy(&output.stderr).trim().to_string();
    }
    if text.is_empty() {
        return Err("QEMU started but returned no version information".to_string());
    }

    Ok(format!("Executable: {}
{text}", executable.display()))
}

/// Check whether a user-supplied path is a regular file.
#[allow(dead_code)]
fn is_file(path: &Path) -> bool {
    path.is_file()
}

#[cfg(test)]
mod tests {
    use super::executable_names;

    #[test]
    fn desktop_binary_name_is_qemu_system_x86_64() {
        let names = executable_names();
        assert!(names.iter().any(|name| name.starts_with("qemu-system-x86_64")));
    }
}
