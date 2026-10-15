"""Pre-resolves `android:featureFlag` attributes in vendored AOSP resources/manifests.

Soong resolves `android:featureFlag` at build time using aconfig values (build map
§3.7); aapt2 driven from Gradle/AGP cannot, so we resolve these attributes ourselves
using the same generated flag values (flags/VALUES.md) and commit the result as an
`aosp-patch:` commit.

The transform is text-level (regex over the raw source), not a DOM round-trip: an XML
parse-and-reserialize would reformat every vendored file and bury the intended change
in reformatting noise. A small tag scanner finds start tags, decides keep/remove from
the flag values, and edits only the bytes that need to change.

Also provides `strip_manifest_package`, used per-module in later tasks when a vendored
manifest becomes a Gradle module manifest (AGP 8+ wants `namespace` in the DSL, not a
source `package=`).

Private framework resource references (`@*android:…`, `@androidprv:…`, `?androidprv:attr/…`):
AGP always passes `-I android.jar` first to `aapt2 link`, and aapt2 resolves a name against
the first include that defines it, so these link silently to the SDK's *private* IDs, which
differ on the device (e.g. dimen/status_bar_height 0x01050368 in SDK 37 vs 0x010503a2 on
HyperOS 4). `rewrite_private_refs` replaces each one with the public `@android:` reference of
the same name when SDK 37 exports it, otherwise with the device framework-res value
(`device_values`: a public resource reached through the device's own reference chain, or a
literal). What cannot be resolved that way is reported and left alone. `--check-private` is
the build guard (Gradle task `checkPrivateRes`): it fails on any remaining private reference
not listed, with a reason, in tools/hgtools/private-refs-allowlist.txt.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

# A start/end tag, from '<' up to (not including) the terminating '>'. Attribute
# values in these files never contain a literal '>' (it would have to be &gt;-escaped
# in valid XML), so scanning up to the next '>' is safe. Comments and `<?xml …?>`
# processing instructions don't start with a name character after '<' (or '</'), so
# they never match and pass through untouched.
_TAG = re.compile(r'<(?P<closing>/?)(?P<name>[A-Za-z_][\w.\-]*)(?P<attrs>[^>]*)>')

# The featureFlag attribute together with whatever whitespace/newline precedes it, so
# deleting the match removes a line that holds only this attribute.
_FEATURE_FLAG_ATTR = re.compile(r'\s+android:featureFlag="([^"]*)"')

_MANIFEST_TAG = re.compile(r'<manifest\b[^>]*>', re.DOTALL)
_PACKAGE_ATTR = re.compile(r'\s+package="[^"]*"')

_VALUES_ROW = re.compile(r'^\|\s*`([^`]+)`\s*\|\s*(true|false)\s*\|')


def _is_self_closing(attrs_text: str) -> bool:
    return attrs_text.rstrip().endswith('/')


def _flag_true(flag_expr: str, values: dict) -> bool:
    negated = flag_expr.startswith('!')
    name = flag_expr[1:] if negated else flag_expr
    value = bool(values.get(name, False))
    return (not value) if negated else value


def resolve_feature_flags(xml_text: str, values: dict) -> str:
    """Resolves every `android:featureFlag="[!]pkg.name"` in xml_text against values.

    A flag absent from `values` is false. When the (possibly negated) flag is true,
    only the attribute is dropped and the element is kept. When it is false, the whole
    element is dropped: for a self-closing tag that's its own line; for a tag with
    children, the matching end tag is found by tracking nesting depth of the *same*
    tag name, and everything from the start tag's line through the end tag's line
    (inclusive of its trailing newline) is removed.
    """
    tags = list(_TAG.finditer(xml_text))

    def line_start(pos):
        nl = xml_text.rfind('\n', 0, pos)
        return nl + 1

    def line_end_incl(pos):
        nl = xml_text.find('\n', pos)
        return nl + 1 if nl != -1 else len(xml_text)

    deletions = []
    i = 0
    n = len(tags)
    while i < n:
        tag = tags[i]
        if tag.group('closing'):
            i += 1
            continue
        attrs_text = tag.group('attrs')
        fm = _FEATURE_FLAG_ATTR.search(attrs_text)
        if not fm:
            i += 1
            continue
        keep = _flag_true(fm.group(1), values)
        if keep:
            attrs_start = tag.start('attrs')
            deletions.append((attrs_start + fm.start(), attrs_start + fm.end()))
            i += 1
            continue

        name = tag.group('name')
        if _is_self_closing(attrs_text):
            deletions.append((line_start(tag.start()), line_end_incl(tag.end())))
            i += 1
            continue

        # Track nesting depth of the same tag name to find the matching end tag.
        depth = 1
        j = i + 1
        end_tag = None
        while j < n:
            other = tags[j]
            if other.group('name') == name:
                if other.group('closing'):
                    depth -= 1
                    if depth == 0:
                        end_tag = other
                        break
                elif not _is_self_closing(other.group('attrs')):
                    depth += 1
            j += 1
        if end_tag is None:
            raise ValueError(f"unmatched <{name}> while removing featureFlag element")
        deletions.append((line_start(tag.start()), line_end_incl(end_tag.end())))
        i = j + 1

    deletions.sort()
    merged = []
    for start, end in deletions:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))

    out = []
    pos = 0
    for start, end in merged:
        out.append(xml_text[pos:start])
        pos = end
    out.append(xml_text[pos:])
    return "".join(out)


def strip_manifest_package(xml_text: str) -> str:
    """Removes `package="…"` from the `<manifest` start tag only."""
    m = _MANIFEST_TAG.search(xml_text)
    if not m:
        return xml_text
    new_tag = _PACKAGE_ATTR.sub("", m.group(0), count=1)
    return xml_text[:m.start()] + new_tag + xml_text[m.end():]


def parse_values_md(text: str) -> dict:
    values = {}
    for line in text.splitlines():
        m = _VALUES_ROW.match(line.strip())
        if m:
            values[m.group(1)] = m.group(2) == "true"
    return values


# ---------------------------------------------------------------------------------------------
# Private framework resource references
# ---------------------------------------------------------------------------------------------

# `@*android:type/name`, `@androidprv:type/name` (resource references) and
# `?*android:attr/name`, `?androidprv:attr/name`, `?androidprv:name` (theme attributes; the
# type may be omitted after `?`). Public `@android:`/`?android:` never match: they need the
# `*` or the `prv` suffix. `xmlns:androidprv="…"` never matches: no `@`/`?` sigil before it.
_PRIVATE_REF = re.compile(
    r'(?P<sigil>[@?])(?:\*android|\*?androidprv):'
    r'(?:(?P<type>[a-z][a-z_\-]*)/)?(?P<name>[A-Za-z_][\w.]*)')

# A style item keyed by a private attribute: `<item name="*android:foo">`.
_PRIVATE_ITEM_KEY = re.compile(
    r"""\bname=(?P<q>["'])(?P<ref>(?:\*android|\*?androidprv):(?P<name>[A-Za-z_][\w.]*))(?P=q)""")

# An element attribute in the private namespace: `<View androidprv:foo="…">`.
_PRIVATE_XML_ATTR = re.compile(r'(?<=\s)(?P<ref>androidprv:(?P<name>[A-Za-z_]\w*))(?=\s*=)')

_COMMENT = re.compile(r'<!--.*?-->', re.DOTALL)


@dataclass(frozen=True)
class PrivateRef:
    ref: str      # the reference exactly as written
    kind: str     # "resource" | "theme-attr" | "style-item" | "xml-attr"
    type: str
    name: str
    line: int     # 1-based
    start: int
    end: int


@dataclass(frozen=True)
class RewriteEntry:
    ref: str
    kind: str
    type: str
    name: str
    line: int
    action: str                   # "public" | "device-public" | "literal" | "unresolved"
    replacement: Optional[str] = None
    reason: Optional[str] = None


def _comment_spans(text: str):
    return [(m.start(), m.end()) for m in _COMMENT.finditer(text)]


def _in_spans(pos: int, spans) -> bool:
    return any(a <= pos < b for a, b in spans)


def find_private_refs(xml_text: str) -> list:
    """Every private framework reference outside XML comments, in source order."""
    comments = _comment_spans(xml_text)
    found = []

    def add(ref, kind, rtype, name, start, end):
        if not _in_spans(start, comments):
            line = xml_text.count("\n", 0, start) + 1
            found.append(PrivateRef(ref, kind, rtype, name, line, start, end))

    for m in _PRIVATE_REF.finditer(xml_text):
        rtype = m.group("type")
        if m.group("sigil") == "?":
            if rtype not in (None, "attr"):
                continue
            add(m.group(0), "theme-attr", "attr", m.group("name"), m.start(), m.end())
        elif rtype is not None:
            add(m.group(0), "resource", rtype, m.group("name"), m.start(), m.end())
    for m in _PRIVATE_ITEM_KEY.finditer(xml_text):
        add(m.group("ref"), "style-item", "attr", m.group("name"),
            m.start("ref"), m.end("ref"))
    for m in _PRIVATE_XML_ATTR.finditer(xml_text):
        add(m.group("ref"), "xml-attr", "attr", m.group("name"), m.start("ref"), m.end("ref"))
    found.sort(key=lambda r: r.start)
    return found


def _inside_tag(text: str, pos: int) -> bool:
    """True when pos sits inside `<…>` (an attribute value), False in element text."""
    return text.rfind("<", 0, pos) > text.rfind(">", 0, pos)


def _escape_literal(value: str, rtype: str, in_attribute: bool) -> str:
    if rtype == "string":
        # aapt2 string semantics: unescaped quotes are stripped and a leading @ or ? makes the
        # value a reference.
        value = value.replace("\\", "\\\\").replace('"', '\\"').replace("'", "\\'")
        if value[:1] in ("@", "?"):
            value = "\\" + value
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if in_attribute:
        value = value.replace('"', "&quot;")
    return value


def rewrite_private_refs(xml_text: str, public_names: set, device_values: dict,
                         reasons: Optional[dict] = None):
    """Rewrites private framework references; returns (new_text, [RewriteEntry]).

    - `(type, name)` public in SDK 37 → `@android:type/name` (`?android:attr/name` for theme
      attributes): action "public".
    - otherwise a resource reference takes `device_values[(type, name)]`: a public `@android:`
      reference reached through the device's own reference chain ("device-public"), or a
      literal ("literal").
    - everything else (theme attributes and style keys with no public attr of that name,
      resources missing from `device_values`) is left untouched and reported "unresolved",
      with `reasons[(type, name)]` when given. Nothing is guessed.
    Only the reference's own characters change; comments are never touched.
    """
    reasons = reasons or {}
    edits = []
    report = []
    for ref in find_private_refs(xml_text):
        key = (ref.type, ref.name)
        action, replacement, reason = "unresolved", None, None
        if ref.kind == "theme-attr":
            if key in public_names:
                action, replacement = "public", f"?android:attr/{ref.name}"
            else:
                reason = "no public attr of this name (theme attributes cannot be inlined)"
        elif ref.kind == "resource":
            if key in public_names:
                action, replacement = "public", f"@android:{ref.type}/{ref.name}"
            elif key in device_values:
                value = device_values[key]
                action = "device-public" if value.startswith("@android:") else "literal"
                replacement = value
            else:
                reason = reasons.get(key, "no device value")
        else:
            reason = f"{ref.kind} naming a private attr; no automatic rewrite, fix by hand"
        if replacement is not None:
            text = replacement
            if action == "literal":
                text = _escape_literal(replacement, ref.type, _inside_tag(xml_text, ref.start))
            edits.append((ref.start, ref.end, text))
        report.append(RewriteEntry(ref.ref, ref.kind, ref.type, ref.name, ref.line, action,
                                   replacement, reason))
    out = []
    pos = 0
    for start, end, text in edits:
        out.append(xml_text[pos:start])
        out.append(text)
        pos = end
    out.append(xml_text[pos:])
    return "".join(out), report


# --- SDK public names --------------------------------------------------------------------------

def parse_public_xml(xml_text: str) -> set:
    """(type, name) pairs a framework public*.xml exports.

    Covers `<public type= name= id=/>` and the `<staging-public-group[-final] type=…>` blocks
    (whose `<public name=…/>` children take the group's type). `removed_*` placeholders are
    skipped.
    """
    names = set()
    for el in ET.fromstring(xml_text):
        if el.tag == "public":
            rtype, name = el.get("type"), el.get("name")
            if rtype and name and not name.startswith("removed_"):
                names.add((rtype, name))
        elif el.tag.startswith("staging-public-group"):
            rtype = el.get("type")
            for child in el:
                name = child.get("name") if child.tag == "public" else None
                if name and not name.startswith("removed_"):
                    names.add((rtype, name))
    return names


def load_public_names(sdk_platform: Path) -> set:
    """Union of `<platform>/data/res/values/public*.xml` (public-final + public-staging)."""
    files = sorted((sdk_platform / "data" / "res" / "values").glob("public*.xml"))
    if not files:
        raise FileNotFoundError(f"no public*.xml under {sdk_platform}/data/res/values")
    names = set()
    for f in files:
        names |= parse_public_xml(_read(f))
    return names


# --- Device framework-res values ---------------------------------------------------------------

@dataclass
class DeviceEntry:
    id: int
    public: bool
    configs: dict  # config qualifier ("" = default) -> value text as aapt2 prints it


_DUMP_RESOURCE = re.compile(r'^    resource 0x(?P<id>[0-9a-fA-F]{8}) (?P<type>[^/\s]+)/'
                            r'(?P<name>\S+)(?P<flags>.*)$')
_DUMP_VALUE = re.compile(r'^      \((?P<config>[^)]*)\) (?P<value>.*)$')


def parse_aapt2_dump(text: str) -> dict:
    """Parses `aapt2 dump resources <apk>` output into {(type, name): DeviceEntry}.

    `^attr-private` entries are keyed as `attr` (that is how `?androidprv:attr/x` names them).
    A name listed twice (staged aliases in the extra `Package` sections) keeps the first
    entry, ORs the PUBLIC flag and fills in configs it lacks. Continuation lines of
    multi-line values (style items, array elements) are indented deeper and ignored.
    """
    entries = {}
    current = None
    for line in text.splitlines():
        m = _DUMP_RESOURCE.match(line)
        if m:
            rtype = "attr" if m.group("type") == "^attr-private" else m.group("type")
            key = (rtype, m.group("name"))
            public = " PUBLIC" in m.group("flags")
            if key in entries:
                current = entries[key]
                current.public = current.public or public
            else:
                current = entries[key] = DeviceEntry(int(m.group("id"), 16), public, {})
            continue
        v = _DUMP_VALUE.match(line)
        if v and current is not None:
            current.configs.setdefault(v.group("config"), v.group("value"))
    return entries


_LITERAL_TYPES = {"color", "dimen", "integer", "bool", "string", "fraction"}
_DIMEN = re.compile(r'^(?P<num>-?\d+(?:\.\d+)?)(?P<unit>dp|dip|sp|px|pt|in|mm)$')
_DUMP_REF = re.compile(r'^@(?:android:)?(?P<type>[a-z][a-z_\-]*)/(?P<name>[A-Za-z_][\w.]*)$')


def _normalize_literal(rtype: str, value: str) -> Optional[str]:
    if value.startswith("("):
        return None
    if rtype == "string":
        if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
            return re.sub(r'\\(.)', r'\1', value[1:-1])
        return None
    if rtype == "dimen":
        m = _DIMEN.match(value)
        if m:
            num = m.group("num")
            if "." in num:
                num = num.rstrip("0").rstrip(".")
            return f"{num}{m.group('unit')}"
    return value


def _describe(value: str) -> str:
    if value.startswith("(file)"):
        return f"file-based resource ({value.split()[1]})"
    if value.startswith("("):
        return f"non-literal value {value.split(')')[0]})"
    return f"unsupported value {value!r}"


def _resolve_device(key, entries, public_names, seen):
    entry = entries.get(key)
    if entry is None:
        return None, f"@{key[0]}/{key[1]} not in device framework-res"
    if key[0] not in _LITERAL_TYPES:
        return None, f"type {key[0]} cannot be inlined"
    default = entry.configs.get("")
    if default is None:
        return None, f"no default-config value (configs: {', '.join(sorted(entry.configs))})"
    differing = sorted(c for c, v in entry.configs.items() if c and v != default)
    if default.startswith("("):
        also = f"; also config-dependent ({', '.join(differing)})" if differing else ""
        return None, _describe(default) + also
    if differing:
        return None, f"config-dependent on device ({', '.join(differing)})"
    if default in ("@null", "@empty"):
        return default, None
    ref = _DUMP_REF.match(default)
    if ref:
        target = (ref.group("type"), ref.group("name"))
        if target in public_names:
            return f"@android:{target[0]}/{target[1]}", None
        if target in seen:
            return None, "reference cycle"
        value, reason = _resolve_device(target, entries, public_names, seen | {target})
        if reason is not None:
            reason = f"via @{target[0]}/{target[1]}: {reason}"
        return value, reason
    if default.startswith(("@", "?")):
        return None, f"unsupported reference {default}"
    literal = _normalize_literal(key[0], default)
    if literal is None:
        return None, _describe(default)
    return literal, None


def device_values(entries: dict, public_names: set):
    """({(type, name): replacement}, {(type, name): why there is none}) for every device entry.

    The replacement is the default-config value after following the device's reference chain:
    the first public resource in the chain as `@android:type/name` (keeps runtime theming, and
    public IDs are stable), else the literal the chain ends in (dimens normalized, e.g.
    56.000000dp → 56dp; strings unquoted). There is none when a non-public hop is
    config-dependent (a literal would freeze one configuration, e.g. drop night mode), is
    file-based (color state list, drawable), is a theme attribute, or is not a literal type.
    """
    values, reasons = {}, {}
    for key in entries:
        value, reason = _resolve_device(key, entries, public_names, {key})
        if value is not None:
            values[key] = value
        else:
            reasons[key] = reason
    return values, reasons


def load_device_entries(apk: Path, aapt2: str) -> dict:
    import subprocess
    out = subprocess.run([aapt2, "dump", "resources", str(apk)], check=True,
                         capture_output=True, text=True, encoding="utf-8").stdout
    return parse_aapt2_dump(out)


# --- Build guard and rewrite CLI ---------------------------------------------------------------

def load_allowlist(path: Path) -> dict:
    """`<repo-relative path> | <ref as written> | <reason>` per line; `#` starts a comment."""
    allowed = {}
    for n, raw in enumerate(_read(path).splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) != 3 or not all(parts):
            raise ValueError(f"{path}:{n}: expected 'path | ref | reason', got {raw!r}")
        allowed[(parts[0], parts[1])] = parts[2]
    return allowed


def _xml_files(paths):
    for p in paths:
        p = Path(p)
        if p.is_dir():
            yield from sorted(q for q in p.rglob("*.xml") if q.is_file())
        elif p.is_file():
            yield p


def _rel(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def check_private(paths, root: Path, allowlist: Optional[Path]) -> int:
    allowed = load_allowlist(allowlist) if allowlist else {}
    bad = []
    count = 0
    for f in _xml_files(paths):
        count += 1
        rel = _rel(f, root)
        for ref in find_private_refs(_read(f)):
            if (rel, ref.ref) not in allowed:
                bad.append(f"{rel}:{ref.line}: {ref.ref}")
    if bad:
        print(f"{len(bad)} private framework resource reference(s) would link to SDK-private "
              "IDs, which are wrong on the device. Rewrite them (python -m "
              "tools.hgtools.res_preprocess --rewrite-private ...) or allowlist them with a "
              "reason in tools/hgtools/private-refs-allowlist.txt:")
        for b in bad:
            print(f"  {b}")
        return 1
    print(f"private-ref check: {count} xml files clean")
    return 0


def rewrite_private(paths, root: Path, sdk_platform: Path, device_res: Path, aapt2: str) -> int:
    public = load_public_names(sdk_platform)
    values, reasons = device_values(load_device_entries(device_res, aapt2), public)
    rows = []
    for f in _xml_files(paths):
        text = _read(f)
        out, report = rewrite_private_refs(text, public, values, reasons)
        if out != text:
            _write(f, out)
        rows += [(_rel(f, root), e) for e in report]
    counts = {}
    for rel, e in rows:
        counts[e.action] = counts.get(e.action, 0) + 1
        detail = e.replacement if e.replacement is not None else f"({e.reason})"
        print(f"{e.action}\t{rel}:{e.line}\t{e.ref}\t{detail}")
    print("summary: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    return 0


_REPO_ROOT = Path(__file__).resolve().parents[2]


def _read(path: Path) -> str:
    # newline='' disables universal-newline translation: CRLF-checked-out vendored
    # files must come back out with CRLF intact, or the aosp-patch diff would touch
    # every line instead of just the intended ones.
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def _write(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def main(argv):
    ap = argparse.ArgumentParser()
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--files", nargs="+", type=Path,
                      help="resolve android:featureFlag in these files (needs --values)")
    mode.add_argument("--check-private", nargs="+", type=Path, metavar="PATH",
                      help="exit 1 on private framework refs in these files/dirs")
    mode.add_argument("--rewrite-private", nargs="+", type=Path, metavar="PATH",
                      help="rewrite private framework refs in these files/dirs; print a report")
    ap.add_argument("--values", type=Path)
    ap.add_argument("--root", type=Path, default=_REPO_ROOT,
                    help="paths are reported (and allowlisted) relative to this")
    ap.add_argument("--allowlist", type=Path)
    ap.add_argument("--sdk-platform", type=Path, help="e.g. $ANDROID_SDK/platforms/android-37.0")
    ap.add_argument("--device-res", type=Path, help="the device framework-res.apk")
    ap.add_argument("--aapt2", default="aapt2")
    a = ap.parse_args(argv)

    if a.check_private:
        return check_private(a.check_private, a.root, a.allowlist)
    if a.rewrite_private:
        if not (a.sdk_platform and a.device_res):
            ap.error("--rewrite-private needs --sdk-platform and --device-res")
        return rewrite_private(a.rewrite_private, a.root, a.sdk_platform, a.device_res, a.aapt2)
    if a.values is None:
        ap.error("--files needs --values")
    values = parse_values_md(_read(a.values))
    changed = 0
    for path in a.files:
        text = _read(path)
        out = resolve_feature_flags(text, values)
        if out != text:
            _write(path, out)
            changed += 1
    print(f"{changed}/{len(a.files)} files changed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
