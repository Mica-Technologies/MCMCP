//! Small copies of the screenshots a session took, for the flight recorder.
//!
//! A screenshot is the most telling thing in a session's history — what the agent actually saw — and
//! the one thing the event log could not keep: it is a file in the game's folder, or a base64 blob
//! that has gone by the time anyone looks. A 320-pixel copy is enough to recognise a frame in a
//! timeline and costs a few tens of kilobytes.
//!
//! Plain PNG in, plain PNG out, with a box filter between. Nothing here needs more than that, and the
//! `png` crate is already in the build.

use anyhow::{Context, Result, bail};
use std::path::{Path, PathBuf};

/// The long edge of a thumbnail, in pixels.
pub const THUMBNAIL_EDGE: u32 = 320;

/// The most thumbnails kept. The oldest go first once there are more.
pub const MAX_THUMBNAILS: usize = 1000;

/// A screenshot larger than this is not read: nothing a game writes is legitimately bigger, and a
/// path that pointed somewhere unexpected should not cost a gigabyte of memory.
const MAX_SOURCE_BYTES: u64 = 40 * 1024 * 1024;

/// Decodes a PNG, shrinks it so its long edge is at most `max_edge`, and encodes it again.
///
/// An image already small enough is re-encoded at its own size rather than enlarged.
pub fn downscale_png(bytes: &[u8], max_edge: u32) -> Result<Vec<u8>> {
    let mut decoder = png::Decoder::new(bytes);
    decoder.set_transformations(png::Transformations::normalize_to_color8());
    let mut reader = decoder.read_info().context("not a PNG")?;
    let mut buffer = vec![0; reader.output_buffer_size()];
    let frame = reader
        .next_frame(&mut buffer)
        .context("could not decode the PNG")?;
    let channels = match frame.color_type {
        png::ColorType::Rgba => 4,
        png::ColorType::Rgb => 3,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Grayscale => 1,
        other => bail!("unsupported colour type {other:?}"),
    };
    let (width, height) = (frame.width, frame.height);
    let pixels = &buffer[..frame.buffer_size()];

    let scale = (max_edge as f64 / width.max(height) as f64).min(1.0);
    let out_width = ((width as f64 * scale).round() as u32).max(1);
    let out_height = ((height as f64 * scale).round() as u32).max(1);
    let shrunk = box_filter(pixels, width, height, channels, out_width, out_height);

    let mut out = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut out, out_width, out_height);
        encoder.set_color(frame.color_type);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header()?;
        writer.write_image_data(&shrunk)?;
    }
    Ok(out)
}

/// Averages each output pixel over the source pixels it covers. Fine for shrinking, which is all
/// this is ever asked to do.
fn box_filter(
    pixels: &[u8],
    width: u32,
    height: u32,
    channels: usize,
    out_width: u32,
    out_height: u32,
) -> Vec<u8> {
    let mut out = vec![0u8; out_width as usize * out_height as usize * channels];
    for oy in 0..out_height {
        let y0 = (oy as u64 * height as u64 / out_height as u64) as u32;
        let y1 = (((oy + 1) as u64 * height as u64).div_ceil(out_height as u64) as u32).max(y0 + 1);
        for ox in 0..out_width {
            let x0 = (ox as u64 * width as u64 / out_width as u64) as u32;
            let x1 = (((ox + 1) as u64 * width as u64).div_ceil(out_width as u64) as u32).max(x0 + 1);
            let mut sums = [0u64; 4];
            let mut count = 0u64;
            for y in y0..y1.min(height) {
                let row = y as usize * width as usize * channels;
                for x in x0..x1.min(width) {
                    let at = row + x as usize * channels;
                    for (channel, sum) in sums.iter_mut().enumerate().take(channels) {
                        *sum += pixels[at + channel] as u64;
                    }
                    count += 1;
                }
            }
            let at = (oy as usize * out_width as usize + ox as usize) * channels;
            for channel in 0..channels {
                out[at + channel] = (sums[channel] / count.max(1)) as u8;
            }
        }
    }
    out
}

