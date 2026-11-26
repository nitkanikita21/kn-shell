//! Runs the headless compositor alone, for poking at it with any Wayland client:
//! `cargo run --example headless -- <socket-name>`.

fn main() {
    let name = std::env::args().nth(1).unwrap_or_else(|| "atlas-headless".to_owned());
    let compositor = atlas_mcp::compositor::Compositor::start(&name).expect("cannot start the compositor");
    println!("listening on WAYLAND_DISPLAY={}", compositor.socket_name());
    loop {
        std::thread::park();
    }
}
