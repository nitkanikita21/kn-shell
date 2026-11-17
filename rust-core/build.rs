fn main() {
    println!("cargo:rerun-if-changed=src/kn_box_layout.c");

    let mut build = cc::Build::new();
    build
        .file("src/kn_box_layout.c")
        .flag_if_supported("-std=c11")
        .flag_if_supported("-Wno-unused-parameter");

    // GTK include paths come from pkg-config (same package the cinterop uses).
    if let Ok(output) = std::process::Command::new("pkg-config")
        .args(["--cflags", "gtk4"])
        .output()
    {
        for flag in String::from_utf8_lossy(&output.stdout).split_whitespace() {
            if let Some(include) = flag.strip_prefix("-I") {
                build.include(include);
            }
        }
    }

    build.compile("kn_box_layout");
}
