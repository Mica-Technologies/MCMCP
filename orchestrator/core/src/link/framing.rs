//! Newline-delimited JSON framing, matching `LinkFraming.java`.
//!
//! Same reasoning as the Java side, arrived at for the same reason: the limit is counted in **bytes
//! as they arrive**, not in characters and not after a line is complete. `read_until` has no bound,
//! so a peer that opens a connection and never sends a newline would grow a buffer until the process
//! died; and UTF-8 runs to four bytes per character, so a character-counted cap permits four times
//! the memory it appears to.
//!
//! Line endings are tolerated in both forms and blank lines are skipped, which costs nothing and
//! keeps the link drivable by hand from a terminal when something has gone wrong enough to need it.

use anyhow::{Context, Result, bail};
use serde_json::Value;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};

/// Largest single frame accepted, in bytes. Matches `LinkFraming.MAX_FRAME_BYTES`.
pub const MAX_FRAME_BYTES: usize = 4 * 1024 * 1024;

/// Reads one frame's raw bytes.
///
/// Returns `Ok(None)` at end of stream — including for a partial final line, because handing back a
/// truncated frame would present it as a complete one and it would parse or not by luck.
pub async fn read_line<R>(reader: &mut R, max_bytes: usize) -> Result<Option<String>>
where
    R: AsyncBufRead + Unpin,
{
    let mut buffer: Vec<u8> = Vec::with_capacity(256);

    loop {
        // fill_buf/consume rather than read_until: it is the only shape that lets the byte count be
        // checked *before* the bytes are kept.
        let available = reader.fill_buf().await.context("reading from the link")?;
        if available.is_empty() {
            return Ok(None);
        }

        match available.iter().position(|&byte| byte == b'\n') {
            Some(newline) => {
                if buffer.len() + newline > max_bytes {
                    bail!("link frame exceeds {max_bytes} bytes");
                }
                buffer.extend_from_slice(&available[..newline]);
                reader.consume(newline + 1);

                if buffer.last() == Some(&b'\r') {
                    buffer.pop();
                }
                let line = String::from_utf8(std::mem::take(&mut buffer))
                    .context("link frame is not valid UTF-8")?;
                if line.trim().is_empty() {
                    // Not a frame. Keep reading rather than handing back something every caller
                    // would have to special-case.
                    continue;
                }
                return Ok(Some(line));
            }
            None => {
                if buffer.len() + available.len() > max_bytes {
                    bail!("link frame exceeds {max_bytes} bytes without a newline");
                }
                let taken = available.len();
                buffer.extend_from_slice(available);
                reader.consume(taken);
            }
        }
    }
}

/// Reads one frame and parses it as a JSON object.
pub async fn read_frame<R>(reader: &mut R) -> Result<Option<Value>>
where
    R: AsyncBufRead + Unpin,
{
    let Some(line) = read_line(reader, MAX_FRAME_BYTES).await? else {
        return Ok(None);
    };
    let value: Value = serde_json::from_str(&line).context("link frame is not valid JSON")?;
    if !value.is_object() {
        bail!("link frame must be a JSON object");
    }
    Ok(Some(value))
}

/// Writes one frame and flushes.
///
/// Flushing every frame rather than relying on the buffer is not a performance oversight: a request
/// sitting in a write buffer waiting for company is a tool call that never arrives, and the far side
/// has no way to tell that from a slow one.
pub async fn write_frame<W>(writer: &mut W, frame: &Value) -> Result<()>
where
    W: AsyncWrite + Unpin,
{
    let mut bytes = serde_json::to_vec(frame).context("serialising a link frame")?;
    if bytes.len() + 1 > MAX_FRAME_BYTES {
        bail!(
            "refusing to send a {} byte link frame; the limit is {MAX_FRAME_BYTES}",
            bytes.len()
        );
    }
    bytes.push(b'\n');
    writer.write_all(&bytes).await.context("writing to the link")?;
    writer.flush().await.context("flushing the link")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::io::BufReader;

    async fn read_all(input: &str) -> Vec<Value> {
        let mut reader = BufReader::new(input.as_bytes());
        let mut frames = Vec::new();
        while let Some(frame) = read_frame(&mut reader).await.expect("frames should read") {
            frames.push(frame);
        }
        frames
    }

    #[tokio::test]
    async fn reads_one_frame_per_line() {
        let frames = read_all("{\"a\":1}\n{\"b\":2}\n").await;

        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0]["a"], 1);
        assert_eq!(frames[1]["b"], 2);
    }

    #[tokio::test]
    async fn tolerates_carriage_returns_and_skips_blank_lines() {
        let frames = read_all("{\"a\":1}\r\n\n   \n{\"b\":2}\r\n").await;

        assert_eq!(frames.len(), 2);
    }

    #[tokio::test]
    async fn treats_a_partial_final_line_as_end_of_stream() {
        // A peer that vanished mid-frame. Returning the fragment would present it as complete.
        let frames = read_all("{\"a\":1}\n{\"b\":").await;

        assert_eq!(frames.len(), 1);
    }

    #[tokio::test]
    async fn preserves_multi_byte_characters() {
        let frames = read_all("{\"name\":\"módB — tëst\"}\n").await;

        assert_eq!(frames[0]["name"], "módB — tëst");
    }

    #[tokio::test]
    async fn refuses_a_line_that_exceeds_the_byte_limit() {
        let oversized = format!("{{\"a\":\"{}\"", "x".repeat(500));
        let mut reader = BufReader::new(oversized.as_bytes());

        let error = read_line(&mut reader, 64)
            .await
            .expect_err("an oversized line must be refused");
        assert!(error.to_string().contains("exceeds"));
    }

    #[tokio::test]
    async fn counts_the_limit_in_bytes_rather_than_characters() {
        // Forty two-byte characters is eighty bytes. A character-counted cap would let this through
        // and permit up to four times the memory the limit appears to allow.
        let multi_byte = "é".repeat(40);
        let mut reader = BufReader::new(multi_byte.as_bytes());

        assert!(read_line(&mut reader, 64).await.is_err());
    }

    #[tokio::test]
    async fn rejects_a_frame_that_is_not_a_json_object() {
        let mut reader = BufReader::new(&b"[1,2,3]\n"[..]);
        assert!(read_frame(&mut reader).await.is_err());

        let mut reader = BufReader::new(&b"\"hello\"\n"[..]);
        assert!(read_frame(&mut reader).await.is_err());
    }

    #[tokio::test]
    async fn rejects_a_frame_that_is_not_valid_json() {
        let mut reader = BufReader::new(&b"{not json}\n"[..]);
        assert!(read_frame(&mut reader).await.is_err());
    }

    #[tokio::test]
    async fn round_trips_a_frame_through_write_and_read() {
        let frame = json!({"type": "hello", "instanceName": "módB — tëst"});
        let mut written: Vec<u8> = Vec::new();

        write_frame(&mut written, &frame)
            .await
            .expect("frame should write");

        assert_eq!(written.last(), Some(&b'\n'));
        let mut reader = BufReader::new(&written[..]);
        let read = read_frame(&mut reader).await.unwrap().unwrap();
        assert_eq!(read, frame);
    }

    #[tokio::test]
    async fn refuses_to_send_a_frame_larger_than_it_would_accept() {
        // Sending something the far end must reject produces a dropped link with its cause a whole
        // process away from the code responsible.
        let frame = json!({ "payload": "x".repeat(MAX_FRAME_BYTES) });
        let mut written: Vec<u8> = Vec::new();

        assert!(write_frame(&mut written, &frame).await.is_err());
    }
}
