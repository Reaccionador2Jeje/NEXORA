use std::collections::BTreeSet;
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, Write};
use std::path::PathBuf;
use std::process::{self, Command as ProcessCommand};

use nexora_core::{parse_command, Command, VERSION};

const HISTORY_LIMIT: usize = 500;

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
    println!("Persistent history: enabled. Type 'help' for commands.\n");
    interactive_loop();
}

fn interactive_loop() {
    let stdin = io::stdin();
    let mut line = String::new();
    let mut history = load_history();

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

        let entered = line.trim();
        if entered.is_empty() {
            continue;
        }

        // !N recalls the Nth command shown by the history command.
        let command_line = if let Some(index) = entered.strip_prefix('!') {
            match index.parse::<usize>() {
                Ok(number) if number > 0 && number <= history.len() => {
                    let recalled = history[number - 1].clone();
                    println!("{recalled}");
                    recalled
                }
                _ => {
                    eprintln!("nex: history entry not found: {entered}");
                    continue;
                }
            }
        } else {
            entered.to_string()
        };

        if history.last() != Some(&command_line) {
            history.push(command_line.clone());
            if history.len() > HISTORY_LIMIT {
                history.remove(0);
            }
            if let Err(error) = append_history(&command_line) {
                eprintln!("nex: could not save history: {error}");
            }
        }

        match parse_command(&command_line) {
            Ok(Some(Command::Exit)) => break,
            Ok(Some(command)) => {
                if let Err(error) = execute(command, &history) {
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
        execute(command, &[])?;
    }
    Ok(())
}

fn execute(command: Command, history: &[String]) -> Result<(), String> {
    match command {
        Command::Help => print_help(),
        Command::Version => println!("NEXORA {VERSION}"),
        Command::Clear => print!("\x1B[2J\x1B[H"),
        Command::Exit => {}
        Command::History => {
            if history.is_empty() {
                println!("No commands in this session yet.");
            } else {
                for (index, entry) in history.iter().enumerate() {
                    println!("{:>4}  {}", index + 1, entry);
                }
            }
        }
        Command::Complete(prefix) => print_completions(&prefix),
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
                    .map(PathBuf::from)
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

fn print_completions(prefix: &str) {
    let mut candidates = BTreeSet::new();
    let builtins = [
        "help", "version", "clear", "exit", "history", "complete",
        "echo", "pwd", "ls", "cd",
    ];

    for command in builtins {
        if command.starts_with(prefix) {
            candidates.insert(command.to_string());
        }
    }

    if let Some(paths) = env::var_os("PATH") {
        for directory in env::split_paths(&paths) {
            let entries = match fs::read_dir(directory) {
                Ok(entries) => entries,
                Err(_) => continue,
            };

            for entry in entries.flatten() {
                let path = entry.path();
                if !path.is_file() {
                    continue;
                }

                let filename = entry.file_name().to_string_lossy().to_string();
                let stem = path.file_stem().unwrap_or_default().to_string_lossy();

                if filename.starts_with(prefix) || stem.starts_with(prefix) {
                    candidates.insert(filename);
                }
            }
        }
    }

    if candidates.is_empty() {
        println!("No commands found for '{prefix}'.");
    } else {
        for candidate in candidates {
            println!("{candidate}");
        }
    }
}

fn history_file() -> Option<PathBuf> {
    #[cfg(windows)]
    let base = env::var_os("APPDATA").map(PathBuf::from);

    #[cfg(not(windows))]
    let base = env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| env::var_os("HOME").map(|home| PathBuf::from(home).join(".local/share")));

    base.map(|directory| directory.join("nexora").join("history.txt"))
}

fn load_history() -> Vec<String> {
    let Some(path) = history_file() else {
        return Vec::new();
    };

    let Ok(file) = File::open(path) else {
        return Vec::new();
    };

    let mut entries: Vec<String> = io::BufReader::new(file)
        .lines()
        .map_while(Result::ok)
        .filter(|line| !line.trim().is_empty())
        .collect();

    if entries.len() > HISTORY_LIMIT {
        entries.drain(..entries.len() - HISTORY_LIMIT);
    }
    entries
}

fn append_history(entry: &str) -> io::Result<()> {
    let Some(path) = history_file() else {
        return Ok(());
    };

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }

    let mut file = OpenOptions::new().create(true).append(true).open(path)?;
    writeln!(file, "{entry}")
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
    println!("  history              Show saved command history");
    println!("  !N                   Run history entry number N");
    println!("  complete [prefix]    Suggest built-in and PATH commands");
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
