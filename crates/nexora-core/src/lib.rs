//! Shared command model and parser for NEXORA.

use std::path::PathBuf;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Help,
    Version,
    Clear,
    Exit,
    Echo(Vec<String>),
    Pwd,
    List(Option<PathBuf>),
    ChangeDirectory(Option<PathBuf>),
    External {
        program: String,
        args: Vec<String>,
    },
}

/// Parse one prompt line into a command.
///
/// Supports single- and double-quoted arguments and backslash escaping.
/// Shell operators are ordinary argument characters; this parser never
/// evaluates a command line as shell code.
pub fn parse_command(input: &str) -> Result<Option<Command>, String> {
    let args = tokenize(input)?;
    if args.is_empty() {
        return Ok(None);
    }

    let name = args[0].to_ascii_lowercase();
    let rest = &args[1..];

    let command = match name.as_str() {
        "help" | "--help" | "-h" => Command::Help,
        "version" | "--version" | "-v" => Command::Version,
        "clear" | "cls" => Command::Clear,
        "exit" | "quit" => Command::Exit,
        "echo" => Command::Echo(rest.to_vec()),
        "pwd" => Command::Pwd,
        "ls" | "dir" => {
            if rest.len() > 1 {
                return Err("usage: ls [path]".to_string());
            }
            Command::List(rest.first().map(PathBuf::from))
        }
        "cd" => {
            if rest.len() > 1 {
                return Err("usage: cd [path]".to_string());
            }
            Command::ChangeDirectory(rest.first().map(PathBuf::from))
        }
        _ => Command::External {
            program: args[0].clone(),
            args: rest.to_vec(),
        },
    };

    Ok(Some(command))
}

fn tokenize(input: &str) -> Result<Vec<String>, String> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    let mut escaped = false;
    let mut token_started = false;

    for ch in input.chars() {
        if escaped {
            current.push(ch);
            escaped = false;
            token_started = true;
            continue;
        }

        if ch == '\\' {
            escaped = true;
            token_started = true;
            continue;
        }

        match quote {
            Some(delimiter) if ch == delimiter => quote = None,
            Some(_) => current.push(ch),
            None if ch == '\'' || ch == '"' => {
                quote = Some(ch);
                token_started = true;
            }
            None if ch.is_whitespace() => {
                if token_started {
                    result.push(std::mem::take(&mut current));
                    token_started = false;
                }
            }
            None => {
                current.push(ch);
                token_started = true;
            }
        }
    }

    if escaped {
        current.push('\\');
    }
    if quote.is_some() {
        return Err("unclosed quote".to_string());
    }
    if token_started {
        result.push(current);
    }

    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::{parse_command, Command};

    #[test]
    fn parses_builtin_command() {
        assert_eq!(parse_command("pwd").unwrap(), Some(Command::Pwd));
    }

    #[test]
    fn parses_quoted_echo_argument() {
        assert_eq!(
            parse_command("echo \"hello world\"").unwrap(),
            Some(Command::Echo(vec!["hello world".to_string()]))
        );
    }

    #[test]
    fn preserves_empty_quoted_argument() {
        assert_eq!(
            parse_command("echo \"\"").unwrap(),
            Some(Command::Echo(vec![String::new()]))
        );
    }

    #[test]
    fn parses_external_program_and_arguments() {
        assert_eq!(
            parse_command("git status").unwrap(),
            Some(Command::External {
                program: "git".to_string(),
                args: vec!["status".to_string()],
            })
        );
    }

    #[test]
    fn rejects_unclosed_quotes() {
        assert!(parse_command("echo \"unfinished").is_err());
    }

    #[test]
    fn empty_line_has_no_command() {
        assert_eq!(parse_command("   ").unwrap(), None);
    }
}
