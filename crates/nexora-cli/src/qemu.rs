//! Native desktop QEMU discovery and diagnostics.
//!
//! This module intentionally does not use the Android/Bionic QEMU binary.
//! Desktop hosts must provide a native x86_64 system emulator.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

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

/// Boot an existing QCOW2 disk with a serial-only foreground console.
pub fn start(disk: &Path) -> Result<(), String> {
    if !disk.is_file() {
        return Err(format!("VM disk does not exist: {}", disk.display()));
    }
    let disk_text = disk.to_string_lossy();
    if disk_text.contains(',') {
        return Err(
            "VM disk paths containing commas are not supported by this QEMU drive configuration."
                .to_string(),
        );
    }
    #[cfg(windows)]
    let disk_path = disk_text.replace('\\', "/");
    #[cfg(not(windows))]
    let disk_path = disk_text.into_owned();

    let executable = locate()?;
    println!("Starting desktop QEMU with disk: {}", disk.display());
    println!("Serial console is attached to this terminal. Press Ctrl+C to stop QEMU.");

    let status = Command::new(&executable)
        .args([
            "-machine",
            "q35",
            "-accel",
            "tcg",
            "-cpu",
            "max",
            "-m",
            "2048",
            "-smp",
            "2",
            "-display",
            "none",
            "-monitor",
            "none",
            "-serial",
            "stdio",
            "-nic",
            "none",
            "-no-reboot",
        ])
        .arg("-drive")
        .arg(format!("file={disk_path},format=qcow2,if=virtio"))
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .status()
        .map_err(|error| format!("Could not start QEMU: {error}"))?;

    if status.success() {
        Ok(())
    } else {
        Err(format!("QEMU exited with status {status}"))
    }
}

const UBUNTU_IMAGE_URL: &str =
    "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img";
const UBUNTU_SUMS_URL: &str = "https://cloud-images.ubuntu.com/noble/current/SHA256SUMS";
const UBUNTU_IMAGE_NAME: &str = "noble-server-cloudimg-amd64.img";

