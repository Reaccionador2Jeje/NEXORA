use std::env;
use std::fs;
use std::io::{self, Write};
use std::process::{self, Command as ProcessCommand};

use nexora_core::{parse_command, Command, VERSION};

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();

    if args.iter().any(|arg| arg == "--version" || arg == "-V") {
        println!("NEXORA {VERSION}");
        return;
    }

    if args.iter().any(|arg| arg == "--help" || arg == "-h") {
        print_help();
        return;
    }

    if !args.is_empty() {
        let line = args.join(" ");
        if let Err(error) = execute_line(&line) {
            eprintln!("nex: {error}");
            process::exit(1);
        }
        return;
    }

    println!("NEXORA {VERSION}");
    println!("Your Terminal. Your Universe.");
    println!("Type 'help' to see available commands.\n");
    interactive_loop();
}

fn interactive_loop() {
    let stdin = io::stdin();
    let mut line = String::new();

    loop {
        let current_dir = env::current_dir()
            .map(|path| path.display().to_string())
            .unwrap_or_else(|_| "?".to_string());

        print!("nexora:{current_dir}> ");
        if io::stdout().flush().is_err() {
            break;
        }

        line.clear();
        match stdin.read_line(&mut line) {
            Ok(0) => {
                println!();
                break;
            }
            Ok(_) => {}
            Err(error) => {
                eprintln!("nex: input error: {error}");
                break;
            }
        }

        match parse_command(&line) {
            Ok(Some(Command::Exit)) => break,
            Ok(Some(command)) => {
                if let Err(error) = execute(command) {
                    eprintln!("nex: {error}");
                }
            }
            Ok(None) => {}
            Err(error) => eprintln!("nex: {error}"),
        }
    }
}

fn execute_line(line: &str) -> Result<(), String> {
    if let Some(command) = parse_command(line)? {
        if command == Command::Exit {
            return Ok(());
        }
        execute(command)?;
    }
    Ok(())
}

fn execute(command: Command) -> Result<(), String> {
    match command {
        Command::Help => print_help(),
        Command::Version => println!("NEXORA {VERSION}"),
        Command::Clear => print!("\x1B[2J\x1B[H"),
        Command::Exit => {}
        Command::Echo(parts) => println!("{}", parts.join(" ")),
        Command::Pwd => {
            let path = env::current_dir().map_err(|error| error.to_string())?;
            println!("{}", path.display());
        }
        Command::List(path) => {
            let directory = path.unwrap_or_else(|| env::current_dir().unwrap_or_default());
            let mut entries = fs::read_dir(&directory)
                .map_err(|error| format!("cannot read '{}': {error}", directory.display()))?
                .map(|entry| entry.map_err(|error| error.to_string()))
                .collect::<Result<Vec<_>, _>>()?;

            entries.sort_by_key(|entry| entry.file_name().to_string_lossy().to_lowercase());

            for entry in entries {
                let kind = if entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
                    " [dir]"
                } else {
                    ""
                };
                println!("{}{kind}", entry.file_name().to_string_lossy());
            }
        }
        Command::ChangeDirectory(path) => {
            let target = match path {
                Some(path) => path,
                None => env::var_os("HOME")
                    .or_else(|| env::var_os("USERPROFILE"))
                    .map(std::path::PathBuf::from)
                    .ok_or_else(|| "home directory is not available".to_string())?,
            };
            env::set_current_dir(&target)
                .map_err(|error| format!("cannot change to '{}': {error}", target.display()))?;
        }
        Command::External { program, args } => {
            let status = ProcessCommand::new(&program)
                .args(&args)
                .status()
                .map_err(|error| format!("could not launch '{program}': {error}"))?;

            if !status.success() {
                eprintln!("nex: program '{program}' exited with {status}");
            }
        }
    }

    Ok(())
}

fn print_help() {
    println!("NEXORA {VERSION} — Your Terminal. Your Universe.\n");
    println!("Built-in commands:");
    println!("  help                 Show this help");
    println!("  version              Show version information");
    println!("  clear                Clear the terminal");
    println!("  echo <text>          Print text");
    println!("  pwd                  Show the current directory");
    println!("  ls [path]            List directory contents");
    println!("  cd [path]            Change the current directory");
    println!("  exit                 Close NEXORA");
    println!();
    println!("Other commands are launched as external programs.");
    println!("Tip: quote arguments that contain spaces.");
}

#[cfg(test)]
mod tests {
    use super::execute_line;

    #[test]
    fn executes_echo_without_error() {
        assert!(execute_line("echo NEXORA").is_ok());
    }

    #[test]
    fn reports_unknown_program() {
        assert!(execute_line("nexora-program-that-does-not-exist").is_err());
    }
}
