"""Generates read-only aconfig Flags classes for the packages HyperGestures bundles.

AOSP 17 release builds are all read-only (build map §0.8), so each Flags.x() returns a constant.
Value precedence (lowest → highest): AOSP release-config chain, device aflags dump, project overrides.
A project override that contradicts the device value is reported (stderr WARNING and a Conflicts
section in the report), since device.txt can never override it.
"""
import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

_TOKEN = re.compile(r'\s+|#[^\n]*|"((?:[^"\\]|\\.)*)"|([{}:])|([A-Za-z0-9_.\-]+)')


@dataclass(frozen=True)
class FlagDecl:
    name: str
    namespace: str
    is_exported: bool
    is_fixed_read_only: bool


def _tokens(text: str):
    pos = 0
    while pos < len(text):
        m = _TOKEN.match(text, pos)
        if not m:
            raise ValueError(f"unexpected character at {pos}: {text[pos:pos + 20]!r}")
        pos = m.end()
        if m.group(1) is not None:
            yield ("str", bytes(m.group(1), "utf-8").decode("unicode_escape"))
        elif m.group(2):
            yield (m.group(2), m.group(2))
        elif m.group(3):
            yield ("id", m.group(3))


class _TokenStream:
    """Explicit-index cursor over a materialized token list.

    Deliberately NOT generator-based: an earlier version threaded a live
    generator through the recursive descent and used a "peek by calling
    next(), then re-wrap the peeked token with the remainder via a chained
    generator" trick to implement lookahead. That works token-by-token, but
    when a block had more than one string-valued field, the wrapper
    generator created for the lookahead was discarded as soon as the peeked
    token turned out to be a plain "}" (not another string). CPython
    refcounts objects, so that discarded generator is deallocated
    immediately, and per PEP 380 a `yield from` delegation propagates
    `close()` to whatever it is delegating to — cascading down through the
    whole chain of wrapper generators and closing the *original* token
    generator out from under the caller. The parser would then silently see
    end-of-input after the first multi-field block. This reproduces on the
    fixture `decl.aconfig` itself (its first flag has 2+ string fields
    before its closing brace), independent of any real AOSP file. A plain
    index into a list sidesteps the issue entirely: peeking never consumes
    or discards anything.
    """

    def __init__(self, tokens):
        self._tokens = tokens
        self._pos = 0

    def next(self):
        tok = self._tokens[self._pos]
        self._pos += 1
        return tok

    def peek(self):
        if self._pos >= len(self._tokens):
            return None
        return self._tokens[self._pos]


def _parse_block(ts: _TokenStream, top=False):
    """Returns a list of (key, value) where value is str or nested list."""
    out = []
    while True:
        tok = ts.peek()
        if tok is None:
            if top:
                return out
            raise ValueError("unterminated block")
        kind, val = tok
        if kind == "}":
            ts.next()
            if top:
                raise ValueError("unbalanced }")
            return out
        if kind != "id":
            raise ValueError(f"expected field name, got {val!r}")
        ts.next()
        key = val
        kind, val = ts.next()
        if kind == ":":
            kind, val = ts.next()
        if kind == "{":
            out.append((key, _parse_block(ts)))
            continue
        if kind == "str":
            parts = [val]
            # adjacent string literals concatenate
            nxt = ts.peek()
            while nxt is not None and nxt[0] == "str":
                parts.append(nxt[1])
                ts.next()
                nxt = ts.peek()
            out.append((key, "".join(parts)))
            continue
        out.append((key, val))


def _parse(text: str):
    return _parse_block(_TokenStream(list(_tokens(text))), top=True)


def _get(fields, key, default=None):
    for k, v in fields:
        if k == key:
            return v
    return default


def parse_declarations(text: str):
    fields = _parse(text)
    pkg = _get(fields, "package")
    flags = []
    for k, v in fields:
        if k == "flag":
            flags.append(FlagDecl(
                name=_get(v, "name"),
                namespace=_get(v, "namespace", ""),
                is_exported=_get(v, "is_exported") == "true",
                is_fixed_read_only=_get(v, "is_fixed_read_only") == "true",
            ))
    return pkg, flags


def parse_values(text: str):
    out = []
    for k, v in _parse(text):
        if k == "flag_value":
            out.append((_get(v, "package"), _get(v, "name"), _get(v, "state") == "ENABLED"))
    return out


def parse_kv(text: str):
    out = {}
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        key, value = (s.strip() for s in line.split("=", 1))
        pkg, name = key.rsplit(".", 1)
        out[(pkg, name)] = value.lower() == "true"
    return out


def camel(name: str) -> str:
    words = [w for w in name.split("_") if w]
    return words[0].lower() + "".join(w[:1].upper() + w[1:] for w in words[1:])


def resolve(decls, layers):
    result = {}
    for pkg, d in decls:
        key = (pkg, d.name)
        value, source = False, "default"
        for layer_name, layer in layers:
            if key in layer:
                value, source = layer[key], layer_name
        result[key] = (value, source)
    return result


def conflicts(device: dict, project: dict):
    """[(key, device_value, project_value)] for every project override that contradicts
    device.txt, sorted by key. The project override wins; these need a human review."""
    return [(k, device[k], project[k]) for k in sorted(project) if k in device and device[k] != project[k]]


