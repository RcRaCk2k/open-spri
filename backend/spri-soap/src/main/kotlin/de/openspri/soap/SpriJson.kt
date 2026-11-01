package de.openspri.soap

import jakarta.xml.bind.annotation.XmlElement
import jakarta.xml.bind.annotation.XmlSeeAlso
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar

/** The JSON does not fit the S/PRI schema; [path] points to the problem (REST: 400). */
class SpriJsonException(val path: String, message: String) : IllegalArgumentException("$path: $message")

/**
 * JSON <-> S/PRI schema objects (JAXB classes generated from Anlage 3).
 *
 * The JSON uses the element names of the XSD 1:1 - the specification is the documentation of the REST API,
 * there is no second data model. Rules:
 * - element name = JSON property, text/date/boolean values as JSON values, repeated elements as arrays
 * - abstract XSD types (e.g. `geschaeftsfall`, `geschaeftsfallProdukt`) need `"@type"` with the XSD type name,
 *   e.g. `{"@type": "BereitstellungType", …}` (in XML: xsi:type)
 * - unknown properties are rejected with the list of allowed names; the XSD check follows when marshalling
 */
object SpriJson {

    private const val TYPE = "@type"
    private val datatypes = DatatypeFactory.newInstance()
    private val classes: Map<String, Class<*>> by lazy {
        packages.flatMap { pkg ->
            runCatching { Class.forName("$pkg.ObjectFactory") }.getOrNull()?.methods.orEmpty()
                .map { it.returnType }.filter { it.name.startsWith("de.telekom.spri") }
        }.flatMap { root -> hierarchyDown(root) }.flatMap { listOf(xsdName(it) to it, it.simpleName to it) }.toMap()
    }

    /** The XSD type name (e.g. `MeldungstypZWM-LEType`); the Java class name drops the hyphen. */
    fun xsdName(type: Class<*>): String =
        type.getAnnotation(jakarta.xml.bind.annotation.XmlType::class.java)?.name?.takeIf { it.isNotEmpty() && it != "##default" } ?: type.simpleName

    /** Element names of an XSD type, e.g. to fill attributes only where the type has them. */
    fun elementsOf(xsdTypeName: String): Set<String> = classes[xsdTypeName]?.let { fields(it).keys } ?: emptySet()
    private val packages = listOf("envelope", "order", "message", "complex", "fttx", "enm", "simple").map { "de.telekom.spri.oss.v4.$it" }

    // --- JSON -> JAXB ----------------------------------------------------------------------------

    fun <T> fromJson(node: JsonNode, type: Class<T>, path: String = "$"): T {
        if (!node.isObject) throw SpriJsonException(path, "Objekt erwartet")
        val concrete = concreteType(node, type, path)
        val instance = concrete.getDeclaredConstructor().newInstance()
        val fields = fields(concrete)
        for ((name, value) in node.properties()) {
            if (name == TYPE) continue
            val field = fields[name] ?: throw SpriJsonException(
                "$path.$name", "unbekanntes Feld in ${concrete.simpleName}; zulässig: ${fields.keys.sorted().joinToString()}",
            )
            field.isAccessible = true
            if (List::class.java.isAssignableFrom(field.type)) {
                val elementType = (field.genericType as ParameterizedType).actualTypeArguments.first() as Class<*>
                @Suppress("UNCHECKED_CAST")
                val list = field.get(instance) as? MutableList<Any> ?: mutableListOf<Any>().also { field.set(instance, it) }
                val items = if (value.isArray) value.toList() else listOf(value)
                items.forEachIndexed { i, item -> list += convert(item, elementType, "$path.$name[$i]") }
            } else {
                if (!value.isNull) field.set(instance, convert(value, field.type, "$path.$name"))
            }
        }
        @Suppress("UNCHECKED_CAST")
        return instance as T
    }

