package hg

import java.io.File

/**
 * The device's binder transaction codes (prebuilts/device/hyperos4-*-aidl-tables.txt) checked
 * against the AIDL stubs this module compiles. Shared by the unit tests of :wmshell-shared
 * and :sysui-shared
 * (AospModule.kt aidlTableTests()).
 *
 * Table format: a `<source> -> <fully.qualified.IInterface>` line starts an interface; each row
 * under it is `<code>  <sync|oneway>  <return type> <method>(<parameters>)`. `#` lines and the
 * `====` rulers are ignored.
 */
object AidlTables {
    /** One interface's rows: device transaction code -> method name. */
    data class Table(val iface: String, val codes: Map<Int, String>)

    /** [matched]: per compiled interface, the table rows whose method our stub also has. */
    data class Result(val matched: Map<String, Int>, val problems: List<String>)

    private val header = Regex("""^\S.* -> ([\w.$]+)\s*$""")
    private val row = Regex("""^\s+(\d+)\s+(?:sync|oneway)\s+[^(]*?(\w+)\(""")

    fun parse(file: File): List<Table> {
        val tables = mutableListOf<Table>()
        var iface: String? = null
        var codes = linkedMapOf<Int, String>()
        fun flush() {
            iface?.let { tables += Table(it, codes) }
        }
        for (line in file.readLines()) {
            if (line.startsWith("#") || line.startsWith("====") || line.isBlank()) continue
            val h = header.find(line)
            if (h != null) {
                flush()
                iface = h.groupValues[1]
                codes = linkedMapOf()
                continue
            }
            val r = row.find(line) ?: error("${file.name}: unparsed line: $line")
            val name = checkNotNull(iface) { "${file.name}: row before any interface: $line" }
            val code = r.groupValues[1].toInt()
            check(codes.put(code, r.groupValues[2]) == null) { "${file.name}: $name lists code $code twice" }
        }
        flush()
        check(tables.isNotEmpty()) { "${file.name}: no interfaces parsed" }
        return tables
    }

    /** `TRANSACTION_*` codes of a compiled AIDL stub by method name, or null if it isn't compiled. */
    fun stubCodes(iface: String): Map<String, Int>? {
        val stub = try {
            Class.forName("$iface\$Stub")
        } catch (e: ClassNotFoundException) {
            return null
        }
        return stub.declaredFields
            .filter { it.name.startsWith("TRANSACTION_") && it.type == Int::class.javaPrimitiveType }
            .associate { f -> f.isAccessible = true; f.name.removePrefix("TRANSACTION_") to f.getInt(null) }
    }

    /**
     * Every table whose interface is compiled: a method both sides have must use the same code, and
     * a code both sides use must be the same method (else our call would run another device method).
     */
    fun check(tables: List<Table>): Result {
        val matched = linkedMapOf<String, Int>()
        val problems = mutableListOf<String>()
        for (t in tables) {
            val ours = stubCodes(t.iface) ?: continue
            var n = 0
            for ((code, name) in t.codes) {
                val our = ours[name] ?: continue
                n++
                if (our != code) problems += "${t.iface}.$name: device code $code, ours $our"
            }
            for ((name, code) in ours) {
                val device = t.codes[code]
                if (device != null && device != name) {
                    problems += "${t.iface} code $code: device runs $device, ours is $name"
                }
            }
            matched[t.iface] = n
        }
        return Result(matched, problems)
    }
}