def release_chain(configs_dir: Path, name: str):
    """The aconfig value sets a release config applies, lowest precedence first, from the
    `inherits:` lines of build/release's release_configs/*.textproto (Soong's order: each
    inherited config's own chain in `inherits` order, a config already applied is not applied
    again, then the config's own values last)."""
    memo = {}

    def chain(n, stack):
        if n in stack:
            raise ValueError("release config inheritance loop: " + " -> ".join(stack + (n,)))
        if n not in memo:
            fields = _parse((configs_dir / f"{n}.textproto").read_text(encoding="utf-8"))
            order = []
            for parent in (v for k, v in fields if k == "inherits"):
                order += [c for c in chain(parent, stack + (n,)) if c not in order]
            memo[n] = order + [n]
        return memo[n]

    return chain(name, ())


def render_flags_java(package: str, flags) -> str:
    lines = [
        "// GENERATED by tools/hgtools/aconfig_gen.py — do not edit. Values: flags/VALUES.md",
        f"package {package};",
        "",
        "/** @hide */",
        "public final class Flags {",
        "    private Flags() {}",
    ]
    for name, _ in flags:
        lines.append(f'    public static final String FLAG_{name.upper()} = "{package}.{name}";')
    lines.append("")
    for name, enabled in flags:
        lines.append(f"    public static boolean {camel(name)}() {{ return {'true' if enabled else 'false'}; }}")
    lines += ["}", ""]
    return "\n".join(lines)


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--decl", nargs="+", type=Path, required=True)
    ap.add_argument("--values-root", type=Path, required=True)
    ap.add_argument("--chain", type=Path, required=True,
                    help="AOSP release-config chain, lowest first; rewritten when --release is given")
    ap.add_argument("--release-configs", type=Path, help="build/release release_configs/ dir")
    ap.add_argument("--release", help="derive --chain from this release config's inherits")
    ap.add_argument("--device", type=Path, required=True)
    ap.add_argument("--overrides", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--report", type=Path, required=True)
    a = ap.parse_args(argv)
    if (a.release is None) != (a.release_configs is None):
        ap.error("--release and --release-configs go together")
    if a.release:
        chain = release_chain(a.release_configs, a.release)
        a.chain.write_text(
            f"# GENERATED by tools/hgtools/aconfig_gen.py from the {a.release} inherits in\n"
            f"# {a.release_configs.as_posix()} (Soong order, lowest precedence first) — do not edit.\n"
            + "".join(c + "\n" for c in chain), encoding="utf-8", newline="\n")

    decls, packages = [], {}
    for f in a.decl:
        pkg, flags = parse_declarations(f.read_text(encoding="utf-8"))
        packages.setdefault(pkg, [])
        for d in flags:
            decls.append((pkg, d))
            packages[pkg].append(d)

    layers = []
    for cfg in [c.strip() for c in a.chain.read_text().splitlines() if c.strip() and not c.startswith("#")]:
        layer = {}
        for pkg in packages:
            for vf in sorted((a.values_root / cfg / pkg).glob("*.textproto")):
                for p, n, v in parse_values(vf.read_text(encoding="utf-8")):
                    layer[(p, n)] = v
        layers.append((f"aosp:{cfg}", layer))
    device = parse_kv(a.device.read_text()) if a.device.exists() else {}
    project = parse_kv(a.overrides.read_text())
    layers.append(("device", device))
    layers.append(("project", project))
    resolved = resolve(decls, layers)

    for key in project:
        if key not in resolved:
            raise SystemExit(f"override for undeclared flag {key}")

    # device.txt can never beat a project override, so say so loudly when they disagree.
    clashes = conflicts(device, project)
    for (pkg, name), dev, proj in clashes:
        print(f"WARNING: project override {pkg}.{name}={str(proj).lower()} contradicts "
              f"{a.device.as_posix()} ({str(dev).lower()}); the project override wins; review it",
              file=sys.stderr)

    report = ["# Generated flag values", "", "| flag | value | source |", "|---|---|---|"]
    for pkg, flags in sorted(packages.items()):
        out_dir = a.out / Path(*pkg.split("."))
        out_dir.mkdir(parents=True, exist_ok=True)
        pairs = [(d.name, resolved[(pkg, d.name)][0]) for d in flags]
        (out_dir / "Flags.java").write_text(render_flags_java(pkg, pairs), encoding="utf-8", newline="\n")
        for d in flags:
            v, src = resolved[(pkg, d.name)]
            if v or src in ("device", "project"):
                report.append(f"| `{pkg}.{d.name}` | {str(v).lower()} | {src} |")
    if clashes:
        report += ["", "## Conflicts (project override vs device)", "",
                   "The project override is used. Review each line in "
                   "`flags/values/project-overrides.txt` against the device value.", "",
                   "| flag | device | project override (used) |", "|---|---|---|"]
        report += [f"| `{pkg}.{name}` | {str(dev).lower()} | {str(proj).lower()} |"
                   for (pkg, name), dev, proj in clashes]
    a.report.write_text("\n".join(report) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(packages)} packages, {len(decls)} flags")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
