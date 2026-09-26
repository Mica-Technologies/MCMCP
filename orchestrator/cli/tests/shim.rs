//! The shim as a process, driven the way an MCP client drives it, against a stand-in for the app.

use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::time::{Duration, Instant};

/// A running shim, attached to a fake app that answers the attach and then does nothing.
struct Attached {
    shim: Child,
    link: TcpStream,
    state: std::path::PathBuf,
}

fn attach(name: &str) -> Attached {
    let state = std::env::temp_dir().join(format!("mcmcp-shim-{name}-{}", std::process::id()));
    let directory = state.join("mcmcp-orchestrator");
    std::fs::create_dir_all(&directory).unwrap();
    std::fs::write(directory.join("mcp-token"), "test-token").unwrap();

    let app = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = app.local_addr().unwrap().port().to_string();

    let shim = Command::new(env!("CARGO_BIN_EXE_mcmcp-orchestrator"))
        .args([
            "--state-dir",
            state.to_str().unwrap(),
            "shim",
            "--mcp-port",
            &port,
            "--no-launch",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();

    let (mut link, _) = app.accept().unwrap();
    let mut frame = String::new();
    BufReader::new(link.try_clone().unwrap())
        .read_line(&mut frame)
        .unwrap();
    assert!(
        frame.contains("\"attach\""),
        "expected an attach frame, got {frame:?}"
    );
    link.write_all(b"{\"type\":\"attached\"}\n").unwrap();
    std::thread::sleep(Duration::from_millis(300));

    Attached { shim, link, state }
}

/// Waits up to ten seconds for the shim to exit, killing it if it does not.
fn exit_of(mut attached: Attached) -> Option<ExitStatus> {
    let deadline = Instant::now() + Duration::from_secs(10);
    let status = loop {
        if let Some(status) = attached.shim.try_wait().unwrap() {
            break Some(status);
        }
        if Instant::now() > deadline {
            let _ = attached.shim.kill();
            break None;
        }
        std::thread::sleep(Duration::from_millis(50));
    };
    let _ = std::fs::remove_dir_all(&attached.state);
    status
}

#[test]
fn the_shim_exits_when_the_app_goes_away_even_while_its_client_is_silent() {
    // The bug: when the app went away, the shim's relay ended but the process did not. Dropping the
    // runtime waited on the stdin pump's blocking read, and an MCP client between calls writes
    // nothing, so the shim stayed up until the next call, looking connected and holding the
    // installed binary locked against an upgrade.
    let mut attached = attach("app-gone");
    let _client = attached.shim.stdin.take(); // held open and never written, like an idle client
    let Attached { link, .. } = &attached;
    link.shutdown(std::net::Shutdown::Both).unwrap();

    assert!(
        exit_of(attached).is_some(),
        "the shim was still running 10s after the app closed its connection"
    );
}

#[test]
fn the_shim_exits_when_its_client_closes_stdin_even_while_the_app_stays() {
    // Closing stdin is how an MCP client says it is done. The shim used to wait for the app to close
    // its side too, which the app does only once every call still in flight has finished.
    let mut attached = attach("client-gone");
    drop(attached.shim.stdin.take());

    let status = exit_of(attached);
    assert!(
        status.is_some(),
        "the shim was still running 10s after its client closed stdin"
    );
    assert!(status.unwrap().success(), "a client leaving is not an error");
}