/// Reads a screenshot a game saved, refusing anything that is not a modest PNG file.
pub fn read_screenshot(path: &Path) -> Result<Vec<u8>> {
    if path
        .extension()
        .and_then(|extension| extension.to_str())
        .map(str::to_ascii_lowercase)
        != Some("png".into())
    {
        bail!("{} is not a PNG", path.display());
    }
    let size = std::fs::metadata(path)?.len();
    if size > MAX_SOURCE_BYTES {
        bail!("{} is {size} bytes, more than a screenshot", path.display());
    }
    Ok(std::fs::read(path)?)
}

/// Writes a thumbnail, then removes the oldest beyond [`MAX_THUMBNAILS`].
pub fn save(directory: &Path, name: &str, png: &[u8]) -> Result<PathBuf> {
    std::fs::create_dir_all(directory)?;
    let path = directory.join(name);
    std::fs::write(&path, png)?;
    prune(directory, MAX_THUMBNAILS);
    Ok(path)
}

/// Keeps the newest `keep` thumbnails. Names start with the millisecond they were taken, so name
/// order is age order and no file needs to be stat'ed.
fn prune(directory: &Path, keep: usize) {
    let Ok(entries) = std::fs::read_dir(directory) else {
        return;
    };
    let mut names: Vec<PathBuf> = entries
        .filter_map(|entry| entry.ok().map(|entry| entry.path()))
        .filter(|path| path.extension().and_then(|extension| extension.to_str()) == Some("png"))
        .collect();
    if names.len() <= keep {
        return;
    }
    names.sort();
    for old in &names[..names.len() - keep] {
        let _ = std::fs::remove_file(old);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png_of(width: u32, height: u32, color: png::ColorType, pixel: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut out, width, height);
            encoder.set_color(color);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().unwrap();
            let data: Vec<u8> = pixel
                .iter()
                .copied()
                .cycle()
                .take(width as usize * height as usize * pixel.len())
                .collect();
            writer.write_image_data(&data).unwrap();
        }
        out
    }

    fn size_of(png: &[u8]) -> (u32, u32) {
        let reader = png::Decoder::new(png).read_info().unwrap();
        (reader.info().width, reader.info().height)
    }

    #[test]
    fn a_screenshot_shrinks_to_the_long_edge_keeping_its_shape() {
        let frame = png_of(1920, 1080, png::ColorType::Rgba, &[10, 20, 30, 255]);
        let thumbnail = downscale_png(&frame, THUMBNAIL_EDGE).unwrap();
        assert_eq!(size_of(&thumbnail), (320, 180));
    }

    #[test]
    fn a_small_image_is_not_enlarged() {
        let frame = png_of(100, 50, png::ColorType::Rgb, &[1, 2, 3]);
        assert_eq!(
            size_of(&downscale_png(&frame, THUMBNAIL_EDGE).unwrap()),
            (100, 50)
        );
    }

    #[test]
    fn colours_survive_the_shrink() {
        let frame = png_of(640, 640, png::ColorType::Rgb, &[200, 100, 50]);
        let thumbnail = downscale_png(&frame, 32).unwrap();
        let mut reader = png::Decoder::new(thumbnail.as_slice()).read_info().unwrap();
        let mut buffer = vec![0; reader.output_buffer_size()];
        reader.next_frame(&mut buffer).unwrap();
        assert_eq!(&buffer[..3], &[200, 100, 50]);
    }

    #[test]
    fn something_that_is_not_a_png_is_refused() {
        assert!(downscale_png(b"not a png at all", THUMBNAIL_EDGE).is_err());
        assert!(read_screenshot(Path::new("C:/somewhere/notes.txt")).is_err());
    }

    #[test]
    fn only_the_newest_thumbnails_are_kept() {
        let directory = std::env::temp_dir().join(format!("mcmcp-thumbs-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).unwrap();
        for at in 100..110 {
            std::fs::write(directory.join(format!("{at}-a.png")), b"x").unwrap();
        }
        prune(&directory, 4);
        let mut left: Vec<String> = std::fs::read_dir(&directory)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        left.sort();
        assert_eq!(left, vec!["106-a.png", "107-a.png", "108-a.png", "109-a.png"]);
        let _ = std::fs::remove_dir_all(&directory);
    }
}