fn locate_qemu_img() -> Result<PathBuf, String> {
    if let Some(explicit) = env::var_os("NEXORA_QEMU_IMG_BIN") {
        let path = PathBuf::from(explicit);
        if path.is_file() {
            return Ok(path);
        }
        return Err(format!(
            "NEXORA_QEMU_IMG_BIN points to a missing file: {}",
            path.display()
        ));
    }

    let qemu = locate()?;
    let directory = qemu.parent().unwrap_or_else(|| Path::new("."));
    let names: &[&str] = if cfg!(windows) {
        &["qemu-img.exe", "qemu-img"]
    } else {
        &["qemu-img"]
    };
    for name in names {
        let candidate = directory.join(name);
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    if let Some(paths) = env::var_os("PATH") {
        for directory in env::split_paths(&paths) {
            for name in names {
                let candidate = directory.join(name);
                if candidate.is_file() {
                    return Ok(candidate);
                }
            }
        }
    }
    Err("qemu-img was not found. Install QEMU utilities or set NEXORA_QEMU_IMG_BIN.".to_string())
}

pub fn ubuntu_directory() -> Result<PathBuf, String> {
    #[cfg(windows)]
    let base = env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .ok_or_else(|| "LOCALAPPDATA is not configured".to_string())?;
    #[cfg(not(windows))]
    let base = env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share")))
        .ok_or_else(|| "Neither XDG_DATA_HOME nor HOME is configured".to_string())?;
    Ok(base.join("nexora").join("vms").join("ubuntu"))
}

/// Download Ubuntu 24.04's official cloud image, verify its SHA-256, and
/// expand its sparse QCOW2 virtual capacity to 20 GiB without overwriting data.
pub fn install_ubuntu() -> Result<PathBuf, String> {
    let qemu_img = locate_qemu_img()?;
    let directory = ubuntu_directory()?;
    fs::create_dir_all(&directory).map_err(|error| {
        format!(
            "Cannot create VM directory '{}': {error}",
            directory.display()
        )
    })?;
    let disk = directory.join("ubuntu-noble-amd64.qcow2");
    if disk.exists() {
        return Err(format!(
            "Ubuntu VM disk already exists at '{}'. Refusing to overwrite it.",
            disk.display()
        ));
    }

    let download = directory.join(UBUNTU_IMAGE_NAME);
    let checksums = directory.join("SHA256SUMS");
    download_file(UBUNTU_IMAGE_URL, &download)?;
    if let Err(error) = download_file(UBUNTU_SUMS_URL, &checksums) {
        let _ = fs::remove_file(&download);
        return Err(error);
    }
    let expected = match expected_sha256(&checksums) {
        Ok(value) => value,
        Err(error) => {
            let _ = fs::remove_file(&download);
            return Err(error);
        }
    };
    if let Err(error) = verify_sha256(&download, &expected) {
        let _ = fs::remove_file(&download);
        return Err(error);
    }

    fs::rename(&download, &disk)
        .map_err(|error| format!("Cannot move verified Ubuntu image into place: {error}"))?;
    let resize = Command::new(&qemu_img)
        .arg("resize")
        .arg(&disk)
        .arg("20G")
        .output()
        .map_err(|error| format!("Could not run qemu-img resize: {error}"))?;
    if !resize.status.success() {
        let _ = fs::remove_file(&disk);
        return Err(format!(
            "qemu-img resize failed: {}",
            String::from_utf8_lossy(&resize.stderr).trim()
        ));
    }
    let info = Command::new(&qemu_img)
        .args(["info", "--output=json"])
        .arg(&disk)
        .output()
        .map_err(|error| format!("Could not inspect Ubuntu disk: {error}"))?;
    if !info.status.success() {
        return Err(format!(
            "qemu-img could not validate the prepared disk: {}",
            String::from_utf8_lossy(&info.stderr).trim()
        ));
    }

    println!("Ubuntu 24.04 cloud disk prepared: {}", disk.display());
    println!("Virtual capacity: 20 GiB (sparse QCOW2).");
    println!(
        "Cloud-init user setup is not configured yet; guest login provisioning is the next step."
    );
    Ok(disk)
}

fn download_file(url: &str, destination: &Path) -> Result<(), String> {
    let output = Command::new("curl")
        .args([
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "--output",
        ])
        .arg(destination)
        .arg(url)
        .output()
        .map_err(|error| format!("Could not start curl for '{url}': {error}"))?;
    if !output.status.success() {
        let _ = fs::remove_file(destination);
        return Err(format!(
            "Download failed for '{url}': {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(())
}

fn expected_sha256(checksums: &Path) -> Result<String, String> {
    let content = fs::read_to_string(checksums)
        .map_err(|error| format!("Cannot read Ubuntu SHA256SUMS: {error}"))?;
    for line in content.lines() {
        let mut fields = line.split_whitespace();
        let hash = fields.next().unwrap_or_default();
        let filename = fields.next().unwrap_or_default().trim_start_matches('*');
        if filename == UBUNTU_IMAGE_NAME
            && hash.len() == 64
            && hash.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            return Ok(hash.to_ascii_lowercase());
        }
    }
    Err(format!(
        "Ubuntu SHA256SUMS has no valid checksum for {UBUNTU_IMAGE_NAME}"
    ))
}

fn verify_sha256(file: &Path, expected: &str) -> Result<(), String> {
    #[cfg(windows)]
    let output = Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-FileHash -Algorithm SHA256 -LiteralPath $args[0]).Hash",
        ])
        .arg(file)
        .output();
    #[cfg(not(windows))]
    let output = Command::new("sha256sum").arg(file).output();

    let output = output.map_err(|error| format!("Could not calculate SHA-256: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "SHA-256 utility failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    let actual = String::from_utf8_lossy(&output.stdout)
        .split_whitespace()
        .find(|part| part.len() == 64 && part.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .map(str::to_ascii_lowercase)
        .ok_or_else(|| "Could not parse SHA-256 utility output".to_string())?;
    if actual != expected {
        return Err(format!(
            "Ubuntu image checksum mismatch (expected {expected}, received {actual})."
        ));
    }
    Ok(())
}

/// Verify that both QEMU's system emulator and disk utility are usable.
pub fn check() -> Result<String, String> {
    let emulator = version()?;
    let image_tool = locate_qemu_img()?;
    let output = Command::new(&image_tool)
        .arg("--version")
        .output()
        .map_err(|error| format!("qemu-img was found but could not start: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "qemu-img version probe failed with status {}",
            output.status
        ));
    }
    let image_version = String::from_utf8_lossy(&output.stdout).trim().to_string();
    Ok(format!(
        "{emulator}\nDisk utility: {}\n{image_version}",
        image_tool.display()
    ))
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

    Ok(format!(
        "Executable: {}
{text}",
        executable.display()
    ))
}

#[cfg(test)]
mod tests {
    use super::{executable_names, expected_sha256, UBUNTU_IMAGE_NAME};
    use std::fs;

    #[test]
    fn desktop_binary_name_is_qemu_system_x86_64() {
        let names = executable_names();
        assert!(names
            .iter()
            .any(|name| name.starts_with("qemu-system-x86_64")));
    }

    #[test]
    fn extracts_ubuntu_image_checksum_from_manifest() {
        let path =
            std::env::temp_dir().join(format!("nexora-sha256-manifest-{}.txt", std::process::id()));
        let expected = "a".repeat(64);
        fs::write(
            &path,
            format!("{expected}  {UBUNTU_IMAGE_NAME}\n{expected}  another.img\n"),
        )
        .unwrap();

        assert_eq!(expected_sha256(&path).unwrap(), expected);
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn rejects_manifest_without_ubuntu_image_checksum() {
        let path =
            std::env::temp_dir().join(format!("nexora-sha256-invalid-{}.txt", std::process::id()));
        fs::write(&path, format!("{}  another.img\n", "b".repeat(64))).unwrap();

        assert!(expected_sha256(&path).is_err());
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn verifies_sha256_on_the_current_host() {
        let path =
            std::env::temp_dir().join(format!("nexora-sha256-content-{}.txt", std::process::id()));
        fs::write(&path, b"abc").unwrap();

        let expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assert!(super::verify_sha256(&path, expected).is_ok());
        fs::remove_file(path).unwrap();
    }
}
