//! The diagnostic log, set up once for both binaries.
//!
//! # Logging must never be able to fail
//!
//! Both binaries tee every line to stderr and to [`paths::log_file_path`]. stderr is not something
//! this process controls. The app inherited it from whichever shim launched it, which is a pipe to
//! whichever Claude Code session started that shim, and when that session closes, every write to it
//! fails with a broken pipe.
//!
//! tracing-subscriber's default answer to a failed write is `eprintln!`, and `eprintln!` panics when
//! stderr fails. Every `info!` became a panic in whatever task called it, after the file had
//! already received the line, so nothing looked wrong in the log. The link listener registers an
//! instance and then logs `instance linked`, so every game that connected was registered, panicked
//! out before its bootstrap was spawned, and stayed listed forever with no tools. The panic also
//! skipped the clearing of tracing's per-thread format buffer, so each later line on that thread
//! wrote every earlier failed line out again, which is where the duplicated, out-of-order entries
//! came from.
//!
//! So internal errors are not reported: a line that reaches either sink is enough, and a line that
//! reaches neither is not worth a panic.

use tracing_subscriber::EnvFilter;
use tracing_subscriber::fmt::MakeWriter;
use tracing_subscriber::fmt::writer::MakeWriterExt;

use crate::paths;

/// Installs the global subscriber and the panic hook. Call once, first thing in `main`.
pub fn init() {
    match paths::open_log_file() {
        Ok(file) => tracing::subscriber::set_global_default(subscriber(std::io::stderr.and(file)))
            .expect("the subscriber is installed once"),
        // A log that cannot be opened is not a reason to refuse to start.
        Err(error) => {
            tracing::subscriber::set_global_default(subscriber(std::io::stderr))
                .expect("the subscriber is installed once");
            tracing::warn!(%error, "could not open the diagnostic log; logging to stderr only");
        }
    }
    install_panic_hook();
}

/// The subscriber both binaries log through, over whatever writer they choose.
///
/// ANSI is off because half of this goes to a file a person is expected to open.
pub fn subscriber<W>(writer: W) -> impl tracing::Subscriber + Send + Sync
where
    W: for<'writer> MakeWriter<'writer> + Send + Sync + 'static,
{
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_env("MCMCP_LOG").unwrap_or_else(|_| EnvFilter::new("info")))
        .with_ansi(false)
        .log_internal_errors(false)
        .with_writer(writer)
        .finish()
}

/// Sends panics to the log as well as to stderr.
///
/// A spawned task that panics takes nothing else down: tokio catches it, and the only report is the
/// default hook's message on stderr. A desktop app has no stderr anyone reads, so a panicking
/// connection task used to leave no trace at all.
fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current();
        let thread = thread.name().unwrap_or("unnamed");
        tracing::error!(thread, panic = %info, "a thread panicked");
        previous(info);
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Set in the child process, naming the file it should log to.
    const CHILD_LOG: &str = "MCMCP_LOGGING_TEST_CHILD_LOG";

    #[test]
    fn a_dead_stderr_neither_panics_the_caller_nor_repeats_lines_in_the_file() {
        // It has to be a real stderr with nobody reading it, in a separate process. The test
        // harness captures `eprintln!`, so a broken writer inside this process never reaches the
        // failing write the bug depends on and the test passes against the old setting too.
        if let Ok(path) = std::env::var(CHILD_LOG) {
            // Give the parent time to close its end, so every write below meets a broken pipe.
            std::thread::sleep(std::time::Duration::from_millis(300));
            let file = move || {
                std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&path)
                    .expect("opening the test log")
            };
            tracing::subscriber::with_default(subscriber(std::io::stderr.and(file)), || {
                tracing::info!("instance linked");
                tracing::info!("instance ready");
            });
            return;
        }

        let path = std::env::temp_dir().join(format!("mcmcp-logging-test-{}.log", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let mut child = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "logging::tests::a_dead_stderr_neither_panics_the_caller_nor_repeats_lines_in_the_file",
                "--nocapture",
            ])
            .env(CHILD_LOG, &path)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .expect("re-running this test as a child");
        drop(child.stderr.take());
        let status = child.wait().unwrap();

        let text = std::fs::read_to_string(&path).unwrap_or_default();
        let _ = std::fs::remove_file(&path);
        assert!(
            status.success(),
            "logging panicked with stderr gone:
{text}"
        );
        assert_eq!(text.matches("instance linked").count(), 1, "{text}");
        assert_eq!(text.matches("instance ready").count(), 1, "{text}");
    }
}
