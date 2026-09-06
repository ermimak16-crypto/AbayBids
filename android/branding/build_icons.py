#!/usr/bin/env python3
"""Build all icon sizes for Android + Windows from the 1024px Abay Technical master logo."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
MASTER = os.path.join(ROOT, "abay-master-1024.png")
WIN_BUILD = "/home/z/my-project/abay-bids-build"
PUBLIC_APP = "/home/z/my-project/public/abay-bids-app"
STAGE_APP = os.path.join(WIN_BUILD, "stage", "Abay Bids", "resources", "app")
ANDROID_RES = "/home/z/my-project/abay-bids-android/app/src/main/res"

# ---------------------------------------------------------------- Android
def android_legacy_pngs():
    """ic_launcher.png + ic_launcher_round.png at every density.
    These are full-bleed master images (with the green background)."""
    densities = {  # dp=48  -> px per density
        "mdpi":    48,
        "hdpi":    72,
        "xhdpi":   96,
        "xxhdpi":  144,
        "xxxhdpi": 192,
    }
    master = Image.open(MASTER).convert("RGBA")
    for d, px in densities.items():
        out_dir = os.path.join(ANDROID_RES, f"mipmap-{d}")
        os.makedirs(out_dir, exist_ok=True)
        sized = master.resize((px, px), Image.LANCZOS)
        sized.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
        # Round icon: just use the same image; Android masks it to circle automatically.
        sized.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
        print(f"  android mipmap-{d}: ic_launcher.png + ic_launcher_round.png ({px}x{px})")

def android_foreground_pngs():
    """Adaptive icon foreground as PNG. The foreground must be 108dp canvas with
    the visible content inside the inner 66% safe zone (center 72dp). The outer
    18% margin on each side must be transparent so the system mask (circle /
    squircle / rounded-square) doesn't clip the logo.
    At xxxhdpi (4x), 108dp = 432px and safe zone = 288px."""
    master = Image.open(MASTER).convert("RGBA")
    densities = {
        "mdpi":    (108, 72),
        "hdpi":    (162, 108),
        "xhdpi":   (216, 144),
        "xxhdpi":  (324, 216),
        "xxxhdpi": (432, 288),
    }
    for d, (canvas_px, safe_px) in densities.items():
        out_dir = os.path.join(ANDROID_RES, f"mipmap-{d}")
        os.makedirs(out_dir, exist_ok=True)
        canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        # The master already has the green background filling the whole square,
        # which we DON'T want for the foreground (the green comes from
        # ic_launcher_background). So composite the master's alpha to extract
        # only the white "A"+document shapes and drop the green.
        #
        # Simpler approach: use the master as-is but treat the green as
        # transparent. Easiest reliable method: chroma-key out near-emerald
        # pixels. Emerald brand color is #0E9F6E = (14,159,110).
        fg = master.resize((safe_px, safe_px), Image.LANCZOS)
        # Make the green pixels transparent
        pixels = fg.load()
        for y in range(fg.height):
            for x in range(fg.width):
                r, g, b, a = pixels[x, y]
                # Treat green-dominant pixels (where the master bg is) as transparent
                if g > 80 and g > r + 20 and g > b + 20 and r < 80:
                    pixels[x, y] = (0, 0, 0, 0)
        offset = (canvas_px - safe_px) // 2
        canvas.paste(fg, (offset, offset), fg)
        canvas.save(os.path.join(out_dir, "ic_launcher_foreground.png"), "PNG")
        print(f"  android mipmap-{d}: ic_launcher_foreground.png ({canvas_px}x{canvas_px}, safe {safe_px})")

def android_playstore_icon():
    """512x512 PNG for the Play Store listing."""
    master = Image.open(MASTER).convert("RGBA")
    sized = master.resize((512, 512), Image.LANCZOS)
    sized.save(os.path.join(ANDROID_RES, "ic_launcher-playstore.png"), "PNG")
    print("  android playstore: ic_launcher-playstore.png (512x512)")

def android_update_adaptive_xml():
    """Replace the foreground vector XML with a bitmap reference so the actual
    generated Abay Technical logo is what shows up on the launcher."""
    fg_xml_path = os.path.join(ANDROID_RES, "mipmap-anydpi-v26", "ic_launcher.xml")
    fg_round_xml_path = os.path.join(ANDROID_RES, "mipmap-anydpi-v26", "ic_launcher_round.xml")
    # The adaptive-icon XMLs reference @drawable/ic_launcher_foreground. By
    # placing ic_launcher_foreground.png in mipmap-*/ folders, the AAPT resource
    # picker will prefer the PNG over the vector drawable of the same name
    # (because density-qualified resources override the generic drawable). We
    # also delete the old vector so there's no ambiguity.
    vector_path = os.path.join(ANDROID_RES, "drawable", "ic_launcher_foreground.xml")
    if os.path.exists(vector_path):
        os.remove(vector_path)
        print("  removed drawable/ic_launcher_foreground.xml (replaced by PNG mipmaps)")
    # Verify the adaptive XMLs still reference @drawable/ic_launcher_foreground —
    # they do, but we just moved it to mipmap-*. Android allows mipmap refs in
    # adaptive-icon foreground. Update the XML to reference @mipmap/ic_launcher_foreground.
    new_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Adaptive launcher icon (Android 8.0+). minSdk=26 so no PNG fallback needed.\n'
        '     Foreground is the actual rendered Abay Technical logo PNG (mipmap-anydpi-v26\n'
        '     would be too coarse — using density-qualified PNG mipmaps instead). -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    with open(fg_xml_path, "w") as f:
        f.write(new_xml)
    with open(fg_round_xml_path, "w") as f:
        f.write(new_xml)
    print("  updated mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml (foreground -> PNG mipmap)")

# ---------------------------------------------------------------- Windows
def make_ico(out_path, sizes=(16, 24, 32, 48, 64, 128, 256)):
    master = Image.open(MASTER).convert("RGBA")
    images = []
    for s in sizes:
        images.append(master.resize((s, s), Image.LANCZOS))
    # PIL saves multi-size ICO when given a list of sizes via the sizes kwarg
    # on a single image, OR by saving a list of images with append_images.
    base = images[0]
    base.save(out_path, format="ICO",
              sizes=[(s, s) for s in sizes],
              append_images=images[1:])
    print(f"  windows {out_path}: ICO with sizes {sizes}")

def copy_resized(target_png, size):
    master = Image.open(MASTER).convert("RGBA")
    master.resize((size, size), Image.LANCZOS).save(target_png, "PNG")
    print(f"  windows {target_png}: ({size}x{size})")

def main():
    print("=== Android icons ===")
    android_legacy_pngs()
    android_foreground_pngs()
    android_playstore_icon()
    android_update_adaptive_xml()

    print("\n=== Windows icons ===")
    # ICO used by NSIS installer (MUI_ICON) + Add/Remove Programs DisplayIcon
    make_ico(os.path.join(WIN_BUILD, "abay-bids.ico"))
    # 512 PNG used as Electron BrowserWindow icon
    copy_resized(os.path.join(WIN_BUILD, "app", "icon-512.png"), 512)
    # 192 PNG used as PWA apple-touch-icon / favicon
    copy_resized(os.path.join(WIN_BUILD, "app", "icon-192.png"), 192)
    # 48 PNG used by NSIS UI graphics
    copy_resized(os.path.join(WIN_BUILD, "abay-bids-48.png"), 48)

    # Also push the master into branding/ for both builds as a fallback
    copy_resized(os.path.join(WIN_BUILD, "abay-bids-256.png"), 256)

    print("\n=== Replicating to stage + public preview ===")
    for src, dsts in [
        (os.path.join(WIN_BUILD, "app", "icon-512.png"),
            [os.path.join(STAGE_APP, "icon-512.png"),
             os.path.join(PUBLIC_APP, "icon-512.png")]),
        (os.path.join(WIN_BUILD, "app", "icon-192.png"),
            [os.path.join(STAGE_APP, "icon-192.png"),
             os.path.join(PUBLIC_APP, "icon-192.png")]),
        (os.path.join(WIN_BUILD, "abay-bids.ico"),
            [os.path.join(STAGE_APP, "abay-bids.ico")]),
        (os.path.join(WIN_BUILD, "abay-bids-48.png"),
            [os.path.join(STAGE_APP, "abay-bids-48.png"),
             os.path.join(PUBLIC_APP, "abay-bids-48.png")]),
    ]:
        for dst in dsts:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(src, "rb") as fr, open(dst, "wb") as fw:
                fw.write(fr.read())
            print(f"  copied {src} -> {dst}")

    # Drop a 192 favicon into public app too for the live preview
    copy_resized(os.path.join(PUBLIC_APP, "favicon-192.png"), 192)
    print("\nDone.")

if __name__ == "__main__":
    main()