    private fun convert(value: JsonNode, type: Class<*>, path: String): Any = when {
        type == String::class.java -> scalar(value, path)
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType ->
            if (value.isBoolean) value.booleanValue() else scalar(value, path).toBooleanStrictOrNull() ?: throw SpriJsonException(path, "true oder false erwartet")
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> scalar(value, path).toIntOrNull() ?: throw SpriJsonException(path, "Zahl erwartet")
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> scalar(value, path).toLongOrNull() ?: throw SpriJsonException(path, "Zahl erwartet")
        type == BigInteger::class.java -> scalar(value, path).toBigIntegerOrNull() ?: throw SpriJsonException(path, "Zahl erwartet")
        type == BigDecimal::class.java -> scalar(value, path).toBigDecimalOrNull() ?: throw SpriJsonException(path, "Zahl erwartet")
        type == ByteArray::class.java -> try { Base64.getDecoder().decode(scalar(value, path)) } catch (e: IllegalArgumentException) { throw SpriJsonException(path, "Base64 erwartet") }
        XMLGregorianCalendar::class.java.isAssignableFrom(type) -> try {
            datatypes.newXMLGregorianCalendar(scalar(value, path))
        } catch (e: IllegalArgumentException) {
            throw SpriJsonException(path, "Datum/Zeit im XSD-Format erwartet, z. B. 2026-12-01 oder 2026-12-01T10:00:00+01:00")
        }
        type.isEnum -> {
            val text = scalar(value, path)
            runCatching { type.getMethod("fromValue", String::class.java).invoke(null, text) }.getOrNull()
                ?: throw SpriJsonException(path, "unzulässiger Wert '$text'; zulässig: ${type.enumConstants.joinToString { it.javaClass.getMethod("value").invoke(it).toString() }}")
        }
        type.name.startsWith("de.telekom.spri") -> fromJson(value, type, path)
        else -> throw SpriJsonException(path, "nicht unterstützter Typ ${type.simpleName}")
    }

    private fun scalar(value: JsonNode, path: String): String {
        if (!value.isValueNode) throw SpriJsonException(path, "Wert erwartet, kein Objekt/Array")
        return value.asString()
    }

    private fun concreteType(node: JsonNode, declared: Class<*>, path: String): Class<*> {
        val named = node.get(TYPE)?.asString()
        if (named == null) {
            if (Modifier.isAbstract(declared.modifiers)) {
                val options = hierarchyDown(declared).filter { !Modifier.isAbstract(it.modifiers) && declared.isAssignableFrom(it) }.map(::xsdName).sorted()
                throw SpriJsonException(path, "abstrakter Typ ${declared.simpleName}: \"$TYPE\" angeben, eines von $options")
            }
            return declared
        }
        val type = classes[named] ?: throw SpriJsonException("$path.$TYPE", "unbekannter Typ '$named'")
        if (!declared.isAssignableFrom(type)) throw SpriJsonException("$path.$TYPE", "$named ist kein ${declared.simpleName}")
        return type
    }

    // --- JAXB -> JSON ----------------------------------------------------------------------------

    fun toJson(value: Any, declared: Class<*> = value.javaClass): ObjectNode {
        val node = JsonNodeFactory.instance.objectNode()
        if (value.javaClass != declared) node.put(TYPE, xsdName(value.javaClass))
        for ((name, field) in fields(value.javaClass)) {
            field.isAccessible = true
            val v = field.get(value) ?: continue
            when {
                v is List<*> -> {
                    if (v.isEmpty()) continue
                    val elementType = (field.genericType as ParameterizedType).actualTypeArguments.first() as Class<*>
                    val array = node.putArray(name)
                    v.filterNotNull().forEach { item -> if (isComplex(item)) array.add(toJson(item, elementType)) else array.add(text(item)) }
                }
                v is Boolean -> node.put(name, v)
                isComplex(v) -> node.set(name, toJson(v, field.type))
                else -> node.put(name, text(v))
            }
        }
        return node
    }

    private fun isComplex(v: Any) = v.javaClass.name.startsWith("de.telekom.spri") && !v.javaClass.isEnum

    private fun text(v: Any): String = when {
        v.javaClass.isEnum -> v.javaClass.getMethod("value").invoke(v).toString()
        v is ByteArray -> Base64.getEncoder().encodeToString(v)
        else -> v.toString()
    }

    // --- reflection helpers ----------------------------------------------------------------------

    /** Element name -> field, including superclasses; order as in the class (= XSD sequence). */
    private fun fields(type: Class<*>): Map<String, Field> {
        val chain = generateSequence(type) { it.superclass?.takeIf { s -> s != Any::class.java } }.toList().reversed()
        return chain.flatMap { it.declaredFields.toList() }.filter { !Modifier.isStatic(it.modifiers) }
            .associateBy { it.getAnnotation(XmlElement::class.java)?.name?.takeIf { n -> n != "##default" } ?: it.name }
    }

    private fun hierarchyDown(type: Class<*>): Set<Class<*>> {
        val result = mutableSetOf<Class<*>>()
        fun walk(t: Class<*>) {
            if (!result.add(t)) return
            t.getAnnotation(XmlSeeAlso::class.java)?.value?.forEach { walk(it.java) }
            t.declaredFields.forEach { f ->
                val ft = (f.genericType as? ParameterizedType)?.actualTypeArguments?.first() as? Class<*> ?: f.type
                if (ft.name.startsWith("de.telekom.spri")) walk(ft)
            }
            t.declaredClasses.forEach(::walk)
        }
        walk(type)
        return result
    }
}
