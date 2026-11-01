"""Locates the Android SDK and its build tools the way the Gradle build does, so the Python tools
don't hard-code a user's path or a build-tools version."""
import os
from pathlib import Path


def sdk_dir(root: Path) -> Path | None:
    """local.properties' sdk.dir under the repo root, else ANDROID_HOME, else ANDROID_SDK_ROOT."""
    props = root / "local.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() == "sdk.dir":
                # java.util.Properties escaping, as Android Studio writes it on Windows.
                return Path(value.strip().replace("\\:", ":").replace("\\\\", "\\"))
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            return Path(os.environ[env])
    return None


def _version_key(name: str):
    try:
        return tuple(int(p) for p in name.split("."))
    except ValueError:
        return None


def find_build_tool(root: Path, env_var: str, names: tuple[str, ...]) -> Path:
    """$env_var if set, else the first of names in the SDK's newest build-tools that has one."""
    if os.environ.get(env_var):
        return Path(os.environ[env_var])
    sdk = sdk_dir(root)
    if sdk is not None:
        versions = [(key, d) for d in (sdk / "build-tools").glob("*")
                    if d.is_dir() and (key := _version_key(d.name)) is not None]
        for _, d in sorted(versions, reverse=True):
            for exe in names:
                if (d / exe).is_file():
                    return d / exe
    raise FileNotFoundError(
        f"{names[-1]} not found: set {env_var}, or the SDK via local.properties sdk.dir / "
        "ANDROID_HOME, with build-tools installed")


def find_aapt2(root: Path) -> Path:
    """$AAPT2 if set, else aapt2 from the SDK's newest build-tools that has one."""
    return find_build_tool(root, "AAPT2", ("aapt2.exe", "aapt2"))
