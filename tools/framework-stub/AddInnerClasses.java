// Completes the InnerClasses attributes of a dex2jar-produced jar, in place.
//
//   java -cp <asm jars> tools/framework-stub/AddInnerClasses.java <jar> [<device fingerprint>]
//
// Dex keeps a class's own nesting (InnerClass / EnclosingClass / MemberClasses annotations), which
// dex2jar turns back into that class's InnerClasses entries; but the JVM spec also wants an
// InnerClasses entry in every class file that merely REFERENCES a nested class (JVMS 4.7.6), and
// that information does not survive dexing. javac and kotlinc tolerate the gap. KSP2 (the Kotlin
// Analysis API) does not: it maps a binary name such as android/window/IRemoteTransition$Stub
// without an entry to a top-level class "IRemoteTransition$Stub", fails to resolve it, and Dagger
// then rejects every @Inject class whose supertypes reach it (e.g. RemoteTransitionStub).
//
// Pass 1 collects every nesting relation the jar declares; pass 2 adds, to each class, an entry for
// every nested class its constant pool references (class constants and descriptors/signatures),
// together with that class's enclosing chain, outermost first. Existing entries are kept, method
// bodies and the constant pool are copied unchanged (ClassWriter(reader, 0)).
//
// Writes a marker entry, META-INF/hg-innerclasses, so a stub built by an older copy of this
// script (or a stale jar copied in from elsewhere) is unambiguously distinguishable from one this
// fix has actually run over: DeviceFramework.kt's useDeviceFramework() checks for it and fails
// the build with a clear message otherwise, rather than failing deep inside KSP as it did before
// this fix existed (see aosp/PATCHES.md / task-7 report §4 fix 1). With a device fingerprint
// argument the marker also carries a `fingerprint=` line, which useDeviceFramework() compares with
// tools/framework-stub/device-build.txt. The jar is replaced by renaming a temp file, which is
// deleted if anything fails.
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public class AddInnerClasses {
    static final String MARKER = "META-INF/hg-innerclasses";

    record Nesting(String name, String outer, String inner, int access) {}

    private static final Pattern REF = Pattern.compile("L([^;<>\\[\\s]+)[;<]");
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_UTF8 = 1;

    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }

        Map<String, Nesting> nesting = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitInnerClass(String name, String outer, String inner, int access) {
                    nesting.putIfAbsent(name, new Nesting(name, outer, inner, access));
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        int changedClasses = 0;
        long addedEntries = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            ClassReader reader = new ClassReader(entry.getValue());
            Set<String> declared = new LinkedHashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitInnerClass(String name, String outer, String inner, int access) {
                    declared.add(name);
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            List<Nesting> missing = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(declared);
            for (String ref : referencedClasses(reader)) {
                // Enclosing chain of ref, outermost first.
                List<Nesting> chain = new ArrayList<>();
                for (Nesting n = nesting.get(ref); n != null; n = n.outer() == null ? null
                        : nesting.get(n.outer())) {
                    chain.add(0, n);
                    if (n.outer() == null) break; // local/anonymous: no enclosing member chain
                }
                for (Nesting n : chain) {
                    if (seen.add(n.name())) missing.add(n);
                }
            }
            if (missing.isEmpty()) continue;

            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public void visitEnd() {
                    for (Nesting n : missing) {
                        super.visitInnerClass(n.name(), n.outer(), n.inner(), n.access());
                    }
                    super.visitEnd();
                }
            }, 0);
            entry.setValue(writer.toByteArray());
            changedClasses++;
            addedEntries += missing.size();
        }

        String marker = "InnerClasses entries completed: " + addedEntries + " added to "
                + changedClasses + " classes\n"
                + (args.length > 1 ? "fingerprint=" + args[1] + "\n" : "");
        Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
        boolean replaced = false;
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    if (entry.getKey().equals(MARKER)) continue; // rewritten below, not copied twice
                    out.putNextEntry(new ZipEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
                out.putNextEntry(new ZipEntry(MARKER));
                out.write(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) Files.deleteIfExists(tmp);
        }
        System.out.println("InnerClasses completed: " + addedEntries + " entries added to "
                + changedClasses + " classes");
    }

    /** Internal names referenced by class constants and by descriptors/signatures in UTF8s. */
    private static Set<String> referencedClasses(ClassReader reader) {
        Set<String> refs = new LinkedHashSet<>();
        char[] buf = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) continue; // second slot of a long/double
            int tag = reader.readByte(offset - 1);
            if (tag == CONSTANT_CLASS) {
                String name = reader.readUTF8(offset, buf);
                if (name.startsWith("[")) {
                    addDescriptorRefs(name, refs);
                } else {
                    refs.add(name);
                }
            } else if (tag == CONSTANT_UTF8) {
                String value = readRawUtf8(reader, offset);
                if (value.indexOf('L') >= 0 && value.indexOf(';') >= 0) {
                    addDescriptorRefs(value, refs);
                }
            }
        }
        return refs;
    }

    private static void addDescriptorRefs(String text, Set<String> refs) {
        Matcher m = REF.matcher(text);
        while (m.find()) refs.add(m.group(1));
    }

    /** A CONSTANT_Utf8 item: u2 length + modified UTF-8, exactly DataInput.readUTF's format. */
    @SuppressWarnings("deprecation") // ClassReader.b: the class file bytes
    private static String readRawUtf8(ClassReader reader, int offset) {
        int length = reader.readUnsignedShort(offset);
        try {
            return new DataInputStream(new ByteArrayInputStream(reader.b, offset, 2 + length))
                    .readUTF();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
